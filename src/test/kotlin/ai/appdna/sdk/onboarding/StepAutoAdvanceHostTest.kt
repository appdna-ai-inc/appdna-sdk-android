package ai.appdna.sdk.onboarding

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Advance by itself after", through the real parser and the real flow host. The shared fixture
 * pins the policy; this pins that the policy is ever consulted: a splash carrying the timer moves
 * to the next step with no input, in the shape a console save publishes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class StepAutoAdvanceHostTest {

    @get:Rule
    val compose = createComposeRule()

    private fun heading(id: String, text: String) = mapOf<String, Any>("id" to id, "type" to "heading", "text" to text)

    private fun flowOf(splash: Map<String, Any>): OnboardingFlowConfig = OnboardingConfigParser.parseFlowForTest(
        "f",
        mapOf(
            "id" to "f", "name" to "f", "version" to 1, "settings" to emptyMap<String, Any>(),
            "steps" to listOf(
                splash,
                mapOf("id" to "next", "type" to "content", "content_blocks" to listOf(heading("h2", "Second screen"))),
            ),
        ),
    )

    private fun exists(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun host(flow: OnboardingFlowConfig) {
        compose.setContent {
            OnboardingFlowHost(
                flow = flow, viewModel = null, delegate = null,
                onStepViewed = { _, _ -> }, onStepCompleted = { _, _, _ -> }, onStepSkipped = { _, _ -> },
                onFlowCompleted = {}, onFlowDismissed = { _, _ -> },
            )
        }
        compose.waitForIdle()
    }

    private fun assertAdvancesAfter(splash: Map<String, Any>, millis: Long) {
        host(flowOf(splash))
        assertTrue("the splash must draw first", exists("Splash screen"))
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(millis - 400)
        assertTrue("it must still be on the splash before its delay", exists("Splash screen"))
        compose.mainClock.advanceTimeBy(1500)
        repeat(60) { compose.mainClock.advanceTimeByFrame() }
        assertTrue("the next step must appear with no input", exists("Second screen"))
    }

    private val blocks = listOf(heading("h1", "Splash screen"))

    @Test
    fun `a timer at the step root advances`() = assertAdvancesAfter(
        mapOf("id" to "s", "type" to "splash", "content_blocks" to blocks, "auto_advance_seconds" to 2), 2000,
    )

    @Test
    fun `a timer only in layout, as an older editor save wrote it, advances`() = assertAdvancesAfter(
        mapOf("id" to "s", "type" to "splash", "content_blocks" to blocks, "layout" to mapOf("auto_advance_seconds" to 2.0)), 2000,
    )

    @Test
    fun `a timer composes with tap anywhere`() = assertAdvancesAfter(
        mapOf("id" to "s", "type" to "splash", "content_blocks" to blocks, "auto_advance_seconds" to 2, "layout" to mapOf("tap_anywhere_advances" to true, "auto_advance_seconds" to 2)), 2000,
    )

    @Test
    fun `a screen step with next_step_rules advances`() = assertAdvancesAfter(
        mapOf("id" to "s", "type" to "screen", "content_blocks" to blocks, "auto_advance_seconds" to 2, "next_step_rules" to listOf(mapOf("condition" to "always", "target_step_id" to "next"))), 2000,
    )
}
