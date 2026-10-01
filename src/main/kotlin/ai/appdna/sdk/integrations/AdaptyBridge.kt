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
 * singleton. Checked against the Adapty Android SDK 2.11, 3.17 and 4.2 class
 * files: activation uses `activate(Context, AdaptyConfig)` (the only form in
 * 4.x; also in 2.11+ and 3.x) and falls back to the older
 * `activate(Context, String)`; `restorePurchases` / `getProfile` take one
 * `ResultCallback` in all three; `AdaptyProfile.accessLevels` is Adapty's own
 * `ImmutableMap` (not a `java.util.Map`) in all three, and `AdaptyProfile.AccessLevel` has
 * `isActive()` and `getVendorProductId()` in all three.
 *
 * Reflection rationale (same as RevenueCatBridge):
 *   - We can't import `com.adapty.*` because that would force a runtime
 *     dependency on every host.
 *   - The Adapty Android SDK exposes `ResultCallback<T>` interfaces in 2.x;
 *     we wrap them in a `suspendCancellableCoroutine` so the bridge
 *     surface stays uniform with iOS (`async throws`).
 *
 * Risk: a future Adapty rename fails at runtime, not at compile time. We log
 * loudly on every miss so the host gets a clear signal.
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
     * iOS `AdaptyBridge.activate()` calls `Adapty.activate(apiKey)`; here the linked Adapty's
     * `activate(Context, AdaptyConfig)` or `activate(Context, String)` is used ([activateReflectively]).
     */
    fun configure(apiKey: String) {
        if (apiKey.isBlank()) {
            // Adapty cannot start without its key: say so instead of activating it with "".
            Log.error("AdaptyBridge.configure: the Adapty API key is empty — Adapty is not activated. Pass your Adapty public SDK key in BillingProvider.Adapty(apiKey).")
            return
        }
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
            val form = activateReflectively(Class.forName("com.adapty.Adapty"), context, apiKey)
            configured = true
            Log.info("Adapty bridge initialized ($form)")
        } catch (e: Exception) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            Log.error("AdaptyBridge.configure failed — Adapty is not activated: ${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    /**
     * Purchase a product through Adapty — refused, as iOS `AdaptyBridge.purchase(productId:appAccountToken:)`
     * refuses it (`BillingError.providerNotAvailable`).
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
     * Restore previously-purchased entitlements: the store products behind the active access levels
     * ([profileToEntitlements]), the same ids as iOS `AdaptyBridge.restore(appAccountToken:)`.
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
     * The store products behind the current active access levels ([profileToEntitlements]), the same
     * ids as iOS `AdaptyBridge.getEntitlements(appAccountToken:)`.
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
                    // Adapty (2.x–4.x) callback interface — `ResultCallback<T>` with a
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
     * Convert an Adapty `AdaptyProfile` (received reflectively) into our canonical [Entitlement] list:
     * one entry per STORE PRODUCT behind an active access level (`AccessLevel.vendorProductId`), deduped —
     * the same ids as iOS `AdaptyBridge.activeProductIds` (`profile.accessLevels.values.filter(\.isActive)
     * .map(\.vendorProductId)`) and as every other bridge, which name store products. This used to return
     * the access-level KEY (e.g. "premium"), so the two platforms reported different ids for one
     * subscription. An access level whose product id cannot be read falls back to its key.
     */
    @Suppress("UNCHECKED_CAST")
    private fun profileToEntitlements(profile: Any?): List<Entitlement> {
        if (profile == null) return emptyList()
        return try {
            val accessLevelsObj = profile.javaClass.getMethod("getAccessLevels").invoke(profile)
            val seen = HashSet<String>()
            accessLevelEntries(accessLevelsObj).mapNotNull { (key, accessLevel) ->
                try {
                    val isActive = accessLevel.javaClass.getMethod("isActive").invoke(accessLevel) as? Boolean
                    if (isActive != true) return@mapNotNull null
                    val productId = vendorProductId(accessLevel) ?: key
                    if (!seen.add(productId)) return@mapNotNull null
                    Entitlement(
                        productId = productId,
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

    /** `AccessLevel.vendorProductId` (Adapty 2.x–4.x), or null when it is missing or blank. */
    private fun vendorProductId(accessLevel: Any): String? = try {
        (accessLevel.javaClass.getMethod("getVendorProductId").invoke(accessLevel) as? String)?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    internal companion object {
        /**
         * Activate Adapty through whichever entry point the linked version has:
         *  1. `activate(Context, AdaptyConfig)` with `AdaptyConfig.Builder(apiKey).build()` — Adapty
         *     2.11+, 3.x and 4.x (the only one 4.x has);
         *  2. `activate(Context, String)` — older 2.x (and still present, deprecated, in 3.x).
         * Returns which form was used. Throws when neither exists. [configClassName] is the
         * `AdaptyConfig` class (a test passes its own), resolved with [adaptyCls]'s class loader.
         */
        fun activateReflectively(
            adaptyCls: Class<*>,
            context: android.content.Context,
            apiKey: String,
            configClassName: String = "com.adapty.models.AdaptyConfig",
        ): String {
            val configCls = try {
                Class.forName(configClassName, true, adaptyCls.classLoader)
            } catch (_: ClassNotFoundException) {
                null
            }
            if (configCls != null) {
                val activate = adaptyCls.methods.firstOrNull {
                    it.name == "activate" && it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == android.content.Context::class.java && it.parameterTypes[1] == configCls
                }
                if (activate != null) {
                    val builderCls = Class.forName("${configCls.name}\$Builder", true, configCls.classLoader)
                    val builder = builderCls.getConstructor(String::class.java).newInstance(apiKey)
                    val config = builderCls.getMethod("build").invoke(builder)
                    activate.invoke(null, context, config)
                    return "AdaptyConfig"
                }
            }
            adaptyCls.getMethod("activate", android.content.Context::class.java, String::class.java)
                .invoke(null, context, apiKey)
            return "apiKey"
        }

        /**
         * `AdaptyProfile.accessLevels` as (key, access level) pairs. Adapty returns its own
         * `com.adapty.utils.ImmutableMap` — NOT a `java.util.Map` — so a `Map` cast yields nothing
         * (every Adapty user used to read as having no entitlement). Read it through its public
         * `keySet()` (an `Iterable`) and `get(key)`; a plain `Map` is accepted too.
         */
        fun accessLevelEntries(accessLevels: Any?): List<Pair<String, Any>> {
            if (accessLevels == null) return emptyList()
            if (accessLevels is Map<*, *>) {
                return accessLevels.mapNotNull { (k, v) -> if (k is String && v != null) k to v else null }
            }
            val keys = accessLevels.javaClass.getMethod("keySet").invoke(accessLevels) as? Iterable<*> ?: return emptyList()
            val get = accessLevels.javaClass.methods.first { it.name == "get" && it.parameterTypes.size == 1 }
            return keys.mapNotNull { k ->
                val v = if (k is String) get.invoke(accessLevels, k) else null
                if (k is String && v != null) k to v else null
            }
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
