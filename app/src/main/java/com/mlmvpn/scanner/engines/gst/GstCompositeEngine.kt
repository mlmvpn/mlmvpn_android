package com.mlmvpn.scanner.engines.gst

import android.content.Context
import android.util.Log
import com.mlmvpn.core.warp.IVpnEngine
import com.mlmvpn.core.warp.VlessXrayInjector
import org.json.JSONArray
import org.json.JSONObject

class GstCompositeEngine(private val vpnFd: Int) : IVpnEngine {
    private val gstEngine = GstEngine()
    private val xrayInjector = VlessXrayInjector(vpnFd)
    private var isRunning = false

    override suspend fun start(context: Context, config: String, localPort: Int): Boolean {
        Log.d("GstCompositeEngine", "Starting composite engine...")
        isRunning = true
        
        // 1. Start GST Engine (binds to e.g. 10809)
        val gstSocksPort = 10809
        val gstStarted = gstEngine.start(context, config, gstSocksPort)
        if (!gstStarted) {
            Log.e("GstCompositeEngine", "GST Engine failed to start")
            return false
        }
        
        // 2. Generate Xray config that acts as tun2socks for GST
        val jsonConfig = generateGstXrayConfig(localPort, gstSocksPort)
        
        // 3. Start Xray
        val xrayStarted = xrayInjector.start(context, jsonConfig, localPort)
        if (!xrayStarted) {
            Log.e("GstCompositeEngine", "Xray tun2socks failed to start")
            gstEngine.stop()
            return false
        }
        
        return true
    }

    override fun stop() {
        Log.d("GstCompositeEngine", "Stopping composite engine...")
        isRunning = false
        xrayInjector.stop()
        gstEngine.stop()
    }

    private fun generateGstXrayConfig(inboundSocksPort: Int, outboundSocksPort: Int): String {
        val json = JSONObject()
        
        // Log
        json.put("log", JSONObject().apply {
            put("access", "none")
            put("error", "none")
            put("loglevel", "error")
        })

        // Inbounds: TUN + Local SOCKS
        val inbounds = JSONArray()
        inbounds.put(JSONObject().apply {
            put("protocol", "tun")
            put("tag", "tun2socks")
            put("settings", JSONObject().apply {
                put("mtu", 1280)
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
            put("port", inboundSocksPort)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("tag", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
            })
        })
        // HTTP inbound at localPort+10000 so the app's RealIpCheck status probe (and any
        // HTTP-proxy consumers) have a listener, matching the other engines' port scheme.
        inbounds.put(JSONObject().apply {
            put("port", inboundSocksPort + 10000)
            put("listen", "127.0.0.1")
            put("protocol", "http")
            put("tag", "http")
        })
        json.put("inbounds", inbounds)

        // Outbounds: SOCKS to GST
        val outbounds = JSONArray()
        outbounds.put(JSONObject().apply {
            put("protocol", "socks")
            put("tag", "proxy")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().put(JSONObject().apply {
                    put("address", "127.0.0.1")
                    put("port", outboundSocksPort)
                }))
            })
        })
        outbounds.put(JSONObject().apply {
            put("protocol", "freedom")
            put("tag", "direct")
        })
        outbounds.put(JSONObject().apply {
            put("protocol", "blackhole")
            put("tag", "block")
        })
        json.put("outbounds", outbounds)

        // Routing
        val routing = JSONObject()
        routing.put("domainStrategy", "AsIs")
        val rules = JSONArray()
        rules.put(JSONObject().apply {
            put("type", "field")
            put("port", 443)
            put("network", "udp")
            put("outboundTag", "block")
        })
        rules.put(JSONObject().apply {
            put("type", "field")
            put("port", 53)
            put("outboundTag", "direct")
        })
        routing.put("rules", rules)
        json.put("routing", routing)
        
        // FakeDNS
        json.put("fakedns", JSONArray().put(JSONObject().apply {
            put("ipPool", "198.18.0.0/15")
            put("poolSize", 65535)
        }))
        val dns = JSONObject()
        val servers = JSONArray()
        servers.put(JSONObject().apply {
            put("address", "1.1.1.1")
            put("domains", JSONArray().put("google.com"))
        })
        servers.put("fakedns")
        dns.put("servers", servers)
        json.put("dns", dns)

        return json.toString()
    }
}
