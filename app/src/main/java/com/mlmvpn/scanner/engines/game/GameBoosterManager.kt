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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject

class GameBoosterManager(private val context: Context) {

    companion object {
        // Health monitor (Phase 5.1): consecutive bad samples before we give up on the current
        // connection and try a fallback. "Bad" = timeout (-1) or above this latency.
        private const val PING_DEGRADED_MS = 250L
        private const val PING_FAIL_THRESHOLD = 3

        /** Pref keys for the Game tab's Aether choices. */
        const val PREF_AETHER_PROTOCOL = "aether_protocol"
        const val PREF_AETHER_SCAN = "aether_scan"

        /**
         * Scan mode for the Game tab's Aether boost. TURBO by default: it takes the first
         * endpoint that passes, which on a filtered line is realistically the only one that
         * will pass at all, and it gets there in seconds instead of two minutes. BALANCED is
         * offered for users on a cleaner line, where several endpoints do survive and picking
         * the lowest-RTT one is a real win for ping.
         *
         * IRONCLAD is deliberately NOT offered here: device logs show it failing every
         * candidate on a filtered line, and a boost button that never completes is worse than
         * one that picks an imperfect endpoint.
         */
        fun gameAetherScan(context: Context): com.mlmvpn.core.aether.AetherScan =
            when (
                context.getSharedPreferences("game_booster_prefs", Context.MODE_PRIVATE)
                    .getString(PREF_AETHER_SCAN, null)
            ) {
                "BALANCED" -> com.mlmvpn.core.aether.AetherScan.BALANCED
                else -> com.mlmvpn.core.aether.AetherScan.TURBO
            }

        /**
         * Which Aether protocol the Game tab runs.
         *
         * MASQUE unless the user opted into WireGuard. WireGuard is the lower-overhead data
         * plane of the two — no HTTP framing on the datagram path — so where it works it
         * should measure better for gaming, which is exactly why it is worth being able to
         * pick. It is opt-in rather than default because it is also the mode with the
         * endpoint-selection weakness documented on
         * [com.mlmvpn.core.aether.AetherProtocol], and a boost button should not default to
         * the mode that might silently carry nothing.
         */
        fun gameAetherProtocol(context: Context): com.mlmvpn.core.aether.AetherProtocol =
            when (
                context.getSharedPreferences("game_booster_prefs", Context.MODE_PRIVATE)
                    .getString(PREF_AETHER_PROTOCOL, null)
            ) {
                "WG" -> com.mlmvpn.core.aether.AetherProtocol.WG
                else -> com.mlmvpn.core.aether.AetherProtocol.MASQUE
            }

        // AETHER RTT signal for the AUTO race: a plain TCP probe to Cloudflare's anycast edge.
        // Aether itself has no fixed host -- it discovers a healthy WARP-pool endpoint at connect
        // time -- so there is nothing meaningful to "connect and measure" the way the old UAE
        // WireGuard trial did. This is only a coarse "is Cloudflare reachable at all" signal to
        // let AETHER compete in AUTO without paying for a real (tens-of-seconds) gateway scan.
        private const val CF_PROBE_HOST = "1.1.1.1"
        private const val CF_PROBE_PORT = 443
    }

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
    private var activeTestJob: kotlinx.coroutines.Job? = null
    private var antiDpiRotationJob: kotlinx.coroutines.Job? = null

    // Dedicated DNS (per-user ECS-steering worker) â€” resolved once per boost session in
    // resolveDedicatedDns(), then applied uniformly across DIRECT/TUNNEL/WARP/HYBRID.
    private var dedicatedDnsBase: String? = null                 // e.g. https://xyz.workers.dev
    private var dedicatedDnsRegion: String = DedicatedDnsResolver.DEFAULT_REGION
    private var dedicatedDnsDomains: List<String> = emptyList()  // the active game's testEndpoints
    // Candidates the health monitor has already switched away from this session, so it doesn't
    // bounce back to a connection it just proved was bad.
    private val excludedFromSwitch = mutableSetOf<String>()
    // Re-entrancy/debounce guard for connectWithBestResult. Rapid repeat taps on the results-list
    // play buttons used to stack many MyVpnService starts; interleaving a WireGuard (AmneziaWG Go
    // runtime) connect with an Xray connect that fast crashes the app with SIGABRT (two Go runtimes).
    private var lastConnectAt = 0L

    suspend fun runBoostTest(game: GameInfo, region: String = "ME", mode: BoostMode = BoostMode.AUTO): BoostResult? {
        activeTestJob?.cancel()
        return kotlinx.coroutines.coroutineScope {
            val job = async { runBoostTestInternal(game, region, mode) }
            activeTestJob = job
            job.await()
        }
    }

