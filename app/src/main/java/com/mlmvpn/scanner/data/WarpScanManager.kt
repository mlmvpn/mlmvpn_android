package com.mlmvpn.scanner.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.mlmvpn.core.warp.WarpAccountManager
import com.mlmvpn.core.warp.WarpBatchTester
import com.mlmvpn.core.warp.WarpIpGenerator
import com.mlmvpn.scanner.utils.NetworkWatchdog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A scanned WARP endpoint candidate + its measured ping. Used by the game-booster WARP scanner. */
data class EndpointModel(
    val id: String,
    val ip: String,
    val port: Int,
    val pingMs: Int?,
    val isSelected: Boolean = false
)

/**
 * Owns the WARP endpoint discovery + WireGuard-ping-test pipeline as a top-level singleton
 * (mirrors ScannerManager for the Cloudflare IP scanner) so a scan keeps running and its
 * results survive tab navigation, and are persisted to disk so they survive an app restart.
 */
object WarpScanManager {

    private const val TAG = "WarpScanManager"

    enum class ScanPhase { IDLE, DISCOVERING, TESTING, PAUSED, DONE }

    // Concurrency: TCP reachability checks are cheap, WireGuard ping tests do a real UDP round trip.
    private const val DISCOVERY_CONCURRENCY = 300
    private const val DISCOVERY_BATCH_SIZE = 300
    private const val DISCOVERY_TIMEOUT_MS = 800
    private const val TEST_CONCURRENCY = 50
    private const val EMPTY_ROUNDS_BEFORE_GIVING_UP = 5 // pool likely exhausted
    private const val NETWORK_DOWN_PAUSE_MS = 4000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scanJob: Job? = null
    private val stopRequested = AtomicBoolean(false)
    // Which sub-phase pause() caught the scan in, so resume() knows whether to re-enter discovery
    // or ping-testing. Lost on a full app restart (in-memory only) -- resume() falls back to a
    // heuristic in that case, since candidates/results themselves are persisted to disk anyway.
    private var pausedAtPhase: ScanPhase? = null

    private val _phase = MutableStateFlow(ScanPhase.IDLE)
    val phase = _phase.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _discoveredCount = MutableStateFlow(0)
    val discoveredCount = _discoveredCount.asStateFlow()

    private val _attemptedCount = MutableStateFlow(0)
    val attemptedCount = _attemptedCount.asStateFlow()

    private val _testProgress = MutableStateFlow(Pair(0, 0))
    val testProgress = _testProgress.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused = _isPaused.asStateFlow()

    private val _pauseMessage = MutableStateFlow<String?>(null)
    val pauseMessage = _pauseMessage.asStateFlow()

    private val _endpoints = MutableStateFlow<List<EndpointModel>>(emptyList())
    val endpoints = _endpoints.asStateFlow()

