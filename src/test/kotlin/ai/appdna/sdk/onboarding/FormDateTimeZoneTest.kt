package ai.appdna.sdk.onboarding

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import ai.appdna.sdk.AppDNA
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * The `input_date` / `input_time` / `input_datetime` field shows a saved answer in the shapes the date wheel stores: a
 * time in the app's zone (UTC when none is set, where it used to show in the phone's zone), and a date as its calendar day,
 * west of UTC included. All three were wrong before; the pure read-back case is the reader on its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class FormDateTimeZoneTest {
    @get:Rule
    val compose = createComposeRule()

    private val inputs = mutableMapOf<String, Any>()
    private var savedZone: String? = null
    private val deviceZone = TimeZone.getDefault()

    @Before fun save() { savedZone = AppDNA.appTimeZoneId; TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu")) }
    @After fun restore() { AppDNA.appTimeZoneId = savedZone; TimeZone.setDefault(deviceZone) }

    private fun show(type: String, mode: String, saved: String, zone: String?) {
        AppDNA.appTimeZoneId = zone
        inputs["f"] = saved
        val block = ContentBlock(id = "f", type = type, field_id = "f", field_config = mapOf("time_format" to "24h", "picker_variant" to "compact"))
        compose.setContent { FormInputDateBlock(block, inputs, mode) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
    }

    @Test fun aTimeShowsInTheAppZone() {
        show("input_time", "time", "2026-11-10T14:03:00Z", "Asia/Tokyo")
        compose.onAllNodesWithText("23:03").assertCountEquals(1)
    }

    @Test fun withNoAppZoneATimeShowsInUtc() {
        show("input_time", "time", "2026-11-10T14:03:00Z", null)
        compose.onAllNodesWithText("14:03").assertCountEquals(1)
    }

    @Test fun anOlderDateAnswerShowsItsDayWestOfUtc() {
        // Stored by an earlier release as the picked day's UTC midnight; west of UTC that moment is the evening before.
        show("input_date", "date", "2000-03-15T00:00:00Z", null)
        compose.onAllNodesWithText("Mar 15, 2000").assertCountEquals(1)
    }

    @Test fun anOlderDateAnswerReadsAsTheDay() {
        val c = DateWheelModel.readFormAnswer("2000-03-15T00:00:00Z", "date", TimeZone.getTimeZone("Pacific/Honolulu"))!!
        assertEquals(listOf(2000, 3, 15), listOf(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH)))
    }
}
