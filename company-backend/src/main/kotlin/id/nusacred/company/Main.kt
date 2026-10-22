package id.nusacred.company

import id.nusacred.common.*
import id.nusacred.custodian.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Path

fun main() {
    val auth = OidcService(requireNotNull(OidcConfig.fromEnvironment()))
    require(auth.config.clientId == "company-bff-p2")
    val store = DomainStore(Path.of(requireNotNull(System.getenv("APPLICATION_DB_PATH"))), "company", protocol = CredentialProtocol())
    val guard = RequestGuard.oidc(auth)
    embeddedServer(Netty, host = System.getenv("HTTP_HOST") ?: "127.0.0.1", port = System.getenv("HTTP_PORT")?.toInt() ?: 18080) {
        authRoutes(auth)
        businessRoutes(store,guard)
    }.start(wait = true)
}
