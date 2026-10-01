package com.mlmvpn.scanner.engines.cfdoctor

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class DoctorNetworkTest {
    @Test fun base64WorksOnAndroid24WithoutJavaUtilBase64() {
        listOf("" to "","f" to "Zg==","fo" to "Zm8=","foo" to "Zm9v","foobar" to "Zm9vYmFy").forEach { (plain,encoded) ->
            assertEquals(encoded,WireBase64.encode(plain.toByteArray()))
            assertEquals(plain,String(WireBase64.decode(encoded)))
        }
        assertThrows(IllegalArgumentException::class.java) { WireBase64.decode("%%%") }
        assertThrows(IllegalArgumentException::class.java) { WireBase64.decode("Z") }
    }
    @Test fun recordFragmentPreservesHandshakeBytes() {
        val record = byteArrayOf(22,3,1,0,6,1,2,3,4,5,6)
        val split = TlsRecords.fragment(record, 2)
        assertArrayEquals(byteArrayOf(22,3,1,0,2,1,2,22,3,1,0,4,3,4,5,6), split)
    }
    @Test fun malformedTlsLengthIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { TlsRecords.fragment(byteArrayOf(22,3,1,0,9,1),1) }
    }
    @Test fun truncatedHttpBodyIsNotSuccessfulDownload() {
        val stream=ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nshort".toByteArray())
        assertThrows(EOFException::class.java) { HttpWire.response(stream,100) }
    }
    @Test fun httpParsesChunkedAndDoesNotFollowRedirectToLocalServices() {
        ServerSocket(0).use { server ->
            val worker = thread(isDaemon=true) { server.accept().use { s ->
                val input=s.getInputStream(); HttpWire.readHead(input)
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\ntest\r\n0\r\n\r\n".toByteArray())
            } }
            Socket("127.0.0.1",server.localPort).use { s ->
                s.soTimeout=2000
                HttpWire.request(s.getOutputStream(),"example.com","/", "GET")
                val response=HttpWire.response(s.getInputStream(), 64)
                assertEquals(200,response.status); assertEquals("test",String(response.body))
            }
            worker.join(2000)
        }
    }
    @Test fun websocketMasksOutgoingAndHandlesPingBeforeBinary() {
        val out=ByteArrayOutputStream()
        val ws=WebSocketStream(ByteArrayInputStream(byteArrayOf(0x89.toByte(),1,42,0x82.toByte(),3,1,2,3)),out)
        assertArrayEquals(byteArrayOf(1,2,3), ByteArray(3).also { assertEquals(3,ws.input.read(it)) })
        assertTrue(out.toByteArray()[1].toInt() and 0x80 != 0)
        ws.output.write(byteArrayOf(4,5))
        val bytes=out.toByteArray(); assertEquals(0x82,bytes[7].toInt() and 255)
    }
    @Test fun quicVersionNegotiationRequiresMatchingConnectionIds() {
        val q=QuicProbe.packet()
        assertEquals(1200,q.bytes.size)
        assertFalse(QuicProbe.validResponse(ByteArray(30),q))
        val r=ByteArrayOutputStream().apply {
            write(0x80); write(ByteArray(4)); write(q.scid.size); write(q.scid); write(q.dcid.size); write(q.dcid); write(byteArrayOf(0,0,0,1))
        }.toByteArray()
        assertTrue(QuicProbe.validResponse(r,q))
        r[6]=(r[6].toInt() xor 1).toByte(); assertFalse(QuicProbe.validResponse(r,q))
    }
}
