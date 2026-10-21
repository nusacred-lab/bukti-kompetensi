package id.nusacred.custodian

import com.nimbusds.jose.util.JSONObjectUtils
import id.nusacred.common.Principal
import id.nusacred.common.RequestGuard
import id.nusacred.contracts.Role
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.*

class OperationOutcomeTest {
    private val token = "synthetic-native-session-0000000000000000001"
    private val origin = "https://wallet.example"
    private fun guard() = RequestGuard(origin, { opaque, channel ->
        check(opaque == token && channel == "native")
        Principal("alice", null, setOf(Role.CANDIDATE), hash(token)) to "csrf"
    })
    private fun register(r: AccessRegistry, now: Instant) {
        r.registerSession("https://auth.example", "alice", hash(token), "sid", now.epochSecond, now.plusSeconds(1800).epochSecond)
    }
    private fun payload() = """{"url":"openid4vp://authorize","wallet":"wallet-one","key":"key-one","query":"query-one","credential":"credential-one","summary":"summary","disclosures":[]}"""
    private fun io.ktor.client.request.HttpRequestBuilder.native() { header("Authorization", "Bearer $token") }
    private fun io.ktor.client.request.HttpRequestBuilder.consent() {
        native(); header("Content-Type", "application/json"); header("Idempotency-Key", "outcome-test-key-0001")
        setBody("""{"previewHash":"summary","consent":true,"credentialId":"credential-one","disclosures":[]}""")
    }

