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
    val auth = OidcConfig.fromEnvironment()?.let(::OidcService)
    embeddedServer(Netty, host = host, port = port) {
        auth?.let { authRoutes(it) }
        stackProbe(portalRoot)
    }.start(wait = true)
}