    private suspend fun runBoostTestInternal(game: GameInfo, region: String, mode: BoostMode): BoostResult? {
        Log.d("GameBoosterManager", ">>> Starting Game Boost Test. Game: ${game.name}, Region: $region, Mode: $mode")
        boosterState.value = BoosterState.TESTING
        allResults.value = emptyList()
        bestResult.value = null
        excludedFromSwitch.clear()
        val results = mutableListOf<BoostResult>()
        val server = game.servers.find { it.region == region } ?: game.servers.first()
        selectedServer.value = server

        // Resolve the user's Dedicated DNS worker + best ECS region ONCE, up front, so every
        // boost mode below (DIRECT/TUNNEL/WARP/HYBRID) shares the same region-steered resolver.
        resolveDedicatedDns(game, server)

        // Dedicated-DNS-only mode: user explicitly wants ONLY their private DNS worker path --
        // no direct DNS race, no tunnel, no WARP. Build the result straight from the worker and
        // return (FAILED if the worker isn't deployed/reachable so the user knows to check it).
        if (mode == BoostMode.DEDICATED_DNS) {
            Log.d("GameBoosterManager", "--- Dedicated DNS only mode ---")
            coroutineContext.ensureActive()
            val dnsResult = testDedicatedDnsOnly(server)
            if (dnsResult != null) {
                bestResult.value = dnsResult
                allResults.value = listOf(dnsResult)
                boosterState.value = BoosterState.IDLE
            } else {
                Log.d("GameBoosterManager", "Dedicated DNS only mode failed -- worker not deployed/enabled or unreachable.")
                boosterState.value = BoosterState.FAILED
            }
            return dnsResult
        }

        // UAE_DNS is retired: the UAE server it resolved through has been shut down, so there
        // is no endpoint left to talk to. Removed from the UI and from the AUTO race; the enum
        // value survives only so persisted selections and exhaustive when-branches still
        // compile, exactly as with DIRECT/TUNNEL/WARP. A stored selection lands here and is
        // reported as unavailable rather than silently testing a dead host.
        if (mode == BoostMode.UAE_DNS) {
            Log.w("GameBoosterManager", "UAE_DNS is retired (server decommissioned) — treating as unavailable")
            boosterState.value = BoosterState.FAILED
            return null
        }

        try {
            // --- Baseline Direct Ping (always, independent of mode) ---
            Log.d("GameBoosterManager", "--- Baseline Direct Ping ---")
            coroutineContext.ensureActive()
            val (baselinePing, baselineJitter) = GamePingTester.averagePing(
                endpoints = server.testEndpoints,
                port = server.port,
                proxyPort = null
            )
            originalPing.value = if (baselinePing > 0) baselinePing else -1L
            Log.d("GameBoosterManager", "Baseline ping: ${originalPing.value}ms")

            // AUTO now races only the three modes the Game tab exposes -- Cloudflare Dedicated DNS,
            // UAE DNS, and Aether -- by REAL (or, for Aether, estimated) ping. DIRECT and TUNNEL
            // are retired (they gave no measurable ping reduction and are gone from the UI), so
            // they no longer compete here; the retired-mode helpers are kept only for the explicit
            // DIRECT/TUNNEL when-branches.

            // --- Candidate 1: Cloudflare Dedicated DNS (region-steered worker) ---
            if (mode == BoostMode.AUTO) {
                Log.d("GameBoosterManager", "--- AUTO: Testing Cloudflare Dedicated DNS ---")
                coroutineContext.ensureActive()
                val cfDns = testDedicatedDnsOnly(server)
                if (cfDns != null) {
                    results.add(cfDns)
                    allResults.value = results.sortedBy { it.pingMs }
                    Log.d("GameBoosterManager", "AUTO Cloudflare DNS: ping=${cfDns.pingMs}ms (${cfDns.details})")
                } else {
                    Log.d("GameBoosterManager", "AUTO Cloudflare DNS: no deployed worker / unreachable -- skipped.")
                }
            }

            // Candidate 2 used to be the UAE Dedicated DNS resolver. That server has been
            // decommissioned, so the candidate is gone from the race entirely — testing a host
            // that no longer answers only added a timeout to every AUTO run.

            // --- Candidate 3: Aether (coarse Cloudflare-reachability RTT, no real gateway scan) ---
            // Aether has no fixed host to connect-and-measure the way the old UAE WireGuard trial
            // did -- it picks a healthy WARP-pool endpoint itself, and doing that for real (tens
            // of seconds) is too slow for the AUTO race. Instead this is a coarse reachability
            // signal so AETHER can still compete; the actual in-tunnel latency is only known once
            // the user connects.
            if (mode == BoostMode.AUTO) {
                Log.d("GameBoosterManager", "--- AUTO: Measuring Aether (Cloudflare reachability RTT) ---")
                coroutineContext.ensureActive()
                val aetherResult = measureAetherReachability(server)
                if (aetherResult != null) {
                    results.add(aetherResult)
                    allResults.value = results.sortedBy { it.pingMs }
                    Log.d("GameBoosterManager", "AUTO Aether: ping=${aetherResult.pingMs}ms (${aetherResult.details})")
                } else {
                    Log.d("GameBoosterManager", "AUTO Aether: Cloudflare unreachable -- skipped.")
                }
            }

            // Retired explicit modes: kept working for any old persisted selection, but not in AUTO.
            if (mode == BoostMode.DIRECT) {
                Log.d("GameBoosterManager", "--- Testing Direct Connection (retired, explicit) ---")
                coroutineContext.ensureActive()
                val directResult = testDirectBoost(server)
                if (directResult != null) {
                    results.add(directResult)
                    allResults.value = results.sortedBy { it.pingMs }
                } else {
                    boosterState.value = BoosterState.FAILED
                    return null
                }
            }

            if (mode == BoostMode.TUNNEL) {
                Log.d("GameBoosterManager", "--- Testing Tunnel Nodes (retired, explicit) ---")
                coroutineContext.ensureActive()
                val tunnelResults = testTunnelTurbo(server)
                results.addAll(tunnelResults)
                allResults.value = results.sortedBy { it.pingMs }
            }

        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.d("GameBoosterManager", "Boost Test Cancelled.")
            boosterState.value = BoosterState.IDLE
            throw e
        } catch (e: Exception) {
            Log.e("GameBoosterManager", "Exception during boost test", e)
        }

        val best = results.filter { it.pingMs > 0 }.minByOrNull { it.pingMs }

        Log.d("GameBoosterManager", "<<< Boost Test Finished. Best Result: ${best?.mode} with ping=${best?.pingMs}ms")
        bestResult.value = best
        boosterState.value = if (best != null) BoosterState.IDLE else BoosterState.FAILED
        return best
    }

    /**
     * Resolves the user's Dedicated DNS worker and picks the ECS region to steer with, once per
     * boost session. Manual mode uses the user's fixed region; Auto mode races all regions (real
     * ping) and caches the winner per game id. Falls back to the last cached winner, else UAE.
     * No-op (clears state) when no Cloudflare account has a deployed DNS worker.
     */
    private suspend fun resolveDedicatedDns(game: GameInfo, server: GameServer) {
        dedicatedDnsBase = null
        dedicatedDnsRegion = DedicatedDnsResolver.DEFAULT_REGION
        dedicatedDnsDomains = emptyList()

        val prefs = context.getSharedPreferences("game_booster_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("dedicated_dns_enabled", true)) {
            Log.d("GameBoosterManager", "Dedicated DNS disabled by user switch -- skipping")
            return
        }

        val account = com.mlmvpn.scanner.data.CloudManager(context).accounts.firstOrNull {
            it.dnsStatus == "deployed" && !it.dnsWorkerUrl.isNullOrEmpty()
        } ?: return

        val base = account.dnsWorkerUrl!!.trimEnd('/')
        val mode = prefs.getString("dedicated_dns_mode", "auto") ?: "auto"

        val region: String = if (mode == "manual") {
            prefs.getString("dedicated_dns_manual_region", DedicatedDnsResolver.DEFAULT_REGION)
                ?: DedicatedDnsResolver.DEFAULT_REGION
        } else {
            val race = DedicatedDnsResolver.raceRegions(base, server.testEndpoints, server.port)
            if (race != null) {
                prefs.edit()
                    .putString(DedicatedDnsResolver.cacheKeyFor(game.id), race.region.code)
                    .putLong("${DedicatedDnsResolver.cacheKeyFor(game.id)}_ping", race.pingMs)
                    .apply()
                race.region.code
            } else {
                prefs.getString(DedicatedDnsResolver.cacheKeyFor(game.id), DedicatedDnsResolver.DEFAULT_REGION)
                    ?: DedicatedDnsResolver.DEFAULT_REGION
            }
        }

        dedicatedDnsBase = base
        dedicatedDnsRegion = region
        dedicatedDnsDomains = server.testEndpoints
        Log.d("GameBoosterManager", "Dedicated DNS active: region=$region base=$base domains=${server.testEndpoints}")
    }

    /** The worker's binary-DoH endpoint for Xray's internal resolver, with region baked in. */
    private fun dedicatedXrayDnsUrl(): String? =
        dedicatedDnsBase?.let { "$it/dns-query?region=$dedicatedDnsRegion" }

