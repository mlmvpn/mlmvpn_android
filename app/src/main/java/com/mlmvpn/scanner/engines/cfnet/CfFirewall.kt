package com.mlmvpn.scanner.engines.cfnet

/**
 * The two filtering families measured on Iranian lines (summary of 2026-10): which Cloudflare
 * handshake a line lets through. The SIM does not tell -- an MCI SIM can sit behind Irancell's
 * firewall and the other way round, and most other ISPs use one of the two -- so the family is
 * MEASURED per network, from the strategies that really carried data there (and, before any did,
 * from a quick probe of the two headline handshakes).
 */
enum class FirewallFamily(val key: String) {
    /**
     * «همراه اول»-type: a worker/CDN config passes on IPv4 with ECH (chrome, `cloudflare-ech.com+udp://1.1.1.1`,
     * no finalMask, no cipherSuites), and on IPv6 with Chrome -- plus F&F's tlshello mask where the
     * config's own name is filtered.
     */
    ECH("ech"),

    /**
     * «ایرانسل»-type: on IPv4 only F&F passes -- fingerprint `unsafe`, PattNG's semi-python cipher
     * suites, finalMask tlshello-0-len, and the ALPN the transport needs (http/1.1 for WebSocket,
     * h2,http/1.1 for XHTTP).
     */
    FF("ff"),

    /** Nothing needs changing: the config's own handshake (or plain Chrome) passes. */
    OPEN("open");

    companion object {
        /** The edge the summary names for its IPv4 ways (ECH, F&F): any address of 188.114.96.0/20 is the same edge. */
        const val EDGE_V4 = "188.114.98.0"

        /** The edge the summary names for its IPv6 way (Chrome, nothing else changed). */
        const val EDGE_V6 = "2a06:98c1:3121::7"

        /**
         * The summary's three ways for a worker/CDN config, as DATA strategy ids, in the order to prove
         * them on a line of [family]: that family's own ways first. ECH and IPv6 with Chrome are the
         * «همراه اول»-type's, F&F the «ایرانسل»-type's; unknown starts with the first.
         */
        fun headline(family: FirewallFamily?): List<String> = when (family) {
            FF -> listOf("dat_fragfp_v4", "dat_ech_v4", "dat_chrome_v6")
            else -> listOf("dat_ech_v4", "dat_chrome_v6", "dat_fragfp_v4")
        }

        fun of(key: String?): FirewallFamily? = entries.find { it.key == key }

        /** The family a DATA strategy belongs to, or null for one that says nothing about it. */
        fun of(s: Strategy): FirewallFamily? {
            val d = s.data ?: return null
            return when (d.shape) {
                TunnelShape.ECH -> ECH
                TunnelShape.FRAG_FP, TunnelShape.XHTTP_H2_FRAG -> FF
                // Chrome (with or without the mask) on Cloudflare's IPv6 is the MCI-type's second way.
                TunnelShape.CHROME, TunnelShape.FRAG_CHROME -> if (d.family == Family.V6) ECH else if (d.shape == TunnelShape.CHROME) OPEN else null
                TunnelShape.AS_IS, TunnelShape.FIREFOX, TunnelShape.SAFARI, TunnelShape.RANDOMIZED,
                TunnelShape.IOS, TunnelShape.EDGE, TunnelShape.ANDROID -> if (d.family == Family.V4) OPEN else null
                else -> null
            }
        }
    }
}
