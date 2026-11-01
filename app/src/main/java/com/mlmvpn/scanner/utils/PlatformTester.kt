package com.mlmvpn.scanner.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import javax.net.ssl.SSLSocketFactory

enum class Platform(val displayName: String, val testUrl: String) {
    INSTAGRAM("Instagram", "https://graph.instagram.com"),
    YOUTUBE("YouTube", "https://googlevideo.com"),
    TIKTOK("TikTok", "https://api.tiktokv.com"),
    TWITTER("X", "https://api.twitter.com"),
    WHATSAPP("WhatsApp", "https://g.whatsapp.net"),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com"),
    ANTIGRAVITY("Antigravity", "https://api.antigravity.google"),
    CLAUDE("Claude", "https://api.anthropic.com"),
    TRAE("Tere", "https://api.trae.ai"),
    CAPCUT("CapCut", "https://api.capcut.com")
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
        // A test core, in the test host process (XrayHosts): a node that panics Xray ends that
        // process, not the app.
        var core: com.mlmvpn.core.warp.VlessXrayInjector? = null
        var socket: java.net.Socket? = null
        var sslSocket: javax.net.ssl.SSLSocket? = null

        try {
            val config = VpnConfig.parseUri(nodeUri) ?: return@withContext -1L
            // Generate Xray config for this node
            val jsonConfig = XrayJsonGenerator.generateConfig(
                config = config,
                localPort = localPort,
                backendDns = "1.1.1.1",
                allowLan = false,
                includeTun = false,
            )

            val c = com.mlmvpn.core.warp.VlessXrayInjector(0, quiet = true)
            if (!c.start(context, jsonConfig, localPort)) return@withContext -1L
            core = c

            // Wait for Xray to initialize and establish the connection
            kotlinx.coroutines.delay(1200)

            // Setup Socket with SOCKS5 Proxy
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", localPort))
            socket = java.net.Socket(proxy)
            socket.soTimeout = 5000

            // Extract host and use createUnresolved to force remote DNS resolution!
            val host = platform.testUrl.replace("https://", "").replace("http://", "").split("/")[0]
            val dest = InetSocketAddress.createUnresolved(host, 443)

            val startTime = System.currentTimeMillis()
            socket.connect(dest, 5000)

            // Verify TLS Handshake to ensure it's not hijacked
            val sslSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            sslSocket = sslSocketFactory.createSocket(socket, host, 443, true) as javax.net.ssl.SSLSocket

            val params = sslSocket.sslParameters
            val sniHostName = javax.net.ssl.SNIHostName(host)
            params.serverNames = listOf(sniHostName)
            sslSocket.sslParameters = params

            sslSocket.startHandshake()

            realDelay = System.currentTimeMillis() - startTime
        } catch (e: Exception) {
            Log.e(TAG, "Platform test failed for ${platform.displayName}", e)
        } finally {
            // sslSocket wraps socket with autoClose=true, so closing it also closes the underlying socket
            if (sslSocket != null) {
                try { sslSocket.close() } catch (e: Exception) {}
            } else {
                try { socket?.close() } catch (e: Exception) {}
            }
            try { core?.stop() } catch (e: Exception) {}
        }
        
        return@withContext realDelay
    }
}



