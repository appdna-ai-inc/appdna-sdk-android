package ai.appdna.sdk

import ai.appdna.sdk.events.EventQueue
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.storage.EventDatabase
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A 10,000-event backlog must not make every flush, or the next `configure()`, pay for the whole backlog.
 *
 * Before: `pruneStale` parsed every row's JSON inside an exclusive transaction on every flush (the next
 * configure's read waited on that lock, on the caller's — usually the main — thread), `removeByEventIds`
 * parsed every row again, and `EventQueue`'s constructor (inside `configure()`) read and parsed all 10,000
 * rows. Now the prune and the removal are SQL on the `event_id` / `ts_ms` columns (schema v3) and the queue
 * reads only its in-memory window.
 *
 * The timing test uses only APIs the old database had, so it measures the tree before the fix too. The
 * migration test guards the v3 upgrade (an older SDK's rows must stay prunable and removable once the prune
 * and the removal read the new columns). NEGATIVE CONTROL: with the columns left unfilled on upgrade the
 * migration test fails (nothing pruned, nothing removed); the shared `flush_cost_bounded` fixture fails on
 * the old JSON-parsing prune / removal (thousands of rows parsed per flush).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventBacklogCostTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun event(name: String, ts: Long = System.currentTimeMillis(), props: JSONObject = JSONObject().put("k", "v")) =
        JSONObject().put("schema_version", 1).put("event_id", "$name-${System.nanoTime()}").put("event_name", name)
            .put("ts_ms", ts).put("properties", props)
            .put("user", JSONObject().put("anon_id", "backlog-anon"))
            .put("context", JSONObject().put("session_id", "backlog-session"))

    @Test
    fun aTenThousandEventBacklogCostsEveryFlushOnlyItsBatch() {
        val db = EventDatabase(ctx, 10_000, "backlog_cost_${System.nanoTime()}.db")
        try {
            db.insertEvents((0 until 10_000).map { event("backlog_$it").toString() })
            assertEquals(10_000, db.count())

            val queueStart = System.nanoTime()
            val q = EventQueue(ApiClient("adn_test_backlog", "http://127.0.0.1:9"), db, null, 50, Long.MAX_VALUE, ctx, null)
            val queueMs = (System.nanoTime() - queueStart) / 1_000_000

            val start = System.nanoTime()
            repeat(20) {
                db.pruneStale(EventDatabase.REDELIVERY_HORIZON_MS)
                val rows = db.loadBatch(50)
                db.removeByEventIds(rows.map { JSONObject(it.second).getString("event_id") }.toSet())
            }
            val flushMs = (System.nanoTime() - start) / 1_000_000
            println("[backlog] 10,000 rows: EventQueue() ${queueMs} ms; 20 flush cycles (prune + batch of 50 + remove) ${flushMs} ms")
            assertEquals(10_000 - 20 * 50, db.count())
            // Generous bounds: both are tens of milliseconds on the JVM after the fix.
            assertTrue("EventQueue() took $queueMs ms on a 10,000-event backlog", queueMs < 3_000)
            assertTrue("20 flush cycles took $flushMs ms on a 10,000-event backlog", flushMs < 5_000)
            q.shutdown()
            q.awaitShutdownFlush(10_000)
        } finally {
            db.close()
        }
    }

    /** A database an older SDK wrote (schema v2: JSON only) gets the lookup columns, filled from its rows. */
    @Test
    fun aVersionTwoDatabaseIsMigratedAndItsRowsArePrunableAndRemovable() {
        val name = "backlog_v2_${System.nanoTime()}.db"
        val file = File(ctx.noBackupFilesDir, name)
        file.parentFile?.mkdirs()
        val now = System.currentTimeMillis()
        val old = now - EventDatabase.REDELIVERY_HORIZON_MS - 60_000
        val fresh = event("fresh")
        val keep = event("keep")
        SQLiteDatabaseFactory.v2(file).use { raw ->
            fun put(json: String) = raw.execSQL("INSERT INTO pending_events (event_json, created_at) VALUES (?, ?)", arrayOf(json, now))
            put(event("stale").put("ts_ms", old).toString())
            put(event("_sdk_events_dropped", old, JSONObject().put("count", 4)).toString())
            put(fresh.toString())
            put(keep.toString())
            put("{not json")
        }
        ai.appdna.sdk.events.DroppedEventsCounter.init(ctx)
        val saved = ai.appdna.sdk.events.DroppedEventsCounter.getAndReset()
        val db = EventDatabase(ctx, 10_000, name)
        try {
            assertEquals(5, db.count())
            assertEquals("the stale event and the stale meta", 2, db.pruneStale(EventDatabase.REDELIVERY_HORIZON_MS))
            assertEquals("1 for the event, the meta's carried 4", 5, ai.appdna.sdk.events.DroppedEventsCounter.peek())
            db.removeByEventIds(setOf(fresh.getString("event_id")))
            val left = db.loadAll()
            assertEquals(listOf(keep.toString(), "{not json"), left)
        } finally {
            ai.appdna.sdk.events.DroppedEventsCounter.getAndReset()
            if (saved > 0) ai.appdna.sdk.events.DroppedEventsCounter.increment(saved)
            db.close()
        }
    }

    /** The in-memory window is the newest rows, oldest first. */
    @Test
    fun loadNewestReturnsTheNewestRowsOldestFirst() {
        val db = EventDatabase(ctx, 10_000, "backlog_newest_${System.nanoTime()}.db")
        try {
            val all = (0 until 20).map { event("e_$it").toString() }
            db.insertEvents(all)
            assertEquals(all.takeLast(5), db.loadNewest(5))
        } finally {
            db.close()
        }
    }

    private object SQLiteDatabaseFactory {
        /** The v2 schema exactly as `EventDatabase` created it before the lookup columns. */
        fun v2(file: File): android.database.sqlite.SQLiteDatabase {
            val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
            db.execSQL(
                "CREATE TABLE pending_events (_id INTEGER PRIMARY KEY AUTOINCREMENT, event_json TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now') * 1000))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_created_at ON pending_events(created_at)")
            db.version = 2
            return db
        }
    }
}
