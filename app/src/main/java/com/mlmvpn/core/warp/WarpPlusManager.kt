package com.mlmvpn.core.warp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Second WARP engine: runs the bundled `warp-plus` client (github.com/bepass-org/warp-plus) as a
 * local process exposing a SOCKS5 proxy, exactly like [MasqueManager] runs usque and
 * [com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager] runs its binary.
 *
 * Why a second engine: usque/MASQUE is dead on some operators (registration only hands out the
 * DPI-black-holed 162.159.198.x range and the pinned pub_key blocks repointing -- proven). warp-plus
 * takes a completely different approach: plain WireGuard-over-UDP but with a built-in **endpoint
 * scanner** (`--scan`) that probes many Cloudflare WARP IP/port combos to find one this network
 * actually passes, plus optional "warp-in-warp" (`--gool`) and Psiphon (`--cfon`) obfuscation. On a
 * network where MASQUE fails but scanned WireGuard survives (or vice-versa), the
 * [WarpEngineSelector] picks whichever actually moves data.
 *
 * Binary: ship `warp-plus` compiled for Android as `libwarpplus.so` in jniLibs per ABI (same as
 * librstaspoof.so / libusque.so). warp-plus self-registers (no separate register step) and stores
 * its identity in --cache-dir.
 *
 * CLI (bepass-org/warp-plus): `warp-plus --bind 127.0.0.1:<port> --scan --cache-dir <dir> -4`
 * Flags live in constants below so they're trivial to match to the shipped build.
 */
object WarpPlusManager {

    private const val TAG = "WarpPlusManager"
    private const val LISTEN_HOST = "127.0.0.1"
    // Distinct from MasqueManager's 10810 so both engines can run/be probed independently.
    const val SOCKS_PORT = 10811
    private const val BINARY_NAME = "libwarpplus.so"
    private const val CACHE_DIR_NAME = "warpplus"
    // Psiphon egress country for --cfon mode. Nearby = lower ping; must be a country Psiphon actually
    // serves. Germany/Netherlands/Austria are reliable and ~60-90ms from Iran.
    private const val CFON_COUNTRY = "DE"

    private var process: Process? = null
    private val lifecycleMutex = Mutex()
    val isRunningFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

