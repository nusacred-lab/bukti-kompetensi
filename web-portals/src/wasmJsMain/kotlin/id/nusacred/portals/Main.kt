@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,kotlin.js.ExperimentalWasmJsInterop::class)
package id.nusacred.portals

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
import androidx.compose.ui.window.ComposeViewport
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import id.nusacred.ui.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.*

fun main() { ComposeViewport(document.getElementById("portal")!!) { Portal() } }

private class PortalState {
    var service by mutableStateOf(when(window.location.hostname) {
        "lsp-nusantara.demo.inoerawan.id", "issuer-p2.demo.inoerawan.id" -> "issuer"
        "karya-instalasi.demo.inoerawan.id", "verifier-p2.demo.inoerawan.id" -> "company"
        else -> "custodian"
    })
    var session by mutableStateOf<Data?>(null)
    var loading by mutableStateOf(true)
    var time by mutableStateOf(now())
    var busy by mutableStateOf(false)
    var problem by mutableStateOf("")
    var success by mutableStateOf("")
    var page by mutableStateOf("overview")
    var publicView by mutableStateOf(false)
    var jobs by mutableStateOf<List<Data>>(emptyList())
    var assessments by mutableStateOf<List<Data>>(emptyList())
    var results by mutableStateOf<List<Data>>(emptyList())
    var trust by mutableStateOf<List<Data>>(emptyList())
    var trustRevision by mutableStateOf(0)
    var status by mutableStateOf<Data?>(null)
    var verification by mutableStateOf<Data?>(null)
    var qr by mutableStateOf<Data?>(null)
    var dialog by mutableStateOf("")
    var offer by mutableStateOf<Data?>(null)
    var viewerCsrf:String?=null
    var candidate by mutableStateOf("")
    var account by mutableStateOf("")
    var competency by mutableStateOf("DEMO-ELEC-001")
    var assessment by mutableStateOf("competent")
    var jobTitle by mutableStateOf("")
    var jobCode by mutableStateOf("DEMO-ELEC-001")
    var issuerIdentity by mutableStateOf("")
    val keys=mutableMapOf<String,String>()
    val csrf get()=session?.s("csrf")
    val isPublic get()=service=="company" && (session==null || publicView)
    fun key(action:String)=keys.getOrPut(action,::newKey)
    suspend fun refresh() {
        when(service) {
            "issuer" -> if(session!=null) assessments=api("/api/v1/assessments").list("items")
            "company" -> {
                jobs=api("/api/v1/jobs").list("items")
                if(session!=null && !publicView) {
                    results=api("/api/v1/verification-sessions").list("items")
                    val list=api("/api/v1/trusted-issuers");trust=list.list("items");trustRevision=list.n("revision").toInt()
                }
            }
            else -> if(session!=null) status=api("/api/v1/service-status")
        }
    }
}
@Composable private fun Portal() {
    val state=remember { PortalState() };val scope=rememberCoroutineScope()
    fun act(work:suspend ()->Unit) { if(state.busy) return;state.busy=true;state.problem="";state.success=""
        scope.launch { try { work() } catch(e:Exception) {
            if(e is PortalFailure && e.status==401 && !state.isPublic) { state.session=null;state.problem="Your session has ended. Sign in again to continue." }
            else state.problem=when {
                e is PortalFailure && e.status==400 -> "Check the form fields before trying again. No new record was saved."
                e is PortalFailure && e.status==403 -> "Your account does not have access to this workspace."
                e is PortalFailure && e.status==410 -> "This request has expired. Create a fresh request to continue."
                e is PortalFailure && e.status==409 -> "This operation needs a status check. Refresh the record before trying again."
                e is PortalFailure && e.status==404 -> "This record is unavailable for your account. Check the recipient or create a fresh request."
                else -> "We couldn't complete the request. Check your connection, then refresh its status."
            }
        } finally { state.busy=false } }
    }
    LaunchedEffect(Unit) { while(true) { delay(1000);state.time=now() } }
    LaunchedEffect(Unit) {
        try {
            state.service=api("/api/v1/portal-config").s("service")
            state.session=try { api("/api/v1/portal-session") } catch(e:PortalFailure) { if(e.status==401) null else throw e }
            state.refresh()
            if(state.isPublic && currentId().isNotEmpty()) {
                try { state.verification=api("/api/v1/verification-sessions/"+currentId()) } catch(_:Exception) { saveId("") }
            }
        } catch(_:Exception) { state.problem="This workspace is temporarily unavailable. Refresh the page to try again." }
        finally { state.loading=false }
    }
    LaunchedEffect(state.session?.n("expiresAt")) {
        val expires=state.session?.n("expiresAt") ?: return@LaunchedEffect
        delay(((expires-now()).coerceAtLeast(0.0)*1000).toLong())
        state.session=null;state.assessments=emptyList();state.results=emptyList();state.status=null
        state.dialog="";state.qr=null;state.offer=null;state.keys.clear();state.verification=null;saveId("")
        state.problem="Your session has ended. Sign in again to continue."
    }
    LaunchedEffect(state.verification?.s("id")) {
        val id=state.verification?.s("id") ?: return@LaunchedEffect
        while(state.verification?.b("final")==false) {
            delay(2500)
            try { state.verification=api("/api/v1/verification-sessions/$id") }
            catch(_:Exception) { state.problem="We couldn't check the result yet. Your request has not been resubmitted.";delay(5000) }
        }
        if(state.service=="company" && state.session!=null && !state.publicView) try { state.refresh() } catch(_:Exception) {}
    }
    val entity=Entities.forService(state.service)
    LaunchedEffect(entity) {
        document.title="${entity.brand} — ${entity.role}"
        document.getElementById("portal")?.setAttribute("aria-label",entity.legalName+" — "+entity.role)
    }
    EntityTheme(entity) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Paper)) {
        val availableHeight=maxHeight;val wide=maxWidth>900.dp;val contentPadding=if(wide) 36.dp else 20.dp
        Row(Modifier.fillMaxSize()) {
            if(wide && !state.isPublic) Sidebar(state)
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth().background(Palette.White).padding(horizontal=contentPadding,vertical=18.dp),
                    horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    if(!wide || state.isPublic) Brand() else Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Eyebrow(entity.role,Palette.Teal);Label(entity.legalName,13,Palette.Ink,FontWeight.Medium)
                    }
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                        if(wide) Pill("Synthetic demo")
                        if(state.session!=null) {
                            if(wide) Label(state.session!!.s("email"),12,Palette.Muted)
                            Action("Sign out",primary=false,enabled=!state.busy,icon="logout") { act {
                                api("/auth/logout","POST","{}",state.csrf);state.session=null;state.dialog="";state.qr=null;state.offer=null
                                state.verification=null;saveId("");state.results=emptyList();state.assessments=emptyList();state.status=null;state.success="You are signed out."
                            } }
                        } else Action(if(state.isPublic) "Company sign in" else "Sign in",icon="arrow") { navigate("/auth/start") }
                    }
                }
                Rule()
                if(!wide && state.session!=null && !state.isPublic) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(Palette.White).padding(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    tabs(state.service).forEach { (id,label,icon) -> Action(label,primary=state.page==id,icon=icon) { state.page=id } }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(contentPadding),verticalArrangement=Arrangement.spacedBy(24.dp)) {
                    OrganizationIdentity(entity)
                    Rule()
                    if(state.problem.isNotEmpty()) Notice(state.problem,true)
                    if(state.success.isNotEmpty()) Notice(state.success)
                    when {
                        state.loading -> Card(Modifier.fillMaxWidth()) { EmptyState("Opening your workspace","Preparing a secure connection…","shield") }
                        state.isPublic -> PublicJobs(state,wide,::act)
                        state.session==null -> Welcome(state,wide)
                        state.service=="issuer" -> IssuerWorkspace(state,wide,::act)
                        state.service=="company" -> CompanyWorkspace(state,wide,::act)
                        else -> CustodianWorkspace(state,wide,::act)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Label(entity.brand+" · Independently operated",11,Palette.Muted)
                        if(wide) Label("Synthetic demonstration · 2026",11,Palette.Muted)
                    }
                }
            }
        }
        if(state.dialog.isNotEmpty()) Popup(alignment=Alignment.Center,
            onDismissRequest={ if(!state.busy) { state.dialog="";state.qr=null } },
            properties=PopupProperties(focusable=true)) { Box(Modifier.fillMaxSize().background(Color(0xAA102923)),contentAlignment=Alignment.Center) {
            Column(Modifier.padding(if(wide) 28.dp else 12.dp).widthIn(max=if(state.dialog=="assessment") 620.dp else 500.dp)
                .fillMaxWidth().heightIn(max=availableHeight-24.dp).clip(RoundedCornerShape(18.dp)).background(Palette.Paper)
                .verticalScroll(rememberScrollState()).padding(26.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    Eyebrow(if(state.dialog=="assessment") "NEW ASSESSMENT" else if(state.dialog=="offer") "CREDENTIAL OFFER" else "VERIFICATION REQUEST")
                    Action("Close",primary=false,icon="close") { state.dialog="";state.qr=null }
                }
                OrganizationIdentity(entity,compact=true)
                Rule()
                when(state.dialog) {
                    "assessment" -> AssessmentForm(state,::act)
                    else -> QrPanel(state,::act)
                }
            }
        }
        }
        if(state.busy) Box(Modifier.align(Alignment.BottomCenter).padding(20.dp).background(Palette.Ink,RoundedCornerShape(10.dp)).padding(horizontal=20.dp,vertical=12.dp)) {
            Label("Working securely…",13,Color.White)
        }
    }
    }
}
private fun workspace(service:String)=when(service) { "issuer" -> "Certification workspace";"company" -> "Company workspace";else -> "Custodian workspace" }
private fun tabs(service:String)=when(service) {
    "issuer" -> listOf(Triple("overview","Assessments","document"),Triple("account","Workspace","shield"))
    "company" -> listOf(Triple("overview","Overview","home"),Triple("jobs","Job opening","briefcase"),Triple("results","Verifications","document"),Triple("trust","Trusted issuers","shield"))
    else -> listOf(Triple("overview","Service overview","home"),Triple("recovery","Recovery & access","shield"))
}
@Composable private fun Sidebar(s:PortalState) {
    val entity=Entities.forService(s.service)
    Column(Modifier.width(252.dp).fillMaxHeight().background(entity.sidebar).padding(24.dp),verticalArrangement=Arrangement.spacedBy(28.dp)) {
        Brand(light=true);Spacer(Modifier.height(12.dp));Eyebrow("WORKSPACE",Color.White.copy(alpha=0.72f))
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) { tabs(s.service).forEach { (id,label,icon) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(if(s.page==id) Color.White.copy(alpha=0.16f) else Color.Transparent)
                .clickable { s.page=id }.padding(horizontal=14.dp,vertical=14.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(icon,if(s.page==id) Color.White else Color.White.copy(alpha=0.72f),Modifier.size(20.dp));Label(label,13,if(s.page==id) Color.White else Color.White.copy(alpha=0.72f),FontWeight.Medium)
            }
        } }
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha=0.08f),RoundedCornerShape(12.dp)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Icon(entity.icon,Color.White.copy(alpha=0.85f));Label(entity.purpose,15,Color.White,FontWeight.SemiBold)
            Label(entity.legalName,12,Color.White.copy(alpha=0.72f))
        }
        Label(entity.role+" / Synthetic demo",10,Color.White.copy(alpha=0.72f))
    }
}
@Composable private fun PageHeading(eyebrow:String,title:String,description:String,action:(@Composable ()->Unit)?=null) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Eyebrow(eyebrow);Heading(title,32);Label(description,15,Palette.Muted);if(action!=null) { Spacer(Modifier.height(4.dp));action() }
    }
}
@Composable private fun Stat(label:String,value:String,caption:String,icon:String,modifier:Modifier=Modifier) {
    Card(modifier) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Label(label,12,Palette.Muted);Icon(icon,Palette.Teal,Modifier.size(20.dp)) }
        Label(value,32,weight=FontWeight.SemiBold);Label(caption,11,Palette.Muted)
    }
}
@Composable private fun Welcome(s:PortalState,wide:Boolean) {
    val issuer=s.service=="issuer"
    Spacer(Modifier.height(12.dp))
    PageHeading("${if(issuer) "CERTIFICATION" else "CUSTODIAN"} WORKSPACE",if(issuer) "Recognize skill.\nIssue with confidence." else "Protect access.\nPreserve confidence.",
        if(issuer) "A considered workspace for assessments, credential offers and their progress." else "A clear view of wallet readiness, account recovery and operations that need attention.")
    Card(Modifier.fillMaxWidth(),Palette.White) {
        Row(horizontalArrangement=Arrangement.spacedBy(18.dp)) {
            Icon(if(issuer) "document" else "shield",Palette.Teal,Modifier.size(40.dp))
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Heading("Your workspace is ready",22)
                Label("Sign in with your assigned ${if(issuer) "certification" else "custodian"} account to continue.",14,Palette.Muted)
                Action("Sign in securely",icon="arrow") { navigate("/auth/start") }
            }
        }
    }
    val messages=if(issuer) listOf("01  Record the assessment","02  Address the offer","03  Track acceptance") else listOf("01  Monitor wallet readiness","02  Review recovery status","03  Resolve uncertain operations")
    if(wide) Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) { messages.forEach { Card(Modifier.weight(1f)) { Label(it,14,weight=FontWeight.Medium) } } }
    else messages.forEach { Label(it,14,Palette.Muted) }
}
@Composable private fun IssuerWorkspace(s:PortalState,wide:Boolean,act:(suspend ()->Unit)->Unit) {
    if(s.page=="account") { WorkspaceAccount(s);return }
    PageHeading("CERTIFICATION WORKSPACE","Give good work its proof.","Record an assessment, address a credential offer, and follow its progress.") {
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Action("New assessment",enabled=!s.busy,icon="plus") { s.dialog="assessment" }
            Action("Refresh",primary=false,enabled=!s.busy,icon="refresh") { act { s.refresh() } }
        }
    }
    if(wide) Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Stat("Assessments",s.assessments.size.toString(),"Recorded in your organization","document",Modifier.weight(1f))
        Stat("Active offer links",s.assessments.count { it.s("offerState")=="COMPLETED" && it.n("offerExpiresAt")>s.time }.toString(),"Within their expiry window","wallet",Modifier.weight(1f))
        Stat("Needs review",s.assessments.count { it.s("offerState")=="RECONCILE_REQUIRED" }.toString(),"Check before issuing again","clock",Modifier.weight(1f))
    }
    Card(Modifier.fillMaxWidth()) {
        Heading("Assessment records",20);Label("Only records from your assigned certification organization appear here.",12,Palette.Muted)
        if(s.assessments.isEmpty()) EmptyState("Start with an assessment","The candidate's recipient code makes sure the offer reaches the right account.")
        s.assessments.forEachIndexed { index,item ->
            if(index>0) Rule()
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Label(item.s("candidateId"),16,weight=FontWeight.SemiBold);Label(item.s("competencyCode"),12,Palette.Muted) }
                    Pill(if(item.s("offerState")=="RECONCILE_REQUIRED") "Needs review" else if(item.s("offerState")=="COMPLETED") "Offer created" else "Recorded",
                        if(item.s("offerState")=="RECONCILE_REQUIRED") "warning" else "success")
                }
                Label("Assessment: "+item.s("assessmentResult").replace('_',' '),12,Palette.Muted)
                when {
                    item.s("offerState")=="RECONCILE_REQUIRED" -> Label("The issuance outcome needs confirmation. Do not create a replacement blindly.",12,Palette.Amber)
                    item.s("offerId").isNotEmpty() && item.n("offerExpiresAt")>s.time -> Action("View offer",primary=false,enabled=!s.busy,icon="scan") { act {
                        s.qr=api("/api/v1/offers/${item.s("offerId")}/qr");s.offer=item;s.dialog="offer"
                    } }
                    else -> Action(if(item.s("offerId").isEmpty()) "Create credential offer" else "Create fresh offer",primary=false,enabled=!s.busy,icon="plus") { act {
                        val id=item.s("id");val result=api("/api/v1/assessments/$id/offers","POST","{}",s.csrf,s.key("offer:$id:${item.s("offerId")}"))
                        s.refresh()
                        if(result.s("state")=="COMPLETED") { s.qr=api("/api/v1/offers/${result.s("id")}/qr");s.offer=s.assessments.find { it.s("id")==id };s.dialog="offer" }
                        else s.problem="This offer needs a status check. It will not be issued again automatically."
                    } }
                }
            }
        }
    }
}
@Composable private fun AssessmentForm(s:PortalState,act:(suspend ()->Unit)->Unit) {
    Heading("A clear record.\nThe right recipient.",28)
    Label("Use the recipient code from the candidate's wallet account. The offer will be bound to that account.",14,Palette.Muted)
    Field("Candidate ID",s.candidate,{ s.candidate=it },hint="e.g. candidate-001")
    Field("Recipient code",s.account,{ s.account=it },hint="Paste the code shared by the candidate")
    Field("Competency code",s.competency,{ s.competency=it })
    Label("Assessment result",13,weight=FontWeight.Medium)
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Action("Competent",primary=s.assessment=="competent",enabled=!s.busy) { s.assessment="competent" }
        Action("Not yet competent",primary=s.assessment=="not_competent",enabled=!s.busy) { s.assessment="not_competent" }
    }
    Notice("Saving an assessment does not send a credential. You create and share its offer in the next step.")
    Action("Save assessment",Modifier.fillMaxWidth(),enabled=!s.busy && s.candidate.isNotBlank() && s.account.isNotBlank() && s.competency.isNotBlank() && s.assessment.isNotBlank(),icon="check") { act {
        val payload=body("candidateAccountId" to s.account.trim(),"candidateId" to s.candidate.trim(),"competencyCode" to s.competency.trim(),"assessmentResult" to s.assessment.trim())
        api("/api/v1/assessments","POST",payload,s.csrf,s.key("assessment:$payload"));s.refresh();s.dialog="";s.success="Assessment saved. You can now create its credential offer."
    } }
}
@Composable private fun PublicJobs(s:PortalState,wide:Boolean,act:(suspend ()->Unit)->Unit) {
    Spacer(Modifier.height(if(wide) 12.dp else 0.dp))
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Eyebrow("OPPORTUNITY, BACKED BY PROOF")
            Heading("Your skills.\nA stronger next step.",if(wide) 48 else 34)
            Label("Let your competency speak for itself. Share a credential from your wallet and see whether it meets the role's requirements.",16,Palette.Muted)
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon("shield",Palette.Teal);Label("You review and approve every request.",13,Palette.Muted)
            }
        }
        if(wide) Card(Modifier.width(300.dp),Palette.Ink) {
            Icon("briefcase",Color.White,Modifier.size(32.dp));Spacer(Modifier.height(10.dp))
            Label("From a trusted record\nto your next opportunity.",25,Color.White,FontWeight.SemiBold)
            Label("01  Open the request\n02  Review in your wallet\n03  See the result",13,Color(0xFFBED4C8))
        }
    }
    Spacer(Modifier.height(4.dp));Heading("Open opportunity",23)
    if(s.jobs.isEmpty()) Card(Modifier.fillMaxWidth()) { EmptyState("No opening is published yet","Check back once the company has saved its role requirements.","briefcase") }
    s.jobs.forEach { job -> Card(Modifier.fillMaxWidth()) {
        Pill("Competency-based application","success")
        Heading(job.s("title"),26)
        Label("A credential-backed first step. Your information is shared only after you approve in your wallet.",14,Palette.Muted)
        Rule()
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) { KeyValue("Required competency",job.s("competencyCode")) }
            Column(Modifier.weight(1f)) { KeyValue("Required assessment",job.s("assessmentResult").replaceFirstChar { it.uppercase() }) }
        }
        Action("Check my competency",enabled=!s.busy,icon="arrow") { act {
            if(s.session==null) s.viewerCsrf=api("/api/v1/browser-session").s("csrf")
            val result=api("/api/v1/jobs/${job.s("id")}/verification-sessions","POST","{}",s.csrf ?: s.viewerCsrf,newKey())
            s.verification=result;saveId(result.s("id"));s.qr=api("/api/v1/verification-sessions/${result.s("id")}/qr");s.dialog="verification"
        } }
    } }
    s.verification?.let { result -> DecisionCard(result) {
        if(!result.b("final")) Action("Open wallet request",primary=false,enabled=!s.busy,icon="scan") { act {
            s.qr=api("/api/v1/verification-sessions/${result.s("id")}/qr");s.dialog="verification"
        } }
    } }
    Notice("Your result belongs to this browser session. Other visitors cannot browse your result or the company's verification records.")
    if(s.session!=null) Action("Back to company workspace",primary=false) { s.publicView=false }
}
@Composable private fun CompanyWorkspace(s:PortalState,wide:Boolean,act:(suspend ()->Unit)->Unit) {
    when(s.page) {
        "jobs" -> {
            PageHeading("JOB OPENING","Make the requirement clear.","One role, a defined competency, and a transparent assessment requirement.")
            Card(Modifier.fillMaxWidth()) {
                val existing=s.jobs.firstOrNull()
                LaunchedEffect(existing?.s("id")) { if(s.jobTitle.isEmpty() && existing!=null) { s.jobTitle=existing.s("title");s.jobCode=existing.s("competencyCode") } }
                Heading("Role requirements",20)
                Field("Role title",s.jobTitle,{ s.jobTitle=it },hint="Electrical installation technician")
                Field("Required competency code",s.jobCode,{ s.jobCode=it })
                KeyValue("Assessment requirement","Competent")
                Action("Save & publish opening",enabled=!s.busy && s.jobTitle.isNotBlank() && s.jobCode.isNotBlank(),icon="check") { act {
                    val payload=body("title" to s.jobTitle.trim(),"competencyCode" to s.jobCode.trim(),"assessmentResult" to "competent")
                    api("/api/v1/jobs/electrician","PUT",payload,s.csrf,s.key("job:$payload"));s.refresh();s.success="Opening saved. Its requirements are now available on the public page."
                } }
            }
            Action("View public opening",primary=false,icon="arrow") { s.publicView=true }
        }
        "trust" -> {
            PageHeading("TRUST POLICY","Choose whose proof you accept.","Updates apply to new decisions. Previously finalized results keep their original policy record.")
            Card(Modifier.fillMaxWidth()) {
                Heading("Add an issuer",20);Field("Verified issuer identity",s.issuerIdentity,{ s.issuerIdentity=it },hint="Paste the issuer's public DID")
                Label("Use the cryptographic identity provided by the certifier. A display name alone does not establish trust.",12,Palette.Muted)
                Action("Add trusted issuer",enabled=!s.busy && s.issuerIdentity.isNotBlank(),icon="plus") { act {
                    val identity=s.issuerIdentity.trim();api("/api/v1/trusted-issuers","POST",body("identity" to identity),s.csrf,s.key("trust:$identity:${s.trustRevision}"))
                    s.refresh();s.issuerIdentity="";s.success="Issuer added. New decisions use the updated trust policy."
                } }
            }
            Card(Modifier.fillMaxWidth()) {
                Heading("Trusted issuers",20);Label("Policy revision ${s.trustRevision}",12,Palette.Muted)
                if(s.trust.isEmpty()) EmptyState("No trusted issuer yet","Add your certifier's verified identity before accepting its credentials.","shield")
                s.trust.forEach { issuer ->
                    Rule();Label(issuer.s("identity"),12,Palette.Muted)
                    Action("Remove issuer",primary=false,enabled=!s.busy) { act {
                        api("/api/v1/trusted-issuers/${issuer.s("id")}","DELETE",csrf=s.csrf,key=s.key("remove:${issuer.s("id")}:${s.trustRevision}"));s.refresh();s.success="Issuer removed. Existing final results are unchanged."
                    } }
                }
            }
        }
        "results" -> {
            PageHeading("VERIFICATION RECORDS","Every decision has a reason.","Results stay tied to their original opening, request and policy revision.") {
                Action("Refresh records",primary=false,enabled=!s.busy,icon="refresh") { act { s.refresh() } }
            }
            ResultsTable(s,act)
        }
        else -> {
            PageHeading("COMPANY WORKSPACE","A clearer view of competency.","Manage your opening, review credential checks, and keep your trust policy current.") {
                Action("View public opening",primary=false,icon="arrow") { s.publicView=true }
            }
            if(wide) Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Stat("Open role",s.jobs.size.toString(),"Published opportunity","briefcase",Modifier.weight(1f))
                Stat("Requirements met",s.results.count { it.s("decision")=="FULFILLED" }.toString(),"Finalized competency checks","check",Modifier.weight(1f))
                Stat("Trusted issuers",s.trust.size.toString(),"Policy revision ${s.trustRevision}","shield",Modifier.weight(1f))
            }
            if(s.jobs.isEmpty()) Card(Modifier.fillMaxWidth(),Palette.TealSoft) {
                Heading("Start with a clear role",22);Label("Save the opening and competency requirement before inviting candidates to verify.",14,Palette.Muted)
                Action("Set up job opening",icon="plus") { s.page="jobs" }
            }
            ResultsTable(s,act)
        }
    }
}
@Composable private fun ResultsTable(s:PortalState,act:(suspend ()->Unit)->Unit) {
    Card(Modifier.fillMaxWidth()) {
        Heading("Recent verifications",20)
        if(s.results.isEmpty()) EmptyState("Your first result will appear here","A candidate starts from the public opening and approves a request in their wallet.")
        s.results.forEachIndexed { index,result ->
            if(index>0) Rule()
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Label("Request ${result.s("id").take(8).uppercase()}",14,weight=FontWeight.SemiBold)
                    Label(reason(result),12,Palette.Muted)
                    Label(if(result.b("final")) "${date(result.n("evaluatedAt"))} · Trust revision ${result.n("trustRevision").toInt()}" else "Awaiting a completed check",11,Palette.Muted)
                }
                Action("Check result",primary=false,enabled=!s.busy) { act {
                    val fresh=api("/api/v1/verification-sessions/${result.s("id")}")
                    s.results=s.results.map { if(it.s("id")==fresh.s("id")) fresh else it }
                } }
            }
        }
    }
}
@Composable private fun CustodianWorkspace(s:PortalState,wide:Boolean,act:(suspend ()->Unit)->Unit) {
    val data=s.status
    PageHeading("CUSTODIAN WORKSPACE",if(s.page=="recovery") "Restore access. Preserve the proof." else "Keep every wallet in good hands.",
        "A limited operational view of registry readiness, recovery gates and work that needs confirmation.") {
        Action("Refresh status",primary=false,enabled=!s.busy,icon="refresh") { act { s.refresh() } }
    }
    if(wide) Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Stat("Wallets ready",(data?.n("walletsReady")?.toInt() ?: 0).toString(),"Persistent server wallets","wallet",Modifier.weight(1f))
        Stat("Recovery gates",(data?.n("recoveryPending")?.toInt() ?: 0).toString(),"Awaiting safe account access","shield",Modifier.weight(1f))
        Stat("Needs confirmation",(data?.n("operationsUncertain")?.toInt() ?: 0).toString(),"No automatic redispatch","clock",Modifier.weight(1f))
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Heading("Registry status",20);Pill(if(data?.b("registryAvailable")==true) "Available" else "Not checked",if(data?.b("registryAvailable")==true) "success" else "warning")
        }
        Label("${data?.n("accounts")?.toInt() ?: 0} account records · ${data?.n("walletsReady")?.toInt() ?: 0} wallets ready",14,Palette.Muted)
        Label("This view confirms registry access. It does not infer that every external service is healthy.",12,Palette.Muted)
    }
    Card(Modifier.fillMaxWidth()) {
        Icon("shield",Palette.Teal);Heading("Recovery without starting over",22)
        Label("Password recovery preserves the wallet and its existing credentials. Old sessions are revoked and pending approvals cannot be reused.",14,Palette.Muted)
        Label("Candidates use the recovery link on secure sign-in. Administrators monitor status here; signing keys and credential exports are not available in this workspace.",14,Palette.Muted)
        if((data?.n("operationsUncertain") ?: 0.0)>0) Notice("Some operation outcomes need confirmation. Follow the reconciliation runbook before taking any replacement action.",true)
    }
}
@Composable private fun WorkspaceAccount(s:PortalState) {
    PageHeading("WORKSPACE ACCESS","The right access. A clear boundary.","Your organization's records are available only through this signed-in workspace.")
    Card(Modifier.fillMaxWidth()) { KeyValue("Account",s.session?.s("email") ?: "");KeyValue("Workspace",workspace(s.service));KeyValue("Organization",s.session?.s("organizationId") ?: "") }
}
@Composable private fun QrPanel(s:PortalState,act:(suspend ()->Unit)->Unit) {
    val receiving=s.dialog=="offer"
    Heading(if(receiving) "Ready for the right recipient." else "Continue in your wallet.",28)
    Label(if(receiving) "The candidate scans this offer, reviews the details and approves adding the credential." else "Scan with NusaCred Wallet, or open the request on this phone. Nothing is shared until you approve.",14,Palette.Muted)
    s.qr?.let { qr ->
        Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) { QrCode(qr.strings("modules"),Modifier.size(320.dp)) }
        Action(if(receiving) "Open credential offer" else "Open in NusaCred Wallet",Modifier.fillMaxWidth(),enabled=!s.busy,icon="arrow") { navigate(qr.s("value")) }
        Action("Copy wallet link",Modifier.fillMaxWidth(),primary=false,enabled=!s.busy) { act { copyText(qr.s("value"));s.success="Wallet link copied." } }
    }
    if(receiving) { s.offer?.let { KeyValue("Candidate",it.s("candidateId"));KeyValue("Competency",it.s("competencyCode")) };Notice("Only the addressed account can accept this offer. If the code expires, create a fresh offer explicitly.") }
    else s.verification?.let { DecisionCard(it) }
}
private fun reason(r:Data)=when(r.s("reason")) {
    "VERIFIED" -> "The credential meets this role's requirements."
    "COMPETENCY_MISMATCH" -> "The credential is valid, but covers a different competency."
    "ASSESSMENT_NOT_COMPETENT" -> "The assessment does not meet the competent requirement."
    "UNTRUSTED_ISSUER" -> "The issuer is not on this company's current trust list."
    "INVALID_PROOF" -> "The credential proof could not be verified."
    "EXPIRED" -> "The request expired. Start a fresh check when ready."
    "VERIFIER_SESSION_MISSING" -> "The verification request is no longer available."
    "DEPENDENCY_UNAVAILABLE" -> "The verification service is temporarily unavailable."
    "RECONCILE_REQUIRED" -> "The request outcome needs confirmation before another attempt."
    else -> "Waiting for a credential approved in your wallet."
}
@Composable private fun DecisionCard(result:Data,action:(@Composable ()->Unit)?=null) {
    val decision=result.s("decision");val final=result.b("final")
    Card(Modifier.fillMaxWidth(),if(decision=="FULFILLED") Palette.TealSoft else Palette.White) {
        Pill(if(decision=="FULFILLED") "Requirements met" else if(decision=="NOT_FULFILLED") "Requirements not met" else if(final) "Check incomplete" else "Waiting for approval",
            if(decision=="FULFILLED") "success" else if(final) "warning" else "neutral")
        Label(reason(result),14,weight=FontWeight.Medium)
        if(final) Label("Final result · ${date(result.n("evaluatedAt"))} · Policy revision ${result.n("trustRevision").toInt()}",11,Palette.Muted)
        action?.invoke()
    }
}
