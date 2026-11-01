package com.mlmvpn.scanner.engines.scout

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class ScoutForwardRoutesTest {
    @Test fun `forwarded measurements reach only their configured local tunnel forward`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { target ->
            target.soTimeout = 3000
            val writer = thread(isDaemon = true) { target.accept().use { it.getOutputStream().write(42) } }
            val lease = ScoutForwardRoutes.register(mapOf(("example.test" to 443) to target.localPort))
            val id = lease.port
            lease.use {
                ScoutForwardRoutes.open(id, "example.test", 443, 2000)!!.use { socket -> assertEquals(42, socket.getInputStream().read()) }
                try { ScoutForwardRoutes.open(id, "other.test", 443, 2000); fail("Unknown destinations must never dial directly") }
                catch (_: java.io.IOException) { }
            }
            writer.join(3000)
            assertNull(ScoutForwardRoutes.open(id, "example.test", 443, 100))
        }
    }
}
