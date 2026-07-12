package com.mlmvpn.scanner.engines.rstaspoof

import android.content.Context
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object RstaSpoofManager {
    private const val TAG = "RstaSpoofManager"
    private const val LISTEN_HOST = "127.0.0.1"
    private const val LISTEN_PORT = 40443
    private var process: Process? = null
    private val lifecycleMutex = Mutex()
    val isRunningFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

    // Track what config we started with for auto-restart
    private var lastConnectIp: String? = null
    private var lastConnectPort: Int? = null
    private var lastFakeSni: String? = null
    private var lastMethod: String = "combined"

    fun start(context: Context, connectIp: String, connectPort: Int, fakeSni: String, methodArg: String = "combined") {
        runBlocking {
            lifecycleMutex.withLock {
                startLocked(context, connectIp, connectPort, fakeSni, methodArg)
            }
        }
    }

    // Must only be called while holding lifecycleMutex.
    private fun startLocked(context: Context, connectIp: String, connectPort: Int, fakeSni: String, methodArg: String = "combined") {
        val method = "combined" // Use combined method for DPI bypass
        Log.d(TAG, ">>> start() called: connect=$connectIp:$connectPort fakeSni=$fakeSni method=$method")
        stopLocked()

        val destFile = File(context.applicationInfo.nativeLibraryDir, "librstaspoof.so")
        if (!destFile.exists()) {
            Log.e(TAG, "FATAL: Binary not found at ${destFile.absolutePath}")
            return
        }
        Log.d(TAG, "Binary found: ${destFile.absolutePath} (${destFile.length()} bytes)")

        try {
            val logFile = File(context.filesDir, "rstaspoof.log")
            logFile.writeText("[${System.currentTimeMillis()}] Starting RSTA Spoof...\n")
            logFile.appendText("Binary: ${destFile.absolutePath}\n")
            logFile.appendText("Connect: $connectIp:$connectPort\n")
            logFile.appendText("FakeSNI: $fakeSni\n")
            logFile.appendText("Method: $method\n")

            val args = arrayOf(
                destFile.absolutePath,
                "-listen", "$LISTEN_HOST:$LISTEN_PORT",
                "-connect", "$connectIp:$connectPort",
                "-sni", fakeSni,
                "-method", method
            )

            Log.d(TAG, "Executing: ${args.joinToString(" ")}")
            logFile.appendText("CMD: ${args.joinToString(" ")}\n")

            val pb = ProcessBuilder(*args)
            pb.redirectErrorStream(true)

            val proc = pb.start()
            process = proc

            // Save config for potential auto-restart
            lastConnectIp = connectIp
            lastConnectPort = connectPort
            lastFakeSni = fakeSni
            lastMethod = method

            // Wait briefly for the process to bind the port
            Thread.sleep(200)

            // Verify the process is still alive
            val alive = try { proc.exitValue(); false } catch (e: IllegalThreadStateException) { true }
            if (!alive) {
                val exitCode = proc.exitValue()
                Log.e(TAG, "Process died immediately with exit code $exitCode")
                logFile.appendText("FATAL: Process exited immediately with code $exitCode\n")
                isRunningFlow.value = false
                return
            }

            // Verify port is listening
            val portOpen = isPortOpen(LISTEN_HOST, LISTEN_PORT, 1000)
            Log.d(TAG, "Port $LISTEN_PORT open check: $portOpen")
            logFile.appendText("Port check ($LISTEN_HOST:$LISTEN_PORT): ${if (portOpen) "OPEN" else "CLOSED"}\n")

            if (!portOpen) {
                Log.e(TAG, "Port $LISTEN_PORT is NOT listening after start!")
                logFile.appendText("WARNING: Port not listening, process may have failed to bind\n")
            }

            isRunningFlow.value = true
            Log.d(TAG, ">>> RSTA Spoof STARTED successfully on $LISTEN_HOST:$LISTEN_PORT")

            // Log reader thread (captures proc locally so a concurrent restart can't swap it mid-read)
            Thread {
                try {
                    proc.inputStream?.bufferedReader()?.useLines { lines ->
                        lines.forEach { line ->
                            val isSpam = line.contains("██████") || line.contains("[SVR RESP ]") || line.contains("[CLI REQ  ]")
                            if (!isSpam) {
                                Log.d(TAG, "RSTA RAW: $line")
                            }
                            // Always append to logFile for debugging
                            try { logFile.appendText("$line\n") } catch (_: Exception) {}
                        }
                    }
                } catch (e: java.io.InterruptedIOException) {
                    // Benign exception when process is destroyed, ignore it
                    Log.d(TAG, "Read interrupted (process stopped)")
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading process output", e)
                    try { logFile.appendText("Error reading output: ${e.message}\n") } catch (_: Exception) {}
                } finally {
                    val exitCode = try { proc.waitFor() } catch (_: Exception) { null }
                    if (process === proc) isRunningFlow.value = false
                    val exitMsg = "Process exited with code $exitCode"
                    Log.d(TAG, exitMsg)
                    try { logFile.appendText("$exitMsg\n") } catch (_: Exception) {}
                }
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start rstaspoof", e)
            isRunningFlow.value = false
        }
    }

    fun stop() {
        runBlocking {
            lifecycleMutex.withLock {
                stopLocked()
            }
        }
    }

    // Must only be called while holding lifecycleMutex.
    private fun stopLocked() {
        try {
            process?.destroyForcibly()
            process = null
            isRunningFlow.value = false
            Log.d(TAG, ">>> Stopped rstaspoof process")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping rstaspoof", e)
        }
    }

    /**
     * Ensure RSTA is running using the saved config from Emergency3 preferences.
     * Called automatically by MyVpnService when connecting to an SNI config.
     * Returns true if RSTA is running (or was successfully started).
     */
    fun ensureRunning(context: Context): Boolean {
        return runBlocking {
            lifecycleMutex.withLock {
                // Already running and port is open?
                if (isRunningFlow.value && isProcessAlive() && isPortOpen(LISTEN_HOST, LISTEN_PORT, 500)) {
                    Log.d(TAG, "ensureRunning: already running, process alive, and port is open")
                    return@withLock true
                }

                // Read saved config from SharedPreferences (set by EmergencyLevel3Screen)
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)

                val connectIp = lastConnectIp
                    ?: prefs.getString("rsta_connect_ip", null)
                    ?: "188.114.98.0"
                val connectPort = lastConnectPort
                    ?: prefs.getInt("rsta_connect_port", 443)
                val fakeSni = lastFakeSni
                    ?: prefs.getString("rsta_fake_sni", null)
                    ?: "security.vercel.com"
                val method = "combined" // Force combined method for stability

                Log.d(TAG, "ensureRunning: (re)starting with connect=$connectIp:$connectPort sni=$fakeSni method=$method")
                startLocked(context, connectIp, connectPort, fakeSni, method)
                isRunningFlow.value
            }
        }
    }

    /**
     * Quick TCP check to see if a port is open.
     */
    fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if the RSTA process is still alive.
     */
    fun isProcessAlive(): Boolean {
        return try {
            process?.exitValue()
            false // exitValue() returned = process has exited
        } catch (e: IllegalThreadStateException) {
            true // process still running
        } catch (e: Exception) {
            false
        }
    }
}
