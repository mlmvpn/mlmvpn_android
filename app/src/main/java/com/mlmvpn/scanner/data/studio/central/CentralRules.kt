package com.mlmvpn.scanner.data.studio.central

import com.mlmvpn.scanner.data.studio.api.RenewMode
import com.mlmvpn.scanner.data.studio.domain.Plan

/**
 * What can be done to the people of one account, as pure functions: a [CentralDoc] in, a new one
 * out, or a [RuleError] with the same stable code an engine would have answered (`username_taken`,
 * `not_found`, `bad_request`) plus `no_room` — the central Worker carries a fixed number of ids.
 *
 * Nothing here touches the network or the clock; the store does both, and applies the result only
 * once the Worker has taken it (see CentralStore). So every rule below is tested as plain JVM code.
 */
object CentralRules {

    class RuleError(val code: String, val detail: String? = null) : Exception(code)

    const val NO_ROOM = "no_room"
    const val TAKEN = "username_taken"
    const val NOT_FOUND = "not_found"
    const val BAD_REQUEST = "bad_request"

    /** Whether [name] is used by someone in [doc] who is not a tombstone (letter case ignored). */
    fun nameTaken(doc: CentralDoc, name: String, except: String? = null): Boolean {
        val n = Central.cleanName(name).lowercase()
        return doc.people.any { it.id != except && it.name.lowercase() == n }
    }

    /** A fresh Worker id no record of [doc] has ever used — a tombstone's id never comes back. */
    private fun freshGw(doc: CentralDoc, newGw: () -> String, also: Set<String> = emptySet()): String {
        val used = doc.users.flatMap { listOf(it.gw) + it.slots }.toHashSet() + also
        repeat(16) {
            val g = newGw().trim().lowercase()
            if (Central.isId(g) && g !in used) return g
        }
        throw RuleError(BAD_REQUEST, "no fresh id")
    }

    private fun requireRoom(doc: CentralDoc, now: Long, capacity: Int) {
        if (doc.liveCount(now) >= capacity) throw RuleError(NO_ROOM)
    }

    /**
     * A change that puts more ids on the Worker than were there needs the room for them: a person
     * back inside their term, switched on again, or given more device places. Fewer never does.
     */
    private fun requireRoomFor(before: CentralDoc, next: CentralDoc, now: Long, capacity: Int) {
        val after = next.liveCount(now)
        if (after > before.liveCount(now) && after > capacity) throw RuleError(NO_ROOM)
    }

    private fun live(doc: CentralDoc, id: String): CentralUser =
        doc.user(id)?.takeIf { !it.isDeleted } ?: throw RuleError(NOT_FOUND)

    private fun replace(doc: CentralDoc, user: CentralUser): CentralDoc =
        doc.copy(users = doc.users.map { if (it.id == user.id) user else it })

    /**
     * A new person, on from [now] until [expiresAt] (0 = no end). Refused when the name is in use or,
     * since they would pass the Worker at once, when it has no room left ([capacity] Studio ids).
     */
    fun create(
        doc: CentralDoc,
        name: String,
        expiresAt: Long,
        now: Long,
        capacity: Int,
        newId: () -> String,
        newGw: () -> String,
        planId: String? = null,
        note: String? = null,
        tags: List<String> = emptyList(),
        movedFrom: String? = null,
    ): Pair<CentralDoc, CentralUser> {
        val clean = Central.cleanName(name)
        if (clean.isEmpty()) throw RuleError(BAD_REQUEST, "no name")
        if (nameTaken(doc, clean)) throw RuleError(TAKEN)
        if (expiresAt in 1..now) throw RuleError(BAD_REQUEST, "already over")
        requireRoom(doc, now, capacity)
        if (doc.users.size >= Central.MAX_USERS) throw RuleError(NO_ROOM, "record full")
        val used = doc.users.map { it.id }.toHashSet()
        var id = newId()
        var tries = 0
        while (id in used && tries++ < 16) id = newId()
        if (id in used) throw RuleError(BAD_REQUEST, "no fresh local id")
        val user = CentralUser(
            id = id, name = clean, gw = freshGw(doc, newGw), enabled = true,
            note = note?.trim()?.take(Central.MAX_NOTE)?.takeIf { it.isNotEmpty() },
            tags = Central.cleanTags(tags), planId = planId?.takeIf { it.isNotBlank() },
            expiresAt = expiresAt.coerceAtLeast(0), createdAt = now, updatedAt = now, movedFrom = movedFrom,
        )
        return doc.copy(users = doc.users + user) to user
    }

