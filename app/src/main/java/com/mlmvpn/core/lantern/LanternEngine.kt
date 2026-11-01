package com.mlmvpn.core.lantern

import com.mlmvpn.scanner.utils.killHard
import com.mlmvpn.scanner.utils.waitUpTo
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.mlmvpn.core.tunnel.ConnectionLog
import com.mlmvpn.scanner.store.StoreEngines
import com.mlmvpn.scanner.store.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * «لنترن» on Android: Lantern 10's own client backend -- getlantern/radiance built with the `novpn`
 * tag and a `main` of ours (docs/LANTERN-BUILD.md) -- exec'd from nativeLibraryDir as
 * `liblantern.so` and driven as a local SOCKS5/HTTP proxy on [SOCKS_PORT]. The desktop's
 * lantern-manager.js, ported: the same command line, the same stdout contract, the same budgets.
 * The TUN is the VPN service's, reached through tun2proxy (TunnelVpnService.startLanternTunnel).
 *
 * Two things make it behave like Lantern's own phone app, which is what users compare it with:
 *
 *  - THE SERVER LIST IS KEPT. The core stores its last config in `v10/config.json` and connects from
 *    it at once (desktop: 0.67 s from launch to listening) while the refresh runs behind it.
 *  - THE FIRST LIST CAN RIDE ANOTHER ENGINE. Where Lantern's config service cannot be reached
 *    directly, only the control plane is sent through a WARP leg of the app's own
 *    ([com.mlmvpn.core.warp.WarpVia]) with `via HOST:PORT` on the core's stdin; Lantern's servers
 *    are always dialled directly.
 *
 * arm64 only: the core is pure Go without cgo, and armv7 does not build that way; a 32-bit phone is
 * told the engine is not available rather than handed a binary that cannot run.
 */
object LanternEngine {

    private const val TAG = "Lantern"
    const val STORE_ID = "lantern"
    const val LIB = "liblantern.so"

    /** The desktop's ports, continuing the fronts' block (Tor 20820, Psiphon 20830). Both answer SOCKS5 and HTTP. */
    const val SOCKS_PORT = 20840
    const val HTTP_PORT = 20841

    fun supported(): Boolean = Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" }

    fun available(ctx: Context): Boolean = supported() && StoreEngines.available(ctx.applicationContext, STORE_ID, LIB)

    fun dataDir(ctx: Context): File = File(ctx.applicationContext.filesDir, "lantern/v10")

    private fun configFile(dir: File) = File(dir, "config.json")
    private fun identityFile(ctx: Context) = File(dataDir(ctx), "mlm-direct.json")

    // ── what the page shows ─────────────────────────────────────────────────────────────────────

    data class Live(
        val running: Boolean = false,
        /** idle | starting | fetching | testing | up */
        val stage: String = "idle",
        val detail: String = "",
        /** How many servers the list in use holds. */
        val servers: Int = 0,
        /** This start connected from the list a previous one kept. */
        val cached: Boolean = false,
        /** A cold start still waiting for its first list. */
        val awaitingList: Boolean = false,
        /** The engine carrying the control plane for the first list, while one does. */
        val via: String? = null,
        /** The core's auto-select group picked a server that carried its test. */
        val proxyOk: Boolean = false,
        /** The core reported that every server on its list failed; it refetches by itself. */
        val exhausted: Boolean = false,
        val exitCity: String = "",
        val exitCountry: String = "",
        val exitCode: String = "",
        val error: String? = null,
        val log: List<String> = emptyList(),
    ) {
        val connected: Boolean get() = stage == "up"
    }

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> get() = _live

    private const val MAX_LOG = 80

    /** One line of the connection's story, in the user's language, for the page and the connection log. */
    fun record(line: String) {
        _live.update { it.copy(log = (it.log + line).takeLast(MAX_LOG)) }
        ConnectionLog.record("Lantern: $line")
    }

    fun beginSession(kept: Int) {
        _live.value = Live(running = true, stage = "starting", detail = tr("راه‌اندازی هسته", "Starting the core"), servers = kept, cached = kept > 0)
    }

    fun setStage(stage: String, detail: String, awaitingList: Boolean? = null) =
        _live.update { it.copy(stage = stage, detail = detail, awaitingList = awaitingList ?: it.awaitingList) }

    fun setVia(label: String?) = _live.update { it.copy(via = label) }

