package com.mlmvpn.core.geph

import com.mlmvpn.scanner.utils.alive
import com.mlmvpn.scanner.utils.killHard
import com.mlmvpn.scanner.utils.waitUpTo

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.mlmvpn.scanner.store.StoreEngines
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * One running geph5-client, exec'd from nativeLibraryDir as `libgeph.so` -- Geph's own Android
 * build, unmodified, the same way the official app runs it (geph-android GephDaemon.kt).
 *
 * The TUN reaches the engine the way the official app hands it over: as the child's stdin,
 * `--vpn-fd 0`. Java's ProcessBuilder closes every descriptor above 2 in the child, so 0 is the one
 * slot a descriptor can ride through; it is put there with dup2 for the instant of the fork and
 * the app's own stdin is restored straight after. The engine then reads and writes raw IP packets
 * on it with its own userspace stack -- no tun2socks and no SOCKS hop, which is why this is the
 * fastest path Geph has. (`--vpn-fd` is the CLI flag in binaries/geph5-client/src/bin; the old
 * `vpn`/`vpn_fd` config keys are gone and would be rejected.)
 */
class GephProcess private constructor(
    val name: String,
    private val process: Process,
) {
    val isAlive: Boolean
        get() = try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }

    val exitCode: Int?
        get() = runCatching { process.exitValue() }.getOrNull()

    /** Waits for the exit the engine was asked for, then makes sure of it. */
    fun stop(control: GephControl?) {
        if (!isAlive) return
        control?.stop()
        val gone = runCatching { process.waitUpTo(1500, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!gone) {
            process.destroy()
            if (!runCatching { process.waitUpTo(1000, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
                process.killHard()
            }
        }
    }

    companion object {
        private const val TAG = "GephProcess"
        private val FD_LOCK = Any()

        /**
         * Starts the engine with [configFile]. With [tun] it runs as the VPN (API 26+ only:
         * `Redirect.INHERIT` for stdin arrived there); without, it runs as a proxy / query engine.
         *
         * [onLine] receives every line the engine prints (stdout and stderr, ANSI stripped);
         * [onExit] fires once, off the main thread, when the process ends for any reason.
         */
        fun start(
            context: Context,
            name: String,
            configFile: File,
            workDir: File,
            tun: ParcelFileDescriptor?,
            onLine: (String) -> Unit,
            onExit: (Int) -> Unit,
        ): GephProcess {
            val cmd = StoreEngines.command(context, "geph", "libgeph.so") +
                listOf("--config", configFile.absolutePath) +
                (if (tun != null) listOf("--vpn-fd", "0") else emptyList())
            val pb = ProcessBuilder(cmd)
                .directory(workDir)
                .redirectErrorStream(true)
            pb.environment().apply {
                // The default filter is geph=debug: one "dial stage failed" line per losing path
                // of every route race, and every line is also an INSERT into the SQLite log
                // table. info keeps what is worth reading and spares the phone the writes.
                put("RUST_LOG", "geph=info,geph5_client=info")
                put("NO_COLOR", "1")
                put("CLICOLOR", "0")
                put("TERM", "dumb")
                // dirs::config_dir() has no Android answer; everything the engine writes has an
                // explicit path, and this keeps anything else inside our own directory.
                put("HOME", workDir.absolutePath)
                put("TMPDIR", context.cacheDir.absolutePath)
            }

            val process = if (tun != null) {
                check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { "--vpn-fd needs Android 8" }
                synchronized(FD_LOCK) {
                    Os.dup2(tun.fileDescriptor, 0)
                    try {
                        pb.redirectInput(ProcessBuilder.Redirect.INHERIT).start()
                    } finally {
                        // Put the app's own stdin back at once. Left pointing at the TUN, fd 0
                        // would keep the interface alive after the VPN is stopped, and hand it to
                        // any later child that inherits stdin.
                        restoreStdin()
                    }
                }
            } else {
                pb.start()
            }

            val gp = GephProcess(name, process)
            Thread({
                try {
                    BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { r ->
                        while (true) {
                            val line = r.readLine() ?: break
                            val clean = line.replace(ANSI, "").trim()
                            if (clean.isNotEmpty()) onLine(clean)
                        }
                    }
                } catch (_: Exception) {
                }
                val code = runCatching { process.waitFor() }.getOrDefault(-1)
                Log.i(TAG, "$name exited with $code")
                onExit(code)
            }, "geph-$name").apply { isDaemon = true }.start()
            return gp
        }

        private fun restoreStdin() {
            try {
                val devNull = Os.open("/dev/null", OsConstants.O_RDONLY, 0)
                Os.dup2(devNull, 0)
                Os.close(devNull)
            } catch (e: Exception) {
                Log.w(TAG, "could not restore stdin: ${e.message}")
            }
        }

        private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    }
}
