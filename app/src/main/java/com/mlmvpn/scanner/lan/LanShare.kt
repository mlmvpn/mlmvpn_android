package com.mlmvpn.scanner.lan

import android.content.Context
import com.mlmvpn.core.tunnel.CoreConfig
import com.mlmvpn.core.tunnel.TunnelStatus
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.data.LastEngine
import com.mlmvpn.scanner.utils.LocalPort
import com.mlmvpn.scanner.utils.NetworkSettings

// =================================================================================================
// What the app can and cannot share right now, in one answer.
//
// Sharing the tunnel is one sentence to a user and four independent facts to the app: is a tunnel
// up, does the engine carrying it publish a local listener at all, is LAN access switched on, and
// does this phone even have an address a second device could reach. Any one of them being false
// means nothing works, and each fails in a way the other three cannot explain.
//
// Before this, all four were read at the call site -- so the settings row could say "on" while the
// running engine had no listener, and the connection log's "LAN sharing: SOCKS5 at ..." line was
// the only place the truth appeared. This object is the single answer, and [LanStatus.blocker] is
// what a screen shows instead of guessing.
//
// Nothing here starts or stops anything. It reports.
// =================================================================================================

/**
 * Which network the phone's local address sits on.
 *
 * The distinction that matters is ownership, not technology: on [HOTSPOT] and [USB] the only
 * devices that can reach this phone are ones the user physically attached, and on [WIFI] it is
 * everyone on somebody else's network. The LAN proxy has no password by default, so those two
 * cases deserve opposite warnings -- which is the whole reason the interface name is carried out
 * of [CoreConfig.localNetworkOn] rather than thrown away with the address.
 */
enum class Medium {
    /** The phone is the access point: `ap0`, `swlan0`, `softap0`. */
    HOTSPOT,

    /** USB tethering: `rndis0`, `usb0`. */
    USB,

    /** Joined to someone else's Wi-Fi: `wlan0`. */
    WIFI,

    /** Wired, on the handful of devices that have it: `eth0`. */
    ETHERNET,

    /** An interface none of the prefixes claim. Treated as untrusted, like [WIFI]. */
    UNKNOWN,

    /** No local address at all -- nothing is reachable. */
    NONE;

    /**
     * True when every device that can reach this phone is one the user attached themselves.
     *
     * [UNKNOWN] is deliberately NOT trusted. An unrecognised interface name is exactly as likely
     * to be a vendor's spelling of a public Wi-Fi as of a hotspot, and the cost of the two wrong
     * answers is not symmetric: calling a hotspot untrusted shows one warning too many, calling a
     * café's Wi-Fi trusted hides the one warning that mattered.
     */
    val isOwnNetwork: Boolean get() = this == HOTSPOT || this == USB
}

/** How much of the sharing feature the currently running engine can actually support. */
enum class EngineShare {
    /** Xray family: a `mixed` inbound on 0.0.0.0. SOCKS5 and HTTP, UDP, auth and ACL. */
    FULL,

    /** Psiphon: SOCKS and HTTP on every interface, but its core has no proxy authentication. */
    NO_AUTH,

    /**
     * MASQUE, WireGuard, WARP-on-WARP in VPN mode.
     *
     * The Rust core took the `tun_fd` branch, so no local listener exists at all -- see
     * [TunnelStatus.isNativeTunMode]. The same core publishes SOCKS when it is NOT handed a tun,
     * which is what Proxy Mode does, so this is recoverable without changing engine.
     */
    NEEDS_PROXY_MODE,

    /** AmneziaWG and VPN Gate: pure tun, no proxy branch in the client at all. */
    IMPOSSIBLE,

    /** Nothing is running, so there is nothing to share yet. */
    NO_TUNNEL,
}

/**
 * The one thing standing between the user and a working share, or null when nothing is.
 *
 * Ordered by the sequence the start button walks, so the first non-null answer is also the first
 * thing to fix. Each one maps to a specific control on the screen -- there is no "something went
 * wrong" case, because a blocker the user cannot act on is the same as no explanation at all.
 */
enum class LanBlocker {
    /** No tunnel is up. Fix: connect. */
    NO_TUNNEL,

    /** The engine cannot publish a listener in its current mode. Fix: proxy mode, or switch. */
    ENGINE_CANNOT_SHARE,

