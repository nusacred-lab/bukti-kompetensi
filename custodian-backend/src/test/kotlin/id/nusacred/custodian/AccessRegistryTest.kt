package id.nusacred.custodian

import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import org.junit.Test

class AccessRegistryTest {
    private val issuer = "https://auth.example.test/realms/test"
    private fun path() = Files.createTempDirectory("registry-test-").resolve("access.db")
    @Test fun `renewal may cross access expiry but never revocation recovery or restart`() {
        var time=Instant.ofEpochSecond(20000)
        val file=path()
        AccessRegistry(file) { time }.use { r ->
            for(id in listOf("renewable","logout","recovery"))
                r.registerSession(issuer,"alice",id,"same-idp-session",time.epochSecond,time.plusSeconds(300).epochSecond)
            time=time.plusSeconds(301)
            assertFailsWith<AccessDenied> { r.authorize("renewable") }
            assertEquals("OPEN",r.authorizeRenewal("renewable").gate)
            r.revoke("logout")
            assertFailsWith<AccessDenied> { r.authorizeRenewal("logout") }
            val operation=randomToken();val revision=r.beginRecovery(issuer,"alice",operation)
            for(id in listOf("renewable","recovery")) assertFailsWith<AccessDenied> { r.authorizeRenewal(id) }
            r.completeRecovery(issuer,"alice",operation,revision)
            assertFailsWith<AccessDenied> { r.authorizeRenewal("renewable") }
            time=time.plusSeconds(1)
            r.registerSession(issuer,"alice","fresh","new-idp-session",time.epochSecond,time.plusSeconds(300).epochSecond,time)
            assertEquals("OPEN",r.authorizeRenewal("fresh").gate)
            assertFailsWith<AccessDenied> { r.authorizeRenewal("recovery") }
        }
        AccessRegistry(file) { time }.use { r -> assertFailsWith<AccessDenied> { r.authorizeRenewal("fresh") } }
    }
    private fun login(r: AccessRegistry, subject: String = "alice", token: String = randomToken(),
                      at: Long = Instant.now().epochSecond): String {
        r.registerSession(issuer, subject, hash(token), randomToken(), at, Instant.now().plusSeconds(600).epochSecond)
        return hash(token)
    }
    @Test fun `same second login requires a nonce flow issued after durable completion`() {
        var time = Instant.ofEpochSecond(20_000, 100_000_000)
        val file = path()
        AccessRegistry(file) { time }.use { r ->
            r.registerSession(issuer, "alice", "before", "old-sid", time.epochSecond, 30_000)
            val operation = randomToken()
            val revision = r.beginRecovery(issuer, "alice", operation)
            r.completeRecovery(issuer, "alice", operation, revision)
            val completion = time
            time = time.plusMillis(100)
            for (started in listOf(null, completion.minusMillis(1), completion, time.plusMillis(1))) {
                assertFailsWith<AccessDenied> {
                    r.registerSession(issuer, "alice", randomToken(), "unknown-old-sid", time.epochSecond, 30_000, started)
                }
            }
            r.registerSession(issuer, "alice", "fresh", "fresh-sid", time.epochSecond, 30_000, time)
            assertEquals("OPEN", r.authorize("fresh").gate)
            assertFailsWith<AccessDenied> { r.authorize("before") }
            // Reopening does not make an old same-second flow eligible.
            assertFailsWith<AccessDenied> {
                r.registerSession(issuer, "alice", randomToken(), "late-old", time.epochSecond, 30_000, completion)
            }
        }
        AccessRegistry(file) { time }.use { r ->
            assertFailsWith<AccessDenied> { r.authorize("fresh") }
            r.registerSession(issuer, "alice", "after-restart", "new-sid", time.epochSecond, 30_000, time)
            assertEquals("OPEN", r.authorize("after-restart").gate)
        }
    }

