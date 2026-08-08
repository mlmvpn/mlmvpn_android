package com.mlmvpn.core.aether

/**
 * Options for one Aether run. Same shape as the desktop aether-manager.js — every field
 * maps onto one or more AETHER_* environment variables when the engine is spawned.
 *
 * Three protocols are supported:
 *   - MASQUE: traffic over HTTP/3 (QUIC) or HTTP/2 (TCP) pretending to be HTTPS. Best for
 *     heavy DPI environments.
 *   - WireGuard ("wg"): the classic WARP tunnel. Lighter, but its handshake is easier to
 *     identify; AetherNoize hides it.
 *   - WARP-in-WARP ("gool"): a second WireGuard tunnel nested inside the first. Slowest
 *     of the three, and most resilient to traffic analysis.
 */
data class AetherOptions(
    val protocol: AetherProtocol = AetherProtocol.MASQUE,
    val scan: AetherScan = AetherScan.TURBO,
    val ipFamily: AetherIp = AetherIp.V4,

    /** MASQUE-only. h2 falls back when UDP is throttled. */
    val transport: String = "h3",                  // "h3" | "h2"
    val ech: String = "",                          // "" | "auto" | a base64 ECHConfigList
    val fragment: Boolean = false,                 // true → use AETHER_MASQUE_H2_FRAGMENT
    val fragmentSize: Int? = null,                 // bytes per chunk
    val fragmentDelay: Int? = null,                // ms between chunks
    val validateSecs: Int? = null,                 // AETHER_MASQUE_VALIDATE_SECS
    val reconnectSecs: Int? = null,                // AETHER_MASQUE_RECONNECT_SECS
    val noDataCheck: Boolean = false,              // skip the data-plane probe

    /** WG / WARP-in-WARP only. */
    val keepalive: Int = 5,                        // AETHER_WG_KEEPALIVE
    val noProfileRetry: Boolean = false,           // AETHER_WG_NO_PROFILE_RETRY
    val staleSecs: Int = 120,                      // AETHER_WG_STALE_SECS

    /** Obfuscation: firewall | balanced | off | aggressive | light. */
    val noize: String = "firewall",

    val quickReconnect: Boolean = true,            // AETHER_QUICK_RECONNECT
    val verbose: Boolean = false,                  // RUST_LOG=debug
    val backtrace: Boolean = true,                 // RUST_BACKTRACE=1 — useful when something panics
    /**
     * Only set when the caller already has a proxy and even the api.cloudflareclient.com
     * registration call can't reach the API directly. We're careful to drop this if the
     * proxy isn't actually listening, otherwise it'd silently break an otherwise-working
     * setup.
     */
    val bootstrapProxy: String? = null,

    /** Forced WireGuard peer (optional, expert feature). */
    val peer: String? = null,
    val wgPeer: String? = null,
) {
    /** Translate to the env-var map the Rust binary reads. env vars come from
     * aether/src/cli.rs — every flag there just sets one of these. */
    fun toEnv(baseEnv: Map<String, String>, dataDir: String, socksPort: Int): Map<String, String> {
        val out = baseEnv.toMutableMap()
        out["RUST_BACKTRACE"] = if (backtrace) "1" else "0"
        // aether::prober logs the reason a candidate was rejected at TRACE, not DEBUG. That
        // one line per probe is the only thing that distinguishes "UDP is blocked" from
        // "TLS is being intercepted" from "the endpoint answered but dropped data" — the
        // difference between a network problem and a client problem. Enable it always: the
        // volume is high but AetherEngine aggregates these into a periodic reason histogram
        // instead of forwarding them, so they never reach logcat or the UI individually.
        val base = if (verbose) "debug" else "info"
        out["RUST_LOG"] = "$base,aether::prober=trace"
        out["AETHER_SOCKS"] = "127.0.0.1:$socksPort"
        out["AETHER_PROTOCOL"] = protocol.env
        out["AETHER_SCAN"] = scan.env
        out["AETHER_IP"] = ipFamily.env
        out["AETHER_QUICK_RECONNECT"] = if (quickReconnect) "1" else "0"
        out["AETHER_CONFIG"] = "$dataDir/aether.toml"
        if (noize.isNotEmpty()) out["AETHER_NOIZE"] = noize

        if (protocol == AetherProtocol.MASQUE) {
            if (transport == "h2") out["AETHER_MASQUE_HTTP2"] = "1"
            if (ech.isNotEmpty()) out["AETHER_ECH"] = ech
            if (fragment) {
                out["AETHER_MASQUE_H2_FRAGMENT"] = "1"
                fragmentSize?.let { out["AETHER_MASQUE_H2_FRAGMENT_SIZE"] = it.toString() }
                fragmentDelay?.let { out["AETHER_MASQUE_H2_FRAGMENT_DELAY"] = it.toString() }
            }
            validateSecs?.let { out["AETHER_MASQUE_VALIDATE_SECS"] = it.toString() }
            reconnectSecs?.let { out["AETHER_MASQUE_RECONNECT_SECS"] = it.toString() }
        }
        // Outside the MASQUE block on purpose: this used to be nested inside it, so turning
        // the check off had no effect at all on the WireGuard and gool protocols even though
        // it set the WG variable.
        if (noDataCheck) {
            out["AETHER_MASQUE_NO_DATA_CHECK"] = "1"
            out["AETHER_WG_NO_DATA_CHECK"] = "1"
        }
        if (protocol == AetherProtocol.WG || protocol == AetherProtocol.WARP_IN_WARP) {
            out["AETHER_WG_KEEPALIVE"] = keepalive.toString()
            if (noProfileRetry) out["AETHER_WG_NO_PROFILE_RETRY"] = "1"
            // The core defaults to declaring a tunnel dead after ~12s with no inbound data.
            // That is far too eager here: WireGuard keepalives draw no reply, so an idle
            // tunnel looks identical to a dead one, and on a slow link the very first DNS
            // lookup does not finish inside the window. The teardown then killed the netstack
            // mid-query ("netstack dropped"), the query failed, and the cycle repeated — a
            // healthy tunnel reconnecting every 12s and never carrying a single request.
            // Verified on device: at 120s the same endpoint loads pages normally.
            out["AETHER_WG_STALE_SECS"] = staleSecs.toString()
        }
        if (!peer.isNullOrBlank()) out["AETHER_PEER"] = peer
        if (!wgPeer.isNullOrBlank()) out["AETHER_WG_PEER"] = wgPeer

        // Bootstrap proxy: only when explicitly set, never leak stale values from parent env.
        if (!bootstrapProxy.isNullOrBlank()) {
            out["HTTPS_PROXY"] = bootstrapProxy
            out["HTTP_PROXY"] = bootstrapProxy
        } else {
            out.remove("HTTPS_PROXY"); out.remove("HTTP_PROXY")
            out.remove("https_proxy"); out.remove("http_proxy")
        }
        return out
    }
}

