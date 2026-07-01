package com.mlmvpn.scanner.utils

import java.security.SecureRandom
import java.util.Locale

object AntiDpi {

    private val random = SecureRandom()

    val BLACKLISTED_KEYWORDS = listOf(
        "bpb", "panel", "vpn", "proxy", "tunnel", "v2ray", "xray",
        "trojan", "clash", "surge", "shadowsocks", "ss", "ssr",
        "vless", "vmess", "wireguard", "warp", "filter", "bypass",
        "freedom", "gfw", "censorship", "mlm"
    )

    private val SAFE_PREFIXES = listOf(
        "app-core", "edge-relay", "main-thunder", "cloud-sync",
        "data-stream", "net-bridge", "api-hub", "web-flow",
        "fast-route", "smart-gate", "node-link", "micro-svc",
        "auto-scale", "load-bal", "cdn-edge", "cache-opt",
        "log-svc", "auth-api", "user-svc", "task-run",
        "event-bus", "msg-queue", "file-io", "db-proxy"
    )

    private val SAFE_SUBDOMAIN_PREFIXES = listOf(
        "dev-team", "eng-ops", "platform", "infra-core",
        "sre-tools", "ci-runner", "build-svc", "deploy-agent",
        "monitor-hub", "test-env", "staging-api", "prod-relay"
    )

    /**
     * Randomize the case of alphabetical characters in the domain
     */
    fun generateMixedCaseSNI(domain: String?): String? {
        if (domain.isNullOrEmpty()) return domain
        val sb = java.lang.StringBuilder()
        for (char in domain) {
            if (char.isLetter()) {
                if (random.nextBoolean()) {
                    sb.append(char.uppercaseChar())
                } else {
                    sb.append(char.lowercaseChar())
                }
            } else {
                sb.append(char)
            }
        }
        return sb.toString()
    }

    /**
     * Generate a safe worker name avoiding blacklisted keywords
     */
    fun generateSafeWorkerName(): String {
        val prefix = SAFE_PREFIXES[random.nextInt(SAFE_PREFIXES.size)]
        val suffix = generateHex(6)
        return "$prefix-$suffix"
    }

    /**
     * Generate a safe subdomain avoiding blacklisted keywords
     */
    fun generateSafeSubdomain(): String {
        val prefix = SAFE_SUBDOMAIN_PREFIXES[random.nextInt(SAFE_SUBDOMAIN_PREFIXES.size)]
        val suffix = generateHex(6)
        return "$prefix-$suffix"
    }

    /**
     * Apply Mixed-Case SNI to sni and host parameters of a VLESS/Trojan URI
     */
    fun applySniCamouflage(uri: String?): String {
        if (uri.isNullOrEmpty()) return ""
        if (!uri.startsWith("vless://") && !uri.startsWith("trojan://")) return uri

        try {
            val hashIdx = uri.indexOf('#')
            val fragment = if (hashIdx != -1) uri.substring(hashIdx) else ""
            val uriWithoutHash = if (hashIdx != -1) uri.substring(0, hashIdx) else uri

            val queryIdx = uriWithoutHash.indexOf('?')
            if (queryIdx == -1) return uri

            val baseUri = uriWithoutHash.substring(0, queryIdx)
            val queryString = uriWithoutHash.substring(queryIdx + 1)

            val params = queryString.split("&").toMutableList()
            val newParams = params.map { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0]
                    val value = parts[1]
                    if (key == "sni" || key == "host") {
                        val mixedValue = generateMixedCaseSNI(value) ?: value
                        "$key=$mixedValue"
                    } else {
                        param
                    }
                } else {
                    param
                }
            }

            return "$baseUri?${newParams.joinToString("&")}$fragment"
        } catch (e: Exception) {
            e.printStackTrace()
            return uri
        }
    }

    fun containsBlacklistedKeyword(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        val lowerName = name.lowercase(Locale.ROOT)
        return BLACKLISTED_KEYWORDS.any { lowerName.contains(it) }
    }

    fun generateSafeKVName(): String {
        val names = listOf("app-store", "config-db", "cache-store", "kv-data", "state-db", "settings-store")
        val name = names[random.nextInt(names.size)]
        val suffix = generateHex(4)
        return "$name-$suffix"
    }

    private fun generateHex(length: Int): String {
        val bytes = ByteArray(length / 2)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
