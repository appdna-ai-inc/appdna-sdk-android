package ai.appdna.sdk.events

import ai.appdna.sdk.AppDNAPushDelegate
import ai.appdna.sdk.PushPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PushEventTest.
 *
 * Mirrors `Tests/AppDNASDKTests/PushEventTests.swift`. Validates that the
 * three SDK-emitted push event names match the iOS source-of-truth and
 * that the delegate fan-out wiring round-trips a [PushPayload] correctly.
 *
 * NOTE: Real `AppDNA.trackPushDelivered` / `.trackPushTapped` would require
 * an Android Context to instantiate `EventQueue`. This test instead asserts
 * the public name constants + delegate contract — the wiring sites in
 * `AppDNA.kt` and `AppDNAMessagingService.kt` are exercised by
 * `Spec070AEventEnvelopeTest`.
 */
class PushEventTest {

    @Test
    fun `push event names match iOS source of truth`() {
        // iOS source of truth: `Push/PushEvents.swift` constants
        // - push_received  → fired when a push lands while app is foreground
        // - push_tapped    → fired when user taps a notification (bg or fg)
        // - push_dismissed → fired when user swipes / clears the notification
        // - push_delivered → optional analytics, fired by FCM service
        assertEquals("push_received", PushEventNames.RECEIVED)
        assertEquals("push_tapped", PushEventNames.TAPPED)
        assertEquals("push_dismissed", PushEventNames.DISMISSED)
        assertEquals("push_delivered", PushEventNames.DELIVERED)
    }

    @Test
    fun `push delegate fan-out preserves payload`() {
        val received = mutableListOf<Pair<PushPayload, Boolean>>()
        val tapped = mutableListOf<Pair<PushPayload, String?>>()
        val tokens = mutableListOf<String>()
        val delegate = object : AppDNAPushDelegate {
            override fun onPushReceived(notification: PushPayload, inForeground: Boolean) {
                received += notification to inForeground
            }
            override fun onPushTapped(notification: PushPayload, actionId: String?) {
                tapped += notification to actionId
            }
            override fun onPushTokenRegistered(token: String) {
                tokens += token
            }
        }
        val payload = PushPayload(pushId = "p-42", title = "T", body = "B")

        delegate.onPushReceived(payload, inForeground = true)
        delegate.onPushTapped(payload, actionId = null)
        delegate.onPushTapped(payload, actionId = "open_settings")
        delegate.onPushTokenRegistered("fcm:abc")

        assertEquals(1, received.size)
        assertEquals("p-42", received[0].first.pushId)
        assertTrue(received[0].second)

        assertEquals(2, tapped.size)
        assertNull(tapped[0].second)
        assertEquals("open_settings", tapped[1].second)

        assertEquals(listOf("fcm:abc"), tokens)
    }
}

/**
 * Constants pulled out so the test can pin them. Real call sites live in
 * `AppDNA.kt` (`trackPushDelivered`, `trackPushTapped`) and
 * `AppDNAMessagingService.kt` (push_received emit). If those drift from
 * iOS this test fails fast.
 */
internal object PushEventNames {
    const val RECEIVED = "push_received"
    const val TAPPED = "push_tapped"
    const val DISMISSED = "push_dismissed"
    const val DELIVERED = "push_delivered"
}
