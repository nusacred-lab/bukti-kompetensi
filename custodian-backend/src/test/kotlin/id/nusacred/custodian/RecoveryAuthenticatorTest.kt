package id.nusacred.custodian

import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*
import org.junit.Test

class RecoveryAuthenticatorTest {
    private val secret = "a".repeat(64).toByteArray()
    private val issuer = "https://auth.example.test/realms/test"
    private val path = "/internal/recoveries/begin"
    private fun signature(path: String, time: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal("$time\n$path\n$body".toByteArray()))
    }
    @Test fun `signature binds timestamp endpoint issuer and exact body`() {
        val verifier = RecoveryAuthenticator(issuer, secret)
        val time = Instant.now().epochSecond.toString()
        val body = json(mapOf("issuer" to issuer, "subject" to "alice", "operation" to randomToken()))
        val sig = signature(path, time, body)
        assertEquals("alice", verifier.verify(path, time, sig, body)["subject"])
        assertFailsWith<AccessDenied> { verifier.verify(path, time, sig, body.replace("alice", "bob")) }
        assertFailsWith<AccessDenied> { verifier.verify("/internal/recoveries/complete", time, sig, body) }
        assertFailsWith<AccessDenied> { verifier.verify(path, time, "invalid", body) }
        val expired = (Instant.now().epochSecond - 31).toString()
        assertFailsWith<AccessDenied> { verifier.verify(path, expired, signature(path, expired, body), body) }
        val future = (Instant.now().epochSecond + 31).toString()
        assertFailsWith<AccessDenied> { verifier.verify(path, future, signature(path, future, body), body) }
        val wrong = body.replace(issuer, "https://other.example.test")
        assertFailsWith<AccessDenied> { verifier.verify(path, time, signature(path, time, wrong), wrong) }
    }
}
