package com.mlmvpn.core.warp

import android.content.Context
import android.util.Base64
import android.util.Log
import java.security.SecureRandom

/**
 * Shared Xray-core runner backed by the (new) libv2ray.aar API.
 *
 * The new aar renamed the entry class from `libv2ray.libv2ray` (lowercase)
 * to `libv2ray.Libv2ray` (capital L). The rest of the surface is unchanged:
 *   Libv2ray.initCoreEnv(path, xudpKey)
 *   Libv2ray.newCoreController(handler) -> CoreController
 *   CoreController.startLoop(configJson, fd)
 *   CoreController.stopLoop()
 */
internal object XrayCore {
    private fun randomXudpKey(): String {
        val keyBytes = ByteArray(32)
        SecureRandom().nextBytes(keyBytes)
        val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        return Base64.encodeToString(keyBytes, flags)
    }

    /**
     * [onLog]: where the core's status lines go -- the in-app log panel (GstLog) of the process
     * that asked for the core, so a user in Iran connecting a default config can see WHY it failed.
     */
    fun newController(context: Context, onLog: ((String) -> Unit)? = null): libv2ray.CoreController {
        libv2ray.Libv2ray.initCoreEnv(context.filesDir.absolutePath, randomXudpKey())
        val handler = object : libv2ray.CoreCallbackHandler {
            override fun onEmitStatus(status: Long, msg: String): Long {
                if (msg.isNotBlank()) {
                    val line = "status=$status $msg"
                    if (onLog != null) onLog(line) else com.mlmvpn.scanner.engines.gst.GstLog.i("XrayCore", line)
                }
                return 0
            }
            override fun shutdown(): Long = 0
            override fun startup(): Long = 0
        }
        return libv2ray.Libv2ray.newCoreController(handler).also { c ->
            // Per-app routing: a rule `"process": ["<uid>"]` asks this finder who owns each
            // connection. Harmless for every config without such a rule — nothing calls it.
            try { c.registerProcessFinder(UidProcessFinder(context.applicationContext)) } catch (_: Throwable) {}
        }
    }
}

/**
 * Which app owns a connection, for Xray's `process` routing rules (AndroidLibXrayLite's
 * ProcessFinder: the core turns the returned UID into the name it matches, so a rule lists UIDs).
 *
 * `getConnectionOwnerUid` answers only the app that owns the active VPN, only for connections on
 * that VPN, and only on Android 10+; everything else is -1 — "not found", which no rule matches, so
 * the connection simply takes the default route.
 */
private class UidProcessFinder(private val context: Context) : libv2ray.ProcessFinder {
    private val cm by lazy { context.getSystemService(android.net.ConnectivityManager::class.java) }

    override fun findProcessByConnection(network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long): Long {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return -1
        return try {
            val proto = if (network == "udp") android.system.OsConstants.IPPROTO_UDP else android.system.OsConstants.IPPROTO_TCP
            // Literals only (the core hands over addresses, never names), so no lookup happens here.
            val local = java.net.InetSocketAddress(java.net.InetAddress.getByName(srcIP), srcPort.toInt())
            val remote = java.net.InetSocketAddress(java.net.InetAddress.getByName(destIP), destPort.toInt())
            cm?.getConnectionOwnerUid(proto, local, remote)?.toLong() ?: -1
        } catch (_: Exception) { -1 }
    }
}

/**
 * The core itself, in this process. Only host processes run one ([XrayCoreHostService], the probe
 * and carrier services); a [VlessXrayInjector] in the main process hands its config to a host.
 */
internal class LocalXrayCore(private val fd: Int, private val onLog: ((String) -> Unit)? = null) {
    private var controller: libv2ray.CoreController? = null

    /** Copy geosite.dat / geoip.dat from assets into filesDir if missing (xray reads them from there). */
    private fun ensureGeoData(context: Context) {
        for (filename in listOf("geosite.dat", "geoip.dat")) {
            try {
                val destFile = java.io.File(context.filesDir, filename)
                if (destFile.exists() && destFile.length() >= 1000) continue
                // Through a temporary file: two host processes can copy at once, and a reader
                // must never see half a file.
                val temp = java.io.File(context.filesDir, "$filename.${android.os.Process.myPid()}.part")
                context.assets.open(filename).use { input ->
                    java.io.FileOutputStream(temp).use { output -> input.copyTo(output) }
                }
                if (!temp.renameTo(destFile)) temp.delete()
                com.mlmvpn.scanner.engines.gst.GstLog.i("VlessXrayInjector", "copied $filename into filesDir")
            } catch (e: Exception) {
                com.mlmvpn.scanner.engines.gst.GstLog.w("VlessXrayInjector", "ensureGeoData $filename failed: ${e.message}")
            }
        }
    }

