package com.mlmvpn.core.warp

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

class WarpXrayInjector(private val vpnFd: Int) : IVpnEngine {
    private var coreController: CoreController? = null

    // Constant WARP Public Key
    private val WARP_PEER_PUBLIC_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

    override suspend fun start(context: Context, config: String, localPort: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            // config string is expected to be a JSON string with WARP parameters
            val params = JSONObject(config)
            val privateKey = params.getString("privateKey")
            val endpointIp = params.getString("endpointIp")
            val endpointPort = params.getInt("endpointPort")
            val ipv4 = params.getString("ipv4")
            val ipv6 = params.getString("ipv6")
            val reservedArray = params.optJSONArray("reserved")
            
            val reservedList = mutableListOf<Int>()
            if (reservedArray != null) {
                for (i in 0 until reservedArray.length()) {
                    reservedList.add(reservedArray.getInt(i))
                }
            } else {
                // Generate default 0,0,0 if missing
                reservedList.addAll(listOf(0, 0, 0))
            }

            val prefs = context.getSharedPreferences("vpn_routing_prefs", Context.MODE_PRIVATE)
            val mtu = prefs.getInt("vpn_mtu", 1280)

            val jsonConfig = buildXrayConfig(localPort, privateKey, endpointIp, endpointPort, ipv4, ipv6, reservedList, mtu)

            // Initialize Libv2ray environment if not done
            val keyBytes = ByteArray(32)
            SecureRandom().nextBytes(keyBytes)
            val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            val xudpBaseKey = Base64.encodeToString(keyBytes, flags)
            
            try {
                Libv2ray.initCoreEnv(context.filesDir.absolutePath, xudpBaseKey)
            } catch (e: Exception) {
                // Ignore if already initialized
            }

            val handler = object : CoreCallbackHandler {
                override fun onEmitStatus(status: Long, msg: String): Long = 0
                override fun shutdown(): Long = 0
                override fun startup(): Long = 0
            }

            coreController = Libv2ray.newCoreController(handler)
            coreController?.startLoop(jsonConfig, vpnFd)
            
            delay(1000) // Wait for core to bind ports
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    override fun stop() {
        try {
            coreController?.stopLoop()
            coreController = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildXrayConfig(
        localPort: Int,
        privateKey: String,
        endpointIp: String,
        endpointPort: Int,
        ipv4: String,
        ipv6: String,
        reserved: List<Int>,
        mtu: Int = 1280
    ): String {
        val root = JSONObject()

        // Log
        root.put("log", JSONObject().apply {
            put("access", "none")
            put("error", "none")
            put("loglevel", "error")
        })

        // Inbounds
        val inbounds = JSONArray()
        
        // SOCKS Inbound (always active for testing and local proxy)
        inbounds.put(JSONObject().apply {
            put("port", localPort)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("tag", "socks")
            put("settings", JSONObject().apply {
                put("udp", true)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().put("http").put("tls"))
            })
        })

        // TUN Inbound (only when vpnFd > 0, i.e. actual Connect, not scanner)
        if (vpnFd > 0) {
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
        }
        
        root.put("inbounds", inbounds)

        // Outbounds
        val outbounds = JSONArray()
        outbounds.put(JSONObject().apply {
            put("protocol", "wireguard")
            put("tag", "proxy")
            put("settings", JSONObject().apply {
                put("secretKey", privateKey)
                
                val addressArray = JSONArray()
                if (ipv4.isNotEmpty()) addressArray.put("$ipv4/32")
                if (ipv6.isNotEmpty()) addressArray.put("$ipv6/128")
                put("address", addressArray)
                
                val peersArray = JSONArray()
                peersArray.put(JSONObject().apply {
                    put("endpoint", "$endpointIp:$endpointPort")
                    put("publicKey", WARP_PEER_PUBLIC_KEY)
                    put("keepAlive", 15)
                    put("allowedIPs", JSONArray().put("0.0.0.0/0").put("::/0"))
                })
                put("peers", peersArray)
                
                put("mtu", mtu)
                
                val reservedJsonArray = JSONArray()
                reserved.forEach { reservedJsonArray.put(it) }
                put("reserved", reservedJsonArray)
            })
            put("streamSettings", JSONObject().apply {
                put("sockopt", JSONObject().apply {
                    put("mark", 0)
                    put("tcpFastOpen", true)
                })
            })
        })
        
        // DNS Outbound
        outbounds.put(JSONObject().apply {
            put("protocol", "dns")
            put("tag", "dns-out")
        })
        
        root.put("outbounds", outbounds)

        // DNS Configuration
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8"))
        })

        // Routing Configuration
        root.put("routing", JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("rules", JSONArray().put(JSONObject().apply {
                put("type", "field")
                put("port", 53)
                put("outboundTag", "dns-out")
            }))
        })

        return root.toString()
    }
}
