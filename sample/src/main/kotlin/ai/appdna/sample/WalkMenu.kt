// A DEVICE-WALK MENU, for a person holding the phone (the Android twin of the iOS host's WalkMenu.swift).
//
// A walk needs a dozen surfaces, and this sample has one button per surface type with ids baked into
// the code. A walk build instead carries its menu in the merged manifest (`-PappdnaWalkMenu=<base64>`,
// see build.gradle.kts): base64 of {"title", "items": [{"title", "kind", "value"}]}, where kind is one
// of flow, placement, paywall, survey, screen, track, identify, reset, push. Empty by default, so a stock build opens
// on the demo screen exactly as before. 🔴 This module is mirrored to the PUBLIC SDK repo: no key, id or
// URL is committed here — the values are supplied by whoever builds the walk.
package ai.appdna.sample

import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.AppDNADeepLinkDelegate
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class WalkMenuItem(val title: String, val kind: String, val value: String)

internal object WalkMenu {
    fun parse(b64: String?): Pair<String, List<WalkMenuItem>>? = runCatching {
        if (b64.isNullOrBlank()) return null
        val json = JSONObject(String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8))
        val items = json.getJSONArray("items")
        json.optString("title", "Walk") to (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            WalkMenuItem(o.getString("title"), o.getString("kind"), o.optString("value", ""))
        }
    }.getOrNull()?.takeIf { it.second.isNotEmpty() }

    fun run(activity: ComponentActivity, item: WalkMenuItem) {
        when (item.kind) {
            "flow" -> {
                val shown = AppDNA.presentOnboarding(activity = activity, flowId = item.value)
                WalkLog.note("presentOnboarding(${item.title}) → ${if (shown) "presented" else "NOT presented (flow not found)"}")
            }
            "placement" -> {
                val shown = AppDNA.presentPaywallByPlacement(activity = activity, placement = item.value)
                WalkLog.note("presentPaywall(placement=${item.value}) → ${if (shown) "presented" else "NOT presented (no paywall)"}")
            }
            "paywall" -> { AppDNA.showPaywall(id = item.value); WalkLog.note("showPaywall(${item.title})") }
            "survey" -> { AppDNA.showSurvey(id = item.value); WalkLog.note("showSurvey(${item.title})") }
            "screen" -> {
                AppDNA.showScreen(screenId = item.value) { r -> WalkLog.note("screen ${item.title} → $r") }
                WalkLog.note("showScreen(${item.title})")
            }
            "track" -> { AppDNA.track(event = item.value); WalkLog.note("track(${item.value})") }
            "identify" -> { AppDNA.identify(userId = item.value); WalkLog.note("identify(${item.value})") }
            "reset" -> { AppDNA.reset(); WalkLog.note("reset()") }
            "push" -> AppDNA.push.requestPermissionFuture(activity).whenComplete { granted, _ ->
                WalkLog.note("notification permission → ${if (granted == true) "granted" else "denied"}")
            }
            else -> WalkLog.note("unknown walk item kind ${item.kind}")
        }
    }
}

/** The menu's visible log and the last deep link, fed by the SDK's own observers. */
internal object WalkLog : ai.appdna.sdk.generated.AppDNALifecycleDelegate, AppDNADeepLinkDelegate,
    ai.appdna.sdk.onboarding.AppDNAOnboardingDelegate {
    val lines = mutableStateListOf<String>()
    val deepLink = mutableStateOf<String?>(null)
    private val main = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val keys = listOf("flow_id", "paywall_id", "survey_id", "product_id", "placement", "step_id", "error", "reason", "url", "responses")

    fun install() {
        AppDNA.setLifecycleDelegate(this)
        AppDNA.deepLinks.setDelegate(this)
        AppDNA.onboarding.setDelegate(this)
    }

    fun note(line: String) {
        val stamped = "${clock.format(Date())} $line"
        main.post {
            lines.add(0, stamped)
            while (lines.size > 40) lines.removeAt(lines.size - 1)
        }
    }

    override fun onEventTracked(name: String, properties: Map<String, Any?>) {
        val detail = keys.mapNotNull { k -> properties[k]?.let { "$k=$it" } }.joinToString(" ")
        note(if (detail.isEmpty()) name else "$name $detail")
    }

    // The onboarding callbacks a walk has to READ, not infer: the step on screen (a timed advance is clocked
    // from the log), what an element handed the host (a toggle's `toggle_dark_mode` with "true"/"false"), and
    // the responses a completed flow delivered. Observe-only: every hook returns the SDK's default.
    override fun onOnboardingStepChanged(flowId: String, stepId: String, stepIndex: Int, totalSteps: Int) {
        note("step ${stepIndex + 1}/$totalSteps $stepId")
    }

    override fun onOnboardingCompleted(flowId: String, responses: Map<String, Any>) {
        note("onboarding completed responses=" + JSONObject(responses.toSortedMap()).toString())
    }

    override suspend fun onElementInteraction(
        flowId: String,
        stepId: String,
        blockId: String,
        action: String,
        value: String?,
        inputValues: Map<String, Any>,
    ): ai.appdna.sdk.onboarding.ElementInteractionResult? {
        note("element blockId=$blockId action=$action value=${value ?: "null"}")
        return null
    }

    override fun onDeepLinkReceived(url: String, params: Map<String, String>) {
        note("deep link received: $url")
        main.post { deepLink.value = url }
    }
}

@Composable
internal fun WalkMenuScreen(activity: ComponentActivity?, title: String, items: List<WalkMenuItem>) {
    val link = WalkLog.deepLink.value
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (link != null) {
            // A deep link lands on its OWN screen, so "the app opened on the linked screen, not the root"
            // is something a tester can see.
            Text("Deep link screen", style = MaterialTheme.typography.headlineMedium)
            val uri = android.net.Uri.parse(link)
            Text("${uri.host ?: ""}${uri.path ?: ""}", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
            Text(link, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Button(modifier = Modifier.fillMaxWidth(), onClick = { WalkLog.deepLink.value = null }) { Text("Back to the menu") }
        } else {
            // An if / else, not an early `return@Column`: leaving an inline composable lambda early on recomposition
            // unbalances Compose's group stack and crashed the app the moment a link arrived.
            Text(title, style = MaterialTheme.typography.headlineSmall)
            for (item in items) {
                Button(modifier = Modifier.fillMaxWidth(), onClick = { activity?.let { WalkMenu.run(it, item) } }) { Text(item.title) }
            }
            Text("Log (newest first)", style = MaterialTheme.typography.titleMedium)
            for (line in WalkLog.lines) Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text("Stored onboarding responses", style = MaterialTheme.typography.titleMedium)
            Text(
                (ai.appdna.sdk.core.SessionDataStore.instance?.onboardingResponses ?: "(none stored)").toString(),
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            )
        }
    }
}
