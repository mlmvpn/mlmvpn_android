package com.mlmvpn.core.warp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The page's measurements of the IPv4 exit, one at a time: a country's full scan, or «سنجش همهٔ
 * کشورها» (a short look at every country, to rank them fast → slow and name the dead ones). Both run
 * THROUGH WARP ([WarpVia]) -- the engine that is up, or a short-lived one of their own -- and say so.
 * A stop ends the run at once for the page; probes in flight end on their own timeouts and are ignored.
 */
object Ipv4ExitRunner {
    private const val TAG = "Ipv4ExitRunner"

    enum class Way { STARTING, ENGINE, HELPER, DIRECT }

    data class State(
        val running: Boolean = false,
        /** The country a single scan is measuring; null for «سنجش همه». */
        val country: String? = null,
        val all: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val ok: Int = 0,
        /** «سنجش همه»: the countries being looked at right now. */
        val current: Set<String> = emptySet(),
        val way: Way? = null,
        /** The country found nothing that carried (single scan), for the page's message. */
        val emptyResult: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    @Volatile private var generation = 0

    fun cancel() {
        generation++
        job?.cancel()
        job = null
        _state.value = State()
    }

    private fun wayOf(kind: WarpVia.Kind?): Way = when (kind) {
        null -> Way.STARTING
        WarpVia.Kind.ENGINE -> Way.ENGINE
        WarpVia.Kind.HELPER -> Way.HELPER
        WarpVia.Kind.DIRECT -> Way.DIRECT
    }

    /**
     * Measures [country] fully. [then] runs when it is done, with how many carried -- the page turns
     * the exit on for a country the user picked once it has proxies that work.
     */
    fun scan(ctx: Context, country: String, then: (Int) -> Unit = {}) {
        if (_state.value.running) return
        val app = ctx.applicationContext
        val gen = ++generation
        _state.value = State(running = true, country = country, way = Way.STARTING)
        job = scope.launch {
            try {
                val r = WarpVia.use(app, onVia = { k -> if (gen == generation) _state.update { it.copy(way = wayOf(k)) } }) { port, _ ->
                    Ipv4Exits.scan(app, country, port,
                        onProgress = { d, t, ok -> if (gen == generation) _state.update { it.copy(done = d, total = t, ok = ok) } },
                        isCancelled = { gen != generation })
                }
                if (gen == generation) {
                    _state.value = State(emptyResult = country.takeIf { r.ok == 0 })
                    then(r.ok)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "scan $country: ${e.message}")
                if (gen == generation) _state.value = State(emptyResult = country)
            }
        }
    }

    /** «سنجش همهٔ کشورها»: a short look at each, fastest-first afterwards. */
    fun pingAll(ctx: Context) {
        if (_state.value.running) return
        val app = ctx.applicationContext
        val gen = ++generation
        val countries = Ipv4Exits.countryList(app)
        _state.value = State(running = true, all = true, total = countries.size, way = Way.STARTING)
        job = scope.launch {
            try {
                WarpVia.use(app, onVia = { k -> if (gen == generation) _state.update { it.copy(way = wayOf(k)) } }) { port, _ ->
                    Ipv4Exits.pingAll(app, port, countries,
                        onCountry = { cc, started, done, _ ->
                            if (gen == generation) _state.update {
                                it.copy(done = done, current = if (started) it.current + cc else it.current - cc)
                            }
                        },
                        isCancelled = { gen != generation })
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "ping all: ${e.message}")
            } finally {
                if (gen == generation) _state.value = State()
            }
        }
    }
}
