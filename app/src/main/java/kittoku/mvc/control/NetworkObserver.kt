package kittoku.mvc.control

import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.preference.PreferenceManager
import kittoku.mvc.SharedBridge
import kittoku.mvc.preference.MvcPreference
import kittoku.mvc.preference.accessor.setStringPrefValue
import kittoku.mvc.teminal.UDPStatus


internal class NetworkObserver(val bridge: SharedBridge) {
    private val manager = bridge.service.getSystemService(ConnectivityManager::class.java)
    private val callback: ConnectivityManager.NetworkCallback
    private val updListener: SharedPreferences.OnSharedPreferenceChangeListener
    private val prefs = PreferenceManager.getDefaultSharedPreferences(bridge.service)

    init {
        wipeStatus()

        val request = NetworkRequest.Builder().let {
            it.addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            it.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            it.build()
        }

        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                updateSummary(linkProperties)
            }
        }

        manager.registerNetworkCallback(request, callback)

        setStringPrefValue("", MvcPreference.UDP_STATUS, prefs)

        updListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == MvcPreference.UDP_STATUS.name) {
                enforceUpdateSummary()
            }
        }

        prefs.registerOnSharedPreferenceChangeListener(updListener)
    }

    private fun updateSummary(properties: LinkProperties) {
        val summary = mutableListOf<String>()

        summary.add("[Assigned IP Address]")
        properties.linkAddresses.forEach {
            summary.add(it.address.hostAddress)
        }
        summary.add("")

        summary.add("[UDP Acceleration]")
        bridge.udpAccelerationConfig?.also {
            when (it.status) {
                UDPStatus.OPEN -> {
                    summary.add("Connected to ${it.serverCurrentAddress!!.hostAddress}:${it.serverCurrentPort}")
                }

                UDPStatus.CLOSED -> {
                    summary.add("Connecting...")
                }
            }
        } ?: summary.add("Disabled")
        summary.add("")

        summary.add("[Route]")
        properties.routes.forEach {
            summary.add(it.toString())
        }

        summary.reduce { acc, s ->
            acc + "\n" + s
        }.also {
            setStringPrefValue(it, MvcPreference.HOME_STATUS, prefs)
        }
    }

    internal fun enforceUpdateSummary() {
        manager.getLinkProperties(manager.activeNetwork)?.also {
            updateSummary(it)
        }
    }

    private fun wipeStatus() {
        setStringPrefValue("", MvcPreference.HOME_STATUS, prefs)
    }

    internal fun close() {
        prefs.unregisterOnSharedPreferenceChangeListener(updListener)

        try {
            manager.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) { } // already unregistered

        wipeStatus()
    }
}