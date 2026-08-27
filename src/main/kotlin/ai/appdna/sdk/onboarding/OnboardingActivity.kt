package ai.appdna.sdk.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// SPEC-070-A J.11 — accessibility string resources for onboarding chrome
// (back / close / dismiss). Hosts can override via their own strings.xml.
import ai.appdna.sdk.Log
import ai.appdna.sdk.R
import ai.appdna.sdk.core.HapticEngine
import ai.appdna.sdk.core.LocalizationEngine
import ai.appdna.sdk.core.entryAnimation
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.core.interpolated
import ai.appdna.sdk.events.EventTracker
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Activity to render onboarding flow UI using Jetpack Compose.
 * Follows the same pattern as SurveyActivity.
 */
class OnboardingActivity : ComponentActivity() {

    /**
     * SPEC-070-A J.21 — VM-backed state survives config changes / process
     * death without leaking pre-launch lambdas across distinct flow launches.
     * `by viewModels()` scopes the VM to THIS Activity instance (each launch
     * is a new ViewModelStoreOwner), so Activity-A's flow never bleeds into
     * Activity-B's flow.
     */
    private val viewModel: OnboardingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SPEC-070-A I.16 — edge-to-edge so Compose `imePadding()`/`safeDrawingPadding()`
        // modifiers in the renderer roots resolve correctly and keyboards don't
        // crop content. The manifest declares `windowSoftInputMode="adjustResize"`
        // so Compose receives IME inset changes.
        // SPEC-419 — TRUE edge-to-edge so the full-bleed step background paints UNDER the
        // status + nav bars (matches iOS `.ignoresSafeArea()`). The Activity uses the legacy
        // @android:style/Theme.Translucent.NoTitleBar where `statusBarColor=transparent` alone
        // is NOT enough — the content stays inset and the system-bar regions show black bands
        // (reported top + bottom). `enableEdgeToEdge()` (androidx.activity) lays the window out
        // edge-to-edge; clearing the contrast scrim stops the system re-tinting the bars.
        // SPEC-419 — pass TRANSPARENT scrims. The no-arg enableEdgeToEdge() installs a default
        // SystemBarStyle.auto scrim on the NAV bar (a translucent dark scrim for contrast); on dark
        // gradient steps that scrim darkened the top of the nav region just below the CTA → a dark
        // band the user read as a "gap below the button" (iOS has no such scrim). Transparent scrims
        // remove it (navigationBarColor=TRANSPARENT alone did NOT — the SystemBarStyle owns the scrim).
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // Theme.Translucent.NoTitleBar sets FLAG_TRANSLUCENT_STATUS/NAVIGATION, which forces an
        // opaque/scrim system-bar background and makes statusBarColor=transparent a NO-OP (the bars
        // stayed pure black top + bottom). Clear those flags + enable app-drawn bar backgrounds so
        // the full-bleed step bg paints under the bars (matches iOS .ignoresSafeArea()).
        window.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
        )
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        // SPEC-070-A J.21 — first-launch path: drain the next-launch payload
        // into the VM. On a config-change recreate the VM is already bound,
        // so we skip rebinding (preserving live currentIndex / responses).
        if (!viewModel.isBound) {
            val payload = consumePendingLaunchPayload()
            if (payload == null) {
                // No payload + no bound VM == process was killed before
                // onCreate ran (cold restart with no flow to resume). Bail.
                finish()
                return
            }
            viewModel.bind(payload)
        }

        val flow = viewModel.flow ?: run {
            finish()
            return
        }

        // SPEC-070-A I.7 — restore (currentStepIndex, responses) from
        // `savedInstanceState` so process-death / config-change rotations
        // re-enter the flow on the same step with prior answers intact.
        // Mirrors iOS `OnboardingRenderer` `@SceneStorage` round-trip.
        //
        // Process-death case: the VM was destroyed along with the Activity,
        // so `viewModel.responses` is empty here — we hydrate it from the
        // bundle. Config-change case: the VM survived, so we DON'T overwrite
        // its in-memory state with a (potentially stale) bundle snapshot.
        if (savedInstanceState != null && viewModel.responses.isEmpty() &&
            viewModel.currentIndex.intValue == 0
        ) {
            val savedIndex = savedInstanceState.getInt(KEY_CURRENT_STEP_INDEX, 0)
            val savedResponsesJson = savedInstanceState.getString(KEY_RESPONSES)
            viewModel.restoredStepIndex = savedIndex.coerceAtLeast(0)
            viewModel.restoredResponses = savedResponsesJson?.let { json ->
                try {
                    // SPEC-401-A R33 P1 — deep-convert JSONObject/JSONArray
                    // → Map/List recursively. iOS Codable round-trip yields
                    // native [String:Any]; Android `obj.opt(k)` returns raw
                    // JSONObject/JSONArray for nested values, which then
                    // fails `as? Map<String, Any?>` cast in
                    // NextStepRuleEvaluator.kt:50, falling through to the
                    // `else -> true` branch and picking the wrong route
                    // for any form/multi-select/chat-transcript step after
                    // process death.
                    fromJsonAny(org.json.JSONObject(json)) as? Map<String, Any>
                } catch (_: Throwable) { null }
            }
            // SPEC-070-A finalization P0 audit-8 D2 — restore navigationHistory
            // alongside currentIndex / responses so previous_step_* rules
            // continue to evaluate correctly after process death.
            savedInstanceState.getStringArray(KEY_NAV_HISTORY)?.let { arr ->
                viewModel.navigationHistory.clear()
                viewModel.navigationHistory.addAll(arr)
            }
        }

        setContent {
            // SPEC-070-A D.5 — system dark-mode pref so onboarding renderers
            // can pick `dark` overrides from console content blocks.
            val isDark = isSystemInDarkTheme()
            // SPEC-401-A R52 (Lens C R52 #5, P1) — actually apply
            // dark-mode color scheme. Was bare `MaterialTheme {}` which
            // always defaulted to lightColorScheme(); muted text labels
            // (alpha 0.5/0.6/0.7 onBackground/onSurface) rendered
            // dark-on-dark on dark devices and washed out completely.
            // iOS adapts via UIHostingController inheriting
            // userInterfaceStyle automatically.
            MaterialTheme(
                colorScheme = if (isDark)
                    androidx.compose.material3.darkColorScheme()
                else
                    androidx.compose.material3.lightColorScheme(),
            ) {
                OnboardingFlowHost(
                    flow = flow,
                    viewModel = viewModel,
                    delegate = viewModel.delegate,
                    eventTracker = viewModel.eventTracker,
                    isDark = isDark,
                    onStepViewed = { stepId, stepIndex ->
                        viewModel.onStepViewed?.invoke(stepId, stepIndex)
                    },
                    onStepCompleted = { stepId, stepIndex, data ->
                        viewModel.onStepCompleted?.invoke(stepId, stepIndex, data)
                    },
                    onStepSkipped = { stepId, stepIndex ->
                        viewModel.onStepSkipped?.invoke(stepId, stepIndex)
                    },
                    onFlowCompleted = { responses ->
                        viewModel.onFlowCompleted?.invoke(responses)
                        cleanup()
                    },
                    onFlowDismissed = { lastStepId, lastStepIndex ->
                        viewModel.onFlowDismissed?.invoke(lastStepId, lastStepIndex)
                        cleanup()
                    }
                )
            }
        }
    }

    private fun cleanup() {
        // SPEC-070-A J.21 — null out delegate + lambda captures eagerly so a
        // stray reference to the VM (e.g. held by a still-running coroutine)
        // doesn't keep the host's strategy/view-model graph alive past the
        // flow end. The full VM is also auto-cleared by Android on finish().
        viewModel.reset()
        finish()
    }

    override fun onBackPressed() {
        // SPEC-070-A I.4 — respect `flow.settings.dismiss_allowed` (canDismiss)
        // when handling the system back button. When dismissal is disallowed
        // the flow intercepts back, mirroring iOS force-flow behavior.
        // (allow_back gates step-back navigation, not the global dismiss.)
        val flow = viewModel.flow
        val canDismiss = flow?.settings?.dismiss_allowed ?: true
        if (!canDismiss) {
            // Force-flow: ignore system back to prevent accidental abandonment.
            return
        }
        // SPEC-401-A R60 (Lens B P2) — invoke dismiss callback BEFORE finishing
        // the Activity. Was calling `super.onBackPressed()` first, which
        // synchronously calls `finish()` and puts the Activity in
        // `isFinishing = true` before `onOnboardingDismissed` runs. Hosts
        // that do UI work in their dismiss handler (e.g. `startActivity(...)`
        // to navigate to home) launched from a finishing Activity context,
        // causing overlap-animation glitches and Android-only "Activity is
        // destroyed" warnings the iOS counterpart never produces. The
        // X-button path (line ~1334) already uses the correct order; this
        // brings system-back into parity. iOS canonical: callback fires
        // BEFORE `viewController.dismiss()` at OnboardingFlowManager.swift
        // :96-104 + OnboardingRenderer.swift:278-289.
        // SPEC-401-A R9 — report the currently-visible step (matches iOS
        // OnboardingRenderer.swift:280-281 which uses `currentStep`/
        // `currentIndex`). Previously reported `steps.first()` + index 0
        // which broke `onOnboardingDismissed(atStep:)` semantics and
        // corrupted dismiss analytics for any flow with >1 step.
        flow?.let { f ->
            val idx = viewModel.currentIndex.intValue.coerceIn(0, (f.steps.size - 1).coerceAtLeast(0))
            val stepId = f.steps.getOrNull(idx)?.id ?: ""
            viewModel.onFlowDismissed?.invoke(stepId, idx)
        }
        // cleanup() calls reset() + finish(); no need for super.onBackPressed()
        // on top — that would cause a duplicate finish() call.
        cleanup()
    }

    /**
     * SPEC-070-A I.7 — persist current step + responses across config
     * changes / process death so the flow re-enters on the same step.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_STEP_INDEX, viewModel.currentIndex.intValue)
        try {
            val obj = org.json.JSONObject()
            for ((k, v) in viewModel.responses) {
                obj.put(k, when (v) {
                    is Map<*, *> -> org.json.JSONObject(v as Map<String, Any?>)
                    is List<*> -> org.json.JSONArray(v)
                    else -> v
                })
            }
            outState.putString(KEY_RESPONSES, obj.toString())
        } catch (_: Throwable) { /* best-effort */ }
        // SPEC-070-A finalization P0 audit-8 D2 — persist navigationHistory
        // alongside currentIndex / responses. previous_step_* rules need
        // this for correctness after rotation / process death.
        outState.putStringArray(KEY_NAV_HISTORY, viewModel.navigationHistory.toTypedArray())
    }

    /**
     * SPEC-070-A J.21 — typed payload handed off from
     * [OnboardingFlowManager.present] to the next [OnboardingActivity]
     * `onCreate`. Replaces the previous bag-of-companion-statics. Each
     * Activity instance consumes the slot exactly once on first
     * `onCreate` then writes it into its [OnboardingViewModel] so the
     * payload's lifetime is bounded to that Activity instance.
     */
    internal data class LaunchPayload(
        val flow: OnboardingFlowConfig,
        val delegate: AppDNAOnboardingDelegate?,
        val eventTracker: EventTracker?,
        val onStepViewed: ((String, Int) -> Unit)?,
        val onStepCompleted: ((String, Int, Map<String, Any>?) -> Unit)?,
        val onStepSkipped: ((String, Int) -> Unit)?,
        val onFlowCompleted: ((Map<String, Any>) -> Unit)?,
        val onFlowDismissed: ((String, Int) -> Unit)?,
    )

    companion object {
        private const val KEY_CURRENT_STEP_INDEX = "appdna_onboarding_current_step_index"
        private const val KEY_RESPONSES = "appdna_onboarding_responses"
        // SPEC-070-A finalization P0 audit-8 D2 — navigation history for
        // previous_step_* rule operators (iOS OnboardingRenderer.swift:982-988).
        private const val KEY_NAV_HISTORY = "appdna_onboarding_nav_history"

        /**
         * SPEC-070-A J.21 — single-slot next-launch payload. Set by [launch]
         * immediately before `startActivity()` and consumed by `onCreate`
         * via [consumePendingLaunchPayload]. Volatile because [launch]
         * may be called from any thread and `onCreate` runs on Main.
         *
         * Only one onboarding flow can be in-flight at a time. The flow
         * manager doesn't enforce this — but the app itself does, since
         * `presentOnboarding(...)` always blocks the calling Activity
         * until the system stacks the new OnboardingActivity. If a host
         * stacks a second `launch()` before the first `onCreate` ran the
         * later one wins (matches the previous companion-static behavior;
         * we tolerate this edge case rather than queue).
         */
        @Volatile
        private var pendingLaunchPayload: LaunchPayload? = null

        /**
         * Atomically read + clear the next-launch payload. Returns null if
         * no flow was queued (cold restart with no caller, or a same-process
         * recreate where the VM should already be bound).
         */
        @Synchronized
        private fun consumePendingLaunchPayload(): LaunchPayload? {
            val p = pendingLaunchPayload
            pendingLaunchPayload = null
            return p
        }

        /**
         * SPEC-401-A R73 (Lens B P1) — drop pending launch payload on SDK
         * shutdown so OnboardingFlowConfig + delegate + EventTracker + 5
         * lambda closures don't leak across `AppDNA.shutdown()` →
         * `configure()`. Mirrors PaywallActivity / SurveyActivity /
         * ScreenHostActivity `clearActiveLaunches()` (called from
         * `AppDNA.shutdown()` at AppDNA.kt:1752-1754). Without this,
         * `presentOnboarding()` immediately followed by `shutdown()` could
         * leave Activity launching with a stale EventTracker whose queue
         * was nulled — onboarding step events become silent no-ops.
         */
        @JvmStatic
        @Synchronized
        internal fun clearActiveLaunches() {
            pendingLaunchPayload = null
        }

        internal fun launch(
            context: Context,
            flow: OnboardingFlowConfig,
            delegate: AppDNAOnboardingDelegate? = null,
            eventTracker: EventTracker? = null,
            onStepViewed: ((String, Int) -> Unit)? = null,
            onStepCompleted: ((String, Int, Map<String, Any>?) -> Unit)? = null,
            onStepSkipped: ((String, Int) -> Unit)? = null,
            onFlowCompleted: ((Map<String, Any>) -> Unit)? = null,
            onFlowDismissed: ((String, Int) -> Unit)? = null
        ) {
            pendingLaunchPayload = LaunchPayload(
                flow = flow,
                delegate = delegate,
                eventTracker = eventTracker,
                onStepViewed = onStepViewed,
                onStepCompleted = onStepCompleted,
                onStepSkipped = onStepSkipped,
                onFlowCompleted = onFlowCompleted,
                onFlowDismissed = onFlowDismissed,
            )

            val intent = Intent(context, OnboardingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

// EPIC-2 — flow-level continuous progress bar. A custom Box-based bar (replaces Material
// LinearProgressIndicator which floored ~4dp) so progress_height honours any thickness, plus an
// optional multi-color gradient fill (progress_gradient_colors). Extracted as a top-level composable
// so the SPEC-419 visual-snapshot harness can render it on the JVM.
@Composable
internal fun ContinuousProgressBar(
    progress: Float,
    color: Color,
    trackColor: Color,
    height: androidx.compose.ui.unit.Dp,
    gradientColors: List<Color>? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(trackColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(height / 2))
                .then(
                    if (gradientColors != null) {
                        Modifier.background(androidx.compose.ui.graphics.Brush.horizontalGradient(gradientColors))
                    } else {
                        Modifier.background(color)
                    },
                ),
        )
    }
}

// EPIC-2 — a nav-bar glyph (custom back arrow / close ✕) as a single Text. Extracted so the
// custom-glyph + back⇄X switch render is snapshot-testable.
@Composable
internal fun NavGlyph(glyph: String, color: Color, size: androidx.compose.ui.unit.TextUnit) {
    Text(
        text = glyph,
        fontSize = size,
        fontWeight = FontWeight.SemiBold,
        color = color,
    )
}

@Composable
internal fun OnboardingFlowHost(
    flow: OnboardingFlowConfig,
    // SPEC-070-A J.21 — VM is the single source of truth for `currentIndex`
    // + `responses`. The composable observes Compose state inside the VM
    // directly so writes survive Activity recreation without a separate
    // mirroring `LaunchedEffect`. Optional for tests / previews that build
    // the host without an Activity — when null we fall back to local
    // `remember`-backed state, mirroring the original behavior.
    viewModel: OnboardingViewModel? = null,
    delegate: AppDNAOnboardingDelegate? = null,
    eventTracker: EventTracker? = null,
    onStepViewed: (String, Int) -> Unit,
    onStepCompleted: (String, Int, Map<String, Any>?) -> Unit,
    onStepSkipped: (String, Int) -> Unit,
    onFlowCompleted: (Map<String, Any>) -> Unit,
    onFlowDismissed: (String, Int) -> Unit,
    // SPEC-070-A D.5 — propagated to step renderers so block-level `dark`
    // overrides from the console are picked correctly.
    @Suppress("UNUSED_PARAMETER") isDark: Boolean = false,
) {
    // SPEC-070-A J.21 — when running under an Activity, use the VM's state
    // directly so the composable's `currentIndex` / `responses` survive
    // Activity recreation. When the VM is null (test / preview path) fall
    // back to local `remember`-backed state seeded from the legacy
    // process-death restore route below.
    val currentIndexState = viewModel?.currentIndex
        ?: remember { mutableIntStateOf(0) }
    val responses: SnapshotStateMap<String, Any> = viewModel?.responses
        ?: remember { mutableStateMapOf() }

    // SPEC-070-A I.7 — one-shot consume the process-death restore snapshot.
    // The VM holds the snapshot when the Activity rehydrated from
    // `savedInstanceState` and the responses map was empty (cold restore).
    LaunchedEffect(viewModel) {
        val vm = viewModel ?: return@LaunchedEffect
        val ri = vm.restoredStepIndex
        val rr = vm.restoredResponses
        if (ri != null) {
            currentIndexState.intValue = ri
            vm.restoredStepIndex = null
        }
        if (rr != null) {
            responses.putAll(rr)
            vm.restoredResponses = null
        }
    }

    // SPEC-070-A J.21 — Compose's `MutableIntState` `getValue` / `setValue`
    // operator extensions let us bind a local `var` to the VM-backed state
    // so the rest of the composable body keeps its existing assignment
    // syntax (`currentIndex++`, `currentIndex = targetIndex`) while writes
    // route through `currentIndexState.intValue` and survive recreation.
    var currentIndex by currentIndexState

    // SPEC-070-A J.2 — haptic feedback for step advance / back / dismiss /
    // button-tap interactions, gated by `flow.settings.haptic.enabled`.
    val hostView = LocalView.current
    val hapticConfig = flow.settings.haptic
    val coroutineScope = rememberCoroutineScope()
    // SPEC-070-A finalization OB-5 — hoist Activity context for the
    // OnboardingPaywallBridge presentation path. AppDNA.paywall.present
    // requires an Activity, and the OnboardingActivity itself is the
    // topmost; this Composable is rendered inside it.
    val activityCtx = androidx.compose.ui.platform.LocalContext.current

    // SPEC-070-A finalization P0 audit-7+8 — navigation history stack for
    // `previous_step_equals` / `previous_step_in` rule operators
    // (iOS OnboardingRenderer.swift:982-988, 1065-1088). Push the step
    // id we're LEAVING from before changing currentIndex; rule evaluator
    // reads `lastOrNull()` as the user's previous step. Mirrors iOS
    // `navigationHistory: [String]`.
    //
    // Audit-8 D2: hoisted into OnboardingViewModel so it survives
    // Activity recreation alongside currentIndex / responses. Test path
    // (no VM available) falls back to a `remember` list — recreation
    // wouldn't apply there.
    val navigationHistory = viewModel?.navigationHistory ?: remember { mutableStateListOf<String>() }

    // SPEC-083: Hook state
    var isProcessing by remember { mutableStateOf(false) }
    // SPEC-419 STEP-2 — lightweight guard for in-flight element interactions (calendar tap, otp digit,
    // memory flip, press-hold, wheel commit, footer toggle). Separate from the full-step `isProcessing`
    // so an element fire never dims the whole step; overlapping fires are dropped.
    var interactionInFlight by remember { mutableStateOf(false) }
    var loadingText by remember { mutableStateOf("Processing...") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showError by remember { mutableStateOf(false) }
    // SPEC-070-A C.8 — success banner state (port of iOS v1.0.60 SPEC-083
    // amendment `.stay(message:)`). Mirrors the existing error banner pair.
    var successMessage by remember { mutableStateOf<String?>(null) }
    var showSuccess by remember { mutableStateOf(false) }
    // SPEC-401-A R57 (Lens B P1) — read from VM so onBeforeStepRender
    // overrides survive Activity recreation. Was composable-scoped
    // `remember` which reset on rotation/locale/dark-mode toggle while
    // sister state (currentIndex/responses/navHistory) survived,
    // causing a flash of original config until LaunchedEffect re-awaited
    // onBeforeStepRender. Mirrors iOS @State preservation via SwiftUI
    // view-identity diffing.
    val configOverrides = viewModel?.configOverrides ?: remember { mutableStateMapOf() }

    val progress = if (flow.steps.isNotEmpty()) {
        (currentIndex + 1).toFloat() / flow.steps.size
    } else 0f

    // Hook event tracking helper
    fun trackHookEvent(event: String, step: OnboardingStep, extra: Map<String, Any> = emptyMap()) {
        val props = mutableMapOf<String, Any>(
            "flow_id" to flow.id,
            "step_id" to step.id,
        )
        props.putAll(extra)
        eventTracker?.track(event, props)
    }

    // SPEC-419 STEP-2 — flow-host half of the element-interaction wiring. Awaits the host delegate's
    // onElementInteraction, then folds the ElementInteractionResult into an AppliedInteraction the STEP
    // scope applies (inputValues + fieldConfigOverrides + advance). No advance logic here — the flow host
    // can't see the step's blocks to validate; the step scope routes any advance through the required-
    // field-gated handleAction("next"). Mirrors iOS OnboardingFlowHost.performInteraction.
    suspend fun performInteraction(
        blockId: String,
        action: String,
        value: String?,
        inputValues: Map<String, Any>,
    ): AppliedInteraction? {
        if (interactionInFlight) return null
        interactionInFlight = true
        try {
            val stepId = if (currentIndex < flow.steps.size) flow.steps[currentIndex].id else ""
            val result = delegate?.onElementInteraction(
                flowId = flow.id,
                stepId = stepId,
                blockId = blockId,
                action = action,
                value = value,
                inputValues = inputValues,
            ) ?: return null
            return applyInteractionResult(result, inputValues)
        } finally {
            interactionInFlight = false
        }
    }

    // SPEC-070-A finalization OB-7 — image preload at flow init.
    // iOS `OnboardingRenderer.swift:130-139` walks every step's content_blocks
    // recursively, collects every image_url referenced, and pre-warms
    // NSURLCache + UIImage decode so the first paint of each step doesn't
    // do a synchronous network fetch. Without this, every step renders
    // blank-image placeholders that snap to loaded mid-scroll.
    //
    // The Android `ImagePreloader` (core/ImagePreloader.kt) was already
    // shipped at SPEC-070-A G.13 but never CALLED. Wire it here once at
    // flow start; Coil dedupes its own work so a second call (e.g. from
    // a hook-driven config refresh) is safe.
    val preloaderContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(flow.id) {
        try {
            val urls = collectFlowImageURLs(flow)
            if (urls.isNotEmpty()) {
                ai.appdna.sdk.core.ImagePreloader(preloaderContext).prefetch(urls)
            }
        } catch (e: Throwable) {
            // Non-fatal — image preload is purely a UX speedup.
            ai.appdna.sdk.Log.warning { "Image preload failed: ${e.message}" }
        }
    }

    // SPEC-083: Before-render hook + step viewed tracking
    // SPEC-401-A R58 (Lens B P2) — gate by VM-scoped lastViewedStepIndex so
    // onBeforeStepRender + onStepViewed only fire on genuine currentIndex
    // changes, NOT on every Activity recreation (rotation, locale change,
    // dark/light toggle, process-death restore). Without this, hosts whose
    // onBeforeStepRender does network work pay duplicate billing per
    // rotation; ETL sees inflated `onboarding_step_viewed` counts. Mirrors
    // iOS `OnboardingRenderer.swift:421-438` `.onAppear` semantics on a
    // view tagged with `.id(currentIndex)` — fires once per index change
    // (including back-nav re-visits via id swap) but NOT on rotation.
    LaunchedEffect(currentIndex) {
        if (currentIndex < flow.steps.size && currentIndex != viewModel?.lastViewedStepIndex) {
            viewModel?.lastViewedStepIndex = currentIndex
            val step = flow.steps[currentIndex]
            delegate?.let { d ->
                // 🔴 BOUNDED. This call used to have no timeout at all, which was mostly harmless
                // while few hosts did real work in the hook — SPEC-448 §B asks every host to FETCH
                // DATA here, so a customer awaiting their own backend on a bad connection, with no
                // timeout of their own, would hang the step indefinitely and it would look like our
                // bug. The SDK's own server hooks have always bounded themselves; this now matches.
                // 3s rather than the hook default of 10s: a hook is a background call, this one
                // stands between the user and a visible screen.
                val override = kotlinx.coroutines.withTimeoutOrNull(3_000L) {
                    d.onBeforeStepRender(
                        flowId = flow.id,
                        stepId = step.id,
                        stepIndex = currentIndex,
                        stepType = step.type.value,
                        responses = responses.toMap()
                    )
                }
                if (override != null) {
                    // A late reply is applied only if the user is STILL on this step. Dropping it
                    // into a step they have left would rewrite a screen they are no longer looking
                    // at. On expiry the flow proceeds with cache then static options.
                    if (currentIndex < flow.steps.size && flow.steps[currentIndex].id == step.id) {
                        configOverrides[step.id] = override
                    }
                }
            }
            onStepViewed(step.id, currentIndex)
        }
    }

    // Auto-dismiss error after 5 seconds
    LaunchedEffect(showError) {
        if (showError) {
            kotlinx.coroutines.delay(5000)
            showError = false
            errorMessage = null
        }
    }

    // SPEC-070-A C.8 — auto-dismiss success after 4 seconds (mirrors iOS
    // `OnboardingRenderer.swift:351-388` 4000ms LaunchedEffect timer).
    LaunchedEffect(showSuccess) {
        if (showSuccess) {
            kotlinx.coroutines.delay(4000)
            showSuccess = false
            successMessage = null
        }
    }

    // Helper functions

    // SPEC-401-A R56 (Lens B P1) — forward reference to `advanceOrComplete`
    // so `presentPaywallTriggerNode.routeOutcome` can re-run next-step
    // rules when a paywall outcome target is unknown / typo'd. Direct
    // forward reference to a local function is illegal in Kotlin (advance
    // is declared after presentPaywallTriggerNode); a lambda var bridges
    // the gap. Assigned right after `advanceOrComplete` is defined below.
    // Matches iOS `renderer.navigateToTarget(chosen)` →
    // `advanceOrComplete()` fallthrough at OnboardingRenderer.swift:1236-1238.
    var advanceOrCompleteRef: (() -> Unit)? = null

    // SPEC-070-A finalization OB-5 — present a paywall_trigger node by id.
    // Mirrors iOS OnboardingRenderer.swift:1186-1272 (`presentPaywallTrigger`)
    // + `routeOutcome`. Extracted as a local function so it can be invoked
    // from BOTH the rule-evaluation `RuleTarget.PaywallTrigger` branch AND
    // recursively from within `routeOutcome`'s "default" branch when an
    // outcome target points at another paywall_trigger node (winback / soft
    // → hard chain). iOS handles chained triggers via `navigateToTarget`;
    // this Android local function mirrors the same routing surface.
    //
    // `depth` is a recursion guard — chained paywalls deeper than 8 levels
    // are almost certainly a config loop; collapse to flow-complete.
    fun presentPaywallTriggerNode(triggerNodeId: String, depth: Int = 0) {
        if (depth > 8) {
            ai.appdna.sdk.Log.warning(
                "OnboardingPaywallBridge: chained paywall depth >8, completing flow"
            )
            @Suppress("UNCHECKED_CAST")
            onFlowCompleted(responses.toMap() as Map<String, Any>)
            return
        }
        // Resolve trigger data — mirrors iOS resolvePaywallTriggerData:
        // prefer graph_nodes, fall back to graph_layout.nodes for back-compat.
        @Suppress("UNCHECKED_CAST")
        val triggerData: Map<String, Any?>? = run {
            val fromNodes = flow.graph_nodes?.get(triggerNodeId) as? Map<String, Any?>
            if (fromNodes != null) return@run fromNodes
            val nodes = flow.graph_layout?.get("nodes") as? List<*>
            val match = nodes?.firstOrNull {
                ((it as? Map<*, *>)?.get("id") as? String) == triggerNodeId
            } as? Map<*, *>
            (match?.get("data") as? Map<String, Any?>)
        }
        // Resolve paywall id + the SPEC-401/403/404 skip gate. Both now live in the pure
        // [PaywallTriggerResolver] so they can be exercised without an Activity, Play Billing
        // or the event tracker; this closure only performs what the outcome describes.
        val runtimeLocked = AppDNA.runtimeLock != null
        val decision = PaywallTriggerResolver.decide(
            triggerData = triggerData,
            hasActiveSubscription = !runtimeLocked && AppDNA.billing.hasActiveSubscription(),
            runtimeLocked = runtimeLocked,
        )
        val paywallId: String = when (decision) {
            is PaywallTriggerResolver.PaywallTriggerOutcome.CompleteFlow -> {
                Log.warning("Onboarding paywall_trigger node $triggerNodeId has no paywall_id; completing flow.")
                @Suppress("UNCHECKED_CAST")
                onFlowCompleted(responses.toMap() as Map<String, Any>)
                return
            }
            is PaywallTriggerResolver.PaywallTriggerOutcome.Present -> decision.paywallId
            is PaywallTriggerResolver.PaywallTriggerOutcome.Skip -> decision.paywallId
        }
        val onSuccessTarget = (triggerData?.get("on_success_target") as? String)?.takeIf { it.isNotBlank() }
        val onFailTarget = (triggerData?.get("on_fail_target") as? String)?.takeIf { it.isNotBlank() }
        val onDismissTarget = (triggerData?.get("on_dismiss_target") as? String)?.takeIf { it.isNotBlank() }
        val legacyDismiss = triggerData?.get("on_dismiss") as? String ?: "continue"
        val edgeTarget = triggerData?.get("next_target") as? String
        // Detect whether a target id refers to a paywall_trigger node.
        // Mirrors iOS `graphNodeType(for:)` plus the `paywall_trigger_` prefix
        // fallback at OnboardingRenderer.swift:1166-1184. Used by routeOutcome
        // below to detect chained paywall_trigger destinations.
        fun isPaywallTriggerTarget(target: String): Boolean {
            if (target.startsWith("paywall_trigger_")) return true
            val nodeData = flow.graph_nodes?.get(target) as? Map<*, *>
            return (nodeData?.get("type") as? String) == "paywall_trigger"
        }
        // Outcome router — mirrors iOS `routeOutcome` closure at lines
        // 1207-1245. Recurses into `presentPaywallTriggerNode` when the
        // chosen target is itself a paywall_trigger (winback chain).
        val routeOutcome: (String?, String, String) -> Unit = { configured, defaultBehavior, reason ->
            val chosen = configured ?: defaultBehavior
            when (chosen) {
                "stay" -> {
                    eventTracker?.track(
                        "onboarding_paywall_stay",
                        mapOf(
                            "flow_id" to flow.id,
                            "paywall_id" to paywallId,
                            "reason" to reason,
                        ),
                    )
                }
                "complete_flow", "" -> {
                    eventTracker?.track(
                        "onboarding_completed",
                        mapOf(
                            "flow_id" to flow.id,
                            "paywall_id" to paywallId,
                            "completed_via" to reason,
                        ),
                    )
                    @Suppress("UNCHECKED_CAST")
                    onFlowCompleted(responses.toMap() as Map<String, Any>)
                }
                "continue" -> {
                    if (!edgeTarget.isNullOrBlank()) {
                        if (isPaywallTriggerTarget(edgeTarget)) {
                            presentPaywallTriggerNode(edgeTarget, depth + 1)
                        } else if (edgeTarget.startsWith("end_") ||
                            ((flow.graph_nodes?.get(edgeTarget) as? Map<*, *>)?.get("type") as? String) == "end") {
                            // SPEC-401-A R59 (Lens B P1) — match iOS
                            // navigateToTarget(edge) `end_*` / nodeType=="end"
                            // detection (OnboardingRenderer.swift:1169-1172).
                            // Without this, paywall outcomes whose continue
                            // edge targets an end node fell into the step
                            // lookup, missed, and re-ran the SAME step's
                            // rules — re-firing the paywall_trigger up to 8
                            // times before depth>8 force-completed with
                            // wrong analytics.
                            eventTracker?.track(
                                "onboarding_completed",
                                mapOf(
                                    "flow_id" to flow.id,
                                    "paywall_id" to paywallId,
                                    "completed_via" to reason,
                                ),
                            )
                            @Suppress("UNCHECKED_CAST")
                            onFlowCompleted(responses.toMap() as Map<String, Any>)
                        } else {
                            val tIdx = flow.steps.indexOfFirst { it.id == edgeTarget }
                            if (tIdx >= 0) {
                                // SPEC-401-A R3 — DO NOT push history here.
                                // advanceOrComplete already pushed the leaving
                                // step before invoking presentPaywallTriggerNode
                                // (line 711). Pushing again caused back-nav to
                                // consume two history entries to move one step
                                // back.
                                currentIndex = tIdx
                            }
                            else {
                                // SPEC-401-A R56 (Lens B P1) — re-run
                                // current step's next_step_rules via the
                                // forward-ref lambda set up below
                                // advanceOrComplete. Matches iOS
                                // navigateToTarget(edge) → advanceOrComplete
                                // fallthrough at OnboardingRenderer.swift
                                // :1166-1184.
                                advanceOrCompleteRef?.invoke()
                                    ?: run {
                                        if (currentIndex + 1 < flow.steps.size) {
                                            currentIndex++
                                        } else {
                                            @Suppress("UNCHECKED_CAST")
                                            onFlowCompleted(responses.toMap() as Map<String, Any>)
                                        }
                                    }
                            }
                        }
                    } else {
                        eventTracker?.track(
                            "onboarding_completed",
                            mapOf(
                                "flow_id" to flow.id,
                                "paywall_id" to paywallId,
                                "completed_via" to reason,
                            ),
                        )
                        @Suppress("UNCHECKED_CAST")
                        onFlowCompleted(responses.toMap() as Map<String, Any>)
                    }
                }
                else -> {
                    // Detect chained paywall_trigger or end node first
                    // (iOS navigateToTarget at OnboardingRenderer.swift:1166).
                    if (isPaywallTriggerTarget(chosen)) {
                        presentPaywallTriggerNode(chosen, depth + 1)
                    } else if (chosen.startsWith("end_") ||
                        ((flow.graph_nodes?.get(chosen) as? Map<*, *>)?.get("type") as? String) == "end") {
                        @Suppress("UNCHECKED_CAST")
                        onFlowCompleted(responses.toMap() as Map<String, Any>)
                    } else {
                        // Treat as a step ID — navigate.
                        val tIdx = flow.steps.indexOfFirst { it.id == chosen }
                        if (tIdx >= 0) {
                            // SPEC-401-A R3 — DO NOT push history here.
                            // advanceOrComplete already pushed the leaving
                            // step (line 711) before invoking
                            // presentPaywallTriggerNode. Pushing again
                            // caused double-pop on back-nav.
                            currentIndex = tIdx
                        } else {
                            // SPEC-401-A R56 (Lens B P1) — re-run current
                            // step's next_step_rules via the forward-ref
                            // lambda set up below advanceOrComplete.
                            // Matches iOS navigateToTarget(chosen) →
                            // advanceOrComplete fallthrough at
                            // OnboardingRenderer.swift:1180-1184. Was a
                            // simplified linear `currentIndex++` that
                            // skipped conditional logic on typo'd outcome
                            // targets.
                            advanceOrCompleteRef?.invoke()
                                ?: run {
                                    if (currentIndex + 1 < flow.steps.size) {
                                        currentIndex++
                                    } else {
                                        @Suppress("UNCHECKED_CAST")
                                        onFlowCompleted(responses.toMap() as Map<String, Any>)
                                    }
                                }
                        }
                    }
                }
            }
        }
        val legacyDismissDefault: String = when (legacyDismiss) {
            "block", "skip_to_end" -> "complete_flow"
            "continue" -> "continue"
            else -> "continue"
        }

        // SPEC-401 Fix 1A (already-subscribed) + SPEC-404 (runtime lock) skip gates. The decision
        // was taken above by [PaywallTriggerResolver]; both branches auto-skip via the SPEC-403
        // resolver chain (on_subscribed_skip_target → on_success_target → legacy "continue" edge)
        // and are told apart in analytics by `reason`.
        if (decision is PaywallTriggerResolver.PaywallTriggerOutcome.Skip) {
            eventTracker?.track(
                "onboarding_paywall_skip",
                mapOf(
                    "flow_id" to flow.id,
                    "paywall_id" to paywallId,
                    "reason" to decision.reason,
                ),
            )
            routeOutcome(decision.target, decision.defaultBehavior, decision.reason)
            return
        }

        val bridge = OnboardingPaywallBridge(
            onPurchased = { routeOutcome(onSuccessTarget, "continue", "paywall_purchased") },
            onFailed = { routeOutcome(onFailTarget, "stay", "paywall_payment_failed") },
            onDismissedWithoutPurchase = {
                routeOutcome(onDismissTarget, legacyDismissDefault, "paywall_dismissed")
            },
        )
        val activity = activityCtx as? android.app.Activity
        if (activity != null) {
            // SPEC-401-A — 100ms delay before presenting paywall mirrors
            // iOS Task.sleep(100ms) at OnboardingRenderer.swift:1280 so
            // the host fade-out cadence is preserved. Android previously
            // called presentPaywall immediately producing a snappier-but-
            // jarring transition.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // Use the static AppDNA.presentPaywall — it accepts a per-call
                // listener (AppDNA.paywall.present instance method only re-uses
                // the global listener slot). The bridge then forwards every
                // delegate event to the global host listener via
                // AppDNA.paywall.listener (OnboardingPaywallBridge.forwardOnMain).
                AppDNA.presentPaywall(
                    activity = activity,
                    id = paywallId,
                    context = null,
                    listener = bridge,
                )
            }, 100)
        } else {
            ai.appdna.sdk.Log.warning(
                "OnboardingPaywallBridge: no Activity context; falling back to legacy __paywall_trigger marker."
            )
            val merged = responses.toMutableMap()
            merged["__paywall_trigger"] = paywallId
            @Suppress("UNCHECKED_CAST")
            onFlowCompleted(merged.toMap() as Map<String, Any>)
        }
    }

    /**
     * A step submission whose completion event is waiting on the hook's verdict.
     *
     * Null unless a hook is in flight. [applyOutcome] fires it if — and only if — the outcome actually
     * navigates away from the step, and clears it otherwise so a blocked attempt cannot leak into the
     * next one. Deliberately a plain `var`, not Compose state: nothing renders from it, and a
     * recomposition must not resurrect a consumed completion.
     */
    var pendingStepCompletion: Triple<String, Int, Map<String, Any>?>? = null

    // SPEC-070-B — the advance/skip/merge/hook-result logic moved verbatim into the pure
    // [OnboardingAdvance] state machine (it was unreachable from any test while it lived in
    // these closures). This is the only place that turns its description of what should happen
    // into the Compose-scoped side effects: haptic, history push, analytics, navigation.
    fun applyOutcome(outcome: OnboardingAdvance.AdvanceOutcome) {
        // 🔴 THE STEP COMPLETES WHEN THE FLOW LEAVES IT — NOT WHEN THE USER TAPS THE BUTTON.
        //
        // `Stay` means the hook said no (wrong password, failed sign-in, validation error): the user is
        // still looking at the same step and nothing completed. Emitting only on a navigating outcome is
        // what makes `onboarding_step_completed` mean what its name says.
        //
        // Cleared either way — a blocked attempt must not leak into the next one, and a user who fixes
        // their password and succeeds emits exactly ONE completion.
        pendingStepCompletion?.let { (stepId, index, data) ->
            pendingStepCompletion = null
            if (outcome.navigation.completesStep) {
                onStepCompleted(stepId, index, data)
            }
        }

        // SPEC-070-A J.2 — `on_step_advance` haptic fires before any analytics or navigation,
        // and only on a natural advance (mirrors iOS HapticController in advanceStep()).
        if (outcome.hapticOnAdvance) {
            HapticEngine.triggerIfEnabled(hostView, hapticConfig?.triggers?.on_step_advance, hapticConfig)
        }
        navigationHistory.addAll(outcome.historyPush)
        if (outcome.responses != responses) {
            responses.clear()
            responses.putAll(outcome.responses)
        }
        // SPEC-088: Persist computed data for cross-module access.
        outcome.computedData?.let { ai.appdna.sdk.core.SessionDataStore.instance?.mergeComputedData(it) }
        outcome.events.forEach { eventTracker?.track(it.name, it.props) }
        when (val banner = outcome.banner) {
            is OnboardingAdvance.Banner.Error -> {
                errorMessage = banner.message
                showError = true
            }
            is OnboardingAdvance.Banner.Success -> {
                successMessage = banner.message
                showSuccess = true
            }
            null -> Unit
        }
        when (val nav = outcome.navigation) {
            is OnboardingAdvance.Navigation.GoToIndex -> currentIndex = nav.index
            is OnboardingAdvance.Navigation.CompleteFlow -> onFlowCompleted(nav.responses)
            is OnboardingAdvance.Navigation.PresentPaywallTrigger -> presentPaywallTriggerNode(nav.nodeId)
            is OnboardingAdvance.Navigation.Stay -> Unit
        }
    }

    fun advanceOrComplete() {
        applyOutcome(
            OnboardingAdvance.advance(
                flow = flow,
                currentIndex = currentIndex,
                responses = responses.toMap(),
                navigationHistory = navigationHistory.toList(),
            )
        )
    }

    // SPEC-401-A R56 (Lens B P1) — bind the forward-ref now that
    // `advanceOrComplete` exists. Read by `presentPaywallTriggerNode
    // .routeOutcome` unknown-target fallback above.
    advanceOrCompleteRef = { advanceOrComplete() }

    fun handleHookResult(result: StepAdvanceResult, step: OnboardingStep) {
        applyOutcome(
            OnboardingAdvance.apply(
                flow = flow,
                currentIndex = currentIndex,
                responses = responses.toMap(),
                result = result,
                navigationHistory = navigationHistory.toList(),
                step = step,
            )
        )
    }


    // The five wire strings now live in OnboardingAdvance.kt so they can be asserted without a
    // Compose tree (the shared fixtures pin them). Same values, one owner.
    fun resultName(result: StepAdvanceResult): String = stepAdvanceResultName(result)

    val currentStep = if (currentIndex < flow.steps.size) flow.steps[currentIndex] else null

    // SPEC-401-A R19 — adapt status bar icon tint to onboarding background
    // luminance. With edge-to-edge layout, the system status bar paints over
    // our content; without setting `isAppearanceLightStatusBars` based on
    // background luminance, white system icons are invisible on a light
    // onboarding background and vice versa. iOS handles this automatically
    // via `preferredStatusBarStyle` inheritance from the parent VC.
    val bgColorForStatusBar = MaterialTheme.colorScheme.background
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window
        if (window != null) {
            val luminance = 0.2126 * bgColorForStatusBar.red +
                0.7152 * bgColorForStatusBar.green +
                0.0722 * bgColorForStatusBar.blue
            val controller = androidx.core.view.WindowInsetsControllerCompat(window, view)
            controller.isAppearanceLightStatusBars = luminance > 0.5
            // SPEC-401-A R20 — also adapt the bottom nav-bar icon tint.
            // Same edge-to-edge problem as the status bar: without this, the
            // host app's nav-bar tint persists into onboarding and 3-button
            // nav icons are invisible against the onboarding background.
            controller.isAppearanceLightNavigationBars = luminance > 0.5
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // QA-R1 — full-bleed step background painted UNDER the
        // status/nav bars. Mirrors iOS `OnboardingRenderer.swift:112-116`
        // `.background(stepFullScreenBackground.ignoresSafeArea())`.
        //
        // Old version painted `MaterialTheme.colorScheme.background` AFTER
        // `safeDrawingPadding()`, confining the paint to the safe-area
        // rectangle. On hosts whose Activity theme used `lightColorScheme()`
        // (most apps — the default) the status-bar inset region showed the
        // host's `#FFFBFE` surface tint as a white/pink strip above the
        // step's authored dark gradient. The back-arrow IconButton inside
        // that band rendered `#6B7280` grey on white → "invisible back
        // button" QA report (R2). With the background now full-bleed, the
        // step's gradient extends edge-to-edge and the grey back glyph
        // contrasts against it as it does on iOS.
        StepFullScreenBackground(currentStep = currentStep)

        // SPEC-419 — top status-bar scrim, matching iOS. iOS darkens the status-bar region on
        // dark/photo/gradient steps for legibility; without a matching scrim, Android's full-bleed
        // bg shows a LIGHTER band at the very top (the gradient's #213359 stop, or the photo) — the
        // user-reported "top a different colour than the centre". A dark→transparent vertical
        // gradient over the top ~140dp reproduces it. Painted OVER the bg, UNDER the safe-area
        // content so chrome/text stay legible.
        // SPEC-419 pass-13 correctness — GATE the scrim to dark/photo/gradient backgrounds. Painting
        // it UNCONDITIONALLY smudged a dark band over light `color`-type steps (white/pastel) where
        // iOS shows none. Skip for light solid-color (and light default/theme) backgrounds.
        fun lumaOf(c: Color) = 0.2126 * c.red + 0.7152 * c.green + 0.0722 * c.blue
        val stepBg = currentStep?.config?.background
        val showTopScrim = when (stepBg?.type) {
            "gradient", "image" -> true
            "color" -> stepBg.color?.let { lumaOf(ai.appdna.sdk.core.StyleEngine.parseColor(it)) < 0.5 } ?: false
            null -> {
                val base = currentStep?.config?.chat_config?.style?.background_color
                    ?.let { ai.appdna.sdk.core.StyleEngine.parseColor(it) }
                    ?: if (currentStep?.type == OnboardingStep.StepType.INTERACTIVE_CHAT) Color(0xFF0F172A)
                       else bgColorForStatusBar
                lumaOf(base) < 0.5
            }
            else -> lumaOf(bgColorForStatusBar) < 0.5 // lottie/rive → neutral theme base
        }
        if (showTopScrim) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
                        ),
                    ),
            )
        }

        // Safe-area content layer — sits on top of the full-bleed
        // background. `imePadding()` keeps keyboards from cropping inputs;
        // `safeDrawingPadding()` insets for status + nav bars so chrome
        // (progress, back/close, step body) avoids the system gutters
        // while the background underneath stays full-bleed.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .safeDrawingPadding()
        ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Progress bar (Gap 9: custom progress_color/progress_track_color)
            // hide_progress per-step: hidden on this step but still counts in total
            if (flow.settings.show_progress && currentStep?.hide_progress != true) {
                // SPEC-070-A audit Round 2-restart attempt 2 F2: progress
                // color resolution mirrors iOS OnboardingRenderer.swift:174-186
                // — step.config.progress_color > step.config.element_style?
                // .background?.color > flow.settings.progress_color > default.
                val stepProgressColor = currentStep?.config?.progress_color
                    ?: currentStep?.config?.element_style?.background?.color
                // SPEC-401-A R53 (Lens C R53 #2, P2) — default to iOS-canonical
                // indigo `#6366F1` (OnboardingRenderer.swift:186) instead of
                // MaterialTheme.colorScheme.primary which on darkColorScheme()
                // is M3 default purple (`#D0BCFF`) and on lightColorScheme()
                // is `#6750A4`. Same flow with no author override now matches
                // iOS on both light + dark mode.
                val progressColor = (stepProgressColor ?: flow.settings.progress_color)?.let {
                    ai.appdna.sdk.core.StyleEngine.parseColor(it)
                } ?: ai.appdna.sdk.AppDNA.brandAccentColor()
                val progressTrackColor = flow.settings.progress_track_color?.let {
                    ai.appdna.sdk.core.StyleEngine.parseColor(it)
                } ?: Color.Gray.copy(alpha = 0.2f)
                // EPIC-2 — dynamic color flash on step-advance: briefly animate the fill to flash_color then back.
                val progressFlashColor = flow.settings.progress_flash_color?.let { ai.appdna.sdk.core.StyleEngine.parseColor(it) }
                var prevFlashIndex by remember { mutableStateOf(currentIndex) }
                var progressFlashing by remember { mutableStateOf(false) }
                LaunchedEffect(currentIndex) {
                    if (currentIndex != prevFlashIndex && progressFlashColor != null) {
                        progressFlashing = true
                        kotlinx.coroutines.delay(450)
                        progressFlashing = false
                    }
                    prevFlashIndex = currentIndex
                }
                val flashedProgressColor by animateColorAsState(
                    targetValue = if (progressFlashing && progressFlashColor != null) progressFlashColor else progressColor,
                    animationSpec = tween(durationMillis = 350),
                    label = "progressFlash",
                )
                // SPEC-070-A finalization Phase B — progress_style branches.
                // Mirrors iOS OnboardingRenderer.swift progress style switch:
                //   "dots" — N circles, filled to currentIndex
                //   "segmented_bar" — N segments, filled to currentIndex
                //   "fraction" — Text "i / N"
                //   "none" — render nothing (still in show_progress branch)
                //   default / "continuous_bar" — LinearProgressIndicator (existing)
                val totalSteps = flow.steps.size.coerceAtLeast(1)
                val current = (currentIndex + 1).coerceIn(1, totalSteps)
                // SPEC-401-A R36 (Lens C #3) — animate progress style transitions
                // to mirror iOS .animation(.easeInOut(duration: 0.2-0.3)) on
                // OnboardingRenderer.swift:199, 211, 235. Was snapping per
                // step → visible flicker on each `next`.
                val animatedProgress by animateFloatAsState(
                    targetValue = progress,
                    animationSpec = tween(durationMillis = 300),
                    label = "progressAnim",
                )
                // EPIC-2 — thin sizing (custom height) + multiple colors at once (gradient), flow-level progress.
                val progressHeight = (flow.settings.progress_height ?: 4.0).dp
                val gradientCols = flow.settings.progress_gradient_colors?.takeIf { it.size >= 2 }
                    ?.map { ai.appdna.sdk.core.StyleEngine.parseColor(it) }
                // EPIC-2 — optional "Skip" link beside the progress (Flo). When set the bar shrinks to make
                // room for the Skip on the trailing side; when null the Box fills the row (no visual change).
                val progressSkipLabel = flow.settings.progress_skip_label
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                  Box(Modifier.weight(1f)) {
                when (flow.settings.progress_style?.lowercase()) {
                    "none" -> { /* explicit suppress */ }
                    "dots" -> {
                        Row(
                            // SPEC-401-A R55 (Lens A R55 #12, P2) — 8→16dp matching
                            // iOS OnboardingRenderer.swift:203 .padding(.horizontal)
                            // (system default 16pt).
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        ) {
                            for (i in 1..totalSteps) {
                                val filled = i <= current
                                // SPEC-401-A R39 — drop dot-size animation added in
                                // R36. iOS OnboardingRenderer.swift:198 uses fixed
                                // .frame(width: 8, height: 8) for every dot — only
                                // fill color animates. Was causing visible "pop"
                                // larger drift from iOS.
                                val animatedColor by animateColorAsState(
                                    targetValue = if (filled) flashedProgressColor else progressTrackColor,
                                    animationSpec = tween(durationMillis = 200),
                                    label = "dotColor",
                                )
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(animatedColor),
                                )
                            }
                        }
                    }
                    "segmented_bar" -> {
                        Row(
                            // SPEC-401-A R55 (Lens A R55 #12, P2) — 8→16dp matching
                            // iOS OnboardingRenderer.swift:215 .padding(.horizontal).
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (i in 1..totalSteps) {
                                val filled = i <= current
                                val animatedColor by animateColorAsState(
                                    targetValue = if (filled) flashedProgressColor else progressTrackColor,
                                    animationSpec = tween(durationMillis = 200),
                                    label = "segColor",
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(progressHeight)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(animatedColor),
                                )
                            }
                        }
                    }
                    "fraction" -> {
                        // SPEC-401-A R33 — match iOS OnboardingRenderer.swift:218
                        // `"\(current+1)/\(total)"` (no spaces) at
                        // `.font(.caption.monospacedDigit())` ~12pt monospaced.
                        // Android was rendering `"3 / 10"` at 14sp non-mono so
                        // digits jittered on each tick.
                        Text(
                            text = "$current/$totalSteps",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            // SPEC-401-A R54 (Lens C R54 #1, P2) — fraction
                            // digits inherit progressColor matching iOS
                            // OnboardingRenderer.swift:217-221
                            // `Text("\(current+1)/\(total)").foregroundColor(fillColor)`.
                            // Was MaterialTheme.colorScheme.onBackground (near
                            // black/white), now indigo `#6366F1` (or authored
                            // override) just like the dots/segments use.
                            color = progressColor,
                        )
                    }
                    else -> {
                        // SPEC-401-A R22 — Material3 1.2 deprecated the
                        // `progress: Float` overload in favor of the lambda
                        // form. Pre-emptive switch silences runtime warnings
                        // and unblocks the next BOM bump.
                        // EPIC-2 — custom continuous bar (honors any height; the Material
                        // LinearProgressIndicator floored ~4dp so flows couldn't go thinner) +
                        // optional multi-color gradient. Extracted so it's snapshot-testable.
                        ContinuousProgressBar(
                            progress = animatedProgress,
                            color = flashedProgressColor,
                            trackColor = progressTrackColor,
                            height = progressHeight,
                            gradientColors = gradientCols,
                        )
                    }
                }
                  } // end weighted progress Box
                  if (progressSkipLabel != null) {
                      Text(
                          text = progressSkipLabel,
                          modifier = Modifier
                              .padding(start = 12.dp, end = 16.dp)
                              .clickable(enabled = !isProcessing) { advanceOrComplete() },
                          color = progressColor,
                          fontSize = 14.sp,
                          fontWeight = FontWeight.Medium,
                      )
                  }
                } // end progress+skip Row
            }

            // SPEC-401-A — gate entire nav-bar Row when both back +
            // dismiss are hidden. iOS doesn't render the empty VStack
            // child in that case (OnboardingRenderer.swift:46-49); on
            // Android the IconButton spacers reserve ~48dp regardless,
            // so authored "no-chrome" flows had a phantom band at top.
            // Per-step `hide_back` (authored in the step's layout) suppresses the back affordance on
            // this step — mirrors `hide_progress`. iOS: OnboardingRenderer.swift currentStepHidesBack.
            val navBackVisible = flow.settings.allow_back && navigationHistory.isNotEmpty() &&
                currentStep?.hide_back != true
            val navDismissAllowed = flow.settings.dismiss_allowed ?: true
            // EPIC-2 — back-arrow⇄X switch: on the first/no-history step show the dismiss "✕" in the
            // LEADING slot (Duolingo pattern) instead of an empty spacer; the trailing X is then hidden.
            val leadingIsClose = !navBackVisible && navDismissAllowed &&
                (flow.settings.back_button_style?.close_on_first == true)
            if (navBackVisible || navDismissAllowed) {
            // Navigation bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // SPEC-070-A finalization B4 P1 — gate the back arrow on
                // navigationHistory.isNotEmpty() rather than currentIndex>0.
                // Mirrors iOS OnboardingRenderer.swift:257. After a skipTo
                // jump that clears the history, currentIndex can be >0 with
                // empty history; the old gate showed a back arrow that
                // popped to a step the user never visited.
                // SPEC-070-A finalization B4 P1 — wire back_button_style
                // (icon_size + icon_color) and dismiss_allowed. iOS reads
                // these from OnboardingRenderer.swift:250-292.
                val bbStyle = flow.settings.back_button_style
                // SPEC-401-A — default 16sp matches iOS
                // OnboardingRenderer.swift:252 (16pt). Android previously
                // defaulted to 20sp making the back glyph noticeably
                // larger than iOS for the same flow config.
                val backIconSize = (bbStyle?.icon_size?.toFloat() ?: 16f).sp
                // SPEC-401-A R5 — default to iOS gray (#6B7280) instead
                // of Material onBackground (black in light theme).
                // Mirrors iOS OnboardingRenderer.swift:253.
                val backIconColor = bbStyle?.icon_color?.let { ai.appdna.sdk.core.StyleEngine.parseColor(it) }
                    ?: ai.appdna.sdk.core.StyleEngine.parseColor("#6B7280")
                if (flow.settings.allow_back && navigationHistory.isNotEmpty() && currentStep?.hide_back != true) {
                    val backCd = stringResource(R.string.appdna_a11y_onboarding_back)
                    IconButton(
                        onClick = {
                            // SPEC-070-A J.2 — back navigation reuses
                            // `on_step_advance` haptic style.
                            HapticEngine.triggerIfEnabled(hostView, hapticConfig?.triggers?.on_step_advance, hapticConfig)
                            // SPEC-401-A R3 — jump to the popped step's
                            // index, NOT currentIndex-1. iOS uses the popped
                            // index (OnboardingRenderer.swift:259-262) so
                            // non-linear flows (rule-skip, paywall continue,
                            // analytics→target) back-nav lands on the step
                            // the user actually came FROM. The old
                            // `currentIndex--` path landed on whatever step
                            // sat at index-1 even if the user had skipped
                            // over it. Android tracks step IDs (not Ints)
                            // so we look up the index by id; if the id no
                            // longer resolves (e.g. flow rebuilt), fall
                            // back to currentIndex-1.
                            val previousId = navigationHistory.lastOrNull()
                            val previousIndex = previousId
                                ?.let { id -> flow.steps.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
                                ?: maxOf(currentIndex - 1, 0)
                            // 🔴 Discard a completion waiting on an in-flight hook — going back means
                            // the user did NOT complete this step; the next applyOutcome would otherwise
                            // record a completion for the step they just abandoned. iOS does the same.
                            pendingStepCompletion = null
                            if (navigationHistory.isNotEmpty()) {
                                navigationHistory.removeAt(navigationHistory.lastIndex)
                            }
                            currentIndex = previousIndex
                        },
                        enabled = !isProcessing,
                        // SPEC-070-A J.11 — back arrow rendered as Text glyph
                        // (no semantic icon); attach contentDescription so
                        // TalkBack announces purpose.
                        modifier = Modifier.semantics { contentDescription = backCd },
                    ) {
                        NavGlyph(bbStyle?.icon ?: "\u2190", backIconColor, backIconSize)
                    }
                } else if (leadingIsClose) {
                    // EPIC-2 \u2014 back\u21c4X switch: dismiss glyph in the leading slot on the first step.
                    val leadCloseCd = stringResource(R.string.appdna_a11y_onboarding_close)
                    IconButton(
                        onClick = {
                            HapticEngine.triggerIfEnabled(hostView, hapticConfig?.triggers?.on_button_tap, hapticConfig)
                            // Same race as Back/Skip (see :1381): dismissing during the hook grace-window
                            // must discard a completion waiting on that hook — the in-flight hook can still
                            // resolve into applyOutcome and emit a phantom completion for the dismissed step.
                            pendingStepCompletion = null
                            if (currentIndex < flow.steps.size) {
                                onFlowDismissed(flow.steps[currentIndex].id, currentIndex)
                            }
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.semantics { contentDescription = leadCloseCd },
                    ) {
                        NavGlyph("\u2715", backIconColor, backIconSize)
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }

                // SPEC-070-A finalization B4 P1 — gate dismiss X on
                // `flow.settings.dismiss_allowed`. iOS suppresses dismiss
                // when set false (e.g. mandatory onboarding flows).
                if (!navDismissAllowed || leadingIsClose) {
                    Spacer(Modifier.size(48.dp))
                } else {
                val dismissCd = stringResource(R.string.appdna_a11y_onboarding_close)
                IconButton(
                    onClick = {
                        // SPEC-070-A J.2 — dismiss reuses on_button_tap haptic.
                        HapticEngine.triggerIfEnabled(hostView, hapticConfig?.triggers?.on_button_tap, hapticConfig)
                        // See leading-close above: clear a hook-pending completion before dismissing.
                        pendingStepCompletion = null
                        if (currentIndex < flow.steps.size) {
                            val step = flow.steps[currentIndex]
                            onFlowDismissed(step.id, currentIndex)
                        }
                    },
                    enabled = !isProcessing,
                    // SPEC-070-A J.11 — close X is a Text glyph with no
                    // semantic label by default.
                    modifier = Modifier.semantics { contentDescription = dismissCd },
                ) {
                    Text(
                        text = "\u2715",
                        // SPEC-401-A R5 \u2014 match iOS dismiss X font.system(size:14).
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
                } // end dismissAllowed
            }
            } // end SPEC-401-A navBarVisible gate

            // Step content with animated transitions
            if (currentIndex < flow.steps.size) {
                AnimatedContent<Int>(
                    targetState = currentIndex,
                    transitionSpec = {
                        // SPEC-401-A R8 — match iOS step transition: pure
                        // horizontal slide with `easeInOut(0.25s)` curve.
                        // Previous Compose default added cross-dissolve
                        // (fadeIn/fadeOut) and used a 300ms FastOutSlowIn
                        // tween, both visible side-by-side with iOS.
                        val spec = tween<IntOffset>(durationMillis = 250, easing = FastOutSlowInEasing)
                        slideInHorizontally(animationSpec = spec) { it } togetherWith
                            slideOutHorizontally(animationSpec = spec) { -it }
                    },
                    label = "step_transition",
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { stepIdx ->
                val step = flow.steps[stepIdx]
                val effectiveConfig = configOverrides[step.id]
                    ?.let { step.config.applyingOverride(it) }
                    ?: step.config

                OnboardingStepView(
                    step = step,
                    effectiveConfig = effectiveConfig,
                    flowId = flow.id,
                    // 🔴 The delegate the RENDERER holds — the same reference `onBeforeStepAdvance`
                    // is called on. The auth gate + permission callbacks downstream must read THIS,
                    // not the global `AppDNA.onboarding.listener`, or a native host that passed its
                    // delegate to `presentOnboarding(...)` gets a veto seam that works and an auth
                    // gate that thinks there is no delegate at all.
                    delegate = delegate,
                    // SPEC-419 STEP-2 — flow-host interaction seam threaded to the step scope.
                    performInteraction = ::performInteraction,
                    // SPEC-401-A R11 — pass accumulated responses (immutable
                    // snapshot of the host's SnapshotStateMap) so block-level
                    // `visibility_condition` operators evaluate against real
                    // user answers, not an empty map.
                    accumulatedResponses = responses.toMap(),
                    // SPEC-070-A finalization B4 P1 — when revisiting a step
                    // via back navigation, restore previously-entered field
                    // values. Mirrors iOS OnboardingStepRouter savedResponses
                    // (OnboardingRenderer.swift:1316,1448) which is threaded
                    // into FormStepView/BlockBasedStepView so inputValues
                    // pre-populate from `responses[step.id]`.
                    savedResponses = (responses[step.id] as? Map<*, *>)?.let { raw ->
                        @Suppress("UNCHECKED_CAST")
                        raw as Map<String, Any>
                    },
                    onNext = { data ->
                        // 🔴 THE PASSWORD GOES TO THE HOST — AND NOWHERE ELSE.
                        //
                        // Both sinks below used to take the RAW field map, which on a `login` /
                        // `register` / `change_password` step contains the password the user just
                        // typed: (1) `SessionDataStore` persists it to SharedPreferences in plaintext
                        // and exposes it to the `{{…}}` template namespace, and (2) `onStepCompleted`
                        // emits `onboarding_step_completed` with `selection_data` = this map verbatim,
                        // which is uploaded and lands in `raw.sdk_events`. Every login attempt shipped
                        // an end-user's plaintext password into the warehouse.
                        //
                        // The delegate still receives the credentials in full (`stepData` in
                        // `onBeforeStepAdvance`) — that is how the host signs the user in. See
                        // `AuthSecretRedactor`; iOS does the same, at the same two sinks.
                        val safeData = AuthSecretRedactor.redact(data, step)
                        if (safeData != null) {
                            responses[step.id] = safeData
                        }
                        // SPEC-087: Persist responses incrementally so TemplateEngine has fresh data for next step.
                        // SPEC-070-A finalization spec audit-4 — was unsafe
                        // `as Map<String, Map<String, Any>>` cast that
                        // throws ClassCastException whenever a step writes
                        // a primitive top-level value (very common). Drop
                        // the cast — `setOnboardingResponses` already takes
                        // `Map<String, Any>` (SessionDataStore.kt:70) so
                        // pass-through is type-safe.
                        ai.appdna.sdk.core.SessionDataStore.instance?.setOnboardingResponses(responses.toMap())

                        // 🔴 `onboarding_step_completed` USED TO FIRE HERE — BEFORE THE HOOK DECIDED.
                        //
                        // On a hook step the HOOK decides whether the step completes. A `login` step
                        // whose delegate answers `Block("Wrong password")` does NOT complete — the user
                        // stays exactly where they were. But the event had already gone out. Mistype a
                        // password three times and the funnel records FOUR completions of a step you
                        // never completed; the successful fourth attempt makes five.
                        //
                        // It corrupts the metric the product is sold on — step-completion and funnel
                        // conversion — and it over-counts worst at the credential step, the step users
                        // actually fail at. So the flows that convert worst look healthiest.
                        //
                        // The emit now happens in [applyOutcome], the single place a decision becomes
                        // navigation, and only when the flow ACTUALLY LEAVES the step. iOS does the
                        // same, at the same seam.
                        if (delegate != null || step.hook?.enabled == true) {
                            pendingStepCompletion = Triple(step.id, currentIndex, safeData)
                        }

                        // SPEC-083: Determine hook type — client delegate takes priority
                        if (delegate != null) {
                            // Client-side hook
                            // SPEC-401-A R11 — match iOS 300ms grace window
                            // (OnboardingRenderer.swift:483-503). iOS schedules a
                            // DispatchWorkItem to flip isProcessing=true after
                            // 300ms, cancelled if the hook returns first. Without
                            // this, every <50ms hook flashes the dimmer + spinner.
                            loadingText = step.hook?.loading_text ?: "Processing..."
                            val hookFinished = java.util.concurrent.atomic.AtomicBoolean(false)
                            val startTime = System.currentTimeMillis()
                            trackHookEvent("onboarding_hook_started", step, mapOf("hook_type" to "client"))

                            // Grace timer — only show spinner if hook hasn't returned within 300ms.
                            // SPEC-401-A R51 (Lens B #4 P3) — capture the timer Job so
                            // the hook completion can cancel it. Without cancellation, a
                            // bursty hook completion right at the 300ms boundary could
                            // race the timer's `if (!hookFinished.get())` check and
                            // leave a stuck spinner. iOS uses
                            // DispatchWorkItem.cancel() at OnboardingRenderer.swift:502.
                            val graceTimerJob = coroutineScope.launch {
                                kotlinx.coroutines.delay(300)
                                if (!hookFinished.get()) {
                                    isProcessing = true
                                }
                            }

                            coroutineScope.launch {
                                // SPEC-401-A R78 (Lens B P1) — wrap in
                                // NonCancellable so `onboarding_hook_completed`
                                // still fires (and handleHookResult cleanup
                                // runs) even when host backgrounds/dismisses
                                // the modal mid-await. Without this, scope-
                                // cancellation cancels the suspending delegate
                                // call and the started/completed event pair
                                // breaks. iOS unstructured `Task { … }` at
                                // OnboardingRenderer.swift:489-511 is
                                // independent of view lifetime — same effect.
                                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                    val result = delegate.onBeforeStepAdvance(
                                        flowId = flow.id,
                                        fromStepId = step.id,
                                        stepIndex = currentIndex,
                                        stepType = step.type.value,
                                        responses = responses.toMap(),
                                        stepData = data
                                    )
                                    hookFinished.set(true)
                                    graceTimerJob.cancel()
                                    val durationMs = System.currentTimeMillis() - startTime
                                    isProcessing = false
                                    trackHookEvent(ONBOARDING_HOOK_COMPLETED_EVENT, step, mapOf(
                                        "hook_type" to "client",
                                        "result" to resultName(result),
                                        "duration_ms" to durationMs,
                                    ))
                                    handleHookResult(result, step)
                                }
                            }
                        } else {
                            val hookConfig = step.hook?.takeIf { it.enabled }
                            if (hookConfig != null) {
                            // Server-side hook (P1)
                            loadingText = hookConfig.loading_text ?: "Processing..."
                            isProcessing = true
                            val startTime = System.currentTimeMillis()
                            trackHookEvent("onboarding_hook_started", step, mapOf(
                                "hook_type" to "server",
                                "webhook_url" to hookConfig.webhook_url,
                            ))

                            coroutineScope.launch {
                                // SPEC-401-A R78 (Lens B P1) — same
                                // NonCancellable wrap for server hook;
                                // ETL hook-duration percentiles depend on
                                // started/completed event pairing.
                                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                    val result = executeWebhook(
                                        flow = flow,
                                        step = step,
                                        data = data,
                                        responses = responses.toMap(),
                                        hookConfig = hookConfig,
                                        currentIndex = currentIndex,
                                        attempt = 0,
                                        onRetry = { attemptNum ->
                                            trackHookEvent("onboarding_hook_retry", step, mapOf(
                                                "attempt_number" to attemptNum
                                            ))
                                        },
                                        onError = { errorType, errorMsg ->
                                            trackHookEvent("onboarding_hook_error", step, mapOf(
                                                "hook_type" to "server",
                                                "error_type" to errorType,
                                                "error_message" to errorMsg,
                                            ))
                                        }
                                    )
                                    val durationMs = System.currentTimeMillis() - startTime
                                    isProcessing = false
                                    trackHookEvent(ONBOARDING_HOOK_COMPLETED_EVENT, step, mapOf(
                                        "hook_type" to "server",
                                        "result" to resultName(result),
                                        "duration_ms" to durationMs,
                                    ))
                                    handleHookResult(result, step)
                                }
                            }
                            } else {
                                // No hook — nothing can veto, so the step completes on submit.
                                onStepCompleted(step.id, currentIndex, safeData)
                                advanceOrComplete()
                            }
                        }
                    },
                    onSkip = {
                        // Skipping is not completing — discard any completion waiting on a hook.
                        pendingStepCompletion = null
                        onStepSkipped(step.id, currentIndex)
                        advanceOrComplete()
                    },
                    modifier = Modifier.fillMaxSize(),
                    currentStepIndex = currentIndex,
                    totalSteps = flow.steps.size,
                )
                }
            }
        }

        // SPEC-083: Error banner
        // SPEC-401-A R35 — wrap in AnimatedVisibility to mirror iOS
        // OnboardingRenderer.swift:80-83,318-347 transition + the success
        // banner sibling below. Was popping in/out instantly.
        AnimatedVisibility(
            visible = showError && errorMessage != null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(top = if (flow.settings.show_progress && currentStep?.hide_progress != true) 56.dp else 52.dp)
                    // SPEC-401-A R60 (Lens C P3 #3) — match iOS systemRed
                    // (#FF3B30 light) which is what SwiftUI `Color.red`
                    // resolves to at OnboardingRenderer.swift:337. Was
                    // Compose `Color.Red` (#FF0000 saturated red) which
                    // sits harsher than the iOS counterpart and never
                    // adapts for dark mode.
                    .background(Color(0xFFFF3B30), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = errorMessage ?: "",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .weight(1f)
                        // SPEC-401-A R37 (Lens C #4) — assertive a11y
                        // announce when the error banner mounts. iOS auto-
                        // announces SwiftUI Text inserted into the hierarchy.
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                )
                val errorDismissCd = stringResource(R.string.appdna_a11y_onboarding_dismiss_error)
                IconButton(
                    onClick = { showError = false; errorMessage = null },
                    // SPEC-070-A J.11 — error-banner close (Text glyph).
                    modifier = Modifier
                        .size(24.dp)
                        .semantics { contentDescription = errorDismissCd },
                ) {
                    Text("\u2715", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                }
            }
        }

        // SPEC-070-A C.8 \u2014 success banner (port of iOS v1.0.60 SPEC-083
        // amendment `OnboardingRenderer.swift:351-388`). Same scaffold slot
        // as the error banner above. Animated entry/exit.
        AnimatedVisibility(
            visible = showSuccess && successMessage != null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(top = if (flow.settings.show_progress && currentStep?.hide_progress != true) 56.dp else 52.dp)
                    .background(Color(0xFF2E9E51), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = successMessage ?: "",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .weight(1f)
                        // SPEC-401-A R37 (Lens C #4) — polite a11y announce
                        // for success banner mount (lower priority than error).
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                val successDismissCd = stringResource(R.string.appdna_a11y_onboarding_dismiss_success)
                IconButton(
                    onClick = { showSuccess = false; successMessage = null },
                    modifier = Modifier.size(24.dp),
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Filled.Close,
                        // SPEC-070-A J.11 — pull from string resource so RTL
                        // locales / hosts can override the a11y label.
                        contentDescription = successDismissCd,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // SPEC-083: Loading overlay
        if (isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    // SPEC-401-A R36 (Lens C #2) — consume pointer events so
                    // step CTAs underneath stop receiving taps during async
                    // webhook waits. iOS uses `.ignoresSafeArea()` on a
                    // hit-testable Color (OnboardingRenderer.swift:296-313).
                    // Compose Box.background paints but doesn't consume hits;
                    // users could double-fire `next`/`continue` during the
                    // 300ms-3s loading spinner.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                    // SPEC-401-A R37 (Lens C #3) — TalkBack announcement
                    // when overlay appears + traversal trap. iOS SwiftUI
                    // ProgressView is auto-announced by VoiceOver and modal
                    // overlays trap a11y focus. Compose needs explicit
                    // liveRegion + isTraversalGroup.
                    .semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = loadingText
                        isTraversalGroup = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f))
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            // SPEC-401-A R84 (Lens C F3) — 32→28dp matches
                            // iOS `ProgressView().scaleEffect(1.2)` ≈ 24pt × 1.2
                            // ≈ 29pt at OnboardingRenderer.swift:302-303.
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            loadingText,
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
        } // QA-R1 — close inner safe-area Box (paired with line ~1186)
    }
}

// MARK: - Server-side webhook execution (SPEC-083 P1)

@Suppress("UNCHECKED_CAST")
private suspend fun executeWebhook(
    flow: OnboardingFlowConfig,
    step: OnboardingStep,
    data: Map<String, Any>?,
    responses: Map<String, Any>,
    hookConfig: StepHookConfig,
    currentIndex: Int,
    attempt: Int,
    onRetry: (Int) -> Unit,
    onError: (String, String) -> Unit
): StepAdvanceResult = withContext(Dispatchers.IO) {
    try {
        val url = URL(hookConfig.webhook_url)
        val conn = url.openConnection() as? HttpURLConnection
            ?: return@withContext StepAdvanceResult.Block("Webhook URL must use HTTP(S)")
        // SPEC-070-A final audit pass C F1 — ensure socket + input stream are
        // released on every exit path. Without this, retry_count up to 3 ×
        // every step → keep-alive sockets accumulate until GC. Mirrors the
        // sibling pattern in ChatStepComposable.fireWebhook.
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = hookConfig.timeout_ms
            conn.readTimeout = hookConfig.timeout_ms
            conn.doOutput = true

            // Apply custom headers with variable interpolation
            hookConfig.headers?.forEach { (key, value) ->
                conn.setRequestProperty(key, interpolateVariables(value))
            }

            // Build request body
            val body = JSONObject().apply {
                put("flow_id", flow.id)
                put("step_id", step.id)
                put("step_index", currentIndex)
                put("step_type", step.type.value)
                put("step_data", JSONObject(data ?: emptyMap<String, Any>()))
                put("responses", JSONObject(responses))
                put("user_id", AppDNA.getCurrentUserId() ?: "")
                put("app_id", AppDNA.getCurrentAppId() ?: "")
            }

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val responseBody = conn.inputStream.use { it.bufferedReader().readText() }
                return@withContext parseWebhookResponse(responseBody, hookConfig)
            }

            throw java.io.IOException("HTTP $responseCode")
        } finally {
            runCatching { conn.disconnect() }
        }

    } catch (e: SocketTimeoutException) {
        val maxRetries = minOf(hookConfig.retry_count, 3)
        if (attempt < maxRetries) {
            onRetry(attempt + 1)
            val delay = Math.pow(2.0, attempt.toDouble()).toLong() * 1000
            kotlinx.coroutines.delay(delay)
            return@withContext executeWebhook(
                flow, step, data, responses, hookConfig,
                currentIndex, attempt + 1, onRetry, onError
            )
        }
        onError("timeout", "Request timed out")
        StepAdvanceResult.Block(hookConfig.error_text ?: "Request timed out. Please try again.")

    } catch (e: Exception) {
        val maxRetries = minOf(hookConfig.retry_count, 3)
        if (attempt < maxRetries) {
            onRetry(attempt + 1)
            val delay = Math.pow(2.0, attempt.toDouble()).toLong() * 1000
            kotlinx.coroutines.delay(delay)
            return@withContext executeWebhook(
                flow, step, data, responses, hookConfig,
                currentIndex, attempt + 1, onRetry, onError
            )
        }
        onError("network", e.message ?: "Network error")
        StepAdvanceResult.Block(hookConfig.error_text ?: "Network error. Please check your connection.")
    }
}

