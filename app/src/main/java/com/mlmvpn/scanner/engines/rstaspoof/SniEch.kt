package com.mlmvpn.scanner.engines.rstaspoof

import android.content.Context
import com.mlmvpn.scanner.engines.cfnet.CfShape
import com.mlmvpn.scanner.utils.CfUnfilter
import com.mlmvpn.scanner.utils.VpnConfig

/**
 * The SNI section's answer to filtering that the SNI front can no longer beat: hide the name.
 *
 * Every method of the front works by cutting or decorating the ClientHello so the filter misreads
 * the server name. Measured on the home Wi-Fi, 2026-10-05: all fourteen methods carried 0 bytes --
 * even `sni_split`, which put the name itself across two TCP segments (157 + 1,404 bytes) and
 * still got no answer. The filter reassembles; no cut gets past it. On the same line, in the same
 * hour, the Cloudflare engine's ECH strategy carried real data to a worker on the first try.
 *
 * So this is one more method on the same ladder: the SNI config's own credentials, path and host,
 * dialled straight to a Cloudflare edge with Encrypted Client Hello -- the outer handshake names
 * `cloudflare-ech.com`, the worker's name is encrypted inside. No front process at all. It is tried
 * after the front's methods, kept only if real data crosses it, and remembered like they are.
 */
object SniEch {
    private const val KEY = "sni_method_ech"

    /** The edge it dials when the selected route's address is not a Cloudflare one. */
    private const val FALLBACK_EDGE = "188.114.98.0"

    fun enabled(context: Context): Boolean =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY, false)

    fun set(context: Context, on: Boolean) {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(KEY, on).apply()
    }

    /** A Cloudflare edge to dial: the route's own address when it is one, else a known-good one. */
    fun edgeFor(context: Context, routeIp: String): String =
        routeIp.takeIf { com.mlmvpn.scanner.engines.flux.core.net.Cloudflare.isCloudflare(it) }
            ?: com.mlmvpn.scanner.data.CfEdge.good(context, 1).firstOrNull()
            ?: FALLBACK_EDGE

    /** [config] (an SNI config, pointing at the front) as the same server dialled directly with ECH. */
    fun direct(config: VpnConfig, edge: String): VpnConfig? {
        if (!CfUnfilter.isLocalFront(config)) return null
        val host = config.sni.ifBlank { config.wsHost }.ifBlank { config.xhttpHost }
        if (host.isBlank()) return null
        return config.copy(address = edge, port = 443, sni = host, ech = CfShape.ECH_DEFAULT, rawUri = "")
    }

    fun directUri(uri: String, edge: String): String? =
        VpnConfig.parseUri(uri)?.let { direct(it, edge) }?.toUriString()
}
