package id.nusacred.wallet

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.nusacred.ui.*
import kotlinx.coroutines.delay
import net.openid.appauth.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Executors

private class WalletHttpFailure(val status: Int,val code: String) : RuntimeException()
private data class CredentialItem(val id:String,val claims:JSONObject)

class MainActivity : ComponentActivity() {
    private val network=Executors.newSingleThreadExecutor()
    private lateinit var authorization:AuthorizationService
    private lateinit var store:SessionStore
    private var generation=0
    private var walletLocked by mutableStateOf(false)
    private var biometricEnabled by mutableStateOf(false)
    private var biometricPrompt:CancellationSignal?=null
    private var ticket:String?=null
    private var busy by mutableStateOf(false)
    private var signedIn by mutableStateOf(false)
    private var scanning by mutableStateOf(false)
    private var expiresAt by mutableStateOf(0L)
    private var email by mutableStateOf("")
    private var recipient by mutableStateOf("")
    private var page by mutableStateOf("wallet")
    private var notice by mutableStateOf("")
    private var errorNotice by mutableStateOf(false)
    private var pendingLink by mutableStateOf<String?>(null)
    private var preview by mutableStateOf<JSONObject?>(null)
    private var selected by mutableStateOf<CredentialItem?>(null)
    private var credentials by mutableStateOf<List<CredentialItem>>(emptyList())
    private var uncertain by mutableStateOf(false)
    private val cameraPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        scanning=it
        if(!it) inform("Allow camera access to scan a code, or open a wallet link instead.",true)
    }
    private val login=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val response=result.data?.let(AuthorizationResponse::fromIntent)
        val failure=result.data?.let(AuthorizationException::fromIntent)
        val pending=ticket;ticket=null
        if(response==null || failure!=null || pending==null) {
            busy=false;inform("Sign-in was cancelled. Your request is still available.")
        } else {
            val expected=generation
            authorization.performTokenRequest(response.createTokenExchangeRequest()) { tokens,exception ->
                if(tokens?.accessToken==null || tokens.idToken==null || exception!=null) ui(expected) {
                    busy=false;inform("Sign-in could not be completed. Please try again.",true)
                } else ui(expected) { submit { g ->
                    val session=obj("/auth/native/session","POST",JSONObject().put("ticket",pending)
                        .put("access_token",tokens.accessToken).put("id_token",tokens.idToken)).getString("session")
                    account(session,g)
                    ui(g) { tokens.refreshToken?.let { store.setRefresh(it,System.currentTimeMillis()/1000+604800) } }
                } }
            }
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        authorization=AuthorizationService(this);store=SessionStore(this)
        biometricEnabled=store.protected();walletLocked=store.locked()
        pendingLink=store.context();expiresAt=store.expiresAt()
        if(savedInstanceState==null) consumeIntent(intent) else setIntent(Intent(intent).setData(null))
        setContent {
            LaunchedEffect(expiresAt) {
                if(expiresAt>0) {
                    delay(((expiresAt-System.currentTimeMillis()/1000).coerceAtLeast(0))*1000)
                    while(busy) delay(200)
                    if(store.refreshToken()!=null) renewAuthentication()
                    else { generation++;store.clearSession();clearIdentity();inform("Your session has ended. Sign in again to continue.") }
                }
            }
            BackHandler(enabled=scanning || selected!=null || preview!=null || page!="wallet") {
                when { scanning -> scanning=false;selected!=null -> selected=null;preview!=null -> preview=null;else -> page="wallet" }
            }
            WalletApp()
        }
        if(!walletLocked) store.load()?.let { submit { g -> account(it,g) } }
    }
    @Composable private fun WalletApp() {
        Column(Modifier.fillMaxSize().background(Palette.Paper).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().background(Palette.White).padding(horizontal=22.dp,vertical=16.dp),
                horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Brand();Pill(if(signedIn) "My wallet" else "Secure wallet",if(signedIn) "success" else "neutral")
            }
            Box(Modifier.weight(1f)) {
                Column(Modifier.fillMaxSize().verticalScroll(remember(signedIn,scanning,page,selected?.id,preview?.optString("id")) { ScrollState(0) }).padding(22.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                    OrganizationIdentity(Entities.Wallet,compact=true)
                    if(notice.isNotEmpty()) Notice(notice,errorNotice)
                    when {
                        walletLocked -> LockedScreen()
                        scanning -> ScannerScreen()
                        preview!=null -> ConsentScreen(preview!!)
                        selected!=null -> DetailScreen(selected!!)
                        !signedIn -> WelcomeScreen()
                        page=="account" -> AccountScreen()
                        else -> HomeScreen()
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if(busy) Row(Modifier.align(Alignment.BottomCenter).padding(14.dp).background(Palette.Ink,RoundedCornerShape(10.dp)).padding(horizontal=18.dp,vertical=10.dp)) {
                    Label("Working securely…",13,Color.White)
                }
            }
            if(signedIn && !scanning && preview==null && selected==null) {
                Rule()
                Row(Modifier.fillMaxWidth().background(Palette.White).padding(horizontal=16.dp,vertical=10.dp),horizontalArrangement=Arrangement.SpaceEvenly) {
                    NavItem("wallet","wallet","Wallet");NavItem("scan","scan","Scan");NavItem("account","shield","Account")
                }
            }
        }
    }
    @Composable private fun LockedScreen() {
        Eyebrow("YOUR WALLET, PROTECTED");Heading("Welcome back.\nMake it yours.",30)
        Label("Unlock with your device biometrics to continue. Nothing is shared until you review and approve.",15,Palette.Muted)
        Action("Unlock with biometrics",Modifier.fillMaxWidth(),icon="shield",enabled=!busy) { authenticateVault() }
        Action("Sign in another way",Modifier.fillMaxWidth(),primary=false,enabled=!busy) { begin() }
        Label("Provided by PT Dompet Identitas Nusantara (demo)",12,Palette.Muted)
    }
    private fun authenticateVault() {
        if(busy) return
        if(getSystemService(BiometricManager::class.java).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)!=BiometricManager.BIOMETRIC_SUCCESS) {
            inform("Strong device biometrics are unavailable. You can sign in securely instead.",true);return
        }
        val restoring=store.protected();val expected=generation
        try {
            val cipher=store.protectionCipher()
            val cancel=CancellationSignal();biometricPrompt=cancel;busy=true
            BiometricPrompt.Builder(this).setTitle(if(restoring) "Unlock NusaCred Wallet" else "Protect your wallet")
                .setSubtitle("PT Dompet Identitas Nusantara")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Cancel",mainExecutor) { _,_ -> busy=false }
                .build().authenticate(BiometricPrompt.CryptoObject(cipher),cancel,mainExecutor,object:BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult) { ui(expected) {
                        busy=false
                        try {
                            store.completeProtection(requireNotNull(result.cryptoObject?.cipher));biometricEnabled=true;walletLocked=false
                            if(restoring) {
                                val session=store.load()
                                if(session!=null) submit { g -> account(session,g) } else renewAuthentication()
                            } else inform("Biometric unlock is enabled. You can return without entering your password while your account login remains valid.")
                        } catch(_:Exception) { store.clear();biometricEnabled=false;walletLocked=false;clearIdentity();inform("Device protection changed. Sign in again to protect your wallet.",true) }
                    } }
                    override fun onAuthenticationError(code:Int,message:CharSequence) { ui(expected) { busy=false;inform("Wallet remains protected. Try biometrics again or sign in securely.") } }
                })
        } catch(_:Exception) { store.clear();biometricEnabled=false;walletLocked=false;clearIdentity();inform("Device protection changed. Sign in again.",true) }
    }
    private fun renewAuthentication() {
        if(busy) return
        val refresh=store.refreshToken() ?: run { store.clearSession();clearIdentity();inform("Sign in again to continue.");return }
        val previous=store.renewalSession() ?: return
        val until=store.refreshUntil();val expected=generation;busy=true
        // Consume before dispatch: a lost rotating-token response must require login, never blind replay.
        store.consumeRefresh()
        AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(BuildConfig.OIDC_ISSUER)) { config,error -> ui(expected) {
            if(config==null || error!=null) { store.clearSession();clearIdentity();inform("Reconnect and sign in again to continue.",true) }
            else authorization.performTokenRequest(TokenRequest.Builder(config,"android-wallet-p2")
                .setGrantType(GrantTypeValues.REFRESH_TOKEN).setRefreshToken(refresh).build()) { tokens,failure -> ui(expected) {
                if(tokens?.accessToken==null || tokens.idToken==null || tokens.refreshToken==null || failure!=null) {
                    store.clearSession();clearIdentity();inform("Your account login has ended. Sign in again to continue.")
                } else {
                    busy=false
                    submit { g ->
                        val next=obj("/api/v1/sessions/renew","POST",JSONObject().put("session",previous).put("access_token",tokens.accessToken).put("id_token",tokens.idToken)).getString("session")
                        ui(g) { preview=null }
                        account(next,g)
                        ui(g) { store.setRefresh(tokens.refreshToken!!,until) }
                    }
                }
            } }
        } }
    }
    override fun onStop() {
        super.onStop();biometricPrompt?.cancel();biometricPrompt=null
        if(::store.isInitialized && store.protected()) {
            generation++;store.lock();clearIdentity();walletLocked=true
        }
    }
    @Composable private fun RowScope.NavItem(id:String,icon:String,label:String) {
        Column(Modifier.weight(1f).heightIn(min=52.dp).clip(RoundedCornerShape(10.dp))
            .clickable(enabled=!busy) { if(id=="scan") startScan() else { page=id;notice="" } }.padding(8.dp),
            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Icon(icon,if(page==id) Palette.Teal else Palette.Muted)
            Label(label,11,if(page==id) Palette.Teal else Palette.Muted,FontWeight.Medium)
        }
    }
    @Composable private fun WelcomeScreen() {
        if(pendingLink!=null) {
            Eyebrow("REQUEST READY")
            Heading("Continue securely.",32)
            Label("Sign in to review the request addressed to your wallet. You will choose whether to approve it.",16,Palette.Muted)
            Card { Icon("shield",Palette.Teal,Modifier.size(30.dp));Heading("Your approval comes first.",23)
                Label("Opening a code does not receive or share a credential. Review the details after signing in.",14,Palette.Muted) }
            Action("Sign in to your wallet",Modifier.fillMaxWidth(),enabled=!busy,icon="arrow") { begin() }
            Action("Scan a different code",Modifier.fillMaxWidth(),primary=false,enabled=!busy,icon="scan") { startScan() }
            Label("NusaCred · Synthetic demonstration",11,Palette.Muted)
            return
        }
        Spacer(Modifier.height(14.dp))
        Eyebrow("YOUR COMPETENCY. YOUR NEXT OPPORTUNITY.")
        Heading("Good work deserves\na trusted record.",34)
        Label("Keep your competency credentials together. Review every request, and choose what you share.",16,Palette.Muted)
        Card(background=Palette.Ink) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Icon("document",Color.White,Modifier.size(32.dp));Pill("COMPETENCY RECORD","success")
            }
            Spacer(Modifier.height(16.dp));Label("Proof you can\ncarry forward.",26,Color.White,FontWeight.SemiBold)
            Label("Issued by your certifier. Ready for your next step.",13,Color(0xFFBDD4C9))
        }
        if(pendingLink!=null) Notice("A request is waiting. Sign in to review it before making a choice.")
        Action("Sign in to your wallet",Modifier.fillMaxWidth(),enabled=!busy,icon="arrow") { begin() }
        Action("Scan a QR code",Modifier.fillMaxWidth(),primary=false,enabled=!busy,icon="scan") { startScan() }
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Icon("shield",Palette.Teal,Modifier.size(18.dp));Label("Your credentials stay securely stored. Sharing always asks for your approval.",12,Palette.Muted)
        }
        Label("NusaCred · Synthetic demonstration",11,Palette.Muted)
    }
    @Composable private fun HomeScreen() {
        Eyebrow("MY CREDENTIALS")
        Heading("Ready for your\nnext opportunity.",29)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Label("${credentials.size} credential${if(credentials.size==1) "" else "s"} in your wallet",14,Palette.Muted)
            Action("Refresh",primary=false,enabled=!busy,icon="refresh") { refresh() }
        }
        if(pendingLink!=null) Card(background=Palette.TealSoft) {
            Icon("document",Palette.Teal);Heading(if(receiving()) "A credential is waiting" else "A sharing request is ready",20)
            Label(if(receiving()) "Review the offer from your certifier before adding it." else "Choose a credential and review the recipient before sharing.",14,Palette.Muted)
            Action(if(uncertain) "Check request status" else "Review request",Modifier.fillMaxWidth(),enabled=!busy,icon="arrow") {
                if(uncertain) submit { g -> restoreOperation(g) } else review()
            }
            Action("Dismiss request",primary=false,enabled=!busy) { dismiss() }
        }
        if(credentials.isEmpty()) Card {
            EmptyState("Your first credential starts here","Scan the offer from your certifier to add it.","wallet") {
                Action("Scan an offer",enabled=!busy,icon="scan") { startScan() }
            }
        }
        credentials.forEach { item ->
            Card(Modifier.fillMaxWidth().clickable(enabled=!busy) { selected=item }) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Box(Modifier.size(42.dp).background(Palette.TealSoft,RoundedCornerShape(11.dp)).padding(10.dp)) { Icon("document",Palette.Teal) }
                    Pill("Stored","success")
                }
                Heading(competency(item.claims.optString("competency_code")),21)
                Label(item.claims.optString("competency_code"),12,Palette.Muted)
                Rule()
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Label("Valid until ${date(item.claims.optLong("exp"))}",12,Palette.Muted);Icon("arrow",Palette.Teal,Modifier.size(18.dp))
                }
            }
        }
        Notice("You decide when to share. Scanning a code never sends a credential automatically.")
    }
    @Composable private fun DetailScreen(item:CredentialItem) {
        Action("Back to wallet",primary=false,icon="arrow") { selected=null }
        Eyebrow("COMPETENCY CREDENTIAL");Heading(competency(item.claims.optString("competency_code")),29)
        Card {
            Pill(if(item.claims.optString("assessment_result")=="competent") "Competent" else "Assessment recorded",if(item.claims.optString("assessment_result")=="competent") "success" else "warning")
            KeyValue("Competency",item.claims.optString("competency_code"))
            KeyValue("Candidate",item.claims.optString("candidate_id"))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f)) { KeyValue("Issued",date(item.claims.optLong("iat"))) }
                Column(Modifier.weight(1f)) { KeyValue("Valid until",date(item.claims.optLong("exp"))) }
            }
            Rule();Label("Issuer identity",12,Palette.Muted);Label(item.claims.optString("iss"),11,Palette.Muted)
        }
        Notice("A receiving company checks the signature, validity and its requirements when you share this credential.")
        Action("Scan a request to share",Modifier.fillMaxWidth(),enabled=!busy,icon="scan") { selected=null;startScan() }
    }
    @Composable private fun AccountScreen() {
        Eyebrow("ACCOUNT & SECURITY");Heading("A wallet that\nstays yours.",30)
        Card {
            KeyValue("Signed in as",email)
            Rule();KeyValue("Your recipient code",recipient)
            Label("Share this code with your certifier so an offer is addressed to your account.",12,Palette.Muted)
            Action("Copy recipient code",primary=false,enabled=!busy) {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("NusaCred recipient code",recipient))
                inform("Recipient code copied.")
            }
        }
        Card {
            Icon("shield",Palette.Teal);Heading("Secure by design",20)
            Label("Credentials and signing keys stay with your custodian. Your session on this phone is protected by Android Keystore.",14,Palette.Muted)
            Label("If you reset your password, sign in again. Old sessions and unapproved requests cannot be reused.",14,Palette.Muted)
        }
        if(!biometricEnabled && store.refreshToken()!=null) {
            Action("Enable biometric unlock",Modifier.fillMaxWidth(),icon="shield",enabled=!busy) { authenticateVault() }
            Label("Keep your account signed in for up to 7 days, with a 24-hour inactivity limit. Your refresh token is protected by device biometrics.",12,Palette.Muted)
        } else if(biometricEnabled) Label("Biometric unlock enabled · account login up to 7 days",12,Palette.Teal)
        Action("Open secure sign-in & recovery",Modifier.fillMaxWidth(),primary=false,enabled=!busy) {
            begin()
        }
        Action("Sign out",Modifier.fillMaxWidth(),primary=false,icon="logout") { signOut() }
        Label("NusaCred Wallet · ${BuildConfig.VERSION_NAME}",12,Palette.Muted)
    }
    @Composable private fun ScannerScreen() {
        Eyebrow("SCAN & REVIEW");Heading("Bring the code\ninto view.",30)
        Label("Scan an offer to receive a credential, or a request to share one. You will review the details first.",14,Palette.Muted)
        Box(Modifier.fillMaxWidth().height(330.dp).clip(RoundedCornerShape(18.dp)).background(Palette.Ink)) {
            QrScanner(this@MainActivity,this@MainActivity,Modifier.fillMaxSize(),onCode={ code -> scanning=false;receiveLink(code) },
                onError={ scanning=false;inform("The camera is unavailable. You can open a wallet link instead.",true) })
        }
        Action("Cancel scanning",Modifier.fillMaxWidth(),primary=false) { scanning=false }
    }
    @Composable private fun ConsentScreen(current:JSONObject) {
        val receive=receiving();val details=current.getJSONObject("details")
        Eyebrow(if(receive) "RECEIVE A CREDENTIAL" else "REVIEW BEFORE SHARING")
        Heading(if(receive) "Add this credential\nto your wallet?" else "You're in control\nof what you share.",29)
        Card {
            Icon(if(receive) "wallet" else "shield",Palette.Teal)
            Heading(if(receive) "Competency Proof" else "Credential request",20)
            if(receive) {
                KeyValue("Issuer",details.optString("issuerName").ifBlank { "Credential issuer" })
                KeyValue("For candidate",details.optString("candidateId"))
                Label("This offer is addressed to your signed-in account.",13,Palette.Muted)
            } else {
                Label("Requested by",12,Palette.Muted)
                Label("Company verification",17,weight=FontWeight.SemiBold)
                Label("Requested by PT Karya Instalasi Nusantara (demo).",13,Palette.Muted)
            }
        }
        if(!receive) {
            Heading("Choose your credential",19)
            val options=current.optJSONArray("choices") ?: JSONArray()
            for(index in 0 until options.length()) {
                val option=options.getJSONObject(index);val id=option.getString("credentialId")
                val item=credentials.find { it.id==id };val active=current.optString("credentialId")==id
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if(active) Palette.TealSoft else Palette.White)
                    .border(if(active) 2.dp else 1.dp,if(active) Palette.Teal else Palette.Line,RoundedCornerShape(12.dp))
                    .clickable(enabled=!busy) { if(!active) review(id) }.padding(18.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(if(active) "check" else "document",Palette.Teal)
                    Column(Modifier.weight(1f)) {
                        Label(item?.let { competency(it.claims.optString("competency_code")) } ?: option.optString("label","Competency Proof"),15,weight=FontWeight.SemiBold)
                        Label(item?.claims?.optString("competency_code") ?: "Review credential details",12,Palette.Muted)
                    }
                }
            }
            credentials.find { it.id==current.optString("credentialId") }?.let { item -> Card {
                Heading("Information shared",18)
                KeyValue("Candidate",item.claims.optString("candidate_id"));KeyValue("Competency",item.claims.optString("competency_code"))
                KeyValue("Assessment",item.claims.optString("assessment_result").replace('_',' '))
                Label("The company also receives the credential ID, issuer, validity dates and a proof that this credential belongs to your wallet.",12,Palette.Muted)
            } }
        }
        var remaining by remember(current) { mutableStateOf((current.optLong("expiresAt")-System.currentTimeMillis()/1000).coerceAtLeast(0)) }
        LaunchedEffect(current) { while(remaining>0) { delay(1000);remaining=(current.optLong("expiresAt")-System.currentTimeMillis()/1000).coerceAtLeast(0) } }
        Notice(if(remaining>0) "This review is available for ${remaining/60}:${(remaining%60).toString().padStart(2,'0')}. Approval applies to this request only."
            else "This review has expired. Scan a fresh code to continue.",remaining==0L)
        Action(if(receive) "Approve & add credential" else "Approve & share credential",Modifier.fillMaxWidth(),enabled=!busy && remaining>0,icon="check") { consent(current,receive) }
        Action("Decline request",Modifier.fillMaxWidth(),primary=false,enabled=!busy) { decline(current) }
    }
    private fun inform(text:String,error:Boolean=false) { notice=text;errorNotice=error }
    private fun ui(expected:Int,work:()->Unit) = runOnUiThread { if(expected==generation) work() }
    private fun submit(releaseBusy:Boolean=true,work:(Int)->Unit) {
        val expected=generation;busy=true
        network.execute {
            try { work(expected) } catch(e:Exception) {
                // Codes only: never log URLs, tokens, credential contents or exception messages.
                val diagnostic=if(e is WalletHttpFailure) "HTTP_${e.status}_"+e.code.takeIf { it.matches(Regex("[A-Z_]{1,64}")) }.orEmpty() else e.javaClass.simpleName
                android.util.Log.w("NusaCredStatus",diagnostic)
                ui(expected) {
                busy=false
                if(e is WalletHttpFailure && e.status==401) { generation++;store.clearSession();clearIdentity();inform("Your session ended or was revoked. Sign in again to continue.",true) }
                else {
                    if(e is WalletHttpFailure && e.status==410) { preview=null;store.clearOperation() }
                    inform(when {
                        e is WalletHttpFailure && e.code=="RECONCILE_REQUIRED" -> "We could not confirm the outcome. Check the request status before trying anything else."
                        e is WalletHttpFailure && e.status==410 -> "This request has expired. Ask for a fresh QR code."
                        e is WalletHttpFailure && e.status==404 -> "This request is unavailable for your account. Check the recipient or ask for a fresh code."
                        e is WalletHttpFailure && e.status==409 -> "This request already has an operation. Check its status or ask your certifier for help."
                        else -> "We could not complete the request. Check your connection and try checking its status."
                    },true)
                }
            } } finally { if(releaseBusy) ui(expected) { busy=false } }
        }
    }
    private fun begin() {
        store.clearSession();walletLocked=false;biometricEnabled=false;clearIdentity()
        submit(releaseBusy=false) { g ->
            val flow=obj("/auth/native/start","POST",JSONObject())
            check(flow.getString("issuer")==BuildConfig.OIDC_ISSUER && flow.getString("client_id")=="android-wallet-p2")
            ui(g) {
                ticket=flow.getString("ticket")
                AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(BuildConfig.OIDC_ISSUER)) { config,error -> ui(g) {
                    if(config==null || error!=null) { busy=false;ticket=null;inform("Sign-in is temporarily unavailable.",true) }
                    else {
                        val request=AuthorizationRequest.Builder(config,"android-wallet-p2",ResponseTypeValues.CODE,Uri.parse(BuildConfig.OIDC_REDIRECT_URI))
                            .setScope("openid profile email").setNonce(flow.getString("nonce")).setPrompt("login")
                            .setAdditionalParameters(mapOf("max_age" to "0")).build()
                        check(request.codeVerifier!=null && request.codeVerifierChallengeMethod=="S256")
                        busy=true
                        try { login.launch(authorization.getAuthorizationRequestIntent(request)) }
                        catch(_:Exception) { busy=false;ticket=null;inform("A browser is required to sign in.",true) }
                    }
                } }
            }
        }
    }
    private fun account(session:String,g:Int) {
        val user=obj("/auth/me","GET",session=session)
        val identity=obj("/api/v1/me","GET",session=session)
        obj("/api/v1/wallet/provision","POST",JSONObject(),session)
        val items=loadCredentials(session)
        ui(g) {
            store.save(session,user.getLong("expires_at"));expiresAt=user.getLong("expires_at")
            signedIn=true;email=user.getString("email");recipient=identity.getString("accountId");credentials=items
            inform(if(pendingLink!=null) "Your request is ready to review." else "")
        }
        restoreOperation(g,session)
    }
    private fun loadCredentials(session:String):List<CredentialItem> {
        val array=request("/api/v1/wallet/credentials","GET",session=session) as JSONArray
        return (0 until minOf(array.length(),100)).map { index ->
            val id=array.getJSONObject(index).getString("id")
            val detail=obj("/api/v1/wallet/credentials/$id","GET",session=session)
            CredentialItem(id,detail.getJSONObject("credential").getJSONObject("credentialData"))
        }
    }
    private fun refresh() { val token=store.load() ?: return;submit { g -> val items=loadCredentials(token);ui(g) { credentials=items;inform("Wallet is up to date.") } } }
    private fun receiving() = pendingLink?.let { Uri.parse(it).path=="/receive" } == true
    private fun reference():String {
        val uri=Uri.parse(store.context() ?: error("Missing request"))
        return requireNotNull(uri.getQueryParameter(if(uri.path=="/receive") "offer" else "request_url"))
    }
    private fun review(credentialId:String?=null) {
        val token=store.load() ?: return
        val link=store.context() ?: return
        submit { g ->
            check(store.context()==link)
            val body=JSONObject().put("reference",reference()).put("credentialId",credentialId ?: JSONObject.NULL)
            val current=obj("/api/v1/exchange-contexts","POST",body,token)
            ui(g) {
                store.saveOperation(current.getString("id"),UUID.randomUUID().toString(),receiving())
                preview=current;notice="";uncertain=false
            }
        }
    }
    private fun consent(current:JSONObject,receive:Boolean) {
        val token=store.load() ?: return
        val operation=store.operation() ?: return
        submit { g ->
            check(current.getString("id")==operation.getString("id"))
            val details=current.getJSONObject("details")
            val body=JSONObject().put("previewHash",current.getString("previewHash")).put("consent",true)
                .put("credentialId",if(receive) JSONObject.NULL else current.getString("credentialId"))
                .put("disclosures",if(receive) JSONArray() else details.optJSONArray("disclosures") ?: JSONArray())
            val state=obj("/api/v1/exchange-contexts/${operation.getString("id")}/${if(receive) "accept" else "present"}","POST",body,token,operation.getString("key"))
            finishOperation(state,g,token,receive)
        }
    }
    private fun restoreOperation(g:Int,session:String?=store.load()) {
        val token=session ?: return;val saved=store.operation() ?: return
        val state=try { obj("/api/v1/operations/${saved.getString("id")}","GET",session=token) } catch(e:WalletHttpFailure) {
            if(e.status==404) { ui(g) { store.clearOperation() };return } else throw e
        }
        if(state.optString("state")=="PENDING") {
            val restored=obj("/api/v1/exchange-contexts/${saved.getString("id")}","GET",session=token)
            ui(g) { preview=restored;uncertain=false }
        } else finishOperation(state,g,token,saved.getBoolean("receiving"))
    }
    private fun finishOperation(state:JSONObject,g:Int,token:String,receive:Boolean) {
        val status=state.getString("state")
        val items=if(status=="COMPLETED" && receive) loadCredentials(token) else null
        ui(g) {
            preview=null
            if(status=="COMPLETED") {
                items?.let { credentials=it };store.clearOperation();store.clearContext();pendingLink=null;uncertain=false
                inform(if(receive) "Credential added. It's ready in your wallet." else "Credential shared. Check the company page for its decision.")
            } else if(status=="CANCELLED") {
                store.clearOperation();store.clearContext();pendingLink=null;inform("The request was not completed. ${if(state.optString("reason")=="EXPIRED") "It expired before approval." else "No further action was taken."}",true)
            } else { uncertain=true;inform("The outcome needs confirmation. This request will not be sent again automatically.",true) }
        }
    }
    private fun decline(current:JSONObject) {
        val token=store.load() ?: return
        submit { g -> obj("/api/v1/exchange-contexts/${current.getString("id")}/decline","POST",JSONObject(),token);ui(g) { dismiss();inform("Request declined. Nothing was shared.") } }
    }
    private fun dismiss() { store.clearContext();store.clearOperation();pendingLink=null;preview=null;uncertain=false }
    private fun clearIdentity() { signedIn=false;expiresAt=0;credentials=emptyList();selected=null;preview=null;email="";recipient="";busy=false;uncertain=false }
    private fun signOut() {
        val token=store.renewalSession();val refresh=store.refreshToken();generation++;store.clear();clearIdentity();walletLocked=false;biometricEnabled=false;pendingLink=null;page="wallet";ticket=null;inform("You are signed out. Your credentials remain safely stored.")
        if(token!=null) network.execute { try { obj("/auth/logout","POST",JSONObject(),token) } catch(_:Exception) { /* Local access is already cleared. */ } }
        if(refresh!=null) network.execute { revokeRefresh(refresh) }
    }
    private fun revokeRefresh(refresh:String) {
        try {
            val connection=URL(BuildConfig.OIDC_ISSUER+"/protocol/openid-connect/revoke").openConnection() as HttpURLConnection
            connection.instanceFollowRedirects=false;connection.connectTimeout=10000;connection.readTimeout=10000
            connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(("client_id=android-wallet-p2&token_type_hint=refresh_token&token="+java.net.URLEncoder.encode(refresh,"UTF-8")).toByteArray()) }
            connection.inputStream.use { it.read(ByteArray(1024)) };connection.disconnect()
        } catch(_:Exception) { /* Offline logout always erases local access; server expiry remains bounded. */ }
    }
    private fun startScan() {
        notice="";selected=null
        if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) scanning=true
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent);consumeIntent(intent) }
    private fun consumeIntent(incoming:Intent) { val value=incoming.data?.toString();setIntent(Intent(incoming).setData(null));value?.let(::receiveLink) }
    private fun receiveLink(value:String):Boolean {
        if(store.operation()!=null) { inform("Finish or decline the current request before opening another code.",true);return false }
        return try {
        val uri=Uri.parse(value);val parameter=when(uri.path) { "/receive" -> "offer";"/present" -> "request_url";else -> error("Unsupported link") }
        check(value.length<=8192 && uri.scheme=="https" && uri.authority==Uri.parse(BuildConfig.WALLET_ORIGIN).authority && uri.fragment==null &&
            uri.queryParameterNames==setOf(parameter) && uri.getQueryParameters(parameter).size==1 && !uri.getQueryParameter(parameter).isNullOrBlank())
        store.saveContext(value);store.clearOperation();pendingLink=value;preview=null;selected=null;uncertain=false;page="wallet"
        inform(if(signedIn) "Request received. Review the details before you approve." else "Request saved. Sign in to review it.");true
    } catch(_:Exception) { inform("This code is not a supported NusaCred wallet link.",true);false }
    }
    override fun onResume() {
        super.onResume()
        if(::store.isInitialized) {
            if(store.context()!=pendingLink) { pendingLink=store.context();preview=null }
            if(signedIn && store.load()==null) { generation++;clearIdentity();inform("Your session has ended. Sign in again to continue.") }
        }
    }
    private fun obj(path:String,method:String,body:JSONObject?=null,session:String?=null,key:String?=null):JSONObject = request(path,method,body,session,key) as JSONObject
    private fun request(path:String,method:String,body:JSONObject?=null,session:String?=null,key:String?=null):Any {
        check(path.startsWith("/api/v1/") || path in setOf("/auth/native/start","/auth/native/session","/auth/me","/auth/logout"))
        check(!path.contains("..") && !path.contains('?') && !path.contains('#'))
        val connection=URL(BuildConfig.WALLET_ORIGIN+path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod=method;connection.instanceFollowRedirects=false;connection.connectTimeout=10_000;connection.readTimeout=20_000
            session?.let { connection.setRequestProperty("Authorization","Bearer $it") };key?.let { connection.setRequestProperty("Idempotency-Key",it) }
            body?.let {
                val bytes=it.toString().toByteArray(Charsets.UTF_8);connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json")
                connection.setFixedLengthStreamingMode(bytes.size);connection.outputStream.use { stream -> stream.write(bytes) }
            }
            val status=connection.responseCode
            if(status !in 200..299) {
                val raw=connection.errorStream?.use { readBounded(it,32768) }
                val code=try { raw?.let { JSONObject(String(it,Charsets.UTF_8)).optString("code") } ?: "" } catch(_:Exception) { "" }
                throw WalletHttpFailure(status,code)
            }
            val bytes=connection.inputStream.use { readBounded(it,2_097_152) };check(bytes.size<=2_097_152)
            return JSONTokener(String(bytes,Charsets.UTF_8)).nextValue()
        } finally { connection.disconnect() }
    }
    private fun readBounded(stream:java.io.InputStream,limit:Int):ByteArray {
        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) { val count=stream.read(buffer,0,minOf(buffer.size,limit+1-output.size()));if(count<0) break
            output.write(buffer,0,count);check(output.size()<=limit) }
        return output.toByteArray()
    }
    private fun competency(code:String)=when(code) { "DEMO-ELEC-001" -> "Electrical installation";"DEMO-OTHER-001" -> "A different competency";else -> code.replace('_',' ').ifBlank { "Competency credential" } }
    private fun date(seconds:Long):String = if(seconds<=0) "Not specified" else DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(seconds))
    override fun onDestroy() { generation++;authorization.dispose();network.shutdownNow();super.onDestroy() }
}
