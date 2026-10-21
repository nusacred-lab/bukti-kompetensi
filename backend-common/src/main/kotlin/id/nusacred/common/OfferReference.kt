package id.nusacred.common

import com.nimbusds.jose.util.JSONObjectUtils
import id.nusacred.custodian.json
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated, encrypted recipient-bound capability. The preauthorized upstream code stays private. */
class OfferReference(key: ByteArray) {
    private val secret=SecretKeySpec(key.copyOf(),"AES")
    init { require(key.size == 32) }
    private val encoder=Base64.getUrlEncoder().withoutPadding()
    fun seal(id: String, account: String, candidate: String, url: String, expires: Long): String {
        validateOfferUrl(url)
        val nonce=ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,secret,GCMParameterSpec(128,nonce))
        cipher.updateAAD("nusacred-offer:v1".toByteArray())
        return "nusacred-offer:"+encoder.encodeToString(nonce+cipher.doFinal(json(mapOf("id" to id,"account" to account,
            "candidate" to candidate,"url" to url,"expires" to expires)).toByteArray()))
    }
    fun open(reference: String, account: String, now: Long): Map<String,Any> {
        try {
            require(reference.startsWith("nusacred-offer:") && reference.length <= 4096)
            val data=Base64.getUrlDecoder().decode(reference.removePrefix("nusacred-offer:"))
            require(data.size>28)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,secret,GCMParameterSpec(128,data.copyOfRange(0,12)))
            cipher.updateAAD("nusacred-offer:v1".toByteArray())
            val payload=JSONObjectUtils.parse(String(cipher.doFinal(data.copyOfRange(12,data.size)),Charsets.UTF_8))
            require(payload["account"] == account && (payload["expires"] as Number).toLong()>now)
            validateOfferUrl(payload["url"] as String)
            return payload
        } catch(_: Exception) { throw ApiFailure(404,"NOT_FOUND","The offer is not available.") }
    }
    companion object {
        fun fromEnvironment(): OfferReference? = System.getenv("OFFER_REFERENCE_KEY_FILE")?.let {
            OfferReference(Files.readAllBytes(Path.of(it)))
        }
    }
}
