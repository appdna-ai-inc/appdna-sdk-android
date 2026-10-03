package ai.appdna.sdk.billing

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.PurchasesUpdatedListener

/**
 * Injectable seam for [BillingConnectionManager].
 *
 * The default implementation builds a real Google Play [BillingClient] (the
 * exact same shape that previously lived inline in
 * [BillingConnectionManager.initialize]). Tests can substitute a fake by
 * passing an alternate [BillingClientFactory] to the manager constructor —
 * letting us exercise reconnect-and-retry / surface-billing-unavailable paths
 * on the JVM without hitting Google Play.
 *
 * Functional interface so test fakes can be written as a single lambda:
 *
 *     val factory = BillingClientFactory { _, listener ->
 *         FakeBillingClient(listener)
 *     }
 */
internal fun interface BillingClientFactory {
    fun create(context: Context, listener: PurchasesUpdatedListener): BillingClient
}

/**
 * Production [BillingClientFactory] — opts in to one-time-product pending
 * purchases (matches typed `PendingPurchasesParams` builder
 * in billing-ktx 7+; subscription pending state is always enabled).
 */
internal val DefaultBillingClientFactory: BillingClientFactory =
    BillingClientFactory { context, listener ->
        BillingClient.newBuilder(context)
            .setListener(listener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .build()
    }
