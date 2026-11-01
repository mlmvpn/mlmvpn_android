package com.mlmvpn.scanner.store

import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The store's downloader -- which the app's own update now uses -- against a server that drops the
 * connection mid-file, the way the lines users complained about do (2026-10-20: «تا آخر میره بعد
 * قطع میشه، دوباره باید از اول دانلود کنیم»).
 */
class StoreDownloadTest {

    @get:Rule val tmp = TemporaryFolder()

    private val data = Random(42).nextBytes(3_000_000)
    private val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    private fun artifact(url: String) = StoreArtifact("app.apk", listOf(url), sha, data.size.toLong(), "raw")

    @Test fun `a file cut twice is finished from where each cut left it`() {
        FlakyServer(data, cutAfter = listOf(1_000_000, 700_000, null)).use { srv ->
            val dest = File(tmp.root, "app.apk")
            StoreNet.download({ client }, artifact(srv.url), dest, { _, _ -> }, backoff = {})
            assertArrayEquals(data, dest.readBytes())
            assertEquals(listOf(null, "bytes=1000000-", "bytes=1700000-"), srv.ranges)
            assertTrue("the part is gone once the file is whole", !File(dest.path + ".part").exists())
        }
    }

    @Test fun `a slow line that drops often still finishes`() {
        // Ten cuts in a row, each after some progress: none of them counts against the URL.
        FlakyServer(data, cutAfter = List(10) { 250_000 } + listOf(null)).use { srv ->
            val dest = File(tmp.root, "app.apk")
            StoreNet.download({ client }, artifact(srv.url), dest, { _, _ -> }, backoff = {})
            assertArrayEquals(data, dest.readBytes())
            assertEquals(11, srv.ranges.size)
        }
    }

    @Test fun `three drops that bring nothing give the URL up, and keep what arrived`() {
        FlakyServer(data, cutAfter = listOf(400_000, 0, 0, 0, 0)).use { srv ->
            val dest = File(tmp.root, "app.apk")
            try {
                StoreNet.download({ client }, artifact(srv.url), dest, { _, _ -> }, backoff = {})
                fail("a server that sends nothing cannot finish the file")
            } catch (e: java.io.IOException) {
                // Expected: the caller is told, and the next press continues from the part.
            }
            assertEquals(4, srv.ranges.size)
            assertEquals(400_000L, File(dest.path + ".part").length())
        }
        // Pressed again later, on a line that works: it asks only for the rest.
        FlakyServer(data, cutAfter = listOf(null)).use { srv ->
            val dest = File(tmp.root, "app.apk")
            StoreNet.download({ client }, artifact(srv.url), dest, { _, _ -> }, backoff = {})
            assertArrayEquals(data, dest.readBytes())
            assertEquals(listOf("bytes=400000-"), srv.ranges)
        }
    }

    @Test fun `a range answered from the wrong place starts the file over instead of splicing`() {
        FlakyServer(data, cutAfter = listOf(500_000, null, null), wrongRangeOn = 1).use { srv ->
            val dest = File(tmp.root, "app.apk")
            StoreNet.download({ client }, artifact(srv.url), dest, { _, _ -> }, backoff = {})
            assertArrayEquals(data, dest.readBytes())
            assertEquals(listOf(null, "bytes=500000-", null), srv.ranges)
        }
    }

    @Test fun `the part of another version is not continued`() {
        val dest = File(tmp.root, "app.apk")
        File(dest.path + ".part").writeBytes(ByteArray(123_456) { 7 })
        File(dest.path + ".part.id").writeText("another|1|app.apk")
        FlakyServer(data, cutAfter = listOf(null)).use { srv ->
            StoreNet.download({ client }, artifact(srv.url), dest, { _, _ -> }, backoff = {})
            assertArrayEquals(data, dest.readBytes())
            assertEquals(listOf<String?>(null), srv.ranges)
        }
    }

    /**
     * A plain HTTP/1.1 file server on loopback that honours Range and, for request i, sends only
     * [cutAfter][i] bytes of the body before closing the socket (null: the whole body).
     */
    private class FlakyServer(
        private val data: ByteArray,
        private val cutAfter: List<Int?>,
        /** On this request a Range is answered as if it began at byte 0. */
        private val wrongRangeOn: Int = -1,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/app.apk"
        val ranges: MutableList<String?> = java.util.Collections.synchronizedList(mutableListOf())

        init {
            Thread({
                var i = 0
                while (!server.isClosed) {
                    val s = runCatching { server.accept() }.getOrNull() ?: break
                    runCatching { serve(s, i++) }
                }
            }, "flaky-server").apply { isDaemon = true }.start()
        }

        private fun serve(s: Socket, i: Int) = s.use {
            val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            reader.readLine() ?: return
            var range: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
            }
            ranges += range
            val asked = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            val start = if (i == wrongRangeOn) 0 else asked
            val body = data.copyOfRange(start, data.size)
            val head = if (range != null) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-${data.size - 1}/${data.size}\r\n"
            else "HTTP/1.1 200 OK\r\n"
            val out = s.getOutputStream()
            out.write("${head}Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.write(body, 0, minOf(cutAfter.getOrNull(i) ?: body.size, body.size))
            out.flush()
        }

        override fun close() = server.close()
    }
}
