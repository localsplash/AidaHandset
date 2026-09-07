package ai.localsplash.aida.handset

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString

/** Only ciphertext is written to app-private storage; the AES key stays in Android Keystore. */
class SecureSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("handset", Context.MODE_PRIVATE)
    val deviceId: String = prefs.getString("installationId", null) ?: UUID.randomUUID().toString().also {
        check(prefs.edit().putString("installationId", it).commit()) { "Unable to save device identity." }
    }

    fun read(): DeviceSession? {
        val stored = prefs.getString("session", null) ?: return null
        return try {
            val pieces = stored.split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
            PlatformApi.json.decodeFromString<DeviceSession>(String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), Charsets.UTF_8))
        } catch (_: Exception) { clear(); null }
    }

    fun save(session: DeviceSession) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(PlatformApi.json.encodeToString(session).toByteArray(Charsets.UTF_8))
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        check(prefs.edit().putString("session", value).commit()) { "Unable to save secure enrollment." }
    }

    fun clear() { check(prefs.edit().remove("session").commit()) { "Unable to remove enrollment." } }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    companion object { private const val KEY_ALIAS = "aida-handset-session-v1" }
}
