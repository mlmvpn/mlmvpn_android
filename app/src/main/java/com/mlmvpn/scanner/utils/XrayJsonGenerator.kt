package com.mlmvpn.scanner.utils

import org.json.JSONArray
import org.json.JSONObject

object XrayJsonGenerator {
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
        pinnedHostIps: Map<String, List<String>> = emptyMap()
    ): String {
        val json = JSONObject()

        // Log
        val log = JSONObject()
        // "info" surfaces per-connection dialing / connection-opened / RTT lines in GoLog
        // (needed to diagnose MLM/xhttp lag); "warning" hid them. Drop back to "warning"
        // once diagnosis is done if the extra log volume is a concern.
        log.put("loglevel", "info")
        json.put("log", log)

        // Inbounds
        val inbounds = JSONArray()
        val socksInbound = JSONObject().apply {
            put("port", localPort)
            put("listen", if (allowLan) "0.0.0.0" else "127.0.0.1")
            put("protocol", "socks")
            put("tag", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
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
                put("users", JSONArray().put(JSONObject().apply {
                    put("id", config.uuid)
                    put("encryption", "none")
                }))
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
        }

        // Stream Settings
        val streamSettings = JSONObject()
        streamSettings.put("network", config.network)
        if (config.tls.isNotEmpty() && config.tls != "none") {
            streamSettings.put("security", config.tls)
            val tlsSettings = JSONObject()
            tlsSettings.put("serverName", if (config.sni.isNotEmpty()) config.sni else config.wsHost)
            tlsSettings.put("fingerprint", "chrome") // ALWAYS KEEP CHROME FINGERPRINT FOR uTLS
            
            if (config.alpn.isNotEmpty()) {
                val alpnArr = JSONArray()
                config.alpn.split(",").forEach { alpnArr.put(it) }
                tlsSettings.put("alpn", alpnArr)
            }
            streamSettings.put("tlsSettings", tlsSettings)
        }

        if (config.network == "ws") {
            val wsSettings = JSONObject()
            wsSettings.put("path", if (config.wsPath.isNotEmpty()) config.wsPath else "/")
            val headers = JSONObject()
            // Host goes in the independent "host" field only. The core logs a deprecation warning
            // for a "Host" entry inside "headers" ("will be removed soon and being migrated to
            // independent host"), and setting both means the same value in two places, one of
            // which is on its way out.
            if (config.wsHost.isNotEmpty()) {
                wsSettings.put("host", config.wsHost)
            }
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            wsSettings.put("headers", headers)
            streamSettings.put("wsSettings", wsSettings)
        }

        if (config.network == "xhttp") {
            val xhttpSettings = JSONObject()
            xhttpSettings.put("host", if (config.xhttpHost.isNotEmpty()) config.xhttpHost else config.wsHost)
            xhttpSettings.put("path", if (config.xhttpPath.isNotEmpty()) config.xhttpPath else "/")
            // Apply the packet-up tuning carried in the URI's ?extra={...}. The app previously
            // dropped this entirely, so packet-up ran with conservative defaults. On xray 26.x the
            // tuning object lives under the "extra" key of xhttpSettings, so pass it through as-is.
            if (config.xhttpExtra.isNotEmpty()) {
                try { xhttpSettings.put("extra", JSONObject(config.xhttpExtra)) } catch (e: Exception) { }
            }
            // Cloudflare buffers request bodies, so stream-up/stream-one hang (TLS handshake
            // timeout to the destination). packet-up is the only reliable mode on CF Workers.
            // Force it after the extra-merge so an "auto"/"mode" carried in extra can't override.
            val mode = if (config.xhttpMode.isNotEmpty() && config.xhttpMode != "auto") config.xhttpMode else "packet-up"
            xhttpSettings.put("mode", mode)
            streamSettings.put("xhttpSettings", xhttpSettings)
        }

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
        
        json.put("outbounds", outbounds)

        // FakeDNS Configuration
        val dns = JSONObject()
        val servers = JSONArray()
        val isXhttp = config.network == "xhttp"

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
        if (warpHybrid == null) {
            rules.put(JSONObject().apply {
                put("type", "field")
                put("network", "udp")
                put("protocol", JSONArray().put("quic"))
                put("outboundTag", "blocked")
            })
            rules.put(JSONObject().apply {
                put("type", "field")
                put("network", "udp")
                put("port", 443)
                put("outboundTag", "blocked")
            })
        }
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
            put("protocol", "socks")
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
            put("users", JSONArray().put(JSONObject().apply {
                put("id", config.uuid); put("encryption", "none")
            }))
        })))
        val stream = JSONObject()
        stream.put("network", config.network)
        if (config.tls.isNotEmpty() && config.tls != "none") {
            stream.put("security", config.tls)
            stream.put("tlsSettings", JSONObject().apply {
                put("serverName", if (config.sni.isNotEmpty()) config.sni else config.wsHost)
                put("fingerprint", "chrome")
                if (config.alpn.isNotEmpty()) put("alpn", JSONArray().apply { config.alpn.split(",").forEach { put(it) } })
            })
        }
        if (config.network == "ws") {
            stream.put("wsSettings", JSONObject().apply {
                put("path", if (config.wsPath.isNotEmpty()) config.wsPath else "/")
                val headers = JSONObject()
                // "host" only -- a "Host" header entry is the deprecated spelling (see generateConfig).
                if (config.wsHost.isNotEmpty()) put("host", config.wsHost)
                headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                put("headers", headers)
            })
        }
        proxy.put("streamSettings", stream)
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
            put("type", "field"); put("network", "tcp,udp"); put("outboundTag", "direct")
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
                    put("users", JSONArray().put(JSONObject().apply {
                        put("id", config.uuid)
                        put("encryption", "none")
                    }))
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
            }

            // Stream Settings
            val streamSettings = JSONObject()
            streamSettings.put("network", config.network)
            if (config.tls.isNotEmpty() && config.tls != "none") {
                streamSettings.put("security", config.tls)
                val tlsSettings = JSONObject()
                tlsSettings.put("serverName", if (config.sni.isNotEmpty()) config.sni else config.wsHost)
                tlsSettings.put("fingerprint", "chrome")
                
                if (config.alpn.isNotEmpty()) {
                    val alpnArr = JSONArray()
                    config.alpn.split(",").forEach { alpnArr.put(it) }
                    tlsSettings.put("alpn", alpnArr)
                }
                streamSettings.put("tlsSettings", tlsSettings)
            }

            if (config.network == "ws") {
                val wsSettings = JSONObject()
                wsSettings.put("path", if (config.wsPath.isNotEmpty()) config.wsPath else "/")
                val headers = JSONObject()
                // "host" only -- a "Host" header entry is the deprecated spelling (see generateConfig).
                if (config.wsHost.isNotEmpty()) {
                    wsSettings.put("host", config.wsHost)
                }
                headers.put("User-Agent", "Mozilla/5.0")
                wsSettings.put("headers", headers)
                streamSettings.put("wsSettings", wsSettings)
            }
            
            outbound.put("streamSettings", streamSettings)
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
     */
    fun generateSpeedtestConfig(config: VpnConfig): String {
        val port = 31000 + (testPortCounter.getAndIncrement() % 4000)
        val full = generateConfig(config, localPort = port, includeTun = false)
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

