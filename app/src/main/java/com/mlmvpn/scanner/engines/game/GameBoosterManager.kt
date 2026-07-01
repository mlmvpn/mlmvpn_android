package com.mlmvpn.scanner.engines.game

import android.content.Context
import android.content.Intent
import android.util.Log
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.utils.VpnConfig
import com.mlmvpn.scanner.utils.XrayJsonGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import com.mlmvpn.core.warp.WarpAccountManager
import com.mlmvpn.core.warp.WarpAntiDpi
import com.mlmvpn.core.warp.WarpBatchTester
import com.mlmvpn.core.warp.WarpHandshakeTester
import com.mlmvpn.core.warp.WarpIpGenerator
import org.json.JSONArray
import org.json.JSONObject

class GameBoosterManager(private val context: Context) {

    val boosterState = MutableStateFlow(BoosterState.IDLE)
    val currentMode = MutableStateFlow(BoostMode.AUTO)
    val bestResult = MutableStateFlow<BoostResult?>(null)
    val allResults = MutableStateFlow<List<BoostResult>>(emptyList())
    val testProgress = MutableStateFlow(Pair(0, 0))
    val livePing = MutableStateFlow(-1L)
    val originalPing = MutableStateFlow(-1L)
    val activeGamePackage = MutableStateFlow<String?>(null)
    val selectedServer = MutableStateFlow<GameServer?>(null)

    private val nodeManager = NodeManager(context)

