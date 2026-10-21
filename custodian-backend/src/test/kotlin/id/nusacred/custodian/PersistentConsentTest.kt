package id.nusacred.custodian

import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class PersistentConsentTest {
    private val now = Instant.parse("2026-10-22T00:00:00Z")
    private fun session(r: AccessRegistry,token: String,subject: String="alice") = r.registerSession("https://auth.example",subject,token,"sid-$token",now.epochSecond,now.plusSeconds(1800).epochSecond)
    @Test fun `consent is bound to session account revision payload and expiry`() {
        AccessRegistry(Files.createTempFile("p3-consent-", ".sqlite"),{now}).use { r ->
            session(r,"alice-one");session(r,"alice-two");session(r,"bob","bob")
            r.savePreview("alice-one","context","summary",now.plusSeconds(240).epochSecond,"{\"credential\":\"one\"}")
            assertFailsWith<AccessDenied> { r.preview("alice-two","context") }
            assertFailsWith<AccessDenied> { r.preview("bob","context") }
            assertFailsWith<AccessDenied> { r.consent("alice-one","context","tampered","key-000000000001") }
            assertEquals("context",r.consent("alice-one","context","summary","key-000000000001"))
            assertEquals(409,assertFailsWith<id.nusacred.common.ApiFailure> {
                r.consent("alice-one","context","summary","key-000000000001","tampered-selection")
            }.status)
            var sent=0
            r.dispatch("alice-one","context","summary") { sent++ }
            assertEquals("context",r.consent("alice-one","context","summary","key-000000000001"))
            assertFailsWith<AccessDenied> { r.dispatch("alice-one","context","summary") { sent++ } }
            assertEquals(1,sent)
            assertEquals("COMPLETED",r.operation("alice-one","context")["state"])
        }
    }
    @Test fun `ambiguous send is never retried and recovery cancels pending consent`() {
        AccessRegistry(Files.createTempFile("p3-ambiguous-", ".sqlite"),{now}).use { r ->
            session(r,"token")
            r.savePreview("token","ambiguous","hash",now.plusSeconds(240).epochSecond,"{}")
            assertFailsWith<ReconciliationRequired> { r.dispatch("token","ambiguous","hash") { error("Response lost") } }
            assertEquals("RECONCILE_REQUIRED",r.operation("token","ambiguous")["state"])
            r.savePreview("token","pending","hash",now.plusSeconds(240).epochSecond,"{}")
            r.beginRecovery("https://auth.example","alice","recovery-operation-0000000000000001")
            assertFailsWith<AccessDenied> { r.preview("token","pending") }
        }
    }
    @Test fun `preview persists but restart never restores old session access`() {
        val path=Files.createTempFile("p3-restart-", ".sqlite")
        AccessRegistry(path,{now}).use { r ->
            session(r,"old");r.savePreview("old","context","hash",now.plusSeconds(240).epochSecond,"{}")
        }
        AccessRegistry(path,{now}).use { r ->
            assertFailsWith<AccessDenied> { r.preview("old","context") }
            session(r,"fresh")
            assertFailsWith<AccessDenied> { r.preview("fresh","context") }
        }
    }
}
