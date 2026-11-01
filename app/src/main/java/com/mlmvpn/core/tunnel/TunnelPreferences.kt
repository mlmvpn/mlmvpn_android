package com.mlmvpn.core.tunnel

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * Typed access to the settings the tunnel core actually reads.
 *
 * [CoreConfig.json] builds the core's config out of a plain SharedPreferences
 * file, looking keys up by string. Anything the UI writes under a different name
 * is silently ignored - the screen shows a setting that changes nothing, which
 * is worse than not offering it. So every key the UI can touch is declared here
 * once, and both sides go through this object.
 *
 * The defaults match [CoreConfig]'s own fallbacks on purpose. If they drifted,
 * a fresh install would connect with different settings than the screen shows.
 */
class TunnelPreferences(context: Context) {

    /**
     * Held because three of these settings are no longer this class's own.
     *
     * DNS and LAN access are app-wide questions answered in Settings, and this object now reads
     * them from there rather than keeping a second copy. `applicationContext` so a screen's
     * context cannot be pinned by a value read after it is gone.
     */
    private val context = context.applicationContext

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // --- what a transport is built on --------------------------------------

    /**
     * Which protocol MASQUE speaks to the gateway.
     *
     * "h3" is QUIC over UDP/443 and is the faster of the two. "h2" is HTTP/2
     * over TCP/443 with TLS fragmentation, which survives networks that block
     * or throttle UDP outright - a real case on some Iranian carriers, where h3
     * never completes a handshake and h2 connects immediately.
     */
    var masqueTransport: String
        get() = read(MASQUE_TRANSPORT, MasqueCarrier.AUTO)
        set(value) = write(MASQUE_TRANSPORT, value)

    /**
     * Whether to reuse the cached gateway list or scan the network fresh.
     *
     * Cache is right almost always: it makes a repeat connect nearly instant.
     * Fresh matters when the carrier has started blocking the cached gateways,
     * where cache would keep retrying addresses that can no longer work.
     */
    var endpointDiscovery: String
        get() = read(ENDPOINT_DISCOVERY, "cache")
        set(value) = write(ENDPOINT_DISCOVERY, value)

    /** How hard the endpoint scan works before settling on a gateway. */
    var scanMode: String
        get() = read(SCAN_MODE, DEFAULT_SCAN_MODE)
        set(value) = write(SCAN_MODE, value)

    /** Which address family to scan: "v4", "v6" or "both". */
    var ipScan: String
        get() = read(IP_SCAN, "v4")
        set(value) = write(IP_SCAN, value)

    /**
     * A single gateway address to use instead of scanning, or blank.
     *
     * Pins one address, so it belongs to the transport it was entered for - a
     * MASQUE gateway is not a WireGuard endpoint. Wrong here means no connection
     * at all rather than a slow one, which is why it stays empty by default.
     */
    var manualEndpoint: String
        get() = read(MANUAL_ENDPOINT, "")
        set(value) = write(MANUAL_ENDPOINT, value)

    // --- how hard it works to look like ordinary traffic --------------------

    /**
     * How much padding and timing noise is mixed into the handshake.
     *
     * More is not better. Heavier profiles cost latency and are themselves a
     * signature; "balanced" is what most networks want, and "off" is faster on
     * a network that is not inspecting anything.
     */
    var obfuscationProfile: String
        get() = read(OBFUSCATION_PROFILE, "balanced")
        set(value) = write(OBFUSCATION_PROFILE, value)

    /** Whether a failed connect retries under the other obfuscation profiles. */
    var retryObfuscationProfiles: Boolean
        get() = prefs.getBoolean(RETRY_OBFUSCATION, true)
        set(value) = prefs.edit().putBoolean(RETRY_OBFUSCATION, value).apply()

    /**
     * Which curve list the TLS hello advertises.
     *
     * "chrome" copies a real Chrome hello, so the handshake does not stand out
     * in a fingerprint check. "compatibility" is a plainer list for middleboxes
     * that cannot parse Chrome's.
     */
    var tlsCurvePreset: String
        get() = read(TLS_CURVE_PRESET, "chrome")
        set(value) = write(TLS_CURVE_PRESET, value)

