package com.mlmvpn.scanner.chat

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.mlmvpn.scanner.R
import java.io.File

/**
 * «MLM CHAT»: phone-to-phone chat with no server (mlm-chat/docs/PLAN.fa.md). Its screens are the
 * Telegram Android fork (chat/TMessagesProj, T4), which runs in its own `:chat` process so a fault
 * there can never take a VPN engine down (CLAUDE.md of mlm-chat, rule 5). This is the VPN app's
 * whole side of it: the tile opens the fork's main activity, and MlmApp starts the fork in that
 * process only.
 */
object MlmChat {
    const val TILE_ID = "mlmchat"

    /** The fork's native library; prebuilt for arm64-v8a and armeabi-v7a (since 1.2.45), so only
     *  a phone of another processor family lacks it. */
    private const val NATIVE = "libtmessages.49.so"

    fun available(context: Context): Boolean =
        !removed(context) && File(context.applicationInfo.nativeLibraryDir, NATIVE).exists()

    /**
     * Gereh taken out of the app from its tile's menu (q3, «حذف کامل گره»): its data is gone, its
     * tile is nowhere and nothing of it starts, until Settings brings it back ([restore]).
     */
    fun removed(context: Context): Boolean =
        org.mlmvpn.chat.bridge.GerehService.flag(context, org.mlmvpn.chat.bridge.GerehService.REMOVED)

    /** Gereh back in the app, as on a first install: a new account when it is first opened. */
    fun restore(context: Context) {
        File(context.filesDir, org.mlmvpn.chat.bridge.GerehService.REMOVED).delete()
    }

    fun open(context: Context) {
        if (removed(context)) {
            Toast.makeText(context, R.string.gereh_removed_hint, Toast.LENGTH_LONG).show()
            return
        }
        if (!available(context)) {
            Toast.makeText(context, R.string.mlmchat_not_on_this_phone, Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent().setClassName(context, "org.telegram.ui.LaunchActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
