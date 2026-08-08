package kittoku.mvc.teminal

import kittoku.mvc.SharedBridge
import kittoku.mvc.extension.move
import kittoku.mvc.extension.padZeroByte
import kittoku.mvc.extension.toIntAsUShort
import kittoku.mvc.extension.toStringOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import kotlin.math.max
import kotlin.math.min


internal const val UDP_CIPHER_ALGORITHM = "ChaCha20-Poly1305"
internal const val CHACHA20_POLY1305_KEY_SIZE = 32
internal const val CHACHA20_POLY1305_NONCE_SIZE = 12
internal const val CHACHA20_POLY1305_TAG_SIZE = 16

private const val UDP_PACKET_BUFFER_SIZE = 1600
private const val UDP_SOCKET_RECEIVE_BUFFER_SIZE = 2097152
internal const val UDP_SOFTETHER_HEADER_SIZE = 23 // = Cookie + 2 * tick + size + Flag
private const val UDP_MAX_PADDING_SIZE = 32
private const val UDP_KEEP_ALIVE_TIMEOUT = 2_100
internal const val UDP_KEEP_ALIVE_MIN_INTERVAL = 500
internal const val UDP_KEEP_ALIVE_INTERVAL_DIFF = 500

private const val UDP_PACKET_AVAILABLE_TIME = 30_000

internal const val UDP_NATT_PORT = 5004
internal const val UDP_NATT_INTERVAL_INITIAL = 3_000
internal const val UDP_NATT_INTERVAL_MIN = 300_000
internal const val UDP_NATT_INTERVAL_DIFF = 300_000

internal const val UDP_NATT_IP_REGEX = """^IP=[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+"""
internal const val UDP_NATT_PORT_REGEX = """PORT=[0-9]+$"""

internal enum class UDPStatus {
    CLOSED,
    OPEN,
}

internal class UDPTerminal(private val bridge: SharedBridge) {
    private val socket: DatagramSocket
    private val config = bridge.udpAccelerationConfig!!

    private val clientCipher = Cipher.getInstance(UDP_CIPHER_ALGORITHM)
    private val serverCipher = Cipher.getInstance(UDP_CIPHER_ALGORITHM)

    private var lastReceivedTick: Long = 0
    private var lastReceivedClientTick: Long = 0
    private var lastReceivedServerTick: Long = 0


    private val socketMutex = Mutex()
    private val packetMutex = Mutex()

    private val incomingPacket =
        DatagramPacket(ByteArray(UDP_PACKET_BUFFER_SIZE), UDP_PACKET_BUFFER_SIZE)
    private val outgoingPacket =
        DatagramPacket(ByteArray(UDP_PACKET_BUFFER_SIZE), UDP_PACKET_BUFFER_SIZE)
    private val nonceHolder = ByteArray(CHACHA20_POLY1305_NONCE_SIZE)
    private val encryptBuffer = ByteBuffer.allocate(UDP_PACKET_BUFFER_SIZE)
    private val decryptBuffer = ByteBuffer.allocate(UDP_PACKET_BUFFER_SIZE)

    private val regexIP = Regex(UDP_NATT_IP_REGEX)
    private val regexPort = Regex(UDP_NATT_PORT_REGEX)

    init {
        val address = run {
            NetworkInterface.getNetworkInterfaces().iterator().forEach { nic ->
                nic.inetAddresses.iterator().forEach {
                    if (it is Inet4Address && !it.isAnyLocalAddress && !it.isLinkLocalAddress && !it.isLoopbackAddress) {
                        return@run it
                    }
                }
            }

            throw Exception("There is no available IP address.")
        }

        socket = DatagramSocket(0, address)
        socket.receiveBufferSize = UDP_SOCKET_RECEIVE_BUFFER_SIZE

        config.clientReportedAddress = address
        config.clientReportedPort = socket.localPort

        socket.soTimeout = UDP_KEEP_ALIVE_MIN_INTERVAL

        bridge.service.protect(socket)
    }

    private fun expectPacket(): Boolean {
        try {
            socket.receive(incomingPacket)
            return true
        } catch (_: SocketTimeoutException) { }

        return false
    }

    internal suspend fun sendPacket(packet: DatagramPacket) {
        socketMutex.withLock {
            socket.send(packet)
        }
    }

