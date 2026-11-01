package com.mlmvpn.scanner.utils

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.io.File

/**
 * What is left working after the user has gone. Android kills a backgrounded app whose CPU use
 * stays high («EXCESSIVE_RESOURCE_USAGE … importance 400», 1.2.42: about eighty reports, most
 * «Caused by child process»): an engine's own process (Lantern, Tor, Geph, URnetwork, a WARP leg)
 * or a test process (`:pstest*`, `:xmeasure`, `:xtest`, `:maeprobe`, `:xprobe`) still running for a
 * screen nobody is looking at, with no VPN up. The kill then took the whole app with it.
 *
 * Two minutes after the last screen closes, if no foreground service of ours is running outside
 * `:chat` (no tunnel, no game boost, no Scout run: those keep their notification and their work),
 * this stops the IP scan and ends every child process of this app and every helper process but the
 * main one and `:chat`. Coming back before then cancels it. Nothing runs while a VPN is up.
 */
object BackgroundGuard {
    private const val TAG = "BackgroundGuard"
    private const val GRACE_MS = 120_000L

    @Volatile private var generation = 0

    fun onForeground() {
        generation++
    }

    fun onBackground(context: Context) {
        val app = context.applicationContext
        val gen = ++generation
        Thread({
            try {
                Thread.sleep(GRACE_MS)
            } catch (e: InterruptedException) {
                return@Thread
            }
            if (gen != generation) return@Thread
            runCatching { sweep(app) }.onFailure { Log.w(TAG, "sweep: ${it.javaClass.simpleName}") }
        }, "background-guard").apply { isDaemon = true }.start()
    }

    private fun sweep(context: Context) {
        if (foregroundServiceRunning(context) || vpnUp(context)) return
        runCatching {
            if (com.mlmvpn.scanner.data.ScannerManager.isScanning.value) com.mlmvpn.scanner.data.ScannerManager.stopScan()
        }
        val children = killChildProcesses(context)
        val helpers = killHelperProcesses(context)
        if (children + helpers > 0) Log.i(TAG, "app in background, no VPN: ended $children child and $helpers helper processes")
    }

    /** A foreground service of ours in any process but `:chat` (Gereh keeps its own). */
    private fun foregroundServiceRunning(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val services = runCatching { am.getRunningServices(Int.MAX_VALUE) }.getOrNull() ?: return true
        val chat = "${context.packageName}:chat"
        return services.any { it.foreground && it.service.packageName == context.packageName && it.process != chat }
    }

    /** Any VPN up on the phone: a tunnel of ours may carry it even without a notification. */
    private fun vpnUp(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        @Suppress("DEPRECATION")
        return runCatching {
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }
        }.getOrDefault(true)
    }

    /** Native executables this app started (any process of our uid that is not an app process). */
    private fun killChildProcesses(context: Context): Int {
        val uid = android.os.Process.myUid()
        val me = android.os.Process.myPid()
        var n = 0
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            if (pid == me) return@forEach
            val status = runCatching { File(dir, "status").readText() }.getOrNull() ?: return@forEach
            val owner = Regex("""(?m)^Uid:\s+(\d+)""").find(status)?.groupValues?.get(1)?.toIntOrNull()
            if (owner != uid) return@forEach
            val cmd = runCatching { File(dir, "cmdline").readText().substringBefore('\u0000') }.getOrNull() ?: return@forEach
            if (cmd.startsWith(context.packageName)) return@forEach // an app process: below
            android.os.Process.killProcess(pid)
            n++
        }
        return n
    }

    /** Helper app processes (test and engine hosts); never the main process or `:chat`. */
    private fun killHelperProcesses(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val keep = setOf(context.packageName, "${context.packageName}:chat")
        var n = 0
        am.runningAppProcesses?.forEach { p ->
            if (p.processName.startsWith(context.packageName) && p.processName !in keep && p.pid != android.os.Process.myPid()) {
                android.os.Process.killProcess(p.pid)
                n++
            }
        }
        return n
    }
}
