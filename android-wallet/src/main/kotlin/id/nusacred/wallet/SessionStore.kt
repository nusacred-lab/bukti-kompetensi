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

/** Only opaque session and expiring link context persist; IdP tokens remain ephemeral. */
class SessionStore(context: Context) {
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
    fun save(value: String) = saveRecord("session", value)
    fun load(): String? = loadRecord("session")
    fun clearSession() { prefs.edit().remove("session").commit() }
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
    fun clear() { prefs.edit().clear().commit() }
}
