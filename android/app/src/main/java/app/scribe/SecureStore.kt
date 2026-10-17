package app.scribe

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps secrets — the Groq key — encrypted with a key that lives in the
 * phone's Android Keystore and never leaves it.
 *
 * The key used to sit in the WebView's localStorage as plain text, readable by
 * anything that could read the app's files. Now only ciphertext is on disk,
 * and it can only be decrypted on this phone, by this app.
 */
object SecureStore {
    private const val ALIAS = "scribe-secrets"
    private const val PREFS = "scribe-secure"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    @Volatile private var cache = HashMap<String, String>()

    @Synchronized
    fun put(context: Context, name: String, value: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (value.isEmpty()) {
            prefs.edit().remove(name).commit()
            cache.remove(name)
            return true
        }
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
            cache[name] = value
            true
        }.getOrElse {
            Diag.log("secure", "could not store $name: ${it.message}")
            false
        }
    }

    @Synchronized
    fun get(context: Context, name: String): String? {
        cache[name]?.let { return it }
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null) ?: return null
        return runCatching {
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, sealed, 0, 12))
            String(cipher.doFinal(sealed, 12, sealed.size - 12), Charsets.UTF_8).also { cache[name] = it }
        }.getOrElse {
            Diag.log("secure", "could not read $name: ${it.message}")
            null
        }
    }
}
