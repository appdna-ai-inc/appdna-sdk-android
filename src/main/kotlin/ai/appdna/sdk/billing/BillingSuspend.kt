package ai.appdna.sdk.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/*
 * #1112 — THE TWO SUSPEND WRAPPERS `billing-ktx` USED TO PROVIDE, over the plain Play Billing Library 8.
 *
 * Play refuses an app built on Play Billing Library 7, and `billing-ktx` 8.x is compiled with Kotlin 2.1
 * metadata that this module's Kotlin 1.9 compiler — and an integrator still on 1.9 — cannot read. Those
 * two functions were all the SDK took from it, so they are written here, with the same names and the
 * same result shapes the call sites already used.
 *
 * Billing 8 changed one of them underneath: `queryProductDetailsAsync` now answers with a
 * `QueryProductDetailsResult` (the details Play returned, plus the products it could not fetch). The
 * wrapper keeps the old `productDetailsList` shape; an unfetched product is simply absent from it, which
 * is what every caller already treats as "no such product".
 *
 * Each resumes exactly once: Play calls a listener once per query, and `isActive` guards a caller that
 * was cancelled (a timeout around the query) before Play answered.
 */

/** What `queryProductDetails` returns — the shape `billing-ktx`'s `ProductDetailsResult` had. */
internal data class ProductDetailsResult(
    val billingResult: BillingResult,
    val productDetailsList: List<ProductDetails>?,
)

/** What `queryPurchasesAsync` returns — the shape `billing-ktx`'s `PurchasesResult` had. */
internal data class PurchasesResult(
    val billingResult: BillingResult,
    val purchasesList: List<Purchase>,
)

internal suspend fun BillingClient.queryProductDetails(params: QueryProductDetailsParams): ProductDetailsResult =
    suspendCancellableCoroutine { cont ->
        queryProductDetailsAsync(params) { billingResult, result ->
            if (cont.isActive) cont.resume(ProductDetailsResult(billingResult, result.productDetailsList))
        }
    }

internal suspend fun BillingClient.queryPurchasesAsync(params: QueryPurchasesParams): PurchasesResult =
    suspendCancellableCoroutine { cont ->
        queryPurchasesAsync(params) { billingResult, purchases ->
            if (cont.isActive) cont.resume(PurchasesResult(billingResult, purchases))
        }
    }
