package com.mlmvpn.scanner.data.studio.index

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.mlmvpn.scanner.data.studio.domain.Enforcement
import com.mlmvpn.scanner.data.studio.domain.ExpiryMode
import com.mlmvpn.scanner.data.studio.domain.ResetPolicy
import com.mlmvpn.scanner.data.studio.domain.StudioUser
import com.mlmvpn.scanner.data.studio.domain.SubscriptionPolicy
import com.mlmvpn.scanner.data.studio.domain.TrafficUsage
import com.mlmvpn.scanner.data.studio.domain.UserStatus

/**
 * The local user index — what every list, search and count in Config Studio reads from.
 *
 * ### Why there is a local copy at all
 *
 * The plan originally said the opposite: *"Do not build a local mirror: a stale local user list that
 * disagrees with the worker is the single worst failure mode."* That was right with one Cloudflare
 * account. It became impossible once the fleet was uncapped — a dashboard cannot be fifty round
 * trips, and a list cannot page by asking fifty workers for one screen of rows. So each installation
 * is pulled incrementally from its own watermark and every read is answered from here, which is
 * O(1) in the size of the fleet.
 *
 * The objection was real, though, so the rule that answers it is enforced above this class:
 * **stale for browsing, fresh for acting.** Every mutation re-reads its one user from its own
 * installation before writing. This index is never the basis of a write.
 *
 * ### Why not Room
 *
 * The plan said Room; this is `SQLiteOpenHelper`, and the change is deliberate. Room would mean
 * introducing an annotation processor — no `kapt`, `ksp` or `annotationProcessor` exists anywhere in
 * this project today — plus its Gradle plugin and three artifacts, and an APT pass on every build of
 * a module that already takes two minutes to compile. What that buys is compile-checked SQL and a
 * migration framework, for **one table and five queries**.
 *
 * `SQLiteOpenHelper` is in the framework: no dependency, no processor, and the SQL is right here
 * where it can be read. The real requirement was never "use Room" — it was "JSON in
 * SharedPreferences cannot hold fifteen thousand rows with a search box on them", and this meets it.
 *
 * ### What is NOT in here
 *
 * Sessions, devices, activity and audit. Those are high-volume, rarely read, and inherently a recent
 * tail — they are fetched live from the one installation that owns them. A merged audit feed across
 * an uncapped fleet is not a thing that can work, and the UI says so rather than showing one that is
 * quietly incomplete.
 */
