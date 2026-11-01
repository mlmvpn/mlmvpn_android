package com.mlmvpn.scanner.engines.amnezia

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.TreeMap
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The certificate a Hysteria2 server presents, as the SHA-256 of its DER: what the core then
 * accepts through `pinnedPeerCertSha256`.
 *
 * Three in four public Hysteria2 links say `insecure=1` (2,824 of 3,777 on 2026-10-07): a
 * self-signed certificate, which this Xray core refuses -- it removed `allowInsecure` -- so none of
 * them could ever pass a test. Pinning the certificate the server shows is the core's own
 * replacement, and stricter than what the links asked for: from then on only that one passes.
 *
 * Just enough QUIC v1 (RFC 9000/9001) and TLS 1.3 to read the Certificate message: one Initial
 * with a ClientHello (P-256 key share, TLS_AES_128_GCM_SHA256, ALPN h3), the server's Initial
 * (ServerHello), the handshake keys from the ECDHE secret, then the server's Handshake packets
 * until the Certificate is whole. Nothing is sent after that: no session ever exists, and the
 * server forgets the attempt. Salamander-obfuscated servers (a third of them) are spoken to
 * through their obfuscation, exactly as the core will.
 */
object QuicCertProbe {

    private val INITIAL_SALT = hex("38762cf7f55934b34d179ae6a4c80cadccbb7f0a")
    /** A HelloRetryRequest's fixed "random" (RFC 8446 4.1.3). */
    private val HRR_RANDOM = hex("cf21ad74e59a6111be1d8c021e65b891c2a211167abb8c5e079e09e2c8a8339c")
    private val rnd = SecureRandom()

    /**
     * The leaf certificate's SHA-256 (64 hex digits), or null when the server did not show one in
     * time. [obfsPassword]: the link's salamander password, or "" for none. [bind] ties the socket
     * to a network before it sends (the bare line, never a tunnel).
     */
    fun fetch(address: InetAddress, port: Int, sni: String, obfsPassword: String = "", timeoutMs: Int = 3_000, bind: (DatagramSocket) -> Unit = {}): String? =
        runCatching { Session(address, port, sni, obfsPassword.toByteArray(), timeoutMs, bind).run() }.getOrNull()

    private class Keys(val key: ByteArray, val iv: ByteArray, val hp: ByteArray)

    private class Session(val address: InetAddress, val port: Int, val sni: String, val psk: ByteArray, val timeoutMs: Int, val bind: (DatagramSocket) -> Unit) {
        /** The connection id the Initial keys come from: ours at first, the Retry's after one. */
        var keyCid = ByteArray(8).also { rnd.nextBytes(it) }
        /** Where our packets go: [keyCid] until the server names its own. */
        var destCid = keyCid
        val scid = ByteArray(8).also { rnd.nextBytes(it) }
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val clientHello = clientHello()
        lateinit var clientInitial: Keys
        lateinit var serverInitial: Keys
        var serverHandshake: Keys? = null
        val initialCrypto = Stream()
        val handshakeCrypto = Stream()
        val pending = ArrayList<ByteArray>()
        var nextPn = 0L
        var largestInitialPn = -1L
        var acks = 0
        var retried = false

