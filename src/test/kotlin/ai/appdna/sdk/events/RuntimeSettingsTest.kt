package ai.appdna.sdk.events

import ai.appdna.sdk.AppDNAOptions
import ai.appdna.sdk.background.EventUploadWorker
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.network.TestHttpServer
import ai.appdna.sdk.storage.EventDatabase
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * `batchSize` as a cap on the adaptive batch size — the flush threshold and the upload size of the REAL
 * [EventQueue], the WorkManager uploader's batch size, and what a bootstrap's settings change on a running
 * queue. The precedence and cap tables are the shared fixture `resilience/runtime_settings_precedence`
 * (ResilienceFixtureTest); iOS `RuntimeSettingsTests`, same contract.
 *
 * NEGATIVE CONTROLS (build Mac, patched sources — status file round 33): the queue ignoring its cap → the
 * threshold / upload-size test fails; the worker ignoring the persisted cap → the worker test fails (1
 * request of 5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeSettingsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: EventDatabase
    private val queues = mutableListOf<EventQueue>()
    private var savedDropped = 0

    @Before
    fun setUp() {
        DroppedEventsCounter.init(ctx)
        savedDropped = DroppedEventsCounter.peek()
        db = EventDatabase(ctx, 10_000, "runtime_${UUID.randomUUID()}.db")
        db.clearAll()
        TestHttpServer.settleIoDispatcher()
    }

    @After
    fun tearDown() {
        queues.forEach { it.shutdown() }
        waitFor { EventUploadCoordinator.tryAcquire().also { if (it) EventUploadCoordinator.release() } }
        Thread.sleep(200)
        DroppedEventsCounter.getAndReset()
        DroppedEventsCounter.increment(savedDropped)
        BatchSizeCapGate.set(ctx, null)
        db.clearAll()
    }

    private fun event(i: Int) = JSONObject()
        .put("event_id", "runtime-$i-${UUID.randomUUID()}")
        .put("event_name", "t$i")
        .put("ts_ms", System.currentTimeMillis())

    /** No connectivity monitor: the adaptive size is 100. */
    private fun queue(server: TestHttpServer, cap: Int?) = EventQueue(
        apiClient = ApiClient("adn_test_runtime", server.baseUrl),
        eventDatabase = db,
        connectivityMonitor = null,
        batchSizeCap = cap,
        flushInterval = Long.MAX_VALUE,
        context = ctx,
    ).also { queues += it }

    private fun waitFor(seconds: Long = 10, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Test
    fun optionsAreUnsetUnlessTheHostSetsThem() {
        val o = AppDNAOptions()
        assertNull(o.flushInterval); assertNull(o.batchSize); assertNull(o.configTTL)
        assertEquals(RuntimeSettings.Resolved(30L, null, 3600L), RuntimeSettings.resolveAll(o, null))
        assertEquals(RuntimeSettings.Resolved(10L, 5, 60L),
            RuntimeSettings.resolveAll(AppDNAOptions(flushInterval = 10L, batchSize = 5, configTTL = 60L), null))
    }

    @Test
    fun bootstrapSettingsParseAndIgnoreWhatIsMissing() {
        assertNull(RuntimeSettings.Bootstrap.from(null))
        assertEquals(RuntimeSettings.Bootstrap(null, null, null), RuntimeSettings.Bootstrap.from(JSONObject()))
        assertEquals(RuntimeSettings.Bootstrap(45L, 7, 900L), RuntimeSettings.Bootstrap.from(
            JSONObject().put("flushInterval", 45).put("batchSize", 7).put("configTTL", 900).put("mapboxToken", "pk")))
        assertEquals(RuntimeSettings.Bootstrap(null, null, null), RuntimeSettings.Bootstrap.from(
            JSONObject().put("flushInterval", "soon").put("batchSize", JSONObject.NULL)))
    }

    /** The cap is the flush threshold: 2 events with a cap of 3 send nothing, the 3rd triggers an upload. */
    @Test
    fun theCapIsTheThreshold() {
        TestHttpServer { _, _ -> 200 to "{}" }.use { server ->
            val q = queue(server, cap = 3)
            assertEquals(3, q.effectiveBatchSizeForTesting)
            assertEquals(3, BatchSizeCapGate.cap(ctx))
            q.enqueue(event(1)); q.enqueue(event(2))
            Thread.sleep(400)
            assertEquals("uploaded below the cap", 0, server.requests.size)
            q.enqueue(event(3))
            assertTrue("no threshold upload at the cap", waitFor { server.requests.size >= 1 })
            assertTrue("the upload did not remove the events", waitFor { db.count() == 0 })
        }
    }

    /** The cap is the upload size: one flush of 5 queued events with a cap of 2 sends 2 and leaves 3. */
    @Test
    fun theCapIsTheUploadSize() {
        TestHttpServer { _, _ -> 200 to "{}" }.use { server ->
            val q = queue(server, cap = null)
            for (i in 1..5) q.enqueue(event(i))
            Thread.sleep(300)
            assertEquals(0, server.requests.size)
            q.applyRuntimeSettings(2, Long.MAX_VALUE)
            q.flush()
            assertTrue("no upload", waitFor { server.requests.size >= 1 })
            assertTrue("one upload did not send exactly the cap", waitFor { db.count() == 3 })
            Thread.sleep(300)
            assertEquals(1, server.requests.size)
            assertEquals(3, db.count())
        }
    }

    @Test
    fun noCapKeepsTheAdaptiveSize() {
        TestHttpServer { _, _ -> 200 to "{}" }.use { server ->
            val q = queue(server, cap = null)
            assertEquals(EventQueue.NO_MONITOR_ADAPTIVE_BATCH_SIZE, q.effectiveBatchSizeForTesting)
            assertNull(BatchSizeCapGate.cap(ctx))
            for (i in 1..5) q.enqueue(event(i))
            Thread.sleep(500)
            assertEquals("5 events crossed a threshold of 100", 0, server.requests.size)
        }
    }

    /** A cap of 0 holds every event: no threshold upload, and the host's flush sends nothing. */
    @Test
    fun aCapOfZeroHoldsEveryEvent() {
        TestHttpServer { _, _ -> 200 to "{}" }.use { server ->
            val q = queue(server, cap = 0)
            for (i in 1..3) q.enqueue(event(i))
            q.flush()
            Thread.sleep(500)
            assertEquals(0, server.requests.size)
            assertEquals(3, db.count())
        }
    }

    @Test
    fun applyingSettingsChangesTheCapAndTheInterval() {
        TestHttpServer { _, _ -> 200 to "{}" }.use { server ->
            val q = queue(server, cap = null)
            q.applyRuntimeSettings(9, 42L)
            assertEquals(9, q.batchSizeCapForTesting)
            assertEquals(42L, q.flushIntervalForTesting)
            assertEquals(9, BatchSizeCapGate.cap(ctx))
            q.applyRuntimeSettings(null, 42L)
            assertNull(q.batchSizeCapForTesting)
            assertNull(BatchSizeCapGate.cap(ctx))
        }
    }

    /** The interval applied to a running queue drives its timer: 1 s → the queued event goes out. */
    @Test
    fun aShorterIntervalAppliedToARunningQueueFlushesOnIt() {
        TestHttpServer { _, _ -> 200 to "{}" }.use { server ->
            val q = queue(server, cap = null)
            q.enqueue(event(1))
            Thread.sleep(300)
            assertEquals(0, server.requests.size)
            q.applyRuntimeSettings(null, 1L)
            assertTrue("the new interval did not drive the timer", waitFor(5) { server.requests.size >= 1 })
        }
    }

    @Test
    fun theWorkerSendsBatchesNoLargerThanTheCap() {
        TestHttpServer { _, _ -> 200 to "{}" }.use { server ->
            db.insertEvents((1..5).map { event(it).toString() })
            assertEquals(EventUploadWorker.Outcome.DONE,
                EventUploadWorker.uploadPending(db, "adn_test_runtime", server.baseUrl, batchSizeCap = 2))
            assertEquals("5 events in batches of at most 2", 3, server.requests.size)
            assertEquals(0, db.count())
        }
    }

    @Test
    fun theWorkerHoldsEventsWithACapOfZero() {
        TestHttpServer { _, _ -> 200 to "{}" }.use { server ->
            db.insertEvents((1..5).map { event(it).toString() })
            assertEquals(EventUploadWorker.Outcome.DONE,
                EventUploadWorker.uploadPending(db, "adn_test_runtime", server.baseUrl, batchSizeCap = 0))
            assertEquals(0, server.requests.size)
            assertEquals(5, db.count())
        }
    }

    @Test
    fun theWorkerBatchSizeFollowsTheGate() {
        assertEquals(100, EventUploadWorker.uploadBatchSize(null))
        assertEquals(7, EventUploadWorker.uploadBatchSize(7))
        assertEquals(0, EventUploadWorker.uploadBatchSize(0))
    }
}
