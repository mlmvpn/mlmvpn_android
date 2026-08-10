package com.mlmvpn.scanner.data

import android.util.Log
import com.mlmvpn.scanner.utils.NetworkWatchdog
import kotlinx.coroutines.*
import okhttp3.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.math.max

data class ScanResult(
    val ip: String,
    val ping: Int, // average latency in ms, or -1 if timeout
    val lossRate: Float, // 0.0 to 1.0
    var downloadSpeed: Float = 0f // speed in MB/s
)

class CloudflareScanner {

    companion object {
        private const val TAG = "CloudflareScanner"
        private const val TCP_PORT = 443
        private const val TCP_TIMEOUT = 1000 // 1 second timeout for ping
        private const val PING_TIMES = 4
        private const val DOWNLOAD_TIMEOUT = 10000 // 10 seconds for speed test
        private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=52428800"

        // Default Cloudflare IPv4 Ranges
        val DEFAULT_RANGES = listOf(
            "103.21.244.0/22",
            "103.22.200.0/22",
            "103.31.4.0/22",
            "104.16.0.0/13",
            "104.24.0.0/14",
            "108.162.192.0/18",
            "131.0.72.0/22",
            "141.101.64.0/18",
            "162.158.0.0/15",
            "172.67.0.0/16",
            "173.245.48.0/20",
            "188.114.96.0/20",
            "190.93.240.0/20",
            "197.234.240.0/22",
            "198.41.128.0/17"
        )
    }

    /**
     * Parse CIDR and pick one random IP per /24 subnet block.
     * Example: 103.21.244.0/22 contains 4 /24 blocks. It will return 4 random IPs.
     */
    fun generateIPs(cidrList: List<String>): List<String> {
        val resultList = mutableListOf<String>()
        for (cidr in cidrList) {
            val parts = cidr.split("/")
            if (parts.size != 2) continue
            val ipStr = parts[0]
            val prefixLen = parts[1].toIntOrNull() ?: continue
            
            val ipLong = ipToLong(ipStr) ?: continue
            val mask = (0xFFFFFFFFL shl (32 - prefixLen)) and 0xFFFFFFFFL
            val networkIp = ipLong and mask
            val broadcastIp = networkIp or (mask.inv() and 0xFFFFFFFFL)
            
            // Iterate over each /24 block within this subnet
            var currentBlock = networkIp
            while (currentBlock <= broadcastIp) {
                // Generate a random IP in the current /24 block (1 to 254)
                val randomSuffix = (1..254).random().toLong()
                val selectedIp = currentBlock or randomSuffix
                resultList.add(longToIp(selectedIp))
                
                // Move to next /24 block
                currentBlock += 256
            }
        }
        return resultList
    }

    enum class ScanPhase {
        IDLE, PINGING, SPEED_TESTING, DONE
    }

