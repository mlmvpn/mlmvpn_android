package com.mlmvpn.core.warp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The exit country of the WARP family, chosen through a public NAT64 gateway.
 *
 * WARP comes out of the Cloudflare data centre the connection reaches, which is the one nearest
 * the user -- so from Iran it comes out in Iran. Dialled through a NAT64 gateway, the same
 * endpoint is reached from the gateway's network instead: Cloudflare sees a connection from
 * London, takes it in London and gives it a London exit. Nothing is spoofed; the endpoint address
 * is simply written as `[<gateway prefix>:<Cloudflare IPv4 in hex>]:<port>`, which the gateway
 * translates. It needs one thing from the line: IPv6.
 *
 * Measured on 2026-10-03 with the desktop's Aether core (handshake, data plane and Cloudflare's
 * trace through the tunnel answering `warp=on` and the country below), for WireGuard AND MASQUE --
 * MASQUE even on a line where its direct gateway is filtered. The entries that did not answer
 * that day are kept: every source listed them, a gateway's health changes, and the ping on the
 * country list says which answer from the user's own line.
 *
 * Three selections, one per process, shared by «وارپ», MASQUE and WireGuard:
 *  - [DIRECT] (""): no gateway. Exactly the behaviour from before this existed.
 *  - [FASTEST]: the gateway the last ping measured fastest.
 *  - a country code: that country's gateways, healthiest first.
 */
object Nat64Exits {

    private const val TAG = "Nat64Exits"
    private const val PREFS = "nat64_exit"

    const val DIRECT = ""
    const val FASTEST = "fastest"

    /** Where each protocol's endpoint lives: Cloudflare's own anycast addresses. */
    enum class Proto(val v4: String, val port: Int) {
        /** engage.cloudflareclient.com -- the WireGuard endpoint of every WARP client. */
        WG("162.159.192.1", 2408),

        /** The MASQUE gateway (HTTP/3 on UDP 443). */
        MASQUE("162.159.198.1", 443),
    }

    data class Gateway(
        /** The /96 prefix, written out: `2a00:1098:2c:0:0:5::`. */
        val prefix: String,
        val country: String,
        val city: String,
        val provider: String,
        val source: Source,
        /** Carried WARP end to end when this list was written. */
        val verified: Boolean = false,
    ) {
        val key: String get() = keyOf(prefix) ?: prefix
    }

    enum class Source { BUILT_IN, FETCHED, CUSTOM }

    /**
     * Every public gateway the sources list, once each. "Once" is by the 96 bits that make the
     * prefix, not by how it is written: `2a01:4f9:c010:3f02:64::` and `…:64:0::` are one gateway.
     *
     * Sources: nat64.xyz (and its GitHub list), nat64.net's provider list and the DNS64 answers of
     * its five resolvers, level66.services, Trex, ZTVI, the BPB panel's NAT64 list, amutu's
     * Hong Kong gateway, go6lab, AS7175's IX service and ipv6.lt.
     */
    private val BUILT_IN = listOf(
        // nat64.net (Kasper Dupont). The prefixes its DNS64 really answers with -- nat64.xyz writes
        // them shortened, and those shortened forms do not translate (kept below as backups).
        Gateway("2a00:1098:2c:0:0:5::", "GB", "London", "nat64.net", Source.BUILT_IN, verified = true),
        Gateway("2a00:1098:2b:0:0:1::", "NL", "Amsterdam", "nat64.net", Source.BUILT_IN, verified = true),
        Gateway("2a01:4f8:c2c:123f:64:5::", "DE", "Nuremberg", "nat64.net", Source.BUILT_IN, verified = true),
        Gateway("2a01:4f9:c010:3f02:64:0::", "FI", "Helsinki", "nat64.net", Source.BUILT_IN, verified = true),
        Gateway("2001:67c:2960:6464::", "DE", "Frankfurt", "level66", Source.BUILT_IN, verified = true),
        Gateway("2001:67c:2b0:db32:0:1::", "FI", "Tampere", "Trex", Source.BUILT_IN, verified = true),
        Gateway("2602:fc59:b0:64::", "US", "Fremont", "ZTVI", Source.BUILT_IN, verified = true),
        Gateway("2600:70ff:ac2c:64::", "HK", "Hong Kong", "rasde", Source.BUILT_IN, verified = true),
        Gateway("2602:fc59:11:64::", "US", "Chicago", "ZTVI", Source.BUILT_IN),
        Gateway("2a02:898:146:64::", "NL", "Netherlands", "BPB list", Source.BUILT_IN),
        Gateway("2001:67c:27e4:642::", "SI", "Ljubljana", "go6lab (A10)", Source.BUILT_IN),
        Gateway("2001:67c:27e4:64::", "SI", "Ljubljana", "go6lab (Palo Alto)", Source.BUILT_IN),
        Gateway("2001:67c:27e4:1064::", "SI", "Ljubljana", "go6lab (Jool)", Source.BUILT_IN),
        Gateway("2001:67c:27e4:11::", "SI", "Ljubljana", "go6lab (Cisco)", Source.BUILT_IN),
        Gateway("2403:780:fe00:7175::", "AU", "Sydney", "AS7175", Source.BUILT_IN),
        Gateway("2001:778:0:ffff:64::", "LT", "Vilnius", "ipv6.lt", Source.BUILT_IN),
        Gateway("2a00:1098:2c:1::", "GB", "London", "nat64.xyz", Source.BUILT_IN),
        Gateway("2a00:1098:2b::", "NL", "Amsterdam", "nat64.xyz", Source.BUILT_IN),
        Gateway("2a01:4f8:c2c:123f:64::", "DE", "Nuremberg", "nat64.xyz", Source.BUILT_IN),
    )

