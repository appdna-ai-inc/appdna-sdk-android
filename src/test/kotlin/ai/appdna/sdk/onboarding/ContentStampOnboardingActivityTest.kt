package ai.appdna.sdk.onboarding

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.billing.BillingTestPipeline
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.storage.LocalStorage
import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * SPEC-536 D1, through the REAL onboarding activity: a served flow with `version: 7` and
 * `served_from: {id: "src_1"}` stamps `content_version` and `content_source_id` on EVERY event it emits —
 * the manager's (started, step viewed / completed, flow completed) and the ones written deep inside a
 * step, which only know the flow by its id (a step sheet opened and closed reads the stamp from
 * `LocalContentStamp`). The flow is presented by `AppDNA.presentOnboarding`, and the buttons are tapped
 * as a finger does. The shared fixture `content_stamp_onboarding_flow_events` covers the manager's
 * closures without composing; this covers the composition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ContentStampOnboardingActivityTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var events: BillingTestPipeline
    private val controllers = mutableListOf<ActivityController<*>>()

    private val sheetStep = mapOf(
        "id" to "step_terms", "type" to "custom",
        "config" to mapOf(
            "content_blocks" to listOf(
                mapOf("id" to "b_t", "type" to "text", "text" to "Read the terms"),
                mapOf("id" to "b_open", "type" to "button", "text" to "Open terms", "action" to "open_sheet", "sheet_id" to "sh_terms"),
                mapOf("id" to "b_go", "type" to "button", "text" to "Continue", "action" to "next", "zone" to "bottom"),
            ),
            "sheets" to listOf(mapOf(
                "id" to "sh_terms",
                "content_blocks" to listOf(
                    mapOf("id" to "b_st", "type" to "text", "text" to "Terms body"),
                    mapOf("id" to "b_close", "type" to "button", "text" to "Close terms", "action" to "close_sheet"),
                ),
            )),
        ),
    )
    private val endStep = mapOf(
        "id" to "step_end", "type" to "custom",
        "config" to mapOf("content_blocks" to listOf(
            mapOf("id" to "b_e", "type" to "text", "text" to "All set"),
            mapOf("id" to "b_done", "type" to "button", "text" to "Finish", "action" to "next", "zone" to "bottom"),
        )),
    )

    private fun seed(servedFrom: Map<String, Any>?) {
        val flow = mutableMapOf<String, Any>(
            "id" to FLOW, "name" to "Stamp probe", "version" to 7,
            "settings" to mapOf("allow_back" to true),
            "steps" to listOf(sheetStep, endStep),
        )
        servedFrom?.let { flow["served_from"] = it }
        LocalStorage(ctx).setString(
            "cache_onboarding",
            JSONObject(mapOf("active_flow_id" to FLOW, "flows" to mapOf(FLOW to flow))).toString(),
        )
    }

    @After
    fun tearDown() {
        for (c in controllers.asReversed()) {
            if (!c.get().isDestroyed) runCatching { c.pause().stop().destroy() }
        }
        controllers.clear()
        idle()
        AppDNA.installOnboardingForTest(null, ready = false)
        OnboardingActivity.clearActiveLaunches()
        PendingCompletionRoute.clear()
        LocalStorage(ctx).setString("cache_onboarding", "{}")
        events.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun settle() {
        idle(); compose.mainClock.advanceTimeBy(1_500); idle(); compose.waitForIdle()
    }

    /** Present, open and close the sheet, continue, finish: the whole flow, by taps. */
    private fun walk(): List<Pair<String, JSONObject>> {
        events = BillingTestPipeline(ctx, "content_stamp_onboarding")
        AppDNA.installOnboardingForTest(
            OnboardingFlowManager(RemoteConfigManager(null, LocalStorage(ctx), 3600L), events.tracker), ready = true,
        )
        val host = Robolectric.buildActivity(Activity::class.java).setup().also { controllers += it }.get()
        assertTrue(AppDNA.presentOnboarding(host, FLOW))
        Robolectric.buildActivity(OnboardingActivity::class.java).setup().also { controllers += it }
        settle()
        compose.onNode(hasText("Open terms")).performClick(); settle()
        compose.onNode(hasText("Close terms")).performClick(); settle()
        compose.onNode(hasText("Continue")).performClick(); settle()
        compose.onNode(hasText("Finish")).performClick(); settle()
        return events.events().filter { it.second.optString("flow_id") == FLOW }
    }

    private val expectedNames = listOf(
        "onboarding_flow_started",
        "onboarding_step_viewed",
        "onboarding_sheet_opened",
        "onboarding_sheet_closed",
        "onboarding_step_completed",
        "onboarding_step_viewed",
        "onboarding_step_completed",
        "onboarding_flow_completed",
    )

    @Test
    fun `a swapped flow stamps content_version and content_source_id on every event, the step sheet's included`() {
        seed(mapOf("id" to "src_1", "version" to 3))
        val flowEvents = walk()
        for (name in expectedNames.toSet()) {
            assertTrue("the walk emitted $name — got ${flowEvents.map { it.first }}", flowEvents.any { it.first == name })
        }
        for ((name, props) in flowEvents) {
            assertEquals("$name content_version", 7, props.opt("content_version"))
            assertEquals("$name content_source_id", "src_1", props.opt("content_source_id"))
        }
        assertEquals("flow_version stays on onboarding_flow_started", 7,
            flowEvents.first { it.first == "onboarding_flow_started" }.second.opt("flow_version"))
    }

    @Test
    fun `a flow that is not swapped stamps content_version and no content_source_id`() {
        seed(null)
        val flowEvents = walk()
        assertTrue("the walk emitted the sheet events — got ${flowEvents.map { it.first }}",
            flowEvents.any { it.first == "onboarding_sheet_opened" })
        for ((name, props) in flowEvents) {
            assertEquals("$name content_version", 7, props.opt("content_version"))
            assertTrue("$name carries no content_source_id", !props.has("content_source_id"))
        }
    }

    private companion object {
        const val FLOW = "flow_stamp_probe"
    }
}
