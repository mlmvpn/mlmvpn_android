package com.mlmvpn.core.tunnel

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * The Smart Iran route's three lists, from Chocolate4U/Iran-v2ray-rules (rebuilt every day):
 *
 *  - `iran_geosite.dat` (their geosite-lite: `ir` = ~57,000 Iranian domains, `category-ir`)
 *  - `iran_geoip.dat` (their geoip-lite: `ir` = Iran's address ranges)
 *  - `smart_iran/ir_cidr.txt` (the same ranges as text, for the VPN builders)
 *
 * The two `.dat` files sit in filesDir, the Xray core's asset directory, where `ext:` rules read
 * them. Copies from the day the app was built ship in assets and are put in place on first use, so
 * the route works offline and on its first connect. Refreshed at most once a day: GitHub, then
 * jsDelivr's copy when GitHub is filtered, then both through the running tunnel. A download is
 * checked (size, content) and swapped in atomically; a bad one never replaces a good file.
 */
object SmartIranLists {

    private const val TAG = "SmartIranLists"
    private const val REPO = "Chocolate4U/Iran-v2ray-rules"
    private const val BRANCH = "release"
    private const val EVERY_MS = 24L * 3_600_000

    const val SEED_DIR = "smart_iran"
    const val SEED_CIDR = "$SEED_DIR/ir_cidr.txt"

    private data class Item(val remote: String, val local: (Context) -> File, val seed: String, val minBytes: Long, val check: (File) -> Boolean)

    private val ITEMS = listOf(
        Item("geosite-lite.dat", { File(it.filesDir, SmartIranRoute.GEOSITE_FILE) }, "$SEED_DIR/${SmartIranRoute.GEOSITE_FILE}", 200_000, { f -> containsAscii(f, "CATEGORY-IR") }),
        Item("geoip-lite.dat", { File(it.filesDir, SmartIranRoute.GEOIP_FILE) }, "$SEED_DIR/${SmartIranRoute.GEOIP_FILE}", 5_000, { f -> containsAscii(f, "IR") }),
        Item("text/ir.txt", { cidrFile(it) }, SEED_CIDR, 10_000, { f -> IranCidr.parse(f.readText()).first.size > 500 }),
    )

    fun cidrFile(ctx: Context): File = File(File(ctx.filesDir, SEED_DIR).apply { mkdirs() }, "ir_cidr.txt")

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("smart_iran_lists", Context.MODE_PRIVATE)

    fun lastUpdated(ctx: Context): Long = prefs(ctx).getLong("ok", 0)

    /** The shipped copies, put in place where nothing (or something broken) is there yet. */
    @Synchronized
    fun ensureSeeded(ctx: Context) {
        for (it in ITEMS) {
            val f = it.local(ctx)
            if (f.exists() && f.length() >= it.minBytes) continue
            runCatching {
                val tmp = File(f.parentFile, f.name + ".seed")
                ctx.assets.open(it.seed).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }.onFailure { e -> Log.w(TAG, "seed ${it.seed} not copied: ${e.message}") }
        }
    }

    /** Downloads what is due (everything when [force]); true when at least one list was renewed. */
    fun refresh(ctx: Context, force: Boolean = false, proxyPort: Int? = null): Boolean {
        ensureSeeded(ctx)
        val now = System.currentTimeMillis()
        if (!force && now - lastUpdated(ctx) < EVERY_MS) return false
        var renewed = 0
        for (it in ITEMS) {
            val urls = listOf(
                "https://raw.githubusercontent.com/$REPO/$BRANCH/${it.remote}",
                "https://cdn.jsdelivr.net/gh/$REPO@$BRANCH/${it.remote}",
            )
            val proxies: List<Proxy?> = listOf<Proxy?>(null) + listOfNotNull(proxyPort?.let { p -> Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", p)) })
            val f = it.local(ctx)
            val tmp = File(f.parentFile, f.name + ".dl")
            val ok = proxies.any { p -> urls.any { u -> download(u, p, tmp) && tmp.length() >= it.minBytes && runCatching { it.check(tmp) }.getOrDefault(false) } }
            if (ok) {
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                renewed++
            } else tmp.delete()
        }
        if (renewed > 0) prefs(ctx).edit().putLong("ok", now).apply()
        Log.i(TAG, "refresh: $renewed of ${ITEMS.size} lists renewed")
        return renewed > 0
    }

    private fun download(url: String, proxy: Proxy?, to: File): Boolean = runCatching {
        val c = (if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection()) as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "MLMVPN-SmartIran")
        try {
            if (c.responseCode != 200) return@runCatching false
            c.inputStream.use { input -> to.outputStream().use { input.copyTo(it) } }
            true
        } finally { c.disconnect() }
    }.getOrDefault(false)

    private fun containsAscii(f: File, text: String): Boolean {
        val needle = text.toByteArray()
        val bytes = f.readBytes()
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    /** Counts for the settings page: (domains-ish, IPv4 ranges, IPv6 ranges). */
    fun stats(ctx: Context): Pair<Int, Int> = runCatching {
        val (v4, v6) = IranCidr.parse(cidrFile(ctx).takeIf { it.exists() }?.readText() ?: ctx.assets.open(SEED_CIDR).bufferedReader().use { it.readText() })
        v4.size to v6.size
    }.getOrDefault(0 to 0)
}
