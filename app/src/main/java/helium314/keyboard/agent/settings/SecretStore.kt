package helium314.keyboard.agent.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Holds secrets: the OpenConnector runtime token and the token-broker app token.
 * Provider API keys never live on the phone; the token broker Worker mints
 * short-lived ephemeral tokens instead.
 *
 * Values are AES-256-GCM encrypted with a non-exportable key in the Android Keystore
 * (API 23+). androidx.security-crypto needs minSdk 23, so this is done by hand to keep
 * HeliBoard's minSdk 21; on API 21/22 values fall back to private app storage.
 */
class SecretStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("agent_secrets", Context.MODE_PRIVATE)

    var connectorRuntimeToken: String?
        get() = read("connector_runtime_token")
        set(v) = write("connector_runtime_token", v)

    var brokerAppToken: String?
        get() = read("broker_app_token")
        set(v) = write("broker_app_token", v)

    private fun read(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        if (Build.VERSION.SDK_INT < 23 || !stored.startsWith(PREFIX)) return stored
        return runCatching {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 12)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun write(key: String, value: String?) {
        if (value.isNullOrEmpty()) { prefs.edit().remove(key).apply(); return }
        val out = if (Build.VERSION.SDK_INT < 23) value else runCatching {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, key())
            PREFIX + Base64.encodeToString(c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }.getOrDefault(value)
        prefs.edit().putString(key, out).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (Build.VERSION.SDK_INT < 23) error("Keystore AES needs API 23")
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "agent_keyboard_secrets"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val PREFIX = "ks1:"
    }
}
