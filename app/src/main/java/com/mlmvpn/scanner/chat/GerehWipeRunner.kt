package com.mlmvpn.scanner.chat

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.mlmvpn.chat.bridge.GerehService
import org.mlmvpn.chat.bridge.GerehWipe
import java.io.File

/**
 * The app's side of the Gereh tile menu's three deletions (q3, Ehsan, 31 Oct 2026). Gereh's own
 * process does the work, since it owns the databases and can leave the groups ([GerehWipe]); this
 * starts it there, waits for it to end, then deletes what a dying process may have written back,
 * and for [GerehWipe.REMOVE] takes Gereh out of the app.
 */
object GerehWipeRunner {
    /** Leaving every group may wait 20 s for the leaves to go out; the rest is seconds. */
    private const val WAIT_MS = 60_000L

    /** True when Gereh's process did the work; false when it could not be reached in time. */
    suspend fun run(context: Context, mode: Int): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val done = File(app.filesDir, GerehWipe.DONE)
        done.delete()
        val intent = Intent(app, GerehService::class.java)
            .setAction(GerehWipe.ACTION)
            .putExtra(GerehWipe.EXTRA_MODE, mode)
        val sent = runCatching {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
        }.isSuccess
        val end = System.currentTimeMillis() + WAIT_MS
        while (sent && !done.exists() && System.currentTimeMillis() < end) delay(300)
        val ok = done.delete()
        // gone by now, or stuck: then it ends here
        endChatProcess(app)
        delay(500)
        if (mode != GerehWipe.CLEAR) {
            GerehWipe.deleteAll(app)
            File(app.filesDir, "gereh_widget.json").delete()
        }
        if (mode == GerehWipe.REMOVE) {
            File(app.filesDir, GerehService.BACKGROUND_OFF).delete()
            File(app.filesDir, GerehService.MUTED).delete()
            File(app.filesDir, GerehService.REMOVED).createNewFile()
        } else {
            // listening again after a clear; with no account left, a no-op
            GerehService.startIfReady(app)
        }
        runCatching { com.mlmvpn.scanner.widget.GerehWidgets.refresh(app) }
        ok || mode != GerehWipe.CLEAR
    }

    private fun endChatProcess(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.runningAppProcesses
            ?.filter { it.processName == "${context.packageName}:chat" }
            ?.forEach { android.os.Process.killProcess(it.pid) }
    }
}
