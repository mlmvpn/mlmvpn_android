package com.mlmvpn.scanner.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.mlmvpn.scanner.ui.ScannedIP

object ScannerManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scanJob: Job? = null

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _phase = MutableStateFlow(CloudflareScanner.ScanPhase.IDLE)
    val phase = _phase.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress = _progress.asStateFlow()

    private val _foundCount = MutableStateFlow(0)
    val foundCount = _foundCount.asStateFlow()

    private val _scannedIPs = MutableStateFlow<List<ScannedIP>>(emptyList())
    val scannedIPs = _scannedIPs.asStateFlow()

    val globalBaseConfig = MutableStateFlow("")

    private val _lastScanNewCount = MutableStateFlow(0)
    val lastScanNewCount = _lastScanNewCount.asStateFlow()

    private val _lastScanDuplicateCount = MutableStateFlow(0)
    val lastScanDuplicateCount = _lastScanDuplicateCount.asStateFlow()

    private val _healthTestResults = MutableStateFlow<Pair<Int, Int>?>(null) // Pair(total, blocked)
    val healthTestResults = _healthTestResults.asStateFlow()

    private var lastHealthTestIps: List<String>? = null
    private var lastHealthTestGoodIps: List<String>? = null

    fun startScan(context: android.content.Context, baseConfig: String, limit: Int = 50, successTarget: Int = 10, customIps: List<String>? = null, isHealthTest: Boolean = false) {
        if (_isScanning.value) return
        _isScanning.value = true
        _scannedIPs.value = emptyList()
        _progress.value = 0f
        _phase.value = CloudflareScanner.ScanPhase.IDLE
        _healthTestResults.value = null
        _lastScanNewCount.value = 0
        _lastScanDuplicateCount.value = 0
        lastHealthTestIps = if (isHealthTest) customIps else null

        scanJob = scope.launch(Dispatchers.Default) {
            try {
                val scanner = CloudflareScanner()
                val ranges = CloudflareScanner.DEFAULT_RANGES
                val ipsToScan = customIps ?: scanner.generateIPs(ranges)
                
                // Map to keep track of uniqueness based on IP string
                val currentMap = java.util.concurrent.ConcurrentHashMap<String, ScannedIP>()
                val idCounter = java.util.concurrent.atomic.AtomicInteger(0)

                val uiUpdateJob = launch {
                    while (isActive) {
                        delay(500) // Update UI every 500ms
                        _foundCount.value = currentMap.size
                        if (_phase.value == CloudflareScanner.ScanPhase.DONE) {
                            val sortedList = currentMap.values.filter { it.downloadSpeed > 0 }.sortedBy { it.downloadSpeed }.take(successTarget)
                            _scannedIPs.value = sortedList
                        } else {
                            _scannedIPs.value = emptyList() // Hide list during scan to prevent UI freeze
                        }
                    }
                }

                try {
                    scanner.startScan(
                        context = context,
                        ips = ipsToScan,
                        baseConfig = baseConfig,
                        limit = limit,
                        successTarget = successTarget,
                        onPhaseChange = { newPhase ->
                            _phase.value = newPhase
                        },
                        onProgress = { current, total ->
                            _progress.value = if (total > 0) (current.toFloat() / total.toFloat()) * 100f else 0f
                        },
                        onIpFound = { result ->
                            // Update our map silently
                            val existing = currentMap[result.ip]
                            if (existing != null) {
                                currentMap[result.ip] = existing.copy(
                                    ping = result.ping,
                                    downloadSpeed = result.downloadSpeed
                                )
                            } else {
                                currentMap[result.ip] = ScannedIP(
                                    id = idCounter.getAndIncrement(),
                                    ip = result.ip,
                                    ping = result.ping,
                                    downloadSpeed = result.downloadSpeed,
                                    selected = true
                                )
                            }
                        }
                    )
                } finally {
                    uiUpdateJob.cancel()
                    val sortedList = currentMap.values.filter { it.downloadSpeed > 0 }.sortedBy { it.downloadSpeed }.take(successTarget)
                    _scannedIPs.value = sortedList

                    if (isHealthTest && lastHealthTestIps != null) {
                        val goodIps = sortedList.map { it.ip }
                        lastHealthTestGoodIps = goodIps
                        val blockedCount = lastHealthTestIps!!.size - goodIps.size
                        if (blockedCount > 0) {
                            _healthTestResults.value = Pair(lastHealthTestIps!!.size, blockedCount)
                        }
                    } else if (sortedList.isNotEmpty()) {
                        try {
                            val gm = GroupManager(context)
                            gm.loadIpArchives()
                            val newIps = sortedList.map { it.ip }
                            val existingArchive = gm.ipArchives.find { it.id == "unified_archive" }
                            val dateStr = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US).format(java.util.Date())
                            
                            val existingIps = existingArchive?.ips ?: emptyList()
                            val duplicatesCount = newIps.count { it in existingIps }
                            val newCount = newIps.size - duplicatesCount
                            _lastScanNewCount.value = newCount
                            _lastScanDuplicateCount.value = duplicatesCount

                            if (existingArchive != null) {
                                val mergedIps = (existingArchive.ips + newIps).distinct()
                                val updatedArchive = existingArchive.copy(
                                    date = "Last updated: $dateStr",
                                    ips = mergedIps
                                )
                                val index = gm.ipArchives.indexOf(existingArchive)
                                gm.ipArchives[index] = updatedArchive
                            } else {
                                val archive = IpArchive(
                                    id = "unified_archive",
                                    date = "Last updated: $dateStr",
                                    ips = newIps.distinct()
                                )
                                gm.ipArchives.add(archive)
                            }
                            gm.saveIpArchives()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isScanning.value = false
                _phase.value = CloudflareScanner.ScanPhase.DONE
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _isScanning.value = false
        _phase.value = CloudflareScanner.ScanPhase.DONE
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
    }

    fun setScannedIPs(ips: List<ScannedIP>) {
        _scannedIPs.value = ips
        _phase.value = CloudflareScanner.ScanPhase.DONE
    }

    fun confirmHealthTestCleanup(context: android.content.Context) {
        val goodIps = lastHealthTestGoodIps ?: return
        try {
            val gm = GroupManager(context)
            gm.loadIpArchives()
            val existingArchive = gm.ipArchives.find { it.id == "unified_archive" }
            if (existingArchive != null) {
                val dateStr = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US).format(java.util.Date())
                val updatedArchive = existingArchive.copy(
                    date = "Last updated: $dateStr",
                    ips = goodIps
                )
                val index = gm.ipArchives.indexOf(existingArchive)
                gm.ipArchives[index] = updatedArchive
                gm.saveIpArchives()
            }
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
