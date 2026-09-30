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

    /**
     * SPEC-497 I4 minors 1+2 — the cross-user grant race. Drain 1 marks user A's tagged entry in-flight
     * and parks before its delegate call; `identify(B)` lands meanwhile (its own drain skips the in-flight
     * entry). When drain 1 resumes, the posted block re-checks the identity ON MAIN: A's entry is not
     * deliverable to B, so it is NOT delivered and stays queued (unmarked). Back as A, it is delivered
     * once. NEGATIVE CONTROL: with the Main-side `deliverable(entry)` re-check removed, drain 1 hands A's
     * purchase to B's delegate — `delivered` is 1 while B is signed in and the queue is empty.
     */
    @Test fun `identify(B) while a drain is parked - A's entry stays queued and is not delivered to B`() {
        val ownerA = AppAccountTokenResolver.token("user_a")!!.toString().lowercase()
        val s = scenario("""{"current_user_id":"user_a","first_identified_user_id":"user_a",
            "pending_deliveries":[{"token":"t1","ownerToken":"$ownerA","productId":"p1"}]}""")
        val gate = CompletableDeferred<Unit>()
        val parkedAt = mutableListOf<String>()
        s.mgr.completion.delegateCallGate = { token -> parkedAt += token; gate.await() }

        s.setDelegate() // trigger (ii): drain 1 parks at the gate with t1 in-flight
        s.idle()
        assertEquals(listOf("t1"), parkedAt)

        // identify(B) — the identity changes, then the production billing half of identify runs.
        s.currentUser = "user_b"
        var identifyDone = false
        s.mgr.scope.launch { AppDNA.billingAfterIdentify("user_b", "user_a"); identifyDone = true }
        s.idle(200)
        assertTrue(identifyDone)
        assertEquals("the identify drain skipped the in-flight entry", listOf("t1"), parkedAt)

        gate.complete(Unit)
        s.idle(200)
        assertEquals("A's purchase was never handed to B", 0, delivered(s))
        assertEquals("A's entry stays queued", listOf("t1"), s.queueTokens())
        assertFalse(s.mgr.completion.purchaseCompletionMutex.isLocked)

        // Still B: another drain delivers nothing.
        s.mgr.triggerDrain(); s.idle()
        assertEquals(0, delivered(s))

        // A signs back in: the entry was unmarked, so it is delivered exactly once.
        s.currentUser = "user_a"
        s.mgr.triggerDrain(); s.idle()
        assertEquals(1, delivered(s))
        assertEquals(emptyList<String>(), s.queueTokens())
    }

    /**
     * SPEC-497 I4 minors 1+2 — the delegate is re-read on Main too. The host clears the delegate AFTER
     * the drain's early check but BEFORE its posted block runs (the clear is posted to Main first, so it
     * runs first): the block finds no delivering delegate, delivers nothing, and the entry stays queued
     * (unmarked) for the next delivering delegate. NEGATIVE CONTROL: with the delegate captured before
     * the post (the old code), the cleared delegate is still called — `delivered` is 1 and the queue empty.
     */
    @Test fun `delegate cleared between the early check and the Main call - not delivered, stays queued`() {
        val s = scenario("""{"pending_deliveries":[{"token":"t1","ownerToken":null,"productId":"p1"}]}""")
        val gate = CompletableDeferred<Unit>()
        var parks = 0
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        s.mgr.completion.delegateCallGate = { _ ->
            if (parks++ == 0) {
                gate.await()
                main.post { AppDNA.billing.setDelegate(null) }
            }
        }
        s.setDelegate(); s.idle()
        assertEquals(1, parks)

        gate.complete(Unit)
        s.idle()
        assertNull("the clear ran", AppDNA.billing.deliveringDelegate())
        assertEquals(0, delivered(s))
        assertEquals(listOf("t1"), s.queueTokens())

        s.setDelegate(); s.idle() // trigger (ii) again
        assertEquals(1, delivered(s))
        assertEquals(emptyList<String>(), s.queueTokens())
    }

    // ---------------------------------------------------------------------------------------------
    // a cancelled drain (impl audit round 2, A5)
    // ---------------------------------------------------------------------------------------------

    private fun cancelManagerChildren(s: BillingScenario) {
        s.mgr.scope.coroutineContext[kotlinx.coroutines.Job]!!.children.forEach { it.cancel() }
    }

    /**
     * The drain is cancelled after it posted the delegate call to Main but before that call started.
     * The call is abandoned (compare-and-set), the entry stays queued AND leaves the in-flight set, so
     * the next trigger delivers it exactly once. NEGATIVE CONTROL: the old code left `t1` in-flight
     * forever (the next drain skipped it) while the posted call still ran — delivered, but also still
     * queued — so this test fails on both counts.
     */
    @Test fun `a drain cancelled before the delegate call starts - the entry stays queued, not in-flight, delivered once later`() {
        val s = scenario("""{"pending_deliveries":[{"token":"t1","ownerToken":null,"productId":"p1"}]}""")
        var cancelled = false
        s.mgr.completion.delegateCallGate = { _ -> if (!cancelled) { cancelled = true; cancelManagerChildren(s) } }
        s.setDelegate() // trigger (ii): the drain posts the call, then its await sees the cancellation
        s.idle()
        assertTrue(cancelled)
        assertEquals("the abandoned call never ran", 0, delivered(s))
        assertEquals(listOf("t1"), s.queueTokens())
        assertFalse(s.mgr.completion.purchaseCompletionMutex.isLocked)

        s.mgr.triggerDrain(); s.idle()
        assertEquals("the in-flight mark was cleared: the next drain delivers", 1, delivered(s))
        assertEquals(emptyList<String>(), s.queueTokens())
        s.mgr.triggerDrain(); s.idle()
        assertEquals(1, delivered(s))
    }

    /**
     * The drain is cancelled WHILE the delegate call runs on Main. The call started, so it counts as
     * delivered: the entry is removed and never delivered a second time.
     */
    @Test fun `a drain cancelled while the delegate call runs - counted delivered, never re-delivered`() {
        val s = scenario("""{"pending_deliveries":[{"token":"t1","ownerToken":null,"productId":"p1"}]}""")
        var cancelled = false
        s.delegateHook = { if (!cancelled) { cancelled = true; cancelManagerChildren(s) } }
        s.setDelegate(); s.idle()
        assertTrue(cancelled)
        assertEquals(1, delivered(s))
        assertEquals("removed, not left queued", emptyList<String>(), s.queueTokens())
        s.mgr.triggerDrain(); s.idle()
        assertEquals("never re-delivered", 1, delivered(s))
    }

    // ---------------------------------------------------------------------------------------------
    // a cancelled verify (impl audit round 2, A9)
    // ---------------------------------------------------------------------------------------------

    /**
     * The listener's run parks in verify and its scope is cancelled. A cancellation is not a verify
     * failure: no `purchase_failed`, and the lock is released. NEGATIVE CONTROL: without the
     * `CancellationException` rethrow the catch-all reports it as a verification failure — one
     * `purchase_failed` — and this test fails.
     */
    @Test fun `a cancelled scope during verify emits no purchase_failed`() {
        val s = scenario(inFlightPurchase)
        s.setDelegate()
        s.fake.autoDeliver = false
        val caller = kotlinx.coroutines.MainScope()
        try {
            caller.launch { runCatching { s.mgr.purchase(activity(), "lifetime_unlock") } }
            s.idle()
            val gate = CompletableDeferred<Unit>()
            val parked = mutableListOf<String>()
            s.verifyGate = { parked += it; gate.await() }
            s.fake.deliver(s.fake.listenerPurchase!!, "inapp")
            s.idle()
            assertEquals(listOf("tok_x"), parked)
            assertTrue(s.mgr.completion.purchaseCompletionMutex.isLocked)

            cancelManagerChildren(s)
            s.idle()
            assertEquals(0, events("purchase_failed"))
            assertEquals(0, events("purchase_completed"))
            assertFalse(s.mgr.completion.purchaseCompletionMutex.isLocked)
        } finally {
            caller.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // a cancelled restore (SPEC-497 §13b.2 R37, I4 r6 M1)
    // ---------------------------------------------------------------------------------------------

    /**
     * `restorePurchases()` runs in the CALLER's scope and parks in the `/billing/verify` gate (holding
     * `purchaseCompletionMutex`); the caller is cancelled. A cancellation is not a failed restore: the
     * caller gets the `CancellationException` as is, no `purchase_restore_failed` is tracked, and the lock
     * is released. NEGATIVE CONTROL: without the `catch (e: CancellationException) { throw e }` ahead of
     * the tracking catch in `restoreDetailed`, the catch-all tracks one `purchase_restore_failed` and
     * rethrows a `BillingError.ServerError` — this test fails on both counts.
     */
    @Test fun `a cancelled caller during restore - CancellationException rethrown, no purchase_restore_failed`() {
        val s = scenario("""{
          "product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"lifetime_unlock","token":"tok_rs","order_id":"GPA.rs","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "verify_replies":{"tok_rs":{"status":200,"consume":false,"expires_at":null,"is_trial":false}}
        }""")
        val gate = CompletableDeferred<Unit>()
        val parked = mutableListOf<String>()
        s.verifyGate = { parked += it; gate.await() }

        val caller = kotlinx.coroutines.MainScope()
        var caught: Throwable? = null
        var returned = false
        try {
            caller.launch {
                try {
                    s.mgr.restorePurchases()
                    returned = true
                } catch (t: Throwable) {
                    caught = t
                    throw t
                }
            }
            s.idle()
            assertEquals("the restore is parked in the verify gate", listOf("tok_rs"), parked)
            assertTrue(s.mgr.completion.purchaseCompletionMutex.isLocked)
            assertNull(caught)

            caller.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            s.idle()
            assertFalse(returned)
            assertTrue(
                "the caller got the CancellationException as is, not a BillingError: $caught",
                caught is kotlinx.coroutines.CancellationException && caught !is BillingError,
            )
            assertEquals(0, events("purchase_restore_failed"))
            assertEquals(0, events("purchase_restored"))
            assertTrue("onRestoreCompleted never fired", s.delegateLog.none { it.first == "onRestoreCompleted" })
            assertFalse(s.mgr.completion.purchaseCompletionMutex.isLocked)
        } finally {
            gate.complete(Unit)
            caller.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
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
