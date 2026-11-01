package com.mlmvpn.scanner.store

import org.json.JSONArray
import org.json.JSONObject

/**
 * The «کانفیگ‌های ایران» list, built from @patterniha's Serverless-for-Iran files -- on the phone.
 *
 * The store item used to come only from our own channel, so when upstream published v51 and v52
 * the store never noticed (reported 2026-10-04). It now follows the upstream repository itself:
 * the store downloads `Serverless-frag{A,B}.jsonc` and this rebuilds the nineteen rows from them,
 * the same rows, in the same order, as the desktop's `iran-profiles-gen.js` that generated the
 * shipped asset -- a port of it, check for check:
 *
 *   1. the two upstream files and the vendored v48 pair, as published;
 *   2. every one of the four with each resolver this app knows (Google / Cloudflare / AdGuard by
 *      IP), that resolver reached unfragmented -- measured 2026-09-12: upstream's own
 *      `cloudflare-dns.com` resolved nothing on an Iranian mobile line;
 *   3. fragA with no fragmentation at all («فقط DNS تمیز»), per resolver.
 *
 * Every edit throws when upstream's shape moves (exactly one no-filter-dns server, exactly one
 * route for it, at least two catch-all fragment routes) rather than shipping a half-edited config,
 * and every result must still be SERVER-LESS: these files arrive unsigned, and the one thing a
 * hostile edit would want -- an outbound to a machine it controls -- is refused, not trusted.
 */
object IranProfilesBuilder {

    class BuildError(message: String) : Exception(message)

    data class Resolver(val key: String, val doh: String, val label: String)

    val RESOLVERS = listOf(
        Resolver("google", "https://8.8.8.8/dns-query", "Google"),
        Resolver("cloudflare", "https://1.1.1.1/dns-query", "Cloudflare"),
        Resolver("adguard", "https://94.140.14.14/dns-query", "AdGuard"),
    )

    private val SERVERLESS = setOf("freedom", "direct", "blackhole", "block", "dns", "loopback")

    /** Upstream's release number, from a file's `remarks` (`Serverless-v52-fragA` → 52). */
    val VERSION = Regex("""Serverless-v(\d+)""")

    // ── jsonc → JSON ──────────────────────────────────────────────────────────────────────────

    /**
     * Comments and trailing commas out; strings respected, so the `//` of an `https://` inside a
     * value is not taken for a comment (that mistake still parses, and breaks every URL).
     */
    fun stripJsonComments(text: String): String {
        val out = StringBuilder(text.length)
        var inStr = false
        var quote = ' '
        var esc = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
            if (inStr) {
                out.append(c)
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == quote) inStr = false
                i++
                continue
            }
            if (c == '"' || c == '\'') { inStr = true; quote = c; out.append(c); i++; continue }
            if (c == '/' && next == '/') {
                while (i < text.length && text[i] != '\n') i++
                out.append('\n')
                continue
            }
            if (c == '/' && next == '*') {
                i += 2
                while (i < text.length && !(text[i] == '*' && i + 1 < text.length && text[i + 1] == '/')) i++
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString().replace(Regex(""",(\s*[}\]])"""), "$1")
    }

    /** An upstream file as the JSON the rows carry; throws when it is not a whole Xray config. */
    fun normalizeSource(text: String): String {
        val obj = try { JSONObject(stripJsonComments(text.removePrefix("﻿"))) } catch (e: Exception) {
            throw BuildError("not JSON: ${e.message}")
        }
        for (k in listOf("outbounds", "inbounds", "routing", "dns")) {
            if (!obj.has(k)) throw BuildError("not a whole Xray config (no $k)")
        }
        return obj.toString(2)
    }

    // ── the two edits ─────────────────────────────────────────────────────────────────────────

