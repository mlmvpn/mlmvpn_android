package com.mlmvpn.core.warp

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Where the app's Xray cores run: never in the main process.
 *
 * WHY
 * ---
 * A Go panic cannot be caught, and Xray panics now and then -- at a core's stop ("close of closed
 * channel"), on some public Shadowsocks and WireGuard servers, after its tun descriptor is closed
 * under it. In the main process each one closed the whole app: the top crash of 1.2.41 ("native
 * crash SIGABRT in libgojni.so", well over a thousand reports) came from the VPN's own core on
 * Outline, Windscribe and Amnezia connects, MAE's probe core on opening MAE, and the test cores of
 * FLUX, the node list and Quick Connect. Leftover goroutines of a stopped core also kept the
 * process busy after the app went to the background, and the system killed it for CPU use.
 *
 * Here a [VlessXrayInjector] made in the main process runs its core in a host process instead
 * ([XrayCoreHostService]); a panic ends that process only. The VPN's core gets the tun descriptor
 * as a [ParcelFileDescriptor] of its own, so the main process and the core never share (and never
 * double-close) one descriptor. A host whose last core stops ends itself, which takes any
 * goroutine the core left behind with it. Cores made in a host process run right there.
 */
object XrayHosts {
    private const val TAG = "XrayHosts"
    private const val START_TIMEOUT_MS = 25_000L
    private const val STOP_TIMEOUT_MS = 8_000L

    /** A host process and the main process's handle on it. */
    class Host(service: Class<out XrayCoreHostService>, tag: String) {
        internal val client = AwgHostClient(service, tag)
        internal val live: MutableSet<RemoteCore> = ConcurrentHashMap.newKeySet()

        init {
            client.onDied = {
                // Every core it ran is gone with it; the tunnel's owner hears about it. A copy
                // through toArray: toList() read a size of one and then found the set empty when a
                // core was removed at the same moment -- NoSuchElementException (1.2.42 #2018).
                val gone = ArrayList(live)
                live.clear()
                gone.forEach { it.lost() }
            }
        }
    }

    // Lazily: a host process asks [inMainProcess] too, and has no use for the clients' threads.

    /** The VPN's own core (MyVpnService). */
    val TUNNEL by lazy { Host(XrayTunnelHostService::class.java, "XrayTunnelHost") }
    /** MAE's probe core: a background engine, kept apart from tests that may take their process down. */
    val MAE by lazy { Host(XrayMaeHostService::class.java, "XrayMaeHost") }
    /** Every test core: FLUX, the node list, the WireGuard scanner, Windscribe's tests, country and platform checks. */
    val TEST by lazy { Host(XrayTestHostService::class.java, "XrayTestHost") }

    @Volatile private var mainProcess: Boolean? = null

    /** True in the app's main process, where no core may run. */
    fun inMainProcess(context: Context): Boolean = mainProcess ?: run {
        val app = context.applicationContext
        val name = if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName() else runCatching {
            java.io.File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '\u0000' }
        }.getOrNull()
        (name == null || name == app.packageName).also { mainProcess = it }
    }

    private val seq = AtomicLong()
    private val logThread by lazy { HandlerThread("xray-host-log").apply { start() } }
    /** The hosts' core status lines, into the in-app log like an in-process core's. */
    private val logSink by lazy {
        Messenger(Handler(logThread.looper) { m ->
            m.data?.getString(XrayCoreHostService.KEY_LINE)?.let { com.mlmvpn.scanner.engines.gst.GstLog.i("XrayCore", it) }
            true
        })
    }

    /** One core in a host. Every call blocks and must run off the main thread. */
    class RemoteCore(private val host: Host, private val fd: Int, private val onLost: (() -> Unit)?, private val log: Boolean = true) {
        private val id = "core-${android.os.Process.myPid()}-${seq.incrementAndGet()}"
        @Volatile private var up = false

        fun start(context: Context, config: String): Boolean {
            // The core's own copy of the tun: Binder hands the host a descriptor of its own, and this
            // dup is closed again here, so the main process's interface stays the only one it owns.
            val tun = if (fd > 0) runCatching { ParcelFileDescriptor.fromFd(fd) }.getOrElse {
                Log.w(TAG, "no tun to hand over: ${it.message}"); return false
            } else null
            try {
                val data = Bundle().apply {
                    putString(XrayCoreHostService.KEY_ID, id)
                    putString(XrayCoreHostService.KEY_CONFIG, config)
                    tun?.let { putParcelable(XrayCoreHostService.KEY_TUN, it) }
                    if (log) putParcelable(XrayCoreHostService.KEY_LOG, logSink)
                }
                // Registered before the call: a host that dies while starting this core reports it.
                host.live += this
                repeat(2) {
                    val r = host.client.callDetailed(context, XrayCoreHostService.MSG_START, data, START_TIMEOUT_MS)
                    r.reply?.let { reply ->
                        up = reply.getBoolean(XrayCoreHostService.KEY_OK)
                        if (!up) host.live -= this
                        return up
                    }
                    // The host ended under the call: its idle exit racing this start, or the config
                    // took it down. Once more, in a fresh process; a second death is the config's.
                    if (!r.died) { host.live -= this; return false }
                    host.live += this
                }
                host.live -= this
                return false
            } finally {
                runCatching { tun?.close() }
            }
        }

        fun stop(context: Context) {
            host.live -= this
            if (!up) return
            up = false
            runCatching {
                host.client.call(context, XrayCoreHostService.MSG_STOP, Bundle().apply { putString(XrayCoreHostService.KEY_ID, id) }, STOP_TIMEOUT_MS)
            }
        }

        /** Bytes each outbound tag moved since the last read (the counters reset on every read); null when the core is not up. */
        fun stats(context: Context, tags: List<String>): Map<String, Long>? {
            if (!up || tags.isEmpty()) return null
            val reply = host.client.call(context, XrayCoreHostService.MSG_STATS, Bundle().apply {
                putString(XrayCoreHostService.KEY_ID, id)
                putStringArray(XrayCoreHostService.KEY_TAGS, tags.toTypedArray())
            }, 3_000) ?: return null
            val bytes = reply.getLongArray(XrayCoreHostService.KEY_BYTES) ?: return null
            return tags.indices.associate { tags[it] to (bytes.getOrNull(it) ?: 0L) }
        }

        internal fun lost() {
            if (!up) return
            up = false
            Log.w(TAG, "the core's host process died under it ($id)")
            com.mlmvpn.scanner.engines.gst.GstLog.w("XrayCore", "the core's process ended (Xray stopped on its own)")
            runCatching { onLost?.invoke() }
        }
    }
}

