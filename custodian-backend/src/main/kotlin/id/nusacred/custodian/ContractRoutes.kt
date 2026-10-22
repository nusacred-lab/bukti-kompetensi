package id.nusacred.custodian

import id.nusacred.common.*
import id.nusacred.contracts.Role
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.respondText

/** Stable P3 routes coexist with the P2 APK endpoints during migration. */
fun Application.contractRoutes(auth: OidcService, wallet: WalletService) {
    contractRoutes(wallet, RequestGuard.oidc(auth))
}

internal fun Application.contractRoutes(wallet: WalletService, guard: RequestGuard) {
    routing {
        get("/api/v1/wallet/credentials/{id}") { call.api {
            guard.identity(call,false).requireRole(Role.CANDIDATE)
            val opaque = call.request.headers["Authorization"]?.removePrefix("Bearer ") ?: reject(403,"NATIVE_REQUIRED","Use the wallet application.")
            call.respondText(wallet.credential(opaque,call.parameters["id"]!!),io.ktor.http.ContentType.Application.Json)
        } }
        get("/api/v1/me") { call.api {
            val p = guard.identity(call,false)
            call.reply(mapOf("accountId" to p.account,"roles" to p.roles.map { it.name },"organizationId" to p.organization))
        } }
        post("/api/v1/exchange-contexts") { call.api {
            guard.identity(call,true).requireRole(Role.CANDIDATE)
            val b = call.jsonBody(setOf("reference","credentialId"))
            val opaque = call.request.headers["Authorization"]?.removePrefix("Bearer ")
                ?: reject(403,"NATIVE_REQUIRED","Use the wallet application.")
            val reference=b.text("reference",4096)
            if(reference.startsWith("nusacred-offer:")) {
                call.reply(wallet.receivePreview(opaque,reference),201)
                return@api
            }
            val preview = wallet.preview(opaque,reference,if(b["credentialId"] == null) null else b.text("credentialId"))
            call.reply(mapOf("id" to preview["context_id"],"credentialId" to preview["credential_id"],"previewHash" to preview["summary_hash"],
                "expiresAt" to preview["expires_at"],"consentRequired" to true,"details" to preview["details"],"choices" to preview["choices"]),201)
        } }
        post("/api/v1/exchange-contexts/{id}/present") { call.api {
            guard.identity(call,true).requireRole(Role.CANDIDATE)
            val b = call.jsonBody(setOf("previewHash","consent","credentialId","disclosures"))
            if(b["consent"] != true) reject(400,"CONSENT_REQUIRED","Explicit consent is required.")
            val disclosures = (b["disclosures"] as? List<*>)?.map { it as? String ?: reject(400,"INVALID_FIELD","Invalid disclosure.") }
                ?: reject(400,"INVALID_FIELD","Invalid disclosures.")
            val opaque = call.request.headers["Authorization"]?.removePrefix("Bearer ") ?: reject(403,"NATIVE_REQUIRED","Use the wallet application.")
            call.reply(wallet.presentConsented(opaque,call.parameters["id"]!!,b.text("previewHash"),b.text("credentialId"),disclosures,call.idempotency()),202)
        } }
        post("/api/v1/exchange-contexts/{id}/accept") { call.api {
            guard.identity(call,true).requireRole(Role.CANDIDATE)
            val b=call.jsonBody(setOf("previewHash","consent","credentialId","disclosures"))
            if(b["consent"] != true || b["credentialId"] != null || b["disclosures"] != emptyList<String>())
                reject(400,"CONSENT_REQUIRED","Approve the exact receiving preview.")
            val opaque=call.request.headers["Authorization"]?.removePrefix("Bearer ") ?: reject(403,"NATIVE_REQUIRED","Use the wallet application.")
            call.reply(wallet.receiveConsented(opaque,call.parameters["id"]!!,b.text("previewHash"),call.idempotency()),202)
        } }
        post("/api/v1/exchange-contexts/{id}/decline") { call.api {
            guard.identity(call,true).requireRole(Role.CANDIDATE); call.jsonBody(emptySet())
            val opaque = call.request.headers["Authorization"]?.removePrefix("Bearer ") ?: reject(403,"NATIVE_REQUIRED","Use the wallet application.")
            wallet.decline(opaque,call.parameters["id"]!!); call.reply(mapOf("state" to "DECLINED"))
        } }
        get("/api/v1/operations/{id}") { call.api {
            guard.identity(call,false).requireRole(Role.CANDIDATE)
            val opaque = call.request.headers["Authorization"]?.removePrefix("Bearer ") ?: reject(403,"NATIVE_REQUIRED","Use the wallet application.")
            call.reply(wallet.operation(opaque,call.parameters["id"]!!))
        } }
    }
}
