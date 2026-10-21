package id.nusacred.custodian

import id.nusacred.common.ApiFailure
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

class AccessDenied : ApiFailure(404,"NOT_FOUND","The requested object is not available.")
class ContextExpired : ApiFailure(410,"EXPIRED","The exchange context has expired.")
class ReconciliationRequired : ApiFailure(503,"RECONCILE_REQUIRED","The operation requires reconciliation.",true)

data class AccountAccess(val id: String, val revision: Long, val gate: String, val freshAfter: Long)
data class WalletBinding(val operation: String, val state: String, val walletId: String?, val keyId: String?)

/** One custodian process owns this database. The monitor also serializes dispatch with recovery.
 * No database transaction stays open during an upstream call. Never deploy multiple replicas.
 */
class AccessRegistry(path: Path, private val now: () -> Instant = Instant::now) : AutoCloseable, OidcRegistry {
    private val db: Connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
    init {
        db.createStatement().use { s ->
            s.execute("PRAGMA foreign_keys=ON")
            s.execute("PRAGMA busy_timeout=5000")
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("PRAGMA synchronous=FULL")
        }
        transaction {
            val version = query("PRAGMA user_version") { it.getInt(1) }.single()
            require(version in 0..4) { "Unsupported access registry schema" }
            if (version == 0) {
                execute("""CREATE TABLE accounts(id TEXT PRIMARY KEY, issuer TEXT NOT NULL, subject TEXT NOT NULL,
                    revision INTEGER NOT NULL DEFAULT 0, gate TEXT NOT NULL DEFAULT 'OPEN'
                    CHECK(gate IN ('OPEN','LOCKED','AWAITING_FRESH_LOGIN')), fresh_after INTEGER NOT NULL DEFAULT -1,
                    UNIQUE(issuer,subject))""")
                execute("""CREATE TABLE wallet_bindings(account_id TEXT PRIMARY KEY REFERENCES accounts(id),
                    operation TEXT UNIQUE NOT NULL, state TEXT NOT NULL CHECK(state IN ('PROVISIONING','READY','RECONCILE_REQUIRED')),
                    wallet_id TEXT UNIQUE, key_id TEXT)""")
                execute("""CREATE TABLE app_sessions(token_hash TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id),
                    sid TEXT NOT NULL, revision INTEGER NOT NULL, expires INTEGER NOT NULL, revoked INTEGER NOT NULL DEFAULT 0)""")
                execute("""CREATE TABLE recovery_operations(id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id),
                    revision INTEGER NOT NULL, state TEXT NOT NULL CHECK(state IN ('LOCKED','COMPLETED')), started INTEGER NOT NULL,
                    completed INTEGER, UNIQUE(account_id,revision))""")
                execute("""CREATE TABLE pending_operations(id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id),
                    token_hash TEXT NOT NULL REFERENCES app_sessions(token_hash), revision INTEGER NOT NULL,
                    payload_hash TEXT NOT NULL, expires INTEGER NOT NULL, state TEXT NOT NULL
                    CHECK(state IN ('PENDING','DISPATCHED','COMPLETED','CANCELLED','RECONCILE_REQUIRED')))""")
                execute("PRAGMA user_version=1")
            }
            if (version < 2) {
                execute("ALTER TABLE accounts ADD COLUMN fresh_flow_after_ms INTEGER NOT NULL DEFAULT -1")
                // Older registries recorded seconds only; retain a conservative boundary for them.
                execute("UPDATE accounts SET fresh_flow_after_ms=fresh_after*1000+999 WHERE fresh_after>=0")
                execute("PRAGMA user_version=2")
            }
            if (version < 3) {
                execute("""CREATE TABLE exchange_contexts(id TEXT PRIMARY KEY REFERENCES pending_operations(id),
                    preview_json TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'PREVIEWED'
                    CHECK(state IN ('PREVIEWED','EXECUTING','COMPLETED','DECLINED','EXPIRED','RECONCILE_REQUIRED')))""")
                execute("""CREATE TABLE idempotency_keys(account_id TEXT NOT NULL REFERENCES accounts(id), session_hash TEXT NOT NULL,
                    action TEXT NOT NULL, key_hash TEXT NOT NULL, payload_hash TEXT NOT NULL, operation_id TEXT NOT NULL REFERENCES pending_operations(id),
                    PRIMARY KEY(account_id,session_hash,action,key_hash))""")
                execute("""CREATE TABLE audit(id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id),
                    action TEXT NOT NULL, resource TEXT NOT NULL, at INTEGER NOT NULL)""")
                execute("PRAGMA user_version=3")
            }
            if (version < 4) {
                execute("ALTER TABLE pending_operations ADD COLUMN reason TEXT")
                execute("PRAGMA user_version=4")
            }
            execute("UPDATE exchange_contexts SET state='RECONCILE_REQUIRED' WHERE state='EXECUTING'")
            // A crash after reserving or dispatching must never silently retry a side effect.
            execute("UPDATE wallet_bindings SET state='RECONCILE_REQUIRED' WHERE state='PROVISIONING'")
            execute("UPDATE pending_operations SET state='RECONCILE_REQUIRED' WHERE state='DISPATCHED'")
            // Raw IdP tokens are deliberately memory-only; process restart invalidates app sessions.
            execute("UPDATE app_sessions SET revoked=1")
        }
    }
    private fun execute(sql: String, vararg args: Any?): Int = db.prepareStatement(sql).use { s ->
        args.forEachIndexed { i, value -> s.setObject(i + 1, value) }; s.executeUpdate()
    }
    private fun <T> query(sql: String, vararg args: Any?, read: (ResultSet) -> T): List<T> = db.prepareStatement(sql).use { s ->
        args.forEachIndexed { i, value -> s.setObject(i + 1, value) }
        s.executeQuery().use { r -> buildList { while (r.next()) add(read(r)) } }
    }
    private fun <T> transaction(block: () -> T): T {
        db.autoCommit = false
        try { val result = block(); db.commit(); return result }
        catch (e: Exception) { db.rollback(); throw e }
        finally { db.autoCommit = true }
    }
    private fun account(id: String): AccountAccess = query("SELECT * FROM accounts WHERE id=?", id) {
        AccountAccess(it.getString("id"), it.getLong("revision"), it.getString("gate"), it.getLong("fresh_after"))
    }.singleOrNull() ?: throw AccessDenied()

