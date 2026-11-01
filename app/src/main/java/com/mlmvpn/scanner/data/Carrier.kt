package com.mlmvpn.scanner.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * Which operator carries mobile data right now -- one answer for every screen that names it.
 *
 * Users reported the app calling MCI «ایرانسل» and Irancell «همراه اول» (2026-10-11). The readers
 * asked the default TelephonyManager, which speaks for the default SIM -- the one for calls on a
 * dual-SIM phone, not necessarily the one for data -- and for the network it is registered on, which
 * under national roaming is another operator's. Here: the SIM of the cellular network that carries
 * data (Android 11+ names it), else the default data SIM, and that SIM's own operator code.
 */
object Carrier {

    const val MCI = "MCI"
    const val MTN = "MTN"
    const val RIGHTEL = "RIGHTEL"
    const val OTHER = "OTHER"
    const val UNKNOWN = "UNKNOWN"

    /** MCC+MNC ("43235") of the SIM carrying mobile data, or "" when it cannot be told. */
    fun dataMccMnc(context: Context): String = runCatching {
        val tm = dataTelephony(context) ?: return ""
        tm.simOperator?.takeIf { it.length >= 5 } ?: tm.networkOperator?.takeIf { it.length >= 5 } ?: ""
    }.getOrDefault("")

    /** [MCI], [MTN], [RIGHTEL], [OTHER], or [UNKNOWN] when there is no SIM to ask. */
    fun code(context: Context): String = dataMccMnc(context).let { if (it.isEmpty()) UNKNOWN else codeOf(it) }

    fun codeOf(mccMnc: String): String = when (mccMnc) {
        "43211" -> MCI
        "43235" -> MTN
        "43220" -> RIGHTEL
        else -> OTHER
    }

    /** The data SIM's operator name as the SIM gives it ("MTN Irancell"), or "". */
    fun name(context: Context): String = runCatching {
        val tm = dataTelephony(context) ?: return ""
        tm.simOperatorName?.takeIf { it.isNotBlank() } ?: tm.networkOperatorName.orEmpty()
    }.getOrDefault("")

    private fun dataTelephony(context: Context): TelephonyManager? {
        val app = context.applicationContext
        val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        val sub = carryingSubscription(app) ?: SubscriptionManager.getDefaultDataSubscriptionId()
        return if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) runCatching { tm.createForSubscriptionId(sub) }.getOrDefault(tm) else tm
    }

    /** The subscription of the cellular network that carries data now (under a VPN too), Android 11+. */
    private fun carryingSubscription(app: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            cm.allNetworks.asSequence()
                .mapNotNull { cm.getNetworkCapabilities(it) }
                .filter { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
                .mapNotNull { (it.networkSpecifier as? android.net.TelephonyNetworkSpecifier)?.subscriptionId }
                .firstOrNull { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        }.getOrNull()
    }
}
