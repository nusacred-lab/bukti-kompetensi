package id.nusacred.common

import com.nimbusds.jose.util.JSONObjectUtils
import id.nusacred.custodian.json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** Fixed private origins only. Upstream bodies, bearer tokens and VP tokens never reach a browser. */
class CredentialProtocol(
    private val transport: ((HttpRequest) -> Pair<Int, java.io.InputStream>)? = null
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build()
    internal fun request(service: String, path: String, body: Map<String, Any?>? = null): Map<String, Any> {
        val origin = when(service) { "issuer" -> "http://issuer:7005"; "verifier" -> "http://verifier:7004"; else -> error("Invalid service") }
        require(if(service == "issuer") path.startsWith("/issuer2/") || path == "/openid4vci/nonce" else path.startsWith("/verification-session/"))
        val builder = HttpRequest.newBuilder(URI(origin+path)).timeout(Duration.ofSeconds(20)).header("Accept","application/json")
        if(body == null) builder.GET() else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json(body)))
        try {
            val req = builder.build()
            val (status,stream) = transport?.invoke(req) ?: http.send(req,HttpResponse.BodyHandlers.ofInputStream()).let { it.statusCode() to it.body() }
            val bytes = stream.use { it.readNBytes(2_097_153) }
            if(status == 404) throw ApiFailure(404,"VERIFIER_SESSION_MISSING","The protocol session is unavailable.")
            if(status !in 200..299 || bytes.size > 2_097_152) throw IllegalStateException()
            return JSONObjectUtils.parse(String(bytes,Charsets.UTF_8))
        } catch(e: ApiFailure) { throw e } catch(_: Exception) {
            throw ApiFailure(503,"DEPENDENCY_UNAVAILABLE","The credential service is unavailable.",true)
        }
    }
    fun offer(candidate: String, competency: String, assessment: String): Map<String,Any> {
        val issued=Instant.now().epochSecond
        // The pinned SD-JWT handler rounds generated timestamp templates. Explicit server-owned
        // NumericDates preserve the demo's actual offer time and exact 365-day lifetime.
        return request("issuer","/issuer2/credential-offers",mapOf("profileId" to "competencyDemo", "authMethod" to "PRE_AUTHORIZED",
            "runtimeOverrides" to mapOf("credentialData" to mapOf("candidate_id" to candidate,"competency_code" to competency,"assessment_result" to assessment),
                "mapping" to mapOf("id" to "<uuid>","iat" to issued,"nbf" to issued,"exp" to issued+365L*86400))))
    }
    fun nonce(): String = request("issuer","/openid4vci/nonce",emptyMap())["c_nonce"] as? String ?: error("Invalid nonce")
    fun createVerification(): Map<String,Any> = request("verifier","/verification-session/create", verificationSetup())
    fun info(id: String): Map<String,Any> {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return request("verifier","/verification-session/$id/info")
    }
}

const val COMPETENCY_VCT = "http://issuer:7005/openid4vci/competency_demo"
const val BUSINESS_POLICY_VERSION = "competency-v1"
val REQUIRED_CLAIMS = listOf("id","candidate_id","competency_code","assessment_result","iss","cnf","iat","nbf","exp")
internal val REQUIRED_VP_POLICIES = setOf("dc+sd-jwt/audience-check","dc+sd-jwt/kb-jwt_signature","dc+sd-jwt/nonce-check",
    "dc+sd-jwt/sd_hash-check","dc+sd-jwt/kb-jwt_iat-check","dc+sd-jwt/exp-check","dc+sd-jwt/nbf-check","dc+sd-jwt/transaction-data-hash-check")
fun verificationSetup(): Map<String,Any?> = mapOf("flow_type" to "cross_device", "core_flow" to mapOf(
    "dcql_query" to mapOf("credentials" to listOf(mapOf("id" to "competency","format" to "dc+sd-jwt","multiple" to true,
        "meta" to mapOf("vct_values" to listOf(COMPETENCY_VCT)),"require_cryptographic_holder_binding" to true,
        "claims" to REQUIRED_CLAIMS.map { mapOf("path" to listOf(it)) }))),
    // Omit vp_policies: the pinned verifier installs its complete default VP policy list.
    "policies" to mapOf("vc_policies" to listOf(mapOf("policy" to "signature"),mapOf("policy" to "expiration","requireField" to true),mapOf("policy" to "not-before")))))

