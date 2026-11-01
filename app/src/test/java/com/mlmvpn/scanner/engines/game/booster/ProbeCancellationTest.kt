package com.mlmvpn.scanner.engines.game.booster

import com.mlmvpn.scanner.engines.game.booster.probe.tlsTrain
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProbeCancellationTest {
    @Test fun `cancelling TLS closes a socket whose peer never completes handshake`() = runBlocking {
        javax.net.ssl.SSLSocketFactory.getDefault() // Exclude first-use trust-store initialization.
        val accepted = CountDownLatch(1)
        val release = CountDownLatch(1)
        ServerSocket(0).use { server ->
            val peer = Thread {
                server.accept().use {
                    accepted.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }.apply { isDaemon = true; start() }
            try {
                val probe = async(Dispatchers.Default) {
                    tlsTrain(InetSocketAddress("127.0.0.1", server.localPort), count = 1, timeoutMs = 10_000)
                }
                assertTrue(accepted.await(2, TimeUnit.SECONDS))
                val started = System.nanoTime()
                probe.cancelAndJoin()
                assertTrue("Cancellation must not wait for the 10 s socket timeout",
                    (System.nanoTime() - started) / 1_000_000 < 1500)
                assertTrue(probe.isCancelled)
            } finally {
                release.countDown()
                peer.join(2000)
            }
        }
    }
}
