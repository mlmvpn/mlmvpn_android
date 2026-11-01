package com.mlmvpn.scanner.engines.scout

import org.json.JSONObject
import java.io.File

object ScoutTunnelPolicy {
    private val paths = mapOf("config_path" to "tunnel.toml", "wireguard_config_path" to "tunnel.toml",
        "masque_config_path" to "tunnel-masque.toml", "endpoint_cache_path" to "endpoints.json")
    private val settings = listOf("obfuscation_profile", "obfuscation_parameters", "retry_obfuscation_profiles",
        "tls_curve_preset", "perf_profile", "h2_fragmentation", "dns_servers", "team", "access_client_id",
        "access_client_secret", "access_token", "access_email", "gateway")

    /**
     * [fragment]: MASQUE's HTTP/2 ClientHello split, forced on or off for a variant that tests that
     * very choice; null keeps the user's own setting. Obfuscation profiles are always retried: a
     * user who turned that off would otherwise see «کار نمی‌کند» for a transport that a second
     * profile carries.
     */
    fun config(base: JSONObject, directory: File, protocol: String, family: Fam, carrier: String, port: Int, peer: String?, fragment: Boolean? = null): JSONObject {
        require(protocol in setOf("masque", "wireguard", "gool") && family in setOf(Fam.V4, Fam.V6))
        require(carrier in setOf("h2", "h3") && port in 1..65535)
        return JSONObject().apply {
            settings.forEach { if (base.has(it) && !base.isNull(it)) put(it, base.get(it)) }
            fragment?.let { put("h2_fragmentation", it) }
            put("retry_obfuscation_profiles", true)
            paths.forEach { (key, name) -> put(key, File(directory, name).absolutePath) }
            put("protocol", protocol); put("listen", "127.0.0.1:$port")
            put("ip_scan", if (family == Fam.V4) "v4" else "v6")
            put("scan_mode", "turbo"); put("masque_transport", carrier)
            put("endpoint_discovery", "cache"); put("wireguard_data_check", true); put("log_level", "error")
            putOpt("forced_peer", peer)
        }
    }

    fun owned(config: JSONObject, directory: File): Boolean = runCatching {
        paths.all { (key, name) -> File(config.getString(key)).canonicalFile == File(directory, name).canonicalFile } &&
            config.optString("protocol") in setOf("masque", "wireguard", "gool") &&
            config.optString("ip_scan") in setOf("v4", "v6") &&
            config.optString("listen").matches(Regex("127\\.0\\.0\\.1:[0-9]{1,5}")) &&
            !config.has("route_direct") && !config.has("route_block")
    }.getOrDefault(false)

    fun mayKill(pid: Int, ownPid: Int, uid: Int, ownUid: Int, name: String, app: String): Boolean =
        pid > 0 && pid != ownPid && uid == ownUid && name == "$app:scouttunnel"

    fun identityUsable(text: String, masque: Boolean): Boolean =
        Regex("(?m)^device_id = \"\\S+\"").containsMatchIn(text) &&
            Regex("(?m)^wg_private_key = \"\\S+\"").containsMatchIn(text) &&
            (!masque || Regex("(?m)^cert_pem = \"{1,3}\\s*-----BEGIN").containsMatchIn(text))
}