    /**
     * A renewal, with an engine's arithmetic (worker-src/studio/04b-studio-users.js) so a person reads
     * the same on both kinds of account:
     *  - EXTEND moves the end from `max(now, end)`, and [addDays] may be negative (the end never
     *    goes below [now]);
     *  - RESTART starts the term again from [now];
     *  - RESET_USAGE has nothing to reset here — there is no meter — and changes nothing.
     * A renewal that gives something switches the person back on; a correction does not.
     * [planDays] fills in the days when none were typed, as the engine does with `plan_id`.
     */
    fun renew(
        doc: CentralDoc,
        id: String,
        addDays: Int?,
        mode: RenewMode,
        now: Long,
        capacity: Int,
        planDays: Int? = null,
        planId: String? = null,
    ): Pair<CentralDoc, CentralUser> {
        val before = live(doc, id)
        if (mode == RenewMode.RESET_USAGE) return doc to before
        val days = addDays?.takeIf { it != 0 } ?: planDays?.takeIf { it > 0 } ?: 0
        if (days == 0) throw RuleError(BAD_REQUEST, "renewal needs days")
        if (mode == RenewMode.RESTART && days < 0) throw RuleError(BAD_REQUEST, "restart cannot take back")
        val base = if (mode == RenewMode.RESTART) now else maxOf(now, before.expiresAt)
        val expiresAt = maxOf(now, base + days * Central.DAY_MS)
        val grant = mode != RenewMode.EXTEND || days > 0
        val after = before.copy(
            expiresAt = expiresAt,
            enabled = if (grant) true else before.enabled,
            planId = planId?.takeIf { it.isNotBlank() } ?: before.planId,
            updatedAt = now,
        )
        val next = replace(doc, after)
        requireRoomFor(doc, next, now, capacity)
        return next to after
    }

    fun setEnabled(doc: CentralDoc, id: String, enabled: Boolean, now: Long, capacity: Int): Pair<CentralDoc, CentralUser> {
        val before = live(doc, id)
        if (before.enabled == enabled) return doc to before
        val after = before.copy(enabled = enabled, updatedAt = now)
        val next = replace(doc, after)
        requireRoomFor(doc, next, now, capacity)
        return next to after
    }

    /**
     * A new link: new Worker ids for this person alone -- their own and every device place -- and,
     * when they have a subscription, a new address for it: a link that leaked would otherwise hand
     * the new ids to whoever holds it at their app's next refresh. The old ones stop at the next upload.
     */
    fun rotate(doc: CentralDoc, id: String, now: Long, newGw: () -> String, newToken: () -> String = Central::newToken): Pair<CentralDoc, CentralUser> {
        val before = live(doc, id)
        val gw = freshGw(doc, newGw)
        val taken = HashSet<String>().apply { add(gw) }
        val slots = before.slots.map { freshGw(doc, newGw, taken).also { taken.add(it) } }
        val after = before.copy(gw = gw, slots = slots, token = before.token?.let { newToken() }, rotatedAt = now, updatedAt = now)
        return replace(doc, after) to after
    }

