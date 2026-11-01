package com.mlmvpn.scanner.ui.npv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import com.mlmvpn.scanner.engines.npv.NpvEngine
import com.mlmvpn.scanner.store.tr

/**
 * «Open with» / «Share» for NPV Tunnel files and config links from Telegram or a file manager:
 * the file or text goes into «NPV»'s library, and the app opens.
 */
class NpvImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        val n = runCatching {
            val text = i.getStringExtra(Intent.EXTRA_TEXT)
            @Suppress("DEPRECATION")
            val uri: Uri? = i.data ?: i.getParcelableExtra(Intent.EXTRA_STREAM)
            when {
                uri != null -> {
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    if (bytes.size > 2_000_000) -1 else NpvEngine.importFile(this, nameOf(uri), bytes)
                }
                !text.isNullOrBlank() -> NpvEngine.importText(this, text)
                else -> -1
            }
        }.getOrDefault(-1)
        Toast.makeText(this, when {
            n < 0 -> tr("NPV: فایل خوانده نشد", "NPV: the file could not be read")
            n == 0 -> tr("NPV: چیز تازه‌ای نبود", "NPV: nothing new")
            else -> tr("NPV: اضافه شد", "NPV: added")
        }, Toast.LENGTH_LONG).show()
        packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    private fun nameOf(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "npv"
}