    fun start(context: Context, config: String): Boolean = try {
        // Ensure geosite.dat / geoip.dat exist in filesDir before starting the core. Configs
        // that use geosite:/geoip: routing rules (e.g. the Iran default configs with
        // geosite:category-ir) otherwise fail with "failed to open geosite.dat: no such file".
        ensureGeoData(context)
        val c = XrayCore.newController(context, onLog)
        c.startLoop(config, fd)
        controller = c
        true
    } catch (e: Throwable) {
        com.mlmvpn.scanner.engines.gst.GstLog.e("VlessXrayInjector", "failed to start xray core: ${e.message}")
        Log.e("VlessXrayInjector", "Failed to start Xray core", e)
        try { controller?.stopLoop() } catch (_: Throwable) {}
        controller = null
        false
    }

    fun stop() {
        try { controller?.stopLoop() } catch (e: Throwable) {
            Log.e("VlessXrayInjector", "Error stopping Xray core", e)
        }
        controller = null
    }

    /** Down plus up through [tag] since the last read (the core resets its counters on a read). */
    fun traffic(tag: String): Long {
        val c = controller ?: return 0L
        return runCatching { c.queryStats(tag, "downlink") + c.queryStats(tag, "uplink") }.getOrDefault(0L)
    }
}

/**
 * Runs a fully-formed Xray JSON config through the core.
 * `fd` is the protected tun file descriptor for VPN mode, or 0 for proxy-only.
 *
 * In the main process the core runs in a host process ([XrayHosts]): the VPN's in `:xtun`, test
 * cores in `:xtest` (or the [host] the caller names), so a Go panic ends that process and not the
 * app. In a host process it runs right here.
 */
class VlessXrayInjector(
    private val fd: Int,
    private val host: XrayHosts.Host? = null,
    /** A throwaway test core: nothing of it goes to the in-app log (one per node would drown it). */
    private val quiet: Boolean = false,
) : IVpnEngine {
    private var local: LocalXrayCore? = null
    private var remote: XrayHosts.RemoteCore? = null
    private var appContext: Context? = null

    companion object {
        /** The VPN's core (the one in the tunnel host) while it is up, for its traffic counters. */
        @Volatile private var tunnel: XrayHosts.RemoteCore? = null

        /**
         * Told when the VPN's core ends on its own (its process died: an Xray panic). Before, that
         * panic closed the whole app; now the VPN's owner can reconnect or report the drop.
         */
        @Volatile var onTunnelLost: (() -> Unit)? = null

        /** Bytes each outbound tag of the VPN's core moved since the last read; null with no tunnel up. Blocks: off the main thread. */
        fun tunnelStats(context: Context, tags: List<String>): Map<String, Long>? = tunnel?.stats(context.applicationContext, tags)
    }

    override suspend fun start(context: Context, raw: String, localPort: Int): Boolean {
        // Every core of the app starts here (the tunnel, the test cores, the WARP carrier): WireGuard's
        // junk goes through a loopback door, never a dialerProxy, so stopping the core cannot panic it.
        val config = NoiseDoor.rewrite(raw)
        if (!quiet) {
            val remarks = try { org.json.JSONObject(config).optString("remarks") } catch (_: Exception) { "" }
            com.mlmvpn.scanner.engines.gst.GstLog.i(
                "VlessXrayInjector",
                "starting xray core (fd=$fd, ${config.length} chars${if (remarks.isNotBlank()) ", remarks=$remarks" else ""})"
            )
        }
        val app = context.applicationContext
        appContext = app
        // A second start without a stop (a scanner reusing one injector per batch) replaces the
        // core instead of leaving the first one running with nothing pointing at it.
        if (local != null || remote != null) stop()
        val ok = if (XrayHosts.inMainProcess(app)) {
            // The VPN's own core (MyVpnService names the tunnel host; a tun means it is that core
            // too) -- also in proxy mode, where it has no tun but is still what the user is on.
            val where = host ?: if (fd > 0) XrayHosts.TUNNEL else XrayHosts.TEST
            val isTunnel = where === XrayHosts.TUNNEL
            val lost: (() -> Unit)? = if (isTunnel) ({ onTunnelLost?.invoke() }) else null
            val r = XrayHosts.RemoteCore(where, fd, lost, log = !quiet)
            val started = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { r.start(app, config) }
            if (started) {
                remote = r
                if (isTunnel) tunnel = r
            }
            started
        } else {
            val c = LocalXrayCore(fd, if (quiet) ({ _ -> }) else ({ line -> com.mlmvpn.scanner.engines.gst.GstLog.i("XrayCore", line) }))
            c.start(app, config).also { if (it) local = c }
        }
        if (!quiet) com.mlmvpn.scanner.engines.gst.GstLog.i("VlessXrayInjector", if (ok) "xray core started OK (fd=$fd)" else "xray core did not start (fd=$fd)")
        return ok
    }

    override fun stop() {
        local?.stop()
        local = null
        val r = remote ?: return
        remote = null
        if (tunnel === r) tunnel = null
        val app = appContext ?: return
        // A round trip to the host, which can wait on the core's stop for seconds: never on the
        // main thread. The tun is the host's own copy, so the caller can close its interface now.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            Thread({ r.stop(app) }, "xray-stop").apply { isDaemon = true }.start()
        } else {
            r.stop(app)
        }
    }
}
