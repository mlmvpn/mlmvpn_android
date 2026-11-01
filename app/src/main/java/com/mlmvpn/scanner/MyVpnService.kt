package com.mlmvpn.scanner

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.flow.first
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

    /**
     * Whether this service is one of the things holding the SNI front up.
     *
     * The front is a separate process and the service is what raises it for an SNI config, so the
     * service is also what has to put it down again -- which it never did. Disconnecting from the
     * connection list took the tunnel away and left the front running, and the home screen went on
     * showing the SNI lamp over whatever the user connected next.
     *
     * A flag rather than an unconditional release in `onDestroy`, because `onDestroy` runs for
     * every Xray disconnect and most of them never touched the front; and because the internal
     * reconnect re-runs the connect block without an `onDestroy` in between, which would claim the
     * front a second time and leave one claim behind on the way out.
     */
    private var frontHeld = false

    /**
     * Give the SNI front back, exactly once, whether we leave by failure or by disconnect.
     *
     * On a thread of its own because the main caller is `onDestroy`, which runs on the main
     * thread, and giving the front back can mean force-killing a process behind a lock. The flag
     * is cleared first, so the second caller returns without waiting on the first.
     */
    private fun releaseFront() {
        if (!frontHeld) return
        frontHeld = false
        Thread {
            runCatching { com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.release() }
        }.start()
    }

    companion object {
        @Volatile var activeGameSessionId: String? = null
        @Volatile var runtimeSocksPort: Int = 0
        @Volatile var runtimeControlSocksPort: Int = 0
        /** Game session route profiles, sent as the GAME_ROUTE_PROFILE extra. See setupVpn. */
        const val GAME_ROUTE_FULL = "FULL"
        const val GAME_ROUTE_DNS_ONLY = "DNS_ONLY"
        /** DNS_ONLY plus the addresses in the GAME_SPLIT_ROUTES extra, sent out fragmented. */
        const val GAME_ROUTE_SPLIT = "SPLIT"
        const val MAX_SPLIT_ROUTES = 16

        /**
         * The resolver address a DNS-only game session hands the game. Any address works as long
         * as nothing real lives there; it only ever exists inside the tunnel.
         */
        const val DEFAULT_GAME_DNS_IP = "10.0.0.53"

        /**
         * Inner MTU ceiling for the Aether TUN. See the use site for the byte-by-byte
         * reasoning; short version is that MASQUE/QUIC adds ~80 bytes of outer overhead and
         * sets DF, so the app-wide 1420 overflows a 1492-byte PPPoE path and the packets are
         * dropped outright. 1280 is the IPv6 minimum MTU — deliverable on any IP path.
         */
        const val AETHER_TUN_MTU = 1280

        val isRunningFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
        val connectedNodeIdFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        val connectionPhaseFlow = kotlinx.coroutines.flow.MutableStateFlow(Phase.IDLE)

        /**
         * True while the config this service is carrying is an SNI-spoof one.
         *
         * Published rather than re-derived, because the only other way to answer it from outside
         * was to look the connected node id up in [com.mlmvpn.scanner.data.NodeManager] and parse
         * its URI -- and that list loads asynchronously, so a caller that asked during the first
         * composition after a process start got "no" for a config that plainly was one. The home
         * screen did exactly that and lit the V2Ray lamp beside the SNI lamp, showing two engines
         * for one connection, and it stayed wrong because nothing in its cache key changed when
         * the list finally arrived.
         *
         * This service already computes the same fact, from the config it is actually connecting,
         * to decide whether to bring the local TLS front up first. That is the authority.
         */
        val sniSessionFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
        val xrayMutex = kotlinx.coroutines.sync.Mutex()
        var isRunning: Boolean
            get() = isRunningFlow.value
            set(value) { isRunningFlow.value = value }
        var connectedNodeId: String?
            get() = connectedNodeIdFlow.value
            set(value) { connectedNodeIdFlow.value = value }
        var instance: MyVpnService? = null
    }

    /** A traffic counter, or 0 when the system cannot answer (DeadSystemException while it restarts). */
    private inline fun stat(read: () -> Long): Long = try { read() } catch (_: RuntimeException) { 0L }

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.mlmvpn.scanner.utils.SafeStart.attach(this)
        com.mlmvpn.scanner.utils.ScreenState.watch(this)
        registerNetworkWatchdog()
        // The Xray core runs in its own process (XrayHosts). When Xray panics there, that process
        // ends and this service hears about it here -- before, the same panic closed the whole app.
        com.mlmvpn.core.warp.VlessXrayInjector.onTunnelLost = { serviceScope.launch { onXrayCoreLost() } }
        // If the update check on app launch failed silently (GitHub filtered/unreachable before
        // any tunnel was up), retry it once real connectivity exists -- whichever engine got us
        // there. Cheap: UpdateChecker throttles itself internally, so repeated CONNECTED events
        // (reconnects, engine switches) don't spam the API.
        serviceScope.launch {
            connectionPhaseFlow.collect { phase ->
                if (phase == Phase.CONNECTED) {
                    com.mlmvpn.scanner.update.UpdateChecker.checkForUpdate(applicationContext)
                    // The one point that means the engine actually came up. Recorded here rather
                    // than at start so the Quick Settings tile can never replay something that
                    // failed -- see LastEngine. Every feature that reaches this service does so
                    // with the same two extras, so one hook covers all of them.
                    // Not for game sessions: those are per-game configs (a DNS-only resolver, a
                    // game-only tunnel), and replaying one from the Quick Settings tile would
                    // bring it up as a whole-device VPN that routes nothing the user expects.
                    lastConnectIntent?.takeIf { !it.getBooleanExtra("GAME_MODE", false) &&
                        it.getStringExtra("NODE_ID") != com.mlmvpn.scanner.engines.windscribe.WindscribeEngine.NODE_ID }?.let { started ->
                        com.mlmvpn.scanner.data.LastEngine.recordXray(
                            applicationContext,
                            started.getStringExtra("NODE_URI"),
                            started.getStringExtra("NODE_ID"),
                        )
                    }
                }
            }
        }
    }

    private fun registerNetworkWatchdog() {
        if (networkWatchdogCallback != null) return
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        // The DEFAULT network only -- the one the tunnel really rides (this app is outside its own
        // tunnel, so its default is the underlying Wi-Fi or mobile network). This used to watch every
        // network with internet: with Wi-Fi and mobile data both on, mobile data going idle and
        // coming back -- which phones do all the time behind a working Wi-Fi -- tore the whole
        // tunnel down and built it again each time. Users of MAE saw it as constant drops.
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            private var current: android.net.Network? = null
            override fun onLost(network: android.net.Network) {
                if (network != current) return
                current = null
                if (isRunning && connectionPhaseFlow.value == Phase.CONNECTED) {
                    Log.w("MyVpnService", "Underlying network lost while connected -- will reconnect once it's back")
                    lostNetworkWhileConnected = true
                }
            }
            override fun onAvailable(network: android.net.Network) {
                val vpn = runCatching { cm.getNetworkCapabilities(network)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }.getOrDefault(false)
                if (vpn) return
                // A switch (Wi-Fi to mobile) can arrive as the new default before the old one is
                // reported lost: the tunnel's sockets are on the old one, so that is a drop too.
                val switched = current != null && current != network
                current = network
                if (switched && isRunning && connectionPhaseFlow.value == Phase.CONNECTED) lostNetworkWhileConnected = true
                if (lostNetworkWhileConnected && isRunning) {
                    lostNetworkWhileConnected = false
                    Log.d("MyVpnService", "Network back after a drop -- reconnecting")
                    triggerReconnect()
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkWatchdogCallback = cb
        } catch (e: Exception) {
            Log.w("MyVpnService", "Could not register network watchdog", e)
        }
    }

    /** When the core's process died under a running tunnel, in elapsed-realtime ms. */
    private val coreLosses = ArrayDeque<Long>()

    /**
     * The Xray core's process died under the tunnel (a Go panic in the core). A connected tunnel
     * reconnects, as after a network drop; a core that keeps dying (three times in two minutes:
     * the config itself panics it) ends the connection instead of looping.
     */
    private suspend fun onXrayCoreLost() {
        if (!isRunning) return
        CrashReporter.note("xray core process died under the tunnel")
        val now = android.os.SystemClock.elapsedRealtime()
        val again = synchronized(coreLosses) {
            coreLosses.addLast(now)
            while (coreLosses.isNotEmpty() && now - coreLosses.first() > 120_000) coreLosses.removeFirst()
            coreLosses.size
        }
        if (again < 3 && connectionPhaseFlow.value == Phase.CONNECTED && lastConnectIntent != null) {
            Log.w("MyVpnService", "Xray core process died -- reconnecting")
            triggerReconnect()
            return
        }
        Log.w("MyVpnService", "Xray core process died ($again in 2 min) -- ending the connection")
        xrayMutex.withLock {
            try { currentEngine?.stop() } catch (_: Exception) {}
            currentEngine = null
            try { vpnInterface?.close() } catch (_: Exception) {}
            vpnInterface = null
        }
        isRunning = false
        connectedNodeId = null
        lastConnectIntent = null
        connectionPhaseFlow.value = Phase.FAILED
        stopSelf()
    }

    private fun triggerReconnect() {
        val intentToRestore = lastConnectIntent ?: return
        if (reconnectJob?.isActive == true) return
        // The network usually comes back with the screen off. Without the CPU held for the
        // teardown, the wait and the new connect, the phone can sleep halfway through and leave the
        // tunnel down until something else wakes it.
        acquireWakeLock(CONNECT_LOCK_MS)
        reconnectJob = serviceScope.launch {
            val stopIntent = Intent(this@MyVpnService, MyVpnService::class.java).apply {
                action = "STOP"
                putExtra("INTERNAL_RECONNECT", true)
            }
            com.mlmvpn.scanner.utils.SafeStart.service(this@MyVpnService, stopIntent)
            // connectionPhaseFlow flips to IDLE synchronously at the top of the STOP handler,
            // before the actual (async, behind xrayMutex) engine teardown below it even starts --
            // it can't be used as a "teardown finished" signal. There's no such signal exposed,
            // so use a flat delay generous enough for the slowest engine (Aether killing a native
            // process) rather than racing a fresh connect against teardown still in flight.
            kotlinx.coroutines.delay(3000)
            // MAE compiles its config per network. The network that came back may be another one
            // (Wi-Fi -> mobile data): replaying the old config ran the previous network's routes
            // and DNS path, and MAE's own re-apply found the tunnel down and skipped. It builds a
            // fresh one instead; if that fails, the old config is still better than nothing.
            val restore = if (intentToRestore.getStringExtra("NODE_ID") == com.mlmvpn.scanner.engines.mae.MaeEngine.NODE_ID) {
                val fresh = runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.mlmvpn.scanner.engines.mae.MaeEngine.freshConfig(applicationContext)
                    }
                }.getOrNull()
                if (fresh != null) Intent(intentToRestore).putExtra("NODE_URI", fresh) else intentToRestore
            } else if (intentToRestore.getStringExtra("NODE_ID") == com.mlmvpn.scanner.engines.flux.FluxEngine.NODE_ID) {
                // FLUX too: the network that came back gets the route FLUX knows for it, when it
                // knows one; otherwise the old routes, which FLUX then checks and replaces if dead.
                val fresh = runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.mlmvpn.scanner.engines.flux.FluxEngine.freshConfig(applicationContext)
                    }
                }.getOrNull()
                if (fresh != null) Intent(intentToRestore).putExtra("NODE_URI", fresh) else intentToRestore
            } else if (intentToRestore.getStringExtra("NODE_ID") == com.mlmvpn.scanner.engines.fenix.FenixEngine.NODE_ID) {
                // FENIX: the same server with a pass that is still good (the stored config's may
                // have run out while the network was gone).
                val fresh = runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.mlmvpn.scanner.engines.fenix.FenixEngine.freshConfig(applicationContext)
                    }
                }.getOrNull()
                if (fresh != null) Intent(intentToRestore).putExtra("NODE_URI", fresh) else intentToRestore
            } else intentToRestore
            com.mlmvpn.scanner.utils.SafeStart.service(this@MyVpnService, restore)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP") {
            val expected = intent.getStringExtra(com.mlmvpn.scanner.utils.GameSessionOwner.EXPECTED)
            if (expected != null && expected != activeGameSessionId) return START_NOT_STICKY
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
                    if (expected != null && expected != activeGameSessionId) return@launch
                    try { currentEngine?.stop() } catch (e: Exception) {}
                    currentEngine = null
                    try { vpnInterface?.close() } catch (e: Exception) {}
                    vpnInterface = null
                }
                trafficMonitorJob?.cancel()
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
                //
                // Except the watchdog's own STOP: its restart is still 3 s away inside
                // triggerReconnect(), so no newer start command exists yet, and stopping here
                // destroyed the service -- and serviceScope with it -- cancelling the restart.
                // Measured on the phone (2026-09-29): Wi-Fi off/on left every engine on this
                // service disconnected for good. The pending restart keeps the service in use.
                if (intent?.getBooleanExtra("INTERNAL_RECONNECT", false) != true) stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        
        val gameSession = intent?.getStringExtra(com.mlmvpn.scanner.utils.GameSessionOwner.EXTRA)
        if (!com.mlmvpn.scanner.utils.GameSessionOwner.accepts(gameSession)) {
            if (!isRunning) stopSelf(startId)
            return START_NOT_STICKY
        }
        activeGameSessionId = gameSession
        isRunning = true
        connectedNodeId = intent?.getStringExtra("NODE_ID")
        connectionPhaseFlow.value = Phase.CONNECTING
        lostNetworkWhileConnected = false
        if (intent != null) lastConnectIntent = Intent(intent)

        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "MLMVPN::VpnWakeLock")
            // Not reference-counted: acquireWakeLock()/releaseWakeLock() are called repeatedly
            // from the traffic monitor below, and with the default counting behaviour a run of
            // acquires would need an equal run of releases to actually let the CPU sleep --
            // i.e. the idle release would silently never take effect.
            wakeLock?.setReferenceCounted(false)
            // For the connect only: the traffic monitor lets go as soon as the tunnel is up.
            acquireWakeLock(CONNECT_LOCK_MS)
        } catch (e: Exception) {
            Log.e("MyVpnService", "Failed to acquire WakeLock", e)
        }

        setupScreenReceiver()
        startTrafficMonitor()
        startAutoSwitchMonitor()
        
        val nodeUri = intent?.getStringExtra("NODE_URI") ?: return START_NOT_STICKY
        val isProxyMode = intent.getBooleanExtra("PROXY_MODE", false)
        // Validated, not merely parsed: a caller could pass a number that is legal as an int but
        // unusable as a port (see LocalPort). Falls back to the stored setting, then the default.
        val localPort = intent.getStringExtra("LOCAL_PORT")
            ?.takeIf { com.mlmvpn.scanner.utils.LocalPort.validate(it) == null }
            ?.trim()?.toInt()
            ?: com.mlmvpn.scanner.utils.LocalPort.get(this)
        // Tell the delay tester which ports are taken, so its throwaway instances cannot land
        // on the tunnel's own inbound or its probe.
        com.mlmvpn.scanner.utils.XrayJsonGenerator.reservePorts(localPort)
        runtimeSocksPort = localPort

        val isGameMode = intent.getBooleanExtra("GAME_MODE", false)
        if (isGameMode) {
            val gamePackage = intent.getStringExtra("GAME_PACKAGE")
            getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("game_mode_active", true)
                .putString("game_package", gamePackage)
                // How much of the game's traffic enters the tunnel -- see setupVpn. Callers that
                // do not say get FULL, which is exactly what every game session did before.
                .putString("game_route_profile", intent.getStringExtra("GAME_ROUTE_PROFILE") ?: GAME_ROUTE_FULL)
                .putString("game_dns_ip", intent.getStringExtra("GAME_DNS_IP") ?: DEFAULT_GAME_DNS_IP)
                // DNS_ONLY plus these IPv4 addresses (comma list): the hosts whose TLS the session
                // sends out fragmented. Empty for every other profile.
                .putString("game_split_routes", intent.getStringExtra("GAME_SPLIT_ROUTES") ?: "")
                .apply()
            Log.d("MyVpnService", "Game Mode activated for: $gamePackage")
        } else {
            // A normal connect must not inherit the last game session's routing. The flag was
            // only ever cleared by STOP and onDestroy, so connecting a config straight after a
            // game session (no STOP in between) kept the tunnel limited to the game app.
            getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("game_mode_active", false)
                .remove("game_route_profile")
                .remove("game_dns_ip")
                .remove("game_split_routes")
                .apply()
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

        // Through NetworkSettings, not straight out of two different preference files. These
        // are app-wide choices that every engine has to agree on; read locally they drifted.
        val backendDns = com.mlmvpn.scanner.utils.NetworkSettings.primaryDns(this)
        // sharingActive, not allowLan: the raw switch says what the user chose, this says
        // whether it applies right now once the share timer and the own-network-only rule
        // have had their say.
        val allowLan = com.mlmvpn.scanner.lan.LanShare.sharingActive(this)
        val lanPassword = com.mlmvpn.scanner.utils.NetworkSettings.lanPassword(this)
            .takeIf { allowLan }
        val lanBlocked = com.mlmvpn.scanner.utils.NetworkSettings.lanBlocked(this)
            .takeIf { allowLan }.orEmpty()
        val vpnPrefs = getSharedPreferences("vpn_routing_prefs", android.content.Context.MODE_PRIVATE)

        // Which of this service's four features is being started, so the right MTU field applies.
        //
        // This service carries V2Ray configs, Quick Connect, the MLM Gateway and SNI anti-filter,
        // and until now they were indistinguishable once the intent arrived: every caller passed
        // NODE_URI and NODE_ID and nothing else. They are different products on different paths
        // -- Gateway is SoftEther inside TLS inside TCP, Quick Connect is whatever the pool row
        // says -- so they cannot share one MTU any more than they share one server. Callers that
        // do not name a profile are treated as V2Ray, which is what they all were.
        val mtuMethod = com.mlmvpn.scanner.utils.NetworkSettings.Method.byId(
            intent.getStringExtra("MTU_PROFILE")
        ) ?: com.mlmvpn.scanner.utils.NetworkSettings.Method.V2RAY
        val mtu = com.mlmvpn.scanner.utils.NetworkSettings.mtu(this, mtuMethod)

        serviceScope.launch {
            xrayMutex.withLock {
                if (gameSession != null && (!com.mlmvpn.scanner.utils.GameSessionOwner.accepts(gameSession) ||
                        activeGameSessionId != gameSession)) return@launch
                // The five-transport stack (MASQUE/WireGuard/WoW/Psiphon/Tor) runs under its own
                // VpnService, and Android grants the device tun to exactly one at a time. The
                // second establish() does not fail -- it takes the interface away from the first,
                // which carries on believing it still has one. So this is the choke point every
                // Xray-family connect passes through, and it makes sure the other side has let
                // go before we build a tun of our own. A no-op, and one volatile read, whenever
                // nothing else is running.
                com.mlmvpn.scanner.ui.tunnel.TunnelExclusion.releaseForXray(this@MyVpnService)

                // Clean up any existing connection before starting a new one. The core first, then
                // the interface: closing the interface first freed its descriptor while the core
                // still read and wrote it, and the number was soon handed to some other file --
                // which the core then wrote packets into and later closed (fdsan aborts, a core
                // spinning on a dead descriptor). The core holds its own copy now (XrayHosts), but
                // an engine that runs here still uses the interface's.
                try { currentEngine?.stop() } catch (e: Exception) {}
                currentEngine = null

                try { vpnInterface?.close() } catch (e: Exception) {}
                vpnInterface = null

                kotlinx.coroutines.delay(300)

                val isRawJsonConfig = nodeUri.startsWith("{") && nodeUri.contains("\"inbounds\"") && nodeUri.contains("\"outbounds\"")
                val isAmneziaWg = nodeUri.trim().startsWith("[Interface]")
                // Aether establishes its TUN late, from inside AetherTunEngine, once the
                // engine is actually carrying traffic — see the openTun callback there.
                // Bringing the interface up here instead would blackhole the whole device
                // for the duration of the gateway scan.
                val isAetherCfg = nodeUri.startsWith("{") &&
                    org.json.JSONObject(nodeUri).optString("type") ==
                        com.mlmvpn.core.aether.AetherTunEngine.CONFIG_TYPE
                // SoftEther's own SSL-VPN: the vendored client owns the TUN, so this service
                // deliberately does not build one. Same arrangement as AmneziaWG above.
                val isSoftEther = nodeUri.startsWith(
                    com.mlmvpn.scanner.engines.vpngate.SoftEtherEngine.URI_SCHEME)
                // These engines own their own TUN, so this service deliberately does not build
                // one — fd stays 0 by design, exactly as in proxy mode.
                val engineOwnsTun = isAmneziaWg || isAetherCfg || isSoftEther
                var fd = 0
                if (!isProxyMode && !engineOwnsTun) {
                    setupVpn(backendDns, mtu, isRawJsonConfig, noIpv6 = isRawJsonConfig && (routesByApp(nodeUri) || fluxIpv4Only(nodeUri)))
                    fd = vpnInterface?.fd ?: 0
                }
                // Surface the TUN state in the in-app GST log so we can tell whether the
                // system VPN actually came up (fd>0 => VPN key icon; fd=0 => no TUN).
                //
                // `engineOwnsTun` has to be part of this verdict. Without it every SoftEther,
                // VPN Gate, AmneziaWG and Aether session logged "TUN establish FAILED — check
                // VPN permission" on a perfectly healthy connection, purely because this
                // branch never ran. Two full debugging sessions were spent chasing that line.
                com.mlmvpn.scanner.engines.gst.GstLog.i(
                    "MyVpnService",
                    "connect: proxyMode=$isProxyMode, vpnFd=$fd " +
                        (if (isProxyMode) "(proxy mode ON → no TUN by design)"
                         else if (engineOwnsTun) "(engine owns the TUN → none built here, by design)"
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
                        // Aether needs a SMALLER inner MTU than the app-wide default.
                        //
                        // The shared default is 1420, chosen for Xray's VLESS/TCP outbounds.
                        // MASQUE is UDP: every inner packet is wrapped in QUIC (short header +
                        // connection id + packet number ≈ 20B, AEAD tag 16B, DATAGRAM frame
                        // header ≈ 3B, connect-ip context ≈ 2B) plus UDP (8B) and IPv4 (20B) —
                        // roughly 70-80 bytes. 1420 + 80 lands at ~1500, i.e. exactly the
                        // Ethernet ceiling with zero headroom, and QUIC sets DF so anything
                        // over the real path MTU is DROPPED rather than fragmented. On PPPoE
                        // (1492, the norm on Iranian ADSL/VDSL) every full-size packet dies,
                        // PMTU discovery black-holes, and TCP inside the tunnel spends its
                        // life retransmitting. Small requests still work, large transfers
                        // stall — which is precisely the reported "connects fine but laggy".
                        //
                        // 1280 is the IPv6 minimum MTU, so it is deliverable on every path
                        // that carries IP at all. Roughly 10% more per-packet overhead, in
                        // exchange for no drops.
                        // A DEFAULT now, not a cap.
                        //
                        // It used to be `minOf(mtu, 1280)`, which silently overrode anything the
                        // user set above it -- so on this path the MTU field in Settings could
                        // only ever be turned down, and a user who raised it saw no change and no
                        // reason. The number below is still what an untouched install gets, for
                        // every reason set out above; an explicit choice is now obeyed, including
                        // upward. That is the point of the setting.
                        // MASQUE's field, not this feature's: the constraint being answered here
                        // is QUIC's framing overhead, which is the same overhead the MASQUE
                        // transport measures itself against, and a user who tuned one and found
                        // the other unchanged would be right to call that broken.
                        val aetherMtu = com.mlmvpn.scanner.utils.NetworkSettings.mtu(
                            this@MyVpnService, com.mlmvpn.scanner.utils.NetworkSettings.Method.MASQUE
                        )
                        Log.i(com.mlmvpn.core.aether.AetherEngine.PERF_TAG,
                            "tun mtu: $aetherMtu (default $AETHER_TUN_MTU for MASQUE/QUIC overhead)")
                        currentEngine = com.mlmvpn.core.aether.AetherTunEngine(
                            openTun = {
                                setupVpn(backendDns, aetherMtu, false)
                                // Hand tun2proxy the RAW detached fd and drop our
                                // ParcelFileDescriptor so the generic vpnInterface?.close()
                                // cleanup paths don't double-close a fd tun2proxy now owns.
                                val raw = try { vpnInterface?.detachFd() ?: 0 } catch (e: Exception) { 0 }
                                vpnInterface = null
                                com.mlmvpn.scanner.engines.gst.GstLog.i(
                                    "MyVpnService", "Aether: TUN established late, fd=$raw")
                                raw
                            },
                            mtu = aetherMtu,
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
                            runtimeSocksPort = com.mlmvpn.core.aether.AetherEngine.AETHER_SOCKS_PORT
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
                                // Same pre-resolve as the plain VLESS path below; the hybrid
                                // config's TCP leg dials the same kind of server domain.
                                val pinnedHybridHosts =
                                    com.mlmvpn.scanner.utils.DomainPreResolver.pinnedHostsFor(tunnelConfig)
                                val jsonConfig = com.mlmvpn.scanner.utils.XrayJsonGenerator.generateConfig(
                                    config = tunnelConfig,
                                    localPort = localPort,
                                    backendDns = backendDns,
                                    allowLan = allowLan,
                                    lanPassword = lanPassword,
                                    lanBlocked = lanBlocked,
                                    // fd==0 means setupVpn() was skipped (proxy mode, or a TUN
                                    // establish failure) -- a "tun" inbound with no real fd to
                                    // attach to isn't just inert, it hands xray-core a bogus
                                    // descriptor it can still try to read/write, which can crash
                                    // the whole engine loop a few seconds after startLoop()
                                    // already returned success. See the same fix below.
                                    includeTun = fd > 0,
                                    mtu = mtu,
                                    useFragment = false,
                                    gameMode = true,
                                    warpHybrid = warpParams,
                                    dedicatedDnsUrl = dedicatedDnsUrl,
                                    dedicatedDnsDomains = dedicatedDnsDomains,
                                    pinnedHostIps = pinnedHybridHosts
                                )
                                currentEngine = com.mlmvpn.core.warp.VlessXrayInjector(fd, com.mlmvpn.core.warp.XrayHosts.TUNNEL)
                                val success = currentEngine?.start(this@MyVpnService, com.mlmvpn.core.tunnel.SmartIranRoute.injectXray(this@MyVpnService, jsonConfig), localPort) ?: false
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
                    } else if (isAmneziaWg) {
                        runtimeSocksPort = 0 // Native WireGuard owns the VPN; it has no SOCKS listener.
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
                            // FAILED, like SoftEther: a caller waiting on the phase hears "no" at
                            // once instead of sitting out its whole timeout.
                            connectionPhaseFlow.value = Phase.FAILED
                            isRunning = false
                            connectedNodeId = null
                            stopSelf()
                        } else {
                            Log.d("MyVpnService", "AmneziaWG Engine Started Successfully!")
                            connectionPhaseFlow.value = Phase.CONNECTED
                            // The tunnel lives in the :awg process. If that process dies, the
                            // tunnel is gone; the session must not go on claiming it is up.
                            com.mlmvpn.core.warp.AwgHostClient.tunnel.onDied = {
                                Log.w("MyVpnService", ":awg process died under a running tunnel -- stopping")
                                com.mlmvpn.scanner.utils.SafeStart.service(this@MyVpnService, Intent(this@MyVpnService, MyVpnService::class.java).setAction("STOP"))
                            }
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
                                
                                // A config that routes by APP (a rule with `process`) needs the
                                // connection's real destination address kept: the owner lookup asks
                                // Android about (source, destination) exactly as the app opened it,
                                // and sniffing that REPLACES the destination with the domain leaves
                                // it nothing to ask with — every app rule would silently never match.
                                // routeOnly keeps the domain for domain rules and the address for
                                // the lookup (setupVpn kept IPv6 off for it). Every other config
                                // keeps its behaviour.
                                val routesByApp = routesByApp(nodeUri)
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
                                            put("routeOnly", routesByApp)
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

                                // Local Network sharing hands other devices the tunnel through the
                                // listener on the Local Port. An engine's whole config (MAE, FLUX,
                                // FENIX, …) may ship none there, and then the share showed «ready»
                                // and dialled nothing: one is added, sniffing like the tun's.
                                if (allowLan) run {
                                    val cur = jsonObj.getJSONArray("inbounds")
                                    val has = (0 until cur.length()).any { cur.getJSONObject(it).optInt("port") == localPort }
                                    if (!has) {
                                        cur.put(org.json.JSONObject()
                                            .put("tag", "lan-in").put("port", localPort).put("listen", "127.0.0.1").put("protocol", "mixed")
                                            .put("settings", org.json.JSONObject().put("auth", "noauth").put("udp", true))
                                            .put("sniffing", org.json.JSONObject().put("enabled", true)
                                                .put("destOverride", org.json.JSONArray().put("tls").put("http").put("quic"))
                                                .put("routeOnly", routesByApp)))
                                        com.mlmvpn.scanner.engines.gst.GstLog.i("MyVpnService", "Injected Local Network inbound on 127.0.0.1:$localPort")
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

                        // A whole config brings its own routing, so the Gemini / Google apps fix
                        // that generateConfig builds in has to be fitted in here -- only where the
                        // tunnel is a worker, and not at all with the switch off. See
                        // XrayJsonGenerator.applyGoogleFix.
                        // MAE decides Google/Gemini routing itself, per service and per network.
                        val isMae = jsonRemarks == com.mlmvpn.scanner.engines.mae.compile.MaeConfigCompiler.REMARKS
                        // FLUX likewise: its routes, DNS and QUIC handling are its own.
                        val isFlux = com.mlmvpn.scanner.engines.flux.core.compile.FluxConfigCompiler.isFlux(jsonRemarks) ||
                            com.mlmvpn.scanner.engines.fenix.FenixEngine.isFenixConfig(jsonRemarks) ||
                            // Amnezia through Cloudflare: its routes and DNS are its own too.
                            com.mlmvpn.scanner.engines.amnezia.AmzCarrier.isCarrierConfig(jsonRemarks)
                        val startConfig = (if (isMae || isFlux) null else com.mlmvpn.scanner.utils.XrayJsonGenerator.applyGoogleFix(finalConfig))
                            ?.also {
                                com.mlmvpn.scanner.engines.gst.GstLog.i(
                                    "MyVpnService", "Gemini / Google apps fix applied to this config"
                                )
                            }
                            ?: finalConfig

                        // A whole config from an engine of its own (MAE, FLUX, FENIX) chose its handshake
                        // and checks its own data path; the Cloudflare engine neither reshapes it nor
                        // second-guesses it. Any other whole config with a Cloudflare outbound gets the
                        // proven CONNECT shape and must carry real data before it counts as connected.
                        val cfnet = com.mlmvpn.scanner.engines.cfnet.CfNetManager.get(this@MyVpnService)
                        val ownEngine = isMae || isFlux
                        runtimeControlSocksPort = 0
                        var working = false
                        var lastShape: com.mlmvpn.scanner.engines.cfnet.Strategy? = null
                        for (attempt in 0 until 2) {
                            val raw = org.json.JSONObject(startConfig)
                            val outs = raw.optJSONArray("outbounds")
                            val cfOutbound = if (ownEngine) null else outs?.let { list -> (0 until list.length()).mapNotNull(list::optJSONObject)
                                .firstOrNull(com.mlmvpn.scanner.utils.CfUnfilter::isCloudflareOutbound) }
                            // A clean-IP outbound with its own handshake is dialled as written, and its
                            // outcome is no evidence about the engine's strategies (judged before any shape).
                            val asWritten = cfOutbound?.let { cfnet.outboundConfig(it) }?.let(cfnet::dialledAsWritten) == true
                            // First the proven shape (if any); if that carries nothing, the config as written.
                            val selected = if (cfOutbound != null && attempt == 0) cfnet.savedForOutbound(com.mlmvpn.scanner.engines.cfnet.CfStage.CONNECT, cfOutbound) else null
                            if (attempt == 1 && lastShape == null) break
                            lastShape = selected
                            if (cfOutbound != null) {
                                selected?.data?.let { shape ->
                                    val stream = cfOutbound.getJSONObject("streamSettings")
                                    com.mlmvpn.scanner.engines.cfnet.CfShape.apply(stream, shape, stream.optString("network"))
                                }
                                val checkPort = java.net.ServerSocket(0).use { it.localPort }
                                val inbounds = raw.optJSONArray("inbounds") ?: org.json.JSONArray().also { raw.put("inbounds", it) }
                                inbounds.put(org.json.JSONObject().put("tag", "cfnet-check").put("listen", "127.0.0.1")
                                    .put("port", checkPort).put("protocol", "socks").put("settings", org.json.JSONObject().put("auth", "noauth").put("udp", false)))
                                val tag = cfOutbound.optString("tag").ifBlank { "cfnet-proxy".also { cfOutbound.put("tag", it) } }
                                routeCfProbe(raw, tag)
                                runtimeControlSocksPort = checkPort
                            }
                            // A serverless «کانفیگ ایران» has no server to fail on, so "the core
                            // started" said nothing: it was shown connected while opening nothing.
                            // It gets a check port of its own (its rules untouched) and must open a
                            // site through it before it counts as connected.
                            val serverlessCheckPort = if (cfOutbound == null && !ownEngine &&
                                com.mlmvpn.scanner.data.IranProfileTester.isServerless(raw)) {
                                java.net.ServerSocket(0).use { it.localPort }.also { p ->
                                    val inbounds = raw.optJSONArray("inbounds") ?: org.json.JSONArray().also { raw.put("inbounds", it) }
                                    val sniff = inbounds.optJSONObject(0)?.optJSONObject("sniffing")
                                    inbounds.put(org.json.JSONObject().put("tag", "iran-check").put("listen", "127.0.0.1")
                                        .put("port", p).put("protocol", "socks").put("settings", org.json.JSONObject().put("auth", "noauth").put("udp", false))
                                        .apply { sniff?.let { put("sniffing", org.json.JSONObject(it.toString())) } })
                                }
                            } else 0
                            // Smart Iran route: Iranian names and addresses leave by the direct outbound.
                            com.mlmvpn.core.tunnel.SmartIranRoute.injectXray(this@MyVpnService, raw)
                            currentEngine = com.mlmvpn.core.warp.VlessXrayInjector(fd, com.mlmvpn.core.warp.XrayHosts.TUNNEL)
                            val started = currentEngine?.start(this@MyVpnService, raw.toString(), localPort) ?: false
                            val result = when {
                                !started -> com.mlmvpn.scanner.engines.cfnet.ValidationResult.fail(com.mlmvpn.scanner.engines.cfnet.FailureClass.TUNNEL_FAILURE)
                                cfOutbound != null -> com.mlmvpn.scanner.engines.cfnet.CfDataProbe.validate(runtimeControlSocksPort)
                                serverlessCheckPort != 0 -> {
                                    val level = com.mlmvpn.scanner.data.IranProfileTester.liveCheck(this@MyVpnService, serverlessCheckPort)
                                    com.mlmvpn.scanner.engines.gst.GstLog.i("MyVpnService", "serverless config live check: $level")
                                    if (level == com.mlmvpn.scanner.data.IranProfileTester.Level.FAIL)
                                        com.mlmvpn.scanner.engines.cfnet.ValidationResult.fail(com.mlmvpn.scanner.engines.cfnet.FailureClass.TUNNEL_FAILURE)
                                    else com.mlmvpn.scanner.engines.cfnet.ValidationResult.pass(0)
                                }
                                else -> com.mlmvpn.scanner.engines.cfnet.ValidationResult.pass(0)
                            }
                            if (cfOutbound != null && started && !asWritten) cfnet.report(com.mlmvpn.scanner.engines.cfnet.CfStage.CONNECT, selected ?: cfnet.asIs(), cfnet.profile(), result)
                            if (result.ok) { working = true; break }
                            currentEngine?.stop()
                            currentEngine = null
                            if (!started) break
                        }
                        if (!working) {
                            com.mlmvpn.scanner.engines.gst.GstLog.e("MyVpnService", "JSON engine failed to start or carried no data")
                            Log.e("MyVpnService", "Failed to start JSON engine")
                            connectionPhaseFlow.value = Phase.FAILED
                            stopSelf()
                        } else {
                            com.mlmvpn.scanner.engines.gst.GstLog.i("MyVpnService", "JSON engine started — CONNECTED")
                            Log.d("MyVpnService", "JSON Engine Started Successfully!")
                            connectionPhaseFlow.value = Phase.CONNECTED
                        }
                    } else {
                        // An SNI config whose remembered method is ECH: no front, the same server
                        // dialled straight to an edge with its name encrypted (SniEch).
                        val parsed = com.mlmvpn.scanner.utils.VpnConfig.parseUri(nodeUri)?.let { c ->
                            if (com.mlmvpn.scanner.utils.CfUnfilter.isLocalFront(c) &&
                                com.mlmvpn.scanner.engines.rstaspoof.SniEch.enabled(this@MyVpnService)) {
                                val (routeIp, _, _) = com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.storedRoute(this@MyVpnService)
                                com.mlmvpn.scanner.engines.rstaspoof.SniEch.direct(c,
                                    com.mlmvpn.scanner.engines.rstaspoof.SniEch.edgeFor(this@MyVpnService, routeIp))
                                    ?.also { com.mlmvpn.scanner.engines.gst.GstLog.i("MyVpnService", "SNI config via ECH to ${it.address}") } ?: c
                            } else c
                        }
                        if (parsed == null) {
                            Log.e("MyVpnService", "Failed to parse VLESS URI")
                            stopSelf()
                            return@withLock
                        }
                        // The Cloudflare engine (cfnet): the strategy proven for CONNECT on this network
                        // is applied by generateConfig, and the live tunnel's own data check below IS
                        // its fast validation -- no separate probe run before every connect. Only when
                        // that check fails is a bounded rediscovery run, and the connect retried once
                        // with the replacement it proves (self-healing). Optimization off: as written.
                        val cfnet = com.mlmvpn.scanner.engines.cfnet.CfNetManager.get(this@MyVpnService)
                        val cloudflareConfig = com.mlmvpn.scanner.utils.CfUnfilter.isCloudflareFronted(parsed)
                        val config = parsed
                        runtimeControlSocksPort = 0

                        Log.d("MyVpnService", "VLESS config prepared; Cloudflare=$cloudflareConfig")

                        // Resolve the server domain here, before the core starts, and pin the
                        // answer into the config (see DomainPreResolver). Skipped entirely for a
                        // bare-IP address, and an empty result just generates the config the old
                        // way -- so this can never block a connection that would otherwise work.
                        val pinnedHosts = com.mlmvpn.scanner.utils.DomainPreResolver.pinnedHostsFor(config)
                        if (pinnedHosts.isNotEmpty()) {
                            com.mlmvpn.scanner.engines.gst.GstLog.i(
                                "MyVpnService",
                                "pre-resolved ${config.address} -> ${pinnedHosts[config.address]?.joinToString()}"
                            )
                        }

                        // Auto-start RSTA Spoof if this is an SNI config (routes through local RSTA proxy)
                        val isSniConfig = config.address == "127.0.0.1" && config.port == 40443
                        sniSessionFlow.value = isSniConfig
                        if (isSniConfig) {
                            Log.d("MyVpnService", ">>> SNI config detected â€” ensuring RSTA Spoof is running")
                            val rstaOk = if (frontHeld) {
                                com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.isRunningFlow.value
                            } else {
                                com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager
                                    .acquire(this@MyVpnService)
                                    .also { frontHeld = true }
                            }
                            if (!rstaOk) {
                                Log.e("MyVpnService", "RSTA Spoof failed to start â€” cannot connect SNI config")
                                releaseFront()
                                stopSelf()
                                return@withLock
                            }
                            // Give RSTA a moment to fully bind
                            Thread.sleep(300)
                            val portReady = com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.isPortOpen("127.0.0.1", 40443, 1000)
                            Log.d("MyVpnService", "RSTA port 40443 ready: $portReady")
                            if (!portReady) {
                                Log.e("MyVpnService", "RSTA port 40443 is NOT listening â€” aborting connection")
                                releaseFront()
                                stopSelf()
                                return@withLock
                            }
                        }

                        // A connect that dials ECH -- the link's own `ech`, or the ECH strategy proven here --
                        // carries Cloudflare's key itself, fetched on the real line (CfEchKey, an hour's cache):
                        // with only «cloudflare-ech.com+udp://1.1.1.1» the core asks 1.1.1.1 over UDP at dial
                        // time, and a line that drops that loses a method that works.
                        if (cloudflareConfig && !isSniConfig && (config.ech.isNotBlank() ||
                                cfnet.appliedStrategy(com.mlmvpn.scanner.engines.cfnet.CfStage.CONNECT, config)?.data?.shape == com.mlmvpn.scanner.engines.cfnet.TunnelShape.ECH)) {
                            runCatching { com.mlmvpn.scanner.engines.cfnet.CfEchKey.fetch(this@MyVpnService) }
                        }

                        val jsonConfig = com.mlmvpn.scanner.utils.XrayJsonGenerator.generateConfig(
                            config = config,
                            localPort = localPort,
                            backendDns = backendDns,
                            allowLan = allowLan,
                            lanPassword = lanPassword,
                            lanBlocked = lanBlocked,
                            // Proxy mode (PROXY_MODE extra) deliberately skips setupVpn() above,
                            // so fd stays 0 -- there is no real TUN file descriptor for xray-core
                            // to attach a "tun" inbound to. Building the config with
                            // includeTun=true anyway (the previous hardcoded value) handed
                            // xray-core a tun inbound pointing at fd 0, a bogus descriptor it
                            // could still try to read from -- this was silently crashing the
                            // whole engine loop a few seconds after startLoop() had already
                            // reported success, which is exactly the "MLM shows connected but
                            // the SOCKS proxy passes nothing" symptom users hit in proxy mode.
                            includeTun = fd > 0,
                            mtu = mtu,
                            useFragment = false,
                            gameMode = isGameMode,
                            dedicatedDnsUrl = dedicatedDnsUrl,
                            dedicatedDnsDomains = dedicatedDnsDomains,
                            pinnedHostIps = pinnedHosts
                        )
                        Log.d("MyVpnService", "Xray JSON config generated (${jsonConfig.length} chars)")
                        if (jsonConfig.contains("\"tag\":\"${com.mlmvpn.scanner.utils.XrayJsonGenerator.QUIC_REFUSAL_TAG}\"")) {
                            com.mlmvpn.scanner.engines.gst.GstLog.i(
                                "MyVpnService", "QUIC refused at once (Gemini / Google apps fix)"
                            )
                        }
                        if (jsonConfig.contains("\"tag\":\"${com.mlmvpn.scanner.utils.XrayJsonGenerator.GOOGLE_V4_TAG}\"")) {
                            com.mlmvpn.scanner.engines.gst.GstLog.i(
                                "MyVpnService", "Google reached by IPv4 address (Gemini / Google apps fix)"
                            )
                        }
                        
                        var working = false
                        // An SNI config rides the local front, which does the unfiltering itself: it is
                        // data-checked like any Cloudflare config, but never reshaped, and its result is
                        // not this network's evidence about direct Cloudflare strategies.
                        // A clean-IP config that carries the handshake its scan proved (a combine wrote
                        // it in) is dialled as written: data-checked, never reshaped, and its outcome is
                        // not evidence about the engine's strategies.
                        val asWritten = cloudflareConfig && cfnet.dialledAsWritten(config)
                        if (asWritten) com.mlmvpn.scanner.engines.gst.GstLog.i("MyVpnService", "Cloudflare config dialled with its own handshake (from its scan)")
                        val optimize = cloudflareConfig && cfnet.enabled() && !isSniConfig && !asWritten
                        var liveConfig = jsonConfig
                        for (attempt in 0 until if (optimize) 2 else 1) {
                            val p = cfnet.profile()
                            // The strategy this attempt runs: the proven one generateConfig applied, or
                            // the config exactly as written when nothing is proven here yet.
                            val selected = if (optimize) cfnet.runtimeStrategy(com.mlmvpn.scanner.engines.cfnet.CfStage.CONNECT, config) ?: cfnet.asIs() else null
                            val validationPort = java.net.ServerSocket(0).use { it.localPort }
                            val liveJson = org.json.JSONObject(liveConfig)
                            if (cloudflareConfig) liveJson.getJSONArray("inbounds").put(org.json.JSONObject()
                                .put("tag", "cfnet-check").put("listen", "127.0.0.1").put("port", validationPort)
                                .put("protocol", "socks").put("settings", org.json.JSONObject().put("auth", "noauth").put("udp", false)))
                            if (cloudflareConfig) routeCfProbe(liveJson, "proxy")
                            if (cloudflareConfig) runtimeControlSocksPort = validationPort
                            // Smart Iran route: Iranian names and addresses leave by the direct outbound.
                            com.mlmvpn.core.tunnel.SmartIranRoute.injectXray(this@MyVpnService, liveJson)
                            currentEngine = com.mlmvpn.core.warp.VlessXrayInjector(fd, com.mlmvpn.core.warp.XrayHosts.TUNNEL)
                            val started = currentEngine?.start(this@MyVpnService, liveJson.toString(), localPort) == true
                            // Connected means data moved: HTTP and HTTPS inside the tunnel, an exact-size
                            // download and an echoed upload. A core that started and carries nothing FAILS.
                            val result = if (!started) com.mlmvpn.scanner.engines.cfnet.ValidationResult.fail(com.mlmvpn.scanner.engines.cfnet.FailureClass.TUNNEL_FAILURE)
                                else if (cloudflareConfig) com.mlmvpn.scanner.engines.cfnet.CfDataProbe.validate(validationPort)
                                else com.mlmvpn.scanner.engines.cfnet.ValidationResult.pass(0)
                            if (cloudflareConfig && started && !isSniConfig) cfnet.report(com.mlmvpn.scanner.engines.cfnet.CfStage.CONNECT, selected, p, result)
                            if (result.ok) { working = true; break }
                            currentEngine?.stop()
                            currentEngine = null
                            runtimeControlSocksPort = 0
                            if (!started || attempt > 0 || !optimize) break
                            // Self-healing: the proven strategy (or the config as written) carried nothing.
                            // Its failure is recorded; a bounded rediscovery proves a replacement, and the
                            // connect is retried with it. Nothing proven: FAILED, not a fake CONNECTED.
                            com.mlmvpn.scanner.engines.gst.GstLog.i("MyVpnService", "Cloudflare data check failed (${result.failure}); finding another way")
                            val healed = cfnet.diagnose(com.mlmvpn.scanner.engines.cfnet.CfStage.CONNECT, parsed,
                                budget = com.mlmvpn.scanner.engines.cfnet.StrategyEngine.StageBudget(parallel = 1, deadlineMs = 90_000))
                            if (!healed.ok) break
                            liveConfig = com.mlmvpn.scanner.utils.XrayJsonGenerator.generateConfig(config, localPort,
                                backendDns = backendDns, allowLan = allowLan, lanPassword = lanPassword,
                                lanBlocked = lanBlocked, includeTun = fd > 0, mtu = mtu,
                                useFragment = false, gameMode = isGameMode, dedicatedDnsUrl = dedicatedDnsUrl,
                                dedicatedDnsDomains = dedicatedDnsDomains, pinnedHostIps = pinnedHosts)
                        }
                        if (working) Log.d("MyVpnService", "VLESS Engine Started Successfully!" + if (isSniConfig) " [via RSTA SNI Spoof]" else "")
                        else Log.e("MyVpnService", "VLESS engine failed to start or carried no data")
                        connectionPhaseFlow.value = if (working) Phase.CONNECTED else Phase.FAILED
                        if (!working) stopSelf()
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e("MyVpnService", "Failed to start Engine", e)
                    connectionPhaseFlow.value = Phase.FAILED
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    /**
     * A raw Xray config that routes by APP (a rule with `process`, e.g. GitHub Tunnel's per-app
     * exit country). Such a config needs two things from this service, both keyed on this one
     * test so they cannot disagree: the tun keeps each connection's real destination address
     * (sniffing `routeOnly`) for the owner lookup, and IPv6 stays off (see [setupVpn]'s `noIpv6`).
     */
    /**
     * FLUX in IPv4 mode: the TUN gets no IPv6 at all, so Android blocks the family for every app
     * instead of letting it leave outside the tunnel.
     */
    private fun routeCfProbe(json: org.json.JSONObject, proxyTag: String) {
        val routing = json.optJSONObject("routing") ?: org.json.JSONObject().also { json.put("routing", it) }
        val old = routing.optJSONArray("rules") ?: org.json.JSONArray()
        val rules = org.json.JSONArray().put(org.json.JSONObject().put("type", "field")
            .put("inboundTag", org.json.JSONArray().put("cfnet-check")).put("outboundTag", proxyTag))
        for (i in 0 until old.length()) rules.put(old.get(i))
        routing.put("rules", rules)
    }

    private fun fluxIpv4Only(config: String): Boolean = try {
        val remarks = org.json.JSONObject(config).optString("remarks")
        // FENIX too: its proxy carries IPv4 only, and without a v6 address Android blocks the family.
        remarks == com.mlmvpn.scanner.engines.flux.core.compile.FluxConfigCompiler.REMARKS_V4 ||
            com.mlmvpn.scanner.engines.fenix.FenixEngine.isFenixConfig(remarks) ||
            // Amnezia through Cloudflare carries IPv4 only (its DNS asks for A records alone).
            com.mlmvpn.scanner.engines.amnezia.AmzCarrier.isCarrierConfig(remarks)
    } catch (_: Exception) { false }

    private fun routesByApp(config: String): Boolean = try {
        val rules = org.json.JSONObject(config).optJSONObject("routing")?.optJSONArray("rules")
        rules != null && (0 until rules.length()).any { (rules.optJSONObject(it)?.optJSONArray("process")?.length() ?: 0) > 0 }
    } catch (_: Exception) { false }

    /**
     * `noIpv6`: add no IPv6 address or route, which makes Android BLOCK the family for every app
     * (not leak it): a v6 connect fails at once, apps fall back to IPv4, and the system resolver
     * stops returning AAAA on a network with no v6. Needed with routeOnly: the tun then forwards
     * the address an app dialled instead of the sniffed name, and the far end (a GitHub runner)
     * has no IPv6 — every v6 site failed with the connection closed.
     */
    private fun setupVpn(backendDns: String = "1.1.1.1", mtu: Int = 1420, isRawJsonConfig: Boolean = false, noIpv6: Boolean = false) {
        if (vpnInterface != null) return

        try {
            val gamePrefs = applicationContext.getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE)
            val isGameMode = gamePrefs.getBoolean("game_mode_active", false)
            val routeProfile = if (isGameMode) {
                gamePrefs.getString("game_route_profile", GAME_ROUTE_FULL) ?: GAME_ROUTE_FULL
            } else GAME_ROUTE_FULL
            val split = routeProfile == GAME_ROUTE_SPLIT
            val dnsOnly = routeProfile == GAME_ROUTE_DNS_ONLY || split

            val builder = Builder()
                .setSession("Cloudflare VPN")
                .addAddress("10.0.0.2", 32)
            if (split) {
                // SPLIT = DNS_ONLY plus a handful of /32s: the sign-in hosts the booster proved
                // are filtered on their TLS name and open when the ClientHello is sent in pieces.
                // Only connections to those addresses enter the tunnel (to the fragmenting
                // outbound); the match and everything else still leave by the physical network.
                (gamePrefs.getString("game_split_routes", "") ?: "").split(',')
                    .map { it.trim() }
                    .filter { it.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$""")) }
                    .distinct().take(MAX_SPLIT_ROUTES)
                    .forEach { ip ->
                        try { builder.addRoute(ip, 32) } catch (e: Exception) { Log.w("MyVpnService", "split route $ip refused: ${e.message}") }
                    }
            }
            if (dnsOnly) {
                // Game booster, DNS steering: ONLY the resolver enters the tunnel.
                //
                // Routing 0.0.0.0/0 here -- what every DNS boost used to do -- sent all of the
                // game's UDP through Xray's userspace TUN and freedom outbound just to rewrite
                // DNS, an extra hop on exactly the traffic whose ping is the point. With one /32
                // route the game's DNS queries come to us and everything else falls through to
                // the physical network (Android's VPN fall-through rule for unrouted
                // destinations), on the kernel path, untouched.
                //
                // IPv6 is left unconfigured on purpose: with no v6 address, route or resolver the
                // VPN blocks the family for the game, which then uses IPv4 -- the family our
                // resolver answers for and the booster measured.
                val dnsIp = gamePrefs.getString("game_dns_ip", DEFAULT_GAME_DNS_IP) ?: DEFAULT_GAME_DNS_IP
                builder.addRoute(dnsIp, 32)
                builder.addDnsServer(dnsIp)
                Log.i("MyVpnService", "game routing: DNS only ($dnsIp) -- gameplay stays on the physical network")
            } else {
                // The whole IPv4 space -- minus Iran's ranges when the Smart Iran route is on.
                // The resolvers handed to apps below always stay inside the tunnel.
                com.mlmvpn.core.tunnel.SmartIranRoute.routeAllV4(applicationContext, builder, listOf(backendDns, "8.8.8.8"))
                // IPv6 as a whole, and only above 1280.
                //
                // 1280 is IPv6's minimum link MTU and the kernel enforces it: putting a v6
                // address on an interface below that fails, and establish() then comes back
                // as "Cannot set address" with no tunnel at all. Below it, neither the
                // address NOR the route is added -- a route without an address of its family
                // is dropped by Android, which leaves v6 unclaimed and leaking over the
                // carrier link, while adding nothing of the family makes VpnService block it.
                if (noIpv6) {
                    Log.i("MyVpnService", "IPv6 blocked: this config routes by app (routeOnly keeps v6 addresses)")
                } else if (mtu >= 1280) {
                    builder.addAddress("fd00:1:2:3:4:5:6:2", 128)
                    com.mlmvpn.core.tunnel.SmartIranRoute.routeAllV6(applicationContext, builder)
                } else {
                    Log.i("MyVpnService", "IPv6 blocked: MTU $mtu is under the IPv6 minimum")
                }
                builder.addDnsServer(backendDns)
                builder.addDnsServer("8.8.8.8")
            }
            builder.setMtu(mtu)
            // Every VPN counts as metered by default from API 29, and games read that: some warn
            // about or throttle downloads on a "metered" connection. A game session is not one.
            if (isGameMode && android.os.Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

            // Routing Logic
            if (isGameMode) {
                val gamePackage = gamePrefs.getString("game_package", null)
                if (dnsOnly) {
                    // This app too, so the booster can check from the inside that the game's DNS
                    // really is ours and that everything else really does fall through. Safe only
                    // because a single /32 is routed: the engine's own sockets never match it.
                    try { builder.addAllowedApplication(applicationContext.packageName) } catch (e: Exception) {}
                }
                if (gamePackage != null) {
                    try {
                        builder.addAllowedApplication(gamePackage)
                        // Under AetherPerf too: when a game session is being diagnosed from a
                        // logcat dump, "was anything except the game actually in the tunnel"
                        // is one of the first things to rule out.
                        Log.i(com.mlmvpn.core.aether.AetherEngine.PERF_TAG,
                            "game routing: ONLY $gamePackage is inside the tunnel")
                        Log.d("MyVpnService", "Game Mode: Only routing $gamePackage through VPN")
                    } catch (e: Exception) {
                        Log.e("MyVpnService", "Failed to add game package to VPN routing", e)
                    }
                }
            } else if (connectedNodeId == com.mlmvpn.scanner.engines.sanction.AntiSanctionManager.NODE_ID &&
                com.mlmvpn.scanner.engines.sanction.AntiSanctionManager.routedPackages(applicationContext).isNotEmpty()
            ) {
                // Anti-sanction with apps chosen: only those packages enter the tunnel, and the
                // config sends everything inside it to the worker. Domain rules cannot do this --
                // a native app resolves through its own DoH, talks QUIC, and does its sign-in
                // under Play Services' UID, so nothing written about "the app's domains" ever
                // matches the traffic that actually matters.
                val routed = com.mlmvpn.scanner.engines.sanction.AntiSanctionManager
                    .routedPackages(applicationContext)
                var added = 0
                for (app in routed) {
                    if (app == applicationContext.packageName) continue
                    try { builder.addAllowedApplication(app); added++ } catch (e: Exception) {
                        Log.w("MyVpnService", "anti-sanction: $app is not installed")
                    }
                }
                Log.d("MyVpnService", "Anti-sanction: routing $added app(s) through the worker")
                // addAllowedApplication with nothing valid would tunnel NOTHING; fall back to the
                // whole device so the feature degrades to its domain behaviour instead of dying.
                if (added == 0) {
                    try { builder.addDisallowedApplication(applicationContext.packageName) } catch (e: Exception) {}
                }
            } else {
                val prefs = applicationContext.getSharedPreferences("vpn_routing_prefs", android.content.Context.MODE_PRIVATE)
                val routingMode = prefs.getString("vpn_routing_mode", "ALL") ?: "ALL"
                val routingApps = prefs.getStringSet("vpn_routing_apps", emptySet()) ?: emptySet()

                if (routingMode == "ALLOW") {
                    var allowed = 0
                    // Smart Iran route: an Iranian app is never among the tunnelled ones.
                    for (app in com.mlmvpn.core.tunnel.SmartIranRoute.allowedFilter(applicationContext, routingApps)) {
                        if (app != applicationContext.packageName) {
                            try { builder.addAllowedApplication(app); allowed++ } catch (e: Exception) {}
                        }
                    }
                    // None of the chosen apps is installed: with no allowed app Android tunnels
                    // EVERY app, this one included, and the core's own sockets loop into the tun.
                    if (allowed == 0) {
                        Log.w("MyVpnService", "split tunnel: no selected app is installed; tunnelling all apps")
                        try { builder.addDisallowedApplication(applicationContext.packageName) } catch (e: Exception) {}
                        com.mlmvpn.core.tunnel.SmartIranRoute.excludeApps(applicationContext, builder, com.mlmvpn.core.tunnel.SplitTunnelSettings.Mode.ALL)
                    }
                } else if (routingMode == "BYPASS") {
                    for (app in routingApps) {
                        try { builder.addDisallowedApplication(app) } catch (e: Exception) {}
                    }
                    try { builder.addDisallowedApplication(applicationContext.packageName) } catch (e: Exception) {}
                    // Smart Iran route: Iranian apps are left outside too (an app already listed is harmless).
                    com.mlmvpn.core.tunnel.SmartIranRoute.excludeApps(applicationContext, builder, com.mlmvpn.core.tunnel.SplitTunnelSettings.Mode.EXCLUDE, skip = routingApps)
                } else {
                    try { builder.addDisallowedApplication(applicationContext.packageName) } catch (e: Exception) {}
                    com.mlmvpn.core.tunnel.SmartIranRoute.excludeApps(applicationContext, builder, com.mlmvpn.core.tunnel.SplitTunnelSettings.Mode.ALL)
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
                            com.mlmvpn.scanner.utils.SafeStart.service(this@MyVpnService, stopIntent)
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
        com.mlmvpn.scanner.utils.SafeStart.detach(this)
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
        runtimeSocksPort = 0
        runtimeControlSocksPort = 0
        connectedNodeId = null
        isRunningFlow.value = false
        connectedNodeIdFlow.value = null
        sniSessionFlow.value = false
        // The front is a process, and this is the only place that knows this service is done with
        // it. Without this the door stayed open after every SNI disconnect.
        releaseFront()
        if (connectionPhaseFlow.value != Phase.FAILED) connectionPhaseFlow.value = Phase.IDLE
        try {
            getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("game_mode_active", false).apply()
        } catch (_: Exception) {}
        super.onDestroy()
        serviceScope.launch {
            xrayMutex.withLock {
                // The engine first, then the interface (see the restart path in onStartCommand):
                // an interface closed under a running engine freed a descriptor the engine kept using.
                try {
                    currentEngine?.stop()
                } catch (e: Exception) {}
                currentEngine = null

                try {
                    vpnInterface?.close()
                } catch (e: Exception) {}
                vpnInterface = null
            }
        }
        
        try {
            screenReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {}
        screenReceiver = null

        disconnectJob?.cancel()
        autoSwitchJob?.cancel()
        trafficMonitorJob?.cancel()

        releaseWakeLock()
        wakeLock = null
    }

    // Battery: when the CPU is kept awake.
    //
    // The tunnel first held an unbounded PARTIAL_WAKE_LOCK for the entire session. Then it held
    // one "while traffic flows", dropped after five minutes without a single byte -- which a
    // connected phone never has: every app's push connection, MAE's WARP keepalive and the
    // system's own checks move a few hundred bytes every few seconds, so the lock was renewed
    // forever and the CPU still never slept. With the CPU awake, every timer in the process --
    // the cores' keepalives, every polling loop -- ran on schedule all night, and so did the radio
    // they kept switching on. That was the battery report.
    //
    // A wake lock does nothing while the screen is on (the display already keeps the CPU up), and
    // an incoming packet wakes the device through the network driver whether or not one is held.
    // What it can still be for is a transfer in progress with the screen off, by an app that holds
    // no lock of its own -- so that, and nothing else, is what keeps it: a sustained rate above
    // [BUSY_BYTES_PER_SEC], held [BUSY_LINGER_MS] past the last busy sample. Keepalives and push
    // traffic never come near it. Connecting holds it briefly too ([CONNECT_LOCK_MS]), so a connect
    // started as the screen goes off finishes. Every acquire has a timeout, so a service that dies
    // abnormally cannot strand the lock.
    private val CONNECT_LOCK_MS = 30_000L
    private val BUSY_LOCK_MS = 60_000L
    private val BUSY_LINGER_MS = 30_000L
    private val BUSY_BYTES_PER_SEC = 16 * 1024L
    /** The live meter is on screen: one sample every two seconds, as it always was. */
    private val SAMPLE_SCREEN_ON_MS = 2_000L
    /** The live widget's chart is drawing these, one point a second ([SessionTraffic.live]). */
    private val SAMPLE_LIVE_MS = 1_000L
    /** Nobody is looking: a sample every ten seconds is plenty for usage and for the lock. */
    private val SAMPLE_SCREEN_OFF_MS = 10_000L
    /** The session counters reach disk this often; the screen reads them from memory. */
    private val SESSION_PERSIST_MS = 60_000L

    /** Elapsed-realtime deadline of the current busy stretch; see [updateWakeLock]. */
    private var busyUntil = 0L

    private fun acquireWakeLock(timeoutMs: Long) {
        try {
            wakeLock?.acquire(timeoutMs)
        } catch (e: Exception) {
            Log.w("MyVpnService", "Could not acquire WakeLock", e)
        }
    }

    /**
     * One decision per traffic sample: hold the CPU only while the screen is off and a real
     * transfer is running (see the note above [CONNECT_LOCK_MS]).
     */
    private fun updateWakeLock(screenOn: Boolean, bytes: Long, elapsedMs: Long, now: Long) {
        if (bytes * 1000 / elapsedMs.coerceAtLeast(1) >= BUSY_BYTES_PER_SEC) busyUntil = now + BUSY_LINGER_MS
        if (!screenOn && now < busyUntil) {
            acquireWakeLock(BUSY_LOCK_MS)
        } else if (wakeLock?.isHeld == true) {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.w("MyVpnService", "Could not release WakeLock", e)
        }
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
            com.mlmvpn.scanner.data.SessionTraffic.reset()

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
            var lastRx = stat { android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid()) }.coerceAtLeast(0L)
            var lastTx = stat { android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid()) }.coerceAtLeast(0L)
            
            // Fallback for devices where UDP getUidRxBytes is broken (e.g., Xiaomi/MTK)
            var lastTotalRx = stat { android.net.TrafficStats.getTotalRxBytes() }.coerceAtLeast(0L)
            var lastTotalTx = stat { android.net.TrafficStats.getTotalTxBytes() }.coerceAtLeast(0L)

            var sessionRx = 0L
            var sessionTx = 0L
            var lastSampleAt = android.os.SystemClock.elapsedRealtime()
            var lastPersistAt = lastSampleAt
            val screen = com.mlmvpn.scanner.utils.ScreenState

            try {
                while (true) {
                    // Every two seconds while the meter can be seen, every second while the live
                    // chart is drawing them, every ten while nobody looks -- and at once when the
                    // screen comes back or the chart appears, so neither starts on a stale count.
                    val screenWasOn = screen.isOn(this@MyVpnService)
                    val liveWas = com.mlmvpn.scanner.data.SessionTraffic.live.value
                    val period = when {
                        !screenWasOn -> SAMPLE_SCREEN_OFF_MS
                        liveWas -> SAMPLE_LIVE_MS
                        else -> SAMPLE_SCREEN_ON_MS
                    }
                    kotlinx.coroutines.withTimeoutOrNull(period) {
                        kotlinx.coroutines.flow.combine(screen.on, com.mlmvpn.scanner.data.SessionTraffic.live) { on, live ->
                            on != screenWasOn || (live && !liveWas)
                        }.first { it }
                    }

                    val currentRx = stat { android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid()) }.coerceAtLeast(0L)
                    val currentTx = stat { android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid()) }.coerceAtLeast(0L)
                    val currentTotalRx = stat { android.net.TrafficStats.getTotalRxBytes() }.coerceAtLeast(0L)
                    val currentTotalTx = stat { android.net.TrafficStats.getTotalTxBytes() }.coerceAtLeast(0L)

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

                    val now = android.os.SystemClock.elapsedRealtime()
                    val elapsed = now - lastSampleAt
                    lastSampleAt = now

                    // Only touch anything when something actually moved. The live meter reads the
                    // counters from memory; the preference file behind it is written once a minute
                    // (it used to be on every sample -- a whole file to flash every two seconds,
                    // all day), and the daily usage batches its own writes the same way.
                    // The in-memory count is the exception: it is renewed on every sample, still
                    // or not, because the live chart takes its rate over the time between counts.
                    sessionRx += rxDelta
                    sessionTx += txDelta
                    com.mlmvpn.scanner.data.SessionTraffic.publish(sessionRx, sessionTx)
                    if (rxDelta > 0L || txDelta > 0L) {
                        if (now - lastPersistAt >= SESSION_PERSIST_MS) {
                            lastPersistAt = now
                            sessionPrefs.edit()
                                .putLong("session_rx", sessionRx)
                                .putLong("session_tx", sessionTx)
                                .apply()
                        }

                        val defaultPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@MyVpnService)
                        if (defaultPrefs.getBoolean("enable_usage_tracking", true)) {
                            trafficManager.addTraffic(rxDelta, txDelta)
                        }
                    }
                    updateWakeLock(screen.on.value, rxDelta + txDelta, elapsed, now)

                    lastRx = currentRx
                    lastTx = currentTx
                    lastTotalRx = currentTotalRx
                    lastTotalTx = currentTotalTx
                }
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    val currentRx = stat { android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid()) }.coerceAtLeast(0L)
                    val currentTx = stat { android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid()) }.coerceAtLeast(0L)
                    val currentTotalRx = stat { android.net.TrafficStats.getTotalRxBytes() }.coerceAtLeast(0L)
                    val currentTotalTx = stat { android.net.TrafficStats.getTotalTxBytes() }.coerceAtLeast(0L)
                    
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
                    // Not published to the live meter: this runs after the session, sometimes after
                    // the next one has already zeroed it, and the screen clears it on disconnect.
                    sessionPrefs.edit()
                        .putLong("session_rx", sessionRx)
                        .putLong("session_tx", sessionTx)
                        .apply()
                    val defaultPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@MyVpnService)
                    if (defaultPrefs.getBoolean("enable_usage_tracking", true)) {
                        trafficManager.addTraffic(rxDelta, txDelta)
                    }
                    // The session is over: whatever the daily usage was still holding goes to disk.
                    trafficManager.flush()
                }
            }
        }
    }

    // startStressTestMonitor() was removed: it was a leftover diagnostic loop that woke every
    // 5 seconds for the entire session purely to Log.d() a RAM figure nobody reads in a release
    // build -- ~17k wakeups a day doing no work, on top of a wakelock that guaranteed the CPU
    // was awake to service them.

    private fun startAutoSwitchMonitor() {
        autoSwitchJob?.cancel()
        autoSwitchJob = serviceScope.launch {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@MyVpnService)
            
            // Never during a game session: auto-switch swaps the running config for a random node,
            // which mid-match drops the game's connection and replaces a per-game route with a
            // whole-device one. Asked again after every wait and right before switching -- a game
            // session can begin at any point of a fifteen-minute interval or a long test round.
            fun isGameSession() = getSharedPreferences("game_booster_prefs", android.content.Context.MODE_PRIVATE)
                .getBoolean("game_mode_active", false)

            // Never while MAE is the tunnel either: it routes each app by itself, and swapping it
            // for one node (after 15 minutes, by default) quietly took every app's route away.
            fun isMaeSession() = connectedNodeId == com.mlmvpn.scanner.engines.mae.MaeEngine.NODE_ID

            while (true) {
                val isAutoSwitchEnabled = prefs.getBoolean("auto_switch_enabled", false) && !isGameSession() && !isMaeSession()
                if (!isAutoSwitchEnabled) {
                    // Auto-switch is off by default, so for most users this branch is the only
                    // one that ever runs. Re-checking a single boolean every 10s meant ~8.6k
                    // needless wakeups a day; a minute's latency on noticing the user turned
                    // the feature on costs nothing (the interval itself is 15+ minutes).
                    kotlinx.coroutines.delay(60000)
                    continue
                }
                
                val intervalMinutes = prefs.getInt("auto_switch_interval", 15)
                kotlinx.coroutines.delay(intervalMinutes * 60 * 1000L)
                
                // Double check if still enabled after delay
                if (!prefs.getBoolean("auto_switch_enabled", false) || !isRunning || isGameSession() || isMaeSession()) continue
                
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
                
                if (bestNode != null && connectedNodeId != bestNode.id && isRunning && !isGameSession() && !isMaeSession()) {
                    Log.d("AutoSwitch", "Found better node: ${bestNode.name} with score $bestScore. Switching...")
                    
                    val isProxyMode = com.mlmvpn.scanner.utils.NetworkSettings.proxyMode(this@MyVpnService)
                    val localPort = com.mlmvpn.scanner.utils.LocalPort.getString(this@MyVpnService)
                    val startIntent = Intent(this@MyVpnService, MyVpnService::class.java).apply {
                        putExtra("NODE_URI", bestNode.uri)
                        putExtra("NODE_ID", bestNode.id)
                        putExtra("PROXY_MODE", isProxyMode)
                        putExtra("LOCAL_PORT", localPort)
                    }
                    com.mlmvpn.scanner.utils.SafeStart.service(this@MyVpnService, startIntent)
                    break 
                } else {
                    Log.d("AutoSwitch", "Current node is still the best or no better node found.")
                }
            }
        }
    }
}
