package com.mlmvpn.scanner.engines.gateway

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.engines.cloud.CfTunnel
import com.mlmvpn.scanner.engines.cloud.CfWorkers
import com.mlmvpn.scanner.engines.cloud.PanelRegistry
import com.mlmvpn.scanner.engines.cloud.WorkerRoute
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.utils.AntiDpi
import com.mlmvpn.scanner.utils.AppLocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * «ورکر مرکزی»: ONE relay Worker on the user's own Cloudflare account for every panel, shared with the
 * Windows app. This is the Android shell around [GatewayEngine] (the protocol, tested on the JVM):
 * the real account behind [GatewayCloud], this device's copy in SharedPreferences, the Worker's code
 * from assets, and DNS-over-HTTPS for the Worker's own addresses.
 *
 * BPB is the Worker's own row; Edge, Zeus, Spider, Netra, Gozargah, Nova, Nahan and MLM are one more id
 * each on the same Worker — connecting one installs no panel code and no second Worker. The id and the
 * tunnel path live in the account's registry (key `GW`, docs/PANEL-REGISTRY.md), which is how the
 * Windows app and this one learn them from each other.
 *
 * Every function blocks or suspends on IO; none throws a raw exception for a user-facing failure —
 * [GatewayException] carries the Persian and English text and [localized] picks one.
 */
object EdgeGateway {

    private const val TAG = "EdgeGateway"
    private const val PREFS = "edge_gateway"
    const val ASSET_DIR = "edge_gateway"

    private val http = OkHttpClient.Builder().dns(WorkerRoute.dns()).protocols(WorkerRoute.HTTP1).socketFactory(WorkerRoute.cfSocketFactory()).proxySelector(com.mlmvpn.scanner.engines.cloud.CfTunnel.selector())
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    /** Plain HTTPS to IP-literal DoH endpoints: they need no DNS of their own, which is the point where names are poisoned. */
    private val dohHttp = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
    private val DOH_ENDPOINTS = listOf(
        "https://1.1.1.1/dns-query", "https://cloudflare-dns.com/dns-query", "https://8.8.8.8/resolve", "https://dns.google/resolve",
    )

    // ── this device's copy ───────────────────────────────────────────────────────────────

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private class PrefsStore(private val context: Context, private val accountId: String) : GatewayStore {
        override fun get(): GatewayRecord? = runCatching {
            GatewayRecord.fromJson(JSONObject(prefs(context).getString("r_$accountId", null) ?: return null))
        }.getOrNull()

        override fun put(rec: GatewayRecord) { prefs(context).edit().putString("r_$accountId", rec.toJson().toString()).apply() }
        override fun del() { prefs(context).edit().remove("r_$accountId").apply() }
    }

    // ── the Worker's code ────────────────────────────────────────────────────────────────

    @Volatile private var cachedModules: List<Pair<String, String>>? = null

