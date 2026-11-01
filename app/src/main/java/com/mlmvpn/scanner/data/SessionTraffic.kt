package com.mlmvpn.scanner.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The running session's byte counters, in memory, for the live meter in the home header.
 *
 * Both tunnel services used to hand these to the screen through the `vpn_session_traffic`
 * preference file: written on every sample (each second on one service, every two seconds on the
 * other, all day, screen on or off) and read back by the screen once a second. Each of those
 * writes was a whole XML file going to flash. The services and the screen share a process, so the
 * numbers now travel through this flow instead, and the file is written rarely -- see the services.
 */
object SessionTraffic {

    data class Totals(val rx: Long = 0L, val tx: Long = 0L)

    /** The latest count and when the service took it ([android.os.SystemClock.uptimeMillis]). */
    data class Count(val rx: Long, val tx: Long, val atMs: Long)

    private val _totals = MutableStateFlow(Totals())
    val totals: StateFlow<Totals> = _totals

    /**
     * The latest count, renewed on every sample a service takes, moved or not -- unlike [totals],
     * which only changes when the numbers do. A rate needs the time between two counts as well as
     * the bytes; the live chart reads this rather than timing [totals] on its own clock.
     */
    @Volatile var count: Count? = null
        private set

    /**
     * True while something draws a per-second chart of these totals (the live widget's sampler).
     * The Xray family's service counts every two seconds to spare the battery, which a one-second
     * chart shows as a staircase; while this is on it counts every second.
     */
    val live = MutableStateFlow(false)

    /** A service's sample. Call it on every sample, whether or not anything moved. */
    fun publish(rx: Long, tx: Long) {
        val next = Totals(rx.coerceAtLeast(0L), tx.coerceAtLeast(0L))
        count = Count(next.rx, next.tx, android.os.SystemClock.uptimeMillis())
        if (next != _totals.value) _totals.value = next
    }

    fun reset() {
        count = null
        _totals.value = Totals()
    }
}
