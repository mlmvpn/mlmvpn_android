package com.mlmvpn.scanner.engines.gateway

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the engine asks of the Cloudflare account — a seam, so the whole protocol runs as a plain
 * JVM test against a fake account (tests/…/GatewayEngineTest), the way the Windows manager's tests
 * run against a faked Cloudflare. Every call blocks; callers are on IO.
 */
interface GatewayCloud {
    /** Who this device is in the account's registry: `android`, or `windows` for the desktop app. */
    val by: String
    /** The account's registry record under key `GW`, or null. Throws when the registry cannot be read. */
    fun registryGet(): SharedRaw?
    fun registryPut(script: String, url: String, s: JSONObject)
    /** Delete the record, but only while it still names [script]. */
    fun registryRemove(script: String)
    fun scriptExists(script: String): Boolean
    /** The account's workers.dev subdomain, made with a neutral name when it has none. */
    fun subdomain(): String
    fun workerName(): String
    /** Upload the ES modules as one Worker with these bindings. */
    fun upload(script: String, files: List<Pair<String, String>>, bindings: List<JSONObject>)
    fun enableWorkersDev(script: String)
    fun removeWorker(script: String)
    fun randomUuid(): String
    /** A tunnel path: "/" + 16 hex. */
    fun randomPath(): String
}

/** A registry record as read: what both apps write under `GW`. */
data class SharedRaw(val script: String, val url: String, val s: JSONObject, val by: String, val at: Long)

/** This device's copy of the record (one per Cloudflare account). */
interface GatewayStore {
    fun get(): GatewayRecord?
    fun put(rec: GatewayRecord)
    fun del()
}

/** The Worker's own name resolved: what BPB's subscription lists beside the name. */
data class DohAnswer(val v4: List<String>, val v6: List<String>)

/** What the screens may know about the install: never the id, the path or a panel's id. */
data class GatewayStatus(
    val installed: Boolean,
    val url: String = "",
    val host: String = "",
    val script: String = "",
    val fallback: String = "",
    val at: Long = 0L,
    val fetchedAt: Long = 0L,
    val configs: Int = 0,
    val upToDate: Boolean = false,
    /** The account holds this record too, so the other device uses the very same Worker. */
    val shared: Boolean = false,
    val sharedBy: String = "",
    val extraScript: String? = null,
    val settings: GatewaySettings = GatewaySettings.DEFAULT,
    val resolvedV4: Int = 0,
    val resolvedV6: Int = 0,
    val panels: Map<String, PanelStatus> = emptyMap(),
    /** [Gateway.idsMarker] of the Studio ids the account's record says the last upload carried. */
    val studioMark: String = "",
    /** How many «کانفیگ استدیو» people fit beside BPB and the connected panels. */
    val studioRoom: Int = 0,
) {
    fun connected(code: String) = panels.containsKey(code)
}

data class PanelStatus(val at: Long, val configs: Int, val fetchedAt: Long)

/** The result of a pull: what the account said and whether this device's copy moved. */
data class Pulled(val state: String, val changed: Boolean = false, val replaced: String? = null)

data class ConfigsResult(
    val configs: List<String>,
    /** `live` / `cache` / `stale` / `none` / `off`, and how many addresses of each family. */
    val resolvedFrom: String,
    val resolvedV4: Int,
    val resolvedV6: Int,
    val methods: List<GatewayLinks.MethodLink>,
)

/** One panel's id added to, or taken off, the Worker in the same upload as the change itself. */
data class PanelChange(val add: String? = null, val remove: String? = null)

/**
 * The central Worker's protocol — the Android half of the Windows app's `edge-gateway/manager.js`,
 * step for step (docs/PANEL-REGISTRY.md, key `GW`). Every change follows it:
 *
 *  1. **pull** — the account's record is read first; what the other device did is taken in, and its
 *     Worker is adopted instead of making a second one;
 *  2. **change** — made on a copy;
 *  3. **publish** — the copy is written to the registry BEFORE the Worker is touched, so the registry
 *     always names at least every id the Worker accepts: an upload from either device can never
 *     revoke an id that is live on the other;
 *  4. **upload** — and if the Worker refuses, the registry goes back to what it held.
 *
 * A registry that cannot be read or written never stops an install (a token without KV rights is
 * common): the Worker works, the record says it is not shared yet, and the next [sync] tells the account.
 */
