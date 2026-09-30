package ai.appdna.sdk

import android.app.Activity
import android.os.Build
import ai.appdna.sdk.billing.Entitlement
import ai.appdna.sdk.billing.NativeBillingManager
import ai.appdna.sdk.billing.ProductInfo
import ai.appdna.sdk.billing.PurchaseOptions as BillingPurchaseOptions
import ai.appdna.sdk.billing.PurchaseResult
import ai.appdna.sdk.config.ExperimentManager
import ai.appdna.sdk.config.FeatureFlagManager
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.feedback.SurveyManager
import ai.appdna.sdk.integrations.AppDNAMessagingService
import ai.appdna.sdk.integrations.AppDNANotificationPoster
import ai.appdna.sdk.integrations.PushIdempotency
import ai.appdna.sdk.integrations.PushPayloadParser
import ai.appdna.sdk.integrations.PushSessionContext
import ai.appdna.sdk.integrations.PushTapRouter
import ai.appdna.sdk.integrations.PushTokenManager
import ai.appdna.sdk.storage.LocalStorage
import android.content.Context
import com.google.firebase.messaging.RemoteMessage
import ai.appdna.sdk.onboarding.OnboardingFlowManager
import ai.appdna.sdk.paywalls.PaywallContext
import ai.appdna.sdk.paywalls.PaywallManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.*
import kotlinx.coroutines.future.future
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume

// MARK: - Module Namespaces (v1.0)
// Provides `AppDNA.push.*`, `AppDNA.billing.*`, etc.

// MARK: - Typed Data Classes (SPEC-041)

/**
 * Push notification payload.
 */
data class PushPayload(
    val pushId: String,
    val title: String,
    val body: String,
    val imageUrl: String? = null,
    val data: Map<String, Any>? = null,
    val action: PushAction? = null,
    /**
     * SPEC-070-B — every action button registered on this notification, in tray order.
     *
     * `action` above is only the notification's single primary action, so a push carrying three
     * buttons reached the host delegate looking identical to one carrying none. Hosts (and the
     * Flutter/RN wrappers) can now see the buttons the SDK actually registered.
     */
    val actions: List<PushActionButton> = emptyList()
)

/**
 * Push action from notification tap.
 */
data class PushAction(
    val type: String,
    val value: String
)

/**
 * One action button attached to a push notification.
 *
 * [type] / [value] carry the same semantics as [PushAction] (e.g. `deep_link` + a URL,
 * `show_screen` + a screen id, `dismiss` + nothing); [id] is echoed back as the `action_id` extra
 * when the user taps the button.
 */
data class PushActionButton(
    val id: String,
    val label: String,
    val type: String,
    val value: String? = null,
    val icon: String? = null
)

/**
 * Transaction information from a completed purchase.
 */
data class TransactionInfo(
    val transactionId: String,
    val productId: String,
    val purchaseDate: String,
    /**
     * `"sandbox"` for a Play test purchase (a license tester's), `"production"` otherwise — as the
     * AppDNA server decided it when it verified the purchase (`billingProvider: StoreKit2`). Play's
     * client library exposes no such flag, so it is `"production"` whenever no verified answer exists:
     * an older server, a purchase resolved without a verify (an already-owned re-tap), and the
     * RevenueCat / Adapty bridges.
     */
    val environment: String = DEFAULT_ENVIRONMENT
) {
    companion object {
        /** The value used when the environment is not known. */
        const val DEFAULT_ENVIRONMENT = "production"
    }
}

/**
 * Survey response entry.
 */
data class SurveyResponse(
    val questionId: String,
    val answer: Any,
    val metadata: Map<String, Any>? = null
)

/**
 * Delegate for push notification lifecycle events.
 */
interface AppDNAPushDelegate {
    /** Called when the push token is registered or refreshed. */
    fun onPushTokenRegistered(token: String) {}
    /** Called when a push notification is received. */
    fun onPushReceived(notification: PushPayload, inForeground: Boolean) {}
    /** Called when a push notification is tapped. */
    fun onPushTapped(notification: PushPayload, actionId: String?) {}
}

/**
 * Push notification module namespace.
 */
class PushModule internal constructor() {
    internal var manager: PushTokenManager? = null
    // SPEC-070-A H.17: dropped duplicate `listener` field. The single source
    // of truth is `PushTokenManager.pushListener`, which Phase B.1 wires from
    // [setDelegate]. Reading the delegate now goes through [delegate].

    /** Current push token. */
    @get:JvmName("getTokenValue")
    val token: String? get() = manager?.currentToken

    /** Get the current push token (spec-compliant method form). */
    fun getToken(): String? = manager?.currentToken

    /** Set the FCM token. */
    fun setToken(token: String) = AppDNA.setPushToken(token)

    /** Track push delivered. */
    fun trackDelivered(pushId: String) = AppDNA.trackPushDelivered(pushId)

    /** Track push tapped. */
    fun trackTapped(pushId: String, action: String? = null) = AppDNA.trackPushTapped(pushId, action)

