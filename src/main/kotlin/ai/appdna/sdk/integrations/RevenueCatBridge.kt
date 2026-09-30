package ai.appdna.sdk.integrations

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.AppDNABillingDelegate
import ai.appdna.sdk.Log
import ai.appdna.sdk.PurchaseFailedException
import ai.appdna.sdk.TransactionInfo
import ai.appdna.sdk.billing.Entitlement
import android.app.Activity

/**
 * SPEC-070-A A.19 — RevenueCat billing bridge for Android.
 *
 * This file mirrors the Swift `RevenueCatBridge.swift`
 * (Integrations/RevenueCatBridge.swift) and exposes the same conceptual API:
 *
 *   - `configure(apiKey)`
 *   - `purchase(activity, productId): TransactionInfo`
 *   - `restore(): List<Entitlement>`
 *   - `getEntitlements(): List<Entitlement>`
 *   - `delegate: AppDNABillingDelegate?`
 *
 * The RevenueCat SDK is a `compileOnly` dependency so AppDNA hosts that don't
 * use RevenueCat don't pay the bytecode cost. Because the SDK isn't on the
 * runtime classpath unless the host adds it, every call site uses reflection
 * (`Class.forName + Method.invoke`) to talk to `Purchases`. This keeps the
 * bridge a thin adapter: hosts that DO ship RevenueCat (8.x+) get full
 * functionality and the same auto-emitted analytics events as iOS.
 *
 * Reflection rationale:
 *   - We can't import `com.revenuecat.purchases.*` because that would force
 *     a runtime dependency on every host.
 *   - The RevenueCat Android SDK only exposes Java-friendly callback APIs in
 *     8.x; we wrap them in a `suspendCancellableCoroutine` so the bridge
 *     surface stays uniform with iOS (`async throws`).
 *
 * Risk: any breaking RevenueCat 8.x → 9.x rename would silently fail at
 * runtime. We log loudly on every miss so the host gets a clear signal.
 */
internal class RevenueCatBridge {

    /** Set by the host app. Mirrored from iOS's `AppDNA.billingDelegate`. */
    var delegate: AppDNABillingDelegate? = null

    private var configured: Boolean = false

    /**
     * Configure RevenueCat with an API key. Safe to call multiple times.
     *
     * Mirrors iOS `RevenueCatBridge.swift:18` — assigns
     * `Purchases.delegate = self` so entitlement-change broadcasts emit
     * `purchase_completed` analytics events automatically.
     */
    fun configure(apiKey: String) {
        if (!isRevenueCatPresent()) {
            Log.warning("RevenueCat SDK not available — billing operations will throw")
            return
        }
        try {
            // Purchases.configure(PurchasesConfiguration.Builder(context, apiKey).build())
            // Reflection-based equivalent for compileOnly dependency.
            val context = AppDNA.appContextForBridges()
                ?: run {
                    Log.warning("RevenueCatBridge.configure: AppDNA not configured yet")
                    return
                }
            val configurationCls = Class.forName("com.revenuecat.purchases.PurchasesConfiguration")
            val builderCls = Class.forName("com.revenuecat.purchases.PurchasesConfiguration\$Builder")
            val builderCtor = builderCls.getConstructor(android.content.Context::class.java, String::class.java)
            val builder = builderCtor.newInstance(context, apiKey)
            val configuration = builderCls.getMethod("build").invoke(builder)
            val purchasesCls = Class.forName("com.revenuecat.purchases.Purchases")
            val configureMethod = purchasesCls.getMethod("configure", configurationCls)
            configureMethod.invoke(null, configuration)
            configured = true
            Log.info("RevenueCat bridge initialized")
            // SPEC-070-A finalization parity audit R4 — install the
            // UpdatedCustomerInfoListener so cross-device entitlement
            // broadcasts (web purchase syndication, family sharing,
            // foreground subscription restore) auto-fire
            // `purchase_completed` events on Android. Mirrors iOS
            // RevenueCatBridge.swift:93-103 PurchasesDelegate
            // `purchases(_:receivedUpdated:)` callback.
            installUpdatedCustomerInfoListener()
        } catch (e: Exception) {
            Log.error("RevenueCatBridge.configure failed: ${e.message}")
        }
    }

