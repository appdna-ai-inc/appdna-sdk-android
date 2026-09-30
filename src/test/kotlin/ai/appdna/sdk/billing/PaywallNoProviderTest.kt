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
        AppDNA.paywall.skipNextAutoDismissOnRestore = false
    }

    private fun tap(provider: BillingProvider?) {
        pipe = BillingTestPipeline(ctx, "no_provider")
        if (provider != null && provider !is BillingProvider.None) sc = BillingScenario(ctx, JSONObject(), ownsTransactions = false)
        AppDNA.billing.ownershipPolicy = if (provider == null) BillingOwnershipPolicy.NotConfigured else BillingOwnership.policy(provider)
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

    /** I4 minor 9: a tap between configure() and billing init is "not configured yet" (`unknown`). */
    @Test fun `a paywall tap before billing is configured - unknown not-configured, no started`() {
        tap(null)
        assertEquals(listOf("purchase_failed"), pipe!!.names())
        assertEquals("unknown", pipe!!.props("purchase_failed").getString("error_type"))
        assertEquals(BillingOwnershipPolicy.NOT_CONFIGURED_MESSAGE, pipe!!.props("purchase_failed").getString("error"))
        assertEquals(listOf("failed:unknown:pro_monthly"), calls)
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

    // -- the paywall RESTORE button (I3 r7 m1 + m5) ------------------------------------------------

    private val restoreCalls = mutableListOf<String>()

    private fun restoreTap(provider: BillingProvider?, ownsTransactions: Boolean = false) {
        pipe = BillingTestPipeline(ctx, "restore_tap")
        if (provider != null && provider !is BillingProvider.None) {
            sc = BillingScenario(ctx, JSONObject(), ownsTransactions = ownsTransactions)
        }
        AppDNA.billing.ownershipPolicy = if (provider == null) BillingOwnershipPolicy.NotConfigured else BillingOwnership.policy(provider)
        AppDNA.paywall.skipNextAutoDismissOnRestore = true
        val delegate = object : AppDNAPaywallDelegate {
            override fun onPaywallRestoreStarted(paywallId: String) { restoreCalls += "started" }
            override fun onPaywallRestoreCompleted(paywallId: String, productIds: List<String>) { restoreCalls += "completed" }
            override fun onPaywallRestoreFailed(paywallId: String, error: Throwable) {
                restoreCalls += "failed:${billingErrorType(error)}"
            }
        }
        val pm = PaywallManager(ai.appdna.sdk.config.RemoteConfigManager(null, LocalStorage(ctx), 3600L), pipe!!.tracker)
        try {
            pm.handleRestore("pw", delegate)
            repeat(20) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
        } finally {
            pm.shutdown()
        }
    }

    /**
     * I3 r7 m1 — under a provider that owns restoring, the tap is refused BEFORE `onPaywallRestoreStarted`,
     * as on iOS and as the purchase tap is. NEGATIVE CONTROL: with the policy gate removed from
     * `handleRestore`, Started fires first (`["started", "failed:providerNotAvailable"]`) — this fails.
     */
    @Test fun `restore tap under revenueCat - no started, one purchase_restore_failed, providerNotAvailable`() {
        restoreTap(BillingProvider.RevenueCat)
        assertEquals(listOf("failed:providerNotAvailable"), restoreCalls)
        assertEquals(listOf("purchase_restore_failed"), pipe!!.names())
        assertEquals("providerNotAvailable", pipe!!.props("purchase_restore_failed").getString("error_type"))
        assertEquals("pw", pipe!!.props("purchase_restore_failed").getString("paywall_id"))
        assertEquals("no billing flow", 0, sc!!.fake.launchCount)
        assertEquals(false, AppDNA.paywall.skipNextAutoDismissOnRestore)
    }

    @Test fun `restore tap under adapty - no started, providerNotAvailable`() {
        restoreTap(BillingProvider.Adapty("k"))
        assertEquals(listOf("failed:providerNotAvailable"), restoreCalls)
        assertEquals(1, pipe!!.count("purchase_restore_failed"))
    }

    @Test fun `restore tap under none - no started, providerNotAvailable`() {
        restoreTap(BillingProvider.None)
        assertEquals(listOf("failed:providerNotAvailable"), restoreCalls)
        assertEquals(listOf("purchase_restore_failed"), pipe!!.names())
    }

    /**
     * I3 r7 m5 — a restore tap before billing is configured (or after `shutdown()`, which puts the policy
     * back to `NotConfigured`) fails with the same `unknown` "not configured yet" error as the purchase tap
     * in the same window (§3.2 rule 3, R65–R67). NEGATIVE CONTROL: without the `isConfigured` check, the tap
     * fails `providerNotAvailable` (no manager) — this fails.
     */
    @Test fun `restore tap before billing is configured - unknown not-configured, no started`() {
        restoreTap(null)
        assertEquals(listOf("failed:unknown"), restoreCalls)
        assertEquals(listOf("purchase_restore_failed"), pipe!!.names())
        val props = pipe!!.props("purchase_restore_failed")
        assertEquals("unknown", props.getString("error_type"))
        assertEquals(BillingOwnershipPolicy.NOT_CONFIGURED_MESSAGE, props.getString("error"))
        assertEquals("not_configured", props.getString("reason"))
        assertEquals(false, AppDNA.paywall.skipNextAutoDismissOnRestore)
    }

    /** Positive control: under `storeKit2` the restore starts (Started fires, then it completes). */
    @Test fun `restore tap under storeKit2 - started fires`() {
        restoreTap(BillingProvider.StoreKit2, ownsTransactions = true)
        assertEquals("started", restoreCalls.firstOrNull())
    }

    @Test fun `PurchaseFailedException carries the verification error type`() {
        assertEquals("verificationFailed", billingErrorType(PurchaseFailedException("p", "x", errorType = "verificationFailed")))
        assertTrue(billingErrorType(PurchaseFailedException("p", "x")) == "unknown")
    }
}
