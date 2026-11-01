package com.mlmvpn.scanner.engines.rstaspoof

import com.mlmvpn.scanner.utils.alive
import com.mlmvpn.scanner.utils.killHard
import com.mlmvpn.scanner.utils.waitUpTo

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

    /**
     * One complete set of the engine's levers -- what the screen calls a «روش اتصال».
     *
     * `-method` ALONE IS NOT THE LEVER, and believing it was is why this never worked on one of
     * Iran's two big mobile networks. The engine takes five settings, and `-method` only chooses
     * which of the other four apply:
     *
     * * `-fragment-strategy` — `sni_split` | `half` | `chunk` | `record_frag`. **Defaults to
     *   `sni_split`**, and on the failing network `sni_split` is the one the DPI kills.
     * * `-fragment-delay` — seconds between fragments (default 0.1).
     * * `-no-raw` — no raw-socket injection. Raw sockets need root, so on an ordinary phone the
     *   engine falls back by itself (`method=fragment-fallback` in its log) -- and the fallback it
     *   picks is the failing default.
     * * `-ttl-trick` — send the fake record with a TTL that dies before the server.
     *
     * Measured on the device, over the operator this used to fail on, three attempts each against
     * one Cloudflare edge under one forged name (`500` = a real HTTP answer came back, so bytes
     * moved; `000` = curl got nothing):
     *
     * | profile | result |
     * |---|---|
     * | no engine at all, for reference | 500 · 500 · 500 |
     * | `fragment` + `chunk` | **500/2.0s · 500/1.4s · 500/1.2s** |
     * | `fragment` + `record_frag`, delay 0.02 | **500/1.4s · 500/2.1s · 500/1.8s** |
     * | `fragment` + `half` | 500/5.6s · 500/4.0s · 500/5.8s |
     * | `combined` + `-no-raw` | 500 · 000 · 000 |
     * | **`combined` — what shipped** | **000 · 000 · 000** |
     * | `fragment` + `sni_split` — the engine's default | 500 · 000 · 000 |
     * | `fake_sni` + `-no-raw` | 000 · 000 · 000 |
     *
     * So there is no single right answer -- `combined` is what works on the other operator, and it
     * carries nothing on this one. The list below is a LADDER, tried in order until one answers,
     * and the winner is remembered. Order is by how many users a rung is expected to serve, not by
     * how fast it was here: `combined` stays first so the network where it already works is not
     * made slower to fix the one where it does not.
     */
    data class SpoofProfile(
        val id: String,
        val method: String,
        val strategy: String? = null,
        val delaySec: Double? = null,
        val noRaw: Boolean = false,
        val ttlTrick: Boolean = false,
    ) {
        /** The command-line form. Only the levers this profile sets are passed. */
        fun args(): List<String> = buildList {
            add("-method"); add(method)
            strategy?.let { add("-fragment-strategy"); add(it) }
            delaySec?.let { add("-fragment-delay"); add(it.toString()) }
            if (noRaw) add("-no-raw")
            if (ttlTrick) add("-ttl-trick")
        }

        /** For a log line or a row of the UI, without naming any operator. */
        fun describe(): String = buildString {
            append(method)
            strategy?.let { append('/').append(it) }
            delaySec?.let { append(" d=").append(it) }
            if (noRaw) append(" no-raw")
            if (ttlTrick) append(" ttl")
        }
    }

    val PROFILES = listOf(
        // Works on one operator; measured dead on the other. First because it is the incumbent.
        SpoofProfile("combined", "combined"),
        // The two that answered every attempt on the operator `combined` fails.
        SpoofProfile("frag-chunk", "fragment", strategy = "chunk"),
        SpoofProfile("frag-record", "fragment", strategy = "record_frag", delaySec = 0.02),
        // Slower but equally reliable there.
        SpoofProfile("frag-half", "fragment", strategy = "half"),
        // Partial successes -- kept, because a rung that answers one network in three is still the
        // rung that saves the user for whom it answers.
        SpoofProfile("combined-noraw", "combined", noRaw = true),
        SpoofProfile("combined-noraw-record", "combined", strategy = "record_frag", noRaw = true),
        SpoofProfile("frag-record-slow", "fragment", strategy = "record_frag"),
        SpoofProfile("frag-sni-split", "fragment", strategy = "sni_split"),
        // The remaining corners of the lever space. None answered here; each is a different trade
        // and no network we can reach is every network.
        SpoofProfile("fake-sni", "fake_sni"),
        SpoofProfile("fake-sni-noraw", "fake_sni", noRaw = true),
        SpoofProfile("fake-sni-chunk", "fake_sni", strategy = "chunk"),
        SpoofProfile("combined-ttl", "combined", ttlTrick = true),
        SpoofProfile("frag-chunk-noraw", "fragment", strategy = "chunk", noRaw = true),
        SpoofProfile("frag-slow", "fragment", strategy = "chunk", delaySec = 0.25),
    )

    const val DEFAULT_PROFILE = "combined"

    /** A stored id that no longer exists must not silently disable the front. */
    fun profileOf(id: String?): SpoofProfile =
        PROFILES.firstOrNull { it.id == id } ?: PROFILES.first { it.id == DEFAULT_PROFILE }

    /** 1-based, for a UI that shows «روش ۳» rather than an internal id. */
    fun profileNumber(id: String?): Int = PROFILES.indexOfFirst { it.id == profileOf(id).id } + 1

    private var process: Process? = null
    private val lifecycleMutex = Mutex()
    val isRunningFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * How many things need the front up right now.
     *
     * Nothing used to take it down. Five call sites started it -- the service when it connects an
     * SNI config, and four measurement paths that need a live port to measure through -- against
     * two that stopped it, neither on the ordinary path. So one delay test over a list that
     * happened to contain an SNI config left the process running for the rest of the app's life,
     * and the home screen read that stray process as "the SNI engine is on" while the user was on
     * a tunnel. That is the lamp coming on by itself.
     *
     * A count and not a flag, because the owners genuinely overlap: a measurement can be started
     * while a session is up, and the measurement ending must not pull the session's door out from
     * under it. The front goes down when the last owner lets go, and not before.
     */
    private val holders = java.util.concurrent.atomic.AtomicInteger(0)

    // Track what config we started with for auto-restart
    private var lastConnectIp: String? = null
    private var lastConnectPort: Int? = null
    private var lastFakeSni: String? = null
    private var lastProfile: String = DEFAULT_PROFILE

    // Must only be called while holding lifecycleMutex.
    private fun startLocked(
        context: Context,
        connectIp: String,
        connectPort: Int,
        fakeSni: String,
        profileId: String = DEFAULT_PROFILE,
    ) {
        val profile = profileOf(profileId)
        Log.d(TAG, ">>> starting: connect=$connectIp:$connectPort fakeSni=$fakeSni via ${profile.describe()}")
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
            logFile.appendText("Method: ${profile.describe()}\n")

            val args = (
                listOf(
                    destFile.absolutePath,
                    "-listen", "$LISTEN_HOST:$LISTEN_PORT",
                    "-connect", "$connectIp:$connectPort",
                    "-sni", fakeSni,
                ) + profile.args()
                ).toTypedArray()

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
            lastProfile = profile.id

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

    /**
     * Claim the front, starting it if it is not already up.
     *
     * The only way anything should bring it up. Pair every call with [release] in a `finally` --
     * the claim is counted, so an unpaired one keeps a process alive forever and lights the SNI
     * lamp on the home screen over a connection that is not SNI.
     *
     * The count is incremented even when the start fails, so that a caller's `finally` is always
     * correct; releasing then simply finds nothing to stop.
     */
    fun acquire(context: Context): Boolean {
        holders.incrementAndGet()
        return ensureRunning(context)
    }

    /** Let one claim go, and take the front down if it was the last one. */
    fun release() {
        while (true) {
            val n = holders.get()
            if (n <= 0) return
            if (holders.compareAndSet(n, n - 1)) {
                if (n == 1) stop()
                return
            }
        }
    }

    /**
     * Take the front down and bring it straight back up, on the stored profile.
     *
     * For the ladder, which changes a lever and needs that change in effect for the NEXT
     * measurement. It must NOT do this with [release] followed by [acquire], and that is not a
     * style preference -- it is the bug this exists to fix.
     *
     * `release` stops the process when the last CLAIM goes, not when it is called. So a single
     * stale claim anywhere in the app -- one screen that acquired the front and returned without
     * releasing it -- turns the whole restart into nothing: the count drops from 2 to 1, the
     * process is left running with the OLD levers, `acquire` says "already running", and the next
     * rung is measured against the previous rung's settings.
     *
     * Measured on the device before this existed: a fourteen-rung ladder logged starts for rungs
     * 1, 3, 5, 7 and 9 only. The even rungs were skipped silently, and the odd ones restarted only
     * because the previous process happened to have died on its own by then. Half the ladder was
     * not being tried at all, and the two rungs measured to be the ones that work on the failing
     * network were both in the half that never ran.
     *
     * The claims are preserved across the restart: whoever was holding the front still is, so the
     * measurement's own `finally` still takes it down exactly once.
     */
    fun restart(context: Context): Boolean = runBlocking {
        lifecycleMutex.withLock {
            // A restart with NO owner is refused rather than invented: starting a process nobody
            // has claimed means nobody will ever release it, which is the leak the count exists to
            // prevent. The claims themselves survive the restart untouched -- see [stopLocked].
            if (holders.get() <= 0) return@withLock false
            val (ip, port, sni) = storedRoute(context)
            startLocked(context, ip, port, sni, storedProfile(context).id)
            isRunningFlow.value
        }
    }

    fun stop() {
        runBlocking {
            lifecycleMutex.withLock {
                // Whoever stops it outright outranks the count: the guard's "turn every engine off"
                // and the session's own disconnect both mean the front is gone regardless of who
                // thought they were holding it. Leaving claims behind would mean the next release()
                // found a non-zero count and never stopped the front again.
                holders.set(0)
                stopLocked()
            }
        }
    }

    /**
     * Choose the route the engine will use, and make that choice stick.
     *
     * The one way to change it. Writing the preferences alone was not enough and looked like it
     * was: [ensureRunning] prefers the in-memory `last*` fields over the stored ones, so once the
     * engine had run even once, a route picked in the UI was written to disk, read by nothing, and
     * the next connect quietly came up on the previous route. The screen showed a tick against a
     * route that was not carrying anything.
     *
     * Returns true when the route actually changed, so a caller that is already connected knows
     * it has to restart to apply it.
     */
    fun setRoute(context: Context, connectIp: String, connectPort: Int, fakeSni: String): Boolean {
        val changed = lastConnectIp != connectIp ||
            lastConnectPort != connectPort ||
            lastFakeSni != fakeSni
        lastConnectIp = connectIp
        lastConnectPort = connectPort
        lastFakeSni = fakeSni
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString("rsta_connect_ip", connectIp)
            .putInt("rsta_connect_port", connectPort)
            .putString("rsta_fake_sni", fakeSni)
            .apply()
        return changed
    }

    /**
     * Choose the connection method, and make that choice stick.
     *
     * Same contract as [setRoute]: returns true when it actually changed, so a caller that is
     * already connected knows it has to restart for the change to mean anything -- the levers are
     * read once, when the process starts.
     */
    fun setProfile(context: Context, id: String): Boolean {
        val next = profileOf(id).id
        val changed = lastProfile != next
        lastProfile = next
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString("rsta_profile", next)
            .apply()
        return changed
    }

    /** The method that is stored, for a screen that has to show which one is current. */
    fun storedProfile(context: Context): SpoofProfile = profileOf(
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getString("rsta_profile", lastProfile)
    )

    /** The route that is stored, for a screen that has to show which one is current. */
    fun storedRoute(context: Context): Triple<String, Int, String> {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        return Triple(
            lastConnectIp ?: prefs.getString("rsta_connect_ip", null) ?: "188.114.98.0",
            lastConnectPort ?: prefs.getInt("rsta_connect_port", 443),
            lastFakeSni ?: prefs.getString("rsta_fake_sni", null) ?: "security.vercel.com",
        )
    }

    // Must only be called while holding lifecycleMutex.
    private fun stopLocked() {
        try {
            // THE CLAIMS ARE NOT TOUCHED HERE, and that is the fix for a bug that reached almost
            // everything this object does.
            //
            // `holders.set(0)` used to be this function's first line -- correct for an outright
            // stop, and ruinous here, because [startLocked] calls this first to clear the way. So
            // the very first `acquire` destroyed the claim it had just made: increment to 1, start,
            // and the start zeroed it. From then on the count said nobody was holding a front that
            // was plainly running, and everything built on the count quietly stopped working:
            //
            // * `release()` saw 0 and returned without stopping, so the front outlived every
            //   measurement -- the stray process that lights the SNI lamp over somebody else's
            //   tunnel, which the count was introduced to prevent;
            // * the method ladder's restart became a no-op, so half its rungs were measured against
            //   the previous rung's settings and the other half only changed because the old process
            //   had died on its own by then;
            // * and [restart] refused outright, reporting no owner for a front it was holding.
            //
            // The outright stop still zeroes the count -- in [stop], where it belongs.
            process?.killHard()
            process = null
            isRunningFlow.value = false
            Log.d(TAG, ">>> Stopped rstaspoof process")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping rstaspoof", e)
        }
    }

    /**
     * Start the front, or confirm it is already up, using the stored route.
     *
     * Private: [acquire] is the door. Called directly, this had no matching way down, which is
     * how the process came to outlive every use of it.
     */
    private fun ensureRunning(context: Context): Boolean {
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
                val profile = storedProfile(context)

                Log.d(TAG, "ensureRunning: (re)starting connect=$connectIp:$connectPort sni=$fakeSni via ${profile.describe()}")
                startLocked(context, connectIp, connectPort, fakeSni, profile.id)
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