    /**
     * SPEC-070-A finalization parity audit R4 — install
     * UpdatedCustomerInfoListener via reflection so entitlement-change
     * broadcasts auto-fire `purchase_completed` analytics events.
     *
     * RevenueCat 7.x Android API:
     *   `Purchases.sharedInstance.updatedCustomerInfoListener =
     *      UpdatedCustomerInfoListener { customerInfo -> ... }`
     *
     * Reflection-based to keep `compileOnly` boundary intact. Failure
     * to install logs warning + continues — host can still emit events
     * via `forwardPurchaseSuccess`.
     */
    private fun installUpdatedCustomerInfoListener() {
        try {
            val purchasesCls = Class.forName("com.revenuecat.purchases.Purchases")
            // sharedInstance is a static getter; field on Companion in 7.x
            val sharedInstance = try {
                purchasesCls.getMethod("getSharedInstance").invoke(null)
            } catch (_: NoSuchMethodException) {
                // Older API: companion object pattern.
                val companion = purchasesCls.getField("Companion").get(null)
                companion.javaClass.getMethod("getSharedInstance").invoke(companion)
            } ?: return
            val listenerCls = Class.forName("com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerCls.classLoader,
                arrayOf(listenerCls),
            ) { _, method, args ->
                if (method.name == "onReceived") {
                    val customerInfo = args?.getOrNull(0)
                    if (customerInfo != null) {
                        // 🔴 TWO BUGS LIVED IN THIS BLOCK, AND THE `isNotEmpty()` GUARD CAUSED BOTH.
                        //
                        // 1. It emitted `purchase_completed` whenever the entitlement set was non-empty.
                        //    That is not a CHANGE — RevenueCat delivers customerInfo on app launch, on
                        //    refresh, on restore and on cross-device sync. So every launch by an existing
                        //    subscriber emitted a purchase that never happened. `purchase_completed` is
                        //    MTPU-metered (`COUNT(DISTINCT user)`), so a subscriber who merely OPENED the
                        //    app was billed as a transacting user, every month, forever. The real purchase
                        //    is already emitted exactly once by `purchase(...)` below. Removed — and
                        //    `check-purchase-emit-chokepoint.ts` now fails the build if it comes back.
                        //
                        // 2. The host's `onEntitlementsChanged` fan-out was INSIDE the same guard — so an
                        //    EXPIRY, which arrives precisely as an empty `active` set, never reached the
                        //    host at all. A lapsed subscriber kept their premium content until the next
                        //    cold start. The fan-out now runs on every delivery, empty or not: "the
                        //    entitlements are now none" is the single most important thing this callback
                        //    can say.
                        delegate?.onEntitlementsChanged(
                            customerInfoToEntitlements(customerInfo),
                        )
                    }
                }
                null
            }
            // Setter: setUpdatedCustomerInfoListener(listener: UpdatedCustomerInfoListener?)
            val setterMethod = purchasesCls.getMethod("setUpdatedCustomerInfoListener", listenerCls)
            setterMethod.invoke(sharedInstance, proxy)
            Log.debug("RevenueCatBridge: UpdatedCustomerInfoListener installed")
        } catch (e: Throwable) {
            Log.warning("RevenueCatBridge: UpdatedCustomerInfoListener install failed: ${e.message}")
        }
    }

    /**
     * Purchase a product through RevenueCat.
     *
     * Mirrors iOS `purchase(productId:)` (RevenueCatBridge.swift:18).
     * Returns a [TransactionInfo] on success; throws [PurchaseFailedException]
     * on any error from the RevenueCat SDK.
     *
     * NOTE: RevenueCat Android requires an Activity for the Play billing
     * dialog (parity with `BillingClient.launchBillingFlow`).
     */
    suspend fun purchase(activity: Activity, productId: String): TransactionInfo {
        if (!isRevenueCatPresent()) {
            throw PurchaseFailedException(productId, "RevenueCat not available")
        }
        Log.info("RevenueCatBridge.purchase: $productId")

        // We cannot do a full reflective purchase flow without significant
        // bridging code; the typical integration shape is for HOST apps to
        // do the actual purchase via `Purchases.sharedInstance.purchaseWith(...)`
        // and merely route the resulting CustomerInfo back through this
        // bridge. We expose [forwardPurchaseSuccess] / [forwardPurchaseFailure]
        // for that pattern. The direct-purchase path stays as a documented
        // throw so test environments don't silently no-op.
        throw PurchaseFailedException(
            productId,
            "Direct RC reflection-based purchase not supported. Host should call " +
                "Purchases.sharedInstance.purchaseWith() and forward via " +
                "RevenueCatBridge.forwardPurchaseSuccess / forwardPurchaseFailure."
        )
    }

