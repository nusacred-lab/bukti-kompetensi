package id.nusacred.wallet

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.security.KeyStore
import java.util.UUID

/** Platform-only device proof. Run solely against the disposable .securityproof APK. */
class DeviceSecurityProof: Instrumentation() {
    private var mode=""
    private var stage="start"
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
                "refresh-memory" -> {
                    store.clear()
                    val token=UUID.randomUUID().toString();val refresh=UUID.randomUUID().toString()
                    store.save(token,System.currentTimeMillis()/1000+300)
                    store.setRefresh(refresh,System.currentTimeMillis()/1000+604800)
                    check(store.refreshToken()==refresh)
                    check(context.getSharedPreferences("private-session",0).all.values.none { it.toString().contains(refresh) })
                    check(SessionStore(context).refreshToken()==null) { "Refresh persisted without biometrics" }
                    store.consumeRefresh();check(store.refreshToken()==null)
                    store.clear()
                }
                "biometric-guard" -> {
                    stage="seed"
                    store.clear()
                    store.save(UUID.randomUUID().toString(),System.currentTimeMillis()/1000+300)
                    store.setRefresh(UUID.randomUUID().toString(),System.currentTimeMillis()/1000+604800)
                    var denied=false
                    stage="unauthenticated-crypto"
                    try { store.completeProtection(store.protectionCipher()) }
                    catch(e:Exception) {
                        denied=e is android.security.keystore.UserNotAuthenticatedException ||
                            e.cause is android.security.KeyStoreException
                    }
                    check(denied) { "Biometric key used without per-operation authentication" }
                    stage="key-metadata"
                    val keys=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    val key=keys.getKey("nusacred-biometric-vault",null) as javax.crypto.SecretKey
                    val info=javax.crypto.SecretKeyFactory.getInstance(key.algorithm,"AndroidKeyStore")
                        .getKeySpec(key,android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo
                    stage="per-use-auth-"+info.userAuthenticationValidityDurationSeconds
                    // Newer devices report the API-30 zero timeout; older metadata uses -1.
                    // The unauthenticated cryptographic operation above must also be rejected.
                    check(info.isUserAuthenticationRequired && info.userAuthenticationValidityDurationSeconds in -1..0)
                    stage="strong-auth-"+info.userAuthenticationType
                    check(info.userAuthenticationType==android.security.keystore.KeyProperties.AUTH_BIOMETRIC_STRONG)
                    store.clear()
                }
                else -> error("Unknown proof mode")
            }
            finish(Activity.RESULT_OK,Bundle().apply { putString("proof","PASS: "+mode) })
        } catch(e:Exception) { finish(Activity.RESULT_CANCELED,Bundle().apply { putString("proof","FAIL: $mode ($stage, ${e.javaClass.simpleName})") }) }
    }
}
