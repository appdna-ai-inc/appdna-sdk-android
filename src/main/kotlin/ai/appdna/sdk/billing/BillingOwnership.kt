package ai.appdna.sdk.billing

import ai.appdna.sdk.BillingProvider

/**
 * SPEC-497 §3.2 (A1) — who owns a store transaction, decided once from the provider the host asked for.
 *
 * On Android `observerMode` names the reconcile mode (there is no `Transaction.updates` observer):
 * `storeKitOwned` = the reconcile verifies, acknowledges and consumes (`storeKit2`); `providerOwned` =
 * a read-only reconcile (every non-owning provider); `none` = no billing.
 */
internal enum class ObserverMode(val wire: String) {
    StoreKitOwned("storeKitOwned"),
    ProviderOwned("providerOwned"),
    None("none"),
}

internal data class BillingOwnershipPolicy(
    val ownsTransactions: Boolean,
    val sdkCanPurchase: Boolean,
    val sdkCanRestore: Boolean,
    val observerMode: ObserverMode,
    val emitsLifecycleEvents: Boolean,
    /**
     * False only for [NotConfigured]: `configure()` has not chosen a policy yet, so a purchase or a
     * restore fails with the `unknown` "not configured yet" error instead of `providerNotAvailable`.
     */
    val isConfigured: Boolean = true,
) {
    companion object {
        /**
         * The explicit initial state (R64/R65), before `initBillingModuleIfNeeded` runs and again after
         * `shutdown()`. Never the result of [BillingOwnership.policy].
         */
        val NotConfigured = BillingOwnershipPolicy(
            ownsTransactions = false,
            sdkCanPurchase = false,
            sdkCanRestore = false,
            observerMode = ObserverMode.None,
            emitsLifecycleEvents = false,
            isConfigured = false,
        )

        /** The message of the `unknown` error a purchase / restore before `configure()` throws. */
        const val NOT_CONFIGURED_MESSAGE = "AppDNA SDK not configured yet — call configure() first"
    }
}

/** The one pure seam (§2 principle 5): production and the fixture runner both call it. */
internal object BillingOwnership {
    /**
     * SPEC-497 I4 R8 m4 — the message of a REFUSED restore, word for word the iOS
     * `BillingOwnershipPolicy.refusalMessage` (§3.2 rule 2: a non-owning provider's restore throws the
     * same error as its purchase), so hosts on both platforms see one string.
     */
    fun restoreRefusalMessage(providerName: String): String = when (providerName) {
        "revenueCat" -> "RevenueCat: purchases are made by RevenueCat in your app"
        "adapty" -> "Adapty: purchases are made by Adapty in your app"
        else -> "No billing provider configured"
    }

    fun policy(provider: BillingProvider): BillingOwnershipPolicy = when (provider) {
        is BillingProvider.StoreKit2 -> BillingOwnershipPolicy(
            ownsTransactions = true,
            sdkCanPurchase = true,
            sdkCanRestore = true,
            observerMode = ObserverMode.StoreKitOwned,
            emitsLifecycleEvents = true,
        )
        // Owner Q2: the RevenueCat webhook is the single source of lifecycle events.
        is BillingProvider.RevenueCat -> BillingOwnershipPolicy(
            ownsTransactions = false,
            sdkCanPurchase = false,
            sdkCanRestore = false,
            observerMode = ObserverMode.ProviderOwned,
            emitsLifecycleEvents = false,
        )
        // Owner Q1 + LD-R10-1: never owns on Android, but keeps the device lifecycle events this release.
        is BillingProvider.Adapty -> BillingOwnershipPolicy(
            ownsTransactions = false,
            sdkCanPurchase = false,
            sdkCanRestore = false,
            observerMode = ObserverMode.ProviderOwned,
            emitsLifecycleEvents = true,
        )
        is BillingProvider.None -> none
    }

    /** No billing: the `none` provider, and the policy a failed billing init falls back to. */
    val none = BillingOwnershipPolicy(
        ownsTransactions = false,
        sdkCanPurchase = false,
        sdkCanRestore = false,
        observerMode = ObserverMode.None,
        emitsLifecycleEvents = false,
    )

    /**
     * LD-R4-1 — the `billing_owner` a new SDK sends on `/billing/verify` and `/billing/restore`: `"sdk"`
     * when the SDK owns transactions, otherwise the provider's wire name. Derived from the policy the
     * verifier is constructed with, never from a string the host passes.
     */
    fun billingOwner(provider: BillingProvider): String =
        if (policy(provider).ownsTransactions) "sdk" else provider.type
}

/**
 * §11.9 — the marker that tells the analytics dedupe an event came from the SDK's own billing layer.
 * The public `AppDNA.track` strips it from host events; SDK billing emits add it through here (the
 * static path uses `AppDNA.trackBillingEvent`, which calls this).
 */
internal object BillingEventProps {
    const val EMITTED_BY = "emitted_by"
    const val APPDNA_ORIGIN = "_appdna_origin"

    fun marked(properties: Map<String, Any>?): Map<String, Any> =
        (properties ?: emptyMap()) + (EMITTED_BY to "sdk")
}
