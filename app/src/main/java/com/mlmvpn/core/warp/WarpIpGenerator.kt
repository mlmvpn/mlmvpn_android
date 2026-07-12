package com.mlmvpn.core.warp

import kotlin.random.Random

object WarpIpGenerator {

    // Full list of WARP-eligible UDP ports published by Cloudflare. Using the full set instead of
    // a tiny weighted subset dramatically increases the chance that at least one ip:port combination
    // is reachable on a heavily-filtered Iranian ISP — if 2408 is throttled on a given path, 4500,
    // 854, 1701, etc. often are not. Discovery rotates across all of them.
    private val warpPorts = listOf(
        500, 854, 859, 864, 878, 880, 890, 891, 894, 903, 908, 928, 934, 939, 942, 943, 945, 946,
        955, 968, 987, 988, 1002, 1010, 1034, 1050, 1094, 1126, 1134, 1174, 1248, 1325, 1337, 1620,
        1672, 1701, 1816, 1843, 2371, 2408, 2506, 3138, 3476, 3581, 3854, 4177, 4198, 4233, 4500,
        4750, 5343, 5439, 5700, 6781, 7028, 7346, 7362, 8044, 8999, 9198, 20600
    )

    // Cloudflare's full anycast IPv4 edge network. WARP UDP traffic gets anycast-routed to
    // whichever nearby Cloudflare edge node can serve it, not just the handful of "official"
    // 162.159.x/188.114.x WARP subnets -- so searching across all of Cloudflare's published
    // ranges finds vastly more usable endpoints, and different edge PoPs suit different users'
    // network paths. In Iran specifically, the classic WARP subnets are often filtered first,
    // so the broader CDN ranges are where reachable IPs tend to survive.
    private val cidrRanges = listOf(
        "103.21.244.0/22",
        "103.22.200.0/22",
        "103.31.4.0/22",
        "104.16.0.0/13",
        "104.24.0.0/14",
        "108.162.192.0/18",
        "131.0.72.0/22",
        "141.101.64.0/18",
        "162.158.0.0/15",
        "162.159.192.0/24",
        "162.159.193.0/24",
        "162.159.195.0/24",
        "172.64.0.0/13",
        "172.70.0.0/15",
        "173.245.48.0/20",
        "188.114.96.0/20",
        "190.93.240.0/20",
        "197.234.240.0/22",
        "198.41.128.0/17"
    )

    // The "official" WARP anycast subnets -- the ones warp scanners classically target. In Iran
    // these are usually the FIRST to get blackholed, which is exactly why the lab tests them
    // separately from the broader CDN ranges to see which family still answers.
    private val officialWarpCidrs = listOf(
        "162.159.192.0/24",
        "162.159.193.0/24",
        "162.159.195.0/24",
        "188.114.96.0/20"
    )

    // Everything else = Cloudflare's general CDN edge ranges.
    private val cdnCidrs = cidrRanges.filterNot { it in officialWarpCidrs }

    // Cloudflare WARP IPv6 endpoint prefixes (engage.cloudflareclient.com resolves into these).
    // IPv6 is frequently far less filtered on Iranian ISPs than IPv4, so it's a distinct lab axis.
    private val warpV6Prefixes = listOf(
        "2606:4700:d0::",
        "2606:4700:d1::"
    )

    private data class Range(val base: Long, val size: Long)

    private fun buildRanges(cidrs: List<String>): List<Range> = cidrs.mapNotNull { cidr ->
        val parts = cidr.split("/")
        if (parts.size != 2) return@mapNotNull null
        val prefixLen = parts[1].toIntOrNull() ?: return@mapNotNull null
        val ipLong = ipToLong(parts[0]) ?: return@mapNotNull null
        val size = 1L shl (32 - prefixLen)
        val mask = (0xFFFFFFFFL shl (32 - prefixLen)) and 0xFFFFFFFFL
        Range(ipLong and mask, size)
    }

    private val ranges: List<Range> by lazy { buildRanges(cidrRanges) }
    private val officialRanges: List<Range> by lazy { buildRanges(officialWarpCidrs) }
    private val cdnRanges: List<Range> by lazy { buildRanges(cdnCidrs) }

    private val totalAddresses: Long by lazy { ranges.sumOf { it.size } }

    private fun ipToLong(ip: String): Long? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        var result = 0L
        for (part in parts) {
            val octet = part.toLongOrNull() ?: return null
            result = (result shl 8) or (octet and 0xFF)
        }
        return result
    }

    private fun longToIp(value: Long): String {
        return "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"
    }

    private fun randomIpInRanges(rangeList: List<Range> = ranges): String {
        // Weighted by range size, so a huge /13 gets proportionally more picks than a tiny /24.
        val total = rangeList.sumOf { it.size }
        var offset = Random.nextLong(total)
        for (range in rangeList) {
            if (offset < range.size) return longToIp(range.base + offset)
            offset -= range.size
        }
        return longToIp(rangeList.last().base)
    }

    /** A random IPv4 from the official WARP subnets only. */
    fun officialWarpEndpoint(): String = "${randomIpInRanges(officialRanges)}:${warpPorts.random()}"

    /** A random IPv4 from the general Cloudflare CDN ranges only. */
    fun cdnEndpoint(): String = "${randomIpInRanges(cdnRanges)}:${warpPorts.random()}"

    /**
     * A random WARP IPv6 endpoint, formatted "[ipv6]:port" so the last ':' split used elsewhere
     * still separates the port correctly.
     */
    fun randomIpv6Endpoint(): String {
        val prefix = warpV6Prefixes.random() // ends with "::"
        // Fill the last 64 bits with random hextets for a random host within the /48-ish prefix.
        val suffix = (0 until 4).joinToString(":") { Random.nextInt(0x10000).toString(16) }
        val addr = prefix + suffix
        return "[$addr]:${warpPorts.random()}"
    }

    /**
     * Generates a single random IPv4:port endpoint from across Cloudflare's full edge network,
     * with a random port drawn from the full WARP port list.
     */
    fun generateRandomEndpoint(): String {
        val ip = randomIpInRanges()
        val port = warpPorts.random()
        return "$ip:$port"
    }

    /**
     * Generates up to [count] distinct random endpoints. With millions of possible ip:port
     * combinations, running out within a single batch essentially never happens; the attempt
     * cap just guards against a pathological edge case rather than a realistic one.
     */
    fun generateRandomEndpoints(count: Int = 100): List<String> {
        val endpoints = mutableSetOf<String>()
        var attempts = 0
        val maxAttempts = count * 20
        while (endpoints.size < count && attempts < maxAttempts) {
            endpoints.add(generateRandomEndpoint())
            attempts++
        }
        return endpoints.toList()
    }

    /** All WARP-eligible ports, in case a caller wants to fan out one IP across many ports. */
    fun allPorts(): List<Int> = warpPorts
}
