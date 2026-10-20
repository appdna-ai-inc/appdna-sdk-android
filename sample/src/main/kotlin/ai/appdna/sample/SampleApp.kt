// sample/example app.
//
// Application subclass that configures the AppDNA SDK on launch with a
// sandbox key. iOS parity: AppDelegate.application(_:didFinishLaunchingWithOptions:)
// calling AppDNA.configure(apiKey:environment:).
package ai.appdna.sample

import android.app.Application
import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Environment

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Configure with a sandbox key. Real apps replace this with their
        // own `adn_test_<...>` (sandbox) or `adn_live_<...>` (production)
        // key issued from the AppDNA console.
        AppDNA.configure(
            context = this,
            apiKey = "appdna_sandbox_demo_key",
            environment = Environment.SANDBOX,
            /*
             * #1043 — DEBUG, so a device walk can read what the SDK did.
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
        // The local-server precheck line every E2E host logs after configure: the base
        // URL the SDK resolved (diagnose()'s `base_url: <v>`) and the environment configured.
        val baseUrl = Regex("base_url: (\\S+)").find(AppDNA.diagnose())?.groupValues?.get(1) ?: "unknown"
        android.util.Log.i("AppDNA-E2E", "AppDNA-E2E base_url=$baseUrl env=${Environment.SANDBOX.name.lowercase()}")
    }
}