/**
 * A process that runs Xray cores for the main process ([XrayHosts]). Each core is keyed by the id
 * its owner gave it; start and stop run on threads of their own (a stop can take seconds while
 * connections drain), so one slow core never holds up another. When the last core stops, the
 * process ends: a stopped core can leave goroutines behind, and a stop is where Xray panics.
 */
open class XrayCoreHostService : Service() {

    companion object {
        const val MSG_START = 1
        const val MSG_STOP = 2
        const val MSG_STATS = 3
        const val MSG_LOG = 4
        const val KEY_ID = "id"
        const val KEY_CONFIG = "config"
        const val KEY_TUN = "tun"
        const val KEY_LOG = "log"
        const val KEY_LINE = "line"
        const val KEY_OK = "ok"
        const val KEY_TAGS = "tags"
        const val KEY_BYTES = "bytes"
    }

    private class Entry(val core: LocalXrayCore)

    private val cores = ConcurrentHashMap<String, Entry>()
    /** Starts and stops in flight: the process does not end under one. */
    private val busy = AtomicInteger(0)
    private val worker = HandlerThread("xray-host").apply { start() }
    private val main = Handler(worker.looper)
    private val jobs = Executors.newCachedThreadPool { r -> Thread(r, "xray-host-job").apply { isDaemon = true } }

    private val messenger = Messenger(Handler(worker.looper) { msg ->
        val data = msg.data ?: Bundle()
        data.classLoader = ParcelFileDescriptor::class.java.classLoader
        val replyTo = msg.replyTo
        val id = data.getString(KEY_ID)
        if (id == null) { reply(replyTo, Bundle()); return@Handler true }
        when (msg.what) {
            MSG_START -> {
                busy.incrementAndGet()
                val config = data.getString(KEY_CONFIG)
                @Suppress("DEPRECATION") val tun = data.getParcelable<ParcelFileDescriptor>(KEY_TUN)
                @Suppress("DEPRECATION") val log = data.getParcelable<Messenger>(KEY_LOG)
                jobs.execute {
                    try {
                        cores.remove(id)?.let { stopEntry(it) }
                        // The core owns its descriptor outright: detached, nothing here closes it
                        // again after the core has (a double close is how a tun fd ended up closing
                        // somebody else's file). Whatever the core leaves open goes with the process,
                        // which ends once its last core stops.
                        val fd = tun?.let { runCatching { it.detachFd() }.getOrDefault(0) } ?: 0
                        val core = LocalXrayCore(fd) { line ->
                            runCatching { log?.send(Message.obtain(null, MSG_LOG).apply { this.data = Bundle().apply { putString(KEY_LINE, line) } }) }
                        }
                        val ok = config != null && (tun == null || fd > 0) && core.start(applicationContext, config)
                        if (ok) cores[id] = Entry(core) else core.stop()
                        reply(replyTo, Bundle().apply { putBoolean(KEY_OK, ok) })
                    } catch (t: Throwable) {
                        reply(replyTo, Bundle().apply { putBoolean(KEY_OK, false) })
                    } finally {
                        busy.decrementAndGet()
                        main.post { exitIfIdle() }
                    }
                }
            }
            MSG_STOP -> {
                busy.incrementAndGet()
                jobs.execute {
                    try {
                        cores.remove(id)?.let { stopEntry(it) }
                    } finally {
                        reply(replyTo, Bundle().apply { putBoolean(KEY_OK, true) })
                        busy.decrementAndGet()
                        main.post { exitIfIdle() }
                    }
                }
            }
            MSG_STATS -> {
                val tags = data.getStringArray(KEY_TAGS) ?: emptyArray()
                val core = cores[id]?.core
                reply(replyTo, Bundle().apply { putLongArray(KEY_BYTES, LongArray(tags.size) { core?.traffic(tags[it]) ?: 0L }) })
            }
            else -> reply(replyTo, Bundle())
        }
        true
    })

    private fun stopEntry(e: Entry) {
        runCatching { e.core.stop() }
    }

    private fun reply(to: Messenger?, data: Bundle) {
        runCatching { to?.send(Message.obtain(null, AwgHostService.MSG_REPLY).apply { this.data = data }) }
    }

    /** On the message thread, after every start and stop: no core and nothing in flight ends the process. */
    private fun exitIfIdle() {
        if (cores.isEmpty() && busy.get() == 0) android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        // Unbound with cores still up (the main process went away): they go with the process.
        worker.quitSafely()
        super.onDestroy()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

/** The VPN's core (`:xtun`). */
class XrayTunnelHostService : XrayCoreHostService()

/** MAE's probe core (`:maeprobe`). */
class XrayMaeHostService : XrayCoreHostService()

/** The app's test cores (`:xtest`). */
class XrayTestHostService : XrayCoreHostService()
