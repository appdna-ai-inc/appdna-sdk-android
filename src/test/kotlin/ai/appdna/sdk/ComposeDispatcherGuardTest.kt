package ai.appdna.sdk

import android.os.Looper
import androidx.compose.ui.platform.AndroidUiDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A test that ends with a Compose dispatch still queued must not break the tests after it.
 *
 * The three run in name order in one JVM. `b` leaves a dispatch pending, as a test does whose last
 * Compose work lands after its last looper idle. `c` then needs the main Compose dispatcher. Without
 * [RobolectricTestApplication]'s `afterTest`, Robolectric's reset discards `b`'s message, the
 * dispatcher's "scheduled" flag stays set, and `c`'s coroutine never runs — the same state that made
 * a whole run fail with `AppNotIdleException` in every later Compose test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ComposeDispatcherGuardTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun a_control_the_guard_sees_a_pending_dispatch_and_an_idle_runs_it() {
        var ran = false
        CoroutineScope(AndroidUiDispatcher.Main).launch { ran = true }
        val dispatcher = ComposeMainDispatcher.ifStarted()!!
        assertTrue("a dispatch posted and not yet run must read as pending", dispatcher.hasPendingDispatch())
        idle()
        assertTrue(ran)
        assertFalse("an idle runs it, after which nothing is pending", dispatcher.hasPendingDispatch())
    }

    @Test
    fun b_a_test_that_ends_with_a_dispatch_still_queued() {
        CoroutineScope(AndroidUiDispatcher.Main).launch { }
        assertTrue(ComposeMainDispatcher.ifStarted()!!.hasPendingDispatch())
    }

    @Test
    fun c_the_next_test_can_still_run_compose_work() {
        var ran = false
        CoroutineScope(AndroidUiDispatcher.Main).launch { ran = true }
        idle()
        assertTrue("the main Compose dispatcher is stuck: the previous test's dispatch was discarded", ran)
    }
}
