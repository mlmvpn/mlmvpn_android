package com.windscribe.vpn.backend.openvpn

/** JNI names retained for the unmodified official Windscribe proxy binary. Loaded only in :wsproxy. */
class WSTunnelLib {
    external fun initialise(development: Boolean, logPath: String)
    external fun startProxy(listenAddress: String, remoteAddress: String, tunnelType: Int, mtu: Long, extraPadding: Boolean, tlsServerName: String)
    external fun socketFd(): Int
    external fun stop()
}
