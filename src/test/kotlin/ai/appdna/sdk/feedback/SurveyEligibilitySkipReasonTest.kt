package ai.appdna.sdk.feedback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.storage.LocalStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * No SDK computes a love score yet, so the stored value is always 0 and a survey whose `love_score_range` has a
 * minimum above 0 is never shown. It used to be skipped with no trace; the skip now carries a reason, which the
 * trigger loop writes to the debug log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SurveyEligibilitySkipReasonTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun manager() = SurveyManager(ctx, EventTracker(ai.appdna.sdk.IdentityManager(LocalStorage(ctx)), "1.0"))
    private fun rules(range: ScoreRange?, minSessions: Int? = null) =
        SurveyTriggerRules("evt", emptyList(), range, "once", null, null, minSessions)

    @Test fun `a love-score minimum above 0 is skipped, and the reason says the device has no love score`() {
        val reason = manager().eligibilitySkipReason("s1", rules(ScoreRange(40, 100)))
        assertEquals("[Surveys] s1 skipped — love_score_range not met (the device has no love score)", reason)
    }

    @Test fun `no love-score rule, or a range starting at 0, is not a skip (the control)`() {
        assertNull(manager().eligibilitySkipReason("s1", rules(null)))
        assertNull(manager().eligibilitySkipReason("s1", rules(ScoreRange(0, 100))))
    }

    @Test fun `an unmet min_sessions is skipped with its own reason`() {
        assertEquals("[Surveys] s2 skipped — min_sessions not reached", manager().eligibilitySkipReason("s2", rules(null, minSessions = 50)))
    }
}
