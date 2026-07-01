package com.mlmvpn.scanner.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import javax.net.ssl.SSLSocketFactory

enum class Platform(val displayName: String, val testUrl: String) {
    INSTAGRAM("اینستاگرام", "https://graph.instagram.com"),
    YOUTUBE("یوتیوب", "https://googlevideo.com"),
    TIKTOK("تیک‌تاک", "https://api.tiktokv.com"),
    TWITTER("ایکس", "https://api.twitter.com"),
    WHATSAPP("واتس‌اپ", "https://g.whatsapp.net"),
    GEMINI("جمنای", "https://generativelanguage.googleapis.com"),
    ANTIGRAVITY("آنتی‌گراویتی", "https://api.antigravity.google"),
    CLAUDE("کلاد", "https://api.anthropic.com"),
    TRAE("تِرِی", "https://api.trae.ai"),
    CAPCUT("کپ‌کات", "https://api.capcut.com")
}

object PlatformTester {
    private const val TAG = "PlatformTester"

    /**
     * Tests a specific VPN node against a platform URL by starting Xray locally.
     * Returns the delay in milliseconds, or -1 if failed.
     */
    suspend fun testNodeForPlatform(
        context: Context,
        nodeUri: String,
        platform: Platform,
        localPort: Int
    ): Long = withContext(Dispatchers.IO) {
        var realDelay = -1L
        var coreController: libv2ray.CoreController? = null

        try {
            val config = VpnConfig.parseUri(nodeUri) ?: return@withContext -1L
            // Generate Xray config for this node
            val jsonConfig = XrayJsonGenerator.generateConfig(
                config,
                localPort,
                "1.1.1.1",
                false,
                false // includeTun = false
            )

            // Initialize Core Environment
            val keyBytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(keyBytes)
            val flags = android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            val xudpBaseKey = android.util.Base64.encodeToString(keyBytes, flags)
            
            libv2ray.Libv2ray.initCoreEnv(context.filesDir.absolutePath, xudpBaseKey)

            val handler = object : libv2ray.CoreCallbackHandler {
                override fun onEmitStatus(status: Long, msg: String): Long = 0
                override fun shutdown(): Long = 0
                override fun startup(): Long = 0
            }

            coreController = libv2ray.Libv2ray.newCoreController(handler)
            coreController?.startLoop(jsonConfig, 0)

            // Wait for Xray to initialize and establish the connection
            kotlinx.coroutines.delay(1200)

            // Setup Socket with SOCKS5 Proxy
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", localPort))
            val socket = java.net.Socket(proxy)
            socket.soTimeout = 5000

            // Extract host and use createUnresolved to force remote DNS resolution!
            val host = platform.testUrl.replace("https://", "").replace("http://", "").split("/")[0]
            val dest = InetSocketAddress.createUnresolved(host, 443)

            val startTime = System.currentTimeMillis()
            socket.connect(dest, 5000)

            // Verify TLS Handshake to ensure it's not hijacked
            val sslSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = sslSocketFactory.createSocket(socket, host, 443, true) as javax.net.ssl.SSLSocket
            
            val params = sslSocket.sslParameters
            val sniHostName = javax.net.ssl.SNIHostName(host)
            params.serverNames = listOf(sniHostName)
            sslSocket.sslParameters = params
            
            sslSocket.startHandshake()
            
            realDelay = System.currentTimeMillis() - startTime
            sslSocket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Platform test failed for ${platform.displayName}", e)
        } finally {
            try { coreController?.stopLoop() } catch (e: Exception) {}
        }
        
        return@withContext realDelay
    }
}
