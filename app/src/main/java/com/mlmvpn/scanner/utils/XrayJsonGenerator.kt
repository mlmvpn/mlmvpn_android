package com.mlmvpn.scanner.utils

import org.json.JSONArray
import org.json.JSONObject

object XrayJsonGenerator {
    fun generateSpeedtestConfig(config: VpnConfig): String {
        val json = JSONObject()
        val log = JSONObject().apply { put("loglevel", "warning") }
        json.put("log", log)

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

        val streamSettings = JSONObject()
        streamSettings.put("network", config.network)
        if (config.tls.isNotEmpty() && config.tls != "none") {
            streamSettings.put("security", config.tls)
            val tlsSettings = JSONObject()
            tlsSettings.put("serverName", if (config.sni.isNotEmpty()) config.sni else config.wsHost)
            if (config.fingerprint.isNotEmpty()) {
                tlsSettings.put("fingerprint", config.fingerprint)
            } else {
                tlsSettings.put("fingerprint", "chrome")
            }
            
            if (config.alpn.isNotEmpty()) {
                val alpnArr = JSONArray()
                config.alpn.split(",").forEach { alpnArr.put(it) }
                tlsSettings.put("alpn", alpnArr)
            } else {
                val alpnArr = JSONArray()
                if (config.network == "ws" || config.network == "h2" || config.network == "http") {
                    alpnArr.put("http/1.1")
                } else if (config.network == "grpc") {
                    alpnArr.put("h2")
                } else {
                    alpnArr.put("h2").put("http/1.1")
                }
                tlsSettings.put("alpn", alpnArr)
            }
            streamSettings.put("tlsSettings", tlsSettings)
        }

        if (config.network == "ws") {
            val wsSettings = JSONObject()
            wsSettings.put("path", if (config.wsPath.isNotEmpty()) config.wsPath else "/")
            val headers = JSONObject()
            if (config.wsHost.isNotEmpty()) {
                wsSettings.put("host", config.wsHost)
                headers.put("Host", config.wsHost)
            }
            wsSettings.put("headers", headers)
            streamSettings.put("wsSettings", wsSettings)
        }
        
        if (config.network == "xhttp") {
            val xhttpSettings = JSONObject().apply {
                put("path", if (config.xhttpPath.isNotEmpty()) config.xhttpPath else "/")
                put("host", if (config.xhttpHost.isNotEmpty()) config.xhttpHost else config.sni)
                put("mode", if (config.xhttpMode.isNotEmpty()) config.xhttpMode else "auto")
                if (config.xhttpExtra.isNotEmpty()) {
                    try {
                        val extraObj = JSONObject(config.xhttpExtra)
                        val keys = extraObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            put(key, extraObj.get(key))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            streamSettings.put("xhttpSettings", xhttpSettings)
        }
        
        mainOutbound.put("streamSettings", streamSettings)
        mainOutbound.put("tag", "proxy")
        outbounds.put(mainOutbound)
        
        json.put("outbounds", outbounds)
        return json.toString()
    }

    fun generateConfig(config: VpnConfig, localPort: Int, backendDns: String = "1.1.1.1", allowLan: Boolean = false, includeTun: Boolean = true, mtu: Int = 1280, useFragment: Boolean = false, gameMode: Boolean = false): String {
        val json = JSONObject()
        
        // Stats and Policy for v2rayNG core compatibility
        json.put("stats", JSONObject())
        json.put("policy", JSONObject().apply {
            put("levels", JSONObject().apply {
                put("8", JSONObject().apply {
                    put("handshake", 4)
                    put("connIdle", 300)
                    put("uplinkOnly", 1)
                    put("downlinkOnly", 1)
                })
            })
            put("system", JSONObject().apply {
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            })
        })

        // Log
        val log = JSONObject()
        log.put("loglevel", "warning")
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
                put("userLevel", 8)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                val destOverride = JSONArray().put("http").put("tls").put("quic")
                if (includeTun) destOverride.put("fakedns")
                put("destOverride", destOverride)
            })
        }
        if (includeTun) {
            val tunInbound = JSONObject().apply {
                put("protocol", "tun")
                put("tag", "tun")
                put("settings", JSONObject().apply {
                    put("name", "xray0")
                    put("MTU", mtu)
                    put("userLevel", 8)
                })
                put("sniffing", JSONObject().apply {
                    put("enabled", true)
                    val destOverride = JSONArray().put("http").put("tls").put("quic")
                    if (includeTun) destOverride.put("fakedns")
                    put("destOverride", destOverride)
                })
            }
            inbounds.put(tunInbound)
        }
        inbounds.put(socksInbound)

        // Add HTTP inbound for OkHttp to pass DNS resolution to proxy
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
            if (config.fingerprint.isNotEmpty()) {
                tlsSettings.put("fingerprint", config.fingerprint)
            } else {
                tlsSettings.put("fingerprint", "chrome")
            }
            
            if (config.alpn.isNotEmpty()) {
                val alpnArr = JSONArray()
                config.alpn.split(",").forEach { alpnArr.put(it) }
                tlsSettings.put("alpn", alpnArr)
            } else {
                val alpnArr = JSONArray()
                if (config.network == "ws" || config.network == "h2" || config.network == "http") {
                    alpnArr.put("http/1.1")
                } else if (config.network == "grpc") {
                    alpnArr.put("h2")
                } else {
                    alpnArr.put("h2").put("http/1.1")
                }
                tlsSettings.put("alpn", alpnArr)
            }
            streamSettings.put("tlsSettings", tlsSettings)
        }

