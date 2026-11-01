package com.mlmvpn.core.warp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * A local SOCKS port that leads into Cloudflare WARP, for measurements that must be made the way the
 * traffic will really go -- the IPv4 exit's proxies above all ([Ipv4Exits]): measured from the
 * user's own line instead, they include proxies that refuse Cloudflare's addresses (desktop,
 * 2026-10-13: a Swiss proxy, 540 ms direct, timed out through WARP on both checks).
 *
 *  1. A WARP engine of ours that is up and has a raw port of its own ([register]): «وارپ»'s, the
 *     tunnel core's in proxy mode, a [WarpLeg] something else is riding. Never a tunnel's front:
 *     that one may already carry an exit.
 *  2. Else a short-lived [WarpLeg] of the measurement's own on [HELPER_PORT], stopped when the work
 *     ends.
 *  3. Else (no engine on this phone, or WARP found no way in on this line) direct, and the caller
 *     says so.
 *
 * The desktop's nat64-probe.js › helperEngine.
 */
object WarpVia {
    const val HELPER_PORT = 20960

    enum class Kind { ENGINE, HELPER, DIRECT }

    private val engines = ConcurrentHashMap<String, Int>()

    /** [owner]'s raw WARP SOCKS port is up and may be measured through. */
    fun register(owner: String, port: Int) { engines[owner] = port }
    fun unregister(owner: String) { engines.remove(owner) }

    /** The raw WARP SOCKS port of an engine of ours that answers now, or 0. */
    fun enginePort(): Int = engines.values.firstOrNull { portOpen(it) } ?: 0

    private val helper = WarpLeg(HELPER_PORT, "warp/via")
    private val lock = Mutex()

    /**
     * Runs [block] with a port into WARP (0 = direct). [onVia] hears which kind before the work starts
     * (null while the helper is still starting: on a filtered line that can take a minute, and the page
     * says so meanwhile).
     */
    suspend fun <T> use(ctx: Context, onVia: (Kind?) -> Unit = {}, block: suspend (port: Int, kind: Kind) -> T): T {
        enginePort().takeIf { it > 0 }?.let { onVia(Kind.ENGINE); return block(it, Kind.ENGINE) }
        return lock.withLock {
            // Another use may have started an engine of ours while this one waited.
            enginePort().takeIf { it > 0 }?.let { onVia(Kind.ENGINE); return@withLock block(it, Kind.ENGINE) }
            onVia(null)
            // A stop pressed while the helper is still coming up ends the wait at once, not after it.
            val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            try {
                val up = withContext(Dispatchers.IO) { helper.start(ctx, cancelled = { job?.isActive == false }) }
                // Cancelled while waiting: nothing below may run as if WARP had simply been unreachable.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (up) { onVia(Kind.HELPER); block(HELPER_PORT, Kind.HELPER) }
                else { onVia(Kind.DIRECT); block(0, Kind.DIRECT) }
            } finally {
                // Also when the wait itself was cancelled just as the helper came up.
                withContext(NonCancellable + Dispatchers.IO) { helper.stop() }
            }
        }
    }

    private fun portOpen(port: Int): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 800) }; true
    }.getOrDefault(false)
}