    /**
     * Public helper for the Dedicated DNS card's Deploy/Retest button: probes the current best
     * region for [game] without touching the live boost session. In Auto mode it races all regions
     * (real ping) and caches the winner; in Manual mode it pings the user's fixed region. Returns
     * (regionCode, pingMs) or null if no DNS worker is deployed / nothing resolved.
     */
    suspend fun probeDedicatedDnsRegion(game: GameInfo, regionPref: String): Pair<String, Long>? {
        val account = com.mlmvpn.scanner.data.CloudManager(context).accounts.firstOrNull {
            it.dnsStatus == "deployed" && !it.dnsWorkerUrl.isNullOrEmpty()
        } ?: return null
        val base = account.dnsWorkerUrl!!.trimEnd('/')
        val server = game.servers.find { it.region == regionPref } ?: game.servers.firstOrNull() ?: return null

        val prefs = context.getSharedPreferences("game_booster_prefs", Context.MODE_PRIVATE)
        val mode = prefs.getString("dedicated_dns_mode", "auto") ?: "auto"

        return if (mode == "manual") {
            val region = prefs.getString("dedicated_dns_manual_region", DedicatedDnsResolver.DEFAULT_REGION)
                ?: DedicatedDnsResolver.DEFAULT_REGION
            val ips = mutableSetOf<String>()
            for (endpoint in server.testEndpoints) {
                ips.addAll(DedicatedDnsResolver.resolveViaWorker(base, endpoint, region))
            }
            val ping = if (ips.isNotEmpty()) {
                GamePingTester.averagePing(ips.toList(), server.port, proxyPort = null).first
            } else -1L
            prefs.edit()
                .putString(DedicatedDnsResolver.cacheKeyFor(game.id), region)
                .putLong("${DedicatedDnsResolver.cacheKeyFor(game.id)}_ping", ping)
                .apply()
            region to ping
        } else {
            val race = DedicatedDnsResolver.raceRegions(base, server.testEndpoints, server.port) ?: return null
            prefs.edit()
                .putString(DedicatedDnsResolver.cacheKeyFor(game.id), race.region.code)
                .putLong("${DedicatedDnsResolver.cacheKeyFor(game.id)}_ping", race.pingMs)
                .apply()
            race.region.code to race.pingMs
        }
    }

    /**
     * Public helper for the Dedicated DNS card's "Test all regions" button: measures EVERY region
     * for [game] on the user's own connection and returns the full per-region result list (best
     * first) so the UI can show the comparison. Also caches the winning region + ping for [game] so
     * a subsequent boost in Auto mode reuses it. Returns empty if no DNS worker is deployed.
     *
     * Ping here is a real TCP-RTT to the game's actual region-steered server IP, so it tracks the
     * in-game ping closely -- see [DedicatedDnsResolver.testAllRegions].
     */
    suspend fun testAllDnsRegions(game: GameInfo, regionPref: String): List<DedicatedDnsResolver.RegionRaceResult> {
        val account = com.mlmvpn.scanner.data.CloudManager(context).accounts.firstOrNull {
            it.dnsStatus == "deployed" && !it.dnsWorkerUrl.isNullOrEmpty()
        } ?: return emptyList()
        val base = account.dnsWorkerUrl!!.trimEnd('/')
        val server = game.servers.find { it.region == regionPref } ?: game.servers.firstOrNull() ?: return emptyList()

        val results = DedicatedDnsResolver.testAllRegions(base, server.testEndpoints, server.port)
        val best = results.firstOrNull()
        if (best != null) {
            context.getSharedPreferences("game_booster_prefs", Context.MODE_PRIVATE).edit()
                .putString(DedicatedDnsResolver.cacheKeyFor(game.id), best.region.code)
                .putLong("${DedicatedDnsResolver.cacheKeyFor(game.id)}_ping", best.pingMs)
                .apply()
        }
        return results
    }

    /** Embeds the dedicated DNS worker URL + scoped domains into a WARP/rotation config JSON. */
    private fun applyDedicatedDns(json: JSONObject) {
        val url = dedicatedXrayDnsUrl() ?: return
        if (dedicatedDnsDomains.isEmpty()) return
        json.put("dedicatedDnsUrl", url)
        json.put("dedicatedDnsDomains", JSONArray(dedicatedDnsDomains))
    }

    /**
     * Attaches the dedicated DNS worker URL + scoped domains as Intent extras for MyVpnService,
     * which builds the Xray config for the TUNNEL (plain VLESS/Trojan) and HYBRID modes and needs
     * these to inject the DNS server entry. Harmless for DIRECT/WARP (already carried in nodeUri).
     */
    private fun attachDedicatedDnsExtras(intent: Intent) {
        val url = dedicatedXrayDnsUrl() ?: return
        if (dedicatedDnsDomains.isEmpty()) return
        intent.putExtra("DEDICATED_DNS_URL", url)
        intent.putExtra("DEDICATED_DNS_DOMAINS", JSONArray(dedicatedDnsDomains).toString())
    }

    /**
     * Dedicated-DNS-ONLY path: uses solely the user's private DNS worker (ECS region-steered),
     * with no fallback to the Iranian/public DNS race, no tunnel, no WARP. Returns null if the
     * worker isn't available (not deployed, disabled, or unreachable) so the caller can mark the
     * boost FAILED rather than silently falling back to another method.
     */
    private suspend fun testDedicatedDnsOnly(server: GameServer): BoostResult? {
        val base = dedicatedDnsBase ?: run {
            Log.w("GameBoosterManager", "Dedicated DNS only: no worker configured (deploy + enable it first)")
            return null
        }
        // Resolve per-hostname so we can pin each game domain to its own region-steered IPs as
        // static hosts (stable for the whole session) rather than dumping every IP into one pool.
        val hostToIps = linkedMapOf<String, List<String>>()
        for (endpoint in server.testEndpoints) {
            val resolved = DedicatedDnsResolver.resolveViaWorker(base, endpoint, dedicatedDnsRegion)
            if (resolved.isNotEmpty()) hostToIps[endpoint] = resolved
        }
        val ips = hostToIps.values.flatten().toSet()
        if (ips.isEmpty()) {
            Log.w("GameBoosterManager", "Dedicated DNS only: worker resolved no IPs for the game hostnames")
            return null
        }
        val (avgPing, jitter) = GamePingTester.averagePing(ips.toList(), server.port, proxyPort = null)
        val regionName = DedicatedDnsResolver.regionByCode(dedicatedDnsRegion).nameFa
        val cfg = XrayJsonGenerator.generateDnsBoostConfig(
            localPort = 10808,
            dnsServers = emptyList(), // real fallback resolvers (1.1.1.1/8.8.8.8) are added by the generator
            dedicatedDnsUrl = dedicatedXrayDnsUrl(),
            dedicatedDnsDomains = server.testEndpoints,
            staticHosts = hostToIps
        )
        Log.d("GameBoosterManager", "Dedicated DNS only: region=$regionName ping=${avgPing}ms over ${ips.size} IPs")
        return BoostResult(
            mode = BoostMode.DEDICATED_DNS,
            // If none pinged we still return the config (worker resolves fine, the probe just
            // couldn't measure) -- show the DNS-answer latency estimate rather than failing.
            pingMs = if (avgPing > 0) avgPing else 0L,
            jitterMs = if (avgPing > 0) jitter else 0L,
            nodeId = "dedicated_dns_only",
            nodeName = "DNS اختصاصی",
            nodeUri = cfg,
            details = "DNS اختصاصی ($regionName)"
        )
    }

