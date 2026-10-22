package id.nusacred.custodian

import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class ReceivingConsentTest {
    @Test fun `one receiving reservation survives restart and cannot be claimed twice or across accounts`() {
        val path=Files.createTempFile("p4-receiving-", ".sqlite")
        val now=Instant.parse("2026-10-22T00:00:00Z")
        AccessRegistry(path,{now}).use { r ->
            for(token in listOf("alice","bob")) r.registerSession("https://auth.example",token,token,"sid-$token",now.epochSecond,now.epochSecond+1800)
            r.saveReceivingPreview("alice","offer","context","summary",now.epochSecond+240,"{}")
            assertFailsWith<AccessDenied> { r.preview("bob","context") }
            assertEquals(409,assertFailsWith<id.nusacred.common.ApiFailure> { r.saveReceivingPreview("alice","offer","another","hash",now.epochSecond+240,"{}") }.status)
            r.consent("alice","context","summary","receiving-key-00001")
            assertFailsWith<ReconciliationRequired> { r.dispatch("alice","context","summary") { error("Lost credential response") } }
            assertEquals("RECONCILE_REQUIRED",r.operation("alice","context")["state"])
            assertFailsWith<AccessDenied> { r.dispatch("alice","context","summary") { true } }
        }
        AccessRegistry(path,{now}).use { r ->
            r.registerSession("https://auth.example","alice","fresh","sid-fresh",now.epochSecond,now.epochSecond+1800)
            assertEquals(409,assertFailsWith<id.nusacred.common.ApiFailure> { r.saveReceivingPreview("fresh","offer","retry","hash",now.epochSecond+240,"{}") }.status)
        }
    }
}
