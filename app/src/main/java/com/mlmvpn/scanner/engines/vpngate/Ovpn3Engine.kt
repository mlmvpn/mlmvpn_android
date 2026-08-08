package com.mlmvpn.scanner.engines.vpngate

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.mlmvpn.core.warp.IVpnEngine
import com.mlmvpn.scanner.MyVpnService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.openvpn.ovpn3.ClientAPI_Config
import net.openvpn.ovpn3.ClientAPI_Event
import net.openvpn.ovpn3.ClientAPI_LogInfo
import net.openvpn.ovpn3.ClientAPI_OpenVPNClient
import net.openvpn.ovpn3.ClientAPI_ProvideCreds

/**
 * Runs openvpn3 directly, in our own process, on MyVpnService's own TUN.
 *
 * The `com.tim.openvpn` service layer that ships with the AAR is only a wrapper around this
 * same native core, and it is not usable for diagnosis: `OpenVPNLogger.d()` and `.e()` have
 * empty bodies in the published build, so every message openvpn3 produces — including the
 * reason a connection fails — is discarded before it reaches logcat. Driving
 * `ClientAPI_OpenVPNClient` ourselves is the only way to see `event()` and `log()`, and it
 * also removes the second `:openvpn` process and its second VpnService.
 */
class Ovpn3Engine : IVpnEngine {

    companion object {
        private const val TAG = "Ovpn3"
        private const val CONNECT_TIMEOUT_MS = 75_000L

        /** Dev-machine packet relay, "host:port". Empty in any build that ships. */
        private const val DEBUG_RELAY = ""

        /** VPN Gate's public credentials — harmless when the server doesn't ask. */
        private const val USERNAME = "vpn"
        private const val PASSWORD = "vpn"

        @Volatile private var nativeLoaded = false

        @Synchronized
        private fun ensureNativeLoaded(): Boolean {
            if (nativeLoaded) return true
            return try {
                System.loadLibrary("ovpn3")
                nativeLoaded = true
                Log.d(TAG, "libovpn3.so loaded")
                true
            } catch (e: Throwable) {
                Log.e(TAG, "failed to load libovpn3.so", e)
                false
            }
        }
    }

    private var client: Ovpn3Client? = null
    private var worker: Thread? = null
    private val connected = CompletableDeferred<Boolean>()

    @Volatile private var reachedConnected = false
    @Volatile private var stopped = false