    /**
     * Aether latency signal for the AUTO race. Unlike the old UAE WireGuard trial, Aether has no
     * single fixed host to probe -- it picks a healthy endpoint from Cloudflare's WARP pool itself,
     * dynamically, at connect time (see [com.mlmvpn.core.aether.AetherEngine]). Running a real scan
     * here to measure it would take 10s of seconds (even the "turbo" budget is 45s) and is far too
     * slow for a race that also tests DNS candidates in milliseconds.
     *
     * So this only measures a coarse "is Cloudflare's anycast edge reachable at all" RTT and returns
     * an AETHER result with the real config already attached ([buildAetherConfig]) -- Aether needs
     * no trial/account provisioning up front (it self-enrolls on first run), so unlike the old
     * WireGuard path there is nothing left to assemble later at connect time.
     */
    private suspend fun measureAetherReachability(server: GameServer): BoostResult? {
        coroutineContext.ensureActive()
        val (rtt, jitter) = GamePingTester.averagePing(
            endpoints = listOf(CF_PROBE_HOST),
            port = CF_PROBE_PORT,
            proxyPort = null
        )
        if (rtt <= 0) {
            Log.d("GameBoosterManager", "measureAetherReachability: Cloudflare unreachable -- skipping Aether in AUTO")
            return null
        }
        Log.d("GameBoosterManager", "Aether RTT estimate (Cloudflare edge): ${rtt}ms (jitter ${jitter}ms)")
        return BoostResult(
            mode = BoostMode.AETHER,
            pingMs = rtt,
            jitterMs = jitter,
            nodeId = "game_aether",
            nodeName = "Aether",
            nodeUri = buildAetherConfig(),
            details = "موتور Aether (تخمین RTT تا کلادفلر)"
        )
    }

    /**
     * Measure and publish the un-boosted baseline ping for [server].
     *
     * runBoostTest() does this inline as part of its flow, but the Game tab's Aether button
     * never goes through runBoostTest — it assembles its own BoostResult and connects straight
     * away — so [originalPing] stayed at its -1 sentinel and the comparison card permanently
     * read "نامشخص (مسدود)". Exposed separately so that path can call it explicitly.
     *
     * MUST be called before the tunnel comes up, or it measures the tunnel rather than the
     * line it is supposed to be the baseline for.
     */
    suspend fun measureBaselinePing(server: GameServer) {
        val (ping, _) = GamePingTester.averagePing(
            endpoints = server.testEndpoints,
            port = server.port,
            proxyPort = null,
        )
        originalPing.value = if (ping > 0) ping else -1L
        Log.d("GameBoosterManager", "Baseline ping (pre-boost): ${originalPing.value}ms")
    }

    /** Live Persian description of what the Aether boost is doing, for the button/progress UI. */
    val boostProgress = MutableStateFlow<String?>(null)

    private var aetherWatchJob: kotlinx.coroutines.Job? = null

