package com.mlmvpn.core.tunnel

import org.json.JSONObject

/**
 * Kotlin side of the tunnel core's C API.
 *
 * The core ships as `libtunnelcore.so`, cross-compiled from Rust for each
 * Android ABI by `native/build-engine.ps1`. Until that library is in
 * `jniLibs`, [isAvailable] is false and nothing here pretends to connect.
 *
 * Two ways to start, and the difference decides the whole data path:
 *
 *   [start]       hands the core the tun file descriptor. The core owns the
 *                 device's traffic directly and there is no local SOCKS
 *                 listener at all - anything wanting to reach the tunnel must
 *                 go through the tun like every other app.
 *   [startProxy]  starts with no tun. The core builds its netstack in userspace
 *                 and publishes a local SOCKS5 port instead. tun2socks then
 *                 bridges the device's tun into that port.
 *
 * Both block until the tunnel ends, so both belong on a worker thread.
 */
object TunnelEngine {

    /**
     * False when the native pair is not bundled for this device's ABI.
     *
     * Two libraries, loaded in order: the core itself, then the JNI shim that
     * binds it to the methods below. The core exports plain C symbols, so the
     * shim is what makes `external fun` resolve at all - loading only the core
     * would leave every call unresolved.
     */
    val isAvailable: Boolean = runCatching {
        // Through the store, which prefers a copy installed from «ام‌ال‌ام استور» and falls back
        // to the shipped pair on any failure.
        com.mlmvpn.scanner.store.StoreEngines.loadLibrary("tunnelcore", "tunnelcore")
        com.mlmvpn.scanner.store.StoreEngines.loadLibrary("tunnelcore", "tunneljni")
    }.isSuccess

    /** What the core reports after [prepare]: the addresses the tun must carry. */
    data class TunnelAddresses(
        val ipv4: String,
        val ipv6: String,
        val gatewayProxy: String = "",
        val organization: String = "",
    )

    /**
     * Negotiates with the gateway and returns the addresses to build the tun
     * with. Must run before [start], because VpnService.Builder needs the
     * addresses before there is a descriptor to hand back down.
     */
    fun prepare(config: String): TunnelAddresses {
        // Iran filters the WARP registration API. When the user has set up the identity relay,
        // the identity is made through their own Worker and saved where the core loads it, so
        // the core never has to reach the filtered host. See WarpIdRelay.
        // Marked before the identity is made: a seal of the previous session's files, still
        // running on its own thread, must not remove what this one is about to load.
        preparing = true
        try {
            com.mlmvpn.core.warp.WarpIdRelay.ensure(config)
            check(nativePrepare(config) == 0) { nativeLastError() }
        } finally {
            preparing = false
        }
        com.mlmvpn.core.warp.WarpIdRelay.afterPrepare(config)
        val result = JSONObject(nativeLastResult())
        return TunnelAddresses(
            result.getString("ipv4"),
            result.optString("ipv6"),
            result.optString("gateway_proxy"),
            result.optString("organization"),
        )
    }

    fun start(config: String, tunFd: Int): Int { coreEnv(config); return nativeStart(config, tunFd) }

    fun startProxy(config: String): Int { coreEnv(config); return nativeStartProxy(config) }

    /** Dedicated Scout process only: identity provisioning and file ownership belong to its caller. */
    fun prepareForProbe(config: String) {
        probeEnv(config)
        check(nativePrepare(config) == 0) { "probe_prepare_failed" }
    }

    fun startProxyForProbe(config: String): Int { probeEnv(config); return nativeStartProxy(config) }

    /** Never calls the normal stop hook, which seals the live VPN's identity directory. */
    fun stopForProbe(): Int = nativeStop()

    private fun probeEnv(config: String) {
        coreEnv(config)
        android.system.Os.setenv("AETHER_QUICK_RECONNECT", "0", true)
    }