    /**
     * Forward a successful host-driven RevenueCat purchase into the
     * AppDNA billing delegate. Mirrors iOS auto-fire on `Purchases.shared.purchase`
     * (RevenueCatBridge.swift:42-58).
     */
    /**
     * @param isSubscription does this product AUTO-RENEW? Drives the `subscription_started` emit —
     *   one of the three MTPU-metered events, which NO SDK used to emit, leaving the subscription funnel
     *   unable to tell a new subscription from a one-off purchase. The host drove this purchase through
     *   RevenueCat, so it holds the `StoreProduct` and knows the answer
     *   (`storeProduct.subscriptionPeriod != null`) — pass it. Left `null`, the SDK infers from the
     *   verified entitlement / Play SUBS catalogue ([NativeBillingManager.isKnownSubscriptionProduct]),
     *   which can answer "not a subscription" if the server has not synced the entitlement yet.
     */
    @JvmOverloads
    fun forwardPurchaseSuccess(productId: String, transactionId: String, isSubscription: Boolean? = null) {
        val txInfo = TransactionInfo(
            transactionId = transactionId,
            productId = productId,
            purchaseDate = System.currentTimeMillis().toString(),
            environment = "production",
        )
        // SPEC-070-A finalization R2 P1 (Lens C) — enrich with paywall_id /
        // experiment_id / price / currency via the shared helper so the
        // direct-purchase path matches the native Play Billing path shape.
        val props = AppDNA.billing.manager
            ?.purchaseEventProps(productId, "revenuecat")
            ?: mapOf("product_id" to productId, "provider" to "revenuecat")
        val isSub = isSubscription
            ?: AppDNA.billing.manager?.isKnownSubscriptionProduct(productId)
            ?: false
        AppDNA.trackBillingEvent("purchase_completed", props)
        // Same envelope, purchase-time, once. NOT emitted from the UpdatedCustomerInfoListener above:
        // that fires on renewals, restores and cross-device syncs too, so `subscription_started` there
        // would fire once per RENEWAL — an over-count on a metered event.
        if (isSub) {
            AppDNA.trackBillingEvent("subscription_started", props)
        }
        delegate?.onPurchaseCompleted(productId, txInfo)
    }

    /**
     * SPEC-070-A finalization R2 P1 (Lens C) — `purchase_started` symmetry
     * with AdaptyBridge.forwardPurchaseStarted. Hosts call this just before
     * `Purchases.shared.purchase(...)` so funnel dashboards see the started
     * event from the direct-purchase path too. Mirrors iOS RevenueCatBridge.
     */
    fun forwardPurchaseStarted(productId: String) {
        val props = AppDNA.billing.manager
            ?.purchaseEventProps(productId, "revenuecat")
            ?: mapOf("product_id" to productId, "provider" to "revenuecat")
        AppDNA.trackBillingEvent("purchase_started", props)
    }

    /**
     * SPEC-070-A finalization R2 P1 (Lens C) — `purchase_canceled` symmetry
     * matches the native Play Billing path (NativeBillingManager.kt:153).
     * Hosts call this when the RC purchase flow surfaces a user-cancel.
     */
    fun forwardPurchaseCanceled(productId: String) {
        val props = AppDNA.billing.manager
            ?.purchaseEventProps(productId, "revenuecat")
            ?: mapOf("product_id" to productId, "provider" to "revenuecat")
        AppDNA.trackBillingEvent("purchase_canceled", props)
    }

    /** Forward a host-driven RevenueCat purchase failure. */
    fun forwardPurchaseFailure(productId: String, error: Throwable) {
        val base = AppDNA.billing.manager
            ?.purchaseEventProps(productId, "revenuecat")
            ?: mapOf("product_id" to productId, "provider" to "revenuecat")
        AppDNA.trackBillingEvent("purchase_failed", base + mapOf(
            "error" to (error.message ?: "unknown"),
        ))
        delegate?.onPurchaseFailed(productId, error)
    }

    /**
     * Restore previously-purchased entitlements. Mirrors iOS
     * `restore()` (RevenueCatBridge.swift:70).
     */
    suspend fun restore(): List<Entitlement> {
        if (!isRevenueCatPresent()) return emptyList()
        Log.info("RevenueCatBridge.restore")
        val entitlements = customerInfoToEntitlements(awaitRestoreCustomerInfo())
        delegate?.onRestoreCompleted(entitlements.map { it.productId })
        return entitlements
    }