    /** `allow_lan` is off. Fix: turn it on -- the start button does this itself. */
    LAN_DISABLED,

    /** No hotspot, no tether, no Wi-Fi. Fix: start a hotspot. */
    NO_LOCAL_NETWORK,

    /** There is an address, but the proxy port is not accepting connections on it. */
    PORT_NOT_LISTENING,
}

/** One device seen using the proxy. */
data class LanClient(
    val address: String,
    /** Open connections right now. Zero means seen earlier in this session, not currently active. */
    val connections: Int,
    /** True when this device has fetched the setup page, whether or not it went on to connect. */
    val openedSetupPage: Boolean,
    /**
     * Bytes relayed for this device, both directions, since the share started.
     *
     * Zero for a device that has only read the instructions. This is the figure the plan gave up
     * on -- `libv2ray` breaks traffic down by outbound tag and the phone's own traffic shares
     * that tag -- and it exists now only because [LanRelay] copies the bytes itself.
     */
    val bytes: Long = 0L,
    /** When this device was last seen, connected or not. */
    val lastSeen: Long = 0L,
) {
    /** True when this device has actually pushed traffic through the share at some point. */
    val everConnected: Boolean get() = bytes > 0L
}

/**
 * Everything the LAN screen draws, and everything the start button branches on.
 *
 * Built by [LanShare.status] in one pass so that no two rows of the screen can disagree about
 * whether sharing is working.
 */
data class LanStatus(
    val address: String?,
    val medium: Medium,
    val engine: EngineShare,
    val vpnUp: Boolean,
    val lanEnabled: Boolean,
    val socksPort: Int,
    val httpPort: Int,
    val setupPort: Int,
    /**
     * Where on loopback the running engine actually publishes its listener.
     *
     * Not always [socksPort]. The Xray family binds the app's Local Port, but the transports
     * carried by `TunnelVpnService` publish wherever that service put them -- Tor's front-end
     * sits on 1821 -- and [TunnelStatus.activeSocksPort] is the only thing that knows which.
     * [LanRelay] dials this; every port the user is shown is still [sharePort].
     */
    val upstreamPort: Int,
    /**
     * True when the upstream answers HTTP proxy requests as well as SOCKS5.
     *
     * Only the Xray family's `mixed` inbound does. Everything else on the tunnel service is a
     * plain SOCKS5 server, which is why [LanRelay] speaks CONNECT itself rather than passing
     * an HTTP request to something that would not understand it.
     */
    val upstreamSpeaksHttp: Boolean,
    val clients: List<LanClient>,
    /**
     * The port [LanRelay] is currently bound to, or null when it is not up.
     *
     * Kept separate from [sharePort] so the fallback is visible: a relay that failed to bind is
     * a different situation from one that is not wanted, and only the first should ever leave
     * the engine's own port on screen.
     */
    val relayPort: Int? = null,
) {
    /**
     * The proxy port other devices are told to use.
     *
     * The relay's, so that every connection is counted, blockable and attributable -- see
     * [LanRelay]. Falls back to the engine's own listener if the relay could not bind, which
     * still works for the user and merely goes uncounted.
     */
    val sharePort: Int get() = relayPort ?: socksPort

    /** The first thing stopping this from working, or null when nothing is. */
    val blocker: LanBlocker?
        get() = when {
            !vpnUp -> LanBlocker.NO_TUNNEL
            engine == EngineShare.NEEDS_PROXY_MODE ||
                engine == EngineShare.IMPOSSIBLE -> LanBlocker.ENGINE_CANNOT_SHARE
            !lanEnabled -> LanBlocker.LAN_DISABLED
            address == null -> LanBlocker.NO_LOCAL_NETWORK
            else -> null
        }

    /** True when a second device could connect right now. */
    val ready: Boolean get() = blocker == null

    /** True once at least one device is actually pushing traffic through the proxy. */
    val inUse: Boolean get() = clients.any { it.connections > 0 }

    /** The setup page to hand the other device. Null until there is an address to reach it on. */
    val setupUrl: String? get() = address?.let { "http://$it:$setupPort" }

    /** The PAC URL, which is the one field most clients need and the only one worth typing. */
    val pacUrl: String? get() = address?.let { "http://$it:$setupPort/proxy.pac" }

    /** `192.168.43.1:10812`, for the clients that take a host and a port and nothing else. */
    val proxyEndpoint: String? get() = address?.let { "$it:$sharePort" }
}

