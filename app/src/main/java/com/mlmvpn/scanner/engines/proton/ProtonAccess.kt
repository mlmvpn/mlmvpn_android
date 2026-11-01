package com.mlmvpn.scanner.engines.proton

import android.content.Context
import android.content.Intent
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.mlmvpn.core.tunnel.TunnelStatus
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.engines.flux.FluxEngine
import com.mlmvpn.scanner.engines.flux.core.model.FluxUiState
import com.mlmvpn.scanner.ui.tunnel.Transport
import com.mlmvpn.scanner.ui.tunnel.TunnelController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * How the sign-in page reaches Proton when Proton is filtered.
 *
 * Proton's site is blocked on many Iranian lines, and the app's own page cannot ride the tunnel of
 * another engine: the tunnels leave the app itself out of the VPN (its own sockets must not loop
 * back into it), so a WebView that simply opens the page goes direct and gets nothing. The way
 * round is the engine's local listener -- every engine that is up publishes one on loopback -- and
 * the WebView is pointed at it (and only the WebView, only while the page is open).
 *
 * So the user turns on any engine, signs in, takes the servers, and the engine is turned off again
 * before Proton itself is connected (Proton is a WireGuard tunnel of its own and cannot run inside
 * another one).
 */
object ProtonAccess {

    /** A local listener that carries traffic through whatever engine is up. */
    data class Route(val http: Boolean, val port: Int) {
        /** The rule the WebView's proxy override takes. */
        val rule: String get() = (if (http) "http" else "socks5") + "://127.0.0.1:$port"
        fun javaProxy(): Proxy = Proxy(if (http) Proxy.Type.HTTP else Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
    }

    /** The engines the page can switch on by itself. Any other that is up is used just the same. */
    enum class Helper { FLUX, TOR, WIREGUARD }

    /**
     * The listener of the engine that is up and carrying traffic, or null: nothing is on, one is
     * still starting, or the one that is on has no listener (the AmneziaWG tunnel Proton itself
     * runs on is exactly that -- a tunnel with nothing to point a page at).
     */
    fun current(): Route? {
        if (MyVpnService.isRunning) {
            if (MyVpnService.connectionPhaseFlow.value != MyVpnService.Phase.CONNECTED) return null
            // FLUX brings the tunnel up for each candidate and only then sees whether it carries
            // anything, so the service reads CONNECTED while FLUX is still searching. Its own
            // Connected comes after a real request went through.
            if (MyVpnService.connectedNodeIdFlow.value == FluxEngine.NODE_ID && FluxEngine.ui.value !is FluxUiState.Connected) return null
            // The Xray family's `mixed` inbound: SOCKS5 and HTTP on one port.
            val p = MyVpnService.runtimeSocksPort
            return if (p > 0) Route(http = true, port = p) else null
        }
        // The transports on the tunnel service publish plain SOCKS5 (Tor's front-end, the core's).
        if (runCatching { TunnelStatus.isActive() }.getOrDefault(false)) {
            val p = TunnelStatus.activeSocksPort
            if (p > 0) return Route(http = false, port = p)
        }
        return null
    }

    /** True while an engine is up or coming up, whether or not it has a listener to use. */
    fun anythingUp(): Boolean =
        MyVpnService.isRunning || runCatching { TunnelStatus.isActive() }.getOrDefault(false)

    /**
     * Whether Proton's sign-in site answers on this route. Any HTTP answer counts, even a refusal:
     * what a filter does is drop or reset the connection, and that is what this tells apart.
     *
     * Through an engine it is asked more patiently: an engine that has just come up, or a slow one
     * (FLUX on a busy line took 8-17 s a request, measured), misses one short try often enough that
     * a single 7 s try sent users back to the engine list with the engine working.
     */
    suspend fun reachable(route: Route?): Boolean = withContext(Dispatchers.IO) {
        val tries = if (route != null) VIA_TRIES else 1
        val ms = if (route != null) VIA_MS else CHECK_MS
        repeat(tries) { i ->
            if (i > 0) delay(1_000)
            val ok = runCatching {
                val c = URL(CHECK_URL).openConnection(route?.javaProxy() ?: Proxy.NO_PROXY) as HttpURLConnection
                try {
                    c.connectTimeout = ms; c.readTimeout = ms
                    c.instanceFollowRedirects = false
                    c.responseCode in 100..599
                } finally { c.disconnect() }
            }.getOrDefault(false)
            if (ok) return@withContext true
        }
        false
    }

