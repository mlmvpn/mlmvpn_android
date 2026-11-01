package com.mlmvpn.scanner.utils

import android.os.Build
import android.os.SystemClock
import java.util.concurrent.TimeUnit

/*
 * java.lang.Process on Android 7 (minSdk 24): isAlive(), destroyForcibly() and waitFor(timeout)
 * only exist from Android 8 (API 26). Calling them there is a NoSuchMethodError that takes the
 * app down (crash reports on 7.1.1: Tor, WARP and the SNI engine). These do the same on every
 * version: the real method from 8 on, the equivalent built from exitValue()/destroy() below it.
 */

/** Whether the process is still running. */
fun Process.alive(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) isAlive
    else try { exitValue(); false } catch (_: IllegalThreadStateException) { true }

/** Kills the process (SIGKILL from Android 8, SIGTERM-then-whatever below it). */
fun Process.killHard() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) destroyForcibly() else destroy()
}

/** Waits at most [timeout] for the process to end; true if it did. */
fun Process.waitUpTo(timeout: Long, unit: TimeUnit): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return waitFor(timeout, unit)
    val deadline = SystemClock.elapsedRealtime() + unit.toMillis(timeout)
    while (true) {
        if (!alive()) return true
        val left = deadline - SystemClock.elapsedRealtime()
        if (left <= 0) return false
        Thread.sleep(minOf(left, 50L))
    }
}
