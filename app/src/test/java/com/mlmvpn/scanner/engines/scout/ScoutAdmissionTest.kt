package com.mlmvpn.scanner.engines.scout

import org.junit.Assert.*
import org.junit.Test

class ScoutAdmissionTest {
    @Test fun `an active app VPN requires explicit pause consent`() {
        assertFalse(ScoutAdmission.allowed(running = false, scanning = false, online = true, ownVpn = true, anyVpn = true, pauseApproved = false))
        assertTrue(ScoutAdmission.allowed(running = false, scanning = false, online = true, ownVpn = true, anyVpn = true, pauseApproved = true))
    }
    @Test fun `pause consent does not authorize another VPN or concurrent work`() {
        assertFalse(ScoutAdmission.allowed(false, false, true, false, true, true))
        assertFalse(ScoutAdmission.allowed(true, false, true, false, false, true))
        assertFalse(ScoutAdmission.allowed(false, true, true, false, false, true))
        assertFalse(ScoutAdmission.allowed(false, false, false, false, false, true))
    }
    @Test fun `bare online network can start without a confirmation`() {
        assertTrue(ScoutAdmission.allowed(false, false, true, false, false, false))
    }
}
