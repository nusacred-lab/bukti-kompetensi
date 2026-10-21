package id.nusacred.wallet

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
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

class MainActivity : ComponentActivity() {
    private val network = Executors.newSingleThreadExecutor()
    private lateinit var authorization: AuthorizationService
    private lateinit var store: SessionStore
    private var ticket: String? = null
    private var message by mutableStateOf("Sign in to your account.")
    private var busy by mutableStateOf(false)
    private var signedIn by mutableStateOf(false)
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
                    store.save(session)
                    account(session)
                }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authorization = AuthorizationService(this)
        store = SessionStore(this)
        // A lost in-flight transaction is cancelled rather than reconstructed without its nonce/state.
        setContent {
            Column(Modifier.fillMaxSize().background(Color.White)
                .windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp), Arrangement.spacedBy(20.dp)) {
                BasicText("Competency Wallet", style = TextStyle(color = Color.Black, fontSize = 24.sp))
                BasicText(message, style = TextStyle(color = Color.Black, fontSize = 16.sp))
                BasicText(if (busy) "Please wait…" else if (signedIn) "Check account" else "Sign in",
                    Modifier.clickable(enabled = !busy) {
                        if (signedIn) submit { account(store.load() ?: error("Missing session")) } else begin()
                    }.padding(12.dp), style = TextStyle(color = Color(0xFF1645A0), fontSize = 18.sp))
                if (signedIn) BasicText("Sign out", Modifier.clickable(enabled = !busy) {
                    submit {
                        try { request("/auth/logout", "POST", JSONObject(), store.load()) } finally { store.clear() }
                        runOnUiThread { signedIn = false; message = "You are signed out." }
                    }
                }.padding(12.dp), style = TextStyle(color = Color(0xFF1645A0), fontSize = 18.sp))
                BasicText("Credential features will become available after access and recovery checks are complete.",
                    style = TextStyle(color = Color.DarkGray, fontSize = 14.sp))
            }
        }
        store.load()?.let { submit { account(it) } }
    }
    private fun submit(releaseBusy: Boolean = true, work: () -> Unit) {
        busy = true
        network.execute {
            try { work() } catch (_: Exception) {
                store.clear()
                runOnUiThread { busy = false; signedIn = false; message = "Your account could not be verified. Please sign in again." }
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
        runOnUiThread { signedIn = true; message = "Signed in as ${user.getString("email")}." }
    }
    private fun request(path: String, method: String, body: JSONObject?, session: String? = null): JSONObject {
        check(path in setOf("/auth/native/start", "/auth/native/session", "/auth/me", "/auth/logout"))
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
            check(connection.responseCode == 200)
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
