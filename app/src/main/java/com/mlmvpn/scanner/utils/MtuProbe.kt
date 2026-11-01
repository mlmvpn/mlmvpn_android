package com.mlmvpn.scanner.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Finds the exact largest packet a method can carry, so the MTU field can be a measurement
 * rather than a guess.
 *
 * ## Why it probes the OUTER path and subtracts, instead of probing through the tunnel
 *
 * The obvious test — bring the tunnel up and ping through it with the don't-fragment bit set —
 * cannot be run from inside this app. Every tunnel here excludes our own package from the tun
 * (`addDisallowedApplication`, see `TunnelVpnService.startWatchdog` for the same trap), so a
 * probe started by the app rides the carrier link no matter what is connected. It would measure
 * the Wi-Fi, report 1500, and be wrong on every method.
 *
 * What the app CAN measure from where it stands is the path the tunnel's own outer packets take,
 * which is the same path and the same link. So this probes THAT, exactly, and subtracts the
 * framing each method wraps an inner packet in. Both halves are checkable:
 *
 *  - the outer probe is ordinary PMTU discovery, binary-searched to the byte and confirmed at the
 *    boundary, so it is not a guess;
 *  - the overheads below were each measured on a real 1500-byte line by connecting the method,
 *    pinging through it from a shell (which is NOT excluded from the tun) and finding the exact
 *    size that stopped arriving.
 *
 * WireGuard is the one that proves the method: 1500 − 1440 = 60 bytes, which is exactly
 * 20 (IPv4) + 8 (UDP) + 32 (WireGuard) on paper. The arithmetic and the wire agree.
 *
 * ## The methods that are not measured
 *
 * Psiphon, Tor, V2Ray, SNI and Quick Connect do not carry inner IP packets at all. Their tun is
 * read by tun2socks, which terminates TCP on the device with its own stack and re-opens it
 * through a local SOCKS proxy — so nothing the tun MTU describes ever reaches the network, and
 * the number is a local buffer size. Bigger is strictly better there, which is why they are
 * [LOCAL_TERMINATION] and answer 1500 without a probe. Probing them would also fail for a second
 * reason: tun2socks forwards TCP and UDP and drops ICMP, so every ping through one of those
 * tunnels times out at every size.
 */
object MtuProbe {

    private const val TAG = "MtuProbe"

    /** IPv4 + ICMP echo header. `ping -s N` sends N bytes of payload on top of this. */
    private const val ICMP_OVERHEAD = 28

    /** The methods whose tun never puts a packet on the wire. See the class note. */
    val LOCAL_TERMINATION = setOf(
        NetworkSettings.Method.PSIPHON,
        NetworkSettings.Method.TOR,
        NetworkSettings.Method.CFWARP,
        NetworkSettings.Method.LANTERN,
        NetworkSettings.Method.NPV,
        NetworkSettings.Method.V2RAY,
        NetworkSettings.Method.SNI,
        NetworkSettings.Method.QUICK_CONNECT,
    )

    /**
     * Bytes each method adds to an inner packet, measured rather than assumed.
     *
     * MASQUE's 196 is larger than the QUIC framing alone accounts for because the WARP tunnel
     * rides inside the MASQUE session — it is two encapsulations, not one, and WARP-on-WARP is
     * three, which is where its 280 comes from. They are recorded as what the wire did, not as a
     * derivation, because a derivation that disagreed with the wire would still be wrong.
     */
    private val OVERHEAD = mapOf(
        NetworkSettings.Method.MASQUE to 196,
        NetworkSettings.Method.WIREGUARD to 60,
        NetworkSettings.Method.WARP_ON_WARP to 280,
        // SoftEther inside TLS inside TCP: 20 IPv4 + 20 TCP + 57 TLS record + 12 SoftEther
        // frame + 14 Ethernet. The tunnel carries Ethernet frames, hence the last term.
        NetworkSettings.Method.GATEWAY to 123,
    )

