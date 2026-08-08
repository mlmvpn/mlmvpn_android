package kittoku.mvc.extension

import java.nio.ByteBuffer


internal fun ByteBuffer.move(diff: Int) {
    position(position() + diff)
}

internal fun ByteBuffer.padZeroByte(size: Int) {
    repeat(size) { put(0) }
}

internal fun ByteBuffer.probeByte(diff: Int): Byte {
    return this.get(this.position() + diff)
}

internal fun ByteBuffer.probeShort(diff: Int): Short {
    return this.getShort(this.position() + diff)
}

internal fun ByteBuffer.probeInt(diff: Int): Int {
    return this.getInt(this.position() + diff)
}

internal fun ByteBuffer.indexOf(target: ByteArray): Int? {
    if (target.size > this.remaining()) {
        return null
    }

    (this.position() until (this.limit() - target.size)).forEach { start ->
        target.forEachIndexed { i, b ->
            if (this.get(start + i) != b) {
                return@forEach
            }
        }

        return start
    }

    return null
}

internal fun ByteBuffer.capacityAfterPayload(): Int {
    return capacity() - limit()
}
