package ai.appdna.sdk.billing

import ai.appdna.sdk.Log
import com.android.billingclient.api.*

/**
 * Resolves product pricing information from Google Play Billing.
 *
 * Queries ProductDetails from Google Play and extracts localized pricing,
 * offer tokens, and trial/introductory pricing phases for subscriptions.
 */
internal class PriceResolver(
    private val connectionManager: BillingConnectionManager
) {

    /**
     * In-memory cache of last-seen price/currency keyed
     * by productId. Populated by [getSubscriptionProducts] / [getInAppProducts]
     * / [getOffers] and read by [NativeBillingManager.handleSuccessfulPurchase]
     * to enrich `purchase_completed` analytics. Best-effort — if the host
     * never queried prices before purchase the cache is empty and the
     * analytics omit the `price`/`currency` fields.
     */
    private val priceInfoCache = java.util.concurrent.ConcurrentHashMap<String, ProductInfo>()

    /** Fetch the cached ProductInfo for analytics enrichment. */
    fun cachedPriceInfo(productId: String): ProductInfo? = priceInfoCache[productId]

    /**
     * Product IDs Play has confirmed are SUBS (auto-renewing) — i.e. they came back from a
     * `ProductType.SUBS` query. Read by [NativeBillingManager.isKnownSubscriptionProduct] to decide
     * whether a purchase should ALSO emit `subscription_started` (an MTPU-metered event no SDK used to
     * emit) on the provider paths — RevenueCat / Adapty — where the host drove the purchase through the
     * provider's SDK and no Play `Purchase` / verified `Entitlement` is in scope at the emit site.
     *
     * A product that never came back from a SUBS query is NOT recorded here, so the fallback answers
     * "not a subscription" rather than fabricating one: over-emitting a metered event is the worse
     * failure.
     */
    private val subscriptionProductIds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    /** True iff Play returned [productId] from a `ProductType.SUBS` query during this session. */
    fun isKnownSubscriptionProduct(productId: String): Boolean = subscriptionProductIds.contains(productId)

    /**
     * Fetch product details for a list of subscription product IDs.
     *
     * @param productIds The Google Play product IDs to query.
     * @return List of resolved ProductInfo, one per found product.
     */
    suspend fun getSubscriptionProducts(productIds: List<String>): List<ProductInfo> {
        if (productIds.isEmpty()) return emptyList()

        val client = connectionManager.awaitConnectedClient() ?: run {
            Log.warning("PriceResolver: BillingClient not available")
            return emptyList()
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productIds.map { id ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            })
            .build()

        val result = client.queryProductDetails(params)

        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.warning("PriceResolver: query failed with code ${result.billingResult.responseCode}")
            return emptyList()
        }

        return result.productDetailsList?.map { details ->
            mapProductDetails(details).also {
                priceInfoCache[it.id] = it
                // Play answered this id under ProductType.SUBS → it auto-renews.
                subscriptionProductIds.add(it.id)
            }
        } ?: emptyList()
    }

    /**
     * Fetch product details for in-app (one-time) products.
     *
     * @param productIds The Google Play product IDs to query.
     * @return List of resolved ProductInfo.
     */
    suspend fun getInAppProducts(productIds: List<String>): List<ProductInfo> {
        if (productIds.isEmpty()) return emptyList()

        val client = connectionManager.awaitConnectedClient() ?: run {
            Log.warning("PriceResolver: BillingClient not available")
            return emptyList()
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productIds.map { id ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            })
            .build()

        val result = client.queryProductDetails(params)

        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.warning("PriceResolver: INAPP query failed with code ${result.billingResult.responseCode}")
            return emptyList()
        }

        return result.productDetailsList?.map { details ->
            ProductInfo(
                id = details.productId,
                name = details.name,
                description = details.description,
                formattedPrice = details.oneTimePurchaseOfferDetails?.formattedPrice ?: "",
                priceMicros = details.oneTimePurchaseOfferDetails?.priceAmountMicros ?: 0,
                currencyCode = details.oneTimePurchaseOfferDetails?.priceCurrencyCode ?: "",
                offerToken = null
            ).also { priceInfoCache[it.id] = it }
        } ?: emptyList()
    }

    /**
     * Get all available offers for a subscription product, including
     * base plans, free trials, and introductory offers.
     *
     * @param productId The subscription product ID.
     * @return List of OfferDetails for the product.
     */
    /**
     * #933 — the ISO billing period of each subscription's BASE plan, by product id, in ONE query.
     *
     * 🔴 WHY THIS EXISTS RATHER THAN `getOffers`. The period lives on [OfferDetails], and
     * `getOffers` takes a single product id — so reading it for a three-plan paywall would be three
     * extra Play round-trips. This reads the same `ProductDetails` list `getSubscriptionProducts`
     * already queries, and keeps the period off the public [ProductInfo] DTO, which the RN and
     * Flutter bridges map field by field.
     *
     * A product Play does not know, and a one-time product with no subscription offer, are both
     * simply absent — the same signal as a failed lookup, and it resolves the same way.
     */
    suspend fun getSubscriptionPeriods(productIds: List<String>): Map<String, String> {
        if (productIds.isEmpty()) return emptyMap()
        val client = connectionManager.awaitConnectedClient() ?: return emptyMap()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.map { id ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(id)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                },
            )
            .build()
        val result = client.queryProductDetails(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.warning("PriceResolver: period query failed with code ${result.billingResult.responseCode}")
            return emptyMap()
        }
        val out = mutableMapOf<String, String>()
        result.productDetailsList?.forEach { details ->
            // The BASE plan's recurring phase: the last phase of the first offer, since a free
            // trial or introductory phase precedes it and is NOT the ongoing cadence.
            val phases = details.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList
            val base = phases?.lastOrNull()?.billingPeriod
            if (!base.isNullOrBlank()) out[details.productId] = base
        }
        return out
    }

    /**
     * #933 slice 3 — the FREE-TRIAL period per product, for products this user is eligible for.
     *
     * 🔴 THE ELIGIBILITY ANSWER IS STRUCTURAL, which is why there is no flag to read. Play returns
     * only "all base plans and offers the user is **eligible to purchase**"
     * (`getSubscriptionOfferDetails`), and its own migration guide tells apps to use exactly this
     * instead of purchase history for trial eligibility. So a zero-price pricing phase AMONG THE
     * RETURNED OFFERS is Play saying this user can have that trial. A user who has already used it
     * simply gets no such offer back.
     *
     * One batched query, like `getSubscriptionPeriods` and for the same reason: the per-product
     * `getOffers` would be one round trip per plan.
     */
    suspend fun getSubscriptionTrials(productIds: List<String>): Map<String, String> {
        if (productIds.isEmpty()) return emptyMap()
        val client = connectionManager.awaitConnectedClient() ?: return emptyMap()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.map { id ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(id)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                },
            )
            .build()
        val result = client.queryProductDetails(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.warning("PriceResolver: trial query failed with code ${result.billingResult.responseCode}")
            return emptyMap()
        }
        val out = mutableMapOf<String, String>()
        result.productDetailsList?.forEach { details ->
            /*
             * Across ALL eligible offers, not just the first: a product can carry several, and a
             * trial on any of them is a trial this user can take. A zero `priceAmountMicros` is
             * what makes a phase a free trial — an introductory PRICE is non-zero and is a
             * different promise, so it is deliberately not treated as one here.
             */
            val trial = details.subscriptionOfferDetails
                ?.asSequence()
                ?.flatMap { it.pricingPhases.pricingPhaseList.asSequence() }
                ?.firstOrNull { it.priceAmountMicros == 0L }
                ?.billingPeriod
            if (!trial.isNullOrBlank()) out[details.productId] = trial
        }
        return out
    }

    suspend fun getOffers(productId: String): List<OfferDetails> {
        val client = connectionManager.awaitConnectedClient() ?: return emptyList()

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(productId)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            ))
            .build()

        val result = client.queryProductDetails(params)
        val details = result.productDetailsList?.firstOrNull() ?: return emptyList()

        return details.subscriptionOfferDetails?.map { offer ->
            val phases = offer.pricingPhases.pricingPhaseList
            val basePricingPhase = phases.lastOrNull()
            val trialPhase = phases.find { it.priceAmountMicros == 0L }
            val introPhase = phases.find {
                it.priceAmountMicros > 0L && it != basePricingPhase && phases.size > 1
            }

            OfferDetails(
                offerToken = offer.offerToken,
                offerId = offer.offerId,
                basePlanId = offer.basePlanId,
                formattedPrice = basePricingPhase?.formattedPrice ?: "",
                priceMicros = basePricingPhase?.priceAmountMicros ?: 0,
                currencyCode = basePricingPhase?.priceCurrencyCode ?: "",
                billingPeriod = basePricingPhase?.billingPeriod ?: "",
                hasTrial = trialPhase != null,
                trialPeriod = trialPhase?.billingPeriod,
                hasIntroOffer = introPhase != null,
                introFormattedPrice = introPhase?.formattedPrice,
                introPriceMicros = introPhase?.priceAmountMicros,
                introPeriod = introPhase?.billingPeriod,
                introCycles = introPhase?.billingCycleCount ?: 0
            )
        } ?: emptyList()
    }

    /**
     * Map a ProductDetails to our simplified ProductInfo model.
     * Uses the base offer (last pricing phase of the first offer).
     */
    private fun mapProductDetails(details: ProductDetails): ProductInfo {
        val offer = details.subscriptionOfferDetails?.firstOrNull()
        val basePricingPhase = offer?.pricingPhases?.pricingPhaseList?.lastOrNull()

        return ProductInfo(
            id = details.productId,
            name = details.name,
            description = details.description,
            formattedPrice = basePricingPhase?.formattedPrice ?: "",
            priceMicros = basePricingPhase?.priceAmountMicros ?: 0,
            currencyCode = basePricingPhase?.priceCurrencyCode ?: "",
            offerToken = offer?.offerToken
        )
    }

    /**
     * Warm the price cache directly from the ProductDetails resolved during a purchase,
     * so `trackPurchaseCompleted` emits `price`/`currency` on purchase_completed/subscription_started
     * even when the host never called getProducts() (the common AppDNA-paywall path, which renders
     * console-authored price strings and never touches PriceResolver). iOS always includes these
     * (its StoreKit Product is fetched in the same purchase call); Android silently omitted them and
     * undercounted revenue value on two of the three MTPU-metered events. Handles SUBS (base-plan,
     * last pricing phase) and INAPP (one-time offer).
     */
    fun cacheFromPurchase(details: ProductDetails) {
        val base = details.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.lastOrNull()
        val oneTime = details.oneTimePurchaseOfferDetails
        val micros = base?.priceAmountMicros ?: oneTime?.priceAmountMicros ?: 0L
        val currency = base?.priceCurrencyCode ?: oneTime?.priceCurrencyCode ?: ""
        if (micros <= 0L && currency.isBlank()) return
        priceInfoCache[details.productId] = ProductInfo(
            id = details.productId,
            name = details.name,
            description = details.description,
            formattedPrice = base?.formattedPrice ?: oneTime?.formattedPrice ?: "",
            priceMicros = micros,
            currencyCode = currency,
            offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken,
        )
    }
}

