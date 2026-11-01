package com.mlmvpn.scanner.utils

import org.json.JSONArray
import org.json.JSONObject
import com.mlmvpn.scanner.data.studio.config.ConfigBuilder
import com.mlmvpn.scanner.data.studio.config.toConfigSpec

object XrayJsonGenerator {

    /**
     * Whether to send Cloudflare-fronted TLS in its "unfiltered" shape (fragment + the unsafe
     * fingerprint). See [CfUnfilter].
     *
     * Held here rather than passed in, because it has to reach ELEVEN call sites and most of them
     * are measurement paths. A config must be measured exactly the way it will be connected, or
     * the delay figure is about a handshake the user will never make -- so a parameter that some
     * callers forget is worse than a value they cannot forget. Written in exactly two places:
     * once at application start, and again whenever the switch is touched.
     */
    @Volatile
    var tlsUnfilter: Boolean = false

    /**
     * The Gemini / Google apps switch. On unless the user turned it off, and off is the old
     * behaviour exactly, everywhere it reaches.
     *
     * It does two things, and both matter only for Cloudflare-worker configs:
     *  1. QUIC is refused at once instead of silently dropped -- see [QuicRefuser].
     *  2. Google's servers reach the worker as an IPv4 ADDRESS, never as a name -- see
     *     [googleByIpv4]. This is the half that makes Gemini open.
     *
     * Held here for the same reason as [tlsUnfilter], and written the same two ways: once at
     * application start, and again whenever the switch is touched.
     */
    @Volatile
    var googleFix: Boolean = true

    /** Tag of the outbound that answers QUIC with a refusal. Checked for before adding a second one. */
    const val QUIC_REFUSAL_TAG = "quic-refuse"

    /** Tag of the tunnel copy that hands Google's servers to the worker by IPv4 address. */
    const val GOOGLE_V4_TAG = "proxy-google"

    /** Tag of the outbound to the user's Gemini exit. See [geminiExit]. */
    const val GEMINI_EXIT_TAG = "gemini-exit"

    /** Where the user's Gemini exit answers: its workers.dev host, WebSocket path and user id. */
    data class GeminiExitRoute(val host: String, val path: String, val id: String) {
        fun encode(): String = "$host|$path|$id"

        companion object {
            fun decode(value: String?): GeminiExitRoute? = value?.split('|')
                ?.takeIf { it.size == 3 && it.all { part -> part.isNotBlank() } }
                ?.let { GeminiExitRoute(it[0], it[1], it[2]) }
        }
    }

    /**
     * The user's Gemini exit, or null when none is set up.
     *
     * Handing Google an IPv4 address ([googleByIpv4]) fixed the IPv6 half of Gemini's refusal. The
     * other half is WHERE the worker runs: it leaves from the Cloudflare data centre nearest the
     * phone, and from Iran that is a European one whose exit Google places in Russia (measured
     * 2026-09-25 -- Google's homepage said "Russia", Gemini's page carried Moscow time, and the
     * same worker reached from a US line got the full page). The exit is a Worker on the user's
     * own account whose Durable Object lives in North America (assets/gemini_exit_worker.js), so
     * Gemini's traffic leaves from there. Only [GEMINI_DOMAINS]; the rest of Google keeps
     * [googleByIpv4].
     *
     * Written at application start and again after a deploy, like [googleFix].
     */
    @Volatile
    var geminiExit: GeminiExitRoute? = null

    /**
     * Gemini and Google's other AI services -- the ones that refuse by country. Each covers the
     * name and everything under it.
     */
    private val GEMINI_DOMAINS = listOf(
        "gemini.google.com", "gemini.google", "bard.google.com",
        "aistudio.google.com", "makersuite.google.com", "notebooklm.google.com", "notebooklm.google",
        "labs.google", "generativelanguage.googleapis.com", "alkalimakersuite-pa.clients6.google.com",
        // The Gemini Android app's own backend ("Robin" is its codename).
        "robinfrontend-pa.googleapis.com",
    )

    private fun geminiRules(): JSONArray = JSONArray().apply { GEMINI_DOMAINS.forEach { put("domain:$it") } }

    /** Sends [GEMINI_DOMAINS] to the exit. Goes ahead of [googleRoute], which covers them too. */
    private fun geminiRoute(): JSONObject = JSONObject().apply {
        put("type", "field")
        put("domain", geminiRules())
        put("outboundTag", GEMINI_EXIT_TAG)
    }

    /**
     * The outbound to the user's Gemini exit, reached the way [tunnel] reaches its worker: the same
     * address and port (a clean IP that works on this line), the same TLS fingerprint and socket
     * options -- so whatever gets the main tunnel past the filter gets this past it too. Only the
     * server name, path and user change. Null when no exit is set up, or [tunnel] has no server to
     * borrow.
     *
     * Names are passed through, not turned into addresses here: the exit resolves them itself, from
     * North America, to IPv4 -- which picks the Google front end nearest IT rather than one near
     * the phone, and never the IPv6 exit Google refuses.
     */
    private fun geminiExitOutbound(tunnel: JSONObject): JSONObject? = geminiExit?.let { geminiExitOutboundVia(tunnel, it) }