    private var candidateEndpoints = CopyOnWriteArrayList<WarpBatchTester.BatchEndpoint>()
    // Once the user manually picks an endpoint, stop re-picking "the best" for them as new
    // (possibly faster) results stream in -- respect their choice for the rest of this scan.
    private val hasManualSelection = AtomicBoolean(false)

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("warp_scan_prefs", Context.MODE_PRIVATE)
        loadCandidates()
        loadResults()
    }

    private fun loadCandidates() {
        try {
            val raw = prefs?.getString("candidates", null) ?: return
            val arr = JSONArray(raw)
            val list = CopyOnWriteArrayList<WarpBatchTester.BatchEndpoint>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(WarpBatchTester.BatchEndpoint(o.getString("ip"), o.getInt("port"), 0))
            }
            candidateEndpoints = list
            _discoveredCount.value = list.size
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load persisted candidates", e)
        }
    }

    private fun persistCandidates() {
        try {
            val arr = JSONArray()
            candidateEndpoints.forEach { ep ->
                arr.put(JSONObject().apply { put("ip", ep.ip); put("port", ep.port) })
            }
            prefs?.edit()?.putString("candidates", arr.toString())?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist candidates", e)
        }
    }

    private fun loadResults() {
        try {
            val raw = prefs?.getString("results", null) ?: return
            val arr = JSONArray(raw)
            val list = mutableListOf<EndpointModel>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ip = o.getString("ip")
                val port = o.getInt("port")
                val ping = if (o.has("ping")) o.getInt("ping") else null
                list.add(EndpointModel(id = "$ip:$port", ip = ip, port = port, pingMs = ping))
            }
            _endpoints.value = autoSelectBest(list.sortedBy { it.pingMs ?: Int.MAX_VALUE })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load persisted results", e)
        }
    }

    private fun persistResults(list: List<EndpointModel>) {
        try {
            val arr = JSONArray()
            list.forEach { ep ->
                arr.put(JSONObject().apply {
                    put("ip", ep.ip)
                    put("port", ep.port)
                    if (ep.pingMs != null) put("ping", ep.pingMs)
                })
            }
            prefs?.edit()?.putString("results", arr.toString())?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist results", e)
        }
    }

    private fun autoSelectBest(list: List<EndpointModel>): List<EndpointModel> {
        if (list.isEmpty() || hasManualSelection.get()) return list
        val best = list.filter { it.pingMs != null }.minByOrNull { it.pingMs!! } ?: return list
        return list.map { it.copy(isSelected = it.id == best.id) }
    }

    /**
     * If the device is currently offline, mark the scan as paused (for the UI banner) and
     * suspend here until connectivity is back, so callers can just call this before each probe
     * instead of racing through a flood of instantly-failing tests during an outage.
     */
    private suspend fun ensureOnline(watchdog: NetworkWatchdog) {
        if (watchdog.isOnline.value) return
        _isPaused.value = true
        _pauseMessage.value = "اتصال اینترنت قطع شده � اسکن متوقف شد و بعد از وصل شدن دوباره ادامه پیدا می‌کند..."
        Log.w(TAG, "Paused: device has no internet connectivity")
        watchdog.waitUntilOnline()
        _isPaused.value = false
        _pauseMessage.value = null
        Log.d(TAG, "Resumed: connectivity is back")
    }

    /** Same idea as [ensureOnline] but triggered by a flood of instantly-failing probes rather than the OS's own (sometimes slow-to-update) connectivity signal -- catches flaky/degraded connections the watchdog doesn't flag as fully offline. */
    private suspend fun pauseForFlakyConnection() {
        _isPaused.value = true
        _pauseMessage.value = "اتصال اینترنت ضعیف یا ناپایدار است � چند لحظه صبر می‌کنیم..."
        delay(NETWORK_DOWN_PAUSE_MS)
        _isPaused.value = false
        _pauseMessage.value = null
    }

    fun toggleSelection(id: String) {
        hasManualSelection.set(true)
        _endpoints.value = _endpoints.value.map { it.copy(isSelected = it.id == id) }
    }

    /**
     * Injects a winning endpoint discovered by the WARP Connectivity Lab into the results list and
     * selects it, so the user can connect to it immediately from the WARP tab. Merges with any
     * existing results (keeps the lab winner selected).
     */
    fun addWinningEndpoint(context: Context, ip: String, port: Int, pingMs: Int?) {
        init(context)
        val id = "$ip:$port"
        val merged = (_endpoints.value.filterNot { it.id == id } +
            EndpointModel(id = id, ip = ip, port = port, pingMs = pingMs))
            .sortedBy { it.pingMs ?: Int.MAX_VALUE }
            .map { it.copy(isSelected = it.id == id) }
        hasManualSelection.set(true)
        _endpoints.value = merged
        persistResults(merged)
    }

    /** Manual one-off re-ping of a single row (from the refresh icon on each endpoint card). */
    fun updateSinglePing(id: String, pingMs: Int?) {
        val updated = _endpoints.value
            .map { if (it.id == id) it.copy(pingMs = pingMs) else it }
            .sortedBy { it.pingMs ?: Int.MAX_VALUE }
        _endpoints.value = updated
        persistResults(updated)
    }

    /**
     * Phase 1: continuously discover reachable WARP endpoints with no artificial cap.
     * targetCount <= 0 means "keep going until stopDiscoveryAndTest() is called" (or the
     * finite endpoint pool runs dry). Once discovery ends (by target, by user stop, or by
     * pool exhaustion) it automatically flows into the WireGuard ping test phase.
     */
    fun startDiscovery(
        context: Context,
        targetCount: Int,
        account: WarpAccountManager.WarpAccountData,
        reservedBytes: List<Int>
    ) {
        if (_isScanning.value) return
        init(context)
        stopRequested.set(false)
        hasManualSelection.set(false)
        pausedAtPhase = null
        _isScanning.value = true
        _phase.value = ScanPhase.DISCOVERING
        candidateEndpoints = CopyOnWriteArrayList()
        _discoveredCount.value = 0
        _attemptedCount.value = 0
        _endpoints.value = emptyList()

        // Keep the scan alive even if the app is swiped away or the screen locks -- see
        // WarpScanService for how it mirrors this scan's state into a foreground notification.
        WarpScanService.ensureRunning(context)

        val watchdog = NetworkWatchdog(context)
        watchdog.start()

        scanJob = scope.launch {
            try {
                runDiscoveryLoop(watchdog, targetCount, account, reservedBytes)
            } finally {
                watchdog.stop()
            }
        }
    }

    /**
     * The actual discovery while-loop, factored out of [startDiscovery] so [resume] can re-enter
     * it without resetting candidateEndpoints/counters -- it just keeps adding to whatever's
     * already there (which may have been loaded from disk after a full app restart).
     */
    private suspend fun runDiscoveryLoop(
        watchdog: NetworkWatchdog,
        targetCount: Int,
        account: WarpAccountManager.WarpAccountData,
        reservedBytes: List<Int>
    ) = coroutineScope {
        val seen = ConcurrentHashMap.newKeySet<String>()
        val semaphore = Semaphore(DISCOVERY_CONCURRENCY)
        var emptyRounds = 0
        val attempted = AtomicInteger(_attemptedCount.value)

        val autosave = launch {
            while (isActive) {
                delay(3000)
                persistCandidates()
            }
        }

        try {
            while (isActive && !stopRequested.get() && (targetCount <= 0 || candidateEndpoints.size < targetCount)) {
                // Check BEFORE spending a round on it: no point launching 300 doomed probes
                // if the device is already known to be offline.
                ensureOnline(watchdog)
                if (stopRequested.get()) break

                val batch = WarpIpGenerator.generateRandomEndpoints(DISCOVERY_BATCH_SIZE)
                val instantFailures = AtomicInteger(0)
                val jobs = batch.mapNotNull { endpointStr ->
                    val lastColon = endpointStr.lastIndexOf(':')
                    val ip = if (lastColon != -1) endpointStr.substring(0, lastColon) else endpointStr
                    if (!seen.add(ip)) return@mapNotNull null
                    val port = if (lastColon != -1) endpointStr.substring(lastColon + 1).toIntOrNull() ?: 2408 else 2408
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            // A quick, short-timeout real WireGuard handshake probe -- this is
                            // the same test phase 2 does, just with a tighter timeout, so an
                            // endpoint that "passes" phase 1 actually correlates with phase 2
                            // success (a plain TCP:80 check does NOT: almost any Cloudflare IP
                            // answers on port 80 regardless of whether it forwards WARP's UDP
                            // WireGuard traffic at all).
                            val attemptStart = System.currentTimeMillis()
                            val ping = WarpBatchTester.testWarpEndpoint(ip, port, reservedBytes, DISCOVERY_TIMEOUT_MS)
                            val elapsed = System.currentTimeMillis() - attemptStart
                            _attemptedCount.value = attempted.incrementAndGet()
                            if (ping != null) {
                                candidateEndpoints.add(WarpBatchTester.BatchEndpoint(ip, port, 0))
                                _discoveredCount.value = candidateEndpoints.size
                            } else if (elapsed < WarpBatchTester.INSTANT_FAILURE_MS) {
                                // Failed almost instantly instead of actually waiting out the
                                // timeout -- there was no real network path to try, so this
                                // wasn't a genuine test of the IP (see WarpBatchTester log tag
                                // "NOT A REAL TEST" in Logcat).
                                instantFailures.incrementAndGet()
                            }
                        }
                    }
                }
                if (jobs.isEmpty()) {
                    emptyRounds++
                    if (emptyRounds >= EMPTY_ROUNDS_BEFORE_GIVING_UP) {
                        Log.d(TAG, "WARP endpoint pool appears exhausted, stopping discovery")
                        break
                    }
                } else {
                    emptyRounds = 0
                    jobs.awaitAll()
                    val failed = instantFailures.get()
                    if (failed.toFloat() / jobs.size > 0.5f) {
                        // The watchdog may not have flagged this yet (e.g. a captive portal
                        // or degraded connection that still reports "connected"), but a wall
                        // of instant failures means the same thing in practice -- pause.
                        Log.w(TAG, "Instant-failure flood this round ($failed/${jobs.size}) even though watchdog says online=${watchdog.isOnline.value} -- treating as a flaky connection")
                        pauseForFlakyConnection()
                    }
                }
            }
        } finally {
            autosave.cancel()
            persistCandidates()
        }

        if (candidateEndpoints.isNotEmpty()) {
            runTesting(watchdog, account, reservedBytes)
        } else {
            _isScanning.value = false
            _phase.value = ScanPhase.DONE
        }
    }

    /** Cooperative stop: the running discovery coroutine notices this and moves on by itself. */
    fun stopDiscoveryAndTest() {
        stopRequested.set(true)
    }

    /** Re-run just the WireGuard ping test against the already-discovered candidate list. */
    fun retest(context: Context, account: WarpAccountManager.WarpAccountData, reservedBytes: List<Int>) {
        if (_isScanning.value || candidateEndpoints.isEmpty()) return
        init(context)
        hasManualSelection.set(false)
        pausedAtPhase = null
        _isScanning.value = true
        WarpScanService.ensureRunning(context)
        val watchdog = NetworkWatchdog(context)
        watchdog.start()
        scanJob = scope.launch {
            try {
                runTesting(watchdog, account, reservedBytes)
            } finally {
                watchdog.stop()
            }
        }
    }

    /**
     * True pause: unlike [stopDiscoveryAndTest]/[stopAll], this halts the scan exactly where it
     * is -- without discarding candidateEndpoints or already-tested results -- and remembers
     * which sub-phase it was in, so [resume] can pick back up there instead of starting over.
     * Combined with the autosave-to-disk that already happens during scanning, this survives the
     * app being closed completely, not just backgrounded.
     */
    fun pause() {
        if (!_isScanning.value) return
        pausedAtPhase = _phase.value.takeIf { it == ScanPhase.DISCOVERING || it == ScanPhase.TESTING }
        stopRequested.set(true)
        scanJob?.cancel()
        _isScanning.value = false
        _isPaused.value = false
        _pauseMessage.value = null
        _phase.value = ScanPhase.PAUSED
    }

    /**
     * Resumes a scan paused with [pause], continuing discovery or ping-testing (whichever it was
     * paused in) using the candidates/results already gathered. If the app was fully closed and
     * reopened, [pausedAtPhase] is gone (in-memory only) -- fall back to testing first if there
     * are discovered candidates that haven't been successfully ping-tested yet, otherwise keep
     * discovering; either way nothing already found is thrown away.
     */
    fun resume(context: Context, account: WarpAccountManager.WarpAccountData, reservedBytes: List<Int>) {
        if (_isScanning.value) return
        init(context)
        val untestedRemain = candidateEndpoints.size > _endpoints.value.size
        val resumePhase = pausedAtPhase ?: if (untestedRemain) ScanPhase.TESTING else ScanPhase.DISCOVERING
        pausedAtPhase = null
        stopRequested.set(false)
        _isScanning.value = true
        _phase.value = resumePhase
        WarpScanService.ensureRunning(context)

        val watchdog = NetworkWatchdog(context)
        watchdog.start()

        scanJob = scope.launch {
            try {
                if (resumePhase == ScanPhase.TESTING) {
                    runTesting(watchdog, account, reservedBytes)
                } else {
                    runDiscoveryLoop(watchdog, 0, account, reservedBytes)
                }
            } finally {
                watchdog.stop()
            }
        }
    }

    fun stopAll() {
        stopRequested.set(true)
        scanJob?.cancel()
        pausedAtPhase = null
        _isScanning.value = false
        _phase.value = ScanPhase.DONE
        _isPaused.value = false
        _pauseMessage.value = null
    }

    private suspend fun runTesting(watchdog: NetworkWatchdog, account: WarpAccountManager.WarpAccountData, reservedBytes: List<Int>) {
        _phase.value = ScanPhase.TESTING
        // Skip endpoints already successfully ping-tested (e.g. from before a pause/resume) --
        // seed `results` with them too so this run merges into the existing list instead of
        // wiping it out the moment the first new probe completes.
        val alreadyTestedKeys = _endpoints.value.map { it.id }.toSet()
        val toTest = candidateEndpoints.toList().filterNot { "${it.ip}:${it.port}" in alreadyTestedKeys }
        _testProgress.value = Pair(0, toTest.size)

        val results = CopyOnWriteArrayList<EndpointModel>(_endpoints.value)
        val completed = AtomicInteger(0)
        val instantFailures = AtomicInteger(0)
        val semaphore = Semaphore(TEST_CONCURRENCY)

        // تعداد نمونه‌ها برای هر endpoint � median می‌گیریم تا false-positive کم شه
        val samplesPerEndpoint = 3

        coroutineScope {
            val autosave = launch {
                while (isActive) {
                    delay(3000)
                    persistResults(results.sortedBy { it.pingMs ?: Int.MAX_VALUE })
                }
            }
            try {
                toTest.map { ep ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            ensureOnline(watchdog)
                            val attemptStart = System.currentTimeMillis()
                            // ── Multi-sample: چند پینگ و median ──
                            val pings = mutableListOf<Int>()
                            var hadInstantFailure = false
                            repeat(samplesPerEndpoint) {
                                val ping = WarpBatchTester.testWarpEndpoint(ep.ip, ep.port, reservedBytes)
                                if (ping != null) {
                                    pings.add(ping)
                                }
                            }
                            val elapsed = System.currentTimeMillis() - attemptStart

                            if (pings.isNotEmpty()) {
                                // median برای کاهش jitter
                                val medianPing = pings.sorted()[pings.size / 2]
                                results.add(EndpointModel(id = "${ep.ip}:${ep.port}", ip = ep.ip, port = ep.port, pingMs = medianPing))
                                _endpoints.value = autoSelectBest(results.sortedBy { it.pingMs ?: Int.MAX_VALUE })
                            } else if (elapsed < WarpBatchTester.INSTANT_FAILURE_MS) {
                                instantFailures.incrementAndGet()
                            }
                            _testProgress.value = Pair(completed.incrementAndGet(), toTest.size)
                        }
                    }
                }.awaitAll()
            } finally {
                autosave.cancel()
                persistResults(results.sortedBy { it.pingMs ?: Int.MAX_VALUE })
            }
        }

        if (toTest.isNotEmpty() && instantFailures.get().toFloat() / toTest.size > 0.5f) {
            Log.w(TAG, "WireGuard ping test result is unreliable: ${instantFailures.get()}/${toTest.size} probes failed instantly (no real network path), not because those IPs are actually bad. Check Logcat tag WarpBatchTester for \"NOT A REAL TEST\" lines and re-run once the connection is stable.")
        }

        // ── Auto-retry: اگر هیچ endpoint‌ی پیدا نشد، یک بار دیگه با reserved bytes و پورت متفاوت ──
        if (results.isEmpty() && toTest.isNotEmpty()) {
            Log.w(TAG, "هیچ endpoint‌ی جواب نداد � retry با reserved bytes و پورت جدید")
            val newReserved = com.mlmvpn.core.warp.WarpAntiDpi.generateReservedBytes()
            val newPorts = listOf(2408, 1701, 500, 4500, 894).shuffled()
            val retryEndpoints = toTest.take(20).mapIndexed { i, ep ->
                com.mlmvpn.core.warp.WarpBatchTester.BatchEndpoint(ep.ip, newPorts[i % newPorts.size], 0)
            }
            _testProgress.value = Pair(0, retryEndpoints.size)
            val completed2 = AtomicInteger(0)
            coroutineScope {
                retryEndpoints.map { ep ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val ping = WarpBatchTester.testWarpEndpoint(ep.ip, ep.port, newReserved)
                            if (ping != null) {
                                results.add(EndpointModel(id = "${ep.ip}:${ep.port}", ip = ep.ip, port = ep.port, pingMs = ping))
                                _endpoints.value = autoSelectBest(results.sortedBy { it.pingMs ?: Int.MAX_VALUE })
                            }
                            _testProgress.value = Pair(completed2.incrementAndGet(), retryEndpoints.size)
                        }
                    }
                }.awaitAll()
            }
            persistResults(results.sortedBy { it.pingMs ?: Int.MAX_VALUE })
        }

        _isScanning.value = false
        _phase.value = ScanPhase.DONE
    }
}
