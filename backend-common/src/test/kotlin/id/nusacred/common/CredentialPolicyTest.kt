package id.nusacred.common

import com.nimbusds.jose.util.JSONObjectUtils
import id.nusacred.contracts.Role
import id.nusacred.custodian.json
import java.nio.file.Files
import kotlin.test.*

class CredentialPolicyTest {
    private val now=1800000000L
    private fun claims(): MutableMap<String,Any> = mutableMapOf("id" to "credential-1", "candidate_id" to "candidate-1",
        "competency_code" to "CODE", "assessment_result" to "competent", "iss" to "did:jwk:issuer", "vct" to COMPETENCY_VCT,
        "cnf" to mapOf("jwk" to mapOf("kty" to "EC","crv" to "P-256","x" to "x","y" to "y")),"iat" to now-30,"nbf" to now-30,"exp" to now+300)
    private fun info(claims: Map<String,Any> = claims()): Map<String,Any> = mapOf("id" to "upstream", "status" to "SUCCESSFUL",
        "policy_results" to mapOf("vp_policies" to mapOf("competency" to REQUIRED_VP_POLICIES.associateWith { mapOf("success" to true) }),
            "vc_policies" to listOf("signature","expiration","not-before").map { mapOf("policy" to mapOf("policy" to it,"requireField" to true),"success" to true,"query_id" to "competency","credential_index" to 0) },
            "specific_vc_policies" to emptyMap<String,Any>()),
        "presented_credentials" to mapOf("competency" to listOf(mapOf("type" to "vc-sd_jwt","credentialData" to claims))))
    @Test fun `each missing and mistyped required claim fails closed`() {
        assertEquals("CODE",verifiedCompetency(info(),"upstream",now).competency)
        for(name in REQUIRED_CLAIMS + "vct") {
            val missing=claims().apply { remove(name) }
            assertEquals("INVALID_PROOF",assertFailsWith<ApiFailure>(name) { verifiedCompetency(info(missing),"upstream",now) }.code)
            val wrong=claims().apply { put(name,if(name in setOf("iat","nbf","exp")) "1800000000" else 123) }
            assertFailsWith<ApiFailure>(name) { verifiedCompetency(info(wrong),"upstream",now) }
        }
    }
    @Test fun `numeric dates require integer seconds valid interval and current validity`() {
        for((field,value) in listOf("exp" to now,"nbf" to now+1,"iat" to now+1,"exp" to -1L,"exp" to 1.8e9,"iat" to true,"nbf" to 0.5,"exp" to 253402300800L)) {
            assertFailsWith<ApiFailure>(field+value) { verifiedCompetency(info(claims().apply { put(field,value) }),"upstream",now) }
        }
        assertFailsWith<ApiFailure> { verifiedCompetency(info(claims().apply { put("vct","other") }),"upstream",now) }
        assertFailsWith<ApiFailure> { verifiedCompetency(info(),"another-session",now) }
    }
    @Test fun `every VP policy must execute successfully and browser success alone is insufficient`() {
        for(id in REQUIRED_VP_POLICIES) {
            for(remove in listOf(true,false)) {
                val data=JSONObjectUtils.parse(json(info()))
                @Suppress("UNCHECKED_CAST") val policies=((data["policy_results"] as Map<String,Any>)["vp_policies"] as Map<String,Any>)["competency"] as MutableMap<String,Any>
                if(remove) policies.remove(id) else policies[id]=mapOf("success" to false)
                assertFailsWith<ApiFailure>(id) { verifiedCompetency(data,"upstream",now) }
            }
        }
        assertFailsWith<ApiFailure> { verifiedCompetency(mapOf("id" to "upstream","status" to "SUCCESSFUL"),"upstream",now) }
    }
    @Test fun `business decision uses stored job current trust and immutable result`() {
        val p=Principal("admin","company",setOf(Role.COMPANY_ADMIN),"session")
        val path=Files.createTempFile("p4-business-", ".sqlite")
        val outcomes=mutableListOf<String>()
        DomainStore(path,"company",{now}).use { s ->
            s.saveJob(p,"job","Electrician","CODE","competent","job-key-00000001")
            val issuer="did:jwk:issuer"
            for((competency,assessment,trust,expected) in listOf(
                listOf("CODE","competent","no","UNTRUSTED_ISSUER"),listOf("CODE","competent","yes","VERIFIED"),
                listOf("OTHER","competent","yes","COMPETENCY_MISMATCH"),listOf("CODE","not_competent","yes","ASSESSMENT_NOT_COMPETENT"))) {
                if(trust=="yes") s.trust(p,issuer)
                val id=s.verification(p,"job",java.util.UUID.randomUUID().toString())["id"] as String
                assertTrue(s.finalizeCredential(id,VerifiedCompetency(issuer,"candidate",competency,assessment)))
                assertEquals(expected,s.result(p,id)["reason"])
                assertFalse(s.finalizeCredential(id,VerifiedCompetency(issuer,"candidate","CODE","competent")))
                outcomes+=id
            }
        }
        DomainStore(path,"company",{now}).use { s -> assertEquals("VERIFIED",s.result(p,outcomes[1])["reason"]) }
    }
}