    /**
     * SPEC-070-A A.30 + H.4 — typed suspend permission request.
     *
     * On Android 13+ (`Build.VERSION_CODES.TIRAMISU` / API 33), requesting
     * `POST_NOTIFICATIONS` is a runtime permission and MUST be triggered from
     * an Activity via the Activity Result API. We use
     * `registerForActivityResult(RequestPermission)` and suspend until the
     * user answers.
     *
     * Below API 33, push permission is implicit (granted at install) and we
     * return `true` immediately. We still emit the same analytics through
     * [PushTokenManager.setPushPermission].
     *
     * @param activity Required on API 33+. May be `null` on older API levels;
     *                 the permission is implicit there.
     * @return `true` if the user granted the permission (or no permission is
     *         required on this OS version), `false` if denied.
     */
    suspend fun requestPermission(activity: Activity? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Permission is implicit pre-13; report granted.
            manager?.setPushPermission(true)
            return true
        }
        val componentActivity = activity as? ComponentActivity
        if (componentActivity == null) {
            Log.warning("PushModule.requestPermission: requires a ComponentActivity on Android 13+")
            return false
        }
        return suspendCancellableCoroutine { cont ->
            val launcher = componentActivity.activityResultRegistry.register(
                "ai.appdna.sdk.push.permission.${UUID.randomUUID()}",
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                manager?.setPushPermission(granted)
                if (cont.isActive) cont.resume(granted)
            }
            cont.invokeOnCancellation {
                runCatching { launcher.unregister() }
            }
            launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Java-friendly overload — returns a [CompletableFuture]. Mirrors the
     * suspend [requestPermission] above for non-Kotlin consumers.
     */
    @JvmOverloads
    fun requestPermissionFuture(activity: Activity? = null): CompletableFuture<Boolean> =
        moduleScope.future { requestPermission(activity) }

    /** Set a delegate for push notification lifecycle events. */
    fun setDelegate(delegate: AppDNAPushDelegate?) {
        manager?.pushListener = delegate
    }

    /**
     * SPEC-070-A H.8: read accessor used by [AppDNAMessagingService] to fire
     * `onPushReceived` / `onPushTapped`. Returns the live delegate registered
     * via [setDelegate], or null when no host has registered one.
     */
    internal fun delegate(): AppDNAPushDelegate? = manager?.pushListener

    // ---------------------------------------------------------------------------------------------
    // SPEC-497 §9.2 (B2) — forwarding API for a host that owns Firebase Messaging.
    // ---------------------------------------------------------------------------------------------

    /** Test seam: the context the push entry points use (production: the configured app context). */
    internal var contextOverride: Context? = null
    internal val context: Context? get() = contextOverride ?: AppDNA.getApplicationContext()

    internal enum class ReceiveOutcome { IGNORED, DUPLICATE, HANDLED }

    /**
     * Whether [data] is an AppDNA push: it carries the server's marker `appdna = "1"`. A bare
     * `push_id` is NOT enough — hosts and third-party pushers commonly use a `push_id` of their own.
     */
    fun isAppDNAMessage(data: Map<String, String>): Boolean = data["appdna"] == "1"

    /**
     * Handle an FCM message a host received in its own `FirebaseMessagingService`: exactly what the
     * SDK's service does for an AppDNA message — tracks `push_delivered` (with `delivery_id`), fires
     * `onPushReceived` and, for a message with a title or body, posts the notification (a message with
     * a `notification` block reaches a service only in the foreground, where FCM does not display it).
     * Returns `false` and does nothing for a message that is not AppDNA's. Safe to call for every
     * message; a message already handled (by the SDK's service or an earlier call) is a no-op that
     * returns `true`.
     */
    fun handleMessage(message: RemoteMessage): Boolean {
        val data = message.data
        return when (receive(data, message.notification?.title, message.notification?.body)) {
            ReceiveOutcome.IGNORED -> false
            ReceiveOutcome.DUPLICATE -> true
            ReceiveOutcome.HANDLED -> {
                // A host's service is called for a message with a `notification` block only while the
                // app is in the foreground (FCM displays it itself in the background), so it is
                // displayed here like a data-only message — the notification block's title / body
                // fill a missing data title / body.
                context?.let { ctx ->
                    try {
                        AppDNANotificationPoster.post(ctx, data, message.notification?.title, message.notification?.body)
                    } catch (e: Throwable) {
                        Log.warning("PushModule.handleMessage: display failed: ${e.message}")
                    }
                }
                true
            }
        }
    }

    /**
     * Like [handleMessage] for a host that owns DISPLAY too: tracks `push_delivered` (with
     * `delivery_id`) and fires `onPushReceived`, and NEVER posts a notification. Returns `false` and
     * does nothing unless the data carries the AppDNA marker.
     */
    fun handleMessageData(data: Map<String, String>): Boolean =
        receive(data, null, null) != ReceiveOutcome.IGNORED

    /**
     * The tap half of `AppDNA.handlePushTap(intent)` for a host that owns display: tracks
     * `push_tapped` (with `delivery_id`), fires `onPushTapped` and auto-routes the tap (the tapped
     * button [actionId] first, then the notification's action, `screen_id`, `deep_link`). Returns
     * `false` and does nothing unless the data carries the AppDNA marker; a tap already handled
     * (through either entry point) is a no-op that returns `true`.
     */
    @JvmOverloads
    fun handleTapData(data: Map<String, String>, actionId: String? = null): Boolean = handleTap(data, actionId)

    /** Shared by the service, [handleMessage] and [handleMessageData]. */
    internal fun receive(
        data: Map<String, String>,
        fallbackTitle: String?,
        fallbackBody: String?,
        ctx: Context? = context,
    ): ReceiveOutcome {
        if (!isAppDNAMessage(data)) return ReceiveOutcome.IGNORED
        val pushId = data["push_id"].orEmpty()
        val deliveryId = data["delivery_id"]?.takeIf { it.isNotBlank() }
        val key = deliveryId ?: pushId
        val mgr = manager
        // Impl audit round 2 (A2): ALWAYS claim, so a message displayed before `configure` is not
        // displayed again when it is forwarded after it. A claim with no manager to track it is marked
        // untracked; the first later forward that can track it tracks it and fires `onPushReceived`
        // (never delivered before `configure`: no delegate yet) — once, and without displaying again.
        val claim = if (key.isBlank()) PushIdempotency.Claim.FIRST else PushIdempotency.claimDelivered(key, canTrack = mgr != null)
        if (claim == PushIdempotency.Claim.DUPLICATE) return ReceiveOutcome.DUPLICATE
        Log.info("Push received: $pushId")
        if (mgr == null) Log.warning("Push received before configure — push_delivered is not tracked for $pushId")
        mgr?.trackDelivered(pushId, deliveryId)
        if (claim == PushIdempotency.Claim.FIRST) {
            ctx?.let {
                try {
                    PushSessionContext.recordPushReceived(it, pushId)
                } catch (e: Throwable) {
                    Log.warning("PushSessionContext: recordPushReceived failed: ${e.message}")
                }
            }
        }
        val delegate = delegate()
        if (delegate != null) {
            val payload = PushPayloadParser.buildPayload(data, fallbackTitle, fallbackBody)
            // The PROCESS foreground state (works in an `:fcm` process too).
            val inForeground = ctx?.let { AppDNAMessagingService.isAppInForeground(it) } ?: false
            postMain { delegate.onPushReceived(payload, inForeground) }
        }
        // A late track does not display again: the first forward already did.
        return if (claim == PushIdempotency.Claim.LATE_TRACK) ReceiveOutcome.DUPLICATE else ReceiveOutcome.HANDLED
    }

    /**
     * A tap on a notification the PREVIOUS SDK version posted (still in the tray when the app updated).
     * That version's tap intent carries no `appdna` marker and, from a server older than this release,
     * a blank `push_id` — [handleTap] would drop it (no marker) or refuse it (no key), so the tap did
     * nothing after the upgrade. With a key it is an ordinary tap; without one it cannot be tracked or
     * deduplicated, so it fires `onPushTapped` and routes, and tracks nothing. (The caller makes the
     * intent inert afterwards, so an Activity re-created with it does not route again.)
     */
    internal fun handleLegacyTap(data: Map<String, String>, actionId: String?): Boolean {
        val marked = data + ("appdna" to "1")
        val key = data["delivery_id"]?.takeIf { it.isNotBlank() } ?: data["push_id"].orEmpty()
        if (key.isNotBlank()) return handleTap(marked, actionId)
        delegate()?.let { d ->
            val payload = PushPayloadParser.buildPayload(marked)
            postMain { d.onPushTapped(payload, actionId) }
        }
        PushTapRouter.route(PushPayloadParser.resolveTapAction(marked, actionId))
        return true
    }

    /** Shared by `AppDNA.handlePushTap(intent)` and [handleTapData]. */
    internal fun handleTap(data: Map<String, String>, actionId: String?): Boolean {
        if (!isAppDNAMessage(data)) return false
        val pushId = data["push_id"].orEmpty()
        val deliveryId = data["delivery_id"]?.takeIf { it.isNotBlank() }
        val key = deliveryId ?: pushId
        if (key.isBlank()) return false

        // One tap tracks once, whichever entry point (or an Activity re-created with the same
        // intent) sees it — the persisted key survives process death.
        val storage = context?.let { LocalStorage(it) }
        if (!PushIdempotency.claimTap(storage, key)) return true

        // A body tap carries iOS's `UNNotificationDefaultActionIdentifier` sentinel so the `action`
        // column is non-null and comparable across platforms (SPEC-401-A R81).
        val resolvedAction = actionId?.takeIf { it.isNotBlank() }
            ?: data["action_type"]?.takeIf { it.isNotBlank() }
            ?: "com.apple.UNNotificationDefaultActionIdentifier"
        try {
            manager?.trackTapped(pushId, resolvedAction, deliveryId)
        } catch (e: Throwable) {
            Log.warning("push tap: trackTapped threw: ${e.message}")
        }
        context?.let {
            try {
                PushSessionContext.recordPushReceived(it, pushId)
            } catch (_: Throwable) { /* best-effort */ }
        }
        delegate()?.let { d ->
            val payload = PushPayloadParser.buildPayload(data)
            postMain { d.onPushTapped(payload, actionId) }
        }
        PushTapRouter.route(PushPayloadParser.resolveTapAction(data, actionId))
        return true
    }

    private fun postMain(block: () -> Unit) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    block()
                } catch (e: Throwable) {
                    Log.warning("AppDNAPushDelegate threw: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            Log.warning("AppDNAPushDelegate fan-out failed: ${e.message}")
        }
    }

