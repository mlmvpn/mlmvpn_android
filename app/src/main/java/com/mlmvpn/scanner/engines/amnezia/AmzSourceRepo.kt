package com.mlmvpn.scanner.engines.amnezia

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder

/**
 * Fetches the lists. GitHub first, jsDelivr's copy when GitHub is filtered, and once more through
 * the running tunnel's local proxy when both fail on the bare network. A list that answers 304,
 * or comes back empty, changes nothing: the servers already in the pool stay.
 */
class AmzSourceRepo(app: Context) {

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Fetched(val source: AmzSource, val batch: AmzParser.Batch)

    fun lastOk(id: String): Long = prefs.getLong("ok_$id", 0)

    /**
     * The sources that are due (all of them when [force]), fetched side by side. Returns what each
     * yielded; the caller merges it into the pool.
     */
    suspend fun refresh(force: Boolean, proxyPort: Int?, now: Long = System.currentTimeMillis()): List<Fetched> = coroutineScope {
        // A forced refresh wants the bodies, not "not modified": the pool may have lost them.
        if (force) prefs.edit().apply { prefs.all.keys.filter { it.startsWith("etag_") }.forEach { remove(it) } }.apply()
        val gate = Semaphore(4)
        AmzSources.BUILT_IN
            .filter { force || AmzSources.due(it, lastOk(it.id), now) }
            .map { src -> async(Dispatchers.IO) { gate.withPermit { runCatching { fetchOne(src, proxyPort, now) }.getOrNull() } } }
            .awaitAll()
            .filterNotNull()
    }

    private suspend fun fetchOne(src: AmzSource, proxyPort: Int?, now: Long): Fetched? {
        val batch = when (src.format) {
            AmzSource.Format.LINKS -> {
                val body = get(src, src.rawUrl, src.mirrorUrl, proxyPort, conditional = true) ?: return null
                if (body === NOT_MODIFIED) { markOk(src, now); return null }
                AmzParser.parseText(String(body, Charsets.UTF_8), src.id, now)
            }
            AmzSource.Format.ZIP -> {
                val body = get(src, src.rawUrl, src.mirrorUrl, proxyPort, conditional = true) ?: return null
                if (body === NOT_MODIFIED) { markOk(src, now); return null }
                AmzParser.parseZip(body, src.id, now)
            }
            AmzSource.Format.CONF_DIR -> confDir(src, proxyPort, now) ?: return null
        }
        Log.i(TAG, "source ${src.id}: ${batch.servers.size} servers, ${batch.skipped} skipped")
        if (batch.servers.isEmpty()) return null
        markOk(src, now)
        return Fetched(src, batch)
    }

    private fun markOk(src: AmzSource, now: Long) = prefs.edit().putLong("ok_${src.id}", now).apply()

