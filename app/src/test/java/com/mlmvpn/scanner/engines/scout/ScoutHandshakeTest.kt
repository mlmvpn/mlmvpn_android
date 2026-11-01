package com.mlmvpn.scanner.engines.scout

import org.junit.Assert.*
import org.junit.Test

class ScoutHandshakeTest {
    @Test fun `TCP connect alone never proves OpenVPN works`() {
        val verdict = ScoutHandshake.openVpn(25, "TCP_CONNECT", null)
        assertEquals(ScoutStatus.INCONCLUSIVE, verdict.status)
        assertNull(verdict.millis)
    }
    @Test fun `legacy TLS probes may accept alerts so cannot prove certificate receipt`() {
        for (method in listOf("OPENVPN_TCP_TLS", "OPENVPN_UDP_TLS")) {
            val verdict = ScoutHandshake.openVpn(180, method, null)
            assertEquals(ScoutStatus.INCONCLUSIVE, verdict.status)
            assertNull(verdict.millis)
        }
    }
    @Test fun `blocked TLS is failure but unsupported authentication is unknown`() {
        assertEquals(ScoutStatus.FAILED, ScoutHandshake.openVpn(null, "OPENVPN_TCP_TLS", "TLS_BLOCKED").status)
        assertEquals(ScoutStatus.INCONCLUSIVE, ScoutHandshake.openVpn(null, "OPENVPN_UDP_RESET", "UNSUPPORTED_CONTROL_AUTH").status)
        assertEquals(ScoutStatus.INCONCLUSIVE, ScoutHandshake.openVpn(12, "NEW_UNKNOWN_METHOD", null).status)
    }
}
