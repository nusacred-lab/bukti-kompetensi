package id.nusacred.custodian

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.util.JSONObjectUtils
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class AuthRejected : RuntimeException()

fun json(value: Map<String, Any?>): String = JSONObjectUtils.toJSONString(value)
fun form(values: Map<String, String>): String = values.entries.joinToString("&") {
    URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + URLEncoder.encode(it.value, Charsets.UTF_8)
}
fun randomToken(): String = ByteArray(32).also(SecureRandom()::nextBytes).let {
    Base64.getUrlEncoder().withoutPadding().encodeToString(it)
}
fun hash(value: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

/** Every endpoint is fixed by local configuration, never by a token or request URL. */
data class OidcConfig(
    val issuer: String,
    val backchannelOrigin: String,
    val portalOrigin: String,
    val clientId: String,
    val clientSecret: String,
    val nativeClientId: String = "android-wallet-p2",
) {
    init {
        for (value in listOf(issuer, backchannelOrigin, portalOrigin)) {
            val uri = URI(value)
            require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null && uri.query == null)
            require(!value.endsWith('/'))
        }
        require(URI(backchannelOrigin).host == URI(issuer).host)
        require(URI(backchannelOrigin).path.isEmpty() && URI(portalOrigin).path.isEmpty())
        require(clientSecret.length >= 32)
    }
    val callback: String get() = "$portalOrigin/auth/callback"
    companion object {
        fun fromEnvironment(): OidcConfig? {
            val issuer = System.getenv("OIDC_ISSUER") ?: return null
            return OidcConfig(issuer, requireNotNull(System.getenv("OIDC_BACKCHANNEL_ORIGIN")),
                requireNotNull(System.getenv("OIDC_PORTAL_ORIGIN")), "custodian-bff-p2",
                Files.readString(Path.of(requireNotNull(System.getenv("OIDC_CLIENT_SECRET_FILE")))).trim())
        }
    }
}

data class LoginFlow(val nonce: String, val verifier: String, val binding: String, val expires: Instant)
data class AppSession(val subject: String, val sid: String, val email: String, val accessToken: String,
                      val expires: Instant, val channel: String, val csrf: String)

