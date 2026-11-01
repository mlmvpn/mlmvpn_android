package com.mlmvpn.scanner.data

import android.content.Context
import android.content.SharedPreferences
import com.mlmvpn.scanner.models.VpnNode
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.launch
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

class NodeManager private constructor(context: Context) {
    companion object {
        @Volatile
        private var instance: NodeManager? = null

        operator fun invoke(context: Context): NodeManager {
            return instance ?: synchronized(this) {
                instance ?: NodeManager(context.applicationContext).also { instance = it }
            }
        }

        /** Group the built-in Iran default configs live in. */
        // Deliberately NOT localised: this is the folder's identity on disk, not a label. Groups are
        // matched by name, so a value that changes with the language would orphan every folder a
        // user already has and quietly build a second one beside it.
        const val IRAN_GROUP = "کانفیگ‌های ایران"

        /**
         * A built-in Iran row's node id, from the row's own id rather than its position: a Store
         * update adds the new version's configs at the TOP and leaves the old ones untouched, so a
         * position-based id would hand every old row a new id. The original rows (`iran-1`…`iran-19`)
         * keep exactly the ids they always had.
         */
        fun iranNodeId(rowId: String, index: Int): String =
            "default_mlmvpn_" + rowId.removePrefix("iran-").ifBlank { "${index + 1}" }

        /** Built-in configs that must never be deletable by any UI path. */
        fun isProtected(node: VpnNode): Boolean = node.id.startsWith("default_mlmvpn_")
    }
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences = context.getSharedPreferences("vpn_nodes_prefs", Context.MODE_PRIVATE)
    
