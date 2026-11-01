package com.mlmvpn.scanner.openvpn

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class OpenVpnData(
    val accounts: List<Account> = emptyList(), val profiles: List<Profile> = emptyList(),
    val activeAccount: String? = null, val selectedProfile: String? = null,
    val autoSwitch: Boolean = false, val storageError: Boolean = false,
)

class OpenVpnRepository private constructor(private val context: Context) {
    private val vault = OpenVpnVault(context)
    private val mutable = MutableStateFlow(OpenVpnData())
    val data = mutable.asStateFlow()
    init {
        try {
            val stored = vault.read("catalog")
            if (stored != null) mutable.value = decode(JSONObject(stored))
            else {
                val ca = context.assets.open("openvpn/openvpn-server-ca.crt").bufferedReader().use { it.readText() }
                val profiles = context.assets.list("openvpn").orEmpty().filter { it.endsWith(".ovpn") || it.endsWith(".ovpn.txt") }.map { name ->
                    val text = context.assets.open("openvpn/$name").bufferedReader().use { it.readText() }
                    ProfileImporter.parse(name, text, mapOf("openvpn-server-ca.crt" to ca))
                }
                save(OpenVpnData(profiles = profiles, selectedProfile = profiles.firstOrNull()?.id))
            }
        } catch (_: Exception) { mutable.value = OpenVpnData(storageError = true) }
    }

    @Synchronized private fun save(next: OpenVpnData) {
        check(!mutable.value.storageError) { "Encrypted storage unavailable" }
        vault.write("catalog", encode(next).toString())
        mutable.value = next
    }
    @Synchronized fun saveAccount(id: String?, username: String, password: String) {
        check(!mutable.value.storageError)
        check(id == null || OpenVpnRuntime.connection.value.accountId != id || !OpenVpnRuntime.connection.value.active)
        require(username.isNotBlank() && username.length <= 320 && password.isNotEmpty() && password.length <= 4096)
        require(!username.contains('\n') && !username.contains('\r') && !username.contains('\u0000'))
        val old = id?.let { value -> mutable.value.accounts.firstOrNull { it.id == value } }
        val key = old?.id ?: UUID.randomUUID().toString()
        val reference = UUID.randomUUID().toString()
        vault.write("credential_$reference", password)
        val account = Account(key, username.trim(), credentialRef = reference)
        try {
            save(mutable.value.copy(accounts = mutable.value.accounts.filterNot { it.id == key } + account,
                activeAccount = mutable.value.activeAccount ?: key))
        } catch (e: Exception) {
            runCatching { vault.remove("credential_$reference") }
            throw e
        }
        old?.let { runCatching { vault.remove("credential_${it.credentialRef}") } }
    }
    @Synchronized fun removeAccount(id: String) {
        check(OpenVpnRuntime.connection.value.accountId != id || !OpenVpnRuntime.connection.value.active)
        val account = mutable.value.accounts.firstOrNull { it.id == id } ?: return
        save(mutable.value.copy(accounts = mutable.value.accounts.filterNot { it.id == id },
            activeAccount = mutable.value.activeAccount.takeUnless { it == id }))
        vault.remove("credential_${account.credentialRef}")
    }
    @Synchronized fun password(id: String): String {
        val account = mutable.value.accounts.firstOrNull { it.id == id } ?: error("Account unavailable")
        return vault.read("credential_${account.credentialRef}") ?: error("Credential unavailable")
    }
    @Synchronized fun selectAccount(id: String) {
        require(mutable.value.accounts.any { it.id == id && it.usable(System.currentTimeMillis()) })
        save(mutable.value.copy(activeAccount = id))
    }
    @Synchronized fun selectProfile(id: String) {
        require(mutable.value.profiles.any { it.id == id })
        save(mutable.value.copy(selectedProfile = id))
    }
    @Synchronized fun autoSwitch(enabled: Boolean) { save(mutable.value.copy(autoSwitch = enabled)) }
    @Synchronized fun updateAccount(id: String, update: (Account) -> Account) {
        save(mutable.value.copy(accounts = mutable.value.accounts.map { if (it.id == id) update(it) else it }))
    }
    fun refreshAccount(id: String) {
        // No documented provider API: keep the last known snapshot, clearly timestamped.
        updateAccount(id) { it.copy(lastChecked = System.currentTimeMillis(), lastError = "USAGE_UNAVAILABLE") }
    }
    @Synchronized fun importFiles(files: Map<String, String>): Pair<Int, List<String>> {
        val companions = JSONObject(vault.read("companions") ?: "{}")
        files.filterKeys { !it.endsWith(".ovpn", true) && !it.endsWith(".ovpn.txt", true) }.forEach { (name, content) ->
            require(name.length <= 180 && content.toByteArray().size <= ProfileImporter.MAX_BYTES)
            companions.put(name, content)
        }
        val available = companions.keys().asSequence().associateWith { companions.getString(it) }
        var count = 0
        val errors = mutableListOf<String>()
        val profiles = mutable.value.profiles.toMutableList()
        files.filterKeys { it.endsWith(".ovpn", true) || it.endsWith(".ovpn.txt", true) }.forEach { (name, text) ->
            try {
                val p = ProfileImporter.parse(name, text, available)
                if (profiles.none { it.id == p.id }) { profiles += p; count++ }
            } catch (e: ProfileImportException) { errors += "$name: ${e.message}" }
        }
        vault.write("companions", companions.toString())
        save(mutable.value.copy(profiles = profiles, selectedProfile = mutable.value.selectedProfile ?: profiles.firstOrNull()?.id))
        return count to errors
    }
    @Synchronized fun updateProfile(id: String, change: (Profile) -> Profile) {
        save(mutable.value.copy(profiles = mutable.value.profiles.map { if (it.id == id) change(it) else it }))
    }
    @Synchronized fun putProviderProfile(profile: Profile) {
        require(profile.id.startsWith("windscribe_"))
        check(OpenVpnRuntime.connection.value.profileId != profile.id || !OpenVpnRuntime.connection.value.active)
        save(mutable.value.copy(profiles = mutable.value.profiles.filterNot { it.id == profile.id } + profile))
    }
    @Synchronized fun removeProfile(id: String) {
        check(OpenVpnRuntime.connection.value.profileId != id || !OpenVpnRuntime.connection.value.active)
        save(mutable.value.copy(profiles = mutable.value.profiles.filterNot { it.id == id },
            selectedProfile = mutable.value.selectedProfile.takeUnless { it == id }))
    }

