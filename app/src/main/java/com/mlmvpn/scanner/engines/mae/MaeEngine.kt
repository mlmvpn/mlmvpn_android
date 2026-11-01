package com.mlmvpn.scanner.engines.mae

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.mlmvpn.core.warp.VlessXrayInjector
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.data.SecureStore
import com.mlmvpn.scanner.engines.game.booster.memory.NetworkKey
import com.mlmvpn.scanner.engines.game.booster.session.GameNetwork
import com.mlmvpn.scanner.engines.mae.compile.MaeConfigCompiler
import com.mlmvpn.scanner.engines.mae.control.XrayApiClient
import com.mlmvpn.scanner.engines.mae.device.DeviceSignals
import com.mlmvpn.scanner.engines.mae.egress.MaeCloudConfigs
import com.mlmvpn.scanner.engines.mae.egress.MaeEgressDeployer
import com.mlmvpn.scanner.engines.mae.egress.MaeWarp
import com.mlmvpn.scanner.engines.mae.model.Diagnosis
import com.mlmvpn.scanner.engines.mae.model.DnsPath
import com.mlmvpn.scanner.engines.mae.model.FailMode
import com.mlmvpn.scanner.engines.mae.model.FamilyPolicy
import com.mlmvpn.scanner.engines.mae.model.ForeignEgressStatus
import com.mlmvpn.scanner.engines.mae.model.Health
import com.mlmvpn.scanner.engines.mae.model.RouteMetrics
import com.mlmvpn.scanner.engines.mae.model.ServiceDef
import com.mlmvpn.scanner.engines.mae.model.ServicePolicy
import com.mlmvpn.scanner.engines.mae.policy.Decision
import com.mlmvpn.scanner.engines.mae.policy.Discovery
import com.mlmvpn.scanner.engines.mae.policy.EchoCache
import com.mlmvpn.scanner.engines.mae.policy.PolicyEngine
import com.mlmvpn.scanner.engines.mae.policy.ProbeBudget
import com.mlmvpn.scanner.engines.mae.policy.RepairLadder
import com.mlmvpn.scanner.engines.mae.policy.RouteScorer
import com.mlmvpn.scanner.engines.mae.policy.Symptom
import com.mlmvpn.scanner.engines.mae.probe.LiveCanary
import com.mlmvpn.scanner.engines.mae.probe.NetProber
import com.mlmvpn.scanner.engines.mae.probe.ProbeRoute
import com.mlmvpn.scanner.engines.mae.registry.ServiceRegistry
import com.mlmvpn.scanner.engines.mae.route.CircuitBreaker
import com.mlmvpn.scanner.engines.mae.route.DirectRoute
import com.mlmvpn.scanner.engines.mae.route.FragmentRoute
import com.mlmvpn.scanner.engines.mae.route.RouteHealth
import com.mlmvpn.scanner.engines.mae.route.RouteKind
import com.mlmvpn.scanner.engines.mae.route.RouteProvider
import com.mlmvpn.scanner.engines.mae.route.ServerlessRoute
import com.mlmvpn.scanner.engines.mae.route.UserConfigRoute
import com.mlmvpn.scanner.engines.mae.route.WarpRoute
import com.mlmvpn.scanner.engines.mae.route.WorkerRoute
import com.mlmvpn.scanner.engines.mae.store.CustomApp
import com.mlmvpn.scanner.engines.mae.store.MaeState
import com.mlmvpn.scanner.engines.mae.store.MaeStore
import com.mlmvpn.scanner.engines.mae.store.SelectedService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.util.Collections

/**
 * MAE's control plane. The data plane is the existing Xray core run by [MyVpnService]; this object
 * decides what that core is told:
 *
 *  - keeps the user's services and what was learned about them per network ([MaeStore]);
 *  - discovers routes in the background with a staged, budgeted prober, one service at a time,
 *    user-reported failures first, apps that must not look Iranian next ([enqueue]);
 *  - compiles one Xray config (Serverless base + per-service balancers + per-app rules) and connects;
 *  - applies route changes live through the Xray API when the spike proved it works on this
 *    phone, otherwise by recompiling and reconnecting;
 *  - watches the routes in use from inside the running tunnel ([LiveCanary]) and heals a dead
 *    one by itself -- by a fresh session, or by moving the apps on it to their next proven route;
 *  - learns passively from Xray's per-outbound counters while connected.
 *
 * Every failure inside MAE is contained here: a provider that throws marks itself unhealthy, a
 * discovery that throws marks the service UNKNOWN, a background task that throws is logged (it
 * used to take the whole app down), and the tunnel keeps its last good config.
 */
object MaeEngine {
    private const val TAG = "MAE"
    const val NODE_ID = "mae"
    private const val BASE_ASSET = "serverless_v48_low_delay.json"
    private const val DNS_TTL_MS = 6 * 3600_000L
    /** Networks unseen this long are forgotten (their decisions, proofs and numbers). */
    private const val NET_FORGET_MS = 30L * 24 * 3600_000L
    /** While connected, stale decisions are re-checked this often (TTL is 2-10 h). */
    private const val REFRESH_EVERY_MS = 15 * 60_000L
    /** The live check runs this often while the screen is on. */
    private const val CANARY_EVERY_MS = 5 * 60_000L
    /** Xray's per-route counters are read this often while the screen is on. */
    private const val STATS_EVERY_MS = 30_000L
    /** Unlocking after this long with the screen off runs the live check at once. */
    private const val WAKE_CHECK_AFTER_MS = 3 * 60_000L
    /** A heal by reconnecting costs every app a blip: at most this often. */
    private const val HEAL_RECONNECT_GAP_MS = 3 * 60_000L
    /** A background reconnect waits for the app checks to finish, at most this long. */
    private const val CHECKS_QUIET_WAIT_MS = 3 * 60_000L
    /** Background reconnects for changed rules (an app added, its sites learned): at most this often. */
    private const val RULES_RECONNECT_GAP_MS = 2 * 60_000L
    /** For routes no app could be moved to live: at most this often -- the apps' routes work meanwhile. */
    private const val ROUTES_RECONNECT_GAP_MS = 15 * 60_000L
    /** How long Lantern's core outlives MAE's session while MAE is still wanted (its own reconnects). */
    private const val LANTERN_KEEP_MS = 30_000L
    private const val NET_CACHE_MS = 3_000L

    enum class Phase { WAITING, TESTING, READY, NO_FOREIGN_ROUTE, BLOCKED, PAUSED }

    /** What happened to an app lately, shown on its row instead of a stack of toasts. */
    enum class NoteKind {
        /** A repair is running or waits for another app's check. */
        CHECKING,
        /** A different route now carries the app. */
        FIXED,
        /** Re-checked: the route still works, nothing needed changing. */
        VERIFIED,
        NOT_FOUND,
        /** The app needs a foreign exit and there is none to try: set one up first. */
        NO_FOREIGN_EXIT,
        /** Its route stopped carrying anything while connected; MAE healed it by itself. */
        HEALED,
        /** The network has no working internet yet (a Wi-Fi login page, a dead minute). */
        OFFLINE,
        /** Feedback needs MAE connected: it is about MAE's route. */
        NOT_CONNECTED,
    }

    data class Note(
        val kind: NoteKind,
        val at: Long,
        val level: Int = 0,
        val routeId: String? = null,
        /** What the phone says that the app checks (DeviceSignals), when it says Iran. */
        val blockers: List<String> = emptyList(),
        /** The app keeps connections open: it should be closed and opened again to use the new route. */
        val reopen: Boolean = false,
    )

    data class ServiceView(
        val def: ServiceDef,
        val phase: Phase,
        val routeId: String?,
        val routeKind: RouteKind?,
        val family: FamilyPolicy?,
        /** The exit's country, when it goes abroad and one was measured. */
        val country: String?,
        val primary: Diagnosis.Primary,
        val paused: Boolean,
        val pinned: Boolean,
        val note: Note?,
        /** What this app reads on the phone that says Iran right now (see [DeviceSignals]). */
        val blockers: List<String>,
    )

    /** "What's wrong?" -- asked from the second "didn't open"; nothing is committed until answered. */
    data class Ask(val serviceId: String, val level: Int, val previous: Symptom?)

    private lateinit var app: Context
    lateinit var store: MaeStore
        private set
    lateinit var registry: ServiceRegistry
        private set

    private val _ready = MutableStateFlow(false)
    /** True once [init] has read the registry and the state (off the main thread for the screen). */
    val ready: StateFlow<Boolean> = _ready