class GatewayEngine(
    private val accountId: String,
    private val cloud: GatewayCloud,
    private val store: GatewayStore,
    private val modules: () -> List<Pair<String, String>>,
    private val lookup: (host: String) -> DohAnswer,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** When each account's record was last read — shared by every engine of this process, so a screen's calls don't each re-read it. */
    private val pulledAt: MutableMap<String, Long> = PULLED_AT,
    /**
     * The ids of «کانفیگ استدیو»'s people on this account that must be on the Worker right now, for an
     * upload that is not the Studio's own (a panel connected, the code updated): every upload carries
     * them, so no screen can drop a person by uploading for another reason. Given the Worker's
     * script, so a record about another Worker is ignored. A failure here leaves them out, and says so.
     */
    private val studioIds: (script: String) -> List<String> = { emptyList() },
) {

    private val notInstalled = GatewayException("درگاه روی این حساب نصب نیست.", "The gateway is not installed on this account.")

    /** What is installed — never the id or the path. */
    fun status(): GatewayStatus = viewOf(store.get())

    private fun currentHash(): String? = runCatching { Gateway.codeHash(modules()) }.getOrNull()

    /** The Worker's own cap, from the code this build uploads. */
    private fun maxIds(): Int = runCatching { Gateway.maxIdsOf(modules()) }.getOrDefault(Gateway.DEFAULT_MAX_IDS)

    /**
     * How many «کانفیگ استدیو» people fit on this account's Worker beside BPB and the panels connected
     * now — from this device's copy, no request. A Worker not installed yet has room for all but its own id.
     */
    fun studioRoom(): Int = (maxIds() - 1 - (store.get()?.panels?.size ?: 0)).coerceAtLeast(0)

    private fun viewOf(rec: GatewayRecord?): GatewayStatus {
        if (rec == null) return GatewayStatus(installed = false, studioRoom = studioRoom())
        return GatewayStatus(
            installed = true, url = rec.url, host = rec.host, script = rec.script, fallback = rec.fallback, at = rec.at,
            fetchedAt = rec.fetchedAt, configs = rec.configCount, upToDate = currentHash()?.let { it == rec.codeHash } == true,
            shared = rec.sharedAt > 0 && !rec.unshared, sharedBy = rec.sharedBy, extraScript = rec.replaced,
            settings = rec.settings, resolvedV4 = rec.resolved?.v4?.size ?: 0, resolvedV6 = rec.resolved?.v6?.size ?: 0,
            // Which panels ride this Worker, and how many links each has taken — never their ids.
            panels = rec.panels.mapValues { PanelStatus(it.value.at, it.value.configCount, it.value.fetchedAt) },
            studioMark = rec.studioMark,
            studioRoom = (maxIds() - 1 - rec.panels.size).coerceAtLeast(0),
        )
    }

    // ── pull / publish ───────────────────────────────────────────────────────────────────

    /**
     * Read the account's shared record and bring this device's copy in line with it:
     *  - none shared / unreadable / not this build's format / its Worker is gone → nothing to take in;
     *  - none here → adopted; another Worker here → the account's wins (the one here is reported as
     *    `replaced` and left alone on Cloudflare); the same Worker → whichever side changed last wins.
     */
    fun pull(step: (String) -> Unit = {}, force: Boolean = false): Pulled {
        if (!force && now() - (pulledAt[accountId] ?: 0L) < Gateway.PULL_TTL_MS) return Pulled("skipped")
        val raw = try {
            cloud.registryGet()
        } catch (e: Exception) {
            step(tr("ثبت مشترک حساب خوانده نشد (${e.message}) — با نسخهٔ همین دستگاه ادامه می‌دهم.", "The account's shared record could not be read (${e.message}) — carrying on with this device's copy."))
            return Pulled("unreadable")
        }
        pulledAt[accountId] = now()
        if (raw == null) return Pulled("none")
        val shared = GatewayShared.parse(raw.script, raw.url, raw.s, raw.by, raw.at) ?: return Pulled("invalid")
        val alive = try { cloud.scriptExists(shared.script) } catch (e: Exception) { true }   // unknown ≠ gone
        if (!alive) return Pulled("dead")
        val local = store.get()
        val who = if (shared.by == "android") tr("نصب گوشی", "the phone's install") else tr("نصب ویندوز", "the Windows install")
        return when (val plan = GatewayShared.plan(local, shared)) {
            PullPlan.Adopt -> {
                store.put(GatewayShared.adopt(accountId, local, shared, null, now()))
                step(tr("ورکر مرکزی این حساب ($who) به کار می‌رود — ورکر تازه‌ای ساخته نمی‌شود.", "Using this account's central Worker ($who) — no new Worker is made."))
                Pulled("adopted", true)
            }
            is PullPlan.Replace -> {
                store.put(GatewayShared.adopt(accountId, local, shared, plan.own, now()))
                step(tr("این حساب از قبل یک ورکر مرکزی دارد (${shared.script}) — همان به کار می‌رود؛ ورکر قبلی این دستگاه (${plan.own}) دست‌نخورده روی حساب مانده است.",
                    "This account already has a central Worker (${shared.script}) — it is used; this device's earlier one (${plan.own}) is left untouched on the account."))
                Pulled("adopted", true, plan.own)
            }
            PullPlan.Push -> {
                publishShared(local!!, step)
                Pulled("pushed")
            }
            PullPlan.Same -> {
                // The Studio marker is not part of «changed» (a record that differs only in it is the
                // same Worker), but the account's copy says what the last upload carried: taken in.
                val l = local!!
                if (l.unshared || l.studioMark != shared.studioMark) {
                    store.put(l.copy(unshared = false, sharedAt = if (l.unshared) now() else l.sharedAt, studioMark = shared.studioMark))
                }
                Pulled("same")
            }
            PullPlan.Update -> {
                store.put(GatewayShared.adopt(accountId, local, shared, null, now()))
                step(tr("ورکر مرکزی از دستگاه دیگر تازه شد — همین دستگاه هم همان را به کار می‌برد.", "The central Worker was updated from the other device — this one uses it too."))
                Pulled("updated", true)
            }
        }
    }

    /** Write this device's record to the account. Never throws: a registry that refuses must not stop an install. */
    private fun publishShared(rec: GatewayRecord, step: (String) -> Unit): Boolean = try {
        cloud.registryPut(rec.script, rec.url, GatewayShared.toS(rec))
        store.get()?.let { store.put(it.copy(unshared = false, sharedAt = now(), sharedBy = cloud.by)) }
        true
    } catch (e: Exception) {
        step(tr("ثبت در حساب نشد (${e.message}) — ورکر کار می‌کند، ولی ویندوز از آن خبر ندارد.", "Could not write to the account (${e.message}) — the Worker works, but Windows does not know about it."))
        store.get()?.let { store.put(it.copy(unshared = true)) }
        false
    }

    /** Put the account's record back to what it held before a change the Worker then refused. */
    private fun restoreShared(before: GatewayRecord?, own: String, step: (String) -> Unit) {
        try {
            if (before != null) cloud.registryPut(before.script, before.url, GatewayShared.toS(before)) else cloud.registryRemove(own)
        } catch (e: Exception) {
            step(tr("ثبت حساب به حالت قبل برنگشت (${e.message}) — هر دستگاهی که ورکر را بروز کند آن را درست می‌کند.", "The account's record did not go back (${e.message}) — the next device to update the Worker fixes it."))
        }
    }

    /**
     * The account's record ↔ this device, without changing the Worker: the other device's Worker is
     * taken in, or — when only this device has one — it is published so the other finds it.
     */
    fun sync(step: (String) -> Unit = {}): Pair<Pulled, GatewayStatus> {
        val r = pull(step, force = true)
        var state = r.state
        val local = store.get()
        if (local != null && (state == "none" || state == "dead")) {
            val alive = try { cloud.scriptExists(local.script) } catch (e: Exception) { false }
            if (alive && publishShared(local, step)) state = "published"
        }
        return r.copy(state = state) to status()
    }

    // ── deploy and everything that goes through it ───────────────────────────────────────

    /**
     * Deploy, or update in place. A second call for the same account while one runs is refused.
     * [fallback] null keeps what is set (the default on a first install); [rotate] makes a new id and
     * path (every old link stops working); [panelChange] connects or disconnects ONE panel in the
     * same upload. [studio] is the list of «کانفیگ استدیو» ids when the Studio itself is uploading;
     * null asks [studioIds], so an upload for any other reason still carries them.
     */
    fun deploy(
        fallback: String? = null,
        rotate: Boolean = false,
        panelChange: PanelChange? = null,
        onStep: (String) -> Unit = {},
        studio: List<String>? = null,
    ): GatewayStatus {
        if (!BUSY.add(accountId)) throw GatewayException("نصب درگاه برای این حساب هم‌اکنون در حال انجام است.", "An install is already running for this account.")
        try {
            val step = { m: String -> try { onStep(m) } catch (_: Exception) { /* a log sink must not stop a deploy */ } }
            val pulled = pull(step, force = true)
            val prev = store.get()
            val backup = if (fallback != null) Gateway.normalizeFallback(fallback) else (prev?.fallback ?: Gateway.DEFAULT_FALLBACK)

            // A panel already connected (or already gone) after the pull needs no upload at all.
            val has = { code: String -> prev?.panels?.containsKey(code) == true }
            panelChange?.add?.let { if (has(it)) return viewOf(prev) }
            panelChange?.remove?.let { if (!has(it)) return viewOf(prev) }

            if (prev == null) step(tr("این حساب هنوز ورکر مرکزی ندارد؛ یکی ساخته می‌شود (فقط یک‌بار — همهٔ پنل‌ها و ویندوز هم از همین استفاده می‌کنند).",
                "This account has no central Worker yet; one is being made (once — every panel and Windows use it too)."))
            step(tr("بررسی زیردامنهٔ workers.dev…", "Checking the workers.dev subdomain…"))
            val sub = cloud.subdomain()

            val script = prev?.script ?: cloud.workerName()
            val keep = prev != null && !rotate
            val uuid = if (keep) prev!!.uuid else cloud.randomUuid()
            val wsPath = if (keep) prev!!.wsPath else cloud.randomPath()
            // The panels connected to this Worker keep their ids on an update; a new id and path
            // (rotate) replaces every one of them, so every old link of every panel stops working.
            val panels = LinkedHashMap<String, PanelId>()
            for ((code, p) in prev?.panels.orEmpty()) panels[code] = if (keep) p else PanelId(cloud.randomUuid(), p.at)
            panelChange?.add?.let { panels[it] = PanelId(cloud.randomUuid(), now()) }
            panelChange?.remove?.let { panels.remove(it) }

            val files = modules()
            val hash = Gateway.codeHash(files)

            // «کانفیگ استدیو»'s people ride the same id list (data/studio/central): after BPB's own id
            // and the panels', so a list too long for the Worker leaves a person out, never a panel —
            // the Worker keeps only the first [max] ids it reads. A panel that would push a person off
            // is refused instead: the operator moves someone first.
            val max = Gateway.maxIdsOf(files)
            val wanted = (studio ?: try {
                studioIds(script)
            } catch (e: GatewayException) {
                // The provider knows people are on the Worker and cannot say who: uploading now
                // would take them off, so nothing is uploaded and the screen says why.
                throw e
            } catch (e: Exception) {
                step(tr("فهرست کاربران استدیو خوانده نشد (${e.message}) — این بار بدون آن‌ها بارگذاری می‌شود.",
                    "The Studio's people could not be read (${e.message}) — this upload goes without them."))
                emptyList()
            }).map { it.trim().lowercase() }
                .filter { id -> Gateway.UUID_RE.matches(id) && !id.equals(uuid, ignoreCase = true) && panels.values.none { it.uuid.equals(id, ignoreCase = true) } }
                .distinct()
            val room = (max - 1 - panels.size).coerceAtLeast(0)
            if (panelChange?.add != null && wanted.size > room) throw GatewayException(
                "ورکر مرکزی جای شناسهٔ تازه ندارد: ${wanted.size} کاربر «کانفیگ استدیو» روی آن هستند. اول یکی را به حساب دیگری ببرید.",
                "The central Worker has no room for another id: ${wanted.size} Config Studio people are on it. Move one to another account first.",
            )
            val studioOn = wanted.take(room)
            if (studioOn.size < wanted.size) step(tr("${wanted.size - studioOn.size} کاربر استدیو روی این ورکر جا نشد.",
                "${wanted.size - studioOn.size} Studio people did not fit on this Worker."))

            val host = "$script.$sub.workers.dev"
            val next = GatewayRecord(
                accountId = accountId, script = script, host = host, uuid = uuid, wsPath = wsPath, fallback = backup, codeHash = hash, at = now(),
                panels = panels, settings = prev?.settings ?: GatewaySettings.DEFAULT,
                fetchedAt = if (keep) prev!!.fetchedAt else 0L, configCount = if (keep) prev!!.configCount else 0,
                resolved = prev?.resolved, sharedAt = prev?.sharedAt ?: 0L, sharedBy = prev?.sharedBy.orEmpty(), unshared = prev?.unshared ?: false,
                replaced = pulled.replaced ?: prev?.replaced,
                studioMark = Gateway.idsMarker(studioOn),
            )

            // 3. publish before the upload: the account then names every id the Worker will accept.
            val before = if (pulled.state in SYNCED && prev != null) prev else null
            var published = false
            try {
                cloud.registryPut(script, next.url, GatewayShared.toS(next))
                published = true
            } catch (e: Exception) {
                step(tr("ثبت در حساب نشد (${e.message}) — ورکر کار می‌کند، ولی ویندوز از آن خبر ندارد.", "Could not write to the account (${e.message}) — the Worker works, but Windows does not know about it."))
            }

            // 4. upload — and if the Worker refuses, the account's record goes back to what it was.
            try {
                step(tr("بارگذاری ورکر (${files.size} ماژول)…", "Uploading the Worker (${files.size} modules)…"))
                val bindings = mutableListOf(
                    JSONObject().put("type", "secret_text").put("name", "UUID").put("text", Gateway.idsText(uuid, panels, studioOn)),
                    JSONObject().put("type", "plain_text").put("name", "WS_PATH").put("text", wsPath),
                )
                if (backup.isNotEmpty()) bindings.add(JSONObject().put("type", "plain_text").put("name", "FALLBACK").put("text", backup))
                cloud.upload(script, files, bindings)
                step(tr("فعال کردن نشانی workers.dev…", "Enabling the workers.dev address…"))
                cloud.enableWorkersDev(script)
            } catch (e: Exception) {
                if (published) restoreShared(before, script, step)
                throw e
            }

            val saved = if (published) next.copy(sharedAt = now(), sharedBy = cloud.by, unshared = false) else next.copy(unshared = true)
            store.put(saved)
            pulledAt[accountId] = now()
            step(tr("نصب شد: ${saved.url}", "Installed: ${saved.url}"))
            return viewOf(saved)
        } finally {
            BUSY.remove(accountId)
        }
    }

    /**
     * Connect a panel (Edge, Zeus, Spider, …) to the account's ONE Worker. The Worker is installed
     * here when the account has none yet — once, for every panel that comes after — and the panel gets
     * an id of its own on it; no panel code is deployed, nothing new appears on the account.
     */
    fun connectPanel(code: String, onStep: (String) -> Unit = {}): GatewayStatus {
        val c = code.uppercase()
        if (c !in Gateway.PANEL_CODES) throw GatewayException("پنل ناشناخته: $code", "Unknown panel: $code")
        val rec = store.get()
        if (rec == null) onStep(tr("اگر این حساب هنوز ورکر مرکزی نداشته باشد، همین حالا نصب می‌شود (فقط یک‌بار، برای همهٔ پنل‌ها و ویندوز)…",
            "If this account has no central Worker yet, it is installed now (once, for every panel and Windows)…"))
        else if (rec.panels[c] == null) onStep(tr("وصل کردن ${Gateway.PANEL_TITLE[c]} به همان ورکر (شناسهٔ تازه، بدون نصب ورکر دیگر)…",
            "Connecting ${Gateway.PANEL_TITLE[c]} to the same Worker (a new id, no second Worker)…"))
        return deploy(panelChange = PanelChange(add = c), onStep = onStep)
    }

    /** Take a panel's id off the Worker (its links stop working, every other panel's keep working). */
    fun disconnectPanel(code: String, onStep: (String) -> Unit = {}): GatewayStatus {
        val c = code.uppercase()
        if (c !in Gateway.PANEL_CODES) throw GatewayException("پنل ناشناخته: $code", "Unknown panel: $code")
        return deploy(panelChange = PanelChange(remove = c), onStep = onStep)
    }

    /**
     * Save the BPB-style settings. Everything but the Proxy IP only shapes the links, so it is stored
     * here and in the account; a changed Proxy IP is a Worker variable and re-uploads the Worker. All of
     * it is validated before anything is written.
     */
    fun saveSettings(patch: SettingsPatch, fallback: String? = null, onStep: (String) -> Unit = {}): GatewayStatus {
        val step = { m: String -> try { onStep(m) } catch (_: Exception) { } }
        runCatching { pull(step, force = true) }
        val rec = store.get() ?: throw notInstalled
        val settings = GatewaySettings.normalize(patch, rec.settings)
        val backup = fallback?.let { Gateway.normalizeFallback(it) }
        val saved = rec.copy(settings = settings, at = now())
        store.put(saved)
        // A changed Proxy IP is a Worker variable: the upload publishes the record itself.
        if (backup != null && backup != rec.fallback) deploy(fallback = backup, onStep = onStep)
        else publishShared(saved, step)
        return status()
    }

    // ── the links ────────────────────────────────────────────────────────────────────────

    private fun resolveWorker(host: String, cached: Resolved?, refresh: Boolean): Triple<String, Resolved?, DohAnswer> {
        if (cached != null && !refresh && now() - cached.at < Gateway.RESOLVE_TTL_MS) return Triple("cache", cached, DohAnswer(cached.v4, cached.v6))
        val live = runCatching { lookup(host) }.getOrDefault(DohAnswer(emptyList(), emptyList()))
        if (live.v4.isNotEmpty() || live.v6.isNotEmpty()) return Triple("live", Resolved(now(), live.v4, live.v6), live)
        if (cached != null && (cached.v4.isNotEmpty() || cached.v6.isNotEmpty())) return Triple("stale", cached, DohAnswer(cached.v4, cached.v6))
        return Triple("none", null, DohAnswer(emptyList(), emptyList()))
    }

    /**
     * The links, built here — no request to the Worker. Like BPB's subscription they list the Worker's
     * name, then (settings permitting) the addresses that name resolves to, then the clean IPs/domains,
     * each on every chosen port. The only network call is the DNS-over-HTTPS lookup of the name, and a
     * failure of that still returns the rest. [panel] picks whose id the links carry (BPB by default).
     */
    fun configs(panel: String = "BPB", ports: List<Int>? = null, refresh: Boolean = false): ConfigsResult {
        // The other device may have connected this panel (or renewed the ids) since the last look; a
        // Worker the other device installed is used as it is. A failure leaves this device's copy to work.
        runCatching { pull(force = false) }
        val stored = store.get() ?: throw notInstalled
        val code = panel.uppercase().ifBlank { "BPB" }
        val id = stored.idOf(code)
        if (code != "BPB" && id == null) {
            val title = Gateway.PANEL_TITLE[code] ?: code
            throw GatewayException("پنل $title به ورکر مرکزی وصل نیست.", "$title is not connected to the central Worker.")
        }
        val label = Gateway.PANEL_TITLE[code] ?: code
        val (result, keep) = build(stored, id!!, label, ports, refresh)
        val stamp = now()
        val all = result.configs
        val next = if (code == "BPB") stored.copy(fetchedAt = stamp, configCount = all.size, resolved = keep)
        else stored.copy(panels = stored.panels + (code to stored.panels.getValue(code).copy(fetchedAt = stamp, configCount = all.size)), resolved = keep)
        store.put(next)
        return result
    }

    /**
     * One «کانفیگ استدیو» person's links: the very set [configs] builds for a panel (the Worker's
     * name, its addresses, the clean IPs, the anti-filter methods), carrying THEIR id and named
     * after them. No request to the Worker; the id must be one the Studio put on it.
     */
    fun linksFor(id: String, label: String, refresh: Boolean = false): ConfigsResult {
        runCatching { pull(force = false) }
        val stored = store.get() ?: throw notInstalled
        if (!Gateway.UUID_RE.matches(id)) throw GatewayException("شناسهٔ کاربر معتبر نیست.", "The person's id is not valid.")
        val (result, keep) = build(stored, id.lowercase(), label.ifBlank { "user" }, null, refresh)
        if (keep != stored.resolved) store.put(stored.copy(resolved = keep))
        return result
    }

    /** The links of [id] on the Worker [stored] describes, and the resolved addresses to keep. */
    private fun build(stored: GatewayRecord, id: String, label: String, ports: List<Int>?, refresh: Boolean): Pair<ConfigsResult, Resolved?> {
        val settings = stored.settings
        val addresses = mutableListOf(GatewayLinks.Addr(stored.host, "domain"))
        var from = "off"
        var keep: Resolved? = stored.resolved
        var answer = DohAnswer(emptyList(), emptyList())
        if (settings.withResolved) {
            val (f, r, a) = resolveWorker(stored.host, stored.resolved, refresh)
            from = f
            answer = a
            if (f == "live") keep = r
            a.v4.forEach { addresses.add(GatewayLinks.Addr(it, "ipv4")) }
            if (settings.ipv6) a.v6.forEach { addresses.add(GatewayLinks.Addr(it, "ipv6")) }
        }
        for (c in settings.cleanIPs) {
            val k = Gateway.classifyAddress(c) ?: continue
            addresses.add(GatewayLinks.Addr(k.address, if (k.kind == "domain") "clean" else k.kind))
        }
        val list = GatewayLinks.buildConfigs(stored.host, id, stored.wsPath, ports?.takeIf { it.isNotEmpty() } ?: settings.ports, addresses, label)
        // The anti-filter methods, as ready configs: ECH, F&F and — with IPv6 — IPv6, each on the
        // developer's example edge, so a link can also be used in another client as it is.
        val short = stored.host.substringBefore('.')
        val base = list.firstOrNull { Regex("@[^:/?#]+:443\\?").containsMatchIn(it) } ?: list.first()
        val methodLinks = if (settings.methods) GatewayLinks.methodVariants(base, settings.ipv6, label, short) else emptyList()
        val all = list + methodLinks.map { it.uri }
        return ConfigsResult(all, from, answer.v4.size, answer.v6.size, methodLinks) to keep
    }

    /**
     * Remove the account's central Worker (the other device's too, when it installed it: the account's
     * record is taken in first). The account's record goes with it, so the other device does not keep
     * pointing at a Worker that is gone.
     */
    fun remove(): Boolean {
        runCatching { pull(force = true) }
        val rec = store.get() ?: return false
        cloud.removeWorker(rec.script)
        store.del()
        runCatching { cloud.registryRemove(rec.script) }
        return true
    }

    companion object {
        /** An install runs once per account at a time, whichever screen asked. */
        private val BUSY: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val PULLED_AT = ConcurrentHashMap<String, Long>()
        /** The pull states after which the registry's content is this device's `before` for a rollback. */
        private val SYNCED = setOf("adopted", "same", "updated", "pushed")

        /** Test hook: forget what a previous test left behind. */
        fun resetForTest() { BUSY.clear(); PULLED_AT.clear() }

        /** Two-language text without Android: the screens pass the user's language in through [language]. */
        @Volatile var language: () -> Boolean = { java.util.Locale.getDefault().language == "fa" }
    }

    private fun tr(fa: String, en: String): String = if (language()) fa else en
}
