package com.mlmvpn.scanner.ui.emergency

import android.app.Activity
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.CombineEngine
import com.mlmvpn.scanner.data.ScanGuard
import com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager
import com.mlmvpn.scanner.engines.rstaspoof.SniSession
import com.mlmvpn.scanner.engines.rstaspoof.SniEch
import com.mlmvpn.scanner.models.VpnNode
import com.mlmvpn.scanner.ui.home.HomeDestinations
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.tlsPing
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/**
 * One entry route: the edge address the engine dials, and the name it puts on the handshake.
 *
 * `method` groups the pairs that belong together. Which pairs get through is a property of the
 * NETWORK the user is on, not of the app: the same install connected on one Iranian mobile
 * operator and not on another, and the reason was simply that the addresses the second one
 * answers on were not in this list. The labels are deliberately neutral numbers — naming an
 * operator would be a guess about the user's network that goes stale the week that operator
 * changes what it blocks, and the app does not need the name: it measures.
 */
data class RstaConfig(
    val connectIp: String,
    val connectPort: Int,
    val fakeSni: String,
    val method: Int = 0,
)

/**
 * Identity, rather than position in the list.
 *
 * The list REORDERS itself after a latency scan, so a selection held as an index pointed at a
 * different route the moment the scan finished -- which the old screen papered over by resetting
 * the selection to the top row every time. Keying the selection and the measurements on the route
 * itself lets the ordering change under them without either one following the wrong row.
 */
private val RstaConfig.key: String get() = "$connectIp:$connectPort|$fakeSni"

val defaultRstaConfigs = listOf(
    // 1 — chatgpt.com against a spread of edges.
    RstaConfig("199.181.197.1", 443, "chatgpt.com", 1),
    RstaConfig("103.160.204.34", 443, "chatgpt.com", 1),
    RstaConfig("185.193.30.94", 443, "chatgpt.com", 1),
    RstaConfig("45.8.211.57", 443, "chatgpt.com", 1),
    RstaConfig("159.112.235.52", 443, "chatgpt.com", 1),
    RstaConfig("170.114.45.239", 443, "chatgpt.com", 1),
    RstaConfig("188.42.88.24", 443, "chatgpt.com", 1),
    RstaConfig("88.216.67.230", 443, "chatgpt.com", 1),
    RstaConfig("45.130.125.75", 443, "chatgpt.com", 1),
    // 2 — public CDN names, against the edges that serve them. The set this app shipped with.
    RstaConfig("104.18.35.46", 443, "replit.com", 2),
    RstaConfig("162.159.152.4", 443, "cdn.medium.com", 2),
    RstaConfig("104.18.183.237", 443, "chartjs.org", 2),
    RstaConfig("104.18.0.22", 443, "unpkg.com", 2),
    RstaConfig("104.18.11.207", 443, "bootstrapcdn.com", 2),
    RstaConfig("104.17.156.85", 443, "cloudflare.net", 2),
    RstaConfig("104.16.147.32", 443, "static.codepen.io", 2),
    RstaConfig("151.101.130.219", 443, "speedtest.net", 2),
    RstaConfig("85.9.112.219", 443, "www.hcaptcha.com", 2),
    // 3 — the upstream SNI-Spoofing project's own default, and the neighbour already shipped.
    RstaConfig("188.114.98.0", 443, "security.vercel.com", 3),
    RstaConfig("188.114.98.0", 443, "auth.vercel.com", 3),
    // 4-6 — tuned edge/name pairs from the UAC SNI Spoofer desktop project, which keeps one per
    // provider instead of one list for everyone. Its per-provider labels are dropped; what is
    // worth having is the PAIRS, because they are addresses this app never tried.
    RstaConfig("104.18.8.83", 443, "www.speedtest.net", 4),
    RstaConfig("104.18.9.83", 443, "www.speedtest.net", 4),
    RstaConfig("104.18.1.1", 443, "www.speedtest.net", 5),
    RstaConfig("172.66.0.1", 443, "www.speedtest.net", 5),
    RstaConfig("104.19.229.21", 443, "chatgpt.com", 6),
    RstaConfig("104.19.230.21", 443, "chatgpt.com", 6),
    RstaConfig("104.18.32.47", 443, "chatgpt.com", 6),
    RstaConfig("172.64.155.209", 443, "chatgpt.com", 6),
)

