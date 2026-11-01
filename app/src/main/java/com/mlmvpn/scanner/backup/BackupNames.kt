package com.mlmvpn.scanner.backup

import java.util.Calendar
import java.util.TimeZone

/** The suggested file name and the dates the screens print. Pure JVM. */
internal object BackupNames {

    /**
     * `MLMVPN-backup-1405-07-26-1042.mlmbak`: the solar date for a Persian user, the Gregorian one
     * otherwise, and the time so two backups of one day do not ask to overwrite each other. Latin
     * digits, because a file name travels through apps that do not all handle Persian ones.
     */
    fun fileName(atMillis: Long, solar: Boolean, zone: TimeZone = TimeZone.getDefault()): String {
        val d = dateParts(atMillis, solar, zone)
        return "MLMVPN-backup-%04d-%02d-%02d-%02d%02d.%s".format(
            java.util.Locale.US, d[0], d[1], d[2], d[3], d[4], BackupFile.EXTENSION,
        )
    }

    /** "۱۴۰۵/۰۷/۲۶ ۱۰:۴۲" or "2026-10-18 10:42". */
    fun displayDate(atMillis: Long, solar: Boolean, zone: TimeZone = TimeZone.getDefault()): String {
        val d = dateParts(atMillis, solar, zone)
        return if (solar) {
            persianDigits("%04d/%02d/%02d %02d:%02d".format(java.util.Locale.US, d[0], d[1], d[2], d[3], d[4]))
        } else {
            "%04d-%02d-%02d %02d:%02d".format(java.util.Locale.US, d[0], d[1], d[2], d[3], d[4])
        }
    }

    /** [year, month, day, hour, minute]. */
    private fun dateParts(atMillis: Long, solar: Boolean, zone: TimeZone): IntArray {
        val c = Calendar.getInstance(zone).apply { timeInMillis = atMillis }
        val gy = c.get(Calendar.YEAR)
        val gm = c.get(Calendar.MONTH) + 1
        val gd = c.get(Calendar.DAY_OF_MONTH)
        val ymd = if (solar) gregorianToSolar(gy, gm, gd) else intArrayOf(gy, gm, gd)
        return intArrayOf(ymd[0], ymd[1], ymd[2], c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    /**
     * Gregorian to the Iranian solar calendar, the widely used arithmetic of jdf.scr.ir. Exact for the
     * years a phone can be set to (it follows the 33-year arithmetic, which matches the official
     * calendar from 1178 to 1634).
     */
    fun gregorianToSolar(gy: Int, gm: Int, gd: Int): IntArray {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 + gd + gdm[gm - 1]
        var jy = -1595 + 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + days / 31
            jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30
            jd = 1 + (days - 186) % 30
        }
        return intArrayOf(jy, jm, jd)
    }

    fun persianDigits(s: String): String = buildString(s.length) {
        for (ch in s) append(if (ch in '0'..'9') '۰' + (ch - '0') else ch)
    }
}
