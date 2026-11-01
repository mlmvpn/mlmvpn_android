package com.mlmvpn.scanner.engines.windscribe

import android.content.Context
import android.net.VpnService
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.mlmvpn.core.warp.AwgHostClient
import com.mlmvpn.scanner.openvpn.WsProxy
import kotlinx.coroutines.*
import java.io.File
import java.net.ServerSocket

/** Reuses the existing bounded Messenger client; all calls run on the VPN's IO worker. */
class WsProxyClient(private val service: VpnService, private val onFailure: () -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var closed = false
    fun start(spec: WsProxy): Int {
        check(available(service))
        val port = ServerSocket(0).use { it.localPort }
        host.onDied = { if (!closed) onFailure() }
        val reply = host.call(service, WsProxyService.START, Bundle().apply {
            putInt("port", port); putString("remote", spec.remote); putInt("type", if (spec.webSocket) 1 else 2)
            putBoolean("padding", spec.padding); putString("sni", spec.sni)
        }, 5_000)
        if (reply?.getBoolean("ok") != true) { close(); throw WsException(WsError.CORE) }
        scope.launch {
            while (isActive && !closed) {
                val state = host.call(service, WsProxyService.SOCKET, null, 2_000)
                @Suppress("DEPRECATION") val fd = state?.getParcelable<ParcelFileDescriptor>("socket")
                fd?.use { if (!service.protect(it.fd) && !closed) onFailure() }
                if (state?.getBoolean("running") != true && !closed) { onFailure(); break }
                delay(100)
            }
        }
        return port
    }
    fun close() {
        if (closed) return
        closed = true; scope.cancel(); host.onDied = null
        host.call(service, WsProxyService.STOP, null, 2_000)
        host.release(service)
    }
    companion object {
        private val host = AwgHostClient(WsProxyService::class.java, "WsProxyClient")
        fun available(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libwsproxy.so").isFile
        fun nativeSmoke(context: Context): Boolean {
            val reply = host.call(context, WsProxyService.CHECK, null, 8_000)
            val ok = reply?.getBoolean("ok") == true && reply.getInt("pid") != android.os.Process.myPid()
            host.call(context, WsProxyService.STOP, null, 2_000); host.release(context)
            return ok
        }
    }
}
