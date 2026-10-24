package id.nusacred.custodian

import id.nusacred.common.*
import id.nusacred.contracts.Role
import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.Locale
import java.util.UUID

fun participantEmail(input: String): String {
    val email=input.trim().lowercase(Locale.ROOT)
    require(email.length in 3..254 && email.matches(Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,63}")))
    require(!email.contains(".."))
    return email
}

fun interface ParticipantDirectory { fun invite(email: String) }
class ParticipantExists : ApiFailure(409,"PARTICIPANT_EXISTS","This email already has an account. No invitation was sent.")

/** Separate additive database: wallet keys, sessions and recovery schema are untouched. */
class ParticipantInvitations(path: Path, private val directory: ParticipantDirectory?, private val now: () -> Instant = Instant::now) : AutoCloseable {
    private val db=DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
    init {
        db.createStatement().use { s ->
            s.execute("PRAGMA busy_timeout=5000")
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("PRAGMA synchronous=FULL")
            s.execute("""CREATE TABLE IF NOT EXISTS invitations(id TEXT PRIMARY KEY,email TEXT UNIQUE NOT NULL,
                actor TEXT NOT NULL,key_hash TEXT UNIQUE NOT NULL,state TEXT NOT NULL,
                created INTEGER NOT NULL,expires INTEGER)""")
            s.execute("UPDATE invitations SET state='RECONCILE_REQUIRED' WHERE state='SENDING'")
        }
    }
    private fun execute(sql: String,vararg args: Any?) = db.prepareStatement(sql).use { statement ->
        args.forEachIndexed { i,value -> statement.setObject(i+1,value) };statement.executeUpdate()
    }
    private fun rows(sql: String,vararg args: Any?): List<Map<String,Any?>> = db.prepareStatement(sql).use { statement ->
        args.forEachIndexed { i,value -> statement.setObject(i+1,value) }
        statement.executeQuery().use { result -> buildList { while(result.next()) add(mapOf(
            "id" to result.getString("id"),"email" to result.getString("email"),"state" to result.getString("state"),
            "createdAt" to result.getLong("created"),"expiresAt" to result.getObject("expires"),
            "actor" to result.getString("actor"),"keyHash" to result.getString("key_hash"))) } }
    }
    private fun public(row: Map<String,Any?>) = row.filterKeys { it !in setOf("actor","keyHash") }
    @Synchronized fun list(): Map<String,Any?> = mapOf("enabled" to (directory!=null),
        "items" to rows("SELECT * FROM invitations ORDER BY created DESC,id DESC LIMIT 50").map(::public))
    @Synchronized fun invite(actor: String,emailInput: String,key: String,confirmed: Boolean): Map<String,Any?> {
        require(confirmed)
        val email=participantEmail(emailInput)
        val keyHash=hash(key)
        rows("SELECT * FROM invitations WHERE key_hash=?",keyHash).singleOrNull()?.let {
            if(it["actor"]!=actor || it["email"]!=email) reject(409,"IDEMPOTENCY_CONFLICT","This request key belongs to another invitation.")
            return public(it)
        }
        if(rows("SELECT * FROM invitations WHERE email=?",email).isNotEmpty())
            reject(409,"INVITATION_EXISTS","An invitation record already exists. Review its status instead of sending again.")
        val sender=directory ?: reject(503,"INVITATIONS_UNAVAILABLE","Participant invitations are not configured.")
        if(rows("SELECT * FROM invitations WHERE created>? LIMIT 1",now().epochSecond-60).isNotEmpty())
            reject(429,"INVITATION_RATE_LIMIT","Wait one minute before inviting another participant.")
        val id=UUID.randomUUID().toString()
        // Commit the send reservation before network I/O. Even a lost response or restart
        // must never authorize a second email for the same recipient/request.
        execute("INSERT INTO invitations(id,email,actor,key_hash,state,created) VALUES(?,?,?,?,'SENDING',?)",id,email,actor,keyHash,now().epochSecond)
        try {
            sender.invite(email)
            execute("UPDATE invitations SET state='SENT',expires=? WHERE id=?",now().epochSecond+900,id)
        } catch(_: ParticipantExists) {
            execute("UPDATE invitations SET state='ACCOUNT_EXISTS' WHERE id=?",id)
        } catch(_: Exception) {
            execute("UPDATE invitations SET state='RECONCILE_REQUIRED' WHERE id=?",id)
        }
        return public(rows("SELECT * FROM invitations WHERE id=?",id).single())
    }
    override fun close() = db.close()
}

fun Application.participantInvitationRoutes(store: ParticipantInvitations,guard: RequestGuard) {
    routing {
        get("/api/v1/participant-invitations") { call.api {
            guard.identity(call,false).requireRole(Role.WALLET_ADMIN)
            call.reply(withContext(Dispatchers.IO) { store.list() })
        } }
        post("/api/v1/participant-invitations") { call.api {
            val principal=guard.identity(call,true)
            principal.requireRole(Role.WALLET_ADMIN)
            val body=call.jsonBody(setOf("email","confirmed"))
            val email=body.text("email",254)
            val key=call.idempotency()
            val result=withContext(Dispatchers.IO) { store.invite(principal.account,email,key,body["confirmed"]=="true") }
            call.reply(result,202)
        } }
    }
}
