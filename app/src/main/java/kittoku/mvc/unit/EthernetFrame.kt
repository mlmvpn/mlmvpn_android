package kittoku.mvc.unit

import kittoku.mvc.debug.assertAlways
import java.nio.ByteBuffer

internal const val ETHER_TYPE_IPv4: Short = 0x0800
internal const val ETHER_TYPE_ARP: Short = 0x0806

internal const val ETHERNET_MAC_ADDRESS_SIZE = 6
internal val ETHERNET_UNKNOWN_ADDRESS: ByteArray = ByteArray(ETHERNET_MAC_ADDRESS_SIZE)
internal val ETHERNET_BROADCAST_ADDRESS: ByteArray = ByteArray(ETHERNET_MAC_ADDRESS_SIZE) { -1 }

internal const val ETHERNET_HEADER_SIZE = 2 * ETHERNET_MAC_ADDRESS_SIZE + Short.SIZE_BYTES
internal const val ETHERNET_MAX_MTU = 1500

internal class EthernetFrame : DataUnit {
    internal val dstMac = ByteArray(ETHERNET_MAC_ADDRESS_SIZE)
    internal val srcMac = ByteArray(ETHERNET_MAC_ADDRESS_SIZE)
    internal var etherType: Short = 0

    internal var payloadARPPacket: ARPPacket? = null
    internal var payloadIPv4Packet: IPv4Packet? = null
    internal var payloadUnknown: ByteArray? = null

    private val validPayloadLength: Int
        get() {
            return when {
                payloadARPPacket != null -> payloadARPPacket!!.length
                payloadIPv4Packet != null -> payloadIPv4Packet!!.length
                payloadUnknown != null -> payloadUnknown!!.size
                else -> 0
            }
        }

    override val length: Int
        get() = ETHERNET_HEADER_SIZE + validPayloadLength

    override fun write(buffer: ByteBuffer) {
        buffer.put(dstMac)
        buffer.put(srcMac)
        buffer.putShort(etherType)

        when {
            payloadARPPacket != null -> payloadARPPacket!!.write(buffer)
            payloadIPv4Packet != null -> payloadIPv4Packet!!.write(buffer)
            payloadUnknown != null -> buffer.put(payloadUnknown!!)
        }
    }

    override fun read(buffer: ByteBuffer) {
        readWithGivenSize(buffer.int, buffer)
    }

    internal fun readWithGivenSize(frameSize: Int, buffer: ByteBuffer) {
        val frameStop = buffer.position() + frameSize

        val payloadLength = frameSize - ETHERNET_HEADER_SIZE
        assertAlways(payloadLength > 0)

        buffer.get(dstMac)
        buffer.get(srcMac)
        etherType = buffer.short

        if (payloadLength > 0) {
            when (etherType) {
                ETHER_TYPE_ARP -> {
                    val startArp = buffer.position()

                    val isEthernet = buffer.short == ARP_HARDWARE_TYPE_ETHERNET
                    val isIPv4 = buffer.short == ETHER_TYPE_IPv4

                    if (isEthernet && isIPv4) {
                        buffer.position(startArp)
                        payloadARPPacket = ARPPacket().also { it.read(buffer) }
                    } else {
                        buffer.position(startArp)
                        payloadUnknown = ByteArray(payloadLength).also { buffer.get(it) }
                    }
                }

                ETHER_TYPE_IPv4 -> payloadIPv4Packet = IPv4Packet().also { it.read(buffer) }
                else -> payloadUnknown = ByteArray(payloadLength).also { buffer.get(it) }
            }
        }

        buffer.position(frameStop) // make sure that padding is discarded
    }
}