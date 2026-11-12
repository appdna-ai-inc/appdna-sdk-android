package ai.appdna.sdk.onboarding

import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "Add widget" attempt, one test per row of its state table plus the rules that hold in every
 * state. Every test drives the PRODUCTION [WidgetPinAttempt] through its two seams: a fake launcher
 * ([Launcher]) and a recorder of what the attempt does to the world ([World]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetPinAttemptTest {

    private class Launcher(
        override var sdkInt: Int = 34,
        var providers: List<String> = listOf(PROVIDER),
        var supported: Boolean = true,
        var result: String = "true",
        var ids: Set<Int>? = emptySet(),
    ) : WidgetPinPlatform {
        val requests = mutableListOf<Pair<String, String>>()
        override fun homeScreenProviders() = providers
        override fun isRequestPinSupported() = supported
        override fun requestPin(providerClass: String, token: String): Boolean {
            requests += providerClass to token
            return when (result) {
                "true" -> true
                "false" -> false
                else -> throw IllegalStateException("not in the foreground")
            }
        }
        override fun widgetIds(providerClass: String) = ids
    }

    private class Timer(val delayMs: Long, val action: () -> Unit) {
        var cancelled = false
    }

    private class World(var gate: Boolean = true) : WidgetPinEffects {
        val events = mutableListOf<Pair<String, Map<String, Any>>>()
        val advances = mutableListOf<String>()
        var sheet: List<ContentBlock>? = null
        var sheetCloses = 0
        var retired = 0
        val timers = mutableListOf<Timer>()
        override fun gatePasses() = gate
        override fun track(name: String, properties: Map<String, Any>) { events += name to properties }
        override fun advance(status: String) { advances += status }
        override fun showHowto(blocks: List<ContentBlock>) { sheet = blocks }
        override fun closeHowto() { sheet = null; sheetCloses++ }
        override fun onAttemptRetired() { retired++ }
        override fun schedule(delayMs: Long, action: () -> Unit): WidgetPinTimer {
            val t = Timer(delayMs, action)
            timers += t
            return WidgetPinTimer { t.cancelled = true }
        }
        fun live(delay: Long) = timers.filter { it.delayMs == delay && !it.cancelled }
        fun names() = events.map { it.first }
        fun props(name: String) = events.last { it.first == name }.second
    }

    private val howto = persistentListOf(
        ContentBlock(id = "h_head", type = "heading", text = "Add the widget"),
        ContentBlock(id = "h_done", type = "button", text = "Done", action = "next"),
    )
    private val button = ContentBlock(id = "cta", type = "button", action = "add_widget").withHowtoBlocks(null, howto)

    private fun attempt(l: Launcher = Launcher(), w: World = World()) =
        Triple(WidgetPinAttempt(l, w, "flow_1", "step_1") { "token-${l.requests.size}" }, l, w)

    /** IDLE → WAITING_DIALOG → AWAY, the common start of the pin path. */
    private fun away(l: Launcher = Launcher(), w: World = World()): Triple<WidgetPinAttempt, Launcher, World> {
        val t = attempt(l, w)
        t.first.tap(button)
        t.first.onPause()
        return t
    }

    // ---- IDLE ----------------------------------------------------------------------------------

    @Test fun `IDLE tap with the gate refused starts nothing and emits nothing`() {
        val (a, l, w) = attempt(w = World(gate = false))
        a.tap(button)
        assertEquals(emptyList<String>(), w.names())
        assertTrue(l.requests.isEmpty())
        assertTrue(w.advances.isEmpty())
        assertNull(w.sheet)
        assertEquals(WidgetPinAttempt.State.IDLE, a.state)
    }

    @Test fun `IDLE tap with no provider is unavailable and advances`() {
        val (a, l, w) = attempt(Launcher(providers = emptyList()))
        a.tap(button)
        assertEquals(listOf("widget_add_unavailable"), w.names())
        assertEquals("no_widget", w.props("widget_add_unavailable")["reason"])
        assertEquals(listOf("unavailable"), w.advances)
        assertTrue(l.requests.isEmpty())
        assertEquals(WidgetPinAttempt.State.DONE, a.state)
    }

    @Test fun `IDLE tap below API 26 shows the how-to with api_level`() {
        val (a, l, w) = attempt(Launcher(sdkInt = 25, ids = setOf(1)))
        a.tap(button)
        assertEquals(listOf("widget_add_tapped", "widget_pin_unavailable", "widget_howto_shown"), w.names())
        assertEquals(1, w.props("widget_add_tapped")["existing_count"])
        assertEquals("cta", w.props("widget_add_tapped")["block_id"])
        assertEquals("api_level", w.props("widget_pin_unavailable")["reason"])
        assertEquals(howto, w.sheet)
        assertTrue("no request below API 26", l.requests.isEmpty())
        assertEquals(WidgetPinAttempt.State.HOWTO, a.state)
    }

    @Test fun `IDLE tap with a launcher that cannot pin shows the how-to with launcher_unsupported`() {
        val (a, l, w) = attempt(Launcher(supported = false))
        a.tap(button)
        assertEquals("launcher_unsupported", w.props("widget_pin_unavailable")["reason"])
        assertTrue(l.requests.isEmpty())
        assertEquals(WidgetPinAttempt.State.HOWTO, a.state)
    }

    @Test fun `IDLE tap whose request throws shows the how-to with request_refused`() {
        val (a, l, w) = attempt(Launcher(result = "throws"))
        a.tap(button)
        assertEquals(listOf("widget_add_tapped", "widget_pin_unavailable", "widget_howto_shown"), w.names())
        assertEquals("request_refused", w.props("widget_pin_unavailable")["reason"])
        assertEquals(1, l.requests.size)
        assertEquals(WidgetPinAttempt.State.HOWTO, a.state)
    }

    @Test fun `IDLE tap whose request returns false shows the how-to with request_refused`() {
        val (a, _, w) = attempt(Launcher(result = "false"))
        a.tap(button)
        assertEquals("request_refused", w.props("widget_pin_unavailable")["reason"])
        assertEquals(WidgetPinAttempt.State.HOWTO, a.state)
    }

    @Test fun `IDLE tap whose request is sent waits for the dialog with the pause timer armed`() {
        val (a, l, w) = attempt()
        a.tap(button)
        assertEquals(listOf("widget_add_tapped", "widget_pin_requested"), w.names())
        assertEquals(PROVIDER, w.props("widget_pin_requested")["provider"])
        assertEquals("flow_1", w.props("widget_pin_requested")["flow_id"])
        assertEquals("step_1", w.props("widget_pin_requested")["step_id"])
        assertEquals(listOf(PROVIDER to "token-0"), l.requests)
        assertEquals(1, w.live(WidgetPinAttempt.PAUSE_TIMEOUT_MS).size)
        assertEquals(WidgetPinAttempt.State.WAITING_DIALOG, a.state)
        assertTrue(w.advances.isEmpty())
    }

    // ---- WAITING_DIALOG ------------------------------------------------------------------------

    @Test fun `WAITING_DIALOG pause cancels the pause timer and goes AWAY`() {
        val (a, _, w) = away()
        assertTrue(w.timers.single().cancelled)
        assertEquals(WidgetPinAttempt.State.AWAY, a.state)
    }

    @Test fun `WAITING_DIALOG pause timeout is a silent launcher and shows the how-to`() {
        val (a, _, w) = attempt()
        a.tap(button)
        w.timers.single().action()
        assertEquals(listOf("widget_add_tapped", "widget_pin_requested", "widget_pin_unavailable", "widget_howto_shown"), w.names())
        assertEquals("silent_launcher", w.props("widget_pin_unavailable")["reason"])
        assertEquals(WidgetPinAttempt.State.HOWTO, a.state)
    }

    @Test fun `WAITING_DIALOG callback is added by pin`() {
        val (a, _, w) = attempt()
        a.tap(button)
        a.onCallback(a.liveToken)
        assertEquals("widget_added", w.names().last())
        assertEquals("pin", w.props("widget_added")["method"])
        assertEquals(PROVIDER, w.props("widget_added")["provider"])
        assertEquals(listOf("added"), w.advances)
        assertTrue(w.timers.single().cancelled)
    }

    // ---- AWAY ----------------------------------------------------------------------------------

    @Test fun `AWAY callback marks pinned and waits for the return`() {
        val (a, _, w) = away()
        a.onCallback(a.liveToken)
        assertEquals(WidgetPinAttempt.State.AWAY, a.state)
        assertTrue("no advance while the dialog is up", w.advances.isEmpty())
        a.onResume()
        assertEquals(listOf("added"), w.advances)
        assertEquals("pin", w.props("widget_added")["method"])
    }

    @Test fun `AWAY resume without a callback opens the grace window`() {
        val (a, _, w) = away()
        a.onResume()
        assertEquals(WidgetPinAttempt.State.GRACE, a.state)
        assertEquals(1, w.live(WidgetPinAttempt.GRACE_MS).size)
        assertEquals(1, w.live(WidgetPinAttempt.GRACE_TICK_MS).size)
        assertTrue(w.advances.isEmpty())
    }

    // ---- GRACE ---------------------------------------------------------------------------------

    @Test fun `GRACE callback is added by pin and stops the timers`() {
        val (a, _, w) = away()
        a.onResume()
        a.onCallback(a.liveToken)
        assertEquals(listOf("added"), w.advances)
        assertTrue(w.live(WidgetPinAttempt.GRACE_MS).isEmpty())
        assertTrue(w.live(WidgetPinAttempt.GRACE_TICK_MS).isEmpty())
    }

    @Test fun `GRACE tick with no new id keeps polling, and with one is added`() {
        val l = Launcher(ids = setOf(3))
        val (a, _, w) = away(l)
        a.onResume()
        w.live(WidgetPinAttempt.GRACE_TICK_MS).single().action()
        assertEquals(WidgetPinAttempt.State.GRACE, a.state)
        assertEquals("a tick that saw nothing schedules the next", 2, w.timers.count { it.delayMs == WidgetPinAttempt.GRACE_TICK_MS })
        l.ids = setOf(3, 9)
        w.timers.last { it.delayMs == WidgetPinAttempt.GRACE_TICK_MS }.action()
        assertEquals(listOf("added"), w.advances)
        assertEquals("pin", w.props("widget_added")["method"])
    }

    @Test fun `GRACE pause cancels the timers and goes AWAY`() {
        val (a, _, w) = away()
        a.onResume()
        a.onPause()
        assertEquals(WidgetPinAttempt.State.AWAY, a.state)
        assertTrue(w.live(WidgetPinAttempt.GRACE_MS).isEmpty())
        assertTrue(w.live(WidgetPinAttempt.GRACE_TICK_MS).isEmpty())
    }

    @Test fun `GRACE end with no new id is not added by pin`() {
        val (a, _, w) = away()
        a.onResume()
        w.live(WidgetPinAttempt.GRACE_MS).single().action()
        assertEquals(listOf("not_added"), w.advances)
        assertEquals("pin", w.props("widget_not_added")["method"])
        assertEquals("ok", w.props("widget_not_added")["detection"])
    }

    @Test fun `GRACE end with a new id re-read is added`() {
        val l = Launcher()
        val (a, _, w) = away(l)
        a.onResume()
        l.ids = setOf(4)
        w.live(WidgetPinAttempt.GRACE_MS).single().action()
        assertEquals(listOf("added"), w.advances)
    }

    @Test fun `a removed widget and an added one in the same trip is still added (set difference, not a count)`() {
        val l = Launcher(ids = setOf(1))
        val (a, _, w) = away(l)
        a.onResume()
        l.ids = setOf(2)
        w.live(WidgetPinAttempt.GRACE_MS).single().action()
        assertEquals(listOf("added"), w.advances)
    }

    // ---- HOWTO ---------------------------------------------------------------------------------

    private fun inHowto(l: Launcher = Launcher(result = "false"), w: World = World()): Triple<WidgetPinAttempt, Launcher, World> {
        val t = attempt(l, w)
        t.first.tap(button)
        assertEquals(WidgetPinAttempt.State.HOWTO, t.first.state)
        return t
    }

    @Test fun `HOWTO entry shows the sheet and reports it`() {
        val (_, _, w) = inHowto()
        assertEquals(howto, w.sheet)
        assertEquals("cta", w.props("widget_howto_shown")["block_id"])
    }

    @Test fun `HOWTO entry with no Android how-to ends the attempt as not added and advances`() {
        val w = World()
        val a = WidgetPinAttempt(Launcher(result = "false"), w, "flow_1", "step_1")
        a.tap(button.withHowtoBlocks(null, null))
        assertNull(w.sheet)
        assertEquals(listOf("not_added"), w.advances)
        assertFalse(w.names().contains("widget_howto_shown"))
    }

    @Test fun `HOWTO resume with a new widget closes the sheet itself and is added by howto`() {
        val l = Launcher(result = "false")
        val (a, _, w) = inHowto(l)
        a.onPause()
        a.onResume()
        assertEquals("nothing new: the sheet stays", WidgetPinAttempt.State.HOWTO, a.state)
        l.ids = setOf(5)
        a.onResume()
        assertNull(w.sheet)
        assertEquals(listOf("widget_howto_dismissed", "widget_added"), w.names().takeLast(2))
        assertEquals("detected", w.props("widget_howto_dismissed")["via"])
        assertEquals("howto", w.props("widget_added")["method"])
        assertEquals(listOf("added"), w.advances)
    }

    @Test fun `HOWTO callback after a silent launcher closes the sheet and is added by pin`() {
        val (a, _, w) = attempt()
        a.tap(button)
        w.timers.single().action()
        a.onCallback(a.liveToken)
        assertNull(w.sheet)
        assertEquals("detected", w.props("widget_howto_dismissed")["via"])
        assertEquals("pin", w.props("widget_added")["method"])
        assertEquals(listOf("added"), w.advances)
    }

    @Test fun `HOWTO Done with nothing new is not added, then dismissed by cta`() {
        val (a, _, w) = inHowto()
        a.onDoneCta()
        assertEquals(listOf("widget_not_added", "widget_howto_dismissed"), w.names().takeLast(2))
        assertEquals("howto", w.props("widget_not_added")["method"])
        assertEquals("cta", w.props("widget_howto_dismissed")["via"])
        assertEquals(listOf("not_added"), w.advances)
        assertNull(w.sheet)
    }

    @Test fun `HOWTO Done after adding by hand is added by howto`() {
        val l = Launcher(result = "false")
        val (a, _, w) = inHowto(l)
        l.ids = setOf(8)
        a.onDoneCta()
        assertEquals(listOf("widget_added", "widget_howto_dismissed"), w.names().takeLast(2))
        assertEquals(listOf("added"), w.advances)
    }

    @Test fun `HOWTO close by swipe, back, scrim or dismiss stays on the step and retires the attempt`() {
        for (via in listOf("swipe", "back", "scrim", "dismiss")) {
            val (a, l, w) = inHowto()
            a.onClose(via)
            assertEquals(via, w.props("widget_howto_dismissed")["via"])
            assertTrue("$via must not advance", w.advances.isEmpty())
            assertNull(w.sheet)
            assertEquals(1, w.retired)
            assertEquals(WidgetPinAttempt.State.IDLE, a.state)
            assertNull(a.liveToken)
            // A later tap starts a NEW attempt.
            a.tap(button)
            assertEquals(2, w.names().count { it == "widget_add_tapped" })
            assertEquals(2, l.requests.size)
        }
    }

    // ---- Rules for every state -----------------------------------------------------------------

    @Test fun `a second tap while an attempt is live is ignored - one event, one request`() {
        for (prime in listOf<(WidgetPinAttempt) -> Unit>({}, { it.onPause() }, { it.onPause(); it.onResume() })) {
            val (a, l, w) = attempt()
            a.tap(button)
            prime(a)
            a.tap(button)
            assertEquals(1, l.requests.size)
            assertEquals(1, w.names().count { it == "widget_add_tapped" })
        }
        val (a, l, w) = inHowto()
        a.tap(button)
        assertEquals(1, l.requests.size)
        assertEquals(1, w.names().count { it == "widget_howto_shown" })
    }

    @Test fun `a callback with another attempt's token is dropped`() {
        val (a, _, w) = away()
        a.onCallback("token-of-a-dead-attempt")
        a.onCallback(null)
        a.onResume()
        assertEquals(WidgetPinAttempt.State.GRACE, a.state)
        assertTrue(w.advances.isEmpty())
    }

    @Test fun `DONE is terminal for its token`() {
        val (a, _, w) = attempt()
        a.tap(button)
        val token = a.liveToken
        a.onCallback(token)
        a.onCallback(token)
        a.onResume()
        a.onDoneCta()
        assertEquals(listOf("added"), w.advances)
    }

    @Test fun `destroy discards a live attempt in every state - no status, no advance, timers cancelled`() {
        val primes = listOf<(WidgetPinAttempt, World) -> Unit>(
            { _, _ -> },
            { a, _ -> a.onPause() },
            { a, _ -> a.onPause(); a.onResume() },
        )
        for (prime in primes) {
            val (a, _, w) = attempt()
            a.tap(button)
            prime(a, w)
            val before = w.events.size
            a.onDestroy()
            assertEquals(WidgetPinAttempt.State.IDLE, a.state)
            assertNull(a.liveToken)
            assertTrue(w.timers.all { it.cancelled })
            assertEquals(before, w.events.size)
            assertTrue(w.advances.isEmpty())
        }
        val (a, _, w) = inHowto()
        a.onDestroy()
        a.onDoneCta()
        assertTrue(w.advances.isEmpty())
    }

    @Test fun `existing_count is the id set at the tap and a widget already present still runs the attempt`() {
        val (a, l, w) = attempt(Launcher(ids = setOf(1, 2)))
        a.tap(button)
        assertEquals(2, w.props("widget_add_tapped")["existing_count"])
        assertEquals(1, l.requests.size)
        assertEquals(WidgetPinAttempt.State.WAITING_DIALOG, a.state)
    }

    @Test fun `ids that cannot be read leave detection unavailable and Done is not added`() {
        val (a, _, w) = inHowto(Launcher(result = "false", ids = null))
        assertFalse(w.props("widget_add_tapped").containsKey("existing_count"))
        a.onDoneCta()
        assertEquals("unavailable", w.props("widget_not_added")["detection"])
        assertEquals(listOf("not_added"), w.advances)
    }

    companion object {
        const val PROVIDER = "com.example.host.widget.StreakWidget"
    }
}
