package com.mlmvpn.scanner.openvpn

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class OpenVpnViewModel(app: Application) : AndroidViewModel(app) {
    private val state = MutableStateFlow<OpenVpnData?>(null)
    val data = state.asStateFlow()
    val message = MutableStateFlow<String?>(null)
    val testing = MutableStateFlow<Set<String>>(emptySet())
    /** How many servers the running measurement started with, for its progress bar. */
    val testTotal = MutableStateFlow(0)
    private var probes: Job? = null
    private val repo get() = OpenVpnRepository.get(getApplication())
    init { viewModelScope.launch(Dispatchers.IO) { repo.data.collect { state.value = it } } }
    fun change(action: (OpenVpnRepository) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try { action(repo) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message.value = "SAVE_FAILED" }
        }
    }
    fun refresh() = change { r -> r.data.value.accounts.forEach { r.refreshAccount(it.id) } }
    fun import(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                require(uris.size <= 100)
                val resolver = getApplication<Application>().contentResolver
                val files = linkedMapOf<String, String>()
                var total = 0
                for ((index, uri) in uris.withIndex()) {
                    // Telegram and some file managers give no display name, or one without the
                    // extension. Neither is a reason to refuse the file: the name falls back to the
                    // URI's last segment, and the content decides whether it is a profile.
                    val rawName = runCatching {
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        }
                    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "profile${index + 1}.ovpn"
                    var name = rawName.replace('/', '_').replace('\\', '_').trim().ifEmpty { "profile${index + 1}.ovpn" }.take(170)
                    if (name in files) name = "${index + 1}-$name".take(170)
                    val bytes = resolver.openInputStream(uri)?.use { stream ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (true) {
                            val n = stream.read(chunk)
                            if (n < 0) break
                            require(buffer.size() + n <= ProfileImporter.MAX_BYTES)
                            buffer.write(chunk, 0, n)
                        }
                        buffer.toByteArray()
                    } ?: error("Unreadable file")
                    total += bytes.size
                    require(total <= 8_388_608)
                    val text = bytes.toString(Charsets.UTF_8)
                    val looksLikeProfile = Regex("(?m)^\\s*remote\\s+\\S+").containsMatchIn(text) &&
                        (Regex("(?m)^\\s*client\\s*$").containsMatchIn(text) || text.contains("<ca>"))
                    if (looksLikeProfile && !name.endsWith(".ovpn", true) && !name.endsWith(".ovpn.txt", true)) {
                        name = name.substringBeforeLast('.').ifEmpty { name } + ".ovpn"
                    }
                    files[name] = text
                }
                val (count, errors) = repo.importFiles(files)
                message.value = if (errors.isEmpty()) "IMPORTED:$count" else "IMPORTED:$count\n" + errors.take(6).joinToString("\n")
            } catch (e: CancellationException) { throw e }
            // The real reason, not a guess about file counts and sizes.
            catch (e: Exception) { message.value = "IMPORT_FAILED:" + e.javaClass.simpleName + (e.message?.let { ": " + it.take(80) } ?: "") }
        }
    }
    fun test(profiles: List<Profile>) {
        if (probes?.isActive == true) return
        testing.value = profiles.map { it.id }.toSet()
        testTotal.value = testing.value.size
        probes = viewModelScope.launch {
            // Eight at a time: each one waits for a real TLS answer (a few seconds at worst), so
            // the whole list is done in about the time four took for a bare reset.
            val semaphore = Semaphore(8)
            try {
                coroutineScope {
                    profiles.map { p -> launch {
                        semaphore.withPermit {
                            val result = OpenVpnLatency.measure(p)
                            withContext(Dispatchers.IO) { repo.updateProfile(p.id) { it.withProbe(result) } }
                            testing.value = testing.value - p.id
                        }
                    } }.joinAll()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message.value = "TEST_FAILED" }
            finally { testing.value = emptySet() }
        }
    }
    fun cancelTests() { probes?.cancel() }
}