/** P2 login sessions only: wallet operations remain unavailable until the recovery registry exists. */
class OidcService(val config: OidcConfig) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build()
    private val nativeFlows = ConcurrentHashMap<String, LoginFlow>()
    private val browserFlows = ConcurrentHashMap<String, LoginFlow>()
    private val sessions = ConcurrentHashMap<String, AppSession>()
    private val keys: JWKSet

    init {
        val discovery = get(config.issuer + "/.well-known/openid-configuration")
        require(discovery["issuer"] == config.issuer)
        for ((name, suffix) in mapOf("authorization_endpoint" to "auth", "token_endpoint" to "token",
            "jwks_uri" to "certs", "introspection_endpoint" to "token/introspect")) {
            require(discovery[name] == config.issuer + "/protocol/openid-connect/" + suffix)
        }
        keys = JWKSet.parse(get(config.issuer + "/protocol/openid-connect/certs"))
    }

    private fun endpoint(url: String): URI {
        require(url.startsWith(config.issuer + "/"))
        val issuerUri = URI(config.issuer)
        val uri = URI(url)
        require(uri.scheme == issuerUri.scheme && uri.rawAuthority == issuerUri.rawAuthority)
        return URI(config.backchannelOrigin + uri.rawPath)
    }
    private fun exchange(url: String, values: Map<String, String>? = null): Map<String, Any> {
        val builder = HttpRequest.newBuilder(endpoint(url)).timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
        if (values == null) builder.GET() else builder.header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form(values)))
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        val bytes = response.body().use { it.readNBytes(524_289) }
        if (response.statusCode() != 200 || bytes.size > 524_288) throw AuthRejected()
        return JSONObjectUtils.parse(String(bytes, Charsets.UTF_8))
    }
    private fun get(url: String) = exchange(url)
    private fun post(suffix: String, values: Map<String, String>) = exchange(
        config.issuer + "/protocol/openid-connect/" + suffix, values)

    private fun claims(token: String, audience: String): JWTClaimsSet {
        if (token.length !in 32..16_384) throw AuthRejected()
        val jwt = SignedJWT.parse(token)
        if (jwt.header.algorithm != JWSAlgorithm.RS256 || jwt.header.criticalParams?.isNotEmpty() == true) throw AuthRejected()
        val key = keys.getKeyByKeyId(jwt.header.keyID) as? RSAKey ?: throw AuthRejected()
        if (!jwt.verify(RSASSAVerifier(key.toRSAPublicKey()))) throw AuthRejected()
        val c = jwt.jwtClaimsSet
        val now = Instant.now()
        if (c.issuer != config.issuer || audience !in c.audience || c.subject.isNullOrBlank() ||
            c.expirationTime == null || !c.expirationTime.toInstant().isAfter(now) || c.issueTime == null ||
            c.issueTime.toInstant().isAfter(now.plusSeconds(30)) ||
            c.notBeforeTime?.toInstant()?.isAfter(now.plusSeconds(30)) == true) throw AuthRejected()
        return c
    }
    private fun active(token: String, subject: String, sid: String) {
        val result = post("token/introspect", mapOf("client_id" to config.clientId,
            "client_secret" to config.clientSecret, "token" to token, "token_type_hint" to "access_token"))
        if (result["active"] != true || result["sub"] != subject || result["sid"] != sid) throw AuthRejected()
    }
    private fun prune() {
        val now = Instant.now()
        nativeFlows.entries.removeIf { !it.value.expires.isAfter(now) }
        browserFlows.entries.removeIf { !it.value.expires.isAfter(now) }
        sessions.entries.removeIf { !it.value.expires.isAfter(now) }
    }
    @Synchronized fun startNative(): Map<String, Any?> {
        prune()
        if (nativeFlows.size + browserFlows.size >= 256 || sessions.size >= 512) throw AuthRejected()
        val ticket = randomToken()
        val flow = LoginFlow(randomToken(), "", "", Instant.now().plusSeconds(300))
        nativeFlows[hash(ticket)] = flow
        return mapOf("ticket" to ticket, "nonce" to flow.nonce, "issuer" to config.issuer,
            "client_id" to config.nativeClientId)
    }
    @Synchronized fun startBrowser(): Pair<String, String> {
        prune()
        if (nativeFlows.size + browserFlows.size >= 256 || sessions.size >= 512) throw AuthRejected()
        val state = randomToken()
        val cookie = randomToken()
        val flow = LoginFlow(randomToken(), randomToken(), hash(cookie), Instant.now().plusSeconds(300))
        browserFlows[hash(state)] = flow
        val query = form(mapOf("client_id" to config.clientId, "redirect_uri" to config.callback,
            "response_type" to "code", "scope" to "openid profile email", "state" to state,
            "nonce" to flow.nonce, "code_challenge" to hash(flow.verifier), "code_challenge_method" to "S256",
            "prompt" to "login", "max_age" to "0"))
        return cookie to (config.issuer + "/protocol/openid-connect/auth?" + query)
    }
    private fun consume(flows: ConcurrentHashMap<String, LoginFlow>, ticket: String): LoginFlow {
        if (ticket.length !in 32..128) throw AuthRejected()
        val flow = flows.remove(hash(ticket)) ?: throw AuthRejected()
        if (!flow.expires.isAfter(Instant.now())) throw AuthRejected()
        return flow
    }
    fun finishNative(ticket: String, access: String, idToken: String): String {
        val flow = consume(nativeFlows, ticket)
        return create(access, idToken, config.nativeClientId, flow.nonce, "native", flow.expires.minusSeconds(300))
    }
    fun finishBrowser(state: String, cookie: String, code: String): String {
        if (state.length !in 32..128 || code.length !in 1..4096) throw AuthRejected()
        val pending = browserFlows[hash(state)] ?: throw AuthRejected()
        if (!MessageDigest.isEqual(pending.binding.toByteArray(), hash(cookie).toByteArray())) throw AuthRejected()
        val flow = consume(browserFlows, state)
        if (flow != pending) throw AuthRejected()
        val tokens = post("token", mapOf("grant_type" to "authorization_code", "code" to code,
            "client_id" to config.clientId, "client_secret" to config.clientSecret,
            "redirect_uri" to config.callback, "code_verifier" to flow.verifier))
        return create(tokens["access_token"] as? String ?: throw AuthRejected(),
            tokens["id_token"] as? String ?: throw AuthRejected(), config.clientId, flow.nonce, "browser", flow.expires.minusSeconds(300))
    }
    private fun create(access: String, idToken: String, clientId: String, nonce: String, channel: String, started: Instant): String {
        val id = claims(idToken, clientId)
        val token = claims(access, config.clientId)
        val sid = token.getStringClaim("sid") ?: throw AuthRejected()
        if (id.getStringClaim("nonce") != nonce || id.subject != token.subject || id.getStringClaim("sid") != sid ||
            token.getStringClaim("azp") != clientId || token.getStringClaim("typ") != "Bearer" ||
            id.getBooleanClaim("email_verified") != true ||
            (id.getStringClaim("azp")?.let { it != clientId } == true ||
                (id.audience.size > 1 && id.getStringClaim("azp") != clientId))) throw AuthRejected()
        val authTime = id.getDateClaim("auth_time") ?: throw AuthRejected()
        if (authTime.toInstant().isBefore(started.minusSeconds(30)) || authTime.toInstant().isAfter(Instant.now().plusSeconds(30))) throw AuthRejected()
        val requiredRole = if (channel == "native") "applicant" else "custodian-admin"
        val roles = token.getJSONObjectClaim("realm_access")?.get("roles") as? List<*>
        if (roles?.contains(requiredRole) != true) throw AuthRejected()
        val atHash = id.getStringClaim("at_hash")
        val expectedHash = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(access.toByteArray(Charsets.US_ASCII)).copyOf(16))
        if (atHash != null && atHash != expectedHash) throw AuthRejected()
        active(access, token.subject, sid)
        val session = AppSession(token.subject, sid, id.getStringClaim("email") ?: "", access,
            minOf(token.expirationTime.toInstant(), id.expirationTime.toInstant(), Instant.now().plusSeconds(1800)),
            channel, randomToken())
        val opaque = randomToken()
        if (sessions.size >= 512) throw AuthRejected()
        sessions[hash(opaque)] = session
        return opaque
    }
    fun session(opaque: String, channel: String): AppSession {
        if (opaque.length !in 32..128) throw AuthRejected()
        val key = hash(opaque)
        val value = sessions[key] ?: throw AuthRejected()
        if (value.channel != channel || !value.expires.isAfter(Instant.now())) {
            sessions.remove(key); throw AuthRejected()
        }
        try { active(value.accessToken, value.subject, value.sid) } catch (e: Exception) {
            sessions.remove(key); throw AuthRejected()
        }
        return value
    }
    fun logout(opaque: String) { sessions.remove(hash(opaque)) }
}
