package id.nusacred.wallet

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.security.KeyStore
import java.util.UUID

/** Platform-only device proof. Run solely against the disposable .securityproof APK. */
class DeviceSecurityProof: Instrumentation() {
    private var mode=""
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);mode=arguments?.getString("mode") ?: "";start() }
    override fun onStart() {
        try {
            val context=targetContext
            check(context.packageName=="id.nusacred.wallet.securityproof") { "Disposable proof package required" }
            val store=SessionStore(context)
            when(mode) {
                "seed" -> {
                    val token=UUID.randomUUID().toString();val reference="https://wallet-p2.demo.inoerawan.id/present?request_url="+UUID.randomUUID()
                    store.save(token,System.currentTimeMillis()/1000+1800)
                    store.saveContext(reference);store.saveOperation(UUID.randomUUID().toString(),UUID.randomUUID().toString(),false)
                    check(store.load()==token && store.context()==reference && store.operation()!=null)
                    val xml=context.getSharedPreferences("private-session",0).all.values.joinToString()
                    check(!xml.contains(token) && !xml.contains(reference)) { "Plaintext session or context persisted" }
                    val keyStore=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    check(keyStore.containsAlias("nusacred-application-session"))
                }
                "restored" -> {
                    // The operator reinstalls the disposable APK and copies only its old encrypted preferences.
                    check(context.getSharedPreferences("private-session",0).all.isNotEmpty()) { "Restore fixture is missing" }
                    check(store.load()==null && store.context()==null && store.operation()==null) { "Old device access revived" }
                    check(context.getSharedPreferences("private-session",0).all.isEmpty()) { "Unreadable records not removed" }
                }
                "logout" -> {
                    store.save(UUID.randomUUID().toString(),System.currentTimeMillis()/1000+1800)
                    store.saveContext("synthetic-link");store.saveOperation("synthetic-context",UUID.randomUUID().toString(),true)
                    store.clear()
                    check(store.load()==null && store.context()==null && store.operation()==null)
                    check(context.getSharedPreferences("private-session",0).all.isEmpty())
                }
                else -> error("Unknown proof mode")
            }
            finish(Activity.RESULT_OK,Bundle().apply { putString("proof","PASS: "+mode) })
        } catch(_:Exception) { finish(Activity.RESULT_CANCELED,Bundle().apply { putString("proof","FAIL: "+mode) }) }
    }
}
