package ai.appdna.sdk.billing

/**
 * What a plan's price label says: the STORE's price, or the one an author typed.
 *
 * 🔴 Until #923 a paywall NEVER asked the store what a product costs. `price_display` was a
 * free-text field and the only thing a user ever saw, which meant a price change in Play Console
 * or App Store Connect did not reach the screen — the store charged the new price and the paywall
 * kept showing the old one. `PriceResolver` existed on this platform all along; its only callers
 * were `PurchaseCompletion` and `NativeBillingManager`, both enriching analytics AFTER a purchase.
 *
 * This is the decision both platforms make, per plan, once the store has answered (or failed to).
 *
 * **The authored string is the FALLBACK, never gone.** A paywall that renders nothing because the
 * network is slow is worse than one showing a stale price, so every failure mode here lands on what
 * the author typed: no product id, a product the store does not know, a lookup that threw, a lookup
 * that timed out, an empty display price.
 *
 * **Scope.** The PRICE, and only the price. The billing period stays authored (#913) and intro
 * offers stay unrendered, because both need localized words of OURS — the store localizes a price,
 * not the phrase "/month" — and that is a separate product decision (#933).
 *
 * Mirrors iOS `StorePriceSubstitution.swift`. Pinned on both by the shared fixture
 * `billing/store_price_substitution`.
 */
internal object StorePriceSubstitution {

    /**
     * Where a rendered price came from. Emitted with the paywall's render event, so a customer
     * asking "why does my paywall show the old price" has an answer in the data rather than a guess.
     */
    const val SOURCE_STORE = "store"
    const val SOURCE_AUTHORED_OPT_OUT = "authored_opt_out"
    const val SOURCE_AUTHORED_NO_PRODUCT = "authored_no_product"
    const val SOURCE_AUTHORED_LOOKUP_FAILED = "authored_lookup_failed"

    data class Outcome(val priceDisplay: String, val source: String)

    /**
     * @param authoredPrice the plan's `price_display`, exactly as published.
     * @param productId the plan's `product_id`, or null/blank when it names none.
     * @param useStorePrice the plan's `use_store_price`; null means the default, which is TRUE.
     * @param storePrice the store's localized `formattedPrice`, or null when the lookup failed,
     *   timed out, or returned no such product.
     */
    fun resolve(
        authoredPrice: String?,
        productId: String?,
        useStorePrice: Boolean?,
        storePrice: String?,
    ): Outcome {
        val authored = authoredPrice ?: ""

        if (useStorePrice == false) return Outcome(authored, SOURCE_AUTHORED_OPT_OUT)
        if (productId.isNullOrBlank()) return Outcome(authored, SOURCE_AUTHORED_NO_PRODUCT)
        if (storePrice.isNullOrBlank()) return Outcome(authored, SOURCE_AUTHORED_LOOKUP_FAILED)
        return Outcome(storePrice, SOURCE_STORE)
    }
}
