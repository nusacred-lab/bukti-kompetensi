package id.nusacred.custodian

import com.nimbusds.jose.util.JSONObjectUtils
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/** Private server adapter. No client selects a wallet, key, endpoint, or upstream bearer token. */
class WalletClient(origin: String) {
    private val base = URI(origin)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build()
    init { require(origin == "http://wallet:7006") }
    private fun request(path: String, body: Map<String, Any?>? = null): String {
        require(path.startsWith("/wallet") || path.startsWith("/stores/"))
        val b = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
        if (body == null) b.GET() else b.header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json(body)))
        val r = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream())
        val bytes = r.body().use { it.readNBytes(2_097_153) }
        if (r.statusCode() !in 200..299 || bytes.size > 2_097_152) throw ReconciliationRequired()
        return String(bytes, Charsets.UTF_8)
    }
    private fun segment(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return value
    }
    fun create(operation: String): Pair<String, String> {
        val marker = "nusacred-${segment(operation)}"
        request("/stores/keys/$marker", emptyMap())
        request("/stores/credentials/$marker", emptyMap())
        val wallet = JSONObjectUtils.parse(request("/wallet", mapOf("keyStoreIds" to listOf(marker),
            "credentialStoreIds" to listOf(marker), "noDidStore" to true)))["walletId"] as String
        val key = JSONObjectUtils.parse(request("/wallet/${segment(wallet)}/keys/generate",
            mapOf("backend" to "jwk", "keyType" to "secp256r1")))["keyId"] as String
        return wallet to key
    }
    /** Never creates during reconciliation. Ambiguous/missing references keep access closed. */
    fun lookup(operation: String): Pair<String, String>? {
        val marker = "nusacred-${segment(operation)}"
        val ids = JSONObjectUtils.parse("{\"ids\":" + request("/wallet") + "}")["ids"] as List<*>
        if (ids.size > 1024) throw ReconciliationRequired()
        val matches = ids.mapNotNull { value ->
            val wallet = value as? String ?: throw ReconciliationRequired()
            val info = JSONObjectUtils.parse(request("/wallet/${segment(wallet)}"))
            if (info["keyStoreIds"] != listOf(marker) || info["credentialStoreIds"] != listOf(marker)) null else wallet
        }
        if (matches.size != 1) return null
        val wallet = matches.single()
        val keys = JSONObjectUtils.parse("{\"keys\":" + request("/wallet/${segment(wallet)}/keys") + "}")["keys"] as List<*>
        if (keys.size != 1) return null
        val key = (keys.single() as? Map<*, *>)?.get("keyId") as? String ?: return null
        return wallet to key
    }
    fun credentials(wallet: String): String = request("/wallet/${segment(wallet)}/credentials")
    fun preview(wallet: String, url: String): Map<String, Any> = JSONObjectUtils.parse(request(
        "/wallet/${segment(wallet)}/credentials/present/preview", mapOf("requestUrl" to url)))
    fun present(wallet: String, url: String, query: String, credential: String, key: String): Boolean {
        val vp = JSONObjectUtils.parse(request("/wallet/${segment(wallet)}/credentials/present/build-vp-token",
            mapOf("requestUrl" to url, "keyId" to key, "selectedCredentialOptions" to
                listOf(mapOf("queryId" to query, "credentialId" to credential)))))
        val response = JSONObjectUtils.parse(request("/wallet/${segment(wallet)}/credentials/present/send-response",
            mapOf("requestUrl" to url, "vpToken" to (vp["vpToken"] as? String ?: throw AccessDenied()))))
        return response["transmission_success"] == true
    }
}

