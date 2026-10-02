package ai.appdna.sdk.billing

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Environment
import ai.appdna.sdk.events.ClientSeqCounter
import ai.appdna.sdk.events.DroppedEventsCounter
import ai.appdna.sdk.events.EventQueue
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.integrations.AdaptyBridge
import ai.appdna.sdk.integrations.RevenueCatBridge
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.storage.EventDatabase
import ai.appdna.sdk.storage.LocalStorage
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 🔴 `subscription_started` is METERED and NO SDK EMITTED IT.
 *
 * `BigQueryBillingService` bills MTPU over
 * `event_name IN ('purchase_completed', 'subscription_started', 'subscription_renewed')`. iOS, Android,
 * Flutter and RN all had ZERO emit sites for the middle one — the only rows in production carrying that
 * name were seeded demo data (sdk_version 1.0.0 / 1.0.3, versions that never shipped). The MTPU TOTAL
 * survived (it is COUNT(DISTINCT user) over the union, and a new subscriber already lands via
 * `purchase_completed`), so nothing alerted — but the subscription funnel was a fiction: nothing
 * downstream could distinguish a NEW SUBSCRIPTION from a one-off / consumable / lifetime purchase.
 *
 * These tests drive the REAL emit chain — real [NativeBillingManager] / real [RevenueCatBridge] / real
 * [AdaptyBridge] → real `AppDNA.track` → real EventTracker → real EventQueue → real EventDatabase — and
 * read back the envelopes that came out. Same pipeline harness as [PurchaseEventsEmittedOnceTest],
 * because a billing event that no test can SEE is exactly how this shipped.
 *
 * The rule under test, on every provider:
 *   - subscription purchase → `purchase_completed` AND `subscription_started`, once each;
 *   - one-off purchase      → `purchase_completed` only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SubscriptionStartedEventTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: EventDatabase
    private lateinit var tracker: EventTracker

    /** A REAL event pipeline: EventTracker → EventQueue → EventDatabase. `batchSize = 0` means the
     *  flush threshold is never tripped, so nothing is uploaded and the envelopes stay readable. */
    private fun installRealPipeline() {
        val storage = LocalStorage(ctx)
        val identity = ai.appdna.sdk.IdentityManager(storage)
        db = EventDatabase(ctx, 10_000, "subscription_started_test.db")
        ClientSeqCounter.init(ctx)
        DroppedEventsCounter.init(ctx)
        db.clearAll()
        tracker = EventTracker(identity, "1.0", "sandbox")
        tracker.setEventQueue(
            EventQueue(
                apiClient = ApiClient("adn_test_subscription_started", Environment.PRODUCTION),
                eventDatabase = db,
                connectivityMonitor = null,
                batchSizeCap = 0,
                flushInterval = Long.MAX_VALUE,
            ),
        )
        // The billing layer emits through the STATIC AppDNA.track — route it at our tracker.
        AppDNA.installEventTrackerForTest(tracker)
    }

    private fun emittedEvents(): List<Pair<String, JSONObject?>> =
        db.loadAll().map { JSONObject(it) }.map { it.getString("event_name") to it.optJSONObject("properties") }

    private fun names(): List<String> = emittedEvents().map { it.first }

    private fun countOf(name: String) = names().count { it == name }

    private fun propsOf(name: String): JSONObject = emittedEvents().first { it.first == name }.second!!

    private fun manager(): NativeBillingManager = NativeBillingManager(
        context = ctx,
        receiptVerifier = ReceiptVerifier(ApiClient("adn_test_subscription_started", Environment.PRODUCTION)),
        entitlementCache = EntitlementCache(ctx),
        storage = LocalStorage(ctx),
    )

    /** The entitlement the server hands back for an AUTO-RENEWING product: it EXPIRES. */
    private fun subscriptionEntitlement(productId: String = "pro_yearly") = Entitlement(
        productId = productId,
        store = "play",
        status = "active",
        expiresAt = "2026-08-13T00:00:00Z",
        isTrial = false,
        offerType = null,
    )

    /** The entitlement for a one-off / lifetime unlock: it NEVER expires. */
    private fun oneOffEntitlement(productId: String = "lifetime_unlock") = Entitlement(
        productId = productId,
        store = "play",
        status = "active",
        expiresAt = null,
        isTrial = false,
        offerType = null,
    )

    @After
    fun tearDown() {
        AppDNA.installEventTrackerForTest(null)
        AppDNA.billing.manager = null
        db.clearAll()
    }

    // ---------------------------------------------------------------- the discriminator

    /**
     * A SUBSCRIPTION expires; a one-off does not. This is the whole rule, and it is the same one iOS
     * encodes as `PurchaseResult.isSubscription` (`Product.subscription != nil`).
     */
    @Test
    fun `an entitlement with an expiry is a subscription, one without is not`() {
        installRealPipeline()
        val mgr = manager()
        assertTrue(mgr.isSubscriptionPurchase(subscriptionEntitlement()))
        assertFalse(mgr.isSubscriptionPurchase(oneOffEntitlement()))
    }

    // ---------------------------------------------------------------- native Play path

    @Test
    fun `a native subscription purchase emits purchase_completed AND subscription_started, once each`() {
        installRealPipeline()
        val mgr = manager()
        val ent = subscriptionEntitlement()

        mgr.trackPurchaseCompleted(
            productId = ent.productId,
            isTrial = ent.isTrial,
            transactionId = "GPA.1234",
            isSubscription = mgr.isSubscriptionPurchase(ent),
        )

        assertEquals(
            "a subscription purchase must emit BOTH — got ${names()}",
            listOf("purchase_completed", "subscription_started"),
            names(),
        )
        assertEquals(1, countOf("purchase_completed"))
        assertEquals(1, countOf("subscription_started"))
    }

    @Test
    fun `a native one-off purchase emits ONLY purchase_completed`() {
        installRealPipeline()
        val mgr = manager()
        val ent = oneOffEntitlement()

        mgr.trackPurchaseCompleted(
            productId = ent.productId,
            isTrial = ent.isTrial,
            transactionId = "GPA.9999",
            isSubscription = mgr.isSubscriptionPurchase(ent),
        )

        assertEquals(
            "a one-off purchase must NOT emit subscription_started — got ${names()}",
            listOf("purchase_completed"),
            names(),
        )
        assertEquals(0, countOf("subscription_started"))
    }

    /** Same envelope, so a funnel joining the two events never has to special-case one of them. */
    @Test
    fun `subscription_started carries the same property envelope as purchase_completed`() {
        installRealPipeline()
        val mgr = manager()
        mgr.currentPaywallMetadata = mapOf("promo_code" to "LAUNCH50")
        val ent = subscriptionEntitlement()

        mgr.trackPurchaseCompleted(
            productId = ent.productId,
            isTrial = true,
            transactionId = "GPA.1234",
            isSubscription = mgr.isSubscriptionPurchase(ent),
        )

        val completed = propsOf("purchase_completed")
        val started = propsOf("subscription_started")
        assertEquals(completed.keys().asSequence().toSet(), started.keys().asSequence().toSet())
        assertEquals("pro_yearly", started.getString("product_id"))
        assertEquals("google_play", started.getString("provider"))
        assertEquals("GPA.1234", started.getString("transaction_id"))
        assertTrue(started.getBoolean("is_trial"))
        assertEquals("LAUNCH50", started.getString("promo_code"))
    }

    // ---------------------------------------------------------------- RevenueCat

    @Test
    fun `a RevenueCat subscription purchase emits purchase_completed AND subscription_started`() {
        installRealPipeline()
        AppDNA.billing.manager = manager()

        RevenueCatBridge().forwardPurchaseSuccess("pro_yearly", "tx-rc-1", isSubscription = true)

        assertEquals(listOf("purchase_completed", "subscription_started"), names())
        assertEquals("revenuecat", propsOf("subscription_started").getString("provider"))
        assertEquals("pro_yearly", propsOf("subscription_started").getString("product_id"))
    }

    @Test
    fun `a RevenueCat one-off purchase emits ONLY purchase_completed`() {
        installRealPipeline()
        AppDNA.billing.manager = manager()

        RevenueCatBridge().forwardPurchaseSuccess("coins_100", "tx-rc-2", isSubscription = false)

        assertEquals(listOf("purchase_completed"), names())
    }

    /**
     * The host may not pass the flag (the pre-existing 2-arg overload). The SDK then infers from the
     * entitlement the server verified — a subscription EXPIRES — rather than silently dropping the
     * metered event.
     */
    @Test
    fun `a RevenueCat purchase with no explicit flag infers subscription from the verified entitlement`() {
        installRealPipeline()
        val cache = EntitlementCache(ctx)
        cache.update(subscriptionEntitlement(), "subs")
        AppDNA.billing.manager = NativeBillingManager(
            context = ctx,
            receiptVerifier = ReceiptVerifier(ApiClient("adn_test_subscription_started", Environment.PRODUCTION)),
            entitlementCache = cache,
            storage = LocalStorage(ctx),
        )

        RevenueCatBridge().forwardPurchaseSuccess("pro_yearly", "tx-rc-3")

        assertEquals(listOf("purchase_completed", "subscription_started"), names())
        cache.clear()
    }

    @Test
    fun `a RevenueCat purchase with no explicit flag and a non-expiring entitlement stays a one-off`() {
        installRealPipeline()
        val cache = EntitlementCache(ctx)
        cache.clear()
        cache.update(oneOffEntitlement(), "inapp")
        AppDNA.billing.manager = NativeBillingManager(
            context = ctx,
            receiptVerifier = ReceiptVerifier(ApiClient("adn_test_subscription_started", Environment.PRODUCTION)),
            entitlementCache = cache,
            storage = LocalStorage(ctx),
        )

        RevenueCatBridge().forwardPurchaseSuccess("lifetime_unlock", "tx-rc-4")

        assertEquals(listOf("purchase_completed"), names())
        cache.clear()
    }

    // ---------------------------------------------------------------- Adapty

    @Test
    fun `an Adapty subscription purchase emits purchase_completed AND subscription_started`() {
        installRealPipeline()
        AppDNA.billing.manager = manager()

        AdaptyBridge().forwardPurchaseSuccess("pro_yearly", "tx-ad-1", isSubscription = true)

        assertEquals(listOf("purchase_completed", "subscription_started"), names())
        assertEquals("adapty", propsOf("subscription_started").getString("provider"))
    }

    @Test
    fun `an Adapty one-off purchase emits ONLY purchase_completed`() {
        installRealPipeline()
        AppDNA.billing.manager = manager()

        AdaptyBridge().forwardPurchaseSuccess("coins_100", "tx-ad-2", isSubscription = false)

        assertEquals(listOf("purchase_completed"), names())
    }

    // ---------------------------------------------------------------- not once per renewal

    /**
     * `subscription_started` is a PURCHASE-TIME event. The reconcile diff owns
     * `subscription_renewed` / `_canceled` / `_renewal_failed` and must stay silent for a product that
     * is merely NEW to its snapshot — every purchase makes the product new to the snapshot exactly once,
     * so emitting there too would double-count the purchase, and MTPU is billed on these names.
     */
    @Test
    fun `the reconcile diff does not emit subscription_started for a newly seen product`() {
        installRealPipeline()
        val mgr = manager()

        mgr.diffAndEmit(
            previous = emptyMap(),
            current = mapOf(
                "pro_yearly" to NativeBillingManager.SubSnapshot(
                    productId = "pro_yearly",
                    purchaseTime = 1_000L,
                    isAcknowledged = true,
                    isAutoRenewing = true,
                ),
            ),
        )

        assertEquals(emptyList<String>(), names())
    }
}
