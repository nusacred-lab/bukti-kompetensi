package id.nusacred.common

import id.nusacred.contracts.Role
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.businessRoutes(store: DomainStore, guard: RequestGuard, publicProfile: IssuerProfile? = null, directory: IssuerDirectory = IssuerDirectory()) {
    routing {
        if (store.service == "issuer") {
            get("/api/v1/issuer-profile") { call.api {
                guard.rate("issuer-profile:"+call.request.local.remoteHost,60)
                call.reply(publicProfile?.publicData() ?: reject(503,"PROFILE_UNAVAILABLE","The issuer has not published its profile yet."))
            } }
            get("/api/v1/assessments") { call.api { call.reply(store.assessments(guard.identity(call,false))) } }
            get("/api/v1/offers/{id}/qr") { call.api {
                val offer=store.offer(guard.identity(call,false),call.parameters["id"]!!)
                if((offer["expiresAt"] as Number).toLong() <= java.time.Instant.now().epochSecond) reject(410,"EXPIRED","Create a fresh offer.")
                val ref=offer["offerUrl"] as? String ?: reject(409,"NOT_READY","This offer is not ready.")
                call.reply(qrResponse(walletLink(ref,true)))
            } }

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
            get("/api/v1/jobs") { call.api { guard.rate("public:"+call.request.local.remoteHost,30);call.reply(store.jobs()) } }
            get("/api/v1/verification-sessions") { call.api { call.reply(store.results(guard.identity(call,false))) } }
            get("/api/v1/verification-sessions/{id}/qr") { call.api {
                val result=store.result(guard.owner(call,false),call.parameters["id"]!!)
                if(result["final"] == true) reject(410,"EXPIRED","Create a new verification session.")
                val ref=result["requestUrl"] as? String ?: reject(409,"NOT_READY","This request is not ready.")
                call.reply(qrResponse(walletLink(ref,false)))
            } }
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
            get("/api/v1/issuer-candidates") { call.api {
                val p=guard.identity(call,false);store.requireCompany(p)
                call.reply(mapOf("items" to listOf(directory.fetch().publicData())))
            } }
            post("/api/v1/issuer-reviews") { call.api {
                val p=guard.identity(call,true);store.requireCompany(p)
                val b=call.jsonBody(setOf("issuerId"));val key=call.idempotency()
                call.reply(store.reviewIssuer(p,directory.fetch(b.text("issuerId")),key),201)
            } }
            get("/api/v1/issuer-reviews/{id}") { call.api {
                call.reply(store.issuerReview(guard.identity(call,false),call.parameters["id"]!!))
            } }
            post("/api/v1/trusted-issuers") { call.api {
                val p=guard.identity(call,true);store.requireCompany(p)
                val b=call.jsonBody(setOf("reviewId","verificationMethod","verificationNote","confirmed"));val key=call.idempotency()
                val id=b.text("reviewId");store.issuerReview(p,id)
                call.reply(store.approveIssuer(p,id,directory.fetch(),b.text("verificationMethod"),b.text("verificationNote",1000),b.text("confirmed"),key),201)
            } }
            delete("/api/v1/trusted-issuers/{id}") { call.api {
                val p = guard.identity(call,true); call.reply(store.trust(p,null,call.parameters["id"]!!,call.idempotency()))
            } }
        }
    }
}