    /**
     * «محدودیت دستگاه»: [limit] device places, each with an id of its own on the Worker (0 = none:
     * everyone shares the person's own id). Places that stay keep their ids -- their devices go on
     * working; new places get fresh ones; places past a lower limit are gone with their devices. The
     * person's own id leaves the Worker while a limit is on: a device has to come through the
     * subscription to be counted. More places need the room for them.
     */
    fun setDevices(
        doc: CentralDoc, id: String, limit: Int, noHwid: String, now: Long, capacity: Int, newGw: () -> String,
    ): Pair<CentralDoc, CentralUser> {
        val before = live(doc, id)
        if (limit < 0 || limit > Central.MAX_DEVICES) throw RuleError(BAD_REQUEST, "device limit")
        val policy = if (noHwid == Central.NOHWID_BLOCK) Central.NOHWID_BLOCK else Central.NOHWID_ALLOW
        val taken = HashSet<String>()
        val slots = if (limit == 0) emptyList() else {
            val kept = before.slots.take(limit)
            taken.addAll(kept)
            kept + List(limit - kept.size) { freshGw(doc, newGw, taken).also { taken.add(it) } }
        }
        val after = before.copy(deviceLimit = limit, slots = slots, noHwid = policy, updatedAt = now)
        if (after == before.copy(updatedAt = now)) return doc to before
        val next = replace(doc, after)
        requireRoomFor(doc, next, now, capacity)
        return next to after
    }

    /**
     * A device taken off a place («حذف دستگاه»): the place gets a fresh id, so the configs that device
     * holds stop at the next upload, and the place is free for the next device that comes. [deviceKey]
     * (the sub Worker's name for it) is refused from then on -- or it would walk back into the very
     * place it left at its app's next refresh.
     */
    fun freeDevicePlace(doc: CentralDoc, id: String, place: Int, now: Long, newGw: () -> String, deviceKey: String? = null): Pair<CentralDoc, CentralUser> {
        val before = live(doc, id)
        if (place !in before.slots.indices) throw RuleError(NOT_FOUND, "place")
        val slots = before.slots.toMutableList().also { it[place] = freshGw(doc, newGw) }
        val banned = deviceKey?.takeIf { Central.DEVICE_KEY_RE.matches(it) }
            ?.let { (before.banned - it + it).takeLast(Central.MAX_BANNED) } ?: before.banned
        val after = before.copy(slots = slots, banned = banned, updatedAt = now)
        return replace(doc, after) to after
    }

    /** «پاک کردن همهٔ دستگاه‌ها»: every place a fresh id, nobody refused -- the devices start over. */
    fun resetDevices(doc: CentralDoc, id: String, now: Long, newGw: () -> String): Pair<CentralDoc, CentralUser> {
        val before = live(doc, id)
        val taken = HashSet<String>()
        val slots = before.slots.map { freshGw(doc, newGw, taken).also { taken.add(it) } }
        val after = before.copy(slots = slots, banned = emptyList(), updatedAt = now)
        return replace(doc, after) to after
    }

    /**
     * «ساب چندلوکیشنی»: the person's exit countries. Each needs at least one proxy, at most
     * [Central.MAX_PROXIES]; a fixed one exactly one (a constant exit IP is one proxy's IP).
     */
    fun setLocations(doc: CentralDoc, id: String, locations: List<CentralLocation>, now: Long): Pair<CentralDoc, CentralUser> {
        val before = live(doc, id)
        if (locations.size > Central.MAX_LOCATIONS) throw RuleError(BAD_REQUEST, "too many locations")
        if (locations.map { it.cc }.toSet().size != locations.size) throw RuleError(BAD_REQUEST, "a country twice")
        for (l in locations) {
            if (!Regex("^[A-Z]{2}$").matches(l.cc)) throw RuleError(BAD_REQUEST, "country")
            if (l.proxies.isEmpty() || l.proxies.size > Central.MAX_PROXIES) throw RuleError(BAD_REQUEST, "proxies")
            if (l.fixed && l.proxies.size != 1) throw RuleError(BAD_REQUEST, "a fixed IP is one proxy")
            if (l.proxies.any { !ExitProxy.valid(it.ip, it.port, it.kind) }) throw RuleError(BAD_REQUEST, "proxy")
            if (l.proxies.map { it.key }.toSet().size != l.proxies.size) throw RuleError(BAD_REQUEST, "a proxy twice")
        }
        val after = before.copy(locations = locations, updatedAt = now)
        return replace(doc, after) to after
    }

