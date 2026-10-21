package id.nusacred.common

import id.nusacred.contracts.Role
import id.nusacred.custodian.*
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** Separate instance and database file per backend; no cross-service database access. */
class DomainStore(path: Path, val service: String, private val now: () -> Long = { Instant.now().epochSecond }) : AutoCloseable {
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
            sql.execute("INSERT INTO assessments VALUES(?,?,?,?,?,?)",id,p.organization(),recipient,candidate,competency,result)
            sql.execute("UPDATE operations SET state='COMPLETED' WHERE id=?",id); audit(p,"ASSESSMENT_CREATED",id)
        }
        mapOf("id" to id, "operationId" to id)
    }
    fun createOffer(p: Principal, assessment: String, key: String): Map<String, Any?> = sql.transaction {
        check(service == "issuer"); permit(p)
        if (sql.query("SELECT id FROM assessments WHERE id=? AND organization=?",assessment,p.organization()) { it.getString(1) }.isEmpty()) reject(404,"NOT_FOUND","Assessment not found.")
        val (id,fresh) = reserve(p,"offer:$assessment",key,assessment)
        if (fresh) { sql.execute("INSERT INTO issuance_offers VALUES(?,?,?,NULL,?)",id,assessment,id,now()+300); audit(p,"OFFER_RESERVED",id) }
        offer(p,id) + mapOf("operationId" to id)
    }
    fun offer(p: Principal, id: String): Map<String, Any?> {
        permit(p)
        return sql.query("""SELECT o.id,o.expires,p.state FROM issuance_offers o JOIN assessments a ON a.id=o.assessment_id
            JOIN operations p ON p.id=o.operation_id WHERE o.id=? AND a.organization=?""",id,p.organization()) {
            mapOf("id" to it.getString(1),"expiresAt" to it.getLong(2),"state" to it.getString(3))
        }.singleOrNull() ?: reject(404,"NOT_FOUND","Offer not found.")
    }
    fun trust(p: Principal, identity: String?, remove: String? = null, key: String = UUID.randomUUID().toString()): Map<String, Any?> = sql.transaction {
        check(service == "company"); permit(p)
        sql.execute("INSERT OR IGNORE INTO trust_revisions VALUES(?,0)",p.organization())
        val id = remove ?: hash(requireNotNull(identity))
        val (operation,fresh) = reserve(p,if(remove == null) "trust:add" else "trust:remove",key,identity ?: remove!!)
        val changed = if(!fresh) 0 else if (remove != null) sql.execute("DELETE FROM trusted_issuers WHERE id=? AND organization=?",id,p.organization())
            else sql.execute("INSERT OR IGNORE INTO trusted_issuers VALUES(?,?,?)",id,p.organization(),identity)
        if (changed == 1) { sql.execute("UPDATE trust_revisions SET revision=revision+1 WHERE organization=?",p.organization()); audit(p,"TRUST_CHANGED",id) }
        sql.execute("UPDATE operations SET state='COMPLETED' WHERE id=?",operation)
        mapOf("id" to id,"revision" to revision(p.organization()))
    }
    private fun revision(org: String): Long = sql.query("SELECT revision FROM trust_revisions WHERE organization=?",org) { it.getLong(1) }.singleOrNull() ?: 0
    fun trusted(p: Principal, offset: Int, limit: Int): Map<String, Any?> = sql.transaction {
        check(service == "company"); permit(p)
        if (offset !in 0..10000 || limit !in 1..100) reject(400,"INVALID_PAGE","Invalid pagination.")
        val rows = sql.query("SELECT id,identity FROM trusted_issuers WHERE organization=? ORDER BY id LIMIT ? OFFSET ?",p.organization(),limit+1,offset) {
            mapOf("id" to it.getString(1),"identity" to it.getString(2))
        }
        mapOf("items" to rows.take(limit),"offset" to offset,"limit" to limit,"nextOffset" to if(rows.size>limit) offset+limit else null,"revision" to revision(p.organization()))
    }
    fun verification(p: Principal, jobId: String, key: String): Map<String, Any?> {
        val id = sql.transaction {
        check(service == "company")
        val j = sql.query("SELECT organization,revision,competency,assessment FROM jobs WHERE id=?",jobId) {
            listOf(it.getString(1),it.getLong(2),it.getString(3),it.getString(4))
        }.singleOrNull() ?: reject(404,"NOT_FOUND","Job not found.")
        val owner = p.copy(organization=j[0] as String)
        val (id,fresh) = reserve(owner,"verification:$jobId",key,p.session)
        if (fresh) {
            sql.execute("INSERT INTO verification_sessions VALUES(?,?,?,?,?,?,?,?,NULL)",id,jobId,j[0],p.session,j[1],j[2],j[3],now()+300)
            audit(owner,"VERIFICATION_RESERVED",id)
        }
        id
        }
        return result(p,id)
    }
    private fun expire(id: String) {
        sql.execute("""INSERT OR IGNORE INTO verification_results SELECT id,'INCOMPLETE','EXPIRED',?,
            COALESCE((SELECT revision FROM trust_revisions WHERE organization=s.organization),0),'foundation-v1'
            FROM verification_sessions s WHERE id=? AND expires<=?""",now(),id,now())
    }
    fun result(p: Principal, id: String): Map<String, Any?> = sql.transaction {
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
            "trustRevision" to if(final) it.getLong("trust_revision") else null)
    }.singleOrNull() ?: reject(404,"NOT_FOUND","Verification session not found.")
    }
    /** Called only by the future verified-protocol adapter; never exposed as a browser endpoint. */
    internal fun finalizeVerified(id: String, issuer: String, competent: Boolean): Boolean = sql.transaction {
        expire(id)
        val org = sql.query("SELECT organization FROM verification_sessions WHERE id=?",id) { it.getString(1) }.single()
        val trusted = sql.query("SELECT id FROM trusted_issuers WHERE organization=? AND identity=?",org,issuer) { it.getString(1) }.isNotEmpty()
        sql.execute("INSERT OR IGNORE INTO verification_results VALUES(?,?,?,?,?,?)",id,
            if(trusted && competent) "FULFILLED" else "NOT_FULFILLED",if(!trusted) "UNTRUSTED_ISSUER" else if(competent) "VERIFIED" else "COMPETENCY_MISMATCH",now(),revision(org),"foundation-v1") == 1
    }
    override fun close() = sql.close()
}
