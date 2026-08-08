package com.mlmvpn.scanner

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash capture for stress-testing.
 *
 * Two different kinds of crash have shown up on this app, and they need different handling:
 *
 *   - **JVM crashes** — an uncaught Kotlin/Java exception. The platform prints these, but the
 *     process dies immediately afterwards, so if logcat wasn't attached at that moment the
 *     stack is gone. Here every one is also written to a file under `files/crashlogs/`, which
 *     survives the process death and can be pulled later.
 *   - **Native crashes** (SIGSEGV, SIGABRT, fdsan, FORTIFY) — these never reach a Java handler
 *     at all. Nothing in-process can catch them; the backtrace only exists in logcat and in
 *     the tombstone. What this class contributes there is a *marker*: the last thing the app
 *     was doing is logged under a single tag, so the logcat line right before the `F/libc`
 *     abort names the screen and action instead of leaving it to guesswork.
 */
object CrashReporter {

    const val TAG = "MLMCrash"

    private val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private lateinit var logDir: File
    private var appVersion: String = "?"

    /** Rolling record of what the app was last doing, printed into every crash report. */
    private val breadcrumbs = ArrayDeque<String>()
    private const val MAX_BREADCRUMBS = 40

    fun install(app: Application) {
        logDir = File(app.filesDir, "crashlogs").apply { mkdirs() }
        appVersion = try {
            val pi = app.packageManager.getPackageInfo(app.packageName, 0)
            "${pi.versionName} (${pi.longVersionCode})"
        } catch (t: Throwable) { "?" }
        prune()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = buildReport(thread, error)
                // Logcat first: if writing the file fails for any reason, the report is still
                // in the buffer the stress test is being watched through.
                Log.e(TAG, text)
                File(logDir, "crash-${stamp.format(Date())}.txt").writeText(text)
            } catch (t: Throwable) {
                Log.e(TAG, "failed to record crash: ${t.message}")
            }
            // Always hand back to the platform handler so the process still dies the normal
            // way — swallowing this leaves a half-dead app that behaves far worse than a crash.
            previous?.uncaughtException(thread, error)
        }

        // The app has been dying with ApplicationExitInfo reason=EXIT_SELF status=255 and no
        // signal, no FORTIFY line and no Java exception — that is a deliberate process exit,
        // not a crash. A shutdown hook runs for System.exit()/Runtime.exit() but NOT for a
        // native exit() or a signal, so this both names the caller and, by staying silent,
        // tells us the exit came from native code instead.
        try {
            Runtime.getRuntime().addShutdownHook(Thread {
                val who = Thread.currentThread().stackTrace.joinToString("\n  ") { it.toString() }
                val others = Thread.getAllStackTraces().entries.joinToString("\n") { (t, st) ->
                    "-- ${t.name} --\n  " + st.take(12).joinToString("\n  ") { it.toString() }
                }
                Log.e(TAG, "VM SHUTTING DOWN (deliberate exit)\nhook stack:\n  $who\n\nthreads:\n$others")
                try {
                    File(logDir, "exit-${stamp.format(Date())}.txt")
                        .writeText("VM shutdown\n\nhook:\n  $who\n\nthreads:\n$others")
                } catch (_: Throwable) {}
            })
        } catch (t: Throwable) {
            Log.w(TAG, "could not register shutdown hook: ${t.message}")
        }

        reportPreviousExit(app)

        Log.i(TAG, "crash reporter installed; reports in ${logDir.absolutePath}")
    }

    /**
     * One-line summary of how the PREVIOUS run of this process ended, or null if it ended normally
     * (or the device is pre-API-30). Read once at startup; safe to read from the UI afterwards.
     */
    @Volatile var lastExitSummary: String? = null
        private set

    /**
     * Records that the app is about to kill itself on purpose (restartAppProcess). Without this
     * marker every deliberate relaunch looks identical to the bug we are chasing -- both surface
     * as ApplicationExitInfo REASON_EXIT_SELF -- and we cannot tell "the app restarted itself as
     * designed" apart from "the native core exited under us".
     */
    fun noteDeliberateExit(app: android.content.Context, why: String) {
        try {
            app.getSharedPreferences("crash_diag", android.content.Context.MODE_PRIVATE).edit()
                .putLong("deliberate_exit_at", System.currentTimeMillis())
                .putString("deliberate_exit_why", why)
                .commit()   // commit(), not apply(): the process dies milliseconds from now.
        } catch (t: Throwable) {
            Log.w(TAG, "could not record deliberate exit: ${t.message}")
        }
        Log.i(TAG, "DELIBERATE EXIT: $why")
    }

    /**
     * Asks the system how the previous run of this process actually died and writes it down.
     *
     * This is the piece that was missing: with no adb on hand, `reason=1 status=255` was all we
     * had, and it is ambiguous -- it fits a native exit() AND the app's own restartAppProcess.
     * ApplicationExitInfo also carries the process NAME (so we learn whether it was the main
     * process or a child), the importance at the time of death (foreground vs cached, i.e. was
     * this the system trimming a background app?) and a system description string.
     */
    private fun reportPreviousExit(app: Application) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        try {
            val am = app.getSystemService(Application.ACTIVITY_SERVICE) as android.app.ActivityManager
            val exits = am.getHistoricalProcessExitReasons(app.packageName, 0, 5)
            if (exits.isEmpty()) return

            val diag = app.getSharedPreferences("crash_diag", android.content.Context.MODE_PRIVATE)
            val deliberateAt = diag.getLong("deliberate_exit_at", 0L)
            val deliberateWhy = diag.getString("deliberate_exit_why", null)
            diag.edit().remove("deliberate_exit_at").remove("deliberate_exit_why").apply()

            val text = buildString {
                appendLine("=== how previous runs ended (newest first) ===")
                appendLine("recorded at : ${Date()}")
                if (deliberateAt > 0L) {
                    appendLine("NOTE: this app deliberately killed itself at ${Date(deliberateAt)} ($deliberateWhy)")
                }
                appendLine()
                exits.forEach { e ->
                    appendLine("time       : ${Date(e.timestamp)}")
                    appendLine("process    : ${e.processName} (pid ${e.pid})")
                    appendLine("reason     : ${reasonName(e.reason)} (${e.reason})")
                    appendLine("status     : ${e.status}")
                    appendLine("importance : ${e.importance}")
                    appendLine("description: ${e.description}")
                    // Present for ANR/native-crash exits; this is the actual stack when there is one.
                    try {
                        e.traceInputStream?.use { appendLine("trace      :\n" + it.readBytes().decodeToString()) }
                    } catch (_: Throwable) {}
                    appendLine("---")
                }
            }
            Log.e(TAG, text)
            File(logDir, "lastexit-${stamp.format(Date())}.txt").writeText(text)

            val newest = exits[0]
            // Ours or the bug? If we marked a deliberate exit within a minute of it, it was ours.
            val wasOurs = deliberateAt > 0L && kotlin.math.abs(newest.timestamp - deliberateAt) < 60_000
            lastExitSummary = "exit: ${reasonName(newest.reason)}/${newest.status} " +
                "proc=${newest.processName.substringAfterLast(':', "main")} " +
                "imp=${newest.importance}" + if (wasOurs) " (ours: $deliberateWhy)" else " (NOT ours)"
            Log.e(TAG, "LAST EXIT → $lastExitSummary")
        } catch (t: Throwable) {
            Log.w(TAG, "could not read exit reasons: ${t.message}")
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        1 -> "EXIT_SELF"; 2 -> "SIGNALED"; 3 -> "LOW_MEMORY"; 4 -> "CRASH(jvm)"
        5 -> "CRASH_NATIVE"; 6 -> "ANR"; 7 -> "INITIALIZATION_FAILURE"; 8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"; 10 -> "USER_REQUESTED"; 11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"; 13 -> "OTHER"; 14 -> "FREEZER"; 15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"; else -> "UNKNOWN"
    }

    /**
     * Record what the user is doing. Cheap enough to call on every screen change or connect —
     * it is a string append, not I/O.
     */
    fun note(message: String) {
        val line = "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())}  $message"
        synchronized(breadcrumbs) {
            breadcrumbs.addLast(line)
            while (breadcrumbs.size > MAX_BREADCRUMBS) breadcrumbs.removeFirst()
        }
        // Echoed to logcat under the same tag so it is visible in the run-up to a *native*
        // crash too, where the file below is never written.
        Log.i(TAG, "• $line")
    }

    private fun buildReport(thread: Thread, error: Throwable): String = buildString {
        appendLine("=== MLMVPN crash ===")
        appendLine("time      : ${Date()}")
        appendLine("thread    : ${thread.name}")
        appendLine("version   : $appVersion")
        appendLine("device    : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                "Android ${android.os.Build.VERSION.RELEASE}")
        appendLine()
        appendLine("--- breadcrumbs (most recent last) ---")
        synchronized(breadcrumbs) { breadcrumbs.forEach { appendLine(it) } }
        appendLine()
        appendLine("--- stack ---")
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        appendLine(sw.toString())
    }

    /** Keep the directory from growing without bound over a long stress run. */
    private fun prune() {
        val files = logDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size > 30) files.take(files.size - 30).forEach { it.delete() }
    }
}
