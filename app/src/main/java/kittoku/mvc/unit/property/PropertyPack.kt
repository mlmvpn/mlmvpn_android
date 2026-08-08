package kittoku.mvc.unit.property

import kittoku.mvc.debug.assertAlways
import kittoku.mvc.extension.move
import kittoku.mvc.extension.reversed
import kittoku.mvc.unit.DataUnit
import kittoku.mvc.unit.IPv4_ADDRESS_SIZE
import java.nio.ByteBuffer


private const val FIXED_HEADER_SIZE = 3 * Int.SIZE_BYTES // 12 = keySize + valueType + valueNum

internal class PropertyPack : DataUnit {
    internal val booleanProperties = mutableMapOf<String, Boolean>()
    internal val intProperties = mutableMapOf<String, Int>()
    internal val longProperties = mutableMapOf<String, Long>()
    internal val addressProperties = mutableMapOf<String, ByteArray>()
    internal val bytesProperties = mutableMapOf<String, ByteArray>()
    internal val asciiProperties = mutableMapOf<String, String>()
    internal val utf8Properties = mutableMapOf<String, String>()

    internal val unknownPropertyKeys = mutableListOf<String>()

    override val length: Int
        get() { // Int.SIZE_BYTES + validProperties.map { it.length }.sum()
            var sum = 4

            booleanProperties.forEach { (key, _) -> sum += FIXED_HEADER_SIZE + key.length + Int.SIZE_BYTES }
            intProperties.forEach { (key, _) -> sum += FIXED_HEADER_SIZE + key.length + Int.SIZE_BYTES }
            longProperties.forEach { (key, _) -> sum += FIXED_HEADER_SIZE + key.length + Long.SIZE_BYTES }
            addressProperties.forEach { (key, _) -> sum += FIXED_HEADER_SIZE + key.length + IPv4_ADDRESS_SIZE }
            bytesProperties.forEach { (key, value) -> sum += FIXED_HEADER_SIZE + key.length + Int.SIZE_BYTES + value.size }
            asciiProperties.forEach { (key, value) -> sum += FIXED_HEADER_SIZE + key.length + Int.SIZE_BYTES + value.length }
            utf8Properties.forEach { (key, value) -> sum += FIXED_HEADER_SIZE + key.length + Int.SIZE_BYTES + value.toByteArray(Charsets.UTF_8).size }

            return sum
        }

    private fun writeHeader(key: String, valueType: Int, valueNum: Int, buffer: ByteBuffer) {
        buffer.putInt(key.length + 1)
        buffer.put(key.toByteArray(Charsets.US_ASCII))
        buffer.putInt(valueType)
        buffer.putInt(valueNum)
    }

    private fun readHeader(valueType: Int, valueNum: Int, buffer: ByteBuffer) {
        assertAlways(buffer.int == valueType)
        assertAlways(buffer.int == valueNum)
    }

    override fun write(buffer: ByteBuffer) {
        var numProperties = 0

        arrayOf<MutableMap<String, *>>(
            booleanProperties, intProperties, longProperties, addressProperties, bytesProperties, asciiProperties, utf8Properties
        ).forEach {
            numProperties += it.size
        }

        buffer.putInt(numProperties)


        booleanProperties.forEach { (key, value) ->
            assertAlways(key in SOFTETHER_BOOLEAN_PROPERTIES)
            writeHeader(key, SEP_INT_TYPE, 1, buffer)
            buffer.putInt(if (value) 1 else 0)
        }

        intProperties.forEach { (key, value) ->
            if (key in SOFTETHER_BE_INT_PROPERTIES) {
                writeHeader(key, SEP_INT_TYPE, 1, buffer)
                buffer.putInt(value)
            } else {
                assertAlways(key in SOFTETHER_LE_INT_PROPERTIES)
                writeHeader(key, SEP_INT_TYPE, 1, buffer)
                buffer.putInt(value.reversed())
            }
        }

        longProperties.forEach { (key, value) ->
            assertAlways(key in SOFTETHER_LONG_PROPERTIES)
            writeHeader(key, SEP_LONG_TYPE, 1, buffer)
            buffer.putLong(value)
        }

        addressProperties.forEach { (key, value) ->
            assertAlways(key in SOFTETHER_ADDRESS_PROPERTIES)
            assertAlways(value.size == IPv4_ADDRESS_SIZE)
            writeHeader(key, SEP_INT_TYPE, 1, buffer)
            buffer.put(value.reversedArray())
        }

        bytesProperties.forEach { (key, value) ->
            if (key in SOFTETHER_160BITS_PROPERTIES) {
                assertAlways(value.size == 20)
            } else {
                assertAlways(key in SOFTETHER_BYTES_PROPERTIES)
            }

            writeHeader(key, SEP_BYTES_TYPE, 1, buffer)
            buffer.putInt(value.size)
            buffer.put(value)
        }

        asciiProperties.forEach { (key, value) ->
            assertAlways(key in SOFTETHER_ASCII_PROPERTIES)
            writeHeader(key, SEP_ASCII_TYPE ,1, buffer)
            value.toByteArray(Charsets.US_ASCII).also {
                buffer.putInt(it.size)
                buffer.put(it)
            }
        }

        utf8Properties.forEach { (key, value) ->
            assertAlways(key in SOFTETHER_UTF8_PROPERTIES)
            writeHeader(key, SEP_UTF8_TYPE ,1, buffer)
            value.toByteArray(Charsets.UTF_8).also {
                buffer.putInt(it.size)
                buffer.put(it)
            }
        }
    }

