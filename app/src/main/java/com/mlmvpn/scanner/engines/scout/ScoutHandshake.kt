package com.mlmvpn.scanner.engines.scout

/** Evidence gates for probes which cannot carry data without taking the device VPN. */
object ScoutHandshake {
    data class Verdict(val status: ScoutStatus, val millis: Long? = null)
    fun openVpn(millis: Long?, method: String, error: String?): Verdict = when {
        method !in setOf("OPENVPN_TCP_TLS", "OPENVPN_UDP_TLS") -> Verdict(ScoutStatus.INCONCLUSIVE)
        error != null || millis == null || millis < 0 -> Verdict(ScoutStatus.FAILED)
        // The legacy probe accepts TLS alerts and byte counts, not a parsed certificate.
        else -> Verdict(ScoutStatus.INCONCLUSIVE)
    }
}
