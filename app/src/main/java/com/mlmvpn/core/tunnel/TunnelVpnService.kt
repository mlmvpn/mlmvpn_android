package com.mlmvpn.core.tunnel

import com.mlmvpn.core.geph.GephAccount
import com.mlmvpn.core.geph.GephEngine
import com.mlmvpn.core.geph.GephSettings

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.content.pm.ServiceInfo
import android.service.quicksettings.TileService
import android.net.IpPrefix
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import ca.psiphon.PsiphonTunnel
import com.mlmvpn.scanner.MainActivity
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.store.tr
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Protocol sets shared between a rung's config and its winner-detection.
 *
 * Declared top-level (not in the companion) so the ladder property initializer
 * can reference them without depending on companion init order.
 *
 * Every name here was verified to exist as a substring in libgojni.so. The
 * INPROXY-* names are deliberately absent: they are assembled at runtime and do
 * not appear as literals, so passing one risks failing config validation.
 */
/**
 * Where Psiphon's own server list lives.
 *
 * These are the production endpoints for propagation channel `iohq-waa4-q4dt`, read out of the
 * official Psiphon Android client rather than invented: the four hosts, their `OnlyAfterAttempts`
 * ordering and their `SkipVerify` flags are exactly what that client ships. The signature key we
 * already carry in [buildPsiphonConfig] is byte-for-byte the one that signs this list, which is
 * what makes fetching it meaningful -- an unsigned or wrongly-signed list is discarded by the
 * core, so this cannot be pointed anywhere else and quietly work.
 *
 * The first host is the origin. The other three are CDN mirrors and carry `SkipVerify`, because
 * they are reached through a front where the TLS certificate is the CDN's rather than the site's.
 *
 * ## Why this was empty until now
 *
 * `RemoteServerListURL` was set to "", so the client had exactly the 430 server entries compiled
 * into the APK and never learned another one. Of those 430, five advertise FRONTED-MEEK -- and on
 * a line where direct protocols are blocked, those five ARE the tunnel. Measured on the reporting
 * device: strategy D (all direct protocols) timed out, strategy C (in-proxy) timed out, and only
 * the fronted rung connected, at 29 KB/s. Five candidates is not a pool, it is a bottleneck, and
 * it is the one the user feels as "Psiphon connects but it is 1 Mbit".
 */
private val PSIPHON_REMOTE_SERVER_LIST_URLS = listOf(
    Triple("https://s3.amazonaws.com/psiphon/web/iohq-waa4-q4dt/server_list_compressed", 0, false),
    Triple("https://www.gpallthingsnumberweather.com/web/iohq-waa4-q4dt/server_list_compressed", 2, true),
    Triple("https://www.storagejsstrategiesfabulous.com/web/iohq-waa4-q4dt/server_list_compressed", 2, true),
    Triple("https://www.diamondberlingamerplanet.com/web/iohq-waa4-q4dt/server_list_compressed", 2, true),
)

/** The obfuscated server list, same four hosts. See [PSIPHON_REMOTE_SERVER_LIST_URLS]. */
private val PSIPHON_OSL_ROOT_URLS = listOf(
    Triple("https://s3.amazonaws.com/psiphon/web/iohq-waa4-q4dt/osl", 0, false),
    Triple("https://www.gpallthingsnumberweather.com/web/iohq-waa4-q4dt/osl", 2, true),
    Triple("https://www.storagejsstrategiesfabulous.com/web/iohq-waa4-q4dt/osl", 2, true),
    Triple("https://www.diamondberlingamerplanet.com/web/iohq-waa4-q4dt/osl", 2, true),
)

/**
 * Psiphon's own encoding for a download-URL list: the URL is base64, the rest is plain.
 *
 * `OnlyAfterAttempts` is how the core spreads load -- the origin is tried first and the mirrors
 * only after the given number of failures, so a working origin never touches them.
 */
private fun psiphonUrlList(urls: List<Triple<String, Int, Boolean>>): JSONArray =
    JSONArray().apply {
        urls.forEach { (url, after, skipVerify) ->
            put(
                JSONObject()
                    .put("URL", android.util.Base64.encodeToString(
                        url.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
                    .put("OnlyAfterAttempts", after)
                    .put("SkipVerify", skipVerify)
            )
        }
    }

private val PROTOCOLS_FRONTED = listOf(
    "FRONTED-MEEK-OSSH",
    "FRONTED-MEEK-HTTP-OSSH",
    "FRONTED-MEEK-QUIC-OSSH",
)

/**
 * Direct-dial protocols, i.e. everything that connects straight to a Psiphon
 * server IP. Used only for winner detection — the direct rung passes no
 * protocol limit at all and lets Psiphon pick.
 */
private val PROTOCOLS_DIRECT = listOf(
    "QUIC-OSSH",
    "TLS-OSSH",
    "UNFRONTED-MEEK-HTTPS-OSSH",
    "UNFRONTED-MEEK-OSSH",
    "SHADOWSOCKS-OSSH",
    "CONJURE-OSSH",
    "OSSH",
    "SSH",
)

/**
 * Every protocol that can cross a SOCKS5 upstream proxy — i.e. TCP only.
 *
 * Used as a HARD limit (`LimitTunnelProtocols`, not the `InitialLimit…`
 * preference) whenever Psiphon runs over WARP. Two separate reasons it has to be
 * a hard limit:
 *
 *  - `InitialLimitTunnelProtocols` is only a preference: once the candidate
 *    budget is spent Psiphon reverts to its full set, which includes the
 *    `INPROXY-WEBRTC-*` entries the bundled server list advertises.
 *  - in-proxy is WebRTC, so it needs raw UDP sockets. A SOCKS5 upstream cannot
 *    carry those, and the field log proves what happens: STUN goes out over the
 *    carrier instead of the tunnel ("Failed get server reflexive address udp4
 *    stun:… timeout while waiting for XORMappedAddr"), ICE gathering takes 34s
 *    instead of ~130ms, and the broker round trip resolves DNS *untunneled*
 *    (`UntunneledResolveIP` → `context deadline exceeded`) on exactly the link
 *    Hamrah-e-Aval null-routes.
 *
 * QUIC-OSSH is absent for the same reason — it is UDP. Psiphon already declines
 * to dial it when an upstream proxy is set (measured: 0 attempts across a full
 * run, against 3 when dialling directly), so naming it here would be a
 * contradiction rather than an option.
 */
private val PROTOCOLS_CHAINABLE = listOf(
    "FRONTED-MEEK-OSSH",
    "FRONTED-MEEK-HTTP-OSSH",
    "TLS-OSSH",
    "UNFRONTED-MEEK-HTTPS-OSSH",
    "UNFRONTED-MEEK-OSSH",
    "SHADOWSOCKS-OSSH",
    "OSSH",
    "SSH",
)

/**
 * Public DNS resolvers on NON-standard ports, for Psiphon's own resolver.
 *
 * Why this exists, from a field log where Psiphon could not connect at all:
 * every dial died on the same line —
 * `checkDNSAnswerIP#1767: IP is bogon`. The resolver got an *answer*, and the
 * answer was a private-range address, i.e. the operator's DNS hijack. Psiphon
 * correctly refused it, so tactics never loaded and not one of the five bundled
 * FRONTED-MEEK entries could be resolved. `Tunnels: {"count":0}`.
 *
 * The resolvers we put on the TUN (`applyDns`, 1.1.1.1 / 8.8.8.8) do NOT help
 * here. Psiphon's resolver builds its own UDP socket and calls `bindToDevice` on
 * it (upstream `resolver.go`), so it leaves *outside* the TUN, straight onto the
 * operator link where UDP/53 is hijacked no matter which address is targeted.
 *
 * The port is the whole point. The hijack observed intercepts UDP/53; the same
 * providers answering on another port were reached cleanly from an uncensored
 * host. So these are ordinary public resolvers reached where the interception
 * does not sit:
 *
 *  - 208.67.222.222:5353 / 208.67.220.220:5353 — OpenDNS's alternate port
 *  - 9.9.9.9:9953 — Quad9's alternate port
 *
 * Scope, so nobody expects too much of this: it fixes name resolution only. On a
 * network that also blocks the transport itself there is nothing to resolve to,
 * and a chained run never even reaches this code — the outer WARP leg has to be
 * up first, and if it is up then DNS was never the problem. Plain Psiphon is
 * where this pays off.
 */
private val PSIPHON_ALTERNATE_DNS = listOf(
    "208.67.222.222:5353",
    "9.9.9.9:9953",
    "208.67.220.220:5353",
)

private const val PSIPHON_RSL_KEY = "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="
private const val PSIPHON_ENTRY_KEY = "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA="
private const val PSIPHON_EXCHANGE_KEY = "DpXzloJk1Hw6aSzmKKky0xcahsEHubch81Mi6K0XMlU="

/**
 * MLM Scout's Psiphon: the same base config a connect starts from -- keys, server lists, the
 * Iran tactics, the alternate resolvers, the worker pool -- on Scout's own loopback port, with no
 * TUN behind it and no ladder rung, so Psiphon chooses among all its protocols the way it does on
 * the first try of a connect. [upstreamSocksPort]: through WARP, TCP protocols only, exactly as
 * the chained connect limits them.
 */
internal fun psiphonProbeConfig(context: android.content.Context, socksPort: Int, upstreamSocksPort: Int?): String =
    JSONObject().apply {
        put("PropagationChannelId", "FFFFFFFFFFFFFFFF")
        put("SponsorId", "1111111111111111")
        put("EgressRegion", "")
        put("EstablishTunnelTimeoutSeconds", 150)
        put("DataDirectory", context.filesDir.absolutePath)
        put("ClientVersion", "1")
        put("TunnelProtocol", "")
        put("RemoteServerListURLs", psiphonUrlList(PSIPHON_REMOTE_SERVER_LIST_URLS))
        put("RemoteServerListDownloadFilename", java.io.File(context.filesDir, "psiphon_remote_server_list").absolutePath)
        put("ObfuscatedServerListRootURLs", psiphonUrlList(PSIPHON_OSL_ROOT_URLS))
        put("ObfuscatedServerListDownloadDirectory", java.io.File(context.filesDir, "psiphon_osl").apply { mkdirs() }.absolutePath)
        put("LocalSocksProxyPort", socksPort)
        put("RemoteServerListSignaturePublicKey", PSIPHON_RSL_KEY)
        put("ServerEntrySignaturePublicKey", PSIPHON_ENTRY_KEY)
        put("ExchangeObfuscationKey", PSIPHON_EXCHANGE_KEY)
        put("DeviceRegion", "IR")
        put("ConnectionWorkerPoolSize", 12)
        put("DNSResolverPreferredAlternateServers", JSONArray(PSIPHON_ALTERNATE_DNS))
        put("DNSResolverPreferAlternateServerProbability", 1.0)
        put("DNSResolverAttemptsPerPreferredServer", 2)
        if (upstreamSocksPort != null) {
            put("UpstreamProxyURL", "socks5://127.0.0.1:$upstreamSocksPort")
            put("LimitTunnelProtocols", JSONArray(PROTOCOLS_CHAINABLE))
            put("InproxyEnabled", false)
            put("InproxyAllowClient", false)
        }
    }.toString()

/**
 * «سنجش واقعی»'s Psiphon ([PsiphonProbes]): ONE way in and nothing else -- rung E given only
 * [cleanCidr] (`providerId to "ip/32"`), or rung D through [upstreamUrl] -- on its own [dataRoot] and
 * port. The desktop's psiphon-manager.js › probeWith with the same rung settings. No server-list
 * download: the test is of one way in, and a download per test is data the user pays for.
 */
internal fun psiphonTestConfig(
    socksPort: Int,
    dataRoot: java.io.File,
    timeoutSeconds: Int,
    cleanCidr: Pair<String, String>? = null,
    upstreamUrl: String? = null,
): String = JSONObject().apply {
    put("PropagationChannelId", "FFFFFFFFFFFFFFFF")
    put("SponsorId", "1111111111111111")
    put("ClientVersion", "1")
    put("EgressRegion", "")
    put("TunnelProtocol", "")
    put("EstablishTunnelTimeoutSeconds", timeoutSeconds)
    put("DataRootDirectory", dataRoot.absolutePath)
    put("LocalSocksProxyPort", socksPort)
    put("DisableRemoteServerListFetcher", true)
    put("RemoteServerListSignaturePublicKey", PSIPHON_RSL_KEY)
    put("ServerEntrySignaturePublicKey", PSIPHON_ENTRY_KEY)
    put("ExchangeObfuscationKey", PSIPHON_EXCHANGE_KEY)
    put("DeviceRegion", "IR")
    put("EmitDiagnosticNotices", true)
    put("DNSResolverPreferredAlternateServers", JSONArray(PSIPHON_ALTERNATE_DNS))
    put("DNSResolverPreferAlternateServerProbability", 1.0)
    put("DNSResolverAttemptsPerPreferredServer", 2)
    if (cleanCidr != null) {
        // Rung E: only FRONTED-MEEK-OSSH (the HTTP variant dials port 80, the scan measured 443; the
        // QUIC one never connected on the desktop's line), at the address under test only.
        put("LimitTunnelProtocols", JSONArray(listOf("FRONTED-MEEK-OSSH")))
        put("InitialLimitTunnelProtocols", JSONArray(listOf("FRONTED-MEEK-OSSH")))
        put("InitialLimitTunnelProtocolsCandidateCount", 30)
        put("DNSResolverPreresolvedIPAddressCIDRs", JSONObject().put(cleanCidr.first, JSONArray(listOf(cleanCidr.second))))
        put("DNSResolverPreresolvedIPAddressProbability", 1.0)
        // ConnectedServer then names the address the tunnel was dialled at (meekResolvedIPAddress),
        // which PsiphonProbes checks is the one under test.
        put("EmitDiagnosticNetworkParameters", true)
        put("ConnectionWorkerPoolSize", 8)
        put("NetworkLatencyMultiplier", 1.5)
        put("DisableTactics", true)
        put("InproxyEnabled", false)
        put("InproxyAllowClient", false)
    }
    if (upstreamUrl != null) {
        // Rung D through the proxy: TCP protocols only (a proxy cannot carry Psiphon's UDP ones),
        // tactics on, exactly as a connect through «پروکسی سرور» runs it.
        put("UpstreamProxyURL", upstreamUrl)
        put("LimitTunnelProtocols", JSONArray(PROTOCOLS_CHAINABLE))
        put("ConnectionWorkerPoolSize", 16)
        put("InproxyEnabled", false)
        put("InproxyAllowClient", false)
    }
}.toString()

/** One rung of the tile's Psiphon ladder as a test runs it: its name, its time, its change to the config. */
internal class PsiphonProbeRung(val name: String, val label: String, val seconds: Int, val apply: (JSONObject) -> Unit)

/**
 * The tile's own ladder (TunnelVpnService.psiphonRung A/D/C, unchained) for MLM Scout, starting at the
 * rung that last carried the tile on this device -- the reason a tap connects in seconds. Scout ran
 * only the bare config, which on the user's line took 84 s one scan and found nothing in 150 s the
 * next (2026-10-11). Keep in step with psiphonRung.
 */
internal fun psiphonProbeRungs(context: android.content.Context): List<PsiphonProbeRung> {
    fun noTactics(c: JSONObject) {
        c.put("DisableTactics", true); c.put("InproxyEnabled", false); c.put("InproxyAllowClient", false)
    }
    val ladder = listOf(
        PsiphonProbeRung("A", "domain-fronted (CDN)", 60) { c ->
            c.put("InitialLimitTunnelProtocols", JSONArray(PROTOCOLS_FRONTED))
            c.put("InitialLimitTunnelProtocolsCandidateCount", 30)
            c.put("LimitTunnelProtocols", JSONArray(PROTOCOLS_FRONTED))
            c.put("ConnectionWorkerPoolSize", 12)
            c.put("NetworkLatencyMultiplier", 2.0)
            noTactics(c)
        },
        PsiphonProbeRung("D", "all protocols (direct)", 45) { c ->
            c.put("ConnectionWorkerPoolSize", 16)
            noTactics(c)
        },
        PsiphonProbeRung("C", "in-proxy (peer relay)", 75) { c ->
            c.put("InproxyEnabled", true); c.put("InproxyAllowClient", true)
            c.put("InproxySkipAwaitFullyConnected", true)
            c.put("ConnectionWorkerPoolSize", 16)
            c.put("NetworkLatencyMultiplier", 3.0)
        },
    )
    // The tile's memory, by name, and the same order the tile uses: the remembered rung moved to the front.
    val start = ladder.indexOfFirst { it.name == PsiphonMethods.remembered(context, chained = false) }
    return if (start <= 0) ladder else listOf(ladder[start]) + ladder.filterIndexed { i, _ -> i != start }
}

/**
 * One rung of the Psiphon escalation ladder.
 *
 * Each rung is a complete, self-contained Psiphon config variant plus the time
 * we are willing to spend on it before moving to the next rung. The ladder is
 * ordered by *expected time to first connection on a hostile carrier*, not by
 * how clever the technique is — the cheapest thing that plausibly works goes
 * first so the common case stays fast.
 */
private class PsiphonStrategy(
    val name: String,
    val label: String,
    val timeoutSeconds: Int,
    /**
     * Protocols this rung asks Psiphon to try first.
     *
     * This is only a *preference*: Psiphon falls back to its full protocol set
     * once InitialLimitTunnelProtocolsCandidateCount candidates are exhausted.
     * So the protocol that ends up carrying the tunnel is often not from this
     * list, which is exactly why winner detection reads the live ActiveTunnel
     * notice instead of assuming the active rung won.
     */
    val preferredProtocols: List<String>,
    /**
     * A data folder of its own («آی‌پی تمیز», «پروکسی سرور»), null for the shared one. Psiphon's
     * replay pins a server's previous dial parameters -- the resolved address and the direct route
     * included -- and cannot be switched off from the config, so in the shared folder the first dial
     * of rung E replayed rung A's DNS answer instead of a clean address, and a connect through a
     * proxy replayed what had worked WITHOUT it (desktop: 10–14 s in the real test, 75–150 s in the
     * shared folder, 2026-10-14).
     */
    val dataRoot: String? = null,
    /** «پروکسی سرور»: the proxy this attempt reaches Psiphon through. */
    val upstream: PsiphonUpstream.Candidate? = null,
    val configure: (JSONObject) -> Unit,
)

class TunnelVpnService : VpnService(), TunnelEngine.CoreCallback, PsiphonTunnel.HostService {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Hand work to [worker], or drop it if the service is already gone.
     *
     * `onDestroy` calls `shutdownNow()`, and Android can still deliver an `onStartCommand` to an
     * instance that is being torn down -- an always-on restart racing a stop, a tile tapped as the
     * service ends. The submission then throws `RejectedExecutionException`, which reached a user
     * as a crash on `startTunnel` while opening WireGuard. There is nothing to recover: the
     * service that would have run the work no longer exists, so the honest answer is to drop it
     * and say so in the log rather than to take the app down.
     */
    private fun runOnWorker(block: () -> Unit) {
        try {
            worker.execute {
                // A stop interrupts the worker; a check that was sleeping or waiting (Lantern's
                // stream check, Psiphon's quality check) then threw InterruptedException, raw or
                // wrapped in an Error, and that closed the app (1.2.42 #2006, #2011, #2013, #2014).
                try {
                    block()
                } catch (e: InterruptedException) {
                    Log.i(LOG_TAG, "worker task interrupted (stopping)")
                } catch (e: Error) {
                    if (e.cause is InterruptedException) Log.i(LOG_TAG, "worker task interrupted (stopping)") else throw e
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            Log.w(LOG_TAG, "worker is shut down; dropping queued work: ${e.message}")
        }
    }
    private val connected = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val vpnModeActive = AtomicBoolean(false)
    private var tun: ParcelFileDescriptor? = null
    private var lastTrafficSampleMs = 0L
    private var currentTx = 0L
    private var currentRx = 0L
    private var prevTx = 0L
    private var prevRx = 0L
    private var prevSpeedSampleMs = 0L
    private var currentSpeedTx = 0L
    private var currentSpeedRx = 0L
    private var accountedTx = 0L
    private var accountedRx = 0L
    /** The session totals last handed to the app's live meter, written to disk with the month. */
    private var usageTx = 0L
    private var usageRx = 0L
    private var usageDirty = false
    private val usage by lazy { com.mlmvpn.scanner.data.TrafficManager(this) }
    /**
     * Monthly totals held in memory, flushed to disk on a timer.
     *
     * These used to be written through to SharedPreferences on every traffic
     * sample, i.e. roughly once a second for the whole life of a tunnel. That is
     * thousands of `apply()` calls an hour, each one a disk write behind the
     * scenes — expensive on flash and on battery, to persist a counter nobody
     * reads until the traffic screen is opened.
     *
     * Now the counters live here and reach disk every [TRAFFIC_FLUSH_MS] and on
     * teardown. Worst case a hard process kill loses the last few seconds of
     * accounting, which is not a number anything depends on being exact.
     */
    private var monthKey: String? = null
    private var monthTxTotal = 0L
    private var monthRxTotal = 0L
    private var lastTrafficFlushMs = 0L

    /**
     * Whether this session has already recorded its transport as working.
     *
     * A latch, not a state: the recording is idempotent and only the first crossing
     * of the byte threshold matters, so once set every later traffic sample costs a
     * single boolean test instead of a preferences write. Reset per tunnel in
     * [resetSessionTraffic].
     */
    private var plainTransportRecorded = false
    private var storedConfig: String? = null
    private var currentProtocol = "Tunnel"
    private var currentVpnIp = ""
    private var currentPing = ""

    /**
     * Country shown on the notification's second line, e.g. "Germany".
     *
     * Empty until something authoritative supplies it. The notification simply
     * omits the segment while it is empty rather than printing a placeholder —
     * an unknown exit country is not worth a line of its own.
     */
    private var currentCountry = ""

    /** The last status broadcast, so [republishStatus] can repeat it rather than invent one. */
    private var lastStatus = ""

    /**
     * The exit country as a two-letter code, alongside [currentCountry]'s human name.
     *
     * Both, because they answer to different readers. The notification wants the name, and it
     * wants it in whatever form the source gave it. The screen wants the CODE, because only a
     * code can become a flag and only a code can be turned into a country name in the language
     * the user set -- "United States" arrived from Psiphon in English and stayed English on a
     * Persian UI, and flag() cannot make a flag out of a name.
     */
    private var currentCountryCode = ""

    /**
     * The exit address, from whichever source found it.
     *
     * Two sources, and which one answers depends on the transport. MASQUE, WireGuard and
     * WARP-on-WARP run the Rust core, which measures the address from inside its own tunnel
     * and hands it to [sendExitIp]. Psiphon and Tor never start the core at all, so for them
     * the only source is the app's own probe, which arrives through ACTION_NOTIFICATION_HEALTH.
     * Held here so a repost carries it -- the address is learned after CONNECTED has gone out.
     */
    private var measuredExitIp = ""

    /** Last progress value published, so a repost can reuse it. */
    @Volatile
    private var connectProgress = -1

    /** Polls [TorManager.progress] while Tor bootstraps. */
    private var torProgressTask: java.util.concurrent.ScheduledFuture<*>? = null

    /**
     * Liveness watchdog for an established tunnel. See [startWatchdog].
     */
    private var watchdogTask: java.util.concurrent.ScheduledFuture<*>? = null

    /**
     * True only when the *user* asked to disconnect (dial tap, notification
     * action, kill switch, revoke).
     *
     * The distinction is the whole point of auto-reconnect: a tunnel that dies
     * on its own must come back, and one the user switched off must stay off.
     * [stopRequested] cannot answer this — it is set by every teardown path,
     * including the ones auto-reconnect itself drives.
     */
    private val userInitiatedStop = AtomicBoolean(false)

    /** Consecutive auto-reconnect attempts since the last verified connect. */
    private var reconnectAttempts = 0

    /** The pending auto-reconnect, so a user action can cancel it. */
    private var reconnectTask: java.util.concurrent.ScheduledFuture<*>? = null

    /**
     * Set by the Rust-core path when its tunnel ended without the user asking.
     *
     * The core's lifecycle is a blocking call inside a `try/finally`, so unlike
     * the Psiphon and Tor paths the decision "was this a drop or a disconnect"
     * has to be carried from the body of the try into the finally block.
     */
    private var nativeExitWasUnexpected = false

    /** Composited launcher artwork for the notification. Built once. */
    private var cachedBadge: android.graphics.drawable.Icon? = null
    private var psiphonTunnel: PsiphonTunnel? = null
    private var psiphonConfigJson: String = ""
    private var psiphonVpnMode = false
    private var psiphonVpnActivated = false

    /**
     * True while this session is running the Rust core behind tun2socks rather than
     * letting it hold the tun itself. See [startNativeProxyTunnel].
     *
     * Read by the watchdog (this path dies if routing dies, exactly like Psiphon's)
     * and by [stopTunnel], which has to unwind two pieces here instead of one.
     */
    private var nativeProxyMode = false
    /**
     * The local SOCKS port the running transport publishes, or 0 when it publishes none.
     *
     * Mirrored onto [TunnelStatus.activeSocksPort] on every write, because it answers a
     * question from outside this service: "how does something in this process reach the
     * internet the way the tunnel does". It cannot do that over the tun -- the app excludes
     * ITSELF from its own tunnel to avoid the routing loop (see applySplitTunneling), so an
     * ordinary socket from this process leaves on the carrier's link no matter how healthy the
     * tunnel is. Through this port it goes where the tunnel goes.
     */
    private var activeSocksPort = 0
        set(value) {
            field = value
            TunnelStatus.activeSocksPort = value
        }

    /** «گف» carries this session: Geph's own engine owns the data path (see [startGephTunnel]). */
    @Volatile private var gephMode = false
    /** Android 7 only: tun2proxy in front of Geph's local SOCKS, since the TUN cannot be handed over. */
    @Volatile private var gephBridge = false
    /** «فقط پروکسی»: Geph serves SOCKS/HTTP for the programs pointed at it, and no TUN exists. */
    @Volatile private var gephProxyOnly = false
    private var gephMonitorTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var gephRestarts = 0
    private val gephRestarting = AtomicBoolean(false)
    private val gephChecking = AtomicBoolean(false)
    private var gephLastNetwork: Network? = null
    @Volatile private var gephConnectedSeen = false
    /** Engine counters restart at zero with the engine; the session's own totals must not. */
    private var gephTxBase = 0L
    private var gephRxBase = 0L

    /** «لنترن» carries this session: Lantern 10's own core, reached through tun2proxy. */
    @Volatile private var lanternMode = false
    private var lanternMonitorTask: java.util.concurrent.ScheduledFuture<*>? = null
    private val lanternRestarting = AtomicBoolean(false)
    private var lanternDead = 0
    private var lanternRxBase = 0L
    private var lanternTxBase = 0L
    /** The real network, and its resolvers, the running Lantern core was started on. */
    @Volatile private var lanternNetwork: Network? = null
    @Volatile private var lanternDns: List<String> = emptyList()
    /** One network check at a time: a burst of callbacks becomes one look, a few seconds later. */
    private val lanternNetCheckPending = AtomicBoolean(false)

    /** «وارپ» carries this session: its own WARP engine, reached through tun2proxy. */
    @Volatile private var cfwarpMode = false

    /** «وارپ» runs with the IPv4 exit's front between the routing and its port (started by this session). */
    @Volatile private var cfwarpFront = false
    private var cfwarpMonitorTask: java.util.concurrent.ScheduledFuture<*>? = null
    private val cfwarpRestarting = AtomicBoolean(false)
    private var cfwarpDead = 0
    private var cfwarpRxBase = 0L
    private var cfwarpTxBase = 0L

    /**
     * True while running Psiphon-over-WARP: the Rust core holds a WARP tunnel and
     * publishes a local SOCKS5 listener, and Psiphon dials out through it.
     *
     * Why this mode exists at all — measured on this VPS, not assumed. Chaining
     * costs latency (0.23s direct vs 0.32s chained on the same protocol) and buys
     * no throughput, so it is NOT a speed feature and must never be the default.
     * What it does buy is an exit IP that belongs to neither layer alone: sites
     * that refuse Cloudflare WARP addresses see a Psiphon egress, and carriers
     * that block every Psiphon dial see only a WARP flow. That is the case this
     * mode is for.
     *
     * One measured consequence shapes the config: with an upstream proxy set,
     * Psiphon never dials QUIC-OSSH (0 attempts across a full run, against 3 when
     * dialling directly). Every protocol it does use is TCP, which the core's
     * SOCKS listener carries — CMD_CONNECT only, and it was never asked for a UDP
     * associate in testing.
     */
    private var chainMode = false

    /**
     * True once an outer transport has been accepted and Psiphon started on it.
     *
     * Distinguishes "this rung failed, try the next" from "the transport carrying a
     * live session just died". Before it is set, an outer leg ending is normal —
     * [raiseOuterLeg] is walking the ladder. After it is set, the same event means
     * the chain has lost its foundation and the UI must be told.
     */
    @Volatile
    private var chainOuterCommitted = false

    // Evidence about how the tunnel was actually established, gathered from
    // Psiphon's own notices rather than inferred from which rung was active.
    private var activeTunnelProtocol = ""
    private var inproxyInUse = false

    // --- the slow-server search and the session's stability, see psiphonQualityCheck ---
    /** The server that carries the session has been measured and accepted (per connect). */
    @Volatile private var psiphonQualityDone = false
    /** Which server of this rung is being measured, 1-based; back to 1 on every new rung. */
    @Volatile private var psiphonQualityAttempts = 1
    /** A measurement that outlived its connect (a stop, a newer one) must not act. */
    @Volatile private var psiphonQualityGen = 0
    /** When the live tunnel was lost, 0 while it is up. */
    @Volatile private var psiphonDroppedAt = 0L
    /** When the current rung started, for «connected in N s» and a clean IP's connect time. */
    @Volatile private var psiphonRungStartedAt = 0L

    // --- Psiphon escalation ladder state ---
    // A hostile carrier (Hamrah-e-Aval) null-routes Psiphon's server IPs, so the
    // first rung of the ladder will time out. Rather than sitting on one config
    // for two minutes and giving up, we walk the ladder automatically: each rung
    // gets its own budget, and a timeout promotes us to the next rung without
    // any user interaction.
    //
    // Late work is dropped, not thrown: onDestroy shuts this down, and a network callback or timer
    // that fires a moment later used to schedule onto it and crash the app with
    // RejectedExecutionException (crash reports, 1.2.39).
    private val ladderScheduler: ScheduledExecutorService =
        java.util.concurrent.ScheduledThreadPoolExecutor(1, java.util.concurrent.ThreadPoolExecutor.DiscardPolicy())

    /**
     * Polls [SocksFront]'s byte counters while a Tor session is up.
     *
     * Needed because Tor mode is the only path with no push source for traffic:
     * the Rust core emits a `traffic` event and Psiphon calls
     * `onBytesTransferred`, but a Tor session has neither. Without this the UI
     * would sit at 0 B on a working tunnel and the RX verification gate would
     * never arm.
     *
     * One task per second, cancelled on teardown, and it does nothing but read
     * two atomics — the same cost as the existing notification refresh, so this
     * does not add a wakeup source beyond what a connected session already has.
     */
    private var torTrafficTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var ladderIndex = 0
    private var ladderAttempts = 0
    private var ladderTimer: ScheduledFuture<*>? = null
    private val ladderActive = AtomicBoolean(false)
    private val attributionPending = AtomicBoolean(false)

    /**
     * Whether this attempt is the short "preferred country" attempt that runs in
     * front of the ladder.
     *
     * The user's country choice is a preference, and Psiphon has no way to express
     * one: `EgressRegion` is a hard filter, so a pinned country removes every
     * server outside it from the candidate pool. On the worst domestic operator
     * that is fatal — only 5 of the 430 embedded server entries advertise
     * FRONTED-MEEK and every one of them is US/GB, so a pin on any other country
     * deletes the only protocol family that works there.
     *
     * So the country gets one bounded attempt, on the rung that last carried a
     * tunnel on this device, and then the flag clears and the normal ladder runs
     * with no region filter at all. Cost of a wrong country is
     * [REGION_PHASE_TIMEOUT_SECONDS], not a failed connection.
     */
    /**
     * The country the benchmark is measuring, which outranks the user's saved preference.
     *
     * Set only while [benchmarkRegions] is running, and cleared in its `finally` -- a stale value
     * here would pin every later connect to whichever country the test happened to end on.
     */
    @Volatile
    private var benchRegion: String? = null

    private var regionPhase = false

    /** The country this session already tried, so it is attempted exactly once. */
    private var regionPhaseTried = ""


    /**
     * The escalation ladder, ordered by *measured* time-to-connect on a hostile
     * carrier, using the Build #65 field logs from Hamrah-e-Aval and SamanTel.
     *
     * What those logs proved:
     *
     *  - On Hamrah-e-Aval every direct dial fails at the TCP layer:
     *    TLS-OSSH, UNFRONTED-MEEK-HTTPS-OSSH, OSSH and SSH candidates all end in
     *    "connect: connection timed out" / "i/o timeout" from tcpDial#308. Not
     *    resets, not TLS errors — the packets never arrive. The carrier
     *    null-routes Psiphon server IPs.
     *  - Only FRONTED-MEEK works there, because it dials a CDN edge instead of a
     *    Psiphon-owned IP. It connected on FRONTED-MEEK-HTTP-OSSH in 33s.
     *  - The old first rung ("443-only protocols") therefore burned its entire
     *    45s budget for nothing before the fronted rung even started, which is
     *    the whole reason connecting felt slow.
     *  - On SamanTel a plain direct QUIC-OSSH dial won in seconds, so direct
     *    protocols must stay reachable early for carriers that do not block.
     *
     * Hence the order: fronted first (the only path that works on the hostile
     * carrier), then wide-open direct (fast where nothing is blocked), then
     * in-proxy (slowest, needs a broker plus WebRTC/ICE negotiation).
     *
     * The rung that actually carries the tunnel is remembered per device, so
     * after one successful connect each SIM starts on its own best rung and the
     * ordering here only matters for the very first attempt.
     *
     * Since 1.2.42 the desktop's whole set ([PsiphonMethods]): «آی‌پی تمیز» (E) between the fronted
     * and the direct rung when there are clean addresses, «کاندوئیت شخصی» (P) when asked for by name,
     * and «پروکسی سرور» (X) in front of the ladder when chosen -- see [buildSessionLadder]. Null when
     * the rung has nothing to work with (no clean address, no pairing).
     */
    private fun psiphonRung(name: String): PsiphonStrategy? = when (name) {
        "A" -> PsiphonStrategy(
            name = "A",
            label = PsiphonMethods.label("A"),
            timeoutSeconds = PsiphonMethods.seconds("A"),
            preferredProtocols = PROTOCOLS_FRONTED,
        ) { config ->
            // Fronted protocols terminate on an Amazon/Cloudflare edge address,
            // never on a Psiphon-owned IP, so a carrier IP blocklist cannot see
            // or drop them. They do need working DNS to resolve the front, which
            // is what the public resolvers on the TUN provide.
            //
            // Only 5 of the 430 bundled server entries advertise FRONTED-MEEK
            // (4x US, 1x GB) — that is why a fronted connection always lands in
            // the US. A low candidate count keeps Psiphon cycling those few
            // entries with fresh dial parameters instead of opening up to the
            // 425 direct entries that are known-dead on this carrier.
            config.put("InitialLimitTunnelProtocols", JSONArray(PROTOCOLS_FRONTED))
            config.put("InitialLimitTunnelProtocolsCandidateCount", 30)
            // A HARD limit as well as the initial preference, so the rung's whole
            // budget is spent on fronted candidates instead of lapsing back to the
            // 425 direct entries that are null-routed on this carrier. Rung D is
            // where direct protocols get their turn.
            config.put("LimitTunnelProtocols", JSONArray(PROTOCOLS_FRONTED))
            config.put("ConnectionWorkerPoolSize", 12)
            // CDN paths are legitimately slower than a direct dial; without this
            // Psiphon abandons them as if they were dead.
            config.put("NetworkLatencyMultiplier", 2.0)
            applyTacticsOverride(config)
        }
        // «آی‌پی تمیز»: rung A's fronted path, dialled at the clean edge addresses found on this line
        // (PsiphonCleanIps) instead of whatever DNS answers. Official config, no fork:
        // DNSResolverPreresolvedIPAddressCIDRs {providerID: [CIDR]} at probability 1.0. Measured on the
        // desktop 2026-10-14: a clean IPv6 Akamai edge connected in 2.5 s where DNS took 8.
        "E" -> PsiphonCleanIps.configCidrs(this).takeIf { it.isNotEmpty() }?.let { cidrs ->
            PsiphonStrategy(
                name = "E",
                label = PsiphonMethods.label("E"),
                timeoutSeconds = PsiphonMethods.seconds("E"),
                preferredProtocols = listOf("FRONTED-MEEK-OSSH"),
                dataRoot = java.io.File(filesDir, "psiphon-clean").absolutePath,
            ) { config ->
                // Only FRONTED-MEEK-OSSH: the HTTP variant dials port 80 (the scan measured 443) and the
                // QUIC one never connected on the desktop's line.
                config.put("LimitTunnelProtocols", JSONArray(listOf("FRONTED-MEEK-OSSH")))
                config.put("InitialLimitTunnelProtocols", JSONArray(listOf("FRONTED-MEEK-OSSH")))
                config.put("InitialLimitTunnelProtocolsCandidateCount", 30)
                config.put("DNSResolverPreresolvedIPAddressCIDRs", JSONObject().apply {
                    cidrs.forEach { (provider, list) -> put(provider, JSONArray(list)) }
                })
                config.put("DNSResolverPreresolvedIPAddressProbability", 1.0)
                config.put("ConnectionWorkerPoolSize", 8)
                config.put("NetworkLatencyMultiplier", 1.5)
                applyTacticsOverride(config)
            }
        }
        "D" -> PsiphonStrategy(
            name = "D",
            label = PsiphonMethods.label("D"),
            timeoutSeconds = PsiphonMethods.seconds("D"),
            preferredProtocols = PROTOCOLS_DIRECT,
        ) { config ->
            // No InitialLimitTunnelProtocols at all: Psiphon uses its own full
            // protocol set and its own replay/tactics ordering. This is the rung
            // that wins on a carrier which is not blocking anything — SamanTel
            // connected this way on QUIC-OSSH — and it is also the safety net if
            // the CDN fronts themselves ever get blocked.
            config.put("ConnectionWorkerPoolSize", 16)
            // Direct dials do not need tactics either, and with tactics on this
            // rung was also being forced onto in-proxy — see applyTacticsOverride.
            applyTacticsOverride(config)
        }
        "C" -> PsiphonStrategy(
            name = "C",
            label = PsiphonMethods.label("C"),
            timeoutSeconds = PsiphonMethods.seconds("C"),
            preferredProtocols = emptyList(),
        ) { config ->
            // In-proxy routes through other Psiphon users' devices over WebRTC.
            // Their addresses are residential and not in any carrier blocklist,
            // which is what makes this rung the last resort that can still work
            // when every server IP and every CDN front is unreachable.
            //
            // Deliberately NOT setting InitialLimitTunnelProtocols here: the
            // INPROXY-* protocol names do not exist as literals in libgojni.so
            // (verified with strings — they are assembled at runtime), so passing
            // one risks failing config validation and killing the whole rung.
            // The flags below are enough; the log confirms Psiphon then reports
            // "in-proxy protocol preferred" and dials INPROXY-WEBRTC-OSSH itself.
            config.put("InproxyEnabled", true)
            config.put("InproxyAllowClient", true)
            config.put("InproxySkipAwaitFullyConnected", true)
            config.put("ConnectionWorkerPoolSize", 16)
            config.put("NetworkLatencyMultiplier", 3.0)
        }
        // «کاندوئیت شخصی»: only through the station(s) of the pairing link. Tactics stay ON: the broker
        // specs in-proxy needs come from them.
        "P" -> PsiphonPairing.read(this)?.let { pairing ->
            PsiphonStrategy(
                name = "P",
                label = PsiphonMethods.label("P"),
                timeoutSeconds = PsiphonMethods.seconds("P"),
                preferredProtocols = emptyList(),
            ) { config ->
                config.put("InproxyEnabled", true)
                config.put("InproxyAllowClient", true)
                config.put("InproxyClientPersonalCompartmentID", pairing.id)
                config.put("NetworkLatencyMultiplier", 3.0)
            }
        }
        else -> null
    }

    /**
     * «پروکسی سرور»: rung D reaching Psiphon THROUGH [up] -- the rung the desktop's real test
     * connected with (fronted meek through a free proxy spent its whole budget first: 69 s against
     * 9 s on D). TCP protocols only, a proxy cannot carry the UDP ones; tactics on; a data folder of
     * its own (see [PsiphonStrategy.dataRoot]).
     */
    private fun upstreamRung(up: PsiphonUpstream.Candidate) = PsiphonStrategy(
        name = "X",
        label = PsiphonMethods.label("X") + " " + up.label,
        timeoutSeconds = PsiphonMethods.seconds("X"),
        preferredProtocols = PROTOCOLS_DIRECT,
        dataRoot = java.io.File(filesDir, "psiphon-upstream").absolutePath,
        upstream = up,
    ) { config ->
        config.put("UpstreamProxyURL", up.url)
        config.put("LimitTunnelProtocols", JSONArray(PROTOCOLS_CHAINABLE))
        config.put("ConnectionWorkerPoolSize", 16)
        config.put("InproxyEnabled", false)
        config.put("InproxyAllowClient", false)
    }

    /**
     * The ladder this session walks, decided once per connect by [buildSessionLadder].
     *
     * Chained runs drop rung C (and P). It is WebRTC, a SOCKS5 upstream cannot carry UDP,
     * and the field log shows the failure precisely: STUN leaving over the carrier
     * instead of the tunnel, 34-second ICE gathering, and an untunneled broker DNS
     * lookup timing out on the link Hamrah-e-Aval null-routes. Keeping it in the
     * list did not merely waste its 75s budget — the *starting* rung was read from
     * the plain run's memory, which could say C, so the very first chained attempt
     * began on the one rung that cannot work and the chainable rungs never got a
     * fair turn. The remembered rung is kept per mode for the same reason.
     */
    @Volatile private var sessionLadder: List<PsiphonStrategy> = emptyList()

    private val activeLadder: List<PsiphonStrategy>
        get() = sessionLadder

    /**
     * The ladder for this connect, as the desktop builds it (psiphon-manager.js `ladderFor` +
     * server.js's «پروکسی سرور»):
     *
     *  - automatic: A, E (only with clean addresses), D, C (not inside WARP); the rung that carried the
     *    last tunnel in this mode moved to the FRONT -- not a rotation: if the network changed, the
     *    rest still run in their order;
     *  - a pinned method: that rung alone; P only when pinned, never automatic (a friend's station may
     *    be off, and the ladder must not wait on it);
     *  - «پروکسی سرور»: one rung per proxy proven seconds ago ([upstream]), then the automatic ladder
     *    with Psiphon's own ways in.
     *
     * Throws with a sentence for the user when the pinned method has nothing to work with.
     */
    private fun buildSessionLadder(pinned: String, upstream: List<PsiphonUpstream.Candidate>): List<PsiphonStrategy> {
        val chained = chainMode
        if (pinned in listOf("A", "E", "D", "C", "P")) {
            if (chained && (pinned == "C" || pinned == "P")) {
                error(com.mlmvpn.scanner.store.tr("«${PsiphonMethods.label(pinned)}» داخل وارپ کار نمی‌کند — روش دیگری انتخاب کنید یا لایهٔ بیرونی را «مستقیم» کنید.",
                    "«${PsiphonMethods.label(pinned)}» does not work inside WARP -- pick another method, or set the outer layer to «direct»."))
            }
            return listOf(psiphonRung(pinned) ?: error(when (pinned) {
                "E" -> com.mlmvpn.scanner.store.tr("هنوز آی‌پی تمیزی نیست — در بخش «آی‌پی تمیز» اسکن کنید یا آی‌پی‌های خودتان را وارد کنید.",
                    "There is no clean IP yet -- scan in «Clean IP» or add your own.")
                else -> com.mlmvpn.scanner.store.tr("لینک جفت‌سازی کاندوئیت وارد نشده — در بخش «کاندوئیت» واردش کنید.",
                    "No Conduit pairing link yet -- add one in «Conduit».")
            }))
        }
        var auto = PsiphonMethods.LADDER_ORDER
            .filter { it != "P" && !(chained && it == "C") }
            .mapNotNull { psiphonRung(it) }
        PsiphonMethods.remembered(this, chained)?.let { last ->
            val i = auto.indexOfFirst { it.name == last }
            if (i > 0) auto = listOf(auto[i]) + auto.filterIndexed { k, _ -> k != i }
        }
        return upstream.map { upstreamRung(it) } + auto
    }

    /**
     * The countries that have at least one domain-fronted server in the bundled list.
     *
     * Read out of `server_entries.txt` rather than hardcoded, because the list is refreshed from
     * Psiphon's published one and a hardcoded set would silently go stale the first time it grew.
     * Parsed once and kept: 493 hex lines is a few milliseconds, and it is asked for on every
     * connect that has a country preference.
     *
     * A record is `0 0 0 0 {json}` hex-encoded, so the region and capabilities can be read without
     * unpacking anything else.
     */
    private val frontedRegions: Set<String> by lazy {
        runCatching {
            com.mlmvpn.scanner.store.StoreFiles.open(this, "server_entries.txt").bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val hex = line.trim()
                    if (hex.length < 32) return@mapNotNull null
                    val text = runCatching {
                        String(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Charsets.UTF_8)
                    }.getOrNull() ?: return@mapNotNull null
                    val json = text.substringAfter('{', "").let { if (it.isEmpty()) null else "{$it" }
                        ?: return@mapNotNull null
                    val entry = runCatching { JSONObject(json) }.getOrNull() ?: return@mapNotNull null
                    val caps = entry.optJSONArray("capabilities") ?: return@mapNotNull null
                    val fronted = (0 until caps.length()).any {
                        caps.optString(it).startsWith("FRONTED-MEEK")
                    }
                    if (fronted) entry.optString("region").takeIf { it.isNotBlank() } else null
                }.toSet()
            }
        }.getOrElse { emptySet() }.also {
            ConnectionLog.record("Countries with a fronted server: ${it.sorted().joinToString(",")}")
        }
    }

    companion object {
        const val LOG_TAG = "TunnelVpnService"

        /**
         * Default MTU for the native core's own tun (MASQUE, WireGuard, WARP-in-WARP).
         *
         * 1280 and not the 1500 the tun2socks paths use, because these three wrap every inner
         * packet in QUIC: a short header, an encryption tag, a datagram frame header and the
         * outer UDP and IP headers add roughly 80 bytes, which puts a full-size packet exactly
         * at the Ethernet ceiling with no headroom. QUIC sets "do not fragment", so on any path
         * that carries less -- ADSL/VDSL at 1492, most mobile carriers at 1400-1450 -- the whole
         * packet is dropped rather than split, and the tunnel reads as "connects but lags". 1280
         * is the IPv6 minimum MTU, deliverable on every path there is. [MyVpnService] caps the
         * Aether tun the same way and for the same reason.
         */
        const val NATIVE_TUN_MTU = 1280

        /**
         * The smallest MTU an interface may carry an IPv6 address on.
         *
         * RFC 8200's minimum link MTU, and not advisory: the kernel refuses the address, and the
         * VpnService.Builder call then fails with "Cannot set address". See
         * [applyTunnelAddresses].
         */
        const val IPV6_MIN_MTU = 1280
        const val ACTION_CONNECT = "com.mlmvpn.core.tunnel.CONNECT"
        const val ACTION_DISCONNECT = "com.mlmvpn.core.tunnel.DISCONNECT"
        const val ACTION_RECONNECT = "com.mlmvpn.core.tunnel.RECONNECT"

        /**
         * «گف»: a setting changed (exit, filters, direct, DNS...). Only the engine restarts; the TUN
         * stays up, so a change applies in seconds without the device ever touching the bare link.
         */
        const val ACTION_GEPH_APPLY = "com.mlmvpn.core.tunnel.GEPH_APPLY"

        /** Measure every country that has a fronted server. See [benchmarkRegions]. */
        const val ACTION_BENCH_REGIONS = "com.mlmvpn.core.tunnel.BENCH_REGIONS"

        /**
         * How long one country gets to come up before it is written off.
         *
         * Measured connects on a working country came in at 5s, 7.5s, 8s, 10.5s and 17s. 20s
         * clears all but the slowest of those, and it is the ceiling that sets the running time:
         * every country that cannot answer costs exactly this much, and the list is now every
         * country rather than six. It was 40s, which spent twenty seconds of each dead country
         * waiting past the point where a live one would have answered.
         */
        private const val BENCH_CONNECT_TIMEOUT_MS = 20_000L

        /**
         * How many servers of one country the second pass will try.
         *
         * Spain has ten in the bundled list and Australia eight; at twenty seconds each, an
         * uncapped pass over a handful of countries is most of an hour. Six is enough to find a
         * good one and keeps the whole run inside a few minutes.
         */
        private const val BENCH_MAX_SERVERS_PER_COUNTRY = 6
        const val ACTION_NOTIFICATION_HEALTH = "com.mlmvpn.core.tunnel.NOTIFICATION_HEALTH"
        const val ACTION_STATUS = "com.mlmvpn.core.tunnel.STATUS"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_STATUS = "status"
        const val EXTRA_DETAIL = "detail"
        /** Which transport the running tunnel is built on, so the UI can say so. */
        const val EXTRA_PROTOCOL = "protocol"
        const val EXTRA_CONNECTED_AT = "connected_at"
        const val EXTRA_COUNTRY = "country"

        /**
         * The same country as a two-letter code, when one is known.
         *
         * Separate from [EXTRA_COUNTRY] because that one carries whatever shape the source
         * produced -- Psiphon names its region in words -- and a flag and a translated name
         * both need the code.
         */
        const val EXTRA_COUNTRY_CODE = "country_code"
        const val EXTRA_TRAFFIC_TX = "traffic_tx"
        const val EXTRA_TRAFFIC_RX = "traffic_rx"
        const val EXTRA_TRAFFIC_SPEED_TX = "traffic_speed_tx"
        const val EXTRA_TRAFFIC_SPEED_RX = "traffic_speed_rx"
        const val EXTRA_TRAFFIC_MONTH_TX = "traffic_month_tx"
        const val EXTRA_TRAFFIC_MONTH_RX = "traffic_month_rx"
        const val EXTRA_NOTIFICATION_IP = "notification_ip"
        const val EXTRA_NOTIFICATION_PING = "notification_ping"

        /**
         * Country the tunnel exits in, for the notification's second line.
         *
         * Sent by the activity once its geolocation lookup lands, because that
         * lookup is a property of the address rather than of the route and the
         * service has no reason to duplicate it. Psiphon also fills this in
         * directly from `onConnectedServerRegion`, which is authoritative and
         * arrives earlier.
         */
        const val EXTRA_NOTIFICATION_COUNTRY = "notification_country"

        /**
         * 0..100 while connecting, or -1 when this status carries no measurable
         * progress.
         *
         * Deliberately not a fabricated animation: every value published here is
         * a real milestone the service has actually reached (see
         * [publishProgress]), and for Tor it is the bootstrap percentage Tor
         * itself reports. A progress bar that moves on a timer while nothing
         * happens is worse than no progress bar.
         */
        const val EXTRA_PROGRESS = "progress"

        /**
         * The WARP member the user asked for, when the ladder handed this connect to another one
         * (blank otherwise). Its screen owns the session and says what happened.
         */
        const val EXTRA_LADDER_FROM = "ladder_from"

        /**
         * Accent used for the notification's icon tint and header text.
         *
         * Sampled from the launcher artwork's neon ring (#70E0B0 region), so the
         * shade row and the app icon read as the same brand.
         */
        private const val NOTIFICATION_ACCENT = 0xFF70E0B0.toInt()

        /** Where the home header's live up/down readout polls its figures from. */
        private const val SESSION_TRAFFIC_PREFS = "vpn_session_traffic"

        /** Preference key for the auto-reconnect toggle. */
        const val AUTO_RECONNECT_PREF = "auto_reconnect"

        /**
         * A unique-local address used only to take the IPv6 route.
         *
         * fd00::/8 is reserved for private use and is never routable on the
         * public internet, so nothing can be reached through it - which is the
         * point. See applyTunnelAddresses.
         */
        private const val IPV6_BLACKHOLE = "fd00:6e64:6f63::1"

        /** Where the last config is kept so an always-on start can reuse it. */
        private const val LAST_CONFIG_PREF = "last_config"

        /**
         * Auto-reconnect is ON by default.
         *
         * The standing requirement for this app is one-click connect on a hostile
         * network. A tunnel that dies at 3am and stays dead until the user notices
         * their apps are offline is the same failure as not connecting at all, so
         * recovery is not an opt-in feature. An explicit "off" from the user is
         * still honoured — the key is written on every toggle.
         */
        const val AUTO_RECONNECT_DEFAULT = true

        /** How often the liveness watchdog checks an established tunnel. */
        private const val WATCHDOG_INTERVAL_S = 30L

        /** «گف»: the protocol value [com.mlmvpn.scanner.ui.tunnel.Transport.GEPH] sends. */
        private const val GEPH_PROTOCOL = "GEPH"

        /**
         * How long a Geph connect may take before it is called failed. A cold one -- no cache, the
         * broker reached through its fronts, a proof of the account, a route and six sessions --
         * measured 25 s on the desktop; a blocked broker adds its 15 s timeouts on top.
         */
        private const val GEPH_CONNECT_TIMEOUT_MS = 120_000L

        /** Engine restarts in a row (TUN kept) before the session is handed to the reconnect ladder. */
        private const val GEPH_MAX_RESTARTS = 4

        /** «وارپ»: the protocol value [com.mlmvpn.scanner.ui.tunnel.Transport.CFWG] sends. */
        private const val CFWG_PROTOCOL = "CFWG"

        /** A pinned NAT64 gateway carries within seconds or not at all (see startCfWarpVerified). */
        private const val NAT64_GATEWAY_BUDGET_MS = 30_000L

        /** MASQUE / WireGuard on a pinned gateway: identity, handshake and data-plane check fit. */
        private const val NAT64_NATIVE_BUDGET_MS = 45_000L

        /** A pinned gateway that came up has this long to bring something back (see watchNat64Silence). */
        private const val NAT64_SILENT_MS = 15_000L

        /** Owners of the raw WARP ports this service publishes to WarpVia (measurements ride them). */
        private const val WARP_VIA_TUNNEL = "tunnel"
        private const val WARP_VIA_CFWARP = "cfwarp"

        /** The ladder's extra way: MASQUE started on HTTP/2 (see warpLadderNext), and the mark that asks for it. */
        private const val MASQUE_ON_H2 = "masque-h2"
        private const val MASQUE_TRIED_H3 = "masque@h3"

        /** How many of a country's gateways one connect tries before it gives up. */
        private const val NAT64_MAX_GATEWAYS = 4

        /** Time for «وارپ» to find an endpoint that really carries traffic; a full rescan fits. */
        private const val CFWG_CONNECT_BUDGET_MS = 150_000L

        /**
         * How often «وارپ» re-proves itself. Endpoints here go stale in about a minute (measured on
         * the desktop), so the proof is repeated rather than trusted.
         */
        private const val CFWG_VERIFY_EVERY_S = 45L

        /** With the screen off, only every this-many-th proof is made (see [startCfWarpMonitor]). */
        private const val CFWG_VERIFY_SCREEN_OFF_EVERY = 4L

        /** Dead endpoints in a row that «وارپ» replaces on its own before the reconnect ladder takes over. */
        private const val CFWG_MAX_DEAD = 4

        /** «لنترن»: the protocol value [com.mlmvpn.scanner.ui.tunnel.Transport.LANTERN] sends. */
        private const val LANTERN_PROTOCOL = "LANTERN"

        /** «URnetwork»: the protocol value [com.mlmvpn.scanner.ui.tunnel.Transport.URNETWORK] sends. */
        private const val URNETWORK_PROTOCOL = "URNETWORK"

        /**
         * A kept list: the core listens in well under two seconds (desktop: 0.67 s). Twenty is for the
         * one slow case left -- the first start after the Iran rule-sets expire, when sing-box waits up
         * to ten seconds for their download before it opens the port.
         */
        private const val LANTERN_WARM_LISTEN_MS = 20_000L

        /**
         * A first list: a first list came in about three seconds wherever Lantern's config service was
         * reachable, so twenty seconds of nothing is not slowness -- from then on it goes through WARP.
         */
        private const val LANTERN_COLD_DIRECT_MS = 20_000L

        /** A first list, all told: the desktop's four minutes. */
        private const val LANTERN_COLD_GIVEUP_MS = 240_000L

        /** From listening to a proven data path: 1.5–3 s on the desktop; the margin is the core's first server tests. */
        private const val LANTERN_DATA_TIMEOUT_MS = 45_000L

        /** How often «لنترن» re-proves its data path once connected. */
        private const val LANTERN_VERIFY_EVERY_S = 45L

        /** Core restarts in a row (TUN kept) before the session is handed to the reconnect ladder. */
        private const val LANTERN_MAX_DEAD = 4

        /** «URnetwork»: the core's port and the TUN's addresses (the first start registers this phone). */
        private const val URNETWORK_READY_MS = 100_000L
        /**
         * From the window opening to its first provider. The SDK keeps filling it the whole time;
         * on a line where URnetwork's server answers only now and then the first one can take two
         * minutes (measured on the owner's Wi-Fi, 2026-10-19).
         */
        private const val URNETWORK_PROVIDERS_MS = 150_000L
        /** From the first provider to a proven data path. */
        private const val URNETWORK_DATA_MS = 60_000L
        /** How often «URnetwork» re-proves its data path once connected. */
        private const val URNETWORK_VERIFY_EVERY_S = 45L
        /**
         * A data path dead this long in spite of the SDK's own recovery gets a new core -- a last
         * resort against a stuck process; a server out of reach for a minute or two is the SDK's to
         * ride out with its providers held, as the official app leaves it.
         */
        private const val URNETWORK_STUCK_MS = 10 * 60_000L
        /** Core restarts in a row (TUN kept) before the session is handed to the reconnect ladder. */
        private const val URNETWORK_MAX_DEAD = 4
        /**
         * The TUN sizes the SDK takes: never below the largest packet a provider sends (1100), never
         * above its packet loop's read buffer (2048, less headroom).
         */
        private const val URNETWORK_MIN_MTU = 1100
        private const val URNETWORK_MAX_MTU = 2000

        /** Auto-reconnect backoff in seconds; the last entry repeats forever. */
        private val RECONNECT_BACKOFF_S = longArrayOf(5, 15, 30, 60, 120)
        /** Exit address measured by the core from inside the tunnel. */
        const val EXTRA_EXIT_IP = "exit_ip"
        const val STATUS_CONNECTING = "connecting"
        const val STATUS_STARTING = "starting"
        const val STATUS_SCANNING = "scanning"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_DISCONNECTED = "disconnected"
        const val STATUS_FAILED = "failed"
        /**
         * Deliberately a new id, not a rename.
         *
         * A channel's lock-screen visibility is fixed at creation: after the first
         * `createNotificationChannel` call Android ignores every later change to
         * that field, so patching the old channel in place would have shipped a
         * build where the code says PUBLIC and the device still behaves as before
         * for everyone who already had the app installed. A fresh id is created
         * fresh, with the new visibility, and [CHANNEL_ID_LEGACY] is deleted so the
         * user is not left with two VPN rows in notification settings.
         */
        const val CHANNEL_ID = "vpn_channel_v2"

        /** The pre-1.4.0 channel, deleted on first foreground start. */
        private const val CHANNEL_ID_LEGACY = "vpn_channel"

        /**
         * Marks a config as Psiphon-over-WARP.
         *
         * Deliberately not a `protocol` value the core knows: the core never sees
         * this string. `startTunnel` reads it, runs the two legs itself (core in
         * SOCKS mode + the Psiphon Go library on top), and hands each leg the
         * protocol name it actually understands.
         */
        const val CHAIN_PROTOCOL_MARKER = "PSIPHON-OVER-WARP"

        /**
         * How long to wait for a rejected outer leg to actually stop.
         *
         * the core's stop call only raises a flag; the core's RUNNING guard clears when the
         * tunnel task unwinds. Starting the next rung before then gets "tunnel
         * already running" and fails it for the wrong reason.
         */
        private const val OUTER_STOP_GRACE_MS = 6_000L

        /**
         * How long a freshly started outer rung has to raise the core's RUNNING flag.
         *
         * `startProxy` is spawned on its own thread, so for a moment after the call
         * the core is legitimately not running yet. Without this grace the readiness
         * loop would read that gap as "the rung died" and reject every transport
         * instantly.
         */
        private const val OUTER_START_GRACE_MS = 3_000L

        /**
         * How long Proxy Mode waits for the core to become ready.
         *
         * Longer than any single rung of the chain ladder gets, because there is no
         * ladder here: the user picked this transport, so the only thing a shorter
         * budget could buy is failing on a slow network that would have connected.
         */
        private const val NATIVE_PROXY_BUDGET_MS = 60_000L

        /** On top of the WARP ladder's budget when Proxy Mode has to hunt: validating the endpoint found. */
        private const val PROXY_HUNT_EXTRA_MS = 40_000L

        /**
         * Budget for the preferred-country attempt that runs in front of the ladder.
         *
         * Deliberately short. `EgressRegion` is a hard filter, so this attempt runs
         * against one country's servers only — if that country is reachable it
         * answers quickly, and if it is not, every extra second is time stolen from
         * the ladder that will actually connect. 25s covers a fronted CDN handshake
         * (measured at 33s on the worst operator *without* a filter, where the win
         * came from a rung with a 60s budget) while keeping the worst case for a
         * wrong country to roughly half a minute.
         */
        private const val REGION_PHASE_TIMEOUT_SECONDS = 25

        const val NOTIFICATION_ID = 1
        const val TRAFFIC_PREFS = "traffic_stats"
        const val TRAFFIC_MONTH = "month"
        const val TRAFFIC_TX = "tx"
        const val TRAFFIC_RX = "rx"

        /**
         * Schema version of [TRAFFIC_PREFS], bumped when stored totals become
         * untrustworthy and have to be discarded rather than migrated.
         *
         * 1 = totals written before the monthly-inflation fix.
         */
        const val TRAFFIC_SCHEMA = "schema"
        const val TRAFFIC_SCHEMA_VERSION = 1

        /**
         * How often the monthly traffic counters are written to disk while a
         * tunnel is up. Teardown always flushes, so this only bounds what a hard
         * process kill can lose.
         */
        private const val TRAFFIC_FLUSH_MS = 60_000L

        /**
         * In-tunnel RX bytes that count as "this transport really works".
         *
         * Mirrors `MainActivity.VERIFY_MIN_RX_BYTES` on purpose — the two answer the
         * same question ("did real payload arrive?") and drifting apart would let one
         * of them accept a tunnel the other rejects. Not shared as one constant
         * because the activity's copy also documents the verification gate's own
         * retry loop; if either changes, change both.
         */
        private const val VERIFIED_RX_BYTES = 4_096L


        /**
         * elapsedRealtime at the moment the tunnel last reached CONNECTED, or 0
         * when it is down. The activity reads this so a session timer survives
         * the UI being destroyed and recreated (rotation, screen off, returning
         * from Recents) instead of restarting from zero on every rebind.
         *
         * Volatile and static because the service and the activity are different
         * lifecycles in the same process; it is a plain timestamp, so a stale
         * read is harmless.
         */
        @Volatile
        private var connectedSince = 0L

        fun connectedSinceElapsed(): Long = connectedSince

        /**
         * The exit address the core last measured from inside the tunnel, or "".
         *
         * Static for the same reason as [connectedSince], but it fixes a sharper
         * bug. The core measures the exit exactly once per tunnel, about a second
         * after the first byte crosses (`ExitProbe` in tun.rs goes to `Done` and
         * stops ticking — deliberately, since waking the loop forever costs
         * battery). That measurement is announced with a single broadcast.
         *
         * Connect from the Quick Settings tile and there is no activity alive at
         * that moment, and the receiver is registered in `onStart` rather than in
         * the manifest — so the broadcast reaches nobody and the only measurement
         * of the session is lost. Opening the app afterwards then finds its own
         * `coreExitIp` empty and, correctly refusing to ask over the carrier link
         * (we are excluded from our own TUN, so the answer would be the carrier's
         * address), sits on "measuring…" for the rest of the session.
         *
         * The service outlives the UI, so it keeps the answer here and the
         * activity reads it on start. The notification already showed this value
         * while the card claimed to be measuring; now both read the same source.
         */
        @Volatile
        private var lastExitIp = ""

        fun lastMeasuredExitIp(): String = lastExitIp
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun bindToDevice(fd: Long) {
        if (!protect(fd.toInt())) {
            throw PsiphonTunnel.Exception("protect(fd=$fd) failed")
        }
    }

    override fun onListeningSocksProxyPort(port: Int) {
        activeSocksPort = port
        ConnectionLog.record("Psiphon SOCKS proxy listening on port $port")
        if (CoreConfig.lanSharingEnabled(this)) {
            val host = CoreConfig.localNetworkAddress(this)
            if (host != null) {
                ConnectionLog.record("LAN sharing: SOCKS5 at $host:$port")
            } else {
                ConnectionLog.record(
                    "LAN sharing is on but this device has no local network address — " +
                        "turn on the hotspot or join a Wi-Fi network"
                )
                // The inputs, not just the verdict. Two builds in a row got this
                // answer wrong and the only evidence was a screenshot of the row.
                ConnectionLog.record("LAN survey: " + CoreConfig.describeLocalNetworks(this))
            }
        }
    }

    /**
     * Only fires when LAN sharing wrote LocalHttpProxyPort — see buildPsiphonConfig.
     * Logged with the address because that is what the user has to type on the other
     * device, and an "it's on" message they cannot act on is worthless.
     */
    override fun onListeningHttpProxyPort(port: Int) {
        val host = CoreConfig.localNetworkAddress(this)
        if (host != null) {
            ConnectionLog.record("LAN sharing: HTTP proxy at $host:$port")
        } else {
            ConnectionLog.record("LAN sharing: HTTP proxy listening on port $port")
        }
    }

    override fun onConnecting() {
        ConnectionLog.record("Psiphon connecting")
        // After a tunnel carried traffic, «connecting» is a DROP: the number that answers «پایدار
        // نیست» -- a session with none is a different problem from a session with nine.
        if (psiphonVpnActivated && connected.get() && psiphonDroppedAt == 0L) {
            psiphonDroppedAt = System.currentTimeMillis()
            PsiphonLive.update { it.copy(drops = it.drops + 1, droppedAt = psiphonDroppedAt, connected = false) }
            ConnectionLog.record("Psiphon lost its tunnel; the core is re-establishing it")
        }
    }

    override fun onServerAlert(reason: String?, subject: String?, actionURLs: MutableList<String>?) {
        ConnectionLog.record("Psiphon server alert: ${reason ?: "?"} ${subject ?: ""}")
        PsiphonLive.update { it.copy(note = com.mlmvpn.scanner.store.tr("هشدار سرور: ", "Server alert: ") + (reason ?: "?")) }
    }

    override fun onUpstreamProxyError(message: String?) {
        ConnectionLog.record("Psiphon upstream proxy error: ${message?.take(200) ?: "?"}")
    }

    override fun onSocksProxyPortInUse(port: Int) {
        ConnectionLog.record("Psiphon SOCKS port $port is in use by something else — this attempt cannot listen there")
    }

    override fun onConnected() {
        ConnectionLog.record("Psiphon connected — upstream tunnel ready")
        // A tunnel exists: disarm the watchdog so it cannot tear down a working
        // connection.
        ladderActive.set(false)
        cancelLadderTimer()
        ladderAttempts = 0
        // The preferred-country attempt succeeded, or we were already past it.
        // Either way the phase is over: Psiphon's NetworkMonitor can restart the
        // controller on its own, and leaving the flag set would re-arm a filtered
        // candidate pool on a tunnel that is already working.
        val inRegionPhase = regionPhase
        if (regionPhase) {
            regionPhase = false
            ConnectionLog.record(
                "Connected in preferred country ${PsiphonRegions.name(regionPhaseTried)}"
            )
        }

        // Attribution is NOT done here. The ActiveTunnel notice that names the
        // protocol arrives *after* this callback — both field logs show it one
        // line below "Psiphon connected" — so at this point activeTunnelProtocol
        // is still empty and any decision would be a guess. See
        // scheduleLadderAttribution() for the deferred, evidence-based version.
        scheduleLadderAttribution()

        val port = activeSocksPort
        if (port <= 0) {
            failAndStop("Psiphon SOCKS port unavailable")
            return
        }
        val socksProxy = "127.0.0.1:$port"

        if (!psiphonVpnMode) {
            // PROXY MODE: just expose the SOCKS port — no TUN needed.
            ConnectionLog.record("Psiphon SOCKS proxy ready at $socksProxy")
            sendStatus(STATUS_CONNECTED)
            return
        }

        // VPN MODE: TUN is already up (created in startTunnel() before Psiphon
        // started). tun2socks keeps running across Psiphon rotations — the SOCKS
        // port is fixed, so a rotation only breaks in-flight upstream sockets and
        // lwIP resets those individual flows while the TUN device stays up.
        if (psiphonVpnActivated && Tun2SocksManager.isRunning) {
            ConnectionLog.record("Psiphon reconnected — tun2socks still routing, nothing to do")
            if (psiphonDroppedAt > 0L) {
                val gap = ((System.currentTimeMillis() - psiphonDroppedAt) / 1000).toInt()
                psiphonDroppedAt = 0L
                ConnectionLog.record("Psiphon tunnel back after ${gap}s")
                PsiphonLive.update { it.copy(connected = true, droppedAt = 0L, lastGapSeconds = gap) }
            }
            sendStatus(STATUS_CONNECTED)
            return
        }

        // Before anyone's traffic is routed through it: what this server is worth. The desktop
        // found a session that connected in five seconds, never dropped once and delivered
        // 0.10 Mbit/s -- every check said success while nothing the user opened would load, and the
        // core replays that server for ever. Not through «پروکسی سرور» (there the proxy is the
        // narrow part, so every server measures the same and the search only repeats whole
        // connects) and not while the country benchmark measures its own way.
        val rung = activeLadder.getOrNull(ladderIndex)
        if (!psiphonQualityDone && benchRegion == null && rung != null && rung.upstream == null) {
            val gen = ++psiphonQualityGen
            val viaRegion = inRegionPhase
            runOnWorker { psiphonQualityCheck(gen, port, viaRegion) }
            return
        }
        activatePsiphonRouting(port)
    }

    /** tun2socks onto Psiphon's port: the device is routed, and only now is it CONNECTED. */
    private fun activatePsiphonRouting(port: Int) {
        val tunFd = tun
        if (tunFd == null) {
            failAndStop("VPN interface missing")
            return
        }

        if (!Tun2SocksManager.start(tunFd, port, mtu = tunMtu(mtuMethod()))) {
            failAndStop("Could not start whole-device routing")
            return
        }
        psiphonVpnActivated = true
        ConnectionLog.record("Whole-device routing active via tun2socks → 127.0.0.1:$port")
        connected.set(true)
        PsiphonLive.update { it.copy(connected = true, measuring = false) }
        // Replace the placeholder "Connecting..." notification immediately. It used
        // to be overwritten by the first traffic sample from the Rust core; with
        // tun2socks the first sample can be seconds away, so the notification
        // would sit on "Connecting..." while the device was fully tunnelled.
        repostNotification()
        sendStatus(STATUS_CONNECTED)
        startWatchdog()
    }

    /**
     * The desktop's slow-server search: measure, and while the server is under
     * [PsiphonQuality.MIN_GOOD_MBIT] draw another one -- the replay dropped, so the next dial cannot
     * come back to it -- up to [PsiphonQuality.MAX_SERVER_ATTEMPTS] servers per rung, then keep the
     * last one and say so. A SEARCH, not one blind swap: a redial is a fresh draw from a small pool
     * and is not better by construction (desktop: 0.88 rejected, 0.69 taken in its place).
     *
     * A measurement where nothing came back at all is not «slow»: that is the dead-path case, and
     * redialling would only spend ten seconds proving it again -- routing goes ahead and the
     * watchdog judges it.
     */
    private fun psiphonQualityCheck(gen: Int, port: Int, viaRegion: Boolean) {
        if (stopRequested.get() || gen != psiphonQualityGen) return
        sendStatus(STATUS_CONNECTING, tr("وصل شد — سنجش ظرفیت این سرور…", "Connected -- measuring this server's capacity…"))
        PsiphonLive.update { it.copy(measuring = true, attempt = psiphonQualityAttempts) }
        val r = PsiphonQuality.measure(port)
        PsiphonLive.update { it.copy(measuring = false, mbit = r.mbit) }
        ConnectionLog.record(
            "Psiphon capacity: ${r.mbit} Mbit/s with ${PsiphonQuality.STREAMS} streams " +
                "(${r.okStreams} brought bytes, ${r.bytes} bytes in ${r.ms} ms) — server ${psiphonQualityAttempts} of ${PsiphonQuality.MAX_SERVER_ATTEMPTS}"
        )
        if (stopRequested.get() || gen != psiphonQualityGen) return
        val slow = r.okStreams > 0 && r.mbit < PsiphonQuality.MIN_GOOD_MBIT
        if (slow && psiphonQualityAttempts < PsiphonQuality.MAX_SERVER_ATTEMPTS) {
            psiphonQualityAttempts++
            val note = tr(
                "این سرور فقط ${faDigits(r.mbit)} مگابیت می‌دهد — سرور دیگری امتحان می‌شود (${faDigits(psiphonQualityAttempts)} از ${faDigits(PsiphonQuality.MAX_SERVER_ATTEMPTS)})",
                "This server gives only ${r.mbit} Mbit/s -- trying another (${psiphonQualityAttempts} of ${PsiphonQuality.MAX_SERVER_ATTEMPTS})",
            )
            ConnectionLog.record("Slow server (${r.mbit} Mbit/s) — drawing another, ${psiphonQualityAttempts} of ${PsiphonQuality.MAX_SERVER_ATTEMPTS}")
            PsiphonLive.update { it.copy(note = note) }
            sendStatus(STATUS_CONNECTING, note)
            // ORDER IS EVERYTHING HERE: the controller stops (and lets go of its datastore) before the
            // datastore is removed, and the next dial starts on a fresh one.
            try { psiphonTunnel?.stop() } catch (_: Exception) {}
            psiphonTunnel = null
            try { Thread.sleep(1200) } catch (_: InterruptedException) { return }
            if (stopRequested.get() || gen != psiphonQualityGen) return
            dropReplayState(activeLadder.getOrNull(ladderIndex)?.dataRoot)
            // The same country again when that is where the first server came from.
            if (viaRegion && CoreConfig.egressRegion(this) != null) regionPhase = true
            activeSocksPort = CoreConfig.socksPort(this)
            startPsiphonTunnel()
            return
        }
        if (slow) {
            ConnectionLog.record("After ${psiphonQualityAttempts} servers the best this line gave was ${r.mbit} Mbit/s — keeping it")
            PsiphonLive.update {
                it.copy(note = tr("بعد از ${faDigits(psiphonQualityAttempts)} سرور، بهترین چیزی که این خط داد ${faDigits(r.mbit)} مگابیت بود",
                    "After ${psiphonQualityAttempts} servers the best this line gave was ${r.mbit} Mbit/s"))
            }
        }
        psiphonQualityDone = true
        activatePsiphonRouting(port)
    }

    override fun onExiting() {
        ConnectionLog.record("Psiphon exiting")
        psiphonTunnel = null
        // Psiphon hit its own EstablishTunnelTimeout and shut the controller down.
        // That is the definitive "this rung is dead" signal, and it arrives before
        // our watchdog's grace period expires — so escalate now instead of leaving
        // the user staring at a stalled spinner for another 8 seconds.
        // Guarded: a user-initiated stop also lands here, and so does a teardown
        // that follows a successful connection.
        if (!stopRequested.get() && !psiphonVpnActivated && ladderActive.get()) {
            escalateLadder()
        }
        // The controller exiting *after* a session was established is the silent
        // death the field report describes: Psiphon is gone, tun2socks keeps
        // routing into nothing, and without this the app would sit there claiming
        // to be connected. Handled here rather than waiting up to 30s for the
        // watchdog tick, because this callback is the definitive signal.
        if (!stopRequested.get() && !userInitiatedStop.get() &&
            psiphonVpnActivated && connected.get()
        ) {
            onTunnelLost("the Psiphon tunnel stopped")
        }
    }

    override fun onClientAddress(address: String?) {
        if (!address.isNullOrBlank()) {
            ConnectionLog.record("Psiphon exit IP: $address")
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putString("last_ip", address).apply()
        }
    }

    override fun onHomepage(homepage: String?) {
        ConnectionLog.record("Psiphon homepage: ${homepage ?: "—"}")
    }

    override fun onClientRegion(region: String?) {
        if (!region.isNullOrBlank()) ConnectionLog.record("Psiphon region: $region")
    }

    /**
     * Psiphon's live list of countries it can currently egress from.
     *
     * Cached because it is the only authoritative source: the 430 embedded server
     * entries age, and the picker must not offer a country the network cannot
     * actually reach. Arrives on every handshake, tunnel or not.
     */
    override fun onAvailableEgressRegions(regions: MutableList<String>?) {
        val list = regions?.filterNotNull().orEmpty()
        if (list.isEmpty()) return
        PsiphonRegions.remember(this, list)
        ConnectionLog.record("Psiphon egress countries available: ${list.size}")
    }

    /**
     * Which country the established tunnel actually exits in.
     *
     * Logged next to the preference so a mismatch is visible: with the country
     * treated as a preference rather than a pin, exiting somewhere else is the
     * expected outcome of a failed region phase, not a bug.
     */
    override fun onConnectedServerRegion(region: String?) {
        if (region.isNullOrBlank()) return
        val wanted = CoreConfig.egressRegion(this)
        val exitedIn = PsiphonRegions.name(region)
        ConnectionLog.record(
            if (wanted == null || wanted == region.uppercase()) {
                "Exit country: $exitedIn ($region)"
            } else {
                "Exit country: $exitedIn ($region) — ${PsiphonRegions.name(wanted)} was preferred but unavailable"
            }
        )
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putString("last_exit_region", region.uppercase()).apply()
        // Psiphon knows its own egress country, which is both earlier and more
        // reliable than the activity's geolocation lookup. One repost, on a
        // change the user can see.
        if (currentCountry != exitedIn) {
            currentCountry = exitedIn
            currentCountryCode = region.trim().uppercase()
            repostNotification()
            // And tell the screens. The country rides on every status broadcast, but by the
            // time Psiphon names its egress region the CONNECTED broadcast has already gone
            // out and no further one is due -- so without this repost the notification knew
            // the country and the app did not, for the whole session.
            republishStatus()
        }
    }

    override fun onBytesTransferred(sent: Long, received: Long) {
        // In VPN mode there is no Rust core in the data path anymore, so Psiphon's
        // own byte counters are the source of traffic stats. These arrive as
        // deltas, not totals.
        if (!psiphonVpnMode) return
        currentTx += sent
        currentRx += received
        updateTrafficNotification(currentTx, currentRx)
    }

    override fun onDiagnosticMessage(message: String) {
        ConnectionLog.record("Psiphon: $message")
        // Capture the protocol that actually carried the tunnel.
        //
        // InitialLimitTunnelProtocols is a preference, not a constraint: once the
        // candidate budget is spent Psiphon reverts to its full protocol set. Both
        // reported field logs proved this — Hamrah-e-Aval ended on
        // FRONTED-MEEK-OSSH (rung A's protocol) while rung B was active, and
        // SamanTel ended on plain OSSH with an in-proxy broker (rung C's mechanism)
        // while rung B was active. Attributing the win to the active rung was
        // therefore wrong in both cases, and persisting that wrong rung meant the
        // next connect started from a strategy that had not actually worked.
        if (message.startsWith("ActiveTunnel:")) {
            runCatching {
                val protocol = JSONObject(message.substringAfter("ActiveTunnel:").trim())
                    .optString("protocol")
                if (protocol.isNotBlank()) {
                    activeTunnelProtocol = protocol
                    PsiphonLive.update { it.copy(protocol = protocol) }
                }
            }
        }
        // WHICH server, how far down the candidate list, and -- on «آی‌پی تمیز» -- which clean
        // address carried it: proven by a real tunnel, so that address goes to the top of the list.
        // The candidate number answers «چرا کند است»: the first server to answer keeps the tunnel
        // however slow it turns out to be (desktop, 2026-09-19: candidate 0 of a pool of 16).
        if (message.startsWith("ConnectedServer:")) {
            runCatching {
                val d = JSONObject(message.substringAfter("ConnectedServer:").trim())
                PsiphonLive.update {
                    it.copy(
                        candidate = if (d.has("candidateNumber")) d.optInt("candidateNumber") else it.candidate,
                        serverRegion = d.optString("region").ifBlank { it.serverRegion },
                    )
                }
                if (activeLadder.getOrNull(ladderIndex)?.name == "E") {
                    d.optString("meekResolvedIPAddress").replace("\\", "").trim().takeIf { it.isNotBlank() }?.let { ip ->
                        val ms = (System.currentTimeMillis() - psiphonRungStartedAt).takeIf { psiphonRungStartedAt > 0 }
                        PsiphonCleanIps.noteUsed(this, ip, ms)
                        PsiphonLive.update { it.copy(cleanIp = ip) }
                        ConnectionLog.record("Clean IP $ip carried the tunnel" + (ms?.let { " in ${it}ms" } ?: ""))
                    }
                }
            }
        }
        // THE SERVER REFUSING A DESTINATION, which is otherwise invisible: `administratively
        // prohibited` is the Psiphon server declining to open a channel -- its own policy, not
        // filtering -- and to the user a site that simply does not open while everything else
        // does. Counted, not logged per event: a retried destination makes a line per attempt.
        if (message.contains("administratively prohibited", ignoreCase = true)) {
            PsiphonLive.update { it.copy(refused = it.refused + 1) }
            val n = PsiphonLive.state.value?.refused ?: 0
            if (n == 1 || n % 10 == 0) {
                ConnectionLog.record("The Psiphon server refused a destination ($n so far) — its own policy, not filtering; some sites will not open through this server")
            }
        }
        // An in-proxy broker selection is decisive evidence the peer-relay path is
        // in play, regardless of which OSSH variant rides on top of it.
        if (message.contains("inproxy: selected broker")) {
            inproxyInUse = true
        }
    }

    override fun getContext(): android.content.Context = this

    override fun getPsiphonConfig(): String = psiphonConfigJson

    private fun buildPsiphonConfig(): String {
        // Fixed port so the TUN can be pre-created before Psiphon starts.
        val socksPort = CoreConfig.socksPort(this)
        val config = org.json.JSONObject().apply {
            put("PropagationChannelId", "FFFFFFFFFFFFFFFF")
            put("SponsorId", "1111111111111111")
            put("EgressRegion", "")
            put("EstablishTunnelTimeoutSeconds", 120)
            put("DataDirectory", filesDir.absolutePath)
            put("ClientVersion", "1")
            put("TunnelProtocol", "")
            // Fetch Psiphon's server list instead of living on the 430 compiled-in entries.
            //
            // Both download locations are required by the core's own validation when the URLs are
            // set, and the OSL directory has to exist before the fetcher writes into it.
            put("RemoteServerListURLs", psiphonUrlList(PSIPHON_REMOTE_SERVER_LIST_URLS))
            put("RemoteServerListDownloadFilename",
                java.io.File(filesDir, "psiphon_remote_server_list").absolutePath)
            put("ObfuscatedServerListRootURLs", psiphonUrlList(PSIPHON_OSL_ROOT_URLS))
            put("ObfuscatedServerListDownloadDirectory",
                java.io.File(filesDir, "psiphon_osl").apply { mkdirs() }.absolutePath)
            put("LocalSocksProxyPort", socksPort)
            // --- LAN sharing, opt-in ---
            //
            // "any" is psiphon-tunnel-core's own spelling for 0.0.0.0 (config.go:
            // "If 'any' is provided then use 0.0.0.0"), so no socket surgery is
            // needed on our side. The HTTP proxy is only bound when sharing is on:
            // Windows takes an HTTP proxy system-wide while SOCKS has to be set per
            // application, so a shared tunnel needs both, but an unshared one has no
            // use for a second listener and should not open one.
            //
            // With sharing off, neither key is written at all — the Go default is
            // 127.0.0.1 and no HTTP proxy, which is exactly the previous behaviour.
            if (CoreConfig.lanSharingEnabled(this@TunnelVpnService)) {
                put("ListenInterface", "any")
                put("LocalHttpProxyPort", CoreConfig.httpProxyPort(this@TunnelVpnService))
            }
            put("RemoteServerListSignaturePublicKey", PSIPHON_RSL_KEY)
            put("ServerEntrySignaturePublicKey", PSIPHON_ENTRY_KEY)
            put("ExchangeObfuscationKey", PSIPHON_EXCHANGE_KEY)
            // Required for onBytesTransferred() to ever fire. Psiphon suppresses
            // the BytesTransferred notice unless this is set, and in VPN mode
            // Psiphon's counters are the ONLY traffic source now that the Rust
            // core is out of the data path — without it the notification stays
            // stuck on "Connecting..." forever and data usage reads 0.
            put("EmitBytesTransferred", true)
            // --- Anti-censorship tuning for restrictive ISPs (e.g. Hamrah-e-Aval) ---
            // Tell Psiphon the user is in Iran so Iran-specific Tactics (protocol
            // selection, padding, server prioritization) are downloaded and applied.
            put("DeviceRegion", "IR")
            // More concurrent connection attempts = higher probability of finding
            // a server/protocol that survives DPI on restrictive networks.
            put("ConnectionWorkerPoolSize", 12)
            // Emit detailed diagnostic notices so we can see exactly which
            // protocols/servers fail on which carriers.
            put("EmitDiagnosticNotices", true)
            // Which fronting address, which resolver, which TLS profile -- the fields that say WHY one
            // dial worked and the next did not, and the address a clean-IP connect came up on
            // (ConnectedServer · meekResolvedIPAddress). Only in the notices when this is on.
            put("EmitDiagnosticNetworkParameters", true)
            // The server telling the client something is wrong: a disallowed destination, a traffic
            // rule, an expired authorization. onServerAlert stays silent without it.
            put("EmitServerAlerts", true)
            // --- Give Psiphon a resolver the operator does not intercept ---
            //
            // See [PSIPHON_ALTERNATE_DNS] for the field evidence. Three keys, and
            // each one is load-bearing:
            //
            //  * PreferredAlternateServers, not AlternateServers: upstream only
            //    consults the plain Alternate list when the system server list is
            //    EMPTY, and on Android it never is — GetDNSServers returns the
            //    resolvers we set on the TUN. Only the Preferred list is allowed
            //    to go first while system servers exist.
            //  * Probability 1.0, because the default is 0.0. The Preferred list
            //    is selected by a weighted coin flip, so without this the list is
            //    configured and then almost never used.
            //  * AttemptsPerPreferredServer 2 (default 1): one lost UDP packet on
            //    a mobile link would otherwise drop us straight back to the
            //    hijacked system resolver.
            //
            // Not a hard override: the system resolvers stay in the list behind
            // these, so a network with honest DNS still resolves normally if the
            // alternate ports are the ones being blocked.
            put("DNSResolverPreferredAlternateServers", JSONArray(PSIPHON_ALTERNATE_DNS))
            put("DNSResolverPreferAlternateServerProbability", 1.0)
            put("DNSResolverAttemptsPerPreferredServer", 2)
            // Psiphon-over-WARP: dial out through the core's SOCKS listener, so
            // every Psiphon connection leaves inside the WARP tunnel.
            //
            // Measured consequence, worth knowing before reading a chained log:
            // with this set Psiphon stops attempting QUIC-OSSH entirely (0
            // attempts over a full run, against 3 when dialling directly) and
            // uses TCP protocols only. That is Psiphon's own rule — a SOCKS5
            // upstream cannot carry its UDP dials — and it is why the ladder's
            // fronted and direct rungs still work here while nothing needs a
            // UDP associate from the outer listener.
            if (chainMode) {
                put(
                    "UpstreamProxyURL",
                    "socks5://127.0.0.1:${CoreConfig.chainSocksPort(this@TunnelVpnService)}",
                )
            }
            // Note: "DisableNetworkManager" was tried here and is a no-op — the
            // key does not exist in libgojni.so (verified with strings). Psiphon's
            // NetworkMonitor still restarts the tunnel when tun0 appears. That is
            // survivable now: tun2socks holds the TUN fd and the SOCKS port is
            // fixed, so a rotation only kills in-flight upstream sockets and lwIP
            // resets those flows individually instead of dropping the interface.
        }

        // Apply the current rung of the escalation ladder. Each rung overrides
        // protocol selection and worker-pool sizing on top of the base config,
        // and owns the establish timeout so a dead rung is abandoned quickly
        // instead of burning the full two minutes.
        val strategy = activeLadder.getOrNull(ladderIndex)
        if (strategy != null) {
            strategy.configure(config)
            config.put("EstablishTunnelTimeoutSeconds", strategy.timeoutSeconds)
            strategy.dataRoot?.let { root ->
                java.io.File(root).mkdirs()
                config.put("DataRootDirectory", root)
            }
            ConnectionLog.record(
                "Strategy ${ladderIndex + 1}/${activeLadder.size} " +
                    "(${strategy.name}): ${strategy.label} — ${strategy.timeoutSeconds}s budget" +
                    (strategy.upstream?.let { " — through ${PsiphonUpstream.masked(it.url)}" } ?: "") +
                    (if (strategy.name == "E") " — clean IPs ${config.optJSONObject("DNSResolverPreresolvedIPAddressCIDRs")}" else "")
            )
        }

        // The preferred-country attempt, in front of the ladder and only once.
        //
        // Applied AFTER the rung so it owns the establish timeout: the rung's own
        // budget (up to 75s) is sized for searching a hostile carrier, and spending
        // that on a country preference would make a wrong choice cost more than the
        // whole ladder. A short budget is also the honest one — if the preferred
        // country is reachable at all it answers quickly, because the filter has
        // already removed everything else.
        // The benchmark's country wins over the saved one: the whole point of the run is to
        // measure a country the user has NOT chosen yet.
        val preferredRegion = benchRegion ?: if (regionPhase) CoreConfig.egressRegion(this) else null
        if (preferredRegion != null) {
            config.put("EgressRegion", preferredRegion)
            config.put("EstablishTunnelTimeoutSeconds", REGION_PHASE_TIMEOUT_SECONDS)
            // Whether to keep the rung's protocol limit depends on the country.
            //
            // The limit used to be dropped unconditionally, and the reason was sound at the time:
            // rung A pins FRONTED-MEEK, only 5 of the bundled entries advertised it, and all five
            // were US or GB. On the operators where fronting is the only thing that works -- which
            // is exactly where the remembered rung is A -- pairing "only fronted servers" with
            // "only German servers" matched nothing, so a German preference burned its budget
            // every single time and then fell back. Widening asked the honest question instead:
            // is ANYTHING in this country reachable?
            //
            // That reasoning expired when the server list was refreshed. There are 11 fronted
            // entries now, in DE, NL, FR, CA, GB and US, so "fronted AND this country" is a real
            // set for six countries. And widening is actively harmful for them: on a line where
            // the direct protocols are blocked, an all-protocols attempt at Germany spends its
            // whole budget dialling German servers that cannot answer, times out, and hands the
            // ladder back -- which is precisely what was measured, a German preference landing in
            // the US every time.
            //
            // So the limit is kept when the country has a fronted server to match it with, and
            // dropped when it does not. The chained branch below still narrows to TCP-only
            // afterwards, which is a hard requirement of a SOCKS5 upstream rather than a
            // preference.
            val frontedHere = preferredRegion in frontedRegions
            if (!frontedHere) {
                config.remove("InitialLimitTunnelProtocols")
                config.remove("InitialLimitTunnelProtocolsCandidateCount")
                config.remove("LimitTunnelProtocols")
            }
            ConnectionLog.record(
                "Preferred country ${PsiphonRegions.name(preferredRegion)} ($preferredRegion), " +
                    (if (frontedHere) "fronted servers exist there — keeping the fronted limit"
                     else "no fronted server there — all protocols") +
                    " — ${REGION_PHASE_TIMEOUT_SECONDS}s before the ladder"
            )
        }


        // Applied AFTER the rung, so no rung can widen it back. This is a hard
        // limit, unlike the rungs' InitialLimitTunnelProtocols preference, because
        // a preference lapses once the candidate budget is spent and Psiphon then
        // reverts to its full set — including the UDP protocols a SOCKS5 upstream
        // cannot carry.
        if (chainMode) {
            config.put("LimitTunnelProtocols", JSONArray(PROTOCOLS_CHAINABLE))

            // The rung's ORDERING is kept, just narrowed to what can cross the
            // proxy. Dropping it entirely would collapse rung A into rung D, and
            // rung A's "fronted first" is the behaviour that works on
            // Hamrah-e-Aval, where every direct dial is null-routed.
            val preference = config.optJSONArray("InitialLimitTunnelProtocols")
            if (preference != null) {
                val chainable = (0 until preference.length())
                    .map(preference::getString)
                    .filter(PROTOCOLS_CHAINABLE::contains)
                if (chainable.isEmpty()) {
                    config.remove("InitialLimitTunnelProtocols")
                    config.remove("InitialLimitTunnelProtocolsCandidateCount")
                } else {
                    config.put("InitialLimitTunnelProtocols", JSONArray(chainable))
                }
            }

            // In-proxy off explicitly: rung C is already filtered out of
            // activeLadder, but the base config must not leave the door open.
            config.put("InproxyEnabled", false)
            config.put("InproxyAllowClient", false)
            ConnectionLog.record("Chain: TCP-only protocols (a SOCKS proxy cannot carry UDP)")
        }
        // Stated once per attempt so a support log proves the resolver was in
        // play. Without it, a future "IP is bogon" log would be impossible to
        // tell apart from a build that predates this.
        ConnectionLog.record(
            "Psiphon DNS: ${PSIPHON_ALTERNATE_DNS.size} public resolvers on " +
                "non-standard ports preferred over the operator's"
        )
        return config.toString()
    }

    private fun startPsiphonTunnel() {
        try {
            // Clear evidence from any previous rung: attribution must reflect this
            // attempt only, otherwise a protocol notice from a failed rung would
            // be credited to whichever rung eventually connects.
            activeTunnelProtocol = ""
            inproxyInUse = false
            psiphonRungStartedAt = System.currentTimeMillis()
            val tunnel = PsiphonTunnel.newPsiphonTunnel(this)
            // Always SOCKS mode — in VPN mode we bridge TUN→SOCKS ourselves.
            tunnel.setVpnMode(false)
            psiphonTunnel = tunnel
            psiphonConfigJson = buildPsiphonConfig()

            // Load hex-encoded server entries from assets
            val serverEntries = try {
                com.mlmvpn.scanner.store.StoreFiles.open(this, "server_entries.txt").bufferedReader().readText().trim()
            } catch (e: Exception) {
                ConnectionLog.record("No server_entries.txt in assets: ${e.message}")
                ""
            }
            // Fire-and-forget: Psiphon connects asynchronously.
            // onListeningSocksProxyPort() saves the port.
            // onConnected() starts the Rust core to bridge TUN → SOCKS.
            tunnel.startTunneling(serverEntries)
            ConnectionLog.record("Psiphon tunnel starting...")
            armLadderTimer()
        } catch (e: Exception) {
            ConnectionLog.record("Psiphon start failed: ${e.message}")
            activeSocksPort = 0
            // Nothing is armed at this point — the exception happened before
            // armLadderTimer(), so no watchdog and no onExiting() will ever fire.
            // Without stopping here the service sits on "Connecting…" forever with
            // a live TUN and no tunnel behind it.
            failAndStop(e.message ?: "Psiphon could not start")
        }
    }

    /** «روش ۲ از ۴: آی‌پی تمیز — تا ۴۵ ثانیه»: the line under the dial while the ladder walks. */
    private fun rungStatus(s: PsiphonStrategy, index: Int, count: Int): String {
        val seconds = rungSeconds(s)
        return tr(
            "روش ${faDigits(index + 1)} از ${faDigits(count)}: ${s.label} — تا ${faDigits(seconds)} ثانیه",
            "Method ${index + 1} of $count: ${s.label} -- up to $seconds s",
        )
    }

    /** What this attempt really has: the preferred-country attempt runs on its own shorter budget. */
    private fun rungSeconds(s: PsiphonStrategy): Int =
        if (regionPhase && CoreConfig.egressRegion(this) != null) REGION_PHASE_TIMEOUT_SECONDS else s.timeoutSeconds

    private fun faDigits(n: Any): String = n.toString().map { c -> if (c in '0'..'9') '۰' + (c - '0') else if (c == '.') '٫' else c }.joinToString("")

    /** The screen's copy of which rung is being tried (see [PsiphonLive]). */
    private fun liveRung(s: PsiphonStrategy?, index: Int, count: Int) {
        if (s == null) return
        PsiphonLive.rung(s.name, s.label, index, count, rungSeconds(s))
        PsiphonLive.update { it.copy(upstream = s.upstream?.label.orEmpty(), upstreamCountry = s.upstream?.country.orEmpty(), cleanIp = "", mbit = 0.0) }
    }

    /**
     * Forget which server worked last time, so the next dial picks a different one -- the desktop's
     * `dropReplayState`. Psiphon's replay is what makes a reconnect fast, and also what pins a client
     * to a bad server for ever (the desktop's session that started this had the SAME French server
     * two connects an hour apart, replayed, at 0.10 Mbit/s); the config cannot turn it off. Removing
     * the datastore it lives in does. ONLY the datastore: the downloaded server list beside it
     * survives and is imported again.
     */
    private fun dropReplayState(dataRoot: String?) {
        val root = java.io.File(dataRoot ?: java.io.File(filesDir, "ca.psiphon.PsiphonTunnel.tunnel-core").absolutePath)
        for (dir in listOf(java.io.File(root, "ca.psiphon.PsiphonTunnel.tunnel-core/datastore"), java.io.File(root, "datastore"))) {
            if (dir.exists() && runCatching { dir.deleteRecursively() }.getOrDefault(false)) ConnectionLog.record("Replay dropped: ${dir.absolutePath}")
        }
    }

    /**
     * Arm the watchdog for the current rung.
     *
     * Psiphon's own EstablishTunnelTimeout fires inside the Go core and shuts the
     * controller down without telling us which rung failed, so we keep our own
     * timer with a small grace period on top. Whichever fires first, the effect
     * is the same: [escalateLadder] moves to the next rung.
     */
    private fun armLadderTimer() {
        val strategy = activeLadder.getOrNull(ladderIndex) ?: return
        cancelLadderTimer()
        ladderActive.set(true)
        // +8s grace so Psiphon's internal timeout and teardown land first; racing
        // it would restart the tunnel while the old controller is still stopping.
        //
        // During the preferred-country attempt the budget is the region phase's,
        // not the rung's: buildPsiphonConfig() overrode EstablishTunnelTimeout for
        // that attempt, so watching the rung's longer budget would leave the user
        // on a filtered candidate pool long after Psiphon had already given up.
        val seconds = if (regionPhase && CoreConfig.egressRegion(this) != null) {
            REGION_PHASE_TIMEOUT_SECONDS
        } else {
            strategy.timeoutSeconds
        }
        val budget = seconds.toLong() + 8L
        ladderTimer = ladderScheduler.schedule({
            if (ladderActive.get() && !psiphonVpnActivated) escalateLadder()
        }, budget, TimeUnit.SECONDS)
    }


    /**
     * Decide whether this connect starts with the preferred-country attempt.
     *
     * Called once per connect, before the first [startPsiphonTunnel].
     *
     * Any run with a country set, chained or plain.
     *
     * It used to be chained runs only, on the reasoning that letting Psiphon pick whichever
     * server answers first is the fastest path. That held while there were five fronted servers
     * and all five were US or GB: there was nothing to choose between, so a preference could only
     * cost time. With the refreshed list there are eleven, in six countries, and on a line where
     * fronting is the only transport the exit country is the single biggest lever on speed --
     * meek is round-trip-bound, so Frankfurt and Los Angeles are not interchangeable from here.
     *
     * The cost stays bounded exactly as before: one attempt at the chosen country with its own
     * short budget, then the ladder with no preference at all. A country that cannot be reached
     * delays the connect by that budget and nothing else.
     */
    /** A Psiphon connect begins: nothing of the last one's search, drops or screen state carries over. */
    private fun beginPsiphonSession() {
        psiphonQualityDone = false
        psiphonQualityAttempts = 1
        psiphonQualityGen++
        psiphonDroppedAt = 0L
        PsiphonLive.begin()
    }

    /**
     * This connect's ladder ([buildSessionLadder]): «پروکسی سرور»'s proxies -- re-proven right now
     * ([PsiphonUpstream.freshCandidates]) -- in front when it is the chosen method, the pinned method
     * alone, or the automatic ladder from the remembered rung. Runs on the worker before the TUN
     * exists. False when the connect cannot go on: stopped meanwhile, or a pinned method with nothing
     * to work with (and then it has already failed, with the reason).
     */
    private fun preparePsiphonLadder(): Boolean {
        val method = PsiphonMethods.pinned(this)
        var upstream = emptyList<PsiphonUpstream.Candidate>()
        if (method == "X" && !chainMode) {
            sendStatus(STATUS_CONNECTING, tr("بررسی پروکسی‌های انتخاب‌شده از اینترنت شما…", "Checking the chosen proxies from your internet…"))
            ConnectionLog.record("Proxy server: checking the chosen proxies before connecting")
            upstream = runBlockingStoppable {
                PsiphonUpstream.freshCandidates(this@TunnelVpnService, onNote = { note ->
                    ConnectionLog.record("Proxy server: $note")
                    sendStatus(STATUS_CONNECTING, note)
                })
            }.orEmpty().take(3)
            if (stopRequested.get()) return false
            if (upstream.isEmpty()) {
                ConnectionLog.record("Proxy server: none of the chosen proxies connects with Psiphon now — Psiphon's own ways (automatic)")
                sendStatus(STATUS_CONNECTING, tr(
                    "هیچ پروکسی‌ای از این انتخاب الان با سایفون وصل نمی‌شود — اتصال با راه‌های خود سایفون (خودکار).",
                    "None of the chosen proxies connects with Psiphon now -- connecting Psiphon's own ways (automatic)."))
            }
        }
        val ladder = try {
            buildSessionLadder(if (method == "X") PsiphonMethods.AUTO else method, upstream)
        } catch (e: IllegalStateException) {
            ConnectionLog.record("Psiphon: ${e.message}")
            failAndStop(e.message ?: tr("این روش اتصال آماده نیست.", "This method is not ready."))
            return false
        }
        sessionLadder = ladder
        ladderIndex = 0
        ladderAttempts = 0
        armRegionPhase()
        ConnectionLog.record("Psiphon methods: " + ladder.joinToString(" → ") { s -> s.name + (s.upstream?.let { "(${it.label})" } ?: "") })
        return true
    }

    /**
     * [block] on this worker thread, ended early when the user stops the connect -- a proxy re-test
     * can take most of a minute, and a stop pressed in it must not wait for it.
     */
    private fun <T> runBlockingStoppable(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T? =
        kotlinx.coroutines.runBlocking {
            val work = async(kotlinx.coroutines.Dispatchers.IO) { block() }
            val watch = launch {
                while (isActive) {
                    if (stopRequested.get()) { work.cancel(); break }
                    kotlinx.coroutines.delay(250)
                }
            }
            try { work.await() } catch (_: kotlinx.coroutines.CancellationException) { null } finally { watch.cancel() }
        }

    private fun armRegionPhase() {
        val region = CoreConfig.egressRegion(this)
        regionPhase = region != null
        regionPhaseTried = region.orEmpty()
        if (region != null) {
            ConnectionLog.record(
                "Preferred country: ${PsiphonRegions.name(region)} ($region), " +
                    "then all countries if it does not connect"
            )
        }
    }

    /**
     * Connect to each country in turn and measure what it actually delivers.
     *
     * Only the countries with a fronted server are tried, because on a line where the direct
     * protocols are blocked those are the only ones that can answer at all -- and on a line where
     * they are not blocked the country makes far less difference, so the list is the useful one
     * either way. Six countries at up to a minute each is a few minutes of the tunnel going up and
     * down, which is why nothing starts it but an explicit tap.
     *
     * Each country gets a cold measurement: the tunnel is torn down between them, so a country is
     * never handed the previous one's warm path. What is recorded is the time to connect, a real
     * round trip through the tunnel, and a real download through it -- see [PsiphonBench] for why
     * all three go over the local SOCKS port rather than an ordinary socket.
     */
    private fun benchmarkRegions() {
        // EVERY country the picker offers, not just the ones with a fronted server.
        //
        // The first version measured only the six fronted countries, on the reasoning that a line
        // which blocks the direct protocols cannot reach the others anyway -- which was true of
        // the line it was written on, and is not a fact about anyone else's. A test that decides
        // in advance which answers are possible is not a test. So all of them are tried.
        //
        // The fronted ones go FIRST, because they are the ones most likely to answer and the user
        // watching the list should see something useful in the first minute rather than the
        // eighth. A country that cannot answer costs the connect budget and nothing more, and the
        // results appear one by one, so leaving the screen after the answer you needed is a
        // perfectly good way to end the run.
        val fronted = frontedRegions.sorted()
        val regions = fronted + PsiphonRegions.options(this).filterNot { it in frontedRegions }
        if (regions.isEmpty()) {
            ConnectionLog.record("Country benchmark: no countries to measure")
            return
        }
        val config = storedConfig ?: CoreConfig.json(this, "psiphon")
        PsiphonBench.begin(regions)
        ConnectionLog.record("Country benchmark: ${regions.joinToString(",")}")
        try {
            for (region in regions) {
                if (PsiphonBench.isCancelled()) {
                    ConnectionLog.record("Country benchmark: stopped by the user")
                    break
                }
                PsiphonBench.current = region
                benchRegion = region

                // Torn down WITHOUT taking the service with it.
                //
                // ACTION_DISCONNECT cannot be used here even though it is the obvious choice: it
                // means "the user wants this off", so it calls stopSelf(), onDestroy shuts the
                // worker down with shutdownNow(), and that interrupts the very thread this loop
                // is running on. Measured: the run ended after one country every time, with
                // "nothing connected" logged one second after the first result.
                stopTunnel(notify = false, teardownService = false)
                // The latches that stop path decides on are not this run's opinion. userInitiated
                // in particular is what auto-reconnect and the ladder both read as "stay down".
                userInitiatedStop.set(false)
                var settle = 0
                while (settle < 20 && (connected.get() || psiphonVpnActivated)) {
                    try { Thread.sleep(300) } catch (_: InterruptedException) { return }
                    settle++
                }
                try { Thread.sleep(800) } catch (_: InterruptedException) { return }

                val startedAt = System.currentTimeMillis()
                startTunnel(config)
                var connectMs: Long? = null
                while (System.currentTimeMillis() - startedAt < BENCH_CONNECT_TIMEOUT_MS) {
                    if (PsiphonBench.isCancelled()) break
                    // psiphonVpnActivated, NOT connected.
                    //
                    // `connected` is a start-requested latch: startTunnel sets it with a
                    // compareAndSet on its first line, before a single packet has moved, and it
                    // exists to stop a second start racing the first. Polling it made every
                    // country "connect" in about a millisecond and then fail both measurements,
                    // which is exactly what the first run of this reported -- six countries, six
                    // "no answer", including the one that was demonstrably working minutes
                    // earlier. psiphonVpnActivated is set where the tunnel actually becomes
                    // usable: after tun2socks is running against a live Psiphon SOCKS port.
                    if (psiphonVpnActivated) {
                        connectMs = System.currentTimeMillis() - startedAt
                        break
                    }
                    try { Thread.sleep(500) } catch (_: InterruptedException) { return }
                }
                // Let the tunnel settle before timing it. A meek session has just finished its
                // handshake and the first request through it would measure that, not the path.
                if (connectMs != null) {
                    try { Thread.sleep(2_000) } catch (_: InterruptedException) { return }
                }

                // Latency only, for every country.
                //
                // The download used to run here too, and it was twelve seconds per country buying
                // a number that did not change the answer: on meek, throughput follows the round
                // trip, so the request that measures latency has already ranked the list. The
                // winner gets a real download afterwards, once, so the figure on screen is
                // measured rather than inferred.
                val row = if (connectMs == null) {
                    ConnectionLog.record("Country benchmark: $region did not connect")
                    PsiphonBench.Row(region = region, failed = true)
                } else {
                    val rtt = PsiphonBench.measureRtt(CoreConfig.socksPort(this))
                    ConnectionLog.record(
                        "Country benchmark: $region connected in ${connectMs}ms, rtt ${rtt ?: -1}ms"
                    )
                    PsiphonBench.Row(region, connectMs, rtt, null, failed = rtt == null)
                }
                PsiphonBench.rows.add(row)
                PsiphonBench.done = PsiphonBench.rows.size
            }
        } finally {
            benchRegion = null
            // The user asked to MEASURE, not to connect.
            //
            // Without this latch the run ends with a torn-down tunnel and userInitiatedStop
            // false -- which is precisely the state auto-reconnect exists to act on, so the app
            // dialled a tunnel by itself the moment the test finished. From the outside that is
            // the app deciding to connect because a measurement happened to end, which is not
            // something a measurement should ever do. The latch is set BEFORE the stop so the
            // teardown itself cannot arm a retry either.
            userInitiatedStop.set(true)
            // The run ends with the tunnel down, and the screen has to say so. With the notify
            // suppressed the UI kept its "connected" state over a tunnel that no longer existed,
            // which reads to the user as "I picked a country and now nothing works".
            stopTunnel(notify = true, teardownService = false)
            PsiphonBench.finish()
            val best = PsiphonBench.ranked().firstOrNull { it.rttMs != null }
            if (best == null) {
                ConnectionLog.record("Country benchmark: nothing connected")
            } else {
                // Adopt the winner. Running a measurement and then leaving the user to copy the
                // answer into a second screen is asking them to do the one part the app already
                // knows; the list still shows every result, so the choice stays visible and
                // changeable.
                // The winner is adopted as the PREFERENCE, and that is all. Nothing here
                // connects: the next time the user taps connect, this is the country it will
                // try first.
                TunnelPreferences(this@TunnelVpnService).egressRegion = best.region
                ConnectionLog.record(
                    "Country benchmark: best is ${best.region} at ${best.rttMs}ms — " +
                        "set as the preferred country (not connected)"
                )
            }
        }
    }

    private fun cancelLadderTimer() {
        ladderTimer?.cancel(false)
        ladderTimer = null
    }

    /**
     * Defer winner attribution until Psiphon has reported the live protocol.
     *
     * Ordering in the real logs, both carriers, is always:
     *
     *     Psiphon connected — upstream tunnel ready   <- onConnected()
     *     Tunnels: {"count":1}
     *     ActiveTunnel: {"protocol":"FRONTED-MEEK-HTTP-OSSH"}   <- the evidence
     *
     * so reading the protocol inside onConnected() always saw an empty string
     * and fell through to "keep the active rung". That is precisely the wrong
     * answer in the interesting cases: Hamrah-e-Aval was credited to A while
     * rung A was active only by luck, and SamanTel was credited to C purely on a
     * background broker notice while the tunnel was direct QUIC-OSSH.
     *
     * A short delay is enough — the notice follows within milliseconds — and the
     * whole thing is best-effort: if nothing arrives we keep the active rung,
     * which is the old behaviour.
     */
    private fun scheduleLadderAttribution() {
        if (!attributionPending.compareAndSet(false, true)) return
        val rungAtConnect = ladderIndex
        ladderScheduler.schedule({
            attributionPending.set(false)
            if (!stopRequested.get()) recordLadderWinner(rungAtConnect)
        }, 2, TimeUnit.SECONDS)
    }

    /**
     * Persist the rung that genuinely produced the tunnel.
     *
     * Attribution is by *evidence*, in order of how conclusive it is:
     *
     *  1. An in-proxy broker was selected -> rung C, whatever protocol rode on
     *     top. SamanTel connected with plain "OSSH" but the log also showed
     *     "inproxy: selected broker", so protocol alone would have mislabelled it.
     *  2. The live ActiveTunnel protocol matches exactly one rung's preferred
     *     list -> that rung. Hamrah-e-Aval ended on FRONTED-MEEK-OSSH, which is
     *     rung A's signature.
     *  3. The protocol appears in several rungs' lists (FRONTED-MEEK-OSSH is in
     *     all three) -> keep the rung that was active, since it is consistent
     *     with the evidence and switching on ambiguity would just add churn.
     *  4. No protocol notice arrived at all -> keep the active rung.
     *
     * Getting this right matters because the stored value decides where the next
     * connect *starts*: a wrong entry costs the user a full rung timeout before
     * the ladder stumbles onto the path that already worked on their carrier.
     */
    private fun recordLadderWinner(rungAtConnect: Int) {
        val protocol = activeTunnelProtocol
        // Indices are into activeLadder, which is the list ladderIndex walks. In chained mode rung C
        // is not in it, so `inproxyRung` is -1 there and every in-proxy branch below is correctly
        // unreachable.
        val ladder = activeLadder
        // «پروکسی سرور» carried it: the proxy is what is remembered, not a rung -- whatever protocol
        // crossed it, it crossed the proxy.
        ladder.getOrNull(rungAtConnect)?.upstream?.let { up ->
            PsiphonUpstream.noteResult(this, up.key, true)
            ConnectionLog.record("Connected${if (protocol.isNotBlank()) " via $protocol" else ""} — through the proxy server ${up.label}")
            return
        }
        val inproxyRung = ladder.indexOfFirst { it.name == "C" }

        val (winnerIndex, reason) = when {
            // The protocol name is the strongest signal available. An INPROXY-*
            // tunnel is unambiguously the peer-relay rung.
            protocol.startsWith("INPROXY") && inproxyRung >= 0 ->
                inproxyRung to "in-proxy protocol $protocol"

            protocol.isNotBlank() -> {
                val matches = ladder.indices.filter { i ->
                    ladder[i].preferredProtocols.contains(protocol)
                }
                when {
                    matches.size == 1 -> matches[0] to "protocol $protocol is unique to this strategy"
                    matches.contains(rungAtConnect) -> rungAtConnect to "protocol $protocol consistent with active strategy"
                    matches.isNotEmpty() -> matches[0] to "protocol $protocol best match"
                    else -> rungAtConnect to "protocol $protocol not in any preference list; keeping active strategy"
                }
            }

            // Only fall back to broker evidence when no protocol was reported.
            // "inproxy: selected broker" is NOT proof the tunnel used a peer
            // relay: the SamanTel log shows that notice arriving while the
            // established tunnel was plain direct QUIC-OSSH, because the
            // in-proxy machinery keeps negotiating in the background. Crediting
            // rung C there would have pinned that SIM to the slowest rung (75s)
            // when the direct rung connects in seconds.
            inproxyInUse && inproxyRung >= 0 ->
                inproxyRung to "in-proxy broker in use, no protocol notice"

            else -> rungAtConnect to "no protocol notice; keeping active strategy"
        }

        val winner = ladder.getOrNull(winnerIndex) ?: return
        // By name, per mode: an index meant another rung whenever the ladder changed shape, and the
        // clean-IP rung comes and goes with its list.
        PsiphonMethods.remember(this, chainMode, winner.name)

        val via = if (protocol.isNotBlank()) " via $protocol" else ""
        ConnectionLog.record(
            "Connected$via — crediting strategy ${winner.name} (${winner.label}): $reason"
        )
        if (winnerIndex != rungAtConnect) {
            val active = ladder.getOrNull(rungAtConnect)
            ConnectionLog.record(
                "Note: strategy ${active?.name ?: "?"} was active but ${winner.name} " +
                    "carried the tunnel — next connect will start from ${winner.name}"
            )
        }
    }

    /**
     * Move to the next rung and re-dial, or give up if the ladder is exhausted.
     *
     * The TUN interface is deliberately left up across rungs: it was created
     * before Psiphon started, tun2socks is not running yet (no tunnel ever came
     * up), and rebuilding it would drop the VPN permission dialog state. Only
     * the Psiphon controller is torn down and restarted with the next config.
     */
    private fun escalateLadder() {
        if (stopRequested.get()) return
        if (!ladderActive.compareAndSet(true, false)) return
        cancelLadderTimer()

        val ladder = activeLadder

        // The preferred country failed. Drop the region filter and hand over to the
        // ladder *at the same rung*, without counting an attempt: the rung was never
        // given a fair try, it ran against one country's servers only. Counting it
        // would silently shorten the ladder by one every time a country is pinned.
        if (regionPhase) {
            regionPhase = false
            val region = regionPhaseTried
            ConnectionLog.record(
                "Preferred country ${PsiphonRegions.name(region)} did not connect — " +
                    "continuing with all countries from strategy ${ladder.getOrNull(ladderIndex)?.name ?: "?"}"
            )
            sendStatus(STATUS_CONNECTING, com.mlmvpn.scanner.store.tr(
                "کشور انتخابی وصل نشد — همهٔ کشورها با «${ladder.getOrNull(ladderIndex)?.label ?: ""}»…",
                "The chosen country did not connect -- every country with «${ladder.getOrNull(ladderIndex)?.label ?: ""}»…"))
            liveRung(ladder.getOrNull(ladderIndex), ladderIndex, ladder.size)
            runOnWorker {
                if (stopRequested.get()) return@runOnWorker
                try { psiphonTunnel?.stop() } catch (_: Exception) {}
                psiphonTunnel = null
                activeSocksPort = CoreConfig.socksPort(this)
                try { Thread.sleep(1200) } catch (_: InterruptedException) {}
                if (stopRequested.get()) return@runOnWorker
                startPsiphonTunnel()
            }
            return
        }

        val failed = ladder.getOrNull(ladderIndex)
        ladderAttempts += 1
        // A proxy of «پروکسی سرور» that did not carry a real connect: the row says so, and the next
        // connect does not start from it.
        failed?.upstream?.let { up ->
            PsiphonUpstream.noteResult(this, up.key, false,
                error = com.mlmvpn.scanner.store.tr("در اتصال واقعی وصل نشد", "did not carry a real connect"))
        }

        // Wrap around instead of walking off the end. Because a successful rung is
        // remembered and reused first, the ladder can start anywhere — so "done"
        // means every rung has had a turn, not that the index hit the last slot.
        if (ladderAttempts >= ladder.size) {
            ConnectionLog.record(
                "All ${ladder.size} strategies exhausted — carrier is blocking every available path"
            )
            ladderIndex = 0
            ladderAttempts = 0
            regionPhase = false
            // Deliberately the same generic failure whether chained or not: the
            // user asked for one message, and a chained-specific string would only
            // suggest the chain itself was at fault when the carrier is.
            failAndStop(com.mlmvpn.scanner.store.tr(
                "هیچ‌کدام از روش‌ها روی این اینترنت وصل نشد. وای‌فای یا سیم‌کارت دیگری را امتحان کنید.",
                "Could not connect on this carrier. Try Wi-Fi or another SIM."))
            return
        }

        ladderIndex = (ladderIndex + 1) % ladder.size
        val next = ladder[ladderIndex]
        // A new rung draws its own servers: the slow-server search starts over with it.
        psiphonQualityAttempts = 1
        ConnectionLog.record(
            "Strategy ${failed?.name ?: "?"} timed out — escalating to ${next.name}: ${next.label}"
        )
        if (failed?.upstream != null && next.upstream == null) {
            ConnectionLog.record("No proxy server carried — Psiphon's own ways next (automatic)")
            sendStatus(STATUS_CONNECTING, com.mlmvpn.scanner.store.tr(
                "هیچ پروکسی‌ای جواب نداد — اتصال با راه‌های خود سایفون (خودکار): ${next.label}…",
                "No proxy answered -- connecting Psiphon's own ways (automatic): ${next.label}…"))
        } else {
            sendStatus(STATUS_CONNECTING, rungStatus(next, ladderIndex, ladder.size))
        }
        liveRung(next, ladderIndex, ladder.size)

        runOnWorker {
            if (stopRequested.get()) return@runOnWorker
            // Tear down only the Psiphon controller. The TUN stays up.
            try { psiphonTunnel?.stop() } catch (_: Exception) {}
            psiphonTunnel = null
            activeSocksPort = CoreConfig.socksPort(this)
            try { Thread.sleep(1200) } catch (_: InterruptedException) {}
            if (stopRequested.get()) return@runOnWorker
            startPsiphonTunnel()
        }
    }

    /**
     * Take protocol selection back from Psiphon's remote tactics.
     *
     * The field log from Iran proved the rung's protocol list was being ignored:
     * rung A asked for the three FRONTED-MEEK protocols, and Psiphon's own
     * `CandidateServers` notice reported
     * `initialLimitTunnelProtocols: [INPROXY-WEBRTC-*]` instead, followed by
     * `in-proxy protocol selection forced` and 228 WebRTC dials that had no hope
     * on a network where UDP is dead.
     *
     * That is not a bug in our config — it is precedence. `Config.SetParameters`
     * passes `[configParameters, tacticsParameters]` to `Parameters.Set`, and
     * `getAppliedValue` walks that list **backwards**, so the remotely delivered
     * tactics value wins over anything the app set. Psiphon changed its Iran
     * tactics to force in-proxy, and every rung of our ladder silently became the
     * same in-proxy rung. It is also why the ladder felt useless: three rungs, one
     * effective behaviour.
     *
     * `DisableTactics` is the only lever that restores our own ordering, because
     * it stops the tactics request and the stored-tactics load entirely. It is
     * applied per rung, not globally:
     *
     *  - rung A (fronted) sets it — the fronting domains live in the embedded
     *    server entries, so this rung needs nothing from tactics.
     *  - rung C (in-proxy) must NOT set it — broker specs arrive via tactics, and
     *    without them the peer-relay rung cannot dial at all.
     *
     * Cost: rung A loses remote tuning it was not benefiting from anyway. Benefit:
     * the 5 fronted server entries actually get dialled, which is the only path
     * that has ever worked on Hamrah-e-Aval.
     */
    private fun applyTacticsOverride(config: JSONObject) {
        // Chained runs are left exactly as they were. Psiphon rides inside WARP
        // there, its tactics request goes out over a working tunnel, and the user
        // reports the chain connecting on the first try — so there is nothing to
        // fix and no reason to change a path that works. Rung C is filtered out of
        // the chained ladder anyway, so forced in-proxy cannot strand it either.
        if (chainMode) return
        config.put("DisableTactics", true)
        // With tactics off there are no broker specs, so in-proxy is dead weight
        // here: it would still consume worker slots on WebRTC/ICE that cannot
        // complete. Rung C is where the peer relay gets its turn, with tactics on.
        config.put("InproxyEnabled", false)
        config.put("InproxyAllowClient", false)
        ConnectionLog.record(
            "Tactics disabled for this rung — using the app's own protocol order"
        )
    }

    /**
     * Report a terminal failure and actually stop.
     *
     * Every path that reaches this used to call `sendStatus(STATUS_FAILED, …)` and
     * return, which told the UI the truth but left the service running with a
     * live TUN and the placeholder "Connecting…" notification. On a Psiphon run
     * the TUN is created *before* Psiphon starts, so that state is not merely
     * cosmetic: the device has a default route into a tunnel with nothing on the
     * other end, i.e. no internet, and no visible sign the app has given up. That
     * is exactly what was reported from the field.
     *
     * The kill switch is deliberately not consulted here. These are failures to
     * ever establish, not a tunnel dropping under a user who asked to stay
     * protected — blocking all traffic after a failed connect would leave the
     * device offline with no explanation.
     */
    private fun failAndStop(detail: String) {
        sendStatus(STATUS_FAILED, detail)
        connected.set(false)
        stopTunnel(notify = false)
        leaveForeground()
    }

    private fun stopPsiphonTunnel() {
        // PsiphonTunnel.stop() blocks until the Go controller has fully unwound,
        // which during establishing means waiting on in-flight dials. stopTunnel()
        // is reached from onStartCommand() on the main thread, so doing that here
        // synchronously froze the UI — which is what made a mid-connect cancel
        // look like it did nothing. Detach the reference synchronously (so
        // nothing else can use it) and let the blocking stop happen off-thread.
        val tunnel = psiphonTunnel ?: return
        psiphonTunnel = null
        Thread({
            try { tunnel.stop() } catch (_: Exception) {}
        }, "psiphon-stop").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The traffic path stops talking to the screens while there is no screen; see
        // [updateTrafficNotification].
        com.mlmvpn.scanner.utils.ScreenState.watch(this)
        // A null intent is the system starting us, not the app: always-on VPN,
        // and a restart after the process was killed. There is no config in
        // hand on that path - the activity never ran - so the last one used is
        // read back from disk. Without this, turning on always-on VPN in
        // Android's settings starts a service that immediately does nothing,
        // and the platform shows the VPN as connecting for ever.
        if (intent == null || intent.action == null) {
            // startForegroundService() puts the service on a five second fuse:
            // call startForeground() before it burns or the platform kills the
            // process with ForegroundServiceDidNotStartInTimeException. Every
            // branch below has to satisfy that, including the one that decides
            // there is nothing to do - so the notification goes up first and the
            // decision is made afterwards.
            startAsForeground()
            if (!connected.get()) {
                val config = getSharedPreferences("settings", MODE_PRIVATE)
                    .getString(LAST_CONFIG_PREF, null)
                if (config != null) {
                    ConnectionLog.record("Started by the system; reusing the last configuration")
                    userInitiatedStop.set(false)
                    reconnectAttempts = 0
                    nat64Rotations = 0
                    warpTried.clear()
                    ladderFrom = ""
                    startTunnel(config)
                } else {
                    // Nothing has ever been connected, so there is nothing to
                    // restore. Stopping is honest; sitting in the foreground
                    // doing nothing is not.
                    ConnectionLog.record("Started by the system with nothing to restore")
                    leaveForeground()
                }
            }
            return Service.START_STICKY
        }
        when (intent.action) {
            ACTION_CONNECT -> intent.getStringExtra(EXTRA_CONFIG).also {
                // The tile and the engine ladder start a connect with startForegroundService: the
                // notification goes up before anything else, the way the system-start branch above
                // does it, because a connect that ended early (no VPN permission, a bad config) never
                // reached startTunnel's own and the platform killed the app for it
                // (ForegroundServiceDidNotStartInTimeException, 1.2.41). A connect that arrived
                // without its config went up too, or the same exception followed (1.2.42 #2460).
                startAsForeground()
                if (it == null && !connected.get()) leaveForeground()
            }?.let { config ->
                // A fresh user-initiated connect clears both the "user switched it
                // off" latch and the backoff counter, so a manual retry always
                // starts from the first, shortest delay.
                userInitiatedStop.set(false)
                reconnectAttempts = 0
                nat64Rotations = 0
                warpTried.clear()
                ladderFrom = ""
                startTunnel(config)
            }
            ACTION_DISCONNECT -> {
                // The one place that means "the user wants this off". Auto-reconnect
                // reads this latch and stays out of the way.
                userInitiatedStop.set(true)
                cancelAutoReconnect()
                stopTunnel()
            }
            ACTION_GEPH_APPLY -> {
                if (gephMode && connected.get() && !stopRequested.get()) {
                    gephRestarts = 0
                    runOnWorker { restartGephEngine("settings changed") }
                }
            }
            ACTION_RECONNECT -> {
                val config = storedConfig
                if (config != null && connected.get()) {
                    ConnectionLog.record("Quick reconnect requested")
                    nat64Rotations = 0
                    warpTried.clear()
                    stopTunnel(notify = false, teardownService = false)
                    runOnWorker {
                        try { Thread.sleep(500) } catch (_: InterruptedException) {}
                        startTunnel(config)
                    }
                }
            }
            ACTION_BENCH_REGIONS -> {
                if (PsiphonBench.running) {
                    ConnectionLog.record("Country benchmark already running")
                } else {
                    userInitiatedStop.set(false)
                    // A thread of its own, NOT runOnWorker.
                    //
                    // `worker` is a single-thread executor and startTunnel posts the actual
                    // Psiphon startup onto it. A benchmark running ON that worker therefore holds
                    // the only thread the thing it is waiting for needs, so the tunnel can never
                    // come up until the loop gives up -- which it does, after its timeout, for
                    // every country in turn. That is exactly what the first two runs reported:
                    // six countries, six "no answer", and not one line of Psiphon activity in the
                    // log between them.
                    Thread({ benchmarkRegions() }, "psiphon-country-bench").start()
                }
            }
            ACTION_NOTIFICATION_HEALTH -> {
                intent.getStringExtra(EXTRA_NOTIFICATION_IP)?.let { currentVpnIp = it }
                intent.getStringExtra(EXTRA_NOTIFICATION_PING)?.let { currentPing = it }
                // The activity's geolocation lookup is a fallback for transports
                // that cannot name their own exit country (everything except
                // Psiphon, which reports it via onConnectedServerRegion). It must
                // not overwrite a country Psiphon already gave us.
                intent.getStringExtra(EXTRA_NOTIFICATION_COUNTRY)?.takeIf { it.isNotBlank() }
                    ?.let {
                        if (currentCountry.isBlank()) currentCountry = it
                        // The code is taken even when a name is already set: Psiphon supplies
                        // the name and the locator supplies the code, and the screen needs the
                        // code whichever of the two got there first.
                        if (it.length == 2) currentCountryCode = it.uppercase()
                    }
                repostNotification()
                // The measured address is a fact the screens want too, and on the Psiphon and
                // Tor paths it is the ONLY source of one: the exit address on the status
                // broadcast is measured by the Rust core from inside its own tunnel, and
                // neither of those paths runs the core at all. Without this the card sat on
                // "resolving..." for the whole session.
                intent.getStringExtra(EXTRA_NOTIFICATION_IP)?.takeIf { it.isNotBlank() }
                    ?.let { measuredExitIp = it }
                republishStatus()
            }
        }
        return Service.START_REDELIVER_INTENT
    }


    // ------------------------------------------------- the link underneath us

    /**
     * Tells Android which real network the tunnel is riding on.
     *
     * Without this the platform assumes the VPN stands on nothing. Three things
     * then go wrong, and all three are visible to the user:
     *
     *  - Data usage for everything on the device is attributed to the VPN rather
     *    than to Wi-Fi or mobile, so the system's data warning never fires and
     *    per-app usage becomes meaningless.
     *  - The connectivity badge can read "no internet" on a working tunnel,
     *    because the VPN network itself was never validated against anything.
     *  - A Wi-Fi to mobile handover does not propagate, so the tunnel keeps
     *    pointing at a link that has gone away instead of following the move.
     *
     * The callback tracks the default network with NET_CAPABILITY_NOT_VPN, so it
     * follows the real link and never picks up our own interface.
     */
    private var underlyingCallback: ConnectivityManager.NetworkCallback? = null

    private fun trackUnderlyingNetwork() {
        if (underlyingCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
                if (gephMode) onGephNetworkChange(network)
                if (cfwarpMode) onCfWarpNetworkChange()
                if (lanternMode) onLanternNetworkChange()
            }

            override fun onLost(network: Network) {
                // null means "work it out yourself" rather than "there is none",
                // which is the right answer while a handover is in flight.
                setUnderlyingNetworks(null)
                if (lanternMode) onLanternNetworkChange()
            }

            // «لنترن»: which network is the default can change with no network appearing or going
            // (mobile data kept up while Wi-Fi validates), and so can a network's resolvers.
            // «URnetwork» follows the network on its own, for every core (UrnetworkHost).
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (lanternMode) onLanternNetworkChange()
            }

            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) {
                if (lanternMode) onLanternNetworkChange()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onSuccess { underlyingCallback = callback }
            .onFailure { ConnectionLog.record("could not track the underlying network: ${it.message}") }
    }

    private fun stopTrackingUnderlyingNetwork() {
        val callback = underlyingCallback ?: return
        underlyingCallback = null
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { manager.unregisterNetworkCallback(callback) }
    }

    /**
     * Settings every tun this service builds should carry.
     *
     * Unmetered is the load-bearing one. A VPN interface is metered by default,
     * and apps take that seriously: Play Store pauses updates, backups stop,
     * podcast and video apps refuse to download. The tunnel is not itself a
     * metered link - whatever it rides on might be, and that is what
     * [trackUnderlyingNetwork] tells the platform, so the system can apply the
     * real link's metering instead of a made-up one.
     */
    /**
     * The MTU for the Android interface, honouring the user's setting.
     *
     * Every transport used to carry its own number and nothing could change any of them: this
     * service has six `Builder()` sites, four pinned to 1500 and two to 1280, and the MTU field
     * in Settings > Advanced was read by exactly one place in the app -- [MyVpnService] -- so it
     * moved the Xray family and left MASQUE, WireGuard, WARP-in-WARP, Psiphon and Tor exactly
     * where they were. A setting that silently applies to some of the tunnels is worse than one
     * that applies to none, because the user tunes it, sees no change, and has no way to learn
     * which half they were in.
     *
     * The transport's own value stays the DEFAULT rather than becoming a suggestion: 1280 on the
     * native core is not a guess, it is what keeps a QUIC datagram under the 1500-byte ceiling
     * once the outer headers are counted, and 1500 on the tun2socks paths is what lwIP is handed
     * as well. So an untouched setting changes nothing for anyone, and a set one is obeyed
     * literally -- including upward, which is the direction the ceiling exists to discourage. A
     * user who raises MASQUE to 1400 has chosen that; the alternative is a slider that quietly
     * refuses half its range.
     */
    private fun tunMtu(method: com.mlmvpn.scanner.utils.NetworkSettings.Method): Int =
        com.mlmvpn.scanner.utils.NetworkSettings.mtu(this, method).also {
            ConnectionLog.record(
                if (it == method.default) "tun mtu: $it (${method.id} default)"
                else "tun mtu: $it (${method.id}, from settings; default ${method.default})"
            )
        }

    /**
     * Which method's MTU field applies to the tunnel now starting.
     *
     * Off [currentProtocol], which is the upper-cased `protocol` out of the core config, so the
     * chained forms fall out for free: "PSIPHON-OVER-WARP" is still Psiphon as far as the tun is
     * concerned, because the tun is the Psiphon end of it. `GOOL` is the core's own name for
     * WARP-on-WARP and there is no renaming it -- see [com.mlmvpn.scanner.ui.tunnel.Transport].
     */
    private fun mtuMethod(): com.mlmvpn.scanner.utils.NetworkSettings.Method = when {
        currentProtocol.contains("TOR") -> com.mlmvpn.scanner.utils.NetworkSettings.Method.TOR
        currentProtocol.contains("PSIPHON") -> com.mlmvpn.scanner.utils.NetworkSettings.Method.PSIPHON
        currentProtocol.contains("GOOL") -> com.mlmvpn.scanner.utils.NetworkSettings.Method.WARP_ON_WARP
        currentProtocol.contains("WIREGUARD") -> com.mlmvpn.scanner.utils.NetworkSettings.Method.WIREGUARD
        else -> com.mlmvpn.scanner.utils.NetworkSettings.Method.MASQUE
    }

    private fun Builder.applyPlatformDefaults(): Builder {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setMetered(false)
        }
        return this
    }

    override fun onCreate() {
        super.onCreate()
        // So a command sent while the app is in the background reaches this instance directly.
        com.mlmvpn.scanner.utils.SafeStart.attach(this)
        // The monthly totals' file, read from disk now on a thread of its own: the first read
        // happened inside startTunnel on the main thread and froze slow phones for seconds at
        // every connect (1.2.42 #2335).
        Thread({ runCatching { getSharedPreferences(TRAFFIC_PREFS, MODE_PRIVATE).getInt(TRAFFIC_SCHEMA, 0) } }, "traffic-prefs").start()
    }

    override fun onDestroy() {
        com.mlmvpn.scanner.utils.SafeStart.detach(this)
        cancelAutoReconnect()
        stopTrackingUnderlyingNetwork()
        stopTorProgressPolling()
        stopTunnel(notify = false)
        cancelLadderTimer()
        ladderScheduler.shutdownNow()
        worker.shutdownNow()
        super.onDestroy()
    }

    /**
     * Android revoked our VPN permission — another VPN app was started, or the
     * user hit "disconnect" in system settings.
     *
     * Reconnecting here would be wrong twice over: the permission is gone, so a
     * retry cannot succeed, and fighting another VPN app for the tunnel is not
     * this app's decision to make. Treated exactly like a user disconnect.
     */
    override fun onRevoke() {
        ConnectionLog.record("VPN permission revoked by the system")
        userInitiatedStop.set(true)
        cancelAutoReconnect()
        stopTrackingUnderlyingNetwork()
        stopTunnel()
        super.onRevoke()
    }

    fun protectSocket(fd: Int): Boolean = !vpnModeActive.get() || protect(fd)

    override fun onEvent(json: String) {
        try {
            val event = JSONObject(json)
            when (event.getString("type")) {
                "status" -> {
                    val status = event.getString("status")
                    val detail = if (event.isNull("detail")) null else event.getString("detail")
                    // In chained mode these events describe the OUTER leg only.
                    //
                    // The core fires mark_ready() the moment WARP is up, which is
                    // long before Psiphon has a tunnel. Forwarding that as
                    // CONNECTED made the UI start its verification gate against
                    // Psiphon's SOCKS port while Psiphon was still establishing:
                    // the field log shows "no active tunnels" at 18:01:49 and the
                    // probe only passing at 18:02:06, i.e. ten attempts and seven
                    // seconds stuck on "Connecting" — and in the second log the
                    // same race hit the probe ceiling and reported Connection
                    // Failed over a perfectly healthy outer tunnel.
                    //
                    // So in chained mode the outer leg never reports CONNECTED.
                    // The chain's CONNECTED comes from onConnected(), once Psiphon
                    // has a tunnel and tun2socks is routing.
                    //
                    // "starting" is swallowed for the same reason: the ladder emits
                    // one per rung it tries, and each would reset the UI to
                    // "Starting" mid-attempt, hiding which transport is being
                    // attempted. raiseOuterLeg() narrates the ladder itself.
                    if (chainMode && (status == STATUS_CONNECTED || status == STATUS_STARTING)) {
                        // Only meaningful once a rung has been accepted. While the
                        // ladder is still walking, several rungs can each announce
                        // themselves ready before being rejected.
                        if (chainOuterCommitted && status == STATUS_CONNECTED) {
                            // Name the inner leg: this same branch now serves Tor
                            // over WARP, where "waiting for Psiphon" would be wrong.
                            val inner = if (currentProtocol.contains("TOR")) "Tor" else "Psiphon"
                            ConnectionLog.record("Chain: outer leg is up; waiting for $inner")
                            sendStatus(STATUS_CONNECTING, "Connecting $inner through WARP…")
                        }
                        return
                    }
                    // A rejected rung's teardown emits DISCONNECTED/FAILED. Before a
                    // rung is accepted that is the ladder working as intended, not
                    // the chain failing, and forwarding it would paint the dial red
                    // between attempts.
                    if (chainMode && !chainOuterCommitted &&
                        (status == STATUS_DISCONNECTED || status == STATUS_FAILED)
                    ) {
                        return
                    }
                    if (status == STATUS_CONNECTED) {
                        coreSaidConnected = true
                        watchNat64Silence()
                    }
                    sendStatus(status, detail)
                }
                "traffic" -> {
                    val tx = event.getLong("tx")
                    val rx = event.getLong("rx")
                    // Chained mode has two sets of counters for the same bytes: the
                    // core's (outer, absolute totals) and Psiphon's (inner, deltas
                    // via onBytesTransferred). Letting both write here made them
                    // fight — Psiphon accumulating while the core overwrote. The
                    // inner leg is the one carrying the user's data, and it is what
                    // plain Psiphon mode already reports, so the outer leg's
                    // counters are dropped for consistency.
                    if (chainMode) return
                    currentTx = tx
                    currentRx = rx
                    updateTrafficNotification(tx, rx)
                }
                // The core measured the exit address from inside the tunnel. This
                // is the authoritative source: the app's own HTTP lookup leaves
                // over the carrier link (we are excluded from our own TUN) and so
                // reports the carrier's IP, not the tunnel's.
                "exit_ip" -> {
                    val ip = event.getString("ip")
                    // In chained mode this is the OUTER leg's exit — a Cloudflare
                    // WARP address — and it is NOT where the device's traffic
                    // leaves. Psiphon's egress is, and the field report caught the
                    // contradiction: the card read Germany (Psiphon's server, per
                    // `ConnectedServerRegion: DE`) while the WARP address behind it
                    // was American. Publishing the outer address would make the
                    // card and the traffic disagree, so it is logged and dropped.
                    //
                    // Belt and braces: the outer leg runs the userspace netstack,
                    // which has no exit probe (only tun::bridge does), so today it
                    // never emits this at all. The guard is here so that adding one
                    // later cannot silently start overwriting the card.
                    if (chainMode) {
                        if (ip.isNotBlank()) {
                            ConnectionLog.record("Chain: outer WARP leg exits at $ip (not the app's exit)")
                        }
                        return
                    }
                    if (ip.isNotBlank()) {
                        currentVpnIp = ip
                        // Survives the UI: the tile can connect with no activity
                        // alive, and this is the session's only measurement.
                        lastExitIp = ip
                        ConnectionLog.record("Tunnel exit $ip")
                        // The country is resolved by the UI from this address; the
                        // core cannot tell one from inside the tunnel.
                        sendExitIp(ip)
                        repostNotification()
                    }
                }
                "log" -> {
                    val message = event.getString("message")
                    ConnectionLog.record(message)
                    if (currentProtocol == "MASQUE") {
                        MasqueCarrier.onCoreLog(this, message)
                        if (MasqueCarrier.isAccepted(message)) masqueAccepted = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to parse event: $json", e)
        }
    }

    /**
     * Bring up Psiphon-over-WARP: WARP carries the traffic, Psiphon rides inside it.
     *
     * Order is forced by three separate constraints, none of them cosmetic:
     *
     *  1. TUN first, before either tunnel. Psiphon's NetworkMonitor treats tun0
     *     appearing as a network change and restarts the controller, which is the
     *     13-second restart loop the plain Psiphon path already works around.
     *  2. The outer WARP leg next, and we must WAIT for it. Psiphon validates
     *     UpstreamProxyURL by dialling it, so starting Psiphon against a proxy whose
     *     tunnel is not up yet fails the whole rung.
     *  3. tun2socks last, on Psiphon's port, from onConnected() — the same as the
     *     plain path. Only then does device traffic have somewhere to go.
     *
     * The core runs WITHOUT a tun_fd here (TunnelEngine.startProxy), so it publishes
     * SOCKS on CHAIN_SOCKS_PORT instead of taking the device TUN. Its own sockets
     * are protected via the JNI protector, so the WARP leg leaves over the carrier
     * link rather than looping back into our own TUN.
     *
     * The outer leg walks [CoreConfig.CHAIN_OUTER_LADDER] — MASQUE, then WireGuard,
     * then WoW — until one comes up, because which of them a carrier allows varies:
     * Hamrah-e-Aval has never carried WireGuard, while other SIMs connect on it
     * instantly. The winning rung is remembered per device, so the cost of finding
     * it is paid once rather than on every connect.
     */
    private fun startChainTunnel() {
        chainMode = true
        chainOuterCommitted = false
        psiphonVpnMode = true
        psiphonVpnActivated = false
        beginPsiphonSession()

        runOnWorker {
            try {
                // chainMode is set first, so this builds the chained ladder from the chained
                // remembered rung. Reading the unchained memory is what started the very first
                // chained attempt on rung C — the one rung a SOCKS upstream cannot carry — and
                // burned its 75s budget before anything chainable was tried.
                if (!preparePsiphonLadder()) return@runOnWorker
                val address = Tun2SocksManager.selectPrivateAddress()
                ConnectionLog.record("Chain: creating TUN before either tunnel starts")
                tun = Builder()
                    .setSession(getString(R.string.app_name))
                    .applyPlatformDefaults()
                    .setMtu(tunMtu(mtuMethod()))
                    .addAddress(address.ipAddress, address.prefixLength)
                    .routeAllV4()
                    .addRoute(address.subnet, address.prefixLength)
                    .addDnsServer(address.router)
                    // Same reasoning as the plain Psiphon path: public resolvers
                    // plus our own exclusion, so both legs can resolve names
                    // over the carrier link before any tunnel exists.
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    // Same reason as the plain Psiphon path: the Split screen's
                    // choice has to apply to chained runs too, and our own package
                    // stays off the TUN in every mode.
                    .applySplitTunneling()
                    .establish() ?: error("Android could not establish the VPN interface")
                vpnModeActive.set(true)

                TunnelEngine.attach(this)
                val outer = raiseOuterLeg() ?: error(
                    if (CoreConfig.chainOuterIsAuto(this)) {
                        getString(R.string.chain_no_outer_psiphon)
                    } else {
                        // Naming the pinned transport is the actionable part: the fix
                        // is to change the pin, not to retry.
                        val pinned = CoreConfig.chainOuterLabel(
                            CoreConfig.chainOuterCandidates(this).first()
                        )
                        getString(R.string.chain_pinned_failed_psiphon, pinned)
                    }
                )
                ConnectionLog.record("Chain: outer leg ready — starting Psiphon through it")

                // --- Inner leg: Psiphon, dialling out through the outer SOCKS ---
                activeSocksPort = CoreConfig.socksPort(this)
                activeLadder.getOrNull(ladderIndex)?.let { s ->
                    sendStatus(STATUS_CONNECTING, tr("داخل $outer — ", "Inside $outer -- ") + rungStatus(s, ladderIndex, activeLadder.size))
                    liveRung(s, ladderIndex, activeLadder.size)
                }
                startPsiphonTunnel()
            } catch (e: Exception) {
                ConnectionLog.record("Chain start failed: ${e.message}")
                chainMode = false
                failAndStop(e.message ?: "Chain start failed")
            }
        }
    }

    /**
     * Bring up a Tor session: TUN → tun2socks → SocksFront → Tor → circuit.
     *
     * Deliberately shaped like the plain Psiphon path rather than the Rust-core
     * one, because the sequencing constraint is the same: the TUN must exist
     * before the tunnel process starts, or Android's new-network callback makes
     * the tunnel treat tun0 as a network change and restart.
     *
     * Unlike Psiphon this path is **synchronous** — there is no connect callback
     * to wait on. [TorManager.start] blocks on its worker thread until bootstrap
     * reaches 100% or every mode has failed, so tun2socks is started right here
     * once it returns true.
     *
     * ## Tor over WARP
     *
     * When [TorManager.chainArmed] agrees, a WARP leg is raised first and Tor is
     * pointed at its SOCKS listener, exactly as Psiphon-over-WARP does — the same
     * [raiseOuterLeg] ladder (MASQUE → WireGuard → WoW) and the same per-device
     * memory of which rung worked, untouched. Only Tor's own ladder narrows:
     * Direct then Meek, because those are the two that were measured working
     * through a proxy (see [TorManager] `chainedLadder`).
     *
     * [chainMode] is set for a chained Tor run as well, and it is load-bearing for
     * three behaviours in [onEvent] that are about the outer leg rather than about
     * Psiphon: the premature CONNECTED is swallowed, the outer traffic counters are
     * dropped in favour of the inner leg's, and the outer exit IP is not published
     * as the session's exit. All three are exactly what a chained Tor run needs.
     * The Psiphon-specific reads of [chainMode] are unreachable here: no
     * `psiphonLadder`, no `armRegionPhase`, no Psiphon config is built on this path.
     *
     * Note what is still NOT done here: no ladder, no `armRegionPhase`, no
     * `recordWorkingPlainTransport`. Those all belong to the Psiphon/WARP
     * transports. Tor keeps its own mode memory inside [TorManager].
     */
    /**
     * MASQUE, WireGuard or WARP-on-WARP in Proxy Mode: TUN → tun2socks → the core's
     * own SOCKS5 listener → the tunnel.
     *
     * The whole device is still tunnelled — this is not "proxy instead of VPN" — but
     * the core reaches the traffic through a local listener rather than by owning the
     * tun file descriptor, and that listener is the thing Local Network sharing can
     * hand to another device. Without it these three transports had no listener at
     * all ([TunnelStatus.isNativeTunMode]), so the sharing screen told the user to
     * turn Proxy Mode on and Proxy Mode did nothing for them: this service never read
     * the setting. That was the bug.
     *
     * Nothing here is new machinery. [startChainTunnel] already runs the core with
     * [TunnelEngine.startProxy] and [startTorTunnel] already bridges a TUN into a
     * local SOCKS port with [Tun2SocksManager]; this is those two halves put
     * together for a single-leg tunnel, and it is shaped like the Tor path for the
     * same reason that one is shaped like Psiphon's — the TUN must exist before the
     * tunnel starts, or Android's new-network callback makes the core treat tun0 as
     * a network change.
     *
     * What is deliberately NOT carried over from the default path: the core's own
     * addresses, `applyGatewayProxy`, `applyDns` and `applyLanAccess`. The TUN here
     * belongs to tun2socks and uses its private address plan, so the resolver is
     * lwIP's router address and DNS goes through udpgw into the tunnel like every
     * other flow.
     */
    /**
     * How long Proxy Mode waits for the core. A pinned peer (a chosen exit country, a cached
     * endpoint) answers or not within [NATIVE_PROXY_BUDGET_MS]. A WARP transport with nothing
     * pinned hunts for an endpoint first, and that alone took 51 s on 2026-10-11 (target reached at
     * 31 s, then the core's 20 s wait for more) -- the 60 s cut it off in the middle of validating
     * the endpoint it had found. Same patience as the default path's WARP ladder, plus the check.
     */
    private fun proxyReadyBudgetMs(config: String): Long {
        val proto = runCatching { WarpLadder.protocolOf(config) }.getOrNull() ?: return NATIVE_PROXY_BUDGET_MS
        if (nat64Peer != null || proto !in CoreConfig.CHAIN_OUTER_LADDER) return NATIVE_PROXY_BUDGET_MS
        return maxOf(NATIVE_PROXY_BUDGET_MS, WarpLadder.budgetMs(proto) + PROXY_HUNT_EXTRA_MS)
    }

    private fun startNativeProxyTunnel(asked: String) {
        nativeProxyMode = true

        runOnWorker {
            try {
                // As on the native path: a member this network closed lately is started as the one
                // that gets through, and a family closed minutes ago is answered at once.
                val requested = warpReroute(asked) ?: run {
                    failAndStop(getString(R.string.warp_ladder_none))
                    return@runOnWorker
                }
                // The core must own its port. Seen 2026-10-11: with 127.0.0.1:10808 already held by
                // something else, the core's SOCKS bind failed on every attempt, its WireGuard session
                // ended ("send task ended") and restarted every 3 s, while the readiness probe below
                // was answered by the other listener -- "connected" with nothing behind it.
                val configured = CoreConfig.socksPort(this)
                val socksPort = freeLoopbackPort(configured)
                if (socksPort != configured) {
                    ConnectionLog.record("Proxy mode: port $configured is in use by something else; the core listens on $socksPort")
                }
                val config = withListen(withNat64Exit(requested), socksPort)
                ConnectionLog.record("Proxy mode: preparing $currentProtocol identity")
                TunnelEngine.attach(this)
                val addresses = TunnelEngine.prepare(config)
                if (addresses.organization.isNotBlank()) {
                    ConnectionLog.record("Zero Trust organization ${addresses.organization}")
                }

                val address = Tun2SocksManager.selectPrivateAddress()
                ConnectionLog.record("Proxy mode: creating TUN before the core starts")
                tun = Builder()
                    .setSession(getString(R.string.app_name))
                    .applyPlatformDefaults()
                    .setMtu(tunMtu(mtuMethod()))
                    .addAddress(address.ipAddress, address.prefixLength)
                    .routeAllV4()
                    .addRoute(address.subnet, address.prefixLength)
                    // lwIP answers here, and it is the only resolver: a public one
                    // listed beside it would send app DNS out over the carrier link
                    // in the clear. The core resolves its own gateway names off the
                    // TUN, because applySplitTunneling keeps our package off it.
                    .addDnsServer(address.router)
                    .applySplitTunneling()
                    .establish() ?: error("Android could not establish the VPN interface")
                vpnModeActive.set(true)

                ConnectionLog.record(
                    "Proxy mode: starting $currentProtocol → SOCKS 127.0.0.1:$socksPort"
                )
                sendStatus(STATUS_CONNECTING, "Connecting $currentProtocol…")
                // A hand-over to the next WARP member ends this core on purpose: its thread must then
                // not report the end as the tunnel's failure.
                val attemptId = nativeAttempt.incrementAndGet()
                Thread({
                    // Guarded for the same reason the chain's outer leg is: a bare
                    // thread hands anything that escapes to the default handler,
                    // which ends the process rather than this tunnel.
                    try {
                        val result = TunnelEngine.startProxy(config)
                        if (result != 0 && !stopRequested.get() && nativeAttempt.get() == attemptId) {
                            val detail = TunnelEngine.lastError()
                                .ifBlank { "exited with code $result" }
                            ConnectionLog.record("Proxy mode: core ended: $detail")
                            if (!willAutoReconnect()) failAndStop(detail)
                        }
                    } catch (t: Throwable) {
                        ConnectionLog.record("Proxy mode: core threw: ${t.message}")
                        if (!stopRequested.get() && nativeAttempt.get() == attemptId) failAndStop(t.message ?: "Tunnel failed")
                    }
                }, "native-proxy-$currentProtocol").start()

                // Readiness, not just a bound port: `socks::serve` binds as soon as
                // the userspace netstack exists, well before the tunnel behind it is
                // validated, so bridging on a port probe alone would point the whole
                // device at a proxy with nothing behind it.
                //
                // On a connect the user asked for, a WARP member gets the ladder's budget here
                // too (MASQUE 30 s, more if it reached a gateway) and then hands the connect to
                // the next member -- with the same exit country: this is also the path of the
                // IPv4 exit, whose front stays the user's choice whichever member carries.
                val ladderProto = WarpLadder.protocolOf(requested)
                val ladder = reconnectAttempts == 0 && warpLadderApplies(requested)
                val readyBudget = proxyReadyBudgetMs(config)
                val ready = if (ladder) {
                    awaitOuterProxy(WarpLadder.budgetMs(ladderProto), socksPort) ||
                        (WarpLadder.stageGraceMs(ladderProto) > 0 && masqueAccepted && !stopRequested.get() &&
                            awaitOuterProxy(WarpLadder.stageGraceMs(ladderProto), socksPort))
                } else awaitOuterProxy(readyBudget, socksPort)
                if (!ready) {
                    if (ladder && !stopRequested.get() && internetUp()) {
                        ConnectionLog.record("$ladderProto did not come up in its budget on this network")
                        val next = warpLadderNext(ladderProto, config)
                        if (next != null) {
                            // This attempt ends here without a failure: the next member takes the connect.
                            nativeAttempt.incrementAndGet()
                            runCatching { TunnelEngine.stop() }
                            TunnelEngine.detach()
                            nativeProxyMode = false
                            vpnModeActive.set(false)
                            tun?.close()
                            tun = null
                            connected.set(false)
                            switchWarpTransport(ladderProto, next)
                            return@runOnWorker
                        }
                        // The family is used up here: said plainly, not as the core's last error line.
                        failAndStop(getString(R.string.warp_ladder_none))
                        return@runOnWorker
                    }
                    error(
                        if (stopRequested.get()) "Stopped"
                        else "$currentProtocol did not come up in " +
                            "${readyBudget / 1000}s"
                    )
                }

                // tun2socks talks to the front, not to the core, and the front is what
                // makes DNS work at all here. Measured on a device: a SOCKS5 CONNECT to
                // example.com:80 through the core returns 05 00 and fetches the page,
                // while the CONNECT to 127.0.0.1:7300 that tun2socks makes for udpgw
                // hangs forever -- the core has no udpgw server behind it, and
                // `udpgw_transparent_dns = 1` sends EVERY DNS query down that path. So
                // the tunnel carried TCP perfectly and nothing on the phone could
                // resolve a name, which is a connected tunnel on which no site opens.
                val frontPort = SocksFront.FRONT_PORT
                val exitOn = ipv4ExitApplies(requested)
                if (!SocksFront.start(
                        listenPort = frontPort,
                        socksPort = socksPort,
                        // No local DNS server to forward to: the core is not Tor and has
                        // no DNSPort, so the front resolves over TCP through the tunnel.
                        dnsPort = 0,
                        dnsTcpResolver =
                            "${com.mlmvpn.scanner.utils.NetworkSettings.backendDns(this)}:53",
                        // The IPv4 exit: every CONNECT leaves through the chosen country's proxy,
                        // itself reached through WARP. DNS still resolves through WARP itself.
                        exit = if (exitOn) ipv4ExitHop() else null,
                    )
                ) {
                    error("Could not start the local proxy front-end")
                }
                activeSocksPort = frontPort
                if (!Tun2SocksManager.start(tun!!, frontPort, mtu = tunMtu(mtuMethod()))) {
                    error("Could not start device routing")
                }
                // The core is NOT holding the tun on this path, which is exactly what
                // makes the share possible -- and what LanShare.engineShare reads.
                TunnelStatus.isNativeTunMode = false

                sendStatus(STATUS_CONNECTED, "$currentProtocol connected")
                ConnectionLog.record("Proxy mode: connected — local SOCKS5 on port $socksPort")
                repostNotification()
                startTrafficPolling()
                startWatchdog()
                // The core's raw port is a way into WARP for the measurements (WarpVia), and the exit
                // is proved now, with a real request through the front.
                com.mlmvpn.core.warp.WarpVia.register(WARP_VIA_TUNNEL, socksPort)
                if (exitOn) startIpv4ExitSession(frontPort, socksPort)
            } catch (e: Exception) {
                if (stopRequested.get()) {
                    // The user cancelled while the core was still preparing. Say nothing more.
                    ConnectionLog.record("Proxy mode: cancelled before the tunnel came up")
                    return@runOnWorker
                }
                val detail = setupFailure(e)
                ConnectionLog.record("Proxy mode start failed: $detail")
                failAndStop(detail)
            }
        }
    }

    private fun startTorTunnel() {
        val chained = TorManager.chainArmed(this)
        chainMode = chained
        chainOuterCommitted = false

        runOnWorker {
            try {
                val address = Tun2SocksManager.selectPrivateAddress()
                ConnectionLog.record("Tor: creating TUN before Tor starts")
                tun = Builder()
                    .setSession(getString(R.string.app_name))
                    .applyPlatformDefaults()
                    .setMtu(tunMtu(mtuMethod()))
                    .addAddress(address.ipAddress, address.prefixLength)
                    .routeAllV4()
                    .addRoute(address.subnet, address.prefixLength)
                    // The ONLY resolver, on purpose — and the opposite of the
                    // Psiphon path, which also lists 1.1.1.1 and 8.8.8.8.
                    //
                    // There, the public resolvers exist to break a bootstrap
                    // deadlock: Psiphon must resolve CDN hostnames before its
                    // tunnel is up. Tor has no such need — every bridge here is
                    // reached by IP, or by a URL the transport resolves itself
                    // over the carrier link (our own package is off the TUN).
                    //
                    // Listing a public resolver on this path would be an actual
                    // anonymity leak: apps' DNS would go to Cloudflare in the
                    // clear, outside the circuit, revealing exactly what a Tor
                    // user is browsing. Tor's DNSPort is the only resolver.
                    //
                    // This holds for the chained run too. The outer WARP leg
                    // resolves its own gateway names in the core, off the TUN, so
                    // it needs nothing added here either.
                    .addDnsServer(address.router)
                    .applySplitTunneling()
                    .establish() ?: error("Android could not establish the VPN interface")
                vpnModeActive.set(true)

                // --- Outer leg, when armed: WARP first, then Tor inside it ---
                var outer: String? = null
                if (chained) {
                    TunnelEngine.attach(this)
                    outer = raiseOuterLeg(inner = "Tor") ?: error(
                        if (CoreConfig.chainOuterIsAuto(this, forTor = true)) {
                            getString(R.string.chain_no_outer_tor)
                        } else {
                            val pinned = CoreConfig.chainOuterLabel(
                                CoreConfig.chainOuterCandidates(this, forTor = true).first()
                            )
                            getString(R.string.chain_pinned_failed_tor, pinned)
                        }
                    )
                    // Only now, once a rung is committed and its listener accepts:
                    // arming the proxy earlier would have Tor write Socks5Proxy
                    // pointing at a port with nothing behind it.
                    TorManager.useUpstreamProxy("127.0.0.1:${CoreConfig.chainSocksPort(this)}")
                    ConnectionLog.record("Chain: outer leg ready — bootstrapping Tor through $outer")
                } else {
                    TorManager.useUpstreamProxy(null)
                }

                sendStatus(
                    STATUS_CONNECTING,
                    if (outer != null) "Starting Tor through $outer…" else "Starting Tor…",
                    5,
                )
                ConnectionLog.record("Tor: TUN ready — bootstrapping")
                // Tor is the one transport that reports genuine progress, so the
                // percentage under "Connecting" is its own bootstrap figure.
                startTorProgressPolling()

                if (!TorManager.start(this)) {
                    error(
                        when {
                            // Inside the chain only Direct and Meek are tried, so
                            // "any method" would overstate what was attempted and
                            // send the user looking for a network fault. Disarming
                            // the chain is the actionable next step, since obfs4
                            // and Snowflake are available unchained.
                            outer != null -> "Tor could not connect through $outer; " +
                                "turn Tor over WARP off to try obfs4 and Snowflake"
                            TorManager.selectedMode(this) == TorManager.TorMode.AUTO ->
                                "Tor could not connect with any method on this network"
                            // Name the pinned mode: the fix is to change it or
                            // switch to Auto, not to retry the same thing.
                            else -> "Tor could not connect over " +
                                "${TorManager.selectedMode(this).label}; try Auto"
                        }
                    )
                }
                stopTorProgressPolling()

                val mode = TorManager.activeMode?.label ?: "Tor"
                activeSocksPort = TorManager.FRONT_SOCKS_PORT
                // dnsOnlyUdpgw: Tor is TCP-only, so SocksFront answers DNS and
                // discards every other UDP flow. Feeding those flows to udpgw
                // anyway burned one of its 256 never-expiring conids each, and
                // once the table saturated (a few minutes of QUIC-heavy traffic,
                // e.g. speed tests) DNS replies came back on rebinded conids and
                // were rejected as "wrong remote address" — name resolution died
                // mid-session while the tunnel itself was still healthy.
                if (!Tun2SocksManager.start(tun!!, TorManager.FRONT_SOCKS_PORT, dnsOnlyUdpgw = true,
                        mtu = tunMtu(mtuMethod()))) {
                    error("Could not start device routing")
                }

                currentVpnIp = ""
                val via = if (outer != null) "$mode over $outer" else mode
                sendStatus(STATUS_CONNECTED, "Tor connected via $via")
                ConnectionLog.record("Tor: connected via $via")
                // The mode is only known now, and it is part of the notification's
                // subtitle ("Tor (Meek)").
                repostNotification()
                startTrafficPolling()
                startWatchdog()
            } catch (e: Exception) {
                stopTorProgressPolling()
                ConnectionLog.record("Tor start failed: ${e.message}")
                TorManager.stop()
                // The outer leg is the service's to stop; TorManager only owns tor
                // and its PT. Left running it would hold the core and make the next
                // connect fail with "already running".
                if (chained) stopOuterLeg()
                chainMode = false
                failAndStop(e.message ?: "Tor start failed")
            }
        }
    }

    // ------------------------------------------------------ «وارپ» (standalone WARP)

    /**
     * «وارپ»: plain Cloudflare WARP on an engine of its own (see CfWarpEngine) -- the desktop's
     * warp-manager.js. The identity is its own and made with WARP switched on; the data plane is
     * Aether's WireGuard with the handshake obfuscated; "connected" is Cloudflare's trace page
     * answering `warp=on` through it.
     *
     * The TUN is opened only after that proof, the way the Aether path in MyVpnService does it: an
     * endpoint scan can take a minute, and during it the phone keeps its ordinary connection
     * instead of sitting behind a tunnel that carries nothing yet.
     */
    private fun startCfWarpTunnel() {
        cfwarpMode = true
        cfwarpDead = 0
        runOnWorker {
            try {
                if (!com.mlmvpn.core.warp.CfWarpEngine.available(this)) error(getString(R.string.cfwarp_not_installed))
                sendStatus(STATUS_CONNECTING, getString(R.string.cfwarp_stage_identity), 5)
                com.mlmvpn.core.warp.CfWarpEngine.ensureIdentity(this) { stopRequested.get() }
                if (stopRequested.get()) return@runOnWorker
                com.mlmvpn.core.warp.CfWarpEngine.onUnexpectedExit = { code -> onCfWarpDied(code) }
                val trace = startCfWarpVerified()
                if (stopRequested.get()) return@runOnWorker
                if (trace == null) error(cfWarpNoEndpointMessage())

                val mtu = tunMtu(com.mlmvpn.scanner.utils.NetworkSettings.Method.CFWARP)
                tun = Builder()
                    .setSession(getString(R.string.app_name))
                    .applyPlatformDefaults()
                    .setMtu(mtu)
                    .addAddress("10.0.0.2", 32)
                    .routeAllV4()
                    .apply {
                        // IPv6 into the tunnel too (tun2proxy carries it); below 1280 no v6
                        // address fits, and adding nothing of the family makes Android block it.
                        if (mtu >= IPV6_MIN_MTU) {
                            addAddress("fd00:1:2:3:4:5:6:2", 128)
                            routeAllV6()
                        }
                    }
                    .addDnsServer("1.1.1.1")
                    .applySplitTunneling()
                    .establish() ?: error("Android could not establish the VPN interface")
                vpnModeActive.set(true)
                // «کشور خروجی با پراکسی رایگان»: the free proxy sits in a front between the device's
                // routing and WARP's port; names reach it unresolved (tun2proxy's virtual DNS), so the
                // proxy resolves them where it is.
                val exitOn = com.mlmvpn.core.warp.Ipv4Exits.isOn(this)
                if (exitOn) {
                    cfwarpFront = SocksFront.start(
                        listenPort = SocksFront.FRONT_PORT,
                        socksPort = com.mlmvpn.core.warp.CfWarpEngine.SOCKS_PORT,
                        dnsPort = 0,
                        dnsTcpResolver = "${com.mlmvpn.scanner.utils.NetworkSettings.backendDns(this)}:53",
                        exit = ipv4ExitHop(),
                    )
                    if (!cfwarpFront) error("Could not start the local proxy front-end")
                }
                val routedPort = if (cfwarpFront) SocksFront.FRONT_PORT else com.mlmvpn.core.warp.CfWarpEngine.SOCKS_PORT
                val args = "tun2proxy --proxy socks5://127.0.0.1:$routedPort " +
                    "--dns virtual --verbosity warn --close-fd-on-drop false"
                if (!com.mlmvpn.scanner.tun.Tun2proxyClient.start(this, tun!!.fd, args, mtu)) {
                    error("Could not start device routing")
                }
                applyCfWarpTrace(trace)
                activeSocksPort = routedPort
                currentVpnIp = ""
                cfwarpRxBase = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid()).coerceAtLeast(0L)
                cfwarpTxBase = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid()).coerceAtLeast(0L)
                sendStatus(STATUS_CONNECTED)
                ConnectionLog.record("WARP: connected (colo ${trace.colo}, ${trace.loc})")
                repostNotification()
                startCfWarpMonitor()
                startWatchdog()
                com.mlmvpn.core.warp.WarpVia.register(WARP_VIA_CFWARP, com.mlmvpn.core.warp.CfWarpEngine.SOCKS_PORT)
                if (cfwarpFront) startIpv4ExitSession(SocksFront.FRONT_PORT, com.mlmvpn.core.warp.CfWarpEngine.SOCKS_PORT)
            } catch (e: Exception) {
                ConnectionLog.record("WARP start failed: ${e.message}")
                com.mlmvpn.core.warp.CfWarpEngine.onUnexpectedExit = null
                com.mlmvpn.scanner.tun.Tun2proxyClient.stop(this)
                com.mlmvpn.core.warp.CfWarpEngine.stop()
                failAndStop(e.message ?: getString(R.string.cfwarp_no_endpoint))
            }
        }
    }

    /**
     * Starts «وارپ»'s engine and waits for traffic to really cross.
     *
     * Direct: one run that scans, with the whole budget. With an exit country: each of that
     * country's NAT64 gateways in turn, best first, each with a short budget -- a pinned endpoint
     * either carries within seconds or not at all, so waiting the scan's two and a half minutes on
     * a dead gateway would only delay the next one. A gateway that works is remembered as good,
     * one that does not goes to the back of the line for next time.
     */
    private fun startCfWarpVerified(): com.mlmvpn.core.warp.CfWarpEngine.Trace? {
        val share = com.mlmvpn.scanner.lan.LanShare.sharingActive(this)
        val nat64 = com.mlmvpn.core.warp.Nat64Exits
        if (nat64.isDirect(this)) {
            com.mlmvpn.core.warp.CfWarpEngine.start(this, share, peer = null)
            return com.mlmvpn.core.warp.CfWarpEngine.awaitVerified(
                CFWG_CONNECT_BUDGET_MS, cancelled = { stopRequested.get() },
            ) { percent, text -> sendStatus(STATUS_CONNECTING, text, percent) }
        }
        if (!com.mlmvpn.scanner.data.CfFamily.hasIpv6Route()) error(getString(R.string.nat64_no_ipv6))
        val endpoints = nat64.candidates(this)
            .mapNotNull { nat64.endpoint(it, com.mlmvpn.core.warp.Nat64Exits.Proto.WG) }
            .take(NAT64_MAX_GATEWAYS)
        for ((i, peer) in endpoints.withIndex()) {
            if (stopRequested.get()) return null
            ConnectionLog.record("WARP: exit country through NAT64 gateway ${i + 1}/${endpoints.size} ($peer)")
            com.mlmvpn.core.warp.CfWarpEngine.start(this, share, peer = peer)
            val trace = com.mlmvpn.core.warp.CfWarpEngine.awaitVerified(
                NAT64_GATEWAY_BUDGET_MS, cancelled = { stopRequested.get() },
            ) { percent, text -> sendStatus(STATUS_CONNECTING, text, percent) }
            nat64.reportOutcome(this, peer, ok = trace != null)
            if (trace != null) return trace
            ConnectionLog.record("WARP: NAT64 gateway $peer carried nothing; trying the next")
        }
        return null
    }

    private fun cfWarpNoEndpointMessage(): String =
        if (com.mlmvpn.core.warp.Nat64Exits.isDirect(this)) getString(R.string.cfwarp_no_endpoint)
        else getString(R.string.nat64_country_failed)

    /**
     * Where WARP comes out. A WARP exit is assigned near the user and can geolocate to Iran --
     * that is the truth, not a leak (see TunnelExitLocator) -- so the Cloudflare colo is shown
     * beside it.
     */
    private fun applyCfWarpTrace(t: com.mlmvpn.core.warp.CfWarpEngine.Trace) {
        currentCountryCode = t.loc?.takeIf { it.length == 2 }.orEmpty()
        currentCountry = listOfNotNull(t.loc, t.colo?.let { "Cloudflare $it" }).joinToString(" · ")
        t.ip?.let {
            measuredExitIp = it
            lastExitIp = it
        }
    }

    private fun onCfWarpDied(code: Int) {
        if (stopRequested.get() || !cfwarpMode) return
        runOnWorker { restartCfWarpEngine("the WARP engine exited (code $code)", dead = false) }
    }

    /** A new network: prove the endpoint still carries from here, a few seconds after the switch. */
    private fun onCfWarpNetworkChange() {
        if (stopRequested.get() || !connected.get()) return
        ladderScheduler.schedule({
            runOnWorker {
                if (!cfwarpMode || stopRequested.get() || cfwarpRestarting.get()) return@runOnWorker
                if (com.mlmvpn.core.warp.CfWarpEngine.verify(9_000)?.warpOn != true) {
                    restartCfWarpEngine("the network changed", dead = true)
                }
            }
        }, 3, TimeUnit.SECONDS)
    }

    /**
     * A new engine behind the same TUN and the same tun2proxy -- they keep pointing at the same
     * port, so nothing reaches the bare link while it comes back. [dead] also forgets the endpoint
     * the engine would retry first, so the scan lands somewhere else.
     */
    private fun restartCfWarpEngine(reason: String, dead: Boolean) {
        if (stopRequested.get() || !cfwarpMode) return
        if (!cfwarpRestarting.compareAndSet(false, true)) return
        try {
            cfwarpDead++
            if (cfwarpDead > CFWG_MAX_DEAD) {
                ConnectionLog.record("WARP: $CFWG_MAX_DEAD replacements in a row — handing over to reconnect")
                cfwarpRestarting.set(false)
                onTunnelLost(reason)
                return
            }
            ConnectionLog.record("WARP: $reason — ${if (dead) "finding another endpoint" else "restarting"} (the TUN stays up)")
            sendStatus(STATUS_CONNECTING, getString(R.string.cfwarp_status_replacing))
            if (dead) {
                com.mlmvpn.core.warp.CfWarpEngine.dropLastEndpoint(this)
                com.mlmvpn.core.warp.Nat64Exits.reportOutcome(this, com.mlmvpn.core.warp.CfWarpEngine.currentPeer, ok = false)
            }
            val trace = startCfWarpVerified()
            if (stopRequested.get()) return
            if (trace == null) error(cfWarpNoEndpointMessage())
            applyCfWarpTrace(trace)
            sendStatus(STATUS_CONNECTED)
        } catch (e: Exception) {
            ConnectionLog.record("WARP restart failed: ${e.message}")
            if (!stopRequested.get() && cfwarpMode) {
                ladderScheduler.schedule({ runOnWorker { restartCfWarpEngine("retry after: ${e.message}", dead = true) } }, 5, TimeUnit.SECONDS)
            }
        } finally {
            cfwarpRestarting.set(false)
        }
    }

    /** Traffic once a second; the real-request proof every [CFWG_VERIFY_EVERY_S]. */
    private fun startCfWarpMonitor() {
        stopCfWarpMonitor()
        var tick = 0L
        var healthySince = SystemClock.elapsedRealtime()
        cfwarpMonitorTask = ladderScheduler.scheduleAtFixedRate({
            try {
                if (stopRequested.get() || !cfwarpMode || cfwarpRestarting.get()) return@scheduleAtFixedRate
                tick++
                val uid = android.os.Process.myUid()
                val rx = android.net.TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L) - cfwarpRxBase
                val tx = android.net.TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L) - cfwarpTxBase
                updateTrafficNotification(tx.coerceAtLeast(0L), rx.coerceAtLeast(0L))
                if (SystemClock.elapsedRealtime() - healthySince > 180_000) cfwarpDead = 0
                if (tick % CFWG_VERIFY_EVERY_S != 0L) return@scheduleAtFixedRate
                // The proof is a real request through the tunnel, and each one switches the radio
                // on. With the screen off one in four is still made -- a dead endpoint is replaced
                // within a few minutes, so messages keep arriving -- instead of one every 45 s.
                if (!com.mlmvpn.scanner.utils.ScreenState.on.value &&
                    (tick / CFWG_VERIFY_EVERY_S) % CFWG_VERIFY_SCREEN_OFF_EVERY != 0L
                ) return@scheduleAtFixedRate
                runOnWorker {
                    if (!cfwarpMode || stopRequested.get() || cfwarpRestarting.get()) return@runOnWorker
                    val ok = com.mlmvpn.core.warp.CfWarpEngine.verify(9_000)?.warpOn == true ||
                        com.mlmvpn.core.warp.CfWarpEngine.verify(9_000)?.warpOn == true
                    if (ok) {
                        if (lastStatus != STATUS_CONNECTED) sendStatus(STATUS_CONNECTED)
                    } else {
                        healthySince = SystemClock.elapsedRealtime()
                        restartCfWarpEngine("the endpoint stopped carrying traffic", dead = true)
                    }
                }
            } catch (_: Exception) {
            }
        }, 1L, 1L, TimeUnit.SECONDS)
    }

    private fun stopCfWarpMonitor() {
        cfwarpMonitorTask?.cancel(false)
        cfwarpMonitorTask = null
    }

    // ------------------------------------------------------ «URnetwork»

    @Volatile private var urnetworkMode = false
    /** The tunnel is up: the TUN went to a core and data went through it. A start in progress is not. */
    @Volatile private var urnetworkUp = false
    private val urnetworkRestarting = AtomicBoolean(false)
    @Volatile private var urnetworkDead = 0
    private var urnetworkMonitorTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var urnetworkRxBase = 0L
    private var urnetworkTxBase = 0L
    /** What the core's routing rules were last written from (see UrnetworkEngine.rulesKey). */
    @Volatile private var urnetworkRulesKey = ""
    /** Since when the data checks fail in a row (0 = they pass). */
    @Volatile private var urnetworkStalledSince = 0L

    /**
     * «URnetwork»: its own core (URnetwork's SDK) carries the TUN on the SDK's packet path -- the
     * path URnetwork's official apps use: no tun2proxy and no SOCKS hop, so UDP, QUIC and IPv6 go
     * through as the providers allow. Like «لنترن», the TUN opens only after real data went through
     * the providers (TLS handshakes to two filtered sites through the core's own SOCKS port), so a
     * network that cannot reach URnetwork leaves the phone on its ordinary connection.
     *
     * The TUN gets the addresses and the resolver the SDK chose for this device -- plain DNS to that
     * resolver is upgraded to DoH inside the tunnel, which is where URnetwork's ad blocker works too --
     * the app's MTU for the method (URnetwork's own 1280 unless the user set one), the smart-Iran
     * routes and the split tunnel; then the descriptor goes to the core over its unix socket.
     */
    private fun startUrnetworkTunnel() {
        urnetworkMode = true
        urnetworkUp = false
        urnetworkDead = 0
        urnetworkStalledSince = 0L
        runOnWorker {
            val U = com.mlmvpn.core.urnetwork.UrnetworkEngine
            val S = com.mlmvpn.core.urnetwork.UrnetworkSettings
            try {
                urnetworkRulesKey = U.rulesKey(this)
                U.beginSession(S.locationName(this), S.blocker(this))
                sendStatus(STATUS_CONNECTING, tr("URnetwork: راه‌اندازی هسته…", "URnetwork: starting the core…"), 5)
                val proc = startUrnetworkVerified(first = true)
                if (proc == null) {
                    if (stopRequested.get()) return@runOnWorker
                    error(U.live.value.error ?: tr("URnetwork وصل نشد.", "URnetwork did not connect."))
                }
                if (stopRequested.get()) return@runOnWorker
                U.setPhase(com.mlmvpn.core.urnetwork.UrnetworkEngine.Phase.TUNNEL)
                U.record(tr("ساخت تونل گوشی", "Building the phone's tunnel"))
                sendStatus(STATUS_CONNECTING, tr("URnetwork: ساخت تونل گوشی…", "URnetwork: building the phone's tunnel…"), 92)
                val addr = proc.tunAddr ?: error("URnetwork gave no tunnel address")
                tun = buildUrnetworkTun(addr) ?: error("Android could not establish the VPN interface")
                vpnModeActive.set(true)
                if (!proc.handTun(tun!!.fileDescriptor)) {
                    error(tr("تونل به هستهٔ URnetwork سپرده نشد.", "The tunnel could not be handed to URnetwork's core."))
                }
                activeSocksPort = proc.socksPort
                currentVpnIp = ""
                applyUrnetworkExit()
                urnetworkRxBase = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid()).coerceAtLeast(0L)
                urnetworkTxBase = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid()).coerceAtLeast(0L)
                U.setPhase(com.mlmvpn.core.urnetwork.UrnetworkEngine.Phase.CONNECTED)
                U.record(urnetworkConnectedLine())
                urnetworkUp = true
                sendStatus(STATUS_CONNECTED)
                ConnectionLog.record("URnetwork: connected through ${U.live.value.providers} providers")
                repostNotification()
                startUrnetworkMonitor()
                startWatchdog()
            } catch (e: Exception) {
                ConnectionLog.record("URnetwork start failed: ${e.message}")
                stopUrnetworkMonitor()
                U.stopTile()
                U.setError(e.message ?: tr("URnetwork وصل نشد.", "URnetwork did not connect."))
                failAndStop(e.message ?: tr("URnetwork وصل نشد.", "URnetwork did not connect."))
            }
        }
    }

    /**
     * A core up and proven: its port and the TUN's addresses, a first provider in the window (the
     * screen counts them while it waits), then real TLS handshakes to two filtered sites through it.
     * Null on failure, with the reason in the live state. [first]: the user's own connect, which
     * reports every step on the dial; a restart behind a live TUN keeps the dial as it is.
     */
    private fun startUrnetworkVerified(first: Boolean): com.mlmvpn.core.urnetwork.UrnetworkProcess? {
        val U = com.mlmvpn.core.urnetwork.UrnetworkEngine
        var signIns = 0
        while (true) {
            val proc = U.startTile(this) { code -> onUrnetworkDied(code) }
            if (!proc.awaitReady(URNETWORK_READY_MS) { stopRequested.get() }) {
                if (urnetworkSignInAgain(proc, ++signIns)) continue
                if (!stopRequested.get()) U.setError(urnetworkExitReason(proc) ?: tr("هستهٔ URnetwork آماده نشد.", "URnetwork's core did not come up."))
                U.stopTile()
                return null
            }
            if (first) sendStatus(STATUS_CONNECTING, tr("URnetwork: یافتن ارائه‌دهنده‌ها…", "URnetwork: finding providers…"), 20)
            val deadline = SystemClock.elapsedRealtime() + URNETWORK_PROVIDERS_MS
            var said = ""
            while (SystemClock.elapsedRealtime() < deadline) {
                if (stopRequested.get()) { U.stopTile(); return null }
                if (proc.added > 0) break
                if (!proc.isAlive || proc.insufficient || proc.refused != null || proc.loggedOut) break
                if (first) {
                    val line = urnetworkWindowLine()
                    if (line != said) {
                        said = line
                        val w = U.live.value.window
                        val pct = if (w.target > 0) 20 + (40 * (w.evaluating + w.added).coerceAtMost(w.target) / w.target) else 25
                        sendStatus(STATUS_CONNECTING, "URnetwork: $line", pct)
                    }
                }
                Thread.sleep(250)
            }
            if (proc.added == 0) {
                if (urnetworkSignInAgain(proc, ++signIns)) continue
                if (!stopRequested.get()) U.setError(urnetworkExitReason(proc) ?: U.failureReason())
                U.stopTile()
                return null
            }
            U.setPhase(com.mlmvpn.core.urnetwork.UrnetworkEngine.Phase.TESTING)
            U.record(tr("آزمایش عبور داده از ارائه‌دهنده‌ها", "Testing that data passes through the providers"))
            if (first) sendStatus(STATUS_CONNECTING, tr("URnetwork: ${U.fa(U.live.value.window.added)} ارائه‌دهنده وصل شد · آزمایش عبور داده…",
                "URnetwork: ${U.live.value.window.added} providers connected · testing the data path…"), 75)
            if (!com.mlmvpn.core.lantern.LanternEngine.carriesWarm(proc.socksPort, URNETWORK_DATA_MS) { stopRequested.get() || !proc.isAlive }) {
                if (!stopRequested.get()) U.setError(urnetworkExitReason(proc) ?: if (U.live.value.insufficient) U.failureReason() else tr(
                    "ارائه‌دهنده‌ها وصل شدند ولی در ${U.fa((URNETWORK_DATA_MS / 1000).toInt())} ثانیه داده‌ای از آن‌ها رد نشد. یک بار دیگر وصل شوید یا مکان دیگری انتخاب کنید.",
                    "Providers connected but no data went through them in ${URNETWORK_DATA_MS / 1000} s. Connect once more, or choose another location."))
                U.stopTile()
                return null
            }
            U.record(tr("داده از ارائه‌دهنده‌ها رد شد", "Data went through the providers"))
            return proc
        }
    }

    /**
     * A core that ended on a refused sign-in gets one more start, twice at most: after the SDK cleared
     * a refused device of its own (the next start registers a new one), or after the account signed
     * in again with the recovery phrase kept on the phone when its own token was refused.
     */
    private fun urnetworkSignInAgain(proc: com.mlmvpn.core.urnetwork.UrnetworkProcess, tries: Int): Boolean {
        val U = com.mlmvpn.core.urnetwork.UrnetworkEngine
        if (stopRequested.get() || tries > 2) return false
        // The exit lands a moment after the line that announced it.
        val until = SystemClock.elapsedRealtime() + 2_000
        while (proc.exitCode == null && proc.isAlive && SystemClock.elapsedRealtime() < until) Thread.sleep(50)
        if (!proc.loggedOut && proc.exitCode != U.EXIT_LOGGED_OUT) return false
        if (proc.accountRefused) {
            U.record(tr("URnetwork ورود حساب را نپذیرفت — ورود دوباره با عبارت بازیابی", "URnetwork refused the account's sign-in -- signing in again with the recovery phrase"))
            sendStatus(STATUS_CONNECTING, tr("URnetwork: ورود دوباره به حساب…", "URnetwork: signing in again…"))
            if (!U.recover(this, proc)) return false
        } else {
            U.record(tr("ثبت دوبارهٔ این گوشی در حساب", "Registering this phone in the account again"))
        }
        return !stopRequested.get()
    }

    /** "۸ در حال بررسی · ۳ وصل از ۸" -- the window as the official app counts it. */
    private fun urnetworkWindowLine(): String {
        val U = com.mlmvpn.core.urnetwork.UrnetworkEngine
        val w = U.live.value.window
        return when {
            w.target == 0 && w.evaluating == 0 -> tr("یافتن ارائه‌دهنده‌ها…", "finding providers…")
            else -> tr("${U.fa(w.evaluating)} در حال بررسی · ${U.fa(w.added)} وصل از ${U.fa(w.target)}",
                "${w.evaluating} being checked · ${w.added} of ${w.target} connected")
        }
    }

    private fun urnetworkConnectedLine(): String {
        val U = com.mlmvpn.core.urnetwork.UrnetworkEngine
        val l = U.live.value
        val n = maxOf(l.providers, l.window.added)
        val where = l.countries.filter { it.code.length == 2 }.take(4).joinToString("، ") { it.code.uppercase() }
        return tr("وصل شد — از راه ${U.fa(n)} ارائه‌دهنده" + (if (where.isNotBlank()) " ($where)" else ""),
            "Connected -- through $n providers" + (if (where.isNotBlank()) " ($where)" else ""))
    }

    /** Why a core gave up, from how it ended: the account, the platform's refusal, its last error. */
    private fun urnetworkExitReason(proc: com.mlmvpn.core.urnetwork.UrnetworkProcess): String? {
        val U = com.mlmvpn.core.urnetwork.UrnetworkEngine
        return when (proc.exitCode) {
            U.EXIT_NO_ACCOUNT -> U.unavailableReason(this)
            U.EXIT_REFUSED -> proc.refused?.let { U.readable(it) } ?: proc.lastError?.let { U.readable(it) }
            U.EXIT_LOGGED_OUT -> U.readable("auth")
            null -> null
            else -> proc.lastError?.let { U.readable(it) }
        }
    }

    /**
     * The TUN with the SDK's addresses: its IPv4 /32, its IPv6 /64 (only from 1280 up, the least an
     * IPv6 link may carry; below it Android blocks IPv6 rather than leak it), its resolver kept
     * inside whatever the smart-Iran route takes out.
     */
    private fun buildUrnetworkTun(addr: com.mlmvpn.core.urnetwork.UrnetworkProcess.TunAddr): ParcelFileDescriptor? {
        val set = tunMtu(com.mlmvpn.scanner.utils.NetworkSettings.Method.URNETWORK)
        val mtu = set.coerceIn(URNETWORK_MIN_MTU, URNETWORK_MAX_MTU)
        if (mtu != set) ConnectionLog.record("URnetwork: tun mtu $set is outside $URNETWORK_MIN_MTU–$URNETWORK_MAX_MTU; using $mtu")
        val b = Builder()
            .setSession(getString(R.string.app_name))
            .applyPlatformDefaults()
            .setMtu(mtu)
            .addAddress(addr.v4, 32)
        SmartIranRoute.routeAllV4(this, b, keep = addr.dns4)
        val v6 = mtu >= IPV6_MIN_MTU && addr.v6.isNotBlank()
        if (v6) {
            b.addAddress(addr.v6, addr.prefix6)
            SmartIranRoute.routeAllV6(this, b)
        }
        addr.dns4.forEach { b.addDnsServer(it) }
        if (v6) addr.dns6.forEach { b.addDnsServer(it) }
        b.applySplitTunneling()
        return b.establish()
    }

    /**
     * Where URnetwork comes out, for the notification and the screen: the chosen country when the
     * user picked one, else the providers' countries -- the exit is many places at once.
     */
    private fun applyUrnetworkExit() {
        val U = com.mlmvpn.core.urnetwork.UrnetworkEngine
        val l = U.live.value
        val chosen = com.mlmvpn.core.urnetwork.UrnetworkSettings.locationCode(this)?.uppercase()
        val n = maxOf(l.providers, l.window.added)
        val codes = l.countries.filter { it.code.length == 2 }.map { it.code.uppercase() }
        currentCountryCode = chosen?.takeIf { it.length == 2 } ?: codes.singleOrNull().orEmpty()
        currentCountry = tr("${U.fa(n)} ارائه‌دهنده", "$n providers") +
            codes.take(4).joinToString(", ").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    }

    private fun onUrnetworkDied(code: Int) {
        // A start in progress reads its own core's end; only a live tunnel restarts it here.
        if (stopRequested.get() || !urnetworkMode || !urnetworkUp || urnetworkRestarting.get()) return
        val U = com.mlmvpn.core.urnetwork.UrnetworkEngine
        if (code == U.EXIT_NO_ACCOUNT || code == U.EXIT_REFUSED) {
            val why = U.tile?.let { urnetworkExitReason(it) } ?: U.unavailableReason(this) ?: tr("URnetwork قطع شد.", "URnetwork disconnected.")
            runOnWorker { urnetworkGiveUp(why, retry = false) }
            return
        }
        runOnWorker { restartUrnetworkCore(tr("هسته بسته شد (کد ${U.fa(code)})", "the core exited (code $code)")) }
    }

    /**
     * A new core behind the same TUN: the TUN stays up throughout, so nothing reaches the bare link
     * while it comes back; the new core gets the same descriptor.
     */
    private fun restartUrnetworkCore(reason: String) {
        if (stopRequested.get() || !urnetworkMode) return
        if (!urnetworkRestarting.compareAndSet(false, true)) return
        val U = com.mlmvpn.core.urnetwork.UrnetworkEngine
        try {
            urnetworkDead++
            if (urnetworkDead > URNETWORK_MAX_DEAD) {
                ConnectionLog.record("URnetwork: $URNETWORK_MAX_DEAD restarts in a row — handing over to reconnect")
                urnetworkRestarting.set(false)
                urnetworkGiveUp(reason, retry = true)
                return
            }
            U.setPhase(com.mlmvpn.core.urnetwork.UrnetworkEngine.Phase.RECOVERING)
            U.record(tr("راه‌اندازی دوبارهٔ هسته (تونل می‌ماند): ", "Restarting the core (the tunnel stays): ") + reason)
            sendStatus(STATUS_CONNECTING, tr("URnetwork: راه‌اندازی دوبارهٔ هسته…", "URnetwork: restarting the core…"))
            val proc = startUrnetworkVerified(first = false)
                ?: error(U.live.value.error ?: tr("هسته دوباره بالا نیامد", "the core did not come back"))
            val t = tun ?: error("the tunnel is gone")
            if (!proc.handTun(t.fileDescriptor)) error(tr("تونل به هستهٔ تازه سپرده نشد", "the tunnel could not be handed to the new core"))
            activeSocksPort = proc.socksPort
            applyUrnetworkExit()
            U.setPhase(com.mlmvpn.core.urnetwork.UrnetworkEngine.Phase.CONNECTED)
            U.record(urnetworkConnectedLine())
            sendStatus(STATUS_CONNECTED)
        } catch (e: Exception) {
            ConnectionLog.record("URnetwork restart failed: ${e.message}")
            if (!stopRequested.get() && urnetworkMode) {
                // A used-up balance does not come back by trying again: say so and let the phone go.
                if (U.live.value.insufficient) {
                    urnetworkRestarting.set(false)
                    urnetworkGiveUp(U.failureReason(), retry = false)
                    return
                }
                val again = tr("تلاش دوباره، چون بار قبل نشد: ", "trying again, the last attempt failed: ") + e.message.orEmpty()
                ladderScheduler.schedule({ runOnWorker { restartUrnetworkCore(again) } }, 5, TimeUnit.SECONDS)
            }
        } finally {
            urnetworkRestarting.set(false)
        }
    }

    /**
     * Traffic once a second; the providers as the window reports them; a real handshake through the
     * core every [URNETWORK_VERIFY_EVERY_S].
     */
    private fun startUrnetworkMonitor() {
        stopUrnetworkMonitor()
        var tick = 0L
        var healthySince = SystemClock.elapsedRealtime()
        var brokeAt = 0L
        urnetworkMonitorTask = ladderScheduler.scheduleAtFixedRate({
            try {
                if (stopRequested.get() || !urnetworkMode || urnetworkRestarting.get()) return@scheduleAtFixedRate
                val U = com.mlmvpn.core.urnetwork.UrnetworkEngine
                tick++
                val uid = android.os.Process.myUid()
                val rx = android.net.TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L) - urnetworkRxBase
                val tx = android.net.TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L) - urnetworkTxBase
                updateTrafficNotification(tx.coerceAtLeast(0L), rx.coerceAtLeast(0L))
                if (SystemClock.elapsedRealtime() - healthySince > 180_000) urnetworkDead = 0
                if (tick % 5 == 0L) {
                    applyUrnetworkExit()
                    // Smart Iran or a per-destination list changed in Settings: the core takes the
                    // new rules now (the VPN builder's own routes follow at the next connect).
                    val key = U.rulesKey(this)
                    if (key != urnetworkRulesKey) {
                        urnetworkRulesKey = key
                        runOnWorker { U.rulesChanged(this) }
                    }
                }
                val live = U.live.value
                // The balance ran out mid-session: nothing carries any more, and trying again cannot
                // change that. Fifteen seconds of grace for a reading that corrects itself.
                if (live.insufficient) {
                    if (brokeAt == 0L) brokeAt = SystemClock.elapsedRealtime()
                    if (SystemClock.elapsedRealtime() - brokeAt > 15_000) {
                        runOnWorker { if (urnetworkMode && !stopRequested.get()) urnetworkGiveUp(U.failureReason(), retry = false) }
                        return@scheduleAtFixedRate
                    }
                } else brokeAt = 0L
                // Every provider left the window: the SDK fills it again by itself; the dial says so meanwhile.
                if (live.window.added == 0) {
                    if (lastStatus == STATUS_CONNECTED) {
                        U.setPhase(com.mlmvpn.core.urnetwork.UrnetworkEngine.Phase.RECOVERING)
                        sendStatus(STATUS_CONNECTING, tr("URnetwork: گرفتن دوبارهٔ ارائه‌دهنده‌ها…", "URnetwork: getting providers again…"))
                    }
                } else if (lastStatus != STATUS_CONNECTED) {
                    U.setPhase(com.mlmvpn.core.urnetwork.UrnetworkEngine.Phase.CONNECTED)
                    sendStatus(STATUS_CONNECTED)
                }
                if (tick % URNETWORK_VERIFY_EVERY_S != 0L) return@scheduleAtFixedRate
                // With the screen off one in four is still made, as for «وارپ»: each switches the radio on.
                if (!com.mlmvpn.scanner.utils.ScreenState.on.value &&
                    (tick / URNETWORK_VERIFY_EVERY_S) % CFWG_VERIFY_SCREEN_OFF_EVERY != 0L
                ) return@scheduleAtFixedRate
                runOnWorker {
                    if (!urnetworkMode || stopRequested.get() || urnetworkRestarting.get()) return@runOnWorker
                    val port = U.tile?.socksPort ?: return@runOnWorker
                    val L = com.mlmvpn.core.lantern.LanternEngine
                    val ok = L.carriesStream(port, "www.youtube.com", 12_000) || L.carriesStream(port, "www.instagram.com", 12_000)
                    if (ok) {
                        urnetworkStalledSince = 0L
                        U.dataRestored()
                        if (lastStatus != STATUS_CONNECTED && U.live.value.window.added > 0) sendStatus(STATUS_CONNECTED)
                    } else if (!U.live.value.insufficient) {
                        // As the official app recovers: the SDK holds its providers through a lost
                        // server connection, so it is asked to redial that connection and to check
                        // every exit again -- never rebuilt, which would throw the window away
                        // (that turned short outages into minutes on the owner's phone).
                        val now = SystemClock.elapsedRealtime()
                        if (urnetworkStalledSince == 0L) urnetworkStalledSince = now
                        if (now - urnetworkStalledSince >= URNETWORK_STUCK_MS) {
                            urnetworkStalledSince = 0L
                            healthySince = now
                            restartUrnetworkCore(tr("مسیر داده ۱۰ دقیقه جواب نداد", "the data path did not answer for 10 minutes"))
                        } else {
                            U.recoverTransports()
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }, 1L, 1L, TimeUnit.SECONDS)
    }

    private fun stopUrnetworkMonitor() {
        urnetworkMonitorTask?.cancel(false)
        urnetworkMonitorTask = null
    }

    /**
     * A session that was up and cannot carry any more. With the kill switch on (the transport's
     * advanced page), the apps stay behind a VPN that carries nothing rather than fall back to the
     * bare link, as the tunnel core does; otherwise [retry] hands it to the reconnect ladder, or the
     * session ends with the reason.
     */
    private fun urnetworkGiveUp(reason: String, retry: Boolean) {
        val U = com.mlmvpn.core.urnetwork.UrnetworkEngine
        val killSwitch = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("kill_switch", false)
        if (killSwitch && urnetworkUp && !stopRequested.get()) {
            ConnectionLog.record("URnetwork: kill switch active; blocking all traffic ($reason)")
            stopWatchdog()
            stopUrnetworkMonitor()
            U.record(tr("کلید قطع اضطراری: برنامه‌ها تا اتصال دوباره بی‌اینترنت می‌مانند", "Kill switch: apps stay offline until it connects again"))
            U.stopTile()
            U.setError(reason)
            urnetworkMode = false
            urnetworkUp = false
            activeSocksPort = 0
            vpnModeActive.set(false)
            connected.set(false)
            sendStatus(STATUS_FAILED, tr("کلید قطع اضطراری فعال است — ", "Kill switch active -- ") + reason)
            rebuildKillSwitchVpn()
            return
        }
        U.setError(reason)
        if (retry) onTunnelLost(reason) else failAndStop(reason)
    }

    // ------------------------------------------------------ «لنترن» (Lantern)

    /**
     * «لنترن»: Lantern 10's own core as a local proxy, the desktop's lantern-manager.js. Like «وارپ»,
     * the TUN opens only after the core has proven it carries -- a first list can take minutes on a
     * line that cannot reach Lantern's config service, and meanwhile the phone keeps its ordinary
     * connection instead of sitting behind a tunnel that carries nothing yet. Then tun2proxy with
     * virtual DNS: names reach Lantern unresolved, so its own rules (Iranian sites direct) and its
     * own resolution apply, and nothing is resolved in the clear on the phone.
     */
    private fun startLanternTunnel() {
        lanternMode = true
        lanternDead = 0
        lanternNetwork = null
        lanternDns = emptyList()
        lanternNetCheckPending.set(false)
        runOnWorker {
            val L = com.mlmvpn.core.lantern.LanternEngine
            try {
                if (!L.supported()) error(tr("لنترن روی این گوشی در دسترس نیست: هستهٔ آن فقط برای گوشی‌های ۶۴ بیتی (arm64) ساخته شده است.",
                    "Lantern is not available on this phone: its core is built for 64-bit (arm64) phones only."))
                if (!L.available(this)) error(tr("فایل هستهٔ لنترن در این نسخه نیست.", "Lantern's core is missing from this build."))
                L.onUnexpectedExit = { code -> onLanternDied(code) }
                if (!startLanternVerified(first = true)) {
                    if (stopRequested.get()) return@runOnWorker
                    error(L.live.value.error ?: tr("لنترن وصل نشد.", "Lantern did not connect."))
                }
                if (stopRequested.get()) return@runOnWorker

                val mtu = tunMtu(com.mlmvpn.scanner.utils.NetworkSettings.Method.LANTERN)
                tun = Builder()
                    .setSession(getString(R.string.app_name))
                    .applyPlatformDefaults()
                    .setMtu(mtu)
                    .addAddress("10.0.0.2", 32)
                    .routeAllV4()
                    .apply {
                        // IPv6 into the tunnel too (tun2proxy carries it); below 1280 no v6 address
                        // fits, and adding nothing of the family makes Android block it.
                        if (mtu >= IPV6_MIN_MTU) {
                            addAddress("fd00:1:2:3:4:5:6:2", 128)
                            routeAllV6()
                        }
                    }
                    // Answered by tun2proxy's virtual DNS, never sent anywhere: names go to Lantern as names.
                    .addDnsServer("1.1.1.1")
                    .applySplitTunneling()
                    .establish() ?: error("Android could not establish the VPN interface")
                vpnModeActive.set(true)
                val args = "tun2proxy --proxy socks5://127.0.0.1:${L.SOCKS_PORT} " +
                    "--dns virtual --verbosity warn --close-fd-on-drop false"
                if (!com.mlmvpn.scanner.tun.Tun2proxyClient.start(this, tun!!.fd, args, mtu)) {
                    error("Could not start device routing")
                }
                applyLanternExit()
                activeSocksPort = L.SOCKS_PORT
                currentVpnIp = ""
                lanternRxBase = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid()).coerceAtLeast(0L)
                lanternTxBase = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid()).coerceAtLeast(0L)
                sendStatus(STATUS_CONNECTED)
                ConnectionLog.record("Lantern: connected" + L.exitLabel().takeIf { it.isNotBlank() }?.let { " — exit $it" }.orEmpty())
                repostNotification()
                startLanternMonitor()
                startWatchdog()
            } catch (e: Exception) {
                ConnectionLog.record("Lantern start failed: ${e.message}")
                L.onUnexpectedExit = null
                com.mlmvpn.scanner.tun.Tun2proxyClient.stop(this)
                L.setError(e.message ?: "")
                L.stop()
                failAndStop(e.message ?: tr("لنترن وصل نشد.", "Lantern did not connect."))
            }
        }
    }

    /**
     * The core up and proven: listening (a kept list: at once; a first list: when it has come,
     * through WARP if the line cannot reach Lantern's config service on its own), then a real TLS
     * handshake through it to two different hosts. The desktop's startLantern + startOnce.
     *
     * [first]: the user's own connect, which tells its whole story; a restart behind a live TUN is
     * quieter and keeps the session's log.
     */
    private fun startLanternVerified(first: Boolean): Boolean {
        val L = com.mlmvpn.core.lantern.LanternEngine
        val smart = com.mlmvpn.scanner.ui.lantern.LanternSettings.smart(this)
        val kept = L.cachedServerCount(this)
        // What this core is started for; [onLanternNetworkChange] restarts it only when these move.
        lanternNetwork = physicalNetwork()
        val dns = L.systemDns(this)
        lanternDns = dns
        var ident = L.readIdentity(this)
        val cold = kept == 0
        if (first) L.beginSession(kept)
        if (!cold) {
            if (first) L.record(tr("${L.fa(kept)} سرور از دفعهٔ قبل ذخیره است — اتصال مستقیم، بی‌معطلی برای فهرست تازه",
                "$kept servers kept from last time -- connecting straight away, without waiting for a new list"))
            // For next time, without making anybody wait for it.
            Thread({ L.directIdentity()?.let { L.saveIdentity(this, it) } }, "lantern-identity").apply { isDaemon = true }.start()
        } else {
            L.setStage("fetching", tr("گرفتن فهرست سرورها", "Getting the server list"), awaitingList = true)
            L.record(tr("بار اول: فهرست سرورهای لنترن گرفته می‌شود", "First start: Lantern's server list is being fetched"))
            sendStatus(STATUS_CONNECTING, tr("لنترن: گرفتن فهرست سرورها…", "Lantern: getting the server list…"), 5)
            L.directIdentity()?.let { ident = it; L.saveIdentity(this, it) }
        }
        if (stopRequested.get()) return false
        L.start(this, smart, via = null, ident = ident, dns = dns)
        L.record(tr("شروع · پروکسی محلی ${L.SOCKS_PORT}", "Starting · local proxy ${L.SOCKS_PORT}"))
        if (!cold) {
            L.setStage("starting", tr("باز کردن پورت محلی", "Opening the local port"))
            sendStatus(STATUS_CONNECTING, tr("لنترن: راه‌اندازی هسته…", "Lantern: starting the core…"), 15)
            if (!L.awaitListening(LANTERN_WARM_LISTEN_MS) { stopRequested.get() }) {
                if (!stopRequested.get()) L.setError(tr("هستهٔ لنترن در زمان مقرر آماده نشد.", "Lantern's core was not ready in time."))
                return false
            }
        } else {
            val t0 = SystemClock.elapsedRealtime()
            var throughWarp = false
            var up = L.awaitListening(LANTERN_COLD_DIRECT_MS) { stopRequested.get() }
            if (!up && !stopRequested.get() && L.isAlive) {
                // Only the control plane goes through WARP, and only for this first list: Lantern's
                // own servers are dialled directly, then and every time after.
                L.record(tr("فهرست مستقیم نرسید — از راه وارپ گرفته می‌شود؛ فقط همین بار، خود ترافیک مستقیم به سرورهای لنترن می‌رود",
                    "No list directly -- getting it through WARP; only this once, the traffic itself goes straight to Lantern's servers"))
                sendStatus(STATUS_CONNECTING, tr("لنترن: گرفتن فهرست از راه وارپ…", "Lantern: getting the list through WARP…"), 25)
                up = runBlockingStoppable {
                    com.mlmvpn.core.warp.WarpVia.use(this@TunnelVpnService) { port, _ ->
                        if (port > 0) {
                            throughWarp = true
                            L.setVia(tr("وارپ", "WARP"))
                            L.command("via 127.0.0.1:$port")
                        } else {
                            L.record(tr("وارپ هم راهی پیدا نکرد — مستقیم ادامه می‌دهد", "WARP found no way in either -- carrying on directly"))
                        }
                        val left = LANTERN_COLD_GIVEUP_MS - (SystemClock.elapsedRealtime() - t0)
                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            L.awaitListening(left.coerceAtLeast(1_000L)) { stopRequested.get() }
                        }
                        // Back to direct before the helper goes: later refreshes must not point at a dead port.
                        if (port > 0) L.command("via direct")
                        L.setVia(null)
                        ok
                    }
                } ?: false
            }
            if (!up) {
                if (!stopRequested.get()) L.setError(
                    if (throughWarp) tr("لنترن در چهار دقیقه حتی از راه وارپ هم فهرست سرورهایش را نگرفت. کمی بعد دوباره امتحان کنید.",
                        "Lantern did not get its server list in four minutes, not even through WARP. Try again a little later.")
                    else tr("لنترن بار اول باید فهرست سرورهایش را از سرویس خودش بگیرد و اینترنت شما به آن نرسید؛ از راه وارپ هم نشد. کمی بعد دوباره امتحان کنید. فقط همین بار لازم است: فهرست ذخیره می‌شود و دفعه‌های بعد مستقیم و در چند ثانیه وصل می‌شود.",
                        "The first time, Lantern has to get its server list from its own service, and your internet did not reach it -- nor did WARP. Try again a little later. It is needed only once: the list is kept, and later connects go straight and take seconds."))
                return false
            }
        }
        L.setStage("testing", tr("آزمایش مسیر داده", "Testing the data path"))
        L.record(tr("پروکسی محلی آماده شد — آزمایش مسیر داده", "The local proxy is up -- testing the data path"))
        sendStatus(STATUS_CONNECTING, tr("لنترن: آزمایش مسیر داده…", "Lantern: testing the data path…"), 70)
        // THE DATA PATH DECIDES. A listener is not a connection, and a SOCKS reply is not one either.
        if (!L.carriesWarm(LANTERN_DATA_TIMEOUT_MS) { stopRequested.get() }) {
            if (!stopRequested.get()) L.setError(L.blockedMessage() ?: tr(
                "هستهٔ لنترن بالا آمد ولی هیچ سروری در ${L.fa((LANTERN_DATA_TIMEOUT_MS / 1000).toInt())} ثانیه ترافیکی رد نکرد. یک بار دیگر وصل شوید یا «فهرست سرور تازه» را بزنید.",
                "Lantern's core came up but no server carried any traffic in ${LANTERN_DATA_TIMEOUT_MS / 1000} s. Connect once more, or tap «Fresh server list»."))
            return false
        }
        L.setUp()
        L.record(tr("وصل شد", "Connected") + L.exitLabel().takeIf { it.isNotBlank() }?.let { tr(" — خروج از $it", " -- exit: $it") }.orEmpty())
        return true
    }

    /** Where Lantern comes out, for the notification and the screen, once the core has said. */
    private fun applyLanternExit() {
        val l = com.mlmvpn.core.lantern.LanternEngine.live.value
        if (l.exitCode.length == 2) currentCountryCode = l.exitCode
        com.mlmvpn.core.lantern.LanternEngine.exitLabel(l).takeIf { it.isNotBlank() }?.let { currentCountry = it }
    }

    private fun onLanternDied(code: Int) {
        if (stopRequested.get() || !lanternMode) return
        val L = com.mlmvpn.core.lantern.LanternEngine
        runOnWorker { restartLanternEngine(tr("هسته بسته شد (کد ${L.fa(code)})", "the core exited (code $code)")) }
    }

    /**
     * Something about the networks moved. The core is started for one network -- its resolvers are
     * that network's and its connections ride it -- so a different default network, or new resolvers
     * on it, means a new core, a few seconds after the switch, behind the same TUN.
     *
     * Only a real change counts. Registering the network callback announces the network the phone is
     * already on, and a phone with Wi-Fi and mobile data both up announces both; read as changes,
     * they restarted the core right after every connect (1.2.42, phone test). So this compares what
     * the phone is on now with what the running core was started on, only once the tunnel is up (a
     * connect in progress starts its core on whatever it finds), and one look covers a burst.
     */
    private fun onLanternNetworkChange() {
        if (stopRequested.get() || !connected.get()) return
        if (!lanternNetCheckPending.compareAndSet(false, true)) return
        ladderScheduler.schedule({
            lanternNetCheckPending.set(false)
            runOnWorker {
                if (!lanternMode || stopRequested.get() || lanternRestarting.get() || tun == null) return@runOnWorker
                // No network at all: nothing to start a core on; the next one to appear is checked.
                val now = physicalNetwork() ?: return@runOnWorker
                val dns = com.mlmvpn.core.lantern.LanternEngine.systemDns(this)
                if (now == lanternNetwork && dns == lanternDns) return@runOnWorker
                restartLanternEngine(tr("شبکهٔ گوشی عوض شد", "the phone's network changed"))
            }
        }, 3, TimeUnit.SECONDS)
    }

    /**
     * The real network this app's own sockets use: the default one, never a VPN. The app is outside
     * its own tunnel, so for it the default is the physical network even while the tunnel is up.
     */
    private fun physicalNetwork(): Network? = runCatching {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null
        val n = cm.activeNetwork ?: return null
        n.takeIf { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true }
    }.getOrNull()

    /**
     * A new core behind the same TUN and the same tun2proxy -- they keep pointing at the same port,
     * so nothing reaches the bare link while it comes back.
     */
    private fun restartLanternEngine(reason: String) {
        if (stopRequested.get() || !lanternMode) return
        if (!lanternRestarting.compareAndSet(false, true)) return
        try {
            lanternDead++
            if (lanternDead > LANTERN_MAX_DEAD) {
                ConnectionLog.record("Lantern: $LANTERN_MAX_DEAD restarts in a row — handing over to reconnect")
                lanternRestarting.set(false)
                onTunnelLost(reason)
                return
            }
            com.mlmvpn.core.lantern.LanternEngine.record(tr("راه‌اندازی دوبارهٔ هسته (تونل می‌ماند): ", "Restarting the core (the tunnel stays): ") + reason)
            sendStatus(STATUS_CONNECTING, tr("لنترن: راه‌اندازی دوبارهٔ هسته…", "Lantern: restarting the core…"))
            if (!startLanternVerified(first = false)) {
                if (stopRequested.get()) return
                error(com.mlmvpn.core.lantern.LanternEngine.live.value.error ?: tr("هسته دوباره بالا نیامد", "the core did not come back"))
            }
            applyLanternExit()
            sendStatus(STATUS_CONNECTED)
        } catch (e: Exception) {
            ConnectionLog.record("Lantern restart failed: ${e.message}")
            if (!stopRequested.get() && lanternMode) {
                // The page's story is in the user's language: the reason too, not only the words around it.
                val again = tr("تلاش دوباره، چون بار قبل نشد: ", "trying again, the last attempt failed: ") + e.message.orEmpty()
                ladderScheduler.schedule({ runOnWorker { restartLanternEngine(again) } }, 5, TimeUnit.SECONDS)
            }
        } finally {
            lanternRestarting.set(false)
        }
    }

    /** Traffic once a second; a real handshake through the core every [LANTERN_VERIFY_EVERY_S]. */
    private fun startLanternMonitor() {
        stopLanternMonitor()
        var tick = 0L
        var healthySince = SystemClock.elapsedRealtime()
        lanternMonitorTask = ladderScheduler.scheduleAtFixedRate({
            try {
                if (stopRequested.get() || !lanternMode || lanternRestarting.get()) return@scheduleAtFixedRate
                tick++
                val uid = android.os.Process.myUid()
                val rx = android.net.TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L) - lanternRxBase
                val tx = android.net.TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L) - lanternTxBase
                updateTrafficNotification(tx.coerceAtLeast(0L), rx.coerceAtLeast(0L))
                if (SystemClock.elapsedRealtime() - healthySince > 180_000) lanternDead = 0
                // The exit is reported once the core's auto-select picks a server; it can come late.
                if (currentCountryCode.isBlank()) applyLanternExit()
                if (tick % LANTERN_VERIFY_EVERY_S != 0L) return@scheduleAtFixedRate
                // With the screen off one in four is still made, as for «وارپ»: each switches the radio on.
                if (!com.mlmvpn.scanner.utils.ScreenState.on.value &&
                    (tick / LANTERN_VERIFY_EVERY_S) % CFWG_VERIFY_SCREEN_OFF_EVERY != 0L
                ) return@scheduleAtFixedRate
                runOnWorker {
                    if (!lanternMode || stopRequested.get() || lanternRestarting.get()) return@runOnWorker
                    val L = com.mlmvpn.core.lantern.LanternEngine
                    val ok = L.carriesStream("www.youtube.com", 12_000) || L.carriesStream("www.instagram.com", 12_000)
                    if (ok) {
                        if (lastStatus != STATUS_CONNECTED) sendStatus(STATUS_CONNECTED)
                    } else {
                        healthySince = SystemClock.elapsedRealtime()
                        restartLanternEngine(tr("هسته دیگر ترافیک رد نمی‌کرد", "the core stopped carrying traffic"))
                    }
                }
            } catch (_: Exception) {
            }
        }, 1L, 1L, TimeUnit.SECONDS)
    }

    private fun stopLanternMonitor() {
        lanternMonitorTask?.cancel(false)
        lanternMonitorTask = null
    }

    // ------------------------------------------------------ «گف» (Geph)

    /**
     * «گف»: Geph's own engine as the VPN -- the same shape the official Geph Android app uses.
     *
     * The TUN is built first and handed to the engine as its stdin (`--vpn-fd 0`, Android 8+), so
     * Geph's userspace stack reads the device's packets directly: no tun2socks, no SOCKS hop, the
     * fastest path Geph has. MTU 16384 because that stack sizes its segments by 16384 whatever the
     * TUN says. IPv6 is routed INTO the tunnel rather than left to leak, and the resolver the phone
     * is given is a public address the engine rewrites into the tunnel anyway. Our own package
     * stays off the TUN, which is what keeps the engine -- a child of this process, same UID --
     * from routing its own uplink into itself.
     *
     * The TUN belongs to this service, not to the engine. When the engine dies, stalls or the
     * network changes, only the engine is restarted; the TUN stays up throughout, so there is no
     * moment in which apps fall back to the bare link.
     */
    private fun startGephTunnel() {
        gephMode = true
        gephRestarts = 0
        gephConnectedSeen = false
        gephTxBase = 0L
        gephRxBase = 0L
        gephLastNetwork = null
        runOnWorker {
            try {
                if (!GephEngine.available(this)) error(getString(R.string.geph_not_installed))
                if (!GephAccount.hasAccount(this)) makeGephAccount()
                if (stopRequested.get()) return@runOnWorker
                gephProxyOnly = GephSettings.coverage(this) == GephSettings.Coverage.PROXY
                val fdMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                gephBridge = !fdMode && !gephProxyOnly
                GephEngine.resetMilestones()
                sendStatus(STATUS_CONNECTING, getString(R.string.geph_status_starting), 5)
                if (!gephProxyOnly) {
                    tun = buildGephTun(fdMode) ?: error("Android could not establish the VPN interface")
                    vpnModeActive.set(true)
                } else {
                    // Nothing is routed: only the programs pointed at the local proxy use Geph.
                    // The service still runs in the foreground, so the engine is not killed and
                    // the notification, lamp and usage counters work exactly as for the tunnel.
                    vpnModeActive.set(false)
                }
                GephEngine.onUnexpectedExit = { code -> onGephEngineDied(code) }
                GephEngine.startMain(this, gephEngineMode(), tun, com.mlmvpn.scanner.lan.LanShare.sharingActive(this))
                if (gephBridge) startGephBridge()
                awaitGephReady(first = true)
            } catch (e: Exception) {
                ConnectionLog.record("Geph start failed: ${e.message}")
                GephEngine.onUnexpectedExit = null
                GephEngine.stopMain()
                if (gephBridge) com.mlmvpn.scanner.tun.Tun2proxyClient.stop(this)
                failAndStop(gephFailure(e))
            }
        }
    }

    /**
     * The first connect with no account makes one, instead of sending the user to the account page:
     * a free Geph account needs no e-mail and no password, only the network's proof-of-work puzzle
     * solved on the phone (half a minute to a few minutes, by processor). Its code is saved like
     * one made from the account page, where the user can see and copy it later.
     */
    private fun makeGephAccount() {
        ConnectionLog.record("Geph: no account yet -- making a free one")
        sendStatus(STATUS_CONNECTING, getString(R.string.geph_stage_register, 0), 2)
        var last = -1
        GephAccount.register(this, onProgress = { p ->
            val pct = (p * 100).toInt().coerceIn(0, 100)
            if (pct != last) {
                last = pct
                // The puzzle is most of a first connect: it gets the bar up to 60%.
                sendStatus(STATUS_CONNECTING, getString(R.string.geph_stage_register, pct), 2 + pct * 58 / 100)
            }
        }, cancelled = { stopRequested.get() }).getOrElse { e ->
            if (stopRequested.get()) return
            throw IllegalStateException(getString(R.string.geph_register_failed, e.message ?: ""))
        }
        ConnectionLog.record("Geph: free account made")
    }

    private fun gephEngineMode(): GephEngine.Mode = when {
        gephProxyOnly -> GephEngine.Mode.PROXY
        gephBridge -> GephEngine.Mode.VPN_BRIDGE
        else -> GephEngine.Mode.VPN_FD
    }

    private fun buildGephTun(fdMode: Boolean): ParcelFileDescriptor? {
        val b = Builder()
            .setSession(getString(R.string.app_name))
            .applyPlatformDefaults()
        if (fdMode) {
            b.setMtu(com.mlmvpn.scanner.utils.NetworkSettings.mtu(this, com.mlmvpn.scanner.utils.NetworkSettings.Method.GEPH))
                .addAddress(GephEngine.TUN_V4, GephEngine.TUN_V4_PREFIX)
                .routeAllV4()
                .addAddress(GephEngine.TUN_V6, GephEngine.TUN_V6_PREFIX)
                .routeAllV6()
                .addDnsServer(GephEngine.TUN_DNS)
        } else {
            // tun2proxy terminates TCP on the phone and answers DNS itself (--dns virtual), the
            // way the Aether path in MyVpnService does it.
            b.setMtu(1500)
                .addAddress("10.0.0.2", 32)
                .routeAllV4()
                .addAddress("fd00:1:2:3:4:5:6:2", 128)
                .routeAllV6()
                .addDnsServer(GephEngine.TUN_DNS)
        }
        return b.applySplitTunneling().establish()
    }

    /** Android 7: the TUN goes to tun2proxy, which dials Geph's SOCKS on loopback. */
    private fun startGephBridge() {
        val raw = tun?.fd ?: error("no TUN for the bridge")
        val args = "tun2proxy --proxy socks5://127.0.0.1:${GephSettings.SOCKS_PORT} --dns virtual " +
            "--verbosity warn --close-fd-on-drop false"
        if (!com.mlmvpn.scanner.tun.Tun2proxyClient.start(this, raw, args, 1500)) {
            error("Could not start device routing")
        }
    }

    /**
     * Waits for a session and proves it with a real request, then reports CONNECTED. Throws on
     * a failed connect (the caller decides whether that is a failure or a retry).
     */
    private fun awaitGephReady(first: Boolean) {
        val info = GephEngine.awaitConnected(
            GEPH_CONNECT_TIMEOUT_MS,
            cancelled = { stopRequested.get() },
        ) { percent, what -> sendStatus(STATUS_CONNECTING, gephStageText(what), percent) }
        if (stopRequested.get()) return
        if (info == null) {
            error(getString(if (GephEngine.isMainAlive) R.string.geph_no_session else R.string.geph_engine_exited))
        }
        sendStatus(STATUS_CONNECTING, getString(R.string.geph_status_verifying), 96)
        // "Connected" is a request that crossed, not the engine's word for it. A miss is logged
        // and the session kept: the free tier is slow enough that a first request can outlast
        // the check, and tearing down a working tunnel to report "nothing crossed" is the worse
        // error. The stall check below keeps asking.
        if (!GephEngine.verifyThroughTunnel()) {
            ConnectionLog.record("Geph: a session is up but the first request has not crossed yet")
        }
        val s = info.sessions.firstOrNull()
        currentCountryCode = s?.country.orEmpty()
        currentCountry = listOfNotNull(s?.country, s?.city?.takeIf { it.isNotBlank() }).joinToString(" · ")
        activeSocksPort = GephEngine.live.value.socksPort
        currentVpnIp = ""
        gephConnectedSeen = true
        sendStatus(STATUS_CONNECTED)
        ConnectionLog.record("Geph: connected via ${s?.protocol} to ${s?.country} ${s?.city}")
        repostNotification()
        startGephMonitor()
        if (first) startWatchdog()
        // The address sites see, off the critical path. Through the engine's own loopback
        // forward -- this process is off the TUN, so an ordinary request would measure the phone.
        ladderScheduler.execute {
            val exit = GephEngine.measureExit() ?: return@execute
            if (!gephMode || stopRequested.get()) return@execute
            lastExitIp = exit.first
            measuredExitIp = exit.first
            GephEngine.setExitIp(exit.first)
            if (currentCountryCode.isBlank()) exit.second?.let { currentCountryCode = it }
            sendStatus(STATUS_CONNECTED)
        }
    }

    private fun gephStageText(what: String): String = getString(
        when (what) {
            "broker" -> R.string.geph_stage_broker
            "auth" -> R.string.geph_stage_auth
            "route" -> R.string.geph_stage_route
            "dialing" -> R.string.geph_stage_dialing
            "tunnel" -> R.string.geph_stage_tunnel
            else -> R.string.geph_status_starting
        }
    )

    private fun gephFailure(e: Exception): String = when (e.message) {
        GephEngine.NO_ACCOUNT -> getString(R.string.geph_need_account)
        GephEngine.NOT_INSTALLED -> getString(R.string.geph_not_installed)
        else -> e.message ?: getString(R.string.geph_no_session)
    }

    /** The engine exited on its own. Restart it, keeping the TUN -- nothing leaks meanwhile. */
    private fun onGephEngineDied(code: Int) {
        if (stopRequested.get() || !gephMode) return
        runOnWorker { restartGephEngine("the Geph engine exited (code $code)") }
    }

    /**
     * A different network (Wi-Fi to mobile and back). Every session the engine holds was bound to
     * the old one, so a fresh engine is faster than waiting for each to time out -- the official
     * app does the same (ACTION_RESTART_ENGINE).
     */
    private fun onGephNetworkChange(network: Network) {
        val previous = gephLastNetwork
        gephLastNetwork = network
        if (previous == null || previous == network || !gephConnectedSeen || stopRequested.get()) return
        ladderScheduler.schedule({ runOnWorker { restartGephEngine("the network changed") } }, 1500, TimeUnit.MILLISECONDS)
    }

    private fun restartGephEngine(reason: String) {
        if (stopRequested.get() || !gephMode) return
        if (!gephProxyOnly && tun == null) return
        if (!gephRestarting.compareAndSet(false, true)) return
        try {
            gephRestarts++
            if (gephRestarts > GEPH_MAX_RESTARTS) {
                ConnectionLog.record("Geph: $GEPH_MAX_RESTARTS restarts in a row — handing over to reconnect")
                gephRestarting.set(false)
                onTunnelLost(reason)
                return
            }
            ConnectionLog.record("Geph: restarting the engine — $reason (the TUN stays up)")
            stopGephMonitor()
            val last = GephEngine.live.value
            gephTxBase += last.tx
            gephRxBase += last.rx
            sendStatus(STATUS_CONNECTING, getString(R.string.geph_status_reconnecting))
            GephEngine.resetMilestones()
            GephEngine.startMain(this, gephEngineMode(), tun, com.mlmvpn.scanner.lan.LanShare.sharingActive(this))
            awaitGephReady(first = false)
        } catch (e: Exception) {
            ConnectionLog.record("Geph restart failed: ${e.message}")
            if (!stopRequested.get() && gephMode) {
                val delay = (5L * gephRestarts).coerceAtMost(30L)
                ladderScheduler.schedule({ runOnWorker { restartGephEngine("retry after: ${e.message}") } }, delay, TimeUnit.SECONDS)
            }
        } finally {
            gephRestarting.set(false)
        }
    }

    /**
     * Once a second: the engine's counters into the notification and the monthly total. Every
     * five: its own view of the tunnel, and a stall check -- sending without receiving for a
     * minute is a tunnel that looks alive and carries nothing, so it is proved and, if it fails
     * the proof, restarted.
     */
    private fun startGephMonitor() {
        stopGephMonitor()
        var tick = 0
        var healthySince = SystemClock.elapsedRealtime()
        var stallSince = 0L
        var lastTx = -1L
        var lastRx = -1L
        gephMonitorTask = ladderScheduler.scheduleAtFixedRate({
            try {
                if (stopRequested.get() || !gephMode || gephRestarting.get()) return@scheduleAtFixedRate
                tick++
                val withConn = tick % 5 == 0
                val live = GephEngine.sample(withConn)
                if (!live.running) return@scheduleAtFixedRate
                updateTrafficNotification(gephTxBase + live.tx, gephRxBase + live.rx)
                if (!withConn) return@scheduleAtFixedRate
                val now = SystemClock.elapsedRealtime()
                if (live.connected) {
                    if (lastStatus != STATUS_CONNECTED) sendStatus(STATUS_CONNECTED)
                    if (now - healthySince > 120_000) gephRestarts = 0
                } else {
                    healthySince = now
                    if (lastStatus == STATUS_CONNECTED) {
                        sendStatus(STATUS_CONNECTING, getString(R.string.geph_status_reconnecting))
                    }
                }
                val sending = lastTx >= 0 && live.tx - lastTx > 16_384
                val receiving = lastRx >= 0 && live.rx - lastRx > 2_048
                lastTx = live.tx
                lastRx = live.rx
                stallSince = if (sending && !receiving) (if (stallSince == 0L) now else stallSince) else 0L
                if (stallSince != 0L && now - stallSince > 60_000 && gephChecking.compareAndSet(false, true)) {
                    stallSince = 0L
                    runOnWorker {
                        try {
                            if (!GephEngine.verifyThroughTunnel()) restartGephEngine("traffic stalled")
                        } finally {
                            gephChecking.set(false)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }, 1L, 1L, TimeUnit.SECONDS)
    }

    private fun stopGephMonitor() {
        gephMonitorTask?.cancel(false)
        gephMonitorTask = null
    }

    /**
     * Feeds [SocksFront]'s counters into the same traffic pipeline the other
     * transports use.
     *
     * [updateTrafficNotification] already owns everything downstream — speed
     * deltas, monthly totals with the rebase guard, notification throttling — so
     * this poll only has to present the numbers once a second; nothing about the
     * accounting is duplicated here.
     *
     * Runs on [ladderScheduler], which is idle for the whole life of a connected
     * Tor session: its other job, the Psiphon escalation timer, is cancelled
     * before any of this starts.
     */
    private fun startTrafficPolling() {
        torTrafficTask?.cancel(false)
        torTrafficTask = ladderScheduler.scheduleAtFixedRate({
            try {
                if (!SocksFront.isRunning) return@scheduleAtFixedRate
                updateTrafficNotification(SocksFront.sessionTx, SocksFront.sessionRx)
            } catch (_: Exception) {
            }
        }, 1L, 1L, TimeUnit.SECONDS)
    }

    private fun stopTrafficPolling() {
        torTrafficTask?.cancel(false)
        torTrafficTask = null
    }

    // ------------------------------------------------------ auto-reconnect

    /**
     * Watch an established tunnel and bring it back when it dies on its own.
     *
     * ## The bug this fixes
     *
     * Reported from the field: after some hours the notification still said
     * connected, but nothing had internet. Opening the app showed the tunnel
     * already disconnected, and tapping connect fixed it. Two separate faults
     * produced that:
     *
     *  1. **Nothing noticed.** On the Psiphon and Tor paths the data plane is
     *     `tun2socks` + a tunnel process. If those die without the service being
     *     told — a Psiphon controller exit after a long doze, tun2socks unwinding
     *     on a network change, Tor's process being killed by the OEM's memory
     *     manager — no callback fires. `connected` stays true and the
     *     notification keeps its last text forever. (The Rust-core path is
     *     different: its `finally` block runs and reports FAILED.)
     *  2. **Nothing recovered.** Even where the failure *was* reported, the only
     *     response was to paint the UI red and wait for a human.
     *
     * ## Shape
     *
     * A 30-second poll — the cheapest thing that can detect case 1 at all, and
     * ~2,900 wakeups over 24 hours of connection, which is inside the budget for
     * a foreground VPN service that is already holding a TUN. It only asks
     * questions that are free (are these threads/processes alive) and never
     * touches the network, so a doze-suppressed tick costs nothing.
     *
     * Deliberately **not** an HTTP health check: our own package is off the TUN
     * (`addDisallowedApplication`), so a probe from here rides the carrier link
     * and proves nothing about the tunnel — the same trap documented in
     * `openTunnelConnection`. Process liveness is the honest signal available in
     * the service.
     */
    private fun startWatchdog() {
        watchdogTask?.cancel(false)
        reconnectAttempts = 0
        watchdogTask = ladderScheduler.scheduleWithFixedDelay({
            try {
                if (stopRequested.get() || userInitiatedStop.get()) return@scheduleWithFixedDelay
                if (!connected.get()) return@scheduleWithFixedDelay
                val dead = tunnelIsDead() ?: return@scheduleWithFixedDelay
                ConnectionLog.record("Watchdog: $dead — reconnecting")
                onTunnelLost(dead)
            } catch (_: Exception) {
            }
        }, WATCHDOG_INTERVAL_S, WATCHDOG_INTERVAL_S, TimeUnit.SECONDS)
    }

    private fun stopWatchdog() {
        watchdogTask?.cancel(false)
        watchdogTask = null
    }

    /**
     * Reason the current data path is broken, or null when it looks healthy.
     *
     * Per-transport because each has a different thing that can die silently.
     * Everything checked here is a local liveness flag; nothing blocks.
     */
    private fun tunnelIsDead(): String? {
        if (urnetworkMode) {
            // A dead core is restarted by [onUrnetworkDied] with the TUN kept; this only catches the
            // case where that is not happening.
            if (urnetworkRestarting.get()) return null
            return if (com.mlmvpn.core.urnetwork.UrnetworkEngine.tile?.isAlive == true) null else "the URnetwork core is not running"
        }
        if (lanternMode) {
            // A dead core is restarted by [onLanternDied] with the TUN kept; this only catches the
            // case where that is not happening.
            if (lanternRestarting.get()) return null
            if (!com.mlmvpn.core.lantern.LanternEngine.isAlive) return "the Lantern core is not running"
            return null
        }
        if (cfwarpMode) {
            if (cfwarpRestarting.get()) return null
            if (!com.mlmvpn.core.warp.CfWarpEngine.isAlive) return "the WARP engine is not running"
            return null
        }
        if (gephMode) {
            // A dead engine is restarted by [onGephEngineDied] with the TUN kept; this only
            // catches the case where that is not happening.
            if (gephRestarting.get()) return null
            if (!GephEngine.isMainAlive) return "the Geph engine is not running"
            return null
        }
        // Whole-device routing is common to Psiphon, the chain and Tor. Without
        // it, packets from the TUN reach nothing regardless of tunnel state.
        val needsRouting = psiphonVpnMode || nativeProxyMode || currentProtocol.contains("TOR")
        if (needsRouting && !Tun2SocksManager.isRunning) return "device routing stopped"

        if (currentProtocol.contains("TOR")) {
            if (!TorManager.isRunning) return "the Tor process exited"
            if (!SocksFront.isRunning) return "the Tor front-end stopped"
            // Tor over WARP: tor stays alive when the outer leg dies, but every
            // circuit it holds is dead, so tor's own liveness is not enough here.
            // Checked last so a plainer cause is reported in preference to this.
            if (chainMode && !TunnelEngine.isRunning()) return "the WARP leg carrying Tor stopped"
            return null
        }
        if (nativeProxyMode) {
            if (!SocksFront.isRunning) return "the local proxy front-end stopped"
            if (!TunnelEngine.isRunning()) return "the tunnel stopped"
            return null
        }
        if (psiphonVpnMode && psiphonTunnel == null) return "the Psiphon tunnel is gone"
        return null
    }

    /**
     * Tear the dead session down and schedule its replacement.
     *
     * Runs on [ladderScheduler]; [stopTunnel] and [startTunnel] are both safe off
     * the main thread, and `teardownService = false` keeps the foreground service
     * (and therefore the notification and the VPN permission) alive across the
     * gap so the reconnect does not have to re-prompt the user.
     */
    private fun onTunnelLost(reason: String) {
        if (!autoReconnectEnabled()) {
            ConnectionLog.record("Auto reconnect is off — leaving the tunnel down")
            sendStatus(STATUS_FAILED, reason)
            connected.set(false)
            stopTunnel(notify = false)
            leaveForeground()
            return
        }
        stopWatchdog()
        connected.set(false)
        stopTunnel(notify = false, teardownService = false)
        scheduleAutoReconnect(reason)
    }

    /**
     * Re-dial after a backoff, until it works or the user intervenes.
     *
     * Backoff is 5s, 15s, 30s, 60s, then 120s forever. Capped rather than
     * abandoned: the overnight case this exists for is a phone that lost its
     * data connection entirely, and the correct behaviour when service returns
     * three hours later is still to reconnect. A 2-minute ceiling costs one
     * wakeup per two minutes while offline, which is less than the OS already
     * spends retrying its own connectivity checks.
     */
    private fun scheduleAutoReconnect(reason: String) {
        if (userInitiatedStop.get()) return
        val config = storedConfig
        if (config == null) {
            ConnectionLog.record("Auto reconnect: no stored config; giving up")
            return
        }
        val delay = RECONNECT_BACKOFF_S[
            reconnectAttempts.coerceAtMost(RECONNECT_BACKOFF_S.size - 1)
        ]
        reconnectAttempts++
        // The UI is told CONNECTING, not FAILED: from the user's point of view the
        // app is working on it, and painting the dial red for a recovery that is
        // about to happen on its own is the wrong report.
        sendStatus(STATUS_CONNECTING, "Reconnecting after $reason…")
        ConnectionLog.record("Auto reconnect #$reconnectAttempts in ${delay}s")
        reconnectTask?.cancel(false)
        reconnectTask = ladderScheduler.schedule({
            try {
                if (userInitiatedStop.get() || connected.get()) return@schedule
                startTunnel(config)
            } catch (e: Exception) {
                ConnectionLog.record("Auto reconnect failed to start: ${e.message}")
                scheduleAutoReconnect("start failure")
            }
        }, delay, TimeUnit.SECONDS)
    }

    private fun cancelAutoReconnect() {
        reconnectTask?.cancel(false)
        reconnectTask = null
        stopWatchdog()
        reconnectAttempts = 0
    }

    private fun autoReconnectEnabled(): Boolean =
        getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean(AUTO_RECONNECT_PREF, AUTO_RECONNECT_DEFAULT)

    /**
     * Whether a dropped tunnel will be picked back up automatically.
     *
     * Both conditions matter: the feature must be on, and the user must not have
     * asked for the disconnect. Used to decide whether a drop is reported as a
     * failure (red dial, session over) or as a reconnect in progress.
     */
    private fun willAutoReconnect(): Boolean =
        autoReconnectEnabled() && !userInitiatedStop.get() && storedConfig != null

    /**
     * Records that the current PLAIN transport reached the internet on this network.
     *
     * Only meaningful for the three transports the chain can use as its outer leg,
     * and only for an unchained run — inside the chain the byte counters belong to
     * Psiphon, and [raiseOuterLeg] already records that case directly and more
     * precisely.
     *
     * The threshold is deliberately the same 4 KiB the app's own verification gate
     * uses ([VERIFIED_RX_BYTES], mirroring MainActivity.VERIFY_MIN_RX_BYTES), and it
     * is on **RX only**:
     *
     *  - TX proves nothing. A dead or size-blind tunnel still sends: retransmits
     *    leave, the WireGuard health probe leaves, and none of it comes back. Every
     *    fake-connected bug in this project's history looked healthy on TX.
     *  - The floor must clear the core's own keepalive drip. The 3-second WireGuard
     *    data-plane probe pushes a few hundred bytes through a completely dead
     *    tunnel, so `rx > 0` would happily record a transport that carries nothing.
     *
     * Latches per session via [plainTransportRecorded] so this is one boolean test
     * per traffic sample once it has fired, not a SharedPreferences write per second.
     */
    private fun recordWorkingPlainTransport(rx: Long) {
        if (plainTransportRecorded) return
        if (chainMode || psiphonVpnMode) return
        if (rx < VERIFIED_RX_BYTES) return
        val transport = currentProtocol.lowercase()
        if (transport !in CoreConfig.CHAIN_OUTER_LADDER) return
        plainTransportRecorded = true
        nat64Rotations = 0
        com.mlmvpn.core.warp.Nat64Exits.reportOutcome(this, nat64Peer, ok = true)
        WarpLadder.markWorks(this, transport)
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putString(CoreConfig.PLAIN_WORKING_TRANSPORT_PREF, transport)
            .apply()
        ConnectionLog.record(
            "$currentProtocol carried real traffic on this network — " +
                "Psiphon-over-WARP will try it first"
        )
    }

    /**
     * Try each WARP transport in turn until one is carrying traffic.
     *
     * Returns the label of the transport that came up, or null when none did.
     *
     * Starts from the rung with the best evidence for this network, then wraps, so
     * every rung still gets a turn but the likeliest one goes first. That matters
     * more here than in the Psiphon ladder: a rung the carrier blocks outright does
     * not fail fast — MASQUE keeps scanning gateways until its budget expires — so a
     * wrong starting rung costs the user most of a minute.
     *
     * Evidence is ranked, strongest first:
     *
     *  1. [CoreConfig.CHAIN_OUTER_PREF] — a transport that has carried Psiphon
     *     inside it before. Direct evidence about the exact job at hand.
     *  2. [CoreConfig.PLAIN_WORKING_TRANSPORT_PREF] — a transport that reached the
     *     internet unchained on this carrier. Weaker (carrying Psiphon is harder
     *     than carrying ordinary traffic, so this can still fail) but far better
     *     than a static order, and it is the common case for a user who used the
     *     app normally before arming the chain.
     *  3. Ladder order — a fresh install with no history at all starts at MASQUE and
     *     walks the usual sequence.
     *
     * Each rung gets its own budget (see [chainOuterBudgetMs]) and the core is fully
     * stopped between attempts: the core's start call refuses to run twice
     * concurrently (its RUNNING compare_exchange returns "already running"), so the
     * next rung would fail instantly if the previous one were still unwinding.
     */
    private fun raiseOuterLeg(inner: String = "Psiphon"): String? {
        // Auto gives the whole ladder; a pinned transport gives just that one, with
        // no fallback — a pin exists to stop the app spending a minute on transports
        // the user already knows their carrier blocks.
        //
        // Tor and Psiphon read separate pins: the same outer leg suits them
        // differently, and the settings screen offers each its own row.
        val forTor = inner == "Tor"
        val ladder = CoreConfig.chainOuterCandidates(this, forTor)
        val auto = ladder.size > 1
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        // -1, not 0: absent must be distinguishable from "rung 0 worked", or a fresh
        // install would look like it had already proven MASQUE and the plain-history
        // hint below would never be consulted.
        val chainMemory = prefs.getInt(CoreConfig.CHAIN_OUTER_PREF, -1)
        val plainHint = prefs.getString(CoreConfig.PLAIN_WORKING_TRANSPORT_PREF, null)
            ?.let { ladder.indexOf(it) }
            ?.takeIf { it >= 0 }
        // The remembered index is into the full ladder, so it only means anything
        // when the full ladder is what we are walking.
        val start = when {
            !auto -> 0
            chainMemory in ladder.indices -> chainMemory
            plainHint != null -> plainHint
            else -> 0
        }

        if (!auto) {
            ConnectionLog.record(
                "Chain: outer transport pinned to ${CoreConfig.chainOuterLabel(ladder[0])}"
            )
        } else if (chainMemory !in ladder.indices && plainHint != null) {
            // Say which evidence was used. Without this the reordering is invisible
            // and a support log cannot distinguish it from the static order.
            ConnectionLog.record(
                "Chain: no chained history yet — starting with " +
                    "${CoreConfig.chainOuterLabel(ladder[plainHint])}, which last carried " +
                    "real traffic on its own"
            )
        }

        for (offset in ladder.indices) {
            if (stopRequested.get()) return null
            val index = (start + offset) % ladder.size
            val protocol = ladder[index]
            val label = CoreConfig.chainOuterLabel(protocol)
            val budget = chainOuterBudgetMs(protocol)

            // The core must be fully idle before this rung starts. If a previous
            // rung is still unwinding, `startProxy` returns "already running"
            // immediately while `isRunning`/`isReady` still describe the OLD tunnel
            // — so awaitOuterProxy would accept a rung that never started. Refusing
            // to continue is the only safe answer; the alternative is handing the
            // inner leg a proxy backed by a tunnel that is being torn down.
            if (TunnelEngine.isRunning()) {
                ConnectionLog.record("Chain: core still busy; cannot start the $label leg")
                return null
            }

            ConnectionLog.record(
                "Chain leg 1/2 attempt ${offset + 1}/${ladder.size}: " +
                    "$label → SOCKS ${CoreConfig.chainSocksPort(this)} (${budget / 1000}s budget)"
            )
            sendStatus(STATUS_CONNECTING, "Connecting $label…")

            val config = CoreConfig.chainOuterJson(this, protocol)
            val started = runCatching {
                // Provisions or loads this protocol's identity. MASQUE and WireGuard
                // keep separate ones, and a failure here (a refused registration, no
                // network) is this rung's failure, not the chain's.
                TunnelEngine.prepare(config)
                Thread({
                    // Guarded for the same reason as the Tor front proxy's relay
                    // threads: this is a bare thread, so anything escaping it goes
                    // to the default handler and takes the process down instead of
                    // failing this one rung.
                    try {
                        val result = TunnelEngine.startProxy(config)
                        if (result != 0 && !stopRequested.get()) {
                            val detail = TunnelEngine.lastError()
                                .ifBlank { "exited with code $result" }
                            ConnectionLog.record("Chain: $label leg ended: $detail")
                            // Only a failure once this rung was the accepted one is the
                            // chain's failure. Before that, ending is how a rung is
                            // rejected and raiseOuterLeg moves on — reporting FAILED
                            // there would abort the ladder on its first miss.
                            if (chainOuterCommitted) {
                                failAndStop("The $label tunnel carrying $inner dropped")
                            }
                        }
                    } catch (t: Throwable) {
                        ConnectionLog.record("Chain: $label leg threw: ${t.message}")
                        if (chainOuterCommitted && !stopRequested.get()) {
                            failAndStop("The $label tunnel carrying $inner dropped")
                        }
                    }
                }, "chain-outer-$protocol").start()
            }.isSuccess

            if (!started) {
                ConnectionLog.record("Chain: $label could not be prepared: ${TunnelEngine.lastError()}")
                stopOuterLeg()
                continue
            }

            if (awaitOuterProxy(budget)) {
                // Remember what worked, so the next automatic connect starts here.
                // Only meaningful for the full ladder: with a pin there is one entry
                // and the index would refer to the wrong transport later.
                if (auto) {
                    getSharedPreferences("settings", MODE_PRIVATE).edit()
                        .putInt(CoreConfig.CHAIN_OUTER_PREF, index).apply()
                }
                chainOuterCommitted = true
                ConnectionLog.record("Chain: $label is carrying the outer leg")
                return label
            }

            if (stopRequested.get()) return null
            if (auto) {
                ConnectionLog.record(
                    "Chain: $label did not come up in ${budget / 1000}s; trying the next transport"
                )
            } else {
                // A pin has nothing to fall back to, by design.
                ConnectionLog.record(
                    "Chain: $label did not come up in ${budget / 1000}s and it is pinned; " +
                        "switch the outer transport to Auto in settings to try the others"
                )
            }
            stopOuterLeg()
        }
        return null
    }

    /**
     * How long a given outer transport gets before the chain moves on.
     *
     * Not uniform, because their failure modes are not. MASQUE does not fail fast
     * when blocked — it keeps scanning gateways until its own budget runs out — so
     * its number is a cap on that scan rather than a timeout on a dial. WireGuard
     * either handshakes quickly or is being dropped. WoW has to raise two tunnels
     * in sequence, so it needs the most.
     *
     * The totals matter: worst case is the sum, spent only on the first connect
     * from a SIM whose usual transport is blocked, since the winner is remembered.
     */
    private fun chainOuterBudgetMs(protocol: String): Long = when (protocol) {
        "masque" -> 50_000L
        "wireguard" -> 40_000L
        else -> 60_000L
    }

    /**
     * Stop the outer leg and wait for the core to actually let go.
     *
     * the core's stop call only sets a flag; RUNNING stays true until the tunnel task
     * unwinds and drops its guard. Starting the next rung before that returns
     * "tunnel already running" and the rung fails for the wrong reason.
     */
    private fun stopOuterLeg() {
        TunnelEngine.stop()
        val deadline = SystemClock.elapsedRealtime() + OUTER_STOP_GRACE_MS
        while (TunnelEngine.isRunning() && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(200)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        if (TunnelEngine.isRunning()) {
            ConnectionLog.record("Chain: previous outer leg is still shutting down")
        }
    }

    /**
     * Wait until the outer leg is genuinely ready to carry Psiphon.
     *
     * Gated on the core's ready flag, NOT on the SOCKS port accepting a connection.
     * That distinction is the whole point: `socks::serve` binds its listener as soon
     * as the userspace netstack exists, before the tunnel behind it is validated, so
     * a port probe returns true almost immediately and would hand Psiphon a proxy
     * with nothing behind it. READY is set by `mark_ready()`, which the core only
     * calls once its data path is up — for MASQUE after data-plane validation, for
     * WoW after both legs are established.
     *
     * The port is then checked as well, since a ready tunnel with an unbound
     * listener would still fail Psiphon's UpstreamProxyURL validation.
     */
    private fun awaitOuterProxy(
        budgetMs: Long,
        port: Int = CoreConfig.chainSocksPort(this),
    ): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + budgetMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (stopRequested.get()) return false
            // A dead rung stops the core outright, so there is nothing left to wait
            // for — but only after it has had time to set RUNNING at all. The caller
            // has just spawned startProxy on another thread, and treating that gap as
            // "the rung died" would reject every rung the instant it was started.
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed > OUTER_START_GRACE_MS && !TunnelEngine.isRunning()) return false
            if (TunnelEngine.isReady() && coreProxyAccepts(port)) return true
            try {
                Thread.sleep(300)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    /** Whether the core's SOCKS listener on [port] is accepting connections. */
    /** [preferred] when nothing listens on it on loopback, else a free port the system picks. */
    private fun freeLoopbackPort(preferred: Int): Int {
        val loopback = java.net.InetAddress.getByName("127.0.0.1")
        val free = runCatching {
            java.net.ServerSocket().use { s ->
                s.reuseAddress = true
                s.bind(java.net.InetSocketAddress(loopback, preferred))
            }
        }.isSuccess
        if (free) return preferred
        return runCatching { java.net.ServerSocket(0, 1, loopback).use { it.localPort } }.getOrDefault(preferred)
    }

    /** The core config with its SOCKS listener moved to [port]. */
    private fun withListen(config: String, port: Int): String {
        val json = runCatching { org.json.JSONObject(config) }.getOrNull() ?: return config
        if (!json.has("listen")) return config
        return json.put("listen", "127.0.0.1:$port").toString()
    }

    private fun coreProxyAccepts(port: Int): Boolean = runCatching {
        java.net.Socket().use { probe ->
            probe.connect(java.net.InetSocketAddress("127.0.0.1", port), 1_000)
        }
    }.isSuccess

    /** The NAT64 endpoint the running MASQUE / WireGuard session is pinned to, if any. */
    @Volatile private var nat64Peer: String? = null

    /** Set when the core was stopped because its NAT64 gateway did not come up in time. */
    @Volatile private var nat64GatewayExpired = false
    @Volatile private var nat64RotateNow = false

    /** Gateways tried in a row for one connect; reset when the user connects again. */
    @Volatile private var nat64Rotations = 0

    /** WARP family members ([WarpLadder]) this connect has used up; reset when the user connects again. */
    private val warpTried: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** Set when the core was stopped because its transport did not come up within its budget. */
    @Volatile private var warpExpired = false

    /** The member to hand the connect to once the core has stopped, or null. */
    @Volatile private var warpNext: String? = null

    /** The member whose budget ran out, for the hand-over's message. */
    @Volatile private var warpFailed: String = ""

    /** The core said "connected" in this session: a later stall is a drop, not a closed transport. */
    @Volatile private var coreSaidConnected = false

    // ── «کشور خروجی با پراکسی رایگان» (core/warp/Ipv4Exits) ──────────────────────────────────

    /**
     * The IPv4 exit applies to [config]: a WARP-family member with the free-proxy exit turned on.
     * Such a connect takes the proxy-mode path, whose front ([SocksFront]) is where the exit hop sits.
     */
    private fun ipv4ExitApplies(config: String): Boolean =
        WarpLadder.protocolOf(config) in WarpLadder.MEMBERS && com.mlmvpn.core.warp.Ipv4Exits.isOn(this)

    /** The front's first exit hop for this connect; null when the pool has none (the session measures it). */
    private fun ipv4ExitHop(): SocksFront.ExitHop? =
        com.mlmvpn.core.warp.Ipv4Exits.chosen(this)?.let { com.mlmvpn.core.warp.Ipv4Exits.hopOf(it) }

    @Volatile private var ipv4ExitSessionUp = false

    /** Proves, swaps and watches the exit once the tunnel is up; every verdict reaches the notification. */
    private fun startIpv4ExitSession(frontPort: Int, enginePort: Int) {
        ipv4ExitSessionUp = true
        com.mlmvpn.core.warp.Ipv4ExitSession.start(
            this, frontPort, enginePort,
            log = { ConnectionLog.record(it) },
            onExit = { live ->
                if (connected.get()) {
                    live.loc?.takeIf { it.length == 2 }?.let { currentCountryCode = it }
                    live.ip?.let { lastExitIp = it; sendExitIp(it) }
                    repostNotification()
                    republishStatus()
                }
            },
        )
    }

    private fun stopIpv4ExitSession() {
        if (!ipv4ExitSessionUp) return
        ipv4ExitSessionUp = false
        com.mlmvpn.core.warp.Ipv4ExitSession.stop()
    }

    /**
     * A WARP tile the ladder may move: anything but a hand-entered endpoint. An exit country no longer
     * stops it (as on the desktop): the member that takes over dials its own NAT64 gateway of the same
     * country, or the same free proxy sits after it, so the country survives the hand-over. A pinned
     * NAT64 gateway is the service's own pick (withNat64Exit), not the user's, and does not count.
     */
    private fun warpLadderApplies(config: String): Boolean =
        WarpLadder.protocolOf(config) in WarpLadder.MEMBERS &&
            runCatching {
                val o = org.json.JSONObject(config)
                o.optString("forced_peer").isBlank() || (nat64Peer != null && o.optString("forced_peer") == nat64Peer)
            }.getOrDefault(false)

    private fun warpLabel(protocol: String): String = when (protocol) {
        MASQUE_ON_H2 -> getString(R.string.masque) + " (HTTP/2)"
        else -> getString(
            when (protocol) {
                "masque" -> R.string.masque
                "wireguard" -> R.string.wireguard_2
                else -> R.string.warp_in_warp
            }
        )
    }

    /** The core logged that it reached a MASQUE gateway in this attempt ("Accepted … via HTTP/n"). */
    @Volatile private var masqueAccepted = false

    /**
     * The WARP member the user asked for, once the ladder handed this connect to another (its enum
     * name, e.g. "MASQUE"); blank otherwise. Published with every status (EXTRA_LADDER_FROM) so that
     * member's screen keeps the session and says what happened. Cleared by a fresh connect.
     */
    @Volatile private var ladderFrom = ""

    /** Counts the core's starts; a ladder timer acts only on the attempt it was set for. */
    private val nativeAttempt = java.util.concurrent.atomic.AtomicInteger()

    /**
     * [proto] did not come up in its budget on this network: remembered as closed here, and the member
     * that takes over is named (null: the family is used up). One more way is kept for MASQUE: started
     * on HTTP/3, it is handed over at 30 s -- before the core's own fall-back to HTTP/2 at ~70 s -- so on
     * a line that drops UDP (where HTTP/2 is WARP's only way in, and WireGuard fails too) it would be
     * lost; there MASQUE gets one more go, started on HTTP/2 straight away.
     */
    private fun warpLadderNext(proto: String, config: String): String? {
        warpTried += proto
        WarpLadder.markBlocked(this, proto)
        if (proto == "masque" && runCatching { org.json.JSONObject(config).optString("masque_transport") }.getOrDefault("") != "h2") {
            warpTried += MASQUE_TRIED_H3
        }
        return WarpLadder.next(this, proto, warpTried)
            ?: MASQUE_ON_H2.takeIf { MASQUE_TRIED_H3 in warpTried && MASQUE_ON_H2 !in warpTried }
    }

    /** [next]'s config for a hand-over; [MASQUE_ON_H2] is MASQUE started on HTTP/2. */
    private fun warpLadderConfig(next: String): String =
        if (next == MASQUE_ON_H2) {
            warpTried += MASQUE_ON_H2
            org.json.JSONObject(CoreConfig.json(this, "masque")).put("masque_transport", "h2").toString()
        } else CoreConfig.json(this, next)

    /**
     * A tile this network closed lately is started as the member that gets through instead
     * ([WarpLadder.pick]), so a second tap does not sit through the same dead scan. Null when the
     * whole family was found closed here minutes ago: the caller says so at once.
     */
    private fun warpReroute(config: String): String? {
        if (!warpLadderApplies(config)) return config
        val asked = WarpLadder.protocolOf(config)
        if (reconnectAttempts == 0 && WarpLadder.allClosedRecently(this, asked)) {
            ConnectionLog.record("$asked and its stand-ins were all closed on this network minutes ago; not scanning again")
            return null
        }
        val use = WarpLadder.pick(this, asked, warpTried)
        if (use == asked) return config
        ConnectionLog.record("$asked was closed on this network lately; starting $use instead")
        if (ladderFrom.isBlank()) ladderFrom = asked.uppercase()
        sendStatus(STATUS_CONNECTING, getString(R.string.warp_ladder_skip, warpLabel(asked), warpLabel(use)))
        currentProtocol = use.uppercase()
        return CoreConfig.json(this, use)
    }

    /**
     * Whether the line itself is up: Cloudflare's resolver answers a TCP connect over IPv4 or IPv6.
     * This app is outside its own tunnel, so the probe leaves over the real link.
     */
    private fun internetUp(): Boolean =
        listOf("1.1.1.1", "2606:4700:4700::1111").any { host ->
            runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, 443), 2_500) }; true }.getOrDefault(false)
        }

    /** [failed] found nothing in its budget: the connect goes on as [next], at once. */
    private fun switchWarpTransport(failed: String, next: String) {
        ConnectionLog.record("$failed is closed on this network; trying $next")
        // The first member the user tapped keeps the session (its screen says what happened).
        if (ladderFrom.isBlank()) ladderFrom = failed.uppercase()
        sendStatus(STATUS_CONNECTING, getString(R.string.warp_ladder_switch, warpLabel(failed), warpLabel(next)))
        reconnectTask?.cancel(false)
        reconnectTask = ladderScheduler.schedule({
            try {
                if (userInitiatedStop.get() || connected.get()) return@schedule
                startTunnel(warpLadderConfig(next))
            } catch (e: Exception) {
                ConnectionLog.record("Could not start $next: ${e.message}")
                sendStatus(STATUS_FAILED, e.message)
            }
        }, 1, TimeUnit.SECONDS)
    }

    /**
     * The exit country, for the two core transports that dial a Cloudflare gateway themselves.
     *
     * With a country chosen, the config's `forced_peer` becomes that country's best NAT64 gateway
     * (see Nat64Exits) written for this protocol -- MASQUE's gateway on 443, WireGuard's on 2408.
     * Done here, on every start, rather than when the config is built: the stored config is what a
     * reconnect replays, and a reconnect after a dead gateway must pick the next one, not the same.
     * Direct, WARP-on-WARP and the chain's outer legs are left exactly as they were.
     *
     * Runs on the worker: the IPv6 check opens a socket.
     */
    private fun withNat64Exit(config: String): String {
        nat64Peer = null
        val nat64 = com.mlmvpn.core.warp.Nat64Exits
        if (nat64.isDirect(this)) return config
        val json = runCatching { org.json.JSONObject(config) }.getOrNull() ?: return config
        val proto = when (json.optString("protocol")) {
            "masque" -> com.mlmvpn.core.warp.Nat64Exits.Proto.MASQUE
            "wireguard" -> com.mlmvpn.core.warp.Nat64Exits.Proto.WG
            else -> return config
        }
        if (!com.mlmvpn.scanner.data.CfFamily.hasIpv6Route()) error(getString(R.string.nat64_no_ipv6))
        val peer = nat64.pick(this, proto) ?: error(getString(R.string.nat64_country_failed))
        nat64Peer = peer
        ConnectionLog.record("Exit country ${nat64.selectedCountry(this) ?: "?"}: NAT64 gateway $peer")
        return json.put("forced_peer", peer).toString()
    }

    /**
     * A pinned NAT64 gateway can pass the core's handshake and data-plane check and then carry
     * nothing back. Measured 2026-10-20 on the test phone: nat64.net's Amsterdam gateway validated
     * in under a second, the tun came up, and from then on not one UDP datagram reached the phone
     * while it kept sending (Udp6InDatagrams flat, tun0 rx 0) -- «متصل» with «۰ B دریافت» until the
     * core's stale timer (120 s, see TunnelEngine.coreEnv) retried the same gateway, which stayed
     * silent. The country ping said the same: «الان جواب نمی‌دهد». It happened with the Smart Iran
     * route on and off alike.
     *
     * So once the core says connected, the session has [NAT64_SILENT_MS] to show that it carries:
     * the core's exit address (its own probe through the tunnel) or [VERIFIED_RX_BYTES] received.
     * With neither, the gateway is stopped and the connect moves on exactly as for a gateway that
     * never came up ([nat64GatewayExpired]): the next one, and the honest «not answering» when the
     * country has none left.
     */
    private fun watchNat64Silence() {
        val peer = nat64Peer ?: return
        if (chainMode) return
        val attempt = nativeAttempt.get()
        ladderScheduler.schedule({
            if (stopRequested.get() || nat64Peer != peer || nativeAttempt.get() != attempt) return@schedule
            if (plainTransportRecorded || currentVpnIp.isNotBlank()) return@schedule
            ConnectionLog.record("NAT64 gateway $peer came up but nothing came back in ${NAT64_SILENT_MS / 1000}s; trying the next")
            nat64GatewayExpired = true
            runCatching { TunnelEngine.stop() }
        }, NAT64_SILENT_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Start a tunnel. Always whole-device VPN mode — proxy mode was removed, so
     * there is no longer a `vpnMode` parameter to branch on.
     */
    private fun startTunnel(config: String) {
        if (!connected.compareAndSet(false, true)) return
        // Start following the real link before any tun exists, so the first
        // establish already knows what it is riding on.
        trackUnderlyingNetwork()
        // A start supersedes any pending retry, whoever asked for it.
        reconnectTask?.cancel(false)
        reconnectTask = null
        nativeExitWasUnexpected = false
        coreSaidConnected = false
        masqueAccepted = false
        storedConfig = config
        // Kept on disk as well as in memory: an always-on or post-kill restart
        // arrives with no intent and no activity to ask.
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putString(LAST_CONFIG_PREF, config)
            .apply()
        currentProtocol = config.substringAfter("\"protocol\":\"").substringBefore('"').uppercase()
        currentVpnIp = ""
        // The country belongs to the session that just ended. Left set, the
        // notification would label a fresh tunnel with the previous exit's
        // country until something overwrote it.
        currentCountry = ""
        currentCountryCode = ""
        measuredExitIp = ""
        resetAppUsageSession()
        // The previous tunnel's exit belongs to the previous tunnel. Cleared here
        // as well as in stopTunnel because a reconnect goes straight from one
        // startTunnel to the next, and a stale address would otherwise be handed
        // to the UI as this session's measurement.
        lastExitIp = ""
        currentPing = ""
        // Every new tunnel makes the core start counting bytes from zero again
        // (rx_total/tx_total are locals inside tun::bridge). Anything here that
        // still holds the previous session's totals would then be compared
        // against a counter that just went backwards, so it all has to be reset
        // together, before the first sample of the new session arrives.
        resetSessionTraffic()
        stopRequested.set(false)
        vpnModeActive.set(true)
        // Belt and braces with the clear in stopTunnel(): a reconnect goes straight
        // from one startTunnel to the next without necessarily passing through the
        // teardown path, and these two flags decide whether this session counts as
        // "plain" for recordWorkingPlainTransport(). The branches below set them
        // again for the Psiphon and chained paths.
        chainMode = false
        psiphonVpnMode = false
        nativeProxyMode = false
        startAsForeground()

        // PSIPHON-OVER-WARP must be tested before the plain PSIPHON branch: its
        // protocol name contains "PSIPHON" too, so the order of these checks is
        // what keeps the chain from being started as an ordinary Psiphon tunnel.
        if (currentProtocol.contains(CHAIN_PROTOCOL_MARKER)) {
            startChainTunnel()
            return
        }

        // «گف» carries its own engine and its own userspace stack. An exact match, not a
        // substring: none of the other transports' names can contain it, nor it theirs.
        if (currentProtocol == GEPH_PROTOCOL) {
            startGephTunnel()
            return
        }

        // «وارپ»: its own engine and identity, none of the tunnel core's. Exact match again.
        if (currentProtocol == CFWG_PROTOCOL) {
            startCfWarpTunnel()
            return
        }

        // «لنترن»: Lantern 10's own core as a local proxy behind tun2proxy. Exact match: "LANTERN"
        // contains none of the other names, nor they it.
        if (currentProtocol == LANTERN_PROTOCOL) {
            startLanternTunnel()
            return
        }

        // «URnetwork»: its own core and the SDK's packet path. Without an account or a core its screen
        // refuses before the VPN consent; a connect that gets here anyway (a replay, a shortcut) ends
        // with the reason instead of falling through to the tunnel core below, which would take an
        // unknown protocol for MASQUE. Exact match: "URNETWORK" contains none of the names matched
        // below ("TOR", "PSIPHON"), nor they it.
        if (currentProtocol == URNETWORK_PROTOCOL) {
            com.mlmvpn.core.urnetwork.UrnetworkEngine.unavailableReason(this)?.let { failAndStop(it); return }
            startUrnetworkTunnel()
            return
        }

        // TOR is checked before PSIPHON only for symmetry with the chain marker
        // above; "TOR" and "PSIPHON" do not overlap as substrings, so the order
        // between these two is not load-bearing.
        if (currentProtocol.contains("TOR")) {
            startTorTunnel()
            return
        }

        // PSIPHON: callback-driven lifecycle — MUST NOT enter try/finally.
        // The finally block calls stopSelf() which destroys the service and kills Psiphon.
        if (currentProtocol.contains("PSIPHON")) {
            psiphonVpnMode = true  // Read by onConnected() to start tun2socks
            psiphonVpnActivated = false
            beginPsiphonSession()
            runOnWorker {
                try {
                    // The ladder first, before the TUN exists: a pinned method with nothing to work
                    // with fails here without the device ever being routed into a tunnel that cannot
                    // come up, and «پروکسی سرور»'s re-test runs while the phone still has its own
                    // internet. chainMode is false on this path: the unchained ladder and memory.
                    if (!preparePsiphonLadder()) return@runOnWorker
                    ConnectionLog.record("Preparing PSIPHON identity")
                    // Create the TUN first, then start Psiphon: this stops Psiphon's
                    // NetworkMonitor seeing tun0 appear as a network change, which
                    // used to cause a 13-second restart loop.
                    val socksPort = CoreConfig.socksPort(this)

                    // Address plan comes from tun2socks: the interface gets
                    // .ipAddress while lwIP answers on .router, which is also
                    // the DNS resolver the system will use. These must not be
                    // swapped or lwIP drops every packet.
                    val address = Tun2SocksManager.selectPrivateAddress()

                    ConnectionLog.record("Creating TUN interface BEFORE Psiphon starts")
                    tun = Builder()
                        .setSession(getString(R.string.app_name))
                        .applyPlatformDefaults()
                        .setMtu(tunMtu(mtuMethod()))
                        .addAddress(address.ipAddress, address.prefixLength)
                        .routeAllV4()
                        .addRoute(address.subnet, address.prefixLength)
                        .addDnsServer(address.router)
                        // --- Strategy A: break the DNS bootstrap deadlock ---
                        // With only address.router as a resolver, every DNS
                        // query goes lwIP → udpgw → Psiphon. Before a tunnel
                        // exists there is nothing on the far end, so DNS is
                        // dead exactly when Psiphon needs it to resolve the
                        // CDN hostnames that FRONTED-MEEK depends on. The log
                        // showed this as "resp 0/0" with 20-second RTTs and
                        // four consecutive "resolve canceled" tactics failures.
                        //
                        // Listing public resolvers as additional DNS servers
                        // gives the resolver somewhere to go. Combined with
                        // addDisallowedApplication(packageName) below — which
                        // keeps our own process off the TUN entirely — Psiphon's
                        // queries leave over the carrier link and resolve
                        // normally, so the fronted protocols become usable.
                        .addDnsServer("1.1.1.1")
                        .addDnsServer("8.8.8.8")
                        // Split tunnelling was ignored on this path: it only ever
                        // excluded our own package, so a user who picked apps in the
                        // Split screen and then connected with Psiphon silently got
                        // every app tunnelled. applySplitTunneling() honours the
                        // choice and still keeps our own process off the TUN in every
                        // mode — which the DNS bootstrap above depends on.
                        .applySplitTunneling()
                        .establish() ?: error("Android could not establish the VPN interface")
                    vpnModeActive.set(true)
                    ConnectionLog.record("TUN ready — now starting Psiphon on port $socksPort")
                    // Pre-save the SOCKS port so onConnected() can start tun2socks immediately.
                    activeSocksPort = socksPort
                    // Said BEFORE the start: a start that fails reports FAILED itself, and a
                    // «connecting» sent after it used to paint over that failure.
                    activeLadder.getOrNull(ladderIndex)?.let { s ->
                        sendStatus(STATUS_CONNECTING, rungStatus(s, ladderIndex, activeLadder.size))
                        liveRung(s, ladderIndex, activeLadder.size)
                    }
                    startPsiphonTunnel()
                } catch (e: Exception) {
                    ConnectionLog.record("Psiphon start failed: ${e.message}")
                    sendStatus(STATUS_FAILED, e.message)
                    connected.set(false)
                    leaveForeground()
                }
            }
            return
        }

        // Proxy Mode, for the transports the Rust core carries. Off by default and
        // the default path below is untouched by it, which is deliberate: that path
        // builds the TUN from the core's OWN addresses and applies the gateway
        // proxy, Zero Trust DNS and LAN rules that go with them, and none of that
        // has an equivalent here.
        // Local Network sharing needs a listener to hand out, and only this path has one: with
        // sharing on, the three WARP tiles take it without the user having to find Proxy Mode too.
        // The IPv4 exit takes this path too: its hop sits in the front this path starts.
        if (com.mlmvpn.scanner.utils.NetworkSettings.proxyMode(this) ||
            com.mlmvpn.scanner.lan.LanShare.sharingActive(this) || ipv4ExitApplies(config)) {
            if (ipv4ExitApplies(config))
                ConnectionLog.record("Exit country by free proxy (IPv4): local proxy path, the proxy sits after WARP")
            else if (!com.mlmvpn.scanner.utils.NetworkSettings.proxyMode(this))
                ConnectionLog.record("Local Network sharing is on: local proxy path, so other devices can use the tunnel")
            startNativeProxyTunnel(config)
            return
        }

        val requested = config
        runOnWorker {
            try {
                val config = withNat64Exit(warpReroute(requested) ?: run {
                    sendStatus(STATUS_FAILED, getString(R.string.warp_ladder_none))
                    return@runOnWorker
                })
                ConnectionLog.record("Preparing $currentProtocol identity")
                TunnelEngine.attach(this)
                // The Rust core binds the Android TUN directly here, so no local
                // listener exists. See startNativeProxyTunnel for the other mode.
                val addresses = TunnelEngine.prepare(config)
                if (stopRequested.get()) {
                    ConnectionLog.record("Cancelled before the tunnel came up")
                    return@runOnWorker
                }
                if (addresses.organization.isNotBlank()) {
                    ConnectionLog.record("Zero Trust organization ${addresses.organization}")
                }
                ConnectionLog.record("Creating Android VPN interface")
                val nativeMtu = tunMtu(mtuMethod())
                tun = Builder()
                    .setSession(getString(R.string.app_name))
                    .applyPlatformDefaults()
                    .setMtu(nativeMtu)
                    // applyTunnelAddresses replaces the hardcoded /32 + /128
                    // pair: v0.8.0 identities can carry a real prefix length,
                    // and a WARP identity without a v6 address must not get a
                    // v6 default route. It needs the MTU because below 1280 no
                    // v6 address can go on the interface at all.
                    .applyTunnelAddresses(addresses, nativeMtu)
                    .applyDns(config, addresses, ipv6 = nativeMtu >= IPV6_MIN_MTU)
                    .applyGatewayProxy(config, addresses)
                    .applyLanAccess(addresses)
                    .applySplitTunneling()
                    // applySplitTunneling() handles app exclusion per mode.
                    .establish() ?: error("Android could not establish the VPN interface")
                ConnectionLog.record("Scanning gateways for VPN")
                // The Rust core is about to bind this TUN fd directly, which
                // means no local SOCKS listener will exist for this session.
                // The UI health check must go direct, not via 127.0.0.1.
                TunnelStatus.isNativeTunMode = true
                // A pinned NAT64 gateway is not given up on by the core -- it retries the same
                // address forever -- so the service does: not up within the budget, and the core
                // is stopped and the country's next gateway dialled.
                nat64GatewayExpired = false
                nat64RotateNow = false
                val gatewayTimer = nat64Peer?.let { peer ->
                    ladderScheduler.schedule({
                        if (!stopRequested.get() && nat64Peer == peer && !TunnelEngine.isReady()) {
                            ConnectionLog.record("NAT64 gateway $peer did not come up in ${NAT64_NATIVE_BUDGET_MS / 1000}s; trying the next")
                            nat64GatewayExpired = true
                            runCatching { TunnelEngine.stop() }
                        }
                    }, NAT64_NATIVE_BUDGET_MS, TimeUnit.MILLISECONDS)
                }
                // A plain WARP tile has a budget too: a transport this network has closed is not
                // scanned for ever -- the connect moves to the member that gets through (WarpLadder).
                warpExpired = false
                warpNext = null
                val warpProto = WarpLadder.protocolOf(config)
                // Only on a connect the user asked for: a reconnect after a drop keeps its old
                // patience (the phone may simply be offline overnight), and so does a line that is
                // down altogether -- that says nothing about which transport it closed. With an exit
                // country too: the member that takes over keeps the country (warpLadderApplies).
                var warpGrace: java.util.concurrent.ScheduledFuture<*>? = null
                // A timer that fires as this attempt ends must not stop the NEXT attempt's core.
                val attemptId = nativeAttempt.incrementAndGet()
                val warpTimer = if (reconnectAttempts == 0 && warpLadderApplies(config)) {
                    val budget = WarpLadder.budgetMs(warpProto)
                    val grace = WarpLadder.stageGraceMs(warpProto)
                    fun stillDown() = nativeAttempt.get() == attemptId &&
                        !stopRequested.get() && !coreSaidConnected && !TunnelEngine.isReady()
                    fun expire(after: Long) {
                        ConnectionLog.record("$warpProto did not come up in ${after / 1000}s on this network")
                        warpExpired = true
                        runCatching { TunnelEngine.stop() }
                    }
                    ladderScheduler.schedule({
                        if (stillDown() && internetUp()) {
                            if (grace > 0 && masqueAccepted) {
                                // It reached a gateway: slow, not closed. A little longer before handing over.
                                ConnectionLog.record("$warpProto reached a gateway; ${grace / 1000}s more before handing over")
                                warpGrace = ladderScheduler.schedule({
                                    if (stillDown() && internetUp()) expire(budget + grace)
                                }, grace, TimeUnit.MILLISECONDS)
                            } else expire(budget)
                        }
                    }, budget, TimeUnit.MILLISECONDS)
                } else null
                val result = try {
                    TunnelEngine.start(config, tun!!.fd)
                } finally {
                    gatewayTimer?.cancel(false)
                    warpTimer?.cancel(false)
                    warpGrace?.cancel(false)
                }
                nat64RotateNow = nat64GatewayExpired && !stopRequested.get() && nat64Rotations < NAT64_MAX_GATEWAYS
                if (nat64GatewayExpired) com.mlmvpn.core.warp.Nat64Exits.reportOutcome(this, nat64Peer, ok = false)
                if (warpExpired && !stopRequested.get()) {
                    warpNext = warpLadderNext(warpProto, config)
                    warpFailed = warpProto
                }

                // Did the tunnel end on its own, i.e. without the user asking?
                // That is the case auto-reconnect exists for, and it has to be
                // decided here where the exit reason is still known.
                val diedOnItsOwn = !stopRequested.get()
                if (warpExpired && !stopRequested.get()) {
                    // Closed here, not dropped: the next member takes over, or -- with the family
                    // used up -- the user is told plainly, and nothing rescans in the background.
                    if (warpNext == null) sendStatus(STATUS_FAILED, getString(R.string.warp_ladder_none))
                } else if (result != 0 && !stopRequested.get()) {
                    val detail = TunnelEngine.lastError().ifBlank { "Tunnel exited with code $result" }
                    ConnectionLog.record("Native tunnel exited: $detail")
                    // A pinned gateway that never carried goes to the back of the line, so the
                    // automatic reconnect that follows dials the country's next one.
                    if (!plainTransportRecorded && !nat64GatewayExpired) com.mlmvpn.core.warp.Nat64Exits.reportOutcome(this, nat64Peer, ok = false)
                    if (!willAutoReconnect() && !nat64RotateNow) sendStatus(STATUS_FAILED, detail)
                } else if (stopRequested.get()) {
                    sendStatus(STATUS_DISCONNECTED)
                } else {
                    ConnectionLog.record("Native tunnel stopped unexpectedly")
                    if (!willAutoReconnect() && !nat64RotateNow) {
                        sendStatus(
                            STATUS_FAILED,
                            if (nat64GatewayExpired) getString(R.string.nat64_country_failed) else "Tunnel stopped unexpectedly",
                        )
                    }
                }
                nativeExitWasUnexpected = diedOnItsOwn
            } catch (error: Exception) {
                com.mlmvpn.core.warp.Nat64Exits.reportOutcome(this, nat64Peer, ok = false)
                val detail = setupFailure(error)
                Log.e(LOG_TAG, "Tunnel failed: $detail", error)
                // Cancelled while the core was still preparing: the user already saw «قطع».
                if (stopRequested.get()) sendStatus(STATUS_DISCONNECTED) else sendStatus(STATUS_FAILED, detail)
                // A setup failure is not a dropped tunnel: there is nothing to
                // restore, and retrying a config Android or the core rejected
                // would loop. Reported and left to the user.
                nativeExitWasUnexpected = false
            } finally {
                TunnelEngine.detach()
                vpnModeActive.set(false)
                TunnelStatus.isNativeTunMode = false
                // The native tunnel can end without stopTunnel() ever running —
                // the core exiting on its own, or the kill-switch branch below,
                // both land here instead. stopTunnel() is where the monthly
                // counters are normally persisted, so without this the traffic
                // since the last 60-second flush was lost on exactly the paths
                // that end a session unexpectedly.
                flushMonthlyTraffic()
                val killSwitch = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("kill_switch", false)
                tun?.close()
                tun = null
                connected.set(false)
                if (killSwitch && !stopRequested.get()) {
                    ConnectionLog.record("Kill switch active; blocking all traffic")
                    sendStatus(STATUS_FAILED, "Kill switch active — tunnel dropped")
                    rebuildKillSwitchVpn()
                } else if (nativeExitWasUnexpected && warpExpired) {
                    nativeExitWasUnexpected = false
                    warpExpired = false
                    val next = warpNext
                    warpNext = null
                    if (next != null) {
                        switchWarpTransport(warpFailed, next)
                    } else {
                        leaveForeground()
                    }
                } else if (nativeExitWasUnexpected && nat64RotateNow) {
                    // The exit country's gateway carried nothing: the next one, straight away,
                    // whether or not auto-reconnect is on -- this is still the connect the user
                    // asked for, not a recovery from a drop.
                    nativeExitWasUnexpected = false
                    nat64RotateNow = false
                    nat64Rotations++
                    reconnectAttempts = 0
                    scheduleAutoReconnect("the exit country's gateway carried nothing")
                } else if (nativeExitWasUnexpected && willAutoReconnect()) {
                    // The core died on its own and the user still wants to be
                    // connected. Keep the foreground service alive so the retry
                    // does not need a fresh VPN consent dialog.
                    nativeExitWasUnexpected = false
                    scheduleAutoReconnect("the tunnel dropped")
                } else {
                    nativeExitWasUnexpected = false
                    leaveForeground()
                }
            }
        }
    }


    /**
     * The sentence a failed setup reports. An identity the app could not make says so in its own
     * words (and carries the marker the transport screen offers the worker route on); anything
     * else is the core's last error, as before.
     */
    private fun setupFailure(error: Exception): String =
        error.message?.takeIf { com.mlmvpn.core.warp.WarpIdRelay.isBlocked(it) }
            ?: TunnelEngine.lastError().ifBlank { error.message ?: "Tunnel setup failed" }

    private fun stopTunnel(notify: Boolean = true, teardownService: Boolean = true) {
        stopRequested.set(true)
        // The watchdog must go first: it is what turns a teardown into a
        // reconnect, and leaving it armed while we dismantle the data path would
        // make it fire on the wreckage of a session that is deliberately ending.
        stopWatchdog()
        stopTorProgressPolling()
        // The traffic counters are only flushed to disk on a slow timer while
        // running, so an ordinary disconnect must persist the remainder here or
        // the last minute of the session would be lost from the monthly total.
        flushMonthlyTraffic()
        // Clear the session stamp here, not in sendStatus: the reconnect path and
        // onDestroy both call stopTunnel(notify = false), so relying on the
        // DISCONNECTED broadcast left connectedSince set and the next session's
        // timer resumed the old elapsed time instead of restarting at zero.
        connectedSince = 0L
        // No tunnel, no exit address. Leaving it set would let the next UI start
        // paint a dead tunnel's exit as if it were live.
        lastExitIp = ""
        // Disarm the escalation ladder before anything else: a pending timer that
        // fires after teardown would resurrect Psiphon on a dead TUN.
        ladderActive.set(false)
        cancelLadderTimer()
        // A stale region phase would apply the country filter to the *next*
        // connect's first attempt even after the user set the picker back to Auto.
        regionPhase = false
        // A capacity check still running for this session must not redial or route anything
        // once it is gone; and the screen stops describing a session that no longer exists.
        psiphonQualityGen++
        if (psiphonVpnMode) PsiphonLive.end()
        // Order matters: stop routing first so no more packets enter a tunnel
        // that is being torn down, then stop Psiphon itself.
        Tun2SocksManager.stop()
        stopTrafficPolling()
        TorManager.stop()
        stopPsiphonTunnel()
        TunnelEngine.stop()
        TunnelStatus.isNativeTunMode = false

        if (psiphonVpnMode) {
            // In VPN mode nothing else owns the service lifecycle now that the
            // Rust core is out of the data path, so tear down here.
            //
            // This also covers Psiphon-over-WARP: TunnelEngine.stop() above ends the
            // outer WARP leg, and chainMode must be cleared here so the next
            // buildPsiphonConfig() does not attach an UpstreamProxyURL pointing at
            // a listener that no longer exists.
            TunnelEngine.detach()
            chainMode = false
            chainOuterCommitted = false
            vpnModeActive.set(false)
            // Cleared with the rest of the per-session Psiphon state. It used to be
            // left set, which was harmless only because nothing read it after
            // teardown — recordWorkingPlainTransport() now does, and a stale `true`
            // would make every later plain MASQUE/WireGuard session look like a
            // Psiphon one and never record the transport that actually worked.
            psiphonVpnMode = false
            psiphonVpnActivated = false
            tun?.close()
            tun = null
            connected.set(false)
            if (notify) sendStatus(STATUS_DISCONNECTED)
            // A reconnect re-enters startTunnel() on the worker thread, so the
            // service must survive; only a real disconnect stops it.
            if (teardownService) {
                leaveForeground()
            }
            return
        }

        if (nativeProxyMode) {
            // Same shape as the two branches around it: Tun2SocksManager.stop() and
            // TunnelEngine.stop() above have already taken the data path apart, and
            // the core was running on its own thread rather than inside the worker's
            // try/finally, so there is no finally block that will do this for us.
            //
            // The front is stopped explicitly because TorManager.stop() above only
            // covers the Tor path's front; this path started its own.
            stopIpv4ExitSession()
            com.mlmvpn.core.warp.WarpVia.unregister(WARP_VIA_TUNNEL)
            SocksFront.stop()
            TunnelEngine.detach()
            nativeProxyMode = false
            vpnModeActive.set(false)
            tun?.close()
            tun = null
            connected.set(false)
            if (notify) sendStatus(STATUS_DISCONNECTED)
            if (teardownService) {
                leaveForeground()
            }
            return
        }

        if (urnetworkMode) {
            stopUrnetworkMonitor()
            val U = com.mlmvpn.core.urnetwork.UrnetworkEngine
            // The story ends with the end for a session that got as far as a tunnel; a failed start
            // ends on its error instead.
            if (tun != null) U.record(tr("قطع شد", "Disconnected"))
            U.stopTile()
            if (U.live.value.phase != com.mlmvpn.core.urnetwork.UrnetworkEngine.Phase.FAILED) U.endSession()
            urnetworkMode = false
            urnetworkUp = false
            activeSocksPort = 0
            vpnModeActive.set(false)
            tun?.close()
            tun = null
            connected.set(false)
            if (notify) sendStatus(STATUS_DISCONNECTED)
            if (teardownService) {
                leaveForeground()
            }
            return
        }

        if (lanternMode) {
            stopLanternMonitor()
            com.mlmvpn.core.lantern.LanternEngine.onUnexpectedExit = null
            // Routing first, so nothing more enters a tunnel whose core is about to go.
            com.mlmvpn.scanner.tun.Tun2proxyClient.stop(this)
            // The page's story ends with the end, not with the last exit it reported -- for a session
            // that got as far as a tunnel; a failed start ends on its error instead.
            if (tun != null) com.mlmvpn.core.lantern.LanternEngine.record(tr("قطع شد", "Disconnected"))
            com.mlmvpn.core.lantern.LanternEngine.stop()
            lanternMode = false
            lanternNetwork = null
            lanternDns = emptyList()
            activeSocksPort = 0
            vpnModeActive.set(false)
            tun?.close()
            tun = null
            connected.set(false)
            if (notify) sendStatus(STATUS_DISCONNECTED)
            if (teardownService) {
                leaveForeground()
            }
            return
        }

        if (cfwarpMode) {
            stopCfWarpMonitor()
            stopIpv4ExitSession()
            com.mlmvpn.core.warp.WarpVia.unregister(WARP_VIA_CFWARP)
            com.mlmvpn.core.warp.CfWarpEngine.onUnexpectedExit = null
            // Routing first, so nothing more enters a tunnel whose engine is about to go.
            com.mlmvpn.scanner.tun.Tun2proxyClient.stop(this)
            if (cfwarpFront) {
                SocksFront.stop()
                cfwarpFront = false
            }
            com.mlmvpn.core.warp.CfWarpEngine.stop()
            cfwarpMode = false
            activeSocksPort = 0
            vpnModeActive.set(false)
            tun?.close()
            tun = null
            connected.set(false)
            if (notify) sendStatus(STATUS_DISCONNECTED)
            if (teardownService) {
                leaveForeground()
            }
            return
        }

        if (gephMode) {
            // Same shape as the Tor branch below: the engine owns its data path, the TUN and the
            // service are ours. The monitor goes first so it cannot restart what is being ended.
            stopGephMonitor()
            GephEngine.onUnexpectedExit = null
            GephEngine.stopMain()
            if (gephBridge) {
                com.mlmvpn.scanner.tun.Tun2proxyClient.stop(this)
                gephBridge = false
            }
            gephMode = false
            gephProxyOnly = false
            gephConnectedSeen = false
            activeSocksPort = 0
            vpnModeActive.set(false)
            tun?.close()
            tun = null
            connected.set(false)
            if (notify) sendStatus(STATUS_DISCONNECTED)
            if (teardownService) {
                leaveForeground()
            }
            return
        }

        if (currentProtocol.contains("TOR")) {
            // Tor owns its own lifecycle the same way: everything to unwind lives in
            // TorManager/Tun2SocksManager, both already stopped above. All that is
            // left is the outer leg's bookkeeping, the TUN and the service.
            if (chainMode) {
                // Tor over WARP did attach the core, so it must detach — unlike the
                // unchained Tor path, where attach/detach never ran. TunnelEngine.stop()
                // above already ended the outer leg; without the detach the next
                // connect starts with a core still bound to a dead service.
                TunnelEngine.detach()
                chainMode = false
                chainOuterCommitted = false
            }
            vpnModeActive.set(false)
            tun?.close()
            tun = null
            connected.set(false)
            if (notify) sendStatus(STATUS_DISCONNECTED)
            if (teardownService) {
                leaveForeground()
            }
            return
        }

        if (notify && !connected.get()) sendStatus(STATUS_DISCONNECTED)
    }

    private fun rebuildKillSwitchVpn() {
        try {
            tun?.close()
            tun = Builder()
                .setSession(getString(R.string.app_name) + " - " + getString(R.string.vpn_kill_switch))
                .applyPlatformDefaults()
                // Not tunMtu(): nothing is carried over this interface. It exists to swallow
                // every packet while no tunnel is up, so its MTU is not a tuning decision and
                // following the user's here would only make the setting look like it did
                // something when the kill switch was the thing on screen.
                .setMtu(1280)
                .addAddress("100.64.0.1", 32)
                .routeAllV4()
                .routeAllV6()
                .addDnsServer("1.1.1.1")
                // The user's split tunnel holds here too: an app they keep outside the VPN keeps
                // its internet, one outside an «only these apps» list is not blocked, and this
                // app's own reconnect is never swallowed by the block it is trying to lift.
                .let { b -> runCatching { b.applySplitTunneling() }.getOrElse { b.addDisallowedApplication(packageName) } }
                .establish()
            ConnectionLog.record("Kill switch VPN active; tunnelled apps blocked until it reconnects")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Kill switch rebuild failed: ${e.message}")
            leaveForeground()
        }
    }

    /**
     * Post the current notification content once.
     *
     * Every caller is a real state change (connected, protocol resolved, country
     * learned) — never a timer. See [updateTrafficNotification] for why nothing
     * periodic is allowed to call this.
     */
    private fun repostNotification() {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification())
        } catch (_: Exception) {
        }
    }

    /**
     * Re-send the current status so a late-arriving fact reaches the screens.
     *
     * The exit country and the exit address are both learned AFTER the tunnel reports itself
     * connected -- Psiphon names its egress region a second later, and the address comes from
     * a probe that has to wait for the tunnel to carry traffic. Both ride on the status
     * broadcast, and no further status is due once connected, so each needs a repost or the
     * screen keeps whatever it had when CONNECTED went out.
     *
     * Deliberately reuses [lastStatus] rather than forcing STATUS_CONNECTED: a country
     * arriving while the tunnel is reconnecting must not paint the UI as connected.
     */
    private fun republishStatus() {
        if (lastStatus.isNotEmpty()) sendStatus(lastStatus)
    }

    private fun sendStatus(status: String, detail: String? = null, progress: Int = -1) {
        Log.i(LOG_TAG, "status=$status${detail?.let { " detail=$it" } ?: ""}")
        // A MASQUE session that failed outright: the carrier remembered for this network is
        // tried afresh (from HTTP/3) next time.
        if (status == STATUS_FAILED && currentProtocol == "MASQUE") MasqueCarrier.forget(this)
        lastStatus = status
        // Stamp the connect moment here rather than at each call site: there are
        // several paths to CONNECTED (native tunnel ready, Psiphon proxy ready,
        // tun2socks up, reconnect) and every one funnels through sendStatus.
        when (status) {
            STATUS_CONNECTED -> if (connectedSince == 0L) connectedSince = SystemClock.elapsedRealtime()
            STATUS_DISCONNECTED, STATUS_FAILED -> connectedSince = 0L
        }
        // Keep the last known figure across the many CONNECTING broadcasts that
        // carry no progress of their own, so a "Starting Tor…" message arriving
        // after "40%" does not visibly reset the percentage to nothing.
        if (progress >= 0) connectProgress = progress
        if (status == STATUS_CONNECTED || status == STATUS_DISCONNECTED || status == STATUS_FAILED) {
            connectProgress = -1
        }
        sendBroadcast(Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_STATUS, status)
            // Carried on every status so a freshly started activity learns which
            // transport is up, rather than only the one that started it knowing.
            .putExtra(EXTRA_PROTOCOL, currentProtocol)
            // Wall-clock stamp of when the tunnel came up, so a screen opened
            // later can show the real session length rather than counting from
            // when it happened to be created. Derived from the elapsed-realtime
            // stamp we already keep, which is the one that survives a clock change.
            .putExtra(
                EXTRA_CONNECTED_AT,
                if (connectedSince == 0L) 0L
                else System.currentTimeMillis() - (SystemClock.elapsedRealtime() - connectedSince)
            )
            .putExtra(EXTRA_COUNTRY, currentCountry)
            .putExtra(EXTRA_COUNTRY_CODE, currentCountryCode)
            .apply { if (measuredExitIp.isNotEmpty()) putExtra(EXTRA_EXIT_IP, measuredExitIp) }
            .putExtra(EXTRA_PROGRESS, connectProgress)
            .putExtra(EXTRA_LADDER_FROM, ladderFrom)
            .apply { detail?.let { putExtra(EXTRA_DETAIL, it) } })
        // The app's single tile, which covers both VPN services. There used to be one per
        // service, which meant the shade showed two entries reporting each other's state.
        // Can be refused outright: under an app cloner or a work profile the system answers that
        // the package is not the caller's (SecurityException, 1.2.41). The tile then simply waits
        // for its next refresh.
        runCatching {
            TileService.requestListeningState(
                this,
                ComponentName(this, com.mlmvpn.scanner.VpnTileService::class.java),
            )
        }
    }

    /**
     * Republish the current CONNECTING state with a new percentage.
     *
     * Used by the Tor bootstrap poller, which has a number but nothing new to
     * say in words. The detail text is left untouched by passing null, so the
     * line the user is reading ("trying Meek…") survives the update.
     */
    private fun publishProgress(percent: Int) {
        if (percent == connectProgress) return
        sendStatus(STATUS_CONNECTING, null, percent)
    }

    /**
     * Mirror Tor's own bootstrap percentage into the UI while it climbs.
     *
     * Tor reports 15 bootstrap notices per attempt and [TorManager] already
     * parses them into [TorManager.progress]; polling that field is cheaper than
     * threading a callback through the process reader, and one wakeup a second
     * on a screen the user is actively watching is not a battery concern — the
     * poller is cancelled the moment the connect resolves either way.
     */
    private fun startTorProgressPolling() {
        torProgressTask?.cancel(false)
        torProgressTask = ladderScheduler.scheduleAtFixedRate({
            try {
                if (stopRequested.get()) return@scheduleAtFixedRate
                val percent = TorManager.progress
                if (percent in 1..99) publishProgress(percent)
            } catch (_: Exception) {
            }
        }, 1L, 1L, TimeUnit.SECONDS)
    }

    private fun stopTorProgressPolling() {
        torProgressTask?.cancel(false)
        torProgressTask = null
    }

    private fun updateTrafficNotification(tx: Long, rx: Long) {
        val now = SystemClock.elapsedRealtime()
        // Throttle FIRST, before the rebase guard below.
        //
        // The order used to be the other way round, and that was the monthly-total
        // inflation bug. The guard zeroes `accountedTx/Rx`; the throttle then
        // returned without recording anything. So any backwards sample that landed
        // inside the 900 ms window left the accounting baseline at zero, and the
        // next sample re-added the session's entire cumulative byte count to the
        // month. One dip every 30 s over an hour of browsing turned 0.17 GB of real
        // traffic into 10.4 GB — a 60x overstatement, which is what produced the
        // reported 230 GB month.
        //
        // Returning before touching any state is the fix: a throttled callback must
        // be a pure no-op. Backwards samples are still caught, just on a callback
        // that goes on to consume them.
        if (now - lastTrafficSampleMs < 900) return

        // The core's counters are per-tunnel locals, and the core reconnects on
        // its own (the MASQUE and WireGuard reconnect loops both re-enter
        // `tun::bridge`) without the service being told. When that happens the
        // numbers arriving here go backwards, and every derived figure below —
        // the speed delta and the monthly delta — would compute a large negative
        // or absurd value from a mismatched baseline. Rebase instead of trying to
        // subtract across the discontinuity.
        if (tx < prevTx || rx < prevRx || tx < accountedTx || rx < accountedRx) {
            prevTx = 0
            prevRx = 0
            accountedTx = 0
            accountedRx = 0
            prevSpeedSampleMs = 0
            currentSpeedTx = 0
            currentSpeedRx = 0
        }

        val elapsed = now - prevSpeedSampleMs
        if (elapsed > 0 && prevSpeedSampleMs > 0) {
            currentSpeedTx = ((tx - prevTx) * 1000) / elapsed
            currentSpeedRx = ((rx - prevRx) * 1000) / elapsed
        }
        prevTx = tx
        prevRx = rx
        prevSpeedSampleMs = now

        val txDelta = (tx - accountedTx).coerceAtLeast(0)
        val rxDelta = (rx - accountedRx).coerceAtLeast(0)
        val (monthTx, monthRx) = recordMonthlyTraffic(txDelta, rxDelta)
        accountedTx = tx
        accountedRx = rx
        publishToAppUsage(tx, rx, txDelta, rxDelta)
        // A plain tunnel that has moved real bytes is evidence about this carrier
        // that the chain can reuse later. Cheap: a single boolean check on the
        // common path.
        recordWorkingPlainTransport(rx)
        // Only the screens read these, and with the screen off there is no screen to read them:
        // it was a broadcast a second through the system server, all night, for nobody. The
        // first one after the screen comes back is at most a second away.
        if (com.mlmvpn.scanner.utils.ScreenState.on.value) sendTraffic(tx, rx, monthTx, monthRx)

        if (now - lastTrafficFlushMs >= TRAFFIC_FLUSH_MS) {
            flushMonthlyTraffic()
            lastTrafficFlushMs = now
        }

        // The notification is NOT reposted here any more.
        //
        // It used to be, every 5 seconds, because it carried live byte counters
        // and speeds. That was the lock-screen alarm the user reported: the row
        // is IMPORTANCE_DEFAULT (required, or MIUI's lock screen drops it as
        // "silent"), and on MIUI every *post* of a DEFAULT row pokes the ambient
        // display even with setOnlyAlertOnce and no sound or vibration on the
        // channel. A tunnel left connected overnight therefore woke the screen
        // 720 times an hour.
        //
        // With the counters gone from the text there is nothing left in it that
        // changes second to second: the elapsed time is drawn by SystemUI's own
        // chronometer (see [notification]), and protocol and country only change
        // when something real happens — each of which reposts once, from its own
        // call site. So the steady state is exactly zero posts.
        lastTrafficSampleMs = now
    }

    /**
     * Feed the app's own usage accounting, which is separate from this service's monthly totals.
     *
     * Two consumers, and neither could see this stack until now — both were written against
     * [com.mlmvpn.scanner.MyVpnService], so every byte carried by MASQUE, WireGuard,
     * WARP-on-WARP, Psiphon or Tor was invisible to Settings > Usage and to the live meter in
     * the home header:
     *
     *  - [com.mlmvpn.scanner.data.SessionTraffic] is what the header's up/down readout shows.
     *    Absolute session totals, which is what this service already has; the Xray side
     *    accumulates its own because it samples per-UID counters rather than being handed totals.
     *    The `vpn_session_traffic` file behind it used to be rewritten here on every sample, moved
     *    or not -- once a second for the whole session. It now goes to disk with the monthly
     *    totals ([flushMonthlyTraffic]).
     *  - [com.mlmvpn.scanner.data.TrafficManager] keeps the per-day history behind Total Usage,
     *    and takes deltas. Gated on the same `enable_usage_tracking` preference the Xray path
     *    honours, so one switch still turns all accounting off.
     *
     * Called from the throttled sample, so this runs at most once a second.
     */
    private fun publishToAppUsage(tx: Long, rx: Long, txDelta: Long, rxDelta: Long) {
        com.mlmvpn.scanner.data.SessionTraffic.publish(rx, tx)
        if (tx != usageTx || rx != usageRx) {
            usageTx = tx
            usageRx = rx
            usageDirty = true
        }
        if (txDelta <= 0 && rxDelta <= 0) return
        val tracking = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this)
            .getBoolean("enable_usage_tracking", true)
        if (tracking) {
            usage.addTraffic(rxDelta, txDelta)
        }
    }

    /** Zero the header's readout so a new session does not open on the last one's figures. */
    private fun resetAppUsageSession() {
        com.mlmvpn.scanner.data.SessionTraffic.reset()
        usageTx = 0L
        usageRx = 0L
        usageDirty = false
        getSharedPreferences(SESSION_TRAFFIC_PREFS, MODE_PRIVATE).edit()
            .putLong("session_rx", 0L)
            .putLong("session_tx", 0L)
            .apply()
    }

    /** The session counters and the daily usage, which the traffic path holds in memory. */
    private fun flushAppUsage() {
        if (usageDirty) {
            usageDirty = false
            getSharedPreferences(SESSION_TRAFFIC_PREFS, MODE_PRIVATE).edit()
                .putLong("session_rx", usageRx)
                .putLong("session_tx", usageTx)
                .apply()
        }
        usage.flush()
    }

    /**
     * Adds this sample to the monthly totals, in memory.
     *
     * The disk write is deliberately not here — see [flushMonthlyTraffic] and the
     * fields it persists. The date is also only formatted when the month is not
     * already known, because building a SimpleDateFormat once a second to
     * re-derive the same string is waste in its own right.
     */
    private fun recordMonthlyTraffic(tx: Long, rx: Long): Pair<Long, Long> {
        // First sample of this process, or the month rolled over mid-session.
        loadMonthlyTotals()
        monthTxTotal += tx
        monthRxTotal += rx
        return monthTxTotal to monthRxTotal
    }

    private fun currentMonthKey(): String =
        java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date())

    /** Loads the persisted monthly totals into memory, once per month key. */
    private fun loadMonthlyTotals() {
        val month = currentMonthKey()
        if (monthKey == month) return
        // The month just rolled over mid-session: persist what the old month
        // accumulated before its key is replaced. Without this, everything since
        // the last 60-second flush was silently dropped from the month that ended,
        // because `monthKey` is what flushMonthlyTraffic() writes under.
        if (monthKey != null) flushMonthlyTraffic()
        val prefs = getSharedPreferences(TRAFFIC_PREFS, MODE_PRIVATE)
        // Discard totals written by a build that had the inflation bug.
        //
        // Fixing the accounting does not fix the number already on disk: it was
        // overstated by roughly the number of throttled backwards samples, which
        // varies per device, so there is no honest factor to divide by. Zeroing
        // once is the only truthful option — the month restarts from a correct
        // baseline instead of carrying a figure nobody can interpret.
        //
        // Gated on a stored schema version, not on the app version, so it happens
        // exactly once ever rather than on every update from here on.
        if (prefs.getInt(TRAFFIC_SCHEMA, 0) < TRAFFIC_SCHEMA_VERSION) {
            // Was there actually anything to throw away? On a fresh install there
            // is not, and announcing "your total was miscounted" to someone who
            // has never had a total is both false and alarming — the line showed
            // up on the first line of every field log for that reason.
            val hadTotals = prefs.contains(TRAFFIC_TX) || prefs.contains(TRAFFIC_RX)
            prefs.edit()
                .putInt(TRAFFIC_SCHEMA, TRAFFIC_SCHEMA_VERSION)
                .remove(TRAFFIC_MONTH)
                .remove(TRAFFIC_TX)
                .remove(TRAFFIC_RX)
                // commit(), not apply(): this must be on disk before anything
                // else, because if the write is lost the discard runs again on the
                // next launch and the month restarts from zero a second time.
                .commit()
            monthTxTotal = 0
            monthRxTotal = 0
            monthKey = month
            if (hadTotals) {
                ConnectionLog.record("Monthly traffic counter reset — previous total was miscounted")
            }
            return
        }
        val stored = prefs.getString(TRAFFIC_MONTH, null)
        monthTxTotal = if (stored == month) prefs.getLong(TRAFFIC_TX, 0) else 0
        monthRxTotal = if (stored == month) prefs.getLong(TRAFFIC_RX, 0) else 0
        monthKey = month
    }

    /**
     * Clears everything that describes the *current session's* traffic.
     *
     * Called at the start of every tunnel. The core's counters are locals inside
     * `tun::bridge`, so each new tunnel restarts them at zero; every mirror of
     * them here has to restart too.
     *
     * This is what the connect/disconnect/connect failure came down to. The
     * activity's verification gate takes `rxAtStart = trafficRx` when the
     * transport reports CONNECTED and then waits for `trafficRx` to reach
     * `rxAtStart + VERIFY_MIN_RX_BYTES`. `trafficRx` is fed straight from
     * `currentRx` here, and neither was ever reset, so on the second connect of
     * a process the gate demanded that a counter starting from zero exceed the
     * *previous* session's final total. It never could, so verification always
     * timed out after 18s and the UI reported "handshake succeeded but nothing
     * passes" for a tunnel that was working. Force-stopping the app made the
     * first connect succeed again because fresh fields start at zero — which is
     * exactly the workaround that was being used.
     *
     * The monthly totals are deliberately NOT cleared: they are cumulative
     * across sessions. Only the per-session deltas reset, and `accountedTx/Rx`
     * going to zero is what keeps the monthly accounting correct — the next
     * sample's delta is measured from zero, matching the core's fresh counter.
     */
    private fun resetSessionTraffic() {
        // The month totals are read lazily on the first sample; make sure they are
        // loaded before broadcasting, or a reset before any traffic would tell the
        // UI the month total is zero and the traffic screen would blank out.
        loadMonthlyTotals()
        currentTx = 0
        currentRx = 0
        prevTx = 0
        prevRx = 0
        currentSpeedTx = 0
        currentSpeedRx = 0
        accountedTx = 0
        accountedRx = 0
        // Zeroed, not set to `now`: these are throttle stamps, and a fresh
        // session should publish its first sample immediately rather than wait
        // out a window inherited from the tunnel that just died.
        prevSpeedSampleMs = 0
        lastTrafficSampleMs = 0
        // Per-session latch: each tunnel gets one chance to prove its transport
        // works. Without this reset the flag would stay set for the life of the
        // process, so a later session on a different transport (or a different
        // network) would never record what actually worked.
        plainTransportRecorded = false
        // The UI keeps its own mirrors, and it cannot know the core restarted
        // counting unless it is told. Without this broadcast the activity would
        // hold the old totals until the first traffic sample of the new session,
        // and the verification baseline is taken before that arrives.
        sendTraffic(0, 0, monthTxTotal, monthRxTotal)
    }

    /**
     * Writes the in-memory monthly totals to disk.
     *
     * Called on a slow timer from the traffic path and unconditionally on
     * teardown, so an ordinary disconnect always persists an exact figure.
     */
    private fun flushMonthlyTraffic() {
        // Same cadence and the same teardown points, so the app's own usage figures are never
        // lost on a path that persisted the month.
        flushAppUsage()
        val month = monthKey ?: return
        getSharedPreferences(TRAFFIC_PREFS, MODE_PRIVATE).edit()
            .putString(TRAFFIC_MONTH, month)
            .putLong(TRAFFIC_TX, monthTxTotal)
            .putLong(TRAFFIC_RX, monthRxTotal)
            .apply()
    }

    private fun sendTraffic(tx: Long, rx: Long, monthTx: Long, monthRx: Long) {
        sendBroadcast(Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_TRAFFIC_TX, tx)
            .putExtra(EXTRA_TRAFFIC_RX, rx)
            .putExtra(EXTRA_TRAFFIC_SPEED_TX, currentSpeedTx)
            .putExtra(EXTRA_TRAFFIC_SPEED_RX, currentSpeedRx)
            .putExtra(EXTRA_TRAFFIC_MONTH_TX, monthTx)
            .putExtra(EXTRA_TRAFFIC_MONTH_RX, monthRx))
    }

    /**
     * Broadcasts the core-measured exit address to the UI.
     *
     * Address only. The country is a geolocation question, which the UI answers
     * over whatever link it has — the answer for a given address is the same
     * either way, so it does not need to be asked from inside the tunnel.
     */
    private fun sendExitIp(ip: String) {
        measuredExitIp = ip
        sendBroadcast(Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_EXIT_IP, ip))
    }

    /** Bumped by every [startAsForeground]: a teardown queued before it must not stop the session after it. */
    private val foregroundGen = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Leaves the foreground and stops the service, off the main thread: `stopForeground` is a call
     * into the system that blocked a busy phone's main thread for 47 seconds on a disconnect
     * (1.2.42 #2283). A connect that starts the foreground again in the meantime wins.
     */
    private fun leaveForeground() {
        val gen = foregroundGen.get()
        Thread({
            if (foregroundGen.get() != gen) return@Thread
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            if (foregroundGen.get() == gen) stopSelf()
        }, "tun-leave-fg").start()
    }

    private fun startAsForeground() {
        foregroundGen.incrementAndGet()
        val manager = getSystemService(NotificationManager::class.java)
        ensureNotificationChannel(manager)
        val notification = newNotificationBuilder()
            .setContentTitle(getString(R.string.vpn_connecting))
            .setContentText(prettyProtocol())
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(appBadge())
            .setColor(NOTIFICATION_ACCENT)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            // No elapsed time yet, and a "0 seconds ago" stamp on a connect
            // attempt is noise.
            .setShowWhen(false)
            .build()
        // Some ROMs refuse it even with the permission declared (crash #581). A VPN service runs
        // without the foreground notification; the app must not die over the notification.
        runCatching { startForeground(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(LOG_TAG, "startForeground refused: ${it.javaClass.simpleName}") }
    }

    /**
     * Create the notification channel so the row survives on the lock screen.
     *
     * Why the old channel did not: it was `IMPORTANCE_LOW`, which Android files as
     * a *silent* notification, and the lock screen's own filter ("Show sensitive /
     * Don't show silent notifications", plus the equivalent on MIUI/EMUI/One UI)
     * drops silent rows entirely on many devices. That is the difference the user
     * sees against other VPN apps: it is the importance class, not the content.
     *
     * So the channel is `IMPORTANCE_DEFAULT` — the alerting class, which the lock
     * screen keeps — with sound and vibration explicitly removed. Alerting without
     * noise is the combination a VPN status row wants; raising importance alone
     * would have made it beep.
     *
     * `lockscreenVisibility = PUBLIC` is the second half: with `PRIVATE` (the
     * default) a device set to hide sensitive content shows "Contents hidden"
     * instead of the status. Nothing here is sensitive — app name, protocol,
     * byte counters — and hiding it defeats the purpose of the request.
     *
     * Battery cost of all this: zero. It changes how an already-posted
     * notification is classified, not how often it is posted.
     */
    /**
     * A builder bound to our channel on Oreo and later, and a plain one before it.
     *
     * The channel overload is API 26; calling it on Android 7 throws, and this service posts a
     * foreground notification on every single connect, so the app was unusable there rather than
     * merely missing a channel.
     */
    @Suppress("DEPRECATION")
    private fun newNotificationBuilder(): Notification.Builder =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

    private fun ensureNotificationChannel(manager: NotificationManager) {
        // Channels arrived in Oreo and this app's minSdk is 24, so on Android 7 every line below
        // is a method that does not exist. It reached a user as `NoSuchMethodError:
        // deleteNotificationChannel` on the FIRST connect -- and the try/catch around that call
        // did not help, because NoSuchMethodError is an Error, not an Exception. There is nothing
        // to create on those releases: notifications there have no channel at all.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return

        // Visibility and importance are frozen at creation time, so the old
        // channel can never be upgraded in place — it is removed instead. Harmless
        // when it was never created (fresh install).
        try { manager.deleteNotificationChannel(CHANNEL_ID_LEGACY) } catch (_: Throwable) {}
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VPN Service",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * The ongoing status notification.
     *
     * Takes no traffic figures: byte counters and speeds were removed from the
     * text (see [updateTrafficNotification]), which is what allows the row to be
     * posted only on real state changes instead of every few seconds.
     */
    private fun notification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = Intent(this, TunnelVpnService::class.java).apply {
            action = ACTION_DISCONNECT
            putExtra(EXTRA_CONFIG, storedConfig ?: "")
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 1, disconnectIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val reconnectIntent = Intent(this, TunnelVpnService::class.java).apply {
            action = ACTION_RECONNECT
        }
        val reconnectPendingIntent = PendingIntent.getService(
            this, 2, reconnectIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Second line: what is carrying the traffic and where it comes out.
        // Byte counters and speed are deliberately gone from here — see
        // [updateTrafficNotification] for why they were the cause of the
        // lock-screen wakeups, not just clutter.
        val method = prettyProtocol()
        val subtitle = if (currentCountry.isNotBlank()) "$method • $currentCountry" else method

        val builder = newNotificationBuilder()
            .setContentTitle(getString(R.string.vpn_connected))
            .setContentText(subtitle)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(appBadge())
            // Tints the small icon and the header text in the app's own accent,
            // which is what makes the row read as this app at a glance.
            .setColor(NOTIFICATION_ACCENT)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            // Same reasoning as the channel: nothing here is sensitive, and PRIVATE
            // would render "Contents hidden" on a device that hides sensitive
            // content — the exact case the user is complaining about.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.tunnel_disconnect), disconnectPendingIntent)
            .addAction(android.R.drawable.ic_menu_revert, getString(R.string.vpn_reconnect), reconnectPendingIntent)

        // The session timer, ticked by the system rather than by us.
        //
        // This is the whole fix for the lock-screen buzzing. Putting an elapsed
        // time in the text would mean re-posting the notification every second,
        // and on MIUI/EMUI every post of an IMPORTANCE_DEFAULT row wakes the
        // ambient display even with setOnlyAlertOnce — which is exactly what the
        // user saw. setUsesChronometer hands the clock to SystemUI: it counts up
        // on its own from `when`, forever, with zero further posts from us.
        //
        // `when` is derived by mapping the elapsedRealtime stamp we already keep
        // onto wall-clock time; using System.currentTimeMillis() directly would
        // restart the displayed timer on every repost.
        if (connectedSince > 0L) {
            val elapsed = SystemClock.elapsedRealtime() - connectedSince
            builder.setWhen(System.currentTimeMillis() - elapsed)
                .setUsesChronometer(true)
                .setShowWhen(true)
        } else {
            builder.setShowWhen(false)
        }

        return builder.build()
    }

    /**
     * Human-readable transport name for the notification.
     *
     * [currentProtocol] is upper-cased raw config text ("PSIPHON-OVER-WARP",
     * "TOR", "MASQUE"), which is right for substring matching and wrong for a
     * user-facing line. For Tor it also names the transport that actually
     * carried the circuit, because "Tor" alone hides the difference between a
     * direct connection and one riding a Snowflake proxy.
     *
     * Tor over WARP has no marker in [currentProtocol] — the chain is decided from
     * the preference, not the config string — so [chainMode] is what distinguishes
     * it here.
     */
    private fun prettyProtocol(): String = when {
        currentProtocol == GEPH_PROTOCOL -> "Geph"
        currentProtocol == CFWG_PROTOCOL -> "WARP"
        currentProtocol == LANTERN_PROTOCOL -> "Lantern"
        currentProtocol == URNETWORK_PROTOCOL -> "URnetwork"
        currentProtocol.contains(CHAIN_PROTOCOL_MARKER) -> "Psiphon over WARP"
        currentProtocol.contains("TOR") -> {
            val mode = TorManager.activeMode?.let { " (${it.label})" } ?: ""
            if (chainMode) "Tor$mode over WARP" else "Tor$mode"
        }
        currentProtocol.contains("PSIPHON") -> "Psiphon"
        currentProtocol.contains("MASQUE") -> "MASQUE"
        currentProtocol.contains("WIREGUARD") -> "WireGuard"
        currentProtocol.contains("GOOL") -> "WARP-on-WARP"
        currentProtocol.isBlank() -> "Tunnel"
        else -> currentProtocol.lowercase().replaceFirstChar { it.uppercase() }
    }

    /**
     * The app's launcher artwork as a round, full-colour notification badge.
     *
     * Rendered here rather than handed to the system as
     * `Icon.createWithResource(R.mipmap.ic_launcher)` because that resource is an
     * adaptive icon: launchers apply a mask to it, but `setLargeIcon` does not,
     * so on several OEM shells it lands as an unmasked square with the
     * background plate showing at the corners. Compositing background+foreground
     * into a circular bitmap ourselves gives the same round badge on every
     * device.
     *
     * Cached: this is a 128 px bitmap draw, and rebuilding it on every repost
     * would be pure waste on a row that is posted for hours.
     */
    private fun appBadge(): android.graphics.drawable.Icon {
        cachedBadge?.let { return it }
        val size = (resources.displayMetrics.density * 48f).toInt().coerceAtLeast(96)
        val bitmap = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)

        // Circular clip first, so both layers are trimmed identically.
        val clip = android.graphics.Path().apply {
            addCircle(size / 2f, size / 2f, size / 2f, android.graphics.Path.Direction.CW)
        }
        canvas.clipPath(clip)

        // Adaptive-icon geometry: the artwork is authored on a 108dp canvas of
        // which the inner 72dp is the guaranteed-visible area, i.e. the layers
        // are drawn 1.5x oversized and centred. Reproducing that scale is what
        // keeps the neon ring from being cropped.
        val inset = (-size * 0.25f).toInt()
        val bounds = android.graphics.Rect(inset, inset, size - inset, size - inset)
        listOf(R.drawable.ic_launcher_background, R.mipmap.ic_launcher_foreground).forEach { id ->
            getDrawable(id)?.apply {
                setBounds(bounds)
                draw(canvas)
            }
        }
        return android.graphics.drawable.Icon.createWithBitmap(bitmap)
            .also { cachedBadge = it }
    }

    /**
     * The whole IPv4 / IPv6 space, minus Iran's ranges when the Smart Iran route is on (see
     * [SmartIranRoute]); exactly `addRoute("0.0.0.0", 0)` / `addRoute("::", 0)` when it is off.
     */
    private fun Builder.routeAllV4(): Builder { SmartIranRoute.routeAllV4(this@TunnelVpnService, this); return this }

    private fun Builder.routeAllV6(): Builder { SmartIranRoute.routeAllV6(this@TunnelVpnService, this); return this }

    private fun Builder.applySplitTunneling(): Builder {
        val settings = SplitTunnelSettings(this@TunnelVpnService)
        val mode = settings.mode()
        // Smart Iran route: Iranian apps are never among an «only these apps» list...
        val packages = if (mode == SplitTunnelSettings.Mode.INCLUDE) SmartIranRoute.allowedFilter(this@TunnelVpnService, settings.packages()).toSet() else settings.packages()
        // ...and are left outside with every other mode.
        if (mode != SplitTunnelSettings.Mode.INCLUDE) SmartIranRoute.excludeApps(this@TunnelVpnService, this, mode, skip = packages)
        // An «only these apps» list left empty tunnels the whole device below: Iranian apps stay out of that too.
        else if (packages.isEmpty()) SmartIranRoute.excludeApps(this@TunnelVpnService, this, SplitTunnelSettings.Mode.ALL)

        if (mode == SplitTunnelSettings.Mode.ALL) {
            // GLOBAL: all apps through VPN, but MUST exclude ourselves to prevent routing loop.
            addDisallowedApplication(packageName)
            return this
        }
        if (mode == SplitTunnelSettings.Mode.INCLUDE) {
            // INCLUDE (whitelist): only listed apps go through VPN.
            // Do NOT add our own packageName — it's excluded by default.
            // Do NOT use addDisallowedApplication here (mixing with addAllowedApplication crashes).
        }
        if (packages.isEmpty()) {
            // EXCLUDE with an empty list, or INCLUDE with nothing chosen: the whole device, as
            // every other engine of the app does it, rather than refusing only on these tiles.
            if (mode == SplitTunnelSettings.Mode.INCLUDE) ConnectionLog.record("Split tunnel: no app selected; all apps tunnelled")
            addDisallowedApplication(packageName)
            return this
        }

        var addedCount = 0
        packages.forEach { pkg ->
            try {
                when (mode) {
                    SplitTunnelSettings.Mode.INCLUDE -> {
                        addAllowedApplication(pkg)
                        addedCount++
                    }
                    SplitTunnelSettings.Mode.EXCLUDE -> {
                        if (pkg != packageName) {
                            addDisallowedApplication(pkg)
                            addedCount++
                        }
                    }
                    // Unreachable: ALL returned above, before this loop.
                    SplitTunnelSettings.Mode.ALL -> Unit
                }
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                Log.w(LOG_TAG, "Split tunnel skipped missing app: $pkg")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to add $pkg to split tunnel: ${e.message}")
            }
        }

        if (mode == SplitTunnelSettings.Mode.INCLUDE && addedCount == 0) {
            // None of the chosen apps is installed: no allowed app means Android tunnels every
            // app, this one included, so this one is taken out -- the same fallback as elsewhere.
            ConnectionLog.record("Split tunnel: none of the selected apps is installed; all apps tunnelled")
            addDisallowedApplication(packageName)
            return this
        }

        // EXCLUDE mode: also disallow our own app to prevent routing loop.
        if (mode == SplitTunnelSettings.Mode.EXCLUDE) {
            addDisallowedApplication(packageName)
        }

        ConnectionLog.record("Split tunnel ${mode.label.lowercase()}: $addedCount app(s)")
        return this
    }

    private fun Builder.applyLanAccess(addresses: TunnelEngine.TunnelAddresses): Builder {
        if (!lanBypassEnabled()) return this
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            ConnectionLog.record("LAN access uses system local routes on Android 12 and older")
            return this
        }
        val ranges = mutableListOf(
            "10.0.0.0/8",
            "192.168.0.0/16",
            "fc00::/7",
            "fe80::/10",
        )
        // Upstream v0.8.0: WARP/Zero Trust device and gateway addresses live in
        // 172.16.0.0/12. Excluding that range would leak org DNS/gateway onto the
        // LAN, so it is only bypassed when we are not on a WARP CGNAT identity.
        if (!isWarpCgnat(addresses)) {
            ranges.add(1, "172.16.0.0/12")
        }
        ranges.forEach { cidr ->
            val (address, prefix) = cidr.split('/')
            excludeRoute(IpPrefix(InetAddress.getByName(address), prefix.toInt()))
        }
        ConnectionLog.record("LAN routes bypass the VPN")
        return this
    }

    /**
     * Upstream v0.8.0 renamed the LAN preference from `lan_sharing` to
     * `lan_bypass` and migrates the old value on first read. Kept verbatim so the
     * service and the merged MainActivity agree on which key is authoritative.
     */
    private fun lanBypassEnabled(): Boolean {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!prefs.contains("lan_bypass") && prefs.getBoolean("lan_sharing", false)) {
            prefs.edit().putBoolean("lan_bypass", true).apply()
            return true
        }
        return prefs.getBoolean("lan_bypass", false)
    }

    private fun Builder.applyTunnelAddresses(
        addresses: TunnelEngine.TunnelAddresses,
        mtu: Int,
    ): Builder {
        val v4 = parseTunnelAddress(addresses.ipv4, 32)
            ?: error("Zero Trust identity has no usable IPv4 address")
        addAddress(v4.first, v4.second)
        routeAllV4()

        // Below 1280 there can be no IPv6 ADDRESS on this interface, whatever we would like.
        //
        // 1280 is IPv6's own minimum link MTU, and the kernel enforces it: adding a v6 address to
        // an interface below it fails, and `establish()` comes back as "Cannot set address" with
        // the tunnel never coming up at all. Measured here the moment WARP-on-WARP's default
        // became 1220 -- which it had to, because 1220 is the largest packet that tunnel can
        // actually deliver on this line.
        //
        // So the choice is not "IPv6 or no IPv6". It is "a tunnel that carries IPv4 correctly" or
        // "a tunnel that refuses to start".
        //
        // NOTHING of the v6 family is added here, and that is the load-bearing part. The obvious
        // move -- claim ::/0 without an address so v6 dies inside the tunnel -- does not work:
        // Android drops a route whose family has no address on the interface, and the result was
        // measured to be the worst of the three states. `ip -6 route get` showed v6 leaving over
        // wlan0 from the phone's real public address while the tunnel was up and the screen said
        // connected. VpnService blocks a family outright when no address, route or DNS server of
        // that family is added, so adding none is what actually contains it.
        if (mtu < IPV6_MIN_MTU) {
            ConnectionLog.record(
                "IPv6 blocked while connected: MTU $mtu is under the IPv6 minimum of $IPV6_MIN_MTU"
            )
            return this
        }

        val v6 = parseTunnelAddress(addresses.ipv6, 128)
        if (v6 != null) {
            addAddress(v6.first, v6.second)
            routeAllV6()
        } else {
            // No IPv6 from the gateway, so IPv6 has to be swallowed rather than
            // left alone.
            //
            // Claiming only the v4 route leaves ::/0 pointing at the carrier
            // link, and on a dual-stack network that is not a corner case: most
            // large sites publish AAAA records, so the device prefers IPv6 and
            // the traffic the user believes is tunnelled leaves in the clear,
            // from their real address. Nothing in the UI would show it.
            //
            // Taking the route with a unique-local address the gateway cannot
            // answer for means IPv6 goes into the tunnel and dies there. Apps
            // then fall back to IPv4 - a connection that works, instead of a
            // leak that looks like it works.
            addAddress(IPV6_BLACKHOLE, 128)
            routeAllV6()
        }
        return this
    }

    private fun Builder.applyGatewayProxy(
        config: String,
        addresses: TunnelEngine.TunnelAddresses,
    ): Builder {
        if (!JSONObject(config).optBoolean("gateway", false)) return this
        val parsed = parseSocketAddress(addresses.gatewayProxy) ?: return this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setHttpProxy(ProxyInfo.buildDirectProxy(parsed.first, parsed.second))
            ConnectionLog.record("Zero Trust gateway ${parsed.first}:${parsed.second}")
        } else {
            ConnectionLog.record("Gateway filtering in VPN mode needs Android 10 or newer")
        }
        return this
    }

    private fun parseTunnelAddress(raw: String, defaultPrefix: Int): Pair<InetAddress, Int>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val host = trimmed.substringBefore('/')
        val prefix = trimmed.substringAfter('/', missingDelimiterValue = "")
            .toIntOrNull() ?: defaultPrefix
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        val maxPrefix = if (address.address.size == 4) 32 else 128
        return address to prefix.coerceIn(0, maxPrefix)
    }

    private fun parseSocketAddress(raw: String): Pair<String, Int>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith('[')) {
            val host = trimmed.substringAfter('[').substringBefore(']')
            val port = trimmed.substringAfter("]:", "").toIntOrNull() ?: return null
            host to port
        } else {
            val separator = trimmed.lastIndexOf(':')
            if (separator <= 0) return null
            val host = trimmed.substring(0, separator)
            val port = trimmed.substring(separator + 1).toIntOrNull() ?: return null
            host to port
        }
    }

    private fun isWarpCgnat(addresses: TunnelEngine.TunnelAddresses): Boolean {
        val host = addresses.ipv4.substringBefore('/').trim()
        val octets = host.split('.')
        if (octets.size == 4) {
            val first = octets[0].toIntOrNull()
            val second = octets[1].toIntOrNull()
            if (first == 172 && second != null && second in 16..31) return true
        }
        return addresses.gatewayProxy.contains("172.16.") ||
            addresses.gatewayProxy.contains("172.17.") ||
            addresses.gatewayProxy.contains("172.18.")
    }

    private fun Builder.applyDns(
        config: String,
        addresses: TunnelEngine.TunnelAddresses,
        /**
         * Whether this tun can carry IPv6 at all. See [applyTunnelAddresses].
         *
         * A DNS server counts as "an entry of that family" to VpnService just as much as an
         * address or a route does, so advertising a v6 resolver on a tun that has neither is
         * what unblocks the family and sends v6 out over the carrier link. That is not a
         * theoretical ordering: it is what `dumpsys connectivity` showed while the tunnel was
         * up -- `DnsAddresses: [1.1.1.1, 8.8.8.8, 2606:4700:4700::1111]` with `Routes:` holding
         * only 0.0.0.0/0, and `ip -6 route get` answering over wlan0 from the real address.
         */
        ipv6: Boolean,
    ): Builder {
        // OURS, kept over upstream's version — this is load-bearing for Psiphon.
        //
        // Carrier DNS on Iranian mobile networks is both censored and rejected by
        // Psiphon's SOCKS5 (reply 5), so public resolvers are forced first and any
        // carrier-supplied server is filtered out rather than merely appended
        // after. Upstream instead uses 1.1.1.1/1.0.0.1 only as a *fallback* when
        // the config lists nothing, which would let carrier DNS through.
        val forcedDns = listOf("1.1.1.1", "8.8.8.8")
        forcedDns.forEach { addDnsServer(InetAddress.getByName(it)) }

        // From upstream v0.8.0: advertise a v6 resolver when the identity has a
        // v6 address, otherwise v6-only lookups have nowhere to go.
        if (ipv6 && addresses.ipv6.isNotBlank()) {
            runCatching { addDnsServer(InetAddress.getByName("2606:4700:4700::1111")) }
        }

        // Also add any DNS servers from config (for non-Psiphon protocols).
        val configured = JSONObject(config).optString("dns_servers")
        configured.split(',', ';', ' ', '\n')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull { entry ->
                val address = when {
                    entry.startsWith('[') -> entry.substringAfter('[').substringBefore(']')
                    entry.count { it == ':' } == 1 -> entry.substringBefore(':')
                    else -> entry
                }
                runCatching { InetAddress.getByName(address) }.getOrNull()
            }
            .distinct()
            .filter { it.hostAddress !in forcedDns }
            // A configured v6 resolver unblocks the family exactly as the built-in one does.
            .filter { ipv6 || it !is java.net.Inet6Address }
            .forEach { addDnsServer(it) }

        ConnectionLog.record("DNS forced to public resolvers, carrier DNS excluded")
        return this
    }
}

// ── ConnectionLog ──

object ConnectionLog {
    private val redactor = com.mlmvpn.scanner.utils.SecretRedactor.Stream()
    private const val MAX_ENTRIES = 100
    private const val MAX_FILE_BYTES = 256 * 1024L
    private val entries = ArrayDeque<String>()
    private var sink: java.io.File? = null

    /**
     * One formatter, reused.
     *
     * SimpleDateFormat is not thread-safe, which is why the usual advice is to make
     * a new one per call — but every write here is already inside a @Synchronized
     * block, so one instance is safe and saves an object plus its parsed pattern on
     * every line. That matters because a single Psiphon connect produces several
     * hundred lines in under two seconds.
     */
    private val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    /**
     * Psiphon notices that are pure bookkeeping.
     *
     * These are not merely noise to read past — they were actively destructive. A
     * single connect emits one `updated server <id>` line per server entry (430 of
     * them in the field log, plus a second wave after the tunnel comes up), and the
     * ring buffer holds 100 entries. Every genuinely useful line — which strategy
     * ran, which country was preferred, why a rung failed — was evicted before the
     * user could ever see it, and each line also cost a JNI hop and a separate file
     * append.
     *
     * Dropped by prefix rather than by log level so the interesting warnings and
     * errors from the same subsystem still arrive.
     */
    private val NOISE = listOf(
        "\"message\":\"updated server ",
        "\"message\":\"Memory metrics at ",
        "\"message\":\"Datastore metrics at ",
        "\"message\":\"DNS metrics at ",
        "\"message\":\"ServerEntryIterator.reset:",
        "\"message\":\"Awaited ScanServerEntries:",
        "\"message\":\"Set dial parameters for ",
        "\"message\":\"port forward failures for ",
    )

    /**
     * Ported from upstream v0.8.0: mirror the ring buffer to a file so logs
     * survive the process being killed. Required — the merged MainActivity calls
     * this on startup. Capped and self-truncating so it cannot grow unbounded.
     */
    @Synchronized
    fun bind(file: java.io.File) {
        sink = file
        if (file.exists() && file.length() > MAX_FILE_BYTES) {
            file.delete()
        }
    }

    @Synchronized
    fun record(message: String) {
        val message = redactor.redact(message)
        if (NOISE.any(message::contains)) return
        val line = "${stamp.format(java.util.Date())}  $message"
        if (entries.size == MAX_ENTRIES) entries.removeFirst()
        entries.addLast(line)
        // The file on a thread of its own: the service records from its main thread, and an append
        // there waited on a busy disk for seconds (freeze report, 1.2.41).
        val file = sink ?: return
        writer.execute { runCatching { file.appendText(line + "\n") } }
    }

    /** One thread, so lines reach the file in the order they were recorded. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "connection-log").apply { isDaemon = true }
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    /** Empties the in-memory log. The file on disk, if bound, is left alone. */
    fun clear() {
        entries.clear()
    }
}
