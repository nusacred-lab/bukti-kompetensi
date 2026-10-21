package id.nusacred.custodian

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.request.receiveStream
import com.nimbusds.jose.util.JSONObjectUtils
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Native-only P2 private diagnostic routes; public ingress continues to deny them. */
fun Application.walletRoutes(auth: OidcService, wallet: WalletService) {
    routing {
        for (action in listOf("provision", "reconcile", "credentials", "preview", "present")) {
            suspend fun handle(call: io.ktor.server.application.ApplicationCall) {
                try {
                    val header = call.request.headers["Authorization"] ?: throw AuthRejected()
                    if (!header.startsWith("Bearer ") || call.request.headers["Cookie"] != null) throw AuthRejected()
                    val opaque = header.removePrefix("Bearer ")
                    val result = withContext(Dispatchers.IO) {
                        auth.session(opaque, "native")
                        if (action in setOf("preview", "present")) {
                            if (call.request.headers["Content-Type"]?.substringBefore(';') != "application/json") throw AccessDenied()
                            val bytes = call.receiveStream().use { it.readNBytes(8193) }
                            if (bytes.size > 8192) throw AccessDenied()
                            val values = JSONObjectUtils.parse(String(bytes, Charsets.UTF_8))
                            if (action == "preview") {
                                if (values.keys != setOf("request_url")) throw AccessDenied()
                                json(wallet.preview(opaque, values["request_url"] as? String ?: throw AccessDenied()))
                            } else {
                                if (values.keys != setOf("context_id", "summary_hash", "consent") || values["consent"] != true) throw AccessDenied()
                                json(mapOf("transmission_success" to wallet.present(opaque,
                                    values["context_id"] as? String ?: throw AccessDenied(),
                                    values["summary_hash"] as? String ?: throw AccessDenied())))
                            }
                        } else if (action == "credentials") wallet.credentials(opaque) else {
                            val b = if (action == "provision") wallet.provision(opaque) else wallet.reconcile(opaque)
                            json(mapOf("state" to b.state, "wallet_id" to b.walletId))
                        }
                    }
                    call.response.headers.append("Cache-Control", "no-store")
                    call.respondText(result, ContentType.Application.Json)
                } catch (_: ReconciliationRequired) {
                    call.respondText("{\"error\":\"Wallet reconciliation required\"}", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                } catch (_: Exception) {
                    call.respondText("{\"error\":\"Wallet access denied\"}", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                }
            }
            if (action == "credentials") get("/api/v1/wallet/credentials") { handle(call) }
            else post("/api/v1/wallet/$action") { handle(call) }
        }
    }
}
