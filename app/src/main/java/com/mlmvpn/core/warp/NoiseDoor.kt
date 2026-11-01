package com.mlmvpn.core.warp

import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Puts a WireGuard outbound's junk packets on a loopback door instead of a `dialerProxy`.
 *
 * The app gives WireGuard its AmneziaWG-style junk by dialling through a `freedom` outbound with
 * `noises` (`streamSettings.sockopt.dialerProxy`). Xray then reads WireGuard's datagrams from a pipe
 * that belongs to that other outbound, and when a core stops, its outbounds close in no fixed order:
 * if the junk outbound goes first, WireGuard's bind sees its connection end unexpectedly, takes its
 * device down in a goroutine while the core is closing the same device, and the core panics with
 * "close of closed channel" (proxy/wireguard/bind.go). A Go panic ends the whole process. Seen on the
 * user's Galaxy A50 on 2026-10-11 at the end of every Amnezia test batch, after the WARP carrier's
 * "Unable to update bind ... no outbound handler for dialerProxy", and very likely the many reports
 * of the app dying while its VPN stopped ("native crash (main)", last step MyVpnService.onDestroy).
 *
 * Here WireGuard dials a loopback UDP port of the same core (a `dokodemo-door`), whose datagrams are
 * routed to the junk outbound and on to the real endpoint -- the same bytes on the wire, junk first.
 * WireGuard's socket is then an ordinary socket to localhost: a closing outbound cannot end it, so
 * nothing is closed twice.
 */
object NoiseDoor {
    private const val DOOR_PREFIX = "noise-door-"

    /** [config] with every WireGuard-through-junk outbound moved onto a door; unchanged when there is none. */
    fun rewrite(config: String): String {
        if (!config.contains("dialerProxy") || !config.contains("wireguard")) return config
        return runCatching { rewrite(JSONObject(config)) }.getOrNull() ?: config
    }

    private fun rewrite(json: JSONObject): String? {
        val outbounds = json.optJSONArray("outbounds") ?: return null
        val byTag = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
            .filter { it.optString("tag").isNotBlank() }.associateBy { it.optString("tag") }
        val doors = ArrayList<Pair<JSONObject, String>>()   // inbound, the junk outbound it goes to
        var n = 0
        for (i in 0 until outbounds.length()) {
            val out = outbounds.optJSONObject(i) ?: continue
            if (out.optString("protocol") != "wireguard") continue
            val sockopt = out.optJSONObject("streamSettings")?.optJSONObject("sockopt") ?: continue
            val via = sockopt.optString("dialerProxy").takeIf { it.isNotBlank() } ?: continue
            val junk = byTag[via] ?: continue
            // Only a junk outbound: anything else behind a dialerProxy (a chain) is left as it is.
            if (junk.optString("protocol") != "freedom" || junk.optJSONObject("settings")?.has("noises") != true) continue
            val peers = out.optJSONObject("settings")?.optJSONArray("peers") ?: continue
            if (peers.length() != 1) continue
            val peer = peers.optJSONObject(0) ?: continue
            val (host, port) = split(peer.optString("endpoint")) ?: continue
            val doorPort = freeUdpPort() ?: continue
            val tag = DOOR_PREFIX + n++
            doors += JSONObject().put("tag", tag).put("listen", "127.0.0.1").put("port", doorPort).put("protocol", "dokodemo-door")
                .put("settings", JSONObject().put("address", host).put("port", port).put("network", "udp")) to via
            peer.put("endpoint", "127.0.0.1:$doorPort")
            sockopt.remove("dialerProxy")
            if (sockopt.length() == 0) out.optJSONObject("streamSettings")?.remove("sockopt")
            if (out.optJSONObject("streamSettings")?.length() == 0) out.remove("streamSettings")
        }
        if (doors.isEmpty()) return null
        val inbounds = json.optJSONArray("inbounds") ?: JSONArray().also { json.put("inbounds", it) }
        doors.forEach { inbounds.put(it.first) }
        val routing = json.optJSONObject("routing") ?: JSONObject().also { json.put("routing", it) }
        val old = routing.optJSONArray("rules") ?: JSONArray()
        // First, ahead of every rule an engine or the Smart Iran route added: a door's datagrams are
        // WireGuard's own and go nowhere but its junk outbound.
        val rules = JSONArray()
        doors.forEach { (inbound, junk) ->
            rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put(inbound.optString("tag"))).put("outboundTag", junk))
        }
        for (i in 0 until old.length()) rules.put(old.get(i))
        routing.put("rules", rules)
        return json.toString()
    }

    /** "host:port" or "[v6]:port" into its parts. */
    private fun split(endpoint: String): Pair<String, Int>? {
        val e = endpoint.trim()
        val at = e.lastIndexOf(':').takeIf { it > 0 } ?: return null
        val port = e.substring(at + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val host = e.substring(0, at).removePrefix("[").removeSuffix("]").takeIf { it.isNotBlank() } ?: return null
        return host to port
    }

    private fun freeUdpPort(): Int? = runCatching {
        DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)).use { it.localPort }
    }.getOrNull()
}
