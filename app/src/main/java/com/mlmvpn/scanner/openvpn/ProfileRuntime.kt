package com.mlmvpn.scanner.openvpn

/**
 * What a stored profile becomes at connect time. The stored text is never changed; these are
 * the edits the bundled core and this network need, applied fresh on every connect.
 *
 *  - **The data cipher.** The TunnelBear profiles name their cipher only in `data-ciphers`, which
 *    the OpenVPN3 core ignores ("Unsupported option"). It then offers GCM alone and falls back to
 *    BF-CBC, which the servers refuse -- so even a handshake that got through would have had no
 *    data channel. A plain `cipher` line carries the same choice in a form the core reads.
 *  - **TCP for TunnelBear.** Their profiles are UDP 443, and from Iran that session is cut after
 *    the first exchange: the server answers the reset, then no TLS record ever comes back
 *    (measured 2026-09-27, 40 s CONNECTION_TIMEOUT). The same servers speak OpenVPN over TCP on
 *    7011 -- a reset sent there from the phone is answered -- and a TCP stream is what
 *    [OpenVpnSplitRelay] can reshape.
 */
object ProfileRuntime {
    const val TUNNELBEAR_TCP_PORT = 7011

    fun isTunnelBear(profile: Profile): Boolean =
        profile.remotes.any { it.host.endsWith(".lazerpenguin.com", ignoreCase = true) }

    /**
     * @param tcp move a TunnelBear profile to TCP [TUNNELBEAR_TCP_PORT].
     * @param relayPort the loopback port of an [OpenVpnSplitRelay] that dials the server itself.
     */
    fun effective(profile: Profile, tcp: Boolean, relayPort: Int? = null): String {
        // The file's own credentials go to the core through its API, like an account's; in the
        // config they become the bare directive that tells it the server wants them.
        val raw = profile.config.replace("\r\n", "\n").split("\n")
        val lines = mutableListOf<String>()
        var inAuth = false
        for (l in raw) {
            val t = l.trim()
            if (t == "<auth-user-pass>") { inAuth = true; lines += "auth-user-pass"; continue }
            if (inAuth) { if (t == "</auth-user-pass>") inAuth = false; continue }
            lines += l
        }
        val directive = { l: String -> l.trim().substringBefore(' ').lowercase() }

        if (lines.none { directive(it) == "cipher" }) {
            val first = lines.firstOrNull { directive(it) == "data-ciphers" }
                ?.trim()?.substringAfter(' ')?.split(':')?.firstOrNull()?.trim()
            if (!first.isNullOrBlank()) lines.add("cipher $first")
        }

        val moveToTcp = (tcp || relayPort != null) && isTunnelBear(profile)
        if (moveToTcp) {
            for (i in lines.indices) {
                val t = lines[i].trim()
                when (directive(t)) {
                    "proto" -> lines[i] = "proto tcp-client"
                    "remote" -> {
                        val parts = t.split(Regex("\\s+"))
                        if (parts.size >= 2) lines[i] = if (relayPort != null) "remote 127.0.0.1 $relayPort tcp-client"
                            else "remote ${parts[1]} $TUNNELBEAR_TCP_PORT tcp-client"
                    }
                    "explicit-exit-notify" -> lines[i] = ""
                }
            }
        }
        return lines.joinToString("\n")
    }
}
