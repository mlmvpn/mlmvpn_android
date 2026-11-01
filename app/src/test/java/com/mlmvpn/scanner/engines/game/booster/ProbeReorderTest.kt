package com.mlmvpn.scanner.engines.game.booster

import com.mlmvpn.scanner.engines.game.booster.probe.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProbeReorderTest {
    @Test fun `out of order replies survive train and slice aggregation`() = runBlocking {
        EchoProtocol.newToken()
        val server = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val echo = Thread {
            try {
                val first = DatagramPacket(ByteArray(2048), 2048)
                val second = DatagramPacket(ByteArray(2048), 2048)
                server.receive(first)
                server.receive(second)
                server.send(DatagramPacket(second.data, second.length, second.socketAddress))
                Thread.sleep(50)
                server.send(DatagramPacket(first.data, first.length, first.socketAddress))
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        try {
            val result = udpTrainFlows(
                InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort),
                EchoProtocol, listOf(UdpVia.Direct()), ppsPerFlow = 5.0,
                durationMs = 400, timeoutMs = 3000,
            )
            assertEquals(2, result.stats.n)
            assertEquals(1, result.stats.reorder)
            val window = SliceAccumulator()
            window.add(result)
            window.add(result)
            assertEquals(2, window.stats().reorder)
        } finally {
            server.close()
            echo.join(500)
        }
    }
}