@Suppress("UNCHECKED_CAST")
internal fun parseWebhookResponse(responseBody: String, hookConfig: StepHookConfig): StepAdvanceResult {
    return try {
        val json = JSONObject(responseBody)
        val action = json.optString("action", "proceed")
        val message = json.optString("message", null as String?)
        val targetStepId = json.optString("target_step_id", null as String?)

        // SPEC-401-A R34 P1 — deep-convert nested JSONObject/JSONArray to
        // native Map/List using the same helper R33 added for process-death
        // restore. Was returning raw JSONObject/JSONArray for nested values
        // → host code reading `responses[stepId]["profile"]` via the
        // delegate broke `as? Map` casts; iOS Codable round-trips into
        // native [String: Any] so works.
        @Suppress("UNCHECKED_CAST")
        val responseData: Map<String, Any>? = if (json.has("data") && !json.isNull("data")) {
            fromJsonAny(json.get("data")) as? Map<String, Any>
        } else null

        when (action) {
            "proceed" -> {
                if (responseData != null) StepAdvanceResult.ProceedWithData(responseData)
                else StepAdvanceResult.Proceed
            }
            "proceed_with_data" -> StepAdvanceResult.ProceedWithData(responseData ?: emptyMap())
            "block" -> StepAdvanceResult.Block(message ?: hookConfig.error_text ?: "Request blocked by server.")
            "skip_to" -> {
                if (targetStepId != null) StepAdvanceResult.SkipTo(targetStepId, responseData)
                else StepAdvanceResult.Proceed
            }
            // SPEC-070-A C.8 — `"stay"` webhook action surfaces the success
            // banner. Mirrors iOS v1.0.60 `parseWebhookResponse` extension.
            "stay" -> StepAdvanceResult.Stay(message)
            else -> StepAdvanceResult.Proceed
        }
    } catch (e: Exception) {
        StepAdvanceResult.Block(hookConfig.error_text ?: "Invalid server response.")
    }
}

