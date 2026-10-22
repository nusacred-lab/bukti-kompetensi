package id.nusacred.custodian

import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class ContextRestorationTest {
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
