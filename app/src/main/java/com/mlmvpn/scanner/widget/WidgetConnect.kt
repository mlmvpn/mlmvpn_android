package com.mlmvpn.scanner.widget

import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import com.mlmvpn.core.tunnel.TunnelEngine
import com.mlmvpn.core.tunnel.TunnelPreferences
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.data.VpnSwitch
import com.mlmvpn.scanner.models.VpnNode
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.tunnel.Transport
import com.mlmvpn.scanner.ui.tunnel.TunnelController
import com.mlmvpn.scanner.ui.tunnel.TunnelStage
import com.mlmvpn.scanner.utils.LocalPort
import com.mlmvpn.scanner.utils.NetworkSettings
import com.mlmvpn.scanner.utils.SafeStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Connects and disconnects a method from outside its screen, for the widgets.
 *
 * Every method is started through exactly the call its own screen makes when its big button is
 * pressed -- the same engine function, the same extras -- so a widget connect is the app's own
 * connect, not a second implementation of it. What a screen would have asked the user first (an
 * account, a server list, a terms page) is answered by [readiness]: a method that is not ready opens
 * on its page instead.
 *
 * The callers have the VPN consent already, and check for a running scan; see WidgetActionActivity.
 * Everything here runs off the main thread except where it says otherwise.
 */
object WidgetConnect {

    sealed class Ready {
        object Yes : Ready()
        /** Something only the method's page can do first: [reason] says what, in the user's language. */
        data class Setup(val reason: String) : Ready()
        /** The method cannot run on this phone at all. */
        data class No(val reason: String) : Ready()
    }

    private val TRANSPORTS = setOf("masque", "wireguard", "warp_on_warp", "warp", "psiphon", "tor", "geph", "lantern", "urnetwork")

    // ---------------------------------------------------------------------------------------------
    // Readiness
    // ---------------------------------------------------------------------------------------------

