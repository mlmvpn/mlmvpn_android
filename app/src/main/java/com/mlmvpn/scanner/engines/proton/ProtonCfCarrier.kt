package com.mlmvpn.scanner.engines.proton

import android.content.Context
import android.net.Network
import android.util.Log
import com.mlmvpn.scanner.data.GroupManager
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.engines.cfnet.CfEchKey
import com.mlmvpn.scanner.engines.cfnet.CfNetManager
import com.mlmvpn.scanner.engines.cfnet.CfShape
import com.mlmvpn.scanner.engines.cfnet.CfStage
import com.mlmvpn.scanner.engines.flux.FluxNet
import com.mlmvpn.scanner.utils.CfUnfilter
import com.mlmvpn.scanner.utils.VpnConfig
import com.mlmvpn.scanner.utils.XrayJsonGenerator
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Proton over IPv4 on a line that blackholes Proton's own IPv4 addresses: through Cloudflare.
 *
 * Measured on the user's line (2026-10-06): to Proton's IPv4 servers the first WireGuard
 * handshake is answered and then nothing passes -- over UDP on every port and over Stealth's TLS
 * alike, whatever its server name or record layout. Cloudflare's IPv4 edges, though, carry data
 * once the TLS hello has the shape the Cloudflare Doctor proved on that line (ECH, or PattNG's
 * fragment). So Stealth's TLS connection is opened from inside Cloudflare: one of the user's own
 * Cloudflare configs (a Worker that relays TCP) carries it, dialled with that proven handshake, and
 * the Proton server sees a connection from Cloudflare. WireGuard stays end to end inside it.
 *
 * This object runs the one Xray core that does it: a local SOCKS port in, the Cloudflare config
 * out. The core lives in a process of its own (`:pcarrier`, [XrayProtonCarrierService]): starting
 * and stopping a core per candidate config could panic Xray, and in the main process a Go panic
 * closes the app. Proton's tunnel carries this app's own traffic too, so the core's connection to the edge must
 * stay out of it or it would loop into itself; Xray has no socket-protect hook here, so its dialer
 * goes through [Bypass], a local SOCKS server whose sockets are bound to the real network. The ECH
 * key is fetched over that network too and written into the config: nothing the core does asks the
 * tunnel, which is what lets it start (or restart) while Proton is already up.
 *
 * Shared by every Proton relay on the same network; stopped a little after the last lease closes.
 */
internal object ProtonCfCarrier {

    private const val TAG = "ProtonCf"
    private const val BYPASS_TAG = "cf-bypass"
    private const val PROBE_MS = 10_000
    private const val MAX_CANDIDATES = 4
    /** After nothing worked on a network, how long before it is looked for again. */
    private const val RETRY_MS = 2 * 60_000L
    private const val IDLE_STOP_MS = 30_000L

    class Lease internal constructor(val port: Int, private val done: () -> Unit) : Closeable {
        @Volatile private var closed = false
        override fun close() { if (!closed) { closed = true; done() } }
    }

    /** The carrier's own process: an Xray panic there ends it, not the app. */
    private val host by lazy { com.mlmvpn.core.warp.AwgHostClient(com.mlmvpn.core.warp.XrayProtonCarrierService::class.java, "ProtonCarrierClient") }
    @Volatile private var appContext: Context? = null

    private class Running(
        val networkKey: String,
        val bypass: Bypass,
        val port: Int,
        /** For the log and the screen: how the config dials (a strategy id or «written»), never an address. */
        val via: String,
    )

    private val lock = Any()
    private var running: Running? = null
    private var leases = 0
    private var failedNet: String? = null
    private var failedAt = 0L

    /** How the carrier is dialling right now, for the screen; null when it is not running. */
    @Volatile var via: String? = null
        private set

    @Volatile private var possibleAt = 0L
    @Volatile private var possibleValue = false

