package ai.appdna.sdk.network

import ai.appdna.sdk.background.EventUploadWorker
import ai.appdna.sdk.events.EventUploadCoordinator
import ai.appdna.sdk.events.UploadPauseGate
import ai.appdna.sdk.storage.EventDatabase
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * An event upload is ONE request on the wire; whoever called it owns the retry. OkHttp's default
 * `retryOnConnectionFailure(true)` re-sends a request on its own when a pooled (warm) connection fails
 * after the request was written — so the stub here answers the first upload (which leaves the connection
 * in the pool), reads the second and closes the socket without answering, and answers anything after it.
 * One attempt shows as two recorded requests and a TransientFailure; a silent re-send shows as three and
 * a success the caller never asked for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SingleAttemptUploadTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        TestHttpServer.settleIoDispatcher()
        EventUploadCoordinator.clearResolvedForTest()
    }

    /** First upload answered, second read and dropped, every later one answered. */
    private fun dropSecondUpload(): TestHttpServer {
        val uploads = AtomicInteger(0)
        return TestHttpServer { path, _ ->
            if (path.contains("/ingest/events") && uploads.incrementAndGet() == 2) TestHttpServer.CLOSE to "" else 200 to "{}"
        }
    }

    private fun uploads(server: TestHttpServer) = server.requests.count { it.first.contains("/ingest/events") }

    @Test fun postEventsBatchMakesOneAttemptOnAWarmConnectionThatFails() {
        dropSecondUpload().use { server ->
            val api = ApiClient("adn_test_single", server.baseUrl)
            val first = runBlocking { api.postEventsBatch("/api/v1/ingest/events", """{"batch":[]}""") }
            assertEquals("the warm-up upload", EventUploadResult.Success, first)
            val second = runBlocking { api.postEventsBatch("/api/v1/ingest/events", """{"batch":[]}""") }
            assertEquals("the dropped upload is a transient failure", EventUploadResult.TransientFailure(null), second)
            assertEquals("requests on the wire for two calls", 2, uploads(server))
        }
    }

    private fun event(i: Int) = JSONObject()
        .put("schema_version", 1).put("event_id", "single-$i-${System.nanoTime()}").put("event_name", "single_$i")
        .put("ts_ms", System.currentTimeMillis()).put("properties", JSONObject().put("n", i))
        .put("user", JSONObject().put("anon_id", "single-anon"))
        .put("context", JSONObject().put("session_id", "single-session"))

    /** The background worker's own client: two batches in one run, the second dropped on the warm connection. */
    @Test fun theWorkerMakesOneAttemptPerBatchOnAWarmConnectionThatFails() {
        dropSecondUpload().use { server ->
            val db = EventDatabase(ctx, 10_000, "single_worker_${System.nanoTime()}.db")
            try {
                UploadPauseGate.setForTest(ctx, false)
                db.insertEvents((0 until 150).map { event(it).toString() })
                val outcome = EventUploadWorker.uploadPending(db, "adn_test_single", server.baseUrl, context = ctx)
                assertEquals("the dropped batch is retried by the run's outcome", EventUploadWorker.Outcome.RETRY, outcome)
                assertEquals("requests on the wire for two batches", 2, uploads(server))
                assertEquals("the dropped batch stays queued", 50, db.count())
            } finally {
                db.close()
            }
        }
    }
}
