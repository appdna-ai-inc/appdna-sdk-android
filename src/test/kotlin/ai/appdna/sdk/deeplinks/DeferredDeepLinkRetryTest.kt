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
 * A TRANSPORT FAILURE MUST NOT SPEND THE ONE CHANCE.
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

    /**
     * Put a visitor id where Strategy 1 will find it.
     *
     * 🔴 SEEDED, NOT LEFT TO THE FALLBACK. `resolveVisitorId()` ends at
     * `Settings.Secure.ANDROID_ID`, which Robolectric may not provide — and a null visitor id takes
     * a DIFFERENT branch that marks launched deliberately (nothing was asked because there was
     * nothing to ask about). Without this the test would exercise that branch and read as a
     * failure of the one under test. The clipboard route is also the real first-launch path: the
     * web page writes it before the store redirect.
     */
    private fun seedVisitorId() {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("", "appdna:visitor:v-test-1234"),
        )
    }

    /**
     * One check, driven to its callback.
     *
     * 🔴 The manager hops to `Dispatchers.IO` to resolve the visitor id, so the continuation is not
     * on the main looper's queue yet when the call returns. A single `idleMainLooper()` therefore
     * sees nothing pending and the callback appears never to fire — which is what the first version
     * of this test reported. Idle in a loop until the latch trips instead.
     */
    private fun check(): Boolean {
        seedVisitorId()
        val latch = java.util.concurrent.CountDownLatch(1)
        DeferredDeepLinkManager(ctx, "org", "app", null).checkDeferredDeepLink { latch.countDown() }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (latch.await(20, java.util.concurrent.TimeUnit.MILLISECONDS)) return true
        }
        return false
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
