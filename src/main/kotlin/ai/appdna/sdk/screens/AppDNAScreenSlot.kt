package ai.appdna.sdk.screens

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.core.AudienceRuleEvaluator
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.appdna.sdk.core.entryAnimation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

/**
 * A Composable that renders a server-driven screen's sections inline.
 * Growth teams assign screens to named slots from the console.
 *
 * Usage:
 * ```kotlin
 * @Composable
 * fun HomeScreen() {
 *     Column {
 *         AppDNAScreenSlot("home_hero")
 *         // ... app content ...
 *         AppDNAScreenSlot("home_bottom")
 *     }
 * }
 * ```
 */
@Composable
fun AppDNAScreenSlot(name: String) {
    var screenConfig by remember { mutableStateOf<ScreenConfig?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isEmpty by remember { mutableStateOf(false) }

    LaunchedEffect(name) {
        // Track slot registration
        AppDNA.track("slot_registered", mapOf("slot_name" to name, "platform" to "android"))

        // Check consent
        if (!AppDNA.isConsentGranted()) {
            isLoading = false
            isEmpty = true
            return@LaunchedEffect
        }

        // Look up slot assignment
        val result = ScreenManager.shared.screenForSlot(name)
        if (result != null) {
            val (screenId, config) = result
            screenConfig = config
            isLoading = false

            AppDNA.track("slot_rendered", mapOf(
                "slot_name" to name,
                "screen_id" to screenId,
                "screen_name" to config.name,
            ))
        } else {
            isEmpty = true
            isLoading = false

            AppDNA.track("slot_empty", mapOf("slot_name" to name))
        }
    }

    when {
        isLoading -> {
            // Animated shimmer placeholder
            // matching iOS AppDNAScreenSlot.swift:48-63 — gray base with a
            // sweeping LinearGradient highlight band. Was a static gray box
            // — Android users saw an empty-looking slab instead of a
            // loading affordance. Sweep animates ~1500ms infinite linear.
            val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "slot_shimmer")
            val sweep by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(durationMillis = 1500, easing = androidx.compose.animation.core.LinearEasing),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
                ),
                label = "slot_shimmer_sweep",
            )
            androidx.compose.foundation.layout.BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Gray.copy(alpha = 0.1f)),
            ) {
                val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
                val bandWidthPx = widthPx * 0.4f
                val startX = -bandWidthPx + sweep * (widthPx + bandWidthPx)
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.3f),
                                    Color.Transparent,
                                ),
                                start = androidx.compose.ui.geometry.Offset(startX, 0f),
                                end = androidx.compose.ui.geometry.Offset(startX + bandWidthPx, 0f),
                            )
                        )
                )
            }
        }
        screenConfig != null -> {
            val config = screenConfig ?: return
            val slotConfig = config.slotConfig
            val maxHeight = slotConfig?.maxHeight

            // Slot action dispatch mirrors iOS
            // `Screens/AppDNAScreenSlot.swift handleSlotAction(_:config:)`.
            // Dismiss is intentionally inert (slot is inline content host
            // can't pop). OpenURL + DeepLink both resolve via ACTION_VIEW
            // through the host activity context; this matches iOS's
            // `UIApplication.shared.open(url)` behavior. Veto is checked via
            // `ScreenManager.shared.handleScreenAction` so a host delegate
            // can intercept slot actions just like full-screen ones.
            val activityContext = LocalContext.current
            val context = SectionContext(
                screenId = config.id,
                onAction = { action ->
                    // Rich, iOS-parity action payload
                    // (`{type, <fields>}`) so the cross-platform veto host reads
                    // `type` + payload fields.
                    val payload = action.toActionMap()
                    // Route through dispatchScreenAction so the
                    // sync delegate veto AND the optional async wrapper-veto both
                    // gate the action. Native hosts (no async veto) run inline.
                    ScreenManager.shared.dispatchScreenAction(config.id, payload) {
                        when (action) {
                            // Config-driven URLs — scheme-checked first.
                            is SectionAction.OpenURL -> {
                                ai.appdna.sdk.core.URLSafety.sanitized(action.url, activityContext)?.let { uri ->
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, uri)
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        activityContext.startActivity(intent)
                                    } catch (_: Throwable) { /* best-effort */ }
                                }
                            }
                            is SectionAction.DeepLink -> {
                                ai.appdna.sdk.core.URLSafety.sanitized(action.url, activityContext)?.let { uri ->
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, uri)
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        activityContext.startActivity(intent)
                                    } catch (_: Throwable) { /* best-effort */ }
                                }
                            }
                            is SectionAction.ShowScreen -> ScreenManager.shared.showScreen(action.id)
                            is SectionAction.ShowPaywall -> action.id?.let { AppDNA.showPaywall(it) }
                            is SectionAction.ShowSurvey -> action.id?.let { AppDNA.showSurvey(it) }
                            is SectionAction.Dismiss -> { /* slot can't dismiss inline content */ }
                            else -> {}
                        }
                    }
                },
            )

            val modifier = Modifier.fillMaxWidth().let { mod ->
                if (maxHeight != null) mod.heightIn(max = maxHeight.dp) else mod
            }.let { mod ->
                if (slotConfig?.presentation == "overlay" || slotConfig?.tapToExpand == true) {
                    mod.clickable {
                        ScreenManager.shared.showScreen(config.id)
                    }
                } else mod
            }

            // Split sticky_footer sections
            // out so they pin to the bottom of the slot. iOS ScreenRenderer
            // separates `sticky_footer` / `paywall_sticky_footer` from main
            // content and renders pinned at bottom inset. Android previously
            // rendered them inline.
            val mainSections = config.sections.filter {
                it.type != "sticky_footer" && it.type != "paywall_sticky_footer"
            }
            val stickyFooters = config.sections.filter {
                it.type == "sticky_footer" || it.type == "paywall_sticky_footer"
            }
            val spacing = (config.layout.spacing ?: 12.0).dp
            // Honor ScreenLayout.type.
            // iOS supports `scroll` (default), `fixed` (no scroll), and
            // `pager` (HorizontalPager). Android collapsed everything to
            // a non-scrolling Column. `pager` falls through to scroll on
            // Android until an accompanist-compose dependency lands.
            val layoutType = config.layout.type
            // `pager` layout uses Compose foundation HorizontalPager
            // (available since BoM 2023.10+, already used by paywalls).
            // Mirrors iOS ScreenRenderer TabView .page style.
            val isPagerLayout = layoutType == "pager"
            // Honor `layout.safe_area` by
            // adding system-status-bar padding when truthy (iOS renders
            // inside the safeAreaInsets by default). Hosts that embed
            // a slot with safe_area=false get edge-to-edge content.
            val safeAreaModifier = if (config.layout.safeArea != false) {
                androidx.compose.ui.Modifier.windowInsetsPadding(
                    androidx.compose.foundation.layout.WindowInsets.systemBars
                )
            } else androidx.compose.ui.Modifier
            androidx.compose.foundation.layout.Box(modifier = modifier.then(safeAreaModifier)) {
                Column(modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
                    // Render NavBarConfig
                    // (title + back/close + bg color) when set. iOS
                    // ScreenRenderer puts this above content; Android slots
                    // render inline so we put it at the top of the column.
                    config.navBar?.let { nav ->
                        val bgClr = nav.backgroundColor?.let { ai.appdna.sdk.core.StyleEngine.parseColor(it) }
                            ?: Color.Transparent
                        Row(
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxWidth()
                                .background(bgClr)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            if (nav.showBack == true) {
                                androidx.compose.material3.TextButton(onClick = {
                                    ScreenManager.shared.dismissScreen()
                                }) { androidx.compose.material3.Text("←") }
                            } else androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(40.dp))
                            nav.title?.let { title ->
                                androidx.compose.material3.Text(
                                    text = title,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                )
                            }
                            if (nav.showClose == true || config.dismiss?.enabled == true) {
                                androidx.compose.material3.TextButton(onClick = {
                                    ScreenManager.shared.dismissScreen()
                                }) { androidx.compose.material3.Text("✕") }
                            } else androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(40.dp))
                        }
                    }
                if (isPagerLayout) {
                    // HorizontalPager: one main section per page,
                    // mirrors iOS ScreenRenderer TabView(.page). Visibility
                    // filter applied up-front so page indices stay stable.
                    val pages = mainSections.filter { evaluateScreenSectionVisibility(it.visibilityCondition) }
                    if (pages.isNotEmpty()) {
                        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                        val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { pages.size })
                        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                        androidx.compose.foundation.pager.HorizontalPager(
                            state = pagerState,
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                        ) { pageIndex ->
                            val section = pages[pageIndex]
                            val animType = section.entranceAnimation?.type
                            val animDur = section.entranceAnimation?.durationMs
                            if (animType != null && animType != "none") {
                                androidx.compose.foundation.layout.Box(
                                    modifier = androidx.compose.ui.Modifier.entryAnimation(animType, animDur),
                                ) { SectionRegistry.Render(section, context) }
                            } else {
                                SectionRegistry.Render(section, context)
                            }
                        }
                    }
                } else {
                Column(
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .let { mod ->
                            if (layoutType == "fixed") mod
                            else mod.verticalScroll(rememberScrollState())
                        },
                    verticalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    for (section in mainSections) {
                        // Per-section
                        // visibility_condition + entrance_animation. Mirrors iOS
                        // ScreenRenderer.
                        if (!evaluateScreenSectionVisibility(section.visibilityCondition)) continue
                        val animType = section.entranceAnimation?.type
                        val animDur = section.entranceAnimation?.durationMs
                        if (animType != null && animType != "none") {
                            androidx.compose.foundation.layout.Box(
                                modifier = androidx.compose.ui.Modifier.entryAnimation(animType, animDur),
                            ) {
                                SectionRegistry.Render(section, context)
                            }
                        } else {
                            SectionRegistry.Render(section, context)
                        }
                    }
                }
                }
                } // close outer Column(navBar+content wrapper)
                // Sticky footers must live in the Box scope (not inside the
                // wrapper Column) so `Modifier.align(BottomCenter)` resolves.
                if (stickyFooters.isNotEmpty()) {
                    Column(
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxWidth()
                            .align(androidx.compose.ui.Alignment.BottomCenter),
                        verticalArrangement = Arrangement.spacedBy(spacing),
                    ) {
                        for (section in stickyFooters) {
                            if (!evaluateScreenSectionVisibility(section.visibilityCondition)) continue
                            SectionRegistry.Render(section, context)
                        }
                    }
                }
            }
        }
        isEmpty -> {
            // Empty slot renders nothing (AC-040c)
        }
    }
}

