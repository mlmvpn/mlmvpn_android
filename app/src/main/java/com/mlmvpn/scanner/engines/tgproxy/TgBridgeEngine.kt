package com.mlmvpn.scanner.engines.tgproxy

import android.content.Context
import android.content.Intent
import android.util.Log
import com.mlmvpn.scanner.engines.cloud.WorkerRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The bridge's one place in the app: [TgBridgeService] runs [run] while the bridge is on, the screen
 * reads [state]. One bridge at a time, for one proxy.
 */
object TgBridgeEngine {

    private const val TAG = "TgBridge"
    private const val PREFS = "tgbridge"

    enum class Phase { OFF, PICKING, ON, FAILED }

    data class State(
        val serviceId: String? = null,
        val phase: Phase = Phase.OFF,
        val ip: String? = null,
        val ms: Long = 0,
        val port: Int = 0,
        val streams: Int = 0,
        val tried: Int = 0,
        val fromScanner: Int = 0,
        /** failed: no_proxy, no_ip, port, internal. */
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** The link that adds the bridge to Telegram, once it is on. */
    fun telegramLink(port: Int, secret: String) = "tg://proxy?server=127.0.0.1&port=$port&secret=$secret"

    fun start(context: Context, serviceId: String) {
        val app = context.applicationContext
        _state.value = State(serviceId = serviceId, phase = Phase.PICKING)
        val intent = Intent(app, TgBridgeService::class.java).setAction(TgBridgeService.ACTION_START).putExtra(TgBridgeService.EXTRA_ID, serviceId)
        val started = runCatching { androidx.core.content.ContextCompat.startForegroundService(app, intent) }.isSuccess
        if (!started) _state.value = State(serviceId = serviceId, phase = Phase.FAILED, error = "internal")
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        runCatching { app.startService(Intent(app, TgBridgeService::class.java).setAction(TgBridgeService.ACTION_STOP)) }
            .onFailure { com.mlmvpn.scanner.utils.SafeStart.deliver(Intent(app, TgBridgeService::class.java).setAction(TgBridgeService.ACTION_STOP), it) }
    }

    internal fun ended() {
        val s = _state.value
        _state.value = if (s.phase == Phase.FAILED) s else State()
    }

    /** The client the bridge and the picker use: the app's split ClientHello toward Cloudflare, HTTP/1.1. */
    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .socketFactory(WorkerRoute.cfSocketFactory())
        .protocols(WorkerRoute.HTTP1)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** The bridge's port for this proxy: the same every time, so the proxy Telegram saved keeps working. */
    private fun portFor(context: Context, serviceId: String, fresh: Boolean): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getInt("port|$serviceId", 0)
        if (saved != 0 && !fresh) return saved
        val port = 20_000 + java.security.SecureRandom().nextInt(20_000)
        prefs.edit().putInt("port|$serviceId", port).apply()
        return port
    }

    /** Runs until cancelled: picks the address, starts the bridge, keeps an eye on it. */
    internal suspend fun run(context: Context, serviceId: String) {
        val app = context.applicationContext
        val manager = TgProxyEngine.get(app)
        val service = manager.services.value.firstOrNull { it.id == serviceId && it.state == TgState.READY }
        val secret = service?.let { manager.secretOf(it.id) }
        if (service == null || secret == null) {
            _state.value = State(serviceId = serviceId, phase = Phase.FAILED, error = "no_proxy")
            return
        }
        val host = service.shownHost
        val base = client()
        var bridge: TgBridge? = null
        try {
            _state.value = State(serviceId = serviceId, phase = Phase.PICKING)
            var picked = TgCleanIp.pick(app, base, host, secret) { n -> _state.value = _state.value.copy(tried = n) }
            if (picked.picks.isEmpty()) {
                _state.value = State(serviceId = serviceId, phase = Phase.FAILED, tried = picked.tried, fromScanner = picked.fromScanner, error = "no_ip")
                return
            }
            val lanes = TgWsLanes(base, host, secret, picked.picks.map { it.ip })
            var port = 0
            for (attempt in 0 until 3) {
                val b = TgBridge(lanes, portFor(app, serviceId, fresh = attempt > 0))
                port = runCatching { b.start() }.getOrNull() ?: continue
                bridge = b
                break
            }
            val running = bridge ?: run {
                _state.value = State(serviceId = serviceId, phase = Phase.FAILED, error = "port")
                return
            }
            Log.i(TAG, "bridge on 127.0.0.1:$port via ${picked.picks.first().ip} (${picked.picks.first().ms} ms)")
            var failuresSeen = 0
            while (true) {
                val st = running.stats()
                val ip = lanes.ipInUse ?: picked.picks.first().ip
                _state.value = State(
                    serviceId = serviceId, phase = Phase.ON, ip = ip, ms = picked.picks.firstOrNull { it.ip == ip }?.ms ?: 0,
                    port = port, streams = st.streams, tried = picked.tried, fromScanner = picked.fromScanner,
                )
                // Every address failed several lanes in a row (a new network, or the edges went bad):
                // look again, without stopping the bridge or changing its port.
                if (st.laneFailures - failuresSeen >= 2 * maxOf(1, lanes.ips.size)) {
                    failuresSeen = st.laneFailures
                    val again = TgCleanIp.pick(app, base, host, secret)
                    if (again.picks.isNotEmpty()) {
                        picked = again
                        lanes.ips = again.picks.map { it.ip }
                        Log.i(TAG, "re-picked: ${again.picks.first().ip}")
                    }
                }
                delay(2_000)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "bridge failed: ${e.javaClass.simpleName}")
            _state.value = State(serviceId = serviceId, phase = Phase.FAILED, error = "internal")
        } finally {
            bridge?.close()
        }
    }
}
