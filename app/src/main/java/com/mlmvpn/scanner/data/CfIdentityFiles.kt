package com.mlmvpn.scanner.data

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Native cores require TOML files while running. Their retained copy is Keystore ciphertext. */
object CfIdentityFiles {
    private val lock = Any()
    fun <T> guarded(block: () -> T): T = synchronized(lock) { block() }
    private fun checked(context: Context, file: File): File {
        val root = context.filesDir.canonicalFile
        val target = file.canonicalFile
        check(target.path.startsWith(root.path + File.separator)) { "identity_path_outside_app" }
        return target
    }
    private fun cipherFile(file: File) = File(file.parentFile, file.name + ".sealed")
    private fun atomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val writer = AtomicFile(file)
        val stream = writer.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); writer.finishWrite(stream) }
        catch (e: Exception) { writer.failWrite(stream); throw e }
        file.setReadable(false, false); file.setWritable(false, false)
        file.setReadable(true, true); file.setWritable(true, true)
    }
    fun exists(file: File) = file.exists() || cipherFile(file).exists()
    /** Copy for an isolated probe without materializing or changing the live engine's file. */
    fun copyForProbe(context: Context, source: File, destination: File) = synchronized(lock) {
        val from = checked(context, source)
        val text = if (from.exists()) from.readText() else {
            SecureStore.open(cipherFile(from).readText()) ?: error("secure_identity_unavailable")
        }
        save(context, destination, text, materialize = true)
    }
    fun save(context: Context, file: File, text: String, materialize: Boolean = false) = synchronized(lock) {
        val target = checked(context, file)
        val sealed = SecureStore.seal(text) ?: error("secure_identity_unavailable")
        atomic(cipherFile(target), sealed)
        if (materialize) atomic(target, text)
    }
    fun restore(context: Context, file: File) = synchronized(lock) {
        val target = checked(context, file)
        if (target.exists()) {
            save(context, target, target.readText())
        } else if (cipherFile(target).exists()) {
            val plain = SecureStore.open(cipherFile(target).readText()) ?: error("secure_identity_unavailable")
            atomic(target, plain)
        }
    }
    fun restoreDirectory(context: Context, directory: File) = synchronized(lock) {
        directory.listFiles()?.filter { it.name.endsWith(".toml.sealed") }?.forEach {
            restore(context, File(directory, it.name.removeSuffix(".sealed")))
        }
    }
    fun sealDirectory(context: Context, directory: File, removePlain: Boolean = true, maySeal: () -> Boolean = { true }) = synchronized(lock) {
        if (!maySeal()) return@synchronized
        directory.listFiles()?.filter { it.name.contains(".toml") && !it.name.contains(".sealed") }?.forEach { file ->
            val text = file.readText()
            if (Regex("(?m)^\\s*(?:access_token|wg_private_key|key_pem|private_key)\\s*=").containsMatchIn(text)) {
                save(context, file, text)
                if (removePlain) check(file.delete()) { "identity_runtime_cleanup_failed" }
            }
        }
    }
    fun sealKnown(context: Context) {
        for (dir in listOf(context.filesDir, File(context.filesDir, "warp/data"), File(context.filesDir, "aether/data")))
            sealDirectory(context, dir)
    }
}
