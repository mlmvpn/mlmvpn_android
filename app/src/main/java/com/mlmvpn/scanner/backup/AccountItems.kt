package com.mlmvpn.scanner.backup

import android.content.Context
import com.mlmvpn.scanner.store.tr
import org.json.JSONArray
import org.json.JSONObject

/**
 * «حساب‌ها و کانفیگ‌ها»: everything the user signed in to, made, added or was given.
 *
 * Secrets travel opened (see [Seals]) and are sealed again with the new phone's Keystore in [plan].
 * Lists merge by what identifies an entry to the user -- a Cloudflare account by its account id, a
 * GitHub account by its login, an email by itself -- so restoring onto a phone that already has the
 * same account replaces it instead of listing it twice.
 *
 * Left out on purpose: GitHub Tunnel's running sessions (they end within hours), Proton's and
 * Firefox's sign-in pages (a browser session, not data), Lantern's device profile (two phones with
 * one id get mixed up), and the shared pool's install id (one per install, by design).
 */
internal object AccountItems {

    val all: List<BackupContributor> = listOf(
        Cloudflare, Deploys, WarpIdentities, Geph, Urnetwork, ZeroTrust, Github, Fenix, Windscribe, OpenVpn, Proton,
        V2ray, ScannerGroups, QuickSaved, OutlineKeys, AmneziaConfigs, FluxLinks, TgProxies, GstRelays,
        Conduit, Certificates, MaeIdentity,
    )

    // ── Cloudflare ─────────────────────────────────────────────────────────────────────────────

    /** The account list, sealed as one JSON array; Config Studio's fields live on each account. */
    private object Cloudflare : BackupContributor {
        override val id = "acc_cloudflare"
        override val kind = BackupKind.ACCOUNTS
        override fun title() = tr("حساب‌های کلادفلر (همراه کانفیگ استدیو)", "Cloudflare accounts (with Config Studio)")

        private const val PREFS = "cloud_accounts_prefs"

        private fun current(ctx: Context): List<JSONObject> {
            val p = prefsOf(ctx, PREFS)
            val json = p.getString("accounts_sealed", null)?.let(Seals::open) ?: p.getString("accounts_list", null) ?: return emptyList()
            return JSONArray(json).objects()
        }

        override fun export(ctx: Context): JSONObject? {
            val list = current(ctx)
            if (list.isEmpty()) return null
            return JSONObject().put("accounts", JSONArray(list))
                .put("verify_reset", prefsOf(ctx, PREFS).getBoolean("verify_verdicts_reset_v1", false))
        }

        override fun summary(data: JSONObject) = countLabel(data.optJSONArray("accounts")?.length() ?: 0, "حساب", "accounts")

        private fun key(o: JSONObject): String? =
            o.optString("accountId").takeIf { it.isNotBlank() }?.let { "a:$it" } ?: o.optString("id").takeIf { it.isNotBlank() }?.let { "i:$it" }

