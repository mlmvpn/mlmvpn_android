package com.mlmvpn.scanner.engines.wireguard

import android.content.Context
import android.util.Log
import com.mlmvpn.core.warp.VlessXrayInjector
import com.mlmvpn.core.warp.WarpAccountManager
import com.mlmvpn.scanner.utils.XrayJsonGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class ScanResult(val ip: String, val port: Int, val timeMs: Long)

object WireguardScannerEngine {

    enum class EngineState { IDLE, SCANNING, TESTING }

    private val _engineState = MutableStateFlow(EngineState.IDLE)
    val engineState = _engineState.asStateFlow()

    private val _scanProgress = MutableStateFlow(0f)
    val scanProgress = _scanProgress.asStateFlow()

    private val _scannedCount = MutableStateFlow(0)
    val scannedCount = _scannedCount.asStateFlow()

    private val _totalScanCount = MutableStateFlow(0)
    val totalScanCount = _totalScanCount.asStateFlow()

    private val _endpointsList = MutableStateFlow<List<ScanResult>>(emptyList())
    val endpointsList = _endpointsList.asStateFlow()

    private val _realPings = MutableStateFlow<Map<String, Long>>(emptyMap())
    val realPings = _realPings.asStateFlow()

    private val _selectedEndpoint = MutableStateFlow<String?>(null)
    val selectedEndpoint = _selectedEndpoint.asStateFlow()

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null
    private var testJob: Job? = null
    
