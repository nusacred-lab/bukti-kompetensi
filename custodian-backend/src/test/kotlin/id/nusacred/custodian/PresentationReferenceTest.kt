package id.nusacred.custodian

import kotlin.test.*
import org.junit.Test

class PresentationReferenceTest {
    @Test fun `presentation references cannot select arbitrary network destinations or inline requests`() {
        val good = "openid4vp://authorize?request_uri=http%3A%2F%2Fverifier%3A7004%2Fverification-session%2Fsession-1%2Frequest&request_uri_method=post&client_id=redirect_uri%3Ahttp%3A%2F%2Fverifier%3A7004%2Fverification-session%2Fsession-1%2Fresponse"
        validatePresentationUrl(good)
        for (bad in listOf(good.replace("verifier", "evil"), good + "&request=inline", good + "&client_id=duplicate",
            good.replace("post", "get"), good.replace("session-1%2Frequest", "..%2Fadmin"),
            good.replace("authorize?", "authorize/path?"), good + "#fragment")) {
            assertFailsWith<AccessDenied> { validatePresentationUrl(bad) }
        }
    }
}
