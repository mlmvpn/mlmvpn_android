package kittoku.mvc.teminal

import kittoku.mvc.extension.match
import kittoku.mvc.extension.probeByte
import kittoku.mvc.extension.probeShort
import kittoku.mvc.unit.ETHERNET_BROADCAST_ADDRESS
import kittoku.mvc.unit.ETHERNET_HEADER_SIZE
import kittoku.mvc.unit.IP_PROTOCOL_UDP
import kittoku.mvc.unit.IPv4_BROADCAST_ADDRESS
import kittoku.mvc.unit.IPv4_HEADER_SIZE
import kittoku.mvc.unit.UDP_PORT_DHCP_CLIENT
import java.nio.ByteBuffer


private const val START_IP_DST = ETHERNET_HEADER_SIZE + 16

internal fun isToMeFrame(buffer: ByteBuffer, myMacAddress: ByteArray): Boolean {
    if (buffer.array().match(myMacAddress, buffer.position())) {
        return true
    }

    if (buffer.array().match(ETHERNET_BROADCAST_ADDRESS, buffer.position())) {
        return true
    }

    return false
}

internal fun isToMePacket(buffer: ByteBuffer, myIPAddress: ByteArray): Boolean {
    if (buffer.array().match(myIPAddress, buffer.position() + START_IP_DST)) {
        return true
    }

    if (buffer.array().match(IPv4_BROADCAST_ADDRESS, buffer.position() + START_IP_DST)) {
        return true
    }

    return false
}

internal fun isDhcpPacket(buffer: ByteBuffer): Boolean { // DHCP message is handled by this app, not native stack
    if (buffer.probeByte(ETHERNET_HEADER_SIZE + 9) != IP_PROTOCOL_UDP) {
        return false
    }

    return buffer.probeShort(ETHERNET_HEADER_SIZE + IPv4_HEADER_SIZE + Short.SIZE_BYTES) == UDP_PORT_DHCP_CLIENT
}