    /**
     * Whether the user has any Cloudflare config this could ride. No network; remembered for a few
     * seconds, since a test asks it once per Proton server.
     */
    fun possible(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - possibleAt < 10_000) return possibleValue
        return candidates(context.applicationContext).isNotEmpty().also { possibleValue = it; possibleAt = now }
    }

    @Volatile private var warming = false

    /** Starts the carrier for [here] in the background, so the first dial does not wait for it. */
    fun warm(context: Context, here: FluxNet.Here) {
        if (warming) return
        running?.let { if (it.networkKey == here.key) return }
        warming = true
        val app = context.applicationContext
        thread(name = "proton-cf-warm", isDaemon = true) {
            try { acquire(app, here)?.close() } finally { warming = false }
        }
    }

    /**
     * A SOCKS port into Cloudflare on [here]'s network, or null when no Cloudflare config of the
     * user's carries data there. Blocking: seconds the first time on a network.
     */
    fun acquire(context: Context, here: FluxNet.Here): Lease? {
        // Unbound sockets would enter Proton's own tunnel: no network to bind to, no carrier.
        val network = here.network ?: return null
        val app = context.applicationContext
        synchronized(lock) {
            running?.let { r ->
                if (r.networkKey == here.key && listening(r.port)) {
                    leases++
                    return Lease(r.port) { release() }
                }
                stopLocked()
            }
            if (failedNet == here.key && System.currentTimeMillis() - failedAt < RETRY_MS) return null
            val r = start(app, here, network)
            if (r == null) {
                failedNet = here.key; failedAt = System.currentTimeMillis()
                return null
            }
            running = r; via = r.via; failedNet = null
            leases++
            return Lease(r.port) { release() }
        }
    }

    private fun release() {
        synchronized(lock) {
            leases = (leases - 1).coerceAtLeast(0)
            if (leases > 0) return
        }
        // A reconnect opens a new relay right after the old one closes: keep the core for it.
        thread(name = "proton-cf-idle", isDaemon = true) {
            Thread.sleep(IDLE_STOP_MS)
            synchronized(lock) { if (leases == 0) stopLocked() }
        }
    }

    private fun stopLocked() {
        val r = running ?: return
        running = null; via = null
        stopCore()
        r.bypass.close()
        Log.i(TAG, "carrier stopped")
    }

    // ---------------------------------------------------------------- choosing a config

    private class Candidate(val config: VpnConfig, val knownMs: Long?)

    /**
     * The user's Cloudflare configs this could ride (the connection list, the scanner's combinations,
     * the panels' own): the ones with a delay already measured first, fastest first; then a clean-IP
     * config (it carries the handshake its scan proved) before one that dials a name.
     */
    private fun candidates(app: Context): List<Candidate> {
        val nodes = runCatching { NodeManager(app).nodes.toList() }.getOrDefault(emptyList())
        val groups = GroupManager(app)
        runCatching { groups.loadScannerGroupsPublic() }
        runCatching { groups.loadCloudGroups() }
        val pool = nodes + groups.scannerGroups.flatMap { it.nodes } + groups.cloudGroups.flatMap { it.nodes }
        val seen = HashSet<String>()
        return pool.mapNotNull { n ->
            if (!seen.add(n.uri)) return@mapNotNull null
            val c = VpnConfig.parseUri(n.uri) ?: return@mapNotNull null
            if (c.protocol != "vless" && c.protocol != "trojan") return@mapNotNull null
            if (!CfUnfilter.isCloudflareFronted(c) || CfUnfilter.isLocalFront(c)) return@mapNotNull null
            Candidate(c, n.delay.removeSuffix("ms").trim().toLongOrNull()?.takeIf { it > 0 })
        }.sortedWith(compareBy<Candidate>({ it.knownMs == null }, { CfShape.literalFamily(it.config.address) == null }, { it.knownMs ?: Long.MAX_VALUE }))
    }

    /** The core's process is up and its SOCKS port answers (a loopback connect: a millisecond). */
    private fun listening(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
        true
    }.getOrDefault(false)

    /** Starts [json] in the carrier's process; false when it did not come up there. */
    private fun startCore(app: Context, json: String): Boolean = runCatching {
        host.call(app, com.mlmvpn.core.warp.XrayProbeService.MSG_START,
            android.os.Bundle().apply { putString(com.mlmvpn.core.warp.XrayProbeService.KEY_CONFIG, json) }, 20_000)
            ?.getBoolean(com.mlmvpn.core.warp.XrayProbeService.KEY_OK) == true
    }.getOrDefault(false)

    /** Ends the carrier's process (the service stops a core by ending its process, which cannot panic). */
    private fun stopCore() {
        val app = appContext ?: return
        runCatching { host.call(app, com.mlmvpn.core.warp.XrayProbeService.MSG_STOP, null, 5_000) }
    }

    private fun start(app: Context, here: FluxNet.Here, network: Network): Running? {
        appContext = app
        val all = candidates(app)
        if (all.isEmpty()) { Log.i(TAG, "no Cloudflare config to ride"); return null }
        val bypass = try { Bypass(network) } catch (e: Exception) { return null }
        val ech by lazy { fetchEch(network) }
        val cfnet = CfNetManager.get(app)
        val debuggable = (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        for (cand in all.take(MAX_CANDIDATES)) {
            val port = ServerSocket(0).use { it.localPort }
            val json = carrierConfig(cand.config, port, bypass.port, debuggable) { ech } ?: continue
            val how = cfnet.describe(cand.config, CfStage.CONNECT)
            if (!startCore(app, json)) {
                Log.i(TAG, "core did not start"); stopCore(); continue
            }
            val ms = probe(port)
            if (ms != null) {
                Log.i(TAG, "carrier up via=$how ${if (CfShape.literalFamily(cand.config.address) != null) "clean-ip" else "name"} in $ms ms")
                return Running(here.key, bypass, port, how)
            }
            Log.i(TAG, "candidate via=$how carried nothing")
            stopCore()
        }
        bypass.close()
        return null
    }

    /**
     * [c] as the carrier's core: a SOCKS inbound on [socksPort], [c]'s own outbound (with the
     * handshake its link carries, or the one the engine proved for CONNECT here) dialled through
     * [bypassPort]. An ECH config list given as «name+udp://resolver» is replaced by the key itself
     * ([ech]), fetched on the real network: the core would otherwise ask that resolver through
     * whatever tunnel is up -- Proton's own, once it is.
     */
    private fun carrierConfig(c: VpnConfig, socksPort: Int, bypassPort: Int, verbose: Boolean, ech: () -> String?): String? {
        val test = runCatching { JSONObject(XrayJsonGenerator.generateSpeedtestConfig(c)) }.getOrNull() ?: return null
        val outs = test.optJSONArray("outbounds") ?: return null
        val proxy = (0 until outs.length()).mapNotNull(outs::optJSONObject).firstOrNull { it.optString("tag") == "proxy" } ?: return null
        val stream = proxy.optJSONObject("streamSettings") ?: JSONObject().also { proxy.put("streamSettings", it) }
        stream.optJSONObject("tlsSettings")?.let { tls ->
            if (tls.optString("echConfigList").contains("://")) tls.put("echConfigList", ech() ?: return null)
        }
        val sockopt = stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }
        // A name goes to the bypass as a name and is resolved there, on the real network.
        sockopt.remove("domainStrategy")
        sockopt.put("dialerProxy", BYPASS_TAG)
        return JSONObject()
            .put("remarks", "mlm-proton-cf")
            // A debug build logs each connection the carrier takes (destination and outcome; no secrets).
            .put("log", JSONObject().put("loglevel", if (verbose) "info" else "warning"))
            .put("inbounds", JSONArray().put(JSONObject().put("tag", "proton-in").put("listen", "127.0.0.1").put("port", socksPort)
                .put("protocol", "socks").put("settings", JSONObject().put("auth", "noauth").put("udp", false))))
            .put("outbounds", JSONArray().put(proxy).put(JSONObject().put("tag", BYPASS_TAG).put("protocol", "socks")
                .put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().put("address", "127.0.0.1").put("port", bypassPort))))))
            .toString()
    }

    /** One real request through the carrier's SOCKS port: its time, or null. */
    private fun probe(port: Int): Long? = runCatching {
        val start = System.nanoTime()
        Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))).use { s ->
            s.soTimeout = PROBE_MS
            s.connect(InetSocketAddress.createUnresolved("www.gstatic.com", 80), PROBE_MS)
            s.getOutputStream().write("GET /generate_204 HTTP/1.0\r\nHost: www.gstatic.com\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n".toByteArray())
            val line = s.getInputStream().bufferedReader().readLine().orEmpty()
            if (line.contains(" 204") || line.contains(" 200")) (System.nanoTime() - start) / 1_000_000 else null
        }
    }.getOrNull()

    /**
     * Cloudflare's current ECH config list (base64), asked on [network] -- plain DNS, then DNS over
     * HTTPS -- never through a tunnel: the app's one fetch, [CfEchKey].
     */
    private fun fetchEch(network: Network): String? {
        val key = CfEchKey.fetchOn(network)
        if (key == null) Log.i(TAG, "no ECH key from DNS")
        return key
    }

    /**
     * A local SOCKS5 server (CONNECT only) whose outgoing sockets are bound to [network], the real
     * line: what the carrier's core dials Cloudflare through, so that connection never enters
     * Proton's tunnel. Bytes are passed on as they come, with Nagle off, so a hello the core sends
     * in pieces leaves in pieces.
     */
    private class Bypass(private val network: Network) : Closeable {
        private val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        @Volatile private var closed = false

        init { thread(name = "proton-cf-bypass", isDaemon = true) { acceptLoop() } }

        private fun acceptLoop() {
            while (!closed) {
                val c = try { server.accept() } catch (e: Exception) { if (closed) return else continue }
                thread(name = "proton-cf-conn", isDaemon = true) { serve(c) }
            }
        }

        private fun serve(client: Socket) {
            var target: Socket? = null
            try {
                client.tcpNoDelay = true
                client.soTimeout = 15_000
                val input = DataInputStream(client.getInputStream())
                val out = client.getOutputStream()
                if (input.readUnsignedByte() != 5) return
                repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
                out.write(byteArrayOf(5, 0)); out.flush()
                val ver = input.readUnsignedByte(); val cmd = input.readUnsignedByte(); input.readUnsignedByte()
                val host = when (input.readUnsignedByte()) {
                    1 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }).hostAddress
                    4 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress
                    3 -> String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) }, Charsets.US_ASCII)
                    else -> null
                }
                val port = input.readUnsignedShort()
                if (ver != 5 || cmd != 1 || host == null) { reply(out, 7); return }
                target = dial(host, port)
                if (target == null) { reply(out, 5); return }
                reply(out, 0)
                client.soTimeout = 0
                val t = target
                val down = thread(name = "proton-cf-down", isDaemon = true) { pump(t.getInputStream(), out); runCatching { client.shutdownOutput() } }
                pump(input, t.getOutputStream())
                runCatching { t.shutdownOutput() }
                down.join(60_000)
            } catch (_: Exception) {
            } finally {
                runCatching { target?.close() }
                runCatching { client.close() }
            }
        }

        private fun reply(out: OutputStream, code: Int) {
            out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
        }

        private fun dial(host: String, port: Int): Socket? {
            val addresses = if (CfShape.literalFamily(host) != null) listOf(InetAddress.getByName(host))
                else runCatching { network.getAllByName(host).toList() }.getOrDefault(emptyList())
            for (a in addresses) {
                val s = network.socketFactory.createSocket()
                try {
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(a, port), 8_000)
                    return s
                } catch (e: Exception) {
                    runCatching { s.close() }
                }
            }
            return null
        }

        private fun pump(from: InputStream, to: OutputStream) {
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = from.read(buf)
                    if (n < 0) break
                    to.write(buf, 0, n); to.flush()
                }
            } catch (_: Exception) { }
        }

        override fun close() {
            closed = true
            runCatching { server.close() }
        }
    }
}