    internal suspend fun sendData(buffer: ByteBuffer) {
        packetMutex.withLock {
            val dataSize = buffer.remaining()

            encryptBuffer.clear()
            encryptBuffer.putInt(config.serverCookie)
            encryptBuffer.putLong(System.currentTimeMillis())
            encryptBuffer.putLong(lastReceivedServerTick)
            encryptBuffer.putShort(dataSize.toShort())
            encryptBuffer.padZeroByte(1) // flag
            encryptBuffer.put(buffer)

            val diff = bridge.maxInternalFrameSize - dataSize
            if (diff >= 0) {
                val randomSize = bridge.random.nextInt(min(diff, UDP_MAX_PADDING_SIZE) + 1)
                encryptBuffer.padZeroByte(randomSize)
            } else {
                throw NotImplementedError()
            }

            bridge.random.nextBytes(nonceHolder)
            clientCipher.init(Cipher.ENCRYPT_MODE, config.clientKey, IvParameterSpec(nonceHolder))

            val packetBuffer = ByteBuffer.wrap(outgoingPacket.data)
            packetBuffer.put(nonceHolder)

            clientCipher.doFinal(
                encryptBuffer.array(),
                0,
                encryptBuffer.position(),
                packetBuffer.array(),
                CHACHA20_POLY1305_NONCE_SIZE
            ).also {
                outgoingPacket.length = CHACHA20_POLY1305_NONCE_SIZE + it
            }

            if (config.status == UDPStatus.OPEN) {
                // fast lane
                outgoingPacket.address = config.serverCurrentAddress!!
                outgoingPacket.port = config.serverCurrentPort
                sendPacket(outgoingPacket)
            } else {
                config.validServerAddresses.forEach { address ->
                    outgoingPacket.address = address

                    config.validServerPorts.forEach { port ->
                        outgoingPacket.port = port
                        sendPacket(outgoingPacket)
                    }
                }
            }
        }
    }

    private fun processNATTInformation() {
        val text = incomingPacket.let {
            it.data.sliceArray(0 until it.length).toStringOrNull(Charsets.US_ASCII)
        } ?: return

        val resultIP = regexIP.find(text)?.value ?: return
        val resultPort = regexPort.find(text)?.value ?: return

        config.clientNATTAddress = Inet4Address.getByName(resultIP.substring(3)) as Inet4Address
        config.clientNATTPort = resultPort.substring(5).toInt()
    }

    internal fun tryReceivePacket(): ByteBuffer? {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastReceivedTick > UDP_KEEP_ALIVE_TIMEOUT) {
            config.status = UDPStatus.CLOSED
        }

        if (!expectPacket()) {
            return null
        }

        if (incomingPacket.address == config.nattAddress) {
            processNATTInformation()
            return null
        }

        if (incomingPacket.length < UDP_SOFTETHER_HEADER_SIZE) {
            return null
        }

        serverCipher.init(
            Cipher.DECRYPT_MODE,
            config.serverKey,
            IvParameterSpec(incomingPacket.data, 0, CHACHA20_POLY1305_NONCE_SIZE)
        )

        serverCipher.doFinal(
            incomingPacket.data,
            CHACHA20_POLY1305_NONCE_SIZE,
            incomingPacket.length - CHACHA20_POLY1305_NONCE_SIZE,
            decryptBuffer.array()
        ).also {
            decryptBuffer.position(0)
            decryptBuffer.limit(it)
        }

        if (decryptBuffer.int != config.clientCookie) {
            return null
        }

        val serverTick = decryptBuffer.long
        if (serverTick >= lastReceivedServerTick) {
            lastReceivedServerTick = serverTick

            config.serverCurrentAddress = incomingPacket.address as Inet4Address
            config.serverCurrentPort = incomingPacket.port
        } else {
            if (lastReceivedServerTick - serverTick >= UDP_PACKET_AVAILABLE_TIME) {
                return null
            }
        }

        val clientTick = decryptBuffer.long
        lastReceivedClientTick = max(clientTick, lastReceivedClientTick)
        if (lastReceivedClientTick + UDP_PACKET_AVAILABLE_TIME >= currentTime) {
            lastReceivedTick = currentTime
        }

        config.status = if (currentTime - lastReceivedTick > UDP_KEEP_ALIVE_TIMEOUT) {
            UDPStatus.CLOSED
        } else {
            UDPStatus.OPEN
        }

        val realDataSize = decryptBuffer.short.toIntAsUShort()

        decryptBuffer.move(1) // ignore flag

        if (realDataSize <= 0) {
            return null
        }

        decryptBuffer.limit(decryptBuffer.position() + realDataSize)

        return decryptBuffer
    }

    internal fun close() {
        socket.close()
    }
}