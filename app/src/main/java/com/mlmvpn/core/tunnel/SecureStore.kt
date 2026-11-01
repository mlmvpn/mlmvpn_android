package com.mlmvpn.core.tunnel

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecureStore {

    /**
     * What was already decrypted, by its ciphertext. Screens read secrets while they draw (Geph's
     * panel asks whether there is an account on every recomposition), and each read went to the
     * Android Keystore -- which, when busy, sleeps and retries inside the call: an ANR on the
     * main thread (1.2.39). Same ciphertext, same plaintext, so one trip per value is enough.
     */
    private val plainCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    // Renaming this is safe precisely because the package changed: AndroidKeyStore
    // is scoped per app UID, so com.mlmvpn.core.tunnel starts with an empty keystore and
    // the alias is generated fresh on first use. There is nothing to migrate.
    private const val KEY_ALIAS = "mlmvpn_tunnel_credential_key"
    private const val PREFS_NAME = "secure_settings"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /** The real cipher. null when the Keystore is unavailable -- never the plaintext. */
    private fun encryptOrNull(plainText: String): String? = try {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
        Base64.encodeToString(combined, Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }

    /**
     * Fail CLOSED: on a crypto failure this returns "" rather than the plaintext. A caller that
     * stores the result therefore never writes a secret in the clear -- it stores nothing. (The old
     * version returned the plaintext here, which [putSecret] then wrote into "secure" prefs.)
     */
    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        return encryptOrNull(plainText) ?: ""
    }

    /**
     * Fail CLOSED: on any failure this returns "" rather than the ciphertext, so a caller never
     * mistakes undecryptable bytes for a usable value. Legacy plaintext migration is handled by
     * [getSecret] against the old "settings" prefs, not by returning the stored bytes here.
     */
    fun decrypt(encryptedBase64: String): String {
        if (encryptedBase64.isEmpty()) return ""
        plainCache[encryptedBase64]?.let { return it }
        return try {
            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) return ""
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, combined, 0, GCM_IV_LENGTH)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val plainBytes = cipher.doFinal(combined, GCM_IV_LENGTH, combined.size - GCM_IV_LENGTH)
            String(plainBytes, Charsets.UTF_8).also { plainCache[encryptedBase64] = it }
        } catch (_: Exception) {
            ""
        }
    }

    fun putSecret(context: Context, key: String, value: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (value.isBlank()) {
            prefs.edit().putString(key, "").apply()
        } else {
            val encrypted = encryptOrNull(value)
            if (encrypted == null) {
                // Keystore unavailable: store NOTHING rather than the plaintext. The value is not
                // persisted (the user may have to re-enter it), but it never lands in the clear.
                android.util.Log.w("SecureStore", "could not seal a secret; not storing it in the clear")
                prefs.edit().remove(key).apply()
            } else {
                prefs.edit().putString(key, encrypted).apply()
            }
        }
        // Clean from plain prefs if it was there
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .remove(key)
            .apply()
    }

    fun getSecret(context: Context, key: String, fallback: String = ""): String {
        val securePrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encrypted = securePrefs.getString(key, null)
        if (encrypted != null) {
            return decrypt(encrypted).ifBlank { fallback }
        }
        // Fallback to legacy plain settings for migration
        val plainPrefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val legacy = plainPrefs.getString(key, null)
        if (!legacy.isNullOrBlank()) {
            putSecret(context, key, legacy)
            return legacy
        }
        return fallback
    }

    /**
     * Whether a value is stored under [key], without decrypting it. The Keystore can take seconds to
     * answer (it waits on its own service after a boot, and retries), so a screen that only needs
     * to know "is there an account" must not ask it from the main thread (Geph's ANRs, 1.2.41).
     */
    fun hasSecret(context: Context, key: String): Boolean {
        val sealed = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(key, null)
        if (sealed != null) return sealed.isNotBlank()
        return !context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(key, null).isNullOrBlank()
    }

    fun removeSecret(context: Context, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(key)
            .apply()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .remove(key)
            .apply()
    }
}
