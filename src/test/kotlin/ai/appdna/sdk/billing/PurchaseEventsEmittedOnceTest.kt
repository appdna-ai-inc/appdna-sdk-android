package ai.appdna.sdk.billing

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Environment
import ai.appdna.sdk.events.ClientSeqCounter
import ai.appdna.sdk.events.DroppedEventsCounter
import ai.appdna.sdk.events.EventQueue
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.paywalls.PaywallConfig
import ai.appdna.sdk.paywalls.PaywallConfigParser
import ai.appdna.sdk.paywalls.PaywallManager
import ai.appdna.sdk.paywalls.PaywallPlan
import ai.appdna.sdk.storage.EventDatabase
import ai.appdna.sdk.storage.LocalStorage
import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
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

/**
 * 🔴 Android DOUBLE-COUNTED the purchase-event family.
 *
 * `PaywallManager.handlePurchase` emitted `purchase_started` / `purchase_completed` /
 * `purchase_canceled` / `purchase_pending` / `purchase_failed`, and then called
 * `AppDNA.billing.purchase()` — i.e. [NativeBillingManager], which emits the SAME events for the SAME
 * purchase. Two of each, with two different property shapes (so nothing downstream could dedup them).
 * Android paywall conversion and purchase volume read ~2× iOS for identical user behaviour, and MTPU
 * (`purchase_completed` is one of the three metered events) was billed on the doubled count.
 *
 * This test drives the REAL chain — real PaywallManager → real BillingModule → real
 * NativeBillingManager → real EventTracker → real EventQueue → real EventDatabase — and COUNTS the
 * envelopes that came out. The billing manager is never `initialize()`d, so `awaitConnectedClient()`
 * returns null (no Play Store in a JVM test) and the purchase dies on the "BillingClient not
 * connected" terminal — after `purchase_started` has been emitted, which is the event being counted.
 *
 * Reverting the fix (restoring PaywallManager's own `eventTracker.track("purchase_started", …)`)
 * turns `purchaseStarted == 1` into `2` — RED.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PurchaseEventsEmittedOnceTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: EventDatabase
    private lateinit var tracker: EventTracker

    /** A REAL event pipeline: EventTracker → EventQueue → EventDatabase. `batchSize = 0` means the
     *  flush threshold is never tripped, so nothing is uploaded and the envelopes stay readable. */
    private fun installRealPipeline() {
        val storage = LocalStorage(ctx)
        val identity = ai.appdna.sdk.IdentityManager(storage)
        db = EventDatabase(ctx, 10_000, "purchase_once_test.db")
        ClientSeqCounter.init(ctx)
        DroppedEventsCounter.init(ctx)
        db.clearAll()
        tracker = EventTracker(identity, "1.0", "sandbox")
        tracker.setEventQueue(
            EventQueue(
                apiClient = ApiClient("adn_test_purchase_once", Environment.PRODUCTION),
                eventDatabase = db,
                connectivityMonitor = null,
                batchSizeCap = 0,
                flushInterval = Long.MAX_VALUE,
            ),
        )
        // The billing layer emits through the STATIC AppDNA.track — route it at our tracker.
        AppDNA.installEventTrackerForTest(tracker)
        // configure() assigns the ownership policy; these tests stand in for it (storeKit2 —
        // the SDK owns billing), or the paywall would take the providerNotAvailable path.
        AppDNA.billing.ownershipPolicy = BillingOwnership.policy(ai.appdna.sdk.BillingProvider.StoreKit2)
    }

    private fun emittedEvents(): List<Pair<String, JSONObject?>> =
        db.loadAll().map { JSONObject(it) }.map { it.getString("event_name") to it.optJSONObject("properties") }

    private fun countOf(name: String) = emittedEvents().count { it.first == name }

    @After
    fun tearDown() {
        AppDNA.installEventTrackerForTest(null)
        AppDNA.billing.manager = null
        AppDNA.billing.ownershipPolicy = BillingOwnershipPolicy.NotConfigured
        db.clearAll()
    }

    private fun realBillingManager(): NativeBillingManager = NativeBillingManager(
        context = ctx,
        receiptVerifier = ReceiptVerifier(ApiClient("adn_test_purchase_once", Environment.PRODUCTION)),
        entitlementCache = EntitlementCache(ctx),
        storage = LocalStorage(ctx),
    )
    // NB: deliberately NOT initialize()d — no BillingClient, so purchase() takes its
    // "BillingClient not connected" terminal instead of trying to reach Google Play.

    private fun paywall(): PaywallConfig = PaywallConfigParser.parsePaywalls(
        mapOf(
            "pw_test" to mapOf(
                "id" to "pw_test",
                "name" to "Test",
                "layout" to mapOf("type" to "stack"),
                "sections" to emptyList<Map<String, Any>>(),
            ),
        ),
    )["pw_test"]!!

    private fun plan() = PaywallPlan(
        id = "plan_yearly",
        product_id = "pro_yearly",
        name = "Yearly",
        price = "$49.99",
    )

    private fun realPaywallManager(): PaywallManager = PaywallManager(
        remoteConfigManager = ai.appdna.sdk.config.RemoteConfigManager(
            firestorePath = null,
            storage = LocalStorage(ctx),
            configTTL = 3600L,
        ),
        eventTracker = tracker,
    )

    /** Drive the whole paywall→billing purchase chain and let the coroutines settle. */
    private fun runPaywallPurchase(metadata: Map<String, Any> = emptyMap()) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val mgr = realBillingManager()
        AppDNA.billing.manager = mgr
        // 🔴 This line used to say `mgr.currentPaywallId = "pw_test"`.
        //
        // Nothing in PRODUCTION ever wrote that field — the test wrote it, and then asserted the
        // event carried it. So when the purchase emits moved down into the billing manager, every
        // Android purchase event shipped `paywall_id: ""` and this test stayed green, because the
        // test was driving a path production could not reach. Blanked paywall conversion, MTPU-by-
        // paywall and every paywall experiment breakdown, while iOS still sent the real id.
        //
        // The setup no longer supplies it. `handlePurchase` must set it, or the assertions below fail.

        realPaywallManager().handlePurchase(
            activity = activity,
            paywallId = "pw_test",
            plan = plan(),
            config = paywall(),
            metadata = metadata,
            listener = null,
        )
        repeat(5) { shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test
    fun `a paywall purchase emits purchase_started exactly once`() {
        installRealPipeline()
        runPaywallPurchase()

        assertEquals(
            "purchase_started must be emitted ONCE per purchase — got ${emittedEvents().map { it.first }}",
            1,
            countOf("purchase_started"),
        )
    }

    @Test
    fun `a failing paywall purchase emits purchase_failed exactly once`() {
        installRealPipeline()
        runPaywallPurchase()

        assertEquals(
            "purchase_failed must be emitted ONCE per purchase — got ${emittedEvents().map { it.first }}",
            1,
            countOf("purchase_failed"),
        )
    }

    /**
     * The paywall's contribution to the funnel is its CONTEXT, not a second event: `paywall_id` and
     * the AC-038 metadata it collected (toggle states, promo_code) must ride on the manager's single
     * emit. Reverting the metadata plumbing loses `promo_code` from the analytics entirely.
     */
    @Test
    fun `the single purchase_started carries the paywall id and the paywall's AC-038 metadata`() {
        installRealPipeline()
        runPaywallPurchase(metadata = mapOf("promo_code" to "LAUNCH50", "annual_toggle" to true))

        val started = emittedEvents().first { it.first == "purchase_started" }.second!!
        assertEquals("pro_yearly", started.getString("product_id"))
        assertEquals("pw_test", started.getString("paywall_id"))
        assertEquals("LAUNCH50", started.getString("promo_code"))
        assertTrue(started.getBoolean("annual_toggle"))
    }

    /** The failure carries iOS's property names (`error` + the machine-readable `error_type`). */
    @Test
    fun `the single purchase_failed carries product_id, paywall_id and error_type`() {
        installRealPipeline()
        runPaywallPurchase()

        val failed = emittedEvents().first { it.first == "purchase_failed" }.second!!
        assertEquals("pro_yearly", failed.getString("product_id"))
        assertEquals("pw_test", failed.getString("paywall_id"))
        assertEquals("billing_client_not_connected", failed.getString("reason"))
        assertTrue(failed.has("error"))
        assertTrue(failed.has("error_type"))
    }

    /**
     * A purchase started OUTSIDE a paywall (`AppDNA.billing.purchase(...)` straight from host code)
     * must ALSO emit exactly one of each — the fix must not simply delete an emit.
     */
    @Test
    fun `a direct billing purchase with no paywall still emits purchase_started exactly once`() = kotlinx.coroutines.runBlocking {
        installRealPipeline()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val mgr = realBillingManager()
        AppDNA.billing.manager = mgr

        runCatching { AppDNA.billing.purchase(activity, "pro_yearly") }
        repeat(5) { shadowOf(Looper.getMainLooper()).idle() }

        assertEquals(1, countOf("purchase_started"))
        assertEquals(1, countOf("purchase_failed"))
        val started = emittedEvents().first { it.first == "purchase_started" }.second!!
        assertEquals("pro_yearly", started.getString("product_id"))
        // No paywall in the chain → empty attribution, not a stale one.
        assertEquals("", started.getString("paywall_id"))
    }
}
