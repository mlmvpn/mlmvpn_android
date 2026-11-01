package com.mlmvpn.scanner.engines.proton

import com.mlmvpn.scanner.engines.cfdoctor.TlsChannel
import java.io.DataInputStream
import java.io.File
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import org.junit.Assert.*
import org.junit.Test

class ProtonTlsTest {
    private fun context(): SSLContext {
        val ks = KeyStore.getInstance("PKCS12")
        val file = sequenceOf(File("app/src/test/resources/cfdoctor/test-server.p12"), File("src/test/resources/cfdoctor/test-server.p12")).first { it.isFile }
        file.inputStream().use { ks.load(it, "doctor-test-only".toCharArray()) }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "doctor-test-only".toCharArray()) }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        return SSLContext.getInstance("TLS").apply { init(km.keyManagers, tm.trustManagers, null) }
    }

    @Test fun `nonempty shaped ClientHellos complete TLS and carry repeated bidirectional data`() {
        val context = context()
        val pool = Executors.newSingleThreadExecutor()
        try {
            // SunJSSE deliberately rejects zero-length handshake records; that PattNG preset is
            // byte-tested in ProtonWireTest and tested against Proton on-device instead.
            for (method in listOf(ProtonMethod.PATTNG_SIX, ProtonMethod.PATTN, ProtonMethod.PATTNG_TLS12)) {
                (context.serverSocketFactory.createServerSocket(0) as SSLServerSocket).use { server ->
                    server.soTimeout = 10_000
                    val echo = pool.submit {
                        server.accept().use { peer ->
                            peer.soTimeout = 10_000
                            repeat(3) {
                                val bytes = ByteArray(4096)
                                DataInputStream(peer.getInputStream()).readFully(bytes)
                                peer.getOutputStream().write(bytes); peer.getOutputStream().flush()
                            }
                        }
                    }
                    try { Socket("127.0.0.1", server.localPort).use { socket ->
                        socket.tcpNoDelay = true; socket.soTimeout = 10_000
                        val tls = TlsChannel(socket.getInputStream(), ProtonHello.output(socket.getOutputStream(), method), "worker.example", server.localPort,
                            context = context, protocol = if (method == ProtonMethod.PATTNG_TLS12) "TLSv1.2" else null,
                            cipherSuites = listOf("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"))
                        if (method == ProtonMethod.PATTNG_TLS12) assertEquals("TLSv1.2", tls.version)
                        repeat(3) { round ->
                            val sent = ByteArray(4096) { (it + round).toByte() }
                            tls.output.write(sent)
                            val got = ByteArray(sent.size)
                            DataInputStream(tls.input).readFully(got)
                            assertArrayEquals(method.name, sent, got)
                        }
                    } } catch (e: Exception) {
                        try { echo.get(1, TimeUnit.SECONDS) } catch (serverError: Exception) { e.addSuppressed(serverError) }
                        throw AssertionError("TLS method $method", e)
                    }
                    echo.get(10, TimeUnit.SECONDS)
                }
            }
        } finally { pool.shutdownNow() }
    }
}
