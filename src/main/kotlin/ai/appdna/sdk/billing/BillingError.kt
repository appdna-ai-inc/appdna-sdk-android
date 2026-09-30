package ai.appdna.sdk.billing

/**
 * Typed billing errors emitted by the SDK. Mirrors iOS
 * `Billing/NativeBillingManager.swift` `public enum BillingError`. Hosts
 * can `when` over this sealed class in `onPaywallPurchaseFailed` /
 * `onPurchaseFailed` callbacks instead of regex-matching error messages.
 *
 * SPEC-070-A finalization §3.2.
 */
sealed class BillingError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Product id was not found by Play Billing or the local catalog. */
    class ProductNotFound(val productId: String) :
        BillingError("Product not found: $productId")

    /** Server-side receipt verification failed (signature/state/refund). */
    class VerificationFailed(message: String = "Transaction verification failed", cause: Throwable? = null) :
        BillingError(message, cause)

    /** Network error during purchase or verification call. */
    class NetworkError(cause: Throwable) :
        BillingError("Network error: ${cause.message ?: cause.javaClass.simpleName}", cause)

    /** Server returned a non-2xx with a message body the SDK couldn't decode. */
    class ServerError(message: String) :
        BillingError("Server error: $message")

    /**
     * Selected billing provider (RevenueCat / Adapty) wasn't on the
     * classpath at runtime so the call could not be dispatched. iOS uses
     * this when SwiftPM extras are excluded from the host target.
     */
    class ProviderNotAvailable(message: String) :
        BillingError(message)

    /** User cancelled the Play Billing dialog (BillingResponseCode.USER_CANCELED). */
    class UserCancelled(message: String = "User cancelled the purchase") :
        BillingError(message)

    /** Pending purchase (e.g. parent approval, deferred payment). */
    class Pending(val productId: String) :
        BillingError("Purchase pending for product: $productId")
}

/**
 * Stable discriminator string for a billing failure.
 *
 * WHY: everything downstream of a failed purchase — the `purchase_failed` event, the paywall
 * delegate, and the Flutter / React Native wrappers, which can only receive plain data across
 * their bridge — used to see an untyped `Throwable` whose only signal was a human-readable
 * `message`. A wrapper (or a BigQuery query) could not tell "user cancelled" from "card
 * declined" from "product misconfigured" without regex-matching English prose. These strings are
 * the contract; they are never localized and never renamed.
 *
 * **Public**, not internal: the React Native wrapper is a SEPARATE Gradle module, so `internal` put
 * this out of its reach and it rejected every purchase — cancel, ask-to-buy, declined card, dead
 * network — with one code (`PURCHASE_ERROR`) and a localized message. These are the exact strings
 * iOS's `billingErrorType(_:)` returns; that is what makes one `catch (e.code)` work on both.
 */
fun billingErrorType(t: Throwable): String = when (t) {
    is BillingError.UserCancelled -> "userCancelled"
    is BillingError.ProductNotFound -> "productNotFound"
    is BillingError.VerificationFailed -> "verificationFailed"
    is BillingError.NetworkError -> "networkError"
    is BillingError.ServerError -> "serverError"
    is BillingError.Pending -> "pending"
    is BillingError.ProviderNotAvailable -> "providerNotAvailable"
    // The `BillingModule.purchase` suspend surface throws its own exception types rather than
    // BillingError; they map onto the same discriminators.
    is ai.appdna.sdk.PurchaseCancelledException -> "userCancelled"
    is ai.appdna.sdk.PurchasePendingException -> "pending"
    // SPEC-497 §13a.2 (round-6 SDK minor 1): a server refusal is a verification failure, the value iOS
    // and the RN error-code table already document — not "unknown".
    is VerificationException -> "verificationFailed"
    // §13a.3 (V7-S1): the typed failure travels on the exception BillingModule.purchase throws.
    is ai.appdna.sdk.PurchaseFailedException -> t.errorType ?: "unknown"
    else -> "unknown"
}