    /**
     * Start the full scanning process.
     */
    suspend fun startScan(
        context: android.content.Context,
        ips: List<String>,
        baseConfig: String,
        limit: Int = 50,
        successTarget: Int = 10,
        maxConcurrency: Int = 200,
        watchdog: NetworkWatchdog,
        onPhaseChange: (ScanPhase) -> Unit,
        onProgress: (Int, Int) -> Unit, // (Completed, Total)
        onIpFound: (ScanResult) -> Unit, // Real-time emit of found IP
        onPaused: (String?) -> Unit = {} // non-null while the scan is frozen waiting for connectivity
    ): List<ScanResult> = coroutineScope {
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val totalIps = ips.size

        Log.d(TAG, "Starting TCP ping on ${ips.size} IPs...")
        onPhaseChange(ScanPhase.PINGING)

        val semaphore = kotlinx.coroutines.sync.Semaphore(maxConcurrency)

        // Phase 1: TCP Ping
        val pingResults = ips.map { ip ->
            async(Dispatchers.IO) {
                semaphore.acquire()
                try {
                    // If the device's own connection just dropped, a bare TCP connect fails
                    // almost instantly (no route) instead of waiting out TCP_TIMEOUT -- which
                    // would otherwise make the scan look like it suddenly sped up while really
                    // just marking every remaining IP "dead" without ever truly testing it.
                    ensureOnline(watchdog, onPaused)
                    val result = tcping(ip)
                    onProgress(completedCount.incrementAndGet(), totalIps)
                    if (result != null) {
                        onIpFound(result)
                    }
                    result
                } finally {
                    semaphore.release()
                }
            }
        }.awaitAll().filterNotNull()

        Log.d(TAG, "TCP ping completed. Valid IPs: ${pingResults.size}")

        // Sort by delay
        val sortedByPing = pingResults.sortedWith(compareBy<ScanResult> { it.lossRate }.thenBy { it.ping })
        
        // Take top limit for real delay testing
        val topIps = sortedByPing.take(limit)
        Log.d(TAG, "Starting real delay test on top ${topIps.size} IPs...")
        
        withContext(Dispatchers.Main) {
            onPhaseChange(ScanPhase.SPEED_TESTING)
            onProgress(0, topIps.size)
        }

        // Phase 2: Real Delay Test
        val speedCompletedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val successCount = java.util.concurrent.atomic.AtomicInteger(0)
        val finalValidIps = java.util.concurrent.CopyOnWriteArrayList<ScanResult>()

        val measureSemaphore = kotlinx.coroutines.sync.Semaphore(15)

        // Declared as a mutable list up front so each task can cancel its siblings
        // as soon as the target is reached, without waiting for slower earlier tasks.
        val deferreds = mutableListOf<Deferred<Unit>>()
        topIps.forEach { ipScan ->
            deferreds.add(async(Dispatchers.IO) {
                measureSemaphore.acquire()
                try {
                    if (!isActive) return@async
                    ensureOnline(watchdog, onPaused)
                    val localPort = (30000..40000).random()
                    val delay = realDelayTest(ipScan.ip, baseConfig, context, localPort)

                    if (delay > 0) {
                        ipScan.downloadSpeed = delay
                        finalValidIps.add(ipScan)
                        withContext(Dispatchers.Main) {
                            onIpFound(ipScan)
                        }
                        if (successCount.incrementAndGet() >= successTarget) {
                            Log.d(TAG, "Found $successTarget successful IPs. Cancelling remaining.")
                            val selfJob = coroutineContext[Job]
                            deferreds.forEach { if (it !== selfJob) it.cancel() }
                        }
                    }
                } finally {
                    measureSemaphore.release()
                    val currentCompleted = speedCompletedCount.incrementAndGet()
                    withContext(Dispatchers.Main) {
                        onProgress(currentCompleted, topIps.size)
                    }
                }
            })
        }

        // Wait for every task to finish or be cancelled (join never throws on cancellation).
        deferreds.forEach { it.join() }

        withContext(Dispatchers.Main) {
            onPhaseChange(ScanPhase.DONE)
        }

        // Final sort by real delay ascending (lower is better)
        return@coroutineScope finalValidIps.sortedBy { it.downloadSpeed }
    }

    /**
     * If the device is currently offline, mark the scan paused (for the UI banner) and suspend
     * here until connectivity is back, instead of racing through the rest of the IP list marking
     * every one "dead" in a few milliseconds each.
     */
    private suspend fun ensureOnline(watchdog: NetworkWatchdog, onPaused: (String?) -> Unit) {
        if (watchdog.isOnline.value) return
        withContext(Dispatchers.Main) {
            onPaused("Ø§ØªØµØ§Ù„ Ø§ÛŒÙ†ØªØ±Ù†Øª Ù‚Ø·Ø¹ Ø´Ø¯Ù‡ â€” Ø§Ø³Ú©Ù† Ù…ØªÙˆÙ‚Ù Ø´Ø¯ Ùˆ Ø¨Ø¹Ø¯ Ø§Ø² ÙˆØµÙ„ Ø´Ø¯Ù† Ø¯ÙˆØ¨Ø§Ø±Ù‡ Ø§Ø¯Ø§Ù…Ù‡ Ù¾ÛŒØ¯Ø§ Ù…ÛŒâ€ŒÚ©Ù†Ø¯...")
        }
        Log.w(TAG, "Paused: device has no internet connectivity")
        watchdog.waitUntilOnline()
        withContext(Dispatchers.Main) {
            onPaused(null)
        }
        Log.d(TAG, "Resumed: connectivity is back")
    }

