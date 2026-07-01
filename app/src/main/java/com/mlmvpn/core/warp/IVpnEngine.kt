package com.mlmvpn.core.warp

import android.content.Context

interface IVpnEngine {
    /**
     * Prepares and starts the VPN engine.
     * @param context Application Context
     * @param config The raw configuration string or identifier to start
     * @param localPort The local SOCKS5/HTTP port the engine should bind to
     * @return true if successfully started, false otherwise
     */
    suspend fun start(context: Context, config: String, localPort: Int): Boolean

    /**
     * Stops the VPN engine and cleans up resources.
     */
    fun stop()
}
