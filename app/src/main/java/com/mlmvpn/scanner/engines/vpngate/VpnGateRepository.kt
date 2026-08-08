package com.mlmvpn.scanner.engines.vpngate

import android.content.Context
import android.util.Base64
import android.util.Log
import com.mlmvpn.scanner.emergency.EmergencyInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Fetches and caches the VPN Gate server list.
 *
 * Three tiers, tried in order, so the tab is never empty:
 *   1. the live API,
 *   2. the on-disk copy of the last successful fetch,
 *   3. the CSV bundled in assets.
 *
 * Follows the app's existing repository idiom (singleton + StateFlow, no ViewModel).
 */
class VpnGateRepository private constructor(private val appCtx: Context) {

    enum class Source { LIVE, CACHE, BUNDLED }

    companion object {
        private const val TAG = "VpnGateRepository"
        private const val API_URL = "http://www.vpngate.net/api/iphone/"

        // www.vpngate.net is DNS-poisoned on Iranian ISPs (it resolves to the 10.10.34.35
        // block page), so the direct fetch can never succeed there. This is the app's own
        // Vercel edge proxy, which resolves and fetches server-side.
        //
        // The upstream MUST stay http:// — proxying the https:// variant fails ("Proxy Error:
        // Network connection lost"), while the plaintext one returns the CSV. EmergencyInterceptor
        // explicitly skips this host, so there is no double-proxying when emergency mode is on.
        private val PROXY_URL = "https://mlm-proxy.vercel.app/api?url=" +
            java.net.URLEncoder.encode(API_URL, "UTF-8")
        private const val ASSET_PATH = "vpngate/default_servers.csv"
        private const val CACHE_DIR = "vpngate"
        private const val CACHE_FILE = "servers.csv"

        @Volatile
        private var instance: VpnGateRepository? = null

        operator fun invoke(context: Context): VpnGateRepository =
            instance ?: synchronized(this) {
                instance ?: VpnGateRepository(context.applicationContext).also { instance = it }
            }
    }

    private val _servers = MutableStateFlow<List<VpnGateServer>>(emptyList())
    val serversFlow: StateFlow<List<VpnGateServer>> = _servers.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loadingFlow: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val errorFlow: StateFlow<String?> = _error.asStateFlow()

    private val _source = MutableStateFlow<Source?>(null)
    val sourceFlow: StateFlow<Source?> = _source.asStateFlow()

    // Own client on purpose: never touch the one SubscriptionManager built. The emergency
    // interceptor reroutes the fetch through the Vercel proxy when the domain is blocked.
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(EmergencyInterceptor(appCtx))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    private val cacheFile: File
        get() = File(File(appCtx.filesDir, CACHE_DIR).apply { mkdirs() }, CACHE_FILE)

    /**
     * Loads the list. When [force] is false and servers are already loaded this is a no-op,
     * so re-entering the tab doesn't re-hit the network.
     */
    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        VpnGatePool.load(appCtx)
        if (!force && _servers.value.isNotEmpty()) return@withContext
        if (_loading.value) return@withContext

        _loading.value = true
        _error.value = null
        try {
            var liveError: String? = null

            // 1) Live API, direct first then through the proxy. Direct is preferred whenever
            //    it works (no third party sees the request); the proxy exists because the
            //    domain is blocked on the networks this app is actually used on.
            for ((label, url) in listOf("direct" to API_URL, "proxy" to PROXY_URL)) {
                try {
                    val csv = fetch(url)
                    val parsed = VpnGateCsvParser.parse(csv)
                    if (parsed.isNotEmpty()) {
                        // Cache the RAW csv before any consumer sees the parse, and do it
                        // atomically, so a future parser change can't poison the cache.
                        writeCacheAtomically(csv)
                        publish(parsed, Source.LIVE)
                        return@withContext
                    }
                    liveError = "$label: empty list"
                    Log.w(TAG, "live fetch ($label) returned no servers")
                } catch (e: Exception) {
                    liveError = "$label: ${e.message ?: e.javaClass.simpleName}"
                    Log.w(TAG, "live fetch ($label) failed: $liveError")
                }
            }

            // 2) Disk cache from the last good fetch.
            try {
                val f = cacheFile
                if (f.exists()) {
                    val parsed = VpnGateCsvParser.parse(f.readText())
                    if (parsed.isNotEmpty()) {
                        publish(parsed, Source.CACHE)
                        _error.value = liveError
                        return@withContext
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "cache read failed", e)
            }

            // 3) Bundled asset. Stale by construction — the UI labels the source.
            try {
                val csv = appCtx.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
                val parsed = VpnGateCsvParser.parse(csv)
                if (parsed.isNotEmpty()) {
                    publish(parsed, Source.BUNDLED)
                    _error.value = liveError
                    return@withContext
                }
            } catch (e: Exception) {
                Log.w(TAG, "bundled asset read failed", e)
            }

            _error.value = liveError ?: "no server list available"
        } finally {
            _loading.value = false
        }
    }

    /** Decodes column 15 into the .ovpn profile text for [server]. */
    fun ovpnTextFor(server: VpnGateServer): String =
        String(Base64.decode(server.configBase64, Base64.DEFAULT), Charsets.UTF_8)

    /** Servers seen for the first time by the most recent successful refresh. */
    private val _newlyDiscovered = MutableStateFlow(0)
    val newlyDiscoveredFlow: StateFlow<Int> = _newlyDiscovered.asStateFlow()

    private suspend fun publish(servers: List<VpnGateServer>, source: Source) {
        _servers.value = servers
        _source.value = source
        // Every refresh folds into the archive. VPN Gate rotates its ~100-server window
        // constantly, so this is the only way the catalogue ever grows past one page.
        _newlyDiscovered.value = VpnGatePool.merge(appCtx, servers)
        Log.d(TAG, "loaded ${servers.size} servers from $source")
    }

    /** Decodes the profile of any server, pooled or live. */
    fun ovpnTextOf(server: VpnGateServer): String = ovpnTextFor(server)

    private fun fetch(url: String): String {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return resp.body.string()
        }
    }

    private fun writeCacheAtomically(csv: String) {
        try {
            val target = cacheFile
            val tmp = File(target.parentFile, "${CACHE_FILE}.tmp")
            tmp.writeText(csv)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) tmp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "cache write failed", e)
        }
    }
}
