package com.mlmvpn.scanner.engines.tgproxy

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The bridge against an in-memory relay that echoes every stream, with real TCP connections on
 * 127.0.0.1 playing Telegram. What matters: bytes arrive both ways, lanes are changed before their
 * budget without Telegram noticing, a dying lane takes only its own connections, pings are answered.
 */
class TgBridgeTest {

    /** A relay lane: records what it got, echoes DATA back with the credit for it, on its own thread. */
    class FakeLane(private val events: TgLaneEvents) : TgLane {
        val opens = CopyOnWriteArrayList<Int>()
        val frames = CopyOnWriteArrayList<TgFrames.Frame>()
        @Volatile var closed = false
        private val delivery = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }

        fun deliver(bytes: ByteArray) = delivery.execute { if (!closed) events.message(bytes) }

        override fun send(frame: ByteArray): Boolean {
            if (closed) return false
            for (f in TgFrames.parse(frame)!!) {
                frames += f
                when (f.type) {
                    TgFrames.OPEN -> opens += f.stream
                    TgFrames.DATA -> {
                        deliver(TgFrames.encode(TgFrames.WINDOW, f.stream, TgBridge.u32(f.payload.size)))
                        deliver(TgFrames.encode(TgFrames.DATA, f.stream, f.payload))
                    }
                }
            }
            return true
        }

        override fun close() { closed = true }

