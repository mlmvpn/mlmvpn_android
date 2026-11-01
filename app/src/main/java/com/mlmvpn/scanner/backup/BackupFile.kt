package com.mlmvpn.scanner.backup

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The backup file on disk: a small JSON envelope around the gzipped content, the content encrypted
 * with the user's password when they gave one.
 *
 * ```
 * { "mlmvpn_backup": 1,                     the envelope's version -- a reader refuses a higher one
 *   "app_version": "1.2.42", "app_build": 78, "created_at": <ms>,
 *   "compression": "gzip",
 *   "encryption": "none" | "aes-256-gcm",
 *   "kdf": "pbkdf2-hmac-sha256", "kdf_iterations": n, "salt": b64, "iv": b64, "password_form": 1,
 *   "payload_sha256": hex,                  over the payload bytes as stored
 *   "payload": b64 }
 * ```
 *
 * The digest is what tells a damaged file from a wrong password: GCM alone fails the same way for
 * both, and «رمز درست نیست» on a file that was cut short in a chat app sends the user hunting for a
 * password that was never wrong. Every header field goes into GCM's associated data, so none of
 * them can be changed without the password check failing.
 *
 * The password is normalized before use ([normalizePassword]): Persian and Arabic keyboards type
 * different characters for the same letters and digits, and a backup made on one phone has to open
 * on the next one, whatever keyboard it has. `password_form` names that rule so it can change.
 *
 * Pure JVM -- no Android types -- so all of it runs in unit tests.
 */
internal object BackupFile {

    const val FORMAT = 1
    const val MAGIC = "mlmvpn_backup"
    const val EXTENSION = "mlmbak"
    const val MIME = "application/octet-stream"

    /** A backup is a few hundred KB; this is far past any real one and well inside the heap. */
    const val MAX_FILE_BYTES = 48 * 1024 * 1024
    private const val MAX_CONTENT_BYTES = 160 * 1024 * 1024

    /**
     * PBKDF2-HMAC-SHA256 rounds for new files. Stored in each file, so it can be raised later without
     * breaking old ones. Measured on a Galaxy A50 (2018 mid-range): see BackupFileTest's note.
     */
    const val KDF_ITERATIONS = 310_000
    private const val MIN_ITERATIONS = 1_000
    private const val MAX_ITERATIONS = 10_000_000
    private const val PASSWORD_FORM = 1

    private const val ENC_NONE = "none"
    private const val ENC_AES = "aes-256-gcm"
    private const val KDF = "pbkdf2-hmac-sha256"
    private const val GZIP = "gzip"

    class Header(
        val format: Int,
        val appVersion: String,
        val appBuild: Long,
        val createdAt: Long,
        val encrypted: Boolean,
    )

    sealed class Opened {
        class Ok(val header: Header, val content: JSONObject) : Opened()
        /** Not a backup of this app at all. */
        object NotBackup : Opened()
        /** Written by a newer app whose envelope this one cannot read. */
        class TooNew(val header: Header) : Opened()
        /** A backup, but damaged. */
        object Corrupt : Opened()
        object NeedsPassword : Opened()
        object WrongPassword : Opened()
    }

    /**
     * The file's bytes. [password] null or blank: no encryption (the screen warns about that).
     * [iterations] and [random] are parameters only for the tests.
     */
    fun write(
        content: JSONObject,
        password: String?,
        appVersion: String,
        appBuild: Long,
        createdAt: Long,
        iterations: Int = KDF_ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val packed = gzip(content.toString().toByteArray(Charsets.UTF_8))
        val env = JSONObject()
            .put(MAGIC, FORMAT)
            .put("app_version", appVersion)
            .put("app_build", appBuild)
            .put("created_at", createdAt)
            .put("compression", GZIP)
        val secret = password?.let(::normalizePassword)?.takeIf { it.isNotEmpty() }
        val payload: ByteArray
        if (secret == null) {
            env.put("encryption", ENC_NONE)
            payload = packed
        } else {
            val salt = ByteArray(16).also(random::nextBytes)
            val iv = ByteArray(12).also(random::nextBytes)
            env.put("encryption", ENC_AES)
                .put("kdf", KDF)
                .put("kdf_iterations", iterations)
                .put("salt", B64.encode(salt))
                .put("iv", B64.encode(iv))
                .put("password_form", PASSWORD_FORM)
            val key = pbkdf2(secret.toByteArray(Charsets.UTF_8), salt, iterations)
            payload = gcm(Cipher.ENCRYPT_MODE, key, iv, aad(env), packed)
        }
        env.put("payload_sha256", hex(sha256(payload)))
        env.put("payload", B64.encode(payload))
        return env.toString().toByteArray(Charsets.UTF_8)
    }