    /** A directory of `.conf` files: listed through GitHub's API, or jsDelivr's when that fails. */
    private suspend fun confDir(src: AmzSource, proxyPort: Int?, now: Long): AmzParser.Batch? = coroutineScope {
        val names: List<String> = runCatching {
            download(src.listingUrl, null, 15_000)?.let { b ->
                val a = JSONArray(String(b, Charsets.UTF_8))
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("type") == "file" }.map { it.optString("name") }
            }
        }.getOrNull() ?: runCatching {
            download(src.mirrorListingUrl, proxyPort?.let { httpProxy(it) }, 15_000)?.let { b ->
                val files = JSONObject(String(b, Charsets.UTF_8)).optJSONArray("files") ?: JSONArray()
                val prefix = "/" + src.path.trim('/') + "/"
                (0 until files.length()).mapNotNull { files.optJSONObject(it)?.optString("name") }
                    .filter { it.startsWith(prefix) && it.removePrefix(prefix).none { c -> c == '/' } }
                    .map { it.removePrefix(prefix) }
            }
        }.getOrNull() ?: return@coroutineScope null
        val confs = names.filter { it.endsWith(".conf", true) }.take(MAX_DIR_FILES)
        val gate = Semaphore(6)
        val servers = confs.map { name ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val enc = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                    val raw = "https://raw.githubusercontent.com/${src.repo}/${src.branch}/${src.path}/$enc"
                    val mirror = "https://cdn.jsdelivr.net/gh/${src.repo}@${src.branch}/${src.path}/$enc"
                    val b = (download(raw, null, 10_000) ?: download(mirror, null, 10_000)
                        ?: proxyPort?.let { download(mirror, httpProxy(it), 10_000) }) ?: return@withPermit null
                    AmzParser.fromConf(String(b, Charsets.UTF_8), name.substringBeforeLast('.'), src.id, now)
                }
            }
        }.awaitAll().filterNotNull()
        AmzParser.Batch(servers.distinctBy { it.id }, confs.size - servers.size)
    }

    private fun get(src: AmzSource, url: String, mirror: String, proxyPort: Int?, conditional: Boolean): ByteArray? {
        val etagKey = "etag_${src.id}"
        val etag = if (conditional) prefs.getString(etagKey, null) else null
        val attempts = listOfNotNull(
            url to null, mirror to null,
            proxyPort?.let { url to httpProxy(it) }, proxyPort?.let { mirror to httpProxy(it) },
        )
        for ((u, proxy) in attempts) {
            val r = runCatching { request(u, proxy, 20_000, src.maxBytes, etag.takeIf { u == url }) }.getOrNull() ?: continue
            if (r.notModified) return NOT_MODIFIED
            r.etag?.takeIf { u == url }?.let { prefs.edit().putString(etagKey, it).apply() }
            return r.body
        }
        return null
    }

    private class Response(val body: ByteArray?, val etag: String?, val notModified: Boolean)

    private fun request(url: String, proxy: Proxy?, timeout: Int, maxBytes: Int, etag: String?): Response? {
        val c = (if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection()) as HttpURLConnection
        c.connectTimeout = minOf(timeout, 10_000); c.readTimeout = timeout
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "MLMVPN-Amnezia")
        etag?.let { c.setRequestProperty("If-None-Match", it) }
        try {
            return when (c.responseCode) {
                304 -> Response(null, etag, true)
                200 -> {
                    val out = java.io.ByteArrayOutputStream()
                    c.inputStream.use { input ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > maxBytes) break
                        }
                    }
                    Response(out.toByteArray(), c.getHeaderField("ETag"), false)
                }
                else -> null
            }
        } finally { c.disconnect() }
    }

    private fun download(url: String, proxy: Proxy?, timeout: Int): ByteArray? =
        runCatching { request(url, proxy, timeout, 512 * 1024, null)?.body }.getOrNull()

    private fun httpProxy(port: Int) = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))

    /** Reads a file the user picked: a `.conf`, a `.zip` of them, or a text list of links. */
    suspend fun parseUserFile(bytes: ByteArray, name: String, now: Long): AmzParser.Batch = withContext(Dispatchers.Default) {
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (isZip) AmzParser.parseZip(bytes, AmzServer.USER_SOURCE, now)
        else AmzParser.parseText(decodeText(bytes), AmzServer.USER_SOURCE, now, fileName = name.substringBeforeLast('.'))
    }

    companion object {
        private const val TAG = "AmzSources"
        private const val PREFS = "amnezia_sources"
        private const val MAX_DIR_FILES = 80
        private val NOT_MODIFIED = ByteArray(0)

        /** UTF-8, or UTF-16 as Windows Notepad saves it (with or without a byte-order mark). */
        fun decodeText(bytes: ByteArray): String {
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            // No mark, but every other byte zero: UTF-16LE text all the same.
            if (bytes.size >= 8 && (1 until minOf(bytes.size, 64) step 2).all { bytes[it] == 0.toByte() }) return String(bytes, Charsets.UTF_16LE)
            return String(bytes, Charsets.UTF_8)
        }
    }
}
