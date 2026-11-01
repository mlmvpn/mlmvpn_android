package org.mlmchat.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The database key (SPEC 4 and 14.9: SQLCipher with a raw 32-byte key, the key in Android Keystore).
 *
 * The 32 random bytes are kept wrapped: AES-256-GCM under a key that lives in Android Keystore
 * and never leaves it. Only the wrapped form is on disk, in no-backup storage next to the
 * database, so a copy of the app's files without this phone's Keystore opens nothing. All of it
 * is the platform's own cryptography, used as documented (no hand-made crypto, T18).
 */
internal object DbKey {
    private const val ALIAS = "org.mlmchat.core.db"
    private const val FILE = "db.key"
    private const val VERSION: Byte = 1
    private const val IV = 12
    private const val TAG_BITS = 128

    fun exists(context: Context): Boolean = File(ChatNode.dir(context), FILE).isFile

    @Synchronized
    fun get(context: Context): ByteArray {
        val file = File(ChatNode.dir(context), FILE)
        if (file.isFile) {
            return unwrap(file.readBytes())
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(wrap(key))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw CoreException("db key: could not be saved")
        }
        return key
    }

    private fun wrap(key: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val sealed = c.doFinal(key)
        return byteArrayOf(VERSION) + c.iv + sealed
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        if (blob.size != 1 + IV + 32 + TAG_BITS / 8 || blob[0] != VERSION) {
            throw CoreException("db key: unreadable")
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keystoreKey(create = false), GCMParameterSpec(TAG_BITS, blob, 1, IV))
        return c.doFinal(blob, 1 + IV, blob.size - 1 - IV)
    }

    private fun keystoreKey(create: Boolean = true): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) {
            throw CoreException("db key: the Keystore key is gone")
        }
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
