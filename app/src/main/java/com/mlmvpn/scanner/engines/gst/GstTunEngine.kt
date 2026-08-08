package com.mlmvpn.scanner.engines.gst

import android.content.Context
import com.mlmvpn.core.warp.IVpnEngine
import com.mlmvpn.scanner.tun.Tun2proxyClient
import com.mlmvpn.scanner.tun.Tun2proxyHostService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-device GST tunnel, wired exactly like the reference mhrv-rs Android app
 * (`MhrvVpnService.kt`): VpnService TUN → native `tun2proxy` → the GST core's
 * local SOCKS5 → Apps Script relay. No Xray in the path.
 *
 * This replaces the old composite engine (which bridged TUN→SOCKS5 with an
 * extra Xray tun2socks hop). The Rust core's virtual-DNS / UDP / QUIC behaviour is
 * tuned for tun2proxy specifically, so this is the intended pairing.
 *
 * tun2proxy itself runs in the `:tun` process — see [Tun2proxyHostService] for why. That also
 * changed who owns the TUN descriptor: tun2proxy used to close it (`--close-fd-on-drop true`),
 * but across a process boundary it would only ever close the host's binder-duplicated copy,
 * leaving the original open here and the TUN up forever. So the flag is now false and this
 * engine closes the fd itself, exactly like AetherTunEngine.
 *
 * @param rawTunFd the RAW, already-detached fd from
 *   `VpnService.Builder.establish().detachFd()`. This engine is its sole owner and closes it
 *   once, in [stop].
 * @param mtu the MTU used on the VpnService Builder.
 */
class GstTunEngine(private val rawTunFd: Int, private val mtu: Int) : IVpnEngine {

    private val gstEngine = GstEngine()
    private val tun2proxyRunning = AtomicBoolean(false)

    /** Needed in [stop] to signal the `:tun` process. Captured in [start]. */
    private var appContext: Context? = null

    /** Cleared once closed, so a racing second stop() cannot close it twice. */
    private var ownedTunFd: Int = -1

    /**
     * Idempotency guard for stop(). MyVpnService calls currentEngine.stop() from BOTH the
     * ACTION_STOP path AND onDestroy() (and Android can revoke the VPN out-of-band) — these
     * run on different threads and can race. For the Xray engines a double stop is harmless,
     * but running the NATIVE tun2proxy stop + fd-close + mhrv runtime shutdown twice races two
     * threads through freed native state → a delayed native crash (surfaces in RenderThread as
     * "pthread_mutex_lock on a destroyed mutex" a few seconds later, GST-only). The reference
     * mhrv-rs MhrvVpnService guards the exact same teardown with an AtomicBoolean for this
     * reason. First caller wins; every later stop() is a no-op.
     */
    private val stopped = AtomicBoolean(false)

    override suspend fun start(context: Context, config: String, localPort: Int): Boolean {
        GstLog.i(TAG, "Starting GST full-tunnel engine (rawTunFd=$rawTunFd, mtu=$mtu, localPort=$localPort)")

        // Use the app's configured SOCKS port so the GST core's HTTP listener lands
        // on localPort+10000 — the exact address the app's real-IP / connectivity
        // status probe (NodesTab) checks. The old composite engine hard-coded 10809,
        // which left nothing on 20808 and made that probe hang 8s per attempt.
        val gstSocksPort = localPort

        // 1. Boot the GST core → local SOCKS5 on gstSocksPort. Owns
        //    Native.setDataDir + Native.startProxy internally.
        val gstStarted = gstEngine.start(context, config, gstSocksPort)
        if (!gstStarted) {
            GstLog.e(TAG, "GST core failed to start — aborting tunnel")
            return false
        }

        if (rawTunFd <= 0) {
            GstLog.e(TAG, "Invalid TUN fd ($rawTunFd) — cannot start tun2proxy")
            gstEngine.stop()
            return false
        }

        // 2. Bridge the TUN fd to the GST SOCKS5 via tun2proxy, running in the :tun process.
        //    Mirrors the reference MhrvVpnService cliArgs, minus udpgw (a FULL-mode feature;
        //    GST here is apps_script mode) and minus --tun-fd, which the host appends with its
        //    own descriptor number because fd numbers are process-local.
        val cliArgs = buildString {
            append("tun2proxy")
            append(" --proxy socks5://127.0.0.1:$gstSocksPort")
            append(" --dns virtual")
            append(" --verbosity info")
            // False, not true: see the class doc. tun2proxy would otherwise close only the
            // host's duplicate and leave the TUN up here forever.
            append(" --close-fd-on-drop false")
        }
        ownedTunFd = rawTunFd
        appContext = context.applicationContext
        if (!Tun2proxyClient.start(context, rawTunFd, cliArgs, mtu)) {
            GstLog.e(TAG, "Failed to start tun2proxy host process")
            gstEngine.stop()
            return false
        }
        tun2proxyRunning.set(true)

        GstLog.i(TAG, "GST full-tunnel engine started")
        return true
    }

    /**
     * Teardown order matters and mirrors the reference project's
     * MhrvVpnService.teardown() (which fixed a SIGSEGV — see their #700):
     *   1. Stop the GST core FIRST. Closing the SOCKS5 listener is what makes
     *      tun2proxy's worker thread's blocking native read return. Stopping in
     *      the other order would free the runtime while tun2proxy is still in a
     *      blocking read against it → use-after-free crash in native.
     *   2. Hand the tun2proxy teardown to the :tun process and do not wait for it. If the
     *      native core exits(255) on its way out, that process dies alone and the app does not
     *      notice — which is the whole reason it lives over there.
     *   3. Close our TUN descriptor.
     */
    override fun stop() {
        // Idempotency: the first stop() wins; a racing second call (STOP path vs onDestroy)
        // returns immediately so we never run the native tun2proxy/mhrv teardown twice.
        if (!stopped.compareAndSet(false, true)) {
            GstLog.d(TAG, "stop() already run — skipping duplicate teardown")
            return
        }
        GstLog.i(TAG, "Stopping GST full-tunnel engine")

        // 1. GST core first — releases the SOCKS5 socket tun2proxy blocks on.
        try { gstEngine.stop() } catch (t: Throwable) {
            GstLog.e(TAG, "gstEngine.stop threw: ${t.message}")
        }

        // 2. Signal the :tun process; the sequencing that used to live here now lives there,
        //    and so does any crash it produces.
        appContext?.let { Tun2proxyClient.stop(it) }
        tun2proxyRunning.set(false)

        // 3. Our TUN descriptor, closed exactly once. Adopting it into a ParcelFileDescriptor
        //    puts it under the platform's fd-ownership tracking, so a stray second close is
        //    reported rather than silently recycling a live descriptor.
        val fd = ownedTunFd
        ownedTunFd = -1
        if (fd > 0) {
            try {
                android.os.ParcelFileDescriptor.adoptFd(fd).close()
                GstLog.i(TAG, "TUN fd $fd closed")
            } catch (t: Throwable) {
                GstLog.w(TAG, "closing TUN fd $fd threw: ${t.message}")
            }
        }
        appContext = null
        GstLog.i(TAG, "GST full-tunnel engine stopped")
    }

    companion object {
        private const val TAG = "GstTunEngine"
    }
}
