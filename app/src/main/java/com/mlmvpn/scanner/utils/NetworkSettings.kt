package com.mlmvpn.scanner.utils

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * The four network choices that belong to the APP, not to any one engine.
 *
 * The app carries two VPN stacks and half a dozen engines on top of them, and four questions
 * apply to all of them: which resolver, which local port, tunnel-everything or proxy-only, and
 * may other devices on the network use the proxy. Those are properties of how the user wants
 * their phone to behave, not of which tunnel happens to be carrying the traffic.
 *
 * They were nonetheless answered twice. Settings held `backend_dns`, `local_port`, `proxy_mode`
 * and `allow_lan`; the five-transport screen held its own `dns_servers`, its own
 * `psiphon_lan_sharing` and a hardcoded SOCKS port of 1819; and the split-tunnel list existed in
 * two stores under two sets of mode names. So the same switch read differently depending on which
 * screen the user had last opened, and a port typed in Settings did nothing at all once a
 * transport was the thing running -- which presents as "the proxy I configured on my PC stopped
 * working when I changed tunnels", with nothing on screen to explain it.
 *
 * This object is now the only place any of them is read or written. The per-engine copies are
 * gone; [com.mlmvpn.core.tunnel.CoreConfig] and [com.mlmvpn.core.tunnel.TunnelPreferences] read
 * through here.
 *
 * **Storage is deliberately unchanged.** The keys and preference files are the ones the app has
 * always used, because changing them would silently reset every existing user's configuration.
 * What changed is that there is now exactly one reader per key.
 */
object NetworkSettings {

    // --- where each value lives ---------------------------------------------------------------
    //
    // Three different preference files, which is not a design so much as a history. Kept as-is on
    // purpose: a migration here buys tidiness and costs every existing user their settings.

    /** `backend_dns` has always lived here. */
    private const val APP_PREFS = "app_settings"

    /** What the transports' own LAN flag used to live in; still read for the migration below. */
    private const val LEGACY_TUNNEL_PREFS = "settings"
    private const val LEGACY_LAN_KEY = "psiphon_lan_sharing"

    const val KEY_BACKEND_DNS = "backend_dns"
    const val KEY_PROXY_MODE = "proxy_mode"
    const val KEY_ALLOW_LAN = "allow_lan"

    const val DEFAULT_DNS = "1.1.1.1"

    /**
     * LAN access is ON by default, at the user's explicit request.
     *
     * What that means, stated plainly because it is a real exposure and not a theoretical one:
     * the local proxy binds to 0.0.0.0 instead of loopback, so every device on the same network
     * can use this phone's tunnel, and neither listener has any authentication. On a personal
     * hotspot that is only the devices the user tethered. On a café or airport Wi-Fi it is
     * everyone on that Wi-Fi.
     *
     * The setting's own page says exactly this, in those terms, rather than burying it -- a
     * default the user cannot see is a default they cannot disagree with.
     */
    const val DEFAULT_ALLOW_LAN = true