    companion object {
        // Shared scope for Java-future overloads. Kept in a companion to avoid
        // per-instance lifecycle leaks; the SDK never re-creates PushModule.
        private val moduleScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    }
}

/**
 * Billing module namespace.
 * Delegates to NativeBillingManager when available.
 *
 * SPEC-070-A A.29 / A.30 — public APIs are typed suspend functions returning
 * concrete data classes (`TransactionInfo`, `ProductInfo`, `Entitlement`),
 * mirroring iOS `AppDNA.Billing` (AppDNA+Modules.swift). Java consumers get
 * `*Future` overloads via [kotlinx.coroutines.future.future].
 */
class BillingModule internal constructor() {
    internal var manager: NativeBillingManager? = null
        set(value) {
            field = value
            // Flush anything registered before billing existed. See `onEntitlementsChanged`.
            if (value != null) {
                // CopyOnWriteArrayList, not a plain ArrayList: `onEntitlementsChanged` runs on the JS
                // thread (a TurboModule body) while this flush runs on the bootstrap coroutine. With a
                // plain list, an `add` interleaving between the snapshot and the clear would DISCARD
                // the listener without attaching it — re-creating, intermittently, the exact
                // dropped-listener bug this queue exists to fix. Remove-then-attach per element keeps
                // the two operations atomic per listener.
                for (cb in pendingEntitlementListeners) {
                    if (pendingEntitlementListeners.remove(cb)) value.entitlementCache.addChangeListener(cb)
                }
            }
        }

