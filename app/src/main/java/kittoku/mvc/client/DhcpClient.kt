package kittoku.mvc.client

import kittoku.mvc.ControlMessage
import kittoku.mvc.Result
import kittoku.mvc.SharedBridge
import kittoku.mvc.Where
import kittoku.mvc.extension.isSame
import kittoku.mvc.extension.read
import kittoku.mvc.unit.ETHERNET_BROADCAST_ADDRESS
import kittoku.mvc.unit.ETHER_TYPE_IPv4
import kittoku.mvc.unit.EthernetFrame
import kittoku.mvc.unit.IP_PROTOCOL_UDP
import kittoku.mvc.unit.IPv4Packet
import kittoku.mvc.unit.IPv4_ADDRESS_SIZE
import kittoku.mvc.unit.IPv4_BROADCAST_ADDRESS
import kittoku.mvc.unit.IPv4_UNKNOWN_ADDRESS
import kittoku.mvc.unit.UDPDatagram
import kittoku.mvc.unit.UDP_PORT_DHCP_CLIENT
import kittoku.mvc.unit.UDP_PORT_DHCP_SEVER
import kittoku.mvc.unit.dhcp.DHCP_MESSAGE_TYPE_ACK
import kittoku.mvc.unit.dhcp.DHCP_MESSAGE_TYPE_DISCOVER
import kittoku.mvc.unit.dhcp.DHCP_MESSAGE_TYPE_OFFER
import kittoku.mvc.unit.dhcp.DHCP_MESSAGE_TYPE_REQUEST
import kittoku.mvc.unit.dhcp.DHCP_OPCODE_BOOT_REQUEST
import kittoku.mvc.unit.dhcp.DHCP_OPTION_DHCP_SERVER_ADDRESS
import kittoku.mvc.unit.dhcp.DHCP_OPTION_DNS_SERVER_ADDRESS
import kittoku.mvc.unit.dhcp.DHCP_OPTION_LEASE_TIME
import kittoku.mvc.unit.dhcp.DHCP_OPTION_MESSAGE_TYPE
import kittoku.mvc.unit.dhcp.DHCP_OPTION_PARAMETER_LIST
import kittoku.mvc.unit.dhcp.DHCP_OPTION_REQUESTED_ADDRESS
import kittoku.mvc.unit.dhcp.DHCP_OPTION_ROUTER_ADDRESS
import kittoku.mvc.unit.dhcp.DHCP_OPTION_SUBNET_MASK
import kittoku.mvc.unit.dhcp.DhcpMessage
import kittoku.mvc.unit.dhcp.OptionPack
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull


internal const val DHCP_RESEND_MESSAGE_TIMEOUT: Long = 3_000
internal const val DHCP_NEGOTIATION_TIMEOUT: Long = 30_000

internal class DhcpClient(private val bridge: SharedBridge) {
    internal val mailbox = Channel<EthernetFrame>(Channel.BUFFERED)
    private var jobNegotiation: Job? = null

    internal fun launchJobNegotiation() {
        jobNegotiation = bridge.scope.launch(bridge.handler) {
            while (isActive) {
                val offer = startDiscoverOfferSequence(DHCP_RESEND_MESSAGE_TIMEOUT) ?: continue
                val ack = startRequestAckSequence(offer, DHCP_RESEND_MESSAGE_TIMEOUT) ?: continue

                if (!registerDhcpInformation(ack)) {
                    bridge.controlMailbox.send(ControlMessage(Where.DHCP, Result.INVALID_CONFIGURATION))
                }

                break
            }

            bridge.controlMailbox.send(ControlMessage(Where.DHCP, Result.PROCEEDED))
        }
    }

    private fun generateBasicOptionsParameters(): ByteArray {
        val parameters = listOf(
            DHCP_OPTION_SUBNET_MASK,
            DHCP_OPTION_ROUTER_ADDRESS,
            DHCP_OPTION_DNS_SERVER_ADDRESS,
        )

        return parameters.toByteArray()
    }

    private fun extractMessageToMe(frame: EthernetFrame): DhcpMessage? {
        val isBroadcastFrame = frame.dstMac.isSame(ETHERNET_BROADCAST_ADDRESS)
        val isToMeFrame = frame.dstMac.isSame(bridge.clientMacAddress)
        if (!(isBroadcastFrame || isToMeFrame)) {
            return null
        }

        val datagram = frame.payloadIPv4Packet!!.payloadUDPDatagram!!
        if (datagram.dstPort != UDP_PORT_DHCP_CLIENT || datagram.srcPort != UDP_PORT_DHCP_SEVER) {
            return null
        }

        val message = datagram.payloadDhcpMessage!!
        if (!message.clientMacAddress.isSame(bridge.clientMacAddress)) {
            return null
        }

        return datagram.payloadDhcpMessage!!
    }