    fun setUp() = _live.update { it.copy(stage = "up", detail = "", error = null, awaitingList = false) }

    fun setError(message: String) = _live.update { it.copy(error = message) }

    fun endSession() = _live.update { it.copy(running = false, stage = "idle", detail = "", awaitingList = false, via = null) }

    /** Persian names of the countries the config service reports, so the page is not half English. */
    private val COUNTRY_FA = mapOf(
        "Germany" to "آلمان", "Netherlands" to "هلند", "United States" to "آمریکا", "USA" to "آمریکا",
        "France" to "فرانسه", "United Kingdom" to "انگلیس", "Canada" to "کانادا", "Sweden" to "سوئد", "Finland" to "فنلاند",
        "Singapore" to "سنگاپور", "Japan" to "ژاپن", "Ireland" to "ایرلند", "Poland" to "لهستان",
        "Switzerland" to "سوئیس", "Austria" to "اتریش", "Spain" to "اسپانیا", "Italy" to "ایتالیا",
        "Belgium" to "بلژیک", "Norway" to "نروژ", "Denmark" to "دانمارک", "India" to "هند",
        "Australia" to "استرالیا", "Brazil" to "برزیل", "Romania" to "رومانی", "Turkey" to "ترکیه",
        "Hong Kong" to "هنگ‌کنگ", "South Korea" to "کرهٔ جنوبی", "Mexico" to "مکزیک",
        "United Arab Emirates" to "امارات", "Chile" to "شیلی", "South Africa" to "آفریقای جنوبی",
        "Indonesia" to "اندونزی", "Malaysia" to "مالزی", "Taiwan" to "تایوان", "Czechia" to "چک",
        "Portugal" to "پرتغال", "Hungary" to "مجارستان", "Bulgaria" to "بلغارستان", "Lithuania" to "لیتوانی",
        "Latvia" to "لتونی", "Estonia" to "استونی", "Serbia" to "صربستان", "Armenia" to "ارمنستان",
        "Georgia" to "گرجستان", "Kazakhstan" to "قزاقستان", "Argentina" to "آرژانتین", "Colombia" to "کلمبیا",
    )

    /** "City — Country", in the user's language; empty while the core has not said. */
    fun exitLabel(l: Live = _live.value): String {
        if (l.exitCountry.isBlank() && l.exitCity.isBlank()) return ""
        val country = tr(COUNTRY_FA[l.exitCountry] ?: l.exitCountry.ifBlank { l.exitCode }, l.exitCountry.ifBlank { l.exitCode })
        return if (l.exitCity.isNotBlank()) "${l.exitCity} — $country" else country
    }

    // ── the tile's core ─────────────────────────────────────────────────────────────────────────

    @Volatile private var core: LanternProcess? = null

    /** Called off the main thread when the tile's core ends without being asked to. */
    @Volatile var onUnexpectedExit: ((Int) -> Unit)? = null

    val isAlive: Boolean get() = core?.isAlive == true

    data class Identity(val ip: String, val loc: String, val at: Long)

    /**
     * Starts the tile's core on [SOCKS_PORT]. [via]: a local SOCKS port to carry the control plane
     * from the start; [ident]: this phone's own address (and, through [via], its country), so the
     * config service places it where it is rather than where a helper comes out; [dns]: the
     * network's resolvers -- the core is pure Go and Android has no resolv.conf.
     */
    @Synchronized
    fun start(ctx: Context, smart: Boolean, via: Int?, ident: Identity?, dns: List<String>) {
        core?.stop()
        val p = LanternProcess(SOCKS_PORT, HTTP_PORT, dataDir(ctx), onEvent = ::onEvent, onExit = { code ->
            _live.update { it.copy(running = false) }
            onUnexpectedExit?.invoke(code)
        })
        core = p
        p.start(ctx, smart, via, ident, dns)
        _live.update { it.copy(running = true, error = null) }
    }

    /** One command on the core's stdin: `via HOST:PORT`, `via direct` or `fetch`. */
    fun command(line: String) { core?.command(line) }

    /** The core down: its stdin closed (it exits by itself), then made sure of. */
    @Synchronized
    fun stop() {
        core?.stop()
        core = null
        endSession()
    }

    /** Waits until the tile's core says it is listening; false on [timeoutMs], on a stop, or when it ends. */
    fun awaitListening(timeoutMs: Long, cancelled: () -> Boolean): Boolean =
        core?.awaitListening(timeoutMs, cancelled) ?: false