    /**
     * 🔴 Listeners registered BEFORE the billing manager exists.
     *
     * `manager` is only assigned inside `performBootstrap`, which is launched from `configure()` and
     * completes after it returns. So the ordinary host sequence — `await configure(); onEntitlementsChanged(cb)`
     * — hit `manager?` while it was still null and the callback was SILENTLY DROPPED. The React Native
     * facade latches "observer started" after its first call and never retries, so that host received
     * no entitlement change for the entire app session. iOS never had this: its handlers live in a
     * static dict that survives init order.
     */
    private val pendingEntitlementListeners =
        java.util.concurrent.CopyOnWriteArrayList<(List<Entitlement>) -> Unit>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * SPEC-497 §3.2 — the ownership policy chosen at `configure` (`BillingOwnership.policy(provider)`),
     * assigned BEFORE the `none` early return; a failed billing init sets the `none` policy. Its explicit
     * initial value is [BillingOwnershipPolicy.NotConfigured] (and again after `shutdown()`), so the
     * paywall never reads an unassigned policy.
     */
    @Volatile
    internal var ownershipPolicy: ai.appdna.sdk.billing.BillingOwnershipPolicy =
        ai.appdna.sdk.billing.BillingOwnershipPolicy.NotConfigured

    /** Check if user has active subscription. */
    fun hasActiveSubscription(): Boolean {
        return manager?.entitlementCache?.hasActiveSubscription ?: false
    }

    /**
     * SPEC-401 Fix 1D — silently refresh cached entitlement state.
     *
     * Calls into [NativeBillingManager.refreshEntitlementCache] which
     * re-reads the user's current entitlements via Play Billing
     * `BillingClient.queryPurchasesAsync` and primes the
     * [EntitlementCache] in place. Designed for two callers:
     *   1. [AppDNA.identify] — auto-refresh after host signs in a user
     *      so the next paywall_trigger entitlement gate (Fix 1A)
     *      reflects that user's subscriptions, not the previous
     *      anonymous user's empty entitlements.
     *   2. Hosts that complete auth out-of-band (SSO callbacks, deep
     *      links, OAuth web flows) and need to flush stale cache
     *      without firing user-visible restore events.
     *
     * Side effects: no UI and no restore events (`purchase_restored` /
     * `onRestoreCompleted` never fire). But under `StoreKit2` it is NOT
     * side-effect free: it completes any purchase Play holds that was never
     * reported — verify, one `purchase_completed` (plus `subscription_started`
     * for a subscription), acknowledge / consume, and a queued
     * `AppDNABillingDelegate.onPurchaseCompleted` — then sends the owned
     * list to the server's restore endpoint and merges the reply, which
     * fires `onEntitlementsChanged` when the cache changes. Under
     * `RevenueCat` / `Adapty` / `None` it only rebuilds the cache locally
     * from Play (no server call, no analytics event). Errors are swallowed and logged
     * at warning level — the suspend returns normally so callers can chain
     * without try/catch.
     */
    suspend fun refreshEntitlementCache() {
        val mgr = manager ?: run {
            Log.warning("BillingModule.refreshEntitlementCache: manager not available")
            return
        }
        try {
            mgr.refreshEntitlementCache()
        } catch (e: Exception) {
            Log.warning("BillingModule.refreshEntitlementCache failed: ${e.message}")
        }
    }

    /** Java-friendly overload — returns a [CompletableFuture]. */
    fun refreshEntitlementCacheFuture(): CompletableFuture<Unit> =
        scope.future { refreshEntitlementCache() }

    /**
     * SPEC-070-A A.30 — typed suspend product fetch.
     *
     * Returns `ProductInfo` data classes from [NativeBillingManager.getProducts].
     * Replaces the previous broken `scope.launch; return result` stub which
     * always returned an empty list synchronously.
     */
    suspend fun getProducts(productIds: List<String>): List<ProductInfo> {
        val mgr = manager ?: run {
            Log.warning("BillingModule: manager not available — returning empty products")
            return emptyList()
        }
        return try {
            mgr.getProducts(productIds)
        } catch (e: Exception) {
            Log.error("BillingModule.getProducts failed: ${e.message}")
            emptyList()
        }
    }

    /** Java-friendly overload — returns a [CompletableFuture]. */
    fun getProductsFuture(productIds: List<String>): CompletableFuture<List<ProductInfo>> =
        scope.future { getProducts(productIds) }

