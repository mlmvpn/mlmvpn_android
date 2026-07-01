package com.mlmvpn.scanner.engines.nahan

import org.json.JSONArray
import org.json.JSONObject

data class NahanUser(
    var name: String,
    var uuid: String,
    var limit: Long = 0,
    var reset: Long = 0,
    var used: Long = 0,
    var expiryMs: Long = 0,
    var limitDailyReq: Long = 0,
    var isPaused: Boolean = false,
    var proxyIp: String = ""
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("id", uuid)
            put("limitTotalReq", limit)
            put("reset", reset)
            put("used", used)
            put("expiryMs", expiryMs)
            put("limitDailyReq", limitDailyReq)
            put("isPaused", isPaused)
            put("proxyIp", proxyIp)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): NahanUser {
            return NahanUser(
                name = json.optString("name", ""),
                uuid = json.optString("id", json.optString("uuid", "")),
                limit = json.optLong("limitTotalReq", json.optLong("limit", 0)),
                reset = json.optLong("reset", 0),
                used = json.optLong("used", 0),
                expiryMs = json.optLong("expiryMs", 0),
                limitDailyReq = json.optLong("limitDailyReq", 0),
                isPaused = json.optBoolean("isPaused", false),
                proxyIp = json.optString("proxyIp", "")
            )
        }
    }
}

data class NahanConfig(
    var protocol: String = "Both",
    var apiRoute: String = "sync",
    var masterKey: String = "",
    var tlsPorts: List<Int> = listOf(443, 8443, 2053, 2083, 2087, 2096),
    var maintenanceHost: String = "ubuntu.com",
    var resolveIp: String = "1.1.1.1",
    var agent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    var users: MutableList<NahanUser> = mutableListOf(),
    var customRelay: String = "",
    var backupRelay: String = "",
    var customDns: String = "https://cloudflare-dns.com/dns-query",
    var cleanIps: String = "",
    var tgBotToken: String = "",
    var tgChatId: String = ""
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("mode", protocol.lowercase())
            put("apiRoute", apiRoute)
            put("masterKey", masterKey)
            
            put("socketPorts", tlsPorts.joinToString(","))
            
            put("maintenanceHost", maintenanceHost)
            put("resolveIp", resolveIp)
            put("agent", agent)
            
            put("customRelay", customRelay)
            put("backupRelay", backupRelay)
            put("customDns", customDns)

            val usersArray = JSONArray()
            users.forEach { usersArray.put(it.toJson()) }
            put("users", usersArray)
            
            put("cleanIps", cleanIps)
            put("tgToken", tgBotToken)
            put("tgChatId", tgChatId)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): NahanConfig {
            val config = NahanConfig(
                protocol = json.optString("mode", "both").replaceFirstChar { it.uppercase() },
                apiRoute = json.optString("apiRoute", "sync"),
                masterKey = json.optString("masterKey", ""),
                maintenanceHost = json.optString("maintenanceHost", "ubuntu.com"),
                resolveIp = json.optString("resolveIp", "1.1.1.1"),
                agent = json.optString("agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"),
                cleanIps = json.optString("cleanIps", ""),
                tgBotToken = json.optString("tgToken", ""),
                tgChatId = json.optString("tgChatId", "")
            )

            config.customRelay = json.optString("customRelay", "")
            config.backupRelay = json.optString("backupRelay", "")
            config.customDns = json.optString("customDns", "https://cloudflare-dns.com/dns-query")

            val portsString = json.optString("socketPorts", "443, 8443, 2053, 2083, 2087, 2096")
            if (portsString.isNotEmpty()) {
                config.tlsPorts = portsString.split(",").mapNotNull { it.trim().toIntOrNull() }
            }

            val usersArray = json.optJSONArray("users")
            if (usersArray != null) {
                for (i in 0 until usersArray.length()) {
                    config.users.add(NahanUser.fromJson(usersArray.getJSONObject(i)))
                }
            }
            return config
        }
    }
}
