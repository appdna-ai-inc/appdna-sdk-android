package ai.appdna.sdk

import ai.appdna.sdk.background.EventUploadWorker
import ai.appdna.sdk.events.DroppedEventsCounter
import ai.appdna.sdk.events.EventQueue
import ai.appdna.sdk.events.EventUploadCoordinator
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.network.TestHttpServer
import ai.appdna.sdk.storage.EventDatabase
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Two upload paths that stopped short of the backlog:
 *  - the in-process queue's window (the newest 1,000 events) was never reloaded once it drained, so the older
 *    events of a larger backlog waited for the WorkManager uploader or the next launch;
 *  - the WorkManager uploader ended its run on a permanently rejected batch (and treated a 408 as permanent).
 * iOS `EventQueueWindowAndUploaderTests`, same rules.
 *
 * NEGATIVE CONTROLS (build Mac, patched sources — status file round 34): without the reload, 200 of the 1,200
 * events are never sent by the queue (`db.count()` 200); with the old worker branches, the run answers FAILED
 * after the 400 with 50 events left, and the 408 batch is dropped (0 left, counter 100).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventWindowAndWorkerTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var savedDropped = 0

    @Before fun setUp() {
        TestHttpServer.settleIoDispatcher()
        DroppedEventsCounter.init(ctx)
        savedDropped = DroppedEventsCounter.getAndReset()
        EventUploadCoordinator.clearResolvedForTest()
    }

    @After fun tearDown() {
        DroppedEventsCounter.getAndReset()
        if (savedDropped > 0) DroppedEventsCounter.increment(savedDropped)
        EventUploadCoordinator.clearResolvedForTest()
    }

    private fun event(i: Int) = JSONObject()
        .put("schema_version", 1).put("event_id", "window-$i-${System.nanoTime()}").put("event_name", "window_$i")
        .put("ts_ms", System.currentTimeMillis()).put("properties", JSONObject().put("n", i))
        .put("user", JSONObject().put("anon_id", "window-anon"))
        .put("context", JSONObject().put("session_id", "window-session"))

    private fun ids(body: ByteArray): List<String> {
        val raw = try {
            InflaterInputStream(ByteArrayInputStream(body), Inflater(true)).readBytes()
        } catch (_: Exception) { body }
        val batch = JSONObject(String(raw, Charsets.UTF_8)).getJSONArray("batch")
        return (0 until batch.length()).map { batch.getJSONObject(it).getString("event_id") }
    }

    /** A 1,200-event backlog: the queue loads its newest 1,000, and keeps sending until every event is sent. */
    @Test fun theQueueReloadsItsDrainedWindowAndSendsTheWholeBacklog() {
        TestHttpServer { _, _ -> 200 to "{}" }.use { server ->
            val db = EventDatabase(ctx, 10_000, "window_${System.nanoTime()}.db")
            val events = (0 until 1_200).map { event(it) }
            db.insertEvents(events.map { it.toString() })
            val q = EventQueue(ApiClient("adn_test_window", server.baseUrl), db, null, null, Long.MAX_VALUE, ctx, null)
            try {
                assertEquals(EventQueue.MAX_IN_MEMORY_EVENTS, q.inMemoryCountForTest)
                repeat(20) { if (db.count() > 0) runBlocking { q.flushCycleForTest() } }
                assertEquals("events of the backlog were never sent by the in-process queue", 0, db.count())
                val sent = server.rawBodies.filter { it.first.contains("/ingest/events") }.flatMap { ids(it.second) }
                assertEquals(1_200, sent.size)
                assertEquals("an event was sent twice", 1_200, sent.toSet().size)
                assertEquals(events.map { it.getString("event_id") }.toSet(), sent.toSet())
            } finally {
                q.shutdown()
                q.awaitShutdownFlush(10_000)
            }
        }
    }

    /** A permanently rejected batch is dropped and counted, and the run goes on with the next one. */
    @Test fun theWorkerDropsARejectedBatchAndGoesOn() {
        val ingests = AtomicInteger(0)
        TestHttpServer { path, _ ->
            if (path.contains("/ingest/events") && ingests.getAndIncrement() == 0) 400 to "{}" else 200 to "{}"
        }.use { server ->
            val db = EventDatabase(ctx, 10_000, "worker_reject_${System.nanoTime()}.db")
            try {
                db.insertEvents((0 until 150).map { event(it).toString() })
                assertEquals(EventUploadWorker.Outcome.DONE, EventUploadWorker.uploadPending(db, "adn_test_worker", server.baseUrl))
                assertEquals("the events behind the rejected batch were not sent", 0, db.count())
                assertEquals(2, ingests.get())
                assertEquals("the rejected batch is counted", 100, DroppedEventsCounter.peek())
            } finally {
                db.close()
            }
        }
    }

    /** A 408 is transient (the in-process queue's and iOS's classifier): retried, nothing dropped. */
    @Test fun theWorkerRetriesA408() {
        TestHttpServer { _, _ -> 408 to "{}" }.use { server ->
            val db = EventDatabase(ctx, 10_000, "worker_408_${System.nanoTime()}.db")
            try {
                db.insertEvents((0 until 100).map { event(it).toString() })
                assertEquals(EventUploadWorker.Outcome.RETRY, EventUploadWorker.uploadPending(db, "adn_test_worker", server.baseUrl))
                assertEquals("a 408 dropped the batch", 100, db.count())
                assertEquals(0, DroppedEventsCounter.peek())
            } finally {
                db.close()
            }
        }
    }
}