    /**
     * SPEC-070-A A.29 / A.30 — initiate a purchase flow.
     *
     * Activity is now the FIRST parameter (required by Google Play Billing
     * `launchBillingFlow`). Mirrors iOS `BillingModule.purchase(productId:options:)`
     * (AppDNA+Modules.swift:72).
     *
     * Returns a [TransactionInfo] on success. Throws on failure (cancelled,
     * pending, billing error).
     *
     * @param activity Activity used to launch the Play billing dialog.
     * @param productId The Google Play product ID to purchase.
     * @param options Optional purchase parameters (offer token, app account token).
     */
    @JvmOverloads
    suspend fun purchase(
        activity: Activity,
        productId: String,
        options: BillingPurchaseOptions? = null,
    ): TransactionInfo {
        val policy = ownershipPolicy
        if (!policy.isConfigured) {
            // R65: before configure — an `unknown` error (no new error type); `purchase_failed` only
            // when a tracker exists.
            val msg = ai.appdna.sdk.billing.BillingOwnershipPolicy.NOT_CONFIGURED_MESSAGE
            if (AppDNA.hasEventTracker()) {
                AppDNA.trackBillingEvent("purchase_failed", mapOf(
                    "product_id" to productId, "reason" to "not_configured", "error" to msg, "error_type" to "unknown",
                ))
            }
            throw IllegalStateException(msg)
        }
        val mgr = manager ?: run {
            // §3.2 rule 5: no provider (or a failed billing init) — exactly one purchase_failed, no
            // purchase_started, no paywall_id.
            val msg = "No billing provider configured"
            AppDNA.trackBillingEvent("purchase_failed", mapOf(
                "product_id" to productId, "reason" to "provider_not_available", "error" to msg,
                "error_type" to "providerNotAvailable",
            ))
            throw ai.appdna.sdk.billing.BillingError.ProviderNotAvailable(msg)
        }
        AppDNA.trackBillingEvent("billing_purchase_requested", mapOf("product_id" to productId))

        val result = mgr.purchase(activity, productId, options)
        return when (result) {
            is PurchaseResult.Purchased -> {
                val ent = result.entitlement
                TransactionInfo(
                    // SPEC-497 §13a.2 (V2-M4, R79): the real id, `orderId ?: purchaseToken`.
                    transactionId = result.transactionId ?: ent.productId,
                    productId = ent.productId,
                    // 🔴 THIS RETURNED THE EXPIRY AS THE PURCHASE DATE — a FUTURE date for a subscription,
                    // an empty string for a one-off. The `Entitlement` carries no purchase time, so the
                    // honest value is now.
                    // Round-11 Finding 2 — emit epoch-MILLIS (String), NOT `Instant.now().toString()`
                    // (ISO). Every OTHER Android purchaseDate is epoch-millis (NativeBillingManager
                    // `purchaseTime.toString()`, RC/Adapty `System.currentTimeMillis().toString()`), and
                    // the RN wrapper passes this field through verbatim — so an ISO string here made the
                    // RN `purchase()` facade return ISO on Android but epoch-millis on iOS, breaking the
                    // mapper's stated epoch-millis contract.
                    purchaseDate = System.currentTimeMillis().toString(),
                    environment = result.environment ?: TransactionInfo.DEFAULT_ENVIRONMENT,
                )
            }
            is PurchaseResult.Cancelled -> throw PurchaseCancelledException(productId)
            is PurchaseResult.Pending -> throw PurchasePendingException(productId)
            is PurchaseResult.Failed -> throw PurchaseFailedException(productId, result.error, result.errorType)
            is PurchaseResult.Unknown -> throw PurchaseFailedException(productId, "unknown billing result")
        }
    }

    /** Java-friendly overload — returns a [CompletableFuture]. */
    @JvmOverloads
    fun purchaseFuture(
        activity: Activity,
        productId: String,
        options: BillingPurchaseOptions? = null,
    ): CompletableFuture<TransactionInfo> =
        scope.future { purchase(activity, productId, options) }

    /**
     * SPEC-070-A A.30 — typed suspend restore. Mirrors iOS
     * `AppDNA+Modules.swift:88` `restorePurchases() async throws -> [String]`.
     * Returns the list of restored product IDs so hosts can show a confirmation
     * UI; the underlying [Entitlement] objects are still surfaced via
     * [AppDNABillingDelegate.onRestoreCompleted] for hosts that prefer the
     * delegate flow.
     */
    suspend fun restorePurchases(): List<String> {
        if (!ownershipPolicy.isConfigured) {
            throw IllegalStateException(ai.appdna.sdk.billing.BillingOwnershipPolicy.NOT_CONFIGURED_MESSAGE)
        }
        val mgr = manager ?: run {
            // SPEC-497 R37 SDK major 2: `none` throws (was `[]`), tracked once.
            val e = ai.appdna.sdk.billing.BillingError.ProviderNotAvailable("No billing provider configured")
            AppDNA.trackBillingEvent("purchase_restore_failed", mapOf(
                "error" to (e.message ?: "providerNotAvailable"), "error_type" to "providerNotAvailable",
            ))
            throw e
        }
        // SPEC-497 restore error contract: every failure reaches the host as a typed BillingError
        // (already tracked once by the manager); the cache is unchanged.
        return mgr.restorePurchases().map { it.productId }
    }

    /**
     * Java-compatible `CompletableFuture` adapter for [restorePurchases].
     */
    @JvmName("restorePurchasesAsync")
    fun restorePurchasesAsync(): CompletableFuture<List<String>> =
        scope.future { restorePurchases() }

    /**
     * SPEC-070-A A.30 — typed suspend entitlements fetch.
     *
     * Returns the current cached active entitlements as `Entitlement`
     * data classes. Suspend so future implementations can refresh from
     * the network without a breaking change.
     */
    suspend fun getEntitlements(): List<Entitlement> {
        val mgr = manager ?: return emptyList()
        return mgr.entitlementCache.getAll()
    }

    /** Java-friendly overload — returns a [CompletableFuture]. */
    fun getEntitlementsFuture(): CompletableFuture<List<Entitlement>> =
        scope.future { getEntitlements() }

    /**
     * Register a callback for entitlement changes.
     */
    fun onEntitlementsChanged(callback: (List<Entitlement>) -> Unit) {
        val mgr = manager
        if (mgr == null) {
            // Queue rather than drop — see `pendingEntitlementListeners`.
            pendingEntitlementListeners.add(callback)
            return
        }
        mgr.entitlementCache.addChangeListener(callback)
    }

    /**
     * SPEC-070-B PN row 5 (E3): remove a callback registered by [onEntitlementsChanged].
     * [EntitlementCache.removeChangeListener] has always existed but was never exposed, so a wrapper
     * that re-`configure()`s (a React Native reload does) accumulated listeners and delivered every
     * change N-fold.
     *
     * Removal is by **reference identity** — pass the same lambda instance you registered. Storing it
     * in a `val` is the only way; `::method` references and lambda literals allocate a fresh object
     * on every evaluation and will silently fail to remove anything.
     */
    fun removeEntitlementsChangedListener(callback: (List<Entitlement>) -> Unit) {
        // Also drop it from the pre-init queue, or a listener removed before billing came up would be
        // re-attached by the flush and fire after the host had already unsubscribed.
        pendingEntitlementListeners.remove(callback)
        manager?.entitlementCache?.removeChangeListener(callback)
    }

