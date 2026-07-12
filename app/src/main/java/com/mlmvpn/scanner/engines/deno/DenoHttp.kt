package com.mlmvpn.scanner.engines.deno

import android.content.Context
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP setup for the Deno v2 API.
 *
 * api.deno.com is Google-fronted and geo-blocked for Iranian IPs. The app
 * disallows itself from its own VPN tunnel, so our traffic never auto-tunnels.
 * When a VPN config is active we therefore route through the core's local
 * "mixed" inbound (HTTP proxy on 127.0.0.1:<local_port>) so requests exit via
 * the tunnel; otherwise we go direct and bypass any system proxy.
 */
object DenoHttp {

    const val API = "https://api.deno.com/v2"

    fun buildClient(context: Context): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
        if (com.mlmvpn.scanner.MyVpnService.isRunning) {
            val port = try {
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                    .getString("local_port", "10808")?.toIntOrNull() ?: 10808
            } catch (e: Exception) { 10808 }
            b.proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", port)))
        } else {
            b.proxy(java.net.Proxy.NO_PROXY)
        }
        return b.build()
    }

    fun authHeaders(token: String): Headers = Headers.Builder()
        .add("Authorization", "Bearer ${token.trim()}")
        .add("Content-Type", "application/json")
        .build()
}
