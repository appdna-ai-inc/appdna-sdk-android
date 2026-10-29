package ai.appdna.sdk.onboarding

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.billing.BillingTestPipeline
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.storage.LocalStorage
import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
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

/**
 * A block-based step draws no step-level "Skip", even with `skip_enabled`: its Skip is a button block
 * (`action: "skip"`) placed by the author, as the console preview and iOS draw it. A Skip link laid
 * over the step sat on top of a bottom-zone button and took its taps, so tapping Continue skipped the
 * step and its typed answer was never stored.
 *
 * The onboarding activity is real (Robolectric). Continue is tapped as a finger does, at the centre of
 * its bounds through the root (`performClick`), so whatever is drawn on top receives the tap. The
 * controls: a legacy step's Skip still draws and still skips, and a Skip button block still skips.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BlockStepNoSkipTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var events: BillingTestPipeline
    private val controllers = mutableListOf<ActivityController<*>>()

    private val blockStep = mapOf(
        "id" to "s_block", "type" to "custom",
        "config" to mapOf(
            "skip_enabled" to true,
            "content_blocks" to listOf(
                mapOf("id" to "b_in", "type" to "input_text", "field_id" to "name", "field_placeholder" to "Type here"),
                mapOf("id" to "b_go", "type" to "button", "text" to "Continue", "action" to "next", "zone" to "bottom"),
            ),
        ),
    )
    private val skipButtonStep = mapOf(
        "id" to "s_skipbtn", "type" to "custom",
        "config" to mapOf("content_blocks" to listOf(
            mapOf("id" to "b_t", "type" to "text", "text" to "Optional step"),
            mapOf("id" to "b_skip", "type" to "button", "variant" to "text", "text" to "Skip this", "action" to "skip", "zone" to "bottom"),
        )),
    )
    private val legacyStep = mapOf("id" to "s_legacy", "type" to "welcome", "config" to mapOf("title" to "Hello", "skip_enabled" to true))
    private val endStep = mapOf("id" to "s_end", "type" to "welcome", "config" to mapOf("title" to "End"))

    @Before
    fun setUp() {
        events = BillingTestPipeline(ctx, "block_step_no_skip")
        LocalStorage(ctx).setString(
            "cache_onboarding",
            JSONObject(mapOf("active_flow_id" to FLOW, "flows" to mapOf(FLOW to mapOf(
                "id" to FLOW, "name" to "Skip probe", "version" to 1,
                "settings" to mapOf("allow_back" to true),
                "steps" to listOf(blockStep, skipButtonStep, legacyStep, endStep),
            )))).toString(),
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

    private fun present(index: Int): OnboardingViewModel {
        AppDNA.installOnboardingForTest(OnboardingFlowManager(RemoteConfigManager(null, LocalStorage(ctx), 3600L), events.tracker), ready = true)
        val host = Robolectric.buildActivity(Activity::class.java).setup().also { controllers += it }.get()
        assertTrue(AppDNA.presentOnboarding(host, FLOW))
        val c = Robolectric.buildActivity(OnboardingActivity::class.java).setup().also { controllers += it }
        idle(); compose.waitForIdle()
        val vm = ViewModelProvider(c.get())[OnboardingViewModel::class.java]
        vm.currentIndex.intValue = index
        idle(); compose.waitForIdle()
        return vm
    }

    private fun skipShown(): Boolean =
        compose.onAllNodes(hasText("Skip"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a block step with skip_enabled draws no Skip, and Continue stores the answer and advances`() {
        val vm = present(0)
        val skipDrawn = skipShown()
        compose.onNode(hasSetTextAction()).performTextInput("Hello123")
        compose.waitForIdle()
        compose.onNode(hasText("Continue")).performClick()
        settle()
        assertEquals("Continue advanced the step", 1, vm.currentIndex.intValue)
        assertEquals("Continue stored the answer", "Hello123", (vm.responses["s_block"] as? Map<*, *>)?.get("name"))
        assertEquals("no step-level Skip on a block step", false, skipDrawn)

        // Back shows the typed value.
        compose.onNode(hasContentDescription("Previous step")).performClick()
        settle()
        assertEquals(0, vm.currentIndex.intValue)
        assertEquals(listOf("Hello123"), compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
            .map { it.config.getOrNull(SemanticsProperties.EditableText)?.text })
    }

    @Test
    fun `a Skip button block still skips (control)`() {
        val vm = present(1)
        compose.onNode(hasText("Skip this")).performClick()
        settle()
        assertEquals("the Skip button block advanced past the step", 2, vm.currentIndex.intValue)
        assertEquals("a skipped step stores no answer", null, vm.responses["s_skipbtn"])
    }

    @Test
    fun `a legacy step keeps its Skip, and it skips (control)`() {
        val vm = present(2)
        assertTrue("a legacy step with skip_enabled draws Skip", skipShown())
        compose.onNode(hasText("Skip")).performClick()
        settle()
        assertEquals("Skip advanced past the legacy step", 3, vm.currentIndex.intValue)
    }

    private companion object {
        const val FLOW = "block_skip_probe"
    }
}
