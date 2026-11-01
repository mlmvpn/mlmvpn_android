package com.mlmvpn.scanner.engines.game.booster.probe

import com.mlmvpn.scanner.utils.alive
import com.mlmvpn.scanner.utils.killHard
import com.mlmvpn.scanner.utils.waitUpTo

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * The outcome of one train: the statistics, and the raw answered samples so interleaved slices
 * can be merged and judged per slice.
 */
data class TrainResult(
    val target: String,
    val proto: String,
    val stats: PathStats,
    /** Answered probes in receive order; seq still identifies their send order within the train. */
    val samples: List<RttSample>,
    val sent: Int,
    val ppsAchieved: Double?,
    val error: String? = null,
    /**
     * How many sequence numbers this train's [samples] can occupy (0 until seqSpan). Not [sent]:
     * a probe that failed to go out keeps its number, so trains laid end to end must be offset by
     * the span or their numbers overlap -- which reorders samples across trains and corrupts the
     * jitter measured over the merged set.
     */
    val seqSpan: Int = sent,
) {
    val ok: Boolean get() = stats.answered
    val score: Int get() = ProbeStats.score(stats)
}

/**
 * Interleaved slices of one path, merged into one set of statistics without their sequence
 * numbers colliding: each train's samples are offset by the spans of the trains before it.
 */
class SliceAccumulator {
    val samples = mutableListOf<RttSample>()
    var sent = 0
        private set
    private var base = 0
    /** The p50 of each slice added, null for a slice that got nothing: what slice wins are judged on. */
    val sliceP50 = mutableListOf<Int?>()

    fun add(r: TrainResult?) {
        if (r == null) return
        r.samples.forEach { samples += it.copy(seq = base + it.seq) }
        base += r.seqSpan
        sent += r.sent
        sliceP50 += r.stats.p50
    }

    fun stats(): PathStats = ProbeStats.stats(samples, sent)

    val isEmpty: Boolean get() = sent == 0
}

/** Where a UDP probe's datagrams go: straight out, or through a SOCKS5 relay. */
sealed class UdpVia {
    /** Straight out, optionally pinned to [network] (the real line under someone else's VPN). */
    data class Direct(val network: Network? = null) : UdpVia()
    /**
     * Through an open UDP ASSOCIATE -- the WARP path, via Aether's local proxy. In a multi-flow
     * train every flow needs its OWN session: the association is what the far end sees as a flow.
     */
    data class Socks(val session: SocksUdpSession) : UdpVia()
}

/**
 * Send a train of UDP probes at a fixed cadence and measure every answer -- over one flow.
 *
 * Kept for callers that measure something without a per-flow limit (STUN, a relay's own echo);
 * a GameLift beacon must be measured with [udpTrainFlows].
 */
suspend fun udpTrain(
    target: InetSocketAddress,
    protocol: UdpProbeProtocol,
    via: UdpVia = UdpVia.Direct(),
    pps: Int = 20,
    durationMs: Long = 2000,
    warmupMs: Long = 0,
    timeoutMs: Long = 1000,
    onSample: ((RttSample) -> Unit)? = null,
): TrainResult = udpTrainFlows(target, protocol, listOf(via), pps.toDouble(), durationMs, warmupMs, timeoutMs, onSample)

/**
 * Send a train of UDP probes at a fixed cadence, spread round-robin over [flows], and measure
 * every answer.
 *
 * The cadence is the point: a game sends on a clock, and a path behaves differently under a steady
 * stream than under a burst of five. Answers are matched by the token they carry, never by order,
 * and an answer later than [timeoutMs] counts as lost -- it is not credited to anything.
 *
 * Why several flows: the AWS GameLift beacons answer about two packets a second per flow (source
 * address and port) and drop the rest, so a 20 pps train on one socket read as ~85 % loss on a
 * clean line. Ten flows at two packets a second each give the same 20 pps cadence on the line and
 * stay under the beacon's limit on every flow. Each flow is its own socket (a new source port),
 * or its own SOCKS association when measured through a relay.
 *
 * Sending runs on its own thread with parkNanos pacing; coroutine delays are millisecond-coarse
 * and drift under load, which shows up as fake jitter in exactly the number this measures.
 */
