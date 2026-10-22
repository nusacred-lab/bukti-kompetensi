package id.nusacred.custodian

import com.nimbusds.jose.util.JSONObjectUtils
import id.nusacred.common.api
import id.nusacred.common.jsonBody
import id.nusacred.common.RequestGuard
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SESSION_COOKIE = "__Host-nusacred-session"
private const val FLOW_COOKIE = "__Host-nusacred-flow"

private fun ApplicationCall.cookie(name: String, value: String, age: Int) {
    response.cookies.append(Cookie(name, value, encoding = CookieEncoding.RAW, path = "/", secure = true, httpOnly = true,
        maxAge = age, extensions = mapOf("SameSite" to "Lax")))
}
private suspend fun ApplicationCall.reply(value: Map<String, Any?>, status: HttpStatusCode = HttpStatusCode.OK) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append("Pragma", "no-cache")
    response.headers.append("X-Content-Type-Options", "nosniff")
    respondText(json(value), ContentType.Application.Json, status)
}
private suspend fun ApplicationCall.guarded(block: suspend () -> Unit) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    try { block() } catch (_: Exception) {
        reply(mapOf("error" to "Authentication could not be verified. Please sign in again."), HttpStatusCode.Unauthorized)
    }
}
private suspend fun ApplicationCall.body(): Map<String, Any> = withContext(Dispatchers.IO) {
    val bytes = receiveStream().use { it.readNBytes(32_769) }
    if (bytes.size > 32_768 || request.headers[HttpHeaders.ContentType]?.substringBefore(';') != "application/json") throw AuthRejected()
    JSONObjectUtils.parse(String(bytes, Charsets.UTF_8))
}
private fun ApplicationCall.identity(): Pair<String, String> {
    val header = request.headers[HttpHeaders.Authorization]
    val cookie = request.cookies[SESSION_COOKIE]
    if (header != null && cookie != null) throw AuthRejected()
    if (header != null) {
        if (!header.startsWith("Bearer ")) throw AuthRejected()
        return header.removePrefix("Bearer ") to "native"
    }
    return (cookie ?: throw AuthRejected()) to "browser"
}

