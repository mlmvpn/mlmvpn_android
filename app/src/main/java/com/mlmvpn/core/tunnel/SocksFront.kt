package com.mlmvpn.core.tunnel

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The SOCKS5 server that tun2socks talks to whenever the tunnel behind it cannot
 * carry UDP itself.
 *
 * Two transports need it, for the same reason and with different DNS behind it:
 * **Tor**, whose SocksPort is CONNECT-only, and **the Rust core in Proxy Mode**
 * (MASQUE, WireGuard, WARP-on-WARP -- see `TunnelVpnService.startNativeProxyTunnel`),
 * whose `socks::serve` is likewise CONNECT-only. Everything below was written for
 * Tor and holds word for word for the core; only [resolve] differs between them,
 * because Tor has a local DNSPort to forward to and the core does not.
 *
 * ## Why this class has to exist at all
 *
 * In Psiphon mode tun2socks is pointed straight at Psiphon's own SOCKS listener
 * and UDP works because of a coincidence of Psiphon's design: tun2socks is told
 * `udpgwServerAddress = 127.0.0.1:7300` and dials it *through* the SOCKS proxy,
 * and the Psiphon **server** intercepts that exact address
 * (`UDPInterceptUdpgwServerAddress` in tunnelServer.go) and does the UDP
 * forwarding remotely. There is no local udpgw daemon anywhere in this app.
 *
 * Tor has no such thing. Two hard facts, both verified against the sources:
 *
 *  1. **Tor's SOCKS port has no UDP ASSOCIATE.** It is CONNECT-only.
 *  2. Pointing tun2socks at Tor directly makes it try to CONNECT to
 *     `127.0.0.1:7300` *through the Tor circuit*, which Tor refuses (private
 *     address), so `SocksUdpGwClient` reconnect-loops forever.
 *
 * The Rust core behaves the same way, measured on a device rather than assumed:
 * a SOCKS5 CONNECT to `example.com:80` through it returns `05 00` and fetches the
 * page, while the identical CONNECT to `127.0.0.1:7300` hangs until it is killed.
 * So a WARP tunnel in Proxy Mode resolved nothing and every app reported no
 * internet on a tunnel that was, by every other measure, connected and working.
 *
 * And UDP is not optional here, because of one line in tun2socks.c:
 *
 * ```c
 * // do nothing if we don't have udpgw
 * if (!options.udpgw_remote_server_addr) { goto fail; }
 * ```
 *
 * plus `udpgw_transparent_dns = 1`, which routes **every DNS query** down the
 * udpgw path. So with Tor and no udpgw, name resolution is dead and the app
 * looks completely broken even though the Tor circuit is fine.
 *
 * ## What this does instead
 *
 * It is the SOCKS5 server tun2socks connects to, and it splits the traffic:
 *
 * ```
 *   tun2socks ──CONNECT <any ip:port>──► relay ──► upstream SOCKS ──► tunnel
 *             └─CONNECT 127.0.0.1:7300─► in-process udpgw server
 *                                          └─ DNS ──► Tor DNSPort (local UDP), or
 *                                                     DNS-over-TCP through the
 *                                                     upstream when there is none
 *                                          └─ other UDP ──► dropped
 * ```
 *
 * The udpgw branch is a real implementation of badvpn's udpgw wire protocol, so
 * badvpn itself is untouched — no patching of vendored C, and the working
 * Psiphon path keeps behaving exactly as before.
 *
 * ## Why non-DNS UDP is dropped rather than proxied
 *
 * There is nowhere to send it. Tor carries TCP streams only. Dropping is the
 * correct behaviour and not a silent failure: QUIC (UDP/443) treats an
 * unanswered handshake as "no QUIC here" and browsers fall back to TCP within a
 * few hundred milliseconds. Answering with an error, or black-holing at the
 * TUN, both produce worse stalls.
 *
 * ## DNS goes to Tor's own DNSPort
 *
 * `DNSPort` is compiled into our libtor.so (verified: the `DNSPort`,
 * `DNSListenAddress` and `dnsserv` symbols are all present in the built
 * binary). It answers on plain local UDP and resolves through the circuit, so
 * there is no DNS leak and no need to hand-roll DNS-over-TCP.
 *
 * Only A/AAAA/PTR are supported by Tor's DNSPort. A Chrome HTTPS/SVCB query
 * (type 65) gets refused, which is the same answer Chrome gets from plenty of
 * real resolvers, and it falls back without a stall.
 */
object SocksFront {

    private const val TAG = "SocksFront"

    /** badvpn's udpgw flags, from `protocol/udpgw_proto.h`. */
    private const val FLAG_KEEPALIVE = 1 shl 0
    private const val FLAG_REBIND = 1 shl 1
    private const val FLAG_DNS = 1 shl 2
    private const val FLAG_IPV6 = 1 shl 3