    @Test fun `HTTP 200 with rejected transmission remains a terminal failure on retry and polling`() = testApplication {
        val now = Instant.now()
        val path = Files.createTempFile("p3-transmission-", ".sqlite")
        val registry = AccessRegistry(path) { now }
        register(registry, now)
        registry.savePreview(hash(token), "context", "summary", now.plusSeconds(240).epochSecond, payload())
        val paths = mutableListOf<String>()
        val upstream = WalletClient("http://wallet:7006") { request ->
            paths += request.uri().path
            val response = when {
                request.uri().path.endsWith("/build-vp-token") -> """{"vpToken":"synthetic-vp-token"}"""
                request.uri().path.endsWith("/send-response") -> """{"transmission_success":false}"""
                else -> error("Unexpected upstream request")
            }
            200 to response.byteInputStream()
        }
        application { contractRoutes(WalletService(registry, upstream), guard()) }
        repeat(2) {
            val response = client.post("/api/v1/exchange-contexts/context/present") { consent() }
            assertEquals(202, response.status.value)
            val operation = JSONObjectUtils.parse(response.bodyAsText())
            assertEquals("CANCELLED", operation["state"])
            assertEquals("TRANSMISSION_REJECTED", operation["reason"])
        }
        val poll = client.get("/api/v1/operations/context") { native() }
        assertEquals(200, poll.status.value)
        assertEquals("TRANSMISSION_REJECTED", JSONObjectUtils.parse(poll.bodyAsText())["reason"])
        assertEquals(2, paths.size, "Retry must not rebuild or send another VP")
        assertEquals(1, paths.count { it.endsWith("/send-response") })
        registry.close()
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().executeQuery("SELECT state,reason FROM pending_operations WHERE id='context'").use { row ->
                assertTrue(row.next()); assertEquals("CANCELLED", row.getString(1)); assertEquals("TRANSMISSION_REJECTED", row.getString(2))
            }
        }
    }

    @Test fun `missing upstream outcome requires reconciliation and never resends after expiry`() = testApplication {
        var now = Instant.now()
        val registry = AccessRegistry(Files.createTempFile("p3-uncertain-", ".sqlite")) { now }
        register(registry, now)
        registry.savePreview(hash(token), "uncertain", "summary", now.plusSeconds(240).epochSecond, payload())
        var calls = 0
        val upstream = WalletClient("http://wallet:7006") { request ->
            calls++
            200 to (if(request.uri().path.endsWith("/build-vp-token")) """{"vpToken":"synthetic-vp-token"}""" else "{}").byteInputStream()
        }
        application { contractRoutes(WalletService(registry, upstream), guard()) }
        assertEquals(503, client.post("/api/v1/exchange-contexts/uncertain/present") { consent() }.status.value)
        now = now.plusSeconds(241)
        val retry = client.post("/api/v1/exchange-contexts/uncertain/present") { consent() }
        assertEquals(202, retry.status.value)
        assertEquals("RECONCILE_REQUIRED", JSONObjectUtils.parse(retry.bodyAsText())["state"])
        assertEquals(2, calls)
        registry.close()
    }

    @Test fun `TTL expiry is durable and rejects consent decline dispatch and replay without revealing other owners`() = testApplication {
        var now = Instant.now()
        val path = Files.createTempFile("p3-expired-", ".sqlite")
        val registry = AccessRegistry(path) { now }
        register(registry, now)
        registry.registerSession("https://auth.example", "bob", "bob-session", "bob-sid", now.epochSecond, now.plusSeconds(1800).epochSecond)
        for(id in listOf("poll-first", "consent-first", "decline-first", "dispatch-first", "retry")) {
            registry.savePreview(hash(token), id, "summary", now.plusSeconds(240).epochSecond, payload())
        }
        registry.consent(hash(token), "retry", "summary", "outcome-test-key-0001")
        val upstream = WalletClient("http://wallet:7006") { error("Expired work must not reach the upstream") }
        application { contractRoutes(WalletService(registry, upstream), guard()) }
        now = now.plusSeconds(240)
        assertFailsWith<AccessDenied> { registry.preview("bob-session", "poll-first") }
        assertEquals("CANCELLED", registry.operation(hash(token), "poll-first")["state"])
        for(id in listOf("consent-first", "retry")) {
            assertEquals(410, client.post("/api/v1/exchange-contexts/$id/present") { consent() }.status.value)
            assertEquals("EXPIRED", registry.operation(hash(token), id)["reason"])
        }
        assertEquals(410, client.post("/api/v1/exchange-contexts/decline-first/decline") {
            native(); header("Content-Type", "application/json"); setBody("{}")
        }.status.value)
        assertFailsWith<ContextExpired> { registry.dispatch(hash(token), "dispatch-first", "summary") { error("Must not send") } }
        for(id in listOf("poll-first", "consent-first", "decline-first", "dispatch-first", "retry")) {
            assertFailsWith<ContextExpired> { registry.preview(hash(token), id) }
            assertEquals("EXPIRED", registry.operation(hash(token), id)["reason"])
        }
        registry.close()
        AccessRegistry(path) { now }.use { restarted ->
            assertFailsWith<AccessDenied> { restarted.operation(hash(token), "poll-first") }
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().executeQuery("SELECT COUNT(*) FROM pending_operations p JOIN exchange_contexts e ON e.id=p.id WHERE p.state='CANCELLED' AND p.reason='EXPIRED' AND e.state='EXPIRED'").use { row ->
                assertTrue(row.next()); assertEquals(5, row.getInt(1))
            }
        }
    }

    @Test fun `schema three migration preserves pending work and wallet references`() {
        val path = Files.createTempFile("p3-schema-three-", ".sqlite")
        val now = Instant.now()
        AccessRegistry(path) { now }.use { registry ->
            register(registry, now)
            registry.provision(hash(token)) { "retained-wallet" to "retained-key" }
            registry.savePreview(hash(token), "pending", "summary", now.plusSeconds(240).epochSecond, payload())
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { it.execute("ALTER TABLE pending_operations DROP COLUMN reason"); it.execute("DROP TABLE receiving_offers"); it.execute("PRAGMA user_version=3") }
        }
        AccessRegistry(path) { now }.use { registry ->
            registry.registerSession("https://auth.example", "alice", "fresh-session", "fresh-sid", now.epochSecond, now.plusSeconds(1800).epochSecond)
            assertEquals("retained-wallet", registry.binding("fresh-session")?.walletId)
            assertEquals("retained-key", registry.binding("fresh-session")?.keyId)
            assertFailsWith<AccessDenied> { registry.operation(hash(token), "pending") }
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().executeQuery("SELECT state,reason FROM pending_operations WHERE id='pending'").use { row ->
                assertTrue(row.next()); assertEquals("PENDING", row.getString(1)); assertNull(row.getString(2))
            }
        }
    }
}
