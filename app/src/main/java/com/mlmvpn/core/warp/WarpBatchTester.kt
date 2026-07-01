package com.mlmvpn.core.warp

import android.content.Context
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.random.Random

object WarpBatchTester {

    // پکت 148 بایتی Initiation استاندارد وایرگارد
    private const val WARP_INIT_HEX = "01000000" +
            "08000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "00000000000000000000000000000000000000000000000000000000" +
            "1694db091c6e1b7f8db89808a38b1bc7" +
            "00000000000000000000000000000000"

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4)
                    + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    data class BatchEndpoint(
        val ip: String,
        val port: Int,
        val localSocksPort: Int = 0
    )

    data class BatchResult(
        val endpointIp: String,
        val endpointPort: Int,
        val pingMs: Int?
    )

    /**
     * Phase 1: Fast Ping.
     * Tries to establish a TCP connection on port 80 to quickly check if the IP is reachable.
     */
    suspend fun stage1FastPing(ip: String, timeoutMs: Int = 1000): Boolean = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, 80), timeoutMs)
            return@withContext true
        } catch (e: Exception) {
            return@withContext false
        } finally {
            try { socket?.close() } catch (e: Exception) {}
        }
    }

    /**
     * Phase 2: Wireguard Endpoint Ping.
     * Sends a WG Initiation packet to measure true UDP connection ping.
     */
    fun testWarpEndpoint(ip: String, port: Int, reservedBytes: List<Int>, timeoutMs: Int = 3000): Int? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = timeoutMs

            val payload = hexStringToByteArray(WARP_INIT_HEX)
            val address = InetSocketAddress(ip, port)
            val sendPacket = DatagramPacket(payload, payload.size, address)
            
            val startTime = System.currentTimeMillis()
            socket.send(sendPacket)

            val receiveBuffer = ByteArray(64)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            
            socket.receive(receivePacket)
            val delay = (System.currentTimeMillis() - startTime).toInt()

            val type = receiveBuffer[0].toInt()
            if (type == 2 || type == 3) {
                return delay
            }
        } catch (e: Exception) {
            return null
        } finally {
            socket?.close()
        }
        return null
    }

    /**
     * Legacy TestBatch method (Still used by GameBoosterManager)
     */
    suspend fun testBatch(
        context: Context,
        endpoints: List<BatchEndpoint>,
        account: WarpAccountManager.WarpAccountData,
        reservedBytes: List<Int>
    ): List<BatchResult> = withContext(Dispatchers.IO) {
        if (endpoints.isEmpty()) return@withContext emptyList()
        val deferreds = endpoints.map { endpoint ->
            async {
                val ping = testWarpEndpoint(endpoint.ip, endpoint.port, reservedBytes)
                BatchResult(endpoint.ip, endpoint.port, ping)
            }
        }
        return@withContext deferreds.awaitAll()
    }
}
