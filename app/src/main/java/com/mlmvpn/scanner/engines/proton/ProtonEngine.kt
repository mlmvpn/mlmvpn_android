package com.mlmvpn.scanner.engines.proton

import android.content.Context
import com.mlmvpn.scanner.engines.amnezia.AmzConnect
import com.mlmvpn.scanner.engines.amnezia.AmzEngine
import com.mlmvpn.scanner.engines.amnezia.AmzServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * «Proton VPN»: the user's own Proton account's free WireGuard servers.
 *
 * Proton lets every account, free ones too, download a WireGuard `.conf` for a server from its
 * own dashboard. The user signs in (or makes the account) on Proton's real page inside the app --
 * the password, the CAPTCHA, the emailed code and the 2FA all stay on that page and never reach
 * the app -- and the config that page produces is picked up here and kept.
 *
 * There is no tunnel of its own: a config is a WireGuard server like any other, so it is stored in
 * and connected by the «آمنزیا» engine ([AmzEngine]), which already knows how to hide the handshake,
 * prove a connection with a real request and learn the exit country. What this object adds is the
 * fence: it remembers which servers are Proton's, and connects among those alone.
 */
object ProtonEngine {

    private const val PREFS = "proton_vpn"
    private const val KEY_IDS = "ids"
    /** The account's WireGuard key (X25519, base64) and when its registration expires (seconds). */
    private const val KEY_WG = "wg_key"
    private const val KEY_WG_EXP = "wg_key_exp"
    /** Each server's IPv6 address, as "ipv4|ipv6": the configs keep IPv4, Stealth tries IPv6 first. */
    private const val KEY_V6 = "v6"
    private const val PING_WAIT_MS = 25_000L

    private lateinit var app: Context
    @Volatile private var initialized = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _switching = MutableStateFlow(false)
    /** True while the helper engine is being turned off and Proton connected in its place. */
    val switching: StateFlow<Boolean> = _switching

    private val _pinging = MutableStateFlow(false)
    /** A ping of Proton's servers that this page started is running (the progress itself is shared with «آمنزیا»). */
    val pinging: StateFlow<Boolean> = _pinging

    private fun watchPing(job: kotlinx.coroutines.Job?) {
        if (job == null) return
        _pinging.value = true
        job.invokeOnCompletion { _pinging.value = false }
    }

