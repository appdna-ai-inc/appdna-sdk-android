// sample/example app.
//
// Single Activity demo grid that exercises every SDK surface a host app
// is likely to integrate. Each button is a one-liner against the static
// AppDNA API so the sample doubles as living API documentation.
package ai.appdna.sample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.appdna.sdk.AppDNA

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Deep-link handoff on cold start. The intent that
        // launched the activity may carry a deep-link URI from the OS launcher.
        intent?.let { handleDeepLinkIntent(it) }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DemoScreen()
                }
            }
        }
    }

    /*
     * LOG THE STORED LOCATION ANSWER, so a device walk can assert what was PERSISTED
     * rather than what the dropdown drew.
     *
     * A suggestion row proves the provider answered. It says nothing about whether the structured
     * answer reached the store in the shape both SDKs decode, which is the half most likely to
     * bite: a wrong assumption about a provider's response shape looks exactly like "no results".
     *
     * `onResume` is the moment, because that is when an onboarding flow has been dismissed and the
     * answer has been persisted (`setOnboardingResponses` writes incrementally on step advance).
     * The field id comes from the launching intent, so the sample carries no probe-specific id:
     *
     *     adb shell am start -n ai.appdna.sample/.MainActivity \
     *       --es appdnaLocationField probe963_loc_all
     */
    override fun onResume() {
        super.onResume()
        val fieldId = intent?.getStringExtra("appdnaLocationField") ?: return
        // The RAW stored responses, so a walk reads every key persisted (`mapbox_id` included)
        // whatever `LocationData` version the sample is built against.
        android.util.Log.i(
            "AppDNA-E2E",
            "AppDNA-E2E responses " + (ai.appdna.sdk.core.SessionDataStore.instance?.onboardingResponses ?: "(none)"),
        )
        val d = AppDNA.getLocationData(fieldId)
        if (d == null) {
            android.util.Log.i("AppDNA-E2E", "AppDNA-E2E location field=$fieldId UNANSWERED")
            return
        }
        val parts = mutableListOf("field=$fieldId")
        d.formatted_address.takeIf { it.isNotBlank() }?.let { parts += "addr=$it" }
        d.city?.takeIf { it.isNotBlank() }?.let { parts += "city=$it" }
        d.country?.takeIf { it.isNotBlank() }?.let { parts += "country=$it" }
        d.country_code?.takeIf { it.isNotBlank() }?.let { parts += "cc=$it" }
        d.latitude?.let { parts += "lat=$it" }
        d.longitude?.let { parts += "lng=$it" }
        d.timezone?.takeIf { it.isNotBlank() }?.let { parts += "tz=$it" }
        d.timezone_offset?.let { parts += "tzoff=$it" }
        d.raw_query?.takeIf { it.isNotBlank() }?.let { parts += "typed=$it" }
        android.util.Log.i("AppDNA-E2E", "AppDNA-E2E location " + parts.joinToString(" | "))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Warm-start deep links arrive here; forward to the SDK.
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent) {
        intent.data?.toString()?.let { url ->
            AppDNA.deepLinks.handleURL(url)
        }
    }
}

@Composable
private fun DemoScreen() {
    // presentOnboarding requires an Activity (Compose-launched flow needs to
    // run on top of a host activity). LocalContext gives us the MainActivity.
    val activity = androidx.compose.ui.platform.LocalContext.current as? ComponentActivity
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("AppDNA SDK Sample", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))

        // ── Onboarding / Paywall / Survey ───────────────────────────────
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                // Replace "demo_onboarding" with a flow id from your console.
                activity?.let {
                    AppDNA.presentOnboarding(activity = it, flowId = "demo_onboarding")
                }
            },
        ) { Text("Present Onboarding") }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                // A build-time paywall id wins (`-PappdnaSamplePaywallId`, see SampleConfig); otherwise
                // replace "demo_paywall" with a paywall id from your console.
                val fromBuild = activity?.let { SampleConfig.paywallId(it) }
                if (fromBuild != null) AppDNA.showPaywall(id = fromBuild) else AppDNA.showPaywall(id = "demo_paywall")
            },
        ) { Text("Show Paywall") }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                // Replace "demo_survey" with a survey id from your console.
                AppDNA.showSurvey(id = "demo_survey")
            },
        ) { Text("Show Survey") }

        Spacer(Modifier.height(16.dp))

        // ── Identity / Events / Session ─────────────────────────────────
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                AppDNA.identify(
                    userId = "demo_user_123",
                    traits = mapOf(
                        "plan" to "pro",
                        "signup_source" to "sample_app",
                    ),
                )
            },
        ) { Text("Identify Demo User") }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                AppDNA.track(
                    event = "sample_button_tapped",
                    properties = mapOf(
                        "screen" to "demo",
                        "value" to 42,
                    ),
                )
            },
        ) { Text("Track Custom Event") }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                // SessionData is available to dynamic-content templating in
                // onboarding/paywalls/messages without re-identifying the user.
                AppDNA.setSessionData(key = "current_screen", value = "demo")
                AppDNA.setSessionData(key = "experiment_arm", value = "B")
            },
        ) { Text("Set Session Data") }
    }
}
