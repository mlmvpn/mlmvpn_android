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

        /** Group the built-in Iran default configs live in. */
        const val IRAN_GROUP = "کانفیگ‌های ایران"

        /** Built-in configs that must never be deletable by any UI path. */
        fun isProtected(node: VpnNode): Boolean = node.id.startsWith("default_mlmvpn_")
    }
    private val prefs: SharedPreferences = context.getSharedPreferences("vpn_nodes_prefs", Context.MODE_PRIVATE)
    
    var nodes: MutableList<VpnNode> = java.util.Collections.synchronizedList(mutableListOf<VpnNode>())
        private set

    private val _nodesFlow = kotlinx.coroutines.flow.MutableStateFlow<List<VpnNode>>(emptyList())
    val nodesFlow: kotlinx.coroutines.flow.StateFlow<List<VpnNode>> = _nodesFlow

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            loadNodes()
            _nodesFlow.value = synchronized(nodes) { nodes.toList() }
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
            synchronized(nodes) {
                nodes.clear()
                nodes.addAll(list)
                injectDefaultConfigs()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveNodes() {
        val arr = JSONArray()
        // Bulletproof protection: the built-in Iran configs must survive every deletion
        // path (delete-all button, per-node delete, group delete). No matter what a caller
        // removed from `nodes`, re-inject them here so they are always persisted and always
        // re-emitted on nodesFlow.
        val snapshot = synchronized(nodes) {
            injectDefaultConfigs()
            nodes.toList()
        }
        for (node in snapshot) {
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
        _nodesFlow.value = snapshot
    }

    fun removeNodesByGroup(groupId: String) {
        synchronized(nodes) {
            nodes.removeAll { it.engineType == "Manual" && it.groupTitle == groupId && !isProtected(it) }
        }
        saveNodes()
    }

    fun addConfigs(configs: List<String>, groupTitle: String? = null): Int {
        var addedCount = 0
        synchronized(nodes) {
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
        }

        if (addedCount > 0) {
            saveNodes()
        }
        return addedCount
    }


    fun clearAll() {
        synchronized(nodes) {
            nodes.clear()
            injectDefaultConfigs()
        }
        saveNodes()
    }

    // Caller must hold `synchronized(nodes)` already; not synchronized internally to avoid re-entrant lock churn.
    private fun injectDefaultConfigs() {
        val default1 = """{"remarks":"Serverless-v44-low_delay","version":{"min":"26.6.1"},"log":{"loglevel":"debug","dnsLog":true,"access":""},"policy":{"levels":{"0":{"uplinkOnly":0,"downlinkOnly":0},"1":{"uplinkOnly":0,"downlinkOnly":0,"connIdle":12}}},"dns":{"hosts":{"cloudflare-dns.com":"challenges.cloudflare.com"},"servers":[{"address":"fakedns","domains":["domain:com","domain:net","domain:org","domain:co","domain:io","domain:tv","domain:info","domain:xyz","geosite:geolocation-!cn","geosite:telegram"]},{"tag":"no-filter-dns","address":"https://8.8.8.8/dns-query","timeoutMs":12000,"finalQuery":true},{"address":"8.8.8.8","domains":["domain:ir","geosite:category-ir"],"finalQuery":true}],"queryStrategy":"UseSystem","useSystemHosts":true,"serveStale":true},"inbounds":[{"tag":"mixed-in","port":10808,"protocol":"mixed","sniffing":{"enabled":true,"destOverride":["fakedns","tls","http","quic"],"routeOnly":false},"settings":{"udp":true,"ip":"127.0.0.1"},"streamSettings":{"sockopt":{"tcpKeepAliveInterval":1,"tcpKeepAliveIdle":11}}}],"outbounds":[{"tag":"block","protocol":"block"},{"tag":"tcp-direct","protocol":"direct","streamSettings":{"sockopt":{"domainStrategy":"ForceIP"}}},{"tag":"udp-direct","protocol":"direct","settings":{"targetStrategy":"ForceIPv4"}},{"tag":"dns-out","protocol":"dns","settings":{"userLevel":1}},{"tag":"tcp-fragment","protocol":"direct","streamSettings":{"finalmask":{"tcp":[{"type":"fragment","settings":{"packets":"1-3","length":"1-5","delay":"10","maxSplit":"163"}}]},"sockopt":{"domainStrategy":"ForceIP"}}},{"tag":"udp-noises","protocol":"direct","settings":{"targetStrategy":"ForceIPv4"},"streamSettings":{"finalmask":{"udp":[{"type":"noise","settings":{"reset":"28","noise":[{"rand":"1200-1230","delay":"10"},{"rand":"1200-1230","delay":"10"},{"rand":"1200-1230","delay":"10"}]}}]}}}],"routing":{"domainStrategy":"IPOnDemand","rules":[{"outboundTag":"tcp-fragment","inboundTag":["no-filter-dns"]},{"outboundTag":"dns-out","port":53},{"outboundTag":"tcp-direct","network":"tcp","domain":["domain:ir","geosite:category-ir"]},{"outboundTag":"udp-direct","network":"udp","domain":["domain:ir","geosite:category-ir"]},{"outboundTag":"tcp-direct","network":"tcp","ip":["geoip:private","geoip:ir"]},{"outboundTag":"udp-direct","network":"udp","ip":["geoip:private","geoip:ir"]},{"outboundTag":"udp-noises","network":"udp","protocol":["quic"],"domain":["geosite:youtube","domain:youtube.com","domain:googlevideo.com","domain:googleapis.com","domain:tiktokv.eu","domain:tiktok.com","domain:tiktokcdn.com"]},{"outboundTag":"block","network":"udp","protocol":["quic"]},{"outboundTag":"udp-direct","network":"udp","ip":["0.0.0.0/0","::/0"]},{"outboundTag":"tcp-fragment","network":"tcp","protocol":["tls","http"],"ip":["0.0.0.0/0","::/0"]},{"outboundTag":"tcp-fragment","network":"tcp","port":"443,80,5222,5228,8080","ip":["0.0.0.0/0","::/0"]},{"outboundTag":"tcp-fragment","network":"tcp","ip":["0.0.0.0/0","::/0"]},{"outboundTag":"block","port":"0-65535"}]}}"""
        // Config #1 is the untouched baseline (`default1`). The other 5 are derived from the
        // same base but each uses a DIFFERENT DPI-evasion strategy, so if one operator's DPI
        // (Hamrah-e-Aval / Irancell / Shatel / …) blocks a given fingerprint, another config
        // still connects. Two knobs are varied per profile:
        //   - DNS resolver/method (Google DoH / Cloudflare DoH / Google DoT)
        //   - TCP fragment profile: how the TLS ClientHello is chopped (packets/length/delay)
        // `remarks` is set per profile so the active strategy shows up in the connection log.
        data class IranProfile(
            val remarks: String,
            val name: String,
            val dns: String,
            val fragPackets: String,
            val fragLength: String,
            val fragDelay: String
        )
        // #1 mirrors the current baseline exactly (Google DoH, frag 1-3/1-5/delay 10).
        val profiles = listOf(
            IranProfile("Serverless-v44-low_delay",       "کانفیگ ایران ۱ — پیش‌فرض (Google DoH)",   "https://8.8.8.8/dns-query", "1-3", "1-5",   "10"),
            IranProfile("iran2-google-doh-aggressive",     "کانفیگ ایران ۲ — تهاجمی ریز (Google DoH)", "https://8.8.8.8/dns-query", "1-5", "1-3",   "5"),
            IranProfile("iran3-google-doh-light",          "کانفیگ ایران ۳ — سبک/سریع (Google DoH)",   "https://8.8.8.8/dns-query", "1-1", "40-80", "2"),
            IranProfile("iran4-cloudflare-doh",            "کانفیگ ایران ۴ — Cloudflare DoH",          "https://1.1.1.1/dns-query", "1-3", "1-5",   "10"),
            IranProfile("iran5-google-dot",                "کانفیگ ایران ۵ — Google DoT",              "tls://8.8.8.8:853",         "1-3", "2-8",   "8"),
            IranProfile("iran6-cloudflare-aggressive",     "کانفیگ ایران ۶ — Cloudflare تهاجمی",       "https://1.1.1.1/dns-query", "1-5", "1-2",   "4")
        )

        nodes.removeAll { it.id.startsWith("default_mlmvpn_") }

        // Insert at index idx so the list ends up ordered 1..6 from the top.
        profiles.forEachIndexed { idx, p ->
            val uri = default1
                .replace("\"remarks\":\"Serverless-v44-low_delay\"", "\"remarks\":\"${p.remarks}\"")
                .replace("\"address\":\"https://8.8.8.8/dns-query\"", "\"address\":\"${p.dns}\"")
                .replace(
                    "\"packets\":\"1-3\",\"length\":\"1-5\",\"delay\":\"10\"",
                    "\"packets\":\"${p.fragPackets}\",\"length\":\"${p.fragLength}\",\"delay\":\"${p.fragDelay}\""
                )
            nodes.add(idx, VpnNode(
                id = "default_mlmvpn_${idx + 1}",
                name = p.name,
                uri = uri,
                type = "JSON",
                engineType = "Manual",
                groupTitle = IRAN_GROUP
            ))
        }
    }
}