class WalletService(private val registry: AccessRegistry, private val upstream: WalletClient) {
    private data class Preview(val url: String, val wallet: String, val key: String, val query: String,
                               val credential: String, val summary: String, val expires: Instant)
    // Restart cancels these previews. The durable consent ledger prevents reuse after dispatch/recovery.
    private val previews = ConcurrentHashMap<String, Preview>()
    fun provision(opaque: String): WalletBinding = registry.provision(hash(opaque), upstream::create)
    fun reconcile(opaque: String): WalletBinding = registry.reconcile(hash(opaque), upstream::lookup)
    fun credentials(opaque: String): String = registry.read(hash(opaque)) {
        val binding = registry.binding(hash(opaque)) ?: throw AccessDenied()
        if (binding.state != "READY") throw AccessDenied()
        upstream.credentials(binding.walletId!!)
    }
    fun preview(opaque: String, url: String): Map<String, Any?> = registry.read(hash(opaque)) {
        validatePresentationUrl(url)
        previews.entries.removeIf { !it.value.expires.isAfter(Instant.now()) }
        if (previews.size >= 256) throw AccessDenied()
        val b = registry.binding(hash(opaque))?.takeIf { it.state == "READY" } ?: throw AccessDenied()
        val p = upstream.preview(b.walletId!!, url)
        if (p["valid"] != true || p["keyId"] != b.keyId) throw AccessDenied()
        val options = p["credentialOptions"] as? List<*> ?: throw AccessDenied()
        // Minimal P2 proof: one explicit credential choice. Full multi-choice UI is a later phase.
        val chosen = options.singleOrNull() as? Map<*, *> ?: throw AccessDenied()
        val query = chosen["queryId"] as? String ?: throw AccessDenied()
        val credential = chosen["credentialId"] as? String ?: throw AccessDenied()
        val disclosures = chosen["disclosures"] as? List<*> ?: throw AccessDenied()
        val recipient = p["clientId"] as? String ?: throw AccessDenied()
        val details = mapOf("recipient" to recipient, "credential_label" to (chosen["label"] ?: "Competency Proof"),
            "issuer" to chosen["issuer"], "disclosures" to disclosures)
        val id = randomToken()
        val summary = hash(json(mapOf("url" to url, "wallet" to b.walletId, "key" to b.keyId,
            "query" to query, "credential" to credential, "details" to details)))
        val expires = Instant.now().plusSeconds(240)
        registry.prepare(hash(opaque), id, summary, expires.epochSecond)
        previews[id] = Preview(url, b.walletId, b.keyId!!, query, credential, summary, expires)
        mapOf("context_id" to id, "credential_id" to credential, "summary_hash" to summary,
            "expires_at" to expires.epochSecond, "consent_required" to true, "details" to details)
    }
    fun present(opaque: String, id: String, summary: String): Boolean {
        val p = previews[id] ?: throw AccessDenied()
        if (!p.expires.isAfter(Instant.now()) || p.summary != summary) throw AccessDenied()
        return registry.dispatch(hash(opaque), id, summary) {
            previews.remove(id)
            upstream.present(p.wallet, p.url, p.query, p.credential, p.key)
        }
    }
}

/** Only request references produced by the isolated P2 verifier are accepted. */
fun validatePresentationUrl(value: String) {
    if (value.length > 4096) throw AccessDenied()
    val uri = URI(value)
    if (uri.scheme != "openid4vp" || uri.host != "authorize" || uri.rawPath.orEmpty().isNotEmpty() ||
        uri.userInfo != null || uri.fragment != null) throw AccessDenied()
    val params = uri.rawQuery?.split('&')?.map {
        val pair = it.split('=', limit = 2)
        if (pair.size != 2) throw AccessDenied()
        URLDecoder.decode(pair[0], Charsets.UTF_8) to URLDecoder.decode(pair[1], Charsets.UTF_8)
    } ?: throw AccessDenied()
    if (params.map { it.first }.toSet().size != params.size ||
        params.any { it.first !in setOf("request_uri", "request_uri_method", "client_id") }) throw AccessDenied()
    val values = params.toMap()
    val request = URI(values["request_uri"] ?: throw AccessDenied())
    if (request.scheme != "http" || request.rawAuthority != "verifier:7004" || request.query != null ||
        request.fragment != null || !request.rawPath.matches(Regex("/verification-session/[A-Za-z0-9_-]{1,128}/request"))) throw AccessDenied()
    if (values["request_uri_method"]?.let { it != "post" } == true) throw AccessDenied()
    val client = values["client_id"] ?: throw AccessDenied()
    if (!client.startsWith("redirect_uri:http://verifier:7004/") && !client.startsWith("http://verifier:7004/")) throw AccessDenied()
}
