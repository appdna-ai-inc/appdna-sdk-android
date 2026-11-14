package ai.appdna.sdk.onboarding

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The band of the real date wheel shows the value the wheel stores: a pick past a bound snaps back to the clamped date,
 * a wheel with nothing to show opens inside its bounds, and a stored year is shown on return.
 * THE CONTROL: a seeded date inside the bounds is shown as seeded, so the readers are not reading a constant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class DateWheelBandTest {
    @get:Rule
    val compose = createComposeRule()

    private val inputs = mutableMapOf<String, Any>()

    private fun show(mode: String = "date", default: String? = null, min: String? = null, max: String? = null) {
        val block = ContentBlock(id = "wheel", type = "date_wheel_picker", field_id = "born", picker_mode = mode,
            default_date_value = default, min_date = min, max_date = max, field_config = mapOf("picker_variant" to "wheel"))
        compose.setContent { DateWheelPickerBlock(block, inputs) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun column(name: String): SemanticsNodeInteraction = compose.onNodeWithTag("date_wheel.$name")
    private fun band(name: String): String = column(name).fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    private fun bandDate() = listOf(band("year"), band("month"), band("day"))

    @Test fun aMonthDraggedBelowTheMinimumSnapsBackToIt() {
        show(default = "2000-03-15", min = "2000-03-15", max = "2025-12-31")
        compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("date_wheel.month")))
            .performTouchInput { swipeDown(startY = top + 10f, endY = bottom - 10f, durationMillis = 600) }
        compose.mainClock.advanceTimeBy(3000)
        compose.waitForIdle()
        assertEquals("2000-03-15", inputs["born"])
        assertEquals(listOf("2000", "Mar", "15"), bandDate())
    }

    @Test fun aWheelWithNothingToShowOpensInsideItsBounds() {
        show(min = "2000-03-15", max = "2000-12-31")
        assertEquals(listOf("2000", "Dec", "31"), bandDate())
        assertNull("nothing is stored before the user moves the wheel", inputs["born"])
    }

    @Test fun aStoredYearIsShownOnReturn() {
        inputs["born"] = "2003"
        show(mode = "year")
        assertEquals("2003", band("year"))
        assertEquals("2003", inputs["born"])
    }

    /**
     * A TAPPED row (a finger, or TalkBack's double-tap on the row it focused, which is a click) is stored AND
     * glides into the band. It used to be stored while its drum stayed put: tapping "Nov" on a wheel at Dec 31 stored
     * 2025-11-30 under a band reading Dec 30, so a TalkBack user heard one date and saved another.
     */
    @Test fun aTappedRowMovesIntoTheBandAndIsStored() {
        show(default = "2025-12-31", min = "2000-03-15", max = "2025-12-31")
        compose.onNode(hasText("Nov") and hasAnyAncestor(hasTestTag("date_wheel.month"))).performClick()
        compose.mainClock.advanceTimeBy(3000)
        compose.waitForIdle()
        assertEquals("2025-11-30", inputs["born"])
        assertEquals(listOf("2025", "Nov", "30"), bandDate())
        compose.onNode(hasText("2024") and hasAnyAncestor(hasTestTag("date_wheel.year"))).performClick()
        compose.mainClock.advanceTimeBy(3000)
        compose.waitForIdle()
        assertEquals("2024-11-30", inputs["born"])
        assertEquals(listOf("2024", "Nov", "30"), bandDate())
    }

    @Test fun aTappedTimeRowMovesIntoTheBand() {
        show(mode = "datetime", default = "2025-06-15T10:00:00Z")
        val hour = compose.onNodeWithTag("date_wheel.hour")
        val before = hour.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        val next = ((before.toInt() + 1) % 24).toString().padStart(2, '0')
        compose.onNode(hasText(next) and hasAnyAncestor(hasTestTag("date_wheel.hour"))).performClick()
        compose.mainClock.advanceTimeBy(3000)
        compose.waitForIdle()
        assertEquals(next, hour.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }

    @Test fun theControlASeededDateIsShownAsSeeded() {
        show(default = "2000-06-15", min = "2000-03-15", max = "2025-12-31")
        assertEquals(listOf("2000", "Jun", "15"), bandDate())
        assertEquals("2000-06-15", inputs["born"])
    }
}
