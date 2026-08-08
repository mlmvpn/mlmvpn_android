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

        // Built line-by-line (no trimIndent) so the conditional obfuscation block can't corrupt the
        // indentation. jc == 0 means "pure WireGuard, no obfuscation" (for standard-WireGuard peers
        // like Cloudflare WARP) -- we omit the AmneziaWG junk/header lines entirely in that case, so
        // the tunnel is vanilla WireGuard; writing zeroed junk params can desync a standard server.
        val sb = StringBuilder()
        sb.append("[Interface]\n")
        sb.append("PrivateKey = ").append(privateKey).append('\n')
        sb.append("Address = ").append(address).append('\n')
        sb.append("DNS = ").append(dns).append('\n')
        sb.append("MTU = ").append(mtu).append('\n')
        if (jc > 0) {
            sb.append("Jc = ").append(jc).append('\n')
            sb.append("Jmin = ").append(jmin).append('\n')
            sb.append("Jmax = ").append(jmax).append('\n')
            sb.append("S1 = ").append(s1).append('\n')
            sb.append("S2 = ").append(s2).append('\n')
            sb.append("H1 = ").append(h1).append('\n')
            sb.append("H2 = ").append(h2).append('\n')
            sb.append("H3 = ").append(h3).append('\n')
            sb.append("H4 = ").append(h4).append('\n')
        }
        sb.append('\n')
        sb.append("[Peer]\n")
        sb.append("PublicKey = ").append(serverPubkey).append('\n')
        sb.append("Endpoint = ").append(endpoint).append('\n')
        sb.append("AllowedIPs = ").append(allowedIps).append('\n')
        sb.append("PersistentKeepalive = 25\n")
        return sb.toString()
    }
}
