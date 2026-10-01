package com.mlmvpn.scanner.openvpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

object OpenVpnRuntime {
    internal val mutable = MutableStateFlow(OpenVpnConnection())

    /** The "account" of a profile that brings its own credentials, or needs none. */
    const val SELF = "@profile"
    val connection = mutable.asStateFlow()
    fun connect(context: Context, profile: String, account: String) {
        ContextCompat.startForegroundService(context, Intent(context, OpenVpnService::class.java)
            .setAction(OpenVpnService.CONNECT).putExtra("profile", profile).putExtra("account", account))
    }
    fun disconnect(context: Context) {
        if (connection.value.active) context.startService(Intent(context, OpenVpnService::class.java).setAction(OpenVpnService.DISCONNECT))
    }
    suspend fun release(context: Context) {
        if (!connection.value.active) return
        disconnect(context)
        withTimeout(10_000) { while (connection.value.active) delay(50) }
    }
}