    fun carriesWarm(budgetMs: Long, cancelled: () -> Boolean): Boolean = carriesWarm(SOCKS_PORT, budgetMs) { cancelled() || !isAlive }

    /** The stdout contract (cmd/lanternproxy/main.go), as the tile's page and log tell it. */
    private fun onEvent(ev: String) {
        when {
            ev.startsWith("config ") -> {
                val c = Regex("^config (cached|fetched) servers=(\\d+)").find(ev) ?: return
                val n = c.groupValues[2].toIntOrNull() ?: 0
                val fresh = c.groupValues[1] == "fetched"
                val first = _live.value.awaitingList
                _live.update { it.copy(servers = n, exhausted = if (fresh) false else it.exhausted, awaitingList = if (fresh) false else it.awaitingList) }
                if (fresh && first) record(tr("فهرست لنترن رسید: ${fa(n)} سرور — برای دفعه‌های بعد ذخیره شد", "Lantern's list arrived: $n servers -- kept for next time"))
                else if (fresh) record(tr("فهرست سرورها به‌روز شد: ${fa(n)} سرور", "The server list was refreshed: $n servers"))
            }
            ev.startsWith("proxy ok") -> {
                if (!_live.value.proxyOk) record(tr("هسته یک سرور سالم انتخاب کرد", "The core picked a working server"))
                _live.update { it.copy(proxyOk = true, exhausted = false) }
            }
            ev.startsWith("exit ") -> {
                val parts = ev.removePrefix("exit ").split('|')
                _live.update {
                    it.copy(exitCity = parts.getOrNull(0)?.trim().orEmpty(), exitCountry = parts.getOrNull(1)?.trim().orEmpty(),
                        exitCode = parts.getOrNull(2)?.trim()?.uppercase(Locale.US).orEmpty())
                }
                record(tr("خروج از ${exitLabel()}", "Exit: ${exitLabel()}"))
            }
            ev.startsWith("exhausted") -> {
                _live.update { it.copy(exhausted = true, proxyOk = false) }
                record(tr("هیچ‌کدام از سرورهای فهرست جواب ندادند — هسته خودش فهرست تازه می‌گیرد",
                    "None of the listed servers answered -- the core fetches a new list by itself"))
            }
            ev.startsWith("error ") -> record(tr("خطای هسته: ", "Core error: ") + ev.removePrefix("error ").take(200))
        }
    }

    // ── the kept list ───────────────────────────────────────────────────────────────────────────

    /** Every server in the kept config of [dir]: outbounds and endpoints that name a remote address. */
    fun cachedServerCount(dir: File): Int = runCatching {
        val o = JSONObject(configFile(dir).readText()).optJSONObject("options") ?: return 0
        var n = 0
        for (key in listOf("outbounds", "endpoints")) {
            val a = o.optJSONArray(key) ?: continue
            for (i in 0 until a.length()) {
                val x = a.optJSONObject(i) ?: continue
                if (x.optString("tag").isBlank()) continue
                val peers = x.optJSONArray("peers")
                val hasPeer = peers != null && (0 until peers.length()).any { peers.optJSONObject(it)?.optString("address")?.isNotBlank() == true }
                if (x.optString("server").isNotBlank() || hasPeer) n++
            }
        }
        n
    }.getOrDefault(0)

    fun cachedServerCount(ctx: Context): Int = cachedServerCount(dataDir(ctx))

    /** When the kept list was written, or 0. */
    fun keptAt(ctx: Context): Long = configFile(dataDir(ctx)).takeIf { it.exists() }?.lastModified() ?: 0L

    /** The files that make a profile: the kept list, the account it was assigned to, and the device id. */
    internal val PROFILE_FILES = listOf("config.json", "config_raw.json", "servers.json", "settings.json", ".lanternsecrets/.deviceid")

    /**
     * The profile ([PROFILE_FILES]) copied from [from] when [to] has no list of its own: a core in a
     * folder of its own (Scout's, MAE's) starts from the tile's kept list instead of fetching one.
     */
    fun seedProfile(from: File, to: File) {
        if (File(to, "config.json").exists() || !File(from, "config.json").exists()) return
        for (name in PROFILE_FILES) {
            val src = File(from, name)
            if (!src.isFile) continue
            runCatching { File(to, name).apply { parentFile?.mkdirs() }.let { src.copyTo(it, overwrite = true) } }
        }
    }

