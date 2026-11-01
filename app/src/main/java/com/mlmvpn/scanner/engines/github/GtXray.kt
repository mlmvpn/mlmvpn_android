package com.mlmvpn.scanner.engines.github

import org.json.JSONArray
import org.json.JSONObject

/**
 * The session's transport as the runner handed it back — checked before any of it reaches a
 * config or a URL path (the same rules as github-tunnel/gt-core.js › validateTransport).
 */
data class GtTransport(
    val hosts: List<String>,
    val wsPath: String,
    val uuids: Map<String, String>,
    val slotName: String?,
    val slotReady: Boolean,
    val runnerCountry: String,
    val runnerCity: String,
    val rev: Int,
    val phase: String,
    val errors: List<String>,
    /** The runner's own public address: traffic seen leaving from it went out by no exit at all. */
    val runnerIp: String = "",
    /** The ways in that are not Cloudflare (runner/relays.mjs) — reached directly, no Worker. */
    val relays: List<GtRelay> = emptyList(),
) {
    companion object {
        private val HOST = Regex("^[a-z0-9-]{3,63}\\.trycloudflare\\.com$")
        private val PATH = Regex("^/[A-Za-z0-9_-]{8,64}$")
        private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
        private val USER = Regex("^(direct|x[1-6])$")
        /** A bare IPv4 or IPv6 literal — nothing that could be a name or carry anything else. */
        private val IP_LITERAL = Regex("^([0-9]{1,3}(\\.[0-9]{1,3}){3}|[0-9a-fA-F:]{2,39})$")

        /** Parse the opened payload. Throws IllegalArgumentException on anything unexpected. */
        fun from(p: JSONObject): GtTransport {
            val hostsArr = p.optJSONArray("hosts") ?: JSONArray()
            val hosts = (0 until hostsArr.length()).map { i ->
                val h = hostsArr.opt(i)
                if (h is JSONObject) h.optString("host") else h?.toString().orEmpty()
            }
            val slot = p.optJSONObject("slot")
            val slotName = slot?.optString("name")?.takeIf { it.isNotBlank() }
            val slotReady = slot?.optBoolean("ready") == true
            if (slot != null) require(slotName in listOf("a", "b", "c")) { "bad slot" }
            require(hosts.all { HOST.matches(it) }) { "bad tunnel host" }
            // A relay outside the known suffixes is dropped, not fatal: a runner newer than this
            // app may know a relay this app does not, and the others still carry the session.
            val relaysArr = p.optJSONArray("relays") ?: JSONArray()
            val relays = (0 until relaysArr.length()).mapNotNull { i ->
                val o = relaysArr.optJSONObject(i) ?: return@mapNotNull null
                GtRelay.of(o.optString("host"), o.optString("kind"))
            }.distinctBy { it.host }
            require(hosts.isNotEmpty() || (slotName != null && slotReady) || relays.isNotEmpty()) { "no tunnel host" }
            val wsPath = p.optString("wsPath")
            require(PATH.matches(wsPath)) { "bad path" }
            val u = p.optJSONObject("uuids") ?: JSONObject()
            val uuids = u.keys().asSequence().associateWith { u.optString(it) }
            require(uuids["direct"]?.let { UUID.matches(it) } == true) { "bad id" }
            require(uuids.all { (k, v) -> USER.matches(k) && UUID.matches(v) }) { "bad exit id" }
            val runner = p.optJSONObject("runner")
            val errs = p.optJSONArray("errors")
            return GtTransport(
                hosts = hosts, wsPath = wsPath, uuids = uuids, slotName = slotName, slotReady = slotReady,
                runnerCountry = runner?.optString("country").orEmpty(), runnerCity = runner?.optString("city").orEmpty(),
                rev = p.optInt("rev", 1), phase = p.optString("phase"),
                errors = (0 until (errs?.length() ?: 0)).map { errs!!.optString(it) },
                runnerIp = runner?.optString("ip").orEmpty().takeIf { IP_LITERAL.matches(it) }.orEmpty(),
                relays = relays,
            )
        }
    }
}

/**
 * One way in that is not Cloudflare: a free public relay the runner dialled out to (runner/relays.mjs),
 * reached by its own HTTPS name — no Worker, no clean IP. `kind` says which service it is.
 * Measured 2026-10-06 from an Iranian mobile line: these four answered, trycloudflare.com did not.
 */
