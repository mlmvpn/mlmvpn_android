package com.mlmvpn.scanner.ui.configstudio.parts

import android.content.Context
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.studio.api.StudioError
import com.mlmvpn.scanner.data.studio.domain.StudioUser
import com.mlmvpn.scanner.data.studio.domain.UserStatus
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.utils.AppLocaleManager

/**
 * What the central-Worker screens of «کانفیگ استدیو» say, in one place: a refusal in the operator's
 * own words, a date in their calendar, and the short message they send a person in place of the
 * subscription page the central Worker does not have.
 */
object CentralKit {

    /**
     * A failure as a sentence. The codes are [com.mlmvpn.scanner.data.studio.central.CentralRules]'s and
     * the API's; a Worker-side refusal carries its own words in [StudioError.detail] already.
     */
    fun errorText(context: Context, error: StudioError): String = when (error.code) {
        "no_room" -> tr(
            "روی ورکر مرکزی این حساب جای کاربر دیگری نیست. حساب کلادفلر دیگری اضافه کنید، یا کسی را خاموش یا حذف کنید.",
            "The central Worker of this account has no room for another person. Add another Cloudflare account, or switch someone off or delete them.",
        )
        "registry_unreadable" -> tr(
            "فهرست کاربران روی حساب کلادفلر خوانده نشد، پس برای اینکه چیزی از دستگاه دیگر پاک نشود، کاری انجام نشد. کمی بعد دوباره امتحان کنید.",
            "The list of people on the Cloudflare account could not be read, so nothing was done — that keeps another device's changes safe. Try again shortly.",
        )
        "already_moved" -> tr("این کاربر قبلاً به ورکر مرکزی منتقل شده است.", "This person was already moved to the central Worker.")
        "not_supported" -> tr("این کار روی ورکر مرکزی شدنی نیست.", "This cannot be done on the central Worker.")
        "bad_request" -> tr("مقدار واردشده درست نیست.", "The value entered is not valid.")
        "username_taken" -> context.getString(R.string.studio_err_username_taken)
        StudioError.NOT_FOUND -> tr("این کاربر دیگر نیست.", "This person no longer exists.")
        StudioError.NETWORK -> error.detail?.takeIf { it.isNotBlank() } ?: context.getString(R.string.studio_err_network)
        "gateway" -> error.detail?.takeIf { it.isNotBlank() } ?: context.getString(R.string.studio_err_generic)
        else -> context.getString(R.string.studio_err_generic)
    }

    /** What went wrong the last time a central Worker was brought in step (StudioStore.refreshInstallations), as a short line. */
    fun syncProblemText(code: String): String = when (code) {
        "central_full" -> tr("ورکر مرکزی پر است؛ چند کاربر جا نشدند", "The central Worker is full; some people did not fit")
        "registry_unreadable" -> tr("فهرست حساب خوانده نشد؛ دوباره امتحان می‌شود", "The account's list could not be read; it is tried again")
        else -> tr("ورکر مرکزی به‌روز نشد؛ دوباره امتحان می‌شود", "The central Worker was not updated; it is tried again")
    }

    /** A day in the operator's calendar: the Persian one in Persian, the Gregorian one in English. */
    fun dateText(ms: Long): String = runCatching {
        val locale = if (AppLocaleManager.isFarsi()) android.icu.util.ULocale("fa_IR@calendar=persian") else android.icu.util.ULocale("en_US")
        android.icu.text.DateFormat.getDateInstance(android.icu.text.DateFormat.LONG, locale).format(java.util.Date(ms))
    }.getOrElse { java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(java.util.Date(ms)) }

    private fun digits(n: Long): String = com.mlmvpn.scanner.ui.emergency.faDigits(n.toString())

    /** Whole days left, rounded to the nearest day (twelve hours and more is a day), or null once it is up. */
    fun daysLeft(expiresAt: Long, now: Long = System.currentTimeMillis()): Long? {
        val left = expiresAt - now
        if (expiresAt <= 0 || left <= 0) return null
        return ((left + 43_200_000L) / 86_400_000L).coerceAtLeast(0)
    }

    /**
     * The message the operator sends a person: their name, how long they have, and how to reach the
     * operator. Short on purpose — it is pasted into a chat — and it names no product.
     */
    fun statusMessage(user: StudioUser, contact: String?, now: Long = System.currentTimeMillis()): String {
        val lines = mutableListOf(user.username)
        val end = user.policy.expiresAt
        lines += when {
            user.status == UserStatus.DISABLED -> tr("اتصال شما فعلاً خاموش است.", "Your access is switched off for now.")
            end == null || end <= 0 -> tr("اعتبار شما تاریخ پایان ندارد.", "Your access has no end date.")
            end <= now -> tr("اعتبار شما به پایان رسیده است.", "Your access has ended.")
            else -> {
                val days = daysLeft(end, now) ?: 0
                val left = if (days >= 1) tr("${digits(days)} روز مانده", "$days days left") else tr("کمتر از یک روز مانده", "less than a day left")
                tr("اعتبار تا ${dateText(end)} — $left", "Valid until ${dateText(end)} — $left")
            }
        }
        // In its own direction: a Latin contact («@support») inside a Persian message otherwise shows
        // with its «@» thrown to the far end. The isolate marks are invisible wherever it is pasted.
        contact?.trim()?.takeIf { it.isNotEmpty() }?.let { lines += "⁨$it⁩" }
        return lines.joinToString("\n")
    }
}
