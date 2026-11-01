package com.mlmvpn.core.lantern

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.mlmvpn.core.warp.WarpVia
import com.mlmvpn.scanner.engines.mae.model.FamilyPolicy
import com.mlmvpn.scanner.engines.mae.route.Capabilities
import com.mlmvpn.scanner.engines.mae.route.RouteKind
import com.mlmvpn.scanner.engines.mae.route.RouteProvider
import com.mlmvpn.scanner.ui.lantern.LanternSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/**
 * «لنترن» as one of MAE's exits abroad: Lantern 10's core in a process of its own, in a folder and
 * on ports of its own -- never the tile's, which may start while this one winds down -- up only while
 * MAE is the tunnel and the user leaves «استفاده در MAE» on.
 *
 * MAE dials it as a plain local SOCKS outbound ([LanternRoute]). Nothing here decides that Lantern is
 * abroad or that a service accepts it: MAE's own probes prove that per service and per network, as
 * for every other exit, so a Lantern that this network blocks is simply never chosen.
 *
 * The core starts from the tile's kept list (copied, the way Scout does), so it comes up in a second
 * or two; with no list anywhere it fetches one directly, and through WARP after twenty seconds of
 * nothing -- the tile's own first-list ladder.
 */
object LanternMae {

    private const val TAG = "LanternMae"

    /**
     * MAE already runs two Xray cores; a third process is not worth a phone this small. Below this,
     * MAE goes on with its other exits and the toggle stays as the user left it.
     */
    private const val MIN_TOTAL_RAM = 2_500L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var job: Job? = null
    /** The job [stop] ended: a new start waits for its core to let go of the folder. */
    @Volatile private var ending: Job? = null
    @Volatile private var proc: LanternProcess? = null
    @Volatile private var port = 0

    fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, "mae/lantern/v10")

    private fun wanted(ctx: Context): Boolean =
        LanternSettings.forMae(ctx) && LanternEngine.available(ctx) && roomy(ctx)

    private fun roomy(ctx: Context): Boolean = runCatching {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return true
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        !am.isLowRamDevice && info.totalMem >= MIN_TOTAL_RAM
    }.getOrDefault(true)

    /** MAE's route through Lantern while its core is up and listening; null otherwise. */
    fun route(ctx: Context): LanternRoute? {
        val p = proc ?: return null
        val at = port
        return if (at > 0 && p.isAlive && p.listening && LanternSettings.forMae(ctx)) LanternRoute(at) else null
    }

    /**
     * Brings the core up for MAE's session, and keeps it up -- a core that dies is started again.
     * Nothing when the user turned it off or this phone cannot run it. [changed] runs whenever the
     * route appears or goes, so MAE can bring its tunnel up to date.
     */
    fun start(ctx: Context, changed: () -> Unit) {
        val app = ctx.applicationContext
        if (!wanted(app)) return
        synchronized(lock) {
            if (job?.isActive == true) return
            val before = ending
            job = scope.launch {
                // The core [stop] ended may still be winding down in the same folder.
                before?.join()
                keep(app, changed)
            }
        }
    }

    /**
     * Ends the core (MAE's session ended, or the user turned it off). Returns at once: the route is
     * gone now, and the core itself is stopped by its own job, which may take a few seconds.
     */
    fun stop() {
        val j = synchronized(lock) {
            port = 0
            job.also { job = null; ending = it }
        }
        j?.cancel()
    }

    private suspend fun keep(app: Context, changed: () -> Unit) {
        var failures = 0
        while (currentCoroutineContext().isActive && wanted(app)) {
            val cameUp = runOnce(app, changed)
            failures = if (cameUp) 0 else failures + 1
            // A core that ran and then died starts again soon; one that would not come up waits
            // longer each time (15 s, 30 s … 4 min), so a network that blocks Lantern costs little.
            delay(if (cameUp) 3_000 else 15_000L shl minOf(failures - 1, 4))
        }
    }

    /** One core, from its start to its end. True when it came up. */
    private suspend fun runOnce(app: Context, changed: () -> Unit): Boolean {
        val dir = dir(app)
        LanternEngine.seedProfile(LanternEngine.dataDir(app), dir)
        val kept = LanternEngine.cachedServerCount(dir)
        val socks = freePort()
        val p = LanternProcess(socks, freePort(), dir)
        synchronized(lock) { proc = p }
        var cameUp = false
        try {
            p.start(app, smart = false, via = null, ident = LanternEngine.readIdentity(app), dns = LanternEngine.systemDns(app))
            var up = runInterruptible { p.awaitListening(20_000) { false } }
            if (!up && kept == 0 && p.isAlive) {
                up = WarpVia.use(app) { warpPort, _ ->
                    if (warpPort > 0) p.command("via 127.0.0.1:$warpPort")
                    val ok = runInterruptible { p.awaitListening(220_000) { false } }
                    if (warpPort > 0) p.command("via direct")
                    ok
                }
            }
            if (!up) {
                Log.i(TAG, "core did not come up (kept list: $kept)")
                return false
            }
            cameUp = true
            val live = currentCoroutineContext().isActive
            // Not when [stop] came in meanwhile: the route must not reappear for a core on its way out.
            synchronized(lock) { if (live && job != null) port = socks }
            Log.i(TAG, "up on $socks for MAE (kept list: $kept)")
            // A list fetched here is the tile's first list too, when the tile has none of its own.
            if (LanternEngine.cachedServerCount(app) == 0 && !LanternEngine.isAlive) LanternEngine.seedProfile(dir, LanternEngine.dataDir(app))
            runCatching { changed() }
            while (currentCoroutineContext().isActive && p.isAlive) delay(2_000)
            Log.i(TAG, "core on $socks ended")
            return true
        } finally {
            synchronized(lock) {
                if (proc === p) proc = null
                if (port == socks) port = 0
            }
            withContext(NonCancellable) { p.stop() }
            if (cameUp) runCatching { changed() }
        }
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
}

/**
 * Lantern's core, for MAE: its local SOCKS port as an Xray outbound. A FOREIGN candidate like every
 * other exit -- used for a service only once that service's own probe passed through it. TCP only:
 * MAE's UDP (QUIC, calls) stays on the routes that carry it.
 */
class LanternRoute(private val port: Int) : RouteProvider {
    override val id = ID
    override val kind = RouteKind.FOREIGN
    // Its core spreads connections over the servers on its list, so the exit is not one place.
    override val caps = Capabilities(udp = false, stableExit = false)
    override val cost = 0.2
    override val families = listOf(FamilyPolicy.BOTH)

    override fun tag(family: FamilyPolicy) = TAG

    override fun outbounds(): List<JSONObject> = listOf(
        JSONObject().put("tag", TAG).put("protocol", "socks")
            .put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().put("address", "127.0.0.1").put("port", port)))),
    )

    companion object {
        const val ID = "lantern"
        /** No other MAE tag starts with this, and it starts with no other tag. */
        const val TAG = "mae-ltn"
    }
}
