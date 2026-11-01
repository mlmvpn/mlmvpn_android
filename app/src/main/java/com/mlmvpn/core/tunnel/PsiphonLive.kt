package com.mlmvpn.core.tunnel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * What the «سایفون» tile is doing right now, for its screen: which method of how many and its
 * budget, then what carried it -- the protocol, the proxy or the clean address, the server's
 * measured capacity -- and how stable it has been (drops, the last gap, destinations the server
 * refused). The desktop's psiphon-manager.js `getStatus`, fed by [TunnelVpnService] in this process.
 */
object PsiphonLive {

    data class Live(
        /** The rung being tried or carrying it: A, E, D, C, P or X. */
        val rung: String = "",
        val label: String = "",
        /** 1-based. */
        val index: Int = 0,
        val count: Int = 0,
        val seconds: Int = 0,
        val startedAt: Long = 0L,
        val connected: Boolean = false,
        /** What actually carried the tunnel (ActiveTunnel). */
        val protocol: String = "",
        /** The capacity check after a connect is running. */
        val measuring: Boolean = false,
        /** Aggregate Mbit/s of six streams at once, 0 when not measured. */
        val mbit: Double = 0.0,
        /** Which server of the slow-server search this is (1 = the first one that answered). */
        val attempt: Int = 1,
        val drops: Int = 0,
        val droppedAt: Long = 0L,
        val lastGapSeconds: Int = 0,
        val refused: Int = 0,
        val serverRegion: String = "",
        val candidate: Int = -1,
        /** «آی‌پی تمیز»: the address that carried it. */
        val cleanIp: String = "",
        /** «پروکسی سرور»: the proxy it came in through, and that proxy's country. */
        val upstream: String = "",
        val upstreamCountry: String = "",
        /** The last thing worth a line: a server alert, a slow server being swapped. */
        val note: String = "",
    )

    private val _state = MutableStateFlow<Live?>(null)
    val state: StateFlow<Live?> get() = _state

    /** A connect begins: nothing from the last one carries over. */
    fun begin() { _state.value = Live() }

    fun rung(name: String, label: String, index: Int, count: Int, seconds: Int) = _state.update {
        (it ?: Live()).copy(rung = name, label = label, index = index + 1, count = count, seconds = seconds,
            startedAt = System.currentTimeMillis(), connected = false)
    }

    fun update(change: (Live) -> Live) = _state.update { change(it ?: Live()) }

    /** The tile is down: the screen shows nothing of a session that no longer exists. */
    fun end() { _state.value = null }
}
