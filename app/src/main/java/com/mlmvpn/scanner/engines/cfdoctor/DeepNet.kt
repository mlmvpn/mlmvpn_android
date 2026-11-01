package com.mlmvpn.scanner.engines.cfdoctor

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/*
 * Doctor v3's wire layer: one HTTP/1.1 exchange over a chosen WAY, taken apart phase by phase.
 *
 * A way = where the address comes from (the phone's resolver, a DoH server, a fixed Cloudflare
 * edge range, the app's own edge picker, IPv6) x how TLS is spoken (plain, TLS 1.2 / 1.3 only,
 * mixed-case or no SNI, the ClientHello cut up in several ways). Every stage of the Doctor runs
 * its REAL request (the account check, a subscription, a worker upload) over every way, so the
 * report says for each user which ways carried it and, for the ones that did not, exactly where
 * they stopped: no address, no TCP, TLS cut, sent but silent, or an answer from Cloudflare.
 *
 * Deliberately not built on ProbeSession: its one-connection-per-IP lease made stage tests wait
 * behind the network sweep and report the wait as "tcp" (Doctor v2, 2026-10-03 reports).
 */

/** How the address is found. */
enum class AddrSource(val key: String) {
    SYSTEM("sys"),          // the phone's resolver: what every app does
    APP_EDGE("cfedge"),     // the app's own per-network edge picker (CfEdge)
    DOH_GOOGLE("doh8"),     // DoH JSON to 8.8.8.8
    DOH_ADGUARD("doh94"),   // DoH JSON to 94.140.14.14
    UDP_GOOGLE("udp8"),     // plain DNS to 8.8.8.8 over UDP
    EDGE("edge"),           // a fixed Cloudflare range ([Way.group])
    V6("v6"),               // a Cloudflare IPv6 edge
}

/** How TLS is spoken. */
enum class TlsVariant(val key: String, val recipe: SplitRecipe = SplitRecipe(), val protocol: String? = null) {
    PLAIN("tls"),
    TLS12("tls12", protocol = "TLSv1.2"),
    TLS13("tls13", protocol = "TLSv1.3"),
    SNI_MIXED("sni_mixed"),
    NO_SNI("no_sni"),
    F1("frag1", ClientHelloSplit.recipe("F1")),
    F3("frag3", ClientHelloSplit.recipe("F3")),
    F4("frag_sni", ClientHelloSplit.recipe("F4")),
    F6("frag16d10", ClientHelloSplit.recipe("F6")),
    F8("frag_record", ClientHelloSplit.recipe("F8")),
    F12("frag75d15", ClientHelloSplit.recipe("F12")),
    F14("frag1d1", ClientHelloSplit.recipe("F14")),
}

data class Way(val source: AddrSource, val tls: TlsVariant = TlsVariant.PLAIN, val group: String = "") {
    val id: String get() = buildString {
        append(source.key); if (group.isNotEmpty()) append('_').append(group)
        if (tls != TlsVariant.PLAIN) append('+').append(tls.key)
    }
}

/** What one exchange over one way came to. [phase] is where it stopped; "ok" means an HTTP answer arrived. */
data class WayResult(
    val way: String, val phase: String, val ip: String = "", val http: Int = 0,
    val cfErrors: List<Int> = emptyList(), val ms: Long = 0, val tcpMs: Long = 0, val tlsMs: Long = 0,
    val sent: Long = 0, val received: Long = 0, val body: String = "",
    val headers: Map<String, String> = emptyMap(),
) {
    val answered: Boolean get() = phase == "ok"
}

/** The Cloudflare edge groups the Doctor tries, each a range the reports showed behaving as one. */
object EdgeGroups {
    val GROUPS: Map<String, List<String>> = linkedMapOf(
        "104_16" to listOf("104.16.0.0/14"),
        "104_24" to listOf("104.24.0.0/15"),
        "172_64" to listOf("172.64.0.0/15"),
        "172_67" to listOf("172.67.0.0/16"),
        "104_21" to listOf("104.21.0.0/16"),
        "104_27" to listOf("104.27.0.0/16"),
        "188_114" to listOf("188.114.96.0/22"),
        "162_159" to listOf("162.159.0.0/16"),
        "141_101" to listOf("141.101.113.0/24"),
        "104_20" to listOf("104.20.0.0/16"),
        "172_66" to listOf("172.66.0.0/16"),
    )

