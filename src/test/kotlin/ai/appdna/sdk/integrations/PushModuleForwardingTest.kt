package ai.appdna.sdk.integrations

import ai.appdna.sdk.AppDNA
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * SPEC-497 §9.2 (B2) — the public forwarding API for a host that owns Firebase Messaging, driven
 * through the REAL `AppDNA.push` entry points and the real PushTokenManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushModuleForwardingTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var h: PushTestHarness

    @Before fun setUp() { h = PushTestHarness(ctx, "fwd") }
    @After fun tearDown() = h.close()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun posted(): Int = shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications.size

    private val marked = mapOf(
        "appdna" to "1", "push_id" to "p1", "delivery_id" to "d1", "title" to "Hi", "body" to "There",
    )

    @Test
    fun `isAppDNAMessage is the marker only`() {
        assertTrue(AppDNA.push.isAppDNAMessage(mapOf("appdna" to "1")))
        assertFalse(AppDNA.push.isAppDNAMessage(mapOf("push_id" to "p1")))
        assertFalse(AppDNA.push.isAppDNAMessage(mapOf("appdna" to "true", "push_id" to "p1")))
        assertFalse(AppDNA.push.isAppDNAMessage(emptyMap()))
    }

    @Test
    fun `handleMessageData tracks delivered with delivery_id, fires onPushReceived and never displays`() {
        assertTrue(AppDNA.push.handleMessageData(marked))
        idle()
        val ev = h.events()
        assertEquals(listOf("push_delivered"), ev.map { it.first })
        assertEquals("p1", ev[0].second.getString("push_id"))
        assertEquals("d1", ev[0].second.getString("delivery_id"))
        assertEquals(listOf("p1"), h.received.map { it.first.pushId })
        assertEquals(0, posted())
    }

    @Test
    fun `an unmarked message is a no-op that returns false`() {
        assertFalse(AppDNA.push.handleMessageData(mapOf("push_id" to "p1", "title" to "t")))
        assertFalse(AppDNA.push.handleTapData(mapOf("push_id" to "p1", "deep_link" to "x://y")))
        idle()
        assertTrue(h.events().isEmpty())
        assertTrue(h.received.isEmpty() && h.tapped.isEmpty() && h.routes.isEmpty())
    }

    @Test
    fun `the same delivery forwarded twice is tracked once and the repeat still returns true`() {
        assertTrue(AppDNA.push.handleMessageData(marked))
        assertTrue(AppDNA.push.handleMessageData(marked))
        idle()
        assertEquals(1, h.events().size)
        assertEquals(1, h.received.size)
        // A second DELIVERY of the same campaign (same push_id, new delivery_id) is its own message.
        assertTrue(AppDNA.push.handleMessageData(marked + ("delivery_id" to "d2")))
        assertEquals(2, h.events().size)
    }

    @Test
    fun `handleTapData tracks the tap with delivery_id and routes`() {
        assertTrue(AppDNA.push.handleTapData(marked + ("deep_link" to "x://feed")))
        idle()
        val ev = h.events().filter { it.first == "push_tapped" }
        assertEquals(1, ev.size)
        assertEquals("d1", ev[0].second.getString("delivery_id"))
        assertEquals(listOf("p1"), h.tapped.map { it.first.pushId })
        assertEquals(listOf("deep_link" to "x://feed"), h.routes)
    }

    @Test
    fun `routing ladder - the canonical action beats screen_id, and a tapped button beats both`() {
        val data = mapOf(
            "appdna" to "1", "push_id" to "p", "screen_id" to "s1",
            "action" to """{"type":"deep_link","value":"x://body"}""",
            "actions" to """[{"id":"b1","action_type":"show_screen","action_value":"s_btn"}]""",
        )
        assertTrue(AppDNA.push.handleTapData(data))
        assertEquals("x://body", h.routes.single().second)
        PushIdempotency.resetForTesting()
        h.routes.clear()
        assertTrue(AppDNA.push.handleTapData(data, "b1"))
        assertEquals(listOf("show_screen" to "s_btn"), h.routes)
    }

    @Test
    fun `handlePushTap without the marker extra does nothing`() {
        val intent = Intent().putExtra("push_id", "p1").putExtra("deep_link", "x://y")
        assertFalse(AppDNA.handlePushTap(intent))
        idle()
        assertTrue(h.events().isEmpty() && h.routes.isEmpty())
    }

    @Test
    fun `handlePushTap then handleTapData for one tap tracks once`() {
        val intent = Intent().apply { marked.forEach { (k, v) -> putExtra(k, v) } }
        assertTrue(AppDNA.handlePushTap(intent))
        assertTrue(AppDNA.push.handleTapData(marked))
        idle()
        assertEquals(1, h.events().count { it.first == "push_tapped" })
        assertEquals(1, h.tapped.size)
    }

    @Test
    fun `handleTapData then handlePushTap for one tap tracks once`() {
        assertTrue(AppDNA.push.handleTapData(marked))
        val intent = Intent().apply { marked.forEach { (k, v) -> putExtra(k, v) } }
        assertTrue(AppDNA.handlePushTap(intent))
        idle()
        assertEquals(1, h.events().count { it.first == "push_tapped" })
        assertEquals(1, h.tapped.size)
    }

    @Test
    fun `a button intent routes through the same ladder as the flat action`() {
        val intent = Intent().apply {
            putExtra("appdna", "1"); putExtra("push_id", "p9"); putExtra("delivery_id", "d9")
            putExtra("action_id", "view"); putExtra("action_type", "deep_link"); putExtra("action_value", "x://btn")
        }
        assertTrue(AppDNA.handlePushTap(intent))
        idle()
        assertEquals(listOf("deep_link" to "x://btn"), h.routes)
        assertEquals(listOf<String?>("view"), h.tapped.map { it.second })
        assertEquals("view", h.events().single { it.first == "push_tapped" }.second.getString("action"))
    }
}