data class GtRelay(val host: String, val kind: String) {
    /** What the screen calls it. */
    val label: String get() = when (kind) {
        "lhr" -> "localhost.run"
        "srv" -> "srv.us"
        "serveo" -> "serveo"
        "lt" -> "localtunnel"
        else -> kind
    }

    companion object {
        private val SUFFIX = mapOf(
            "lhr" to Regex("^[a-z0-9-]{1,63}\\.lhr\\.(life|pro)$"),
            "srv" to Regex("^[a-z0-9-]{1,63}\\.srv\\.us$"),
            "serveo" to Regex("^[a-z0-9-]{1,63}\\.(serveo\\.net|serveousercontent\\.com)$"),
            "lt" to Regex("^[a-z0-9-]{1,63}\\.loca\\.lt$"),
        )

        /** Null unless the name belongs to the service it claims to be. */
        fun of(host: String, kind: String): GtRelay? {
            val h = host.trim().lowercase()
            return if (SUFFIX[kind]?.matches(h) == true) GtRelay(h, kind) else null
        }
    }
}

/**
 * The Xray configuration of the tunnel — the same shape the Windows client builds
 * (github-tunnel/gt-core.js › buildOutbound / buildConfig), for Android's core:
 *
 *   phone ──TLS (SNI: the user's own Worker)──► clean Cloudflare IP ──► the Worker
 *     ──/p/<signed pass>──► <label>.trycloudflare.com ──► cloudflared on the runner ──► Xray
 *
 * or, with no Worker (or none that this line reaches), straight to a relay (runner/relays.mjs):
 *
 *   phone ──TLS (SNI: the relay's own name)──► <name>.lhr.life / .srv.us / … ──► the runner ──► Xray
 *
 * MyVpnService adds the tun inbound (and its probe port) to a whole config like this one, so this
 * builds only the proxy side: a SOCKS inbound on the app's local port, one outbound per tunnel ×
 * clean address and one per relay, and a balancer that keeps traffic on whichever still answers.
 */
object GtXray {
    /** Quick tunnels refuse more than 200 in-flight requests each — so TCP is multiplexed. */
    const val MUX = 8
    const val PROBE_URL = "https://www.gstatic.com/generate_204"
    /** The tag MyVpnService gives the tun inbound it adds to a raw config. */
    const val TUN_INBOUND = "tun-in"