    /** Lists re-read in the background, so a gateway a source adds reaches the app without a release. */
    private val SOURCES = listOf(
        "https://raw.githubusercontent.com/level66network/nat64.xyz/main/content/_index.md",
        "https://raw.githubusercontent.com/bia-pain-bache/BPB-Worker-Panel/main/docs/NAT64Prefixes.md",
    )

    // ── prefixes ────────────────────────────────────────────────────────────────────────────────

    /** The 96 bits that identify a gateway, as hex; null when it is not an IPv6 prefix at all. */
    fun keyOf(prefix: String): String? {
        val bytes = prefixBytes(prefix) ?: return null
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun prefixBytes(prefix: String): ByteArray? {
        val p = prefix.trim().removePrefix("[").substringBefore('/').removeSuffix("]").trim()
        if (!p.contains(':') || p.any { !(it.isLetterOrDigit() || it == ':' || it == '.') }) return null
        return runCatching { (InetAddress.getByName(p) as? Inet6Address)?.address }.getOrNull()
    }

    /** `2a00:1098:2c:0:0:5:a29f:c001`: the IPv4 address written into the gateway's prefix. */
    fun embed(prefix: String, ipv4: String): String? {
        val b = prefixBytes(prefix) ?: return null
        val v4 = ipv4.split('.').mapNotNull { it.toIntOrNull() }
        if (v4.size != 4) return null
        for (i in 0 until 4) b[12 + i] = v4[i].toByte()
        return runCatching { InetAddress.getByAddress(b).hostAddress?.substringBefore('%') }.getOrNull()
    }

    /** The address the core dials: `[…]:2408`. */
    fun endpoint(gw: Gateway, proto: Proto): String? =
        embed(gw.prefix, proto.v4)?.let { "[$it]:${proto.port}" }

    // ── the catalogue ───────────────────────────────────────────────────────────────────────────

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Built-in first, then what the sources added, then the user's own; one entry per gateway. */
    fun gateways(ctx: Context): List<Gateway> {
        val out = LinkedHashMap<String, Gateway>()
        for (g in BUILT_IN + fetched(ctx) + custom(ctx)) {
            val k = keyOf(g.prefix) ?: continue
            if (k !in out) out[k] = g
        }
        return out.values.toList()
    }

    /**
     * One gateway for each country, the first that [gateways] lists for it: what MLM Scout tests. A
     * country is one exit, whichever of its gateways carries it -- Scout testing all ~20 gateways,
     * each with its own wait, was most of a scan's length (user report 2026-10-11).
     */
    fun onePerCountry(ctx: Context): List<Gateway> =
        gateways(ctx).groupBy { it.country.lowercase() }.values.map { it.first() }

    /** Every country with a gateway, verified ones first, then by how many gateways it has. */
    fun countries(ctx: Context): List<String> =
        gateways(ctx).groupBy { it.country }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, List<Gateway>>> { e -> e.value.any { it.verified } }
                .thenByDescending { it.value.size })
            .map { it.key }

