package id.nusacred.common

import kotlin.test.*

class OfferReferenceTest {
    @Test fun `recipient expiry endpoint and encryption integrity are mandatory`() {
        val codec=OfferReference(ByteArray(32) { it.toByte() })
        val url="openid-credential-offer://?credential_offer_uri=http%3A%2F%2Fissuer%3A7005%2Fopenid4vci%2Fcredential-offer%3Fid%3Doffer1"
        val reference=codec.seal("offer1","alice","candidate",url,2000)
        assertEquals("candidate",codec.open(reference,"alice",1999)["candidate"])
        assertFalse(reference.contains("candidate"))
        assertFailsWith<ApiFailure> { codec.open(reference,"bob",1999) }
        assertFailsWith<ApiFailure> { codec.open(reference,"alice",2000) }
        assertFailsWith<ApiFailure> { codec.open(reference.replaceRange(30,31,if(reference[30]=='A') "B" else "A"),"alice",1000) }
        assertFailsWith<ApiFailure> { OfferReference(ByteArray(32)).open(reference,"alice",1000) }
        for(host in listOf("attacker.example","169.254.169.254","issuer:7005@evil.example")) {
            assertFails { codec.seal("offer","alice","candidate",url.replace("issuer%3A7005",host),2000) }
        }
    }
}