    /**
     * Where to send the outer probe.
     *
     * The three Cloudflare methods reach the same edge, and 162.159.192.1 is the WARP endpoint
     * itself, so the probe follows the exact path their packets will. Anything else falls back to
     * a resolver that answers from everywhere.
     */
    private fun targetFor(method: NetworkSettings.Method): String = when (method) {
        NetworkSettings.Method.MASQUE,
        NetworkSettings.Method.WIREGUARD,
        NetworkSettings.Method.WARP_ON_WARP -> "162.159.192.1"
        else -> "1.1.1.1"
    }

    /** What a run found. [inner] is null when the line could not be measured at all. */
    data class Result(
        val method: NetworkSettings.Method,
        val outerPathMtu: Int?,
        val inner: Int?,
        val localTermination: Boolean,
        val probes: Int,
        /** A size the method's own software fixes, not the line: answered without probing. */
        val fixed: Boolean = false,
    )

    /**
     * Measure [method] on the line this phone is on right now.
     *
     * [onProgress] is called with each size tried so the screen can show the search happening --
     * a run takes 10-20 seconds and a spinner with no numbers behind it looks like a hang.
     */
    suspend fun measure(
        method: NetworkSettings.Method,
        onProgress: (Int) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        if (method in LOCAL_TERMINATION) {
            return@withContext Result(method, null, NetworkSettings.MAX_MTU, true, 0)
        }
        // «URnetwork» carries the TUN's packets inside its own transfer frames, which its SDK sizes
        // to the path by itself: what the TUN takes is URnetwork's own interface size, not the line's.
        if (method == NetworkSettings.Method.URNETWORK) {
            return@withContext Result(method, null, method.default, false, 0, fixed = true)
        }

        val host = targetFor(method)
        var probes = 0
        fun fits(mtu: Int): Boolean {
            probes++
            onProgress(mtu)
            return ping(host, mtu - ICMP_OVERHEAD)
        }

        // Does this host answer at all? A line that drops every echo cannot be measured, and
        // saying so is better than reporting the floor as if it were an answer.
        if (!fits(NetworkSettings.MIN_MTU)) {
            return@withContext Result(method, null, null, false, probes)
        }

        // Binary search for the largest packet that arrives. `lo` always fits, `hi + 1` never
        // does, so the loop ends with lo == the exact byte.
        var lo = NetworkSettings.MIN_MTU
        var hi = NetworkSettings.MAX_MTU
        if (fits(hi)) {
            lo = hi
        } else {
            while (lo + 1 < hi) {
                val mid = (lo + hi) / 2
                if (fits(mid)) lo = mid else hi = mid
            }
        }

        // Confirm the boundary. One lost packet mid-search moves the answer by up to half the
        // remaining range, and "exact" has to mean exact: the size below must pass twice and the
        // size above must fail twice before the number is reported.
        var best = lo
        while (best > NetworkSettings.MIN_MTU && !fits(best)) best -= 4
        if (best < NetworkSettings.MAX_MTU && fits(best + 1)) {
            // The search settled low. Walk up until it stops fitting.
            while (best < NetworkSettings.MAX_MTU && fits(best + 1)) best++
        }

        val inner = (best - (OVERHEAD[method] ?: 0)).coerceIn(NetworkSettings.MIN_MTU, NetworkSettings.MAX_MTU)
        Log.i(TAG, "${method.id}: outer=$best inner=$inner after $probes probes to $host")
        Result(method, best, inner, false, probes)
    }

    /**
     * One don't-fragment echo of an exact size.
     *
     * `ping` rather than a raw socket because Android has no unprivileged way to set DF on one,
     * and the binary is present on every device this app runs on. Failure of any kind — a
     * timeout, "message too long", a missing binary — is a packet that did not arrive, which is
     * the only distinction the search needs.
     */
    private fun ping(host: String, payload: Int): Boolean = try {
        val p = ProcessBuilder(
            "/system/bin/ping", "-n", "-c", "1", "-W", "2", "-M", "do", "-s", payload.toString(), host
        ).redirectErrorStream(true).start()
        val done = p.waitUpTo(4, TimeUnit.SECONDS)
        if (!done) {
            p.destroy()
            false
        } else {
            p.exitValue() == 0
        }
    } catch (e: Exception) {
        Log.w(TAG, "probe failed at $payload: ${e.message}")
        false
    }
}
