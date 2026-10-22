package id.nusacred.issuer

import id.nusacred.common.*
import id.nusacred.custodian.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Path

fun main() {
    val auth = OidcService(requireNotNull(OidcConfig.fromEnvironment()))
    require(auth.config.clientId == "issuer-bff-p2")
    val store = DomainStore(Path.of(requireNotNull(System.getenv("APPLICATION_DB_PATH"))), "issuer", protocol = CredentialProtocol(), offerReferences = requireNotNull(OfferReference.fromEnvironment()))
    val guard = RequestGuard.oidc(auth)
    embeddedServer(Netty, host = System.getenv("HTTP_HOST") ?: "127.0.0.1", port = System.getenv("HTTP_PORT")?.toInt() ?: 18080) {
        portalRoutes("issuer",System.getenv("PORTAL_ROOT")?.let { java.io.File(it) })
        authRoutes(auth)
        businessRoutes(store,guard)
    }.start(wait = true)
}
