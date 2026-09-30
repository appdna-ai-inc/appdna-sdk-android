package ai.appdna.sdk.integrations

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.AppDNABillingDelegate
import ai.appdna.sdk.Log
import ai.appdna.sdk.PurchaseFailedException
import ai.appdna.sdk.TransactionInfo
import ai.appdna.sdk.billing.Entitlement
import android.app.Activity

/**
 * SPEC-070-A finalization B-5 — Adapty billing bridge for Android.
 *
 * Mirrors iOS [AdaptyBridge.swift](packages/appdna-sdk-ios/Sources/AppDNASDK/Integrations/AdaptyBridge.swift)
 * and exposes the same conceptual API:
 *
 *   - `configure(apiKey)`
 *   - `purchase(activity, productId): TransactionInfo`
 *   - `restore(): List<Entitlement>`
 *   - `getEntitlements(): List<Entitlement>`
 *   - `delegate: AppDNABillingDelegate?`
 *
 * Like [RevenueCatBridge], the Adapty SDK is a `compileOnly` dependency so
 * AppDNA hosts that don't use Adapty pay no bytecode cost. Every call site
 * uses reflection (`Class.forName + Method.invoke`) to talk to the Adapty
 * singleton. Hosts that DO ship Adapty 2.x get full functionality and the
 * same auto-emitted analytics events as the iOS bridge.
 *
 * Reflection rationale (same as RevenueCatBridge):
 *   - We can't import `com.adapty.*` because that would force a runtime
 *     dependency on every host.
 *   - The Adapty Android SDK exposes `ResultCallback<T>` interfaces in 2.x;
 *     we wrap them in a `suspendCancellableCoroutine` so the bridge
 *     surface stays uniform with iOS (`async throws`).
 *
 * Risk: any breaking Adapty 2.x → 3.x rename will silently fail at runtime.
 * We log loudly on every miss so the host gets a clear signal.
 *
 * **Direct-purchase limitation** (mirrors RevenueCatBridge): a fully
 * reflective Adapty purchase flow requires significant bridging code
 * (Activity-bound dialog + AdaptyPaywall product resolution). The typical
 * integration shape is for HOST apps to call `Adapty.makePurchase(...)`
 * themselves and forward the resulting profile via
 * [forwardPurchaseSuccess] / [forwardPurchaseFailure]. The direct-purchase
 * path stays as a documented throw so test environments don't silently
 * no-op.
 */
internal class AdaptyBridge {

    /** Set by the host app. Mirrored from iOS's `AppDNA.billingDelegate`. */
    var delegate: AppDNABillingDelegate? = null

    private var configured: Boolean = false

    /**
     * Configure Adapty with an API key. Safe to call multiple times.
     *
     * Mirrors iOS `AdaptyBridge.swift:24-32` — calls `Adapty.activate(apiKey)`.
     */
    fun configure(apiKey: String) {
        if (!isAdaptyPresent()) {
            Log.warning("Adapty SDK not available — billing operations will throw")
            return
        }
        try {
            val context = AppDNA.appContextForBridges()
                ?: run {
                    Log.warning("AdaptyBridge.configure: AppDNA not configured yet")
                    return
                }
            // Adapty.activate(context, apiKey) — Adapty 2.x SDK signature.
            val adaptyCls = Class.forName("com.adapty.Adapty")
            val activateMethod = adaptyCls.getMethod(
                "activate",
                android.content.Context::class.java,
                String::class.java,
            )
            activateMethod.invoke(null, context, apiKey)
            configured = true
            Log.info("Adapty bridge initialized")
        } catch (e: Exception) {
            Log.error("AdaptyBridge.configure failed: ${e.message}")
        }
    }

    /**
     * Purchase a product through Adapty. Mirrors iOS
     * `purchase(productId:)` (AdaptyBridge.swift:36).
     *
     * Like RevenueCatBridge, we throw a documented [PurchaseFailedException]
     * for the direct-purchase path because Adapty's purchase API requires
     * an `AdaptyPaywall` + `AdaptyPaywallProduct` reference resolved from
     * the host's actual paywall flow. Hosts should drive the purchase
     * themselves and forward via [forwardPurchaseSuccess] /
     * [forwardPurchaseFailure].
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun purchase(activity: Activity, productId: String): TransactionInfo {
        if (!isAdaptyPresent()) {
            throw PurchaseFailedException(productId, "Adapty not available")
        }
        Log.info("AdaptyBridge.purchase: $productId")
        throw PurchaseFailedException(
            productId,
            "Direct Adapty reflection-based purchase not supported. Host should " +
                "call Adapty.makePurchase(activity, paywall, product, callback) and " +
                "forward via AdaptyBridge.forwardPurchaseSuccess / forwardPurchaseFailure.",
        )
    }

    /**
     * SPEC-070-A finalization B3#P2 — forward a host-driven Adapty
     * purchase START into AppDNA analytics. Mirrors iOS
     * AdaptyBridge.swift:37-40 which fires `purchase_started` before
     * invoking `Adapty.makePurchase`. Hosts must call this just before
     * `Adapty.makePurchase(...)` so cross-platform funnels filtering on
     * `purchase_started` aren't undercount on Android.
     */
    fun forwardPurchaseStarted(productId: String) {
        // SPEC-070-A finalization R2 P1 (Lens C) — enrich with
        // paywall_id/experiment_id/price/currency via shared helper.
        val props = AppDNA.billing.manager
            ?.purchaseEventProps(productId, "adapty")
            ?: mapOf("product_id" to productId, "provider" to "adapty")
        AppDNA.trackBillingEvent("purchase_started", props)
    }

