package ai.appdna.sdk.billing

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Environment
import ai.appdna.sdk.IdentityManager
import ai.appdna.sdk.events.ClientSeqCounter
import ai.appdna.sdk.events.DroppedEventsCounter
import ai.appdna.sdk.events.EventQueue
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.storage.EventDatabase
import ai.appdna.sdk.storage.LocalStorage
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC-070-A A.20 — the subscription-lifecycle emitter: [NativeBillingManager.diffAndEmit] compares the
 * previous vs current `queryPurchasesAsync` snapshot and emits `subscription_renewed` /
 * `subscription_canceled` / `subscription_renewal_failed`.
 *
 * This test calls the REAL emitter and reads back the envelopes the REAL EventTracker → EventQueue →
 * EventDatabase produced. (It used to re-implement the diff inline and assert against its own copy of
 * the rules — which proved nothing about the SDK and left the event and property NAMES entirely
 * unasserted. Those names are the contract iOS's new `SubscriptionStatusObserver` mirrors
 * byte-for-byte: `raw.sdk_events.properties` is a JSON blob read via JSON_EXTRACT, so a divergent key
 * is silent forever.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SubscriptionDiffAndEmitTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: EventDatabase
    private lateinit var manager: NativeBillingManager

    @Before
    fun setUp() {
        val storage = LocalStorage(ctx)
        db = EventDatabase(ctx, 10_000, "sub_diff_test.db")
        ClientSeqCounter.init(ctx)
        DroppedEventsCounter.init(ctx)
        db.clearAll()
        val tracker = EventTracker(IdentityManager(storage), "1.0", "sandbox")
        tracker.setEventQueue(
            EventQueue(
                apiClient = ApiClient("adn_test_sub_diff", Environment.PRODUCTION),
                eventDatabase = db,
                connectivityMonitor = null,
                batchSizeCap = 0, // never trips the flush threshold → nothing uploaded, envelopes readable
                flushInterval = Long.MAX_VALUE,
            ),
        )
        AppDNA.installEventTrackerForTest(tracker)

        manager = NativeBillingManager(
            context = ctx,
            receiptVerifier = ReceiptVerifier(ApiClient("adn_test_sub_diff", Environment.PRODUCTION)),
            entitlementCache = EntitlementCache(ctx),
            storage = storage,
        )
    }

    @After
    fun tearDown() {
        AppDNA.installEventTrackerForTest(null)
        db.clearAll()
    }

    private fun emitted(): List<Pair<String, JSONObject?>> =
        db.loadAll().map { JSONObject(it) }
            .map { it.getString("event_name") to it.optJSONObject("properties") }

    private fun snap(
        productId: String,
        purchaseTime: Long,
        isAcknowledged: Boolean = true,
        isAutoRenewing: Boolean = true,
    ) = NativeBillingManager.SubSnapshot(
        productId = productId,
        purchaseTime = purchaseTime,
        isAcknowledged = isAcknowledged,
        isAutoRenewing = isAutoRenewing,
    )

    @Test
    fun `a vanished auto-renewing sub emits subscription_renewal_failed with product_id`() {
        manager.diffAndEmit(
            previous = mapOf("pro_yearly" to snap("pro_yearly", 1_000L, isAutoRenewing = true)),
            current = emptyMap(),
        )

        val events = emitted()
        assertEquals(listOf("subscription_renewal_failed"), events.map { it.first })
        assertEquals("pro_yearly", events[0].second!!.getString("product_id"))
    }

    @Test
    fun `a vanished non-auto-renewing sub emits subscription_canceled with product_id`() {
        manager.diffAndEmit(
            previous = mapOf("pro_yearly" to snap("pro_yearly", 1_000L, isAutoRenewing = false)),
            current = emptyMap(),
        )

        val events = emitted()
        assertEquals(listOf("subscription_canceled"), events.map { it.first })
        assertEquals("pro_yearly", events[0].second!!.getString("product_id"))
    }

    @Test
    fun `an advanced purchaseTime emits subscription_renewed with product_id and purchase_time`() {
        manager.diffAndEmit(
            previous = mapOf("pro_yearly" to snap("pro_yearly", 1_000L)),
            current = mapOf("pro_yearly" to snap("pro_yearly", 2_000L)),
        )

        val events = emitted()
        assertEquals(listOf("subscription_renewed"), events.map { it.first })
        val props = events[0].second!!
        assertEquals("pro_yearly", props.getString("product_id"))
        assertEquals(2_000L, props.getLong("purchase_time"))
    }

    @Test
    fun `an unchanged sub emits nothing`() {
        manager.diffAndEmit(
            previous = mapOf("pro_yearly" to snap("pro_yearly", 1_000L)),
            current = mapOf("pro_yearly" to snap("pro_yearly", 1_000L)),
        )
        assertEquals(emptyList<String>(), emitted().map { it.first })
    }

    @Test
    fun `a brand-new sub emits nothing — the initial purchase is purchase_completed's job`() {
        manager.diffAndEmit(
            previous = emptyMap(),
            current = mapOf("pro_yearly" to snap("pro_yearly", 5_000L)),
        )
        assertEquals(emptyList<String>(), emitted().map { it.first })
    }
}
