package com.mlmvpn.scanner.engines.proton

import android.content.Context
import android.net.Network
import android.util.Log
import com.mlmvpn.scanner.engines.amnezia.AmzEngine
import com.mlmvpn.scanner.engines.amnezia.AmzServer
import com.mlmvpn.scanner.engines.amnezia.WgConfig
import com.mlmvpn.scanner.engines.flux.FluxNet
import com.mlmvpn.scanner.engines.cfdoctor.TlsChannel
import com.mlmvpn.scanner.engines.cfnet.CfShape
import com.mlmvpn.scanner.engines.cfnet.NetworkProfile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * The way WireGuard reaches one Proton server: every way this line might allow, raced at once.
 *
 * Proton's Stealth is WireGuard inside one TLS connection to the server's TCP 443 -- what Proton's
 * own apps use where UDP is filtered. Measured on the user's line (2026-10-06), with a WireGuard
 * client written for the test and checked first on an unfiltered network: to Proton's IPv4
 * addresses, over UDP on every port and over Stealth with any server name, the handshake is
 * answered once and then the filter blackholes the connection. To the same servers' IPv6 addresses
 * Stealth carries data, though not every server takes it (14 of 82 free ones did on that line),
 * and from inside Cloudflare ([ProtonCfCarrier]) every server does. Other lines have no IPv6 at
 * all, or drop UDP, or let Proton through untouched -- no fixed order suits them all.
 *
 * So the relay races the ways ([ProtonRoutePlan.lanes]): each is dialled at once, WireGuard's
 * packets go out on every way that is up, and the first way that brings WireGuard *data* back wins
 * -- a handshake answer is not enough, that is exactly what the filter above lets through. The
 * server answers on the way its latest packet came in on, so the way that wins is the one that both
 * works and is quickest. The others are closed, and the way that won is remembered for the server
 * and the network, where the next race gives it a head start. When the way in use stalls under the
 * tunnel, the race runs again without it.
 *
 * The framing is TunSafe's, as in ProtonVPN/wireguard-go (conn/bind_std_tcp.go, tcp_tls_utils.go):
 * a 2-byte header -- the top two bits a type, the other 14 the length -- and then
 *  - type 0: a whole WireGuard packet;
 *  - type 2: a data packet without its 16-byte header, which the receiver writes back from the
 *    last full data packet's first 8 bytes and its counter plus one.
 * This side always sends type 0 (proved enough on the line and off it; the short form only saves
 * 16 bytes) and reads both.
 *
 * WireGuard itself (the AmneziaWG library in the tunnel, Xray in a test) is pointed at a local UDP
 * port; each datagram there goes out on the way in use and each packet back out to it. The TLS is
 * only a disguise, as in Proton's client (which skips verification too): WireGuard authenticates
 * and encrypts end to end inside it.
 */
