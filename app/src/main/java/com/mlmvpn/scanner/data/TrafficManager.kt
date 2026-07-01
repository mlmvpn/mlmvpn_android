package com.mlmvpn.scanner.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TrafficManager(context: Context) {
    private val prefs = context.getSharedPreferences("vpn_traffic_stats", Context.MODE_PRIVATE)
    private val dateFormat = SimpleDateFormat("yyyy_MM_dd", Locale.US)

    data class TrafficData(val rxBytes: Long, val txBytes: Long)

    private fun getTodayDateString(): String {
        return dateFormat.format(Date())
    }

    private fun getDateString(offsetDays: Int): String {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, offsetDays)
        return dateFormat.format(calendar.time)
    }

    fun addTraffic(rxDelta: Long, txDelta: Long) {
        if (rxDelta <= 0 && txDelta <= 0) return
        
        val today = getTodayDateString()
        val currentRx = prefs.getLong("rx_$today", 0L)
        val currentTx = prefs.getLong("tx_$today", 0L)
        
        prefs.edit()
            .putLong("rx_$today", currentRx + rxDelta)
            .putLong("tx_$today", currentTx + txDelta)
            .apply()
    }

    fun getTodayTraffic(): TrafficData {
        val today = getTodayDateString()
        return TrafficData(
            rxBytes = prefs.getLong("rx_$today", 0L),
            txBytes = prefs.getLong("tx_$today", 0L)
        )
    }

    fun getTrafficForDays(days: Int): List<TrafficData> {
        val list = mutableListOf<TrafficData>()
        for (i in (days - 1) downTo 0) {
            val dateStr = getDateString(-i)
            list.add(TrafficData(
                rxBytes = prefs.getLong("rx_$dateStr", 0L),
                txBytes = prefs.getLong("tx_$dateStr", 0L)
            ))
        }
        return list
    }

    fun getWeeklyTraffic(): TrafficData {
        val list = getTrafficForDays(7)
        return TrafficData(
            rxBytes = list.sumOf { it.rxBytes },
            txBytes = list.sumOf { it.txBytes }
        )
    }

    fun getMonthlyTraffic(): TrafficData {
        val list = getTrafficForDays(30)
        return TrafficData(
            rxBytes = list.sumOf { it.rxBytes },
            txBytes = list.sumOf { it.txBytes }
        )
    }
}
