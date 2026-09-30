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
