package ai.appdna.sdk.feedback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #939 — THE PART OF A REVIEW REQUEST THAT IS OURS, AND IS ASSERTABLE.
 *
 * Neither store reports whether a prompt appeared — deliberately — so a device walk can show the
 * step arriving and the SDK asking, and nothing more. What IS ours is the rate limit: three a
 * year, ninety days apart, kept in `SharedPreferences`. Nothing tested it on either platform.
 *
 * 🔴 #928's class is why it matters. `request_store_review` was decoded and never encoded, so it
 * vanished from the cache on a cold start and a flow asked again every launch. The rate limit is
 * the same shape of state — if it did not survive a restart, a user would be asked on every cold
 * start, which is exactly what the limit exists to prevent.
 *
 * The iOS half is `ReviewPromptRateLimitTests.swift`, asserting the same four things.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewPromptRateLimitTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun prefs() = ctx.getSharedPreferences("ai.appdna.sdk.review", Context.MODE_PRIVATE)

    @Before fun clear() = prefs().edit().clear().commit().let { }
    @After fun cleanUp() = prefs().edit().clear().commit().let { }

    private fun canAsk(): Boolean = ReviewPromptManager.canShowReviewPrompt(ctx)

    @Test
    fun `a fresh install may ask`() {
        assertTrue(canAsk())
    }

    @Test
    fun `the yearly ceiling holds`() {
        prefs().edit().putInt("review_prompt_count", 3).commit()
        assertFalse("three asks is the yearly ceiling — a fourth must be refused", canAsk())

        prefs().edit().putInt("review_prompt_count", 2).commit()
        assertTrue(
            "and the control: two is under the ceiling, so this harness is not refusing everything",
            canAsk(),
        )
    }

    @Test
    fun `ninety days must pass between asks`() {
        val day = 86_400_000L
        prefs().edit().putInt("review_prompt_count", 1).putLong("review_prompt_last_date", System.currentTimeMillis() - 89 * day).commit()
        assertFalse("89 days is inside the 90-day window and must be refused", canAsk())

        prefs().edit().putLong("review_prompt_last_date", System.currentTimeMillis() - 91 * day).commit()
        assertTrue(
            "91 days is outside it — without this the assertion above would pass on a manager "
                + "that refused unconditionally",
            canAsk(),
        )
    }

    /**
     * 🔴 THE COLD-START ASSERTION, which is the one #928 is a warning about.
     *
     * `SharedPreferences` is the store, so the state outlives the process by construction — but
     * "by construction" is what was said about `request_store_review` reaching the cache, and it
     * did not. Reading it back through a FRESH handle is the cheapest thing that would fail if the
     * manager were ever changed to keep this in memory.
     */
    @Test
    fun `the limit survives a cold start`() {
        prefs().edit().putInt("review_prompt_count", 3).putLong("review_prompt_last_date", System.currentTimeMillis()).commit()

        val afterRestart = ctx.getSharedPreferences("ai.appdna.sdk.review", Context.MODE_PRIVATE)
        assertTrue(
            "the ask count must be readable from a new handle — in memory it would be 0 and the "
                + "user would be asked again on the next launch",
            afterRestart.getInt("review_prompt_count", 0) == 3,
        )
        assertFalse(canAsk())
    }
}
