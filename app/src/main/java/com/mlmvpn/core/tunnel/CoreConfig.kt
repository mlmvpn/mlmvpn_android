package com.mlmvpn.core.tunnel

import android.content.Context
import org.json.JSONObject
import java.io.File

object CoreConfig {
    /**
     * The SOCKS port this stack listens on: the app's own Local Port setting.
     *
     * Was hardcoded to 1819 on the reasoning that in VPN mode nothing user-facing
     * binds it. True for the core itself, but not for Psiphon, whose Go controller
     * publishes a real SOCKS listener here — and not for the user, who had set a
     * Local Port in Settings and found the proxy they had configured on their PC
     * stop working the moment a transport was the thing running, with nothing on
     * screen to say why.
     *
     * Sharing one port with the Xray family is safe because the two stacks are
     * mutually exclusive at runtime: only one VpnService may hold the device tun,
     * which is exactly what TunnelExclusion enforces. So whichever engine is up,
     * the proxy is where the user said it would be.
     */
    fun socksPort(context: Context): Int =
        com.mlmvpn.scanner.utils.NetworkSettings.localPort(context)

    /**
     * Where the Rust core publishes its SOCKS5 listener in Psiphon-over-WARP mode.
     *
     * Must differ from [socksPort]: in that mode both listeners exist at once —
     * the core's (WARP, the outer leg) and Psiphon's (the inner leg, which
     * tun2socks dials). Reusing one port would make the second bind fail and the
     * chain would silently collapse to whichever came up first.
     */
    fun chainSocksPort(context: Context): Int =
        com.mlmvpn.scanner.utils.NetworkSettings.chainSocksPort(context)

    /**
     * Which outer transport last carried the chain on this device.
     *
     * An index into [CHAIN_OUTER_LADDER]. The next connect starts there instead of
     * walking the whole ladder again, so a SIM that needs WireGuard pays the MASQUE
     * timeout only once, ever.
     */
    const val CHAIN_OUTER_PREF = "chain_outer_index"

    /**
     * Which transport last carried a PLAIN (unchained) tunnel far enough to move
     * real bytes, as a `CHAIN_OUTER_LADDER` entry — or absent if none ever has.
     *
     * Separate key from [CHAIN_OUTER_PREF] because they are different measurements
     * and must not overwrite each other:
     *
     *  - [CHAIN_OUTER_PREF] = "this transport carried Psiphon **inside** it". Direct
     *    evidence about the chain.
     *  - this key = "this transport reached the internet on this carrier **on its
     *    own**". Weaker evidence for the chain — carrying Psiphon is a harder job
     *    than carrying ordinary traffic — but far better than the static ladder
     *    order when the chain has no history yet.
     *
     * So the chain's own memory always wins; this is only consulted when it is
     * absent. See [TunnelVpnService.raiseOuterLeg].
     *
     * Written only after the byte threshold in
     * [TunnelVpnService.recordWorkingPlainTransport] — a handshake is not
     * evidence, which is the whole lesson of the fake-connected bugs.
     */
    const val PLAIN_WORKING_TRANSPORT_PREF = "plain_working_transport"


    fun json(context: Context, protocol: String? = null): String =
        json(context, protocol, listenOverride = null)

    /**
     * @param listenOverride binds the core's SOCKS listener somewhere other than
     *   [SOCKS_PORT]. Only the outer leg of Psiphon-over-WARP uses this: Psiphon
     *   owns [SOCKS_PORT] in that mode, so the core has to move aside.
     */
    fun json(context: Context, protocol: String?, listenOverride: Int?): String =
        json(context, protocol, listenOverride, migratePreferences = true)

