package kittoku.mvc.io.incoming

import kittoku.mvc.SharedBridge
import kittoku.mvc.client.ARPClient
import kittoku.mvc.client.DhcpClient
import kittoku.mvc.client.SoftEtherClient
import kittoku.mvc.extension.move
import kittoku.mvc.teminal.TCP_SOFTETHER_HEADER_SIZE
import kittoku.mvc.teminal.isDhcpPacket
import kittoku.mvc.teminal.isToMeFrame
import kittoku.mvc.teminal.isToMePacket
import kittoku.mvc.unit.ETHERNET_MAX_MTU
import kittoku.mvc.unit.ETHER_TYPE_ARP
import kittoku.mvc.unit.EthernetFrame
import kittoku.mvc.unit.HttpMessage
import kittoku.mvc.unit.KEEP_ALIVE_FRAME_NUM
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.math.max


internal class IncomingManager(internal val bridge: SharedBridge) {
    private val bufferSize = max(bridge.socket.session.applicationBufferSize, TCP_SOFTETHER_HEADER_SIZE + ETHERNET_MAX_MTU)
    private val buffer = ByteBuffer.allocate(bufferSize).also { it.limit(0) }

    internal var nextTCPKeepAlive = 0L
    internal var nextUDPKeepAlive = 0L
    internal var nextUDPInquireNATT = 0L

    private var jobTCP: Job? = null
    private var jobUDP: Job? = null

    internal var softEtherMailbox: Channel<HttpMessage>? = null
    internal var dhcpMailbox: Channel<EthernetFrame>? = null
    internal var arpMailbox: Channel<EthernetFrame>? = null

    internal fun <T> registerMailbox(client: T) {
        when (client) {
            is SoftEtherClient -> softEtherMailbox = client.mailbox
            is DhcpClient -> dhcpMailbox = client.mailbox
            is ARPClient -> arpMailbox = client.mailbox
            else -> throw NotImplementedError(client?.toString() ?: "")
        }
    }

    internal fun <T> unregisterMailbox(client: T) {
        when (client) {
            is SoftEtherClient -> softEtherMailbox = null
            is DhcpClient -> dhcpMailbox= null
            is ARPClient -> arpMailbox = null
            else -> throw NotImplementedError(client?.toString() ?: "")
        }
    }

    internal fun launchJobTCP() {
        jobTCP = bridge.scope.launch(bridge.handler) {
            while (isActive) {
                if (bridge.httpRequestChannel.receive()) {
                    relayHttpMessage(buffer)
                } else {
                    break
                }
            }

            while (isActive) {
                trySendTCPKeepAlive()

                bridge.tcpTerminal!!.ensureBytes(Int.SIZE_BYTES, buffer)
                val numFrame = buffer.int
                if (numFrame == KEEP_ALIVE_FRAME_NUM) {
                    buffer.move(-Int.SIZE_BYTES)
                    processKeepAlivePacket(buffer)
                    continue
                }

                repeat(numFrame) {
                    bridge.tcpTerminal!!.ensureBytes(Int.SIZE_BYTES, buffer)
                    val frameSize = buffer.int
                    bridge.tcpTerminal!!.ensureBytes(frameSize, buffer)

                    if (!isToMeFrame(buffer, bridge.clientMacAddress)) {
                        buffer.move(frameSize) // discard frame
                        return@repeat
                    }

                    if (isToMePacket(buffer, bridge.assignedIpAddress) && !isDhcpPacket(buffer)) {
                        processDataPacket(frameSize, buffer)
                        return@repeat
                    }

                    val frame = EthernetFrame().also {
                        it.readWithGivenSize(frameSize, buffer)
                    }

                    if (frame.etherType == ETHER_TYPE_ARP) {
                        arpMailbox?.send(frame)
                        return@repeat
                    }

                    if (frame.payloadIPv4Packet?.payloadUDPDatagram?.payloadDhcpMessage != null) {
                        dhcpMailbox?.send(frame)
                        return@repeat
                    }
                }
            }
        }
    }

    internal fun launchJobUDP() {
        jobUDP = bridge.scope.launch(bridge.handler) {
            while (isActive) {
                trySendUDPInquireNATT()
                trySendUDPKeepAlive()

                val received = bridge.udpTerminal!!.tryReceivePacket() ?: continue

                if (!isToMeFrame(received, bridge.clientMacAddress)) {
                    continue
                }

                if (isToMePacket(received, bridge.assignedIpAddress) && !isDhcpPacket(received)) {
                    processDataPacket(received.remaining(), received)
                    continue
                }

                val frame = EthernetFrame().also {
                    it.readWithGivenSize(received.remaining(), received)
                }

                if (frame.etherType == ETHER_TYPE_ARP) {
                    arpMailbox?.send(frame)
                    continue
                }

                if (frame.payloadIPv4Packet?.payloadUDPDatagram?.payloadDhcpMessage != null) {
                    dhcpMailbox?.send(frame)
                    continue
                }
            }
        }
    }

    internal fun cancel() {
        jobTCP?.cancel()
        jobUDP?.cancel()
    }
}