    private fun appPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)

    private fun defaultPrefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    // --- DNS ----------------------------------------------------------------------------------

    /**
     * The resolver every engine uses, as typed. May be a comma-separated list.
     *
     * One field rather than two, even though the two stacks consume it differently: the Xray
     * family puts the first entry on the Android tun interface, and the transports hand the whole
     * list to their core as the resolvers to use inside the tunnel. Both answer the same user
     * question -- "who resolves my names" -- and having two fields meant a user who set one and
     * not the other got a different answer depending on which tunnel came up.
     */
    fun backendDns(context: Context): String =
        appPrefs(context).getString(KEY_BACKEND_DNS, DEFAULT_DNS)?.trim()?.ifBlank { null }
            ?: DEFAULT_DNS

    fun setBackendDns(context: Context, value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return
        appPrefs(context).edit().putString(KEY_BACKEND_DNS, clean).apply()
    }

    /** The first resolver only, for the places that can carry exactly one (the tun interface). */
    fun primaryDns(context: Context): String =
        backendDns(context).split(',').firstOrNull()?.trim()?.ifBlank { null } ?: DEFAULT_DNS

    /** Every resolver, for the places that take a list. */
    fun dnsList(context: Context): List<String> =
        backendDns(context).split(',').map { it.trim() }.filter { it.isNotEmpty() }

    // --- ports --------------------------------------------------------------------------------
    //
    // Every engine now listens on the port the user set, because the two stacks are mutually
    // exclusive at runtime (see TunnelExclusion) and can therefore share one. That is the whole
    // point: a proxy configured once on a PC or a browser keeps working whichever tunnel the
    // phone is running, instead of moving to 1819 the moment a transport was chosen.

    /** The mixed SOCKS/HTTP inbound. What the user types in Settings. */
    fun localPort(context: Context): Int = LocalPort.get(context)

    /**
     * The second SOCKS listener, for the one mode that needs both at once.
     *
     * Psiphon-over-WARP runs the Rust core and Psiphon simultaneously; Psiphon keeps the main
     * port, so the core moves aside by one. Every other mode never binds this.
     */
    fun chainSocksPort(context: Context): Int = localPort(context) + 1

    /**
     * The HTTP proxy Psiphon publishes alongside its SOCKS listener.
     *
     * Separate because the two are not interchangeable to a client: Windows takes an HTTP proxy
     * system-wide from Internet Options while SOCKS has to be set per application. The Xray family
     * needs no equivalent -- its inbound is `mixed`, so it already speaks both on one port.
     */
    fun httpProxyPort(context: Context): Int = localPort(context) + 2

    // --- proxy-only mode ----------------------------------------------------------------------

    /** True when the app publishes a local proxy instead of taking the whole device's traffic. */
    fun proxyMode(context: Context): Boolean =
        defaultPrefs(context).getBoolean(KEY_PROXY_MODE, false)

    fun setProxyMode(context: Context, value: Boolean) {
        defaultPrefs(context).edit().putBoolean(KEY_PROXY_MODE, value).apply()
        // Turning it ON re-arms the warning. Acknowledging once should not silence it forever:
        // the switch is easy to leave on by accident months later, and the failure it produces
        // -- the app says connected while the browser is not tunnelled at all -- is
        // indistinguishable from the app being broken.
        if (value) setProxyModeAcknowledged(context, false)
    }

    private const val KEY_PROXY_MODE_ACK = "proxy_mode_acknowledged"

    /** True once the user has been told what Proxy Mode does and chosen to keep it. */
    fun proxyModeAcknowledged(context: Context): Boolean =
        defaultPrefs(context).getBoolean(KEY_PROXY_MODE_ACK, false)

    fun setProxyModeAcknowledged(context: Context, value: Boolean) {
        defaultPrefs(context).edit().putBoolean(KEY_PROXY_MODE_ACK, value).apply()
    }

    /** Whether the Proxy Mode warning still has to be shown. */
    fun proxyModeNeedsWarning(context: Context): Boolean =
        proxyMode(context) && !proxyModeAcknowledged(context)

    // --- LAN ----------------------------------------------------------------------------------

    /**
     * Whether the local proxy binds to 0.0.0.0 rather than loopback.
     *
     * Reads the app-wide key, and migrates a user who had turned the transports' own LAN switch ON
     * before the two were merged -- otherwise unifying them would silently take away a setting
     * they had deliberately enabled.
     */
    fun allowLan(context: Context): Boolean {
        val prefs = defaultPrefs(context)
        if (!prefs.contains(KEY_ALLOW_LAN)) {
            val legacy = context.applicationContext
                .getSharedPreferences(LEGACY_TUNNEL_PREFS, Context.MODE_PRIVATE)
                .getBoolean(LEGACY_LAN_KEY, false)
            if (legacy) {
                prefs.edit().putBoolean(KEY_ALLOW_LAN, true).apply()
                return true
            }
        }
        return prefs.getBoolean(KEY_ALLOW_LAN, DEFAULT_ALLOW_LAN)
    }

    fun setAllowLan(context: Context, value: Boolean) {
        defaultPrefs(context).edit().putBoolean(KEY_ALLOW_LAN, value).apply()
    }

    // --- the four optional protections ---------------------------------------------------------
    //
    // All four are OFF by default and stay off unless the user turns them on. That is a decision,
    // not an oversight: [DEFAULT_ALLOW_LAN] has been true for a long time, and tightening the
    // default would cut off every existing user whose laptop connects over their home Wi-Fi, the
    // next time they updated, with nothing on screen to explain it. The Local Network screen warns
    // instead, and these are what the warning offers.

    /**
     * Send the TLS handshake without a browser fingerprint, with a TLS 1.2 cipher list.
     *
     * Off by default and deliberately so: the ordinary handshake is the one that looks like every
     * other user's, and this one does not. It exists for the case where that is the problem --
     * Cloudflare reachable but every config through it dead.
     */
    // --- MTU ----------------------------------------------------------------------------------

    /**
     * `vpn_mtu` has always lived here, written by Settings > Advanced.
     *
     * A fourth preference file, for the same reason as the other three: moving the key would
     * silently reset the value for every user who had already set one.
     */
    private const val ROUTING_PREFS = "vpn_routing_prefs"

    const val KEY_MTU = "vpn_mtu"

    /**
     * The bounds an MTU may take on an Android VPN interface.
     *
     * 1500 is the ceiling because that is the Ethernet payload every path is measured against;
     * asking for more produces an interface the platform will not carry. 576 is the floor: it is
     * the smallest datagram IPv4 guarantees every host can reassemble, and below it ordinary
     * traffic starts failing in ways that look nothing like an MTU problem. A value outside the
     * range is clamped rather than refused -- the setting exists to be experimented with, and
     * silently doing nothing would be the worst of the three options.
     */
    const val MIN_MTU = 576
    const val MAX_MTU = 1500

    /**
     * The nine ways this app can carry traffic, and the MTU each one wants.
     *
     * One number for all of them was the wrong shape. The methods do not share a path: MASQUE
     * wraps every inner packet in QUIC and loses ~200 bytes to it, WireGuard loses ~60, and the
     * tun2socks methods (Psiphon, Tor) lose nothing at all -- lwIP terminates TCP on the device
     * and re-opens it through a SOCKS proxy, so their tun MTU is a local buffer size that never
     * has to survive the network. A single value that is safe for the first is wasteful for the
     * last, and one that is efficient for the last black-holes the first.
     *
     * [default] is the value measured to work best on a real line, not a guess. See the release
     * notes for 1.2.32 for the measurements behind each one.
     */
    enum class Method(val id: String, val default: Int) {
        // Measured, on a line whose path MTU is a full 1500. Each is the largest inner packet the
        // method actually delivered -- one byte more and the packet was dropped rather than split.
        // WARP-on-WARP is the one that was WRONG before: its old default of 1280 was 60 bytes
        // above what it can carry, so every full-size packet died and the tunnel read as
        // "connects, then large pages hang".
        MASQUE("masque", 1304),
        WIREGUARD("wireguard", 1440),
        WARP_ON_WARP("warp_on_warp", 1220),
        // Nothing to measure: tun2socks terminates TCP on the device and re-opens it through a
        // local SOCKS proxy, so the tun MTU is a buffer size and never reaches the network.
        // Bigger is strictly better. See [MtuProbe.LOCAL_TERMINATION].
        PSIPHON("psiphon", 1500),
        TOR("tor", 1500),
        // «وارپ» reaches its engine through tun2proxy, which terminates TCP on the phone: the tun
        // MTU is a local buffer like Psiphon's and Tor's. The WireGuard packet size is the
        // engine's own business.
        CFWARP("warp", 1500),
        QUICK_CONNECT("quick_connect", 1500),
        // SoftEther carries Ethernet frames inside TLS inside TCP: 1500 less 20 IPv4, 20 TCP,
        // 57 TLS record, 12 SoftEther and 14 Ethernet. It was 1350, rounded down by hand before
        // anything measured the line.
        GATEWAY("gateway", 1377),
        SNI("sni", 1500),
        V2RAY("v2ray", 1500),
        // The rest each had no field of their own and took V2Ray's (or Quick Connect's) without
        // the user being able to tell. Their defaults are the values they had: nothing changes
        // until a field is filled in.
        AMNEZIA("amnezia", 1500),
        PROTON("proton", 1500),
        WINDSCRIBE("windscribe", 1500),
        OUTLINE("outline", 1500),
        FLUX("flux", 1500),
        FENIX("fenix", 1500),
        MAE("mae", 1500),
        GITHUB("github", 1500),
        GST("gst", 1500),
        ANTI_SANCTION("anti_sanction", 1500),
        // OpenVPN (its tile and Windscribe's OpenVPN family): the server's pushed value unless set.
        OPENVPN("openvpn", 1500),
        // Geph's own tun: a large local buffer by design (GephEngine.VPN_MTU).
        GEPH("geph", 16384),
        // «لنترن» is reached through tun2proxy, which terminates TCP on the phone: a local buffer,
        // like «وارپ»'s. The packet sizes on the wire are the core's own business.
        LANTERN("lantern", 1500),
        // «URnetwork»: its core carries the TUN's packets inside URnetwork's own frames (the SDK's
        // packet path). 1280 is the interface size URnetwork's own apps configure
        // (connect.DefaultTunnelMtu): the least IPv6 takes, and what the provider side is sized for.
        URNETWORK("urnetwork", 1280),
        // «NPV»: its standard links run on the Xray stack, like Outline's keys.
        NPV("npv", 1500);

        /** Where this method's own value lives. */
        val key: String get() = "${KEY_MTU}_$id"

        companion object {
            fun byId(id: String?): Method? = entries.firstOrNull { it.id == id }
        }
    }

    /**
     * The MTU in force for one method.
     *
     * Three layers, most specific first: this method's own field, then the app-wide field that
     * predates it, then the method's default. The middle layer is what keeps the upgrade honest
     * -- a user who had already set the single MTU field keeps getting it everywhere until they
     * set a per-method value, rather than silently reverting to defaults on update.
     */
    fun mtu(context: Context, method: Method): Int =
        methodMtu(context, method).takeIf { it > 0 }
            ?: globalMtu(context).takeIf { it > 0 }
            ?: method.default

    /** What this method's own field holds, or 0 when it is on automatic. */
    fun methodMtu(context: Context, method: Method): Int =
        routingPrefs(context).getInt(method.key, 0).let { if (it <= 0) 0 else it.coerceIn(MIN_MTU, MAX_MTU) }

    fun setMethodMtu(context: Context, method: Method, value: Int) {
        routingPrefs(context).edit().putInt(method.key, value.coerceIn(MIN_MTU, MAX_MTU)).apply()
    }

    /** Back to this method's default. */
    fun clearMethodMtu(context: Context, method: Method) {
        routingPrefs(context).edit().remove(method.key).apply()
    }

    /**
     * The app-wide MTU field, or 0 when it has never been set.
     *
     * Kept because it shipped, and because it is still the quickest way to move everything at
     * once. A per-method value overrides it.
     */
    fun globalMtu(context: Context): Int =
        routingPrefs(context).getInt(KEY_MTU, 0).let { if (it <= 0) 0 else it.coerceIn(MIN_MTU, MAX_MTU) }

    fun setGlobalMtu(context: Context, value: Int) {
        routingPrefs(context).edit().putInt(KEY_MTU, value.coerceIn(MIN_MTU, MAX_MTU)).apply()
    }

    fun clearGlobalMtu(context: Context) {
        routingPrefs(context).edit().remove(KEY_MTU).apply()
    }

    // --- the pre-per-method API, kept for the call sites that have no method to name ---------

    /** @deprecated superseded by [mtu] with a [Method]; still used where the method is unknown. */
    fun mtu(context: Context): Int = globalMtu(context)

    fun mtuOr(context: Context, fallback: Int): Int = globalMtu(context).takeIf { it > 0 } ?: fallback

    fun setMtu(context: Context, value: Int) = setGlobalMtu(context, value)

    fun clearMtu(context: Context) = clearGlobalMtu(context)

    private fun routingPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(ROUTING_PREFS, Context.MODE_PRIVATE)

    /**
     * The «رفع فیلتر کانفیگ‌ها (کلادفلر)» switch: the fragment + fingerprint handshake of
     * [com.mlmvpn.scanner.utils.CfUnfilter] for Cloudflare-fronted configs. ON by default.
     *
     * The key is new on purpose. The switch used to be stored as `tls_unfilter` while it changed
     * nothing at all (see CfUnfilter), so what anyone left it at says nothing about what they want
     * now; reading the old key would have kept the fix off for exactly the people who toggled it
     * while looking for one. Everyone starts from the default, and a choice made from here on sticks.
     */
    const val KEY_TLS_UNFILTER = "tls_unfilter_v2"

    fun tlsUnfilter(context: Context): Boolean =
        defaultPrefs(context).getBoolean(KEY_TLS_UNFILTER, true)
            .also { com.mlmvpn.scanner.utils.XrayJsonGenerator.tlsUnfilter = it }

    fun setTlsUnfilter(context: Context, value: Boolean) {
        defaultPrefs(context).edit().putBoolean(KEY_TLS_UNFILTER, value).apply()
        com.mlmvpn.scanner.utils.XrayJsonGenerator.tlsUnfilter = value
    }

    const val KEY_GOOGLE_FIX = "google_fix"

    /**
     * The Gemini / Google apps switch: whether Google is reached through a Cloudflare worker by
     * IPv4 address, and QUIC refused at once rather than silently dropped. On by default -- see
     * XrayJsonGenerator.googleFix for both halves. Takes effect on the next connect, like
     * tlsUnfilter.
     */
    fun googleFix(context: Context): Boolean =
        defaultPrefs(context).getBoolean(KEY_GOOGLE_FIX, true)
            .also { com.mlmvpn.scanner.utils.XrayJsonGenerator.googleFix = it }

    fun setGoogleFix(context: Context, value: Boolean) {
        defaultPrefs(context).edit().putBoolean(KEY_GOOGLE_FIX, value).apply()
        com.mlmvpn.scanner.utils.XrayJsonGenerator.googleFix = value
    }

    const val KEY_GEMINI_EXIT = "gemini_exit"

    /**
     * The Gemini exit set up on the user's Cloudflare account, or null. Kept here rather than read
     * from the account list at connect time: that list loads asynchronously, and a connect racing
     * it would quietly leave Gemini on the old path.
     */
    fun geminiExit(context: Context): XrayJsonGenerator.GeminiExitRoute? =
        XrayJsonGenerator.GeminiExitRoute.decode(defaultPrefs(context).getString(KEY_GEMINI_EXIT, null))
            .also { XrayJsonGenerator.geminiExit = it }

    fun setGeminiExit(context: Context, route: XrayJsonGenerator.GeminiExitRoute?) {
        defaultPrefs(context).edit().apply {
            if (route == null) remove(KEY_GEMINI_EXIT) else putString(KEY_GEMINI_EXIT, route.encode())
        }.apply()
        XrayJsonGenerator.geminiExit = route
    }

    const val KEY_LAN_PASSWORD = "lan_password"
    const val KEY_LAN_OWN_ONLY = "lan_own_network_only"
    const val KEY_LAN_BLOCKED = "lan_blocked_clients"
    const val KEY_LAN_UNTIL = "lan_share_until"

    /**
     * The proxy password, or null when sharing is open.
     *
     * Xray's `mixed` inbound authenticates SOCKS5 and HTTP from one `accounts` list, so this one
     * value covers both. Psiphon and Tor have no equivalent, which is why the switch is disabled
     * rather than silently ineffective on those engines.
     */
    fun lanPassword(context: Context): String? =
        appPrefs(context).getString(KEY_LAN_PASSWORD, null)?.trim()?.ifBlank { null }

    fun setLanPassword(context: Context, value: String?) {
        val clean = value?.trim().orEmpty()
        appPrefs(context).edit().apply {
            if (clean.isEmpty()) remove(KEY_LAN_PASSWORD) else putString(KEY_LAN_PASSWORD, clean)
        }.apply()
    }

    /** The username that goes with [lanPassword]. Fixed, because two secrets is one too many. */
    const val LAN_USER = "mlm"

    /** Share only when the phone is the hotspot or the tether, never on someone else's Wi-Fi. */
    fun lanOwnNetworkOnly(context: Context): Boolean =
        defaultPrefs(context).getBoolean(KEY_LAN_OWN_ONLY, false)

    fun setLanOwnNetworkOnly(context: Context, value: Boolean) {
        defaultPrefs(context).edit().putBoolean(KEY_LAN_OWN_ONLY, value).apply()
    }

    /** Client addresses the user has blocked. Routed to the blackhole outbound. */
    fun lanBlocked(context: Context): Set<String> =
        appPrefs(context).getStringSet(KEY_LAN_BLOCKED, emptySet()).orEmpty()

    fun setLanBlocked(context: Context, value: Set<String>) {
        // A copy, not the live set: SharedPreferences hands back its own instance and mutating it
        // corrupts the cached value without ever writing to disk.
        appPrefs(context).edit().putStringSet(KEY_LAN_BLOCKED, value.toSet()).apply()
    }

    fun toggleLanBlocked(context: Context, address: String) {
        val current = lanBlocked(context).toMutableSet()
        if (!current.remove(address)) current.add(address)
        setLanBlocked(context, current)
    }

    /**
     * Epoch millis after which sharing switches itself off, or 0 for no limit.
     *
     * Stored as a deadline rather than a duration so it survives the app being killed: a
     * countdown held in memory would silently restart, and "share for 30 minutes" would become
     * "share until you next reboot".
     */
    fun lanShareUntil(context: Context): Long =
        appPrefs(context).getLong(KEY_LAN_UNTIL, 0L)

    fun setLanShareUntil(context: Context, epochMillis: Long) {
        appPrefs(context).edit().putLong(KEY_LAN_UNTIL, epochMillis).apply()
    }

    /** True when a timer was set and has run out. */
    fun lanShareExpired(context: Context): Boolean {
        val until = lanShareUntil(context)
        return until > 0L && System.currentTimeMillis() >= until
    }

    /**
     * Whether the proxy should actually bind every interface right now.
     *
     * [onOwnNetwork] is supplied by the caller (see [com.mlmvpn.scanner.lan.LanShare]) so this
     * object keeps no opinion about interface names. Everything that decides a bind goes through
     * here rather than reading [allowLan] directly, or the switch and the three protections would
     * disagree depending on which engine asked.
     */
    fun lanSharingActive(context: Context, onOwnNetwork: Boolean): Boolean {
        if (!allowLan(context)) return false
        if (lanShareExpired(context)) return false
        if (lanOwnNetworkOnly(context) && !onOwnNetwork) return false
        return true
    }
}
