package com.mlmvpn.scanner

import com.mlmvpn.scanner.crash.CrashClient
import com.mlmvpn.scanner.crash.CrashText
import com.mlmvpn.scanner.crash.CrashUploadJob
import com.mlmvpn.scanner.utils.SecretRedactor
import android.app.Application
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash capture, and delivery of what it captured.
 *
 * Three kinds of failure close this app, and they need different handling:
 *
 *   - **JVM crashes** — an uncaught Kotlin/Java exception. The process dies immediately
 *     afterwards, so every one is written to a file under `files/crashlogs/` first, with the
 *     stack and the last breadcrumbs.
 *   - **Native crashes** (SIGSEGV, SIGABRT, fdsan, FORTIFY) — these never reach a Java handler.
 *     On the next launch the system's record of the death (ApplicationExitInfo) is turned into a
 *     report of its own: the signal, the library, the abort message, the readable part of the
 *     tombstone, and -- through [note] and the process state summary -- the version and the last
 *     thing the app was doing when it died.
 *   - **ANRs** — the system kills a frozen app. The same: the next launch writes a report from the
 *     exit record, with the thread dump.
 *
 * ## Where a report goes
 *
 * Into an outbox, and from there to the crash collector (worker-src/crash), with the Quick Connect
 * pool's `/crash` as the fallback -- see [CrashClient]. A JVM crash schedules [CrashUploadJob] in
 * the moment before the process dies, so its report leaves within minutes whether or not the app
 * is opened again; a report stays in the outbox until one of the two says it was filed.
 *
 * Automatically, unless the user turned that off (Settings → Crash report). The app's owner asked
 * for every crash to reach them: the launch dialog this replaced reached only people who came
 * back, landed on the home screen and tapped Send, which is why crashes kept being described in
 * words and never arrived as stacks. What is sent is the report text below -- the error, the
 * breadcrumbs, the device model and the app version, passed through [SecretRedactor] -- and
 * nothing from configs or traffic. The first time it happens the user is told, once, and shown
 * where to turn it off; with it off, the old dialog asks every time.
 *
 * The whole path across the app, the collector, the pool, D1, KV and the private repository is in
 * `docs/CRASH-REPORTS.md`. Read it before changing anything here that a Worker also depends on --
 * [signatureOf] in particular, which decides what counts as "the same bug".
 */
object CrashReporter {

    const val TAG = "MLMCrash"

    private val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    /** A report of one crash of any kind: the thing that gets sent. */
    private const val CRASH_PREFIX = "crash-"
    /** How earlier runs ended, from ApplicationExitInfo, for the shared text. */
    private const val LAST_EXIT_PREFIX = "lastexit-"
    /** A VM shutdown note from the shutdown hook, written by every deliberate exit. */
    private const val EXIT_PREFIX = "exit-"
    /** A process's breadcrumbs, saved as it goes (`crumbs-<pid>.log`), for a death that leaves no report. */
    private const val CRUMBS_PREFIX = "crumbs-"

    /** Crash reports kept on the phone, newest first. */
    private const val KEEP_CRASHES = 20
    /** Exit histories and shutdown notes kept, each. */
    private const val KEEP_NOTES = 5
    /** At most this many reports go up in one pass; the rest wait for the next. */
    private const val MAX_PER_FLUSH = 5
    /** A report nobody could deliver in this long is given up on. */
    private const val OUTBOX_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    /** An ANR's thread dump or a native tombstone can run to megabytes; a report needs its top. */
    private const val MAX_TRACE_CHARS = 24_000

    private lateinit var logDir: File
    private lateinit var appContext: android.content.Context
    private var appVersion: String = "?"
    private var activityManager: android.app.ActivityManager? = null

    /** Rolling record of what the app was last doing, printed into every crash report. */
    private val breadcrumbs = ArrayDeque<String>()
    private const val MAX_BREADCRUMBS = 40

    /**
     * The screen the user was on ("windscribe", "proton"), from the last openTab/goBack breadcrumb.
     * It goes into the state the system keeps for a native crash or an ANR, and from there into the
     * line the report is grouped by: a native crash's own line ("SIGABRT in libart.so (main)") is
     * the same for unrelated bugs, and one closed issue then swallowed the next feature's reports.
     */
    @Volatile private var area: String? = null
    private val AREA_STEP = Regex("^(?:openTab|goBack) \\S+ -> ([A-Za-z0-9_-]{1,24})")
    private val AREA_TAG = Regex("^\\[([A-Za-z0-9_-]{1,24})] ")

    /**
     * Memory set aside at start and let go first thing in the crash handler: out of memory, the
     * handler could not even build its report, and the death was filed as "CRASH(jvm): crash" with
     * no stack -- which is how the Windscribe/Proton/Outline crashes of a tester (LAN sharing, split
     * tunnel and the smart Iran route on) arrived on 2026-10-11.
     */
    @Volatile private var reserve: ByteArray? = null