    fun gatewaysOf(ctx: Context, country: String): List<Gateway> =
        gateways(ctx).filter { it.country.equals(country, ignoreCase = true) }

    // ── the user's own gateways ─────────────────────────────────────────────────────────────────

    fun custom(ctx: Context): List<Gateway> = readList(p(ctx).getString("custom", null), Source.CUSTOM)

    /** Null on success, else why it was refused. */
    fun addCustom(ctx: Context, prefix: String, country: String): String? {
        val cc = country.trim().uppercase(Locale.US)
        if (cc.length != 2 || cc.any { it !in 'A'..'Z' }) return "country"
        val key = keyOf(prefix) ?: return "prefix"
        val canonical = canonicalPrefix(prefix) ?: return "prefix"
        val list = custom(ctx).filterNot { keyOf(it.prefix) == key } +
            Gateway(canonical, cc, "", "custom", Source.CUSTOM)
        p(ctx).edit().putString("custom", writeList(list)).apply()
        return null
    }

    fun removeCustom(ctx: Context, prefix: String) {
        val key = keyOf(prefix) ?: return
        p(ctx).edit().putString("custom", writeList(custom(ctx).filterNot { keyOf(it.prefix) == key })).apply()
    }

    private fun canonicalPrefix(prefix: String): String? {
        val b = prefixBytes(prefix) ?: return null
        val groups = (0 until 6).map { i -> Integer.toHexString(((b[2 * i].toInt() and 0xff) shl 8) or (b[2 * i + 1].toInt() and 0xff)) }
        return groups.joinToString(":") + "::"
    }

    // ── the fetched lists ───────────────────────────────────────────────────────────────────────

    private fun fetched(ctx: Context): List<Gateway> = readList(p(ctx).getString("fetched", null), Source.FETCHED)