class StudioIndex private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    private val appContext = context.applicationContext
    @Volatile private var checked = false

    /*
     * A damaged file ("file is not a database", seen on a phone with 1.2.39) failed every query
     * and crashed the screen that asked. This is a cache that re-syncs from the installations, so
     * the cure is to throw the file away once and start empty.
     */
    override fun getReadableDatabase(): SQLiteDatabase = checkedOpen { super.getReadableDatabase() }
    override fun getWritableDatabase(): SQLiteDatabase = checkedOpen { super.getWritableDatabase() }

    @Synchronized
    private fun checkedOpen(open: () -> SQLiteDatabase): SQLiteDatabase {
        if (checked) return open()
        val db = try {
            open().also { it.rawQuery("PRAGMA schema_version", null).use { c -> c.moveToFirst() } }
        } catch (e: android.database.sqlite.SQLiteDatabaseCorruptException) {
            android.util.Log.w("StudioIndex", "index file damaged; starting it again")
            runCatching { close() }
            appContext.deleteDatabase(DB_NAME)
            open()
        }
        checked = true
        return db
    }

    companion object {
        private const val DB_NAME = "studio_index.db"

        /**
         * Bump for any schema change, and handle it in [onUpgrade].
         *
         * A destructive fallback is survivable here in a way it would not be for real data -- the
         * index is a cache and re-syncs -- but only if every installation is reachable at that
         * moment, which at fleet scale it will not be. So an upgrade drops the table AND resets the
         * watermarks, which forces a full re-pull rather than leaving a half-populated index that
         * looks complete.
         */
        // 3: locations + ports (multi-location). The upgrade drops and resyncs, as every bump does.
        private const val DB_VERSION = 3

        private const val T_USERS = "idx_users"
        private const val T_SYNC = "idx_sync"

        @Volatile private var INSTANCE: StudioIndex? = null

        fun get(context: Context): StudioIndex =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: StudioIndex(context).also { INSTANCE = it }
            }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        // WAL: the sync loop writes while a list is being read, and without it the reader blocks on
        // the writer -- which shows up as a list that freezes for a moment every refresh cycle.
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $T_USERS (
              row_id TEXT PRIMARY KEY,
              installation_id TEXT NOT NULL,
              user_id TEXT NOT NULL,
              username TEXT NOT NULL,
              username_lc TEXT NOT NULL,
              credential TEXT,
              status TEXT NOT NULL,
              note TEXT,
              tags TEXT,
              group_id TEXT,
              plan_id TEXT,
              expiry_mode TEXT,
              expires_at INTEGER,
              activation_days INTEGER,
              first_connect_at INTEGER,
              quota_bytes INTEGER,
              used_bytes INTEGER NOT NULL DEFAULT 0,
              daily_quota_bytes INTEGER,
              daily_used_bytes INTEGER NOT NULL DEFAULT 0,
              reset_policy TEXT,
              device_limit INTEGER,
              conn_limit INTEGER,
              ip_limit INTEGER,
              enforcement TEXT,
              created_at TEXT,
              last_active_at INTEGER,
              updated_at INTEGER NOT NULL DEFAULT 0,
              synced_at INTEGER NOT NULL DEFAULT 0,
              locations TEXT,
              ports TEXT
            )
            """.trimIndent()
        )
        // `username_lc` exists so search is case-insensitive without `LOWER(username)` in the
        // predicate, which no index can serve.
        db.execSQL("CREATE INDEX ix_idx_users_name ON $T_USERS(username_lc)")
        db.execSQL("CREATE INDEX ix_idx_users_created ON $T_USERS(created_at DESC, user_id DESC)")
        db.execSQL("CREATE INDEX ix_idx_users_expires ON $T_USERS(expires_at)")
        db.execSQL("CREATE INDEX ix_idx_users_inst ON $T_USERS(installation_id)")
        db.execSQL("CREATE INDEX ix_idx_users_status ON $T_USERS(status)")

        db.execSQL(
            """
            CREATE TABLE $T_SYNC (
              installation_id TEXT PRIMARY KEY,
              watermark INTEGER NOT NULL DEFAULT 0,
              watermark_uid TEXT NOT NULL DEFAULT '',
              last_sync_at INTEGER NOT NULL DEFAULT 0,
              last_error TEXT
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $T_USERS")
        db.execSQL("DROP TABLE IF EXISTS $T_SYNC")
        onCreate(db)
    }

    // ------------------------------------------------------------------ writes

    /**
     * Apply one sync page.
     *
     * **Tombstones delete.** A row arriving with `deletedAt` set is removed here, which is the entire
     * reason the sync carries them: without it the user is gone from the engine and still present in
     * this index, and the next list shows someone who no longer has access.
     *
     * One transaction, because a half-applied page would advance nothing and leave the index in a
     * state whose watermark lies about what it holds.
     */
    fun applySync(
        installationId: String,
        users: List<StudioUser>,
        watermark: Long,
        watermarkUid: String = "",
        now: Long = System.currentTimeMillis(),
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (u in users) {
                val rowId = rowId(installationId, u.id)
                if (u.isDeleted) {
                    db.delete(T_USERS, "row_id = ?", arrayOf(rowId))
                } else {
                    db.insertWithOnConflict(T_USERS, null, values(installationId, u, now), SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.insertWithOnConflict(
                T_SYNC, null,
                ContentValues().apply {
                    put("installation_id", installationId)
                    put("watermark", watermark)
                    put("watermark_uid", watermarkUid)
                    put("last_sync_at", now)
                    putNull("last_error")
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Record that an installation could not be reached.
     *
     * The rows stay. Deleting them because a network call failed is how a user vanishes from the
     * operator's list for a reason that has nothing to do with them -- the list shows the age and
     * the error instead, and keeps the people in it.
     */
    // No `now` parameter, deliberately: `last_sync_at` keeps its previous value because a sync that
    // failed did not sync. Stamping it here would make the age shown on screen reset every time a
    // shard failed to answer -- so the longer it stayed broken, the fresher it would look.
    fun markSyncFailed(installationId: String, error: String) {
        val db = writableDatabase
        val existing = watermarkOf(installationId)
        val existingUid = watermarkUidOf(installationId)
        db.insertWithOnConflict(
            T_SYNC, null,
            ContentValues().apply {
                put("installation_id", installationId)
                put("watermark", existing)
                put("watermark_uid", existingUid)
                put("last_sync_at", lastSyncAt(installationId))
                put("last_error", error)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Applied after a single-user write, so the screen updates without waiting for the next cycle. */
    fun upsert(installationId: String, user: StudioUser, now: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        if (user.isDeleted) db.delete(T_USERS, "row_id = ?", arrayOf(rowId(installationId, user.id)))
        else db.insertWithOnConflict(T_USERS, null, values(installationId, user, now), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun remove(installationId: String, userId: String) {
        writableDatabase.delete(T_USERS, "row_id = ?", arrayOf(rowId(installationId, userId)))
    }

    /** Everything belonging to an installation, for when an account is disconnected. */
    fun forgetInstallation(installationId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(T_USERS, "installation_id = ?", arrayOf(installationId))
            db.delete(T_SYNC, "installation_id = ?", arrayOf(installationId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ------------------------------------------------------------------ reads

    fun watermarkOf(installationId: String): Long =
        readableDatabase.rawQuery(
            "SELECT watermark FROM $T_SYNC WHERE installation_id = ?", arrayOf(installationId)
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /**
     * The tie-break half of the watermark.
     *
     * Empty means "start at the beginning of that timestamp", which is right both for a first sync
     * and for an installation whose engine is too old to send one.
     */
    fun watermarkUidOf(installationId: String): String =
        readableDatabase.rawQuery(
            "SELECT watermark_uid FROM $T_SYNC WHERE installation_id = ?", arrayOf(installationId)
        ).use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }

    fun lastSyncAt(installationId: String): Long =
        readableDatabase.rawQuery(
            "SELECT last_sync_at FROM $T_SYNC WHERE installation_id = ?", arrayOf(installationId)
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun syncErrorOf(installationId: String): String? =
        readableDatabase.rawQuery(
            "SELECT last_error FROM $T_SYNC WHERE installation_id = ?", arrayOf(installationId)
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    /**
     * A page of the list.
     *
     * `LIMIT/OFFSET` rather than a keyset cursor, and the difference from the engine's list is not an
     * inconsistency. There, OFFSET reads and discards rows that D1 **charges for**, so page 100 costs
     * the price of pages 1-100. Here it walks a local index in memory, which for any list a person
     * will actually scroll is free.
     *
     * The **search is an infix match**, which the engine deliberately refuses to do: `LIKE '%x%'`
     * cannot use an index, and on D1 that reads the whole table on every keystroke. Locally it is a
     * scan of a few thousand short strings. So the index does not merely make search fast — it makes
     * a better search possible than the API can safely offer.
     */
    fun page(
        limit: Int,
        offset: Int = 0,
        query: String? = null,
        status: UserStatus? = null,
        installationId: String? = null,
        filter: UserFilter = UserFilter.ALL,
        sort: UserSort = UserSort.NEWEST,
        tag: String? = null,
        now: Long = System.currentTimeMillis(),
    ): List<StudioUser> {
        val (where, args) = predicate(query, status, installationId, filter, tag, now)
        return readableDatabase.rawQuery(
            "SELECT * FROM $T_USERS WHERE $where ORDER BY ${sort.orderBy} LIMIT ? OFFSET ?",
            (args + limit.toString() + offset.toString()).toTypedArray(),
        ).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
    }

    /**
     * How many rows the current narrowing matches, ignoring paging.
     *
     * Separate from [count] because that one answers a question about the whole index and this one
     * answers a question about what is on screen. A list that says «۱۲۰ کاربر» at the top while
     * showing a filtered forty is worse than a list that says nothing.
     */
    fun countMatching(
        query: String? = null,
        status: UserStatus? = null,
        installationId: String? = null,
        filter: UserFilter = UserFilter.ALL,
        tag: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Int {
        val (where, args) = predicate(query, status, installationId, filter, tag, now)
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM $T_USERS WHERE $where", args.toTypedArray())
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /**
     * The WHERE clause shared by the page and its count, built once so they cannot drift.
     *
     * The **search covers name, tag, note and id**, not just the name. An operator searching for
     * «تهران» is looking for the tag they wrote, and a search box that silently only reads one column
     * returns nothing and gives no reason. The engine still refuses this — `LIKE '%x%'` cannot use an
     * index and D1 charges for the scan — which is the whole argument for the index existing.
     */
    private fun predicate(
        query: String?,
        status: UserStatus?,
        installationId: String?,
        filter: UserFilter,
        tag: String?,
        now: Long,
    ): Pair<String, List<String>> {
        val where = StringBuilder("1=1")
        val args = mutableListOf<String>()
        query?.takeIf { it.isNotBlank() }?.let {
            // The wildcards are stripped rather than escaped: `LIKE ... ESCAPE` would need every
            // caller to know the escape character, and nobody types `%` looking for a user.
            val needle = "%" + it.lowercase().replace("%", "").replace("_", "") + "%"
            where.append(
                " AND (username_lc LIKE ? OR LOWER(IFNULL(tags,'')) LIKE ?" +
                    " OR LOWER(IFNULL(note,'')) LIKE ? OR LOWER(user_id) LIKE ?)"
            )
            repeat(4) { args.add(needle) }
        }
        status?.let { where.append(" AND status = ?"); args.add(it.name.lowercase()) }
        installationId?.let { where.append(" AND installation_id = ?"); args.add(it) }
        filter.clause(now)?.let { where.append(" AND ($it)") }
        tag?.takeIf { it.isNotBlank() }?.let {
            // Commas on both ends, so «vip» does not also match «vip-old». `tags` is stored as a
            // bare comma join, so the padding has to be added in the comparison rather than assumed.
            where.append(" AND (',' || IFNULL(tags,'') || ',') LIKE ?")
            args.add("%,${it},%")
        }
        return where.toString() to args
    }

    /**
     * Every tag in use, most-used first, for the filter row.
     *
     * Read in Kotlin rather than with a recursive CTE to split the column: the index holds a few
     * thousand rows of a short string, and a `WITH RECURSIVE` here would be the one piece of SQL in
     * this file nobody could change safely.
     */
    fun allTags(limit: Int = 24): List<String> {
        val counts = HashMap<String, Int>()
        readableDatabase.rawQuery(
            "SELECT tags FROM $T_USERS WHERE tags IS NOT NULL AND tags <> ''", null
        ).use { c ->
            while (c.moveToNext()) {
                c.getString(0).orEmpty().split(",").forEach { raw ->
                    val t = raw.trim()
                    if (t.isNotEmpty()) counts[t] = (counts[t] ?: 0) + 1
                }
            }
        }
        return counts.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
        ).take(limit).map { it.key }
    }

    fun find(installationId: String, userId: String): StudioUser? =
        readableDatabase.rawQuery(
            "SELECT * FROM $T_USERS WHERE row_id = ?", arrayOf(rowId(installationId, userId))
        ).use { if (it.moveToNext()) read(it) else null }

    /** Whether this name is already taken anywhere in the fleet — instant, and O(1) in fleet size. */
    fun usernameExists(username: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM $T_USERS WHERE username_lc = ? LIMIT 1", arrayOf(username.lowercase())
        ).use { it.moveToFirst() }

    /**
     * Which of [names] are already taken, as ONE query.
     *
     * The bulk-create screen checks a batch on every keystroke, and doing that as a point lookup per
     * name is up to five hundred queries on the main thread each time a digit is typed. Each one is
     * indexed and fast; five hundred of them, sixty times a minute, is not.
     *
     * Chunked at 400 because SQLite's default `SQLITE_MAX_VARIABLE_NUMBER` is 999 and the ceiling
     * belongs here rather than in the caller's head.
     */
    fun takenAmong(names: List<String>): Set<String> {
        if (names.isEmpty()) return emptySet()
        val out = HashSet<String>()
        names.map { it.lowercase() }.distinct().chunked(400).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT username_lc FROM $T_USERS WHERE username_lc IN ($marks)",
                chunk.toTypedArray(),
            ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        }
        return out
    }

    fun count(status: UserStatus? = null): Int {
        val sql = if (status == null) "SELECT COUNT(*) FROM $T_USERS"
        else "SELECT COUNT(*) FROM $T_USERS WHERE status = '${status.name.lowercase()}'"
        return readableDatabase.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun countExpiringWithin(millis: Long, now: Long = System.currentTimeMillis()): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $T_USERS WHERE expires_at > ? AND expires_at <= ?",
            arrayOf(now.toString(), (now + millis).toString()),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    // ------------------------------------------------------------------ mapping

    private fun rowId(installationId: String, userId: String) = "$installationId:$userId"

    private fun values(installationId: String, u: StudioUser, now: Long) = ContentValues().apply {
        put("row_id", rowId(installationId, u.id))
        put("installation_id", installationId)
        put("user_id", u.id)
        put("username", u.username)
        put("username_lc", u.username.lowercase())
        put("credential", u.credential)
        put("status", u.status.name.lowercase())
        put("note", u.note)
        put("tags", u.tags.joinToString(","))
        put("group_id", u.groupId)
        put("plan_id", u.planId)
        put("expiry_mode", u.policy.expiryMode.name.lowercase())
        put("expires_at", u.policy.expiresAt)
        put("activation_days", u.policy.activationDays)
        put("first_connect_at", u.policy.firstConnectAt)
        put("quota_bytes", u.policy.quotaBytes)
        put("used_bytes", u.usage.usedBytes)
        put("daily_quota_bytes", u.policy.dailyQuotaBytes)
        put("daily_used_bytes", u.usage.dailyUsedBytes)
        put("reset_policy", u.policy.reset.name.lowercase())
        put("device_limit", u.policy.deviceLimit)
        put("conn_limit", u.policy.connLimit)
        put("ip_limit", u.policy.ipLimit)
        put("enforcement", u.policy.enforcement.name.lowercase())
        put("created_at", u.createdAt)
        put("last_active_at", u.lastActiveAt)
        put("updated_at", u.updatedAt)
        put("synced_at", now)
        put("locations", u.locations.joinToString(","))
        put("ports", u.ports.joinToString(","))
    }

    private fun read(c: Cursor): StudioUser {
        fun str(name: String): String? = c.getColumnIndex(name).let { if (it < 0 || c.isNull(it)) null else c.getString(it) }
        fun lng(name: String): Long? = c.getColumnIndex(name).let { if (it < 0 || c.isNull(it)) null else c.getLong(it) }
        fun int(name: String): Int? = c.getColumnIndex(name).let { if (it < 0 || c.isNull(it)) null else c.getInt(it) }

        return StudioUser(
            id = str("user_id").orEmpty(),
            username = str("username").orEmpty(),
            credential = str("credential"),
            status = UserStatus.from(str("status")),
            note = str("note"),
            tags = str("tags").orEmpty().split(",").filter { it.isNotBlank() },
            locations = str("locations").orEmpty().split(",").filter { it.length == 2 },
            ports = str("ports").orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() },
            groupId = str("group_id"),
            planId = str("plan_id"),
            policy = SubscriptionPolicy(
                expiryMode = ExpiryMode.from(str("expiry_mode")),
                expiresAt = lng("expires_at"),
                activationDays = int("activation_days"),
                firstConnectAt = lng("first_connect_at"),
                quotaBytes = lng("quota_bytes"),
                dailyQuotaBytes = lng("daily_quota_bytes"),
                reset = ResetPolicy.from(str("reset_policy")),
                deviceLimit = int("device_limit"),
                connLimit = int("conn_limit"),
                ipLimit = int("ip_limit"),
                enforcement = Enforcement.from(str("enforcement")),
            ),
            usage = TrafficUsage(
                usedBytes = lng("used_bytes") ?: 0,
                dailyUsedBytes = lng("daily_used_bytes") ?: 0,
            ),
            createdAt = str("created_at"),
            updatedAt = lng("updated_at") ?: 0,
            lastActiveAt = lng("last_active_at"),
            installationId = str("installation_id").orEmpty(),
        )
    }

    /** How stale the freshest data in this index is, for the strip a list shows above itself. */
    fun oldestSyncAge(installationIds: List<String>, now: Long = System.currentTimeMillis()): Long? {
        if (installationIds.isEmpty()) return null
        val oldest = installationIds.minOfOrNull { lastSyncAt(it) } ?: return null
        return if (oldest <= 0) null else now - oldest
    }
}
