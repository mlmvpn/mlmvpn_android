package com.mlmvpn.scanner.engines.scout

/** Starting a check must never silently disconnect a VPN or overlap another scan. */
object ScoutAdmission {
    fun allowed(running: Boolean, scanning: Boolean, online: Boolean, ownVpn: Boolean, anyVpn: Boolean, pauseApproved: Boolean): Boolean =
        !running && !scanning && online && (!anyVpn || ownVpn) && (!ownVpn || pauseApproved)
}