    /**
     * The address tun2socks is told to reach udpgw on.
     *
     * Must match what [Tun2SocksManager] passes, and it is deliberately the same
     * 127.0.0.1:7300 Psiphon uses: keeping one convention means the udpgw code
     * path in badvpn is identical in both modes, and only the far end differs.
     */
    /**
     * The port this front binds, and what tun2socks is pointed at.
     *
     * Clear of 1819 ([CoreConfig.SOCKS_PORT]) and 1820 ([CoreConfig.CHAIN_SOCKS_PORT]),
     * which the Rust core and Psiphon own. One constant rather than one per caller
     * because this is a singleton: only one front can be bound at a time, which is
     * fine because `TunnelExclusion` allows only one transport at a time anyway.
     */
    const val FRONT_PORT = 1821

    private const val UDPGW_HOST = "127.0.0.1"
    private const val UDPGW_PORT = 7300

    private const val SOCKS_VERSION = 5
    private const val CMD_CONNECT = 1
    private const val ATYP_IPV4 = 1
    private const val ATYP_DOMAIN = 3
    private const val ATYP_IPV6 = 4

    private const val REP_SUCCESS = 0
    private const val REP_GENERAL_FAILURE = 1
    private const val REP_CMD_NOT_SUPPORTED = 7

    private const val RELAY_BUFFER = 32 * 1024
    private const val UPSTREAM_CONNECT_TIMEOUT_MS = 30_000
    private const val DNS_TIMEOUT_MS = 10_000

    /**
     * Maximum DNS answer we will read back from Tor's DNSPort.
     *
     * 4096 covers EDNS0-advertised sizes; anything larger is a truncated-reply
     * case the resolver handles by retrying over TCP, which goes through the
     * CONNECT path instead.
     */
    private const val DNS_BUFFER = 4096

    private val running = AtomicBoolean(false)

    /**
     * Session byte counters.
     *
     * Tor mode has no other source of traffic numbers: the Rust core is not in
     * the data path and there is no Psiphon `onBytesTransferred` callback, so
     * without counting here the UI would show a connected tunnel moving 0 B —
     * which is exactly what every "fake connected" bug in this project looked
     * like, and would also break the app's own RX verification gate.
     *
     * Counted at the relay because that is the one place all tunnelled bytes
     * pass through. DNS is excluded on purpose: a few hundred bytes of lookups
     * would let a Tor session that resolves names but carries no real traffic
     * look alive.
     *
     * `tx`/`rx` are from the device's point of view — tx is what the phone sent.
     */
    private val txBytes = java.util.concurrent.atomic.AtomicLong(0)
    private val rxBytes = java.util.concurrent.atomic.AtomicLong(0)

    val sessionTx: Long get() = txBytes.get()
    val sessionRx: Long get() = rxBytes.get()

    @Volatile
    private var serverSocket: ServerSocket? = null

    /** The SOCKS5 server every ordinary CONNECT is forwarded to. */
    @Volatile
    private var upstreamSocksPort: Int = 0

    /**
     * A local UDP DNS server to forward queries to, or 0 when there is none.
     *
     * Tor's DNSPort sets this. The Rust core has no equivalent, so it leaves this
     * zero and supplies [dnsOverTcp] instead.
     */
    @Volatile
    private var localDnsPort: Int = 0

    /**
     * `host:port` of a resolver to query over TCP through the upstream, used when
     * [localDnsPort] is 0.
     *
     * TCP rather than UDP because the upstream carries no UDP -- that is the whole
     * reason this class exists -- and DNS over TCP is the same wire format behind a
     * two-byte length, so nothing has to be re-encoded. Through the upstream rather
     * than directly, because a query sent on the phone's own link would leak exactly
     * which sites the tunnelled device is visiting.
     */
    @Volatile
    private var dnsOverTcp: String? = null

    /**
     * Threads for in-flight DNS lookups.
     *
     * Bounded on purpose: each task holds one ephemeral UDP socket for at most
     * [DNS_TIMEOUT_MS], and a burst of queries from a page load must not turn
     * into an unbounded thread count on a phone. 8 concurrent lookups is more
     * than a browser will usefully pipeline through a single Tor circuit.
     */
    /**
     * «کشور خروجی با پراکسی رایگان» (com.mlmvpn.core.warp.Ipv4Exits): a free proxy in the chosen
     * country that every CONNECT leaves through, itself dialled THROUGH the upstream -- so the line
     * sees only WARP, and the sites see the proxy's country. Null: CONNECTs go to the upstream as
     * they always did. DNS never takes this hop: it resolves through the upstream itself.
     *
     * Settable while running: a proxy that stopped answering is swapped for the next of the pool
     * without the tunnel going down; flows already open keep the hop they were made with.
     */
    data class ExitHop(val ip: String, val port: Int, val type: Int) {
        override fun toString() = "${when (type) { HOP_SOCKS5 -> "socks5"; HOP_SOCKS4 -> "socks4"; else -> "http" }}://$ip:$port"
    }

