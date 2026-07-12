package com.mlmvpn.scanner.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Watches real device connectivity while a scan is running.
 *
 * Without this, a scanner that loses its network mid-run doesn't actually notice: every probe
 * (TCP connect / UDP send) fails almost instantly with "network unreachable" instead of waiting
 * out its real timeout, so the scan appears to speed up dramatically while it's really just
 * burning through IPs marking every single one "dead" -- a completely fake result, not a real
 * scan. This class gives scanners a real signal to pause on and resume from automatically.
 */
class NetworkWatchdog(context: Context) {

    companion object {
        private const val TAG = "NetworkWatchdog"
    }

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _isOnline = MutableStateFlow(currentlyOnline())
    val isOnline: StateFlow<Boolean> = _isOnline

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null || connectivityManager == null) return
        _isOnline.value = currentlyOnline()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _isOnline.value = currentlyOnline()
                Log.d(TAG, "Network available -- online=${_isOnline.value}")
            }
            override fun onLost(network: Network) {
                _isOnline.value = currentlyOnline()
                Log.w(TAG, "Network lost -- online=${_isOnline.value}")
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                _isOnline.value = currentlyOnline()
            }
        }
        try {
            connectivityManager.registerNetworkCallback(request, cb)
            callback = cb
        } catch (e: Exception) {
            Log.w(TAG, "Could not register network callback, assuming online", e)
            _isOnline.value = true
        }
    }

    fun stop() {
        try {
            callback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            // Already unregistered or manager gone -- harmless.
        }
        callback = null
    }

    private fun currentlyOnline(): Boolean {
        return try {
            val network = connectivityManager?.activeNetwork ?: return false
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            true // fail-open: never block scanning just because the check itself errored
        }
    }

    /** Suspends (polling) until the device is back online. */
    suspend fun waitUntilOnline() {
        while (!_isOnline.value) {
            delay(1000)
        }
    }
}