        /** The relay's end goes away. */
        fun die() = delivery.execute { closed = true; events.closed() }
    }

    class FakeRelay(override val budget: Int = 45) : TgLaneFactory {
        val lanes = CopyOnWriteArrayList<FakeLane>()
        val tokens = CopyOnWriteArrayList<String>()
        @Volatile var refuse = false
        override fun open(events: TgLaneEvents): TgLane? = if (refuse) null else FakeLane(events).also { lanes += it }
        override fun token(token: String) { tokens += token }
    }

    private val bridges = ArrayList<TgBridge>()
    private val sockets = ArrayList<Socket>()

    @After fun tearDown() {
        sockets.forEach { runCatching { it.close() } }
        bridges.forEach { it.close() }
    }

    private fun bridge(relay: FakeRelay, quietMs: Long = TgBridge.QUIET_MS, watchMs: Long = TgBridge.WATCH_MS): Int =
        TgBridge(relay, 0, quietMs = quietMs, watchMs = watchMs, lanePauseMs = 20).also { bridges += it }.start()

    private fun connect(port: Int) = Socket("127.0.0.1", port).also { it.soTimeout = 5_000; sockets += it }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var at = 0
        while (at < n) {
            val r = input.read(out, at, n - at)
            if (r < 0) throw SocketException("closed after $at bytes")
            at += r
        }
        return out
    }

    private fun roundTrip(s: Socket, text: String) {
        s.getOutputStream().apply { write(text.toByteArray()); flush() }
        assertEquals(text, String(readExactly(s.getInputStream(), text.length)))
    }

    /** True when the bridge closed [s] (EOF or reset). */
    private fun closedByBridge(s: Socket): Boolean = try {
        s.getInputStream().read() < 0
    } catch (e: SocketException) {
        true
    }

    private fun waitFor(what: String, ms: Long = 5_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!check()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(10)
        }
    }

    @Test fun bytesGoBothWaysAndTheCreditComesBack() {
        val relay = FakeRelay()
        val port = bridge(relay)
        val s = connect(port)
        roundTrip(s, "hello relay")
        val big = ByteArray(300_000) { (it % 251).toByte() }
        s.getOutputStream().apply { write(big); flush() }
        assertArrayEquals(big, readExactly(s.getInputStream(), big.size))
        val lane = relay.lanes.single()
        assertEquals(1, lane.opens.size)
        assertTrue("DATA chunks stay within 64 KiB", lane.frames.filter { it.type == TgFrames.DATA }.all { it.payload.size <= 64 * 1024 })
        val granted = lane.frames.filter { it.type == TgFrames.WINDOW }.sumOf { java.nio.ByteBuffer.wrap(it.payload).int.toLong() }
        assertEquals("every byte written to Telegram is credited back", (11 + big.size).toLong(), granted)
    }

    @Test fun aConnectionTelegramClosesIsClosedAtTheRelay() {
        val relay = FakeRelay()
        val port = bridge(relay)
        val s = connect(port)
        roundTrip(s, "x")
        s.close()
        waitFor("CLOSE reaches the relay") { relay.lanes.single().frames.any { it.type == TgFrames.CLOSE } }
    }

    @Test fun theRelayClosingAStreamClosesTelegramsConnection() {
        val relay = FakeRelay()
        val port = bridge(relay)
        val s = connect(port)
        roundTrip(s, "x")
        val lane = relay.lanes.single()
        lane.deliver(TgFrames.encode(TgFrames.CLOSE, lane.opens.single()))
        assertTrue(closedByBridge(s))
    }

    @Test fun lanesChangeBeforeTheirBudgetAndOldConnectionsKeepWorking() {
        val relay = FakeRelay(budget = 6) // switch at 4 opens per lane
        val port = bridge(relay)
        val conns = (1..12).map { i -> connect(port).also { roundTrip(it, "message $i") } }
        assertTrue("several lanes (${relay.lanes.size})", relay.lanes.size >= 3)
        assertTrue("no lane past its switch point", relay.lanes.all { it.opens.size <= 4 })
        assertEquals(12, relay.lanes.sumOf { it.opens.size })
        // the first connection still talks over the first lane, which stays while it is used
        roundTrip(conns[0], "still here")
        val first = relay.lanes[0]
        assertFalse(first.closed)
        // once its connections are gone, the old lane is closed; the newest is not
        val onFirst = first.opens.size
        conns.take(onFirst).forEach { it.close() }
        waitFor("the drained lane closes") { first.closed }
        assertFalse(relay.lanes.last().closed)
        roundTrip(conns.last(), "newest")
    }

    @Test fun aDyingLaneClosesOnlyItsOwnConnections() {
        val relay = FakeRelay(budget = 6)
        val port = bridge(relay)
        val old = (1..4).map { connect(port).also { c -> roundTrip(c, "old") } }
        val fresh = connect(port).also { roundTrip(it, "fresh") }
        val first = relay.lanes[0]
        val holder = relay.lanes.first { it.opens.isNotEmpty() && it !== first && !it.closed }
        assertTrue(first.opens.size in 1..4)
        first.die()
        old.filterIndexed { i, _ -> i < first.opens.size }.forEach { assertTrue("its connections close", closedByBridge(it)) }
        assertFalse(holder.closed)
        roundTrip(fresh, "unaffected")
        roundTrip(connect(port), "a new connection gets through")
    }

    @Test fun pingsAreAnsweredAndTheirTokenKept() {
        val relay = FakeRelay()
        val port = bridge(relay)
        roundTrip(connect(port), "x")
        val lane = relay.lanes.single()
        val token = "A".repeat(20) + "b_-".repeat(7) + "Zz"
        assertEquals(43, token.length)
        lane.deliver(TgFrames.encode(TgBridge.PING, 0, token.toByteArray()))
        waitFor("a PONG") { lane.frames.any { it.type == TgBridge.PONG } }
        val pong = lane.frames.first { it.type == TgBridge.PONG }
        assertEquals(0, pong.stream)
        assertEquals(token, String(pong.payload))
        assertEquals(listOf(token), relay.tokens.toList())
    }

    @Test fun aLaneThatPingedAndFellSilentIsDropped() {
        val relay = FakeRelay()
        val port = bridge(relay, quietMs = 150, watchMs = 50)
        val s = connect(port)
        roundTrip(s, "x")
        val lane = relay.lanes.single()
        lane.deliver(TgFrames.encode(TgBridge.PING, 0, "p".toByteArray()))
        waitFor("dropped", 3_000) { lane.closed }
        assertTrue(closedByBridge(s))
    }

    @Test fun aLaneThatNeverPingedIsNotJudgedBySilence() {
        val relay = FakeRelay()
        val port = bridge(relay, quietMs = 100, watchMs = 30)
        val s = connect(port)
        roundTrip(s, "x")
        Thread.sleep(400)
        assertFalse(relay.lanes.single().closed)
        roundTrip(s, "still")
    }

    @Test fun noLaneAtAllClosesTheConnection() {
        val relay = FakeRelay().apply { refuse = true }
        val port = bridge(relay)
        val s = connect(port)
        assertTrue(closedByBridge(s))
        relay.refuse = false
        roundTrip(connect(port), "works once the relay answers")
    }
}