    /** What the exit proxy speaks: HTTP CONNECT, SOCKS5, or SOCKS4/4a (no IPv6 targets). */
    const val HOP_HTTP = 0
    const val HOP_SOCKS5 = 1
    const val HOP_SOCKS4 = 2

    @Volatile
    var exitHop: ExitHop? = null
        private set

    fun setExit(hop: ExitHop?) {
        if (hop != exitHop) ConnectionLog.record("$TAG exit ${hop ?: "off"}")
        exitHop = hop
    }

    @Volatile
    private var dnsPool: ExecutorService? = null

    @Volatile
    private var connPool: ExecutorService? = null

    val isRunning: Boolean
        get() = running.get()

    /**
     * Bind the front-end.
     *
     * @param listenPort what tun2socks will dial as its SOCKS server.
     * @param socksPort Tor's `SocksPort`.
     * @param dnsPort a local UDP DNS server to forward queries to (Tor's `DNSPort`),
     *   or 0 when the upstream has none -- pass [dnsTcpResolver] instead.
     * @param dnsTcpResolver `host:port` of a resolver to query over TCP through the
     *   upstream, used only when [dnsPort] is 0. See [dnsOverTcp].
     * @param bindAll bind 0.0.0.0 rather than loopback, so devices on the same
     *   network can use this front as their proxy. Off unless the user turned
     *   Local Network on; see [com.mlmvpn.scanner.utils.NetworkSettings.allowLan].
     *   Tor was the one transport whose front stayed loopback-only, which showed
     *   up as the Local Network screen reporting a working share that no client
     *   could actually connect to.
     */
    @Synchronized
    fun start(
        listenPort: Int,
        socksPort: Int,
        dnsPort: Int,
        bindAll: Boolean = false,
        dnsTcpResolver: String? = null,
        exit: ExitHop? = null,
    ): Boolean {
        if (running.get()) {
            ConnectionLog.record("$TAG already running")
            setExit(exit)
            return true
        }
        upstreamSocksPort = socksPort
        localDnsPort = dnsPort
        dnsOverTcp = dnsTcpResolver
        setExit(exit)

        val bindHost = if (bindAll) "0.0.0.0" else "127.0.0.1"
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(bindHost), listenPort))
            }
        } catch (e: Exception) {
            ConnectionLog.record("$TAG could not bind $bindHost:$listenPort: ${e.message}")
            return false
        }

        serverSocket = server
        dnsPool = Executors.newFixedThreadPool(8)
        connPool = Executors.newCachedThreadPool()
        // Zeroed per session so the service's traffic deltas start from a known
        // baseline; a restart must not look like a sudden burst.
        txBytes.set(0)
        rxBytes.set(0)
        running.set(true)

        Thread({
            // The accept loop must outlive anything a single connection can do.
            // It is a bare thread, so an escape here would reach the default
            // handler and kill the process rather than merely stop accepting.
            try {
                while (running.get()) {
                    val client = try {
                        server.accept()
                    } catch (e: Exception) {
                        if (running.get()) ConnectionLog.record("$TAG accept failed: ${e.message}")
                        break
                    }
                    val pool = connPool
                    if (pool == null) {
                        closeQuietly(client)
                        break
                    }
                    try {
                        pool.execute {
                            // A pool task's uncaught exception reaches the worker
                            // thread's default handler and kills the process, exactly
                            // like a bare Thread. handleClient guards itself, but the
                            // guarantee belongs here so it cannot be lost by an edit
                            // inside it.
                            try {
                                handleClient(client)
                            } catch (_: Throwable) {
                                closeQuietly(client)
                            }
                        }
                    } catch (e: Exception) {
                        // Pool shut down between accept and submit.
                        closeQuietly(client)
                    }
                }
            } catch (t: Throwable) {
                ConnectionLog.record("$TAG accept loop ended: ${t.message}")
            }
        }, "tor-front-accept").apply { isDaemon = true }.start()

        ConnectionLog.record(
            "$TAG listening on $bindHost:$listenPort → Tor SOCKS $socksPort, DNS $dnsPort"
        )
        return true
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        exitHop = null
        closeQuietly(serverSocket)
        serverSocket = null
        dnsPool?.shutdownNow()
        dnsPool = null
        connPool?.shutdownNow()
        connPool = null
        ConnectionLog.record("$TAG stopped")
    }

    // ---------------------------------------------------------------- SOCKS5

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            val input = DataInputStream(BufferedInputStream(client.getInputStream()))
            val output = BufferedOutputStream(client.getOutputStream())

            // Greeting. tun2socks offers "no authentication" only, because we
            // pass no credentials to BSocksClient.
            val version = input.read()
            if (version != SOCKS_VERSION) {
                closeQuietly(client)
                return
            }
            val methodCount = input.read()
            if (methodCount <= 0) {
                closeQuietly(client)
                return
            }
            val methods = ByteArray(methodCount)
            input.readFully(methods)
            if (methods.none { it.toInt() == 0 }) {
                // 0xFF = no acceptable method.
                output.write(byteArrayOf(SOCKS_VERSION.toByte(), 0xFF.toByte()))
                output.flush()
                closeQuietly(client)
                return
            }
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), 0x00))
            output.flush()

            // Request.
            if (input.read() != SOCKS_VERSION) {
                closeQuietly(client)
                return
            }
            val command = input.read()
            input.read() // reserved
            val addressType = input.read()

            val host: String
            val rawAddress: ByteArray
            when (addressType) {
                ATYP_IPV4 -> {
                    rawAddress = ByteArray(4).also { input.readFully(it) }
                    host = InetAddress.getByAddress(rawAddress).hostAddress ?: ""
                }
                ATYP_IPV6 -> {
                    rawAddress = ByteArray(16).also { input.readFully(it) }
                    host = InetAddress.getByAddress(rawAddress).hostAddress ?: ""
                }
                ATYP_DOMAIN -> {
                    val length = input.read()
                    if (length <= 0) {
                        closeQuietly(client)
                        return
                    }
                    rawAddress = ByteArray(length).also { input.readFully(it) }
                    host = String(rawAddress, Charsets.US_ASCII)
                }
                else -> {
                    replyFailure(output, REP_GENERAL_FAILURE)
                    closeQuietly(client)
                    return
                }
            }
            val port = ((input.read() and 0xFF) shl 8) or (input.read() and 0xFF)

            if (command != CMD_CONNECT) {
                // UDP ASSOCIATE and BIND are genuinely unsupported: Tor cannot
                // carry either. tun2socks never asks for them.
                replyFailure(output, REP_CMD_NOT_SUPPORTED)
                closeQuietly(client)
                return
            }

            if (host == UDPGW_HOST && port == UDPGW_PORT) {
                // Report success on the SOCKS layer first — SocksUdpGwClient
                // waits for BSOCKSCLIENT_EVENT_UP before it starts framing
                // udpgw packets onto the stream.
                replySuccess(output)
                serveUdpgw(client, input, output)
                return
            }

            relayThroughTor(client, host, port, output)
        } catch (e: Exception) {
            closeQuietly(client)
        }
    }

    private fun replySuccess(output: OutputStream) {
        // BND.ADDR/BND.PORT are ignored by tun2socks for CONNECT, so a zero
        // IPv4 address is fine and is what most SOCKS servers emit.
        output.write(
            byteArrayOf(
                SOCKS_VERSION.toByte(), REP_SUCCESS.toByte(), 0, ATYP_IPV4.toByte(),
                0, 0, 0, 0, 0, 0,
            )
        )
        output.flush()
    }

    private fun replyFailure(output: OutputStream, code: Int) {
        try {
            output.write(
                byteArrayOf(
                    SOCKS_VERSION.toByte(), code.toByte(), 0, ATYP_IPV4.toByte(),
                    0, 0, 0, 0, 0, 0,
                )
            )
            output.flush()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ TCP relay

    private fun isIpLiteral(host: String): Boolean =
        host.indexOf(':') >= 0 || // bare IPv6
            Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host) // IPv4

    /**
     * Chain a CONNECT onto Tor's SOCKS port.
     *
     * tun2socks only ever asks for raw IP addresses — it is a transparent TUN
     * and has no names. That is not a DNS leak: the address it asks for came
     * out of a DNS answer that [serveUdpgw] already resolved *through* Tor.
     */
    private fun relayThroughTor(client: Socket, target: String, targetPort: Int, clientOut: OutputStream) {
        // With an exit proxy the upstream is asked for the PROXY, and the proxy for the target.
        val hop = exitHop
        val host = hop?.ip ?: target
        val port = hop?.port ?: targetPort
        val upstream = try {
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress("127.0.0.1", upstreamSocksPort), UPSTREAM_CONNECT_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            replyFailure(clientOut, REP_GENERAL_FAILURE)
            closeQuietly(client)
            return
        }

        try {
            val upIn = DataInputStream(BufferedInputStream(upstream.getInputStream()))
            val upOut = BufferedOutputStream(upstream.getOutputStream())

            upOut.write(byteArrayOf(SOCKS_VERSION.toByte(), 1, 0x00))
            upOut.flush()
            if (upIn.read() != SOCKS_VERSION || upIn.read() != 0x00) {
                replyFailure(clientOut, REP_GENERAL_FAILURE)
                closeQuietly(upstream)
                closeQuietly(client)
                return
            }

            // Pass the target to Tor exactly as we got it. An IP literal goes as
            // an IP (the common case: tun2socks has no names). A HOSTNAME must
            // go through as ATYP_DOMAIN so TOR resolves it inside the circuit —
            // resolving here first both leaks the name to the carrier resolver
            // and hands Tor an address it may reject ("private address"), which
            // is what the field log caught.
            // ⚠️ The first three bytes are NOT optional and are easy to lose in a
            // refactor: ByteArray() is zero-filled, so omitting them sends
            // `00 00 00 …` and Tor answers "Socks version 0 not recognized. (This
            // port is not an HTTP proxy…)" for every single flow — a fully
            // bootstrapped circuit that carries nothing. That is exactly what the
            // first field build did, hundreds of times per session.
            val request: ByteArray = if (isIpLiteral(host)) {
                val addressBytes = InetAddress.getByName(host).address
                val addressType = if (addressBytes.size == 16) ATYP_IPV6 else ATYP_IPV4
                ByteArray(4 + addressBytes.size + 2).apply {
                    this[0] = SOCKS_VERSION.toByte()
                    this[1] = CMD_CONNECT.toByte()
                    this[2] = 0
                    this[3] = addressType.toByte()
                    System.arraycopy(addressBytes, 0, this, 4, addressBytes.size)
                    this[4 + addressBytes.size] = ((port shr 8) and 0xFF).toByte()
                    this[5 + addressBytes.size] = (port and 0xFF).toByte()
                }
            } else {
                val name = host.toByteArray(Charsets.US_ASCII)
                check(name.size <= 255) { "hostname too long" }
                ByteArray(5 + name.size + 2).apply {
                    this[0] = SOCKS_VERSION.toByte()
                    this[1] = CMD_CONNECT.toByte()
                    this[2] = 0
                    this[3] = ATYP_DOMAIN.toByte()
                    this[4] = name.size.toByte()
                    System.arraycopy(name, 0, this, 5, name.size)
                    this[5 + name.size] = ((port shr 8) and 0xFF).toByte()
                    this[6 + name.size] = (port and 0xFF).toByte()
                }
            }
            upOut.write(request)
            upOut.flush()

            // Tor's reply. Read it fully so the stream is positioned at payload.
            if (upIn.read() != SOCKS_VERSION) {
                replyFailure(clientOut, REP_GENERAL_FAILURE)
                closeQuietly(upstream)
                closeQuietly(client)
                return
            }
            val reply = upIn.read()
            upIn.read() // reserved
            when (upIn.read()) {
                ATYP_IPV4 -> upIn.readFully(ByteArray(4))
                ATYP_IPV6 -> upIn.readFully(ByteArray(16))
                ATYP_DOMAIN -> {
                    val length = upIn.read()
                    if (length > 0) upIn.readFully(ByteArray(length))
                }
            }
            upIn.readFully(ByteArray(2)) // bound port

            if (reply != REP_SUCCESS) {
                // Pass Tor's own failure code back so lwIP resets that one flow
                // instead of retrying a dead exit forever.
                replyFailure(clientOut, reply)
                closeQuietly(upstream)
                closeQuietly(client)
                return
            }

            if (hop != null) {
                // The stream now reaches the free proxy; it is asked for the real target. A proxy that
                // refuses fails this one flow -- the watch in Ipv4Exits notices a dead one and swaps it.
                upstream.soTimeout = EXIT_HANDSHAKE_TIMEOUT_MS
                val opened = runCatching {
                    when (hop.type) {
                        HOP_SOCKS5 -> exitSocks5(upIn, upOut, target, targetPort)
                        HOP_SOCKS4 -> exitSocks4(upIn, upOut, target, targetPort)
                        else -> exitHttpConnect(upIn, upOut, target, targetPort)
                    }
                }.getOrDefault(false)
                if (!opened) {
                    replyFailure(clientOut, REP_GENERAL_FAILURE)
                    closeQuietly(upstream)
                    closeQuietly(client)
                    return
                }
                upstream.soTimeout = 0
            }

            replySuccess(clientOut)

            // Downstream on this thread, upstream on one more. Closing both
            // sockets when either direction ends is what stops half-open
            // sockets accumulating on a phone.
            //
            // getInputStream() is resolved HERE, inside this guarded scope,
            // and not inside the pump lambda. Socket.getInputStream() throws
            // SocketException("Socket is closed") the moment the socket is
            // closed, and as the first statement of a bare Thread body that
            // throw had nothing above it to catch: a raw thread's uncaught
            // exception reaches the default handler, which kills the whole
            // process. A field crash arrived on exactly that line — Tor's
            // front proxy died and took the app with it — because the flow can
            // be torn down in the window between start() and the lambda's
            // first statement: either direction finishing closes both sockets,
            // and stop() closes every one of them at once.
            //
            // Resolved on the owning thread the throw lands in the enclosing
            // catch instead, which is the correct outcome: one dead flow,
            // reset by lwIP, and a tunnel that stays up.
            val clientIn = client.getInputStream()
            val pump = Thread({
                try {
                    pipe(clientIn, upOut, txBytes)
                } catch (_: Throwable) {
                    // Per-flow and unreportable, but never fatal. A relay
                    // thread for one TCP flow must not be able to end the
                    // session, whatever it hits.
                } finally {
                    closeQuietly(upstream)
                    closeQuietly(client)
                }
            }, "tor-front-up").apply { isDaemon = true }
            pump.start()

            pipe(upIn, clientOut, rxBytes)
        } catch (e: Exception) {
            // Nothing useful to report per-flow; lwIP will reset the stream.
        } finally {
            closeQuietly(upstream)
            closeQuietly(client)
        }
    }

    /** How long a free proxy gets to answer its own handshake for one flow. */
    private const val EXIT_HANDSHAKE_TIMEOUT_MS = 15_000

    /** An HTTP proxy's CONNECT to the target: true once it answered 200 and the stream carries the target. */
    private fun exitHttpConnect(upIn: DataInputStream, upOut: OutputStream, target: String, port: Int): Boolean {
        val authority = (if (target.indexOf(':') >= 0) "[$target]" else target) + ":" + port
        upOut.write("CONNECT $authority HTTP/1.1\r\nHost: $authority\r\nProxy-Connection: keep-alive\r\n\r\n".toByteArray(Charsets.US_ASCII))
        upOut.flush()
        // The head, byte by byte up to the blank line: anything after it already belongs to the target.
        val head = StringBuilder()
        while (head.length < 8192 && !head.endsWith("\r\n\r\n")) {
            val b = upIn.read()
            if (b < 0) return false
            head.append(b.toChar())
        }
        return Regex("""^HTTP/1\.[01] 200""").containsMatchIn(head)
    }

    /** A SOCKS5 proxy's CONNECT to the target, without authentication: true on its success reply. */
    private fun exitSocks5(upIn: DataInputStream, upOut: OutputStream, target: String, port: Int): Boolean {
        upOut.write(byteArrayOf(SOCKS_VERSION.toByte(), 1, 0x00))
        upOut.flush()
        if (upIn.read() != SOCKS_VERSION || upIn.read() != 0x00) return false
        val request: ByteArray = if (isIpLiteral(target)) {
            val a = InetAddress.getByName(target).address
            byteArrayOf(SOCKS_VERSION.toByte(), CMD_CONNECT.toByte(), 0, (if (a.size == 16) ATYP_IPV6 else ATYP_IPV4).toByte()) +
                a + byteArrayOf(((port shr 8) and 0xFF).toByte(), (port and 0xFF).toByte())
        } else {
            val name = target.toByteArray(Charsets.US_ASCII)
            if (name.size > 255) return false
            byteArrayOf(SOCKS_VERSION.toByte(), CMD_CONNECT.toByte(), 0, ATYP_DOMAIN.toByte(), name.size.toByte()) +
                name + byteArrayOf(((port shr 8) and 0xFF).toByte(), (port and 0xFF).toByte())
        }
        upOut.write(request)
        upOut.flush()
        if (upIn.read() != SOCKS_VERSION) return false
        val reply = upIn.read()
        upIn.read()
        when (upIn.read()) {
            ATYP_IPV4 -> upIn.readFully(ByteArray(4))
            ATYP_IPV6 -> upIn.readFully(ByteArray(16))
            ATYP_DOMAIN -> { val n = upIn.read(); if (n > 0) upIn.readFully(ByteArray(n)) }
            else -> return false
        }
        upIn.readFully(ByteArray(2))
        return reply == REP_SUCCESS
    }

    /**
     * A SOCKS4 proxy's CONNECT: an IPv4 target as SOCKS4, a name as SOCKS4a (the proxy resolves it);
     * an IPv6 target cannot be asked for at all -- that one flow fails, the rest go on.
     */
    private fun exitSocks4(upIn: DataInputStream, upOut: OutputStream, target: String, port: Int): Boolean {
        val portBytes = byteArrayOf(((port shr 8) and 0xFF).toByte(), (port and 0xFF).toByte())
        val request = if (isIpLiteral(target)) {
            val a = InetAddress.getByName(target).address
            if (a.size != 4) return false
            byteArrayOf(4, 1) + portBytes + a + byteArrayOf(0)
        } else {
            byteArrayOf(4, 1) + portBytes + byteArrayOf(0, 0, 0, 1, 0) + target.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        }
        upOut.write(request)
        upOut.flush()
        val reply = ByteArray(8)
        upIn.readFully(reply)
        return reply[1].toInt() and 0xFF == 0x5A
    }

    private fun pipe(
        from: InputStream,
        to: OutputStream,
        counter: java.util.concurrent.atomic.AtomicLong,
    ) {
        val buffer = ByteArray(RELAY_BUFFER)
        try {
            while (true) {
                val read = from.read(buffer)
                if (read < 0) break
                to.write(buffer, 0, read)
                to.flush()
                counter.addAndGet(read.toLong())
            }
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------- udpgw server

    /**
     * Speak badvpn's udpgw protocol on an established stream.
     *
     * Wire format, from `protocol/packetproto.h` and `protocol/udpgw_proto.h`:
     *
     * ```
     *   uint16 LE  length of everything that follows
     *   uint8      flags        (KEEPALIVE 1, REBIND 2, DNS 4, IPV6 8)
     *   uint16 LE  conid
     *   [ipv4] uint32 addr, uint16 port      <- both NETWORK byte order
     *   [ipv6] uint8[16] addr, uint16 port
     *   uint8[]    UDP payload
     * ```
     *
     * The address bytes are network order because badvpn copies them straight
     * out of the IP and UDP headers (`BAddr_InitIPv4(&remote_addr,
     * ipv4_header.destination_address, udp_header.dest_port)`) and writes them
     * back out unswapped. Byte-swapping them here would make the client's
     * `BAddr_CompareOrder` check reject every reply.
     */
    private fun serveUdpgw(socket: Socket, input: DataInputStream, output: OutputStream) {
        val via = if (localDnsPort > 0) "local DNSPort $localDnsPort" else "TCP to $dnsOverTcp"
        ConnectionLog.record("$TAG udpgw stream up — DNS via $via")
        // Serialises replies from the DNS pool onto the one shared stream.
        val writeLock = Any()
        try {
            while (running.get()) {
                val low = input.read()
                if (low < 0) break
                val high = input.read()
                if (high < 0) break
                val length = (high shl 8) or low
                if (length < 0 || length > 65535) break
                val body = ByteArray(length)
                input.readFully(body)

                // header is flags(1) + conid(2)
                if (body.size < 3) continue
                val flags = body[0].toInt() and 0xFF
                val conid = ((body[2].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)

                if (flags and FLAG_KEEPALIVE != 0) {
                    // Header-only packet whose entire purpose is to keep the
                    // stream warm. There is nothing to answer.
                    continue
                }

                val isIpv6 = flags and FLAG_IPV6 != 0
                val addressLength = if (isIpv6) 18 else 6
                if (body.size < 3 + addressLength) continue
                val address = body.copyOfRange(3, 3 + addressLength)
                val payload = body.copyOfRange(3 + addressLength, body.size)

                val port = ((address[addressLength - 2].toInt() and 0xFF) shl 8) or
                    (address[addressLength - 1].toInt() and 0xFF)

                // Two ways a query counts as DNS: the transparent-DNS flag that
                // tun2socks sets for traffic aimed at the TUN's own resolver
                // address, and an explicit query to port 53 from an app that
                // hardcodes 8.8.8.8. Both must work or those apps go dark.
                val isDns = (flags and FLAG_DNS != 0) || port == 53
                if (!isDns || payload.isEmpty()) continue

                val pool = dnsPool ?: break
                try {
                    pool.execute {
                        // Same reason as the connection pool in start(): an
                        // uncaught throw here would kill the process, not just
                        // this query.
                        try {
                            resolve(payload, conid, address, isIpv6, output, writeLock)
                        } catch (_: Throwable) {
                        }
                    }
                } catch (e: Exception) {
                    // Shutting down.
                    break
                }
            }
        } catch (e: Exception) {
            // Stream closed by tun2socks, or we are stopping.
        } finally {
            closeQuietly(socket)
            ConnectionLog.record("$TAG udpgw stream closed")
        }
    }

    private fun resolve(
        query: ByteArray,
        conid: Int,
        address: ByteArray,
        isIpv6: Boolean,
        output: OutputStream,
        writeLock: Any,
    ) {
        val answer = (
            if (localDnsPort > 0) resolveOverLocalUdp(query) else resolveOverUpstreamTcp(query)
            )
            // A failure is answered with silence rather than a SERVFAIL, on purpose: a
            // timeout is the normal outcome for query types Tor's DNSPort will not answer
            // (HTTPS/SVCB, SRV), and silence makes the client's resolver retry or fall
            // back, which is what we want it to do.
            ?: return

        val body = ByteArray(3 + address.size + answer.size)
        body[0] = (if (isIpv6) FLAG_IPV6 else 0).toByte()
        body[1] = (conid and 0xFF).toByte()
        body[2] = ((conid shr 8) and 0xFF).toByte()
        System.arraycopy(address, 0, body, 3, address.size)
        System.arraycopy(answer, 0, body, 3 + address.size, answer.size)

        synchronized(writeLock) {
            try {
                output.write(body.size and 0xFF)
                output.write((body.size shr 8) and 0xFF)
                output.write(body)
                output.flush()
            } catch (e: Exception) {
                // Stream gone; the read loop will notice and exit.
            }
        }
    }

    /** Tor's DNSPort, straight over loopback UDP. */
    private fun resolveOverLocalUdp(query: ByteArray): ByteArray? = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = DNS_TIMEOUT_MS
            socket.send(
                DatagramPacket(
                    query, query.size,
                    InetAddress.getByName("127.0.0.1"), localDnsPort,
                )
            )
            val buffer = ByteArray(DNS_BUFFER)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            buffer.copyOf(response.length)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * DNS over TCP (RFC 7766) to [dnsOverTcp], dialled through the upstream SOCKS.
     *
     * The query the client sent is a complete DNS message already, so the only
     * difference from the UDP form is the two-byte big-endian length in front of it,
     * both ways. Nothing is parsed or rewritten: whatever the resolver answers is
     * handed back to the client verbatim, which keeps EDNS0, DNSSEC records and query
     * types this code has never heard of working.
     *
     * One connection per query. Reusing one would mean tracking outstanding message
     * IDs across a shared stream, and DNS lookups on a phone are rare enough that a
     * pooled connection would buy a few milliseconds for a whole class of ordering
     * bugs.
     */
    private fun resolveOverUpstreamTcp(query: ByteArray): ByteArray? {
        val resolver = dnsOverTcp ?: return null
        val host = resolver.substringBeforeLast(':', resolver)
        val port = resolver.substringAfterLast(':', "53").toIntOrNull() ?: 53
        var socket: Socket? = null
        return try {
            val upstream = Socket().apply {
                tcpNoDelay = true
                soTimeout = DNS_TIMEOUT_MS
                connect(InetSocketAddress("127.0.0.1", upstreamSocksPort), DNS_TIMEOUT_MS)
            }
            socket = upstream
            val input = DataInputStream(BufferedInputStream(upstream.getInputStream()))
            val output = BufferedOutputStream(upstream.getOutputStream())

            output.write(byteArrayOf(SOCKS_VERSION.toByte(), 1, 0x00))
            output.flush()
            if (input.read() != SOCKS_VERSION || input.read() != 0x00) return null

            val name = host.toByteArray(Charsets.US_ASCII)
            output.write(
                byteArrayOf(
                    SOCKS_VERSION.toByte(), CMD_CONNECT.toByte(), 0,
                    ATYP_DOMAIN.toByte(), name.size.toByte(),
                )
            )
            output.write(name)
            output.write(byteArrayOf((port shr 8).toByte(), port.toByte()))
            output.flush()

            if (input.read() != SOCKS_VERSION || input.read() != REP_SUCCESS) return null
            input.read() // reserved
            // The bound address has to be consumed or its bytes would be read as the
            // first bytes of the DNS reply.
            when (input.read()) {
                ATYP_IPV4 -> input.readFully(ByteArray(4))
                ATYP_IPV6 -> input.readFully(ByteArray(16))
                ATYP_DOMAIN -> input.readFully(ByteArray(input.read().coerceAtLeast(0)))
                else -> return null
            }
            input.readFully(ByteArray(2)) // bound port

            output.write((query.size shr 8) and 0xFF)
            output.write(query.size and 0xFF)
            output.write(query)
            output.flush()

            val length = ((input.read() and 0xFF) shl 8) or (input.read() and 0xFF)
            if (length !in 1..DNS_BUFFER) return null
            ByteArray(length).also { input.readFully(it) }
        } catch (e: Exception) {
            null
        } finally {
            closeQuietly(socket)
        }
    }

    private fun closeQuietly(closeable: java.io.Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }
}
