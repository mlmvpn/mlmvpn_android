package com.mlmvpn.scanner.engines.scout

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.mlmvpn.scanner.engines.cfdoctor.AndroidNetEnv
import com.mlmvpn.scanner.engines.cfdoctor.CfBogon
import com.mlmvpn.scanner.engines.cfdoctor.DataBudget
import com.mlmvpn.scanner.engines.cfdoctor.NetProbes
import com.mlmvpn.scanner.engines.cfdoctor.QuicProbe
import com.mlmvpn.scanner.engines.game.booster.probe.StunProtocol
import com.mlmvpn.scanner.engines.game.booster.probe.UdpVia
import com.mlmvpn.scanner.engines.game.booster.probe.icmpPing
import com.mlmvpn.scanner.engines.game.booster.probe.udpTrain
import com.mlmvpn.scanner.engines.game.booster.session.GameNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom

/**
 * «اینترنت شما»: what the line itself can do, measured straight over the phone's own network (the
 * app's VPN is paused for the run). Light probes only -- they run beside the methods' tests -- and
 * every item that cannot be established honestly is reported as unknown rather than guessed.
 *
 * Nothing identifying is kept: no address, no Wi-Fi name, nothing a resolver answered.
 */
internal class ScoutLine(private val ctx: Context, private val update: ((LineFacts) -> LineFacts) -> Unit) {

    private val picked = GameNetwork.pick(ctx)
    val network: Network? = picked?.network

