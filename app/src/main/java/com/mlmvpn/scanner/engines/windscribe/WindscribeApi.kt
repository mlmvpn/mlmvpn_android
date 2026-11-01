package com.mlmvpn.scanner.engines.windscribe

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mlmvpn.scanner.engines.flux.FluxNet
import com.wsnet.lib.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import kotlin.coroutines.resume

/** Standalone WSNetServerAPI, as used by official Android; no hand-written endpoints or request secrets. */
internal class WsApi(private val app: Context, private val repository: WsRepository) {
    // Hold all JNI peers and callback handles strongly: native peers use weak Java references.
    private val ws: WSNet
    private val api: WSNetServerAPI
    private val advanced: WSNetAdvancedParameters
    private val resolver: WSNetDnsResolver
    var backup: Int = -1
    var padding: Boolean = true
    var onVpnRoute: Boolean = false
        private set
    init {
        try {
            val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName
            check(WSNet.initialize("android", "android", version, repository.installationId(), "2.6.0", "4", false,
                app.resources.configuration.locales[0].language, repository.nativeSettings() ?: "", { _ -> }, true, "2.0"))
            ws = WSNet.instance()
            api = ws.serverAPI()
            advanced = ws.advancedParameters()
            resolver = ws.dnsResolver()
            api.setIgnoreSslErrors(false)
            advanced.setLogApiResponce(false)
            advanced.setSuppressBridgeApiTokenRequest(true)
        } catch (_: LinkageError) { throw WsException(WsError.CORE) }
    }
    fun mode(network: String, reset: Boolean = false) {
        val preferred = if (reset) null else repository.apiMode(network)
        backup = preferred?.first ?: -1
        padding = preferred?.second ?: true
        advanced.setAPIExtraTLSPadding(padding)
        if (reset) api.resetFailover()
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val current = cm.activeNetwork
        val dns = current?.let { cm.getLinkProperties(it)?.dnsServers?.mapNotNull { d -> d.hostAddress } }.orEmpty()
        resolver.setDnsServers(dns.toTypedArray())
        ws.setConnectivityState(FluxNet.online(app))
        // WSNet skips API failover in connected-VPN state. Another VPN can exclude this app.
        onVpnRoute = cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
        ws.setIsConnectedToVpnState(onVpnRoute)
    }
    /** Retry transport failures only; rejected credentials and expired sessions are never replayed. */
    suspend fun request(network: String, submit: (WSNetServerAPI, WSNetServerAPI.Function1) -> WSNetCancelableCallback): String {
        return try { withTimeout(28_000) { boundedRequest(network, submit) } }
        catch (_: kotlinx.coroutines.TimeoutCancellationException) { currentCoroutineContext().ensureActive(); throw WsException(WsError.NETWORK) }
    }
    private suspend fun boundedRequest(network: String, submit: (WSNetServerAPI, WSNetServerAPI.Function1) -> WSNetCancelableCallback): String {
        var last: WsException? = null
        val modes = listOf(backup to padding, -1 to true, 1 to true, 0 to false).distinct()
        for ((b, pad) in modes) {
            backup = b; padding = pad; advanced.setAPIExtraTLSPadding(pad)
            try {
                val raw = withTimeout(14_000) {
                    suspendCancellableCoroutine<String> { cont ->
                        val handle = submit(api, WSNetServerAPI.Function1 { code, json ->
                            android.util.Log.i("Windscribe", "api backup=$b padding=$pad code=$code bytes=${json?.length ?: 0}")
                            if (cont.isActive) {
                                if (code == 0) cont.resume(json)
                                else cont.resumeWith(Result.failure(WsException(WsError.NETWORK)))
                            }
                        })
                        cont.invokeOnCancellation { handle.cancel() }
                    }
                }
                repository.saveApiMode(network, b, pad)
                repository.saveNativeSettings(ws.currentPersistentSettings())
                return raw
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                android.util.Log.i("Windscribe", "api backup=$b padding=$pad timed out")
                last = WsException(WsError.NETWORK)
            }
            catch (e: WsException) { if (e.error != WsError.NETWORK) throw e; last = e }
        }
        throw last ?: WsException(WsError.NETWORK)
    }
    suspend fun json(network: String, submit: (WSNetServerAPI, WSNetServerAPI.Function1) -> WSNetCancelableCallback): JSONObject {
        val raw = request(network, submit)
        runCatching { JSONObject(raw) }.getOrNull()?.takeIf { it.optInt("errorCode") != 0 }?.let {
            android.util.Log.i("Windscribe", "api error ${it.optInt("errorCode")} ${it.optString("errorMessage").take(80)}")
        }
        return WsContract.data(raw)
    }
    fun responseLoggingDisabled(): Boolean = !advanced.isLogApiResponce
    suspend fun session(id: String, network: String): JSONObject = json(network) { a, cb -> a.session(repository.token(id), "", "", 0, backup, cb) }
    suspend fun servers(id: String, network: String): JSONObject = json(network) { a, cb -> a.getServers(repository.token(id), backup, cb) }
    suspend fun locations(id: String, network: String): JSONObject = json(network) { a, cb -> a.getLocations(repository.token(id), cb) }
    suspend fun ports(id: String, network: String): JSONObject = json(network) { a, cb -> a.portMap(repository.token(id), 5, arrayOf("wstunnel"), cb) }
}
