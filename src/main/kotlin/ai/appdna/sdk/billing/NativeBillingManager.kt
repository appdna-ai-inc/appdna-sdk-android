package ai.appdna.sdk.billing

import android.app.Activity
import android.content.Context
import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import ai.appdna.sdk.storage.LocalStorage
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.android.billingclient.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Options applied to a [NativeBillingManager.purchase] call.
 *
 * Mirrors iOS `PurchaseOptions` (AppDNA+Modules.swift:454).
 * The `appAccountToken` is forwarded to Google Play as the obfuscated account
 * ID (`BillingFlowParams.setObfuscatedAccountId`) so Play can correlate the
 * receipt with the app's user identity for fraud / chargeback investigations.
 */
data class PurchaseOptions(
    /** Optional offer token to select a specific Play subscription offer / trial. */
    val offerToken: String? = null,
    /**
     * Host-supplied UUID that uniquely identifies the buying user. Forwarded
     * to Play as `obfuscatedAccountId`. Use the same value you pass on iOS
     * (StoreKit `Product.PurchaseOption.appAccountToken`) so receipts line
     * up across stores.
     */
    val appAccountToken: UUID? = null,
)

/**
 * Entitlement model representing an active subscription or purchase.
 *
 * `@Keep` so R8/minify never drops this DTO. Hosts read
 * these fields reflectively when bridging to RN/Flutter channels and
 * via direct getters from the RevenueCat shim.
 */
@androidx.annotation.Keep
data class Entitlement(
    val productId: String,
    val store: String,
    val status: String,
    val expiresAt: String?,
    val isTrial: Boolean,
    val offerType: String?
)

/**
 * Result of a purchase operation.
 */
sealed class PurchaseResult {
    /** Purchase completed and verified. */
    data class Purchased(val entitlement: Entitlement) : PurchaseResult() {
        /**
         * The real transaction id, `orderId ?: purchaseToken`. An internal
         * property, not a constructor field, so `copy` / `componentN` are unchanged.
         */
        internal var transactionId: String? = null
            private set

        /** The server-verified environment (`"production"` / `"sandbox"`); null when not known. */
        internal var environment: String? = null
            private set

        internal constructor(entitlement: Entitlement, transactionId: String?, environment: String? = null) : this(entitlement) {
            this.transactionId = transactionId
            this.environment = environment
        }
    }
    /** User cancelled the purchase flow. */
    object Cancelled : PurchaseResult()
    /** Purchase is pending (e.g., awaiting parental approval or slow payment method). */
    object Pending : PurchaseResult()
    /** Unknown or unhandled result. */
    object Unknown : PurchaseResult()
    /** Purchase verification failed. */
    data class Failed @JvmOverloads constructor(
        val error: String,
        /** The `billingErrorType` of the failure (`verificationFailed`, `networkError`, …). */
        val errorType: String? = null,
    ) : PurchaseResult()
}

/**
 * Simplified product information for display in paywalls and UI.
 */
data class ProductInfo(
    val id: String,
    val name: String,
    val description: String,
    val formattedPrice: String,
    val priceMicros: Long,
    val currencyCode: String,
    val offerToken: String?
)

/**
 * Orchestrates native Google Play Billing operations.
 *
 * This is the primary class that app developers interact with for:
 * - Initiating purchases (subscriptions and in-app products)
 * - Restoring purchases
 * - Querying product details and prices
 * - Managing entitlement state
 *
 * Internally delegates to:
 * - [BillingConnectionManager] for connection lifecycle
 * - [ReceiptVerifier] for server-side receipt verification
 * - [EntitlementCache] for local entitlement persistence
 * - [PriceResolver] for product price resolution
 *
 * Usage:
 * ```kotlin
 * val billing = NativeBillingManager(context, receiptVerifier, entitlementCache)
 * billing.initialize()
 *
 * // Purchase
 * val result = billing.purchase(activity, "premium_monthly")
 *
 * // Check entitlement
 * val isPremium = billing.entitlementCache.hasActiveSubscription
 * ```
 */
