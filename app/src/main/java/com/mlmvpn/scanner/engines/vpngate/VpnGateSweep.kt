package com.mlmvpn.scanner.engines.vpngate

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/**
 * Progress of whichever bulk test — ping or real handshake — is currently running.
 *
 * Shared rather than held per-screen because the same two tests are launched from two
 * different places (the main list's picker dialog and the "more servers" browser) and both
 * have to show the same strip. Hoisting it here also means a sweep started in one and still
 * running when the user moves to the other keeps reporting, instead of the progress silently
 * belonging to a composable that is gone.
 *
 * Only one sweep at a time: they hit the same servers, and running both at once just makes
 * each slower and the strip ambiguous about which number it is showing.
 */
object VpnGateSweep {

    enum class Kind(val labelFa: String) { PING("پینگ"), PROBE("تست واقعی") }

    data class State(val kind: Kind, val done: Int, val total: Int) {
        val percent: Int get() = if (total > 0) done * 100 / total else 0
        val fraction: Float get() = if (total > 0) done.toFloat() / total else 0f
    }

    private val _state = MutableStateFlow<State?>(null)

    /** Null when nothing is running — which is also the strip's visibility condition. */
    val stateFlow: StateFlow<State?> = _state.asStateFlow()

    private var job: Job? = null

    /** Counted separately from the flow: [tick] runs on many IO threads at once. */
    private val counter = AtomicInteger(0)

    fun isRunning(): Boolean = _state.value != null

    /**
     * @param job the coroutine running the sweep, so [cancel] has something to stop. The
     *   caller must still clear the sweep in its own `finally` via [end] — cancelling a job
     *   does not report back here.
     */
    fun begin(kind: Kind, total: Int, job: Job) {
        this.job = job
        counter.set(0)
        _state.value = State(kind, 0, total)
    }

    /**
     * One server finished. Safe from any thread — [update] rather than a plain assignment,
     * so a burst of concurrent ticks can't overwrite each other and leave the strip showing
     * a stale count (the same read-modify-write race that was dropping results in
     * VpnGateStore.putHandshake).
     */
    fun tick() {
        val done = counter.incrementAndGet()
        _state.update { it?.copy(done = done) }
    }

    fun end() {
        job = null
        _state.value = null
    }

    /** Stops the running sweep. The launcher's `finally` calls [end]. */
    fun cancel() {
        job?.cancel()
    }
}
