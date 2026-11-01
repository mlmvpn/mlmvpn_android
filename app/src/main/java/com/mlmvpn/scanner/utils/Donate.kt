package com.mlmvpn.scanner.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Supporting the app: the owner's donation page, opened in the phone's own browser. */
object Donate {

    const val URL = "https://donito.me/ehsan2noven"

    /** Opens [URL] in the user's browser (this app handles no https links, so nothing else takes it). */
    fun open(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(URL))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, URL, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, URL, Toast.LENGTH_LONG).show()
        }
    }
}