    private fun binaryFile(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    private fun cacheDir(context: Context): File =
        File(context.filesDir, CACHE_DIR_NAME).apply { if (!exists()) mkdirs() }

    fun isAvailable(context: Context): Boolean = binaryFile(context).exists()

    fun start(context: Context): Boolean = runBlocking {
        lifecycleMutex.withLock { startLocked(context) }
    }

    private fun startLocked(context: Context): Boolean {
        stopLocked()
        val bin = binaryFile(context)
        if (!bin.exists()) {
            Log.e(TAG, "FATAL: warp-plus binary not found at ${bin.absolutePath} -- ship libwarpplus.so in jniLibs")
            return false
        }

        val logFile = File(context.filesDir, "warpplus.log")
        try { logFile.writeText("[${System.currentTimeMillis()}] Starting WARP-Plus...\n") } catch (_: Exception) {}

        return try {
            // --cfon (Psiphon-over-WARP): PROVEN necessary on this operator. Plain scanned WireGuard
            //   ("normal"/"--scan" mode) finds reachable IPs but the WireGuard HANDSHAKE itself is
            //   DPI-blocked ("context deadline exceeded" after 40s), same as native WARP. --cfon wraps
            //   the tunnel in Psiphon's anti-DPI obfuscation, which is built for exactly this. Heavier
            //   and higher-latency, but it actually gets through.
            // --country: Psiphon egress; nearby = lower ping (see CFON_COUNTRY).
            // -4: force IPv4.
            // NOTE: --scan is for finding raw WARP endpoints; it's not used with --cfon (Psiphon picks
            //   the path). --gool (warp-in-warp) still needs the blocked WG handshake, so it's skipped.
            val args = arrayOf(
                bin.absolutePath,
                "--bind", "$LISTEN_HOST:$SOCKS_PORT",
                "--cfon",
                "--country", CFON_COUNTRY,
                "--cache-dir", cacheDir(context).absolutePath,
                "-4"
            )
            Log.d(TAG, "Executing: ${args.joinToString(" ")}")
            logFile.appendText("CMD: ${args.joinToString(" ")}\n")

            val proc = ProcessBuilder(*args).redirectErrorStream(true).start()
            process = proc

            // CRITICAL: start draining stdout IMMEDIATELY. warp-plus is very verbose during its
            // ~10s endpoint scan; if we don't read stdout the OS pipe buffer (~64KB) fills and
            // warp-plus BLOCKS on write -- it never finishes scanning or binds the SOCKS port. (This
            // is why an earlier 15s wait saw the port never open.) Read first, wait second.
            startLogReader(proc, logFile)

            // --cfon (Psiphon) bootstrap is slow: it has to find a working Psiphon server through the
            // same DPI first, then bring up WARP over it. Give it a generous 90s budget; poll the port.
            val portOpen = waitForPort(LISTEN_HOST, SOCKS_PORT, totalWaitMs = 90000)

            val alive = try { proc.exitValue(); false } catch (_: IllegalThreadStateException) { true }
            if (!alive) {
                Log.e(TAG, "warp-plus exited (code ${try { proc.exitValue() } catch (_: Exception) { "?" }}) before binding SOCKS")
                isRunningFlow.value = false
                return false
            }

            Log.d(TAG, "SOCKS port $SOCKS_PORT open: $portOpen")
            logFile.appendText("Port $LISTEN_HOST:$SOCKS_PORT: ${if (portOpen) "OPEN" else "CLOSED"}\n")

            isRunningFlow.value = alive && portOpen
            alive && portOpen
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start warp-plus", e)
            isRunningFlow.value = false
            false
        }
    }

    /** Poll until the SOCKS port accepts a connection or the budget expires. */
    private fun waitForPort(host: String, port: Int, totalWaitMs: Int): Boolean {
        val deadline = System.currentTimeMillis() + totalWaitMs
        while (System.currentTimeMillis() < deadline) {
            if (isPortOpen(host, port, 500)) return true
            try { Thread.sleep(300) } catch (_: InterruptedException) { return false }
        }
        return isPortOpen(host, port, 500)
    }

    private fun startLogReader(proc: Process, logFile: File) {
        Thread {
            try {
                proc.inputStream?.bufferedReader()?.useLines { lines ->
                    lines.forEach { line ->
                        Log.d(TAG, "warp-plus: $line")
                        try { logFile.appendText("$line\n") } catch (_: Exception) {}
                    }
                }
            } catch (_: java.io.InterruptedIOException) {
            } catch (e: java.io.IOException) {
                // "Stream closed" is expected when we destroyForcibly() the process; not an error.
                if (e.message?.contains("Stream closed", true) != true) Log.w(TAG, "warp-plus read: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error reading warp-plus output", e)
            } finally {
                val code = try { proc.waitFor() } catch (_: Exception) { null }
                if (process === proc) isRunningFlow.value = false
                Log.d(TAG, "warp-plus exited with code $code")
            }
        }.start()
    }

    fun stop() = runBlocking {
        lifecycleMutex.withLock { stopLocked() }
    }

    private fun stopLocked() {
        try {
            process?.destroyForcibly()
            process = null
            isRunningFlow.value = false
            Log.d(TAG, ">>> Stopped warp-plus process")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping warp-plus", e)
        }
    }

    fun ensureRunning(context: Context): Boolean = runBlocking {
        lifecycleMutex.withLock {
            if (isRunningFlow.value && isProcessAlive() && isPortOpen(LISTEN_HOST, SOCKS_PORT, 500)) {
                return@withLock true
            }
            startLocked(context)
        }
    }

    fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (e: Exception) {
        false
    }

    fun isProcessAlive(): Boolean = try {
        process?.exitValue(); false
    } catch (_: IllegalThreadStateException) {
        true
    } catch (_: Exception) {
        false
    }
}