/** The port [RstaSpoofManager] binds. Shown to the user, because it is what a config points at. */
private const val LOCAL_ENTRY = "127.0.0.1:40443"

/** Pages this screen pushes on top of itself. */
private const val P_CONFIGS = "configs"
private const val P_BUILD = "build"

/**
 * The SNI anti-filter engine: one screen, one button, one connection.
 *
 * This page used to be half a feature. It started the local TLS front and stopped there -- and the
 * front carries no traffic by itself, it is a door the core has to walk through. The other half
 * was two screens away: build the SNI configs from "+ Add" on the connection page, then find them
 * in the list and connect one there. Nothing on either screen mentioned the other, so the ordinary
 * outcome was a user turning this on, reading "running", and concluding the app was broken.
 *
 * Now the whole thing is here and behaves like every other engine in the app: one dial, the same
 * one MASQUE, WireGuard, Psiphon and Tor use, with the same states and the same status line under
 * it. Pressing it applies the fastest route, brings up the front and connects the chosen config
 * through it -- and the status line names which of those three is happening, because a single
 * "connecting…" over a sequence that can fail in three different places tells the user nothing.
 *
 * The two measurements stay, because they answer different questions and the user needs both:
 * which ROUTE the front should dial (a TLS handshake to the edge, cheap, all eight at once), and
 * which CONFIG is fastest through it (a real proxied request, expensive, three at a time).
 *
 * Building configs is here too rather than on the connection page. It is the step that makes this
 * screen usable at all, and a feature whose first requirement lives in another screen's "+" menu
 * is a feature nobody finds.
 */
