package com.mlmvpn.scanner.engines.subgenerator

data class SubLinkConfig(
    val slug: String,
    val name: String,
    val mappedGroupName: String?,
    val expiryTimestamp: Long,
    val createdAt: Long = System.currentTimeMillis(),
    var hits: Int = 0, // Cache for UI display
    val accountId: String = "" // Support for multiple accounts
)

data class CloudflareKVData(
    val configs: String,
    val expiry: Long
)
