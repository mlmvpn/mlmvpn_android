package com.mlmvpn.scanner.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals small secrets (the Cloudflare account list) with an AES-256-GCM key that lives in the
 * Android Keystore and can never be exported, so the value in SharedPreferences is useless off
 * this device: a backup, a rooted file copy or a leaked prefs XML shows only ciphertext.
 *
 * Output is `v1:` + base64(iv ‖ ciphertext‖tag). [open] returns null for anything it cannot open
 * (wrong key after a reinstall, corrupted bytes) and never throws; callers decide what that means.
 *
 * Some devices ship a broken Keystore. [available] is false there. Callers retain previously
 * saved ciphertext and fail closed instead of writing a new plaintext credential.
 */
object SecureStore {
    private const val TAG = "SecureStore"
    private const val ALIAS = "mlm_secure_v1"
    private const val PREFIX = "v1:"
    private const val IV_LEN = 12

    @Volatile private var broken = false

    val available: Boolean get() = !broken && runCatching { key() }.isSuccess

    fun isSealed(value: String?): Boolean = value != null && value.startsWith(PREFIX)

    fun seal(plain: String): String? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    } catch (e: Exception) {
        broken = true
        Log.w(TAG, "seal failed: ${e.javaClass.simpleName}")
        null
    }

    fun open(sealed: String): String? {
        if (!sealed.startsWith(PREFIX)) return null
        return try {
            val raw = Base64.decode(sealed.substring(PREFIX.length), Base64.NO_WRAP)
            if (raw.size <= IV_LEN) return null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_LEN))
            String(c.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "open failed: ${e.javaClass.simpleName}")
            null
        }
    }

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
