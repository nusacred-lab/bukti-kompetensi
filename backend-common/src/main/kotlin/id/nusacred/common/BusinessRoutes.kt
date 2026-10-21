package id.nusacred.common

import id.nusacred.contracts.Role
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.businessRoutes(store: DomainStore, guard: RequestGuard) {
    routing {
        if (store.service == "issuer") {
            post("/api/v1/assessments") { call.api {
                val p = guard.identity(call,true)
                val b = call.jsonBody(setOf("candidateAccountId","candidateId","competencyCode","assessmentResult"))
                call.reply(store.assessment(p,b.text("candidateAccountId"),b.text("candidateId"),b.text("competencyCode"),b.text("assessmentResult"),call.idempotency()),201)
            } }
            post("/api/v1/assessments/{id}/offers") { call.api {
                val p = guard.identity(call,true); call.jsonBody(emptySet())
                call.reply(store.createOffer(p,call.parameters["id"]!!,call.idempotency()),202)
            } }
            get("/api/v1/offers/{id}") { call.api { call.reply(store.offer(guard.identity(call,false),call.parameters["id"]!!)) } }
        } else {
            get("/api/v1/browser-session") { call.api { call.reply(guard.openViewer(call)) } }
            put("/api/v1/jobs/{id}") { call.api {
                val p = guard.identity(call,true)
                val b = call.jsonBody(setOf("title","competencyCode","assessmentResult"))
                call.reply(store.saveJob(p,call.parameters["id"]!!,b.text("title",200),b.text("competencyCode"),b.text("assessmentResult"),call.idempotency()))
            } }
            get("/api/v1/jobs/{id}") { call.api {
                guard.rate("public:"+call.request.local.remoteHost,30)
                call.reply(store.job(call.parameters["id"]!!))
            } }
            post("/api/v1/jobs/{id}/verification-sessions") { call.api {
                val p = guard.owner(call,true); call.jsonBody(emptySet())
                call.reply(store.verification(p,call.parameters["id"]!!,call.idempotency()),202)
            } }
            get("/api/v1/verification-sessions/{id}") { call.api { call.reply(store.result(guard.owner(call,false),call.parameters["id"]!!)) } }
            get("/api/v1/trusted-issuers") { call.api {
                val p = guard.identity(call,false)
                fun page(name: String, default: Int) = call.request.queryParameters[name]?.toIntOrNull()
                    ?: if(call.request.queryParameters[name] == null) default else reject(400,"INVALID_PAGE","Invalid pagination.")
                call.reply(store.trusted(p,page("offset",0),page("limit",25)))
            } }
            post("/api/v1/trusted-issuers") { call.api {
                val p = guard.identity(call,true); val b = call.jsonBody(setOf("identity"))
                call.reply(store.trust(p,b.text("identity",2048),key=call.idempotency()),201)
            } }
            delete("/api/v1/trusted-issuers/{id}") { call.api {
                val p = guard.identity(call,true); call.reply(store.trust(p,null,call.parameters["id"]!!,call.idempotency()))
            } }
        }
    }
}
