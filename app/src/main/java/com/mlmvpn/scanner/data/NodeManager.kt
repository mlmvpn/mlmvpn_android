package com.mlmvpn.scanner.data

import android.content.Context
import android.content.SharedPreferences
import com.mlmvpn.scanner.models.VpnNode
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.launch

class NodeManager private constructor(context: Context) {
    companion object {
        @Volatile
        private var instance: NodeManager? = null

        operator fun invoke(context: Context): NodeManager {
            return instance ?: synchronized(this) {
                instance ?: NodeManager(context.applicationContext).also { instance = it }
            }
        }
    }
    private val prefs: SharedPreferences = context.getSharedPreferences("vpn_nodes_prefs", Context.MODE_PRIVATE)
    
    var nodes: MutableList<VpnNode> = mutableListOf()
        private set

    private val _nodesFlow = kotlinx.coroutines.flow.MutableStateFlow<List<VpnNode>>(emptyList())
    val nodesFlow: kotlinx.coroutines.flow.StateFlow<List<VpnNode>> = _nodesFlow

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            loadNodes()
            _nodesFlow.value = nodes.toList()
        }
    }

    private fun loadNodes() {
        val jsonStr = prefs.getString("nodes_list", "[]") ?: "[]"
        try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<VpnNode>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    VpnNode(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        uri = obj.getString("uri"),
                        type = obj.getString("type"),
                        ping = obj.optString("ping", "Test"),
                        delay = obj.optString("delay", "Test"),
                        speed = obj.optString("speed", "Test"),
                        addedAt = obj.optLong("addedAt", System.currentTimeMillis()),
                        engineType = obj.optString("engineType", "BPB"),
                        countryCode = if (obj.has("countryCode") && !obj.isNull("countryCode")) obj.getString("countryCode") else null,
                        groupTitle = if (obj.has("groupTitle") && !obj.isNull("groupTitle")) {
                            val gt = obj.getString("groupTitle")
                            if (gt == "پیش‌فرض") null else gt
                        } else null
                    )
                )
            }
            nodes = list
            injectDefaultConfigs()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveNodes() {
        val arr = JSONArray()
        for (node in nodes) {
            val obj = JSONObject().apply {
                put("id", node.id)
                put("name", node.name)
                put("uri", node.uri)
                put("type", node.type)
                put("ping", node.ping)
                put("delay", node.delay)
                put("speed", node.speed)
                put("addedAt", node.addedAt)
                put("engineType", node.engineType)
                put("countryCode", node.countryCode ?: JSONObject.NULL)
                put("groupTitle", node.groupTitle ?: JSONObject.NULL)
            }
            arr.put(obj)
        }
        prefs.edit().putString("nodes_list", arr.toString()).apply()
        _nodesFlow.value = nodes.toList()
    }

    fun removeNodesByGroup(groupId: String) {
        nodes.removeAll { it.engineType == "Manual" && it.groupTitle == groupId }
        saveNodes()
    }

    fun addConfigs(configs: List<String>, groupTitle: String? = null): Int {
        var addedCount = 0
        for (config in configs) {
            if (config.isBlank()) continue
            val type = if (config.startsWith("vless://")) "VLESS"
                       else if (config.startsWith("trojan://")) "TROJAN"
                       else "UNKNOWN"
            
            // Extract name from fragment (#Name)
            var name = "Node ${nodes.size + 1}"
            val hashIdx = config.lastIndexOf("#")
            if (hashIdx != -1) {
                try {
                    name = java.net.URLDecoder.decode(config.substring(hashIdx + 1), "UTF-8")
                } catch(e: Exception) {}
            }
            
            // Prevent duplicates within the same group
            if (nodes.any { it.uri == config && it.groupTitle == groupTitle }) continue
            
            nodes.add(
                VpnNode(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    uri = config,
                    type = type,
                    engineType = "Manual",
                    groupTitle = groupTitle
                )
            )
            addedCount++
        }
        
        if (addedCount > 0) {
            saveNodes()
        }
        return addedCount
    }


    fun clearAll() {
        nodes.clear()
        injectDefaultConfigs()
        saveNodes()
    }
    private fun injectDefaultConfigs() {
        val default1 = """{"remarks":"Serverless-v44-low_delay","version":{"min":"26.6.1"},"log":{"loglevel":"debug","dnsLog":true,"access":""},"policy":{"levels":{"0":{"uplinkOnly":0,"downlinkOnly":0},"1":{"uplinkOnly":0,"downlinkOnly":0,"connIdle":12}}},"dns":{"hosts":{"cloudflare-dns.com":"challenges.cloudflare.com"},"servers":[{"address":"fakedns","domains":["domain:com","domain:net","domain:org","domain:co","domain:io","domain:tv","domain:info","domain:xyz","geosite:geolocation-!cn","geosite:telegram"]},{"tag":"no-filter-dns","address":"tcp://8.8.8.8","timeoutMs":12000,"finalQuery":true},{"address":"8.8.8.8","domains":["domain:ir","geosite:category-ir"],"finalQuery":true}],"queryStrategy":"UseSystem","useSystemHosts":true,"serveStale":true},"inbounds":[{"tag":"mixed-in","port":10808,"protocol":"mixed","sniffing":{"enabled":true,"destOverride":["fakedns","tls","http","quic"],"routeOnly":false},"settings":{"udp":true,"ip":"127.0.0.1"},"streamSettings":{"sockopt":{"tcpKeepAliveInterval":1,"tcpKeepAliveIdle":11}}}],"outbounds":[{"tag":"block","protocol":"block"},{"tag":"tcp-direct","protocol":"direct","streamSettings":{"sockopt":{"domainStrategy":"ForceIP"}}},{"tag":"udp-direct","protocol":"direct","settings":{"targetStrategy":"ForceIPv4"}},{"tag":"dns-out","protocol":"dns","settings":{"userLevel":1}},{"tag":"tcp-fragment","protocol":"direct","streamSettings":{"finalmask":{"tcp":[{"type":"fragment","settings":{"packets":"1-3","length":"1-5","delay":"10","maxSplit":"163"}}]},"sockopt":{"domainStrategy":"ForceIP"}}},{"tag":"udp-noises","protocol":"direct","settings":{"targetStrategy":"ForceIPv4"},"streamSettings":{"finalmask":{"udp":[{"type":"noise","settings":{"reset":"28","noise":[{"rand":"1200-1230","delay":"10"},{"rand":"1200-1230","delay":"10"},{"rand":"1200-1230","delay":"10"}]}}]}}}],"routing":{"domainStrategy":"IPOnDemand","rules":[{"outboundTag":"tcp-fragment","inboundTag":["no-filter-dns"]},{"outboundTag":"dns-out","port":53},{"outboundTag":"tcp-direct","network":"tcp","domain":["domain:ir","geosite:category-ir"]},{"outboundTag":"udp-direct","network":"udp","domain":["domain:ir","geosite:category-ir"]},{"outboundTag":"tcp-direct","network":"tcp","ip":["geoip:private","geoip:ir"]},{"outboundTag":"udp-direct","network":"udp","ip":["geoip:private","geoip:ir"]},{"outboundTag":"udp-noises","network":"udp","protocol":["quic"],"domain":["geosite:youtube","domain:youtube.com","domain:googlevideo.com","domain:googleapis.com","domain:tiktokv.eu","domain:tiktok.com","domain:tiktokcdn.com"]},{"outboundTag":"block","network":"udp","protocol":["quic"]},{"outboundTag":"udp-direct","network":"udp","ip":["0.0.0.0/0","::/0"]},{"outboundTag":"tcp-fragment","network":"tcp","protocol":["tls","http"],"ip":["0.0.0.0/0","::/0"]},{"outboundTag":"tcp-fragment","network":"tcp","port":"443,80,5222,5228,8080","ip":["0.0.0.0/0","::/0"]},{"outboundTag":"tcp-fragment","network":"tcp","ip":["0.0.0.0/0","::/0"]},{"outboundTag":"block","port":"0-65535"}]}}"""
        val default2 = """{"remarks":"Serverless-v44-low_delay","version":{"min":"26.6.1"},"log":{"loglevel":"debug","dnsLog":true,"access":""},"policy":{"levels":{"0":{"uplinkOnly":0,"downlinkOnly":0},"1":{"uplinkOnly":0,"downlinkOnly":0,"connIdle":12}}},"dns":{"hosts":{"cloudflare-dns.com":"challenges.cloudflare.com"},"servers":[{"address":"fakedns","domains":["domain:com","domain:net","domain:org","domain:co","domain:io","domain:tv","domain:info","domain:xyz","geosite:geolocation-!cn","geosite:telegram"]},{"tag":"no-filter-dns","address":"tcp://8.8.8.8","timeoutMs":12000,"finalQuery":true},{"address":"8.8.8.8","domains":["domain:ir","geosite:category-ir"],"finalQuery":true}],"queryStrategy":"UseSystem","useSystemHosts":true,"serveStale":true},"inbounds":[{"tag":"mixed-in","port":10808,"protocol":"mixed","sniffing":{"enabled":true,"destOverride":["fakedns","tls","http","quic"],"routeOnly":false},"settings":{"udp":true,"ip":"127.0.0.1"},"streamSettings":{"sockopt":{"tcpKeepAliveInterval":1,"tcpKeepAliveIdle":11}}}],"outbounds":[{"tag":"block","protocol":"block"},{"tag":"tcp-direct","protocol":"direct","streamSettings":{"sockopt":{"domainStrategy":"ForceIP"}}},{"tag":"udp-direct","protocol":"direct","settings":{"targetStrategy":"ForceIPv4"}},{"tag":"dns-out","protocol":"dns","settings":{"userLevel":1}},{"tag":"tcp-fragment","protocol":"direct","streamSettings":{"finalmask":{"tcp":[{"type":"fragment","settings":{"packets":"1-3","length":"1-5","delay":"10","maxSplit":"163"}}]},"sockopt":{"domainStrategy":"ForceIP"}}},{"tag":"udp-noises","protocol":"direct","settings":{"targetStrategy":"ForceIPv4"},"streamSettings":{"finalmask":{"udp":[{"type":"noise","settings":{"reset":"28","noise":[{"rand":"1200-1230","delay":"10"},{"rand":"1200-1230","delay":"10"},{"rand":"1200-1230","delay":"10"}]}}]}}}],"routing":{"domainStrategy":"IPOnDemand","rules":[{"outboundTag":"tcp-fragment","inboundTag":["no-filter-dns"]},{"outboundTag":"dns-out","port":53},{"outboundTag":"tcp-direct","network":"tcp","domain":["domain:ir","geosite:category-ir"]},{"outboundTag":"udp-direct","network":"udp","domain":["domain:ir","geosite:category-ir"]},{"outboundTag":"tcp-direct","network":"tcp","ip":["geoip:private","geoip:ir"]},{"outboundTag":"udp-direct","network":"udp","ip":["geoip:private","geoip:ir"]},{"outboundTag":"udp-noises","network":"udp","protocol":["quic"],"domain":["geosite:youtube","domain:youtube.com","domain:googlevideo.com","domain:googleapis.com","domain:tiktokv.eu","domain:tiktok.com","domain:tiktokcdn.com"]},{"outboundTag":"block","network":"udp","protocol":["quic"]},{"outboundTag":"udp-direct","network":"udp","ip":["0.0.0.0/0","::/0"]},{"outboundTag":"tcp-fragment","network":"tcp","protocol":["tls","http"],"ip":["0.0.0.0/0","::/0"]},{"outboundTag":"tcp-fragment","network":"tcp","port":"443,80,5222,5228,8080","ip":["0.0.0.0/0","::/0"]},{"outboundTag":"tcp-fragment","network":"tcp","ip":["0.0.0.0/0","::/0"]},{"outboundTag":"block","port":"0-65535"}]}}"""
        nodes.removeAll { it.id.startsWith("default_mlmvpn_") }
        
        nodes.add(0, VpnNode(
            id = "default_mlmvpn_6",
            name = "کانفیگ پیش‌فرض mlmvpn 6",
            uri = default2,
            type = "JSON",
            engineType = "Manual",
            groupTitle = null
        ))
        nodes.add(0, VpnNode(
            id = "default_mlmvpn_5",
            name = "کانفیگ پیش‌فرض mlmvpn 5",
            uri = default1,
            type = "JSON",
            engineType = "Manual",
            groupTitle = null
        ))
        nodes.add(0, VpnNode(
            id = "default_mlmvpn_4",
            name = "کانفیگ پیش‌فرض mlmvpn 4",
            uri = default2,
            type = "JSON",
            engineType = "Manual",
            groupTitle = null
        ))
        nodes.add(0, VpnNode(
            id = "default_mlmvpn_3",
            name = "کانفیگ پیش‌فرض mlmvpn 3",
            uri = default1,
            type = "JSON",
            engineType = "Manual",
            groupTitle = null
        ))
        nodes.add(0, VpnNode(
            id = "default_mlmvpn_2",
            name = "کانفیگ پیش‌فرض mlmvpn 2",
            uri = default2,
            type = "JSON",
            engineType = "Manual",
            groupTitle = null
        ))
        nodes.add(0, VpnNode(
            id = "default_mlmvpn_1",
            name = "کانفیگ پیش‌فرض mlmvpn 1",
            uri = default1,
            type = "JSON",
            engineType = "Manual",
            groupTitle = null
        ))
    }
}