    suspend fun runBoostTest(game: GameInfo, region: String = "ME", mode: BoostMode = BoostMode.AUTO): BoostResult? {
        Log.d("GameBoosterManager", ">>> Starting Game Boost Test. Game: ${game.name}, Region: $region, Mode: $mode")
        boosterState.value = BoosterState.TESTING
        allResults.value = emptyList()
        bestResult.value = null
        val results = mutableListOf<BoostResult>()
        val server = game.servers.find { it.region == region } ?: game.servers.first()
        selectedServer.value = server

        try {
            if (mode == BoostMode.AUTO || mode == BoostMode.DIRECT) {
                Log.d("GameBoosterManager", "--- Testing Direct Connection ---")
                coroutineContext.ensureActive()
                val directResult = testDirectBoost(server)
                originalPing.value = directResult?.pingMs ?: -1L
                if (directResult != null) {
                    Log.d("GameBoosterManager", "Direct Test Success: ping=${directResult.pingMs}ms, jitter=${directResult.jitterMs}ms")
                    results.add(directResult)
                    allResults.value = results.sortedBy { it.pingMs }
                } else {
                    Log.d("GameBoosterManager", "Direct Test Failed or Timeout.")
                    // If user EXPLICITLY requested DIRECT mode, add a fake result so it proceeds
                    if (mode == BoostMode.DIRECT) {
                        Log.d("GameBoosterManager", "Direct mode forced by user. Proceeding despite ping failure.")
                        results.add(BoostResult(BoostMode.DIRECT, -1, 0, details = "Ping Failed (Forced Direct)"))
                    }
                }
            }

            if (mode == BoostMode.AUTO || mode == BoostMode.TUNNEL) {
                Log.d("GameBoosterManager", "--- Testing Tunnel Nodes ---")
                coroutineContext.ensureActive()
                val tunnelResults = testTunnelTurbo(server)
                Log.d("GameBoosterManager", "Tunnel Test Completed. Found ${tunnelResults.size} working nodes.")
                results.addAll(tunnelResults)
                allResults.value = results.sortedBy { it.pingMs }
            }

            if (mode == BoostMode.AUTO || mode == BoostMode.WARP) {
                Log.d("GameBoosterManager", "--- Testing WARP WireGuard ---")
                coroutineContext.ensureActive()
                val warpResult = testWarpBoost(server)
                if (warpResult != null) {
                    Log.d("GameBoosterManager", "WARP Test Success: ping=${warpResult.pingMs}ms")
                    results.add(warpResult)
                    allResults.value = results.sortedBy { it.pingMs }
                } else {
                    Log.d("GameBoosterManager", "WARP Test Failed or Timeout.")
                }
            }

            if (mode == BoostMode.AUTO || mode == BoostMode.WORKER) {
                Log.d("GameBoosterManager", "--- Testing Worker Relay ---")
                coroutineContext.ensureActive()
                val workerResult = testWorkerRelay(server)
                if (workerResult != null) {
                    Log.d("GameBoosterManager", "Worker Relay Success: ping=${workerResult.pingMs}ms")
                    results.add(workerResult)
                    allResults.value = results.sortedBy { it.pingMs }
                } else {
                    Log.d("GameBoosterManager", "Worker Relay Failed or Timeout.")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.d("GameBoosterManager", "Boost Test Cancelled.")
            boosterState.value = BoosterState.IDLE
            throw e
        } catch (e: Exception) {
            Log.e("GameBoosterManager", "Exception during boost test", e)
        }

        val best = if (mode == BoostMode.DIRECT && results.isNotEmpty()) {
            results.first() // If forced direct, just use it
        } else {
            results.filter { it.pingMs > 0 }.minByOrNull { it.pingMs }
        }
        
        Log.d("GameBoosterManager", "<<< Boost Test Finished. Best Result: ${best?.mode} with ping=${best?.pingMs}ms")
        bestResult.value = best
        boosterState.value = if (best != null) BoosterState.IDLE else BoosterState.FAILED
        return best
    }

    private suspend fun testDirectBoost(server: GameServer): BoostResult? {
        val (avgPing, jitter) = GamePingTester.averagePing(
            endpoints = server.testEndpoints,
            port = server.port,
            proxyPort = null
        )
        if (avgPing <= 0) return null
        return BoostResult(
            mode = BoostMode.DIRECT,
            pingMs = avgPing,
            jitterMs = jitter,
            details = "Direct connection"
        )
    }

    private suspend fun testTunnelTurbo(
        server: GameServer
    ): List<BoostResult> = withContext(Dispatchers.IO) {
        val nodes = nodeManager.nodes.toList()
        if (nodes.isEmpty()) return@withContext emptyList()

        val testPort = 25000
        val results = mutableListOf<BoostResult>()
        val nodesToTest = nodes.shuffled().take(15)

        testProgress.value = Pair(0, nodesToTest.size)

        for ((index, node) in nodesToTest.withIndex()) {
            coroutineContext.ensureActive()
            testProgress.value = Pair(index + 1, nodesToTest.size)

            var pingMs = -1L
            var jitterMs = 0L

            try {
                val config = VpnConfig.parseUri(node.uri) ?: continue
                
                // Measure real node latency using TLS Ping
                val sni = if (config.sni.isNotEmpty()) config.sni else config.wsHost
                val fullTlsTime = com.mlmvpn.scanner.ui.tlsPing(config.address, config.port, sni)
                
                // TLS Handshake is ~2 RTTs. We divide by 2 for a realistic base ping estimate.
                pingMs = (fullTlsTime / 2).toLong()
                
                // Since we only do one ping, jitter is 0
                jitterMs = 0L
                
                Log.d("GameBoosterManager", "Node Test [${node.name}]: ping=${pingMs}ms, jitter=${jitterMs}ms")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d("GameBoosterManager", "Tunnel test failed for node: ${node.name} - ${e.message}")
            }

            if (pingMs > 0) {
                results.add(BoostResult(
                    mode = BoostMode.TUNNEL,
                    pingMs = pingMs,
                    jitterMs = jitterMs,
                    nodeId = node.id,
                    nodeName = node.name,
                    nodeUri = node.uri,
                    details = node.name
                ))
            }
        }

        return@withContext results.sortedBy { it.pingMs }.take(5)
    }

    private suspend fun testWorkerRelay(server: GameServer): BoostResult? {
        return null
    }

    private suspend fun testWarpBoost(server: GameServer): BoostResult? = withContext(Dispatchers.IO) {
        val warpManager = WarpAccountManager(context)
        val savedAccount = warpManager.getSavedAccount() ?: run {
            Log.d("GameBoosterManager", "No WARP account found, attempting to register...")
            val result = warpManager.registerNewAccount()
            if (result.isSuccess) warpManager.getSavedAccount() else null
        }
        
        if (savedAccount == null) {
            Log.e("GameBoosterManager", "Failed to obtain WARP account")
            return@withContext null
        }

        testProgress.value = Pair(0, 100)
        val reservedBytes = WarpAntiDpi.generateReservedBytes()
        
        // Stage 1: Find 5 reachable IPs quickly
        val foundEndpoints = mutableListOf<WarpBatchTester.BatchEndpoint>()
        while (foundEndpoints.size < 5) {
            val batchToTest = WarpIpGenerator.generateRandomEndpoints(20)
            kotlinx.coroutines.coroutineScope {
                val deferreds = batchToTest.map { endpointStr ->
                    async {
                        val lastColonIndex = endpointStr.lastIndexOf(':')
                        val ip = if (lastColonIndex != -1) endpointStr.substring(0, lastColonIndex) else endpointStr
                        val port = if (lastColonIndex != -1) endpointStr.substring(lastColonIndex + 1).toIntOrNull() ?: 2408 else 2408
                        
                        val isAlive = WarpBatchTester.stage1FastPing(ip, 1000)
                        if (isAlive) WarpBatchTester.BatchEndpoint(ip, port, 0) else null
                    }
                }
                foundEndpoints.addAll(deferreds.awaitAll().filterNotNull())
            }
            testProgress.value = Pair(foundEndpoints.size * 10, 100)
        }

        // Stage 2: WG Handshake Ping
        val results = WarpBatchTester.testBatch(
            context = context,
            endpoints = foundEndpoints.take(5),
            account = savedAccount,
            reservedBytes = reservedBytes
        )

        testProgress.value = Pair(100, 100)

        val bestEndpoint = results.filter { it.pingMs != null && it.pingMs > 0 }.minByOrNull { it.pingMs!! }
        if (bestEndpoint == null) {
            Log.e("GameBoosterManager", "No working WARP endpoint found in batch test")
            return@withContext null
        }

        // We already did a WG ping in testBatch, so we don't strictly need to do HandshakeTester again,
        // but keeping it for consistency if needed. Actually we can just use bestEndpoint.pingMs!
        val ping = bestEndpoint.pingMs!!

        val warpJson = JSONObject().apply {
            put("type", "warp")
            put("privateKey", savedAccount.privateKey)
            put("ipv4", savedAccount.ipv4)
            put("ipv6", savedAccount.ipv6)
            put("endpointIp", bestEndpoint.endpointIp)
            put("endpointPort", bestEndpoint.endpointPort)
            put("reserved", JSONArray(reservedBytes))
        }.toString()

        return@withContext BoostResult(
            mode = BoostMode.WARP,
            pingMs = ping.toLong(),
            jitterMs = 0L,
            nodeId = "warp_game_mode",
            nodeName = "WARP WireGuard",
            nodeUri = warpJson,
            details = "Endpoint: ${bestEndpoint.endpointIp} (Ping: ${bestEndpoint.pingMs}ms)"
        )
    }

    fun connectWithBestResult(result: BoostResult, gamePackage: String) {
        boosterState.value = BoosterState.CONNECTING
        activeGamePackage.value = gamePackage

        Log.d("GameBoosterManager", "Connecting with best result: mode=${result.mode}, ping=${result.pingMs}ms for package $gamePackage")
        when (result.mode) {
            BoostMode.DIRECT -> {
                Log.d("GameBoosterManager", "Direct mode selected. No VPN started. App will use direct internet.")
                boosterState.value = BoosterState.BOOSTED
            }
            BoostMode.TUNNEL, BoostMode.WORKER, BoostMode.WARP -> {
                Log.d("GameBoosterManager", "Tunnel/WARP mode selected. Node: ${result.nodeName}. Starting VPN in Game Mode.")
                val prefs = context.getSharedPreferences("game_booster_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("game_mode_active", true)
                    .putString("game_package", gamePackage)
                    .apply()

                val intent = Intent(context, MyVpnService::class.java).apply {
                    putExtra("NODE_URI", result.nodeUri)
                    putExtra("NODE_ID", result.nodeId)
                    putExtra("GAME_MODE", true)
                    putExtra("GAME_PACKAGE", gamePackage)
                }
                context.startService(intent)

                boosterState.value = BoosterState.BOOSTED
            }
            BoostMode.AUTO -> {}
        }
    }

    suspend fun startLivePingMonitor(server: GameServer, proxyPort: Int? = null) {
        selectedServer.value = server
        while (true) {
            coroutineContext.ensureActive()
            val ping = if (proxyPort != null) {
                GamePingTester.proxyPing(
                    host = server.testEndpoints.first(),
                    port = server.port,
                    proxyPort = proxyPort
                )
            } else {
                GamePingTester.directPing(
                    server.testEndpoints.first(),
                    server.port
                )
            }
            livePing.value = ping
            delay(5000)
        }
    }

    fun disconnect() {
        val prefs = context.getSharedPreferences("game_booster_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("game_mode_active", false).apply()

        if (MyVpnService.isRunning) {
            val stopIntent = Intent(context, MyVpnService::class.java).apply { action = "STOP" }
            context.startService(stopIntent)
        }

        boosterState.value = BoosterState.IDLE
        bestResult.value = null
        allResults.value = emptyList()
        livePing.value = -1L
        activeGamePackage.value = null
        selectedServer.value = null
    }
}