class ProtonStealth internal constructor(
    private val v4: String,
    private val v6: String?,
    private val network: Network?,
    private val plan: ProtonRoutePlan,
    /** The server's own WireGuard port, for the plain-UDP ways. */
    private val wgPort: Int = 51820,
    /** A lease on the Cloudflare carrier's SOCKS port ([ProtonCfCarrier.acquire]), asked on the first dial of that way. */
    private val carrier: (() -> ProtonCfCarrier.Lease?)? = null,
) : AmzEngine.RelayHandle {

    private val udp = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
    override val port: Int = udp.localPort

    @Volatile private var closed = false
    /** WireGuard's own socket, learned from the packets it sends. */
    @Volatile private var peer: SocketAddress? = null
    private val lock = Any()

    /** One way WireGuard's packets travel to the server and back. */
    private abstract inner class Path(val route: ProtonRoute) {
        val openedAt = System.currentTimeMillis()
        /** Taken out of use on purpose (rotated away from, lost a race): its end is no failure. */
        @Volatile var retired = false
        /** Already handled as broken: a second report of the same end says nothing new. */
        @Volatile var dropped = false
        /** The way that had a head start in its race. */
        var lead = false
        /** The rest of its lane: the IPv4 methods still to try if this one fails during the race. */
        var rest: List<ProtonRoute> = emptyList()
        /**
         * When WireGuard data went out on this way with nothing coming back since (0: nothing
         * waiting). A TCP connection the network has silently stalled carries no error: without this,
         * the tunnel sat dead until WireGuard itself gave up and re-handshook, 15-20 s later.
         */
        @Volatile var waitingSince = 0L
        /** A handshake sent on this way and not answered since. */
        @Volatile var handshakeAt = 0L
        @Volatile var lastDown = 0L
        /** WireGuard data packets back during the race this way is in. */
        @Volatile var raceData = 0
        /** Bytes carried both ways since it went into use, for [rotate]. */
        @Volatile var bytes = 0L
        /** How long it took to come up (dial and TLS); 0 for plain UDP. */
        var setupMs = 0L
        /** On trial in a speed comparison: the way before it waits beside it. */
        @Volatile var trial = false

        /** Hands [packet] on without waiting; false when the way cannot take it now. */
        protected abstract fun offer(packet: ByteArray): Boolean
        /** Starts reading what the server sends back (and writing, for a connection). */
        abstract fun start()
        abstract fun close()

        fun send(packet: ByteArray) {
            if (!offer(packet)) return
            val now = System.currentTimeMillis()
            if (packet[0] == 1.toByte() && handshakeAt == 0L) handshakeAt = now
            if (isPayload(packet) && waitingSince == 0L) waitingSince = now
            bytes += packet.size
            up++
        }

        /**
         * A handshake WireGuard sends again (it does after 5 s without an answer) on a way that has
         * carried nothing back since the last one: the filter has silenced it without a word.
         */
        fun silent(now: Long) = handshakeAt != 0L && lastDown < handshakeAt && now - handshakeAt >= STALE_MS
        /**
         * Data has gone unanswered for a while: the way is stalled, not slow. Sooner when another
         * connection waits ready beside it (a spare, or the way before a trial): a wrong call then
         * costs a switch, not a handshake.
         */
        fun stalled(now: Long) = waitingSince != 0L && now - waitingSince >= when {
            trial -> TRIAL_STALL_MS
            spare != null -> STALL_SPARE_MS
            else -> STALL_MS
        }
    }

    /** Stealth: one TLS connection, each WireGuard packet a TunSafe frame in it. */
    private inner class TlsPath(route: ProtonRoute, private val socket: Socket, private val input: InputStream, output: OutputStream) : Path(route) {
        private val out = BufferedOutputStream(output, 16 * 1024)
        /**
         * Frames waiting for the connection. A connection the network has stalled stops taking
         * writes; with them queued here instead, it holds up neither WireGuard nor the other ways
         * of a race, and once the queue is full what comes next is dropped (TCP inside the tunnel
         * sends it again).
         */
        private val queue = ArrayBlockingQueue<ByteArray>(SEND_QUEUE)
        @Volatile private var writer: Thread? = null

        override fun offer(packet: ByteArray): Boolean {
            val frame = ByteArray(packet.size + 2)
            frame[0] = (packet.size ushr 8).toByte()
            frame[1] = packet.size.toByte()
            System.arraycopy(packet, 0, frame, 2, packet.size)
            return queue.offer(frame)
        }

        override fun start() {
            writer = thread(name = "proton-stealth-send", isDaemon = true) {
                try {
                    while (true) {
                        out.write(queue.take())
                        // Whatever else is waiting goes in the same write: fewer, fuller TLS records.
                        while (true) out.write(queue.poll() ?: break)
                        out.flush()
                    }
                } catch (_: InterruptedException) {
                } catch (e: Exception) {
                    if (!closed) drop(this, "send: ${e.javaClass.simpleName}")
                }
            }
            thread(name = "proton-stealth-down", isDaemon = true) { pumpDown(this, input) }
        }

        override fun close() {
            runCatching { socket.close() }
            writer?.interrupt()
        }
    }

    /** Plain WireGuard: its datagrams straight to the server's own port, from a socket on the bare network. */
    private inner class UdpPath(route: ProtonRoute, private val socket: DatagramSocket) : Path(route) {
        override fun offer(packet: ByteArray): Boolean =
            runCatching { socket.send(DatagramPacket(packet, packet.size)); true }.getOrDefault(false)

        override fun start() {
            thread(name = "proton-udp-down", isDaemon = true) {
                val buf = ByteArray(65_535)
                try {
                    while (!closed) {
                        val p = DatagramPacket(buf, buf.size)
                        socket.receive(p)
                        if (p.length > 0) deliver(this, buf.copyOfRange(p.offset, p.offset + p.length))
                    }
                } catch (e: Exception) {
                    if (!closed) drop(this, "recv: ${e.javaClass.simpleName}")
                }
            }
        }

        override fun close() { runCatching { socket.close() } }
    }

    /** The way the tunnel is on. */
    @Volatile private var active: Path? = null

    /**
     * A second connection of the way in use, open and idle beside it. When the active one breaks
     * or is rotated away from, WireGuard's next packet goes out on this one at once -- no TLS
     * handshake (one to two seconds through Cloudflare) while the whole tunnel waits. Measured
     * 2026-10-07: through a free-plan Cloudflare Worker a connection is cut after roughly 10 MB,
     * every half minute under load, and each cut stalled everything for 1-3 s.
     */
    @Volatile private var spare: Path? = null
    @Volatile private var spareDialing = false
    /** Spares die unused here (the server closes idle connections): stop keeping them. */
    @Volatile private var spareUseless = false
    @Volatile private var spareShortLived = 0
    @Volatile private var spareCheckedAt = 0L
    /** This relay carries the tunnel ([carry]), not a test. */
    @Volatile private var carrying = false

    // The race; changed only under [lock].
    @Volatile private var racing = false
    @Volatile private var raceId = 0
    /** The race whose head-start way has failed: the others stop waiting for it. */
    @Volatile private var leadOver = -1
    private var lanesLeft = 0
    private val racers = ArrayList<Path>()
    private var lastRaceAt = 0L
    private var retries = 0
    /** When the race first gave WireGuard's packets to a way. */
    private var raceFirstSend = 0L
    /** The race started under a running WireGuard session (the way in use broke), not before one. */
    private var raceInSession = false
    /** A handshake answer came back during the race, and when. */
    @Volatile private var raceAnswered = false
    @Volatile private var answeredAt = 0L
    /**
     * The race is stuck, so the quickest ways are left out of it, a step at a time. The server
     * answers only the copy that reaches it first -- the quickest way's; when that way can send but
     * not receive (a filter that lets a handshake out and nothing back in), every handshake and
     * every answer is lost on it while the ways that work never get one (seen in the lab,
     * 2026-10-07: with IPv6 silenced on the way back the race never ended).
     */
    private var narrowing = false
    private val leftOut = HashSet<Path>()
    private var stepSince = 0L
    private var stepGotHandshake = false
    /** When each way stalled under the tunnel lately. */
    private val stalls = HashMap<String, ArrayDeque<Long>>()
    /** When the tunnel may next try to go back to the fastest way (see [returnToBest]). */
    @Volatile private var nextReturnAt = 0L
    /** When the head-start way of the current race came up. */
    @Volatile private var leadUpAt = 0L
    /** The tunnel has been on a way before (WireGuard has a session). */
    @Volatile private var everActive = false
    /** The way the tunnel was on before a speed comparison moved it: back on it if the new one fails. */
    @Volatile private var fallback: Path? = null
    private val tuning = java.util.concurrent.atomic.AtomicBoolean(false)
    /** What WireGuard sent during the race, for each way that comes up later in it. */
    private val recent = ArrayDeque<Pair<Long, ByteArray>>()
    /** Ways that stalled under the tunnel a moment ago: kept out of the races for a while. */
    private val avoidUntil = HashMap<String, Long>()

    private val dialing: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    @Volatile private var cfLease: ProtonCfCarrier.Lease? = null
    private val leaseLock = Any()
    private val sni = serverName()
    @Volatile private var viaListener: ((String) -> Unit)? = null
    @Volatile private var up = 0
    @Volatile private var down = 0

    init {
        thread(name = "proton-stealth-up", isDaemon = true) { pumpUp() }
    }

    /** WireGuard -> server: each datagram out on the way in use, or on every way of a race. */
    private fun pumpUp() {
        val buf = ByteArray(65_535)
        while (!closed) {
            val p = DatagramPacket(buf, buf.size)
            try { udp.receive(p) } catch (e: Exception) { if (closed) return else continue }
            peer = p.socketAddress
            val len = p.length
            if (len <= 0 || len > MAX_FRAME) continue
            val packet = buf.copyOfRange(p.offset, p.offset + len)
            val now = System.currentTimeMillis()
            active?.let { a ->
                val waiting = a.waitingSince
                when {
                    // A fresh handshake on a way that has answered nothing since the last one.
                    packet[0] == 1.toByte() && a.silent(now) -> drop(a, "silent since the last handshake", stalled = true)
                    a.stalled(now) -> drop(a, "nothing back for ${if (waiting > 0) (now - waiting) / 1000 else 0} s", stalled = true)
                    else -> Unit
                }
            }
            for (t in targets(packet, now)) t.send(packet)
            active?.let { a -> if (a.route.via == ProtonVia.CF && a.bytes >= ROTATE_BYTES) rotate() }
            if (isPayload(packet)) upkeep(now)
        }
    }

    /** Where [packet] goes: the way in use; with none, the spare -- or every way, raced. */
    private fun targets(packet: ByteArray, now: Long): List<Path> {
        synchronized(lock) {
            if (closed) return emptyList()
            active?.let { return listOf(it) }
            if (!racing) {
                spare?.let { s -> spare = null; activate(s); return listOf(s) }
                if (!startRace(now)) return emptyList()
            }
            recent.addLast(now to packet)
            while (recent.size > RECENT_MAX || (recent.isNotEmpty() && now - recent.first().first > RECENT_MS)) recent.removeFirst()
            if (racers.isEmpty()) return emptyList()
            if (raceFirstSend == 0L) raceFirstSend = now
            val initiation = packet[0] == 1.toByte()
            if (!narrowing) {
                if (racers.size < 2 || now - raceFirstSend < STUCK_MS) return racers.toList()
                narrowing = true
                leaveOutQuickest(now)
            } else {
                // With a WireGuard session up, each step gets a few seconds of traffic (from the
                // handshake answer on); before one, each gets one handshake -- WireGuard sends the
                // next only 5 s later.
                val next = if (raceAnswered || raceInSession) now - maxOf(stepSince, answeredAt) >= TURN_MS else initiation && stepGotHandshake
                if (next) leaveOutQuickest(now)
            }
            if (initiation) stepGotHandshake = true
            return racers.filter { it !in leftOut }
        }
    }

    /** Under [lock]. False when a race started a moment ago, or there is no way at all. */
    private fun startRace(now: Long): Boolean {
        if (now - lastRaceAt < RACE_GAP_MS) return false
        val avoided = avoidUntil.filterValues { it > now }.keys
        val lanes = plan.lanes(avoided)
        if (lanes.isEmpty()) return false
        lastRaceAt = now
        racing = true
        val id = ++raceId
        lanesLeft = lanes.size
        racers.clear()
        recent.clear()
        raceFirstSend = 0L
        raceInSession = everActive
        raceAnswered = false
        answeredAt = 0L
        narrowing = false
        leftOut.clear()
        leadUpAt = 0L
        // The way that measured fastest here, else the one that last carried data, gets a head
        // start: where it still works nothing else is dialled.
        val lead = plan.preferredRoute(avoided)?.takeIf { it == lanes.first().first() }
        Log.i(TAG, "$v4: racing ${lanes.joinToString(" ") { it.first().id }}" + (lead?.let { " (${it.id} first)" } ?: ""))
        // Plain UDP is up the moment it is opened, so it got every first handshake before the TLS
        // connections were up -- and on a line that lets UDP out but nothing back in, that handshake
        // was lost and the next came 5 s later (2026-10-07). So it joins a moment after them.
        val tlsToo = lanes.any { !it.first().udp }
        lanes.forEachIndexed { i, lane ->
            val first = lane.first()
            val late = when {
                first.id in avoided -> LATE_MS
                first.udp && tlsToo && first != lead -> UDP_LATE_MS
                else -> 0L
            }
            thread(name = "proton-race", isDaemon = true) { runLane(id, lane, lead = lead != null && i == 0, wait = lead != null && i > 0, lateMs = late) }
        }
        return true
    }

    private fun raceOn(id: Int) = !closed && racing && raceId == id

    /**
     * Under [lock]. Leaves the quickest ways still in the race out of it (plain UDP comes up at
     * once, so both of its ways go together); when that would leave nothing, all are back in.
     */
    private fun leaveOutQuickest(now: Long) {
        val inRace = racers.filter { it !in leftOut }
        val quickest = inRace.minOfOrNull { it.setupMs }
        val out = inRace.filter { it.setupMs == quickest }
        if (out.isEmpty() || out.size == inRace.size) leftOut.clear() else leftOut += out
        stepSince = now
        stepGotHandshake = false
        Log.i(TAG, "$v4: the race is stuck; now on " + racers.filter { it !in leftOut }.joinToString(" ") { it.route.id })
    }

    /** Dials one way of a race (the next IPv4 method when one fails) and puts it in the race. */
    private fun runLane(id: Int, lane: List<ProtonRoute>, lead: Boolean, wait: Boolean, lateMs: Long = 0L) {
        if (lateMs > 0) {
            // A way that stalled a moment ago (or plain UDP, see startRace) joins a moment later.
            val until = System.currentTimeMillis() + lateMs
            while (raceOn(id) && System.currentTimeMillis() < until) {
                try { Thread.sleep(50) } catch (_: InterruptedException) { break }
            }
        }
        if (wait) {
            // Until the head-start way has been up a moment (enough for a handshake and the first
            // data), or failed, or has had long enough.
            val start = System.currentTimeMillis()
            while (raceOn(id) && leadOver != id) {
                val now = System.currentTimeMillis()
                val up = leadUpAt
                if (now - start >= LEAD_MAX_MS || (up != 0L && now - up >= LEAD_UP_MS)) break
                try { Thread.sleep(50) } catch (_: InterruptedException) { break }
            }
        }
        for ((i, route) in lane.withIndex()) {
            if (!raceOn(id)) break
            val path = open(route) ?: continue
            val joined = synchronized(lock) {
                if (!raceOn(id)) false else {
                    path.rest = lane.drop(i + 1)
                    path.lead = lead
                    racers += path
                    if (lead) leadUpAt = System.currentTimeMillis()
                    // What WireGuard already sent goes out on this way too: its handshake does not
                    // wait for WireGuard to send it again, 5 s later. (Not once the race narrows.)
                    if (!narrowing && recent.isNotEmpty()) {
                        if (raceFirstSend == 0L) raceFirstSend = System.currentTimeMillis()
                        recent.forEach { path.send(it.second) }
                    }
                    true
                }
            }
            if (!joined) { path.close(); break }
            path.start()
            Log.i(TAG, "${route.id}: in the race")
            return
        }
        if (lead) leadOver = id
        laneEnded(id)
    }

    private fun laneEnded(id: Int) = synchronized(lock) { laneEndedLocked(id) }

    private fun laneEndedLocked(id: Int) {
        if (raceId != id || !racing) return
        if (--lanesLeft <= 0 && racers.isEmpty()) {
            racing = false
            recent.clear()
            Log.i(TAG, "$v4: no way carried anything")
        }
    }

    /** [s] brought WireGuard data back first: the tunnel goes on it and the race ends. */
    private fun win(s: Path) {
        val losers: List<Path>
        synchronized(lock) {
            if (closed || !racing || s !in racers) return
            racing = false
            raceId++
            racers.remove(s)
            losers = racers.toList()
            racers.clear()
            recent.clear()
            activate(s)
        }
        losers.forEach { it.retired = true }
        Log.i(TAG, "${s.route.id}: won the race" + if (losers.isEmpty()) "" else " (${losers.joinToString { it.route.id }} closed)")
        if (losers.isNotEmpty()) thread(name = "proton-race-end", isDaemon = true) {
            // Anything already in flight on them still comes in for a moment.
            try { Thread.sleep(LOSER_GRACE_MS) } catch (_: InterruptedException) {}
            losers.forEach { it.close() }
        }
        viaListener?.invoke(s.route.via.key)
        ensureSpare()
    }

    /** Under [lock]. */
    private fun activate(s: Path) {
        active = s
        everActive = true
        // A spare of another way (the tunnel just moved) would rotate the tunnel back onto it.
        spare?.takeIf { it.route != s.route }?.let { it.retired = true; it.close(); spare = null }
        s.handshakeAt = 0L
        s.waitingSince = 0L
        s.bytes = 0L
    }

    /**
     * Moves the traffic onto the spare before the active connection reaches the point where the
     * Cloudflare Worker carrying it is cut off. WireGuard follows on its own (the server answers on
     * the connection the last packet came in on), and the old one is still read for a moment for
     * what was already in flight -- so the switch costs nothing.
     */
    private fun rotate() {
        val old: Path
        synchronized(lock) {
            val next = spare ?: return
            old = active ?: return
            spare = null
            activate(next)
            old.retired = true
        }
        Log.i(TAG, "${old.route.id}: moved to a fresh connection after ${ROTATE_BYTES / 1_000_000} MB")
        thread(name = "proton-stealth-retire", isDaemon = true) {
            try { Thread.sleep(RETIRE_MS) } catch (_: InterruptedException) {}
            old.close()
        }
        ensureSpare()
    }

    /**
     * Keeps one more connection of the way in use open beside it -- only under the tunnel itself
     * ([carrying]): a spare for each of a hundred servers being tested would only load the line and
     * the Worker.
     */
    private fun ensureSpare() {
        val a = active ?: return
        if (closed || !carrying || spareUseless || spare != null || spareDialing || a.route.udp || a.trial) return
        spareDialing = true
        thread(name = "proton-stealth-spare", isDaemon = true) {
            try {
                val s = open(a.route) ?: return@thread
                // 0: not wanted any more; 1: kept as the spare; 2: the way in use broke meanwhile,
                // so it goes straight into use; 3: a race is on, so it joins it.
                val use = synchronized(lock) {
                    val current = active
                    when {
                        closed -> 0
                        current != null -> if (current.route == a.route && spare == null) { spare = s; 1 } else 0
                        !racing -> { activate(s); 2 }
                        else -> { racers += s; lanesLeft++; recent.forEach { s.send(it.second) }; 3 }
                    }
                }
                if (use == 0) { s.close(); return@thread }
                s.start()
                if (use == 2) Log.i(TAG, "${a.route.id}: spare connection taken into use")
            } finally {
                spareDialing = false
            }
        }
    }

    /**
     * While traffic flows, the spare is kept young: one left idle for 20-25 s was closed by the far
     * end and died the moment it was taken into use (measured 2026-10-07), which is the very stall
     * it exists to prevent. When the tunnel is idle nothing is refreshed: a drop then only costs the
     * next packet a handshake.
     */
    private fun upkeep(now: Long) {
        if (now - spareCheckedAt < 2_000) return
        spareCheckedAt = now
        returnToBest(now)
        if (!carrying || spareUseless) return
        val old = synchronized(lock) {
            spare?.takeIf { now - it.openedAt > SPARE_MAX_AGE_MS }?.also { spare = null; it.retired = true }
        }
        old?.close()
        ensureSpare()
    }

    /**
     * The tunnel ended up on a slower way (the fastest stalled, or did not come up in the race) and
     * the fastest is no longer left out: it gets the tunnel back -- the way in use waiting beside
     * it, as in a speed comparison, and taken back if the fastest does not carry for a while.
     * Without this a session that once fell back to a slow way stayed on it until it ended.
     */
    private fun returnToBest(now: Long) {
        if (!carrying || now < nextReturnAt || tuning.get() || fallback != null || racing) return
        val a = active ?: return
        val avoid = synchronized(lock) { avoidUntil.filterValues { it > now }.keys.toSet() }
        val best = plan.preferredRoute(avoid) ?: return
        if (best.via == a.route.via || !plan.faster(best, a.route)) return
        nextReturnAt = now + RETURN_RETRY_MS
        if (!tuning.compareAndSet(false, true)) return
        thread(name = "proton-stealth-return", isDaemon = true) {
            try {
                val path = open(best) ?: return@thread
                if (!moveTo(path)) { path.close(); return@thread }
                val until = System.currentTimeMillis() + RETURN_PROOF_MS
                while (System.currentTimeMillis() < until && active === path && !path.dropped && !closed) Thread.sleep(250)
                if (active === path && !path.dropped && path.lastDown > 0) {
                    path.trial = false
                    letGoOfFallback()
                    ensureSpare()
                    viaListener?.invoke(best.via.key)
                    Log.i(TAG, "${best.id}: back on the fastest way")
                } else moveBack(path)
            } catch (_: InterruptedException) {
            } finally {
                tuning.set(false)
            }
        }
    }

    private fun open(route: ProtonRoute): Path? {
        if (closed) return null
        val host = (if (route.ipv6) v6 else v4) ?: return null
        return if (route.udp) openUdp(host, route) else dial(host, route)
    }

    private fun openUdp(host: String, route: ProtonRoute): Path? = try {
        val socket = DatagramSocket()
        // On the bare network: Proton's own tunnel may be the device's VPN by now.
        network?.bindSocket(socket)
        socket.connect(InetSocketAddress(host, wgPort))
        UdpPath(route, socket)
    } catch (e: Exception) {
        Log.i(TAG, "${route.id}: no UDP socket (${e.javaClass.simpleName})")
        null
    }

    private fun dial(host: String, route: ProtonRoute): Path? {
        var raw: Socket? = null
        val method = route.method
        try {
            val t0 = System.currentTimeMillis()
            val socket = if (route.via == ProtonVia.CF) {
                // From inside Cloudflare: the carrier's SOCKS port, the server's own IPv4 address
                // asked of it -- the Worker at the far end opens the TCP connection to Proton.
                // One lease per relay, though several connections may dial at once.
                val lease = synchronized(leaseLock) { cfLease ?: carrier?.invoke()?.also { cfLease = it } }
                    ?: run { Log.i(TAG, "${route.id}: no Cloudflare carrier here"); return null }
                Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", lease.port)))
            } else {
                // On the bare network: Proton's own tunnel may be the device's VPN by now, and this
                // connection is the one carrying that tunnel.
                network?.socketFactory?.createSocket() ?: Socket()
            }
            raw = socket
            dialing += socket
            if (closed) { socket.close(); return null }
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, PORT), when (route.via) {
                ProtonVia.V6, ProtonVia.U6 -> CONNECT_V6_MS
                ProtonVia.CF -> CONNECT_CF_MS
                ProtonVia.V4, ProtonVia.U4 -> CONNECT_MS
            })
            // Through Cloudflare the TLS handshake also waits for the Worker to reach the server.
            socket.soTimeout = if (route.via == ProtonVia.CF) HANDSHAKE_CF_MS else HANDSHAKE_MS
            val path = if (method == ProtonMethod.PLAIN) {
                val s = CONTEXT.socketFactory.createSocket(socket, sni, PORT, true) as SSLSocket
                s.sslParameters = s.sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
                s.startHandshake()
                s.soTimeout = 0
                TlsPath(route, s, s.inputStream, s.outputStream)
            } else {
                // Same record/TCP masks as PattNG/PattN. Android supplies the TLS engine; it is
                // not a browser uTLS fingerprint. WireGuard remains the authenticated inner layer.
                val channel = TlsChannel(socket.inputStream, ProtonHello.output(socket.outputStream, method), sni, PORT,
                    context = CONTEXT, protocol = if (method == ProtonMethod.PATTNG_TLS12) "TLSv1.2" else null,
                    cipherSuites = CfShape.CIPHER_SUITES.split(':'))
                socket.soTimeout = 0
                TlsPath(route, socket, channel.input, channel.output)
            }
            // Proton's client waits this long after the TLS handshake: the server side sometimes
            // drops a WireGuard handshake that arrives right behind it.
            Thread.sleep(100)
            path.setupMs = System.currentTimeMillis() - t0
            Log.i(TAG, "${route.id}: TLS up in ${path.setupMs} ms")
            return path
        } catch (e: Exception) {
            runCatching { raw?.close() }
            // The message names the cause ("Connection closed by peer"); its addresses -- this phone's
            // own among them -- are cut out.
            Log.i(TAG, "${route.id}: no Stealth (${e.javaClass.simpleName}: ${e.message?.replace(ADDRESS, "…")?.take(100)})")
            return null
        } finally {
            raw?.let { dialing -= it }
        }
    }

    /** Server -> WireGuard over a Stealth connection: each frame back as one datagram. */
    private fun pumpDown(s: Path, stream: InputStream) {
        var prefix = ByteArray(8)
        var counter = 0L
        var hasDataHeader = false
        try {
            val input = DataInputStream(BufferedInputStream(stream))
            val header = ByteArray(2)
            while (!closed) {
                input.readFully(header)
                val type = (header[0].toInt() and 0xff) ushr 6
                val size = ((header[0].toInt() and 0x3f) shl 8) or (header[1].toInt() and 0xff)
                val packet = when (type) {
                    TYPE_NORMAL -> ByteArray(size).also { input.readFully(it) }
                    TYPE_DATA -> ByteArray(size + WG_DATA_HEADER).also { p ->
                        if (!hasDataHeader || size + WG_DATA_HEADER > MAX_FRAME) throw IOException("invalid compressed frame")
                        System.arraycopy(prefix, 0, p, 0, 8)
                        putLongLe(p, 8, counter)
                        input.readFully(p, WG_DATA_HEADER, size)
                    }
                    else -> throw IOException("unknown frame type $type")
                }
                if (type == TYPE_NORMAL && isWgData(packet)) {
                    prefix = packet.copyOfRange(0, 8)
                    counter = getLongLe(packet, 8)
                    hasDataHeader = true
                }
                if (isWgData(packet)) counter++
                deliver(s, packet)
            }
        } catch (e: Exception) {
            if (!closed) drop(s, "recv: ${e.javaClass.simpleName}")
            return
        }
        drop(s, "closed")
    }

    /** A packet from the server, on whichever way it came: back to WireGuard. */
    private fun deliver(s: Path, packet: ByteArray) {
        // The debug lab's stand-in for a filter that silences one way.
        if (testBlackhole == s.route.via.key) return
        val now = System.currentTimeMillis()
        s.lastDown = now
        s.handshakeAt = 0L
        s.waitingSince = 0L
        s.bytes += packet.size
        down++
        if (racing && packet[0] == 2.toByte()) {
            raceAnswered = true
            answeredAt = now
            Log.i(TAG, "${s.route.id}: handshake answered")
        }
        if (isPayload(packet)) {
            if (racing && ++s.raceData >= WIN_DATA) win(s)
            if (s === active) meter(now, packet.size)
        }
        peer?.let { runCatching { udp.send(DatagramPacket(packet, packet.size, it)) } }
    }

    private fun drop(s: Path, why: String, stalled: Boolean = false) {
        if (s.dropped) return
        s.dropped = true
        // 1: the way in use; 2: the spare; 3: a way in a race.
        var role = 0
        var promoted = false
        var again = false
        var cont: List<ProtonRoute> = emptyList()
        var contRace = 0
        synchronized(lock) {
            when {
                active === s -> {
                    role = 1
                    active = null
                    val now = System.currentTimeMillis()
                    // A way that stalls again and again (a line that lets each new connection run a
                    // few seconds and then silences it) is given up on: its spare would only stall
                    // the same way, so every other way is raced instead. Not for a stall now and then,
                    // which any way has and its spare rides out: on the user's line (2026-10-07)
                    // Stealth over IPv6 stalled about once a minute at 4 Mbps, and giving it up put
                    // the tunnel on a 0.6 Mbps way.
                    if (stalled) {
                        val recent = stalls.getOrPut(s.route.id) { ArrayDeque() }
                        recent.addLast(now)
                        while (recent.isNotEmpty() && now - recent.first() > STALL_WINDOW_MS) recent.removeFirst()
                        // ...and only when another way measured working here: the only way that works
                        // is ridden out on its spares, stalls and all.
                        again = recent.size >= STALLS_TO_GIVE_UP && plan.otherWayWorks(s.route)
                        // Last in the next races for a while: the others get the first chance, and it
                        // still joins a moment later when they bring nothing.
                        avoidUntil[s.route.id] = now + AVOID_MS
                    }
                    // During a speed comparison the way the tunnel was on goes back on; otherwise the
                    // spare takes over at once: the next packet goes out without a handshake.
                    val back = fallback?.takeIf { !it.dropped }
                    fallback = null
                    if (back != null) { activate(back); promoted = true }
                    else if (!again && !s.route.udp) spare?.let { next -> spare = null; activate(next); promoted = true }
                    else spare?.let { it.retired = true; it.close(); spare = null }
                }
                spare === s -> { role = 2; spare = null }
                fallback === s -> fallback = null
                racers.remove(s) -> {
                    role = 3
                    if (s.lead) leadOver = raceId
                    // The lane's next IPv4 method gets its turn; else the lane is done.
                    if (racing && s.rest.isNotEmpty()) { cont = s.rest; contRace = raceId } else laneEndedLocked(raceId)
                }
                else -> Unit
            }
        }
        val quiet = closed || s.retired
        // A stalled connection is still read a moment -- what was in flight on it may come in yet;
        // a broken one is done with.
        if (stalled && role == 1) closeLater(s) else s.close()
        if (cont.isNotEmpty()) thread(name = "proton-race", isDaemon = true) { runLane(contRace, cont, lead = false, wait = false) }
        if (quiet) return
        when (role) {
            1 -> {
                Log.i(TAG, "$v4: ${s.route.id} dropped ($why)" + (if (again) ", again" else "") + "; " +
                    if (promoted) "${active?.route?.id} took over" else "every other way is raced")
                // Plain WireGuard that stalls under the tunnel: this network lets it start, then
                // drops it. Like a way that keeps stalling, it is worth nothing here, however fast.
                if (stalled && s.route.udp) plan.failed(s.route)
                if (stalled && (again || s.route.udp)) plan.unstable(s.route)
            }
            // A spare the server closes before it is ever used: on such a server keeping one only
            // makes connections for nothing, so twice in a row and they stop.
            2 -> if (System.currentTimeMillis() - s.openedAt < SPARE_MIN_LIFE_MS) {
                if (++spareShortLived >= 2) { spareUseless = true; Log.i(TAG, "$v4: the server closes idle connections; no spare kept") }
            } else spareShortLived = 0
            3 -> Log.i(TAG, "${s.route.id}: out of the race ($why)")
        }
        ensureSpare()
    }

    // ── a minute's traffic, for the log: how each way really does on each line ─────────────────
    @Volatile private var meterStart = 0L
    @Volatile private var meterBytes = 0L
    @Volatile private var windowStart = 0L
    @Volatile private var windowBytes = 0L
    @Volatile private var peakKbps = 0L

    private fun meter(now: Long, size: Int) {
        if (!carrying) return
        if (meterStart == 0L) { meterStart = now; windowStart = now }
        meterBytes += size
        windowBytes += size
        if (now - windowStart >= 2_000) {
            peakKbps = maxOf(peakKbps, windowBytes * 8 / (now - windowStart))
            windowStart = now
            windowBytes = 0
        }
        if (now - meterStart >= 60_000) {
            if (meterBytes >= 100_000) Log.i(TAG, String.format(Locale.US, "%s: %.1f MB in the last minute, peak %.1f Mbps",
                active?.route?.id, meterBytes / 1e6, peakKbps / 1000.0))
            meterStart = now
            meterBytes = 0
            peakKbps = 0
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val all = synchronized(lock) {
            (racers + listOfNotNull(active, spare, fallback)).also { racers.clear(); active = null; spare = null; fallback = null; racing = false }
        }
        if (up > 0) Log.i(TAG, "$v4: closed after $up packets out, $down back")
        runCatching { udp.close() }
        // Dials in progress hold their sockets for seconds; closing them is enough.
        dialing.forEach { runCatching { it.close() } }
        all.forEach { it.retired = true; it.close() }
        // Not here: a dial may hold [leaseLock] for seconds while the carrier starts, and close()
        // runs on the main thread (ANR #844, #848). The lease goes as soon as that dial lets go.
        thread(name = "proton-lease-close", isDaemon = true) { synchronized(leaseLock) { cfLease?.close(); cfLease = null } }
    }

    /** The way in use (for the screen); before a race is won, the one expected to win, or "all". */
    override val via: String get() = active?.route?.via?.key ?: plan.preferredRoute()?.via?.key ?: "all"

    /** The way in use, method and all, for the debug lab. */
    internal val routeId: String? get() = active?.route?.id

    override fun watchVia(listener: (String) -> Unit) { viaListener = listener }

    override fun validated() {
        if (closed) return
        // The check's answer came back while the race was still undecided: the way that carried most of it.
        if (active == null) synchronized(lock) { racers.maxByOrNull { it.raceData }?.takeIf { it.raceData > 0 } }?.let { win(it) }
        val a = active ?: return
        plan.validated(a.route)
        Log.i(TAG, "${a.route.id}: application data verified")
    }

    /**
     * The race picks the way that answers first, which is not always the fastest: on the user's
     * line (2026-10-07) Stealth over IPv6 won every race and carried 3-4 Mbps while the line did
     * 22-32 and Cloudflare had carried 18. So under the tunnel, once a network is due (nothing
     * measured there lately), each other way gets the tunnel for one short timed download
     * ([measure], through the tunnel) and the fastest keeps it -- by a clear margin, so a
     * measurement's noise does not move it. The next race here gives that way the head start.
     */
    override fun tune(measure: (stillValid: () -> Boolean) -> Long?) {
        if (closed || !carrying || !plan.speedsDue() || !tuning.compareAndSet(false, true)) return
        try {
            val first = active ?: return
            val avoid = synchronized(lock) { avoidUntil.filterValues { it > System.currentTimeMillis() }.keys.toSet() }
            val others = plan.lanes(avoid).map { it.first() }.filter { it.via != first.route.via && it.id !in avoid }.take(CONTENDERS)
            if (others.isEmpty()) return
            var best = first.route
            // The way in use may rotate onto its spare meanwhile: the same way still.
            val onFirst = { active?.route == first.route }
            var bestRate = measure(onFirst)?.takeIf { onFirst() } ?: return
            val measured = linkedMapOf(best.id to bestRate)
            for (route in others) {
                if (closed || active?.route != best) break
                // A way that cannot carry the sample itself measures 0: it is not tried again here
                // until the next comparison.
                val path = open(route)
                if (path == null) { measured[route.id] = 0L; continue }
                if (!moveTo(path)) { path.close(); break }
                val held = { active === path && !path.dropped }
                val rate = measure(held)?.takeIf { held() }
                measured[route.id] = rate ?: 0L
                if (rate != null && rate * FASTER_DEN > bestRate * FASTER_NUM) {
                    best = route
                    bestRate = rate
                    path.trial = false
                    letGoOfFallback()
                    ensureSpare()
                } else moveBack(path)
            }
            plan.speeds(measured)
            if (best != first.route) {
                plan.validated(best)
                viaListener?.invoke(best.via.key)
            }
            Log.i(TAG, "$v4: speeds here " + measured.entries.joinToString { String.format(Locale.US, "%s %.1f Mbps", it.key, it.value / 1e6) } + "; on ${best.id}")
        } finally {
            tuning.set(false)
        }
    }

    /** Puts [path] in use, the way in use kept open beside it as [fallback]. */
    private fun moveTo(path: Path): Boolean {
        synchronized(lock) {
            if (closed || racing) return false
            fallback = active ?: return false
            path.trial = true
            activate(path)
        }
        path.start()
        Log.i(TAG, "${path.route.id}: tried for speed")
        return true
    }

    /** The comparison is over and [path] lost it: the way before it goes back on. */
    private fun moveBack(path: Path) {
        val back: Path?
        synchronized(lock) {
            back = fallback?.takeIf { !it.dropped && active === path }
            fallback = null
            if (back != null) activate(back)
        }
        // Lost or broken, the tried way is done with (when it broke, the way before it is already back on).
        if (back != null || path.dropped) closeLater(path) else path.trial = false
        ensureSpare()
    }

    private fun letGoOfFallback() {
        val old = synchronized(lock) { fallback.also { fallback = null } } ?: return
        closeLater(old)
    }

    /** Out of use, but still read for a moment for what was in flight on it. */
    private fun closeLater(path: Path) {
        path.retired = true
        thread(name = "proton-stealth-retire", isDaemon = true) {
            try { Thread.sleep(LOSER_GRACE_MS) } catch (_: InterruptedException) {}
            path.close()
        }
    }

    override fun carry() {
        if (closed) return
        carrying = true
        // A moment after the tunnel came up, not inside its first seconds.
        thread(name = "proton-stealth-spare-start", isDaemon = true) {
            try { Thread.sleep(SPARE_DELAY_MS) } catch (_: InterruptedException) {}
            if (!closed) ensureSpare()
        }
    }

    /** One more race from scratch, once: the first already tried every way at once. */
    override fun retryAfterFailure(): Boolean {
        val all = synchronized(lock) {
            if (closed || retries >= 1) return false
            retries++
            racing = false
            raceId++
            everActive = false
            lastRaceAt = 0L
            recent.clear()
            (racers + listOfNotNull(active, spare)).also { racers.clear(); active = null; spare = null }
        }
        all.forEach { it.retired = true; it.close() }
        spareUseless = false
        spareShortLived = 0
        peer = null
        Log.i(TAG, "$v4: racing again")
        return true
    }

    companion object {
        private const val TAG = "ProtonStealth"
        /** Proton's Stealth port. */
        const val PORT = 443
        private const val CONNECT_V6_MS = 3_000
        private const val CONNECT_MS = 6_000
        private const val CONNECT_CF_MS = 5_000
        private const val HANDSHAKE_MS = 8_000
        private const val HANDSHAKE_CF_MS = 15_000
        private const val STALE_MS = 4_000L
        /**
         * Data out and nothing back for this long: the way is stalled. Generous against a slow
         * answer -- a live connection acknowledges within a round trip, and through Cloudflare on
         * a busy line one is a second or two -- and well short of WireGuard's own 15-20 s. A false
         * call costs little: the spare takes over.
         */
        private const val STALL_MS = 5_000L
        private const val STALL_SPARE_MS = 3_000L
        /** A WireGuard keepalive: a data message with no payload, header and tag only. */
        private const val WG_KEEPALIVE = 32
        /**
         * WireGuard data packets back on one way that win it the race: more than a handshake
         * answer, which is all a filter that silences WireGuard lets through.
         */
        private const val WIN_DATA = 2
        /** How long the head-start way has the race to itself once it is up, and at most. */
        private const val LEAD_UP_MS = 2_500L
        private const val LEAD_MAX_MS = 5_000L
        /** A race with nothing back for this long since its first packet goes one way at a time. */
        private const val STUCK_MS = 3_000L
        /** One way's turn, with a WireGuard session up. */
        private const val TURN_MS = 2_500L
        /** A way must be this much faster (5/4) to take the tunnel over in a speed comparison. */
        private const val FASTER_NUM = 5L
        private const val FASTER_DEN = 4L
        /** How many other ways one speed comparison tries. */
        private const val CONTENDERS = 2
        /** A way on trial with nothing back for this long has failed it: the way before it is right there. */
        private const val TRIAL_STALL_MS = 2_000L
        /** Races start at most this often: WireGuard sends its handshake again every 5 s. */
        private const val RACE_GAP_MS = 4_000L
        /** How long the losers of a race are still read for what was in flight on them. */
        private const val LOSER_GRACE_MS = 2_000L
        /** How long a way that stalled under the tunnel stays out of the races. */
        private const val AVOID_MS = 5 * 60_000L
        /** How long a way that stalled a moment ago waits before it joins a race. */
        private const val LATE_MS = 3_000L
        /** How long plain UDP waits for the TLS connections of a race to come up first. */
        private const val UDP_LATE_MS = 1_200L
        /** A way that stalls this often under the tunnel is given up on instead of carried on its spare. */
        private const val STALLS_TO_GIVE_UP = 3
        private const val STALL_WINDOW_MS = 60_000L
        /** How long a way going back to the fastest one must carry before the old one is let go. */
        private const val RETURN_PROOF_MS = 8_000L
        /** After a failed try, the next try to go back to the fastest way. */
        private const val RETURN_RETRY_MS = 90_000L
        /** What WireGuard sent lately, handed to each way that comes up during a race. */
        private const val RECENT_MS = 5_000L
        private const val RECENT_MAX = 32
        /** Frames waiting for one Stealth connection (see TlsPath.queue). */
        private const val SEND_QUEUE = 512
        /**
         * Through Cloudflare, the active connection is swapped for the spare after this much: a
         * free-plan Worker cut a connection at about 10 MB, and some well before (2026-10-07, three
         * five-minute soaks).
         */
        private const val ROTATE_BYTES = 4_000_000L
        /** How long a rotated-away connection is still read for what was already in flight. */
        private const val RETIRE_MS = 10_000L
        /** A spare closed sooner than this, unused, means the far end will not keep one open. */
        private const val SPARE_MIN_LIFE_MS = 10_000L
        /** A spare older than this is replaced while traffic flows (see upkeep). */
        private const val SPARE_MAX_AGE_MS = 12_000L
        private const val SPARE_DELAY_MS = 3_000L
        private const val MAX_FRAME = 0x3fff
        private const val TYPE_NORMAL = 0
        private const val TYPE_DATA = 2
        private const val WG_DATA_HEADER = 16
        /** How long a way that carried data gets the head start: for that server, and on the network. */
        private const val SERVER_MEMORY_MS = 20 * 60_000L
        private const val NETWORK_MEMORY_MS = 6 * 3600_000L
        /** How long a network that dropped plain WireGuard is left out of it. */
        private const val UDP_MEMORY_MS = 12 * 3600_000L
        /** How long the speeds measured on a network lead its races. */
        private const val SPEED_MEMORY_MS = 3 * 24 * 3600_000L
        /** An IPv4 or IPv6 address (with its port) inside an exception message. */
        private val ADDRESS = Regex("/?\\[?[0-9a-fA-F]*[:.][0-9a-fA-F:.]{5,}\\]?(\\s*\\(port \\d+\\))?")

        /** Debug lab only: what comes back on this way ("v6", "cf", ...) is thrown away, as a filter would. */
        @Volatile internal var testBlackhole: String? = null
        /** Debug lab only: this network is treated as having no IPv6, as most lines in Iran have none. */
        @Volatile internal var testNoV6 = false

        /**
         * The relay the Amnezia engine asks for every connect and every test: Proton's servers
         * (the ids [ProtonEngine] keeps) go through it, every other server as before.
         */
        fun relay(context: Context): AmzEngine.Relay {
            val app = context.applicationContext
            return AmzEngine.Relay { s: AmzServer ->
                if (s.id !in ProtonEngine.storedIds(app)) null
                else {
                    val here = FluxNet.current(app)
                    val v6 = ProtonEngine.storedV6(app, s.host)?.takeIf { !testNoV6 && FluxNet.hasV6(app, here.network) }
                    val cf = here.network != null && ProtonCfCarrier.possible(app)
                    val prefs = app.getSharedPreferences("proton_routes", Context.MODE_PRIVATE)
                    val serverKey = NetworkProfile.hashKey("${here.key}|${here.network?.networkHandle}|${v6 != null}|${s.host}")
                    val netKey = "net|" + NetworkProfile.hashKey("${here.key}|${v6 != null}|$cf")
                    fun remembered(key: String, maxAge: Long) = prefs.getString(key, null)?.split('|')
                        ?.takeIf { it.size == 2 && System.currentTimeMillis() - (it[1].toLongOrNull() ?: 0) in 0..maxAge }?.first()
                    // Plain WireGuard that stalled under the tunnel on this network is left out of
                    // its races for a while: such a line lets it start and then drops it.
                    val udpKey = "udp|" + NetworkProfile.hashKey(here.key)
                    val udpOk = System.currentTimeMillis() - prefs.getLong(udpKey, 0L) !in 0..UDP_MEMORY_MS
                    // What each way measured on this network (a speed comparison under the tunnel):
                    // "way=bits/s;way=bits/s|when", kept for days -- the line, not the server, decides
                    // it; apart for servers with and without IPv6, whose ways differ.
                    val speedKey = "speed|" + NetworkProfile.hashKey("${here.key}|${v6 != null}")
                    fun speeds(): Pair<Map<String, Long>, Long> {
                        val v = prefs.getString(speedKey, null)?.split('|')?.takeIf { it.size == 2 } ?: return emptyMap<String, Long>() to 0L
                        val at = v[1].toLongOrNull() ?: return emptyMap<String, Long>() to 0L
                        if (System.currentTimeMillis() - at !in 0..SPEED_MEMORY_MS) return emptyMap<String, Long>() to 0L
                        return v[0].split(';').mapNotNull { e -> e.split('=').takeIf { it.size == 2 }?.let { it[0] to (it[1].toLongOrNull() ?: return@mapNotNull null) } }.toMap() to at
                    }
                    val plan = ProtonRoutePlan(v6 != null, cf, { remembered(serverKey, SERVER_MEMORY_MS) ?: remembered(netKey, NETWORK_MEMORY_MS) },
                        udp = udpOk, udp6 = udpOk,
                        speeds = { speeds() },
                        onSpeeds = { measured ->
                            // The latest comparison replaces the one before: its ways were measured on
                            // one server, a moment apart -- a speed from another server or hour is no match.
                            prefs.edit().putString(speedKey, measured.entries.joinToString(";") { "${it.key}=${it.value}" } + "|${System.currentTimeMillis()}").apply()
                        },
                        onUnstable = { id ->
                            // Fast but unable to hold the tunnel: worth nothing here, the rest kept as measured.
                            val (measured, at) = speeds()
                            if (id in measured) prefs.edit().putString(speedKey, (measured + (id to 0L)).entries.joinToString(";") { "${it.key}=${it.value}" } + "|$at").apply()
                        }) { id, ok ->
                        val edit = prefs.edit()
                        if (ok) "$id|${System.currentTimeMillis()}".let { edit.putString(serverKey, it).putString(netKey, it) }
                        else if (prefs.getString(serverKey, null)?.substringBefore('|') == id) edit.remove(serverKey)
                        if (id.startsWith(ProtonVia.U4.key) || id.startsWith(ProtonVia.U6.key)) {
                            if (ok) edit.remove(udpKey) else edit.putLong(udpKey, System.currentTimeMillis())
                        }
                        edit.apply()
                    }
                    // Without IPv6 here Cloudflare is the likely way: its core starts now, so its
                    // lane of the race does not wait for it.
                    if (cf && (v6 == null || remembered(netKey, NETWORK_MEMORY_MS)?.startsWith(ProtonVia.CF.key) == true)) ProtonCfCarrier.warm(app, here)
                    val wgPort = WgConfig.parseConf(s.raw)?.endpointPort ?: 51820
                    ProtonStealth(s.host, v6, here.network, plan, wgPort) { ProtonCfCarrier.acquire(app, here) }
                }
            }
        }

        private fun isWgData(p: ByteArray) =
            p.size >= WG_DATA_HEADER && p[0] == 4.toByte() && p[1] == 0.toByte() && p[2] == 0.toByte() && p[3] == 0.toByte()

        /** WireGuard data with something in it (a keepalive is the bare 32-byte header, never answered). */
        private fun isPayload(p: ByteArray) = p.size > WG_KEEPALIVE && p[0] == 4.toByte()

        private fun putLongLe(b: ByteArray, at: Int, v: Long) { for (i in 0 until 8) b[at + i] = (v ushr (8 * i)).toByte() }
        private fun getLongLe(b: ByteArray, at: Int): Long { var v = 0L; for (i in 0 until 8) v = v or ((b[at + i].toLong() and 0xff) shl (8 * i)); return v }

        private val random = SecureRandom()
        private val TLDS = listOf("com", "net", "org", "it", "fr", "me", "ru", "cn", "es", "tr", "top", "xyz", "info")

        /** A made-up name, as Proton's client sends (its random strategy): 3 to 12 letters and a common ending. */
        private fun serverName(): String {
            val n = 3 + random.nextInt(10)
            val name = CharArray(n) { 'a' + random.nextInt(26) }
            return String(name) + "." + TLDS[random.nextInt(TLDS.size)]
        }

        /** Proton's server presents a certificate for whatever name was asked: nothing to verify. */
        private val CONTEXT: SSLContext by lazy {
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }), random)
            }
        }
    }
}
