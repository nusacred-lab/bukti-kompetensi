package id.nusacred.contracts

object ApiContract {
    const val VERSION = "1.0.0"
    const val PREFIX = "/api/v1"
    const val BODY_LIMIT = 32768
    const val PAGE_LIMIT = 100
    const val CONTEXT_TTL_SECONDS = 300L
    const val SESSION_TTL_SECONDS = 1800L
}
enum class Role { CANDIDATE, ISSUER_ADMIN, ISSUER_OPERATOR, WALLET_ADMIN, COMPANY_ADMIN }
enum class ExchangeState { PREVIEWED, ACCEPTED, EXECUTING, COMPLETED, DECLINED, EXPIRED, RECONCILE_REQUIRED }
enum class OperationState { PENDING, DISPATCHED, COMPLETED, CANCELLED, RECONCILE_REQUIRED }
enum class Decision { FULFILLED, NOT_FULFILLED, INCOMPLETE }
enum class Reason { WAITING, VERIFIED, COMPETENCY_MISMATCH, ASSESSMENT_NOT_COMPETENT, UNTRUSTED_ISSUER,
    INVALID_PROOF, CANCELLED, EXPIRED, DEPENDENCY_UNAVAILABLE, VERIFIER_SESSION_MISSING, RECONCILE_REQUIRED }
data class ApiError(val code: String, val message: String, val correlationId: String, val retryable: Boolean)
data class Page<T>(val items: List<T>, val offset: Int, val limit: Int, val nextOffset: Int?)
data class JobInput(val title: String, val competencyCode: String, val assessmentResult: String = "competent")
data class AssessmentInput(val candidateAccountId: String, val candidateId: String, val competencyCode: String, val assessmentResult: String)
data class ExchangeInput(val reference: String)
data class ConsentInput(val previewHash: String, val consent: Boolean, val credentialId: String?, val disclosures: List<String>)
data class OperationView(val id: String, val state: OperationState, val reason: Reason?)
data class VerificationView(val id: String, val jobId: String, val decision: Decision, val reason: Reason,
    val final: Boolean, val evaluatedAt: Long?, val trustRevision: Long?)

fun ExchangeState.canTransitionTo(next: ExchangeState): Boolean = when (this) {
    ExchangeState.PREVIEWED -> next in setOf(ExchangeState.ACCEPTED, ExchangeState.DECLINED, ExchangeState.EXPIRED)
    ExchangeState.ACCEPTED -> next in setOf(ExchangeState.EXECUTING, ExchangeState.EXPIRED)
    ExchangeState.EXECUTING -> next in setOf(ExchangeState.COMPLETED, ExchangeState.RECONCILE_REQUIRED)
    ExchangeState.RECONCILE_REQUIRED -> next == ExchangeState.COMPLETED
    else -> false
}
