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
        // A test core, in the test host process (XrayHosts): a node that panics Xray ends that
        // process, not the app.
        var core: com.mlmvpn.core.warp.VlessXrayInjector? = null
        try {
            val config = VpnConfig.parseUri(nodeUri) ?: return@withContext null
            val jsonConfig = XrayJsonGenerator.generateConfig(
                config = config,
                localPort = localPort,
                backendDns = "1.1.1.1",
                allowLan = false,
                includeTun = false,
            )

            val c = com.mlmvpn.core.warp.VlessXrayInjector(0, quiet = true)
            if (!c.start(context, jsonConfig, localPort)) return@withContext null
            core = c

            kotlinx.coroutines.delay(1200)

            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", localPort))
            // The bundled OkHttp, not HttpURLConnection.
            //
            // HttpURLConnection is backed by the PLATFORM's own copy of OkHttp, and on Android 8.0
            // that copy has a race in its connection pool: the cleanup thread reads the address of
            // a socket that has already been released and throws
            // `NullPointerException: ... java.net.InetAddress.toString() on a null object
            // reference` from `com.android.okhttp.ConnectionPool.cleanup`. It happens on a thread
            // this app does not own, with not one frame of ours in the stack, so nothing here can
            // catch it -- a user hit it seconds after opening the V2Ray list, which is where this
            // lookup runs once per node. Nothing can be done about the platform's copy; the answer
            // is not to use it. The app already ships its own OkHttp, whose pool does not have the
            // bug, and this call site is the only one that reaches Cloudflare through the tunnel.
            val client = okhttp3.OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val body = client.newCall(okhttp3.Request.Builder().url(GEOIP_URL).build())
                .execute()
                .use { it.body?.string().orEmpty() }

            val json = JSONObject(body)
            val code = json.optString("country_code", "")
            code.takeIf { it.length == 2 }?.uppercase()
        } catch (e: Exception) {
            Log.d(TAG, "country lookup failed: ${e.message}")
            null
        } finally {
            try { core?.stop() } catch (e: Exception) {}
        }
    }
}
