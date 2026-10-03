package ai.appdna.sdk.billing

import ai.appdna.sdk.PurchaseCancelledException
import ai.appdna.sdk.PurchasePendingException
import ai.appdna.sdk.paywalls.AppDNAPaywallDelegate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `purchase_failed` and the paywall delegate now carry a stable discriminator, so a
 * host (or a Flutter/RN wrapper, which only receives data across its bridge) can tell "user
 * cancelled" from "card declined" without regex-matching an English message.
 */
class BillingErrorTypeTest {

    @Test
    fun `every BillingError maps to its stable discriminator`() {
        assertEquals("userCancelled", billingErrorType(BillingError.UserCancelled()))
        assertEquals("productNotFound", billingErrorType(BillingError.ProductNotFound("pro_monthly")))
        assertEquals("verificationFailed", billingErrorType(BillingError.VerificationFailed()))
        assertEquals("networkError", billingErrorType(BillingError.NetworkError(RuntimeException("boom"))))
        assertEquals("serverError", billingErrorType(BillingError.ServerError("500")))
        assertEquals("pending", billingErrorType(BillingError.Pending("pro_monthly")))
        assertEquals("providerNotAvailable", billingErrorType(BillingError.ProviderNotAvailable("rc")))
    }

    @Test
    fun `the purchase surface exceptions map onto the same discriminators`() {
        assertEquals("userCancelled", billingErrorType(PurchaseCancelledException("pro_monthly")))
        assertEquals("pending", billingErrorType(PurchasePendingException("pro_monthly")))
    }

    @Test
    fun `an unrecognised throwable is unknown, never blank`() {
        assertEquals("unknown", billingErrorType(IllegalStateException("card declined")))
    }

    /**
     * The typed overload is a Kotlin interface default, so a host that only implements the 2-arg
     * method — every conformer shipped before this change — still receives the failure.
     */
    @Test
    fun `existing 2-arg conformers still receive the failure through the typed overload`() {
        var seen: Pair<String, Throwable>? = null
        val legacyHost = object : AppDNAPaywallDelegate {
            override fun onPaywallPurchaseFailed(paywallId: String, error: Throwable) {
                seen = paywallId to error
            }
        }
        val err = PurchaseCancelledException("pro_monthly")
        legacyHost.onPaywallPurchaseFailed("pw1", err, billingErrorType(err))

        assertEquals("pw1", seen?.first)
        assertEquals(err, seen?.second)
    }

    @Test
    fun `a typed conformer receives the discriminator`() {
        var type: String? = null
        val host = object : AppDNAPaywallDelegate {
            override fun onPaywallPurchaseFailed(paywallId: String, error: Throwable, errorType: String) {
                type = errorType
            }
        }
        val err = BillingError.ProductNotFound("pro_monthly")
        host.onPaywallPurchaseFailed("pw1", err, billingErrorType(err))
        assertEquals("productNotFound", type)
    }
}