    /**
     * SPEC-070-A finalization R2 P1 (Lens C) — `purchase_canceled` symmetry
     * with the native path. Hosts call this when Adapty.makePurchase surfaces
     * a user-cancel.
     */
    fun forwardPurchaseCanceled(productId: String) {
        val props = AppDNA.billing.manager
            ?.purchaseEventProps(productId, "adapty")
            ?: mapOf("product_id" to productId, "provider" to "adapty")
        AppDNA.trackBillingEvent("purchase_canceled", props)
    }

    /**
     * Forward a successful host-driven Adapty purchase into the AppDNA
     * billing delegate. Mirrors iOS auto-fire on `Adapty.makePurchase`
     * (AdaptyBridge.swift:42-69).
     */
    /**
     * @param isSubscription does this product AUTO-RENEW? Drives the `subscription_started` emit — one
     *   of the three MTPU-metered events, which NO SDK used to emit. The host drove this purchase
     *   through Adapty and holds the `AdaptyPaywallProduct`, so it knows the answer — pass it. Left
     *   `null`, the SDK infers via [NativeBillingManager.isKnownSubscriptionProduct] (verified
     *   entitlement, then the Play SUBS catalogue), which can answer "not a subscription" if the server
     *   has not synced the entitlement yet.
     */
    @JvmOverloads
    fun forwardPurchaseSuccess(productId: String, transactionId: String, isSubscription: Boolean? = null) {
        val txInfo = TransactionInfo(
            transactionId = transactionId,
            productId = productId,
            purchaseDate = System.currentTimeMillis().toString(),
            environment = "production",
        )
        val props = AppDNA.billing.manager
            ?.purchaseEventProps(productId, "adapty")
            ?: mapOf("product_id" to productId, "provider" to "adapty")
        val isSub = isSubscription
            ?: AppDNA.billing.manager?.isKnownSubscriptionProduct(productId)
            ?: false
        AppDNA.trackBillingEvent("purchase_completed", props)
        // Same envelope, purchase-time, once. Renewals stay the reconcile diff's business
        // (`subscription_renewed`) — emitting `subscription_started` there would double-count.
        if (isSub) {
            AppDNA.trackBillingEvent("subscription_started", props)
        }
        delegate?.onPurchaseCompleted(productId, txInfo)
    }

    /** Forward a host-driven Adapty purchase failure. */
    fun forwardPurchaseFailure(productId: String, error: Throwable) {
        val base = AppDNA.billing.manager
            ?.purchaseEventProps(productId, "adapty")
            ?: mapOf("product_id" to productId, "provider" to "adapty")
        AppDNA.trackBillingEvent("purchase_failed", base + mapOf(
            "error" to (error.message ?: "unknown"),
        ))
        delegate?.onPurchaseFailed(productId, error)
    }

    /**
     * Restore previously-purchased entitlements. Mirrors iOS
     * `restore()` (AdaptyBridge.swift:97).
     */
    suspend fun restore(): List<Entitlement> {
        if (!isAdaptyPresent()) return emptyList()
        Log.info("AdaptyBridge.restore")
        val entitlements = profileToEntitlements(awaitRestoreProfile())
        AppDNA.trackBillingEvent(
            "purchase_restored",
            mapOf(
                "restored_count" to entitlements.size,
                "provider" to "adapty",
            ),
        )
        delegate?.onRestoreCompleted(entitlements.map { it.productId })
        return entitlements
    }

    /**
     * Get current active access levels. Mirrors iOS
     * `getEntitlements()` (AdaptyBridge.swift:120).
     */
    suspend fun getEntitlements(): List<Entitlement> {
        if (!isAdaptyPresent()) return emptyList()
        return profileToEntitlements(awaitProfile())
    }

    // -- Internals --