    private val baseClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8000, TimeUnit.MILLISECONDS)
            .readTimeout(8000, TimeUnit.MILLISECONDS)
            .build()
    }

    fun loadPersistedResults(context: Context) {
        val prefs = context.getSharedPreferences("wg_scanner_prefs", Context.MODE_PRIVATE)
        val str = prefs.getString("saved_endpoints", "") ?: ""
        if (str.isEmpty()) return
        
        val list = str.split(",").mapNotNull {
            val parts = it.split(":")
            if (parts.size == 3) {
                ScanResult(parts[0], parts[1].toIntOrNull() ?: 2408, parts[2].toLongOrNull() ?: 0L)
            } else null
        }
        _endpointsList.value = list
        if (list.isNotEmpty() && _selectedEndpoint.value == null) {
            _selectedEndpoint.value = "${list.first().ip}:${list.first().port}"
        }
    }

    private fun persistResults(context: Context) {
        val prefs = context.getSharedPreferences("wg_scanner_prefs", Context.MODE_PRIVATE)
        val str = _endpointsList.value.joinToString(",") { "${it.ip}:${it.port}:${it.timeMs}" }
        prefs.edit().putString("saved_endpoints", str).apply()
    }

    fun clearEndpoints(context: Context) {
        _endpointsList.value = emptyList()
        _realPings.value = emptyMap()
        _selectedEndpoint.value = null
        persistResults(context)
    }

    fun selectEndpoint(id: String) {
        _selectedEndpoint.value = id
    }

    fun stopAll() {
        scanJob?.cancel()
        testJob?.cancel()
        _engineState.value = EngineState.IDLE
    }

    fun startScan(
        context: Context,
        cidrs: List<String>,
        ports: List<Int>,
        maxSamples: Int,
        targetSuccess: Int
    ) {
        if (_engineState.value != EngineState.IDLE) return
        _engineState.value = EngineState.SCANNING
        
        scanJob = engineScope.launch {
            try {
                val samplesPerCidr = (maxSamples / (cidrs.size * ports.size)).coerceAtLeast(1)
                scanRandomEndpointsInternal(context, cidrs, ports, 800, samplesPerCidr, targetSuccess)
            } finally {
                if (_engineState.value == EngineState.SCANNING) {
                    _engineState.value = EngineState.IDLE
                }
            }
        }
    }

    private suspend fun scanRandomEndpointsInternal(
        context: Context,
        cidrs: List<String>,
        ports: List<Int>,
        timeoutMs: Int,
        samplesPerCidr: Int,
        targetSuccessCount: Int
    ) = coroutineScope {
        val targetIps = mutableListOf<String>()
        for (cidr in cidrs) {
            for (i in 0 until samplesPerCidr) {
                getRandomIpFromCidr(cidr)?.let { targetIps.add(it) }
            }
        }
        targetIps.shuffle()

        var currentScannedCount = 0
        val totalToScan = targetIps.size * ports.size
        _scannedCount.value = 0
        _totalScanCount.value = totalToScan
        _scanProgress.value = 0f

        val workChannel = kotlinx.coroutines.channels.Channel<Pair<String, Int>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val allTasks = mutableListOf<Pair<String, Int>>()
        targetIps.forEach { ip -> ports.forEach { port -> allTasks.add(Pair(ip, port)) } }
        allTasks.shuffle()
        allTasks.forEach { workChannel.trySend(it) }
        workChannel.close()

        val resultChannel = kotlinx.coroutines.channels.Channel<ScanResult>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        
        val workers = (1..50).map {
            launch(Dispatchers.IO) {
                for ((ip, port) in workChannel) {
                    ensureActive()
                    val time = testTcpPort(ip, port, timeoutMs)
                    
                    val newCount = synchronized(this@WireguardScannerEngine) {
                        currentScannedCount++
                        currentScannedCount
                    }
                    if (newCount % 20 == 0 || newCount == totalToScan) { // throttle updates
                        _scannedCount.value = newCount
                        _scanProgress.value = newCount.toFloat() / totalToScan.toFloat()
                    }
                    
                    if (time != -1L) {
                        resultChannel.trySend(ScanResult(ip, port, time))
                    }
                }
            }
        }

        val collectorJob = launch {
            val octetCounts = mutableMapOf<String, Int>()
            val maxPerOctet = (targetSuccessCount / 4).coerceAtLeast(5)
            val currentList = _endpointsList.value.toMutableList()
            var addedCount = 0

            for (result in resultChannel) {
                val parts = result.ip.split(".")
                val subnet16 = if (parts.size >= 2) "${parts[0]}.${parts[1]}" else parts[0]
                val count = octetCounts.getOrDefault(subnet16, 0)
                
                if (count < maxPerOctet) {
                    octetCounts[subnet16] = count + 1
                    
                    // Add distinct and sort
                    val isDuplicate = currentList.any { it.ip == result.ip && it.port == result.port }
                    if (!isDuplicate) {
                        currentList.add(result)
                        currentList.sortBy { it.timeMs }
                        _endpointsList.value = currentList.toList()
                        persistResults(context)
                        
                        if (_selectedEndpoint.value == null) {
                            _selectedEndpoint.value = "${currentList.first().ip}:${currentList.first().port}"
                        }
                        
                        addedCount++
                        if (addedCount >= targetSuccessCount) break
                    }
                }
            }
        }

        workers.joinAll()
        resultChannel.close()
        collectorJob.join()
    }

    fun startTest100(
        context: Context,
        accountsList: List<WarpAccountManager.WarpAccountData>
    ) {
        if (_engineState.value != EngineState.IDLE) return
        if (accountsList.isEmpty() || _endpointsList.value.isEmpty()) return
        
        _engineState.value = EngineState.TESTING
        
        testJob = engineScope.launch {
            try {
                val allEps = _endpointsList.value.map { "${it.ip}:${it.port}" }.take(150)
                val newPings = _realPings.value.toMutableMap()
                
                val batches = allEps.chunked(25)
                val injector = VlessXrayInjector(0)
                
                // Reset progress tracking for test phase
                var completed = 0
                _scannedCount.value = 0
                _totalScanCount.value = allEps.size
                _scanProgress.value = 0f
                
                for (batch in batches) {
                    if (!isActive || _engineState.value != EngineState.TESTING) break
                    
                    val config = XrayJsonGenerator.generateMultiWireguardConfig(
                        accounts = accountsList,
                        endpoints = batch,
                        baseSocksPort = 10000
                    )
                    
                    val started = injector.start(context, config, 0)
                    if (started) {
                        delay(5000)
                        val deferreds = batch.mapIndexed { index, ep ->
                            async(Dispatchers.IO) {
                                if (!isActive || _engineState.value != EngineState.TESTING) return@async
                                val port = 10000 + index
                                val ping = measureRealPing(port, baseClient)
                                synchronized(newPings) { newPings[ep] = ping }
                                
                                val c = synchronized(this@WireguardScannerEngine) { 
                                    completed++ 
                                    completed 
                                }
                                _scannedCount.value = c
                                _scanProgress.value = c.toFloat() / allEps.size.toFloat()
                                _realPings.value = newPings.toMap()
                                
                                val fastest = newPings.filterValues { it > 0 }.minByOrNull { it.value }?.key
                                if (fastest != null) _selectedEndpoint.value = fastest
                            }
                        }
                        deferreds.joinAll()
                        injector.stop()
                        System.gc()
                        delay(2000)
                    }
                }
            } finally {
                if (_engineState.value == EngineState.TESTING) {
                    _engineState.value = EngineState.IDLE
                }
            }
        }
    }

    private suspend fun measureRealPing(socksPort: Int, clientBase: OkHttpClient): Long = withContext(Dispatchers.IO) {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val client = clientBase.newBuilder().proxy(proxy).build()
        val request = Request.Builder()
            .url("http://1.1.1.1/generate_204")
            .header("Host", "cp.cloudflare.com")
            .build()
        val start = System.currentTimeMillis()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) return@withContext System.currentTimeMillis() - start
            }
        } catch (e: Exception) {
            Log.d("WGTest", "Ping failed on port $socksPort: ${e.message}")
        }
        return@withContext -1L
    }

    private fun getRandomIpFromCidr(cidr: String): String? {
        try {
            val parts = cidr.split("/")
            if (parts.size != 2) return null
            val ip = parts[0]
            val prefix = parts[1].toInt()
            val ipParts = ip.split(".")
            if (ipParts.size != 4) return null

            var baseIpNum = 0L
            for (i in 0..3) {
                baseIpNum = (baseIpNum shl 8) or ipParts[i].toLong()
            }

            val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            val network = baseIpNum and mask
            val hostCount = (1 shl (32 - prefix)) - 1
            if (hostCount <= 0) return ip
            
            val randomHost = Random.nextInt(0, hostCount)
            val randomIpNum = network or randomHost.toLong()
            return "${(randomIpNum shr 24) and 0xFF}.${(randomIpNum shr 16) and 0xFF}.${(randomIpNum shr 8) and 0xFF}.${randomIpNum and 0xFF}"
        } catch (e: Exception) {
            return null
        }
    }

    private fun testTcpPort(ip: String, port: Int, timeoutMs: Int): Long {
        val start = System.currentTimeMillis()
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            return System.currentTimeMillis() - start
        } catch (e: Exception) {
            return -1
        } finally {
            try { socket?.close() } catch (e: Exception) {}
        }
    }
}
