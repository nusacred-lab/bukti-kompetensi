package id.nusacred.custodian

import id.nusacred.common.*
import id.nusacred.contracts.Role
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.custodianStatusRoutes(registry: AccessRegistry,guard: RequestGuard) {
    routing { get("/api/v1/service-status") { call.api {
        guard.identity(call,false).requireRole(Role.WALLET_ADMIN)
        call.reply(registry.serviceSummary())
    } } }
}
