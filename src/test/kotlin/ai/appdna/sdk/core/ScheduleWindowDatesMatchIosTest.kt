package ai.appdna.sdk.core

import ai.appdna.sdk.messages.MessageManager
import ai.appdna.sdk.screens.AutoTriggerEngine

import ai.appdna.sdk.screens.ScreenIndexEntry
import ai.appdna.sdk.screens.ScreenManager

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * The schedule windows (`start_date` / `end_date`) read dates as iOS does:
 *  - screens and auto-triggers: `ISO8601.date(from:)` only — an instant WITH its zone; anything else is
 *    nil, and a nil date is no constraint;
 *  - in-app messages: that, or EXACTLY `yyyy-MM-dd` at UTC midnight (iOS `MessageManager.parseWindowDate`,
 *    a non-lenient `DateFormatter`).
 * Android fell back to `SimpleDateFormat` after `IsoInstant` returned null: a zone-less instant was read
 * in the device's time zone (screens) or cut to its date (messages, `parse` reads a prefix), an impossible
 * date rolled over (lenient), and the auto-trigger engine turned a bad date into 0 — so a bad `end_date`
 * hid the screen for ever where iOS shows it.
 * NEGATIVE CONTROL: with the three `SimpleDateFormat` fallbacks back, every test here fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScheduleWindowDatesMatchIosTest {

    private val zoneLessFuture = "2999-01-01T00:00:00"
    private val zoneLessPast = "2000-01-01T00:00:00"

    private fun entry(start: String? = null, end: String? = null) = ScreenIndexEntry(
        id = "s1", name = "s1",
        triggerRules = UnifiedTriggerRules(events = listOf(EventTrigger(eventName = "go"))),
        startDate = start, endDate = end,
    )

    private fun shown(e: ScreenIndexEntry) =
        AutoTriggerEngine().evaluate(listOf(e), "go", null, emptyMap(), 1, 0, null)

    @Test
    fun `auto-trigger - a bad or zone-less window date is no constraint`() {
        assertEquals("bad end_date", "s1", shown(entry(end = "not a date")))
        assertEquals("zone-less end_date in the past", "s1", shown(entry(end = zoneLessPast)))
        assertEquals("zone-less start_date in the future", "s1", shown(entry(start = zoneLessFuture)))
        // A zoned date still constrains.
        assertNull(shown(entry(start = "2999-01-01T00:00:00Z")))
        assertNull(shown(entry(end = "2000-01-01T00:00:00.000Z")))
    }

    @Test
    fun `screens - only a zoned instant parses`() {
        val sm = ScreenManager.shared
        assertNull(sm.parseIsoToEpochMs(zoneLessFuture))
        assertNull(sm.parseIsoToEpochMs("2026-05-07"))
        assertEquals(1_778_148_000_000L, sm.parseIsoToEpochMs("2026-05-07T10:00:00Z"))
    }

    @Test
    fun `in-app messages - zoned instant, or exactly yyyy-MM-dd at UTC midnight, else nothing`() {
        val saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        try {
            val mm = MessageManager(ApplicationProvider.getApplicationContext(), { emptyMap() })
            assertEquals(1_778_148_000_000L, mm.parseWindowDate("2026-05-07T10:00:00Z")?.time)
            assertEquals("date-only is UTC midnight (iOS keeps this)", 1_778_112_000_000L, mm.parseWindowDate("2026-05-07")?.time)
            assertNull("zone-less instant: not its date prefix", mm.parseWindowDate("2026-05-07T10:00:00"))
            assertNull("an impossible date does not roll over", mm.parseWindowDate("2026-02-30"))
            assertNull(mm.parseWindowDate("garbage"))
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test
    fun `IsoInstant date-only helper takes the whole string only`() {
        assertEquals(1_778_112_000_000L, IsoInstant.parseDateOnlyUtcMs("2026-05-07"))
        assertNull(IsoInstant.parseDateOnlyUtcMs("2026-05-07x"))
        assertNull(IsoInstant.parseDateOnlyUtcMs("2026-13-01"))
    }
}
