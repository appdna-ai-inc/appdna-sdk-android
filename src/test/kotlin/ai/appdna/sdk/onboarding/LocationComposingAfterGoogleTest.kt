package ai.appdna.sdk.onboarding

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.billing.BillingTestPipeline
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.storage.LocalStorage
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
/**
 * The first edit after a Google answer's line, still composing (an input method's composing span, as
 * Gboard holds a word), is the user's own text the moment the step moves on or the process ends: Continue
 * submits it, and the saved state keeps it, instead of the Google answer the field showed. The restart
 * rule applies: Google's line is never stored; the stored text is the user's query plus the edit.
 *
 * The onboarding activity is real (Robolectric). The composition goes through the field's real input
 * connection (`setComposingText`), so the field reports a composing span exactly as an IME's does. Each
 * surface is covered: the `input_location` block and the form step's Location field. The controls are a
 * composing edit on a typed (non-Google) answer, which was always kept, and a rotation, where the commit
 * arrives as the field leaves. All place data is synthetic.
 */
class LocationComposingAfterGoogleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var events: BillingTestPipeline
    private val controllers = mutableListOf<ActivityController<*>>()
    private val FLOW = "composing_probe_flow"

    private val first = mapOf("id" to "s_first", "type" to "welcome", "config" to mapOf("title" to "First"))
    private val locationStep = mapOf(
        "id" to "s_loc", "type" to "custom",
        "config" to mapOf("content_blocks" to listOf(
            mapOf("id" to "b_loc", "type" to "input_location", "field_id" to "where",
                "field_config" to mapOf("location_type" to "address", "location_min_chars" to 3)),
        )),
    )
    private val locationFormStep = mapOf(
        "id" to "s_locform", "type" to "form",
        "config" to mapOf("fields" to listOf(mapOf("id" to "where", "type" to "location", "label" to "Where")), "cta_text" to "Next please"),
    )
    private val locationStepWithButton = mapOf(
        "id" to "s_locbtn", "type" to "custom",
        "config" to mapOf("content_blocks" to listOf(
            mapOf("id" to "b_loc", "type" to "input_location", "field_id" to "where",
                "field_config" to mapOf("location_type" to "address", "location_min_chars" to 3)),
            mapOf("id" to "b_go", "type" to "button", "text" to "Next please", "action" to "next"),
        )),
    )
    private val endStep = mapOf("id" to "s_end", "type" to "welcome", "config" to mapOf("title" to "End"))
    private val googleAnswer = mapOf(
        "formatted_address" to "Synthetic Line 1, Town", "address" to "Synthetic Line 1, Town",
        "place_id" to "place_synthetic", "raw_query" to "typed q",
    )
    private val noRows = object : GeocodeTransport {
        override suspend fun post(url: String, headers: Map<String, String>, body: String): GeocodeHttpReply =
            GeocodeHttpReply(200, """{"data":{"suggestions":[]}}""")
    }
    @Before fun setUp() {
        events = BillingTestPipeline(ctx, "location_composing_after_google")
        LocalStorage(ctx).setString("cache_onboarding", JSONObject(mapOf("active_flow_id" to FLOW, "flows" to mapOf(FLOW to mapOf(
            "id" to FLOW, "name" to "Composing probe", "version" to 1, "steps" to listOf(first, locationStep, locationFormStep, locationStepWithButton, endStep),
        )))).toString())
    }

    @After fun tearDown() {
        for (c in controllers.asReversed()) { if (!c.get().isDestroyed) runCatching { c.pause().stop().destroy() } }
        controllers.clear(); idle()
        AppDNA.installOnboardingForTest(null, ready = false)
        OnboardingActivity.clearActiveLaunches(); PendingCompletionRoute.clear()
        LocalStorage(ctx).setString("cache_onboarding", "{}")
        locationGeocodeTransport = LiveGeocodeTransport
        events.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun manager() = OnboardingFlowManager(RemoteConfigManager(null, LocalStorage(ctx), 3600L), events.tracker)
    private fun vmOf(a: OnboardingActivity) = ViewModelProvider(a)[OnboardingViewModel::class.java]
    private fun present(i: Int): ActivityController<OnboardingActivity> {
        AppDNA.installOnboardingForTest(manager(), ready = true)
        val host = Robolectric.buildActivity(Activity::class.java).setup().also { controllers += it }.get()
        assertTrue(AppDNA.presentOnboarding(host, FLOW))
        val c = Robolectric.buildActivity(OnboardingActivity::class.java).setup().also { controllers += it }
        idle(); vmOf(c.get()).currentIndex.intValue = i; idle(); compose.waitForIdle(); return c
    }
    private fun field(): SemanticsNodeInteraction = compose.onAllNodes(hasSetTextAction())[0]
    private fun texts() = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.EditableText)?.text ?: "" }
    private fun editor(v: View): View? {
        if (v.onCheckIsTextEditor()) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) editor(v.getChildAt(i))?.let { return it }
        return null
    }
    private fun kill(c: ActivityController<OnboardingActivity>): Bundle {
        val saved = Bundle(); c.saveInstanceState(saved).pause().stop().destroy()
        val p = Parcel.obtain()
        val r = try { p.writeBundle(saved); p.setDataPosition(0); p.readBundle(OnboardingActivity::class.java.classLoader)!! } finally { p.recycle() }
        OnboardingActivity.clearActiveLaunches(); PendingCompletionRoute.clear(); AppDNA.installOnboardingForTest(null, ready = false)
        return r
    }
    private fun restore(saved: Bundle): ActivityController<OnboardingActivity> {
        AppDNA.installOnboardingForTest(manager(), ready = true)
        val c = Robolectric.buildActivity(OnboardingActivity::class.java).setup(saved).also { controllers += it }
        idle(); compose.waitForIdle(); return c
    }
    private fun openGoogle(i: Int, stepId: String): ActivityController<OnboardingActivity> {
        locationGeocodeTransport = noRows
        val c = present(0); val vm = vmOf(c.get())
        vm.responses[stepId] = mapOf("where" to googleAnswer)
        vm.currentIndex.intValue = i; idle(); compose.waitForIdle()
        assertEquals(listOf("Synthetic Line 1, Town"), texts())
        return c
    }
    private fun composeX(c: ActivityController<OnboardingActivity>) {
        field().performSemanticsAction(SemanticsActions.RequestFocus); compose.waitForIdle()
        val ic = editor(c.get().window.decorView)!!.onCreateInputConnection(EditorInfo())!!
        compose.runOnUiThread { ic.setComposingText("x", 1) }; compose.waitForIdle()
        assertEquals("composing shows", listOf("Synthetic Line 1, Townx"), texts())
    }


    private fun continueWhileComposing(i: Int, stepId: String): String {
        val c = openGoogle(i, stepId); composeX(c)
        compose.onAllNodes(hasText("Next please", ignoreCase = true), useUnmergedTree = true)[0].performClick()
        compose.mainClock.advanceTimeBy(1000); idle(); compose.waitForIdle()
        val vm = vmOf(c.get())
        return "index=" + vm.currentIndex.intValue + " submitted=" + (vm.responses[stepId] as? Map<*, *>)?.get("where")
    }

    @Test fun continueOnTheBlockSubmitsTheComposingEditAfterAGoogleLine() {
        assertEquals("index=4 submitted=typed qx", continueWhileComposing(3, "s_locbtn"))
    }

    @Test fun continueOnTheFormStepSubmitsTheComposingEditAfterAGoogleLine() {
        assertEquals("index=3 submitted=typed qx", continueWhileComposing(2, "s_locform"))
    }

    @Test fun aProcessDeathOnTheBlockKeepsTheComposingEditAfterAGoogleLine() {
        val c = openGoogle(1, "s_loc"); composeX(c)
        restore(kill(c))
        assertEquals(listOf("typed qx"), texts())
    }

    @Test fun aProcessDeathOnTheFormStepKeepsTheComposingEditAfterAGoogleLine() {
        val c = openGoogle(2, "s_locform"); composeX(c)
        restore(kill(c))
        assertEquals(listOf("typed qx"), texts())
    }

    /** Control: a rotation while composing; the commit arrives as the field leaves. */
    @Test fun aRotationWhileComposingAfterAGoogleLineKeepsTheEdit() {
        val c = openGoogle(1, "s_loc"); composeX(c)
        c.recreate(); idle(); compose.waitForIdle()
        assertEquals(listOf("typed qx"), texts())
    }

    /** Control: a composing edit on a typed (non-Google) answer is submitted with its text. */
    @Test fun continueWhileComposingAfterTypedTextSubmitsIt() {
        locationGeocodeTransport = noRows
        val c = present(0); val vm = vmOf(c.get())
        vm.responses["s_locbtn"] = mapOf("where" to "typed q")
        vm.currentIndex.intValue = 3; idle(); compose.waitForIdle()
        field().performSemanticsAction(SemanticsActions.RequestFocus); compose.waitForIdle()
        val ic = editor(c.get().window.decorView)!!.onCreateInputConnection(EditorInfo())!!
        compose.runOnUiThread { ic.setComposingText("x", 1) }; compose.waitForIdle()
        compose.onAllNodes(hasText("Next please", ignoreCase = true), useUnmergedTree = true)[0].performClick()
        compose.mainClock.advanceTimeBy(1000); idle(); compose.waitForIdle()
        assertEquals("index=4 submitted=typed qx", "index=" + vm.currentIndex.intValue + " submitted=" + (vm.responses["s_locbtn"] as? Map<*, *>)?.get("where"))
    }

    /** Control: Continue with no edit submits the Google answer as it is. */
    @Test fun continueWithoutAnEditSubmitsTheGoogleAnswer() {
        val c = openGoogle(3, "s_locbtn")
        compose.onAllNodes(hasText("Next please", ignoreCase = true), useUnmergedTree = true)[0].performClick()
        compose.mainClock.advanceTimeBy(1000); idle(); compose.waitForIdle()
        val submitted = (vmOf(c.get()).responses["s_locbtn"] as? Map<*, *>)?.get("where") as? Map<*, *>
        assertEquals("Synthetic Line 1, Town", submitted?.get("formatted_address"))
    }
}