        override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
            val backup = data.getJSONArray("accounts").objects()
            // The fields CloudManager reads with getString: an entry without them would stop the whole list loading.
            for (o in backup) for (k in listOf("id", "token", "email", "name", "accountId", "status", "addedAt")) {
                require(o.has(k)) { "account without $k" }
            }
            val merged = if (mode == RestoreMode.MERGE) mergeById(current(ctx), backup, ::key) else backup
            val sealed = Seals.seal(JSONArray(merged).toString())
            plan.prefs(PREFS) {
                put("accounts_sealed", sealed)
                remove("accounts_list")
                if (data.optBoolean("verify_reset")) put("verify_verdicts_reset_v1", true)
            }
        }
    }

    /**
     * What the app put on those accounts: the panels (Nova, Netra, Spider, Gozargah), the edge gateway,
     * the subscription generator, MAE's US exit, and WARP's registration relay with its key.
     */
    private object Deploys : SimpleItem(
        "acc_deploys", BackupKind.ACCOUNTS, "ورکرها و پنل‌های ساخته‌شده روی حساب‌های کلادفلر", "Workers and panels on your Cloudflare accounts",
        listOf(
            PrefsSlice("nova_panel", whole = true),
            PrefsSlice("netra_panel", whole = true),
            PrefsSlice("spider_panel", whole = true),
            PrefsSlice("gozargah_panel", whole = true),
            PrefsSlice("edge_gateway", whole = true),
            PrefsSlice("subgen_prefs", whole = true),
            PrefsSlice("mae", whole = true),
            PrefsSlice("warp_id_relay", keys = setOf("account", "script", "url", "version")),
        ),
    ) {
        private fun relayKey(ctx: Context): String? {
            val p = prefsOf(ctx, "warp_id_relay")
            p.getString("key_sealed", null)?.let { return Seals.open(it) }
            return p.getString("key", null)
        }

        override fun export(ctx: Context): JSONObject? {
            val base = super.export(ctx)
            val key = relayKey(ctx)
            if (base == null && key == null) return null
            return (base ?: JSONObject()).apply { if (key != null) put("relay_key", key) }
        }

        override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
            super.plan(ctx, data, dataVersion, mode, plan)
            val key = data.optString("relay_key", "")
            if (key.isNotEmpty()) {
                val sealed = Seals.seal(key)
                plan.prefs("warp_id_relay") { put("key_sealed", sealed); remove("key") }
            } else if (mode == RestoreMode.REPLACE) {
                plan.prefs("warp_id_relay") { remove("key_sealed"); remove("key") }
            }
        }
    }

    // ── WARP identities ────────────────────────────────────────────────────────────────────────

    /**
     * Every WARP registration the app holds: MASQUE's and WireGuard's (`tunnel*.toml`), «وارپ»'s
     * (`warp/data`), the engine's (`aether/data`), the measurements' own (`warp/probe`), Amnezia's
     * carrier, and the older WARP and trial keys. Registering is what Iran's filtering blocks most
     * often, which is what makes these worth carrying.
     *
     * The plain `.toml` exists only while an engine runs; between runs only `<name>.sealed` is kept,
     * and the engine opens it before it starts (CfIdentityFiles). A restore therefore writes the
     * sealed copy alone.
     */
    private object WarpIdentities : BackupContributor {
        override val id = "acc_warp"
        override val kind = BackupKind.ACCOUNTS
        override fun title() = tr("هویت‌های وارپ (ماسک، وایرگارد، وارپ، آمنزیا)", "WARP identities (MASQUE, WireGuard, WARP, Amnezia)")

        private val SEALED_DIRS = listOf("files", "files/warp/data", "files/aether/data")
        private const val PROBE = "files/warp/probe/identity.toml"
        private val SECRET = Regex("(?m)^\\s*(?:access_token|wg_private_key|key_pem|private_key)\\s*=")
        private val NAME = Regex("[A-Za-z0-9._-]+\\.toml")

        private val slices = listOf(
            PrefsSlice("cfwarp", keys = setOf("id_via", "id_at", "id_device")),
            PrefsSlice("warp_id_relay", prefixes = listOf("at_", "src_")),
            PrefsSlice("warp_prefs", whole = true),
            PrefsSlice("wg_trial", whole = true),
        )

        private fun dirOf(ctx: Context, d: String) = if (d == "files") ctx.filesDir else dataFile(ctx, d)

        private fun allowed(path: String): Boolean {
            if (path == PROBE) return true
            val d = path.substringBeforeLast('/')
            val name = path.substringAfterLast('/')
            return d in SEALED_DIRS && NAME.matches(name) && !name.endsWith("-lastconn.toml")
        }

        /** path of the plain file -> its text, for every identity on the phone. */
        private fun identities(ctx: Context): Map<String, String> {
            val out = sortedMapOf<String, String>()
            for (d in SEALED_DIRS) {
                val dir = dirOf(ctx, d)
                val names = dir.listFiles()?.filter { it.isFile }?.map { it.name.removeSuffix(".sealed") }?.toSortedSet().orEmpty()
                for (name in names) {
                    val path = "$d/$name"
                    if (!allowed(path)) continue
                    val plain = java.io.File(dir, name)
                    val sealed = java.io.File(dir, "$name.sealed")
                    val live = plain.takeIf { it.isFile }?.readText()?.takeIf { SECRET.containsMatchIn(it) }
                    val text = live ?: sealed.takeIf { it.isFile }?.let { Seals.open(it.readText()) } ?: continue
                    out[path] = text
                }
            }
            dataFile(ctx, PROBE).takeIf { it.isFile && it.length() > 0 }?.let { out[PROBE] = it.readText() }
            return out
        }

        override fun export(ctx: Context): JSONObject? {
            val ids = identities(ctx)
            val prefs = exportSlices(ctx, slices)
            val own = prefsOf(ctx, "amnezia_carrier").getString("own", null)?.let { raw ->
                JSONObject(raw).apply { put("k", Seals.open(getString("k"))) }
            }
            if (ids.isEmpty() && prefs == null && own == null) return null
            return JSONObject().apply {
                if (ids.isNotEmpty()) put("identities", JSONObject(ids as Map<*, *>))
                prefs?.let { put("prefs", it) }
                own?.let { put("amz_own", it) }
            }
        }

        override fun summary(data: JSONObject): String? {
            val n = (data.optJSONObject("identities")?.length() ?: 0) + (if (data.has("amz_own")) 1 else 0)
            return if (n == 0) null else countLabel(n, "هویت", "identities")
        }

        override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
            val wanted = LinkedHashMap<String, String>()
            data.optJSONObject("identities")?.let { o ->
                for (path in o.keys()) {
                    require(allowed(path)) { "unexpected identity path: $path" }
                    wanted[path] = o.getString(path).also { require(it.isNotBlank()) { "empty identity" } }
                }
            }
            for ((path, text) in wanted) {
                if (path == PROBE) {
                    plan.putFile(PROBE, text.toByteArray(Charsets.UTF_8))
                } else {
                    plan.putFile("$path.sealed", Seals.seal(text).toByteArray(Charsets.UTF_8))
                    if (dataFile(ctx, path).exists()) plan.deleteFile(path)
                }
            }
            if (mode == RestoreMode.REPLACE) {
                for (path in identities(ctx).keys) {
                    if (path in wanted) continue
                    if (dataFile(ctx, path).exists()) plan.deleteFile(path)
                    if (path != PROBE && dataFile(ctx, "$path.sealed").exists()) plan.deleteFile("$path.sealed")
                }
            }
            planSlices(ctx, slices, data.optJSONObject("prefs"), mode, plan)
            val own = data.optJSONObject("amz_own")
            if (own != null) {
                val stored = own.copy().apply { put("k", Seals.seal(own.getString("k"))) }
                plan.prefs("amnezia_carrier") { put("own", stored.toString()); remove("own_fail") }
            } else if (mode == RestoreMode.REPLACE) {
                plan.prefs("amnezia_carrier") { remove("own") }
            }
        }
    }

    // ── single sign-ins kept in the tunnel's secure store ─────────────────────────────────────

    /**
     * Credentials kept by `core.tunnel.SecureStore` under fixed keys, restored as one set: a Geph
     * account is either a secret or a user/password pair, never half of each.
     */
    private open class SecretKeysItem(
        override val id: String,
        private val fa: String,
        private val en: String,
        private val keys: List<String>,
        private val extra: List<PrefsSlice> = emptyList(),
    ) : BackupContributor {
        override val kind = BackupKind.ACCOUNTS
        override fun title() = tr(fa, en)

        override fun export(ctx: Context): JSONObject? {
            val secrets = JSONObject()
            for (k in keys) {
                if (!com.mlmvpn.core.tunnel.SecureStore.hasSecret(ctx, k)) continue
                val v = com.mlmvpn.core.tunnel.SecureStore.getSecret(ctx, k)
                check(v.isNotEmpty()) { "a sealed value on this phone cannot be opened" }
                secrets.put(k, v)
            }
            val prefs = exportSlices(ctx, extra)
            if (secrets.length() == 0 && prefs == null) return null
            return JSONObject().apply {
                if (secrets.length() > 0) put("secrets", secrets)
                prefs?.let { put("prefs", it) }
            }
        }

        override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
            val secrets = data.optJSONObject("secrets")
            val sealed = keys.associateWith { k -> secrets?.optString(k, "").orEmpty().let { if (it.isEmpty()) "" else Seals.encryptCore(it) } }
            plan.prefs("secure_settings") {
                for ((k, v) in sealed) if (v.isNotEmpty()) put(k, v) else remove(k)
            }
            // The plain copies an old version kept; getSecret would otherwise bring one back.
            plan.prefs("settings") { keys.forEach { remove(it) } }
            if (extra.isNotEmpty()) planSlices(ctx, extra, data.optJSONObject("prefs"), mode, plan)
        }
    }

    private object Geph : SecretKeysItem(
        "acc_geph", "حساب گف", "Geph account",
        listOf("geph_secret", "geph_legacy_user", "geph_legacy_pass"),
    )

    /**
     * The URnetwork account: its network token and its recovery phrase, both sealed on this phone and
     * sealed again on the next. Each job's own device in the account is not carried: a restored phone
     * registers devices of its own from the token (UrnetworkEngine), so two phones never share one.
     */
    private object Urnetwork : SecretKeysItem(
        "acc_urnetwork", "حساب URnetwork", "URnetwork account",
        listOf("urnetwork_jwt", "urnetwork_seed"),
    )

    private object ZeroTrust : SecretKeysItem(
        "acc_zero_trust", "Zero Trust کلادفلر", "Cloudflare Zero Trust",
        listOf("zero_trust_team", "zero_trust_client_id", "zero_trust_client_secret", "zero_trust_token", "zero_trust_email"),
        listOf(PrefsSlice("settings", keys = setOf("zero_trust_gateway"))),
    )

    // ── GitHub Tunnel ──────────────────────────────────────────────────────────────────────────

    /** Accounts with their tokens, the relay on the user's Cloudflare and the secret it was deployed with. */
    private object Github : BackupContributor {
        override val id = "acc_github"
        override val kind = BackupKind.ACCOUNTS
        override fun title() = tr("حساب‌های گیت‌هاب تانل", "GitHub Tunnel accounts")

        private const val PREFS = "github_tunnel"

        private fun stored(ctx: Context): List<JSONObject> =
            runCatching { JSONArray(prefsOf(ctx, PREFS).getString("accounts", "[]")).objects() }.getOrDefault(emptyList())

        override fun export(ctx: Context): JSONObject? {
            val p = prefsOf(ctx, PREFS)
            val accounts = stored(ctx).map { a ->
                a.copy().apply {
                    val enc = optString("tokenEnc")
                    remove("tokenEnc")
                    put("token", Seals.decryptCore(enc))
                }
            }
            val broker = p.getString("broker", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            val secret = p.getString("install_secret", "").orEmpty().let { if (it.isBlank()) "" else Seals.decryptCore(it) }
            if (accounts.isEmpty() && broker == null && secret.isEmpty()) return null
            return JSONObject().put("accounts", JSONArray(accounts)).apply {
                broker?.let { put("broker", it) }
                if (secret.isNotEmpty()) put("install_secret", secret)
            }
        }

        override fun summary(data: JSONObject) = countLabel(data.optJSONArray("accounts")?.length() ?: 0, "حساب", "accounts")

        override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
            val backup = data.optJSONArray("accounts")?.objects().orEmpty().map { a ->
                require(a.optString("id").isNotBlank() && a.optString("login").isNotBlank()) { "account without id or login" }
                a.copy().apply {
                    val token = optString("token")
                    remove("token")
                    put("tokenEnc", Seals.encryptCore(token))
                }
            }
            val merged = if (mode == RestoreMode.MERGE) {
                mergeById(stored(ctx), backup) { it.optString("login").lowercase().ifBlank { null } }
            } else backup
            val broker = data.optJSONObject("broker")
            val secret = data.optString("install_secret", "").let { if (it.isEmpty()) "" else Seals.encryptCore(it) }
            plan.prefs(PREFS) {
                put("accounts", JSONArray(merged).toString())
                if (broker != null) put("broker", broker.toString()) else if (mode == RestoreMode.REPLACE) remove("broker")
                if (secret.isNotEmpty()) put("install_secret", secret) else if (mode == RestoreMode.REPLACE) remove("install_secret")
            }
        }
    }

    // ── FENIX ──────────────────────────────────────────────────────────────────────────────────

    private object Fenix : BackupContributor {
        override val id = "acc_fenix"
        override val kind = BackupKind.ACCOUNTS
        override fun title() = tr("حساب‌های فایرفاکس (FENIX)", "Firefox accounts (FENIX)")

        private const val PREFS = "fenix"

        private fun stored(ctx: Context): List<JSONObject> {
            val raw = prefsOf(ctx, PREFS).getString("accounts", null) ?: return emptyList()
            return JSONArray(if (Seals.isSealed(raw)) Seals.open(raw) else raw).objects()
        }

        override fun export(ctx: Context): JSONObject? {
            val list = stored(ctx)
            if (list.isEmpty()) return null
            return JSONObject().put("accounts", JSONArray(list)).apply {
                prefsOf(ctx, PREFS).getString("active", null)?.let { put("active", it) }
            }
        }

        override fun summary(data: JSONObject) = countLabel(data.optJSONArray("accounts")?.length() ?: 0, "حساب", "accounts")

        override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
            val backup = data.getJSONArray("accounts").objects()
            backup.forEach { require(it.optString("id").isNotBlank()) { "account without id" } }
            val merged = if (mode == RestoreMode.MERGE) {
                mergeById(stored(ctx), backup) { it.optString("email").lowercase().ifBlank { null } ?: "id:" + it.optString("id") }
            } else backup
            val text = JSONArray(merged).toString()
            // FenixStore's own rule: a phone whose Keystore is broken keeps the sign-in unsealed.
            val stored = com.mlmvpn.scanner.data.SecureStore.seal(text) ?: text
            val ids = merged.map { it.optString("id") }.toSet()
            val nowActive = prefsOf(ctx, PREFS).getString("active", null)
            val active = when {
                mode == RestoreMode.MERGE && nowActive in ids -> nowActive
                data.optString("active") in ids -> data.optString("active")
                else -> merged.firstOrNull()?.optString("id")
            }
            plan.prefs(PREFS) {
                put("accounts", stored)
                if (active != null) put("active", active) else remove("active")
                remove("auth")
                remove("pass")
            }
        }
    }

    // ── the OpenVPN vault: OpenVPN and Windscribe ──────────────────────────────────────────────

    private object OpenVpn : BackupContributor {
        override val id = "acc_openvpn"
        override val kind = BackupKind.ACCOUNTS
        override fun title() = tr("OpenVPN: حساب‌ها و فایل‌ها", "OpenVPN: accounts and files")

        private const val DIR = "no_backup/openvpn"

        override fun export(ctx: Context): JSONObject? {
            val names = VaultCipher.names(ctx).filter { it != Windscribe.VAULT }
            if (names.isEmpty()) return null
            val files = JSONObject()
            for (n in names) files.put(n, VaultCipher.read(ctx, n) ?: continue)
            return JSONObject().put("vault", files)
        }

        override fun summary(data: JSONObject): String? {
            val catalog = data.optJSONObject("vault")?.optString("catalog")?.takeIf { it.isNotEmpty() } ?: return null
            val o = runCatching { JSONObject(catalog) }.getOrNull() ?: return null
            val a = o.optJSONArray("accounts")?.length() ?: 0
            val p = o.optJSONArray("profiles")?.length() ?: 0
            return countLabel(a, "حساب", "accounts") + "، " + countLabel(p, "فایل", "files")
        }

        override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
            val vault = data.getJSONObject("vault")
            val out = LinkedHashMap<String, String>()
            for (n in vault.keys()) {
                require(n != Windscribe.VAULT && Regex("[a-zA-Z0-9_-]{1,100}").matches(n)) { "unexpected vault entry" }
                out[n] = vault.getString(n)
            }
            if (mode == RestoreMode.MERGE) {
                out["catalog"]?.let { b -> VaultCipher.read(ctx, "catalog")?.let { c -> out["catalog"] = mergeCatalog(JSONObject(c), JSONObject(b)).toString() } }
                out["companions"]?.let { b ->
                    VaultCipher.read(ctx, "companions")?.let { c ->
                        val merged = JSONObject(c)
                        val add = JSONObject(b)
                        for (k in add.keys()) merged.put(k, add.get(k))
                        out["companions"] = merged.toString()
                    }
                }
            } else {
                for (n in VaultCipher.names(ctx)) if (n != Windscribe.VAULT && n !in out) plan.deleteFile("$DIR/$n")
            }
            for ((n, text) in out) plan.putFile("$DIR/$n", VaultCipher.seal(n, text))
        }

        /** Accounts by user name, profiles by provider id or name; the phone's current choices stay. */
        private fun mergeCatalog(current: JSONObject, backup: JSONObject): JSONObject {
            val accounts = mergeById(current.optJSONArray("accounts")?.objects().orEmpty(), backup.optJSONArray("accounts")?.objects().orEmpty()) {
                it.optString("username").lowercase().ifBlank { null }
            }
            val profiles = mergeById(current.optJSONArray("profiles")?.objects().orEmpty(), backup.optJSONArray("profiles")?.objects().orEmpty()) {
                it.optString("providerId").ifBlank { null } ?: it.optString("name").ifBlank { null }?.let { n -> "n:$n" }
            }
            val ids = accounts.map { it.optString("id") }.toSet()
            val active = current.optString("active").takeIf { !current.isNull("active") && it in ids }
                ?: backup.optString("active").takeIf { !backup.isNull("active") && it in ids }
            return JSONObject()
                .put("version", 1)
                .put("active", active ?: JSONObject.NULL)
                .put("selected", if (current.isNull("selected")) backup.opt("selected") ?: JSONObject.NULL else current.opt("selected"))
                .put("auto", current.optBoolean("auto"))
                .put("accounts", JSONArray(accounts))
                .put("profiles", JSONArray(profiles))
        }
    }

    private object Windscribe : BackupContributor {
        const val VAULT = "windscribe"
        override val id = "acc_windscribe"
        override val kind = BackupKind.ACCOUNTS
        override fun title() = tr("حساب‌های ویندسکرایب", "Windscribe accounts")

        private const val PATH = "no_backup/openvpn/windscribe"
        private val slices = listOf(PrefsSlice("windscribe_amz", whole = true))

        override fun export(ctx: Context): JSONObject? {
            val root = VaultCipher.read(ctx, VAULT)?.let(::JSONObject)
            val prefs = exportSlices(ctx, slices)
            if ((root == null || (root.optJSONArray("accounts")?.length() ?: 0) == 0) && prefs == null) return null
            return JSONObject().apply {
                root?.let { put("vault", it) }
                prefs?.let { put("prefs", it) }
            }
        }

        override fun summary(data: JSONObject) =
            countLabel(data.optJSONObject("vault")?.optJSONArray("accounts")?.length() ?: 0, "حساب", "accounts")

        override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
            val backup = data.optJSONObject("vault")
            if (backup != null) {
                val current = VaultCipher.read(ctx, VAULT)?.let(::JSONObject)
                val root = if (mode == RestoreMode.MERGE && current != null) {
                    current.copy().apply {
                        put("accounts", JSONArray(mergeById(
                            current.optJSONArray("accounts")?.objects().orEmpty(),
                            backup.optJSONArray("accounts")?.objects().orEmpty(),
                        ) { it.optString("user_id").ifBlank { null } }))
                        if (!has("active") || optString("active").isBlank()) backup.optString("active").takeIf { it.isNotBlank() }?.let { put("active", it) }
                    }
                } else {
                    backup.copy().apply {
                        // The installation this phone already registered with Windscribe stays its own.
                        current?.optString("installationId")?.takeIf { it.isNotBlank() }?.let { put("installationId", it) }
                    }
                }
                plan.putFile(PATH, VaultCipher.seal(VAULT, root.toString()))
            } else if (mode == RestoreMode.REPLACE && dataFile(ctx, PATH).exists()) {
                plan.deleteFile(PATH)
            }
            planSlices(ctx, slices, data.optJSONObject("prefs"), mode, plan)
        }
    }

    /** The servers taken from the user's Proton account and the account's WireGuard key. */
    private object Proton : SimpleItem(
        "acc_proton", BackupKind.ACCOUNTS, "سرورهای پروتون و کلید حساب", "Proton servers and account key",
        listOf(PrefsSlice("proton_vpn", whole = true)),
    )

    // ── configs ────────────────────────────────────────────────────────────────────────────────

    /** V2Ray's configs, the subscription links and the groups the cloud panels made. */
    private object V2ray : BackupContributor {
        override val id = "acc_v2ray"
        override val kind = BackupKind.ACCOUNTS
        override fun title() = tr("کانفیگ‌های V2Ray، لینک‌های اشتراک و گروه‌ها", "V2Ray configs, subscription links and groups")

        private const val NODES = "files/nodes.json"

        private fun nodes(ctx: Context): List<JSONObject> {
            val f = dataFile(ctx, NODES)
            if (f.isFile) return JSONArray(f.readText()).objects()
            val bak = dataFile(ctx, "$NODES.bak")
            if (bak.isFile) return JSONArray(bak.readText()).objects()
            return prefsOf(ctx, "vpn_nodes_prefs").getString("nodes_list", null)?.let { JSONArray(it).objects() }.orEmpty()
        }

        private fun prefList(ctx: Context, file: String, key: String): List<JSONObject> =
            prefsOf(ctx, file).getString(key, null)?.let { runCatching { JSONArray(it).objects() }.getOrNull() }.orEmpty()

        override fun export(ctx: Context): JSONObject? {
            val out = JSONObject()
            nodes(ctx).takeIf { it.isNotEmpty() }?.let { out.put("nodes", JSONArray(it)) }
            prefList(ctx, "subscription_manager_prefs", "subscriptions").takeIf { it.isNotEmpty() }?.let { out.put("subscriptions", JSONArray(it)) }
            prefList(ctx, "group_manager_prefs", "cloud_groups").takeIf { it.isNotEmpty() }?.let { out.put("cloud_groups", JSONArray(it)) }
            return if (out.length() == 0) null else out
        }

        override fun summary(data: JSONObject): String? {
            val n = data.optJSONArray("nodes")?.length() ?: 0
            val s = data.optJSONArray("subscriptions")?.length() ?: 0
            return listOfNotNull(
                countLabel(n, "کانفیگ", "configs").takeIf { n > 0 },
                countLabel(s, "لینک اشتراک", "links").takeIf { s > 0 },
            ).joinToString(tr("، ", ", ")).ifEmpty { null }
        }

        private fun merge(mode: RestoreMode, current: () -> List<JSONObject>, backup: JSONArray?): List<JSONObject> {
            val b = backup?.objects().orEmpty()
            return if (mode == RestoreMode.MERGE) mergeById(current(), b) { it.optString("id").ifBlank { null } } else b
        }

        override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
            data.optJSONArray("nodes")?.objects()?.forEach { n ->
                for (k in listOf("id", "name", "uri", "type")) require(n.optString(k).isNotEmpty()) { "config without $k" }
            }
            if (data.has("nodes") || mode == RestoreMode.REPLACE) {
                val list = merge(mode, { nodes(ctx) }, data.optJSONArray("nodes")).distinctBy { it.optString("id") }
                plan.putFile(NODES, JSONArray(list).toString().toByteArray(Charsets.UTF_8))
                plan.prefs("vpn_nodes_prefs") { remove("nodes_list") }
            }
            if (data.has("subscriptions") || mode == RestoreMode.REPLACE) {
                val list = merge(mode, { prefList(ctx, "subscription_manager_prefs", "subscriptions") }, data.optJSONArray("subscriptions"))
                plan.prefs("subscription_manager_prefs") { put("subscriptions", JSONArray(list).toString()) }
            }
            if (data.has("cloud_groups") || mode == RestoreMode.REPLACE) {
                val list = merge(mode, { prefList(ctx, "group_manager_prefs", "cloud_groups") }, data.optJSONArray("cloud_groups"))
                plan.prefs("group_manager_prefs") { put("cloud_groups", JSONArray(list).toString()) }
            }
        }
    }

    /**
     * The scanner's combined groups and its IP archive: the user's own work, but tied to the network
     * they were measured on and sometimes tens of megabytes -- an item of its own, so it can be left
     * out without leaving the configs out.
     */
    private object ScannerGroups : BackupContributor {
        override val id = "acc_scanner_groups"
        override val kind = BackupKind.ACCOUNTS
        override fun title() = tr("گروه‌های ترکیب اسکنر و آرشیو آی‌پی", "Scanner groups and IP archive")

        private const val FILE = "files/scanner_groups.json"
        private const val MAX = 12L * 1024 * 1024

        override fun export(ctx: Context): JSONObject? {
            val f = dataFile(ctx, FILE)
            val src = f.takeIf { it.isFile } ?: dataFile(ctx, "$FILE.bak").takeIf { it.isFile }
            check(src == null || src.length() <= MAX) { "too large" }
            val groups = src?.let { JSONArray(it.readText()).objects() }.orEmpty()
            val archives = prefsOf(ctx, "group_manager_prefs").getString("ip_archives", null)
                ?.let { runCatching { JSONArray(it).objects() }.getOrNull() }.orEmpty()
            if (groups.isEmpty() && archives.isEmpty()) return null
            return JSONObject().apply {
                if (groups.isNotEmpty()) put("groups", JSONArray(groups))
                if (archives.isNotEmpty()) put("archives", JSONArray(archives))
            }
        }

        override fun summary(data: JSONObject) = countLabel(data.optJSONArray("groups")?.length() ?: 0, "گروه", "groups")

        override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
            if (data.has("groups") || mode == RestoreMode.REPLACE) {
                val b = data.optJSONArray("groups")?.objects().orEmpty()
                val list = if (mode == RestoreMode.MERGE) {
                    val f = dataFile(ctx, FILE)
                    mergeById(f.takeIf { it.isFile }?.let { JSONArray(it.readText()).objects() }.orEmpty(), b) { it.optString("id").ifBlank { null } }
                } else b
                plan.putFile(FILE, JSONArray(list).toString().toByteArray(Charsets.UTF_8))
                plan.prefs("group_manager_prefs") { remove("scanner_groups") }
            }
            if (data.has("archives") || mode == RestoreMode.REPLACE) {
                val b = data.optJSONArray("archives")?.objects().orEmpty()
                val list = if (mode == RestoreMode.MERGE) {
                    val cur = prefsOf(ctx, "group_manager_prefs").getString("ip_archives", null)
                        ?.let { runCatching { JSONArray(it).objects() }.getOrNull() }.orEmpty()
                    mergeById(cur, b) { it.optString("id").ifBlank { null } }
                } else b
                plan.prefs("group_manager_prefs") { put("ip_archives", JSONArray(list).toString()) }
            }
        }
    }

    /** The servers saved in «اتصال سریع». */
    private object QuickSaved : BackupContributor {
        override val id = "acc_quick_saved"
        override val kind = BackupKind.ACCOUNTS
        override fun title() = tr("سرورهای ذخیره‌شدهٔ اتصال سریع", "Quick Connect saved servers")

        private const val FILE = "files/quick-saved.json"

        private fun stored(ctx: Context): List<JSONObject> =
            dataFile(ctx, FILE).takeIf { it.isFile }?.let { JSONObject(it.readText()).optJSONArray("servers")?.objects() }.orEmpty()

        override fun export(ctx: Context): JSONObject? = stored(ctx).takeIf { it.isNotEmpty() }?.let { JSONObject().put("servers", JSONArray(it)) }

        override fun summary(data: JSONObject) = countLabel(data.optJSONArray("servers")?.length() ?: 0, "سرور", "servers")

        override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
            val b = data.getJSONArray("servers").objects()
            val list = if (mode == RestoreMode.MERGE) mergeById(stored(ctx), b) { it.optString("id").ifBlank { null } } else b
            plan.putFile(FILE, JSONObject().put("servers", JSONArray(list)).toString().toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * The user's own entries in an engine's state file (Outline's keys, Amnezia's configs): taken from
     * the file's `servers` by their source, put back the same way, and checked with the engine's own
     * decoder before anything is staged.
     */
    private abstract class StateServers(
        override val id: String,
        private val fa: String,
        private val en: String,
        private val path: String,
        /** The field a server's source is in, and the user's source. */
        private val srcKey: String,
        private val idKey: String,
        private val tombKey: String,
        private val emptyState: () -> JSONObject,
        private val unit: Pair<String, String>,
    ) : BackupContributor {
        override val kind = BackupKind.ACCOUNTS
        override fun title() = tr(fa, en)

        abstract fun validate(text: String)

        private fun isUser(o: JSONObject) = o.optString(srcKey) == "user"

        override fun export(ctx: Context): JSONObject? {
            val text = dataFile(ctx, path).takeIf { it.isFile }?.readText() ?: return null
            val mine = JSONObject(text).optJSONArray("servers")?.objects().orEmpty().filter(::isUser)
            return if (mine.isEmpty()) null else JSONObject().put("servers", JSONArray(mine))
        }

        override fun summary(data: JSONObject) = countLabel(data.optJSONArray("servers")?.length() ?: 0, unit.first, unit.second)

        override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
            val backup = data.getJSONArray("servers").objects()
            backup.forEach { require(isUser(it) && it.optString(idKey).isNotEmpty()) { "unexpected server entry" } }
            val state = currentText(ctx, plan, path)?.let(::JSONObject) ?: emptyState()
            val ids = backup.map { it.optString(idKey) }.toSet()
            val kept = state.optJSONArray("servers")?.objects().orEmpty().filterNot { o ->
                o.optString(idKey) in ids || (mode == RestoreMode.REPLACE && isUser(o))
            }
            state.put("servers", JSONArray(kept + backup))
            // A server the user removed once is held off by its tombstone; restoring it lifts that.
            state.optJSONObject(tombKey)?.let { t -> ids.forEach { t.remove(it) } }
            val text = state.toString()
            validate(text)
            plan.putFile(path, text.toByteArray(Charsets.UTF_8))
        }
    }

    private object OutlineKeys : StateServers(
        "acc_outline", "کلیدهای اوتلاین شما", "Your Outline keys", "files/outline/state.json",
        srcKey = "src", idKey = "id", tombKey = "tomb", emptyState = { JSONObject().put("v", 1) }, unit = "کلید" to "keys",
    ) {
        override fun validate(text: String) {
            com.mlmvpn.scanner.engines.outline.OutlineCodec.decode(text)
        }
    }

    private object AmneziaConfigs : StateServers(
        "acc_amnezia", "کانفیگ‌های آمنزیای شما (با پروتون و ویندسکرایب)", "Your Amnezia configs (with Proton and Windscribe)",
        "files/amnezia/state.json",
        srcKey = "s", idKey = "i", tombKey = "tomb", emptyState = { JSONObject().put("v", 2) }, unit = "کانفیگ" to "configs",
    ) {
        override fun validate(text: String) {
            com.mlmvpn.scanner.engines.amnezia.AmzCodec.decode(text)
        }
    }

    /** FLUX's own subscription links (at most five, as FLUX keeps). */
    private object FluxLinks : BackupContributor {
        override val id = "acc_flux"
        override val kind = BackupKind.ACCOUNTS
        override fun title() = tr("لینک‌های اشتراک FLUX شما", "Your FLUX subscription links")

        private fun stored(ctx: Context): List<String> =
            prefsOf(ctx, "flux_sources").getString("user_subs", null)?.let { runCatching { JSONArray(it).strings() }.getOrNull() }.orEmpty()

        override fun export(ctx: Context): JSONObject? = stored(ctx).takeIf { it.isNotEmpty() }?.let { JSONObject().put("links", JSONArray(it)) }

        override fun summary(data: JSONObject) = countLabel(data.optJSONArray("links")?.length() ?: 0, "لینک", "links")

        override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
            val b = data.getJSONArray("links").strings().filter { it.startsWith("https://") }
            val list = (if (mode == RestoreMode.MERGE) b + stored(ctx) else b).distinct().take(5)
            plan.prefs("flux_sources") { put("user_subs", JSONArray(list).toString()) }
        }
    }

    /** Telegram WEB proxies on the user's Cloudflare, each with its secret. */
    private object TgProxies : BackupContributor {
        override val id = "acc_tgproxy"
        override val kind = BackupKind.ACCOUNTS
        override fun title() = tr("پروکسی‌های تلگرام", "Telegram proxies")

        private const val FILE = "files/tgproxy/state.json"

        private fun stored(ctx: Context, plan: RestorePlan? = null): List<JSONObject> {
            val text = (if (plan != null) currentText(ctx, plan, FILE) else dataFile(ctx, FILE).takeIf { it.isFile }?.readText()) ?: return emptyList()
            return JSONObject(text).optJSONArray("services")?.objects().orEmpty()
        }

        override fun export(ctx: Context): JSONObject? {
            val list = stored(ctx).map { s ->
                s.copy().apply {
                    val sec = optString("sec")
                    remove("sec")
                    if (sec.isNotEmpty()) put("secret", Seals.open(sec))
                }
            }
            return if (list.isEmpty()) null else JSONObject().put("services", JSONArray(list))
        }

        override fun summary(data: JSONObject) = countLabel(data.optJSONArray("services")?.length() ?: 0, "پروکسی", "proxies")

        override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
            val backup = data.getJSONArray("services").objects().map { s ->
                require(s.optString("id").isNotBlank()) { "proxy without id" }
                s.copy().apply {
                    val secret = optString("secret")
                    remove("secret")
                    put("sec", if (secret.isEmpty()) "" else Seals.seal(secret))
                }
            }
            val list = if (mode == RestoreMode.MERGE) mergeById(stored(ctx, plan), backup) { it.optString("id").ifBlank { null } } else backup
            val text = JSONObject().put("v", 1).put("services", JSONArray(list)).toString()
            com.mlmvpn.scanner.engines.tgproxy.TgCodec.decode(text)
            plan.putFile(FILE, text.toByteArray(Charsets.UTF_8))
        }
    }

    /** «اضطراری ۲»'s relays: the user's Apps Script deployments and the relay worker's key. */
    private object GstRelays : BackupContributor {
        override val id = "acc_gst"
        override val kind = BackupKind.ACCOUNTS
        override fun title() = tr("رله‌های گوگل‌اسکریپت (اضطراری ۲)", "Google Apps Script relays (Emergency 2)")

        private val slices = listOf(PrefsSlice(DEFAULT_PREFS, keys = setOf("gst_relay_urls", "gst_relay_proxy_key")))

        private fun relays(ctx: Context): List<JSONObject> =
            prefsOf(ctx, DEFAULT_PREFS).getString("gst_relays", null)?.let { runCatching { JSONArray(it).objects() }.getOrNull() }.orEmpty()

        override fun export(ctx: Context): JSONObject? {
            val list = relays(ctx)
            val prefs = exportSlices(ctx, slices)
            if (list.isEmpty() && prefs == null) return null
            return JSONObject().apply {
                if (list.isNotEmpty()) put("relays", JSONArray(list))
                prefs?.let { put("prefs", it) }
            }
        }

        override fun summary(data: JSONObject) = countLabel(data.optJSONArray("relays")?.length() ?: 0, "رله", "relays")

        override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
            if (data.has("relays") || mode == RestoreMode.REPLACE) {
                val b = data.optJSONArray("relays")?.objects().orEmpty()
                val list = if (mode == RestoreMode.MERGE) {
                    mergeById(relays(ctx), b) { it.optString("id").ifBlank { null } ?: it.optString("deploymentId").ifBlank { null } }
                } else b
                plan.prefs(prefsName(ctx, DEFAULT_PREFS)) { put("gst_relays", JSONArray(list).toString()) }
            }
            planSlices(ctx, slices, data.optJSONObject("prefs"), mode, plan)
        }
    }

    /** A friend's private Conduit station (its pairing link). */
    private object Conduit : SimpleItem(
        "acc_conduit", BackupKind.ACCOUNTS, "کاندوئیت شخصی سایفون", "Psiphon personal Conduit",
        files = listOf("files/psiphon/pairing.json"),
    )

    /**
     * The certificates the user may have installed in Android: domain fronting's and «اضطراری ۲»'s
     * CA. Restored with their keys, a CA already trusted by the phone (an app reinstalled, say) keeps
     * working without being installed again.
     */
    private object Certificates : BackupContributor {
        override val id = "acc_certs"
        override val kind = BackupKind.ACCOUNTS
        override fun title() = tr("گواهی‌های برنامه (دامین‌فرانتینگ، اضطراری ۲)", "App certificates (domain fronting, Emergency 2)")

        private val fixed = listOf("files/mlmvpn_mitm.crt", "files/mlmvpn_mitm.key")
        private val slices = listOf(PrefsSlice("mitm_cert_prefs", whole = true))

        private fun caFiles(ctx: Context): List<String> =
            dataFile(ctx, "files/ca").listFiles()?.filter { it.isFile && it.length() <= 1024 * 1024 }?.map { "files/ca/${it.name}" }?.sorted().orEmpty()

        override fun export(ctx: Context): JSONObject? {
            val files = exportFiles(ctx, fixed + caFiles(ctx))
            val prefs = exportSlices(ctx, slices)
            if (files == null && prefs == null) return null
            return JSONObject().apply {
                files?.let { put("files", it) }
                prefs?.let { put("prefs", it) }
            }
        }

        override fun summary(data: JSONObject) = countLabel(data.optJSONObject("files")?.length() ?: 0, "فایل", "files")

        override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
            val files = data.optJSONObject("files")
            val ca = files?.keys()?.asSequence()?.filter { it.startsWith("files/ca/") }?.toList().orEmpty()
            ca.forEach { require(Regex("files/ca/[A-Za-z0-9._-]+").matches(it)) { "unexpected certificate path" } }
            val current = if (mode == RestoreMode.REPLACE) caFiles(ctx) else emptyList()
            planFiles(ctx, fixed + (ca + current).distinct(), files, mode, plan)
            planSlices(ctx, slices, data.optJSONObject("prefs"), mode, plan)
        }
    }

    /** MAE's own WARP identities, its exit worker and the configs it read from the user's panels. */
    private object MaeIdentity : BackupContributor {
        override val id = "acc_mae"
        override val kind = BackupKind.ACCOUNTS
        override fun title() = tr("هویت و ورکر MAE", "MAE identity and worker")

        private const val FILE = "files/mae/state.json"

        override fun export(ctx: Context): JSONObject? {
            val text = dataFile(ctx, FILE).takeIf { it.isFile }?.readText() ?: return null
            val s = JSONObject(text)
            val out = JSONObject()
            for (k in listOf("warp", "warpProbe")) s.optJSONObject(k)?.let { w -> out.put(k, w.copy().apply { put("k", Seals.open(w.getString("k"))) }) }
            s.optJSONObject("worker")?.let { w -> out.put("worker", w.copy().apply { put("id", Seals.open(w.getString("id"))) }) }
            s.optJSONArray("cloudLinks")?.strings()?.takeIf { it.isNotEmpty() }?.let { links ->
                out.put("cloudLinks", JSONArray(links.map(Seals::open)))
                out.put("cloudAt", s.optLong("cloudAt"))
            }
            return if (out.length() == 0) null else out
        }

        override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
            val s = currentText(ctx, plan, FILE)?.let(::JSONObject)
                ?: JSONObject().put("schema", com.mlmvpn.scanner.engines.mae.store.MaeStateCodec.SCHEMA)
            for (k in listOf("warp", "warpProbe")) {
                val w = data.optJSONObject(k)
                if (w != null) s.put(k, w.copy().apply { put("k", Seals.seal(w.getString("k"))) })
                else if (mode == RestoreMode.REPLACE) s.remove(k)
            }
            val worker = data.optJSONObject("worker")
            if (worker != null) s.put("worker", worker.copy().apply { put("id", Seals.seal(worker.getString("id"))) })
            else if (mode == RestoreMode.REPLACE) s.remove("worker")
            val links = data.optJSONArray("cloudLinks")?.strings()
            if (links != null) {
                val now = if (mode == RestoreMode.MERGE) s.optJSONArray("cloudLinks")?.strings().orEmpty().mapNotNull { runCatching { Seals.open(it) }.getOrNull() } else emptyList()
                s.put("cloudLinks", JSONArray((now + links).distinct().map(Seals::seal)))
                s.put("cloudAt", maxOf(s.optLong("cloudAt"), data.optLong("cloudAt")))
            } else if (mode == RestoreMode.REPLACE) {
                s.remove("cloudLinks")
            }
            val text = s.toString()
            com.mlmvpn.scanner.engines.mae.store.MaeStateCodec.decode(text)
            plan.putFile(FILE, text.toByteArray(Charsets.UTF_8))
        }
    }
}