    /**
     * Which runner user each outbound group of a built config dials as — «gt=x1 gx-r0=x2 …» — by
     * NAME (direct, x1…x6), never the ids themselves. The one question a wrong exit country comes
     * down to: did the phone ask for the exit, or did the runner not honour it.
     */
    fun describeUsers(config: String, t: GtTransport): String = try {
        val byId = t.uuids.entries.associate { (k, v) -> v to k }
        val outs = JSONObject(config).optJSONArray("outbounds") ?: JSONArray()
        val groups = linkedMapOf<String, MutableSet<String>>()
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("protocol") != "vless") continue
            val tag = o.optString("tag")
            val group = if (tag.startsWith("gx-")) tag.substringBeforeLast('-') else "gt"
            val id = o.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0)
                ?.optJSONArray("users")?.optJSONObject(0)?.optString("id").orEmpty()
            groups.getOrPut(group) { linkedSetOf() }.add(byId[id] ?: "?")
        }
        groups.entries.joinToString(" ") { (g, users) -> "$g=${users.joinToString("/")}" }
    } catch (e: Exception) { "?" }

    fun label(host: String) = host.removeSuffix(".trycloudflare.com")

    /** The exact outbound the tunnel uses — the clean-IP measurement tries this very object. */
    fun outbound(ip: String, workerHost: String, pass: String, t: GtTransport, mux: Int = MUX, user: String = "direct"): JSONObject {
        val id = t.uuids[user] ?: t.uuids.getValue("direct")
        return JSONObject()
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject()
                .put("address", ip).put("port", 443)
                .put("users", JSONArray().put(JSONObject().put("id", id).put("encryption", "none"))))))
            .put("streamSettings", JSONObject()
                .put("network", "ws")
                .put("security", "tls")
                // http/1.1 ONLY: offered h2, Cloudflare picks it, and a WebSocket cannot ride h2.
                .put("tlsSettings", JSONObject().put("serverName", workerHost).put("fingerprint", "chrome")
                    .put("alpn", JSONArray().put("http/1.1")))
                .put("wsSettings", JSONObject().put("path", "/p/$pass${t.wsPath}?ed=2560").put("host", workerHost)
                    .put("heartbeatPeriod", 30)))
            // TCP multiplexed, UDP as XUDP, and QUIC refused so browsers fall back to TCP rather than
            // UDP inside TCP. mux -1 (a stable slot): every TCP connection gets its own WebSocket.
            .put("mux", JSONObject().put("enabled", true).put("concurrency", mux)
                .put("xudpConcurrency", 16).put("xudpProxyUDP443", "reject"))
    }

    /**
     * The outbound for a relay: TLS and the WebSocket straight to the relay's own name. Same user,
     * path and mux as [outbound] — the relay hands the WebSocket to the same Xray port.
     */
    fun relayOutbound(r: GtRelay, t: GtTransport, mux: Int = MUX, user: String = "direct"): JSONObject {
        val id = t.uuids[user] ?: t.uuids.getValue("direct")
        val ws = JSONObject().put("path", t.wsPath).put("host", r.host).put("heartbeatPeriod", 30)
        // localtunnel shows a browser-like client a reminder page instead of the upgrade.
        if (r.kind == "lt") ws.put("headers", JSONObject().put("bypass-tunnel-reminder", "1"))
        return JSONObject()
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject()
                .put("address", r.host).put("port", 443)
                .put("users", JSONArray().put(JSONObject().put("id", id).put("encryption", "none"))))))
            .put("streamSettings", JSONObject()
                .put("network", "ws")
                .put("security", "tls")
                .put("tlsSettings", JSONObject().put("serverName", r.host).put("fingerprint", "chrome")
                    .put("alpn", JSONArray().put("http/1.1")))
                .put("wsSettings", ws))
            .put("mux", JSONObject().put("enabled", true).put("concurrency", mux)
                .put("xudpConcurrency", 16).put("xudpProxyUDP443", "reject"))
    }

    /**
     * Which exit the connection leaves by (runner/exits.mjs): `use` — `direct` (the runner's own
     * address) or an exit slot `x1`…`x6`, a different VLESS user on the same tunnel; `rules` — sites
     * that leave by another slot. [normalize] drops anything this transport has no user for.
     */
    data class ExitPlan(val use: String = "direct", val rules: List<Rule> = emptyList()) {
        /** `uids`: the apps of the rule, as the UIDs the core's owner lookup answers with. */
        data class Rule(val id: String, val domains: List<String>, val uids: List<Int> = emptyList())

        fun normalize(t: GtTransport): ExitPlan {
            fun ok(id: String) = Regex("^x[1-6]$").matches(id) && t.uuids.containsKey(id)
            val u = if (ok(use)) use else "direct"
            return ExitPlan(u, rules.filter { ok(it.id) && it.id != u && (it.domains.isNotEmpty() || it.uids.isNotEmpty()) }
                .map { Rule(it.id, it.domains.take(200), it.uids.distinct().take(200)) })
        }
    }

    /**
     * Every tunnel host × every chosen clean address, and every relay that answered, behind one
     * balancer — an address that gets filtered mid-session costs a retry, not the connection. With
     * no Worker (`workerHost` null) or no clean address, the relays alone. Site rules
     * (gt-core.js › buildConfig): per rule, the first quick tunnel × the clean addresses and the
     * relays as ITS user, behind its own `gx-` balancer and a domain rule ahead of the default, so
     * no `gt-` selector ever picks them up.
     */
    fun config(session: GtSession, t: GtTransport, workerHost: String?, secret: String, ips: List<String>, localPort: Int,
               exitPlan: ExitPlan = ExitPlan(), relays: List<GtRelay> = emptyList()): String {
        val exp = session.expiresAt.takeIf { it > 0 } ?: (System.currentTimeMillis() + 6 * 3600_000L)
        val plan = exitPlan.normalize(t)
        val worker = workerHost?.takeIf { it.isNotBlank() }
        val cfIps = if (worker == null) emptyList() else ips
        val outs = JSONArray()
        var n = 0
        if (worker != null) for (h in t.hosts) {
            val pass = GtCrypto.signPass(secret, label(h), session.id, exp)
            for (ip in cfIps) outs.put(outbound(ip, worker, pass, t, user = plan.use).put("tag", "gt-${n++}"))
        }
        for (r in relays) outs.put(relayOutbound(r, t, user = plan.use).put("tag", "gt-${n++}"))
        val balancers = JSONArray().put(JSONObject().put("tag", "gt")
            .put("selector", JSONArray().put("gt-"))
            .put("strategy", JSONObject().put("type", "leastPing"))
            .put("fallbackTag", "gt-0"))
        // Sites first, then apps: a site the user named goes to its country whichever app opens it.
        // App rules match on the owner UID (VlessXrayInjector's finder); MyVpnService keeps the
        // tun's original destination for them (routeOnly), without which no lookup can succeed.
        val siteRules = mutableListOf<JSONObject>()
        val appRules = mutableListOf<JSONObject>()
        val first = t.hosts.firstOrNull()?.takeIf { worker != null && cfIps.isNotEmpty() }
        if (first != null || relays.isNotEmpty()) {
            val pass = first?.let { GtCrypto.signPass(secret, label(it), session.id, exp) }
            plan.rules.forEachIndexed { i, r ->
                val base = "gx-r$i-"
                var k = 0
                if (pass != null) cfIps.forEach { ip -> outs.put(outbound(ip, worker!!, pass, t, user = r.id).put("tag", "$base${k++}")) }
                relays.forEach { rl -> outs.put(relayOutbound(rl, t, user = r.id).put("tag", "$base${k++}")) }
                balancers.put(JSONObject().put("tag", "gx-r$i").put("selector", JSONArray().put(base))
                    .put("strategy", JSONObject().put("type", "leastPing")).put("fallbackTag", "${base}0"))
                if (r.domains.isNotEmpty()) siteRules.add(JSONObject().put("type", "field").put("balancerTag", "gx-r$i")
                    .put("domain", JSONArray().apply { r.domains.forEach { put("domain:$it") } }))
                // Only the tun's own connections: an app is known only for those (a proxy client
                // is a loopback socket), and Xray tests inboundTag first and process last, so the
                // owner lookup is never paid for the status channel or the probe port.
                if (r.uids.isNotEmpty()) appRules.add(JSONObject().put("type", "field").put("balancerTag", "gx-r$i")
                    .put("inboundTag", JSONArray().put(TUN_INBOUND))
                    .put("process", JSONArray().apply { r.uids.forEach { put(it.toString()) } }))
            }
        }
        val ruleRules = siteRules + appRules
        outs.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        val rules = JSONArray()
            // Nothing on the user's own network leaves through the runner.
            .put(JSONObject().put("type", "field").put("outboundTag", "block")
                .put("ip", JSONArray().put("10.0.0.0/8").put("172.16.0.0/12").put("192.168.0.0/16")
                    .put("127.0.0.0/8").put("169.254.0.0/16")))
        ruleRules.forEach { rules.put(it) }
        rules.put(JSONObject().put("type", "field").put("network", "tcp,udp").put("balancerTag", "gt"))
        return JSONObject()
            // No access log: every destination the user visits would otherwise be written down.
            .put("log", JSONObject().put("loglevel", "warning").put("access", "none"))
            .put("remarks", "GitHub Tunnel")
            .put("inbounds", JSONArray().put(JSONObject()
                .put("tag", "socks").put("listen", "127.0.0.1").put("port", localPort).put("protocol", "socks")
                .put("settings", JSONObject().put("udp", true).put("auth", "noauth"))
                .put("sniffing", JSONObject().put("enabled", true)
                    .put("destOverride", JSONArray().put("http").put("tls")).put("routeOnly", true))))
            .put("outbounds", outs)
            .put("observatory", JSONObject()
                .put("subjectSelector", JSONArray().put("gt-").apply { if (ruleRules.isNotEmpty()) put("gx-") })
                .put("probeUrl", PROBE_URL).put("probeInterval", "60s").put("enableConcurrency", true))
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("balancers", balancers).put("rules", rules))
            .toString()
    }
}