    /** [geminiExitOutbound] for a given [exit]: also used by MAE, which borrows one of the user's configs. */
    fun geminiExitOutboundVia(tunnel: JSONObject, exit: GeminiExitRoute): JSONObject? {
        val settings = tunnel.optJSONObject("settings") ?: return null
        val server = settings.optJSONArray("vnext")?.optJSONObject(0)
            ?: settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
        val address = server.optString("address").takeIf { it.isNotBlank() } ?: return null
        val theirs = tunnel.optJSONObject("streamSettings") ?: JSONObject()
        val theirTls = theirs.optJSONObject("tlsSettings")
        // A worker behind plain HTTP (port 80 and friends) cannot carry TLS on its port: use 443.
        val port = if (theirs.optString("security") == "tls") server.optInt("port", 443) else 443
        val stream = JSONObject().apply {
            put("network", "ws")
            put("security", "tls")
            put("tlsSettings", JSONObject().apply {
                put("serverName", AntiDpi.generateMixedCaseSNI(exit.host))
                // The tunnel's own handshake, whatever it is: its fingerprint (`unsafe` included) and the
                // cipher list that only counts with it.
                put("fingerprint", theirTls?.optString("fingerprint").orEmpty().ifBlank { "chrome" })
                theirTls?.optString("cipherSuites")?.takeIf { it.isNotBlank() }?.let { put("cipherSuites", it) }
                theirTls?.optString("maxVersion")?.takeIf { it.isNotBlank() }?.let { put("maxVersion", it) }
                put("alpn", JSONArray().put("http/1.1"))
            })
            put("wsSettings", JSONObject().apply {
                put("path", "/" + exit.path)
                put("host", AntiDpi.generateMixedCaseSNI(exit.host))
                theirs.optJSONObject("wsSettings")?.optJSONObject("headers")?.let { put("headers", JSONObject(it.toString())) }
            })
            theirs.optJSONObject("sockopt")?.let { put("sockopt", JSONObject(it.toString())) }
            // The fragment masks too: the exit is reached the way the tunnel is, handshake included.
            theirs.optJSONObject("finalmask")?.let { put("finalmask", JSONObject(it.toString())) }
        }
        return JSONObject().apply {
            put("tag", GEMINI_EXIT_TAG)
            put("protocol", "vless")
            put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().apply {
                put("address", address)
                put("port", port)
                put("users", JSONArray().put(JSONObject().put("id", exit.id).put("encryption", "none")))
            })))
            put("streamSettings", stream)
        }
    }

    /** Tag of the resolver Google's names are looked up with. See [googleDnsServer]. */
    private const val GOOGLE_DNS_TAG = "google-dns"

    /**
     * The policy level the refusal outbound runs at. Any number no real config uses would do; see
     * [addQuicRefusalPolicy] for what it changes and why it has to be a level of its own.
     */
    private const val QUIC_REFUSAL_LEVEL = 97

    /** Transports a Cloudflare Worker is reached over. A worker has no UDP, so none carries QUIC. */
    private val WORKER_TRANSPORTS = setOf("ws", "websocket", "httpupgrade", "xhttp", "splithttp")

    /** Protocols that tunnel to a server, as opposed to leaving directly or answering locally. */
    private val TUNNEL_PROTOCOLS = setOf("vless", "vmess", "trojan")

    /**
     * Google's own domains. Each covers the name and everything under it (`google` is the TLD:
     * labs.google, gemini.google).
     *
     * YouTube -- youtube.com, googlevideo.com, ytimg.com -- is left out on purpose: it already
     * works over the worker's IPv6 exit, and its video is the heaviest thing a phone sends, so
     * there is nothing to gain from moving it.
     */
    private val GOOGLE_DOMAINS = listOf(
        "google.com", "googleapis.com", "gstatic.com", "googleusercontent.com", "google",
        "google.dev", "ggpht.com", "gvt1.com", "gvt2.com", "withgoogle.com",
    )

    /** [GOOGLE_DOMAINS] as Xray domain rules. */
    private fun googleRules(): JSONArray = JSONArray().apply { GOOGLE_DOMAINS.forEach { put("domain:$it") } }

    /** Whether an Xray domain rule -- "domain:x", "full:x" or a bare name -- names one of Google's. */
    private fun isGoogleRule(rule: String): Boolean {
        val host = rule.substringAfter(':').trim().lowercase()
        return GOOGLE_DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * A copy of the tunnel [proxy] that hands the worker an IPv4 address instead of a name.
     *
     * ## Why Gemini would not open
     *
     * A worker dials what it is given. Given a NAME, Cloudflare's `connect()` picks the address
     * itself and prefers IPv6 wherever the site has one -- and Google has one everywhere -- so the
     * connection leaves from the worker's IPv6 exit, a shared Cloudflare WARP range Google will not
     * serve Gemini to. Measured through a live panel config on the phone (2026-09-23): by name,
     * every request for gemini.google.com came back as Google's "unusual traffic" page, which the
     * Gemini app shows as "not available in your country"; by IPv4 address, the same worker left
     * from its IPv4 exit (104.28.x.x) and got the full Gemini page, twice out of two.
     *
     * The tunnel sent names because the sniffer puts them there: `destOverride` replaces every
     * destination with the name from its TLS handshake, and must -- that is what rescues a
     * connection the ISP's resolver poisoned. `targetStrategy: UseIPv4` turns the name back into an
     * IPv4 address just before it leaves, looked up by [googleDnsServer] through the tunnel so the
     * ISP cannot poison it. When that lookup fails Xray sends the name as before, so this can only
     * ever help.
     *
     * NetDoctor (Dr.Net) gets the same effect from its whole design: sing-box resolves remotely
     * with `ipv4_only` and does not replace destinations, so its worker is only ever given IPv4
     * addresses. Here only Google is moved; everything else keeps the path it had.
     */
    private fun googleByIpv4(proxy: JSONObject): JSONObject =
        JSONObject(proxy.toString())
            .put("tag", GOOGLE_V4_TAG)
            .put("targetStrategy", "UseIPv4")

    /**
     * The resolver for Google's names: DNS over HTTPS to 8.8.8.8, through the tunnel, IPv4 only.
     *
     * Through the tunnel because Iran's resolvers poison names, and [googleByIpv4] would hand the
     * worker a poisoned address as faithfully as a real one. IPv4 only so an app asking for an
     * address gets no IPv6 one to connect to in the first place. `skipFallback` keeps every other
     * name off it, so nothing else changes resolver.
     */
    private fun googleDnsServer(): JSONObject = JSONObject().apply {
        put("address", "https://8.8.8.8/dns-query")
        put("domains", googleRules())
        put("skipFallback", true)
        put("queryStrategy", "UseIPv4")
        put("tag", GOOGLE_DNS_TAG)
    }

    /** Sends [googleDnsServer]'s own queries through the tunnel tagged [tunnelTag]. */
    private fun googleDnsRoute(tunnelTag: String): JSONObject = JSONObject().apply {
        put("type", "field")
        put("inboundTag", JSONArray().put(GOOGLE_DNS_TAG))
        put("outboundTag", tunnelTag)
    }

    /** Sends [domains] through the [googleByIpv4] copy. */
    private fun googleRoute(domains: JSONArray): JSONObject = JSONObject().apply {
        put("type", "field")
        put("domain", domains)
        put("outboundTag", GOOGLE_V4_TAG)
    }

    /**
     * The outbound that hands QUIC to [QuicRefuser], or null to keep the old silent drop: when the
     * switch is off, or when the responder could not be opened.
     */
    private fun quicRefusal(): JSONObject? {
        if (!googleFix) return null
        val port = QuicRefuser.port() ?: return null
        return JSONObject().apply {
            put("protocol", "freedom")
            put("tag", QUIC_REFUSAL_TAG)
            put("settings", JSONObject().apply {
                // freedom sends every packet of the flow here, and the answer goes back to the app
                // stamped with the flow's own destination -- see QuicRefuser.
                put("redirect", "127.0.0.1:$port")
                put("userLevel", QUIC_REFUSAL_LEVEL)
            })
        }
    }

    /**
     * The QUIC refusal outbound for a config built elsewhere (FLUX's compiler), or null when the
     * switch is off or the responder is not open. Pair it with [addQuicRefusalPolicyTo].
     */
    fun quicRefusalOutbound(): JSONObject? = quicRefusal()

    /** The refusal outbound's policy level, written into a config built elsewhere. */
    fun addQuicRefusalPolicyTo(json: JSONObject) = addQuicRefusalPolicy(json)

    /**
     * A policy level that lets each refused flow go a few seconds after its answer.
     *
     * The default keeps an idle UDP flow for 300 s, and every refused attempt is a flow of its own: a
     * socket and its goroutines, held for five minutes to carry nothing. The answer leaves in the
     * first millisecond, so four idle seconds is already generous. Only this level is written; a
     * config's own levels -- level 0, which every other connection runs at, above all -- are left
     * exactly as they were.
     */
    private fun addQuicRefusalPolicy(json: JSONObject) {
        val policy = json.optJSONObject("policy") ?: JSONObject().also { json.put("policy", it) }
        val levels = policy.optJSONObject("levels") ?: JSONObject().also { policy.put("levels", it) }
        val key = QUIC_REFUSAL_LEVEL.toString()
        if (!levels.has(key)) {
            levels.put(key, JSONObject().apply {
                put("connIdle", 4)
                put("uplinkOnly", 1)
                put("downlinkOnly", 1)
            })
        }
    }

    /**
     * Whether a whole config's tunnel can carry UDP, judged from its outbounds.
     *
     * The transport decides it, not the protocol: VLESS over WebSocket is a worker and VLESS over
     * TCP is a server. So only a config whose every VLESS/VMess/Trojan outbound rides a worker
     * transport is treated as unable -- a chain (BPB's worker in front of a real server), a WARP
     * config or a REALITY one all carry UDP, and refusing their QUIC would throw away the faster
     * protocol for nothing. A config with no tunnel at all (fragment-only, DNS-only) is left to
     * whatever its author chose.
     */
    private fun carriesUdp(outbounds: JSONArray): Boolean {
        var tunnels = 0
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            when (outbound.optString("protocol").lowercase()) {
                in TUNNEL_PROTOCOLS -> {
                    tunnels++
                    val network = outbound.optJSONObject("streamSettings")
                        ?.optString("network").orEmpty().lowercase()
                    if (network !in WORKER_TRANSPORTS) return true
                }
                "wireguard", "hysteria", "hysteria2", "tuic", "shadowsocks", "socks" -> return true
            }
        }
        return tunnels == 0
    }

    /** The config's one tunnel outbound, or null when it has several (a balancer) or no tag. */
    private fun singleTunnel(outbounds: JSONArray): JSONObject? {
        var found: JSONObject? = null
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            if (outbound.optString("protocol").lowercase() !in TUNNEL_PROTOCOLS) continue
            if (found != null) return null
            found = outbound
        }
        return found?.takeIf { it.optString("tag").isNotEmpty() }
    }

    /**
     * [config] -- a whole Xray config the user imported -- with the Gemini / Google apps fix, or
     * null when none of it applies: the switch is off, the config's tunnel is not a worker
     * ([carriesUdp]), it already has the fix, or it cannot be read.
     *
     * Such configs bring their own routing, so each half is fitted around it:
     *  - the QUIC refusal goes FIRST. Panel ones (BPB's Xray subscription) already send UDP/443 to a
     *    `block` blackhole -- the same silent drop [generateConfig] used to make.
     *  - the Google rule goes just before the first rule of theirs that uses the tunnel, so anything
     *    they route elsewhere on purpose still goes there. Only for a config with one tunnel
     *    outbound: with a balancer over several there is no single one to copy. Google's names get
     *    [googleDnsServer] only where the config already has a DNS section -- adding one to a config
     *    without would take its other names off the system resolver.
     */
    fun applyGoogleFix(config: String): String? {
        if (!googleFix) return null
        return try {
            val json = JSONObject(config)
            val outbounds = json.optJSONArray("outbounds") ?: return null
            for (i in 0 until outbounds.length()) {
                val tag = outbounds.optJSONObject(i)?.optString("tag")
                if (tag == QUIC_REFUSAL_TAG || tag == GOOGLE_V4_TAG || tag == GEMINI_EXIT_TAG) return null
            }
            if (carriesUdp(outbounds)) return null

            val routing = json.optJSONObject("routing") ?: JSONObject()
            val theirs = routing.optJSONArray("rules") ?: JSONArray()
            val rules = JSONArray()

            val tunnel = singleTunnel(outbounds)
            val tunnelTag = tunnel?.optString("tag").orEmpty()
            // Where the Google rule goes: before their first rule into the tunnel, or last when the
            // tunnel is their default outbound and no rule names it. -1: nowhere safe.
            var googleAt = -1
            if (tunnel != null) {
                for (i in 0 until theirs.length()) {
                    if (theirs.optJSONObject(i)?.optString("outboundTag") == tunnelTag) {
                        googleAt = i
                        break
                    }
                }
                if (googleAt < 0 && outbounds.optJSONObject(0) === tunnel) googleAt = theirs.length()
            }
            val servers = json.optJSONObject("dns")?.optJSONArray("servers")
            if (googleAt >= 0 && servers != null) {
                servers.put(googleDnsServer())
                rules.put(googleDnsRoute(tunnelTag))
            }

            val refusal = quicRefusal()
            if (refusal != null) {
                outbounds.put(refusal)
                addQuicRefusalPolicy(json)
                rules.put(JSONObject().apply {
                    put("type", "field")
                    put("network", "udp")
                    put("port", "443")
                    put("outboundTag", QUIC_REFUSAL_TAG)
                })
            }
            if (googleAt >= 0) outbounds.put(googleByIpv4(tunnel!!))
            val exit = if (googleAt >= 0) geminiExitOutbound(tunnel!!) else null
            if (exit != null) outbounds.put(exit)
            if (refusal == null && googleAt < 0) return null

            for (i in 0..theirs.length()) {
                if (i == googleAt) {
                    if (exit != null) rules.put(geminiRoute())
                    rules.put(googleRoute(googleRules()))
                }
                if (i < theirs.length()) rules.put(theirs.get(i))
            }
            routing.put("rules", rules)
            json.put("routing", routing)
            json.toString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * `streamSettings` for an outbound, from the one shared builder.
     *
     * This file used to construct `streamSettings` in **three** places -- `generateConfig`,
     * `generateAntiSanctionConfig` and `generateMultiConfig` -- and the three had already drifted:
     *
     *  - only `generateConfig` handled **xhttp** at all. The other two emitted `network: "xhttp"`
     *    with no `xhttpSettings`, so the mode fell back to a default that does not work through
     *    Cloudflare (it buffers request bodies, so anything but `packet-up` hangs). An xhttp config
     *    measured by `generateMultiConfig` was therefore being measured through a broken outbound.
     *  - `generateMultiConfig` sent `User-Agent: Mozilla/5.0` where the other two sent a full
     *    Chrome string -- a two-token user agent is a fingerprint of its own.
     *
     * Both are fixed by there being one builder rather than three. [ConfigBuilder] is also what
     * Config Studio uses to *emit* configs, so the link this app hands out and the outbound it dials
     * cannot describe the same server differently.
     *
     * What stays here is what is genuinely about **this device dialling**, and has no meaning in a
     * config given to someone else: the unfiltering cipher swap below, and the `sockopt` pinning in
     * `generateConfig`.
     */
    private fun buildStream(config: VpnConfig, stage: com.mlmvpn.scanner.engines.cfnet.CfStage? = com.mlmvpn.scanner.engines.cfnet.CfStage.CONNECT): JSONObject {
        val stream = ConfigBuilder.buildStreamSettings(config.toConfigSpec())

        // «رفع فیلتر»: the fragment + fingerprint handshake, for Cloudflare-fronted TLS outbounds -- and
        // the link's own `cs` / `fm`, always. See [CfUnfilter] for what it sets and why the earlier
        // version of this switch (remove the fingerprint, set a cipher list) did nothing.
        CfUnfilter.shape(stream, config, tlsUnfilter)
        if (tlsUnfilter) com.mlmvpn.scanner.engines.cfnet.CfNetManager.current()?.apply(stream, config, stage)
        if (CfUnfilter.isLocalFront(config)) CfUnfilter.shapeForLocalFront(stream)
        return stream
    }

    /**
     * The VLESS user object, carrying `flow` when the link asks for one.
     *
     * `xtls-rprx-vision` is not decoration: a server configured for it rejects a user that arrives
     * without it. It was parsed out of the link and then dropped by all three builders below.
     */
    private fun vlessUser(config: VpnConfig): JSONObject = JSONObject().apply {
        put("id", config.uuid)
        put("encryption", "none")
        if (config.flow.isNotBlank()) put("flow", config.flow)
    }

    fun generateConfig(
        config: VpnConfig,
        localPort: Int,
        backendDns: String = "1.1.1.1",
        allowLan: Boolean = false,
        includeTun: Boolean = true,
        mtu: Int = 1280,
        useFragment: Boolean = false,
        gameMode: Boolean = false,
        warpHybrid: org.json.JSONObject? = null,
        dedicatedDnsUrl: String? = null,
        dedicatedDnsDomains: List<String> = emptyList(),
        pinnedHostIps: Map<String, List<String>> = emptyMap(),
        // These two are LAST on purpose. Several callers pass the leading arguments
        // positionally, so a parameter added anywhere above silently re-binds theirs --
        // which is exactly what happened when they were first written in beside allowLan.
        /**
         * Password for the shared proxy, or null for an open one.
         *
         * Applied to the `mixed` inbound, which authenticates SOCKS5 and HTTP from the one
         * `accounts` list -- so a client using either protocol is covered by a single value.
         * Ignored entirely unless [allowLan] is on: an authenticated loopback proxy would lock
         * the phone's own apps out of it for no gain.
         */
        lanPassword: String? = null,
        /**
         * Client addresses whose traffic is sent to the blackhole outbound.
         *
         * The blocklist is enforced in routing rather than at accept time because that is the
         * only layer that sees the source address: the inbound is one shared listener, so a
         * refused client and an allowed one arrive the same way.
         */
        lanBlocked: Set<String> = emptySet(),
        strategyStage: com.mlmvpn.scanner.engines.cfnet.CfStage? = com.mlmvpn.scanner.engines.cfnet.CfStage.CONNECT,
        preserveEndpoint: Boolean = false,
    ): String {
        val config = if (tlsUnfilter && !preserveEndpoint) com.mlmvpn.scanner.engines.cfnet.CfNetManager.current()?.configure(config, strategyStage) ?: config else config
        val json = JSONObject()

        // Log
        val log = JSONObject()
        // Was "info" while diagnosing MLM/xhttp lag, which its own comment said to revert once
        // done. At "info" the core writes several lines per connection through JNI -- a single
        // page load is 50-150 connections -- so this was a constant CPU/IO tax on every session
        // for diagnostics nobody reads in a release build. "warning" is also what upstream
        // v2rayNG and the Serverless configs default to.
        log.put("loglevel", "warning")
        json.put("log", log)

        // Inbounds
        val inbounds = JSONArray()
        val socksInbound = JSONObject().apply {
            put("port", localPort)
            put("listen", if (allowLan) "0.0.0.0" else "127.0.0.1")
            // "mixed" serves SOCKS *and* HTTP on the same port, so an app that only speaks HTTP
            // proxy can use the Local Port the user was given instead of needing to know about
            // the second port below. The tag stays "socks" because routing rules reference it.
            put("protocol", "mixed")
            put("tag", "socks")
            put("settings", JSONObject().apply {
                val password = lanPassword?.takeIf { allowLan && it.isNotBlank() }
                if (password == null) {
                    put("auth", "noauth")
                } else {
                    put("auth", "password")
                    put(
                        "accounts",
                        JSONArray().put(
                            JSONObject()
                                .put("user", "mlm")
                                .put("pass", password)
                        ),
                    )
                }
                put("udp", true)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().put("http").put("tls").put("fakedns"))
            })
        }
        if (includeTun) {
            val tunInbound = JSONObject().apply {
                put("protocol", "tun")
                put("tag", "tun2socks")
                put("settings", JSONObject().apply {
                    put("mtu", mtu)
                    put("autoRoute", false)
                    put("strictRoute", false)
                    put("endpoint", "10.0.0.2")
                    put("stack", "system")
                })
                put("sniffing", JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", JSONArray().put("http").put("tls").put("fakedns"))
                })
            }
            inbounds.put(tunInbound)
        }
        inbounds.put(socksInbound)
        // HTTP inbound at localPort+10000 so the app's status probe (RealIpCheck) and the
        // connected-country flag fetch have a proxy to reach the internet THROUGH the tunnel.
        // Without it those requests hit a refused 127.0.0.1:<port+10000> (log spam) and the
        // country flag above the connect button never appears -- the regression being fixed here.
        //
        // Kept even though the inbound above is now "mixed" and would serve HTTP on localPort
        // too: localPort+10000 is a convention shared with the GST engine (GstEngine listens
        // there itself), so the app's probes target it regardless of which engine is connected.
        // Dropping it here would only work for Xray sessions and break the GST ones.
        val httpInbound = JSONObject().apply {
            put("port", localPort + 10000)
            put("listen", "127.0.0.1")
            put("protocol", "http")
            put("tag", "http")
        }
        inbounds.put(httpInbound)
        json.put("inbounds", inbounds)

        // Outbounds
        val outbounds = JSONArray()
        val mainOutbound = JSONObject()
        
        if (config.protocol == "vless") {
            mainOutbound.put("protocol", "vless")
            val vnext = JSONArray().put(JSONObject().apply {
                put("address", config.address)
                put("port", config.port)
                put("users", JSONArray().put(vlessUser(config)))
            })
            mainOutbound.put("settings", JSONObject().put("vnext", vnext))
        } else if (config.protocol == "trojan") {
            mainOutbound.put("protocol", "trojan")
            val servers = JSONArray().put(JSONObject().apply {
                put("address", config.address)
                put("port", config.port)
                put("password", config.uuid)
            })
            mainOutbound.put("settings", JSONObject().put("servers", servers))
        } else if (config.protocol == "vmess") {
            mainOutbound.put("protocol", "vmess")
            val vnext = JSONArray().put(JSONObject().apply {
                put("address", config.address)
                put("port", config.port)
                put("users", JSONArray().put(JSONObject().apply {
                    put("id", config.uuid)
                    put("alterId", config.alterId)
                    put("security", config.vmessSecurity.ifBlank { "auto" })
                }))
            })
            mainOutbound.put("settings", JSONObject().put("vnext", vnext))
        } else if (config.protocol == "ss") {
            mainOutbound.put("protocol", "shadowsocks")
            val servers = JSONArray().put(JSONObject().apply {
                put("address", config.address)
                put("port", config.port)
                put("method", config.method)
                put("password", config.password)
            })
            mainOutbound.put("settings", JSONObject().put("servers", servers))
        }

        // Stream Settings -- built by the shared ConfigBuilder (see buildStream).
        val streamSettings = buildStream(config, strategyStage)

        // The server address was already resolved by DomainPreResolver and its IPs are pinned
        // into dns.hosts below, so tell the outbound to dial by IP ("ForceIP" resolves through
        // xray's own DNS, which now answers instantly from that hosts entry -- no lookup on the
        // connect path) and let happyEyeballs race the pinned addresses instead of walking them
        // one at a time. That racing is the point: a workers.dev domain resolves to many
        // Cloudflare edge IPs and some are throttled or dead from Iran at any moment, so trying
        // the first one serially is what turns into a multi-second stall or a failed connect.
        //
        // prioritizeIPv6 stays false and only A records are pinned: plenty of Iranian mobile
        // networks advertise broken IPv6, and preferring it there would trade one stall for
        // another.
        if (pinnedHostIps[config.address]?.isNotEmpty() == true) {
            streamSettings.put("sockopt", JSONObject().apply {
                put("domainStrategy", "ForceIP")
                put("happyEyeballs", JSONObject().apply {
                    put("tryDelayMs", 300)
                    put("prioritizeIPv6", false)
                    put("interleave", 2)
                    put("maxConcurrentTry", 6)
                })
            })
        }

        mainOutbound.put("streamSettings", streamSettings)
        mainOutbound.put("tag", "proxy")
        outbounds.put(mainOutbound)

        // Direct Outbound
        val directOutbound = JSONObject().apply {
            put("protocol", "freedom")
            put("tag", "direct")
        }
        outbounds.put(directOutbound)

        // Google's servers, handed to the worker by IPv4 address -- see googleByIpv4 for why that
        // is what makes Gemini open. Worker transports only: a real server has its own exit and
        // nothing to fix.
        val googleV4 = googleFix && config.network.lowercase() in WORKER_TRANSPORTS
        if (googleV4) outbounds.put(googleByIpv4(mainOutbound))
        // Gemini itself, through the user's exit in North America when one is set up.
        val geminiOut = if (googleV4) geminiExitOutbound(mainOutbound) else null
        if (geminiOut != null) outbounds.put(geminiOut)

        json.put("outbounds", outbounds)

        // FakeDNS Configuration
        val dns = JSONObject()
        val servers = JSONArray()
        val isXhttp = config.network == "xhttp"

        // Google's names: through the tunnel, IPv4 only. The lookup googleV4 depends on.
        if (googleV4) servers.put(googleDnsServer())

        // For xhttp (MLM / CF-worker) configs, resolve everything EXCEPT the worker's own host
        // through an encrypted DoH server that egresses via the proxy (see the remote-dns routing
        // rule below). This closes the DNS leak where real-domain lookups previously went out over
        // the user's real IP as plaintext UDP:53. Only xhttp is changed; ws/others are untouched.
        if (isXhttp) {
            servers.put(JSONObject().apply {
                put("address", "https://8.8.8.8/dns-query")
                put("tag", "remote-dns")
            })
        }

        val directDns = JSONObject()
        directDns.put("address", backendDns)
        val directDomains = JSONArray()
        val hosts = setOf(config.address, config.wsHost, config.sni).filter { it.isNotEmpty() }
        hosts.forEach { directDomains.put(it) }
        directDns.put("domains", directDomains)
        // Restrict the direct resolver to the worker host only so it can't serve (and leak)
        // general domains as a fallback — those must go through the DoH/proxy path.
        if (isXhttp) directDns.put("skipFallback", true)
        servers.put(directDns)

        servers.put("fakedns")

        dns.put("servers", servers)
        // Without a queryStrategy, xray asks for AAAA *and* A for every single domain, serially:
        // a live logcat capture showed every lookup costing two round trips, the first almost
        // always coming back "TypeAAAA -> []" followed by "empty response" and then a fresh A
        // query -- 150ms + 700ms for one hostname, over and over, on the app's own tun path.
        // "UseSystem" follows the device's own address-family preference, so on the IPv4-only
        // mobile networks most users are on the AAAA query disappears entirely. This exact value
        // is what the bundled Iran default configs already ship, so it is known to be accepted
        // by this core build (on a genuinely dual-stack network both families can still be
        // queried -- that is the correct behaviour there, not the waste being fixed here).
        dns.put("queryStrategy", "UseSystem")
        // Pin the pre-resolved server IPs. `hosts` is consulted before any entry in `servers`,
        // which also hardens the outbound against the fakedns entry above ever handing it a
        // 198.18.x.x fake address for its own server domain.
        if (pinnedHostIps.isNotEmpty()) {
            val hostsObj = JSONObject()
            pinnedHostIps.forEach { (host, ips) ->
                if (ips.isEmpty()) return@forEach
                // A single-element array is valid here, but xray accepts a bare string too and
                // that keeps the generated config easier to read in the log.
                hostsObj.put(host, if (ips.size == 1) ips[0] else JSONArray().apply { ips.forEach { put(it) } })
            }
            if (hostsObj.length() > 0) dns.put("hosts", hostsObj)
        }
        json.put("dns", dns)
        
        val fakedns = JSONObject()
        fakedns.put("ipPool", "198.18.0.0/15")
        fakedns.put("poolSize", 65535)
        json.put("fakedns", JSONArray().put(fakedns))

        // Routing
        val routing = JSONObject()
        routing.put("domainStrategy", "AsIs")
        
        val rules = JSONArray()
        // Blocked LAN clients, FIRST so nothing below can route them somewhere else. `source`
        // matches the address the connection came from, which for a shared proxy is the other
        // device -- the phone's own apps arrive from loopback and are never in this list.
        if (allowLan && lanBlocked.isNotEmpty()) {
            rules.put(JSONObject().apply {
                put("type", "field")
                put("inboundTag", JSONArray().put("socks"))
                put("source", JSONArray().apply { lanBlocked.forEach { put(it) } })
                put("outboundTag", "blocked")
            })
        }
        // Route user DNS queries to Xray's internal DNS
        rules.put(JSONObject().apply {
            put("type", "field")
            put("inboundTag", if (includeTun) JSONArray().put("tun2socks").put("socks") else JSONArray().put("socks"))
            put("port", 53)
            put("outboundTag", "dns-out")
        })
        // xhttp only: send the DoH resolver's own traffic through the proxy so real-domain
        // lookups are encrypted and tunneled (no DNS leak). DoH is HTTPS/443, so it never
        // matches the port-53 direct rule below.
        if (isXhttp) {
            rules.put(JSONObject().apply {
                put("type", "field")
                put("inboundTag", JSONArray().put("remote-dns"))
                put("outboundTag", "proxy")
            })
        }
        if (googleV4) rules.put(googleDnsRoute("proxy"))
        // Route Xray's internal DNS queries to direct to avoid UDP drops over proxy.
        // (For xhttp this now only matches the worker-host bootstrap resolver; the general
        // DoH resolver above is on 443 and goes through the proxy instead.)
        rules.put(JSONObject().apply {
            put("type", "field")
            put("port", 53)
            put("outboundTag", "direct")
        })

        // Iran's filtering infrastructure answers hijacked connections from these ranges (the
        // block page). A live capture showed that traffic being dutifully carried through the
        // tunnel -- paying full proxy latency to fetch a censorship notice. Blackholing it makes
        // the connection fail fast so the app retries instead, which is what upstream
        // Serverless v48 does too.
        rules.put(JSONObject().apply {
            put("type", "field")
            put("ip", JSONArray().put("10.10.34.0/24").put("2001:4188:2:600::/64"))
            put("outboundTag", "blocked")
        })

        // QUIC over a Cloudflare-worker VLESS tunnel cannot work: the worker carries no UDP, so
        // every attempt was tunneled to the worker, stalled, and died with
        // "websocket: close 1005" / "1006 abnormal closure" -- dozens of times in a single
        // minute of TikTok/Instagram use in the captured log. The app then retries over TCP
        // anyway, so the only thing those attempts bought was a few hundred milliseconds of
        // delay before every video. Rejecting QUIC outright makes clients fall straight through
        // to TCP, the same trade upstream Serverless v48 made when it dropped its UDP-noise
        // approach. Only UDP/443 and sniffed QUIC are refused -- game and voice traffic on other
        // UDP ports is untouched -- and it is skipped entirely for hybrid configs, whose whole
        // purpose is to carry UDP out through a WARP outbound instead.
        //
        // "Rejecting" was what this meant and not what `blocked` did. It is a blackhole: it answers
        // nothing, and Xray's TUN sends no ICMP for it either, so every client sat out its own
        // timer before trying TCP. Google's apps wait longest -- Gemini's never recovers -- which
        // is why they would not open on a panel config at all. With the switch on, QUIC goes to
        // the refusal outbound instead and is answered at once (see QuicRefuser); with it off, or
        // if the responder could not open, this is the old drop exactly.
        if (warpHybrid == null) {
            val quicSink = quicRefusal()?.let { refusal ->
                outbounds.put(refusal)
                addQuicRefusalPolicy(json)
                QUIC_REFUSAL_TAG
            } ?: "blocked"
            rules.put(JSONObject().apply {
                put("type", "field")
                put("network", "udp")
                put("protocol", JSONArray().put("quic"))
                put("outboundTag", quicSink)
            })
            rules.put(JSONObject().apply {
                put("type", "field")
                put("network", "udp")
                put("port", 443)
                put("outboundTag", quicSink)
            })
        }
        // Google, by IPv4 address. After the QUIC rules, so Google's QUIC is still refused rather
        // than carried, and after the block page, so a poisoned address still dies at once.
        if (geminiOut != null) rules.put(geminiRoute())
        if (googleV4) rules.put(googleRoute(googleRules()))
        routing.put("rules", rules)
        json.put("routing", routing)
        
        // Add DNS outbound
        val dnsOutbound = JSONObject().apply {
            put("protocol", "dns")
            put("tag", "dns-out")
        }
        outbounds.put(dnsOutbound)

        // Sink for the block rules above. Must exist or those rules would reference a missing
        // tag and xray would fall back to the default (first) outbound -- i.e. the proxy, which
        // is exactly what we are trying to stop sending this traffic to.
        outbounds.put(JSONObject().apply {
            put("protocol", "blackhole")
            put("tag", "blocked")
        })

        return json.toString()
    }

    /**
     * Anti-sanction split tunnel: ONLY [sanctionedDomains] egress through [config] (the user's
     * Cloudflare VLESS worker → clean CF IP → sanction bypassed); every other domain goes
     * `direct`. Uses plain SNI sniffing (NOT fakedns) so the direct leg resolves and connects
     * to real destinations normally — the whole point of a split tunnel.
     *
     * MyVpnService injects the `tun` inbound for VPN mode; here we ship the socks + http-probe
     * inbounds it expects. [sanctionedDomains] entries should be xray domain matchers
     * (e.g. "domain:openai.com").
     */
    fun generateAntiSanctionConfig(
        config: VpnConfig,
        localPort: Int,
        sanctionedDomains: List<String>,
        backendDns: String = "1.1.1.1",
        /**
         * Send everything in the tunnel to the worker, instead of only the listed domains.
         *
         * Set when the user has picked apps to un-sanction: MyVpnService then admits ONLY those
         * packages into the tunnel, so there is nothing in here that is not meant for the worker.
         * This is what makes app routing work where domain routing cannot -- it does not care
         * whether the app speaks QUIC, resolves through its own DoH, or pins its IPs.
         */
        proxyEverything: Boolean = false,
        mtu: Int = 1280
    ): String {
        val json = JSONObject()
        json.put("log", JSONObject().put("loglevel", "warning"))

        // Inbounds (tun injected later by MyVpnService, with its own sniffing that already
        // includes "fakedns" -- see the DNS section below for why routing does NOT rely on TLS
        // SNI sniffing alone: modern browsers increasingly encrypt the ClientHello (ECH), which
        // blinds SNI sniffing entirely and silently sends every "sanctioned" connection out
        // `direct` with the real Iranian IP -- exactly the site-doesn't-open symptom this was
        // built to avoid. destOverride still includes tls/http/quic as a fallback for domains
        // that aren't in the fakedns pool (e.g. the worker's own host).
        val inbounds = JSONArray()
        inbounds.put(JSONObject().apply {
            put("port", localPort)
            put("listen", "127.0.0.1")
            // "mixed" (SOCKS + HTTP on one port), same as the main config -- see generateConfig.
            put("protocol", "mixed")
            put("tag", "socks")
            put("settings", JSONObject().apply { put("auth", "noauth"); put("udp", true) })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().put("fakedns").put("http").put("tls").put("quic"))
                put("routeOnly", false)
            })
        })
        inbounds.put(JSONObject().apply {
            put("port", localPort + 10000)
            put("listen", "127.0.0.1")
            put("protocol", "http")
            put("tag", "http")
        })
        json.put("inbounds", inbounds)

        // Outbounds: proxy (worker), direct (freedom w/ built-in DNS resolution), dns.
        val outbounds = JSONArray()
        val proxy = JSONObject()
        proxy.put("protocol", "vless")
        proxy.put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().apply {
            put("address", config.address)
            put("port", config.port)
            put("users", JSONArray().put(vlessUser(config)))
        })))
        // Was a ws-only copy of generateConfig's block, so an xhttp config reached the
        // anti-sanction outbound with no xhttpSettings at all. See buildStream.
        proxy.put("streamSettings", buildStream(config))
        proxy.put("tag", "proxy")
        outbounds.put(proxy)
        outbounds.put(JSONObject().apply {
            put("protocol", "freedom")
            put("tag", "direct")
            put("settings", JSONObject().put("domainStrategy", "UseIP"))
        })
        outbounds.put(JSONObject().apply { put("protocol", "dns"); put("tag", "dns-out") })
        json.put("outbounds", outbounds)

        // Routing sanctioned domains used to rely entirely on TLS SNI sniffing (destOverride
        // tls/http/quic) to recognize them after the fact. That silently fails for any site
        // using Encrypted Client Hello (ECH) -- increasingly the default in Chrome -- since the
        // SNI is no longer visible in plaintext, so sniffing finds nothing, the connection falls
        // through to the final catch-all "direct" rule, and the sanctioned site loads (or fails
        // to load) over the user's real Iranian IP as if this feature were off. Fixed by
        // resolving sanctioned domains to a reserved "fake" IP pool via DNS instead: Xray
        // remembers which domain a fake IP came from and routes by that domain regardless of
        // what's (or isn't) visible in the TLS handshake -- immune to ECH.
        val dnsServers = JSONArray()
        if (sanctionedDomains.isNotEmpty()) {
            dnsServers.put(JSONObject().apply {
                put("address", "fakedns")
                put("domains", JSONArray().apply { sanctionedDomains.forEach { put(it) } })
            })
        }
        // Plain UDP:53 to backendDns (Cloudflare/Google resolvers) is commonly blocked or
        // throttled on Iranian ISPs -- since this feature only tunnels a handful of sanctioned
        // domains, that used to break DNS resolution for EVERYTHING while it was on, including
        // domains that were never meant to be touched (e.g. Gmail). Resolve via DoH (HTTPS/443,
        // which isn't blocked the way plain DNS is) instead, same fix already used for the main
        // VPN config's xhttp path; backendDns is kept only as a fallback if DoH itself is
        // unreachable.
        dnsServers.put(JSONObject().apply {
            put("address", "https://8.8.8.8/dns-query")
            put("tag", "remote-dns")
        })
        dnsServers.put(backendDns)
        json.put("dns", JSONObject().put("servers", dnsServers))

        if (sanctionedDomains.isNotEmpty()) {
            json.put("fakedns", JSONArray().put(JSONObject().apply {
                put("ipPool", "198.18.0.0/15")
                put("poolSize", 65535)
            }))
        }

        // Routing: DNS → dns-out; the DoH resolver's own HTTPS traffic → direct (it's just a
        // lookup, not sanctioned data); sanctioned domains → proxy; everything else → direct.
        val rules = JSONArray()
        rules.put(JSONObject().apply {
            put("type", "field"); put("port", 53); put("outboundTag", "dns-out")
        })
        rules.put(JSONObject().apply {
            put("type", "field")
            put("inboundTag", JSONArray().put("remote-dns"))
            put("outboundTag", "direct")
        })
        // The worker host itself must always be reached directly (never via itself).
        val workerHosts = setOf(config.address, config.wsHost, config.sni).filter { it.isNotEmpty() }
        if (workerHosts.isNotEmpty()) {
            rules.put(JSONObject().apply {
                put("type", "field")
                put("domain", JSONArray().apply { workerHosts.forEach { put("full:$it") } })
                put("outboundTag", "direct")
            })
        }
        // QUIC that would go to the worker, refused at once. The worker cannot carry it, and a
        // silent drop leaves the very apps this feature exists for -- Gemini first -- waiting out a
        // timer before they try TCP (see QuicRefuser). Only that QUIC: everything else here leaves
        // `direct`, where QUIC works and refusing it would only cost speed.
        val quicToWorker = when {
            proxyEverything -> listOf(JSONObject())
            sanctionedDomains.isNotEmpty() -> listOf(
                JSONObject().put("ip", JSONArray().put("198.18.0.0/15")),
                JSONObject().put("domain", JSONArray().apply { sanctionedDomains.forEach { put(it) } }),
            )
            else -> emptyList()
        }
        if (quicToWorker.isNotEmpty()) {
            quicRefusal()?.let { refusal ->
                outbounds.put(refusal)
                addQuicRefusalPolicy(json)
                quicToWorker.forEach { rule ->
                    rules.put(rule.apply {
                        put("type", "field")
                        put("network", "udp")
                        put("port", "443")
                        put("outboundTag", QUIC_REFUSAL_TAG)
                    })
                }
            }
        }
        // Google's sanctioned services -- Gemini, AI Studio and the rest -- handed to the worker by
        // IPv4 address. By name the worker leaves from its IPv6 exit, which Google will not serve
        // Gemini to; see googleByIpv4. Only the Google entries of the list, and ahead of the rules
        // below that would send them by name, so the split itself is exactly what it was.
        val googleTargets = when {
            !googleFix -> JSONArray()
            proxyEverything -> googleRules()
            else -> JSONArray().apply { sanctionedDomains.filter { isGoogleRule(it) }.forEach { put(it) } }
        }
        if (googleTargets.length() > 0) {
            outbounds.put(googleByIpv4(proxy))
            geminiExitOutbound(proxy)?.let { exit ->
                outbounds.put(exit)
                rules.put(geminiRoute())
            }
            rules.put(googleRoute(googleTargets))
        }
        if (sanctionedDomains.isNotEmpty()) {
            // Route by the fakedns pool's IP range directly, instead of relying on the
            // dispatcher reverse-mapping a fake IP back to its domain at routing time (traced
            // live: DNS resolution into the pool below was confirmed working via logcat --
            // 198.18.x.x fake IPs were being handed out correctly for sanctioned lookups -- but
            // routing still sent that traffic `direct`, so the domain-based rule alone isn't
            // reliably matching fakedns-resolved connections on this Xray build). Anything
            // resolved into this pool can only be a domain from [sanctionedDomains] (it's the
            // only thing scoped to use fakedns), so matching by IP is just as precise and does
            // not depend on that reverse-lookup step at all.
            rules.put(JSONObject().apply {
                put("type", "field")
                put("ip", JSONArray().put("198.18.0.0/15"))
                put("outboundTag", "proxy")
            })
            // Kept as a second line of defense for any sanctioned domain that gets sniffed via
            // SNI/HTTP host before it would otherwise fall through (e.g. non-ECH sites).
            rules.put(JSONObject().apply {
                put("type", "field")
                put("domain", JSONArray().apply { sanctionedDomains.forEach { put(it) } })
                put("outboundTag", "proxy")
            })
        }
        rules.put(JSONObject().apply {
            put("type", "field")
            put("network", "tcp,udp")
            put("outboundTag", if (proxyEverything) "proxy" else "direct")
        })
        // "AsIs" only ever matches by domain once one is known, and never falls back to the ip
        // rule above -- a live debug trace showed fakedns correctly resolving a sanctioned
        // domain into our pool AND Xray correctly reverse-mapping it back to that domain, but
        // the domain-based rule still not matching it (a quirk of this bundled Xray build), and
        // "AsIs" meant the ip-based rule never got a chance to catch it either. "IPIfNonMatch"
        // falls back to matching the destination address (here, the already-known fake IP --
        // no extra DNS work needed) whenever the domain rules don't hit.
        json.put("routing", JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch"); put("rules", rules)
        })

        return json.toString()
    }

    fun generateMultiConfig(configs: List<VpnConfig>, baseSocksPort: Int, backendDns: String = "1.1.1.1"): String {
        val json = JSONObject()
        val log = JSONObject().apply { put("loglevel", "warning") }
        json.put("log", log)

        val inbounds = JSONArray()
        val outbounds = JSONArray()
        val routingRules = JSONArray()
        val hosts = mutableSetOf<String>()

        configs.forEachIndexed { index, config ->
            val socksPort = baseSocksPort + index
            val tag = "proxy_$index"

            // Inbound
            inbounds.put(JSONObject().apply {
                put("port", socksPort)
                put("listen", "127.0.0.1")
                put("protocol", "socks")
                put("tag", "in_$tag")
                put("settings", JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", true)
                })
                put("sniffing", JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", JSONArray().put("http").put("tls").put("fakedns"))
                })
            })

            // Routing Rule
            routingRules.put(JSONObject().apply {
                put("type", "field")
                put("inboundTag", JSONArray().put("in_$tag"))
                put("outboundTag", tag)
            })

            // Outbound
            val outbound = JSONObject()
            if (config.protocol == "vless") {
                outbound.put("protocol", "vless")
                val vnext = JSONArray().put(JSONObject().apply {
                    put("address", config.address)
                    put("port", config.port)
                    put("users", JSONArray().put(vlessUser(config)))
                })
                outbound.put("settings", JSONObject().put("vnext", vnext))
            } else if (config.protocol == "trojan") {
                outbound.put("protocol", "trojan")
                val servers = JSONArray().put(JSONObject().apply {
                    put("address", config.address)
                    put("port", config.port)
                    put("password", config.uuid)
                })
                outbound.put("settings", JSONObject().put("servers", servers))
            } else if (config.protocol == "vmess") {
                outbound.put("protocol", "vmess")
                val vnext = JSONArray().put(JSONObject().apply {
                    put("address", config.address)
                    put("port", config.port)
                    put("users", JSONArray().put(JSONObject().apply {
                        put("id", config.uuid)
                        put("alterId", config.alterId)
                        put("security", config.vmessSecurity.ifBlank { "auto" })
                    }))
                })
                outbound.put("settings", JSONObject().put("vnext", vnext))
            } else if (config.protocol == "ss") {
                outbound.put("protocol", "shadowsocks")
                val servers = JSONArray().put(JSONObject().apply {
                    put("address", config.address)
                    put("port", config.port)
                    put("method", config.method)
                    put("password", config.password)
                })
                outbound.put("settings", JSONObject().put("servers", servers))
            }

            // Was a ws-only copy sending a two-token "Mozilla/5.0" user agent, so every config
            // measured through this path was measured with a different TLS/HTTP profile than the
            // one it would actually connect with -- and an xhttp config with no xhttpSettings at
            // all. See buildStream.
            outbound.put("streamSettings", buildStream(config))
            outbound.put("tag", tag)
            outbounds.put(outbound)

            hosts.addAll(listOf(config.address, config.wsHost, config.sni).filter { it.isNotEmpty() })
        }

        // Direct Outbound
        outbounds.put(JSONObject().apply {
            put("protocol", "freedom")
            put("tag", "direct")
        })

        json.put("inbounds", inbounds)
        json.put("outbounds", outbounds)

        // DNS
        val dns = JSONObject()
        val servers = JSONArray()
        val directDns = JSONObject()
        directDns.put("address", backendDns)
        val directDomains = JSONArray()
        hosts.forEach { directDomains.put(it) }
        directDns.put("domains", directDomains)
        servers.put(directDns)
        dns.put("servers", servers)
        json.put("dns", dns)

        val routing = JSONObject()
        routing.put("domainStrategy", "AsIs")
        routing.put("rules", routingRules)
        json.put("routing", routing)

        return json.toString()
    }

    /**
     * The game booster's DNS-only session: the VPN routes nothing but the resolver address
     * ([MyVpnService.GAME_ROUTE_DNS_ONLY]), so the only packets that ever arrive here are the
     * game's DNS queries -- its gameplay never enters this process.
     *
     * Answers come from [staticHosts] first (the login hosts the booster already resolved over
     * DoH and proved reachable), then from DNS over HTTPS -- never plain UDP 53, which Iranian
     * carriers intercept whatever the destination. `UseIPv4` because the session blocks IPv6
     * for the game, and an AAAA answer would only be a connection that fails slowly.
     *
     * TCP 853 is refused so Android's opportunistic Private DNS probe fails at once instead of
     * timing out; everything else leaves `direct` (the first outbound), which in practice is only
     * the DoH resolvers' own connections.
     *
     * [providerServers] -- (resolver address, names) pairs: an anti-sanction DNS the booster proved
     * on this line, asked ONLY for the game's refused names (`skipFallback`, so it never answers
     * anything else). [ispResolvers] -- the line's own resolvers, the default for every other name:
     * they are the fastest and map CDNs to the nearest edge, where DoH to a foreign resolver was
     * slow and mapped by the resolver's location. DoH by IP stays as the last resort. Without ISP
     * resolvers the order is the previous one: DoH first.
     */
    fun generateGameDnsOnlyConfig(
        mtu: Int,
        staticHosts: Map<String, List<String>> = emptyMap(),
        dedicatedDnsUrl: String? = null,
        dedicatedDnsDomains: List<String> = emptyList(),
        providerServers: List<Pair<String, List<String>>> = emptyList(),
        ispResolvers: List<String> = emptyList(),
        fragmentIps: List<String> = emptyList(),
    ): String {
        val json = JSONObject()
        json.put("log", JSONObject().put("loglevel", "warning"))
        json.put("inbounds", JSONArray().put(JSONObject().apply {
            put("protocol", "tun")
            put("tag", "tun-in")
            put("settings", JSONObject().put("mtu", mtu))
            // Nothing but DNS, and the fragmented hosts' TLS, arrives; there is nothing to sniff.
            put("sniffing", JSONObject().put("enabled", false))
        }))
        json.put("outbounds", JSONArray()
            .put(JSONObject().put("protocol", "freedom").put("tag", "direct"))
            .put(JSONObject().put("protocol", "dns").put("tag", "dns-out"))
            .put(JSONObject().put("protocol", "blackhole").put("tag", "block"))
            .apply { if (fragmentIps.isNotEmpty()) put(gameFragmentOutbound()) })

        val servers = JSONArray()
        if (!dedicatedDnsUrl.isNullOrBlank() && dedicatedDnsDomains.isNotEmpty()) {
            servers.put(JSONObject().apply {
                put("address", dedicatedDnsUrl)
                put("domains", JSONArray().apply { dedicatedDnsDomains.forEach { put("domain:$it") } })
                put("skipFallback", true)
            })
        }
        providerServers.forEach { (ip, names) ->
            if (names.isEmpty()) return@forEach
            servers.put(JSONObject().apply {
                put("address", ip)
                put("port", 53)
                // `full:` for a proven host, `domain:` for a catalog suffix given as ".suffix".
                put("domains", JSONArray().apply {
                    names.forEach { n -> put(if (n.startsWith(".")) "domain:${n.removePrefix(".")}" else "full:$n") }
                })
                put("skipFallback", true)
            })
        }
        ispResolvers.forEach { ip -> servers.put(JSONObject().put("address", ip).put("port", 53)) }
        // The IP-addressed forms: the named ones (and 1.1.1.1) are filtered on Iranian lines.
        servers.put("https://8.8.8.8/dns-query")
        servers.put("https://1.1.1.1/dns-query")
        json.put("dns", JSONObject().apply {
            put("servers", servers)
            put("queryStrategy", "UseIPv4")
            if (staticHosts.isNotEmpty()) {
                put("hosts", JSONObject().apply {
                    staticHosts.forEach { (host, ips) ->
                        if (ips.isNotEmpty()) put(host, if (ips.size == 1) ips[0] else JSONArray().apply { ips.forEach { put(it) } })
                    }
                })
            }
        })
        json.put("routing", JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("rules", JSONArray()
                .put(JSONObject().put("type", "field").put("port", "53").put("outboundTag", "dns-out"))
                .put(JSONObject().put("type", "field").put("network", "tcp").put("port", "853").put("outboundTag", "block"))
                .apply {
                    if (fragmentIps.isNotEmpty()) {
                        put(JSONObject().put("type", "field")
                            .put("ip", JSONArray().apply { fragmentIps.forEach { put(it) } })
                            .put("outboundTag", GAME_FRAGMENT_TAG))
                    }
                })
        })
        return json.toString()
    }

    const val GAME_FRAGMENT_TAG = "frag"

    /**
     * The fragmenting outbound for sign-in hosts filtered on their TLS name: the TLS ClientHello
     * leaves in pieces so the filter never sees the name in one segment. The shape and numbers are
     * the bundled Iran profiles' own (`tcp-fragment-tls`, Serverless v50 "fragA"), which is what the
     * app already ships as working in Iran; nothing is tuned here.
     */
    private fun gameFragmentOutbound(): JSONObject = JSONObject()
        .put("tag", GAME_FRAGMENT_TAG)
        .put("protocol", "freedom")
        .put("streamSettings", JSONObject()
            .put("finalmask", JSONObject().put("tcp", JSONArray()
                .put(JSONObject().put("type", "fragment").put("settings", JSONObject()
                    .put("packets", "tlshello")
                    .put("lengths", JSONArray().put("6").put("98").put("1"))
                    .put("delays", JSONArray().put("0"))
                    .put("maxSplit", "0")))
                .put(JSONObject().put("type", "fragment").put("settings", JSONObject()
                    .put("packets", "1-1")
                    .put("lengths", JSONArray().put("114").put("1"))
                    .put("delays", JSONArray().put("1"))
                    .put("maxSplit", "11"))))))

    /**
     * "DNS boost" config: a local tun VPN that ONLY changes DNS resolution and sends all
     * real traffic out direct (freedom) -- no proxy tunnel. Game hostnames can be resolved
     * via a dedicated DoH worker (dedicatedDnsUrl) and/or pinned to specific IPs (staticHosts),
     * with plain resolvers (dnsServers) plus 1.1.1.1 / 8.8.8.8 as fallbacks.
     */
    fun generateDnsBoostConfig(
        localPort: Int,
        dnsServers: List<String>,
        dedicatedDnsUrl: String? = null,
        dedicatedDnsDomains: List<String> = emptyList(),
        staticHosts: Map<String, List<String>> = emptyMap(),
        mtu: Int = 1280
    ): String {
        val json = JSONObject()
        json.put("log", JSONObject().put("loglevel", "warning"))

        // Inbounds: tun (captures device traffic) + a local socks for good measure.
        val inbounds = JSONArray()
        inbounds.put(JSONObject().apply {
            put("protocol", "tun")
            put("tag", "tun2socks")
            put("settings", JSONObject().apply {
                put("mtu", mtu)
                put("autoRoute", false)
                put("strictRoute", false)
                put("endpoint", "10.0.0.2")
                put("stack", "system")
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().put("http").put("tls").put("fakedns"))
            })
        })
        inbounds.put(JSONObject().apply {
            put("port", localPort)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("tag", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
            })
        })
        json.put("inbounds", inbounds)

        // Everything goes out direct; DNS queries are handled internally.
        val outbounds = JSONArray()
        outbounds.put(JSONObject().apply { put("protocol", "freedom"); put("tag", "direct") })
        outbounds.put(JSONObject().apply { put("protocol", "dns"); put("tag", "dns-out") })
        json.put("outbounds", outbounds)

        // DNS
        val dns = JSONObject()
        val servers = JSONArray()
        if (!dedicatedDnsUrl.isNullOrEmpty()) {
            servers.put(JSONObject().apply {
                put("address", dedicatedDnsUrl)
                if (dedicatedDnsDomains.isNotEmpty()) {
                    put("domains", JSONArray().apply { dedicatedDnsDomains.forEach { put(it) } })
                }
            })
        }
        dnsServers.forEach { servers.put(it) }
        // Fallback resolvers
        servers.put("1.1.1.1")
        servers.put("8.8.8.8")
        dns.put("servers", servers)
        if (staticHosts.isNotEmpty()) {
            val hostsObj = JSONObject()
            staticHosts.forEach { (host, ips) ->
                hostsObj.put(host, JSONArray().apply { ips.forEach { put(it) } })
            }
            dns.put("hosts", hostsObj)
        }
        json.put("dns", dns)

        // Routing: send DNS (port 53) to the dns outbound, everything else direct.
        val rules = JSONArray()
        rules.put(JSONObject().apply {
            put("type", "field")
            put("port", 53)
            put("outboundTag", "dns-out")
        })
        json.put("routing", JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("rules", rules)
        })
        return json.toString()
    }

    /**
     * Ports for throwaway test instances. The delay test spins up a fresh core per config
     * (a live capture showed 15 "Xray started" lines in 6 seconds during one bulk run), and
     * every one of them used to be handed the same hardcoded 10853 -- so concurrent tests were
     * all describing the same two local ports. Walk a private range instead, well clear of the
     * tunnel's own 10808/20808 and Aether's 20810.
     */
    private val testPortCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /** Lowest and highest port a throwaway test instance may take. */
    const val TEST_PORT_MIN = 31000
    const val TEST_PORT_MAX = 34999

    /**
     * Ports a test instance must not take, because something long-lived is on them.
     *
     * Set from the user's Local Port whenever a tunnel is started. The settings screen already
     * refuses a port that would land here, but the two defences are not redundant: a value
     * saved by an older build, or restored from a backup, was never validated at all, and a
     * silent port clash presents as "the delay test says every server is dead".
     */
    @Volatile
    private var reserved: Set<Int> = emptySet()

    fun reservePorts(localPort: Int) {
        reserved = setOf(localPort, localPort + 10000)
    }

    private fun nextTestPort(): Int {
        // At most one lap of the range; if every port in it were reserved (impossible with two
        // reservations) this still terminates.
        val span = TEST_PORT_MAX - TEST_PORT_MIN + 1
        repeat(span) {
            val port = TEST_PORT_MIN + (testPortCounter.getAndIncrement() % span)
            if (port !in reserved) return port
        }
        return TEST_PORT_MIN
    }

    /**
     * Minimal proxy-only config for native outbound-delay measurement
     * (libv2ray.measureOutboundDelay).
     *
     * Built by generating the normal config and then stripping it, rather than assembling a
     * second copy of the outbound/stream logic -- that logic is subtle (uTLS fingerprint, xhttp
     * packet-up forcing, ws host handling) and a divergent copy would silently make measured
     * delay stop describing the connection users actually get.
     *
     * What is stripped and why: measureOutboundDelay dials one URL through the outbound and does
     * not serve traffic, so DNS/fakedns/routing/the block sink and the second inbound are pure
     * setup cost paid once per config per run. The tell is that the captured log never showed a
     * "listening TCP" line for a test instance at all -- those inbounds were never even started.
     * Log level drops to warning too: a bulk run of hundreds of configs at "info" is a lot of
     * log for numbers nobody reads per-connection.
     *
     * [strategyStage] is which proven Cloudflare strategy the measurement dials with. CONNECT by
     * default: a delay shown next to a config must describe the connection that config will get.
     * The scanner passes SCAN; the Doctor's own probes pass null to measure the bare config.
     */
    fun generateSpeedtestConfig(config: VpnConfig, strategyStage: com.mlmvpn.scanner.engines.cfnet.CfStage? = com.mlmvpn.scanner.engines.cfnet.CfStage.CONNECT): String {
        val port = nextTestPort()
        val full = generateConfig(config, localPort = port, includeTun = false, strategyStage = strategyStage, preserveEndpoint = true)
        return try {
            val json = JSONObject(full)
            json.put("log", JSONObject().put("loglevel", "warning"))
            json.remove("dns")
            json.remove("fakedns")
            json.remove("routing")

            // Keep only the proxy outbound. With a single outbound and no routing section every
            // dial goes to it by default, which is exactly what a delay test wants to measure.
            json.optJSONArray("outbounds")?.let { outs ->
                val keep = JSONArray()
                for (i in 0 until outs.length()) {
                    val o = outs.optJSONObject(i) ?: continue
                    if (o.optString("tag") == "proxy") keep.put(o)
                }
                if (keep.length() > 0) json.put("outbounds", keep)
            }

            // Keep a single socks inbound so the config still parses as a complete one.
            json.optJSONArray("inbounds")?.let { ins ->
                val keep = JSONArray()
                for (i in 0 until ins.length()) {
                    val ib = ins.optJSONObject(i) ?: continue
                    if (ib.optString("tag") == "socks") { keep.put(ib); break }
                }
                if (keep.length() > 0) json.put("inbounds", keep)
            }

            json.toString()
        } catch (_: Exception) {
            // Any surgery failure falls back to the full config: slower to set up, but it is the
            // known-good shape, so a delay test never fails because of this trimming.
            full
        }
    }

    // Cloudflare WARP well-known peer public key.
    private const val WARP_PUBLIC_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuMdxHkJfChtBg="

    /**
     * One Xray config that tests many WARP endpoints in parallel: for each endpoint
     * a dedicated SOCKS inbound (baseSocksPort + index) routed to its own WireGuard
     * outbound, using a WARP account for the interface keys.
     */
    fun generateMultiWireguardConfig(
        accounts: List<com.mlmvpn.core.warp.WarpAccountManager.WarpAccountData>,
        endpoints: List<String>,
        baseSocksPort: Int,
        mtu: Int = 1280
    ): String {
        val json = JSONObject()
        json.put("log", JSONObject().put("loglevel", "warning"))

        val inbounds = JSONArray()
        val outbounds = JSONArray()
        val rules = JSONArray()

        endpoints.forEachIndexed { index, endpoint ->
            if (accounts.isEmpty()) return@forEachIndexed
            val account = accounts[index % accounts.size]
            val socksPort = baseSocksPort + index
            val tag = "wg_$index"

            inbounds.put(JSONObject().apply {
                put("port", socksPort)
                put("listen", "127.0.0.1")
                put("protocol", "socks")
                put("tag", "in_$tag")
                put("settings", JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", true)
                })
            })

            rules.put(JSONObject().apply {
                put("type", "field")
                put("inboundTag", JSONArray().put("in_$tag"))
                put("outboundTag", tag)
            })

            outbounds.put(JSONObject().apply {
                put("protocol", "wireguard")
                put("tag", tag)
                put("settings", JSONObject().apply {
                    put("secretKey", account.privateKey)
                    put("address", JSONArray().put("${account.ipv4}/32").put("${account.ipv6}/128"))
                    put("mtu", mtu)
                    put("reserved", JSONArray().apply { account.reserved.forEach { put(it) } })
                    put("peers", JSONArray().put(JSONObject().apply {
                        put("publicKey", WARP_PUBLIC_KEY)
                        put("endpoint", endpoint)
                        put("allowedIPs", JSONArray().put("0.0.0.0/0").put("::/0"))
                    }))
                })
            })
        }

        outbounds.put(JSONObject().apply { put("protocol", "freedom"); put("tag", "direct") })

        json.put("inbounds", inbounds)
        json.put("outbounds", outbounds)
        json.put("routing", JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("rules", rules)
        })
        return json.toString()
    }
}