object LanShare {

    /**
     * Offset from the app's Local Port to the setup web server.
     *
     * +3 because the first three are spoken for: `port` is the mixed inbound, `port + 1` the
     * chained SOCKS of Psiphon-over-WARP, `port + 2` Psiphon's separate HTTP proxy. `port + 10000`
     * is the status probe. Nothing else in the app binds a port derived from this one, so +3 is
     * free at every legal value of the setting -- [LocalPort.validate] already keeps `port` out of
     * the delay tester's range, and +3 cannot cross into it from outside.
     */
    const val SETUP_PORT_OFFSET = 3

    fun setupPort(context: Context): Int = LocalPort.get(context) + SETUP_PORT_OFFSET

    /**
     * The whole picture, cheap enough to call on every repaint.
     *
     * The only expensive part is the interface walk inside [CoreConfig.localNetworkOn], which is
     * the same one the connection log already does per connect. [clients] is passed in rather than
     * read here so that a screen can poll connections at its own rate without re-walking every
     * network interface each time.
     */
    fun status(context: Context, clients: List<LanClient> = emptyList()): LanStatus {
        val local = CoreConfig.localNetworkOn(context)
        val port = LocalPort.get(context)
        return LanStatus(
            address = local?.address,
            medium = mediumOf(local?.iface),
            engine = engineShare(context),
            vpnUp = isTunnelUp(context),
            // The effective answer, not the raw switch: a share that a protection has turned
            // off must show as off, or the screen would advertise an address nothing answers on.
            lanEnabled = NetworkSettings.lanSharingActive(
                context,
                onOwnNetwork = mediumOf(local?.iface).isOwnNetwork,
            ),
            socksPort = port,
            httpPort = NetworkSettings.httpProxyPort(context),
            setupPort = port + SETUP_PORT_OFFSET,
            clients = clients,
            relayPort = LanRelay.boundPort(),
            upstreamPort = upstreamPort(context),
            upstreamSpeaksHttp = MyVpnService.isRunning && MyVpnService.runtimeSocksPort != com.mlmvpn.core.aether.AetherEngine.AETHER_SOCKS_PORT,
        )
    }

    /**
     * Interface name to [Medium].
     *
     * The prefixes are the same ones [CoreConfig.localNetworkOn] ranks by, kept in step on
     * purpose: an interface that function calls a hotspot and this one calls unknown would show
     * the wrong warning over a correct address.
     */
    fun mediumOf(iface: String?): Medium {
        val name = iface?.lowercase().orEmpty()
        return when {
            name.isEmpty() -> Medium.NONE
            name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") ->
                Medium.HOTSPOT
            name.startsWith("rndis") || name.startsWith("usb") -> Medium.USB
            name.startsWith("wlan") -> Medium.WIFI
            name.startsWith("eth") -> Medium.ETHERNET
            else -> Medium.UNKNOWN
        }
    }

    /**
     * Whether the proxy should be published on the LAN right now, protections included.
     *
     * The one call every engine makes when it decides where to bind. It folds the user's switch
     * together with the share timer and the own-network-only rule, so a phone that has wandered
     * onto a café's Wi-Fi stops sharing without the engine having to know why.
     */
    fun sharingActive(context: Context): Boolean =
        NetworkSettings.lanSharingActive(context, onOwnNetwork = currentMedium(context).isOwnNetwork)

    /** The medium the phone's local address currently sits on. */
    fun currentMedium(context: Context): Medium = mediumOf(CoreConfig.localNetworkOn(context)?.iface)

    /**
     * True when any of the app's engines is carrying traffic.
     *
     * The engine flags first, then the two answers that need no engine to report in: a transport
     * of the tunnel service that published a SOCKS port (WARP's tile, Geph, Tor, Psiphon -- none of
     * them is the Rust core or tun2socks, so [TunnelStatus.isActive] alone said "no tunnel" with
     * them up), and a VPN this app owns according to the system (the AmneziaWG backend in its own
     * process, and any engine added later).
     */
    fun isTunnelUp(context: Context): Boolean = MyVpnService.isRunning || TunnelStatus.isActive() ||
        TunnelStatus.activeSocksPort > 0 || com.mlmvpn.scanner.openvpn.OpenVpnSocks.port > 0 || vpnGateUp(context) || ownVpnUp(context)

