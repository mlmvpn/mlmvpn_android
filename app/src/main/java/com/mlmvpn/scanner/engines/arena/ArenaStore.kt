package com.mlmvpn.scanner.engines.arena

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File

/**
 * The arena's history: the last [MAX] races, each with its time and the network it ran on.
 *
 * [latest] is the groundwork for Smart Connect, and deliberately narrow: a result counts as current
 * only on the SAME network and within [FRESH_MS]. Anything else is history, shown as history.
 */
object ArenaStore {

    const val MAX = 50
    const val FRESH_MS = 24 * 60 * 60 * 1000L

    private val _history = MutableStateFlow<List<ArenaSession>>(emptyList())
    val history: StateFlow<List<ArenaSession>> = _history.asStateFlow()
    @Volatile private var loaded = false

    private fun file(context: Context) = File(File(context.filesDir, "arena").apply { mkdirs() }, "history.json")

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _history.value = runCatching {
            val arr = JSONArray(file(context).readText())
            (0 until arr.length()).map { ArenaSession.from(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /** Newest first; trimmed to [MAX]. */
    fun trim(list: List<ArenaSession>): List<ArenaSession> = list.sortedByDescending { it.startedAt }.take(MAX)

    @Synchronized
    fun add(context: Context, s: ArenaSession) {
        load(context)
        val next = trim(listOf(s) + _history.value.filter { it.id != s.id })
        _history.value = next
        runCatching {
            val f = file(context)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(JSONArray(next.map { it.toJson() }).toString())
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        }
    }

    /** The newest race on [network] that is still fresh, or null. */
    fun latest(network: ArenaNetwork, now: Long = System.currentTimeMillis()): ArenaSession? =
        _history.value.firstOrNull { it.network == network && now - it.finishedAt in 0..FRESH_MS }

    /** Wi-Fi or mobile, and the operator: the context a result belongs to. */
    fun network(context: Context): ArenaNetwork {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val kind = when {
            caps == null -> ""
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
        val op = if (kind == "Mobile") com.mlmvpn.scanner.data.Carrier.name(context) else ""
        return ArenaNetwork(kind, op)
    }
}