    /**
     * «فهرست سرور تازه»: forget the kept list and the account it was assigned to, so the next start
     * asks for a new one. The device id stays; the free tier assigns servers per account, so a new
     * account is the closest thing to a new draw. sing-box's cache is not about servers and stays.
     */
    fun rotate(ctx: Context): Int {
        var removed = 0
        for (f in listOf("config.json", "config_raw.json", "servers.json", "settings.json")) {
            if (File(dataDir(ctx), f).delete()) removed++
        }
        return removed
    }

    // ── this phone, seen without any tunnel ─────────────────────────────────────────────────────

    fun readIdentity(ctx: Context): Identity? = runCatching {
        val o = JSONObject(identityFile(ctx).readText())
        Identity(o.getString("ip"), o.optString("loc"), o.optLong("at"))
    }.getOrNull()

    fun saveIdentity(ctx: Context, v: Identity) {
        runCatching {
            dataDir(ctx).mkdirs()
            identityFile(ctx).writeText(JSONObject().put("ip", v.ip).put("loc", v.loc).put("at", v.at).toString())
        }
    }

    /**
     * This phone's public address and country, asked over its own network: the app's own sockets
     * never enter its VPN, so what Cloudflare reports is the address Lantern's servers will see.
     * `www.cloudflare.com/cdn-cgi/trace` answers Iranian addresses and carries `ip=` and `loc=`.
     */
    fun directIdentity(timeoutMs: Int = 4_000): Identity? = runCatching {
        val c = URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            val body = c.inputStream.bufferedReader().use { it.readText().take(4096) }
            fun field(k: String) = body.lineSequence().firstOrNull { it.startsWith("$k=") }?.substringAfter('=')?.trim()
            val ip = field("ip")?.takeIf { it.isNotBlank() } ?: return null
            Identity(ip, field("loc").orEmpty().uppercase(Locale.US), System.currentTimeMillis())
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    /**
     * The resolvers of the network the phone is really on -- never a VPN's, which is what the active
     * network is while a tunnel is up. Link-local IPv6 resolvers need a zone Go cannot be given, so
     * they are left out; with nothing at all, two public ones, since a core with no resolver at all
     * asks 127.0.0.1:53 and every name fails.
     */
    fun systemDns(ctx: Context): List<String> {
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return FALLBACK_DNS
        val nets = runCatching {
            @Suppress("DEPRECATION")
            val all = cm.allNetworks.toList()
            (listOfNotNull(cm.activeNetwork) + all).distinct().filter { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@filter false
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }.getOrDefault(emptyList())
        val out = LinkedHashSet<String>()
        for (n in nets) {
            val lp = runCatching { cm.getLinkProperties(n) }.getOrNull() ?: continue
            for (a in lp.dnsServers) {
                when (a) {
                    is Inet4Address -> a.hostAddress?.let { out += it }
                    is Inet6Address -> if (!a.isLinkLocalAddress && a.scopeId == 0) a.hostAddress?.substringBefore('%')?.let { out += "[$it]:53" }
                }
            }
            if (out.isNotEmpty()) break
        }
        return out.toList().ifEmpty { FALLBACK_DNS }
    }

    private val FALLBACK_DNS = listOf("8.8.8.8", "1.1.1.1")

    // ── the data-path probe ─────────────────────────────────────────────────────────────────────

    /**
     * TWO hosts, both DNS-poisoned in Iran, and they must be different: resolved locally each is a
     * sinkhole, so a completed handshake proves the name travelled to the exit and the exit carried
     * it; and a core may cache a working route per domain, so a second pass on the same host would
     * say nothing about anything else.
     */
    private val PROBE_HOSTS = listOf("www.youtube.com", "www.instagram.com")

    /** How fast the SECOND host must answer: comfortably inside a browser's patience. */
    private const val WARM_MS = 9_000L

    /** Is the core on [port] carrying now -- for anything, not just one lucky host? The desktop's carriesWarm. */
    fun carriesWarm(port: Int, budgetMs: Long, cancelled: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + budgetMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (cancelled()) return false
            val left = deadline - SystemClock.elapsedRealtime()
            if (!carriesStream(port, PROBE_HOSTS[0], minOf(left, 20_000L), cancelled)) continue
            if (cancelled()) return false
            val second = minOf(WARM_MS, maxOf(2_000L, deadline - SystemClock.elapsedRealtime()))
            if (carriesStream(port, PROBE_HOSTS[1], second, cancelled)) return true
        }
        return false
    }

    /**
     * A FULL TLS HANDSHAKE through the SOCKS port, not a SOCKS reply: a SOCKS server may answer
     * "success" before it has dialled anything, so only the far server's half proves the path.
     */
    fun carriesStream(port: Int, host: String, timeoutMs: Long, cancelled: () -> Boolean = { false }): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (cancelled()) return false
            if (handshake(port, host, (deadline - SystemClock.elapsedRealtime()).coerceIn(1_000L, 12_000L).toInt())) return true
            if (SystemClock.elapsedRealtime() + 1_500 >= deadline) break
            try {
                Thread.sleep(1_500)
            } catch (e: InterruptedException) {
                // the tile is stopping: no answer, not a crash (1.2.42 #2006, #2013)
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    /** The tile's port, for the service's periodic check. */
    fun carriesStream(host: String, timeoutMs: Long): Boolean = carriesStream(SOCKS_PORT, host, timeoutMs)

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    /** Reachability is being tested, not the certificate chain. */
    private val tls by lazy { SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }.socketFactory }

    private fun handshake(port: Int, host: String, timeoutMs: Int): Boolean = try {
        Socket(Proxy.NO_PROXY).use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 3_000)
            s.soTimeout = timeoutMs
            val o = s.getOutputStream()
            val i = s.getInputStream()
            o.write(byteArrayOf(5, 1, 0)); o.flush()
            if (i.read() != 5 || i.read() != 0) return false
            val h = host.toByteArray(Charsets.US_ASCII)
            o.write(byteArrayOf(5, 1, 0, 3, h.size.toByte()) + h + byteArrayOf(0x01, 0xBB.toByte())); o.flush()
            if (i.read() != 5 || i.read() != 0) return false
            i.read()
            val skip = when (i.read()) { 1 -> 4; 4 -> 16; 3 -> i.read(); else -> return false }
            repeat(skip + 2) { if (i.read() < 0) return false }
            (tls.createSocket(s, host, 443, true) as SSLSocket).use { t ->
                t.sslParameters = t.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
                t.startHandshake()
                true
            }
        }
    } catch (_: Exception) {
        false
    }

    /** "None of the N servers answered" -- as the core's own fact, not a guess. Null unless it said so. */
    fun blockedMessage(): String? {
        val l = _live.value
        if (!l.exhausted || l.connected) return null
        return tr("هیچ‌کدام از ${fa(l.servers)} سرور فهرست فعلی لنترن از اینترنت شما جواب نداد. هسته خودش فهرست تازه می‌گیرد؛ «فهرست سرور تازه» را هم می‌توانید بزنید تا فهرست از نو گرفته شود. اگر آن هم نشد، فعلاً یکی از موتورهای دیگر را امتحان کنید.",
            "None of the ${l.servers} servers on Lantern's current list answered from your internet. The core fetches a new list by itself; you can also tap «Fresh server list» to get one now. If that does not help either, try another engine for now.")
    }

    internal fun fa(n: Int): String = tr(n.toString().map { c -> if (c in '0'..'9') '۰' + (c - '0') else c }.joinToString(""), n.toString())
}

