package ai.appdna.sdk.storage

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ai.appdna.sdk.Log
import org.json.JSONObject

/**
 * Redirects the event database into `noBackupFilesDir`, the Android equivalent of
 * iOS's `isExcludedFromBackup`.
 *
 * The pending-event table holds whatever properties and traits the host chose to
 * send. The default `databases/` directory is copied into Google cloud backup and
 * device-to-device transfer; `noBackupFilesDir` is not.
 *
 * A library cannot set `android:fullBackupContent` / `android:dataExtractionRules`
 * itself — those are application-level attributes, and declaring them here would
 * fail the manifest merger for every host that already declares its own. Redirecting
 * the file is the only mechanism that works without host cooperation.
 *
 * Migrates any pre-existing database out of `databases/` exactly once, then leaves
 * the old location empty. Idempotent and self-healing: a partial move is retried on
 * the next launch, and a failed move simply leaves the SDK reading the old path.
 */
private class NoBackupContext(base: Context, dbName: String) : ContextWrapper(base) {

    init {
        migrateOutOfBackedUpStorage(base, dbName)
    }

    override fun getDatabasePath(name: String): File = File(noBackupFilesDir, name)

    private companion object {
        /** SQLite sidecars that must travel with the main file. */
        private val SUFFIXES = listOf("", "-journal", "-wal", "-shm")

        fun migrateOutOfBackedUpStorage(base: Context, dbName: String) {
            try {
                val dest = File(base.noBackupFilesDir, dbName)
                if (dest.exists()) return // already migrated
                val src = base.getDatabasePath(dbName)
                if (!src.exists()) return // fresh install — nothing to move
                base.noBackupFilesDir.mkdirs()
                for (suffix in SUFFIXES) {
                    val from = File(src.parentFile, dbName + suffix)
                    if (!from.exists()) continue
                    val to = File(base.noBackupFilesDir, dbName + suffix)
                    if (!from.renameTo(to)) {
                        from.copyTo(to, overwrite = true)
                        from.delete()
                    }
                }
                Log.info("Migrated event database out of backed-up storage")
            } catch (e: Exception) {
                // Never block SDK startup on the migration. Worst case the DB stays
                // in databases/ and is backed up, exactly as before this change.
                Log.warning("Event database backup-exclusion migration failed: ${e.message}")
            }
        }
    }
}

/**
 * SPEC-067: SQLite-based event persistence replacing SharedPreferences.
 * Provides atomic row-level operations that survive process kills.
 * Enforces both event count cap (10K) and disk quota (5 MB of LIVE event bytes — each stored event's UTF-8
 * JSON plus one byte, the newline iOS stores after each line — the same measure as iOS `EventStore`, not the
 * SQLite file, whose pages, indexes and free list are not events).
 *
 * Stored in `noBackupFilesDir` — see [NoBackupContext].
 */
