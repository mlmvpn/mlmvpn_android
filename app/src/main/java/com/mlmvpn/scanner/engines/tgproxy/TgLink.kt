package com.mlmvpn.scanner.engines.tgproxy

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The secret, the host and the links of a Telegram WEB proxy. Pure Kotlin, covered by unit tests
 * against the vectors Telegram publishes for the bridge capability.
 *
 * A WEB proxy link is `tg://webproxy?server=<host>&secret=<hex>`: no port (it is always 443), no
 * user name. The secret is the ordinary MTProxy secret, 32 hex characters, or `dd` + 32 hex for the
 * random-padding mode. Fake-TLS (`ee...`) secrets are not offered: a Worker cannot terminate them.
 */
object TgLink {

    /** Every resource this feature creates in a Cloudflare account starts with this. */
    const val PREFIX = "tgp-"

    private val SECRET = Regex("^(dd)?[0-9a-f]{32}$")
    private val HOST = Regex("^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** A fresh random-padding secret: `dd` + 16 random bytes in hex. */
    fun newSecret(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return "dd" + hex(bytes)
    }

    fun isSecret(text: String): Boolean = SECRET.matches(text)

    /** True for a canonical lower-case DNS name. */
    fun isHost(text: String): Boolean = HOST.matches(text)

    /** True when the secret asks for the padded transport (and so for the `dd` tag). */
    fun isPadded(secret: String): Boolean = secret.startsWith("dd")

    /** The 16 bytes obfuscated2 mixes into its keys: the secret without the `dd` marker. */
    fun keyBytes(secret: String): ByteArray = unhex(secret.removePrefix("dd"))

    /**
     * `base64url(HMAC-SHA256(secret, "tdesktop-web-proxy-bridge-v1\n" + host))`, the proof of
     * knowing the secret that makes the server show its bridge page instead of a 404. The key is the
     * secret as written, including the `dd` byte.
     */
    fun capability(host: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(unhex(secret), "HmacSHA256"))
        return b64url(mac.doFinal("tdesktop-web-proxy-bridge-v1\n$host".toByteArray(Charsets.UTF_8)))
    }

    fun bridgeUrl(host: String, secret: String): String = "https://$host/?bridge=${capability(host, secret)}"

    /** The link Telegram opens directly. */
    fun appLink(host: String, secret: String): String = "tg://webproxy?server=$host&secret=$secret"

    /** The https form, for sharing with someone whose Telegram handles t.me links. */
    fun webLink(host: String, secret: String): String = "https://t.me/webproxy?server=$host&secret=$secret"

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun unhex(text: String): ByteArray {
        require(text.length % 2 == 0) { "odd hex" }
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun b64url(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            val n = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63])
            if (i + 1 < bytes.size) sb.append(ALPHABET[(n shr 6) and 63])
            if (i + 2 < bytes.size) sb.append(ALPHABET[n and 63])
            i += 3
        }
        return sb.toString()
    }
}