    /** The envelope alone, without opening the content: null when this is not a backup. */
    fun header(bytes: ByteArray): Header? = envelope(bytes)?.let(::headerOf)

    fun open(bytes: ByteArray, password: String?): Opened {
        // A file cut short in a messenger no longer parses, but still begins as one of ours: damaged,
        // not "some other file".
        val env = envelope(bytes) ?: return if (looksLikeBackup(bytes)) Opened.Corrupt else Opened.NotBackup
        val header = runCatching { headerOf(env) }.getOrNull() ?: return Opened.Corrupt
        if (header.format > FORMAT) return Opened.TooNew(header)
        if (header.format < 1) return Opened.Corrupt
        val payload = runCatching { B64.decode(env.getString("payload")) }.getOrNull() ?: return Opened.Corrupt
        val digest = env.optString("payload_sha256", "")
        if (!digest.equals(hex(sha256(payload)), ignoreCase = true)) return Opened.Corrupt
        if (env.optString("compression") != GZIP) return Opened.Corrupt
        val packed: ByteArray = when (env.optString("encryption")) {
            ENC_NONE -> payload
            ENC_AES -> {
                val secret = password?.let(::normalizePassword)?.takeIf { it.isNotEmpty() } ?: return Opened.NeedsPassword
                if (env.optString("kdf") != KDF) return Opened.Corrupt
                if (env.optInt("password_form", -1) != PASSWORD_FORM) return Opened.Corrupt
                val iterations = env.optInt("kdf_iterations", -1)
                if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return Opened.Corrupt
                val salt = runCatching { B64.decode(env.getString("salt")) }.getOrNull()?.takeIf { it.size >= 8 } ?: return Opened.Corrupt
                val iv = runCatching { B64.decode(env.getString("iv")) }.getOrNull()?.takeIf { it.size == 12 } ?: return Opened.Corrupt
                val key = pbkdf2(secret.toByteArray(Charsets.UTF_8), salt, iterations)
                try {
                    gcm(Cipher.DECRYPT_MODE, key, iv, aad(env), payload)
                } catch (_: AEADBadTagException) {
                    // The digest matched, so these are the bytes that were written: the key is wrong.
                    return Opened.WrongPassword
                } catch (_: Exception) {
                    return Opened.Corrupt
                }
            }
            else -> return Opened.Corrupt
        }
        val text = runCatching { gunzip(packed) }.getOrNull() ?: return Opened.Corrupt
        val content = runCatching { JSONObject(String(text, Charsets.UTF_8)) }.getOrNull() ?: return Opened.Corrupt
        return Opened.Ok(header, content)
    }

    private fun envelope(bytes: ByteArray): JSONObject? {
        if (bytes.isEmpty() || bytes.size > MAX_FILE_BYTES) return null
        // A JSON object, starting at its first non-blank byte. Anything else is some other file.
        val first = bytes.firstOrNull { it != ' '.code.toByte() && it != '\n'.code.toByte() && it != '\r'.code.toByte() && it != '\t'.code.toByte() }
        if (first != '{'.code.toByte()) return null
        val env = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return null
        return env.takeIf { it.has(MAGIC) && it.opt(MAGIC) is Number }
    }

    private fun looksLikeBackup(bytes: ByteArray): Boolean =
        String(bytes, 0, minOf(bytes.size, 256), Charsets.UTF_8).contains("\"$MAGIC\"")

    private fun headerOf(env: JSONObject) = Header(
        format = env.getInt(MAGIC),
        appVersion = env.optString("app_version", ""),
        appBuild = env.optLong("app_build", 0L),
        createdAt = env.optLong("created_at", 0L),
        encrypted = env.optString("encryption") == ENC_AES,
    )

    /**
     * Every header field the content depends on, in a fixed order with fixed separators -- not the
     * envelope's `toString()`, whose key order differs between JSON libraries.
     */
    private fun aad(env: JSONObject): ByteArray = buildString {
        for (k in listOf(MAGIC, "app_version", "app_build", "created_at", "compression", "encryption", "kdf", "kdf_iterations", "salt", "iv", "password_form")) {
            append(k).append('=').append(env.opt(k)?.toString().orEmpty().replace('\n', ' ')).append('\n')
        }
    }.toByteArray(Charsets.UTF_8)

