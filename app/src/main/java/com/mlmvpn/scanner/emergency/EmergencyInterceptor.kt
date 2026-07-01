package com.mlmvpn.scanner.emergency

import android.content.Context
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

class EmergencyInterceptor(private val context: Context) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val isEmergency = EmergencyStateManager.getInstance(context).isVercelEnabled.value

        val host = request.url.host
        val isTargetDomain = host == "api.cloudflare.com" || 
                             host.endsWith(".workers.dev") || 
                             // Exclude direct IPs (IPv4)
                             !host.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))

        // We also want to make sure we don't proxy Vercel itself and cause infinite loops
        val isNotVercelProxy = host != "mlm-proxy.vercel.app"
        val isNotSpeedTest = host != "speed.cloudflare.com" // speed test

        if (isEmergency && isTargetDomain && isNotVercelProxy && isNotSpeedTest) {
            val originalUrlString = request.url.toString()
            val encodedUrl = URLEncoder.encode(originalUrlString, "UTF-8")
            val proxyUrl = "https://mlm-proxy.vercel.app/api?url=$encodedUrl".toHttpUrlOrNull()

            if (proxyUrl != null) {
                android.util.Log.d("EmergencyInterceptor", "ðŸ”¥ PROXY ACTIVE: Redirecting ${request.method} $originalUrlString -> $proxyUrl")
                // Vercel edge proxy forwards methods, headers and body automatically
                val newRequest = request.newBuilder()
                    .url(proxyUrl)
                    .build()
                return chain.proceed(newRequest)
            }
        } else if (isEmergency) {
            android.util.Log.d("EmergencyInterceptor", "âš¡ PROXY BYPASS: Direct ${request.method} ${request.url}")
        }

        return chain.proceed(request)
    }
}
