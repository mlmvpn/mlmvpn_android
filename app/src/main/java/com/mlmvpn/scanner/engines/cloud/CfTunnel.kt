package com.mlmvpn.scanner.engines.cloud

import android.content.Context
import com.mlmvpn.scanner.update.UpdateNet
import java.net.Proxy
import java.net.ProxySelector

/**
 * The app's own tunnels as a FALLBACK for the calls that deploy a Worker and fetch configs.
 *
 * Where Cloudflare is blocked a user cannot deploy or take configs directly. Turning on any tunnel of
 * the app (Psiphon, Tor, Lantern, Geph, the GitHub tunnel, V2Ray, …) opens a local SOCKS5 port
 * ([UpdateNet.tunnelProxy] — the VPN services exclude this app from their own tunnel, so that port is
 * the only door in), and a request that cannot connect directly goes through it. Direct stays first
 * (see [CfTunnelSelector]); when nothing is on, nothing changes.
 *
 * Two small bounds keep «direct first» from costing a blocked line a minute per call:
 *  - while a tunnel is on, a direct connect gives up after [DIRECT_CONNECT_MS] (Cloudflare accepts a
 *    TCP connection in well under a second where it is reachable) — see [CfFront];
 *  - and the direct route tries [DIRECT_ADDRESSES] addresses, not every one the name has — see
 *    [WorkerRoute.dns].
 */
object CfTunnel {

    const val DIRECT_CONNECT_MS = 6_000
    const val DIRECT_ADDRESSES = 2

    @Volatile private var source: () -> Proxy? = { null }

    /** Called once at start (MlmApp): the tunnels are asked through the application context. */
    fun bind(context: Context) {
        val app = context.applicationContext
        source = { runCatching { UpdateNet.tunnelProxy(app) }.getOrNull() }
    }

    /** For tests: stand in for the app's tunnels. */
    internal fun bindForTest(provider: () -> Proxy?) { source = provider }

    /** The local SOCKS proxy of whichever tunnel is on, or null. */
    fun proxy(): Proxy? = source()

    /** True when a tunnel is on, i.e. when a failing direct route has somewhere to go. */
    fun available(): Boolean = proxy() != null

    /** A direct connect's timeout: shortened while a tunnel waits behind it. */
    fun directConnectMs(requested: Int): Int = if (requested > 0 && available()) minOf(requested, DIRECT_CONNECT_MS) else requested

    /** The addresses the direct route tries: all of them normally, only the first few while a tunnel is on. */
    fun <T> capDirect(addresses: List<T>): List<T> = if (available()) addresses.take(DIRECT_ADDRESSES) else addresses

    private val cloudflareSelector = CfTunnelSelector(tunnel = { proxy() })

    /** For `OkHttpClient.Builder().proxySelector(...)`: Cloudflare names go direct, then through the tunnel. */
    fun selector(): ProxySelector = cloudflareSelector

    /** What to tell the user when Cloudflare could not be reached at all: the way out is a tunnel. */
    fun advice(tunnelWasOn: Boolean): Pair<String, String> =
        if (tunnelWasOn) Pair(
            "کلادفلر نه مستقیم باز شد و نه از تونل روشن برنامه. تونل دیگری را روشن کنید یا کمی بعد دوباره امتحان کنید.",
            "Cloudflare did not open directly or through the tunnel that is on. Turn on another tunnel, or try again in a moment.",
        ) else Pair(
            "کلادفلر از این شبکه باز نمی‌شود. یکی از تونل‌های برنامه (سایفون، تور، لنترن، گف، گیت‌هاب تانل یا V2Ray) را روشن کنید و دوباره امتحان کنید؛ دیپلوی و دریافت کانفیگ همان‌جا از تونل رد می‌شود.",
            "Cloudflare does not open from this network. Turn on one of the app's tunnels (Psiphon, Tor, Lantern, Geph, GitHub tunnel or V2Ray) and try again — deploying and taking configs then go through it.",
        )
}
