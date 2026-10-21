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
class WalletClient internal constructor(origin: String, private val transport: ((HttpRequest) -> Pair<Int, java.io.InputStream>)? = null) {
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
        val request = b.build()
        val (status, bodyStream) = transport?.invoke(request) ?: http.send(request, HttpResponse.BodyHandlers.ofInputStream()).let { it.statusCode() to it.body() }
        val bytes = bodyStream.use { it.readNBytes(2_097_153) }
        if (status !in 200..299 || bytes.size > 2_097_152) throw ReconciliationRequired()
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
    fun credential(wallet: String, id: String): String = request("/wallet/${segment(wallet)}/credentials/${segment(id)}")
    fun preview(wallet: String, url: String): Map<String, Any> = JSONObjectUtils.parse(request(
        "/wallet/${segment(wallet)}/credentials/present/preview", mapOf("requestUrl" to url)))
    fun present(wallet: String, url: String, query: String, credential: String, key: String): Boolean {
        val vp = JSONObjectUtils.parse(request("/wallet/${segment(wallet)}/credentials/present/build-vp-token",
            mapOf("requestUrl" to url, "keyId" to key, "selectedCredentialOptions" to
                listOf(mapOf("queryId" to query, "credentialId" to credential)))))
        val response = JSONObjectUtils.parse(request("/wallet/${segment(wallet)}/credentials/present/send-response",
            mapOf("requestUrl" to url, "vpToken" to (vp["vpToken"] as? String ?: throw AccessDenied()))))
        return response["transmission_success"] as? Boolean ?: throw ReconciliationRequired()
    }
}

class WalletService(private val registry: AccessRegistry, private val upstream: WalletClient) {
    fun provision(opaque: String): WalletBinding = registry.provision(hash(opaque), upstream::create)
    fun reconcile(opaque: String): WalletBinding = registry.reconcile(hash(opaque), upstream::lookup)
    fun credentials(opaque: String): String = registry.read(hash(opaque)) {
        val binding = registry.binding(hash(opaque)) ?: throw AccessDenied()
        if (binding.state != "READY") throw AccessDenied()
        upstream.credentials(binding.walletId!!)
    }
    fun credential(opaque: String, id: String): String = registry.read(hash(opaque)) {
        val binding = registry.binding(hash(opaque))?.takeIf { it.state == "READY" } ?: throw AccessDenied()
        upstream.credential(binding.walletId!!,id)
    }
    fun preview(opaque: String, url: String): Map<String, Any?> = registry.read(hash(opaque)) {
        validatePresentationUrl(url)
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
        registry.savePreview(hash(opaque), id, summary, expires.epochSecond, json(mapOf(
            "url" to url, "wallet" to b.walletId, "key" to b.keyId, "query" to query,
            "credential" to credential, "summary" to summary, "disclosures" to disclosures, "expires" to expires.epochSecond)))
        mapOf("context_id" to id, "credential_id" to credential, "summary_hash" to summary,
            "expires_at" to expires.epochSecond, "consent_required" to true, "details" to details)
    }
    fun present(opaque: String, id: String, summary: String): Boolean {
        val p = JSONObjectUtils.parse(registry.preview(hash(opaque),id))
        if (p["summary"] != summary) throw AccessDenied()
        return registry.dispatch(hash(opaque), id, summary) {
            upstream.present(p["wallet"] as String,p["url"] as String,p["query"] as String,p["credential"] as String,p["key"] as String)
        }
    }
    fun presentConsented(opaque: String, id: String, summary: String, credential: String, disclosures: List<String>, key: String): Map<String,Any?> = synchronized(registry) {
        // Idempotent retries return the durable outcome; they never dispatch a second time.
        val state = registry.operation(hash(opaque),id)["state"]
        if(state == "PENDING") {
            val p = JSONObjectUtils.parse(registry.preview(hash(opaque),id))
            if(p["credential"] != credential || p["disclosures"] != disclosures || p["summary"] != summary) throw AccessDenied()
        }
        registry.consent(hash(opaque),id,summary,key,hash(json(mapOf("id" to id,"summary" to summary,"credential" to credential,"disclosures" to disclosures))))
        if(state == "PENDING") present(opaque,id,summary)
        registry.operation(hash(opaque),id)
    }
    fun decline(opaque: String,id: String) = registry.decline(hash(opaque),id)
    fun operation(opaque: String,id: String) = registry.operation(hash(opaque),id)

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