    private val crumbWriter = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "crash-crumbs").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val crumbsDirty = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Activities on screen, for the freeze watchdog: a stall nobody sees is not a freeze. */
    @Volatile private var visible = 0
    /** The main thread unanswered this long gets a first look at where it is stuck. */
    private const val FREEZE_MS = 4_000L
    /**
     * ...and is reported only if it is still stuck this long after it was asked. At 4 s the watchdog
     * reported every slow frame of a weak phone -- a first draw of the home screen, a long text
     * layout -- and those were most of 1.2.41's 169 freeze reports. A stall that passes is a
     * breadcrumb now; one that the system turns into an ANR still arrives with the system's trace.
     */
    private const val FREEZE_REPORT_MS = 9_000L
    private const val MAX_FREEZE_REPORTS = 2
    /** Set first thing in the crash handler: a process on its way down stops answering by design. */
    @Volatile private var crashing = false

    fun install(app: Application) {
        logDir = File(app.filesDir, "crashlogs").apply { mkdirs() }
        appContext = app.applicationContext
        appVersion = try {
            val pi = app.packageManager.getPackageInfo(app.packageName, 0)
            "${pi.versionName} (${pi.longVersionCode})"
        } catch (t: Throwable) { "?" }
        activityManager = app.getSystemService(Application.ACTIVITY_SERVICE) as? android.app.ActivityManager
        prune()
        publishState("start")
        if (isMainProcess(app)) reserve = ByteArray(1024 * 1024)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            crashing = true
            reserve = null
            try {
                val text = buildReport(thread, error)
                // Logcat first: if writing the file fails for any reason, the report is still
                // in the buffer the stress test is being watched through.
                Log.e(TAG, text)
                val file = writeReport(text)
                // Handed to the system before this process dies: the job runs in a fresh one, as
                // soon as there is a network, whether or not anyone opens the app again.
                if (autoSend()) {
                    enqueue(file)
                    CrashUploadJob.schedule(appContext)
                }
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
                    File(logDir, "$EXIT_PREFIX${stamp.format(Date())}.txt")
                        .writeText(SecretRedactor.redact("VM shutdown\n\nhook:\n  $who\n\nthreads:\n$others"))
                } catch (_: Throwable) {}
            })
        } catch (t: Throwable) {
            Log.w(TAG, "could not register shutdown hook: ${t.message}")
        }

        // The exit history and the outbox are the package's, not a process's: the main process
        // reads and sends, so the tunnel's process starting up does not do it all again.
        if (isMainProcess(app)) {
            watchMainThread(app)
            // Off the main thread: reading the system's traces and redacting them can take
            // seconds on a slow phone after an ANR, and doing it inside Application.onCreate
            // made the app itself fail to start (an ANR report of its own, 1.2.38).
            Thread({
                reportPreviousExit(app)
                try {
                    queueUnsent(app)
                    if (queued().isNotEmpty()) CrashUploadJob.schedule(app)
                } catch (t: Throwable) {
                    // A crash reporter must never be the crash.
                    Log.w(TAG, "could not queue reports: ${t.message}")
                }
            }, "crash-previous-exit").start()
        }

        Log.i(TAG, "crash reporter installed; reports in ${logDir.absolutePath}")
    }

    private fun isMainProcess(app: Application): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName() == app.packageName
        } else {
            val pid = android.os.Process.myPid()
            activityManager?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
                ?.let { it == app.packageName } ?: true
        }
    } catch (t: Throwable) {
        true
    }

    /** Distinguishes reports written inside the same second. See [writeReport]. */
    private val reportSequence = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Write one crash report, safely against the case that matters most: several threads dying
     * at once.
     *
     * The first version formatted a filename from [stamp] alone, which has one-second
     * resolution, and wrote straight to it. A relay bug in this app killed six threads inside
     * three milliseconds; all six handlers built the same path, all six truncated it, and the
     * file that survived was **zero bytes** — the report was destroyed by exactly the kind of
     * crash it was there to explain. [stamp] is also a `SimpleDateFormat`, which is not
     * thread-safe, so the six were racing on its internal calendar as well.
     *
     * So: the name carries a counter and the thread (or [tag]), formatting is serialised, and the
     * bytes go to a temporary file that is renamed into place only once it is complete. A
     * half-written report is worse than none, because it looks like a report.
     */
    @Synchronized
    private fun writeReport(raw: String, tag: String = Thread.currentThread().name): File {
        val text = SecretRedactor.redact(raw)
        val name = "$CRASH_PREFIX${stamp.format(Date())}-${reportSequence.incrementAndGet()}" +
            "-${tag.take(24).replace(Regex("[^A-Za-z0-9_-]"), "_")}.txt"
        val target = File(logDir, name)
        val temp = File(logDir, "$name.part")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            // A rename can only fail here for something like a full disk. Falling back to a
            // direct write is still better than losing the report.
            target.writeText(text)
            temp.delete()
        }
        return target
    }

    /**
     * Every report on disk, newest first, for the "send crash report" flow.
     *
     * Partial writes are excluded: a `.part` file is one that never finished, and handing a
     * truncated stack to someone reading it wastes the one chance the report had.
     */
    fun reports(): List<File> =
        if (!this::logDir.isInitialized) emptyList()
        else logDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }

    private fun crashReports(): List<File> = reports().filter { it.name.startsWith(CRASH_PREFIX) }

    /**
     * How many things on disk are worth sending: crash reports, and the records of runs that
     * ended badly. Not the shutdown notes every deliberate restart writes -- Settings counted
     * those as crashes.
     */
    fun reportCount(): Int =
        reports().count { it.name.startsWith(CRASH_PREFIX) || it.name.startsWith(LAST_EXIT_PREFIX) }

    /**
     * One text blob of the most recent reports, ready to be shared.
     *
     * Newest first and capped, because this is going into a message box: the newest report is
     * the one being asked about, and no one pastes half a megabyte into Telegram.
     *
     * The reports come first and the app's own notes after them, one of each. It used to be simply
     * the newest three files, and since an exit history was written on every launch, those three
     * were nearly always exit histories: the reports users sent said a JVM crash had happened
     * and left out the stack that would have said where.
     */
    fun collect(limit: Int = 3, maxChars: Int = 60_000): String {
        val all = reports()
        val files = all.filter { it.name.startsWith(CRASH_PREFIX) }.take(limit) + listOfNotNull(
            all.firstOrNull { it.name.startsWith(LAST_EXIT_PREFIX) },
            all.firstOrNull { it.name.startsWith(EXIT_PREFIX) },
        )
        if (files.isEmpty()) return ""
        val body = buildString {
            appendLine("MLM VPN crash reports")
            appendLine("app     : $appVersion")
            appendLine("device  : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            lastExitSummary?.let { appendLine("last exit: $it") }
            appendLine()
            files.forEach { f ->
                appendLine("========== ${f.name} ==========")
                appendLine(runCatching { f.readText() }.getOrElse { "(unreadable: ${it.message})" })
                appendLine()
            }
        }
        val clean = SecretRedactor.redact(body)
        return if (clean.length <= maxChars) clean else clean.take(maxChars) + "\n… (truncated)"
    }

    /**
     * The one line that identifies a crash: the exception, its message, and the first frame of
     * ours.
     *
     * "Ours" matters. The top frame of a Compose crash is nearly always inside the Compose
     * runtime and identical across completely unrelated bugs, so grouping on it would file every
     * UI crash in the app under one heading. The first `com.mlmvpn` frame is the line a developer
     * would actually open.
     *
     * In a JVM report the exception is the line after `--- stack ---`. It used to be the first
     * line anywhere that said "Exception" or "Error", and a breadcrumb that happened to say either
     * became the bug's title. Native-crash and ANR reports have no stack section; their `error:`
     * headline ([CrashText]) is the line.
     */
    fun signatureOf(text: String): String {
        val lines = text.lines()
        val stackAt = lines.indexOfFirst { it.trim() == "--- stack ---" }
        val stack = if (stackAt >= 0) lines.drop(stackAt + 1) else lines
        val cause = (if (stackAt >= 0) stack.firstOrNull { it.isNotBlank() } else null)?.trim()
            ?: lines.firstOrNull { it.startsWith("error") || it.contains("Exception") || it.contains("Error") }
                ?.trim()
                .orEmpty()
        val frame = stack.map { it.trim() }.firstOrNull { it.startsWith("at com.mlmvpn") }.orEmpty()
        return listOf(stable(cause), frame).filter { it.isNotBlank() }.joinToString("  |  ").take(300)
    }

    /**
     * The message without what changes from one occurrence to the next. The server files one issue
     * per summary, and "app is in background uid UidRecord{6492d3d u0a469 ...}" or an object's
     * @address differ every time: one bug became 96 issues through 1.2.39.
     */
    private fun stable(cause: String): String = cause
        .replace(Regex("(app is in background).*"), "$1")
        .replace(Regex("Intent \\{[^}]*\\}"), "Intent {…}")
        // a record's hash (ServiceRecord{eae6ac4 u0 …}) changes with every crash: 1.2.43 and 1.2.44
        // filed one Gereh service crash as 650 issues (mlmvpn/crashes)
        .replace(Regex("ServiceRecord\\{\\S+ \\S+ ([^ }]+)[^}]*\\}"), "ServiceRecord{$1}")
        .replace(Regex("(ProcessRecord|ActivityRecord|BroadcastRecord)\\{[^}]*\\}"), "$1{…}")
        .replace(Regex("@[0-9a-fA-F]{5,}"), "@…")
        .replace(Regex("\\b(pid|uid)=\\d+"), "$1=#")
        .replace(Regex("\\d{4,}"), "#")

    // ---------------------------------------------------------------------------- the outbox
    //
    // A marker file per report, in a directory: the crash handler of whichever process died adds
    // one, the upload job removes it, and the filesystem is the one store every process of the
    // app sees the same way (SharedPreferences are cached per process).

    private fun outbox() = File(logDir, "outbox").apply { mkdirs() }
    private fun sentDir() = File(logDir, "sent").apply { mkdirs() }

    private fun enqueue(report: File) {
        try {
            if (!File(sentDir(), report.name).exists()) File(outbox(), report.name).createNewFile()
        } catch (t: Throwable) {
            Log.w(TAG, "could not queue ${report.name}: ${t.message}")
        }
    }

    /** The reports waiting to be delivered, oldest first. */
    private fun queued(): List<File> {
        if (!this::logDir.isInitialized) return emptyList()
        val now = System.currentTimeMillis()
        return outbox().listFiles().orEmpty().mapNotNull { marker ->
            val report = File(logDir, marker.name)
            if (!report.isFile || now - report.lastModified() > OUTBOX_MAX_AGE_MS) {
                marker.delete()
                null
            } else {
                report
            }
        }.sortedBy { it.lastModified() }
    }

    private fun markSent(report: File) {
        runCatching { File(sentDir(), report.name).createNewFile() }
        File(outbox(), report.name).delete()
    }

    private val flushLock = Mutex()

    /**
     * Delivers what is waiting in the outbox. True when nothing is left waiting.
     *
     * Stops at the first report that does not go through: when the network or both servers are
     * down, the rest would fail the same way, and the job's backoff is the right pace to retry.
     */
    suspend fun flushQueue(context: android.content.Context): Boolean = flushLock.withLock {
        for (report in queued().take(MAX_PER_FLUSH)) {
            val text = runCatching { report.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
            if (text == null) {
                File(outbox(), report.name).delete()
                continue
            }
            val ok = CrashClient.send(
                context,
                // Reports written before redaction existed are cleaned here too.
                summary = SecretRedactor.redact(signatureOf(text)),
                body = SecretRedactor.redact(text),
                kind = CrashText.kindOf(text),
            )
            if (!ok) break
            markSent(report)
        }
        queued().isEmpty()
    }

    /**
     * With automatic sending on: every report the user has not been asked about goes into the
     * outbox -- written by a native crash or an ANR on this launch, or by a JVM crash whose own
     * handler could not queue it.
     */
    private fun queueUnsent(context: android.content.Context) {
        if (!autoSend()) return
        val prefs = context.getSharedPreferences("crash_diag", android.content.Context.MODE_PRIVATE)
        val offeredUpTo = prefs.getLong("offered_up_to", 0L)
        val fresh = crashReports().filter { it.lastModified() > offeredUpTo }
        if (fresh.isEmpty()) return
        fresh.forEach { enqueue(it) }
        prefs.edit()
            .putLong("offered_up_to", fresh.maxOf { it.lastModified() })
            .putLong("auto_reported_at", System.currentTimeMillis())
            .apply()
    }

    /**
     * Sends, now, what the user agreed to in the launch dialog (automatic sending off). True when
     * it was all delivered; whatever was not stays queued, and the job keeps trying.
     */
    suspend fun upload(context: android.content.Context): Boolean {
        val offeredUpTo = context.getSharedPreferences("crash_diag", android.content.Context.MODE_PRIVATE)
            .getLong("offered_up_to", 0L)
        // Every crash since the last offer, not only the newest: two in a row are as often two
        // bugs as one, and the dialog asked about "a crash" without saying which.
        val crashes = crashReports()
        crashes.filter { it.lastModified() > offeredUpTo }.ifEmpty { crashes.take(1) }.forEach { enqueue(it) }
        val done = flushQueue(context)
        if (!done) CrashUploadJob.schedule(context)
        return done
    }

    // ---------------------------------------------------------------------------- the choice

    /** Present when the user turned automatic sending off. A file, so every process sees it. */
    private fun manualMarker() = File(logDir, ".manual-send")

    /** Whether crash reports are sent without asking. On unless the user turned it off. */
    fun autoSend(): Boolean = this::logDir.isInitialized && !manualMarker().exists()

    fun setAutoSend(on: Boolean) {
        if (!this::logDir.isInitialized) return
        if (on) {
            manualMarker().delete()
        } else {
            runCatching { manualMarker().createNewFile() }
            // Queued without asking, so withdrawn with the permission.
            outbox().listFiles().orEmpty().forEach { it.delete() }
        }
    }

    /**
     * True once: the first launch after a report was sent automatically, so the user hears that
     * it happened and where to turn it off.
     */
    fun noticeDue(context: android.content.Context): Boolean {
        if (!autoSend()) return false
        val prefs = context.getSharedPreferences("crash_diag", android.content.Context.MODE_PRIVATE)
        return prefs.getLong("auto_reported_at", 0L) > 0L && !prefs.getBoolean("auto_notice_shown", false)
    }

    fun markNoticeShown(context: android.content.Context) {
        context.getSharedPreferences("crash_diag", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("auto_notice_shown", true).apply()
    }

    /**
     * Hand the newest reports to whatever the user wants to send them with.
     *
     * Plain text in an ACTION_SEND rather than a file attachment: the destination is almost
     * always Telegram, where a pasted stack is readable in the chat and a .txt is one more tap
     * and a download for whoever has to read it. A FileProvider would also need a manifest entry
     * and a grant for every target app, which is a lot of moving parts for a support message.
     */
    fun share(context: android.content.Context) {
        val text = collect()
        if (text.isBlank()) return
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "MLM VPN crash report")
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        runCatching {
            context.startActivity(
                android.content.Intent.createChooser(send, null)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * The newest report the user has not been asked about, or null -- for the launch dialog, which
     * only asks when automatic sending is off.
     *
     * Tracked by timestamp rather than by a "shown" flag per file so that a burst of reports from
     * one crash prompts once, and so that clearing the marker cannot resurrect old prompts.
     */
    fun unreportedCrash(context: android.content.Context): File? {
        if (autoSend()) return null
        val newest = crashReports().firstOrNull() ?: return null
        val prefs = context.getSharedPreferences("crash_diag", android.content.Context.MODE_PRIVATE)
        return if (newest.lastModified() > prefs.getLong("offered_up_to", 0L)) newest else null
    }

    /** Remember that the user has been shown everything up to now, whatever they chose. */
    fun markOffered(context: android.content.Context) {
        context.getSharedPreferences("crash_diag", android.content.Context.MODE_PRIVATE)
            .edit().putLong("offered_up_to", System.currentTimeMillis()).apply()
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
     * Asks the system how the previous runs actually died, writes that down, and turns every
     * crash it did not already have a report for -- native crashes and ANRs above all -- into one.
     *
     * ApplicationExitInfo carries the process NAME (main or the tunnel's), the importance at the
     * time of death (foreground vs cached), the system's description, the trace for native
     * crashes and ANRs, and the state summary this process published through [note]: its version
     * and the last thing it was doing.
     */
    private fun reportPreviousExit(app: Application) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        try {
            val am = activityManager ?: return
            val exits = am.getHistoricalProcessExitReasons(app.packageName, 0, 16)
            if (exits.isEmpty()) return

            val diag = app.getSharedPreferences("crash_diag", android.content.Context.MODE_PRIVATE)
            val deliberateAt = diag.getLong("deliberate_exit_at", 0L)
            val deliberateWhy = diag.getString("deliberate_exit_why", null)
            // The system keeps a rolling list, so every launch used to write the same exits down
            // again -- one file per launch, which crowded the real crash stacks out of what users
            // sent and, through [prune], off the phone. Only exits not seen before count now.
            val seenUpTo = diag.getLong("exits_seen_up_to", 0L)
            val fresh = exits.filter { it.timestamp > seenUpTo }
            diag.edit()
                .remove("deliberate_exit_at").remove("deliberate_exit_why")
                .putLong("exits_seen_up_to", maxOf(seenUpTo, exits.maxOf { it.timestamp }))
                .apply()

            // Ours or the bug? If we marked a deliberate exit within a minute of it, it was ours.
            fun ours(e: android.app.ApplicationExitInfo) =
                deliberateAt > 0L && kotlin.math.abs(e.timestamp - deliberateAt) < 60_000

            val newest = exits[0]
            lastExitSummary = "exit: ${reasonName(newest.reason)}/${newest.status} " +
                "proc=${newest.processName.substringAfterLast(':', "main")} " +
                "imp=${newest.importance}" + if (ours(newest)) " (ours: $deliberateWhy)" else " (NOT ours)"
            Log.e(TAG, "LAST EXIT → $lastExitSummary")

            // Written down only when a run ended badly. Swiped from Recents, updated, reclaimed
            // while cached: that is how most runs end, and it says nothing about a bug.
            val bad = fresh.filter { noteworthy(it) && !ours(it) }
            if (bad.isEmpty()) return
            val traces = HashMap<android.app.ApplicationExitInfo, String>()
            val text = buildString {
                appendLine("=== how previous runs ended (newest first) ===")
                appendLine("recorded at : ${Date()}")
                if (deliberateAt > 0L) {
                    appendLine("NOTE: this app deliberately killed itself at ${Date(deliberateAt)} ($deliberateWhy)")
                }
                appendLine()
                fresh.forEach { e ->
                    appendLine("time       : ${Date(e.timestamp)}")
                    appendLine("process    : ${e.processName} (pid ${e.pid})")
                    appendLine("reason     : ${reasonName(e.reason)} (${e.reason})")
                    appendLine("status     : ${e.status}")
                    appendLine("importance : ${e.importance}")
                    appendLine("description: ${e.description}")
                    stateOf(e)?.let { appendLine("state      : $it") }
                    // Present for ANR/native-crash exits; this is the actual stack when there is one.
                    try {
                        e.traceInputStream?.use { s ->
                            val trace = readableTrace(e.reason, s.readBytes())
                            traces[e] = trace
                            appendLine("trace      :\n$trace")
                        }
                    } catch (_: Throwable) {}
                    appendLine("---")
                }
            }
            Log.e(TAG, text)
            File(logDir, "$LAST_EXIT_PREFIX${stamp.format(Date())}.txt").writeText(SecretRedactor.redact(text))

            bad.forEach { e -> reportFromExit(e, traces[e]) }
        } catch (t: Throwable) {
            Log.w(TAG, "could not read exit reasons: ${t.message}")
        }
    }

    /**
     * A crash report of its own for an exit that left none: every native crash and ANR, a JVM
     * crash whose handler could not write (out of memory, out of disk), and the system killing the
     * app for being stuck at start-up or for using too much.
     */
    private fun reportFromExit(e: android.app.ApplicationExitInfo, trace: String?) {
        val kind = when (e.reason) {
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native"
            // A process that died of a signal: a Go panic aborts with SIGABRT, and when the system's
            // crash dumper cannot unwind it (Xray on Android 11, Outline «تست همه», 2026-10-11) the
            // death is recorded as SIGNALED, not CRASH_NATIVE -- and went unreported. Only SIGKILL is
            // left out: that is the system or this app (killProcess) ending a process on purpose.
            android.app.ApplicationExitInfo.REASON_SIGNALED -> if (e.status == 9) return else "native"
            android.app.ApplicationExitInfo.REASON_ANR -> "anr"
            android.app.ApplicationExitInfo.REASON_CRASH,
            android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            // Native code ending the process with an error, a process killed because one it
            // depended on died, killed for memory while carrying the tunnel: [noteworthy] already
            // keeps only the ones that can be a bug, and each was written down and never sent.
            android.app.ApplicationExitInfo.REASON_EXIT_SELF,
            android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "jvm"
            else -> return
        }
        // A JVM crash that wrote its own report needs no second one.
        if (e.reason == android.app.ApplicationExitInfo.REASON_CRASH &&
            crashReports().any { kotlin.math.abs(it.lastModified() - e.timestamp) < 30_000 }
        ) return

        val state = stateOf(e)
        // A signal death carries no tombstone: the signal's name is all there is to group it by.
        val signal = if (e.reason == android.app.ApplicationExitInfo.REASON_SIGNALED) when (e.status) {
            6 -> "SIGABRT"; 11 -> "SIGSEGV"; 7 -> "SIGBUS"; 4 -> "SIGILL"; 8 -> "SIGFPE"; 5 -> "SIGTRAP"; 3 -> "SIGQUIT"; 15 -> "SIGTERM"
            else -> "signal ${e.status}"
        } else null
        val step = state?.substringAfter('|', "")
        val where = step?.let { AREA_TAG.find(it)?.groupValues?.get(1) }
        val headline = when (kind) {
            "native" -> CrashText.nativeHeadline(e.processName, listOfNotNull(signal, trace).joinToString("\n"), where)
            "anr" -> CrashText.anrHeadline(e.processName, e.description, where)
            else -> "error: ${reasonName(e.reason)} (${CrashText.processLabel(e.processName)})" + (where?.let { " [$it]" } ?: "") +
                (e.description?.takeIf { it.isNotBlank() }?.let { ": " + CrashText.normalize(it).take(160) } ?: "")
        }
        val text = buildString {
            appendLine(
                when (kind) {
                    "native" -> CrashText.NATIVE_HEADER
                    "anr" -> CrashText.ANR_HEADER
                    else -> CrashText.JVM_HEADER
                }
            )
            // The line the report is grouped by, first, ahead of anything that could look like one.
            appendLine(headline)
            appendLine("time      : ${Date(e.timestamp)}")
            appendLine("process   : ${e.processName} (pid ${e.pid})")
            appendLine("version   : ${state?.substringBefore('|')?.removePrefix("v=") ?: "? (reported by $appVersion)"}")
            appendLine("device    : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
            state?.substringAfter('|', "")?.takeIf { it.isNotBlank() }?.let { appendLine("last step : $it") }
            appendLine("reason    : ${reasonName(e.reason)} status ${e.status}, importance ${e.importance}")
            appendLine("described : ${e.description}")
            appendLine("settings  : ${settingsLine()} (now)")
            savedCrumbs(e)?.let {
                appendLine()
                appendLine("--- breadcrumbs (saved by the process before it died, most recent last) ---")
                appendLine(it)
            }
            if (!trace.isNullOrBlank()) {
                appendLine()
                appendLine(if (kind == "native") "--- tombstone (readable parts) ---" else "--- trace ---")
                appendLine(trace)
            }
        }
        val file = writeReport(text, tag = kind + "-" + CrashText.processLabel(e.processName).removePrefix(":"))
        // Dated when it happened, not when it was found: the outbox and the dialog order by it.
        runCatching { file.setLastModified(maxOf(e.timestamp, 1L)) }
        if (autoSend()) enqueue(file)
    }

    /** What the process published through [publishState]: "v=<version>|<last step>", or null. */
    private fun stateOf(e: android.app.ApplicationExitInfo): String? = try {
        e.processStateSummary?.takeIf { it.isNotEmpty() }?.decodeToString()
    } catch (t: Throwable) {
        null
    }

    /**
     * An exit that can be a bug: a crash of either kind, an ANR, the system killing a process for
     * what it did, or a process ending itself with an error the app did not ask for.
     */
    private fun noteworthy(e: android.app.ApplicationExitInfo): Boolean = when (e.reason) {
        android.app.ApplicationExitInfo.REASON_CRASH,
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
        android.app.ApplicationExitInfo.REASON_ANR,
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        android.app.ApplicationExitInfo.REASON_SIGNALED -> true
        // Only a provider of this app's own: under an app cloner the app runs beside the cloner's
        // providers ("com.excelliance...PluginManagerProvider"), and their deaths are the cloner's.
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED ->
            e.description.orEmpty().let { it.contains("/com.mlmvpn.") || it.contains("/.") }
        // The helper processes end themselves by design: tun2proxy's host exits(255) after every
        // teardown (see Tun2proxyHostService), the Xray hosts end once their last core stops
        // (XrayHosts, XrayProbeService). In the main process a nonzero exit is native code ending
        // the process under us.
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> e.status != 0 && !e.processName.contains(':')
        // Killed for memory while the user was looking at it. A helper process, or the app in the
        // background, being reclaimed is the system making room, not a fault: those filled the
        // tracker with LOW_MEMORY "crashes" from every busy phone (1.2.41).
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY ->
            !e.processName.contains(':') &&
                e.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        else -> false
    }

    /**
     * An exit's trace as text, and only its top. An ANR's is a thread dump, text already, and can
     * run to megabytes; a native crash's is a binary tombstone, of which the readable runs --
     * the abort message, the backtrace's library and function names -- are the useful part.
     */
    private fun readableTrace(reason: Int, bytes: ByteArray): String {
        val text = if (reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) {
            CrashText.readableRuns(bytes)
        } else {
            bytes.decodeToString()
        }
        if (text.length <= MAX_TRACE_CHARS) return text
        // What lies past the cut still counts: the app's libraries and any abort message, first.
        val key = if (reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) CrashText.keyLines(text) else ""
        return key + text.take(MAX_TRACE_CHARS) + "\n… (trace truncated)"
    }

    private fun reasonName(reason: Int): String = when (reason) {
        1 -> "EXIT_SELF"; 2 -> "SIGNALED"; 3 -> "LOW_MEMORY"; 4 -> "CRASH(jvm)"
        5 -> "CRASH_NATIVE"; 6 -> "ANR"; 7 -> "INITIALIZATION_FAILURE"; 8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"; 10 -> "USER_REQUESTED"; 11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"; 13 -> "OTHER"; 14 -> "FREEZER"; 15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"; else -> "UNKNOWN"
    }

    /** The switches that change which code an engine runs: they differed between a tester and the owner. */
    private fun settingsLine(): String = try {
        val ctx = appContext
        val split = com.mlmvpn.core.tunnel.SplitTunnelSettings(ctx).mode().name.lowercase()
        val smart = com.mlmvpn.core.tunnel.SmartIranRoute.enabled(ctx)
        val lan = com.mlmvpn.scanner.utils.NetworkSettings.allowLan(ctx)
        "split=$split smartIran=${if (smart) "on" else "off"} lan=${if (lan) "on" else "off"}"
    } catch (t: Throwable) {
        "?"
    }

    private fun memoryLine(): String = try {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        "${used} MB used of ${rt.maxMemory() / (1024 * 1024)} MB"
    } catch (t: Throwable) {
        "?"
    }

    /**
     * Writes this process's breadcrumbs to `crumbs-<pid>.log` within a second of a new one, off the
     * caller's thread. A native crash, an ANR or a JVM crash whose handler could not run leaves no
     * report of its own; the next launch finds this file by the dead process's pid.
     */
    private fun saveCrumbsSoon() {
        if (!this::logDir.isInitialized || !crumbsDirty.compareAndSet(false, true)) return
        runCatching {
            crumbWriter.schedule({
                crumbsDirty.set(false)
                runCatching {
                    val text = synchronized(breadcrumbs) { breadcrumbs.joinToString("\n") }
                    val target = File(logDir, "$CRUMBS_PREFIX${android.os.Process.myPid()}.log")
                    val temp = File(logDir, target.name + ".part")
                    temp.writeText(text)
                    if (!temp.renameTo(target)) { target.writeText(text); temp.delete() }
                }
            }, 1, java.util.concurrent.TimeUnit.SECONDS)
        }.onFailure { crumbsDirty.set(false) }
    }

    /** The breadcrumbs the process of [e] saved, if they are its own (not a later process's with the same pid). */
    private fun savedCrumbs(e: android.app.ApplicationExitInfo): String? = try {
        File(logDir, "$CRUMBS_PREFIX${e.pid}.log")
            .takeIf { it.exists() && it.lastModified() <= e.timestamp + 5_000 && e.timestamp - it.lastModified() < 6 * 3600_000L }
            ?.readText()?.takeLast(8_000)?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        null
    }

    /**
     * A freeze, caught while it happens. The system's ANR record arrives only on the next launch,
     * only when the system recorded one (some phones kill a frozen app outright, which no report
     * follows), and grouped by a line that often matches an older bug. So: a watchdog thread asks
     * the main thread to answer every second; when it has not for [FREEZE_MS] while a screen is
     * showing, the main thread's stack -- the code it is stuck in -- and the breadcrumbs are written
     * as a report and handed to the upload job at once, before anything can kill the process.
     */
    private fun watchMainThread(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: android.app.Activity) { visible++ }
            override fun onActivityPaused(a: android.app.Activity) { visible = (visible - 1).coerceAtLeast(0) }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        val looper = android.os.Looper.getMainLooper()
        val main = android.os.Handler(looper)
        Thread({
            var reported = 0
            while (reported < MAX_FREEZE_REPORTS) {
                val answered = java.util.concurrent.atomic.AtomicBoolean(false)
                main.post { answered.set(true) }
                val asked = android.os.SystemClock.uptimeMillis()
                while (!answered.get() && android.os.SystemClock.uptimeMillis() - asked < FREEZE_MS) Thread.sleep(200)
                if (!answered.get() && visible > 0 && !crashing) {
                    // Where it is stuck at the first look, kept for the report: the second look
                    // shows whether it is the same place (a hang) or somewhere else (slow work).
                    val first = runCatching { looper.thread.stackTrace }.getOrNull()
                    while (!answered.get() && android.os.SystemClock.uptimeMillis() - asked < FREEZE_REPORT_MS) Thread.sleep(200)
                    val stuck = android.os.SystemClock.uptimeMillis() - asked
                    if (!answered.get() && visible > 0 && !crashing) {
                        runCatching { reportFreeze(looper.thread, stuck, first) }
                        reported++
                        // One report per freeze: wait for it to end (or for the process to be killed).
                        while (!answered.get()) Thread.sleep(500)
                    } else if (answered.get() && !crashing) {
                        // Slow, not frozen: a breadcrumb, so a later report shows it happened.
                        val at = first?.firstOrNull { it.className.startsWith("com.mlmvpn") }
                        note("slow main thread ${stuck / 100 * 100} ms" + (at?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}" } ?: ""))
                    }
                }
                Thread.sleep(1_000)
            }
        }, "crash-freeze-watch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun reportFreeze(main: Thread, stuckMs: Long, first: Array<StackTraceElement>?) {
        val stack = main.stackTrace
        val where = area
        val text = buildString {
            appendLine(CrashText.ANR_HEADER)
            // Grouped by the screen and (through signatureOf) the first frame of the app's own code.
            appendLine("error: freeze (main)" + (where?.let { " [$it]" } ?: "") + ": the main thread stopped answering")
            appendLine("time      : ${Date()}")
            appendLine("version   : $appVersion")
            appendLine("device    : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
            appendLine("stuck for : $stuckMs ms and counting")
            appendLine("settings  : ${settingsLine()}")
            appendLine("memory    : ${memoryLine()}")
            appendLine()
            appendLine("--- breadcrumbs (most recent last) ---")
            synchronized(breadcrumbs) { breadcrumbs.forEach { appendLine(it) } }
            appendLine()
            appendLine("--- main thread ---")
            stack.forEach { appendLine("\tat $it") }
            appendLine()
            if (first != null && !first.contentEquals(stack)) {
                appendLine("--- main thread at the first look (${FREEZE_MS / 1000} s) ---")
                first.take(30).forEach { appendLine("\tat $it") }
                appendLine()
            }
            appendLine("--- other threads (top frames) ---")
            Thread.getAllStackTraces().entries
                .filter { (t, st) -> t !== main && st.isNotEmpty() && t.name != "crash-freeze-watch" }
                .sortedByDescending { (_, st) -> st.any { it.className.startsWith("com.mlmvpn") } }
                .take(40)
                .forEach { (t, st) ->
                    appendLine("\"${t.name}\" ${t.state}")
                    st.take(10).forEach { appendLine("\t  $it") }
                }
        }
        Log.e(TAG, text)
        val file = writeReport(text, tag = "freeze-main")
        if (autoSend()) {
            enqueue(file)
            CrashUploadJob.schedule(appContext)
        }
    }

    /**
     * MLM CHAT: the watchdog of the chat process (`:chat`). On Ehsan's S26 (27 Oct 2026) Telegram's
     * storage queue stopped answering: every chat sat on its loading circle, and closing the app did
     * not help, because Gereh's foreground service keeps the process alive. The watching itself
     * (which queues, how long, the restart) is Gereh's bridge's now, for every app that carries it
     * ([org.mlmvpn.chat.bridge.GerehWatchdog]); what a stall becomes is the app's: here a report like
     * any freeze's, filed and sent ([reportChatStall]).
     */
    fun watchChatQueues(app: Application) {
        org.mlmvpn.chat.bridge.GerehWatchdog.start(app, object : org.mlmvpn.chat.bridge.GerehWatchdog.Host {
            override fun stalled(stalls: Array<org.mlmvpn.chat.bridge.GerehWatchdog.Stall>) {
                stalls.forEach { runCatching { reportChatStall(it) } }
            }

            override fun stillStuck(stalls: Array<org.mlmvpn.chat.bridge.GerehWatchdog.Stall>) {
                // filed at the first word (stalled); the bridge restarts the process now
            }

            override fun crashing(): Boolean = this@CrashReporter.crashing

            override fun note(line: String) = this@CrashReporter.note(line)
        })
    }

    private fun reportChatStall(s: org.mlmvpn.chat.bridge.GerehWatchdog.Stall) {
        val text = buildString {
            appendLine(CrashText.ANR_HEADER)
            appendLine("error: freeze (chat ${s.queue} queue)" + (s.where?.let { " in $it" } ?: "") + ": it stopped answering")
            appendLine("time      : ${Date()}")
            appendLine("version   : $appVersion")
            appendLine("device    : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
            appendLine("stuck for : ${s.stuckMs} ms and counting")
            appendLine("settings  : ${settingsLine()}")
            appendLine("memory    : ${memoryLine()}")
            appendLine()
            appendLine("--- breadcrumbs (most recent last) ---")
            synchronized(breadcrumbs) { breadcrumbs.forEach { appendLine(it) } }
            appendLine()
            // the stuck queue's whole stack, then every other thread's top (the bridge wrote both)
            append(s.stack)
            appendLine()
            append(s.others)
        }
        Log.e(TAG, text)
        val file = writeReport(text, tag = "freeze-chat-${s.queue}")
        if (autoSend()) {
            enqueue(file)
            CrashUploadJob.schedule(appContext)
        }
    }

    /**
     * Hands the system a line to keep with this process: its version and the last thing it was
     * doing. If the process then dies where no handler runs -- a native crash, an ANR -- the
     * system gives the line back with the record of the death, and [reportFromExit] puts it in the
     * report: "openTab home -> mae" is often the whole answer to where it happened.
     */
    private fun publishState(step: String) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        try {
            val tagged = area?.let { "[$it] $step" } ?: step
            var bytes = "v=$appVersion|$tagged".toByteArray()
            // The system keeps at most 128 bytes.
            if (bytes.size > 127) bytes = bytes.copyOf(127)
            activityManager?.setProcessStateSummary(bytes)
        } catch (t: Throwable) {
            // Best effort: losing the line costs a little context, never the app.
        }
    }

    /**
     * Record what the user is doing. Cheap enough to call on every screen change or connect —
     * a string append and one call to the system, not I/O.
     */
    fun note(message: String) {
        val clean = SecretRedactor.redact(message)
        val line = "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())}  $clean"
        synchronized(breadcrumbs) {
            breadcrumbs.addLast(line)
            while (breadcrumbs.size > MAX_BREADCRUMBS) breadcrumbs.removeFirst()
        }
        AREA_STEP.find(clean)?.let { area = it.groupValues[1] }
        // A binder call to the system, so not on the caller's thread: breadcrumbs are noted from
        // the main thread, and a busy system_server held it there (freeze report, 1.2.41). One
        // thread, so the newest state is the one that stays.
        runCatching { crumbWriter.execute { publishState(clean) } }.onFailure { publishState(clean) }
        saveCrumbsSoon()
        // Echoed to logcat under the same tag so it is visible in the run-up to a *native*
        // crash too, where the file below is never written.
        Log.i(TAG, "• $line")
    }

    private fun buildReport(thread: Thread, error: Throwable): String = buildString {
        appendLine(CrashText.JVM_HEADER)
        appendLine("time      : ${Date()}")
        appendLine("thread    : ${thread.name}")
        appendLine("version   : $appVersion")
        appendLine("device    : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                "Android ${android.os.Build.VERSION.RELEASE}")
        appendLine("settings  : ${settingsLine()}")
        appendLine("memory    : ${memoryLine()}")
        appendLine()
        appendLine("--- breadcrumbs (most recent last) ---")
        synchronized(breadcrumbs) { breadcrumbs.forEach { appendLine(it) } }
        appendLine()
        appendLine("--- stack ---")
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        appendLine(sw.toString())
    }

    /**
     * Keep the directory from growing without bound over a long stress run -- by kind. It used to
     * keep the newest thirty files of any kind, and the notes the app writes on its own outnumber
     * crashes many times over, so the stacks were the files that went.
     */
    private fun prune() {
        val files = logDir.listFiles()?.filter { it.isFile } ?: return
        fun keepNewest(n: Int, matches: (String) -> Boolean) = files.filter { matches(it.name) }
            .sortedByDescending { it.lastModified() }
            .drop(n)
            .forEach { it.delete() }
        keepNewest(KEEP_CRASHES) { it.startsWith(CRASH_PREFIX) && it.endsWith(".txt") }
        keepNewest(KEEP_NOTES) { it.startsWith(LAST_EXIT_PREFIX) }
        keepNewest(KEEP_NOTES) { it.startsWith(EXIT_PREFIX) }
        keepNewest(KEEP_NOTES + 1) { it.startsWith(CRUMBS_PREFIX) && it.endsWith(".log") }
        // A write the process died in the middle of; never renamed, never readable as a report.
        // Old ones only: another process of the app may be writing one this very moment.
        files.filter { it.name.endsWith(".part") && System.currentTimeMillis() - it.lastModified() > 60_000 }
            .forEach { it.delete() }
        // Queue and delivery markers of reports that are gone.
        listOf(File(logDir, "outbox"), File(logDir, "sent")).forEach { dir ->
            dir.listFiles().orEmpty().filter { !File(logDir, it.name).exists() }.forEach { it.delete() }
        }
    }
}