    override fun read(buffer: ByteBuffer) {
        val propertyNum = buffer.int
        assertAlways(propertyNum >= 0)

        unknownPropertyKeys.clear()

        repeat(propertyNum) {
            val keySize = buffer.int - 1
            assertAlways(keySize >= 0)

            val key = ByteArray(keySize).let {
                buffer.get(it)
                it.toString(Charsets.US_ASCII)
            }

            when (key) {
                in SOFTETHER_BOOLEAN_PROPERTIES -> {
                    readHeader(SEP_INT_TYPE, 1, buffer)
                    booleanProperties[key] = buffer.int != 0
                }

                in SOFTETHER_BE_INT_PROPERTIES -> {
                    readHeader(SEP_INT_TYPE, 1, buffer)
                    intProperties[key] = buffer.int
                }

                in SOFTETHER_LE_INT_PROPERTIES -> {
                    readHeader(SEP_INT_TYPE, 1, buffer)
                    intProperties[key] = buffer.int.reversed()
                }

                in SOFTETHER_LONG_PROPERTIES -> {
                    readHeader(SEP_LONG_TYPE, 1, buffer)
                    longProperties[key] = buffer.long
                }

                in SOFTETHER_ADDRESS_PROPERTIES -> {
                    readHeader(SEP_INT_TYPE, 1, buffer)
                    val reversed = ByteArray(IPv4_ADDRESS_SIZE)
                    buffer.get(reversed)
                    addressProperties[key] = reversed.reversedArray()
                }

                in SOFTETHER_BYTES_PROPERTIES -> {
                    readHeader(SEP_BYTES_TYPE, 1, buffer)
                    val size = buffer.int
                    assertAlways(size >= 0)
                    ByteArray(size).also {
                        buffer.get(it)
                        bytesProperties[key] = it
                    }
                }

                in SOFTETHER_160BITS_PROPERTIES -> {
                    readHeader(SEP_BYTES_TYPE, 1, buffer)
                    val size = buffer.int
                    assertAlways(size == 20)
                    ByteArray(size).also {
                        buffer.get(it)
                        bytesProperties[key] = it
                    }
                }

                in SOFTETHER_ASCII_PROPERTIES -> {
                    readHeader(SEP_ASCII_TYPE, 1, buffer)
                    val size = buffer.int
                    assertAlways(size >= 0)
                    ByteArray(size).also {
                        buffer.get(it)
                        asciiProperties[key] = it.toString(Charsets.US_ASCII)
                    }
                }

                in SOFTETHER_UTF8_PROPERTIES -> {
                    readHeader(SEP_UTF8_TYPE, 1, buffer)
                    val size = buffer.int
                    assertAlways(size >= 0)
                    ByteArray(size).also {
                        buffer.get(it)
                        asciiProperties[key] = it.toString(Charsets.UTF_8)
                    }
                }

                else -> {
                    unknownPropertyKeys.add(key)
                    discardProperty(buffer)
                }
            }
        }
    }

    private fun discardProperty(buffer: ByteBuffer) {
        when (buffer.int) {
            SEP_INT_TYPE -> buffer.move(Int.SIZE_BYTES * 2)
            SEP_LONG_TYPE -> buffer.move(Int.SIZE_BYTES + Long.SIZE_BYTES)
            SEP_BYTES_TYPE, SEP_ASCII_TYPE, SEP_UTF8_TYPE -> {
                buffer.move(Int.SIZE_BYTES)
                val size = buffer.int
                assertAlways(size >= 0)
                buffer.move(size)
            }
            else -> throw NotImplementedError()
        }
    }
}
