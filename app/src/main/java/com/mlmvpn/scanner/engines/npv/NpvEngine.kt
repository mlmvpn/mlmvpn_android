package com.mlmvpn.scanner.engines.npv

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.FileProvider
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.data.CombineEngine
import com.mlmvpn.scanner.data.VpnSwitch
import com.mlmvpn.scanner.engines.freeconfig.FreeConfigEngine
import com.mlmvpn.scanner.engines.npv.NpvLibrary.Entry
import com.mlmvpn.scanner.engines.npv.NpvLibrary.Kind
import com.mlmvpn.scanner.engines.npv.NpvLibrary.Shelf
import com.mlmvpn.scanner.update.UpdateNet
import com.mlmvpn.scanner.utils.LocalPort
import com.mlmvpn.scanner.utils.NetworkSettings
import com.mlmvpn.scanner.utils.SafeStart
import com.mlmvpn.scanner.utils.VpnConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * «NPV»: imports, keeps, measures and runs what the library holds (see [NpvLibrary]).
 *
 *  - Standard links connect on [MyVpnService], the way Outline's keys do, so split tunnelling, LAN
 *    sharing, proxy mode and the Smart Iran route apply unchanged.
 *  - Native NPV Tunnel files are kept and handed to NPV Tunnel; never decrypted here.
 *  - «بررسی کانفیگ‌های تازه» ([startRefresh]) reads the 100 default channels, the user's own and the
 *    public lists, step by step: Telegram first (directly, else through the app's own running
 *    tunnel), then which channels posted something new, then the download into «انبار». It asks
 *    before it turns the app's tunnel off and before it tests and prunes.
 */
object NpvEngine {

    const val NODE_ID = "npv"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A measurement that outlives its wait (a core that never answers) is abandoned here, not awaited. */
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Public subscription lists (docs/URNETWORK-NPV-PLAN.md, 4 C, and others). Checked 2026-10-18:
     * the plan's Au1rxx, morpheusadam and therealaleph lists answer 404 and are not here.
     */
    private val EXTRA_LISTS = listOf(
        "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/All_Configs_Sub.txt",
        "https://raw.githubusercontent.com/V2RayRoot/V2RayConfig/main/Config/vless.txt",
        "https://raw.githubusercontent.com/V2RayRoot/V2RayConfig/main/Config/vmess.txt",
        "https://raw.githubusercontent.com/V2RayRoot/V2RayConfig/main/Config/trojan.txt",
        "https://raw.githubusercontent.com/V2RayRoot/V2RayConfig/main/Config/shadowsocks.txt",
        "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt",
        "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt",
        "https://raw.githubusercontent.com/ALIILAPRO/v2rayNG-Config/main/sub.txt",
        "https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/v2ray/all_sub.txt",
        "https://raw.githubusercontent.com/MhdiTaheri/V2rayCollector/main/sub/mix",
        "https://raw.githubusercontent.com/SoliSpirit/v2ray-configs/main/all_configs.txt",
        // Iranian collectors (Iranian channels, or tested for Iran), checked 2026-10-19.
        "https://raw.githubusercontent.com/youfoundamin/V2rayCollector/main/mixed_iran.txt",
        "https://raw.githubusercontent.com/Surfboardv2ray/TGParse/main/splitted/mixed",
        "https://raw.githubusercontent.com/NiREvil/vless/main/sub/SSTime",
        "https://raw.githubusercontent.com/10ium/free-config/main/HighSpeed.txt",
        "https://raw.githubusercontent.com/Rayan-Config/C-Sub/main/configs/proxy.txt",
        "https://raw.githubusercontent.com/Mahdi0024/ProxyCollector/master/sub/proxies.txt",
        "https://raw.githubusercontent.com/hans-thomas/v2ray-subscription/master/servers.txt",
        "https://raw.githubusercontent.com/peasoft/NoMoreWalls/master/list_raw.txt",
        "https://raw.githubusercontent.com/MrPooyaX/VpnsFucking/main/Shenzo.txt",
        "https://raw.githubusercontent.com/Danialsamadi/v2go/main/AllConfigsSub.txt",
        "https://raw.githubusercontent.com/arshiacomplus/v2rayExtractor/main/mix/sub.html",
        "https://raw.githubusercontent.com/AzadNetCH/Clash/main/AzadNet.txt",
        "https://raw.githubusercontent.com/ShatakVPN/ConfigForge-V2Ray/main/configs/all.txt",
        "https://raw.githubusercontent.com/Pawdroid/Free-servers/main/sub",
    )
    private const val SOURCES = "npv/sources.json"
    private const val CHANNELS = "npv/channels.json"
    private const val DEAD = "npv/dead.json"

    /** A channel page with no posts after the newest one: small, and only Telegram answers it with a channel header. */
    private const val PROBE = "https://t.me/s/telegram?after=999999999"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    /** A config that failed (or was deleted) stays out of the refreshes this long. */
    private const val DEAD_FOR_MS = 3 * 24 * 3_600_000L
    /** Channel files kept in the list, newest first. */
    private const val MAX_FILES = 300
    /** Untested configs kept in «انبار» before the oldest untested ones go. */
    private const val MAX_STORE = 3000
    /** New configs one refresh takes from the public lists (the channels' own are all taken). */
    private const val LIST_CAP = 800
    /** Subscription links from posts fetched in one refresh. */
    private const val SUB_CAP = 15
    /** On a first run, this many of the shipped configs go to the main page; the rest to «انبار». */
    private const val MAIN_SEED = 30

    /**
     * The owner's run that fills assets/npv/seed.txt takes far more from the lists. Switched on by a
     * file only a debug build can be given (`run-as … touch files/npv/harvest`); no user sees it.
     */
    private fun harvest(context: Context) = File(context.filesDir, "npv/harvest").exists()

    /** Pages of history the harvest reads per channel (20 posts each). */
    private const val HARVEST_PAGES = 12

