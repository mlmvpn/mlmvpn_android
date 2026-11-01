package com.mlmvpn.scanner.widget

import com.mlmvpn.core.tunnel.IpFormatter
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.utils.AppLocaleManager
import java.util.Locale

/**
 * Numbers, sizes, durations and countries as the widgets show them, in the language the user picked
 * in the app -- not the phone's. A widget is drawn outside the app's activity, where nothing else
 * would pin the language, so everything a widget says goes through here or through [tr].
 */
object WidgetFormat {

    private val persian: Boolean get() = AppLocaleManager.isFarsi()

    /** Western digits to Persian ones when the app is in Persian; anything else passes through. */
    fun digits(text: String): String {
        if (!persian) return text
        val out = StringBuilder(text.length)
        for (c in text) out.append(
            when (c) {
                in '0'..'9' -> '۰' + (c - '0')
                // The Persian decimal separator, so «۱٫۲» does not read as a date or a list.
                '.' -> '٫'
                else -> c
            }
        )
        return out.toString()
    }

    /** A byte count: «۸۴۰ KB», «۱٫۲۴ GB». Units stay Latin; they are what every meter on a phone uses. */
    fun bytes(n: Long): String {
        val v = n.coerceAtLeast(0L).toDouble()
        val text = when {
            v < 1024.0 -> "${v.toLong()} B"
            v < 1024.0 * 1024 -> "${one(v / 1024)} KB"
            v < 1024.0 * 1024 * 1024 -> "${one(v / (1024 * 1024))} MB"
            else -> "${two(v / (1024.0 * 1024 * 1024))} GB"
        }
        return digits(text)
    }

    /** A rate in bytes per second: «۱۲٫۴ MB/s». */
    fun rate(bytesPerSecond: Long): String = bytes(bytesPerSecond).let { it + "/s" }

    /** A session's length: «۰۰:۱۲:۳۴», or «۱۲:۳۴» under an hour when [short] is set. */
    fun clock(ms: Long, short: Boolean = false): String {
        val total = (ms.coerceAtLeast(0L) / 1000L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val text = if (short && h == 0L) String.format(Locale.US, "%02d:%02d", m, s)
        else String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        return digits(text)
    }

    /**
     * The same length in words, for a widget that is redrawn every few seconds rather than every
     * second: «۱۲ دقیقه», «۲ ساعت و ۵ دقیقه». A ticking clock that only moves every three seconds
     * reads as a broken clock; minutes do not.
     */
    fun duration(ms: Long): String {
        val minutes = (ms.coerceAtLeast(0L) / 60_000L)
        val h = minutes / 60
        val m = minutes % 60
        val text = when {
            minutes < 1 -> tr("کمتر از یک دقیقه", "under a minute")
            h == 0L -> tr("$m دقیقه", "$m min")
            m == 0L -> tr("$h ساعت", "$h h")
            else -> tr("$h ساعت و $m دقیقه", "$h h $m min")
        }
        return digits(text)
    }

    /**
     * Two parts of one status, «متصل، ۱۲ دقیقه». A Persian comma rather than a middle dot: beside
     * Persian digits a «·» is the same small dot as «۰», and «متصل · ۱۲» read as «متصل ۱۲۰».
     */
    fun joined(first: String, second: String): String = if (persian) "$first، $second" else "$first · $second"

    /** A country's name in the app's language, or the code itself when it is not a real country. */
    fun countryName(code: String?): String {
        val key = code?.trim()?.uppercase().orEmpty()
        if (!IpFormatter.isRealCountry(key)) return key
        val locale = if (persian) Locale("fa") else Locale.ENGLISH
        return Locale("", key).getDisplayCountry(locale).ifBlank { key }
    }

    /** The flag emoji for a country code; a globe for anything that is not one. */
    fun flag(code: String?): String = IpFormatter.flag(code)

    private fun one(v: Double): String =
        if (v >= 100) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

    private fun two(v: Double): String =
        if (v >= 100) v.toLong().toString() else String.format(Locale.US, "%.2f", v)
}
