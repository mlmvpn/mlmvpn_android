package kittoku.mvc.teminal

import kittoku.mvc.SharedBridge
import kittoku.mvc.debug.assertAlways
import kittoku.mvc.extension.capacityAfterPayload
import kittoku.mvc.unit.EthernetFrame
import kittoku.mvc.unit.HttpMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory


internal const val TCP_SOFTETHER_HEADER_SIZE = 8 + 4 // for first frame; 8 == SoftEther header, 4 == Frame Header
/** Compact the receive buffer once the free tail drops below this, to keep reads large. */
private const val SLIDE_THRESHOLD = 4096

/** Socket buffers sized for a ~230 ms path rather than the platform default. */
private const val SOCKET_BUFFER_SIZE = 512 * 1024
private const val TCP_CONNECT_TIMEOUT = 10_000
private const val TCP_HANDSHAKE_TIMEOUT = 15_000
private const val TCP_CONTROL_UNIT_WAIT_TIMEOUT = 10
private const val TCP_DATA_UNIT_WAIT_TIMEOUT = 1_000
private const val TCP_KEEP_ALIVE_TIMEOUT = 20_000
internal const val TCP_KEEP_ALIVE_MIN_INTERVAL = TCP_KEEP_ALIVE_TIMEOUT / 5
internal const val TCP_KEEP_ALIVE_INTERVAL_DIFF = TCP_KEEP_ALIVE_TIMEOUT / 2 - TCP_KEEP_ALIVE_MIN_INTERVAL

internal class TCPTerminal(private val bridge: SharedBridge) {
    private val socket: SSLSocket

    private val outgoingBuffer = ByteBuffer.allocate(16384)
    private val mutex = Mutex()

    init {
        // MLMVPN: accept self-signed server certificates.
        //
        // SoftEther authenticates the session through the hub credentials and the watermark
        // exchange, not through PKI, and the upstream client does not require a trusted chain
        // either. Volunteer VPN Gate relays are all self-signed, so validating against the
        // system store rejected them with "Trust anchor for certification path not found"
        // before any SoftEther traffic — and on a line where the operator's own address range
        // is filtered, those volunteer relays are the only ones still reachable.
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(
            object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }
        )
        val socketFactory = javax.net.ssl.SSLContext.getInstance("TLS")
            .apply { init(null, trustAll, java.security.SecureRandom()) }
            .socketFactory

        // MLMVPN: bounded connect + handshake.
        //
        // createSocket(host, port) connects with no timeout and startHandshake() then blocks
        // with none either, so a server that never answers hangs this constructor forever —
        // before Controller has logged even its first stage. Not every VPN Gate relay runs a
        // SoftEther listener on 443, so that is a routine outcome, not an edge case.
        android.util.Log.d("SoftEther", "TLS connect ${bridge.serverHostname}:${bridge.serverPort}")
        socket = (socketFactory.createSocket() as SSLSocket).apply {
            connect(
                java.net.InetSocketAddress(bridge.serverHostname, bridge.serverPort),
                TCP_CONNECT_TIMEOUT
            )
            soTimeout = TCP_HANDSHAKE_TIMEOUT
        }

        if (bridge.sslVersion != "DEFAULT") {
            socket.enabledProtocols = arrayOf(bridge.sslVersion)
        }

        if (bridge.doSelectCipherSuites) {
            socket.enabledCipherSuites = socket.supportedCipherSuites.filter {
                // the order of suites should be kept
                bridge.selectedCipherSuites.contains(it)
            }.toTypedArray()
        }

        // MLMVPN: size the socket for the bandwidth-delay product before the handshake.
        //
        // These relays sit ~230 ms away, so the default buffers cap throughput long before the
        // link does: at 230 ms RTT a 16 KB window is worth well under a megabit. Must be set
        // before connect/handshake to take effect on the SYN window scale.
        try {
            socket.receiveBufferSize = SOCKET_BUFFER_SIZE
            socket.sendBufferSize = SOCKET_BUFFER_SIZE
            socket.tcpNoDelay = true
        } catch (e: Exception) {
            android.util.Log.w("SoftEther", "socket tuning rejected: ${e.message}")
        }

        socket.startHandshake()
        android.util.Log.d("SoftEther", "TLS up: ${socket.session.protocol} / ${socket.session.cipherSuite}")
        socket.soTimeout = TCP_CONTROL_UNIT_WAIT_TIMEOUT
        bridge.service.protect(socket)
        bridge.socket = socket
    }

    internal fun setTimeoutForData() {
        socket.soTimeout = TCP_DATA_UNIT_WAIT_TIMEOUT
    }


    internal suspend fun sendStream(buffer: ByteBuffer) {
        mutex.withLock {
            buffer.array().sliceArray(buffer.position() until buffer.limit())

            socket.outputStream.write(
                buffer.array(),
                buffer.position(),
                buffer.remaining()
            )

            socket.outputStream.flush()

            buffer.position(buffer.limit())
        }
    }

    private fun receiveStream(buffer: ByteBuffer) {
        try {
            val capacity = buffer.capacity() - buffer.limit()
            val readLength = socket.inputStream.read(buffer.array(), buffer.limit(), capacity)

            assertAlways(readLength >= 0)

            buffer.limit(buffer.limit() + readLength)
        } catch (_: SocketTimeoutException) { }
    }

    private fun extendStream(buffer: ByteBuffer) {
        val payloadLengthBeforeExtended = buffer.remaining()

        // MLMVPN: slide as soon as the free tail is small, not only when it is exactly zero.
        //
        // receiveStream() reads into `capacity - limit` bytes. As the limit creeps toward the
        // end of the buffer that window shrinks to almost nothing, so the socket gets read a
        // handful of bytes at a time — thousands of syscalls per megabyte — and the original
        // condition (`< 1`) only compacted once it had degraded all the way to zero.
        if (buffer.capacityAfterPayload() < SLIDE_THRESHOLD) {
            buffer.get(buffer.array(), 0, payloadLengthBeforeExtended) // slide

            // update startPayload
            buffer.position(0)

            // update stopPayload
            buffer.limit(payloadLengthBeforeExtended)
        }

        receiveStream(buffer)
    }

    internal suspend fun ensureBytes(minBytes: Int, buffer: ByteBuffer) {
        while (true) {
            yield()

            if (buffer.remaining() >= minBytes) {
                return
            } else {
                extendStream(buffer)
            }
        }
    }

    internal suspend fun sendFrame(frame: EthernetFrame) {
        val buffer = ByteBuffer.allocate(2 * Int.SIZE_BYTES + frame.length)
        buffer.clear()
        buffer.putInt(1)
        buffer.putInt(frame.length)
        frame.write(buffer)
        buffer.flip()
        sendStream(buffer)
    }

    internal suspend fun sendHttpMessage(message: HttpMessage) {
        outgoingBuffer.clear()
        message.write(outgoingBuffer)
        outgoingBuffer.flip()
        sendStream(outgoingBuffer)
    }

    internal fun close() {
        socket.close()
    }
}