    fun random(cidr: String, rnd: java.util.Random = java.security.SecureRandom()): String {
        val (base, bits) = cidr.split('/')
        val b = base.split('.').map { it.toLong() }
        val start = (b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3]
        val size = 1L shl (32 - bits.toInt())
        val ip = start + 1 + Math.floorMod(rnd.nextLong(), size - 2)
        return "${(ip shr 24) and 255}.${(ip shr 16) and 255}.${(ip shr 8) and 255}.${ip and 255}"
    }
}

/**
 * Finds, once per run, an address per edge group that at least takes a TCP connection, plus the
 * DoH/UDP answers for a name. Results are shared by every stage.
 */
class EdgeBook(private val env: NetEnv) {
    private val edges = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val dead = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val answers = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /** A TCP-alive address in [group], or null when [tries] random ones all refused. */
    fun edge(group: String, preferred: List<String> = emptyList(), tries: Int = 4): String? {
        edges[group]?.let { return it }
        if (group in dead) return null
        val cidrs = EdgeGroups.GROUPS[group] ?: return null
        val candidates = (preferred.filter { ip -> cidrs.any { inRange(ip, it) } } + List(tries) { EdgeGroups.random(cidrs.random()) }).distinct()
        for (ip in candidates) if (tcp(ip, 443, 2_500) != null) { edges[group] = ip; return ip }
        dead += group
        return null
    }

    fun knownEdges(): Map<String, String> = edges.toMap()
    fun deadGroups(): Set<String> = dead.toSet()

    fun tcp(ip: String, port: Int, timeout: Int): Long? = runCatching {
        val t = System.nanoTime()
        env.socket().use { it.connect(InetSocketAddress(InetAddress.getByName(ip), port), timeout) }
        (System.nanoTime() - t) / 1_000_000
    }.getOrNull()

    /** The IPv4 addresses [source] gives for [host] (cached per run). */
    fun resolve(source: AddrSource, host: String): List<String> {
        val key = "${source.key}|$host"
        answers[key]?.let { return it }
        val out = runCatching {
            when (source) {
                AddrSource.SYSTEM -> env.resolve(host).mapNotNull { it.hostAddress }.filter { !it.contains(':') }
                AddrSource.DOH_GOOGLE -> dohJson("8.8.8.8", host)
                AddrSource.DOH_ADGUARD -> dohJson("94.140.14.14", host)
                AddrSource.UDP_GOOGLE -> udp("8.8.8.8", host)
                AddrSource.APP_EDGE -> com.mlmvpn.scanner.data.CfEdge.find(null, host, 2)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        answers[key] = out
        return out
    }

    private fun dohJson(server: String, host: String): List<String> {
        val ip = InetAddress.getByName(server)
        val s = env.socket(); s.soTimeout = 5_000
        s.use {
            it.connect(InetSocketAddress(ip, 443), 4_000)
            val tls = TlsChannel(it.getInputStream(), it.getOutputStream(), server, 443, server, context = env.tlsContext())
            HttpWire.request(tls.output, server, "/resolve?name=$host&type=A", headers = mapOf("Accept" to "application/dns-json"))
            val r = HttpWire.response(tls.input, 16_384)
            val a = org.json.JSONObject(String(r.body)).optJSONArray("Answer") ?: return emptyList()
            return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optInt("type") == 1 }.map { it.getString("data") }
        }
    }

    private fun udp(server: String, host: String): List<String> {
        val q = DnsWire.query(host, 1)
        val xid = ((q[0].toInt() and 255) shl 8) or (q[1].toInt() and 255)
        env.datagram().use { s ->
            s.soTimeout = 4_000
            s.connect(InetAddress.getByName(server), 53)
            s.send(java.net.DatagramPacket(q, q.size))
            val p = java.net.DatagramPacket(ByteArray(4096), 4096); s.receive(p)
            return DnsWire.parse(p.data.copyOf(p.length), xid).addresses.filter { !it.contains(':') }
        }
    }

    companion object {
        fun inRange(ip: String, cidr: String): Boolean = runCatching {
            fun n(s: String) = s.split('.').fold(0L) { a, v -> (a shl 8) or v.toLong() }
            val (base, bits) = cidr.split('/')
            val mask = (0xffffffffL shl (32 - bits.toInt())) and 0xffffffffL
            (n(ip) and mask) == (n(base) and mask)
        }.getOrDefault(false)

        /** "104.21.x.x"-style: which range, without the full address of a user's server. */
        fun mask16(ip: String): String = if (ip.contains(':')) ip.split(':').take(2).joinToString(":") + "::/32"
            else ip.split('.').take(2).joinToString(".") + ".x.x"
    }
}

/**
 * The exchange itself. Blocking; every socket is closed by a watchdog at [deadlineMs], so a silent
 * peer costs the deadline and nothing more. Phases: dns, tcp, tls, send, wait (request out, no
 * answer), body, ok.
 */
class WayClient(private val env: NetEnv, private val book: EdgeBook) {

