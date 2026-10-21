package id.nusacred.common

import id.nusacred.contracts.Role
import id.nusacred.custodian.json
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.test.*

class ProtocolDispatchTest {
    private val p=Principal("operator","issuer",setOf(Role.ISSUER_OPERATOR),"session")
    @Test fun `lost issuance response retry and restart never create another offer`() {
        var calls=0
        val client=CredentialProtocol { calls++; error("Response lost") }
        val path=Files.createTempFile("p4-dispatch-", ".sqlite")
        val codec=OfferReference(ByteArray(32))
        var assessment=""
        DomainStore(path,"issuer",protocol=client,offerReferences=codec).use { s ->
            assessment=s.assessment(p,"account","candidate","CODE","competent","assessment-key-0001")["id"] as String
            repeat(2) { assertEquals("RECONCILE_REQUIRED",s.createOffer(p,assessment,"offer-key-00000001")["state"]) }
            assertEquals(1,calls)
        }
        DomainStore(path,"issuer",protocol=client,offerReferences=codec).use { s ->
            assertEquals("RECONCILE_REQUIRED",s.createOffer(p,assessment,"offer-key-00000001")["state"])
            assertEquals(1,calls)
        }
    }
    @Test fun `candidate binding cannot be reassigned by another assessment`() {
        DomainStore(Files.createTempFile("p4-candidate-", ".sqlite"),"issuer").use { s ->
            s.assessment(p,"alice","candidate","CODE","competent","assessment-key-0001")
            assertEquals(409,assertFailsWith<ApiFailure> { s.assessment(p,"bob","candidate","OTHER","competent","assessment-key-0002") }.status)
            assertEquals(409,assertFailsWith<ApiFailure> { s.assessment(p,"alice","other-candidate","CODE","competent","assessment-key-0003") }.status)
        }
    }
    @Test fun `upstream outage stays incomplete missing session finalizes and cross account cannot poll upstream`() {
        val admin=Principal("admin","company",setOf(Role.COMPANY_ADMIN),"owner")
        var missing=false;var outage=false;var polls=0
        val url="openid4vp://authorize?request_uri=http%3A%2F%2Fverifier%3A7004%2Fverification-session%2Fupstream%2Frequest&client_id=redirect_uri%3Ahttp%3A%2F%2Fverifier%3A7004%2Fresponse"
        val client=CredentialProtocol { req ->
            if(req.method()=="POST") 200 to ByteArrayInputStream(json(mapOf("sessionId" to "upstream","bootstrapAuthorizationRequestUrl" to url)).toByteArray())
            else { polls++; if(outage) error("Timeout") else if(missing) 404 to ByteArrayInputStream(ByteArray(0))
                else 200 to ByteArrayInputStream("{\"id\":\"upstream\",\"status\":\"ACTIVE\"}".toByteArray()) }
        }
        DomainStore(Files.createTempFile("p4-poll-", ".sqlite"),"company",protocol=client).use { s ->
            s.saveJob(admin,"job","Job","CODE","competent","job-key-000000001")
            val session=s.verification(admin,"job","session-key-00001")
            assertEquals("WAITING",session["reason"])
            val id=session["id"] as String;val count=polls
            assertFailsWith<ApiFailure> { s.result(admin.copy(roles=emptySet(),session="attacker",organization=null),id) }
            assertEquals(count,polls)
            outage=true;assertEquals("DEPENDENCY_UNAVAILABLE",s.result(admin,id)["reason"]);assertEquals(false,s.result(admin,id)["final"])
            outage=false;missing=true;assertEquals("VERIFIER_SESSION_MISSING",s.result(admin,id)["reason"])
            missing=false;assertEquals("VERIFIER_SESSION_MISSING",s.result(admin,id)["reason"])
        }
    }
}
