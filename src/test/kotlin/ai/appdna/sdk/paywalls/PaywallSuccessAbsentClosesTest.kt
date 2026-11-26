package ai.appdna.sdk.paywalls

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.TransactionInfo
import ai.appdna.sdk.billing.BillingScenario
import ai.appdna.sdk.billing.BillingTestPipeline
import ai.appdna.sdk.onboarding.OnboardingPaywallBridge
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * #2223 (owner, 2026-11-26: "SDK 1.0.93 closes it safely"; Android 1.0.65). This covers a successful purchase on a
 * paywall with NO post_purchase.on_success. A real PaywallActivity is launched, the purchase goes through
 * PaywallManager.handlePurchase, and Play is faked at the BillingClient.
 *   - still showing: the SDK finishes it after the default delay, and onPaywallDismissed fires;
 *   - presented by an onboarding flow: the bridge routes on_success_target;
 *   - the app closed it itself in onPaywallPurchaseCompleted: nothing extra fires.
 * RED CONTROL: with handlePostPurchaseSuccess's `config == null` branch reverted to "signalPurchaseEnded(); return", the
 * first two tests fail. The paywall stays up and the flow never routes. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaywallSuccessAbsentClosesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var sc: BillingScenario? = null
    private var pipe: BillingTestPipeline? = null
    private val host = mutableListOf<String>()
    private val purchase = """{
      "current_user_id":"user_a","first_identified_user_id":"user_a",
      "product_details":{"lifetime_unlock":{"price_micros":4990000,"currency":"USD","product_type":"inapp"}},
      "owned_purchases":[{"product_id":"lifetime_unlock","token":"tok_x","order_id":"GPA.x","acknowledged":false,"product_type":"inapp","purchase_state":"PURCHASED"}],
      "listener_purchase_token":"tok_x",
      "verify_replies":{"tok_x":{"status":200,"consume":false,"expires_at":null,"is_trial":false}}
    }"""

    @After fun tearDown() { sc?.teardown(); pipe?.close(); AppDNA.paywall.setDelegate(null) }

    private fun paywall(): PaywallConfig = PaywallConfigParser.parseSinglePaywall("pw_absent_close", mapOf(
        "id" to "pw_absent_close", "name" to "Synthetic", "placement" to "custom", "version" to 1,
        "layout" to mapOf("type" to "stack", "sections" to listOf(mapOf("id" to "h", "type" to "header", "config" to mapOf("title" to "Go further")))),
        "plans" to listOf(mapOf("id" to "l", "product_id" to "lifetime_unlock", "label" to "Lifetime", "price_display" to "$4.99", "is_default" to true)),
        "cta" to mapOf("text" to "Buy"),
        "dismiss" to mapOf("allowed" to true, "style" to "x_button"),
    ))!!

    /** Launch a real PaywallActivity, buy through handlePurchase, run 4 s of main-looper time. */
    private fun run(onboarding: Boolean, appClosesItItself: Boolean): Triple<Boolean, List<String>, String> {
        pipe = BillingTestPipeline(ctx, "pw_absent_close")
        val s = BillingScenario(ctx, JSONObject(purchase)).also { sc = it }
        s.setDelegate()
        val config = paywall()
        assertEquals(null, config.post_purchase?.on_success)
        PaywallActivity.launch(ctx, config.id, config)
        val intent = shadowOf(ctx as android.app.Application).nextStartedActivity
        val controller = Robolectric.buildActivity(PaywallActivity::class.java, intent).setup()
        val activity = controller.get()
        var routed = "none"
        val recorder = object : AppDNAPaywallDelegate {
            override fun onPaywallPurchaseCompleted(paywallId: String, productId: String, transaction: TransactionInfo) {
                host += "completed"
                if (appClosesItItself) activity.finish() // the documented pre-1.0.65 pattern: the app closes it
            }
            override fun onPaywallDismissed(paywallId: String) { host += "dismissed" }
        }
        val listener: AppDNAPaywallDelegate = if (onboarding) OnboardingPaywallBridge(
            onPurchased = { routed = "on_success_target" },
            onFailed = { routed = "on_fail_target" },
            onDismissedWithoutPurchase = { routed = "on_dismiss_target" },
        ) else recorder
        if (onboarding) AppDNA.paywall.setDelegate(recorder)
        val pm = PaywallManager(ai.appdna.sdk.config.RemoteConfigManager(null, ai.appdna.sdk.storage.LocalStorage(ctx), 3600L), pipe!!.tracker)
        try {
            pm.handlePurchase(activity, config.id, PaywallPlan(id = "l", product_id = "lifetime_unlock", name = "Lifetime", price = ""), config, emptyMap(), listener)
            repeat(40) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)); Thread.sleep(10) }
            s.idle(200)
        } finally { pm.shutdown() }
        val finishing = activity.isFinishing
        runCatching { controller.pause().stop().destroy() }
        repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        return Triple(finishing, host.toList(), routed)
    }

    @Test fun `no on_success - still showing - the SDK closes it and the app hears onPaywallDismissed`() {
        val (closed, h, _) = run(onboarding = false, appClosesItItself = false)
        assertTrue("the paywall stayed up after the purchase", closed)
        assertEquals(listOf("completed", "dismissed"), h)
    }

    @Test fun `no on_success - presented by an onboarding flow - the flow routes on_success_target`() {
        val (closed, h, routed) = run(onboarding = true, appClosesItItself = false)
        assertTrue(closed)
        assertEquals("on_success_target", routed)
        assertTrue("the host did not hear onPaywallDismissed through the bridge: $h", h.contains("dismissed"))
    }

    @Test fun `no on_success - the app closed it itself - nothing extra fires`() {
        val (closed, h, _) = run(onboarding = false, appClosesItItself = true)
        assertTrue("the app's own finish() closed it", closed)
        assertEquals(listOf("completed"), h)
        assertFalse(h.contains("dismissed"))
    }
}
