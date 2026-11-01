package com.mlmvpn.scanner.data

import android.content.Context
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * «کدام کانفیگ ایران روی این خط کار می‌کند؟» — answered by running each one.
 *
 * The serverless configs have no server: they pass the filter with a fragmented TLS ClientHello
 * and resolve names over DoH, and which of the two tricks a line lets through decides everything.
 *
 * This used to ask only the first question, by sending one plain HTTPS request straight to each
 * config's DoH endpoint. That gave the answers BACKWARDS (reported 2026-10-04): the original
 * upstream rows reach `cloudflare-dns.com` through their own fragmenting outbound, so the bare
 * request was filtered and a config that worked was marked dead; the rows that ask 8.8.8.8 got
 * through on the bare request and were marked good even where their fragmentation could not get
 * a single filtered site open.
 *
 * Now every config is started for real (a core of its own, four at a time, its own rules and
 * sniffing intact, only the port changed) and asked for a site that is filtered by SNI:
 *
 *   OK      — YouTube answered through it: the config gets past the filter on this line.
 *   PARTIAL — YouTube did not, but an unfiltered site did: it connects and resolves, but cannot
 *             open filtered sites here («فقط DNS تمیز» rows are this by design).
 *   FAIL    — nothing came back at all.
 */
object IranProfileTester {

    enum class Level { OK, PARTIAL, FAIL }

    /** What one profile did. [ms] is the filtered site's time when [level] is OK, else the open one's. */
    data class Verdict(val level: Level, val ms: Long, val why: String) {
        val ok: Boolean get() = level == Level.OK
    }

    /** Filtered by SNI in Iran: the thing these configs exist to open. */
    private const val FILTERED = "www.youtube.com"
    /** Not filtered: tells «connects but cannot pass the filter» from «nothing at all». */
    private const val OPEN = "www.google.com"

    /** A config that opens YouTube does so in 1-3 s; one that has not answered in 6 s will not. */
    private const val FILTERED_MS = 6_000
    private const val OPEN_MS = 5_000

    /**
     * How many configs run at once. One at a time (the first version) took minutes for nineteen
     * rows -- reported 2026-10-04 as far too slow. Each runs in a core of its own on its own port,
     * so four side by side cost no accuracy: a row's answer is still about that row alone.
     */
    private const val PARALLEL = 4

