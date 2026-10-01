package com.mlmvpn.scanner.engines.cfdoctor

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class TunnelStageTest {
    private val config=TunnelConfig("vless","worker.example",443,"00000000-0000-0000-0000-000000000001","ws","worker.example","/private")
    @Test fun vlessWireHeaderUsesDomainAddressAndNetworkOrderPort() {
        val bytes=TunnelStageClient.proxyHeader(config,"example.com",443)
        assertEquals(0,bytes[0].toInt()); assertEquals(1,bytes[16].toInt())
        assertArrayEquals(byteArrayOf(0,1,1,0xbb.toByte(),2,11),bytes.copyOfRange(17,23))
        assertEquals("example.com",String(bytes.copyOfRange(23,bytes.size)))
    }
    @Test fun acceptedVlessWithDeadEgressIsNotAuthenticationFailure() {
        val input=ByteArrayInputStream(byteArrayOf(0,0))
        val result=TunnelStageClient.egress(input,ByteArrayOutputStream(),config,"connectivitycheck.gstatic.com","/generate_204")
        assertEquals("egress",result.code)
        assertEquals(1L,result.numbers["proxy_ack"])
    }
    @Test fun rejectedVlessResponseAndTrojanAmbiguityAreSeparate() {
        assertEquals("proxy.handshake",TunnelStageClient.egress(ByteArrayInputStream(byteArrayOf(5,0)),ByteArrayOutputStream(),config,"example.com","/").code)
        assertEquals("proxy_or_egress",TunnelStageClient.egress(ByteArrayInputStream(ByteArray(0)),ByteArrayOutputStream(),config.copy(protocol="trojan"),"example.com","/").code)
    }
    @Test fun successfulProxyRequiresExpectedEgressStatus() {
        val input=ByteArrayInputStream(byteArrayOf(0,0)+"HTTP/1.1 204 No Content\r\n\r\n".toByteArray())
        assertTrue(TunnelStageClient.egress(input,ByteArrayOutputStream(),config,"connectivitycheck.gstatic.com","/generate_204").ok)
    }
}
