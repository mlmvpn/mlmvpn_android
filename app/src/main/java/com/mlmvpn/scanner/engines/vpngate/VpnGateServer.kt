package com.mlmvpn.scanner.engines.vpngate

/**
 * One row of the VPN Gate public server list.
 *
 * Mirrors the 15 columns of the `*vpn_servers` CSV served by
 * http://www.vpngate.net/api/iphone/ — see [VpnGateCsvParser] for the format.
 */
data class VpnGateServer(
    val hostName: String,
    val ip: String,
    val score: Long,
    val ping: Int,
    val speedBps: Long,
    val countryLong: String,
    val countryShort: String,
    val numSessions: Int,
    val uptimeMs: Long,
    val totalUsers: Long,
    val totalTraffic: Long,
    val logType: String,
    val operator: String,
    val message: String,
    /** Base64 of a complete inline-cert .ovpn profile. Never blank — blank rows are dropped. */
    val configBase64: String,
    /**
     * The endpoint from the profile's `remote` line — the port OpenVPN actually dials, which
     * is often NOT the address in [ip] and is never the same thing as the web port. Extracted
     * once at parse time so the UI and the latency probe don't each decode a 13 KB profile.
     */
    val remoteHost: String = ip,
    val remotePort: Int = 0,
) {
    /**
     * Stable node id. Also the string MyVpnService reports through
     * `connectedNodeIdFlow`, so the UI can tell which row is live.
     */
    val id: String get() = "vpngate_$hostName"

    /** VPN Gate publishes speed in bytes/sec. */
    fun speedMbps(): Double = speedBps * 8.0 / 1_000_000.0

    /**
     * The relays run by the project itself (`public-vpn-*`, 219.100.37.x, port 443) rather
     * than by a volunteer on a home connection. They are markedly more stable, so the list
     * flags them.
     */
    val isOfficialRelay: Boolean get() = hostName.startsWith("public-vpn-")
}
