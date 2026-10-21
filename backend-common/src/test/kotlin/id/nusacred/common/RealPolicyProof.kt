package id.nusacred.common

import com.nimbusds.jose.util.JSONObjectUtils
import id.nusacred.custodian.json
import id.nusacred.contracts.Role
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Read private real-verifier responses; only sanitized verdicts are written to the result file. */
object RealPolicyProof {
    @JvmStatic fun main(args: Array<String>) {
        val root=Path.of(requireNotNull(System.getenv("P4_NEGATIVE_RUNTIME")))
        require("runtime" in root.toAbsolutePath().map { it.toString() })
        val index=JSONObjectUtils.parse(Files.readString(root.resolve("index.json")))
        val cases=index["checks"] as List<*>
        val results=mutableListOf<Map<String,Any>>()
        val p=Principal("admin","company",setOf(Role.COMPANY_ADMIN),"owner")
        DomainStore(root.resolve("decisions-${UUID.randomUUID()}.sqlite"),"company").use { store ->
            store.saveJob(p,"job","Job","DEMO-ELEC-001","competent",UUID.randomUUID().toString())
            for(item in cases) {
                val case=item as Map<*,*>;val name=case["id"] as String
                if(case["status"] == "ISSUER_REJECTED") {
                    results+=mapOf("id" to name,"layer" to "issuer","accepted" to false);continue
                }
                val info=JSONObjectUtils.parse(Files.readString(root.resolve("$name.json")))
                val claims=try { verifiedCompetency(info,info["id"] as String) } catch(_: ApiFailure) { null }
                if(name == "valid" || name == "assessment_not_competent") {
                    checkNotNull(claims) { "Positive cryptographic fixture rejected: $name" }
                    store.trust(p,claims.issuer)
                    val id=store.verification(p,"job",UUID.randomUUID().toString())["id"] as String
                    store.finalizeCredential(id,claims)
                    check(store.result(p,id)["reason"] == if(name == "valid") "VERIFIED" else "ASSESSMENT_NOT_COMPETENT")
                } else check(claims == null) { "Invalid real fixture accepted: $name" }
                results+=mapOf("id" to name,"layer" to if(info["status"] == "SUCCESSFUL") "business_adapter" else "verifier",
                    "accepted" to (name == "valid"))
            }
        }
        Files.writeString(root.resolve("adapter-result.json"),json(mapOf("checks" to results,"passed" to true)))
        println("PASS: "+results.size+" real credential/protocol cases checked with explicit rejection layers")
    }
}
