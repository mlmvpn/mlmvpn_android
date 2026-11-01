package com.mlmvpn.core.warp

import android.content.Context
import android.util.Log
import com.mlmvpn.core.tunnel.SocksFront
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The IPv4 exit while a WARP-family tunnel is up -- the desktop's server.js › warpExitVerify,
 * publishEngineExit and warpExitWatchStart, for the phone.
 *
 *  - Proves the exit with a real request: Cloudflare's trace over HTTPS through the tunnel's front,
 *    the way a browser uses the proxy (the desktop's plain-HTTP check asked HTTP proxies for CONNECT
 *    :80, which most refuse, so a healthy proxy was marked dead and the exit fell back to Iran while
 *    the page still named the country -- 2026-10-13).
 *  - A proxy that answers from ANOTHER country is not dead, it is mis-filed: it moves to the country
 *    it really leaves from, and this country tries its next.
 *  - A dead one is marked and the next of the pool takes over -- in the running front, without the
 *    tunnel going down. A pool that runs dry is measured again, through WARP, before giving up; and
 *    giving up is said on the page (the exit is WARP's own then), not only in the log.
 *  - While a proxy carries the traffic it is asked every minute: a free proxy dies without notice,
 *    and a tunnel routed through a dead one is a phone with no internet and a green badge. Two misses
 *    in a row, and the next one takes over.
 */
object Ipv4ExitSession {
    private const val TAG = "Ipv4Exit"
    private const val MAX_SWAPS = 6
    private const val WATCH_EVERY_MS = 60_000L

    enum class Via { PROXY, WARP }

    /** Where the tunnel's traffic really leaves, measured -- for the «کشور خروجی» line on the engine pages. */
    data class Live(
        val measuring: Boolean,
        val ok: Boolean = false,
        val loc: String? = null,
        val ip: String? = null,
        /** The country the user picked. */
        val want: String? = null,
        val via: Via = Via.PROXY,
        /** No proxy of the country carried: the exit is WARP's own, and the page says so. */
        val fellBack: Boolean = false,
        /** The proxy that carries, for the page («پراکسی 1.2.3.4»). */
        val proxy: String? = null,
        val at: Long = System.currentTimeMillis(),
    )

    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null

    /**
     * Starts proving and watching the exit of a tunnel whose front listens on [frontPort] and whose
     * WARP engine's raw SOCKS port is [enginePort] (for re-measuring a dry pool). [onExit] hears every
     * verdict, for the notification and the status the service publishes.
     */
    fun start(ctx: Context, frontPort: Int, enginePort: Int, log: (String) -> Unit, onExit: (Live) -> Unit) {
        stop()
        val app = ctx.applicationContext
        job = scope.launch {
            verify(app, frontPort, enginePort, log, onExit, attempt = 1, rescanned = false)
            var misses = 0
            while (isActive) {
                delay(WATCH_EVERY_MS)
                if (SocksFront.exitHop == null) continue
                val want = Ipv4Exits.load(app).country
                val r = Ipv4Exits.httpsTrace(frontPort)
                if (r.ok && (want.isBlank() || r.loc == want)) { misses = 0; continue }
                if (++misses < 2) continue
                misses = 0
                log("⚠️ پراکسی خروجی دیگر جواب نمی‌دهد — جابه‌جایی به پراکسی بعدی همین کشور…")
                verify(app, frontPort, enginePort, log, onExit, attempt = 1, rescanned = false)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _live.value = null
    }

    private fun entryOf(ctx: Context, hop: SocksFront.ExitHop): Ipv4Exits.PoolEntry? {
        val s = Ipv4Exits.load(ctx)
        return s.pools.values.asSequence().flatten().firstOrNull { it.ip == hop.ip && it.port == hop.port }
    }

    private fun publish(onExit: (Live) -> Unit, live: Live) {
        _live.value = live
        onExit(live)
    }

    private suspend fun verify(
        ctx: Context, frontPort: Int, enginePort: Int, log: (String) -> Unit, onExit: (Live) -> Unit,
        attempt: Int, rescanned: Boolean,
    ) {
        val want = Ipv4Exits.load(ctx).country
        var hop = SocksFront.exitHop
        var scanned = rescanned
        if (hop == null) {
            // Turned on with nothing healthy left in the pool: measured now, through WARP, before giving up.
            if (!scanned && want.isNotBlank()) {
                _live.value = Live(measuring = true, want = want)
                log("سنجش پراکسی‌های $want از داخل وارپ…")
                runCatching { Ipv4Exits.scan(ctx, want, enginePort) }
                scanned = true
                Ipv4Exits.chosen(ctx)?.let { SocksFront.setExit(Ipv4Exits.hopOf(it)); hop = SocksFront.exitHop }
            }
            if (hop == null) return fellBack(enginePort, want, log, onExit)
        }
        val current = hop!!
        _live.value = Live(measuring = true, want = want, proxy = current.ip)
        val r = Ipv4Exits.httpsTrace(frontPort)
        val entry = entryOf(ctx, current)
        if (r.ok && (want.isBlank() || r.loc == want)) {
            log("🌍 کشور خروجی: ${r.loc} (پراکسی ${current.ip}، آی‌پی ${r.ip}).")
            if (entry != null && r.loc != null) Ipv4Exits.confirm(ctx, entry, r.loc)
            publish(onExit, Live(measuring = false, ok = true, loc = r.loc, ip = r.ip, want = want, via = Via.PROXY, proxy = current.ip))
            return
        }
        if (r.ok && r.loc != null && want.isNotBlank() && r.loc != want && entry != null) {
            Ipv4Exits.reclassify(ctx, entry, want, r.loc)
            log("پراکسی ${current.ip} در واقع از ${r.loc} بیرون می‌آید، نه $want — به فهرست ${r.loc} منتقل شد.")
        }
        entry?.let { Ipv4Exits.reportDead(ctx, it) }
        var next = Ipv4Exits.chosen(ctx)
        if (next == null && !scanned && want.isNotBlank()) {
            log("پراکسی‌های ذخیره‌شدهٔ $want جواب ندادند — سنجش دوباره از داخل وارپ…")
            _live.value = Live(measuring = true, want = want)
            runCatching { Ipv4Exits.scan(ctx, want, enginePort) }
            scanned = true
            next = Ipv4Exits.chosen(ctx)
        }
        if (next != null && attempt < MAX_SWAPS) {
            log("پراکسی ${current.ip} جواب نداد — پراکسی بعدی امتحان می‌شود…")
            SocksFront.setExit(Ipv4Exits.hopOf(next))
            return verify(ctx, frontPort, enginePort, log, onExit, attempt + 1, scanned)
        }
        fellBack(enginePort, want, log, onExit)
    }

    /** No proxy of the country carries: the tunnel goes on through WARP itself, and that is said. */
    private fun fellBack(enginePort: Int, want: String, log: (String) -> Unit, onExit: (Live) -> Unit) {
        SocksFront.setExit(null)
        log("⚠️ هیچ پراکسی سالمی برای $want پیدا نشد — خروجی خود وارپ استفاده می‌شود.")
        val r = Ipv4Exits.httpsTrace(enginePort)
        Log.i(TAG, "fell back to WARP's own exit (${r.loc})")
        publish(onExit, Live(measuring = false, ok = r.ok, loc = r.loc, ip = r.ip, want = want, via = Via.WARP, fellBack = true))
    }
}