suspend fun udpTrainFlows(
    target: InetSocketAddress,
    protocol: UdpProbeProtocol,
    flows: List<UdpVia>,
    ppsPerFlow: Double = BEACON_PPS_PER_FLOW,
    durationMs: Long = 2000,
    warmupMs: Long = 0,
    timeoutMs: Long = 1000,
    onSample: ((RttSample) -> Unit)? = null,
): TrainResult = withContext(Dispatchers.IO) {
    val label = "${target.address?.hostAddress ?: target.hostString}:${target.port}"
    if (flows.isEmpty()) {
        return@withContext TrainResult(label, protocol.name, PathStats.empty(0), emptyList(), 0, null, error = "no flow")
    }
    val sockets = ArrayList<DatagramSocket>(flows.size)
    try {
        flows.forEach { via ->
            sockets += when (via) {
                is UdpVia.Direct -> DatagramSocket().also { s -> via.network?.bindSocket(s) }
                is UdpVia.Socks -> via.session.socket
            }
        }
    } catch (e: Exception) {
        flows.forEachIndexed { i, via -> if (via is UdpVia.Direct && i < sockets.size) try { sockets[i].close() } catch (_: Exception) {} }
        return@withContext TrainResult(label, protocol.name, PathStats.empty(0), emptyList(), 0, null,
            error = e.message ?: "socket")
    }

    val schedule = TrainSchedule.of(flows.size, ppsPerFlow, durationMs)
    val count = schedule.count
    val intervalNs = schedule.intervalNs
    val outstanding = ConcurrentHashMap<String, Int>()
    // Written by the sender, read by the receivers. The send time is stored BEFORE the token is
    // published in [outstanding], so the map's happens-before edge guarantees a receiver sees it.
    val sendAt = java.util.concurrent.atomic.AtomicLongArray(count)
    val samples = java.util.Collections.synchronizedList(ArrayList<RttSample>(count))
    val sendError = java.util.concurrent.atomic.AtomicReference<String?>(null)
    // Raised when the train ends early (the boost was stopped): the threads notice within one
    // receive timeout instead of running to the end of the train.
    val stop = java.util.concurrent.atomic.AtomicBoolean(false)
    val t0 = System.nanoTime()

    val sender = Thread({
        for (seq in 0 until count) {
            if (stop.get()) break
            val due = t0 + seq * intervalNs
            var wait = due - System.nanoTime()
            while (wait > 0) {
                LockSupport.parkNanos(wait)
                wait = due - System.nanoTime()
            }
            val flow = schedule.flowOf(seq)
            val via = flows[flow]
            val token = protocol.newToken()
            val payload = protocol.build(token)
            try {
                val packet = when (via) {
                    is UdpVia.Direct -> DatagramPacket(payload, payload.size, target)
                    is UdpVia.Socks -> via.session.packetFor(target, payload)
                }
                sendAt.set(seq, System.nanoTime())
                outstanding[token.toHex()] = seq
                try {
                    sockets[flow].send(packet)
                } catch (e: Exception) {
                    // Never went out: not "sent", so it cannot count as lost on the line.
                    outstanding.remove(token.toHex())
                    sendAt.set(seq, 0L)
                    throw e
                }
            } catch (e: Exception) {
                sendError.compareAndSet(null, e.message ?: "send")
            }
        }
    }, "gb-udp-sender")
    sender.isDaemon = true
    sender.start()

    val deadline = t0 + count * intervalNs + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    // One receiver per flow. An answer is credited once: removing its token from [outstanding]
    // is atomic, so a duplicate (or the same answer arriving twice) finds nothing to credit.
    val receivers = flows.indices.map { i ->
        Thread({
            val socket = sockets[i]
            val via = flows[i]
            val rx = ByteArray(2048)
            val packet = DatagramPacket(rx, rx.size)
            try {
                socket.soTimeout = 100
                while (!stop.get() && System.nanoTime() < deadline) {
                    try {
                        packet.setData(rx)
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val at = System.nanoTime()
                    val (offset, length) = when (via) {
                        is UdpVia.Direct -> 0 to packet.length
                        is UdpVia.Socks -> Socks5Codec.unwrapUdp(rx, packet.length) ?: continue
                    }
                    val body = if (offset == 0) rx else rx.copyOfRange(offset, offset + length)
                    val token = protocol.match(body, length) ?: continue
                    val seq = outstanding.remove(token.toHex()) ?: continue
                    val sentNs = sendAt.get(seq)
                    val rttMs = (at - sentNs) / 1_000_000.0
                    if (rttMs > timeoutMs) continue // late: lost, and credited to nothing
                    val sample = RttSample(seq, rttMs, (sentNs - t0) / 1_000_000.0)
                    samples.add(sample)
                    onSample?.invoke(sample)
                }
            } catch (e: Exception) {
                if (!socket.isClosed) sendError.compareAndSet(null, e.message ?: "receive")
            }
        }, "gb-udp-rx-$i").apply { isDaemon = true; start() }
    }
    try {
        while (System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive()
            delay(50)
        }
    } finally {
        stop.set(true)
        // The threads all see [stop] within one 100 ms receive timeout, so joining them one after
        // another costs about one timeout in total, not one each.
        receivers.forEach { it.join(300) }
        sender.join(500)
        flows.forEachIndexed { i, via -> if (via is UdpVia.Direct) try { sockets[i].close() } catch (_: Exception) {} }
    }

    // Warm-up is judged by when a probe was SENT, and loss only over the probes sent after it.
    // A probe that never went out (send failed, sendAt 0) is not counted as sent.
    val sentSeqs = (0 until count).filter { sendAt.get(it) != 0L }
    val afterWarmup = sentSeqs.filter { (sendAt.get(it) - t0) / 1_000_000.0 >= warmupMs }
    // Numbers restart at the first probe past warm-up, as the TrainResult contract says.
    val firstKept = afterWarmup.firstOrNull() ?: count
    val kept = synchronized(samples) { samples.toList() }
        .filter { it.txRelMs >= warmupMs && it.seq >= firstKept }
        .map { it.copy(seq = it.seq - firstKept) }
        .sortedBy { it.txRelMs + it.rttMs }
    val stats = ProbeStats.stats(kept, afterWarmup.size)
    val spanS = if (sentSeqs.size > 1) (sendAt.get(sentSeqs.last()) - sendAt.get(sentSeqs.first())) / 1e9 else 0.0
    TrainResult(
        target = label,
        proto = protocol.name,
        stats = stats,
        samples = kept,
        sent = afterWarmup.size,
        ppsAchieved = if (spanS > 0) (sentSeqs.size - 1) / spanS else null,
        error = if (kept.isEmpty()) (sendError.get() ?: "no answer") else null,
        seqSpan = count - firstKept,
    )
}

/** What a GameLift beacon answers per flow, with a little headroom. */
const val BEACON_PPS_PER_FLOW = 2.0

/** Flows used against a beacon on the direct line: 10 × 2 pps = a game's 20 pps cadence. */
const val BEACON_FLOWS_DIRECT = 10

/** Flows through a SOCKS relay, where each flow is a UDP association kept open for the train. */
const val BEACON_FLOWS_SOCKS = 6

/**
 * When each probe of a multi-flow train is sent and on which flow -- pure, so the schedule the
 * beacon's per-flow limit depends on can be checked without a network.
 */
data class TrainSchedule(val flows: Int, val count: Int, val intervalNs: Long) {
    /** Round-robin: consecutive probes never share a flow while there is more than one. */
    fun flowOf(seq: Int): Int = seq % flows

    /** Probes each flow carries per second at this schedule. */
    fun ppsPerFlow(): Double = 1e9 / intervalNs / flows

    companion object {
        fun of(flows: Int, ppsPerFlow: Double, durationMs: Long): TrainSchedule {
            val f = flows.coerceAtLeast(1)
            val totalPps = (ppsPerFlow * f).coerceIn(0.5, 100.0)
            val intervalNs = (1e9 / totalPps).toLong()
            val count = ((durationMs * totalPps) / 1000.0).toInt().coerceAtLeast(1)
            return TrainSchedule(f, count, intervalNs)
        }
    }
}

/**
 * A train of TCP handshakes -- a SYN → SYN/ACK is a real transport round trip through the same
 * queues as game data, and unlike ICMP it proves the port answers. Used where nothing answers
 * UDP. Through [socks] each sample is a full CONNECT via the proxy, which Aether only answers once
 * the far-side connection is established, so the number is device → WARP → target and back.
 *
 * `SO_LINGER 0` closes each probe with a reset, so a train does not leave dozens of sockets in
 * TIME_WAIT.
 */
suspend fun tcpTrain(
    target: InetSocketAddress,
    count: Int = 8,
    gapMs: Long = 120,
    timeoutMs: Int = 3000,
    socks: InetSocketAddress? = null,
    network: Network? = null,
    onSample: ((RttSample) -> Unit)? = null,
): TrainResult = withContext(Dispatchers.IO) {
    val label = "${target.address?.hostAddress ?: target.hostString}:${target.port}"
    val samples = ArrayList<RttSample>(count)
    var lastError: String? = null
    val t0 = System.nanoTime()
    var sent = 0
    for (seq in 0 until count) {
        if (!isActive) break
        sent++
        val s = when {
            socks != null -> Socket(Proxy(Proxy.Type.SOCKS, socks))
            network != null -> network.socketFactory.createSocket()
            else -> Socket()
        }
        val start = System.nanoTime()
        try {
            s.setSoLinger(true, 0)
            withProbeSocket(s) { it.connect(target, timeoutMs) }
            val rtt = (System.nanoTime() - start) / 1_000_000.0
            val sample = RttSample(seq, rtt, (start - t0) / 1_000_000.0)
            samples.add(sample)
            onSample?.invoke(sample)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            lastError = e.message ?: e.javaClass.simpleName
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
        if (seq < count - 1) delay(gapMs)
    }
    val stats = ProbeStats.stats(samples, sent)
    TrainResult(label, "tcp", stats, samples, sent, null,
        error = if (samples.isEmpty()) (lastError ?: "no connection") else null)
}

/** Close the descriptor on cancellation; interrupting a JVM socket read alone is insufficient. */
private suspend fun <T> withProbeSocket(socket: Socket, block: (Socket) -> T): T = kotlinx.coroutines.coroutineScope {
    val closer = launch(kotlinx.coroutines.Dispatchers.Unconfined, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        try { kotlinx.coroutines.awaitCancellation() } finally { runCatching { socket.close() } }
    }
    try { withContext(Dispatchers.IO) { block(socket) } }
    finally { closer.cancel(); runCatching { socket.close() } }
}

/** End-to-end TLS timing, including CONNECT. The same method is used in the race and live card. */
suspend fun tlsTrain(
    target: InetSocketAddress, count: Int = 5, gapMs: Long = 150, timeoutMs: Int = 6000,
    socks: InetSocketAddress? = null, network: Network? = null,
): TrainResult {
    val samples = ArrayList<RttSample>()
    val t0 = System.nanoTime()
    var sent = 0
    var lastError: String? = null
    val address = if (socks != null) InetSocketAddress.createUnresolved(target.hostString, target.port)
        else if (target.isUnresolved) resolveV4(target.hostString, network).firstOrNull()?.let { InetSocketAddress(it, target.port) }
        else target
    if (address == null) return TrainResult(target.hostString, "tls", PathStats.empty(count), emptyList(), count, null, "dns")
    repeat(count) { seq ->
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val raw = when {
            socks != null -> Socket(Proxy(Proxy.Type.SOCKS, socks))
            network != null -> network.socketFactory.createSocket()
            else -> Socket()
        }
        sent++
        val start = System.nanoTime()
        try {
            withProbeSocket(raw) { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(address, timeoutMs)
                val factory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
                (factory.createSocket(socket, target.hostString, target.port, true) as javax.net.ssl.SSLSocket).use { tls ->
                    tls.soTimeout = timeoutMs
                    tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    tls.startHandshake()
                }
            }
            samples += RttSample(seq, (System.nanoTime() - start) / 1_000_000.0, (start - t0) / 1_000_000.0)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { lastError = e.message ?: e.javaClass.simpleName }
        if (seq < count - 1) delay(gapMs)
    }
    return TrainResult(target.hostString, "tls", ProbeStats.stats(samples, sent), samples, sent, null, lastError)
}

/**
 * One SOCKS5 UDP ASSOCIATE, held open for as long as trains run through it.
 *
 * The control TCP connection IS the association: closing it tells the proxy to drop the relay,
 * after which every datagram silently vanishes and the train reads as 100 % loss. So the session
 * owns both sockets and closes them together.
 */
class SocksUdpSession private constructor(
    private val control: Socket,
    val socket: DatagramSocket,
    private val relay: InetSocketAddress,
) : AutoCloseable {

    fun packetFor(target: InetSocketAddress, payload: ByteArray): DatagramPacket {
        val wrapped = Socks5Codec.wrapUdp(target.address, target.port, payload)
        return DatagramPacket(wrapped, wrapped.size, relay)
    }

    val alive: Boolean get() = !control.isClosed && !socket.isClosed

    override fun close() {
        try { socket.close() } catch (_: Exception) {}
        try { control.close() } catch (_: Exception) {}
    }

    companion object {
        /**
         * Up to [n] associations, one per flow of a multi-flow train. Whatever opened is returned
         * (possibly fewer than asked, possibly none); a caller that gets none has no UDP path.
         */
        suspend fun openMany(proxy: InetSocketAddress, n: Int, timeoutMs: Int = 4000): List<SocksUdpSession> {
            val out = ArrayList<SocksUdpSession>(n)
            repeat(n) {
                try {
                    out += open(proxy, timeoutMs)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    out.forEach { it.close() }
                    throw e
                } catch (e: Exception) {
                    return out
                }
            }
            return out
        }

        /** Open an association through the proxy at [proxy], or throw with a readable reason. */
        suspend fun open(proxy: InetSocketAddress, timeoutMs: Int = 4000): SocksUdpSession =
            withContext(Dispatchers.IO) {
                val control = Socket()
                try {
                    control.connect(proxy, timeoutMs)
                    control.soTimeout = timeoutMs
                    val out = control.getOutputStream()
                    val input = DataInputStream(control.getInputStream())

                    out.write(Socks5Codec.GREETING); out.flush()
                    val method = ByteArray(2).also { input.readFully(it) }
                    if (!Socks5Codec.greetingAccepted(method)) throw IOException("socks: no acceptable method")

                    out.write(Socks5Codec.udpAssociateRequest()); out.flush()
                    val head = ByteArray(4).also { input.readFully(it) }
                    val (rep, rest) = Socks5Codec.replyHead(head) ?: throw IOException("socks: bad reply")
                    if (rep != 0) throw IOException("socks: UDP ASSOCIATE refused ($rep)")
                    if (rest < 0) throw IOException("socks: domain relay address unsupported")
                    val tail = ByteArray(rest).also { input.readFully(it) }
                    var (relayAddr, relayPort) = Socks5Codec.boundAddress(tail)
                        ?: throw IOException("socks: bad relay address")
                    // A relay bound to the wildcard address means "the proxy's own address".
                    if (relayAddr.isAnyLocalAddress) relayAddr = proxy.address
                    control.soTimeout = 0

                    val udp = DatagramSocket()
                    SocksUdpSession(control, udp, InetSocketAddress(relayAddr, relayPort))
                } catch (e: Exception) {
                    try { control.close() } catch (_: Exception) {}
                    throw e
                }
            }
    }
}

/**
 * ICMP echo through `/system/bin/ping`, which works unprivileged from this app's own uid.
 *
 * Only ever a distance floor: routers deprioritise ICMP and queue it separately (desktop,
 * 2026-08-19: a 53 ms spread on ICMP against 12 ms for a UDP train to the same network), so it is
 * never used for jitter or loss. And never through a tunnel: Xray's TUN answers ICMP itself,
 * which read as a 2–6 ms "ping" to 8.8.8.8 on the test phone.
 */
suspend fun icmpPing(host: String, count: Int = 5, intervalS: Double = 0.2, timeoutS: Int = 1): TrainResult =
    withContext(Dispatchers.IO) {
        val args = listOf("/system/bin/ping", "-n", "-c", count.toString(),
            "-i", String.format(java.util.Locale.US, "%.1f", intervalS.coerceAtLeast(0.2)),
            "-W", timeoutS.toString(), host)
        val out = try {
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            val budgetMs = (count * intervalS * 1000 + timeoutS * 1000 + 2000).toLong()
            if (!p.waitUpTo(budgetMs, TimeUnit.MILLISECONDS)) p.destroy()
            p.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            return@withContext TrainResult(host, "icmp", PathStats.empty(count), emptyList(), count, null,
                error = e.message ?: "ping")
        }
        val parsed = PingOutputParser.parse(out)
        val sent = parsed.transmitted ?: count
        val samples = parsed.samples.mapIndexed { i, s -> s.copy(seq = i) }
        TrainResult(host, "icmp", ProbeStats.stats(samples, sent), samples, sent, null,
            error = if (samples.isEmpty()) "no ICMP answer" else null)
    }

private val dnsWork = BoundedProbeWork(4)

/** A stuck platform resolver occupies at most one of four workers, never the calling session. */
suspend fun resolveV4(host: String, network: Network? = null, timeoutMs: Long = 1500): List<InetAddress> =
    kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        dnsWork.run {
            (network?.getAllByName(host) ?: InetAddress.getAllByName(host))
                .filterIsInstance<java.net.Inet4Address>()
        }
    }.orEmpty()

private fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        chars[i * 2] = HEX[v ushr 4]
        chars[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(chars)
}

private val HEX = "0123456789abcdef".toCharArray()
