package com.mlmvpn.scanner.engines.vpngate

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.mlmvpn.scanner.MyVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The only place that knows how a VPN Gate connect is wired to [MyVpnService].
 *
 * Connecting goes THROUGH MyVpnService rather than around it. That is what keeps
 * `isRunningFlow`, `connectedNodeIdFlow`, `connectionPhaseFlow` and the quick-settings tile
 * honest for free, and it means the teardown of whatever engine was running (Xray, GST,
 * Aether, AmneziaWG) is the existing, already-tested code path — not a second one written here.
 */
object VpnGateController {

    private const val TAG = "VpnGateController"

    /**
     * Returns the consent Intent to launch, or null when permission is already granted.
     * One consent covers OpenVPNService too — it runs under the same UID.
     */
    fun needsConsent(context: Context): Intent? = VpnService.prepare(context)

    /**
     * Writes the profile out and asks MyVpnService to dispatch it. Call only after
     * [needsConsent] has returned null.
     */
    suspend fun connect(context: Context, server: VpnGateServer, ovpnText: String) =
        withContext(Dispatchers.IO) {
            val ctx = context.applicationContext

            // Written only when we still have one. Servers restored from the archive carry
            // metadata but no profile — the SoftEther path doesn't need it, and the OpenVPN
            // path is only reachable for servers that came straight from a live fetch.
            if (ovpnText.isNotBlank()) {
                val target = VpnGateEngine.pendingFile(ctx)
                target.parentFile?.mkdirs()
                target.writeText(ovpnText)
            }

            Log.d(TAG, "connect(): ${server.hostName} (${server.countryShort})")

            // SoftEther's native SSL-VPN rather than OpenVPN — see SoftEtherEngine for why.
            // It dials the server's own address, not the OpenVPN `remote` from the profile,
            // because the two are separate listeners.
            ctx.startService(
                Intent(ctx, MyVpnService::class.java).apply {
                    putExtra("NODE_URI", SoftEtherEngine.uriFor(server.ip))
                    putExtra("NODE_ID", server.id)
                }
            )
        }

    /** Same STOP path every other engine uses. */
    fun disconnect(context: Context) {
        val ctx = context.applicationContext
        ctx.startService(
            Intent(ctx, MyVpnService::class.java).apply { action = "STOP" }
        )
    }
}
