package kittoku.mvc.unit.dhcp

import kittoku.mvc.debug.assertAlways
import kittoku.mvc.extension.move
import kittoku.mvc.extension.padZeroByte
import kittoku.mvc.unit.ARP_MAC_ADDRESS_SIZE
import kittoku.mvc.unit.DataUnit
import java.nio.ByteBuffer
import kotlin.math.max


internal class DhcpMessage : DataUnit {
    internal var opcode: Byte = 0
    internal var transactionId = 0

    internal val clientIpAddress = ByteArray(4)
    internal val yourIpAddress = ByteArray(4)
    internal val serverIpAddress = ByteArray(4)

    internal val clientMacAddress = ByteArray(6)

    internal var options = OptionPack()

    private val lengthBeforePadded: Int
        get() = 240 + options.length

    private val minimumLength = 300

    override val length: Int
        get() = max(lengthBeforePadded, minimumLength)

    override fun write(buffer: ByteBuffer) {
        buffer.put(opcode)
        buffer.put(DHCP_HARDWARE_TYPE_ETHERNET)
        buffer.put(ARP_MAC_ADDRESS_SIZE)
        buffer.put(0) // hops

        buffer.putInt(transactionId)
        buffer.putShort(0) // secs
        buffer.putShort(0) // flags

        buffer.put(clientIpAddress)
        buffer.put(yourIpAddress)
        buffer.put(serverIpAddress)
        buffer.putInt(0) // relay IP Address

        buffer.put(clientMacAddress)
        buffer.padZeroByte(10)

        buffer.padZeroByte(64) // pad for server host name
        buffer.padZeroByte(128) // pad for boot file name

        buffer.putInt(DHCP_MAGIC_COOKIE)

        options.write(buffer)

        if (minimumLength > lengthBeforePadded) {
            buffer.padZeroByte(minimumLength - lengthBeforePadded)
        }
    }

    override fun read(buffer: ByteBuffer) {
        val startDhcp = buffer.position()

        opcode = buffer.get()
        assertAlways(buffer.get() == DHCP_HARDWARE_TYPE_ETHERNET)
        assertAlways(buffer.get() == ARP_MAC_ADDRESS_SIZE)
        buffer.move(Byte.SIZE_BYTES) // hops

        transactionId = buffer.int
        buffer.move(Short.SIZE_BYTES) // secs
        buffer.move(Short.SIZE_BYTES) // flags

        buffer.get(clientIpAddress)
        buffer.get(yourIpAddress)
        buffer.get(serverIpAddress)
        buffer.move(Int.SIZE_BYTES) // relay IP Address

        buffer.get(clientMacAddress)
        buffer.move(10)

        buffer.move(64) // ignore server host name
        buffer.move(128) // ignore boot file name

        assertAlways(buffer.int == DHCP_MAGIC_COOKIE)

        options.read(buffer)

        val readLength = buffer.position() - startDhcp
        if (readLength < minimumLength) {
            // MLMVPN: skip only padding that is actually there.
            //
            // The original moved straight to `minimumLength` (the BOOTP minimum), assuming
            // every reply is zero-padded to it. VPN Gate's SecureNAT DHCP server answers with
            // a compact message instead, so the move ran past the end of the received data —
            // "IllegalArgumentException: Bad position 4340/4330", ten bytes over, every time.
            //
            // UDPDatagram.read realigns to the datagram's declared end afterwards, so a short
            // skip here cannot leave the stream out of step.
            buffer.move(minOf(minimumLength - readLength, buffer.remaining()))
        }
    }
}