    /**
     * Run every profile in [configs] (id -> config JSON), [PARALLEL] at a time, and report each as
     * soon as it is known through [onResult]: a list that fills in row by row is one the user can
     * already act on.
     */
    suspend fun testAll(
        context: Context,
        configs: Map<String, String>,
        onResult: (String, Verdict) -> Unit = { _, _ -> },
    ): Map<String, Verdict> = kotlinx.coroutines.coroutineScope {
        val app = context.applicationContext
        val gate = kotlinx.coroutines.sync.Semaphore(PARALLEL)
        val out = java.util.concurrent.ConcurrentHashMap<String, Verdict>()
        configs.map { (id, config) ->
            launch(Dispatchers.IO) {
                gate.acquire()
                try {
                    val v = runCatching { testOne(app, config) }
                        .getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) throw e else Verdict(Level.FAIL, 0, "خطا") }
                    out[id] = v
                    withContext(Dispatchers.Main) { onResult(id, v) }
                } finally { gate.release() }
            }
        }.joinAll()
        configs.keys.associateWith { out[it] ?: Verdict(Level.FAIL, 0, "سنجیده نشد") }
    }

    /** One config in a core of its own: its rules and sniffing as written, only the port is ours. */
    private suspend fun testOne(context: Context, config: String): Verdict {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val json = try { withSocks(config, port) } catch (_: Exception) { return Verdict(Level.FAIL, 0, "کانفیگ خراب است") }
        val tls = probeFactory(context)
        // In the test host process (XrayHosts): a config that panics Xray ends that process, not the app.
        val core = com.mlmvpn.core.warp.VlessXrayInjector(0, quiet = true)
        try {
            if (!core.start(context, json, port)) return Verdict(Level.FAIL, 0, "هسته کانفیگ را نپذیرفت")
            if (!runInterruptible { listening(port, 3_000) }) return Verdict(Level.FAIL, 0, "اجرا نشد")
            runInterruptible { https204(tls, port, FILTERED, FILTERED_MS) }?.let { return Verdict(Level.OK, it, "") }
            runInterruptible { https204(tls, port, OPEN, OPEN_MS) }?.let { return Verdict(Level.PARTIAL, it, "یوتیوب باز نشد") }
            return Verdict(Level.FAIL, 0, "هیچ سایتی باز نشد")
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { core.stop() } }
        }
    }

    private fun withSocks(config: String, port: Int): String {
        val j = org.json.JSONObject(config)
        val old = j.optJSONArray("inbounds") ?: JSONArray()
        val own = old.optJSONObject(0)
        val inbounds = JSONArray().put(org.json.JSONObject()
            .put("tag", own?.optString("tag")?.ifBlank { null } ?: "socks")
            .put("listen", "127.0.0.1").put("port", port).put("protocol", "socks")
            .put("settings", org.json.JSONObject().put("auth", "noauth").put("udp", false))
            .apply { own?.optJSONObject("sniffing")?.let { put("sniffing", it) } })
        // Domain fronting reaches its own TLS terminators (`tunnel` inbounds) through `redirect`:
        // they stay, each moved to a free loopback port -- the same config may be connected while
        // it is tested, and its fixed ports would then be taken.
        for (i in 1 until old.length()) {
            val ib = old.optJSONObject(i) ?: continue
            if (ib.optString("protocol") != "tunnel") continue
            val from = ib.optInt("port")
            val to = java.net.ServerSocket(0).use { it.localPort }
            ib.put("port", to).put("listen", "127.0.0.1")
            val outs = j.optJSONArray("outbounds") ?: JSONArray()
            for (k in 0 until outs.length()) {
                val st = outs.optJSONObject(k)?.optJSONObject("settings") ?: continue
                if (st.optString("redirect") == "127.0.0.1:$from") st.put("redirect", "127.0.0.1:$to")
            }
            inbounds.put(ib)
        }
        j.put("inbounds", inbounds)
        j.put("log", org.json.JSONObject().put("loglevel", "none"))
        return j.toString()
    }

    private fun listening(port: Int, withinMs: Long): Boolean {
        val until = System.currentTimeMillis() + withinMs
        while (System.currentTimeMillis() < until) {
            if (runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) } }.isSuccess) return true
            Thread.sleep(100)
        }
        return false
    }

    /** Every outbound is server-less (direct / block / dns / loopback): a «کانفیگ ایران»-style config. */
    fun isServerless(config: org.json.JSONObject): Boolean {
        val outs = config.optJSONArray("outbounds") ?: return false
        if (outs.length() == 0) return false
        val kinds = setOf("freedom", "direct", "blackhole", "block", "dns", "loopback")
        return (0 until outs.length()).all { i ->
            val o = outs.optJSONObject(i) ?: return@all false
            val st = o.optJSONObject("settings")
            o.optString("protocol").lowercase() in kinds &&
                (st == null || (!st.has("vnext") && !st.has("servers") && !st.has("address") && !st.has("peers")))
        }
    }

    /**
     * The check a running serverless config must pass before it is called connected: through the
     * SOCKS port [port] the live core listens on, a filtered site, else an open one. FAIL means
     * nothing at all came back -- a config that only "started" is not a connection.
     */
    suspend fun liveCheck(context: Context, port: Int): Level = withContext(Dispatchers.IO) {
        val tls = probeFactory(context)
        if (runInterruptible { https204(tls, port, FILTERED, FILTERED_MS) } != null) Level.OK
        else if (runInterruptible { https204(tls, port, OPEN, OPEN_MS) } != null) Level.PARTIAL
        else Level.FAIL
    }

    /**
     * The trust the probe checks answers with: the system's, plus this device's own fronting
     * certificate ([com.mlmvpn.scanner.mitm.MitmCertManager]). Domain fronting answers every
     * request with a certificate it issues from that one, and the app -- unlike Chrome -- does not
     * take user-installed certificates, so with the system's alone the one config that gets past
     * the filter here read as dead and was refused at connect. Its key never leaves the device,
     * so only our own core can present a certificate this accepts.
     */
    private fun probeFactory(context: Context): SSLSocketFactory {
        val system = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ours = com.mlmvpn.scanner.mitm.MitmCertManager.loadCert(context) ?: return system
        return try {
            fun managerOf(ks: java.security.KeyStore?) = javax.net.ssl.TrustManagerFactory
                .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(ks) }.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().first()
            val sys = managerOf(null)
            val mine = managerOf(java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
                .apply { load(null); setCertificateEntry("mlm-fronting", ours) })
            val both = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) =
                    sys.checkClientTrusted(chain, authType)
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                    try { sys.checkServerTrusted(chain, authType) }
                    catch (_: java.security.cert.CertificateException) { mine.checkServerTrusted(chain, authType) }
                }
                override fun getAcceptedIssuers() = sys.acceptedIssuers + mine.acceptedIssuers
            }
            javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(both), null) }.socketFactory
        } catch (_: Exception) {
            system
        }
    }

    /**
     * `GET https://[host]/generate_204` through the config's SOCKS port, the name resolved by the
     * config itself (its DoH, its routing). The time in ms when the answer is a real 204, else null.
     */
    private fun https204(tls: SSLSocketFactory, port: Int, host: String, timeoutMs: Int): Long? = runCatching {
        val start = System.nanoTime()
        Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))).use { raw ->
            raw.soTimeout = timeoutMs
            raw.connect(InetSocketAddress.createUnresolved(host, 443), timeoutMs)
            tls.createSocket(raw, host, 443, true).use { s ->
                val tls = s as SSLSocket
                tls.sslParameters = tls.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
                tls.soTimeout = timeoutMs
                tls.startHandshake()
                tls.outputStream.write("GET /generate_204 HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
                tls.outputStream.flush()
                val line = tls.inputStream.bufferedReader().readLine().orEmpty()
                if (line.matches(Regex("HTTP/1\\.[01] 204(?: .*)?"))) (System.nanoTime() - start) / 1_000_000 else null
            }
        }
    }.getOrNull()

    /** The profiles this app ships, as id -> config JSON, read from the generated asset. */
    fun builtInConfigs(context: Context): Map<String, String> = try {
        val raw = com.mlmvpn.scanner.store.StoreFiles.open(context, "iran_profiles.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(raw)
        buildMap {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val cfg = o.optString("config", "")
                if (cfg.isNotEmpty()) put(NodeManager.iranNodeId(o.optString("id"), i), cfg)
            }
        }
    } catch (_: Exception) {
        emptyMap()
    }
}
