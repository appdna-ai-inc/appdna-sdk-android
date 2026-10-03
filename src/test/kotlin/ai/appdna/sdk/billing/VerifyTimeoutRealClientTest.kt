package ai.appdna.sdk.billing

import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.network.TestHttpServer
import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The 30 s verify bound really bounds the lock, with the REAL [ApiClient] against a
 * server that accepts the request and never answers.
 *
 * Before the fix `ApiClient.postForResult` ran a blocking OkHttp `execute()`: cancelling the coroutine
 * did not interrupt it, so `withTimeoutOrNull(30 s)` could only return once OkHttp gave up (the 30 s
 * read timeout, then retries, under a 60 s `callTimeout`) — the purchase lock stayed held and the
 * purchase stayed unresolved that long. The Main looper's clock is virtual (the 30 s timer fires on
 * `idleFor`); the network is real, so the NEGATIVE CONTROL (the old `execute()`) fails the "resolved
 * within 10 s of real time" and "the client closed the connection" assertions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VerifyTimeoutRealClientTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var sc: BillingScenario? = null
    private var pipe: BillingTestPipeline? = null
    private var server: TestHttpServer? = null

    @After fun tearDown() {
        sc?.teardown(); pipe?.close(); server?.close()
    }

    @Test fun `a stalled verify - the call is cancelled at 30 s, the lock is released and the purchase resolves Failed networkError`() {
        TestHttpServer.settleIoDispatcher()
        val srv = TestHttpServer { _, _ -> null }.also { server = it } // accept, read, never answer
        pipe = BillingTestPipeline(ctx, "verify_timeout_real")
        val s = BillingScenario(ctx, JSONObject("""{
          "product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"lifetime_unlock","token":"tok_st","order_id":"GPA.st","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "listener_purchase_token":"tok_st"
        }""")).also { sc = it }
        // The production verifier over the production HTTP client.
        val real = ReceiptVerifier(ApiClient("adn_test_stall", srv.baseUrl), "sdk", currentUserId = { null })
        NativeBillingManager::class.java.getDeclaredField("receiptVerifier").apply { isAccessible = true }.set(s.mgr, real)

        var result: PurchaseResult? = null
        s.mgr.scope.launch { result = s.mgr.purchase(Robolectric.buildActivity(Activity::class.java).setup().get(), "lifetime_unlock") }
        s.idle()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (srv.requests.isEmpty() && System.nanoTime() < deadline) { s.idle(1); Thread.sleep(10) }
        assertTrue("the verify reached the server — ${TestHttpServer.workerDiagnostics()}", srv.requests.isNotEmpty())
        assertTrue(srv.requests.first().first.endsWith("/api/v1/billing/verify"))
        assertTrue("the verify holds the purchase lock", s.mgr.completion.purchaseCompletionMutex.isLocked)
        assertEquals(null, result)

        val started = System.nanoTime()
        shadowOf(Looper.getMainLooper()).idleFor(31, TimeUnit.SECONDS) // the 30 s bound fires
        while (result == null && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10)) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        val failed = result as? PurchaseResult.Failed
            ?: throw AssertionError("the purchase did not resolve within 10 s of real time after the 30 s bound (got $result after ${elapsedMs} ms)")
        assertEquals("networkError", failed.errorType)
        assertTrue("resolved promptly, not after OkHttp's own timeouts ($elapsedMs ms)", elapsedMs < 10_000)
        assertFalse("the lock is released", s.mgr.completion.purchaseCompletionMutex.isLocked)
        assertTrue("the HTTP call itself was cancelled (the client closed the connection)", srv.connectionClosedByClient.await(5, TimeUnit.SECONDS))
        assertEquals(1, pipe!!.count("purchase_failed"))
        assertEquals("networkError", pipe!!.props("purchase_failed").getString("error_type"))
        assertEquals(1, srv.requests.size) // no retry after the cancellation
        assertEquals(emptyList<String>(), s.fake.acknowledgeCalls)

        // The lock is free for the next entry: the rescue runs (and stalls again only in its own verify).
        s.mgr.scope.launch { s.mgr.completion.run(PurchaseCompletion.Entry.RESTORE) }
        val waitFrom = System.nanoTime()
        while (srv.requests.size < 2 && System.nanoTime() - waitFrom < TimeUnit.SECONDS.toNanos(5)) {
            s.idle(1)
            Thread.sleep(10)
        }
        assertEquals("the next run got the lock and reached its own verify", 2, srv.requests.size)
    }
}