    /**
     * One spelling for what one person would call the same password: NFC; Persian and Arabic-Indic
     * digits as ASCII; Arabic yeh and kaf as the Persian letters; the invisible joiners and marks a
     * keyboard may or may not insert dropped; the ends trimmed.
     */
    fun normalizePassword(raw: String): String {
        val out = StringBuilder(raw.length)
        for (ch in Normalizer.normalize(raw, Normalizer.Form.NFC)) {
            when (ch) {
                in '۰'..'۹' -> out.append('0' + (ch - '۰'))
                in '٠'..'٩' -> out.append('0' + (ch - '٠'))
                'ي', 'ى' -> out.append('ی')
                'ك' -> out.append('ک')
                '‌', '‍', '‎', '‏', '﻿' -> Unit
                else -> out.append(ch)
            }
        }
        return out.toString().trim()
    }

    /** PBKDF2 with HMAC-SHA256 (RFC 8018), by hand: `PBKDF2WithHmacSHA256` exists only from API 26. */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int = 32): ByteArray {
        require(password.isNotEmpty() && iterations >= 1 && length in 1..64)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = mac.macLength
        val out = ByteArray(length)
        val u = ByteArray(hLen)
        val t = ByteArray(hLen)
        var block = 1
        var pos = 0
        while (pos < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            mac.doFinal(u, 0)
            System.arraycopy(u, 0, t, 0, hLen)
            for (i in 2..iterations) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (k in 0 until hLen) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val n = minOf(hLen, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            block++
        }
        return out
    }

    private fun gcm(mode: Int, key: ByteArray, iv: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return c.doFinal(input)
    }

    fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(data.size / 3 + 64)
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    fun gunzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size * 3)
        GZIPInputStream(ByteArrayInputStream(data)).use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                check(out.size() <= MAX_CONTENT_BYTES) { "content too large" }
            }
        }
        return out.toByteArray()
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hex(data: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(data.size * 2)
        for (b in data) {
            sb.append(digits[(b.toInt() ushr 4) and 15]).append(digits[b.toInt() and 15])
        }
        return sb.toString()
    }
}

/**
 * Standard Base64 with padding, by hand: `java.util.Base64` exists only from API 26 and
 * `android.util.Base64` not at all in unit tests. Decoding skips whitespace and refuses anything else
 * outside the alphabet.
 */
internal object B64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val INDEX = IntArray(128) { -1 }.also { for (i in ALPHABET.indices) it[ALPHABET[i].code] = i }

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val n = ((data[i].toInt() and 0xff) shl 16) or ((data[i + 1].toInt() and 0xff) shl 8) or (data[i + 2].toInt() and 0xff)
            out.append(ALPHABET[(n ushr 18) and 63]).append(ALPHABET[(n ushr 12) and 63])
                .append(ALPHABET[(n ushr 6) and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xff) shl 16
                out.append(ALPHABET[(n ushr 18) and 63]).append(ALPHABET[(n ushr 12) and 63]).append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xff) shl 16) or ((data[i + 1].toInt() and 0xff) shl 8)
                out.append(ALPHABET[(n ushr 18) and 63]).append(ALPHABET[(n ushr 12) and 63])
                    .append(ALPHABET[(n ushr 6) and 63]).append('=')
            }
        }
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        val clean = StringBuilder(text.length)
        for (ch in text) if (!ch.isWhitespace()) clean.append(ch)
        require(clean.length % 4 == 0) { "base64 length" }
        val pad = when {
            clean.endsWith("==") -> 2
            clean.endsWith("=") -> 1
            else -> 0
        }
        val out = ByteArray(clean.length / 4 * 3 - pad)
        var o = 0
        var i = 0
        while (i < clean.length) {
            var n = 0
            for (j in 0 until 4) {
                val ch = clean[i + j]
                val v = if (ch == '=') {
                    require(i + j >= clean.length - pad) { "base64 padding" }
                    0
                } else {
                    require(ch.code < 128 && INDEX[ch.code] >= 0) { "base64 character" }
                    INDEX[ch.code]
                }
                n = (n shl 6) or v
            }
            if (o < out.size) out[o++] = (n ushr 16).toByte()
            if (o < out.size) out[o++] = (n ushr 8).toByte()
            if (o < out.size) out[o++] = n.toByte()
            i += 4
        }
        return out
    }
}