private fun interpolateVariables(value: String): String {
    // SPEC-088: Delegate to shared TemplateEngine
    val ctx = ai.appdna.sdk.core.TemplateEngine.buildContext()
    return ai.appdna.sdk.core.TemplateEngine.interpolate(value, ctx)
}

/**
 * SPEC-401-A R33 P1 — recursively convert JSONObject/JSONArray trees into
 * native Map/List so downstream code (NextStepRuleEvaluator,
 * AppDNAOnboardingDelegate, host code reading `responses[stepId]`) sees
 * the same shape iOS Codable round-trips into Foundation types. Without
 * this, restored responses for form/multi-select/chat steps surface as
 * raw JSONObject/JSONArray and fail every `as? Map`/`as? List` cast.
 */
private fun fromJsonAny(value: Any?): Any? = when (value) {
    null, org.json.JSONObject.NULL -> null
    is org.json.JSONObject -> {
        val map = mutableMapOf<String, Any?>()
        value.keys().forEach { k -> map[k] = fromJsonAny(value.opt(k)) }
        map
    }
    is org.json.JSONArray -> {
        (0 until value.length()).map { i -> fromJsonAny(value.opt(i)) }
    }
    else -> value
}

/**
 * QA-R1 — full-bleed background painted behind the system status +
 * navigation bars. Mirrors iOS `OnboardingRenderer.swift:142-164`
 * (`stepFullScreenBackground`) which `.background(...)` modifier is
 * `.ignoresSafeArea()`'d to extend under the safe area.
 *
 * Resolution order matches iOS:
 *   1. step.config.background (BackgroundStyleConfig: color/gradient/image/lottie/rive)
 *   2. step.config.chat_config.style.background_color (chat step convention)
 *   3. interactive_chat fallback: #0F172A (dark navy, iOS canonical)
 *   4. MaterialTheme.colorScheme.background (host-theme-aware fallback)
 *
 * NOTE: the rendering branches mirror the existing background painter in
 * `BlockBasedStepView` (~line 2761) — extracted here so it paints at the
 * Activity root, full-bleed under the status bar. The duplicate painter in
 * BlockBasedStepView remains but is now defensive (no-op since the root
 * already painted) and only fires for legacy non-block steps where the
 * root resolver doesn't catch the authored background.
 */
