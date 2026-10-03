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

    /**
     * The networks the system has reported through the callback (capabilities included), so a loss can settle on
     * what is still up even when the system has not switched its default network yet — at `onLost` time
     * `activeNetwork` can still be null, or still the lost network, while cellular is connected. Guarded by itself.
     */
    private val reported = LinkedHashMap<Network, NetworkCapabilities>()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        // `onCapabilitiesChanged` always follows `onAvailable` (API 26+), so the regain is reported there.
        // The callback reports EVERY network with internet — a cellular network that stays up in the background
        // while Wi-Fi is the default, a VPN, a second SIM. Only the default network (the one uploads use) decides the
        // type: a change to another network is recorded (a loss can settle on it later) but changes nothing now. It
        // used to apply whichever network changed last, so a background cellular update made a Wi-Fi device
        // "cellular" (smaller batches) until the next Wi-Fi callback.
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val wasNone = currentConnectionType == ConnectionType.NONE
            synchronized(reported) { reported[network] = capabilities }
            readActiveNetwork(changed = network to capabilities)
            Log.debug("Network changed: $currentConnectionType, metered=$isMetered")
            regainedIfWasNone(wasNone && currentConnectionType != ConnectionType.NONE)
        }

        // One network lost is not necessarily all of them: Wi-Fi dropping while cellular is up leaves the
        // device online. Settle on what is still up (NONE only when nothing is) instead of setting NONE for any
        // loss — which emptied the batch size and stopped uploads while online.
        override fun onLost(network: Network) {
            synchronized(reported) { reported.remove(network) }
            readActiveNetwork(lost = network)
            Log.debug("Network lost — now $currentConnectionType")
        }
    }

    /** Set the state from one network's capabilities (the one mapping every path uses). */
    private fun apply(capabilities: NetworkCapabilities) {
        currentConnectionType = typeOf(capabilities)
        isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Set the state from the active (default) network ([lost]: a network just reported lost, which the system may
     * still name as active for a moment — it counts as gone; [changed]: a network whose capabilities the callback just
     * reported — when it is the default, those capabilities are the newest). When the system names no usable active
     * network, the best network the callback reported that is still up (Wi-Fi / wired over cellular) decides; NONE
     * only when there is none.
     */
    private fun readActiveNetwork(lost: Network? = null, changed: Pair<Network, NetworkCapabilities>? = null) {
        val current: Network? = try { connectivityManager.activeNetwork } catch (_: Throwable) { null }
        val activeNetwork = current?.takeIf { it != lost }
        val capabilities = activeNetwork?.let { active -> changed?.takeIf { it.first == active }?.second }
            ?: activeNetwork?.let {
                try { connectivityManager.getNetworkCapabilities(it) } catch (_: Throwable) { null }
            } ?: activeNetwork?.let { synchronized(reported) { reported[it] } }
            ?: bestReported(except = lost)
        if (capabilities != null) apply(capabilities) else currentConnectionType = ConnectionType.NONE
    }

    /** The best network the callback reported, other than [except]: Wi-Fi / wired before cellular. */
    private fun bestReported(except: Network?): NetworkCapabilities? {
        val candidates = synchronized(reported) { reported.filterKeys { it != except }.values.toList() }
        return candidates.minByOrNull { if (typeOf(it) == ConnectionType.CELLULAR) 1 else 0 }
    }

    /** Test seam: the callback the system calls. */
    internal val networkCallbackForTest: ConnectivityManager.NetworkCallback get() = networkCallback

    init {
        // Check current state
        readActiveNetwork()

        // Register for updates
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    companion object {
        /**
         * The connection type of a connected network — the ONE mapping the callback and the active-network read
         * both use: Wi-Fi or Ethernet → WIFI; cellular → CELLULAR; any other connected transport (VPN, Bluetooth,
         * USB, unknown) → WIFI, as iOS `NetworkMonitor` treats "wired or other connected interface". A network
         * that carries Wi-Fi or Ethernet and cellular (rare) is WIFI.
         */
        internal fun typeOf(capabilities: NetworkCapabilities): ConnectionType = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.CELLULAR
            else -> ConnectionType.WIFI
        }
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
        synchronized(reported) { reported.clear() }
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
            // Already unregistered
        }
    }
}
