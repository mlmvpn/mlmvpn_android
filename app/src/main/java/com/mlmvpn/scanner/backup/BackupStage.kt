package com.mlmvpn.scanner.backup

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.mlmvpn.scanner.store.tr
import java.io.File

/**
 * The Android side of [RestoreApplier]: where its folders and prefs live, the hook at launch, and
 * the stop-and-restart that hands a staged restore to that hook.
 */
object BackupStage {

    private const val TAG = "Backup"

    private fun work(ctx: Context) = File(ctx.noBackupFilesDir, "backup")

    internal fun roots(ctx: Context): Map<String, File> = mapOf(
        "files" to ctx.filesDir,
        "no_backup" to ctx.noBackupFilesDir,
        "databases" to (ctx.getDatabasePath("probe").parentFile ?: File(ctx.applicationInfo.dataDir, "databases")),
    )

    internal fun applier(ctx: Context): RestoreApplier {
        val app = ctx.applicationContext
        return RestoreApplier(AndroidPrefsIo(app), roots(app), work(app))
    }

    /**
     * From `MlmApp.onCreate`, straight after the crash reporter and before anything else reads its
     * settings: applies a staged restore, or finishes one a previous launch left half-way. One `stat`
     * when there is none. Never throws -- a backup must not be able to stop the app from starting.
     */
    @JvmStatic
    fun onAppStart(app: Application) {
        try {
            if (!File(work(app), "journal.json").exists()) return
            if (!isMainProcess(app)) return
            val t0 = SystemClock.elapsedRealtime()
            val done = applier(app).resume(System.currentTimeMillis()) ?: return
            Log.i(TAG, "restore at launch: ${done.result} in ${SystemClock.elapsedRealtime() - t0} ms ${done.error.orEmpty()}")
            // Posted: the language the restore may just have changed is read later in onCreate.
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { Toast.makeText(app, resultMessage(done), Toast.LENGTH_LONG).show() }
            }, 1_200)
        } catch (t: Throwable) {
            Log.w(TAG, "restore at launch failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    internal fun stage(ctx: Context, plan: RestorePlan, titles: List<String>) =
        applier(ctx).stage(plan.ops, titles, System.currentTimeMillis())

    /** Drops a staged restore that the restart never picked up (true when nothing is under way). */
    internal fun unstage(ctx: Context): Boolean = applier(ctx).unstage()

    /** The last restore's outcome, for the screen. */
    internal fun lastResult(ctx: Context): RestoreApplier.Journal? =
        applier(ctx).journal()?.takeIf { it.phase == RestoreApplier.Phase.DONE }

    internal fun resultMessage(j: RestoreApplier.Journal): String = when (j.result) {
        RestoreApplier.Result.OK -> tr("پشتیبان برگردانده شد.", "Backup restored.")
        RestoreApplier.Result.ROLLED_BACK ->
            tr("بازگردانی کامل نشد؛ همه‌چیز به حالت قبل برگشت.", "The restore did not finish; everything was put back as it was.")
        else -> tr("بازگردانی انجام نشد.", "The restore failed.")
    }

    /**
     * Stops every tunnel, ends this app's other processes and starts the app again; [onAppStart]
     * applies the staged restore on the way up. Blocks up to ~8 s waiting for the tunnels -- call it
     * off the main thread. Does not return.
     */
    internal fun stopEverythingAndRestart(ctx: Context) {
        val app = ctx.applicationContext
        runCatching { com.mlmvpn.scanner.data.VpnSwitch.stopAll(app) }
            .onFailure { Log.w(TAG, "stopAll: ${it.message}") }
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val busy = runCatching { com.mlmvpn.scanner.data.VpnSwitch.isUp(app) || com.mlmvpn.scanner.data.VpnSwitch.engineBusy() }
                .getOrDefault(false)
            if (!busy) break
            SystemClock.sleep(250)
        }
        // The helper processes hold settings of their own in memory; none may outlive this one and
        // write an old copy over the restore. SIGKILL is what the crash reporter ignores.
        runCatching {
            val am = app.getSystemService(ActivityManager::class.java)
            am?.runningAppProcesses?.forEach { p ->
                if (p.pid != android.os.Process.myPid() && p.uid == android.os.Process.myUid()) android.os.Process.killProcess(p.pid)
            }
        }
        com.mlmvpn.scanner.ui.restartAppProcess(app)
    }

    private fun isMainProcess(app: Application): Boolean =
        if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName() == app.packageName
        } else {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName == app.packageName
        }
}

/** [PrefsIo] on the app's SharedPreferences. */
internal class AndroidPrefsIo(private val ctx: Context) : PrefsIo {

    private fun file(name: String) = File(File(ctx.applicationInfo.dataDir, "shared_prefs"), "$name.xml")

    override fun exists(name: String): Boolean = file(name).exists()

    override fun read(name: String): Map<String, Any?> = HashMap(ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all)

    override fun write(name: String, clear: Boolean, puts: Map<String, Any>, removes: Set<String>) {
        val e = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
        if (clear) e.clear()
        for (k in removes) e.remove(k)
        for ((k, v) in puts) {
            when (v) {
                is String -> e.putString(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
                else -> error("unsupported value for $k")
            }
        }
        check(e.commit()) { "could not save $name" }
    }

    override fun delete(name: String) {
        ctx.deleteSharedPreferences(name)
    }
}
