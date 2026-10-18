package ai.appdna.sdk.integrations

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Environment
import ai.appdna.sdk.IdentityManager
import ai.appdna.sdk.events.ClientSeqCounter
import ai.appdna.sdk.events.DroppedEventsCounter
import ai.appdna.sdk.events.EventQueue
import ai.appdna.sdk.storage.EventDatabase
import ai.appdna.sdk.storage.LocalStorage
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.network.ApiClient
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #968 — `push_dismissed` carries `delivery_id`, which the receiver had been dropping.
 *
 * 🔴 THE VALUE WAS ALREADY ON THE INTENT. `AppDNAMessagingService` builds the dismissal
 * PendingIntent with `putExtra("delivery_id", …)`, and `PushDismissReceiver` read only `push_id`
 * — while the public events page documents `push_dismissed` as carrying `push_id · delivery_id`.
 * So the one event that says a push was REJECTED could not be joined to the delivery that caused
 * it, and a documented property was never sent by anybody.
 *
 * 🔴 THESE DRIVE THE REAL RECEIVER AND READ THE REAL ENVELOPE. An earlier version of this file
 * rebuilt the receiver's own `buildMap` expression in the test and asserted on that — which would
 * have passed just as happily against the version that dropped `delivery_id`, because a test that
 * re-implements the thing under test can only ever agree with it. This installs a real
 * `EventTracker` over a real `EventQueue`/`EventDatabase` (the `Pipeline` pattern from
 * `SharedFixtureTest`, with a batch cap of 0 and no flush, so nothing leaves the device) and reads
 * the persisted envelopes back out of SQLite.
 */
@RunWith(RobolectricTestRunner::class)
class PushDismissDeliveryIdTest {

    private lateinit var ctx: Context
    private lateinit var db: EventDatabase

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ClientSeqCounter.init(ctx)
        DroppedEventsCounter.init(ctx)
        db = EventDatabase(ctx, 10_000, "push_dismiss_968.db")
        db.clearAll()
        val tracker = EventTracker(IdentityManager(LocalStorage(ctx)), "1.0", "sandbox")
        tracker.setEventQueue(
            EventQueue(
                apiClient = ApiClient("adn_test_968", Environment.PRODUCTION),
                eventDatabase = db,
                connectivityMonitor = null,
                batchSizeCap = 0,
                flushInterval = Long.MAX_VALUE,
            ),
        )
        AppDNA.installEventTrackerForTest(tracker)
    }

    @After
    fun tearDown() {
        AppDNA.installEventTrackerForTest(null)
        db.clearAll()
    }

    /** The envelopes the SDK actually persisted. */
    private fun events(): List<JSONObject> = db.loadAll().map { JSONObject(it) }

    private fun props(e: JSONObject): JSONObject = e.optJSONObject("properties") ?: JSONObject()

    private fun dismiss(pushId: String?, deliveryId: String?) {
        val intent = Intent(ctx, PushDismissReceiver::class.java).apply {
            action = PushDismissReceiver.ACTION_PUSH_DISMISSED
            pushId?.let { putExtra("push_id", it) }
            deliveryId?.let { putExtra("delivery_id", it) }
        }
        PushDismissReceiver().onReceive(ctx, intent)
    }

    @Test
    fun `a dismissal carries both ids when the delivery is known`() {
        dismiss("p_1", "d_1")
        val all = events()
        assertEquals(1, all.size)
        assertEquals("push_dismissed", all[0].optString("event_name"))
        assertEquals("p_1", props(all[0]).optString("push_id"))
        assertEquals("d_1", props(all[0]).optString("delivery_id"))
    }

    @Test
    fun `a missing delivery_id is ABSENT, not an empty string`() {
        // An empty `delivery_id` joins to no delivery row and reads in a funnel as a real value
        // that matches nothing — worse than the key not being there at all.
        dismiss("p_2", null)
        dismiss("p_3", "")
        val all = events()
        assertEquals(2, all.size)
        assertFalse("an absent delivery must not emit the key", props(all[0]).has("delivery_id"))
        assertFalse("an empty delivery must not emit the key", props(all[1]).has("delivery_id"))
        assertEquals("p_2", props(all[0]).optString("push_id"))
        assertEquals("p_3", props(all[1]).optString("push_id"))
    }

    @Test
    fun `no push_id means no event at all`() {
        // The receiver returns early: an event identifying no push is noise in the funnel.
        dismiss(null, "d_4")
        assertTrue(events().isEmpty())
    }

    @Test
    fun `an intent that is not a dismissal is ignored`() {
        /*
         * The control. Without it, every assertion above would pass against a receiver that fired
         * on ANY broadcast — and an explicit PendingIntent reaching only a manifest-declared
         * receiver makes that safe by luck rather than by the guard.
         */
        val foreign = Intent(ctx, PushDismissReceiver::class.java).apply {
            action = "ai.appdna.SOMETHING_ELSE"
            putExtra("push_id", "p_5")
        }
        PushDismissReceiver().onReceive(ctx, foreign)
        assertTrue(events().isEmpty())
    }
}
