package id.nusacred.custodian

import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class ContextRestorationTest {
    @Test fun `native renewal preserves pending and uncertain work only within the same login`() {
        var now=Instant.parse("2026-10-22T00:00:00Z")
        AccessRegistry(Files.createTempFile("renewal-operation-",".sqlite"),{now}).use { r ->
            val authenticatedAt=now.epochSecond
            r.registerSession("https://auth.example","alice","old","same-sid",authenticatedAt,authenticatedAt+300)
            now=now.plusSeconds(200)
            for(id in listOf("pending","uncertain")) r.savePreview("old",id,"summary",now.epochSecond+240,"{}")
            val key=java.util.UUID.randomUUID().toString()
            r.consent("old","uncertain","summary",key)
            assertFailsWith<ReconciliationRequired> { r.dispatch("old","uncertain","summary") { error("Synthetic lost outcome") } }
            now=now.plusSeconds(101)
            for((token,sid) in listOf("next" to "same-sid","other" to "different-sid"))
                r.registerSession("https://auth.example","alice",token,sid,authenticatedAt,now.epochSecond+300)
            assertFailsWith<AccessDenied> { r.completeRenewal("old","other") }
            assertFailsWith<AccessDenied> { r.authorize("old") }
            r.completeRenewal("old","next")
            assertFailsWith<AccessDenied> { r.authorizeRenewal("old") }
            assertEquals("PENDING",r.operation("next","pending")["state"])
            assertEquals("{}",r.preview("next","pending"))
            assertEquals("RECONCILE_REQUIRED",r.operation("next","uncertain")["state"])
            assertEquals("uncertain",r.consent("next","uncertain","summary",key))
            assertFailsWith<AccessDenied> { r.dispatch("next","uncertain","summary") { error("Must never resend") } }
            assertFailsWith<AccessDenied> { r.operation("other","pending") }
        }
    }
    @Test fun `review restoration returns only safe consent fields and cannot approve or cross sessions`() {
        var now=Instant.parse("2026-10-22T00:00:00Z")
        AccessRegistry(Files.createTempFile("portal-restore-",".sqlite"),{now}).use { registry ->
            for(token in listOf("first","second")) registry.registerSession("https://auth.example","alice",hash(token),"sid-$token",now.epochSecond,now.epochSecond+1800)
            registry.saveReceivingPreview(hash("first"),"offer","context","summary",now.epochSecond+240,
                json(mapOf("kind" to "receive","summary" to "summary","expires" to now.epochSecond+240,
                    "wallet" to "private-wallet","key" to "private-key","resolved" to mapOf("preAuthorizedCode" to "private-code"),
                    "details" to mapOf("candidateId" to "candidate","issuer" to "issuer"))))
            val service=WalletService(registry,WalletClient("http://wallet:7006"))
            val restored=service.context("first","context")
            assertEquals(setOf("id","credentialId","previewHash","expiresAt","consentRequired","details","choices"),restored.keys)
            assertEquals(true,restored["consentRequired"])
            assertFalse(json(restored).contains("private-"))
            assertEquals("PENDING",registry.operation(hash("first"),"context")["state"])
            assertFailsWith<AccessDenied> { service.context("second","context") }
            now=now.plusSeconds(241)
            assertFailsWith<ContextExpired> { service.context("first","context") }
            assertEquals("CANCELLED",registry.operation(hash("first"),"context")["state"])
        }
    }
    @Test fun `service status exposes aggregates without subjects wallet references or keys`() {
        AccessRegistry(Files.createTempFile("portal-status-",".sqlite")).use { registry ->
            val status=registry.serviceSummary()
            assertEquals(setOf("registryAvailable","accounts","walletsReady","recoveryPending","operationsUncertain"),status.keys)
            assertEquals(true,status["registryAvailable"]);assertEquals(0L,status["accounts"])
        }
    }
}
