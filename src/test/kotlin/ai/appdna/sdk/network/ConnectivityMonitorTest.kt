package ai.appdna.sdk.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

/**
 * One network lost is not all of them. `onLost` used to set NONE for ANY network the system reported lost,
 * so Wi-Fi dropping while cellular was up made the SDK think it was offline: an adaptive batch size of 0
 * (no uploads) and no "network is back" until some later capabilities change. It now re-reads the active
 * network.
 *
 * NEGATIVE CONTROL (build Mac): `onLost` setting `ConnectionType.NONE` again → the first test fails
 * (NONE, batch size 0).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectivityMonitorTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun activate(type: Int) {
        shadowOf(cm).setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, NetworkInfo.State.CONNECTED)
        )
    }

    private fun caps(transport: Int): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also {
        shadowOf(it).addTransportType(transport)
        shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    @Test fun wifiLostWhileCellularIsUpStaysOnlineWithTheCellularBatchSize() {
        activate(ConnectivityManager.TYPE_WIFI)
        val wifi = cm.activeNetwork!!
        shadowOf(cm).setNetworkCapabilities(wifi, caps(NetworkCapabilities.TRANSPORT_WIFI))
        val monitor = ConnectivityMonitor(ctx)
        try {
            assertEquals(ConnectivityMonitor.ConnectionType.WIFI, monitor.currentConnectionType)
            assertEquals(100, monitor.adaptiveBatchSize)

            // Wi-Fi goes; cellular is (now) the active network.
            activate(ConnectivityManager.TYPE_MOBILE)
            val cellular = cm.activeNetwork!!
            assertTrue("the test needs two distinct networks", cellular != wifi)
            shadowOf(cm).setNetworkCapabilities(cellular, caps(NetworkCapabilities.TRANSPORT_CELLULAR))
            monitor.networkCallbackForTest.onLost(wifi)

            assertTrue("Wi-Fi lost while cellular is up made the SDK offline", monitor.isConnected)
            assertEquals(ConnectivityMonitor.ConnectionType.CELLULAR, monitor.currentConnectionType)
            assertEquals("the cellular batch size", 50, monitor.adaptiveBatchSize)
        } finally {
            monitor.shutdown()
        }
    }

    @Test fun theLastNetworkLostIsOffline() {
        activate(ConnectivityManager.TYPE_WIFI)
        val wifi = cm.activeNetwork!!
        shadowOf(cm).setNetworkCapabilities(wifi, caps(NetworkCapabilities.TRANSPORT_WIFI))
        val monitor = ConnectivityMonitor(ctx)
        try {
            // The system can still name the lost network as active for a moment: it counts as gone.
            monitor.networkCallbackForTest.onLost(wifi)
            assertFalse(monitor.isConnected)
            assertEquals(0, monitor.adaptiveBatchSize)

            shadowOf(cm).setDefaultNetworkActive(false)
            monitor.networkCallbackForTest.onLost(wifi)
            assertEquals(ConnectivityMonitor.ConnectionType.NONE, monitor.currentConnectionType)
        } finally {
            monitor.shutdown()
        }
    }
}
