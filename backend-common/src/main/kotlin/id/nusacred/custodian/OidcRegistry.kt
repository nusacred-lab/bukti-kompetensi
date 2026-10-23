package id.nusacred.custodian

import java.time.Instant

/** Recovery-aware session boundary. Each service owns its storage. */
interface OidcRegistry {
    fun registerSession(issuer: String, subject: String, tokenHash: String, sid: String,
        authTime: Long, expires: Long, loginStarted: Instant? = null): Any
    fun authorize(tokenHash: String): Any
    /** Renewal may outlive an access token, but never revocation or account recovery. */
    fun authorizeRenewal(tokenHash: String): Any
    fun completeRenewal(previousHash:String,nextHash:String)
    fun accountId(tokenHash: String): String
    fun revoke(tokenHash: String)
}
