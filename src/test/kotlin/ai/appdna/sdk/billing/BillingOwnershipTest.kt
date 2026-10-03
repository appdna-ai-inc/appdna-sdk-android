package ai.appdna.sdk.billing

import ai.appdna.sdk.BillingProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The ownership table, row by row (Android). */
class BillingOwnershipTest {

    private fun row(p: BillingOwnershipPolicy) =
        listOf(p.ownsTransactions, p.sdkCanPurchase, p.sdkCanRestore, p.observerMode.wire, p.emitsLifecycleEvents)

    @Test fun `storeKit2 owns, buys, restores, reconciles as owner, emits lifecycle events`() =
        assertEquals(listOf(true, true, true, "storeKitOwned", true), row(BillingOwnership.policy(BillingProvider.StoreKit2)))

    @Test fun `revenueCat never owns and emits no device lifecycle events (owner Q2)`() =
        assertEquals(listOf(false, false, false, "providerOwned", false), row(BillingOwnership.policy(BillingProvider.RevenueCat)))

    @Test fun `adapty never owns on Android (owner Q1) but keeps lifecycle events (LD-R10-1)`() =
        assertEquals(listOf(false, false, false, "providerOwned", true), row(BillingOwnership.policy(BillingProvider.Adapty("k"))))

    @Test fun `none is no billing`() =
        assertEquals(listOf(false, false, false, "none", false), row(BillingOwnership.policy(BillingProvider.None)))

    @Test fun `NotConfigured is the explicit initial state and is never a policy result`() {
        assertFalse(BillingOwnershipPolicy.NotConfigured.isConfigured)
        for (p in listOf(BillingProvider.StoreKit2, BillingProvider.RevenueCat, BillingProvider.Adapty("k"), BillingProvider.None)) {
            assertEquals(true, BillingOwnership.policy(p).isConfigured)
        }
    }

    @Test fun `billing_owner is sdk only when the SDK owns transactions (LD-R4-1)`() {
        assertEquals("sdk", BillingOwnership.billingOwner(BillingProvider.StoreKit2))
        assertEquals("revenueCat", BillingOwnership.billingOwner(BillingProvider.RevenueCat))
        assertEquals("adapty", BillingOwnership.billingOwner(BillingProvider.Adapty("k")))
    }
}
