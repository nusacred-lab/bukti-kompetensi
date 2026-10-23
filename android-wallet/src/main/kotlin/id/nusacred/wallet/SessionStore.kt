package id.nusacred.wallet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/** Refresh tokens persist only in a vault whose wrapping key requires strong biometric authentication. */
class SessionStore(context: Context) {
    private var vaultKey:ByteArray?=null
    private var authentication:org.json.JSONObject?=null
    private val biometricAlias="nusacred-biometric-vault"
    private val prefs = context.getSharedPreferences("private-session", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "nusacred-application-session"
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private fun saveRecord(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val record = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(name, Base64.encodeToString(record, Base64.NO_WRAP)).commit())
    }
    private fun loadRecord(name: String): String? = try {
        prefs.getString(name, null)?.let {
            val bytes = Base64.decode(it, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
    } catch (_: Exception) { prefs.edit().remove(name).commit(); null }
    private fun sessionRecord():org.json.JSONObject? = authentication ?: loadRecord("session")?.let { org.json.JSONObject(it) }
    fun save(value:String,expiresAt:Long) {
        require(expiresAt>System.currentTimeMillis()/1000)
        val old=authentication
        authentication=org.json.JSONObject().put("token",value).put("expires",expiresAt)
        if(old!=null) {
            for(name in listOf("refresh","refreshUntil")) if(old.has(name)) authentication!!.put(name,old.get(name))
        }
        persistAuthentication()
    }
    private fun persistAuthentication() {
        val record=authentication ?: return
        val material=vaultKey
        if(material!=null) {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,SecretKeySpec(material,"AES"));updateAAD("nusacred-auth-v1".toByteArray()) }
            val bytes=cipher.iv+cipher.doFinal(record.toString().toByteArray(Charsets.UTF_8))
            check(prefs.edit().putString("vault",Base64.encodeToString(bytes,Base64.NO_WRAP)).remove("session").commit())
        } else {
            // Refresh tokens are never written under the non-biometric legacy key.
            saveRecord("session",org.json.JSONObject().put("token",record.getString("token")).put("expires",record.getLong("expires")).toString())
        }
    }
    fun setRefresh(value:String,until:Long) {
        require(value.length in 32..16384 && until>System.currentTimeMillis()/1000)
        val record=authentication ?: error("No authenticated account")
        record.put("refresh",value).put("refreshUntil",until);persistAuthentication()
    }
    fun refreshToken():String?=authentication?.takeIf { it.optLong("refreshUntil")>System.currentTimeMillis()/1000 }?.optString("refresh")?.takeIf { it.isNotBlank() }
    fun refreshUntil():Long=authentication?.optLong("refreshUntil") ?: 0
    fun consumeRefresh() { authentication?.remove("refresh");persistAuthentication() }
    fun renewalSession():String?=sessionRecord()?.optString("token")?.takeIf { it.isNotBlank() }
    fun expiresAt():Long=try { sessionRecord()?.optLong("expires") ?: 0 } catch(_:Exception) { clearSession();0 }
    fun load():String?=try { sessionRecord()?.takeIf { it.optLong("expires")>System.currentTimeMillis()/1000 }?.getString("token") } catch(_:Exception) { clearSession();null }
    fun protected():Boolean=prefs.contains("wrapped-key")
    fun locked():Boolean=protected() && vaultKey==null
    fun protectionCipher():Cipher {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if(!store.containsAlias(biometricAlias)) KeyGenerator.getInstance("AES","AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(biometricAlias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true).setUserAuthenticationParameters(0,KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true).build())
        }.generateKey()
        val key=store.getKey(biometricAlias,null) as SecretKey
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            if(protected()) {
                val bytes=Base64.decode(prefs.getString("wrapped-key",null),Base64.NO_WRAP)
                init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            } else init(Cipher.ENCRYPT_MODE,key)
        }
    }
    fun completeProtection(cipher:Cipher) {
        if(protected()) {
            val wrapped=Base64.decode(prefs.getString("wrapped-key",null),Base64.NO_WRAP)
            vaultKey=cipher.doFinal(wrapped.copyOfRange(12,wrapped.size))
            try {
                val bytes=Base64.decode(prefs.getString("vault",null),Base64.NO_WRAP)
                val decrypt=Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE,SecretKeySpec(vaultKey!!,"AES"),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
                    updateAAD("nusacred-auth-v1".toByteArray())
                }
                authentication=org.json.JSONObject(String(decrypt.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
            } catch(e:Exception) { lock();throw e }
        } else {
            check(refreshToken()!=null)
            val material=ByteArray(32).also(SecureRandom()::nextBytes)
            val wrapped=cipher.iv+cipher.doFinal(material)
            vaultKey=material
            check(prefs.edit().putString("wrapped-key",Base64.encodeToString(wrapped,Base64.NO_WRAP)).commit())
            persistAuthentication()
        }
    }
    fun lock() { vaultKey?.fill(0);vaultKey=null;authentication=null }
    fun clearSession() {
        lock();prefs.edit().remove("session").remove("vault").remove("wrapped-key").remove("operation").commit()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null);if(containsAlias(biometricAlias)) deleteEntry(biometricAlias) }
    }
    fun saveContext(link: String) = saveRecord("context", org.json.JSONObject().put("link", link)
        .put("expires", System.currentTimeMillis() + 240_000).toString())
    fun context(): String? = try {
        loadRecord("context")?.let {
            val record = org.json.JSONObject(it)
            if (record.getLong("expires") > System.currentTimeMillis()) record.getString("link")
            else { clearContext(); null }
        }
    } catch (_: Exception) { clearContext(); null }
    fun clearContext() { prefs.edit().remove("context").commit() }
    fun saveOperation(id: String, key: String, receiving: Boolean) = saveRecord("operation",org.json.JSONObject()
        .put("id",id).put("key",key).put("receiving",receiving).toString())
    fun operation(): org.json.JSONObject? = try { loadRecord("operation")?.let { org.json.JSONObject(it) } } catch(_: Exception) { clearOperation();null }
    fun clearOperation() { prefs.edit().remove("operation").commit() }
    fun clear() { clearSession();prefs.edit().clear().commit() }
}
