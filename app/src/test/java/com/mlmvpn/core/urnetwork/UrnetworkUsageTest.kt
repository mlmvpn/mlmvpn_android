package com.mlmvpn.core.urnetwork

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrnetworkUsageTest {

    private val gib = 1024L * 1024 * 1024

    private fun balance(start: String, end: String, bytes: Long, paid: Boolean = false) = JSONObject()
        .put("start_time", start).put("end_time", end)
        .put("start_balance_byte_count", bytes).put("balance_byte_count", bytes).put("paid", paid)

    @Test
    fun `used is start minus available minus pending, never below zero`() {
        val json = JSONObject()
            .put("start_balance_byte_count", 30 * gib)
            .put("balance_byte_count", 27 * gib)
            .put("open_transfer_byte_count", gib)
        val u = UrnetworkUsage.parse(json, 0L)
        assertEquals(30 * gib, u.total)
        assertEquals(27 * gib, u.remaining)
        assertEquals(gib, u.pending)
        assertEquals(2 * gib, u.used)
        assertEquals(2f / 30f, u.fraction, 0.0001f)
        assertFalse(u.pro)

        // The server samples its three numbers independently: a negative difference reads as zero.
        val odd = UrnetworkUsage.parse(JSONObject().put("start_balance_byte_count", gib).put("balance_byte_count", gib).put("open_transfer_byte_count", 5L), 0L)
        assertEquals(0L, odd.used)
    }

    @Test
    fun `the reset is the soonest end among the live free balances`() {
        val now = System.currentTimeMillis()
        val inTenDays = java.time.Instant.ofEpochMilli(now + 10L * 86_400_000 - 3_600_000).toString()
        val inFortyDays = java.time.Instant.ofEpochMilli(now + 40L * 86_400_000).toString()
        val yesterday = java.time.Instant.ofEpochMilli(now - 86_400_000).toString()
        val json = JSONObject()
            .put("start_balance_byte_count", 40 * gib)
            .put("balance_byte_count", 40 * gib)
            .put("active_transfer_balances", JSONArray()
                .put(balance(yesterday, inFortyDays, 10 * gib, paid = true))
                .put(balance(yesterday, inTenDays, 30 * gib))
                .put(balance("2020-01-01T00:00:00Z", yesterday, 30 * gib)))
        val u = UrnetworkUsage.parse(json, now)
        assertNotNull(u.resetAt)
        assertEquals(10, u.daysLeft(now))
    }

    @Test
    fun `the free data comes again at the next utc midnight, pro at the end of its month`() {
        val day = 86_400_000L
        // 2026-10-19T20:42:00Z: the next grant is 2026-10-20T00:00:00Z (03:30 in Tehran).
        val now = 1_792_442_520_000L
        val midnight = 1_792_454_400_000L
        assertEquals(midnight, UrnetworkUsage.nextFreeRefresh(now))
        // Exactly at midnight the grant just happened: the next one is a day later.
        assertEquals(midnight + day, UrnetworkUsage.nextFreeRefresh(midnight))
        assertEquals(midnight, UrnetworkUsage.nextFreeRefresh(midnight - 1))

        // The balance's own end (an hour past the day) is not when new data comes.
        val inGrace = java.time.Instant.ofEpochMilli(midnight + 3_600_000).toString()
        val dayStart = java.time.Instant.ofEpochMilli(midnight - day).toString()
        val json = JSONObject().put("start_balance_byte_count", 30 * gib).put("balance_byte_count", 30 * gib)
            .put("active_transfer_balances", JSONArray().put(balance(dayStart, inGrace, 30 * gib)))
        val free = UrnetworkUsage.parse(json, now)
        assertEquals(midnight, free.refreshAt(now))

        val proEnd = java.time.Instant.ofEpochMilli(now + 12 * day).toString()
        val proJson = JSONObject().put("current_subscription", JSONObject().put("plan", "monthly"))
            .put("active_transfer_balances", JSONArray().put(balance(dayStart, proEnd, 600 * gib, paid = true)))
        val pro = UrnetworkUsage.parse(proJson, now)
        assertEquals(UrnetworkUsage.time(proEnd), pro.refreshAt(now))
    }

    @Test
    fun `the countdown rounds minutes up and never reads zero while the grant is ahead`() {
        assertEquals(3L to 18L, UrnetworkUsage.countdown(0L, (3 * 60 + 18) * 60_000L))
        assertEquals(0L to 1L, UrnetworkUsage.countdown(0L, 1L))
        assertEquals(0L to 1L, UrnetworkUsage.countdown(0L, 60_000L))
        assertEquals(1L to 0L, UrnetworkUsage.countdown(0L, 60 * 60_000L))
        assertEquals(0L to 0L, UrnetworkUsage.countdown(5L, 0L))
    }

    @Test
    fun `host signals follow the official app's policy`() {
        // the mobile network's country only on mobile data, and only as two letters
        assertEquals("ir", UrnetworkHost.countryFor(true, " IR "))
        assertEquals("", UrnetworkHost.countryFor(false, "ir"))
        assertEquals("", UrnetworkHost.countryFor(true, ""))
        assertEquals("", UrnetworkHost.countryFor(true, "irn"))
        assertEquals("", UrnetworkHost.countryFor(true, null))
        // five steady Wi-Fi levels, none when the radio gave no strength
        assertNull(UrnetworkHost.signalLevel(Int.MIN_VALUE))
        assertEquals(0, UrnetworkHost.signalLevel(-95))
        assertEquals(2, UrnetworkHost.signalLevel(-71))
        assertEquals(3, UrnetworkHost.signalLevel(-61))
        assertEquals(4, UrnetworkHost.signalLevel(-40))
        // power-of-two bandwidth bands: estimator noise inside a band is no change
        assertEquals(0, UrnetworkHost.bandwidthBucket(0))
        assertEquals(16384, UrnetworkHost.bandwidthBucket(30000))
        assertEquals(UrnetworkHost.bandwidthBucket(17000), UrnetworkHost.bandwidthBucket(32000))
        // Data Saver holds the app back only on a metered network
        assertTrue(UrnetworkHost.dataSaverDegrades(true, android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED))
        assertTrue(UrnetworkHost.dataSaverDegrades(true, android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED))
        assertFalse(UrnetworkHost.dataSaverDegrades(true, android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED))
        assertFalse(UrnetworkHost.dataSaverDegrades(false, android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED))
    }

    @Test
    fun `a pro plan is read from the current subscription`() {
        val json = JSONObject().put("current_subscription", JSONObject().put("plan", "monthly"))
        assertTrue(UrnetworkUsage.parse(json, 0L).pro)
        assertNull(UrnetworkUsage.parse(JSONObject(), 0L).resetAt)
    }

    @Test
    fun `server times in both forms`() {
        assertEquals(1_763_452_800_000L, UrnetworkUsage.time("2025-11-18T08:00:00Z"))
        assertEquals(1_763_452_800_123L, UrnetworkUsage.time("2025-11-18T08:00:00.123456Z"))
        assertEquals(1_763_452_800_000L, UrnetworkUsage.time("2025-11-18T11:30:00+03:30"))
        assertEquals(1_763_452_800_000L, UrnetworkUsage.time("2025-11-18 08:00:00 +0000 UTC"))
        assertNull(UrnetworkUsage.time("soon"))
        assertNull(UrnetworkUsage.time(""))
    }

    @Test
    fun `per-destination lists become the SDK's host patterns`() {
        assertEquals(
            listOf("**.example.com", "**.bank.ir", "1.2.3.0/24", "**.gov.ir", "10.0.0.1", "2001:db8::/32", "**.shop.ir"),
            UrnetworkEngine.routeEntries("example.com, *.bank.ir,1.2.3.0/24 .gov.ir\n10.0.0.1;2001:db8::/32  **.shop.ir EXAMPLE.com."),
        )
        assertEquals(emptyList<String>(), UrnetworkEngine.routeEntries(" , ; "))
        // A wildcard anywhere but the front is not a pattern the SDK knows: dropped, not guessed at.
        assertEquals(emptyList<String>(), UrnetworkEngine.routeEntries("ex*mple.com"))
    }
}
