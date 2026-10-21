package id.nusacred.custodian

import com.nimbusds.jose.util.JSONObjectUtils
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class RecoveryAuthenticator(private val issuer: String, private val secret: ByteArray) {
    init { require(secret.size >= 43) }
    fun verify(path: String, timestamp: String, signature: String, body: String): Map<String, Any> {
        val time = timestamp.toLongOrNull() ?: throw AccessDenied()
        val now = Instant.now().epochSecond
        if (time < now - 30 || time > now + 30) throw AccessDenied()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val expected = mac.doFinal("$timestamp\n$path\n$body".toByteArray(Charsets.UTF_8))
        val supplied = try { Base64.getUrlDecoder().decode(signature) } catch (_: Exception) { throw AccessDenied() }
        if (!MessageDigest.isEqual(expected, supplied)) throw AccessDenied()
        val values = JSONObjectUtils.parse(body)
        if (values["issuer"] != issuer || (values["subject"] as? String).isNullOrBlank()) throw AccessDenied()
        return values
    }
}

fun Application.recoveryRoutes(registry: AccessRegistry, authenticator: RecoveryAuthenticator) {
    routing {
        for (action in listOf("begin", "complete")) post("/internal/recoveries/$action") {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (call.request.headers["Content-Type"] != "application/json") throw AccessDenied()
                    val bytes = call.receiveStream().use { it.readNBytes(4097) }
                    if (bytes.size > 4096) throw AccessDenied()
                    val values = authenticator.verify("/internal/recoveries/$action",
                        call.request.headers["X-Recovery-Time"] ?: "", call.request.headers["X-Recovery-Signature"] ?: "",
                        String(bytes, Charsets.UTF_8))
                    val issuer = values["issuer"] as String
                    val subject = values["subject"] as String
                    val operation = values["operation"] as? String ?: throw AccessDenied()
                    val revision = if (action == "begin") {
                        if (values["idp_write_fenced"] != true) throw AccessDenied()
                        registry.beginRecovery(issuer, subject, operation, idpWriteFenced = true)
                    } else {
                        val revision = (values["revision"] as? Number)?.toLong() ?: throw AccessDenied()
                        registry.completeRecovery(issuer, subject, operation, revision)
                        revision
                    }
                    mapOf("operation" to operation, "revision" to revision)
                }
                call.response.headers.append("Cache-Control", "no-store")
                call.respondText(json(result), ContentType.Application.Json)
            } catch (_: Exception) {
                call.respondText("{\"error\":\"Recovery acknowledgement unavailable\"}", ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable)
            }
        }
    }
}