    /** The Worker's ES modules, from assets, with LF line ends; sorted by name. */
    fun modules(context: Context): List<Pair<String, String>> = cachedModules ?: synchronized(this) {
        cachedModules ?: run {
            val assets = context.applicationContext.assets
            val names = assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(".js") }.sorted()
            check(Gateway.MAIN_MODULE in names) { "the gateway Worker's code is missing from the app" }
            names.map { n -> n to assets.open("$ASSET_DIR/$n").use { it.readBytes().toString(Charsets.UTF_8) }.replace("\r\n", "\n") }
        }.also { cachedModules = it }
    }

    // ── the real account ─────────────────────────────────────────────────────────────────

    private class AccountCloud(private val account: CloudAccount) : GatewayCloud {
        private val rnd = SecureRandom()
        override val by = "android"
        override fun registryGet(): SharedRaw? = PanelRegistry.get(account, Gateway.CODE)?.let { SharedRaw(it.script, it.url, it.s, it.by, it.at) }
        override fun registryPut(script: String, url: String, s: JSONObject) = PanelRegistry.put(account, Gateway.CODE, script, url, null, null, s)
        override fun registryRemove(script: String) = PanelRegistry.removeIf(account, Gateway.CODE, script)
        override fun scriptExists(script: String) = PanelRegistry.scriptExists(account, script)
        override fun subdomain(): String = CfWorkers.ensureSubdomain(account)
        override fun workerName(): String = AntiDpi.generateSafeWorkerName() + Gateway.SCRIPT_SUFFIX
        override fun upload(script: String, files: List<Pair<String, String>>, bindings: List<JSONObject>) =
            CfWorkers.uploadModules(account, script, Gateway.MAIN_MODULE, files, bindings, Gateway.COMPAT_DATE)
        override fun enableWorkersDev(script: String) = CfWorkers.enableWorkersDev(account, script)
        override fun removeWorker(script: String) = CfWorkers.remove(account, script)
        override fun randomUuid(): String = UUID.randomUUID().toString()
        override fun randomPath(): String = "/" + ByteArray(8).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }

    private fun engine(context: Context, account: CloudAccount): GatewayEngine {
        GatewayEngine.language = { AppLocaleManager.isFarsi() }
        return GatewayEngine(account.accountId, AccountCloud(account), PrefsStore(context, account.accountId), { modules(context) }, ::resolve,
            // «کانفیگ استدیو»'s people ride every upload, whichever screen made it (data/studio/central).
            studioIds = { script -> com.mlmvpn.scanner.data.studio.central.CentralUploadIds.forUpload(context, account, script, status(context, account).studioMark) })
    }

    // ── the Worker's own addresses (DNS over HTTPS) ──────────────────────────────────────

    /** One DoH question across every endpoint at once; the addresses of the right family, or empty. */
    private fun ask(host: String, type: String): List<String> {
        val code = if (type == "AAAA") 28 else 1
        val pool = Executors.newFixedThreadPool(DOH_ENDPOINTS.size)
        try {
            val tasks = DOH_ENDPOINTS.map { base ->
                Callable {
                    val req = Request.Builder().url("$base?name=${java.net.URLEncoder.encode(host, "UTF-8")}&type=$type")
                        .header("accept", "application/dns-json").get().build()
                    dohHttp.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        val answers = JSONObject(r.body?.string().orEmpty()).optJSONArray("Answer") ?: error("empty")
                        val ips = (0 until answers.length()).map { answers.getJSONObject(it) }
                            .filter { it.optInt("type") == code }.map { it.optString("data") }
                            .filter { if (code == 1) Gateway.isIPv4(it) else Gateway.isIPv6(it) }.distinct().take(4)
                        if (ips.isEmpty()) error("empty")
                        ips
                    }
                }
            }
            return runCatching { pool.invokeAny(tasks, 8, TimeUnit.SECONDS) }.getOrDefault(emptyList())
        } finally {
            pool.shutdownNow()
        }
    }

    private fun resolve(host: String): DohAnswer {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit(Callable { ask(host, "A") })
            val aaaa = pool.submit(Callable { ask(host, "AAAA") })
            return DohAnswer(runCatching { a.get() }.getOrDefault(emptyList()), runCatching { aaaa.get() }.getOrDefault(emptyList()))
        } finally {
            pool.shutdownNow()
        }
    }

    // ── what the screens call ────────────────────────────────────────────────────────────

    private val _version = MutableStateFlow(0L)

    /** Bumped whenever this device's copy of an account's record may have changed, so an open screen reads it again. */
    val version: StateFlow<Long> = _version

    /** This device's copy, without any request: fast enough to ask on every recomposition. */
    fun status(context: Context, account: CloudAccount): GatewayStatus = engine(context, account).status()

    /**
     * Run [block] on IO. A request that never reached Cloudflare (no such host, timeout, reset, a bad
     * certificate) — direct AND through the tunnel if one is on, see [CfTunnel] — becomes one sentence that
     * says what to do: turn on one of the app's tunnels. Anything Cloudflare answered keeps its own message.
     */
    private suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
        val r = runCatching(block)
        _version.value = _version.value + 1
        val e = r.exceptionOrNull() ?: return@withContext r
        if (e is GatewayException) return@withContext r
        Log.w(TAG, "gateway call failed", e)
        Result.failure(explain(e))
    }

    private fun explain(e: Throwable): Throwable {
        if (generateSequence(e) { it.cause }.none { it is java.io.IOException }) return e
        val (fa, en) = CfTunnel.advice(CfTunnel.available())
        return GatewayException(fa, en, e)
    }

    /** [sync] for code that is already on IO (PanelSync); true when this device's copy moved. */
    fun syncBlocking(context: Context, account: CloudAccount): Boolean = runCatching { engine(context, account).sync().first.changed }
        .onFailure { Log.w(TAG, "sync", it) }.getOrDefault(false).also { if (it) _version.value = _version.value + 1 }

    /** The account's record ↔ this device (the Cloud tab calls it once per session per account). */
    suspend fun sync(context: Context, account: CloudAccount, onStep: (String) -> Unit = {}): Result<Pair<Pulled, GatewayStatus>> =
        io { engine(context, account).sync(onStep) }

    suspend fun deploy(context: Context, account: CloudAccount, rotate: Boolean = false, onStep: (String) -> Unit = {}): Result<GatewayStatus> =
        io { engine(context, account).deploy(rotate = rotate, onStep = onStep) }

    suspend fun connectPanel(context: Context, account: CloudAccount, code: String, onStep: (String) -> Unit = {}): Result<GatewayStatus> =
        io { engine(context, account).connectPanel(code, onStep) }

    suspend fun disconnectPanel(context: Context, account: CloudAccount, code: String, onStep: (String) -> Unit = {}): Result<GatewayStatus> =
        io { engine(context, account).disconnectPanel(code, onStep) }

    suspend fun saveSettings(context: Context, account: CloudAccount, patch: SettingsPatch, fallback: String?, onStep: (String) -> Unit = {}): Result<GatewayStatus> =
        io { engine(context, account).saveSettings(patch, fallback, onStep) }

    suspend fun configs(context: Context, account: CloudAccount, panel: String = "BPB", refresh: Boolean = false): Result<ConfigsResult> =
        io { engine(context, account).configs(panel, refresh = refresh) }

    suspend fun remove(context: Context, account: CloudAccount): Result<Boolean> = io { engine(context, account).remove() }

    /**
     * The central Worker as an exit for code that dials it itself (MAE's Worker route): its address,
     * BPB's own id, the path, and the Proxy IP that lets it reach Cloudflare-hosted sites.
     */
    data class Exit(val accountId: String, val host: String, val uuid: String, val wsPath: String, val fallback: String)

    /**
     * The central Worker this device knows of -- the same one on every call: of several accounts, the
     * one whose id sorts first. Read from this device's copy, no request; null when none is installed.
     */
    fun anyExit(context: Context): Exit? = runCatching {
        prefs(context).all.entries.asSequence()
            .filter { it.key.startsWith("r_") }
            .mapNotNull { e -> runCatching { GatewayRecord.fromJson(JSONObject(e.value as String)) }.getOrNull() }
            .filter { Gateway.UUID_RE.matches(it.uuid) && it.host.isNotBlank() }
            .sortedBy { it.accountId }
            .firstOrNull()
            ?.let { Exit(it.accountId, it.host, it.uuid, it.wsPath, it.fallback) }
    }.getOrNull()

    /** The plain `:443` link of the Worker's own name, for code that needs a real Cloudflare config to send a request through. */
    fun probeLink(context: Context, account: CloudAccount): String? = runCatching {
        val rec = PrefsStore(context, account.accountId).get() ?: return null
        GatewayLinks.buildConfigs(rec.host, rec.uuid, rec.wsPath, listOf(443), null, "BPB").firstOrNull()
    }.getOrNull()

    /** The links of a connected panel (BPB always), or an empty list; for the arena and the Doctor. */
    suspend fun linksOf(context: Context, account: CloudAccount, panel: String): List<String> =
        configs(context, account, panel).getOrNull()?.configs.orEmpty()

    // ── «کانفیگ استدیو» on this Worker (data/studio/central) ─────────────────────────────

    /** The account's record taken in, then what the Studio needs: installed, room, the Studio digest. Blocking. */
    fun studioStatusBlocking(context: Context, account: CloudAccount): GatewayStatus {
        val e = engine(context, account)
        runCatching { e.pull(force = true) }.onFailure { Log.w(TAG, "studio pull", it) }
        _version.value = _version.value + 1
        return e.status()
    }

    /**
     * Upload the Worker carrying exactly [ids] as the Studio's people — the same upload every other
     * change makes (pull, publish, upload), installing the Worker when the account has none. Blocking.
     */
    fun studioApplyBlocking(context: Context, account: CloudAccount, ids: List<String>, onStep: (String) -> Unit = {}): GatewayStatus =
        try {
            engine(context, account).deploy(studio = ids, onStep = onStep)
        } finally {
            _version.value = _version.value + 1
        }

    /** One Studio person's links: the panels' set, with their id and their name. */
    suspend fun studioLinks(context: Context, account: CloudAccount, id: String, label: String, refresh: Boolean = false): Result<ConfigsResult> =
        io { engine(context, account).linksFor(id, label, refresh) }

    /** [studioLinks], blocking (callers are on IO): what a person's subscription is written from. */
    fun studioLinksBlocking(context: Context, account: CloudAccount, id: String, label: String): List<String> =
        engine(context, account).linksFor(id, label, false).configs

    /**
     * The Worker's own plain link (its own id, the one BPB's configs carry): the way the Studio
     * measures a public proxy THROUGH this account's central Worker -- the same path a person's
     * location will take, which is what decides whether that proxy answers it.
     */
    fun ownLinkBlocking(context: Context, account: CloudAccount): String? =
        runCatching { engine(context, account).configs("BPB").configs.firstOrNull() }.getOrNull()

    /** The text of a failure the Studio shows: a gateway sentence as it is, a network failure as advice. */
    fun explainForStudio(e: Throwable): String = localized(explain(e))

    /** A GET on the Worker's own host: does it answer, and is it the gateway? */
    data class Check(val reachable: Boolean, val gateway: Boolean, val status: Int, val ms: Long)

    suspend fun check(context: Context, account: CloudAccount): Result<Check> = io {
        val rec = PrefsStore(context, account.accountId).get() ?: throw GatewayException("درگاه روی این حساب نصب نیست.", "The gateway is not installed on this account.")
        val t0 = System.currentTimeMillis()
        runCatching {
            http.newCall(Request.Builder().url(rec.url + "/").get().build()).execute().use { r ->
                val body = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull()
                Check(r.isSuccessful, body != null && body.optString("status") == "ok" && body.optString("service").isNotBlank(), r.code, System.currentTimeMillis() - t0)
            }
        }.getOrElse { Check(false, false, 0, System.currentTimeMillis() - t0) }
    }

    /** The user-facing text of a failure, in the language the app shows. */
    fun localized(e: Throwable): String = when (e) {
        is GatewayException -> if (AppLocaleManager.isFarsi()) e.fa else e.en
        else -> e.message ?: e.javaClass.simpleName
    }
}