    fun json(context: Context, protocol: String?, listenOverride: Int?, migratePreferences: Boolean): String {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        // Once: the MASQUE transport moves to «خودکار». A fixed h2 left over from a test meant the
        // core never tried HTTP/3 or its alternate ports (device, 2026-10-04: h2 only, every
        // gateway timed out, while WireGuard worked on UDP 1701 in the same minute).
        if (migratePreferences && !prefs.getBoolean("masque_transport_auto_v1", false)) {
            prefs.edit().putString("default_masque_transport", MasqueCarrier.AUTO)
                .putBoolean("masque_transport_auto_v1", true).apply()
        }
        fun text(key: String, fallback: String = "") =
            prefs.getString(key, fallback)?.trim().orEmpty()
        val manualObfuscation = JSONObject().apply {
            text("obfuscation_jc").toIntOrNull()?.let { put("jc", it) }
            text("obfuscation_jmin").toIntOrNull()?.let { put("jmin", it) }
            text("obfuscation_jmax").toIntOrNull()?.let { put("jmax", it) }
            putOpt("i1", text("obfuscation_i1").ifBlank { null })
            putOpt("i2", text("obfuscation_i2").ifBlank { null })
        }

        return JSONObject().apply {
            put("config_path", File(context.filesDir, "tunnel.toml").absolutePath)
            val selected = protocol ?: text("default_protocol", "masque")
            // The picker used to save its choice without ever selecting the existing chain service.
            val psiphonOuter = text(CHAIN_OUTER_MODE_PREF, "direct")
            put("protocol", if (listenOverride == null && selected.equals("psiphon", true) &&
                (psiphonOuter == CHAIN_OUTER_AUTO || psiphonOuter in CHAIN_OUTER_LADDER))
                TunnelVpnService.CHAIN_PROTOCOL_MARKER else selected)
            // The app is VPN-mode only: the whole device is tunnelled and there is
            // no user-facing proxy any more. `listen` is still sent because the
            // core requires the field, but in VPN mode no SOCKS listener is ever
            // bound from it — MASQUE/WireGuard/WARP-on-WARP take the `tun_fd`
            // branch in main.rs, and Psiphon owns this port itself via
            // LocalSocksProxyPort. Taken from the app's Local Port setting so a
            // proxy configured once keeps working whichever engine is running.
            put("listen", "127.0.0.1:${listenOverride ?: socksPort(context)}")
            // «توربو» unless the user picked another: the first endpoint that carries data. Measured
            // 2026-10-07 (Irancell line, WARP-in-WARP): balanced 37-47 s -- it collects five endpoints
            // and then waits for a quiet spell -- turbo 17 s. The core rescans by itself when a turbo
            // pass finds nothing, with a fresh sample, so a hard line is not given up on sooner.
            put("scan_mode", text(TunnelPreferences.SCAN_MODE, TunnelPreferences.DEFAULT_SCAN_MODE))
            // Chosen per network unless the user asked for v6 or both (CfFamily.warpScanFamily).
            put("ip_scan", com.mlmvpn.scanner.data.CfFamily.warpScanFamily(context, text("default_scan", "v4")))
            put("endpoint_cache_path", File(context.filesDir, "masque-gateway-cache.json").absolutePath)
            put("endpoint_discovery", text("endpoint_discovery", "cache"))
            // «خودکار» (the default): HTTP/3 with the core's own fallback to HTTP/2, or straight to
            // HTTP/2 where this network is known to need it (MasqueCarrier). A fixed choice is
            // still honoured for whoever set one on purpose.
            put("masque_transport", text("default_masque_transport", MasqueCarrier.AUTO)
                .let { if (it == MasqueCarrier.AUTO) MasqueCarrier.startOn(context) else it })
            // A manual peer pins ONE address, and it belongs to whichever transport
            // the user entered it for. Handing it to the chain's ladder would send a
            // MASQUE gateway to the WireGuard rung, where it cannot work — so the
            // chain's outer legs always scan.
            if (listenOverride == null) {
                putOpt("forced_peer", text("manual_endpoint").ifBlank { null })
            }
            put("obfuscation_profile", text("obfuscation_profile", "balanced"))
            putOpt("obfuscation_parameters", manualObfuscation.takeIf { it.length() > 0 }?.toString())
            put("retry_obfuscation_profiles", prefs.getBoolean("retry_obfuscation_profiles", true))
            put("tls_curve_preset", text("tls_curve_preset", "chrome"))
            put("wireguard_data_check", prefs.getBoolean("wireguard_data_check", true))
            put("log_level", text("log_level", "info"))
            put("perf_profile", text("perf_profile", "auto"))
            put("h2_fragmentation", text("h2_fragmentation", "on") == "on")
            putOpt("dns_servers", text("dns_servers").ifBlank { null })
            putOpt("route_block", text("route_block").ifBlank { null })
            // The Smart Iran route adds every .ir name to the user's own direct list; Iran's
            // addresses already leave outside the tunnel at the VPN builder (SmartIranRoute).
            putOpt("route_direct", (listOf(text("route_direct")).filter { it.isNotBlank() } + SmartIranRoute.transportDirect(context))
                .joinToString(", ").ifBlank { null })
            putOpt("team", SecureStore.getSecret(context, "zero_trust_team").ifBlank { null })
            putOpt("access_client_id", SecureStore.getSecret(context, "zero_trust_client_id").ifBlank { null })
            putOpt("access_client_secret", SecureStore.getSecret(context, "zero_trust_client_secret").ifBlank { null })
            putOpt("access_token", SecureStore.getSecret(context, "zero_trust_token").ifBlank { null })
            putOpt("access_email", SecureStore.getSecret(context, "zero_trust_email").ifBlank { null })
            put("gateway", prefs.getBoolean("zero_trust_gateway", false))
            // Psiphon-over-WARP: the core's SOCKS listener is the upstream proxy
            // Psiphon dials, so the core itself needs no upstream. Left unset.
        }.toString()
    }

