package com.mlmvpn.scanner.engines.scout

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.mlmvpn.core.warp.AwgHostClient
import com.mlmvpn.scanner.store.tr
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal object ScoutTunnelHost {
    private val client by lazy { AwgHostClient(TunnelProbeService::class.java, "ScoutTunnelClient") }
    private val lock = Mutex()

    suspend fun <T> run(kit: ScoutKit, token: String, config: JSONObject, beforeStart: () -> Unit, afterStop: () -> Unit, block: suspend (Int) -> T,
                        verifyMs: Long = 210_000): Served<T> =
        withContext(Dispatchers.IO) { lock.withLock {
            var pid = 0
            fun args() = Bundle().apply { putString(TunnelProbeService.TOKEN, token) }
            try {
                val opened = runInterruptible {
                    client.call(kit.context, TunnelProbeService.OPEN, args(), 5_000)?.also {
                        if (it.getBoolean(TunnelProbeService.OK) && it.getString(TunnelProbeService.TOKEN) == token)
                            pid = it.getInt(TunnelProbeService.PID)
                    }
                }
                if (pid <= 0 || opened?.getBoolean(TunnelProbeService.OK) != true)
                    return@withLock Served.Failed(tr("پردازش تست آماده نشد", "The test process did not become available"), true)
                beforeStart()
                val started = runInterruptible { client.call(kit.context, TunnelProbeService.START,
                    args().apply { putString(TunnelProbeService.CONFIG, config.toString()) }, 5_000) }
                if (started?.getBoolean(TunnelProbeService.OK) != true)
                    return@withLock Served.Failed(tr("هستهٔ تست شروع نشد", "The test core did not start"), true)
                val port = config.getString("listen").substringAfterLast(':').toInt()
                var failed = false
                val verified = withTimeoutOrNull(verifyMs) {
                    while (true) {
                        val status = runInterruptible { client.call(kit.context, TunnelProbeService.STATUS, args(), 2_000) }
                        if (status?.getBoolean(TunnelProbeService.OK) != true || status.getString(TunnelProbeService.PHASE) in listOf("failed", "stopped")) {
                            failed = true; return@withTimeoutOrNull false
                        }
                        kit.step(if (status.getString(TunnelProbeService.PHASE) == "preparing") Step.PREPARING else Step.CONNECTING)
                        if (runInterruptible { ScoutNet.warpVerified(port, 5_000) }) return@withTimeoutOrNull true
                        delay(1_000)
                    }
                    @Suppress("UNREACHABLE_CODE") false
                } == true
                if (verified) Served.Ok(block(port)) else Served.Failed(
                    tr("عبور داده از هستهٔ تونل تأیید نشد", "Tunnel-core data transfer was not verified"), failed)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        if (pid > 0) runCatching { client.call(kit.context, TunnelProbeService.STOP, args(), 1_500) }
                    } finally { client.release(kit.context) }
                    // Cancellation while binding can arrive before OPEN replies with the pid.
                    // This host's mutex owns the dedicated process even during that interval.
                    if (pid <= 0) {
                        val manager = kit.context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        pid = manager.runningAppProcesses?.firstOrNull { process ->
                            ScoutTunnelPolicy.mayKill(process.pid, android.os.Process.myPid(), process.uid,
                                android.os.Process.myUid(), process.processName, kit.context.packageName)
                        }?.pid ?: 0
                    }
                    if (pid > 0) {
                        if (!awaitGone(pid, 1_500)) {
                            val manager = kit.context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                            manager.runningAppProcesses?.firstOrNull { it.pid == pid }?.let { process ->
                                if (ScoutTunnelPolicy.mayKill(pid, android.os.Process.myPid(), process.uid,
                                        android.os.Process.myUid(), process.processName, kit.context.packageName))
                                    android.os.Process.killProcess(pid)
                            }
                        }
                        if (!awaitGone(pid, 2_000)) {
                            ScoutEngine.stop(EndReason.INTERRUPTED)
                            error("probe_cleanup_incomplete")
                        }
                    }
                    // Files are only touched once every native thread in the child has ended.
                    afterStop()
                }
            }
        } }

    private fun alive(pid: Int): Boolean = try { Os.kill(pid, 0); true }
        catch (e: ErrnoException) { e.errno != OsConstants.ESRCH }

    private suspend fun awaitGone(pid: Int, withinMs: Long): Boolean {
        val until = android.os.SystemClock.elapsedRealtime() + withinMs
        while (alive(pid) && android.os.SystemClock.elapsedRealtime() < until) delay(50)
        return !alive(pid)
    }
}
