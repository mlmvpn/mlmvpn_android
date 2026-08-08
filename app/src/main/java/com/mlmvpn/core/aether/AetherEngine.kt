package com.mlmvpn.core.aether

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns one Aether process on the device.
 *
 * Responsibilities:
 *   - Resolve the on-device Rust binary, which ships as jniLibs/<abi>/libaether.so and is
 *     unpacked to nativeLibraryDir by the installer. That directory is the only app-owned
 *     location we are still allowed to exec from on targetSdk >= 29.
 *   - spawn it with the right AETHER_* env vars translated from [AetherOptions]
 *   - own stderr/stdout threads that the parsing layer reads from
 *   - emit [AetherState] updates through [state] (a StateFlow the UI subscribes to) and
 *     [logs] (a SharedFlow, unbounded, used by the live log view)
 *
 * Talks to the rest of the app through:
 *   - the shared SOCKS5 endpoint it exposes (default 127.0.0.1:20810). Other VPNs and the
 *     Xray outbound can dial through it like a normal proxy; the engine is unaware of the
 *     chain beyond the env vars we hand it.
 *   - state events the [com.mlmvpn.scanner.ui.aether.AetherScreen] renders as steps and
 *     badges.
 *
 * Lifecycle: one Engine per running VPN tunnel. Stopping the engine stops the OS process
 * and tears down the SOCKS listener.
 */
