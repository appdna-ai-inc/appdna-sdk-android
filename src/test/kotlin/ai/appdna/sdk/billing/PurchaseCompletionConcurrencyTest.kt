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
 * The concurrency cases with REAL contention.
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
     * +2 — the cross-user grant race. Drain 1 marks user A's tagged entry in-flight
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
     * +2 — the delegate is re-read on Main too. The host clears the delegate AFTER
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
    // a cancelled drain
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
    // a cancelled verify
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
    // a cancelled restore
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

    /**
     * The PAYWALL restore button's run parks in the verify gate and the paywall
     * scope is cancelled (`PaywallManager.shutdown()`, as `AppDNA.shutdown()` does). A cancellation is
     * not a failed restore: no `purchase_restore_failed` and no `onPaywallRestoreFailed`. NEGATIVE
     * CONTROL: without the `catch (e: CancellationException) { throw e }` in `handleRestore`, its
     * catch-all tracks one `purchase_restore_failed` (with `paywall_id`) and calls
     * `onPaywallRestoreFailed` — this test fails.
     */
    @Test fun `a paywall restore cancelled by shutdown - no purchase_restore_failed, no onPaywallRestoreFailed`() {
        val s = scenario("""{
          "product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"lifetime_unlock","token":"tok_pr","order_id":"GPA.pr","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "verify_replies":{"tok_pr":{"status":200,"consume":false,"expires_at":null,"is_trial":false}}
        }""")
        val gate = CompletableDeferred<Unit>()
        val parked = mutableListOf<String>()
        s.verifyGate = { parked += it; gate.await() }

        val paywallEvents = mutableListOf<String>()
        val listener = object : AppDNAPaywallDelegate {
            override fun onPaywallRestoreStarted(paywallId: String) { paywallEvents += "started" }
            override fun onPaywallRestoreCompleted(paywallId: String, productIds: List<String>) { paywallEvents += "completed" }
            override fun onPaywallRestoreFailed(paywallId: String, error: Throwable) { paywallEvents += "failed:${error::class.simpleName}" }
        }
        val pm = ai.appdna.sdk.paywalls.PaywallManager(
            ai.appdna.sdk.config.RemoteConfigManager(null, ai.appdna.sdk.storage.LocalStorage(ctx), 3600L), pipe!!.tracker,
        )
        try {
            pm.handleRestore("pw_cancel", listener)
            s.idle()
            assertEquals("the paywall restore is parked in the verify gate", listOf("tok_pr"), parked)
            assertTrue(s.mgr.completion.purchaseCompletionMutex.isLocked)

            // The host set the one-shot skip flag for THIS restore; the cancellation must clear
            // it, or it leaks into the next paywall's restore. NEGATIVE CONTROL: without the clear in the
            // `CancellationException` catch of `handleRestore`, the flag is still true and this fails.
            AppDNA.paywall.skipNextAutoDismissOnRestore = true
            pm.shutdown()
            s.idle()
            assertEquals(0, events("purchase_restore_failed"))
            assertEquals(0, events("purchase_restored"))
            assertEquals("no onPaywallRestoreFailed / Completed", listOf("started"), paywallEvents)
            assertFalse(s.mgr.completion.purchaseCompletionMutex.isLocked)
            assertFalse("the skip flag is cleared by the cancelled restore", AppDNA.paywall.skipNextAutoDismissOnRestore)
        } finally {
            AppDNA.paywall.skipNextAutoDismissOnRestore = false
            gate.complete(Unit)
            pm.shutdown()
        }
    }

    /**
     * The PAYWALL purchase is waiting for Play's result and the paywall scope is
     * cancelled (`PaywallManager.shutdown()`, as `AppDNA.shutdown()` does). A cancellation is not a failed
     * purchase: no `onPaywallPurchaseFailed` and no `purchase_failed` (the purchase may still complete, and
     * Then reports it through the delivery queue — a failure callback first would contradict it).
     * NEGATIVE CONTROL: without the `catch (e: CancellationException) { throw e }` ahead of the
     * `PurchaseCancelledException` catch in `handlePurchase`, the catch-all hands the cancellation to
     * `onPaywallPurchaseFailed` — this test fails.
     */
    @Test fun `a paywall purchase cancelled by shutdown - no onPaywallPurchaseFailed`() {
        val s = scenario(inFlightPurchase)
        s.setDelegate()
        s.fake.autoDeliver = false // Play never answers: the purchase waits for its result
        val paywallEvents = mutableListOf<String>()
        val listener = object : AppDNAPaywallDelegate {
            override fun onPaywallPurchaseStarted(paywallId: String, productId: String) { paywallEvents += "started" }
            override fun onPaywallPurchaseCompleted(paywallId: String, productId: String, transaction: TransactionInfo) { paywallEvents += "completed" }
            override fun onPaywallPurchaseFailed(paywallId: String, error: Throwable, errorType: String, productId: String?) {
                paywallEvents += "failed:$errorType"
            }
        }
        val paywall = ai.appdna.sdk.paywalls.PaywallConfigParser.parsePaywalls(mapOf("pw_buy" to mapOf(
            "id" to "pw_buy", "name" to "t", "layout" to mapOf("type" to "stack"), "sections" to emptyList<Any>(),
        )))["pw_buy"]!!
        val pm = ai.appdna.sdk.paywalls.PaywallManager(
            ai.appdna.sdk.config.RemoteConfigManager(null, ai.appdna.sdk.storage.LocalStorage(ctx), 3600L), pipe!!.tracker,
        )
        try {
            pm.handlePurchase(
                activity(), "pw_buy",
                ai.appdna.sdk.paywalls.PaywallPlan(id = "l", product_id = "lifetime_unlock", name = "L", price = ""),
                paywall, emptyMap(), listener,
            )
            s.idle()
            assertEquals("the purchase launched and waits for Play", 1, s.fake.launchCount)
            assertEquals(listOf("started"), paywallEvents)

            val resetsBefore = ai.appdna.sdk.paywalls.PaywallActivity.purchaseEndedSignal.get()
            pm.shutdown()
            s.idle()
            assertEquals("no onPaywallPurchaseFailed / Completed", listOf("started"), paywallEvents)
            assertEquals(0, events("purchase_failed"))
            // The cancelled purchase re-enables a paywall still on screen. NEGATIVE CONTROL:
            // without `signalPurchaseEnded()` in the CancellationException catch, the signal is unchanged.
            assertTrue("the purchase-ended signal was bumped",
                ai.appdna.sdk.paywalls.PaywallActivity.purchaseEndedSignal.get() > resetsBefore)

            // After the cancel: Play completes the purchase later. The cancelled caller
            // is gone (and its paywall attribution with it), so the report is queued and delivered by the
            // drain: exactly one purchase_completed and one onPurchaseCompleted, and no paywall callback.
            // NEGATIVE CONTROL: with the queue entry for a report that resolved no live caller not written
            // (`deliveries = null` in PurchaseCompletion's one write), no onPurchaseCompleted — this fails.
            s.fake.deliver(s.fake.listenerPurchase!!, "inapp")
            s.idle()
            assertEquals(1, delivered(s))
            assertEquals(1, events("purchase_completed"))
            assertEquals(listOf("started"), paywallEvents)
            assertEquals(emptyList<String>(), paywallCalls)
        } finally {
            pm.shutdown()
        }
    }

    /**
     * A paywall purchase that SUCCEEDS with no `post_purchase.on_success` config leaves the
     * paywall on screen for the host to close, so its CTA must stop spinning. NEGATIVE CONTROL: without
     * `signalPurchaseEnded()` in `handlePostPurchaseSuccess`'s no-config branch, the signal is unchanged.
     */
    @Test fun `a paywall purchase that succeeds with no on_success config re-enables the CTA`() {
        val s = scenario(inFlightPurchase)
        s.setDelegate()
        val paywallEvents = mutableListOf<String>()
        val listener = object : AppDNAPaywallDelegate {
            override fun onPaywallPurchaseCompleted(paywallId: String, productId: String, transaction: TransactionInfo) { paywallEvents += "completed" }
            override fun onPaywallPurchaseFailed(paywallId: String, error: Throwable, errorType: String, productId: String?) {
                paywallEvents += "failed:$errorType"
            }
        }
        val paywall = ai.appdna.sdk.paywalls.PaywallConfigParser.parsePaywalls(mapOf("pw_buy" to mapOf(
            "id" to "pw_buy", "name" to "t", "layout" to mapOf("type" to "stack"), "sections" to emptyList<Any>(),
        )))["pw_buy"]!!
        assertNull(paywall.post_purchase?.on_success)
        val pm = ai.appdna.sdk.paywalls.PaywallManager(
            ai.appdna.sdk.config.RemoteConfigManager(null, ai.appdna.sdk.storage.LocalStorage(ctx), 3600L), pipe!!.tracker,
        )
        try {
            val resetsBefore = ai.appdna.sdk.paywalls.PaywallActivity.purchaseEndedSignal.get()
            pm.handlePurchase(
                activity(), "pw_buy",
                ai.appdna.sdk.paywalls.PaywallPlan(id = "l", product_id = "lifetime_unlock", name = "L", price = ""),
                paywall, emptyMap(), listener,
            )
            s.idle(200)
            assertEquals(listOf("completed"), paywallEvents)
            assertEquals(1, events("purchase_completed"))
            assertTrue("the purchase-ended signal was bumped",
                ai.appdna.sdk.paywalls.PaywallActivity.purchaseEndedSignal.get() > resetsBefore)
        } finally {
            pm.shutdown()
        }
    }

    /**
     * A paywall purchase that COMPLETES through billing raises the celebrate signal, and one
     * that does not complete never does. `PaywallPurchaseCelebrationDrawsTest` proves what the paywall
     * draws when the signal rises; this proves the signal rises from a real purchase, through
     * `handlePurchase` → `AppDNA.billing.purchase` → `handlePostPurchaseSuccess`, with Play faked at
     * the `BillingClient` boundary (the emulator has no Play billing, so no device purchase succeeds).
     * NEGATIVE CONTROL: without `PaywallActivity.signalPurchaseCelebrate()` at the top of
     * `handlePostPurchaseSuccess`, the first assertion fails.
     */
    @Test fun `a completed paywall purchase raises the celebrate signal, a pending one does not`() {
        val s = scenario(inFlightPurchase)
        s.setDelegate()
        val paywall = ai.appdna.sdk.paywalls.PaywallConfigParser.parsePaywalls(mapOf("pw_buy" to mapOf(
            "id" to "pw_buy", "name" to "t", "layout" to mapOf("type" to "stack"), "sections" to emptyList<Any>(),
        )))["pw_buy"]!!
        val pm = ai.appdna.sdk.paywalls.PaywallManager(
            ai.appdna.sdk.config.RemoteConfigManager(null, ai.appdna.sdk.storage.LocalStorage(ctx), 3600L), pipe!!.tracker,
        )
        val plan = ai.appdna.sdk.paywalls.PaywallPlan(id = "l", product_id = "lifetime_unlock", name = "L", price = "")
        val signal = ai.appdna.sdk.paywalls.PaywallActivity.purchaseCelebrateSignal
        try {
            // Play has not answered yet: the purchase is in flight, nothing to celebrate.
            s.fake.autoDeliver = false
            val before = signal.get()
            pm.handlePurchase(activity(), "pw_buy", plan, paywall, emptyMap(), object : AppDNAPaywallDelegate {})
            s.idle()
            assertEquals("a purchase that has not completed raised the celebrate signal", before, signal.get())

            // Play answers: the purchase completes, and only now does the paywall celebrate.
            s.fake.deliver(s.fake.listenerPurchase!!, "inapp")
            s.idle(200)
            assertEquals(1, events("purchase_completed"))
            assertEquals("a completed paywall purchase did not raise the celebrate signal (#1110)", before + 1, signal.get())
        } finally {
            pm.shutdown()
        }
    }

    /**
     * `onPaywallPurchaseCompleted` fires ONCE per paywall purchase. The host's paywall delegate is both
     * `AppDNA.paywall.listener` and the listener the paywall was presented with (the normal setup); the
     * billing manager used to call it too, so it fired twice. NEGATIVE CONTROL: with the paywall-delegate
     * block back in `NativeBillingManager.postPurchaseDelegates`, `completed` is recorded twice.
     */
    @Test fun `onPaywallPurchaseCompleted fires once per paywall purchase`() {
        val s = scenario(inFlightPurchase)
        s.setDelegate()
        val paywallEvents = mutableListOf<String>()
        val listener = object : AppDNAPaywallDelegate {
            override fun onPaywallPurchaseCompleted(paywallId: String, productId: String, transaction: TransactionInfo) {
                paywallEvents += "completed:$paywallId/$productId"
            }
        }
        AppDNA.paywall.setDelegate(listener)
        val paywall = ai.appdna.sdk.paywalls.PaywallConfigParser.parsePaywalls(mapOf("pw_buy" to mapOf(
            "id" to "pw_buy", "name" to "t", "layout" to mapOf("type" to "stack"), "sections" to emptyList<Any>(),
        )))["pw_buy"]!!
        val pm = ai.appdna.sdk.paywalls.PaywallManager(
            ai.appdna.sdk.config.RemoteConfigManager(null, ai.appdna.sdk.storage.LocalStorage(ctx), 3600L), pipe!!.tracker,
        )
        try {
            pm.handlePurchase(
                activity(), "pw_buy",
                ai.appdna.sdk.paywalls.PaywallPlan(id = "l", product_id = "lifetime_unlock", name = "L", price = ""),
                paywall, emptyMap(), listener,
            )
            s.idle(200)
            assertEquals(1, events("purchase_completed"))
            assertEquals(1, delivered(s))
            assertEquals(listOf("completed:pw_buy/lifetime_unlock"), paywallEvents)
        } finally {
            pm.shutdown()
        }
    }

    /**
     * A paywall purchase that SUCCEEDS with an `on_success.action` this SDK does not know
     * leaves the paywall on screen, so its CTA must stop spinning. NEGATIVE CONTROL: with the `else` branch
     * of `handlePostPurchaseSuccess` back to a no-op, the signal is unchanged.
     */
    @Test fun `a paywall purchase that succeeds with an unknown on_success action re-enables the CTA`() {
        val s = scenario(inFlightPurchase)
        s.setDelegate()
        val paywallEvents = mutableListOf<String>()
        val listener = object : AppDNAPaywallDelegate {
            override fun onPaywallPurchaseCompleted(paywallId: String, productId: String, transaction: TransactionInfo) { paywallEvents += "completed" }
        }
        val paywall = ai.appdna.sdk.paywalls.PaywallConfigParser.parsePaywalls(mapOf("pw_buy" to mapOf(
            "id" to "pw_buy", "name" to "t", "layout" to mapOf("type" to "stack"), "sections" to emptyList<Any>(),
            "post_purchase" to mapOf("on_success" to mapOf("action" to "a_future_action", "delay_ms" to 0)),
        )))["pw_buy"]!!
        assertEquals("a_future_action", paywall.post_purchase?.on_success?.action)
        val pm = ai.appdna.sdk.paywalls.PaywallManager(
            ai.appdna.sdk.config.RemoteConfigManager(null, ai.appdna.sdk.storage.LocalStorage(ctx), 3600L), pipe!!.tracker,
        )
        try {
            val resetsBefore = ai.appdna.sdk.paywalls.PaywallActivity.purchaseEndedSignal.get()
            pm.handlePurchase(
                activity(), "pw_buy",
                ai.appdna.sdk.paywalls.PaywallPlan(id = "l", product_id = "lifetime_unlock", name = "L", price = ""),
                paywall, emptyMap(), listener,
            )
            s.idle(200)
            assertEquals(listOf("completed"), paywallEvents)
            assertTrue("the purchase-ended signal was bumped",
                ai.appdna.sdk.paywalls.PaywallActivity.purchaseEndedSignal.get() > resetsBefore)
        } finally {
            pm.shutdown()
        }
    }

    /**
     * `AppDNA.shutdown()` called OFF Main during a paywall purchase. `destroy()` resolves
     * the purchase `Failed("shutdown")` and that resumption is dispatched to Main; the test runs Main right
     * there (inside `endConnection`, i.e. after `destroy()` resumed the caller and before shutdown goes on),
     * as a real Main thread may. With the paywall scope cancelled FIRST, the paywall caller is already gone
     * and nothing reaches `onPaywallPurchaseFailed`. NEGATIVE CONTROL: with `paywallManager.shutdown()`
     * back after `billing.manager.destroy()`, Main runs the resumed purchase and the delegate gets
     * `failed:unknown` — this test fails.
     */
    @Test fun `AppDNA shutdown off Main during a paywall purchase - no onPaywallPurchaseFailed`() {
        val s = scenario(inFlightPurchase)
        s.fake.autoDeliver = false
        val paywallEvents = mutableListOf<String>()
        val listener = object : AppDNAPaywallDelegate {
            override fun onPaywallPurchaseStarted(paywallId: String, productId: String) { paywallEvents += "started" }
            override fun onPaywallPurchaseCompleted(paywallId: String, productId: String, transaction: TransactionInfo) { paywallEvents += "completed" }
            override fun onPaywallPurchaseFailed(paywallId: String, error: Throwable, errorType: String, productId: String?) {
                paywallEvents += "failed:$errorType"
            }
        }
        val paywall = ai.appdna.sdk.paywalls.PaywallConfigParser.parsePaywalls(mapOf("pw_buy" to mapOf(
            "id" to "pw_buy", "name" to "t", "layout" to mapOf("type" to "stack"), "sections" to emptyList<Any>(),
        )))["pw_buy"]!!
        val pm = ai.appdna.sdk.paywalls.PaywallManager(
            ai.appdna.sdk.config.RemoteConfigManager(null, ai.appdna.sdk.storage.LocalStorage(ctx), 3600L), pipe!!.tracker,
        )
        AppDNA.paywallManager = pm
        try {
            pm.handlePurchase(
                activity(), "pw_buy",
                ai.appdna.sdk.paywalls.PaywallPlan(id = "l", product_id = "lifetime_unlock", name = "L", price = ""),
                paywall, emptyMap(), listener,
            )
            s.idle()
            assertEquals(1, s.fake.launchCount)
            assertEquals(listOf("started"), paywallEvents)

            // Inside destroy(): after the in-flight purchase was resolved, let Main run what it was handed.
            val atEndConnection = java.util.concurrent.CountDownLatch(1)
            val mainRan = java.util.concurrent.CountDownLatch(1)
            s.fake.onEndConnection = {
                atEndConnection.countDown()
                mainRan.await(10, java.util.concurrent.TimeUnit.SECONDS)
            }
            val t = Thread { AppDNA.shutdown() }
            t.start()
            assertTrue("shutdown reached destroy()", atEndConnection.await(10, java.util.concurrent.TimeUnit.SECONDS))
            s.idle()
            mainRan.countDown()
            t.join(10_000)
            s.idle()
            assertEquals("no onPaywallPurchaseFailed", listOf("started"), paywallEvents)
            assertEquals(0, events("purchase_failed"))
        } finally {
            s.fake.onEndConnection = null
            AppDNA.paywallManager = null
            pm.shutdown()
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
        // A direct manager purchase (no PaywallManager on the stack) calls no paywall delegate: that call
        // belongs to PaywallManager.handlePurchase alone (see the once-per-purchase test below).
        assertEquals(emptyList<String>(), paywallCalls)
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
