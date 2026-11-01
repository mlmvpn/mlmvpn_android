package com.mlmvpn.scanner.store

import com.mlmvpn.scanner.utils.alive
import com.mlmvpn.scanner.utils.killHard
import com.mlmvpn.scanner.utils.waitUpTo

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Running an engine the store downloaded.
 *
 * Android 10+ refuses `exec()` of any file in an app's own data folder (W^X, targetSdk ≥ 29) —
 * which is why the shipped engines are packaged as `lib*.so` into nativeLibraryDir, the one place
 * the package installer can write and the app cannot. A downloaded engine can never go there.
 *
 * Two doors stay open, and each covers one kind of engine:
 *
 *  * EXEC engines (aether, tor, lyrebird) are dynamically linked PIE executables. The system's own
 *    dynamic linker is a system file, so exec'ing IT is allowed, and it maps the program it is
 *    handed the same way it maps any library: `/system/bin/linker64 <engine> <args…>`. Checked
 *    against every shipped engine: all are PIE with `/system/bin/linker64` as their interpreter —
 *    except rstaspoof, which is static and therefore stays "with the app".
 *  * JNI engines (the GST core, the tunnel core) are loaded with `System.load(path)`, which reads
 *    from app-private storage like any hot-fixed library.
 *
 * A JNI library that crashes while loading takes the process with it, so every load is fenced: a
 * marker with the pid is written first and removed after. A marker left behind by a process that
 * no longer exists means the load killed it, and that copy is set aside at the next start — the
 * shipped library is used again, with no action from the user.
 */
object StoreEngines {

    private const val TAG = "StoreEngines"

    @Volatile private var app: Context? = null

    /** From Application.onCreate, in every process, before any engine can be started. */
    fun init(context: Context) {
        app = context.applicationContext
        runCatching { checkLoadMarkers(context.applicationContext) }
    }

    fun linker(): String =
        if (android.os.Process.is64Bit()) "/system/bin/linker64" else "/system/bin/linker"

    /**
     * The command that starts an EXEC engine: the store's copy through the linker, or the shipped
     * file exactly as before.
     */
    fun command(context: Context, id: String, lib: String): List<String> {
        val store = StoreFiles.activeFile(context, id, lib)
        if (store != null) {
            Log.i(TAG, "$id: using the store's copy ${store.absolutePath}")
            return listOf(linker(), store.absolutePath)
        }
        return listOf(File(context.applicationInfo.nativeLibraryDir, lib).absolutePath)
    }

    /** Whether an EXEC engine can be started at all — the store's copy or the shipped one. */
    fun available(context: Context, id: String, lib: String): Boolean =
        StoreFiles.activeFile(context, id, lib) != null ||
            File(context.applicationInfo.nativeLibraryDir, lib).exists()

    // ── JNI ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Load one JNI library by its short name (`mhrv_rs`), preferring the store's copy.
     *
     * Falls back to the shipped library on ANY failure, so a bad download costs one log line,
     * never a feature.
     */
    fun loadLibrary(id: String, name: String) {
        val ctx = app
        val file = ctx?.let { StoreFiles.activeFile(it, id, "lib$name.so") }
        if (ctx != null && file != null) {
            val marker = markerFile(ctx, id)
            try {
                marker.parentFile?.mkdirs()
                marker.writeText(android.os.Process.myPid().toString())
                System.load(file.absolutePath)
                marker.delete()
                Log.i(TAG, "$id: loaded the store's lib$name.so")
                return
            } catch (t: Throwable) {
                marker.delete()
                Log.w(TAG, "$id: the store's lib$name.so did not load (${t.message}); using the shipped one")
                StoreFiles.quarantine(ctx, id, t.message ?: t.javaClass.simpleName)
            }
        }
        System.loadLibrary(name)
    }

    private fun markerFile(context: Context, id: String) = File(StoreFiles.itemDir(context, id), "loading.pid")

    private fun checkLoadMarkers(context: Context) {
        val items = File(context.filesDir, "store/items").listFiles() ?: return
        for (dir in items) {
            val marker = File(dir, "loading.pid")
            if (!marker.exists()) continue
            val pid = marker.readText().trim().toIntOrNull()
            // Still alive: another process of ours is loading it right now. Leave it be.
            if (pid != null && File("/proc/$pid").exists()) continue
            marker.delete()
            StoreFiles.quarantine(context, dir.name, tr("هنگام بارگذاری از کار افتاد", "It crashed while loading"))
            Log.w(TAG, "${dir.name}: the store's copy crashed the process that loaded it — set aside")
        }
    }

    // ── checking a file before it is used ──────────────────────────────────────────────────────

    /** ELF machine numbers of the ABIs this app builds for. */
    private fun machineFor(abi: String): Int? = when (abi) {
        "arm64-v8a" -> 183
        "armeabi-v7a" -> 40
        "x86_64" -> 62
        "x86" -> 3
        else -> null
    }

