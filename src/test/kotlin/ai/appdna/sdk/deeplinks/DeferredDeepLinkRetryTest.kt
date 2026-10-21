package ai.appdna.sdk.deeplinks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * #1063 — A TRANSPORT FAILURE MUST NOT SPEND THE ONE CHANCE.
 *
 * A deferred deep link is resolvable exactly once, on the first launch ever. `markLaunched()` used
 * to run on every path out of the fetch, the failure listener and the missing-Firestore branch
 * included — so a user who installed from a link and opened the app with no network lost the
 * destination and the referrer permanently, and silently.
 *
 * `AppDNA.firestoreDB` is null in a unit test, which is exactly the "nothing was asked" branch, so
 * this drives the real manager through the real `SharedPreferences` with no mocking at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DeferredDeepLinkRetryTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun prefs() = ctx.getSharedPreferences("ai.appdna.sdk", Context.MODE_PRIVATE)

    private val firstLaunchKey = "ai.appdna.sdk.first_launch_completed"
    private val attemptsKey = "ai.appdna.sdk.ddl_failed_attempts"

    @Before fun clean() { prefs().edit().clear().commit() }

    /** One check, run to completion. */
    private fun check(): Boolean {
        var called = false
        DeferredDeepLinkManager(ctx, "org", "app", null).checkDeferredDeepLink { called = true }
        ShadowLooper.idleMainLooper()
        return called
    }

    @Test fun `a failed check keeps the chance for the next launch, and gives up after four`() {
        // Attempts 1..3: the chance survives, and the count is what survives it.
        for (n in 1..DeferredDeepLinkManager.MAX_FAILED_ATTEMPTS - 1) {
            assertTrue("the host must always get its callback", check())
            assertFalse(
                "attempt $n failed without reaching the server — the chance must survive it",
                prefs().getBoolean(firstLaunchKey, false),
            )
            assertEquals("attempt $n must be counted", n, prefs().getInt(attemptsKey, 0))
        }

        // The fourth gives up, so a permanently offline device stops reading Firestore.
        assertTrue(check())
        assertTrue(
            "after ${DeferredDeepLinkManager.MAX_FAILED_ATTEMPTS} failures the chance IS spent",
            prefs().getBoolean(firstLaunchKey, false),
        )

        /*
         * 🔴 NEGATIVE CONTROL. Everything above is also true of a manager that never marks launched
         * at all — including the "gives up" assertion, if the key were being set by something else.
         * Once the flag is set, a further check must return WITHOUT counting another attempt: that
         * is the early return at the top of `checkDeferredDeepLink`, and it is what distinguishes
         * "the chance is spent" from "the chance was never tracked".
         */
        val countedWhenSpent = prefs().getInt(attemptsKey, -1)
        assertTrue(check())
        assertEquals(
            "a check after the flag is set must not reach the fetch at all",
            countedWhenSpent,
            prefs().getInt(attemptsKey, -1),
        )
    }
}