    /**
     * Set a delegate to receive billing lifecycle callbacks (purchases, failures, restores).
     */
    fun setDelegate(delegate: AppDNABillingDelegate?) {
        assignBillingDelegate(delegate, delivers = true)
    }

    /**
     * SPEC-497 D-R40-1 — set the billing delegate and say whether it takes queued purchase deliveries.
     * A delegate registered with `deliversPurchases = false` is never called by the delivery queue's
     * drain (it still receives the live `purchase()` caller's `onPurchaseCompleted`). Any delegate set
     * through [setDelegate] delivers — implement `onPurchaseCompleted` and grant idempotently by
     * `transactionId` (delivery is at least once).
     */
    fun setDelegate(delegate: AppDNABillingDelegate?, deliversPurchases: Boolean) {
        assignBillingDelegate(delegate, deliversPurchases)
    }

    /** The delegate and its `delivers` flag, held as ONE volatile snapshot (R46). */
    private class DelegateSlot(val delegate: AppDNABillingDelegate?, val delivers: Boolean)

    @Volatile
    private var delegateSlot = DelegateSlot(null, false)

    /** The current billing delegate (read at call time). Only [assignBillingDelegate] writes it. */
    internal val billingListener: AppDNABillingDelegate?
        get() = delegateSlot.delegate

    /** The delegate the drain may call, or null when there is none or it does not deliver purchases. */
    internal fun deliveringDelegate(): AppDNABillingDelegate? =
        delegateSlot.let { if (it.delivers) it.delegate else null }

    /**
     * The ONE entry point that writes the delegate storage and the flag (R42/R43). The flag is set with
     * the delegate in one snapshot, and a delivering delegate triggers the drain itself (trigger (ii)).
     */
    internal fun assignBillingDelegate(delegate: AppDNABillingDelegate?, delivers: Boolean) {
        delegateSlot = DelegateSlot(delegate, delivers && delegate != null)
        if (delegate != null && delivers) manager?.triggerDrain()
    }

    /**
     * SPEC-070-A H.24 — cancel the wrapper's coroutine scope so any pending
     * `purchaseFuture()` / `getProductsFuture()` / restore continuation is
     * surfaced as cancellation rather than leaking. The owning
     * [NativeBillingManager] is destroyed separately by [AppDNA.shutdown].
     */
    internal fun shutdown() {
        scope.cancel()
        assignBillingDelegate(null, delivers = false)
        ownershipPolicy = ai.appdna.sdk.billing.BillingOwnershipPolicy.NotConfigured
    }
}

/** Thrown by [BillingModule.purchase] when the user cancels. */
class PurchaseCancelledException(val productId: String) : RuntimeException("Purchase cancelled for product: $productId")

/** Thrown by [BillingModule.purchase] when the purchase enters PENDING state (e.g. parental approval). */
class PurchasePendingException(val productId: String) : RuntimeException("Purchase pending for product: $productId")

/** Thrown by [BillingModule.purchase] when Google Play returns an error. */
class PurchaseFailedException @JvmOverloads constructor(
    val productId: String,
    val error: String,
    /** SPEC-497 §13a.3 — the `billingErrorType` of the underlying failure (`verificationFailed`, …). */
    val errorType: String? = null,
) : RuntimeException("Purchase failed for $productId: $error")

/**
 * Onboarding module namespace.
 */
class OnboardingModule internal constructor() {
    internal var manager: OnboardingFlowManager? = null
    internal var listener: ai.appdna.sdk.onboarding.AppDNAOnboardingDelegate? = null

    /** Present an onboarding flow. */
    fun present(
        activity: Activity,
        flowId: String? = null,
        context: OnboardingContext? = null
    ): Boolean {
        return AppDNA.presentOnboarding(activity, flowId, listener)
    }

    /** Set a delegate for onboarding events. */
    fun setDelegate(delegate: ai.appdna.sdk.onboarding.AppDNAOnboardingDelegate?) {
        this.listener = delegate
    }
}

/**
 * Paywall module namespace.
 */
class PaywallModule internal constructor() {
    internal var manager: PaywallManager? = null
    internal var listener: ai.appdna.sdk.paywalls.AppDNAPaywallDelegate? = null

    /**
     * SPEC-401 Fix 1C — host opt-out for SDK auto-dismiss-on-restore-success.
     *
     * When set to `true`, the next successful Restore tap on a presented
     * paywall will fire `onPaywallRestoreCompleted` to the delegate as
     * usual, but the SDK will NOT auto-finish the PaywallActivity. The
     * host owns dismissal in this case (typical pattern: show a
     * "Restored — tap continue when ready" overlay, then call
     * `activity.finish()` from a button tap).
     *
     * One-shot: PaywallManager.handleRestore reads + clears this flag
     * each time it processes a restore. After the next restore (success
     * or failure), the flag resets to `false` so subsequent paywall
     * presentations get the default auto-dismiss behavior.
     *
     * Mirrors iOS `AppDNA.paywall.skipNextAutoDismissOnRestore`.
     */
    @Volatile
    var skipNextAutoDismissOnRestore: Boolean = false

    /** Present a paywall. */
    /**
     * 🔴 THIS DISCARDED THE ANSWER — ON THE SURFACE THAT TAKES THE MONEY.
     *
     * [AppDNA.presentPaywall] returns a Boolean: false when the id is not in the published config, when
     * the SDK is not configured, or when it is runtime-locked. This facade — the one the docs tell hosts
     * to call, and the one BOTH wrappers route through — threw it away and returned Unit. So
     * `AppDNA.paywall.present(activity, "typo_id")` looked like a success to every caller, native and
     * wrapper alike, and no paywall ever appeared.
     *
     * [OnboardingModule.present] has always returned Boolean. The paywall — where the revenue is — was
     * the one that did not.
     *
     * @return false if nothing was presented.
     */
    fun present(
        activity: Activity,
        paywallId: String,
        context: PaywallContext? = null
    ): Boolean {
        return AppDNA.presentPaywall(activity, paywallId, context, listener)
    }

