package ai.appdna.sdk.billing

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.PurchasesUpdatedListener
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `awaitConnectedClient`'s check-then-add race. The connection comes up between the
 * waiter's fast-path `isConnected` check and its registration; the setup dispatch finds an empty list.
 * The waiter must still get the client. NEGATIVE CONTROL: without the under-lock `isConnected` re-check in
 * `awaitConnectedClient`, the callback is parked after the dispatch and the waiter never resumes — this
 * test fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BillingConnectionRaceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun `a connection that comes up between the check and the registration still resumes the waiter`() {
        val listener = PurchasesUpdatedListener { _, _ -> }
        val fake = FakeBillingClient(listener).apply { holdSetup = true }
        val mgr = BillingConnectionManager(ctx, listener) { _, _ -> fake }
        mgr.initialize()
        val held = fake.heldSetupListener!!
        assertEquals(false, mgr.isConnected)

        mgr.beforeWaiterRegistered = {
            mgr.beforeWaiterRegistered = null
            held.onBillingSetupFinished(FakeBillingClient.result(BillingClient.BillingResponseCode.OK))
        }
        val scope = MainScope()
        var got: BillingClient? = null
        var returned = false
        scope.launch { got = mgr.awaitConnectedClient(); returned = true }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("the waiter resumed", true, returned)
        assertSame(fake, got)
        assertEquals("no dead waiter left behind", 0, mgr.pendingWaiterCount)
        scope.cancel()
        mgr.destroy()
    }

    /**
     * `destroy()` runs between the waiter's fast-path read of the client and its
     * registration, so its `resumeWaitersWithNull` finds an empty list. The waiter must still get null at
     * once (the `PriceResolver` waits have no timeout). NEGATIVE CONTROL: without the `destroyed` check in
     * the locked check-and-add, the callback is parked after the release and the waiter never resumes — this
     * test fails.
     */
    @Test fun `a destroy between the check and the registration resumes the waiter with null`() {
        val listener = PurchasesUpdatedListener { _, _ -> }
        val fake = FakeBillingClient(listener).apply { holdSetup = true }
        val mgr = BillingConnectionManager(ctx, listener) { _, _ -> fake }
        mgr.initialize()
        assertEquals(false, mgr.isConnected)

        mgr.beforeWaiterRegistered = {
            mgr.beforeWaiterRegistered = null
            mgr.destroy()
        }
        val scope = MainScope()
        var got: BillingClient? = fake
        var returned = false
        scope.launch { got = mgr.awaitConnectedClient(); returned = true }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("the waiter resumed", true, returned)
        assertNull(got)
        assertEquals("no dead waiter left behind", 0, mgr.pendingWaiterCount)
        scope.cancel()
    }
}