    /**
     * Config for the OUTER leg of Psiphon-over-WARP.
     *
     * Differences from a normal connect, each one load-bearing:
     *  - `protocol` is a WARP transport, never "psiphon"; the chain's Psiphon half
     *    is the Go library, not the core.
     *  - `listen` moves to [CHAIN_SOCKS_PORT] because Psiphon keeps [SOCKS_PORT].
     *  - no `tun_fd` is passed by the caller (see TunnelEngine.startProxy), so the
     *    core runs its userspace netstack and publishes SOCKS instead of taking
     *    the device TUN — tun2socks owns that, on Psiphon's side.
     *
     * @param protocol which WARP transport carries this attempt. The service walks
     *   [CHAIN_OUTER_LADDER] and calls this once per rung, so the choice belongs to
     *   the caller rather than to a stored preference.
     */
    fun chainOuterJson(context: Context, protocol: String): String =
        json(context, protocol, listenOverride = chainSocksPort(context))

    /**
     * The outer transports tried, in order, until one carries Psiphon.
     *
     * Ordered by measured likelihood of working on an Iranian carrier, cheapest
     * first:
     *
     *  - `masque` leads because it has two transports of its own (HTTP/3 over
     *    UDP/443, then HTTP/2 over TCP/443 with TLS fragmentation) and a gateway
     *    cache plus a last-known-good endpoint, so a repeat connect is fast.
     *  - `wireguard` next: it is blocked outright on some carriers (Hamrah-e-Aval
     *    has never carried it) but connects immediately on others.
     *  - `gool` last. It is WARP-on-WARP, so it stacks two tunnels under Psiphon
     *    for three in total — the slowest rung, and only worth reaching when the
     *    single-layer ones are blocked.
     *
     * The rung that works is remembered per device, so this ordering only decides
     * the very first attempt.
     */
    val CHAIN_OUTER_LADDER = listOf("masque", "wireguard", "gool")

    /**
     * Which transport the user pinned for the chain's outer leg, or "auto".
     *
     * Separate from [CHAIN_OUTER_PREF]: this is a choice, that is a measurement.
     * Pinning is for the case where the user already knows what their carrier
     * allows and does not want to sit through the search — the automatic ladder
     * remains the default because it is right without being told anything.
     */
    const val CHAIN_OUTER_MODE_PREF = "chain_outer_mode"

    /**
     * Tor's own outer-transport pin. Separate key from [CHAIN_OUTER_MODE_PREF].
     *
     * The two inner tunnels have genuinely different outer needs, so one shared pin
     * would be wrong in both directions. Tor bootstraps through a SOCKS proxy and
     * cares only that it is reachable; Psiphon runs its own protocol ladder inside
     * and is far more sensitive to the outer leg's latency. A user who pins WoW to
     * get Psiphon through a hostile carrier should not thereby force every Tor
     * bootstrap through three stacked tunnels.
     *
     * Absent means auto, exactly as for Psiphon, so nothing changes for anyone who
     * never opens the row.
     */
    const val CHAIN_OUTER_MODE_TOR_PREF = "chain_outer_mode_tor"

