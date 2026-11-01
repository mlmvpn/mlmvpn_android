package com.mlmvpn.scanner.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The app keeps its secrets three ways, each bound to this phone's Keystore, so a backup carries
 * them opened and a restore seals them again with the new phone's keys:
 *
 *  - `scanner.data.SecureStore`: `v1:` + base64 (Cloudflare accounts, identities, FENIX, Telegram, MAE…)
 *  - `core.tunnel.SecureStore`: bare base64 (Geph, Zero Trust, GitHub tokens), "" on any failure
 *  - the OpenVPN vault: one AES-GCM file per name in `no_backup/openvpn` (OpenVPN, Windscribe)
 *
 * Every helper here fails loudly. An export that cannot open a secret leaves that item out and names
 * it; a restore that cannot seal one restores nothing.
 */
internal object Seals {

    fun open(sealed: String): String =
        com.mlmvpn.scanner.data.SecureStore.open(sealed) ?: throw IllegalStateException("a sealed value on this phone cannot be opened")

    fun seal(plain: String): String =
        com.mlmvpn.scanner.data.SecureStore.seal(plain) ?: throw IllegalStateException("this phone's secure storage is unavailable")

    fun isSealed(value: String?): Boolean = com.mlmvpn.scanner.data.SecureStore.isSealed(value)

    /** Opens a `core.tunnel.SecureStore` value; "" stays "". */
    fun decryptCore(encrypted: String): String {
        if (encrypted.isEmpty()) return ""
        return com.mlmvpn.core.tunnel.SecureStore.decrypt(encrypted).ifEmpty {
            throw IllegalStateException("a sealed value on this phone cannot be opened")
        }
    }

    fun encryptCore(plain: String): String {
        if (plain.isEmpty()) return ""
        return com.mlmvpn.core.tunnel.SecureStore.encrypt(plain).ifEmpty {
            throw IllegalStateException("this phone's secure storage is unavailable")
        }
    }
}

/**
 * The OpenVPN vault's file format, for building its files inside a restore plan instead of writing
 * them in place: `1 ‖ iv(12) ‖ AES-GCM(ciphertext ‖ tag)`, the file's name as associated data, the
 * Keystore key `mlmvpn_openvpn_v1`. Kept byte for byte with `openvpn/OpenVpnVault`; [seal] checks
 * its own output by opening it again.
 */
internal object VaultCipher {
    private const val ALIAS = "mlmvpn_openvpn_v1"
    private val NAME = Regex("[a-zA-Z0-9_-]{1,100}")

    fun dir(ctx: Context) = File(ctx.noBackupFilesDir, "openvpn")

    /** The names in the vault; a name whose only copy is AtomicFile's `.bak` counts too. */
    fun names(ctx: Context): List<String> =
        dir(ctx).listFiles()?.filter { it.isFile }?.map { it.name.removeSuffix(".bak") }
            ?.filter { NAME.matches(it) }?.distinct()?.sorted().orEmpty()

    fun read(ctx: Context, name: String): String? {
        require(NAME.matches(name))
        val base = File(dir(ctx), name)
        // AtomicFile reads its `.bak` in place of the file when one is left from a write that died.
        val f = File(base.path + ".bak").takeIf { it.isFile } ?: base.takeIf { it.isFile } ?: return null
        return open(name, f.readBytes())
    }

    fun open(name: String, data: ByteArray): String {
        require(data.size in 29..16_777_216 && data[0] == 1.toByte()) { "vault file damaged: $name" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 1, 12))
        c.updateAAD(name.toByteArray())
        return c.doFinal(data, 13, data.size - 13).toString(Charsets.UTF_8)
    }

    fun seal(name: String, plaintext: String): ByteArray {
        require(NAME.matches(name))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(name.toByteArray())
        val data = byteArrayOf(1) + c.iv + c.doFinal(plaintext.toByteArray())
        check(open(name, data) == plaintext) { "vault round trip failed" }
        return data
    }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build()
            )
        }.generateKey()
    }
}
