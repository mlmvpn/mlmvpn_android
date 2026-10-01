package com.mlmvpn.scanner.engines.cfdoctor

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.*
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.*
import kotlin.concurrent.thread

class DoctorTlsTest {
    private fun context(): SSLContext {
        val ks=KeyStore.getInstance("PKCS12")
        val file=sequenceOf(File("app/src/test/resources/cfdoctor/test-server.p12"),File("src/test/resources/cfdoctor/test-server.p12")).first { it.isFile }
        file.inputStream().use { ks.load(it,"doctor-test-only".toCharArray()) }
        val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks,"doctor-test-only".toCharArray()) }
        val tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        return SSLContext.getInstance("TLS").apply { init(km.keyManagers,tm.trustManagers,null) }
    }
    private fun env(port: Int,ctx: SSLContext)=object: NetEnv {
        override fun resolve(host: String)=listOf(InetAddress.getByName("104.16.0.1"))
        override fun socket()=object: Socket() { override fun connect(endpoint: SocketAddress,timeout: Int)=super.connect(InetSocketAddress("127.0.0.1",port),timeout) }
        override fun tlsContext()=ctx
    }
    @Test fun realTlsAndFragmentedHelloCompleteHttpAndKeepEchoedSecretsOutOfReport() = runBlocking {
        val ctx=context(); val secret="user-secret-worker.example/private-sub-path"
        val server=ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        server.use {
            val worker=thread(isDaemon=true) {
                server.accept().use { s ->
                    s.soTimeout=4000; HttpWire.readHead(s.getInputStream())
                    val body="{\"errors\":[{\"code\":9109,\"message\":\"$secret\"}]}"
                    s.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nServer: $secret\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
                }
            }
            val net=NetProbes(env(server.localPort,ctx),DataBudget(100000))
            val row=net.run(ProbeSpec("ST1",privateEndpoint=true,recipe=SplitRecipe(at=5,chunk=1)),5000)
            worker.join(5000)
            assertEquals(403,row.http); assertEquals(1L,row.numbers["tls"])
            val report=CfDoctorReport.render("1",listOf(row),emptySet(),emptyList(),true)
            assertFalse(report.contains(secret)); assertTrue(report.contains("9109"))
        }
    }
    @Test fun silentAfterTlsHasHttpFailureAndTlsEvidence() = runBlocking {
        val ctx=context(); val server=ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        server.use {
            // The server completes TLS, reads the request, then says nothing for longer than the
            // probe will wait. 4 s for the whole probe: a TLS handshake on a loaded build machine
            // can take over a second, and 800 ms made this fail in TLS instead of in the silence.
            thread(isDaemon=true) { runCatching { server.accept().use { s -> s.soTimeout=10000; HttpWire.readHead(s.getInputStream()); Thread.sleep(8000) } } }
            val row=NetProbes(env(server.localPort,ctx),DataBudget(100000)).run(ProbeSpec("X1"),4000)
            assertFalse(row.ok); assertEquals(1L,row.numbers["tls"]); assertTrue(row.code.startsWith("http_"))
        }
    }
    @Test fun poisonedDnsDoesNotOpenAnySocket() = runBlocking {
        var opened=false
        val env=object: NetEnv {
            override fun resolve(host: String)=listOf(InetAddress.getByName("10.10.34.1"))
            override fun socket(): Socket { opened=true; throw AssertionError() }
        }
        val row=NetProbes(env,DataBudget(10000)).run(ProbeSpec("D1",kind="dns"),1000)
        assertEquals("dns_bogon",row.code); assertFalse(opened)
    }
    @Test fun websocketProxyFallsBackToSecondEgressAndLabelsInnerTlsFailure() = runBlocking {
        val ctx=context(); val server=ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        val count=java.util.concurrent.atomic.AtomicInteger()
        fun frame(input: java.io.InputStream): ByteArray {
            assertTrue(input.read()>=0); val second=input.read(); assertTrue(second and 128!=0)
            var n=second and 127
            if(n==126) { val b=HttpWire.readExact(input,2); n=(b[0].toInt() and 255)*256+(b[1].toInt() and 255) }
            val mask=HttpWire.readExact(input,4); val bytes=HttpWire.readExact(input,n)
            return ByteArray(n) { (bytes[it].toInt() xor mask[it%4].toInt()).toByte() }
        }
        fun send(out: java.io.OutputStream,bytes: ByteArray) {
            out.write(0x82); if(bytes.size<126) out.write(bytes.size) else { out.write(126); out.write(bytes.size ushr 8); out.write(bytes.size and 255) }; out.write(bytes); out.flush()
        }
        server.use {
            val worker=thread(isDaemon=true) { repeat(3) { leg -> server.accept().use { s ->
                count.incrementAndGet(); s.soTimeout=3000
                val input=s.getInputStream(); val output=s.getOutputStream(); val head=HttpWire.readHead(input)
                val key=head.lineSequence().first { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
                val accept=WireBase64.encode(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                frame(input); frame(input)
                when(leg) {
                    0 -> send(output,byteArrayOf(0,0)) // Proxy acknowledgement, then dead egress.
                    1 -> send(output,byteArrayOf(0,0)+"HTTP/1.1 200 OK\r\nContent-Length: 22\r\n\r\nMicrosoft Connect Test".toByteArray())
                    else -> send(output,byteArrayOf(0,0,21,3,3,0,2,2,40)) // TLS fatal handshake_failure.
                }
            } } }
            val row=TunnelStageClient(env(server.localPort,ctx),DataBudget(100000)).test(TunnelConfig("vless","worker.example",443,"00000000-0000-0000-0000-000000000001","ws","worker.example","/private"),8000)
            worker.join(2000)
            assertEquals(3,count.get()); assertEquals("app.http",row.code); assertEquals(1L,row.numbers["egress"])
        }
    }
}