    /** Value of [CHAIN_OUTER_MODE_PREF] meaning "try them all, in order". */
    const val CHAIN_OUTER_AUTO = "auto"

    /**
     * Which egress country the user asked Psiphon to try first, or "auto".
     *
     * A *preference*, not a constraint. Psiphon's own `EgressRegion` key is a hard
     * filter — set it and every server outside that country disappears from the
     * candidate pool — and pinning it for the whole session would break the one
     * path that works on the worst domestic operator: of the 430 embedded server
     * entries only 5 advertise FRONTED-MEEK, and all 5 are US/GB. So the country is
     * used for a single short attempt in front of the ladder, then dropped.
     *
     * Shared by both Psiphon paths (plain and chained) on purpose: the question
     * "which country do you want to come out in" has the same answer either way.
     *
     * Read only on the chained path, though — see
     * [TunnelVpnService.armRegionPhase]. A plain Psiphon connect always takes
     * whichever server answers first, which is the fastest path and the behaviour
     * that predates this setting.
     */
    const val EGRESS_REGION_PREF = "psiphon_egress_region"

    /** Value of [EGRESS_REGION_PREF] meaning "let Psiphon choose". */
    const val EGRESS_REGION_AUTO = "auto"

    /**
     * Whether Psiphon's local proxies listen on 0.0.0.0 instead of 127.0.0.1.
     *
     * Off by default, and deliberately so: binding to 0.0.0.0 exposes an OPEN,
     * UNAUTHENTICATED proxy to every device that can reach the phone. On a hotspot
     * that is only the tethered clients, but on a public Wi-Fi it is everyone on
     * the network, and psiphon-tunnel-core has no authentication for its local
     * proxies. It is a real exposure, not a theoretical one, so it stays an
     * explicit opt-in with the risk stated in the UI rather than a silent default.
     *
     * Psiphon-only. MASQUE, WireGuard and WoW take the `tun_fd` branch in the Rust
     * core and never bind a local listener at all, so there is nothing to share;
     * Tor's SOCKS front is TCP-only and stays on loopback.
     */
    const val LAN_SHARING_PREF = "psiphon_lan_sharing"

    /**
     * HTTP proxy port for LAN sharing, alongside SOCKS on [socksPort].
     *
     * Both are offered because they are not interchangeable to the client: Windows
     * takes an HTTP proxy system-wide from Internet Options, while SOCKS has to be
     * configured per-application. Derived from the same Local Port setting rather
     * than fixed at 8080, so all three listeners this app can publish are
     * predictable from the one number the user typed.
     */
    fun httpProxyPort(context: Context): Int =
        com.mlmvpn.scanner.utils.NetworkSettings.httpProxyPort(context)

    /**
     * Whether the user has opted into exposing the local proxies on the LAN.
     *
     * The app-wide setting, not a transport-local copy. The two used to disagree,
     * so the same switch read differently depending on which screen was last open.
     */
    fun lanSharingEnabled(context: Context): Boolean =
        com.mlmvpn.scanner.lan.LanShare.sharingActive(context)

    /** An address a second device can open a socket to, and the interface carrying it. */
    data class LocalAddress(val address: String, val iface: String)

    /**
     * The phone's own address on the LOCAL network, or null when it has none.
     *
     * Used to show the user the address to type on the other device, so the only
     * acceptable answer is an address a second device can actually open a socket to.
     * [localNetworkOn] is the same answer with the interface it came from, and
     * carries the three rules that decide it.
     */
    fun localNetworkAddress(context: Context? = null): String? =
        localNetworkOn(context)?.address

