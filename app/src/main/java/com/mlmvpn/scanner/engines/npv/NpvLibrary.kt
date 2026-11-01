package com.mlmvpn.scanner.engines.npv

import android.content.Context
import com.mlmvpn.scanner.utils.VpnConfig
import org.json.JSONArray
import java.io.File

/**
 * «NPV»'s library: the user's own NPV Tunnel files and configs, and the free ones from the sources
 * (docs/URNETWORK-NPV-PLAN.md, 15.6).
 *
 * Two kinds of entry, never mixed up. A standard link (vless, vmess, trojan, ss, socks,
 * hysteria2 …) runs on the app's own Xray engine. A native NPV Tunnel file (.npvt, .npvs) is kept,
 * listed and handed to NPV Tunnel, but never decrypted or run here: no authorized key for it
 * exists, and taking one out of NPV Tunnel would get around the file's protection.
 *
 * Two shelves. [Shelf.MAIN] is what the NPV page lists and connects: the user's own imports and
 * whatever they picked. [Shelf.STORE] («انبار») is everything the sources brought in, measured and
 * pruned there until the user sends some of it to the main page.
 */
object NpvLibrary {

    /** Under filesDir. */
    const val FILE = "npv/library.json"

    enum class Kind {
        /** A standard config link, run on the Xray engine. */
        LINK,
        /** A native NPV Tunnel file: kept and opened in NPV Tunnel, not run here. */
        NATIVE,
        /** A file seen in a channel; [Entry.uri] is its Telegram post, where it is fetched. */
        REMOTE,
    }

    enum class Shelf { MAIN, STORE }

    data class Entry(
        val id: String,
        val name: String,
        val kind: Kind,
        /** The link itself for [Kind.LINK], the post for [Kind.REMOTE]; null for a native file. */
        val uri: String?,
        /** Where it came from: "mine" for the user's own, else the source's id ("tg:x", "gh:x", "free:seed" …). */
        val source: String,
        val addedAt: Long,
        val shelf: Shelf = if (source == "mine") Shelf.MAIN else Shelf.STORE,
        /** The last measured delay on this internet: above 0 in ms, -1 did not answer, 0 never measured. */
        val ping: Long = 0,
        val pingAt: Long = 0,
    ) {
        val healthy: Boolean get() = ping > 0
        val broken: Boolean get() = ping < 0
    }

    fun entries(context: Context): List<Entry> {
        val file = File(context.filesDir, FILE)
        if (!file.isFile) return emptyList()
        return runCatching { parse(file.readText()) }.getOrDefault(emptyList())
    }

    /** The links the Xray engine can run: a parsed address, nothing else. */
    fun runnable(context: Context): List<Entry> = entries(context).filter(::isRunnable)

    internal fun isRunnable(e: Entry): Boolean =
        e.kind == Kind.LINK && e.uri != null && VpnConfig.parseUri(e.uri)?.address?.isNotBlank() == true

    /**
     * Fastest first: the ones that answered by delay, then the ones never measured (newest first),
     * then the ones that did not answer.
     */
    fun byDelay(list: List<Entry>, live: Map<String, Long> = emptyMap()): List<Entry> {
        fun p(e: Entry) = live[e.id] ?: e.ping
        return list.sortedWith(compareBy<Entry>({ when { p(it) > 0 -> 0; p(it) == 0L -> 1; else -> 2 } }, { if (p(it) > 0) p(it) else 0L }, { -it.addedAt }))
    }

    /** A damaged entry is skipped, not the whole library. */
    internal fun parse(text: String): List<Entry> {
        val arr = JSONArray(text)
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
            val kind = Kind.entries.firstOrNull { it.name == o.optString("kind") } ?: continue
            val uri = if (o.has("uri") && !o.isNull("uri")) o.optString("uri").takeIf { it.isNotBlank() } else null
            if (kind != Kind.NATIVE && uri == null) continue
            val source = o.optString("source").ifBlank { "mine" }
            out += Entry(
                id = id,
                name = o.optString("name").ifBlank { id },
                kind = kind,
                uri = if (kind == Kind.NATIVE) null else uri,
                source = source,
                addedAt = o.optLong("addedAt", 0L),
                shelf = Shelf.entries.firstOrNull { it.name == o.optString("shelf") } ?: if (source == "mine") Shelf.MAIN else Shelf.STORE,
                ping = o.optLong("ping", 0L),
                pingAt = o.optLong("pingAt", 0L),
            )
        }
        return out
    }
}