@Composable
private fun StepFullScreenBackground(currentStep: OnboardingStep?) {
    val bg = currentStep?.config?.background
    val chatBg = currentStep?.config?.chat_config?.style?.background_color
    when {
        bg != null -> {
            when (bg.type) {
                "color" -> bg.color?.let {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(ai.appdna.sdk.core.StyleEngine.parseColor(it))
                    )
                }
                "gradient" -> bg.gradient?.stops?.let { stops ->
                    if (stops.size >= 2) {
                        val colors = stops.map { ai.appdna.sdk.core.StyleEngine.parseColor(it.color) }
                        val brush = when (bg.gradient.type) {
                            "radial" -> Brush.radialGradient(colors)
                            else -> {
                                // SPEC-419 — span the FULL bounds. The old fixed `*1000f` offset
                                // made the gradient COMPLETE at ~1000px (≈42% of a ~2400px screen)
                                // then show its flat end-colour below — the user-reported "gradient
                                // ends earlier than the screen". iOS uses relative UnitPoints (0→1)
                                // so it spans the whole height. Compose's vertical/horizontal
                                // gradients auto-span the draw bounds; use them for cardinal angles
                                // (the only ones authored in practice) + a large-offset fallback.
                                val angle = ((bg.gradient.angle ?: 180.0) % 360 + 360) % 360
                                when (angle) {
                                    180.0 -> Brush.verticalGradient(colors)
                                    0.0 -> Brush.verticalGradient(colors.reversed())
                                    90.0 -> Brush.horizontalGradient(colors)
                                    270.0 -> Brush.horizontalGradient(colors.reversed())
                                    else -> {
                                        val rads = Math.toRadians(angle)
                                        val dx = sin(rads).toFloat()
                                        val dy = -cos(rads).toFloat()
                                        Brush.linearGradient(
                                            colors = colors,
                                            start = Offset((0.5f - dx / 2f) * 4000f, (0.5f - dy / 2f) * 4000f),
                                            end = Offset((0.5f + dx / 2f) * 4000f, (0.5f + dy / 2f) * 4000f),
                                        )
                                    }
                                }
                            }
                        }
                        Box(Modifier.fillMaxSize().background(brush))
                    }
                }
                "image" -> {
                    Box(Modifier.fillMaxSize()) {
                        val imageScale = when (bg.image_fit?.lowercase()) {
                            "fit", "contain" -> androidx.compose.ui.layout.ContentScale.Fit
                            else -> androidx.compose.ui.layout.ContentScale.Crop
                        }
                        ai.appdna.sdk.core.NetworkImage(
                            url = bg.image_url,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = imageScale,
                        )
                        bg.overlay?.takeIf { it.isNotEmpty() && it.lowercase() != "transparent" }?.let { overlay ->
                            val op = bg.overlay_opacity ?: run {
                                val l = overlay.lowercase()
                                if (l == "#000000" || l == "#ffffff" || l == "000000" || l == "ffffff") 0.4 else null
                            }
                            val color = ai.appdna.sdk.core.StyleEngine.parseColor(overlay)
                            val tinted = if (op != null) color.copy(alpha = op.toFloat()) else color
                            Box(Modifier.fillMaxSize().background(tinted))
                        }
                    }
                }
                else -> {
                    // lottie/rive backgrounds keep painting inside the
                    // step renderer (BlockBasedStepView) for now — they
                    // need lifecycle-bound players that the root layer
                    // can't manage cleanly. Fall through to a neutral
                    // base color so the status bar doesn't show host
                    // theme through transparent.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    )
                }
            }
        }
        chatBg != null -> Box(
            Modifier
                .fillMaxSize()
                .background(ai.appdna.sdk.core.StyleEngine.parseColor(chatBg))
        )
        currentStep?.type == OnboardingStep.StepType.INTERACTIVE_CHAT -> Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0F172A))
        )
        else -> Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        )
    }
}