/**
 * Detailed offer information for a subscription product.
 * Includes base pricing, trial, and introductory offer phases.
 */
data class OfferDetails(
    /** Token required to initiate purchase for this offer. */
    val offerToken: String,
    /** Google Play offer ID (null for base plans). */
    val offerId: String?,
    /** The base plan ID this offer belongs to. */
    val basePlanId: String,
    /** Formatted base price string (e.g., "$9.99"). */
    val formattedPrice: String,
    /** Base price in micros (e.g., 9990000 for $9.99). */
    val priceMicros: Long,
    /** ISO 4217 currency code (e.g., "USD"). */
    val currencyCode: String,
    /** ISO 8601 billing period (e.g., "P1M" for monthly, "P1Y" for yearly). */
    val billingPeriod: String,
    /** Whether this offer includes a free trial phase. */
    val hasTrial: Boolean,
    /** Trial period duration (ISO 8601), if applicable. */
    val trialPeriod: String?,
    /** Whether this offer includes an introductory price phase. */
    val hasIntroOffer: Boolean,
    /** Formatted introductory price string, if applicable. */
    val introFormattedPrice: String?,
    /** Introductory price in micros, if applicable. */
    val introPriceMicros: Long?,
    /** Introductory period duration (ISO 8601), if applicable. */
    val introPeriod: String?,
    /** Number of introductory billing cycles. */
    val introCycles: Int
)
