package com.mlmvpn.scanner.crash

/** What kind of failure a report is about; the collector labels issues by it. */
enum class CrashKind(val wire: String) { JVM("jvm"), NATIVE("native"), ANR("anr") }

/**
 * The words of a crash report that decide how reports group. Pure, so it is unit tested
 * (CrashTextTest): a headline that differs between two occurrences of one bug files that bug as
 * many issues, and one that is too vague files many bugs as one.
 */
object CrashText {

    const val JVM_HEADER = "=== MLMVPN crash ==="
    const val NATIVE_HEADER = "=== MLMVPN native crash ==="
    const val ANR_HEADER = "=== MLMVPN ANR ==="

    fun kindOf(text: String): CrashKind = when (text.lineSequence().firstOrNull()?.trim()) {
        NATIVE_HEADER -> CrashKind.NATIVE
        ANR_HEADER -> CrashKind.ANR
        else -> CrashKind.JVM
    }

    private val HEX_ADDRESS = Regex("0x[0-9a-fA-F]+")
    /** A token of hex digits with at least one digit in it: a window token, a hash, an id. */
    private val HEX_TOKEN = Regex("\\b(?=[0-9a-fA-F]*\\d)[0-9a-fA-F]{5,}\\b")
    private val NUMBER = Regex("\\d+")
    private val SPACE = Regex("\\s+")

    /**
     * A line that names a bug, without what changes between occurrences of it: an ANR's
     * "Waited 5001ms" and window token, a native abort's file descriptor and pointers.
     */
    fun normalize(line: String): String = line
        .replace(HEX_ADDRESS, "0x…")
        .replace(HEX_TOKEN, "…")
        .replace(NUMBER, "#")
        .replace(SPACE, " ")
        .trim()

    /**
     * The readable runs of a binary tombstone, one per line -- what `strings` would print. The
     * signal, the abort message and the libraries and functions of the backtrace are all in there.
     */
    fun readableRuns(bytes: ByteArray, min: Int = 6): String {
        val out = StringBuilder()
        val run = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 0x20..0x7e || c == 0x09) {
                run.append(c.toChar())
            } else {
                if (run.length >= min) out.append(run).append('\n')
                run.setLength(0)
            }
        }
        if (run.length >= min) out.append(run).append('\n')
        return out.toString()
    }

    /** "main" for the app's own process, ":tun" for the tunnel's, and so on. */
    fun processLabel(process: String): String =
        if (':' in process) ":" + process.substringAfterLast(':') else "main"

    private val SIGNAL = Regex("\\b(SIG[A-Z]{2,7})\\b")
    // Not a word boundary at the end: a tombstone's strings run into the next field ("libgojni.so0").
    private val LIBRARY = Regex("(/[^\\s()'\"]+\\.so)(?![A-Za-z_])")
    private val ABORT_HINTS = listOf("Abort message", "fdsan", "FORTIFY", "panicked", "CHECK failed", "assertion")

    /**
     * The one line a native crash is grouped by: its signal, the first of the app's own libraries
     * in the trace, the process, the screen the user was on ([area]), and the abort message.
     *
     * `error: native crash SIGABRT in libtun2proxy.so (:tun): fdsan: attempted to close file descriptor #, …`
     */
    fun nativeHeadline(process: String, trace: String, area: String? = null): String {
        val lines = trace.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val signal = lines.firstNotNullOfOrNull { SIGNAL.find(it)?.groupValues?.get(1) }
        val libraries = lines.flatMap { line -> LIBRARY.findAll(line).map { it.groupValues[1] }.toList() }
        val library = (libraries.firstOrNull { "/data/app/" in it }
            ?: libraries.firstOrNull { !it.startsWith("/system/") && !it.startsWith("/apex/") && !it.startsWith("/vendor/") }
            ?: libraries.firstOrNull())?.substringAfterLast('/')
        val abort = lines.firstOrNull { line -> ABORT_HINTS.any { line.contains(it, ignoreCase = true) } }
        return buildString {
            append("error: native crash")
            if (signal != null) append(' ').append(signal)
            if (library != null) append(" in ").append(library)
            append(" (").append(processLabel(process)).append(')')
            if (area != null) append(" [").append(area).append(']')
            if (abort != null) append(": ").append(normalize(abort.removePrefix("Abort message:").trim().trim('\'')).take(160))
        }
    }

    /**
     * The lines of a whole tombstone worth reading first: every library of the app's own that appears
     * in it (full path, so [nativeHeadline] still finds it) and any abort message. A report keeps only
     * the top of the tombstone, and the thread that aborted can come after the cut: a crash on
     * 2026-10-11 arrived with nothing but idle threads and no library at all.
     */
    fun keyLines(trace: String): String {
        val lines = trace.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val libraries = lines.flatMap { line -> LIBRARY.findAll(line).map { it.groupValues[1] }.toList() }
            .filter { "/data/app/" in it }.distinct()
        val aborts = lines.filter { line -> ABORT_HINTS.any { line.contains(it, ignoreCase = true) } }.distinct().take(5)
        if (libraries.isEmpty() && aborts.isEmpty()) return ""
        return buildString {
            appendLine("--- in the whole tombstone ---")
            libraries.forEach { appendLine(it) }
            aborts.forEach { appendLine(it.take(300)) }
            appendLine("---")
        }
    }

    /** The one line an ANR is grouped by: the process, the screen, and what the system said it waited for. */
    fun anrHeadline(process: String, description: String?, area: String? = null): String =
        "error: ANR (${processLabel(process)})" + (area?.let { " [$it]" } ?: "") +
            (description?.takeIf { it.isNotBlank() }?.let { ": " + normalize(it).take(160) } ?: "")
}