    /** The resolver swapped for [doh], reached unfragmented. Nothing about the fragmentation changes. */
    fun withReachableResolver(src: String, doh: String): String {
        val cfg = JSONObject(src)
        val dns = cfg.getJSONObject("dns")
        dns.remove("hosts")
        val servers = dns.getJSONArray("servers")
        var found = 0
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            if (s.optString("tag") == "no-filter-dns") { s.put("address", doh); found++ }
        }
        if (found != 1) throw BuildError("expected exactly one no-filter-dns server, found $found")
        val rules = cfg.getJSONObject("routing").getJSONArray("rules")
        var routed = 0
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            val tags = r.optJSONArray("inboundTag") ?: continue
            if (tags.length() == 1 && tags.optString(0) == "no-filter-dns") { r.put("outboundTag", "tcp-direct"); routed++ }
        }
        if (routed != 1) throw BuildError("expected exactly one no-filter-dns route, found $routed")
        // The domain lists name the old resolver; with it gone they would be stale.
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            val domains = s.optJSONArray("domains") ?: continue
            val kept = JSONArray()
            for (j in 0 until domains.length()) {
                val d = domains.opt(j)
                if (d != "full:challenges.cloudflare.com") kept.put(d)
            }
            s.put("domains", kept)
        }
        cfg.put("remarks", cfg.optString("remarks") + "-directDNS")
        return cfg.toString(2)
    }

    /** The same with its catch-all fragment routes sent direct: the honest-DNS half on its own. */
    fun withoutFragmentation(src: String, doh: String): String {
        val cfg = JSONObject(withReachableResolver(src, doh))
        val rules = cfg.getJSONObject("routing").getJSONArray("rules")
        var moved = 0
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            val ip = r.optJSONArray("ip") ?: continue
            val everywhere = (0 until ip.length()).any { ip.optString(it) == "0.0.0.0/0" }
            val out = r.optString("outboundTag")
            if (everywhere && (out == "tcp-fragment-tls" || out == "tcp-fragment")) { r.put("outboundTag", "tcp-direct"); moved++ }
        }
        if (moved < 2) throw BuildError("expected the catch-all fragment routes, found $moved")
        cfg.put("remarks", cfg.optString("remarks").replace("-directDNS", "") + "-cleanDNS")
        return cfg.toString(2)
    }

    /** Every outbound server-less, none carrying an address; throws otherwise. */
    fun assertServerless(config: JSONObject, label: String) {
        val outs = config.optJSONArray("outbounds")
        if (outs == null || outs.length() == 0) throw BuildError("$label: no outbounds")
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: throw BuildError("$label: bad outbound")
            val proto = o.optString("protocol").lowercase()
            if (proto !in SERVERLESS) throw BuildError("$label: outbound «$proto» has a server")
            val st = o.optJSONObject("settings")
            if (st != null && (st.has("vnext") || st.has("servers") || st.has("address") || st.has("peers"))) {
                throw BuildError("$label: outbound «$proto» carries a server address")
            }
        }
    }

    // ── the list ──────────────────────────────────────────────────────────────────────────────

    data class Profile(val id: String, val name: String, val method: String, val dns: String, val note: String, val config: String)

    private data class Shape(val src: String, val label: String)

    /**
     * The rows from [fragA] and [fragB] (upstream, any version) and the vendored [v48low] /
     * [v48high], in the order the shipped asset has them -- the rows are known by position.
     */
    fun build(fragA: String, fragB: String, v48low: String, v48high: String): List<Profile> {
        val a = normalizeSource(fragA)
        val b = normalizeSource(fragB)
        val va = VERSION.find(JSONObject(a).optString("remarks"))?.groupValues?.get(1)
            ?: throw BuildError("fragA carries no Serverless-vNN remarks")
        val vb = VERSION.find(JSONObject(b).optString("remarks"))?.groupValues?.get(1)
            ?: throw BuildError("fragB carries no Serverless-vNN remarks")
        val shapes = listOf(
            Shape(a, "v$va fragA"),
            Shape(b, "v$vb fragB"),
            Shape(normalizeSource(v48low), "v48 دیلی کم"),
            Shape(normalizeSource(v48high), "v48 دیلی بالا"),
        )
        val out = ArrayList<Profile>()
        fun add(name: String, method: String, dns: String, note: String, config: String) {
            out += Profile("iran-${out.size + 1}", name, method, dns, note, config)
        }
        shapes.forEach { add("${it.label} — اصل پروژه", "stock", "", "همان‌طور که سازنده منتشر کرده.", it.src) }
        RESOLVERS.forEach { r ->
            shapes.forEach { sh ->
                add("${sh.label} — DNS ${r.label}", "resolver", r.doh, "همان کانفیگ، فقط نام‌ها را از ${r.label} می‌پرسد.",
                    withReachableResolver(sh.src, r.doh))
            }
        }
        RESOLVERS.forEach { r ->
            add("فقط DNS تمیز — ${r.label}", "clean", r.doh, "سایت‌های بسته‌شده با دستکاری DNS را باز می‌کند، نه سایت‌های فیلترشده.",
                withoutFragmentation(a, r.doh))
        }
        val seen = HashSet<String>()
        for (p in out) {
            val c = JSONObject(p.config)
            for (k in listOf("outbounds", "inbounds", "routing")) if (!c.has(k)) throw BuildError("${p.id}: not a whole config")
            assertServerless(c, p.name)
            if (!seen.add(p.config)) throw BuildError("${p.id}: identical to an earlier row")
        }
        return out
    }

    /**
     * [existingList] with upstream's new files added -- and nothing else changed.
     *
     * The user's rule (2026-10-04): an update must not touch the configs already there; it adds
     * the new version's configs (upstream publishes two, fragA and fragB) at the top, newest
     * first. A file whose `remarks` the list already carries is not added twice. Throws when there
     * is nothing new, or when a new file is not a whole, server-less config.
     */
    fun merge(existingList: String, upstreamFiles: List<String>): String {
        val existing = JSONArray(existingList)
        val have = HashSet<String>()
        for (i in 0 until existing.length()) {
            val o = existing.optJSONObject(i) ?: continue
            runCatching { JSONObject(o.optString("config")).optString("remarks") }.getOrNull()?.let { if (it.isNotBlank()) have += it }
        }
        val added = ArrayList<Profile>()
        for (text in upstreamFiles) {
            val cfg = normalizeSource(text)
            val remarks = JSONObject(cfg).optString("remarks")
            val m = Regex("""Serverless-v(\d+)-?(.*)""").find(remarks)
                ?: throw BuildError("no Serverless-vNN remarks in an upstream file")
            if (remarks in have) continue
            val tag = m.groupValues[2].ifBlank { "${added.size + 1}" }
            val label = "v${m.groupValues[1]} $tag"
            assertServerless(JSONObject(cfg), label)
            added += Profile("iran-v${m.groupValues[1]}-${tag.replace(Regex("[^A-Za-z0-9_-]"), "")}",
                "$label — اصل پروژه", "stock", "", "همان‌طور که سازنده منتشر کرده.", cfg)
            have += remarks
        }
        if (added.isEmpty()) throw BuildError("nothing new: the list already has these configs")
        val out = JSONArray()
        added.forEach { p ->
            out.put(JSONObject().put("id", p.id).put("name", p.name).put("method", p.method)
                .put("dns", p.dns).put("note", p.note).put("config", p.config))
        }
        for (i in 0 until existing.length()) out.put(existing.get(i))
        return out.toString()
    }

    /** The list as the app's `iran_profiles.json`. */
    fun toJson(list: List<Profile>): String = JSONArray().apply {
        list.forEach { p ->
            put(JSONObject().put("id", p.id).put("name", p.name).put("method", p.method)
                .put("dns", p.dns).put("note", p.note).put("config", p.config))
        }
    }.toString()

    /**
     * The vendored v48 pair, read out of a shipped list: its two «اصل پروژه» rows are those files
     * exactly as published, so nothing has to be shipped twice.
     */
    fun v48From(shippedList: String): Pair<String, String> {
        val arr = JSONArray(shippedList)
        var low: String? = null
        var high: String? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("method") != "stock") continue
            val cfg = o.optString("config")
            when (runCatching { JSONObject(cfg).optString("remarks") }.getOrDefault("")) {
                "Serverless-v48-low_delay" -> low = cfg
                "Serverless-v48-high_delay" -> high = cfg
            }
        }
        if (low == null || high == null) throw BuildError("the shipped list has no v48 pair")
        return low to high
    }
}