enum class AetherProtocol(
    val env: String,
    val displayFa: String,
    /**
     * Whether the protocol gets a tab. Both non-MASQUE modes are hidden today:
     *
     *   - gool: its inner tunnel talks to the same Cloudflare edge as the outer one, which
     *     the edge drops, so it never carries traffic.
     *   - wg: the tunnel handshakes and the UI goes green, but the peer never returns any
     *     data; the core's own watchdog gives up on a ~123s cycle
     *     ("wireguard tunnel stale: no valid data from peer; reconnecting") and reconnects
     *     forever. Curiously the wg_prober's handshake + data-plane check passes on the
     *     very same endpoint, so the fault is between the prober and the live tunnel —
     *     unresolved, and not diagnosable from this repo: the shipped libaether.so carries
     *     that watchdog but aether-src/ does not.
     *
     * The engine paths stay in place so either can be re-enabled once fixed upstream.
     * MASQUE is the only mode that actually carries traffic, so it is the only tab; TabRow
     * hides itself entirely while that is true.
     */
    val userVisible: Boolean = true,
) {
    MASQUE("masque", "MASQUE"),
    WG("wg", "WireGuard", userVisible = false),
    // Single-language label: the other two tabs are plain English, and the bilingual form
    // wrapped onto two lines and made the tab row look broken.
    WARP_IN_WARP("gool", "WARP-in-WARP", userVisible = false);
}

enum class AetherScan(val env: String, val displayFa: String) {
    /** One healthy endpoint is enough. Fastest. */
    TURBO("turbo", "توربو — با اولین سرور سالم وصل شو"),

    /** Hunt until 6 healthy endpoints are found, pick the lowest latency. ~2 minutes. */
    BALANCED("balanced", "متعادل — ۶ سرور پیدا کن، بهترین را انتخاب کن"),

    /** Deep scan for the lowest possible ping. Slowest. */
    THOROUGH("thorough", "کامل — کندتر، بهترین پینگ"),

    /** Slow and quiet — emission rate capped. Good for adversarial DPI. */
    STEALTH("stealth", "مخفی — آرام و بی‌سروصدا");
}

enum class AetherIp(val env: String, val displayFa: String) {
    V4("v4", "IPv4"),
    V6("v6", "IPv6"),
    BOTH("both", "هر دو");
}

/**
 * Where the on-device Rust binary lives. We bundle it as an APK asset under assets/aether/<abi>/
 * so a single APK carries all the ABIs we support; the engine picks the right one for the
 * device's primary ABI on first launch and copies it to filesDir so `exec()` can chmod +
 * run it. We can't exec straight from the APK.
 */
object AetherBinaryLocator {
    /**
     * File name the aether executable is packaged under.
     *
     * The binary is NOT shipped in assets/. Since Android 10 (API 29) the platform enforces
     * W^X for apps with targetSdk >= 29: a file written into the app's data directory
     * (filesDir, cacheDir, ...) can never be exec'd, even after chmod 755 — the kernel
     * rejects it with EACCES/"Permission denied". Copying an asset out and exec'ing it,
     * which is what the Windows port did, therefore cannot work here (we target SDK 34).
     *
     * The supported route is to package the executable as if it were a shared library:
     * `jniLibs/<abi>/libaether.so`. The installer extracts it into the app's
     * nativeLibraryDir, which is the one app-owned location that stays executable. It must
     * be named `lib*.so` or the packager drops it from the APK.
     *
     * This also means the per-ABI selection is done by the package installer, not by us —
     * nativeLibraryDir already points at the directory for the device's ABI.
     */
    const val LIB_NAME = "libaether.so"

    /**
     * The on-disk path of the executable: <nativeLibraryDir>/libaether.so.
     *
     * Already mode 0755 and already ABI-correct — nothing to copy, chmod, or choose.
     */
    fun executable(context: android.content.Context): java.io.File =
        java.io.File(context.applicationInfo.nativeLibraryDir, LIB_NAME)
}
