package com.mlmvpn.scanner.engines.tgproxy

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/** One carrier session to the relay, as the bridge sees it. */
interface TgLane {
    /** Queues one message; false when the lane cannot take it (it is then treated as dead). */
    fun send(frame: ByteArray): Boolean
    fun close()
}

/** What a lane reports back: every message from the relay, and its end. */
interface TgLaneEvents {
    fun message(bytes: ByteArray)
    fun closed()
}

/** Opens lanes to the relay. The real one is [TgWsLanes]; tests use an in-memory relay. */
interface TgLaneFactory {
    /** Data-centre connections one lane may carry, as the relay's page says; 45 until known. */
    val budget: Int

    /** Opens a lane; blocking. Null when none could be opened. */
    fun open(events: TgLaneEvents): TgLane?

    /** A fresh session token the relay sent in a PING, for the next lane. */
    fun token(token: String) {}
}

/** What the screen shows of a running bridge. */
data class TgBridgeStats(val streams: Int, val lanes: Int, val up: Long, val down: Long, val laneFailures: Int)

/**
 * The MTProto bridge on the phone. Telegram uses it as a plain MTProxy on 127.0.0.1 (`tg://proxy`);
 * every TCP connection Telegram opens to it becomes one stream of the WEB proxy protocol, carried to
 * the user's own relay over sessions ("lanes") that [TgLaneFactory] opens -- through a Cloudflare
 * address chosen on this network rather than whatever DNS hands out.
 *
 * The bytes are not touched: Telegram speaks obfuscated2 with the proxy's secret and the relay opens
 * it, exactly as with the WEB proxy. Lanes behave as in the relay's bridge page: new streams go to
 * the current lane; a lane close to its dial budget hands over to one made beforehand and drains; a
 * lane that dies closes only its own connections, which Telegram reconnects by itself; PINGs are
 * answered here and their token opens later lanes; a lane that pinged and then fell silent is dropped.
 */
