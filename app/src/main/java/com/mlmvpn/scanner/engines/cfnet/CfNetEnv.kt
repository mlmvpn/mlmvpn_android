package com.mlmvpn.scanner.engines.cfnet

import android.content.Context
import com.mlmvpn.scanner.data.CfFamily
import com.mlmvpn.scanner.engines.cfdoctor.AndroidNetEnv
import com.mlmvpn.scanner.engines.cfdoctor.CfBogon
import com.mlmvpn.scanner.engines.github.GtCleanIp

/**
 * Builds a privacy-safe [NetworkProfile] for the current network. The raw network key (transport +
 * gateway) is hashed before it is kept, so nothing identifying survives. v6 comes from the measured
 * IPv6 route, DNS sanity from one resolve of a known name checked against the bogon list, and the
 * carrier from the SIM operator code or the transport -- never an SSID.
 */
object CfNetEnv {

    @Volatile private var dnsSaneCache: Pair<String, Boolean>? = null

    /**
     * What identifies "this network" across reconnections, so a strategy learned on it is reused:
     *  - Wi-Fi: the saved-network id when Android shares it, plus the default gateway and the DNS
     *    servers (a home router keeps both); never the per-connection handle, which changed on every
     *    reconnect and threw away everything learned on the same Wi-Fi.
     *  - mobile: the carrier (MCC+MNC) only. The gateway and DNS servers there are per session.
     */
    private fun networkKey(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val net = AndroidNetEnv(context).picked?.network ?: return "offline"
        val links = cm.getLinkProperties(net)
        val caps = cm.getNetworkCapabilities(net)
        if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) != true) {
            // The data SIM's own operator, not the default (calling) SIM's network: see Carrier.
            val operator = com.mlmvpn.scanner.data.Carrier.dataMccMnc(context).ifBlank { AndroidNetEnv.operator(context) }
            return "cell|$operator"
        }
        val wifiId = runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION") val info = wifi.connectionInfo
            info.networkId.takeIf { it >= 0 }?.toString()
        }.getOrNull().orEmpty()
        val gateways = links?.routes?.filter { it.isDefaultRoute && it.gateway?.isLinkLocalAddress != true }
            ?.joinToString { it.gateway?.hostAddress.orEmpty() }
        val dns = links?.dnsServers?.filter { !it.isLinkLocalAddress }?.joinToString { it.hostAddress.orEmpty() }
        return "wifi|$wifiId|$gateways|$dns"
    }

    /** The current profile. Cheap: v6 and dns-sanity come from caches refreshed by [refresh]. */
    fun profile(context: Context): NetworkProfile {
        val raw = runCatching { networkKey(context) }.getOrDefault("unknown")
        val key = NetworkProfile.hashKey(raw)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val net = AndroidNetEnv(context).picked?.network
        val links = net?.let(cm::getLinkProperties)
        val v4 = links?.linkAddresses?.any { it.address is java.net.Inet4Address } == true
        val v6 = links?.routes?.any { it.isDefaultRoute && it.destination.address is java.net.Inet6Address } == true
        val carrier = runCatching {
            val env = AndroidNetEnv(context).environment(context)
            val net = env["net"] ?: "unknown"
            if (net == "wifi") "wifi" else AndroidNetEnv.operator(context)
        }.getOrDefault("unknown")
        val dnsSane = dnsSaneCache?.takeIf { it.first == key }?.second ?: true
        return NetworkProfile(key = key, hasV4 = v4, hasV6 = v6, dnsSane = dnsSane, carrier = carrier, ts = System.currentTimeMillis())
    }

    /** One real check of DNS sanity for the current network; cached per network key. Call off the main thread. */
    fun refresh(context: Context) {
        val raw = runCatching { networkKey(context) }.getOrDefault("unknown")
        val key = NetworkProfile.hashKey(raw)
        val sane = runCatching {
            val env = AndroidNetEnv(context)
            val addrs = env.resolve("www.cloudflare.com").mapNotNull { it.hostAddress }
            addrs.isNotEmpty() && addrs.none { CfBogon.isBogon(it) }
        }.getOrDefault(false)
        dnsSaneCache = key to sane
    }
}
