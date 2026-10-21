package id.nusacred.custodian

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.http.content.staticFiles
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.File

fun Application.stackProbe(portalRoot: File? = null) {
    routing {
        get("/health/live") {
            call.respondText("{\"status\":\"up\",\"scope\":\"stack-probe\"}", ContentType.Application.Json)
        }
        if (portalRoot != null) {
            require(portalRoot.isDirectory) { "PORTAL_ROOT must be an existing asset directory" }
            staticFiles("/", portalRoot)
        }
    }
}

fun main() {
    val host = System.getenv("HTTP_HOST") ?: "127.0.0.1"
    val port = System.getenv("HTTP_PORT")?.let {
        requireNotNull(it.toIntOrNull()?.takeIf { port -> port in 1..65535 }) { "Invalid HTTP_PORT" }
    } ?: 18080
    val portalRoot = System.getenv("PORTAL_ROOT")?.let(::File)
    val registry = System.getenv("ACCESS_REGISTRY_PATH")?.let { AccessRegistry(java.nio.file.Path.of(it)) }
    val auth = OidcConfig.fromEnvironment()?.let { config ->
        // Discovery remains strict. Startup may wait for the identity container to become ready.
        val deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos()
        var service: OidcService? = null
        while (service == null) {
            try { service = OidcService(config, registry) }
            catch (e: Exception) {
                if (System.nanoTime() >= deadline) throw IllegalStateException("Identity service did not become ready", e)
                Thread.sleep(1000)
            }
        }
        service
    }
    val wallet = System.getenv("WALLET_ORIGIN")?.let {
        WalletService(requireNotNull(registry), WalletClient(it))
    }
    val recoverySecret = System.getenv("RECOVERY_SECRET_FILE")?.let {
        java.nio.file.Files.readString(java.nio.file.Path.of(it)).trim().toByteArray(Charsets.US_ASCII)
    }
    embeddedServer(Netty, host = host, port = port) {
        if (registry != null && recoverySecret != null) recoveryRoutes(registry,
            RecoveryAuthenticator(requireNotNull(System.getenv("OIDC_ISSUER")), recoverySecret))
        auth?.let { authRoutes(it) }
        if (auth != null && wallet != null) walletRoutes(auth, wallet)
        stackProbe(portalRoot)
    }.start(wait = true)
}
