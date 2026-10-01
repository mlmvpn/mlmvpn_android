package com.mlmvpn.scanner.ui.amnezia

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.mlmvpn.scanner.MainActivity
import com.mlmvpn.scanner.engines.amnezia.AmzEngine

/**
 * «Open with» for a WireGuard / AmneziaWG `.conf` from Telegram or a file manager: the file goes
 * into the «آمنزیا» section, and the app opens on it. No UI of its own.
 */
class AmzImportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> intent?.data
        }
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (uri != null) {
            readUri(this, uri)?.let { (bytes, name) -> AmzEngine.importFile(this, bytes, name) }
        } else if (!text.isNullOrBlank()) {
            AmzEngine.importText(this, text)
        }
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_TAB, "amnezia")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        finish()
    }

    companion object {
        /** Read by AppScreen: the destination to open on launch. */
        const val EXTRA_OPEN_TAB = "open_tab"
    }
}
