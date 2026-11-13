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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * Two upload paths that stopped short of the backlog, and the WorkManager uploader's answer to a rejection:
 *  - the in-process queue's window (the newest 1,000 events) was never reloaded once it drained, so the older
 *    events of a larger backlog waited for the WorkManager uploader or the next launch;
 *  - the WorkManager uploader treated a 408 as permanent; after a permanent rejection it now ends its run (it went on
 *    with up to 50 more batches at once), and a 401 / 403 pauses uploads as the in-process queue does.
 * iOS `EventQueueWindowAndUploaderTests`, same rules.
 *
 * NEGATIVE CONTROLS (build Mac, patched sources): without the reload, 200 of the 1,200 events are never sent by the
 * queue (`db.count()` 200); with the old worker branches the 408 batch is dropped (0 left, counter 100), the run goes
 * on after the 400 (2 uploads, DONE, 0 left), and a 401 sets no pause.
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

    /**
     * A queue an earlier test built and never shut down keeps flushing, and taking its claim back once is
     * not enough: its NEXT flush, started after the reset, took the claim again and held it while it waited
     * on a server, so this test's flush deferred and sent nothing. That is the full-suite-only failure the
     * pause and runtime-settings tests showed, with the failing test moving with timing. Manufactured here: the stale
     * queue's flush hangs on a server that never answers, started after the reset, while a queue built after
     * the reset uploads.
     * NEGATIVE CONTROL: `tryAcquire` ignoring the generation → fails at "a queue built before the reset took
     * the upload claim again" (the stale flush holds it while its request hangs).
     */
    @Test fun aQueueBuiltBeforeTheResetCannotTakeTheClaimAgain() {
        val silent = TestHttpServer { _, _ -> null }   // accepts, reads, never answers
        val ok = AtomicInteger(0)
        val live = TestHttpServer { path, _ -> if (path.contains("/ingest/events")) ok.incrementAndGet(); 200 to "{}" }
        val staleDb = EventDatabase(ctx, 10_000, "stale_gen_${System.nanoTime()}.db")
        val liveDb = EventDatabase(ctx, 10_000, "live_gen_${System.nanoTime()}.db")
        val stale = EventQueue(ApiClient("adn_test_stale_gen", silent.baseUrl), staleDb, null, 100, Long.MAX_VALUE, ctx, null)
        val staleFlush = Thread { runCatching { runBlocking { stale.flushCycleForTest() } } }
        var current: EventQueue? = null
        try {
            stale.enqueue(event(0))
            // What the next test's setUp does.
            TestHttpServer.settleIoDispatcher()
            staleFlush.start()
            Thread.sleep(500)
            assertFalse("a queue built before the reset took the upload claim again", EventUploadCoordinator.isHeld())

            current = EventQueue(ApiClient("adn_test_live_gen", live.baseUrl), liveDb, null, 100, Long.MAX_VALUE, ctx, null)
            current.enqueue(event(1))
            runBlocking { current.flushCycleForTest() }
            assertEquals("the current queue's upload deferred to a queue from before the reset", 1, ok.get())
        } finally {
            silent.close(); live.close()
            staleFlush.join(15_000)
            stale.shutdown(); current?.shutdown()
            stale.awaitShutdownFlush(10_000); current?.awaitShutdownFlush(10_000)
        }
    }

    /**
     * In one JVM a queue an earlier test left uploading — to a server it has closed, or sleeping in
     * a retry backoff — still holds the process-wide upload claim, and this test's worker then deferred to
     * it: RETRY with nothing sent (the `1 ≠ 0 ingests` failure of [theWorkerEndsItsRunAfterARejectedBatch]
     * in the full suite). The per-test `TestHttpServer.settleIoDispatcher()` takes the claim back; the late owner's release then must not
     * free a claim taken after the reset, or two owners would upload at once.
     * NEGATIVE CONTROLS: a reset that leaves the claim held → it is still held, and in the full suite the next test's run returns RETRY having sent nothing;
     * a release that ignores whose claim it is → the claim is free while `next` still holds it.
     */
    @Test fun aClaimAnEarlierTestLeftHeldIsTakenBackAndItsLateReleaseFreesNothing() {
        val stale = EventUploadCoordinator.tryAcquire()
        assertNotNull("setUp left the claim free", stale)
        // What the next test's setUp does: drain the IO pool, which takes the claim back.
        TestHttpServer.settleIoDispatcher()
        assertFalse("settleIoDispatcher left an earlier test's claim held", EventUploadCoordinator.isHeld())
        val ingests = AtomicInteger(0)
        TestHttpServer { path, _ -> if (path.contains("/ingest/events")) ingests.incrementAndGet(); 200 to "{}" }.use { server ->
            val db = EventDatabase(ctx, 10_000, "worker_stale_claim_${System.nanoTime()}.db")
            try {
                ai.appdna.sdk.events.UploadPauseGate.setForTest(ctx, false)
                db.insertEvents((0 until 3).map { event(it).toString() })
                assertEquals(EventUploadWorker.Outcome.DONE,
                    EventUploadWorker.uploadPending(db, "adn_test_worker", server.baseUrl, context = ctx))
                assertEquals("the run sent nothing — it deferred to the stale claim", 1, ingests.get())
                assertEquals(0, db.count())
            } finally {
                db.close()
            }
        }
        val next = EventUploadCoordinator.tryAcquire()
        assertNotNull("the worker did not release its own claim", next)
        EventUploadCoordinator.release(stale!!)
        assertTrue("the stale owner's late release freed the claim another owner holds", EventUploadCoordinator.isHeld())
        assertNull(EventUploadCoordinator.tryAcquire())
        EventUploadCoordinator.release(next!!)
        assertFalse(EventUploadCoordinator.isHeld())
    }

    /**
     * A permanently rejected batch is dropped and counted, and the run ends there (as iOS `BackgroundUploader`'s does):
     * a fresh run sends the rest. (A 401 / 403 keeps the batch and pauses uploads: `aRejectedKeyPausesUploadsFromTheWorker`.)
     */
    @Test fun theWorkerEndsItsRunAfterARejectedBatch() {
        val ingests = AtomicInteger(0)
        TestHttpServer { path, _ ->
            if (path.contains("/ingest/events") && ingests.getAndIncrement() == 0) 400 to TestHttpServer.INGEST_REFUSAL_BODY else 200 to "{}"
        }.use { server ->
            val db = EventDatabase(ctx, 10_000, "worker_reject_${System.nanoTime()}.db")
            try {
                ai.appdna.sdk.events.UploadPauseGate.setForTest(ctx, false)
                db.insertEvents((0 until 150).map { event(it).toString() })
                assertEquals(EventUploadWorker.Outcome.RETRY,
                    EventUploadWorker.uploadPending(db, "adn_test_worker", server.baseUrl, context = ctx))
                assertEquals("the run went on after the rejected batch", 1, ingests.get())
                assertEquals(50, db.count())
                assertEquals("the rejected batch is counted", 100, DroppedEventsCounter.peek())
                assertEquals(false, ai.appdna.sdk.events.UploadPauseGate.isPaused(ctx))
                // The next run sends the events behind it.
                assertEquals(EventUploadWorker.Outcome.DONE,
                    EventUploadWorker.uploadPending(db, "adn_test_worker", server.baseUrl, context = ctx))
                assertEquals(0, db.count())
            } finally {
                db.close()
            }
        }
    }

    /** A 401 KEEPS the batch, pauses uploads (the gate the queue honours) and retries nothing. */
    @Test fun aRejectedKeyPausesUploadsFromTheWorker() {
        TestHttpServer { _, _ -> 401 to "{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"Invalid API key\"}}" }.use { server ->
            val db = EventDatabase(ctx, 10_000, "worker_401_${System.nanoTime()}.db")
            try {
                ai.appdna.sdk.events.UploadPauseGate.setForTest(ctx, false)
                db.insertEvents((0 until 150).map { event(it).toString() })
                assertEquals(EventUploadWorker.Outcome.DONE,
                    EventUploadWorker.uploadPending(db, "adn_test_worker", server.baseUrl, context = ctx))
                assertEquals("a 401 deleted events", 150, db.count())
                assertEquals("a 401 counted a loss", 0, DroppedEventsCounter.peek())
                assertEquals(true, ai.appdna.sdk.events.UploadPauseGate.isPaused(ctx))
                // The paused gate holds for the next run.
                assertEquals(EventUploadWorker.Outcome.DONE, EventUploadWorker.uploadPending(db, "adn_test_worker",
                    server.baseUrl, paused = ai.appdna.sdk.events.UploadPauseGate.isPaused(ctx), context = ctx))
                assertEquals("a paused run sent a batch", 150, db.count())
            } finally {
                ai.appdna.sdk.events.UploadPauseGate.setForTest(ctx, false)
                db.close()
            }
        }
    }

    /**
     * The outage the SDK used to lose events in: a tunnel in front of the API answers 401 with its own HTML page while
     * the server is down. Every event is kept and nothing is counted as dropped; once the server is back, the host's
     * `AppDNA.flush()` (it clears the pause) sends all of them, each once.
     */
    @Test fun aTunnel401KeepsEveryEventAndTheyAreSentWhenTheServerIsBack() {
        val serverUp = java.util.concurrent.atomic.AtomicBoolean(false)
        val tunnelPage = "<!DOCTYPE html><html><head><title>401 Unauthorized</title></head><body><h1>Unauthorized</h1></body></html>"
        TestHttpServer { path, _ ->
            if (!path.contains("/ingest/events")) 200 to "{}"
            else if (serverUp.get()) 202 to "{\"accepted\":1,\"rejected\":0}"
            else 401 to tunnelPage
        }.use { server ->
            val db = EventDatabase(ctx, 10_000, "tunnel_401_${System.nanoTime()}.db")
            val events = (0 until 150).map { event(it) }
            db.insertEvents(events.map { it.toString() })
            ai.appdna.sdk.events.UploadPauseGate.setForTest(ctx, false)
            val q = EventQueue(ApiClient("adn_test_tunnel", server.baseUrl), db, null, 100, Long.MAX_VALUE, ctx, null)
            try {
                // The outage: the queue's flush and a background run, both answered by the tunnel.
                runBlocking { q.flushCycleForTest() }
                assertEquals("the in-process queue deleted events on a tunnel 401", 150, db.count())
                assertEquals(true, ai.appdna.sdk.events.UploadPauseGate.isPaused(ctx))
                ai.appdna.sdk.events.UploadPauseGate.setForTest(ctx, false)
                assertEquals(EventUploadWorker.Outcome.DONE,
                    EventUploadWorker.uploadPending(db, "adn_test_tunnel", server.baseUrl, context = ctx))
                assertEquals("the background run deleted events on a tunnel 401", 150, db.count())
                assertEquals("a tunnel 401 was counted as a loss", 0, DroppedEventsCounter.peek())

                // The server is back: the host's flush clears the pause and sends everything.
                serverUp.set(true)
                val deadline = System.currentTimeMillis() + 20_000
                while (db.count() > 0 && System.currentTimeMillis() < deadline) {
                    q.flush()
                    Thread.sleep(300)
                }
                assertEquals("events left after the server came back", 0, db.count())
                val delivered = synchronized(server.rawBodies) {
                    server.rawBodies.filter { it.first.contains("/ingest/events") }.map { it.second }
                }.drop(2).flatMap { ids(it) } // the first two uploads were the tunnel's
                assertEquals("an event was missed or sent twice", events.map { it.getString("event_id") }.sorted(), delivered.sorted())
                assertEquals(0, DroppedEventsCounter.peek())
            } finally {
                q.shutdown()
                q.awaitShutdownFlush(10_000)
                ai.appdna.sdk.events.UploadPauseGate.setForTest(ctx, false)
                db.clearAll(); db.close()
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
