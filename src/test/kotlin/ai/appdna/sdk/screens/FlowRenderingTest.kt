package ai.appdna.sdk.screens

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Environment
import ai.appdna.sdk.events.ClientSeqCounter
import ai.appdna.sdk.events.DroppedEventsCounter
import ai.appdna.sdk.events.EventQueue
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.storage.EventDatabase
import ai.appdna.sdk.storage.LocalStorage
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.collections.immutable.toImmutableList
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 🔴 ANDROID MULTI-SCREEN SDUI FLOWS DID NOT RENDER. AT ALL.
 *
 * `ScreenManager.showFlow(flowId, callback)` — with a perfectly VALID, fully cached config — emitted
 * `flow_started`, constructed an EMPTY `FlowResult` (completed=false), invoked the host's callback
 * with it, emitted `flow_abandoned`, and returned. It launched no Activity and rendered no pixel. Its
 * own comment admitted it: "Without a real FlowManager running…". `FlowManager` — a real 21-verb
 * router, sitting right there in the same package — was never instantiated anywhere in `src/main`.
 *
 * iOS builds one and presents it (`Screens/ScreenManager.swift:230`). So a console-authored
 * multi-screen flow worked on iOS and was DEAD on Android — including for every React Native and
 * Flutter host, which reach the same method. And the host was told the flow had finished.
 *
 * These tests drive the REAL entry point (`AppDNA.showFlow`) into the REAL FlowManager and read back
 * the envelopes a REAL EventTracker → EventQueue → EventDatabase produced.
 *
 * Falsification (revert `ScreenManager.showFlow` to the empty-result version):
 *   - `showFlow presents a host Activity…`         RED — no Activity is ever started.
 *   - `showFlow does not fabricate a completion`   RED — callback fires instantly, `flow_abandoned`.
 *   - `flow_completed reports the real outcome`    RED — the flow can't be driven; nothing completes.
 *   - `navigation rules and set_response…`         RED — responses stay empty, no routing happens.
 *   - `a flow emits exactly one terminal event`    RED — `flow_abandoned` is emitted at start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FlowRenderingTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: EventDatabase
    private lateinit var tracker: EventTracker

    @Before
    fun setUp() {
        startedIntent = null
        val storage = LocalStorage(ctx)
        val identity = ai.appdna.sdk.IdentityManager(storage)
        db = EventDatabase(ctx, 10_000, "flow_rendering_test.db")
        ClientSeqCounter.init(ctx)
        DroppedEventsCounter.init(ctx)
        db.clearAll()
        tracker = EventTracker(identity, "1.0", "sandbox")
        tracker.setEventQueue(
            EventQueue(
                apiClient = ApiClient("adn_test_flow", Environment.PRODUCTION),
                eventDatabase = db,
                connectivityMonitor = null,
                batchSizeCap = 0,
                flushInterval = Long.MAX_VALUE,
            ),
        )
        AppDNA.installEventTrackerForTest(tracker)
        // The presentation path launches an Activity from the application context — only
        // `configure()` sets it in production, which is exactly why no test ever reached this code.
        AppDNA.installApplicationContextForTest(ctx)
        ScreenManager.shared.reset()
    }

    @After
    fun tearDown() {
        AppDNA.installEventTrackerForTest(null)
        AppDNA.installApplicationContextForTest(null)
        ScreenManager.shared.reset()
        ScreenHostActivity.clearActiveLaunches()
        db.clearAll()
    }

    // ---- fixtures ----

    private fun screen(id: String) = ScreenConfig(
        id = id,
        name = id,
        layout = ScreenLayout.fromMap(emptyMap()),
        sections = listOf(
            ScreenSection.fromMap(mapOf("id" to "sec_$id", "type" to "text")),
        ).toImmutableList(),
    )

    /**
     * s1 → s2 → s3, plus a conditional rule on s1: `responses.plan == "pro"` jumps straight to s3.
     * Mirrors the shape the console authors.
     */
    private fun cacheFlow(withRule: Boolean = false) {
        listOf("s1", "s2", "s3").forEach { ScreenManager.shared.cacheScreen(it, screen(it)) }
        val flow = FlowConfig(
            id = "f1",
            name = "Welcome Flow",
            screens = listOf(
                FlowScreenRef(
                    screenId = "s1",
                    navigationRules = if (withRule) listOf(
                        NavigationRule(
                            condition = "when_equals",
                            variable = "responses.plan",
                            value = "pro",
                            target = "s3",
                        ),
                    ) else emptyList(),
                ),
                FlowScreenRef("s2", emptyList()),
                FlowScreenRef("s3", emptyList()),
            ),
            startScreenId = "s1",
            settings = FlowSettings(),
        )
        ScreenManager.shared.cacheFlow("f1", flow)
    }

    private fun events(): List<Pair<String, JSONObject?>> =
        db.loadAll().map { JSONObject(it) }.map { it.getString("event_name") to it.optJSONObject("properties") }

    private fun propsOf(name: String): JSONObject? = events().firstOrNull { it.first == name }?.second

    private fun countOf(name: String) = events().count { it.first == name }

    /** `nextStartedActivity` POPS the queue, so read it once and keep it. */
    private var startedIntent: android.content.Intent? = null

    private fun startedActivityIntent(): android.content.Intent? {
        if (startedIntent == null) {
            startedIntent = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
                .nextStartedActivity
        }
        return startedIntent
    }

    /** The FlowManager the production code handed to the host Activity. */
    private fun presentedFlowManager(): FlowManager? {
        val intent = startedActivityIntent() ?: return null
        val token = intent.getStringExtra(ScreenHostActivity.EXTRA_FLOW_TOKEN) ?: return null
        return ScreenHostActivity.flowManagerForToken(token)
    }

    // ---- (1) it renders ----

    @Test
    fun `showFlow presents a host Activity for the flow`() {
        cacheFlow()
        AppDNA.showFlow("f1")

        val intent = startedActivityIntent()
        assertNotNull("showFlow must PRESENT the flow — no Activity was started", intent)
        assertEquals(
            ScreenHostActivity::class.java.name,
            intent!!.component?.className,
        )
        assertEquals("f1", intent.getStringExtra(ScreenHostActivity.EXTRA_FLOW_ID))
        // …and the host was handed a REAL FlowManager, parked on the configured start screen.
        val fm = presentedFlowManager()
        assertNotNull("no FlowManager was constructed", fm)
        assertEquals("s1", fm!!.currentScreenId)
        assertEquals("s1", fm.currentScreen?.id)
    }

    /** The Activity itself: does `onCreate` wire the flow and stay up (rather than finish)? */
    @Test
    fun `the host Activity binds the FlowManager and stays presented`() {
        cacheFlow()
        AppDNA.showFlow("f1")
        val intent = startedActivityIntent()!!

        val controller = org.robolectric.Robolectric
            .buildActivity(ScreenHostActivity::class.java, intent)
            .create()
        val activity = controller.get()
        assertFalse("flow host finished itself instead of rendering", activity.isFinishing)

        // The flow's terminal transition must close the host.
        presentedFlowManager()!!.handleAction(SectionAction.Complete)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("a completed flow must dismiss its host", activity.isFinishing)
    }

    // ---- (2) it does not lie to the host ----

    @Test
    fun `showFlow does not fabricate a completion`() {
        cacheFlow()
        var result: FlowResult? = null
        AppDNA.showFlow("f1") { result = it }

        assertNull("the callback fired before the user saw a single screen", result)
        assertEquals("no flow may terminate at presentation time", 0, countOf("flow_abandoned"))
        assertEquals(0, countOf("flow_completed"))

        // flow_started matches iOS ScreenManager.swift:249-253 (incl. start_screen_id).
        val started = propsOf("flow_started")
        assertNotNull(started)
        assertEquals("f1", started!!.getString("flow_id"))
        assertEquals("Welcome Flow", started.getString("flow_name"))
        assertEquals("s1", started.getString("start_screen_id"))
    }

    // ---- (3) the outcome is the REAL one ----

    @Test
    fun `flow_completed reports the real outcome after the user walks the flow`() {
        cacheFlow()
        var result: FlowResult? = null
        AppDNA.showFlow("f1") { result = it }
        val fm = presentedFlowManager()!!

        fm.handleAction(SectionAction.Next) // s1 → s2
        assertEquals("s2", fm.currentScreenId)
        fm.handleAction(SectionAction.Next) // s2 → s3
        assertEquals("s3", fm.currentScreenId)
        fm.handleAction(SectionAction.Next) // past the end → complete

        assertNotNull(result)
        assertTrue("the flow ran to the end — it completed", result!!.completed)
        assertEquals("s3", result!!.lastScreenId)
        assertEquals(listOf("s1", "s2", "s3"), result!!.screensViewed)

        assertEquals(1, countOf("flow_completed"))
        assertEquals(0, countOf("flow_abandoned"))
        val done = propsOf("flow_completed")!!
        assertEquals("f1", done.getString("flow_id"))
        assertEquals("Welcome Flow", done.getString("flow_name"))
        assertEquals(3, done.getJSONArray("screens_viewed").length())
        assertTrue(done.has("duration_ms"))
    }

    @Test
    fun `navigation rules and set_response route the user`() {
        cacheFlow(withRule = true)
        var result: FlowResult? = null
        AppDNA.showFlow("f1") { result = it }
        val fm = presentedFlowManager()!!

        fm.handleAction(SectionAction.SetResponse("plan", "pro"))
        fm.handleAction(SectionAction.Next)

        assertEquals("the `plan == pro` navigation rule must skip s2", "s3", fm.currentScreenId)
        fm.handleAction(SectionAction.Complete)

        assertEquals("pro", result!!.responses["plan"])
        assertEquals(listOf("s1", "s3"), result!!.screensViewed)
        assertTrue(result!!.completed)
    }

    @Test
    fun `an abandoned flow emits flow_abandoned once, with the screens actually seen`() {
        cacheFlow()
        var result: FlowResult? = null
        AppDNA.showFlow("f1") { result = it }
        val fm = presentedFlowManager()!!

        fm.handleAction(SectionAction.Next)      // saw s2
        fm.handleAction(SectionAction.Dismiss)   // …then bailed
        fm.dismissFlow()                          // host teardown backstop — must NOT double-emit

        assertFalse(result!!.completed)
        assertEquals(listOf("s1", "s2"), result!!.screensViewed)
        assertEquals(1, countOf("flow_abandoned"))
        assertEquals(0, countOf("flow_completed"))
        assertEquals("s2", propsOf("flow_abandoned")!!.getJSONArray("screens_viewed").getString(1))
    }

    /** A completed flow must not ALSO be reported as abandoned by the Activity teardown backstop. */
    @Test
    fun `a completed flow emits exactly one terminal event`() {
        cacheFlow()
        AppDNA.showFlow("f1")
        val fm = presentedFlowManager()!!
        fm.handleAction(SectionAction.Complete)
        fm.dismissFlow()

        assertEquals(1, countOf("flow_completed"))
        assertEquals(0, countOf("flow_abandoned"))
    }
}