fun Application.authRoutes(auth: OidcService) {
    val rate = RequestGuard.oidc(auth)
    routing {
        post("/auth/native/start") {
            call.guarded { call.reply(auth.startNative()) }
        }
        post("/auth/native/session") {
            call.guarded {
                rate.rate("auth:"+call.request.local.remoteHost,30)
                val values = call.body()
                val opaque = withContext(Dispatchers.IO) {
                    auth.finishNative(values["ticket"] as? String ?: throw AuthRejected(),
                        values["access_token"] as? String ?: throw AuthRejected(),
                        values["id_token"] as? String ?: throw AuthRejected())
                }
                call.reply(mapOf("session" to opaque))
            }
        }
        post("/api/v1/sessions") {
            call.api {
                rate.rate("auth:"+call.request.local.remoteHost,30)
                val values = call.jsonBody(setOf("ticket","access_token","id_token"))
                val opaque = withContext(Dispatchers.IO) {
                    auth.finishNative(values["ticket"] as? String ?: throw AuthRejected(),
                        values["access_token"] as? String ?: throw AuthRejected(),
                        values["id_token"] as? String ?: throw AuthRejected())
                }
                call.reply(mapOf("session" to opaque))
            }
        }
        get("/auth/start") {
            call.guarded {
                rate.rate("auth:"+call.request.local.remoteHost,30)
                val (binding, url) = auth.startBrowser()
                call.cookie(FLOW_COOKIE, binding, 300)
                call.respondRedirect(url)
            }
        }
        get("/auth/callback") {
            call.guarded {
                rate.rate("auth:"+call.request.local.remoteHost,30)
                val q = call.request.queryParameters
                if (q.getAll("state")?.size != 1 || q.getAll("code")?.size != 1 || q.getAll("iss")?.size != 1 || q["iss"] != auth.config.issuer || q["error"] != null) throw AuthRejected()
                val opaque = withContext(Dispatchers.IO) {
                    auth.finishBrowser(q["state"]!!, call.request.cookies[FLOW_COOKIE] ?: throw AuthRejected(), q["code"]!!)
                }
                call.cookie(FLOW_COOKIE, "", 0)
                val lifetime=withContext(Dispatchers.IO) {
                    (auth.session(opaque,"browser").expires.epochSecond-java.time.Instant.now().epochSecond).coerceIn(1,1800).toInt()
                }
                call.cookie(SESSION_COOKIE, opaque, lifetime)
                call.respondRedirect("/")
            }
        }
        get("/auth/account") {
            call.guarded {
                rate.rate("auth:"+call.request.local.remoteHost,30)
                val (opaque, channel) = call.identity()
                if (channel != "browser") throw AuthRejected()
                val session = withContext(Dispatchers.IO) { auth.session(opaque, channel) }
                val nonce = randomToken()
                fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
                    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
                call.response.headers.append("Content-Security-Policy", "default-src 'none'; script-src 'nonce-$nonce'; connect-src 'self'; style-src 'unsafe-inline'; frame-ancestors 'none'")
                call.respondText("""<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                    <title>Competency Proof account</title><body style="font:18px system-ui;padding:32px;max-width:720px">
                    <h1>You are signed in</h1><p>${escape(session.email)}</p>
                    <p>Credential access will become available after recovery checks are complete.</p>
                    <button id="logout">Sign out</button><p id="result"></p>
                    <script nonce="$nonce">document.getElementById('logout').onclick=async()=>{
                    const r=await fetch('/auth/logout',{method:'POST',headers:{'X-CSRF-Token':'${session.csrf}'}});
                    if(r.ok) location.replace('/'); else document.getElementById('result').textContent='Please sign in again.';};</script>
                    </body></html>""".trimIndent(), ContentType.Text.Html)
            }
        }
        get("/api/v1/portal-session") {
            call.api {
                try {
                    val (opaque,channel)=call.identity()
                    if(channel != "browser") throw AuthRejected()
                    val p=rate.identity(call,false)
                    val session=auth.session(opaque,channel)
                    call.reply(mapOf("email" to session.email,"accountId" to p.account,"roles" to p.roles.map { it.name },
                        "organizationId" to p.organization,"csrf" to session.csrf,"expiresAt" to session.expires.epochSecond))
                } catch(e: Exception) {
                    if(call.request.headers[HttpHeaders.Authorization] == null &&
                        (e is AuthRejected || (e is id.nusacred.common.ApiFailure && e.status==401)))
                        call.cookie(SESSION_COOKIE,"",0)
                    throw e
                }
            }
        }
        get("/auth/me") {
            call.guarded {
                rate.rate("auth:"+call.request.local.remoteHost,30)
                val (opaque, channel) = call.identity()
                val session = withContext(Dispatchers.IO) { auth.session(opaque, channel) }
                call.reply(mapOf("email" to session.email, "subject" to session.subject,
                    "csrf" to if (channel == "browser") session.csrf else null,
                    "expires_at" to session.expires.epochSecond,
                    "wallet_access" to "unavailable_until_recovery_registry_is_proven"))
            }
        }
        post("/auth/logout") {
            call.guarded {
                rate.rate("auth:"+call.request.local.remoteHost,30)
                val (opaque, channel) = call.identity()
                val session = withContext(Dispatchers.IO) { auth.session(opaque, channel) }
                if (channel == "browser" && (call.request.headers[HttpHeaders.Origin] != auth.config.portalOrigin ||
                    call.request.headers["X-CSRF-Token"] != session.csrf)) throw AuthRejected()
                auth.logout(opaque)
                if (channel == "browser") call.cookie(SESSION_COOKIE, "", 0)
                call.reply(mapOf("signed_out" to true))
            }
        }
        delete("/api/v1/sessions/current") {
            call.api {
                rate.rate("auth:"+call.request.local.remoteHost,30)
                val (opaque, channel) = call.identity()
                val session = withContext(Dispatchers.IO) { auth.session(opaque, channel) }
                if (channel == "browser" && (call.request.headers[HttpHeaders.Origin] != auth.config.portalOrigin ||
                    call.request.headers["X-CSRF-Token"] != session.csrf)) throw AuthRejected()
                auth.logout(opaque)
                if (channel == "browser") call.cookie(SESSION_COOKIE, "", 0)
                call.reply(mapOf("signed_out" to true))
            }
        }
    }
}
