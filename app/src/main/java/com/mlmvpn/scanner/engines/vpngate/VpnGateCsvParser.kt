package com.mlmvpn.scanner.engines.vpngate

/**
 * Parser for the VPN Gate `*vpn_servers` CSV (http://www.vpngate.net/api/iphone/).
 *
 * Exact shape of the payload, verified against a captured sample:
 *
 *     *vpn_servers
 *     #HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,
 *      TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
 *     public-vpn-123,219.100.37.1,1234,12,58000000,Japan,JP,45,...,<base64>
 *     ...
 *     *
 *
 * Deliberately free of Android imports so it can be unit-tested on the JVM.
 */
object VpnGateCsvParser {

    private const val EXPECTED_COLUMNS = 15

    /**
     * Never throws: a malformed row is skipped, not fatal. A single bad line in a
     * 300-row live response must not cost the user the whole list.
     */
    fun parse(csv: String): List<VpnGateServer> {
        val out = ArrayList<VpnGateServer>()

        for (rawLine in csv.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            // Drops both the leading "*vpn_servers" sentinel and the trailing bare "*".
            if (line.startsWith("*")) continue
            // The column header.
            if (line.startsWith("#")) continue

            // A plain split is safe: VPN Gate replaces commas inside the Operator and
            // Message fields with underscores before serving the CSV.
            val p = line.split(",")
            if (p.size < EXPECTED_COLUMNS) continue

            // Rows without an OpenVPN profile (some academic servers) cannot be connected.
            val configBase64 = p[14].trim()
            if (configBase64.isEmpty()) continue

            val hostName = p[0].trim()
            if (hostName.isEmpty()) continue

            val countryShort = p[6].trim().uppercase()
                .takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } } ?: "XX"

            // One decode per row here saves the UI and the latency probe from doing it per
            // render. ~13 KB each, ~100 rows — cheap once, wasteful repeatedly.
            val remote = try {
                val ovpn = String(
                    android.util.Base64.decode(configBase64, android.util.Base64.DEFAULT),
                    Charsets.UTF_8,
                )
                OvpnProfileBuilder.remoteOf(ovpn)
            } catch (e: Exception) {
                null
            }

            out.add(
                VpnGateServer(
                    remoteHost = remote?.first ?: p[1].trim(),
                    remotePort = remote?.second ?: 0,
                    hostName = hostName,
                    ip = p[1].trim(),
                    score = p[2].trim().toLongOrNull() ?: 0L,
                    ping = p[3].trim().toIntOrNull() ?: -1,
                    speedBps = p[4].trim().toLongOrNull() ?: 0L,
                    countryLong = p[5].trim(),
                    countryShort = countryShort,
                    numSessions = p[7].trim().toIntOrNull() ?: 0,
                    uptimeMs = p[8].trim().toLongOrNull() ?: 0L,
                    totalUsers = p[9].trim().toLongOrNull() ?: 0L,
                    totalTraffic = p[10].trim().toLongOrNull() ?: 0L,
                    logType = p[11].trim(),
                    operator = p[12].trim(),
                    message = p[13].trim(),
                    configBase64 = configBase64,
                )
            )
        }

        return out
    }
}
