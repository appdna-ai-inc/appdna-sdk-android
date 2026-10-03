package ai.appdna.sdk

import ai.appdna.sdk.events.EventQueue
import ai.appdna.sdk.events.EventUploadCoordinator
import ai.appdna.sdk.events.UploadPauseGate
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.storage.EventDatabase
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The persisted upload pause, written by the production path — not by a test seam. Five real failed upload
 * cycles (a closed port) pause the queue, and the pause reaches [UploadPauseGate], which the WorkManager
 * uploader reads. And a queue that `shutdown()` ended cannot set the gate after the next `configure()`'s queue
 * cleared it: the gate belongs to the newest queue. iOS `UploadPauseGatePersistenceTests`, same contract.
 *
 * NEGATIVE CONTROLS (build Mac, patched sources):
 *  - the `UploadPauseGate.set(context, true, …)` in `bumpFailureCounter` removed → the first test fails;
 *  - the owner check in `UploadPauseGate.set` removed → the replaced-queue test fails (the ended queue's
 *    fifth failure sets the new session's gate).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadPauseGatePersistenceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val queues = mutableListOf<EventQueue>()

    @Before fun setUp() {
        UploadPauseGate.setForTest(ctx, false)
        EventUploadCoordinator.clearResolvedForTest()
    }

    @After fun tearDown() {
        for (q in queues) { q.shutdown(); q.awaitShutdownFlush(10_000) }
        UploadPauseGate.setForTest(ctx, false)
    }

    /** A queue whose every upload fails: nothing listens on port 9. */
    private fun failingQueue(): EventQueue {
        val db = EventDatabase(ctx, 10_000, "pause_persist_${System.nanoTime()}.db")
        val q = EventQueue(ApiClient("adn_test_pause_persist", "http://127.0.0.1:9"), db, null, 100, Long.MAX_VALUE, ctx, null)
        q.enqueue(JSONObject().put("event_id", "pp-${System.nanoTime()}").put("event_name", "e").put("ts_ms", System.currentTimeMillis()))
        queues += q
        return q
    }

    private fun failOneCycle(q: EventQueue) {
        val before = q.consecutiveFailuresForTest
        runBlocking { q.flushCycleForTest() }
        assertEquals("the upload cycle did not fail", before + 1, q.consecutiveFailuresForTest)
    }

    @Test fun fiveFailedCyclesPersistThePause() {
        val q = failingQueue()
        repeat(4) { i ->
            failOneCycle(q)
            assertFalse("paused after ${i + 1} failed cycle(s)", UploadPauseGate.isPaused(ctx))
        }
        failOneCycle(q)
        assertTrue("five failed cycles did not persist the pause", UploadPauseGate.isPaused(ctx))
    }

    @Test fun aQueueThatWasReplacedCannotSetTheNewSessionsPause() {
        val ended = failingQueue()
        repeat(4) { failOneCycle(ended) }

        // The next configure()'s queue: it clears the gate and owns it.
        val current = failingQueue()
        assertFalse(UploadPauseGate.isPaused(ctx))

        // The ended queue's last upload fails a fifth time: its own pause, not the new session's.
        failOneCycle(ended)
        assertFalse("a replaced queue set the new session's upload pause", UploadPauseGate.isPaused(ctx))

        // The current queue still can.
        repeat(5) { failOneCycle(current) }
        assertTrue(UploadPauseGate.isPaused(ctx))
    }
}
