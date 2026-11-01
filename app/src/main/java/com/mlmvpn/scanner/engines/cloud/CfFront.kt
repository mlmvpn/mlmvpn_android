package com.mlmvpn.scanner.engines.cloud

import android.util.Log
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.SocketFactory

/**
 * The camouflaged route for the app's OWN calls to Cloudflare -- adding an account, deploying a
 * worker, fetching a config -- for the networks where even `api.cloudflare.com` and a Worker's
 * `*.workers.dev` name cannot be reached because the filter reads the TLS server name (SNI) in the
 * first packet and drops the connection.
 *
 * It is the same trick the app already uses for the WARP identity API ([com.mlmvpn.core.warp.WarpApiFront]),
 * moved to where OkHttp can use it: the first TLS record -- the ClientHello, which carries the SNI --
 * is written in small TCP segments with short pauses, so the name is spread across several packets
 * and is never sitting in one for the filter to match. Everything after the ClientHello is written
 * untouched, so there is no cost beyond the first handshake. The certificate and host name are still
 * checked in full by OkHttp's own TLS on top: this hides the name from the ISP, it does not weaken
 * what is verified.
 *
 * It is applied as OkHttp's plain [SocketFactory]. OkHttp dials the raw TCP socket through this
 * factory and then layers its ordinary TLS on top; the TLS stack writes the ClientHello through this
 * socket's output stream, which is where the split happens. Nothing about the request, the
 * certificate check or the HTTP layer changes, and it adds no library -- it is a few hundred lines
 * of the JDK's own `java.net`, so the APK does not grow.
 *
 * Paired with [WorkerRoute.dns], which already hands these clients the Cloudflare edge addresses
 * proven to answer on this network: the address gets the packets to a live edge, and the split gets
 * the handshake past the name filter.
 */
object CfFront {

    private const val TAG = "CfFront"

    /**
     * Off would send the ClientHello whole again. Kept as a switch rather than a constant so a future
     * setting, or a diagnostic, can turn it off without a rebuild; there is no UI for it, because a
     * user who cannot reach Cloudflare to begin with cannot be asked to find a switch first.
     */
    @Volatile
    var enabled: Boolean = true

    /** OkHttp's raw-socket factory: every socket it makes splits its first TLS ClientHello. */
    val socketFactory: SocketFactory = Factory()

    private class Factory : SocketFactory() {
        override fun createSocket(): Socket = FrontSocket()
        override fun createSocket(host: String?, port: Int): Socket = FrontSocket().apply { connect(java.net.InetSocketAddress(host, port)) }
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            FrontSocket().apply { bind(java.net.InetSocketAddress(localHost, localPort)); connect(java.net.InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress?, port: Int): Socket = FrontSocket().apply { connect(java.net.InetSocketAddress(host, port)) }
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            FrontSocket().apply { bind(java.net.InetSocketAddress(localAddress, localPort)); connect(java.net.InetSocketAddress(address, port)) }
    }

    /** A plain TCP socket whose output stream splits the first TLS handshake record. */
    private class FrontSocket : Socket() {
        private var wrapped: OutputStream? = null

        /**
         * A direct connect gives up sooner while one of the app's tunnels is on: Cloudflare accepts a TCP
         * connection in well under a second where it is reachable, and a blocked line should not spend
         * 15 s per address before the tunnel is tried (see [CfTunnel]). Nothing changes without a tunnel.
         */
        override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) = super.connect(endpoint, CfTunnel.directConnectMs(timeout))
        override fun getOutputStream(): OutputStream {
            wrapped?.let { return it }
            val real = super.getOutputStream()
            val w = if (enabled) SplittingStream(real) else real
            wrapped = w
            return w
        }
    }

    /**
     * Splits only the first write that begins with a TLS handshake record (`0x16`), which is the
     * ClientHello; every later write (the client's Finished, then the application data) passes
     * straight through. A failure in the split falls back to a plain write, so this can make a
     * connection work but never break one that already would.
     */
    private class SplittingStream(private val out: OutputStream) : OutputStream() {
        private val rng = SecureRandom()
        @Volatile private var split = false

        override fun write(b: Int) = out.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (!split && len > 64 && b[off] == TLS_HANDSHAKE) {
                split = true
                try {
                    writeSplit(b, off, len)
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "split failed, sending whole: ${e.message}")
                    out.write(b, off, len)
                    return
                }
            }
            out.write(b, off, len)
        }

        /**
         * A tiny first piece cuts the record header itself, then 12-48 bytes each with 3-8 ms
         * between, so the server name is spread over several segments whatever its offset -- the
         * same shape [com.mlmvpn.core.warp.WarpApiFront] uses.
         */
        private fun writeSplit(b: ByteArray, off: Int, len: Int) {
            var at = off
            val end = off + len
            var first = true
            while (at < end) {
                val size = if (first) 1 + rng.nextInt(4) else 12 + rng.nextInt(37)
                val n = minOf(size, end - at)
                out.write(b, at, n)
                out.flush()
                at += n
                first = false
                if (at < end) Thread.sleep(3L + rng.nextInt(6))
            }
        }

        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    private const val TLS_HANDSHAKE: Byte = 0x16
}
