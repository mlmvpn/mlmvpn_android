package com.mlmvpn.scanner.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.mlmvpn.scanner.R

/**
 * The clipboard is a call into the system server, and a call carries about 1 MB: copying every
 * config of a big scan (2 MB) threw TransactionTooLargeException and closed the app (1.2.42
 * #2211). A text that long is refused with a message instead.
 */
object SafeClip {
    /** Characters; at two bytes each this stays well under the call's limit. */
    const val MAX_CHARS = 300_000

    /** Copies [text]; false (and the user is told) when it is too long or the system refused it. */
    fun copy(context: Context, label: String, text: String): Boolean {
        if (text.length > MAX_CHARS) {
            Toast.makeText(context, context.getString(R.string.clip_too_large), Toast.LENGTH_LONG).show()
            return false
        }
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        } catch (e: RuntimeException) {
            Toast.makeText(context, context.getString(R.string.clip_too_large), Toast.LENGTH_LONG).show()
            false
        }
    }
}
