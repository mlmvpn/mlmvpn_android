package com.mlmvpn.scanner.models

data class CloudSettings(
    var ipv6: Boolean = true,
    var protocolVLESS: Boolean = true,
    var protocolTrojan: Boolean = true,
    var proxyIpMode: String = "proxyip", // 'proxyip' or 'prefix'
    var prefixes: List<String> = emptyList(),
    var proxyIps: List<String> = emptyList(),
    var bypassIran: Boolean = true,
    var blockAds: Boolean = false,
    var warpEnable: Boolean = false,
    var dns: String = "cloudflare",
    var fragEnable: Boolean = false,
    var fragMin: Int = 100,
    var fragMax: Int = 200,
    var fragInt: Int = 2,
    var allowLANConnection: Boolean = true,
    var ports: List<Int> = emptyList()
)
