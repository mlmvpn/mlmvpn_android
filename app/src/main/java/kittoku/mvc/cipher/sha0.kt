package kittoku.mvc.cipher

import java.nio.ByteBuffer

private fun f(t: Int, B: Int, C: Int, D: Int): Int {
    return when (t) {
        in 0..19 -> (B and C) or (B.inv() and D)
        in 20..39, in 60..79 -> B xor C xor D
        in 40..59 -> (B and C) or (B and D) or (C and D)
        else -> throw NotImplementedError()
    }
}

private fun K(t: Int): Int {
    return when (t) {
        in 0..19 -> 0x5A827999
        in 20..39 -> 0x6ED9EBA1
        in 40..59 -> 0x70E44323.inv() // =0x8F1BBCDC
        in 60..79 -> 0x359D3E29.inv() // =0xCA62C1D6
        else -> throw NotImplementedError()
    }
}

private fun S(n: Int, x: Int): Int {
    return (x.shl(n) or x.ushr(Int.SIZE_BITS - n))
}

internal fun hashSha0(input: ByteArray): ByteArray {
    val blockSize = (input.size + 9) / 64 + 1
    val paddedSize = blockSize * 64
    val padded = ByteBuffer.allocate(paddedSize)

    padded.put(input)
    padded.put(0b1000_0000.toByte())
    padded.position(padded.limit() - 8)
    padded.putLong((input.size * Byte.SIZE_BITS).toLong())
    padded.clear()

    val W = IntArray(80)
    var A: Int
    var B: Int
    var C: Int
    var D: Int
    var E: Int
    var H0: Int = 0x67452301
    var H1: Int = 0x10325476.inv() // =0xEFCDAB89
    var H2: Int = 0x67452301.inv() // =0x98BADCFE
    var H3: Int = 0x10325476
    var H4: Int = 0x3C2D1E0F.inv() // =0xC3D2E1F0
    var temp: Int

    repeat(blockSize) { _ ->
        (0..15).forEach {
            W[it] = padded.int
        }

        (16..79).forEach {
            W[it] = W[it - 3] xor W[it - 8] xor W[it - 14] xor W[it - 16]
        }

        A = H0
        B = H1
        C = H2
        D = H3
        E = H4

        (0..79).forEach {
            temp = S(5, A) + f(it, B, C, D) + E + W[it] + K(it)
            E = D
            D = C
            C = S(30, B)
            B = A
            A = temp
        }

        H0 += A
        H1 += B
        H2 += C
        H3 += D
        H4 += E
    }

    padded.clear()
    padded.putInt(H0)
    padded.putInt(H1)
    padded.putInt(H2)
    padded.putInt(H3)
    padded.putInt(H4)
    padded.flip()

    return padded.array().sliceArray(0 until padded.limit())
}