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
 * opens the database, two helpers creating or upgrading the same file, a database a newer SDK wrote, the disk quota
 * and its running totals.
 *
 * NEGATIVE CONTROLS (build Mac, patched sources):
 *  - [twoHelpersUpgradingTheSameFileBothOpenIt]: with the unconditional `ALTER TABLE … ADD COLUMN` back, the
 *    second upgrade throws "duplicate column name: event_id".
 *  - [twoHelpersCreatingANewFileBothOpenIt]: with a plain `CREATE TABLE` back, the second `onCreate` throws
 *    "table pending_events already exists".
 *  - [aDatabaseANewerSdkWroteOpensWithItsEvents]: with no `onDowngrade` (the default throws), opening it throws
 *    "Can't downgrade database from version 9 to 4".
 *  - [aTenThousandRowV2BacklogUpgradesWithinItsBound] is a regression bound, not a discriminating control: on the
 *    JVM (Robolectric's SQLite) the per-row `ContentValues` update took 257–274 ms and the compiled, chunked
 *    statement 216–219 ms for 10,000 rows (3 runs each); the gain on a device's SQLite, where each `db.update`
 *    compiles its statement, is larger. With the unconditional `ALTER` back it fails outright.
 *  - [theQuotaCountsTheLiveEventBytesAndEvictsUntilTheyFit]: with the quota measuring the SQLite file, the
 *    table is emptied (nothing survives a 20,000-byte quota: the file alone is larger).
 *  - [anInsertOnATenThousandRowTableStaysFast]: with the quota summing the table on every insert, an insert on the
 *    10,000-row table is many times slower than on an empty one.
 *  - [anEvictionThatRollsBackCountsNothing]: with the loss counted inside the eviction's transaction, the counter
 *    keeps the rolled-back drop.
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

    /** A 10,000-row v2 backlog: one chunked back-fill with a compiled statement, within a bound. */
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
     * `SQLiteOpenHelper` reads the version before it takes the write lock: two helpers on one v2 file
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

    /** A newer SDK wrote version 9 with an extra optional column: this SDK keeps the events. */
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

    /** A newer SDK's table this SDK cannot write (a required column it does not know): rebuilt, counted. */
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

    /** The quota is the live events' bytes; eviction takes the oldest 10 % until they fit, counted. */
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

    /** A helper's running totals equal the table, by count and by live bytes. */
    private fun assertTotalsMatch(db: EventDatabase, why: String) {
        assertEquals("$why: live bytes", db.scannedLiveBytesForTest(), db.liveBytesForTest())
        val rows = db.writableDatabase.rawQuery("SELECT row_count FROM event_stats WHERE id = 1", null)
            .use { if (it.moveToFirst()) it.getInt(0) else -1 }
        assertEquals("$why: row count", db.count(), rows)
    }

    /**
     * The quota reads running totals, not the table: inserting (what `track()` does) on a 10,000-row table needs no
     * scan and costs about what it costs on an empty one. The quota used to SUM the table — and `COUNT(*)` it — on
     * every insert, on the caller's thread.
     */
    @Test fun anInsertOnATenThousandRowTableStaysFast() {
        val big = EventDatabase(ctx, 50_000, "schema_fast_big_${System.nanoTime()}.db", 64 * 1024 * 1024)
        val small = EventDatabase(ctx, 50_000, "schema_fast_small_${System.nanoTime()}.db", 64 * 1024 * 1024)
        try {
            big.insertEvents((0 until 10_000).map { event(it).toString() })
            assertEquals(10_000, big.count())
            fun timeInserts(db: EventDatabase, from: Int): Long {
                repeat(20) { db.insertEvent(event(from + it)) }   // warm-up
                val start = System.nanoTime()
                repeat(300) { db.insertEvent(event(from + 100 + it)) }
                return System.nanoTime() - start
            }
            val scans = big.liveBytesScansForTest
            val bigNs = timeInserts(big, 20_000)
            val smallNs = timeInserts(small, 30_000)
            println("[schema] 300 inserts: 10,000-row table ${bigNs / 1_000_000} ms, empty table ${smallNs / 1_000_000} ms")
            assertEquals("an insert scanned the table", scans, big.liveBytesScansForTest)
            assertTrue("inserts on the 10,000-row table took ${bigNs / 1_000_000} ms vs ${smallNs / 1_000_000} ms on an empty one",
                bigNs < smallNs * 3 + 200_000_000L)
            assertTotalsMatch(big, "after the inserts")
        } finally {
            big.close(); small.close()
        }
    }

    /** Every write path keeps the totals right, and an open that finds them missing or off rebuilds them. */
    @Test fun theTotalsFollowEveryWriteAndAreRebuiltWhenOff() {
        val name = "schema_totals_${System.nanoTime()}.db"
        val db = EventDatabase(ctx, 100, name, 30_000)
        try {
            val old = System.currentTimeMillis() - EventDatabase.REDELIVERY_HORIZON_MS - 60_000
            val rows = (0 until 60).map { event(it, if (it < 5) old else System.currentTimeMillis()).toString() }
            db.insertEvents(rows)
            assertTotalsMatch(db, "batch insert")
            db.insertEvent(event(500))
            assertTotalsMatch(db, "insert")
            db.removeByEventIds(setOf(JSONObject(rows[10]).getString("event_id"), JSONObject(rows[11]).getString("event_id")))
            assertTotalsMatch(db, "remove by event id")
            db.removeByIds(db.loadBatch(3).map { it.first })
            assertTotalsMatch(db, "remove by row id")
            db.pruneStale()
            assertTotalsMatch(db, "prune")
            db.insertEvents((0 until 200).map { event(1_000 + it).toString() })   // past the count cap and the quota
            assertTrue(db.count() <= 100)
            assertTrue(db.liveBytesForTest() <= 30_000)
            assertTotalsMatch(db, "eviction")
            db.clearAll()
            assertEquals(0L, db.liveBytesForTest())
            assertTotalsMatch(db, "clear")
            db.insertEvents((0 until 7).map { event(2_000 + it).toString() })

            // Totals that do not match the table (written outside the SDK): the next open rebuilds them.
            db.writableDatabase.execSQL("UPDATE event_stats SET row_count = 0, live_bytes = 0")
            db.close()
            val reopened = EventDatabase(ctx, 100, name, 30_000)
            try {
                assertEquals(7, reopened.count())
                assertEquals(1, reopened.statsRebuildsForTest)
                assertTotalsMatch(reopened, "rebuilt after a mismatch")
                // An SDK without the triggers wrote rows (a rollback): rebuilt too.
                reopened.writableDatabase.execSQL("DROP TRIGGER trg_events_stats_insert")
                reopened.writableDatabase.execSQL("INSERT INTO pending_events (event_json, created_at) VALUES (?, 0)", arrayOf(event(3_000).toString()))
                reopened.close()
                val third = EventDatabase(ctx, 100, name, 30_000)
                try {
                    assertEquals(8, third.count())
                    assertEquals(1, third.statsRebuildsForTest)
                    assertTotalsMatch(third, "rebuilt after missing triggers")
                    third.insertEvent(event(3_001))
                    assertTotalsMatch(third, "the trigger is back")
                } finally {
                    third.close()
                }
            } finally {
                reopened.close()
            }
        } finally {
            db.close()
        }
    }

    /** An upgrade from v2 builds the totals from the upgraded rows. */
    @Test fun anUpgradeBuildsTheTotals() {
        val name = "schema_totals_v2_${System.nanoTime()}.db"
        createV2(name, (0 until 25).map { event(it).toString() })
        val db = EventDatabase(ctx, 10_000, name)
        try {
            assertEquals(25, db.count())
            assertTotalsMatch(db, "after the v2 upgrade")
        } finally {
            db.close()
        }
    }

    /**
     * The eviction is one transaction, and its loss is counted once, after the commit: a rollback (here a fault after
     * the first DELETE) drops nothing and counts nothing. It used to count each pass inside the transaction.
     */
    @Test fun anEvictionThatRollsBackCountsNothing() {
        val db = EventDatabase(ctx, 10_000, "schema_rollback_${System.nanoTime()}.db", 20_000)
        try {
            var n = 0
            while (db.liveBytesForTest() + event(n).toString().length + 1 <= 20_000) db.insertEvent(event(n++))
            val before = db.count()
            assertEquals(0, DroppedEventsCounter.peek())
            db.evictionFaultForTest = { throw IllegalStateException("fault after an eviction DELETE") }
            db.insertEvent(event(10_000))   // over the quota: the eviction rolls back (the insert itself committed)
            assertEquals("the rolled-back eviction dropped events", before + 1, db.count())
            assertEquals("the rolled-back eviction was counted", 0, DroppedEventsCounter.peek())
            assertTotalsMatch(db, "after the rollback")

            db.evictionFaultForTest = null
            db.insertEvent(event(10_001))
            val dropped = before + 2 - db.count()
            assertTrue("the next insert evicts", dropped > 0)
            assertEquals("a committed eviction is counted once", dropped, DroppedEventsCounter.peek())
            assertTotalsMatch(db, "after the eviction")
        } finally {
            db.close()
        }
    }

    /**
     * `SQLiteOpenHelper` reads the version before it takes the write lock: two helpers opening a NEW file (the queue's
     * and the WorkManager uploader's) can both see version 0 and both run `onCreate`, the second after the first
     * committed. The second `onCreate` must find everything there and change nothing; and two helpers racing to open
     * a new file both open it.
     */
    @Test fun twoHelpersCreatingANewFileBothOpenIt() {
        val name = "schema_create_race_${System.nanoTime()}.db"
        val first = EventDatabase(ctx, 10_000, name)
        val second = EventDatabase(ctx, 10_000, name)
        try {
            first.insertEvent(event(1))   // the first helper creates the file
            val db = first.writableDatabase
            db.beginTransaction()
            try {
                second.onCreate(db)        // what the race makes the second helper do
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            second.insertEvent(event(2))
            assertEquals(2, first.count())
            assertTotalsMatch(first, "after the second create")
        } finally {
            first.close(); second.close()
        }

        repeat(10) { round ->
            val raceName = "schema_create_race_${round}_${System.nanoTime()}.db"
            val helpers = listOf(EventDatabase(ctx, 10_000, raceName), EventDatabase(ctx, 10_000, raceName))
            val barrier = java.util.concurrent.CyclicBarrier(2)
            val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
            val threads = helpers.mapIndexed { i, h ->
                Thread {
                    try {
                        barrier.await()
                        h.writableDatabase
                        h.insertEvent(event(round * 10 + i))
                    } catch (t: Throwable) { errors += t }
                }.also { it.start() }
            }
            threads.forEach { it.join(10_000) }
            try {
                assertTrue("a helper could not open the new file: $errors", errors.isEmpty())
                assertEquals(2, helpers[0].count())
            } finally {
                helpers.forEach { it.close() }
            }
        }
    }
}
