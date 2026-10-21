package id.nusacred.custodian

import com.nimbusds.jose.util.JSONObjectUtils
import id.nusacred.common.*
import id.nusacred.contracts.Role
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Explicit opt-in real-service proof. No mock outcomes and no raw credential/token output. */
object RealBusinessProof {
    @JvmStatic fun main(args: Array<String>) {
        val work=Path.of(requireNotNull(System.getenv("P4_PROOF_RUNTIME")))
        require("runtime" in work.toAbsolutePath().map { it.toString() })
        Files.createDirectories(work)
        val http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build()
        fun forward(req: HttpRequest): Pair<Int,java.io.InputStream> {
            val port=(System.getenv("P4_PORT_BASE")?.toInt() ?: 17000)+mapOf("issuer" to 5,"wallet" to 6,"verifier" to 4).getValue(req.uri().host)
            val uri=URI("http://127.0.0.1:$port"+req.uri().rawPath+(req.uri().rawQuery?.let { "?$it" } ?: ""))
            val b=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
            req.headers().map().forEach { (key,values) -> values.forEach { b.header(key,it) } }
            b.method(req.method(),req.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()))
            val response=http.send(b.build(),HttpResponse.BodyHandlers.ofInputStream())
            return response.statusCode() to response.body()
        }
        val protocol=CredentialProtocol(::forward)
        val client=WalletClient("http://wallet:7006",::forward)
        val codec=OfferReference(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
        val now=Instant.now()
        val checks=mutableListOf<String>()
        AccessRegistry(work.resolve("registry.sqlite")).use { registry ->
            val opaque=randomToken();val token=hash(opaque)
            val account=registry.registerSession("https://synthetic-auth.example",UUID.randomUUID().toString(),token,"sid",now.epochSecond,now.epochSecond+1800)
            val wallet=WalletService(registry,client,codec,protocol)
            wallet.provision(opaque)
            val issuer=Principal("operator","synthetic-issuer",setOf(Role.ISSUER_OPERATOR),"issuer-session")
            DomainStore(work.resolve("issuer.sqlite"),"issuer",protocol=protocol,offerReferences=codec).use { issuing ->
                fun receive(code: String): String {
                    val assessment=issuing.assessment(issuer,account.id,"candidate-synthetic",code,"competent",UUID.randomUUID().toString())["id"] as String
                    val key=UUID.randomUUID().toString()
                    val offer=issuing.createOffer(issuer,assessment,key)
                    check(offer["state"] == "COMPLETED") { "Real offer creation did not complete" }
                    check(issuing.createOffer(issuer,assessment,key) == offer)
                    val preview=wallet.receivePreview(opaque,offer["offerUrl"] as String)
                    val context=preview["id"] as String; val summary=preview["previewHash"] as String
                    val consent=UUID.randomUUID().toString()
                    check(wallet.receiveConsented(opaque,context,summary,consent)["state"] == "COMPLETED") { "Real receiving did not complete" }
                    check(wallet.receiveConsented(opaque,context,summary,consent)["state"] == "COMPLETED")
                    checks+="real_receive_"+code
                    val entries=JSONObjectUtils.parse("{\"items\":"+wallet.credentials(opaque)+"}")["items"] as List<*>
                    return (entries.last() as Map<*,*>)["id"] as String
                }
                receive("DEMO-ELEC-001");receive("DEMO-OTHER-001")
                val entries=JSONObjectUtils.parse("{\"items\":"+wallet.credentials(opaque)+"}")["items"] as List<*>
                check(entries.size == 2)
                val credentialIds=mutableSetOf<String>()
                for(entry in entries) {
                    val metadata=entry as Map<*,*>
                    val stored=JSONObjectUtils.parse(wallet.credential(opaque,metadata["id"] as String))["credential"] as Map<*,*>
                    val claims=stored["credentialData"] as Map<*,*>
                    check(claims["id"] != metadata["id"])
                    check(credentialIds.add(claims["id"] as String))
                    val issued=(claims["iat"] as Number).toLong()
                    check(issued >= now.epochSecond && issued <= Instant.now().epochSecond)
                    check((claims["nbf"] as Number).toLong() == issued)
                    check((claims["exp"] as Number).toLong()-issued == 365L*86400)
                }
                checks+="unique_credential_ids_actual_dates_365_day_validity"
                val identity=(entries.first() as Map<*,*>)["issuer"] as String
                val admin=Principal("admin","synthetic-company",setOf(Role.COMPANY_ADMIN),"browser-session")
                val companyPath=work.resolve("company.sqlite")
                val finals=mutableListOf<String>()
                DomainStore(companyPath,"company",protocol=protocol).use { company ->
                    company.saveJob(admin,"electrician","Electrical installation technician","DEMO-ELEC-001","competent",UUID.randomUUID().toString())
                    company.trust(admin,identity)
                    fun present(code: String, expected: String) {
                        val session=company.verification(admin,"electrician",UUID.randomUUID().toString())
                        check(session["reason"] == "WAITING") { "Protocol session did not become ready: "+session["reason"] }
                        val id=session["id"] as String;val url=session["requestUrl"] as String
                        val binding=registry.binding(token)!!
                        val upstreamPreview=client.preview(binding.walletId!!,url)
                        val options=upstreamPreview["credentialOptions"] as List<*>
                        check(options.size == 2) { "DCQL filtered a business mismatch" }
                        val chosen=options.map { it as Map<*,*> }.single { option ->
                            val detail=JSONObjectUtils.parse(wallet.credential(opaque,option["credentialId"] as String))
                            val credential=detail["credential"] as Map<*,*>
                            (credential["credentialData"] as Map<*,*>)["competency_code"] == code
                        }
                        val preview=wallet.preview(opaque,url,chosen["credentialId"] as String)
                        val consent=UUID.randomUUID().toString()
                        @Suppress("UNCHECKED_CAST") val disclosures=(preview["details"] as Map<String,Any>)["disclosures"] as List<String>
                        wallet.presentConsented(opaque,preview["context_id"] as String,preview["summary_hash"] as String,
                            preview["credential_id"] as String,disclosures,consent)
                        val result=company.result(admin,id)
                        check(result["reason"] == expected) { "Unexpected business result: "+result["reason"] }
                        check(result["final"] == true)
                        finals+=id;checks+="real_business_"+expected
                    }
                    present("DEMO-ELEC-001","VERIFIED")
                    present("DEMO-OTHER-001","COMPETENCY_MISMATCH")
                    company.trust(admin,null,hash(identity))
                    present("DEMO-ELEC-001","UNTRUSTED_ISSUER")
                    company.trust(admin,identity)
                    present("DEMO-ELEC-001","VERIFIED")
                }
                DomainStore(companyPath,"company",protocol=protocol).use { company ->
                    check(company.result(admin,finals.first())["reason"] == "VERIFIED")
                    checks+="final_result_restart"
                }
            }
        }
        Files.writeString(work.resolve("result.json"),json(mapOf("checks" to checks,"passed" to true,"at" to Instant.now().toString())))
        println("PASS: "+checks.joinToString(", "))
    }
}
