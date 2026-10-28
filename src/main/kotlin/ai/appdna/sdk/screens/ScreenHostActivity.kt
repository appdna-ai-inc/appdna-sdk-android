package ai.appdna.sdk.screens

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import ai.appdna.sdk.core.PresentationCoordinator
import ai.appdna.sdk.core.StyleEngine
import ai.appdna.sdk.core.entryAnimation
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Host Activity for SDUI screens presented via [AppDNA.showScreen].
 *
 * Mirrors iOS `Sources/AppDNASDK/Screens/ScreenPresenter.swift`, which wraps
 * a SwiftUI `ScreenRenderer` in a `UIHostingController` and presents it via
 * `UIViewController.present(...)` honoring `config.presentation` (fullscreen
 * / pageSheet / bottom_sheet) and `config.transition` (slide_up / slide_left
 * / fade / none).
 *
 * Android limits us to per-Activity transitions; we use the standard
 * `overridePendingTransition` after `startActivity` to honor the requested
 * style. Compose handles the actual section rendering through
 * [SectionRegistry] — the same registry used by [AppDNAScreenSlot].
 */
class ScreenHostActivity : ComponentActivity() {

    private var launchToken: String? = null
    private var dispatchedDismiss: Boolean = false
    private var snapshotOnDismiss: ((ScreenResult) -> Unit)? = null
    private var snapshotScreenId: String? = null
    private var flowToken: String? = null
    private var flowManager: FlowManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        // 🔴 FLOW MODE. `ScreenManager.showFlow` used to emit `flow_started`, hand the caller an
        // empty `FlowResult`, emit `flow_abandoned` and return — rendering NOTHING, while iOS
        // (`Screens/ScreenManager.swift:230`) built a real FlowManager and presented it. This branch
        // is the Android counterpart of `ScreenPresenter.presentFlow(flowManager:)`: it renders the
        // flow's CURRENT screen through the exact same body the single-screen path uses, and routes
        // every section action into `FlowManager.handleAction` so navigation rules, `set_response`
        // and the completed-vs-abandoned outcome are the REAL ones.
        intent.getStringExtra(EXTRA_FLOW_TOKEN)?.let { token ->
            startFlowMode(token)
            return
        }

        val screenId = intent.getStringExtra(EXTRA_SCREEN_ID) ?: run {
            finish()
            return
        }
        val token = intent.getStringExtra(EXTRA_LAUNCH_TOKEN)
        val slot = token?.let { activeLaunches[it] } ?: run {
            // Process-death recovery.
            // Mirrors PaywallActivity: if the static slot is gone but the OS
            // recreated us from `savedInstanceState`, fire a synthetic
            // `screen_dismissed` and onScreenDismissed delegate so analytics
            // + host stays consistent.
            if (savedInstanceState != null) {
                try {
                    // Process-death recovery: in-memory `onDismiss` closure is gone.
                    // Emit analytics so dashboards see the dismissal; host delegate
                    // re-fire is impossible (closure not serializable).
                    AppDNA.track("screen_dismissed", mapOf(
                        "screen_id" to screenId,
                        "reason" to "process_death",
                        "duration_ms" to 0,
                    ))
                } catch (e: Throwable) {
                    Log.warning { "ScreenHost process-death recovery failed: ${e.message}" }
                }
            }
            finish()
            return
        }
        launchToken = token
        snapshotOnDismiss = slot.onDismiss
        snapshotScreenId = screenId
        val config = slot.config

