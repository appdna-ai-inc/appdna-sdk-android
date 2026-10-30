package ai.appdna.sdk.feedback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AN UNRECOGNISED FREQUENCY MUST NOT BE THE MOST PERMISSIVE OPTION.
 *
 * The console's enum carried `recurring`, which neither SDK implements. iOS decodes an unknown
 * frequency to `.once` (`MessageFrequency(rawValue:) ?? .once`); this tracker answered `else ->
 * true`. So the same published survey was shown ONCE EVER on iOS and on EVERY trigger event here —
 * 7 live rows when it was measured.
 *
 * `once` is the console's documented default, so an unrecognised value now behaves as if the author
 * had set nothing, on both platforms. Nothing is shown more often than the author could have asked
 * for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SurveyFrequencyFallbackTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun tracker() = SurveyFrequencyTracker(ctx)

    @Test fun `an unrecognised frequency behaves as once, not as every time`() {
        val t = tracker()
        // control: before anything is shown, every frequency permits the first display — so the
        // assertion below is about the SECOND, not about a tracker that refuses everything.
        assertTrue(t.canShow("s1", "recurring", null))
        assertTrue(t.canShow("s2", "every_time", null))

        t.recordDisplay("s1")
        t.recordDisplay("s2")

        // 🔴 THE DEFECT: this used to be true, which is `every_time` behaviour for a value the SDK
        // does not understand.
        assertFalse("an unknown frequency must not repeat", t.canShow("s1", "recurring", null))
        // control: the frequency that really does mean "every time" still does.
        assertTrue("every_time must still repeat", t.canShow("s2", "every_time", null))
    }

    @Test fun `the four real frequencies behave as documented`() {
        val t = tracker()
        assertTrue(t.canShow("once", "once", null))
        t.recordDisplay("once")
        assertFalse(t.canShow("once", "once", null))

        assertTrue(t.canShow("ops", "once_per_session", null))
        t.recordDisplay("ops")
        assertFalse(t.canShow("ops", "once_per_session", null))
        t.resetSession()
        assertTrue("a new session restores once_per_session", t.canShow("ops", "once_per_session", null))

        // max_times counts displays against its cap, and is unbounded without one.
        assertTrue(t.canShow("cap", "max_times", 2))
        t.recordDisplay("cap")
        assertTrue(t.canShow("cap", "max_times", 2))
        t.recordDisplay("cap")
        assertFalse(t.canShow("cap", "max_times", 2))
        assertTrue("no cap means no limit", t.canShow("cap", "max_times", null))
    }
}