    /**
     * Points the app's WebViews at [route], or back at the network when it is null. The override is
     * process-wide, so whoever sets it clears it when its page closes. False when this WebView
     * cannot do proxy overrides (a very old one): the page then offers the browser instead.
     */
    suspend fun setWebViewProxy(route: Route?): Boolean = kotlinx.coroutines.withContext(proxyDispatcher) {
        suspendCancellableCoroutine { c ->
            if (!proxyOverrideSupported()) { c.resume(route == null); return@suspendCancellableCoroutine }
            val now = Executor { it.run() }
            try {
                if (route == null) ProxyController.getInstance().clearProxyOverride(now) { if (c.isActive) c.resume(true) }
                else ProxyController.getInstance().setProxyOverride(ProxyConfig.Builder().addProxyRule(route.rule).build(), now) { if (c.isActive) c.resume(true) }
            } catch (e: Exception) {
                if (c.isActive) c.resume(false)
            }
        }
    }

    /** Puts the WebViews back on the network. Safe to call when no override was set. */
    fun clearWebViewProxy() {
        proxyThread.execute {
            if (!proxyOverrideSupported()) return@execute
            runCatching { ProxyController.getInstance().clearProxyOverride(Executor { it.run() }) { } }
        }
    }

    /**
     * False also on a phone with no WebView installed at all: asking loads the WebView, which then
     * throws an Error (ExceptionInInitializerError, «No WebView installed»), and that closed the app
     * from this thread (1.2.42 #2276).
     */
    private fun proxyOverrideSupported(): Boolean =
        try { WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) } catch (t: Throwable) { false }

    /**
     * Every proxy change on one thread of its own, in the order it was asked for. On the main thread
     * a change waited on the WebView's network thread -- and its first use loads the whole WebView --
     * which froze the Proton page and ended in ANRs on opening and leaving it (1.2.41).
     */
    private val proxyThread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "webview-proxy").apply { isDaemon = true }
    }
    private val proxyDispatcher: kotlinx.coroutines.CoroutineDispatcher by lazy { proxyThread.asCoroutineDispatcher() }

    /** After the VPN permission was granted. */
    fun start(context: Context, helper: Helper) {
        when (helper) {
            Helper.FLUX -> FluxEngine.connectAsync(context)
            Helper.TOR -> TunnelController.connect(context, Transport.TOR)
            Helper.WIREGUARD -> TunnelController.connect(context, Transport.WIREGUARD)
        }
    }

    /** Turns off whichever engine is up, through the door that engine's own screen uses. */
    fun stop(context: Context) {
        val app = context.applicationContext
        when {
            MyVpnService.isRunning ->
                if (MyVpnService.connectedNodeIdFlow.value == FluxEngine.NODE_ID) FluxEngine.disconnectAsync(app)
                else com.mlmvpn.scanner.utils.SafeStart.service(app, Intent(app, MyVpnService::class.java).apply { action = "STOP" })
            runCatching { TunnelStatus.isActive() }.getOrDefault(false) -> TunnelController.disconnect(app)
        }
    }

    /** Waits until the device's tun is free again (a tunnel is slow to unwind), up to [timeoutMs]. */
    suspend fun awaitDown(timeoutMs: Long = 12_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end && anythingUp()) delay(250)
        delay(600)
    }

    private const val CHECK_URL = "https://account.protonvpn.com/"
    private const val CHECK_MS = 7_000
    private const val VIA_MS = 20_000
    private const val VIA_TRIES = 3
}
