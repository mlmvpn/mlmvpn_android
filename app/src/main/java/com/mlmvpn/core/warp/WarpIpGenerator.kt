package com.mlmvpn.core.warp

import kotlin.random.Random

object WarpIpGenerator {

    private val warpPorts = listOf(
        500, 1701, 2404, 2408, 4500, 2408, 2408, 2408 // Weighted towards 2408
    )

    // Extensive list of Cloudflare WARP IPv4 CIDRs
    private val warpIpv4Ranges = listOf(
        "162.159.192",
        "162.159.193",
        "162.159.195",
        "188.114.96",
        "188.114.97",
        "188.114.98",
        "188.114.99"
    )

    /**
     * Generates a single random IPv4 endpoint.
     */
    fun generateRandomEndpoint(): String {
        val baseV4 = warpIpv4Ranges.random()
        val lastOctet = Random.nextInt(1, 255)
        val port = warpPorts.random()
        return "$baseV4.$lastOctet:$port"
    }

    /**
     * Generates a fixed number of IPv4 endpoints.
     */
    fun generateRandomEndpoints(count: Int = 100): List<String> {
        val endpoints = mutableSetOf<String>()
        while (endpoints.size < count) {
            endpoints.add(generateRandomEndpoint())
        }
        return endpoints.toList()
    }
}
