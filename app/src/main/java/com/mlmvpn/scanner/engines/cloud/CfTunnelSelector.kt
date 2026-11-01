package com.mlmvpn.scanner.engines.cloud

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Cloudflare DIRECT first; the user's own tunnel only when the direct route cannot connect.
 *
 * The rule (the user, 2026-10-16): «دیپلوی و دریافت کانفیگ مستقیم باشه؛ فقط اگر برای یک کاربر
 * کلادفلر مسدود بود و نتونست مستقیم دیپلوی کنه یا کانفیگ بگیره، یکی از تونل‌ها رو روشن کنه و با اون
 * دیپلوی کنه و کانفیگ‌ها رو بگیره.» So this never turns the tunnel into a mode: for a Cloudflare name
 * ([isCloudflare]) it hands OkHttp the routes in the order «direct, then the tunnel's local SOCKS
 * proxy», and OkHttp does the rest — it tries the routes in that order on a connection failure, and it
 * remembers a route that just failed, so the next call to the same name tries the tunnel first
 * without paying the direct timeout again. Any other name is left exactly as it was.
 *
 * Through a SOCKS5 proxy OkHttp sends the NAME, not an address this phone looked up, so a line that
 * poisons the name is beaten by the tunnel resolving it.
 *
 * Pure JDK, so it runs as a plain JVM test; [CfTunnel] binds it to the app's tunnels.
 */
class CfTunnelSelector(
    /** The local proxy of whichever tunnel is on right now, or null. */
    private val tunnel: () -> Proxy?,
    private val isCloudflare: (String) -> Boolean = { WorkerRoute.isCloudflareName(it) },
) : ProxySelector() {

    override fun select(uri: URI?): List<Proxy> {
        val host = uri?.host?.lowercase() ?: return DIRECT
        if (!isCloudflare(host)) return DIRECT
        val t = tunnel() ?: return DIRECT
        return listOf(Proxy.NO_PROXY, t)
    }

    /** OkHttp reports a failed PROXY route here; a failed direct route is remembered by OkHttp's own route database. */
    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit

    companion object {
        private val DIRECT = listOf(Proxy.NO_PROXY)
    }
}