    /**
     * Reflectively invoke `Adapty.restorePurchases(resultCallback)` and
     * suspend until the callback fires.
     *
     * Returns the raw `AdaptyProfile` on success or null on error.
     */
    private suspend fun awaitRestoreProfile(): Any? = awaitProfileCallback(
        methodName = "restorePurchases",
    )

    /** As above but for `Adapty.getProfile(resultCallback)`. */
    private suspend fun awaitProfile(): Any? = awaitProfileCallback(
        methodName = "getProfile",
    )

    private suspend fun awaitProfileCallback(methodName: String): Any? {
        return try {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                try {
                    val adaptyCls = Class.forName("com.adapty.Adapty")
                    // Adapty 2.x callback interface — `ResultCallback<T>` with a
                    // single `onResult(AdaptyResult<T>)` method. We don't import
                    // it, so we resolve it reflectively via the method's
                    // parameter type lookup below.
                    // First: find the method on the singleton.
                    val candidateMethods = adaptyCls.declaredMethods.filter {
                        it.name == methodName && it.parameterCount == 1
                    }
                    val targetMethod = candidateMethods.firstOrNull() ?: run {
                        Log.warning("AdaptyBridge: $methodName not found on Adapty")
                        if (cont.isActive) cont.resume(null) {}
                        return@suspendCancellableCoroutine
                    }
                    val callbackCls = targetMethod.parameterTypes[0]
                    val self = arrayOfNulls<Any>(1)
                    val proxy = java.lang.reflect.Proxy.newProxyInstance(
                        callbackCls.classLoader,
                        arrayOf(callbackCls),
                    ) { _, method, args ->
                        when (method.name) {
                            "onResult" -> {
                                // args[0] is AdaptyResult<AdaptyProfile> sealed class.
                                // Resolve Success → profile; Error → null + log.
                                val result = args?.getOrNull(0)
                                val profile = extractProfileFromResult(result)
                                if (cont.isActive) cont.resume(profile) {}
                            }
                            "equals" -> return@newProxyInstance args?.getOrNull(0) === self[0]
                            "hashCode" -> return@newProxyInstance System.identityHashCode(self[0])
                            "toString" -> return@newProxyInstance "AdaptyCallbackProxy"
                        }
                        null
                    }
                    self[0] = proxy
                    targetMethod.invoke(null, proxy)
                } catch (e: Exception) {
                    Log.error("AdaptyBridge.awaitProfileCallback($methodName) failed: ${e.message}")
                    if (cont.isActive) cont.resume(null) {}
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Adapty's `AdaptyResult<T>` is a sealed class with `Success(value)`
     * and `Error(error)` variants. Reflectively extract the Success
     * payload, log the Error, return null on Error.
     */
    private fun extractProfileFromResult(result: Any?): Any? {
        if (result == null) return null
        return try {
            // Sealed-class subtype check via simpleName (avoids importing
            // both AdaptyResult.Success and AdaptyResult.Error).
            val className = result.javaClass.simpleName
            when {
                className == "Success" -> {
                    // Adapty's Success wraps the profile via `value` property.
                    result.javaClass.getMethod("getValue").invoke(result)
                }
                className == "Error" -> {
                    val err = result.javaClass.getMethod("getError").invoke(result)
                    Log.warning("Adapty result error: $err")
                    null
                }
                else -> {
                    Log.warning("AdaptyBridge: unexpected result subtype $className")
                    null
                }
            }
        } catch (e: Exception) {
            Log.warning("AdaptyBridge.extractProfileFromResult failed: ${e.message}")
            null
        }
    }

    /**
     * Convert an Adapty `AdaptyProfile` (received reflectively) into our
     * canonical [Entitlement] list. Mirrors iOS
     * `profile.accessLevels.filter(\.value.isActive).map(\.key)`
     * (AdaptyBridge.swift:99-100, 124).
     */
    @Suppress("UNCHECKED_CAST")
    private fun profileToEntitlements(profile: Any?): List<Entitlement> {
        if (profile == null) return emptyList()
        return try {
            val accessLevelsObj = profile.javaClass.getMethod("getAccessLevels").invoke(profile)
                as? Map<String, Any>
                ?: return emptyList()
            accessLevelsObj.mapNotNull { (key, accessLevel) ->
                try {
                    val isActive = accessLevel.javaClass.getMethod("isActive").invoke(accessLevel) as? Boolean
                    if (isActive != true) return@mapNotNull null
                    Entitlement(
                        productId = key,
                        store = "adapty",
                        status = "active",
                        expiresAt = null,
                        isTrial = false,
                        offerType = null,
                    )
                } catch (e: Exception) {
                    Log.debug("Adapty access-level parse failure: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.warning("AdaptyBridge.profileToEntitlements failed: ${e.message}")
            emptyList()
        }
    }

    private fun isAdaptyPresent(): Boolean {
        return try {
            Class.forName("com.adapty.Adapty")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }
}
