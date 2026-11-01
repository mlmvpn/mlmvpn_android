package com.mlmvpn.scanner.engines.rstaspoof

import android.content.Context
import android.content.Intent
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.models.VpnNode
import com.mlmvpn.scanner.utils.LocalPort
import com.mlmvpn.scanner.utils.NetworkSettings
import com.mlmvpn.scanner.utils.VpnConfig

/**
 * The SNI engine and the config that rides on it, as ONE connection.
 *
 * They are two pieces and the screen used to show them as two: a dial that started the local TLS
 * front, and then nothing — the user had to work out for themselves that the front carries no
 * traffic on its own, leave for the connection list, find the folder of SNI configs and connect
 * one of them there. Two buttons on two screens for one thing, and no hint on either screen that
 * the other existed. The overwhelmingly common failure was turning on the engine, seeing "running",
 * and concluding the app was broken because nothing was unblocked.
 *
 * There is one button now, and this object is what makes that honest. The ordering is not
 * cosmetic: the config points at `127.0.0.1:40443`, so the front has to be listening before the
 * core dials it or the connection fails at the first packet.
 *
 * The sequencing itself already lives in [MyVpnService] — it detects an SNI config by where it
 * points and claims it through [RstaSpoofManager.acquire] before starting the core -- and gives it
 * back when the service is destroyed, which is what keeps the front from outliving the connection
 * that needed it. So this object does not re-implement any of that; it selects the route, picks
 * the config, and starts the service. What it adds is the part the service cannot know: WHICH
 * config, and which route.
 */
object SniSession {

    /** Where every SNI-spoof config points. It is what makes a config an SNI config. */
    const val LOCAL_HOST = "127.0.0.1"
    const val LOCAL_PORT = 40443

    /**
     * Is this config one that rides the SNI front?
     *
     * By destination rather than by folder name. A folder is something the user can rename or drag
     * a config out of; the address is the thing that decides whether the front has to be up.
     */
    fun isSniUri(uri: String): Boolean {
        // The node list asks this of every config on every update. A link that names no loopback
        // address cannot point at the front, and a vmess blob is decoded once: full parses of
        // thousands of links held the main thread for seconds (freeze reports, 1.2.41).
        if (!uri.startsWith("vmess://", ignoreCase = true)) {
            if (!uri.contains(LOCAL_HOST)) return false
        } else {
            sniCache[uri]?.let { return it }
        }
        val cfg = VpnConfig.parseUri(uri)
        val sni = cfg != null && cfg.address == LOCAL_HOST && cfg.port == LOCAL_PORT
        if (uri.startsWith("vmess://", ignoreCase = true)) {
            if (sniCache.size > 4_096) sniCache.clear()
            sniCache[uri] = sni
        }
        return sni
    }

    /** vmess links already looked at: their address is inside base64, so there is no cheap test. */
    private val sniCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun isSniNode(node: VpnNode): Boolean = isSniUri(node.uri)

    /**
     * The panels whose configs can be turned into SNI configs at all.
     *
     * Converting means repointing a config at `127.0.0.1:40443` and letting the local front dial
     * a Cloudflare EDGE address under a forged handshake name. The edge then routes the inner
     * connection by the host the config still carries -- so the config's own server has to be
     * something that edge can reach, which means a Cloudflare Worker. BPB, EDG, Nahan and MLM are
     * exactly that, and so are their combined configs: a combine only rewrites the endpoint, so
     * the panel a config came from survives it.
     *
     * A manually added, imported or subscription config almost always points at a server of its
     * own, and Cloudflare's edge has no route to it. Converting one produced a config that could
     * not connect on any entry point, and that reads to the user as "SNI is broken" rather than
     * as "this config was never eligible" -- which is why the ineligible ones are not offered
     * instead of being offered and failing.
     */
    val CLOUD_PANELS = setOf("BPB", "EDG", "NHN", "MLM")

    /** Whether this config is one the SNI section may build from. */
    fun isConvertible(node: VpnNode): Boolean =
        !NodeManager.isProtected(node) &&
            !isSniNode(node) &&
            node.engineType in CLOUD_PANELS

    /**
     * Delete SNI configs by id, and report how many actually went.
     *
     * Here rather than in the screen because the screen is not the only thing that will ever want
     * it, and because "which of these is an SNI config" is this object's question: an id that
     * names something else is ignored, so a stale selection cannot delete an unrelated config.
     */
    fun delete(context: Context, ids: Set<String>): Int {
        if (ids.isEmpty()) return 0
        val nm = NodeManager(context.applicationContext)
        val gone = synchronized(nm.nodes) {
            val doomed = nm.nodes.filter { it.id in ids && isSniNode(it) }
            nm.nodes.removeAll(doomed.toSet())
            doomed.size
        }
        if (gone > 0) nm.saveNodes()
        return gone
    }

    /** Every SNI config the user has, newest first. */
    fun configs(context: Context): List<VpnNode> {
        val nm = NodeManager(context.applicationContext)
        return synchronized(nm.nodes) { nm.nodes.toList() }
            .filter { isSniNode(it) }
            .sortedByDescending { it.addedAt }
    }

    /** True when the tunnel currently up is an SNI one. */
    fun isConnected(context: Context): Boolean {
        if (!MyVpnService.isRunning) return false
        val id = MyVpnService.connectedNodeId ?: return false
        return configs(context).any { it.id == id }
    }

    /**
     * Bring the whole thing up: route, front, core.
     *
     * [route] is applied first and stored, because `ensureRunning` inside the service reads it —
     * and reads it from memory in preference to disk, which is why this goes through
     * [RstaSpoofManager.setRoute] rather than writing the preferences directly.
     */
    fun connect(
        context: Context,
        node: VpnNode,
        routeIp: String,
        routePort: Int,
        routeSni: String,
    ) {
        val app = context.applicationContext
        RstaSpoofManager.setRoute(app, routeIp, routePort, routeSni)
        com.mlmvpn.scanner.utils.SafeStart.service(app, 
            Intent(app, MyVpnService::class.java).apply {
                putExtra("NODE_URI", node.uri)
                putExtra("NODE_ID", node.id)
                putExtra("MTU_PROFILE", NetworkSettings.Method.SNI.id)
                putExtra("PROXY_MODE", NetworkSettings.proxyMode(app))
                putExtra("LOCAL_PORT", LocalPort.getString(app))
            }
        )
    }

    /**
     * Take it all down, in the reverse order.
     *
     * The core first: stopping the front underneath a running core leaves the core dialling a
     * port that is no longer there, which it reports as a connection failure rather than as a
     * disconnect the user asked for.
     */
    fun disconnect(context: Context) {
        val app = context.applicationContext
        com.mlmvpn.scanner.utils.SafeStart.service(app, Intent(app, MyVpnService::class.java).apply { action = "STOP" })
        try { RstaSpoofManager.stop() } catch (e: Exception) { /* already down */ }
    }
}
