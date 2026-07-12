package com.mlmvpn.scanner.utils

object AmneziaWgConfigGenerator {

    fun generateAmneziaWgConfig(
        privateKey: String,
        address: String, // format: "10.8.0.x/24"
        serverPubkey: String,
        endpoint: String, // format: "ip:port"
        mtu: Int = 1280,
        dns: String = "8.8.8.8",
        gameSubnets: List<String> = emptyList(),
        // AmneziaWG obfuscation params. These MUST match the server's awg0.conf
        // (S1/S2 are the junk sizes prepended to handshake init/response packets;
        // a mismatch means the server silently drops our handshake). The UAE game
        // server uses Jc=4 Jmin=40 Jmax=70 S1=17 S2=22 H1..H4=1,2,3,4.
        jc: Int = 4,
        jmin: Int = 40,
        jmax: Int = 70,
        s1: Int = 17,
        s2: Int = 22,
        h1: Int = 1,
        h2: Int = 2,
        h3: Int = 3,
        h4: Int = 4
    ): String {
        val allowedIps = if (gameSubnets.isEmpty() || gameSubnets.contains("0.0.0.0/0")) {
            "0.0.0.0/0, ::/0"
        } else {
            gameSubnets.joinToString(", ")
        }
        
        return """
            [Interface]
            PrivateKey = $privateKey
            Address = $address
            DNS = $dns
            MTU = $mtu
            Jc = $jc
            Jmin = $jmin
            Jmax = $jmax
            S1 = $s1
            S2 = $s2
            H1 = $h1
            H2 = $h2
            H3 = $h3
            H4 = $h4

            [Peer]
            PublicKey = $serverPubkey
            Endpoint = $endpoint
            AllowedIPs = $allowedIps
            PersistentKeepalive = 25
        """.trimIndent()
    }
}