class TgBridge(
    private val factory: TgLaneFactory,
    /** 0 picks any free port (tests). */
    private val port: Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val quietMs: Long = QUIET_MS,
    private val watchMs: Long = WATCH_MS,
    private val lanePauseMs: Long = LANE_PAUSE_MS,
) : Closeable {

    private val lock = Any()
    @Volatile private var closed = false
    private var server: ServerSocket? = null
    private val streams = HashMap<Int, Stream>()
    private val lanes = LinkedHashSet<Lane>()
    private var cur: Lane? = null
    private var next: Lane? = null
    private var nextId = 1
    private val up = java.util.concurrent.atomic.AtomicLong()
    private val down = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var laneFailures = 0

    private inner class Stream(val id: Int, val socket: Socket, val lane: Lane) {
        val gate = ReentrantLock()
        val credit = gate.newCondition()
        var sendWindow = INITIAL_WINDOW // guarded by gate
        val out = LinkedBlockingQueue<ByteArray>()
        @Volatile var dead = false

        fun kill() {
            if (dead) return
            dead = true
            out.offer(POISON)
            gate.withLock { credit.signalAll() }
            runCatching { socket.close() }
        }
    }

    private inner class Lane {
        var link: TgLane? = null
        val pending = ArrayList<ByteArray>()
        val ids = HashSet<Int>()
        var opens = 0
        var dead = false
        @Volatile var heard = 0L
        @Volatile var pinged = false
        val events = object : TgLaneEvents {
            override fun message(bytes: ByteArray) = fromRelay(this@Lane, bytes)
            override fun closed() = lost(this@Lane)
        }
    }

    /** Listens on 127.0.0.1 and returns the port. Throws when the port is taken. */
    fun start(): Int {
        val s = ServerSocket(port, 64, InetAddress.getByName("127.0.0.1"))
        server = s
        thread(name = "tg-bridge-accept", isDaemon = true) { acceptLoop(s) }
        thread(name = "tg-bridge-watch", isDaemon = true) { watch() }
        return s.localPort
    }

    fun stats(): TgBridgeStats = synchronized(lock) { TgBridgeStats(streams.size, lanes.count { it.link != null }, up.get(), down.get(), laneFailures) }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { server?.close() }
        val all: List<Stream>
        val links: List<TgLane>
        synchronized(lock) {
            all = streams.values.toList()
            streams.clear()
            links = lanes.mapNotNull { it.link }
            lanes.forEach { it.dead = true }
            lanes.clear()
            cur = null
            next = null
        }
        all.forEach { it.kill() }
        links.forEach { runCatching { it.close() } }
    }

    // ------------------------------------------------------------------ Telegram's side

    private fun acceptLoop(s: ServerSocket) {
        while (!closed) {
            val socket = try { s.accept() } catch (e: Exception) { break }
            runCatching { socket.tcpNoDelay = true }
            val stream = synchronized(lock) { admit(socket) }
            if (stream == null) {
                runCatching { socket.close() }
                continue
            }
            thread(name = "tg-bridge-up", isDaemon = true) { readLoop(stream) }
            thread(name = "tg-bridge-down", isDaemon = true) { writeLoop(stream) }
        }
    }

    /** Gives a new connection a stream on the right lane; null when it has to be refused. */
    private fun admit(socket: Socket): Stream? {
        if (closed || streams.size >= MAX_STREAMS) return null
        val lane = laneFor() ?: return null
        val id = nextId
        nextId = if (nextId >= 0xFFFFFF) 1 else nextId + 1
        val s = Stream(id, socket, lane)
        streams[id] = s
        lane.ids += id
        lane.opens++
        sendLocked(lane, TgFrames.encode(TgFrames.OPEN, id))
        if (lane === cur && lane.opens >= warmAt()) warmLocked()
        return s
    }

    /** Telegram -> relay, never past the relay's credit. */
    private fun readLoop(s: Stream) {
        val buf = ByteArray(DATA_CHUNK)
        try {
            val input = s.socket.getInputStream()
            while (!s.dead) {
                val allowance = s.gate.withLock {
                    while (!s.dead && s.sendWindow <= 0) s.credit.await(1, TimeUnit.SECONDS)
                    minOf(DATA_CHUNK.toLong(), s.sendWindow).toInt()
                }
                if (s.dead) return
                val n = input.read(buf, 0, allowance)
                if (n < 0) break
                if (n == 0) continue
                s.gate.withLock { s.sendWindow -= n }
                up.addAndGet(n.toLong())
                send(s.lane, TgFrames.encode(TgFrames.DATA, s.id, buf.copyOf(n)))
            }
        } catch (_: Exception) {
            // the connection ended; closed below
        }
        end(s, tellRelay = true)
    }

    /** Relay -> Telegram; the credit goes back once the bytes are written. */
    private fun writeLoop(s: Stream) {
        try {
            val output = s.socket.getOutputStream()
            while (true) {
                val chunk = s.out.take()
                if (chunk === POISON) return
                output.write(chunk)
                down.addAndGet(chunk.size.toLong())
                send(s.lane, TgFrames.encode(TgFrames.WINDOW, s.id, u32(chunk.size)))
            }
        } catch (_: Exception) {
            end(s, tellRelay = true)
        }
    }

    private fun end(s: Stream, tellRelay: Boolean) {
        synchronized(lock) {
            if (streams[s.id] === s) {
                streams.remove(s.id)
                s.lane.ids.remove(s.id)
                if (tellRelay) sendLocked(s.lane, TgFrames.encode(TgFrames.CLOSE, s.id))
                retireLocked(s.lane)
            }
        }
        s.kill()
    }

    // ------------------------------------------------------------------ the relay's side

    private fun fromRelay(lane: Lane, bytes: ByteArray) {
        if (closed || lane.dead) return
        val frames = TgFrames.parse(bytes) ?: return lost(lane)
        lane.heard = clock()
        for (f in frames) {
            if (f.stream == 0) {
                if (f.type == PING && f.payload.size <= 64) {
                    lane.pinged = true
                    val text = String(f.payload, Charsets.ISO_8859_1)
                    if (TOKEN.matches(text)) factory.token(text)
                    send(lane, TgFrames.encode(PONG, 0, f.payload))
                }
                continue
            }
            val s = synchronized(lock) { streams[f.stream]?.takeIf { it.lane === lane } } ?: continue
            when (f.type) {
                TgFrames.DATA -> if (f.payload.isNotEmpty()) s.out.offer(f.payload)
                TgFrames.WINDOW -> if (f.payload.size == 4) {
                    val n = ((f.payload[0].toLong() and 0xFF) shl 24) or ((f.payload[1].toLong() and 0xFF) shl 16) or
                        ((f.payload[2].toLong() and 0xFF) shl 8) or (f.payload[3].toLong() and 0xFF)
                    s.gate.withLock {
                        s.sendWindow = minOf(MAX_WINDOW, s.sendWindow + n)
                        s.credit.signalAll()
                    }
                }
                TgFrames.CLOSE -> end(s, tellRelay = false)
            }
        }
    }

    private fun send(lane: Lane, frame: ByteArray) {
        val ok = synchronized(lock) { sendLocked(lane, frame) }
        if (!ok) lost(lane)
    }

    /** False when the lane refused the message. A lane still being made keeps it for later. */
    private fun sendLocked(lane: Lane, frame: ByteArray): Boolean {
        if (lane.dead) return true
        val link = lane.link
        if (link == null) {
            lane.pending += frame
            return true
        }
        return link.send(frame)
    }

    // ------------------------------------------------------------------ lanes

    private fun warmAt() = maxOf(1, factory.budget - 12)
    private fun switchAt() = maxOf(1, factory.budget - 2)

    private fun laneFor(): Lane? {
        cur?.let { if (!it.dead && it.opens < switchAt()) return it }
        warmLocked()
        return next
    }

    /** Starts making the next lane, unless one is already on its way. */
    private fun warmLocked() {
        if (next != null || closed) return
        val lane = Lane()
        next = lane
        lanes += lane
        thread(name = "tg-bridge-lane", isDaemon = true) { openLane(lane) }
    }

    private fun openLane(lane: Lane) {
        var link: TgLane? = null
        for (attempt in 0 until LANE_TRIES) {
            if (closed || lane.dead) return
            link = runCatching { factory.open(lane.events) }.getOrNull()
            if (link != null) break
            laneFailures++
            if (attempt < LANE_TRIES - 1) Thread.sleep(lanePauseMs * (attempt + 1))
        }
        // Not made at all: Telegram's next connection asks again, nothing retries on its own.
        if (link == null) return lost(lane, rewarm = false)
        var refused = false
        val old: Lane?
        synchronized(lock) {
            if (closed || lane.dead) {
                old = null
                refused = true
            } else {
                lane.link = link
                lane.heard = clock()
                for (f in lane.pending) if (!link.send(f)) { refused = true; break }
                lane.pending.clear()
                if (next === lane) next = null
                old = cur
                cur = lane
            }
        }
        if (refused) {
            if (closed || lane.dead) runCatching { link.close() } else lost(lane)
            return
        }
        old?.let { synchronized(lock) { retireLocked(it) } }
    }

    /** A lane that is neither current nor coming goes once its last stream has. */
    private fun retireLocked(lane: Lane) {
        if (lane === cur || lane === next || lane.dead || lane.ids.isNotEmpty() || lane.link == null) return
        lane.dead = true
        lanes -= lane
        runCatching { lane.link?.close() }
    }

    /** A lane that failed by itself: its connections close (Telegram reconnects them) and a new lane comes. */
    private fun lost(lane: Lane, rewarm: Boolean = true) {
        val victims: List<Stream>
        synchronized(lock) {
            if (lane.dead) return
            lane.dead = true
            lanes -= lane
            if (cur === lane) cur = null
            if (next === lane) next = null
            victims = lane.ids.mapNotNull { streams.remove(it) }
            lane.ids.clear()
            lane.pending.clear()
        }
        runCatching { lane.link?.close() }
        victims.forEach { it.kill() }
        if (rewarm) synchronized(lock) { if (!closed && cur == null && streams.isNotEmpty()) warmLocked() }
    }

    private fun watch() {
        while (!closed) {
            try { Thread.sleep(watchMs) } catch (_: InterruptedException) { return }
            val now = clock()
            val quiet = synchronized(lock) { lanes.filter { it.link != null && it.pinged && now - it.heard > quietMs } }
            quiet.forEach { lost(it) }
        }
    }

    companion object {
        const val PING = 5
        const val PONG = 6
        const val MAX_STREAMS = 64
        const val DATA_CHUNK = 64 * 1024
        const val INITIAL_WINDOW = 4L * 1024 * 1024
        private const val MAX_WINDOW = 0x7fffffffL
        const val QUIET_MS = 65_000L
        const val WATCH_MS = 5_000L
        private const val LANE_TRIES = 3
        const val LANE_PAUSE_MS = 1_000L
        private val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
        private val POISON = ByteArray(0)

        fun u32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    }
}