@Composable
fun OnboardingStepView(
    step: OnboardingStep,
    effectiveConfig: StepConfig,
    onNext: (Map<String, Any>?) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    flowId: String = "",
    /** The renderer's delegate — see the call site in [OnboardingFlowHost]. */
    delegate: AppDNAOnboardingDelegate? = null,
    currentStepIndex: Int = 0,
    totalSteps: Int = 1,
    /**
     * SPEC-070-A finalization B4 P1 — previously-entered responses for
     * THIS step, threaded down so back-nav restores text/selection/toggle
     * state. Mirrors iOS OnboardingStepRouter.savedResponses
     * (OnboardingRenderer.swift:1316,1448).
     */
    savedResponses: Map<String, Any>? = null,
    // SPEC-401-A R11 — accumulated `responses` (across all prior steps)
    // and per-step `hookData` propagated down to the visibility-condition
    // evaluator. iOS plumbs through OnboardingStepView → ThreeZoneStepLayout.
    accumulatedResponses: Map<String, Any> = emptyMap(),
    hookData: Map<String, Any>? = null,
    // SPEC-419 STEP-2 — flow-host interaction seam (delegate round-trip + applyInteractionResult),
    // threaded into BlockBasedStepView where the step scope owns advance + inputValues + overrides.
    performInteraction: suspend (String, String, String?, Map<String, Any>) -> AppliedInteraction? = { _, _, _, _ -> null },
) {
    // SPEC-070-A finalization B4 P1 — pre-populate from savedResponses on
    // first composition. `step.id` keying the remember ensures a fresh
    // clear when the user navigates forward to a different step.
    val toggleValues = remember(step.id) {
        mutableMapOf<String, Boolean>().apply {
            savedResponses?.forEach { (k, v) ->
                if (k.startsWith("toggle_") && v is Boolean) put(k.removePrefix("toggle_"), v)
            }
        }
    }
    // SPEC-419 STEP-2 — SnapshotStateMap (was mutableMapOf) so the step scope's handleInteract can
    // clear()/putAll() host-pushed inputValue patches and have the tree recompose. It's a `val` — never
    // reassign; mutate in place.
    val inputValues = remember(step.id) {
        mutableStateMapOf<String, Any>().apply {
            savedResponses?.forEach { (k, v) ->
                if (!k.startsWith("toggle_") && k != "action" && k != "selected" && k != "selection_mode") {
                    put(k, v)
                }
            }
        }
    }

    // SPEC-084: Block-based vs legacy rendering
    val blocks = effectiveConfig.content_blocks
    if (!blocks.isNullOrEmpty()) {
        // Block-based rendering with layout variants
        BlockBasedStepView(
            effectiveConfig = effectiveConfig,
            blocks = blocks,
            toggleValues = toggleValues,
            inputValues = inputValues,
            onNext = onNext,
            onSkip = if (step.config.skip_enabled == true) onSkip else null,
            modifier = modifier,
            currentStepIndex = currentStepIndex,
            totalSteps = totalSteps,
            // SPEC-401-A R11 — flow visibility-eval data through.
            responses = accumulatedResponses,
            hookData = hookData,
            // SPEC-401-A R11 — identity for auth-action analytics emit.
            flowId = flowId,
            stepId = step.id,
            // SPEC-419 STEP-2 — pass the flow-host interaction seam down to the step scope.
            performInteraction = performInteraction,
            delegate = delegate,
        )
    } else {
        // Legacy rendering
        // SPEC-401-A R7 P0 — chat step contains a LazyColumn that
        // crashes inside a verticalScroll-wrapping parent
        // ("Vertically scrollable component was measured with an
        // infinity maximum height constraints"). iOS uses a plain
        // VStack at OnboardingRenderer.swift:1466-1490 with no
        // ScrollView, so ChatStepView's own ScrollView lays out
        // fine. On Android, branch the wrapper: chat gets a
        // non-scrolling Column with fillMaxSize so its internal
        // LazyColumn measures correctly. Other step types keep the
        // verticalScroll fallback for long-content overflow.
        if (step.type == OnboardingStep.StepType.INTERACTIVE_CHAT) {
            // SPEC-401-A R53 (Lens A R53 #1, P0) — wrap ChatStepComposable
            // in Box(weight=1f) so it gets bounded constraints. Was using
            // bare fillMaxSize() inside an unbounded Column which made the
            // chat composable swallow all available vertical space, leaving
            // the Skip TextButton with 0dp height (invisible/clipped).
            // Match the bare-wrapper pattern at line 1875 used by every
            // other step type.
            Column(
                modifier = modifier
                    .entryAnimation(effectiveConfig.animation?.entry_animation, effectiveConfig.animation?.entry_duration_ms)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                    ChatStepComposable(
                        step = step,
                        flowId = flowId,
                        onNext = { data -> onNext(data) },
                        onSkip = { onSkip() },
                        // SPEC-401-A — pass prior step transcript so back-nav
                        // restores chat bubbles + completion state. iOS does
                        // the same via `savedTranscript: savedResponses` at
                        // OnboardingRenderer.swift:1480.
                        savedTranscript = savedResponses,
                    )
                }
                // Skip button
                if (step.config.skip_enabled == true) {
                    TextButton(
                        onClick = onSkip,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp),
                    ) {
                        // SPEC-401-A R52 (Lens A R51 #20, P3) — Skip 15sp matching iOS .subheadline.
                        Text("Skip", fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                    }
                }
            }
            return
        }
        // SPEC-401-A R52 (Lens A R50 P0 #1) — bare-VStack wrapper matching
        // iOS legacyStepView at OnboardingRenderer.swift:1466-1490: NO outer
        // padding, NO verticalScroll, NO verticalArrangement=Center.
        // Each step composable owns its own padding + scroll + Spacer-based
        // vertical layout (mirroring iOS step views). Was wrapping every
        // step in `Column(.verticalScroll, padding(24), Center)` which:
        //   (a) double-padded each step (24dp wrapper + 24dp inner = 48dp)
        //   (b) made `Spacer.weight(1f)` undefined inside the infinite-tall
        //       scroll, breaking iOS-style vertical centering on tall
        //       screens (Welcome/Form CTA position drifted vs iOS).
        //   (c) forced unwanted scroll on short steps.
        // Each step now controls its own scroll behavior — Form does scroll,
        // Welcome/Question/ValueProp/Custom use Spacer.weight for centering.
        Column(
            modifier = modifier
                .entryAnimation(effectiveConfig.animation?.entry_animation, effectiveConfig.animation?.entry_duration_ms)
                .fillMaxSize(),
            // SPEC-401-A R52 (Lens A R52 #7, P2) — 8dp gap between step body
            // and Skip mirroring SwiftUI VStack default spacing
            // (OnboardingRenderer.swift:1467 legacyStepView VStack).
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Inner Box weight=1f reserves space for the step body so the
            // skip button can sit at the bottom edge without conflict with
            // the step's Spacer.weight pieces.
            androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                when (step.type) {
                    OnboardingStep.StepType.WELCOME -> WelcomeStep(effectiveConfig, onNext)
                    OnboardingStep.StepType.QUESTION -> QuestionStep(effectiveConfig, onNext)
                    OnboardingStep.StepType.VALUE_PROP -> ValuePropStep(effectiveConfig, onNext)
                    // SPEC-401-A — `info` + `permission` mirror iOS
                    // OnboardingRenderer.swift:1497 routing through
                    // CustomStepView. Authored content-blocks render via the
                    // block-based path; this handles the no-blocks fallback so
                    // configured title/subtitle/cta still appear.
                    OnboardingStep.StepType.CUSTOM,
                    OnboardingStep.StepType.INFO,
                    OnboardingStep.StepType.PERMISSION -> CustomStep(effectiveConfig, onNext)
                    OnboardingStep.StepType.FORM -> FormStep(effectiveConfig, onNext, savedResponses)
                    // INTERACTIVE_CHAT handled via the early return above.
                    OnboardingStep.StepType.INTERACTIVE_CHAT -> Unit
                }
            }

            // Skip button — pinned to bottom outside step body.
            if (step.config.skip_enabled == true) {
                TextButton(
                    onClick = onSkip,
                    // SPEC-401-A R52 (Lens A R52 #6, P2) — bottom 8→16dp
                    // matching iOS OnboardingRenderer.swift:1487
                    // .padding(.bottom, 16).
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp),
                ) {
                    // SPEC-401-A R52 (Lens A R51 #20, P3) — Skip 15sp matching iOS .subheadline.
                    Text("Skip", fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                }
            }
        }
    }
}

/**
 * SPEC-070-A C.1 — Strict-typed auth/account action emitter for
 * Android. Mirrors iOS `OnboardingRenderer.swift:1559-1583`
 * `emitAuthAction(...)`. Builds a `{action, [recipient?], ...inputValues}`
 * payload and dispatches via `onNext` so the host can route through
 * `onBeforeStepAdvance`. Stays on the step (no auto-advance) — host is
 * expected to return `.Block("Signing in…")` while the side effect runs.
 *
 * Merge order matches iOS: inputValues first so SDK-controlled keys
 * (`action`, `recipient`) always win on a field-id collision. We do not
 * resolve OTP channel here (Android does not yet ship OtpChannelResolver
 * parity — SPEC-086 follow-up). The `actionValue` half of the colon-
 * encoded action surfaces as `recipient` for OTP flows and
 * `action_value` for everything else, leaving channel resolution to the
 * host.
 */
// SPEC-070-A finalization OB-4 — actions iOS' AuthActionPolicy says
// REQUIRE a host-registered onboarding delegate (`onBeforeStepAdvance`)
// before they can advance. iOS source-of-truth:
// `OnboardingRenderer.swift:1605-1618 AuthActionPolicy.delegateRequiredActions`.
// Without a delegate, sensitive credentials (email/password/OTP) would
// silently flow through `onNext` -> `responses` payload while no host
// authentication side-effect actually runs. iOS logs a warning and
// stays on the step so the user notices auth didn't happen.
private val AUTH_ACTIONS_REQUIRING_DELEGATE = setOf(
    "login",
    "register",
    "reset_password",
    "magic_link",
    "verify_email",
    "resend_verification",
    "enable_biometric",
    "email_login",
    "request_otp",
    "verify_otp",
    "logout",
    "change_password",
    "set_new_password",
    "delete_account",
    "update_profile",
)

/**
 * SPEC-070-A finalization B4#P0 — pure resolver for the OTP delivery
 * channel used by `request_otp` / `verify_otp` buttons. Mirrors iOS
 * `OtpChannelResolver` at OnboardingRenderer.swift:1625-1674.
 *
 * Resolution order:
 *   1) Explicit `actionValue` from button config — "sms"|"email"|"whatsapp"|"voice"
 *   2) Auto-detect: exactly one phone-typed block → "sms";
 *      exactly one email-typed block → "email"
 *   3) (null, null) — ambiguous. Host must fail explicitly rather than guess.
 *
 * Recipient is taken from `inputValues[block.field_id ?: block.id]` for the
 * matching block.
 */
internal object OtpChannelResolver {
    private val SUPPORTED = setOf("sms", "email", "whatsapp", "voice")

    fun resolve(
        actionValue: String?,
        blocks: List<ContentBlock>,
        inputValues: Map<String, Any>,
    ): Pair<String?, String?> {
        val phoneBlocks = blocks.filter { it.type == "input_phone" }
        val emailBlocks = blocks.filter { it.type == "input_email" }

        val raw = actionValue?.lowercase()
        if (raw != null && raw in SUPPORTED) {
            val recipient = when (raw) {
                "sms", "whatsapp", "voice" ->
                    phoneBlocks.firstOrNull()?.let { inputValues[it.field_id ?: it.id] as? String }
                "email" ->
                    emailBlocks.firstOrNull()?.let { inputValues[it.field_id ?: it.id] as? String }
                else -> null
            }
            return raw to recipient
        }

        if (phoneBlocks.size == 1 && emailBlocks.isEmpty()) {
            val b = phoneBlocks[0]
            return "sms" to (inputValues[b.field_id ?: b.id] as? String)
        }
        if (emailBlocks.size == 1 && phoneBlocks.isEmpty()) {
            val b = emailBlocks[0]
            return "email" to (inputValues[b.field_id ?: b.id] as? String)
        }
        return null to null
    }
}

internal fun emitAuthAction(
    action: String,
    actionValue: String?,
    toggleValues: Map<String, Boolean>,
    inputValues: Map<String, Any>,
    onNext: (Map<String, Any>?) -> Unit,
    // Shows the same pill the required-field validation uses. Without it, an auth action with no host
    // delegate stayed on the step and logged a warning the USER never sees — "Continue with email"
    // was a dead button: tap, nothing, no error, forever. The log line is for the developer; the pill
    // is for the person holding the phone. Both platforms now show it.
    onError: (String) -> Unit = {},
    blocks: List<ContentBlock> = emptyList(),
    /**
     * 🔴 THE DELEGATE THE RENDERER ACTUALLY HOLDS — not `AppDNA.onboarding.listener`.
     *
     * This gate used to read the GLOBAL listener while the renderer's veto seam
     * (`onBeforeStepAdvance`) reads the delegate that was passed to `present`. A native Kotlin host
     * that calls the documented `AppDNA.presentOnboarding(activity, "welcome", myDelegate)` WITHOUT
     * also calling `AppDNA.onboarding.setDelegate(...)` therefore had a working veto seam and a gate
     * that saw NO delegate: every `email_login` / `request_otp` / `social_login` step refused to
     * advance, forever, with a delegate sitting right there.
     */
    delegate: AppDNAOnboardingDelegate? = null,
) {
    // SPEC-070-A finalization OB-4 — auth-action delegate gate. If the
    // host has not registered an AppDNAOnboardingDelegate (which is the
    // contract surface for `onBeforeStepAdvance`), refuse to advance
    // for actions in AUTH_ACTIONS_REQUIRING_DELEGATE. Mirrors iOS
    // OnboardingRenderer.swift:1465-1474 (warn + return early). Logging
    // via SDK Log so production builds (BuildConfig.DEBUG=false) don't
    // suppress the message — auth flow misconfiguration is a developer
    // contract issue worth surfacing in release builds too.
    if (action in AUTH_ACTIONS_REQUIRING_DELEGATE && delegate == null) {
        // 🔴 NO `onboarding_step_completed` HERE. The step did NOT complete — it refused to
        // advance. Android used to emit one anyway (with a `blocked_reason: "no_delegate"` property
        // no dbt model has ever heard of), inflating Android step-completion counts and funnel
        // conversion by every misconfigured auth tap. iOS's blocked path emits nothing (its only
        // `onboarding_step_completed` emitter is the real advance, via `onStepCompleted`).
        ai.appdna.sdk.Log.warning(
            "Auth action `$action` was triggered but no AppDNAOnboardingDelegate " +
            "is registered. Register via AppDNA.onboarding.setDelegate(...) and " +
            "implement onBeforeStepAdvance to handle authentication. Step will NOT " +
            "advance to avoid silently leaking credentials into the responses payload."
        )
        onError("Sign-in isn't available right now. Please try again later.")
        return
    }

    val data = mutableMapOf<String, Any>()
    // input values first so SDK-controlled keys overwrite collisions
    data.putAll(inputValues)
    for ((k, v) in toggleValues) {
        data["toggle_$k"] = v
    }
    data["action"] = action
    when (action) {
        "request_otp", "verify_otp" -> {
            // SPEC-070-A finalization B4#P0 — emit BOTH `channel` (sms|email|whatsapp|voice)
            // AND `recipient` (resolved from inputValues via OtpChannelResolver),
            // matching iOS OnboardingRenderer.swift:1559-1594. Previously Android
            // only emitted `recipient = actionValue` (which is actually the channel
            // string, not a phone/email) and never emitted `channel` at all,
            // breaking every host that branches on the OTP delivery method.
            val (channel, recipient) = OtpChannelResolver.resolve(actionValue, blocks, inputValues)
            if (channel != null) data["channel"] = channel
            if (recipient != null) data["recipient"] = recipient
        }
        else -> {
            if (actionValue != null) data["action_value"] = actionValue
        }
    }
    onNext(data)
}

/**
 * The `social_login` CTA. Extracted from the Compose `when (action)` branch so the delegate gate is
 * reachable — and provable — without a live Activity; it was previously unreachable by any test,
 * which is how it kept BOTH bugs (global-listener gate, fake `onboarding_step_completed`) that
 * [emitAuthAction] had.
 *
 * iOS treats `social_login` as one more entry in `AuthActionPolicy.delegateRequiredActions`
 * (OnboardingRenderer.swift:1697), so the blocked path behaves exactly like every other auth action:
 * log, show the error toast, DO NOT advance, emit nothing.
 */
internal fun emitSocialLoginAction(
    provider: String?,
    inputValues: Map<String, Any>,
    onNext: (Map<String, Any>?) -> Unit,
    onError: (String) -> Unit = {},
    delegate: AppDNAOnboardingDelegate? = null,
) {
    if (delegate == null) {
        ai.appdna.sdk.Log.warning(
            "social_login button tapped but no AppDNAOnboardingDelegate is " +
            "registered. Register via AppDNA.onboarding.setDelegate(...) and " +
            "implement onBeforeStepAdvance to handle the OAuth flow. Step will " +
            "NOT advance until a delegate is provided."
        )
        onError("Sign-in isn't available right now. Please try again later.")
        return
    }
    // SPEC-070-A C.1 — social_login retains its existing data shape
    // (`{provider, action: "social_login"}`) for hosts that switch on
    // `action == "social_login"`. iOS `OnboardingRenderer.swift:1507-1524`.
    val data = mutableMapOf<String, Any>(
        "provider" to (provider ?: "unknown"),
        "action" to "social_login",
    )
    data.putAll(inputValues)
    onNext(data)
}

