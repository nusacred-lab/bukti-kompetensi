package id.nusacred.common

import id.nusacred.custodian.hash
import id.nusacred.contracts.Role
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.*

class FoundationTest {
    private fun principal(org: String = "company-a", role: Role = Role.COMPANY_ADMIN, session: String = "session-a") = Principal("account-a",org,setOf(role),session)
    private fun store(service: String = "company") = DomainStore(Files.createTempFile("p3-test-", ".sqlite"),service)
    private fun guard() = RequestGuard("https://company.example", { opaque, _ ->
        if(opaque != "valid") error("Invalid session")
        principal() to "csrf"
    })
    @Test fun `job ownership and idempotency survive restart`() {
        val path = Files.createTempFile("p3-restart-", ".sqlite")
        DomainStore(path,"company").use { s ->
            val p = principal()
            val first = s.saveJob(p,"electrician","Electrician","DEMO-ELEC-001","competent","key-000000000001")
            assertEquals(first,s.saveJob(p,"electrician","Electrician","DEMO-ELEC-001","competent","key-000000000001"))
            assertEquals(409,assertFailsWith<ApiFailure> { s.saveJob(p,"electrician","Changed","OTHER","competent","key-000000000001") }.status)
            assertEquals(404,assertFailsWith<ApiFailure> { s.saveJob(principal("company-b"),"electrician","Other","OTHER","competent","key-000000000002") }.status)
        }
        DomainStore(path,"company").use { assertEquals("Electrician",it.job("electrician")["title"]) }
        assertFailsWith<IllegalArgumentException> { DomainStore(path,"issuer") }
    }
    @Test fun `role and organization are both required`() {
        store().use { s ->
            assertEquals(403,assertFailsWith<ApiFailure> { s.saveJob(principal(role=Role.CANDIDATE),"job","Job","CODE","competent","key-000000000001") }.status)
            assertEquals(403,assertFailsWith<ApiFailure> { s.saveJob(principal().copy(organization=null),"job","Job","CODE","competent","key-000000000001") }.status)
        }
    }
    @Test fun `verification is private and snapshots job rules`() {
        store().use { s ->
            val p=principal(); s.saveJob(p,"job","Electrician","DEMO-ELEC-001","competent","key-000000000001")
            val id=s.verification(p,"job","key-000000000002")["id"] as String
            assertEquals(404,assertFailsWith<ApiFailure> { s.result(principal("company-b",Role.CANDIDATE,"other"),id) }.status)
            assertEquals("INCOMPLETE",s.result(p,id)["decision"])
            s.trust(p,"did:web:issuer.example")
            assertTrue(s.finalizeCredential(id,VerifiedCompetency("did:web:issuer.example","candidate",s.job("job")["competencyCode"] as String,"competent")))
            s.trust(p,null,hash("did:web:issuer.example"))
            assertFalse(s.finalizeCredential(id,VerifiedCompetency("did:web:untrusted.example","candidate","OTHER","competent")))
            assertEquals("FULFILLED",s.result(p,id)["decision"])
            assertEquals("FULFILLED",s.verification(p,"job","key-000000000002")["decision"])
            assertEquals(1L,s.result(p,id)["trustRevision"])
            val next=s.verification(p,"job","key-000000000003")["id"] as String
            assertTrue(s.finalizeCredential(next,VerifiedCompetency("did:web:issuer.example","candidate",s.job("job")["competencyCode"] as String,"competent")))
            assertEquals("UNTRUSTED_ISSUER",s.result(p,next)["reason"])
        }
    }
    @Test fun `issuer operators cannot access another organization offer`() {
        store("issuer").use { s ->
            val p=principal("issuer-a",Role.ISSUER_OPERATOR)
            val a=s.assessment(p,"recipient-account","synthetic-001","DEMO-ELEC-001","competent","key-000000000001")["id"] as String
            val offer=s.createOffer(p,a,"key-000000000002")["id"] as String
            assertEquals("PENDING",s.offer(p,offer)["state"])
            assertEquals(404,assertFailsWith<ApiFailure> { s.offer(principal("issuer-b",Role.ISSUER_ADMIN),offer) }.status)
            assertEquals(404,assertFailsWith<ApiFailure> { s.createOffer(principal("issuer-b",Role.ISSUER_ADMIN),a,"key-000000000003") }.status)
        }
    }
    @Test fun `migration failure rolls back and edited history is rejected`() {
        val path=Files.createTempFile("p3-migration-", ".sqlite")
        SqlStore(path,listOf("CREATE TABLE demo(id TEXT PRIMARY KEY)")).close()
        assertFailsWith<IllegalArgumentException> { SqlStore(path,listOf("CREATE TABLE demo(id INTEGER PRIMARY KEY)")) }
        assertFails { SqlStore(path,listOf("CREATE TABLE demo(id TEXT PRIMARY KEY)","CREATE TABLE later(id TEXT);\nINVALID SQL")) }
        SqlStore(path,listOf("CREATE TABLE demo(id TEXT PRIMARY KEY)")).use { s ->
            assertEquals(1,s.query("SELECT COUNT(*) FROM schema_migrations") { it.getInt(1) }.single())
            assertTrue(s.query("SELECT name FROM sqlite_master WHERE name='later'") { it.getString(1) }.isEmpty())
        }
    }
    @Test fun `anonymous wrong origin csrf and excessive bodies are rejected`() = testApplication {
        val s=store()
        application { businessRoutes(s,guard()) }
        assertEquals(401,client.put("/api/v1/jobs/job").status.value)
        fun io.ktor.client.request.HttpRequestBuilder.auth() { header("Authorization","Bearer valid") }
        assertEquals(403,client.put("/api/v1/jobs/job") { auth(); header("Origin","https://attacker.example") }.status.value)
        assertEquals(403,client.put("/api/v1/jobs/job") { header("Cookie","__Host-nusacred-session=valid"); header("Origin","https://company.example") }.status.value)
        assertEquals(401,client.put("/api/v1/jobs/job") { auth(); header("Cookie","other=1") }.status.value)
        assertEquals(413,client.put("/api/v1/jobs/job") { auth(); contentType(ContentType.Application.Json); setBody("x".repeat(32769)) }.status.value)
        assertEquals(415,client.put("/api/v1/jobs/job") { auth(); setBody("{}") }.status.value)
        val good=client.put("/api/v1/jobs/job") {
            auth(); contentType(ContentType.Application.Json); header("Idempotency-Key","key-000000000001")
            setBody("""{"title":"Electrician","competencyCode":"DEMO-ELEC-001","assessmentResult":"competent"}""")
        }
        assertEquals(200,good.status.value)
        assertEquals("no-store",good.headers["Cache-Control"])
        val public=client.get("/api/v1/jobs/job")
        assertEquals(200,public.status.value); assertFalse(public.bodyAsText().contains("organization"))
        s.close()
    }
    @Test fun `rate limits expire and cannot grow without bound`() {
        var now=0L
        val g=RequestGuard("https://company.example",{ _, _ -> principal() to "csrf" },{now},2)
        repeat(60) { g.rate("one") }
        assertEquals(429,assertFailsWith<ApiFailure> { g.rate("one") }.status)
        g.rate("two")
        assertEquals(429,assertFailsWith<ApiFailure> { g.rate("three") }.status)
        now=60; g.rate("three")
    }
    @Test fun `reference resolver rejects arbitrary urls`() {
        val policy=ReferencePolicy(mapOf("request-1" to java.net.URI("http://verifier:7004/verification-session/one/request")))
        assertEquals("verifier",policy.resolve("request-1").host)
        for (url in listOf("http://127.0.0.1/admin","http://169.254.169.254/","https://attacker.example/","request-1/../admin")) {
            assertEquals(400,assertFailsWith<ApiFailure> { policy.resolve(url) }.status)
        }
        assertFailsWith<IllegalArgumentException> { ReferencePolicy(mapOf("bad" to java.net.URI("http://verifier:7004@evil.example/"))) }
    }
    @Test fun `expiration is terminal and late results cannot overwrite it`() {
        var now=1000L
        DomainStore(Files.createTempFile("p3-expiry-", ".sqlite"),"company",{now}).use { s ->
            val p=principal(); s.saveJob(p,"job","Job","CODE","competent","key-000000000001")
            val id=s.verification(p,"job","key-000000000002")["id"] as String
            now+=301
            assertEquals("EXPIRED",s.result(p,id)["reason"])
            assertEquals(true,s.result(p,id)["final"])
            assertFalse(s.finalizeCredential(id,VerifiedCompetency("did:web:issuer.example","candidate",s.job("job")["competencyCode"] as String,"competent")))
            assertEquals("EXPIRED",s.result(p,id)["reason"])
        }
    }
    @Test fun `trust mutation and evaluation share one consistent transaction`() {
        store().use { s ->
            val p=principal(); s.saveJob(p,"job","Job","CODE","competent","key-000000000001")
            val id=s.verification(p,"job","key-000000000002")["id"] as String
            val barrier=java.util.concurrent.CyclicBarrier(2)
            val executor=java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val trust=executor.submit { barrier.await(); s.trust(p,"did:web:issuer.example") }
                val evaluation=executor.submit { barrier.await(); s.finalizeCredential(id,VerifiedCompetency("did:web:issuer.example","candidate",s.job("job")["competencyCode"] as String,"competent")) }
                trust.get(5,java.util.concurrent.TimeUnit.SECONDS); evaluation.get(5,java.util.concurrent.TimeUnit.SECONDS)
                val result=s.result(p,id)
                assertTrue((result["trustRevision"]==0L && result["decision"]=="NOT_FULFILLED") ||
                    (result["trustRevision"]==1L && result["decision"]=="FULFILLED"))
            } finally { executor.shutdownNow() }
        }
    }
}