    /**
     * The list on disk: a file of its own, written and read one node at a time.
     *
     * It used to be one JSON string in the preferences, built whole at every save. With thousands
     * of configs that string ran to tens of megabytes -- the save itself ran the app out of memory
     * (OutOfMemoryError in saveNodes, 1.2.41), and the preferences kept a second copy of it in
     * memory for as long as the app ran. A list still in the preferences is read from there once
     * and moved here by the first save.
     */
    private val nodesFile = java.io.File(appContext.filesDir, "nodes.json")
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "nodes-writer").apply { isDaemon = true }
    }
    /** The newest list waiting to be written; a burst of saves is written once. */
    private val pendingWrite = java.util.concurrent.atomic.AtomicReference<List<VpnNode>?>(null)
    // Declared before `init`: the loader it starts reads these on another thread at once.

    var nodes: MutableList<VpnNode> = java.util.Collections.synchronizedList(mutableListOf<VpnNode>())
        private set

    private val _nodesFlow = kotlinx.coroutines.flow.MutableStateFlow<List<VpnNode>>(emptyList())
    val nodesFlow: kotlinx.coroutines.flow.StateFlow<List<VpnNode>> = _nodesFlow

    /** Released once the stored list has been read in (or failed to be); see [awaitLoaded]. */
    private val loaded = java.util.concurrent.CountDownLatch(1)

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                loadNodes()
                _nodesFlow.value = synchronized(nodes) { nodes.toList() }
            } finally {
                loaded.countDown()
            }
        }
    }

    /**
     * Blocks (off the main thread) until the stored list has been read, at most [timeoutMs].
     *
     * The first read is asynchronous. A caller that adds to [nodes] and saves before it lands
     * writes a list holding only its own rows and the built-ins -- every other config the user had
     * is gone from disk -- and the late read then drops the caller's rows in memory. Seen
     * 2026-10-10 with domain fronting's setup on a cold start. False on timeout.
     */
    fun awaitLoaded(timeoutMs: Long = 15_000): Boolean =
        loaded.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

    private fun persist(snapshot: List<VpnNode>) {
        if (pendingWrite.getAndSet(snapshot) != null) return
        writer.execute {
            val list = pendingWrite.getAndSet(null) ?: return@execute
            try {
                writeFile(list)
                if (prefs.contains("nodes_list")) prefs.edit().remove("nodes_list").apply()
            } catch (t: Throwable) {
                android.util.Log.e("NodeManager", "could not write the node list: ${t.message}")
            }
        }
    }

    private fun writeFile(list: List<VpnNode>) {
        val temp = java.io.File(nodesFile.path + ".part")
        java.io.OutputStreamWriter(java.io.BufferedOutputStream(java.io.FileOutputStream(temp)), Charsets.UTF_8).use { out ->
            val w = android.util.JsonWriter(out)
            w.beginArray()
            for (node in list) {
                w.beginObject()
                w.name("id").value(node.id)
                w.name("name").value(node.name)
                w.name("uri").value(node.uri)
                w.name("type").value(node.type)
                w.name("ping").value(node.ping)
                w.name("delay").value(node.delay)
                w.name("speed").value(node.speed)
                w.name("addedAt").value(node.addedAt)
                w.name("engineType").value(node.engineType)
                val cc = node.countryCode
                if (cc != null) w.name("countryCode").value(cc) else w.name("countryCode").nullValue()
                val gt = node.groupTitle
                if (gt != null) w.name("groupTitle").value(gt) else w.name("groupTitle").nullValue()
                w.endObject()
            }
            w.endArray()
            w.flush()
        }
        // The previous list is kept one write back: a file that cannot be read still leaves the
        // user's configs, rather than an empty list that the next save would make permanent.
        val backup = java.io.File(nodesFile.path + ".bak")
        if (nodesFile.exists()) {
            backup.delete()
            nodesFile.renameTo(backup)
        }
        if (!temp.renameTo(nodesFile)) {
            backup.renameTo(nodesFile)
            error("rename failed")
        }
    }

    private fun readFile(): List<VpnNode> = try {
        readFile(nodesFile)
    } catch (e: Exception) {
        android.util.Log.e("NodeManager", "node list unreadable, using the copy before it: ${e.message}")
        readFile(java.io.File(nodesFile.path + ".bak"))
    }

    private fun readFile(source: java.io.File): List<VpnNode> {
        val list = ArrayList<VpnNode>()
        java.io.InputStreamReader(java.io.BufferedInputStream(java.io.FileInputStream(source)), Charsets.UTF_8).use { input ->
            val r = android.util.JsonReader(input)
            r.beginArray()
            while (r.hasNext()) {
                var id: String? = null
                var name: String? = null
                var uri: String? = null
                var type: String? = null
                var ping = "Test"
                var delay = "Test"
                var speed = "Test"
                var addedAt = System.currentTimeMillis()
                var engineType = "BPB"
                var countryCode: String? = null
                var groupTitle: String? = null
                r.beginObject()
                while (r.hasNext()) {
                    val key = r.nextName()
                    if (r.peek() == android.util.JsonToken.NULL) { r.nextNull(); continue }
                    when (key) {
                        "id" -> id = r.nextString()
                        "name" -> name = r.nextString()
                        "uri" -> uri = r.nextString()
                        "type" -> type = r.nextString()
                        "ping" -> ping = r.nextString()
                        "delay" -> delay = r.nextString()
                        "speed" -> speed = r.nextString()
                        "addedAt" -> addedAt = r.nextLong()
                        "engineType" -> engineType = r.nextString()
                        "countryCode" -> countryCode = r.nextString()
                        "groupTitle" -> groupTitle = r.nextString().let { g -> if (g == S(R.string.default_str_2)) null else g }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (id != null && name != null && uri != null && type != null) {
                    list.add(VpnNode(id = id, name = name, uri = uri, type = type, ping = ping, delay = delay, speed = speed,
                        addedAt = addedAt, engineType = engineType, countryCode = countryCode, groupTitle = groupTitle))
                }
            }
            r.endArray()
        }
        return list
    }

    /** The stored list, read without waiting on the in-memory one: for a caller that needs it before the load. */
    fun storedNodes(): List<VpnNode> = runCatching {
        if (nodesFile.exists() || java.io.File(nodesFile.path + ".bak").exists()) readFile() else readPrefs()
    }.getOrDefault(emptyList())

    /** The old home of the list (see [nodesFile]). */
    private fun readPrefs(): List<VpnNode> {
        val jsonStr = prefs.getString("nodes_list", "[]") ?: "[]"
        val arr = JSONArray(jsonStr)
        val list = mutableListOf<VpnNode>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                VpnNode(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    uri = obj.getString("uri"),
                    type = obj.getString("type"),
                    ping = obj.optString("ping", "Test"),
                    delay = obj.optString("delay", "Test"),
                    speed = obj.optString("speed", "Test"),
                    addedAt = obj.optLong("addedAt", System.currentTimeMillis()),
                    engineType = obj.optString("engineType", "BPB"),
                    countryCode = if (obj.has("countryCode") && !obj.isNull("countryCode")) obj.getString("countryCode") else null,
                    groupTitle = if (obj.has("groupTitle") && !obj.isNull("groupTitle")) {
                        val gt = obj.getString("groupTitle")
                        if (gt == S(R.string.default_str_2)) null else gt
                    } else null
                )
            )
        }
        return list
    }

    private fun loadNodes() {
        try {
            val list = if (nodesFile.exists() || java.io.File(nodesFile.path + ".bak").exists()) readFile() else readPrefs()
            synchronized(nodes) {
                nodes.clear()
                // Deduplicated on the way in, because a shipped bug wrote duplicates to this file
                // and they are still there. The delay/speed test used to write its result by list
                // POSITION while the list was moving underneath it, so one node's result landed on
                // another node's row and both rows ended up carrying the same id -- which
                // LazyColumn throws on, crashing the V2Ray screen on every launch for anyone whose
                // stored list had been corrupted. Fixing the writer stops it happening again; this
                // is what repairs the users it already happened to, and the next save writes the
                // repaired list back.
                nodes.addAll(list.distinctBy { it.id })
                injectDefaultConfigs()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Replace the whole list, atomically.
     *
     * The only correct way to do what a dozen call sites used to do by hand:
     *
     *     nodeManager.nodes.clear()
     *     nodeManager.nodes.addAll(list)
     *     nodeManager.saveNodes()
     *
     * `nodes` is a synchronized list, so each of those calls is individually safe and the PAIR is
     * not. Two of them racing -- and they do race, because per-node measurement results, the bulk
     * test's final write and the SNI auto-rename all write from IO threads while the screen writes
     * from the main one -- interleave as clear, clear, addAll(A), addAll(B), and the list then
     * holds every node TWICE with the same id. That is the crash that leads the fleet:
     * `IllegalArgumentException: Key "<uuid>" was already used`, thrown by LazyColumn on its next
     * measure pass, on 1.2.31 and 1.2.32 alike.
     */
    fun replaceAll(newNodes: List<VpnNode>) {
        synchronized(nodes) {
            nodes.clear()
            nodes.addAll(newNodes)
        }
        saveNodes()
    }

    /**
     * Write these rows back BY ID and leave every other row exactly as it is.
     *
     * What a measurement actually means. A delay test learns something about the configs it
     * measured and nothing whatsoever about the rest, but the old code expressed the result by
     * replacing the entire stored list with the screen's snapshot -- so anything added while the
     * test was running was erased by it. A bulk test takes minutes, and building SNI configs on
     * another screen takes seconds; the user built 180 of them, came back, and the connection
     * screen's next write-back deleted the lot.
     *
     * An id that is not in the list is IGNORED rather than added. If a config was deleted while a
     * measurement was in flight, its result must not bring it back from the dead.
     */
    fun mergeById(updated: List<VpnNode>) {
        if (updated.isEmpty()) return
        val byId = updated.associateBy { it.id }
        var touched = false
        synchronized(nodes) {
            for (i in nodes.indices) {
                val replacement = byId[nodes[i].id] ?: continue
                nodes[i] = replacement
                touched = true
            }
        }
        if (touched) saveNodes()
    }

    /** Remove these ids, atomically. Built-in configs never go, whatever is asked. */
    fun removeByIds(victims: Set<String>) {
        if (victims.isEmpty()) return
        val removed = synchronized(nodes) {
            nodes.removeAll { it.id in victims && !isProtected(it) }
        }
        if (removed) saveNodes()
    }

    fun saveNodes() {
        // Bulletproof protection: the built-in Iran configs must survive every deletion
        // path (delete-all button, per-node delete, group delete). No matter what a caller
        // removed from `nodes`, re-inject them here so they are always persisted and always
        // re-emitted on nodesFlow.
        val snapshot = synchronized(nodes) {
            injectDefaultConfigs()
            // A duplicate id must never reach the disk.
            //
            // The writes above are atomic now, but this is the last gate and it is worth keeping:
            // one duplicate is a crash the user cannot get past, because LazyColumn throws while
            // MEASURING the list, so the screen that would let them fix it is the screen that
            // will not open. It was also a silent data loss -- `loadNodes` de-duplicates by id, so
            // whichever copy came second was quietly dropped on the next launch.
            //
            // Dropping the duplicate here rather than there means the list on disk is correct, so
            // nothing has to be dropped on the way back in.
            val seen = HashSet<String>(nodes.size)
            val unique = nodes.filter { seen.add(it.id) }
            if (unique.size != nodes.size) {
                nodes.clear()
                nodes.addAll(unique)
            }
            unique
        }
        // Written on a thread of its own, a node at a time (see [nodesFile]).
        persist(snapshot)
        _nodesFlow.value = snapshot
    }

    fun removeNodesByGroup(groupId: String) {
        synchronized(nodes) {
            nodes.removeAll { it.engineType == "Manual" && it.groupTitle == groupId && !isProtected(it) }
        }
        saveNodes()
    }

    /**
     * Put already-built nodes into a group, replacing nothing.
     *
     * [addConfigs] cannot serve a JSON subscription: it takes share LINKS and works out the type
     * from the `vless://`-style prefix, and a JSON config has neither. The nodes are built by the
     * importer that understands the document; this only files them.
     */
    fun addNodes(newNodes: List<VpnNode>, groupTitle: String? = null): Int {
        if (newNodes.isEmpty()) return 0
        // The duplicate check runs outside the lock: with tens of thousands of configs it held
        // the list long enough for the screen, waiting to read its size, to freeze (ANR #721).
        val existing = synchronized(nodes) { nodes.mapTo(HashSet(nodes.size * 2)) { it.uri } }
        val fresh = newNodes.filter { existing.add(it.uri) }
        fresh.forEach { it.groupTitle = groupTitle }
        val added: Int
        synchronized(nodes) {
            nodes.addAll(fresh)
            // What was ADDED, not what was offered. The caller puts this number in front of the
            // user ("added N configs"), and counting the input would report work that a duplicate
            // check had just decided not to do.
            added = fresh.size
        }
        if (added > 0) saveNodes()
        return added
    }

    fun addConfigs(configs: List<String>, groupTitle: String? = null): Int {
        var addedCount = 0
        synchronized(nodes) {
            for (config in configs) {
                if (config.isBlank()) continue
                val type = if (config.startsWith("vless://")) "VLESS"
                           else if (config.startsWith("trojan://")) "TROJAN"
                           else "UNKNOWN"

                // Extract name from fragment (#Name)
                var name = "Node ${nodes.size + 1}"
                val hashIdx = config.lastIndexOf("#")
                if (hashIdx != -1) {
                    try {
                        name = java.net.URLDecoder.decode(config.substring(hashIdx + 1), "UTF-8")
                    } catch(e: Exception) {}
                }

                // Prevent duplicates within the same group
                if (nodes.any { it.uri == config && it.groupTitle == groupTitle }) continue

                nodes.add(
                    VpnNode(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        uri = config,
                        type = type,
                        engineType = "Manual",
                        groupTitle = groupTitle
                    )
                )
                addedCount++
            }
        }

        if (addedCount > 0) {
            saveNodes()
        }
        return addedCount
    }


    fun clearAll() {
        synchronized(nodes) {
            nodes.clear()
            injectDefaultConfigs()
        }
        saveNodes()
    }

    // Caller must hold `synchronized(nodes)` already; not synchronized internally to avoid re-entrant lock churn.
    /**
     * The built-in «کانفیگ‌های ایران», from one generated asset.
     *
     * These reach the internet with NO server: they fragment the TLS ClientHello to defeat SNI
     * inspection and resolve names over DoH. TWO things therefore decide whether one works on a
     * given line, and they fail independently:
     *
     *   1. the RESOLVER — can this network reach that DoH endpoint at all? Measured on one
     *      Iranian mobile line (2026-09-12): Google 8.8.8.8 answered in 493 ms and AdGuard in
     *      1036 ms, while Cloudflare 1.1.1.1 was reset after 11 s and the upstream default
     *      `cloudflare-dns.com` never answered. With no resolver NOT ONE NAME RESOLVES, so every
     *      site fails and it reads as "the config does not connect" even when the rest is fine.
     *   2. the FRAGMENT SHAPE — upstream ships four across two releases, and one that does not
     *      suit the line does not merely fail to help: v50's profile could not open
     *      www.cloudflare.com at all (22 s), while the same config without it answered in 3.9 s.
     *
     * So the list is every combination, and `assets/iran_profiles.json` is generated from the
     * upstream files by `scripts/gen-iran-profiles.js` — the same generator the desktop app uses,
     * so the two cannot drift. Its JSON surgery carries invariants that throw when upstream moves
     * (exactly one no-filter-dns server, exactly one route for it, at least two catch-all
     * fragment routes); re-doing that as Kotlin string replacement would be the same work with
     * none of the checks.
     *
     * If the asset is missing or unreadable nothing is injected rather than half a list — a row
     * that cannot connect is worse than a row that is not there.
     */
    private fun injectDefaultConfigs() {
        nodes.removeAll { it.id.startsWith("default_mlmvpn_") }

        val raw = try {
            com.mlmvpn.scanner.store.StoreFiles.open(appContext, "iran_profiles.json").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return
        }

        val arr = try { JSONArray(raw) } catch (_: Exception) { return }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val config = o.optString("config", "")
            if (config.isEmpty()) continue
            nodes.add(
                i,
                VpnNode(
                    id = iranNodeId(o.optString("id"), i),
                    name = o.optString("name", "کانفیگ ایران ${i + 1}"),
                    uri = config,
                    type = "JSON",
                    engineType = "Manual",
                    groupTitle = IRAN_GROUP
                )
            )
        }
    }

}