// SPEC-070-A finalization OB-8 — three-zone block layout helper.
// SPEC-401-A revised — full parity with iOS `ThreeZoneStepLayout.swift`:
//   - Top + center are scrollable together (iOS line 40: `ScrollView`)
//   - Bottom is sticky / keyboard-aware (iOS line 80: `.safeAreaInset(edge: .bottom)`
//     + `.ignoresSafeArea(.keyboard, edges: .bottom)`)
//   - Default unzoned blocks go to TOP, not center (iOS line 118: `default: top.append`)
//   - Tap on empty area dismisses keyboard (iOS line 53: `resignFirstResponder`)
//   - Responsive horizontal padding `max(24dp, screenWidth * 8%)` (iOS line 106)
//   - Zone top paddings: 16dp top + 20dp center (iOS lines 44, 48)
//   - "only-center" path centers a lone center block vertically (iOS lines 30-37)
//
// Without these, the CTA authored at the bottom of a step + footer-pinned
// legal text scrolled inline with the question content, making every step's
// vertical rhythm wrong vs iOS. The keyboard would also push the CTA off
// the screen on Android, while iOS pinned it via safeAreaInset.
@androidx.compose.runtime.Composable
private fun ThreeZoneBlockLayout(
    blocks: List<ContentBlock>,
    onAction: (String) -> Unit,
    toggleValues: MutableMap<String, Boolean>,
    inputValues: MutableMap<String, Any>,
    loc: ((String, String) -> String)?,
    currentStepIndex: Int,
    totalSteps: Int,
    // SPEC-401-A R11 — `responses` + `hookData` flow into the visibility
    // filter. iOS threads these through ThreeZoneStepLayout
    // (ThreeZoneStepLayout.swift:13-14, 23-26); without them every
    // `visibility_condition` operator (when_equals / when_not_empty /
    // when_gt / when_lt) silently evaluates against an empty map,
    // breaking conditional content rendering on Android.
    responses: Map<String, Any> = emptyMap(),
    hookData: Map<String, Any>? = null,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    // SPEC-419 STEP-2 — interactive-element fire closure + per-block field_config override read-layer,
    // threaded to every zone's ContentBlockRendererView.
    onInteract: (String, String, String?) -> Unit = { _, _, _ -> },
    fieldConfigOverrides: Map<String, Map<String, Any>> = emptyMap(),
) {
    // SPEC-401-A R11 — apply `visibility_condition` BEFORE partitioning,
    // mirroring iOS at ThreeZoneStepLayout.swift:23-26. Previously Android
    // partitioned ALL blocks first (visible + hidden) and the inner
    // ContentBlockRendererView filtered per-zone, so the
    // `onlyCenterContent` decision used the unfiltered top count and
    // took the wrong layout branch when a top block was hidden.
    val visibleBlocks = blocks.filter { block ->
        evaluateVisibilityCondition(block.visibility_condition, responses = responses, hookData = hookData)
    }
    // SPEC-401-A R55 (Lens A R54 #2, P1) — drop the `!anyZoned` early
    // return so the unzoned case still runs through the partition + zone
    // render path, getting responsive horizontal padding + scroll wrapper
    // + keyboard-dismiss tap + bottom-zone safeArea inset for free.
    // iOS ThreeZoneStepLayout.swift:109-122 always partitions and routes
    // unzoned blocks to top via `default: top.append`. Was bypassing all
    // those wrappers when no block had an explicit zone.
    // SPEC-401-A — iOS partition (`ThreeZoneStepLayout.swift:113-119`)
    // routes UNKNOWN/empty zones to TOP via `default: top.append(block)`,
    // not center. Earlier Android comment was factually wrong about iOS.
    // Fixing makes the same flow render with the same vertical rhythm
    // on both platforms (was P0: a block authored without an explicit
    // zone landed in different parts of the screen on each native).
    val top = mutableListOf<ContentBlock>()
    val center = mutableListOf<ContentBlock>()
    val bottom = mutableListOf<ContentBlock>()
    for (block in visibleBlocks) {
        val effectiveZone = (block.zone ?: block.vertical_align ?: "top").lowercase()
        when (effectiveZone) {
            "center" -> center.add(block)
            "bottom" -> bottom.add(block)
            else -> top.add(block)
        }
    }
    val onlyCenterContent = top.isEmpty() && center.isNotEmpty()

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    // SPEC-401-A — iOS uses `max(24, screenWidth * 0.08)` for responsive
    // horizontal margins (`ThreeZoneStepLayout.swift:106`). Mirror the
    // formula in dp so wider screens (foldables, tablets) get proportional
    // breathing room and small phones stay at the 24dp floor.
    val horizontalPadding = maxOf(24, (configuration.screenWidthDp * 0.08).toInt()).dp

    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxSize()
            // SPEC-401-A R36 (Lens C #1) — root-level keyboard dismiss to
            // match iOS OnboardingRenderer.swift:123-129. Was scoped to the
            // inner scroll Column only, so taps in the bottom-zone gutter,
            // nav-bar gutter, or progress-bar gutter left the keyboard up
            // and the user got stuck.
            .pointerInput(Unit) {
                detectTapGestures {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }
            },
    ) {
        // ── TOP + CENTER (scrollable, keyboard-aware) ─────────────────────
        if (onlyCenterContent) {
            // Only center content (e.g. loading spinner) — vertically center it.
            // Mirrors iOS `ThreeZoneStepLayout.swift:30-37`.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding)
                    .padding(bottom = if (bottom.isNotEmpty()) 80.dp else 0.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                ContentBlockRendererView(
                    blocks = center,
                    onAction = onAction,
                    toggleValues = toggleValues,
                    inputValues = inputValues,
                    loc = loc,
                    responses = responses,
                    hookData = hookData,
                    currentStepIndex = currentStepIndex,
                    totalSteps = totalSteps,
                    onInteract = onInteract,
                    fieldConfigOverrides = fieldConfigOverrides,
                )
            }
        } else {
            // Normal: top scrolls with center; tap empty area dismisses
            // keyboard; bottom zone leaves room via padding(bottom = 80dp)
            // (iOS uses safeAreaInset with intrinsic height — 80dp covers
            // the typical CTA + small footer).
            // SPEC-401-A R55 (Lens A R54 #1, P1) — outer
            // verticalArrangement = spacedBy(12.dp) matching iOS
            // ThreeZoneStepLayout.swift:41 VStack(spacing: 12). Per-zone
            // top-pads (16dp on top, 20dp on center) reapplied via
            // ContentBlockRendererView's wrapping Box modifier so the
            // iOS-canonical gap top→center = 12 + 20 = 32pt is preserved.
            // Was Spacer(20dp) only = 20dp, drift 12dp.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (bottom.isNotEmpty()) 80.dp else 0.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (top.isNotEmpty()) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        ContentBlockRendererView(
                            blocks = top,
                            onAction = onAction,
                            toggleValues = toggleValues,
                            inputValues = inputValues,
                            loc = loc,
                            responses = responses,
                            hookData = hookData,
                            currentStepIndex = currentStepIndex,
                            totalSteps = totalSteps,
                    onInteract = onInteract,
                    fieldConfigOverrides = fieldConfigOverrides,
                        )
                    }
                }
                if (center.isNotEmpty()) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.padding(top = 20.dp),
                    ) {
                        ContentBlockRendererView(
                            blocks = center,
                            onAction = onAction,
                            toggleValues = toggleValues,
                            inputValues = inputValues,
                            loc = loc,
                            responses = responses,
                            hookData = hookData,
                            currentStepIndex = currentStepIndex,
                            totalSteps = totalSteps,
                    onInteract = onInteract,
                    fieldConfigOverrides = fieldConfigOverrides,
                        )
                    }
                }
            }
        }

        // ── BOTTOM (sticky, keyboard-aware) ──────────────────────────────
        // SPEC-401-A — iOS pins the CTA via `safeAreaInset(edge: .bottom)`
        // and explicitly calls `ignoresSafeArea(.keyboard, edges: .bottom)`
        // so the IME doesn't push it. Compose's `Modifier.imePadding()`
        // is the standard Android idiom for the same behavior.
        if (bottom.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    // SPEC-419 — no extra bottom pad: the CTA sits right above the nav/gesture
                    // safe-area inset (the parent's safeDrawingPadding already insets it), matching
                    // iOS where the CTA sits right above the home indicator. The old 8dp pushed the
                    // button ~8dp higher → a visible dark gap below it vs iOS (user-reported).
                    .imePadding(),
            ) {
                ContentBlockRendererView(
                    blocks = bottom,
                    onAction = onAction,
                    toggleValues = toggleValues,
                    inputValues = inputValues,
                    loc = loc,
                    responses = responses,
                    hookData = hookData,
                    currentStepIndex = currentStepIndex,
                    totalSteps = totalSteps,
                    onInteract = onInteract,
                    fieldConfigOverrides = fieldConfigOverrides,
                )
            }
        }
    }
}

// SPEC-070-A finalization OB-7 — collect every image URL referenced by a
// flow so [ImagePreloader.prefetch] can warm Coil's cache before the first
// paint. Mirrors iOS `OnboardingRenderer.swift:866-916` `collectImageURLs`
// recursive walk over content_blocks, step.config fields, and per-block
// option lists. Returns trimmed, non-empty URLs.
private fun collectFlowImageURLs(flow: OnboardingFlowConfig): List<String> {
    val urls = mutableListOf<String>()
    fun pushIfPresent(s: String?) {
        val t = s?.trim()
        if (!t.isNullOrEmpty()) urls += t
    }
    fun walkBlocks(blocks: List<ContentBlock>?) {
        if (blocks.isNullOrEmpty()) return
        for (b in blocks) {
            pushIfPresent(b.image_url)
            // SPEC-070-A finalization B4 P1 — also preload `placeholder_image_url`.
            // iOS collectImageURLs walks every block.placeholder_image_url so the
            // first-frame poster paints immediately while Lottie/video stream.
            // Android previously skipped it even though the field IS in the DTO,
            // making lottie blocks flash empty until the network response landed.
            pushIfPresent(b.placeholder_image_url)
            pushIfPresent(b.video_thumbnail_url)
            pushIfPresent(b.lottie_url) // pre-warm Lottie too (Coil renders the JSON URL as a network resource)
            pushIfPresent(b.rive_url)
            // Nested blocks: card.children, row.children, stack.children.
            walkBlocks(b.children)
            // InputOption-shaped images for select/chips/segmented blocks.
            b.field_options?.forEach { opt ->
                pushIfPresent(opt.image_url)
            }
        }
    }
    for (step in flow.steps) {
        pushIfPresent(step.config.image_url)
        pushIfPresent(step.config.background?.image_url)
        walkBlocks(step.config.content_blocks)
    }
    return urls.distinct()
}

// SPEC-070-A finalization OB-1 — auth-class actions that ALSO require
// required-field validation (you can't login without an email, etc.).
// Mirrors the validation policy iOS' OnboardingRenderer applies inside
// `emitAuthAction` (`OnboardingRenderer.swift:1561-1567`): canAdvance
// runs BEFORE invoking the auth handler.
private val AUTH_ACTIONS_REQUIRING_VALIDATION = setOf(
    "login",
    "register",
    "reset_password",
    "magic_link",
    "verify_email",
    "email_login",
    "request_otp",
    "verify_otp",
    "change_password",
    "set_new_password",
    "update_profile",
    "social_login", // provider already chosen; submitted profile fields still validated
    // Hotfix-1.0.33 — close parity gap with iOS `emitAuthAction`
    // (OnboardingRenderer.swift:1598-1606) which gates EVERY auth action via
    // canAdvance. Android originally placed the gate at the dispatch switch
    // keyed off membership, but missed these 4. Without them a screen that
    // pairs a required input with `resend_verification` / `enable_biometric`
    // / `logout` / `delete_account` advanced with empty payload on Android
    // while iOS showed the validation pill.
    "resend_verification",
    "enable_biometric",
    "logout",
    "delete_account",
)