    /**
     * [localNetworkAddress] plus the interface carrying it.
     *
     * The interface name is the only honest signal for whether the network is the
     * user's own -- `ap0` and `rndis0` are a hotspot and a USB tether, `wlan0` is
     * somebody else's Wi-Fi -- and the LAN screen has to say which, because
     * "anyone on this network can use your tunnel" is true on one and false on the
     * other.
     *
     * Three rules, in this order, because each of the two previous single-rule
     * versions of this function got a field report wrong in the OPPOSITE
     * direction:
     *
     *  1. POSITIVE, from the OS: if ConnectivityManager says an interface belongs
     *     to a WIFI or ETHERNET network, any site-local IPv4 on it is the answer.
     *     This is authoritative and needs no guessing at all. It covers the normal
     *     case — phone joined to the same Wi-Fi router as the laptop.
     *
     *  2. RANGE, for tethering: 192.168/16, 172.16/12 and 169.254/16 are accepted
     *     on any non-cellular, non-virtual interface even with NO broadcast
     *     address. This is the rule the broadcast-only build was missing: Android
     *     configures the softap/rndis address over netlink WITHOUT IFA_BROADCAST
     *     on many devices, so `getBroadcast()` is null on a perfectly working
     *     hotspot and the row reported "no local network" while clients were
     *     already associated. Those three ranges are never handed out by a
     *     carrier, so accepting them without the broadcast test is safe.
     *
     *  3. BROADCAST, for everything else: 10/8 is the one private range Iranian
     *     carriers DO assign (10.100.144.206 on an Irancell CGNAT link is
     *     site-local and lives on an interface whose name I did not predict), so a
     *     10.x address is only trusted when the kernel also gave the address a
     *     broadcast address — which a point-to-point modem link never has.
     *
     * The name blocklist and the CM cellular/VPN exclusion stay in front of all
     * three as belt and braces, but nothing rests on them alone.
     */
    fun localNetworkOn(context: Context? = null): LocalAddress? {
        val interfaces = try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: java.net.SocketException) {
            return null
        } catch (_: RuntimeException) {
            // Android's own list can trip over an interface that went away while it was being read
            // (NullPointerException inside NetworkInterface.getAll, on the Outline screen, 1.2.41).
            return null
        }
        val survey = surveyNetworks(context)
        val excluded = survey.excludedNames
        // Rule 1. The OS named the interface itself, so there is nothing to infer.
        // Checked before the scan below because it is the only source that cannot
        // be wrong, and on a phone joined to a router it is always available.
        for (name in survey.lanNames) {
            val nic = interfaces.firstOrNull { it.name == name } ?: continue
            for (ifAddr in nic.interfaceAddresses) {
                val address = ifAddr.address
                if (address !is java.net.Inet4Address) continue
                if (address.isLoopbackAddress) continue
                if (!address.isSiteLocalAddress) continue
                return LocalAddress(address.hostAddress ?: continue, name)
            }
        }
        // Tethering first: when a user shares their VPN the client is almost always
        // on the hotspot, so that address is the one that works. Wi-Fi next. Anything
        // unrecognised still ranks last but is NOT rejected — that is what keeps an
        // unknown hotspot spelling working.
        val rank = { name: String ->
            when {
                name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") -> 0
                name.startsWith("rndis") || name.startsWith("usb") -> 1
                name.startsWith("wlan") -> 2
                name.startsWith("eth") -> 3
                else -> 4
            }
        }
        val candidates = interfaces
            .filter { nic ->
                val name = nic.name.orEmpty()
                name.isNotEmpty() &&
                    name !in excluded &&
                    !isNeverReachablePrefix(name) &&
                    runCatching { nic.isUp }.getOrDefault(false) &&
                    !runCatching { nic.isLoopback }.getOrDefault(true)
            }
            .sortedBy { rank(it.name.orEmpty()) }
        for (nic in candidates) {
            // interfaceAddresses, not inetAddresses, because only the former carries
            // the broadcast address, which rule 3 needs.
            for (ifAddr in nic.interfaceAddresses) {
                val address = ifAddr.address
                if (address !is java.net.Inet4Address) continue
                if (address.isLoopbackAddress) continue
                val text = address.hostAddress ?: continue
                // Rule 2: ranges no carrier ever hands out. Accepted with or
                // without a broadcast address, because Android brings the
                // hotspot/rndis address up without IFA_BROADCAST on many devices
                // and the broadcast-only build therefore reported "no local
                // network" on a working hotspot. 169.254 is here for a USB-tethered
                // laptop that never got a DHCP lease.
                if (isDefinitelyLocalRange(text)) return LocalAddress(text, nic.name.orEmpty())
                // Rule 3: everything else — in practice 10/8, which Iranian
                // carriers do assign on CGNAT links — needs the structural proof
                // that this is a broadcast domain and not a point-to-point modem.
                if (ifAddr.broadcast == null) continue
                if (!address.isSiteLocalAddress) continue
                return LocalAddress(text, nic.name.orEmpty())
            }
        }
        return null
    }

    /**
     * Ranges that can only be a local network, never a carrier link.
     *
     * 192.168/16 is every consumer router and every Android hotspot; 172.16/12 is
     * the other RFC1918 block used by tethering on some vendors; 169.254/16 is
     * link-local, which a USB-tethered client reaches with no DHCP at all.
     * Deliberately excludes 10/8 — that is exactly what an Irancell CGNAT link
     * hands out, and trusting it by range is the bug this whole function keeps
     * relapsing into.
     */
    private fun isDefinitelyLocalRange(text: String): Boolean {
        if (text.startsWith("192.168.")) return true
        if (text.startsWith("169.254.")) return true
        if (text.startsWith("172.")) {
            val second = text.split('.').getOrNull(1)?.toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }

    /**
     * What the OS says about the current networks.
     *
     * [excludedNames] are interface names behind a cellular or VPN network.
     * [lanNames] are interface names behind a Wi-Fi or Ethernet network — the
     * positive half, and the only fully authoritative signal available: when the OS
     * itself says "this interface is the Wi-Fi network", no range or broadcast
     * heuristic can improve on it.
     * [describe] is a one-line, log-safe summary used by the diagnostic line, so a
     * repeat failure in the field names the interfaces instead of making me guess
     * a third time.
     */
    private class NetworkSurvey(
        val excludedNames: Set<String>,
        val lanNames: Set<String>,
        val describe: String,
    )

    private fun surveyNetworks(context: Context?): NetworkSurvey {
        if (context == null) return NetworkSurvey(emptySet(), emptySet(), "no context")
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
            ?: return NetworkSurvey(emptySet(), emptySet(), "no ConnectivityManager")
        val names = mutableSetOf<String>()
        val lan = mutableSetOf<String>()
        val notes = mutableListOf<String>()
        try {
            // getAllNetworks() is deprecated but is the only one-shot enumeration;
            // the callback API would mean holding state for a value read once per
            // repaint. Both cellular and VPN are excluded: the VPN entry is our own
            // tun, whose address is no more reachable from the LAN than the modem's.
            @Suppress("DEPRECATION")
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                // A VPN's LinkProperties carry its whole route table, and reading them back costs
                // the square of its size: OpenVPN split around the WARP edge holds hundreds of
                // routes, and this runs on the main thread (a Windscribe crash report of
                // 2026-10-11 caught it there). Its tun interfaces are excluded by name instead.
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                    tunNames().forEach { names.add(it) }
                    notes.add("tun*=vpn")
                    continue
                }
                val iface = cm.getLinkProperties(network)?.interfaceName ?: continue
                val transports = mutableListOf<String>()
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    transports.add("cell")
                }
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                    transports.add("vpn")
                }
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                    transports.add("wifi")
                }
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    transports.add("eth")
                }
                notes.add("$iface=${transports.joinToString("+").ifEmpty { "other" }}")
                if (transports.contains("cell") || transports.contains("vpn")) {
                    names.add(iface)
                } else if (transports.contains("wifi") || transports.contains("eth")) {
                    // Never both: a VPN network can also report wifi in its
                    // transport set, and that entry must stay excluded.
                    lan.add(iface)
                }
            }
        } catch (_: SecurityException) {
            // ACCESS_NETWORK_STATE missing. The range and broadcast rules stand
            // alone in that case.
            return NetworkSurvey(emptySet(), emptySet(), "ACCESS_NETWORK_STATE denied")
        }
        return NetworkSurvey(
            names,
            lan,
            notes.joinToString(" ").ifEmpty { "no networks" },
        )
    }

    /** The phone's VPN interfaces by name (tun0, ppp0, ipsec…): what a VPN network sits on. */
    private fun tunNames(): List<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { it.name }
            .filter { it.startsWith("tun") || it.startsWith("ppp") || it.startsWith("ipsec") }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * One log-safe line describing why [localNetworkAddress] answered as it did.
     *
     * Exists because the first two attempts at this picked the wrong address and I
     * had no way to tell which interface it came from — only the user's screenshot.
     * Lists every interface with its broadcast flag, which is the deciding test, and
     * the ConnectivityManager verdict. Addresses are private LAN addresses by
     * definition here, and the tunnel's public exit IP is already logged elsewhere,
     * so this leaks nothing new.
     */
    fun describeLocalNetworks(context: Context? = null): String {
        val survey = surveyNetworks(context)
        val rows = mutableListOf<String>()
        try {
            for (nic in java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                val name = nic.name.orEmpty()
                if (name.isEmpty()) continue
                if (!runCatching { nic.isUp }.getOrDefault(false)) continue
                if (runCatching { nic.isLoopback }.getOrDefault(false)) continue
                for (ifAddr in nic.interfaceAddresses) {
                    val a = ifAddr.address
                    if (a !is java.net.Inet4Address) continue
                    val bcast = if (ifAddr.broadcast != null) "bcast" else "p2p"
                    rows.add("$name:${a.hostAddress}/$bcast")
                }
            }
        } catch (_: java.net.SocketException) {
            return "interface scan failed"
        }
        val picked = localNetworkAddress(context) ?: "none"
        return "ifaces[${rows.joinToString(" ")}] cm[${survey.describe}] picked=$picked"
    }

    /**
     * Names that can never carry LAN traffic, as a fallback for when
     * ConnectivityManager tells us nothing.
     *
     * Cellular spellings collected from the wild plus this app's own tun and the
     * kernel's virtual interfaces. Deliberately narrow: anything not listed here is
     * accepted, because a false rejection hides a working hotspot.
     */
    private fun isNeverReachablePrefix(name: String): Boolean =
        name.startsWith("rmnet") ||
            name.startsWith("rev_rmnet") ||
            name.startsWith("ccmni") ||
            name.startsWith("pdp") ||
            name.startsWith("clat") ||
            name.startsWith("v4-") ||
            name.startsWith("tun") ||
            name.startsWith("ppp") ||
            name.startsWith("dummy") ||
            name.startsWith("sit") ||
            name.startsWith("p2p")

    /**
     * The preferred egress country, or null when the user has not picked one.
     *
     * Anything that is not two ASCII letters is treated as "auto" rather than
     * passed through: an invalid region would silently empty Psiphon's candidate
     * pool and every rung would then fail for a reason that looks like censorship.
     */
    fun egressRegion(context: Context): String? {
        val stored = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString(EGRESS_REGION_PREF, EGRESS_REGION_AUTO)
            ?.trim()
            ?.uppercase()
            .orEmpty()
        if (stored.isEmpty() || stored.equals(EGRESS_REGION_AUTO, ignoreCase = true)) return null
        return if (PsiphonRegions.isCode(stored)) stored else null
    }


    /**
     * The transports this connect may use for the outer leg.
     *
     * Auto returns the whole ladder. A pinned transport returns only itself — no
     * silent fallback, because a pin exists precisely to stop the app spending time
     * on transports the user knows are blocked. A stale or unknown pin falls back
     * to auto rather than producing an empty ladder.
     *
     * [forTor] selects Tor's pin instead of Psiphon's. The caller has to say which
     * inner tunnel it is raising the leg for, since the two keys are independent.
     */
    fun chainOuterCandidates(context: Context, forTor: Boolean = false): List<String> {
        val key = if (forTor) CHAIN_OUTER_MODE_TOR_PREF else CHAIN_OUTER_MODE_PREF
        val mode = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString(key, CHAIN_OUTER_AUTO)
            ?.trim()
            .orEmpty()
        return when {
            mode.isEmpty() || mode == CHAIN_OUTER_AUTO -> CHAIN_OUTER_LADDER
            CHAIN_OUTER_LADDER.contains(mode) -> listOf(mode)
            else -> CHAIN_OUTER_LADDER
        }
    }

    /** Whether the outer transport is being chosen automatically. */
    fun chainOuterIsAuto(context: Context, forTor: Boolean = false): Boolean =
        chainOuterCandidates(context, forTor).size > 1

    /** Human-readable name for a rung of [CHAIN_OUTER_LADDER]. */
    fun chainOuterLabel(protocol: String): String = when (protocol) {
        "masque" -> "MASQUE"
        "wireguard" -> "WireGuard"
        "gool" -> "WoW"
        else -> protocol.uppercase()
    }
}
