package kittoku.mvc.control

import androidx.documentfile.provider.DocumentFile
import kittoku.mvc.SharedBridge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


internal class LogWriter(bridge: SharedBridge) {
    private val outputStream: BufferedOutputStream
    private val mutex = Mutex()

    private val currentTime: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())

    init {
        val currentDateTime = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(Date())
        val filename = "log_mvc_${currentDateTime}.txt"

        DocumentFile.fromTreeUri(bridge.service, bridge.logDirectory!!)!!.createFile("text/plain", filename).also {
            outputStream =
                BufferedOutputStream(bridge.service.contentResolver.openOutputStream(it!!.uri))
        }
    }

    internal suspend fun report(message: String) {
        mutex.withLock {
            outputStream.write("[$currentTime] $message\n".toByteArray(Charsets.UTF_8))
        }
    }

    internal suspend fun reportThrowable(throwable: Throwable) {
        var message = "An exception/error has been occurred:\n"
        message += throwable.stackTraceToString()
        report(message)
    }

    internal fun close() {
        outputStream.flush()
        outputStream.close()
    }
}