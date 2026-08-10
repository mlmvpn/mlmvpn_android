package kittoku.mvc.debug

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Counters for the SoftEther data plane, dumped to logcat on an interval.
 *
 * WHY THIS EXISTS.
 * The UDP-acceleration failure this was written for was invisible from outside: the channel
 * negotiated, the status read OPEN, keep-alives kept flowing, and the UI reported UDP as
 * active — while not one byte of user traffic moved. Every signal the app already had said
 * "working". Distinguishing "the channel is up" from "the channel is carrying traffic"
 * needs separate counters for each, which is what this provides.
 *
 * Read it with:
 *   adb logcat -s MlmVpnPerf:D
 *
 * The line to watch is `udp.tx` versus `udp.rx`. A session where `tx` climbs and `rx` stays
 * flat means the server is not receiving (NAT, blocked port, wrong source address); one where
 * both stall while `pool.free` sits at 0 means the sender starved on buffers, not the network.
 */
internal object Telemetry {
    const val TAG = "MlmVpnPerf"

    // Data plane, split by channel so the TCP fallback and the UDP fast lane can be told apart.
    val udpTxBytes = AtomicLong()
    val udpTxPackets = AtomicLong()
    val udpRxBytes = AtomicLong()
    val udpRxPackets = AtomicLong()
    val tcpTxBytes = AtomicLong()
    val tcpTxFrames = AtomicLong()
    val tcpRxBytes = AtomicLong()
    val tcpRxFrames = AtomicLong()

    // Datagrams that arrived but were thrown away, by reason. A high count here with a healthy
    // tx is the signature of a mismatched key/cookie or an aggressive NAT rewriting the source.
    val udpDropShort = AtomicLong()
    val udpDropDecrypt = AtomicLong()
    val udpDropCookie = AtomicLong()
    val udpDropStale = AtomicLong()

    // Keep-alives prove the control path is alive even when no user traffic is moving, which
    // is exactly the state that made the original bug look like a working connection. The
    // received side is NOT a drop — a payload-less datagram is the server answering correctly
    // — so it is counted here rather than in drops[], where it read as damage during testing.
    val udpKeepAlivesSent = AtomicLong()
    val udpKeepAlivesRecv = AtomicLong()

    /** Free buffers in OutgoingManager's pool. 0 for any length of time means the tun reader is stalled. */
    @Volatile var poolFree: Int = -1

    /** Last observed UDP status, so transitions can be logged rather than polled. */
    @Volatile var udpStatus: String = "?"

    /** Consecutive 2-second samples of "sending, nothing received" before that counts as broken. */
    private const val ONE_WAY_SAMPLES_BEFORE_WARNING = 6

    private var oneWaySamples = 0
    private var lastAt = 0L
    private var lastUdpTx = 0L
    private var lastUdpRx = 0L
    private var lastTcpTx = 0L
    private var lastTcpRx = 0L

    fun reset() {
        listOf(
            udpTxBytes, udpTxPackets, udpRxBytes, udpRxPackets,
            tcpTxBytes, tcpTxFrames, tcpRxBytes, tcpRxFrames,
            udpDropShort, udpDropDecrypt, udpDropCookie, udpDropStale,
            udpKeepAlivesSent, udpKeepAlivesRecv
        ).forEach { it.set(0) }
        poolFree = -1
        udpStatus = "?"
        lastAt = 0
        lastUdpTx = 0; lastUdpRx = 0; lastTcpTx = 0; lastTcpRx = 0
    }

    /** Log one line of cumulative totals plus the rate since the previous call. */
    fun report() {
        val now = System.currentTimeMillis()
        val elapsed = if (lastAt == 0L) 0L else now - lastAt
        lastAt = now

        val utx = udpTxBytes.get()
        val urx = udpRxBytes.get()
        val ttx = tcpTxBytes.get()
        val trx = tcpRxBytes.get()

        // Rates are what tell a stalled tunnel from a quiet one; totals alone cannot.
        // Locale.ROOT is not optional here. String.format() without it uses the device locale,
        // and on a Persian-locale phone that renders every number in Persian-Indic digits
        // ("۹۳۰" instead of "930") — unreadable at a glance and impossible to parse with any
        // tool. Diagnostic output must always be locale-independent.
        val rate = { delta: Long ->
            if (elapsed <= 0) "-"
            else String.format(java.util.Locale.ROOT, "%.0f", delta * 1000.0 / elapsed / 1024.0)
        }

        Log.d(
            TAG,
            "udp[$udpStatus] tx=${utx / 1024}KB/${udpTxPackets.get()}p rx=${urx / 1024}KB/${udpRxPackets.get()}p " +
                "| ${rate(utx - lastUdpTx)}/${rate(urx - lastUdpRx)} KB/s up/down " +
                "|| tcp tx=${ttx / 1024}KB/${tcpTxFrames.get()}f rx=${trx / 1024}KB/${tcpRxFrames.get()}f " +
                "| ${rate(ttx - lastTcpTx)}/${rate(trx - lastTcpRx)} KB/s " +
                "|| pool.free=$poolFree ka=${udpKeepAlivesSent.get()}s/${udpKeepAlivesRecv.get()}r " +
                "drops[short=${udpDropShort.get()} crypt=${udpDropDecrypt.get()} " +
                "cookie=${udpDropCookie.get()} stale=${udpDropStale.get()}]"
        )

        lastUdpTx = utx; lastUdpRx = urx; lastTcpTx = ttx; lastTcpRx = trx

        // Call out the two failure shapes explicitly, so a tester does not have to interpret
        // the numbers to know something is wrong.
        if (poolFree == 0) {
            Log.w(TAG, "STALL: outgoing buffer pool is empty — the tun reader cannot proceed")
        }
        // One-way has to PERSIST before it means anything. SoftEther switches the upstream to
        // UDP as soon as the channel opens, but the server keeps sending downstream over TCP
        // until it has seen our datagrams — so roughly the first ten seconds of every healthy
        // UDP session look exactly like a one-way failure. Warning on the first sample cried
        // wolf on a working connection; requiring several consecutive samples does not.
        if (udpStatus == "OPEN" && utx > 0 && urx == 0L) {
            oneWaySamples++
            if (oneWaySamples >= ONE_WAY_SAMPLES_BEFORE_WARNING) {
                Log.w(
                    TAG,
                    "ONE-WAY: UDP has been sending for ${oneWaySamples * 2}s with nothing back — " +
                        "the server is not receiving our datagrams"
                )
            }
        } else {
            oneWaySamples = 0
        }
    }

    fun logStatusChange(from: String, to: String, note: String = "") {
        Log.i(TAG, "udp status $from -> $to $note")
    }
}
