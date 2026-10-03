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
import org.robolectric.shadows.ShadowNetwork
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

    private fun caps(transport: Int, metered: Boolean = false): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also {
        shadowOf(it).addTransportType(transport)
        shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!metered) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
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

    /**
     * The system has not switched its default network yet when `onLost(wifi)` arrives — `activeNetwork` is null —
     * but the callback already reported cellular. The SDK stays online on cellular.
     * NEGATIVE CONTROL: the previous `readActiveNetwork` (active network only, no reported networks) → NONE, batch 0.
     */
    @Test fun wifiLostBeforeTheSystemSwitchesTheDefaultStaysOnTheReportedCellular() {
        activate(ConnectivityManager.TYPE_WIFI)
        val wifi = cm.activeNetwork!!
        shadowOf(cm).setNetworkCapabilities(wifi, caps(NetworkCapabilities.TRANSPORT_WIFI))
        val monitor = ConnectivityMonitor(ctx)
        try {
            val cellular = ShadowNetwork.newInstance(4242)
            monitor.networkCallbackForTest.onCapabilitiesChanged(wifi, caps(NetworkCapabilities.TRANSPORT_WIFI))
            monitor.networkCallbackForTest.onCapabilitiesChanged(cellular, caps(NetworkCapabilities.TRANSPORT_CELLULAR))

            shadowOf(cm).setActiveNetworkInfo(null) // no default network at the moment of the loss
            monitor.networkCallbackForTest.onLost(wifi)

            assertTrue("Wi-Fi lost before the default switched made the SDK offline", monitor.isConnected)
            assertEquals(ConnectivityMonitor.ConnectionType.CELLULAR, monitor.currentConnectionType)
            assertEquals(50, monitor.adaptiveBatchSize)

            // Then cellular goes too: nothing is left.
            monitor.networkCallbackForTest.onLost(cellular)
            assertEquals(ConnectivityMonitor.ConnectionType.NONE, monitor.currentConnectionType)
            assertEquals(0, monitor.adaptiveBatchSize)
        } finally {
            monitor.shutdown()
        }
    }

    /**
     * A VPN-only network (no Wi-Fi / cellular transport visible) maps the same way through the callback and through
     * the active-network read (startup): connected, WIFI. NEGATIVE CONTROL: the previous active-network read mapped
     * an unknown transport to NONE while the callback mapped it to WIFI → the startup assertion fails.
     */
    @Test fun aVpnNetworkMapsTheSameOnEveryPath() {
        assertEquals(ConnectivityMonitor.ConnectionType.WIFI,
            ConnectivityMonitor.typeOf(caps(NetworkCapabilities.TRANSPORT_VPN)))
        assertEquals(ConnectivityMonitor.ConnectionType.CELLULAR,
            ConnectivityMonitor.typeOf(caps(NetworkCapabilities.TRANSPORT_CELLULAR)))
        assertEquals(ConnectivityMonitor.ConnectionType.WIFI,
            ConnectivityMonitor.typeOf(caps(NetworkCapabilities.TRANSPORT_ETHERNET)))

        // Robolectric only models Wi-Fi / mobile networks: the active network carries VPN-only capabilities.
        activate(ConnectivityManager.TYPE_WIFI)
        val vpn = cm.activeNetwork!!
        shadowOf(cm).setNetworkCapabilities(vpn, caps(NetworkCapabilities.TRANSPORT_VPN))
        val monitor = ConnectivityMonitor(ctx)
        try {
            assertEquals("startup read of a VPN network", ConnectivityMonitor.ConnectionType.WIFI, monitor.currentConnectionType)
            assertTrue(monitor.isConnected)
            monitor.networkCallbackForTest.onCapabilitiesChanged(vpn, caps(NetworkCapabilities.TRANSPORT_VPN))
            assertEquals("callback for the same VPN network", ConnectivityMonitor.ConnectionType.WIFI, monitor.currentConnectionType)
        } finally {
            monitor.shutdown()
        }
    }

    /** Metered cellular, through the callback: the 20-event batch. */
    @Test fun meteredCellularIsTheSmallBatch() {
        shadowOf(cm).setActiveNetworkInfo(null)
        val monitor = ConnectivityMonitor(ctx)
        try {
            assertEquals(ConnectivityMonitor.ConnectionType.NONE, monitor.currentConnectionType)
            monitor.networkCallbackForTest.onCapabilitiesChanged(ShadowNetwork.newInstance(77),
                caps(NetworkCapabilities.TRANSPORT_CELLULAR, metered = true))
            assertEquals(20, monitor.adaptiveBatchSize)
        } finally {
            monitor.shutdown()
        }
    }
}