    /** What the system already knows: no traffic. */
    fun snapshot(): Pair<LineFacts, LineSnapshot> {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = picked?.caps
        val lp = network?.let { runCatching { cm.getLinkProperties(it) }.getOrNull() }
        val cell = picked?.isCellular == true
        val hasV4 = lp?.linkAddresses?.any { it.address is Inet4Address && !it.address.isLoopbackAddress } == true
        val hasV6 = runCatching { com.mlmvpn.scanner.engines.flux.FluxNet.hasV6(ctx, network) }.getOrDefault(false)
        val facts = LineFacts(
            netType = when {
                picked == null -> null
                picked.isWifi -> "wifi"
                cell -> "cell"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
                else -> "other"
            },
            operator = if (cell) AndroidNetEnv.operator(ctx) else null,
            metered = caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) },
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            captive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            privateDns = if (Build.VERSION.SDK_INT >= 28) lp?.isPrivateDnsActive else null,
            v4 = if (!hasV4) FamState.NONE else null,
            v6 = if (!hasV6) FamState.NONE else null,
        )
        // A line with neither family reported (an odd link) is treated as IPv4: the tests decide.
        return facts to LineSnapshot(hasV4 = hasV4 || !hasV6, hasV6 = hasV6, cellular = cell, network = network)
    }

    /** Everything light, side by side. */
    suspend fun run(snap: LineSnapshot) = coroutineScope {
        launch { reach() }
        launch { families(snap) }
        launch { tcpSet() }
        launch { udp() }
        launch { dns() }
        launch { tlsSni() }
        launch { httpPlain() }
        launch { icmp() }
        launch { firewall() }
    }

    private suspend fun <T> blocking(block: () -> T): T = runInterruptible(Dispatchers.IO) { block() }

    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    // ---- international / domestic, HTTPS, the clock ----------------------------------------

    private suspend fun reach() = coroutineScope {
        val google = async { blocking { ScoutNet.https204(null, network, ScoutNet.VERIFY_HOST, 6_000) } }
        val cf = async { blocking { ScoutNet.tcp(network, ip("1.1.1.1"), 443, 4_000) ?: ScoutNet.tcp(network, ip("8.8.8.8"), 443, 4_000) } }
        val home = async {
            blocking {
                listOf("www.aparat.com", "www.digikala.com").any { h ->
                    runCatching { ScoutNet.resolve(network, h).firstOrNull()?.let { a -> ScoutNet.tlsTime(network, a, h, 6_000, verifyName = false) } }.getOrNull() != null
                }
            }
        }
        val g = google.await(); val c = cf.await(); val d = home.await()
        val foreign = g != null || c != null
        update {
            it.copy(
                intl = when { foreign -> Tri.YES; d -> Tri.NO; else -> Tri.UNKNOWN },
                domestic = if (d) Tri.YES else if (foreign) Tri.NO else Tri.UNKNOWN,
                https = if (g != null) Tri.YES else if (foreign) Tri.NO else Tri.UNKNOWN,
                clockSkewS = g?.date?.let(::skew),
            )
        }
    }

    private fun skew(date: String): Long? = runCatching {
        val f = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
        (System.currentTimeMillis() - f.parse(date)!!.time) / 1000
    }.getOrNull()

    // ---- IPv4 / IPv6 -------------------------------------------------------------------------

    /**
     * Whether a family carries traffic at all: a TCP handshake to any of many places, as the online
     * IPv6 tests check. It was a TLS handshake to Cloudflare's and Google's DNS (one.one.one.one,
     * dns.google) -- two services Iran filters by name -- so both families read «بسته» on lines where
     * every site said they worked (user report 2026-10-11). The filter can break a name; it cannot
     * fake a handshake, so a handshake proves the family whatever is filtered on top of it.
     */
    private suspend fun families(snap: LineSnapshot) {
        val v4 = if (snap.hasV4) blocking { familyTargets(v6 = false) } else emptyList()
        val v6 = if (snap.hasV6) blocking { familyTargets(v6 = true) } else emptyList()
        val v4ok = v4.firstOrNull { a -> blocking { ScoutNet.tcp(network, a, 443, 4_000) } != null }
        val v6ok = v6.firstOrNull { a -> blocking { ScoutNet.tcp(network, a, 443, 4_000) } != null }
        update {
            it.copy(
                v4 = if (it.v4 == FamState.NONE) FamState.NONE else if (v4ok != null) FamState.WORKS else FamState.BROKEN,
                v6 = if (it.v6 == FamState.NONE) FamState.NONE else if (v6ok != null) FamState.WORKS else FamState.BROKEN,
            )
        }
        // The place that answered on each family, taken in turns so a dip on the line falls on both.
        val a4 = ArrayList<Long>(); val a6 = ArrayList<Long>()
        repeat(5) {
            v4ok?.let { a -> blocking { ScoutNet.tcp(network, a, 443, 3_000) }?.let { a4 += it } }
            v6ok?.let { a -> blocking { ScoutNet.tcp(network, a, 443, 3_000) }?.let { a6 += it } }
        }
        update { it.copy(v4Ms = ScoutNet.median(a4), v6Ms = ScoutNet.median(a6)) }
    }

    /**
     * Places to knock on for one family: big sites asked of this network's own resolver (answers
     * that are the filter's block page or private are dropped), then fixed public addresses.
     * Foreign and Iranian alike: the question is the family, not the border.
     */
    private fun familyTargets(v6: Boolean): List<InetAddress> {
        val names = listOf("www.google.com", "www.cloudflare.com", "www.wikipedia.org", "www.aparat.com", "www.digikala.com", "www.microsoft.com")
        val resolved = names.flatMap { h ->
            runCatching { ScoutNet.resolve(network, h) }.getOrDefault(emptyList())
                .filter { a -> (a is java.net.Inet6Address) == v6 && !a.isSiteLocalAddress && !a.isLoopbackAddress && !a.isLinkLocalAddress && a.hostAddress?.let(CfBogon::isBogon) != true }
                .take(1)
        }
        val fixed = if (v6) listOf("2606:4700:4700::1111", "2001:4860:4860::8888", "2620:fe::fe") else listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")
        return (resolved + fixed.mapNotNull { runCatching { ip(it) }.getOrNull() }).distinct()
    }

    // ---- TCP and the line's delay -----------------------------------------------------------

    private suspend fun tcpSet() {
        val targets = listOf("1.1.1.1" to 443, "1.1.1.1" to 80, "8.8.8.8" to 443, "8.8.4.4" to 443, "9.9.9.9" to 443, "208.67.222.222" to 443,
            "142.250.185.68" to 443, "151.101.1.69" to 443)
        val times = coroutineScope { targets.map { (h, p) -> async { blocking { ScoutNet.tcp(network, ip(h), p, 4_000) } } }.map { it.await() } }
        // To the first place that answered: 1.1.1.1 alone left the line's delay empty wherever the
        // filter drops it.
        val rtt = ArrayList<Long>()
        targets.zip(times).firstOrNull { it.second != null }?.first?.let { (h, p) ->
            repeat(3) { blocking { ScoutNet.tcp(network, ip(h), p, 3_000) }?.let { rtt += it } }
        }
        update { it.copy(tcpOk = times.count { t -> t != null }, tcpTotal = targets.size, tcpMs = ScoutNet.median(times.filterNotNull()), rttMs = ScoutNet.median(rtt)) }
    }

    private suspend fun icmp() {
        // More than one target: one that drops ICMP said nothing about the line.
        for (target in listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")) {
            val r = icmpPing(target, count = 4, intervalS = 0.25, timeoutS = 1)
            val ms = r.stats.p50?.toLong()
            if (ms != null) { update { it.copy(icmpMs = ms) }; return }
        }
    }

    // ---- UDP, QUIC, jitter and loss ---------------------------------------------------------

    private fun datagram(timeoutMs: Int): DatagramSocket {
        val s = DatagramSocket(null)
        try {
            network?.bindSocket(s)
            s.bind(InetSocketAddress(0))
            s.soTimeout = timeoutMs
            return s
        } catch (e: Exception) {
            s.close(); throw e
        }
    }

    /** [attempts] tries of one probe; how many came back valid. */
    private suspend fun udpProbe(target: String, port: Int, attempts: Int, once: () -> Boolean): ScoutRules.UdpProbe {
        var valid = 0
        repeat(attempts) { if (attempt { blocking { once() } } == true) valid++ }
        return ScoutRules.UdpProbe(target, port, attempts, valid)
    }

    private fun stun(addr: InetAddress, port: Int): Boolean = datagram(2_000).use { s ->
        val token = StunProtocol.newToken()
        val q = StunProtocol.build(token)
        s.send(DatagramPacket(q, q.size, addr, port))
        val buf = ByteArray(1024)
        val p = DatagramPacket(buf, buf.size)
        s.receive(p)
        StunProtocol.match(buf, p.length)?.contentEquals(token) == true
    }

    /**
     * `whoami.cloudflare` in the CHAOS class, asked of 1.1.1.1: only Cloudflare's own resolver answers
     * it, with the asker's address as text. An operator's resolver standing in for 1.1.1.1 cannot, so
     * a valid answer proves the datagram really crossed the border.
     */
    private fun whoami(): Boolean = datagram(2_000).use { s ->
        val id = SecureRandom().nextInt(65536)
        val q = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply {
                writeShort(id); writeShort(0x0100); writeShort(1); writeShort(0); writeShort(0); writeShort(0)
                "whoami.cloudflare".split('.').forEach { l -> writeByte(l.length); writeBytes(l) }
                writeByte(0); writeShort(16); writeShort(3)
            }
        }.toByteArray()
        s.send(DatagramPacket(q, q.size, InetAddress.getByName("1.1.1.1"), 53))
        val buf = ByteArray(1024)
        val p = DatagramPacket(buf, buf.size)
        s.receive(p)
        txtIsAddress(buf.copyOf(p.length), id)
    }

    private fun txtIsAddress(b: ByteArray, id: Int): Boolean = runCatching {
        fun u16(i: Int) = ((b[i].toInt() and 255) shl 8) or (b[i + 1].toInt() and 255)
        if (b.size < 12 || u16(0) != id || u16(2) and 0x8000 == 0 || u16(2) and 15 != 0 || u16(6) == 0) return false
        var p = 12
        fun skip() { while (true) { val n = b[p].toInt() and 255; if (n == 0) { p++; return }; if (n and 0xc0 == 0xc0) { p += 2; return }; p += n + 1 } }
        repeat(u16(4)) { skip(); p += 4 }
        skip()
        val type = u16(p); val len = u16(p + 8); p += 10
        if (type != 16 || len < 2) return false
        val t = String(b, p + 1, (b[p].toInt() and 255).coerceAtMost(len - 1))
        t.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) || (t.contains(':') && t.all { it.isLetterOrDigit() || it == ':' })
    }.getOrDefault(false)

    private fun quic(addr: InetAddress): Boolean = datagram(2_000).use { s ->
        val q = QuicProbe.packet()
        s.send(DatagramPacket(q.bytes, q.bytes.size, addr, 443))
        val buf = ByteArray(4096)
        val p = DatagramPacket(buf, buf.size)
        s.receive(p)
        QuicProbe.validResponse(buf.copyOf(p.length), q)
    }

    private suspend fun udp() = coroutineScope {
        val stunG = attempt { blocking { ScoutNet.resolve(network, "stun.l.google.com").firstOrNull { it is Inet4Address } } }
        val stunC = attempt { blocking { ScoutNet.resolve(network, "stun.cloudflare.com").firstOrNull { it is Inet4Address } } }
        val googleWeb = attempt { blocking { ScoutNet.resolve(network, "www.google.com").firstOrNull { it is Inet4Address } } }
        // The comparison TCP goes to the same companies the UDP probes go to, several of them: with
        // only 1.1.1.1 and 8.8.8.8, a line that filters those two made every UDP verdict «نمی‌شود گفت».
        val tcpWorks = async {
            blocking {
                listOfNotNull(stunG, stunC, googleWeb, runCatching { ip("1.1.1.1") }.getOrNull(), runCatching { ip("8.8.8.8") }.getOrNull())
                    .any { a -> ScoutNet.tcp(network, a, 443, 4_000) != null }
            }
        }
        val probes = listOfNotNull(
            stunG?.let { a -> async { udpProbe("stun.l.google.com", 19302, 2) { stun(a, 19302) } } },
            stunC?.let { a -> async { udpProbe("stun.cloudflare.com", 3478, 2) { stun(a, 3478) } } },
            async { udpProbe("1.1.1.1", 53, 2) { whoami() } },
            async { udpProbe("1.1.1.1", 443, 2) { quic(ip("1.1.1.1")) } },
            googleWeb?.let { a -> async { udpProbe("www.google.com", 443, 2) { quic(a) } } },
        ).map { it.await() }
        val verdict = ScoutRules.judgeUdp(probes, tcpWorks.await())
        val quicOk = probes.any { it.port == 443 && it.valid > 0 }
        update {
            it.copy(
                udp = verdict.udp,
                udpWhy = when (verdict.why) {
                    ScoutRules.UdpWhy.ANSWERED -> com.mlmvpn.scanner.store.tr("جواب معتبر از: ", "Valid answer from: ") + verdict.answeredBy.joinToString("، ")
                    ScoutRules.UdpWhy.ALL_SILENT -> com.mlmvpn.scanner.store.tr("${probes.size} کاوش مستقل، هرکدام دو بار، بی‌جواب ماند؛ TCP به همان سرورها کار می‌کند", "${probes.size} independent probes, twice each, all silent; TCP to the same servers works")
                    ScoutRules.UdpWhy.TCP_DOWN -> com.mlmvpn.scanner.store.tr("TCP هم کار نکرد؛ نمی‌شود گفت", "TCP failed too; cannot tell")
                    ScoutRules.UdpWhy.NOT_ENOUGH -> com.mlmvpn.scanner.store.tr("شواهد کافی نیست", "Not enough evidence")
                },
                quic = when {
                    quicOk -> Tri.YES
                    verdict.udp != Tri.UNKNOWN -> Tri.NO
                    else -> Tri.UNKNOWN
                },
            )
        }
        // Jitter and loss only where a STUN server really answers: 60 probes at a steady 10 a second.
        val target = if (probes.any { it.target == "stun.l.google.com" && it.valid > 0 }) stunG?.let { InetSocketAddress(it, 19302) }
        else if (probes.any { it.target == "stun.cloudflare.com" && it.valid > 0 }) stunC?.let { InetSocketAddress(it, 3478) } else null
        if (target != null) {
            val r = attempt { udpTrain(target, StunProtocol, UdpVia.Direct(network), pps = 10, durationMs = 6_000, timeoutMs = 1_000) }
            if (r != null && r.stats.answered) {
                update { it.copy(jitterMs = r.stats.jitter, lossPct = if (r.stats.lossIsMeaningful) r.stats.loss else null, udpSamples = r.sent) }
            }
        }
    }

    // ---- DNS ------------------------------------------------------------------------------

    private suspend fun dns() {
        val t0 = System.nanoTime()
        val google = attempt { blocking { ScoutNet.resolve(network, "www.google.com") } }
        val dnsMs = google?.let { (System.nanoTime() - t0) / 1_000_000 }
        val filtered = listOf("www.youtube.com", "www.instagram.com").map { h ->
            attempt { blocking { ScoutNet.resolve(network, h).mapNotNull { it.hostAddress } } }
        }
        val tampered = when {
            filtered.any { l -> l != null && l.any(CfBogon::isBogon) } -> Tri.YES
            google != null && filtered.any { it == null || it.isEmpty() } -> Tri.YES
            filtered.all { it != null && it.isNotEmpty() } -> Tri.NO
            else -> Tri.UNKNOWN
        }
        val env = AndroidNetEnv(ctx)
        val probes = NetProbes(env, DataBudget(400_000))
        var doh = false
        for (r in listOf("1.1.1.1", "8.8.8.8")) if (!doh) doh = attempt { probes.dns("scout-doh", r, "doh", host = "www.google.com").code == "ok" } == true
        update { it.copy(dnsMs = dnsMs, dnsTampered = tampered, doh = if (doh) Tri.YES else if (google != null) Tri.NO else Tri.UNKNOWN) }
    }

    // ---- TLS and name-based filtering -------------------------------------------------------

    private suspend fun tlsSni() {
        val addr = attempt { blocking { ScoutNet.resolve(network, "www.google.com").firstOrNull { it is Inet4Address } } }
        if (addr == null) { update { it.copy(sniFilter = Tri.UNKNOWN) }; return }
        val open = blocking { ScoutNet.tlsTime(network, addr, "www.google.com", 6_000) }
        // The same free address, asked for a filtered name: only the name differs.
        val filtered = blocking { ScoutNet.tlsTime(network, addr, ScoutNet.FILTERED_HOST, 6_000) }
            ?: blocking { ScoutNet.tlsTime(network, addr, ScoutNet.FILTERED_HOST, 6_000) }
        update {
            it.copy(
                tlsMs = open,
                sniFilter = when {
                    open == null -> Tri.UNKNOWN
                    filtered == null -> Tri.YES
                    else -> Tri.NO
                },
            )
        }
    }

    private suspend fun httpPlain() {
        val r = blocking { ScoutNet.http(network, "connectivitycheck.gstatic.com", ScoutNet.PATH_204, 6_000) }
        update {
            it.copy(
                http = if (r != null) Tri.YES else Tri.UNKNOWN,
                httpTampered = when {
                    r == null -> Tri.UNKNOWN
                    r.code == 204 -> Tri.NO
                    else -> Tri.YES
                },
            )
        }
    }

    private suspend fun firewall() {
        val fam = runCatching {
            val m = com.mlmvpn.scanner.engines.cfnet.CfNetManager.get(ctx)
            // Only what DATA proved here, never the quick probe's hint.
            m.store.family(m.profile(), m.registry::byId)?.key
        }.getOrNull()
        if (fam != null) update { it.copy(family = fam) }
    }

    // ---- the slower extras, run later ------------------------------------------------------

    /** Path MTU to 1.1.1.1 by binary search, confirmed at the boundary. Only where ICMP answers. */
    suspend fun mtu(icmpAnswers: Boolean) {
        if (!icmpAnswers) { update { it.copy(mtuTried = true) }; return }
        val r = attempt { com.mlmvpn.scanner.utils.MtuProbe.measure(com.mlmvpn.scanner.utils.NetworkSettings.Method.GATEWAY) }
        update { it.copy(mtu = r?.outerPathMtu, mtuTried = true) }
    }

    /** The line's own speed over the same file every method is sampled with. */
    suspend fun directMbps(maxBytes: Int): Double? = blocking {
        // Google's own download host refuses Iranian addresses, so on the bare line that file alone
        // usually gave no speed at all; the next ones are served to anyone.
        DIRECT_SPEED_URLS.firstNotNullOfOrNull { url -> ScoutNet.download(null, network, url, maxBytes, 20_000) }
    }?.mbps

    private companion object {
        val DIRECT_SPEED_URLS = listOf(
            ScoutNet.SPEED_URL,
            "https://speed.cloudflare.com/__down?bytes=25000000",
            "https://cachefly.cachefly.net/50mb.test",
        )
    }
}

/** [block]'s value, or null when it threw -- except a cancellation, which always goes through. */
internal suspend inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
