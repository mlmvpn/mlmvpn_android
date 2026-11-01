package com.mlmvpn.core.tunnel

/**
 * Address arithmetic for the Smart Iran route, free of Android so it is unit-tested.
 *
 * Android 13+ can take a range OUT of a VPN (`excludeRoute`). Older versions cannot: the only way
 * to leave Iran's ranges outside is to route everything EXCEPT them, i.e. the complement of the
 * list inside 0.0.0.0/0. The full list's complement is ~10,000 routes, too many for an older
 * phone's `establish()`; [complementV4] keeps only ranges of at least `/maxPrefix` (fewer, larger
 * holes: ~700 routes at /18 for three quarters of Iran's addresses). What is left inside the
 * tunnel is still sent out directly by the core's own Iran rules, for the engines that have them.
 */
object IranCidr {

    data class V4(val start: Long, val prefix: Int) {
        val size: Long get() = 1L shl (32 - prefix)
        val end: Long get() = start + size - 1
        override fun toString() = "${text(start)}/$prefix"
    }

    data class V6(val address: String, val prefix: Int)

    fun parseV4(line: String): V4? {
        val l = line.trim()
        if (l.isEmpty() || l.startsWith("#") || l.contains(':')) return null
        val addr = l.substringBefore('/')
        val prefix = l.substringAfter('/', "32").toIntOrNull()?.takeIf { it in 0..32 } ?: return null
        val parts = addr.split('.')
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            val n = p.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            v = (v shl 8) or n.toLong()
        }
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return V4(v and mask, prefix)
    }

    fun parseV6(line: String): V6? {
        val l = line.trim()
        if (!l.contains(':') || l.startsWith("#")) return null
        val prefix = l.substringAfter('/', "128").toIntOrNull()?.takeIf { it in 0..128 } ?: return null
        val addr = l.substringBefore('/')
        if (addr.isBlank() || addr.any { !(it.isLetterOrDigit() || it == ':' || it == '.') }) return null
        return V6(addr, prefix)
    }

    fun parse(text: String): Pair<List<V4>, List<V6>> {
        val v4 = ArrayList<V4>()
        val v6 = ArrayList<V6>()
        text.lineSequence().forEach { line ->
            parseV4(line)?.let { v4 += it } ?: parseV6(line)?.let { v6 += it }
        }
        return collapse(v4) to v6
    }

    /** Sorted, with ranges inside other ranges dropped and touching buddies merged. */
    fun collapse(list: List<V4>): List<V4> {
        val sorted = list.sortedWith(compareBy({ it.start }, { it.prefix }))
        val out = ArrayList<V4>()
        for (n in sorted) {
            val last = out.lastOrNull()
            if (last != null && n.start >= last.start && n.end <= last.end) continue
            out += n
            // Merge buddies upward: two halves of one larger block become that block.
            while (out.size >= 2) {
                val b = out[out.size - 1]
                val a = out[out.size - 2]
                if (a.prefix == b.prefix && a.prefix > 0 && a.end + 1 == b.start && a.start % (a.size * 2) == 0L) {
                    out.removeAt(out.size - 1); out.removeAt(out.size - 1)
                    out += V4(a.start, a.prefix - 1)
                } else break
            }
        }
        return out
    }

    /**
     * Every block of 0.0.0.0/0 that is NOT in [holes] with prefix <= [maxPrefix], as the fewest
     * CIDR blocks; [keep] addresses always stay inside (a resolver that happens to be Iranian).
     */
    fun complementV4(holes: List<V4>, maxPrefix: Int, keep: List<Long> = emptyList()): List<V4> {
        val big = collapse(holes.filter { it.prefix <= maxPrefix })
            .filter { h -> keep.none { k -> k in h.start..h.end } }
        val out = ArrayList<V4>()
        var cur = 0L
        for (h in big) {
            if (h.start > cur) out += blocks(cur, h.start - 1)
            cur = maxOf(cur, h.end + 1)
        }
        if (cur <= 0xFFFFFFFFL) out += blocks(cur, 0xFFFFFFFFL)
        return out
    }

    /** The fewest CIDR blocks covering [from]..[to] exactly. */
    fun blocks(from: Long, to: Long): List<V4> {
        val out = ArrayList<V4>()
        var s = from
        while (s <= to) {
            var prefix = 32
            while (prefix > 0) {
                val size = 1L shl (32 - (prefix - 1))
                if (s % size != 0L || s + size - 1 > to) break
                prefix--
            }
            out += V4(s, prefix)
            s += 1L shl (32 - prefix)
        }
        return out
    }

    fun text(v: Long): String = "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"

    fun ipv4Value(ip: String): Long? = parseV4("$ip/32")?.start

    fun contains(list: List<V4>, ip: Long): Boolean {
        var lo = 0; var hi = list.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val n = list[mid]
            when {
                ip < n.start -> hi = mid - 1
                ip > n.end -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }
}
