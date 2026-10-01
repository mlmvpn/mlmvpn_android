package com.mlmvpn.scanner.ui.doctor

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object DoctorShare {
    fun share(context: Context,name: String,text: String): Boolean = runCatching {
        val dir=File(context.cacheDir,"doctor").apply { mkdirs() }
        val file=File(dir,name).apply { writeText(text) }
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file)
        val intent=Intent(Intent.ACTION_SEND).apply {
            type="text/plain"; putExtra(Intent.EXTRA_STREAM,uri)
            clipData=ClipData.newUri(context.contentResolver,name,uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent,name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