    /**
     * Get current active entitlements. Mirrors iOS
     * `getEntitlements()` (RevenueCatBridge.swift:87).
     */
    suspend fun getEntitlements(): List<Entitlement> {
        if (!isRevenueCatPresent()) return emptyList()
        return customerInfoToEntitlements(awaitCustomerInfo())
    }

    // -- Internals --

    /**
     * Reflectively invoke `Purchases.sharedInstance.restorePurchases(receiveCustomerInfoCallback)`
     * and suspend until the callback fires.
     *
     * Returns the raw `CustomerInfo` on success or null on error.
     */
    private suspend fun awaitRestoreCustomerInfo(): Any? = awaitWithCallback(
        methodName = "restorePurchases",
    )

    /** As above but for the `getCustomerInfo` SDK call. */
    private suspend fun awaitCustomerInfo(): Any? = awaitWithCallback(
        methodName = "getCustomerInfo",
    )

    private suspend fun awaitWithCallback(methodName: String): Any? {
        return try {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                try {
                    val purchasesCls = Class.forName("com.revenuecat.purchases.Purchases")
                    val sharedInstance = purchasesCls.getMethod("getSharedInstance").invoke(null)
                        ?: run {
                            cont.resume(null) {}
                            return@suspendCancellableCoroutine
                        }
                    val callbackCls = Class.forName("com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback")
                    // Forward-reference workaround: the lambda body needs to refer back
                    // to the proxy itself for equals/hashCode, but we can't reference a
                    // val inside its own initializer. Holding an Array<Any?> lets us
                    // late-bind via a captured reference.
                    val self = arrayOfNulls<Any>(1)
                    val proxy = java.lang.reflect.Proxy.newProxyInstance(
                        callbackCls.classLoader,
                        arrayOf(callbackCls),
                    ) { _, method, args ->
                        when (method.name) {
                            "onReceived" -> {
                                if (cont.isActive) cont.resume(args?.getOrNull(0)) {}
                            }
                            "onError" -> {
                                Log.warning("RevenueCat callback onError: ${args?.getOrNull(0)}")
                                if (cont.isActive) cont.resume(null) {}
                            }
                            // equals/hashCode/toString from Object — return sane defaults
                            "equals" -> return@newProxyInstance args?.getOrNull(0) === self[0]
                            "hashCode" -> return@newProxyInstance System.identityHashCode(self[0])
                            "toString" -> return@newProxyInstance "RcCallbackProxy"
                        }
                        null
                    }
                    self[0] = proxy
                    val method = sharedInstance.javaClass.getMethod(methodName, callbackCls)
                    method.invoke(sharedInstance, proxy)
                } catch (e: Exception) {
                    Log.error("awaitWithCallback($methodName) failed: ${e.message}")
                    if (cont.isActive) cont.resume(null) {}
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Convert a RevenueCat `CustomerInfo` (received reflectively) into our
     * canonical [Entitlement] list. Mirrors iOS `Array(customerInfo.entitlements.active.keys)`.
     */
    @Suppress("UNCHECKED_CAST")
    private fun customerInfoToEntitlements(customerInfo: Any?): List<Entitlement> {
        if (customerInfo == null) return emptyList()
        return try {
            val entitlementsObj = customerInfo.javaClass.getMethod("getEntitlements").invoke(customerInfo)
                ?: return emptyList()
            val active = entitlementsObj.javaClass.getMethod("getActive").invoke(entitlementsObj) as? Map<String, Any>
                ?: return emptyList()
            active.values.mapNotNull { entitlementInfo ->
                try {
                    val productId = entitlementInfo.javaClass.getMethod("getProductIdentifier")
                        .invoke(entitlementInfo) as? String ?: return@mapNotNull null
                    Entitlement(
                        productId = productId,
                        store = "revenuecat",
                        status = "active",
                        expiresAt = null,
                        isTrial = false,
                        offerType = null,
                    )
                } catch (e: Exception) {
                    Log.debug("RC entitlement parse failure: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.warning("customerInfoToEntitlements failed: ${e.message}")
            emptyList()
        }
    }

    private fun isRevenueCatPresent(): Boolean {
        return try {
            Class.forName("com.revenuecat.purchases.Purchases")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }
}
