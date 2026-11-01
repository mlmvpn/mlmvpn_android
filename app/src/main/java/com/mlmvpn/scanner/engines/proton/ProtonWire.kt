package com.mlmvpn.scanner.engines.proton

import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.security.SecureRandom

internal enum class ProtonMethod { PLAIN, PATTNG_ZERO, PATTNG_SIX, PATTN, PATTNG_TLS12 }

/**
 * How WireGuard reaches the server:
 *  - [U6], [U4]: plain WireGuard over UDP to the server's IPv6 / IPv4 address. Where the line lets
 *    it through this is by far the best way: WireGuard inside a TCP connection (all the others)
 *    carries every packet behind the one before it, so on a lossy line one lost segment stalls
 *    everything for a round trip or more -- the slowness, the DNS timeouts and the stalls measured
 *    on Stealth (2026-10-06/07).
 *  - [V6], [V4]: Stealth, WireGuard inside TLS on TCP 443, to the server's IPv6 / IPv4 address.
 *  - [CF]: Stealth from inside Cloudflare ([ProtonCfCarrier]) -- the way round a line that
 *    blackholes Proton's IPv4 servers but lets Cloudflare's IPv4 edges through with the handshake
 *    the Cloudflare Doctor proved there.
 */
internal enum class ProtonVia(val key: String) { U6("u6"), V6("v6"), CF("cf"), U4("u4"), V4("v4") }

internal data class ProtonRoute(val via: ProtonVia, val method: ProtonMethod = ProtonMethod.PLAIN) {
    val id: String get() = "${via.key}:${method.name}"
    val ipv6: Boolean get() = via == ProtonVia.V6 || via == ProtonVia.U6
    /** Plain WireGuard over UDP: the relay sends WireGuard's datagrams to the server's own port. */
    val udp: Boolean get() = via == ProtonVia.U6 || via == ProtonVia.U4
}

/**
 * Every way one server can be reached from here, as the lanes of a race: the relay dials them all
 * at once and the first that carries WireGuard data back wins. Lines in Iran differ too much for
 * any fixed order -- one has no IPv6 at all, one blackholes Proton's IPv4, one drops UDP, one cuts
 * Cloudflare -- and trying the ways one after another cost a first connect half a minute on every
 * way that was dead there. A lane is one way; the IPv4 lane holds Stealth's IPv4 methods, tried in
 * turn when one fails.
 *
 * The way that measured fastest on this network ([speeds]), else the one that last carried data --
 * for this server, else on this network ([preferred]) -- leads its lane and the race. Both are asked
 * when a race starts, not when the plan is made: a test opens every server's relay up front, and
 * the way its first batch learns must reach the later ones. TLS and WireGuard handshakes never call
 * [validated]; only application data does.
 */
internal class ProtonRoutePlan(
    hasV6: Boolean,
    cf: Boolean = false,
    private val preferred: () -> String? = { null },
    v4Methods: List<ProtonMethod> = V4_METHODS,
    /**
     * Plain WireGuard over UDP to the server's IPv4: off on a network where it was just seen to
     * stall (the relay factory remembers that), so it is not raced there for a while.
     */
    udp: Boolean = true,
    /** Plain WireGuard over UDP to the server's IPv6. */
    udp6: Boolean = true,
    /** Only these ways (the debug lab tests one at a time); null for all. */
    only: Set<ProtonVia>? = null,
    /** What the ways measured on this network lately (bits/s by way id), and when; empty when not lately. */
    private val speeds: () -> Pair<Map<String, Long>, Long> = { emptyMap<String, Long>() to 0L },
    /** Keeps what a speed comparison measured here. */
    private val onSpeeds: (Map<String, Long>) -> Unit = {},
    /** A way that could not hold the tunnel here (it stalled again and again, or it is UDP the line drops). */
    private val onUnstable: (String) -> Unit = {},
    private val result: (String, Boolean) -> Unit = { _, _ -> },
) {
    /** Plain WireGuard stalled under this tunnel: the line drops it, so neither UDP way is raced again. */
    @Volatile private var udpDropped = false
    private val all: List<List<ProtonRoute>> = buildList {
        if (hasV6) {
            if (udp6) add(listOf(ProtonRoute(ProtonVia.U6)))
            add(listOf(ProtonRoute(ProtonVia.V6)))
        }
        if (cf) add(listOf(ProtonRoute(ProtonVia.CF)))
        if (udp) add(listOf(ProtonRoute(ProtonVia.U4)))
        if (v4Methods.isNotEmpty()) add(v4Methods.map { ProtonRoute(ProtonVia.V4, it) })
    }.filter { only == null || it.first().via in only }

    /** How many ways the plan holds (each IPv4 method counts). */
    val size: Int get() = all.sumOf { it.size }

    private fun usable(): List<List<ProtonRoute>> = if (udpDropped) all.filter { !it.first().udp } else all

    /**
     * The way that measured fastest here (and is not in [avoid]), else the one that last carried
     * data -- when this plan has it. A way measured at 0 (it failed its sample, or could not hold
     * the tunnel) is no lead.
     */
    fun preferredRoute(avoid: Set<String> = emptySet()): ProtonRoute? {
        val ways = usable().flatten().filter { it.id !in avoid }
        speeds().first.entries.filter { it.value > 0 }.sortedByDescending { it.value }
            .firstNotNullOfOrNull { e -> ways.firstOrNull { it.id == e.key } }?.let { return it }
        return preferred()?.let { id -> ways.firstOrNull { it.id == id } }
    }

    /**
     * Whether a speed comparison is due here: nothing measured lately, or a way this plan has was
     * left out of the last one and that was a while ago.
     */
    fun speedsDue(now: Long = System.currentTimeMillis()): Boolean {
        val (measured, at) = speeds()
        if (measured.isEmpty()) return true
        return now - at >= REMEASURE_MS && usable().any { lane -> lane.first().id !in measured }
    }

    /** What a speed comparison measured here (bits/s by way id). */
    fun speeds(measured: Map<String, Long>) = onSpeeds(measured)

    /** [a] measured clearly faster here than [b] (a quarter or more; a way never measured is no match). */
    fun faster(a: ProtonRoute, b: ProtonRoute): Boolean {
        val measured = speeds().first
        val sa = measured[a.id] ?: return false
        return sa * 4 > (measured[b.id] ?: 0L) * 5
    }

    /**
     * The lanes of a race, the remembered way leading its lane and the race. Ways in [avoid] (they
     * stalled a moment ago) go last, in their lane and in the race -- never out of it: where such a
     * way is the only one that works, leaving it out left the tunnel with nothing (seen 2026-10-07
     * on a line without IPv6, Cloudflare its only way).
     */
    fun lanes(avoid: Set<String> = emptySet()): List<List<ProtonRoute>> {
        val p = preferredRoute(avoid)
        return usable().map { lane -> if (p != null && p in lane) listOf(p) + (lane - p) else lane }
            .map { lane -> lane.sortedBy { if (it.id in avoid) 1 else 0 } }
            .sortedBy { lane -> if (p != null && lane.first() == p) 0 else if (lane.first().id in avoid) 2 else 1 }
    }

    /** Another way than [route] measured working here (a speed above 0), and is still raced. */
    fun otherWayWorks(route: ProtonRoute): Boolean {
        val ways = usable().flatten().map { it.id }.toSet()
        return speeds().first.any { (id, v) -> id != route.id && v > 0 && id in ways }
    }

    /** [route] carried application data. */
    fun validated(route: ProtonRoute) = result(route.id, true)
    /** [route] stalled while it carried the tunnel. */
    fun failed(route: ProtonRoute) {
        if (route.udp) udpDropped = true
        result(route.id, false)
    }

    /** [route] could not hold the tunnel here, however fast it measured. */
    fun unstable(route: ProtonRoute) = onUnstable(route.id)

    companion object {
        /** A way left out of the last speed comparison gets its own after this long. */
        const val REMEASURE_MS = 12 * 3600_000L

        /**
         * Measured on the user's line (2026-10-06, the IPv4 lab): none of these reached a Proton IPv4
         * server -- the TLS handshake timed out under every shape. They stay for a line without IPv6
         * and without a Cloudflare config, PattNG's empty first record last: a strict TLS server
         * refuses a zero-length handshake fragment outright.
         */
        val V4_METHODS = listOf(ProtonMethod.PLAIN, ProtonMethod.PATTNG_SIX, ProtonMethod.PATTN, ProtonMethod.PATTNG_TLS12, ProtonMethod.PATTNG_ZERO)
    }
}