    /**
     * Follow the Aether engine to a real verdict and publish it.
     *
     * The engine already exposes exactly the right signal — [com.mlmvpn.core.aether.AetherState]
     * carries the connect stage, its Persian label, and a `connected` flag that is only true
     * once the SOCKS listener is up behind a validated data plane. This mirrors it onto
     * [boosterState] / [boostProgress] so the Game tab can show the same stage list the Aether
     * screen does instead of a green button that means nothing.
     *
     * The timeout matches the scan mode's own budget plus headroom, so a legitimately slow
     * «متعادل» sweep is not cut off early.
     */
    private fun awaitAetherBoost() {
        val engine = com.mlmvpn.core.aether.AetherEngine.get(context)
        val budgetMs = when (gameAetherScan(context)) {
            com.mlmvpn.core.aether.AetherScan.BALANCED -> 180_000L
            else -> 90_000L
        }

        aetherWatchJob?.cancel()
        aetherWatchJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            val startedAt = System.currentTimeMillis()
            boostProgress.value = "در حال راه‌اندازی موتور…"

            // The engine has NOT started yet when this runs.
            //
            // connectWithBestResult only asks MyVpnService to start; the service spins the
            // engine up on its own thread ~300ms later. Until then engine.state still holds
            // the PREVIOUS session's terminal value — STOPPED — and the terminal check below
            // matched it instantly, declaring the boost failed 3ms after the tap. The engine
            // then started and connected normally, so the user saw a dead button on the first
            // tap and a "VPN already running" conflict on the second.
            //
            // Terminal states are therefore ignored until this session has been observed
            // running at least once. If that never happens the loop still ends, via the
            // startup deadline below rather than by trusting stale state.
            var engineSeenRunning = false
            val startupDeadlineMs = 20_000L

            while (System.currentTimeMillis() - startedAt < budgetMs) {
                val s = engine.state.value
                if (s.running) engineSeenRunning = true

                if (!engineSeenRunning) {
                    if (System.currentTimeMillis() - startedAt > startupDeadlineMs) {
                        Log.w("GameBoosterManager", "aether engine never started within ${startupDeadlineMs}ms")
                        boostProgress.value = null
                        boosterState.value = BoosterState.FAILED
                        return@launch
                    }
                    boostProgress.value = "در حال راه‌اندازی موتور…"
                    delay(250)
                    continue
                }

                if (s.connected) {
                    Log.i("GameBoosterManager",
                        "aether boost connected after ${System.currentTimeMillis() - startedAt}ms " +
                            "server=${s.server} rtt=${s.rtt}")
                    boostProgress.value = null
                    boosterState.value = BoosterState.BOOSTED
                    return@launch
                }

                // A terminal engine state means there is nothing left to wait for. Reporting
                // it immediately beats letting the user stare at a spinner until the timeout.
                if (!s.running && s.stage != com.mlmvpn.core.aether.AetherStage.IDLE &&
                    s.stage != com.mlmvpn.core.aether.AetherStage.STARTING
                ) {
                    Log.w("GameBoosterManager", "aether boost ended without connecting: ${s.stage} ${s.stageFa}")
                    boostProgress.value = null
                    boosterState.value = BoosterState.FAILED
                    return@launch
                }

                // The engine's own Persian stage label, so the Game tab and the Aether screen
                // never disagree about what is happening.
                boostProgress.value = s.stageFa
                delay(500)
            }

            Log.w("GameBoosterManager", "aether boost timed out after ${budgetMs}ms")
            boostProgress.value = null
            boosterState.value = BoosterState.FAILED
        }
    }

    /**
     * Config for the Game tab's Aether boost. The profile itself (h3, scan mode, light
     * obfuscation, short keepalive) lives in AetherTunEngine.buildGameConfig, which the
     * explicit Aether button also calls — one definition, both call sites.
     */
    private fun buildAetherConfig(): String {
        val protocol = gameAetherProtocol(context)
        val scan = gameAetherScan(context)
        Log.i("GameBoosterManager", "aether game profile: protocol=${protocol.env} scan=${scan.env}")
        return com.mlmvpn.core.aether.AetherTunEngine.buildGameConfig(protocol, scan)
    }


    private suspend fun testDirectBoost(server: GameServer): BoostResult? {
        // Highest priority: the user's own Dedicated DNS worker (ECS region-steered). If it
        // resolves the game hostnames and they're reachable, build a local pass-through VPN that
        // keeps resolving via the worker for the whole session (not a one-shot IP snapshot).
        val base = dedicatedDnsBase
        if (base != null) {
            val hostToIps = linkedMapOf<String, List<String>>()
            for (endpoint in server.testEndpoints) {
                val resolved = DedicatedDnsResolver.resolveViaWorker(base, endpoint, dedicatedDnsRegion)
                if (resolved.isNotEmpty()) hostToIps[endpoint] = resolved
            }
            val ips = hostToIps.values.flatten().toSet()
            if (ips.isNotEmpty()) {
                val (avgPing, jitter) = GamePingTester.averagePing(ips.toList(), server.port, proxyPort = null)
                if (avgPing > 0) {
                    val regionName = DedicatedDnsResolver.regionByCode(dedicatedDnsRegion).nameFa
                    val cfg = XrayJsonGenerator.generateDnsBoostConfig(
                        localPort = 10808,
                        dnsServers = emptyList(), // real fallback resolvers added by the generator
                        dedicatedDnsUrl = dedicatedXrayDnsUrl(),
                        dedicatedDnsDomains = server.testEndpoints,
                        staticHosts = hostToIps
                    )
                    Log.d("GameBoosterManager", "Dedicated DNS Direct Boost: region=$regionName ping=${avgPing}ms")
                    return BoostResult(
                        mode = BoostMode.DIRECT,
                        pingMs = avgPing,
                        jitterMs = jitter,
                        nodeId = "dedicated_dns_boost",
                        nodeName = "Dedicated DNS",
                        nodeUri = cfg,
                        details = "Direct (DNS اختصاصی - $regionName)"
                    )
                }
                Log.w("GameBoosterManager", "Dedicated DNS resolved ${ips.size} IPs but none pingable -- falling through")
            }
        }

        // ÛŒÚ© Ø¨Ø§Ø± Ø¨Ø±Ø§ÛŒ Ù‡Ø± hostname Ù‡Ù…Ù‡â€ŒÛŒ ØªØ±Ú©ÛŒØ¨â€ŒÙ‡Ø§ÛŒ DNS+Ù¾ÙˆØ±Øª Ø±Ùˆ race Ù…ÛŒâ€ŒÚ©Ù†ÛŒÙ… Ùˆ Ø§Ø² Ù‡Ù…ÙˆÙ† ÛŒÚ© Ù†ØªÛŒØ¬Ù‡
        // Ù‡Ù… IPÙ‡Ø§ÛŒ resolve Ø´Ø¯Ù‡ (Ø¨Ø±Ø§ÛŒ ØªØ³Øª Ù¾ÛŒÙ†Ú¯ Ù…Ø³ØªÙ‚ÛŒÙ…) Ùˆ Ù‡Ù… Ù„ÛŒØ³Øª Ø³Ø±ÙˆØ±Ù‡Ø§ÛŒ DNS Ø²Ù†Ø¯Ù‡ (Ø¨Ø±Ø§ÛŒ VPN Ù…Ø­Ù„ÛŒ
        // Ø¯Ø± ØµÙˆØ±Øª Ù†ÛŒØ§Ø²) Ø±Ùˆ Ø§Ø³ØªØ®Ø±Ø§Ø¬ Ù…ÛŒâ€ŒÚ©Ù†ÛŒÙ… -- Ø¨Ù‡â€ŒØ¬Ø§ÛŒ race Ø¬Ø¯Ø§Ú¯Ø§Ù†Ù‡ Ø¨Ø±Ø§ÛŒ Ù‡Ø± Ù…ØµØ±Ù.
        val udpResults = mutableListOf<DnsRaceTester.DnsResult>()
        for (endpoint in server.testEndpoints) {
            Log.d("GameBoosterManager", "--- DnsRace (UDP): resolve Ù‡Ù…Ù‡â€ŒÛŒ IPÙ‡Ø§ÛŒ $endpoint ---")
            udpResults.addAll(DnsRaceTester.findWorkingDnsServers(endpoint))
        }
        val udpIps = udpResults.mapNotNull { it.resolvedIp }.distinct()

        if (udpIps.isNotEmpty()) {
            Log.d("GameBoosterManager", "${udpIps.size} IP Ù…ØªÙØ§ÙˆØª Ø§Ø² UDP resolve Ø´Ø¯: $udpIps")
            // Ù¾ÛŒÙ†Ú¯ Ù‡Ù…Ù‡â€ŒÛŒ Ú©Ø§Ù†Ø¯ÛŒØ¯Ù‡Ø§ Ø±Ùˆ Ø§Ù…ØªØ­Ø§Ù† Ú©Ù† -- Ø§Ú¯Ù‡ ÛŒÚ©ÛŒ ÙˆØµÙ„ Ø´Ø¯ Ù†ÛŒØ§Ø²ÛŒ Ø¨Ù‡ Ø¨Ù‚ÛŒÙ‡â€ŒÛŒ Ø±ÙˆØ´â€ŒÙ‡Ø§ Ù†ÛŒØ³Øª
            val (avgPing, jitter) = GamePingTester.averagePing(udpIps, server.port, proxyPort = null)
            if (avgPing > 0) {
                return BoostResult(mode = BoostMode.DIRECT, pingMs = avgPing, jitterMs = jitter, details = "Direct (DNS بهینه)")
            }
            Log.w("GameBoosterManager", "Ù‡ÛŒÚ†â€ŒÚ©Ø¯ÙˆÙ… Ø§Ø² ${udpIps.size} IP resolve Ø´Ø¯Ù‡ Ø¨Ø§ UDP ÙˆØµÙ„ Ù†Ø´Ø¯Ù† -- ØªÙ„Ø§Ø´ Ø¨Ø§ DNS-over-HTTPS")
        } else {
            // Ù‡ÛŒÚ† DNSØ§ÛŒ Ø±ÙˆÛŒ UDP:53/Ù¾ÙˆØ±Øªâ€ŒÙ‡Ø§ÛŒ Ø¬Ø§ÛŒÚ¯Ø²ÛŒÙ† Ø¬ÙˆØ§Ø¨ Ù‚Ø§Ø¨Ù„â€ŒØ§Ø³ØªÙØ§Ø¯Ù‡ Ù†Ø¯Ø§Ø¯ -- Ø§ÛŒÙ† Ù…Ø¹Ù…ÙˆÙ„Ø§Ù‹ ÛŒØ¹Ù†ÛŒ
            // Ø§Ù¾Ø±Ø§ØªÙˆØ± Ú©Ù„ ØªØ±Ø§ÙÛŒÚ© UDP Ø±Ùˆ ØµØ±Ùâ€ŒÙ†Ø¸Ø± Ø§Ø² IP Ù…Ù‚ØµØ¯ Ø´ÙØ§Ù Ø±Ù‡Ú¯ÛŒØ±ÛŒ Ù…ÛŒâ€ŒÚ©Ù†Ù‡. DNS-over-HTTPS Ø±Ùˆ
            // Ø§Ù…ØªØ­Ø§Ù† Ú©Ù† Ú©Ù‡ Ø±Ù‡Ú¯ÛŒØ±ÛŒâ€ŒØ§Ø´ Ø¨Ø±Ø§ÛŒ Ø§Ù¾Ø±Ø§ØªÙˆØ± Ø®ÛŒÙ„ÛŒ Ø³Ø®Øªâ€ŒØªØ±Ù‡.
            Log.w("GameBoosterManager", "Ù‡ÛŒÚ† DNSØ§ÛŒ Ù‡ÛŒÚ†â€ŒÚ©Ø¯Ø§Ù… Ø§Ø² hostnameÙ‡Ø§ÛŒ Ø¨Ø§Ø²ÛŒ Ø±Ø§ resolve Ù†Ú©Ø±Ø¯ -- ØªÙ„Ø§Ø´ Ø¨Ø§ DNS-over-HTTPS")
        }

        // DoH: Ù‡Ù… ÙˆÙ‚ØªÛŒ UDP Ù‡ÛŒÚ†ÛŒ resolve Ù†Ú©Ø±Ø¯ØŒ Ù‡Ù… ÙˆÙ‚ØªÛŒ resolve Ú©Ø±Ø¯ ÙˆÙ„ÛŒ ÙˆØµÙ„ Ù†Ø´Ø¯ (Ø±Ú©ÙˆØ±Ø¯
        // Ù…Ø³ÛŒØ±ÛŒØ§Ø¨ÛŒâ€ŒØ´Ø¯Ù‡â€ŒÛŒ Shecan/Electro Ù…Ù…Ú©Ù†Ù‡ Ù‚Ø¯ÛŒÙ…ÛŒ Ø¨Ø§Ø´Ù‡Ø› DoH Ø§Ø² Ø³Ø±ÙˆØ± Ø§ØµÙ„ÛŒ Ú¯ÙˆÚ¯Ù„/Ú©Ù„ÙˆØ¯ÙÙ„Ø±
        // Ø¬ÙˆØ§Ø¨ ÙˆØ§Ù‚Ø¹ÛŒ Ùˆ Ø¨Ù‡â€ŒØ±ÙˆØ² Ù…ÛŒâ€ŒÚ¯ÛŒØ±Ù‡)
        val dohIps = mutableListOf<String>()
        for (endpoint in server.testEndpoints) {
            val ips = DnsRaceTester.resolveViaDoh(endpoint)
            if (ips.isNotEmpty()) {
                Log.d("GameBoosterManager", "DoH Ù…ÙˆÙÙ‚: $endpoint -> $ips")
                dohIps.addAll(ips)
            }
        }
        val newDohIps = dohIps.distinct().filterNot { it in udpIps }
        if (newDohIps.isNotEmpty()) {
            val (avgPing, jitter) = GamePingTester.averagePing(newDohIps, server.port, proxyPort = null)
            if (avgPing > 0) {
                return BoostResult(mode = BoostMode.DIRECT, pingMs = avgPing, jitterMs = jitter, details = "Direct (DNS Ø¨Ù‡ÛŒÙ†Ù‡)")
            }
        }

        if (udpIps.isEmpty() && dohIps.isEmpty()) {
            Log.w("GameBoosterManager", "Ù‡ÛŒÚ†â€ŒÚ©Ø¯Ø§Ù… Ø§Ø² Ø±ÙˆØ´â€ŒÙ‡Ø§ÛŒ DNS (UDP ÛŒØ§ DoH) Ù†ØªÙˆÙ†Ø³ØªÙ† hostnameÙ‡Ø§ÛŒ Ø¨Ø§Ø²ÛŒ Ø±Ùˆ resolve Ú©Ù†Ù†")
        } else {
            Log.w("GameBoosterManager", "hostnameÙ‡Ø§ÛŒ Ø¨Ø§Ø²ÛŒ resolve Ø´Ø¯Ù† ÙˆÙ„ÛŒ Ù‡ÛŒÚ† IPØ§ÛŒ Ù…Ø³ØªÙ‚ÛŒÙ…Ø§Ù‹ Ø§Ø² Ø§Ù¾ Ù…Ø§ ÙˆØµÙ„ Ù†Ø´Ø¯")
        }

        // Ù¾ÛŒÙ†Ú¯ Ú©Ø±Ø¯Ù† Ù…Ø³ØªÙ‚ÛŒÙ… Ø§Ø² Ù¾Ø±Ø¯Ø§Ø²Ø´ Ø®ÙˆØ¯Ù…ÙˆÙ† (ØªØ§ÛŒÙ…â€ŒØ§ÙˆØª Ú©ÙˆØªØ§Ù‡ØŒ ÛŒÙ‡ ØªÙ„Ø§Ø´) Ù‚Ø§Ø¨Ù„â€ŒØ§Ø¹ØªÙ…Ø§Ø¯ Ù†Ø¨ÙˆØ¯: ÛŒÙ‡
        // Ù¾Ø±Ø§Ø¨ ÙˆØ§Ù‚Ø¹ÛŒ Ø§Ø² ÛŒÚ© Ù†Ø±Ù…â€ŒØ§ÙØ²Ø§Ø± Ù…Ø±Ø¬Ø¹ Ù†Ø´ÙˆÙ† Ø¯Ø§Ø¯ Ø§ÙˆÙ† Ø¨Ù‡â€ŒØ¬Ø§ÛŒ Ø§ÛŒÙ† Ú©Ø§Ø±ØŒ Ø®ÙˆØ¯Ø´ ÛŒÙ‡ VPN Ù…Ø­Ù„ÛŒ
        // (Ø¨Ø¯ÙˆÙ† Ù‡Ø§Ù¾ Ø±Ø§Ù‡â€ŒØ¯ÙˆØ± -- ÙÙ‚Ø· DNS Ø±Ùˆ Ø¹ÙˆØ¶ Ù…ÛŒâ€ŒÚ©Ù†Ù‡ Ùˆ Ø¨Ù‚ÛŒÙ‡â€ŒÛŒ ØªØ±Ø§ÙÛŒÚ© Ø±Ùˆ Ù…Ø³ØªÙ‚ÛŒÙ… Ø±Ø¯ Ù…ÛŒâ€ŒÚ©Ù†Ù‡)
        // Ø±Ø§Ù‡ Ù…ÛŒâ€ŒÙ†Ø¯Ø§Ø²Ù‡ Ùˆ Ù…ÛŒâ€ŒØ°Ø§Ø±Ù‡ Ø®ÙˆØ¯Ù Ø³ÛŒØ³ØªÙ…â€ŒØ¹Ø§Ù…Ù„/Ø¨Ø§Ø²ÛŒ Ø¨Ø§ DNS ØªÙ…ÛŒØ² Ùˆ stack ÙˆØ§Ù‚Ø¹ÛŒ Ø´Ø¨Ú©Ù‡ ÙˆØµÙ„ Ø¨Ø´Ù‡.
        // Ø§Ú¯Ù‡ Ø­Ø¯Ø§Ù‚Ù„ ÛŒÙ‡ DNS Ø¨Ø±Ø§ÛŒ ÛŒÚ©ÛŒ Ø§Ø² hostnameÙ‡Ø§ÛŒ Ø¨Ø§Ø²ÛŒ Ø¬ÙˆØ§Ø¨ Ø¯Ø§Ø¯Ù‡ Ø¨Ø§Ø´Ù‡ (Ø­ØªÛŒ Ø§Ú¯Ù‡ Ù¾Ø±Ø§Ø¨ Ø®ÙˆØ¯Ù…ÙˆÙ†
        // Ù†ØªÙˆÙ†Ø³Øª Ø¨Ù‡Ø´ ÙˆØµÙ„ Ø¨Ø´Ù‡)ØŒ Ù‡Ù…ÛŒÙ†Ùˆ Ø¨Ù‡â€ŒØ¹Ù†ÙˆØ§Ù† DNS Ø§ÛŒÙ† VPN Ù…Ø­Ù„ÛŒ Ù…Ø¹Ø±ÙÛŒ Ù…ÛŒâ€ŒÚ©Ù†ÛŒÙ….
        val distinctDns = udpResults.distinctBy { it.dnsServer.ip }.sortedBy { it.latencyMs }.take(3)
        if (distinctDns.isNotEmpty()) {
            Log.d("GameBoosterManager", "Direct DNS Boost (VPN Ù…Ø­Ù„ÛŒ) Ø¨Ø§ DNSÙ‡Ø§ÛŒ: ${distinctDns.map { it.dnsServer.ip }}")
            val dnsBoostConfig = XrayJsonGenerator.generateDnsBoostConfig(
                localPort = 10808,
                dnsServers = distinctDns.map { it.dnsServer.ip }
            )
            return BoostResult(
                mode = BoostMode.DIRECT,
                // Ù¾ÛŒÙ†Ú¯ ÙˆØ§Ù‚Ø¹ÛŒÙ Ø¨Ø§Ø²ÛŒ Ù‡Ù†ÙˆØ² Ù…Ø¹Ù„ÙˆÙ… Ù†ÛŒØ³Øª (ØªØ§ ÙˆØµÙ„ Ù†Ø´ÛŒÙ… Ù†Ù…ÛŒâ€ŒÙÙ‡Ù…ÛŒÙ…) -- ØªØ§Ø®ÛŒØ± Ù¾Ø§Ø³Ø® DNS Ø±Ùˆ
                // Ø¨Ù‡â€ŒØ¹Ù†ÙˆØ§Ù† ØªØ®Ù…ÛŒÙ† Ù†Ø´ÙˆÙ† Ù…ÛŒâ€ŒØ¯ÛŒÙ…ØŒ Ø¯Ù‚ÛŒÙ‚Ø§Ù‹ Ú©Ø§Ø±ÛŒ Ú©Ù‡ Ù†Ø±Ù…â€ŒØ§ÙØ²Ø§Ø± Ù…Ø±Ø¬Ø¹ Ù‡Ù… Ø§Ù†Ø¬Ø§Ù… Ù…ÛŒâ€ŒØ¯Ù‡.
                pingMs = distinctDns.first().latencyMs,
                jitterMs = 0L,
                nodeId = "direct_dns_boost",
                nodeName = "Direct DNS Boost",
                nodeUri = dnsBoostConfig,
                details = "Direct (DNS Ø¨Ù‡ÛŒÙ†Ù‡ØŒ Ø¨Ø¯ÙˆÙ† ØªÙˆÙ†Ù„)"
            )
        }

        // Fallback Ù†Ù‡Ø§ÛŒÛŒ: ØªØ³Øª Ù…Ø³ØªÙ‚ÛŒÙ… Ø¨Ø§ endpoints Ø§ØµÙ„ÛŒ (DNS Ù¾ÛŒØ´â€ŒÙØ±Ø¶ Ú¯ÙˆØ´ÛŒ)
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
                
                // Measure node latency using TLS Ping (device -> node only)
                // Note: This measures client-to-node latency, not end-to-end game server latency.
                // A true end-to-end test would require starting Xray SOCKS locally and using proxyPing,
                // which needs VPN permission and is too heavyweight for a quick boost test.
                val sni = if (config.sni.isNotEmpty()) config.sni else config.wsHost
                val fullTlsTime = com.mlmvpn.scanner.ui.tlsPing(config.address, config.port, sni)
                
                if (fullTlsTime > 0) {
                    // TLS Handshake â‰ˆ 1.5 RTTs (with session resumption) to 2 RTTs (full handshake)
                    // Use a factor of 1.5 for a more realistic estimate than hard /2
                    pingMs = (fullTlsTime * 2L / 3L)
                    
                    // Estimate jitter as 20% of ping (conservative for single measurement)
                    jitterMs = pingMs / 5L
                    
                    Log.d("GameBoosterManager", "Node Test [${node.name}]: estimated ping=${pingMs}ms")
                }
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

    fun connectWithBestResult(result: BoostResult, gamePackage: String) {
        // Debounce: ignore connects that arrive within 2s of the previous one. This kills the
        // "play button storm" (rapid taps queued 15 MyVpnService starts in a few seconds and crashed
        // the app when a WireGuard connect got interleaved with an Xray one -- two Go runtimes at
        // once = SIGABRT). Legitimate mode switches and the 5s health-monitor fallback are unaffected.
        val now = System.currentTimeMillis()
        if (now - lastConnectAt < 2000) {
            // Silent before, which meant a swallowed tap was indistinguishable from a broken
            // button. The state is restored so the UI cannot be left mid-spinner over a connect
            // that was never started.
            Log.w("GameBoosterManager", "connectWithBestResult ignored: debounced (too soon after previous connect)")
            if (boosterState.value == BoosterState.CONNECTING) boosterState.value = BoosterState.IDLE
            return
        }
        lastConnectAt = now

        boosterState.value = BoosterState.CONNECTING
        activeGamePackage.value = gamePackage

        Log.d("GameBoosterManager", "Connecting with best result: mode=${result.mode}, ping=${result.pingMs}ms for package $gamePackage")
        when (result.mode) {
            BoostMode.DIRECT, BoostMode.DEDICATED_DNS, BoostMode.UAE_DNS -> {
                antiDpiRotationJob?.cancel()
                antiDpiRotationJob = null
                if (result.nodeUri != null) {
                    // Direct/Dedicated DNS Boost: a local pass-through VPN (no remote hop, so no
                    // added latency) that only overrides DNS -- lets the OS/game's own network
                    // stack resolve+connect through a DNS that actually has working routes, instead
                    // of trusting our own single-shot probe (which proved unreliable).
                    Log.d("GameBoosterManager", "${result.mode} DNS Boost selected. Starting local pass-through VPN.")
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
                        attachDedicatedDnsExtras(this)
                    }
                    context.startService(intent)
                } else {
                    Log.d("GameBoosterManager", "Direct mode: connection already clean, no VPN needed.")
                }
                boosterState.value = BoosterState.BOOSTED
            }
            BoostMode.TUNNEL -> {
                Log.d("GameBoosterManager", "${result.mode} mode selected. Node: ${result.nodeName}. Starting VPN in Game Mode.")
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
                    attachDedicatedDnsExtras(this)
                }
                context.startService(intent)

                boosterState.value = BoosterState.BOOSTED
                // WARP now self-manages its transport via the multi-engine selector, so the old
                // reserved-bytes/endpoint rotation no longer applies to either mode.
                antiDpiRotationJob?.cancel()
                antiDpiRotationJob = null
            }
            // AUTO never reaches connect with its own mode; WARP is retired from the Game tab
            // (removed from the UI + AUTO race) — kept in the enum only so old persisted
            // selections and exhaustive when-branches don't break. Both are no-ops here.
            BoostMode.AUTO, BoostMode.WARP -> {}
            BoostMode.AETHER -> {
                // Aether routes TUN -> tun2proxy -> the Aether process's local SOCKS5 (dispatched
                // by MyVpnService whenever the config's "type" is "aether" -- see AetherTunEngine).
                //
                // GAME_MODE / GAME_PACKAGE are now passed. They were previously omitted on the
                // grounds that game-mode routing "fights a tunnel that owns its own TUN", but
                // that is not what they do here: Aether's openTun callback calls the very same
                // setupVpn(), and the only thing the game-mode prefs change inside it is the
                // Builder gaining addAllowedApplication(gamePackage). Without them a boost meant
                // routing the ENTIRE device through Cloudflare — every background app, every
                // download — which is both slower for the game and not what a per-game booster
                // is supposed to do. With them, only the selected game crosses the tunnel.
                //
                // The dedicated-DNS extras stay off: those drive Xray config generation, which
                // has no part in this path.
                Log.d("GameBoosterManager", "AETHER mode selected. Tunnelling only $gamePackage.")
                val intent = Intent(context, MyVpnService::class.java).apply {
                    putExtra("NODE_URI", result.nodeUri)
                    putExtra("NODE_ID", result.nodeId)
                    putExtra("GAME_MODE", true)
                    putExtra("GAME_PACKAGE", gamePackage)
                }
                context.startService(intent)

                // CONNECTING, not BOOSTED.
                //
                // Every other mode here reaches its destination the moment startService()
                // returns, because a DNS boost or an Xray config is live as soon as the
                // service is up. Aether is not: startService() only kicks off identity
                // enrolment and a gateway scan that takes anywhere from 4 seconds on a cached
                // endpoint to over two minutes on a cold «متعادل» sweep. Announcing BOOSTED
                // there was simply false — the button went green while the scan had not even
                // begun, and the user's traffic was still going out untouched.
                //
                // awaitAetherBoost() below watches the engine's own state and publishes the
                // real answer when there is one.
                boosterState.value = BoosterState.CONNECTING
                awaitAetherBoost()
                antiDpiRotationJob?.cancel()
                antiDpiRotationJob = null
            }
        }
    }

    /**
     * Phase 5.1: measures live ping every 5s like before, but now also tracks consecutive
     * degraded/timed-out samples. After [PING_FAIL_THRESHOLD] bad samples in a row it tries to
     * switch to the next-best fallback (another Tunnel node, another WARP endpoint, etc.) instead
     * of just sitting on a connection that's gone bad for the rest of the session.
     */
    suspend fun startLivePingMonitor(server: GameServer, proxyPort: Int? = null) {
        selectedServer.value = server
        var currentProxyPort = proxyPort
        var consecutiveBad = 0
        while (true) {
            coroutineContext.ensureActive()
            // Measure across ALL endpoints, not just the first: a single dead/blocked/slow game
            // hostname must not blank out the live reading (this was the main cause of the ping
            // showing "—"/نامشخص while actually connected). averagePing skips the failures and
            // averages whatever responds. proxyPort=null falls back to direct pings automatically.
            val (ping, _) = GamePingTester.averagePing(
                endpoints = server.testEndpoints,
                port = server.port,
                samples = 1,
                proxyPort = currentProxyPort
            )
            livePing.value = ping

            val isBad = ping < 0 || ping > PING_DEGRADED_MS
            consecutiveBad = if (isBad) consecutiveBad + 1 else 0

            if (consecutiveBad >= PING_FAIL_THRESHOLD) {
                val gamePackage = activeGamePackage.value
                if (gamePackage != null && trySwitchToFallback(server, gamePackage)) {
                    consecutiveBad = 0
                    // Tunnel keeps using the same local SOCKS port; WARP/Hybrid fall back to raw
                    // ping since they aren't measured through the SOCKS inbound.
                    currentProxyPort = if (bestResult.value?.mode == BoostMode.TUNNEL) currentProxyPort else null
                    delay(3000) // give the freshly (re)started VPN a moment to come up
                }
            }

            delay(5000)
        }
    }

    private fun resultKey(r: BoostResult): String = "${r.mode}:${r.nodeId ?: r.details}"

    /**
     * Tries the next-best untried candidate from this session's original test results (e.g. other
     * Tunnel nodes). Only ever touches an active VPN connection -- Direct/Dedicated-DNS are local
     * pass-through VPNs with nothing to switch to, and WARP self-manages its own transport.
     */
    private suspend fun trySwitchToFallback(server: GameServer, gamePackage: String): Boolean {
        if (!MyVpnService.isRunning) return false
        val current = bestResult.value ?: return false
        // Direct / Dedicated-DNS / UAE-DNS are local pass-through VPNs with no alternate route to
        // switch to (just a DNS override), so there's nothing to fall back to.
        if (current.mode == BoostMode.DIRECT || current.mode == BoostMode.DEDICATED_DNS ||
            current.mode == BoostMode.UAE_DNS) return false
        excludedFromSwitch.add(resultKey(current))

        // NEVER auto-switch across the Aether (separate process, owns its own TUN) ↔ Xray(libgojni)
        // engine boundary: starting one while the other is mid-teardown races two engines fighting
        // over the same TUN/VpnService. Only consider fallbacks in the SAME engine family as the
        // current connection.
        val curIsAether = current.mode == BoostMode.AETHER
        val next = allResults.value
            .filter {
                it.pingMs > 0 && resultKey(it) !in excludedFromSwitch &&
                    (it.mode == BoostMode.AETHER) == curIsAether
            }
            .sortedBy { it.pingMs }
            .firstOrNull()

        if (next == null) {
            Log.w("GameBoosterManager", "Health monitor: connection degraded (${current.mode}) but no fallback candidate is available -- staying put")
            return false
        }

        Log.w("GameBoosterManager", "Health monitor: ping degraded on ${current.mode}(${current.details}) -- switching to ${next.mode}(${next.details})")
        bestResult.value = next
        connectWithBestResult(next, gamePackage)
        return true
    }

    fun disconnect() {
        antiDpiRotationJob?.cancel()
        antiDpiRotationJob = null
        excludedFromSwitch.clear()

        // Clear the session's dedicated-DNS scoping so a later non-game connect never inherits it.
        dedicatedDnsBase = null
        dedicatedDnsRegion = DedicatedDnsResolver.DEFAULT_REGION
        dedicatedDnsDomains = emptyList()

        val prefs = context.getSharedPreferences("game_booster_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("game_mode_active", false).apply()

        if (MyVpnService.isRunning) {
            com.mlmvpn.scanner.ui.stopVpnSafely(context)
        }

        boosterState.value = BoosterState.IDLE
        bestResult.value = null
        allResults.value = emptyList()
        livePing.value = -1L
        activeGamePackage.value = null
        selectedServer.value = null
    }
}