/**
 * One running Lantern core: its own ports, its own data folder, its stdout contract
 * (cmd/lanternproxy/main.go) read into [onEvent] -- the tile's ([LanternEngine]) or Scout's.
 */
class LanternProcess(
    val socksPort: Int,
    val httpPort: Int,
    val dir: File,
    /** A contract line, without its `[lantern] ` prefix. */
    private val onEvent: (String) -> Unit = {},
    /** The core ended without being asked to. */
    private val onExit: (Int) -> Unit = {},
) {
    @Volatile private var proc: Process? = null
    @Volatile private var stdin: OutputStream? = null
    @Volatile private var stopping = false
    @Volatile var listening = false
        private set

    val isAlive: Boolean
        get() = proc?.let { p -> runCatching { p.exitValue(); false }.getOrDefault(true) } ?: false

    fun start(ctx: Context, smart: Boolean, via: Int?, ident: LanternEngine.Identity?, dns: List<String>) {
        val app = ctx.applicationContext
        dir.mkdirs()
        val tmp = File(app.cacheDir, "lantern-tmp").apply { mkdirs() }
        val args = buildList {
            add("-socks"); add("127.0.0.1:$socksPort")
            add("-http"); add("127.0.0.1:$httpPort")
            add("-configdir"); add(dir.absolutePath)
            // Exit when our end of stdin closes: an app that is killed must not leave a core holding
            // its port, which would make the next connect fail on bind.
            add("-control")
            if (smart) add("-smart")
            if (ident != null) { add("-clientip"); add(ident.ip) }
            if (via != null && via > 0) {
                add("-fetchvia"); add("127.0.0.1:$via")
                if (Regex("^[A-Z]{2}$").matches(ident?.loc.orEmpty())) { add("-country"); add(ident!!.loc) }
            }
            if (dns.isNotEmpty()) { add("-sysdns"); add(dns.joinToString(",")) }
            // The resolver for what the rules send direct -- the Iranian sites -- and for downloading
            // the Iran rule-sets that make them so. The core's default, "system", is the platform's
            // resolver, which on Android (no resolv.conf) asks [::1]:53 where nothing listens: the
            // rule-sets never loaded and every Iranian site went through Lantern's foreign servers
            // (1.2.42, phone test: aparat.com and digikala.com via Frankfurt). The network's own
            // resolver instead, an IPv4 one when there is one.
            val local = dns.firstOrNull { !it.startsWith("[") } ?: dns.firstOrNull()
            if (local != null) { add("-localdns"); add(local) }
        }
        val pb = ProcessBuilder(StoreEngines.command(app, LanternEngine.STORE_ID, LanternEngine.LIB) + args)
            .directory(dir)
            .redirectErrorStream(true)
        pb.environment().apply {
            // Go's os.TempDir() is /tmp without it, which an app cannot write.
            put("TMPDIR", tmp.absolutePath)
            put("HOME", dir.parentFile?.absolutePath ?: dir.absolutePath)
        }
        val p = pb.start()
        stopping = false
        listening = false
        proc = p
        stdin = p.outputStream
        Log.i(TAG, "started on $socksPort: ${args.joinToString(" ")}")
        Thread({ read(p) }, "lantern-$socksPort").apply { isDaemon = true }.start()
    }

    /** One command on stdin: `via HOST:PORT`, `via direct` or `fetch`. */
    @Synchronized
    fun command(line: String) {
        val out = stdin ?: return
        runCatching { out.write((line + "\n").toByteArray(Charsets.UTF_8)); out.flush() }
            .onFailure { Log.w(TAG, "command '$line' failed: ${it.message}") }
    }

    @Synchronized
    fun stop() {
        val p = proc ?: return
        stopping = true
        proc = null
        runCatching { stdin?.close() }
        stdin = null
        val gone = runCatching { p.waitUpTo(1500, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!gone) {
            // Seen on the phone: a core in the middle of a config fetch took five seconds to wind down.
            Log.i(TAG, "core on $socksPort still running 1.5 s after its stdin closed; terminating it")
            p.destroy()
            if (!runCatching { p.waitUpTo(1000, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
                runCatching { p.killHard() }
                // The next core takes the same ports and the same data folder: not while this one runs.
                if (!runCatching { p.waitUpTo(1000, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
                    Log.w(TAG, "core on $socksPort still running after it was killed")
                }
            }
        }
    }

    fun awaitListening(timeoutMs: Long, cancelled: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (listening) return true
            if (cancelled() || !isAlive) return false
            Thread.sleep(200)
        }
        return listening
    }

    private fun read(p: Process) {
        try {
            BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { r ->
                while (true) {
                    val line = r.readLine()?.trim() ?: break
                    val m = CONTRACT.find(line)
                    if (m == null) {
                        if (line.isNotBlank()) Log.d(TAG, line.take(300))
                        continue
                    }
                    val ev = m.groupValues[1]
                    Log.i(TAG, "[$socksPort] $ev")
                    if (ev.startsWith("listening")) listening = true
                    runCatching { onEvent(ev) }
                }
            }
        } catch (_: Exception) {
        }
        val code = runCatching { p.waitFor() }.getOrDefault(-1)
        Log.i(TAG, "core on $socksPort exited with $code")
        if (stopping || proc !== p) return
        proc = null
        runCatching { onExit(code) }
    }

    private companion object {
        const val TAG = "Lantern"
        val CONTRACT = Regex("^\\[lantern]\\s*(.*)$")
    }
}
