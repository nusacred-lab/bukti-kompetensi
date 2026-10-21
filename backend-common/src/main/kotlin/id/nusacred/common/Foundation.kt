package id.nusacred.common

import com.nimbusds.jose.util.JSONObjectUtils
import id.nusacred.contracts.Role
import id.nusacred.custodian.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import java.net.URI
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding

open class ApiFailure(val status: Int, val code: String, override val message: String, val retryable: Boolean = false) : RuntimeException(message)
fun reject(status: Int, code: String, message: String): Nothing = throw ApiFailure(status, code, message)
data class Principal(val account: String, val organization: String?, val roles: Set<Role>, val session: String, val revision: Long = 0, val correlationId: String = UUID.randomUUID().toString()) {
    fun requireRole(vararg allowed: Role) {
        if (roles.none { it in allowed }) reject(403, "FORBIDDEN", "This role cannot perform the action.")
    }
    fun organization(): String = organization?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
        ?: reject(403, "ORGANIZATION_REQUIRED", "An organization assignment is required.")
}
fun principal(auth: OidcService, opaque: String, channel: String): Principal {
    val session = auth.session(opaque, channel)
    val roles = session.roles.mapNotNull { when(it) {
        "applicant", "candidate" -> Role.CANDIDATE
        "issuer-admin", "issuer_admin" -> Role.ISSUER_ADMIN
        "issuer-operator", "issuer_operator" -> Role.ISSUER_OPERATOR
        "custodian-admin", "wallet_admin" -> Role.WALLET_ADMIN
        "company-admin", "company_admin" -> Role.COMPANY_ADMIN
        else -> null
    } }.toSet()
    return Principal(auth.accountId(opaque,channel), session.organization, roles, hash(opaque))
}

/** Deliberately ignores Forwarded/X-Forwarded-* headers. A trusted edge must enforce host routing. */
class RequestGuard(private val origin: String, private val authenticate: (String, String) -> Pair<Principal, String>,
                   private val now: () -> Long = { Instant.now().epochSecond }, private val capacity: Int = 4096) {
    private val viewers = linkedMapOf<String, Pair<String,Long>>()
    @Synchronized fun viewer(call: ApplicationCall, mutate: Boolean): Principal {
        val cookie = call.request.cookies["__Host-nusacred-viewer"]
        val stored = cookie?.let { viewers[hash(it)] }?.takeIf { it.second > now() }
        if(stored == null) reject(401,"VIEWER_SESSION_REQUIRED","Open a new browser session.")
        if(mutate && (call.request.headers[HttpHeaders.Origin] != origin || call.request.headers["X-CSRF-Token"] != stored.first))
            reject(403,"CSRF_REJECTED","The request could not be verified.")
        if(call.request.headers[HttpHeaders.Origin]?.let { it != origin } == true) reject(403,"ORIGIN_REJECTED","The request origin is not allowed.")
        rate("viewer:"+hash(cookie!!),30)
        return Principal("viewer:"+hash(cookie),null,emptySet(),hash(cookie))
    }
    @Synchronized fun openViewer(call: ApplicationCall): Map<String,Any?> {
        if(call.request.headers[HttpHeaders.Origin]?.let { it != origin } == true) reject(403,"ORIGIN_REJECTED","The request origin is not allowed.")
        rate("viewer-start:"+call.request.local.remoteHost,10)
        viewers.entries.removeIf { it.value.second <= now() }
        if(viewers.size >= 512) reject(429,"RATE_LIMITED","Please retry later.")
        val token = randomToken(); val csrf = randomToken()
        viewers[hash(token)] = csrf to (now()+1800)
        call.response.cookies.append(Cookie("__Host-nusacred-viewer",token,encoding=CookieEncoding.RAW,path="/",secure=true,httpOnly=true,maxAge=1800,extensions=mapOf("SameSite" to "Strict")))
        return mapOf("csrf" to csrf,"expiresAt" to now()+1800)
    }
    fun owner(call: ApplicationCall, mutate: Boolean): Principal =
        if(call.request.headers[HttpHeaders.Authorization] != null || call.request.cookies["__Host-nusacred-session"] != null) identity(call,mutate) else viewer(call,mutate)
    private val buckets = linkedMapOf<String, Pair<Long, Int>>()
    @Synchronized fun rate(key: String, limit: Int = 60) {
        val minute = now() / 60
        buckets.entries.removeIf { it.value.first != minute }
        val count = buckets[key]?.second ?: 0
        if (count >= limit || (key !in buckets && buckets.size >= capacity)) reject(429, "RATE_LIMITED", "Please retry later.")
        buckets[key] = minute to count + 1
    }
    fun identity(call: ApplicationCall, mutate: Boolean): Principal {
        val bearer = call.request.headers[HttpHeaders.Authorization]
        val cookie = call.request.cookies["__Host-nusacred-session"]
        if ((bearer == null) == (cookie == null) || (bearer != null && call.request.headers[HttpHeaders.Cookie] != null))
            reject(401, "UNAUTHENTICATED", "Please sign in.")
        val channel = if (bearer != null) "native" else "browser"
        if (bearer != null && !bearer.startsWith("Bearer ")) reject(401, "UNAUTHENTICATED", "Please sign in.")
        val opaque = bearer?.removePrefix("Bearer ") ?: cookie!!
        val (p, csrf) = try { authenticate(opaque, channel) } catch (_: Exception) { reject(401, "UNAUTHENTICATED", "Please sign in again.") }
        val suppliedOrigin = call.request.headers[HttpHeaders.Origin]
        if (suppliedOrigin != null && suppliedOrigin != origin) reject(403, "ORIGIN_REJECTED", "The request origin is not allowed.")
        if (mutate && channel == "browser" && (suppliedOrigin != origin || call.request.headers["X-CSRF-Token"] != csrf))
            reject(403, "CSRF_REJECTED", "The request could not be verified.")
        rate(p.session)
        return p.copy(correlationId=call.response.headers["X-Correlation-ID"] ?: p.correlationId)
    }
    companion object {
        fun oidc(auth: OidcService) = RequestGuard(auth.config.portalOrigin, { opaque, channel ->
            val s = auth.session(opaque, channel)
            principal(auth, opaque, channel) to s.csrf
        })
    }
}