        if (config.network == "ws") {
            val wsSettings = JSONObject()
            wsSettings.put("path", if (config.wsPath.isNotEmpty()) config.wsPath else "/")
            val headers = JSONObject()
            if (config.wsHost.isNotEmpty()) {
                wsSettings.put("host", config.wsHost)
                headers.put("Host", config.wsHost)
            }
            wsSettings.put("headers", headers)
            streamSettings.put("wsSettings", wsSettings)
        }
        
        if (config.network == "xhttp") {
            val xhttpSettings = JSONObject().apply {
                put("path", if (config.xhttpPath.isNotEmpty()) config.xhttpPath else "/")
                put("host", if (config.xhttpHost.isNotEmpty()) config.xhttpHost else config.sni)
                put("mode", if (config.xhttpMode.isNotEmpty()) config.xhttpMode else "auto")
                if (config.xhttpExtra.isNotEmpty()) {
                    try {
                        val extraObj = JSONObject(config.xhttpExtra)
                        val keys = extraObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            put(key, extraObj.get(key))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            streamSettings.put("xhttpSettings", xhttpSettings)
        }
        
        if (useFragment) {
            val sockopt = JSONObject().apply {
                put("dialerProxy", "fragment-out")
            }
            streamSettings.put("sockopt", sockopt)
        }
        
        mainOutbound.put("streamSettings", streamSettings)
        mainOutbound.put("tag", "proxy")
        outbounds.put(mainOutbound)
        
        if (useFragment) {
            outbounds.put(JSONObject().apply {
                put("tag", "fragment-out")
                put("protocol", "freedom")
                put("settings", JSONObject().apply {
                    put("fragment", JSONObject().apply {
                        put("packets", "tlshello")
                        put("length", "100-200")
                        put("interval", "10-20")
                    })
                })
            })
        }

        // Direct Outbound
        val directOutbound = JSONObject().apply {
            put("protocol", "freedom")
            put("tag", "direct")
            put("streamSettings", JSONObject().apply {
                put("sockopt", JSONObject().apply {
                    put("domainStrategy", "UseIP")
                })
            })
        }
        outbounds.put(directOutbound)
        
        // Block Outbound
        val blockOutbound = JSONObject().apply {
            put("protocol", "blackhole")
            put("tag", "block")
            put("settings", JSONObject().apply {
                put("response", JSONObject().apply {
                    put("type", "http")
                })
            })
        }
        outbounds.put(blockOutbound)
        
        json.put("outbounds", outbounds)

        // FakeDNS Configuration
        val dns = JSONObject()
        val servers = JSONArray()
        
        // 1. Direct DNS for Server IPs/Hosts
        val directDns = JSONObject()
        directDns.put("address", backendDns)
        val directDomains = JSONArray()
        val hosts = setOf(config.address, config.wsHost, config.sni).filter { 
            it.isNotEmpty() && !it.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) 
        }
        hosts.forEach { directDomains.put(it) }
        directDns.put("domains", directDomains)
        directDns.put("skipFallback", true)
        directDns.put("tag", "direct-dns-out")
        servers.put(directDns)

        // 2. FakeDNS Server
        if (includeTun) {
            servers.put(JSONObject().apply {
                put("address", "fakedns")
            })
        }
        
        // 3. DoH server with a dedicated tag — routed through the tunnel
        servers.put(JSONObject().apply {
            put("address", "https://8.8.8.8/dns-query")
            put("tag", "doh-dns-out")
        })
        
        // 4. Fallback DNS
        servers.put("localhost")
        
        dns.put("servers", servers)
        dns.put("queryStrategy", "UseIPv4")
        json.put("dns", dns)
        
        if (includeTun) {
            // FakeDNS Root Object
            val fakednsArray = JSONArray()
            fakednsArray.put(JSONObject().apply {
                put("ipPool", "198.18.0.0/15")
                put("poolSize", 65535)
            })
            json.put("fakedns", fakednsArray)
        }


        // Routing
        val routing = JSONObject()
        routing.put("domainStrategy", "AsIs")
        
        val rules = JSONArray()
        
        // Route Direct DNS explicitly to direct
        rules.put(JSONObject().apply {
            put("type", "field")
            put("inboundTag", JSONArray().put("direct-dns-out"))
            put("outboundTag", "direct")
        })
        
        // Route DoH traffic through proxy
        rules.put(JSONObject().apply {
            put("type", "field")
            put("inboundTag", JSONArray().put("doh-dns-out"))
            put("outboundTag", "proxy")
        })

        // 1. Route user DNS queries to Xray's internal DNS
        rules.put(JSONObject().apply {
            put("type", "field")
            put("inboundTag", if (includeTun) JSONArray().put("tun").put("socks") else JSONArray().put("socks"))
            put("port", 53)
            put("outboundTag", "dns-out")
        })
        
        // Block QUIC (UDP/443) to force fallback to TCP
        rules.put(JSONObject().apply {
            put("type", "field")
            put("network", "udp")
            put("port", 443)
            put("outboundTag", "block")
        })
        
        val isWorker = config.wsHost.contains("workers.dev", ignoreCase = true) || 
                       config.sni.contains("workers.dev", ignoreCase = true) || 
                       config.wsHost.contains("pages.dev", ignoreCase = true) || 
                       config.sni.contains("pages.dev", ignoreCase = true)
        
        // Route UDP to direct ONLY if not in game mode OR if the proxy is a worker (which can't proxy UDP)
        if (!gameMode || isWorker) {
            rules.put(JSONObject().apply {
                put("type", "field")
                put("network", "udp")
                put("outboundTag", "direct")
            })
        } else {
            // In game mode, we must ensure UDP (except QUIC/DNS) goes to proxy
            rules.put(JSONObject().apply {
                put("type", "field")
                put("network", "udp")
                put("outboundTag", "proxy")
            })
        }
        
        val directIps = JSONArray().put("geoip:private")
        if (backendDns.isNotEmpty() && backendDns != "8.8.8.8" && backendDns != "1.1.1.1") {
            // Only add custom backend DNS to direct if strictly necessary
            // directIps.put(backendDns) 
        }
        if (config.address.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))) {
            directIps.put(config.address)
        }
        rules.put(JSONObject().apply {
            put("type", "field")
            put("ip", directIps)
            put("outboundTag", "direct")
        })
        
