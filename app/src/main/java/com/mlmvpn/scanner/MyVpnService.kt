package com.mlmvpn.scanner

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

class MyVpnService : VpnService() {

    // High-level connection phase for UI (esp. the WARP tab, which spends time auto-testing several
    // transports before one connects). IDLE -> CONNECTING -> CONNECTED / FAILED. Nested directly in
    // the class (not the companion) so it resolves as MyVpnService.Phase from callers.
    enum class Phase { IDLE, CONNECTING, CONNECTED, FAILED }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var currentEngine: com.mlmvpn.core.warp.IVpnEngine? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var screenReceiver: android.content.BroadcastReceiver? = null
    private var disconnectJob: kotlinx.coroutines.Job? = null
    private var trafficMonitorJob: kotlinx.coroutines.Job? = null
    private var stressTestJob: kotlinx.coroutines.Job? = null
    private var autoSwitchJob: kotlinx.coroutines.Job? = null
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)

    // Reconnect-on-network-recovery: without this, losing the underlying network (Wi-Fi/mobile
    // drop) while CONNECTED leaves the tunnel sitting dead -- Xray/the engine has no way to know
    // its socket is gone, so the UI keeps reporting "connected" and stays that way even after the
    // network comes back, until the user manually disconnects and reconnects. Tracks whether a
    // real drop-then-recover happened while we were actually connected (not during the initial
    // connect, and not on every capabilities tweak) and, if so, cycles the same connection.
    private var lastConnectIntent: Intent? = null
    private var networkWatchdogCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var lostNetworkWhileConnected = false
    private var reconnectJob: kotlinx.coroutines.Job? = null

    companion object {
        val isRunningFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
        val connectedNodeIdFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        val connectionPhaseFlow = kotlinx.coroutines.flow.MutableStateFlow(Phase.IDLE)
        val xrayMutex = kotlinx.coroutines.sync.Mutex()
        var isRunning: Boolean
            get() = isRunningFlow.value
            set(value) { isRunningFlow.value = value }
        var connectedNodeId: String?
            get() = connectedNodeIdFlow.value
            set(value) { connectedNodeIdFlow.value = value }
        var instance: MyVpnService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerNetworkWatchdog()
    }

    private fun registerNetworkWatchdog() {
        if (networkWatchdogCallback != null) return
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        val request = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onLost(network: android.net.Network) {
                if (isRunning && connectionPhaseFlow.value == Phase.CONNECTED) {
                    Log.w("MyVpnService", "Underlying network lost while connected -- will reconnect once it's back")
                    lostNetworkWhileConnected = true
                }
            }
            override fun onAvailable(network: android.net.Network) {
                if (lostNetworkWhileConnected && isRunning) {
                    lostNetworkWhileConnected = false
                    Log.d("MyVpnService", "Network back after a drop -- reconnecting")
                    triggerReconnect()
                }
            }
        }
        try {
            cm.registerNetworkCallback(request, cb)
            networkWatchdogCallback = cb
        } catch (e: Exception) {
            Log.w("MyVpnService", "Could not register network watchdog", e)
        }
    }

    private fun triggerReconnect() {
        val intentToRestore = lastConnectIntent ?: return
        if (reconnectJob?.isActive == true) return
        reconnectJob = serviceScope.launch {
            val stopIntent = Intent(this@MyVpnService, MyVpnService::class.java).apply {
                action = "STOP"
                putExtra("INTERNAL_RECONNECT", true)
            }
            startService(stopIntent)
            // connectionPhaseFlow flips to IDLE synchronously at the top of the STOP handler,
            // before the actual (async, behind xrayMutex) engine teardown below it even starts --
            // it can't be used as a "teardown finished" signal. There's no such signal exposed,
            // so use a flat delay generous enough for the slowest engine (Aether killing a native
            // process) rather than racing a fresh connect against teardown still in flight.
            kotlinx.coroutines.delay(3000)
            startService(intentToRestore)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP") {
            connectedNodeId = null
            isRunning = false
            connectionPhaseFlow.value = Phase.IDLE
            lastConnectIntent = null
            lostNetworkWhileConnected = false
            // Don't cancel our own reconnect job if this STOP is the one IT issued -- that would
            // cancel the coroutine currently suspended a few lines below in triggerReconnect(),
            // right before it restarts the connection, silently turning "network came back" into
            // "stayed disconnected forever". Only a STOP from outside that flow (user tapping
            // disconnect, screen-off timeout, etc.) should cancel a pending reconnect.
            if (intent?.getBooleanExtra("INTERNAL_RECONNECT", false) != true) {
                reconnectJob?.cancel()
            }
            // Aether's connect can sit waiting on a gateway scan for a minute or more while
            // holding xrayMutex, so the teardown below would queue behind it. Signal it
            // here, before contending for the lock, so the button responds immediately.
            com.mlmvpn.core.aether.AetherTunEngine.requestAbort()
            getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("game_mode_active", false).apply()
            serviceScope.launch {
                xrayMutex.withLock {
                    try { currentEngine?.stop() } catch (e: Exception) {}
                    currentEngine = null
                    try { vpnInterface?.close() } catch (e: Exception) {}
                    vpnInterface = null
                }
                trafficMonitorJob?.cancel()
                stressTestJob?.cancel()
                // stopSelf(startId), NOT bare stopSelf().
                //
                // The teardown above runs behind xrayMutex and can take seconds (Aether kills
                // an OS process and joins tun2proxy). A user who taps disconnect and then
                // reconnects lands a new connect intent on THIS SAME service instance while
                // we are still unwinding. Bare stopSelf() ignores that and destroys the
                // service anyway; onDestroy() then takes the mutex the new connect just
                // released and tears down the brand-new engine ~300ms after it started.
                // Killing tun2proxy that soon after its native init races it and takes the
                // whole process down (observed: "Zygote: Process ... exited cleanly (255)",
                // no Java exception, no tombstone), which is what read as "connected, then
                // the app crashed".
                //
                // stopSelf(startId) is the API for exactly this: it is a no-op if a newer
                // start command has arrived, so a reconnect keeps the service alive.
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        
        isRunning = true
        connectedNodeId = intent?.getStringExtra("NODE_ID")
        connectionPhaseFlow.value = Phase.CONNECTING
        lostNetworkWhileConnected = false
        if (intent != null) lastConnectIntent = Intent(intent)

        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "MLMVPN::VpnWakeLock")
            wakeLock?.acquire()
            Log.d("MyVpnService", "WakeLock acquired")
        } catch (e: Exception) {
            Log.e("MyVpnService", "Failed to acquire WakeLock", e)
        }
        
        setupScreenReceiver()
        startTrafficMonitor()
        startStressTestMonitor()
        startAutoSwitchMonitor()
        
        val nodeUri = intent?.getStringExtra("NODE_URI") ?: return START_NOT_STICKY
        val isProxyMode = intent.getBooleanExtra("PROXY_MODE", false)
        val localPort = intent.getStringExtra("LOCAL_PORT")?.toIntOrNull() ?: 10808

        val isGameMode = intent.getBooleanExtra("GAME_MODE", false)
        if (isGameMode) {
            val gamePackage = intent.getStringExtra("GAME_PACKAGE")
            getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("game_mode_active", true)
                .putString("game_package", gamePackage)
                .apply()
            Log.d("MyVpnService", "Game Mode activated for: $gamePackage")
        }

        // Dedicated DNS (per-user ECS-steering worker) — passed by GameBoosterManager for game
        // TUNNEL/HYBRID connects. Absent (null/empty) for every non-game connect, so the config
        // generators fall back to their exact prior behaviour.
        val dedicatedDnsUrl = intent.getStringExtra("DEDICATED_DNS_URL")?.takeIf { it.isNotEmpty() }
        val dedicatedDnsDomains: List<String> = intent.getStringExtra("DEDICATED_DNS_DOMAINS")?.let { raw ->
            try {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) { emptyList() }
        } ?: emptyList()

        val sharedPrefs = getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
        val backendDns = sharedPrefs.getString("backend_dns", "1.1.1.1") ?: "1.1.1.1"
        val allowLan = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).getBoolean("allow_lan", false)
        val vpnPrefs = getSharedPreferences("vpn_routing_prefs", android.content.Context.MODE_PRIVATE)
        val mtu = vpnPrefs.getInt("vpn_mtu", 1420)

        serviceScope.launch {
            xrayMutex.withLock {
                // Clean up any existing connection before starting a new one
                try { vpnInterface?.close() } catch (e: Exception) {}
                vpnInterface = null

                kotlinx.coroutines.delay(300)

                try { currentEngine?.stop() } catch (e: Exception) {}
                currentEngine = null

                val isRawJsonConfig = nodeUri.startsWith("{") && nodeUri.contains("\"inbounds\"") && nodeUri.contains("\"outbounds\"")
                val isAmneziaWg = nodeUri.trim().startsWith("[Interface]")
                // Aether establishes its TUN late, from inside AetherTunEngine, once the
                // engine is actually carrying traffic — see the openTun callback there.
                // Bringing the interface up here instead would blackhole the whole device
                // for the duration of the gateway scan.
                val isAetherCfg = nodeUri.startsWith("{") &&
                    org.json.JSONObject(nodeUri).optString("type") ==
                        com.mlmvpn.core.aether.AetherTunEngine.CONFIG_TYPE
                // VPN Gate (OpenVPN): the openvpn3 core brings up its own VpnService, in its
                // own :openvpn process. Establishing a TUN here as well would revoke the one
                // it creates. Same arrangement as AmneziaWG above.
                val isVpnGate = nodeUri.startsWith(
                    com.mlmvpn.scanner.engines.vpngate.VpnGateEngine.URI_SCHEME)
                // SoftEther's own SSL-VPN. Same arrangement: the vendored client owns the TUN.
                val isSoftEther = nodeUri.startsWith(
                    com.mlmvpn.scanner.engines.vpngate.SoftEtherEngine.URI_SCHEME)
                var fd = 0
                if (!isProxyMode && !isAmneziaWg && !isAetherCfg && !isVpnGate && !isSoftEther) {
                    setupVpn(backendDns, mtu, isRawJsonConfig)
                    fd = vpnInterface?.fd ?: 0
                }
                // Surface the TUN state in the in-app GST log so we can tell whether the
                // system VPN actually came up (fd>0 => VPN key icon; fd=0 => no TUN).
                com.mlmvpn.scanner.engines.gst.GstLog.i(
                    "MyVpnService",
                    "connect: proxyMode=$isProxyMode, vpnFd=$fd " +
                        (if (isProxyMode) "(proxy mode ON → no TUN by design)"
                         else if (fd == 0) "(TUN establish FAILED → no VPN key icon; check VPN permission)"
                         else "(TUN established OK → VPN key icon should appear)")
                )

                // Force Xray to initialize its outbounds (specifically Wireguard) by sending a dummy TCP packet.
                // This prevents a known bug in xray-core where lazy-initializing the Wireguard outbound 
                // during shutdown causes a fatal nil pointer panic.
                if (fd > 0 && !isProxyMode && !isAmneziaWg) {
                    serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            kotlinx.coroutines.delay(1000) // wait for Xray to fully bind
                            val url = java.net.URL("http://1.1.1.1") // Public IP ensures it routes to 'proxy' outbound
                            val conn = url.openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 2000
                            conn.readTimeout = 2000
                            conn.responseCode // This will force traffic through the TUN into Xray
                        } catch (e: Exception) {
                            // We don't care if it fails, we just want the packet to hit the Xray outbound
                        }
                    }
                }

                try {
                    val isGst = nodeUri.startsWith("{") && org.json.JSONObject(nodeUri).optString("type") == "gst"
                    val isHybrid = nodeUri.startsWith("{") && org.json.JSONObject(nodeUri).optString("type") == "hybrid"
                    if (isAetherCfg) {
                        // Full-device Aether tunnel: TUN → tun2proxy → the Aether process's
                        // SOCKS5. Same shape as the GST branch below. No Xray in the path.
                        //
                        // Unlike every other branch here, the TUN is NOT up yet: the engine
                        // opens it through this callback only after it has a working gateway,
                        // so the device keeps normal connectivity throughout the scan.
                        //
                        // There is deliberately no proxy-mode branch: AetherScanService
                        // already covers "just publish a SOCKS5 listener". This path exists
                        // for the case the user actually asked for — the whole device routed
                        // without a second app.
                        currentEngine = com.mlmvpn.core.aether.AetherTunEngine(
                            openTun = {
                                setupVpn(backendDns, mtu, false)
                                // Hand tun2proxy the RAW detached fd and drop our
                                // ParcelFileDescriptor so the generic vpnInterface?.close()
                                // cleanup paths don't double-close a fd tun2proxy now owns.
                                val raw = try { vpnInterface?.detachFd() ?: 0 } catch (e: Exception) { 0 }
                                vpnInterface = null
                                com.mlmvpn.scanner.engines.gst.GstLog.i(
                                    "MyVpnService", "Aether: TUN established late, fd=$raw")
                                raw
                            },
                            mtu = mtu,
                        )
                        val success = currentEngine?.start(this@MyVpnService, nodeUri, localPort) ?: false
                        if (!success) {
                            Log.e("MyVpnService", "Failed to start Aether engine (VPN mode)")
                            try { currentEngine?.stop() } catch (_: Exception) {}
                            currentEngine = null
                            // Publish the terminal state explicitly. Without this the flow
                            // stayed on CONNECTING and isRunning stayed true, so the UI sat
                            // on "در حال اتصال…" forever — the reason a failed connect
                            // appeared to say nothing at all. AetherTunEngine has already
                            // put a specific reason on AetherEngine.state for the Aether
                            // screen to show.
                            connectionPhaseFlow.value = Phase.FAILED
                            isRunning = false
                            connectedNodeId = null
                            stopSelf()
                        } else {
                            Log.d("MyVpnService", "Aether Engine (full tunnel) started")
                            connectionPhaseFlow.value = Phase.CONNECTED
                        }
                    } else if (isGst) {
                        // Full-device GST tunnel, exactly like the reference mhrv-rs app:
                        //   Proxy Mode  → GST core only (127.0.0.1 SOCKS5/HTTP listeners, no TUN).
                        //   VPN Mode    → VpnService TUN → native tun2proxy → GST core SOCKS5.
                        // No Xray in either path.
                        if (isProxyMode) {
                            Log.d("MyVpnService", "GST proxy-mode: SOCKS5/HTTP listeners only (no TUN)")
                            currentEngine = com.mlmvpn.scanner.engines.gst.GstEngine()
                            val success = currentEngine?.start(this@MyVpnService, nodeUri, localPort) ?: false
                            if (!success) {
                                Log.e("MyVpnService", "Failed to start GST engine (proxy mode)")
                                try { currentEngine?.stop() } catch (_: Exception) {}
                                currentEngine = null
                                stopSelf()
                            } else {
                                Log.d("MyVpnService", "GST Engine (proxy mode) started")
                                connectionPhaseFlow.value = Phase.CONNECTED
                            }
                        } else if (fd <= 0) {
                            Log.e("MyVpnService", "GST VPN-mode start aborted: no TUN fd (VPN permission?)")
                            stopSelf()
                        } else {
                            // Hand tun2proxy the RAW detached fd and relinquish our
                            // ParcelFileDescriptor so the generic vpnInterface?.close()
                            // cleanup paths don't double-close a fd tun2proxy now owns
                            // (--close-fd-on-drop true).
                            val rawFd = try { vpnInterface?.detachFd() ?: fd } catch (e: Exception) { fd }
                            vpnInterface = null
                            currentEngine = com.mlmvpn.scanner.engines.gst.GstTunEngine(rawFd, mtu)
                            val success = currentEngine?.start(this@MyVpnService, nodeUri, localPort) ?: false
                            if (!success) {
                                Log.e("MyVpnService", "Failed to start GST engine (VPN mode)")
                                try { currentEngine?.stop() } catch (_: Exception) {}
                                currentEngine = null
                                stopSelf()
                            } else {
                                Log.d("MyVpnService", "GST Engine (full tunnel) started")
                                connectionPhaseFlow.value = Phase.CONNECTED
                            }
                        }
                    } else if (isHybrid) {
                        // Hybrid Routing (Phase 6): TCP (login/API) via a VLESS/Trojan tunnel,
                        // UDP (real gameplay) via WARP -- both baked into a single Xray config
                        // with two outbounds, so one engine instance handles both.
                        Log.d("MyVpnService", "Hybrid Routing config detected (TCP via tunnel, UDP via WARP)")
                        try {
                            val hybridJson = org.json.JSONObject(nodeUri)
                            val tunnelUri = hybridJson.getString("tunnelUri")
                            val warpParams = hybridJson.getJSONObject("warp")
                            val tunnelConfig = com.mlmvpn.scanner.utils.VpnConfig.parseUri(tunnelUri)
                            if (tunnelConfig == null) {
                                Log.e("MyVpnService", "Hybrid: failed to parse tunnel URI")
                                stopSelf()
                            } else {
                                val jsonConfig = com.mlmvpn.scanner.utils.XrayJsonGenerator.generateConfig(
                                    config = tunnelConfig,
                                    localPort = localPort,
                                    backendDns = backendDns,
                                    allowLan = allowLan,
                                    includeTun = true,
                                    mtu = mtu,
                                    useFragment = false,
                                    gameMode = true,
                                    warpHybrid = warpParams,
                                    dedicatedDnsUrl = dedicatedDnsUrl,
                                    dedicatedDnsDomains = dedicatedDnsDomains
                                )
                                currentEngine = com.mlmvpn.core.warp.VlessXrayInjector(fd)
                                val success = currentEngine?.start(this@MyVpnService, jsonConfig, localPort) ?: false
                                if (!success) {
                                    Log.e("MyVpnService", "Failed to start Hybrid engine")
                                    stopSelf()
                                } else {
                                    Log.d("MyVpnService", "Hybrid Engine Started Successfully!")
                                    connectionPhaseFlow.value = Phase.CONNECTED
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MyVpnService", "Failed to build Hybrid config", e)
                            stopSelf()
                        }
                    } else if (isSoftEther) {
                        Log.d("MyVpnService", "SoftEther config detected")
                        CrashReporter.note("engine start: SoftEther")
                        autoSwitchJob?.cancel()
                        currentEngine = com.mlmvpn.scanner.engines.vpngate.SoftEtherEngine()
                        val success = currentEngine?.start(this@MyVpnService, nodeUri, localPort) ?: false
                        if (!success) {
                            Log.e("MyVpnService", "Failed to start SoftEther engine")
                            try { currentEngine?.stop() } catch (_: Exception) {}
                            currentEngine = null
                            connectionPhaseFlow.value = Phase.FAILED
                            isRunning = false
                            connectedNodeId = null
                            stopSelf()
                        } else {
                            Log.d("MyVpnService", "SoftEther Engine Started Successfully!")
                            connectionPhaseFlow.value = Phase.CONNECTED
                        }
                    } else if (isVpnGate) {
                        Log.d("MyVpnService", "VPN Gate (OpenVPN) config detected")
                        // Auto-switch re-dials whatever URI is stored for the node as a VLESS
                        // config; it cannot do anything with a vpngate:// sentinel, so keep it
                        // out of this session entirely.
                        autoSwitchJob?.cancel()
                        // openvpn3 driven directly, in this process, on this service's own TUN.
                        // The AAR's own service wrapper is only a shell around the same core
                        // and silently discards every diagnostic it produces.
                        currentEngine = com.mlmvpn.scanner.engines.vpngate.Ovpn3Engine()
                        val success = currentEngine?.start(this@MyVpnService, nodeUri, localPort) ?: false
                        if (!success) {
                            Log.e("MyVpnService", "Failed to start VPN Gate engine")
                            try { currentEngine?.stop() } catch (_: Exception) {}
                            currentEngine = null
                            connectionPhaseFlow.value = Phase.FAILED
                            isRunning = false
                            connectedNodeId = null
                            stopSelf()
                        } else {
                            Log.d("MyVpnService", "VPN Gate Engine Started Successfully!")
                            connectionPhaseFlow.value = Phase.CONNECTED
                        }
                    } else if (isAmneziaWg) {
                        Log.d("MyVpnService", "AmneziaWG Config detected")
                        currentEngine = com.mlmvpn.core.warp.AmneziaWgInjector(fd)
                        val success = currentEngine?.start(this@MyVpnService, nodeUri, localPort) ?: false
                        if (!success) {
                            Log.e("MyVpnService", "Failed to start AmneziaWG engine")
                            // Defense in depth: don't leave a failed engine sitting in
                            // currentEngine. stopSelf() below doesn't tear the service down
                            // synchronously -- if the user retries before onDestroy() runs,
                            // the next onStartCommand's cleanup would call stop() on this same
                            // (already-failed) instance. AmneziaWgInjector now defends against
                            // that itself, but clearing here too keeps every engine type safe.
                            try { currentEngine?.stop() } catch (_: Exception) {}
                            currentEngine = null
                            stopSelf()
                        } else {
                            Log.d("MyVpnService", "AmneziaWG Engine Started Successfully!")
                            connectionPhaseFlow.value = Phase.CONNECTED
                        }
                    } else if (nodeUri.startsWith("{") && nodeUri.contains("\"inbounds\"") && nodeUri.contains("\"outbounds\"")) {
                        Log.d("MyVpnService", "Raw JSON Config detected (${nodeUri.length} chars)")
                        val jsonRemarks = try { org.json.JSONObject(nodeUri).optString("remarks") } catch (_: Exception) { "" }
                        com.mlmvpn.scanner.engines.gst.GstLog.i(
                            "MyVpnService",
                            "connecting JSON config (node=$connectedNodeId${if (jsonRemarks.isNotBlank()) ", remarks=$jsonRemarks" else ""}, proxyMode=$isProxyMode)"
                        )
                        
                        // Inject tun inbound for VPN mode so Xray can capture tun traffic
                        val finalConfig = if (!isProxyMode && fd != 0) {
                            try {
                                val jsonObj = org.json.JSONObject(nodeUri)
                                val inbounds = jsonObj.getJSONArray("inbounds")
                                
                                // Check if tun inbound already exists (Xray uses protocol: tun, Singbox uses type: tun)
                                var hasTun = false
                                for (i in 0 until inbounds.length()) {
                                    val ib = inbounds.getJSONObject(i)
                                    if (ib.optString("protocol") == "tun" || ib.optString("type") == "tun") {
                                        hasTun = true
                                        break
                                    }
                                }
                                
                                if (!hasTun) {
                                    val tunInbound = org.json.JSONObject().apply {
                                        put("protocol", "tun")
                                        put("tag", "tun-in")
                                        put("settings", org.json.JSONObject().apply {
                                            put("mtu", mtu)
                                            put("autoRoute", false)
                                            put("strictRoute", false)
                                            put("endpoint", "10.0.0.2")
                                            put("stack", "system")
                                        })
                                        put("sniffing", org.json.JSONObject().apply {
                                            put("enabled", true)
                                            put("destOverride", org.json.JSONArray().put("fakedns").put("tls").put("http").put("quic"))
                                            put("routeOnly", false)
                                        })
                                    }
                                    // Insert tun as the first inbound
                                    val newInbounds = org.json.JSONArray()
                                    newInbounds.put(tunInbound)
                                    for (i in 0 until inbounds.length()) {
                                        newInbounds.put(inbounds.getJSONObject(i))
                                    }
                                    jsonObj.put("inbounds", newInbounds)
                                    Log.d("MyVpnService", "Injected tun inbound into JSON config for VPN mode")
                                }

                                // The app's connectivity/real-IP probe expects a local HTTP proxy on
                                // localPort+10000 (default 20808). Raw JSON default configs only ship a
                                // mixed inbound on 10808, so the probe hammered 127.0.0.1:20808 every
                                // couple of seconds with an 8s-timeout ECONNREFUSED (visible in logcat as
                                // "Failed to connect to /127.0.0.1:20808"). Inject the missing HTTP
                                // inbound so the probe works and the spam stops.
                                run {
                                    val probePort = localPort + 10000
                                    val cur = jsonObj.getJSONArray("inbounds")
                                    var hasProbe = false
                                    for (i in 0 until cur.length()) {
                                        if (cur.getJSONObject(i).optInt("port") == probePort) { hasProbe = true; break }
                                    }
                                    if (!hasProbe) {
                                        cur.put(org.json.JSONObject().apply {
                                            put("tag", "http-probe-in")
                                            put("port", probePort)
                                            put("protocol", "http")
                                            put("listen", "127.0.0.1")
                                            put("settings", org.json.JSONObject())
                                        })
                                        com.mlmvpn.scanner.engines.gst.GstLog.i(
                                            "MyVpnService", "Injected HTTP probe inbound on 127.0.0.1:$probePort"
                                        )
                                    }
                                }

                                jsonObj.toString()
                            } catch (e: Exception) {
                                Log.e("MyVpnService", "Failed to inject tun inbound, using raw config", e)
                                nodeUri
                            }
                        } else {
                            nodeUri
                        }
                        
                        
                        currentEngine = com.mlmvpn.core.warp.VlessXrayInjector(fd)
                        
                        val success = currentEngine?.start(this@MyVpnService, finalConfig, localPort) ?: false
                        if (!success) {
                            com.mlmvpn.scanner.engines.gst.GstLog.e("MyVpnService", "JSON engine failed to start")
                            Log.e("MyVpnService", "Failed to start JSON engine")
                            stopSelf()
                        } else {
                            com.mlmvpn.scanner.engines.gst.GstLog.i("MyVpnService", "JSON engine started — CONNECTED")
                            Log.d("MyVpnService", "JSON Engine Started Successfully!")
                            connectionPhaseFlow.value = Phase.CONNECTED
                        }
                    } else {
                        val config = com.mlmvpn.scanner.utils.VpnConfig.parseUri(nodeUri)
                        if (config == null) {
                            Log.e("MyVpnService", "Failed to parse VLESS URI")
                            stopSelf()
                            return@withLock
                        }
                        
                        Log.d("MyVpnService", "VLESS Config: addr=${config.address}:${config.port} sni=${config.sni} host=${config.wsHost} path=${config.wsPath}")

                        // Auto-start RSTA Spoof if this is an SNI config (routes through local RSTA proxy)
                        val isSniConfig = config.address == "127.0.0.1" && config.port == 40443
                        if (isSniConfig) {
                            Log.d("MyVpnService", ">>> SNI config detected â€” ensuring RSTA Spoof is running")
                            val rstaOk = com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.ensureRunning(this@MyVpnService)
                            if (!rstaOk) {
                                Log.e("MyVpnService", "RSTA Spoof failed to start â€” cannot connect SNI config")
                                stopSelf()
                                return@withLock
                            }
                            // Give RSTA a moment to fully bind
                            Thread.sleep(300)
                            val portReady = com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.isPortOpen("127.0.0.1", 40443, 1000)
                            Log.d("MyVpnService", "RSTA port 40443 ready: $portReady")
                            if (!portReady) {
                                Log.e("MyVpnService", "RSTA port 40443 is NOT listening â€” aborting connection")
                                stopSelf()
                                return@withLock
                            }
                        }

                        val jsonConfig = com.mlmvpn.scanner.utils.XrayJsonGenerator.generateConfig(
                            config = config,
                            localPort = localPort,
                            backendDns = backendDns,
                            allowLan = allowLan,
                            includeTun = true,
                            mtu = mtu,
                            useFragment = false,
                            gameMode = isGameMode,
                            dedicatedDnsUrl = dedicatedDnsUrl,
                            dedicatedDnsDomains = dedicatedDnsDomains
                        )
                        Log.d("MyVpnService", "Xray JSON config generated (${jsonConfig.length} chars)")
                        
                        currentEngine = com.mlmvpn.core.warp.VlessXrayInjector(fd)
                        val success = currentEngine?.start(this@MyVpnService, jsonConfig, localPort) ?: false
                        if (!success) {
                            Log.e("MyVpnService", "Failed to start VLESS engine")
                            stopSelf()
                        } else {
                            Log.d("MyVpnService", "VLESS Engine Started Successfully!" + if (isSniConfig) " [via RSTA SNI Spoof]" else "")
                            connectionPhaseFlow.value = Phase.CONNECTED
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MyVpnService", "Failed to start Engine", e)
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    private fun setupVpn(backendDns: String = "1.1.1.1", mtu: Int = 1420, isRawJsonConfig: Boolean = false) {
        if (vpnInterface != null) return

        try {
            val builder = Builder()
                .setSession("Cloudflare VPN")
                .addAddress("10.0.0.2", 32)
                .addAddress("fd00:1:2:3:4:5:6:2", 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer(backendDns)
                .addDnsServer("8.8.8.8")
                .setMtu(mtu)
            // Routing Logic
            val gamePrefs = applicationContext.getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE)
            val isGameMode = gamePrefs.getBoolean("game_mode_active", false)

            if (isGameMode) {
                val gamePackage = gamePrefs.getString("game_package", null)
                if (gamePackage != null) {
                    try {
                        builder.addAllowedApplication(gamePackage)
                        Log.d("MyVpnService", "Game Mode: Only routing $gamePackage through VPN")
                    } catch (e: Exception) {
                        Log.e("MyVpnService", "Failed to add game package to VPN routing", e)
                    }
                }
            } else {
                val prefs = applicationContext.getSharedPreferences("vpn_routing_prefs", android.content.Context.MODE_PRIVATE)
                val routingMode = prefs.getString("vpn_routing_mode", "ALL") ?: "ALL"
                val routingApps = prefs.getStringSet("vpn_routing_apps", emptySet()) ?: emptySet()

                if (routingMode == "ALLOW") {
                    for (app in routingApps) {
                        if (app != applicationContext.packageName) {
                            try { builder.addAllowedApplication(app) } catch (e: Exception) {}
                        }
                    }
                } else if (routingMode == "BYPASS") {
                    for (app in routingApps) {
                        try { builder.addDisallowedApplication(app) } catch (e: Exception) {}
                    }
                    try { builder.addDisallowedApplication(applicationContext.packageName) } catch (e: Exception) {}
                } else {
                    try { builder.addDisallowedApplication(applicationContext.packageName) } catch (e: Exception) {}
                }
            }
            vpnInterface = builder.establish()
        } catch (e: Exception) {
            Log.e("MyVpnService", "Failed to setup VPN", e)
        }
    }

    private fun setupScreenReceiver() {
        if (screenReceiver != null) return
        screenReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                val action = intent.action
                if (action == Intent.ACTION_SCREEN_OFF) {
                    val defaultPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                    val timeoutStr = defaultPrefs.getString("screen_off_timeout", "0") ?: "0"
                    val timeoutMinutes = timeoutStr.toLongOrNull() ?: 0L
                    
                    if (timeoutMinutes > 0) {
                        Log.d("MyVpnService", "Screen off. Scheduling disconnect in $timeoutMinutes minutes.")
                        disconnectJob?.cancel()
                        disconnectJob = serviceScope.launch {
                            kotlinx.coroutines.delay(timeoutMinutes * 60 * 1000)
                            Log.d("MyVpnService", "Timeout reached. Disconnecting VPN.")
                            val stopIntent = Intent(context, MyVpnService::class.java).apply { this.action = "STOP" }
                            startService(stopIntent)
                        }
                    } else {
                        Log.d("MyVpnService", "Screen off. Timeout is 0 (Always On).")
                    }
                } else if (action == Intent.ACTION_SCREEN_ON) {
                    Log.d("MyVpnService", "Screen on. Canceling disconnect timer.")
                    disconnectJob?.cancel()
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onDestroy() {
        // Teardown is where the native engines have historically aborted (double-closed fds,
        // locks taken on destroyed objects), so mark both ends of it.
        CrashReporter.note("MyVpnService.onDestroy begin engine=${currentEngine?.javaClass?.simpleName}")
        // Only clear the static handle if it still points at us. Android can construct the
        // next MyVpnService before the previous one's onDestroy() runs; an unconditional
        // null here would blank out the LIVE instance and leave callers holding nothing.
        if (instance === this) instance = null
        try {
            networkWatchdogCallback?.let {
                (getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)
                    ?.unregisterNetworkCallback(it)
            }
        } catch (e: Exception) {}
        networkWatchdogCallback = null
        reconnectJob?.cancel()
        lastConnectIntent = null
        isRunning = false
        connectedNodeId = null
        isRunningFlow.value = false
        connectedNodeIdFlow.value = null
        if (connectionPhaseFlow.value != Phase.FAILED) connectionPhaseFlow.value = Phase.IDLE
        try {
            getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("game_mode_active", false).apply()
        } catch (_: Exception) {}
        super.onDestroy()
        serviceScope.launch {
            xrayMutex.withLock {
                try {
                    // Close TUN interface first to stop packet flow into Xray
                    vpnInterface?.close()
                } catch (e: Exception) {}
                vpnInterface = null

                // Wait for Xray to drain its internal queues
                kotlinx.coroutines.delay(300)

                try {
                    currentEngine?.stop()
                } catch (e: Exception) {}
                currentEngine = null
            }
        }
        
        try {
            screenReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {}
        screenReceiver = null

        disconnectJob?.cancel()
        autoSwitchJob?.cancel()
        trafficMonitorJob?.cancel()
        stressTestJob?.cancel()
        
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d("MyVpnService", "WakeLock released")
            }
        } catch (e: Exception) {}
        wakeLock = null
    }

    private fun startTrafficMonitor() {
        trafficMonitorJob?.cancel()
        trafficMonitorJob = serviceScope.launch {
            val trafficManager = com.mlmvpn.scanner.data.TrafficManager(this@MyVpnService)
            val sessionPrefs = getSharedPreferences("vpn_session_traffic", android.content.Context.MODE_PRIVATE)
            sessionPrefs.edit()
                .putLong("session_rx", 0L)
                .putLong("session_tx", 0L)
                .apply()

            // Don't start counting until the tunnel is actually up.
            //
            // The counters below are per-UID, and everything this app does runs under that
            // UID — including the Aether engine's gateway scan, which probes up to ~2000
            // endpoints before a tunnel exists. Sampling from service start made the
            // on-screen up/down meter tick during the scan, showing "traffic" on a
            // connection the user could plainly see was not established yet.
            while (connectionPhaseFlow.value == Phase.CONNECTING) {
                kotlinx.coroutines.delay(500)
            }
            if (connectionPhaseFlow.value != Phase.CONNECTED) return@launch

            val isGameTrial = connectedNodeId == "game_uae_trial"
            var lastRx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid()).coerceAtLeast(0L)
            var lastTx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid()).coerceAtLeast(0L)
            
            // Fallback for devices where UDP getUidRxBytes is broken (e.g., Xiaomi/MTK)
            var lastTotalRx = android.net.TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
            var lastTotalTx = android.net.TrafficStats.getTotalTxBytes().coerceAtLeast(0L)

            var sessionRx = 0L
            var sessionTx = 0L

            try {
                while (true) {
                    kotlinx.coroutines.delay(2000)

                    val currentRx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid()).coerceAtLeast(0L)
                    val currentTx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid()).coerceAtLeast(0L)
                    val currentTotalRx = android.net.TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
                    val currentTotalTx = android.net.TrafficStats.getTotalTxBytes().coerceAtLeast(0L)

                    var rxDelta = if (currentRx > lastRx) currentRx - lastRx else 0L
                    var txDelta = if (currentTx > lastTx) currentTx - lastTx else 0L

                    // If UID traffic is 0 (due to unconnected UDP sockets bug in xray-core WireGuard)
                    // we fallback to device-wide traffic divided by 2 (since VPN double-counts TUN + Wi-Fi)
                    if (isGameTrial && rxDelta == 0L && txDelta == 0L) {
                        val totalRxDelta = if (currentTotalRx > lastTotalRx) currentTotalRx - lastTotalRx else 0L
                        val totalTxDelta = if (currentTotalTx > lastTotalTx) currentTotalTx - lastTotalTx else 0L
                        rxDelta = totalRxDelta / 2
                        txDelta = totalTxDelta / 2
                    }

                    sessionRx += rxDelta
                    sessionTx += txDelta
                    sessionPrefs.edit()
                        .putLong("session_rx", sessionRx)
                        .putLong("session_tx", sessionTx)
                        .apply()

                    val defaultPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@MyVpnService)
                    if (defaultPrefs.getBoolean("enable_usage_tracking", true)) {
                        trafficManager.addTraffic(rxDelta, txDelta)
                    }

                    lastRx = currentRx
                    lastTx = currentTx
                    lastTotalRx = currentTotalRx
                    lastTotalTx = currentTotalTx
                }
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    val currentRx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid()).coerceAtLeast(0L)
                    val currentTx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid()).coerceAtLeast(0L)
                    val currentTotalRx = android.net.TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
                    val currentTotalTx = android.net.TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
                    
                    var rxDelta = if (currentRx > lastRx) currentRx - lastRx else 0L
                    var txDelta = if (currentTx > lastTx) currentTx - lastTx else 0L
                    
                    if (isGameTrial && rxDelta == 0L && txDelta == 0L) {
                        val totalRxDelta = if (currentTotalRx > lastTotalRx) currentTotalRx - lastTotalRx else 0L
                        val totalTxDelta = if (currentTotalTx > lastTotalTx) currentTotalTx - lastTotalTx else 0L
                        rxDelta = totalRxDelta / 2
                        txDelta = totalTxDelta / 2
                    }
                    
                    sessionRx += rxDelta
                    sessionTx += txDelta
                    sessionPrefs.edit()
                        .putLong("session_rx", sessionRx)
                        .putLong("session_tx", sessionTx)
                        .apply()
                    val defaultPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@MyVpnService)
                    if (defaultPrefs.getBoolean("enable_usage_tracking", true)) {
                        trafficManager.addTraffic(rxDelta, txDelta)
                    }
                }
            }
        }
    }

    private fun startStressTestMonitor() {
        stressTestJob?.cancel()
        stressTestJob = serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5000) // Every 5 seconds
                val runtime = Runtime.getRuntime()
                val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L
                val totalMem = runtime.totalMemory() / 1048576L
                val maxMem = runtime.maxMemory() / 1048576L
                Log.d("StressTest", "RAM Usage: ${usedMem}MB / ${totalMem}MB (Max: ${maxMem}MB) | Engine Running: ${currentEngine != null}")
            }
        }
    }

    private fun startAutoSwitchMonitor() {
        autoSwitchJob?.cancel()
        autoSwitchJob = serviceScope.launch {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@MyVpnService)
            
            while (true) {
                // Check if auto-switch is enabled
                val isAutoSwitchEnabled = prefs.getBoolean("auto_switch_enabled", false)
                if (!isAutoSwitchEnabled) {
                    kotlinx.coroutines.delay(10000) // check again in 10s
                    continue
                }
                
                val intervalMinutes = prefs.getInt("auto_switch_interval", 15)
                kotlinx.coroutines.delay(intervalMinutes * 60 * 1000L)
                
                // Double check if still enabled after delay
                if (!prefs.getBoolean("auto_switch_enabled", false) || !isRunning) continue
                
                val platformStr = prefs.getString("auto_switch_platform", "None") ?: "None"
                
                Log.d("AutoSwitch", "Starting background test: Platform=$platformStr")
                
                val nodeManager = com.mlmvpn.scanner.data.NodeManager(this@MyVpnService)
                val nodes = nodeManager.nodes.toList()
                if (nodes.isEmpty()) continue
                
                var bestNode: com.mlmvpn.scanner.models.VpnNode? = null
                var bestScore = Long.MAX_VALUE
                
                val testCount = prefs.getInt("auto_switch_test_count", 20)
                val nodesToTest = if (testCount == 0) nodes.shuffled() else nodes.shuffled().take(testCount)
                
                for (node in nodesToTest) {
                    if (!isRunning) break
                    var currentScore = 0L
                    var valid = true
                    
                    if (platformStr != "None") {
                        val platform = try {
                            com.mlmvpn.scanner.utils.Platform.valueOf(platformStr)
                        } catch (e: Exception) { null }
                        
                        if (platform != null) {
                            val pDelay = xrayMutex.withLock {
                                com.mlmvpn.scanner.utils.PlatformTester.testNodeForPlatform(this@MyVpnService, node.uri, platform, 25000)
                            }
                            if (pDelay > 0) {
                                currentScore += pDelay
                            } else {
                                valid = false
                            }
                        }
                    }
                    
                    if (valid && platformStr == "None") {
                        val config = com.mlmvpn.scanner.utils.VpnConfig.parseUri(node.uri)
                        if (config != null) {
                            val delay = if (config.tls == "tls" || config.sni.isNotEmpty()) {
                                com.mlmvpn.scanner.ui.tlsPing(config.address, config.port, config.sni.ifEmpty { config.wsHost })
                            } else {
                                com.mlmvpn.scanner.ui.httpDelay(config.address, config.port)
                            }
                            if (delay > 0) currentScore += delay else valid = false
                        } else {
                            valid = false
                        }
                    }
                    
                    if (valid && currentScore < bestScore) {
                        bestScore = currentScore
                        bestNode = node
                    }
                }
                
                if (bestNode != null && connectedNodeId != bestNode.id && isRunning) {
                    Log.d("AutoSwitch", "Found better node: ${bestNode.name} with score $bestScore. Switching...")
                    
                    val isProxyMode = prefs.getBoolean("proxy_mode", false)
                    val localPort = prefs.getString("local_port", "10808")
                    val startIntent = Intent(this@MyVpnService, MyVpnService::class.java).apply {
                        putExtra("NODE_URI", bestNode.uri)
                        putExtra("NODE_ID", bestNode.id)
                        putExtra("PROXY_MODE", isProxyMode)
                        putExtra("LOCAL_PORT", localPort)
                    }
                    startService(startIntent)
                    break 
                } else {
                    Log.d("AutoSwitch", "Current node is still the best or no better node found.")
                }
            }
        }
    }
}
