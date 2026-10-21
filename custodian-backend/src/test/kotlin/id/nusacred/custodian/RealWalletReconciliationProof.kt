package id.nusacred.custodian

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** One-shot private integration runner; never included in the application distribution. */
object RealWalletReconciliationProof {
    @JvmStatic fun main(args: Array<String>) {
        val directory = Path.of(args.single())
        Files.createDirectories(directory)
        val file = directory.resolve("access.db")
        require(!Files.exists(file)) { "Use a fresh disposable proof directory" }
        val issuer = "https://auth-p2.demo.inoerawan.id/realms/competency-proof-p2"
        val subject = "synthetic-reconciliation-" + randomToken()
        val upstream = WalletClient("http://wallet:7006")
        var reference: Pair<String, String>? = null
        var operation = ""
        var attempts = 0
        fun login(registry: AccessRegistry): String {
            val token = hash(randomToken())
            registry.registerSession(issuer, subject, token, randomToken(), Instant.now().epochSecond,
                Instant.now().plusSeconds(300).epochSecond)
            return token
        }
        AccessRegistry(file).use { registry ->
            val token = login(registry)
            try {
                registry.provision(token) { op ->
                    attempts++; operation = op; reference = upstream.create(op)
                    // Inject a lost result after actual Wallet2 persistence and before registry acknowledgement.
                    throw java.net.SocketTimeoutException("Injected lost result")
                }
                error("Uncertain provisioning unexpectedly succeeded")
            } catch (_: ReconciliationRequired) { }
            check(registry.binding(token)?.state == "RECONCILE_REQUIRED")
            try { registry.provision(token) { attempts++; error("Must not recreate") }; error("Blind retry accepted") }
            catch (_: ReconciliationRequired) { }
        }
        AccessRegistry(file).use { registry ->
            val token = login(registry)
            val restored = registry.reconcile(token, upstream::lookup)
            check(restored.operation == operation && restored.state == "READY")
            check(restored.walletId == reference!!.first && restored.keyId == reference!!.second)
            check(registry.provision(token) { attempts++; error("Must not recreate") } == restored)
            check(attempts == 1)
        }
        Files.writeString(directory.resolve("result.json"), json(mapOf("schemaVersion" to 1,
            "realWallet2" to true, "fault" to "lost result after Wallet2 commit before registry acknowledgement",
            "checks" to listOf("Persistent uncertain state", "Blind retry denied", "Registry process restart",
                "Original named-store wallet and holder key reconciled", "Exactly one create attempt"))))
        println("PASS: real Wallet2 wallet/key reconciled after injected lost result and registry restart")
    }
}
