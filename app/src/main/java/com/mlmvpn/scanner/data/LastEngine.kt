package com.mlmvpn.scanner.data

import android.content.Context

/**
 * What the user last connected with, so one button can bring it back.
 *
 * The app has two VPN services and about eight ways to reach them -- the node list, Quick Connect,
 * VPN Gate, Aether, the anti-sanction relay, the game booster, the five transports. Nothing wrote
 * down which of those was actually used, which is fine while the user is inside the app looking at
 * the screen that started it, and useless anywhere else: a Quick Settings tile has no screen and
 * no context, so without this it can only offer a guess or open the app and give up.
 *
 * Recorded at the one point that means it worked -- the service reporting CONNECTED / RUNNING --
 * rather than when a connect is attempted. A record of something that failed to come up is worse
 * than no record: the tile would replay it and fail the same way, with the tile blinking on and
 * off as its only explanation.
 */
object LastEngine {

    private const val PREFS = "app_settings"
    private const val KEY_KIND = "last_engine_kind"
    private const val KEY_URI = "last_engine_uri"
    private const val KEY_NODE_ID = "last_engine_node_id"
    private const val KEY_TRANSPORT = "last_engine_transport"

    private const val KEY_OVPN_PROFILE = "last_engine_ovpn_profile"
    private const val KEY_OVPN_ACCOUNT = "last_engine_ovpn_account"

    private const val KIND_XRAY = "xray"
    private const val KIND_TUNNEL = "tunnel"
    private const val KIND_OPENVPN = "openvpn"

    /** What to bring back, and with which service. */
    sealed class Record {
        /**
         * Anything that runs through [com.mlmvpn.scanner.MyVpnService].
         *
         * One case for all of them because the engine's identity is already in the URI -- a
         * SoftEther gateway, an Aether config and a VLESS node are told apart by their own
         * scheme and fields, not by a label this object would have to keep in step. [nodeId] is
         * carried alongside because it is what says whether this was a config from the user's own
         * list (in which case the right move is to race the list again, not replay one entry).
         */
        data class Xray(val uri: String, val nodeId: String?) : Record()

        /** One of the five transports, by [com.mlmvpn.scanner.ui.tunnel.Transport.id]. */
        data class Tunnel(val transportId: String) : Record()

        /** An OpenVPN profile (its own service, [com.mlmvpn.scanner.openvpn.OpenVpnService]), with its account. */
        data class OpenVpn(val profileId: String, val accountId: String) : Record()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * MAE is recorded by name only. Its config is compiled per network and carries MAE's sealed
     * secrets unsealed (the WARP key, the Worker's id, the user's configs): stored here it went
     * into Android backups and, replayed, brought one network's routes to another. The tile builds
     * a fresh one instead.
     */
    const val MAE_MARKER = "mae"

    /**
     * FLUX likewise, by name: its config is chosen per network from public nodes, and the tile
     * asks FLUX for this network's route instead of replaying another network's.
     */
    const val FLUX_MARKER = "flux"

    /** FENIX likewise: its config carries a short-lived pass, so the tile asks FENIX for a new one. */
    const val FENIX_MARKER = "fenix"
    /** The real config contains a private key. Reconnect through the encrypted provider repository. */
    const val WINDSCRIBE_MARKER = "windscribe"

    /**
     * The Amnezia engine (Amnezia, Proton) and Outline likewise: the config carries a private key, and
     * a Proton server's points at a relay port that exists only while that relay runs -- replayed raw
     * it reached nothing. The tile asks the engine to connect its last server again.
     */
    const val AMNEZIA_MARKER = "amnezia"
    const val OUTLINE_MARKER = "outline"

    private fun isMae(nodeId: String?) = nodeId == MAE_MARKER || nodeId == FLUX_MARKER || nodeId == FENIX_MARKER ||
        nodeId == WINDSCRIBE_MARKER || nodeId == AMNEZIA_MARKER || nodeId == OUTLINE_MARKER

    /**
     * An Amnezia server reached through Cloudflare, likewise by name: its config carries the app's
     * own WARP key, and its way in (the endpoint, the key) is chosen per network -- the tile asks the
     * Amnezia engine to connect that server again instead of replaying another network's carrier.
     */
    const val AMNEZIA_CF_MARKER = "amnezia-cf"

    private fun isAmneziaCf(uri: String): Boolean =
        uri.startsWith("{") && uri.contains(com.mlmvpn.scanner.engines.amnezia.AmzCarrier.REMARKS)

    fun recordXray(context: Context, uri: String?, nodeId: String?) {
        if (uri.isNullOrBlank()) return
        prefs(context).edit()
            .putString(KEY_KIND, KIND_XRAY)
            .putString(KEY_URI, when { isMae(nodeId) -> nodeId; isAmneziaCf(uri) -> AMNEZIA_CF_MARKER; else -> uri })
            .putString(KEY_NODE_ID, nodeId)
            .apply()
    }

    /** On OpenVPN's CONNECTED. Windscribe's own profiles are not recorded here: Windscribe records itself. */
    fun recordOpenVpn(context: Context, profileId: String?, accountId: String?) {
        if (profileId.isNullOrBlank() || accountId.isNullOrBlank()) return
        if (profileId.startsWith(com.mlmvpn.scanner.engines.windscribe.WindscribeEngine.PROFILE_PREFIX)) return
        prefs(context).edit()
            .putString(KEY_KIND, KIND_OPENVPN)
            .putString(KEY_OVPN_PROFILE, profileId)
            .putString(KEY_OVPN_ACCOUNT, accountId)
            .apply()
    }

    fun recordTunnel(context: Context, transportId: String) {
        if (transportId.isBlank()) return
        prefs(context).edit()
            .putString(KEY_KIND, KIND_TUNNEL)
            .putString(KEY_TRANSPORT, transportId)
            .apply()
    }

    fun read(context: Context): Record? {
        val p = prefs(context)
        return when (p.getString(KEY_KIND, null)) {
            KIND_XRAY -> p.getString(KEY_URI, null)
                ?.takeIf { it.isNotBlank() }
                ?.let { uri ->
                    val nodeId = p.getString(KEY_NODE_ID, null)
                    // A record from before the marker still holds a whole MAE config: dropped here.
                    if (isMae(nodeId) && uri != nodeId) p.edit().putString(KEY_URI, nodeId).apply()
                    Record.Xray(if (isMae(nodeId)) nodeId!! else uri, nodeId)
                }

            KIND_TUNNEL -> p.getString(KEY_TRANSPORT, null)
                ?.takeIf { it.isNotBlank() }
                ?.let { Record.Tunnel(it) }

            KIND_OPENVPN -> {
                val profile = p.getString(KEY_OVPN_PROFILE, null)
                val account = p.getString(KEY_OVPN_ACCOUNT, null)
                if (profile.isNullOrBlank() || account.isNullOrBlank()) null else Record.OpenVpn(profile, account)
            }

            else -> null
        }
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_KIND)
            .remove(KEY_URI)
            .remove(KEY_NODE_ID)
            .remove(KEY_TRANSPORT)
            .remove(KEY_OVPN_PROFILE)
            .remove(KEY_OVPN_ACCOUNT)
            .apply()
    }
}
