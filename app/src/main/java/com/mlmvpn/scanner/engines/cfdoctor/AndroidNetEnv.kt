package com.mlmvpn.scanner.engines.cfdoctor

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import com.mlmvpn.scanner.engines.game.booster.session.GameNetwork
import java.net.*

class AndroidNetEnv(context: Context): NetEnv {
    val picked=GameNetwork.pick(context)
    override fun resolve(host: String): List<InetAddress> = picked?.network?.getAllByName(host)?.toList() ?: throw UnknownHostException()
    override fun socket(): Socket = picked?.network?.socketFactory?.createSocket() ?: throw SocketException()
    override fun datagram(): DatagramSocket = DatagramSocket(null).also { s ->
        try { (picked?.network ?: throw SocketException()).bindSocket(s); s.bind(InetSocketAddress(0)) } catch(e: Exception) { s.close(); throw e }
    }
    fun environment(context: Context): Map<String,String> = buildMap {
        val cm=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val links=picked?.network?.let(cm::getLinkProperties)
        put("net",when { picked?.isWifi==true -> "wifi"; picked?.isCellular==true -> "cell"; else -> "unknown" })
        put("op",operator(context))
        put("v6",if(links?.linkAddresses?.any { it.address is Inet6Address && !it.address.isLinkLocalAddress }==true) "y" else "n")
        put("validated",(picked?.caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true).toString())
        put("captive",(picked?.caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)==true).toString())
        put("vpn",cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true }.toString())
        put("private_dns",if(Build.VERSION.SDK_INT>=28) (links?.isPrivateDnsActive==true).toString() else "unsupported")
        put("android",Build.VERSION.SDK_INT.toString()); put("model",Build.MODEL.take(48))
        put("app",runCatching { context.packageManager.getPackageInfo(context.packageName,0).versionName }.getOrDefault("unknown"))
        put("time",java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",java.util.Locale.US).format(java.util.Date()))
        put("timezone",java.util.TimeZone.getDefault().id)
    }
    companion object {
        fun operator(context: Context): String = runCatching {
            val sim=(context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).networkOperator
            when(sim) { "43211" -> "MCI"; "43235" -> "MTN"; "43220" -> "RIGHTEL"; else -> "OTHER" }
        }.getOrDefault("UNKNOWN")
    }
}