    /**
     * Whether a WireGuard connect must move real data before it reports success.
     *
     * On by default, and it should stay on: a WireGuard handshake completes on
     * carriers that then drop every data packet, so a handshake alone is not
     * evidence of a working tunnel. Turning this off makes connecting faster and
     * makes "connected" mean less.
     */
    var wireguardDataCheck: Boolean
        get() = prefs.getBoolean(WIREGUARD_DATA_CHECK, true)
        set(value) = prefs.edit().putBoolean(WIREGUARD_DATA_CHECK, value).apply()

    /** Splits the TLS record across packets. Only meaningful on the h2 transport. */
    var h2Fragmentation: Boolean
        get() = read(H2_FRAGMENTATION, "on") == "on"
        set(value) = write(H2_FRAGMENTATION, if (value) "on" else "off")

    // --- Psiphon ------------------------------------------------------------

    /**
     * Which country Psiphon should try to come out in, or "auto".
     *
     * A preference, not a constraint. Psiphon treats its own EgressRegion as a
     * hard filter - set it and every server outside that country disappears from
     * the candidate pool - so pinning it for a whole session would break the one
     * path that works on the worst domestic operators. The service uses it for a
     * short attempt in front of the ladder and then drops it.
     */
    var egressRegion: String
        get() = read(EGRESS_REGION, "auto")
        set(value) = write(EGRESS_REGION, value)

    /**
     * Which transport carries the outer leg when Psiphon runs inside WARP,
     * or "auto" to walk the whole ladder.
     */
    var chainOuterMode: String
        get() = read(CHAIN_OUTER_MODE, "direct")
        set(value) = write(CHAIN_OUTER_MODE, value)

    /**
     * Whether the local proxies bind to 0.0.0.0 instead of loopback.
     *
     * Now the APP-wide setting rather than a copy of it. This used to be a second switch, in a
     * second preferences file, answering exactly the same question as Settings > Allow LAN --
     * so the same phone was reachable or not depending on which of the two screens the user had
     * last opened, and neither screen mentioned the other.
     *
     * See [com.mlmvpn.scanner.utils.NetworkSettings.allowLan] for what turning it on exposes.
     */
    var lanSharing: Boolean
        get() = com.mlmvpn.scanner.utils.NetworkSettings.allowLan(context)
        set(value) = com.mlmvpn.scanner.utils.NetworkSettings.setAllowLan(context, value)

    // --- applies to every transport ------------------------------------------

    /**
     * Whether losing the tunnel should cut the network rather than fall back.
     *
     * Off by default because it is a trade the user has to choose: on, a dropped
     * tunnel leaves the device with no working network at all until it comes
     * back, which is the point - traffic must never silently leave unprotected -
     * but it looks identical to a broken phone if you did not expect it.
     */
    var killSwitch: Boolean
        get() = prefs.getBoolean(KILL_SWITCH, false)
        set(value) = prefs.edit().putBoolean(KILL_SWITCH, value).apply()

    /** Whether a tunnel that drops on its own is retried without asking. */
    var autoReconnect: Boolean
        get() = prefs.getBoolean(AUTO_RECONNECT, true)
        set(value) = prefs.edit().putBoolean(AUTO_RECONNECT, value).apply()

    // --- Tor ----------------------------------------------------------------

    /**
     * How to reach the Tor network: directly, or through which kind of bridge.
     *
     * "auto" walks the ladder until one connects, which is right for a user who
     * does not already know what their network blocks. Pinning one skips the
     * search, and is what a user who does know should do.
     */
    var torMode: String
        get() = read(TOR_MODE, "auto")
        set(value) = write(TOR_MODE, value)

    /** Which country to leave the Tor network from, or "auto". */
    var torExitRegion: String
        get() = read(TOR_EXIT_REGION, "auto")
        set(value) = write(TOR_EXIT_REGION, value)

    /**
     * Whether Tor may run inside a WARP tunnel when it cannot bootstrap alone.
     *
     * Separate from the Psiphon chain setting on purpose: the two inner tunnels
     * have genuinely different needs from an outer leg, so one shared switch
     * would be wrong in both directions.
     */
    var torChainArmed: Boolean
        get() = prefs.getBoolean(TOR_CHAIN_ARMED, true)
        set(value) = prefs.edit().putBoolean(TOR_CHAIN_ARMED, value).apply()