    override suspend fun start(context: Context, config: String, localPort: Int): Boolean {
        if (!ensureNativeLoaded()) return false

        val service = MyVpnService.instance
        if (service == null) {
            Log.e(TAG, "no MyVpnService instance — cannot build a TUN")
            return false
        }

        val host = config.removePrefix(VpnGateEngine.URI_SCHEME).ifBlank { "vpngate" }
        val ovpn = try {
            VpnGateEngine.pendingFile(context.applicationContext).readText()
        } catch (e: Exception) {
            Log.e(TAG, "failed to read pending profile", e)
            return false
        }
        if (ovpn.isBlank()) {
            Log.e(TAG, "pending profile is empty")
            return false
        }

        var hardened = OvpnProfileBuilder.harden(ovpn)

        // TEMPORARY DIAGNOSTIC — set to "" to disable.
        // Redirects the dial through a packet-decoding relay on the dev machine, so what this
        // core actually puts on the wire can be compared against a client that completes the
        // same handshake from the same Wi-Fi. The upstream is fixed on the relay side, so any
        // server in the list works; VPN Gate shares one CA and one *.opengw.net server cert.
        if (DEBUG_RELAY.isNotEmpty()) {
            val (rh, rp) = DEBUG_RELAY.split(":")
            hardened = hardened.lines().joinToString("\n") {
                if (it.trimStart().startsWith("remote ")) "remote $rh $rp" else it
            }
            Log.w(TAG, "DIAGNOSTIC BUILD: dialling relay $DEBUG_RELAY instead of the server")
        }
        OvpnProfileBuilder.remoteOf(hardened)?.let { (h, p) -> Log.d(TAG, "dialling $h:$p ($host)") }
        // Echo the directives we inject, so a log can never again leave it ambiguous whether
        // the build under test actually carried them.
        Log.d(TAG, "injected: " + hardened.lines()
            .filter { it.startsWith("tls-") || it.startsWith("<auth-user-pass>") ||
                      it.startsWith("connect-retry") || it.startsWith("proto ") }
            .joinToString(" | "))

        return try {
            val c = Ovpn3Client(service, ::onEvent)
            client = c

            val cfg = ClientAPI_Config().apply {
                content = hardened
                // The TLS relaxations live in the profile text (`tls-cert-profile insecure`,
                // `tls-version-min 1.0`), NOT here. The override setters take a different
                // vocabulary from the config file — passing the config-file spelling made
                // connect() fail instantly with
                //     option_error: tls-version-min: unrecognized override string
                // eval_config() accepts the directives in the profile, so that is the one
                // place to express them.
                // Route DNS lookups synchronously; the async resolver needs a running event
                // loop we don't provide here.
                synchronousDnsLookup = true
                info = true
            }

            val eval = c.eval_config(cfg)
            if (eval.error) {
                Log.e(TAG, "eval_config REJECTED the profile: ${eval.message}")
                return false
            }
            Log.d(TAG, "eval_config ok: ${eval.remoteHost}:${eval.remotePort}/${eval.remoteProto} " +
                    "autologin=${eval.autologin}")

            // Supplied unconditionally: some VPN Gate operators turn auth-user-pass on, and a
            // server that asks while the client has nothing to give just stalls.
            c.provide_creds(ClientAPI_ProvideCreds().apply {
                username = USERNAME
                password = PASSWORD
            })

            worker = Thread({
                try {
                    val status = c.connect()
                    Log.d(TAG, "connect() returned error=${status.error} " +
                            "status=${status.status} message=${status.message}")
                    if (status.error && !connected.isCompleted) connected.complete(false)
                } catch (e: Throwable) {
                    Log.e(TAG, "connect() threw", e)
                    if (!connected.isCompleted) connected.complete(false)
                }
            }, "ovpn3-connect").apply { start() }

            val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() } ?: false
            if (!ok) Log.e(TAG, "connect failed or timed out for $host")
            if (!ok) teardownQuietly()
            ok
        } catch (e: Throwable) {
            Log.e(TAG, "failed to start openvpn3", e)
            teardownQuietly()
            false
        }
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        try {
            client?.stop()
        } catch (e: Throwable) {
            Log.e(TAG, "error stopping openvpn3", e)
        } finally {
            if (!connected.isCompleted) connected.complete(false)
            try { worker?.join(2000) } catch (_: Exception) {}
            try { client?.closeTun() } catch (_: Exception) {}
            worker = null
            client = null
        }
    }

    private fun onEvent(name: String, info: String, fatal: Boolean) {
        when {
            name == "CONNECTED" -> {
                reachedConnected = true
                if (!connected.isCompleted) connected.complete(true)
            }
            // AUTH_FAILED, CERT_VERIFY_FAIL, CLIENT_HALT, … — retrying cannot help.
            fatal -> if (!connected.isCompleted) connected.complete(false)
            name == "DISCONNECTED" && reachedConnected && !stopped -> reportDrop()
        }
    }

    private fun reportDrop() {
        val ctx = MyVpnService.instance ?: return
        try {
            ctx.startService(
                android.content.Intent(ctx, MyVpnService::class.java).apply { action = "STOP" }
            )
        } catch (e: Exception) {
            Log.e(TAG, "failed to report drop", e)
        }
    }

    private fun teardownQuietly() {
        try { client?.stop() } catch (_: Exception) {}
        try { client?.closeTun() } catch (_: Exception) {}
        stopped = true
    }
}

/**
 * The openvpn3 callback surface. Everything the core wants to tell us arrives here — which is
 * the entire point of this class existing.
 */