    /**
     * Performs a TCP ping on port 443, 4 times.
     *
     * Note: unlike the WARP UDP probe, a fast failure here ("connection refused") is a normal,
     * expected outcome when scanning random Cloudflare IPs and does NOT reliably mean the device
     * itself is offline -- so we don't try to infer an outage from timing in this function; that
     * detection is handled up in startScan via the real OS-level NetworkWatchdog instead.
     */
    private fun tcping(ip: String): ScanResult? {
        var successCount = 0
        var totalDelay = 0L

        for (i in 0 until PING_TIMES) {
            val start = System.currentTimeMillis()
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(ip, TCP_PORT), TCP_TIMEOUT)
                val delay = System.currentTimeMillis() - start
                totalDelay += delay
                successCount++
            } catch (e: SocketTimeoutException) {
                Log.v(TAG, "tcping $ip: timed out after ~${TCP_TIMEOUT}ms (normal miss)")
            } catch (e: Exception) {
                Log.v(TAG, "tcping $ip: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try { socket?.close() } catch (e: Exception) {}
            }
        }

        val lossRate = (PING_TIMES - successCount).toFloat() / PING_TIMES
        // Only accept IPs with 0 loss or max 1 loss (0.25 loss rate) to filter out dirty IPs
        if (successCount == 0 || lossRate > 0.25f) {
            return null
        }

        val avgPing = (totalDelay / successCount).toInt()
        // Only accept IPs with reasonable ping
        if (avgPing > 800) {
            return null
        }
        return ScanResult(ip = ip, ping = avgPing, lossRate = lossRate)
    }

    /**
     * Performs a real delay test using Xray native measureOutboundDelay.
     * Returns real delay in milliseconds, or 0f if failed.
     */
    private suspend fun realDelayTest(ip: String, baseConfigUri: String, context: android.content.Context, localPort: Int): Float {
        var realDelay = 0f

        try {
            val config = com.mlmvpn.scanner.utils.VpnConfig.parseUri(baseConfigUri) ?: return 0f
            config.address = ip // Replace the address with our target IP
            // Trimmed test config (see generateSpeedtestConfig): measureOutboundDelay never
            // serves traffic, so the DNS/routing/second-inbound setup the full config carries
            // was paid once per scanned IP for nothing. localPort is no longer used here --
            // each test instance now gets its own port from a private range.
            val jsonConfig = com.mlmvpn.scanner.utils.XrayJsonGenerator.generateSpeedtestConfig(config)

            withContext(Dispatchers.IO) {
                // Initialize Core Environment if not already done
                try {
                    val keyBytes = ByteArray(32)
                    java.security.SecureRandom().nextBytes(keyBytes)
                    val flags = android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                    val xudpBaseKey = android.util.Base64.encodeToString(keyBytes, flags)
                    libv2ray.Libv2ray.initCoreEnv(context.filesDir.absolutePath, xudpBaseKey)
                } catch (e: Exception) {}

                val delayMs = libv2ray.Libv2ray.measureOutboundDelay(jsonConfig, "https://clients3.google.com/generate_204")
                if (delayMs > 0) {
                    realDelay = delayMs.toFloat()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Real delay test failed for $ip", e)
        }
        return realDelay
    }

    private fun ipToLong(ipAddress: String): Long? {
        val ipAddressInArray = ipAddress.split(".")
        if (ipAddressInArray.size != 4) return null
        var result: Long = 0
        for (i in 0..3) {
            val ipPart = ipAddressInArray[i].toLongOrNull() ?: return null
            val power = 3 - i
            result += ipPart * Math.pow(256.0, power.toDouble()).toLong()
        }
        return result
    }

    private fun longToIp(ip: Long): String {
        return ((ip shr 24 and 0xFF).toString() + "."
                + (ip shr 16 and 0xFF) + "."
                + (ip shr 8 and 0xFF) + "."
                + (ip and 0xFF))
    }
}



