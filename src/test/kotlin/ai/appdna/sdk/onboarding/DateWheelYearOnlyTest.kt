package ai.appdna.sdk.onboarding

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Year-only mode through the real date wheel: only the year column is offered, and a tapped year is stored as four digits.
 * THE CONTROL: date mode shows the month column, so a pass is not a harness that cannot see months.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class DateWheelYearOnlyTest {
    @get:Rule
    val compose = createComposeRule()

    private val inputs = mutableMapOf<String, Any>()
    private fun show(mode: String) {
        val block = ContentBlock(id = "wheel", type = "date_wheel_picker", field_id = "born", picker_mode = mode, default_date_value = "2000-06-15")
        compose.setContent { DateWheelPickerBlock(block, inputs) }
        compose.mainClock.advanceTimeBy(500)
    }

    @Test fun yearModeOffersNoMonths() {
        show("year")
        compose.onAllNodesWithText("Jan").assertCountEquals(0)
        compose.onAllNodesWithText("Jun").assertCountEquals(0)
    }

    @Test fun yearModeStoresFourDigits() {
        show("year")
        assertEquals("2000", inputs["born"])
        compose.onNodeWithText("2001").performClick()
        compose.waitForIdle()
        assertEquals("2001", inputs["born"])
    }

    @Test fun theControlDateModeShowsMonths() {
        show("date")
        compose.onAllNodesWithText("Jun").assertCountEquals(1)
        assertEquals("2000-06-15", inputs["born"])
    }
}
