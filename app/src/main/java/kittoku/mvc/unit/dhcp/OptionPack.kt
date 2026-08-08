package kittoku.mvc.unit.dhcp

import kittoku.mvc.debug.assertAlways
import kittoku.mvc.extension.move
import kittoku.mvc.extension.toIntAsUByte
import kittoku.mvc.unit.DataUnit
import kittoku.mvc.unit.IPv4_ADDRESS_SIZE
import java.nio.ByteBuffer


private const val HEADER_SIZE = 2

internal class OptionPack : DataUnit {
    internal val byteOptions = mutableMapOf<Byte, Byte>()
    internal val intOptions = mutableMapOf<Byte, Int>()
    internal val addressOptions = mutableMapOf<Byte, ByteArray>()
    internal val variableOptions = mutableMapOf<Byte, ByteArray>()
    internal val unknownOptionTags = mutableListOf<Byte>()

    override val length: Int
        get() {
            var sum = 1 // 1 == END

            byteOptions.values.forEach { _ -> sum += HEADER_SIZE + Byte.SIZE_BYTES }
            intOptions.values.forEach { _ -> sum += HEADER_SIZE + Int.SIZE_BYTES }
            addressOptions.values.forEach { _ -> sum += HEADER_SIZE + IPv4_ADDRESS_SIZE }
            variableOptions.values.forEach { sum += HEADER_SIZE + it.size }

            return sum
        }

    override fun write(buffer: ByteBuffer) {
        byteOptions.forEach { (key, value) ->
            assertAlways(key in DHCP_BYTE_OPTIONS)
            buffer.put(key)
            buffer.put(Byte.SIZE_BYTES.toByte())
            buffer.put(value)
        }

        intOptions.forEach { (key, value) ->
            assertAlways(key in DHCP_INT_OPTIONS)
            buffer.put(key)
            buffer.put(Int.SIZE_BYTES.toByte())
            buffer.putInt(value)
        }

        addressOptions.forEach { (key, value) ->
            assertAlways(value.size == IPv4_ADDRESS_SIZE)
            buffer.put(key)
            buffer.put(IPv4_ADDRESS_SIZE.toByte())
            buffer.put(value)
        }

        variableOptions.forEach { (key, value) ->
            buffer.put(key)
            buffer.put(value.size.toByte())
            buffer.put(value)
        }

        buffer.put(DHCP_OPTION_END)
    }

    override fun read(buffer: ByteBuffer) {
        unknownOptionTags.clear()

        while (true) {
            when (val tag = buffer.get()) {
                in DHCP_BYTE_OPTIONS -> {
                    assertAlways(buffer.get().toIntAsUByte() == Byte.SIZE_BYTES)
                    byteOptions[tag] = buffer.get()
                }

                in DHCP_BYTE_OPTIONS -> {
                    assertAlways(buffer.get().toIntAsUByte() == Int.SIZE_BYTES)
                    intOptions[tag] = buffer.int
                }

                in DHCP_ADDRESS_OPTIONS -> {
                    assertAlways(buffer.get().toIntAsUByte() == IPv4_ADDRESS_SIZE)
                    val address = ByteArray(IPv4_ADDRESS_SIZE)
                    buffer.get(address)
                    addressOptions[tag] = address
                }

                in DHCP_VARIABLE_OPTIONS -> {
                    val valueLength = buffer.get().toIntAsUByte()
                    val value = ByteArray(valueLength)
                    buffer.get(value)
                    variableOptions[tag] = value
                }

                DHCP_OPTION_END -> break

                else -> {
                    unknownOptionTags.add(tag)
                    discardOption(buffer)
                }
            }
        }
    }

    private fun discardOption(buffer: ByteBuffer) {
        val size = buffer.get().toIntAsUByte()
        buffer.move(size)
    }
}
