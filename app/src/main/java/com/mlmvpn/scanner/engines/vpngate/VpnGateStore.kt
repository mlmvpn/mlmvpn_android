package com.mlmvpn.scanner.engines.vpngate

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Remembers which VPN Gate server the user picked, the latency we last measured for it, and
 * when the current session started — so the screen can come back up showing the same server
 * and a live "connected for HH:MM:SS" instead of resetting to an empty state.
 */
object VpnGateStore {

    private const val PREFS = "vpngate_prefs"
    private const val KEY_HOST = "selected_host"
    private const val KEY_SINCE = "connected_since"
    private const val KEY_UDP = "udp_acceleration"

    private val _selectedHost = MutableStateFlow<String?>(null)
    val selectedHostFlow: StateFlow<String?> = _selectedHost.asStateFlow()

    /** hostName -> last measured RTT in ms (or [VpnGatePinger.FAILED]). In-memory only. */
    private val _pings = MutableStateFlow<Map<String, Int>>(emptyMap())
    val pingsFlow: StateFlow<Map<String, Int>> = _pings.asStateFlow()

    /**
     * hostName -> result of the real SoftEther handshake test. Kept separate from [pingsFlow]
     * on purpose: a TCP ping says the port answers, this says the server will actually start a
     * session with us, and on some carriers those two disagree for most of the list.
     */
    private val _handshakes = MutableStateFlow<Map<String, SoftEtherProbe.Result>>(emptyMap())
    val handshakesFlow: StateFlow<Map<String, SoftEtherProbe.Result>> = _handshakes.asStateFlow()

    /**
     * `update` and not `_handshakes.value = _handshakes.value + ...`.
     *
     * That plain form is a read-modify-write, and the bulk tests call this from 16 IO threads
     * at once: two workers read the same map, each adds its own entry, and the second write
     * throws the first one away. The sweep genuinely probed every server — the counter is
     * atomic and reached the full total — but a handful of results silently never made it
     * into the map, so those rows stayed blank as if they had been skipped. Losing 4 of 40 is
     * exactly the collision rate 16 concurrent writers produce. [update] retries on conflict,
     * so no result is dropped.
     */
    fun putHandshake(host: String, result: SoftEtherProbe.Result) {
        _handshakes.update { it + (host to result) }
    }

    /** Drops results for servers that no longer exist, so the map can't outlive the archive. */
    fun forgetHandshakes(hosts: Collection<String>) {
        if (hosts.isEmpty()) return
        val gone = hosts.toSet()
        _handshakes.update { it - gone }
        _pings.update { it - gone }
    }

    private val _connectedSince = MutableStateFlow(0L)
    val connectedSinceFlow: StateFlow<Long> = _connectedSince.asStateFlow()

    /**
     * SoftEther's UDP acceleration. Off by default: it moves the data plane onto a second,
     * non-TLS channel, and on a filtered network that channel is both conspicuous and — as
     * measured here — silently dropped, leaving a tunnel that reports OPEN and carries
     * nothing. Where UDP does get through it avoids TCP-in-TCP and is considerably faster,
     * so it is worth offering rather than hard-coding.
     */
    private val _udpAcceleration = MutableStateFlow(false)
    val udpAccelerationFlow: StateFlow<Boolean> = _udpAcceleration.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        val p = prefs(context)
        _selectedHost.value = p.getString(KEY_HOST, null)
        _connectedSince.value = p.getLong(KEY_SINCE, 0L)
        _udpAcceleration.value = p.getBoolean(KEY_UDP, false)
    }

    fun setUdpAcceleration(context: Context, enabled: Boolean) {
        _udpAcceleration.value = enabled
        prefs(context).edit().putBoolean(KEY_UDP, enabled).apply()
    }

    /** Read straight from disk: the engine may start in a process where load() hasn't run. */
    fun isUdpAccelerationEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UDP, false)

    fun select(context: Context, host: String) {
        _selectedHost.value = host
        prefs(context).edit().putString(KEY_HOST, host).apply()
    }

    fun markConnected(context: Context) {
        val now = System.currentTimeMillis()
        _connectedSince.value = now
        prefs(context).edit().putLong(KEY_SINCE, now).apply()
    }

    fun markDisconnected(context: Context) {
        _connectedSince.value = 0L
        prefs(context).edit().putLong(KEY_SINCE, 0L).apply()
    }

    /** Atomic for the same reason as [putHandshake] — pingAll writes from 12 threads. */
    fun putPing(host: String, rtt: Int) {
        _pings.update { it + (host to rtt) }
    }

    fun pingOf(host: String): Int? = _pings.value[host]
}
