package com.mlmvpn.scanner.engines.amnezia

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `filesDir/amnezia/state.json`, kept the way FLUX keeps its own: written whole to a `.tmp` and
 * renamed, off the caller's thread and at most every couple of seconds; a file that does not
 * parse is moved aside rather than crashing the section.
 */
class AmzStore(dir: File) {

    private val root = File(dir, "amnezia").apply { mkdirs() }
    private val file = File(root, "state.json")
    private val tmp = File(root, "state.json.tmp")
    private val corrupt = File(root, "state.corrupt.json")

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AmzState> = _state
    val current: AmzState get() = _state.value

    @Synchronized
    fun update(change: (AmzState) -> AmzState): AmzState {
        val cur = _state.value
        val next = change(cur)
        if (next !== cur) {
            _state.value = next
            scheduleWrite()
        }
        return next
    }

    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "amz-store").apply { isDaemon = true } }
    private val pending = AtomicBoolean(false)

    private fun scheduleWrite() {
        if (pending.compareAndSet(false, true)) {
            writer.schedule({ pending.set(false); write(_state.value) }, WRITE_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun load(): AmzState {
        if (!file.exists()) return AmzState()
        return try {
            AmzCodec.decode(file.readText())
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "state unreadable (${e.javaClass.simpleName}); starting fresh") }
            runCatching { corrupt.delete(); file.renameTo(corrupt) }
            AmzState()
        }
    }

    private val fileLock = Any()

    private fun write(s: AmzState) {
        synchronized(fileLock) {
            try {
                tmp.writeText(AmzCodec.encode(s))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
                Unit
            } catch (e: Exception) {
                runCatching { Log.w(TAG, "state not written: ${e.javaClass.simpleName}") }
            }
        }
    }

    companion object {
        private const val TAG = "AmzStore"
        private const val WRITE_DELAY_MS = 2_000L
    }
}
