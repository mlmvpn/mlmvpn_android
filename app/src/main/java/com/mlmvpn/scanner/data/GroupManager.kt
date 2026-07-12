package com.mlmvpn.scanner.data

import android.content.Context
import com.mlmvpn.scanner.models.VpnNode
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.launch

data class CloudGroup(
    val id: String,
    val accountId: String,
    val date: String,
    val title: String,
    val nodes: List<VpnNode>
)

data class ScannerGroup(
    val id: String,
    val date: String,
    val title: String,
    val sourceGroupCount: Int,
    val ipCount: Int,
    val sourceGroupId: String? = null,
    val nodes: List<VpnNode>
)

data class IpArchive(
    val id: String,
    val date: String,
    val ips: List<String>
)

class GroupManager private constructor(context: Context) {
    companion object {
        @Volatile
        private var instance: GroupManager? = null

        operator fun invoke(context: Context): GroupManager {
            return instance ?: synchronized(this) {
                instance ?: GroupManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences("group_manager_prefs", Context.MODE_PRIVATE)

    val cloudGroups = mutableListOf<CloudGroup>()
    val scannerGroups = mutableListOf<ScannerGroup>()
    val ipArchives = mutableListOf<IpArchive>()

    private val _cloudGroupsFlow = kotlinx.coroutines.flow.MutableStateFlow<List<CloudGroup>>(emptyList())
    val cloudGroupsFlow: kotlinx.coroutines.flow.StateFlow<List<CloudGroup>> = _cloudGroupsFlow

    private val _scannerGroupsFlow = kotlinx.coroutines.flow.MutableStateFlow<List<ScannerGroup>>(emptyList())
    val scannerGroupsFlow: kotlinx.coroutines.flow.StateFlow<List<ScannerGroup>> = _scannerGroupsFlow

    private val _ipArchivesFlow = kotlinx.coroutines.flow.MutableStateFlow<List<IpArchive>>(emptyList())
    val ipArchivesFlow: kotlinx.coroutines.flow.StateFlow<List<IpArchive>> = _ipArchivesFlow

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            loadCloudGroups()
            loadScannerGroups()
            loadIpArchives()
            _cloudGroupsFlow.value = cloudGroups.toList()
            _scannerGroupsFlow.value = scannerGroups.toList()
            _ipArchivesFlow.value = ipArchives.toList()
        }
    }

    fun loadCloudGroups() {
        val jsonStr = prefs.getString("cloud_groups", "[]") ?: "[]"
        try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<CloudGroup>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val nodesArr = obj.getJSONArray("nodes")
                val nodesList = mutableListOf<VpnNode>()
                for (j in 0 until nodesArr.length()) {
                    val nObj = nodesArr.getJSONObject(j)
                    nodesList.add(
                        VpnNode(
                            id = nObj.getString("id"),
                            name = nObj.getString("name"),
                            uri = nObj.getString("uri"),
                            type = nObj.getString("type"),
                            ping = nObj.optString("ping", "Test"),
                            addedAt = nObj.optLong("addedAt", System.currentTimeMillis()),
                            engineType = nObj.optString("engineType", "BPB")
                        )
                    )
                }
                list.add(
                    CloudGroup(
                        id = obj.getString("id"),
                        accountId = obj.getString("accountId"),
                        date = obj.getString("date"),
                        title = obj.optString("title", "Cloud Group"),
                        nodes = nodesList
                    )
                )
            }
            cloudGroups.clear()
            cloudGroups.addAll(list)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveCloudGroups() {
        val arr = JSONArray()
        for (g in cloudGroups) {
            val obj = JSONObject()
            obj.put("id", g.id)
            obj.put("accountId", g.accountId)
            obj.put("date", g.date)
            obj.put("title", g.title)
            val nodesArr = JSONArray()
            for (n in g.nodes) {
                val nObj = JSONObject()
                nObj.put("id", n.id)
                nObj.put("name", n.name)
                nObj.put("uri", n.uri)
                nObj.put("type", n.type)
                nObj.put("ping", n.ping)
                nObj.put("addedAt", n.addedAt)
                nObj.put("engineType", n.engineType)
                nodesArr.put(nObj)
            }
            obj.put("nodes", nodesArr)
            arr.put(obj)
        }
        prefs.edit().putString("cloud_groups", arr.toString()).apply()
        _cloudGroupsFlow.value = cloudGroups.toList()
    }

    private fun loadScannerGroups() {
        val jsonStr = prefs.getString("scanner_groups", "[]") ?: "[]"
        try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<ScannerGroup>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val nodesArr = obj.getJSONArray("nodes")
                val nodesList = mutableListOf<VpnNode>()
                for (j in 0 until nodesArr.length()) {
                    val nObj = nodesArr.getJSONObject(j)
                    nodesList.add(
                        VpnNode(
                            id = nObj.getString("id"),
                            name = nObj.getString("name"),
                            uri = nObj.getString("uri"),
                            type = nObj.getString("type"),
                            ping = nObj.optString("ping", "Test"),
                            addedAt = nObj.optLong("addedAt", System.currentTimeMillis()),
                            engineType = nObj.optString("engineType", "BPB")
                        )
                    )
                }
                list.add(
                    ScannerGroup(
                        id = obj.getString("id"),
                        date = obj.getString("date"),
                        title = obj.optString("title", "Scanner Group"),
                        sourceGroupCount = obj.getInt("sourceGroupCount"),
                        ipCount = obj.getInt("ipCount"),
                        sourceGroupId = obj.optString("sourceGroupId", null).takeIf { it != "null" },
                        nodes = nodesList
                    )
                )
            }
            scannerGroups.clear()
            scannerGroups.addAll(list)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveScannerGroups() {
        val arr = JSONArray()
        for (g in scannerGroups) {
            val obj = JSONObject()
            obj.put("id", g.id)
            obj.put("date", g.date)
            obj.put("title", g.title)
            obj.put("sourceGroupCount", g.sourceGroupCount)
            obj.put("ipCount", g.ipCount)
            g.sourceGroupId?.let { obj.put("sourceGroupId", it) }
            val nodesArr = JSONArray()
            for (n in g.nodes) {
                val nObj = JSONObject()
                nObj.put("id", n.id)
                nObj.put("name", n.name)
                nObj.put("uri", n.uri)
                nObj.put("type", n.type)
                nObj.put("ping", n.ping)
                nObj.put("addedAt", n.addedAt)
                nObj.put("engineType", n.engineType)
                nodesArr.put(nObj)
            }
            obj.put("nodes", nodesArr)
            arr.put(obj)
        }
        prefs.edit().putString("scanner_groups", arr.toString()).apply()
        _scannerGroupsFlow.value = scannerGroups.toList()
    }

    fun loadIpArchives() {
        val jsonStr = prefs.getString("ip_archives", "[]") ?: "[]"
        try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<IpArchive>()
            val allIps = mutableSetOf<String>()
            
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val ipsArr = obj.getJSONArray("ips")
                for (j in 0 until ipsArr.length()) {
                    allIps.add(ipsArr.getString(j))
                }
            }
            
            if (allIps.isNotEmpty() || arr.length() == 0) { // Keep unified archive even if empty sometimes, but if empty we just don't add. 
                // Actually, if it's empty, we just have an empty list.
            }
            
            if (allIps.isNotEmpty()) {
                // Determine a date string (using today's date or a constant if we don't want it to constantly update on load, but today is fine for "last seen")
                val sdf = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US)
                val dateStr = sdf.format(java.util.Date())
                
                list.add(
                    IpArchive(
                        id = "unified_archive",
                        date = "Last updated: $dateStr",
                        ips = allIps.toList()
                    )
                )
            }
            
            ipArchives.clear()
            ipArchives.addAll(list)
            
            // If the original json had multiple archives, save the unified one back immediately
            if (arr.length() > 1) {
                saveIpArchives()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveIpArchives() {
        val arr = JSONArray()
        for (a in ipArchives) {
            val obj = JSONObject()
            obj.put("id", a.id)
            obj.put("date", a.date)
            val ipsArr = JSONArray()
            for (ip in a.ips) {
                ipsArr.put(ip)
            }
            obj.put("ips", ipsArr)
            arr.put(obj)
        }
        prefs.edit().putString("ip_archives", arr.toString()).apply()
        _ipArchivesFlow.value = ipArchives.toList()
    }
}
