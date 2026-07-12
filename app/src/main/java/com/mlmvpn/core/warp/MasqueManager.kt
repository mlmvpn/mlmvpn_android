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
 * Runs the bundled `usque` MASQUE client (Cloudflare WARP over HTTP/3, RFC 9298 CONNECT-UDP) as a
 * local process that exposes a SOCKS5 proxy, exactly like [com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager]
 * runs its native binary. Game/DNS traffic is then routed through that local SOCKS port.
 *
 * Why MASQUE: on operators where native WireGuard/WARP UDP is dropped by DPI (proven by the WARP
 * Connectivity Lab) but QUIC/UDP:443 to Cloudflare survives (also proven), MASQUE tunnels the same
 * WARP service over what looks like ordinary HTTPS/QUIC web traffic -- hidden AND low-ping (native
 * UDP, direct to Cloudflare's backbone), no UDP-over-TCP overhead.
 *
 * Binary requirement: `usque` compiled for Android is shipped as `libusque.so` in jniLibs per ABI
 * (same mechanism as librstaspoof.so). It is NOT extracted -- Android places jniLibs in
 * nativeLibraryDir and they are executable there.
 *
 * CLI (usque, github.com/Diniboy1123/usque):
 *   register:  libusque.so register -c <config.json>     (one-time, creates a WARP MASQUE account)
 *   socks:     libusque.so socks -c <config.json> -b 127.0.0.1 -p <port>
 * The exact flags live in constants below so they're trivial to adjust to the shipped build.
 */
object MasqueManager {

    private const val TAG = "MasqueManager"
    private const val LISTEN_HOST = "127.0.0.1"
    const val SOCKS_PORT = 10810
    private const val BINARY_NAME = "libusque.so"
    // Bumped from usque_config.json: the old file was endpoint-patched (IP repointed) which broke
    // cert pinning. A fresh name forces a clean re-registration with a consistent endpoint+pub_key.
    // Bumped again to v3: v2 worked once (300-400KB tunneled) but on the next run failed 100% of
    // the time with "remote endpoint has a different public key than what we trust in config.json"
    // -- same untouched config, same endpoint, yet the pinned key no longer matches what the server
    // presents. That means the identity/endpoint pairing recorded in v2 went stale server-side (key
    // rotated, endpoint reassigned, or account state changed) -- not something we can patch our way
    // out of. A fresh registration gets a brand-new identity pinned against current server state.
    // v4: v3 got endpoint-patched on-device (104.16.2.2) during the repoint experiment, which is now
    // reverted; a fresh name forces a clean registration instead of reusing that broken patched file.
    private const val CONFIG_NAME = "usque_config_v4.json"

    private var process: Process? = null
    private val lifecycleMutex = Mutex()
    val isRunningFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

    private fun binaryFile(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    private fun configFile(context: Context): File =
        File(context.filesDir, CONFIG_NAME)

    fun isAvailable(context: Context): Boolean = binaryFile(context).exists()

    /**
     * Ensures a MASQUE/WARP account config exists, registering one the first time. Blocking; run off
     * the main thread. Returns true if a usable config is present afterward.
     */
    // The 162.159.198.0/24 block is Cloudflare's WARP-specific MASQUE anycast range, and this is the
    // one range Iran's DPI silently black-holes even over --http2/TCP:443 (no RST, no error -- the
    // handshake just never completes, confirmed by a run that registered into .2 and sat forever with
    // zero bytes moved). A previous registration that landed on a generic Cloudflare CDN IP
    // (104.16.2.2, NOT in this range) worked immediately. Cloudflare's registration endpoint hands out
    // whichever edge IP it feels like, so we can't request a specific one -- but we CAN throw away a
    // registration that landed in the blocked range and roll again.
    private val BLOCKED_ENDPOINT_PREFIX = "162.159.198."
    // Only 2 tries now (was 5): on networks that ALWAYS hand out the blocked range, retrying just
    // burns ~2-3s each for nothing. If both land blocked, maybeRepointBlockedEndpoint rewrites the
    // endpoint to a working IP instead -- so a couple of tries (in case a good natural IP appears) is
    // plenty, and the repoint is the real safety net.
    private const val MAX_REGISTER_ATTEMPTS = 2

    private fun ensureRegistered(context: Context, logFile: File): Boolean {
        val cfg = configFile(context)
        if (cfg.exists() && cfg.length() > 0) {
            if (!isBlockedEndpoint(cfg)) return true
            Log.w(TAG, "Existing config landed on blocked endpoint range ($BLOCKED_ENDPOINT_PREFIX*) -- re-registering")
            cfg.delete()
        }

        val bin = binaryFile(context)
        repeat(MAX_REGISTER_ATTEMPTS) { attempt ->
            cfg.delete()
            val args = arrayOf(bin.absolutePath, "register", "--config", cfg.absolutePath, "--accept-tos")
            Log.d(TAG, "Registering MASQUE account (attempt ${attempt + 1}/$MAX_REGISTER_ATTEMPTS): ${args.joinToString(" ")}")
            logFile.appendText("REGISTER attempt ${attempt + 1}: ${args.joinToString(" ")}\n")
            try {
                val pb = ProcessBuilder(*args).redirectErrorStream(true)
                val proc = pb.start()
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { try { logFile.appendText("reg: $it\n") } catch (_: Exception) {} }
                }
                val code = proc.waitFor()
                Log.d(TAG, "register exited with code $code, config exists=${cfg.exists()}")
            } catch (e: Exception) {
                Log.e(TAG, "MASQUE register failed", e)
                return@repeat
            }
            if (!cfg.exists() || cfg.length() == 0L) return@repeat
            if (!isBlockedEndpoint(cfg)) return true
            Log.w(TAG, "Attempt ${attempt + 1} landed on blocked endpoint range -- retrying")
        }
        // Out of attempts: keep whatever config we ended up with (even if it's in the blocked range)
        // rather than leaving no config at all -- better to try connecting than to refuse outright.
        return cfg.exists() && cfg.length() > 0
    }

    private fun isBlockedEndpoint(cfg: File): Boolean = try {
        val raw = cfg.readText()
        Regex("\"endpoint_(?:v4|h2_v4)\"\\s*:\\s*\"([^\"]*)\"").findAll(raw)
            .any { it.groupValues[1].startsWith(BLOCKED_ENDPOINT_PREFIX) }
    } catch (_: Exception) {
        false
    }

    fun start(context: Context): Boolean = runBlocking {
        lifecycleMutex.withLock { startLocked(context) }
    }

    private fun startLocked(context: Context): Boolean {
        stopLocked()
        val bin = binaryFile(context)
        if (!bin.exists()) {
            Log.e(TAG, "FATAL: usque binary not found at ${bin.absolutePath} -- ship libusque.so in jniLibs")
            return false
        }

        val logFile = File(context.filesDir, "usque.log")
        try { logFile.writeText("[${System.currentTimeMillis()}] Starting MASQUE (usque)...\n") } catch (_: Exception) {}

        if (!ensureRegistered(context, logFile)) {
            Log.e(TAG, "No MASQUE account config -- registration failed")
            return false
        }

        // DISABLED: repointing the endpoint IP is PROVEN not to work. endpoint_pub_key is pinned to
        // the exact endpoint IP registration returned; forcing a different IP (104.16.2.2) triggers
        // "x509: no valid chains built: remote endpoint has a different public key" and the tunnel
        // never connects. And the natural endpoint on this operator (162.159.198.2) is DPI
        // black-holed (request enters tunnel, every response Read-times-out). So usque/MASQUE has no
        // reachable endpoint here -- the multi-engine orchestrator + warp-plus is the path forward.
        // Kept the detection helpers below for the orchestrator to reuse.

        // Dump the (possibly repointed) config. private_key is redacted from the log.
        try {
            val raw = configFile(context).readText()
            val redacted = raw.replace(Regex("\"private_key\"\\s*:\\s*\"[^\"]*\""), "\"private_key\":\"<redacted>\"")
            Log.d(TAG, "usque_config.json = $redacted")
        } catch (e: Exception) {
            Log.w(TAG, "Could not read config for logging: ${e.message}")
        }

        return try {
            val cfg = configFile(context)
            val args = arrayOf(
                bin.absolutePath, "socks",
                "--config", cfg.absolutePath,
                "--bind", LISTEN_HOST,
                "--port", SOCKS_PORT.toString(),
                // Iran's DPI drops UDP:443 to the MASQUE endpoint range (162.159.198.x) but leaves
                // TCP:443 to it open (proven: TCP handshake returned HTTP 530). --http2 runs the
                // MASQUE tunnel over HTTP/2 (TCP) instead of HTTP/3 (QUIC), sidestepping the UDP
                // block entirely. Slightly more head-of-line blocking, but it actually connects.
                "--http2"
            )
            Log.d(TAG, "Executing: ${args.joinToString(" ")}")
            logFile.appendText("SOCKS: ${args.joinToString(" ")}\n")

            val proc = ProcessBuilder(*args).redirectErrorStream(true).start()
            process = proc

            // Give it a moment to open the QUIC session + bind the SOCKS port.
            Thread.sleep(600)

            val alive = try { proc.exitValue(); false } catch (_: IllegalThreadStateException) { true }
            if (!alive) {
                Log.e(TAG, "usque exited immediately with code ${proc.exitValue()}")
                isRunningFlow.value = false
                return false
            }

            val portOpen = isPortOpen(LISTEN_HOST, SOCKS_PORT, 2000)
            Log.d(TAG, "SOCKS port $SOCKS_PORT open: $portOpen")
            logFile.appendText("Port $LISTEN_HOST:$SOCKS_PORT: ${if (portOpen) "OPEN" else "CLOSED"}\n")

            isRunningFlow.value = alive
            startLogReader(proc, logFile)
            alive
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start usque", e)
            isRunningFlow.value = false
            false
        }
    }

    private fun startLogReader(proc: Process, logFile: File) {
        Thread {
            try {
                proc.inputStream?.bufferedReader()?.useLines { lines ->
                    lines.forEach { line ->
                        Log.d(TAG, "usque: $line")
                        try { logFile.appendText("$line\n") } catch (_: Exception) {}
                    }
                }
            } catch (_: java.io.InterruptedIOException) {
            } catch (e: Exception) {
                Log.e(TAG, "Error reading usque output", e)
            } finally {
                val code = try { proc.waitFor() } catch (_: Exception) { null }
                if (process === proc) isRunningFlow.value = false
                Log.d(TAG, "usque exited with code $code")
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
            Log.d(TAG, ">>> Stopped usque process")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping usque", e)
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
