package id.nusacred.common

import id.nusacred.contracts.Role
import com.nimbusds.jose.util.JSONObjectUtils
import id.nusacred.custodian.*
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** Separate instance and database file per backend; no cross-service database access. */
class DomainStore(path: Path, val service: String, private val now: () -> Long = { Instant.now().epochSecond }, private val protocol: CredentialProtocol? = null, private val offerReferences: OfferReference? = null) : AutoCloseable {
    private val sql = SqlStore(path, listOf("""
        CREATE TABLE service_identity(name TEXT PRIMARY KEY);
        CREATE TABLE jobs(id TEXT PRIMARY KEY, organization TEXT NOT NULL, title TEXT NOT NULL, competency TEXT NOT NULL, assessment TEXT NOT NULL, revision INTEGER NOT NULL);
        CREATE TABLE assessments(id TEXT PRIMARY KEY, organization TEXT NOT NULL, recipient TEXT NOT NULL, candidate TEXT NOT NULL, competency TEXT NOT NULL, result TEXT NOT NULL);
        CREATE TABLE operations(id TEXT PRIMARY KEY, actor TEXT NOT NULL, organization TEXT NOT NULL, action TEXT NOT NULL, key_hash TEXT NOT NULL, payload_hash TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('PENDING','DISPATCHED','COMPLETED','RECONCILE_REQUIRED')), created INTEGER NOT NULL, UNIQUE(actor,organization,action,key_hash));
        CREATE TABLE issuance_offers(id TEXT PRIMARY KEY, assessment_id TEXT NOT NULL REFERENCES assessments(id), operation_id TEXT NOT NULL UNIQUE REFERENCES operations(id), upstream_reference TEXT, expires INTEGER NOT NULL);
        CREATE TABLE trust_revisions(organization TEXT PRIMARY KEY, revision INTEGER NOT NULL);
        CREATE TABLE trusted_issuers(id TEXT NOT NULL, organization TEXT NOT NULL, identity TEXT NOT NULL, PRIMARY KEY(id,organization), UNIQUE(organization,identity));
        CREATE TABLE verification_sessions(id TEXT PRIMARY KEY, job_id TEXT NOT NULL REFERENCES jobs(id), organization TEXT NOT NULL, owner_hash TEXT NOT NULL, job_revision INTEGER NOT NULL, competency TEXT NOT NULL, assessment TEXT NOT NULL, expires INTEGER NOT NULL, upstream_reference TEXT);
        CREATE TABLE verification_results(session_id TEXT PRIMARY KEY REFERENCES verification_sessions(id), decision TEXT NOT NULL CHECK(decision IN ('FULFILLED','NOT_FULFILLED','INCOMPLETE')), reason TEXT NOT NULL, evaluated_at INTEGER NOT NULL, trust_revision INTEGER NOT NULL, policy_version TEXT NOT NULL);
        CREATE TRIGGER immutable_result_update BEFORE UPDATE ON verification_results BEGIN SELECT RAISE(ABORT,'Immutable result'); END;
        CREATE TRIGGER immutable_result_delete BEFORE DELETE ON verification_results BEGIN SELECT RAISE(ABORT,'Immutable result'); END;
        CREATE TABLE audit(id TEXT PRIMARY KEY, actor TEXT NOT NULL, organization TEXT NOT NULL, action TEXT NOT NULL, resource TEXT NOT NULL, at INTEGER NOT NULL, outcome TEXT NOT NULL, correlation_id TEXT NOT NULL)
    """, """
        CREATE TABLE candidate_bindings(organization TEXT NOT NULL, candidate TEXT NOT NULL, recipient TEXT NOT NULL, PRIMARY KEY(organization,candidate), UNIQUE(organization,recipient));
        INSERT INTO candidate_bindings SELECT DISTINCT organization,candidate,recipient FROM assessments;
        ALTER TABLE issuance_offers ADD COLUMN offer_url TEXT;
        ALTER TABLE verification_sessions ADD COLUMN request_url TEXT
    """, """
        CREATE TABLE issuer_reviews(id TEXT PRIMARY KEY, actor TEXT NOT NULL, organization TEXT NOT NULL, profile_json TEXT NOT NULL, profile_hash TEXT NOT NULL, created INTEGER NOT NULL, expires INTEGER NOT NULL);
        CREATE TABLE issuer_approvals(id TEXT PRIMARY KEY, review_id TEXT NOT NULL REFERENCES issuer_reviews(id), actor TEXT NOT NULL, organization TEXT NOT NULL, identity TEXT NOT NULL, method TEXT NOT NULL, note TEXT NOT NULL, approved_at INTEGER NOT NULL);
        ALTER TABLE trusted_issuers ADD COLUMN approval_id TEXT REFERENCES issuer_approvals(id)
    """))
    init {
        require(service in setOf("issuer", "company"))
        sql.execute("INSERT OR IGNORE INTO service_identity VALUES(?)", service)
        require(sql.query("SELECT name FROM service_identity") { it.getString(1) } == listOf(service)) { "Wrong service database" }
        sql.execute("UPDATE operations SET state='RECONCILE_REQUIRED' WHERE state='DISPATCHED'")
    }
    private fun permit(p: Principal) {
        if (service == "issuer") p.requireRole(Role.ISSUER_ADMIN, Role.ISSUER_OPERATOR) else p.requireRole(Role.COMPANY_ADMIN)
        p.organization()
    }
    private fun audit(p: Principal, action: String, resource: String) = sql.execute("INSERT INTO audit VALUES(?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(), p.account, p.organization(), action, resource, now(), "SUCCESS",p.correlationId)
    private fun reserve(p: Principal, action: String, key: String, payload: String): Pair<String, Boolean> {
        require(key.matches(Regex("[A-Za-z0-9_-]{16,128}")))
        val old = sql.query("SELECT id,payload_hash FROM operations WHERE actor=? AND organization=? AND action=? AND key_hash=?",
            p.account, p.organization(), action, hash(key)) { it.getString(1) to it.getString(2) }.singleOrNull()
        if (old != null) {
            if (old.second != hash(payload)) reject(409, "IDEMPOTENCY_CONFLICT", "This key was already used for different input.")
            return old.first to false
        }
        val id = UUID.randomUUID().toString()
        sql.execute("INSERT INTO operations VALUES(?,?,?,?,?,?,'PENDING',?)", id, p.account, p.organization(), action, hash(key), hash(payload), now())
        return id to true
    }
    fun saveJob(p: Principal, id: String, title: String, competency: String, assessment: String, key: String): Map<String, Any?> = sql.transaction {
        check(service == "company"); permit(p)
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val existing = sql.query("SELECT organization FROM jobs WHERE id=?", id) { it.getString(1) }.singleOrNull()
        if (existing != null && existing != p.organization()) reject(404, "NOT_FOUND", "Job not found.")
        val (operation, fresh) = reserve(p, "job:$id", key, json(mapOf("title" to title, "competencyCode" to competency, "assessmentResult" to assessment)))
        if (fresh) {
            sql.execute("""INSERT INTO jobs VALUES(?,?,?,?,?,1) ON CONFLICT(id) DO UPDATE SET title=excluded.title,
                competency=excluded.competency,assessment=excluded.assessment,revision=jobs.revision+1""", id, p.organization(), title, competency, assessment)
            sql.execute("UPDATE operations SET state='COMPLETED' WHERE id=?", operation)
            audit(p,"JOB_SAVED",id)
        }
        mapOf("id" to id, "operationId" to operation)
    }
    fun job(id: String): Map<String, Any?> = sql.query("SELECT * FROM jobs WHERE id=?", id) {
        mapOf("id" to it.getString("id"), "title" to it.getString("title"), "competencyCode" to it.getString("competency"),
            "assessmentResult" to it.getString("assessment"), "revision" to it.getLong("revision"))
    }.singleOrNull() ?: reject(404,"NOT_FOUND","Job not found.")
    fun assessment(p: Principal, recipient: String, candidate: String, competency: String, result: String, key: String): Map<String, Any?> = sql.transaction {
        check(service == "issuer"); permit(p)
        val (id, fresh) = reserve(p,"assessment",key,json(mapOf("candidateAccountId" to recipient,"candidateId" to candidate,"competencyCode" to competency,"assessmentResult" to result)))
        if (fresh) {
            val bound=sql.query("SELECT recipient FROM candidate_bindings WHERE organization=? AND candidate=?",p.organization(),candidate) { it.getString(1) }.singleOrNull()
            val other=sql.query("SELECT candidate FROM candidate_bindings WHERE organization=? AND recipient=?",p.organization(),recipient) { it.getString(1) }.singleOrNull()
            if((bound != null && bound != recipient) || (other != null && other != candidate)) reject(409,"CANDIDATE_BINDING_CONFLICT","The candidate already has a different recipient binding.")
            sql.execute("INSERT OR IGNORE INTO candidate_bindings VALUES(?,?,?)",p.organization(),candidate,recipient)
            sql.execute("INSERT INTO assessments VALUES(?,?,?,?,?,?)",id,p.organization(),recipient,candidate,competency,result)
            sql.execute("UPDATE operations SET state='COMPLETED' WHERE id=?",id); audit(p,"ASSESSMENT_CREATED",id)
        }
        mapOf("id" to id, "operationId" to id)
    }
    private fun reserveOffer(p: Principal, assessment: String, key: String): Map<String, Any?> = sql.transaction {
        check(service == "issuer"); permit(p)
        if (sql.query("SELECT id FROM assessments WHERE id=? AND organization=?",assessment,p.organization()) { it.getString(1) }.isEmpty()) reject(404,"NOT_FOUND","Assessment not found.")
        val (id,fresh) = reserve(p,"offer:$assessment",key,assessment)
        if (fresh) { sql.execute("INSERT INTO issuance_offers(id,assessment_id,operation_id,upstream_reference,expires) VALUES(?,?,?,NULL,?)",id,assessment,id,now()+300); audit(p,"OFFER_RESERVED",id) }
        offer(p,id) + mapOf("operationId" to id)
    }
    fun offer(p: Principal, id: String): Map<String, Any?> {
        permit(p)
        return sql.query("""SELECT o.id,o.expires,p.state,o.offer_url FROM issuance_offers o JOIN assessments a ON a.id=o.assessment_id
            JOIN operations p ON p.id=o.operation_id WHERE o.id=? AND a.organization=?""",id,p.organization()) {
            mapOf("id" to it.getString(1),"expiresAt" to it.getLong(2),"state" to it.getString(3),"offerUrl" to it.getString(4))
        }.singleOrNull() ?: reject(404,"NOT_FOUND","Offer not found.")
    }
    fun trust(p: Principal, identity: String?, remove: String? = null, key: String = UUID.randomUUID().toString()): Map<String, Any?> = sql.transaction {
        check(service == "company"); permit(p)
        sql.execute("INSERT OR IGNORE INTO trust_revisions VALUES(?,0)",p.organization())
        val id = remove ?: hash(requireNotNull(identity))
        val (operation,fresh) = reserve(p,if(remove == null) "trust:add" else "trust:remove",key,identity ?: remove!!)
        val changed = if(!fresh) 0 else if (remove != null) sql.execute("DELETE FROM trusted_issuers WHERE id=? AND organization=?",id,p.organization())
            else sql.execute("INSERT OR IGNORE INTO trusted_issuers(id,organization,identity) VALUES(?,?,?)",id,p.organization(),identity)
        if (changed == 1) { sql.execute("UPDATE trust_revisions SET revision=revision+1 WHERE organization=?",p.organization()); audit(p,"TRUST_CHANGED",id) }
        sql.execute("UPDATE operations SET state='COMPLETED' WHERE id=?",operation)
        mapOf("id" to id,"revision" to revision(p.organization()))
    }
    private fun revision(org: String): Long = sql.query("SELECT revision FROM trust_revisions WHERE organization=?",org) { it.getLong(1) }.singleOrNull() ?: 0
    fun requireCompany(p: Principal) { check(service == "company"); permit(p) }
    fun reviewIssuer(p: Principal, profile: IssuerProfile, key: String): Map<String, Any?> = sql.transaction {
        requireCompany(p)
        val (id,fresh) = reserve(p,"issuer:review",key,profile.digest)
        if(fresh) {
            sql.execute("INSERT INTO issuer_reviews VALUES(?,?,?,?,?,?,?)",id,p.account,p.organization(),json(profile.publicData()),profile.digest,now(),now()+900)
            sql.execute("UPDATE operations SET state='COMPLETED' WHERE id=?",id)
            audit(p,"ISSUER_REVIEW_OPENED",id)
        }
        issuerReview(p,id)
    }
    fun issuerReview(p: Principal, id: String): Map<String, Any?> {
        requireCompany(p)
        return sql.query("SELECT profile_json,profile_hash,created,expires FROM issuer_reviews WHERE id=? AND actor=? AND organization=?",id,p.account,p.organization()) {
            mapOf("id" to id,"profile" to JSONObjectUtils.parse(it.getString(1)),"profileHash" to it.getString(2),"reviewedAt" to it.getLong(3),"expiresAt" to it.getLong(4))
        }.singleOrNull() ?: reject(404,"NOT_FOUND","Review not found for your account.")
    }
    fun approveIssuer(p: Principal, reviewId: String, profile: IssuerProfile, method: String, note: String, confirmed: String, key: String): Map<String, Any?> = sql.transaction {
        requireCompany(p)
        if(confirmed != "true" || method !in setOf("official-contact","authoritative-directory","demo-walkthrough") || note.trim().length !in 20..1000 || note.any { it.isISOControl() })
            reject(400,"VERIFICATION_REQUIRED","Record how you independently verified the organization and its published identity.")
        val review=issuerReview(p,reviewId)
        val payload=json(mapOf("reviewId" to reviewId,"method" to method,"note" to note.trim(),"confirmed" to confirmed))
        val (operation,fresh)=reserve(p,"issuer:approve",key,payload)
        val reviewedIdentity=(review["profile"] as Map<*, *>)["identity"] as String
        val id=hash(reviewedIdentity)
        if(fresh) {
            if((review["expiresAt"] as Long) <= now()) reject(410,"REVIEW_EXPIRED","Open a fresh issuer review before approval.")
            if(review["profileHash"] != profile.digest) reject(409,"ISSUER_PROFILE_CHANGED","The published identity changed. Review the current profile before approval.")
            // One approval per review, even when a caller supplies a different idempotency key.
            if(sql.query("SELECT id FROM issuer_approvals WHERE review_id=?",reviewId) { it.getString(1) }.isNotEmpty())
                reject(409,"REVIEW_ALREADY_APPROVED","This review was already approved. Open a new review to update it.")
            sql.execute("INSERT INTO issuer_approvals VALUES(?,?,?,?,?,?,?,?)",operation,reviewId,p.account,p.organization(),profile.identity,method,note.trim(),now())
            sql.execute("INSERT INTO trusted_issuers(id,organization,identity,approval_id) VALUES(?,?,?,?) ON CONFLICT(organization,identity) DO UPDATE SET approval_id=excluded.approval_id",id,p.organization(),profile.identity,operation)
            sql.execute("INSERT OR IGNORE INTO trust_revisions VALUES(?,0)",p.organization())
            sql.execute("UPDATE trust_revisions SET revision=revision+1 WHERE organization=?",p.organization())
            sql.execute("UPDATE operations SET state='COMPLETED' WHERE id=?",operation)
            audit(p,"ISSUER_TRUST_APPROVED",operation)
        }
        mapOf("id" to id,"approvalId" to operation,"revision" to revision(p.organization()))
    }
    fun trusted(p: Principal, offset: Int, limit: Int): Map<String, Any?> = sql.transaction {
        requireCompany(p)
        if (offset !in 0..10000 || limit !in 1..100) reject(400,"INVALID_PAGE","Invalid pagination.")
        val rows = sql.query("""SELECT t.id,t.identity,a.actor,a.approved_at,a.method,a.note,r.profile_json FROM trusted_issuers t
            LEFT JOIN issuer_approvals a ON a.id=t.approval_id LEFT JOIN issuer_reviews r ON r.id=a.review_id
            WHERE t.organization=? ORDER BY t.id LIMIT ? OFFSET ?""",p.organization(),limit+1,offset) {
            mapOf("id" to it.getString(1),"identity" to it.getString(2),"approvedBy" to it.getString(3),"approvedAt" to it.getObject(4),
                "verificationMethod" to it.getString(5),"verificationNote" to it.getString(6),"profile" to it.getString(7)?.let(JSONObjectUtils::parse),
                "reviewStatus" to if(it.getString(3) == null) "LEGACY_APPROVAL" else "REVIEWED")
        }
        mapOf("items" to rows.take(limit),"offset" to offset,"limit" to limit,"nextOffset" to if(rows.size>limit) offset+limit else null,"revision" to revision(p.organization()))
    }
    private fun reserveVerification(p: Principal, jobId: String, key: String): Map<String, Any?> {
        val id = sql.transaction {
        check(service == "company")
        val j = sql.query("SELECT organization,revision,competency,assessment FROM jobs WHERE id=?",jobId) {
            listOf(it.getString(1),it.getLong(2),it.getString(3),it.getString(4))
        }.singleOrNull() ?: reject(404,"NOT_FOUND","Job not found.")
        val owner = p.copy(organization=j[0] as String)
        val (id,fresh) = reserve(owner,"verification:$jobId",key,p.session)
        if (fresh) {
            sql.execute("INSERT INTO verification_sessions(id,job_id,organization,owner_hash,job_revision,competency,assessment,expires,upstream_reference) VALUES(?,?,?,?,?,?,?,?,NULL)",id,jobId,j[0],p.session,j[1],j[2],j[3],now()+300)
            audit(owner,"VERIFICATION_RESERVED",id)
        }
        id
        }
        return storedResult(p,id)
    }
    private fun expire(id: String) {
        sql.execute("""INSERT OR IGNORE INTO verification_results SELECT id,'INCOMPLETE','EXPIRED',?,
            COALESCE((SELECT revision FROM trust_revisions WHERE organization=s.organization),0),'competency-v1'
            FROM verification_sessions s WHERE id=? AND expires<=?""",now(),id,now())
    }
    private fun storedResult(p: Principal, id: String): Map<String, Any?> = sql.transaction {
        // Check ownership before any state mutation.
        val visible = sql.query("SELECT id FROM verification_sessions WHERE id=? AND (owner_hash=? OR (organization=? AND ?=1))",
            id,p.session,p.organization,if(Role.COMPANY_ADMIN in p.roles) 1 else 0) { it.getString(1) }.isNotEmpty()
        if(!visible) reject(404,"NOT_FOUND","Verification session not found.")
        expire(id)
        sql.query("""SELECT s.*,r.decision,r.reason,r.evaluated_at,r.trust_revision FROM verification_sessions s
        LEFT JOIN verification_results r ON r.session_id=s.id WHERE s.id=? AND (s.owner_hash=? OR (s.organization=? AND ?=1))""",
        id,p.session,p.organization,if(Role.COMPANY_ADMIN in p.roles) 1 else 0) {
        val final = it.getString("decision") != null
        mapOf("id" to id,"jobId" to it.getString("job_id"),"decision" to (it.getString("decision") ?: "INCOMPLETE"),
            "reason" to (it.getString("reason") ?: if(it.getLong("expires")<=now()) "EXPIRED" else "WAITING"),
            "final" to final,"evaluatedAt" to if(final) it.getLong("evaluated_at") else null,
            "trustRevision" to if(final) it.getLong("trust_revision") else null, "requestUrl" to it.getString("request_url"))
    }.singleOrNull() ?: reject(404,"NOT_FOUND","Verification session not found.")
    }
    fun createOffer(p: Principal, assessment: String, key: String): Map<String,Any?> {
        val reservation = reserveOffer(p,assessment,key)
        val client = protocol ?: return reservation
        checkNotNull(offerReferences) { "Offer reference key required" }
        val id = reservation["id"] as String
        if((reservation["expiresAt"] as Number).toLong() <= now()) reject(410,"EXPIRED","The offer has expired.")
        if(!claimDispatch(id)) return offer(p,id)+mapOf("operationId" to id)
        try {
            val data = sql.query("SELECT candidate,competency,result,recipient FROM assessments WHERE id=?",assessment) {
                listOf(it.getString(1),it.getString(2),it.getString(3),it.getString(4))
            }.single()
            val result = client.offer(data[0],data[1],data[2])
            val url = result["credentialOffer"] as? String ?: error("Missing offer reference")
            validateOfferUrl(url)
            sql.transaction {
                sql.execute("UPDATE issuance_offers SET offer_url=? WHERE id=?",offerReferences.seal(id,data[3],data[0],url,(reservation["expiresAt"] as Number).toLong()),id)
                sql.execute("UPDATE operations SET state='COMPLETED' WHERE id=? AND state='DISPATCHED'",id)
            }
        } catch(_: Exception) { uncertain(id) }
        return offer(p,id)+mapOf("operationId" to id)
    }
    private fun claimDispatch(id: String): Boolean = sql.execute("UPDATE operations SET state='DISPATCHED' WHERE id=? AND state='PENDING'",id) == 1
    private fun uncertain(id: String) { sql.execute("UPDATE operations SET state='RECONCILE_REQUIRED' WHERE id=? AND state='DISPATCHED'",id) }
    fun verification(p: Principal, jobId: String, key: String): Map<String,Any?> {
        val reservation = reserveVerification(p,jobId,key)
        val client = protocol ?: return reservation
        val id = reservation["id"] as String
        if(reservation["final"] != true && claimDispatch(id)) {
            try {
                val created=client.createVerification()
                val upstream=created["sessionId"] as? String ?: error("Missing protocol session")
                require(upstream.matches(Regex("[A-Za-z0-9_-]{1,128}")))
                val url=created["bootstrapAuthorizationRequestUrl"] as? String ?: error("Missing request URL")
                validateProtocolRequest(url,upstream)
                sql.transaction {
                    sql.execute("UPDATE verification_sessions SET upstream_reference=?,request_url=? WHERE id=?",upstream,url,id)
                    sql.execute("UPDATE operations SET state='COMPLETED' WHERE id=? AND state='DISPATCHED'",id)
                }
            } catch(_: Exception) { uncertain(id) }
        }
        return result(p,id)
    }
    fun result(p: Principal, id: String): Map<String,Any?> {
        val stored=storedResult(p,id) // Authorize before contacting the verifier.
        val client=protocol ?: return stored
        if(stored["final"] == true) return stored
        val upstream=sql.query("SELECT upstream_reference FROM verification_sessions WHERE id=?",id) { it.getString(1) }.singleOrNull()
        if(upstream == null) return stored + mapOf("reason" to "RECONCILE_REQUIRED")
        try {
            val info=client.info(upstream)
            when(info["status"]) {
                "SUCCESSFUL" -> {
                    val claims=try { verifiedCompetency(info,upstream,now()) } catch(_: ApiFailure) {
                        finishIncomplete(id,"INVALID_PROOF"); return storedResult(p,id)
                    }
                    finalizeCredential(id,claims)
                }
                "FAILED" -> finishIncomplete(id,"INVALID_PROOF")
                "EXPIRED" -> finishIncomplete(id,"EXPIRED")
                "ACTIVE", "UNUSED", "IN_USE", "VALIDATING_RECEIVED_REQUEST", "PROCESSING_FLOW" -> return stored
                else -> return stored + mapOf("reason" to "DEPENDENCY_UNAVAILABLE")
            }
        } catch(e: ApiFailure) {
            if(e.code == "VERIFIER_SESSION_MISSING") finishIncomplete(id,"VERIFIER_SESSION_MISSING")
            else return stored + mapOf("reason" to "DEPENDENCY_UNAVAILABLE")
        }
        return storedResult(p,id)
    }
    private fun finishIncomplete(id: String, reason: String) = sql.transaction {
        expire(id)
        sql.execute("""INSERT OR IGNORE INTO verification_results SELECT id,'INCOMPLETE',?,?,
            COALESCE((SELECT revision FROM trust_revisions WHERE organization=s.organization),0),?
            FROM verification_sessions s WHERE id=?""",reason,now(),BUSINESS_POLICY_VERSION,id)
    }
    internal fun finalizeCredential(id: String, claims: VerifiedCompetency): Boolean = sql.transaction {
        expire(id)
        val job=sql.query("SELECT organization,competency,assessment FROM verification_sessions WHERE id=?",id) {
            Triple(it.getString(1),it.getString(2),it.getString(3))
        }.single()
        val trusted=sql.query("SELECT id FROM trusted_issuers WHERE organization=? AND identity=?",job.first,claims.issuer) { it.getString(1) }.isNotEmpty()
        val reason=when {
            !trusted -> "UNTRUSTED_ISSUER"
            claims.competency != job.second -> "COMPETENCY_MISMATCH"
            claims.assessment != "competent" || claims.assessment != job.third -> "ASSESSMENT_NOT_COMPETENT"
            else -> "VERIFIED"
        }
        sql.execute("INSERT OR IGNORE INTO verification_results VALUES(?,?,?,?,?,?)",id,
            if(reason == "VERIFIED") "FULFILLED" else "NOT_FULFILLED",reason,now(),revision(job.first),BUSINESS_POLICY_VERSION) == 1
    }
    fun assessments(p: Principal): Map<String,Any?> = sql.transaction {
        check(service == "issuer");permit(p)
        val items=sql.query("""SELECT a.*,o.id AS offer_id,o.expires,o.offer_url,p.state FROM assessments a
            LEFT JOIN issuance_offers o ON o.id=(SELECT id FROM issuance_offers WHERE assessment_id=a.id ORDER BY expires DESC,id DESC LIMIT 1)
            LEFT JOIN operations p ON p.id=o.operation_id WHERE a.organization=? ORDER BY a.rowid DESC LIMIT 100""",p.organization()) {
            mapOf("id" to it.getString("id"),"candidateId" to it.getString("candidate"),"candidateAccountId" to it.getString("recipient"),
                "competencyCode" to it.getString("competency"),"assessmentResult" to it.getString("result"),"offerId" to it.getString("offer_id"),
                "offerState" to it.getString("state"),"offerExpiresAt" to it.getLong("expires"),"offerUrl" to it.getString("offer_url"))
        }
        mapOf("items" to items,"limit" to 100)
    }
    fun jobs(): Map<String,Any?> = mapOf("items" to sql.query("SELECT id,title,competency,assessment,revision FROM jobs ORDER BY id LIMIT 100") {
        mapOf("id" to it.getString(1),"title" to it.getString(2),"competencyCode" to it.getString(3),"assessmentResult" to it.getString(4),"revision" to it.getLong(5))
    },"limit" to 100)
    fun results(p: Principal): Map<String,Any?> {
        check(service == "company");permit(p)
        val ids=sql.query("SELECT id FROM verification_sessions WHERE organization=? ORDER BY rowid DESC LIMIT 100",p.organization()) { it.getString(1) }
        // Lists use stored snapshots. Only an explicitly selected record polls the verifier.
        return mapOf("items" to ids.map { storedResult(p,it) },"limit" to 100)
    }
    override fun close() = sql.close()
}