    /**
     * Environment the core reads at run time and the JSON cannot carry.
     *
     * AETHER_WG_STALE_SECS: the core declares a WireGuard tunnel dead after 10 s without a
     * decryptable packet. On WARP-in-WARP the inner hop's replies ride the outer tunnel, and a
     * gap of 11 s was enough (device log 2026-10-04 19:40): «وارپ در وارپ» carried traffic, the
     * watchdog fired, and the teardown that followed crashed the old core -- so the user saw it
     * connect and then drop. The Aether screen's engine already runs with 120 s for the same
     * reason (AetherOptions.staleSecs); the in-process core gets the same. Set before every start
     * because the core reads it when the tunnel comes up.
     */
    private fun coreEnv(config: String) {
        runCatching { android.system.Os.setenv("AETHER_WG_STALE_SECS", "120", true) }
        // WireGuard's scan reads its mode and address family from AETHER_SCAN / AETHER_IP, not from
        // the JSON (the core's run_wireguard; MASQUE and WARP-in-WARP do take the JSON). Unset, they
        // fell to "balanced" over IPv4 alone: on a line whose WARP IPv4 is closed -- the phone,
        // 2026-10-07: 0 of 2540 IPv4 endpoints, IPv6 within seconds -- «وایرگارد» made its identity
        // and then never connected. The JSON's own values, for whichever transport starts.
        val cfg = runCatching { JSONObject(config) }.getOrNull() ?: return
        cfg.optString("scan_mode").ifBlank { null }?.let { mode -> runCatching { android.system.Os.setenv("AETHER_SCAN", mode, true) } }
        cfg.optString("ip_scan").ifBlank { null }?.let { ip -> runCatching { android.system.Os.setenv("AETHER_IP", ip, true) } }
        // A remembered WireGuard endpoint that verifies sends the core down a branch that rescans
        // over IPv4 alone once that endpoint dies mid-session. Its turbo scan finds a fresh endpoint
        // in seconds, so WireGuard always scans; the other transports keep their quick reconnect.
        runCatching {
            android.system.Os.setenv("AETHER_QUICK_RECONNECT", if (cfg.optString("protocol").equals("wireguard", true)) "0" else "1", true)
        }
    }

    /** True while [prepare] is inside the core, which may be waiting on a filtered API. */
    @Volatile var preparing: Boolean = false
        private set

    /**
     * Stop the core.
     *
     * While [prepare] is still inside it, the native stop waits on the lock prepare holds, so
     * calling it from the main thread froze the cancel button until the registration attempt gave
     * up minutes later. Then it is sent from a thread of its own: the caller returns at once, and
     * the service already knows the user asked to stop.
     */
    fun stop(): Int {
        if (preparing) {
            Thread({ try { nativeStop() } finally { com.mlmvpn.core.warp.WarpIdRelay.onCoreStop() } }, "core-stop").start()
            return 0
        }
        return try { nativeStop() } finally { com.mlmvpn.core.warp.WarpIdRelay.onCoreStop() }
    }

    /** True while the core itself is carrying traffic. See [TunnelStatus]. */
    fun isRunning(): Boolean = nativeIsRunning()

    /** True once the handshake is done and the tunnel is actually usable. */
    fun isReady(): Boolean = nativeIsReady()

    fun lastError(): String = nativeLastError()

    fun lastLog(): String = nativeLastLog()

    /**
     * Gives the core a way back into the service.
     *
     * [service] must expose `protectSocket(Int): Boolean` and
     * `onEvent(String)`; the shim looks both up by name and signature. Without
     * the first, every socket the core opens is routed back into the tun it is
     * trying to feed, and the tunnel deadlocks on its own traffic.
     */
    /**
     * What the shim calls back into. The service implements it; the name and
     * signature are looked up reflectively in tunnel_jni.cpp, so both must stay
     * as they are.
     */
    interface CoreCallback {
        fun onEvent(json: String)
    }

    fun attach(service: TunnelVpnService) = nativeAttach(service)

    fun detach() = nativeDetach()

    // --- native surface, one-to-one with the exported C functions -----------

    @JvmStatic private external fun nativePrepare(config: String): Int
    @JvmStatic private external fun nativeLastResult(): String
    @JvmStatic private external fun nativeRequestEmailCode(team: String, email: String): Int
    @JvmStatic private external fun nativeConfirmEmailCode(code: String): Int
    @JvmStatic private external fun nativeStart(config: String, tunFd: Int): Int
    @JvmStatic private external fun nativeStartProxy(config: String): Int
    @JvmStatic private external fun nativeStop(): Int
    @JvmStatic private external fun nativeIsRunning(): Boolean
    @JvmStatic private external fun nativeIsReady(): Boolean
    @JvmStatic private external fun nativeLastError(): String
    @JvmStatic private external fun nativeLastLog(): String
    @JvmStatic private external fun nativeAttach(service: TunnelVpnService)
    @JvmStatic private external fun nativeDetach()
}
