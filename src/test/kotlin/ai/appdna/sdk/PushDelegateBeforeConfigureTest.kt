package ai.appdna.sdk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A push delegate registered before `configure` must be the one `configure`'s push manager calls.
 *
 * `PushModule.setDelegate` was `manager?.pushListener = delegate` — a null-safe DROP before
 * `configure` creates the `PushTokenManager`, and `shutdown` nulls the manager's listener, so a host
 * that set the delegate first (the documented order on iOS, and the order the Flutter plugin uses
 * when Dart listens before `configure`) got no `onPushReceived` / `onPushTapped` for the whole
 * session, while `AppDNA.pushDelegate` still returned it. iOS keeps `AppDNA.pushDelegate` across
 * configure and shutdown; Android now holds it in the module and applies it to every new manager.
 *
 * Drives the REAL `AppDNA.configure` / `AppDNA.shutdown`.
 * NEGATIVE CONTROL: with `setDelegate` back to `manager?.pushListener = delegate` and no setter on
 * `manager`, the first two tests fail (`pushListener` is null after configure).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PushDelegateBeforeConfigureTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val delegate = object : AppDNAPushDelegate {}

    @Before
    fun setUp() {
        AppDNA.shutdown()
        AppDNA.push.setDelegate(null)
    }

    @After
    fun tearDown() {
        AppDNA.push.setDelegate(null)
        AppDNA.shutdown()
    }

    @Test
    fun `a delegate set before configure is the one the configured push manager calls`() {
        assertNull("precondition: no push manager before configure", AppDNA.push.manager)
        AppDNA.push.setDelegate(delegate)

        AppDNA.configure(context, "adn_test_push_delegate")

        val manager = AppDNA.push.manager
        assertNotNull("configure creates the push manager", manager)
        assertSame("onPushReceived goes through the manager's listener", delegate, manager!!.pushListener)
        assertSame("onPushTapped goes through delegate()", delegate, AppDNA.push.delegate())
    }

    @Test
    fun `the delegate survives shutdown and is applied again by the next configure`() {
        AppDNA.configure(context, "adn_test_push_delegate")
        AppDNA.push.setDelegate(delegate)
        assertSame(delegate, AppDNA.push.manager!!.pushListener)

        AppDNA.shutdown()
        assertNull(AppDNA.push.manager)

        AppDNA.configure(context, "adn_test_push_delegate")
        assertSame("re-configure must re-apply the host's delegate", delegate, AppDNA.push.manager!!.pushListener)
        assertSame(delegate, AppDNA.push.delegate())
    }

    @Test
    fun `clearing the delegate before configure leaves the configured manager without one`() {
        AppDNA.push.setDelegate(delegate)
        AppDNA.push.setDelegate(null)

        AppDNA.configure(context, "adn_test_push_delegate")

        assertNull(AppDNA.push.manager!!.pushListener)
        assertNull(AppDNA.push.delegate())
    }

    @Test
    fun `the top-level pushDelegate property reaches the configured manager too`() {
        AppDNA.pushDelegate = delegate
        try {
            AppDNA.configure(context, "adn_test_push_delegate")
            assertSame(delegate, AppDNA.push.manager!!.pushListener)
        } finally {
            AppDNA.pushDelegate = null
        }
    }
}
