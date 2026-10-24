package id.nusacred.custodian

import id.nusacred.common.*
import id.nusacred.contracts.Role
import io.ktor.client.request.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.time.Instant
import java.sql.DriverManager
import kotlin.test.*

class ParticipantInvitationsTest {
    private val email="participant@example.test"
    @Test fun `delivery is durable and replay never sends again even after restart`() {
        val path=Files.createTempFile("invitation-", ".sqlite")
        var sends=0
        val directory=ParticipantDirectory { sends++ }
        val first=ParticipantInvitations(path,directory).use {
            it.invite("operator",email,"request-key-00001",true)
        }
        ParticipantInvitations(path,directory).use {
            assertEquals(first,it.invite("operator",email,"request-key-00001",true))
            assertEquals("SENT",first["state"])
            assertEquals(1,sends)
            assertFalse(it.list().toString().contains("keyHash"))
            assertFailsWith<ApiFailure> { it.invite("another",email,"request-key-00001",true) }
            assertFailsWith<ApiFailure> { it.invite("operator",email,"request-key-00002",true) }
        }
    }
    @Test fun `interrupted reservation becomes reconciliation after restart without resending`() {
        val path=Files.createTempFile("interrupted-invitation-", ".sqlite")
        ParticipantInvitations(path,ParticipantDirectory {}).use {
            it.invite("operator",email,"request-key-00001",true)
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { it.executeUpdate("UPDATE invitations SET state='SENDING',expires=NULL") }
        }
        var sends=0
        ParticipantInvitations(path,ParticipantDirectory { sends++ }).use {
            assertEquals("RECONCILE_REQUIRED",it.invite("operator",email,"request-key-00001",true)["state"])
            assertEquals(0,sends)
        }
    }
    @Test fun `ambiguous delivery is locked against blind retries`() {
        var sends=0
        ParticipantInvitations(Files.createTempFile("invitation-", ".sqlite"),ParticipantDirectory { sends++;error("lost SMTP response") }).use {
            repeat(2) { _ -> assertEquals("RECONCILE_REQUIRED",it.invite("operator",email,"request-key-00001",true)["state"]) }
            assertEquals(1,sends)
        }
    }
    @Test fun `confirmation validation unavailable delivery and cooldown fail closed`() {
        var clock=Instant.ofEpochSecond(2000)
        var sends=0
        ParticipantInvitations(Files.createTempFile("invitation-", ".sqlite"),ParticipantDirectory { sends++ }) { clock }.use {
            assertFailsWith<IllegalArgumentException> { it.invite("operator",email,"key1",false) }
            assertFailsWith<IllegalArgumentException> { it.invite("operator","bad\r\n@example.test","key1",true) }
            it.invite("operator",email,"key1",true)
            assertEquals(429,assertFailsWith<ApiFailure> { it.invite("operator","second@example.test","key2",true) }.status)
            clock=clock.plusSeconds(61)
            it.invite("operator","second@example.test","key2",true)
            assertEquals(2,sends)
        }
        ParticipantInvitations(Files.createTempFile("invitation-", ".sqlite"),null).use {
            assertEquals(false,it.list()["enabled"])
            assertEquals(503,assertFailsWith<ApiFailure> { it.invite("operator",email,"key1",true) }.status)
            assertEquals(emptyList<Any>(),it.list()["items"])
        }
    }
    @Test fun `existing account is recorded without another email`() {
        ParticipantInvitations(Files.createTempFile("invitation-", ".sqlite"),ParticipantDirectory { throw ParticipantExists() }).use {
            assertEquals("ACCOUNT_EXISTS",it.invite("operator",email,"key1",true)["state"])
        }
    }
    @Test fun `only wallet admin with browser origin csrf and explicit confirmation can send`() = testApplication {
        var sends=0
        val store=ParticipantInvitations(Files.createTempFile("invitation-route-", ".sqlite"),ParticipantDirectory { sends++ })
        val guard=RequestGuard("https://wallet.example",{ opaque,_ ->
            Principal(opaque,null,setOf(if(opaque=="admin") Role.WALLET_ADMIN else Role.CANDIDATE),opaque) to "csrf"
        })
        application { participantInvitationRoutes(store,guard) }
        assertEquals(401,client.get("/api/v1/participant-invitations").status.value)
        assertEquals(403,client.get("/api/v1/participant-invitations") { header("Authorization","Bearer candidate") }.status.value)
        suspend fun send(origin:String?,csrf:String?,confirmation:String="true")=client.post("/api/v1/participant-invitations") {
            header("Cookie","__Host-nusacred-session=admin")
            origin?.let { header("Origin",it) };csrf?.let { header("X-CSRF-Token",it) }
            header("Content-Type","application/json");header("Idempotency-Key","invitation-route-0001")
            setBody(json(mapOf("email" to email,"confirmed" to confirmation)))
        }
        assertEquals(403,send(null,null).status.value)
        assertEquals(403,send("https://other.example","csrf").status.value)
        assertEquals(403,send("https://wallet.example","incorrect").status.value)
        assertEquals(400,send("https://wallet.example","csrf","false").status.value)
        assertEquals(0,sends)
        repeat(2) { assertEquals(202,send("https://wallet.example","csrf").status.value) }
        assertEquals(1,sends)
        store.close()
    }
}