    private val breaker = CircuitBreaker()
    private fun bk(routeId: String, net: String) = CircuitBreaker.key(routeId, net)
    private val echoCache = EchoCache()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "background task failed: ${e.javaClass.simpleName}", e)
    })
    private val applyLock = Mutex()

    private val testing = MutableStateFlow<Set<String>>(emptySet())
    /** Apps being checked now, or waiting their turn after a "didn't open": both show as testing. */
    val testingFlow: StateFlow<Set<String>> = testing

    private val _notes = MutableStateFlow<Map<String, Note>>(emptyMap())
    val notes: StateFlow<Map<String, Note>> = _notes

    private val _ask = MutableStateFlow<Ask?>(null)
    /** Held here, not on the screen: a rotation or a trip to another page does not lose it. */
    val pendingAsk: StateFlow<Ask?> = _ask

    private val netFlow = MutableStateFlow("")

    /**
     * The rows the main screen shows, worked out off the main thread: the store changes many times
     * a second while apps are checked. Recomputed when the state, the checks, the notes or the
     * NETWORK change -- each network has its own decisions. Emits only when a row actually changed.
     */
    val viewsFlow: StateFlow<List<ServiceView>> by lazy {
        combine(store.state, testing, _notes, netFlow) { s, t, n, _ -> Triple(s, t, n) }
            .conflate()
            .map { (s, t, n) -> views(s, t, n) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            // Only while the screen is looking. Eagerly, every change to the state -- dozens a
            // second while an app is checked, in the background too -- rebuilt every row, reading
            // the network and the SIM for each, for a list nobody was showing. The last rows are
            // kept, so coming back shows them at once while they are brought up to date.
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    private data class Job2(val serviceId: String, val incident: Boolean)
    /** The user is waiting: "didn't open", a re-test. */
    private val urgent = Channel<Job2>(Channel.UNLIMITED)
    /** Apps that must not look Iranian, and re-checks the live check asked for. */
    private val soon = Channel<Job2>(Channel.UNLIMITED)
    private val queue = Channel<Job2>(Channel.UNLIMITED)
    /** Apps with a routine check queued (not twice). */
    private val queued = Collections.synchronizedSet(HashSet<String>())
    /** Apps with a repair queued or running (a second tap does not start a second). */
    private val incidentPending = Collections.synchronizedSet(HashSet<String>())
    /** Apps whose check waits for the network to have internet. */
    private val deferred = Collections.synchronizedSet(HashSet<String>())
    /** Apps whose routine re-check waits for the screen to come on; see [discover]. */
    private val waitingForScreen = Collections.synchronizedSet(HashSet<String>())
    /** When stale decisions were last swept for while connected ([REFRESH_EVERY_MS]). */
    @Volatile private var lastRefreshAt = 0L
    /** Elapsed realtime of the last passive stats read; 0 = none yet this session. */
    @Volatile private var lastStatsAt = 0L

    private var worker: Job? = null
    private var statsJob: Job? = null
    private var healthJob: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var screenReceiver: BroadcastReceiver? = null
    @Volatile private var screenOffAt = 0L
    @Volatile private var lastNet: String = ""
    /**
     * The user wants MAE connected. Checked right before every start: a reconnect scheduled a
     * moment before the user pressed Disconnect used to bring the tunnel back up.
     */
    @Volatile private var wantConnected = false

    fun init(context: Context) {
        synchronized(this) {
            if (this::app.isInitialized) return
            app = context.applicationContext
            registry = runCatching {
                ServiceRegistry.parse(app.assets.open("mae/services.json").bufferedReader().use { it.readText() })
            }.getOrElse {
                Log.e(TAG, "registry unreadable: ${it.javaClass.simpleName}")
                ServiceRegistry.parse("{\"schema\":1,\"services\":[]}")
            }
            store = MaeStore(app.filesDir)
        }
        adoptKnownApps()
        prune()
        com.mlmvpn.scanner.utils.ScreenState.watch(app)
        worker = scope.launch { runQueue() }
        netFlow.value = currentNet()
        observeSession()
        observeScreen()
        _ready.value = true
        // Whatever went stale while the app was closed is re-checked in the background, so the
        // list is current before the user taps Connect.
        scope.launch { refreshStale() }
    }

    /** [init] off the caller's thread: the screen must not read files while it draws. */
    suspend fun initInBackground(context: Context) = withContext(Dispatchers.IO) { init(context) }

    /**
     * An installed app added before the registry knew it (Google Flow's app is
     * `com.google.android.apps.labs.whisk`) becomes the registry's entry, so it gets that entry's
     * domains, probes and hints -- the US exit, the Google bundle -- instead of a guessed domain.
     * What was learned about the unknown app is dropped: it was learned about the wrong thing.
     */
    private fun adoptKnownApps() {
        val s = store.current
        val adopt = s.customApps.mapNotNull { (id, a) -> registry.services.firstOrNull { a.pkg in it.packages }?.let { id to it.id } }.toMap()
        if (adopt.isEmpty()) return
        Log.i(TAG, "known apps adopted: ${adopt.values}")
        store.update { st ->
            fun gone(k: String) = adopt.keys.any { k == it || k.startsWith("$it|") || k.contains("|$it|") }
            val selected = st.selected.map { sel -> adopt[sel.id]?.let { sel.copy(id = it) } ?: sel }.distinctBy { it.id }
            st.copy(
                selected = selected,
                customApps = st.customApps - adopt.keys,
                policies = st.policies.filterKeys { !gone(it) },
                diagnoses = st.diagnoses.filterKeys { !gone(it) },
                egress = st.egress.filterKeys { !gone(it) },
                proofs = st.proofs.filterKeys { !gone(it) },
                metrics = st.metrics.filterKeys { !gone(it) },
                repairs = st.repairs.filterKeys { !gone(it) },
                userEvidence = st.userEvidence.filterKeys { !gone(it) },
                siteHosts = st.siteHosts - adopt.keys,
            )
        }
    }

    /**
     * Forgets networks not seen for a month, numbers and proofs nobody refreshed in that time,
     * and keeps the incident log short. The state is rewritten whole on every change, so what
     * it carries is what every change costs.
     */
    private fun prune() {
        val now = System.currentTimeMillis()
        val cutoff = now - NET_FORGET_MS
        store.update { s ->
            // A state from before `netSeen`: every network it knows counts as seen now.
            val seen = if (s.netSeen.isEmpty() && s.policies.isNotEmpty()) s.policies.values.associate { it.netKey to now } else s.netSeen
            val stale = seen.filterValues { it < cutoff }.keys
            fun <T> Map<String, T>.dropNets() = if (stale.isEmpty()) this else filterKeys { k -> stale.none { n -> k.endsWith("|$n") } }
            val metrics = s.metrics.dropNets().filterValues { it.lastTestedAt == 0L || it.lastTestedAt >= cutoff }
            val proofs = s.proofs.dropNets().filterValues { it.at == 0L || it.at >= cutoff }
            if (stale.isEmpty() && seen === s.netSeen && metrics.size == s.metrics.size && proofs.size == s.proofs.size &&
                s.incidents.size <= com.mlmvpn.scanner.engines.mae.store.MaeStateCodec.MAX_INCIDENTS) return@update s
            if (stale.isNotEmpty()) Log.i(TAG, "forgot ${stale.size} network(s) unseen for a month")
            s.copy(
                netSeen = seen - stale,
                diagnoses = s.diagnoses.dropNets(), policies = s.policies.dropNets(), metrics = metrics, proofs = proofs,
                egress = s.egress.dropNets(), repairs = s.repairs.dropNets(), userEvidence = s.userEvidence.dropNets(),
                dnsPaths = s.dnsPaths - stale, warpEndpoint = s.warpEndpoint - stale, usExitVia = s.usExitVia - stale,
                incidents = s.incidents.filter { it.netKey !in stale }.takeLast(com.mlmvpn.scanner.engines.mae.store.MaeStateCodec.MAX_INCIDENTS),
            )
        }
    }

    private fun markSeen(net: String) {
        val now = System.currentTimeMillis()
        if (now - (store.current.netSeen[net] ?: 0L) < 3600_000L) return
        store.update { it.copy(netSeen = it.netSeen + (net to now)) }
    }

    // ---------------------------------------------------------------- services

    fun serviceDef(id: String): ServiceDef? {
        val s = store.current
        val base = registry.get(id) ?: when {
            id.startsWith("site:") -> ServiceRegistry.customSite(id.removePrefix("site:"))
            id.startsWith("app:") -> s.customApps[id]?.let { a -> ServiceRegistry.customApp(id, a.pkg, a.label, a.domain) }
            else -> null
        } ?: return null
        // Apps the user attached to this one (Google Maps under Google): their per-app rule too.
        val pkgs = s.extraPackages[id].orEmpty().filter { it !in base.packages }
        val withPkgs = if (pkgs.isEmpty()) base else base.copy(packages = base.packages + pkgs)
        return withHosts(s, id, withPkgs)
    }

    /**
     * An app or site also covers the domains its pages were seen loading from -- registry apps
     * too: their learning used to be fetched and then never used. Another picked app's own
     * domains stay with that app.
     */
    private fun withHosts(s: MaeState, id: String, base: ServiceDef): ServiceDef {
        val extra = s.siteHosts[id].orEmpty()
        if (extra.isEmpty()) return base
        val others = s.selected.asSequence().filter { it.id != id }.mapNotNull { registry.get(it.id) }.flatMap { it.domains.asSequence() }.toSet()
        val add = extra.filter { it !in others && it !in base.domains }
        return if (add.isEmpty()) base else base.copy(domains = base.domains + add)
    }

    sealed class AddApp {
        data class Added(val id: String) : AddApp()
        /** No domain could be found for it: nothing to route by yet. */
        object NoDomain : AddApp()
    }

    /**
     * Adds an app installed on the phone -- at once: the tap selects it immediately, with the most
     * likely main domain for its package. The domain is then confirmed in the background (all
     * guesses at the same time, the phone's own DNS first, DoH only as a fallback, 8 s at most)
     * and replaced if a better one answers. Measured on Irancell (2026-09-29): checking guesses
     * one by one over DoH took minutes, and the tap looked dead the whole time.
     */
    fun addInstalledApp(pkg: String, label: String): AddApp {
        registry.services.firstOrNull { pkg in it.packages }?.let { known ->
            setSelection(store.current.selected.map { it.id } + known.id)
            return AddApp.Added(known.id)
        }
        // Google's own apps (Maps, Gmail, Photos...) talk to Google's domains: the Google entry
        // covers them. Guessing used to make "Photos" a site called google.com, beside Google.
        // Picking one attaches its package to Google, so it is ticked and routed as Google.
        if (pkg.startsWith("com.google.android.")) registry.get("google")?.let { g ->
            store.update { s ->
                val have = s.extraPackages[g.id].orEmpty()
                if (pkg in have || pkg in g.packages) s else s.copy(extraPackages = s.extraPackages + (g.id to (have + pkg)))
            }
            setSelection(store.current.selected.map { it.id } + g.id)
            scope.launch { applyIfConnected() }
            return AddApp.Added(g.id)
        }
        // A guess that is another known app's domain would steal that app's traffic.
        val guesses = ServiceRegistry.domainGuesses(pkg).filter { registry.forHost(it) == null }
        val first = guesses.firstOrNull() ?: return AddApp.NoDomain
        val id = "app:$pkg"
        store.update { s ->
            s.copy(
                customApps = s.customApps + (id to (s.customApps[id] ?: CustomApp(pkg, label, first))),
                selected = if (s.selected.any { it.id == id }) s.selected else s.selected + SelectedService(id),
            )
        }
        scope.launch { confirmAppDomain(id, pkg, guesses) }
        return AddApp.Added(id)
    }

    private suspend fun confirmAppDomain(id: String, pkg: String, guesses: List<String>) {
        val prober = NetProber(app)
        val network = runCatching { GameNetwork.pick(app)?.network }.getOrNull()
        fun resolves(d: String): Boolean {
            val system = runCatching { (network?.getAllByName(d) ?: java.net.InetAddress.getAllByName(d)).map { it.hostAddress.orEmpty() } }
                .getOrDefault(emptyList())
            if (system.any { !com.mlmvpn.scanner.engines.mae.probe.DnsEvidence.isBogus(it) }) return true
            return prober.dohResolve(d, 1).isNotEmpty()
        }
        // Each guess answers on its own time (a lookup cannot be interrupted); they report here and
        // the first guess, in order of likelihood, that resolves wins as soon as every more likely
        // guess has answered no. The 8 s cap then really is a cap: a guess that hangs no longer
        // throws away the ones that answered.
        val results = Channel<Pair<Int, Boolean>>(Channel.UNLIMITED)
        guesses.forEachIndexed { i, d -> scope.launch { results.send(i to runCatching { resolves(d) }.getOrDefault(false)) } }
        val done = BooleanArray(guesses.size)
        val ok = BooleanArray(guesses.size)
        var found: Int? = null
        withTimeoutOrNull(8_000) {
            while (found == null) {
                val (i, r) = results.receive()
                done[i] = true; ok[i] = r
                val lead = guesses.indices.firstOrNull { !done[it] || ok[it] } ?: break
                if (done[lead] && ok[lead]) found = lead
            }
        }
        val pick = found ?: guesses.indices.firstOrNull { done[it] && ok[it] }
        val domain = pick?.let { guesses[it] } ?: guesses.first()
        store.update { s ->
            val cur = s.customApps[id] ?: return@update s
            if (cur.domain == domain) s
            else s.copy(customApps = s.customApps + (id to cur.copy(domain = domain)),
                // A different main domain: what was learned about the old guess does not apply.
                diagnoses = s.diagnoses.filterKeys { !it.startsWith("$id|") }, siteHosts = s.siteHosts - id)
        }
        Log.i(TAG, "app $pkg -> $domain${if (pick == null) " (unconfirmed)" else ""}")
        enqueue(id, priority = true)
        applyIfConnected()
    }

    /**
     * Reads a site's (or an app's website's) home page through the route that works for it and
     * adds the other domains the page loads from. Runs when the app is first checked and again on
     * a repair that asks for it, so a site that opened but did not finish loading repairs itself.
     */
    private suspend fun learnSiteHosts(def: ServiceDef, net: String, ports: Map<String, Int>, providers: List<RouteProvider>, incident: Boolean): Int {
        val policy = store.current.policies[MaeState.sk(def.id, net)] ?: return 0
        val p = providers.firstOrNull { it.id == policy.routeId } ?: return 0
        // Half a megabyte through a quota-limited exit is not spent on a routine check.
        if (p.caps.quotaLimited && !incident) return 0
        val route = if (p.kind == RouteKind.DIRECT) ProbeRoute(p.id, p.kind, policy.family)
            else ProbeRoute(p.id, p.kind, policy.family, ports[p.tag(policy.family)] ?: return 0)
        val site = registry.get(def.id)?.domains?.firstOrNull() ?: def.domains.firstOrNull() ?: return 0
        val html = NetProber(app).pageText(route, "https://$site/") ?: return 0
        // Domains that are another known app's (a page linking to its Instagram or YouTube) stay
        // with that app: they are not this site's content and must keep their own route.
        // Brands of other known apps (google, youtube, …): their country domains stay theirs.
        val brands = registry.services.filter { it.id != def.id }.flatMap { s -> s.domains.map { it.substringBefore('.') } }.toSet()
        val found = com.mlmvpn.scanner.engines.mae.registry.RelatedHosts.extract(html, site, knownBrands = brands)
            .filter { registry.forHost(it) == null }
        if (found.isEmpty()) return 0
        val before = store.current.siteHosts[def.id].orEmpty()
        // The page as it is now is the truth: a fresh read replaces the old list, so a wrong
        // domain learned earlier does not stay forever.
        val merged = found.distinct().take(40)
        store.update { it.copy(siteHosts = it.siteHosts + (def.id to merged)) }
        val added = merged.count { it !in before }
        Log.i(TAG, "site ${def.id}: ${merged.size} related domain(s), $added new")
        return added
    }

    fun selectedDefs(state: MaeState = store.current): List<ServiceDef> =
        state.selected.mapNotNull { serviceDef(it.id) }

    fun views(state: MaeState, testingIds: Set<String>, notes: Map<String, Note> = _notes.value): List<ServiceView> {
        val net = currentNet()
        val providers = providersCached(state, net)
        val kinds = providers.associate { it.id to it.kind }
        val byId = providers.associateBy { it.id }
        return state.selected.mapNotNull { sel ->
            val def = serviceDef(sel.id) ?: return@mapNotNull null
            val key = MaeState.sk(def.id, net)
            val d = state.diagnoses[key] ?: Diagnosis()
            val policy = state.policies[key]
            val egress = state.egress[key]
            val kind = policy?.let { kinds[it.routeId] }
            val geo = d.primary in setOf(Diagnosis.Primary.GEO_RESTRICTED, Diagnosis.Primary.CENSORED_AND_GEO)
            // An exit abroad recorded for an app that may no longer use it is no exit: Gemini kept
            // «found: the Worker» from before the fixed-exit rule (PolicyEngine.fixedExitAbroad), so on
            // the phone (2026-10-20) it read «no working route yet» with nothing to set up, while Google
            // Flow, measured again since, read «no working foreign route yet» and named FENIX and Proton.
            val looseExit = def.hints.usExit && egress is ForeignEgressStatus.Proven &&
                byId[egress.routeId]?.let(PolicyEngine::fixedExitAbroad) != true
            val phase = when {
                sel.paused -> Phase.PAUSED
                def.id in testingIds -> Phase.TESTING
                egress is ForeignEgressStatus.NoneFound && geo -> Phase.NO_FOREIGN_ROUTE
                policy != null && proven(state, def.id, net, policy) -> Phase.READY
                looseExit && geo -> Phase.NO_FOREIGN_ROUTE
                d.axes.isNotEmpty() -> Phase.BLOCKED
                else -> Phase.WAITING
            }
            val country = if (kind == RouteKind.FOREIGN && policy != null) {
                state.proofs["${policy.routeId}:${policy.family}|${def.id}|$net"]?.country
                    ?: state.proofs.entries.firstOrNull { (k, p) -> k.startsWith("${policy.routeId}:") && k.endsWith("|${def.id}|$net") && p.serviceAccepted }?.value?.country
            } else null
            ServiceView(def, phase, policy?.routeId, kind, policy?.family, country, d.primary, sel.paused, policy?.pinned == true,
                notes[def.id], runCatching { DeviceSignals.iranian(app, def.hints.clientChecks) }.getOrDefault(emptyList()))
        }
    }

    /** The chosen route really served this service on this network, on the chosen family. */
    private fun proven(state: MaeState, id: String, net: String, p: ServicePolicy): Boolean {
        val m = state.metrics[MaeState.rk(p.routeId, id, net)] ?: return false
        return m.lastTestedAt > 0 && m.lastOkAt == m.lastTestedAt
    }

    fun setSelection(ids: List<String>) {
        val had = store.current.selected.map { it.id }.toSet()
        store.update { s ->
            val keep = s.selected.associateBy { it.id }
            s.copy(onboarded = true, selected = ids.distinct().map { keep[it] ?: SelectedService(it) })
        }
        // Only the apps just picked are checked, first. Every app used to be queued again on each
        // pick, so adding one app looked like the whole list starting over.
        val added = ids.distinct().filter { it !in had }
        added.forEach { enqueue(it, priority = true) }
        if (added.isNotEmpty() || had.size != ids.distinct().size) scope.launch { applyIfConnected() }
    }

    /**
     * Unticks one of the phone's apps. One attached to another app (Google Maps under Google) is
     * just detached; otherwise the app it belongs to is removed with everything learned.
     */
    fun detachPackage(pkg: String): Boolean {
        val owner = store.current.extraPackages.entries.firstOrNull { pkg in it.value }?.key ?: return false
        store.update { s ->
            val left = s.extraPackages[owner].orEmpty() - pkg
            s.copy(extraPackages = if (left.isEmpty()) s.extraPackages - owner else s.extraPackages + (owner to left))
        }
        scope.launch { applyIfConnected() }
        return true
    }

    /** The user asked for this app to be checked again now: shown as checking at once. */
    fun recheck(id: String) = enqueue(id, incident = true)

    /**
     * Every app checked again, as routine checks: shown as checking at once, the new decisions
     * applied together once they are all done -- one reconnect at most, not one per app.
     */
    fun recheckAll() {
        val net = currentNet()
        val ids = store.current.selected.filterNot { it.paused }.map { it.id }
        if (ids.isEmpty()) return
        store.update { s ->
            s.copy(policies = s.policies.mapValues { (k, p) ->
                if (p.netKey == net && p.serviceId in ids && !p.pinned) p.copy(decidedAt = 0) else p
            })
        }
        testing.update { it + ids }
        ids.forEach { enqueue(it, priority = true) }
    }

    /**
     * While the user is picking apps nothing is applied: every pick used to rebuild the tunnel, so
     * the VPN key blinked on each tap and the list under the finger reset. Released when the
     * picker closes; what changed is then applied once, after the new apps' checks.
     */
    fun holdApply(hold: Boolean) {
        applyHeld = hold
        if (!hold && applyPending) {
            applyPending = false
            scope.launch { applyIfConnected() }
        }
    }

    @Volatile private var applyHeld = false
    @Volatile private var applyPending = false

    /** Whether a Cloudflare account is connected: MAE's foreign exits all live on it. */
    fun hasCloudAccount(): Boolean = runCatching { CloudManager(app).loadedAccounts().isNotEmpty() }.getOrDefault(false)

    /**
     * Adds a site. Returns the id it was added as -- the known app's own id when the site is a
     * known app's (youtube.com is YouTube, not a second copy of its domains) -- or null when the
     * text is not a domain.
     */
    fun addSite(input: String): String? {
        val def = ServiceRegistry.customSite(input) ?: return null
        registry.forHost(def.displayName)?.let { known ->
            if (store.current.selected.none { it.id == known.id }) setSelection(store.current.selected.map { it.id } + known.id)
            return known.id
        }
        store.update { s ->
            if (s.selected.any { it.id == def.id }) s
            else s.copy(selected = s.selected + SelectedService(def.id), customSites = (s.customSites + def.displayName).distinct())
        }
        enqueue(def.id)
        return def.id
    }

    /** Removes the app and everything learned about it: MAE keeps nothing about apps the user dropped. */
    fun remove(id: String) {
        store.update { s ->
            fun <T> Map<String, T>.drop() = filterKeys { k -> k.split('|').none { it == id } }
            s.copy(
                selected = s.selected.filterNot { it.id == id },
                customSites = s.customSites.filterNot { "site:$it" == id },
                diagnoses = s.diagnoses.drop(), policies = s.policies.drop(), metrics = s.metrics.drop(),
                proofs = s.proofs.drop(), egress = s.egress.drop(), siteHosts = s.siteHosts - id, customApps = s.customApps - id,
                repairs = s.repairs.drop(), userEvidence = s.userEvidence.drop(),
                incidents = s.incidents.filterNot { it.serviceId == id }, extraPackages = s.extraPackages - id,
            )
        }
        _notes.update { it - id }
        if (_ask.value?.serviceId == id) _ask.value = null
        scope.launch { applyIfConnected(urgent = true) }
    }

    fun setPaused(id: String, paused: Boolean) {
        store.update { s -> s.copy(selected = s.selected.map { if (it.id == id) it.copy(paused = paused) else it }) }
        // Back from a pause: what it knows may be stale by now.
        if (!paused) enqueue(id)
        scope.launch { applyIfConnected(urgent = true) }
    }

    /**
     * Pins [routeId] for this app on this network, on the family that route proved for it; null
     * is "Auto" again -- decided afresh now, not hours later when the pinned choice goes stale.
     */
    fun pin(id: String, routeId: String?) {
        val net = currentNet()
        val now = System.currentTimeMillis()
        store.update { s ->
            val key = MaeState.sk(id, net)
            val p = s.policies[key]
            val next = if (routeId == null) p?.copy(pinned = false, decidedAt = 0)
            else {
                val def = serviceDef(id)
                val family = def?.let { d ->
                    PolicyEngine.candidates(s, d, net, providers(s, net)) { breaker.health(bk(it, net), now) }
                        .firstOrNull { it.routeId == routeId }?.let { RouteScorer.familyFor(it) }
                } ?: FamilyPolicy.BOTH
                (p ?: ServicePolicy(id, net, routeId)).copy(routeId = routeId, family = family, pinned = true, why = "pinned by user", decidedAt = now)
            }
            if (next == null) s else s.copy(policies = s.policies + (key to next))
        }
        if (routeId == null) enqueue(id)
        scope.launch { applyIfConnected(urgent = true) }
    }

    // ---------------------------------------------------------------- network

    /**
     * The current network's key. Working it out takes several system calls (every network, its
     * capabilities, the carrier or Wi-Fi), and the screen asks on every redraw: the answer is kept
     * for [NET_CACHE_MS] and dropped at once when the network changes.
     */
    fun currentNet(): String {
        val now = System.currentTimeMillis()
        netCache?.let { (key, at) -> if (now - at < NET_CACHE_MS) return key }
        val key = runCatching { NetworkKey.current(app).key }.getOrDefault(NetworkKey.UNKNOWN).ifEmpty { "unknown" }
        netCache = key to now
        return key
    }

    @Volatile private var netCache: Pair<String, Long>? = null

    private fun onCellular(): Boolean = runCatching { NetworkKey.current(app).isCellular }.getOrDefault(false)

    /**
     * Whether this network really reaches the internet. Android's own validation first; where it
     * failed (Google's check can be slow or filtered here), two neutral addresses are tried directly.
     * A captive portal is offline until the user signs in.
     */
    private fun online(): Boolean {
        val picked = runCatching { GameNetwork.pick(app) }.getOrNull() ?: return false
        val caps = picked.caps
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) return false
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return true
        return listOf("8.8.8.8", "1.1.1.1").any { ip ->
            runCatching {
                (picked.network.socketFactory.createSocket()).use { it.connect(java.net.InetSocketAddress(ip, 443), 1_500); true }
            }.getOrDefault(false)
        }
    }

    // ---------------------------------------------------------------- providers

    fun providers(state: MaeState = store.current, net: String = currentNet()): List<RouteProvider> = buildList {
        add(DirectRoute); add(ServerlessRoute); add(FragmentRoute)
        state.warp?.let { w -> MaeWarp.route(w, state.warpEndpoint[net] ?: 0)?.let { add(it) } }
        workerRoute(state)?.let { add(it) }
        addAll(userConfigRoutes(state, net) + cloudConfigRoutes(state, net))
        // The old US helper (a Durable Object, the «خروجی آمریکا» of Settings) is no exit of MAE's any
        // more: it misbehaved for the user (2026-10-18). Gemini and Google Flow are served by the fixed
        // exits abroad below -- FENIX (the US one too), the user's Outline servers, configs, FLUX.
        // FLUX's proven exits on this network, read from its store (FLUX does not probe for MAE).
        addAll(com.mlmvpn.scanner.engines.flux.FluxRoute.forNet(app, net))
        // FENIX: the user's Firefox account's exit, when they are signed in (see FenixRoute), and its
        // American server beside it when the chosen country is another.
        addAll(runCatching { com.mlmvpn.scanner.engines.fenix.FenixEngine.maeRoutes(app) }.getOrDefault(emptyList()))
        // Outline: the user's servers that worked on this network (see OutlineMae).
        addAll(outlineRoutes())
        // Proton and Windscribe: WireGuard servers of the user's accounts (see AmzMae).
        addAll(com.mlmvpn.scanner.engines.amnezia.AmzMae.routes(app))
        // Lantern: its own core beside MAE's tunnel, while it is up (see LanternMae).
        runCatching { com.mlmvpn.core.lantern.LanternMae.route(app) }.getOrNull()?.let { add(it) }
        // URnetwork: its own core (a device of its own in the user's account) beside MAE's tunnel,
        // once providers joined its window (see UrnetworkMae).
        runCatching { com.mlmvpn.core.urnetwork.UrnetworkMae.route(app) }.getOrNull()?.let { add(it) }
    }

    /**
     * MAE's Worker exit: the account's central Worker -- the one every panel rides, BPB's own id on it --
     * so MAE makes no Worker of its own. A MAE Worker made before is used only while the account has
     * no central one.
     */
    private fun workerRoute(state: MaeState): WorkerRoute? {
        com.mlmvpn.scanner.engines.gateway.EdgeGateway.anyExit(app)?.let { gw ->
            return WorkerRoute(gw.host, gw.uuid, gw.wsPath, reachesCloudflare = gw.fallback.isNotBlank())
        }
        val w = state.worker ?: return null
        return MaeEgressDeployer.uuidOf(w)?.let { WorkerRoute(w.url, it) }
    }

    /** What the Worker exit is now, to tell when it changed: the central Worker, or MAE's own. */
    private fun workerExitKey(state: MaeState): String =
        com.mlmvpn.scanner.engines.gateway.EdgeGateway.anyExit(app)?.let { "gw:${it.host}:${it.uuid}:${it.wsPath}" }
            ?: state.worker?.let { w -> "mae:${w.url}:${MaeEgressDeployer.uuidOf(w)}" }.orEmpty()

    /**
     * A different Worker behind the same route id invalidates what was proven through the old one:
     * those verdicts are forgotten and every app is checked again -- once, when the exit changed
     * (moving onto the central Worker, or its id renewed), never on an ordinary sweep.
     */
    private fun syncWorkerExit() {
        val prefs = app.getSharedPreferences("mae_exit", Context.MODE_PRIVATE)
        val key = workerExitKey(store.current)
        val was = prefs.getString("worker", null)
        if (was == key) return
        prefs.edit().putString("worker", key).apply()
        // The first look after an update only records where things stand -- unless that is already the
        // central Worker, which is a new exit for whoever used MAE's own.
        if (was == null && !key.startsWith("gw:")) return
        store.update { s ->
            s.copy(
                egress = s.egress.filterValues { it !is ForeignEgressStatus.NoneFound && !(it is ForeignEgressStatus.Proven && it.routeId == WorkerRoute.ID) },
                proofs = s.proofs.filterKeys { !it.startsWith("${WorkerRoute.ID}:") },
                metrics = s.metrics.filterKeys { !it.startsWith("${WorkerRoute.ID}|") },
                policies = s.policies.mapValues { (_, p) -> p.copy(decidedAt = 0) },
            )
        }
        store.current.selected.forEach { enqueue(it.id) }
        scope.launch { applyIfConnected() }
    }

    /**
     * The user switched «استفاده در MAE» on Lantern's screen: its core starts or stops at once when
     * MAE is the tunnel, and the tunnel follows; otherwise the next session reads the switch.
     */
    fun lanternForMaeChanged() {
        if (!this::app.isInitialized || !isConnected()) return
        if (com.mlmvpn.scanner.ui.lantern.LanternSettings.forMae(app)) startLantern() else com.mlmvpn.core.lantern.LanternMae.stop()
        scope.launch { applyIfConnected() }
    }

    /**
     * The user switched «استفاده در MAE» on URnetwork's screen: its core starts or stops at once when
     * MAE is the tunnel, and the tunnel follows; otherwise the next session reads the switch.
     */
    fun urnetworkForMaeChanged() {
        if (!this::app.isInitialized || !isConnected()) return
        if (com.mlmvpn.core.urnetwork.UrnetworkSettings.forMae(app)) {
            com.mlmvpn.core.urnetwork.UrnetworkMae.start(app) { scope.launch { applyIfConnected() } }
        } else com.mlmvpn.core.urnetwork.UrnetworkMae.stop()
        scope.launch { applyIfConnected() }
    }

    /**
     * Lantern's and URnetwork's cores for this session; when a route appears or goes, the tunnel is
     * brought up to date.
     */
    private fun startLantern() {
        lanternStop?.cancel()
        lanternStop = null
        com.mlmvpn.core.lantern.LanternMae.start(app) { scope.launch { applyIfConnected() } }
        com.mlmvpn.core.urnetwork.UrnetworkMae.start(app) { scope.launch { applyIfConnected() } }
    }

    /**
     * MAE's own reconnects (a route found dead, a rule change) take the session down for a few
     * seconds: the core is kept through them -- the next tunnel is compiled with it, on the same port --
     * and stopped once MAE is down for good, or at once when the user disconnected.
     */
    @Volatile private var lanternStop: Job? = null

    private fun stopLanternSoon() {
        lanternStop?.cancel()
        lanternStop = scope.launch {
            if (wantConnected) delay(LANTERN_KEEP_MS)
            if (!isConnected()) {
                com.mlmvpn.core.lantern.LanternMae.stop()
                com.mlmvpn.core.urnetwork.UrnetworkMae.stop()
            }
        }
    }

    /** [providers] for the screen, which asks on every change: kept a few seconds. */
    @Volatile private var providersCache: Triple<MaeState, String, List<RouteProvider>>? = null
    private fun providersCached(state: MaeState, net: String): List<RouteProvider> {
        providersCache?.let { (s, n, p) -> if (s === state && n == net) return p }
        return providers(state, net).also { providersCache = Triple(state, net, it) }
    }

    /**
     * The routes the PROBE core carries. WARP's session is one per key: the probe core uses MAE's
     * second identity, so a check never knocks the tunnel's WARP session over. Without a second
     * identity, WARP is left to the live check while the tunnel is up.
     */
    private fun probeProviders(state: MaeState, net: String): List<RouteProvider> {
        // A Proton or Windscribe server is one WireGuard session per key, like WARP: while the tunnel
        // carries it, a probe of the same server would take the session from it.
        val all = providers(state, net).let { p ->
            if (isConnected()) p.filterNot { com.mlmvpn.scanner.engines.amnezia.WgExitRoute.isWgExitRoute(it.id) } else p
        }
        val probeWarp = state.warpProbe?.let { MaeWarp.route(it, state.warpEndpoint[net] ?: 0) }
        return when {
            probeWarp != null -> all.map { if (it.id == WarpRoute.ID) probeWarp else it }
            isConnected() -> all.filterNot { it.id == WarpRoute.ID }
            else -> all
        }
    }

    /** Outbounds built from config links, by route id (a link does not change under its id). */
    private val outboundCache = HashMap<String, JSONObject?>()

    private fun outboundFor(id: String, uri: String): JSONObject? = synchronized(outboundCache) {
        outboundCache.getOrPut(id) {
            runCatching {
                val cfg = com.mlmvpn.scanner.utils.VpnConfig.parseUri(uri) ?: return@runCatching null
                val full = JSONObject(com.mlmvpn.scanner.utils.XrayJsonGenerator.generateConfig(cfg, 10808, includeTun = false))
                val outs = full.getJSONArray("outbounds")
                (0 until outs.length()).map { outs.getJSONObject(it) }.firstOrNull { it.optString("tag") == "proxy" }
                    ?.takeIf { !it.toString().contains("dialerProxy") }
            }.getOrNull()
        }
    }

    /**
     * A config that failed every app it was tried for on this network, twice or more in a row,
     * and has not worked here for a day: the window moves past it.
     */
    private fun configDead(state: MaeState, id: String, net: String): Boolean {
        val here = state.metrics.filterKeys { it.startsWith("$id|") && it.endsWith("|$net") }.values
        if (here.isEmpty()) return breaker.health(bk(id, net), System.currentTimeMillis()) == Health.UNAVAILABLE
        val dayAgo = System.currentTimeMillis() - 24 * 3600_000L
        return here.none { it.lastOkAt > dayAgo } && here.all { it.consecutiveFailures >= 2 }
    }

    /**
     * The window of candidates MAE probes: configs that proved themselves on this network first
     * (most recent first), then untried ones in the order given, never one that is dead here --
     * so the window is sticky, and moves on only past configs that stopped working.
     */
    private fun window(state: MaeState, net: String, ids: List<String>): List<String> {
        fun lastOk(id: String) = state.metrics.filterKeys { it.startsWith("$id|") && it.endsWith("|$net") }.values.maxOfOrNull { it.lastOkAt } ?: 0L
        val alive = ids.filterNot { configDead(state, it, net) }
        val proven = alive.filter { lastOk(it) > 0 }.sortedByDescending { lastOk(it) }
        val fresh = alive.filter { lastOk(it) == 0L }
        return (proven + fresh).take(UserConfigRoute.MAX).ifEmpty { ids.take(UserConfigRoute.MAX) }
    }

    /**
     * A few of the user's own saved configs as foreign-exit candidates: VLESS/Trojan links only,
     * not the Iranian / SNI / domain-fronting groups (those do not exit abroad), lowest measured
     * delay first -- each under an id of its own, whatever the list's order.
     */
    private fun userConfigRoutes(state: MaeState, net: String): List<RouteProvider> {
        val nodes = runCatching { com.mlmvpn.scanner.data.NodeManager(app).nodesFlow.value }.getOrDefault(emptyList())
        val eligible = nodes.filter { n ->
            (n.uri.startsWith("vless://") || n.uri.startsWith("trojan://")) &&
                !com.mlmvpn.scanner.data.NodeManager.isProtected(n) &&
                n.groupTitle?.let { g -> g == com.mlmvpn.scanner.data.NodeManager.IRAN_GROUP || g.contains("sni", true) || g.contains("فرانتینگ") } != true
        }.sortedBy { n -> Regex("[0-9]+").find(n.delay)?.value?.toIntOrNull()?.takeIf { it > 0 } ?: Int.MAX_VALUE }
        if (eligible.isEmpty()) return emptyList()
        val byId = eligible.associateBy { UserConfigRoute.idFor(it.id) }
        return window(state, net, byId.keys.toList()).mapNotNull { id ->
            val n = byId.getValue(id)
            outboundFor(id, n.uri)?.let { UserConfigRoute(id, it, n.name) }
        }
    }

    /** The user's Outline servers that worked here, each as a Shadowsocks outbound (see OutlineMae). */
    private fun outlineRoutes(): List<RouteProvider> = runCatching {
        com.mlmvpn.scanner.engines.outline.OutlineMae.servers(app).mapNotNull { s ->
            val id = com.mlmvpn.scanner.engines.outline.OutlineRoute.idFor(s.id)
            outboundFor(id, s.uri)?.let { com.mlmvpn.scanner.engines.outline.OutlineRoute(id, it, s.country) }
        }
    }.getOrDefault(emptyList())

    @Volatile private var cloudOpened: Pair<List<String>, List<String>>? = null

    /**
     * Configs from the user's own Cloudflare panels ([MaeCloudConfigs]), each on a clean IPv6 edge
     * first and on IPv4 as the fallback -- the variants are candidates like any other exit, and
     * the fastest one an app accepts wins.
     */
    private fun cloudConfigRoutes(state: MaeState, net: String): List<RouteProvider> {
        if (state.cloudLinks.isEmpty()) return emptyList()
        val links = cloudOpened?.takeIf { it.first === state.cloudLinks }?.second
            ?: state.cloudLinks.mapNotNull { SecureStore.open(it) }.also { cloudOpened = state.cloudLinks to it }
        if (links.isEmpty()) return emptyList()
        val variants = MaeCloudConfigs.variants(links, MaeCloudConfigs.edges(app, net))
        val byId = variants.associateBy { UserConfigRoute.idFor(it.substringBefore('#'), UserConfigRoute.CLOUD_PREFIX) }
        return window(state, net, byId.keys.toList()).mapNotNull { id ->
            outboundFor(id, byId.getValue(id))?.let { UserConfigRoute(id, it, "cloud") }
        }
    }

    /**
     * Reads the user's panels' configs when an app that may need a foreign exit is checked and the
     * copy is old or missing. A failed read waits an hour; nothing is read for apps that stay local.
     */
    private suspend fun ensureCloudConfigs() {
        val s = store.current
        val now = System.currentTimeMillis()
        val fresh = s.cloudLinks.isNotEmpty() && now - s.cloudLinksAt < MaeCloudConfigs.REFRESH_MS
        if (fresh || now - s.cloudLinksFailedAt < MaeCloudConfigs.RETRY_MS) return
        if (CloudManager(app).accounts.isEmpty()) return
        val links = withTimeoutOrNull(90_000) { runCatching { MaeCloudConfigs.fetch(app) }.getOrNull() }.orEmpty()
        val sealed = links.mapNotNull { SecureStore.seal(it) }
        store.update { st ->
            if (sealed.isEmpty()) st.copy(cloudLinksFailedAt = now)
            else st.copy(cloudLinks = sealed, cloudLinksAt = now, cloudLinksFailedAt = 0L)
        }
        Log.i(TAG, "cloud configs: ${sealed.size} from the user's panels")
    }

    /**
     * Makes MAE's WARP identities the first time they are needed: the tunnel's, and a second one
     * for the probe core. Failure is remembered and retried after [MaeWarp.RETRY_MS].
     */
    private suspend fun ensureWarp() {
        val now = System.currentTimeMillis()
        val s = store.current
        if (s.warp == null && now - s.warpFailedAt >= MaeWarp.RETRY_MS) {
            runCatching { MaeWarp.register(app) }
                .onSuccess { w -> Log.i(TAG, "WARP identity made (${w.via})"); store.update { it.copy(warp = w) } }
                .onFailure { e -> Log.w(TAG, "WARP identity not made: ${e.javaClass.simpleName}"); store.update { it.copy(warpFailedAt = now) } }
        }
        val t = store.current
        if (t.warp != null && t.warpProbe == null && now - t.warpProbeFailedAt >= MaeWarp.RETRY_MS) {
            runCatching { MaeWarp.register(app) }
                .onSuccess { w -> Log.i(TAG, "WARP probe identity made (${w.via})"); store.update { it.copy(warpProbe = w) } }
                .onFailure { e -> Log.w(TAG, "WARP probe identity not made: ${e.javaClass.simpleName}"); store.update { it.copy(warpProbeFailedAt = now) } }
        }
    }

    fun routeLabel(routeId: String?): String = routeId ?: "—"

    // ---------------------------------------------------------------- discovery queue

    /**
     * Queue a service for discovery. [incident]: the user is waiting (it goes first, always runs,
     * and shows as testing at once). [priority]: ahead of routine checks -- apps that must not
     * look Iranian always are.
     */
    fun enqueue(serviceId: String, incident: Boolean = false, priority: Boolean = false) {
        if (incident) {
            if (!incidentPending.add(serviceId)) return
            // Shown at once, not when the queue gets to it: a tap must never look ignored.
            testing.update { it + serviceId }
            note(serviceId, NoteKind.CHECKING)
            urgent.trySend(Job2(serviceId, true))
            return
        }
        if (!queued.add(serviceId)) return
        val h = registry.get(serviceId)?.hints
        val early = priority || h?.failMode == FailMode.CLOSED || h?.requiresForeign == true || h?.likelyGeoRestricted == true
        (if (early) soon else queue).trySend(Job2(serviceId, false))
    }

    /**
     * Gemini and Google Flow on an exit that is not one fixed server abroad -- decided before that rule
     * (Lantern, the Worker, the old US helper, a panel config) -- are decided again at once: they looked
     * open there and were refused at the first prompt.
     */
    private fun dropLooseUsExits() {
        fun fixedAbroad(id: String) = com.mlmvpn.scanner.engines.fenix.FenixRoute.isFenixRoute(id) ||
            com.mlmvpn.scanner.engines.outline.OutlineRoute.isOutlineRoute(id) ||
            com.mlmvpn.scanner.engines.amnezia.WgExitRoute.isWgExitRoute(id) ||
            id.startsWith(UserConfigRoute.PREFIX) || com.mlmvpn.scanner.engines.flux.FluxRoute.isFluxRoute(id)
        val loose = store.current.policies.filterValues { p ->
            !p.pinned && p.decidedAt > 0 && serviceDef(p.serviceId)?.hints?.usExit == true && !fixedAbroad(p.routeId)
        }.keys
        if (loose.isEmpty()) return
        store.update { s -> s.copy(policies = s.policies.mapValues { (k, p) -> if (k in loose) p.copy(decidedAt = 0) else p }) }
    }

    /** Re-verify every stale service; called on connect, on network change and while connected. */
    fun refreshStale() {
        syncWorkerExit()
        dropLooseUsExits()
        val net = currentNet()
        val now = System.currentTimeMillis()
        val s = store.current
        s.selected.filterNot { it.paused }.forEach { sel ->
            if (PolicyEngine.isStale(s.policies[MaeState.sk(sel.id, net)], now)) enqueue(sel.id)
        }
    }

    private suspend fun runQueue() {
        suspend fun next(): Job2 = select {
            urgent.onReceive { it }
            soon.onReceive { it }
            queue.onReceive { it }
        }
        while (scope.isActive) {
            // The 60 s timeout only exists to put the probe core down once the queue is quiet.
            // With no core running there is nothing to put down, so the loop sleeps until there
            // is work instead of waking every minute for the life of the process.
            val job = urgent.tryReceive().getOrNull() ?: soon.tryReceive().getOrNull() ?: queue.tryReceive().getOrNull()
                ?: if (probeCore != null) withTimeoutOrNull(60_000) { next() } else next()
            if (job == null) { stopProbeCore(); continue }
            if (!job.incident) queued.remove(job.serviceId)
            runCatching { discover(job) }.onFailure {
                Log.w(TAG, "discovery ${job.serviceId} failed: ${it.javaClass.simpleName}")
                // A repair the user waits on always ends with an answer, even this one.
                if (job.incident) note(job.serviceId, NoteKind.NOT_FOUND, level = repairLevel(job.serviceId))
            }
        }
    }

    private var probeCore: VlessXrayInjector? = null
    private var probePorts: Map<String, Int> = emptyMap()
    private var probeProvidersKey: String = ""

    private suspend fun ensureProbeCore(providers: List<RouteProvider>, dns: DnsPath?): Map<String, Int> {
        // Keyed on the outbounds themselves, so a changed WARP endpoint or Worker rebuilds the core.
        val key = providers.joinToString { it.id + ":" + it.outbounds().toString().hashCode() } + "#" + dns?.url + "|" + dns?.via
        if (probeCore != null && key == probeProvidersKey) return probePorts
        stopProbeCore()
        // New outbounds: what their exits echoed before may not hold for these.
        echoCache.clear()
        val tags = providers.filter { it.kind != RouteKind.DIRECT }.flatMap { p -> p.families.map { p.tag(it) } }.distinct()
        val ports = tags.associateWith { freePort() }
        val cfg = MaeConfigCompiler.compileProbe(base(), providers, ports, dns)
        // In MAE's own host process: a panic there (MAE's WARP and Worker outbounds) used to close
        // the app on opening MAE, and it must not take a test's core down with it either.
        val core = VlessXrayInjector(0, com.mlmvpn.core.warp.XrayHosts.MAE)
        if (!core.start(app, cfg, 0)) error("probe core did not start")
        probeCore = core; probePorts = ports; probeProvidersKey = key
        return ports
    }

    /**
     * The tunnel's own DNS path for this network: the base's choice if it works, otherwise the
     * first DoH-by-IP that answers directly, otherwise one through a fragment. Chosen once per
     * network and re-checked after [DNS_TTL_MS].
     */
    private suspend fun dnsPathFor(net: String, providers: List<RouteProvider>): DnsPath {
        val now = System.currentTimeMillis()
        store.current.dnsPaths[net]?.takeIf { now - it.at < DNS_TTL_MS }?.let { return it }
        val prober = NetProber(app)
        val ports = ensureProbeCore(providers, null)
        val direct = ProbeRoute(DirectRoute.id, RouteKind.DIRECT)
        fun via(tag: String) = ports[tag]?.let { ProbeRoute(tag, RouteKind.BYPASS, socksPort = it) }
        val candidates = listOfNotNull(
            via(ServerlessRoute.TAG)?.let { Triple(DnsPath.BASE, "https://cloudflare-dns.com/dns-query", it) },
            Triple(DnsPath("https://8.8.8.8/dns-query", DirectRoute.TAG), "https://8.8.8.8/dns-query", direct),
            Triple(DnsPath("https://1.1.1.1/dns-query", DirectRoute.TAG), "https://1.1.1.1/dns-query", direct),
            via(ServerlessRoute.TAG)?.let { Triple(DnsPath("https://8.8.8.8/dns-query", ServerlessRoute.TAG), "https://8.8.8.8/dns-query", it) },
            via(FragmentRoute.TAG)?.let { Triple(DnsPath("https://1.1.1.1/dns-query", FragmentRoute.TAG), "https://1.1.1.1/dns-query", it) },
        )
        val works = kotlinx.coroutines.coroutineScope {
            candidates.map { (path, url, route) -> async { path to prober.dohWorks(url, route) } }.map { it.await() }
        }
        val chosen = (works.firstOrNull { it.second }?.first ?: DnsPath.BASE).copy(at = now)
        Log.i(TAG, "dns path for this network: ${chosen.url ?: "base"} via ${chosen.via} (${works.count { it.second }}/${works.size} worked)")
        store.update { it.copy(dnsPaths = it.dnsPaths + (net to chosen)) }
        return chosen
    }

    private fun stopProbeCore() {
        probeCore?.let { runCatching { it.stop() } }
        probeCore = null; probePorts = emptyMap(); probeProvidersKey = ""
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** Where this network sends what MAE knows nothing about: the fallback for a fail-open app. */
    private fun fallbackRouteFor(state: MaeState, net: String) =
        if (defaultVia(state, net) == DirectRoute.TAG) DirectRoute.id else ServerlessRoute.id

    private suspend fun discover(job: Job2) {
        val def = serviceDef(job.serviceId) ?: run { finish(job); return }
        val state0 = store.current
        if (state0.selected.none { it.id == def.id && !it.paused }) { finish(job); return }
        val net = currentNet()
        val now = System.currentTimeMillis()
        val key = MaeState.sk(def.id, net)
        if (!job.incident && !PolicyEngine.isStale(state0.policies[key], now) && state0.diagnoses.containsKey(key)) return
        // A routine re-check of a decision this network already has waits for the screen. It is a
        // second Xray core, a few dozen connections and up to two megabytes of download per app,
        // and it used to run every few hours per app around the clock -- in the pocket, all night,
        // for decisions nobody was about to use. The app keeps its route meanwhile; the check runs
        // the moment the screen comes on. An app this network has never seen is still learned at
        // once, screen or not: it has no route of its own until then.
        if (!job.incident && state0.diagnoses.containsKey(key) && !com.mlmvpn.scanner.utils.ScreenState.isOn(app)) {
            waitingForScreen += def.id
            // "Check all" marks every row as checking up front; the phone locked right after it
            // must not leave them spinning until the screen comes back.
            finish(job)
            return
        }
        // No internet here (a Wi-Fi login page, a dead minute): nothing is learned from that --
        // every route would "fail" and every app would be blocked. Checked again once it is back.
        if (!online()) {
            deferred += def.id
            if (job.incident) note(def.id, NoteKind.OFFLINE)
            finish(job)
            return
        }

        testing.update { it + def.id }
        try {
            markSeen(net)
            ensureWarp()
            val before = store.current.policies[key]
            val repair = if (job.incident) RepairLadder.active(store.current.repairs[key], now) else null
            val mayGoAbroad = def.hints.requiresForeign || def.hints.likelyGeoRestricted || def.hints.usExit || repair != null ||
                PolicyEngine.requirements(store.current, def, net, now).wantsForeign
            if (mayGoAbroad) ensureCloudConfigs()
            val all = providers(store.current, net)
            val probeAll = probeProviders(store.current, net)
            val dns = dnsPathFor(net, probeAll)
            val ports = ensureProbeCore(probeAll, dns)
            val onLocal = before?.let { p -> all.firstOrNull { it.id == p.routeId }?.kind != RouteKind.FOREIGN } ?: true
            val plan = repair?.let { RepairLadder.plan(it, likelyGeo = def.hints.likelyGeoRestricted || def.hints.requiresForeign, onLocalRoute = onLocal) }
            // A deep repair tries routes still in backoff, too: the user is waiting on this app.
            val probeSet = if (plan?.deep == true) probeAll else probeAll.filter { breaker.allow(bk(it.id, net), now) }
            plan?.let { Log.i(TAG, "repair ${def.id} rung ${it.level}: ${it.why}") }
            // Families are probed one by one (both-families is derived: usable when each was).
            val routes = probeSet.flatMap { p ->
                val fams = p.families.filter { it != FamilyPolicy.BOTH }.ifEmpty { listOf(FamilyPolicy.BOTH) }
                if (p.kind == RouteKind.DIRECT) fams.map { f -> ProbeRoute(p.id, p.kind, f) }
                else fams.mapNotNull { f -> ports[p.tag(f)]?.let { port -> ProbeRoute(p.id, p.kind, f, port, limited = p.caps.noCloudflareDestinations) } }
            }
            val prior = probeSet.associate { p -> p.id to (state0.metrics[MaeState.rk(p.id, def.id, net)] ?: RouteMetrics()) }
            val budget = when {
                plan?.deep == true || plan?.preferThroughput == true ->
                    ProbeBudget(maxConcurrent = 3, rttSamples = 5, finalists = 3, throughputBytes = if (onCellular()) 512 * 1024 else 2_000_000)
                onCellular() -> ProbeBudget.CELLULAR
                else -> ProbeBudget.WIFI
            }
            val result = Discovery(NetProber(app), budget, echoes = echoCache, net = net)
                .run(def, routes, prior, forceForeign = plan?.forceForeign == true)
            if (result.offline) {
                Log.i(TAG, "${def.id}: nothing got through anywhere -- the network is down, nothing recorded")
                deferred += def.id
                if (job.incident) note(def.id, NoteKind.OFFLINE)
                return
            }

            // Route health, from the transport -- not from whether this one app answered.
            for (p in probeSet) {
                val mine = result.observations.filter { it.routeId == p.id }
                breaker.observe(bk(p.id, net), RouteHealth.verdict(p.kind, mine), now)
            }
            // WARP's endpoint is per network: when this one carries nothing, the next discovery
            // tries the next endpoint instead of waiting out the backoff on a dead one.
            if (probeSet.any { it.id == WarpRoute.ID } && breaker.health(bk(WarpRoute.ID, net), now) == Health.UNAVAILABLE) {
                store.update { it.copy(warpEndpoint = it.warpEndpoint + (net to ((it.warpEndpoint[net] ?: 0) + 1))) }
                breaker.success(bk(WarpRoute.ID, net), now)
                Log.i(TAG, "WARP endpoint rotated for this network")
            }

            store.update { s ->
                PolicyEngine.mergeDiscovery(s, def, net, result, { providers(it, net) }, { breaker.health(bk(it, net), now) }, now,
                    job.incident, plan, fallbackRouteFor(s, net)).first
            }
            var learned = 0
            // Every app, not only hand-added sites: an app's website loads from domains its
            // registry entry may not list, and the user should not have to add the site again.
            // Once when the app is first checked, and again on every repair that asks for it.
            if (store.current.siteHosts[def.id] == null || plan?.learnHosts == true) {
                learned = runCatching { learnSiteHosts(def, net, ports, all, job.incident) }
                    .onFailure { Log.w(TAG, "site hosts for ${def.id}: ${it.javaClass.simpleName}") }
                    .getOrDefault(0)
            }
            val after = store.current.policies[key]
            val changed = before?.routeId != after?.routeId || before?.family != after?.family
            // A repair that moved the app is applied by a reconnect: the app keeps its open
            // connections, and a live switch leaves those on the route that failed -- which is why
            // "didn't open" used to change nothing the user could see.
            applyIfConnected(urgent = job.incident, flush = job.incident && changed)
            if (job.incident) {
                val ok = after != null && result.observations.any { it.usable }
                val needsAbroad = plan?.needsForeign == true || def.hints.requiresForeign
                val noForeign = needsAbroad && all.none { it.kind == RouteKind.FOREIGN }
                val blockers = if (def.hints.clientChecks.isNotEmpty() && (plan?.deviceCheck == true || needsAbroad))
                    DeviceSignals.iranian(app, def.hints.clientChecks) else emptyList()
                val level = repairLevel(def.id)
                note(def.id, when {
                    noForeign -> NoteKind.NO_FOREIGN_EXIT
                    !ok -> NoteKind.NOT_FOUND
                    changed || learned > 0 -> NoteKind.FIXED
                    else -> NoteKind.VERIFIED
                }, level = level, routeId = after?.routeId, blockers = blockers, reopen = changed)
            }
        } finally {
            finish(job)
        }
    }

    private fun finish(job: Job2) {
        val id = job.serviceId
        if (job.incident) incidentPending.remove(id)
        // A repair queued behind this check keeps showing as testing.
        if (job.incident || id !in incidentPending) testing.update { it - id }
    }

    private fun note(serviceId: String, kind: NoteKind, level: Int = 0, routeId: String? = null, blockers: List<String> = emptyList(), reopen: Boolean = false) {
        _notes.update { it + (serviceId to Note(kind, System.currentTimeMillis(), level, routeId, blockers, reopen)) }
    }

    /** The screen has shown it: the row goes back to its plain status. */
    fun clearNote(serviceId: String) = _notes.update { it - serviceId }

    // ---------------------------------------------------------------- feedback

    /** The rung the last repair of this app was on, for the message ("approach 3 of 5"). */
    fun repairLevel(serviceId: String): Int =
        RepairLadder.active(store.current.repairs[MaeState.sk(serviceId, currentNet())], System.currentTimeMillis())?.level ?: 0

    /** "Opened": a bounded confidence boost for the current route. Never locks it in. */
    fun feedbackOk(serviceId: String) {
        if (!isConnected()) { note(serviceId, NoteKind.NOT_CONNECTED); return }
        val net = currentNet()
        val now = System.currentTimeMillis()
        store.update { s ->
            val p = s.policies[MaeState.sk(serviceId, net)]
            val local = p?.let { pol -> providers(s, net).firstOrNull { it.id == pol.routeId }?.kind != RouteKind.FOREIGN } == true
            // It works now: the repair ladder starts over next time, and what the user reported
            // about this route no longer holds.
            PolicyEngine.recordFeedback(s, serviceId, net, opened = true, now = now)
                .let { st -> st.copy(repairs = st.repairs - MaeState.sk(serviceId, net)) }
                .let { st -> PolicyEngine.withEvidence(st, serviceId, net, now, openedRoute = p?.let { "${it.routeId}:${it.family}" }, openedLocally = local) }
        }
        clearNote(serviceId)
    }

    /**
     * "Didn't open": an incident, not a rating. From the second time on the user is asked what is
     * wrong first -- and nothing is recorded until they answer ([answerSymptom]); cancelling the
     * question costs no rung.
     */
    fun feedbackFailed(serviceId: String) {
        if (!isConnected()) { note(serviceId, NoteKind.NOT_CONNECTED); return }
        // Already being repaired: a second tap is the same report.
        if (serviceId in incidentPending) { note(serviceId, NoteKind.CHECKING); return }
        val key = MaeState.sk(serviceId, currentNet())
        val cur = store.current.repairs[key]
        val level = RepairLadder.nextLevel(cur, System.currentTimeMillis())
        if (RepairLadder.needsQuestion(level)) {
            _ask.value = Ask(serviceId, level, RepairLadder.active(cur, System.currentTimeMillis())?.symptom)
            return
        }
        commitRepair(serviceId, symptom = null)
    }

    /** The user's answer to "what's wrong?": remembered for this app and its next rungs. */
    fun answerSymptom(serviceId: String, symptom: Symptom) {
        if (_ask.value?.serviceId == serviceId) _ask.value = null
        commitRepair(serviceId, symptom)
    }

    /** The question was dismissed: nothing happened, nothing is recorded. */
    fun cancelAsk() { _ask.value = null }

    private fun commitRepair(serviceId: String, symptom: Symptom?) {
        val net = currentNet()
        val now = System.currentTimeMillis()
        val key = MaeState.sk(serviceId, net)
        store.update { s ->
            // The user said it fails: a route they pinned by hand is not sacred any more.
            val failed = s.policies[key]?.let { "${it.routeId}:${it.family}" }
            val rung = RepairLadder.next(s.repairs[key], failed, now).let { r -> if (symptom != null) r.copy(symptom = symptom) else r }
            PolicyEngine.recordFeedback(s, serviceId, net, opened = false, now = now)
                .copy(repairs = s.repairs + (key to rung))
                .let { st -> PolicyEngine.withEvidence(st, serviceId, net, now, failedRoute = failed, geo = RepairLadder.isGeo(symptom)) }
        }
        enqueue(serviceId, incident = true)
    }

    // ---------------------------------------------------------------- config + connect

    private fun base(): String = app.assets.open(BASE_ASSET).bufferedReader().use { it.readText() }

    private fun apiSocket(): File = File(File(app.filesDir, "mae"), "api.sock")

    /** The live check's inbound: the port the app's own connectivity probe already uses. */
    private fun canaryPort(): Int = com.mlmvpn.scanner.utils.LocalPort.get(app) + 10000

    private data class Compiled(val config: String, val structure: String, val targets: Map<String, String>, val net: String)

    /** The Android UIDs of an app's installed packages, for per-app rules (Android 10+ only). */
    private val uidCache = HashMap<String, Int?>()

    private fun uidsOf(def: ServiceDef): List<Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        return def.packages.mapNotNull { pkg ->
            synchronized(uidCache) {
                uidCache.getOrPut(pkg) { runCatching { app.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull() }
            }
        }.distinct()
    }

    private fun compileNow(withApi: Boolean): Compiled {
        val state = store.current
        val net = currentNet()
        val now = System.currentTimeMillis()
        val providers = providers(state, net)
        val fallback = fallbackRouteFor(state, net)
        synchronized(uidCache) { uidCache.clear() }
        val routes = state.selected.filterNot { it.paused }.mapNotNull { sel ->
            val def = serviceDef(sel.id) ?: return@mapNotNull null
            // The stored policy IS the decision (a repair or a pin chose it deliberately);
            // re-deciding here would quietly undo a repair. Only without one is it decided now.
            val stored = state.policies[MaeState.sk(def.id, net)]?.takeIf { p -> providers.any { it.id == p.routeId } }
            val decision = stored?.let { Decision.Use(it.routeId, it.family, 0.0, it.why) }
                ?: PolicyEngine.decide(state, def, net, providers, { breaker.health(bk(it, net), now) }, now, fallbackRoute = fallback).first
            val req = PolicyEngine.requirements(state, def, net, now)
            val abroad = (decision as? Decision.Use)?.let { d -> providers.firstOrNull { it.id == d.routeId }?.kind == RouteKind.FOREIGN } == true
            MaeConfigCompiler.ServiceRoute(def, decision, PolicyEngine.blockUdp(decision, providers, req), if (abroad) def.bundle else emptyList(), uidsOf(def))
        }
        val dns = state.dnsPaths[net]
        val canary = canaryPort()
        val config = MaeConfigCompiler.compile(base(), providers, routes, if (withApi) apiSocket().absolutePath else null,
            dns, defaultVia(state, net), verbose = File(File(app.filesDir, "mae"), "debug").exists(), canaryPort = canary)
        // Structure = what needs a new config; everything else is a balancer target, switched live.
        // The outbounds themselves are part of it: a rotated WARP endpoint, a redeployed Worker or
        // an edited config used to be switched "live" -- to nothing, as the running core still had
        // the old ones. The DNS path counts by what it is, not by when it was last checked.
        val structure = buildString {
            providers.forEach { append(it.id).append(':').append(it.outbounds().toString().hashCode()).append(',') }
            append('#').append(dns?.url).append('|').append(dns?.via)
            append('#').append(defaultVia(state, net)).append('#').append(canary).append('#')
            routes.filter { MaeConfigCompiler.targetsFor(it, providers) != null }.forEach { r ->
                append(r.service.id).append(':').append(r.service.domains.hashCode()).append(':').append(r.service.ipRanges.hashCode())
                    .append(':').append(r.bundle.hashCode()).append(':').append(r.uids.hashCode()).append(',')
            }
        }
        val targets = routes.flatMap { MaeConfigCompiler.balancerTargets(it, providers).entries.map { e -> e.key to e.value } }.toMap()
        return Compiled(config, structure, targets, net)
    }

    /**
     * The route for traffic MAE knows nothing about. The Serverless base, unless Serverless was
     * tried on this network and served nothing -- then direct, so ordinary sites keep working
     * (MCI, 2026-09-29: fragment reached nothing, not even unfiltered GitHub).
     */
    fun defaultVia(state: MaeState, net: String): String? {
        val tried = state.proofs.filterKeys { it.startsWith("${ServerlessRoute.id}:") && it.endsWith("|$net") }
        if (tried.isEmpty() || tried.values.any { it.serviceAccepted }) return null
        val directWorks = state.proofs.any { (k, p) -> k.startsWith("${DirectRoute.id}:") && k.endsWith("|$net") && p.serviceAccepted }
        return if (directWorks) DirectRoute.TAG else null
    }

    @Volatile private var applied: Compiled? = null

    /** The config to connect with. Uses cached policies only: tap → connected, no probing first. */
    fun buildConfig(): String {
        runCatching { apiSocket().delete() }
        val c = compileNow(withApi = store.current.liveApiWorks != false)
        applied = c
        // Debug builds with the `debug` flag keep the last config for inspection (no secrets
        // beyond what the app already holds in its own private files).
        val dir = File(app.filesDir, "mae")
        if (File(dir, "debug").exists()) runCatching { File(dir, "last_config.json").writeText(c.config) }
        return c.config
    }

    /**
     * A config for THIS network, for a tunnel started without the MAE screen: MyVpnService's own
     * reconnect after a network drop, the Quick Settings tile. Replaying the last config compiled
     * brought the previous network's decisions (and DNS path) to the new one.
     */
    fun freshConfig(context: Context): String {
        init(context)
        return buildConfig()
    }

    /**
     * MLM Scout: the config a connect would compile on this network, without the live tunnel's
     * API socket and without becoming [applied] -- a test must not move the running engine.
     * Null without a Cloudflare account (MAE does not start half-built either).
     */
    fun scoutConfig(context: Context): String? {
        init(context)
        if (!hasCloudAccount()) return null
        return runCatching { compileNow(withApi = false).config }.getOrNull()
    }

    fun isConnected(): Boolean =
        MyVpnService.connectedNodeIdFlow.value == NODE_ID && MyVpnService.connectionPhaseFlow.value == MyVpnService.Phase.CONNECTED

    fun startTunnel(context: Context) {
        val cfg = buildConfig()
        // The user may have pressed Disconnect while this was being built.
        if (!wantConnected) return
        com.mlmvpn.scanner.utils.SafeStart.service(context, Intent(context, MyVpnService::class.java).apply {
            putExtra("NODE_URI", cfg)
            putExtra("MTU_PROFILE", com.mlmvpn.scanner.utils.NetworkSettings.Method.MAE.id)
            putExtra("NODE_ID", NODE_ID)
            putExtra("PROXY_MODE", false)
            putExtra("LOCAL_PORT", com.mlmvpn.scanner.utils.LocalPort.getString(context))
        })
    }

    /** [startTunnel] off the main thread: building the config takes long enough to stutter a tap. */
    fun startTunnelAsync(context: Context) {
        init(context)
        wantConnected = true
        scope.launch {
            // Without a Cloudflare account MAE has none of its foreign exits: it does not start
            // half-built (the screen asks for the account first).
            if (!hasCloudAccount()) { wantConnected = false; Log.i(TAG, "not started: no Cloudflare account"); return@launch }
            applyLock.withLock { if (wantConnected) startTunnel(context) }
        }
    }

    fun stopTunnelAsync(context: Context) {
        wantConnected = false
        reconnectJob?.cancel()
        scope.launch { stopTunnel(context) }
    }

    fun stopTunnel(context: Context) {
        wantConnected = false
        com.mlmvpn.scanner.utils.SafeStart.service(context, Intent(context, MyVpnService::class.java).apply { action = "STOP" })
    }

    /**
     * Follows the tunnel's life however it was started or stopped -- from this screen, the Quick
     * Settings tile, MyVpnService's own reconnect, another engine taking over -- so MAE's watchers
     * run exactly while MAE is the tunnel.
     */
    private fun observeSession() {
        scope.launch {
            combine(MyVpnService.connectionPhaseFlow, MyVpnService.connectedNodeIdFlow) { phase, id ->
                phase == MyVpnService.Phase.CONNECTED && id == NODE_ID
            }.distinctUntilChanged().collect { up -> if (up) sessionUp() else sessionDown() }
        }
    }

    private fun sessionUp() {
        wantConnected = true
        lastTunnelBuildAt = System.currentTimeMillis()
        watchNetwork()
        registerScreen()
        startLantern()
        refreshStale()
        statsJob?.cancel()
        lastStatsAt = 0L
        statsJob = scope.launch {
            spikeLiveApi()
            lastRefreshAt = System.currentTimeMillis()
            while (isActive && isConnected()) {
                // Both jobs of this loop are for a phone in use. With the screen off it does not
                // tick at all: it waits for the screen, and the re-check it may owe is made then
                // (see [observeScreen]).
                if (!com.mlmvpn.scanner.utils.ScreenState.isOn(app)) {
                    com.mlmvpn.scanner.utils.ScreenState.on.first { it }
                    continue
                }
                samplePassiveStats()
                if (System.currentTimeMillis() - lastRefreshAt >= REFRESH_EVERY_MS) { refreshStale(); lastRefreshAt = System.currentTimeMillis() }
                delay(STATS_EVERY_MS)
            }
        }
        healthJob?.cancel()
        healthJob = scope.launch {
            delay(8_000)
            // Not after MAE's own reconnect to apply a change: its routes were proven moments
            // ago, and a check of a tunnel still warming up moved apps that were fine.
            if (System.currentTimeMillis() - selfReconnectAt > 60_000) runCanary("connected")
            while (isActive && isConnected()) {
                // The periodic check is for the screen being on; unlocking after a while runs its
                // own (see [registerScreen]). With the screen off this waits instead of waking
                // every five minutes to find it still off.
                if (!screenOn()) {
                    com.mlmvpn.scanner.utils.ScreenState.on.first { it }
                    continue
                }
                delay(CANARY_EVERY_MS)
                if (screenOn()) runCanary("periodic")
            }
        }
        // A tunnel started without this engine (the tile, the service's own reconnect) or one
        // compiled for another network is brought up to date for this one.
        val a = applied
        if (a == null || a.net != currentNet()) scope.launch { applyIfConnected(urgent = true) }
    }

    private fun sessionDown() {
        statsJob?.cancel(); statsJob = null
        healthJob?.cancel(); healthJob = null
        reconnectJob?.cancel()
        stopLanternSoon()
        netCallback?.let { cb -> runCatching { (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) } }
        netCallback = null
        screenReceiver?.let { r -> runCatching { app.unregisterReceiver(r) } }
        screenReceiver = null
    }

    private fun screenOn(): Boolean = runCatching { (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive }.getOrDefault(true)

    /**
     * The screen came on: the routine checks that waited for it run now, and so does the sweep for
     * stale decisions if one fell due while it was off. Whether MAE is connected or not -- a check
     * is about the network, and the list is what the user is about to look at.
     */
    private fun observeScreen() {
        scope.launch {
            com.mlmvpn.scanner.utils.ScreenState.on.collect { on ->
                if (!on) return@collect
                val ids = synchronized(waitingForScreen) { waitingForScreen.toList().also { waitingForScreen.clear() } }
                ids.forEach { enqueue(it) }
                if (isConnected() && System.currentTimeMillis() - lastRefreshAt >= REFRESH_EVERY_MS) {
                    lastRefreshAt = System.currentTimeMillis()
                    refreshStale()
                }
            }
        }
    }

    /**
     * The moment that matters most is the user coming back: unlocking after a while with the
     * screen off runs the live check at once, so a route that died meanwhile is healed before, or
     * right as, the app they open needs it.
     */
    private fun registerScreen() {
        if (screenReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> screenOffAt = System.currentTimeMillis()
                    Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> {
                        val off = screenOffAt
                        if (off > 0 && System.currentTimeMillis() - off >= WAKE_CHECK_AFTER_MS) {
                            screenOffAt = 0
                            scope.launch { runCanary("wake") }
                        }
                    }
                }
            }
        }
        runCatching {
            app.registerReceiver(r, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            })
            screenReceiver = r
        }
    }

    /**
     * The live-routing spike. Harmless by construction: it re-points one balancer at the target
     * it already has. Success proves the Unix-socket API, HTTP/2 and the method all work on this
     * phone. A failure is retried on later connects -- live switching is given up only after three
     * in a row (one slow start used to turn it off for good) -- and an app update tests it again.
     */
    private suspend fun spikeLiveApi() {
        repeat(30) { if (isConnected()) return@repeat; delay(1000) }
        if (!isConnected()) return
        val build = appBuild()
        val s = store.current
        if (s.liveApiBuild != build && s.liveApiWorks != true) store.update { it.copy(liveApiWorks = null, liveApiFails = 0, liveApiBuild = build) }
        if (store.current.liveApiWorks != null) return
        val c = applied ?: return
        val (bal, target) = c.targets.entries.firstOrNull()?.toPair() ?: return
        // The API listener comes up with the core: a moment for it.
        delay(1_500)
        val r = XrayApiClient(apiSocket().absolutePath).overrideBalancerTarget(bal, target)
        val works = r is XrayApiClient.Result.Ok
        Log.i(TAG, "live API spike: ${if (works) "works" else "failed ($r)"}")
        store.update { st ->
            if (works) st.copy(liveApiWorks = true, liveApiFails = 0, liveApiBuild = build)
            else (st.liveApiFails + 1).let { f -> st.copy(liveApiWorks = if (f >= 3) false else null, liveApiFails = f, liveApiBuild = build) }
        }
    }

    private fun appBuild(): Long = runCatching {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)

    private var reconnectJob: Job? = null
    @Volatile private var selfReconnectAt = 0L

    /**
     * A reconnect costs every app a second of nothing, so while apps are still being checked the
     * reconnects they need are made once, together: after the checks quiet down, and never more
     * than [CHECKS_QUIET_WAIT_MS] late, and never closer than the gap to the last rebuild. [urgent] (the user just said an app did not open) goes now.
     */
    private fun scheduleReconnect(urgent: Boolean, gapMs: Long = RULES_RECONNECT_GAP_MS) {
        synchronized(this) {
            // A pending background reconnect keeps its turn, unless this one may come sooner.
            if (!urgent && reconnectJob?.isActive == true && gapMs >= pendingGapMs) return
            reconnectJob?.cancel()
            pendingGapMs = if (urgent) 0L else gapMs
            reconnectJob = scope.launch {
                if (!urgent) {
                    // Measured 2026-10-07: every app check that finished, and every WARP endpoint
                    // rotation, rebuilt the tunnel -- about once a minute, and every app on the
                    // phone (TikTok: "disconnected / connected") felt each one. Background changes
                    // now wait for the checks to finish and come at most once per [gapMs].
                    val until = System.currentTimeMillis() + CHECKS_QUIET_WAIT_MS
                    delay(2_000)
                    while ((testing.value.isNotEmpty() || queued.isNotEmpty()) && System.currentTimeMillis() < until) delay(1_000)
                    val since = System.currentTimeMillis() - lastTunnelBuildAt
                    if (since < gapMs) delay(gapMs - since)
                }
                applyLock.withLock {
                    pendingGapMs = Long.MAX_VALUE
                    if (!isConnected() || !wantConnected) return@withLock
                    val next = compileNow(withApi = store.current.liveApiWorks != false)
                    val prev = applied
                    if (prev != null && prev.config == next.config) return@withLock
                    Log.i(TAG, "applying by reconnect${if (urgent) " (urgent)" else ""}")
                    selfReconnectAt = System.currentTimeMillis()
                    lastTunnelBuildAt = selfReconnectAt
                    startTunnel(app)
                }
            }
        }
    }

    /** The gap of the background reconnect waiting now; MAX when none waits. */
    @Volatile private var pendingGapMs = Long.MAX_VALUE
    /** When the running tunnel was built (connect or reconnect): background reconnects keep their distance from it. */
    @Volatile private var lastTunnelBuildAt = 0L

    /** The outbounds of a compiled config, by tag, as text: what a live switch can point at. */
    private fun outboundsOf(config: String): Map<String, String> = runCatching {
        val arr = JSONObject(config).optJSONArray("outbounds") ?: return@runCatching emptyMap()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.associate { it.optString("tag") to it.toString() }
    }.getOrDefault(emptyMap())

    /** The structure without the outbounds: the rules, the DNS path, the default route. */
    private fun rulesOf(structure: String): String = structure.substringAfter('#', "")

    /**
     * Push the current decisions into a running tunnel: live when possible, reconnect otherwise.
     * [flush]: the app that moved must drop its open connections -- a reconnect, even where a live
     * switch would do for new ones.
     */
    suspend fun applyIfConnected(urgent: Boolean = false, flush: Boolean = false) = applyLock.withLock {
        if (!isConnected()) return@withLock
        if (applyHeld) { applyPending = true; return@withLock }
        val next = compileNow(withApi = store.current.liveApiWorks != false)
        val prev = applied
        if (prev != null && prev.structure == next.structure && prev.targets == next.targets) return@withLock
        // Live when the running tunnel already has what the new targets need: the same rules and,
        // for every target, the very same outbound. A route found or changed that no app uses yet
        // (a new config, a rotated WARP endpoint) is no reason to cut every app's connections.
        val sameStructure = prev != null && prev.structure == next.structure
        val servable = prev != null && !sameStructure && rulesOf(prev.structure) == rulesOf(next.structure) && run {
            val running = outboundsOf(prev.config)
            val fresh = outboundsOf(next.config)
            next.targets.values.all { t -> running[t] != null && running[t] == fresh[t] }
        }
        if (!flush && prev != null && (sameStructure || servable) && store.current.liveApiWorks == true) {
            val api = XrayApiClient(apiSocket().absolutePath)
            var allOk = true
            for ((bal, target) in next.targets) {
                if (prev.targets[bal] == target) continue
                // One retry before giving up on live switching: a reconnect costs every service a blip.
                val r = api.overrideBalancerTarget(bal, target).let { first ->
                    if (first is XrayApiClient.Result.Ok) first else api.overrideBalancerTarget(bal, target)
                }
                if (r !is XrayApiClient.Result.Ok) { allOk = false; Log.w(TAG, "live switch $bal failed: $r"); break }
                Log.i(TAG, "live switch $bal -> $target")
            }
            // What runs is still the previous config: only its targets moved.
            if (allOk) { applied = prev.copy(targets = next.targets); return@withLock }
        }
        when {
            urgent || flush -> scheduleReconnect(urgent = true)
            // The rules changed (an app added or removed, its sites learned): soon, batched.
            prev == null || rulesOf(prev.structure) != rulesOf(next.structure) -> scheduleReconnect(urgent = false, gapMs = RULES_RECONNECT_GAP_MS)
            // Only routes no app can be moved to live: the apps keep the route that works for
            // them now, and the better one arrives with the next rebuild, rarely.
            else -> scheduleReconnect(urgent = false, gapMs = ROUTES_RECONNECT_GAP_MS)
        }
    }

    private fun watchNetwork() {
        if (netCallback != null) return
        lastNet = currentNet()
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Internet came back (a Wi-Fi login done): what waited for it is checked now.
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && deferred.isNotEmpty()) {
                    val ids = synchronized(deferred) { deferred.toList().also { deferred.clear() } }
                    ids.forEach { enqueue(it, priority = true) }
                }
            }
            private fun changed() {
                netCache = null
                scope.launch {
                    delay(2000) // let the new network settle
                    netCache = null
                    val now = currentNet()
                    netFlow.value = now
                    if (now == lastNet) return@launch
                    lastNet = now
                    Log.i(TAG, "network changed; re-deciding from this network's memory")
                    refreshStale()
                    // A new network is a new set of decisions: applied now, not batched.
                    applyIfConnected(urgent = true)
                    delay(10_000)
                    runCanary("network")
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); netCallback = cb }
    }

    // ---------------------------------------------------------------- live health

    private val canaryLock = Mutex()
    @Volatile private var lastHealReconnectAt = 0L

    /**
     * Checks every route in use, from inside the tunnel, with one small request of an app that
     * uses it. A route whose app cannot be reached twice in a row is dead:
     *  - WARP (a UDP session that idles out and must handshake again), or every route at once on a
     *    network that does have internet: a fresh session -- a reconnect, exactly what the user
     *    used to do by hand;
     *  - otherwise the apps on it move to their next proven route, live, and are re-checked.
     * An app that answers with its country refusal is re-checked with priority (a Worker's exit
     * changes country between connections).
     */
    private suspend fun runCanary(reason: String) = canaryLock.withLock {
        if (!isConnected()) return@withLock
        val state = store.current
        val net = currentNet()
        val providers = providers(state, net)
        val kinds = providers.associate { it.id to it.kind }
        // One app per route and family in use.
        val inUse = LinkedHashMap<String, ServiceDef>()
        for (sel in state.selected.filterNot { it.paused }) {
            val def = serviceDef(sel.id) ?: continue
            val p = state.policies[MaeState.sk(def.id, net)] ?: continue
            if (p.routeId !in kinds || def.probes.isEmpty()) continue
            inUse.putIfAbsent("${p.routeId}:${p.family}", def)
        }
        if (inUse.isEmpty()) return@withLock
        val canary = LiveCanary(app, canaryPort())
        val dead = ArrayList<String>()
        val refused = ArrayList<String>()
        for ((routeKey, def) in inUse) {
            val spec = def.probes.first()
            var r = canary.check(spec)
            if (!r.answered) { delay(1_500); r = canary.check(spec) }
            when {
                !r.answered -> dead += routeKey
                r.refused -> refused += def.id
            }
        }
        Log.i(TAG, "canary ($reason): ${inUse.size} route(s) in use, ${dead.size} dead${if (dead.isNotEmpty()) " $dead" else ""}")
        refused.forEach { enqueue(it, priority = true) }
        if (dead.isEmpty()) return@withLock
        if (!online()) return@withLock // the network itself is down: MyVpnService's watchdog reconnects
        val now = System.currentTimeMillis()
        val sessionRoute = dead.any { it.substringBefore(':') == WarpRoute.ID }
        val everything = dead.size == inUse.size
        if ((sessionRoute || everything) && now - lastHealReconnectAt >= HEAL_RECONNECT_GAP_MS) {
            lastHealReconnectAt = now
            Log.i(TAG, "healing by a fresh session (${if (sessionRoute) "WARP" else "every route"} stopped carrying)")
            dead.forEach { rk -> affected(state, net, rk).forEach { note(it, NoteKind.HEALED, routeId = rk.substringBefore(':')) } }
            reconnectNow()
            return@withLock
        }
        // Move the apps off the dead routes: their record says so, the scorer picks the next
        // proven route, and it is applied live. Then they are re-checked properly.
        store.update { s ->
            var st = s
            for (rk in dead) {
                val routeId = rk.substringBefore(':')
                breaker.failure(bk(routeId, net), now)
                for (id in affected(s, net, rk)) {
                    val k = MaeState.rk(routeId, id, net)
                    st = st.copy(metrics = st.metrics + (k to (st.metrics[k] ?: RouteMetrics()).record(false, null, now)))
                    val def = serviceDef(id) ?: continue
                    val (_, pol) = PolicyEngine.decide(st, def, net, providers, { breaker.health(bk(it, net), now) }, now,
                        fallbackRoute = fallbackRouteFor(st, net))
                    // Stale on purpose: the proper re-check queued below must not be skipped as fresh.
                    if (pol != null && st.policies[MaeState.sk(id, net)]?.pinned != true)
                        st = st.copy(policies = st.policies + (MaeState.sk(id, net) to pol.copy(decidedAt = 0)))
                }
            }
            st
        }
        dead.forEach { rk -> affected(state, net, rk).forEach { id -> note(id, NoteKind.HEALED, routeId = store.current.policies[MaeState.sk(id, net)]?.routeId); enqueue(id, priority = true) } }
        applyIfConnected(urgent = true)
    }

    /** Apps whose policy on [net] is the route and family [routeKey] (`route:FAMILY`). */
    private fun affected(state: MaeState, net: String, routeKey: String): List<String> =
        state.selected.filterNot { it.paused }.map { it.id }.filter { id ->
            state.policies[MaeState.sk(id, net)]?.let { "${it.routeId}:${it.family}" } == routeKey
        }

    /** A reconnect now, with this network's config: fresh sessions for every route. */
    private suspend fun reconnectNow() = applyLock.withLock {
        if (!isConnected() || !wantConnected) return@withLock
        startTunnel(app)
    }

    /**
     * Passive learning: bytes each MAE outbound moved since the last sample (queryStats resets the
     * counter). A busy window is a lower bound on what the route can carry; it only ever raises
     * the throughput estimate of routes that carried real traffic, never invents one.
     */
    private fun samplePassiveStats() {
        val net = currentNet()
        val state = store.current
        val providers = providers(state, net)
        // The tunnel's core runs in its own process (XrayHosts): one round trip for every tag.
        val counts = VlessXrayInjector.tunnelStats(app, providers.flatMap { p -> p.families.map { p.tag(it) } }.distinct()) ?: return
        // The window is what really passed: sampling stops while the screen is off, and the first
        // read after it covers the whole gap. Spread over minutes the bytes say nothing about what
        // a route can carry, so that read only resets the counters.
        val now = android.os.SystemClock.elapsedRealtime()
        val windowSecs = (now - lastStatsAt).coerceAtLeast(1L) / 1000.0
        val meaningful = lastStatsAt != 0L && windowSecs <= STATS_EVERY_MS * 1.5 / 1000.0
        lastStatsAt = now
        for (p in providers) {
            val bytes = p.families.map { p.tag(it) }.distinct().sumOf { tag -> counts[tag] ?: 0L }
            if (bytes > 0) Log.i(TAG, "passive: ${p.id} moved $bytes bytes in ${windowSecs.toInt()} s")
            if (!meaningful || bytes < 2_000_000) continue
            val bps = bytes / windowSecs
            store.update { s ->
                var metrics = s.metrics
                s.policies.values.filter { it.netKey == net && it.routeId == p.id }.forEach { pol ->
                    val k = MaeState.rk(p.id, pol.serviceId, net)
                    val m = metrics[k] ?: return@forEach
                    if ((m.throughputBps ?: 0.0) < bps) metrics = metrics + (k to m.withThroughput(bps))
                }
                if (metrics === s.metrics) s else s.copy(metrics = metrics)
            }
        }
    }

    // ---------------------------------------------------------------- foreign egress

    fun cloudAccounts() = CloudManager(app).accounts.toList()

    /**
     * MAE's foreign exit is the account's central Worker: installed here when the account has none yet
     * (once -- every panel and the desktop app use the same one), adopted as it is when it has.
     * Nothing of MAE's own is deployed any more.
     */
    suspend fun deployEgress(accountIndex: Int = 0): Result<Unit> = runCatching {
        val account = cloudAccounts().getOrNull(accountIndex) ?: error("no Cloudflare account")
        val gw = com.mlmvpn.scanner.engines.gateway.EdgeGateway
        if (!gw.status(app, account).installed) {
            // Another device may already have one on this account: the sync adopts it, never a second.
            gw.sync(app, account)
            if (!gw.status(app, account).installed) gw.deploy(app, account).getOrElse { throw IllegalStateException(gw.localized(it), it) }
        }
        syncWorkerExit()
    }

    /**
     * Removes the exit, and everything that pointed at it: apps on it are decided again now --
     * they used to keep showing (and compiling) a route that no longer existed until hours later.
     */
    suspend fun removeEgress(): Result<Unit> = runCatching {
        val w = store.current.worker ?: return@runCatching
        cloudAccounts().firstOrNull { it.accountId == w.accountId }?.let { MaeEgressDeployer.remove(it) }
        store.update { s ->
            s.copy(
                worker = null,
                policies = s.policies.mapValues { (_, p) -> if (p.routeId == WorkerRoute.ID) p.copy(decidedAt = 0, pinned = false) else p },
                proofs = s.proofs.filterKeys { !it.startsWith("${WorkerRoute.ID}:") },
                metrics = s.metrics.filterKeys { !it.startsWith("${WorkerRoute.ID}|") },
                egress = s.egress.filterValues { !(it is ForeignEgressStatus.Proven && it.routeId == WorkerRoute.ID) },
            )
        }
        store.current.selected.forEach { enqueue(it.id) }
        applyIfConnected()
    }
}
