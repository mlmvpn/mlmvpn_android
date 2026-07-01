package com.mlmvpn.scanner.engines.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import kotlin.math.abs

object GamePingTester {
    
    /**
     * تست مستقیم پینگ TCP به سرور بازی (بدون VPN)
     * @return پینگ به ms یا -1 در صورت خطا
     */
    suspend fun directPing(host: String, port: Int, timeoutMs: Int = 3000): Long = 
        withContext(Dispatchers.IO) {
            try {
                // Force IPv4 resolution to avoid IPv6 routing issues on some ISPs
                val inetAddresses = java.net.InetAddress.getAllByName(host)
                val ipv4Address = inetAddresses.firstOrNull { it is java.net.Inet4Address } ?: inetAddresses.first()
                
                val socket = Socket()
                val startTime = System.currentTimeMillis()
                socket.connect(InetSocketAddress(ipv4Address, port), timeoutMs)
                val elapsed = System.currentTimeMillis() - startTime
                socket.close()
                elapsed
            } catch (e: Exception) {
                android.util.Log.e("GamePingTester", "directPing failed for $host:$port - ${e.message}", e)
                -1L
            }
        }
    
    /**
     * تست پینگ از طریق SOCKS proxy (نود VLESS/Trojan)
     * @return پینگ به ms یا -1 در صورت خطا
     */
    suspend fun proxyPing(
        host: String, 
        port: Int, 
        proxyHost: String = "127.0.0.1", 
        proxyPort: Int, 
        timeoutMs: Int = 5000
    ): Long = withContext(Dispatchers.IO) {
        try {
            val proxy = Proxy(
                Proxy.Type.SOCKS, 
                InetSocketAddress(proxyHost, proxyPort)
            )
            val socket = Socket(proxy)
            socket.soTimeout = timeoutMs
            val dest = InetSocketAddress.createUnresolved(host, port)
            val startTime = System.currentTimeMillis()
            socket.connect(dest, timeoutMs)
            val elapsed = System.currentTimeMillis() - startTime
            socket.close()
            elapsed
        } catch (e: Exception) {
            -1L
        }
    }
    
    /**
     * تست چند endpoint و برگرداندن میانگین و Jitter
     * @return Pair(avgPing, jitter)
     */
    suspend fun averagePing(
        endpoints: List<String>, 
        port: Int, 
        samples: Int = 3,
        proxyPort: Int? = null  // null = direct, otherwise via proxy
    ): Pair<Long, Long> {
        val pings = mutableListOf<Long>()
        
        for (i in 0 until samples) {
            for (endpoint in endpoints) {
                val ping = if (proxyPort != null) {
                    proxyPing(host = endpoint, port = port, proxyPort = proxyPort)
                } else {
                    directPing(host = endpoint, port = port)
                }
                
                if (ping > 0) {
                    pings.add(ping)
                }
            }
        }
        
        if (pings.isEmpty()) return Pair(-1L, 0L)
        
        // Remove outliers if we have enough samples
        val validPings = if (pings.size > 2) {
            val sorted = pings.sorted()
            sorted.drop(1).dropLast(1) // remove min and max
        } else {
            pings
        }
        
        val avgPing = validPings.average().toLong()
        
        // Calculate jitter (average deviation from mean)
        val jitter = if (validPings.size > 1) {
            validPings.map { abs(it - avgPing) }.average().toLong()
        } else {
            0L
        }
        
        return Pair(avgPing, jitter)
    }
}