    /** Set a delegate for paywall events. */
    fun setDelegate(delegate: ai.appdna.sdk.paywalls.AppDNAPaywallDelegate?) {
        this.listener = delegate
    }
}

/**
 * Remote config module namespace.
 */
class RemoteConfigModule internal constructor() {
    internal var manager: RemoteConfigManager? = null
    private var changeCallback: ((Map<String, Any>) -> Unit)? = null

    /** Get a config value. */
    fun get(key: String): Any? = manager?.getConfig(key)

    /** Get all config values. */
    fun getAll(): Map<String, Any> = manager?.getAllConfig() ?: emptyMap()

    /** Force refresh config from server. */
    fun refresh() { manager?.fetchConfigs() }

    /** Register a callback that fires when remote config values change. */
    fun onChanged(callback: (Map<String, Any>) -> Unit) {
        this.changeCallback = callback
        manager?.addChangeListener(callback)
    }
}

/**
 * Feature flags module namespace.
 */
class FeaturesModule internal constructor() {
    internal var manager: FeatureFlagManager? = null
    private var changeCallback: ((Map<String, Boolean>) -> Unit)? = null

    /** Check if a feature flag is enabled. */
    fun isEnabled(flag: String): Boolean = manager?.isEnabled(flag) ?: false

    /** Get feature flag value. */
    fun getVariant(flag: String): Any? = manager?.getValue(flag)

    /** Register a callback that fires when feature flags change. */
    fun onChanged(callback: (Map<String, Boolean>) -> Unit) {
        this.changeCallback = callback
        manager?.addChangeListener(callback)
    }
}

/**
 * In-app messages module namespace.
 */
class InAppMessagesModule internal constructor() {
    /**
     * Public delegate exposed for hosts that read it directly. The
     * source-of-truth is `MessageManager.delegate` — this field is kept
     * in lockstep via `setDelegate(...)` so callers querying
     * `AppDNA.inAppMessages` see the same instance.
     */
    var delegate: AppDNAInAppMessageDelegate? = null
        private set

    /** SPEC-070-A A.11: Temporarily suppress display. Forwards to MessageManager. */
    fun suppressDisplay(suppress: Boolean) {
        AppDNA.messageManager?.suppressDisplay(suppress)
    }

    /** SPEC-070-A A.11: Set a delegate for in-app message lifecycle events. */
    fun setDelegate(delegate: AppDNAInAppMessageDelegate?) {
        this.delegate = delegate
        AppDNA.messageManager?.delegate = delegate
    }

    /**
     * SPEC-070-C D10 — set the OPTIONAL async `shouldShowMessage` wrapper-veto.
     * Forwards to `MessageManager.asyncShouldShowMessage`, which awaits it (in
     * addition to the sync delegate veto) before presenting. Null clears it.
     */
    fun setAsyncShouldShowMessage(veto: (suspend (String) -> Boolean)?) {
        AppDNA.messageManager?.asyncShouldShowMessage = veto
    }
}

/**
 * Surveys module namespace.
 *
 * SPEC-070-A B.4 — `surveyListener` is `internal` (was private) so
 * `SurveyManager` can fan out lifecycle callbacks to the host's registered
 * delegate. Mirrors the `BillingModule.billingListener` shape.
 */
class SurveysModule internal constructor() {
    internal var manager: SurveyManager? = null
    internal var surveyListener: AppDNASurveyDelegate? = null

    /** Present a specific survey. */
    fun present(surveyId: String) { manager?.present(surveyId) }

    /** Set a delegate for survey lifecycle events. */
    fun setDelegate(delegate: AppDNASurveyDelegate?) {
        this.surveyListener = delegate
    }
}

/**
 * Deep links module namespace.
 */
class DeepLinksModule internal constructor() {
    private var listener: AppDNADeepLinkDelegate? = null

    /** Test seam (SPEC-497 §8.7): where `deep_link_handled` goes. Production: the public `AppDNA.track`. */
    internal var track: (String, Map<String, Any>) -> Unit = { name, props -> AppDNA.track(name, props) }

    /**
     * SPEC-070-C D10 — OPTIONAL async `shouldOpen` wrapper-veto. This is a
     * NET-NEW decision point (no native deep-link veto existed). When set (the
     * Flutter plugin), [handleURL] awaits it before dispatching the deep link;
     * a `false` reply skips processing entirely. Null for native hosts →
     * dispatch synchronously exactly as before.
     */
    @Volatile
    var asyncShouldOpen: (suspend (String, Map<String, String>) -> Boolean)? = null

    /** SPEC-070-C D10 — main-thread scope for awaiting the async veto. */
    private val vetoScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /**
     * Handle an incoming deep link URL.
     * Parses the URL, extracts parameters, and notifies the listener.
     *
     * @param url The deep link URL to handle.
     */
    fun handleURL(url: String) {
        Log.info("DeepLinksModule: handling URL: $url")
        try {
            val uri = android.net.Uri.parse(url)
            val params = mutableMapOf<String, String>()
            uri.queryParameterNames?.forEach { key ->
                uri.getQueryParameter(key)?.let { value ->
                    params[key] = value
                }
            }
            // SPEC-070-C D10 — NET-NEW async `shouldOpen` veto. When set, await
            // it before processing; a `false` reply skips onDeepLinkReceived +
            // the deep_link_handled event. When null, process synchronously.
            val asyncVeto = asyncShouldOpen
            if (asyncVeto != null) {
                vetoScope.launch {
                    val allow = try {
                        asyncVeto(url, params)
                    } catch (_: Throwable) {
                        true
                    }
                    if (!allow) {
                        Log.debug("DeepLinksModule: URL vetoed by host asyncShouldOpen: $url")
                        return@launch
                    }
                    listener?.onDeepLinkReceived(url, params)
                    track("deep_link_handled", mapOf("url" to url))
                }
                return
            }
            listener?.onDeepLinkReceived(url, params)
            track("deep_link_handled", mapOf("url" to url))
        } catch (e: Exception) {
            Log.error("DeepLinksModule: failed to handle URL: ${e.message}")
        }
    }

