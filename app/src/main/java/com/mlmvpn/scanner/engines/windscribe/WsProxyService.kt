package com.mlmvpn.scanner.engines.windscribe

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.*
import com.windscribe.vpn.backend.openvpn.WSTunnelLib

/** Isolates the official Go proxy from the existing Xray and Amnezia Go runtimes. */
class WsProxyService : Service() {
    private val worker = HandlerThread("windscribe-proxy-ipc").apply { start() }
    private var lib: WSTunnelLib? = null
    @Volatile private var proxy: Thread? = null
    private var localPort = 0
    private val messenger = Messenger(Handler(worker.looper) { msg -> handle(msg); true })
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    private fun load(): WSTunnelLib {
        lib?.let { return it }
        System.loadLibrary("wsproxy")
        return WSTunnelLib().also { it.initialise(false, "/dev/null"); lib = it }
    }
    private fun handle(msg: Message) {
        val out = Bundle()
        try {
            when(msg.what) {
                CHECK -> { load(); out.putInt("pid", Process.myPid()); out.putBoolean("ok", true) }
                START -> {
                    check(proxy == null)
                    val native = load()
                    val data = msg.data
                    localPort = data.getInt("port"); require(localPort in 1..65535)
                    val remote = data.getString("remote") ?: error("missing endpoint")
                    require(remote.startsWith("https://") || remote.startsWith("wss://"))
                    proxy = Thread({ runCatching { native.startProxy("127.0.0.1:$localPort", remote, data.getInt("type"), 1280,
                        data.getBoolean("padding"), data.getString("sni").orEmpty()) } }, "windscribe-proxy").apply { isDaemon = true; start() }
                    out.putBoolean("ok", true)
                }
                SOCKET -> {
                    out.putBoolean("running", proxy?.isAlive == true)
                    val fd = lib?.socketFd() ?: -1
                    if (fd >= 0) runCatching { out.putParcelable("socket", ParcelFileDescriptor.fromFd(fd)) }
                }
                STOP -> {
                    // A fresh process also clears native socket/global state for the next owner.
                    out.putBoolean("ok", true)
                    Thread({ runCatching { lib?.stop() } }, "windscribe-proxy-stop").apply { isDaemon = true; start() }
                    Handler(Looper.getMainLooper()).postDelayed({ stopSelf(); killWorker() }, 100)
                }
            }
        } catch (_: Throwable) { out.putBoolean("ok", false) }
        try { msg.replyTo?.send(Message.obtain(null, 100).apply { data = out }) }
        finally { @Suppress("DEPRECATION") (out.getParcelable<ParcelFileDescriptor>("socket"))?.close() }
    }
    private fun killWorker() {
        val name = (getSystemService(ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }?.processName
        if (name == "$packageName:wsproxy") Process.killProcess(Process.myPid())
    }
    override fun onDestroy() {
        worker.quitSafely(); super.onDestroy(); killWorker()
    }
    companion object { const val START=1; const val SOCKET=2; const val STOP=3; const val CHECK=4 }
}