    /**
     * Re-reads [SOURCES] when the copy is over a day old. Safe to call often; quiet on failure (the
     * sources live on GitHub, which some lines cannot reach -- the built-in list stands then).
     */
    fun refreshIfStale(ctx: Context, force: Boolean = false) {
        val prefs = p(ctx)
        if (!force && System.currentTimeMillis() - prefs.getLong("fetched_at", 0L) < 24 * 3600_000L) return
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        val rows = ArrayList<Pair<String, String>>()   // prefix -> country
        var any = false
        for (url in SOURCES) {
            val text = runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.string() else null
                }
            }.getOrNull() ?: continue
            any = true
            rows += parseTable(text)
        }
        if (!any) return
        // A prefix a source files under two different countries is the source's own copy-paste
        // (nat64.xyz lists all four of one provider's prefixes under each of its cities): its
        // country is unknown, so it is left out rather than guessed.
        val byKey = rows.groupBy { keyOf(it.first) }
        val list = byKey.mapNotNull { (k, v) ->
            if (k == null) return@mapNotNull null
            val countries = v.map { it.second }.distinct()
            if (countries.size != 1) return@mapNotNull null
            val prefix = canonicalPrefix(v.first().first) ?: return@mapNotNull null
            Gateway(prefix, countries.first(), "", "list", Source.FETCHED)
        }
        prefs.edit().putString("fetched", writeList(list)).putLong("fetched_at", System.currentTimeMillis()).apply()
        Log.i(TAG, "sources: ${list.size} gateways listed")
    }

    private val PREFIX_RE = Regex("""\[?([0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{0,4}){2,7})(?:/96)?\]?""")

    /** Markdown tables: any row naming a known country, with every /96-looking prefix in it. */
    internal fun parseTable(md: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (line in md.lines()) {
            if (!line.trim().startsWith("|")) continue
            val cells = line.split('|').map { it.trim() }
            val country = cells.firstNotNullOfOrNull { countryCode(it.substringBefore('/').trim()) } ?: continue
            // Only the prefix column: a DNS64 server address is not a prefix. A prefix ends in "::"
            // or carries "/96"; the resolvers in these tables end in a host part.
            for (cell in cells) {
                for (m in PREFIX_RE.findAll(cell)) {
                    val raw = m.value.trim('[', ']')
                    if (!(raw.endsWith("/96") || m.groupValues[1].endsWith("::"))) continue
                    out += m.groupValues[1] to country
                }
            }
        }
        return out
    }

    private val ALIASES = mapOf(
        "usa" to "US", "u.s.a." to "US", "u.s.a" to "US", "united states" to "US",
        "uk" to "GB", "united kingdom" to "GB", "england" to "GB",
        "netherland" to "NL", "netherlands" to "NL", "holland" to "NL",
        "hong kong" to "HK", "hongkong" to "HK",
    )

    private val countryNames: Map<String, String> by lazy {
        Locale.getISOCountries().associateBy { Locale("", it).getDisplayCountry(Locale.ENGLISH).lowercase(Locale.US) }
    }

    internal fun countryCode(name: String): String? {
        val n = name.replace(Regex("""\[|\]|\(.*?\)"""), "").trim().lowercase(Locale.US)
        if (n.length < 2) return null
        ALIASES[n]?.let { return it }
        return countryNames[n]
    }

    private fun readList(json: String?, source: Source): List<Gateway> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Gateway(o.getString("p"), o.getString("c"), o.optString("city"), o.optString("by"), source)
            }
        }.getOrDefault(emptyList())
    }

    private fun writeList(list: List<Gateway>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("p", it.prefix).put("c", it.country).put("city", it.city).put("by", it.provider)) }
    }.toString()

    // ── the selection ───────────────────────────────────────────────────────────────────────────

    private val _selection = MutableStateFlow<String?>(null)

    fun selection(ctx: Context): String = p(ctx).getString("selection", DIRECT).orEmpty()

    /** For the UI: recomposes when the selection changes. */
    fun selectionFlow(ctx: Context): StateFlow<String?> {
        if (_selection.value == null) _selection.value = selection(ctx)
        return _selection.asStateFlow()
    }

    fun setSelection(ctx: Context, value: String) {
        p(ctx).edit().putString("selection", value).apply()
        _selection.value = value
        // One thing decides the country at a time: a NAT64 country turns the free-proxy exit off.
        if (value.isNotBlank()) Ipv4Exits.disable(ctx)
    }

    fun isDirect(ctx: Context): Boolean = selection(ctx).isBlank()

    // ── health ──────────────────────────────────────────────────────────────────────────────────

    /** What the last ping found for one gateway. */
    data class Probe(
        val ok: Boolean,
        val rttMs: Long? = null,
        val mbps: Double? = null,
        /** The exit country Cloudflare reported through the tunnel. */
        val exitLoc: String? = null,
        val colo: String? = null,
        val at: Long = System.currentTimeMillis(),
    )

    private val _probes = MutableStateFlow<Map<String, Probe>>(emptyMap())
    val probes: StateFlow<Map<String, Probe>> = _probes.asStateFlow()

    fun loadProbes(ctx: Context) {
        if (_probes.value.isNotEmpty()) return
        val json = p(ctx).getString("probes", null) ?: return
        _probes.value = runCatching {
            val o = JSONObject(json)
            o.keys().asSequence().associateWith { k ->
                val x = o.getJSONObject(k)
                Probe(
                    ok = x.optBoolean("ok"),
                    rttMs = x.optLong("rtt", -1).takeIf { it >= 0 },
                    mbps = x.optDouble("mbps", -1.0).takeIf { it >= 0 },
                    exitLoc = x.optString("loc").ifBlank { null },
                    colo = x.optString("colo").ifBlank { null },
                    at = x.optLong("at"),
                )
            }
        }.getOrDefault(emptyMap())
    }

    fun recordProbe(ctx: Context, gw: Gateway, probe: Probe) {
        _probes.value = _probes.value + (gw.key to probe)
        val o = JSONObject()
        for ((k, v) in _probes.value) {
            o.put(k, JSONObject().put("ok", v.ok).put("rtt", v.rttMs ?: -1).put("mbps", v.mbps ?: -1.0)
                .put("loc", v.exitLoc.orEmpty()).put("colo", v.colo.orEmpty()).put("at", v.at))
        }
        p(ctx).edit().putString("probes", o.toString()).apply()
    }

    /** Connection outcomes, per gateway: a gateway that failed to connect moves to the back. */
    private fun failures(ctx: Context, gw: Gateway): Int = p(ctx).getInt("fail_${gw.key}", 0)

    fun reportOutcome(ctx: Context, endpoint: String?, ok: Boolean) {
        val gw = gatewayOfEndpoint(ctx, endpoint) ?: return
        val k = "fail_${gw.key}"
        val prefs = p(ctx)
        if (ok) prefs.edit().remove(k).apply() else prefs.edit().putInt(k, prefs.getInt(k, 0) + 1).apply()
    }

    private fun gatewayOfEndpoint(ctx: Context, endpoint: String?): Gateway? {
        val host = endpoint?.substringAfter('[', "")?.substringBefore(']')?.ifBlank { null } ?: return null
        val key = keyOf(host) ?: return null
        return gateways(ctx).firstOrNull { it.key == key }
    }

    /** How long a ping still counts as what the gateway does now. */
    private const val FRESH_MS = 6 * 3600_000L

    /**
     * What the last ping says about a gateway, best first: it carried the speed test (0), it
     * answered Cloudflare's trace but not the download (1), it was not measured lately (2), it did
     * not answer (3).
     *
     * Answering the trace is not carrying traffic. Measured 2026-10-20 on the test phone: London
     * answered in 220 ms and moved under 32 KB of the 2 MB download, and «سریع‌ترین» -- then ranked
     * by round trip alone -- took it over Frankfurt (328 ms, 7.4 Mbit/s). Through the tunnel the
     * same 2 MB took 8.2 s from London and 3.5 s from Frankfurt.
     */
    internal fun evidence(p: Probe?, now: Long): Int = when {
        p == null || now - p.at > FRESH_MS -> 2
        !p.ok -> 3
        p.mbps != null -> 0
        else -> 1
    }

    /**
     * The gateways to try for the current selection, best first: what the last ping proved (see
     * [evidence]), each failed connect counting as one step down, then the shortest round trip, then
     * the ones that carried WARP when the list was written.
     */
    fun candidates(ctx: Context): List<Gateway> {
        val sel = selection(ctx)
        if (sel.isBlank()) return emptyList()
        loadProbes(ctx)
        val probes = _probes.value
        val pool = if (sel == FASTEST) {
            gateways(ctx).filter { probes[it.key]?.ok == true }.ifEmpty { gateways(ctx).filter { it.verified } }
        } else {
            gatewaysOf(ctx, sel)
        }
        return order(pool, probes, { failures(ctx, it) }, System.currentTimeMillis())
    }

    /**
     * [candidates]' order. A failed connect weighs one step of evidence rather than everything: a
     * gateway that answered the ping and then went quiet once is still a better next try than one
     * the same ping found dead, which would cost a whole [com.mlmvpn.core.tunnel.TunnelVpnService]
     * gateway budget to find out again.
     */
    internal fun order(pool: List<Gateway>, probes: Map<String, Probe>, failures: (Gateway) -> Int, now: Long): List<Gateway> =
        pool.sortedWith(
            compareBy<Gateway> { evidence(probes[it.key], now) + failures(it).coerceIn(0, 3) }
                .thenBy { probes[it.key]?.takeIf { p -> p.ok }?.rttMs ?: Long.MAX_VALUE }
                .thenByDescending { it.verified }
        )

    /** Of the pings that answered, the one that says most: it carried the speed test, then the shortest round trip. */
    val BETTER_PROBE: Comparator<Probe> = compareBy<Probe> { it.mbps == null }.thenBy { it.rttMs ?: Long.MAX_VALUE }

    /** The endpoint the next connection should dial, or null for the direct (scanning) behaviour. */
    fun pick(ctx: Context, proto: Proto): String? =
        candidates(ctx).firstNotNullOfOrNull { endpoint(it, proto) }

    /** The country the current selection means, for messages: a code, or null for direct. */
    fun selectedCountry(ctx: Context): String? = when (val s = selection(ctx)) {
        DIRECT -> null
        FASTEST -> candidates(ctx).firstOrNull()?.country
        else -> s
    }
}
