package ai.appdna.sdk.billing

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.paywalls.AppDNAPaywallDelegate
import ai.appdna.sdk.paywalls.PaywallManager
import ai.appdna.sdk.storage.LocalStorage
import android.app.Activity
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * SPEC-497 I4 M3 — the §13a.2 / §13a.5 cases the implementation audit found untested, each driven
 * through the production entry points (`PurchaseCompletion.run`, the Play listener, the drain,
 * `AppDNA.billingAfterIdentify`) with [FakeBillingClient] and a scripted server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PurchaseCompletionRequiredCasesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var sc: BillingScenario? = null
    private var pipe: BillingTestPipeline? = null

    @After fun tearDown() {
        sc?.teardown(); pipe?.close(); AppDNA.paywall.setDelegate(null)
    }

    private fun scenario(json: String): BillingScenario {
        pipe = BillingTestPipeline(ctx, "required")
        NativeBillingManager.clearPersistedSnapshot(LocalStorage(ctx))
        return BillingScenario(ctx, JSONObject(json)).also { sc = it }
    }

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private fun delivered(s: BillingScenario) = s.delegateLog.count { it.first == "onPurchaseCompleted" }
    private fun events(name: String) = pipe!!.count(name)

    private class SimulatedCrash : RuntimeException("process died between persist and emit")

    // ---------------------------------------------------------------------------------------------
    // §13a.2 queue list
    // ---------------------------------------------------------------------------------------------

    @Test fun `Q6 - a caller cancelled after the step-3 decision is queued and delivered once, never inline too`() {
        val s = scenario("""{
          "product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"lifetime_unlock","token":"tok_c","order_id":"GPA.c","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "listener_purchase_token":"tok_c",
          "verify_replies":{"tok_c":{"status":200,"consume":false,"expires_at":null,"is_trial":false}}
        }""")
        s.setDelegate()
        var result: PurchaseResult? = null
        val caller = kotlinx.coroutines.MainScope().launch { result = s.mgr.purchase(activity(), "lifetime_unlock") }
        var cancelledAtStep3 = false
        // Step 3 has taken the live caller and persisted; the caller is cancelled before step 5 resumes it.
        s.mgr.completion.afterPersistHook = {
            if (!cancelledAtStep3) { cancelledAtStep3 = true; caller.cancel() }
        }
        s.idle(200)
        assertTrue(cancelledAtStep3)
        assertNull("the cancelled caller never sees a result", result)
        assertEquals(1, events("purchase_completed"))
        assertEquals("delivered exactly once — by the drain, not inline", 1, delivered(s))
        assertEquals(emptyList<String>(), s.queueTokens())
        s.mgr.triggerDrain(); s.idle()
        assertEquals(1, delivered(s))
    }

    @Test fun `R62 - a purchase in flight plus an ON_START rescue of another product - Purchased once, the rescue queued`() {
        val s = scenario("""{
          "product_details":{
            "lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"},
            "other_unlock":{"price_micros":2990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[
            {"product_id":"lifetime_unlock","token":"tok_a","order_id":"GPA.a","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"},
            {"product_id":"other_unlock","token":"tok_b","order_id":"GPA.b","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "listener_purchase_token":"tok_a",
          "verify_replies":{
            "tok_a":{"status":200,"consume":false,"expires_at":null,"is_trial":false},
            "tok_b":{"status":200,"consume":false,"expires_at":null,"is_trial":false}}
        }""")
        s.fake.autoDeliver = false
        s.mgr.currentPaywallId = "pw_r62"
        var result: PurchaseResult? = null
        var resolutions = 0
        s.mgr.scope.launch { result = s.mgr.purchase(activity(), "lifetime_unlock"); resolutions++ }
        s.idle()

        s.drive { s.mgr.reconcileSubscriptionState() }
        assertNull("the rescue of another product never takes the in-flight caller", result)
        assertTrue(s.mgr.hasLiveContinuation())
        assertEquals(listOf("tok_b"), s.queueTokens())
        assertEquals("", pipe!!.props("purchase_completed").getString("paywall_id"))

        s.fake.deliver(s.fake.listenerPurchase!!, "inapp")
        s.idle(200)
        assertTrue(result is PurchaseResult.Purchased)
        assertEquals("lifetime_unlock", (result as PurchaseResult.Purchased).entitlement.productId)
        assertEquals(1, resolutions)
        assertEquals(2, events("purchase_completed"))
        val byProduct = pipe!!.events().filter { it.first == "purchase_completed" }.associate { it.second.getString("product_id") to it.second.getString("paywall_id") }
        assertEquals(mapOf("other_unlock" to "", "lifetime_unlock" to "pw_r62"), byProduct)
        assertEquals("the live report is not queued; the rescue still is", listOf("tok_b"), s.queueTokens())
    }

    @Test fun `a callback that returns after the 30 s timeout counts as delivered`() {
        val s = scenario("""{"pending_deliveries":[{"token":"t_late","ownerToken":null,"productId":"p"}]}""")
        // The host's callback runs 31 s (Robolectric's SystemClock.sleep advances the Main looper clock).
        s.delegateHook = { SystemClock.sleep(31_000) }
        s.setDelegate(); s.idle()
        assertEquals(1, delivered(s))
        assertEquals("the call ran past the 30 s bound", 1, s.mgr.completion.delegateTimeouts.get())
        assertEquals("removed, not re-queued", emptyList<String>(), s.queueTokens())
        s.delegateHook = null
        s.mgr.triggerDrain(); s.idle()
        assertEquals("never re-delivered", 1, delivered(s))
    }

    @Test fun `the drain has a total budget of 60 s - the rest waits for the next trigger`() {
        val s = scenario("{}")
        val now = System.currentTimeMillis()
        s.ledger.write(deliveries = (1..4).map { PendingDelivery("t$it", "t$it", "p$it", 0, 1, null, now) })
        var fakeNow = 5_000_000L
        s.mgr.completion.clock = { fakeNow }
        s.delegateHook = { fakeNow += 25_000 } // each delivery takes 25 s
        s.setDelegate(); s.idle()
        // 0 s → t1, 25 s → t2, 50 s → t3, 75 s > 60 s → stop.
        assertEquals(3, delivered(s))
        assertEquals(listOf("t4"), s.queueTokens())
        s.mgr.triggerDrain(); s.idle()
        assertEquals(4, delivered(s))
        assertEquals(emptyList<String>(), s.queueTokens())
    }

    @Test fun `a drain triggered from a report does not deadlock`() {
        val s = scenario("""{
          "product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"lifetime_unlock","token":"tok_d","order_id":"GPA.d","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "verify_replies":{"tok_d":{"status":200,"consume":false,"expires_at":null,"is_trial":false}}
        }""")
        s.setDelegate()
        // run → (lock released) → drain, in ONE coroutine: a non-reentrant mutex held across would hang here.
        val r = s.drive {
            s.mgr.completion.run(PurchaseCompletion.Entry.ON_START)
            s.mgr.completion.drainDeliveries()
            "finished"
        }
        assertEquals("finished", r.getOrThrow())
        assertEquals(1, delivered(s))
        assertFalse(s.mgr.completion.purchaseCompletionMutex.isLocked)

        // And through the real listener, whose report triggers the drain itself (trigger i).
        s.fake.deliver(FakeBillingClient.purchase("lifetime_unlock", "tok_d2"), "inapp")
        s.setup.getJSONObject("verify_replies").put("tok_d2", JSONObject("""{"status":200,"consume":false}"""))
        s.idle(200)
        assertEquals(2, delivered(s))
        assertEquals(emptyList<String>(), s.queueTokens())
        assertFalse(s.mgr.completion.purchaseCompletionMutex.isLocked)
    }

    @Test fun `Q1 - the report path makes exactly one ledger write holding the reported set and the queue entry`() {
        val s = scenario("""{
          "product_details":{"coins_100":{"price_micros":990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"coins_100","token":"tok_w","order_id":"GPA.w","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "verify_replies":{"tok_w":{"status":200,"consume":true,"expires_at":null,"is_trial":false}}
        }""")
        // `afterPersistHook` runs right after step 3's write and BEFORE the emit: it splits the writes.
        var emitted = false
        s.mgr.completion.afterPersistHook = { emitted = true }
        val writes = mutableListOf<Pair<Map<String, String?>, Boolean>>()
        s.ledger.writeObserver = { values -> writes += values to emitted }
        s.drive { s.mgr.completion.run(PurchaseCompletion.Entry.ON_START) }
        s.ledger.writeObserver = null
        s.mgr.completion.afterPersistHook = null

        val beforeEmit = writes.filter { (v, afterEmit) ->
            !afterEmit && (v.containsKey(PurchaseLedger.KEY_REPORTED) || v.containsKey(PurchaseLedger.KEY_DELIVERIES))
        }
        assertEquals("ONE write before the emit touches the reported set / the queue", 1, beforeEmit.size)
        val w = beforeEmit.single().first
        assertTrue(w[PurchaseLedger.KEY_REPORTED]!!.contains("tok_w"))
        assertTrue(w[PurchaseLedger.KEY_DELIVERIES]!!.contains("tok_w"))
        assertEquals(1, events("purchase_completed"))
        // Every one of those writes was one setStringsSync (commit): a fresh read sees both keys.
        val prefs = ctx.getSharedPreferences("ai.appdna.sdk", Context.MODE_PRIVATE)
        assertTrue(prefs.getString(PurchaseLedger.KEY_REPORTED, "")!!.contains("tok_w"))
        assertTrue(prefs.getString(PurchaseLedger.KEY_DELIVERIES, "")!!.contains("tok_w"))
    }

    @Test fun `a crash between persist and emit gives no second purchase_completed after the restart`() {
        val s = scenario("""{
          "product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"lifetime_unlock","token":"tok_k","order_id":"GPA.k","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "verify_replies":{"tok_k":{"status":200,"consume":false,"expires_at":null,"is_trial":false}}
        }""")
        s.mgr.completion.afterPersistHook = { throw SimulatedCrash() }
        val crashed = s.drive { s.mgr.completion.run(PurchaseCompletion.Entry.ON_START) }
        assertTrue(crashed.exceptionOrNull() is SimulatedCrash)
        assertEquals("the emit was lost with the crash (accepted, documented)", 0, events("purchase_completed"))
        assertFalse(s.mgr.completion.purchaseCompletionMutex.isLocked)

        // "Restart": the ledger ON DISK already has the token reported, with its acknowledge pending.
        val onDisk = PurchaseLedger(LocalStorage(ctx)).reported()
        assertEquals(listOf(ReportedToken("tok_k", Finalize.Acknowledge)), onDisk)

        s.mgr.completion.afterPersistHook = null
        s.drive { s.mgr.reconcileSubscriptionState() }
        s.drive { s.mgr.reconcileSubscriptionState() }
        assertEquals("never a double emit", 0, events("purchase_completed"))
        assertEquals("precedence (2) retried only the pending acknowledge", listOf("tok_k"), s.fake.acknowledgeCalls)
        assertEquals(listOf(mapOf("token" to "tok_k", "pending_finalize" to null)), s.reportedState())
    }

    // ---------------------------------------------------------------------------------------------
    // §13a.5
    // ---------------------------------------------------------------------------------------------

    @Test fun `a SUBS purchase whose verify failed - identify wins the race and reports it once`() {
        val s = scenario("""{
          "current_user_id":"user_a","first_identified_user_id":"user_a",
          "product_details":{"monthly_sub":{"price_micros":9990000,"currency":"USD","product_type":"subs"}},
          "owned_purchases":[{"product_id":"monthly_sub","token":"tok_s","order_id":"GPA.s..0","acknowledged":false,"product_type":"subs","purchase_state":"PURCHASED"}],
          "listener_purchase_token":"tok_s",
          "verify_replies":{"tok_s":{"status":503}}
        }""")
        s.setDelegate()
        val failed = s.drive { s.mgr.purchase(activity(), "monthly_sub") }.getOrNull()
        assertTrue(failed is PurchaseResult.Failed)
        assertEquals(1, events("purchase_failed"))
        assertEquals(0, events("purchase_completed"))

        // The server recovers. identify's refresh takes the lock first and PARKS in its verify while
        // ON_START starts and waits for the lock.
        s.setup.getJSONObject("verify_replies").put("tok_s", JSONObject("""{"status":200,"consume":false,"expires_at":"2026-10-30T00:00:00Z","is_trial":false}"""))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        s.verifyGate = { gate.await() }
        var identifyDone = false
        var onStartDone = false
        s.mgr.scope.launch { AppDNA.billingAfterIdentify("user_a", null); identifyDone = true }
        s.idle()
        s.mgr.scope.launch { s.mgr.reconcileSubscriptionState(); onStartDone = true }
        s.idle()
        assertFalse(identifyDone || onStartDone)
        s.verifyGate = null
        gate.complete(Unit)
        s.idle(200)
        assertTrue(identifyDone && onStartDone)

        assertEquals(1, events("purchase_completed"))
        assertEquals(1, events("subscription_started"))
        assertEquals("", pipe!!.props("purchase_completed").getString("paywall_id"))
        assertEquals("the purchase path's verify and identify's — ON_START verified nothing", listOf("tok_s", "tok_s"), s.verifyCalls)
        assertTrue("identify's /restore includes the token it just reported", s.restoreCalls.first().contains("tok_s"))
        assertEquals(0, events("purchase_restored"))
        assertEquals(1, delivered(s))
    }

    @Test fun `R21-S2 - the listener's purchase is reported even when the re-query lags, and the re-query adds other tokens`() {
        val s = scenario("""{
          "current_user_id":"user_a","first_identified_user_id":"user_a",
          "product_details":{
            "lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"},
            "other_unlock":{"price_micros":2990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[
            {"product_id":"lifetime_unlock","token":"tok_l","order_id":"GPA.l","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"},
            {"product_id":"other_unlock","token":"tok_o","order_id":"GPA.o","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "listener_purchase_token":"tok_l",
          "verify_replies":{
            "tok_l":{"status":200,"consume":false,"expires_at":null,"is_trial":false},
            "tok_o":{"status":200,"consume":false,"expires_at":null,"is_trial":false}}
        }""")
        s.fake.autoDeliver = false
        s.mgr.currentPaywallId = "pw_s2"
        var result: PurchaseResult? = null
        s.mgr.scope.launch { result = s.mgr.purchase(activity(), "lifetime_unlock") }
        s.idle()
        // Play's listener delivers tok_l, but queryPurchasesAsync does not return it yet.
        s.fake.deliverWithoutOwning(s.fake.listenerPurchase!!)
        s.idle(200)
        assertTrue(result is PurchaseResult.Purchased)
        assertEquals(setOf("tok_l", "tok_o"), s.verifyCalls.toSet())
        val byProduct = pipe!!.events().filter { it.first == "purchase_completed" }.associate { it.second.getString("product_id") to it.second.getString("paywall_id") }
        assertEquals(mapOf("lifetime_unlock" to "pw_s2", "other_unlock" to ""), byProduct)
        assertEquals("only the other token (no live caller) is queued", listOf("tok_o"), s.queueTokens())
    }

    @Test fun `a restore right after a purchase leaves that purchase out of restored_count`() {
        val s = scenario("""{
          "product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"lifetime_unlock","token":"tok_p","order_id":"GPA.p","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "listener_purchase_token":"tok_p",
          "verify_replies":{"tok_p":{"status":503}}
        }""")
        val failed = s.drive { s.mgr.purchase(activity(), "lifetime_unlock") }.getOrNull()
        assertTrue(failed is PurchaseResult.Failed)
        s.setup.getJSONObject("verify_replies").put("tok_p", JSONObject("""{"status":200,"consume":false,"expires_at":null,"is_trial":false}"""))

        // The paywall's restore button: PaywallManager.handleRestore → restoreDetailed → restoreOutcome.
        val restored = mutableListOf<List<String>>()
        val listener = object : AppDNAPaywallDelegate {
            override fun onPaywallRestoreCompleted(paywallId: String, productIds: List<String>) { restored += productIds }
        }
        val pm = PaywallManager(ai.appdna.sdk.config.RemoteConfigManager(null, LocalStorage(ctx), 3600L), pipe!!.tracker)
        PaywallManager::class.java.getDeclaredMethod("handleRestore", String::class.java, AppDNAPaywallDelegate::class.java)
            .apply { isAccessible = true }
            .invoke(pm, "pw_restore", listener)
        s.idle(200)

        assertEquals(1, events("purchase_completed")) // reported by this restore
        val aggregate = pipe!!.events().filter { it.first == "purchase_restored" && it.second.has("restored_count") }
        assertEquals(1, aggregate.size)
        assertEquals("the product this restore just reported is not counted as restored", 0, aggregate.single().second.getInt("restored_count"))
        assertEquals("no per-product purchase_restored for it either", 0, pipe!!.events().count { it.first == "purchase_restored" && it.second.has("product_id") })
        assertEquals(listOf(listOf("lifetime_unlock")), restored) // onRestoreCompleted still lists it
    }

    @Test fun `a consumed INAPP never enters the SUBS lifecycle snapshot - no phantom subscription_canceled`() {
        val s = scenario("""{
          "product_details":{
            "coins_100":{"price_micros":990000,"currency":"USD","product_type":"inapp"},
            "monthly_sub":{"price_micros":9990000,"currency":"USD","product_type":"subs"}},
          "owned_purchases":[
            {"product_id":"coins_100","token":"tok_cn","order_id":"GPA.cn","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"},
            {"product_id":"monthly_sub","token":"tok_sb","order_id":"GPA.sb..0","acknowledged":true,"product_type":"subs","purchase_state":"PURCHASED"}],
          "verify_replies":{"tok_cn":{"status":200,"consume":true,"expires_at":null,"is_trial":false}}
        }""")
        s.drive { s.mgr.reconcileSubscriptionState() }
        assertEquals(listOf("tok_cn"), s.fake.consumeCalls)
        assertEquals(setOf("monthly_sub"), s.mgr.loadLastSnapshot().keys)
        // The consumed unit is gone from Play now; the next ON_START must not "cancel" anything.
        s.drive { s.mgr.reconcileSubscriptionState() }
        assertEquals(setOf("monthly_sub"), s.mgr.loadLastSnapshot().keys)
        assertEquals(0, events("subscription_canceled"))
        assertEquals(1, events("purchase_completed"))
    }

    @Test fun `an acknowledge that times out at 30 s is retryable - pending, retried by the next run`() {
        val s = scenario("""{
          "product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"lifetime_unlock","token":"tok_ah","order_id":"GPA.ah","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "verify_replies":{"tok_ah":{"status":200,"consume":false,"expires_at":null,"is_trial":false}}
        }""")
        s.fake.acknowledgeHangs += "tok_ah"
        runPastTheTimeout(s)
        assertEquals(listOf(mapOf("token" to "tok_ah", "pending_finalize" to "acknowledge")), s.reportedState())
        s.drive { s.mgr.reconcileSubscriptionState() }
        assertEquals(listOf("tok_ah", "tok_ah"), s.fake.acknowledgeCalls)
        assertEquals(listOf(mapOf("token" to "tok_ah", "pending_finalize" to null)), s.reportedState())
        assertEquals(1, events("purchase_completed"))
    }

    @Test fun `a consume that times out at 30 s is retryable - pending, retried by the next run`() {
        val s = scenario("""{
          "product_details":{"coins_100":{"price_micros":990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"coins_100","token":"tok_ch","order_id":"GPA.ch","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "verify_replies":{"tok_ch":{"status":200,"consume":true,"expires_at":null,"is_trial":false}}
        }""")
        s.fake.consumeHangs += "tok_ch"
        runPastTheTimeout(s)
        assertEquals(listOf(mapOf("token" to "tok_ch", "pending_finalize" to "consume")), s.reportedState())
        s.drive { s.mgr.reconcileSubscriptionState() }
        assertEquals(listOf("tok_ch", "tok_ch"), s.fake.consumeCalls)
        assertEquals(listOf(mapOf("token" to "tok_ch", "pending_finalize" to null)), s.reportedState())
        assertEquals(1, events("purchase_completed"))
    }

    /** An ON_START whose finalize never answers: the 30 s bound ends it and releases the lock. */
    private fun runPastTheTimeout(s: BillingScenario) {
        var done = false
        s.mgr.scope.launch { s.mgr.reconcileSubscriptionState(); done = true }
        s.idle()
        assertFalse("parked on Play's finalize callback", done)
        assertTrue(s.mgr.completion.purchaseCompletionMutex.isLocked)
        shadowOf(Looper.getMainLooper()).idleFor(31, TimeUnit.SECONDS)
        s.idle()
        assertTrue(done)
        assertFalse(s.mgr.completion.purchaseCompletionMutex.isLocked)
        assertEquals(1, events("purchase_completed"))
    }

    // ---------------------------------------------------------------------------------------------
    // ITEM_ALREADY_OWNED (I4 minors 6, 7)
    // ---------------------------------------------------------------------------------------------

    @Test fun `ITEM_ALREADY_OWNED - a consumable whose pending consume fails again resolves Failed item_already_owned`() {
        val s = scenario("""{
          "product_details":{"coins_100":{"price_micros":990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"coins_100","token":"tok_cf","order_id":"GPA.cf","acknowledged":true,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "reported_tokens":[{"token":"tok_cf","pending_finalize":"consume"}],
          "consume_failures":["tok_cf"],
          "play_response_codes":["ITEM_ALREADY_OWNED"]
        }""")
        val r = s.drive { s.mgr.purchase(activity(), "coins_100") }.getOrNull()
        assertEquals(PurchaseResult.Failed("item_already_owned", "unknown"), r)
        assertEquals(0, events("purchase_restored"))
        assertEquals("item_already_owned", pipe!!.events().last { it.first == "purchase_failed" }.second.getString("reason"))
        assertEquals(1, s.fake.launchCount) // no relaunch: it would answer ITEM_ALREADY_OWNED again
    }

    @Test fun `ITEM_ALREADY_OWNED - a failed precedence-4 verify resolves Failed (R80), not a restore`() {
        val s = scenario("""{
          "product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"lifetime_unlock","token":"tok_vf","order_id":"GPA.vf","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "play_response_codes":["ITEM_ALREADY_OWNED"],
          "verify_replies":{"tok_vf":{"status":503}}
        }""")
        val r = s.drive { s.mgr.purchase(activity(), "lifetime_unlock") }.getOrNull()
        assertTrue(r is PurchaseResult.Failed)
        assertEquals("verificationFailed", (r as PurchaseResult.Failed).errorType)
        assertEquals(1, events("purchase_failed"))
        assertEquals(0, events("purchase_restored"))
        assertEquals(0, events("purchase_completed"))
    }

    // ---------------------------------------------------------------------------------------------
    // I4 minors 3, 5
    // ---------------------------------------------------------------------------------------------

    @Test fun `minor 5 - a token the in-flight purchase owns gets no precedence-2 retry either`() {
        val s = scenario("""{
          "product_details":{"coins_100":{"price_micros":990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"coins_100","token":"tok_pf","order_id":"GPA.pf","acknowledged":true,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "reported_tokens":[{"token":"tok_pf","pending_finalize":"consume"}]
        }""")
        s.fake.autoDeliver = false
        s.mgr.scope.launch { s.mgr.purchase(activity(), "coins_100") }
        s.idle()
        s.drive { s.mgr.reconcileSubscriptionState() }
        assertEquals("skipped: processed by no precedence while its purchase is in flight", emptyList<String>(), s.fake.consumeCalls)
        assertEquals(listOf(mapOf("token" to "tok_pf", "pending_finalize" to "consume")), s.reportedState())
    }

    @Test fun `minor 3 - the identified-users seed holds the first-identified AND the previous current user`() {
        val s = scenario("""{"first_identified_user_id":"user_first"}""")
        s.ledger.clearForTesting()
        assertNull(s.ledger.identifiedTokens())
        s.drive { s.mgr.completion.noteIdentified("user_new", previousUserId = "user_prev") }
        val tokens = s.ledger.identifiedTokens()!!.toSet()
        val expected = listOf("user_first", "user_prev", "user_new")
            .map { AppAccountTokenResolver.token(it).toString().lowercase() }.toSet()
        assertEquals(expected, tokens)
    }
}