        fun run(): String? {
            DatagramSocket().use { sock ->
                bind(sock)
                sock.connect(InetSocketAddress(address, port))
                sendInitial(sock, ByteArray(0))
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (true) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) return null
                    sock.soTimeout = left.toInt().coerceAtLeast(1)
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (_: SocketTimeoutException) { return null }
                    val d = deobfuscate(buf.copyOf(p.length)) ?: continue
                    when (val r = datagram(d)) {
                        is Step.Retry -> {
                            if (retried) return null
                            retried = true
                            keyCid = r.scid
                            destCid = r.scid
                            sendInitial(sock, r.token)
                            continue
                        }
                        Step.Fail -> return null
                        Step.Ok -> Unit
                    }
                    certificate()?.let { return it }
                    // A long chain may not fit what a server sends to an address it has not
                    // validated yet (3x what it got): each padded datagram from us raises that.
                    if (largestInitialPn >= 0 && acks < 3) { acks++; sendAck(sock) }
                }
            }
        }

        fun send(sock: DatagramSocket, packet: ByteArray) {
            val out = obfuscate(packet)
            sock.send(DatagramPacket(out, out.size))
        }

        fun sendInitial(sock: DatagramSocket, token: ByteArray) {
            val secret = hkdfExtract(INITIAL_SALT, keyCid)
            clientInitial = keys(expandLabel(secret, "client in", ByteArray(0), 32))
            serverInitial = keys(expandLabel(secret, "server in", ByteArray(0), 32))
            val frame = ByteArrayOutputStream().apply {
                write(0x06); write(varint(0)); write(varint(clientHello.size.toLong())); write(clientHello)
            }.toByteArray()
            send(sock, packet(token, nextPn++, frame))
        }

        fun sendAck(sock: DatagramSocket) {
            val ack = ByteArrayOutputStream().apply {
                write(0x02); write(varint(largestInitialPn)); write(varint(0)); write(varint(0)); write(varint(largestInitialPn))
            }.toByteArray()
            send(sock, packet(ByteArray(0), nextPn++, ack))
        }

        /** One client Initial packet, padded to 1200 bytes, encrypted and header-protected. */
        fun packet(token: ByteArray, pn: Long, frames: ByteArray): ByteArray {
            val head = ByteArrayOutputStream().apply {
                write(0xC3); write(byteArrayOf(0, 0, 0, 1))
                write(destCid.size); write(destCid); write(scid.size); write(scid)
                write(varint(token.size.toLong())); write(token)
            }.toByteArray()
            // The rest of the payload is PADDING frames (zero bytes).
            val payload = frames.copyOf(maxOf(frames.size, 1200 - head.size - 2 - 4 - 16))
            val len = 4 + payload.size + 16
            val header = head + byteArrayOf((0x40 or (len shr 8)).toByte(), len.toByte()) +
                byteArrayOf((pn shr 24).toByte(), (pn shr 16).toByte(), (pn shr 8).toByte(), pn.toByte())
            val out = header + gcm(true, clientInitial.key, nonce(clientInitial.iv, pn), header, payload)
            val pnOffset = header.size - 4
            val mask = ecb(clientInitial.hp, out.copyOfRange(pnOffset + 4, pnOffset + 20))
            out[0] = (out[0].toInt() xor (mask[0].toInt() and 0x0f)).toByte()
            for (i in 0 until 4) out[pnOffset + i] = (out[pnOffset + i].toInt() xor mask[1 + i].toInt()).toByte()
            return out
        }

        sealed class Step {
            object Ok : Step()
            object Fail : Step()
            class Retry(val token: ByteArray, val scid: ByteArray) : Step()
        }

        /** Every long-header packet in one datagram (a server coalesces its Initial and Handshake). */
        fun datagram(d: ByteArray): Step {
            var off = 0
            while (off + 7 < d.size) {
                val b0 = d[off].toInt() and 0xff
                if (b0 and 0x80 == 0) break // a short-header packet: nothing of ours is in it
                val version = ((d[off + 1].toInt() and 0xff) shl 24) or ((d[off + 2].toInt() and 0xff) shl 16) or
                    ((d[off + 3].toInt() and 0xff) shl 8) or (d[off + 4].toInt() and 0xff)
                if (version == 0) return Step.Fail // version negotiation: no QUIC v1 here
                if (version != 1) break
                val type = (b0 shr 4) and 3
                var p = off + 5
                p += 1 + (d[p].toInt() and 0xff)
                val scl = d[p].toInt() and 0xff
                val serverCid = d.copyOfRange(p + 1, p + 1 + scl); p += 1 + scl
                if (type == 3) return if (d.size - 16 > p) Step.Retry(d.copyOfRange(p, d.size - 16), serverCid) else Step.Fail
                if (type == 0) { val (tl, n) = readVarint(d, p); p += n + tl.toInt() }
                val (length, ln) = readVarint(d, p); p += ln
                val end = p + length.toInt()
                if (end > d.size || length < 20) break
                when (type) {
                    0 -> { destCid = serverCid; open(d, off, p, end, serverInitial, initialCrypto, initial = true) }
                    2 -> serverHandshake?.let { open(d, off, p, end, it, handshakeCrypto, initial = false) } ?: run { pending += d.copyOfRange(off, end) }
                }
                off = end
            }
            advance()
            return Step.Ok
        }

        fun open(d: ByteArray, start: Int, pnOffset: Int, end: Int, keys: Keys, crypto: Stream, initial: Boolean) {
            val mask = ecb(keys.hp, d.copyOfRange(pnOffset + 4, pnOffset + 20))
            val first = (d[start].toInt() xor (mask[0].toInt() and 0x0f)) and 0xff
            val pnLen = (first and 3) + 1
            val header = d.copyOfRange(start, pnOffset + pnLen)
            header[0] = first.toByte()
            var pn = 0L
            for (i in 0 until pnLen) {
                val b = (d[pnOffset + i].toInt() xor mask[1 + i].toInt()) and 0xff
                header[pnOffset - start + i] = b.toByte()
                pn = (pn shl 8) or b.toLong()
            }
            val plain = runCatching { gcm(false, keys.key, nonce(keys.iv, pn), header, d.copyOfRange(pnOffset + pnLen, end)) }.getOrNull() ?: return
            if (initial && pn > largestInitialPn) largestInitialPn = pn
            frames(plain, crypto)
        }

        fun frames(f: ByteArray, crypto: Stream) {
            var p = 0
            while (p < f.size) {
                val (t, n) = readVarint(f, p); p += n
                when (t.toInt()) {
                    0x00, 0x01 -> Unit
                    0x02, 0x03 -> {
                        repeat(2) { p += readVarint(f, p).second }
                        val (count, c) = readVarint(f, p); p += c
                        p += readVarint(f, p).second
                        repeat(count.toInt() * 2) { p += readVarint(f, p).second }
                        if (t.toInt() == 0x03) repeat(3) { p += readVarint(f, p).second }
                    }
                    0x06 -> {
                        val (o, a) = readVarint(f, p); p += a
                        val (l, b) = readVarint(f, p); p += b
                        if (p + l.toInt() > f.size) return
                        crypto.put(o, f.copyOfRange(p, p + l.toInt())); p += l.toInt()
                    }
                    else -> return // CONNECTION_CLOSE, or something a probe does not need
                }
            }
        }

        /** Once the ServerHello is whole: the handshake keys, and the packets that waited for them. */
        fun advance() {
            if (serverHandshake != null) return
            val sh = message(initialCrypto.bytes(), 2) ?: return
            val body = sh.copyOfRange(4, sh.size)
            if (body.size < 38 || body.copyOfRange(2, 34).contentEquals(HRR_RANDOM)) return
            var p = 34
            p += 1 + (body[p].toInt() and 0xff)
            val suite = u16at(body, p)
            if (suite != 0x1301) return
            p += 3
            val extEnd = p + 2 + u16at(body, p); p += 2
            var peer: ByteArray? = null
            while (p + 4 <= extEnd && p + 4 <= body.size) {
                val et = u16at(body, p)
                val el = u16at(body, p + 2)
                if (et == 51 && p + 8 <= body.size) {
                    val kl = u16at(body, p + 6)
                    if (p + 8 + kl <= body.size) peer = body.copyOfRange(p + 8, p + 8 + kl)
                }
                p += 4 + el
            }
            val point = peer ?: return
            if (point.size != 65 || point[0].toInt() != 4) return
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(kp.private)
            val params = (kp.public as ECPublicKey).params
            val spec = ECPublicKeySpec(ECPoint(BigInteger(1, point.copyOfRange(1, 33)), BigInteger(1, point.copyOfRange(33, 65))), params)
            ka.doPhase(KeyFactory.getInstance("EC").generatePublic(spec), true)
            val shared = ka.generateSecret()
            val zeros = ByteArray(32)
            val early = hkdfExtract(zeros, zeros)
            val derived = expandLabel(early, "derived", sha256(ByteArray(0)), 32)
            val hs = hkdfExtract(derived, shared)
            serverHandshake = keys(expandLabel(hs, "s hs traffic", sha256(clientHello + sh), 32))
            val waiting = pending.toList(); pending.clear()
            for (pkt in waiting) datagram(pkt)
        }

        /** The SHA-256 of the first (leaf) certificate, once the Certificate message is whole. */
        fun certificate(): String? {
            val m = message(handshakeCrypto.bytes(), 11) ?: return null
            var p = 4
            p += 1 + (m[p].toInt() and 0xff) // certificate_request_context
            p += 3 // certificate_list length
            if (p + 3 > m.size) return null
            val len = ((m[p].toInt() and 0xff) shl 16) or ((m[p + 1].toInt() and 0xff) shl 8) or (m[p + 2].toInt() and 0xff)
            if (len <= 0 || p + 3 + len > m.size) return null
            return sha256(m.copyOfRange(p + 3, p + 3 + len)).joinToString("") { String.format(java.util.Locale.US, "%02x", it) }
        }

        fun clientHello(): ByteArray {
            val pub = kp.public as ECPublicKey
            val point = byteArrayOf(4) + fixed(pub.w.affineX) + fixed(pub.w.affineY)
            val ext = ByteArrayOutputStream()
            fun e(type: Int, body: ByteArray) { ext.write(u16(type)); ext.write(u16(body.size)); ext.write(body) }
            // No server_name for a literal address: the core sends none either.
            if (sni.isNotEmpty() && !sni.contains(':') && !sni.all { it.isDigit() || it == '.' }) {
                val n = sni.toByteArray()
                e(0, u16(n.size + 3) + byteArrayOf(0) + u16(n.size) + n)
            }
            e(10, u16(2) + u16(0x0017))
            val sig = intArrayOf(0x0403, 0x0804, 0x0401, 0x0503, 0x0805, 0x0501, 0x0806, 0x0601, 0x0807)
            e(13, u16(sig.size * 2) + sig.fold(ByteArray(0)) { a, s -> a + u16(s) })
            e(16, u16(3) + byteArrayOf(2) + "h3".toByteArray())
            e(43, byteArrayOf(2) + u16(0x0304))
            e(45, byteArrayOf(1, 1))
            e(51, u16(point.size + 4) + u16(0x0017) + u16(point.size) + point)
            val tp = ByteArrayOutputStream().apply {
                fun param(id: Long, v: ByteArray) { write(varint(id)); write(varint(v.size.toLong())); write(v) }
                param(0x01, varint(30_000)); param(0x04, varint(1L shl 20)); param(0x05, varint(1L shl 18))
                param(0x06, varint(1L shl 18)); param(0x07, varint(1L shl 18)); param(0x08, varint(100)); param(0x09, varint(100))
                param(0x0f, scid)
            }.toByteArray()
            e(0x39, tp)
            val random = ByteArray(32).also { rnd.nextBytes(it) }
            val body = ByteArrayOutputStream().apply {
                write(u16(0x0303)); write(random); write(0)
                write(u16(2)); write(u16(0x1301)); write(1); write(0)
                val x = ext.toByteArray(); write(u16(x.size)); write(x)
            }.toByteArray()
            return byteArrayOf(1, (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body
        }

        /** Hysteria2's salamander: an 8-byte salt, then the packet XORed with BLAKE2b-256(password + salt). */
        fun obfuscate(packet: ByteArray): ByteArray {
            if (psk.isEmpty()) return packet
            val salt = ByteArray(8).also { rnd.nextBytes(it) }
            val key = Blake2b.hash256(psk + salt)
            return salt + ByteArray(packet.size) { i -> (packet[i].toInt() xor key[i % 32].toInt()).toByte() }
        }

        fun deobfuscate(d: ByteArray): ByteArray? {
            if (psk.isEmpty()) return d
            if (d.size <= 8) return null
            val key = Blake2b.hash256(psk + d.copyOfRange(0, 8))
            return ByteArray(d.size - 8) { i -> (d[8 + i].toInt() xor key[i % 32].toInt()).toByte() }
        }
    }

    /** A CRYPTO stream, reassembled from frames that may come out of order or twice. */
    private class Stream {
        private val parts = TreeMap<Long, ByteArray>()
        fun put(offset: Long, data: ByteArray) {
            if (data.isNotEmpty() && (parts[offset]?.size ?: -1) < data.size) parts[offset] = data
        }
        fun bytes(): ByteArray {
            val out = ByteArrayOutputStream()
            var at = 0L
            for ((o, d) in parts) {
                if (o > at) break
                val skip = (at - o).toInt()
                if (skip < d.size) { out.write(d, skip, d.size - skip); at = o + d.size }
            }
            return out.toByteArray()
        }
    }

    /** The first whole handshake message of [type] in [data], header included, or null. */
    private fun message(data: ByteArray, type: Int): ByteArray? {
        var p = 0
        while (p + 4 <= data.size) {
            val t = data[p].toInt() and 0xff
            val len = ((data[p + 1].toInt() and 0xff) shl 16) or ((data[p + 2].toInt() and 0xff) shl 8) or (data[p + 3].toInt() and 0xff)
            if (p + 4 + len > data.size) return null
            if (t == type) return data.copyOfRange(p, p + 4 + len)
            p += 4 + len
        }
        return null
    }

    private fun keys(secret: ByteArray) = Keys(
        expandLabel(secret, "quic key", ByteArray(0), 16),
        expandLabel(secret, "quic iv", ByteArray(0), 12),
        expandLabel(secret, "quic hp", ByteArray(0), 16),
    )

    /** The client's Initial key, IV and header key for [dcid] (RFC 9001 A.1 checks these). */
    internal fun clientInitialKeys(dcid: ByteArray): List<ByteArray> =
        keys(expandLabel(hkdfExtract(INITIAL_SALT, dcid), "client in", ByteArray(0), 32)).let { listOf(it.key, it.iv, it.hp) }

    private fun nonce(iv: ByteArray, pn: Long): ByteArray {
        val n = iv.copyOf()
        for (i in 0 until 8) n[11 - i] = (n[11 - i].toInt() xor ((pn shr (8 * i)).toInt() and 0xff)).toByte()
        return n
    }

    private fun gcm(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return c.doFinal(data)
    }

    private fun ecb(key: ByteArray, sample: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }.doFinal(sample)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray) = hmac(salt, ikm)

    private fun expandLabel(secret: ByteArray, label: String, context: ByteArray, length: Int): ByteArray {
        val full = "tls13 $label".toByteArray()
        val info = u16(length) + byteArrayOf(full.size.toByte()) + full + byteArrayOf(context.size.toByte()) + context
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmac(secret, t + info + byteArrayOf(i.toByte()))
            out.write(t); i++
        }
        return out.toByteArray().copyOf(length)
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    private fun fixed(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun u16at(b: ByteArray, p: Int) = ((b[p].toInt() and 0xff) shl 8) or (b[p + 1].toInt() and 0xff)

    private fun varint(v: Long): ByteArray = when {
        v < 64 -> byteArrayOf(v.toByte())
        v < 16384 -> byteArrayOf((0x40 or (v shr 8).toInt()).toByte(), v.toByte())
        v < 1073741824 -> byteArrayOf((0x80 or (v shr 24).toInt()).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        else -> ByteArray(8) { i -> (v shr (8 * (7 - i))).toByte() }.also { it[0] = (it[0].toInt() or 0xC0).toByte() }
    }

    private fun readVarint(b: ByteArray, p: Int): Pair<Long, Int> {
        val first = b[p].toInt() and 0xff
        val len = 1 shl (first shr 6)
        var v = (first and 0x3f).toLong()
        for (i in 1 until len) v = (v shl 8) or (b[p + i].toLong() and 0xff)
        return v to len
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Unkeyed BLAKE2b-256 (RFC 7693), for salamander. Android ships no BLAKE2. */
    internal object Blake2b {
        private val IV = longArrayOf(
            0x6a09e667f3bcc908L, -0x4498517a7b3558c5L, 0x3c6ef372fe94f82bL, -0x5ab00ac5a0e2c90fL,
            0x510e527fade682d1L, -0x64fa9773d4c193e1L, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L,
        )
        private val SIGMA = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
            intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
            intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
            intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
            intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
            intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
            intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
            intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
            intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
        )

        fun hash256(input: ByteArray): ByteArray {
            val h = IV.copyOf()
            h[0] = h[0] xor 0x01010020L // digest length 32, no key, fanout 1, depth 1
            var off = 0
            var t = 0L
            while (input.size - off > 128) {
                t += 128
                compress(h, input, off, t, false)
                off += 128
            }
            val last = ByteArray(128)
            System.arraycopy(input, off, last, 0, input.size - off)
            t += input.size - off
            compress(h, last, 0, t, true)
            return ByteArray(32) { i -> (h[i / 8] ushr (8 * (i % 8))).toByte() }
        }

        private fun compress(h: LongArray, block: ByteArray, off: Int, t: Long, final: Boolean) {
            val m = LongArray(16) { i ->
                var v = 0L
                for (b in 7 downTo 0) v = (v shl 8) or (block[off + i * 8 + b].toLong() and 0xff)
                v
            }
            val v = LongArray(16)
            System.arraycopy(h, 0, v, 0, 8)
            System.arraycopy(IV, 0, v, 8, 8)
            v[12] = v[12] xor t
            if (final) v[14] = v[14].inv()
            for (r in 0 until 12) {
                val s = SIGMA[r % 10]
                g(v, 0, 4, 8, 12, m[s[0]], m[s[1]]); g(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
                g(v, 2, 6, 10, 14, m[s[4]], m[s[5]]); g(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
                g(v, 0, 5, 10, 15, m[s[8]], m[s[9]]); g(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
                g(v, 2, 7, 8, 13, m[s[12]], m[s[13]]); g(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
            }
            for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
        }

        private fun g(v: LongArray, a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
            v[a] = v[a] + v[b] + x; v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 32)
            v[c] = v[c] + v[d]; v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 24)
            v[a] = v[a] + v[b] + y; v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 16)
            v[c] = v[c] + v[d]; v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 63)
        }
    }
}
