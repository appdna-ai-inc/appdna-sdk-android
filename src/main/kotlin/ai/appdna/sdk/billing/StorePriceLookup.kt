package ai.appdna.sdk.billing

import ai.appdna.sdk.Log
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Asks Play what a paywall's products cost, bounded, and never fails.
 *
 * #923 — the renderer's door to Play Billing. [PriceResolver] could already do this and its only
 * callers were `PurchaseCompletion` and `NativeBillingManager`, both enriching analytics AFTER a
 * purchase. So a paywall showed whatever an author typed, and a price change in Play Console never
 * reached it.
 *
 * 🔴 ALWAYS RETURNS, on a bounded deadline, and never throws. A paywall that waits on a slow
 * network is worse than one showing a stale price, so a lookup that fails, that returns nothing, or
 * that outlives [timeoutMs] yields an empty map — and every plan then falls back to its authored
 * `price_display` through [StorePriceSubstitution]. `withTimeoutOrNull` cancels the query rather
 * than leaving it running behind a screen that has already drawn.
 *
 * Queried for DISPLAY only, and so it runs whoever owns transactions — a RevenueCat or Adapty
 * paywall still names Play product ids, and reading their prices does not touch the purchase path
 * or [BillingOwnership].
 *
 * Both product types are asked for, because a paywall may mix a subscription with a lifetime
 * one-time purchase, and Play answers the two through DIFFERENT queries: a SUBS query simply does
 * not return an INAPP product, so asking only one way silently loses half a paywall's prices.
 */
internal object StorePriceLookup {

    /**
     * #933 — what the store knows about a product: its localized price and its ISO billing period.
     *
     * 🔴 CORRECTED WHILE WRITING THIS. The first version claimed both came off one `ProductInfo`
     * and read `info.billingPeriod` — which does not exist. `ProductInfo` carries no period at all;
     * it lives on the base plan's recurring pricing phase, behind a different query. So this is a
     * price lookup (with its cache rung) plus ONE batched period query, both inside the same
     * budget — not the single pass the comment first asserted.
     *
     * They are still resolved together rather than separately, because a paywall showing this
     * month's price against last month's cadence is worse than showing neither.
     */
    data class StoreFacts(val price: String?, val periodIso: String?)

    /**
     * Price AND period by product id, in one store round-trip. A product Play does not know is
     * simply absent, which is the same signal as a failed lookup and resolves the same way.
     */
    suspend fun facts(
        resolver: PriceResolver?,
        productIds: List<String>,
        timeoutMs: Long = 1500L,
    ): Map<String, StoreFacts> {
        val ids = productIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty() || resolver == null) return emptyMap()

        // The price half reuses `prices`, cache rung and all — no second implementation to drift.
        val priceMap = prices(resolver, ids, timeoutMs)

        /*
         * The period half is ONE batched query. It cannot come off `ProductInfo`, which carries no
         * period: the value lives on the base plan's recurring pricing phase, and
         * `getSubscriptionPeriods` reads it from the same `ProductDetails` list the price query
         * already fetches rather than calling the per-product `getOffers` once per plan.
         */
        val periodMap = withTimeoutOrNull(timeoutMs) {
            runCatching { resolver.getSubscriptionPeriods(ids) }.getOrDefault(emptyMap())
        } ?: run {
            Log.info("[Paywall] store period lookup exceeded ${timeoutMs}ms — authored periods stand")
            emptyMap()
        }

        return ids.mapNotNull { id ->
            val price = priceMap[id]
            val period = periodMap[id]
            if (price == null && period == null) null else id to StoreFacts(price, period)
        }.toMap()
    }

    /**
     * Localized display prices by product id. A product Play does not know is simply absent, which
     * is the same signal as a failed lookup and resolves the same way.
     */
    suspend fun prices(
        resolver: PriceResolver?,
        productIds: List<String>,
        timeoutMs: Long = 1500L,
    ): Map<String, String> {
        val ids = productIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty() || resolver == null) return emptyMap()

        // The resolver's in-memory cache, first and free. It is populated by any earlier query this
        // session, so a second presentation pays nothing and renders the store price instantly.
        val cached = mutableMapOf<String, String>()
        for (id in ids) {
            resolver.cachedPriceInfo(id)?.formattedPrice?.takeIf { it.isNotBlank() }?.let { cached[id] = it }
        }
        val missing = ids.filterNot { cached.containsKey(it) }
        if (missing.isEmpty()) return cached

        val fetched = withTimeoutOrNull(timeoutMs) {
            val out = mutableMapOf<String, String>()
            runCatching { resolver.getSubscriptionProducts(missing) }
                .getOrDefault(emptyList())
                .forEach { if (it.formattedPrice.isNotBlank()) out[it.id] = it.formattedPrice }
            val stillMissing = missing.filterNot { out.containsKey(it) }
            if (stillMissing.isNotEmpty()) {
                runCatching { resolver.getInAppProducts(stillMissing) }
                    .getOrDefault(emptyList())
                    .forEach { if (it.formattedPrice.isNotBlank()) out[it.id] = it.formattedPrice }
            }
            out
        }

        if (fetched == null) {
            Log.info("[Paywall] store price lookup exceeded ${timeoutMs}ms — using authored prices")
            return cached
        }
        if (fetched.isEmpty() && cached.isEmpty()) {
            Log.info("[Paywall] Play knows none of ${ids.size} product(s) — using authored prices")
        }
        return cached + fetched
    }
}
