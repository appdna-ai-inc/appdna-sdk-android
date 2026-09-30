package ai.appdna.sdk.billing

import android.content.Context
import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.*
import kotlin.random.Random

/**
 * Manages the Google Play Billing connection lifecycle with exponential backoff retry.
 *
 * The BillingClient must be connected before any billing operations can be performed.
 * This manager handles:
 * - Initial connection establishment
 * - Automatic reconnection on disconnection with exponential backoff
 * - Connection state tracking
 * - Graceful shutdown
 */
internal class BillingConnectionManager(
    private val context: Context,
    private val purchasesUpdatedListener: PurchasesUpdatedListener,
    // SPEC-070-A J.23 — injectable factory so unit tests can swap in a fake
    // BillingClient. Default is the production builder which wires
    // PendingPurchasesParams identically to the previous inline implementation.
    private val factory: BillingClientFactory = DefaultBillingClientFactory,
) {
    private var billingClient: BillingClient? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var retryAttempt = 0
    // SPEC-070-A H.14: hard cap on backoff slots — 1s, 2s, 4s, 8s, 16s, then
    // we surface `onBillingUnavailable` to the host delegate and stop. The
    // existing `maxAutoRetries` budget remains as a defense-in-depth limit
    // for the scheduleRetry loop itself (see scheduleRetry below).
    private val backoffSlots = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L)
    private val maxRetryDelay = 60_000L // upper safety bound, keeps any future slot in range
    @Suppress("unused")
    private val baseRetryDelay = 1_000L // retained for reference / tests
    // Cap the bit-shift exponent. (Retained — used by the legacy fallback
    // path when callers reset retryAttempt on user-initiated requests.)
    private val maxShiftExponent = 16
    // After this many consecutive failures we stop reconnecting on our own.
    // The next caller will re-trigger `startConnection()` via withConnectedClient.
    // Prevents the runaway loop seen on devices/emulators with no Play Services.
    private val maxAutoRetries = backoffSlots.size
    @Volatile
    private var retryScheduled = false
    @Volatile
    private var unavailableSurfaced = false

    // SPEC-070-A finalization B-1+B-2 — re-entry guard for startConnection.
    // Without this, four independent paths can each call
    // `client.startConnection(listener)` while a previous attempt's listener
    // hasn't fired yet:
    //   1. initialize()                          — first connect
    //   2. scheduleRetry().launch.delay → start  — backoff retry
        //   4. awaitConnectedClient() if !retryScheduled
    // Play Billing rejects re-entry with the exact log spam the user
    // reported: "Client is already in the process of connecting to billing
    // service." The `retryScheduled` flag previously only guarded path 2.
    // AtomicBoolean.compareAndSet ensures only ONE startConnection() call
    // is in flight at any time, regardless of which path triggered it.
    // The flag is reset in BOTH listener callbacks (success + disconnect)
    // because those are the moments a NEW startConnection() becomes safe.
    private val isConnecting = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    var isConnected = false
        private set

    /** Callbacks waiting for connection readiness. */
    private val pendingCallbacks = mutableListOf<(BillingClient?) -> Unit>()

    /**
     * SPEC-497 round 11 — set by [destroy] under the `pendingCallbacks` lock. A waiter that read the client
     * before `destroy` but registers after its `resumeWaitersWithNull` would park forever (the `PriceResolver`
     * waits have no timeout); the registration checks this flag in the same locked check-and-add instead.
     */
    private var destroyed = false

    /**
     * Initialize the BillingClient and start the connection.
     */
    fun initialize() {
        if (billingClient != null) {
            Log.warning("BillingConnectionManager already initialized")
            return
        }

        // SPEC-070-A A.25 + J.23 — delegate to `factory` so unit tests can sub
        // in a fake BillingClient. Production factory wires the same
        // PendingPurchasesParams (subs always pending; opt in to one-time-
        // product pending purchases).
        billingClient = factory.create(context, purchasesUpdatedListener)

        Log.info("BillingClient created, starting connection...")
        startConnection()
    }

    /**
     * Start or restart the billing connection.
     */
    private fun startConnection() {
        val client = billingClient ?: return

        // SPEC-070-A finalization B-1 — atomic re-entry guard. If a
        // startConnection() is already in flight, return early. The previous
        // attempt's listener will eventually fire and either dispatch
        // pending callbacks or schedule a retry — either way, the work
        // continues without an extra "already in process" rejection from
        // Play Billing.
        if (!isConnecting.compareAndSet(false, true)) {
            Log.debug("BillingConnectionManager.startConnection: skip; already connecting")
            return
        }

        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                // B-1 — release guard FIRST so any racing caller after this
                // point can proceed normally (we're past the in-flight window).
                isConnecting.set(false)
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.info("Billing connection established")
                    retryAttempt = 0

                    // Dispatch pending callbacks. SPEC-497 I4 m7 — `isConnected` flips under the same lock
                    // `awaitConnectedClient` registers under, so a waiter either sees it or is dispatched.
                    // Round 11 — the callbacks run OUTSIDE the lock (copied and cleared under it).
                    val callbacks = synchronized(pendingCallbacks) {
                        isConnected = true
                        val copy = ArrayList(pendingCallbacks)
                        pendingCallbacks.clear()
                        copy
                    }
                    callbacks.forEach { it(client) }
                } else {
                    Log.warning("Billing setup failed: ${result.responseCode} - ${result.debugMessage}")
                    isConnected = false
                    scheduleRetry()
                }
            }

            override fun onBillingServiceDisconnected() {
                // B-1 — release guard so scheduleRetry → startConnection can
                // proceed when the timer fires.
                isConnecting.set(false)
                Log.warning("Billing service disconnected")
                isConnected = false
                scheduleRetry()
            }
        })
    }

    /**
     * SPEC-070-A H.14: jittered exponential reconnect with a hard 5-attempt
     * cap. Slots are 1s/2s/4s/8s/16s + ±20% jitter. After all 5 slots fail
     * we stop scheduling and fire `AppDNABillingDelegate.onBillingUnavailable()`
     * so the host UI can degrade (hide paywalls, show cash-out path, etc.).
     */
    private fun scheduleRetry() {
        if (retryAttempt >= maxAutoRetries) {
            Log.warning(
                "Billing reconnection gave up after $retryAttempt attempts. " +
                    "Surfacing onBillingUnavailable; will retry on next billing operation."
            )
            // SPEC-497 R38 SDK minor 2: release the parked waiters with null BEFORE surfacing, so a
            // restore / purchase waiting on the connection fails now instead of hanging.
            resumeWaitersWithNull()
            surfaceBillingUnavailable()
            return
        }
        if (retryScheduled) return
        retryScheduled = true

        val slot = backoffSlots.getOrElse(retryAttempt) {
            // Defensive — slot index out of range (shouldn't trip given the
            // maxAutoRetries gate above). Use the max slot.
            backoffSlots.last()
        }
        // SPEC-070-A H.14: ±20% jitter prevents thundering-herd reconnects on
        // mass-disconnect events (e.g. Play Services restarts).
        val jitterRange = (slot * 0.20).toLong()
        val jitter = if (jitterRange > 0) Random.nextLong(-jitterRange, jitterRange + 1) else 0L
        val delay = (slot + jitter).coerceIn(0L, maxRetryDelay)
        retryAttempt++
        Log.debug("Scheduling billing reconnection in ${delay}ms (attempt $retryAttempt of $maxAutoRetries)")

        scope.launch {
            delay(delay)
            // SPEC-070-A finalization B-2 — clear retryScheduled BEFORE
            // calling startConnection so that the AtomicBoolean isConnecting
            // is the single source of truth for re-entry. Previously, the
            // window between `retryScheduled = false` and the listener firing
            // allowed a racing `withConnectedClient` caller to invoke
            // startConnection a second time. Now isConnecting.compareAndSet
            // catches the race in startConnection itself.
            retryScheduled = false
            if (!isConnected) {
                startConnection()
            }
        }
    }

    /**
     * SPEC-070-A H.14: notify host once that billing is unavailable. We post
     * to main-thread so host code never runs on the BillingClient callback
     * thread, and we de-dup so reconnects after the gave-up state don't
     * fire the delegate repeatedly.
     */
    private fun surfaceBillingUnavailable() {
        if (unavailableSurfaced) return
        unavailableSurfaced = true
        val delegate = try {
            AppDNA.billing.billingListener
        } catch (_: Throwable) { null } ?: return
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { delegate.onBillingUnavailable() }
                    .onFailure { Log.warning("AppDNABillingDelegate.onBillingUnavailable threw: ${it.message}") }
            }
        } catch (e: Throwable) {
            Log.warning("BillingConnectionManager: onBillingUnavailable fan-out failed: ${e.message}")
        }
    }

    /**
     * Execute a suspend operation when the billing client is connected.
     * Suspends the caller until the client is ready.
     *
     * @return The connected BillingClient, or null if not initialized.
     */
    suspend fun awaitConnectedClient(): BillingClient? {
        val client = billingClient ?: return null
        if (isConnected) return client

        return suspendCancellableCoroutine { continuation ->
            val callback: (BillingClient?) -> Unit = { connectedClient ->
                continuation.resume(connectedClient) {}
            }
            beforeWaiterRegistered?.invoke()
            // SPEC-497 I4 m7 — check-and-add atomically: the connection may have come up since the fast-path
            // check above, after its dispatch emptied the list; a callback added then would never be called.
            // Round 11 — likewise `destroy()` may have run since, after its release emptied the list: a
            // destroyed manager answers null at once instead of parking the waiter forever.
            val outcome = synchronized(pendingCallbacks) {
                when {
                    destroyed -> WaiterOutcome.DESTROYED
                    isConnected -> WaiterOutcome.CONNECTED
                    else -> { pendingCallbacks.add(callback); WaiterOutcome.PARKED }
                }
            }
            when (outcome) {
                WaiterOutcome.DESTROYED -> { continuation.resume(null) {}; return@suspendCancellableCoroutine }
                WaiterOutcome.CONNECTED -> { continuation.resume(client) {}; return@suspendCancellableCoroutine }
                WaiterOutcome.PARKED -> Unit
            }
            // SPEC-497 R9 — a cancelled waiter (a cancelled caller, or `withTimeoutOrNull` giving up) takes
            // its callback with it, so a device that never connects does not accumulate dead waiters.
            continuation.invokeOnCancellation {
                synchronized(pendingCallbacks) { pendingCallbacks.remove(callback) }
            }
            // Ensure connection attempt is in progress (reset retry budget —
            // see withConnectedClient for rationale).
            if (!isConnected) {
                retryAttempt = 0
                unavailableSurfaced = false
                if (!retryScheduled) startConnection()
            }
        }
    }

    private enum class WaiterOutcome { DESTROYED, CONNECTED, PARKED }

    /** Test seam (I4 m7) — runs between the fast-path `isConnected` check and the waiter registration. */
    @Volatile internal var beforeWaiterRegistered: (() -> Unit)? = null

    /** How many [awaitConnectedClient] callers are parked (observed by tests). */
    internal val pendingWaiterCount: Int
        get() = synchronized(pendingCallbacks) { pendingCallbacks.size }

    /** Resume every parked [awaitConnectedClient] with null (give-up branch and [destroy]). */
    private fun resumeWaitersWithNull() {
        val waiters = synchronized(pendingCallbacks) {
            val copy = ArrayList(pendingCallbacks)
            pendingCallbacks.clear()
            copy
        }
        waiters.forEach { runCatching { it(null) } }
    }

    /**
     * Get the raw BillingClient instance (may not be connected).
     */
    fun getClient(): BillingClient? = billingClient

    /**
     * End the billing connection and release resources.
     */
    fun destroy() {
        scope.cancel()
        synchronized(pendingCallbacks) { destroyed = true }
        resumeWaitersWithNull()
        billingClient?.endConnection()
        billingClient = null
        isConnected = false
        retryAttempt = 0
        Log.info("BillingConnectionManager destroyed")
    }
}
