package ai.appdna.sdk.onboarding

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * One onboarding step's "Add widget" wiring: the channel its buttons tap, the attempt, and the
 * Android how-to sheet the attempt may show. Built by `BlockBasedStepView`, once per presentation.
 */
internal class WidgetStepWiring(
    val attempt: WidgetPinAttempt,
    internal val howto: MutableState<List<ContentBlock>?>,
) {
    val channel = WidgetActionChannel { block -> attempt.tap(block) }
}

/**
 * The [WidgetPinPlatform] a step uses: the host's own `AppWidgetManager`, behind the debug-only
 * launcher simulation when a debuggable build sets it. The simulation is re-read at each tap, so a
 * device walk can switch it between attempts without re-presenting the step.
 */
internal class HostWidgetPinPlatform(context: Context) : WidgetPinPlatform {
    private val app = context.applicationContext
    private val real = AndroidWidgetPinPlatform(app)
    private var current: WidgetPinPlatform = real

    override val sdkInt: Int get() = current.sdkInt

    override fun homeScreenProviders(): List<String> {
        // The attempt asks this first, at the tap: the moment to (re)read the simulation.
        current = WidgetPinSimulation.wrap(real, WidgetPinSimulation.mode(app.applicationInfo))
        return current.homeScreenProviders()
    }

    override fun isRequestPinSupported(): Boolean = current.isRequestPinSupported()
    override fun requestPin(providerClass: String, token: String): Boolean = current.requestPin(providerClass, token)
    override fun widgetIds(providerClass: String): Set<Int>? = current.widgetIds(providerClass)
}

@Composable
internal fun rememberWidgetStepWiring(
    context: Context,
    flowId: String,
    stepId: String,
    /** The step's required-field gate; shows the validation message itself on a refusal. */
    gatePasses: () -> Boolean,
    /** Advance once with `widget_status` merged last into the step's data. */
    advance: (status: String) -> Unit,
): WidgetStepWiring {
    val gate = rememberUpdatedState(gatePasses)
    val adv = rememberUpdatedState(advance)
    // SPEC-536: every widget event carries the flow's content (`content_version` / `content_source_id`).
    val contentStamp = ai.appdna.sdk.core.LocalContentStamp.current
    val wiring = remember(stepId) {
        val howto = mutableStateOf<List<ContentBlock>?>(null)
        WidgetPinTiming.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val effects = object : WidgetPinEffects {
            override fun gatePasses(): Boolean {
                val ok = gate.value()
                if (!ok) OnboardingPressedButton.clear()
                return ok
            }
            override fun track(name: String, properties: Map<String, Any>) {
                WidgetPinTiming.event(name, properties)
                ai.appdna.sdk.AppDNA.track(name, ai.appdna.sdk.core.ContentStamp.apply(properties, contentStamp))
            }
            override fun advance(status: String) {
                WidgetPinTiming.mark("advance status=$status")
                adv.value(status)
            }
            override fun showHowto(blocks: List<ContentBlock>) { howto.value = blocks }
            override fun closeHowto() { howto.value = null }
            override fun onAttemptRetired() = OnboardingPressedButton.clear()
            override fun schedule(delayMs: Long, action: () -> Unit): WidgetPinTimer {
                WidgetPinTiming.mark("timer_set ms=$delayMs")
                return mainThreadSchedule(delayMs) { WidgetPinTiming.mark("timer_fired ms=$delayMs"); action() }
            }
        }
        WidgetStepWiring(WidgetPinAttempt(HostWidgetPinPlatform(context), effects, flowId, stepId), howto)
    }

    // The attempt is driven by the host activity's lifecycle (the pin dialog pauses it) and by the
    // launcher's success broadcast. Leaving the composition is DESTROY: the attempt is discarded.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, wiring) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> { WidgetPinTiming.mark("pause"); wiring.attempt.onPause() }
                Lifecycle.Event.ON_RESUME -> { WidgetPinTiming.mark("resume"); wiring.attempt.onResume() }
                else -> Unit
            }
        }
        val onCallback: (String?) -> Unit = { token -> wiring.attempt.onCallback(token) }
        WidgetPinRegistry.register(onCallback)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            WidgetPinRegistry.unregister(onCallback)
            wiring.attempt.onDestroy()
            wiring.howto.value = null
        }
    }
    return wiring
}

/** The Android how-to, over the step, while the attempt shows one. */
@Composable
internal fun WidgetHowtoSheet(wiring: WidgetStepWiring, loc: (String, String) -> String) {
    val blocks = wiring.howto.value ?: return
    // The how-to is a modal above the step: when its own text shows a Google answer, the credit is
    // drawn inside it (the step's own walk skips the how-to keys).
    val credit = LocalGoogleCreditSheetContext.current?.let { GoogleCredit.howtoShows(blocks, it.answers) } ?: false
    DeviceAppearanceTheme {
        BlockSheet(
            blocks = blocks,
            loc = loc,
            onDone = { wiring.attempt.onDoneCta() },
            onClose = { via -> wiring.attempt.onClose(via) },
            toggleValues = remember { mutableStateMapOf() },
            inputValues = remember { mutableStateMapOf() },
            forwardEveryAction = true,
            skipPartiallyExpanded = true,
            showsGoogleCredit = credit,
        )
    }
}
