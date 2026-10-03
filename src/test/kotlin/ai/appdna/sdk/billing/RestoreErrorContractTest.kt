package ai.appdna.sdk.billing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.launch
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Restore error contract — Android `storeKit2`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreErrorContractTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var sc: BillingScenario? = null
    private var pipe: BillingTestPipeline? = null

    @After fun tearDown() { sc?.teardown(); pipe?.close() }

    private fun scenario(json: String): BillingScenario {
        pipe = BillingTestPipeline(ctx, "restore_contract")
        return BillingScenario(ctx, JSONObject(json)).also { sc = it }
    }

    private val ownedLifetime = """
      "cached_entitlements":[{"product_id":"lifetime","status":"active","store":"google_play","expires_at":null,"product_type":"inapp"}],
      "owned_purchases":[{"product_id":"lifetime","token":"tok_l","order_id":"GPA.l","acknowledged":true,"product_type":"inapp","purchase_state":"PURCHASED"}],
      "reported_tokens":[{"token":"tok_l","pending_finalize":null}]"""

    @Test fun `Play OK plus an offline restore - NetworkError, cache unchanged, no onRestoreCompleted`() {
        val s = scenario("{$ownedLifetime}")
        val field = NativeBillingManager::class.java.getDeclaredField("receiptVerifier").apply { isAccessible = true }
        field.set(s.mgr, ReceiptVerifier(null, "sdk", post = { _, _ -> ai.appdna.sdk.network.ApiResult(null, null, null) }, currentUserId = { null }))
        s.setDelegate()
        val r = s.drive { s.mgr.restorePurchases() }
        assertTrue(r.exceptionOrNull() is BillingError.NetworkError)
        assertEquals(listOf("lifetime"), s.entitlementIds())
        assertEquals(0, s.delegateLog.count { it.first == "onRestoreCompleted" })
        assertEquals(1, pipe!!.count("purchase_restore_failed"))
        assertEquals("networkError", pipe!!.props("purchase_restore_failed").getString("error_type"))
    }

    /**
     * Impl: `/restore` is bounded by 30 s like every other wait in the locked run. A
     * reply that never comes ends in NetworkError, the cache unchanged, the lock released.
     */
    @Test fun `a restore that never answers - NetworkError at 30 s, cache unchanged, lock released`() {
        val s = scenario("{$ownedLifetime}")
        val field = NativeBillingManager::class.java.getDeclaredField("receiptVerifier").apply { isAccessible = true }
        val real = s.verifier
        val never = kotlinx.coroutines.CompletableDeferred<ai.appdna.sdk.network.ApiResult>()
        var restoreCalls = 0
        field.set(s.mgr, ReceiptVerifier(null, "sdk", post = { _, _ -> restoreCalls++; never.await() }, currentUserId = { null }))
        try {
            s.setDelegate()
            var outcome: Result<List<Entitlement>>? = null
            kotlinx.coroutines.MainScope().launch { outcome = runCatching { s.mgr.restorePurchases() } }
            s.idle()
            assertEquals("parked in /restore", 1, restoreCalls)
            assertEquals(null, outcome)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(31, java.util.concurrent.TimeUnit.SECONDS)
            s.idle()
            assertTrue("got ${outcome?.exceptionOrNull()}", outcome?.exceptionOrNull() is BillingError.NetworkError)
            assertEquals(listOf("lifetime"), s.entitlementIds())
            assertEquals(0, s.delegateLog.count { it.first == "onRestoreCompleted" })
            assertTrue(!s.mgr.completion.purchaseCompletionMutex.isLocked)
        } finally {
            field.set(s.mgr, real)
        }
    }

    @Test fun `a failed Play query - ServerError, cache unchanged`() {
        val s = scenario("""{$ownedLifetime, "query_response_codes":{"subs":"ERROR"}}""")
        val r = s.drive { s.mgr.restorePurchases() }
        assertTrue(r.exceptionOrNull() is BillingError.ServerError)
        assertEquals(listOf("lifetime"), s.entitlementIds())
    }

    @Test fun `Play NETWORK_ERROR on a query - NetworkError`() {
        val s = scenario("""{$ownedLifetime, "query_response_codes":{"inapp":"NETWORK_ERROR"}}""")
        val r = s.drive { s.mgr.restorePurchases() }
        assertTrue(r.exceptionOrNull() is BillingError.NetworkError)
    }

    @Test fun `a 5xx restore reply - ServerError and no merge`() {
        val s = scenario("""{$ownedLifetime, "restore_status":503}""")
        val r = s.drive { s.mgr.restorePurchases() }
        assertTrue(r.exceptionOrNull() is BillingError.ServerError)
        assertEquals(listOf("lifetime"), s.entitlementIds())
    }

    @Test fun `a restore that started under another identity drops its reply`() {
        val s = scenario("""{$ownedLifetime, "current_user_id":"user_a","first_identified_user_id":"user_a",
           "restore_reply":[{"token":"tok_l","product_id":"other","product_type":"inapp","expires_at":null,"is_trial":false,"consume":false}]}""")
        val field = NativeBillingManager::class.java.getDeclaredField("receiptVerifier").apply { isAccessible = true }
        val real = s.verifier
        field.set(s.mgr, ReceiptVerifier(null, "sdk", post = { p, b ->
            s.currentUser = "user_b" // identify(B) lands while /restore is in flight
            ai.appdna.sdk.network.ApiResult(200, """{"data":[{"product_id":"other","product_type":"inapp","store":"google_play","status":"active","consume":false}]}""", null)
        }, currentUserId = { s.currentUser }))
        val r = s.drive { s.mgr.restorePurchases() }
        assertTrue(r.exceptionOrNull() is BillingError.ServerError)
        assertTrue("other" !in s.entitlementIds())
        field.set(s.mgr, real)
    }

    @Test fun `Play billing unavailable - restore throws ServerError within the 30 s bound`() {
        val s = scenario("""{"billing_setup_code":"BILLING_UNAVAILABLE"}""")
        var outcome: Result<List<Entitlement>>? = null
        kotlinx.coroutines.MainScope().launch { outcome = runCatching { s.mgr.restorePurchases() } }
        s.idle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(31, java.util.concurrent.TimeUnit.SECONDS)
        s.idle()
        assertTrue(outcome?.exceptionOrNull() is BillingError.ServerError)
    }

    @Test fun `an empty OK owned list re-runs the refresh at the next ON_START`() {
        val s = scenario("""{"cached_entitlements":[{"product_id":"lifetime","status":"active","store":"google_play","expires_at":null,"product_type":"inapp"}],
           "restore_reply":[{"token":"tok_back","product_id":"lifetime","product_type":"inapp","expires_at":null,"is_trial":false,"consume":false}]}""")
        s.drive { s.mgr.refreshEntitlementCache() }
        assertEquals(emptyList<String>(), s.entitlementIds())           // Play answered an empty OK list
        assertTrue(s.mgr.completion.refreshRerunPending)
        s.fake.addOwned("inapp", FakeBillingClient.purchase("lifetime", "tok_back", acknowledged = true))
        s.ledger.write(reported = listOf(ReportedToken("tok_back", null)))
        s.drive { s.mgr.reconcileSubscriptionState() }
        assertEquals(listOf("lifetime"), s.entitlementIds())
        assertTrue(!s.mgr.completion.refreshRerunPending)
    }
}
