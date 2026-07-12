package com.mlmvpn.scanner.utils

import android.net.Uri

data class VpnConfig(
    var protocol: String = "vless",
    var name: String = "",
    var address: String = "",
    var port: Int = 443,
    var uuid: String = "",
    var flow: String = "",
    var network: String = "tcp",
    var wsHost: String = "",
    var wsPath: String = "",
    var xhttpPath: String = "",
    var xhttpHost: String = "",
    var xhttpMode: String = "auto",
    var xhttpExtra: String = "",
    var serviceName: String = "",
    var tls: String = "tls",
    var sni: String = "",
    var alpn: String = "",
    var fingerprint: String = "",
    var publicKey: String = "",
    var shortId: String = "",
    var spiderX: String = ""
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
                
                config.network = uri.getQueryParameter("type") ?: "tcp"
                config.wsHost = uri.getQueryParameter("host") ?: ""
                config.wsPath = uri.getQueryParameter("path") ?: ""
                config.xhttpPath = uri.getQueryParameter("path") ?: ""
                config.xhttpHost = uri.getQueryParameter("host") ?: ""
                config.xhttpMode = uri.getQueryParameter("mode") ?: "auto"
                config.xhttpExtra = uri.getQueryParameter("extra") ?: ""
                config.serviceName = uri.getQueryParameter("serviceName") ?: ""
                config.tls = uri.getQueryParameter("security") ?: ""
                config.sni = uri.getQueryParameter("sni") ?: ""
                config.alpn = uri.getQueryParameter("alpn") ?: ""
                config.fingerprint = uri.getQueryParameter("fp") ?: ""
                config.flow = uri.getQueryParameter("flow") ?: ""
                config.publicKey = uri.getQueryParameter("pbk") ?: ""
                config.shortId = uri.getQueryParameter("sid") ?: ""
                config.spiderX = uri.getQueryParameter("spx") ?: ""

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
        } else if (network == "grpc") {
            if (serviceName.isNotEmpty()) builder.appendQueryParameter("serviceName", serviceName)
        } else {
            if (wsHost.isNotEmpty()) builder.appendQueryParameter("host", wsHost)
            if (wsPath.isNotEmpty()) builder.appendQueryParameter("path", wsPath)
        }
        if (tls.isNotEmpty()) builder.appendQueryParameter("security", tls)
        if (sni.isNotEmpty()) builder.appendQueryParameter("sni", sni)
        if (alpn.isNotEmpty()) builder.appendQueryParameter("alpn", alpn)
        if (fingerprint.isNotEmpty()) builder.appendQueryParameter("fp", fingerprint)
        if (protocol == "vless" && flow.isNotEmpty()) builder.appendQueryParameter("flow", flow)
        if (tls == "reality") {
            if (publicKey.isNotEmpty()) builder.appendQueryParameter("pbk", publicKey)
            if (shortId.isNotEmpty()) builder.appendQueryParameter("sid", shortId)
            if (spiderX.isNotEmpty()) builder.appendQueryParameter("spx", spiderX)
        }
        if (protocol == "vless") builder.appendQueryParameter("encryption", "none")
        
        builder.fragment(name)
        
        return builder.build().toString().replace("%40", "@")
    }
}