// SPEC-084: Block-based step view with 5 layout variants
@Composable
private fun BlockBasedStepView(
    effectiveConfig: StepConfig,
    blocks: List<ContentBlock>,
    toggleValues: MutableMap<String, Boolean>,
    inputValues: MutableMap<String, Any> = mutableMapOf(),
    onNext: (Map<String, Any>?) -> Unit,
    onSkip: (() -> Unit)?,
    modifier: Modifier = Modifier,
    currentStepIndex: Int = 0,
    totalSteps: Int = 1,
    // SPEC-401-A R11 — flow `responses` + `hookData` through to the
    // ThreeZoneBlockLayout / ContentBlockRendererView so authored
    // `visibility_condition` operators evaluate against real responses
    // instead of an empty map. Mirrors iOS BlockBasedStepView path
    // (OnboardingRenderer.swift:1452-1460).
    responses: Map<String, Any> = emptyMap(),
    hookData: Map<String, Any>? = null,
    // SPEC-401-A R11 — flowId/stepId so the auth-action no-delegate
    // path can fire `onboarding_step_completed` analytics with the
    // correct identity (matches iOS handleStepCompleted pre-gate emit).
    flowId: String = "",
    stepId: String = "",
    // SPEC-419 STEP-2 — flow-host interaction seam. This is the STEP scope: it owns fieldConfigOverrides
    // + handleInteract and routes any host-requested advance through the required-field-gated handleAction.
    performInteraction: suspend (String, String, String?, Map<String, Any>) -> AppliedInteraction? = { _, _, _, _ -> null },
    /**
     * The renderer's delegate. Read by the auth-action gate ([emitAuthAction] /
     * [emitSocialLoginAction]) and by the permission callbacks below, all of which used to read the
     * GLOBAL `AppDNA.onboarding.listener` instead — a different reference.
     */
    delegate: AppDNAOnboardingDelegate? = null,
) {
    val variant = effectiveConfig.layout_variant ?: "no_image"

    // SPEC-419 STEP-2 — per-block field_config overrides pushed by the host delegate (blockId -> key ->
    // value). Snapshot-observable so a merged override recomposes the affected block. Reset per step.
    val fieldConfigOverrides = remember(stepId) { mutableStateMapOf<String, Map<String, Any>>() }
    val interactionScope = rememberCoroutineScope()

    // SPEC-401-A R35 — in-app validation pill (Lens C #4). iOS shows a
    // bottom-aligned styled pill `OnboardingRenderer.swift:1383-1402`
    // instead of system Toast. android.widget.Toast inherits OEM theming
    // and looks foreign vs. the rest of the SDK UI.
    var validationMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(validationMessage) {
        if (validationMessage != null) {
            kotlinx.coroutines.delay(2500)
            validationMessage = null
        }
    }

    // SPEC-084: Localization helper for step text
    // SPEC-087: Also interpolates {{variables}} after localization
    fun loc(key: String, fallback: String): String {
        val localized = LocalizationEngine.resolve(key, effectiveConfig.localizations, effectiveConfig.default_locale, fallback)
        return localized.interpolated()
    }

    // SPEC-070-A finalization OB-1 — required-field validation gate.
    // Mirrors iOS `OnboardingRenderer.swift:1465-1487` `canAdvance` walk:
    // enumerate every block where `field_required == true`, check whether
    // `inputValues[field_id ?: id]` is non-empty, return false (with the
    // first failing block's id for highlighting / error copy) if any are
    // empty. iOS shows `showValidationToast`; Android Toast is the
    // equivalent surface. Without this gate, the BlockBasedStepView's
    // "next" branch advanced unconditionally — required fields had a red
    // asterisk in the label but no actual gating.
    // SPEC-419 STEP-2 — delegates to the pure, unit-testable RequiredFieldGate. The empty-check walk
    // (including the QA-R19 `List<*>` empty-multi-select branch) moved there verbatim, so behavior is
    // unchanged; extracting it proves an interaction-driven advance can NOT bypass required-field
    // validation without a live host (see ElementInteractionWiringTest).
    fun canAdvance(): Pair<Boolean, String?> = RequiredFieldGate.evaluate(blocks, inputValues)

    val activityCtx = androidx.compose.ui.platform.LocalContext.current

    // SPEC-421 — runtime permission pipeline plumbing. The ActivityResultLauncher MUST be registered
    // at COMPOSITION (never lazily at button-tap, which throws IllegalStateException when RESUMED).
    // The launcher callback completes the manager's in-flight `request()` deferred, bridging the
    // callback → suspend so the async pipeline can await the OS result. Mirrors the iOS pipeline.
    val permissionManager = remember { PermissionManager(activityCtx) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionManager.completePending(granted) }
    // Wire the launcher bridge (the launcher is stable across recompositions).
    permissionManager.requestLauncher = { perm -> permLauncher.launch(perm) }
    // Mrozu (alarmy s4.1) — SCHEDULE_EXACT_ALARM has no runtime-permission dialog; it's a Settings
    // screen opened via ACTION_REQUEST_SCHEDULE_EXACT_ALARM. StartActivityForResult fires its callback
    // when the user returns, at which point completePendingExactAlarm() re-reads canScheduleExactAlarms().
    val exactAlarmLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { permissionManager.completePendingExactAlarm() }
    permissionManager.exactAlarmSettingsLauncher = {
        val intent = permissionManager.exactAlarmSettingsIntent()
        // Guard the launch: if the SDK's host app never declared USE_EXACT_ALARM /
        // SCHEDULE_EXACT_ALARM (nor the queries entry), the settings deep-link may
        // fail to resolve and throw ActivityNotFoundException, crashing the host or
        // hanging requestExactAlarm()'s deferred so the step never advances. Mirror
        // the sibling link-open try/catch in ButtonBlock: resolve-check, and on any
        // failure still complete the pending request so the pipeline advances.
        if (intent != null && intent.resolveActivity(activityCtx.packageManager) != null) {
            try {
                exactAlarmLauncher.launch(intent)
            } catch (e: Exception) {
                permissionManager.completePendingExactAlarm()
            }
        } else {
            permissionManager.completePendingExactAlarm()
        }
    }
    // Opt-in "Open Settings" affordance for a permanently-denied permission.
    var permSettingsFallback by remember(stepId) { mutableStateOf<PermissionSettingsFallback?>(null) }

    // SPEC-421 — analytics props attached to every `permission_*` literal (parity with iOS permissionProps).
    fun permissionProps(type: String): Map<String, Any> =
        mapOf("permission_type" to type, "flow_id" to flowId, "step_id" to stepId)

    // Store the resolved value under `permission_{type}` (routable by next_step_rules) and fire the
    // observe-only delegate callback. Does NOT advance — callers advance explicitly.
    fun storePermissionResult(type: String, granted: Boolean) {
        inputValues["permission_" + type] = if (granted) "granted" else "denied"
        delegate?.onPermissionResult(
            flowId = flowId, stepId = stepId, permissionType = type, granted = granted,
        )
    }

    // Advance via the same collect-and-onNext path the primary CTA uses so the freshly stored
    // `permission_{type}` value rides along in the step response. Mirrors iOS advancePermissionStep.
    //
    // SPEC-070-B — the payload now also carries the `onAction` pair (`action` = "permission",
    // `action_value` = the resolved type) via [permissionActionPayload], so a host reading
    // `onBeforeStepAdvance`'s stepData can see WHICH permission this advance came from. Before, a
    // permission step advanced with an anonymous bag of inputs and the host had to guess.
    fun advancePermissionStep(type: String) {
        val merged = permissionActionPayload(type, toggleValues, inputValues).toMutableMap()
        onNext(if (merged.isEmpty()) null else merged)
    }

    // Present the opt-in "Open Settings" affordance (advance handled on the user's choice), or advance.
    fun maybeShowSettingsFallbackOrAdvance(type: String) {
        val showFallback = (effectiveConfig.show_settings_fallback_on_denied
            ?: (effectiveConfig.layout?.get("show_settings_fallback_on_denied") as? Boolean)) == true
        val label = effectiveConfig.settings_fallback_label
            ?: (effectiveConfig.layout?.get("settings_fallback_label") as? String) ?: "Open Settings"
        if (showFallback) {
            permSettingsFallback = PermissionSettingsFallback(type = type, label = label)
        } else {
            advancePermissionStep(type)
        }
    }

    // SPEC-421 — the full async permission pipeline for a `permission` CTA: runs the optional host
    // pre-hook, status check, native OS request, analytics + delegate callbacks, stores the result,
    // and advances. Never crashes on an undeclared manifest permission (status returns UNAVAILABLE →
    // emit + advance without launching). Mirrors iOS OnboardingRenderer.runPermissionPipeline.
    //
    // SPEC-070-B — [type] is now resolved by the pure [resolvePermissionType] seam at the call site
    // (config → layout → the button's own `action_value`) instead of being re-read from `layout`
    // inside this closure, so the type + prompt/fallback decision is assertable without Compose.
    fun runPermissionPipeline(type: String) {
        interactionScope.launch {
            // 1. Optional host pre-hook — host may resolve without prompting.
            val handling = delegate?.onPermissionRequest(type)
            if (handling is PermissionHandling.HandledByHost) {
                val granted = handling.granted
                if (granted) {
                    AppDNA.track("permission_granted", permissionProps(type))
                } else {
                    AppDNA.track("permission_denied", permissionProps(type))
                }
                storePermissionResult(type, granted)
                advancePermissionStep(type)
                return@launch
            }

            // 2. Status check → route.
            when (permissionManager.status(type)) {
                PermissionStatus.GRANTED -> {
                    AppDNA.track("permission_already_granted", permissionProps(type))
                    storePermissionResult(type, true)
                    advancePermissionStep(type)
                }
                PermissionStatus.DENIED -> {
                    AppDNA.track("permission_denied", permissionProps(type))
                    storePermissionResult(type, false)
                    maybeShowSettingsFallbackOrAdvance(type)
                }
                PermissionStatus.UNAVAILABLE -> {
                    AppDNA.track("permission_unavailable", permissionProps(type))
                    ai.appdna.sdk.Log.warning(
                        "[Permission] '$type' unavailable (not declared in host manifest or " +
                        "unsupported type) — advancing without prompting.",
                    )
                    advancePermissionStep(type)
                }
                PermissionStatus.UNDETERMINED -> {
                    // 3. Request the OS prompt (async via the composition-registered launcher).
                    AppDNA.track("permission_prompted", permissionProps(type))
                    val granted = permissionManager.request(type)
                    if (granted) {
                        AppDNA.track("permission_granted", permissionProps(type))
                        storePermissionResult(type, true)
                        advancePermissionStep(type)
                    } else {
                        AppDNA.track("permission_denied", permissionProps(type))
                        storePermissionResult(type, false)
                        // Sticky-denied detection is post-request only (never-asked vs permanently-denied
                        // are indistinguishable from checkSelfPermission alone).
                        val blocked = PermissionManager.isBlockedAfterDenial(
                            granted = false,
                            shouldShowRationale = permissionManager.shouldShowRationale(type),
                        )
                        if (blocked) maybeShowSettingsFallbackOrAdvance(type) else advancePermissionStep(type)
                    }
                }
            }
        }
    }

    fun handleAction(action: String) {
        // SPEC-070-A C.1 — strict-typed auth/account action cases mirror iOS
        // `OnboardingRenderer.swift:1531-1545`. Action strings travel as either
        // a bare token ("login", "logout", ...) OR as a colon-encoded pair
        // ("social_login:google", "request_otp:sms", "email_login:email") so
        // the existing `(String) -> Unit` callback shape stays intact across
        // ContentBlockRenderer call sites. The recipient/value half — when
        // present — is forwarded to the host via `data["action_value"]` so
        // `onBeforeStepAdvance` can branch on it.
        val (rawAction, actionValue) = when {
            action.contains(":") -> {
                val idx = action.indexOf(':')
                action.substring(0, idx) to action.substring(idx + 1)
            }
            else -> action to null
        }

        // SPEC-421 — a permission step whose CTA is authored as plain `action:"next"` runs the
        // permission pipeline (prompt) BEFORE the required-field validation gate below, matching
        // iOS (OnboardingRenderer.swift `case "next"` short-circuits before `guard canAdvance`).
        // Permission steps skip required-field validation by design (the pipeline owns advance).
        // SPEC-070-B — the type + prompt/fallback call is the pure seam's now. A plain `next` on a
        // permission step only diverts into the pipeline when the step really does declare a
        // supported permission (RunPipeline); otherwise it falls through to the normal `next`
        // handling below, exactly as before.
        if (rawAction == "next") {
            val nextDecision = decidePermissionAction(
                resolvePermissionType(
                    configType = effectiveConfig.permission_type,
                    layoutType = effectiveConfig.layout?.get("permission_type") as? String,
                    // A bare `next` carries no action value — never treat the CTA label as a type.
                    actionValue = null,
                ),
            )
            if (nextDecision is PermissionActionDecision.RunPipeline) {
                runPermissionPipeline(nextDecision.type)
                return
            }
        }

        // OB-1 — actions that finalize/submit the step must pass validation.
        // Auth-class actions (login, register, request_otp, email_login, ...)
        // also gate on required fields because the host can't authenticate
        // with empty credentials.
        val requiresValidation = rawAction == "next" || rawAction in AUTH_ACTIONS_REQUIRING_VALIDATION
        if (requiresValidation) {
            val (ok, fieldLabel) = canAdvance()
            if (!ok) {
                // SPEC-401-A R51 (Lens C #3, P3) — match iOS toast copy at
                // OnboardingRenderer.swift:1393 `"Please fill in <field>"`.
                // Was Android-bespoke `"<field> is required"` — analytics
                // filtering on the toast text would split between platforms.
                val msg = "Please fill in ${fieldLabel ?: "required fields"}"
                // SPEC-401-A R35 — surface as in-app pill instead of system
                // Toast (iOS OnboardingRenderer.swift:1383-1402).
                validationMessage = msg
                return
            }
        }

        when (rawAction) {
            "next" -> {
                // (permission-step-via-"next" is handled above, before validation)
                // SPEC-401-A R15 — match iOS OnboardingRenderer.swift:1518-1527.
                // iOS prefixes every toggle key with `toggle_` so the namespaces
                // never collide with form input keys; Android previously merged
                // raw toggle keys, breaking cross-platform analytics aggregation
                // and any NextStepRule JSON authored against `toggle_<id>`.
                // Order: inputs first, then toggle_<key> entries — matches iOS
                // line ordering at 1519-1526.
                val merged = mutableMapOf<String, Any>()
                merged.putAll(inputValues)
                for ((key, value) in toggleValues) {
                    merged["toggle_$key"] = value
                }
                onNext(if (merged.isEmpty()) null else merged)
            }
            "skip" -> onSkip?.invoke()
            // SPEC-070-A C.1 — social_login retains its existing data shape
            // (`{provider, action: "social_login"}`) for backwards compatibility
            // with hosts that switch on `action == "social_login"`. iOS
            // `OnboardingRenderer.swift:1507-1524`.
            "social_login" -> {
                emitSocialLoginAction(
                    actionValue, inputValues, onNext,
                    onError = { msg -> validationMessage = msg },
                    delegate = delegate,
                )
            }
            // SPEC-070-A C.1 — strict-typed auth/account actions. Every case
            // emits `onNext({action, [recipient?], ...inputValues})` so the
            // host can route via `onBeforeStepAdvance`. Mirrors iOS
            // `OnboardingRenderer.swift:1531-1546` `emitAuthAction(...)`.
            //
            // Auth entry actions:
            "login", "register", "reset_password", "magic_link",
            "verify_email", "resend_verification", "enable_biometric",
            "email_login",
            // OTP actions — channel/recipient resolved via OtpChannelResolver
            // (SPEC-070-A finalization B4#P0 parity with iOS):
            "request_otp", "verify_otp",
            // Account lifecycle:
            "logout", "change_password", "set_new_password",
            "delete_account", "update_profile" -> {
                emitAuthAction(
                    rawAction, actionValue, toggleValues, inputValues, onNext,
                    onError = { msg -> validationMessage = msg },
                    blocks = blocks,
                    delegate = delegate,
                )
            }
            // SPEC-421 / SPEC-070-B — a `permission` CTA now goes through the [emitPermissionAction]
            // seam. It (a) makes the tap OBSERVABLE to the host — `{action: "permission",
            // action_value: <type>}` on the same `onNext` channel every other CTA uses, which is what
            // the cross-platform fixtures call `onAction` — and (b) decides prompt-vs-safe-fallback
            // purely. Supported type → fire the real OS prompt (async), capture grant/deny, emit
            // analytics + the delegate hook, store the result for next-step routing, then advance.
            // Absent/unsupported type → emit `permission_unavailable` and ADVANCE (the seam already
            // emitted the action + completed the step), so the button is never a dead end.
            // Mirrors iOS `OnboardingRenderer.swift` `case "permission"`.
            "permission" -> {
                val decision = emitPermissionAction(
                    configType = effectiveConfig.permission_type,
                    layoutType = effectiveConfig.layout?.get("permission_type") as? String,
                    actionValue = actionValue,
                    toggleValues = toggleValues,
                    inputValues = inputValues,
                    onNext = onNext,
                )
                when (decision) {
                    is PermissionActionDecision.RunPipeline -> runPermissionPipeline(decision.type)
                    is PermissionActionDecision.SafeFallbackAdvance -> {
                        AppDNA.track("permission_unavailable", permissionProps(decision.type))
                        ai.appdna.sdk.Log.warning(
                            "[Permission] '${decision.type}' is not a permission this platform can " +
                                "request (unsupported or undeclared type) — the action was reported to " +
                                "the host and the flow advanced without prompting.",
                        )
                    }
                }
            }
            else -> onNext(null)
        }
    }

    // SPEC-419 STEP-2 — step-scope half of the interaction wiring. An interactive element calls this
    // with (blockId, action, value); we await the flow-host delegate round-trip, then on a non-nil
    // result apply inputValue patches (clear()/putAll() the SnapshotStateMap — it's a val, never
    // reassigned), key-level-merge field_config overrides, and — if the host asked to advance — funnel
    // through the EXISTING handleAction("next"), the ONLY entry that runs canAdvance (RequiredFieldGate)
    // then onNext → onBeforeStepAdvance. Mirrors iOS OnboardingStepRouter.handleInteract.
    fun handleInteract(blockId: String, action: String, value: String?) {
        interactionScope.launch {
            val applied = performInteraction(blockId, action, value, inputValues.toMap())
            if (applied != null) {
                inputValues.clear()
                inputValues.putAll(applied.inputValues)
                applied.fieldConfigOverrides.forEach { (id, patch) ->
                    fieldConfigOverrides[id] = (fieldConfigOverrides[id] ?: emptyMap()) + patch
                }
                if (applied.advance) handleAction("next")
            }
        }
    }

    Box(modifier = modifier.entryAnimation(effectiveConfig.animation?.entry_animation, effectiveConfig.animation?.entry_duration_ms)) {
        // Step-level background (color, gradient, image)
        effectiveConfig.background?.let { bg ->
            // SPEC-419 — color + gradient are painted FULL-BLEED by StepFullScreenBackground at the
            // Activity root (under the system bars). Re-painting them HERE — inside the safe-area
            // content, so `fillMaxSize` is bounded to the CONTENT height, not the screen — made the
            // gradient finish at the safe-area bottom and render darker in the content area than the
            // full-bleed copy shows in the nav region: the banded "gap"/dark area below the CTA the
            // user reported (iOS spans full-screen → uniform). Skip the duplicate color/gradient
            // fills here; image/lottie/rive animate and stay (root also draws them, harmless).
            val bgType = if (bg.type == "color" || bg.type == "gradient") "__rootPaintsFullBleed__" else bg.type
            when (bgType) {
                "color" -> bg.color?.let {
                    Box(Modifier.fillMaxSize().background(ai.appdna.sdk.core.StyleEngine.parseColor(it)))
                }
                "gradient" -> bg.gradient?.stops?.let { stops ->
                    if (stops.size >= 2) {
                        val colors = stops.map { ai.appdna.sdk.core.StyleEngine.parseColor(it.color) }
                        val brush = when (bg.gradient.type) {
                            "radial" -> Brush.radialGradient(colors)
                            else -> {
                                // SPEC-419 — span the FULL bounds. The old fixed `*1000f` offset
                                // made the gradient COMPLETE at ~1000px (≈42% of a ~2400px screen)
                                // then show its flat end-colour below — the user-reported "gradient
                                // ends earlier than the screen". iOS uses relative UnitPoints (0→1)
                                // so it spans the whole height. Compose's vertical/horizontal
                                // gradients auto-span the draw bounds; use them for cardinal angles
                                // (the only ones authored in practice) + a large-offset fallback.
                                val angle = ((bg.gradient.angle ?: 180.0) % 360 + 360) % 360
                                when (angle) {
                                    180.0 -> Brush.verticalGradient(colors)
                                    0.0 -> Brush.verticalGradient(colors.reversed())
                                    90.0 -> Brush.horizontalGradient(colors)
                                    270.0 -> Brush.horizontalGradient(colors.reversed())
                                    else -> {
                                        val rads = Math.toRadians(angle)
                                        val dx = sin(rads).toFloat()
                                        val dy = -cos(rads).toFloat()
                                        Brush.linearGradient(
                                            colors = colors,
                                            start = Offset((0.5f - dx / 2f) * 4000f, (0.5f - dy / 2f) * 4000f),
                                            end = Offset((0.5f + dx / 2f) * 4000f, (0.5f + dy / 2f) * 4000f),
                                        )
                                    }
                                }
                            }
                        }
                        Box(Modifier.fillMaxSize().background(brush))
                    }
                }
                "image" -> {
                    Box(Modifier.fillMaxSize()) {
                        // SPEC-401-A R15 — read `image_fit` from DTO instead
                        // of hardcoding Crop. Matches iOS imageFit() helper at
                        // StyleEngine.swift:337 — "fit" → letterbox, "fill"/
                        // "cover"/null → fill.
                        val imageScale = when (bg.image_fit?.lowercase()) {
                            "fit", "contain" -> androidx.compose.ui.layout.ContentScale.Fit
                            else -> androidx.compose.ui.layout.ContentScale.Crop
                        }
                        ai.appdna.sdk.core.NetworkImage(
                            url = bg.image_url,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = imageScale,
                        )
                        // SPEC-401-A R15 — overlay opacity per iOS
                        // StyleEngine.swift:343-358. Pure black/white default
                        // hexes (editor default) get 0.4 opacity so the image
                        // remains visible; explicit overlay_opacity wins.
                        bg.overlay?.takeIf { it.isNotEmpty() && it.lowercase() != "transparent" }?.let { overlay ->
                            val op = bg.overlay_opacity ?: run {
                                val l = overlay.lowercase()
                                if (l == "#000000" || l == "#ffffff" || l == "000000" || l == "ffffff") 0.4 else null
                            }
                            val color = ai.appdna.sdk.core.StyleEngine.parseColor(overlay)
                            val tinted = if (op != null) color.copy(alpha = op.toFloat()) else color
                            Box(Modifier.fillMaxSize().background(tinted))
                        }
                    }
                }
                "lottie" -> {
                    // SPEC-401-A R15 — full-screen Lottie background
                    // (iOS StyleEngine.swift:361-392). Without this branch
                    // a console-authored Lottie background rendered as a
                    // transparent / theme-default screen on Android.
                    Box(Modifier.fillMaxSize()) {
                        bg.lottie_url?.let { url ->
                            // LottieBlockView constrains height; for a
                            // full-screen background pass screenHeightDp.
                            val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.toFloat()
                            ai.appdna.sdk.core.LottieBlockView(
                                block = ai.appdna.sdk.core.LottieBlock(
                                    lottie_url = url,
                                    lottie_json = null,
                                    autoplay = true,
                                    loop = bg.animation_loop ?: true,
                                    speed = 1.0f,
                                    width = null,
                                    height = screenHeight,
                                    alignment = "center",
                                    play_on_scroll = null,
                                    play_on_tap = null,
                                    color_overrides = null,
                                ),
                            )
                        }
                        bg.overlay?.takeIf { it.isNotEmpty() && it.lowercase() != "transparent" }?.let { overlay ->
                            val op = bg.overlay_opacity ?: run {
                                val l = overlay.lowercase()
                                if (l == "#000000" || l == "#ffffff" || l == "000000" || l == "ffffff") 0.4 else null
                            }
                            val color = ai.appdna.sdk.core.StyleEngine.parseColor(overlay)
                            val tinted = if (op != null) color.copy(alpha = op.toFloat()) else color
                            Box(Modifier.fillMaxSize().background(tinted))
                        }
                    }
                }
                "rive" -> {
                    // SPEC-401-A R35 — full-screen Rive background (iOS
                    // StyleEngine.swift:393-425). Mirrors the "lottie" branch.
                    Box(Modifier.fillMaxSize()) {
                        bg.rive_url?.let { url ->
                            val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.toFloat()
                            ai.appdna.sdk.core.RiveBlockView(
                                block = ai.appdna.sdk.core.RiveBlock(
                                    rive_url = url,
                                    autoplay = true,
                                    height = screenHeight,
                                    alignment = "center",
                                ),
                            )
                        }
                        bg.overlay?.takeIf { it.isNotEmpty() && it.lowercase() != "transparent" }?.let { overlay ->
                            val op = bg.overlay_opacity ?: run {
                                val l = overlay.lowercase()
                                if (l == "#000000" || l == "#ffffff" || l == "000000" || l == "ffffff") 0.4 else null
                            }
                            val color = ai.appdna.sdk.core.StyleEngine.parseColor(overlay)
                            val tinted = if (op != null) color.copy(alpha = op.toFloat()) else color
                            Box(Modifier.fillMaxSize().background(tinted))
                        }
                    }
                }
                else -> {}
            }
        }

        when (variant) {
            "image_fullscreen" -> {
                Box(Modifier.fillMaxSize()) {
                    // SPEC-419 — only render the variant image + its darkening overlay when an image
                    // is ACTUALLY authored. The nurrai steps all use layout_variant=image_fullscreen
                    // but have NO image_url (the bg is a gradient painted full-bleed by
                    // StepFullScreenBackground). The Transparent→Black0.7 overlay was firing anyway,
                    // darkening the BOTTOM of the CONTENT area (it's bounded to the safe-area content)
                    // → a banded dark "gap" below the CTA vs the uniform navy in the nav region (the
                    // user-reported bottom band). iOS only darkens over a real image; no image → no
                    // overlay → the gradient stays uniform top-to-bottom.
                    if (effectiveConfig.image_url != null) {
                        ai.appdna.sdk.core.NetworkImage(
                            url = effectiveConfig.image_url,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                        // Gradient overlay (legibility scrim over the image)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                                        startY = 300f,
                                    )
                                )
                        )
                    }
                    // SPEC-419 — let ThreeZoneBlockLayout own ALL vertical positioning
                    // (top/center/bottom zones), matching iOS which renders the bg full-screen
                    // and positions the zone layout normally. The old `Arrangement.Bottom` +
                    // 200dp top Spacer shoved a TOP-zone block (e.g. an injected select) ~200dp
                    // down, so the block iOS pins to the top rendered a third of the way down.
                    // The Box(weight = 1f) gives TZBL an explicitly BOUNDED height so its inner
                    // verticalScroll never measures to infinity (the crash the old wrapper
                    // guarded against) — without imposing any vertical offset.
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 20.dp), // SPEC-419 — horizontal owned by ThreeZoneBlockLayout (8%) so margins match iOS ~10.4% (was double-padded ~15% → headings wrapped more)
                    ) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            ThreeZoneBlockLayout(blocks = blocks, onAction = ::handleAction, toggleValues = toggleValues, inputValues = inputValues, loc = ::loc, responses = responses, hookData = hookData, currentStepIndex = currentStepIndex, totalSteps = totalSteps, onInteract = ::handleInteract, fieldConfigOverrides = fieldConfigOverrides)
                        }
                    }
                }
            }
            "image_split" -> {
                Row(Modifier.fillMaxSize()) {
                    // Image side (40%)
                    ai.appdna.sdk.core.NetworkImage(
                        url = effectiveConfig.image_url,
                        modifier = Modifier.weight(0.4f).fillMaxHeight(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                    // Content side (60%) — TZBL provides its own internal
                    // verticalScroll (Hotfix-1.0.33 — was nested-scroll crash).
                    Column(
                        modifier = Modifier
                            .weight(0.6f)
                            .padding(16.dp),
                    ) {
                        ThreeZoneBlockLayout(blocks = blocks, onAction = ::handleAction, toggleValues = toggleValues, inputValues = inputValues, loc = ::loc, responses = responses, hookData = hookData, currentStepIndex = currentStepIndex, totalSteps = totalSteps, onInteract = ::handleInteract, fieldConfigOverrides = fieldConfigOverrides)
                    }
                }
            }
            "image_bottom" -> {
                // Hotfix-1.0.33 — outer scroll removed; TZBL owns scrolling.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 20.dp), // SPEC-419 — horizontal owned by TZBL (8%) → margins match iOS ~10.4%
                ) {
                    ThreeZoneBlockLayout(blocks = blocks, onAction = ::handleAction, toggleValues = toggleValues, inputValues = inputValues, loc = ::loc, responses = responses, hookData = hookData, currentStepIndex = currentStepIndex, totalSteps = totalSteps, onInteract = ::handleInteract, fieldConfigOverrides = fieldConfigOverrides)
                    Spacer(Modifier.height(16.dp))
                    ai.appdna.sdk.core.NetworkImage(
                        url = effectiveConfig.image_url,
                        modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                }
            }
            "image_top" -> {
                // Hotfix-1.0.33 — outer scroll removed; TZBL owns scrolling.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 20.dp), // SPEC-419 — horizontal owned by TZBL (8%) → margins match iOS ~10.4%
                ) {
                    ai.appdna.sdk.core.NetworkImage(
                        url = effectiveConfig.image_url,
                        modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                    Spacer(Modifier.height(16.dp))
                    ThreeZoneBlockLayout(blocks = blocks, onAction = ::handleAction, toggleValues = toggleValues, inputValues = inputValues, loc = ::loc, responses = responses, hookData = hookData, currentStepIndex = currentStepIndex, totalSteps = totalSteps, onInteract = ::handleInteract, fieldConfigOverrides = fieldConfigOverrides)
                }
            }
            else -> { // no_image
                // Hotfix-1.0.33 — outer scroll removed; TZBL owns scrolling.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 20.dp), // SPEC-419 — horizontal owned by TZBL (8%) → margins match iOS ~10.4%
                ) {
                    ThreeZoneBlockLayout(blocks = blocks, onAction = ::handleAction, toggleValues = toggleValues, inputValues = inputValues, loc = ::loc, responses = responses, hookData = hookData, currentStepIndex = currentStepIndex, totalSteps = totalSteps, onInteract = ::handleInteract, fieldConfigOverrides = fieldConfigOverrides)
                }
            }
        }

        // Skip button at bottom
        if (onSkip != null) {
            TextButton(
                onClick = onSkip,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
            ) {
                // SPEC-401-A R52 (Lens A R51 #20, P3) — Skip 15sp matching iOS .subheadline.
                Text("Skip", fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            }
        }

        // SPEC-401-A R35 — in-app validation pill (Lens C #4). Mirrors iOS
        // bottom-pill at OnboardingRenderer.swift:1383-1402 — `.background(
        // Color.black.opacity(0.8))`, `.cornerRadius(12)`, slide+fade
        // transition. Replaces system Toast which inherited OEM theming.
        AnimatedVisibility(
            visible = validationMessage != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 100.dp),
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(
                    text = validationMessage ?: "",
                    color = Color.White,
                    // SPEC-401-A R54 (Lens A R54 #3, P3) — 14→15sp Medium
                    // matching iOS .subheadline.weight(.medium) at
                    // OnboardingRenderer.swift:1393-1394.
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    // SPEC-401-A R37 (Lens C #4) — assertive announce so
                    // screen-reader users know why Continue didn't advance.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
        }

        // SPEC-421 — settings fallback for a permanently-denied permission (opt-in via
        // `show_settings_fallback_on_denied`). Both choices advance, mirroring the iOS alert.
        permSettingsFallback?.let { fallback ->
            AlertDialog(
                onDismissRequest = {
                    permSettingsFallback = null
                    advancePermissionStep(fallback.type)
                },
                title = { Text("Permission needed") },
                text = { Text("You can enable this in Settings.") },
                confirmButton = {
                    TextButton(onClick = {
                        permissionManager.openAppSettings()
                        permSettingsFallback = null
                        advancePermissionStep(fallback.type)
                    }) { Text(fallback.label) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        permSettingsFallback = null
                        advancePermissionStep(fallback.type)
                    }) { Text("Continue") }
                },
            )
        }
    }
}