    @Synchronized fun identify(issuer: String, subject: String): AccountAccess {
        require(issuer.isNotBlank() && subject.isNotBlank())
        execute("INSERT OR IGNORE INTO accounts(id,issuer,subject) VALUES(?,?,?)", UUID.randomUUID().toString(), issuer, subject)
        val id = query("SELECT id FROM accounts WHERE issuer=? AND subject=?", issuer, subject) { it.getString(1) }.single()
        return account(id)
    }
    @Synchronized override fun registerSession(issuer: String, subject: String, tokenHash: String, sid: String,
                                      authTime: Long, expires: Long, loginStarted: Instant?): AccountAccess = transaction {
        val a = identify(issuer, subject)
        val completionMillis = query("SELECT fresh_flow_after_ms FROM accounts WHERE id=?", a.id) { it.getLong(1) }.single()
        // Same-second authentication is safe only with a nonce-bound flow issued after durable completion.
        // OIDC verifies that nonce, forces interactive login and introspects the resulting IdP session.
        val sameSecondFresh = authTime == a.freshAfter && loginStarted != null &&
            loginStarted.toEpochMilli() > completionMillis && !loginStarted.isAfter(now()) &&
            authTime >= loginStarted.epochSecond
        if (a.gate == "LOCKED" || authTime < a.freshAfter ||
            (authTime == a.freshAfter && !sameSecondFresh) || expires <= now().epochSecond) throw AccessDenied()
        if (a.gate == "AWAITING_FRESH_LOGIN") execute("UPDATE accounts SET gate='OPEN' WHERE id=?", a.id)
        execute("INSERT INTO app_sessions(token_hash,account_id,sid,revision,expires) VALUES(?,?,?,?,?)",
            tokenHash, a.id, sid, a.revision, expires)
        account(a.id)
    }
    @Synchronized override fun authorize(tokenHash: String): AccountAccess {
        val a = query("""SELECT a.* FROM accounts a JOIN app_sessions s ON s.account_id=a.id
            WHERE s.token_hash=? AND s.revoked=0 AND s.revision=a.revision AND a.gate='OPEN' AND s.expires>?""",
            tokenHash, now().epochSecond) {
            AccountAccess(it.getString("id"), it.getLong("revision"), it.getString("gate"), it.getLong("fresh_after"))
        }.singleOrNull() ?: throw AccessDenied()
        return a
    }
    @Synchronized override fun accountId(tokenHash: String): String = authorize(tokenHash).id
    @Synchronized override fun revoke(tokenHash: String) { execute("UPDATE app_sessions SET revoked=1 WHERE token_hash=?", tokenHash) }
    @Synchronized fun <T> read(tokenHash: String, block: () -> T): T {
        authorize(tokenHash)
        return block()
    }

