package kittoku.mvc.extension

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.Charset


internal fun ByteArray.isSame(other: ByteArray): Boolean {
    if (this.size != other.size) return false

    this.zip(other).forEach {
        if (it.first != it.second) return false
    }

    return true
}

internal fun ByteArray.match(pattern: ByteArray, offset: Int=0): Boolean {
    if (pattern.size > this.size - offset) return false

    for (i in pattern.indices) {
        if (this[i + offset] != pattern[i]) return false
    }

    return true
}

internal fun ByteArray.search(pattern: ByteArray): Int {
    val diff = this.size - pattern.size

    if (diff < 0) return -1

    outer@ for (i in 0..diff) {
        for (j in pattern.indices) {
            if (this[i + j] != pattern[j]) continue@outer
        }

        return i
    }

    return -1
}

internal fun ByteArray.read(other: ByteArray) {
    val wrapped = ByteBuffer.wrap(other)
    wrapped.get(this)
}

internal fun ByteArray.toInetAddress(): InetAddress {
    return InetAddress.getByAddress(this)
}

internal fun ByteArray.toStringOrNull(charset: Charset): String? {
    try {
        return toString(charset)
    } catch (_: Exception) {}

    return null
}

internal fun ByteArray.toHexString(parse: Boolean = false): String {
    var output = ""

    forEachIndexed { index, byte ->
        output += String.format("%02X", byte.toInt() and 0xFF)

        if (parse) output += if (index % 16 == 15) "\n" else " "
    }

    return output
}

internal fun String.toHexByteArray(): ByteArray {
    if (length % 2 != 0) throw Exception("Fragmented Byte")

    val arrayLength = length / 2
    val output = ByteArray(arrayLength)

    repeat(arrayLength) {
        val start = it * 2
        output[it] = this.slice(start..start + 1).toInt(16).toByte()
    }

    return output
}
