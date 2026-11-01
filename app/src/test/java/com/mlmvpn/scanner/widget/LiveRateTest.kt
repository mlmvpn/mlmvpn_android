package com.mlmvpn.scanner.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live chart reads an engine's running count once a second, and the engines count on clocks
 * of their own. Timed on the tick, a two-second engine plotted double, nothing, double, nothing --
 * the pulse the chart used to have. These replay an engine and the tick against each other.
 */
class LiveRateTest {

    /**
     * An engine moving [bytesPerSec] steadily that counts every [periodMs] (first at [phaseMs]),
     * read on a one-second tick for [seconds]. Returns the rate plotted at each tick.
     */
    private fun replay(bytesPerSec: Long, periodMs: Long, phaseMs: Long, seconds: Int): List<Long> {
        val meter = LiveRate()
        val out = mutableListOf<Long>()
        for (tick in 0..seconds) {
            val now = tick * 1_000L
            if (now < phaseMs) continue
            // The engine's latest count at this tick, and when it took it.
            val countedAt = phaseMs + (now - phaseMs) / periodMs * periodMs
            val rate = meter.next(bytesPerSec * countedAt / 1_000L, countedAt)
            if (tick > 2) out += rate
        }
        return out
    }

    @Test fun twoSecondEngineNeverReadsAsDoubleOrNothing() {
        val rates = replay(bytesPerSec = 1_000_000, periodMs = 2_000, phaseMs = 300, seconds = 120)
        assertTrue(rates.size > 100)
        for (r in rates) assertEquals(1_000_000L, r)
    }

    @Test fun engineOnAClockOfItsOwnStaysSteady() {
        // Faster, slower and level with the tick: some ticks find two counts, some none.
        for (period in longArrayOf(900, 990, 1_000, 1_003, 1_010, 1_050, 1_500)) {
            val rates = replay(bytesPerSec = 2_000_000, periodMs = period, phaseMs = 450, seconds = 600)
            for (r in rates) assertEquals("period $period", 2_000_000L, r)
        }
    }

    @Test fun stillCountsReadZeroAndABurstIsItsOwnPeriod() {
        // A service that counts every second whether or not anything moved.
        val meter = LiveRate()
        assertEquals(0L, meter.next(1_000, 0))
        for (t in 1..10) assertEquals(0L, meter.next(1_000, t * 1_000L))
        assertEquals(500_000L, meter.next(501_000, 11_000))
    }

    @Test fun lateCountHoldsOnceThenStoppedEngineReadsZero() {
        val meter = LiveRate()
        meter.next(0, 0)
        assertEquals(100_000L, meter.next(100_000, 1_000))
        // No new count at the next two ticks.
        assertEquals(100_000L, meter.next(100_000, 1_000))
        assertEquals(0L, meter.next(100_000, 1_000))
        // It comes back: the rate is over its own two seconds.
        assertEquals(50_000L, meter.next(200_000, 3_000))
    }

    @Test fun restartedCountIsNotANegativeRate() {
        val meter = LiveRate()
        meter.next(0, 0)
        meter.next(5_000_000, 1_000)
        assertEquals(0L, meter.next(10_000, 2_000))
        assertEquals(20_000L, meter.next(30_000, 3_000))
    }
}
