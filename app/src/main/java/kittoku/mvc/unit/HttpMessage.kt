package kittoku.mvc.unit

import java.nio.ByteBuffer

internal class HttpMessage : DataUnit {
    var startLine = ""
    var fields = hashMapOf<String, String>()
    var body: ByteArray? = null

    override val length: Int
        get() {
            var sum = 2 // last CRLF

            sum += startLine.length + 2 // CRLF

            fields.forEach { (key, value) ->
                sum += key.length + value.length + 4 // colon, space, CRLF
            }

            sum += body?.size ?: 0

            return sum
        }

    override fun write(buffer: ByteBuffer) {
        var sum = startLine + "\r\n"

        fields.forEach { (key, value) ->
            sum += "$key: $value\r\n"
        }

        sum += "\r\n"

        buffer.put(sum.toByteArray(Charsets.UTF_8))
        body?.also { buffer.put(it) }
    }

    override fun read(buffer: ByteBuffer) {
        throw NotImplementedError() // delegated to IncomingManager
    }
}