    /**
     * Is this an ELF for THIS device, dynamically linked?
     *
     * The digest proves the file is the one published; this proves it is one the device can run —
     * a mislabelled asset would otherwise pass every check and then fail on connect.
     */
    fun checkElf(file: File, needDynamic: Boolean): String? {
        val h = ByteArray(64)
        file.inputStream().use { if (it.read(h) < 64) return tr("فایل ناقص است", "The file is truncated") }
        if (h[0] != 0x7f.toByte() || h[1] != 'E'.code.toByte() || h[2] != 'L'.code.toByte() || h[3] != 'F'.code.toByte()) {
            return tr("فایل اجرایی اندروید نیست", "Not an Android executable")
        }
        val machine = (h[18].toInt() and 0xff) or ((h[19].toInt() and 0xff) shl 8)
        val type = (h[16].toInt() and 0xff) or ((h[17].toInt() and 0xff) shl 8)
        val want = machineFor(Build.SUPPORTED_ABIS.firstOrNull() ?: "")
        val ok = Build.SUPPORTED_ABIS.mapNotNull { machineFor(it) }.contains(machine)
        if (want != null && !ok) return tr("برای پردازندهٔ این گوشی ساخته نشده", "Not built for this phone's processor")
        // ET_DYN (3) is what the linker can load; a static ET_EXEC (2) cannot go through it.
        if (needDynamic && type != 3) return tr("این فایل از راه لینکر اجرا نمی‌شود", "This file cannot be run through the linker")
        return null
    }

    /**
     * Which of [needles] (ASCII) do not occur anywhere in [file]. Read in chunks that overlap by the
     * longest needle, so a name split across two reads is still found and a 20 MB engine is never
     * held in memory at once.
     */
    fun missingStrings(file: File, needles: List<String>): List<String> {
        val left = needles.toMutableSet()
        val overlap = (needles.maxOfOrNull { it.length } ?: 0).coerceAtLeast(1)
        val buf = ByteArray(1 shl 20)
        var carry = ""
        file.inputStream().use { input ->
            while (left.isNotEmpty()) {
                val n = input.read(buf)
                if (n < 0) break
                val text = carry + String(buf, 0, n, Charsets.ISO_8859_1)
                left.removeAll { text.contains(it) }
                carry = text.takeLast(overlap)
            }
        }
        return needles.filter { it in left }
    }

    /** What running an engine once told us. */
    data class Probe(val version: String?, val output: String, val exitCode: Int?, val stillRunning: Boolean) {
        /**
         * Did it START? A version line is the best evidence, but not the only one: an engine that
         * exits cleanly, or is still running when the probe gives up, got past the linker and its
         * own start-up — which is the thing that can fail for a file run from the store's folder.
         */
        fun started(expected: String?): Boolean =
            version != null || (expected != null && output.contains(expected)) || exitCode == 0 || stillRunning
    }

    /**
     * Run an EXEC engine with its version flag and read the answer — the one proof that the new
     * file really starts on this phone before anything depends on it.
     */
    fun probe(cmd: List<String>, args: List<String>, re: Regex?, timeoutSec: Long = 8): Probe {
        return try {
            val p = ProcessBuilder(cmd + args).redirectErrorStream(true).start()
            val out = StringBuffer()
            val reader = Thread {
                runCatching { p.inputStream.bufferedReader().forEachLine { if (out.length < 8000) out.append(it).append('\n') } }
            }.apply { isDaemon = true; start() }
            val exited = p.waitUpTo(timeoutSec, TimeUnit.SECONDS)
            if (!exited) p.destroy()
            reader.join(1000)
            val text = out.toString()
            Probe(re?.find(text)?.groupValues?.getOrNull(1), text, if (exited) p.exitValue() else null, !exited)
        } catch (e: Exception) {
            Probe(null, e.message ?: "", null, false)
        }
    }

    /** The version of what is in use, cached per app build so the shipped binary is asked once. */
    fun shippedVersion(context: Context, item: StoreItem): String? {
        val spec = item.engine ?: return null
        // Known from the binary itself: no need to start it just to ask.
        if (spec.mode == EngineSpec.Mode.JNI || spec.probeRe == null || spec.shipped != null) return spec.shipped
        val prefs = context.getSharedPreferences("store_prefs", Context.MODE_PRIVATE)
        val code = runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        }.getOrDefault(0L)
        val key = "shipped_${item.id}_$code"
        prefs.getString(key, null)?.let { return it.ifBlank { spec.shipped } }
        val exe = File(context.applicationInfo.nativeLibraryDir, spec.libs.first())
        if (!exe.exists()) return spec.shipped
        val v = probe(listOf(exe.absolutePath), spec.probeArgs, spec.probeRe).version
        prefs.edit().putString(key, v ?: "").apply()
        return v ?: spec.shipped
    }
}
