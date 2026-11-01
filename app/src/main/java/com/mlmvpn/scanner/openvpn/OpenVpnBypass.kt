package com.mlmvpn.scanner.openvpn

import java.net.InetAddress

/**
 * Addresses an OpenVPN tunnel must leave out of its routes: the edge of a carrier that runs in this
 * app and carries the tunnel's own connection (Windscribe over Cloudflare WARP). Inside the tunnel
 * that carrier would loop into itself.
 *
 * Android 11 has no `excludeRoute`, so each route that covers such an address is replaced by the
 * routes that cover the rest of it -- at most one per prefix bit per address.
 */
object OpenVpnBypass {
    private val hosts = java.util.concurrent.CopyOnWriteArraySet<String>()

    fun add(host: String) { normalize(host)?.let { hosts += it } }
    fun remove(host: String) { normalize(host)?.let { hosts -= it } }
    fun isEmpty() = hosts.isEmpty()

    /** [address]/[prefix] without the bypassed addresses: (address, prefix) pairs to route instead. */
    fun split(address: String, prefix: Int): List<Pair<String, Int>> {
        val net = runCatching { InetAddress.getByName(address).address }.getOrNull() ?: return listOf(address to prefix)
        val holes = hosts.mapNotNull { runCatching { InetAddress.getByName(it).address }.getOrNull() }.filter { it.size == net.size }
        if (holes.isEmpty()) return listOf(address to prefix)
        return without(net, prefix, holes).map { (bytes, p) -> InetAddress.getByAddress(bytes).hostAddress!! to p }
    }

    internal fun without(net: ByteArray, prefix: Int, holes: List<ByteArray>): List<Pair<ByteArray, Int>> {
        val bits = net.size * 8
        val inside = holes.filter { contains(net, prefix, it) }
        if (inside.isEmpty()) return listOf(masked(net, prefix) to prefix)
        if (prefix >= bits) return emptyList()
        val low = masked(net, prefix)
        val high = low.copyOf().also { setBit(it, prefix) }
        return without(low, prefix + 1, inside) + without(high, prefix + 1, inside)
    }

    private fun contains(net: ByteArray, prefix: Int, host: ByteArray): Boolean {
        for (i in 0 until prefix) if (bit(net, i) != bit(host, i)) return false
        return true
    }

    private fun bit(a: ByteArray, i: Int) = (a[i / 8].toInt() shr (7 - i % 8)) and 1
    private fun setBit(a: ByteArray, i: Int) { a[i / 8] = (a[i / 8].toInt() or (1 shl (7 - i % 8))).toByte() }
    private fun masked(a: ByteArray, prefix: Int): ByteArray = ByteArray(a.size) { i ->
        val keep = (prefix - i * 8).coerceIn(0, 8)
        (a[i].toInt() and (0xff shl (8 - keep))).toByte()
    }

    private fun normalize(host: String): String? = runCatching {
        InetAddress.getByName(host.removePrefix("[").removeSuffix("]")).hostAddress
    }.getOrNull()
}
