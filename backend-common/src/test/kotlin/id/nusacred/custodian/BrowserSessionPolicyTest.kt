package id.nusacred.custodian

import java.time.Instant
import kotlin.test.*

class BrowserSessionPolicyTest {
    private val now=Instant.ofEpochSecond(1000)
    @Test fun `browser has a one hour absolute maximum without extending token validity`() {
        assertEquals(now.plusSeconds(3600),appSessionExpiry(now.plusSeconds(7200),now.plusSeconds(7200),"browser",now))
        assertEquals(now.plusSeconds(300),appSessionExpiry(now.plusSeconds(300),now.plusSeconds(3600),"browser",now))
        assertEquals(now.plusSeconds(120),appSessionExpiry(now.plusSeconds(3600),now.plusSeconds(120),"browser",now))
    }
    @Test fun `native retains short access and its previous session maximum`() {
        assertEquals(now.plusSeconds(300),appSessionExpiry(now.plusSeconds(300),now.plusSeconds(3600),"native",now))
        assertEquals(now.plusSeconds(1800),appSessionExpiry(now.plusSeconds(7200),now.plusSeconds(7200),"native",now))
        assertFailsWith<IllegalArgumentException> { appSessionExpiry(now,now,"other",now) }
    }
}
