package ai.appdna.sdk.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import ai.appdna.sdk.Log

/**
 * SPEC-067: Network condition monitoring for adaptive batch sizing.
 * Uses ConnectivityManager.NetworkCallback for real-time updates.
 */
internal class ConnectivityMonitor(context: Context) {

    enum class ConnectionType {
        WIFI, CELLULAR, NONE
    }

    @Volatile
    var currentConnectionType: ConnectionType = ConnectionType.WIFI
        private set

    @Volatile
    var isMetered: Boolean = false
        private set

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Called (on the system's callback thread) when a network comes back after none was available — the
     * SDK retries a failed bootstrap then. Set by `AppDNA.configure()`; null when nobody listens.
     */
    @Volatile
    var onConnectivityRegained: (() -> Unit)? = null

    private fun regainedIfWasNone(wasNone: Boolean) {
        if (!wasNone) return
        try { onConnectivityRegained?.invoke() } catch (e: Throwable) {
            Log.warning("Connectivity-regained listener threw: ${e.message}")
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        // `onCapabilitiesChanged` always follows `onAvailable` (API 26+), so the regain is reported there.
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val wasNone = currentConnectionType == ConnectionType.NONE
            val hasWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val hasCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val hasEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

            currentConnectionType = when {
                hasWifi || hasEthernet -> ConnectionType.WIFI
                hasCellular -> ConnectionType.CELLULAR
                else -> ConnectionType.WIFI // Default to wifi for unknown connected types
            }

            isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            Log.debug("Network changed: $currentConnectionType, metered=$isMetered")
            regainedIfWasNone(wasNone)
        }

        override fun onLost(network: Network) {
            currentConnectionType = ConnectionType.NONE
            Log.debug("Network lost")
        }
    }

    init {
        // Check current state
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        if (capabilities != null) {
            currentConnectionType = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.CELLULAR
                else -> ConnectionType.NONE
            }
            isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } else {
            currentConnectionType = ConnectionType.NONE
        }

        // Register for updates
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    /**
     * Returns the adaptive batch size based on current network conditions.
     * WiFi=100, Cellular=50, Constrained/Metered=20, None=0
     */
    val adaptiveBatchSize: Int
        get() = when (currentConnectionType) {
            ConnectionType.WIFI -> 100
            ConnectionType.CELLULAR -> if (isMetered) 20 else 50
            ConnectionType.NONE -> 0
        }

    /** Whether a network is available now. */
    val isConnected: Boolean get() = currentConnectionType != ConnectionType.NONE

    fun shutdown() {
        onConnectivityRegained = null
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
            // Already unregistered
        }
    }
}