    suspend fun readiness(context: Context, method: String): Ready = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        runCatching { readinessOf(app, method) }.getOrElse {
            android.util.Log.w("WidgetConnect", "readiness $method: ${it.message}")
            Ready.Setup(tr("برای ادامه، صفحهٔ این روش را باز کنید", "Open this method's page to go on"))
        }
    }

    private suspend fun readinessOf(app: Context, method: String): Ready {
        if (method in TRANSPORTS) {
            val t = Transport.byId(method) ?: return Ready.No(tr("این روش در این نسخه نیست", "Not in this version"))
            if (!t.supportedHere) return Ready.No(tr("این روش روی پردازندهٔ این گوشی اجرا نمی‌شود", "This method does not run on this phone's processor"))
            if (t.needsTunnelCore && !TunnelEngine.isAvailable) return Ready.No(tr("هستهٔ تونل روی این گوشی بارگذاری نشد", "The tunnel core did not load on this phone"))
            t.unavailableReason(app)?.let { return Ready.Setup(it) }
            val coreMissing = when (method) {
                "warp" -> !com.mlmvpn.core.warp.CfWarpEngine.available(app)
                "geph" -> !com.mlmvpn.core.geph.GephEngine.available(app)
                "lantern" -> !com.mlmvpn.core.lantern.LanternEngine.available(app)
                else -> false
            }
            if (coreMissing) return Ready.Setup(tr("هستهٔ این روش هنوز نصب نشده؛ از صفحهٔ خودش نصبش کنید", "This method's core is not installed yet; install it from its page"))
            return Ready.Yes
        }
        return when (method) {
            "mae" -> {
                val mae = com.mlmvpn.scanner.engines.mae.MaeEngine
                mae.init(app)
                when {
                    !mae.hasCloudAccount() -> Ready.Setup(tr("MAE به یک حساب کلادفلر نیاز دارد", "MAE needs a Cloudflare account"))
                    runCatching { !mae.store.current.onboarded }.getOrDefault(false) ->
                        Ready.Setup(tr("اول در صفحهٔ MAE برنامه‌هایتان را انتخاب کنید", "Pick your apps on MAE's page first"))
                    else -> Ready.Yes
                }
            }
            "github" -> {
                val store = com.mlmvpn.scanner.engines.github.GtStore(app)
                when {
                    store.accounts().isEmpty() -> Ready.Setup(tr("اول حساب گیت‌هاب را در صفحهٔ گیت‌هاب تانل وصل کنید", "Connect a GitHub account on GitHub Tunnel's page first"))
                    store.activeSession() == null -> Ready.Setup(tr("نشست فعالی نیست؛ از صفحهٔ گیت‌هاب تانل یکی بسازید", "No session is running; start one on GitHub Tunnel's page"))
                    else -> Ready.Yes
                }
            }
            "emergency_2" ->
                if (com.mlmvpn.scanner.engines.gst.GstConfigManager.getRelays(app).any { it.deploymentId.isNotBlank() }) Ready.Yes
                else Ready.Setup(tr("اول از صفحهٔ گوگل اسکریپت یک رله بسازید", "Build a relay on Google Script's page first"))
            "emergency_3" -> {
                NodeManager(app).awaitLoaded(6_000)
                if (com.mlmvpn.scanner.engines.rstaspoof.SniSession.configs(app).isNotEmpty()) Ready.Yes
                else Ready.Setup(tr("هنوز کانفیگ SNI ندارید؛ از صفحهٔ ضد فیلتر SNI بسازید", "No SNI config yet; build one on its page"))
            }
            "antisanction" -> {
                val m = com.mlmvpn.scanner.engines.sanction.AntiSanctionManager
                if (m.exitKind(app) != com.mlmvpn.scanner.engines.sanction.AntiSanctionManager.Exit.WORKER) {
                    // The gateway exit is chosen per server on its screen.
                    Ready.Setup(tr("خروج این روش روی «گیت‌وی» است؛ از صفحهٔ خودش وصل شوید", "This method exits through the gateway; connect from its page"))
                } else {
                    withTimeoutOrNull(6_000) { com.mlmvpn.scanner.data.CloudManager(app).awaitLoaded() }
                    when {
                        m.accountsWithWorker(app).isEmpty() -> Ready.Setup(tr("اول ورکر را در صفحهٔ «DNS تحریم» بسازید", "Deploy its Worker on the page first"))
                        m.routingDomains(app).isEmpty() && m.routedPackages(app).isEmpty() ->
                            Ready.Setup(tr("هنوز سایت یا برنامه‌ای برای این روش انتخاب نشده", "No site or app is chosen for it yet"))
                        else -> Ready.Yes
                    }
                }
            }
            "iran" -> if (lastIranNode(app) != null) Ready.Yes
                else Ready.Setup(tr("یک بار از صفحهٔ «کانفیگ ایران» یکی را وصل کنید؛ ویجت همان را وصل می‌کند", "Connect one from «Iran configs» once; the widget then uses it"))
            "fronting" -> {
                NodeManager(app).awaitLoaded(6_000)
                if (com.mlmvpn.scanner.mitm.MitmProfile.isReady(app)) Ready.Yes
                else Ready.Setup(tr("دامین فرانتینگ هنوز راه‌اندازی نشده", "Domain fronting is not set up yet"))
            }
            "fenix" -> {
                com.mlmvpn.scanner.engines.fenix.FenixEngine.init(app)
                if (com.mlmvpn.scanner.engines.fenix.FenixEngine.hasAccounts()) Ready.Yes
                else Ready.Setup(tr("اول حساب فایرفاکس را در صفحهٔ FENIX وارد کنید", "Sign in with a Firefox account on FENIX's page first"))
            }
            "amnezia" -> {
                com.mlmvpn.scanner.engines.amnezia.AmzEngine.init(app)
                if (com.mlmvpn.scanner.engines.amnezia.AmzEngine.store.current.servers.isNotEmpty()) Ready.Yes
                else Ready.Setup(tr("فهرست سرورها هنوز گرفته نشده", "The server list is not here yet"))
            }
            "proton" -> {
                com.mlmvpn.scanner.engines.amnezia.AmzEngine.init(app)
                com.mlmvpn.scanner.engines.proton.ProtonEngine.init(app)
                if (protonServers(app).isNotEmpty()) Ready.Yes
                else Ready.Setup(tr("اول در صفحهٔ Proton VPN وارد شوید", "Sign in on Proton VPN's page first"))
            }
            "windscribe" ->
                if (com.mlmvpn.scanner.engines.windscribe.WindscribeEngine.signedIn(app)) Ready.Yes
                else Ready.Setup(tr("اول در صفحهٔ Windscribe وارد شوید", "Sign in on Windscribe's page first"))
            "openvpn" -> {
                val d = com.mlmvpn.scanner.openvpn.OpenVpnRepository.get(app).data.value
                when {
                    d.storageError -> Ready.Setup(tr("حافظهٔ امن OpenVPN باز نشد", "OpenVPN's secure storage did not open"))
                    openVpnPlan(app, null) == null -> Ready.Setup(tr("اول در صفحهٔ OpenVPN یک سرور و در صورت نیاز حساب انتخاب کنید", "Pick a server, and an account if it needs one, on OpenVPN's page first"))
                    else -> Ready.Yes
                }
            }
            "vpngate" -> Ready.Yes
            "flux", "outline", "quick" -> Ready.Yes
            "nodes" -> {
                NodeManager(app).awaitLoaded(6_000)
                if (ownNodes(app).isNotEmpty()) Ready.Yes
                else Ready.Setup(tr("هنوز کانفیگی اضافه نکرده‌اید", "You have not added a config yet"))
            }
            "npv" -> {
                val npv = com.mlmvpn.scanner.engines.npv.NpvEngine
                npv.ensureSeed(app)
                if (npv.best(com.mlmvpn.scanner.engines.npv.NpvLibrary.entries(app)) != null) Ready.Yes
                else Ready.Setup(tr("هنوز فایل NPV اجراشدنی ندارید", "No runnable NPV file yet"))
            }
            else -> Ready.Setup(tr("برای ادامه، صفحهٔ این روش را باز کنید", "Open this method's page to go on"))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Connect
    // ---------------------------------------------------------------------------------------------

    /**
     * Starts [method], with [country] applied first when the design asks for one and the method
     * can honour it. True once the engine has the connect in hand; whether it then comes up is the
     * engine's own business, watched by the caller (WidgetConnectService).
     */
    suspend fun connect(app: Context, method: String, country: String?): Boolean = withContext(Dispatchers.IO) {
        val cc = country?.trim()?.uppercase()?.takeIf { it.length == 2 }
        if (method in TRANSPORTS) {
            val t = Transport.byId(method) ?: return@withContext false
            if (cc != null) applyTransportCountry(app, t, cc)
            withContext(Dispatchers.Main) { TunnelController.connect(app, t) }
            return@withContext true
        }
        when (method) {
            "mae" -> {
                com.mlmvpn.scanner.engines.mae.MaeEngine.startTunnelAsync(app)
                true
            }
            "github" -> {
                val gt = com.mlmvpn.scanner.engines.github.GtEngine
                gt.attach(app)
                if (cc != null) {
                    val store = com.mlmvpn.scanner.engines.github.GtStore(app)
                    val now = store.exitPrefs
                    if (now.country != cc) gt.setExitPrefs(now.copy(country = cc))
                }
                withContext(Dispatchers.Main) { gt.connect() }
                true
            }
            "emergency_2" -> {
                startXray(app, "{\"type\":\"gst\"}", "GST_EMERGENCY", NetworkSettings.Method.GST.id)
                true
            }
            "emergency_3" -> {
                NodeManager(app).awaitLoaded(6_000)
                val sni = com.mlmvpn.scanner.engines.rstaspoof.SniSession
                val configs = sni.configs(app)
                val chosenId = PreferenceManager.getDefaultSharedPreferences(app).getString("sni_selected_config_id", null)
                val node = configs.firstOrNull { it.id == chosenId } ?: configs.firstOrNull() ?: return@withContext false
                val (ip, port, fake) = com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.storedRoute(app)
                sni.connect(app, node, ip, port, fake)
                true
            }
            "antisanction" -> {
                val cfg = com.mlmvpn.scanner.engines.sanction.AntiSanctionManager.buildConfig(app, LocalPort.get(app))
                    ?: return@withContext false
                startXray(app, cfg, com.mlmvpn.scanner.engines.sanction.AntiSanctionManager.NODE_ID, NetworkSettings.Method.ANTI_SANCTION.id)
                true
            }
            "iran" -> {
                val node = lastIranNode(app) ?: return@withContext false
                startXray(app, node.uri, node.id, null)
                true
            }
            "fronting" -> {
                NodeManager(app).awaitLoaded(6_000)
                val node = nodesOf(app).firstOrNull { it.id == com.mlmvpn.scanner.mitm.MitmProfile.NODE_ID }
                    ?: return@withContext false
                startXray(app, node.uri, node.id, null)
                true
            }
            "flux" -> {
                val flux = com.mlmvpn.scanner.engines.flux.FluxEngine
                flux.init(app)
                if (cc != null) flux.setCountry(cc)
                withContext(Dispatchers.Main) { flux.connectAsync(app) }
                true
            }
            "fenix" -> {
                val fenix = com.mlmvpn.scanner.engines.fenix.FenixEngine
                fenix.init(app)
                if (cc != null && fenix.servers.value.any { it.country.equals(cc, ignoreCase = true) }) fenix.setCountry(app, cc)
                fenix.connectAsync(app)
                true
            }
            "amnezia" -> {
                val amz = com.mlmvpn.scanner.engines.amnezia.AmzEngine
                amz.init(app)
                if (cc == null) {
                    amz.connectTarget(app)
                } else {
                    // Only Amnezia's own servers: Proton's and Windscribe's share the pool.
                    val own = amneziaOwnIds(app)
                    val net = com.mlmvpn.scanner.engines.flux.FluxNet.current(app).key
                    val best = com.mlmvpn.scanner.engines.amnezia.AmzBrain.best(amz.store.current, net, cc, exclude = amz.store.current.servers.map { it.id }.toSet() - own)
                    if (best != null) amz.connectAsync(app, serverId = best.id, only = own)
                    else amz.connectAsync(app, country = cc, only = own)
                }
                true
            }
            "proton" -> {
                val proton = com.mlmvpn.scanner.engines.proton.ProtonEngine
                com.mlmvpn.scanner.engines.amnezia.AmzEngine.init(app)
                proton.init(app)
                val target = cc?.let { bestProtonIn(app, it) }
                if (target != null) proton.connect(app, target) else proton.connect(app)
                true
            }
            "windscribe" -> {
                val ws = com.mlmvpn.scanner.engines.windscribe.WindscribeEngine
                ws.init(app)
                if (cc != null) {
                    // The data loads behind the init; give it a moment before reading the servers.
                    withTimeoutOrNull(4_000) { ws.data.first { it.servers.isNotEmpty() } }
                    val pick = ws.data.value.servers.filter { it.country.equals(cc, ignoreCase = true) }
                        .minByOrNull { it.load ?: 100 }
                    if (pick != null && ws.data.value.selectedServer != pick.id) {
                        ws.selectServer(pick.id)
                        withTimeoutOrNull(4_000) { ws.data.first { it.selectedServer == pick.id } }
                    }
                }
                withContext(Dispatchers.Main) { ws.connect(app) }
                true
            }
            "outline" -> {
                val outline = com.mlmvpn.scanner.engines.outline.OutlineEngine
                outline.init(app)
                if (outline.store.current.servers.isEmpty()) {
                    // A fresh install whose Outline page was never opened: fetch the lists first,
                    // which is what the page does when it opens.
                    outline.refresh()
                    withTimeoutOrNull(60_000) {
                        delay(300)
                        outline.refreshing.first { !it }
                    }
                }
                val id = cc?.let {
                    com.mlmvpn.scanner.engines.outline.OutlineBrain.best(outline.store.current, com.mlmvpn.scanner.engines.flux.FluxNet.current(app).key, it)?.id
                }
                outline.connectAsync(app, id)
                true
            }
            "openvpn" -> {
                val plan = openVpnPlan(app, cc) ?: openVpnPlan(app, null) ?: return@withContext false
                com.mlmvpn.scanner.openvpn.OpenVpnRuntime.connect(app, plan.first, plan.second)
                true
            }
            "vpngate" -> connectVpnGate(app, cc)
            "quick" -> connectQuick(app)
            "nodes" -> connectOwnNodes(app)
            "npv" -> {
                val npv = com.mlmvpn.scanner.engines.npv.NpvEngine
                npv.ensureSeed(app)
                val best = npv.best(com.mlmvpn.scanner.engines.npv.NpvLibrary.entries(app)) ?: return@withContext false
                // A native or remote NPV entry has no config of its own to run.
                val uri = best.uri ?: return@withContext false
                npv.connect(app, uri)
                true
            }
            else -> false
        }
    }

    /**
     * The country of a transport, written where its own screen writes it -- these are the method's
     * settings, so the app's page shows the same choice afterwards.
     *
     * WARP, MASQUE and WireGuard leave the country through a NAT64 gateway on a network with IPv6,
     * or through a measured free proxy on one without. Neither is invented here: NAT64 only for a
     * country it has a gateway for, the proxy only for a country whose pool the user already
     * measured on the method's page. Otherwise nothing is changed and the method leaves where it
     * would have anyway -- the widget then shows that real country.
     */
    private fun applyTransportCountry(app: Context, t: Transport, cc: String) {
        runCatching {
            when (t) {
                Transport.PSIPHON -> if (cc in com.mlmvpn.core.tunnel.PsiphonRegions.options(app)) TunnelPreferences(app).egressRegion = cc
                Transport.TOR -> if (cc in com.mlmvpn.core.tunnel.TorRegions.options()) TunnelPreferences(app).torExitRegion = cc
                Transport.GEPH -> com.mlmvpn.core.geph.GephSettings.setExit(app, com.mlmvpn.core.geph.GephExitChoice(cc, null))
                Transport.MASQUE, Transport.WIREGUARD, Transport.CFWG -> {
                    val nat64 = com.mlmvpn.core.warp.Nat64Exits
                    if (cc in nat64.countries(app) && com.mlmvpn.scanner.data.CfFamily.hasIpv6Route()) {
                        nat64.setSelection(app, cc)
                    } else {
                        useIpv4Exit(app, cc)
                    }
                }
                Transport.GOOL -> useIpv4Exit(app, cc)
                else -> Unit
            }
        }.onFailure { android.util.Log.w("WidgetConnect", "country $cc for ${t.id}: ${it.message}") }
    }

    private fun useIpv4Exit(app: Context, cc: String) {
        val v4 = com.mlmvpn.core.warp.Ipv4Exits
        val s = v4.load(app)
        if (s.ack && s.poolOf(cc).isNotEmpty()) v4.setEnabled(app, on = true, country = cc)
    }

    /** MyVpnService started the way a screen starts it, with the lamp claimed before the core is up. */
    private suspend fun startXray(app: Context, uri: String, nodeId: String, mtu: String?) {
        val intent = Intent(app, MyVpnService::class.java).apply {
            putExtra("NODE_URI", uri)
            putExtra("NODE_ID", nodeId)
            if (mtu != null) putExtra("MTU_PROFILE", mtu)
            putExtra("PROXY_MODE", NetworkSettings.proxyMode(app))
            putExtra("LOCAL_PORT", LocalPort.getString(app))
        }
        withContext(Dispatchers.Main) { SafeStart.service(app, intent) }
    }

    /** The Iran profile last connected (the widget connects that one), when it is still there. */
    private fun lastIranNode(app: Context): VpnNode? {
        NodeManager(app).awaitLoaded(6_000)
        val last = com.mlmvpn.scanner.data.LastEngine.read(app) as? com.mlmvpn.scanner.data.LastEngine.Record.Xray ?: return null
        val id = last.nodeId?.takeIf { it.startsWith("default_mlmvpn_") } ?: return null
        return nodesOf(app).firstOrNull { it.id == id }
    }

    /** A copy of the config list, taken under its own lock (it is filled from disk on another thread). */
    private fun nodesOf(app: Context): List<VpnNode> {
        val nm = NodeManager(app)
        return synchronized(nm.nodes) { nm.nodes.toList() }
    }

    /**
     * The user's own configs: not the app's built-in Iran profiles, not SNI configs (their own tile),
     * not domain fronting. The Quick Settings tile races "the newest N" of the whole list, and the
     * built-in profiles are re-made with a fresh date on every load -- so they were always the
     * newest, and the user's own were left out of the race. Here they are kept apart.
     */
    private fun ownNodes(app: Context): List<VpnNode> =
        nodesOf(app).filter { node ->
            !NodeManager.isProtected(node) &&
                node.id != com.mlmvpn.scanner.mitm.MitmProfile.NODE_ID &&
                !com.mlmvpn.scanner.engines.rstaspoof.SniSession.isSniUri(node.uri)
        }

    /** The V2Ray tile: the user's own configs raced, the fastest connected (the tile's own rule). */
    private suspend fun connectOwnNodes(app: Context): Boolean {
        NodeManager(app).awaitLoaded(6_000)
        val count = PreferenceManager.getDefaultSharedPreferences(app).getInt("quick_tile_count", 20).coerceIn(1, 200)
        val nodes = ownNodes(app).sortedByDescending { it.addedAt }.take(count)
        if (nodes.isEmpty()) return false
        val scored = coroutineScope {
            nodes.map { node ->
                async(Dispatchers.IO) {
                    val cfg = com.mlmvpn.scanner.utils.VpnConfig.parseUri(node.uri)
                    val ms = if (cfg != null && cfg.address.isNotBlank()) {
                        runCatching { com.mlmvpn.scanner.ui.httpDelay(cfg.address, cfg.port) }.getOrDefault(-1)
                    } else -1
                    node to ms
                }
            }.map { it.await() }
        }
        val chosen = scored.filter { it.second in 1..8000 }.minByOrNull { it.second }?.first ?: nodes.first()
        startXray(app, chosen.uri, chosen.id, null)
        withContext(Dispatchers.Main) {
            MyVpnService.isRunning = true
            MyVpnService.connectedNodeId = chosen.id
        }
        return true
    }

    /**
     * «اتصال سریع», as its big button does it: servers other people on this operator just proved,
     * plus a few of the user's saved ones, all raced at once; the first that carries traffic wins.
     */
    private suspend fun connectQuick(app: Context): Boolean {
        val pooled = runCatching { com.mlmvpn.scanner.quick.MlmPoolClient.fetch(app) }.getOrDefault(emptyList())
        val good = com.mlmvpn.scanner.quick.QuickScanner.GOOD_ENOUGH_MS
        val fallback = com.mlmvpn.scanner.quick.QuickSavedStore.all(app).sortedWith(
            compareBy(
                { if (it.delay in 1..good) 0 else 1 },
                { if (it.delay > 0) it.delay else Int.MAX_VALUE },
            )
        ).map { it.id to it.uri }
        val candidates = if (pooled.isNotEmpty()) {
            val known = pooled.mapTo(HashSet()) { it.uri }
            pooled.map { it.id to it.uri } + fallback.filter { it.second !in known }.take(6)
        } else fallback
        if (candidates.isEmpty()) return false
        val winner = AtomicReference<Pair<String, String>?>(null)
        withTimeoutOrNull(45_000) {
            coroutineScope {
                val jobs = candidates.map { (id, uri) ->
                    async(Dispatchers.IO) {
                        if (winner.get() != null) return@async
                        if (com.mlmvpn.scanner.quick.QuickScanner.measure(app, uri) > 0) winner.compareAndSet(null, id to uri)
                    }
                }
                while (jobs.any { it.isActive } && winner.get() == null) delay(120)
                jobs.forEach { it.cancel() }
            }
        }
        val (id, uri) = winner.get() ?: return false
        // Claimed before the tunnel exists, as the screen does, so the lamp is Quick Connect's.
        com.mlmvpn.scanner.quick.QuickSavedStore.markConnected(app, id)
        startXray(app, uri, id, NetworkSettings.Method.QUICK_CONNECT.id)
        return true
    }

    /** «گیت‌وی MLM»: the server chosen on its page, or for a country the best-rated one there. */
    private suspend fun connectVpnGate(app: Context, cc: String?): Boolean {
        val store = com.mlmvpn.scanner.engines.vpngate.VpnGateStore
        val pool = com.mlmvpn.scanner.engines.vpngate.VpnGatePool
        store.load(app)
        pool.load(app)
        val repo = com.mlmvpn.scanner.engines.vpngate.VpnGateRepository(app)
        runCatching { repo.refresh() }
        val hidden = pool.hiddenFlow.value
        val servers = (repo.serversFlow.value + pool.keptServers()).distinctBy { it.hostName }.filter { it.hostName !in hidden }
        if (servers.isEmpty()) return false
        val inCountry = cc?.let { c -> servers.filter { it.countryShort.equals(c, ignoreCase = true) }.maxByOrNull { it.score } }
        val server = inCountry
            ?: servers.firstOrNull { it.hostName == store.selectedHostFlow.value }
            ?: servers.maxByOrNull { it.score }
            ?: return false
        com.mlmvpn.scanner.engines.vpngate.VpnGateController.connect(app, server, "")
        return true
    }

    /** OpenVPN: (profile id, account id) as its dial would pick them, for [cc] when given. */
    private fun openVpnPlan(app: Context, cc: String?): Pair<String, String>? {
        val d = com.mlmvpn.scanner.openvpn.OpenVpnRepository.get(app).data.value
        if (d.storageError) return null
        val prefix = com.mlmvpn.scanner.engines.windscribe.WindscribeEngine.PROFILE_PREFIX
        val profiles = d.profiles.filter { !it.id.startsWith(prefix) }
        val fa = com.mlmvpn.scanner.utils.AppLocaleManager.isFarsi()
        val profile = if (cc != null) {
            profiles.filter { com.mlmvpn.scanner.openvpn.OpenVpnPlaces.of(it, fa).code.equals(cc, ignoreCase = true) }
                .sortedWith(compareBy({ if (it.id == d.selectedProfile) 0 else 1 }, { if (it.favorite) 0 else 1 }))
                .firstOrNull()
        } else {
            profiles.firstOrNull { it.id == d.selectedProfile }
        } ?: return null
        val account = d.accounts.firstOrNull { it.id == d.activeAccount }
        val tunnelBear = com.mlmvpn.scanner.openvpn.ProfileRuntime.isTunnelBear(profile)
        return when {
            profile.selfContained && (account == null || !tunnelBear) -> profile.id to com.mlmvpn.scanner.openvpn.OpenVpnRuntime.SELF
            account != null && account.usable(System.currentTimeMillis()) -> profile.id to account.id
            else -> null
        }
    }

    private fun protonServers(app: Context) = com.mlmvpn.scanner.engines.proton.ProtonEngine.servers(
        com.mlmvpn.scanner.engines.amnezia.AmzEngine.store.current.servers,
        com.mlmvpn.scanner.engines.proton.ProtonEngine.storedIds(app),
    )

    /** Proton's best server in [cc]: one that worked on this network, else the one tested fastest. */
    private fun bestProtonIn(app: Context, cc: String): String? {
        val amz = com.mlmvpn.scanner.engines.amnezia.AmzEngine
        val mine = protonServers(app).map { it.id }.toSet()
        val net = com.mlmvpn.scanner.engines.flux.FluxNet.current(app).key
        val others = amz.store.current.servers.map { it.id }.toSet() - mine
        com.mlmvpn.scanner.engines.amnezia.AmzBrain.best(amz.store.current, net, cc, exclude = others)?.let { return it.id }
        return protonServers(app).firstOrNull { com.mlmvpn.scanner.engines.amnezia.AmzBrain.group(it) == cc }?.id
    }

    /** Amnezia's own servers: the shared pool less Proton's and Windscribe's. */
    private fun amneziaOwnIds(app: Context): Set<String> {
        val all = com.mlmvpn.scanner.engines.amnezia.AmzEngine.store.current.servers.map { it.id }.toSet()
        val proton = com.mlmvpn.scanner.engines.proton.ProtonEngine.storedIds(app)
        val ws = runCatching { com.mlmvpn.scanner.engines.windscribe.WindscribeEngine.wireGuardIds(app).values.toSet() }.getOrDefault(emptySet())
        return all - proton - ws
    }

    // ---------------------------------------------------------------------------------------------
    // Disconnect
    // ---------------------------------------------------------------------------------------------

    /**
     * Stops whatever is up -- or on its way up -- from a widget.
     *
     * [VpnSwitch.stopAll] stops every engine and service, but it reaches the transport stack only
     * when its native core reports itself running, and «گف», «وارپ», «لنترن» and «URnetwork» run on
     * engines of their own that never set that flag: for them the transport stack is told directly.
     * The engines with a watchdog of their own (MAE, GitHub Tunnel, SNI's local front) are told too,
     * so nothing brings the tunnel straight back.
     */
    fun disconnect(context: Context) {
        val app = context.applicationContext
        val conn = WidgetLive.conn.value
        val tunnel = TunnelController.state.value
        if (tunnel.stage == TunnelStage.STARTING || tunnel.stage == TunnelStage.RUNNING) TunnelController.disconnect(app)
        when (conn.method) {
            "mae" -> runCatching { com.mlmvpn.scanner.engines.mae.MaeEngine.stopTunnelAsync(app) }
            "github" -> runCatching { com.mlmvpn.scanner.engines.github.GtEngine.disconnect() }
            "proton" -> runCatching { com.mlmvpn.scanner.engines.proton.ProtonEngine.disconnect(app) }
            "vpngate" -> runCatching { com.mlmvpn.scanner.engines.vpngate.VpnGateStore.markDisconnected(app) }
            "emergency_3" -> Thread({ runCatching { com.mlmvpn.scanner.engines.rstaspoof.SniSession.disconnect(app) } }, "widget-sni-stop").start()
        }
        VpnSwitch.stopAll(app)
        WidgetLive.clearRequested()
    }

    /** Everything is down, the transport stack included (see [disconnect] for why it is asked apart). */
    fun allDown(context: Context): Boolean {
        val t = TunnelController.state.value.stage
        return !VpnSwitch.isUp(context) && !VpnSwitch.engineBusy() && t != TunnelStage.STARTING && t != TunnelStage.RUNNING
    }

    // ---------------------------------------------------------------------------------------------
    // Countries, for the studio
    // ---------------------------------------------------------------------------------------------

    /**
     * The countries a design for [method] can be pinned to, as ISO-2 codes in the method's own order
     * (best first where it has one). Null for a method that has no country to choose.
     */
    suspend fun countries(context: Context, method: String): List<String>? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        runCatching { countriesOf(app, method) }.getOrNull()?.map { it.uppercase() }
            ?.filter { com.mlmvpn.core.tunnel.IpFormatter.isRealCountry(it) }?.distinct()
    }

    private suspend fun countriesOf(app: Context, method: String): List<String>? = when (method) {
        "psiphon" -> com.mlmvpn.core.tunnel.PsiphonRegions.options(app)
        "tor" -> com.mlmvpn.core.tunnel.TorRegions.options()
        "geph" -> {
            val level = com.mlmvpn.core.geph.GephAccount.cachedInfo(app)?.level ?: com.mlmvpn.core.geph.GephAccount.Level.FREE
            com.mlmvpn.core.geph.GephExits.countries(com.mlmvpn.core.geph.GephExits.cached(app), level)
                .filter { it.usable(level) > 0 }.map { it.code }
        }
        "masque", "wireguard", "warp" -> com.mlmvpn.core.warp.Nat64Exits.countries(app) + measuredIpv4(app)
        "warp_on_warp" -> measuredIpv4(app)
        "flux" -> {
            com.mlmvpn.scanner.engines.flux.FluxEngine.init(app)
            com.mlmvpn.scanner.engines.flux.FluxEngine.countries().map { it.code }
        }
        "fenix" -> {
            com.mlmvpn.scanner.engines.fenix.FenixEngine.init(app)
            com.mlmvpn.scanner.engines.fenix.FenixEngine.servers.value.map { it.country }
        }
        "amnezia" -> {
            val amz = com.mlmvpn.scanner.engines.amnezia.AmzEngine
            amz.init(app)
            val own = amneziaOwnIds(app)
            val net = com.mlmvpn.scanner.engines.flux.FluxNet.current(app).key
            val state = amz.store.current.let { it.copy(servers = it.servers.filter { s -> s.id in own }) }
            com.mlmvpn.scanner.engines.amnezia.AmzBrain.countryOrder(state, net).map { it.first }
        }
        "proton" -> {
            com.mlmvpn.scanner.engines.amnezia.AmzEngine.init(app)
            com.mlmvpn.scanner.engines.proton.ProtonEngine.init(app)
            protonServers(app).map { com.mlmvpn.scanner.engines.amnezia.AmzBrain.group(it) }
        }
        "windscribe" -> {
            val ws = com.mlmvpn.scanner.engines.windscribe.WindscribeEngine
            ws.init(app)
            withTimeoutOrNull(4_000) { ws.data.first { it.servers.isNotEmpty() } }
            ws.data.value.servers.map { it.country }
        }
        "outline" -> {
            val outline = com.mlmvpn.scanner.engines.outline.OutlineEngine
            outline.init(app)
            outline.store.current.servers.mapNotNull { it.country }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        }
        "openvpn" -> {
            val fa = com.mlmvpn.scanner.utils.AppLocaleManager.isFarsi()
            com.mlmvpn.scanner.openvpn.OpenVpnRepository.get(app).data.value.profiles
                .filter { !it.id.startsWith(com.mlmvpn.scanner.engines.windscribe.WindscribeEngine.PROFILE_PREFIX) }
                .mapNotNull { com.mlmvpn.scanner.openvpn.OpenVpnPlaces.of(it, fa).code }
        }
        "vpngate" -> {
            val repo = com.mlmvpn.scanner.engines.vpngate.VpnGateRepository(app)
            com.mlmvpn.scanner.engines.vpngate.VpnGatePool.load(app)
            runCatching { repo.refresh() }
            (repo.serversFlow.value + com.mlmvpn.scanner.engines.vpngate.VpnGatePool.keptServers())
                .groupingBy { it.countryShort }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        }
        "github" -> {
            val (vpngate, psiphon) = com.mlmvpn.scanner.engines.github.GtStore(app).exitCatalog()
            (vpngate.filterValues { it > 0 }.keys + psiphon).toList()
                .ifEmpty { listOf("JP", "KR", "US", "DE", "NL", "GB", "FR", "SG", "CA", "SE", "CH", "TH", "VN", "RU", "TW") }
        }
        else -> null
    }

    /** Countries whose free-proxy exit the user has already measured on the method's page. */
    private fun measuredIpv4(app: Context): List<String> {
        val s = com.mlmvpn.core.warp.Ipv4Exits.load(app)
        if (!s.ack) return emptyList()
        return s.pools.filterValues { it.isNotEmpty() }.keys.toList()
    }

    /** What a country choice means for [method], when it has a condition worth saying. */
    fun countryNote(method: String): String? = when (method) {
        "masque", "wireguard", "warp" -> tr(
            "روی اینترنت دارای IPv6 از دروازهٔ NAT64 و روی بقیه از «خروج IPv4» که در صفحهٔ همین روش آماده کرده‌اید خارج می‌شود. اگر هیچ‌کدام برای این کشور آماده نباشد، روش از همان جایی خارج می‌شود که خودش انتخاب کند و ویجت کشور واقعی را نشان می‌دهد.",
            "On an internet with IPv6 it leaves through a NAT64 gateway, elsewhere through the IPv4 exit you prepared on this method's page. If neither is ready for this country, the method leaves wherever it would anyway and the widget shows the real country.",
        )
        "warp_on_warp" -> tr(
            "فقط کشورهایی که «خروج IPv4» آن‌ها را در صفحهٔ وارپ در وارپ آماده کرده‌اید.",
            "Only the countries whose IPv4 exit you prepared on WARP-on-WARP's page.",
        )
        "psiphon", "tor" -> tr(
            "این کشور اول امتحان می‌شود؛ اگر در آن لحظه در دسترس نبود، کشور دیگری.",
            "This country is tried first; if it is not available at that moment, another one.",
        )
        "flux" -> tr("FLUX فقط مسیری را می‌پذیرد که واقعاً از همین کشور خارج شود.", "FLUX only accepts a route that really leaves from this country.")
        "amnezia", "proton", "outline", "vpngate", "windscribe", "openvpn" -> tr(
            "بهترین سرور همین کشور که روی اینترنت شما جواب داده انتخاب می‌شود.",
            "The best server in this country that answered on your internet is used.",
        )
        else -> null
    }

    /** Whether a design for [method] can carry a country at all (the studio hides the row otherwise). */
    fun hasCountries(method: String): Boolean = method in setOf(
        "psiphon", "tor", "geph", "masque", "wireguard", "warp", "warp_on_warp", "flux", "fenix",
        "amnezia", "proton", "windscribe", "outline", "openvpn", "vpngate", "github",
    )
}
