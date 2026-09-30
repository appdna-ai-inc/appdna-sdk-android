package ai.appdna.sdk.billing

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.BillingProvider
import ai.appdna.sdk.PurchaseFailedException
import ai.appdna.sdk.paywalls.AppDNAPaywallDelegate
import ai.appdna.sdk.paywalls.PaywallConfigParser
import ai.appdna.sdk.paywalls.PaywallManager
import ai.appdna.sdk.paywalls.PaywallPlan
import ai.appdna.sdk.storage.LocalStorage
import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SPEC-497 §3.2 rule 5 / R65 — the paywall tap and the direct API without an owning provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaywallNoProviderTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var pipe: BillingTestPipeline? = null
    private var sc: BillingScenario? = null
    private val calls = mutableListOf<String>()

    @After fun tearDown() {
        sc?.teardown(); pipe?.close()
        AppDNA.billing.manager = null
        AppDNA.billing.ownershipPolicy = BillingOwnershipPolicy.NotConfigured
    }

    private fun tap(provider: BillingProvider) {
        pipe = BillingTestPipeline(ctx, "no_provider")
        if (provider !is BillingProvider.None) sc = BillingScenario(ctx, JSONObject(), ownsTransactions = false)
        AppDNA.billing.ownershipPolicy = BillingOwnership.policy(provider)
        val paywall = PaywallConfigParser.parsePaywalls(mapOf("pw" to mapOf("id" to "pw", "name" to "t", "layout" to mapOf("type" to "stack"), "sections" to emptyList<Any>())))["pw"]!!
        val delegate = object : AppDNAPaywallDelegate {
            override fun onPaywallPurchaseStarted(paywallId: String, productId: String) { calls += "started" }
            override fun onPaywallPurchaseFailed(paywallId: String, error: Throwable, errorType: String, productId: String?) {
                calls += "failed:$errorType:$productId"
            }
        }
        PaywallManager(ai.appdna.sdk.config.RemoteConfigManager(null, LocalStorage(ctx), 3600L), pipe!!.tracker)
            .handlePurchase(Robolectric.buildActivity(Activity::class.java).setup().get(), "pw", PaywallPlan(id = "m", product_id = "pro_monthly", name = "M", price = ""), paywall, emptyMap(), delegate)
        repeat(10) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
    }

    @Test fun `none - one purchase_failed, providerNotAvailable, no started`() {
        tap(BillingProvider.None)
        assertEquals(listOf("purchase_failed"), pipe!!.names())
        assertEquals("providerNotAvailable", pipe!!.props("purchase_failed").getString("error_type"))
        assertEquals(listOf("failed:providerNotAvailable:pro_monthly"), calls)
    }

    @Test fun `revenueCat - one purchase_failed, providerNotAvailable, no started, no store call`() {
        tap(BillingProvider.RevenueCat)
        assertEquals(listOf("purchase_failed"), pipe!!.names())
        assertEquals(listOf("failed:providerNotAvailable:pro_monthly"), calls)
        assertEquals(0, sc!!.fake.launchCount)
    }

    @Test fun `direct purchase before configure fails unknown with the not-configured message`() = runBlocking {
        AppDNA.billing.ownershipPolicy = BillingOwnershipPolicy.NotConfigured
        try {
            AppDNA.billing.purchase(Robolectric.buildActivity(Activity::class.java).setup().get(), "p")
            fail()
        } catch (e: IllegalStateException) {
            assertEquals(BillingOwnershipPolicy.NOT_CONFIGURED_MESSAGE, e.message)
            assertEquals("unknown", billingErrorType(e))
        }
    }

    @Test fun `direct purchase under none throws ProviderNotAvailable with one purchase_failed`() = runBlocking {
        pipe = BillingTestPipeline(ctx, "direct_none")
        AppDNA.billing.ownershipPolicy = BillingOwnership.policy(BillingProvider.None)
        try {
            AppDNA.billing.purchase(Robolectric.buildActivity(Activity::class.java).setup().get(), "p")
            fail()
        } catch (e: BillingError.ProviderNotAvailable) {
            assertEquals("providerNotAvailable", billingErrorType(e))
        }
        assertEquals(listOf("purchase_failed"), pipe!!.names())
    }

    @Test fun `direct restore under none throws providerNotAvailable (was an empty list)`() = runBlocking {
        pipe = BillingTestPipeline(ctx, "restore_none")
        AppDNA.billing.ownershipPolicy = BillingOwnership.policy(BillingProvider.None)
        try { AppDNA.billing.restorePurchases(); fail() } catch (e: BillingError.ProviderNotAvailable) { }
        assertEquals(1, pipe!!.count("purchase_restore_failed"))
    }

    @Test fun `PurchaseFailedException carries the verification error type`() {
        assertEquals("verificationFailed", billingErrorType(PurchaseFailedException("p", "x", errorType = "verificationFailed")))
        assertTrue(billingErrorType(PurchaseFailedException("p", "x")) == "unknown")
    }
}
