package com.mlmvpn.core.urnetwork

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.engines.mae.model.FamilyPolicy
import com.mlmvpn.scanner.engines.mae.route.Capabilities
import com.mlmvpn.scanner.engines.mae.route.RouteKind
import com.mlmvpn.scanner.engines.mae.route.RouteProvider
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

/**
 * «URnetwork» as one of MAE's exits abroad: its core in a process and a folder of its own -- a
 * device of its own in the same account, never the tile's -- up only while MAE is the tunnel and the
 * user leaves «استفاده در MAE» on.
 *
 * MAE dials it as a plain local SOCKS outbound ([UrnetworkRoute]). Nothing here decides that a
 * service opens through URnetwork: MAE's own probes prove that per service and per network, as for
 * every other exit, so a URnetwork this network cannot reach is simply never chosen.
 */
object UrnetworkMae {

    private const val TAG = "UrnetworkMae"

    /** MAE already runs two Xray cores; a third process is not worth a phone this small. */
    private const val MIN_TOTAL_RAM = 2_500L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var job: Job? = null
    /** The job [stop] ended: a new start waits for its core to let go of the folder. */
    @Volatile private var ending: Job? = null
    @Volatile private var proc: UrnetworkProcess? = null
    @Volatile private var port = 0

    private fun wanted(ctx: Context): Boolean =
        UrnetworkSettings.forMae(ctx) && UrnetworkEngine.available(ctx) && UrnetworkEngine.hasAccount(ctx) && roomy(ctx)

    private fun roomy(ctx: Context): Boolean = runCatching {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return true
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        !am.isLowRamDevice && info.totalMem >= MIN_TOTAL_RAM
    }.getOrDefault(true)

    /** MAE's route through URnetwork while its core is up with providers in its window; null otherwise. */
    fun route(ctx: Context): UrnetworkRoute? {
        val p = proc ?: return null
        val at = port
        return if (at > 0 && p.isAlive && p.listening && p.added > 0 && UrnetworkSettings.forMae(ctx)) UrnetworkRoute(at) else null
    }

    /**
     * Brings the core up for MAE's session and keeps it up -- a core that dies is started again.
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
                before?.join()
                keep(app, changed)
            }
        }
    }

    /** Ends the core. Returns at once: the route is gone now, the core is stopped by its own job. */
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
            // A core that ran and then died starts again soon; one that found no provider waits
            // longer each time (15 s, 30 s … 4 min), so a network that blocks URnetwork costs little.
            delay(if (cameUp) 3_000 else 15_000L shl minOf(failures - 1, 4))
        }
    }

    /** One core, from its start to its end. True when it came up with providers. */
    private suspend fun runOnce(app: Context, changed: () -> Unit): Boolean {
        withContext(Dispatchers.IO) { UrnetworkEngine.syncAccount(app) }
        val socks = UrnetworkEngine.freePort()
        val p = UrnetworkProcess(role = "mae", dir = UrnetworkEngine.roleDir(app, "mae"), socksPort = socks, memoryMb = 48)
        synchronized(lock) { proc = p }
        var cameUp = false
        try {
            p.start(app)
            val ready = runInterruptible { p.awaitReady(100_000) { false } }
            val up = ready && runInterruptible { p.awaitProviders(90_000) { false } }
            if (!up) {
                Log.i(TAG, "core did not come up (ready=$ready, error=${p.lastError}, exit=${p.exitCode})")
                // A refused sign-in is recovered before the next start (a new device, or the account
                // signed in again with its recovery phrase).
                if (p.loggedOut || p.exitCode == UrnetworkEngine.EXIT_LOGGED_OUT) runInterruptible { UrnetworkEngine.recover(app, p) }
                return false
            }
            cameUp = true
            val live = currentCoroutineContext().isActive
            // Not when [stop] came in meanwhile: the route must not reappear for a core on its way out.
            synchronized(lock) { if (live && job != null) port = socks }
            Log.i(TAG, "up on $socks for MAE (${p.added} providers)")
            runCatching { changed() }
            while (currentCoroutineContext().isActive && p.isAlive) delay(2_000)
            Log.i(TAG, "core on $socks ended (exit ${p.exitCode})")
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
}

/**
 * URnetwork's core, for MAE: its local SOCKS port as an Xray outbound. A FOREIGN candidate like every
 * other exit -- used for a service only once that service's own probe passed through it. The core's
 * proxy carries UDP too (UDP ASSOCIATE through the providers). Its traffic counts against the
 * account's data (a daily allowance on the free plan), so heavy traffic prefers anything else that works.
 */
class UrnetworkRoute(private val port: Int) : RouteProvider {
    override val id = ID
    override val kind = RouteKind.FOREIGN
    // Its window spreads connections over several providers, so the exit is not one place.
    override val caps = Capabilities(udp = true, quotaLimited = true, stableExit = false)
    override val cost = 0.3
    override val families = listOf(FamilyPolicy.BOTH)

    override fun tag(family: FamilyPolicy) = TAG

    override fun outbounds(): List<JSONObject> = listOf(
        JSONObject().put("tag", TAG).put("protocol", "socks")
            .put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().put("address", "127.0.0.1").put("port", port)))),
    )

    companion object {
        const val ID = "urnetwork"
        /** No other MAE tag starts with this, and it starts with no other tag. */
        const val TAG = "mae-urn"
    }
}