    private suspend fun sendAsBroadcast(message: DhcpMessage) {
        val datagram = UDPDatagram().also {
            it.dstPort = UDP_PORT_DHCP_SEVER
            it.srcPort = UDP_PORT_DHCP_CLIENT
            it.payloadDhcpMessage = message
        }

        val packet = IPv4Packet().also {
            it.protocol = IP_PROTOCOL_UDP
            it.identification = bridge.random.nextInt().toShort()
            it.dstAddress.read(IPv4_BROADCAST_ADDRESS)
            it.srcAddress.read(IPv4_UNKNOWN_ADDRESS)
            it.payloadUDPDatagram = datagram

            datagram.importIPv4Header(it)
        }

        val frame = EthernetFrame().also {
            it.etherType = ETHER_TYPE_IPv4
            it.dstMac.read(ETHERNET_BROADCAST_ADDRESS)
            it.srcMac.read(bridge.clientMacAddress)
            it.payloadIPv4Packet = packet
        }

        bridge.tcpTerminal!!.sendFrame(frame)
    }

    private suspend fun expectOfferMessage(transactionId: Int): DhcpMessage? {
        while (true) {
            val reply = extractMessageToMe(mailbox.receive()) ?: continue
            if (reply.transactionId != transactionId) continue

            return if (reply.options.byteOptions[DHCP_OPTION_MESSAGE_TYPE] == DHCP_MESSAGE_TYPE_OFFER) {
                reply
            } else null
        }
    }

    private suspend fun expectAckMessage(transactionId: Int): DhcpMessage? {
        while (true) {
            val reply = extractMessageToMe(mailbox.receive()) ?: continue
            if (reply.transactionId != transactionId) continue

            return if (reply.options.byteOptions[DHCP_OPTION_MESSAGE_TYPE] == DHCP_MESSAGE_TYPE_ACK) {
                reply
            } else null
        }
    }

    private suspend fun startDiscoverOfferSequence(timeout: Long): DhcpMessage? {
         return withTimeoutOrNull(timeout) {
             val transactionId = bridge.random.nextInt()

             val options = OptionPack().also {
                 it.byteOptions[DHCP_OPTION_MESSAGE_TYPE] = DHCP_MESSAGE_TYPE_DISCOVER
                 it.variableOptions[DHCP_OPTION_PARAMETER_LIST] = generateBasicOptionsParameters()
             }

             val message = DhcpMessage().also {
                 it.opcode = DHCP_OPCODE_BOOT_REQUEST
                 it.transactionId = transactionId
                 it.clientMacAddress.read(bridge.clientMacAddress)
                 it.options = options
             }

             sendAsBroadcast(message)
             expectOfferMessage(transactionId)
         }
    }

    private suspend fun startRequestAckSequence(offer: DhcpMessage, timeout: Long): DhcpMessage? {
        return withTimeoutOrNull(timeout) {
            val options = OptionPack().also {
                it.byteOptions[DHCP_OPTION_MESSAGE_TYPE] = DHCP_MESSAGE_TYPE_REQUEST
                it.addressOptions[DHCP_OPTION_REQUESTED_ADDRESS] = offer.yourIpAddress.copyOf()
                it.addressOptions[DHCP_OPTION_DHCP_SERVER_ADDRESS] = offer.options.addressOptions[DHCP_OPTION_DHCP_SERVER_ADDRESS]!!.copyOf()
                it.variableOptions[DHCP_OPTION_PARAMETER_LIST] = generateBasicOptionsParameters()
            }

            val message = DhcpMessage().also {
                it.opcode = DHCP_OPCODE_BOOT_REQUEST
                it.transactionId = offer.transactionId
                it.clientMacAddress.read(bridge.clientMacAddress)
                it.options = options
            }

            sendAsBroadcast(message)
            expectAckMessage(offer.transactionId)
        }
    }

    private fun registerDhcpInformation(ack: DhcpMessage): Boolean {
        if (ack.yourIpAddress.isSame(IPv4_UNKNOWN_ADDRESS)) return false
        bridge.assignedIpAddress.read(ack.yourIpAddress)

        val subnetMask = ack.options.addressOptions[DHCP_OPTION_SUBNET_MASK] ?: return false
        if (subnetMask.isSame(IPv4_UNKNOWN_ADDRESS)) return false
        bridge.subnetMask.read(subnetMask)

        val givenDefaultGatewayAddresses = ack.options.variableOptions[DHCP_OPTION_ROUTER_ADDRESS] ?: return false
        val defaultGatewayAddress = givenDefaultGatewayAddresses.sliceArray(0 until IPv4_ADDRESS_SIZE) // use only the first address
        if (defaultGatewayAddress.isSame(IPv4_UNKNOWN_ADDRESS)) return false
        bridge.defaultGatewayIpAddress.read(defaultGatewayAddress)

        val dhcpServerAddress = ack.options.addressOptions[DHCP_OPTION_DHCP_SERVER_ADDRESS] ?: return false
        if (dhcpServerAddress.isSame(IPv4_UNKNOWN_ADDRESS)) return false
        bridge.dhcpServerIpAddress.read(dhcpServerAddress)

        ack.options.variableOptions[DHCP_OPTION_DNS_SERVER_ADDRESS]?.also {
            if (it.isSame(IPv4_UNKNOWN_ADDRESS)) return false
            bridge.dnsServerIpAddress = it.sliceArray(0 until IPv4_ADDRESS_SIZE) // use only the first address
        }

        ack.options.intOptions[DHCP_OPTION_LEASE_TIME]?.also {
            if (it < 0) return false
            bridge.leaseTime = it.toLong() * 1_000 // as millisecond
        }

        return true
    }

    internal fun cancel() {
        jobNegotiation?.cancel()
        mailbox.close()
    }
}