@Composable
fun EmergencyLevel3Screen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
    }

    var page by remember { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = page != null) { page = null }

    // --- what is actually running ----------------------------------------------------------
    //
    // Read from the service, not from a local flag. The connection outlives this screen -- it can
    // be started from the tile or left running while the user goes elsewhere -- so a local
    // "connected" boolean would be wrong every time they came back.
    val phase by MyVpnService.connectionPhaseFlow.collectAsState()
    val connectedId by MyVpnService.connectedNodeIdFlow.collectAsState()
    val frontUp by RstaSpoofManager.isRunningFlow.collectAsState()

    var configsRevision by remember { mutableStateOf(0) }
    val sniConfigs = remember(configsRevision) { SniSession.configs(context) }

    var selectedConfigId by remember(configsRevision) {
        mutableStateOf(
            prefs.getString(KEY_SELECTED_CONFIG, null)
                ?.takeIf { id -> sniConfigs.any { it.id == id } }
                ?: sniConfigs.firstOrNull()?.id
        )
    }
    val selectedConfig = sniConfigs.firstOrNull { it.id == selectedConfigId }

    // Ours only. A tunnel raised from the V2Ray tab or a transport must not light this dial.
    val isOurs = connectedId != null && sniConfigs.any { it.id == connectedId }
    var starting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    val stage = when {
        isOurs && phase == MyVpnService.Phase.CONNECTED -> DialState.RUNNING
        isOurs && phase == MyVpnService.Phase.CONNECTING -> DialState.STARTING
        starting -> DialState.STARTING
        failed || (isOurs && phase == MyVpnService.Phase.FAILED) -> DialState.FAILED
        else -> DialState.IDLE
    }

    // The service owns the outcome, so the local "starting" flag has done its job the moment the
    // service reports anything. Leaving it set kept the dial spinning after a failure.
    LaunchedEffect(phase) {
        if (phase == MyVpnService.Phase.CONNECTED || phase == MyVpnService.Phase.FAILED) {
            starting = false
            failed = phase == MyVpnService.Phase.FAILED && isOurs
        }
    }

    // --- routes ------------------------------------------------------------------------------
    var routes by remember { mutableStateOf(defaultRstaConfigs) }
    val pings = remember { mutableStateMapOf<String, Int>() }
    var isScanning by remember { mutableStateOf(false) }

    var selectedKey by remember {
        val (ip, _, sni) = RstaSpoofManager.storedRoute(context)
        val saved = defaultRstaConfigs.firstOrNull { it.fakeSni == sni && it.connectIp == ip }
        mutableStateOf(saved?.key ?: defaultRstaConfigs.first().key)
    }
    val selectedRoute = routes.firstOrNull { it.key == selectedKey } ?: routes.first()

    // --- config speeds ------------------------------------------------------------------------
    val configDelays = remember { mutableStateMapOf<String, Long>() }
    var isMeasuring by remember { mutableStateOf(false) }
    var measured by remember { mutableStateOf(0 to 0) }
    // Which connection method is being tried, while the ladder is walking. Null the rest of the
    // time. Without this the probe reads as a stalled progress bar: the count restarts at 1 of 3
    // over and over with nothing saying why.
    var trying by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val noConfigText = stringResource(R.string.sni_no_config_yet)

    // --- connect / disconnect ------------------------------------------------------------------
    fun startNow(node: VpnNode) {
        starting = true
        failed = false
        SniSession.connect(
            context = context,
            node = node,
            routeIp = selectedRoute.connectIp,
            routePort = selectedRoute.connectPort,
            routeSni = selectedRoute.fakeSni,
        )
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val node = selectedConfig
        if (result.resultCode == Activity.RESULT_OK && node != null) startNow(node) else starting = false
    }

    fun connect() {
        val node = selectedConfig
        if (node == null) {
            Toast.makeText(context, noConfigText, Toast.LENGTH_LONG).show()
            return
        }
        // Same guard every other connect path goes through: a running IP scan owns the link, and
        // taking it away mid-scan turns the scan's result into a fake one.
        ScanGuard.run(ScanGuard.Reason.CONNECT_VPN) {
            val consent = runCatching { VpnService.prepare(context) }.getOrNull()
            if (consent != null) {
                starting = true
                vpnLauncher.launch(consent)
            } else {
                startNow(node)
            }
        }
    }

    fun disconnect() {
        scope.launch {
            withContext(Dispatchers.IO) { SniSession.disconnect(context) }
            starting = false
            failed = false
        }
    }

    /**
     * Picking a route applies it immediately, and reconnects when something is already up.
     *
     * There is no Save button anywhere else in this app either. A picker that silently leaves a
     * live connection on the previous route is the one behaviour a user can neither see nor
     * correct -- the tick moves, the traffic does not.
     */
    fun selectRoute(route: RstaConfig) {
        selectedKey = route.key
        val changed = RstaSpoofManager.setRoute(
            context, route.connectIp, route.connectPort, route.fakeSni
        )
        if (changed && stage == DialState.RUNNING) {
            selectedConfig?.let { node ->
                scope.launch {
                    withContext(Dispatchers.IO) { SniSession.disconnect(context) }
                    kotlinx.coroutines.delay(400)
                    startNow(node)
                }
            }
        }
    }

    /**
     * Measure the entry points and adopt the fastest that answered.
     *
     * Suspending, and separate from the button, because it is now half of a longer run: the one
     * button on this screen measures the ROUTE and then the CONFIG through it, and doing those in
     * the other order measures every config over whichever entry point happened to be selected.
     */
    suspend fun scanRoutesNow() = kotlinx.coroutines.coroutineScope {
        val results = routes
            .map { route ->
                async(Dispatchers.IO) {
                    route to tlsPing(route.connectIp, route.connectPort, route.fakeSni)
                }
            }
            .awaitAll()
            .toMap()

        pings.clear()
        results.forEach { (route, ms) -> pings[route.key] = ms }
        // Unreachable routes sink to the bottom rather than disappearing: one that failed on
        // this network is still worth a tap, and hiding it would leave the list a different
        // length after every scan.
        routes = routes.sortedBy { results[it]?.takeIf { ms -> ms > 0 } ?: Int.MAX_VALUE }

        // Adopt the fastest, but only if it actually answered. When nothing did, the user's
        // own choice is left alone -- selecting the top row regardless would, on a fully
        // blocked network, silently replace a working route with a dead one.
        routes.firstOrNull()
            ?.takeIf { (results[it] ?: -1) > 0 && it.key != selectedKey }
            ?.let { selectRoute(it) }
        Unit
    }

    /**
     * The manual entry-point test.
     *
     * A WRAPPER, not a second implementation. It used to be a line-for-line copy of
     * [scanRoutesNow] -- the same measurement, the same sort, the same adopt-only-if-it-answered
     * rule, written twice -- and a copy is a place for the two to drift apart. That is not
     * hypothetical here: the config measurement on this same screen had a copied retry pass, and
     * the copy is what counted to 16 out of 8 and left the spinner turning.
     */
    fun scanRoutes() {
        if (isScanning || isMeasuring) return
        isScanning = true
        scope.launch {
            try {
                scanRoutesNow()
            } finally {
                isScanning = false
            }
        }
    }

    /**
     * Measure the configs themselves, through the front.
     *
     * A real proxied request per config, not a reachability check: every one of these points at
     * the same local port, so a TCP probe would measure the same socket eight times and rank
     * nothing. Three at a time, like every other bulk measurement in the app -- more than that
     * against one edge inflates every reading.
     *
     * The front has to be up first, or all of them measure a closed port.
     */
    fun measureConfigs() {
        if (isMeasuring || isScanning || sniConfigs.isEmpty()) return
        ScanGuard.run(ScanGuard.Reason.DELAY_TEST) {
            isMeasuring = true
            measured = 0 to sniConfigs.size
            scope.launch {
                // The entry points first, and the configs through the winner.
                //
                // These were two separate buttons and that put the burden of knowing the order on
                // the user: measuring configs over a slow entry point measures the entry point,
                // and adopting a faster one afterwards throws every number away. One press now
                // does both, in the only order in which either answer means anything.
                android.util.Log.d("SniMeasure", "routes: scanning")
                isScanning = true
                runCatching { scanRoutesNow() }
                isScanning = false
                android.util.Log.d("SniMeasure", "routes: done, picked ${selectedRoute.connectIp}")

                // Claimed, and given back in the `finally` below.
                //
                // The front is a process, and a measurement that merely starts it leaves it
                // running for the rest of the app's life -- which is what used to put the SNI
                // lamp on the home screen over a tunnel the user had connected afterwards. If a
                // session is already holding it, the claim simply stacks and the release below
                // does not take the door away from underneath it.
                val ready = withContext(Dispatchers.IO) {
                    RstaSpoofManager.setRoute(
                        context,
                        selectedRoute.connectIp,
                        selectedRoute.connectPort,
                        selectedRoute.fakeSni,
                    )
                    RstaSpoofManager.acquire(context)
                }
                try {
                    if (!ready) {
                        failed = true
                        return@launch
                    }
                    // TWO at a time, not three. Every one of these goes through the same single
                    // spoof front, and on a marginal network the contention is measurable: a rung
                    // that answered 1 of 3 configs at three-way concurrency is a rung whose front
                    // was being asked to do the fake-SNI dance on three sockets at once.
                    val gate = Semaphore(2)

                    /**
                     * One complete pass over [nodes]. Returns how many answered.
                     *
                     * A FUNCTION, and deliberately so: the retry used to be a second copy of this
                     * loop, and the copy shared the first pass's counter and had its own exit. Both
                     * showed. The progress counted past the end -- «۸ تا کانفیگ هست ولی میزنه ۱۶
                     * تا» -- because `done` went on counting from 8, and the spinner never stopped
                     * because the copy left through `return@launch`, which jumps over anything
                     * written after it. Neither can recur here: the counter is born inside the pass,
                     * and `isMeasuring` is cleared in the `finally`, where no return can miss it.
                     */
                    // ECH, the method that does not use the front at all (SniEch): the configs are
                    // measured as the same servers dialled straight to an edge, name encrypted.
                    var viaEch = false
                    val echEdge = SniEch.edgeFor(context, selectedRoute.connectIp)
                    fun uriFor(node: VpnNode): String =
                        if (viaEch) SniEch.directUri(node.uri, echEdge) ?: node.uri else node.uri

                    suspend fun pass(
                        nodes: List<VpnNode>,
                        timeoutMs: Long = 25_000L,
                        retry: Boolean = true,
                    ): Int = kotlinx.coroutines.coroutineScope {
                        val done = java.util.concurrent.atomic.AtomicInteger(0)
                        // Cleared HERE, before anything runs. It used to be cleared after the last
                        // result came back, so the list sat unchanged for the length of the run and
                        // then filled in at once -- a long wait with nothing to watch.
                        configDelays.clear()
                        measured = 0 to nodes.size
                        nodes.map { node ->
                            async(Dispatchers.IO) {
                                gate.acquire()
                                val ms = try {
                                    // A second attempt before calling it dead, and only when the
                                    // first found nothing -- the same warm two-shot the desktop
                                    // tester settled on. One attempt over a line where the
                                    // handshake is marginal reports a working config as dead, and
                                    // "no ping" is the one answer a user cannot argue with.
                                    CombineEngine.measureUri(context, uriFor(node), timeoutMs)
                                        .takeIf { it > 0 }
                                        ?: if (retry) CombineEngine.measureUri(context, uriFor(node), timeoutMs) else 0L
                                } finally {
                                    gate.release()
                                    measured = done.incrementAndGet() to nodes.size
                                }
                                // Published the moment it is known. The list is ordered on this
                                // map, so a result arriving also moves its row: the fastest climbs
                                // while you watch, which is the only part of a long run worth
                                // watching.
                                withContext(Dispatchers.Main) { configDelays[node.id] = ms }
                                ms
                            }
                        }.awaitAll().count { it > 0 }
                            .also { android.util.Log.d("SniMeasure", "pass of ${nodes.size} -> $it alive") }
                    }

                    // THE CONNECTION METHOD IS THE NEXT THING TO CHANGE WHEN NOTHING ANSWERS.
                    //
                    // RstaSpoofManager.PROFILES holds every combination of the engine's levers, and
                    // they are not interchangeable: the one that has always shipped carries zero
                    // bytes on one of Iran's two big mobile networks while another works on it
                    // three attempts out of three. The measurements are tabulated on SpoofProfile.
                    // Neither is right for everyone, so the right rung is the one THIS line
                    // answers, and the only way to know it is to try.
                    //
                    // Probed on a HANDFUL of configs and not all of them: fourteen rungs against
                    // every config would be a hundred measurements, and the question a rung has to
                    // answer is only "does anything at all come back through the front". Three
                    // configs answer that. The full list is then measured once, through the winner,
                    // which is the number the user is actually choosing between.
                    // TWO configs, and two at most. The question a rung has to answer is only
                    // "does anything come back through the front", and asking it of more configs
                    // does not answer it better -- these configs commonly share one endpoint, as
                    // all eight on the line this was debugged against did. Two, so that one dead
                    // config cannot condemn a rung that works.
                    val probeSet = sniConfigs.take(2)
                    val firstProfile = RstaSpoofManager.storedProfile(context)
                    // TWENTY SECONDS, and that number is measured, not chosen for comfort.
                    //
                    // It was seven, and seven was wrong in a way that hid the fix completely: the
                    // ladder found the rung that works, measured it as dead, and walked past it. On
                    // the line this was failing on, one WebSocket upgrade through the fragmented
                    // front takes **9 to 14 seconds** -- confirmed with `101 Switching Protocols`
                    // from the worker itself on every route tried, twice each -- and the delay test
                    // needs that upgrade plus a request through it. A budget under the handshake is
                    // not a fast test, it is a test that always says no.
                    //
                    // Shortening the fragment delay does not help: 0.02s and 0.005s measured no
                    // faster than the default. The slowness is the line, not the setting.
                    val probeBudget = 20_000L
                    android.util.Log.d("SniMeasure", "probing ${probeSet.size} configs on ${firstProfile.describe()}")
                    // Where ECH is what worked last time, it is asked first: the front's fourteen
                    // methods are minutes of nothing on a line that reassembles every cut.
                    var found = false
                    if (SniEch.enabled(context)) {
                        viaEch = true
                        found = pass(probeSet, probeBudget, retry = false) > 0
                        if (!found) viaEch = false
                    }
                    if (!found) found = pass(probeSet, probeBudget, retry = false) > 0
                    // ECH second, before the rest of the front's methods: where the filter reassembles
                    // every cut (the home Wi-Fi, 2026-10-05) those thirteen are six minutes of nothing,
                    // and where it does not, the stored method above has usually answered already.
                    if (!found && !SniEch.enabled(context)) {
                        viaEch = true
                        found = pass(probeSet, probeBudget, retry = false) > 0
                        android.util.Log.d("SniMeasure", "rung ECH via $echEdge -> ${if (found) "alive" else "dead"}")
                        if (!found) viaEch = false
                    }
                    if (!found) {
                        for ((i, p) in RstaSpoofManager.PROFILES.withIndex()) {
                            if (p.id == firstProfile.id) continue
                            trying = i + 1 to RstaSpoofManager.PROFILES.size
                            // restart(), NOT release()+acquire(): the levers are read when the
                            // process starts, and release() only stops it when the last claim goes,
                            // so one stale claim elsewhere in the app silently left the old process
                            // running and measured this rung against the previous rung's settings.
                            val up = withContext(Dispatchers.IO) {
                                RstaSpoofManager.setProfile(context, p.id)
                                RstaSpoofManager.restart(context)
                            }
                            android.util.Log.d("SniMeasure", "rung ${i + 1}: ${p.describe()} up=$up")
                            if (!up) continue
                            if (pass(probeSet, probeBudget, retry = false) > 0) { found = true; break }
                        }
                        trying = null
                        // A measurement that found nothing must not leave the app configured
                        // differently from how it found it -- the rung it happened to stop on would
                        // then be the one the connect button uses, chosen by nothing.
                        if (!found) {
                            withContext(Dispatchers.IO) {
                                RstaSpoofManager.setProfile(context, firstProfile.id)
                            }
                        }
                    }

                    // The real measurement, through whichever method answered. Skipped when the
                    // probe already covered every config there is.
                    // Remembered either way: ECH when it is what carried, the front's method otherwise.
                    if (found) withContext(Dispatchers.IO) { SniEch.set(context, viaEch) }
                    android.util.Log.d("SniMeasure", "found=$found ech=$viaEch; full pass=${found && probeSet.size < sniConfigs.size}")
                    if (found && probeSet.size < sniConfigs.size) pass(sniConfigs)

                    // Adopt the fastest that answered, and only then -- same rule as the route scan.
                    sniConfigs.mapNotNull { n -> configDelays[n.id]?.takeIf { it > 0 }?.let { n.id to it } }
                        .minByOrNull { it.second }
                        ?.let { (id, _) ->
                            selectedConfigId = id
                            prefs.edit().putString(KEY_SELECTED_CONFIG, id).apply()
                        }
                } finally {
                    // HERE, and not at the end of the happy path. Every `return@launch` above
                    // jumps straight to this block, so this is the only place that cannot be
                    // skipped -- and a cleared flag is what takes the screen off its spinner.
                    android.util.Log.d("SniMeasure", "finished")
                    isMeasuring = false
                    trying = null
                    // Off the main thread and outside cancellation: taking the front down is a
                    // blocking process kill, and this has to happen even when the screen goes.
                    runCatching {
                        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                            RstaSpoofManager.release()
                        }
                    }
                }
            }
        }
    }

    /**
     * Remove SNI configs, and leave the rest of the screen agreeing with the list.
     *
     * Four things move together, and leaving any one of them behind is a visible bug: the config
     * that is connected has to be disconnected FIRST (otherwise the dial goes on saying
     * "connected" over a config that no longer exists, and the only way to stop it is to find it
     * in a list it has just left); the stored selection has to go, because the quick-settings
     * tile reads that preference too; the measurements have to go with the configs they belong
     * to; and the page has to close itself once there is nothing left to show on it.
     */
    fun deleteConfigs(ids: Set<String>) {
        if (ids.isEmpty()) return
        val chosen = selectedConfigId
        val hitChosen = chosen != null && chosen in ids
        val live = stage == DialState.RUNNING && hitChosen
        val remaining = sniConfigs.count { it.id !in ids }
        scope.launch {
            if (live) withContext(Dispatchers.IO) { SniSession.disconnect(context) }
            val gone = withContext(Dispatchers.IO) { SniSession.delete(context, ids) }
            ids.forEach { configDelays.remove(it) }
            if (hitChosen) prefs.edit().remove(KEY_SELECTED_CONFIG).apply()
            // Re-reads the list and re-derives the selection from it; see the `remember` keys.
            configsRevision++
            if (gone > 0) {
                Toast.makeText(
                    context,
                    context.getString(R.string.sni_configs_deleted, faDigits(gone.toString())),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            if (remaining == 0) page = null
        }
    }

    /**
     * The configs that were measured and did not answer.
     *
     * A config with NO reading is not a dead config -- it has never been asked -- so it is not
     * offered here and cannot be swept up by a tap meant for something else.
     */
    fun deleteDeadConfigs() {
        val dead = sniConfigs.filter { (configDelays[it.id] ?: 1L) <= 0L }.map { it.id }.toSet()
        if (dead.isEmpty()) {
            Toast.makeText(context, S(R.string.nodes_delete_dead_none), Toast.LENGTH_SHORT).show()
            return
        }
        deleteConfigs(dead)
    }

    // --- pushed pages --------------------------------------------------------------------------
    when (page) {
        P_CONFIGS -> {
            SniConfigPickerPage(
                configs = sniConfigs,
                selectedId = selectedConfigId,
                delays = configDelays,
                measuring = isMeasuring || isScanning,
                measuredLabel = when {
                    isScanning -> stringResource(R.string.emergency_3_scanning)
                    trying != null -> stringResource(
                        R.string.sni_trying_method,
                        faDigits(trying!!.first.toString()),
                        faDigits(trying!!.second.toString()),
                    )
                    isMeasuring -> faDigits(measured.first.toString()) + "/" +
                        faDigits(measured.second.toString())
                    else -> null
                },
                onMeasure = { measureConfigs() },
                onSelect = { id ->
                    selectedConfigId = id
                    prefs.edit().putString(KEY_SELECTED_CONFIG, id).apply()
                    page = null
                },
                onDelete = { node -> deleteConfigs(setOf(node.id)) },
                onDeleteDead = { deleteDeadConfigs() },
                onDeleteAll = { deleteConfigs(sniConfigs.map { it.id }.toSet()) },
                onBack = { page = null },
            )
            return
        }

        P_BUILD -> {
            SniBuildPage(
                onBuilt = { added ->
                    configsRevision++
                    page = null
                    Toast.makeText(
                        context,
                        context.getString(R.string.sni_configs_were_built, faDigits(added.toString())),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onBack = { page = null },
            )
            return
        }
    }

    IosScreen(
        title = stringResource(R.string.emergency_3_title),
        onBack = onBack,
        backLabel = stringResource(R.string.emergency_3_back),
    ) {
        Spacer(Modifier.height(18.dp))

        Text(
            "SNI Fragmentation",
            color = Ios.SecondaryLabel,
            fontSize = 13.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.emergency_3_tagline),
            color = Ios.SecondaryLabel,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 34.dp),
        )

        Spacer(Modifier.height(26.dp))

        EmergencyDial(
            state = stage,
            idleAccent = HomeDestinations.Red,
            idleIcon = Icons.Default.VpnLock,
            idleLabel = stringResource(R.string.emergency_3_action_on),
            runningLabel = stringResource(R.string.emergency_3_action_off),
            onClick = {
                when (stage) {
                    DialState.RUNNING -> disconnect()
                    DialState.STARTING -> Unit
                    else -> connect()
                }
            },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(18.dp))

        // Three things happen behind one button, and they fail in three different places. The
        // status line names which one is running so a failure is diagnosable rather than just red.
        EmergencyStatusLine(
            state = stage,
            idleAccent = HomeDestinations.Red,
            headline = stringResource(
                when {
                    stage == DialState.RUNNING -> R.string.emergency_3_state_on
                    stage == DialState.STARTING && !frontUp -> R.string.sni_state_starting_front
                    stage == DialState.STARTING -> R.string.sni_state_starting_tunnel
                    stage == DialState.FAILED -> R.string.emergency_3_state_failed
                    else -> R.string.emergency_3_state_off
                }
            ),
            sub = when (stage) {
                DialState.RUNNING -> stringResource(R.string.emergency_3_sub_on)
                DialState.FAILED -> stringResource(R.string.sni_sub_failed)
                DialState.IDLE -> if (sniConfigs.isEmpty()) stringResource(R.string.sni_no_config_yet) else null
                else -> null
            },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(24.dp))

        // Only while connected: every field in it answers "what is carrying my traffic right
        // now", which is not a question an idle screen has an answer to.
        AnimatedVisibility(visible = stage == DialState.RUNNING) {
            Column {
                SettingsSectionHeader(stringResource(R.string.emergency_3_active_route))
                SettingsGroup {
                    SettingsRow(
                        title = stringResource(R.string.sni_row_config),
                        icon = Icons.Default.Storage,
                        tint = Ios.Green,
                        value = selectedConfig?.name?.take(22),
                        showChevron = false,
                    )
                    Separator()
                    SettingsRow(
                        title = stringResource(R.string.emergency_3_row_sni),
                        icon = Icons.Default.Dns,
                        tint = Ios.Teal,
                        value = selectedRoute.fakeSni,
                        showChevron = false,
                    )
                    Separator()
                    SettingsRow(
                        title = stringResource(R.string.emergency_3_row_edge),
                        icon = Icons.Default.Public,
                        tint = Ios.Indigo,
                        value = "${selectedRoute.connectIp}:${selectedRoute.connectPort}",
                        showChevron = false,
                    )
                    Separator()
                    SettingsRow(
                        title = stringResource(R.string.emergency_3_row_local),
                        icon = Icons.Default.Link,
                        tint = Ios.Gray,
                        value = LOCAL_ENTRY,
                        showChevron = false,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        // --- the config half ------------------------------------------------------------------
        SettingsSectionHeader(stringResource(R.string.sni_section_config))
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.sni_row_selected_config),
                icon = Icons.Default.Storage,
                tint = if (selectedConfig == null) Ios.Gray else Ios.Blue,
                value = selectedConfig?.name?.take(20)
                    ?: stringResource(R.string.not_chosen_yet),
                onClick = { if (sniConfigs.isNotEmpty()) page = P_CONFIGS },
            )
            Separator()
            SettingsActionRow(
                label = when {
                    isScanning -> stringResource(R.string.emergency_3_scanning)
                    trying != null -> stringResource(
                        R.string.sni_trying_method,
                        faDigits(trying!!.first.toString()),
                        faDigits(trying!!.second.toString()),
                    )
                    isMeasuring -> stringResource(
                        R.string.sni_measuring_progress,
                        faDigits(measured.first.toString()),
                        faDigits(measured.second.toString()),
                    )
                    else -> stringResource(R.string.sni_measure_configs)
                },
                icon = Icons.Default.Speed,
                tint = Ios.Green,
                busy = isMeasuring || isScanning,
                enabled = sniConfigs.isNotEmpty() && !isMeasuring && !isScanning,
                onClick = { measureConfigs() },
            )
            Separator()
            SettingsActionRow(
                label = stringResource(R.string.sni_build_configs),
                icon = Icons.Default.Add,
                tint = Ios.Indigo,
                onClick = { page = P_BUILD },
            )
        }
        SettingsFooter(stringResource(R.string.sni_section_config_footer))

        // --- the route half -------------------------------------------------------------------
        SettingsSectionHeader(stringResource(R.string.emergency_3_entry_points))
        SettingsGroup {
            // ABOVE the list, not under it. There are twenty-eight routes, so a button after them
            // is a button behind a scroll -- and this is the one thing to press on a network where
            // the selected route is not the one that works.
            SettingsActionRow(
                label = stringResource(
                    if (isScanning) R.string.emergency_3_scanning else R.string.emergency_3_scan
                ),
                icon = Icons.Default.NetworkCheck,
                busy = isScanning,
                enabled = !isScanning && !isMeasuring,
                onClick = { scanRoutes() },
            )
            routes.forEach { route ->
                Separator()
                RouteRow(
                    route = route,
                    selected = route.key == selectedKey,
                    ping = pings[route.key],
                    dimmed = isScanning,
                    onClick = { selectRoute(route) },
                )
            }
        }
        SettingsFooter(stringResource(R.string.emergency_3_routes_footer))

        Spacer(Modifier.height(6.dp))
        SettingsFooter(stringResource(R.string.emergency_3_footer))

        Spacer(Modifier.height(28.dp))
    }
}

/** Which SNI config the dial connects. Persisted, because the tile can start it too. */
private const val KEY_SELECTED_CONFIG = "sni_selected_config_id"

/**
 * One route, laid out the way this app lays out any single choice: label and detail on the
 * leading edge, the value on the trailing edge, and a blue tick against the current one.
 *
 * The tick's 20dp is reserved whether or not it is drawn, so the latency column does not shift
 * sideways by a glyph's width between the selected row and the rest.
 */
@Composable
private fun RouteRow(
    route: RstaConfig,
    selected: Boolean,
    ping: Int?,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                route.fakeSni,
                color = Ios.Label,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (route.method > 0) "${route.connectIp}:${route.connectPort}  ·  روش ${route.method}"
                else "${route.connectIp}:${route.connectPort}",
                color = Ios.SecondaryLabel,
                fontSize = 12.sp,
            )
        }

        if (ping != null) {
            Text(
                pingLabel(ping),
                color = pingTone(ping).copy(alpha = if (dimmed) 0.4f else 1f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(10.dp))
        }

        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Ios.Blue,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Latency for one route.
 *
 * [tlsPing] returns -1 when the TLS handshake never completed, which is not a slow route but an
 * unreachable one -- and that distinction is the whole point of the scan. Persian digits, like
 * every other measurement the app prints.
 */
@Composable
private fun pingLabel(ms: Int): String =
    if (ms <= 0) stringResource(R.string.emergency_3_no_ping) else "${faDigits(ms.toString())}ms"