class NativeBillingManager internal constructor(
    private val context: Context,
    internal val receiptVerifier: ReceiptVerifier,
    internal val entitlementCache: EntitlementCache,
    internal val storage: LocalStorage? = null,
    /**
     * False under a non-owning provider (RevenueCat, Adapty): the manager then
     * never verifies, acknowledges or consumes, and `purchase` / `restorePurchases` throw
     * [BillingError.ProviderNotAvailable]. Default true keeps the internal constructor's callers unchanged.
     */
    internal val ownsTransactions: Boolean = true,
    /** Gates ONLY [diffAndEmit]; the snapshot is saved either way. */
    internal val emitsLifecycleEvents: Boolean = true,
    /** The injectable Play client seam, passed to the connection manager. */
    billingClientFactory: BillingClientFactory = DefaultBillingClientFactory,
    /** The configured provider's wire name, for messages. */
    internal val providerName: String = "storeKit2",
) {
    internal val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** The current identity's user id (null = anonymous). Injectable for tests. */
    internal var currentUserIdProvider: () -> String? = { AppDNA.getIdentityRef()?.userId }
    internal fun currentUserId(): String? = currentUserIdProvider()
    internal fun currentUserToken(): UUID? = currentUserId()?.let { AppAccountTokenResolver.token(it) }

    /**
     * Cross-account-leak filter — keeps only the purchases that belong to the currently-identified app
     * user (per their `obfuscatedAccountId`). See [EntitlementOwnerFilter] for the decision matrix.
     */
    internal fun filterOwnedPurchases(
        purchases: List<Purchase>,
        expectedToken: UUID?,
        source: String,
    ): List<Purchase> {
        // Resolve the device-level first-identifier anchor ONCE per call so the decision matrix sees a
        // stable value even if the host identifies a different user mid-iteration.
        val firstIdentifier = AppAccountTokenResolver.firstIdentifiedToken()
        val kept = mutableListOf<Purchase>()
        for (purchase in purchases) {
            val rawToken = purchase.accountIdentifiers?.obfuscatedAccountId
            val purchaseToken = EntitlementOwnerFilter.parseObfuscatedAccountId(rawToken)
            when (EntitlementOwnerFilter.decide(purchaseToken, expectedToken, firstIdentifier)) {
                EntitlementOwnershipDecision.Grant,
                EntitlementOwnershipDecision.GrantAnonymousPolicy -> kept.add(purchase)
                EntitlementOwnershipDecision.GrantUntaggedMigration -> {
                    Log.info("$source: granting untagged historical purchase ${purchase.orderId ?: purchase.purchaseToken} to the device's first-identifier (migration-tolerant policy — server should claim ownership).")
                    kept.add(purchase)
                }
                EntitlementOwnershipDecision.DenyOtherUser ->
                    Log.warning("$source: skipped purchase ${purchase.orderId ?: purchase.purchaseToken} — obfuscatedAccountId does not match the current user.")
                EntitlementOwnershipDecision.DenyUntaggedOtherUser ->
                    Log.warning("$source: skipped untagged purchase ${purchase.orderId ?: purchase.purchaseToken} — the current user is not the device's first-identifier (cross-account leak guard).")
            }
        }
        return kept
    }

    // -- the purchase in flight ------------------------------------------------------------------

    /** Active purchase continuation — only one purchase flow at a time. */
    private var purchaseContinuation: CancellableContinuation<PurchaseResult>? = null

    /**
     * Product currently mid-purchase, carried through Play's non-OK callbacks (which do
     * not surface the requested product) so every purchase analytic keeps `product_id`.
     */
    internal var pendingProductId: String? = null

    /** The Play product type resolved by [purchase] (`"subs"` / `"inapp"`). */
    internal var pendingProductType: String? = null

    /**
     * Set BEFORE the connection wait and cleared on every exit (incl. [failPurchase]);
     * a second [purchase] while it is set fails with `purchase_in_progress`.
     */
    @Volatile internal var purchaseInFlight: Boolean = false

    /**
     * Which [purchase] call owns the purchase scope (0 = none). Minted when the
     * in-flight flag is set and reset by [clearPurchaseScope], so a call that loses its scope (cancelled,
     * [destroy]) never clears — or acts on — the scope of a newer purchase.
     */
    @Volatile private var purchaseScopeOwner: Long = 0L
    private var purchaseScopeSeq: Long = 0L

    /** ITEM_ALREADY_OWNED — the purchase's Activity, weakly held so a relaunch can reuse it. */
    private var purchaseActivity: java.lang.ref.WeakReference<Activity>? = null
    private var lastFlowParams: BillingFlowParams? = null
    /** The billing flow is relaunched at most once per purchase. */
    internal var relaunchUsed: Boolean = false
    /** How many times the billing flow was launched (observed by tests). */
    @Volatile internal var launchCount: Int = 0

    /** Attribution context for the current purchase. */
    var currentPaywallId: String? = null
    // ⚠️ PRE-EXISTING GAP, not a regression: nothing writes this, so `experiment_id` ships as "" on
    // every purchase event. The value lives on `PaywallContext.experiment`, which `handlePurchase` never
    // receives — closing it means threading the context down, not a one-line assignment.
    var currentExperimentId: String? = null

    /**
     * Paywall-supplied event metadata for the purchase currently in flight (AC-038 toggle states,
     * `promo_code`, anything a plan card injected). The billing manager is the single owner of the
     * purchase-event family, so the paywall hands its metadata down here instead of emitting a second
     * event. Purchase-scoped: cleared with the rest of the purchase scope.
     */
    internal var currentPaywallMetadata: Map<String, Any> = emptyMap()

    internal fun hasLiveContinuation(): Boolean = purchaseContinuation?.isActive == true

    /**
     * Does [owner] still own the purchase scope? An async handler captures
     * [purchaseScopeOwner] when Play answers and checks this before it resolves, clears or relaunches
     * anything: a handler whose purchase was cancelled while it waited must never act on the scope of a
     * newer purchase.
     */
    internal fun ownsScope(owner: Long): Boolean = owner != 0L && purchaseScopeOwner == owner

    /** Take the live continuation (nulling the field). Null when there is no live caller. */
    internal fun takeContinuation(): CancellableContinuation<PurchaseResult>? {
        val c = purchaseContinuation
        purchaseContinuation = null
        return c?.takeIf { it.isActive }
    }

    /** Clear every purchase-scoped field (incl. the paywall id, so a later report is unattributed). */
    internal fun clearPurchaseScope() {
        pendingProductId = null
        pendingProductType = null
        currentPaywallMetadata = emptyMap()
        currentPaywallId = null
        currentExperimentId = null
        purchaseInFlight = false
        purchaseScopeOwner = 0L
        purchaseActivity = null
        lastFlowParams = null
        relaunchUsed = false
    }

    /** Fold the paywall metadata into a purchase event without overwriting the canonical keys. */
    private fun MutableMap<String, Any>.withPaywallMetadata(metadata: Map<String, Any> = currentPaywallMetadata): MutableMap<String, Any> {
        for ((k, v) in metadata) if (!containsKey(k)) put(k, v)
        return this
    }

    // Process-lifecycle observer: every foreground entry runs the ON_START reconcile.
    private val foregroundObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_START) {
            scope.launch { reconcileSubscriptionState() }
        }
    }
    /**
     * Whether the observer is registered (or its registration is launched). Atomic: `initialize()` and
     * `destroy()` run on any thread, and the flag used to be a plain var set only inside the launched
     * coroutine, so two registrations before the first coroutine ran both launched one.
     */
    private val foregroundObserverRegistered = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Test seam: how many observer registrations were launched. */
    internal val foregroundObserverLaunches = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Play's purchase updates. Every owned-purchase branch goes through [completion].
     *
     * A non-OK answer carries no product, so it belongs to the purchase whose sheet is up:
     * the one with a live continuation. Without one (its caller was cancelled, and a newer purchase may be
     * waiting for its connection or product query) the answer is reported unattributed and resolves,
     * clears and relaunches nothing.
     */
    private val purchaseUpdateListener = PurchasesUpdatedListener { billingResult, purchases ->
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    scope.launch { handleSuccessfulPurchase(purchase) }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.debug("Purchase cancelled by user")
                val live = hasLiveContinuation()
                AppDNA.trackBillingEvent("purchase_canceled", buildMap {
                    put("paywall_id", if (live) currentPaywallId ?: "" else "")
                    if (live) pendingProductId?.let { put("product_id", it) }
                })
                if (live) resumePurchase(PurchaseResult.Cancelled)
            }
            // Never a restore, never firstOrNull(): the handler works on the purchase
            // matching pendingProductId, which is still set.
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                if (hasLiveContinuation()) {
                    Log.info("Item already owned — routing to the ITEM_ALREADY_OWNED handler")
                    val owner = purchaseScopeOwner
                    scope.launch { completion.handleItemAlreadyOwned(owner) }
                } else {
                    // The caller is gone, but Play says the product is owned: run the ON_START
                    // rescue now so an owned, unreported purchase is not left until the next foreground.
                    Log.warning("Item already owned for a purchase with no live caller — running the owned-purchase rescue")
                    rescueOwnedPurchases()
                }
            }
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> failAndResume("Item not owned", "item_not_owned", billingResult.debugMessage)
            // Finalization B-4 — an explicit, typed failure per BillingResponseCode.
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> failAndResume("service_disconnected", "service_disconnected", billingResult.debugMessage)
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> failAndResume("service_unavailable", "service_unavailable", billingResult.debugMessage)
            BillingClient.BillingResponseCode.SERVICE_TIMEOUT -> failAndResume("service_timeout", "service_timeout", billingResult.debugMessage)
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> failAndResume("billing_unavailable", "billing_unavailable", billingResult.debugMessage)
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> failAndResume("item_unavailable", "item_unavailable", billingResult.debugMessage)
            BillingClient.BillingResponseCode.DEVELOPER_ERROR -> failAndResume("developer_error", "developer_error", billingResult.debugMessage)
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> failAndResume("feature_not_supported", "feature_not_supported", billingResult.debugMessage)
            BillingClient.BillingResponseCode.NETWORK_ERROR -> failAndResume("network_error", "network_error", billingResult.debugMessage)
            BillingClient.BillingResponseCode.ERROR -> failAndResume("billing_error", "billing_error", billingResult.debugMessage)
            else -> failAndResume(
                "unknown_code_${billingResult.responseCode}",
                "unknown_code_${billingResult.responseCode}",
                billingResult.debugMessage,
            )
        }
    }

    /**
     * Track (while the purchase scope still carries its attribution), then resolve the caller. With no
     * live caller the failure is reported unattributed and the scope is left alone.
     */
    private fun failAndResume(error: String, reason: String, debugMessage: String?) {
        Log.warning("Purchase update: $reason ${debugMessage ?: ""}")
        val live = hasLiveContinuation()
        trackPurchaseFailed(
            reason, debugMessage,
            productId = if (live) pendingProductId else null,
            paywallId = if (live) currentPaywallId else "",
        )
        if (live) resumePurchase(PurchaseResult.Failed(error))
    }

    /**
     * Best-effort answer to "does [productId] auto-renew?" for the RevenueCat / Adapty forward paths:
     * the stored product type of the cached entitlement, then the Play SUBS
     * catalogue [PriceResolver] queried this session. Neither knows → `false`.
     */
    fun isKnownSubscriptionProduct(productId: String): Boolean =
        entitlementCache.productTypeOf(productId) == "subs" ||
            priceResolver.isKnownSubscriptionProduct(productId)

    /**
     * Finalization — shared purchase-event property builder used by
     * RevenueCatBridge + AdaptyBridge so direct-purchase paths emit the same shape as the native path.
     */
    fun purchaseEventProps(productId: String, provider: String): Map<String, Any> {
        val props = mutableMapOf<String, Any>(
            "product_id" to productId,
            "provider" to provider,
            "paywall_id" to (currentPaywallId ?: ""),
            "experiment_id" to (currentExperimentId ?: ""),
        )
        priceResolver.cachedPriceInfo(productId)?.let { info ->
            props["price"] = info.priceMicros / 1_000_000.0
            props["currency"] = info.currencyCode
        }
        return props
    }

    /**
     * One `purchase_failed` with a typed `reason` (B-4) and iOS's `error` / `error_type`.
     * [paywallId] defaults to the purchase in flight; [omitPaywallId] drops the key (the non-owning
     * direct API).
     */
    internal fun trackPurchaseFailed(
        reason: String,
        debugMessage: String?,
        productId: String? = null,
        errorType: String = "unknown",
        paywallId: String? = currentPaywallId,
        omitPaywallId: Boolean = false,
    ) {
        AppDNA.trackBillingEvent("purchase_failed", buildMap {
            if (!omitPaywallId) put("paywall_id", paywallId ?: "")
            put("reason", reason)
            put("error", debugMessage?.takeIf { it.isNotBlank() } ?: reason)
            put("error_type", errorType)
            if (!debugMessage.isNullOrBlank()) put("debug_message", debugMessage)
            if (!productId.isNullOrBlank()) put("product_id", productId)
        })
    }

    internal fun trackPurchasePending(productId: String, live: Boolean) {
        AppDNA.trackBillingEvent("purchase_pending", mapOf(
            "product_id" to productId,
            "paywall_id" to (if (live) currentPaywallId ?: "" else ""),
        ))
    }

    /** Kept for callers that classify by expiry; the SDK itself now uses the Play product type. */
    internal fun isSubscriptionPurchase(entitlement: Entitlement): Boolean = entitlement.expiresAt != null

    /**
     * The single `purchase_completed` emit for the native Play path — plus `subscription_started` when
     * the Play product type is SUBS (it used to key on `expiresAt`). Both events carry
     * the SAME envelope. `subscription_started` never comes from the lifecycle diff.
     *
     * `emitted_by: "sdk"` (through `AppDNA.trackBillingEvent`), `is_consumable`,
     * `original_transaction_id`, `quantity` (> 1 only); `transaction_id` is the real Play order id and
     * is OMITTED when there is none (no fake ids); a free trial reports price 0.
     * Reports from any entry other than the purchase path pass [paywallId] null → `paywall_id: ""` and
     * no paywall metadata.
     */
    internal fun trackPurchaseCompleted(
        productId: String,
        isTrial: Boolean,
        transactionId: String?,
        isSubscription: Boolean,
        isConsumable: Boolean? = null,
        originalTransactionId: String? = null,
        quantity: Int = 1,
        paywallId: String? = currentPaywallId,
        experimentId: String? = currentExperimentId,
        paywallMetadata: Map<String, Any> = currentPaywallMetadata,
    ) {
        val props = mutableMapOf<String, Any>(
            "product_id" to productId,
            "paywall_id" to (paywallId ?: ""),
            "is_trial" to isTrial,
            "provider" to "google_play",
        )
        transactionId?.takeIf { it.isNotBlank() }?.let { props["transaction_id"] = it }
        originalTransactionId?.takeIf { it.isNotBlank() }?.let { props["original_transaction_id"] = it }
        experimentId?.let { props["experiment_id"] = it }
        isConsumable?.let { props["is_consumable"] = it }
        if (quantity > 1) props["quantity"] = quantity
        try {
            priceResolver.cachedPriceInfo(productId)?.let { info ->
                props["price"] = if (isTrial) 0.0 else info.priceMicros / 1_000_000.0
                props["currency"] = info.currencyCode
            }
        } catch (_: Throwable) { /* best-effort */ }
        val envelope: Map<String, Any> = props.withPaywallMetadata(paywallMetadata)
        AppDNA.trackBillingEvent("purchase_completed", envelope)
        if (isSubscription) {
            AppDNA.trackBillingEvent("subscription_started", envelope)
        }
    }

    /**
     * Terminal-failure helper for the early returns inside [purchase] (no client, unknown product,
     * launch rejected): one `purchase_failed`, then the purchase scope is cleared (the in-flight
     * flag is cleared on every exit).
     */
    private fun failPurchase(
        message: String,
        reason: String,
        productId: String?,
        errorType: String = "unknown",
    ): PurchaseResult.Failed {
        trackPurchaseFailed(reason = reason, debugMessage = message, productId = productId, errorType = errorType)
        clearPurchaseScope()
        return PurchaseResult.Failed(message, errorType)
    }

    /** Connection manager handling BillingClient lifecycle. */
    internal val connectionManager = BillingConnectionManager(
        context = context,
        purchasesUpdatedListener = purchaseUpdateListener,
        factory = billingClientFactory,
    )

    /** Price resolver for querying product details. */
    internal val priceResolver = PriceResolver(connectionManager)

    /** The one owner of purchase completion. */
    internal val completion = PurchaseCompletion(this, PurchaseLedger(storage))

    // -- Initialization --

    /**
     * Initialize the billing system. Must be called before any billing operations.
     */
    fun initialize() {
        Log.info("NativeBillingManager: initializing (ownsTransactions=$ownsTransactions)")
        connectionManager.initialize()
        registerForegroundObserver()
    }

    internal fun registerForegroundObserver() {
        if (!foregroundObserverRegistered.compareAndSet(false, true)) return
        foregroundObserverLaunches.incrementAndGet()
        scope.launch {
            try {
                ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)
            } catch (e: Exception) {
                foregroundObserverRegistered.set(false)
                Log.warning("NativeBillingManager: failed to attach foreground observer: ${e.message}")
            }
        }
    }

    /**
     * Removes the process-lifecycle observer ON MAIN. `ProcessLifecycleOwner`'s registry rejects a
     * `removeObserver` from any other thread (IllegalStateException), and `destroy()` is reachable off
     * Main (`AppDNA.shutdown()` from a background thread): the rejection used to be swallowed, so the
     * observer — and this whole manager with it — stayed registered for the life of the process. The
     * registration also runs on Main (it is launched on [scope]), so a posted removal is ordered after
     * it; a registration that had not started yet is cancelled with [scope] by `destroy()`.
     */
    private fun unregisterForegroundObserver() {
        val remove = {
            if (foregroundObserverRegistered.getAndSet(false)) {
                try {
                    ProcessLifecycleOwner.get().lifecycle.removeObserver(foregroundObserver)
                } catch (e: Exception) {
                    Log.warning("NativeBillingManager: failed to detach foreground observer: ${e.message}")
                }
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            remove()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post { remove() }
        }
    }

    /**
     * An `ITEM_ALREADY_OWNED` that no live purchase can act on (its caller was cancelled
     * or its scope taken over) still means Play holds an owned purchase that may be unreported. Run the
     * ON_START reconcile — the same rescue a foreground entry runs — instead of waiting for the next one.
     */
    internal fun rescueOwnedPurchases() {
        scope.launch { reconcileSubscriptionState() }
    }

    /** Drain trigger (i)–(iv): delivery happens only in [PurchaseCompletion.drainDeliveries]. */
    internal fun triggerDrain() {
        scope.launch { completion.drainDeliveries() }
    }

    /**
     * The ON_START reconcile.
     *
     * Owning (`storeKit2`): [PurchaseCompletion.run] (`ON_START`) applies the completion plan to BOTH the
     * INAPP and the SUBS arm (a subscription whose purchase-path verify failed is reported once),
     * then the SUBS-only lifecycle diff runs against the persisted snapshot. Non-owning: a read-only
     * reconcile — local entitlements, no server call, no store write. The snapshot is saved
     * in both; only [diffAndEmit] is gated on [emitsLifecycleEvents] (rule 4).
     */
    internal suspend fun reconcileSubscriptionState() {
        if (!ownsTransactions) {
            reconcileReadOnly()
            return
        }
        applySnapshotDiff {
            val result = completion.run(PurchaseCompletion.Entry.ON_START)
            triggerDrain()
            if (result.okSubsProducts == null) return@applySnapshotDiff null // the SUBS query failed: keep the baseline
            val current = LinkedHashMap<String, SubSnapshot>()
            for (op in result.subsForSnapshot) {
                val pid = op.productId
                if (pid.isEmpty()) continue
                current[pid] = snapshotOf(op.purchase, pid)
            }
            current
        }
    }

    /** Read-only reconcile under a non-owning provider. */
    private suspend fun reconcileReadOnly() = applySnapshotDiff {
        val lists = queryOwnedReadOnly() ?: return@applySnapshotDiff null
        val (subs, inapp) = lists
        if (subs != null && inapp != null) replaceWithLocalEntitlements(subs, inapp)
        if (subs == null) return@applySnapshotDiff null
        val current = LinkedHashMap<String, SubSnapshot>()
        for (p in filterOwnedPurchases(subs, currentUserToken(), "reconcileReadOnly")) {
            if (p.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            val pid = p.products.firstOrNull() ?: continue
            current[pid] = snapshotOf(p, pid)
        }
        current
    }

    /**
     * Read Play → load → (price warm-up) → diff → save, one reconcile at a time. [readCurrent] (Play's
     * owned SUBS as snapshots, or null to keep the baseline) runs INSIDE [snapshotMutex], so the reconcile
     * that read Play later is always the one that applies later:
     *   - the warm-up suspends on a Play query, and two reconciles used to both load the SAME old snapshot
     *     across it and both emit the renewal (two `subscription_renewed` for one renewal);
     *   - a reconcile that read Play BEFORE a renewal but applied AFTER one that read it later diffed the
     *     older purchase against the newer snapshot: the order id changed, so it emitted a renewal
     *     backwards and saved the older snapshot, and the next reconcile emitted the real renewal again.
     * [isRenewal] also requires the purchase time not to go back, so a stale read can never be a renewal.
     */
    private suspend fun applySnapshotDiff(readCurrent: suspend () -> Map<String, SubSnapshot>?) = snapshotMutex.withLock {
        val current = readCurrent() ?: return@withLock
        val previous = loadLastSnapshot()
        if (emitsLifecycleEvents) {
            warmRenewalPrices(previous, current)
            diffAndEmit(previous, current)
        }
        saveSnapshot(current)
    }

    /** Serialises [applySnapshotDiff]. */
    private val snapshotMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * `subscription_renewed` carries `price` / `currency` (the names `purchase_completed` uses) from the
     * in-memory [PriceResolver] cache. A renewal is usually seen on a cold start, before anything has
     * queried the catalogue, so each renewed product that misses the cache is queried once (SUBS,
     * bounded) — the same warm-up the purchase path does. A failed lookup only omits the price.
     */
    private suspend fun warmRenewalPrices(previous: Map<String, SubSnapshot>, current: Map<String, SubSnapshot>) {
        val missing = current.filter { (pid, now) ->
            val prev = previous[pid]
            prev != null && isRenewal(prev, now) && priceResolver.cachedPriceInfo(pid) == null
        }.keys
        if (missing.isEmpty()) return
        val client = withTimeoutOrNull(PurchaseCompletion.WAIT_MS) { connectionManager.awaitConnectedClient() } ?: return
        for (pid in missing) {
            try {
                withTimeoutOrNull(PurchaseCompletion.WAIT_MS) { queryProductDetails(client, pid, BillingClient.ProductType.SUBS) }
                    ?.let { priceResolver.cacheFromPurchase(it.productDetails) }
            } catch (e: Exception) {
                Log.debug("subscription_renewed: price lookup for $pid failed: ${e.message}")
            }
        }
    }

    /**
     * A renewal is `purchase_time` advancing, or the Play order id's `..N` suffix changing —
     * only when BOTH ids are non-blank and differ, and the purchase time has not gone back (an older
     * read of the same subscription is never a renewal).
     */
    internal fun isRenewal(prev: SubSnapshot, now: SubSnapshot): Boolean =
        now.purchaseTime > prev.purchaseTime ||
            (now.purchaseTime >= prev.purchaseTime &&
                prev.transactionId != null && now.transactionId != null && prev.transactionId != now.transactionId)

    /** SUBS and INAPP (each null when its query failed), or null without a client. */
    private suspend fun queryOwnedReadOnly(): Pair<List<Purchase>?, List<Purchase>?>? {
        val client = withTimeoutOrNull(PurchaseCompletion.WAIT_MS) { connectionManager.awaitConnectedClient() } ?: return null
        suspend fun q(type: String): List<Purchase>? = try {
            withTimeoutOrNull(PurchaseCompletion.WAIT_MS) {
                client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build())
            }?.takeIf { it.billingResult.responseCode == BillingClient.BillingResponseCode.OK }?.purchasesList
        } catch (e: Exception) {
            null
        }
        return q(BillingClient.ProductType.SUBS) to q(BillingClient.ProductType.INAPP)
    }

    /**
     * "Local entitlements (read-only)": one entitlement per product of each PURCHASED,
     * identity-owned purchase (`store = google_play`, `status = active`, no expiry — Play's client API
     * carries none —, `isTrial = false`), typed by the query arm that returned it. PENDING gives nothing.
     */
    private fun replaceWithLocalEntitlements(subs: List<Purchase>, inapp: List<Purchase>) {
        val types = LinkedHashMap<String, String>()
        val out = LinkedHashMap<String, Entitlement>()
        val seen = HashSet<String>()
        for ((list, type) in listOf(subs to "subs", inapp to "inapp")) {
            for (p in filterOwnedPurchases(list, currentUserToken(), "localEntitlements")) {
                if (!seen.add(p.purchaseToken) || p.purchaseState != Purchase.PurchaseState.PURCHASED) continue
                for (pid in p.products) {
                    out[pid] = PurchaseCompletion.localEntitlement(pid)
                    types[pid] = type
                }
            }
        }
        entitlementCache.replaceAll(out.values.toList(), types)
    }

    /**
     * Compare previous and current snapshots and emit lifecycle events (SUBS only — a consumed INAPP
     * never enters the snapshot). Does no I/O.
     *
     * `subscription_canceled` / `subscription_renewal_failed` carry the LAST-SEEN
     * `transaction_id` / `original_transaction_id` from the snapshot, `subscription_renewed` the current
     * ones; absent ids are omitted, never sent empty. A renewal is `purchase_time` advancing, or
     * the Play order id's `..N` suffix changing — only when BOTH ids are non-blank and differ; in that
     * arm `purchase_time` is omitted unless it advanced. Cancels carry `cancel_semantics`.
     * `subscription_renewed` also carries `price` / `currency` when the product's price is cached
     * ([warmRenewalPrices] fills the cache first).
     */
    internal fun diffAndEmit(
        previous: Map<String, SubSnapshot>,
        current: Map<String, SubSnapshot>,
    ) {
        for ((productId, prev) in previous) {
            if (current[productId] != null) continue
            val props = buildMap<String, Any> {
                put("product_id", productId)
                prev.transactionId?.let { put("transaction_id", it) }
                prev.originalTransactionId?.let { put("original_transaction_id", it) }
            }
            // Both names are LITERALS at the call site — `check:event-name-parity` reads them.
            if (prev.isAutoRenewing) {
                AppDNA.trackBillingEvent("subscription_renewal_failed", props)
            } else {
                AppDNA.trackBillingEvent("subscription_canceled", props + ("cancel_semantics" to "vanished_not_renewing"))
            }
        }
        for ((productId, now) in current) {
            val prev = previous[productId] ?: continue
            if (!isRenewal(prev, now)) continue
            val timeAdvanced = now.purchaseTime > prev.purchaseTime
            AppDNA.trackBillingEvent("subscription_renewed", buildMap<String, Any> {
                put("product_id", productId)
                if (timeAdvanced) put("purchase_time", now.purchaseTime)
                now.transactionId?.let { put("transaction_id", it) }
                now.originalTransactionId?.let { put("original_transaction_id", it) }
                // The renewal's price: the base plan's recurring price (the last pricing phase — a renewal
                // is never a trial), with the same `price` / `currency` names as `purchase_completed`.
                // Omitted when the price is unknown (never sent as 0). Play's client API carries no charged
                // amount for an order (`Purchase` has no price), so this is the catalogue price — not a
                // promotional or grandfathered amount; iOS reports the charged `transaction.price`.
                // Renewal props: product_id, purchase_time, transaction_id, original_transaction_id, price,
                // currency and emitted_by — no `is_trial` (a renewal is never a trial); the server books a
                // device renewal's revenue from these.
                try {
                    priceResolver.cachedPriceInfo(productId)?.takeIf { it.currencyCode.isNotBlank() }?.let { info ->
                        put("price", info.priceMicros / 1_000_000.0)
                        put("currency", info.currencyCode)
                    }
                } catch (_: Throwable) { /* best-effort */ }
            })
        }
    }

    private fun snapshotOf(p: Purchase, productId: String): SubSnapshot {
        val orderId = p.orderId?.takeIf { it.isNotBlank() }
        return SubSnapshot(
            productId = productId,
            purchaseTime = p.purchaseTime,
            isAcknowledged = p.isAcknowledged,
            isAutoRenewing = p.isAutoRenewing,
            transactionId = orderId,
            originalTransactionId = orderId?.let { PurchaseCompletion.stripRenewalSuffix(it) },
        )
    }

    internal fun loadLastSnapshot(): Map<String, SubSnapshot> {
        val raw = storage?.getString(KEY_LAST_SUB_SNAPSHOT) ?: return emptyMap()
        return try {
            val arr = JSONArray(raw)
            val out = mutableMapOf<String, SubSnapshot>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val pid = obj.optString("productId", "")
                if (pid.isEmpty()) continue
                out[pid] = SubSnapshot(
                    productId = pid,
                    purchaseTime = obj.optLong("purchaseTime", 0L),
                    isAcknowledged = obj.optBoolean("isAcknowledged", false),
                    isAutoRenewing = obj.optBoolean("isAutoRenewing", false),
                    // optString defaults to "" — normalise blank to null on load too.
                    transactionId = obj.optString("transactionId", "").takeIf { it.isNotBlank() },
                    originalTransactionId = obj.optString("originalTransactionId", "").takeIf { it.isNotBlank() },
                )
            }
            out
        } catch (e: Exception) {
            Log.debug("loadLastSnapshot: parse failure ${e.message}")
            emptyMap()
        }
    }

    internal fun saveSnapshot(snapshot: Map<String, SubSnapshot>) {
        val storage = storage ?: return
        val arr = JSONArray()
        for ((_, s) in snapshot) {
            arr.put(JSONObject().apply {
                put("productId", s.productId)
                put("purchaseTime", s.purchaseTime)
                put("isAcknowledged", s.isAcknowledged)
                put("isAutoRenewing", s.isAutoRenewing)
                s.transactionId?.let { put("transactionId", it) }
                s.originalTransactionId?.let { put("originalTransactionId", it) }
            })
        }
        storage.setString(KEY_LAST_SUB_SNAPSHOT, arr.toString())
    }

    /**
     * Lightweight snapshot of a single Play subscription: the last-seen Play order id
     * and its suffix-stripped base, normalised blank → null.
     */
    internal class SubSnapshot(
        val productId: String,
        val purchaseTime: Long,
        val isAcknowledged: Boolean,
        val isAutoRenewing: Boolean,
        transactionId: String? = null,
        originalTransactionId: String? = null,
    ) {
        val transactionId: String? = transactionId?.takeIf { it.isNotBlank() }
        val originalTransactionId: String? = originalTransactionId?.takeIf { it.isNotBlank() }

        override fun equals(other: Any?): Boolean = other is SubSnapshot &&
            other.productId == productId && other.purchaseTime == purchaseTime &&
            other.isAcknowledged == isAcknowledged && other.isAutoRenewing == isAutoRenewing &&
            other.transactionId == transactionId && other.originalTransactionId == originalTransactionId

        override fun hashCode(): Int = listOf(productId, purchaseTime, isAcknowledged, isAutoRenewing, transactionId, originalTransactionId).hashCode()

        override fun toString(): String =
            "SubSnapshot($productId, $purchaseTime, ack=$isAcknowledged, autoRenew=$isAutoRenewing, tx=$transactionId, otx=$originalTransactionId)"
    }

    internal companion object {
        // Snapshot key persisted in LocalStorage so the reconcile survives process death.
        const val KEY_LAST_SUB_SNAPSHOT = "billing_last_sub_snapshot_v1"

        /**
         * Clear the persisted subscription snapshot. MUST be called on any identity change (sign-out
         * `reset()` and sign-in to a DIFFERENT user via `identify()`), or the new user's first reconcile
         * fabricates a phantom subscription_canceled / _renewal_failed against the previous user's snapshot.
         */
        fun clearPersistedSnapshot(storage: LocalStorage?) {
            storage?.setString(KEY_LAST_SUB_SNAPSHOT, "[]")
        }
    }

    // -- Purchase Flow --

    /**
     * Initiate a subscription or one-time-product purchase.
     *
     * Queries SUBS and INAPP in parallel (SUBS wins, iOS order), launches the matching billing flow and
     * suspends until Play answers. The purchase itself completes in [PurchaseCompletion] (verify → one
     * write → emit → finalize).
     * - a non-owning provider throws [BillingError.ProviderNotAvailable] BEFORE `purchase_started` and
     *   any Play call, with exactly one `purchase_failed{error_type: providerNotAvailable}`;
     * - a second call while one is in flight resolves `Failed("purchase_in_progress")`;
     * - the connection wait is bounded by 30 s → `purchase_failed{error_type: serverError}`.
     */
    suspend fun purchase(
        activity: Activity,
        productId: String,
        options: PurchaseOptions? = null,
    ): PurchaseResult {
        if (!ownsTransactions) {
            val message = BillingOwnership.refusalMessage(providerName)
            trackPurchaseFailed(
                reason = "provider_not_available", debugMessage = message, productId = productId,
                errorType = "providerNotAvailable", omitPaywallId = true,
            )
            throw BillingError.ProviderNotAvailable(message)
        }
        // Checked BEFORE any field is written; never touches the in-flight purchase's state.
        if (purchaseInFlight) {
            trackPurchaseFailed(
                reason = "purchase_in_progress", debugMessage = "A purchase is already in progress",
                productId = productId, errorType = "unknown", paywallId = "",
            )
            return PurchaseResult.Failed("purchase_in_progress", "unknown")
        }
        Log.info("Starting purchase for product: $productId")
        purchaseInFlight = true
        val scopeId = ++purchaseScopeSeq
        purchaseScopeOwner = scopeId
        pendingProductId = productId
        purchaseActivity = java.lang.ref.WeakReference(activity)
        relaunchUsed = false
        // THE ONE `purchase_started` for this purchase — paywall-driven or direct.
        AppDNA.trackBillingEvent("purchase_started", mutableMapOf<String, Any>(
            "product_id" to productId,
            "paywall_id" to (currentPaywallId ?: ""),
            "experiment_id" to (currentExperimentId ?: ""),
        ).withPaywallMetadata())

        // Every exit clears the scope. The returns below clear it themselves
        // ([failPurchase], [resumePurchase]); an exception — above all the caller's cancellation while
        // this call waits for the connection or the product query, before the continuation (and its
        // `invokeOnCancellation`) exists — is caught here. Guarded by [purchaseScopeOwner]: a scope already
        // cleared (e.g. by `invokeOnCancellation` or [destroy]) and reused by a newer purchase is left alone.
        try {
            val client = withTimeoutOrNull(PurchaseCompletion.WAIT_MS) { connectionManager.awaitConnectedClient() }
            if (purchaseScopeOwner != scopeId) return scopeLost(productId)
            if (client == null) {
                return failPurchase("BillingClient not connected", "billing_client_not_connected", productId, errorType = "serverError")
            }

            val resolved = coroutineScope {
                val subsDeferred = async { queryProductDetails(client, productId, BillingClient.ProductType.SUBS) }
                val inappDeferred = async { queryProductDetails(client, productId, BillingClient.ProductType.INAPP) }
                subsDeferred.await() ?: inappDeferred.await()
            }
            if (purchaseScopeOwner != scopeId) return scopeLost(productId)
            if (resolved == null) {
                return failPurchase("Product not found: $productId", "product_not_found", productId, errorType = "productNotFound")
            }

            val productDetails = resolved.productDetails
            val productType = resolved.productType
            pendingProductType = if (productType == BillingClient.ProductType.SUBS) "subs" else "inapp"

            // Warm the price cache so the purchase event carries price/currency.
            try { priceResolver.cacheFromPurchase(productDetails) } catch (_: Throwable) { /* best-effort */ }

            val selectedOffer = if (productType == BillingClient.ProductType.SUBS) {
                val token = options?.offerToken
                val offers = productDetails.subscriptionOfferDetails
                if (token != null) offers?.find { it.offerToken == token }
                else {
                    /*
                     * With no named offer, the FREE-TRIAL offer the paywall advertised, when
                     * Play returned one (it only does for a user who is eligible), chosen by the same
                     * rule that wrote the paywall's trial sentence. Otherwise the base plan (no
                     * offerId), never an arbitrary one. This used to be the base plan always, so the
                     * paywall promised a trial and the sheet charged the full price from day one.
                     */
                    val trialAt = offers?.let { list ->
                        StoreTrialPhase.firstFreeTrialOffer(list.map { offer ->
                            offer.pricingPhases.pricingPhaseList.map { StoreTrialPhase.Phase(it.priceAmountMicros, it.billingPeriod) }
                        })
                    }
                    trialAt?.let { offers[it] } ?: offers?.firstOrNull { it.offerId == null } ?: offers?.firstOrNull()
                }
            } else null

            val flowParamsBuilder = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .apply { selectedOffer?.let { setOfferToken(it.offerToken) } }
                        .build()
                ))

            // The host's token, else the current user's derived token (cross-account leak).
            val resolvedToken = options?.appAccountToken ?: currentUserToken()
            if (resolvedToken != null) {
                flowParamsBuilder.setObfuscatedAccountId(resolvedToken.toString())
            } else {
                Log.warning("NativeBillingManager.purchase: no appAccountToken — host should call AppDNA.identify(userId:) BEFORE purchase to avoid cross-account entitlement leaks.")
            }
            val flowParams = flowParamsBuilder.build()
            lastFlowParams = flowParams

            return suspendCancellableCoroutine { continuation ->
                purchaseContinuation = continuation
                continuation.invokeOnCancellation {
                    // A cancelled caller: a later report for this purchase has no live caller, so it is queued.
                    if (purchaseContinuation === continuation) {
                        purchaseContinuation = null
                        clearPurchaseScope()
                    }
                }
                launchCount++
                val launchResult = client.launchBillingFlow(activity, flowParams)
                when (launchResult.responseCode) {
                    BillingClient.BillingResponseCode.OK -> Unit
                    // The SYNCHRONOUS ITEM_ALREADY_OWNED goes to the same handler, before anything is cleared.
                    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
                        scope.launch { completion.handleItemAlreadyOwned(scopeId) }
                    else -> resumePurchase(
                        failPurchase(
                            "Failed to launch billing flow: ${launchResult.debugMessage}",
                            "launch_billing_flow_failed",
                            productId,
                        ),
                    )
                }
            }
        } catch (t: Throwable) {
            if (purchaseScopeOwner == scopeId) {
                purchaseContinuation = null
                clearPurchaseScope()
            }
            throw t
        }
    }

    /**
     * This [purchase] lost its scope while it waited: stop without launching the sheet and
     * without touching the scope, which may already belong to a newer purchase. Nothing is tracked, as
     * for [destroy].
     *
     * Before the continuation exists, [destroy] is the only thing that can take the scope.
     * The caller's own cancellation throws instead of returning here, and every other path that clears
     * it (the listener's non-OK branches, [PurchaseCompletion.handleItemAlreadyOwned], precedence (4), a
     * PENDING update, a verify failure) acts only for a live continuation or for the scope owner it
     * captured. So `Failed("shutdown")` is exact.
     */
    private fun scopeLost(productId: String): PurchaseResult {
        Log.warning("NativeBillingManager.purchase($productId): the purchase scope was cleared while waiting — not launching")
        return PurchaseResult.Failed("shutdown")
    }

    /**
     * ITEM_ALREADY_OWNED — relaunch the billing flow ONCE with the weakly held Activity, if it is
     * still alive. Returns false when it cannot (the caller then fails the purchase).
     */
    internal fun relaunchBillingFlow(): Boolean {
        val activity = purchaseActivity?.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return false
        val params = lastFlowParams ?: return false
        val client = connectionManager.getClient() ?: return false
        relaunchUsed = true
        launchCount++
        val result = client.launchBillingFlow(activity, params)
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                val owner = purchaseScopeOwner
                scope.launch { completion.handleItemAlreadyOwned(owner) }
            }
            else -> resumePurchase(
                failPurchase("Failed to relaunch billing flow: ${result.debugMessage}", "launch_billing_flow_failed", pendingProductId),
            )
        }
        return true
    }

    /**
     * Internal helper — query a single product against a specific product type. Null if not found.
     */
    internal suspend fun queryProductDetails(
        client: BillingClient,
        productId: String,
        @BillingClient.ProductType productType: String,
    ): ResolvedProduct? {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(productId)
                    .setProductType(productType)
                    .build()
            ))
            .build()
        val result = client.queryProductDetails(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.debug("queryProductDetails($productType) returned ${result.billingResult.responseCode} for $productId")
            return null
        }
        val details = result.productDetailsList?.firstOrNull() ?: return null
        return ResolvedProduct(details, productType)
    }

    internal data class ResolvedProduct(
        val productDetails: ProductDetails,
        @BillingClient.ProductType val productType: String,
    )

    // -- Restore --

    /**
     * Fix 1D / — the silent entitlement-cache refresh ([AppDNA.identify] and hosts).
     *
     * Owning: [PurchaseCompletion.run] (`IDENTIFY_REFRESH`) — it may report an unreported purchase
     * (`purchase_completed`, queued `onPurchaseCompleted`), then sends the in-lock owned list to
     * `/billing/restore` and MERGES the reply. Non-owning: a local, read-only rebuild — no server
     * call. Errors are logged, never thrown; a failed query leaves the cache unchanged.
     */
    suspend fun refreshEntitlementCache() = refreshEntitlementCache(anonymousToFirstUser = false)

    internal suspend fun refreshEntitlementCache(anonymousToFirstUser: Boolean) {
        try {
            if (!ownsTransactions) {
                val lists = queryOwnedReadOnly() ?: return
                val (subs, inapp) = lists
                if (subs != null && inapp != null) replaceWithLocalEntitlements(subs, inapp)
                return
            }
            val result = completion.run(PurchaseCompletion.Entry.IDENTIFY_REFRESH, anonymousToFirstUser = anonymousToFirstUser)
            triggerDrain()
            if (!result.queryOk) Log.warning("refreshEntitlementCache: purchase query failed — cache unchanged")
            result.restoreError?.let { Log.warning("refreshEntitlementCache: ${it.message} — cache unchanged") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.warning("refreshEntitlementCache: silent refresh failed — ${e.message}")
        }
    }

    /** What a restore produced, and which products the same call reported. */
    internal data class RestoreDetail(val entitlements: List<Entitlement>, val reportedProducts: Set<String>)

    /**
     * Restore the owned purchases (SUBS + INAPP).
     *
     * Every failure THROWS a typed [BillingError] — a
     * non-owning provider `ProviderNotAvailable`; a failed Play query or no client within 30 s
     * `ServerError` (Play `NETWORK_ERROR` / `SERVICE_UNAVAILABLE` → `NetworkError`); an offline `/restore`
     * `NetworkError`; a non-2xx `/restore` `ServerError`. The cache is left unchanged and
     * `onRestoreCompleted` does not fire. One `purchase_restore_failed` is tracked per failure.
     * `purchase_restored` is not emitted for a product this same call just reported.
     */
    suspend fun restorePurchases(): List<Entitlement> = restoreDetailed(trackFailure = true).entitlements

    internal suspend fun restoreDetailed(trackFailure: Boolean): RestoreDetail {
        try {
            if (!ownsTransactions) {
                throw BillingError.ProviderNotAvailable(BillingOwnership.refusalMessage(providerName))
            }
            Log.info("Restoring purchases (SUBS + INAPP)")
            val result = completion.run(PurchaseCompletion.Entry.RESTORE)
            if (!result.queryOk || result.restoreError != null) {
                if (result.queued > 0) triggerDrain()
                throw result.restoreError ?: result.queryError ?: BillingError.ServerError("purchase query failed")
            }
            val entitlements = result.restored.map { it.entitlement }
            for (e in entitlements) {
                if (e.productId in result.reportedProducts) continue
                AppDNA.trackBillingEvent("purchase_restored", mapOf("product_id" to e.productId))
            }
            fireOnRestoreCompleted(entitlements.map { it.productId })
            // The drain runs after onRestoreCompleted (FIFO on Main): a queued report is delivered after it.
            triggerDrain()
            return RestoreDetail(entitlements, result.reportedProducts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val typed: BillingError = e as? BillingError ?: BillingError.ServerError(e.message ?: "restore failed")
            Log.error("Restore failed: ${typed.message}")
            if (trackFailure) {
                AppDNA.trackBillingEvent("purchase_restore_failed", mapOf(
                    "error" to (typed.message ?: "unknown"),
                    "error_type" to billingErrorType(typed),
                ))
            }
            throw typed
        }
    }

    /** Main-thread dispatch of onRestoreCompleted to the host's billing delegate. Best-effort. */
    private fun fireOnRestoreCompleted(productIds: List<String>) {
        try {
            val delegate = AppDNA.billing.billingListener ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    delegate.onRestoreCompleted(productIds)
                } catch (e: Throwable) {
                    Log.warning("AppDNABillingDelegate.onRestoreCompleted threw: ${e.message}")
                }
            }
        } catch (_: Throwable) { /* best-effort */ }
    }

    // -- Product Queries --

    /**
     * Get product information for a list of subscription product IDs.
     */
    suspend fun getProducts(productIds: List<String>): List<ProductInfo> {
        return priceResolver.getSubscriptionProducts(productIds)
    }

    // -- Internal Helpers --

    /**
     * A purchase update from Play. PENDING resolves the caller only when it is the in-flight product;
     * a PURCHASED update goes through [PurchaseCompletion.run] (`PURCHASE`) and then the drain.
     */
    private suspend fun handleSuccessfulPurchase(purchase: Purchase) {
        if (!ownsTransactions) {
            Log.info("NativeBillingManager: ignoring a Play update — $providerName owns purchases")
            return
        }
        val productId = purchase.products.firstOrNull() ?: return
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            Log.info("Purchase is pending for product: $productId")
            val live = productId == pendingProductId && hasLiveContinuation()
            trackPurchasePending(productId, live)
            if (live) resumePurchase(PurchaseResult.Pending)
            return
        }
        completion.run(PurchaseCompletion.Entry.PURCHASE, listenerPurchase = purchase)
        triggerDrain()
    }

    /**
     * A verification failure of a Play purchase update: `purchase_failed` with `reason` = the server's
     * code (e.g. `store_credentials_missing`) or `verification_failed`, and `error_type` from
     * [billingErrorType] (`verificationFailed`; a 30 s timeout `networkError`); `onPurchaseFailed`; and,
     * for the live caller, `Failed` with that `errorType`. The token is left for the next rescue.
     */
    internal fun reportVerificationFailure(productId: String, e: Throwable, live: Boolean) {
        val errorType = billingErrorType(e)
        trackPurchaseFailed(
            reason = (e as? VerificationException)?.serverError ?: "verification_failed",
            debugMessage = e.message ?: "verification_error",
            productId = productId,
            errorType = errorType,
            paywallId = if (live) currentPaywallId else "",
        )
        try {
            val delegate = AppDNA.billing.billingListener
            if (delegate != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        delegate.onPurchaseFailed(productId, e)
                    } catch (err: Throwable) {
                        Log.warning("AppDNABillingDelegate.onPurchaseFailed threw: ${err.message}")
                    }
                }
            }
        } catch (_: Throwable) { /* best-effort */ }
        if (live) resumePurchase(PurchaseResult.Failed(e.message ?: "Verification failed", errorType))
    }

    /**
     * The live caller's fire-and-forget billing delegate (the delegate read at emit time, skipped when
     * null): `onPurchaseCompleted`.
     *
     * `onPaywallPurchaseCompleted` is NOT called here. A paywall purchase always runs through
     * `PaywallManager.handlePurchase`, which calls it once when `AppDNA.billing.purchase` returns — as
     * iOS does (`PaywallManager.swift`). Calling it here as well fired it twice for every paywall
     * purchase whenever the host's paywall delegate was the one the paywall was presented with.
     */
    internal fun postPurchaseDelegates(productId: String, txInfo: ai.appdna.sdk.TransactionInfo) {
        try {
            val delegate = AppDNA.billing.billingListener
            if (delegate != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        delegate.onPurchaseCompleted(productId, txInfo)
                    } catch (e: Throwable) {
                        Log.warning("AppDNABillingDelegate.onPurchaseCompleted threw: ${e.message}")
                    }
                }
            }
        } catch (_: Throwable) { /* best-effort */ }
    }

    /**
     * Resume the suspended purchase with [result] and clear the purchase scope. Every terminal purchase
     * outcome flows through here, so nothing leaks into the next purchase.
     */
    internal fun resumePurchase(result: PurchaseResult) {
        val continuation = takeContinuation()
        clearPurchaseScope()
        continuation?.resume(result) {}
    }

    /**
     * Shut down the billing system and release all resources. A purchase in flight resolves
     * `Failed("shutdown")`.
     */
    fun destroy() {
        Log.info("NativeBillingManager: destroying")
        resumePurchase(PurchaseResult.Failed("shutdown"))
        completion.drainReady = false
        unregisterForegroundObserver()
        scope.cancel()
        connectionManager.destroy()
        entitlementCache.stopObserving()
    }
}