    /** Set a delegate for deep link events. */
    fun setDelegate(delegate: AppDNADeepLinkDelegate?) {
        this.listener = delegate
    }
}

/**
 * Experiments module namespace.
 */
class ExperimentsModule internal constructor() {
    internal var manager: ExperimentManager? = null

    /**
     * SPEC-070-A I.14 — Get the assigned variant id for an experiment, or
     * `null` if the user is not eligible.  Type matches iOS
     * `getVariant(experimentId:) -> String?`.
     */
    fun getVariant(experimentId: String): String? = manager?.getVariant(experimentId)

    /**
     * SPEC-070-A I.15 — Get all active exposures as named [ai.appdna.sdk.config.ExposureEntry]
     * tuples, matching iOS's `[(experimentId: String, variant: String)]` shape.
     */
    fun getExposures(): List<ai.appdna.sdk.config.ExposureEntry> =
        manager?.getExposures() ?: emptyList()
}

/**
 * Context passed to onboarding flows for dynamic branching.
 */
data class OnboardingContext(
    val source: String? = null,
    val campaign: String? = null,
    val referrer: String? = null,
    val userProperties: Map<String, Any>? = null,
    val experimentOverrides: Map<String, String>? = null
)

// MARK: - Delegate Interfaces (v1.0)

/**
 * Delegate for billing/purchase events.
 */
interface AppDNABillingDelegate {
    fun onPurchaseCompleted(productId: String, transaction: TransactionInfo) {}
    // SPEC-070-A finalization §3.2 — widen `error` to `Throwable` so hosts
    // can catch the new typed BillingError sealed class (also Throwable)
    // and Kotlin Errors. Mirrors iOS `error: Error` parity.
    fun onPurchaseFailed(productId: String, error: Throwable) {}
    fun onEntitlementsChanged(entitlements: List<Entitlement>) {}
    fun onRestoreCompleted(restoredProducts: List<String>) {}
    /**
     * SPEC-070-A H.14 — fires once when [BillingConnectionManager] gives up
     * after exhausting all reconnect slots (Play Services missing or
     * permanently broken). Hosts should hide paywalls / disable purchase UI
     * when this fires so users don't tap a dead button.
     */
    fun onBillingUnavailable() {}
}

/**
 * Delegate for in-app message events.
 */
interface AppDNAInAppMessageDelegate {
    fun onMessageShown(messageId: String, trigger: String) {}
    fun onMessageAction(messageId: String, action: String, data: Map<String, Any>?) {}
    fun onMessageDismissed(messageId: String) {}
    fun shouldShowMessage(messageId: String): Boolean = true
}

/**
 * Delegate for survey events.
 */
interface AppDNASurveyDelegate {
    fun onSurveyPresented(surveyId: String) {}
    fun onSurveyCompleted(surveyId: String, responses: List<SurveyResponse>) {}
    fun onSurveyDismissed(surveyId: String) {}
}

/**
 * Delegate for deep link events.
 */
interface AppDNADeepLinkDelegate {
    fun onDeepLinkReceived(url: String, params: Map<String, String>) {}
}

/**
 * SPEC-070-A H.20 — delegate for SDK init lifecycle events.
 *
 * Currently surfaces only [onInitDegraded], fired once when the SDK detects
 * a recoverable startup failure (missing `google-services-appdna.json`,
 * Firebase project misconfiguration, etc.). Hosts can use this to:
 *   - log to their crash reporter so misconfigured production apps don't go
 *     unnoticed,
 *   - hide features that depend on remote config when the SDK is degraded,
 *   - render an in-app banner for QA builds.
 *
 * The error throwable is also available synchronously via
 * [ai.appdna.sdk.AppDNA.lastInitError] for consumers that read it lazily.
 */
interface AppDNAInitDelegate {
    /**
     * Called when the SDK detects a recoverable init failure. May fire on
     * the main thread; do not block.
     */
    fun onInitDegraded(reason: Throwable) {}
}

/**
 * SPEC-070-B W13 — the recoverable init failures the SDK reports through
 * [AppDNAInitDelegate.onInitDegraded] and [ai.appdna.sdk.AppDNA.lastInitError].
 *
 * Mirrors iOS `AppDNAInitError` (AppDNA+Delegates.swift:194) case for case, so a cross-platform host
 * — the React Native and Flutter wrappers above all — sees one error vocabulary.
 */
sealed class AppDNAInitError(message: String) : Exception(message) {
    /**
     * One subsystem failed to start. The others — analytics above all — are unaffected.
     *
     * 🔴 Before this existed, `configure()` built every subsystem bare. A throw out of any manager's
     * constructor propagated to the HOST's `Application.onCreate()`, and the SDK never reached
     * `performBootstrap`: no `isConfigured`, no `sdk_initialized`, no `onReady` callback — ever, for
     * the whole process. iOS had isolated its subsystems; Android had not.
     */
    data class SubsystemFailed(val name: String, val detail: String) :
        AppDNAInitError("Subsystem '$name' failed to initialize: $detail")

    /** No usable AppDNA Firebase configuration. Remote config will not load; analytics still work. */
    data class FirebaseConfigMissing(val detail: String) :
        AppDNAInitError("Firebase configuration missing: $detail")

    /** The bootstrap request failed. The SDK runs on cached config, if any. */
    data class BootstrapFailed(val detail: String) :
        AppDNAInitError("Bootstrap failed: $detail")
}
