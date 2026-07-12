package com.mlmvpn.scanner.engines.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.PortUnreachableException
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
            var socket: Socket? = null
            try {
                // Force IPv4 resolution to avoid IPv6 routing issues on some ISPs
                val inetAddresses = java.net.InetAddress.getAllByName(host)
                val ipv4Address = inetAddresses.firstOrNull { it is java.net.Inet4Address } ?: inetAddresses.first()

                socket = Socket()
                val startTime = System.currentTimeMillis()
                socket.connect(InetSocketAddress(ipv4Address, port), timeoutMs)
                System.currentTimeMillis() - startTime
            } catch (e: Exception) {
                // SECURITY: never log the host/IP or the exception message (it embeds the address).
                // A failed ping to one of our own servers would otherwise leak its IP to logcat,
                // where it can be scraped and filtered. Log only the port, at debug level.
                android.util.Log.d("GamePingTester", "directPing failed on port $port (unreachable/timeout)")
                -1L
            } finally {
                try { socket?.close() } catch (_: Exception) {}
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
        var socket: Socket? = null
        try {
            val proxy = Proxy(
                Proxy.Type.SOCKS,
                InetSocketAddress(proxyHost, proxyPort)
            )
            socket = Socket(proxy)
            socket.soTimeout = timeoutMs
            val dest = InetSocketAddress.createUnresolved(host, port)
            val startTime = System.currentTimeMillis()
            socket.connect(dest, timeoutMs)
            System.currentTimeMillis() - startTime
        } catch (e: Exception) {
            -1L
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
    
    /**
     * تست پینگ واقعی UDP به سرور بازی. بیشتر پروتکل‌های اختصاصی بازی به یک پکت ساده جواب
     * نمی‌دهند، اما زمان لازم برای رفتن پکت و برگشتِ خطای ICMP Port Unreachable (وقتی پورت
     * بسته باشد) هم از همان مسیر شبکه واقعی عبور می‌کند -- پس همچنان یک تخمین RTT واقعی‌تر از
     * TCP connect است، چون پروتکل واقعی بازی (UDP) را امتحان می‌کند نه TCP.
     * @return پینگ به ms یا -1 در صورت عدم پاسخ (نه پاسخ مستقیم و نه ICMP)
     */
    suspend fun udpPing(host: String, port: Int, timeoutMs: Int = 2000): Long =
        withContext(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            val startTime = System.currentTimeMillis()
            try {
                val inetAddresses = java.net.InetAddress.getAllByName(host)
                val ipv4Address = inetAddresses.firstOrNull { it is java.net.Inet4Address } ?: inetAddresses.first()

                socket = DatagramSocket()
                socket.soTimeout = timeoutMs
                socket.connect(ipv4Address, port)

                val payload = ByteArray(32)
                val sendPacket = DatagramPacket(payload, payload.size, ipv4Address, port)
                socket.send(sendPacket)

                val receiveBuffer = ByteArray(512)
                val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                socket.receive(receivePacket)
                System.currentTimeMillis() - startTime
            } catch (e: PortUnreachableException) {
                // ICMP Port Unreachable از همون مسیر شبکه برگشته -- همچنان RTT واقعی به این IP است
                System.currentTimeMillis() - startTime
            } catch (e: Exception) {
                -1L
            } finally {
                try { socket?.close() } catch (_: Exception) {}
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
