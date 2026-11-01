package com.mlmvpn.scanner.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class BackupNamesTest {

    @Test
    fun `solar dates around Nowruz and today`() {
        assertArrayEquals(intArrayOf(1405, 7, 26), BackupNames.gregorianToSolar(2026, 10, 18))
        assertArrayEquals(intArrayOf(1405, 1, 1), BackupNames.gregorianToSolar(2026, 3, 21))
        assertArrayEquals(intArrayOf(1404, 12, 29), BackupNames.gregorianToSolar(2026, 3, 20))
        assertArrayEquals(intArrayOf(1404, 1, 1), BackupNames.gregorianToSolar(2025, 3, 21))
        // 1403 was a leap year: Esfand had 30 days.
        assertArrayEquals(intArrayOf(1403, 12, 30), BackupNames.gregorianToSolar(2025, 3, 20))
        assertArrayEquals(intArrayOf(1403, 1, 1), BackupNames.gregorianToSolar(2024, 3, 20))
        assertArrayEquals(intArrayOf(1402, 1, 1), BackupNames.gregorianToSolar(2023, 3, 21))
        assertArrayEquals(intArrayOf(1405, 6, 31), BackupNames.gregorianToSolar(2026, 9, 22))
        assertArrayEquals(intArrayOf(1405, 7, 1), BackupNames.gregorianToSolar(2026, 9, 23))
    }

    @Test
    fun `the file name carries the date, the time and the extension, in Latin digits`() {
        val tehran = TimeZone.getTimeZone("Asia/Tehran")
        val at = Calendar.getInstance(tehran).apply { clear(); set(2026, Calendar.OCTOBER, 18, 10, 42) }.timeInMillis
        assertEquals("MLMVPN-backup-1405-07-26-1042.mlmbak", BackupNames.fileName(at, solar = true, zone = tehran))
        assertEquals("MLMVPN-backup-2026-10-18-1042.mlmbak", BackupNames.fileName(at, solar = false, zone = tehran))
        assertEquals("۱۴۰۵/۰۷/۲۶ ۱۰:۴۲", BackupNames.displayDate(at, solar = true, zone = tehran))
    }
}
