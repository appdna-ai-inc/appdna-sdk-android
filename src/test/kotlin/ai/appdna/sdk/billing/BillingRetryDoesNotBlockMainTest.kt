package ai.appdna.sdk.billing

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.PurchasesUpdatedListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * #902 — does the billing reconnection backoff BLOCK the main thread?
 *
 * An ANR was seen once on the sample host, and the one named suspect was this loop: five retries
 * with exponential backoff, logged from the main thread's own id, spread across ~13 seconds on a
 * device with no Play billing. An ANR needs about five seconds of blocked input, so the order of
 * magnitude fit.
 *
 * The issue said reading `BillingConnectionManager` would settle it. Reading says it should be
 * fine — the scope is `Dispatchers.Main + SupervisorJob()` and the wait is `delay()`, which
 * SUSPENDS rather than blocks. But "it should be fine" is the sentence that precedes most of this
 * repo's defects, so this executes it instead.
 *
 * The test drives five real connection failures and asserts that, between them, the main looper
 * still runs other work and the clock still advances. If `delay` were `Thread.sleep`, the looper
 * could not idle and these assertions could not pass — on Robolectric a blocked main thread is a
 * hung test, which is the loudest possible failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BillingRetryDoesNotBlockMainTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun `a reconnection backoff schedules on the main looper instead of blocking it`() {
        val listener = PurchasesUpdatedListener { _, _ -> }
        val fake = FakeBillingClient(listener).apply {
            setupCode = BillingClient.BillingResponseCode.BILLING_UNAVAILABLE
        }
        val mgr = BillingConnectionManager(ctx, listener) { _, _ -> fake }

        // Other main-thread work, posted BEFORE the failing connection starts. If the backoff
        // blocked, this would not run until the whole retry budget had elapsed.
        var ranDuringBackoff = false
        Handler(Looper.getMainLooper()).post { ranDuringBackoff = true }

        val startedAt = System.nanoTime()
        mgr.initialize()
        val initializeTookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        // `initialize` must RETURN. The first backoff slot is 1000ms; anything near that means the
        // caller's thread waited.
        assertTrue(
            "initialize() took ${initializeTookMs}ms — the backoff is blocking its caller",
            initializeTookMs < 500,
        )

        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("other main-thread work ran while a retry was pending", ranDuringBackoff)
        assertEquals("no retry has fired yet — the delay has not elapsed", 1, fake.setupAttempts)

        // Advance the clock through the whole budget: 1s, 2s, 4s, 8s, 16s plus jitter — 31 VIRTUAL
        // seconds.
        //
        // 🔴 This wall-clock assertion is the one that distinguishes, and the first version of this
        // test did not have it. `scope.launch { … }` posts to the looper either way, so
        // `initialize()` returns immediately whether the body suspends or sleeps, and a queued
        // Runnable posted beforehand runs first regardless. Both of those assertions pass against a
        // BLOCKING implementation. What cannot pass is finishing 31 virtual seconds of backoff in a
        // fraction of a real second: `delay` lets Robolectric advance its clock, `Thread.sleep`
        // would charge the full 31 seconds to the wall.
        val budgetStartedAt = System.nanoTime()
        shadowOf(Looper.getMainLooper()).idleFor(40, TimeUnit.SECONDS)
        val budgetTookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - budgetStartedAt)

        assertTrue(
            "the backoff should have retried several times; saw ${fake.setupAttempts} attempts",
            fake.setupAttempts > 1,
        )
        assertTrue(
            "31 virtual seconds of backoff cost ${budgetTookMs}ms of REAL time — the retry is "
                + "sleeping on the main thread, not suspending on it. That is the ANR hypothesis.",
            budgetTookMs < 5_000,
        )
    }
}
