package ai.appdna.sdk.onboarding

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The `linear` measurement style: a vertical slider on the left, the value and its one, locked unit on the right.
 * Operated, not only drawn: the slider is tapped at its top, dragged to its bottom, and set through its accessibility
 * action, and each time the stored answer and the drawn value are read back. One unit means no unit toggle.
 *
 * THE CONTROL: the same block as `ruler` draws no slider, so finding the slider is the style being honoured.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class MeasurementLinearTest {
    @get:Rule
    val compose = createComposeRule()

    private val inputs = mutableMapOf<String, Any>()
    private val commits = mutableListOf<String?>()

    private fun show(style: String = "linear") {
        val cfg = mapOf<String, Any>(
            "measurement_type" to "height", "measurement_style" to style, "measurement_default" to 160,
            "units" to listOf(mapOf("id" to "cm", "label" to "cm", "min" to 100, "max" to 220, "step" to 1, "decimals" to 0, "factor" to 1, "offset" to 0)),
        )
        val block = ContentBlock(id = "h", type = "wheel_picker", field_id = "height", field_config = cfg)
        val config = parseMeasurementConfig(block) ?: error("measurement mode must resolve")
        compose.setContent { MeasurementWheelBlock(block, config, inputs) { _, _, v -> commits.add(v) } }
        compose.waitForIdle()
    }

    private fun slider() = compose.onNodeWithTag("measurement.linear")
    private fun drawn() = slider().fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    @Test fun opensOnTheDefaultWithTheUnitLocked() {
        show()
        assertEquals("160 cm", drawn())
        assertEquals(160L, inputs["height"])
        assertEquals("cm", inputs["height_unit"])
        assertEquals("one unit: no toggle — the only clickable thing is nothing", 0,
            compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        assertTrue("no commit at first render", commits.isEmpty())
    }

    @Test fun aTapAtTheTopPicksTheMaximumAndADragToTheBottomTheMinimum() {
        show()
        slider().performTouchInput { click(Offset(centerX, 1f)) }
        compose.waitForIdle()
        assertEquals(220L, inputs["height"])
        assertEquals("220 cm", drawn())
        slider().performTouchInput { swipe(Offset(centerX, top + 40f), Offset(centerX, bottom - 1f), 400) }
        compose.waitForIdle()
        assertEquals(100L, inputs["height"])
        assertEquals("100 cm", drawn())
        assertEquals("one commit per gesture", listOf("220", "100"), commits)
    }

    @Test fun theAccessibilityActionSetsAQuarter() {
        show()
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        compose.waitForIdle()
        assertEquals(130L, inputs["height"])
        assertEquals("130 cm", drawn())
    }

    @Test fun theControlTheRulerDrawsNoSlider() {
        show("ruler")
        assertTrue(compose.onAllNodes(hasTestTag("measurement.linear")).fetchSemanticsNodes().isEmpty())
        assertTrue("the ruler still draws the value", compose.onAllNodes(androidx.compose.ui.test.hasText("160")).fetchSemanticsNodes().isNotEmpty())
        assertEquals(160L, inputs["height"])
    }
}
