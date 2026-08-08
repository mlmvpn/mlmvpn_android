package kittoku.mvc.client

import kittoku.mvc.ControlMessage
import kittoku.mvc.Result
import kittoku.mvc.SharedBridge
import kittoku.mvc.Where
import kittoku.mvc.extension.isSame
import kittoku.mvc.extension.read
import kittoku.mvc.unit.ARPPacket
import kittoku.mvc.unit.ARP_OPCODE_REPLY
import kittoku.mvc.unit.ARP_OPCODE_REQUEST
import kittoku.mvc.unit.ETHERNET_BROADCAST_ADDRESS
import kittoku.mvc.unit.ETHERNET_UNKNOWN_ADDRESS
import kittoku.mvc.unit.ETHER_TYPE_ARP
import kittoku.mvc.unit.EthernetFrame
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull


internal const val ARP_NEGOTIATION_TIMEOUT: Long = 30_000
internal class ARPClient(private val bridge: SharedBridge) {
    internal val mailbox = Channel<EthernetFrame>(Channel.BUFFERED)
    private var jobNegotiation: Job? = null
    private var jobControl: Job? = null

    internal fun launchJobNegotiation() { // resolve default gateway MAC address
        jobNegotiation = bridge.scope.launch(bridge.handler) {
            while (isActive) {
                val reply = startResolveDefaultGatewaySequence(DHCP_RESEND_MESSAGE_TIMEOUT) ?: continue

                if (!registerArpInformation(reply)) {
                    bridge.controlMailbox.send(ControlMessage(Where.ARP, Result.INVALID_CONFIGURATION))
                }

                break
            }

            bridge.controlMailbox.send(ControlMessage(Where.ARP, Result.PROCEEDED))
        }
    }

    internal fun launchJobControl() {
        jobControl = bridge.scope.launch(bridge.handler) {
            while (isActive) {
                val received = mailbox.receive()

                if (isARPRequest(received)) {
                    processARPRequest(received)
                }
            }
        }
    }

    private fun isARPRequest(frame: EthernetFrame): Boolean {
        val arpPacket = frame.payloadARPPacket ?: return false

        return (arpPacket.targetIp.isSame(bridge.assignedIpAddress) && arpPacket.opcode == ARP_OPCODE_REQUEST)
    }

    private suspend fun processARPRequest(requestFrame: EthernetFrame) {
        val requestPacket = requestFrame.payloadARPPacket!!

        val replyPacket = ARPPacket().also {
            it.opcode = ARP_OPCODE_REPLY
            it.senderIp.read(bridge.assignedIpAddress)
            it.senderMac.read(bridge.clientMacAddress)
            it.targetIp.read(requestPacket.senderIp)
            it.targetMac.read(requestPacket.senderMac)
        }

        EthernetFrame().also {
            it.etherType = ETHER_TYPE_ARP
            it.dstMac.read(requestFrame.srcMac)
            it.srcMac.read(bridge.clientMacAddress)
            it.payloadARPPacket = replyPacket

            bridge.tcpTerminal!!.sendFrame(it)
        }
    }

    private fun extractMessageToMe(frame: EthernetFrame): ARPPacket? {
        val isBroadcastFrame = frame.dstMac.isSame(ETHERNET_BROADCAST_ADDRESS)
        val isToMeFrame = frame.dstMac.isSame(bridge.clientMacAddress)
        if (!(isBroadcastFrame || isToMeFrame)) {
            return null
        }

        return frame.payloadARPPacket!!
    }

    private suspend fun sendAsBroadcast(packet: ARPPacket) {
        val frame = EthernetFrame().also {
            it.etherType = ETHER_TYPE_ARP
            it.dstMac.read(ETHERNET_BROADCAST_ADDRESS)
            it.srcMac.read(bridge.clientMacAddress)
            it.payloadARPPacket = packet
        }

        bridge.tcpTerminal!!.sendFrame(frame)
    }

    private suspend fun expectReplyPacket(): ARPPacket? {
        while (true) {
            val reply = extractMessageToMe(mailbox.receive()) ?: continue

            return if (reply.opcode == ARP_OPCODE_REPLY) {
                reply
            } else null
        }
    }

    private suspend fun startResolveDefaultGatewaySequence(timeout: Long): ARPPacket? {
        return withTimeoutOrNull(timeout) {
            val packet = ARPPacket().also {
                it.opcode = ARP_OPCODE_REQUEST
                it.senderIp.read(bridge.assignedIpAddress)
                it.senderMac.read(bridge.clientMacAddress)
                it.targetIp.read(bridge.defaultGatewayIpAddress)
            }

            sendAsBroadcast(packet)
            expectReplyPacket()
        }
    }

    private fun registerArpInformation(reply: ARPPacket): Boolean {
        if (!reply.targetIp.isSame(bridge.assignedIpAddress)) return false
        if (!reply.targetMac.isSame(bridge.clientMacAddress)) return false
        if (!reply.senderIp.isSame(bridge.defaultGatewayIpAddress)) return false
        if (reply.senderMac.isSame(ETHERNET_UNKNOWN_ADDRESS)) return false
        if (reply.senderMac.isSame(ETHERNET_BROADCAST_ADDRESS)) return false

        bridge.defaultGatewayMacAddress.read(reply.senderMac)

        return true
    }

    internal fun cancel() {
        jobNegotiation?.cancel()
        jobControl?.cancel()
        mailbox.close()
    }
}