package com.mlmvpn.scanner.widget

/**
 * A session's running byte total, read once a tick, turned into the rate to plot for that tick.
 *
 * The engines count on clocks of their own -- every second on one service, every two seconds on
 * another -- and "this reading minus the last, per tick" aliases against them: a two-second engine
 * came out as double, nothing, double, nothing, and the live chart beat like a pulse. So the rate
 * is taken over the engine's own time between the two counts it compares ([next]'s `countedAtMs`),
 * never over our tick; and a tick that finds no new count holds the last rate once (the engine is
 * a little late, its next count will cover the gap) and reads zero after that (the engine stopped
 * counting).
 *
 * Pure and clock-free (the caller passes the times), so the rules are tested on their own.
 */
internal class LiveRate {
    private var last = -1L
    private var lastAt = 0L
    /** Ticks in a row that found no new count. */
    private var stale = 0
    private var held = 0L

    /**
     * This tick's reading: the engine's running [total] and when the engine counted it
     * ([countedAtMs], one monotonic clock across calls). Returns the bytes per second to plot.
     */
    fun next(total: Long, countedAtMs: Long): Long {
        if (last < 0L || total < last || countedAtMs < lastAt) {
            // The first reading, or the engine started its count again: nothing to compare with yet.
            last = total
            lastAt = countedAtMs
            stale = 0
            held = 0L
            return 0L
        }
        if (countedAtMs == lastAt) {
            stale++
            return if (stale == 1) held else 0L
        }
        val rate = (total - last) * 1000L / (countedAtMs - lastAt)
        last = total
        lastAt = countedAtMs
        stale = 0
        held = rate
        return rate
    }
}
