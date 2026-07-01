package com.mlmvpn.scanner.utils

import android.net.Uri

data class VpnConfig(
    var protocol: String = "vless",
    var name: String = "",
    var address: String = "",
    var port: Int = 443,
    var uuid: String = "",
    var network: String = "ws",
    var wsHost: String = "",
    var wsPath: String = "",
    var xhttpPath: String = "",
    var xhttpHost: String = "",
    var xhttpMode: String = "auto",
    var xhttpExtra: String = "",
    var tls: String = "tls",
    var sni: String = "",
    var alpn: String = "",
    var fingerprint: String = ""
) {
    companion object {
        fun parseUri(uriString: String): VpnConfig? {
            try {
                val config = VpnConfig()
                val uri = Uri.parse(uriString)
                config.protocol = uri.scheme?.lowercase() ?: return null
                if (config.protocol != "vless" && config.protocol != "trojan") return null
                
                val userInfo = uri.userInfo
                if (userInfo != null) config.uuid = userInfo
                
                config.address = uri.host ?: ""
                config.port = if (uri.port > 0) uri.port else 443
                
                config.name = uri.fragment?.let { android.net.Uri.decode(it) } ?: ""
                
                config.network = uri.getQueryParameter("type") ?: "ws"
                config.wsHost = uri.getQueryParameter("host") ?: ""
                config.wsPath = uri.getQueryParameter("path") ?: ""
                config.xhttpPath = uri.getQueryParameter("path") ?: ""
                config.xhttpHost = uri.getQueryParameter("host") ?: ""
                config.xhttpMode = uri.getQueryParameter("mode") ?: "auto"
                config.xhttpExtra = uri.getQueryParameter("extra") ?: ""
                config.tls = uri.getQueryParameter("security") ?: ""
                config.sni = uri.getQueryParameter("sni") ?: ""
                config.alpn = uri.getQueryParameter("alpn") ?: ""
                config.fingerprint = uri.getQueryParameter("fp") ?: ""
                
                return config
            } catch (e: Exception) {
                return null
            }
        }
    }
    
    fun toUriString(): String {
        val builder = Uri.Builder()
            .scheme(protocol)
            .encodedAuthority("$uuid@$address:$port")
        
        if (network.isNotEmpty()) builder.appendQueryParameter("type", network)
        if (network == "xhttp") {
            if (xhttpHost.isNotEmpty()) builder.appendQueryParameter("host", xhttpHost)
            if (xhttpPath.isNotEmpty()) builder.appendQueryParameter("path", xhttpPath)
            if (xhttpMode != "auto" && xhttpMode.isNotEmpty()) builder.appendQueryParameter("mode", xhttpMode)
            if (xhttpExtra.isNotEmpty()) builder.appendQueryParameter("extra", xhttpExtra)
        } else {
            if (wsHost.isNotEmpty()) builder.appendQueryParameter("host", wsHost)
            if (wsPath.isNotEmpty()) builder.appendQueryParameter("path", wsPath)
        }
        if (tls.isNotEmpty()) builder.appendQueryParameter("security", tls)
        if (sni.isNotEmpty()) builder.appendQueryParameter("sni", sni)
        if (alpn.isNotEmpty()) builder.appendQueryParameter("alpn", alpn)
        if (fingerprint.isNotEmpty()) builder.appendQueryParameter("fp", fingerprint)
        if (protocol == "vless") builder.appendQueryParameter("encryption", "none")
        
        builder.fragment(name)
        
        return builder.build().toString().replace("%40", "@")
    }
}