    /**
     * Tor's own pin for the outer leg, separate from [chainOuterMode].
     *
     * [CoreConfig.chainOuterCandidates] has always read this key, and nothing ever wrote it --
     * so Tor's outer leg silently walked the whole ladder however the Psiphon row was set, and
     * the Psiphon row appeared to control something it did not. The two inner tunnels want
     * genuinely different things from an outer leg (Tor only needs a reachable SOCKS proxy;
     * Psiphon runs its own protocol ladder inside and is far more sensitive to its latency),
     * which is exactly why the core kept them apart.
     */
    var chainOuterModeTor: String
        get() = read(CoreConfig.CHAIN_OUTER_MODE_TOR_PREF, "auto")
        set(value) = write(CoreConfig.CHAIN_OUTER_MODE_TOR_PREF, value)

    // --- the rest of what the core reads -------------------------------------
    //
    // Everything below is a field [CoreConfig.json] already sends on every connect, for which
    // there was no control anywhere in the app. They were therefore fixed at their fallbacks
    // for every user forever. None of them is exotic: a resolver that is not Cloudflare's, a
    // hostname that must stay outside the tunnel, and the log level you need the moment a
    // connection fails are all ordinary asks on a censored line.

    /**
     * How much the core writes to its log.
     *
     * `info` is the shipping default and the right one: `debug` and `trace` are worth turning
     * on to diagnose a failing connect and are not worth leaving on, because the prober emits
     * one line per candidate and a full scan is thousands of them.
     */
    var logLevel: String
        get() = read(LOG_LEVEL, "info")
        set(value) = write(LOG_LEVEL, value)

    /**
     * How much concurrency the core gives itself, or "auto" to size it from the device.
     *
     * Auto reads CPU count and RAM and picks a tier, which is right almost always. The reason
     * to override is a phone whose thermal behaviour the tier does not predict: `low` on a
     * device that throttles under a wide scan, `high` on one that auto-detects as medium
     * because it reports few cores.
     */
    var perfProfile: String
        get() = read(PERF_PROFILE, "auto")
        set(value) = write(PERF_PROFILE, value)

    /**
     * Resolvers used INSIDE the tunnel, comma separated, or blank for the core's own.
     *
     * Worth having because the resolver decides what a name resolves to, and inside a tunnel
     * that is a routing decision as much as a privacy one: pointing this at a resolver in the
     * exit country is what stops a CDN answering with a node next to the gateway rather than
     * next to the exit.
     */
    var dnsServers: String
        get() = com.mlmvpn.scanner.utils.NetworkSettings.backendDns(context)
        set(value) = com.mlmvpn.scanner.utils.NetworkSettings.setBackendDns(context, value)

    /**
     * Hosts and ranges that must NOT go through the tunnel, comma separated.
     *
     * The split-tunnel list next door works per app; this works per destination, which is the
     * only one of the two that can keep a domestic bank or a national ID service reachable
     * while the browser that also needs it stays tunnelled.
     */
    var routeDirect: String
        get() = read(ROUTE_DIRECT, "")
        set(value) = write(ROUTE_DIRECT, value)

    /** Hosts and ranges dropped outright, comma separated. A blocklist, not a bypass. */
    var routeBlock: String
        get() = read(ROUTE_BLOCK, "")
        set(value) = write(ROUTE_BLOCK, value)

    // --- manual traffic shaping ----------------------------------------------
    //
    // The named profiles (off/light/balanced/aggressive) are four points in a space the core
    // will accept any point in. These five fields are that space, and the core validates them
    // itself -- an inconsistent pair (jmin above jmax) is refused rather than silently
    // reinterpreted. Left blank, the named profile decides, which is what every existing
    // install gets.

    /** How many junk packets precede the handshake. Blank = use the named profile's value. */
    var obfuscationJc: String
        get() = read(OBFUSCATION_JC, "")
        set(value) = write(OBFUSCATION_JC, value)

    /** Smallest junk packet, in bytes. */
    var obfuscationJmin: String
        get() = read(OBFUSCATION_JMIN, "")
        set(value) = write(OBFUSCATION_JMIN, value)