    /** The system has a VPN network up that belongs to this app. */
    private fun ownVpnUp(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        cm.allNetworks.any { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@any false
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                (android.os.Build.VERSION.SDK_INT < 29 || caps.ownerUid == android.os.Process.myUid())
        }
    }.getOrDefault(false)

    /**
     * VPN Gate (SoftEther) is a pure tun with no proxy branch: up, but nothing to share. Its
     * "connected since" is saved and cleared only by a clean disconnect, so after the app was killed
     * or crashed while it was up the stale value read as a running tunnel and the LAN screen said
     * "on" with nothing connected (2026-10-11): it counts only while a VPN of this app is up.
     */
    private fun vpnGateUp(context: Context): Boolean = com.mlmvpn.scanner.engines.vpngate.VpnGateStore.connectedSinceFlow.value > 0L &&
        (MyVpnService.isRunning || ownVpnUp(context))

    /**
     * What the engine that is actually running can support.
     *
     * The decisive test is [TunnelStatus.isNativeTunMode], not which transport the user picked:
     * the same MASQUE that cannot share in VPN mode publishes SOCKS perfectly well in Proxy Mode,
     * and asking the transport's name would get that backwards in both directions.
     *
     * [MyVpnService] is checked first because the Xray family is both the common case and the
     * only one with no caveats. The two services are mutually exclusive at runtime -- see
     * `TunnelExclusion` -- so the order is a preference, not a race.
     */
    fun engineShare(context: Context): EngineShare {
        // Except the AmneziaWG core it hosts for an AmneziaWG-only server: no listener exists there,
        // and calling it FULL showed a ready share whose relay dialled nothing.
        if (MyVpnService.isRunning) return if (MyVpnService.runtimeSocksPort == 0) EngineShare.IMPOSSIBLE else EngineShare.FULL
        // OpenVPN (its tile, Windscribe's OpenVPN family): the service's own Local Port, password and all.
        if (com.mlmvpn.scanner.openvpn.OpenVpnSocks.port > 0) return EngineShare.FULL
        if (vpnGateUp(context)) return EngineShare.IMPOSSIBLE
        val core = TunnelStatus.isActive()
        if (!core && TunnelStatus.activeSocksPort <= 0 && !ownVpnUp(context)) return EngineShare.NO_TUNNEL
        // Psiphon owns the port itself and binds "any" when sharing is on, so it publishes a
        // listener even though the Rust core beside it may not.
        if (isPsiphon(context)) return EngineShare.NO_AUTH
        // The core took the tun: no SOCKS listener exists to share. Recoverable via Proxy Mode,
        // which on this service now means TUN -> tun2socks -> the core's own SOCKS rather than
        // "no VPN" -- see TunnelVpnService.startNativeProxyTunnel.
        if (TunnelStatus.isNativeTunMode) return EngineShare.NEEDS_PROXY_MODE
        // Up, but neither the core nor a published port: the AmneziaWG backend, a pure tun.
        if (!core && TunnelStatus.activeSocksPort <= 0) return EngineShare.IMPOSSIBLE
        // A listener exists, and on this service it is always plain SOCKS5 with no accounts:
        // Tor's front-end has no authentication, and the Rust core's `socks::serve` has none
        // either. Neither is a `mixed` inbound, so neither answers HTTP -- which is what the
        // relay's CONNECT front-end is for.
        return EngineShare.NO_AUTH
    }

    /**
     * The loopback port the running engine's listener is on, or 0 when there is none.
     *
     * [MyVpnService] is asked first for the same reason [engineShare] asks it first: it is the
     * common case, it always uses the app's Local Port, and it never sets
     * [TunnelStatus.activeSocksPort].
     */
    fun upstreamPort(context: Context): Int = when {
        MyVpnService.isRunning -> MyVpnService.runtimeSocksPort.takeIf { it > 0 } ?: LocalPort.get(context)
        com.mlmvpn.scanner.openvpn.OpenVpnSocks.port > 0 -> com.mlmvpn.scanner.openvpn.OpenVpnSocks.port
        else -> TunnelStatus.activeSocksPort
    }

    private fun isPsiphon(context: Context): Boolean = lastTransportId(context) == "psiphon"

    private fun lastTransportId(context: Context): String? =
        (LastEngine.read(context) as? LastEngine.Record.Tunnel)?.transportId
}