internal data class VerifiedCompetency(val issuer: String, val candidate: String, val competency: String, val assessment: String)
/** Consumes only a private verifier response for the stored upstream session, never browser claims. */
internal fun verifiedCompetency(info: Map<String,Any>, expectedSession: String, now: Long = Instant.now().epochSecond): VerifiedCompetency {
    fun invalid(): Nothing = throw ApiFailure(422,"INVALID_PROOF","The credential proof is invalid.")
    fun obj(value: Any?): Map<*,*> = value as? Map<*,*> ?: invalid()
    if(info["id"] != expectedSession || info["status"] != "SUCCESSFUL") invalid()
    val policies = obj(info["policy_results"])
    val vp = obj(obj(policies["vp_policies"])["competency"])
    if(!vp.keys.containsAll(REQUIRED_VP_POLICIES) || vp.values.any { obj(it)["success"] != true }) invalid()
    val vc = policies["vc_policies"] as? List<*> ?: invalid()
    if(vc.isEmpty() || vc.any { obj(it)["success"] != true }) invalid()
    val ids = vc.map { obj(obj(it)["policy"])["policy"] }.toSet()
    if(ids != setOf("signature","expiration","not-before") || vc.size != 3) invalid()
    if(vc.none { obj(obj(it)["policy"])["policy"] == "expiration" && obj(obj(it)["policy"])["requireField"] == true }) invalid()
    if(vc.any { obj(it)["query_id"] != "competency" || (obj(it)["credential_index"] as? Number)?.toLong() != 0L }) invalid()
    val specific = obj(policies["specific_vc_policies"])
    if(specific.values.any { entries -> (entries as? List<*>)?.any { obj(it)["success"] != true } != false }) invalid()
    val candidates = obj(info["presented_credentials"])
    if(candidates.keys != setOf("competency")) invalid()
    val credential = obj((candidates["competency"] as? List<*>)?.singleOrNull())
    // The pinned DigitalCredential wire type carries its decoded, verified credentialData.
    if(credential["type"] != "vc-sd_jwt") invalid()
    val claims = obj(credential["credentialData"])
    fun text(name: String): String = (claims[name] as? String)?.takeIf { it.isNotBlank() && it.length <= 2048 && it.none(Char::isISOControl) } ?: invalid()
    fun date(name: String): Long {
        val n = claims[name]
        if(n !is Long && n !is Int) invalid()
        return (n as Number).toLong().takeIf { it in 0..253402300799L } ?: invalid()
    }
    if(text("vct") != COMPETENCY_VCT) invalid()
    text("id")
    val iat=date("iat"); val nbf=date("nbf"); val exp=date("exp")
    if(iat>now || nbf>now || exp<=now || exp<=iat || exp<=nbf) invalid()
    val cnf=obj(claims["cnf"])
    val jwk=cnf["jwk"] as? Map<*,*>
    if(jwk == null || jwk["kty"] != "EC" || jwk["crv"] != "P-256" || jwk["x"] !is String || jwk["y"] !is String || "d" in jwk) invalid()
    return VerifiedCompetency(text("iss"),text("candidate_id"),text("competency_code"),text("assessment_result"))
}

fun validateOfferUrl(value: String) {
    val uri=URI(value)
    require(value.length <= 4096 && uri.scheme == "openid-credential-offer" && uri.rawAuthority.orEmpty().isEmpty() && uri.rawPath.orEmpty().isEmpty() && uri.userInfo == null && uri.fragment == null)
    val query=uri.rawQuery?.split('&')?.map { it.split('=',limit=2) } ?: error("Invalid offer")
    require(query.size == 1 && query.single().size == 2 && query.single()[0] == "credential_offer_uri")
    val ref=URI(java.net.URLDecoder.decode(query.single()[1],Charsets.UTF_8))
    require(ref.scheme == "http" && ref.rawAuthority == "issuer:7005" && ref.fragment == null &&
        ref.rawPath == "/openid4vci/credential-offer" && ref.rawQuery?.matches(Regex("id=[A-Za-z0-9_-]{1,128}")) == true)
}
fun validateProtocolRequest(value: String, expectedSession: String) {
    val uri=URI(value)
    require(value.length <= 4096 && uri.scheme == "openid4vp" && uri.host == "authorize" && uri.rawPath.orEmpty().isEmpty() && uri.userInfo == null && uri.fragment == null)
    val parts=uri.rawQuery?.split('&')?.map {
        val pair=it.split('=',limit=2); require(pair.size == 2)
        java.net.URLDecoder.decode(pair[0],Charsets.UTF_8) to java.net.URLDecoder.decode(pair[1],Charsets.UTF_8)
    } ?: error("Invalid request")
    require(parts.map { it.first }.toSet().size == parts.size)
    val params=parts.toMap()
    require(params.keys.all { it in setOf("request_uri","request_uri_method","client_id") })
    require(params["request_uri"] == "http://verifier:7004/verification-session/$expectedSession/request")
    require(params["request_uri_method"] in setOf(null,"post"))
    require(params["client_id"]?.let { it.startsWith("redirect_uri:http://verifier:7004/") || it.startsWith("http://verifier:7004/") } == true)
}