    /** Only authenticated Keycloak code after valid recovery proof may call this method. */
    @Synchronized fun beginRecovery(issuer: String, subject: String, operation: String, idpWriteFenced: Boolean = false): Long = transaction {
        require(operation.matches(Regex("[A-Za-z0-9_-]{32,128}")))
        val a = identify(issuer, subject)
        val previous = query("SELECT account_id,revision,state FROM recovery_operations WHERE id=?", operation) {
            Triple(it.getString(1), it.getLong(2), it.getString(3))
        }.singleOrNull()
        if (previous != null) {
            if (previous.first != a.id || previous.second != a.revision || previous.third != "LOCKED" || a.gate != "LOCKED") throw AccessDenied()
            return@transaction previous.second
        }
        // A new proven flow may supersede an abandoned recovery only while the IdP holds
        // its per-account transaction lock. Older operation IDs can never supersede anything.
        if (a.gate != "OPEN" && !idpWriteFenced) throw AccessDenied()
        val revision = a.revision + 1
        execute("UPDATE accounts SET revision=?,gate='LOCKED' WHERE id=?", revision, a.id)
        execute("UPDATE app_sessions SET revoked=1 WHERE account_id=?", a.id)
        execute("UPDATE pending_operations SET state='CANCELLED' WHERE account_id=? AND state='PENDING'", a.id)
        execute("UPDATE exchange_contexts SET state='DECLINED' WHERE state='PREVIEWED' AND id IN (SELECT id FROM pending_operations WHERE account_id=?)", a.id)
        execute("INSERT INTO recovery_operations(id,account_id,revision,state,started) VALUES(?,?,?,'LOCKED',?)",
            operation, a.id, revision, now().epochSecond)
        revision
    }
    /** Completion follows confirmed IdP commit AND revocation. It only permits a fresh login. */
    @Synchronized fun completeRecovery(issuer: String, subject: String, operation: String, revision: Long) = transaction {
        val a = identify(issuer, subject)
        val state = query("SELECT state FROM recovery_operations WHERE id=? AND account_id=? AND revision=?",
            operation, a.id, revision) { it.getString(1) }.singleOrNull() ?: throw AccessDenied()
        if (a.revision != revision) throw AccessDenied()
        if (state == "COMPLETED") return@transaction // Duplicate never changes the freshness boundary or gate.
        if (a.gate != "LOCKED") throw AccessDenied()
        val completed = now()
        execute("UPDATE recovery_operations SET state='COMPLETED',completed=? WHERE id=?", completed.epochSecond, operation)
        execute("UPDATE accounts SET gate='AWAITING_FRESH_LOGIN',fresh_after=?,fresh_flow_after_ms=? WHERE id=?",
            completed.epochSecond, completed.toEpochMilli(), a.id)
    }
    @Synchronized fun binding(tokenHash: String): WalletBinding? {
        val a = authorize(tokenHash)
        return query("SELECT * FROM wallet_bindings WHERE account_id=?", a.id) {
            WalletBinding(it.getString("operation"), it.getString("state"), it.getString("wallet_id"), it.getString("key_id"))
        }.singleOrNull()
    }
    /** Reserve durably before network dispatch. Any uncertain create needs explicit reconciliation. */
    @Synchronized fun provision(tokenHash: String, create: (String) -> Pair<String, String>): WalletBinding {
        val a = authorize(tokenHash)
        binding(tokenHash)?.let { if (it.state == "READY") return it else throw ReconciliationRequired() }
        val operation = UUID.randomUUID().toString()
        execute("INSERT INTO wallet_bindings(account_id,operation,state) VALUES(?,?,'PROVISIONING')", a.id, operation)
        try {
            val (wallet, key) = create(operation)
            require(wallet.isNotBlank() && key.isNotBlank())
            execute("UPDATE wallet_bindings SET state='READY',wallet_id=?,key_id=? WHERE account_id=?", wallet, key, a.id)
        } catch (e: Exception) {
            execute("UPDATE wallet_bindings SET state='RECONCILE_REQUIRED' WHERE account_id=?", a.id)
            throw ReconciliationRequired()
        }
        return binding(tokenHash)!!
    }
    /** Internal adapter must verify the upstream reference; never accept wallet IDs from a caller. */
    @Synchronized fun reconcile(tokenHash: String, lookup: (String) -> Pair<String, String>?): WalletBinding {
        val a = authorize(tokenHash)
        val b = binding(tokenHash) ?: throw ReconciliationRequired()
        if (b.state == "READY") return b
        val (wallet, key) = lookup(b.operation) ?: throw ReconciliationRequired()
        require(wallet.isNotBlank() && key.isNotBlank())
        execute("UPDATE wallet_bindings SET state='READY',wallet_id=?,key_id=? WHERE account_id=?", wallet, key, a.id)
        return binding(tokenHash)!!
    }
    @Synchronized fun prepare(tokenHash: String, id: String, payloadHash: String, expires: Long) {
        val a = authorize(tokenHash)
        require(expires > now().epochSecond && expires <= now().plusSeconds(300).epochSecond)
        execute("""INSERT INTO pending_operations(id,account_id,token_hash,revision,payload_hash,expires,state)
            VALUES(?,?,?,?,?,?,'PENDING')""", id, a.id, tokenHash, a.revision, payloadHash, expires)
    }
    /** Recovery shares this monitor. Already dispatched upstream work cannot be recalled. */
    @Synchronized fun <T> dispatch(tokenHash: String, id: String, payloadHash: String, send: () -> T): T {
        if (expireOperation(tokenHash,id)) throw ContextExpired()
        val a = authorize(tokenHash)
        val changed = execute("""UPDATE pending_operations SET state='DISPATCHED' WHERE id=? AND account_id=?
            AND token_hash=? AND revision=? AND payload_hash=? AND expires>? AND state='PENDING'""",
            id, a.id, tokenHash, a.revision, payloadHash, now().epochSecond)
        if (changed != 1) throw AccessDenied()
        execute("UPDATE exchange_contexts SET state='EXECUTING' WHERE id=?",id)
        try {
            val result = send()
            transaction {
                val rejected = result == false
                execute("UPDATE pending_operations SET state=?,reason=? WHERE id=?",
                    if(rejected) "CANCELLED" else "COMPLETED", if(rejected) "TRANSMISSION_REJECTED" else null, id)
                execute("UPDATE exchange_contexts SET state=? WHERE id=?",if(rejected) "DECLINED" else "COMPLETED",id)
            }
            return result
        } catch (e: Exception) {
            execute("UPDATE pending_operations SET state='RECONCILE_REQUIRED' WHERE id=?", id)
            execute("UPDATE exchange_contexts SET state='RECONCILE_REQUIRED' WHERE id=?",id)
            throw ReconciliationRequired()
        }
    }
    @Synchronized fun savePreview(tokenHash: String, id: String, summary: String, expires: Long, previewJson: String) = transaction {
        prepare(tokenHash,id,summary,expires)
        execute("INSERT INTO exchange_contexts(id,preview_json) VALUES(?,?)",id,previewJson)
        val a = authorize(tokenHash)
        execute("INSERT INTO audit VALUES(?,?,?,?,?)",UUID.randomUUID().toString(),a.id,"PREVIEW_CREATED",id,now().epochSecond)
    }
    /** Expiry is committed before returning a 410, including rejected consent retries. */
    private fun expireOperation(tokenHash: String, id: String): Boolean {
        val a = authorize(tokenHash)
        val row = query("SELECT state,reason,expires FROM pending_operations WHERE id=? AND account_id=? AND token_hash=? AND revision=?",
            id,a.id,tokenHash,a.revision) { Triple(it.getString(1),it.getString(2),it.getLong(3)) }.singleOrNull() ?: throw AccessDenied()
        if(row.first == "PENDING" && row.third <= now().epochSecond) {
            transaction {
                execute("UPDATE pending_operations SET state='CANCELLED',reason='EXPIRED' WHERE id=?",id)
                execute("UPDATE exchange_contexts SET state='EXPIRED' WHERE id=?",id)
            }
            return true
        }
        return row.second == "EXPIRED"
    }
    @Synchronized fun preview(tokenHash: String, id: String): String {
        if(expireOperation(tokenHash,id)) throw ContextExpired()
        return pendingPreview(tokenHash,id)
    }
    private fun pendingPreview(tokenHash: String, id: String): String {
        val a = authorize(tokenHash)
        return query("""SELECT e.preview_json FROM exchange_contexts e JOIN pending_operations p ON p.id=e.id
            WHERE e.id=? AND p.account_id=? AND p.token_hash=? AND p.revision=? AND p.expires>?
            AND p.state='PENDING' AND e.state='PREVIEWED'""",id,a.id,tokenHash,a.revision,now().epochSecond) { it.getString(1) }
            .singleOrNull() ?: throw AccessDenied()
    }
    @Synchronized fun decline(tokenHash: String, id: String) {
        if(expireOperation(tokenHash,id)) throw ContextExpired()
        transaction {
            pendingPreview(tokenHash,id)
            execute("UPDATE exchange_contexts SET state='DECLINED' WHERE id=?",id)
            execute("UPDATE pending_operations SET state='CANCELLED',reason='CANCELLED' WHERE id=?",id)
        }
    }
    @Synchronized fun operation(tokenHash: String, id: String): Map<String,Any?> {
        expireOperation(tokenHash,id)
        val a = authorize(tokenHash)
        return query("SELECT state,reason FROM pending_operations WHERE id=? AND account_id=? AND token_hash=? AND revision=?",id,a.id,tokenHash,a.revision) {
            mapOf("id" to id,"state" to it.getString(1),"reason" to if(it.getString(1)=="RECONCILE_REQUIRED") "RECONCILE_REQUIRED" else it.getString(2))
        }.singleOrNull() ?: throw AccessDenied()
    }
    @Synchronized fun consent(tokenHash: String, id: String, summary: String, key: String, requestHash: String = summary): String {
        if(expireOperation(tokenHash,id)) throw ContextExpired()
        return transaction {
            val a = authorize(tokenHash)
            require(key.matches(Regex("[A-Za-z0-9_-]{16,128}")))
            val old = query("SELECT operation_id,payload_hash FROM idempotency_keys WHERE account_id=? AND session_hash=? AND action='present' AND key_hash=?",a.id,tokenHash,hash(key)) {
                it.getString(1) to it.getString(2)
            }.singleOrNull()
            if (old != null) {
                if(old.first != id || old.second != requestHash) throw ApiFailure(409,"IDEMPOTENCY_CONFLICT","This key was used for different input.")
                return@transaction old.first
            }
            pendingPreview(tokenHash,id)
            val expected = query("SELECT payload_hash FROM pending_operations WHERE id=?",id) { it.getString(1) }.single()
            if(expected != summary) throw AccessDenied()
            execute("INSERT INTO idempotency_keys VALUES(?,?,'present',?,?,?)",a.id,tokenHash,hash(key),requestHash,id)
            execute("INSERT INTO audit VALUES(?,?,?,?,?)",UUID.randomUUID().toString(),a.id,"CONSENT_ACCEPTED",id,now().epochSecond)
            id
        }
    }
    @Synchronized override fun close() { db.close() }
}
