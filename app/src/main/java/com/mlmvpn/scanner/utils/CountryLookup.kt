package com.mlmvpn.scanner.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Resolves the exit country of a node by actually tunneling a request through it, instead of
 * trusting the country claimed in the config's own remark text. Only meant to run ONCE per node
 * (the result is cached on VpnNode.countryCode and persisted) -- this is the heavier,
 * CoreController-based path (same shape as PlatformTester.testNodeForPlatform), not the fast
 * measureOutboundDelay() call the regular "Real Delay" test uses for every run, so callers must
 * gate it behind `node.countryCode == null` themselves rather than calling it on every test.
 */
object CountryLookup {
    private const val TAG = "CountryLookup"
    private const val GEOIP_URL = "https://api.ip.sb/geoip"

    suspend fun resolveCountry(context: Context, nodeUri: String, localPort: Int): String? = withContext(Dispatchers.IO) {
        var coreController: libv2ray.CoreController? = null
        try {
            val config = VpnConfig.parseUri(nodeUri) ?: return@withContext null
            val jsonConfig = XrayJsonGenerator.generateConfig(config, localPort, "1.1.1.1", false, false)

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

            kotlinx.coroutines.delay(1200)

            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", localPort))
            val conn = URL(GEOIP_URL).openConnection(proxy) as javax.net.ssl.HttpsURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val body = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }

            val json = JSONObject(body)
            val code = json.optString("country_code", "")
            code.takeIf { it.length == 2 }?.uppercase()
        } catch (e: Exception) {
            Log.d(TAG, "country lookup failed: ${e.message}")
            null
        } finally {
            try { coreController?.stopLoop() } catch (e: Exception) {}
        }
    }
}
