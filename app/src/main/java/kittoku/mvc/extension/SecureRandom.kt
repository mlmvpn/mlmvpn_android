package kittoku.mvc.extension

import java.security.SecureRandom


internal fun SecureRandom.nextBytes(size: Int): ByteArray {
    val array = ByteArray(size)
    this.nextBytes(array)
    return array
}