suspend fun ApplicationCall.api(block: suspend () -> Unit) {
    val correlation = request.headers["X-Correlation-ID"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: UUID.randomUUID().toString()
    response.headers.append("X-Correlation-ID", correlation)
    response.headers.append("Cache-Control", "no-store")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
    try { block() } catch (e: Exception) {
        val failure = e as? ApiFailure ?: if(e is AuthRejected) ApiFailure(401,"UNAUTHENTICATED","Please sign in again.") else if(e is IllegalArgumentException) ApiFailure(400,"INVALID_INPUT","Invalid request input.") else ApiFailure(503, "DEPENDENCY_UNAVAILABLE", "The operation could not be completed.", true)
        if (failure.status == 429) response.headers.append("Retry-After", "60")
        respondText(json(mapOf("code" to failure.code, "message" to failure.message, "correlationId" to correlation,
            "retryable" to failure.retryable)), ContentType.Application.Json, HttpStatusCode.fromValue(failure.status))
    }
}
suspend fun ApplicationCall.jsonBody(fields: Set<String>): Map<String, Any> {
    if (request.headers[HttpHeaders.ContentType]?.substringBefore(';') != "application/json") reject(415, "CONTENT_TYPE", "Use application/json.")
    val bytes = receiveStream().use { it.readNBytes(32769) }
    if (bytes.size > 32768) reject(413, "BODY_LIMIT", "The request body is too large.")
    val body = try { JSONObjectUtils.parse(String(bytes, Charsets.UTF_8)) } catch (_: Exception) { reject(400, "INVALID_BODY", "Invalid JSON object.") }
    if (body.keys != fields) reject(400, "INVALID_BODY", "Unexpected or missing fields.")
    return body
}
fun Map<String, Any>.text(name: String, max: Int = 128): String = (get(name) as? String)?.takeIf {
    it.isNotBlank() && it.length <= max && it.none { c -> c.isISOControl() }
} ?: reject(400, "INVALID_FIELD", "Invalid $name.")
fun ApplicationCall.idempotency(): String = request.headers["Idempotency-Key"]?.takeIf {
    it.matches(Regex("[A-Za-z0-9_-]{16,128}"))
} ?: reject(400, "IDEMPOTENCY_REQUIRED", "Provide a valid Idempotency-Key.")
suspend fun ApplicationCall.reply(body: Map<String, Any?>, status: Int = 200) = respondText(json(body), ContentType.Application.Json, HttpStatusCode.fromValue(status))

/** Exact references only; no DNS lookup or arbitrary URL fetch is performed by application clients. */
class ReferencePolicy(private val references: Map<String, URI>) {
    init { require(references.values.all { it.scheme == "http" && it.rawAuthority in setOf("issuer:7002", "verifier:7004") && it.userInfo == null && it.fragment == null }) }
    fun resolve(id: String): URI = references[id] ?: reject(400, "UNTRUSTED_REFERENCE", "Use an offer or request generated by this demo.")
}

/** Migration checksums detect edited history; failures roll back the entire migration. */
class SqlStore(path: Path, private val migrations: List<String>) : AutoCloseable {
    private val db: Connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
    init {
        try {
            db.createStatement().use { s ->
                s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA busy_timeout=5000")
                s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA synchronous=FULL")
            }
            transaction {
                execute("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, checksum TEXT NOT NULL)")
                val applied = query("SELECT version,checksum FROM schema_migrations ORDER BY version") { it.getInt(1) to it.getString(2) }
                require(applied.map { it.first } == (1..applied.size).toList() && applied.size <= migrations.size) { "Unsupported schema" }
                applied.forEach { (version, checksum) -> require(hash(migrations[version-1]) == checksum) { "Migration history changed" } }
                migrations.drop(applied.size).forEachIndexed { index, sql ->
                    sql.split(Regex(";\\s*\\n|;\\s*$")).filter { it.isNotBlank() }.forEach { execute(it) }
                    execute("INSERT INTO schema_migrations VALUES(?,?)", applied.size+index+1, hash(sql))
                }
            }
        } catch (e: Exception) { db.close(); throw e }
    }
    @Synchronized fun execute(sql: String, vararg args: Any?): Int = db.prepareStatement(sql).use { s ->
        args.forEachIndexed { i, v -> s.setObject(i+1,v) }; s.executeUpdate()
    }
    @Synchronized fun <T> query(sql: String, vararg args: Any?, read: (ResultSet) -> T): List<T> = db.prepareStatement(sql).use { s ->
        args.forEachIndexed { i, v -> s.setObject(i+1,v) }
        s.executeQuery().use { rs -> buildList { while(rs.next()) add(read(rs)) } }
    }
    @Synchronized fun <T> transaction(block: () -> T): T {
        check(db.autoCommit) { "Nested transaction" }
        db.autoCommit = false
        try { val result = block(); db.commit(); return result } catch (e: Exception) { db.rollback(); throw e }
        finally { db.autoCommit = true }
    }
    @Synchronized override fun close() = db.close()
}