private class Ovpn3Client(
    private val service: VpnService,
    private val onEvent: (name: String, info: String, fatal: Boolean) -> Unit,
) : ClientAPI_OpenVPNClient() {

    private companion object { const val TAG = "Ovpn3" }

    private var builder: VpnService.Builder? = null
    /** Raw descriptor handed to the core, which owns it from that point on. */
    private var tunFd: Int = -1

    // ---- diagnostics --------------------------------------------------------------------

    override fun log(info: ClientAPI_LogInfo) {
        Log.d(TAG, info.text.trimEnd('\n'))
    }

    override fun event(ev: ClientAPI_Event) {
        val name = ev.name ?: return
        val info = ev.info ?: ""
        if (ev.error || ev.fatal) Log.e(TAG, "EVENT $name: $info (fatal=${ev.fatal})")
        else Log.d(TAG, "EVENT $name: $info")
        onEvent(name, info, ev.fatal)
    }

    // ---- socket protection --------------------------------------------------------------

    override fun socket_protect(socket: Int, remote: String?, ipv6: Boolean): Boolean {
        // Without this the core's own socket would be routed back into the tunnel it is
        // building, and the connection would deadlock the moment the TUN comes up.
        val ok = service.protect(socket)
        if (!ok) Log.w(TAG, "protect($socket) failed for $remote")
        return ok
    }

    // ---- tun builder ---------------------------------------------------------------------

    // Every tun_builder_* the core may call has to be answered. The base class returns false,
    // and openvpn3 treats a single false as a fatal tun_prop_error: the whole session is
    // discarded right after ASSIGN_IP, with the TLS handshake already successfully completed.
    // That is what "tun_builder_set_remote_address failed" was.

    override fun tun_builder_new(): Boolean {
        builder = service.Builder()
        return true
    }

    override fun tun_builder_set_layer(layer: Int): Boolean = layer == 3

    /**
     * The server's own address. No bypass route is needed for it because the core's socket is
     * already protect()ed, so acknowledging it is enough.
     */
    override fun tun_builder_set_remote_address(address: String?, ipv6: Boolean): Boolean = true

    override fun tun_builder_set_route_metric_default(metric: Int): Boolean = true

    override fun tun_builder_exclude_route(
        address: String, prefixLength: Int, metric: Int, ipv6: Boolean,
    ): Boolean = true

    override fun tun_builder_set_allow_family(af: Int, allow: Boolean): Boolean = true

    override fun tun_builder_add_wins_server(address: String?): Boolean = true

    override fun tun_builder_add_proxy_bypass(bypassHost: String?): Boolean = true

    override fun tun_builder_set_proxy_auto_config_url(url: String?): Boolean = true

    override fun tun_builder_set_proxy_http(host: String?, port: Int): Boolean = true

    override fun tun_builder_set_proxy_https(host: String?, port: Int): Boolean = true

    override fun tun_builder_set_adapter_domain_suffix(name: String?): Boolean = true

    override fun tun_builder_set_session_name(name: String?): Boolean {
        builder?.setSession(name ?: "MLMVPN")
        return true
    }

    override fun tun_builder_set_mtu(mtu: Int): Boolean {
        builder?.setMtu(mtu)
        return true
    }

    override fun tun_builder_add_address(
        address: String, prefixLength: Int, gateway: String?, ipv6: Boolean, net30: Boolean,
    ): Boolean {
        builder?.addAddress(address, prefixLength)
        return true
    }

    override fun tun_builder_add_route(
        address: String, prefixLength: Int, metric: Int, ipv6: Boolean,
    ): Boolean {
        // 0.0.0.0/0 arrives separately via reroute_gw on some servers; adding it here too is
        // harmless because VpnService de-duplicates identical routes.
        return try {
            builder?.addRoute(address, prefixLength)
            true
        } catch (e: Exception) {
            Log.w(TAG, "addRoute($address/$prefixLength) rejected: ${e.message}")
            false
        }
    }

    override fun tun_builder_reroute_gw(ipv4: Boolean, ipv6: Boolean, flags: Long): Boolean {
        if (ipv4) builder?.addRoute("0.0.0.0", 0)
        if (ipv6) builder?.addRoute("::", 0)
        return true
    }

    override fun tun_builder_add_dns_server(address: String, ipv6: Boolean): Boolean {
        return try {
            builder?.addDnsServer(address)
            true
        } catch (e: Exception) {
            Log.w(TAG, "addDnsServer($address) rejected: ${e.message}")
            false
        }
    }

    override fun tun_builder_add_search_domain(domain: String): Boolean {
        builder?.addSearchDomain(domain)
        return true
    }

    override fun tun_builder_establish(): Int {
        val b = builder ?: return -1
        return try {
            b.addDisallowedApplication(service.packageName)
            val pfd = b.establish() ?: run {
                Log.e(TAG, "establish() returned null — VPN permission revoked?")
                return -1
            }
            // detachFd(), not fd. The core takes ownership and closes it itself; handing over
            // a borrowed descriptor and then closing our ParcelFileDescriptor in teardown
            // pulls it out from under the native side, which crashed the process on the
            // reconnect that followed the data-channel error.
            val fd = pfd.detachFd()
            tunFd = fd
            Log.d(TAG, "TUN established, fd=$fd")
            fd
        } catch (e: Exception) {
            Log.e(TAG, "establish() failed", e)
            -1
        }
    }

    override fun tun_builder_persist(): Boolean = true

    override fun tun_builder_teardown(disconnect: Boolean) {
        // The core is telling us it is done with the descriptor it owns — just drop our
        // bookkeeping. Closing here is what caused the double close.
        tunFd = -1
        builder = null
    }

    /**
     * Only reclaims the descriptor if the core never took it. Once handed over, closing it
     * here would be a double close.
     */
    fun closeTun() {
        val fd = tunFd
        tunFd = -1
        builder = null
        if (fd >= 0) {
            try { ParcelFileDescriptor.adoptFd(fd).close() } catch (_: Exception) {}
        }
    }
}