/** SPEC-421 — identifies a pending "Open Settings" affordance for a denied permission. */
private data class PermissionSettingsFallback(val type: String, val label: String)

@Composable
private fun WelcomeStep(config: StepConfig, onNext: (Map<String, Any>?) -> Unit) {
    // SPEC-401-A R55 (Lens A R53 #3, P2) — outer Column uses
    // verticalArrangement = spacedBy(24.dp) matching iOS
    // WelcomeStepView.swift VStack(spacing: 24). All explicit
    // Spacer(Modifier.height(...)) drops are folded into the spacing
    // so iOS and Android compute identical inter-element gaps.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // SPEC-401-A — flexible top spacer instead of fixed 48dp.
        // iOS uses Spacer() top + Spacer() between image-block and
        // CTA so the content vertically centers on tall screens.
        Spacer(Modifier.weight(1f))
        // SPEC-401-A — legacy step image_url. Mirrors iOS WelcomeStepView
        // .swift:13-21 — 280×280dp ContentScale.Fit (matches scaledToFit)
        // with rounded 16dp corners.
        config.image_url?.takeIf { it.isNotBlank() }?.let { url ->
            // SPEC-401-A R48 (Lens A #3) — match iOS WelcomeStepView.swift:20
            // `.frame(maxWidth: 280, maxHeight: 280)`. Was fixed 280dp square
            // → non-square images letterboxed.
            ai.appdna.sdk.core.NetworkImage(
                url = url,
                contentDescription = null,
                modifier = androidx.compose.ui.Modifier
                    .sizeIn(maxWidth = 280.dp, maxHeight = 280.dp)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
        // SPEC-401-A R59 (Lens A P3 #4) — iOS WelcomeStepView wraps title +
        // subtitle in an inner `VStack(spacing: 12)` while the outer is
        // `VStack(spacing: 24)`. Without this nesting the title↔subtitle gap
        // was 24dp on Android vs 12pt on iOS — 2x too wide. Inner Column
        // becomes a single child of the outer Column so outer 24dp spacing
        // applies above and below the pair, matching iOS.
        if (config.title != null || config.subtitle != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                config.title?.let {
                    // SPEC-401-A R48 (Lens A #2) — iOS .largeTitle ≈ 34pt
                    // (was 28sp). SPEC-070-A J.11 — step title is the
                    // screen heading for a11y. Lens A #4 — horizontal
                    // padding 32dp matching iOS .padding(.horizontal, 32)
                    // (WelcomeStepView.swift:45).
                    Text(
                        text = it.interpolated(),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 32.dp)
                            .semantics { heading() },
                    )
                }
                config.subtitle?.let {
                    // SPEC-401-A R48 (Lens A #4) — horizontal padding 32dp.
                    // SPEC-401-A R55 (Lens A R55 #7, P2) — 16→17sp matching
                    // iOS WelcomeStepView.swift:39-40 .body (~17pt).
                    Text(
                        text = it.interpolated(),
                        fontSize = 17.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }
        }
        // SPEC-401-A R9 — match iOS WelcomeStepView.swift:10,47 vertical
        // distribution: `Spacer()` top + `Spacer()` bottom so content
        // vertically centers on tall screens.
        Spacer(Modifier.weight(1f))
        // SPEC-401-A R48 (Lens A #1+#5) — CTA height 54dp matching iOS
        // .frame(height: 54); horizontal/bottom padding matching iOS
        // .padding(.horizontal, 24).padding(.bottom, 32).
        Button(
            onClick = { onNext(null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            // SPEC-401-A R10 — match iOS WelcomeStepView.swift:55 fixed
            // indigo `#6366F1` so the same flow renders the same CTA
            // color on both natives, regardless of host's Material theme
            // primary override.
            colors = ButtonDefaults.buttonColors(containerColor = ai.appdna.sdk.AppDNA.brandAccentColor())
        ) {
            Text(text = (config.cta_text ?: "Get Started").interpolated(), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
    }
}

@Composable
private fun QuestionStep(config: StepConfig, onNext: (Map<String, Any>?) -> Unit) {
    val selectedOptions = remember { mutableStateListOf<String>() }
    val isMulti = config.selection_mode == SelectionMode.MULTI

    // SPEC-401-A R52 (Lens A R52 #1+#5 P0/P1) — split into title (top
    // pinned) + scrollable options (Box weight=1f) + CTA (bottom pinned)
    // mirroring iOS QuestionStepView.swift:53-103. Was wrapping the whole
    // step in verticalScroll which made user scroll past option list to
    // reach the Continue button on long question lists. iOS keeps both
    // the title and CTA visible with only the option grid scrolling.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 24.dp),
    ) {
        config.title?.let {
            // SPEC-070-A J.11 — question step title is the screen heading.
            // SPEC-401-A R49 (Lens A #7) — title 24→22sp matching iOS
            // QuestionStepView.swift:57 `.font(.title2.bold())` (~22pt).
            Text(
                text = it.interpolated(),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
        }
        // SPEC-401-A R55 (Lens A R55 #2, P2) — DROP config.subtitle from
        // QuestionStep. iOS QuestionStepView.swift:53-103 renders only
        // title + options + CTA — no subtitle. Was an Android-only extra
        // line that diverged from iOS canon.
        Spacer(Modifier.height(24.dp))

        // SPEC-401-A R49 (Lens A #6) — 2-column grid mirrors iOS
        // QuestionStepView.swift:63-78 manual grid (`rowCount =
        // (count + 1) / 2`, 12pt spacing). Was a single-column vertical
        // list — same JSON rendered visually different layouts on
        // each platform. When a row has a lone option, the second slot
        // is filled with a transparent weight Spacer so the populated
        // card keeps half-width.
        // SPEC-401-A R49 (Lens C #6, P1) — element_style passthrough.
        // iOS QuestionStepView.swift:147-154 derives accent + container
        // colors from element_style. Without this Android renders every
        // brand-overridden question step in default Material primary.
        val accentColor = config.element_style?.border?.color
            ?.let { ai.appdna.sdk.core.StyleEngine.parseColor(it) }
            ?: config.element_style?.background?.color
                ?.let { ai.appdna.sdk.core.StyleEngine.parseColor(it) }
            ?: ai.appdna.sdk.AppDNA.brandAccentColor()
        // SPEC-401-A R51 (Lens A #12, P2) — iOS QuestionStepView.swift:24-27
        // piggy-backs `element_style.shadow.color` as the selected_bg override
        // before falling back to accent×0.15. Authored payloads with that key
        // were dropped on Android.
        val selectedBgColor = config.element_style?.shadow?.color
            ?.let { ai.appdna.sdk.core.StyleEngine.parseColor(it) }
            ?: accentColor.copy(alpha = 0.15f)
        val optionBgColor = config.element_style?.background?.overlay
            ?.let { ai.appdna.sdk.core.StyleEngine.parseColor(it) }
            ?: config.element_style?.background?.color
                ?.let { ai.appdna.sdk.core.StyleEngine.parseColor(it).copy(alpha = 0.1f) }
            ?: MaterialTheme.colorScheme.surface
        // SPEC-401-A R51 (Lens A #11, P2) — element_style.text_style.color
        // resolves the option label color. iOS QuestionStepView.swift:48-51,
        // 132, 139. Brand-themed payloads with text_style.color set kept
        // showing default Material on Android.
        val optionTextColor = config.element_style?.text_style?.color
            ?.let { ai.appdna.sdk.core.StyleEngine.parseColor(it) }
            ?: Color.Unspecified
        // SPEC-401-A R51 (Lens A #13, P2) — option corner_radius from
        // element_style.corner_radius. iOS QuestionStepView.swift:111
        // defaults to 12pt. Was hardcoded 12dp.
        val optionCornerRadius = (config.element_style?.corner_radius ?: 12.0).dp
        // SPEC-401-A R55 (Lens A R55 #5, P3) — unselected border color iOS
        // chain at QuestionStepView.swift:42-45: when border.color set,
        // use that × 0.3; else `Color.white.opacity(0.15)`. Was deriving
        // from accentColor which incorrectly substituted background.color
        // when only background was authored.
        val unselectedBorderColor = config.element_style?.border?.color
            ?.let { ai.appdna.sdk.core.StyleEngine.parseColor(it).copy(alpha = 0.3f) }
            ?: Color.White.copy(alpha = 0.15f)
        // SPEC-401-A R52 (Lens A R52 #1 P0) — wrap option grid in
        // Box(weight=1f).verticalScroll so the title stays pinned top and
        // the CTA pinned bottom (mirrors iOS QuestionStepView.swift:65-78
        // ScrollView around only the option list).
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
        config.options?.chunked(2)?.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { option ->
                    val isSelected = selectedOptions.contains(option.id)
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 80.dp)
                            .border(
                                if (isSelected) 2.dp else 1.dp,
                                if (isSelected) accentColor else unselectedBorderColor,
                                RoundedCornerShape(optionCornerRadius),
                            )
                            .clickable {
                                if (isMulti) {
                                    if (isSelected) selectedOptions.remove(option.id) else selectedOptions.add(option.id)
                                } else {
                                    selectedOptions.clear(); selectedOptions.add(option.id)
                                }
                            },
                        shape = RoundedCornerShape(optionCornerRadius),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) selectedBgColor else optionBgColor,
                        ),
                    ) {
                        // SPEC-401-A R50 (Lens A #3, P0) — VStack stacked layout.
                        // iOS QuestionStepView.swift:124-143 places icon on TOP
                        // (32pt), label center, optional subtitle below. Min
                        // height 80. Was Row(icon-left, 20sp) — same JSON
                        // produced visually different cards on each platform.
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            option.icon?.let { Text(text = it, fontSize = 32.sp) }
                            // SPEC-401-A R51 (Lens A #10, P2 + #11) — 15sp medium
                            // matches iOS .subheadline.weight(.medium); pass through
                            // optionTextColor from element_style.text_style.color.
                            Text(
                                text = option.label.interpolated(),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = optionTextColor,
                                textAlign = TextAlign.Center,
                            )
                            option.subtitle?.takeIf { it.isNotEmpty() }?.let { sub ->
                                Text(
                                    text = sub.interpolated(),
                                    // SPEC-401-A R55 (Lens A R55 #3, P3) — 13→12sp
                                    // matching iOS QuestionStepView.swift:138 .caption.
                                    fontSize = 12.sp,
                                    color = if (optionTextColor != Color.Unspecified)
                                        optionTextColor.copy(alpha = 0.65f)
                                    else
                                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        }
        // CTA pinned bottom outside the scroll, mirrors iOS Spacer() +
        // Button at QuestionStepView.swift:81,98-103.
        Button(
            // SPEC-070-A finalization B4 P2 — include `selection_mode` in the
            // CTA payload (iOS QuestionStepView.swift:86-90 emits
            // {"selected": [...], "selection_mode": "single"|"multi"}). Hosts
            // that branch on the mode (e.g. multi-select rendering vs radio)
            // were missing this field on Android.
            onClick = {
                onNext(
                    mapOf(
                        "selected" to selectedOptions.toList(),
                        "selection_mode" to if (isMulti) "multi" else "single",
                    )
                )
            },
            enabled = selectedOptions.isNotEmpty(),
            // SPEC-401-A R48 (Lens C #1) — CTA height 52→54dp matching iOS.
            // SPEC-401-A R52 (Lens A R52 #5, P1) — top spacer 16dp instead
            // of 24dp wrapper-padding. Bottom 32dp matches iOS.
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 32.dp).height(54.dp),
            // SPEC-401-A R58 (Lens A P1 #1) — read element_style.corner_radius
            // matching iOS QuestionStepView.swift:98
            // `.cornerRadius(CGFloat(config.element_style?.corner_radius ?? 14))`.
            // Sibling option cards already honor it via `optionCornerRadius`
            // (line 3053). Was hardcoded 14dp — author setting
            // element_style.corner_radius=24 got 24dp option cards next to
            // a 14dp CTA on Android, fully matched 24dp on iOS.
            shape = RoundedCornerShape((config.element_style?.corner_radius ?: 14.0).dp),
            // SPEC-401-A R55 (Lens A R55 #4, P1) — use accentColor (already
            // computed at line 2945-2949) so element_style.border.color or
            // element_style.background.color overrides reach the CTA.
            // iOS QuestionStepView.swift:97 uses the same accent chain.
            // Was hardcoded #6366F1 — brand-themed flows lost their CTA color.
            colors = ButtonDefaults.buttonColors(
                containerColor = accentColor,
                // SPEC-401-A R64 (Lens A P3 #4) — match iOS QuestionStepView
                // .swift:97 `selectedIds.isEmpty ? Color.gray : accentColor`.
                // R59 wrongly applied FormStepView's accent×0.4 pattern here;
                // QuestionStep uses iOS Color.gray (system grey adaptive),
                // not accent-derived alpha. Brand-themed flows now show a
                // neutral grey CTA when disabled (matches iOS), reserving
                // the brand color for the enabled state.
                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            ),
        ) {
            Text(text = (config.cta_text ?: "Continue").interpolated(), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        // SPEC-401-A R52 — Spacer.height(32) dropped; CTA modifier already
        // pads bottom=32dp and is now outside the verticalScroll.
    }
}

@Composable
private fun ValuePropStep(config: StepConfig, onNext: (Map<String, Any>?) -> Unit) {
    // SPEC-401-A R52 — own fillMaxSize + 24dp horizontal padding mirroring
    // iOS ValuePropStepView.swift:9-56. Title gets top=24dp padding directly
    // since the Column already pads horizontal.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        config.title?.let {
            // SPEC-401-A R48 (Lens A #2 polish) — iOS .title2.bold() ≈ 22pt.
            // SPEC-401-A R50 (Lens A #5, P1) — iOS .padding(.top, 24)
            // (horizontal already supplied by Column).
            Text(
                text = it.interpolated(),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .semantics { heading() },
            )
        }
        // SPEC-401-A R59 (Lens A P2 #3) — title↔items 24dp gap matching iOS
        // ValuePropStepView.swift:9 outer `VStack(spacing: 24)`. Outer Column
        // has no verticalArrangement (defaults to 0); inserting a Spacer
        // here preserves the existing CTA spacing (Button already pads
        // top=32dp) instead of adding spacedBy on the outer column.
        Spacer(Modifier.height(24.dp))
        // SPEC-401-A R48 (Lens A #6) — wrap items in scrollable Column so
        // long bullet lists don't clip on small screens. iOS uses ScrollView
        // (ValuePropStepView.swift:19-41).
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            config.items?.forEach { item ->
                // SPEC-401-A R48 (Lens A #9) — horizontal padding 24dp matching
                // iOS .padding(.horizontal, 24). Lens A #7 — icon 36sp inside
                // 48dp Box for stable column alignment regardless of emoji
                // intrinsic width.
                // SPEC-401-A R52 (Lens A R52 #2, P1) — drop the per-item
                // horizontal=24dp padding. Outer Column already pads
                // horizontal=24 (line 3091) so the duplicate stacked
                // 48dp horizontal-pad on items vs iOS 24pt.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = item.icon, fontSize = 36.sp)
                    }
                    Spacer(Modifier.width(16.dp))
                    // SPEC-401-A R48 (Lens A #8) — title↔subtitle 4dp gap
                    // matching iOS VStack(spacing: 4).
                    // SPEC-401-A R54 (Lens A R54 #5, P2) — title 16→17sp
                    // (.headline), subtitle 14→15sp (.subheadline) per
                    // iOS ValuePropStepView.swift:29,32. weight(1f) so
                    // multi-line text wraps to remaining row width
                    // (iOS HStack uses Spacer() trailing — same effect).
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(text = item.title.interpolated(), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Text(text = item.subtitle.interpolated(), fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                    }
                }
            }
        }
        Button(
            onClick = { onNext(null) },
            // SPEC-401-A R48 (Lens C #1) — CTA height 52→54dp matching iOS.
            // SPEC-401-A R52 (Lens A R52 #4, P1) — top=32dp + bottom=32dp
            // padding mirroring iOS ValuePropStepView.swift:55
            // .padding(.bottom, 32). Was Spacer(32) only above CTA.
            modifier = Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 32.dp).height(54.dp),
            shape = RoundedCornerShape(14.dp),
            // SPEC-401-A R12 — match iOS ValuePropStepView.swift:51 fixed
            // indigo `#6366F1` so flows render the same CTA color on both
            // natives regardless of host's MaterialTheme primary override.
            colors = ButtonDefaults.buttonColors(containerColor = ai.appdna.sdk.AppDNA.brandAccentColor())
        ) {
            Text(text = (config.cta_text ?: "Continue").interpolated(), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
    }
}

@Composable
private fun CustomStep(config: StepConfig, onNext: (Map<String, Any>?) -> Unit) {
    // SPEC-401-A R52 — own fillMaxSize + Spacer.weight top/bottom for
    // vertical centering (iOS CustomStepView.swift:9-11,43 VStack(spacing:24)
    // { Spacer(); title; subtitle; image; Spacer(); button }).
    // SPEC-401-A R55 (Lens A R53 #3, P2) — outer spacedBy(24) matching
    // iOS VStack(spacing: 24); drops explicit Spacer(24)s.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Spacer(Modifier.weight(1f))
        config.title?.let {
            // SPEC-070-A J.11 — custom step title is the screen heading.
            // SPEC-401-A R50 (Lens A #4, P1) — title 24→22sp matching iOS
            // CustomStepView.swift:9-56 .title2.bold() (~22pt) + horizontal
            // padding 24.
            Text(
                text = it.interpolated(),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .semantics { heading() },
            )
        }
        config.subtitle?.let {
            // Title→subtitle 24dp gap supplied by outer spacedBy(24).
            // SPEC-401-A R50 (Lens A #4) — subtitle horizontal padding 32 per iOS.
            // SPEC-401-A R51 (Lens A #8, P2) — 15→17sp matching iOS .body.
            Text(
                text = it.interpolated(),
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        // SPEC-401-A — legacy step image_url. Mirrors iOS CustomStepView
        // .swift:29-41 — image renders AFTER title+subtitle, ContentScale
        // .Fit (matches scaledToFit so non-square assets are letterboxed
        // not cropped), and NO rounded corners (iOS uses no clip-shape).
        // Affects custom/info/permission step types.
        // SPEC-401-A R50 (Lens A #4) — sizeIn(maxWidth=280, maxHeight=280)
        // matches iOS .frame(maxWidth: 280, maxHeight: 280) so non-square
        // assets aren't square-cropped.
        config.image_url?.takeIf { it.isNotBlank() }?.let { url ->
            // SPEC-401-A R64 (Lens A P2 #2) — drop redundant Spacer(24).
            // Outer Column already has Arrangement.spacedBy(24.dp); the
            // extra Spacer stacks 3×24=72dp gap between subtitle and
            // image vs iOS 24pt (CustomStepView.swift:9-41 outer
            // VStack(spacing:24)). R55 cleanup comment says "drops
            // explicit Spacer(24)s" — this one was missed.
            ai.appdna.sdk.core.NetworkImage(
                url = url,
                contentDescription = null,
                modifier = androidx.compose.ui.Modifier
                    .sizeIn(maxWidth = 280.dp, maxHeight = 280.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
        // SPEC-401-A R52 — Spacer.weight before CTA so the title+subtitle+
        // image group vertically centers between top safe area and CTA on
        // tall screens (iOS VStack puts a Spacer() between image + button).
        Spacer(Modifier.weight(1f))
        Button(
            onClick = { onNext(null) },
            // SPEC-401-A R48 (Lens C #1) — CTA height 52→54dp matching iOS.
            // SPEC-401-A R50 (Lens A #4, P1) — horizontal padding 24 + bottom
            // padding 32 matching iOS CustomStepView.swift:51-52.
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            // SPEC-401-A R12 — match iOS CustomStepView.swift:51 fixed
            // indigo `#6366F1`. Affects custom/info/permission steps.
            colors = ButtonDefaults.buttonColors(containerColor = ai.appdna.sdk.AppDNA.brandAccentColor())
        ) {
            Text(text = (config.cta_text ?: "Continue").interpolated(), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
    }
}
