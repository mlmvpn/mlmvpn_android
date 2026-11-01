package com.mlmvpn.scanner.engines.tgproxy

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The shared frames of the WEB proxy protocol: `type:u8 | stream:u24 | length:u32 | payload`,
 * big-endian. Only what the self-test needs: a client opens a stream, sends a few bytes, reads the
 * answer.
 */
object TgFrames {
    const val OPEN = 1
    const val DATA = 2
    const val CLOSE = 3
    const val WINDOW = 4
    const val HELLO = 0x10
    const val WELCOME = 0x11

    /** One complete HELLO frame: type 0x10, stream 0, a single payload byte 1. */
    val HELLO_FRAME: ByteArray = byteArrayOf(0x10, 0, 0, 0, 0, 0, 0, 1, 1)

    class Frame(val type: Int, val stream: Int, val payload: ByteArray)

    fun encode(type: Int, stream: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(8 + payload.size)
        out[0] = type.toByte()
        out[1] = (stream ushr 16).toByte()
        out[2] = (stream ushr 8).toByte()
        out[3] = stream.toByte()
        out[4] = (payload.size ushr 24).toByte()
        out[5] = (payload.size ushr 16).toByte()
        out[6] = (payload.size ushr 8).toByte()
        out[7] = payload.size.toByte()
        payload.copyInto(out, 8)
        return out
    }

    /** Splits a batch; returns null when it is malformed (short, oversized, over 4096 frames). */
    fun parse(bytes: ByteArray): List<Frame>? {
        val frames = ArrayList<Frame>()
        var at = 0
        while (at < bytes.size) {
            if (bytes.size - at < 8 || frames.size >= 4096) return null
            val size = ((bytes[at + 4].toInt() and 0xFF) shl 24) or ((bytes[at + 5].toInt() and 0xFF) shl 16) or
                ((bytes[at + 6].toInt() and 0xFF) shl 8) or (bytes[at + 7].toInt() and 0xFF)
            if (size < 0 || size > 1024 * 1024 || at + 8 + size > bytes.size) return null
            frames += Frame(
                bytes[at].toInt() and 0xFF,
                ((bytes[at + 1].toInt() and 0xFF) shl 16) or ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF),
                bytes.copyOfRange(at + 8, at + 8 + size),
            )
            at += 8 + size
        }
        return if (frames.isEmpty()) null else frames
    }
}

/**
 * A Telegram client's end of an obfuscated2 stream to an MTProxy -- the 64-byte header and the two
 * keystreams -- built the way Telegram builds it. The self-test uses it to ask a data centre for
 * `res_pq` through the whole proxy path.
 */
class TgObfs2Client(secret16: ByteArray, dc: Int, tag: Int, random: SecureRandom = SecureRandom()) {

    /** The 64 bytes that open the stream. */
    val header: ByteArray

    private val up: Cipher
    private val down: Cipher

    init {
        val init = ByteArray(64)
        do {
            random.nextBytes(init)
        } while (!usable(init))
        // The transport tag at 56..60 and the data-centre id (signed, little-endian) at 60..62.
        init[56] = (tag ushr 24).toByte(); init[57] = (tag ushr 16).toByte(); init[58] = (tag ushr 8).toByte(); init[59] = tag.toByte()
        init[60] = dc.toByte(); init[61] = (dc ushr 8).toByte()

        val upKey = sha256(init.copyOfRange(8, 40) + secret16)
        val reversed = init.copyOfRange(8, 56).reversedArray()
        val downKey = sha256(reversed.copyOfRange(0, 32) + secret16)
        up = aes(upKey, init.copyOfRange(40, 56))
        down = aes(downKey, reversed.copyOfRange(32, 48))
        val sealed = up.update(init)
        header = init.copyOf().also { sealed.copyInto(it, 56, 56, 64) }
    }

    /** Encrypts what goes to the proxy; the keystream continues across calls. */
    fun seal(data: ByteArray): ByteArray = up.update(data)

    /** Decrypts what the proxy sends back; the keystream continues across calls. */
    fun open(data: ByteArray): ByteArray = down.update(data)

    private fun usable(init: ByteArray): Boolean {
        if ((init[0].toInt() and 0xFF) == 0xEF) return false
        val first = (init[0].toInt() and 0xFF) or ((init[1].toInt() and 0xFF) shl 8) or
            ((init[2].toInt() and 0xFF) shl 16) or ((init[3].toInt() and 0xFF) shl 24)
        if (first in FORBIDDEN) return false
        return init[4].toInt() != 0 || init[5].toInt() != 0 || init[6].toInt() != 0 || init[7].toInt() != 0
    }

    private fun aes(key: ByteArray, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    companion object {
        /** 'HEAD', 'POST', 'GET ', 'OPTI', the other two transport tags, and a TLS record start. */
        private val FORBIDDEN = setOf(0x44414548, 0x54534f50, 0x20544547, 0x4954504f, 0xdddddddd.toInt(), 0xeeeeeeee.toInt(), 0x02010316)
    }
}