    data class Req(
        val host: String, val path: String, val method: String = "GET",
        val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0),
        val port: Int = 443, val maxBody: Int = 65_536, val deadlineMs: Int = 15_000,
        /** Multipart-style uploads report how far they got: written in 16 KB slices. */
        val trackUpload: Boolean = false,
    )

    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "doctor-way-deadline").apply { isDaemon = true } }

    /** The address [way] uses for [host], or null with the reason. */
    fun addressFor(way: Way, host: String, preferredEdges: List<String> = emptyList()): Pair<String?, String> = when (way.source) {
        AddrSource.EDGE -> book.edge(way.group, preferredEdges)?.let { it to "edge" } ?: (null to "edge.none")
        AddrSource.V6 -> com.mlmvpn.scanner.data.CfFamily.sampleV6(1).firstOrNull()?.let { it to "v6" } ?: (null to "v6.none")
        else -> {
            val ips = book.resolve(way.source, host)
            when {
                ips.isEmpty() -> null to "dns.empty"
                ips.all { CfBogon.isBogon(it) } -> null to "dns.bogon"
                else -> ips.first { !CfBogon.isBogon(it) } to "dns"
            }
        }
    }

    fun exchange(way: Way, req: Req, fixedIp: String? = null, cancelled: () -> Boolean = { false }): WayResult {
        val start = System.nanoTime()
        fun ms() = (System.nanoTime() - start) / 1_000_000
        val (ip, why) = if (fixedIp != null) fixedIp to "fixed" else addressFor(way, req.host)
        if (ip == null) return WayResult(way.id, why, ms = ms())
        if (cancelled() || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("cfnet_cancelled")
        val socket: Socket = try { env.socket() } catch (e: Exception) { return WayResult(way.id, "socket", ip, ms = ms()) }
        val owner = Thread.currentThread()
        val expires = System.nanoTime() + req.deadlineMs.toLong() * 1_000_000
        val timer = watchdog.scheduleAtFixedRate({
            if (cancelled() || owner.isInterrupted || System.nanoTime() >= expires) runCatching { socket.close() }
        }, 50, 50, TimeUnit.MILLISECONDS)
        var phase = "tcp"; var tcpMs = 0L; var tlsMs = 0L; var sent = 0L; var received = 0L
        try {
            socket.soTimeout = req.deadlineMs; socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(InetAddress.getByName(ip), req.port), minOf(req.deadlineMs, 6_000))
            tcpMs = ms()
            var input: InputStream = socket.getInputStream(); var output: OutputStream = socket.getOutputStream()
            if (req.port !in PLAIN_PORTS) {
                phase = "tls"
                val sni = when (way.tls) {
                    TlsVariant.NO_SNI -> ""
                    TlsVariant.SNI_MIXED -> req.host.mapIndexed { i, c -> if (i % 2 == 0) c.uppercaseChar() else c.lowercaseChar() }.joinToString("")
                    else -> req.host
                }
                val t = System.nanoTime()
                val tls = TlsChannel(input, output, req.host, req.port, sni, way.tls.recipe, way.tls.protocol, env.tlsContext())
                tlsMs = (System.nanoTime() - t) / 1_000_000
                input = tls.input; output = tls.output
            }
            phase = "send"
            val counting = object : OutputStream() {
                override fun write(b: Int) { output.write(b); sent++ }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    var p = off
                    while (p < off + len) { val n = minOf(16_384, off + len - p); output.write(b, p, n); output.flush(); sent += n; p += n }
                }
                override fun flush() = output.flush()
            }
            HttpWire.request(counting, req.host, req.path, req.method, req.headers, req.body)
            phase = "wait"
            val head = HttpWire.head(input)
            phase = "body"
            val body = (if (req.method == "HEAD") ByteArray(0) else readBody(input, head, req.maxBody)).also { received = it.size.toLong() }
            val text = String(body, Charsets.UTF_8)
            return WayResult(way.id, "ok", ip, head.status, cfErrors(text), ms(), tcpMs, tlsMs, sent, received, text, head.headers)
        } catch (e: Exception) {
            val p = when {
                e is javax.net.ssl.SSLHandshakeException && phase == "tls" -> "tls.alert"
                generateSequence<Throwable>(e) { it.cause }.take(6).any { it is java.security.cert.CertificateException } -> "tls.cert"
                else -> phase
            }
            return WayResult(way.id, p, ip, ms = ms(), tcpMs = tcpMs, tlsMs = tlsMs, sent = sent, received = received)
        } finally {
            timer.cancel(false); runCatching { socket.close() }
        }
    }

    private fun readBody(input: InputStream, head: HttpWire.Response, limit: Int): ByteArray {
        if (head.status in listOf(101, 204, 304)) return ByteArray(0)
        val out = ByteArrayOutputStream()
        val chunked = head.headers["transfer-encoding"]?.equals("chunked", true) == true
        if (chunked) {
            while (true) {
                val line = StringBuilder()
                while (line.length < 64) { val c = input.read(); if (c < 0) throw java.io.EOFException(); if (c == '\n'.code) break; if (c != '\r'.code) line.append(c.toChar()) }
                val n = line.toString().trim().substringBefore(';').toIntOrNull(16) ?: throw java.io.IOException("invalid_chunk")
                if (n == 0) break
                if (n < 0 || n > limit - out.size()) throw java.io.IOException("body_limit")
                val buf = ByteArray(minOf(8192, n))
                var left = n
                while (left > 0) { val count = input.read(buf, 0, minOf(buf.size, left)); if (count < 0) throw java.io.EOFException(); out.write(buf, 0, count); left -= count }
                if (input.read() != 13 || input.read() != 10) throw java.io.IOException("invalid_chunk_end")
            }
        } else {
            val announced = head.headers["content-length"]?.toLongOrNull()
            if (announced != null && announced !in 0..limit.toLong()) throw java.io.IOException("body_limit")
            val len = announced?.toInt() ?: limit + 1
            val buf = ByteArray(8192)
            while (out.size() < len) { val n = input.read(buf, 0, minOf(buf.size, len - out.size())); if (n < 0) break; out.write(buf, 0, n) }
            if (out.size() > limit) throw java.io.IOException("body_limit")
            if (announced != null && out.size() != len) throw java.io.EOFException()
        }
        return out.toByteArray()
    }

    fun shutdown() { watchdog.shutdownNow() }

    companion object {
        val PLAIN_PORTS = setOf(80, 8080, 8880, 2052, 2082, 2086, 2095)

        fun cfErrors(body: String): List<Int> = runCatching {
            val a = org.json.JSONObject(body).optJSONArray("errors") ?: return emptyList()
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optInt("code")?.takeIf { c -> c > 0 } }.take(4)
        }.getOrDefault(emptyList())
    }
}
