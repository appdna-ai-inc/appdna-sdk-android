package ai.appdna.sdk.onboarding

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Date Picker's "Picker Style" and "Selection" decide what it draws, and the range calendar is operated: two days
 * tapped and the stored answer read back, the year list opened from the header and a year picked.
 * THE CONTROL: a `wheel` style still draws the wheel's columns, so the calendar is the style being honoured.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class DatePickerStyleTest {
    @get:Rule
    val compose = createComposeRule()

    private val inputs = mutableMapOf<String, Any>()

    private fun show(cfg: Map<String, Any>, default: String? = "2026-11-05", min: String? = "2026-01-01", max: String? = "2027-12-31") {
        val block = ContentBlock(id = "d", type = "date_wheel_picker", field_id = "trip", picker_mode = "date",
            default_date_value = default, min_date = min, max_date = max, field_config = cfg)
        compose.setContent { DateWheelPickerBlock(block, inputs) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test fun theCalendarStyleDrawsNoWheel() {
        show(mapOf("picker_variant" to "graphical"))
        assertFalse("graphical drew the wheel", exists("date_wheel.year"))
    }

    @Test fun theControlTheWheelStyleDrawsTheWheel() {
        show(mapOf("picker_variant" to "wheel"))
        assertTrue(exists("date_wheel.year"))
    }

    @Test fun aRangeIsPickedWithTwoTaps() {
        show(mapOf("selection_mode" to "range"))
        assertFalse(exists("date_wheel.year"))
        compose.onNodeWithTag("date_range.day.2026-11-07").performClick()
        compose.waitForIdle()
        assertEquals("2026-11-07", inputs["trip_start"])
        assertNull("a started range is not an answer", inputs["trip"])
        compose.onNodeWithTag("date_range.day.2026-11-15").performClick()
        compose.waitForIdle()
        assertEquals("2026-11-07/2026-11-15", inputs["trip"])
        assertEquals(9, inputs["trip_days"])
    }

    @Test fun theHeaderOpensTheYearsAndAYearJumpsThere() {
        show(mapOf("selection_mode" to "range"))
        compose.onNodeWithTag("date_range.header").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("date_range.year.2027").performClick()
        compose.waitForIdle()
        assertTrue("November 2027 is shown", exists("date_range.day.2027-11-01"))
        assertFalse(exists("date_range.day.2026-11-01"))
    }

    @Test fun aDayOutsideTheBoundsCannotBePicked() {
        show(mapOf("selection_mode" to "range"), default = "2026-01-10", min = "2026-01-05")
        compose.onNodeWithTag("date_range.day.2026-01-02").performClick()
        compose.waitForIdle()
        assertNull(inputs["trip_start"])
    }
}