    /** Largest junk packet, in bytes. Must be above [obfuscationJmin]. */
    var obfuscationJmax: String
        get() = read(OBFUSCATION_JMAX, "")
        set(value) = write(OBFUSCATION_JMAX, value)

    /** A literal prefix prepended to the first packet, as a hex or ASCII signature. */
    var obfuscationI1: String
        get() = read(OBFUSCATION_I1, "")
        set(value) = write(OBFUSCATION_I1, value)

    /** A second such prefix, for the packet after it. */
    var obfuscationI2: String
        get() = read(OBFUSCATION_I2, "")
        set(value) = write(OBFUSCATION_I2, value)

    // --- Cloudflare Zero Trust ----------------------------------------------
    //
    // A WARP tunnel can enrol against an organisation's own Zero Trust team rather than
    // against consumer WARP, which is what makes the exit an address the organisation
    // controls. The core has always accepted these; the credentials live in [SecureStore]
    // (AndroidKeyStore-wrapped) rather than in plain preferences, because a service token is a
    // credential and the rest of this file is not.

    /** Whether Gateway policy enforcement is requested for this team. */
    var zeroTrustGateway: Boolean
        get() = prefs.getBoolean(ZERO_TRUST_GATEWAY, false)
        set(value) = prefs.edit().putBoolean(ZERO_TRUST_GATEWAY, value).apply()

    private fun read(key: String, fallback: String) =
        prefs.getString(key, fallback)?.trim().orEmpty().ifBlank { fallback }

    private fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    companion object {
        // The exact strings CoreConfig looks up. Changing one here without
        // changing it there breaks the setting silently.
        const val MASQUE_TRANSPORT = "default_masque_transport"
        const val ENDPOINT_DISCOVERY = "endpoint_discovery"
        const val SCAN_MODE = "default_scan_mode"
        /** «توربو»: see CoreConfig.json for the measurement behind it. */
        const val DEFAULT_SCAN_MODE = "turbo"
        const val IP_SCAN = "default_scan"
        const val MANUAL_ENDPOINT = "manual_endpoint"
        const val OBFUSCATION_PROFILE = "obfuscation_profile"
        const val RETRY_OBFUSCATION = "retry_obfuscation_profiles"
        const val TLS_CURVE_PRESET = "tls_curve_preset"
        const val H2_FRAGMENTATION = "h2_fragmentation"
        const val WIREGUARD_DATA_CHECK = "wireguard_data_check"
        const val EGRESS_REGION = "psiphon_egress_region"
        const val CHAIN_OUTER_MODE = "chain_outer_mode"
        const val LAN_SHARING = "psiphon_lan_sharing"
        const val KILL_SWITCH = "kill_switch"
        const val AUTO_RECONNECT = "auto_reconnect"
        const val TOR_MODE = "tor_mode"
        const val TOR_EXIT_REGION = "tor_exit_region"
        const val TOR_CHAIN_ARMED = "tor_chain_armed"

        // Read by CoreConfig.json and, until now, written by nothing.
        const val LOG_LEVEL = "log_level"
        const val PERF_PROFILE = "perf_profile"
        const val DNS_SERVERS = "dns_servers"
        const val ROUTE_DIRECT = "route_direct"
        const val ROUTE_BLOCK = "route_block"
        const val OBFUSCATION_JC = "obfuscation_jc"
        const val OBFUSCATION_JMIN = "obfuscation_jmin"
        const val OBFUSCATION_JMAX = "obfuscation_jmax"
        const val OBFUSCATION_I1 = "obfuscation_i1"
        const val OBFUSCATION_I2 = "obfuscation_i2"
        const val ZERO_TRUST_GATEWAY = "zero_trust_gateway"

        /** SecureStore keys for the Zero Trust credentials, in the order the page shows them. */
        const val ZERO_TRUST_TEAM = "zero_trust_team"
        const val ZERO_TRUST_CLIENT_ID = "zero_trust_client_id"
        const val ZERO_TRUST_CLIENT_SECRET = "zero_trust_client_secret"
        const val ZERO_TRUST_TOKEN = "zero_trust_token"
        const val ZERO_TRUST_EMAIL = "zero_trust_email"
    }
}
