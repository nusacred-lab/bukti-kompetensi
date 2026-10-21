package id.nusacred.custodian

import id.nusacred.common.*
import id.nusacred.contracts.Role
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** P2 compatibility aliases use the same origin, role, body and rate controls as P3. */
fun Application.walletRoutes(auth: OidcService, wallet: WalletService) {
    val guard = RequestGuard.oidc(auth)
    routing {
        for (action in listOf("provision", "reconcile", "credentials", "preview", "present")) {
            suspend fun handle(call: io.ktor.server.application.ApplicationCall) = call.api {
                guard.identity(call,action != "credentials").requireRole(Role.CANDIDATE)
                val header = call.request.headers["Authorization"] ?: throw AuthRejected()
                if (!header.startsWith("Bearer ") || call.request.headers["Cookie"] != null) throw AuthRejected()
                val opaque = header.removePrefix("Bearer ")
                val result = withContext(Dispatchers.IO) {
                    if (action in setOf("preview", "present")) {
                        val fields = if(action=="preview") setOf("request_url") else setOf("context_id","summary_hash","consent")
                        val values = call.jsonBody(fields)
                        if (action == "preview") json(wallet.preview(opaque,values.text("request_url",4096)))
                        else {
                            if(values["consent"] != true) reject(400,"CONSENT_REQUIRED","Explicit consent is required.")
                            json(mapOf("transmission_success" to wallet.present(opaque,values.text("context_id"),values.text("summary_hash"))))
                        }
                    } else if (action == "credentials") wallet.credentials(opaque) else {
                        val binding = if(action == "provision") wallet.provision(opaque) else wallet.reconcile(opaque)
                        json(mapOf("state" to binding.state,"wallet_id" to binding.walletId))
                    }
                }
                call.respondText(result,ContentType.Application.Json)
            }
            if (action == "credentials") get("/api/v1/wallet/credentials") { handle(call) }
            else post("/api/v1/wallet/$action") { handle(call) }
        }
    }
}
