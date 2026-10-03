package ai.appdna.sdk.storage

import ai.appdna.sdk.events.DroppedEventsCounter
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The event database's schema changes on a real device: a large v2 backlog upgraded on the thread that first
 * opens the database, two helpers upgrading the same file, a database a newer SDK wrote, and the disk quota.
 *
 * NEGATIVE CONTROLS (build Mac, patched sources — status file round 34):
 *  - [twoHelpersUpgradingTheSameFileBothOpenIt]: with the unconditional `ALTER TABLE … ADD COLUMN` back, the
 *    second upgrade throws "duplicate column name: event_id".
 *  - [aDatabaseANewerSdkWroteOpensWithItsEvents]: with no `onDowngrade` (the default throws), opening it throws
 *    "Can't downgrade database from version 9 to 4".
 *  - [aTenThousandRowV2BacklogUpgradesWithinItsBound] is a regression bound, not a discriminating control: on the
 *    JVM (Robolectric's SQLite) the per-row `ContentValues` update took 257–274 ms and the compiled, chunked
 *    statement 216–219 ms for 10,000 rows (3 runs each, status file round 34); the gain on a device's SQLite, where
 *    each `db.update` compiles its statement, is larger. With the unconditional `ALTER` back it fails outright.
 *  - [theQuotaCountsTheLiveEventBytesAndEvictsUntilTheyFit]: with the quota measuring the SQLite file, the
 *    table is emptied (nothing survives a 20,000-byte quota: the file alone is larger).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventDatabaseSchemaTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var savedDropped = 0

    @Before fun setUp() {
        DroppedEventsCounter.init(ctx)
        savedDropped = DroppedEventsCounter.getAndReset()
    }

    @After fun tearDown() {
        DroppedEventsCounter.getAndReset()
        if (savedDropped > 0) DroppedEventsCounter.increment(savedDropped)
    }

    private fun event(i: Int, ts: Long = System.currentTimeMillis()) = JSONObject()
        .put("schema_version", 1).put("event_id", "schema-$i-${System.nanoTime()}").put("event_name", "schema_$i")
        .put("ts_ms", ts).put("properties", JSONObject().put("k", "v").put("n", i))
        .put("user", JSONObject().put("anon_id", "schema-anon"))
        .put("context", JSONObject().put("session_id", "schema-session"))

    private fun file(name: String) = File(ctx.noBackupFilesDir, name).also { it.parentFile?.mkdirs() }

    /** The v2 schema exactly as `EventDatabase` created it before the lookup columns. */
    private fun createV2(name: String, rows: List<String>) {
        SQLiteDatabase.openOrCreateDatabase(file(name), null).use { raw ->
            raw.execSQL(
                "CREATE TABLE pending_events (_id INTEGER PRIMARY KEY AUTOINCREMENT, event_json TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now') * 1000))"
            )
            raw.execSQL("CREATE INDEX IF NOT EXISTS idx_events_created_at ON pending_events(created_at)")
            raw.beginTransaction()
            try {
                val now = System.currentTimeMillis()
                for (json in rows) raw.execSQL("INSERT INTO pending_events (event_json, created_at) VALUES (?, ?)", arrayOf(json, now))
                raw.setTransactionSuccessful()
            } finally {
                raw.endTransaction()
            }
            raw.version = 2
        }
    }

    /** Item 7. A 10,000-row v2 backlog: one chunked back-fill with a compiled statement, within a bound. */
    @Test fun aTenThousandRowV2BacklogUpgradesWithinItsBound() {
        val name = "schema_v2_large_${System.nanoTime()}.db"
        val rows = (0 until 10_000).map { event(it).toString() }
        createV2(name, rows)
        val db = EventDatabase(ctx, 10_000, name)
        try {
            val start = System.nanoTime()
            assertEquals(10_000, db.count())   // the first open runs the upgrade
            val ms = (System.nanoTime() - start) / 1_000_000
            println("[schema] v2 → v${EventDatabase.DATABASE_VERSION} upgrade of 10,000 rows: $ms ms")
            assertTrue("the v2 upgrade of 10,000 rows took $ms ms", ms < 4_000)
            // Every row got its lookup columns and size: removal by event id and the quota work on them.
            val first = JSONObject(rows[0]).getString("event_id")
            db.removeByEventIds(setOf(first))
            assertEquals(9_999, db.count())
            val expectedBytes = rows.drop(1).sumOf { it.toByteArray(Charsets.UTF_8).size + 1L }
            assertEquals(expectedBytes, db.liveBytesForTest())
        } finally {
            db.close()
        }
    }

    /**
     * Item 8. `SQLiteOpenHelper` reads the version before it takes the write lock: two helpers on one v2 file
     * (the queue's and the WorkManager uploader's) can both run `onUpgrade(2, 4)`, the second after the first
     * committed. The second must find the columns there and change nothing.
     */
    @Test fun twoHelpersUpgradingTheSameFileBothOpenIt() {
        val name = "schema_race_${System.nanoTime()}.db"
        val rows = (0 until 50).map { event(it).toString() }
        createV2(name, rows)
        val first = EventDatabase(ctx, 10_000, name)
        val second = EventDatabase(ctx, 10_000, name)
        try {
            assertEquals(50, first.count())   // the first helper upgrades
            // The second helper's upgrade, run on the upgraded file — what the race makes it do.
            val db = first.writableDatabase
            db.beginTransaction()
            try {
                second.onUpgrade(db, 2, EventDatabase.DATABASE_VERSION)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            assertEquals(50, second.count())
            second.removeByEventIds(setOf(JSONObject(rows[3]).getString("event_id")))
            assertEquals(49, first.count())
        } finally {
            first.close(); second.close()
        }
    }

    /** Item 10. A newer SDK wrote version 9 with an extra optional column: this SDK keeps the events. */
    @Test fun aDatabaseANewerSdkWroteOpensWithItsEvents() {
        val name = "schema_downgrade_${System.nanoTime()}.db"
        val rows = (0 until 5).map { event(it).toString() }
        EventDatabase(ctx, 10_000, name).apply { insertEvents(rows); close() }
        SQLiteDatabase.openDatabase(file(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("ALTER TABLE pending_events ADD COLUMN priority INTEGER")
            raw.version = 9
        }
        val db = EventDatabase(ctx, 10_000, name)
        try {
            assertEquals(5, db.count())
            assertEquals(rows, db.loadAll())
            db.insertEvent(event(99).toString())
            assertEquals(6, db.count())
            assertEquals(0, DroppedEventsCounter.peek())
        } finally {
            db.close()
        }
    }

    /** Item 10. A newer SDK's table this SDK cannot write (a required column it does not know): rebuilt, counted. */
    @Test fun anUnusableNewerTableIsRebuiltAndItsEventsCounted() {
        val name = "schema_downgrade_incompatible_${System.nanoTime()}.db"
        SQLiteDatabase.openOrCreateDatabase(file(name), null).use { raw ->
            raw.execSQL("CREATE TABLE pending_events (_id INTEGER PRIMARY KEY AUTOINCREMENT, event_json TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL DEFAULT 0, shard TEXT NOT NULL)")
            for (i in 0 until 3) raw.execSQL("INSERT INTO pending_events (event_json, shard) VALUES (?, 'a')", arrayOf(event(i).toString()))
            raw.version = 9
        }
        val db = EventDatabase(ctx, 10_000, name)
        try {
            assertEquals(0, db.count())
            assertEquals("the 3 events the rebuild dropped are counted", 3, DroppedEventsCounter.peek())
            db.insertEvent(event(7).toString())
            assertEquals(1, db.count())
        } finally {
            db.close()
        }
    }

    /** Item 9. The quota is the live events' bytes; eviction takes the oldest 10 % until they fit, counted. */
    @Test fun theQuotaCountsTheLiveEventBytesAndEvictsUntilTheyFit() {
        val db = EventDatabase(ctx, 10_000, "schema_quota_${System.nanoTime()}.db", 20_000)
        try {
            val events = (0 until 150).map { event(it).toString() }
            for (e in events) db.insertEvent(e)
            val left = db.loadAll()
            assertTrue("live ${db.liveBytesForTest()} over the quota", db.liveBytesForTest() <= 20_000)
            assertTrue("evicted far more than the quota needs (live ${db.liveBytesForTest()})", db.liveBytesForTest() > 16_000)
            assertEquals(events.takeLast(left.size), left)
            assertEquals(150 - left.size, DroppedEventsCounter.peek())
            // One insert far over the quota: evicted until it fits, in one pass.
            db.insertEvents((0 until 40).map { event(100 + it).toString() })
            assertTrue(db.liveBytesForTest() <= 20_000)
        } finally {
            db.close()
        }
    }
}
