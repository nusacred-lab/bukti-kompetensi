package id.nusacred.wallet

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private class WalletHttpFailure(val status: Int, val path: String) : RuntimeException()

class MainActivity : ComponentActivity() {
    private val network = Executors.newSingleThreadExecutor()
    private lateinit var authorization: AuthorizationService
    private lateinit var store: SessionStore
    private var ticket: String? = null
    private var message by mutableStateOf("Sign in to your account.")
    private var busy by mutableStateOf(false)
    private var signedIn by mutableStateOf(false)
    private var scanning by mutableStateOf(false)
    private var sessionExpiresAt by mutableStateOf(0L)
    private var pendingLink by mutableStateOf<String?>(null)
    private var preview by mutableStateOf<JSONObject?>(null)
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scanning = granted
        if (!granted) message = "Camera permission is needed to scan a QR code. You can also open a wallet link."
    }
    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val response = data?.let(AuthorizationResponse::fromIntent)
        val error = data?.let(AuthorizationException::fromIntent)
        val pending = ticket
        ticket = null
        if (response == null || error != null || pending == null) {
            busy = false; message = "Sign-in was cancelled or could not be verified."
        } else {
            authorization.performTokenRequest(response.createTokenExchangeRequest()) { tokens, exception ->
                if (tokens?.accessToken == null || tokens.idToken == null || exception != null) {
                    runOnUiThread { busy = false; message = "Sign-in could not be verified. Please try again." }
                } else submit {
                    val session = request("/auth/native/session", "POST", JSONObject().put("ticket", pending)
                        .put("access_token", tokens.accessToken).put("id_token", tokens.idToken)).getString("session")
                    account(session)
                }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authorization = AuthorizationService(this)
        store = SessionStore(this)
        pendingLink = store.context()
        sessionExpiresAt = store.expiresAt()
        if (savedInstanceState == null) consumePresentationIntent(intent)
        else setIntent(Intent(intent).setData(null))
        // A lost in-flight transaction is cancelled rather than reconstructed without its nonce/state.
        setContent {
            LaunchedEffect(sessionExpiresAt) {
                if (sessionExpiresAt > 0) {
                    delay(((sessionExpiresAt - System.currentTimeMillis() / 1000).coerceAtLeast(0)) * 1000)
                    store.clearSession(); signedIn = false; preview = null; sessionExpiresAt = 0
                    pendingLink = store.context()
                    message = if (pendingLink != null) "Your session expired. Sign in to review your saved request."
                        else "Your session expired. Sign in when you are ready to review a request."
                }
            }
            Column(Modifier.fillMaxSize().background(Color.White)
                .windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(24.dp), Arrangement.spacedBy(20.dp)) {
                BasicText("Competency Wallet", style = TextStyle(color = Color.Black, fontSize = 24.sp))
                BasicText(message, style = TextStyle(color = Color.Black, fontSize = 16.sp))
                if (scanning) {
                    QrScanner(this@MainActivity, this@MainActivity, Modifier.fillMaxWidth().height(280.dp),
                        onCode = {
                            scanning = false
                            val supported = receiveLink(it)
                            Log.i("WalletScan", if (supported) "Supported optical QR decoded" else "Unsupported optical QR decoded")
                            if (supported) message = if (store.load() == null)
                                "QR code recognized. Sign in to review your saved request."
                                else "QR code recognized. Tap Review request to continue."
                        },
                        onError = { scanning = false; message = "The camera is unavailable. Please open a wallet link." })
                    BasicText("Cancel scanning", Modifier.clickable { scanning = false }.padding(12.dp))
                } else BasicText("Scan QR code", Modifier.clickable(enabled = !busy) {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
                    else cameraPermission.launch(Manifest.permission.CAMERA)
                }.padding(12.dp), style = TextStyle(color = Color(0xFF1645A0), fontSize = 18.sp))
                BasicText(if (busy) "Please wait…" else if (signedIn) "Check account" else "Sign in",
                    Modifier.clickable(enabled = !busy) {
                        if (signedIn) submit { account(store.load() ?: error("Missing session")) } else begin()
                    }.padding(12.dp), style = TextStyle(color = Color(0xFF1645A0), fontSize = 18.sp))
                if (signedIn) BasicText("Sign out", Modifier.clickable(enabled = !busy) {
                    submit {
                        try { request("/auth/logout", "POST", JSONObject(), store.load()) } finally { store.clear() }
                        runOnUiThread { signedIn = false; pendingLink = null; preview = null; message = "You are signed out." }
                    }
                }.padding(12.dp), style = TextStyle(color = Color(0xFF1645A0), fontSize = 18.sp))
                if (pendingLink != null) {
                    BasicText(if (signedIn) "A presentation link is ready to review." else "A presentation link is saved. Sign in to review it.")
                    if (signedIn && preview == null) BasicText("Review request", Modifier.clickable(enabled = !busy) {
                        val link = pendingLink ?: return@clickable
                        submit {
                            val session = store.load() ?: error("Missing session")
                            check(store.context() == link)
                            request("/api/v1/wallet/provision", "POST", JSONObject(), session)
                            val response = request("/api/v1/wallet/preview", "POST", JSONObject().put("request_url",
                                Uri.parse(link).getQueryParameter("request_url")), session)
                            runOnUiThread { preview = response; message = "Review the selected credential before sending." }
                        }
                    }.padding(12.dp), style = TextStyle(color = Color(0xFF1645A0), fontSize = 18.sp))
                    preview?.let { current ->
                        val details = current.getJSONObject("details")
                        BasicText("Credential: ${details.optString("credential_label", "Competency Proof")}")
                        BasicText("Recipient: ${details.optString("recipient")}")
                        BasicText("Issuer: ${details.optString("issuer")}")
                        val disclosures = details.getJSONArray("disclosures")
                        for (index in 0 until disclosures.length()) {
                            val claim = disclosures.getJSONObject(index)
                            BasicText("${claim.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: claim.optString("path")}: ${claim.opt("value")}")
                        }
                        BasicText("Send this credential", Modifier.clickable(enabled = !busy) {
                            val link = pendingLink ?: return@clickable
                            submit {
                                check(store.context() == link)
                                check(current.getLong("expires_at") > System.currentTimeMillis() / 1000)
                                val response = request("/api/v1/wallet/present", "POST", JSONObject()
                                    .put("context_id", current.getString("context_id"))
                                    .put("summary_hash", current.getString("summary_hash")).put("consent", true), store.load())
                                check(response.getBoolean("transmission_success"))
                                store.clearContext()
                                runOnUiThread { preview = null; pendingLink = null; message = "Your credential was sent." }
                            }
                        }.padding(12.dp), style = TextStyle(color = Color(0xFF1645A0), fontSize = 18.sp))
                    }
                    BasicText("Decline request", Modifier.clickable(enabled = !busy) {
                        store.clearContext(); pendingLink = null; preview = null; message = "The request was declined."
                    }.padding(12.dp))
                }
            }
        }
        store.load()?.let { submit { account(it) } }
    }
    private fun submit(releaseBusy: Boolean = true, work: () -> Unit) {
        busy = true
        network.execute {
            try { work() } catch (failure: Exception) {
                // Exception classes reveal transport failures without logging tokens, URLs or account data.
                Log.w("WalletAuth", "Account request failed: ${failure.javaClass.simpleName}")
                val existing = store.load()
                var rejectedSession = failure is WalletHttpFailure && failure.status == 401
                if (rejectedSession && failure is WalletHttpFailure &&
                    failure.path.startsWith("/api/v1/wallet/") && existing != null) {
                    // Invalid/expired requests must not log out an otherwise valid account.
                    try { request("/auth/me", "GET", null, existing); rejectedSession = false }
                    catch (probe: Exception) { rejectedSession = probe is WalletHttpFailure && probe.status == 401 }
                }
                if (rejectedSession) store.clearSession()
                runOnUiThread {
                    busy = false; preview = null
                    if (rejectedSession) {
                        signedIn = false; sessionExpiresAt = 0
                        pendingLink = store.context()
                        message = "Your session expired or was revoked. Sign in to continue." +
                            if (pendingLink != null) " Your request is saved." else ""
                    } else message = "The request is unavailable. Check your connection or scan a fresh QR code."
                }
            } finally { if (releaseBusy) runOnUiThread { busy = false } }
        }
    }
    private fun begin() = submit(releaseBusy = false) {
        val flow = request("/auth/native/start", "POST", JSONObject())
        check(flow.getString("issuer") == BuildConfig.OIDC_ISSUER && flow.getString("client_id") == "android-wallet-p2")
        ticket = flow.getString("ticket")
        AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(BuildConfig.OIDC_ISSUER)) { config, error ->
            runOnUiThread {
                if (config == null || error != null) {
                    if (BuildConfig.DEBUG) Log.w("WalletAuth", "Discovery failed: type=${error?.type}, code=${error?.code}, cause=${error?.cause?.javaClass?.simpleName}")
                    ticket = null; busy = false; message = "Sign-in is temporarily unavailable."
                } else {
                    val request = AuthorizationRequest.Builder(config, "android-wallet-p2", ResponseTypeValues.CODE,
                        Uri.parse(BuildConfig.OIDC_REDIRECT_URI)).setScope("openid profile email")
                        .setNonce(flow.getString("nonce")).setPrompt("login")
                        .setAdditionalParameters(mapOf("max_age" to "0")).build()
                    check(request.codeVerifier != null && request.codeVerifierChallengeMethod == "S256")
                    busy = true
                    try { login.launch(authorization.getAuthorizationRequestIntent(request)) }
                    catch (_: Exception) { busy = false; ticket = null; message = "A browser is required to sign in." }
                }
            }
        }
    }
    private fun account(session: String) {
        val user = request("/auth/me", "GET", null, session)
        val expires = user.getLong("expires_at")
        store.save(session, expires)
        runOnUiThread { sessionExpiresAt = expires; signedIn = true; message = "Signed in as ${user.getString("email")}." }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumePresentationIntent(intent)
    }
    private fun consumePresentationIntent(incoming: Intent) {
        val link = incoming.data?.toString()
        // A previously consumed launch link must never overwrite a later camera scan on recreation.
        setIntent(Intent(incoming).setData(null))
        if (link != null) receiveLink(link)
    }
    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) {
            val context = store.context()
            if (context != pendingLink) { pendingLink = context; preview = null }
            if (signedIn && store.load() == null) {
                signedIn = false; sessionExpiresAt = 0; preview = null
                message = "Your session expired. Sign in to continue."
            }
        }
    }
    private fun receiveLink(value: String): Boolean {
        return try {
            val uri = Uri.parse(value)
            check(value.length <= 8192 && uri.scheme == "https" && uri.authority == "wallet-p2.demo.inoerawan.id" &&
                uri.path == "/present" && uri.fragment == null && uri.queryParameterNames == setOf("request_url") &&
                uri.getQueryParameters("request_url").size == 1)
            check(!uri.getQueryParameter("request_url").isNullOrBlank())
            store.saveContext(value); pendingLink = value; preview = null
            message = "Presentation link received. Review it before sending."
            true
        } catch (_: Exception) { message = "This QR code is not a supported wallet link."; false }
    }
    private fun request(path: String, method: String, body: JSONObject?, session: String? = null): JSONObject {
        check(path in setOf("/auth/native/start", "/auth/native/session", "/auth/me", "/auth/logout",
            "/api/v1/wallet/provision", "/api/v1/wallet/preview", "/api/v1/wallet/present"))
        val connection = URL(BuildConfig.WALLET_ORIGIN + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000; connection.readTimeout = 15_000
            session?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status != 200) {
                Log.w("WalletAuth", "Wallet response status: $status")
                throw WalletHttpFailure(status, path)
            }
            val bytes = connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (output.size() <= 32_768) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, 32_769 - output.size()))
                    if (count == -1) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            check(bytes.size <= 32_768)
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
    override fun onDestroy() {
        authorization.dispose()
        network.shutdownNow()
        super.onDestroy()
    }
}
