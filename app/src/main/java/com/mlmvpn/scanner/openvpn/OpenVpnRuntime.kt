package com.mlmvpn.scanner.openvpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import com.mlmvpn.scanner.utils.GameSessionOwner

object OpenVpnRuntime {
    internal val mutable = MutableStateFlow(OpenVpnConnection())

    /** The "account" of a profile that brings its own credentials, or needs none. */
    const val SELF = "@profile"
    val connection = mutable.asStateFlow()
    fun connect(context: Context, profile: String, account: String, owner: String? = null) {
        ContextCompat.startForegroundService(context, Intent(context, OpenVpnService::class.java)
            .setAction(OpenVpnService.CONNECT).putExtra("profile", profile).putExtra("account", account).putExtra(GameSessionOwner.EXTRA, owner))
    }
    fun disconnect(context: Context, owner: String? = null) {
        if (owner != null || connection.value.active) com.mlmvpn.scanner.utils.SafeStart.service(context,
            Intent(context, OpenVpnService::class.java).setAction(OpenVpnService.DISCONNECT).putExtra(GameSessionOwner.EXPECTED, owner))
    }
    suspend fun release(context: Context, owner: String? = null) {
        if (owner == null && !connection.value.active) return
        disconnect(context, owner)
        withTimeout(10_000) { while (connection.value.active) delay(50) }
    }
}
