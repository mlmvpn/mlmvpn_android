package com.mlmvpn.scanner.engines.gst

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Central in-memory log sink for the GST (Google Apps Script Tunnel) subsystem.
 *
 * Everything the engine, diagnostics and (later) the auto-deployer emit is funneled
 * through here so a single live panel in the UI can show it � previously the only
 * signal was [com.therealaleph.mhrv.Native.drainLogs] going straight to logcat, which
 * made it impossible to see from inside the app why a relay failed.
 *
 * Backed by a bounded ring buffer so it never grows without limit, and exposed as a
 * [StateFlow] so Compose can collect it directly.
 */
object GstLog {
    private const val MAX_LINES = 500
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    enum class Level { D, I, W, E }

    data class Entry(
        val time: Long,
        val level: Level,
        val tag: String,
        val message: String
    ) {
        fun format(): String = "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(time))} " +
            "${level.name}/$tag: $message"
    }

    private val buffer = ArrayDeque<Entry>(MAX_LINES)

    private val _lines = MutableStateFlow<List<Entry>>(emptyList())
    val lines: StateFlow<List<Entry>> = _lines

    @Synchronized
    fun log(level: Level, tag: String, message: String) {
        // Mirror to logcat so `adb logcat` keeps working exactly as before.
        when (level) {
            Level.D -> Log.d(tag, message)
            Level.I -> Log.i(tag, message)
            Level.W -> Log.w(tag, message)
            Level.E -> Log.e(tag, message)
        }
        buffer.addLast(Entry(System.currentTimeMillis(), level, tag, message))
        while (buffer.size > MAX_LINES) buffer.removeFirst()
        _lines.value = buffer.toList()
    }

    fun d(tag: String, message: String) = log(Level.D, tag, message)
    fun i(tag: String, message: String) = log(Level.I, tag, message)
    fun w(tag: String, message: String) = log(Level.W, tag, message)
    fun e(tag: String, message: String) = log(Level.E, tag, message)

    @Synchronized
    fun clear() {
        buffer.clear()
        _lines.value = emptyList()
    }

    /** Full log as a single string, for share/copy. */
    @Synchronized
    fun dump(): String = buffer.joinToString("\n") {
        "${timeFmt.format(Date(it.time))} ${it.level.name}/${it.tag}: ${it.message}"
    }

    /**
     * Redact a secret (auth key) for safe logging: keep first/last 2 chars.
     * "abcdef1234" -> "ab****34"
     */
    fun redact(secret: String?): String {
        if (secret.isNullOrEmpty()) return "<empty>"
        if (secret.length <= 4) return "****"
        return secret.take(2) + "****" + secret.takeLast(2)
    }
}