        routing.put("rules", rules)
        json.put("routing", routing)
        
        // Add DNS outbound
        val dnsOutbound = JSONObject().apply {
            put("protocol", "dns")
            put("tag", "dns-out")
        }
        outbounds.put(dnsOutbound)

        return json.toString()
    }

    fun generateMultiConfig(configs: List<VpnConfig>, baseSocksPort: Int, backendDns: String = "1.1.1.1", useFragment: Boolean = false): String {
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
                    put("destOverride", JSONArray().put("http").put("tls").put("quic"))
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
                if (config.fingerprint.isNotEmpty()) {
                    tlsSettings.put("fingerprint", config.fingerprint)
                }
                
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
                if (config.wsHost.isNotEmpty()) {
                    wsSettings.put("host", config.wsHost)
                }
                streamSettings.put("wsSettings", wsSettings)
            }
            
            if (config.network == "xhttp") {
                val xhttpSettings = JSONObject().apply {
                    put("path", if (config.xhttpPath.isNotEmpty()) config.xhttpPath else "/")
                    put("host", if (config.xhttpHost.isNotEmpty()) config.xhttpHost else config.sni)
                    put("mode", if (config.xhttpMode.isNotEmpty()) config.xhttpMode else "auto")
                    if (config.xhttpExtra.isNotEmpty()) {
                        try {
                            val extraObj = JSONObject(config.xhttpExtra)
                            val keys = extraObj.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                put(key, extraObj.get(key))
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                streamSettings.put("xhttpSettings", xhttpSettings)
            }
            
            if (useFragment) {
                val sockopt = JSONObject().apply {
                    put("dialerProxy", "fragment-out")
                }
                streamSettings.put("sockopt", sockopt)
            }
            
            outbound.put("streamSettings", streamSettings)
            outbound.put("tag", tag)
            outbounds.put(outbound)

            hosts.addAll(listOf(config.address, config.wsHost, config.sni).filter { 
                it.isNotEmpty() && !it.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) 
            })
        }
        
        if (useFragment) {
            outbounds.put(JSONObject().apply {
                put("tag", "fragment-out")
                put("protocol", "freedom")
                put("settings", JSONObject().apply {
                    put("fragment", JSONObject().apply {
                        put("packets", "tlshello")
                        put("length", "100-200")
                        put("interval", "10-20")
                    })
                })
            })
        }

        // Direct Outbound
        outbounds.put(JSONObject().apply {
            put("protocol", "freedom")
            put("tag", "direct")
        })
        
        // Block Outbound
        outbounds.put(JSONObject().apply {
            put("protocol", "blackhole")
            put("tag", "block")
        })
        
        // DNS Outbound
        outbounds.put(JSONObject().apply {
            put("protocol", "dns")
            put("tag", "dns-out")
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
        
        val directIps = JSONArray().put("geoip:private")
        if (backendDns.isNotEmpty() && backendDns != "8.8.8.8" && backendDns != "1.1.1.1") {
            directIps.put(backendDns)
        }
        hosts.forEach {
            if (it.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))) {
                directIps.put(it)
            }
        }
        routingRules.put(JSONObject().apply {
            put("type", "field")
            put("ip", directIps)
            put("outboundTag", "direct")
        })
        
        routing.put("rules", routingRules)
        json.put("routing", routing)

        return json.toString()
    }

    fun generateWarpWireguardConfig(
        privateKey: String,
        localAddress: String,
        endpointIp: String,
        endpointPort: Int = 2408,
        localPort: Int = 10808,
        includeTun: Boolean = true
    ): String {
        val json = JSONObject()
        json.put("log", JSONObject().apply { put("loglevel", "warning") })

        val inbounds = JSONArray()
        if (includeTun) {
            inbounds.put(JSONObject().apply {
                put("port", localPort)
                put("protocol", "tun")
                put("settings", JSONObject().apply {
                    put("mtu", 1500)
                    put("autoRoute", true)
                    put("strictRoute", true)
                    put("endpointIndependentNat", true)
                    put("stack", "system")
                })
                put("tag", "tun-in")
            })
        } else {
            inbounds.put(JSONObject().apply {
                put("port", localPort)
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", true)
                })
                put("tag", "socks-in")
            })
        }
        json.put("inbounds", inbounds)

        val outbounds = JSONArray()
        outbounds.put(JSONObject().apply {
            put("protocol", "wireguard")
            put("settings", JSONObject().apply {
                put("secretKey", privateKey)
                put("address", JSONArray().apply { put(localAddress) })
                put("peers", JSONArray().apply {
                    put(JSONObject().apply {
                        put("publicKey", "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=")
                        put("endpoint", "$endpointIp:$endpointPort")
                    })
                })
                put("mtu", 1280)
            })
            put("tag", "proxy")
        })

        outbounds.put(JSONObject().apply {
            put("protocol", "freedom")
            put("settings", JSONObject())
            put("tag", "direct")
        })
        json.put("outbounds", outbounds)

        val routing = JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "proxy")
                    put("network", "tcp,udp")
                })
            })
        }
        json.put("routing", routing)

        return json.toString(2)
    }
}
