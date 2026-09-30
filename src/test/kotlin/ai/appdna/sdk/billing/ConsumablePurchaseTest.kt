package ai.appdna.sdk.billing

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.TransactionInfo
import ai.appdna.sdk.paywalls.AppDNAPaywallDelegate
import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * SPEC-497 §13a.5 — the named `PurchaseCompletion` cases (LD-R19-1, R20–R24, D-R40-1) end to end through
 * the entry points, with FakeBillingClient and a scripted server. (The fixture scenarios themselves run
 * in SharedFixtureTest through the same [BillingScenario].)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConsumablePurchaseTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var sc: BillingScenario? = null
    private lateinit var pipe: BillingTestPipeline
    private val paywallCalls = mutableListOf<String>()

    @After fun tearDown() {
        sc?.teardown(); pipe.close(); AppDNA.paywall.setDelegate(null)
    }

    private fun scenario(json: String): BillingScenario {
        pipe = BillingTestPipeline(ctx, "consumable")
        AppDNA.paywall.setDelegate(object : AppDNAPaywallDelegate {
            override fun onPaywallPurchaseCompleted(paywallId: String, productId: String, transaction: TransactionInfo) {
                paywallCalls += "$paywallId/$productId"
            }
        })
        return BillingScenario(ctx, JSONObject(json)).also { sc = it }
    }

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private fun completions() = sc!!.delegateLog.count { it.first == "onPurchaseCompleted" }

    @Test fun `concurrent ON_START + identify refresh + in-flight purchase - exactly one purchase_completed`() {
        val s = scenario("""{
          "current_user_id":"user_a","first_identified_user_id":"user_a",
          "product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"lifetime_unlock","token":"tok_x","order_id":"GPA.x","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "listener_purchase_token":"tok_x",
          "verify_replies":{"tok_x":{"status":200,"consume":false,"expires_at":null,"is_trial":false}}
        }""")
        s.setDelegate()
        s.fake.autoDeliver = false
        s.mgr.currentPaywallId = "pw_1"
        var result: PurchaseResult? = null
        s.mgr.scope.launch { result = s.mgr.purchase(activity(), "lifetime_unlock") }
        s.idle()
        // The purchase is in flight; Play delivers it while ON_START and the identify refresh race it.
        s.fake.deliver(s.fake.listenerPurchase!!, "inapp")
        s.mgr.scope.launch { s.mgr.reconcileSubscriptionState() }
        s.mgr.scope.launch { s.mgr.refreshEntitlementCache() }
        s.idle(200)
        assertTrue(result is PurchaseResult.Purchased)
        assertEquals(1, pipe.count("purchase_completed"))
        assertEquals("pw_1", pipe.props("purchase_completed").getString("paywall_id"))
        assertEquals(1, completions())
        assertEquals(listOf("pw_1/lifetime_unlock"), paywallCalls)
    }

    @Test fun `SUBS verify failed then ON_START - purchase_completed and subscription_started once, queued`() {
        val s = scenario("""{
          "product_details":{"monthly_sub":{"price_micros":9990000,"currency":"USD","product_type":"subs"}},
          "owned_purchases":[{"product_id":"monthly_sub","token":"tok_s","order_id":"GPA.s..0","acknowledged":false,"product_type":"subs","purchase_state":"PURCHASED"}],
          "verify_attempted":["tok_s"],
          "verify_replies":{"tok_s":{"status":200,"consume":false,"expires_at":"2026-10-30T00:00:00Z","is_trial":false}}
        }""")
        s.setDelegate()
        s.drive { s.mgr.reconcileSubscriptionState() }
        s.drive { s.mgr.reconcileSubscriptionState() }
        assertEquals(1, pipe.count("purchase_completed"))
        assertEquals(1, pipe.count("subscription_started"))
        assertEquals("", pipe.props("purchase_completed").getString("paywall_id"))
        assertEquals(1, completions())
        assertEquals(emptyList<String>(), s.queueTokens())
    }

    @Test fun `verify failed then the identify refresh - one report, entitlement present, no purchase_restored`() {
        val s = scenario("""{
          "product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"lifetime_unlock","token":"tok_l","order_id":"GPA.l","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "verify_replies":{"tok_l":{"status":200,"consume":false,"expires_at":null,"is_trial":false}}
        }""")
        s.drive { s.mgr.refreshEntitlementCache() }
        assertEquals(1, pipe.count("purchase_completed"))
        assertEquals(0, pipe.count("purchase_restored"))
        assertEquals(listOf("lifetime_unlock"), s.entitlementIds())
        assertEquals(listOf(listOf("tok_l")), s.restoreCalls)
    }

    @Test fun `the purchase path finding its token already reported resolves Purchased from the cache, no second emit`() {
        val s = scenario("""{
          "owned_purchases":[{"product_id":"lifetime_unlock","token":"tok_r","order_id":"GPA.r","acknowledged":true,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "listener_purchase_token":"tok_r",
          "reported_tokens":[{"token":"tok_r","pending_finalize":null}],
          "cached_entitlements":[{"product_id":"lifetime_unlock","status":"active","store":"google_play","expires_at":null,"product_type":"inapp"}],
          "product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}}
        }""")
        val r = s.drive { s.mgr.purchase(activity(), "lifetime_unlock") }
        assertTrue(r.getOrNull() is PurchaseResult.Purchased)
        assertEquals(0, pipe.count("purchase_completed"))
        assertEquals(emptyList<String>(), s.verifyCalls)
    }

    @Test fun `a second purchase while one is in flight fails with purchase_in_progress`() {
        val s = scenario("""{"product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}}}""")
        s.fake.autoDeliver = false
        s.mgr.scope.launch { s.mgr.purchase(activity(), "lifetime_unlock") }
        s.idle()
        val second = s.drive { s.mgr.purchase(activity(), "lifetime_unlock") }.getOrNull() as PurchaseResult.Failed
        assertEquals("purchase_in_progress", second.error)
        assertEquals("unknown", second.errorType)
        assertEquals("purchase_in_progress", pipe.events().last { it.first == "purchase_failed" }.second.getString("reason"))
        assertEquals(1, pipe.count("purchase_started"))
    }

    /**
     * SPEC-497 I4 R8 M1 — the caller is cancelled while `purchase()` waits for the billing connection,
     * BEFORE the continuation (and its `invokeOnCancellation`) exists. R40: the in-flight flag is cleared
     * on every exit, so the next `purchase()` launches. NEGATIVE CONTROL: without the `catch` around the
     * body of `purchase()`, `purchaseInFlight` stays true and the second call fails `purchase_in_progress`
     * with no launch — this test fails.
     */
    @Test fun `a caller cancelled during the connection wait does not block the next purchase`() {
        val s = scenario("""{
          "billing_setup_code":"SERVICE_UNAVAILABLE",
          "product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}}
        }""")
        s.fake.autoDeliver = false
        var first: PurchaseResult? = null
        val job = kotlinx.coroutines.MainScope().launch { first = s.mgr.purchase(activity(), "lifetime_unlock") }
        s.idle()
        assertTrue("the first purchase waits for the connection", s.mgr.purchaseInFlight)
        assertEquals(0, s.mgr.launchCount)

        job.cancel()
        s.idle()
        assertEquals(null, first)
        assertEquals("the cancelled caller cleared its scope", false, s.mgr.purchaseInFlight)
        assertEquals(null, s.mgr.pendingProductId)

        // Play comes back; the second purchase connects and launches.
        s.fake.setupCode = com.android.billingclient.api.BillingClient.BillingResponseCode.OK
        shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS)
        var second: PurchaseResult? = null
        s.mgr.scope.launch { second = s.mgr.purchase(activity(), "lifetime_unlock") }
        s.idle()
        assertEquals("the second purchase launched", 1, s.mgr.launchCount)
        assertEquals("it waits for Play (no purchase_in_progress)", null, second)
        assertEquals(0, pipe.events().count { it.first == "purchase_failed" && it.second.optString("reason") == "purchase_in_progress" })
        assertEquals("lifetime_unlock", s.mgr.pendingProductId)
    }

    /**
     * SPEC-497 I4 R8 M1 — the same, with the caller cancelled while `purchase()` waits for the SUBS / INAPP
     * product query. NEGATIVE CONTROL: without the `catch` in `purchase()`, the second call fails
     * `purchase_in_progress` — this test fails.
     */
    @Test fun `a caller cancelled during the product query does not block the next purchase`() {
        val s = scenario("""{"product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}}}""")
        s.fake.autoDeliver = false
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        s.fake.productQueryGate = gate
        var first: PurchaseResult? = null
        val job = kotlinx.coroutines.MainScope().launch { first = s.mgr.purchase(activity(), "lifetime_unlock") }
        s.idle()
        assertTrue("the first purchase is parked in the product query", s.fake.productQueriesParked > 0)
        assertTrue(s.mgr.purchaseInFlight)

        job.cancel()
        s.idle()
        gate.complete(Unit)
        s.idle()
        assertEquals(null, first)
        assertEquals(false, s.mgr.purchaseInFlight)
        assertEquals(0, s.mgr.launchCount)

        var second: PurchaseResult? = null
        s.mgr.scope.launch { second = s.mgr.purchase(activity(), "lifetime_unlock") }
        s.idle()
        assertEquals("the second purchase launched", 1, s.mgr.launchCount)
        assertEquals(null, second)
        assertEquals(0, pipe.events().count { it.first == "purchase_failed" && it.second.optString("reason") == "purchase_in_progress" })
    }

    /**
     * SPEC-497 I4 R8 M1 — a purchase that LOST its scope while parked (here: resolved from outside, as
     * `destroy()` does) never touches the scope of the newer purchase that took it over: it stops without
     * launching, and the newer purchase keeps its flag, its product and its launch.
     */
    @Test fun `a purchase that lost its scope while waiting leaves the newer purchase alone`() {
        val s = scenario("""{"product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"},
          "coins":{"price_micros":990000,"currency":"USD","product_type":"inapp"}}}""")
        s.fake.autoDeliver = false
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        s.fake.productQueryGate = gate
        var first: PurchaseResult? = null
        s.mgr.scope.launch { first = s.mgr.purchase(activity(), "lifetime_unlock") }
        s.idle()
        s.mgr.resumePurchase(PurchaseResult.Failed("shutdown")) // clears the scope; no continuation yet
        assertEquals(false, s.mgr.purchaseInFlight)

        var second: PurchaseResult? = null
        s.mgr.scope.launch { second = s.mgr.purchase(activity(), "coins") }
        s.idle()
        gate.complete(Unit)
        s.idle()
        assertEquals(PurchaseResult.Failed("shutdown"), first)
        assertEquals("only the newer purchase launched", 1, s.mgr.launchCount)
        assertEquals(null, second)
        assertTrue("the newer purchase still owns the scope", s.mgr.purchaseInFlight)
        assertEquals("coins", s.mgr.pendingProductId)
    }

    /**
     * SPEC-497 R9 — purchase A answers ITEM_ALREADY_OWNED and its handler parks in `run()` (the owned
     * query). A's caller is cancelled, purchase B starts and launches, then A's handler finishes with an
     * `Owned` outcome. B must not be resolved, relaunched or cleared by A's handler, and A's outcome is not
     * reported against B's scope. NEGATIVE CONTROL: without the `ownsScope(owner)` check after `run()` in
     * `handleItemAlreadyOwned`, A's handler resolves B `Purchased` with A's product and clears its scope —
     * this test fails.
     */
    @Test fun `an ITEM_ALREADY_OWNED handler of a cancelled purchase leaves the newer purchase alone`() {
        val s = scenario("""{
          "product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"},
            "coins":{"price_micros":990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"lifetime_unlock","token":"tok_o","order_id":"GPA.o","acknowledged":true,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "reported_tokens":[{"token":"tok_o","pending_finalize":null}],
          "cached_entitlements":[{"product_id":"lifetime_unlock","status":"active","store":"google_play","expires_at":null,"product_type":"inapp"}],
          "play_response_codes":["ITEM_ALREADY_OWNED"]
        }""")
        s.fake.autoDeliver = false
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        s.fake.queryGate = gate
        var first: PurchaseResult? = null
        val job = kotlinx.coroutines.MainScope().launch { first = s.mgr.purchase(activity(), "lifetime_unlock") }
        s.idle()
        assertEquals(1, s.mgr.launchCount)
        assertTrue("A's ITEM_ALREADY_OWNED handler is parked in run()", s.fake.queriesParked > 0)

        job.cancel()
        s.idle()
        assertEquals(false, s.mgr.purchaseInFlight)

        var second: PurchaseResult? = null
        s.mgr.scope.launch { second = s.mgr.purchase(activity(), "coins") }
        s.idle()
        assertEquals("B launched", 2, s.mgr.launchCount)

        gate.complete(Unit)
        s.idle(200)
        assertEquals(null, first)
        assertEquals("B is not resolved by A's handler", null, second)
        assertEquals("B is not relaunched", 2, s.mgr.launchCount)
        assertTrue("B still owns the scope", s.mgr.purchaseInFlight)
        assertEquals("coins", s.mgr.pendingProductId)
        assertEquals(0, pipe.count("purchase_restored"))
    }

    /**
     * SPEC-497 R9 — A's caller is cancelled with its sheet still up; purchase B is waiting for its product
     * query (no continuation yet) when Play answers A's sheet with USER_CANCELED, then ERROR. Neither may
     * resolve or clear B's scope; each is reported once, unattributed. NEGATIVE CONTROL: without the
     * `hasLiveContinuation()` guard on the listener's non-OK branches, the late answer clears B's scope and
     * B stops `Failed("shutdown")` without launching — this test fails.
     */
    @Test fun `a late non-OK Play answer for a cancelled purchase leaves the newer purchase alone`() {
        val s = scenario("""{"product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"},
          "coins":{"price_micros":990000,"currency":"USD","product_type":"inapp"}}}""")
        s.fake.autoDeliver = false
        s.mgr.currentPaywallId = "pw_a"
        var first: PurchaseResult? = null
        val job = kotlinx.coroutines.MainScope().launch { first = s.mgr.purchase(activity(), "lifetime_unlock") }
        s.idle()
        assertEquals(1, s.mgr.launchCount)
        job.cancel()
        s.idle()

        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        s.fake.productQueryGate = gate
        s.mgr.currentPaywallId = "pw_b"
        var second: PurchaseResult? = null
        s.mgr.scope.launch { second = s.mgr.purchase(activity(), "coins") }
        s.idle()
        s.fake.answer(com.android.billingclient.api.BillingClient.BillingResponseCode.USER_CANCELED)
        s.fake.answer(com.android.billingclient.api.BillingClient.BillingResponseCode.ERROR)
        s.idle()
        assertTrue("B still owns the scope", s.mgr.purchaseInFlight)

        gate.complete(Unit)
        s.idle()
        assertEquals(null, first)
        assertEquals("B launched and waits for Play", null, second)
        assertEquals(2, s.mgr.launchCount)
        assertEquals("coins", s.mgr.pendingProductId)
        val canceled = pipe.events().single { it.first == "purchase_canceled" }.second
        assertEquals("", canceled.getString("paywall_id"))
        assertTrue(!canceled.has("product_id"))
        val failed = pipe.events().single { it.first == "purchase_failed" }.second
        assertEquals("billing_error", failed.getString("reason"))
        assertEquals("", failed.getString("paywall_id"))
        assertTrue(!failed.has("product_id"))
    }

    /**
     * SPEC-497 I4 m2 — A's caller is cancelled with its sheet up and Play then answers ITEM_ALREADY_OWNED
     * through the listener, with no live caller. The owned, unreported consumable Play refers to is rescued
     * right away (reported once, consumed, queued) — not left until the next ON_START. NEGATIVE CONTROL:
     * without `rescueOwnedPurchases()` in the listener's no-live-caller branch, nothing is reported or
     * consumed — this test fails.
     */
    @Test fun `a listener ITEM_ALREADY_OWNED with no live caller rescues the owned consumable at once`() {
        val s = scenario("""{
          "product_details":{"coins_100":{"price_micros":990000,"currency":"USD","product_type":"inapp"}},
          "verify_replies":{"tok_r":{"status":200,"consume":true,"expires_at":null,"is_trial":false}}
        }""")
        s.fake.autoDeliver = false
        var first: PurchaseResult? = null
        val job = kotlinx.coroutines.MainScope().launch { first = s.mgr.purchase(activity(), "coins_100") }
        s.idle()
        assertEquals(1, s.mgr.launchCount)
        job.cancel()
        s.idle()
        assertEquals(false, s.mgr.hasLiveContinuation())

        // Play holds an owned, unreported unit of the product and answers the dead sheet ITEM_ALREADY_OWNED.
        s.fake.addOwned("inapp", FakeBillingClient.purchase("coins_100", "tok_r"))
        s.fake.answer(com.android.billingclient.api.BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED)
        s.idle(200)
        assertEquals(null, first)
        assertEquals("rescued without waiting for ON_START", 1, pipe.count("purchase_completed"))
        assertEquals(listOf("tok_r"), s.fake.consumeCalls)
        assertEquals(listOf("tok_r"), s.queueTokens())
        assertEquals("never relaunched", 1, s.mgr.launchCount)
    }

    /**
     * SPEC-497 I4 m2 — `handleItemAlreadyOwned` whose owner lost the scope before it ran (the pre-`run()`
     * owner check) resolves nothing but rescues the owned consumable. NEGATIVE CONTROL: without
     * `rescueOwnedPurchases()` in that early return, nothing is reported or consumed — this test fails.
     */
    @Test fun `an ITEM_ALREADY_OWNED handler that lost its scope before run rescues the owned consumable`() {
        val s = scenario("""{
          "product_details":{"coins_100":{"price_micros":990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"coins_100","token":"tok_h","order_id":"GPA.h","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "verify_replies":{"tok_h":{"status":200,"consume":true,"expires_at":null,"is_trial":false}}
        }""")
        assertEquals(false, s.mgr.ownsScope(42L))
        s.mgr.scope.launch { s.mgr.completion.handleItemAlreadyOwned(42L) }
        s.idle(200)
        assertEquals(1, pipe.count("purchase_completed"))
        assertEquals(listOf("tok_h"), s.fake.consumeCalls)
        assertEquals(0, pipe.count("purchase_restored"))
        assertEquals(0, pipe.count("purchase_failed"))
        assertEquals(0, s.mgr.launchCount)
    }

    /**
     * SPEC-497 R9 — a cancelled `awaitConnectedClient` waiter takes its callback with it. NEGATIVE CONTROL:
     * without `invokeOnCancellation` in `awaitConnectedClient`, the count stays 1 — this test fails.
     */
    @Test fun `a cancelled connection waiter is removed`() {
        val s = scenario("""{"billing_setup_code":"SERVICE_UNAVAILABLE"}""")
        val job = kotlinx.coroutines.MainScope().launch { s.mgr.connectionManager.awaitConnectedClient() }
        s.idle()
        assertEquals(1, s.mgr.connectionManager.pendingWaiterCount)
        job.cancel()
        s.idle()
        assertEquals(0, s.mgr.connectionManager.pendingWaiterCount)
    }

    @Test fun `destroy during a purchase resolves Failed shutdown`() {
        val s = scenario("""{"product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}}}""")
        s.fake.autoDeliver = false
        var result: PurchaseResult? = null
        // The caller's own scope: destroy() cancels the manager's.
        kotlinx.coroutines.MainScope().launch { result = s.mgr.purchase(activity(), "lifetime_unlock") }
        s.idle()
        s.mgr.destroy()
        s.idle()
        assertEquals(PurchaseResult.Failed("shutdown"), result)
    }

    @Test fun `ITEM_ALREADY_OWNED with a PENDING owned purchase resolves Pending with purchase_pending`() {
        val s = scenario("""{
          "product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"lifetime_unlock","token":"tok_p","order_id":"GPA.p","acknowledged":false,"product_type":"inapp","purchase_state":"PENDING"}],
          "play_response_codes":["ITEM_ALREADY_OWNED"]
        }""")
        val r = s.drive { s.mgr.purchase(activity(), "lifetime_unlock") }
        assertEquals(PurchaseResult.Pending, r.getOrNull())
        assertEquals(1, pipe.count("purchase_pending"))
    }

    @Test fun `ITEM_ALREADY_OWNED with no cache entry and a failed verify takes the non-consumable branch`() {
        val s = scenario("""{
          "product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"lifetime_unlock","token":"tok_u","order_id":"GPA.u","acknowledged":true,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "reported_tokens":[{"token":"tok_u","pending_finalize":null}],
          "play_response_codes":["ITEM_ALREADY_OWNED"],
          "verify_replies":{"tok_u":{"status":503,"consume":false}}
        }""")
        val r = s.drive { s.mgr.purchase(activity(), "lifetime_unlock") }
        val purchased = r.getOrNull() as PurchaseResult.Purchased
        assertEquals("lifetime_unlock", purchased.entitlement.productId)
        assertEquals(1, pipe.count("purchase_restored"))
        assertEquals(emptyList<String>(), s.fake.consumeCalls)
        assertEquals(0, pipe.count("purchase_completed"))
    }

    @Test fun `a consume retry that sees ITEM_NOT_OWNED counts as done`() {
        val s = scenario("""{
          "owned_purchases":[{"product_id":"coins_100","token":"tok_c","order_id":"GPA.c","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "reported_tokens":[{"token":"tok_c","pending_finalize":"consume"}]
        }""")
        s.fake.consumeNotOwned += "tok_c"
        s.drive { s.mgr.reconcileSubscriptionState() }
        assertEquals(listOf(mapOf("token" to "tok_c", "pending_finalize" to null)), s.reportedState())
    }

    @Test fun `no delegate - a rescued consumable is reported, consumed and queued, then delivered once on setDelegate`() {
        val s = scenario("""{
          "product_details":{"coins_100":{"price_micros":990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"coins_100","token":"tok_q","order_id":"GPA.q","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "verify_replies":{"tok_q":{"status":200,"consume":true,"expires_at":null,"is_trial":false}}
        }""")
        s.drive { s.mgr.reconcileSubscriptionState() }
        assertEquals(1, pipe.count("purchase_completed"))
        assertEquals(listOf("tok_q"), s.fake.consumeCalls)
        assertEquals(listOf("tok_q"), s.queueTokens())
        assertEquals(0, completions())
        s.setDelegate(); s.idle()
        assertEquals(1, completions())
        assertEquals(emptyList<String>(), s.queueTokens())
        s.setDelegate(); s.idle()
        assertEquals(1, completions())
    }

    @Test fun `in-flight purchase and IDENTIFY_REFRESH first - restore never gets the token, purchase resolves with paywall_id`() {
        val s = scenario("""{
          "product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"lifetime_unlock","token":"tok_i","order_id":"GPA.i","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "listener_purchase_token":"tok_i",
          "verify_replies":{"tok_i":{"status":200,"consume":false,"expires_at":null,"is_trial":false}}
        }""")
        s.fake.autoDeliver = false
        s.mgr.currentPaywallId = "pw_2"
        var result: PurchaseResult? = null
        s.mgr.scope.launch { result = s.mgr.purchase(activity(), "lifetime_unlock") }
        s.idle()
        s.fake.addOwned("inapp", s.fake.listenerPurchase!!)       // Play knows it; the listener has not fired yet
        s.drive { s.mgr.refreshEntitlementCache() }                  // takes the lock first: skips the token
        assertTrue(s.restoreCalls.none { "tok_i" in it })
        s.fake.deliver(s.fake.listenerPurchase!!, "inapp")
        s.idle(200)
        assertTrue(result is PurchaseResult.Purchased)
        assertEquals("pw_2", pipe.props("purchase_completed").getString("paywall_id"))
    }

    @Test fun `a verify timeout on the purchase path resolves Failed networkError and emits purchase_failed`() {
        val s = scenario("""{
          "product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}},
          "owned_purchases":[{"product_id":"lifetime_unlock","token":"tok_t","order_id":"GPA.t","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
          "listener_purchase_token":"tok_t"
        }""")
        // A verify that never answers.
        val hanging = ReceiptVerifier(null, "sdk", post = { _, _ -> kotlinx.coroutines.awaitCancellation() }, currentUserId = { null })
        val field = NativeBillingManager::class.java.getDeclaredField("receiptVerifier").apply { isAccessible = true }
        field.set(s.mgr, hanging)
        var result: PurchaseResult? = null
        s.mgr.scope.launch { result = s.mgr.purchase(activity(), "lifetime_unlock") }
        s.idle()
        shadowOf(Looper.getMainLooper()).idleFor(31, TimeUnit.SECONDS)
        s.idle()
        val failed = result as PurchaseResult.Failed
        assertEquals("networkError", failed.errorType)
        assertEquals("networkError", pipe.props("purchase_failed").getString("error_type"))
        assertEquals(emptyList<String>(), s.fake.acknowledgeCalls)
    }

    @Test fun `setStringsSync - a fresh SharedPreferences read sees every key after the call returns`() {
        val storage = ai.appdna.sdk.storage.LocalStorage(ctx)
        storage.setStringsSync(mapOf(PurchaseLedger.KEY_REPORTED to "[1]", PurchaseLedger.KEY_DELIVERIES to "[2]"))
        val prefs = ctx.getSharedPreferences("ai.appdna.sdk", Context.MODE_PRIVATE)
        assertEquals("[1]", prefs.getString(PurchaseLedger.KEY_REPORTED, null))
        assertEquals("[2]", prefs.getString(PurchaseLedger.KEY_DELIVERIES, null))
        pipe = BillingTestPipeline(ctx, "noop")
    }
}
