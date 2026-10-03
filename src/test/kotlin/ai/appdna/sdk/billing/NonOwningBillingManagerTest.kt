package ai.appdna.sdk.billing

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.launch

/**
 * With `ownsTransactions == false` the manager makes NO server call and NO store
 * write anywhere, builds the entitlement cache locally, and gates only the lifecycle
 * emit on `emitsLifecycleEvents` (rule 4) — the snapshot is saved either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NonOwningBillingManagerTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var sc: BillingScenario? = null
    private var pipe: BillingTestPipeline? = null

    @After fun tearDown() { sc?.teardown(); pipe?.close() }

    private val unackedSub = JSONObject("""{
        "owned_purchases":[{"product_id":"monthly_sub","token":"tok_s","order_id":"GPA.1..0","acknowledged":false,"product_type":"subs","purchase_state":"PURCHASED"}],
        "verify_replies":{"tok_s":{"status":200,"consume":false,"expires_at":"2026-10-30T00:00:00Z","is_trial":false}}
    }""")

    private fun scenario(owns: Boolean, emits: Boolean = true, setup: JSONObject = unackedSub): BillingScenario {
        pipe = BillingTestPipeline(ctx, "nonowning")
        return BillingScenario(ctx, setup, ownsTransactions = owns, emitsLifecycleEvents = emits).also { sc = it }
    }

    @Test fun `reconcile never verifies, acknowledges or consumes and builds the local entitlement`() {
        val s = scenario(owns = false)
        s.drive { s.mgr.reconcileSubscriptionState() }
        assertEquals(emptyList<String>(), s.verifyCalls)
        assertEquals(emptyList<String>(), s.fake.acknowledgeCalls)
        assertEquals(emptyList<String>(), s.fake.consumeCalls)
        val e = s.cache.getAll().single()
        assertEquals(Entitlement("monthly_sub", "google_play", "active", null, false, null), e)
        assertEquals("subs", s.cache.productTypeOf("monthly_sub"))
    }

    @Test fun `identify refresh sends nothing to restore and rebuilds the same local cache`() {
        val s = scenario(owns = false)
        s.drive { s.mgr.refreshEntitlementCache() }
        assertEquals(emptyList<List<String>>(), s.restoreCalls)
        assertEquals(listOf("monthly_sub"), s.entitlementIds())
    }

    @Test fun `purchase throws ProviderNotAvailable with no launch and one purchase_failed`() {
        val s = scenario(owns = false)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val r = s.drive { s.mgr.purchase(activity, "monthly_sub") }
        assertTrue(r.exceptionOrNull() is BillingError.ProviderNotAvailable)
        // R9 — the purchase refusal carries the same message as the restore refusal (and iOS).
        assertEquals("RevenueCat: purchases are made by RevenueCat in your app", r.exceptionOrNull()?.message)
        assertEquals("RevenueCat: purchases are made by RevenueCat in your app", pipe!!.props("purchase_failed").getString("error"))
        assertEquals(0, s.fake.launchCount)
        assertEquals(listOf("purchase_failed"), pipe!!.names())
        assertEquals("providerNotAvailable", pipe!!.props("purchase_failed").getString("error_type"))
        assertTrue(!pipe!!.props("purchase_failed").has("paywall_id"))
    }

    @Test fun `restore throws ProviderNotAvailable and tracks exactly one purchase_restore_failed`() {
        val s = scenario(owns = false)
        val r = s.drive { s.mgr.restorePurchases() }
        assertTrue(r.exceptionOrNull() is BillingError.ProviderNotAvailable)
        // The iOS wording, on both platforms.
        assertEquals("RevenueCat: purchases are made by RevenueCat in your app", r.exceptionOrNull()?.message)
        assertEquals(1, pipe!!.count("purchase_restore_failed"))
    }

    @Test fun `owning regression guard - one verify and one acknowledge`() {
        val s = scenario(owns = true)
        s.drive { s.mgr.reconcileSubscriptionState() }
        assertEquals(listOf("tok_s"), s.verifyCalls)
        assertEquals(listOf("tok_s"), s.fake.acknowledgeCalls)
    }

    private fun renewalDiff(s: BillingScenario) {
        val prev = NativeBillingManager.SubSnapshot("monthly_sub", 1L, true, true, "GPA.1..0", "GPA.1")
        s.mgr.saveSnapshot(mapOf("monthly_sub" to prev))
        s.fake.owned.values.forEach { it.clear() }
        s.fake.addOwned("subs", FakeBillingClient.purchase("monthly_sub", "tok_s", "GPA.1..1", acknowledged = true, purchaseTime = 2L, autoRenewing = true))
        s.drive { s.mgr.reconcileSubscriptionState() }
    }

    @Test fun `adapty shape (not owning, emits) - a renewal is emitted with zero verify, ack or consume`() {
        val s = scenario(owns = false, emits = true)
        renewalDiff(s)
        assertEquals(1, pipe!!.count("subscription_renewed"))
        assertEquals(emptyList<String>(), s.verifyCalls + s.fake.acknowledgeCalls + s.fake.consumeCalls)
    }

    @Test fun `revenueCat shape (not owning, no emit) - nothing is emitted and the snapshot is still saved`() {
        val s = scenario(owns = false, emits = false)
        renewalDiff(s)
        assertEquals(0, pipe!!.count("subscription_renewed"))
        val saved = s.mgr.loadLastSnapshot().getValue("monthly_sub")
        assertEquals(2L, saved.purchaseTime)
        assertEquals("GPA.1..1", saved.transactionId)
    }

    /**
     * `subscription_renewed` carries the renewal's `price` / `currency` (the names `purchase_completed`
     * uses). The price cache is empty on a cold start, so the renewed product is queried first.
     * NEGATIVE CONTROL: without the price block in `diffAndEmit` (or without `warmRenewalPrices`), the
     * event has no `price` — this test fails.
     */
    @Test fun `a renewal carries price and currency - the price is looked up on a cold cache`() {
        val setup = JSONObject(unackedSub.toString()).put(
            "product_details",
            JSONObject("""{"monthly_sub":{"price_micros":9990000,"currency":"EUR","product_type":"subs"}}"""),
        )
        val s = scenario(owns = false, emits = true, setup = setup)
        assertEquals(null, s.mgr.priceResolver.cachedPriceInfo("monthly_sub"))
        renewalDiff(s)
        val props = pipe!!.props("subscription_renewed")
        assertEquals(9.99, props.getDouble("price"), 1e-9)
        assertEquals("EUR", props.getString("currency"))
    }

    @Test fun `a renewal whose price cannot be found omits price and currency`() {
        val s = scenario(owns = false, emits = true)
        renewalDiff(s)
        val props = pipe!!.props("subscription_renewed")
        assertTrue(!props.has("price"))
        assertTrue(!props.has("currency"))
    }

    /**
     * Two reconciles at once (a foreground entry racing another pass), both parked on the renewal-price
     * lookup. NEGATIVE CONTROL: without the snapshot mutex both loaded the old snapshot before the lookup
     * and both emitted the renewal — two `subscription_renewed` for one renewal.
     */
    @Test fun `two concurrent reconciles emit one subscription_renewed`() {
        val setup = JSONObject(unackedSub.toString()).put(
            "product_details",
            JSONObject("""{"monthly_sub":{"price_micros":9990000,"currency":"EUR","product_type":"subs"}}"""),
        )
        val s = scenario(owns = false, emits = true, setup = setup)
        val prev = NativeBillingManager.SubSnapshot("monthly_sub", 1L, true, true, "GPA.1..0", "GPA.1")
        s.mgr.saveSnapshot(mapOf("monthly_sub" to prev))
        s.fake.owned.values.forEach { it.clear() }
        s.fake.addOwned("subs", FakeBillingClient.purchase("monthly_sub", "tok_s", "GPA.1..1", acknowledged = true, purchaseTime = 2L, autoRenewing = true))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        s.fake.productQueryGate = gate
        s.mgr.scope.launch { s.mgr.reconcileSubscriptionState() }
        s.mgr.scope.launch { s.mgr.reconcileSubscriptionState() }
        s.idle()
        gate.complete(Unit)
        s.idle()
        assertEquals(1, pipe!!.count("subscription_renewed"))
        assertEquals(9.99, pipe!!.props("subscription_renewed").getDouble("price"), 1e-9)
    }

    /**
     * An OUT-OF-ORDER apply. Reconcile 1 reads Play before the renewal (GPA.1..0) and its answer
     * is held; Play then renews (GPA.1..1) and reconcile 2 is started; reconcile 2's read is answered first,
     * then reconcile 1's.
     * NEGATIVE CONTROL: with Play read OUTSIDE the snapshot mutex (and `isRenewal` not requiring the purchase
     * time to hold), reconcile 2 applied the renewal, then reconcile 1 diffed the OLDER purchase against it:
     * the order id differed, so it emitted a second, backwards `subscription_renewed` and saved the older
     * snapshot (two events, the saved order id GPA.1..0).
     */
    @Test fun `an older Play read applied after a newer one emits no backwards renewal and does not regress the snapshot`() {
        val s = scenario(owns = false, emits = true)
        val first = FakeBillingClient.purchase("monthly_sub", "tok_s", "GPA.1..0", acknowledged = true, purchaseTime = 1L, autoRenewing = true)
        s.mgr.saveSnapshot(mapOf("monthly_sub" to NativeBillingManager.SubSnapshot("monthly_sub", 1L, true, true, "GPA.1..0", "GPA.1")))
        s.fake.owned.values.forEach { it.clear() }
        s.fake.addOwned("subs", first)

        val g1 = kotlinx.coroutines.CompletableDeferred<Unit>()
        val g2 = kotlinx.coroutines.CompletableDeferred<Unit>()
        s.fake.subsQueryGates.addLast(g1)
        s.fake.subsQueryGates.addLast(g2)
        var done1 = false; var done2 = false
        s.mgr.scope.launch { s.mgr.reconcileSubscriptionState(); done1 = true }
        s.idle()
        assertEquals("reconcile 1 is parked in its SUBS read", 1, s.fake.subsQueriesStarted)

        // Play renews; reconcile 2 starts.
        s.fake.owned.values.forEach { it.clear() }
        s.fake.addOwned("subs", FakeBillingClient.purchase("monthly_sub", "tok_s", "GPA.1..1", acknowledged = true, purchaseTime = 2L, autoRenewing = true))
        s.mgr.scope.launch { s.mgr.reconcileSubscriptionState(); done2 = true }
        s.idle()

        g2.complete(Unit) // the newer read answers first
        s.idle()
        g1.complete(Unit)
        s.idle(200)
        assertTrue(done1 && done2)
        assertEquals("one renewal, never a backwards one", 1, pipe!!.count("subscription_renewed"))
        assertEquals("GPA.1..1", pipe!!.props("subscription_renewed").getString("transaction_id"))
        val saved = s.mgr.loadLastSnapshot().getValue("monthly_sub")
        assertEquals("the snapshot never goes back", "GPA.1..1", saved.transactionId)
        assertEquals(2L, saved.purchaseTime)
    }

    /** The pure rule: an older purchase time is never a renewal, even with a different order id. */
    @Test fun `isRenewal requires the purchase time not to go back`() {
        val s = scenario(owns = false, emits = true)
        val newer = NativeBillingManager.SubSnapshot("m", 2L, true, true, "GPA.1..1", "GPA.1")
        val older = NativeBillingManager.SubSnapshot("m", 1L, true, true, "GPA.1..0", "GPA.1")
        assertTrue(s.mgr.isRenewal(older, newer))
        assertTrue(!s.mgr.isRenewal(newer, older))
        assertTrue("same time, new order id (R47)", s.mgr.isRenewal(older, NativeBillingManager.SubSnapshot("m", 1L, true, true, "GPA.1..1", "GPA.1")))
        s.mgr.diffAndEmit(mapOf("m" to newer), mapOf("m" to older))
        assertEquals(0, pipe!!.count("subscription_renewed"))
    }
}
