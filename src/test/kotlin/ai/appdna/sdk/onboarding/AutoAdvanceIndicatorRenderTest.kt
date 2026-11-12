package ai.appdna.sdk.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The countdown on a self-advancing step, driven by the real [AutoAdvanceIndicatorOverlay] on
 * the Compose frame clock: the label ticks 3 → 2 → 1 and the bar shortens as the step's 3 s run out.
 *
 * THE CONTROL: the same step without `show: true` draws no indicator at all — the default every
 * published flow has — so a harness that cannot find the node fails the first test, not passes it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-mdpi")
class AutoAdvanceIndicatorRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun step(indicator: Map<String, Any>?): OnboardingStep {
        val layout = mutableMapOf<String, Any>("auto_advance_seconds" to 3)
        if (indicator != null) layout["auto_advance_indicator"] = indicator
        return OnboardingConfigParser.parseStepForTest(mapOf("id" to "splash", "type" to "splash", "layout" to layout))
            ?: error("the step did not parse")
    }

    /** The step host's own arrangement: the TOP placement is a row above the content, BOTTOM is over it. */
    private fun show(s: OnboardingStep) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Column(Modifier.size(400.dp, 800.dp).background(Color.White)) {
                    AutoAdvanceIndicatorOverlay(step = s, placement = "top")
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Text("Step heading", modifier = Modifier.testTag("heading"))
                        AutoAdvanceIndicatorOverlay(step = s, placement = "bottom")
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun barWidth(): Float = compose.onNodeWithTag("auto_advance_indicator_bar").getBoundsInRoot().let { (it.right - it.left).value }

    @Test
    fun theLabelTicksDownAndTheBarEmpties() {
        show(step(mapOf("show" to true, "text" to "Continuing in {seconds}")))
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("auto_advance_indicator_label").assertTextEquals("Continuing in 3")
        val w0 = barWidth()
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithTag("auto_advance_indicator_label").assertTextEquals("Continuing in 2")
        val w1 = barWidth()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("auto_advance_indicator_label").assertTextEquals("Continuing in 1")
        val w2 = barWidth()
        assertTrue("the bar did not shorten: $w0 → $w1 → $w2", w0 > w1 && w1 > w2)
        // 352dp track (400 − 2 × 24). At 0.1 s ~ 97% left; at 1.2 s 60%; at 2.2 s ~ 27%.
        assertEquals(352f * 0.6f, w1, 4f)
    }

    @Test
    fun controlWithoutShowNothingIsDrawn() {
        show(step(null))
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("auto_advance_indicator").assertDoesNotExist()
    }

    @Test
    fun controlShowFalseNothingIsDrawn() {
        show(step(mapOf("show" to false)))
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("auto_advance_indicator").assertDoesNotExist()
    }

    @Test
    fun theTextStyleHasNoBarAndTheBarStyleNoText() {
        show(step(mapOf("show" to true, "style" to "text")))
        compose.onNodeWithTag("auto_advance_indicator_label").assertExists()
        compose.onNodeWithTag("auto_advance_indicator_bar").assertDoesNotExist()
    }

    @Test
    fun theBarStyleHasNoText() {
        show(step(mapOf("show" to true, "style" to "bar", "position" to "top")))
        compose.onNodeWithTag("auto_advance_indicator_bar").assertExists()
        compose.onNodeWithTag("auto_advance_indicator_label").assertDoesNotExist()
        // At the top of the 800dp surface, not the bottom.
        val top = compose.onNodeWithTag("auto_advance_indicator").getBoundsInRoot().top.value
        assertTrue("a top indicator drew at $top", top < 100f)
    }

    /**
     * A countdown at the TOP takes its own space: the step's first block starts BELOW it. (On iOS it was
     * painted over the heading.) The control is the bottom placement, which leaves the heading at the top.
     */
    @Test
    fun aTopCountdownSitsAboveTheContentNotOverIt() {
        show(step(mapOf("show" to true, "style" to "text", "text" to "Next in {seconds}s", "position" to "top")))
        compose.mainClock.advanceTimeBy(100)
        val indicator = compose.onNodeWithTag("auto_advance_indicator").getBoundsInRoot()
        val heading = compose.onNodeWithTag("heading").getBoundsInRoot()
        assertTrue("the heading (${heading.top}) starts inside the countdown (${indicator.bottom})", heading.top.value >= indicator.bottom.value - 0.5f)
    }

    @Test
    fun controlABottomCountdownLeavesTheContentWhereItWas() {
        show(step(mapOf("show" to true, "style" to "text")))
        compose.mainClock.advanceTimeBy(100)
        assertEquals(0f, compose.onNodeWithTag("heading").getBoundsInRoot().top.value, 0.5f)
    }
}
