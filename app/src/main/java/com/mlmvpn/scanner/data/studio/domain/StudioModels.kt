package com.mlmvpn.scanner.data.studio.domain

/**
 * What the app talks about. Not what the worker sends.
 *
 * The boundary is the point: DTOs live in `data/studio/api/dto/` and are mapped here at the edge, so
 * a screen never holds a type whose field names are a wire format. It costs a mapper; it buys the
 * ability to change the API — rename a field, split an object, add a version — without the change
 * reaching into composables.
 *
 * The rule that keeps it honest, and it is greppable: **nothing under `ui/configstudio/` may import
 * `data.studio.api.dto`.**
 *
 * Two conventions run through all of it:
 *  * **Bytes, never GB floats.** `limit_gb REAL` cannot express exactly 30 GB, and someone given 30
 *    who is shown 29.7 has a question that costs more to answer than a Long costs to carry.
 *  * **Absolute milliseconds, never "days from creation".** The legacy schema counted expiry from
 *    `created_at`, which made renewing impossible without deleting and recreating the user — and
 *    that loses their link.
 */

enum class UserStatus { ACTIVE, DISABLED, EXPIRED, DELETED, UNKNOWN;
    companion object {
        fun from(s: String?): UserStatus = when (s?.lowercase()) {
            "active" -> ACTIVE
            "disabled" -> DISABLED
            "expired" -> EXPIRED
            "deleted" -> DELETED
            else -> UNKNOWN
        }
    }
}

enum class ExpiryMode { ABSOLUTE, ON_FIRST_CONNECT;
    companion object {
        fun from(s: String?): ExpiryMode =
            if (s == "on_first_connect") ON_FIRST_CONNECT else ABSOLUTE
    }
}

enum class ResetPolicy { NONE, DAILY, WEEKLY, MONTHLY;
    companion object {
        fun from(s: String?): ResetPolicy = when (s?.lowercase()) {
            "daily" -> DAILY
            "weekly" -> WEEKLY
            "monthly" -> MONTHLY
            else -> NONE
        }
    }
}

/**
 * Whether the caps on this user are actually applied.
 *
 * Reported per installation rather than assumed, because device / concurrent / IP limits need a
 * Durable Object and one may not be deployed. A cap drawn as working when it is advisory is worse
 * than one drawn as advisory: the operator makes a promise the engine cannot keep.
 */
enum class Enforcement { STRICT, SOFT;
    companion object {
        fun from(s: String?): Enforcement = if (s == "strict") STRICT else SOFT
    }
}

data class SubscriptionPolicy(
    val expiryMode: ExpiryMode = ExpiryMode.ABSOLUTE,
    val expiresAt: Long? = null,
    val activationDays: Int? = null,
    val firstConnectAt: Long? = null,
    val quotaBytes: Long? = null,
    val dailyQuotaBytes: Long? = null,
    val reset: ResetPolicy = ResetPolicy.NONE,
    val quotaResetAt: Long? = null,
    val deviceLimit: Int? = null,
    val connLimit: Int? = null,
    val ipLimit: Int? = null,
    val enforcement: Enforcement = Enforcement.SOFT,
) {
    val isUnlimitedVolume: Boolean get() = quotaBytes == null || quotaBytes <= 0
    val isNeverExpiring: Boolean get() = expiresAt == null || expiresAt <= 0
}

