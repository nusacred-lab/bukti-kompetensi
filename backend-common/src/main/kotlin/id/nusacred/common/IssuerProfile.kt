package id.nusacred.common

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jose.util.JSONObjectUtils
import id.nusacred.custodian.json
import id.nusacred.custodian.hash
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Self-published information, never an accreditation or automatic trust decision. */
data class IssuerProfile(val identity: String) {
    init {
        require(identity.length <= 2048 && identity.startsWith("did:jwk:"))
        val key = JWK.parse(Base64URL(identity.removePrefix("did:jwk:")).decodeToString())
        require(!key.isPrivate && key.keyType.value == "EC" && key.toJSONObject()["crv"] == "P-256")
    }
    fun publicData(): Map<String, Any?> = linkedMapOf(
        "id" to ID, "legalName" to "LSP Teknik Ketenagalistrikan Nusantara (demo)",
        "displayName" to "LSP Nusantara", "role" to "Independent credential issuer",
        "profileUrl" to "$ORIGIN/?view=issuer-profile", "sourceUrl" to "$ORIGIN/api/v1/issuer-profile",
        "identity" to identity, "identityFingerprint" to hash(identity),
        "competencyCode" to "DEMO-ELEC-001", "scope" to "Electrical installation competency (synthetic demo)",
        "contactUrl" to "$ORIGIN/?view=issuer-profile", "publicationStatus" to "SELF_PUBLISHED",
        "disclaimer" to "Synthetic organization. No real accreditation is claimed. Verify the organization and its identity through an independent, known channel before approval."
    )
    val digest get() = hash(json(publicData()))
    companion object {
        const val ID = "lsp-nusantara"
        const val ORIGIN = "https://lsp-nusantara.demo.inoerawan.id"
        fun fromEnvironment(): IssuerProfile? = System.getenv("ISSUER_PUBLIC_IDENTITY_FILE")?.let { readPublication(Path.of(it)) }
        fun readPublication(path: Path): IssuerProfile {
            val bytes = Files.newInputStream(path).use { stream -> stream.readNBytes(8193) }
            require(bytes.size <= 8192)
            val data=JSONObjectUtils.parse(String(bytes,Charsets.UTF_8))
            // The retained P2 publication uses "did"; P4 preparations use "identity".
            val profile=IssuerProfile(data.text(if(data.containsKey("identity")) "identity" else "did",2048))
            require(data["did"] == null || data["did"] == profile.identity)
            return profile
        }
    }
}

/** Operator-configured exact HTTPS endpoint; callers cannot supply a URL or a DID. */
class IssuerDirectory(private val origin: String = System.getenv("ISSUER_PROFILE_BACKCHANNEL_ORIGIN") ?: IssuerProfile.ORIGIN,
                      private val load: (() -> IssuerProfile)? = null) {
    init {
        val uri = URI(origin)
        require(uri.scheme == "https" && uri.host == URI(IssuerProfile.ORIGIN).host &&
            uri.port in setOf(-1,443,8443) && uri.userInfo == null && uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.fragment == null)
    }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build()
    fun fetch(id: String = IssuerProfile.ID): IssuerProfile {
        if(id != IssuerProfile.ID) reject(404,"NOT_FOUND","Issuer not found in the configured sources.")
        load?.let { return it() }
        try {
            val request = HttpRequest.newBuilder(URI("$origin/api/v1/issuer-profile"))
                .timeout(Duration.ofSeconds(8)).header("Accept","application/json").GET().build()
            val response = client.send(request,HttpResponse.BodyHandlers.ofInputStream())
            val raw = response.body().use { it.readNBytes(16385) }
            require(response.statusCode() == 200 && raw.size <= 16384 &&
                response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"))
            val body = JSONObjectUtils.parse(String(raw,Charsets.UTF_8))
            val profile = IssuerProfile(body.text("identity",2048))
            // Refuse partial/misleading profiles and changed source metadata.
            require(body == profile.publicData())
            return profile
        } catch(_: Exception) { reject(503,"ISSUER_SOURCE_UNAVAILABLE","The issuer profile could not be retrieved from its configured source. Try again later.") }
    }
}
