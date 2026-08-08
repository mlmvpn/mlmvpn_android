package kittoku.mvc.unit

import kittoku.mvc.debug.assertAlways
import kittoku.mvc.extension.nextBytes
import kittoku.mvc.extension.padZeroByte
import kittoku.mvc.extension.search
import kittoku.mvc.extension.toIntAsUShort
import java.net.Inet4Address
import java.nio.ByteBuffer
import java.security.SecureRandom
import kotlin.math.max

internal const val KEEP_ALIVE_MAX_SIZE = 512
internal const val KEEP_ALIVE_FRAME_NUM = -1

internal val KEEP_ALIVE_NATT_PORT_TAG = "NATT_MY_PORT".toByteArray(Charsets.US_ASCII)
internal val KEEP_ALIVE_NATT_IP_TAG = "NATT_MY_IP".toByteArray(Charsets.US_ASCII)
internal val KEEP_ALIVE_NATT_INFO_SIZE = KEEP_ALIVE_NATT_PORT_TAG.size + Short.SIZE_BYTES + KEEP_ALIVE_NATT_IP_TAG.size + 16

internal class KeepAlivePacket : DataUnit {
    internal var value: ByteArray? = null

    internal var nattAddress: Inet4Address? = null
    internal var nattPort = 0

    private val valueLength: Int
        get() = value?.size ?: 0
    override val length: Int
        get() = 2 * Int.SIZE_BYTES + valueLength

    internal fun preparePacket(random: SecureRandom) {
        val minSize = nattAddress?.let {
            KEEP_ALIVE_NATT_INFO_SIZE
        } ?: 0

        val randomSize = max(random.nextInt(KEEP_ALIVE_MAX_SIZE + 1), minSize)

        value = random.nextBytes(randomSize).also { array ->
            nattAddress?.also {
                val buffer = ByteBuffer.wrap(array)
                buffer.put(KEEP_ALIVE_NATT_PORT_TAG)
                buffer.putShort(nattPort.toShort())
                buffer.put(KEEP_ALIVE_NATT_IP_TAG)
                buffer.padZeroByte(10)
                buffer.putShort(-1)
                buffer.put(it.address)
            }
        }
    }

    internal fun extractNATTInfo() {
        nattPort = 0
        nattAddress = null

        value?.also {
            val buffer = ByteBuffer.wrap(it)

            val startPortTag = it.search(KEEP_ALIVE_NATT_PORT_TAG)
            if (startPortTag >= 0) {
                nattPort = buffer.getShort(startPortTag + KEEP_ALIVE_NATT_PORT_TAG.size).toIntAsUShort()
            }

            val startIPTag = it.search(KEEP_ALIVE_NATT_IP_TAG)
            if (startIPTag >= 0) {
                val address = ByteArray(IPv4_ADDRESS_SIZE)

                val startIP = startIPTag + KEEP_ALIVE_NATT_PORT_TAG.size + 12
                buffer.position(startIP)
                buffer.get(address)
                nattAddress = Inet4Address.getByAddress(address) as Inet4Address
            }
        }
    }

    override fun read(buffer: ByteBuffer) {
        assertAlways(buffer.int == KEEP_ALIVE_FRAME_NUM)
        value = ByteArray(buffer.int).also {
            buffer.get(it)
        }
    }

    override fun write(buffer: ByteBuffer) {
        buffer.putInt(KEEP_ALIVE_FRAME_NUM)
        buffer.putInt(valueLength)
        value?.also { buffer.put(it) }
    }
}