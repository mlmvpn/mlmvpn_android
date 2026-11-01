package com.mlmvpn.scanner.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.mlmvpn.scanner.ui.ScannedIP
import com.mlmvpn.scanner.utils.NetworkWatchdog
import com.mlmvpn.scanner.utils.ScanPreflight

object ScannerManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scanJob: Job? = null

    /**
     * Which run owns the shared state.
     *
     * Cancelling a scan does not stop it instantly -- the job unwinds through its own `finally`,
     * on another thread, some milliseconds later. That `finally` writes the results, the archive
     * and the phase, and it used to do so unconditionally: pressing "cancel and rescan" cleared
     * the list and then watched the dying run put it straight back, and starting a second scan
     * quickly enough had the first one's teardown flip `isScanning` to false underneath it.
     *
     * Every run captures this counter on the way in and checks it before touching anything shared.
     * Stopping or restarting bumps it, so a superseded run finishes quietly and changes nothing.
     */
    private var generation = 0

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _phase = MutableStateFlow(CloudflareScanner.ScanPhase.IDLE)
    val phase = _phase.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress = _progress.asStateFlow()

    /**
     * The scan's own account of itself: probed, alive, tested, healthy.
     *
     * [progress] is kept because a single number is still the right thing for a compact readout,
     * but it can only ever describe one half of a pipeline that runs both halves at once -- which
     * is why the screen reads this instead.
     */
    private val _stats = MutableStateFlow(CloudflareScanner.ScanStats())
    val stats = _stats.asStateFlow()

    private val _foundCount = MutableStateFlow(0)
    val foundCount = _foundCount.asStateFlow()

    private val _scannedIPs = MutableStateFlow<List<ScannedIP>>(emptyList())
    val scannedIPs = _scannedIPs.asStateFlow()

    val globalBaseConfig = MutableStateFlow("")

    /**
     * The cloud group the base config was picked from, when it was picked from one.
     *
     * A combine multiplies EVERY config in a group against every clean IP, which is what makes it
     * worth doing; knowing only the one URI in the field would limit it to a single server.
     */
    val selectedSourceGroupId = MutableStateFlow("")

    /** What the scan optimises for. Persisted by the screen, read here when a scan starts. */
    val strategy = MutableStateFlow(ScanStrategy.FAST_CONFIG)

    /**
     * The ports the configs being combined actually use.
     *
     * Derived from the source group rather than assumed: a group whose workers answer on 2053 is
     * unusable if the scan only ever probes 443, and probing every Cloudflare TLS port for every
     * address would multiply the scan by six for no reason on the common single-port group.
     */
    val scanPorts = MutableStateFlow(listOf(443))

    /**
     * True while the TCP sweep is pinned to the real line under somebody else's VPN.
     *
     * The screen shows it, because the two halves of the scan are then measuring different things:
     * the sweep sees the real link, the verification still goes through their tunnel.
     */
    private val _pinnedToRealLine = MutableStateFlow(false)
    val pinnedToRealLine = _pinnedToRealLine.asStateFlow()

    private val _lastScanNewCount = MutableStateFlow(0)
    val lastScanNewCount = _lastScanNewCount.asStateFlow()

    private val _lastScanDuplicateCount = MutableStateFlow(0)
    val lastScanDuplicateCount = _lastScanDuplicateCount.asStateFlow()

    /** (tested, blocked) after a health test, or null when there is nothing to clean up. */
    private val _healthTestResults = MutableStateFlow<Pair<Int, Int>?>(null)
    val healthTestResults = _healthTestResults.asStateFlow()

    private val _pauseMessage = MutableStateFlow<String?>(null)
    val pauseMessage = _pauseMessage.asStateFlow()

    private var lastHealthTestIps: List<String>? = null
    private var lastHealthTestGoodIps: List<String>? = null

    /**
     * Start a scan.
     *
     * @param testBudget the ceiling on definitive (proxied) tests -- the "test limit" field.
     * @param successTarget how many working IPs end the scan early.
     * @param customIps a fixed list to test instead of generating one from the Cloudflare ranges.
     * @param isHealthTest re-testing an archive rather than discovering new addresses. Changes the
     *   contract: every address is tested (no early stop), because the question is which of THESE
     *   still work, and an answer based on a partial sweep would be used to delete the rest.
     * @param useRealLine pin the TCP sweep to the non-VPN interface. Only meaningful when a
     *   foreign VPN holds the default route and the user chose to scan anyway.
     */
    fun startScan(
        context: android.content.Context,
        baseConfig: String,
        testBudget: Int = 50,
        successTarget: Int = 10,
        customIps: List<String>? = null,
        isHealthTest: Boolean = false,
        useRealLine: Boolean = false,
    ) {
        if (_isScanning.value) return
        // The IP scan and MLM Scout never share the line: the newer request wins, and Scout keeps
        // (and labels) what it had measured.
        com.mlmvpn.scanner.engines.scout.ScoutEngine.stop(com.mlmvpn.scanner.engines.scout.EndReason.OTHER_SCAN)
        val myGeneration = ++generation
        _isScanning.value = true
        _scannedIPs.value = emptyList()
        _progress.value = 0f
        _stats.value = CloudflareScanner.ScanStats()
        _phase.value = CloudflareScanner.ScanPhase.IDLE
        _healthTestResults.value = null
        _lastScanNewCount.value = 0
        _lastScanDuplicateCount.value = 0
        // Both of these used to survive into the next scan. A stale count showed the previous
        // run's total for the first half-second, and a pause banner left over from a run that was
        // cancelled while offline stayed on screen accusing a perfectly healthy connection.
        _foundCount.value = 0
        _pauseMessage.value = null
        _pinnedToRealLine.value = false
        lastHealthTestIps = if (isHealthTest) customIps?.distinct() else null
        lastHealthTestGoodIps = null

        // applicationContext, not the caller's. This scope outlives every screen, so holding the
        // Activity here leaked it for the whole length of a scan.
        val appContext = context.applicationContext
        val watchdog = NetworkWatchdog(appContext)
        watchdog.start()
        // A scan can run for minutes. Without a foreground service the platform is free to freeze
        // the process the moment the user looks at another app, and a frozen scan does not fail --
        // it silently marks everything it has not reached yet as dead.
        IpScanService.start(appContext)

        scanJob = scope.launch(Dispatchers.Default) {
            try {
                val scanner = CloudflareScanner()
                val ranges = CloudflareScanner.DEFAULT_RANGES
                // Discovery scouts the ranges first (ScanScout): under the 2026-09-28 filtering only
                // one Cloudflare range still carried traffic and the fixed order never reached it.
                val basePort = com.mlmvpn.scanner.utils.VpnConfig.parseUri(baseConfig)?.port ?: 443
                val ipsToScan = customIps?.distinct()
                    ?: runCatching { ScanScout.order(appContext, scanner, ranges, baseConfig, basePort) }.getOrElse { scanner.generateIPs(ranges) }

                // A health test asks a closed question -- which of THESE addresses still work --
                // so it has to try all of them. Stopping at the usual target and then reporting
                // "the rest are blocked" was how a 60-address archive got cut to 10.
                val effectiveTarget = if (isHealthTest) ipsToScan.size else successTarget
                val effectiveBudget = if (isHealthTest) ipsToScan.size else testBudget

                // Under a foreign VPN the sweep can still see the real link, which is the whole
                // reason "scan anyway" is offered rather than merely permitted.
                val probeNetwork = if (useRealLine) ScanPreflight.underlyingNetwork(appContext) else null
                if (probeNetwork != null) _pinnedToRealLine.value = true

                // Map to keep track of uniqueness based on IP string
                val currentMap = java.util.concurrent.ConcurrentHashMap<String, ScannedIP>()
                val idCounter = java.util.concurrent.atomic.AtomicInteger(0)

                fun snapshot(): List<ScannedIP> = currentMap.values
                    .filter { it.downloadSpeed > 0 }
                    .sortedBy { it.downloadSpeed }
                    // The cap is a display concern for a discovery scan; a health test must show
                    // every address that answered, since that list is what replaces the archive.
                    .let { if (isHealthTest) it else it.take(effectiveTarget) }

                val uiUpdateJob = launch {
                    while (isActive) {
                        delay(500) // Update UI every 500ms
                        if (myGeneration != generation) break
                        _foundCount.value = currentMap.size
                        // Live, not hidden until the end. The pipeline verifies exits while it
                        // is still probing, so there is something true to show the whole time --
                        // the old two-phase scan had nothing until the very last moment, which is
                        // why this used to blank the list during a scan.
                        _scannedIPs.value = snapshot()
                    }
                }

                try {
                    scanner.startScan(
                        context = appContext,
                        ips = ipsToScan,
                        baseConfig = baseConfig,
                        testBudget = effectiveBudget,
                        successTarget = effectiveTarget,
                        strategy = strategy.value,
                        ports = scanPorts.value,
                        watchdog = watchdog,
                        probeNetwork = probeNetwork,
                        healthTest = isHealthTest,
                        onPhaseChange = { newPhase ->
                            if (myGeneration == generation) _phase.value = newPhase
                        },
                        onStats = { s ->
                            if (myGeneration == generation) {
                                _stats.value = s
                                val probe = if (s.total > 0) s.probed.toFloat() / s.total else 0f
                                _progress.value = if (s.healthTest) {
                                    // A health test ends when it has been through the LIST, and
                                    // most of a stale archive is expected to fail -- so weighting
                                    // this towards "healthy found" would have a run that worked
                                    // perfectly finish at 20%.
                                    probe.coerceIn(0f, 1f) * 100f
                                } else {
                                    // A single figure for the compact readout. Weighted towards
                                    // verification because that is what actually ends the scan:
                                    // the probes can finish long before the last candidate is
                                    // tested.
                                    val verify =
                                        if (s.target > 0) s.healthy.toFloat() / s.target else 0f
                                    (probe * 0.3f + verify * 0.7f).coerceIn(0f, 1f) * 100f
                                }
                            }
                        },
                        onIpFound = { result ->
                            // (The scanner itself has already noted this edge as working, with its
                            // real-test delay -- CfEdge.noteGood in CloudflareScanner's verifier.)
                            // Update our map silently
                            val existing = currentMap[result.ip]
                            if (existing != null) {
                                currentMap[result.ip] = existing.copy(
                                    ping = result.ping,
                                    downloadSpeed = result.downloadSpeed,
                                    port = result.port,
                                    strategy = result.strategy,
                                )
                            } else {
                                currentMap[result.ip] = ScannedIP(
                                    id = idCounter.getAndIncrement(),
                                    ip = result.ip,
                                    ping = result.ping,
                                    downloadSpeed = result.downloadSpeed,
                                    port = result.port,
                                    selected = true,
                                    strategy = result.strategy,
                                )
                            }
                        },
                        onPaused = { message ->
                            if (myGeneration == generation) _pauseMessage.value = message
                        }
                    )
                } finally {
                    uiUpdateJob.cancel()
                    watchdog.stop()
                    if (myGeneration == generation) {
                        val sortedList = snapshot()
                        _scannedIPs.value = sortedList
                        _pauseMessage.value = null

                        if (isHealthTest) {
                            recordHealthTest(sortedList)
                        } else if (sortedList.isNotEmpty()) {
                            archiveFoundIps(appContext, sortedList)
                        }
                    }
                }
            } catch (e: CancellationException) {
                // The normal way a stopped scan ends.
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (myGeneration == generation) {
                    _isScanning.value = false
                    _phase.value = CloudflareScanner.ScanPhase.DONE
                    _pauseMessage.value = null
                }
                // Unconditional: a superseded run owns no state, but it may well be the only
                // reason the service is still up, and leaving a foreground notification behind is
                // a bug the user can see. The service also stops itself when isScanning clears,
                // so a live run that is still going will simply start it again.
                if (!_isScanning.value) IpScanService.stop(appContext)
            }
        }
    }

    /**
     * What the health test found, and what it is therefore safe to say.
     *
     * "Blocked" is `tested - answered`, never `archive size - answered`. The two are the same only
     * when the run actually reached every address; when it did not -- a stop, a lost connection --
     * the second number invents a verdict for addresses nobody looked at, and the cleanup button
     * then deletes them.
     */
    private fun recordHealthTest(answered: List<ScannedIP>) {
        val asked = lastHealthTestIps ?: return
        // PROBED, not tested. An archive address that no longer opens its port fails at the TCP
        // sweep and never reaches the proxied test at all -- so counting "tested" would say the
        // run never finished precisely when it found the most blocked addresses. Every address in
        // the list gets a probe; one that neither opened nor answered is what "blocked" means.
        val checked = _stats.value.probed.coerceAtMost(asked.size)
        val goodIps = answered.map { it.ip }
        lastHealthTestGoodIps = goodIps

        val blocked = (checked - goodIps.size).coerceAtLeast(0)
        // Nothing to offer if nothing failed, and nothing HONEST to offer if the run did not get
        // through the whole list: deleting on a partial answer is the data loss this exists to
        // prevent. A stopped or interrupted test therefore offers no cleanup at all.
        if (blocked > 0 && checked >= asked.size) {
            _healthTestResults.value = Pair(checked, blocked)
        } else {
            lastHealthTestGoodIps = null
        }
    }

    /** Merge a discovery scan's results into the single unified archive. */
    private fun archiveFoundIps(context: android.content.Context, found: List<ScannedIP>) {
        try {
            // One call, so the load/merge/write happens under the archive's own lock rather than
            // across three steps that another thread's delete could land in the middle of.
            val (new, duplicates) = GroupManager(context)
                .mergeIntoUnifiedArchive(found.map { it.ip })
            _lastScanNewCount.value = new
            _lastScanDuplicateCount.value = duplicates
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Stop the running scan.
     *
     * The generation bump is what makes this immediate from the caller's point of view: the job
     * still unwinds on its own schedule, but from here on it owns nothing and writes nothing.
     */
    fun stopScan() {
        generation++
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
        _phase.value = CloudflareScanner.ScanPhase.DONE
        _pauseMessage.value = null
        _pinnedToRealLine.value = false
    }

    fun toggleSelection(id: Int) {
        _scannedIPs.value = _scannedIPs.value.map {
            if (it.id == id) it.copy(selected = !it.selected) else it
        }
    }

    fun reset() {
        stopScan()
        _scannedIPs.value = emptyList()
        _progress.value = 0f
        _foundCount.value = 0
        _stats.value = CloudflareScanner.ScanStats()
        _phase.value = CloudflareScanner.ScanPhase.IDLE
        _lastScanNewCount.value = 0
        _lastScanDuplicateCount.value = 0
    }

    fun confirmHealthTestCleanup(context: android.content.Context) {
        val goodIps = lastHealthTestGoodIps ?: return
        try {
            GroupManager(context).replaceUnifiedArchive(goodIps)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            _healthTestResults.value = null
            lastHealthTestIps = null
            lastHealthTestGoodIps = null
        }
    }

    fun dismissHealthTestCleanup() {
        _healthTestResults.value = null
        lastHealthTestIps = null
        lastHealthTestGoodIps = null
    }
}
