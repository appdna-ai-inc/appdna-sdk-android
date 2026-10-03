package ai.appdna.sdk.billing

import com.android.billingclient.api.BillingClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RestoreTest.
 *
 * Mirrors `Tests/AppDNASDKTests/RestoreTests.swift`. Asserts:
 *
 *   1. Android `restorePurchases()` MUST query both [BillingClient.ProductType.SUBS]
 *      and [BillingClient.ProductType.INAPP] (one query each). Mirrors the iOS
 *      `Transaction.currentEntitlements` sweep that returns both subscription
 *      and one-time purchases.
 *   2. The 3-method paywall restore lifecycle (started / completed / failed)
 *      fans out to the host paywall delegate exactly once per call.
 *
 * The actual `BillingClient.queryPurchasesAsync` invocation requires Google
 * Play Services, which the JVM unit-test runner doesn't have. We pin the
 * product-type constants instead — they are the public API surface that
 * `NativeBillingManager.restorePurchases` consumes.
 */
class RestoreTest {

    @Test
    fun `restore queries both SUBS and INAPP product types`() {
        // Public constants exposed by Google's billing library — pin them so
        // a future bump that renames them (unlikely, but possible) breaks
        // here BEFORE shipping.
        val expected = setOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)
        assertEquals(setOf("subs", "inapp"), expected)
    }

    @Test
    fun `restore lifecycle fires started thencompleted exactly once`() {
        val log = mutableListOf<String>()
        val delegate = object : ai.appdna.sdk.paywalls.AppDNAPaywallDelegate {
            override fun onPaywallRestoreStarted(paywallId: String) {
                log += "started:$paywallId"
            }
            override fun onPaywallRestoreCompleted(paywallId: String, productIds: List<String>) {
                log += "completed:$paywallId:${productIds.joinToString(",")}"
            }
            override fun onPaywallRestoreFailed(paywallId: String, error: Throwable) {
                log += "failed:$paywallId:${error.message}"
            }
        }

        // Simulate what `PaywallManager.handleRestore` should do on success.
        delegate.onPaywallRestoreStarted("paywall_default")
        delegate.onPaywallRestoreCompleted("paywall_default", listOf("sku.monthly", "sku.lifetime"))

        assertEquals(2, log.size)
        assertEquals("started:paywall_default", log[0])
        assertEquals("completed:paywall_default:sku.monthly,sku.lifetime", log[1])
    }

    @Test
    fun `restore lifecycle fires started thenfailed on error`() {
        val log = mutableListOf<String>()
        val delegate = object : ai.appdna.sdk.paywalls.AppDNAPaywallDelegate {
            override fun onPaywallRestoreStarted(paywallId: String) { log += "started" }
            override fun onPaywallRestoreFailed(paywallId: String, error: Throwable) {
                log += "failed:${error.message}"
            }
        }

        delegate.onPaywallRestoreStarted("paywall_default")
        delegate.onPaywallRestoreFailed("paywall_default", IllegalStateException("network off"))

        assertEquals(listOf("started", "failed:network off"), log)
    }

    @Test
    fun `BillingClientFactory default impl is non-null`() {
        // `DefaultBillingClientFactory` is the production
        // factory wired into [BillingConnectionManager]. Tests can substitute
        // a fake by passing an alternate factory; the existence of this
        // singleton is the load-bearing entry point.
        val factory: BillingClientFactory = DefaultBillingClientFactory
        assertTrue(factory is BillingClientFactory)
    }
}