    /** The person's subscription address, made once: an existing one is kept (it is in their app). */
    fun ensureToken(doc: CentralDoc, id: String, now: Long, newToken: () -> String = Central::newToken): Pair<CentralDoc, CentralUser> {
        val before = live(doc, id)
        if (before.token != null) return doc to before
        val used = doc.users.mapNotNull { it.token }.toHashSet()
        var t = newToken()
        repeat(8) { if (t in used) t = newToken() }
        if (!Central.TOKEN_RE.matches(t) || t in used) throw RuleError(BAD_REQUEST, "token")
        val after = before.copy(token = t, updatedAt = now)
        return replace(doc, after) to after
    }

    fun delete(doc: CentralDoc, id: String, now: Long): CentralDoc {
        val before = live(doc, id)
        return replace(doc, before.asTombstone(now))
    }

    /**
     * Name, note, tags, «بسته» and end date; null leaves a field as it is. A changed end date that
     * puts the person back inside their term needs room on the Worker, like any other way back.
     */
    fun patch(
        doc: CentralDoc,
        id: String,
        now: Long,
        capacity: Int,
        name: String? = null,
        note: String? = null,
        tags: List<String>? = null,
        planId: String? = null,
        expiresAt: Long? = null,
        enabled: Boolean? = null,
    ): Pair<CentralDoc, CentralUser> {
        val before = live(doc, id)
        val newName = name?.let { Central.cleanName(it) }
        if (newName != null) {
            if (newName.isEmpty()) throw RuleError(BAD_REQUEST, "no name")
            if (nameTaken(doc, newName, except = id)) throw RuleError(TAKEN)
        }
        val after = before.copy(
            name = newName ?: before.name,
            note = if (note != null) note.trim().take(Central.MAX_NOTE).takeIf { it.isNotEmpty() } else before.note,
            tags = if (tags != null) Central.cleanTags(tags) else before.tags,
            planId = if (planId != null) planId.takeIf { it.isNotBlank() } else before.planId,
            expiresAt = expiresAt?.coerceAtLeast(0) ?: before.expiresAt,
            enabled = enabled ?: before.enabled,
            updatedAt = now,
        )
        if (after == before.copy(updatedAt = now)) return doc to before
        val next = replace(doc, after)
        requireRoomFor(doc, next, now, capacity)
        return next to after
    }

    /** Create or replace a «بسته» under the id the caller chose — the fleet writes the same one everywhere. */
    fun putPlan(doc: CentralDoc, plan: Plan, now: Long): CentralDoc {
        val stamped = plan.copy(presentOn = emptySet(), updatedAt = maxOf(plan.updatedAt, now), createdAt = plan.createdAt.takeIf { it > 0 } ?: now)
        val others = doc.plans.filter { it.id != plan.id }
        // One default across the account, as on an engine: choosing a new one clears the old.
        val rest = if (stamped.isDefault) others.map { if (it.isDefault) it.copy(isDefault = false, updatedAt = now) else it } else others
        if (rest.size >= Central.MAX_PLANS) throw RuleError(BAD_REQUEST, "too many plans")
        return doc.copy(plans = rest + stamped)
    }

    fun archivePlan(doc: CentralDoc, id: String, now: Long): CentralDoc {
        val p = doc.plans.firstOrNull { it.id == id } ?: throw RuleError(NOT_FOUND)
        if (p.archived) return doc
        return doc.copy(plans = doc.plans.map { if (it.id == id) it.copy(archived = true, isDefault = false, updatedAt = now) else it })
    }

    /** How many people on this account hold [planId]. */
    fun countPlan(doc: CentralDoc, planId: String): Int = doc.people.count { it.planId == planId }
}