    private val _tried = MutableStateFlow(false)
    /**
     * A Proton connect was asked for and not ended since. A connect that fails before it picks a
     * server (none of them answered) names no server, so this is how the screen knows it was Proton's.
     */
    val tried: StateFlow<Boolean> = _tried

    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    /** The ids of the Amnezia-store servers that are Proton's. */
    val ids: StateFlow<Set<String>> = _ids

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        AmzEngine.init(app)
        _ids.value = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_IDS, emptySet()).orEmpty().toSet()
        initialized = true
    }

    fun isInitialized() = initialized

    /** Proton's server ids, also for callers that may come before [init] (the Stealth relay). */
    fun storedIds(context: Context): Set<String> =
        if (initialized) _ids.value
        else context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_IDS, emptySet()).orEmpty()

    @Volatile private var v6Cache: Map<String, String>? = null

    /** The IPv6 address of the server whose IPv4 one is [host], when Proton gave one. */
    fun storedV6(context: Context, host: String): String? {
        val m = v6Cache ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_V6, emptySet()).orEmpty()
            .mapNotNull { e -> e.split('|').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap().also { v6Cache = it }
        return m[host]
    }

    private fun saveV6(map: Map<String, String>) {
        if (map.isEmpty()) return
        val merged = (v6Cache ?: emptyMap()) + map
        v6Cache = merged
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_V6, merged.map { "${it.key}|${it.value}" }.toSet()).apply()
    }

    private fun storedKey(): ProtonApi.AccountKey? {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = prefs.getString(KEY_WG, null) ?: return null
        return ProtonApi.AccountKey(k, prefs.getLong(KEY_WG_EXP, 0L))
    }

    private fun saveKey(key: ProtonApi.AccountKey?) {
        val e = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (key == null) e.remove(KEY_WG).remove(KEY_WG_EXP) else e.putString(KEY_WG, key.privateKey).putLong(KEY_WG_EXP, key.expiresAt)
        e.apply()
    }

    /** Proton's servers that are still in the store (the user may have deleted one from «آمنزیا»). */
    fun servers(all: List<AmzServer>, ids: Set<String>): List<AmzServer> = all.filter { it.id in ids }

    /**
     * A config the page produced. True when it is a usable WireGuard config and now kept;
     * otherwise [AmzEngine] has already posted the reason as a notice.
     */
    fun onConf(context: Context, text: String, fileName: String): Boolean {
        init(context)
        val id = AmzEngine.importConf(context, text, fileName.ifBlank { "Proton" }) ?: return false
        save(_ids.value + id)
        return true
    }

    /**
     * The free servers of the account the page is signed in to, taken by the app itself the way
     * Proton's dashboard makes them ([ProtonApi]); the ones already kept are not taken twice.
     * [proxy] is the way the page reached Proton, [version] the dashboard's own.
     */
    suspend fun takeFromAccount(context: Context, proxy: java.net.Proxy, version: String, userAgent: String, pageUid: String?): ProtonApi.Outcome {
        init(context)
        val have = servers(AmzEngine.store.state.value.servers, _ids.value).map { it.host }.toSet()
        val out = ProtonApi.takeFreeServers(proxy, version, userAgent, have, storedKey(), pageUid = pageUid)
        when (out) {
            is ProtonApi.Outcome.Got -> {
                saveV6(out.v6)
                val before = _ids.value
                if (out.key != null) saveKey(out.key)
                val added = AmzEngine.importConfs(app, out.confs.map { Triple(it.text, it.name, it.country) })
                if (out.key != null) {
                    // A new key: every server was made again with it, so the ones of the old key go.
                    val stale = before - added.toSet()
                    if (stale.isNotEmpty()) AmzEngine.delete(stale)
                    save(added.toSet())
                } else if (added.isNotEmpty()) save(before + added)
            }
            is ProtonApi.Outcome.NothingNew -> saveV6(out.v6)
            else -> Unit
        }
        return out
    }

    /**
     * Out of the account: a Proton connection is ended, the account's servers leave the list and
     * the page's sign-in is forgotten -- the screen is back to «ورود / ساخت حساب». The configs stay
     * in the account itself (Proton's dashboard); signing in again takes fresh ones.
     */
    fun signOut(context: Context) {
        init(context)
        val mine = _ids.value
        val active = when (val c = AmzEngine.connect.value) {
            is AmzConnect.Connecting -> c.serverId
            is AmzConnect.Connected -> c.serverId
            else -> null
        }
        if (active != null && active in mine) disconnect(context)
        _tried.value = false
        if (mine.isNotEmpty()) AmzEngine.delete(mine)
        save(emptySet())
        // The key belongs to this account; another account signing in gets one of its own.
        saveKey(null)
        ProtonApi.forgetSession()
    }

    fun remove(context: Context, id: String) {
        init(context)
        AmzEngine.delete(listOf(id))
        save(_ids.value - id)
    }

    /** The fastest working Proton server (or the last used, or the most promising); never another provider's. */
    fun connect(context: Context) {
        init(context)
        val mine = _ids.value
        if (mine.isEmpty()) return
        _tried.value = true
        AmzEngine.connectAsync(context, only = mine)
    }

    /** One chosen server, with the same fence for the fallback. */
    fun connect(context: Context, serverId: String) {
        init(context)
        _tried.value = true
        AmzEngine.connectAsync(context, serverId = serverId, only = _ids.value)
    }

    /**
     * The end of a sign-in that needed another engine: that engine is turned off (Proton is a
     * WireGuard tunnel of its own and cannot run inside another tunnel), the servers just taken
     * are pinged, and Proton connects to the fastest one.
     */
    fun switchToProton(context: Context) {
        init(context)
        if (_switching.value || _ids.value.isEmpty()) return
        _switching.value = true
        scope.launch {
            try {
                ProtonAccess.stop(app)
                ProtonAccess.awaitDown()
                AmzEngine.testServers(_ids.value)?.also { watchPing(it) }?.let { job -> withTimeoutOrNull(PING_WAIT_MS) { job.join() } }
                connect(app)
            } finally { _switching.value = false }
        }
    }

    /** Pings every Proton server (the rows show each answer as it comes). Only ever on the user's tap. */
    fun pingAll(context: Context) {
        init(context)
        watchPing(AmzEngine.testServers(_ids.value))
    }

    fun disconnect(context: Context) {
        init(context)
        _tried.value = false
        AmzEngine.disconnectAsync(context)
    }

    private fun save(set: Set<String>) {
        _ids.value = set
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_IDS, set).apply()
    }

    // ── the page ────────────────────────────────────────────────────────────────────────────────

    /** Proton's own login page; after signing in it lands on the Downloads page. */
    const val SIGN_IN_URL = "https://account.protonvpn.com/downloads#wireguard-configuration"
    const val SIGN_UP_URL = "https://account.protonvpn.com/signup?from=mobile"

    /** Hosts the in-app page may stay on: Proton's own, nothing else. */
    fun isProtonHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "proton.me" || h.endsWith(".proton.me") || h == "protonvpn.com" || h.endsWith(".protonvpn.com")
    }

    /**
     * Run on every page load of Proton's site. The dashboard makes the `.conf` in the browser and
     * saves it as a downloaded file (a `blob:` link on an `<a download>`), which a WebView hands
     * to nobody -- so the blob is read as it is made, and the text goes to the app. Only a
     * WireGuard config is passed on; the app checks it again.
     *
     * The bridge is exposed to every frame of the page, other sites' (a CAPTCHA's) included, so each
     * call must carry [TOKEN_PLACEHOLDER]'s replacement: a random value only this script holds in a
     * closure, which a frame from another origin cannot read.
     */
    fun captureJs(token: String): String = CAPTURE_JS.replace(TOKEN_PLACEHOLDER, token)

    private const val TOKEN_PLACEHOLDER = "__MLM_TOKEN__"

    private const val CAPTURE_JS = """(function(){
if(window.__mlmProton)return;window.__mlmProton=1;
var last='';
function send(t,n){try{if(t&&t.indexOf('[Interface]')>=0&&t.indexOf('[Peer]')>=0&&t.length<20000)MlmProton.conf('__MLM_TOKEN__',t,n||last||'');}catch(e){}}
var oc=URL.createObjectURL;
URL.createObjectURL=function(b){
try{if(b&&b.size<20000&&typeof FileReader!=='undefined'){var r=new FileReader();r.onload=function(){send(String(r.result),'');};r.readAsText(b);}}catch(e){}
return oc.apply(this,arguments);};
function note(a){try{if(a&&a.download)last=a.download;}catch(e){}}
var ac=HTMLAnchorElement.prototype.click;
HTMLAnchorElement.prototype.click=function(){note(this);return ac.apply(this,arguments);};
var ad=EventTarget.prototype.dispatchEvent;
HTMLAnchorElement.prototype.dispatchEvent=function(e){note(this);return ad.call(this,e);};
})();"""
}
