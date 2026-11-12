package ai.appdna.sdk.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import ai.appdna.sdk.AppDNA
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A time or date-and-time wheel works in the app's time zone (the console's app setting; UTC when none): it stores the
 * wall time there with the zone's offset, as iOS does. THE CONTROL: a date-only wheel stays a calendar date.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class DateWheelTimeZoneTest {
    @get:Rule
    val compose = createComposeRule()

    private val inputs = mutableMapOf<String, Any>()
    private var savedZone: String? = null

    @Before fun save() { savedZone = AppDNA.appTimeZoneId }
    @After fun restore() { AppDNA.appTimeZoneId = savedZone }

    private fun stored(mode: String, zone: String?, default: String = "2000-06-15"): Any? {
        AppDNA.appTimeZoneId = zone
        val block = ContentBlock(id = "wheel", type = "date_wheel_picker", field_id = "born", picker_mode = mode, default_date_value = default, field_config = mapOf("picker_variant" to "wheel"))
        compose.setContent { DateWheelPickerBlock(block, inputs) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        return inputs["born"]
    }

    @Test fun aDateAndTimeIsStoredAsTheWallTimeInTheAppZone() {
        assertEquals("2000-06-15T00:00:00-04:00", stored("datetime", "America/New_York"))
    }

    @Test fun withNoAppZoneItIsUtc() {
        assertEquals("2000-06-15T00:00:00Z", stored("datetime", null))
    }

    @Test fun aStoredMomentIsReadBackInTheAppZone() {
        inputs["born"] = "2026-11-10T14:03:00Z"
        assertEquals("2026-11-10T15:03:00+01:00", stored("datetime", "Europe/Warsaw"))
    }

    @Test fun theControlADateStaysACalendarDate() {
        assertEquals("2000-06-15", stored("date", "Pacific/Tongatapu"))
    }
}
