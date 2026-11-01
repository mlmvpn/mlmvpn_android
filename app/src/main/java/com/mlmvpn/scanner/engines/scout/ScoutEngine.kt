package com.mlmvpn.scanner.engines.scout

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.util.Log
import com.mlmvpn.scanner.data.ScannerManager
import com.mlmvpn.scanner.engines.cfnet.CfNetEnv
import com.mlmvpn.scanner.engines.game.booster.session.GameNetwork
import com.mlmvpn.scanner.store.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * MLM Scout's brain (plan section 3).
 *
 * One run: the app's own VPN paused (with consent), the line diagnosed beside a QUICK pass (each
 * method's most likely variant, so a first healthy answer comes in seconds), then a SPEED round
 * (strictly one method at a time, with the line's own speed taken before and after), then the DEEP
 * pass (every other variant), and a last speed sample for anything newly working.
 *
 * Every result is written to disk as it lands. A stop, a network change, another VPN coming up or
 * the line going away for good ends the run safely: the results so far stay, labelled with the
 * network they belong to and marked partial. The user's settings are never written.
 */
object ScoutEngine {

    private const val TAG = "Scout"
    /** [quickWarp]'s guard against a run that never ends: well past the quick pass's own budgets. */
    private const val QUICK_WARP_CEILING_MS = 9 * 60_000L

    data class Ui(
        val running: Boolean = false,
        /** The run on screen: the one in progress, or the latest finished one. */
        val run: ScoutRun? = null,
        val tasksDone: Int = 0,
        val tasksTotal: Int = 0,
        val lineDown: Boolean = false,
        /** Wall-clock start of the run in progress, for the elapsed time. */
        val startedAt: Long = 0,
        val stopping: Boolean = false,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var job: Job? = null
    @Volatile private var endReason: EndReason = EndReason.STOPPED
    /** This run stops after its quick pass ([quickWarp]): no speed round, no full pass. */
    @Volatile private var quickOnly = false
    @Volatile private lateinit var app: Context

    /** What each healthy method ran on, kept for the speed round (memory only, never written). */
    private val handles = ConcurrentHashMap<Pair<String, String>, Prepared.Ready>()
    private val definitions = ConcurrentHashMap<Pair<String, String>, Variant>()

    /** The speed sample goes to the variant that speaks for the method: WORKS before WEAK, then the lowest delay. */
    private fun keepHandle(methodId: String, variantId: String, ready: Prepared.Ready, r: VariantResult) {
        handles[methodId to variantId] = ready
    }
    private val saveLock = Any()
    @Volatile private var lastSave = 0L

    // A cancelled Job still owns its cores until its finally blocks finish.
    fun isRunning(): Boolean = _ui.value.running

    /** Loads history, closes a run the app was killed in the middle of, shows this network's latest. */
    fun init(context: Context) {
        app = context.applicationContext
        ScoutStore.load(app)
        if (!isRunning()) {
            ScoutStore.runs.value.filter { it.finishedAt == null }.forEach { r -> ScoutStore.put(app, closeRun(r, EndReason.INTERRUPTED, interrupted = true)) }
            if (_ui.value.run == null || _ui.value.run?.finishedAt == null) _ui.update { it.copy(run = latestForHere()) }
        }
    }

    /** The latest run on the network the phone is on now, else the latest at all. */
    fun latestForHere(): ScoutRun? {
        val key = runCatching { CfNetEnv.profile(app).key }.getOrNull()
        return key?.let { ScoutStore.latestFor(it) } ?: ScoutStore.latest()
    }

    fun currentNetKey(context: Context): String? = runCatching {
        if (GameNetwork.pick(context) == null) null else CfNetEnv.profile(context.applicationContext).key
    }.getOrNull()

    /** Shows another stored run (history), only while nothing runs. */
    fun show(run: ScoutRun) { if (!isRunning()) _ui.update { it.copy(run = run) } }

    // ---- starting -------------------------------------------------------------------------

    sealed class Check {
        object Ok : Check()
        /** This app's VPN is up: ask, then pause it for the run. */
        data class OurVpn(val label: String) : Check()
        /** Another app's VPN: Scout would measure that VPN, not the user's line. */
        object ForeignVpn : Check()
        object Scanning : Check()
        object Offline : Check()
    }

    fun check(context: Context): Check = when {
        ScannerManager.isScanning.value -> Check.Scanning
        ScoutVpn.ourVpnUp() -> Check.OurVpn(ScoutVpn.current(context)?.label ?: "VPN")
        ScoutVpn.anyVpn(context) -> Check.ForeignVpn
        GameNetwork.pick(context) == null -> Check.Offline
        else -> Check.Ok
    }

    /**
     * Starts a run. [resume] continues an earlier run on the same network (only what it did not
     * finish); [only] limits the run to some methods (a «دوباره تست کن»); [quickOnly] ends it after
     * the quick pass.
     */
    @Synchronized
    fun start(context: Context, resume: ScoutRun? = null, only: Set<String>? = null, pauseApproved: Boolean = false,
        quickOnly: Boolean = false) {
        if (!ScoutAdmission.allowed(_ui.value.running, ScannerManager.isScanning.value,
                GameNetwork.pick(context) != null, ScoutVpn.ourVpnUp(), ScoutVpn.anyVpn(context), pauseApproved)) return
        app = context.applicationContext
        ScoutStore.load(app)
        endReason = EndReason.STOPPED
        this.quickOnly = quickOnly
        handles.clear(); definitions.clear()
        _ui.update { Ui(running = true, run = resume, startedAt = System.currentTimeMillis()) }
        job = scope.launch {
            try {
                execute(resume, only, pauseApproved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "run failed", e)
                endReason = EndReason.INTERRUPTED
            } finally {
                withContext(NonCancellable) { finish() }
            }
        }
        ScoutService.start(app)
    }

    fun stop(reason: EndReason = EndReason.STOPPED) {
        val j = job ?: return
        if (!j.isActive) return
        endReason = reason
        _ui.update { it.copy(stopping = true) }
        j.cancel()
    }

    /** A pending connection may proceed only after every test core has released its resources. */
    suspend fun stopAndWait(reason: EndReason = EndReason.STOPPED) {
        val finishing = job
        stop(reason)
        finishing?.join()
    }

    /** The whole of one method again, the rest of the run as it was. */
    fun retest(context: Context, methodId: String, pauseApproved: Boolean = false) {
        val run = _ui.value.run ?: return start(context, only = setOf(methodId), pauseApproved = pauseApproved)
        if (run.netKey != currentNetKey(context)) return start(context, only = setOf(methodId), pauseApproved = pauseApproved)
        start(context, resume = run.copy(methods = run.methods - methodId), only = setOf(methodId), pauseApproved = pauseApproved)
    }

    /** Why [quickWarp] could not look: Scout busy, a VPN up (ours is never paused for it), a scan, no line, or cut short. */
    enum class WarpSkip { BUSY, OUR_VPN, FOREIGN_VPN, SCANNING, OFFLINE, INTERRUPTED }

    /** What the quick look at «وایرگارد» and «ماسک» found on this line. */
    data class WarpQuick(val wireguard: Boolean = false, val masque: Boolean = false, val skipped: WarpSkip? = null)

    /**
     * «وایرگارد» and «ماسک» -- the WARP and MASQUE the 2026-10 summary gives for both firewall types --
     * each given its quick pass here: the tile's own core, identity and first family (IPv6 where the
     * line has it), MASQUE on HTTP/3 with the core's own fall back to HTTP/2. Merged into this
     * network's latest run like a «دوباره تست کن», so Scout's screen keeps the rest of it. For «سنجش
     * روش‌ها» when none of the Cloudflare ways carried a config; [onStep] names the tile being checked.
     * Suspends until the run ends; cancelling it stops the run.
     */
    suspend fun quickWarp(context: Context, onStep: (String?) -> Unit = {}): WarpQuick {
        val wireguard = com.mlmvpn.scanner.ui.tunnel.Transport.WIREGUARD.id
        val masque = com.mlmvpn.scanner.ui.tunnel.Transport.MASQUE.id
        val ids = setOf(wireguard, masque)
        val skip = if (isRunning()) WarpSkip.BUSY else when (check(context)) {
            Check.Ok -> null
            is Check.OurVpn -> WarpSkip.OUR_VPN
            Check.ForeignVpn -> WarpSkip.FOREIGN_VPN
            Check.Scanning -> WarpSkip.SCANNING
            Check.Offline -> WarpSkip.OFFLINE
        }
        if (skip != null) return WarpQuick(skipped = skip)
        val here = _ui.value.run?.takeIf { it.finishedAt != null && it.netKey == currentNetKey(context) }
        start(context, resume = here?.let { it.copy(methods = it.methods - ids) }, only = ids, quickOnly = true)
        if (!isRunning()) return WarpQuick(skipped = WarpSkip.BUSY)
        try {
            // Scout's own budgets end the quick pass; the ceiling only guards against a stuck run.
            val ended = withTimeoutOrNull(QUICK_WARP_CEILING_MS) {
                ui.first { u ->
                    onStep(ids.firstOrNull { u.run?.methods?.get(it)?.status == ScoutStatus.CHECKING })
                    !u.running
                }
            }
            if (ended == null) stopAndWait()
        } catch (e: CancellationException) {
            stop()
            throw e
        }
        val run = _ui.value.run
        val methods = run?.methods.orEmpty()
        fun works(id: String) = methods[id]?.variants.orEmpty().any { it.status.healthy }
        val result = WarpQuick(wireguard = works(wireguard), masque = works(masque))
        return if (run?.end == EndReason.DONE || result.wireguard || result.masque) result else result.copy(skipped = WarpSkip.INTERRUPTED)
    }

    // ---- the run ---------------------------------------------------------------------------

    private class Planned(val adapter: ScoutAdapter, var variants: List<Variant>)

    @Volatile private var line: ScoutLine? = null
    @Volatile private var snap: LineSnapshot? = null
    @Volatile private var pulse: Heartbeat? = null
    @Volatile private var guard: NetGuard? = null
    @Volatile private var bytesAtStart = 0L

    private fun current(): ScoutRun = _ui.value.run!!

    private fun mutate(f: (ScoutRun) -> ScoutRun) {
        _ui.update { u -> u.run?.let { u.copy(run = f(it)) } ?: u }
        save(force = false)
    }

    private fun save(force: Boolean) {
        synchronized(saveLock) {
            val run = _ui.value.run ?: return
            val now = System.currentTimeMillis()
            if (!force && now - lastSave < 400) return
            lastSave = now
            // Serialize the snapshot together with its write: an earlier worker must not
            // overwrite a newer worker's results after waiting for the store's lock.
            runCatching { ScoutStore.put(app, run) }
        }
    }

    private fun method(id: String, f: (MethodResult) -> MethodResult) = mutate { r ->
        val m = r.methods[id] ?: MethodResult(id)
        r.copy(methods = LinkedHashMap(r.methods).apply { put(id, f(m).copy(updatedAt = System.currentTimeMillis())) })
    }

    private fun tick(n: Int = 1) = _ui.update { it.copy(tasksDone = (it.tasksDone + n).coerceAtMost(it.tasksTotal)) }
    private fun addTasks(n: Int) = _ui.update { it.copy(tasksTotal = it.tasksTotal + n) }

    private suspend fun execute(resume: ScoutRun?, only: Set<String>?, pauseApproved: Boolean) {
        bytesAtStart = uidBytes()
        if (ScannerManager.isScanning.value) { endReason = EndReason.OTHER_SCAN; return }
        if (ScoutVpn.ourVpnUp() && !pauseApproved) { endReason = EndReason.VPN_STARTED; return }
        val paused = ScoutVpn.pause(app)
        if (ScoutVpn.anyVpn(app)) { endReason = EndReason.VPN_STARTED; return }

        val profile = CfNetEnv.profile(app)
        val l = ScoutLine(app) { f -> mutate { r -> r.copy(line = f(r.line)) } }
        line = l
        val (facts, s) = l.snapshot()
        snap = s
        val label = when (facts.netType) {
            "wifi" -> "Wi-Fi"
            "cell" -> operatorName(facts.operator)
            "ethernet" -> "Ethernet"
            else -> tr("شبکه", "Network")
        }

        val history = ScoutStore.history(profile.key)
        val base = resume?.takeIf { it.netKey == profile.key }
        val run0 = (base?.copy(finishedAt = null, end = null, pass = Pass.QUICK, paused = paused ?: base.paused)
            ?: ScoutRun(id = "s" + System.currentTimeMillis().toString(36), netKey = profile.key, netLabel = label,
                startedAt = System.currentTimeMillis(), paused = paused))
            .let { if (base == null) it.copy(line = facts) else it }

        // The plan: every registered method gets a row; the ones that cannot run are answered now.
        val planned = ArrayList<Planned>()
        val methods = LinkedHashMap<String, MethodResult>()
        for (a in ScoutRegistry.ALL) {
            val prev = run0.methods[a.id]
            if (only != null && a.id !in only) { prev?.let { methods[a.id] = it }; continue }
            val ready = runCatching { a.readiness(app) }.getOrElse { Readiness.NeedsSetup(it.message ?: "?") }
            when {
                ready is Readiness.NeedsSetup -> methods[a.id] = MethodResult(a.id, ScoutStatus.NEEDS_SETUP, ready.reason)
                ready is Readiness.NotInstalled -> methods[a.id] = MethodResult(a.id, ScoutStatus.NOT_INSTALLED, ready.reason)
                !a.built -> methods[a.id] = MethodResult(a.id, ScoutStatus.UPCOMING, tr("تست این روش در مرحلهٔ بعدی Scout اضافه می‌شود", "Testing this method comes in Scout's next stage"))
                else -> {
                    val all = runCatching { a.variants(app, s) }.getOrDefault(emptyList())
                    // Smart order: the variant that worked here last time runs in the quick pass.
                    val good = ScoutStore.lastGoodVariant(profile.key, a.id)?.takeIf { g -> all.any { it.id == g && it.notApplicable == null && it.needsSetup == null } }
                    val ordered = if (good == null) all else all.map { it.copy(quick = it.id == good) }
                    ordered.forEach { definitions[a.id to it.id] = it }
                    val kept = prev?.variants.orEmpty().filter { it.status.finished && it.status != ScoutStatus.CANCELLED && it.status != ScoutStatus.INCONCLUSIVE }
                    val instant = ordered.filter { v -> kept.none { it.id == v.id } && (v.notApplicable != null || v.needsSetup != null) }.map { v ->
                        VariantResult(v.id, v.label, v.fam,
                            if (v.needsSetup != null) ScoutStatus.NEEDS_SETUP else ScoutStatus.NOT_APPLICABLE,
                            reason = v.needsSetup ?: v.notApplicable, at = System.currentTimeMillis())
                    }
                    val todo = ordered.filter { v -> kept.none { it.id == v.id } && instant.none { it.id == v.id } }
                    val variants = kept + instant
                    val pending = todo.size + if (a.discoversVariants) 1 else 0
                    methods[a.id] = MethodResult(a.id, ScoutRules.aggregate(variants, pending), variants = variants, pending = pending)
                    if (pending > 0) planned += Planned(a, todo)
                }
            }
        }
        _ui.update {
            // Quick only: one variant per method, and no line diagnosis to wait for.
            val tasks = if (quickOnly) planned.sumOf { p -> p.variants.count { v -> v.quick } } else
                planned.sumOf { p -> p.variants.size + if (p.adapter.discoversVariants) 1 else 0 } + 1
            it.copy(run = run0.copy(methods = methods), tasksTotal = tasks, tasksDone = 0, startedAt = System.currentTimeMillis())
        }
        save(force = true)

        // History first: what worked on this network before is tried first, so the first healthy
        // answer comes quickly; then the registry's order.
        planned.sortBy { p -> history[p.adapter.id]?.let { (ok, n) -> if (n > 0) -ok.toDouble() / n else 0.0 } ?: 0.0 }

        val hb = Heartbeat(app, s.network)
        pulse = hb
        val g = NetGuard(app, profile.key) { reason -> stop(reason) }
        guard = g
        g.register()
        try {
            coroutineScope {
                var beats = launch { hb.loop { down -> _ui.update { it.copy(lineDown = down) } } }
                val diag = launch {
                    runCatching { l.run(s) }.onFailure { if (it is CancellationException) throw it }
                    // Path MTU last: a few dozen tiny pings, and only where ICMP answers at all.
                    runCatching { l.mtu(icmpAnswers = current().line.icmpMs != null) }.onFailure { if (it is CancellationException) throw it }
                    tick()
                }
                suspend fun quietSpeed() {
                    ScoutTiming.exclusiveSpeed(diag, beats) {
                        mutate { it.copy(pass = Pass.SPEED) }
                        speedRound()
                    }
                    beats = launch { hb.loop { down -> _ui.update { it.copy(lineDown = down) } } }
                }
                runPass(planned) { p -> runMethod(p, quick = true) }
                if (quickOnly) {
                    // The question was only "does it get through here at all": answered.
                    diag.cancel()
                } else {
                    // A single method's retest gets its own sample, not the whole round again.
                    quietSpeed()
                    mutate { it.copy(pass = Pass.DEEP) }
                    runPass(planned) { p -> runMethod(p, quick = false) }
                    quietSpeed()
                    diag.join()
                }
                beats.cancel()
            }
            endReason = EndReason.DONE
        } finally {
            g.unregister()
        }
    }

    /** Methods that share no lane run side by side (at most 3, 2 on a small phone); the rest wait their turn. */
    private suspend fun runPass(planned: List<Planned>, body: suspend (Planned) -> Unit) = coroutineScope {
        val laneLocks = Lane.entries.associateWith { Mutex() }
        val heavy = Semaphore(if (lowRam()) 2 else 3)
        planned.map { p ->
            launch {
                val locks = p.adapter.lanes.sorted().map { laneLocks.getValue(it) }
                locks.forEach { it.lock() }
                try {
                    heavy.withPermit { body(p) }
                } finally {
                    locks.asReversed().forEach { it.unlock() }
                }
            }
        }.joinAll()
    }

    private fun lowRam(): Boolean = runCatching {
        (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    }.getOrDefault(false)

    private suspend fun runMethod(p: Planned, quick: Boolean) {
        val id = p.adapter.id
        if (!quick && p.adapter.discoversVariants) {
            method(id) { it.copy(step = Step.PREPARING, stepNote = tr("دریافت همهٔ خروجی‌ها…", "Discovering all exits…")) }
            try {
                val discovered = withTimeoutOrNull(600_000) { p.adapter.discover(kit(id)) } ?: error("discovery_timeout")
                val known = p.variants.map { it.id }.toSet() + current().methods[id]?.variants.orEmpty().map { it.id }
                val extra = discovered.distinctBy { it.id }.filter { it.id !in known }
                discovered.forEach { definitions[id to it.id] = it }
                val refreshed = discovered.associateBy { it.id }
                p.variants = (p.variants + extra).map { refreshed[it.id] ?: it }
                addTasks(extra.size)
                method(id) { it.copy(pending = (it.pending - 1 + extra.size).coerceAtLeast(0)) }
                tick()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                record(id, VariantResult("__discovery", tr("فهرست خروجی‌ها", "Exit catalogue"), status = ScoutStatus.INCONCLUSIVE,
                    reason = tr("فهرست کامل دریافت نشد؛ نتیجه فقط برای خروجی‌های آزموده‌شده است", "The full list was unavailable; results cover tested exits only"), at = System.currentTimeMillis()), p, replace = false)
            }
        }
        val done = current().methods[id]?.variants.orEmpty().map { it.id }.toSet()
        val todo = ScoutRules.pendingVariants(p.variants, done, quick)
        if (todo.isEmpty()) { finishMethod(id); return }

        // The full pass of a method with many variants: one sweep for a yes/no each, then the best
        // of them measured in full if the method has no fully measured healthy variant yet.
        if (!quick && todo.size > 1) {
            val kit = kit(id)
            method(id) { it.copy(status = ScoutStatus.CHECKING, step = Step.MEASURING) }
            val swept = runCatching { p.adapter.sweep(kit, todo) }.getOrElse { if (it is CancellationException) throw it; null }
            if (swept != null) {
                for (v in todo) {
                    val r = swept[v.id] ?: VariantResult(v.id, v.label, v.fam, ScoutStatus.INCONCLUSIVE, tr("نتیجه‌ای نیامد", "No result"), at = System.currentTimeMillis(), full = false)
                    record(id, r, p)
                }
                val m = current().methods[id]
                if (m != null && m.variants.none { it.full && it.status.healthy }) {
                    val best = m.variants.filter { !it.full && it.status.healthy }.minWithOrNull(
                        compareBy<VariantResult> { ScoutRules.rank(it.status) }.thenBy { it.numbers.latencyMs ?: Long.MAX_VALUE })
                    val v = best?.let { b -> p.variants.firstOrNull { it.id == b.id } }
                    if (v != null) {
                        addTasks(1)
                        record(id, runVariant(p.adapter, v), p, replace = true)
                    }
                }
                finishMethod(id)
                return
            }
        }
        for (v in todo) {
            // A sibling that already carried data answers this variant's question: not run again.
            val covered = v.unneededIf?.let { sib -> current().methods[id]?.variants?.firstOrNull { it.id == sib }?.takeIf { it.status.healthy } }
            record(id, when {
                v.notApplicable != null || v.needsSetup != null ->
                    VariantResult(v.id, v.label, v.fam, if (v.needsSetup != null) ScoutStatus.NEEDS_SETUP else ScoutStatus.NOT_APPLICABLE,
                        reason = v.needsSetup ?: v.notApplicable, at = System.currentTimeMillis())
                covered != null -> VariantResult(v.id, v.label, v.fam, ScoutStatus.NOT_APPLICABLE,
                    reason = tr("لازم نشد: «${covered.label}» کار کرد", "Not needed: «${covered.label}» works"), at = System.currentTimeMillis())
                else -> runVariant(p.adapter, v)
            }, p)
        }
        finishMethod(id)
    }

    private fun record(methodId: String, r: VariantResult, p: Planned, replace: Boolean = false) {
        method(methodId) { m ->
            val variants = m.variants.filter { it.id != r.id } + r
            val pending = if (replace) m.pending else (m.pending - 1).coerceAtLeast(0)
            m.copy(variants = variants, pending = pending, status = ScoutRules.aggregate(variants, pending), reason = reasonOf(variants, pending))
        }
        save(force = true)
        tick()
    }

    private fun finishMethod(id: String) = method(id) { m ->
        val st = ScoutRules.aggregate(m.variants, m.pending)
        m.copy(status = st, step = null, stepNote = null, reason = reasonOf(m.variants, m.pending))
    }

    private fun reasonOf(variants: List<VariantResult>, pending: Int): String? {
        val best = ScoutRules.representative(variants) ?: return null
        if (ScoutRules.aggregate(variants, pending) == ScoutStatus.WAITING && variants.isNotEmpty())
            return tr("حالت اول نتیجه نداد؛ بقیهٔ حالت‌ها در بررسی کامل", "The first mode did not work; the rest come in the full check")
        return best.reason
    }

    private fun kit(methodId: String) = ScoutKit(app, snap!!) { step, note ->
        method(methodId) { it.copy(status = if (it.status.finished && it.status.healthy) it.status else ScoutStatus.CHECKING, step = step, stepNote = note) }
    }

    /** One variant, retried once if the line itself went away while it ran (a line outage is never a method's failure). */
    private suspend fun runVariant(a: ScoutAdapter, v: Variant): VariantResult {
        val hb = pulse
        repeat(2) { attempt ->
            val downBefore = hb?.downEvents ?: 0
            val r = attemptVariant(a, v)
            val troubled = hb != null && (hb.downEvents != downBefore || hb.down)
            if (r.status.healthy || !troubled || attempt == 1) return r
            method(a.id) { it.copy(status = ScoutStatus.CHECKING, stepNote = tr("اینترنت قطع شد؛ صبر تا برگردد…", "The line dropped; waiting for it…")) }
            if (!hb!!.awaitUp(60_000)) { stop(EndReason.LINE_DOWN); throw CancellationException("line down") }
        }
        return VariantResult(v.id, v.label, v.fam, ScoutStatus.INCONCLUSIVE, at = System.currentTimeMillis())
    }

    private suspend fun attemptVariant(a: ScoutAdapter, v: Variant): VariantResult {
        val kit = kit(a.id)
        var step = Step.PREPARING
        kit.step(Step.PREPARING, v.label.takeIf { it.isNotBlank() })
        fun fail(status: ScoutStatus, reason: String, at: Step, detail: String? = null) =
            VariantResult(v.id, v.label, v.fam, status, reason, at, at = System.currentTimeMillis(), detail = detail)

        val outcome = withTimeoutOrNull(v.budgetMs) {
            if (a.evidence == Evidence.HANDSHAKE) {
                step = Step.CONNECTING
                kit.step(step)
                return@withTimeoutOrNull try {
                    a.handshake(kit, v)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { fail(ScoutStatus.INCONCLUSIVE, tr("خطای داخلی تست", "The tester failed"), step) }
            }
            val prepared = try {
                a.prepare(kit, v)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "${a.id}/${v.id} prepare", e)
                Prepared.Failed(ScoutStatus.INCONCLUSIVE, tr("خطای داخلی تست", "The tester failed"))
            }
            when (prepared) {
                is Prepared.Failed -> fail(prepared.status, prepared.reason, prepared.at)
                is Prepared.Done -> prepared.result.copy(id = v.id, label = v.label, fam = v.fam, at = System.currentTimeMillis(), full = false)
                is Prepared.Ready -> {
                    step = Step.CONNECTING
                    kit.step(Step.CONNECTING, null)
                    // Downloading an engine or making a free account is setup, not connection time.
                    val t0 = System.currentTimeMillis()
                    val served = try {
                        a.serve(kit, prepared) { port -> step = Step.MEASURING; measure(a, v, port, t0, kit, prepared) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.w(TAG, "${a.id}/${v.id} serve", e)
                        Served.Failed(tr("خطای داخلی تست", "The tester failed"), inconclusive = true)
                    }
                    when (served) {
                        is Served.Ok -> served.value.also { r -> if (r.status.healthy) keepHandle(a.id, v.id, prepared, r) }
                        is Served.Failed -> fail(if (served.inconclusive) ScoutStatus.INCONCLUSIVE else ScoutStatus.FAILED, served.reason, Step.CONNECTING, detailOf(prepared))
                    }
                }
            }
        }
        return outcome ?: fail(ScoutStatus.FAILED, tr("در زمان مقرر به نتیجه نرسید", "No result in the time allowed"), step)
    }

    /** The shared yardstick, through the method's own local port. Identical for every method. */
    private suspend fun measure(a: ScoutAdapter, v: Variant, port: Int, t0: Long, kit: ScoutKit, prep: Prepared.Ready): VariantResult {
        kit.step(Step.MEASURING, tr("تأیید عبور داده…", "Checking data passes…"))
        // The first request may carry the server's own handshake: one more chance before "no data".
        val first = io { ScoutNet.https204(port, null, ScoutNet.VERIFY_HOST, 10_000) }
            ?: io { ScoutNet.https204(port, null, ScoutNet.VERIFY_HOST, 10_000) }
            ?: return VariantResult(v.id, v.label, v.fam, ScoutStatus.FAILED, tr("وصل شد ولی داده‌ای رد نشد", "It connected, but no data passed"),
                Step.CONNECTING, detail = detailOf(prep), at = System.currentTimeMillis())
        val connectMs = System.currentTimeMillis() - t0
        val filtered = if (a.kind == MethodKind.ANTISANCTION) Tri.UNKNOWN else {
            kit.step(Step.MEASURING, tr("باز کردن یوتیوب…", "Opening YouTube…"))
            // Twice before «باز نشد»: one slow answer on a busy tunnel is not a filtered site.
            val opened = io { ScoutNet.https204(port, null, ScoutNet.FILTERED_HOST, 8_000) }
                ?: io { ScoutNet.https204(port, null, ScoutNet.FILTERED_HOST, 12_000) }
            if (opened != null) Tri.YES else Tri.NO
        }
        kit.step(Step.MEASURING, tr("سنجش تأخیر…", "Measuring delay…"))
        val lat = io { ScoutNet.latencyTrain(port, 8, 6_000) }
        kit.step(Step.MEASURING, tr("سنجش پایداری…", "Measuring stability…"))
        val stab = ArrayList<Long?>()
        repeat(STABILITY_TRIES) { i ->
            if (i > 0) delay(STABILITY_GAP_MS)
            stab += io { ScoutNet.https204(port, null, ScoutNet.VERIFY_HOST, 8_000) }?.ms
        }
        val country = if (a.kind == MethodKind.TUNNEL || a.kind == MethodKind.ANTISANCTION) {
            kit.step(Step.MEASURING, tr("پرسیدن کشور خروجی…", "Asking the exit country…"))
            io { ScoutNet.exitCountry(port, 6_000) }
        } else null
        // The speed sample now, while this connection is up: the speed round used to bring every
        // healthy method up a second time just for it (Geph bootstrapping again minutes after it had
        // worked: «دوباره ازش تست میگیره», user report 2026-10-11). One download at a time, so methods
        // tested side by side do not share the line for it.
        val sampled = a.evidence == Evidence.DATA
        val speed = if (sampled) speedLock.withLock {
            kit.step(Step.MEASURING, tr("نمونهٔ سرعت…", "Speed sample…"))
            val bytes = if (snap?.cellular == true) 512 * 1024 else 1024 * 1024
            withTimeoutOrNull(25_000) { io { ScoutNet.download(port, null, ScoutNet.SPEED_URL, bytes, 20_000) } }
        } else null
        val n = Numbers(
            connectMs = connectMs,
            latencyMs = ScoutNet.median(lat) ?: ScoutNet.median(stab.filterNotNull()) ?: first.ms,
            latencyP95Ms = ScoutNet.p95(lat).takeIf { lat.size >= 4 },
            stabilityOk = stab.count { it != null }, stabilityTotal = stab.size,
            filtered = filtered, exitCountry = country,
            mbps = speed?.mbps, speedBytes = speed?.bytes, speedAttempted = sampled,
        )
        val weak = ScoutRules.weakness(a.kind, n, a.requiresForeignExit(v.id))
        return VariantResult(v.id, v.label, v.fam, if (weak == null) ScoutStatus.WORKS else ScoutStatus.WEAK, weak?.let(::weakText),
            numbers = n, detail = detailOf(prep), at = System.currentTimeMillis())
    }

    private fun detailOf(prep: Prepared.Ready): String? = (prep.handle as? LiveDetail)?.liveDetail ?: prep.detail

    private suspend fun <T> io(block: () -> T): T = runInterruptible(Dispatchers.IO) { block() }

    fun weakText(w: ScoutRules.Weak): String = when (w) {
        ScoutRules.Weak.NO_FILTERED -> tr("وصل شد ولی سایت‌های فیلتر (یوتیوب) باز نشد", "Connects, but filtered sites (YouTube) did not open")
        ScoutRules.Weak.IRAN_EXIT -> tr("وصل شد ولی خروجی‌اش ایران است", "Connects, but it comes out in Iran")
        ScoutRules.Weak.UNSTABLE -> tr("ناپایدار: بعضی درخواست‌ها جواب نگرفت", "Unstable: some requests got no answer")
        ScoutRules.Weak.SLOW_RESPONSE -> tr("خیلی کند جواب می‌دهد", "Answers very slowly")
        ScoutRules.Weak.SLOW_SPEED -> tr("سرعت خیلی کم", "Very low speed")
    }

    // ---- the speed round -------------------------------------------------------------------

    /**
     * A short throughput sample, strictly one method at a time and nothing else measuring, fastest
     * responders first; the line's own speed before and after, so a line that swung during the
     * round is marked «تقریبی» rather than blamed on whichever method went last.
     */
    /** One speed download at a time across the methods tested side by side. */
    private val speedLock = Mutex()

    private suspend fun speedRound() {
        val cell = snap?.cellular == true
        val bytes = if (cell) 512 * 1024 else 1024 * 1024
        val cands = ScoutRules.speedCandidates(current().methods.values.filter {
            ScoutRegistry.byId(it.id)?.evidence == Evidence.DATA
        // Only fully measured variants: a yes/no from a sweep or an engine's own test has no local
        // port to sample, and preparing it again would repeat that whole test for nothing.
        }).filter { (methodId, variant) -> variant.full && definitions.containsKey(methodId to variant.id) }
            .sortedBy { it.second.numbers.latencyMs ?: Long.MAX_VALUE }
        val l = line ?: return
        // The line's own speed even when no method is left to sample: it was skipped then, and the
        // card's «سرعت مستقیم» stayed on «…» for good.
        if (cands.isEmpty()) {
            val bytes0 = if (cell) 512 * 1024 else 1024 * 1024
            if (current().line.mbpsStart == null) {
                val v = l.directMbps(bytes0)
                mutate { r -> r.copy(line = r.line.copy(mbpsStart = v)) }
            } else if (current().line.mbpsEnd == null) {
                val v = l.directMbps(bytes0)
                mutate { r -> r.copy(line = r.line.copy(mbpsEnd = v)) }
            }
            return
        }
        addTasks(cands.size + 2)
        val before = l.directMbps(bytes).also { v -> mutate { r -> r.copy(line = r.line.copy(mbpsStart = v)) }; tick() }
        val measured = ArrayList<String>()
        for ((methodId, variant) in cands) {
            val a = ScoutRegistry.byId(methodId) ?: continue
            val variantId = variant.id
            val definition = definitions[methodId to variantId] ?: continue
            method(methodId) { it.copy(step = Step.MEASURING, stepNote = variant.label + " · " + tr("نمونهٔ سرعت…", "Speed sample…")) }
            val served = withTimeoutOrNull(a.speedBudgetMs) {
                runCatching {
                    val ready = handles[methodId to variantId] ?: (a.prepare(kit(methodId), definition) as? Prepared.Ready)
                    if (ready == null) null else a.serve(kit(methodId), ready) { port ->
                        withTimeoutOrNull(20_000) { io { ScoutNet.download(port, null, ScoutNet.SPEED_URL, bytes, 20_000) } }
                    }
                }.getOrElse { if (it is CancellationException) throw it; null }
            }
            val speed = (served as? Served.Ok)?.value
            method(methodId) { mr ->
                val variants = mr.variants.map { vr ->
                    if (vr.id != variantId) vr else {
                        val n = vr.numbers.copy(mbps = speed?.mbps, speedBytes = speed?.bytes, speedAttempted = true)
                        val weak = ScoutRules.weakness(a.kind, n, a.requiresForeignExit(variantId))
                        vr.copy(numbers = n, status = if (weak == null) ScoutStatus.WORKS else ScoutStatus.WEAK, reason = weak?.let(::weakText))
                    }
                }
                mr.copy(variants = variants, status = ScoutRules.aggregate(variants, mr.pending), step = null, stepNote = null, reason = reasonOf(variants, mr.pending))
            }
            if (speed != null) measured += methodId
            save(force = true)
            tick()
        }
        run {
            val after = l.directMbps(bytes)
            mutate { r -> r.copy(line = r.line.copy(mbpsEnd = after)) }
            tick()
            if (before != null && after != null && (before / after > 2 || after / before > 2)) {
                for (id in measured) method(id) { m -> m.copy(variants = m.variants.map { it.copy(numbers = it.numbers.copy(speedApprox = it.numbers.mbps != null)) }) }
            }
        }
    }

    // ---- ending ----------------------------------------------------------------------------

    private suspend fun finish() {
        val run = _ui.value.run
        pulse = null
        runCatching { guard?.unregister() }
        guard = null
        if (run != null) {
            val closed = warpFamilyNotes(closeRun(run, endReason, interrupted = false, quickOnly = quickOnly)).copy(dataBytes = (uidBytes() - bytesAtStart).takeIf { bytesAtStart > 0 && it >= 0 })
            _ui.update { it.copy(run = closed) }
            save(force = true)
        }
        handles.clear(); definitions.clear()
        _ui.update { it.copy(running = false, stopping = false, lineDown = false) }
        Log.i(TAG, "run ended: $endReason")
    }

    /**
     * The three WARP tiles hand a connect to each other on a line that closes one of them
     * (WarpLadder): a tap on «ماسک» where MASQUE is closed but WireGuard works still connects. The
     * row stays «کار نمی‌کند» -- that is the truth about the transport -- but says what the tap does,
     * so nobody is told the tile is useless and then finds it connecting.
     */
    private fun warpFamilyNotes(r: ScoutRun): ScoutRun {
        val ids = mapOf("masque" to com.mlmvpn.scanner.ui.tunnel.Transport.MASQUE.id,
            "wireguard" to com.mlmvpn.scanner.ui.tunnel.Transport.WIREGUARD.id,
            "gool" to com.mlmvpn.scanner.ui.tunnel.Transport.GOOL.id)
        val methods = LinkedHashMap(r.methods)
        for ((value, id) in ids) {
            val m = methods[id] ?: continue
            if (m.status != ScoutStatus.FAILED) continue
            val via = com.mlmvpn.core.tunnel.WarpLadder.successors(value)
                .firstOrNull { s -> r.methods[ids[s]]?.status?.healthy == true } ?: continue
            val name = com.mlmvpn.core.tunnel.CoreConfig.chainOuterLabel(via)
            methods[id] = m.copy(reason = tr("خودش روی این خط باز نشد؛ ولی با زدن همین کاشی، برنامه خودکار از $name وصل می‌شود",
                "Closed on this line itself; tapping this tile still connects, through $name automatically"))
        }
        return r.copy(methods = methods)
    }

    /** A run as it ends: anything still waiting or under test says why it has no answer. */
    private fun closeRun(r: ScoutRun, end: EndReason, interrupted: Boolean, quickOnly: Boolean = false): ScoutRun {
        val now = System.currentTimeMillis()
        val why = if (quickOnly && end == EndReason.DONE) {
            tr("فقط بررسی سریع انجام شد؛ «دوباره تست کن» همهٔ حالت‌ها را می‌سنجد", "Only the quick check ran; «Retest» checks every mode")
        } else when (end) {
            EndReason.NETWORK_CHANGED -> tr("شبکه عوض شد", "The network changed")
            EndReason.LINE_DOWN -> tr("اینترنت قطع شد", "The line dropped")
            EndReason.VPN_STARTED -> tr("یک VPN روشن شد", "A VPN came up")
            EndReason.OTHER_SCAN -> tr("اسکن آی‌پی شروع شد", "An IP scan started")
            EndReason.INTERRUPTED -> tr("بررسی نیمه‌کاره ماند", "The check did not finish")
            else -> tr("لغو شد", "Cancelled")
        }
        val methods = r.methods.mapValues { (_, m) ->
            if (m.status.finished && m.pending == 0 && m.step == null) return@mapValues m
            val variants = m.variants.map {
                if (it.status == ScoutStatus.CHECKING) it.copy(status = ScoutStatus.INCONCLUSIVE, reason = tr("تست کامل نشد", "The test did not finish")) else it
            }
            val st = when {
                variants.any { it.status == ScoutStatus.WORKS } -> ScoutStatus.WORKS
                variants.any { it.status == ScoutStatus.WEAK } -> ScoutStatus.WEAK
                m.status == ScoutStatus.CHECKING && interrupted -> ScoutStatus.INCONCLUSIVE
                else -> ScoutStatus.CANCELLED
            }
            m.copy(status = st, variants = variants, step = null, stepNote = null,
                reason = if (st.healthy) m.reason else if (st == ScoutStatus.INCONCLUSIVE) tr("تست کامل نشد", "The test did not finish") else why)
        }
        return r.copy(methods = LinkedHashMap(methods), finishedAt = now, end = end, pass = Pass.DONE)
    }

    private fun uidBytes(): Long = runCatching {
        val uid = android.os.Process.myUid()
        TrafficStats.getUidRxBytes(uid) + TrafficStats.getUidTxBytes(uid)
    }.getOrDefault(0L).takeIf { it >= 0 } ?: 0L

    fun operatorName(op: String?): String = when (op) {
        "MCI" -> tr("همراه اول", "MCI")
        "MTN" -> tr("ایرانسل", "Irancell")
        "RIGHTEL" -> tr("رایتل", "Rightel")
        else -> tr("دادهٔ همراه", "Mobile data")
    }

    // ---- estimates --------------------------------------------------------------------------

    /** Seconds and megabytes a run here should take: the last finished run here, else the methods' own estimates. */
    fun estimate(context: Context): Pair<Int, Double> {
        val key = currentNetKey(context)
        val last = key?.let { ScoutStore.latestFor(it) }?.takeIf { it.end == EndReason.DONE && it.finishedAt != null }
        if (last != null) return (((last.finishedAt!! - last.startedAt) / 1000).toInt()) to ((last.dataBytes ?: 0L) / 1_048_576.0).coerceAtLeast(1.0)
        val cell = GameNetwork.pick(context)?.isCellular == true
        val ready = ScoutRegistry.ALL.filter { it.built && runCatching { it.readiness(context) is Readiness.Ready }.getOrDefault(false) }
        val sec = 25 + ready.sumOf { it.estimateSec }
        val mb = 1.0 + ready.sumOf { it.estimateMb } + (if (cell) 0.5 * 3 + 1.0 else 1.0 * 6 + 2.0)
        return sec to mb
    }

    private const val STABILITY_TRIES = 6
    private const val STABILITY_GAP_MS = 1_200L
}

/**
 * A small direct request every few seconds: is the line itself still up? A test that failed while
 * the line was down is run again once it is back -- never written down as the method's failure.
 */
internal class Heartbeat(private val context: Context, private val network: Network?) {
    @Volatile var down = false
        private set
    @Volatile var downEvents = 0
        private set
    @Volatile private var downSince = 0L

    private val targets: List<Pair<() -> InetAddress?, Int>> = listOf(
        { InetAddress.getByName("1.1.1.1") } to 443,
        { InetAddress.getByName("8.8.8.8") } to 443,
        { runCatching { ScoutNet.resolve(network, "www.aparat.com").firstOrNull() }.getOrNull() } to 443,
    )

    private fun beat(): Boolean = targets.any { (addr, port) -> addr()?.let { ScoutNet.tcp(network, it, port, 1_500) } != null }

    suspend fun loop(onChange: (Boolean) -> Unit) {
        var misses = 0
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val ok = runInterruptible(Dispatchers.IO) { beat() }
            if (ok) {
                misses = 0
                if (down) { down = false; onChange(false) }
            } else if (++misses >= 2 && !down) {
                down = true; downEvents++; downSince = System.currentTimeMillis(); onChange(true)
            }
            if (down && System.currentTimeMillis() - downSince > 90_000) ScoutEngine.stop(EndReason.LINE_DOWN)
            delay(if (down) 2_000 else 4_000)
        }
    }

    suspend fun awaitUp(withinMs: Long): Boolean = withTimeoutOrNull(withinMs) {
        while (down) delay(500)
        true
    } ?: false
}

/**
 * Watches the network for the run (plan 3-11): another network, no network, or a VPN coming up ends
 * the run safely -- results so far stay with the network they were measured on.
 */
internal class NetGuard(private val context: Context, private val netKey: String, private val onEnd: (EndReason) -> Unit) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var pending: Job? = null
    @Volatile private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) onEnd(EndReason.VPN_STARTED) else changed()
        }
    }

    fun register() {
        runCatching { cm.registerDefaultNetworkCallback(callback); registered = true }
    }

    fun unregister() {
        if (registered) runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
        pending?.cancel()
    }

    /** Debounced: a Wi-Fi that blips and comes back as the same network is not a change. */
    private fun changed() {
        pending?.cancel()
        pending = scope.launch {
            delay(2_500)
            if (ScoutVpn.anyVpn(context)) { onEnd(EndReason.VPN_STARTED); return@launch }
            val now = runCatching { if (GameNetwork.pick(context) == null) null else CfNetEnv.profile(context).key }.getOrNull()
            if (now != null && now != netKey) onEnd(EndReason.NETWORK_CHANGED)
            // No network at all is the heartbeat's business: it waits, then ends the run as LINE_DOWN.
        }
    }
}
