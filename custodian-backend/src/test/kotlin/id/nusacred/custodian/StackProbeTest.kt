package id.nusacred.custodian

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class StackProbeTest {
    @Test fun probeDoesNotExposeWalletOperations() = testApplication {
        application { stackProbe() }
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
        assertEquals("{\"status\":\"up\",\"scope\":\"stack-probe\"}", client.get("/health/live").bodyAsText())
        for (path in listOf("/wallets", "/credentials", "/internal/recoveries", "/health/ready")) {
            assertEquals(HttpStatusCode.NotFound, client.get(path).status)
        }
    }
}
