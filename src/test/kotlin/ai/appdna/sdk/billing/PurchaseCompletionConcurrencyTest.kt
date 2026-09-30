package ai.appdna.sdk.billing

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.TransactionInfo
import ai.appdna.sdk.paywalls.AppDNAPaywallDelegate
import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC-497 I4 M2 — the concurrency cases with REAL contention.
 *
 * The earlier tests ran their "concurrent" entries one after the other: every fake answered
 * synchronously, so an uncontended `withLock` never suspended and the first entry finished before the
 * second began (the drain test passed with the `inFlight` set deleted). Here one entry is PARKED on a
 * [CompletableDeferred] — inside the verify (holding `purchaseCompletionMutex`), inside Play's
 * `queryPurchasesAsync` callback (holding it too), or at the drain's delegate call (holding the
 * entry in-flight) — the second entry is started and observed while the first is parked, and only
 * then is the first released.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PurchaseCompletionConcurrencyTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var sc: BillingScenario? = null
    private var pipe: BillingTestPipeline? = null
    private val paywallCalls = mutableListOf<String>()

    @After fun tearDown() {
        sc?.teardown(); pipe?.close(); AppDNA.paywall.setDelegate(null)
    }

    private fun scenario(json: String): BillingScenario {
        pipe = BillingTestPipeline(ctx, "concurrency")
        AppDNA.paywall.setDelegate(object : AppDNAPaywallDelegate {
            override fun onPaywallPurchaseCompleted(paywallId: String, productId: String, transaction: TransactionInfo) {
                paywallCalls += "$paywallId/$productId"
            }
        })
        return BillingScenario(ctx, JSONObject(json)).also { sc = it }
    }

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private fun delivered(s: BillingScenario) = s.delegateLog.count { it.first == "onPurchaseCompleted" }
    private fun events(name: String) = pipe!!.count(name)

    private val inFlightPurchase = """{
      "current_user_id":"user_a","first_identified_user_id":"user_a",
      "product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}},
      "owned_purchases":[{"product_id":"lifetime_unlock","token":"tok_x","order_id":"GPA.x","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
      "listener_purchase_token":"tok_x",
      "verify_replies":{"tok_x":{"status":200,"consume":false,"expires_at":null,"is_trial":false}}
    }"""

    // ---------------------------------------------------------------------------------------------
    // drain vs drain (Q3)
    // ---------------------------------------------------------------------------------------------

    /**
     * Drain 1 marks the entry in-flight and parks at its delegate call; drain 2 runs to completion
     * meanwhile. NEGATIVE CONTROL: with the `token in inFlight` check removed from
     * `drainDeliveries`, drain 2 also reaches the delegate call (parks too, `second` stays false)
     * and the entry is delivered twice — this test fails.
     */
    @Test fun `two concurrent drains - the second skips the in-flight entry, one delivery`() {
        val s = scenario("""{"pending_deliveries":[{"token":"t1","ownerToken":null,"productId":"p1"}]}""")
        val gate = CompletableDeferred<Unit>()
        val parkedAt = mutableListOf<String>()
        s.mgr.completion.delegateCallGate = { token -> parkedAt += token; gate.await() }

        s.setDelegate() // trigger (ii): drain 1
        s.idle()
        assertEquals("drain 1 is parked at the delegate call", listOf("t1"), parkedAt)
        assertFalse(s.mgr.completion.purchaseCompletionMutex.isLocked) // in-flight, not locked

        var secondFinished = false
        s.mgr.scope.launch { s.mgr.completion.drainDeliveries(); secondFinished = true }
        s.idle()
        assertTrue("drain 2 ran to completion while drain 1 was mid-delivery", secondFinished)
        assertEquals("drain 2 never reached the delegate call", listOf("t1"), parkedAt)
        assertEquals(0, delivered(s))
        assertEquals(listOf("t1"), s.queueTokens())

        gate.complete(Unit)
        s.idle()
        assertEquals(1, delivered(s))
        assertEquals(emptyList<String>(), s.queueTokens())

        // A later trigger finds nothing to deliver.
        s.mgr.triggerDrain(); s.idle()
        assertEquals(1, delivered(s))
    }

    // ---------------------------------------------------------------------------------------------
    // run vs run
    // ---------------------------------------------------------------------------------------------

    @Test fun `run vs run - the second waits for the lock and sees the token reported, one purchase_completed`() {
        val s = scenario("""{
          "product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"lifetime_unlock","token":"tok_r","order_id":"GPA.r","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "verify_replies":{"tok_r":{"status":200,"consume":false,"expires_at":null,"is_trial":false}}
        }""")
        val gate = CompletableDeferred<Unit>()
        val parked = mutableListOf<String>()
        s.verifyGate = { parked += it; gate.await() }

        var firstDone = false
        var secondDone = false
        s.mgr.scope.launch { s.mgr.completion.run(PurchaseCompletion.Entry.ON_START); firstDone = true }
        s.mgr.scope.launch { s.mgr.completion.run(PurchaseCompletion.Entry.RESTORE); secondDone = true }
        s.idle()
        assertEquals("only run 1 reached the verify", listOf("tok_r"), parked)
        assertTrue("run 1 holds the lock while parked", s.mgr.completion.purchaseCompletionMutex.isLocked)
        assertFalse(firstDone || secondDone)
        assertEquals(0, events("purchase_completed"))

        gate.complete(Unit)
        s.idle()
        assertTrue(firstDone && secondDone)
        assertEquals("run 2 never verified: the token was reported when it got the lock", listOf("tok_r"), parked)
        assertEquals(1, events("purchase_completed"))
        assertEquals(listOf("tok_r"), s.queueTokens())
        assertFalse(s.mgr.completion.purchaseCompletionMutex.isLocked)
    }

    // ---------------------------------------------------------------------------------------------
    // ON_START vs the purchase listener
    // ---------------------------------------------------------------------------------------------

    @Test fun `ON_START vs listener - the purchase path parks in verify, ON_START waits, one report with paywall_id`() {
        val s = scenario(inFlightPurchase)
        s.setDelegate()
        s.fake.autoDeliver = false
        s.mgr.currentPaywallId = "pw_1"
        var result: PurchaseResult? = null
        s.mgr.scope.launch { result = s.mgr.purchase(activity(), "lifetime_unlock") }
        s.idle()

        val gate = CompletableDeferred<Unit>()
        val parked = mutableListOf<String>()
        s.verifyGate = { parked += it; gate.await() }
        s.fake.deliver(s.fake.listenerPurchase!!, "inapp")
        s.idle()
        assertEquals(listOf("tok_x"), parked)

        var onStartDone = false
        s.mgr.scope.launch { s.mgr.reconcileSubscriptionState(); onStartDone = true }
        s.idle()
        assertFalse("ON_START waits for the lock", onStartDone)
        assertNull(result)

        gate.complete(Unit)
        s.idle(200)
        assertTrue(onStartDone)
        assertTrue(result is PurchaseResult.Purchased)
        assertEquals(listOf("tok_x"), parked)
        assertEquals(1, events("purchase_completed"))
        assertEquals("pw_1", pipe!!.props("purchase_completed").getString("paywall_id"))
        assertEquals(1, delivered(s))
        assertEquals(listOf("pw_1/lifetime_unlock"), paywallCalls)
        assertEquals(emptyList<String>(), s.queueTokens())
    }

    @Test fun `ON_START first - parked in Play's query callback, the listener waits, ON_START skips the in-flight product`() {
        val s = scenario(inFlightPurchase)
        s.setDelegate()
        s.fake.autoDeliver = false
        s.mgr.currentPaywallId = "pw_1"
        var result: PurchaseResult? = null
        s.mgr.scope.launch { result = s.mgr.purchase(activity(), "lifetime_unlock") }
        s.idle()

        val gate = CompletableDeferred<Unit>()
        s.fake.queryGate = gate
        var onStartDone = false
        s.mgr.scope.launch { s.mgr.reconcileSubscriptionState(); onStartDone = true }
        s.idle()
        assertEquals("ON_START is parked in queryPurchasesAsync", 1, s.fake.queriesParked)
        assertTrue(s.mgr.completion.purchaseCompletionMutex.isLocked)

        // Play delivers the purchase while ON_START holds the lock: the purchase path must wait.
        s.fake.deliver(s.fake.listenerPurchase!!, "inapp")
        s.idle()
        assertNull(result)
        assertEquals(emptyList<String>(), s.verifyCalls)

        gate.complete(Unit)
        s.idle(200)
        assertTrue(onStartDone)
        assertTrue(result is PurchaseResult.Purchased)
        assertEquals("ON_START skipped the in-flight token; only the purchase path verified it", listOf("tok_x"), s.verifyCalls)
        assertEquals(1, events("purchase_completed"))
        assertEquals("pw_1", pipe!!.props("purchase_completed").getString("paywall_id"))
        assertEquals(1, delivered(s))
    }

    // ---------------------------------------------------------------------------------------------
    // identify vs the purchase listener
    // ---------------------------------------------------------------------------------------------

    @Test fun `identify vs listener - the identify refresh parks in the query, skips the in-flight token and never restores it`() {
        val s = scenario(inFlightPurchase)
        s.setDelegate()
        s.fake.autoDeliver = false
        s.mgr.currentPaywallId = "pw_2"
        var result: PurchaseResult? = null
        s.mgr.scope.launch { result = s.mgr.purchase(activity(), "lifetime_unlock") }
        s.idle()

        val gate = CompletableDeferred<Unit>()
        s.fake.queryGate = gate
        var identifyDone = false
        // The production billing half of AppDNA.identify (refresh + drain trigger iii).
        s.mgr.scope.launch { AppDNA.billingAfterIdentify("user_a", null); identifyDone = true }
        s.idle()
        assertTrue(s.fake.queriesParked >= 1)
        assertTrue(s.mgr.completion.purchaseCompletionMutex.isLocked)

        s.fake.deliver(s.fake.listenerPurchase!!, "inapp")
        s.idle()
        assertNull(result)

        gate.complete(Unit)
        s.idle(200)
        assertTrue(identifyDone)
        assertTrue(result is PurchaseResult.Purchased)
        assertTrue("the refresh's /restore never carried the in-flight token", s.restoreCalls.none { "tok_x" in it })
        assertEquals(listOf("tok_x"), s.verifyCalls)
        assertEquals(1, events("purchase_completed"))
        assertEquals("pw_2", pipe!!.props("purchase_completed").getString("paywall_id"))
        assertEquals(1, delivered(s))
    }

    @Test fun `identify vs listener - the purchase path parks in verify, the identify refresh waits and reports nothing again`() {
        val s = scenario(inFlightPurchase)
        s.setDelegate()
        s.fake.autoDeliver = false
        var result: PurchaseResult? = null
        s.mgr.scope.launch { result = s.mgr.purchase(activity(), "lifetime_unlock") }
        s.idle()

        val gate = CompletableDeferred<Unit>()
        s.verifyGate = { gate.await() }
        s.fake.deliver(s.fake.listenerPurchase!!, "inapp")
        s.idle()
        var identifyDone = false
        s.mgr.scope.launch { AppDNA.billingAfterIdentify("user_a", null); identifyDone = true }
        s.idle()
        assertFalse(identifyDone)

        gate.complete(Unit)
        s.idle(200)
        assertTrue(identifyDone)
        assertTrue(result is PurchaseResult.Purchased)
        assertEquals(1, events("purchase_completed"))
        assertEquals(1, delivered(s))
        // The refresh ran after the report: its /restore includes the just-reported token (idempotent).
        assertTrue(s.restoreCalls.any { "tok_x" in it })
        assertEquals(0, events("purchase_restored"))
    }
}
