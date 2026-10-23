package id.nusacred.common

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jose.util.JSONObjectUtils
import id.nusacred.contracts.Role
import id.nusacred.custodian.json
import id.nusacred.custodian.hash
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.*

class IssuerTrustTest {
    private fun profile() = IssuerProfile("did:jwk:"+Base64URL.encode(ECKeyGenerator(Curve.P_256).generate().toPublicJWK().toJSONString()))
    private val admin=Principal("admin-a","company-a",setOf(Role.COMPANY_ADMIN),"session-a")
    private val evidence="Compared the synthetic organization and its exact identity against the public LSP demo profile."
    @Test fun `public profile excludes private material and rejects unsafe source configuration`() {
        val p=profile()
        assertEquals("SELF_PUBLISHED",p.publicData()["publicationStatus"])
        assertEquals(hash(p.identity),p.publicData()["identityFingerprint"])
        assertFalse(json(p.publicData()).contains("issuerKey"))
        val privateDid="did:jwk:"+Base64URL.encode(ECKeyGenerator(Curve.P_256).generate().toJSONString())
        assertFailsWith<IllegalArgumentException> { IssuerProfile(privateDid) }
        for(url in listOf("http://issuer-backend:8080","https://attacker.example","https://lsp-nusantara.demo.inoerawan.id@evil.example","https://lsp-nusantara.demo.inoerawan.id/other"))
            assertFailsWith<IllegalArgumentException> { IssuerDirectory(url) }
        assertEquals(404,assertFailsWith<ApiFailure> { IssuerDirectory(load={p}).fetch("https://169.254.169.254/") }.status)
    }
    @Test fun `publication retains P2 and P4 identities without reading private issuer configuration`() {
        val p=profile();val path=Files.createTempFile("public-issuer-", ".json")
        for(field in listOf("did","identity")) {
            Files.writeString(path,json(mapOf(field to p.identity)))
            assertEquals(p,IssuerProfile.readPublication(path))
        }
        Files.writeString(path,json(mapOf("did" to p.identity,"identity" to profile().identity)))
        assertFailsWith<IllegalArgumentException> { IssuerProfile.readPublication(path) }
        Files.writeString(path,"x".repeat(8193))
        assertFailsWith<IllegalArgumentException> { IssuerProfile.readPublication(path) }
    }
    @Test fun `approval requires independent evidence current profile and an owned unexpired review`() {
        var clock=1000L
        val path=Files.createTempFile("issuer-review-", ".sqlite")
        val published=profile()
        var reviewId=""
        DomainStore(path,"company",{clock}).use { s ->
            reviewId=s.reviewIssuer(admin,published,"review-key-000001")["id"] as String
            assertEquals(0L,s.trusted(admin,0,25)["revision"])
            assertEquals(reviewId,s.reviewIssuer(admin,published,"review-key-000001")["id"])
            assertEquals(404,assertFailsWith<ApiFailure> { s.issuerReview(admin.copy(account="admin-b"),reviewId) }.status)
            assertEquals(404,assertFailsWith<ApiFailure> { s.issuerReview(admin.copy(organization="company-b"),reviewId) }.status)
            assertEquals(403,assertFailsWith<ApiFailure> { s.reviewIssuer(admin.copy(roles=setOf(Role.ISSUER_ADMIN)),published,"review-key-000002") }.status)
            assertEquals(400,assertFailsWith<ApiFailure> { s.approveIssuer(admin,reviewId,published,"demo-walkthrough",evidence,"false","approval-key-0001") }.status)
            assertEquals(400,assertFailsWith<ApiFailure> { s.approveIssuer(admin,reviewId,published,"demo-walkthrough","Too short","true","approval-key-0001") }.status)
            assertEquals(409,assertFailsWith<ApiFailure> { s.approveIssuer(admin,reviewId,profile(),"demo-walkthrough",evidence,"true","approval-key-0001") }.status)
            assertEquals(0L,s.trusted(admin,0,25)["revision"])
            clock=1900
            assertEquals(410,assertFailsWith<ApiFailure> { s.approveIssuer(admin,reviewId,published,"demo-walkthrough",evidence,"true","approval-key-0001") }.status)
            reviewId=s.reviewIssuer(admin,published,"review-key-000003")["id"] as String
            val result=s.approveIssuer(admin,reviewId,published,"demo-walkthrough",evidence,"true","approval-key-0001")
            assertEquals(1L,result["revision"])
            assertEquals(result,s.approveIssuer(admin,reviewId,published,"demo-walkthrough",evidence,"true","approval-key-0001"))
            assertEquals(409,assertFailsWith<ApiFailure> { s.approveIssuer(admin,reviewId,published,"demo-walkthrough",evidence,"true","approval-key-0002") }.status)
            assertEquals(409,assertFailsWith<ApiFailure> { s.approveIssuer(admin,reviewId,published,"official-contact",evidence,"true","approval-key-0001") }.status)
        }
        DomainStore(path,"company",{clock}).use { s ->
            @Suppress("UNCHECKED_CAST")
            val record=(s.trusted(admin,0,25)["items"] as List<Map<String,Any?>>).single()
            assertEquals(published.identity,record["identity"])
            assertEquals(admin.account,record["approvedBy"])
            assertEquals(evidence,record["verificationNote"])
            assertEquals("REVIEWED",record["reviewStatus"])
            assertEquals(emptyList<Any>(),s.trusted(admin.copy(organization="company-b"),0,25)["items"])
            s.trust(admin,null,hash(published.identity),"remove-key-000001")
            assertEquals(emptyList<Any>(),s.trusted(admin,0,25)["items"])
            assertEquals(reviewId,s.issuerReview(admin,reviewId)["id"])
        }
    }
    @Test fun `legacy trust can gain recorded provenance without changing the issuer identity`() {
        DomainStore(Files.createTempFile("issuer-legacy-", ".sqlite"),"company").use { s ->
            val p=profile();s.trust(admin,p.identity)
            val id=s.reviewIssuer(admin,p,"review-key-000001")["id"] as String
            s.approveIssuer(admin,id,p,"demo-walkthrough",evidence,"true","approval-key-0001")
            assertEquals(2L,s.trusted(admin,0,25)["revision"])
            @Suppress("UNCHECKED_CAST")
            val items=s.trusted(admin,0,25)["items"] as List<Map<String,Any?>>
            assertEquals(1,items.size);assertEquals("REVIEWED",items.single()["reviewStatus"])
        }
    }
    @Test fun `HTTP boundary rejects raw DID shortcuts anonymous approvals and browser CSRF`() = testApplication {
        val p=profile()
        val store=DomainStore(Files.createTempFile("issuer-http-", ".sqlite"),"company")
        val guard=RequestGuard("https://company.example",{ _, _ -> admin to "csrf" })
        application { businessRoutes(store,guard,directory=IssuerDirectory(load={p})) }
        assertEquals(401,client.get("/api/v1/issuer-candidates").status.value)
        assertEquals(401,client.post("/api/v1/issuer-reviews").status.value)
        assertEquals(403,client.post("/api/v1/issuer-reviews") {
            header("Cookie","__Host-nusacred-session=valid");header("Origin","https://attacker.example")
        }.status.value)
        val old=client.post("/api/v1/trusted-issuers") {
            header("Authorization","Bearer valid");contentType(ContentType.Application.Json)
            header("Idempotency-Key","approval-key-0001");setBody(json(mapOf("identity" to p.identity)))
        }
        assertEquals(400,old.status.value)
        assertFalse(old.bodyAsText().contains(p.identity))
        val candidates=client.get("/api/v1/issuer-candidates") { header("Authorization","Bearer valid") }
        assertEquals(200,candidates.status.value);assertEquals("no-store",candidates.headers["Cache-Control"])
        val review=client.post("/api/v1/issuer-reviews") {
            header("Cookie","__Host-nusacred-session=valid");header("Origin","https://company.example");header("X-CSRF-Token","csrf")
            contentType(ContentType.Application.Json);header("Idempotency-Key","review-http-0001");setBody("""{"issuerId":"lsp-nusantara"}""")
        }
        assertEquals(201,review.status.value)
        val reviewId=JSONObjectUtils.parse(review.bodyAsText())["id"] as String
        val approved=client.post("/api/v1/trusted-issuers") {
            header("Cookie","__Host-nusacred-session=valid");header("Origin","https://company.example");header("X-CSRF-Token","csrf")
            contentType(ContentType.Application.Json);header("Idempotency-Key","approval-http-01")
            setBody(json(mapOf("reviewId" to reviewId,"verificationMethod" to "demo-walkthrough","verificationNote" to evidence,"confirmed" to "true")))
        }
        assertEquals(201,approved.status.value);assertEquals("no-store",approved.headers["Cache-Control"])
        assertTrue(approved.bodyAsText().contains("approvalId"))
        store.close()
    }
    @Test fun `issuer publication is anonymous and has no account fields`() = testApplication {
        val p=profile()
        val store=DomainStore(Files.createTempFile("issuer-public-", ".sqlite"),"issuer")
        application { businessRoutes(store,RequestGuard("https://issuer.example",{ _, _ -> error("No authentication expected") }),publicProfile=p) }
        val response=client.get("/api/v1/issuer-profile")
        assertEquals(200,response.status.value);assertEquals("no-store",response.headers["Cache-Control"])
        assertTrue(response.bodyAsText().contains(p.identity));assertFalse(response.bodyAsText().contains("candidateAccountId"))
        assertEquals(404,client.get("/api/v1/issuer-candidates").status.value)
        store.close()
    }
}