    @Test fun `schema one migration preserves conservative recovery boundary and wallet`() {
        var time = Instant.ofEpochSecond(20_000, 100_000_000)
        val file = path()
        AccessRegistry(file) { time }.use { r ->
            r.registerSession(issuer, "alice", "before", "old", time.epochSecond, 30_000)
            r.provision("before") { "retained-wallet" to "retained-key" }
            val operation = randomToken(); val revision = r.beginRecovery(issuer, "alice", operation)
            r.completeRecovery(issuer, "alice", operation, revision)
        }
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().use {
                it.execute("DROP TABLE idempotency_keys")
                it.execute("DROP TABLE exchange_contexts")
                it.execute("DROP TABLE audit")
                it.execute("ALTER TABLE accounts DROP COLUMN fresh_flow_after_ms")
                it.execute("ALTER TABLE pending_operations DROP COLUMN reason")
                it.execute("DROP TABLE receiving_offers")
                it.execute("PRAGMA user_version=1")
            }
        }
        time = time.plusMillis(100)
        AccessRegistry(file) { time }.use { r ->
            assertFailsWith<AccessDenied> {
                r.registerSession(issuer, "alice", "ambiguous", "same-second", time.epochSecond, 30_000, time)
            }
            time = time.plusSeconds(1)
            r.registerSession(issuer, "alice", "fresh", "new", time.epochSecond, 30_000, time)
            assertEquals("retained-wallet", r.binding("fresh")?.walletId)
            assertEquals("retained-key", r.binding("fresh")?.keyId)
        }
    }

    @Test fun `identity uses issuer and subject and never email`() {
        AccessRegistry(path()).use { r ->
            val a = r.identify(issuer, "alice")
            assertEquals(a, r.identify(issuer, "alice"))
            assertNotEquals(a.id, r.identify(issuer, "bob").id)
            assertNotEquals(a.id, r.identify("https://other.example.test", "alice").id)
        }
    }
    @Test fun `concurrent logins create one wallet and preserve ownership`() {
        AccessRegistry(path()).use { r ->
            val sessions = (1..12).map { login(r) }
            val calls = AtomicInteger()
            val pool = Executors.newFixedThreadPool(6)
            try {
                val results = sessions.map { session -> pool.submit<WalletBinding> {
                    r.provision(session) { calls.incrementAndGet(); "wallet-alice" to "key-alice" }
                } }.map { it.get(5, TimeUnit.SECONDS) }
                assertEquals(1, calls.get())
                assertEquals(1, results.toSet().size)
                assertNull(r.binding(login(r, "bob")))
            } finally { pool.shutdownNow() }
        }
    }
    @Test fun `lost create response never repeats create and is reconciled by durable reference`() {
        val file = path()
        var operation = ""
        AccessRegistry(file).use { r ->
            val token = login(r)
            assertFailsWith<ReconciliationRequired> { r.provision(token) { operation = it; error("response lost") } }
            assertFailsWith<ReconciliationRequired> { r.provision(token) { error("must not retry") } }
        }
        AccessRegistry(file).use { r ->
            val token = login(r)
            assertEquals("RECONCILE_REQUIRED", r.binding(token)?.state)
            val b = r.reconcile(token) { assertEquals(operation, it); "original-wallet" to "original-key" }
            assertEquals("original-wallet", b.walletId)
            assertEquals(b, r.provision(token) { error("must not recreate") })
        }
    }
    @Test fun `two old sessions old token exchanges and pending consent fail throughout recovery`() {
        AccessRegistry(path()).use { r ->
            val one = login(r); val two = login(r)
            r.prepare(one, "pending", "preview", Instant.now().plusSeconds(60).epochSecond)
            val op = randomToken()
            val revision = r.beginRecovery(issuer, "alice", op)
            assertEquals(revision, r.beginRecovery(issuer, "alice", op))
            for (t in listOf(one, two)) assertFailsWith<AccessDenied> { r.authorize(t) }
            assertFailsWith<AccessDenied> { login(r) }
            assertFailsWith<AccessDenied> { r.dispatch(one, "pending", "preview") { error("must not send") } }
            r.completeRecovery(issuer, "alice", op, revision)
            val boundary = r.identify(issuer, "alice").freshAfter
            assertEquals("AWAITING_FRESH_LOGIN", r.identify(issuer, "alice").gate)
            assertFailsWith<AccessDenied> { login(r, at = boundary) }
            assertFailsWith<AccessDenied> { login(r, at = boundary - 1) }
            val fresh = login(r, at = boundary + 1)
            assertEquals("OPEN", r.authorize(fresh).gate)
            for (t in listOf(one, two)) assertFailsWith<AccessDenied> { r.authorize(t) }
        }
    }
    @Test fun `forged mismatched duplicate and delayed callbacks cannot reopen access`() {
        AccessRegistry(path()).use { r ->
            login(r)
            val op = randomToken(); val revision = r.beginRecovery(issuer, "alice", op)
            assertFailsWith<AccessDenied> { r.completeRecovery(issuer, "alice", randomToken(), revision) }
            assertFailsWith<AccessDenied> { r.completeRecovery(issuer, "bob", op, revision) }
            assertFailsWith<AccessDenied> { r.completeRecovery(issuer, "alice", op, revision + 1) }
            assertEquals("LOCKED", r.identify(issuer, "alice").gate)
            r.completeRecovery(issuer, "alice", op, revision)
            val before = r.identify(issuer, "alice")
            r.completeRecovery(issuer, "alice", op, revision)
            assertEquals(before, r.identify(issuer, "alice"))
            login(r, at = before.freshAfter + 1)
            val second = randomToken(); r.beginRecovery(issuer, "alice", second)
            assertFailsWith<AccessDenied> { r.completeRecovery(issuer, "alice", op, revision) }
            assertEquals("LOCKED", r.identify(issuer, "alice").gate)
        }
    }
    @Test fun `restart retains closed gate and wallet while invalidating app sessions`() {
        val file = path(); var token = ""; val op = randomToken(); var revision = 0L
        AccessRegistry(file).use { r ->
            token = login(r)
            r.provision(token) { "wallet-before" to "key-before" }
            revision = r.beginRecovery(issuer, "alice", op)
        }
        AccessRegistry(file).use { r ->
            assertEquals("LOCKED", r.identify(issuer, "alice").gate)
            assertFailsWith<AccessDenied> { r.authorize(token) }
            assertFailsWith<AccessDenied> { login(r) }
            r.completeRecovery(issuer, "alice", op, revision)
            val fresh = login(r, at = r.identify(issuer, "alice").freshAfter + 1)
            assertEquals("wallet-before", r.binding(fresh)?.walletId)
            assertEquals("key-before", r.binding(fresh)?.keyId)
        }
    }
    @Test fun `dispatch and recovery have an explicit serialized boundary`() {
        AccessRegistry(path()).use { r ->
            val token = login(r); val entered = CountDownLatch(1); val release = CountDownLatch(1)
            r.prepare(token, "op", "hash", Instant.now().plusSeconds(60).epochSecond)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val sent = pool.submit<String> { r.dispatch(token, "op", "hash") {
                    entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); "sent"
                } }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val reset = pool.submit<Long> { r.beginRecovery(issuer, "alice", randomToken()) }
                assertFalse(reset.isDone)
                release.countDown(); assertEquals("sent", sent.get(5, TimeUnit.SECONDS)); reset.get(5, TimeUnit.SECONDS)
                assertFailsWith<AccessDenied> { r.dispatch(token, "op", "hash") { "replayed" } }
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }
    @Test fun `consent binds session payload and revision and is single use`() {
        AccessRegistry(path()).use { r ->
            val token = login(r); val other = login(r)
            r.prepare(token, "op", "preview", Instant.now().plusSeconds(60).epochSecond)
            assertFailsWith<AccessDenied> { r.dispatch(other, "op", "preview") { "wrong session" } }
            assertFailsWith<AccessDenied> { r.dispatch(token, "op", "changed") { "wrong payload" } }
            assertEquals("sent", r.dispatch(token, "op", "preview") { "sent" })
            assertFailsWith<AccessDenied> { r.dispatch(token, "op", "preview") { "replay" } }
        }
    }
    @Test fun `only an IdP fenced new proof may supersede abandoned recovery and old callbacks stay stale`() {
        AccessRegistry(path()).use { r ->
            login(r)
            val abandoned = randomToken(); val oldRevision = r.beginRecovery(issuer, "alice", abandoned)
            val fresh = randomToken()
            assertFailsWith<AccessDenied> { r.beginRecovery(issuer, "alice", fresh) }
            val revision = r.beginRecovery(issuer, "alice", fresh, idpWriteFenced = true)
            assertEquals(oldRevision + 1, revision)
            assertFailsWith<AccessDenied> { r.completeRecovery(issuer, "alice", abandoned, oldRevision) }
            assertFailsWith<AccessDenied> { r.beginRecovery(issuer, "alice", abandoned, idpWriteFenced = true) }
            r.completeRecovery(issuer, "alice", fresh, revision)
            val session = login(r, at = r.identify(issuer, "alice").freshAfter + 1)
            assertFailsWith<AccessDenied> { r.beginRecovery(issuer, "alice", abandoned, idpWriteFenced = true) }
            assertEquals("OPEN", r.authorize(session).gate)
        }
    }
}