class AetherEngine private constructor(
    private val context: Context,
) {

    private val tag = "AetherEngine"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processRef = java.util.concurrent.atomic.AtomicReference<Process?>(null)
    private val stopping = AtomicBoolean(false)
    private val logBuffer = ArrayDeque<String>(MAX_LOG_BUFFER)
    private val crashLogFile: File by lazy { File(context.filesDir, "aether/last-crash.log") }

    /** Stopped state shown by [state] before [start] has been called. */
    private val _state = MutableStateFlow(AetherState.IDLE)
    val state: StateFlow<AetherState> = _state.asStateFlow()

    /** Log lines, fanout to the UI. Replay 0: subscribers only see future lines. */
    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    /** True once the packaged executable has been located in nativeLibraryDir. */
    @Volatile var binaryReady: Boolean = false
        private set

    /** Whether every engine line is copied to logcat. Follows AetherOptions.verbose. */
    @Volatile private var mirrorToLogcat: Boolean = false

    /** Currently running process, or null when stopped. Exposed so [stop] can verify it
     * actually got killed without leaving a zombie holding the SOCKS port. */
    val process: Process? get() = processRef.get()

    /**
     * Locate the packaged Rust executable.
     *
     * Nothing is copied or chmod'd: the binary ships as `jniLibs/<abi>/libaether.so`, so the
     * package installer has already unpacked the ABI-correct copy into nativeLibraryDir with
     * the execute bit set. See [AetherBinaryLocator] for why exec'ing out of filesDir — what
     * this function used to do — is impossible on targetSdk >= 29.
     */
    fun prepareBinary(onError: (String) -> Unit = {}): File? {
        val exe = AetherBinaryLocator.executable(context)
        if (!exe.exists()) {
            // Almost always a packaging problem, not a device problem: the .so was not
            // bundled for this device's ABI. Name the ABIs so the report is actionable.
            onError(
                "فایل اجرایی aether در این نسخه از برنامه وجود ندارد " +
                    "(${AetherBinaryLocator.LIB_NAME} برای ${Build.SUPPORTED_ABIS.joinToString()})"
            )
            return null
        }
        if (!exe.canExecute()) {
            onError("فایل اجرایی aether قابل اجرا نیست: ${exe.absolutePath}")
            return null
        }
        binaryReady = true
        return exe
    }

    /**
     * Start the engine.
     *
     * @param onError a callback for fatal failures (asset missing, spawn failed). State-flow
     *                updates are not delivered through this; they're emitted on [state].
     */
    fun start(options: AetherOptions, onError: (String) -> Unit = {}): Boolean {
        // CRITICAL: every early-exit path MUST publish the FAILED state. Without it the
        // StateFlow stays at IDLE and the UI / notification show "خاموش" forever — users
        // see nothing happening after pressing connect. The onError callback fires for
        // the service's own log/notification but isn't enough on its own because (a) the
        // notification gets overwritten by the next "connecting" line in onStartCommand,
        // and (b) the Compose StateFlow subscribers would still see IDLE.
        if (stopping.get()) {
            onError("موتور در حال توقف است")
            _state.value = AetherState.failed("موتور در حال توقف است", "STOPPING")
            return false
        }
        if (processRef.get() != null) {
            onError("موتور قبلاً روشن است")
            _state.value = AetherState.failed("موتور قبلاً روشن است", "ALREADY_RUNNING")
            return false
        }
        val exe = prepareBinary { msg ->
            // Surface the prepareBinary error in BOTH places: callback for the service's
            // log + terminal notification, and StateFlow for the UI/notification flow
            // collector. The state is set BEFORE onError fires so by the time the
            // notification overwrite race in onStartCommand resolves, the state collector
            // has already overwritten "در حال اتصال…" with the proper "خطا".
            onError(msg)
            _state.value = AetherState.failed(msg, "BINARY_MISSING")
        } ?: return false

        val socksPort = AETHER_SOCKS_PORT
        val dataDir = File(context.filesDir, "aether/data").apply { mkdirs() }
        val env = options.toEnv(System.getenv(), dataDir.absolutePath, socksPort)
        val pb = ProcessBuilder(exe.absolutePath).apply {
            directory(dataDir)
            redirectErrorStream(false)
            environment().clear()
            environment().putAll(env)
        }

        val proc: Process
        try {
            proc = pb.start()
        } catch (e: Exception) {
            onError("اجرای فایل aether با خطا مواجه شد: ${e.message}")
            _state.value = AetherState.failed("اجرا ناموفق", e.message ?: "")
            return false
        }
        processRef.set(proc)
        mirrorToLogcat = options.verbose
        _state.value = AetherState.starting(options, socksPort)

        log("[AETHER] راه‌اندازی موتور")
        log("[AETHER] پروتکل: ${options.protocol.displayFa}")
        if (options.protocol == AetherProtocol.MASQUE) {
            log("[AETHER] ترنسپورت: ${if (options.transport == "h2") "HTTP/2 (TCP)" else "HTTP/3 (QUIC)"}")
            if (options.ech.isNotBlank()) log("[AETHER] ECH: ${options.ech}")
        }
        log("[AETHER] SOCKS محلی: 127.0.0.1:$socksPort")

        // Each stream on its own coroutine so a stall on one cannot pin the other. Read
        // line-by-line and dispatch into the parser. Lines that match the noise regex are
        // counted, not logged: at debug RUST_LOG level the engine emits thousands of them
        // per second, and pushing every one through the SharedFlow would saturate it.
        //
        // The counter is shared across three coroutines (the two readers and the periodic
        // summer) so it has to be thread-safe — Kotlin's captured Int has no happens-before
        // guarantee and the timer could otherwise observe stale values indefinitely.
        val noiseCount = java.util.concurrent.atomic.AtomicInteger(0)
        // Wrap the reader in `use {}` so the BufferedReader (and underlying process
        // stdout pipe fd) is closed when the coroutine finishes or is cancelled. Without
        // this, on process tear-down the JVM keeps the pipe fd open until GC, which can
        // stall the next spawn if the port is held.
        //
        // Both readers MUST swallow their exceptions. `readLine` on a process pipe is a
        // blocking native read, and stop() destroys the process out from under it, which
        // surfaces as IOException — or, when the coroutine dispatcher interrupts the
        // carrier thread, as InterruptedIOException. Neither is a CancellationException,
        // so an uncaught one propagates to the thread's default handler and takes the
        // whole app down. That was the "press disconnect, app dies" crash:
        //   FATAL EXCEPTION: DefaultDispatcher-worker-3
        //   java.io.InterruptedIOException: read interrupted
        //     at ...BufferedReader.readLine ... AetherEngine$start$2.invokeSuspend
        // A pipe dying during teardown is the expected path, not an error worth reporting.
        scope.launch {
            drainStream(proc.inputStream, isErr = false, noise = { noiseCount.incrementAndGet() })
        }
        scope.launch {
            drainStream(proc.errorStream, isErr = true, noise = { noiseCount.incrementAndGet() })
        }

        // Once every few seconds, if we suppressed any noise during that interval, print a
        // summary line so a long quiet period still proves the engine is alive.
        scope.launch {
            while (processRef.get() === proc) {
                kotlinx.coroutines.delay(5_000)
                val n = noiseCount.getAndSet(0)
                if (n > 0) log("[AETHER] … $n خط جزئیات بسته (نمایش داده نشد)")

                // Report *why* candidates are failing, not just that they are. Without this
                // a fruitless scan looks identical whether the network is dropping our UDP,
                // resetting our TLS, or answering and then swallowing data — and those need
                // completely different fixes.
                val snapshot = synchronized(probeFailures) {
                    val copy = probeFailures.toList()
                    probeFailures.clear()
                    copy
                }
                if (snapshot.isNotEmpty()) {
                    val total = snapshot.sumOf { it.second }
                    val top = snapshot.sortedByDescending { it.second }
                        .take(3)
                        .joinToString("، ") { "${it.first} ×${it.second}" }
                    log("[AETHER] $total کاندید رد شد — دلایل: $top")
                }
            }
        }

        // Watcher: when the OS process exits, fold its exit code into a terminal state.
        scope.launch {
            try {
                val rc = proc.waitFor()
                onExit(rc, options)
            } catch (e: Exception) {
                Log.w(tag, "waitFor interrupted: ${e.message}")
            }
        }
        return true
    }

    /**
     * Read one of the process's pipes to EOF, feeding every line to [handleLine].
     *
     * Never throws. See the call site for why that is load-bearing rather than defensive.
     * The `use {}` closes the BufferedReader (and the underlying pipe fd) on every exit
     * path, so tear-down doesn't leave the fd pinned until GC — which could otherwise
     * stall the next spawn while the old process still holds the SOCKS port.
     */
    private fun drainStream(stream: java.io.InputStream, isErr: Boolean, noise: () -> Unit) {
        try {
            BufferedReader(stream.reader(Charsets.UTF_8)).use { reader ->
                for (line in reader.lineSequence()) handleLine(line, isErr, noise)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // structured concurrency: cancellation must keep propagating
        } catch (e: Throwable) {
            Log.d(tag, "${if (isErr) "stderr" else "stdout"} reader ended: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Why candidates are being rejected, reason → count, reset every summary interval.
     *
     * Guarded by its own lock rather than a concurrent map because the summariser needs an
     * atomic read-and-clear, which a ConcurrentHashMap can't give without a second pass.
     */
    private val probeFailures = HashMap<String, Int>()

    private fun handleLine(line: String, isErr: Boolean, noise: () -> Unit) {
        val text = line.trim()
        if (text.isEmpty()) return
        // Two signals worth keeping out of the per-probe noise, because each one has a known
        // and different remedy: an access-denied means the identity needs re-enrolling, an
        // exhausted scan on h3 means the transport should fall back to h2.
        if (text.contains("ACCESS_DENIED", ignoreCase = true)) {
            if (!_state.value.sawAccessDenied) {
                _state.value = _state.value.copy(sawAccessDenied = true)
            }
        }
        if (text.contains("no usable", ignoreCase = true) ||
            text.contains("scan deadline reached with no gateway", ignoreCase = true) ||
            text.contains("no clean endpoint found", ignoreCase = true)
        ) {
            if (!_state.value.sawNoGateway) {
                _state.value = _state.value.copy(sawNoGateway = true)
            }
        }

        AetherStageParser.probeFailureReason(text)?.let { reason ->
            synchronized(probeFailures) {
                probeFailures[reason] = (probeFailures[reason] ?: 0) + 1
            }
            return  // counted, never forwarded — thousands of these per scan
        }
        if (AetherStageParser.isNoise(text)) { noise(); return }
        log(text)
        if (isErr && AetherStageParser.isPanicLine(text)) {
            Log.e(tag, text)
            // Make sure parent dirs exist before writing — `appendText` doesn't auto-mkdir,
            // and the dataDir is narrow (only contains identity .toml files), not logs.
            try {
                crashLogFile.parentFile?.mkdirs()
                crashLogFile.appendText("[${System.currentTimeMillis()}] $text\n")
            } catch (_: Exception) {}
        }
        val detail = AetherStageParser.extractDetail(text)
        // Stage transitions drive the main state.
        val rule = AetherStageParser.classify(text)
        if (rule != null) {
            // Always mirrored, regardless of the debug switch: a few dozen lines per session
            // at most, and they are the ones that answer "where did the connect get stuck".
            if (!mirrorToLogcat) Log.i(tag, text)
            val (stage, fa) = rule
            _state.value = _state.value.copy(stage = stage, stageFa = fa,
                server = detail.server ?: _state.value.server,
                rtt = detail.rtt ?: _state.value.rtt,
                profile = detail.profile ?: _state.value.profile,
                socks = detail.socks ?: _state.value.socks,
                connected = stage == AetherStage.CONNECTED || _state.value.connected,
            )
        } else if (detail.server != null || detail.rtt != null || detail.profile != null || detail.socks != null) {
            // Not a stage change but the user wants to see something moving.
            _state.value = _state.value.copy(
                server = detail.server ?: _state.value.server,
                rtt = detail.rtt ?: _state.value.rtt,
                profile = detail.profile ?: _state.value.profile,
                socks = detail.socks ?: _state.value.socks,
            )
        }        }

    private fun onExit(code: Int, options: AetherOptions) {
        processRef.set(null)
        if (stopping.get()) {
            _state.value = _state.value.copy(
                running = false, connected = false,
                stage = AetherStage.STOPPED, stageFa = "متوقف شد",
                exitCode = code,
            )
            return
        }
        // Distinguish panic from clean error from signal from normal exit, same way the
        // desktop manager does — the user copies us into a "panic" / "registration failed"
        // / "clean error" branch, and the right phrasing avoids blaming the wrong cause.
        val panicked = logBuffer.any { AetherStageParser.isPanicLine(it) }
        val apiFailed = logBuffer.any { AetherStageParser.isApiFailLine(it) }
        val label = when {
            code == 0 -> "متوقف شد"
            panicked -> "کرش موتور (کد $code)"
            apiFailed -> "ثبت‌نام ناموفق — API کلادفلر در دسترس نیست"
            else -> "خطا در اجرا (کد $code)"
        }
        _state.value = _state.value.copy(
            running = false, connected = false,
            stage = when {
                code == 0 -> AetherStage.STOPPED
                panicked -> AetherStage.CRASHED
                else -> AetherStage.FAILED
            },
            stageFa = label,
            exitCode = code,
        )
    }

    /**
     * Publish a terminal failure with a human-readable reason.
     *
     * For failures the engine itself cannot see. The tunnel layer above it can fail for
     * reasons the Rust process never reports — no gateway found within the deadline, the
     * user denying the VPN permission, the TUN failing to establish — and without this the
     * UI just fell back to "متوقف شد", which tells the user nothing about what went wrong.
     */
    fun reportFailure(message: String, detail: String = "") {
        log("[AETHER] $message${if (detail.isNotBlank()) " — $detail" else ""}")
        _state.value = AetherState.failed(message, detail)
    }

    /**
     * Stop the engine. Safe to call multiple times, including concurrently.
     *
     * The `stopping` flag is claimed with compareAndSet rather than plain set: two callers
     * racing here used to have the loser clear the flag in its own `finally` while the
     * winner was still inside destroy()/waitFor(), which made [start] and [onExit] briefly
     * see stopping == false mid-teardown and treat the exit as an unexpected crash.
     */
    fun stop() {
        val claimed = stopping.compareAndSet(false, true)
        val proc = processRef.getAndSet(null)
        if (proc != null) {
            try { proc.destroy() } catch (_: Exception) {}
            try { proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
            if (proc.isAlive) {
                try { proc.destroyForcibly() } catch (_: Exception) {}
            }
        }
        _state.value = _state.value.copy(
            running = false, connected = false,
            stage = AetherStage.STOPPED, stageFa = "متوقف شد",
        )
        // Only the caller that set the flag may clear it.
        if (claimed) stopping.set(false)
    }

    /** Whether the SOCKS port really accepts a TCP connection right now. Cheap probe used
     * by the UI to distinguish "engine starting" from "engine running". */
    fun isSocksAlive(timeoutMs: Int = 1500): Boolean {
        val state = _state.value
        if (!state.connected) return false
        val port = AETHER_SOCKS_PORT
        return try {
            Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        } catch (_: Exception) { false }
    }

    /** Drop stored identities so the next start provisions fresh Cloudflare accounts. */
    fun resetIdentity(): Int {
        var removed = 0
        try {
            val dir = File(context.filesDir, "aether/data")
            if (dir.exists()) {
                dir.listFiles()?.forEach { f ->
                    if (f.name.endsWith(".toml")) {
                        try { f.delete(); removed++ } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
        return removed
    }

    /**
     * Snapshot of the buffered log, oldest first.
     *
     * The UI needs this because [logs] is a replay-0 SharedFlow: a subscriber that attaches
     * after the engine started would otherwise see an empty box until the next line arrives,
     * which during a long scan can be many seconds. Seed from here, then stream from [logs].
     */
    fun logSnapshot(): List<String> = synchronized(logBuffer) { logBuffer.toList() }

    private fun log(text: String) {
        synchronized(logBuffer) {
            logBuffer.addLast(text)
            if (logBuffer.size > MAX_LOG_BUFFER) logBuffer.removeFirst()
        }
        // Mirror to logcat only when asked. These lines used to go nowhere but the in-memory
        // buffer, which made off-device diagnosis impossible; mirroring *every* line was the
        // opposite mistake. A logcat write is a socket round-trip to logd that throttles
        // under load, and the engine emits thousands of lines during a scan — enough for the
        // reader to fall behind, fill the child's stdout pipe, and block the scanner on
        // write. Stage transitions and panics are mirrored unconditionally by handleLine;
        // everything else needs the debug switch.
        // "[AETHER] …" lines are our own, a handful per session, and describe what was
        // asked for — always worth having in a bug report.
        if (mirrorToLogcat || text.startsWith("[AETHER]")) Log.i(tag, text)
        _logs.tryEmit(text)
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    companion object {
        const val AETHER_SOCKS_PORT = 20810
        const val TAG = "AetherEngine"
        private const val MAX_LOG_BUFFER = 2000

        @Volatile private var INSTANCE: AetherEngine? = null

        fun get(context: Context): AetherEngine =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AetherEngine(context.applicationContext).also { INSTANCE = it }
            }
    }
}

/** Snapshot the UI subscribes to. Keep it immutable so Compose subscribers don't have to
 * worry about who mutates it — every update goes through StateFlow. */
data class AetherState(
    val running: Boolean = false,
    val connected: Boolean = false,
    val stage: AetherStage = AetherStage.IDLE,
    val stageFa: String = "خاموش",
    val protocol: AetherProtocol = AetherProtocol.MASQUE,
    val socks: String = "127.0.0.1:${AetherEngine.AETHER_SOCKS_PORT}",
    val server: String? = null,
    val rtt: String? = null,
    val profile: String? = null,
    val error: String? = null,
    val exitCode: Int? = null,

    /**
     * Cloudflare answered a real endpoint and refused the session. Seen as
     * `[TLSV1_ALERT_ACCESS_DENIED]` among the probe rejections, and it means the stored
     * MASQUE identity is no longer accepted — re-enrolling fixes it, nothing else does.
     */
    val sawAccessDenied: Boolean = false,

    /** A whole scan finished without a usable gateway. */
    val sawNoGateway: Boolean = false,
) {
    companion object {
        val IDLE = AetherState()
        fun starting(options: AetherOptions, port: Int): AetherState =
            AetherState(running = true, stage = AetherStage.STARTING,
                protocol = options.protocol, socks = "127.0.0.1:$port")
        fun failed(message: String, error: String) = AetherState(
            running = false, stage = AetherStage.FAILED, stageFa = message, error = error)
    }
}
