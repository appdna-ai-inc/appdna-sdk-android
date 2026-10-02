package ai.appdna.sdk.events

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
 * The event queue's failure pause, which flush paths clear it, the final upload attempt on
 * `shutdown()` and the HTTP cache — each against the REAL [EventQueue] / [ApiClient] and a loopback
 * server.
 *
 * NEGATIVE CONTROLS (run on the build Mac against patched sources):
 *  - the threshold path calling the public `flush()` again (which clears the pause) → the first two
 *    tests fail: the 6th `enqueue` uploads while paused;
 *  - `EventQueue.shutdown()` without the final attempt → the shutdown test fails (no request);
 *  - an OkHttp `Cache` on the SDK client → the bootstrap test fails (one request, a cached 200 offline).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventQueuePauseAndShutdownTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: EventDatabase
    private val queues = mutableListOf<EventQueue>()

    @Before
    fun setUp() {
        DroppedEventsCounter.init(ctx)
        db = EventDatabase(ctx, 10_000, "pause_gate_${UUID.randomUUID()}.db")
        db.clearAll()
        TestHttpServer.settleIoDispatcher()
    }

    @After
    fun tearDown() {
        queues.forEach { it.shutdown() }
        db.clearAll()
    }

    private fun event(i: Int) = JSONObject()
        .put("event_id", "pause-$i-${UUID.randomUUID()}")
        .put("event_name", "t$i")
        .put("ts_ms", System.currentTimeMillis())

    private fun queue(server: TestHttpServer, batchSize: Int) = EventQueue(
        apiClient = ApiClient("adn_test_pause_gate", server.baseUrl),
        eventDatabase = db,
        connectivityMonitor = null,
        batchSize = batchSize,
        flushInterval = Long.MAX_VALUE,
    ).also { queues += it }

    private fun waitFor(seconds: Long = 10, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    /** Five threshold flushes, each one 401 (a dropped batch counts toward the pause): paused. */
    private fun pauseAfterFiveFailures(q: EventQueue, server: TestHttpServer) {
        for (i in 1..5) {
            q.enqueue(event(i))
            assertTrue("upload $i never arrived: ${server.requests.size}", waitFor { server.requests.size == i })
        }
    }

    /** The 6th `track()` reaches the batch size while paused: no upload. */
    private fun assertAThresholdFlushKeepsThePause(q: EventQueue, server: TestHttpServer) {
        q.enqueue(event(6))
        Thread.sleep(1_500)
        assertEquals("a threshold flush cleared the pause and uploaded again", 5, server.requests.size)
    }

    @Test
    fun aThresholdFlushKeepsThePauseAndTheHostsFlushClearsIt() {
        TestHttpServer { _, _ -> 401 to "{}" }.use { server ->
            val q = queue(server, batchSize = 1)
            pauseAfterFiveFailures(q, server)
            assertAThresholdFlushKeepsThePause(q, server)

            q.flush()   // `AppDNA.flush()`
            assertTrue("the host's flush did not clear the pause", waitFor { server.requests.size == 6 })
        }
    }

    @Test
    fun backgroundKeepsThePauseAndForegroundClearsIt() {
        TestHttpServer { _, _ -> 401 to "{}" }.use { server ->
            val q = queue(server, batchSize = 1)
            pauseAfterFiveFailures(q, server)
            assertAThresholdFlushKeepsThePause(q, server)

            q.onAppBackground()
            Thread.sleep(1_000)
            assertEquals("backgrounding cleared the pause", 5, server.requests.size)

            q.onAppForeground()
            assertTrue("coming to the foreground did not clear the pause", waitFor { server.requests.size == 6 })
        }
    }

    /** The trigger table the shared `flush_pause_gate` fixture asserts on both platforms. */
    @Test
    fun onlyForegroundAndTheHostsFlushClearThePause() {
        val clearing = EventQueue.FlushTrigger.values().filter { it.clearsPauseGate }.toSet()
        assertEquals(setOf(EventQueue.FlushTrigger.FOREGROUND, EventQueue.FlushTrigger.EXPLICIT), clearing)
    }

    @Test
    fun shutdownMakesOneFinalUploadAttemptOfTheQueuedEvents() {
        TestHttpServer { _, _ -> 200 to "{}" }.use { server ->
            val q = queue(server, batchSize = 100)
            q.enqueue(event(1))           // below the batch size: nothing is sent yet
            Thread.sleep(300)
            assertEquals(0, server.requests.size)

            q.shutdown()
            assertTrue("shutdown() sent nothing", server.awaitRequest(10))
            assertEquals("POST /api/v1/ingest/events", server.requests.first().first)
            assertTrue("the uploaded event stayed on disk", waitFor { db.loadAll().isEmpty() })
        }
    }

    /**
     * The bootstrap is never answered from an HTTP cache: the server sends
     * `Cache-Control: private, max-age=86400`, and the second GET still reaches it; with the server
     * gone, the GET fails instead of returning a cached 200. (OkHttp caches nothing unless the client
     * is built with a `Cache` — this holds the SDK's client to that.)
     */
    @Test
    fun theBootstrapIsNeverAnsweredFromAnHttpCache() {
        val server = TestHttpServer(mapOf("Cache-Control" to "private, max-age=86400")) { _, _ -> 200 to "{\"appId\":\"a\"}" }
        val client = ApiClient("adn_test_http_cache", server.baseUrl)
        try {
            val first = kotlinx.coroutines.runBlocking { client.get("/api/v1/sdk/bootstrap") }
            val second = kotlinx.coroutines.runBlocking { client.get("/api/v1/sdk/bootstrap") }
            assertEquals("a", first?.optString("appId"))
            assertEquals("a", second?.optString("appId"))
            assertEquals("the second bootstrap was answered from a cache", 2, server.requests.size)
        } finally {
            server.close()
        }
        val offline = kotlinx.coroutines.runBlocking { client.get("/api/v1/sdk/bootstrap") }
        assertNull("an unreachable server answered with a cached bootstrap", offline)
    }
}
