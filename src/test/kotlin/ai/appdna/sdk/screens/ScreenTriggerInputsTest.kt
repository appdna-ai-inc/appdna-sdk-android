package ai.appdna.sdk.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Days since install, as a screen's `days_since_install` trigger reads it: whole days, never negative, null when unknown. */
class ScreenTriggerInputsTest {
    private val day = 86_400_000L
    private val install = 1_700_000_000_000L

    @Test fun wholeDaysRoundDown() {
        assertEquals(0, ScreenTriggerInputs.daysSinceInstall(install, install + day - 1))
        assertEquals(1, ScreenTriggerInputs.daysSinceInstall(install, install + day))
        assertEquals(7, ScreenTriggerInputs.daysSinceInstall(install, install + 7 * day + 5))
    }

    @Test fun aClockBeforeTheInstallIsDayZero() = assertEquals(0, ScreenTriggerInputs.daysSinceInstall(install, install - day))

    @Test fun anUnknownInstallIsUnknown() = assertNull(ScreenTriggerInputs.daysSinceInstall(null, install))
}
