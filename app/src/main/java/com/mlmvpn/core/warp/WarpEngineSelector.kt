package com.mlmvpn.core.warp

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Smart multi-engine WARP selector.
 *
 * Different operators block different things: on some, MASQUE (usque) is dead but scanned WireGuard
 * (warp-plus) survives; on others the reverse. Rather than hard-coding one engine, this tries each,
 * **verifies real data actually flows** through its local SOCKS with a live health-check (fetch
 * cloudflare.com/cdn-cgi/trace THROUGH the proxy), and returns the first that works. The winner is
 * cached per-device so next launch tries the known-good engine first -- fast path -- and only falls
 * back to probing the rest if it stops working.
 *
 * This is the answer to "many users, many networks": each device self-selects the engine that works
 * on its network, with zero manual configuration.
 */
object WarpEngineSelector {

    private const val TAG = "WarpEngineSelector"
    private const val PREF_LAST_GOOD = "warp_last_good_engine"
    private const val HEALTH_URL = "https://cloudflare.com/cdn-cgi/trace"
    private const val HEALTH_TIMEOUT_MS = 6000L

    /** A runnable WARP engine backed by a local SOCKS5 proxy. */
    data class Engine(
        val id: String,
        val socksPort: Int,
        val isAvailable: (Context) -> Boolean,
        val ensureRunning: (Context) -> Boolean,
        val stop: () -> Unit
    )

    /** The engines to try, in default priority order. */
    private val ENGINES: List<Engine> = listOf(
        Engine(
            id = "masque",
            socksPort = MasqueManager.SOCKS_PORT,
            isAvailable = { MasqueManager.isAvailable(it) },
            ensureRunning = { MasqueManager.ensureRunning(it) },
            stop = { MasqueManager.stop() }
        ),
        Engine(
            id = "warpplus",
            socksPort = WarpPlusManager.SOCKS_PORT,
            isAvailable = { WarpPlusManager.isAvailable(it) },
            ensureRunning = { WarpPlusManager.ensureRunning(it) },
            stop = { WarpPlusManager.stop() }
        )
    )

    /** Result of a successful selection: which engine won and the SOCKS port to route Xray through. */
    data class Selection(val engineId: String, val socksPort: Int)

    /**
     * Try engines until one both starts AND passes a live data-flow health-check. Blocking; call off
     * the main thread. Returns the winning [Selection] or null if none work on this network.
     *
     * Order: last-known-good engine first (fast path), then the rest. Losing engines are stopped so
     * only the winner's process stays alive.
     */
    fun selectBest(context: Context): Selection? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val lastGood = prefs.getString(PREF_LAST_GOOD, null)

        val ordered = ENGINES.sortedByDescending { it.id == lastGood }
        Log.d(TAG, "Selection order: ${ordered.joinToString { it.id }} (lastGood=$lastGood)")

        for (engine in ordered) {
            if (!engine.isAvailable(context)) {
                Log.d(TAG, "[${engine.id}] binary not shipped -- skipping")
                continue
            }
            Log.d(TAG, "[${engine.id}] starting...")
            val started = try { engine.ensureRunning(context) } catch (e: Exception) {
                Log.w(TAG, "[${engine.id}] ensureRunning threw: ${e.message}"); false
            }
            if (!started) {
                Log.w(TAG, "[${engine.id}] failed to start -- next engine")
                try { engine.stop() } catch (_: Exception) {}
                continue
            }

            val healthy = healthCheck(engine.socksPort)
            if (healthy) {
                Log.d(TAG, "[${engine.id}] HEALTHY -- data flows. Selected.")
                prefs.edit().putString(PREF_LAST_GOOD, engine.id).apply()
                // Stop the other engines so only the winner holds a process/port.
                ordered.filter { it.id != engine.id }.forEach { try { it.stop() } catch (_: Exception) {} }
                return Selection(engine.id, engine.socksPort)
            }

            Log.w(TAG, "[${engine.id}] started but NO data flows (health-check failed) -- next engine")
            try { engine.stop() } catch (_: Exception) {}
        }

        Log.e(TAG, "No WARP engine passed the health-check on this network")
        return null
    }

    /**
     * Fetch [HEALTH_URL] THROUGH the engine's local SOCKS5 proxy. Real proof the tunnel carries
     * traffic end-to-end: a plain port-open check is not enough (usque opens its SOCKS port even when
     * the upstream MASQUE tunnel is black-holed). Retries a couple of times because a freshly-started
     * tunnel may need a moment to establish its first upstream connection.
     */
    private fun healthCheck(socksPort: Int, attempts: Int = 2): Boolean {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(HEALTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(HEALTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(HEALTH_TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

        repeat(attempts) { attempt ->
            try {
                val request = Request.Builder().url(HEALTH_URL).build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    // cdn-cgi/trace returns key=value lines; "warp=on"/"h=..." present means we reached CF.
                    if (response.isSuccessful && (body.contains("warp=") || body.contains("loc="))) {
                        Log.d(TAG, "health-check OK on port $socksPort (attempt ${attempt + 1})")
                        return true
                    }
                    Log.d(TAG, "health-check bad response on $socksPort (attempt ${attempt + 1}): code=${response.code}")
                }
            } catch (e: Exception) {
                Log.d(TAG, "health-check failed on $socksPort (attempt ${attempt + 1}): ${e.javaClass.simpleName}: ${e.message}")
            }
            try { Thread.sleep(2000) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    /** Stop every engine (used on VPN teardown so no orphan processes linger). */
    fun stopAll() {
        ENGINES.forEach { try { it.stop() } catch (_: Exception) {} }
    }
}