/**
 * Pure evaluator for section-level
 * `visibility_condition`. Mirrors iOS ScreenRenderer's per-section
 * filtering. Returns true (section is visible) for `null` and `"always"`,
 * false-by-default for unknown types so authors can't accidentally
 * leak a hidden section by misspelling the rule.
 *
 * Reads only static SDK state (user traits + session data); the
 * onboarding `responses[step.id]` map is not in scope here since SDUI
 * screens render outside of an onboarding flow.
 */
private fun evaluateScreenSectionVisibility(condition: VisibilityConditionConfig?): Boolean {
    if (condition == null) return true
    val traits = AppDNA.getUserTraits()
    val session = ai.appdna.sdk.core.SessionDataStore.instance?.sessionData ?: emptyMap()
    fun resolve(path: String?): Any? {
        if (path.isNullOrBlank()) return null
        val keys = path.split(".")
        var cur: Any? = if (keys.first() == "user_traits") traits else session
        for (k in keys.drop(if (keys.first() == "user_traits") 1 else 0)) {
            cur = (cur as? Map<*, *>)?.get(k) ?: return null
        }
        return cur
    }
    return when (condition.type) {
        "always" -> true
        "when_equals" -> resolve(condition.variable)?.toString() == condition.value?.toString()
        "when_not_equals" -> resolve(condition.variable)?.toString() != condition.value?.toString()
        "when_not_empty" -> resolve(condition.variable)?.toString()?.isNotEmpty() == true
        "when_empty" -> {
            val v = resolve(condition.variable)?.toString()
            v.isNullOrEmpty()
        }
        "when_gt" -> {
            val a = resolve(condition.variable)?.toString()?.toDoubleOrNull() ?: return false
            val b = condition.value?.toString()?.toDoubleOrNull() ?: return false
            a > b
        }
        "when_lt" -> {
            val a = resolve(condition.variable)?.toString()?.toDoubleOrNull() ?: return false
            val b = condition.value?.toString()?.toDoubleOrNull() ?: return false
            a < b
        }
        else -> false
    }
}
