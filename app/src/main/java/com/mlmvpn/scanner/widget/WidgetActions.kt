package com.mlmvpn.scanner.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * What a tap on a widget does, as the PendingIntents the widgets carry.
 *
 * Every tap goes to [WidgetActionActivity] -- an invisible activity -- rather than to a broadcast
 * receiver. A tap is the one moment Android lets an app start things from the home screen, and an
 * activity is the one place that can also show the VPN consent dialog when it is needed, and start
 * the foreground service that carries the connect (WidgetConnectService) with no background-start
 * restriction in the way.
 */
object WidgetActions {

    const val ACTION_TOGGLE = "com.mlmvpn.scanner.widget.TOGGLE"
    const val ACTION_OPEN = "com.mlmvpn.scanner.widget.OPEN"
    /** Opens «ویجت استدیو» (a placed widget whose design was deleted). */
    const val ACTION_STUDIO = "com.mlmvpn.scanner.widget.STUDIO"

    const val EXTRA_METHOD = "widget_method"
    const val EXTRA_COUNTRY = "widget_country"
    const val EXTRA_WIDGET_ID = "widget_id"

    fun pending(context: Context, action: String, method: String?, widgetId: Int, country: String? = null): PendingIntent {
        val intent = Intent(context, WidgetActionActivity::class.java)
            .setAction(action)
            // Unique per widget and action: PendingIntents that differ only in extras would be one
            // and the same, and every widget would end up carrying the last one made.
            .setData(Uri.parse("mlmvpn-widget://${action.substringAfterLast('.')}/$widgetId/${method.orEmpty()}"))
            .putExtra(EXTRA_METHOD, method)
            .putExtra(EXTRA_WIDGET_ID, widgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        if (country != null) intent.putExtra(EXTRA_COUNTRY, country)
        return PendingIntent.getActivity(
            context,
            requestCode(action, widgetId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun requestCode(action: String, widgetId: Int): Int = widgetId * 8 + when (action) {
        ACTION_TOGGLE -> 1
        ACTION_OPEN -> 2
        else -> 3
    }
}
