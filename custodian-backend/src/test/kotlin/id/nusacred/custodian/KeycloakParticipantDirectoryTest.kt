package id.nusacred.custodian

import java.net.http.HttpRequest
import kotlin.test.*

class KeycloakParticipantDirectoryTest {
    private fun config(realm:String="wallet-demo")=OidcConfig("https://login.example/realms/$realm","https://login.example","https://wallet.example","custodian-bff-p2","s".repeat(40))
    @Test fun `fixed realm endpoints grant only applicant and send one fifteen minute setup link`() {
        val requests=mutableListOf<HttpRequest>()
        val responses=ArrayDeque(listOf(
            200 to """{"access_token":"synthetic-token"}""",
            200 to "[]",
            200 to """{"id":"applicant-role","name":"applicant"}""",
            201 to "",
            200 to """[{"id":"participant-1"}]""",
            204 to "",
            204 to ""
        ))
        val sender=KeycloakParticipantDirectory(config(),"s".repeat(40)) { request ->
            requests+=request
            val response=responses.removeFirst()
            response.first to response.second.byteInputStream()
        }
        sender.invite("participant@example.test")
        assertTrue(responses.isEmpty())
        assertTrue(requests.all { it.uri().host=="login.example" && it.uri().path.contains("/realms/wallet-demo/") })
        assertEquals("/admin/realms/wallet-demo/roles/applicant",requests[2].uri().path)
        assertEquals("PUT",requests.last().method())
        assertEquals("lifespan=900",requests.last().uri().query)
        assertEquals(1,requests.count { it.uri().path.endsWith("/execute-actions-email") })
    }
    @Test fun `existing participant never triggers create role change or email`() {
        var calls=0
        val sender=KeycloakParticipantDirectory(config(),"s".repeat(40)) {
            calls++
            200 to (if(calls==1) """{"access_token":"synthetic-token"}""" else """[{"id":"existing"}]""").byteInputStream()
        }
        assertFailsWith<ParticipantExists> { sender.invite("existing@example.test") }
        assertEquals(2,calls)
    }
    @Test fun `master realm redirect and excessive response are rejected`() {
        assertFailsWith<IllegalArgumentException> { KeycloakParticipantDirectory(config("master"),"s".repeat(40)) }
        var calls=0
        val redirect=KeycloakParticipantDirectory(config(),"s".repeat(40)) { calls++;302 to "".byteInputStream() }
        assertFailsWith<IllegalStateException> { redirect.invite("participant@example.test") }
        assertEquals(1,calls)
        val oversized=KeycloakParticipantDirectory(config(),"s".repeat(40)) { 200 to "x".repeat(131073).byteInputStream() }
        assertFailsWith<IllegalStateException> { oversized.invite("participant@example.test") }
    }
}