        // Honor `config.transition`
        // via overridePendingTransition. iOS uses UIModalTransitionStyle on
        // ScreenPresenter; Android equivalent is the per-Activity enter/exit
        // anim pair applied immediately after the Intent fires. Falls back to
        // platform default ("none") when the value is unknown or missing.
        // `slide_up` now uses custom
        // R.anim.appdna_slide_up_in (vertical translateY 100%→0) matching
        // iOS UIModalTransitionStyle.coverVertical at
        // ScreenPresenter.swift:80. Stock `slide_in_left` is horizontal —
        // visible cross-platform divergence.
        // Unknown / null transition defaults to slide_up
        // matching iOS `default: return .coverVertical` at
        // ScreenPresenter.swift:84.
        @Suppress("DEPRECATION")
        when (config.transition) {
            "slide_up" -> overridePendingTransition(
                ai.appdna.sdk.R.anim.appdna_slide_up_in,
                android.R.anim.fade_out,
            )
            "slide_left" -> overridePendingTransition(
                android.R.anim.slide_in_left,
                android.R.anim.slide_out_right,
            )
            "fade" -> overridePendingTransition(
                android.R.anim.fade_in,
                android.R.anim.fade_out,
            )
            "none" -> overridePendingTransition(0, 0)
            else -> overridePendingTransition(
                ai.appdna.sdk.R.anim.appdna_slide_up_in,
                android.R.anim.fade_out,
            )
        }

