package kittoku.mvc.extension

import java.nio.ByteBuffer


internal fun Int.addOnesComplement(addend: Int): Int {
    var sum = this + addend

    if (sum and 0x00010000 != 0) {
        sum += 1
    }

    return sum and 0x0000FFFF
}

internal fun Int.reversed(): Int {
    val bytes = ByteBuffer.allocate(4)
    bytes.putInt(this)
    bytes.array().reverse()
    return bytes.getInt(0)
}
