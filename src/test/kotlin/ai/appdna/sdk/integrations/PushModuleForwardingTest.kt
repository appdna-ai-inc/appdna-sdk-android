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
import org.junit.Assert.assertNull
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

    // ---------------------------------------------------------------------------------------------
    // SPEC-497 I4 M8 / minors 31–34
    // ---------------------------------------------------------------------------------------------

    private fun tapIntent(pushId: String, deliveryId: String, link: String) = Intent().apply {
        putExtra("appdna", "1"); putExtra("push_id", pushId); putExtra("delivery_id", deliveryId)
        putExtra("deep_link", link)
    }

    /**
     * M8: the persisted dedup key holds only the LAST tap. Tap A (onCreate), then tap B through
     * `onNewIntent` WITHOUT `setIntent`, then a rotation re-runs `onCreate` with intent A. NEGATIVE
     * CONTROL: removing only `push_id` (the old code) leaves A's marker and `delivery_id`, so the re-run
     * keys on `dA` ≠ the stored `dB` and A is tracked and routed a second time — this test fails.
     */
    @Test
    fun `tap A, tap B via onNewIntent without setIntent, then A re-run - no second track or route`() {
        val a = tapIntent("pA", "dA", "x://a")
        val b = tapIntent("pB", "dB", "x://b")
        assertTrue(AppDNA.handlePushTap(a))
        assertTrue(AppDNA.handlePushTap(b))
        assertFalse("the handled intent A is inert", AppDNA.handlePushTap(a))
        idle()
        val taps = h.events().filter { it.first == "push_tapped" }.map { it.second.getString("delivery_id") }
        assertEquals(listOf("dA", "dB"), taps)
        assertEquals(listOf("deep_link" to "x://a", "deep_link" to "x://b"), h.routes)
        assertEquals(2, h.tapped.size)
        assertFalse(a.hasExtra("appdna") || a.hasExtra("push_id") || a.hasExtra("delivery_id"))
        assertTrue("the host's own extras are left alone", a.hasExtra("deep_link"))
    }

    /** Minor 31: check-and-mark is one atomic step — racing entry points claim a tap once. */
    @Test
    fun `claimTap is atomic - many threads racing on one tap, exactly one wins`() {
        val storage = ai.appdna.sdk.storage.LocalStorage(ctx)
        val start = java.util.concurrent.CountDownLatch(1)
        val wins = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = (1..16).map {
            Thread {
                start.await()
                if (PushIdempotency.claimTap(storage, "race_key")) wins.incrementAndGet()
            }.also { t -> t.start() }
        }
        start.countDown()
        threads.forEach { it.join(5_000) }
        assertEquals(1, wins.get())
        assertFalse(PushIdempotency.claimTap(storage, "race_key"))
    }

    /** Minor 32: a delivery seen before configure is not claimed, so the forward after configure tracks. */
    @Test
    fun `a delivery with no push manager is not claimed - the same message forwarded after configure is tracked`() {
        val mgr = AppDNA.push.manager
        AppDNA.push.manager = null
        assertTrue(AppDNA.push.handleMessageData(marked))
        idle()
        assertTrue(h.events().isEmpty())
        AppDNA.push.manager = mgr
        assertTrue(AppDNA.push.handleMessageData(marked))
        idle()
        assertEquals(listOf("push_delivered"), h.events().map { it.first })
        assertEquals("d1", h.events().single().second.getString("delivery_id"))
    }

    /** Minor 33: the first rung that is PRESENT decides (iOS parity) — a blank value never falls through. */
    @Test
    fun `routing ladder - a tapped button or a body action with a blank value routes nowhere`() {
        val blankButton = mapOf(
            "appdna" to "1", "push_id" to "p", "screen_id" to "s1", "deep_link" to "x://fallback",
            "actions" to """[{"id":"b1","action_type":"show_screen","action_value":""}]""",
        )
        assertNull(PushPayloadParser.resolveTapAction(blankButton, "b1"))
        val blankBody = mapOf("appdna" to "1", "push_id" to "p", "screen_id" to "s1", "action" to """{"type":"deep_link","value":""}""")
        assertNull(PushPayloadParser.resolveTapAction(blankBody, null))
        val blankFlat = mapOf("appdna" to "1", "push_id" to "p", "deep_link" to "x://y", "action_type" to "show_screen", "action_value" to " ")
        assertNull(PushPayloadParser.resolveTapAction(blankFlat, null))
        // An unknown button id is not present: the ladder continues.
        assertEquals("s1", PushPayloadParser.resolveTapAction(blankButton, "nope")?.value)
        // No action at all: screen_id, then deep_link.
        assertEquals("s1", PushPayloadParser.resolveTapAction(mapOf("screen_id" to "s1", "deep_link" to "x://y"), null)?.value)
        assertTrue(AppDNA.push.handleTapData(blankButton, "b1"))
        assertTrue(h.routes.isEmpty())
    }

    /** Minor 34: `delivery_id` reaches the `/push/delivered` and `/push/tapped` REQUEST BODIES. */
    @Test
    fun `delivery_id is in the push delivered and tapped request bodies`() {
        ai.appdna.sdk.network.TestHttpServer.settleIoDispatcher()
        val srv = ai.appdna.sdk.network.TestHttpServer { _, _ -> 200 to "{}" }
        try {
            h.close()
            h = PushTestHarness(ctx, "fwd_bodies", ai.appdna.sdk.network.ApiClient("adn_test_push_bodies", srv.baseUrl))
            assertTrue(AppDNA.push.handleMessageData(marked))
            assertTrue(AppDNA.push.handleTapData(marked))
            // Real network on Dispatchers.IO: allow a generous wall-clock bound on a loaded test JVM.
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
            while (srv.requests.size < 2 && System.nanoTime() < deadline) { idle(); Thread.sleep(10) }
            assertEquals("requests seen: ${srv.requests} — ${ai.appdna.sdk.network.TestHttpServer.workerDiagnostics()}", 2, srv.requests.size)
            val bodies = synchronized(srv.requests) { srv.requests.associate { it.first to org.json.JSONObject(it.second) } }
            val delivered = bodies.getValue("POST /api/v1/push/delivered")
            val tapped = bodies.getValue("POST /api/v1/push/tapped")
            assertEquals("p1", delivered.getString("push_id"))
            assertEquals("d1", delivered.getString("delivery_id"))
            assertEquals("p1", tapped.getString("push_id"))
            assertEquals("d1", tapped.getString("delivery_id"))
        } finally {
            srv.close()
        }
    }
}