        setContent {
            val isDark = isSystemInDarkTheme()
            MaterialTheme {
              androidx.compose.runtime.CompositionLocalProvider(
                ai.appdna.sdk.onboarding.LocalGoogleAttributionBackdrop provides
                    ai.appdna.sdk.onboarding.GoogleMapsAttribution.backdrop(ofScreen = config.background),
              ) {
                ScreenHostBody(
                    config = config,
                    isDark = isDark,
                    onDismiss = {
                        dispatchedDismiss = true
                        slot.onDismiss?.invoke(
                            ScreenResult(
                                screenId = screenId,
                                dismissed = true,
                                durationMs = (System.currentTimeMillis() - slot.startedAtMs).toInt(),
                            )
                        )
                        cleanup()
                    },
                )
              }
            }
        }
    }

    /**
     * Render a multi-screen flow. Mirrors iOS `ScreenPresenter.presentFlow(flowManager:)`
     * (ScreenPresenter.swift:57-76) — with the one thing iOS's snapshot-present cannot do: the body
     * re-composes when `FlowManager.currentScreenIndex` changes, so `next` / `back` / `navigate`
     * actually move the user through the flow.
     */
    private fun startFlowMode(token: String) {
        val slot = activeFlows[token] ?: run {
            // Process death: the in-memory FlowManager is gone. Nothing to render, nothing to
            // resume — leave without inventing a completion for the host.
            finish()
            return
        }
        flowToken = token
        val manager = slot.flowManager
        flowManager = manager
        // Terminal transition (complete OR dismiss) closes the host. `onComplete` is ScreenManager's
        // (analytics + the caller's callback); this hook is ours.
        manager.onFinished = { runOnUiThread { if (!isFinishing) finish() } }

        setContent {
            val isDark = isSystemInDarkTheme()
            MaterialTheme {
                val index = manager.currentScreenIndex.collectAsState().value
                val config = androidx.compose.runtime.remember(index) { manager.currentScreen }
                if (config == null) {
                    // The start screen's config was never cached — the flow cannot render.
                    androidx.compose.runtime.LaunchedEffect(Unit) { manager.dismissFlow() }
                } else {
                  androidx.compose.runtime.CompositionLocalProvider(
                    ai.appdna.sdk.onboarding.LocalGoogleAttributionBackdrop provides
                        ai.appdna.sdk.onboarding.GoogleMapsAttribution.backdrop(ofScreen = config.background),
                  ) {
                    ScreenHostBody(
                        config = config,
                        isDark = isDark,
                        onDismiss = { manager.dismissFlow() },
                        flowId = manager.flowConfig.id,
                        currentScreenIndex = index,
                        totalScreens = manager.flowConfig.screens.size,
                        responses = manager.responses,
                        onAction = { action -> manager.handleAction(action) },
                    )
                  }
                }
            }
        }
    }

    override fun onBackPressed() {
        // Flow mode: back walks the flow's own navigation stack; only a back press with nowhere to
        // go abandons the flow.
        flowManager?.let { manager ->
            if (manager.flowConfig.settings.allowBack && manager.canGoBack) {
                manager.handleAction(SectionAction.Back)
                return
            }
            if (!manager.flowConfig.settings.dismissEnabled) return
            manager.dismissFlow()
            return
        }
        // Honor `dismiss.enabled == false` for force-modal screens.
        val allowed = launchToken?.let { activeLaunches[it]?.config?.dismiss?.enabled } ?: true
        if (allowed == false) return
        dispatchedDismiss = true
        @Suppress("DEPRECATION")
        super.onBackPressed()
        snapshotOnDismiss?.invoke(
            ScreenResult(
                screenId = snapshotScreenId ?: "",
                dismissed = true,
            )
        )
        cleanup()
    }

    private fun cleanup() {
        // Pair the symmetric exit animation to
        // the entry transition so dismiss matches present. iOS
        // `modalTransitionStyle` (ScreenPresenter.swift:20) is symmetric;
        // Android requires explicit `overridePendingTransition` AFTER finish().
        val transition = launchToken?.let { activeLaunches[it]?.config?.transition }
        finish()
        @Suppress("DEPRECATION")
        when (transition) {
            "slide_up" -> overridePendingTransition(
                android.R.anim.fade_in,
                ai.appdna.sdk.R.anim.appdna_slide_down_out,
            )
            "slide_left" -> overridePendingTransition(
                android.R.anim.slide_in_left,
                android.R.anim.slide_out_right,
            )
            "fade" -> overridePendingTransition(
                android.R.anim.fade_in,
                android.R.anim.fade_out,
            )
            "none" -> overridePendingTransition(0, 0)
            else -> overridePendingTransition(
                android.R.anim.fade_in,
                ai.appdna.sdk.R.anim.appdna_slide_down_out,
            )
        }
    }

    override fun onDestroy() {
        // Flow backstop: the host went away (swipe-away / finish()) without the flow reaching a
        // terminal transition → that's an abandon. `FlowManager.finish()` is latched, so a flow that
        // already completed does NOT also emit `flow_abandoned` here.
        if (isFinishing && !isChangingConfigurations) {
            flowManager?.dismissFlow()
            flowToken?.let { activeFlows.remove(it) }
        }
        if (isFinishing && !dispatchedDismiss) {
            val cb = snapshotOnDismiss
            snapshotOnDismiss = null
            cb?.invoke(
                ScreenResult(
                    screenId = snapshotScreenId ?: "",
                    dismissed = true,
                )
            )
        }
        // Only purge slot on real dismissal, not config-change recreate.
        if (isFinishing && !isChangingConfigurations) {
            launchToken?.let { activeLaunches.remove(it) }
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SCREEN_ID = "appdna.screen.id"
        const val EXTRA_LAUNCH_TOKEN = "appdna.screen.launch_token"
        const val EXTRA_FLOW_ID = "appdna.flow.id"
        const val EXTRA_FLOW_TOKEN = "appdna.flow.launch_token"

        internal data class ScreenLaunchSlot(
            val config: ScreenConfig,
            val startedAtMs: Long,
            val onDismiss: ((ScreenResult) -> Unit)?,
        )

        internal data class FlowLaunchSlot(val flowManager: FlowManager)

        private val activeLaunches = java.util.concurrent.ConcurrentHashMap<String, ScreenLaunchSlot>()
        private val activeFlows = java.util.concurrent.ConcurrentHashMap<String, FlowLaunchSlot>()

        /** Test/inspection seam: the FlowManager a `launchFlow` handed to the host. */
        internal fun flowManagerForToken(token: String): FlowManager? = activeFlows[token]?.flowManager

        /**
         * Present a multi-screen flow. Android counterpart of iOS
         * `ScreenPresenter.presentFlow(flowManager:)`.
         */
        @JvmStatic
        internal fun launchFlow(context: Context, flowId: String, flowManager: FlowManager) {
            val token = java.util.UUID.randomUUID().toString()
            activeFlows[token] = FlowLaunchSlot(flowManager)
            val intent = Intent(context, ScreenHostActivity::class.java).apply {
                putExtra(EXTRA_FLOW_ID, flowId)
                putExtra(EXTRA_FLOW_TOKEN, token)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        /**
         * Clear all in-flight slot
         * captures so SDK shutdown doesn't leak ScreenConfig + onDismiss
         * closures. Called from `AppDNA.shutdown()`. Mirrors the same call
         * shape as PaywallActivity / SurveyActivity static-map cleanup.
         */
        @JvmStatic
        internal fun clearActiveLaunches() {
            activeLaunches.clear()
            activeFlows.clear()
        }

        @JvmStatic
        fun launch(
            context: Context,
            screenId: String,
            config: ScreenConfig,
            onDismiss: ((ScreenResult) -> Unit)? = null,
        ) {
            val token = java.util.UUID.randomUUID().toString()
            activeLaunches[token] = ScreenLaunchSlot(
                config = config,
                startedAtMs = System.currentTimeMillis(),
                onDismiss = onDismiss,
            )
            val intent = Intent(context, ScreenHostActivity::class.java).apply {
                putExtra(EXTRA_SCREEN_ID, screenId)
                putExtra(EXTRA_LAUNCH_TOKEN, token)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

@androidx.compose.runtime.Composable
private fun ScreenHostBody(
    config: ScreenConfig,
    isDark: Boolean,
    onDismiss: () -> Unit,
    // Flow mode (all defaulted → the single-screen path is unchanged). `onAction`, when supplied,
    // REPLACES the single-screen action handling with the flow's router (FlowManager.handleAction);
    // the veto gate in front of it is identical either way.
    flowId: String? = null,
    currentScreenIndex: Int = 0,
    totalScreens: Int = 1,
    responses: MutableMap<String, Any> = mutableMapOf(),
    onAction: ((SectionAction) -> Unit)? = null,
) {
    val activityContext = LocalContext.current
    val bgColor = config.background?.color?.let { StyleEngine.parseColor(it) }
        ?: MaterialTheme.colorScheme.background

    // Fire haptic + particle effect on
    // present, mirroring iOS ScreenRenderer.swift:82-90 (ConfettiOverlay) +
    // 101-106 (HapticEngine.trigger). DTOs were parsed at ScreenConfig.kt:37-38
    // but no render path consumed them.
    val currentView = androidx.compose.ui.platform.LocalView.current
    val showParticlesState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val showParticles = showParticlesState.value
    androidx.compose.runtime.LaunchedEffect(config.id) {
        if (config.haptic?.onPresent == true) {
            val hapticType = ai.appdna.sdk.core.HapticType.fromString(config.haptic.type)
            if (hapticType != null) {
                ai.appdna.sdk.core.HapticEngine.trigger(currentView, hapticType)
            }
        }
        if (config.particleEffect?.onPresent == true) {
            showParticlesState.value = true
        }
    }

    // Build a SectionContext that routes section actions for the host screen.
    // Mirrors the dispatch in AppDNAScreenSlot but supports `Dismiss` since
    // the host activity owns dismissal. Veto via ScreenManager.handleScreenAction
    // so host delegates can intercept actions on the full-screen surface too.
    val sectionContext = SectionContext(
        screenId = config.id,
        flowId = flowId,
        responses = responses,
        currentScreenIndex = currentScreenIndex,
        totalScreens = totalScreens,
        onAction = { action ->
            // Rich, iOS-parity action payload (`{type, <fields>}`)
            // so the cross-platform veto host reads `type` + payload fields.
            val payload = action.toActionMap()
            // Route through dispatchScreenAction so BOTH the
            // synchronous delegate veto AND the optional async wrapper-veto are
            // consulted before the action is performed. With no async veto
            // registered (native hosts) the `perform` lambda runs inline.
            ScreenManager.shared.dispatchScreenAction(config.id, payload) {
                if (onAction != null) {
                    onAction(action)
                    return@dispatchScreenAction
                }
                // Full standalone-screen action dispatch, mirroring iOS
                // ScreenManager.performAction. Previously only 6 of 16 verbs were handled and the
                // DEFAULT `next`/`back` CTA fell to `else -> {}`, leaving the user STUCK on the
                // screen. `next`/`back` on a standalone (non-flow) screen dismiss it (iOS :305-311).
                // Config-driven URLs are scheme-checked before the OS.
                fun openUri(raw: String) {
                    ai.appdna.sdk.core.URLSafety.sanitized(raw, activityContext)?.let { uri ->
                        try {
                            activityContext.startActivity(
                                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Throwable) { /* best-effort */ }
                    }
                }
                when (action) {
                    is SectionAction.Next, is SectionAction.Back, is SectionAction.Dismiss -> onDismiss()
                    is SectionAction.Navigate -> { onDismiss(); ScreenManager.shared.showScreen(action.screenId) }
                    is SectionAction.OpenURL -> openUri(action.url)
                    is SectionAction.OpenWebview -> openUri(action.url)
                    is SectionAction.DeepLink -> openUri(action.url)
                    is SectionAction.OpenAppSettings -> {
                        try {
                            activityContext.startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", activityContext.packageName, null),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Throwable) { /* best-effort */ }
                    }
                    is SectionAction.Share -> {
                        try {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, action.text)
                            }
                            activityContext.startActivity(
                                Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Throwable) { /* best-effort */ }
                    }
                    is SectionAction.ShowScreen -> ScreenManager.shared.showScreen(action.id)
                    is SectionAction.ShowPaywall -> action.id?.let { AppDNA.showPaywall(it) }
                    is SectionAction.ShowSurvey -> action.id?.let { AppDNA.showSurvey(it) }
                    is SectionAction.SubmitForm -> AppDNA.track(
                        "screen_response_submitted",
                        mapOf("screen_id" to config.id, "field_count" to action.data.size),
                    )
                    is SectionAction.Track -> AppDNA.track(action.event, action.properties)
                    is SectionAction.Haptic -> ai.appdna.sdk.core.HapticType.fromString(action.type)?.let {
                        ai.appdna.sdk.core.HapticEngine.trigger(currentView, it)
                    }
                    // Custom: host already notified via the veto gate; no built-in handling (iOS :379).
                    is SectionAction.Custom -> {}
                    else -> {}
                }
                // Emit screen_action HERE (in the standalone performer, after the veto gate
                // allowed the action AND the native dispatch above ran), matching iOS performAction:
                // ONCE, AFTER side-effects, only when BOTH the sync AND async vetoes allow (this perform
                // lambda runs only then). The veto gate (ScreenManager.handleScreenAction) no longer
                // emits it — that fired BEFORE the async veto (wrapper over-emit) and for inline slots
                // (which iOS does not emit for).
                AppDNA.track(
                    "screen_action",
                    mapOf("screen_id" to config.id, "action_type" to (payload["type"]?.toString() ?: "unknown")),
                )
            }
        },
    )
    val safeAreaModifier = if (config.layout.safeArea != false) {
        Modifier.windowInsetsPadding(WindowInsets.systemBars)
    } else Modifier

    val mainSections = config.sections.filter {
        it.type != "sticky_footer" && it.type != "paywall_sticky_footer"
    }
    val stickyFooters = config.sections.filter {
        it.type == "sticky_footer" || it.type == "paywall_sticky_footer"
    }
    val spacing = (config.layout.spacing ?: 12.0).dp

    // Presentation-mode container.
    // iOS ScreenPresenter routes via UIModalPresentationStyle:
    //   "fullscreen" → fillMaxSize + edge-to-edge
    //   "page_sheet" → top inset + rounded top + dim backdrop
    //   "bottom_sheet" → 70% height aligned bottom + grabber bar + dim backdrop
    // Android ScreenHostActivity uses Theme.Translucent for ALL modes, so the
    // visual differentiation lives in the Compose body's positioning + chrome.
    val presentation = config.presentation
    val isBottomSheet = presentation == "bottom_sheet"
    val isPageSheet = presentation == "page_sheet" || presentation == "modal"

    val outerModifier = Modifier
        .fillMaxSize()
        .let { mod ->
            // Dim backdrop for sheet presentations; fullscreen has no backdrop.
            if (isBottomSheet || isPageSheet) {
                mod.background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = androidx.compose.runtime.remember {
                            androidx.compose.foundation.interaction.MutableInteractionSource()
                        },
                        indication = null,
                        onClick = { if (config.dismiss?.enabled != false) onDismiss() },
                    )
            } else mod
        }

    val bodyAlignment = when {
        isBottomSheet -> Alignment.BottomCenter
        isPageSheet -> Alignment.Center
        else -> Alignment.TopStart
    }

    Box(
        modifier = outerModifier,
        contentAlignment = bodyAlignment,
    ) {
        // Inner container holds the actual screen content. For sheets this is
        // a sized child; for fullscreen it fills.
        val innerSheetShape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = if (isBottomSheet || isPageSheet) 16.dp else 0.dp,
            topEnd = if (isBottomSheet || isPageSheet) 16.dp else 0.dp,
            bottomStart = if (isPageSheet) 16.dp else 0.dp,
            bottomEnd = if (isPageSheet) 16.dp else 0.dp,
        )
        val innerModifier = when {
            isBottomSheet -> Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .clip(innerSheetShape)
                .background(bgColor)
                .clickable(
                    interactionSource = androidx.compose.runtime.remember {
                        androidx.compose.foundation.interaction.MutableInteractionSource()
                    },
                    indication = null,
                    onClick = {},
                )
                .then(safeAreaModifier)
            isPageSheet -> Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .clip(innerSheetShape)
                .background(bgColor)
                .clickable(
                    interactionSource = androidx.compose.runtime.remember {
                        androidx.compose.foundation.interaction.MutableInteractionSource()
                    },
                    indication = null,
                    onClick = {},
                )
                .then(safeAreaModifier)
            else -> Modifier
                .fillMaxSize()
                .background(bgColor)
                .then(safeAreaModifier)
        }
    Box(
        modifier = innerModifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Bottom-sheet grabber
            // affordance. iOS pageSheet + bottom_sheet show a grabber pill at
            // the top edge by default; Android needs to draw one explicitly
            // since we host in a translucent Activity, not a BottomSheetDialog.
            if (isBottomSheet) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp, 4.dp)
                            .background(
                                color = Color.Gray.copy(alpha = 0.4f),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                            ),
                    ) {}
                }
            }

            // Optional nav bar — title + close button.
            config.navBar?.let { nav ->
                val navBg = nav.backgroundColor?.let { StyleEngine.parseColor(it) }
                    ?: Color.Transparent
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(navBg)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (nav.showBack == true) {
                        TextButton(onClick = onDismiss) { Text("←") }
                    } else Spacer(Modifier.padding(horizontal = 20.dp))
                    nav.title?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                    if (nav.showClose == true || config.dismiss?.enabled == true) {
                        TextButton(onClick = onDismiss) { Text("✕") }
                    } else Spacer(Modifier.padding(horizontal = 20.dp))
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        if (config.layout.type == "fixed") mod
                        else mod.verticalScroll(rememberScrollState())
                    }
                    // #904 — the authored screen padding. iOS applies `config.layout.padding` to
                    // the scroll container in all three presentation modes (ScreenRenderer.swift
                    // :44, :53, :70); Android read `spacing` and `safeArea` out of the same object
                    // and never this, so an authored 20pt inset put the content flush to the edge
                    // on one platform and inset on the other.
                    .padding((config.layout.padding ?: 0.0).dp),
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                for (section in mainSections) {
                    val animType = section.entranceAnimation?.type
                    val animDur = section.entranceAnimation?.durationMs
                    if (animType != null && animType != "none") {
                        Box(modifier = Modifier.entryAnimation(animType, animDur)) {
                            SectionRegistry.Render(section, sectionContext)
                        }
                    } else {
                        SectionRegistry.Render(section, sectionContext)
                    }
                }
            }
        }
        if (stickyFooters.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                for (section in stickyFooters) {
                    SectionRegistry.Render(section, sectionContext)
                }
            }
        }
        // Confetti / particle overlay drawn last so it renders on top of all
        // sections + sticky footers. Mirrors iOS ScreenRenderer overlay layer.
        if (showParticles) {
            val pe = config.particleEffect
            if (pe != null) {
                ai.appdna.sdk.core.ConfettiOverlay(
                    effect = ai.appdna.sdk.core.ParticleEffect(
                        type = pe.type ?: "confetti",
                        trigger = "on_appear",
                        duration_ms = pe.durationMs ?: 2500,
                        intensity = pe.intensity ?: "medium",
                    ),
                    trigger = true,
                )
            }
        }
        } // close inner Box (sized presentation container)
    }
}