internal object ProtonHello {
    private val random = SecureRandom()

    /** PattNG's two ordered masks, or PattN's 50–100 byte TLS records / 10–20 ms delay.
     * This reshapes TLS records, not just socket writes: a TCP-only split is a different method.
     * Reference: PattNG res/values/arrays.xml; PattN V2rayOutboundService.BuildFragmentsMasks.
     */
    fun write(out: OutputStream, bytes: ByteArray, method: ProtonMethod) {
        val size = if (bytes.size >= 5) ((bytes[3].toInt() and 255) shl 8) or (bytes[4].toInt() and 255) else 0
        if (method == ProtonMethod.PLAIN || bytes.size < 6 || bytes[0] != 22.toByte() || size == 0 || size + 5 > bytes.size) {
            out.write(bytes); out.flush(); return
        }
        val merged = ByteArrayOutputStream()
        var offset = 5
        var index = 0
        while (offset < 5 + size) {
            checkInterrupted()
            val length = when {
                method == ProtonMethod.PATTN -> 50 + random.nextInt(51)
                index == 0 -> if (method != ProtonMethod.PATTNG_ZERO) 6 else 0
                index == 1 -> if (method != ProtonMethod.PATTNG_ZERO) 98 else 104
                else -> 1
            }.coerceAtMost(5 + size - offset)
            val record = byteArrayOf(bytes[0], bytes[1], bytes[2], (length ushr 8).toByte(), length.toByte()) + bytes.copyOfRange(offset, offset + length)
            if (method == ProtonMethod.PATTN) {
                out.write(record); out.flush(); pause(10 + random.nextInt(11))
            } else merged.write(record)
            offset += length
            index++
        }
        if (method != ProtonMethod.PATTN) {
            val wire = merged.toByteArray()
            var sent = 0
            repeat(11) { part ->
                if (sent < wire.size) {
                    checkInterrupted()
                    val n = (if (part == 0) 114 else if (part == 10) wire.size - sent else 1).coerceAtMost(wire.size - sent)
                    out.write(wire, sent, n); out.flush(); sent += n; pause(1)
                }
            }
        }
        if (bytes.size > 5 + size) { out.write(bytes, 5 + size, bytes.size - 5 - size); out.flush() }
    }

    /** One first flight per TLS connection; subsequent ciphertext is never fragmented here. */
    fun output(out: OutputStream, method: ProtonMethod): OutputStream = object : OutputStream() {
        private var first = true
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len == 0) return
            if (first) { first = false; ProtonHello.write(out, b.copyOfRange(off, off + len), method) }
            else out.write(b, off, len)
        }
        override fun flush() = out.flush()
    }

    private fun checkInterrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedIOException("proton_cancelled") }
    private fun pause(ms: Int) {
        try { Thread.sleep(ms.toLong()) } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); throw InterruptedIOException("proton_cancelled")
        }
    }
}
