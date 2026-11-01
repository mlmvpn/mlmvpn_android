package com.mlmvpn.scanner.engines.cfnet

import java.security.MessageDigest

/**
 * A privacy-safe fingerprint of the current network, enough to choose strategies without collecting
 * anything personal. The raw network key (transport + default-gateway address, from
 * `GtCleanIp.networkKey`) is hashed before it is ever stored or logged, so neither the gateway
 * address nor an SSID survives. The Android lookup that fills this in lives in `CfNetEnv`.
 */
data class NetworkProfile(
    /** SHA-256 of the raw network key, truncated -- the per-network bucket id used by the store. */
    val key: String,
    val hasV4: Boolean = true,
    val hasV6: Boolean = false,
    /** The system resolver returned sane (non-bogon) answers for a known name. */
    val dnsSane: Boolean = true,
    /** A coarse carrier/transport tag ("cell", "wifi", or a carrier code) -- never an SSID. */
    val carrier: String = "unknown",
    val ts: Long = 0,
) {
    /**
     * The part of the profile that, if it changes, means old confidence should not be trusted
     * blindly: a different network, or the address families / DNS behaviour flipped. The timestamp
     * is deliberately excluded so the same network keeps its learning across runs.
     */
    fun signature(): String = "$key|${if (hasV4) 1 else 0}${if (hasV6) 1 else 0}${if (dnsSane) 1 else 0}"

    companion object {
        /** SHA-256 of [raw], hex, first 16 chars -- stable per network, reveals nothing. */
        fun hashKey(raw: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return d.joinToString("") { "%02x".format(it) }.take(16)
        }

        val UNKNOWN = NetworkProfile(key = hashKey("unknown"))
    }
}