    private val LINK = Regex("""(?<![A-Za-z0-9+.-])((?:vless|vmess|trojan|ss|socks|hysteria2|hy2|tuic)://[^\s"'<>`]+)""")
    private val HTTPS = Regex("""https://[^\s"'<>`]+""")
    private val DOC = Regex("""tgme_widget_message_document_title[^>]*>([^<]+)<""")
    private val DOC_SIZE = Regex("""tgme_widget_message_document_extra[^>]*>([^<]+)<""")
    private val HREF = Regex("""href="([^"]+)"""")
    private val BLOCK_END = Regex("""(?i)<br\s*/?>|</(?:pre|code|div|p|blockquote|li)>""")
    private val TAG = Regex("""<[^>]*>""")
    private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);""")

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(true).followSslRedirects(true).build()
    }

    // ---------------------------------------------------------------- library

    private val _revision = MutableStateFlow(0)

    /** Bumped on every write to the library, so the screens reload. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val lock = Any()

    private fun write(context: Context, entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().put("id", e.id).put("name", e.name).put("kind", e.kind.name)
                .put("uri", e.uri ?: JSONObject.NULL).put("source", e.source).put("addedAt", e.addedAt)
                .put("shelf", e.shelf.name).put("ping", e.ping).put("pingAt", e.pingAt))
        }
        val f = File(context.filesDir, NpvLibrary.FILE).apply { parentFile?.mkdirs() }
        val tmp = File(f.path + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Read, change and write under one lock; [block] says whether it changed anything. */
    private fun edit(context: Context, block: (MutableList<Entry>) -> Boolean): Boolean {
        val changed = synchronized(lock) {
            val all = NpvLibrary.entries(context).toMutableList()
            block(all).also { if (it) write(context, all) }
        }
        if (changed) _revision.update { it + 1 }
        return changed
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }.take(16)

    /** What makes two links one server: everything that changes where and how it connects, not the name. */
    internal fun keyOf(uri: String): String {
        val c = VpnConfig.parseUri(uri) ?: return uri.substringBefore('#')
        val secret = if (c.protocol == "ss") "${c.method}:${c.password}" else c.uuid.ifBlank { c.password }
        return listOf(
            c.protocol, c.address.lowercase(), c.port, secret, c.network,
            c.wsHost.ifBlank { c.xhttpHost }.lowercase(), c.wsPath.ifBlank { c.xhttpPath }.ifBlank { c.serviceName },
            c.sni.lowercase(), c.publicKey,
        ).joinToString("|")
    }

    /** A link the Xray engine can parse into an address. (A parameter where it is used, so the page parser tests off the phone.) */
    private val parsable: (String) -> Boolean = { VpnConfig.parseUri(it)?.address?.isNotBlank() == true }

    /** Links found in [text]; standard schemes only. */
    fun extractLinks(text: String, accept: (String) -> Boolean = parsable): List<String> =
        LINK.findAll(text).map { it.groupValues[1].trimEnd(')', ',', '.', ';', ']', '}') }
            .filter(accept).distinct().toList()

    private fun nameOf(uri: String): String =
        uri.substringAfter('#', "").let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            .ifBlank { VpnConfig.parseUri(uri)?.address ?: uri.take(24) }

    /**
     * Adds the links in [text] (pasted, shared, or a text file) to the main page. One already in
     * «انبار» moves to the main page instead of coming in twice. Returns how many arrived.
     */
    fun importText(context: Context, text: String, source: String = "mine", shelf: Shelf = Shelf.MAIN): Int {
        val links = extractLinks(text)
        if (links.isEmpty()) return 0
        var added = 0
        edit(context) { all ->
            val at = HashMap<String, Int>()
            all.forEachIndexed { i, e -> if (e.kind == Kind.LINK && e.uri != null) at[keyOf(e.uri)] = i }
            val now = System.currentTimeMillis()
            links.forEach { uri ->
                val k = keyOf(uri)
                val i = at[k]
                when {
                    i == null -> {
                        all += Entry("l" + sha(uri.toByteArray()), nameOf(uri), Kind.LINK, uri, source, now, shelf)
                        at[k] = all.size - 1
                        added++
                    }
                    all[i].shelf != shelf -> { all[i] = all[i].copy(shelf = shelf); added++ }
                }
            }
            added > 0
        }
        return added
    }

    /** A file: an NPV Tunnel file is kept as it is; anything else is read for links. */
    fun importFile(context: Context, name: String, bytes: ByteArray): Int {
        val lower = name.lowercase()
        val head = String(bytes.copyOfRange(0, minOf(5, bytes.size)), Charsets.ISO_8859_1)
        val native = lower.endsWith(".npvt") || lower.endsWith(".npvs") || head.startsWith("NPVT1") || head.startsWith("NPVS")
        if (!native) return importText(context, String(bytes, Charsets.UTF_8))
        val id = "n" + sha(bytes)
        val ext = if (lower.endsWith(".npvs") || head.startsWith("NPVS")) "npvs" else "npvt"
        var added = 0
        edit(context) { all ->
            val had = all.indexOfFirst { it.id == id }
            if (had >= 0) {
                // already here; a name a chat's stored file gave it («2_86254….npvs») gives way to its real one
                val stored = Regex("^-?\\d+_-?\\d+\\.npv[st]$")
                if (stored.matches(all[had].name) && name.isNotBlank() && !stored.matches(name)) {
                    all[had] = all[had].copy(name = name)
                    return@edit true
                }
                return@edit false
            }
            File(context.filesDir, "npv/native").mkdirs()
            File(context.filesDir, "npv/native/$id.$ext").writeBytes(bytes)
            all += Entry(id, name.ifBlank { "$id.$ext" }, Kind.NATIVE, null, "mine", System.currentTimeMillis(), Shelf.MAIN)
            added = 1
            true
        }
        return added
    }

    /** Removes these entries. A free config removed stays out of the next refreshes for a while. */
    fun remove(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toHashSet()
        val gone = ArrayList<Entry>()
        edit(context) { all ->
            all.removeAll { e -> (e.id in set).also { if (it) gone += e } }
            gone.isNotEmpty()
        }
        rememberDead(context, gone.filter { it.kind == Kind.LINK && it.source != "mine" }.mapNotNull { it.uri })
        gone.filter { it.kind == Kind.NATIVE }.forEach { e ->
            File(context.filesDir, "npv/native").listFiles()?.filter { it.name.startsWith("${e.id}.") }?.forEach { it.delete() }
        }
    }

    fun move(context: Context, ids: Collection<String>, to: Shelf) {
        val set = ids.toHashSet()
        edit(context) { all ->
            var n = 0
            for (i in all.indices) if (all[i].id in set && all[i].shelf != to) { all[i] = all[i].copy(shelf = to); n++ }
            n > 0
        }
    }

    private fun linksOn(context: Context, shelf: Shelf) = NpvLibrary.entries(context).filter { it.shelf == shelf && it.kind == Kind.LINK }

    /** Every config on [shelf] to the other one («انتقال همه»). */
    fun moveAll(context: Context, from: Shelf, to: Shelf) = move(context, linksOn(context, from).map { it.id }, to)

    /** Every config on [shelf] («حذف همه»). The user's NPV Tunnel files stay. */
    fun clear(context: Context, shelf: Shelf) = remove(context, linksOn(context, shelf).map { it.id })

    /** The configs on [shelf] whose last measurement got no answer («حذف خراب‌ها»). */
    fun removeBroken(context: Context, shelf: Shelf): Int {
        val ids = linksOn(context, shelf).filter { it.broken }.map { it.id }
        remove(context, ids)
        return ids.size
    }

    /** A connect to this one ended without data: the next tap picks another. */
    fun markFailed(context: Context, id: String) {
        edit(context) { all ->
            val i = all.indexOfFirst { it.id == id }
            if (i < 0) false else { all[i] = all[i].copy(ping = -1, pingAt = System.currentTimeMillis()); true }
        }
    }

    private fun deadKeys(context: Context): MutableMap<String, Long> {
        val out = HashMap<String, Long>()
        runCatching {
            val o = JSONObject(File(context.filesDir, DEAD).readText())
            val now = System.currentTimeMillis()
            o.keys().forEach { k -> val t = o.optLong(k); if (now - t < DEAD_FOR_MS) out[k] = t }
        }
        return out
    }

    private fun rememberDead(context: Context, uris: List<String>) {
        if (uris.isEmpty()) return
        synchronized(lock) {
            val m = deadKeys(context)
            val now = System.currentTimeMillis()
            uris.forEach { m[keyOf(it)] = now }
            val o = JSONObject()
            m.forEach { (k, v) -> o.put(k, v) }
            File(context.filesDir, DEAD).apply { parentFile?.mkdirs() }.writeText(o.toString())
        }
    }

    /** Hands a kept NPV Tunnel file to NPV Tunnel (or whatever opens it). False when nothing can. */
    fun openNative(context: Context, entry: Entry): Boolean {
        val src = File(context.filesDir, "npv/native").listFiles()?.firstOrNull { it.name.startsWith("${entry.id}.") } ?: return false
        val dir = File(context.cacheDir, "npv-open").apply { mkdirs() }
        val out = File(dir, entry.name.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.contains('.')) it else "$it.${src.extension}" })
        src.copyTo(out, overwrite = true)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", out)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/octet-stream")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(Intent.createChooser(view, entry.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    /** Opens a channel post in Telegram, where a file is fetched. */
    fun openPost(context: Context, link: String): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)

    // ---------------------------------------------------------------- measure («سنجش»)

    data class Measure(val shelf: Shelf? = null, val done: Int = 0, val total: Int = 0, val working: Int = 0, val running: Boolean = false)

    private val _measure = MutableStateFlow(Measure())
    val measuring: StateFlow<Measure> = _measure.asStateFlow()

    private val _live = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Results of the measurement running now, by entry id, before they are written to the library. */
    val live: StateFlow<Map<String, Long>> = _live.asStateFlow()

    private var measureJob: Job? = null

    /** «سنجش»: every config on [shelf] for real on this internet; the list then sorts fastest first. */
    fun measure(context: Context, shelf: Shelf) {
        if (_measure.value.running || _refresh.value.step == Step.TEST && _refresh.value.running) return
        val app = context.applicationContext
        val list = linksOn(app, shelf).filter(NpvLibrary::isRunnable)
        if (list.isEmpty()) return
        _measure.value = Measure(shelf, 0, list.size, 0, true)
        measureJob = scope.launch {
            try {
                measureEntries(app, list) { done, ok -> _measure.update { it.copy(done = done, working = ok) } }
            } finally {
                _measure.update { it.copy(running = false) }
            }
        }
    }

    fun stopMeasure() { measureJob?.cancel() }

    /** Measures [list] and writes each result to the library, also when stopped halfway. Returns how many answered. */
    private suspend fun measureEntries(context: Context, list: List<Entry>, onProgress: (done: Int, working: Int) -> Unit): Int {
        val results = ConcurrentHashMap<String, Long>()
        var done = 0
        var ok = 0
        try {
            probeAll(context, list.mapNotNull { e -> e.uri?.let { e.id to it } }) { id, ms ->
                results[id] = ms
                _live.update { it + (id to ms) }
                synchronized(results) {
                    done++
                    if (ms > 0) ok++
                    onProgress(done, ok)
                }
            }
        } finally {
            if (results.isNotEmpty()) {
                val now = System.currentTimeMillis()
                edit(context) { all ->
                    var n = 0
                    for (i in all.indices) results[all[i].id]?.let { all[i] = all[i].copy(ping = it, pingAt = now); n++ }
                    n > 0
                }
            }
            _live.update { it - results.keys }
        }
        return ok
    }

    /**
     * Every config for real: a quick knock on its port first when the address is an IP (most dead
     * ones end there, in two seconds instead of a full core start), then a real request through it.
     * [onResult] gets the delay in ms, or -1.
     */
    private suspend fun probeAll(context: Context, items: List<Pair<String, String>>, onResult: (String, Long) -> Unit) = coroutineScope {
        FreeConfigEngine.ensureXrayEnv(context)
        CombineEngine.ensureCoreEnv(context)
        com.mlmvpn.scanner.engines.cfnet.CfNetManager.get(context)
        // As the app's own delay test does (NodesTab): with no verdict on this network yet, one
        // Cloudflare config settles whether IPv4 carries data here, so every Cloudflare config
        // below is measured the way a connect runs it -- on an IPv6 edge when IPv4 carries nothing.
        if (com.mlmvpn.scanner.data.CfFamily.current(context) == null) {
            val cf = items.asSequence().mapNotNull { VpnConfig.parseUri(it.second) }.filter { com.mlmvpn.scanner.data.CfEdgeHeal.isCfFronted(it) }.take(40).toList()
            (cf.firstOrNull { com.mlmvpn.scanner.data.CfEdgeHeal.v4ToLong(it.address) != null } ?: cf.firstOrNull())
                ?.let { runCatching { com.mlmvpn.scanner.data.CfEdgeHeal.heal(context, it, probe = true) } }
        }
        // The harvest also starts from the Cloudflare handshake a connect would end up with: the
        // connect proves one for this network when its first try carries nothing (MyVpnService's
        // self-healing), and generateSpeedtestConfig stamps the proven one on every Cloudflare test.
        if (harvest(context)) runCatching {
            withTimeoutOrNull(75_000) {
                val won = com.mlmvpn.scanner.engines.cfnet.CfNetManager.get(context).ensure(
                    com.mlmvpn.scanner.engines.cfnet.CfStage.CONNECT,
                    budget = com.mlmvpn.scanner.engines.cfnet.StrategyEngine.StageBudget(parallel = 2, deadlineMs = 60_000),
                )
                android.util.Log.i("NpvEngine", "harvest: Cloudflare CONNECT handshake here = ${won?.id ?: "none proven"}")
            }
        }
        // Wide, because a refresh brings thousands (about two thousand from the channels alone) and
        // most of them are dead: 24 real tests and 48 knocks at a time on a capable phone, a
        // quarter of that on a small one (ThreadBudget, the same budget the delay cores share).
        val knock = Semaphore(com.mlmvpn.scanner.utils.ThreadBudget.parallel(48))
        val real = Semaphore(com.mlmvpn.scanner.utils.ThreadBudget.parallel(24))
        items.map { (id, uri) ->
            async(Dispatchers.IO) {
                val cfg = VpnConfig.parseUri(uri)
                val ms = when {
                    cfg == null || cfg.address.isBlank() -> -1L
                    // A server that does not take even a TCP connection is dead: two seconds instead
                    // of a core start and its timeout. Not for a Cloudflare one: the connect may move
                    // it to another edge, so its own address being closed says nothing about it.
                    !com.mlmvpn.scanner.data.CfEdgeHeal.isCfFronted(cfg) && !knock.withPermit { reachable(cfg) } -> -1L
                    else -> real.withPermit { realDelay(context, uri) }
                }
                onResult(id, ms)
            }
        }.awaitAll()
    }

    /** TCP to the server: its IP as written, or its name's address over DoH (as the connect pins it). */
    private suspend fun reachable(cfg: VpnConfig): Boolean {
        val host = cfg.address.trim('[', ']')
        val ip = if (isIpLiteral(host)) host else com.mlmvpn.scanner.utils.DomainPreResolver.resolve(host).firstOrNull() ?: return false
        return portOpen(ip, cfg.port)
    }

    private suspend fun realDelay(context: Context, uri: String): Long {
        // One tester that never answers must not hold the rest (seen on the phone: 675 of 684 and
        // then nothing), so the wait has a ceiling of its own and the probe is left behind.
        val probe = probeScope.async { runCatching { measureAsConnected(context, uri) }.getOrDefault(0L) }
        // 6 s for the request, the name's DoH lookup (6 s at most, once per name), and for a
        // Cloudflare config a second edge and a second request.
        val ms = withTimeoutOrNull(26_000) { probe.await() } ?: 0L.also { probe.cancel() }
        return if (ms > 0) ms else -1L
    }

    /**
     * One real request through [uri], dialled the way the connect dials it:
     *  - a Cloudflare-fronted config goes onto an IPv6 edge when IPv4 carries nothing here
     *    (CfEdgeHeal, as the app's own delay test and the connect do; measured on the phone
     *    2026-10-18: without it nearly every Cloudflare config read as dead);
     *  - a server NAME is looked up over DoH and the address pinned (MyVpnService,
     *    DomainPreResolver), because the line's own resolver answers some names with the filter's
     *    address (10.10.34.36). The name stays the TLS name and the Host header.
     */
    private suspend fun measureAsConnected(context: Context, uri: String): Long {
        val original = VpnConfig.parseUri(uri) ?: return 0L
        var cfg = com.mlmvpn.scanner.data.CfEdgeHeal.heal(context, original, probe = false)
        if (!isIpLiteral(cfg.address)) {
            com.mlmvpn.scanner.utils.DomainPreResolver.resolve(cfg.address).firstOrNull()?.let { ip ->
                cfg = com.mlmvpn.scanner.data.CfEdgeHeal.onto(cfg, ip)
            }
        }
        // Six seconds: a working config answers in one or two (the slowest seen on the phone, under
        // one), and a dead one must not hold a slot for longer.
        val ms = CombineEngine.measureDelay(com.mlmvpn.scanner.utils.XrayJsonGenerator.generateSpeedtestConfig(cfg), timeoutMs = 6_000)
        if (ms > 0 || !com.mlmvpn.scanner.data.CfEdgeHeal.isCfFronted(original)) return ms
        // A Cloudflare config whose edge carried nothing: the connect does not stop there either
        // (MyVpnService heals onto another way), so neither does the measurement -- the same Worker
        // through an edge, or a family, proven on this network.
        val healed = com.mlmvpn.scanner.data.CfEdgeHeal.heal(context, original, probe = true)
        if (healed === original || healed.address == cfg.address) return ms
        return CombineEngine.measureDelay(com.mlmvpn.scanner.utils.XrayJsonGenerator.generateSpeedtestConfig(healed), timeoutMs = 6_000)
    }

    private fun isIpLiteral(host: String): Boolean {
        val h = host.trim('[', ']')
        return h.contains(':') || Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(h)
    }

    private fun portOpen(host: String, port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), 2_000) }
        true
    }.getOrDefault(false)

    /**
     * The one to connect: the fastest that answered on the main page, else in «انبار»; with nothing
     * measured, the newest on the main page (the shipped list is in the order it was measured in).
     */
    fun best(entries: List<Entry>, live: Map<String, Long> = emptyMap()): Entry? {
        fun p(e: Entry) = live[e.id] ?: e.ping
        val run = entries.filter(NpvLibrary::isRunnable)
        val main = run.filter { it.shelf == Shelf.MAIN }
        val store = run.filter { it.shelf == Shelf.STORE }
        return main.filter { p(it) > 0 }.minByOrNull { p(it) }
            ?: store.filter { p(it) > 0 }.minByOrNull { p(it) }
            ?: main.filter { p(it) == 0L }.maxByOrNull { it.addedAt }
            ?: store.filter { p(it) == 0L }.maxByOrNull { it.addedAt }
    }

    // ---------------------------------------------------------------- connect

    /** Takes the tunnel from any other engine of the app first, as every Xray-side connect does. */
    fun connect(context: Context, uri: String) {
        val app = context.applicationContext
        scope.launch {
            com.mlmvpn.scanner.ui.tunnel.TunnelExclusion.releaseForXray(app)
            SafeStart.service(app, Intent(app, MyVpnService::class.java).apply {
                putExtra("NODE_URI", uri)
                putExtra("NODE_ID", NODE_ID)
                putExtra("MTU_PROFILE", NetworkSettings.Method.NPV.id)
                putExtra("PROXY_MODE", NetworkSettings.proxyMode(app))
                putExtra("LOCAL_PORT", LocalPort.getString(app))
            })
        }
    }

    fun disconnect(context: Context) {
        if (MyVpnService.connectedNodeIdFlow.value == NODE_ID) {
            val app = context.applicationContext
            SafeStart.service(app, Intent(app, MyVpnService::class.java).apply { action = "STOP" })
        }
    }

    fun isOurs(): Boolean = MyVpnService.connectedNodeIdFlow.value == NODE_ID && MyVpnService.connectionPhaseFlow.value != MyVpnService.Phase.IDLE

    // ---------------------------------------------------------------- channels

    /** The Telegram channels the user added, as usernames. */
    fun userChannels(context: Context): List<String> = runCatching {
        val arr = JSONArray(File(context.filesDir, SOURCES).readText())
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    private fun saveUserChannels(context: Context, list: List<String>) {
        File(context.filesDir, SOURCES).apply { parentFile?.mkdirs() }.writeText(JSONArray(list).toString())
        _revision.update { it + 1 }
    }

    /** `t.me/x`, `@x`, `https://t.me/s/x`, `tg://resolve?domain=x` -> `x`; null when it is not a channel. */
    fun channelOf(input: String): String? {
        val s = input.trim()
        val name = Regex("""(?:t\.me/(?:s/)?|telegram\.me/(?:s/)?|domain=|^@)([A-Za-z0-9_]{4,64})""").find(s)?.groupValues?.get(1)
            ?: s.takeIf { Regex("^[A-Za-z0-9_]{4,64}$").matches(it) }
        return name?.takeIf { it.lowercase() !in setOf("joinchat", "proxy", "share", "addstickers", "addemoji", "socks") }
    }

    /** Adds a channel; null when [input] is not one. */
    fun addChannel(context: Context, input: String): String? {
        val ch = channelOf(input) ?: return null
        val list = userChannels(context)
        if (list.none { it.equals(ch, true) }) saveUserChannels(context, list + ch)
        return ch
    }

    fun removeChannel(context: Context, channel: String) = saveUserChannels(context, userChannels(context).filterNot { it == channel })

    /** The channels the app ships as default sources (assets/npv/channels.txt). */
    fun defaultChannels(context: Context): List<String> = runCatching {
        context.assets.open("npv/channels.txt").bufferedReader().readLines().mapNotNull { channelOf(it) }
    }.getOrDefault(emptyList())

    /** What the last refresh found in one channel. */
    data class ChannelState(
        /** The newest post read; the next refresh asks only for posts after it. */
        val lastId: Long = 0,
        val checkedAt: Long = 0,
        /** When something new was last posted. */
        val newAt: Long = 0,
        /** Configs and files in the posts that were new at the last check. */
        val lastConfigs: Int = 0,
        val lastFiles: Int = 0,
        val totalConfigs: Int = 0,
        val totalFiles: Int = 0,
        /** "unreachable", "no-public-page", or null. */
        val error: String? = null,
    )

    /** By channel name in lower case. */
    fun channelStates(context: Context): Map<String, ChannelState> = runCatching {
        val o = JSONObject(File(context.filesDir, CHANNELS).readText())
        o.keys().asSequence().associateWith { k ->
            val c = o.getJSONObject(k)
            ChannelState(c.optLong("lastId"), c.optLong("checkedAt"), c.optLong("newAt"), c.optInt("lastConfigs"), c.optInt("lastFiles"),
                c.optInt("totalConfigs"), c.optInt("totalFiles"), c.optString("error").takeIf { it.isNotBlank() })
        }
    }.getOrDefault(emptyMap())

    private fun saveChannelStates(context: Context, m: Map<String, ChannelState>) {
        val o = JSONObject()
        m.forEach { (k, s) ->
            o.put(k, JSONObject().put("lastId", s.lastId).put("checkedAt", s.checkedAt).put("newAt", s.newAt)
                .put("lastConfigs", s.lastConfigs).put("lastFiles", s.lastFiles).put("totalConfigs", s.totalConfigs)
                .put("totalFiles", s.totalFiles).put("error", s.error ?: ""))
        }
        File(context.filesDir, CHANNELS).apply { parentFile?.mkdirs() }.writeText(o.toString())
    }

    // ---------------------------------------------------------------- the page of a channel

    /** One post of a channel's public page: its standard links, subscription links, and files (name, size). */
    internal data class Post(val id: Long, val link: String, val links: List<String>, val subs: List<String>, val files: List<Pair<String, String?>>)

    /** Every post on a channel's public page (`t.me/s/<channel>`). */
    internal fun posts(channel: String, html: String, accept: (String) -> Boolean = parsable): List<Post> {
        val out = ArrayList<Post>()
        for (b in html.split("data-post=\"").drop(1)) {
            val ref = b.substringBefore('"')
            val id = ref.substringAfterLast('/').toLongOrNull() ?: continue
            val body = b.substringAfter('"').let { s -> s.indexOf("tgme_widget_message_footer").let { if (it > 0) s.substring(0, it) else s } }
            val names = DOC.findAll(body).map { decodeEntities(it.groupValues[1]).trim() }.toList()
            val sizes = DOC_SIZE.findAll(body).map { it.groupValues[1].trim() }.toList()
            val files = names.mapIndexedNotNull { i, n -> if (isWantedFile(n)) n to sizes.getOrNull(i) else null }
            val text = visibleText(body)
            val hrefs = HREF.findAll(body).map { decodeEntities(it.groupValues[1]) }.toList()
            out += Post(id, "https://t.me/" + ref.ifBlank { channel }, extractLinks(text, accept), subLinks(text, hrefs), files)
        }
        return out
    }

    /** NPV Tunnel's own files, and text files that can hold configs. */
    internal fun isWantedFile(name: String): Boolean {
        val n = name.lowercase()
        return listOf(".npvt", ".npvs", ".txt", ".conf", ".json", ".yaml", ".yml", ".sub").any { n.endsWith(it) }
    }

    /** The text a reader sees: tags out (block ends become spaces, so two configs never run together), entities decoded. */
    internal fun visibleText(html: String): String = decodeEntities(html.replace(BLOCK_END, " ").replace(TAG, ""))

    internal fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        return ENTITY.replace(s) { m ->
            val v = m.groupValues[1]
            when (v) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> " "
                else -> runCatching {
                    val cp = if (v.startsWith("#x")) v.substring(2).toInt(16) else v.substring(1).toInt()
                    String(Character.toChars(cp))
                }.getOrDefault(m.value)
            }
        }
    }

    /**
     * Subscription links in a post: https only, to a public host (never this phone's own network),
     * and only ones that look like a list rather than any web page.
     */
    internal fun subLinks(text: String, hrefs: List<String>): List<String> =
        (HTTPS.findAll(text).map { it.value } + hrefs.asSequence())
            .map { it.trimEnd(')', ',', '.', ';', ']', '}') }
            .filter { u ->
                val l = u.lowercase()
                val host = l.removePrefix("https://").substringBefore('/').substringBefore(':')
                l.startsWith("https://") && host.contains('.') && !isIpLiteral(host) && host != "localhost" &&
                    !host.endsWith("t.me") && !host.endsWith("telegram.org") && !host.endsWith("telegram.me") &&
                    (l.contains("/sub") || l.contains("sub=") || l.contains("raw.githubusercontent.com") || l.contains("/raw/") ||
                        l.contains("gist.githubusercontent.com") || l.endsWith(".txt"))
            }
            .distinct().toList()

    // ---------------------------------------------------------------- refresh («بررسی کانفیگ‌های تازه»)

    enum class Step { REACH, SCAN, DOWNLOAD, TEST }

    /** A question the refresh is waiting on. */
    enum class Ask { NONE, TUNNEL, TEST }

    /** How Telegram was reached: straight (or through another app's VPN), or through this app's running tunnel. */
    enum class Route { DIRECT, OTHER_VPN, APP_TUNNEL }

    enum class Outcome {
        /** Telegram could not be reached; nothing was changed. */
        NO_TELEGRAM,
        /** Downloaded, tested, and the broken ones removed. */
        TESTED,
        /** Downloaded into «انبار», untested, as the user chose. */
        STORED,
        NOTHING_NEW,
        STOPPED,
        FAILED,
    }

    data class Refresh(
        val running: Boolean = false,
        val step: Step = Step.REACH,
        val ask: Ask = Ask.NONE,
        val route: Route? = null,
        /** A tunnel of the app is up but offers no proxy this app can use. */
        val tunnelNoDoor: Boolean = false,
        val channelsDone: Int = 0,
        val channelsTotal: Int = 0,
        /** Channels with posts the app had not read. */
        val channelsNew: Int = 0,
        val channelsFailed: Int = 0,
        /** Configs and files in those new posts. */
        val foundConfigs: Int = 0,
        val foundFiles: Int = 0,
        val fetchDone: Int = 0,
        val fetchTotal: Int = 0,
        /** New to the library: configs into «انبار», files into the channel files' list. */
        val added: Int = 0,
        val addedFiles: Int = 0,
        val testDone: Int = 0,
        val testTotal: Int = 0,
        val working: Int = 0,
        val removed: Int = 0,
        val outcome: Outcome? = null,
        val note: String? = null,
        val finishedAt: Long = 0,
    )

    private val _refresh = MutableStateFlow(Refresh())
    val refresh: StateFlow<Refresh> = _refresh.asStateFlow()

    private var refreshJob: Job? = null
    @Volatile private var pending: CompletableDeferred<Boolean>? = null

    fun startRefresh(context: Context) {
        if (_refresh.value.running) return
        val app = context.applicationContext
        _refresh.value = Refresh(running = true, step = Step.REACH)
        refreshJob = scope.launch {
            try {
                runRefresh(app)
            } catch (e: kotlinx.coroutines.CancellationException) {
                end(Outcome.STOPPED)
                throw e
            } catch (e: Exception) {
                end(Outcome.FAILED, e.message)
            }
        }
    }

    /** The answer to the question the refresh is waiting on. */
    fun answer(yes: Boolean) { pending?.complete(yes) }

    fun cancelRefresh() { refreshJob?.cancel() }

    private fun end(o: Outcome, note: String? = null) =
        _refresh.update { it.copy(running = false, ask = Ask.NONE, outcome = o, note = note, finishedAt = System.currentTimeMillis()) }

    private suspend fun ask(a: Ask): Boolean {
        val d = CompletableDeferred<Boolean>()
        pending = d
        _refresh.update { it.copy(ask = a) }
        return try { d.await() } finally { pending = null; _refresh.update { it.copy(ask = Ask.NONE) } }
    }

    /** Another app's VPN carries this app's traffic: a measurement now would be about that VPN. */
    fun otherVpnUp(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)

    /**
     * A GET, its body as text, or null. Through [via] when given. A body over [maxBytes] is null,
     * or with [truncate] its first [maxBytes] up to the last whole line -- a list is read in part
     * rather than downloaded whole for the few hundred configs a refresh takes from it.
     */
    private fun get(url: String, via: Proxy?, connectSec: Long = 10, readSec: Long = 15, maxBytes: Long = 3_000_000, truncate: Boolean = false): String? = runCatching {
        val c = http.newBuilder().proxy(via).connectTimeout(connectSec, TimeUnit.SECONDS).readTimeout(readSec, TimeUnit.SECONDS)
            .callTimeout(connectSec + readSec + 5, TimeUnit.SECONDS).build()
        c.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val src = r.body?.source() ?: return@use null
            when {
                !src.request(maxBytes) -> src.buffer.readUtf8()
                !truncate -> null
                else -> src.buffer.readUtf8(maxBytes).let { s -> s.substring(0, s.lastIndexOf('\n').takeIf { it > 0 } ?: s.length) }
            }
        }
    }.getOrNull()

    private fun reach(context: Context): Route? {
        if (get(PROBE, null, 8, 12)?.contains("tgme_channel_info") == true) return if (otherVpnUp(context)) Route.OTHER_VPN else Route.DIRECT
        val p = UpdateNet.tunnelProxy(context)
        if (p == null) {
            if (VpnSwitch.isUp(context)) _refresh.update { it.copy(tunnelNoDoor = true) }
            return null
        }
        return if (get(PROBE, p, 10, 15)?.contains("tgme_channel_info") == true) Route.APP_TUNNEL else null
    }

    private data class Scan(val ok: Boolean, val posts: List<Post>, val more: Boolean, val firstTime: Boolean, val error: String?)

    /** The posts after [lastId] (on a first look, the latest page). */
    private fun scanChannel(ch: String, lastId: Long, via: Proxy?): Scan {
        val html = get(if (lastId > 0) "https://t.me/s/$ch?after=$lastId" else "https://t.me/s/$ch", via)
            ?: return Scan(false, emptyList(), false, lastId == 0L, "unreachable")
        if (!html.contains("tgme_channel_info")) return Scan(false, emptyList(), false, lastId == 0L, "no-public-page")
        val all = posts(ch, html)
        return Scan(true, all.filter { it.id > lastId }, more = lastId > 0 && all.size >= 20, firstTime = lastId == 0L, error = null)
    }

    /** The rest of the new posts: forward while a page comes back full, or one page further back on a first look. */
    private fun morePosts(ch: String, s: Scan, via: Proxy?): List<Post> {
        val out = ArrayList<Post>()
        if (s.firstTime) {
            val min = s.posts.minOfOrNull { it.id } ?: return out
            get("https://t.me/s/$ch?before=$min", via)?.let { html -> out += posts(ch, html).filter { it.id < min } }
            return out
        }
        var after = s.posts.maxOfOrNull { it.id } ?: return out
        repeat(4) {
            val html = get("https://t.me/s/$ch?after=$after", via) ?: return out
            val page = posts(ch, html).filter { it.id > after }
            if (page.isEmpty()) return out
            out += page
            after = page.maxOf { it.id }
            if (page.size < 20) return out
        }
        return out
    }

    /** Up to [pages] pages of a channel's posts before [start], newest first. */
    private fun history(ch: String, start: Long, pages: Int, via: Proxy?): List<Post> {
        val out = ArrayList<Post>()
        var before = start
        repeat(pages) {
            val html = get("https://t.me/s/$ch?before=$before", via) ?: return out
            val page = posts(ch, html).filter { it.id < before }
            if (page.isEmpty()) return out
            out += page
            before = page.minOf { it.id }
        }
        return out
    }

    /** Plain or base64: the links in a list's body. */
    private fun linksInBody(body: String): List<String> {
        val text = if (body.contains("://")) body else runCatching {
            String(android.util.Base64.decode(body.trim(), android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault("")
        return extractLinks(text)
    }

    private suspend fun runRefresh(app: Context) {
        // ---- 1. Telegram, directly or through the app's own tunnel
        val route = reach(app)
        if (route == null) { end(Outcome.NO_TELEGRAM); return }
        val via = if (route == Route.APP_TUNNEL) UpdateNet.tunnelProxy(app) else null
        val channels = (userChannels(app) + defaultChannels(app)).distinctBy { it.lowercase() }
        _refresh.update { it.copy(route = route, step = Step.SCAN, channelsTotal = channels.size) }

        // ---- 2. which channels posted something new
        val states = channelStates(app).toMutableMap()
        val scans = ConcurrentHashMap<String, Scan>()
        coroutineScope {
            val gate = Semaphore(6)
            channels.map { ch ->
                async {
                    gate.withPermit {
                        val s = scanChannel(ch, states[ch.lowercase()]?.lastId ?: 0L, via)
                        scans[ch] = s
                        _refresh.update { r ->
                            r.copy(
                                channelsDone = r.channelsDone + 1,
                                channelsFailed = r.channelsFailed + if (s.ok) 0 else 1,
                                channelsNew = r.channelsNew + if (s.posts.isNotEmpty()) 1 else 0,
                                foundConfigs = r.foundConfigs + s.posts.sumOf { it.links.size },
                                foundFiles = r.foundFiles + s.posts.sumOf { it.files.size },
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        // ---- 3. the download: the rest of the new posts, their subscription links, the public lists
        val deeper = scans.filter { (_, s) -> s.ok && s.posts.isNotEmpty() && (s.more || s.firstTime) }
        _refresh.update { it.copy(step = Step.DOWNLOAD, fetchDone = 0, fetchTotal = deeper.size + EXTRA_LISTS.size + 1) }
        coroutineScope {
            val gate = Semaphore(6)
            deeper.map { (ch, s) ->
                async {
                    gate.withPermit {
                        val extra = morePosts(ch, s, via)
                        scans[ch] = s.copy(posts = s.posts + extra)
                        _refresh.update { r ->
                            r.copy(fetchDone = r.fetchDone + 1, foundConfigs = r.foundConfigs + extra.sumOf { it.links.size },
                                foundFiles = r.foundFiles + extra.sumOf { it.files.size })
                        }
                    }
                }
            }.awaitAll()
        }
        if (harvest(app)) {
            // The owner's harvest reads much further back in every channel: on the phone the
            // channels' configs were the ones that worked (2026-10-19: 41 of 49 working came from
            // them, 8 from about 8,800 list entries).
            val back = scans.filter { (_, s) -> s.ok }
            _refresh.update { it.copy(fetchTotal = it.fetchTotal + back.size) }
            coroutineScope {
                val gate = Semaphore(6)
                back.map { (ch, s) ->
                    async {
                        gate.withPermit {
                            val start = s.posts.minOfOrNull { it.id } ?: ((states[ch.lowercase()]?.lastId ?: 0L) + 1)
                            val old = if (start > 1) history(ch, start, HARVEST_PAGES, via) else emptyList()
                            scans[ch] = s.copy(posts = s.posts + old)
                            _refresh.update { r ->
                                r.copy(fetchDone = r.fetchDone + 1, foundConfigs = r.foundConfigs + old.sumOf { it.links.size },
                                    foundFiles = r.foundFiles + old.sumOf { it.files.size })
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        val fromChannels = LinkedHashMap<String, String>()
        scans.forEach { (ch, s) -> s.posts.sortedByDescending { it.id }.forEach { p -> p.links.forEach { fromChannels.putIfAbsent(it, "tg:$ch") } } }
        val subs = scans.values.flatMap { s -> s.posts.flatMap { it.subs } }.distinct().take(SUB_CAP)
        _refresh.update { it.copy(fetchTotal = it.fetchTotal + subs.size + 1) }
        val big = harvest(app)
        val listBytes = if (big) 20_000_000L else 2_000_000L
        // The aggregator's pre-tested pools come first when the lists are cut to a cap: their
        // configs passed a real request minutes ago (elsewhere), so far more of them work here.
        val preferred = ConcurrentHashMap<String, String>()
        val fromLists = ConcurrentHashMap<String, String>()
        coroutineScope {
            val gate = Semaphore(4)
            val jobs = subs.map { u ->
                async {
                    gate.withPermit {
                        val body = get(u, null, 8, 15, 1_000_000) ?: via?.let { get(u, it, 10, 15, 1_000_000) }
                        val found = body?.let(::linksInBody).orEmpty()
                        synchronized(fromChannels) { found.forEach { l -> fromChannels.putIfAbsent(l, "sub:" + u.removePrefix("https://").substringBefore('/')) } }
                        _refresh.update { it.copy(fetchDone = it.fetchDone + 1) }
                    }
                }
            } + EXTRA_LISTS.map { url ->
                async {
                    gate.withPermit {
                        val body = get(url, null, 8, 20, listBytes, truncate = true) ?: via?.let { get(url, it, 10, 20, listBytes, truncate = true) }
                        body?.let { linksInBody(it).take(if (big) 20_000 else 1500).forEach { l -> fromLists.putIfAbsent(l, "gh:" + url.substringAfter("githubusercontent.com/").substringBefore('/')) } }
                        _refresh.update { it.copy(fetchDone = it.fetchDone + 1) }
                    }
                }
            } + async {
                runCatching { FreeConfigEngine.fetchCandidates() }.getOrDefault(emptyList()).forEach { fromLists.putIfAbsent(it, "lists") }
                _refresh.update { it.copy(fetchDone = it.fetchDone + 1) }
            } + async {
                runCatching {
                    FreeConfigEngine.fetchCatalog().pools.filter { it.id == "verified" || it.id == "fast" }.forEach { pool ->
                        FreeConfigEngine.fetchCandidates(pool).forEach { preferred.putIfAbsent(it, "gh:0xRadikal") }
                    }
                }
                _refresh.update { it.copy(fetchDone = it.fetchDone + 1) }
            }
            jobs.awaitAll()
        }

        // Into «انبار»: the channels' own configs all, the lists' up to a cap; nothing the library has
        // or recently threw away; the channel files as posts to open in Telegram.
        val now = System.currentTimeMillis()
        var added = 0
        var addedFiles = 0
        val dead = deadKeys(app).keys
        val listCap = if (big) 8000 else LIST_CAP
        edit(app) { all ->
            val have = HashSet<String>(dead)
            all.forEach { e -> if (e.kind == Kind.LINK && e.uri != null) have += keyOf(e.uri) }
            for ((u, src) in fromChannels) if (have.add(keyOf(u))) {
                all += Entry("f" + sha(u.toByteArray()), nameOf(u), Kind.LINK, u, src, now, Shelf.STORE); added++
            }
            var listed = 0
            for ((u, src) in preferred.entries.shuffled() + fromLists.entries.shuffled()) {
                if (listed >= listCap) break
                // The free-config engine's lists come unparsed: a link the engine cannot run would
                // sit in «انبار» never measured (seen on the phone: vmess with spaces in its JSON).
                if (!parsable(u)) continue
                if (have.add(keyOf(u))) { all += Entry("f" + sha(u.toByteArray()), nameOf(u), Kind.LINK, u, src, now, Shelf.STORE); added++; listed++ }
            }
            val files = all.filter { it.kind == Kind.REMOTE }.map { it.id }.toHashSet()
            scans.forEach { (ch, s) ->
                s.posts.forEach { p ->
                    p.files.forEach { (name, size) ->
                        val id = "r" + sha("${p.link}|$name".toByteArray())
                        if (files.add(id)) {
                            all += Entry(id, name + (size?.let { " · $it" } ?: ""), Kind.REMOTE, p.link, "tg:$ch", now + p.id % 1000, Shelf.STORE)
                            addedFiles++
                        }
                    }
                }
            }
            trimShelves(all, if (big) 20_000 else MAX_STORE)
            added > 0 || addedFiles > 0
        }
        scans.forEach { (ch, s) ->
            val k = ch.lowercase()
            val old = states[k] ?: ChannelState()
            states[k] = if (!s.ok) old.copy(checkedAt = now, error = s.error) else {
                val nl = s.posts.sumOf { it.links.size }
                val nf = s.posts.sumOf { it.files.size }
                old.copy(lastId = maxOf(old.lastId, s.posts.maxOfOrNull { it.id } ?: 0L), checkedAt = now,
                    newAt = if (s.posts.isNotEmpty()) now else old.newAt, lastConfigs = nl, lastFiles = nf,
                    totalConfigs = old.totalConfigs + nl, totalFiles = old.totalFiles + nf, error = null)
            }
        }
        saveChannelStates(app, states)
        _refresh.update { it.copy(added = added, addedFiles = addedFiles, fetchDone = it.fetchTotal) }

        // ---- 4. the app's tunnel was there only for Telegram
        if (VpnSwitch.isUp(app) && ask(Ask.TUNNEL)) {
            VpnSwitch.stopAll(app)
            withTimeoutOrNull(8_000) { while (VpnSwitch.isUp(app)) delay(200) }
        }

        // ---- 5. test everything in «انبار» and drop the broken ones, if the user wants it now
        val store = linksOn(app, Shelf.STORE).filter(NpvLibrary::isRunnable)
        if (store.isEmpty()) { end(if (added + addedFiles == 0) Outcome.NOTHING_NEW else Outcome.STORED); return }
        if (!ask(Ask.TEST)) { end(Outcome.STORED); return }
        while (_measure.value.running) delay(500)
        _refresh.update { it.copy(step = Step.TEST, testDone = 0, testTotal = store.size, working = 0) }
        measureEntries(app, store) { done, ok -> _refresh.update { it.copy(testDone = done, working = ok) } }
        val removed = removeBroken(app, Shelf.STORE)
        _refresh.update { it.copy(removed = removed) }
        end(Outcome.TESTED)
    }

    /** The newest [MAX_FILES] channel files, and at most [MAX_STORE] configs in «انبار» (the oldest untested go first). */
    private fun trimShelves(all: MutableList<Entry>, maxStore: Int) {
        // A free link nothing can run never gets measured, so it would never leave either.
        all.removeAll { it.kind == Kind.LINK && it.source != "mine" && !NpvLibrary.isRunnable(it) }
        val files = all.filter { it.kind == Kind.REMOTE }
        if (files.size > MAX_FILES) {
            val drop = files.sortedByDescending { it.addedAt }.drop(MAX_FILES).map { it.id }.toHashSet()
            all.removeAll { it.id in drop }
        }
        val store = all.filter { it.kind == Kind.LINK && it.shelf == Shelf.STORE }
        if (store.size > maxStore) {
            val drop = store.filter { it.ping == 0L }.sortedBy { it.addedAt }.take(store.size - maxStore).map { it.id }.toHashSet()
            all.removeAll { it.id in drop }
        }
    }

    // ---------------------------------------------------------------- the shipped list

    /**
     * The configs the app ships (assets/npv/seed.txt, fastest first as measured): ones that really
     * carried data on the owner's phone, so the section is never empty on a first run without
     * Telegram. On a first run the first [MAIN_SEED] go to the main page and the rest to «انبار»; a
     * newer list in an update adds only what this phone has not had before, so a config the user
     * deleted does not come back.
     */
    fun ensureSeed(context: Context) {
        val lines = runCatching { context.assets.open("npv/seed.txt").bufferedReader().readLines() }.getOrDefault(emptyList())
        val version = lines.firstOrNull { it.startsWith("# version") }?.substringAfter("version")?.trim(':', ' ', '\t') ?: "0"
        // Not the old "npv/seeded": that one only ever recorded an empty list.
        val flag = File(context.filesDir, "npv/seed-version")
        if (flag.isFile && flag.readText().trim() == version) return
        val links = lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.substringBefore('\t').trim() }
            .filter { VpnConfig.parseUri(it)?.address?.isNotBlank() == true }
        val seenFile = File(context.filesDir, "npv/seed-seen.txt")
        // First = no shipped config has come in yet (an empty list in an earlier build does not count).
        val first = !seenFile.isFile
        val seen = runCatching { seenFile.readLines().toHashSet() }.getOrDefault(HashSet())
        val fresh = ArrayList<String>()
        edit(context) { all ->
            val have = all.mapNotNull { e -> e.uri?.takeIf { e.kind == Kind.LINK }?.let(::keyOf) }.toHashSet()
            var onMain = all.count { it.kind == Kind.LINK && it.shelf == Shelf.MAIN }
            val now = System.currentTimeMillis()
            links.forEachIndexed { i, uri ->
                val k = keyOf(uri)
                if (k in seen || !have.add(k)) return@forEachIndexed
                val main = first && onMain < MAIN_SEED
                if (main) onMain++
                all += Entry("f" + sha(uri.toByteArray()), nameOf(uri), Kind.LINK, uri, "free:seed", now - i, if (main) Shelf.MAIN else Shelf.STORE)
                fresh += k
            }
            fresh.isNotEmpty()
        }
        seenFile.parentFile?.mkdirs()
        if (fresh.isNotEmpty()) seenFile.appendText(fresh.joinToString("\n", postfix = "\n"))
        flag.writeText(version)
    }
}