internal class EventDatabase(
    context: Context,
    // SPEC-428: injectable so the shared behavioral fixtures (events/ category) drive eviction at a
    // small cap with an isolated db. Production callers use the defaults.
    private val maxEvents: Int = MAX_EVENTS,
    dbName: String = DATABASE_NAME,
    /** The disk quota in live event bytes (injectable for the shared fixtures; production: [MAX_DISK_BYTES]). */
    private val maxDiskBytes: Int = MAX_DISK_BYTES,
) : SQLiteOpenHelper(NoBackupContext(context, dbName), dbName, null, DATABASE_VERSION) {

    init {
        // SPEC-428 CL-1/D2: wire the dropped-events counter to this app context.
        ai.appdna.sdk.events.DroppedEventsCounter.init(context)
    }

    companion object {
        private const val DATABASE_NAME = "appdna_events.db"
        // V2 adds idx_events_created_at on upgrade. v3 adds the event_id / ts_ms /
        // meta_count columns, so a flush's prune and removal are SQL statements, not a JSON parse of every row.
        // v4 adds payload_bytes, so the disk quota measures the live events' bytes (iOS's measure), not the file.
        internal const val DATABASE_VERSION = 4
        private const val TABLE_EVENTS = "pending_events"
        private const val COL_ID = "_id"
        private const val COL_EVENT_JSON = "event_json"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_EVENT_ID = "event_id"
        private const val COL_TS_MS = "ts_ms"
        /** The `count` a `_sdk_events_dropped` meta carries; NULL for a normal event (which counts 1). */
        private const val COL_META_COUNT = "meta_count"
        /** The stored JSON's UTF-8 length. */
        private const val COL_PAYLOAD_BYTES = "payload_bytes"
        /** Rows parsed per step of the v3 back-fill. */
        private const val BACKFILL_CHUNK = 1_000
        /** SQLite's default bound-parameter limit is 999. */
        private const val IN_CHUNK = 500

        const val MAX_EVENTS = 10_000
        const val MAX_DISK_BYTES = 5 * 1024 * 1024 // 5 MB
        // SPEC-428 CL-2/D5: client redelivery horizon (7d, tracking SPEC-426's server dedup window).
        const val REDELIVERY_HORIZON_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * SPEC-070-B PN row 19 (W14) — an age past this is not a stale event, it is a broken clock.
         * The horizon compares wall clocks, so a forward clock jump makes every queued event look
         * older than 7 days at once and prunes it **unsent**. Beyond this bound, and for any negative
         * age (the clock moved backwards), we keep the event: a retained event costs a retry, a pruned
         * one is gone. 30 days is ~4x the horizon.
         */
        const val IMPLAUSIBLE_AGE_MS: Long = 30L * 24 * 60 * 60 * 1000

        /** True when the event is genuinely past the horizon, as opposed to a victim of a clock jump. */
        @JvmStatic
        fun isStale(tsMs: Long, nowMs: Long, horizonMs: Long): Boolean {
            val age = nowMs - tsMs
            return age > horizonMs && age <= IMPLAUSIBLE_AGE_MS
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_EVENTS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_EVENT_JSON TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL DEFAULT (strftime('%s', 'now') * 1000),
                $COL_EVENT_ID TEXT,
                $COL_TS_MS INTEGER,
                $COL_META_COUNT INTEGER,
                $COL_PAYLOAD_BYTES INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_created_at ON $TABLE_EVENTS($COL_CREATED_AT)")
        createLookupIndexes(db)
    }

    /**
     * Every upgrade brings the table to this version's shape through [ensureSchema], which checks what is there
     * before it changes anything. `SQLiteOpenHelper` reads the version BEFORE it takes the write lock, so two
     * helpers on one file (the event queue's and the WorkManager uploader's) can both run `onUpgrade(2, 4)`, the
     * second after the first committed: an unconditional `ALTER TABLE … ADD COLUMN` then threw "duplicate
     * column" and the second helper could not open the database.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        ensureSchema(db)
    }

    /**
     * A newer SDK wrote a higher version (the host app rolled back an SDK update). The default `onDowngrade`
     * throws, and no event could be stored or sent until the app was reinstalled. Keep the table when this
     * version can use it ([ensureSchema]: the columns it needs are there or can be added, and no unknown column
     * demands a value on insert); otherwise rebuild it, counting the rows that are lost.
     */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        try {
            ensureSchema(db)
            Log.warning("Event database v$oldVersion opened by an SDK that writes v$newVersion — kept")
        } catch (e: Exception) {
            val lost = try {
                db.rawQuery("SELECT COUNT(*) FROM $TABLE_EVENTS", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            } catch (_: Exception) { 0 }
            db.execSQL("DROP TABLE IF EXISTS $TABLE_EVENTS")
            onCreate(db)
            if (lost > 0) ai.appdna.sdk.events.DroppedEventsCounter.increment(lost)
            Log.warning("Event database v$oldVersion could not be used by this SDK (${e.message}) — rebuilt; $lost events lost (counted)")
        }
    }

    /** The table's columns: name → (NOT NULL, has a default). */
    private fun columns(db: SQLiteDatabase): Map<String, Pair<Boolean, Boolean>> {
        val out = HashMap<String, Pair<Boolean, Boolean>>()
        db.rawQuery("PRAGMA table_info($TABLE_EVENTS)", null).use { c ->
            val name = c.getColumnIndex("name")
            val notNull = c.getColumnIndex("notnull")
            val dflt = c.getColumnIndex("dflt_value")
            val pk = c.getColumnIndex("pk")
            while (c.moveToNext()) {
                out[c.getString(name)] = (c.getInt(notNull) != 0 && c.getInt(pk) == 0) to !c.isNull(dflt)
            }
        }
        return out
    }

    /**
     * Brings the table to this version's shape, idempotently: creates it when absent, adds only the columns that
     * are missing (checked with `PRAGMA table_info`, inside the upgrade's transaction), back-fills the rows that
     * lack them, and creates the indexes. Throws when the table cannot be used (no `_id` / `event_json`, or a
     * column this SDK does not know that requires a value on insert).
     */
    private fun ensureSchema(db: SQLiteDatabase) {
        val cols = columns(db)
        if (cols.isEmpty()) { onCreate(db); return }
        require(COL_ID in cols && COL_EVENT_JSON in cols) { "no $COL_ID / $COL_EVENT_JSON column" }
        val known = setOf(COL_ID, COL_EVENT_JSON, COL_CREATED_AT, COL_EVENT_ID, COL_TS_MS, COL_META_COUNT, COL_PAYLOAD_BYTES)
        for ((name, flags) in cols) {
            require(name in known || !flags.first || flags.second) { "column $name requires a value this SDK does not write" }
        }
        fun add(name: String, type: String) {
            if (name !in cols) db.execSQL("ALTER TABLE $TABLE_EVENTS ADD COLUMN $name $type")
        }
        add(COL_CREATED_AT, "INTEGER NOT NULL DEFAULT 0")
        add(COL_EVENT_ID, "TEXT")
        add(COL_TS_MS, "INTEGER")
        add(COL_META_COUNT, "INTEGER")
        add(COL_PAYLOAD_BYTES, "INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_created_at ON $TABLE_EVENTS($COL_CREATED_AT)")
        backfillLookupColumns(db)
        // The payload size is SQL: the UTF-8 length of the stored JSON.
        db.execSQL("UPDATE $TABLE_EVENTS SET $COL_PAYLOAD_BYTES = LENGTH(CAST($COL_EVENT_JSON AS BLOB)) WHERE $COL_PAYLOAD_BYTES IS NULL")
        createLookupIndexes(db)
    }

    /**
     * Fills `event_id` / `ts_ms` / `meta_count` from the stored JSON for the rows that have none of them (rows an
     * SDK before v3 wrote) — the only full parse the table ever needs. In chunks of [BACKFILL_CHUNK] rows by `_id`,
     * with ONE compiled UPDATE statement re-bound per row, inside the caller's transaction (`onUpgrade` runs in
     * one). It used to build a ContentValues `db.update(…)` per row, compiling the statement 10,000 times for a
     * 10,000-event backlog on the thread that first opened the database.
     */
    private fun backfillLookupColumns(db: SQLiteDatabase) {
        val update = db.compileStatement(
            "UPDATE $TABLE_EVENTS SET $COL_EVENT_ID = ?, $COL_TS_MS = ?, $COL_META_COUNT = ? WHERE $COL_ID = ?"
        )
        try {
            var lastId = -1L
            while (true) {
                var seen = 0
                db.rawQuery(
                    "SELECT $COL_ID, $COL_EVENT_JSON FROM $TABLE_EVENTS WHERE $COL_ID > ? AND $COL_EVENT_ID IS NULL " +
                        "AND $COL_TS_MS IS NULL AND $COL_META_COUNT IS NULL ORDER BY $COL_ID LIMIT $BACKFILL_CHUNK",
                    arrayOf(lastId.toString()),
                ).use { c ->
                    while (c.moveToNext()) {
                        seen++
                        lastId = c.getLong(0)
                        val v = lookupValues(c.getString(1)) ?: continue
                        update.clearBindings()
                        v.getAsString(COL_EVENT_ID)?.let { update.bindString(1, it) } ?: update.bindNull(1)
                        v.getAsLong(COL_TS_MS)?.let { update.bindLong(2, it) } ?: update.bindNull(2)
                        v.getAsLong(COL_META_COUNT)?.let { update.bindLong(3, it) } ?: update.bindNull(3)
                        update.bindLong(4, lastId)
                        update.executeUpdateDelete()
                    }
                }
                if (seen < BACKFILL_CHUNK) break
            }
        } finally {
            update.close()
        }
    }

    private fun createLookupIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_event_id ON $TABLE_EVENTS($COL_EVENT_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_ts_ms ON $TABLE_EVENTS($COL_TS_MS)")
    }

    /** JSON parses of stored or inserted events (test seam: a flush must add none). */
    private val jsonParses = java.util.concurrent.atomic.AtomicInteger(0)
    internal val jsonParsesForTest: Int get() = jsonParses.get()

    /** The lookup columns of one event: `event_id`, `ts_ms`, and a dropped-events meta's carried `count`. */
    private fun lookupValues(eventJson: String): ContentValues? {
        jsonParses.incrementAndGet()
        val obj = try { JSONObject(eventJson) } catch (_: Exception) { return null }
        return lookupValues(obj)
    }

    private fun lookupValues(obj: JSONObject): ContentValues = ContentValues().apply {
        val id = obj.optString("event_id", "")
        if (id.isNotEmpty()) put(COL_EVENT_ID, id) else putNull(COL_EVENT_ID)
        // A missing or non-numeric ts_ms stays NULL: such a row is never stale (the old parse used `now`).
        if (obj.has("ts_ms") && !obj.isNull("ts_ms") && obj.opt("ts_ms") is Number) put(COL_TS_MS, obj.getLong("ts_ms"))
        else putNull(COL_TS_MS)
        if (obj.optString("event_name") == "_sdk_events_dropped")
            put(COL_META_COUNT, obj.optJSONObject("properties")?.optInt("count", 0) ?: 0)
        else putNull(COL_META_COUNT)
    }

    /**
     * Insert an event JSON string into the database.
     * Enforces count and disk quotas after insert.
     */
    fun insertEvent(eventJson: String) {
        insertRow(eventJson, lookupValues(eventJson))
    }

    /** The event queue's insert: the event is already parsed, so its lookup columns cost nothing. */
    fun insertEvent(event: JSONObject) {
        insertRow(event.toString(), lookupValues(event))
    }

    private fun insertRow(eventJson: String, lookup: ContentValues?) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                lookup?.let { putAll(it) }
                put(COL_EVENT_JSON, eventJson)
                put(COL_PAYLOAD_BYTES, eventJson.toByteArray(Charsets.UTF_8).size)
                put(COL_CREATED_AT, System.currentTimeMillis()) // SPEC-428 CL-6: milliseconds, not seconds
            }
            db.insert(TABLE_EVENTS, null, values)
            enforceQuotas(db)
        } catch (e: Exception) {
            Log.error("Failed to insert event: ${e.message}")
        }
    }

    /**
     * Insert multiple events in a single transaction.
     */
    fun insertEvents(events: List<String>) {
        if (events.isEmpty()) return
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (eventJson in events) {
                    val values = ContentValues().apply {
                        lookupValues(eventJson)?.let { putAll(it) }
                        put(COL_EVENT_JSON, eventJson)
                        put(COL_PAYLOAD_BYTES, eventJson.toByteArray(Charsets.UTF_8).size)
                        put(COL_CREATED_AT, System.currentTimeMillis()) // SPEC-428 CL-6: milliseconds, not seconds
                    }
                    db.insert(TABLE_EVENTS, null, values)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            enforceQuotas(db)
        } catch (e: Exception) {
            Log.error("Failed to insert events batch: ${e.message}")
        }
    }

    /**
     * Load all pending events ordered by creation time (oldest first).
     */
    fun loadAll(): List<String> {
        val events = mutableListOf<String>()
        try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_EVENTS, arrayOf(COL_EVENT_JSON),
                null, null, null, null,
                "$COL_CREATED_AT ASC, $COL_ID ASC"
            )
            cursor.use {
                while (it.moveToNext()) {
                    events.add(it.getString(0))
                }
            }
        } catch (e: Exception) {
            Log.error("Failed to load events: ${e.message}")
        }
        return events
    }

    /**
     * The [limit] newest pending events, oldest first — the event queue's in-memory window. Reads only
     * those rows, so building a queue (inside `configure()`) costs the window, not the backlog.
     */
    fun loadNewest(limit: Int): List<String> {
        val events = ArrayList<String>()
        try {
            readableDatabase.query(
                TABLE_EVENTS, arrayOf(COL_EVENT_JSON),
                null, null, null, null,
                "$COL_CREATED_AT DESC, $COL_ID DESC",
                limit.toString()
            ).use { while (it.moveToNext()) events.add(it.getString(0)) }
        } catch (e: Exception) {
            Log.error("Failed to load events: ${e.message}")
        }
        events.reverse()
        return events
    }

    /**
     * Load up to [limit] oldest pending events.
     */
    fun loadBatch(limit: Int): List<Pair<Long, String>> {
        val events = mutableListOf<Pair<Long, String>>()
        try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_EVENTS, arrayOf(COL_ID, COL_EVENT_JSON),
                null, null, null, null,
                "$COL_CREATED_AT ASC, $COL_ID ASC",
                limit.toString()
            )
            cursor.use {
                while (it.moveToNext()) {
                    events.add(it.getLong(0) to it.getString(1))
                }
            }
        } catch (e: Exception) {
            Log.error("Failed to load event batch: ${e.message}")
        }
        return events
    }

    /**
     * Remove events by their database IDs after successful upload.
     */
    fun removeByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        try {
            val db = writableDatabase
            val placeholders = ids.joinToString(",") { "?" }
            db.delete(TABLE_EVENTS, "$COL_ID IN ($placeholders)", ids.map { it.toString() }.toTypedArray())
        } catch (e: Exception) {
            Log.error("Failed to remove events: ${e.message}")
        }
    }

    /**
     * SPEC-428 CL-4/D3 — CONTENT-ADDRESSED removal: delete exactly the rows whose event's `event_id`
     * was in the UPLOADED batch, never a re-derived "globally-oldest N". After a CL-1 in-memory
     * eviction the in-mem batch diverges from the DB's oldest N, so deleting the oldest N would drop
     * never-uploaded rows (LOSS) and leave the uploaded rows behind (DUP on the next flush).
     */
    fun removeByEventIds(eventIds: Set<String>) {
        val ids = eventIds.filter { it.isNotEmpty() }
        if (ids.isEmpty()) return
        try {
            // An indexed DELETE on the event_id column — the cost of the batch, not of every stored row.
            val db = writableDatabase
            for (chunk in ids.chunked(IN_CHUNK)) {
                val placeholders = chunk.joinToString(",") { "?" }
                db.delete(TABLE_EVENTS, "$COL_EVENT_ID IN ($placeholders)", chunk.toTypedArray())
            }
        } catch (e: Exception) {
            Log.error("Failed to remove events by event_id: ${e.message}")
        }
    }

    /**
     * SPEC-428 CL-2/D5: drop events past the redelivery horizon so NO consumer — the in-process flush OR
     * the WorkManager uploader that fires hours/days later — re-sends an event past the server dedup window
     * (double-count). This lives at the STORE so both paths are covered. Counted (CL-1). Returns # dropped.
     */
    fun pruneStale(horizonMs: Long = REDELIVERY_HORIZON_MS): Int {
        val now = System.currentTimeMillis()
        var pruned = 0
        var lost = 0
        try {
            val db = writableDatabase
            // Wrap the scan+delete in ONE transaction so concurrent prunes SERIALIZE: the in-process flush
            // and the background Worker hold SEPARATE EventDatabase handles on the same file, so without this
            // both could count the same 7-day-stale rows. beginTransaction takes the write lock — the second
            // caller blocks until the first commits, then its scan sees the rows gone and counts 0. This is
            // what makes pruneStale the true SINGLE count source on Android (as it already is on iOS).
            //
            // The stale test is SQL on the ts_ms column — `isStale` (age > horizon, and not past the
            // implausible-age clamp) as a range — so the transaction lasts milliseconds whatever the backlog. It
            // used to parse every row's JSON while holding the write lock, on every flush.
            db.beginTransaction()
            try {
                val where = "$COL_TS_MS IS NOT NULL AND $COL_TS_MS < ? AND $COL_TS_MS >= ?"
                val args = arrayOf((now - horizonMs).toString(), (now - IMPLAUSIBLE_AGE_MS).toString())
                db.rawQuery(
                    "SELECT COUNT(*), COALESCE(SUM(COALESCE($COL_META_COUNT, 1)), 0) FROM $TABLE_EVENTS WHERE $where",
                    args,
                ).use { c ->
                    // Meta-aware — a stale `_sdk_events_dropped` carries N drops.
                    if (c.moveToFirst()) { pruned = c.getInt(0); lost = c.getInt(1) }
                }
                if (pruned > 0) db.delete(TABLE_EVENTS, where, args)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (pruned > 0) {
                ai.appdna.sdk.events.DroppedEventsCounter.increment(lost)
                Log.warning("Pruned $pruned events past the redelivery horizon (loss metric +$lost)")
            }
        } catch (e: Exception) {
            Log.error("pruneStale failed: ${e.message}")
        }
        return pruned
    }

    /**
     * SPEC-424 STEP-1a (CL-7): purge ALL persisted events WITHOUT uploading — analytics consent was
     * revoked, so queued-but-unsent events must never be transmitted.
     */
    fun clearAll() {
        try {
            writableDatabase.delete(TABLE_EVENTS, null, null)
        } catch (e: Exception) {
            Log.error("Failed to clear events: ${e.message}")
        }
    }

    /**
     * Get the count of pending events.
     */
    fun count(): Int {
        return try {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_EVENTS", null)
            cursor.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Migrate existing SharedPreferences event data to SQLite.
     * Called once on first launch after upgrade.
     */
    fun migrateFromSharedPreferences(storage: LocalStorage) {
        val existing = storage.getEventQueue()
        if (existing.isEmpty()) return

        Log.info("Migrating ${existing.size} events from SharedPreferences to SQLite")
        insertEvents(existing)

        // Only clear SP after successful SQLite write
        storage.setEventQueue(emptyList())
        Log.info("SharedPreferences event migration complete")
    }

    // MARK: - Private

    /**
     * Enforce event count cap and disk quota.
     */
    // SPEC-428 STEP-4: sum the TRUE loss over the oldest `limit` rows (the eviction set) — count 1 per
    // normal event, but the carried N for a `_sdk_events_dropped` META event, so evicting the meta before
    // delivery re-adds (and re-emits) the N drops it represented instead of under-counting them to 1.
    private fun evictionLoss(db: SQLiteDatabase, limit: Int): Int {
        db.rawQuery(
            "SELECT COALESCE(SUM(COALESCE($COL_META_COUNT, 1)), 0) FROM (SELECT $COL_META_COUNT FROM $TABLE_EVENTS " +
                "ORDER BY $COL_CREATED_AT ASC, $COL_ID ASC LIMIT $limit)",
            null,
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** The live events' bytes: each row's JSON plus one (iOS stores a newline after each line). */
    private fun liveBytes(db: SQLiteDatabase): Long =
        db.rawQuery(
            "SELECT COALESCE(SUM(COALESCE($COL_PAYLOAD_BYTES, LENGTH(CAST($COL_EVENT_JSON AS BLOB))) + 1), 0) FROM $TABLE_EVENTS",
            null,
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    /** The bytes [liveBytes] counts for the oldest [limit] rows. */
    private fun oldestBytes(db: SQLiteDatabase, limit: Int): Long =
        db.rawQuery(
            "SELECT COALESCE(SUM(COALESCE($COL_PAYLOAD_BYTES, LENGTH(CAST($COL_EVENT_JSON AS BLOB))) + 1), 0) FROM " +
                "(SELECT $COL_PAYLOAD_BYTES, $COL_EVENT_JSON FROM $TABLE_EVENTS ORDER BY $COL_CREATED_AT ASC, $COL_ID ASC LIMIT $limit)",
            null,
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    /** Live event bytes now (test reader; see [liveBytes]). */
    internal fun liveBytesForTest(): Long = try { liveBytes(readableDatabase) } catch (_: Exception) { 0L }

    private fun enforceQuotas(db: SQLiteDatabase) {
        // 1. Count cap
        val currentCount = count()
        if (currentCount > maxEvents) {
            val excess = currentCount - maxEvents
            val loss = evictionLoss(db, excess) // recover meta-carried drops BEFORE the DELETE
            db.execSQL("""
                DELETE FROM $TABLE_EVENTS WHERE $COL_ID IN (
                    SELECT $COL_ID FROM $TABLE_EVENTS ORDER BY $COL_CREATED_AT ASC, $COL_ID ASC LIMIT $excess
                )
            """.trimIndent())
            ai.appdna.sdk.events.DroppedEventsCounter.increment(loss) // SPEC-428 CL-1/D2/STEP-4
            Log.warning("Event database overflow: dropped $excess oldest events (count cap; loss metric +$loss)")
        }

        // 2. Disk quota: the live events' bytes (iOS `EventStore`'s measure), not the SQLite file — the file
        // carries pages, three indexes and a free list, so measuring it evicted events well under 5 MB of them,
        // and (10 % once per insert) could stay over the quota. Drop the oldest 10 % until the events fit, as
        // iOS does. One transaction: the uploader's handle never sees a half-evicted table.
        var bytes = liveBytes(db)
        if (bytes <= maxDiskBytes) return
        var dropped = 0
        var loss = 0
        db.beginTransaction()
        try {
            var remaining = count()
            while (bytes > maxDiskBytes && remaining > 0) {
                val dropCount = maxOf(remaining / 10, 1)
                val passLoss = evictionLoss(db, dropCount) // recover meta-carried drops BEFORE the DELETE
                bytes -= oldestBytes(db, dropCount)
                db.execSQL("""
                    DELETE FROM $TABLE_EVENTS WHERE $COL_ID IN (
                        SELECT $COL_ID FROM $TABLE_EVENTS ORDER BY $COL_CREATED_AT ASC, $COL_ID ASC LIMIT $dropCount
                    )
                """.trimIndent())
                ai.appdna.sdk.events.DroppedEventsCounter.increment(passLoss)
                loss += passLoss
                dropped += dropCount
                remaining -= dropCount
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Log.warning("Event database disk quota enforced: dropped $dropped events (${maxDiskBytes / 1024}KB of live events; loss +$loss)")
    }
}
