package com.mlmvpn.scanner.engines.windscribe

import android.content.Context
import com.mlmvpn.scanner.openvpn.OpenVpnVault
import com.mlmvpn.scanner.engines.windscribe.WsContract.objects
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class WsData(val accounts: List<WsAccount> = emptyList(), val activeId: String? = null,
    val servers: List<WsServer> = emptyList(), val ports: Map<WsProtocol, List<Int>> = emptyMap(),
    val serverCheckedAt: Long = 0, val selectedServer: String? = null, val protocol: WsProtocol? = null,
    val storageError: Boolean = false) {
    val active get() = accounts.firstOrNull { it.id == activeId }
}

/** Reuses the existing authenticated AES-GCM/Keystore vault in noBackupFilesDir. No plaintext fallbacks. */
internal class WsRepository(context: Context) {
    private val vault = OpenVpnVault(context)
    private var root = JSONObject().put("accounts", JSONArray())
    private val mutable = MutableStateFlow(WsData())
    val data = mutable.asStateFlow()
    init {
        try {
            vault.read("windscribe")?.let { root = JSONObject(it) }
            publish()
        } catch (_: Exception) { mutable.value = WsData(storageError = true) }
    }
    private fun account(id: String): JSONObject = root.getJSONArray("accounts").objects().firstOrNull { it.optString("user_id") == id }
        ?: throw WsException(WsError.AUTH)
    @Synchronized fun token(id: String): String = account(id).optString("session_auth_hash").takeIf { it.isNotBlank() }
        ?: throw WsException(WsError.SESSION_EXPIRED)
    @Synchronized fun secret(id: String, key: String): String? = account(id).optString(key).takeIf { it.isNotBlank() }
    @Synchronized fun putSecret(id: String, key: String, value: String) = change { account(id).put(key, value) }
    @Synchronized fun login(raw: JSONObject) = change {
        val id = raw.optString("user_id")
        if (id.isBlank() || raw.optString("session_auth_hash").isBlank()) throw WsException(WsError.CONFIG)
        val previous = root.getJSONArray("accounts").objects().firstOrNull { it.optString("user_id") == id }
        val stored = previous?.let { JSONObject(it.toString()) } ?: JSONObject()
        raw.keys().forEach { key -> stored.put(key, raw.get(key)) }
        stored.put("checkedAt", System.currentTimeMillis()).put("sessionValid", true)
        root.put("accounts", JSONArray(root.getJSONArray("accounts").objects().filterNot { it.optString("user_id") == id } + stored))
        root.put("active", id).remove("selectedServer")
    }
    @Synchronized fun update(id: String, raw: JSONObject) = change {
        val stored = account(id)
        raw.keys().forEach { key -> stored.put(key, raw.get(key)) }
        stored.put("checkedAt", System.currentTimeMillis()).put("sessionValid", true)
    }
    @Synchronized fun invalidate(id: String) = change { account(id).put("sessionValid", false).remove("session_auth_hash") }
    @Synchronized fun remove(id: String) = change {
        root.put("accounts", JSONArray(root.getJSONArray("accounts").objects().filterNot { it.optString("user_id") == id }))
        if (root.optString("active") == id) root.remove("active")
        root.remove("selectedServer")
    }
    @Synchronized fun selectAccount(id: String) = change { account(id); root.put("active", id).remove("selectedServer") }
    @Synchronized fun selection(serverId: String?, protocol: WsProtocol?) = change {
        root.put("selectedServer", serverId).put("protocol", protocol?.name)
    }
    @Synchronized fun cache(id: String, locations: JSONObject, servers: JSONObject, ports: JSONObject) = change {
        account(id).put("locations", locations).put("inventory", servers).put("ports", ports).put("serverCheckedAt", System.currentTimeMillis())
    }
    @Synchronized fun alc(id: String): Set<String> = account(id).optJSONArray("alc")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
    @Synchronized fun preferred(id: String, network: String): WsMethod? = account(id).optJSONObject("best")?.optJSONObject(network)?.let(::methodFromJson)
    @Synchronized fun remember(id: String, network: String, method: WsMethod?) = change {
        val best = account(id).optJSONObject("best") ?: JSONObject().also { account(id).put("best", it) }
        if (method == null) best.remove(network) else best.put(network, methodJson(method))
    }
    @Synchronized fun apiMode(network: String): Pair<Int, Boolean>? = root.optJSONObject("apiModes")?.optJSONObject(network)?.let { it.optInt("backup", -1) to it.optBoolean("padding", true) }
    @Synchronized fun saveApiMode(network: String, backup: Int, padding: Boolean) = change {
        val modes = root.optJSONObject("apiModes") ?: JSONObject().also { root.put("apiModes", it) }
        modes.put(network, JSONObject().put("backup", backup).put("padding", padding))
    }
    @Synchronized fun nativeSettings(): String? = root.optString("wsnet").takeIf { it.isNotBlank() }
    @Synchronized fun installationId(): String {
        root.optString("installationId").takeIf { it.isNotBlank() }?.let { return it }
        val id = UUID.randomUUID().toString()
        change { root.put("installationId", id) }
        return id
    }
    @Synchronized fun measurements(id: String, network: String): List<WsMeasurement> =
        account(id).optJSONObject("measurements")?.optJSONArray(network)?.objects()?.mapNotNull { j ->
            val method = j.optJSONObject("method")?.let(::methodFromJson) ?: return@mapNotNull null
            WsMeasurement(method, j.optLong("connectMs"), if (j.isNull("latencyMs")) null else j.optLong("latencyMs"),
                if (j.isNull("downloadBps")) null else j.optLong("downloadBps"), j.optInt("successfulSamples"), j.optInt("samples"), j.optLong("checkedAt"))
        }.orEmpty()
    @Synchronized fun saveMeasurements(id: String, network: String, results: List<WsMeasurement>) = change {
        val history = account(id).optJSONObject("measurements") ?: JSONObject().also { account(id).put("measurements", it) }
        history.put(network, JSONArray(results.takeLast(200).map { m -> JSONObject().put("method", methodJson(m.method))
            .put("connectMs", m.connectMs).put("latencyMs", m.latencyMs).put("downloadBps", m.downloadBps)
            .put("successfulSamples", m.successfulSamples).put("samples", m.samples).put("checkedAt", m.checkedAt) }))
    }
    @Synchronized fun saveNativeSettings(raw: String) = change {
        // Only the official failover route survives. API resources/tokens remain scoped to their accounts here.
        val settings = JSONObject(raw)
        val route = JSONObject().put("version", settings.opt("version"))
        listOf("flvId", "countryOverride").forEach { if (settings.has(it)) route.put(it, settings.get(it)) }
        root.put("wsnet", route.toString())
    }
    private fun change(block: () -> Unit) {
        if (mutable.value.storageError) throw WsException(WsError.STORAGE)
        val old = root.toString()
        try { block(); vault.write("windscribe", root.toString()); publish() }
        catch (e: Exception) { root = JSONObject(old); throw if (e is WsException) e else WsException(WsError.STORAGE) }
    }
    private fun publish() {
        val accounts = root.getJSONArray("accounts").objects().map { WsContract.account(it, it.optLong("checkedAt")) }
        val activeId = root.optString("active").takeIf { it.isNotBlank() }
        val raw = activeId?.let { account(it) }
        val active = accounts.firstOrNull { it.id == activeId }
        val servers = if (raw?.optJSONObject("locations") != null && raw.optJSONObject("inventory") != null && active != null)
            WsContract.servers(raw.getJSONObject("locations").toString(), raw.getJSONObject("inventory").toString(), active.premium, alc(active.id)) else emptyList()
        val ports = raw?.optJSONObject("ports")?.let { WsContract.ports(it) }.orEmpty()
        mutable.value = WsData(accounts, activeId, servers, ports, raw?.optLong("serverCheckedAt") ?: 0,
            root.optString("selectedServer").takeIf { id -> servers.any { it.id == id } },
            root.optString("protocol").takeIf { it.isNotBlank() }?.let { runCatching { WsProtocol.valueOf(it) }.getOrNull() })
    }
    companion object {
        fun methodJson(m: WsMethod) = JSONObject().put("server", m.serverId).put("protocol", m.protocol.name).put("port", m.port).put("padding", m.padding).put("cf", m.cf)
        fun methodFromJson(j: JSONObject): WsMethod? = runCatching { WsMethod(j.getString("server"), WsProtocol.valueOf(j.getString("protocol")), j.getInt("port"), j.optBoolean("padding"), j.optBoolean("cf")) }.getOrNull()
    }
}
