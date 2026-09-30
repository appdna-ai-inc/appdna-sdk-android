package ai.appdna.sdk.billing

import android.content.Context
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.concurrent.thread

/**
 * `destroy()` off Main (`AppDNA.shutdown()` from a background thread) detaches the process-lifecycle
 * observer. `ProcessLifecycleOwner`'s registry rejects `removeObserver` off Main, and the rejection was
 * swallowed, so the old manager's observer stayed registered for the life of the process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForegroundObserverUnregisterTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var sc: BillingScenario? = null

    @After fun tearDown() { sc?.teardown() }

    private fun registry() = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry

    /** NEGATIVE CONTROL: with the removal back inline on the calling thread, the count stays +1. */
    @Test fun `destroy off Main removes the foreground observer`() {
        val s = BillingScenario(ctx).also { sc = it }
        val before = registry().observerCount
        s.mgr.registerForegroundObserver()
        s.idle()
        assertEquals("registered", before + 1, registry().observerCount)

        thread { s.mgr.destroy() }.join()
        s.idle()
        assertEquals("detached after destroy() off Main", before, registry().observerCount)
    }

    @Test fun `destroy on Main removes the foreground observer`() {
        val s = BillingScenario(ctx).also { sc = it }
        val before = registry().observerCount
        s.mgr.registerForegroundObserver()
        s.idle()
        s.mgr.destroy()
        assertEquals(before, registry().observerCount)
    }
}
