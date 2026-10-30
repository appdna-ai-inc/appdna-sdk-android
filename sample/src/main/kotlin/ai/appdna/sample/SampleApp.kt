// sample/example app.
//
// Application subclass that configures the AppDNA SDK on launch with a
// sandbox key. iOS parity: AppDelegate.application(_:didFinishLaunchingWithOptions:)
// calling AppDNA.configure(apiKey:environment:).
package ai.appdna.sample

import android.app.Application
import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Environment
import ai.appdna.sdk.paywalls.AppDNAPaywallDelegate

/**
 * The delegate a device walk reads, and why the sample carries one.
 *
 * A walk can only assert what it can observe, and the one fact a plan-selection walk is about —
 * WHICH product the CTA handed to billing — is not on the screen. `EventTracker` logs
 * `Tracked event: purchase_failed` at DEBUG with the NAME only, so logcat says a purchase was
 * attempted and not what for.
 *
 * The delegate does carry it: `onPaywallPurchaseStarted`/`Failed`/`Completed` all take a
 * `productId`. This logs it at INFO under the same `AppDNA-E2E` tag the base-URL line uses, which
 * makes "tapping the annual row changes what the CTA buys" readable from a device with no Play
 * billing configured — the failure still names the product it tried to buy.
 *
 * It is SAMPLE code, not SDK code, exactly like the iOS host's `TrackedEventLog`: an integrator
 * implements this interface for their own analytics, and this doubles as the living example of
 * how.
 */
private object SamplePaywallLog : AppDNAPaywallDelegate {
    /**
     * What the USER did, which is not a tracked event and so reaches no other log.
     * A device walk reads this line to prove a feature row, a plan row or a link is live.
     */
    override fun onPaywallAction(paywallId: String, action: ai.appdna.sdk.paywalls.PaywallAction) {
        android.util.Log.i("AppDNA-E2E", "AppDNA-E2E action=${action.value} paywall=$paywallId")
    }

    override fun onPaywallPurchaseStarted(paywallId: String, productId: String) {
        android.util.Log.i("AppDNA-E2E", "AppDNA-E2E purchase_started product_id=$productId paywall=$paywallId")
    }

    override fun onPaywallPurchaseFailed(
        paywallId: String,
        error: Throwable,
        errorType: String,
        productId: String?,
    ) {
        android.util.Log.i(
            "AppDNA-E2E",
            "AppDNA-E2E purchase_failed product_id=${productId ?: "(none)"} type=$errorType paywall=$paywallId",
        )
    }

    override fun onPaywallPurchaseCompleted(
        paywallId: String,
        productId: String,
        transaction: ai.appdna.sdk.TransactionInfo,
    ) {
        android.util.Log.i("AppDNA-E2E", "AppDNA-E2E purchase_completed product_id=$productId paywall=$paywallId")
    }
}

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Configure with a sandbox key. Real apps replace this with their
        // own `adn_test_<...>` (sandbox) or `adn_live_<...>` (production)
        // key issued from the AppDNA console.
        AppDNA.configure(
            context = this,
            // A build-time key wins (see SampleConfig); otherwise this literal, which device walks rewrite.
            apiKey = "appdna_sandbox_demo_key".let { SampleConfig.apiKey(this) ?: it },
            environment = Environment.SANDBOX,
            /*
             * DEBUG, so a device walk can read what the SDK did.
             *
             * `trigger_winback` is the one survey follow-up that RENDERS NOTHING: it fires
             * `survey_followup_winback` and expects the host to launch a campaign. A walk reads
             * the screen, so there is nothing on screen to assert — but `EventTracker.track`
             * already logs `Tracked event: <name>` at DEBUG, and the sample was running at the
             * WARNING default, which is why that line had never been seen.
             *
             * It is the sample app, not the SDK: an integrator's own log level is their choice.
             */
            options = ai.appdna.sdk.AppDNAOptions(logLevel = ai.appdna.sdk.LogLevel.DEBUG),
        )
        // Register the delegate AFTER configure, the way an integrator would.
        AppDNA.paywall.setDelegate(SamplePaywallLog)

        // The local-server precheck line every E2E host logs after configure: the base
        // URL the SDK resolved (diagnose()'s `base_url: <v>`) and the environment configured.
        val baseUrl = Regex("base_url: (\\S+)").find(AppDNA.diagnose())?.groupValues?.get(1) ?: "unknown"
        android.util.Log.i("AppDNA-E2E", "AppDNA-E2E base_url=$baseUrl env=${Environment.SANDBOX.name.lowercase()}")
    }
}
