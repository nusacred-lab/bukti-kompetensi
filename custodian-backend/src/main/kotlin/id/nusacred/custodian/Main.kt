package id.nusacred.custodian

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.stackProbe() {
    routing {
        get("/health/live") {
            call.respondText("{\"status\":\"up\",\"scope\":\"stack-probe\"}", ContentType.Application.Json)
        }
    }
}

fun main() {
    embeddedServer(Netty, host = "127.0.0.1", port = 18080) { stackProbe() }.start(wait = true)
}