data class TrafficUsage(
    val usedBytes: Long = 0,
    val dailyUsedBytes: Long = 0,
) {
    /** Null when there is no quota to be a fraction of — not zero, which would draw an empty bar. */
    fun fractionOf(quotaBytes: Long?): Float? {
        if (quotaBytes == null || quotaBytes <= 0) return null
        return (usedBytes.toDouble() / quotaBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    fun remainingOf(quotaBytes: Long?): Long? =
        if (quotaBytes == null || quotaBytes <= 0) null else (quotaBytes - usedBytes).coerceAtLeast(0)
}

data class StudioUser(
    val id: String,
    val username: String,
    val credential: String?,
    val status: UserStatus,
    val note: String? = null,
    val tags: List<String> = emptyList(),
    val groupId: String? = null,
    val planId: String? = null,
    val policy: SubscriptionPolicy = SubscriptionPolicy(),
    val usage: TrafficUsage = TrafficUsage(),
    val createdAt: String? = null,
    /** The sync watermark. A write that fails to move it never reaches any other device. */
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
    val lastActiveAt: Long? = null,
    /** Countries of this person's configs (build 17). Non-empty = «مولتی لوکیشن». */
    val locations: List<String> = emptyList(),
    /** Ports their subscription is served on. Empty = the default, 443. */
    val ports: List<Int> = emptyList(),
    /**
     * Which installation holds this user. **Client-side only** — no worker knows the fleet exists.
     *
     * Every write goes to exactly one installation, which is what keeps a fleet of Cloudflare
     * accounts free of distributed transactions (plan §A.2).
     */
    val installationId: String = "",
) {
    val isDeleted: Boolean get() = deletedAt != null && deletedAt > 0

    /** Why this user is stopped, or null if they are not. Ordered by what can be done about it. */
    fun stoppedReason(now: Long = System.currentTimeMillis()): StoppedReason? = when {
        isDeleted -> StoppedReason.DELETED
        status == UserStatus.DISABLED -> StoppedReason.DISABLED
        policy.expiresAt != null && policy.expiresAt > 0 && now > policy.expiresAt -> StoppedReason.EXPIRED
        policy.quotaBytes != null && policy.quotaBytes > 0 && usage.usedBytes >= policy.quotaBytes -> StoppedReason.OUT_OF_VOLUME
        else -> null
    }
}

/** Distinct because the answers differ: one is renewed, one is topped up, one is switched back on. */
enum class StoppedReason { DISABLED, EXPIRED, OUT_OF_VOLUME, DELETED }

data class Subscription(
    val id: String,
    val userId: String,
    val token: String,
    val url: String,
    val pageUrl: String,
    val createdAt: Long = 0,
    val rotatedAt: Long? = null,
    val revokedAt: Long? = null,
    /**
     * Fetches, **sampled at most once per ten minutes** — not a fetch count.
     *
     * Named for what it is because writing per fetch would let one misbehaving client spend 1,440
     * of the day's 100,000 D1 row writes on a single person.
     */
    val hitSamples: Int = 0,
    val lastHitAt: Long? = null,
    val lastUserAgent: String? = null,
)

data class StudioConfig(
    val id: String,
    val userId: String,
    val label: String?,
    val enabled: Boolean,
    /**
     * One letter, as the engine stores it.
     *
     * Abbreviated on purpose: a plaintext protocol name in the worker's own code is the thing
     * Cloudflare's deploy-time scanner objects to, and a failure there looks like a generic upload
     * error that is easy to misdiagnose as a bindings problem (plan R2).
     */
    val protocol: String?,
    val transportType: String?,
    /**
     * The secret this config authenticates with, as the engine stores it.
     *
     * Read back so the app can render the config's own link without asking the subscription
     * endpoint for it — which is what «ترکیب» needs, because it combines one node per CONFIG and
     * the subscription emits one link per config per endpoint per port.
     */
    val credential: String?,
    val uriTemplate: String?,
    /** The path this config answers on. Null means the root, which is what a first config gets. */
    val routeKey: String? = null,
    /**
     * The endpoints this config is served on, or empty for every enabled one.
     *
     * Empty is the ordinary state and the one every config written before build 9 has — it is not
     * "unset" waiting to be filled in. A non-empty list is somebody having been deliberately put on
     * particular addresses, which is what «ترکیب با آی‌پی تمیز» does and what its undo clears.
     */
    val nodeIds: List<String> = emptyList(),
    val createdAt: Long = 0,
    /**
     * The country this config leaves from («لوکیشن»), or null for Cloudflare's own egress.
     *
     * Set only through an exit the installation has (`/v1/exits`); the engine never falls back to a
     * direct connection for a config that has one, so a country here is a promise, not a hint.
     */
    val exitCc: String? = null,
    /**
     * Where a location config really leaves from, as the engine measured it when it chose the exit
     * (build 18). Only on the answer to a create; null otherwise.
     */
    val exitIp: String? = null,
) {
    /** True when this config has been pinned to particular endpoints rather than served on all. */
    val isPinned: Boolean get() = nodeIds.isNotEmpty()
}

data class DashboardSnapshot(
    val total: Int = 0,
    val active: Int = 0,
    val expiringSoon: Int = 0,
    val expired: Int = 0,
    val overQuota: Int = 0,
    val trafficUpBytes24h: Long = 0,
    val trafficDownBytes24h: Long = 0,
    val trafficUpBytes30d: Long = 0,
    val trafficDownBytes30d: Long = 0,
    /**
     * People whose tunnel wrote in the last sixty-five seconds.
     *
     * Measured, not inferred. Sixty-five is not a rounded guess: the data plane refreshes a user's
     * `last_active` at most once per sixty-five seconds, so any tighter window would report a
     * connected person as gone in the gap between two of their own writes.
     *
     * **Null, not zero, on an installation that cannot answer it.** An engine older than build 7
     * sends no such field, and a screen that read the absence as zero would print "nobody is
     * connected" for an account with fifty people on it — the exact failure this codebase refuses
     * everywhere else it draws a number.
     */
    val online: Int? = null,
    /** Sessions that ENDED in the last day. Not the same question as [online], and cheaper. */
    val sessions24h: Int? = null,
    val configs: Int? = null,
    val nodesTotal: Int? = null,
    val nodesEnabled: Int? = null,
    val nodesDown: Int? = null,
    val enforcement: Enforcement = Enforcement.SOFT,
    val generatedAt: Long = 0,
) {
    /** Whether this snapshot came from an engine that answers the build-7 counts. */
    val hasV2Counts: Boolean get() = online != null || configs != null || nodesTotal != null
}

/**
 * Something the SYSTEM observed, as opposed to something a person decided.
 *
 * The companion to an audit entry, and separate from one on purpose: an audit row is a decision and
 * is kept for the life of the installation, while this is an observation — a tunnel refused, a
 * credential nobody owns — written by the data plane, useful for about a week, and pruned hard.
 *
 * [kind] is the engine's stable machine name (`tunnel.expired`, `tunnel.device_limit`). It is never
 * printed raw: the screen maps it to a sentence, for the same reason error codes are mapped, so the
 * engine can rename nothing and the app can reword everything.
 */
data class ActivityEntry(
    val id: Long,
    val ts: Long,
    val kind: String,
    val severity: ActivitySeverity,
    val userId: String? = null,
    /** Resolved by the engine, because a row about someone deleted since has only a bare id. */
    val username: String? = null,
    val detail: String? = null,
    val installationId: String = "",
)

enum class ActivitySeverity { INFO, WARN, ERROR;
    companion object {
        fun from(s: String?): ActivitySeverity = when (s?.lowercase()) {
            "error" -> ERROR
            "warn" -> WARN
            else -> INFO
        }
    }
}

/**
 * «نقطهٔ اتصال» — one address a subscription is served on.
 *
 * A node is **not** a shard. A shard is a Cloudflare account; a node is a clean IP with a port list
 * and a server name to present, and one account has many. The two are kept apart in the vocabulary
 * because either could loosely be called "a server", and blurring them makes every capacity
 * conversation ambiguous.
 *
 * Before nodes, the endpoint list lived in `users.ips`, **per person** — so moving traffic to a new
 * clean IP meant editing every subscriber one at a time.
 */
data class StudioNode(
    val id: String,
    val name: String,
    val host: String,
    val ports: List<Int> = listOf(443),
    val country: String? = null,
    /**
     * Free text, and used by nothing but the operator's own eyes.
     *
     * Separate from [country] because the two answer different questions: a country is what a
     * subscriber recognises in a server name, a city is what tells two clean IPs in the same
     * country apart on this screen. **Nothing routes by it** — no strategy reads it, and the
     * engine stores it without looking inside.
     */
    val city: String? = null,
    val sni: String? = null,
    val hostHeader: String? = null,
    val groupId: String? = null,
    /** Lower goes first. Same meaning as the column, and the order the subscription fans out in. */
    val priority: Int = 100,
    val enabled: Boolean = true,
    val health: NodeHealth = NodeHealth.UNKNOWN,
    val healthCheckedAt: Long? = null,
    /**
     * What the last probe measured, from the phone.
     *
     * Null rather than zero when nothing has probed yet — a latency of zero and a node nobody has
     * tested look identical otherwise, and only one of them is worth acting on.
     */
    val latencyMs: Int? = null,
    val lastError: String? = null,
    /** Client-side only, exactly as on [StudioUser]: which installation holds this row. */
    val installationId: String = "",
) {
    val portsLabel: String get() = ports.joinToString("، ")

    /** True when a probe has run and failed. An unprobed node is not a broken one. */
    val isDown: Boolean get() = health == NodeHealth.DOWN
}

/**
 * How a group hands its endpoints out.
 *
 * Every one of these is a rule for the **order** of a list, and that is the whole of what the
 * engine controls: a subscription is a list of links, nothing here proxies anything, and the client
 * on the far side is what picks one and what falls back. The client's own "try them in turn"
 * behaviour is what turns an order into an outcome.
 *
 * There is deliberately nothing here that claims to steer traffic. Every endpoint is a different
 * clean IP reaching the SAME worker, so there is no per-node load to move between them — which is
 * why [SPREAD] is named for what it does rather than called load balancing.
 */
enum class NodeStrategy(val wire: String) {
    /** The operator's own priority. The default, and what every installation did before groups. */
    ORDER("order"),

    /**
     * Endpoints the last probe found down sink to the bottom of the group.
     *
     * Sink, never drop. The probe ran on one phone, on one network, at one moment, and the cost of
     * being wrong is not symmetric: a node wrongly kept is a dead entry in a server list, while a
     * node wrongly dropped is somebody's only working link disappearing with nothing to explain it.
     */
    HEALTHY("healthy"),

    /**
     * Ordered by the latency the OPERATOR'S phone measured, not the subscriber's.
     *
     * Said that way on the screen too, because it is the difference between a useful hint and a
     * false promise: one device on one network is what produced these numbers.
     */
    FASTEST("fastest"),

    /**
     * The first endpoint rotates per subscriber, stably.
     *
     * Nothing is balanced across — see the note above. What it does fix is real: with one fixed
     * order every client tries the same endpoint first, so an endpoint that gets blocked takes
     * everybody with it at the same moment.
     */
    SPREAD("spread");

    companion object {
        fun from(s: String?): NodeStrategy = entries.firstOrNull { it.wire == s } ?: ORDER
    }
}

/**
 * «گروه نقطه» — a set of endpoints and one rule for the order they are handed out in.
 *
 * [enabled] is the property worth having: switching a group off takes every endpoint in it out of
 * the fan-out without touching the endpoints themselves, which turns "stop using this region for an
 * hour" into one action and one undo rather than six edits that have to be remembered.
 */
data class NodeGroup(
    val id: String,
    val name: String,
    val strategy: NodeStrategy = NodeStrategy.ORDER,
    /** Lower goes first. The same meaning as a node's own priority, one level up. */
    val priority: Int = 100,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Client-side only, exactly as on [StudioNode]: which installation holds this row. */
    val installationId: String = "",
)

/**
 * What the probes recorded for one endpoint.
 *
 * [uptime] is **the fraction of stored samples that were up**, not a fraction of wall-clock time,
 * and the two are not close: nobody probes on a schedule the engine controls, so five samples
 * across a week and fifty across an hour both produce a percentage and only one of them means
 * anything. [samples] is beside it for exactly that reason, and the screen prints both.
 *
 * Null [uptime] on an endpoint nobody has probed — zero per cent and "never measured" are not the
 * same claim, and only one of them should send the operator to replace an address.
 */
data class NodeHistory(
    val nodeId: String,
    val samples: Int = 0,
    val upSamples: Int = 0,
    val uptime: Float? = null,
    val avgLatencyMs: Int? = null,
    val items: List<NodeProbe> = emptyList(),
)

data class NodeProbe(
    val ts: Long,
    val up: Boolean,
    val latencyMs: Int? = null,
    val error: String? = null,
)

/**
 * Three states, not two.
 *
 * [UNKNOWN] is the one that earns its place: a node nobody has probed is not healthy and not
 * broken, and drawing it as either is a claim the installation cannot support.
 */
enum class NodeHealth { UP, DOWN, UNKNOWN;
    companion object {
        fun from(s: String?): NodeHealth = when (s?.lowercase()) {
            "up" -> UP
            "down" -> DOWN
            else -> UNKNOWN
        }
    }
}

/**
 * How full a shard is, and what that number is and is not.
 *
 * Every ceiling in the plan's §4 is **per Cloudflare account** — 100k Worker requests a day, 100k D1
 * row writes, 5M row reads — which is the whole reason more accounts buy more room. The one that
 * binds first is Worker requests, and it is spent almost entirely by connections: each WebSocket
 * connection is one request, held open for as long as it lasts.
 *
 * **This is an estimate, and the UI must say so.** Cloudflare's own request counter is not readable
 * from inside the Worker, and counting requests in D1 would cost one row write per request — the
 * scarcest budget in the system, spent to measure itself (plan R5). So the meter is a user count
 * against a stated band, not a reading.
 *
 * The band comes from the plan's own arithmetic: a user reconnecting ~15 times a day costs ~17
 * requests including their client's subscription polling, and one on a flaky mobile connection
 * reconnects several times that. That puts the ceiling somewhere between roughly 300 and 800 users
 * per account depending on how people actually connect — so the meter uses the **conservative** end
 * as "full", because an operator warned early moves one user, and an operator warned late loses an
 * afternoon.
 */
object ShardCapacity {
    /** Users per shard at which the meter reads full. The pessimistic end of the plan's §4.1 band. */
    const val FULL_AT_USERS = 300

    /** Above this the shard is shown as filling up and auto-placement starts avoiding it. */
    const val CROWDED_FRACTION = 0.8f

    fun fractionOf(users: Int): Float = (users.toFloat() / FULL_AT_USERS).coerceIn(0f, 1f)
    fun isCrowded(users: Int): Boolean = fractionOf(users) >= CROWDED_FRACTION
}

/**
 * One Cloudflare account carrying one worker and one D1 — a shard of the fleet.
 *
 * `capabilities` comes from `/v1/health` and is per installation on purpose: with an uncapped fleet,
 * one account on build 6 while another is on build 7 is an ordinary state, not an error. Code that
 * branches on a single global build number is wrong the moment that happens, and the failure is
 * silent and shard-specific — a feature that works on one account and not another, which reads as a
 * network problem and is not (plan R13).
 */
data class StudioInstallation(
    /**
     * The **local** `CloudAccount.id`, and the same value as [StudioUser.installationId],
     * `Plan.presentOn` and the keys of `StudioState.fleetLabels`.
     *
     * There are two account identifiers in play and they are not interchangeable: this one, which is
     * the app's own record id, and [cloudAccountId], which is Cloudflare's. This field used to carry
     * Cloudflare's — harmless while nothing joined installations to users or plans, and wrong the
     * moment the fleet screen does, in the quiet way where a list simply comes back empty.
     */
    val installationId: String,
    /** Cloudflare's account id. Used to address their API; never used to join anything local. */
    val cloudAccountId: String,
    val accountLabel: String,
    val workerUrl: String,
    val apiRoute: String,
    val engineBuild: Int = 0,
    val schemaVersion: Int = 0,
    val enforcement: Enforcement = Enforcement.SOFT,
    val capabilities: Set<String> = emptySet(),
    val migrationError: String? = null,
    val d1Ok: Boolean = true,
    val adopted: Boolean = false,
    /** When this installation last synced. The age of the worst one is what a list must show. */
    val lastSyncAt: Long = 0,
    val syncError: String? = null,
    /**
     * How many people this shard carries, from its own `/v1/dashboard`.
     *
     * -1 means "not asked yet", which is a third state and not zero: an empty shard and one whose
     * dashboard has not been read are the same number otherwise, and auto-placement would happily
     * send everyone to the account it knows least about.
     */
    val users: Int = -1,
    /** True when this installation answered its last health check. */
    val reachable: Boolean = true,
    /**
     * An account's central Worker ([isCentral]): how many Studio people it can carry at once beside
     * its own id and the connected panels -- an exact number, not [ShardCapacity]'s estimate. -1 on
     * an engine.
     */
    val centralRoom: Int = -1,
    /** An account's central Worker: how many of its people may pass right now (each takes a place). */
    val centralLive: Int = 0,
) {
    fun can(capability: String): Boolean = capabilities.contains(capability)
    val isHealthy: Boolean get() = reachable && d1Ok && migrationError == null && syncError == null

    /**
     * People on this account ride its central Worker, answered from this phone: no engine, no
     * database, no counter -- so no volume, devices or traffic, and nothing to update.
     */
    val isCentral: Boolean get() = can("central.v1")

    /** Null until the shard has reported a user count, so a meter is never drawn from nothing. */
    val loadFraction: Float? get() = when {
        isCentral -> if (centralRoom > 0) (centralLive.toFloat() / centralRoom).coerceIn(0f, 1f) else null
        users < 0 -> null
        else -> ShardCapacity.fractionOf(users)
    }
    val isCrowded: Boolean get() = when {
        isCentral -> centralRoom >= 0 && centralRoom - centralLive <= maxOf(2, centralRoom / 10)
        else -> users >= 0 && ShardCapacity.isCrowded(users)
    }

    /** Whether this installation carries an engine new enough for the app's current screens. */
    fun isStale(appBuild: Int, appSchema: Int): Boolean =
        (engineBuild in 1 until appBuild) || (schemaVersion in 1 until appSchema)
}

/**
 * «قالب» — one config SHAPE, named and reused.
 *
 * The other half of [Plan], and the split is the whole design: a plan is what somebody is
 * **allowed** (volume, days, caps); a template is what their link **looks like** (protocol,
 * transport, fingerprint, which endpoints it is served on). Merging them would mean a new row every
 * time either half changed, and the operator picking from twenty combinations of four things.
 *
 * **A template is a starting point, never a live reference.** A config copies the shape when it is
 * made and carries its own `uri_template` afterwards, so editing a template changes nothing for
 * anybody already holding a link — the same rule [Plan] follows and for the same reason. There is
 * deliberately no `:apply` for templates: re-shaping an existing config means re-issuing its
 * credential, which is a rotate and is per person on purpose.
 *
 * Like a plan, it lives on every installation under the same [id], written by the app rather than
 * minted by a worker (§D, R14).
 */
data class ConfigTemplate(
    val id: String,
    val name: String,
    /** One letter, as the engine stores it, for the reason [StudioConfig.protocol] gives. */
    val protocol: String? = null,
    val transportType: String? = null,
    val fingerprint: String? = null,
    val alpn: List<String> = emptyList(),
    /**
     * Whether a config from this template claims a path of its own.
     *
     * Null means "let the builder decide", which is what a template with no opinion should do: the
     * first config a person gets answers on the root, and later ones need their own path.
     */
    val ownPath: Boolean? = null,
    /**
     * Which endpoints a config from this template is served on. Empty means every enabled one.
     *
     * A filter, not a list of addresses. The engine falls back to all of them when none of these
     * still exist, because a config that renders zero links is a subscription entry that vanishes.
     */
    val nodeIds: List<String> = emptyList(),
    val isDefault: Boolean = false,
    val archived: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Which installations carry it. Filled in by the store, never by the wire — as on [Plan]. */
    val presentOn: Set<String> = emptySet(),
)

/**
 * «بسته» — one set of terms handed to many people.
 *
 * Two properties are the whole design, and both exist because the alternative is worse:
 *
 *  * **There is no money in it, in any field, in any phase** (D6). Not a price, not a currency, not
 *    a paid-at date. What an operator arranges with someone is not this product's business, and a
 *    field named `price` eventually grows a screen to display it.
 *  * **A user's terms are COPIED from the plan, never referenced.** [SubscriptionPolicy] on a user
 *    is a snapshot taken when they were created. Editing a plan therefore changes nothing for the
 *    people already holding a link — which is the only safe default, because silently rewriting the
 *    terms of someone who is already using them is the worst thing this feature could do. Pushing an
 *    edit onto them is a separate, named, audited action.
 *
 * A plan lives on every installation in the fleet under the same [id], written by the app rather
 * than minted by a worker: syncing to N accounts means N writes that must be safe to retry, and a
 * server-generated id would make one plan a different row on every account (plan §D, R14).
 */
data class Plan(
    val id: String,
    val name: String,
    val quotaBytes: Long? = null,
    val durationDays: Int? = null,
    val expiryMode: ExpiryMode = ExpiryMode.ABSOLUTE,
    val dailyQuotaBytes: Long? = null,
    val reset: ResetPolicy = ResetPolicy.NONE,
    val deviceLimit: Int? = null,
    val connLimit: Int? = null,
    val ipLimit: Int? = null,
    val templateId: String? = null,
    val isDefault: Boolean = false,
    val archived: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /**
     * Which installations in the fleet actually carry this plan.
     *
     * Filled in by the store, not the wire. A plan missing from one account is shown as missing
     * rather than hidden, because the operator will otherwise create a user on that account and get
     * terms they did not choose — visible drift beats invisible drift (R14).
     */
    val presentOn: Set<String> = emptySet(),
) {
    val isUnlimitedVolume: Boolean get() = quotaBytes == null || quotaBytes <= 0
    val isOpenEnded: Boolean get() = durationDays == null || durationDays <= 0

    /** The terms this plan hands a new user, as of [now]. */
    fun toPolicy(now: Long = System.currentTimeMillis()): SubscriptionPolicy = SubscriptionPolicy(
        expiryMode = expiryMode,
        expiresAt = durationDays?.takeIf { it > 0 }
            ?.let { if (expiryMode == ExpiryMode.ABSOLUTE) now + it * 86_400_000L else null },
        activationDays = durationDays?.takeIf { expiryMode == ExpiryMode.ON_FIRST_CONNECT },
        quotaBytes = quotaBytes,
        dailyQuotaBytes = dailyQuotaBytes,
        reset = reset,
        deviceLimit = deviceLimit,
        connLimit = connLimit,
        ipLimit = ipLimit,
    )
}