    private fun encode(d: OpenVpnData) = JSONObject().apply {
        put("version", 1); put("active", d.activeAccount); put("selected", d.selectedProfile); put("auto", d.autoSwitch)
        put("accounts", JSONArray(d.accounts.map { a -> JSONObject().apply {
            put("id", a.id); put("username", a.username); put("auth", a.auth.name)
            put("credentialRef", a.credentialRef)
            put("checked", a.lastChecked); put("connected", a.lastConnected); put("unavailable", a.unavailableUntil); put("error", a.lastError)
            a.usage?.let { put("usage", JSONObject().put("total", it.total).put("used", it.used).put("checked", it.checkedAt).put("reset", it.resetAt)) }
        } }))
        put("profiles", JSONArray(d.profiles.map { p -> JSONObject().apply {
            put("name", p.name); put("config", p.config); put("favorite", p.favorite)
            if (p.id.startsWith("windscribe_")) { put("providerId", p.id); put("splitTcp", p.splitTcp) }
            p.wsProxy?.let { proxy -> put("wsProxy", JSONObject().put("host", proxy.host).put("port", proxy.port)
                .put("webSocket", proxy.webSocket).put("padding", proxy.padding).put("sni", proxy.sni)) }
            p.probe?.let { put("probe", JSONObject().put("ms", it.millis).put("checked", it.checkedAt).put("method", it.method).put("error", it.error).put("ips", JSONArray(it.healthy))) }
        } }))
    }
    private fun decode(j: JSONObject): OpenVpnData {
        require(j.getInt("version") == 1)
        val accounts = j.getJSONArray("accounts").objects().map { a ->
            Account(a.getString("id"), a.getString("username"), AuthState.valueOf(a.getString("auth")),
                a.optJSONObject("usage")?.let { ProviderUsage(it.getLong("total"), it.getLong("used"), it.getLong("checked"), it.longOrNull("reset")) },
                a.longOrNull("checked"), a.longOrNull("connected"), a.optLong("unavailable"), a.stringOrNull("error"),
                a.optString("credentialRef", a.getString("id")))
        }
        val profiles = j.getJSONArray("profiles").objects().map { p ->
            var profile = ProfileImporter.parse(p.getString("name"), p.getString("config"), emptyMap()).withFavorite(p.optBoolean("favorite"))
            p.optString("providerId").takeIf { it.startsWith("windscribe_") }?.let { id ->
                val proxy = p.optJSONObject("wsProxy")?.let { j -> WsProxy(j.getString("host"), j.getInt("port"), j.getBoolean("webSocket"), j.optBoolean("padding"), j.optString("sni")) }
                profile = Profile(id, profile.name, profile.config, profile.remotes, profile.authenticatedControl, profile.favorite, splitTcp = p.optBoolean("splitTcp"), wsProxy = proxy)
            }
            p.optJSONObject("probe")?.let {
                val ips = it.optJSONArray("ips")?.let { a -> (0 until a.length()).map { i -> a.getString(i) } }.orEmpty()
                profile = profile.withProbe(ProbeResult(it.longOrNull("ms"), it.getLong("checked"), it.getString("method"), it.stringOrNull("error"), ips))
            }
            profile
        }
        return OpenVpnData(accounts, profiles, j.stringOrNull("active"), j.stringOrNull("selected"), j.optBoolean("auto"))
    }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.stringOrNull(key: String) = if (isNull(key)) null else getString(key)
    private fun JSONObject.longOrNull(key: String) = if (isNull(key)) null else getLong(key)

    companion object {
        @Volatile private var instance: OpenVpnRepository? = null
        fun get(context: Context): OpenVpnRepository = instance ?: synchronized(this) {
            instance ?: OpenVpnRepository(context.applicationContext).also { instance = it }
        }
    }
}
