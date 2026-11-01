package com.mlmvpn.scanner.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.mlmvpn.scanner.store.tr

/**
 * A WebView for an AndroidView factory, or a short notice in its place when the phone has none to
 * give right now.
 *
 * Creating a WebView loads the system's WebView package, and that fails while the package is being
 * updated, when it is disabled, or on a box that ships without one: the constructor (or the first
 * WebStorage / CookieManager call) throws, and inside a composition that closed the app -- FENIX's
 * sign-in page on an Android 12 TV box (AndroidRuntimeException: InvocationTargetException, 1.2.41).
 */
internal fun webViewOrNotice(ctx: Context, build: (Context) -> View): View = try {
    build(ctx)
} catch (t: Throwable) {
    android.util.Log.w("SafeWebView", "no WebView on this phone right now: ${t.javaClass.simpleName} ${t.message}")
    TextView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        gravity = Gravity.CENTER
        setPadding(48, 48, 48, 48)
        setTextColor(0xFFB0B0B5.toInt())
        textSize = 15f
        text = tr(
            "مرورگر داخلی اندروید (Android System WebView) روی این گوشی در دسترس نیست — شاید در حال به‌روزرسانی یا غیرفعال است. آن را از گوگل‌پلی به‌روز یا فعال کنید و دوباره امتحان کنید.",
            "Android System WebView is not available on this phone right now -- it may be updating or disabled. Update or enable it from Google Play and try again.",
        )
    }
}
