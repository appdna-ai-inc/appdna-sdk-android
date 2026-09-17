package ai.appdna.sdk.paywalls

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Divider
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import ai.appdna.sdk.core.entryAnimation
import ai.appdna.sdk.core.sectionStagger
import ai.appdna.sdk.core.ctaAnimation
import ai.appdna.sdk.core.planSelectionAnimation
import ai.appdna.sdk.core.StyleEngine
import ai.appdna.sdk.core.StyleEngine.applyContainerStyle
import ai.appdna.sdk.core.LocalizationEngine
import ai.appdna.sdk.core.dismissAnimation
import ai.appdna.sdk.core.LottieBlock
import ai.appdna.sdk.core.LottieBlockView
import ai.appdna.sdk.core.RiveBlock
import ai.appdna.sdk.core.RiveBlockView
import ai.appdna.sdk.core.VideoBlock as CoreVideoBlock
import ai.appdna.sdk.core.VideoBlockView as CoreVideoBlockView
import ai.appdna.sdk.core.ConfettiOverlay
import ai.appdna.sdk.core.HapticEngine
import ai.appdna.sdk.core.HapticType
import ai.appdna.sdk.core.IconView
import ai.appdna.sdk.core.IconReference
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.launch
// SPEC-070-A J.11 — accessibility string resources (Close / Restore /
// Purchase). Hosts can override these via their own `strings.xml`.
import ai.appdna.sdk.R

/**
 * Activity to render paywall UI using Jetpack Compose.
 * Follows the same pattern as SurveyActivity.
 */
class PaywallActivity : ComponentActivity() {

    // SPEC-070-A finalization OB-5 audit-2 HIGH-3 — instance-level snapshot
    // of the dismiss callback so `onDestroy` can fire it as a backstop when
    // the OS / user kills the Activity outside the regular dismissal paths
    // (task swipe in recents, low-memory kill, finish() from elsewhere).
    // Without this, an onboarding-presented paywall that's force-killed
    // never routes its `on_dismiss_target`, leaving the host onboarding
    // flow stuck in mid-state on next resume.
    private var snapshotOnDismiss: ((DismissReason) -> Unit)? = null
    private var dispatchedDismiss: Boolean = false
    // SPEC-070-A finalization P0 audit-3 P1 fix — instance-stored
    // launch token so cleanup/onBackPressed/onDestroy can pull THIS
    // Activity's slots from the per-launch registry, not whatever was
    // last written to the static companion-object slots.
    private var launchToken: String? = null
    private var instanceConfig: PaywallConfig? = null
    private var instancePaywallId: String? = null
    // SPEC-419 — when this Activity became visible, to gate delayed-dismiss
    // (`dismiss.delay_seconds`) back-press the same way the on-screen X is gated.
    private var presentedAtMs: Long = 0L

    /**
     * SPEC-401 Fix 1C — host-controlled opt-out for SDK auto-dismiss on
     * restore success. Hosts that want to keep the paywall visible after
     * restore (e.g. show a "Restored! Tap X when ready" overlay) flip
     * this flag inside their `onPaywallRestoreCompleted` delegate body
     * before returning. When true, [dismissAfterRestore] short-circuits.
     * Mirrors iOS PaywallDismissGuard.skipSDKAutoDismiss.
     */
    internal var skipSDKAutoDismiss: Boolean = false

    /**
     * SPEC-401 Fix 1C — auto-dismiss on restore success. Called from
     * [PaywallManager.handleRestore] AFTER the delegate's
     * `onPaywallRestoreCompleted(productIds)` fires with non-empty products.
     * No-ops if the user already dismissed (`dispatchedDismiss`) or if the
     * host requested to keep the paywall up ([skipSDKAutoDismiss]).
     *
     * SPEC-401 R3 audit Lens D P1 — also no-op when the Activity is
     * mid-lifecycle (`isFinishing` or `isChangingConfigurations`). If the
     * user rotates the device during a restore call, the BillingClient
     * coroutine continuation lands here on the OLD Activity instance via
     * the WeakReference registry. Calling `finish()` + firing
     * `snapshotOnDismiss` on a config-changing Activity races with the
     * new Activity's onCreate and corrupts dismiss routing. Bridge state
     * (`didPurchase=true`) survives in OnboardingActivity, so when the
     * recreated PaywallActivity comes up and the user taps X, the
     * dismiss-time routing still lands on `on_success_target` correctly.
     */
    internal fun dismissAfterRestore() {
        if (dispatchedDismiss) return
        if (skipSDKAutoDismiss) return
        if (isFinishing || isChangingConfigurations) return
        dispatchedDismiss = true
        // Surface the dismiss reason on the captured `onDismiss` slot so
        // the onboarding bridge / host can route via `on_success_target`
        // (Fix 1B already flipped didPurchase=true via the bridge so this
        // path lands on the same outcome as a real purchase).
        // SPEC-401-A R20 — emit RESTORE_SUCCESS ("restore_success") instead
        // of PURCHASED so dashboards filtering MTPU vs restore can segment
        // correctly. Matches iOS PaywallManager.swift:411 dismiss_reason.
        snapshotOnDismiss?.invoke(DismissReason.RESTORE_SUCCESS)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SPEC-419 — edge-to-edge so the full-bleed paywall background paints UNDER
        // the system bars like iOS .ignoresSafeArea(). IDENTICAL to OnboardingActivity:
        // the demo app's Theme.Translucent.NoTitleBar sets FLAG_TRANSLUCENT_STATUS/
        // NAVIGATION, which forces an opaque/scrim bar background and makes
        // statusBarColor=transparent a NO-OP — the bars stayed PURE BLACK top + bottom
        // (the "black gaps"). enableEdgeToEdge() lays the window out edge-to-edge with
        // explicit TRANSPARENT SystemBarStyle scrims (the no-arg form installs a dark
        // nav scrim); then clearing the translucent flags + DRAWS_SYSTEM_BAR_BACKGROUNDS
        // lets the gradient paint under the bars. (Manual setDecorFitsSystemWindows +
        // statusBarColor alone did NOT work here — same lesson as the onboarding screens.)
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

        val paywallId = intent.getStringExtra(EXTRA_PAYWALL_ID) ?: run {
            finish()
            return
        }
        // SPEC-070-A finalization P0 audit-3 — read THIS Activity's
        // launch slot via the per-call UUID extra. Concurrent launches
        // each get their own slot; no clobber.
        //
        // SPEC-070-A finalization P0 audit-4 — PEEK (not remove) so the
        // slot survives Activity recreation (rotation, dark-mode toggle,
        // font-scale, locale, multi-window resize). Removal happens in
        // onDestroy only when `isFinishing && !isChangingConfigurations`
        // — i.e. real dismissal, not config-change recreate. Without
        // this, a rotation mid-paywall would call onCreate again, find
        // no slot, finish(), and the user's purchase context vanishes.
        val token = intent.getStringExtra(EXTRA_LAUNCH_TOKEN)
        val slots = token?.let { activeLaunches[it] } ?: run {
            // SPEC-070-A finalization (Lens D P0) — process-death recovery.
            // If we're being recreated by the OS (`savedInstanceState != null`)
            // but the static `activeLaunches` map is empty, the SDK process was
            // killed since the original launch and our in-memory callbacks are
            // gone. Fire the global PaywallDelegate so analytics + onPaywallDismissed
            // fires (with `reason=process_death`) instead of finishing silently.
            if (savedInstanceState != null) {
                try {
                    // SPEC-070-A finalization R8 (Lens C P1) — emit canonical
                    // `paywall_close` event (matches iOS PaywallManager.swift:104
                    // + Android PaywallManager.kt:136) instead of the
                    // dashboard-divergent `paywall_dismissed` name.
                    // SPEC-401-A R77 (Lens B P2) — use `dismiss_reason` key
                    // matching iOS PaywallManager.swift:120-124 + happy-path
                    // PaywallManager.kt:139. Was `reason` — divergent from
                    // every other paywall_close row, BigQuery `dismiss_reason`
                    // column would be NULL for process-death rows.
                    AppDNA.track("paywall_close", mapOf(
                        "paywall_id" to paywallId,
                        "dismiss_reason" to "process_death",
                    ))
                    AppDNA.paywall.listener?.onPaywallDismissed(paywallId)
                } catch (e: Throwable) {
                    Log.warning { "Process-death recovery delegate fire failed: ${e.message}" }
                }
            }
            finish()
            return
        }
        launchToken = token
        val config = slots.config
        instanceConfig = config
        instancePaywallId = paywallId
        presentedAtMs = System.currentTimeMillis()
        // SPEC-401 Fix 1C — register this Activity so PaywallManager can
        // call dismissAfterRestore() on it after a successful restore.
        activePaywallInstances[paywallId] = java.lang.ref.WeakReference(this)

        val onAppear = slots.onAppear
        val onDismiss = slots.onDismiss
        val onPlanSelected = slots.onPlanSelected
        val onPromoCodeSubmit = slots.onPromoCodeSubmit
        // SPEC-070-A C.3 — restore lifecycle hook (delegate-fired by PaywallManager)
        val onRestoreCb = slots.onRestore

        // SPEC-070-A finalization OB-5 audit-2 HIGH-3 — capture onDismiss
        // on the instance so onDestroy can fire it when the regular paths
        // (Compose onDismiss, onBackPressed) didn't run.
        snapshotOnDismiss = onDismiss

        // Notify appearance
        onAppear?.invoke()

        setContent {
            // SPEC-070-A D.5 — read system dark-mode pref so the renderer can
            // pick `dark` overrides from paywall content blocks. Mirrors the
            // SurveyActivity pattern landed by Phase D for surveys.
            val isDark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (isDark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()) {
                PaywallScreen(
                    config = config,
                    onPlanSelected = { plan, metadata ->
                        onPlanSelected?.invoke(plan, metadata)
                    },
                    onRestore = {
                        // SPEC-070-A C.3 — forward restore taps to the
                        // PaywallManager-supplied lifecycle callback.
                        onRestoreCb?.invoke()
                    },
                    onDismiss = { reason ->
                        dispatchedDismiss = true
                        onDismiss?.invoke(reason)
                        cleanup()
                    },
                    onPromoCodeSubmit = onPromoCodeSubmit,
                    isDark = isDark,
                )
            }
        }
    }

    private fun cleanup() {
        // SPEC-070-A finalization P0 audit-3 — slot was already removed
        // from activeLaunches in onCreate; no companion-object cleanup
        // required. instanceConfig is dropped by Activity destruction.
        instanceConfig = null
        finish()
    }

    override fun onBackPressed() {
        // SPEC-070-A I.4 — force-choice paywalls: when `dismiss.allowed == false`,
        // intercept the system back so the user can't dismiss without selecting
        // a plan or restoring. Mirrors iOS `Paywalls/PaywallRenderer.swift` which
        // wraps the paywall in `.interactiveDismissDisabled(!allowed)`.
        // SPEC-070-A finalization P0 audit-3 — read THIS Activity's
        // dismiss policy from instanceConfig, NOT a global slot.
        // SPEC-419 — `delay_seconds` reveals dismiss after N seconds even when
        // allowed=false (winback). Back is blocked only while the paywall is NOT
        // yet dismissable: a true force-choice (allowed=false AND no delay) blocks
        // forever; a delayed-dismiss blocks until its delay elapses, then allows back.
        val dismiss = instanceConfig?.dismiss
        val allowed = dismiss?.allowed ?: true
        val delaySeconds = dismiss?.delay_seconds ?: 0
        val elapsedSeconds = (System.currentTimeMillis() - presentedAtMs) / 1000
        val dismissableNow = allowed || (delaySeconds > 0 && elapsedSeconds >= delaySeconds)
        if (!dismissableNow) {
            return
        }
        dispatchedDismiss = true
        @Suppress("DEPRECATION")
        super.onBackPressed()
        snapshotOnDismiss?.invoke(DismissReason.DISMISSED)
        cleanup()
    }

    override fun onDestroy() {
        // SPEC-070-A finalization OB-5 audit-2 HIGH-3 — backstop for
        // dismissal paths that don't go through `onBackPressed` or the
        // Compose `onDismiss` callback (OS kill, task-recents swipe,
        // external `finish()`). Without this an onboarding-presented
        // paywall force-killed mid-display never routes
        // `on_dismiss_target`, stranding the onboarding flow.
        if (isFinishing && !dispatchedDismiss) {
            val cb = snapshotOnDismiss
            snapshotOnDismiss = null
            cb?.invoke(DismissReason.DISMISSED)
        }
        // SPEC-070-A finalization P0 audit-4 — GC the launch slot ONLY
        // on real dismissal (`isFinishing && !isChangingConfigurations`).
        // Config-change recreates (rotation, dark-mode, locale, font
        // scale) MUST keep the slot so the recreated Activity's
        // onCreate can read it. Without this guard, rotating the device
        // during a paywall would lose all pending callbacks +
        // PaywallConfig and `finish()` the recreated instance.
        if (isFinishing && !isChangingConfigurations) {
            launchToken?.let { activeLaunches.remove(it) }
            // SPEC-401 Fix 1C — clear our weak ref entry so a stale
            // reference can't be returned to PaywallManager after the
            // Activity is gone.
            instancePaywallId?.let { id ->
                val ref = activePaywallInstances[id]
                if (ref?.get() === this) activePaywallInstances.remove(id)
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PAYWALL_ID = "paywall_id"
        private const val EXTRA_LAUNCH_TOKEN = "paywall_launch_token"

        // SPEC-070-A finalization P0 audit-3 P1 fix — per-launch slot
        // registry. Replaces the old single-set companion-object vars
        // (pendingConfig / pendingOnDismiss / etc.) which were CLOBBERED
        // when two `PaywallManager.present()` calls happened in quick
        // succession (e.g. journey trigger + host-initiated). Two rapid
        // launches both wrote to the same slots; the FIRST Activity's
        // onCreate then read SECOND launch's payload, rendering the wrong
        // paywall and firing the wrong host listeners. iOS UIKit's
        // `present(_:animated:)` no-ops a duplicate present on the same
        // parent VC; Android's `startActivity` stacks → exposed this race.
        //
        // Now: each `launch()` mints a UUID, stores its payload in this
        // map under the UUID, passes the UUID via Intent.putExtra. Each
        // Activity reads its OWN payload in onCreate via the UUID extra
        // and removes its slot. Concurrent launches each have their own
        // payload — no clobber. Mirrors how iOS captures delegate refs
        // in per-call SwiftUI sheet closures.
        internal data class LaunchSlots(
            val config: PaywallConfig,
            val onAppear: (() -> Unit)? = null,
            val onDismiss: ((DismissReason) -> Unit)? = null,
            val onPlanSelected: ((PaywallPlan, Map<String, Any>) -> Unit)? = null,
            val onPromoCodeSubmit: ((String, (Boolean) -> Unit) -> Unit)? = null,
            val onRestore: (() -> Unit)? = null,
        )
        private val activeLaunches = java.util.concurrent.ConcurrentHashMap<String, LaunchSlots>()

        // SPEC-401 Fix 1C — per-paywallId registry of live PaywallActivity
        // instances. Used by [PaywallManager.handleRestore] to call
        // [PaywallActivity.dismissAfterRestore] when restore succeeds with
        // non-empty productIds. Stored as WeakReference so a leaked entry
        // never holds the Activity beyond its real lifecycle.
        //
        // In practice only one paywall presents at a time per paywallId;
        // a second present overwrites the prior weak ref, and the prior
        // Activity (if still alive) is dismissed by its own onDestroy
        // path. Mirrors iOS PaywallManager's per-presentation closure
        // capture of viewController.
        private val activePaywallInstances =
            java.util.concurrent.ConcurrentHashMap<String, java.lang.ref.WeakReference<PaywallActivity>>()

        /**
         * SPEC-401 Fix 1C — look up the currently-live PaywallActivity for
         * the given paywallId so [PaywallManager.handleRestore] can call
         * [dismissAfterRestore] from a non-Activity coroutine context.
         * Returns null if no Activity is registered or the WeakReference
         * has been cleared by GC.
         */
        @JvmStatic
        internal fun activeInstance(paywallId: String): PaywallActivity? =
            activePaywallInstances[paywallId]?.get()

        /**
         * SPEC-401-A R83 (Lens B P1) — finish ALL live paywall activities
         * (one per paywallId in normal use). Called from
         * [PaywallManager.handlePostPurchaseFailure] when
         * `on_failure.action == "dismiss"` so the paywall actually closes
         * after a card-decline, mirroring iOS PaywallManager.swift:322-324
         * `viewController.dismiss(animated:true)`.
         */
        @JvmStatic
        internal fun dismissCurrent(suppressDismissCallback: Boolean = false) {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            activePaywallInstances.values.forEach { ref ->
                ref.get()?.let { activity ->
                    handler.post {
                        if (!activity.isFinishing) {
                            // Round-13 — when the CALLER already handles the dismiss notification (the
                            // post-purchase success branches fire onPaywallDismissed / onPostPurchase*
                            // themselves, matching iOS which does NOT re-route on_dismiss or emit
                            // paywall_close after a purchase), mark the dismiss as dispatched so the
                            // onDestroy backstop (which fires the onDismiss lambda → paywall_close +
                            // onPaywallDismissed) does NOT double-fire.
                            if (suppressDismissCallback) activity.dispatchedDismiss = true
                            activity.finish()
                        }
                    }
                }
            }
        }

        // Read-only accessor for `onBackPressed` to consult the active
        // paywall's `dismiss.allowed` field. Returns null if the Activity
        // already consumed and removed its slot, in which case the back
        // press should fall through to system default (allowed).
        internal fun slotsForToken(token: String?): LaunchSlots? =
            token?.let { activeLaunches[it] }

        /**
         * SPEC-070-A finalization R3 P0 (Lens D) — clear all in-flight slot
         * captures. Called from `AppDNA.shutdown()`.
         */
        @JvmStatic
        internal fun clearActiveLaunches() {
            activeLaunches.clear()
        }

        @JvmStatic
        fun launch(
            context: Context,
            paywallId: String,
            config: PaywallConfig,
            paywallContext: PaywallContext? = null,
            onAppear: (() -> Unit)? = null,
            onDismiss: ((DismissReason) -> Unit)? = null,
            onPlanSelected: ((PaywallPlan, Map<String, Any>) -> Unit)? = null,
            onPromoCodeSubmit: ((String, (Boolean) -> Unit) -> Unit)? = null,
            onRestore: (() -> Unit)? = null,
        ) {
            val token = java.util.UUID.randomUUID().toString()
            activeLaunches[token] = LaunchSlots(
                config = config,
                onAppear = onAppear,
                onDismiss = onDismiss,
                onPlanSelected = onPlanSelected,
                onPromoCodeSubmit = onPromoCodeSubmit,
                onRestore = onRestore,
            )
            val intent = Intent(context, PaywallActivity::class.java).apply {
                putExtra(EXTRA_PAYWALL_ID, paywallId)
                putExtra(EXTRA_LAUNCH_TOKEN, token)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // SPEC-070-A finalization P0 audit-4 — startActivity can
            // throw (ActivityNotFoundException, SecurityException, or
            // background-launch denial on Android 12+). Without
            // try/catch, the slot would be orphaned forever — leaking
            // a PaywallConfig + 5 closures per failed launch. On throw,
            // remove the slot AND fire onDismiss so the host's pending
            // paywall context (e.g. an onboarding flow waiting for the
            // paywall outcome) doesn't strand.
            try {
                context.startActivity(intent)
            } catch (t: Throwable) {
                activeLaunches.remove(token)
                onDismiss?.invoke(DismissReason.DISMISSED)
                throw t
            }
        }

        /**
         * SPEC-070-A C.5 — broadcast post-purchase success overlay (confetti +
         * lottie) into a live PaywallActivity composition. Mirrors iOS's
         * `NotificationCenter.default.post(.paywallPurchaseSuccess, ...)`.
         */
        @Volatile internal var postPurchaseOverlay: PostPurchaseOverlayState? = null
    }
}

/**
 * SPEC-070-A C.5 — payload for the post-purchase success overlay (confetti +
 * lottie animation rendered atop the paywall after a successful purchase).
 */
internal data class PostPurchaseOverlayState(
    val message: String? = null,
    val confetti: Boolean = false,
    val lottieUrl: String? = null,
    /**
     * SPEC-070-A finalization parity audit R6 — failure overlay
     * retry CTA. Mirrors iOS PaywallManager.swift:309-313 which posts
     * `paywallPurchaseFailure` notification with `retry_text` /
     * `action`. PaywallRenderer.swift:351-353 renders a labelled retry
     * button when `action == "retry"`. Android failure overlay
     * previously dropped both fields — this surface re-introduces them.
     */
    val retryText: String? = null,
    val action: String? = null,
    val allowDismiss: Boolean = true,
)

// Parity: maps a console cta_font_weight string (normal | medium | semibold | bold)
// to a Compose FontWeight. Mirrors iOS resolveCTAFontWeight semantics; default SemiBold.
fun resolveCtaWeight(w: String?): FontWeight = when (w?.lowercase()) {
    "normal", "regular" -> FontWeight.Normal
    "medium" -> FontWeight.Medium
    "bold" -> FontWeight.Bold
    "semibold" -> FontWeight.SemiBold
    else -> FontWeight.SemiBold
}

@Composable
fun PaywallScreen(
    config: PaywallConfig,
    onPlanSelected: (PaywallPlan, Map<String, Any>) -> Unit,
    onRestore: () -> Unit,
    onDismiss: (DismissReason) -> Unit,
    // AC-037: Callback for promo code validation. Returns true if code is valid, false otherwise.
    onPromoCodeSubmit: ((String, (Boolean) -> Unit) -> Unit)? = null,
    // SPEC-070-A D.5 — system dark-mode pref. Renderers/blocks may apply
    // `dark` overrides from console-designed paywall content based on this.
    @Suppress("UNUSED_PARAMETER") isDark: Boolean = false,
) {
    var selectedPlanId by remember { mutableStateOf<String?>(null) }
    var showDismiss by remember { mutableStateOf(false) }
    var isPurchasing by remember { mutableStateOf(false) }
    var isDismissing by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(0f) }
    var showConfetti by remember { mutableStateOf(false) }
    // AC-038: Hoisted toggle states for inclusion in purchase metadata
    val toggleStates = remember { mutableStateMapOf<String, Boolean>() }
    // SPEC-070-A C.7 — promo state hoisted out of PaywallPromoInputSection
    // so CTA tap handlers can fold the validated code into the purchase
    // metadata. Mirrors iOS `PaywallRenderer.swift:2160-2167`.
    var promoCode by remember { mutableStateOf("") }
    var promoState by remember { mutableStateOf("idle") } // idle | loading | success | error
    // SPEC-070-A C.5 — post-purchase success overlay state. Drained from the
    // companion-object slot every recomposition so PaywallManager.handle
    // PostPurchaseSuccess can fire confetti + lottie atop the paywall while
    // the dismiss timer counts down.
    var postPurchaseOverlay by remember { mutableStateOf<PostPurchaseOverlayState?>(null) }
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        // Poll the companion-object slot for new overlay payloads. Cheap
        // (one variable read per frame) — and we only enter this loop while
        // the paywall composition is alive.
        while (true) {
            val pending = PaywallActivity.postPurchaseOverlay
            if (pending != null && pending != postPurchaseOverlay) {
                postPurchaseOverlay = pending
                PaywallActivity.postPurchaseOverlay = null
                // A drained failure overlay (retry / show_error) means the purchase
                // did NOT complete — re-enable the CTA so the user isn't stuck behind
                // a permanently-disabled button. iOS resets in its failure observer;
                // success overlays use other actions and are left untouched.
                if (pending.action == "retry" || pending.action == "show_error") {
                    isPurchasing = false
                }
                // Auto-dismiss after 4s when the overlay has no Retry CTA
                // — without this, an error overlay shown via show_error
                // (no retry) with allowDismiss=false leaves the user
                // stranded behind a dim backdrop with no escape. When
                // action=="retry" the overlay stays up; user must pick
                // Retry vs tap-to-dismiss. Mirrors iOS PaywallRenderer
                // `.paywallPurchaseFailure` observer auto-dismiss.
                if (pending.action != "retry") {
                    val target = pending
                    coroutineScope.launch {
                        kotlinx.coroutines.delay(4000)
                        if (postPurchaseOverlay === target) {
                            postPurchaseOverlay = null
                        }
                    }
                }
            }
            kotlinx.coroutines.delay(100)
        }
    }
    val currentView = LocalView.current

    fun triggerDismiss() {
        if (config.animation?.dismiss_animation != null) {
            isDismissing = true
            coroutineScope.launch {
                kotlinx.coroutines.delay(350)
                onDismiss(DismissReason.DISMISSED)
            }
        } else {
            onDismiss(DismissReason.DISMISSED)
        }
    }

    // Select default plan on appear + initialize toggle defaults
    LaunchedEffect(Unit) {
        // SPEC-070-A finalization PW-2 — read effective plans (sections OR
        // top-level config.plans fallback). Computed inline because
        // effectivePlans() lives in the @Composable body below; mirror its
        // logic here at the LaunchedEffect site.
        val sectionPlans = config.sections
            .firstOrNull { it.type == "plans" }
            ?.data?.plans
            ?: emptyList<PaywallPlan>()
        val plans = if (sectionPlans.isEmpty()) (config.plans ?: emptyList()) else sectionPlans
        selectedPlanId = plans.firstOrNull { it.is_default == true }?.id
            ?: plans.firstOrNull()?.id

        // AC-038: Initialize toggle defaults from config
        config.sections.filter { it.type == "toggle" }.forEach { section ->
            val key = section.data?.label ?: "toggle_${section.type}"
            if (section.data?.default_value != null) {
                toggleStates[key] = section.data.default_value
            }
        }

        // QA-R18 — honour `config.dismiss.allowed = false` on the
        // VISIBLE dismiss control, not just the system back button. Was
        // setting `showDismiss = true` unconditionally regardless of the
        // authored `allowed` flag, so a paywall configured as force-choice
        // (Winback / hard-paywall) STILL rendered an X / Skip / swipe-down
        // handle — defeating the whole point of `allowed: false`. The
        // onBackPressed gate at line ~265 caught back-press attempts but
        // the user could still tap the on-screen control to dismiss.
        //
        // iOS PaywallRenderer.swift gates `dismissControl` rendering on
        // `config.dismiss?.allowed != false` at the view level; mirror
        // that here.
        // A `delay_seconds > 0` means "REVEAL the dismiss control after N seconds"
        // and MUST win even when `allowed == false` — that's the WINBACK pattern
        // (force engagement for N seconds, THEN let the user leave). Previously
        // `allowed == false` hid the X unconditionally and ignored delay_seconds, so a
        // winback authored as {allowed:false, delay_seconds:10} could NEVER be dismissed.
        // Only a paywall that is BOTH disallowed AND has no delayed reveal is a true
        // hard force-choice. Mirrors iOS PaywallRenderer.swift dismiss gating.
        val delay = config.dismiss?.delay_seconds ?: 0
        val neverDismissable = config.dismiss?.allowed == false && delay <= 0
        if (neverDismissable) {
            showDismiss = false
            return@LaunchedEffect
        }
        if (delay > 0) {
            kotlinx.coroutines.delay(delay * 1000L)
        }
        showDismiss = true
    }

    // Localization helper + SPEC-088: Template variable interpolation
    val templateContext = ai.appdna.sdk.core.TemplateEngine.buildContext()
    fun loc(key: String, fallback: String): String {
        val localized = LocalizationEngine.resolve(key, config.localizations, config.default_locale, fallback)
        return ai.appdna.sdk.core.TemplateEngine.interpolate(localized, templateContext)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .dismissAnimation(config.animation?.dismiss_animation, isDismissing)
            .entryAnimation(config.animation?.entry_animation, config.animation?.entry_duration_ms)
            .then(
                if ((config.dismiss?.type ?: "x_button") == "swipe_down") {
                    Modifier.offset(y = dragOffset.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (dragOffset > 150) {
                                        triggerDismiss()
                                    } else {
                                        dragOffset = 0f
                                    }
                                },
                                onVerticalDrag = { _, dragAmount ->
                                    if (dragOffset + dragAmount > 0) {
                                        dragOffset += dragAmount
                                    }
                                }
                            )
                        }
                } else Modifier
            )
    ) {
        // Background
        PaywallBackground(config.background)

        // SPEC-089d: Extract sticky_footer section
        val stickyFooterSection = config.sections.firstOrNull { it.type == "sticky_footer" }

        // SPEC-070-A finalization PW-2 — `effectivePlans` helper. Mirrors iOS
        // `PaywallRenderer.swift:511-512` resolution:
        //   sectionPlans = config.sections.first(where: { type == "plans" })?.data?.plans
        //   effectivePlans = sectionPlans.isEmpty ? (config.plans ?? []) : sectionPlans
        // Without this, any paywall authored at the document root (no plans
        // section, just top-level `plans: []`) renders with no plans on
        // Android — even though parser landed top-level `config.plans`
        // support already. Also resolves selection by id.
        fun effectivePlans(): List<PaywallPlan> {
            val sectionPlans = config.sections
                .firstOrNull { it.type == "plans" }
                ?.data?.plans
                ?: emptyList<PaywallPlan>()
            return if (sectionPlans.isEmpty()) (config.plans ?: emptyList()) else sectionPlans
        }
        // SPEC-070-A finalization PW-3 — CTA text resolution chain is now
        // inlined at the CTA section render site (PaywallActivity.kt:~1497)
        // because that site lives inside PaywallSectionView, a separate
        // @Composable scope from PaywallScreen. The helper that previously
        // lived here was dead code — audit round 2 D1 cleanup.

        // Content in a Column with scrollable area + sticky footer
        Column(modifier = Modifier.fillMaxSize()) {
            // SPEC-070-A J.20 — convert eager `Column.verticalScroll` to
            // `LazyColumn` with stable keys so the recompose-window only
            // touches sections that scrolled into view + plan/feature
            // sub-lists don't fully reconstruct when a single section
            // re-emits. iOS uses `ScrollView { LazyVStack }` for the same
            // reason (PaywallRenderer.swift:38-67).
            val staggerDelay = config.animation?.section_stagger_delay_ms ?: 0
            // SPEC-070-A audit Round 2 finding 3: filter `sticky_footer`
            // (rendered separately below) so it doesn't render twice.
            // Mirrors iOS PaywallRenderer.swift:38-67,119 partition.
            //
            // SPEC-070-A finalization R2 P1 (Lens B) — `legal` sections that
            // appear AFTER the cta in the sections array are pinned beneath
            // the CTA inside the safeAreaInset.bottom, NOT scrolled away with
            // body content. Mirrors iOS PaywallRenderer.swift:54-67 + 213-222
            // `pinnedFooterLegalSections` partition. Authors who want a
            // permanently-visible ToS/Privacy footer place a `legal` section
            // after their `cta` section.
            val ctaIndex = config.sections.indexOfFirst { it.type == "cta" }
            val pinnedLegalSections: List<PaywallSection> = if (ctaIndex >= 0) {
                config.sections
                    .drop(ctaIndex + 1)
                    .filter { it.type == "legal" }
            } else emptyList()
            // Use referential identity for the pin-out check — section.id is
            // nullable so id-based filtering would mis-partition unidentified
            // legal sections.
            val pinnedLegalSet: Set<PaywallSection> = pinnedLegalSections.toSet()
            // SPEC-419 Gap 7 — exclude the CTA section from the scroll body so it
            // can be PINNED to the bottom zone (iOS safeAreaInset(.bottom),
            // PaywallRenderer.swift:141-230) instead of floating mid-screen.
            val ctaSection = if (ctaIndex >= 0) config.sections[ctaIndex] else null
            val scrollableSections = config.sections.filter {
                it.type != "sticky_footer" && it.type != "cta" && it !in pinnedLegalSet
            }
            // SPEC-070-A finalization PW-11 — collapse_on_scroll wiring.
            // Mirrors iOS PaywallScrollOffsetPrefKey-driven collapse: as user
            // scrolls, sections marked collapse_on_scroll==true fade their
            // alpha + slightly translate up. We hoist a LazyListState here
            // and derive a SINGLE global scroll offset that all marked
            // sections share — see globalCollapseRatio below.
            val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
            // SPEC-070-A finalization P0 audit-1 HIGH-1 — collapse_on_scroll
            // is GLOBAL on iOS, not per-section. iOS PaywallRenderer.swift:
            // 120-128 derives `collapseProgress = min(max(scrollOffset/50, 0), 1)`
            // from a single page-level PaywallScrollOffsetPrefKey, then ALL
            // sections marked collapse_on_scroll==true fade together as soon
            // as the user scrolls past 50px. Per-section logic (each section
            // waiting until it's the first visible item before fading) breaks
            // multi-section collapse layouts: a `collapse_on_scroll` banner
            // at index 0 + a `collapse_on_scroll` testimonial at index 5
            // would fade independently on Android instead of together.
            //
            // Approximation: any scroll past the first item (i.e.
            // firstVisibleItemIndex > 0) means user has progressed past 50px.
            // Otherwise use the offset within the first item. Same 50px
            // hard-collapse distance as iOS.
            val globalCollapseRatio by androidx.compose.runtime.remember {
                androidx.compose.runtime.derivedStateOf {
                    val first = lazyListState.firstVisibleItemIndex
                    val offset = lazyListState.firstVisibleItemScrollOffset
                    val pxScrolled: Float =
                        if (first > 0) Float.POSITIVE_INFINITY
                        else offset.toFloat()
                    (pxScrolled / 50f).coerceIn(0f, 1f)
                }
            }
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .weight(1f)
                    .padding((config.layout.padding ?: 20f).dp),
            ) {
                itemsIndexed(
                    items = scrollableSections,
                    key = { idx, section -> "${section.type}_${section.id ?: idx}" },
                ) { index, section ->
                    val collapseRatio = if (section.collapse_on_scroll == true) globalCollapseRatio else 0f
                    val collapseAlpha = 1f - collapseRatio
                    val collapseHeight = if (collapseRatio >= 1f) 0.dp else androidx.compose.ui.unit.Dp.Unspecified
                    Box(
                        modifier = Modifier
                            .then(if (collapseHeight == 0.dp) Modifier.height(0.dp) else Modifier)
                            .alpha(collapseAlpha)
                            .sectionStagger(
                                config.animation?.section_stagger,
                                delayMs = staggerDelay * index,
                            )
                    ) {
                        PaywallSectionView(
                            section = section,
                            config = config,
                            selectedPlanId = selectedPlanId,
                            isPurchasing = isPurchasing,
                            onPlanSelect = { planId ->
                                selectedPlanId = planId
                                // SPEC-085: Haptic on plan select
                                HapticEngine.triggerIfEnabled(
                                    currentView,
                                    config.haptic?.triggers?.on_plan_select,
                                    config.haptic,
                                )
                            },
                            onCTATap = {
                                // PW-2 — top-level plans fallback.
                                val plans = effectivePlans()
                                val plan = plans.firstOrNull { p -> p.id == selectedPlanId }
                                if (plan != null) {
                                    isPurchasing = true
                                    // SPEC-085: Haptic on CTA tap
                                    HapticEngine.triggerIfEnabled(
                                        currentView,
                                        config.haptic?.triggers?.on_button_tap,
                                        config.haptic,
                                    )
                                    // SPEC-085: Confetti on purchase
                                    if (config.particle_effect != null) {
                                        showConfetti = true
                                    }
                                    // SPEC-070-A C.7 — fold validated promo
                                    // code into purchase metadata. Mirrors
                                    // iOS PaywallRenderer.swift:2160-2167.
                                    val md = toggleStates.toMutableMap<String, Any>()
                                    if (promoState == "success" && promoCode.isNotBlank()) {
                                        md["promo_code"] = promoCode
                                    }
                                    onPlanSelected(plan, md.toMap())
                                }
                            },
                            onRestore = onRestore,
                            loc = ::loc,
                            toggleStates = toggleStates,
                            onPromoCodeSubmit = onPromoCodeSubmit,
                            promoCode = promoCode,
                            promoState = promoState,
                            onPromoCodeChange = { promoCode = it },
                            onPromoStateChange = { promoState = it },
                        )
                    }
                    Spacer(modifier = Modifier.height((config.layout.spacing ?: 16f).dp))
                }
            }

            // Footer padding — mirror iOS `.padding(.bottom, footer_padding ?? 8)`
            // on the pinned bottom inset (PaywallRenderer.swift:245). Wrap the pinned
            // CTA + legal + sticky-footer group so the configured gap sits below the
            // whole group, composing with each block's own navigationBarsPadding and
            // without touching the weighted scroll body above.
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = (config.layout.footer_padding ?: 8f).dp)) {
            // SPEC-419 Gap 7 — CTA pinned to the bottom zone: below the scroll
            // body (weight(1f)), above the pinned legal + sticky footer. Same
            // horizontal inset as the scrolled content. Mirrors iOS
            // safeAreaInset(.bottom) ordering (PaywallRenderer.swift:141-230).
            ctaSection?.let { cta ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = (config.layout.padding ?: 20f).dp)) {
                    PaywallSectionView(
                        section = cta,
                        config = config,
                        selectedPlanId = selectedPlanId,
                        isPurchasing = isPurchasing,
                        onPlanSelect = { planId ->
                            selectedPlanId = planId
                            HapticEngine.triggerIfEnabled(currentView, config.haptic?.triggers?.on_plan_select, config.haptic)
                        },
                        onCTATap = {
                            val plans = effectivePlans()
                            val plan = plans.firstOrNull { p -> p.id == selectedPlanId }
                            if (plan != null) {
                                isPurchasing = true
                                HapticEngine.triggerIfEnabled(currentView, config.haptic?.triggers?.on_button_tap, config.haptic)
                                if (config.particle_effect != null) showConfetti = true
                                val md = toggleStates.toMutableMap<String, Any>()
                                if (promoState == "success" && promoCode.isNotBlank()) md["promo_code"] = promoCode
                                onPlanSelected(plan, md.toMap())
                            }
                        },
                        onRestore = onRestore,
                        loc = ::loc,
                        toggleStates = toggleStates,
                        onPromoCodeSubmit = onPromoCodeSubmit,
                        promoCode = promoCode,
                        promoState = promoState,
                        onPromoCodeChange = { promoCode = it },
                        onPromoStateChange = { promoState = it },
                    )
                }
            }

            // SPEC-070-A finalization R2 P1 (Lens B) — pinned legal sections
            // render between the scroll body and the sticky footer (or above
            // the system inset when no sticky footer). Mirrors iOS
            // PaywallRenderer.swift:213-222 safeAreaInset.bottom layer where
            // legals authored AFTER the cta stay permanently visible.
            if (pinnedLegalSections.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = (config.layout.padding ?: 20f).dp)
                        // SPEC-419 — the pinned legal (Terms & Conditions) is the BOTTOM-most
                        // element; without the nav-bar inset it rendered under the gesture bar
                        // and was clipped/invisible (iOS shows it under Restore). Clear the bar.
                        .navigationBarsPadding(),
                ) {
                    for (section in pinnedLegalSections) {
                        PaywallSectionView(
                            section = section,
                            config = config,
                            selectedPlanId = selectedPlanId,
                            isPurchasing = isPurchasing,
                            onPlanSelect = {},
                            onCTATap = {},
                            onRestore = onRestore,
                            loc = ::loc,
                            toggleStates = toggleStates,
                            onPromoCodeSubmit = onPromoCodeSubmit,
                            promoCode = promoCode,
                            promoState = promoState,
                            onPromoCodeChange = { promoCode = it },
                            onPromoStateChange = { promoState = it },
                        )
                    }
                }
            }

            // SPEC-089d: Sticky footer pinned to bottom
            if (stickyFooterSection != null) {
                PaywallStickyFooter(
                    section = stickyFooterSection,
                    isPurchasing = isPurchasing,
                    // Round-MZ — selected plan's per-plan cta_text overrides the footer label.
                    selectedPlanCtaText = effectivePlans().firstOrNull { it.id == selectedPlanId }?.cta_text?.takeIf { it.isNotBlank() },
                    onCTATap = {
                        // PW-2 — top-level plans fallback (sticky footer path).
                        val plans = effectivePlans()
                        val plan = plans.firstOrNull { p -> p.id == selectedPlanId }
                        if (plan != null) {
                            isPurchasing = true
                            HapticEngine.triggerIfEnabled(
                                currentView,
                                config.haptic?.triggers?.on_button_tap,
                                config.haptic,
                            )
                            if (config.particle_effect != null) {
                                showConfetti = true
                            }
                            // SPEC-070-A C.7 — fold validated promo code
                            // into purchase metadata (sticky footer path).
                            val md = toggleStates.toMutableMap<String, Any>()
                            if (promoState == "success" && promoCode.isNotBlank()) {
                                md["promo_code"] = promoCode
                            }
                            onPlanSelected(plan, md.toMap())
                        }
                    },
                    onRestore = onRestore,
                    loc = ::loc,
                )
            }
            } // end pinned bottom group (footer_padding wrapper)
        }

        // SPEC-085: Confetti overlay
        if (showConfetti && config.particle_effect != null) {
            ConfettiOverlay(
                effect = config.particle_effect,
                trigger = showConfetti,
            )
        }

        // SPEC-070-A C.5 — post-purchase success overlay (confetti + lottie).
        // Mirrors iOS `PaywallRenderer.swift:346` `.paywallPurchaseSuccess`
        // notification observer. Confetti uses an ad-hoc ParticleEffect when
        // the paywall has no `particle_effect` config of its own. Lottie URL
        // renders via the shared LottieBlockView.
        postPurchaseOverlay?.let { overlay ->
            // SPEC-401-A R71 (Lens A P2) — only render confetti if the
            // paywall config has `particle_effect` set, matching iOS
            // PaywallRenderer.swift:235-237 `if let effect = config
            // .particle_effect`. Was fabricating an ad-hoc ParticleEffect
            // when none was configured — paywall with NO particle_effect
            // celebrated on Android but stayed silent on iOS.
            if (overlay.confetti && config.particle_effect != null) {
                ConfettiOverlay(
                    effect = config.particle_effect,
                    trigger = true,
                )
            }
            if (!overlay.lottieUrl.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) {
                    LottieBlockView(
                        block = LottieBlock(
                            lottie_url = overlay.lottieUrl,
                            loop = false,
                            autoplay = true,
                        ),
                    )
                    if (!overlay.message.isNullOrEmpty()) {
                        Column(
                            verticalArrangement = Arrangement.Bottom,
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
                        ) {
                            Text(
                                text = overlay.message,
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 32.dp),
                            )
                        }
                    }
                }
            } else if (!overlay.message.isNullOrEmpty()) {
                // Message-only overlay (no lottie configured) — surface
                // a centered card atop the paywall. SPEC-070-A finalization
                // parity audit R6 — when overlay.action == "retry", render
                // a retry CTA labelled with overlay.retryText (mirrors iOS
                // PaywallRenderer.swift:351-353). When action is null
                // (success path) or "show_error", show message-only.
                val isFailure = overlay.action == "retry" || overlay.action == "show_error"
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f))
                        .let { mod ->
                            if (overlay.allowDismiss) {
                                mod.clickable { postPurchaseOverlay = null }
                            } else mod
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isFailure) Color(0xFFD64545) else Color(0xFF2E9E51),
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = overlay.message,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                            // Retry CTA — only when iOS-side action == "retry".
                            if (overlay.action == "retry") {
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        // Re-fire purchase for currently-selected plan.
                                        // Use effectivePlans() (sections OR top-level) like every
                                        // other selected-plan lookup, so section-scoped plans retry.
                                        val plan = effectivePlans().firstOrNull { it.id == selectedPlanId }
                                        postPurchaseOverlay = null
                                        plan?.let { onPlanSelected(it, emptyMap()) }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White,
                                        contentColor = Color(0xFFD64545),
                                    ),
                                ) {
                                    Text(
                                        text = overlay.retryText ?: "Try Again",
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Dismiss control
        if (showDismiss) {
            // SPEC-070-A finalization R3 P0 (Lens A PW-5) — prefer `dismiss.style`
            // over `dismiss.type`. iOS PaywallRenderer.swift:567-573 reads `_style`
            // first and falls back to `_type` for backward compat with older
            // configs that still use `type`. Both fields parse to PaywallDismiss.
            val dismissType = config.dismiss?.style ?: config.dismiss?.type ?: "x_button"
            when (dismissType) {
                "text_link" -> {
                    val dismissCd = stringResource(R.string.appdna_a11y_paywall_dismiss)
                    TextButton(
                        onClick = { triggerDismiss() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                            // SPEC-070-A J.11 — text-link dismiss has no icon
                            // semantic so we attach an explicit a11y label.
                            .semantics { contentDescription = dismissCd },
                    ) {
                        Text(
                            text = loc("dismiss.text", config.dismiss?.text ?: "No thanks"),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                        )
                    }
                }
                "swipe_down" -> {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 8.dp)
                            .width(36.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.5f))
                    )
                }
                else -> { // x_button (default)
                    val closeCd = stringResource(R.string.appdna_a11y_paywall_close)
                    IconButton(
                        onClick = { triggerDismiss() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            // SPEC-419 — sit BELOW the status bar (edge-to-edge Activity),
                            // matching iOS which places the X inside the safe area. Without
                            // this the X overlapped the clock/battery and was hard to tap.
                            .statusBarsPadding()
                            .padding(16.dp)
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.3f))
                            // SPEC-070-A J.11 \u2014 close X has no semantic icon
                            // (it's a Text composable with the unicode glyph),
                            // so attach an a11y label that TalkBack reads.
                            .semantics { contentDescription = closeCd },
                    ) {
                        Text(
                            text = "\u2715",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaywallBackground(background: PaywallBackground?) {
    // SPEC-070-A finalization PW-7 / PW-8 — full PaywallBackground matrix
    // mirroring iOS' PaywallRenderer LegacyBackground/BackgroundStyle paths
    // (PaywallRenderer.swift:386-449). Adds: gradient.stops+angle parsing,
    // video background composable, image_fit honoring, overlay color, and
    // a transparent/clear/none type that returns Color.Transparent.
    when (background?.type) {
        "gradient" -> {
            // Prefer canonical `gradient.stops + angle` (Firestore writes it
            // this way per validator). Fall back to legacy `colors` array.
            val stops = background.gradient?.stops
            val angle = background.gradient?.angle ?: 180.0 // default top→bottom
            val brush: Brush? = when {
                stops != null && stops.size >= 2 -> {
                    val colorStopList = stops.mapNotNull { stop ->
                        val c = stop.color ?: return@mapNotNull null
                        val pos = stop.position?.toFloat() ?: 0f
                        pos to parseHexColor(c)
                    }.toTypedArray()
                    if (colorStopList.size >= 2) {
                        // SPEC-419 — iOS uses UnitPoint (0..1 FRACTIONS). Compose
                        // Brush.linearGradient start/end are in PIXELS, so passing
                        // 0..1 collapsed the whole gradient into the first pixel and
                        // clamped to a near-solid color. Use a size-aware ShaderBrush
                        // so the fractions map to real pixels = a true full-bleed
                        // gradient matching iOS PaywallRenderer.swift:394-412.
                        val rads = angle * Math.PI / 180.0
                        object : androidx.compose.ui.graphics.ShaderBrush() {
                            override fun createShader(size: androidx.compose.ui.geometry.Size): android.graphics.Shader {
                                val fx = (0.5 - kotlin.math.cos(rads) * 0.5).toFloat()
                                val fy = (0.5 - kotlin.math.sin(rads) * 0.5).toFloat()
                                val tx = (0.5 + kotlin.math.cos(rads) * 0.5).toFloat()
                                val ty = (0.5 + kotlin.math.sin(rads) * 0.5).toFloat()
                                return androidx.compose.ui.graphics.LinearGradientShader(
                                    from = androidx.compose.ui.geometry.Offset(fx * size.width, fy * size.height),
                                    to = androidx.compose.ui.geometry.Offset(tx * size.width, ty * size.height),
                                    colors = colorStopList.map { it.second },
                                    colorStops = colorStopList.map { it.first },
                                )
                            }
                        }
                    } else null
                }
                background.colors != null && background.colors.size >= 2 -> {
                    val cs = background.colors.map { parseHexColor(it) }
                    Brush.verticalGradient(cs)
                }
                else -> null
            }
            if (brush != null) {
                Box(modifier = Modifier.fillMaxSize().background(brush))
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            }
        }
        "image" -> {
            Box(modifier = Modifier.fillMaxSize()) {
                // Prefer canonical `image_url`, fall back to legacy `value`.
                val imageUrl = background.image_url ?: background.value
                if (imageUrl != null) {
                    val scale = when (background.image_fit) {
                        "contain" -> androidx.compose.ui.layout.ContentScale.Fit
                        "fill" -> androidx.compose.ui.layout.ContentScale.FillBounds
                        else -> androidx.compose.ui.layout.ContentScale.Crop // "cover" default
                    }
                    ai.appdna.sdk.core.NetworkImage(
                        url = imageUrl,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = scale,
                    )
                    // Optional overlay color tint (iOS PaywallRenderer:593).
                    background.overlay?.let { overlayHex ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(parseHexColor(overlayHex)),
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                }
            }
        }
        "video" -> {
            // PW-8 — Media3/ExoPlayer-backed video background. Mirrors iOS'
            // `VideoBackgroundView` (PaywallRenderer.swift:416). Auto-mutes
            // unless `video_muted == false`, loops by default.
            VideoBackgroundView(
                url = background.video_url ?: background.value,
                posterUrl = background.video_poster_url,
                muted = background.video_muted ?: true,
                loop = background.video_loop ?: true,
                modifier = Modifier.fillMaxSize(),
            )
            background.overlay?.let { overlayHex ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(parseHexColor(overlayHex)),
                )
            }
        }
        "color" -> {
            // PW-6 fix already in parser: `value` now folds in `bg.color`.
            // SPEC-070-A finalization audit follow-up — iOS treats the literal
            // strings "transparent" / "clear" as Color.clear
            // (PaywallRenderer.swift:427-431). Android parseHexColor("transparent")
            // falls through to Color.Black, so console-authored
            // bg.color="transparent" rendered BLACK on Android and CLEAR on iOS.
            // Special-case those literals here before parseHexColor is called.
            val color = when (background.value?.lowercase()?.trim()) {
                null, "" -> Color.Black
                "transparent", "clear" -> Color.Transparent
                else -> background.value?.let { parseHexColor(it) } ?: Color.Black
            }
            Box(modifier = Modifier.fillMaxSize().background(color))
        }
        "transparent", "clear", "none" -> {
            // Explicit transparent type — composable but renders nothing so
            // the host app's underlying screen shows through.
            Box(modifier = Modifier.fillMaxSize())
        }
        else -> {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        }
    }
}

/**
 * SPEC-070-A finalization PW-8 — video background composable backed by
 * Media3/ExoPlayer. Mirrors iOS' `VideoBackgroundView` (AVPlayer-backed):
 * auto-plays muted, loops, hides controls.
 *
 * Lifecycle:
 *  - DisposableEffect tears the player down on composition exit so the SDK
 *    doesn't hold an audio focus token across paywall dismissals.
 *  - Renders a poster image as a placeholder until the player is ready.
 */
@Composable
private fun VideoBackgroundView(
    url: String?,
    posterUrl: String?,
    muted: Boolean,
    loop: Boolean,
    modifier: Modifier = Modifier,
) {
    if (url.isNullOrBlank()) {
        // Bare poster fallback — useful if video URL fails to resolve.
        Box(modifier = modifier) {
            posterUrl?.let { p ->
                ai.appdna.sdk.core.NetworkImage(
                    url = p,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
        }
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val exoPlayer = remember(url) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            volume = if (muted) 0f else 1f
            repeatMode = if (loop) {
                androidx.media3.common.Player.REPEAT_MODE_ONE
            } else {
                androidx.media3.common.Player.REPEAT_MODE_OFF
            }
            playWhenReady = true
            prepare()
        }
    }
    androidx.compose.runtime.DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx ->
            androidx.media3.ui.PlayerView(ctx).apply {
                useController = false
                player = exoPlayer
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
// `internal`, not `private`, so the Roborazzi snapshot test can render the REAL plan card
// rather than a copy of it. `internal` is module-scoped — it does not widen the SDK's public
// API surface for consumers.
@Composable
internal fun PaywallSectionView(
    section: PaywallSection,
    config: PaywallConfig,
    selectedPlanId: String?,
    isPurchasing: Boolean,
    onPlanSelect: (String) -> Unit,
    onCTATap: () -> Unit,
    onRestore: () -> Unit,
    loc: (String, String) -> String,
    toggleStates: MutableMap<String, Boolean> = mutableMapOf(),
    onPromoCodeSubmit: ((String, (Boolean) -> Unit) -> Unit)? = null,
    // SPEC-070-A C.7 — hoisted promo state for purchase metadata.
    promoCode: String = "",
    promoState: String = "idle",
    onPromoCodeChange: (String) -> Unit = {},
    onPromoStateChange: (String) -> Unit = {},
) {
    when (section.type) {
        "header" -> {
            // Mirror iOS HeaderSection.swift:54 — `.padding(.top, 40)` gives
            // the title breathing room from the safe-area / nav bar.
            // applyContainerStyle runs after so author overrides still win.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp)
                    .run { with(StyleEngine) { applyContainerStyle(section.style?.container) } }
            ) {
                // SPEC-401-A R77 (Lens A P1) — render header section image
                // matching iOS HeaderSection.swift:19-28
                // `BundledAsyncImage(url: data.imageUrl, ...).scaledToFit()
                // .frame(maxHeight: 200)`. Was missing — paywall headers
                // authored with `image_url` showed nothing on Android.
                section.data?.image_url?.takeIf { it.isNotBlank() }?.let { url ->
                    // Round-MZ — honor image_alignment (leading|center|trailing) + image_max_height,
                    // mirroring iOS HeaderSection.swift `.frame(maxHeight:).frame(maxWidth:.infinity,
                    // alignment:)`. Box gives the image full width to align within; heightIn caps it.
                    val imgAlignment = when (section.data?.image_alignment) {
                        "leading" -> Alignment.CenterStart
                        "trailing" -> Alignment.CenterEnd
                        else -> Alignment.Center
                    }
                    val imgMaxHeight = (section.data?.image_max_height ?: 200f).dp
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = imgAlignment) {
                        ai.appdna.sdk.core.NetworkImage(
                            url = url,
                            modifier = Modifier.heightIn(max = imgMaxHeight),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                section.data?.title?.let {
                    // SPEC-401-A R77 (Lens A P1) — prefer data-level
                    // `title_style` over section-level
                    // `style.elements["title"].text_style` matching iOS
                    // HeaderSection.swift:10-15 precedence.
                    val titleStyle = StyleEngine.applyTextStyle(
                        // Theme-adaptive default matching iOS HeaderSection `.primary` (black on light,
                        // white on dark) — was hardcoded white, invisible on light paywalls.
                        TextStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 28.sp, textAlign = TextAlign.Center),
                        section.data.title_style ?: section.style?.elements?.get("title")?.text_style
                    )
                    Text(
                        text = loc("section-header.title", it),
                        style = titleStyle,
                        // SPEC-070-A J.11 — paywall hero/header title is the
                        // accessibility heading for the screen, mirroring iOS
                        // `accessibilityAddTraits(.isHeader)`.
                        modifier = Modifier.semantics { heading() },
                    )
                }
                section.data?.subtitle?.let {
                    Spacer(Modifier.height(8.dp))
                    val subtitleStyle = StyleEngine.applyTextStyle(
                        // Shared cross-platform default: muted grey #6B7280 (iOS uses the same),
                        // legible on both light and dark backgrounds.
                        TextStyle(color = Color(0xFF6B7280), fontSize = 16.sp, textAlign = TextAlign.Center),
                        section.data.subtitle_style ?: section.style?.elements?.get("subtitle")?.text_style
                    )
                    Text(
                        text = loc("section-header.subtitle", it),
                        style = subtitleStyle,
                    )
                }
            }
        }
        "features" -> {
            val featureItemStyle = StyleEngine.applyTextStyle(
                TextStyle(color = Color.White, fontSize = 16.sp),
                section.style?.elements?.get("item")?.text_style
            )
            // SPEC-419 \u2014 iOS FeatureList is a content-sized VStack(.leading)
            // CENTERED in its parent (no maxWidth). Mirror that: outer column
            // centers the block; inner wrapContentWidth keeps rows left-aligned
            // among themselves. Checkmark uses the brand accent like iOS
            // (FeatureList.swift:120,132), not a hardcoded green.
            Column(
                modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally)) {
                    section.data?.features?.forEachIndexed { index, feature ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 6.dp)
                        ) {
                            Text(text = "\u2713", color = ai.appdna.sdk.AppDNA.brandAccentColor(), fontSize = 18.sp)
                            Spacer(Modifier.width(12.dp))
                            Text(text = loc("feature.$index", feature), style = featureItemStyle)
                        }
                    }

                    // 🔴 RICH ITEMS. This branch read ONLY `section.data.features` — a list of plain
                    // strings — while the console has long written `config.items`, the structured
                    // shape with per-item emoji, icon, image and excluded state. So a features
                    // section authored in the console rendered as an EMPTY BLOCK on Android while
                    // iOS drew it in full (FeatureList.swift `richItemRow`). Nothing failed loudly;
                    // the section was simply absent.
                    //
                    // Found while implementing #591: a per-item subtitle has nowhere to land on a
                    // platform that does not render the items at all.
                    //
                    // The legacy `features` loop above is KEPT rather than replaced. Both keys can
                    // be present on an older document, and rendering only one of them would trade
                    // this bug for its mirror image.
                    val richItems = section.data?.items.orEmpty()
                    val subtitleColor = section.data?.item_subtitle_color?.takeIf { it.isNotBlank() }
                        ?.let { StyleEngine.parseColor(it) } ?: Color.White.copy(alpha = 0.7f)
                    val subtitleSize = (section.data?.item_subtitle_font_size ?: 12f).sp
                    // #591 — `rounded` (default, unchanged), `circle` or `square`. iOS parity:
                    // FeatureList.swift `imageClipShape`.
                    val imageShape = when (section.data?.item_image_shape) {
                        "circle" -> CircleShape
                        "square" -> RoundedCornerShape(0.dp)
                        else -> RoundedCornerShape(4.dp)
                    }
                    richItems.forEachIndexed { index, item ->
                        val included = item.included ?: true
                        Row(
                            // `Top`, not `CenterVertically`: with a subtitle the row is two lines,
                            // and centring floats the icon against the gap between them.
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.padding(vertical = 6.dp).alpha(if (included) 1f else 0.4f),
                        ) {
                            when {
                                !item.emoji.isNullOrEmpty() ->
                                    Text(text = item.emoji, fontSize = 18.sp)
                                !item.image_url.isNullOrEmpty() ->
                                    ai.appdna.sdk.core.NetworkImage(
                                        url = item.image_url,
                                        modifier = Modifier.size(20.dp).clip(imageShape),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    )
                                !item.icon.isNullOrEmpty() ->
                                    Text(text = item.icon, fontSize = 18.sp)
                                // The excluded cross is AUTHORABLE, not a hardcoded red. A colour
                                // a person sees is a colour a person should be able to set — and a
                                // fixed red is unreadable on the red-toned paywalls it would land on.
                                else -> Text(
                                    text = if (included) "\u2713" else "\u2717",
                                    color = if (included) {
                                        ai.appdna.sdk.AppDNA.brandAccentColor()
                                    } else {
                                        section.data?.item_excluded_color?.takeIf { it.isNotBlank() }
                                            ?.let { StyleEngine.parseColor(it) } ?: parseHexColor("#EF4444")
                                    },
                                    fontSize = 18.sp,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = loc("${'$'}{section.id}.items.$index", item.text ?: item.title ?: item.label ?: ""),
                                    style = featureItemStyle,
                                )
                                // #591 — the second line, when the author wrote one.
                                item.subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
                                    Text(
                                        text = loc("${'$'}{section.id}.items.$index.subtitle", sub),
                                        fontSize = subtitleSize,
                                        color = subtitleColor,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        "plans" -> {
            // Gap 10-11: Read plan_display_style from section data, fallback to layout.type
            val displayStyle = section.data?.plan_display_style ?: config.layout.type
            // SPEC-070-A finalization PW-2 — fall back to top-level
            // `config.plans` when section has no plans (matches iOS
            // PaywallRenderer.swift:511-512 effectivePlans logic).
            val sectionPlansRaw = section.data?.plans ?: emptyList()
            val plans = if (sectionPlansRaw.isEmpty()) (config.plans ?: emptyList()) else sectionPlansRaw

            // Card styling from config
            val cardRadius = (section.data?.card_corner_radius ?: 12f).dp
            val cardPad = (section.data?.card_padding ?: 16f).dp
            // Audit pass-8 — default the unauthored card_gap to 12 to match iOS'
            // primary vertical-stack default (PaywallRenderer.swift:1698
            // `cardStyle.cardGap ?? 12`) and the console/preview default
            // (SectionContentEditor.tsx:385, PaywallPreview.tsx:458). Was 8f, so
            // default vertical-stack cards sat tighter on Android than iOS/preview.
            // (iOS grid/side-by-side use 8; the flat 12 default is the accepted
            // parity tradeoff for the LOW-frequency multi-column layouts.)
            val cardGap = (section.data?.card_gap ?: 12f).dp
            // console card_height → minimum plan-card height. Null when unauthored so it
            // never forces a floor. Mirrors iOS PlanCard `.frame(minHeight:)` + PaywallPreview minHeight.
            val cardHeight = section.data?.card_height?.let { it.dp }
            val cardShape = RoundedCornerShape(cardRadius)
            // Mirror iOS PlanCard.swift:206-210 — accept Bool OR String enum
            // ("sm"/"md"/"lg"/"none") and derive elevation. String values
            // were silently dropped before (cast-to-Boolean returned null).
            val cardShadowRaw = section.data?.card_shadow
            val cardShadowEnabled = when (cardShadowRaw) {
                is Boolean -> cardShadowRaw
                is String -> cardShadowRaw.lowercase() !in setOf("none", "false", "")
                else -> false
            }
            val cardShadowElevation = when {
                cardShadowRaw is String && cardShadowRaw.equals("sm", ignoreCase = true) -> 2.dp
                cardShadowRaw is String && cardShadowRaw.equals("lg", ignoreCase = true) -> 8.dp
                cardShadowEnabled -> 4.dp
                else -> 0.dp
            }

            // Text styles
            val planNameStyle = StyleEngine.applyTextStyle(
                TextStyle(fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 16.sp),
                // Mirror iOS PlanCard.swift:22 (elements["plan_name"] ?? elements["label"]) — the
                // console Style tab exposes plan-name under the 'label' element key (SECTION_ELEMENTS.plans),
                // so without the 'label' fallback an authored plan-name style is silently dropped on Android.
                section.style?.elements?.get("plan_name")?.text_style
                    ?: section.style?.elements?.get("label")?.text_style
            )
            val priceStyle = StyleEngine.applyTextStyle(
                TextStyle(fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp),
                section.style?.elements?.get("price")?.text_style
            )
            val periodStyle = StyleEngine.applyTextStyle(
                TextStyle(fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f)),
                section.style?.elements?.get("period")?.text_style
            )
            // Console Style tab exposes `trial_label`; apply its authored text style
            // (mirrors priceStyle above). Color still resolved per selected/brand-accent
            // logic at the Text call site.
            // Base color = brand accent so an authored trial_label color wins when present
            // (applyTextStyle overrides base color only when the element style sets one),
            // matching iOS PlanCard + the console preview (getElementStyle spread after accent).
            val trialStyle = StyleEngine.applyTextStyle(
                TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ai.appdna.sdk.AppDNA.brandAccentColor()),
                section.style?.elements?.get("trial_label")?.text_style
            )

            // Badge styling
            // Mrozu QA — default badge bg is the brand accent (matches iOS + the console editor
            // default), not the hardcoded green. Other accent defaults in this file use the same
            // accessor (e.g. selected-border/bg at :1595/:1597).
            val badgeBg = section.data?.badge_bg_color?.let { parseHexColor(it) } ?: ai.appdna.sdk.AppDNA.brandAccentColor()
            val badgeTxt = section.data?.badge_text_color?.let { parseHexColor(it) } ?: Color.White
            val badgeFontSize = (section.data?.badge_font_size ?: 11f).sp
            // Audit pass-8 — resolve the badge text style from the Style-tab element
            // `section.style.elements["badge"].text_style` FIRST (iOS PlanCard.badgeView
            // PlanCard.swift:30-32,:312 is badgeTextStyle-first for font/size/color/weight),
            // falling back to the flat badge_font_size + badge_text_color when the element
            // style is absent. Android previously read only the flat fields.
            val badgeTextStyle = StyleEngine.applyTextStyle(
                TextStyle(fontWeight = FontWeight.SemiBold, color = badgeTxt, fontSize = badgeFontSize),
                section.style?.elements?.get("badge")?.text_style,
            )
            // Mrozu QA — badge border + leading icon were decoded (PaywallConfig.kt:312-314) but
            // dropped by BadgeView; iOS PlanCard.badgeView (:298-311/:332-335) renders both.
            val badgeBorderColor = section.data?.badge_border_color?.let { parseHexColor(it) }
            val badgeBorderWidth = section.data?.badge_border_width ?: 0f
            val badgeIcon = section.data?.badge_icon
            val badgeIconColorHex = section.data?.badge_text_color ?: "#FFFFFF"
            val badgeShapeStr = section.data?.badge_shape ?: "pill"
            // Mirror iOS PlanCard.swift:314-323 — console emits "rectangle"
            // as the iOS-native naming; keep "square" alias for back-compat.
            val badgeCorner = when (badgeShapeStr) {
                "square", "rectangle" -> RoundedCornerShape(2.dp)
                "rounded" -> RoundedCornerShape(4.dp)
                // Notched-ribbon: rectangle with a triangular notch cut into the trailing
                // edge, matching the console PaywallPreview clipPath polygon
                // (0,0 → 100,0 → 92,50 → 100,100 → 0,100) and the iOS RibbonBadgeShape.
                "ribbon" -> androidx.compose.foundation.shape.GenericShape { size, _ ->
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width * 0.92f, size.height / 2f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                else -> RoundedCornerShape(999.dp) // pill
            }
            // QA-R11 — default `top_right` to mirror iOS
            // `PlanCard.swift:259` (`cardStyle.badgePosition ?? "top_right"`).
            // Was `"inline"` which forced the badge inside the card body, on
            // top of the plan name and price.
            // Round-21 F2 — the console writes HYPHENATED positions ("top-right" etc.) but the `when`
            // blocks below match UNDERSCORED constants, so an authored value hit the `else` (top-right)
            // while iOS's default hit top-LEFT — opposite corners. Normalize hyphen→underscore so
            // "top-left"/"top-center"/"top-right" route correctly and match iOS.
            val badgePosition = (section.data?.badge_position ?: "top_right").replace("-", "_").lowercase()

            @Composable
            fun BadgeView(badgeText: String) {
                Row(
                    modifier = Modifier
                        .background(badgeBg, badgeCorner)
                        .then(
                            // Border only when both width>0 and a color is set (iOS PlanCard.swift:332-335).
                            if (badgeBorderWidth > 0f && badgeBorderColor != null)
                                Modifier.border(badgeBorderWidth.dp, badgeBorderColor, badgeCorner)
                            else Modifier,
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Leading badge icon (iOS PlanCard.swift:304-311): an SF Symbol / Material name
                    // (contains "." or "_") renders via IconView; anything else (emoji/glyph) via Text.
                    badgeIcon?.takeIf { it.isNotBlank() }?.let { icon ->
                        if (icon.contains(".") || icon.contains("_")) {
                            ai.appdna.sdk.core.IconView(
                                ref = ai.appdna.sdk.core.IconReference(
                                    library = "sf-symbols",
                                    name = icon,
                                    color = badgeIconColorHex,
                                    size = 11f,
                                ),
                                defaultSize = 11f,
                            )
                        } else {
                            Text(text = icon, color = badgeTxt, fontSize = badgeFontSize)
                        }
                    }
                    Text(
                        text = badgeText,
                        style = badgeTextStyle,
                    )
                }
            }

            // SPEC-070-A finalization PW-9 — pull all 5 plan-card show-flags
            // + selection styling colors from section.data. Mirrors iOS'
            // PlanCard rendering flags (PaywallConfig.swift:254-258).
            val showPlanIcons = section.data?.show_plan_icons ?: false
            val showPlanImages = section.data?.show_plan_images ?: false
            val showPlanSubtitles = section.data?.show_plan_subtitles ?: false
            val showPlanFeatures = section.data?.show_plan_features ?: false
            val showSavings = section.data?.show_savings ?: false
            val subtitlePosition = section.data?.subtitle_position ?: "below_price"
            val selectedTextColor = section.data?.selected_text_color?.let { parseHexColor(it) }
            val unselectedBorderColor = section.data?.unselected_border_color?.let { parseHexColor(it) }
            val unselectedBgColor = section.data?.unselected_bg_color?.let { parseHexColor(it) }
            val customSelectedBorder = section.data?.selected_border_color?.let { parseHexColor(it) }
            val customSelectedBg = section.data?.selected_bg_color?.let { parseHexColor(it) }

            @Composable
            fun PlanCard(plan: PaywallPlan, planIdx: Int, modifier: Modifier = Modifier) {
                val isSelected = selectedPlanId == plan.id
                // #589 — everything the card gives a plan, deliberately absent: no border, no
                // background, no badge, no subtitle, no selection control. Just the name and price
                // on one centred line.
                //
                // Still clickable and still calls `onPlanSelect` — the layout this exists for is one
                // prominent card with "or £4.99/month, cancel anytime" beneath it, and a caption you
                // cannot pick would be a different thing entirely. Selection shows as weight rather
                // than a control, because a radio circle is the card treatment this mode removes.
                // iOS parity: PlanCard.swift `textOnlyBody`.
                if ((plan.display_mode ?: "card") == "text_only") {
                    Text(
                        text = "${loc("plan.$planIdx.name", plan.displayName)} · ${loc("plan.$planIdx.price", plan.displayPrice)}",
                        fontSize = (plan.text_only_font_size ?: 13f).sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = plan.text_only_color?.let { StyleEngine.parseColor(it) } ?: Color(0xFF9CA3AF),
                        textAlign = TextAlign.Center,
                        modifier = modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {
                                role = Role.RadioButton
                                selected = isSelected
                            }
                            .clickable { onPlanSelect(plan.id) }
                            .padding(vertical = 6.dp),
                    )
                    return
                }
                val elevation = cardShadowElevation
                // PW-9: honor authored selected/unselected border + bg colors.
                val selectedBorderColor = customSelectedBorder ?: ai.appdna.sdk.AppDNA.brandAccentColor()
                val unselectedBorder = unselectedBorderColor
                val selectedBg = customSelectedBg ?: ai.appdna.sdk.AppDNA.brandAccentColor().copy(alpha = 0.1f)
                val unselectedBg = unselectedBgColor ?: Color.White.copy(alpha = 0.1f)
                // PW-9: text color flips when selected if author provided one.
                val resolvedTextColor = if (isSelected) {
                    selectedTextColor ?: Color.Unspecified
                } else {
                    Color.Unspecified
                }
                Card(
                    modifier = modifier
                        .planSelectionAnimation(config.animation?.plan_selection_animation, isSelected)
                        .then(
                            when {
                                isSelected -> Modifier.border(2.dp, selectedBorderColor, cardShape)
                                unselectedBorder != null -> Modifier.border(1.dp, unselectedBorder, cardShape)
                                else -> Modifier
                            }
                        )
                        // SPEC-401-A R85 (Lens C F1) — radio-button role + selected
                        // semantics so TalkBack reads "Selected, <plan name>" matching
                        // iOS SwiftUI Button + isSelected in PaywallRenderer.swift:2131.
                        // Was bare Card → "Card" with no selection state.
                        .semantics(mergeDescendants = true) {
                            role = Role.RadioButton
                            selected = isSelected
                        }
                        .then(if (cardHeight != null) Modifier.heightIn(min = cardHeight) else Modifier)
                        .clickable { onPlanSelect(plan.id) },
                    shape = cardShape,
                    elevation = CardDefaults.cardElevation(defaultElevation = elevation),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) selectedBg else unselectedBg,
                    ),
                ) {
                    Box {
                        // QA-R11 — reserve top space for the straddling
                        // badge so plan name / price don't sit underneath it.
                        // Mirrors iOS `PlanCard.swift:237-239` `.padding(.top,
                        // plan.badge != nil && badgePosition != "inline"
                        //     ? max(12, ((badgeFontSize ?? 11) + 8) / 2 + 2)
                        //     : 0)`.
                        val badgeReservedTop = if (plan.badge != null && badgePosition != "inline") {
                            maxOf(12f, ((section.data?.badge_font_size ?: 11f) + 8f) / 2f + 2f).dp
                        } else 0.dp
                        Column(
                            modifier = Modifier
                                .padding(cardPad)
                                .padding(top = badgeReservedTop),
                        ) {
                            // PW-9: optional plan icon (top of card; iOS SF
                            // Symbol rendered via IconView). Resolved from
                            // plan.icon when show_plan_icons flag is true.
                            if (showPlanIcons && !plan.icon.isNullOrBlank()) {
                                ai.appdna.sdk.core.IconView(
                                    ref = ai.appdna.sdk.core.IconReference(
                                        library = "material",
                                        name = plan.icon,
                                        size = 24f,
                                    ),
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            // PW-9: optional plan image (above name; iOS path).
                            if (showPlanImages && !plan.image_url.isNullOrBlank()) {
                                ai.appdna.sdk.core.NetworkImage(
                                    url = plan.image_url,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(80.dp),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            // PW-12 — `plan.displayName` mirrors iOS' computed
                            // `label ?? name` accessor; respect resolvedTextColor.
                            Text(
                                text = loc("plan.$planIdx.name", plan.displayName),
                                style = planNameStyle,
                                color = resolvedTextColor,
                            )

                            // PW-9: subtitle above price if `subtitle_position == "above_price"`.
                            if (showPlanSubtitles && subtitlePosition == "above_price" && !plan.description.isNullOrBlank()) {
                                Spacer(Modifier.height(2.dp))
                                // SPEC-438 (#544) — a coloured pill when the product authored
                                // one, plain text otherwise. `wrapContentWidth` is what makes it
                                // hug its text and read as a badge rather than a full-width banner.
                                val descBadge = plan.description_badge?.takeIf { it.enabled == true }
                                if (descBadge != null) {
                                    Text(
                                        text = loc("plan.$planIdx.description", plan.description),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = descBadge.text_color?.let { parseHexColor(it) } ?: Color.White,
                                        modifier = Modifier
                                            .wrapContentWidth()
                                            .background(
                                                descBadge.bg_color?.let { parseHexColor(it) } ?: parseHexColor("#15803D"),
                                                RoundedCornerShape((descBadge.corner_radius ?: 6f).dp),
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                } else {
                                    // #587 — the plan's own subtitle type wins over the section
                                    // style, for the same reason the price colour does. Alignment
                                    // needs `fillMaxWidth` as well as `textAlign`: without a width
                                    // to align WITHIN, a centred subtitle stays left-hugging and
                                    // only its wrapped second line moves, which reads as a bug.
                                    // iOS parity: PlanCard.swift `planSubtitleView`.
                                    Text(
                                        text = loc("plan.$planIdx.description", plan.description),
                                        fontSize = (plan.subtitle_font_size ?: 12f).sp,
                                        color = plan.subtitle_color?.takeIf { it.isNotBlank() }?.let { StyleEngine.parseColor(it) }
                                            ?: resolvedTextColor.takeIf { it != Color.Unspecified } ?: Color.Gray,
                                        textAlign = when (plan.subtitle_align) {
                                            "center" -> TextAlign.Center
                                            "right" -> TextAlign.End
                                            else -> TextAlign.Start
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }

                            Spacer(Modifier.height(4.dp))
                            // PW-12 — `plan.displayPrice` mirrors `price_display ?? price`.
                            // Round-MZ — render struck original_price_display beside the price
                            // (mirrors iOS PlanCard.swift Row-2). strikethrough_color is section-level.
                            // SPEC-438 (#548) — authored strikethrough size/gap, and the
                            // price_layout preset. Defaults reproduce the previous rendering
                            // exactly, so unauthored paywalls are unchanged.
                            val strikeColor = section.data?.strikethrough_color?.let { parseHexColor(it) }
                                ?: parseHexColor("#9CA3AF")
                            val strikeSize = (section.data?.strikethrough_font_size ?: 12f).sp
                            val strikeGap = (section.data?.strikethrough_gap ?: 4f).dp
                            val struck = plan.original_price_display?.takeIf { it.isNotBlank() }
                            val chargedTotal = plan.price_total_display?.takeIf { it.isNotBlank() }
                            // #588 — this plan's own price colour beats BOTH the section's price
                            // style and the selected/unselected text colour. Section styling paints
                            // every plan the same; the point of the field is to make one tier's
                            // price stand out, so anything that could override it would defeat it.
                            // iOS parity: PlanCard.swift `planPriceColor`.
                            val planPriceColor = plan.price_color?.takeIf { it.isNotBlank() }
                                ?.let { StyleEngine.parseColor(it) } ?: resolvedTextColor

                            if ((section.data?.price_layout ?: "inline") == "headline_stacked") {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = loc("plan.$planIdx.price", plan.displayPrice),
                                        style = priceStyle,
                                        color = planPriceColor,
                                    )
                                    if (struck != null || chargedTotal != null) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(strikeGap),
                                        ) {
                                            struck?.let {
                                                Text(
                                                    text = it,
                                                    fontSize = strikeSize,
                                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                                                    color = strikeColor,
                                                )
                                            }
                                            chargedTotal?.let {
                                                Text(text = it, fontSize = strikeSize, color = resolvedTextColor)
                                            }
                                        }
                                    }
                                }
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(strikeGap),
                                ) {
                                    struck?.let {
                                        Text(
                                            text = it,
                                            fontSize = strikeSize,
                                            textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                                            // Unset strikethrough_color defaults to #9CA3AF to match
                                            // iOS PlanCard.swift + PaywallPreview.tsx.
                                            color = strikeColor,
                                        )
                                    }
                                    Text(
                                        text = loc("plan.$planIdx.price", plan.displayPrice),
                                        style = priceStyle,
                                        color = planPriceColor,
                                    )
                                }
                            }
                            // Audit pass-8 — drop the standalone `plan.period` line to match
                            // iOS PlanCard, which never renders plan.period (PlanCard.swift:27-29
                            // defines only an unused periodTextStyle) and the console preview,
                            // which omits it. `period` isn't authored via the console (not in
                            // PlanSchema; billing_period is folded into price_display), so a
                            // non-console publisher setting `period` previously got a divergent
                            // extra line here. periodStyle is still used by other layouts.

                            // Round-MZ — post-price block order mirrors iOS PlanCard.swift:
                            // trial → subtitle(below) → divider → savings → features.
                            // Was subtitle → features → savings → trial (divergent).

                            // PW-12 — `plan.trialLabel` shows trial copy if present
                            // (computed from `trial?.label ?? trial_duration`).
                            plan.trialLabel?.takeIf { it.isNotBlank() }?.let { trialText ->
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = loc("plan.$planIdx.trial", trialText),
                                    style = trialStyle,
                                    // When selected the trial matches name/price (authored selected_text_color,
                                    // else default primary). When unselected, defer to trialStyle.color —
                                    // which is the authored trial_label color, or the brand-accent base —
                                    // so an authored color wins (iOS + preview parity).
                                    color = if (isSelected) (selectedTextColor ?: Color.Unspecified) else Color.Unspecified,
                                )
                            }

                            // PW-9: subtitle below price (default position).
                            if (showPlanSubtitles && subtitlePosition != "above_price" && !plan.description.isNullOrBlank()) {
                                Spacer(Modifier.height(4.dp))
                                // SPEC-438 (#544) — a coloured pill when the product authored
                                // one, plain text otherwise. `wrapContentWidth` is what makes it
                                // hug its text and read as a badge rather than a full-width banner.
                                val descBadge = plan.description_badge?.takeIf { it.enabled == true }
                                if (descBadge != null) {
                                    Text(
                                        text = loc("plan.$planIdx.description", plan.description),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = descBadge.text_color?.let { parseHexColor(it) } ?: Color.White,
                                        modifier = Modifier
                                            .wrapContentWidth()
                                            .background(
                                                descBadge.bg_color?.let { parseHexColor(it) } ?: parseHexColor("#15803D"),
                                                RoundedCornerShape((descBadge.corner_radius ?: 6f).dp),
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                } else {
                                    // #587 — the plan's own subtitle type wins over the section
                                    // style, for the same reason the price colour does. Alignment
                                    // needs `fillMaxWidth` as well as `textAlign`: without a width
                                    // to align WITHIN, a centred subtitle stays left-hugging and
                                    // only its wrapped second line moves, which reads as a bug.
                                    // iOS parity: PlanCard.swift `planSubtitleView`.
                                    Text(
                                        text = loc("plan.$planIdx.description", plan.description),
                                        fontSize = (plan.subtitle_font_size ?: 12f).sp,
                                        color = plan.subtitle_color?.takeIf { it.isNotBlank() }?.let { StyleEngine.parseColor(it) }
                                            ?: resolvedTextColor.takeIf { it != Color.Unspecified } ?: Color.Gray,
                                        textAlign = when (plan.subtitle_align) {
                                            "center" -> TextAlign.Center
                                            "right" -> TextAlign.End
                                            else -> TextAlign.Start
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }

                            // Round-MZ — per-plan divider between price/subtitle and
                            // savings/features (mirrors iOS PlanCard.swift:150-155,
                            // default #E5E7EB). Decoded + editable + previewed; was
                            // silently dropped here.
                            if (section.data?.show_divider == true) {
                                Spacer(Modifier.height(2.dp))
                                Divider(color = section.data?.divider_color?.let { parseHexColor(it) } ?: parseHexColor("#E5E7EB"))
                                Spacer(Modifier.height(2.dp))
                            }

                            // PW-9: per-plan savings text (typically "Save 20%").
                            // Mirror iOS PlanCard.swift:152 — base green is
                            // #22C55E (Tailwind green-500), flipping to
                            // selectedTextColor when the plan is selected so
                            // it stays readable against custom selected_bg.
                            if (showSavings && !plan.savings_text.isNullOrBlank()) {
                                Spacer(Modifier.height(4.dp))
                                val savingsColor = if (isSelected && selectedTextColor != null) {
                                    selectedTextColor
                                } else {
                                    Color(0xFF22C55E)
                                }
                                Text(
                                    text = loc("plan.$planIdx.savings", plan.savings_text),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = savingsColor,
                                )
                            }

                            // PW-9: per-plan feature checkmark list.
                            if (showPlanFeatures && !plan.features.isNullOrEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                // R88 — checkmark color matches iOS PlanCard.swift:162
                                // `Color(hex: "#6366F1")` (indigo). When the plan is
                                // selected and the host configured a custom
                                // selectedTextColor, iOS uses that color for the
                                // checkmark to keep contrast against the selected
                                // background — mirrored here via resolvedTextColor.
                                // Was hardcoded #10B981 (emerald green) which
                                // diverged visibly from iOS at default theme.
                                val featureCheckColor = if (isSelected && resolvedTextColor != Color.Unspecified) {
                                    resolvedTextColor
                                } else {
                                    ai.appdna.sdk.AppDNA.brandAccentColor()
                                }
                                plan.features.forEachIndexed { fIdx, feature ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = "✓ ", color = featureCheckColor, fontSize = 13.sp)
                                        Text(
                                            text = loc("plan.$planIdx.feature.$fIdx", feature),
                                            fontSize = 13.sp,
                                            color = resolvedTextColor,
                                        )
                                    }
                                    Spacer(Modifier.height(2.dp))
                                }
                            }

                            plan.badge?.let {
                                if (badgePosition == "inline") {
                                    Spacer(Modifier.height(8.dp))
                                    BadgeView(loc("plan.$planIdx.badge", it))
                                }
                            }
                        }
                        // QA-R11 — badge straddles the card's top edge
                        // (half above, half on) instead of sitting inside the
                        // card content area. Mirrors iOS `PlanCard.swift:217-228`
                        // `.overlay(alignment: badgeAlignment) { ... .offset(y:
                        // -(((badgeFontSize ?? 11) + 8) / 2)) }`. The card's
                        // top padding is also bumped (see `cardPad` adjustment
                        // below) so the title doesn't sit underneath the
                        // straddling badge.
                        plan.badge?.let { badge ->
                            if (badgePosition != "inline") {
                                val alignment = when (badgePosition) {
                                    "top_left" -> Alignment.TopStart
                                    "top_center" -> Alignment.TopCenter
                                    else -> Alignment.TopEnd // top_right default
                                }
                                // iOS uses badge_font + vertical_padding (4+4=8)
                                // / 2 as the offset. Mirror with badge font size
                                // + 8dp total vertical padding.
                                val badgeOverhang = ((section.data?.badge_font_size ?: 11f) + 8f) / 2f
                                Box(
                                    modifier = Modifier
                                        .align(alignment)
                                        .offset(y = (-badgeOverhang).dp)
                                        .padding(horizontal = 8.dp),
                                ) {
                                    BadgeView(loc("plan.$planIdx.badge", badge))
                                }
                            }
                        }
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } }) {
                when (displayStyle) {
                    "horizontal_scroll", "carousel_cards", "carousel" -> {
                        // Horizontally scrollable plan cards / carousel
                        val pagerState = rememberPagerState(
                            initialPage = plans.indexOfFirst { it.id == selectedPlanId }.coerceAtLeast(0),
                            pageCount = { plans.size }
                        )
                        HorizontalPager(
                            state = pagerState,
                            contentPadding = PaddingValues(horizontal = 48.dp),
                            pageSpacing = cardGap,
                        ) { planIdx ->
                            PlanCard(plan = plans[planIdx], planIdx = planIdx, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    "segmented_toggle", "segmented" -> {
                        // Mirror iOS PaywallRenderer.swift:1762-1783 — native
                        // segmented control followed by a price line for the
                        // currently-selected plan. Material3 SingleChoice
                        // SegmentedButtonRow is the Compose analogue of
                        // Picker(.segmented).
                        val selectedIdx = plans.indexOfFirst { it.id == selectedPlanId }
                            .coerceAtLeast(0)
                        val selectedPlan = plans.getOrNull(selectedIdx)
                        // QA-R7 — explicit SegmentedButton colors so
                        // the active segment doesn't fall back to
                        // `MaterialTheme.colorScheme.primary` (the M3 default
                        // purple `#D0BCFF` / `#6750A4`). iOS uses
                        // `Picker(.segmented)` with the iOS-canonical indigo
                        // `#6366F1` for the active fill. Authored
                        // `selected_bg_color` / `selected_border_color` win.
                        val segActiveBg = customSelectedBg ?: ai.appdna.sdk.AppDNA.brandAccentColor().copy(alpha = 0.2f)
                        val segActiveBorder = customSelectedBorder ?: ai.appdna.sdk.AppDNA.brandAccentColor()
                        val segInactiveBg = unselectedBgColor ?: Color.Transparent
                        val segInactiveBorder = unselectedBorderColor ?: Color.White.copy(alpha = 0.2f)
                        val segActiveText = selectedTextColor ?: Color.White
                        val segInactiveText = Color.White.copy(alpha = 0.7f)
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            plans.forEachIndexed { planIdx, plan ->
                                SegmentedButton(
                                    selected = selectedPlanId == plan.id,
                                    onClick = { onPlanSelect(plan.id) },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = planIdx,
                                        count = plans.size,
                                    ),
                                    colors = SegmentedButtonDefaults.colors(
                                        activeContainerColor = segActiveBg,
                                        activeBorderColor = segActiveBorder,
                                        activeContentColor = segActiveText,
                                        inactiveContainerColor = segInactiveBg,
                                        inactiveBorderColor = segInactiveBorder,
                                        inactiveContentColor = segInactiveText,
                                    ),
                                ) {
                                    Text(
                                        text = loc("plan.$planIdx.name", plan.displayName),
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        // Compose defaults overflow to Clip, which cuts a long price MID-GLYPH
                                        // with no indication anything is missing. Ellipsis at least tells the
                                        // reader the value is truncated. Same long-string class as #545.
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        selectedPlan?.let { sp ->
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = loc("plan.$selectedIdx.price", sp.displayPrice) +
                                    (sp.period?.let { " / ${loc("plan.$selectedIdx.period", it)}" } ?: ""),
                                style = priceStyle,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    "pill_selector", "minimal_chips" -> {
                        // Mirror iOS PaywallRenderer.swift:1736-1759 — horizontal
                        // ScrollView so 4+ pills stay readable (previously each
                        // pill was `.weight(1f)` and compressed to unreadable
                        // widths). Each chip sizes to its content.
                        val pillScrollState = rememberScrollState()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(pillScrollState)
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(cardGap),
                        ) {
                            plans.forEachIndexed { planIdx, plan ->
                                val isSelected = selectedPlanId == plan.id
                                Box(
                                    modifier = Modifier
                                        .clip(cardShape)
                                        .background(
                                            if (isSelected) ai.appdna.sdk.AppDNA.brandAccentColor() else Color.White.copy(alpha = 0.1f),
                                            cardShape,
                                        )
                                        .border(
                                            if (isSelected) 0.dp else 1.dp,
                                            if (isSelected) Color.Transparent else Color.White.copy(alpha = 0.3f),
                                            cardShape,
                                        )
                                        .clickable { onPlanSelect(plan.id) }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = loc("plan.$planIdx.name", plan.displayName),
                                            style = planNameStyle.copy(fontSize = 14.sp),
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = loc("plan.$planIdx.price", plan.displayPrice),
                                            style = priceStyle.copy(fontSize = 14.sp),
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                        plan.badge?.let {
                                            Spacer(Modifier.height(4.dp))
                                            BadgeView(loc("plan.$planIdx.badge", it))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "radio_list" -> {
                        // Column with radio buttons
                        plans.forEachIndexed { planIdx, plan ->
                            val isSelected = selectedPlanId == plan.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = cardGap / 2)
                                    .clip(cardShape)
                                    .background(
                                        if (isSelected) ai.appdna.sdk.AppDNA.brandAccentColor().copy(alpha = 0.1f) else Color.Transparent,
                                        cardShape,
                                    )
                                    .border(
                                        if (isSelected) 2.dp else 1.dp,
                                        if (isSelected) ai.appdna.sdk.AppDNA.brandAccentColor() else Color.White.copy(alpha = 0.2f),
                                        cardShape,
                                    )
                                    .clickable { onPlanSelect(plan.id) }
                                    .padding(cardPad),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.RadioButton(
                                    selected = isSelected,
                                    onClick = { onPlanSelect(plan.id) },
                                    colors = androidx.compose.material3.RadioButtonDefaults.colors(
                                        selectedColor = ai.appdna.sdk.AppDNA.brandAccentColor(),
                                        unselectedColor = Color.White.copy(alpha = 0.5f),
                                    ),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = loc("plan.$planIdx.name", plan.displayName), style = planNameStyle)
                                    plan.period?.let { Text(text = loc("plan.$planIdx.period", it), style = periodStyle) }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(text = loc("plan.$planIdx.price", plan.displayPrice), style = priceStyle)
                                    plan.badge?.let {
                                        Spacer(Modifier.height(4.dp))
                                        BadgeView(loc("plan.$planIdx.badge", it))
                                    }
                                }
                            }
                        }
                    }
                    "accordion" -> {
                        // Expandable vertical list — selected plan shows expanded details
                        plans.forEachIndexed { planIdx, plan ->
                            val isSelected = selectedPlanId == plan.id
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = cardGap / 2)
                                    .planSelectionAnimation(config.animation?.plan_selection_animation, isSelected)
                                    .then(
                                        if (isSelected) Modifier.border(2.dp, ai.appdna.sdk.AppDNA.brandAccentColor(), cardShape) else Modifier
                                    )
                                    .then(if (cardHeight != null) Modifier.heightIn(min = cardHeight) else Modifier)
                                    .clickable { onPlanSelect(plan.id) },
                                shape = cardShape,
                                colors = CardDefaults.cardColors(
                                    // SPEC-419 — selected card BACKGROUND must use the authored
                                    // selected_bg_color (e.g. nurrai #2c374c) so it's visually DISTINCT
                                    // from the unselected card. Do NOT default it to the brand accent:
                                    // a light brand (e.g. white) collapses onto the white-10% unselected
                                    // fill and the selection becomes invisible. Brand drives foreground
                                    // accents (border/badge/check), not the card fill.
                                    containerColor = if (isSelected) (customSelectedBg ?: Color.White.copy(alpha = 0.16f)) else (unselectedBgColor ?: Color.White.copy(alpha = 0.06f))
                                ),
                            ) {
                                Column(modifier = Modifier.padding(cardPad)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(text = loc("plan.$planIdx.name", plan.displayName), style = planNameStyle)
                                        Text(text = loc("plan.$planIdx.price", plan.displayPrice), style = priceStyle)
                                    }
                                    if (isSelected) {
                                        Spacer(Modifier.height(8.dp))
                                        plan.period?.let { Text(text = loc("plan.$planIdx.period", it), style = periodStyle) }
                                        plan.badge?.let {
                                            Spacer(Modifier.height(4.dp))
                                            BadgeView(loc("plan.$planIdx.badge", it))
                                        }
                                        plan.trialLabel?.let {
                                            Spacer(Modifier.height(4.dp))
                                            Text(text = it, style = periodStyle)
                                        }
                                        // SPEC-401-A R83 (Lens A P1) — render expanded body
                                        // matching iOS PaywallRenderer.swift:2080-2105:
                                        // description, savings_text (green bold), and full
                                        // features list with leading checkmark icons. DTO
                                        // fields exist in PaywallConfig.kt:400-403; was a
                                        // pure renderer gap. Without this, accordion sold
                                        // as 'expandable details' showed almost nothing
                                        // when expanded.
                                        plan.description?.takeIf { it.isNotBlank() }?.let { desc ->
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                text = loc("plan.$planIdx.description", desc),
                                                style = periodStyle,
                                            )
                                        }
                                        plan.savings_text?.takeIf { it.isNotBlank() }?.let { savings ->
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = loc("plan.$planIdx.savings", savings),
                                                color = parseHexColor("#22C55E"),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                            )
                                        }
                                        plan.features?.takeIf { it.isNotEmpty() }?.let { features ->
                                            Spacer(Modifier.height(8.dp))
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                features.forEachIndexed { fIdx, feature ->
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            text = "✓",
                                                            color = parseHexColor("#22C55E"),
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 13.sp,
                                                        )
                                                        Spacer(Modifier.width(6.dp))
                                                        Text(
                                                            text = loc("plan.$planIdx.feature.$fIdx", feature),
                                                            style = periodStyle,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "grid" -> {
                        // 2-column grid
                        val chunked = plans.chunked(2)
                        chunked.forEachIndexed { chunkIdx, row ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(cardGap)) {
                                row.forEachIndexed { colIdx, plan ->
                                    val planIdx = chunkIdx * 2 + colIdx
                                    PlanCard(plan = plan, planIdx = planIdx, modifier = Modifier.weight(1f).padding(vertical = cardGap / 2))
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    "toggle_cards" -> {
                        // Row of toggle-style cards (2 plans side by side)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(cardGap),
                        ) {
                            plans.forEachIndexed { planIdx, plan ->
                                PlanCard(plan = plan, planIdx = planIdx, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    // SPEC-070-A finalization parity audit G1 — 5 plan
                    // display styles previously fell through to the
                    // vertical-stack default. Each iOS branch at
                    // PaywallRenderer.swift mapped to its own visual
                    // shape; Android now mirrors per iOS authoring intent.
                    "mini_cards" -> {
                        // iOS PaywallRenderer.swift:1774 — compact 2-col
                        // LazyVGrid; smaller padding than default cards.
                        plans.chunked(2).forEachIndexed { chunkIdx, row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = cardGap / 4),
                                horizontalArrangement = Arrangement.spacedBy(cardGap / 2),
                            ) {
                                row.forEachIndexed { colIdx, plan ->
                                    val planIdx = chunkIdx * 2 + colIdx
                                    PlanCard(
                                        plan = plan,
                                        planIdx = planIdx,
                                        modifier = Modifier.weight(1f).padding(vertical = cardGap / 4),
                                    )
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    "single_hero" -> {
                        // Mirror iOS PaywallRenderer.swift:2117-2122 — render
                        // only the hero (first) plan; collapse remaining
                        // plans under an expandable "More options" toggle so
                        // the funnel defaults to the highlighted plan.
                        plans.firstOrNull()?.let { hero ->
                            PlanCard(
                                plan = hero,
                                planIdx = 0,
                                modifier = Modifier.fillMaxWidth().padding(vertical = cardGap / 2),
                            )
                        }
                        if (plans.size > 1) {
                            var isHeroExpanded by remember { mutableStateOf(false) }
                            Spacer(Modifier.height(4.dp))
                            TextButton(
                                onClick = { isHeroExpanded = !isHeroExpanded },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = if (isHeroExpanded) "Hide options" else "More options",
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            androidx.compose.animation.AnimatedVisibility(visible = isHeroExpanded) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    plans.drop(1).forEachIndexed { idx, plan ->
                                        PlanCard(
                                            plan = plan,
                                            planIdx = idx + 1,
                                            modifier = Modifier.fillMaxWidth().padding(vertical = cardGap / 4),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    "product_as_cta" -> {
                        // iOS PaywallRenderer.swift:1993 — each plan IS
                        // a CTA button itself; tapping the plan triggers
                        // immediate purchase. Render each plan as a
                        // full-width filled button row labeled with
                        // displayPrice + name.
                        plans.forEachIndexed { planIdx, plan ->
                            val isSelected = selectedPlanId == plan.id
                            Button(
                                onClick = {
                                    // SPEC-070-A finalization B5 P2 — iOS
                                    // PaywallRenderer.swift:1993 fires the
                                    // CTA immediately on plan tap for the
                                    // product_as_cta layout. Match it: select
                                    // the plan THEN trigger purchase in the
                                    // same gesture so users don't have to
                                    // tap twice.
                                    onPlanSelect(plan.id)
                                    onCTATap()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = cardGap / 2)
                                    .planSelectionAnimation(config.animation?.plan_selection_animation, isSelected),
                                shape = cardShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) ai.appdna.sdk.AppDNA.brandAccentColor() else Color.White.copy(alpha = 0.1f),
                                    contentColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.9f),
                                ),
                                contentPadding = PaddingValues(horizontal = cardPad, vertical = cardPad),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = loc("plan.$planIdx.name", plan.displayName),
                                        style = planNameStyle,
                                    )
                                    Text(
                                        text = loc("plan.$planIdx.price", plan.displayPrice),
                                        style = priceStyle.copy(fontSize = 18.sp),
                                    )
                                }
                            }
                        }
                    }
                    "comparison_table", "comparison_cards", "feature_matrix", "pricing_table" -> {
                        // iOS PaywallRenderer.swift:1880 — feature × plan
                        // matrix. Android already renders a dedicated
                        // PaywallComparisonTableSection; here in the
                        // plan-section context, render each plan as a
                        // full-width card with its features list visible
                        // (PlanCard already supports show_features /
                        // features per PW-9).
                        plans.forEachIndexed { planIdx, plan ->
                            PlanCard(
                                plan = plan,
                                planIdx = planIdx,
                                modifier = Modifier.fillMaxWidth().padding(vertical = cardGap / 2),
                            )
                        }
                    }
                    "timeline_reveal", "timeline" -> {
                        // iOS PaywallRenderer.swift:1809 — vertical
                        // timeline (dot + line + horizontal-card per
                        // plan). Android: render each plan with a
                        // leading dot indicator + connecting line via a
                        // Row, plus the existing PlanCard.
                        plans.forEachIndexed { planIdx, plan ->
                            val isSelected = selectedPlanId == plan.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = cardGap / 2),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Timeline indicator (dot)
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(
                                            if (isSelected) ai.appdna.sdk.AppDNA.brandAccentColor() else Color.White.copy(alpha = 0.4f),
                                        ),
                                )
                                Spacer(Modifier.width(12.dp))
                                PlanCard(plan = plan, planIdx = planIdx, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    else -> {
                        // vertical_stack (default) — mirror iOS PlanCard.swift:
                        // name+price in a LEADING weight(1f) column (price never
                        // truncated; period on its own line), trailing radio circle,
                        // and the badge STRADDLING the card's top edge (offset up +
                        // reserved top padding) so it never lands on top of the price.
                        plans.forEachIndexed { planIdx, plan ->
                            val isSelected = selectedPlanId == plan.id
                            // iOS PlanCard.swift:39,51-56 — authored colors win.
                            val selBorder = customSelectedBorder ?: ai.appdna.sdk.AppDNA.brandAccentColor()
                            val unselBorder = unselectedBorderColor ?: Color.White.copy(alpha = 0.3f)
                            val radioColor = if (isSelected) (selectedTextColor ?: selBorder) else unselBorder
                            val planTextColor = if (isSelected) (selectedTextColor ?: Color.Unspecified) else Color.Unspecified
                            // iOS PlanCard.swift:245-247 — reserve overhang so the
                            // straddling badge isn't clipped and doesn't cover content.
                            val badgeTopReserve = if (!plan.badge.isNullOrBlank() && badgePosition != "inline")
                                maxOf(12f, ((section.data?.badge_font_size ?: 11f) + 8f) / 2f + 2f).dp else 0.dp
                            val badgeOverhang = ((section.data?.badge_font_size ?: 11f) + 8f) / 2f

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = badgeTopReserve, bottom = cardGap)
                            ) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .planSelectionAnimation(config.animation?.plan_selection_animation, isSelected)
                                        .then(
                                            // iOS: 2dp selected (authored color) / 1dp subtle unselected.
                                            if (isSelected) Modifier.border(2.dp, selBorder, cardShape)
                                            else Modifier.border(1.dp, unselBorder, cardShape)
                                        )
                                        .semantics(mergeDescendants = true) {
                                            role = Role.RadioButton
                                            selected = isSelected
                                        }
                                        .then(if (cardHeight != null) Modifier.heightIn(min = cardHeight) else Modifier)
                                        .clickable { onPlanSelect(plan.id) },
                                    shape = cardShape,
                                    elevation = CardDefaults.cardElevation(defaultElevation = if (cardShadowEnabled) 4.dp else 0.dp),
                                    colors = CardDefaults.cardColors(
                                        // SPEC-419 — authored selected_bg_color (nurrai #2c374c) wins;
                                        // SPEC-419 — unselected card must be OPAQUE + uniform. A
                                        // translucent white-10% let the navy PAGE GRADIENT bleed
                                        // through, giving a "strange gradient inside the card". Derive
                                        // an opaque subtle tone from the authored selected color (darker)
                                        // so it stays theme-aware + distinct; fall back to a subtle navy.
                                        containerColor = if (isSelected) (customSelectedBg ?: Color.White.copy(alpha = 0.16f))
                                            else (unselectedBgColor
                                                ?: customSelectedBg?.let { androidx.compose.ui.graphics.lerp(it, Color.Black, 0.40f) }
                                                ?: Color(0xFF1B2540))
                                    )
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                    // Round-31 — plan image, full-width at the card top (iOS
                                    // PlanCard.swift:75). Dropped on the DEFAULT layout like
                                    // trial/savings/features were; Android's own fun PlanCard (:1618)
                                    // already renders it for the other layouts.
                                    if (showPlanImages && !plan.image_url.isNullOrBlank()) {
                                        ai.appdna.sdk.core.NetworkImage(
                                            url = plan.image_url,
                                            modifier = Modifier.fillMaxWidth().height(80.dp),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(cardPad),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // iOS leading VStack — full width, no truncation.
                                        Column(modifier = Modifier.weight(1f)) {
                                            // Round-30 — subtitle/description text color falls back to a
                                            // muted grey (iOS PlanCard.swift secondary); accent for trial.
                                            val subtitleColor = planTextColor.takeIf { it != Color.Unspecified } ?: Color.Gray
                                            val trialColor = planTextColor.takeIf { it != Color.Unspecified } ?: ai.appdna.sdk.AppDNA.brandAccentColor()
                                            Text(text = loc("plan.$planIdx.name", plan.displayName), style = planNameStyle, color = planTextColor)
                                            Spacer(Modifier.height(2.dp))
                                            // Round-30 — subtitle ABOVE price (iOS PlanCard.swift:105).
                                            if (showPlanSubtitles && subtitlePosition == "above_price" && !plan.description.isNullOrBlank()) {
                                                run {
                                                    // SPEC-438 (#544) — pill when the product authored one.
                                                    val b = plan.description_badge?.takeIf { it.enabled == true }
                                                    if (b != null) {
                                                        Text(
                                                            text = loc("plan.$planIdx.description", plan.description),
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = b.text_color?.let { parseHexColor(it) } ?: Color.White,
                                                            modifier = Modifier
                                                                .wrapContentWidth()
                                                                .background(
                                                                    b.bg_color?.let { parseHexColor(it) } ?: parseHexColor("#15803D"),
                                                                    RoundedCornerShape((b.corner_radius ?: 6f).dp),
                                                                )
                                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                                        )
                                                    } else {
                                                        Text(text = loc("plan.$planIdx.description", plan.description), fontSize = 12.sp, color = subtitleColor)
                                                    }
                                                }
                                                Spacer(Modifier.height(2.dp))
                                            }
                                            // Round-MZ — render struck original_price_display beside the
                                            // price (mirrors iOS PlanCard.swift Row-2 + Android fun PlanCard).
                                            // The standalone plan.period line was dropped to match iOS
                                            // PlanCard (never renders plan.period) and fun PlanCard —
                                            // period is folded into price_display, not authored via console.
                                            // SPEC-438 (#548) — authored strikethrough size/gap + the
                                            // price_layout preset. Defaults reproduce the previous
                                            // rendering exactly, so unauthored paywalls are unchanged.
                                            val vsStrikeColor = section.data?.strikethrough_color?.let { parseHexColor(it) }
                                                ?: parseHexColor("#9CA3AF")
                                            val vsStrikeSize = (section.data?.strikethrough_font_size ?: 12f).sp
                                            val vsStrikeGap = (section.data?.strikethrough_gap ?: 4f).dp
                                            val vsStruck = plan.original_price_display?.takeIf { it.isNotBlank() }
                                            val vsTotal = plan.price_total_display?.takeIf { it.isNotBlank() }
                                            if ((section.data?.price_layout ?: "inline") == "headline_stacked") {
                                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Text(text = loc("plan.$planIdx.price", plan.displayPrice), style = priceStyle, color = planTextColor)
                                                    if (vsStruck != null || vsTotal != null) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(vsStrikeGap),
                                                        ) {
                                                            vsStruck?.let {
                                                                Text(
                                                                    text = it,
                                                                    fontSize = vsStrikeSize,
                                                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                                                                    color = vsStrikeColor,
                                                                )
                                                            }
                                                            vsTotal?.let {
                                                                Text(text = it, fontSize = vsStrikeSize, color = planTextColor)
                                                            }
                                                        }
                                                    }
                                                }
                                            } else {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(vsStrikeGap),
                                                ) {
                                                    vsStruck?.let {
                                                        Text(
                                                            text = it,
                                                            fontSize = vsStrikeSize,
                                                            textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                                                            color = vsStrikeColor,
                                                        )
                                                    }
                                                    Text(text = loc("plan.$planIdx.price", plan.displayPrice), style = priceStyle, color = planTextColor)
                                                }
                                            }
                                            // Round-30 — PARITY FIX: the default vertical_stack path
                                            // silently dropped trial/subtitle/savings/features that iOS
                                            // PlanCard.swift renders (free-trial + discount merchandising
                                            // was invisible on Android's DEFAULT paywall layout — revenue).
                                            // Mirror iOS order: trial → subtitle(below) → savings → features.
                                            plan.trialLabel?.takeIf { it.isNotBlank() }?.let { trialText ->
                                                Spacer(Modifier.height(4.dp))
                                                Text(text = loc("plan.$planIdx.trial", trialText), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = trialColor)
                                            }
                                            if (showPlanSubtitles && subtitlePosition != "above_price" && !plan.description.isNullOrBlank()) {
                                                Spacer(Modifier.height(4.dp))
                                                run {
                                                    // SPEC-438 (#544) — pill when the product authored one.
                                                    val b = plan.description_badge?.takeIf { it.enabled == true }
                                                    if (b != null) {
                                                        Text(
                                                            text = loc("plan.$planIdx.description", plan.description),
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = b.text_color?.let { parseHexColor(it) } ?: Color.White,
                                                            modifier = Modifier
                                                                .wrapContentWidth()
                                                                .background(
                                                                    b.bg_color?.let { parseHexColor(it) } ?: parseHexColor("#15803D"),
                                                                    RoundedCornerShape((b.corner_radius ?: 6f).dp),
                                                                )
                                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                                        )
                                                    } else {
                                                        Text(text = loc("plan.$planIdx.description", plan.description), fontSize = 12.sp, color = subtitleColor)
                                                    }
                                                }
                                            }
                                            // Round-MZ — per-plan divider between price/subtitle and
                                            // savings (mirrors iOS PlanCard.swift order + Android fun
                                            // PlanCard, default #E5E7EB). Was silently dropped on the
                                            // default vertical_stack layout.
                                            if (section.data?.show_divider == true) {
                                                Spacer(Modifier.height(2.dp))
                                                Divider(color = section.data?.divider_color?.let { parseHexColor(it) } ?: parseHexColor("#E5E7EB"))
                                                Spacer(Modifier.height(2.dp))
                                            }
                                            if (showSavings && !plan.savings_text.isNullOrBlank()) {
                                                Spacer(Modifier.height(4.dp))
                                                val savingsColor = if (isSelected && selectedTextColor != null) selectedTextColor else Color(0xFF22C55E)
                                                Text(text = loc("plan.$planIdx.savings", plan.savings_text), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = savingsColor)
                                            }
                                            if (showPlanFeatures && !plan.features.isNullOrEmpty()) {
                                                Spacer(Modifier.height(8.dp))
                                                val featureCheckColor = if (isSelected && planTextColor != Color.Unspecified) planTextColor else ai.appdna.sdk.AppDNA.brandAccentColor()
                                                plan.features.forEachIndexed { fIdx, feature ->
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(text = "✓ ", color = featureCheckColor, fontSize = 13.sp)
                                                        Text(text = loc("plan.$planIdx.feature.$fIdx", feature), fontSize = 13.sp, color = planTextColor)
                                                    }
                                                    Spacer(Modifier.height(2.dp))
                                                }
                                            }
                                            plan.badge?.takeIf { it.isNotBlank() }?.let {
                                                if (badgePosition == "inline") {
                                                    Spacer(Modifier.height(8.dp))
                                                    BadgeView(loc("plan.$planIdx.badge", it))
                                                }
                                            }
                                        }
                                        // Round-31 — plan icon, trailing before the radio (iOS
                                        // PlanCard.swift:178). Dropped on the DEFAULT layout; Android's
                                        // fun PlanCard (:1606) renders it for the other layouts.
                                        if (showPlanIcons && !plan.icon.isNullOrBlank()) {
                                            ai.appdna.sdk.core.IconView(
                                                ref = ai.appdna.sdk.core.IconReference(
                                                    library = "material",
                                                    name = plan.icon,
                                                    size = 24f,
                                                ),
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        // iOS PlanCard.swift:182-184 — trailing radio indicator.
                                        Icon(
                                            imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = radioColor,
                                            modifier = Modifier.padding(start = 12.dp).size(24.dp),
                                        )
                                    }
                                    }
                                }
                                // iOS PlanCard.swift:225-238 — badge straddles the top edge.
                                plan.badge?.takeIf { it.isNotBlank() }?.let { badge ->
                                    if (badgePosition != "inline") {
                                        val alignment = when (badgePosition) {
                                            "top_left" -> Alignment.TopStart
                                            "top_center" -> Alignment.TopCenter
                                            else -> Alignment.TopEnd
                                        }
                                        Box(
                                            modifier = Modifier
                                                .align(alignment)
                                                .offset(y = (-badgeOverhang).dp)
                                                .padding(horizontal = 8.dp),
                                        ) {
                                            BadgeView(loc("plan.$planIdx.badge", badge))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // SPEC-070-A finalization B5#P1 — restore button moved to the
                // CTA section under `cta.show_restore` (PW-10). Previously this
                // hardcoded button rendered unconditionally below `plans`, so
                // restore appeared TWICE when CTA show_restore was true and
                // appeared even when authors disabled it. Now the CTA section
                // is the single source of truth, matching iOS.
            }
        }
        "cta" -> {
            // SPEC-070-A finalization PW-10 — full CTA section with optional
            // restore button placed above OR below the main CTA, plus CTA
            // gradient + cta_height + cta_font_size honored from authored
            // values. Mirrors iOS PaywallRenderer CTA branch with the
            // RestoreLinkView wrapping (PaywallRenderer.swift:146-205).
            // QA-R8 — mirror iOS CTAButton.swift:28-45 full resolution
            // chain. Was reading only `section.style.elements.button.background.color`
            // and falling back to `Color(0xFF6366F1)` (indigo). Console-authored
            // `cta.bg_color` / `cta_bg_color` (from the simpler Content-tab fields
            // most authors use) was silently dropped, so a paywall configured
            // with `cta: { bg_color: "#FFFFFF", text_color: "#000000" }` rendered
            // as the indigo default → "text-only with no background" QA report.
            // SPEC-419 — guard each step with isNotBlank(). An empty-string
            // bg_color hit StyleEngine.parseColor("") → Color.Transparent, which
            // is non-null and SHORT-CIRCUITED the chain (CTA went transparent =
            // dark bg showing through = "plain black"). iOS `??` over a nil falls
            // through to the brand accent; mirror that by treating "" as absent.
            // SPEC-419 — iOS PaywallConfig.swift:511 reads `styleObj?.bg_color` FIRST:
            // the console writes `cta: { style: { bg_color, text_color, corner_radius } }`,
            // a NESTED object. Android's PaywallCTA.style is a raw `Any?` that was never
            // read, so nurrai's `cta.style.bg_color = #ffffff` (white button) was dropped
            // and the CTA fell through to the brand accent / transparent = "plain black".
            // SPEC-419 — resolve to null (NOT Transparent) for blank/invalid/
            // "transparent"/shorthand values so the ?: chain CONTINUES to the
            // next authored color instead of short-circuiting and painting the
            // navy PAGE bg through a transparent button. StyleEngine.parseColor
            // returns Color.Transparent (non-null) for those, which hijacked the
            // chain. iOS `??` only falls through on nil; mirror that.
            fun ctaColor(s: String?): Color? = s?.takeIf { it.isNotBlank() }?.let {
                val c = StyleEngine.parseColor(it)
                if (c == Color.Transparent) null else c
            }
            val ctaStyleMap = config.cta?.style as? Map<*, *>
            val sectionCtaStyleMap = section.data?.cta?.style as? Map<*, *>
            // SPEC-419 — capture whether an EXPLICIT solid CTA bg_color was authored
            // (vs the brand-accent fallback). An explicit solid color WINS over a
            // cta_gradient: the gradient path collapses to the page-navy via the
            // degenerate pixel-offset brush below, so honoring the author's #ffffff
            // paints the intended white button.
            val explicitCtaBg: Color? = ctaColor(ctaStyleMap?.get("bg_color") as? String)
                ?: ctaColor(sectionCtaStyleMap?.get("bg_color") as? String)
                ?: ctaColor(section.style?.elements?.get("button")?.background?.color)
                ?: ctaColor(config.cta?.bg_color)
                ?: ctaColor(section.data?.cta?.bg_color)
                ?: ctaColor(section.data?.cta_bg_color)
            val buttonBgColor = explicitCtaBg ?: ai.appdna.sdk.AppDNA.brandAccentColor()
            // QA-R8 — same chain for the button text color. iOS `CTAButton.swift:40-45`
            // reads `buttonTextStyle.color` then `cta?.resolvedTextColor`
            // (= `styleObj?.text_color ?? text_color ?? "#FFFFFF"`).
            val buttonTextColor = ctaColor(ctaStyleMap?.get("text_color") as? String)
                ?: ctaColor(sectionCtaStyleMap?.get("text_color") as? String)
                ?: ctaColor(section.style?.elements?.get("button")?.text_style?.color)
                ?: ctaColor(config.cta?.text_color)
                ?: ctaColor(section.data?.cta?.text_color)
                ?: ctaColor(section.data?.cta_text_color)
                ?: Color.White
            // PW-10: prefer authored cta_font_size over the 17.sp baseline.
            val ctaFontSize = (section.data?.cta_font_size ?: section.data?.cta?.font_size?.toFloat() ?: 17f).sp
            val ctaHeight = (section.data?.cta_height ?: section.data?.cta?.height?.toFloat() ?: 56f).dp
            val buttonTextStyle = StyleEngine.applyTextStyle(
                TextStyle(fontWeight = resolveCtaWeight(section.data?.cta_font_weight), fontSize = ctaFontSize),
                section.style?.elements?.get("button")?.text_style,
            )
            // PW-10 / iOS PaywallRenderer.swift:1052+ — CTA gradient brush
            // built from gradient.stops + angle. Falls back to solid color
            // when no gradient is authored.
            val ctaGradient = section.data?.cta_gradient
            val ctaBrush: Brush? = ctaGradient?.takeIf { (it.stops?.size ?: 0) >= 2 }?.let { g ->
                val stops = g.stops!!.mapNotNull { s ->
                    val c = s.color ?: return@mapNotNull null
                    val pos = s.position?.toFloat() ?: 0f
                    pos to parseHexColor(c)
                }.toTypedArray()
                if (stops.size < 2) return@let null
                val rads = (g.angle ?: 90.0) * Math.PI / 180.0
                // SPEC-419 — Compose Offset is in PIXELS, not 0..1 fractions. Passing
                // fractions collapsed the gradient into the first pixel (clamped to the
                // navy end-stop). Map fractions to real pixels via a size-aware
                // ShaderBrush so it renders like iOS (UnitPoint).
                object : androidx.compose.ui.graphics.ShaderBrush() {
                    override fun createShader(size: androidx.compose.ui.geometry.Size): android.graphics.Shader {
                        val sx = (0.5 - kotlin.math.sin(rads) / 2).toFloat()
                        val sy = (0.5 + kotlin.math.cos(rads) / 2).toFloat()
                        val ex = (0.5 + kotlin.math.sin(rads) / 2).toFloat()
                        val ey = (0.5 - kotlin.math.cos(rads) / 2).toFloat()
                        return androidx.compose.ui.graphics.LinearGradientShader(
                            from = androidx.compose.ui.geometry.Offset(sx * size.width, sy * size.height),
                            to = androidx.compose.ui.geometry.Offset(ex * size.width, ey * size.height),
                            colors = stops.map { it.second },
                            colorStops = stops.map { it.first },
                        )
                    }
                }
            }

            // PW-10 — restore link rendering helper.
            val showRestoreLink = section.data?.show_restore == true
            val restorePosition = section.data?.restore_position ?: "below"
            val restoreText = section.data?.restore_text ?: "Restore Purchases"
            val restoreColor = section.data?.restore_text_color?.let { parseHexColor(it) }
                ?: Color.White.copy(alpha = 0.6f)
            val restoreFontSize = (section.data?.restore_font_size ?: 13f).sp
            // SPEC-490 (#651 item 1) — the CTA↔Restore gap. Unset keeps the previous hardcoded 8.dp.
            val restoreGap = (section.data?.restore_gap ?: 8f).dp

            @Composable
            fun RestoreLink() {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                // QA-R13 — Restore Purchases link is centred inside its
                // CTA section row to match iOS `CTAButton.swift:94-126` which
                // uses a `Button` whose label sits inside a `VStack(spacing:
                // 8) { ... restoreButton ... }` — SwiftUI VStack centers all
                // labels horizontally by default. Android `Column` defaults to
                // Start; without `Alignment.CenterHorizontally` on the column
                // OR `.fillMaxWidth() + textAlign = Center` on the text, the
                // Restore link sticks to the left edge.
                Text(
                    text = loc("cta.restore_text", restoreText),
                    color = restoreColor,
                    fontSize = restoreFontSize,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRestore() }
                        .padding(vertical = 8.dp)
                        .semantics {
                            contentDescription = ctx.getString(R.string.appdna_a11y_paywall_restore)
                        },
                )
            }

            Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                if (showRestoreLink && restorePosition == "above") {
                    RestoreLink()
                    Spacer(Modifier.height(restoreGap))
                }
                // CTA Button — paints gradient if present, else solid color.
                // SPEC-401-A R70 (Lens C P1) — gate enabled state by both
                // `!isPurchasing` AND `selectedPlanId != null` matching iOS
                // `.disabled(isPurchasing || selectedPlanId == nil)` at
                // PaywallRenderer.swift:205. Plus 50% alpha when disabled
                // mirrors SwiftUI's auto-dim on `.disabled`. Was firing
                // onCTATap() with no plan selected — purchase no-op'd or
                // bought a default-fallback plan.
                val ctaEnabled = !isPurchasing && selectedPlanId != null
                // QA-R9 — disable Compose's default M3 ripple so the CTA
                // doesn't flash `colorScheme.primary` (purple on the default
                // M3 dark scheme) on tap. iOS `Button` has no ripple equivalent
                // — taps just dim. Compose `clickable {}` uses LocalIndication
                // which is a Material ripple by default; we'd otherwise need
                // to override with a tinted ripple matching the authored CTA
                // colour, which is more bookkeeping than this UX warrants.
                val ctaInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ctaHeight)
                        .ctaAnimation(config.animation?.cta_animation)
                        .run { with(StyleEngine) { applyContainerStyle(section.style?.container) } }
                        // R89 — CTA corner_radius default 12 matches iOS
                        // PaywallCTA.resolvedCornerRadius at PaywallConfig.swift:515
                        // `styleObj?.corner_radius ?? corner_radius ?? 12.0`. Was 14.
                        // SPEC-419 — apply the fill DIRECTLY with the shape (was .clip()+.then()
                        // which rendered transparent on-device despite buttonBgColor=white per logcat).
                        // explicit solid bg_color (#ffffff) WINS over a cta_gradient.
                        .background(
                            brush = if (explicitCtaBg == null && ctaBrush != null) ctaBrush
                                else androidx.compose.ui.graphics.SolidColor(buttonBgColor),
                            // Audit pass-8 — the CTA-section `data.cta.corner_radius` is
                            // near-always null; the console authors the main CTA radius via
                            // `cta.style.corner_radius`, parsed into `config.cta.corner_radius`
                            // (PaywallConfig.kt:885) + surfaced through ctaStyleMap. Resolve the
                            // shape from the same source used for bg/text (iOS CTAButton.swift:47
                            // resolvedCornerRadius, PaywallPreview.tsx:1363), else a non-default
                            // slider value rendered on iOS/preview but always 12 on Android.
                            shape = RoundedCornerShape((
                                section.data?.cta?.corner_radius?.toFloat()
                                    ?: (ctaStyleMap?.get("corner_radius") as? Number)?.toFloat()
                                    ?: (sectionCtaStyleMap?.get("corner_radius") as? Number)?.toFloat()
                                    ?: config.cta?.corner_radius?.toFloat()
                                    ?: 12f
                            ).dp),
                        )
                        .alpha(if (ctaEnabled) 1f else 0.5f)
                        .clickable(
                            enabled = ctaEnabled,
                            interactionSource = ctaInteraction,
                            indication = null, // QA-R9 — no purple ripple
                        ) { onCTATap() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isPurchasing) {
                        // Round-30 — mirror iOS CTAButton.swift:58-69 which shows a spinner
                        // AND "Processing..." text; Android previously showed a bare spinner,
                        // so an in-flight purchase read "Processing..." on iOS but was text-less
                        // on Android. QA-R9 — spinner color follows the authored CTA text color
                        // so a white-CTA + black-text config produces a BLACK spinner (not
                        // white-on-white), and an authored primary-coloured CTA shows white.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = buttonTextColor,
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = "Processing...",
                                style = buttonTextStyle.copy(color = buttonTextColor),
                            )
                        }
                    } else {
                        Text(
                            // SPEC-070-A finalization PW-3 / Round-30 — iOS resolution chain.
                            // The CTA-SECTION path (this one, the common case) routes through
                            // iOS CTAButton.swift:63/66 whose default is "Subscribe" — NOT
                            // "Continue" (that's only iOS's no-cta-section top-level fallback,
                            // PaywallRenderer.swift:194). The old "Continue" default here made
                            // an unauthored paywall read "Continue" on Android vs "Subscribe" on
                            // iOS. Inlined (not via helper) because PaywallSectionView is a
                            // separate @Composable scope from PaywallScreen.
                            text = loc(
                                "cta.text",
                                // Round-MZ — selected plan's per-plan cta_text overrides the
                                // section/top-level CTA label (mirrors iOS selectedPlanCtaText).
                                // Resolve the plan list like effectivePlans()/iOS selectedPlan do —
                                // the "plans"-type section first, then top-level config.plans — NOT
                                // this CTA section's own (normally absent) plans, which made the
                                // pinned CTA fall through to "Subscribe" when plans lived only in a
                                // plans section.
                                (config.sections.firstOrNull { it.type == "plans" }?.data?.plans ?: config.plans)
                                    ?.firstOrNull { it.id == selectedPlanId }?.cta_text?.takeIf { it.isNotBlank() }
                                    ?: config.cta?.text?.takeIf { it.isNotBlank() }
                                    ?: section.data?.cta?.text?.takeIf { it.isNotBlank() }
                                    ?: section.data?.cta_text?.takeIf { it.isNotBlank() }
                                    ?: section.data?.text?.takeIf { it.isNotBlank() }
                                    ?: "Subscribe",
                            ),
                            // QA-R8 — apply resolved CTA text color
                            // (was hardcoded Color.White). An authored white-bg
                            // CTA with black text now actually renders the
                            // authored black instead of white-on-white.
                            style = buttonTextStyle.copy(color = buttonTextColor),
                        )
                    }
                }
                if (showRestoreLink && restorePosition != "above") {
                    Spacer(Modifier.height(restoreGap))
                    RestoreLink()
                }
            }
        }
        "social_proof" -> {
            // SPEC-084: Social proof sub-types
            val subType = section.data?.sub_type ?: "app_rating"
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } }
            ) {
                when (subType) {
                    "countdown" -> {
                        CountdownTimer(
                            seconds = section.data?.countdown_seconds ?: 86400,
                            valueTextStyle = section.style?.elements?.get("value")?.text_style,
                        )
                    }
                    "trial_badge" -> {
                        val trialBadgeStyle = StyleEngine.applyTextStyle(
                            TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ai.appdna.sdk.AppDNA.brandAccentColor()),
                            section.style?.elements?.get("value")?.text_style
                        )
                        Text(
                            text = loc("social_proof.trial_badge", section.data?.text ?: "Free Trial"),
                            style = trialBadgeStyle,
                            modifier = Modifier
                                .background(
                                    ai.appdna.sdk.AppDNA.brandAccentColor().copy(alpha = 0.15f),
                                    RoundedCornerShape(999.dp),
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    else -> { // app_rating
                        val socialValueStyle = StyleEngine.applyTextStyle(
                            TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp),
                            section.style?.elements?.get("value")?.text_style
                        )
                        section.data?.rating?.let { rating ->
                            // SPEC-401-A R84 (Lens A F3) \u2014 render 5 fixed stars
                            // with filled/half/outline based on rating instead
                            // of `repeat(rating.toInt())` which dropped the
                            // half-star (4.5\u2605 rendered as 4 solid stars).
                            // Mirrors iOS PaywallRenderer.swift socialProofSection
                            // app_rating star loop.
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                for (i in 0 until 5) {
                                    val delta = rating - i
                                    val (vector, alpha) = when {
                                        delta >= 1.0 -> Icons.Filled.Star to 1f
                                        delta >= 0.5 -> Icons.AutoMirrored.Filled.StarHalf to 1f
                                        else -> Icons.Filled.StarBorder to 0.6f
                                    }
                                    Icon(
                                        imageVector = vector,
                                        contentDescription = null,
                                        tint = Color(0xFFFBBF24).copy(alpha = alpha),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            section.data.review_count?.let { count ->
                                // SPEC-401-A R84 (Lens A F3) \u2014 compact count
                                // formatter (e.g. 1245 \u2192 "1.2K") matching iOS
                                // formatCount; was the literal string
                                // "$rating from $count reviews".
                                val compact = formatCompactCount(count)
                                Text(
                                    text = loc("social_proof.review_text", "($compact)"),
                                    style = socialValueStyle,
                                )
                            }
                        }
                        section.data?.testimonial?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "\"${loc("social_proof.testimonial", it)}\"",
                                style = socialValueStyle,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
        "guarantee" -> {
            // SPEC-401-A R84 (Lens A F1) — full 4-element VStack matching
            // iOS PaywallRenderer.swift:594-661 (icon + capsule badge + title +
            // description). Was bare 12sp text dropping icon/title/description/
            // accent color/badge styling.
            val accentColor = StyleEngine.parseColor(section.data?.accent_color ?: "#22C55E")
            val hasContent = section.data?.guarantee_text != null
                || section.data?.title != null
                || section.data?.text != null
                || section.data?.description != null
            if (hasContent) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Icon (SF Symbol → Material via IconView; fallback shield)
                    val iconName = section.data?.icon
                    IconView(
                        ref = IconReference(
                            library = "sf-symbols",
                            name = iconName?.takeIf { it.contains(".") || it.contains("_") }
                                ?: "shield.checkmark.fill",
                            color = section.data?.accent_color ?: "#22C55E",
                            size = 28f,
                        ),
                        defaultSize = 28f,
                    )
                    // Badge text (capsule)
                    val badge = section.data?.guarantee_text ?: section.data?.text
                    if (badge != null) {
                        val badgeStyle = StyleEngine.applyTextStyle(
                            TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = accentColor),
                            section.style?.elements?.get("badge")?.text_style
                        )
                        Text(
                            text = loc("guarantee.badge", badge),
                            style = badgeStyle,
                            modifier = Modifier
                                .background(accentColor.copy(alpha = 0.15f), RoundedCornerShape(999.dp))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    // Title
                    section.data?.title?.let { title ->
                        val titleStyle = StyleEngine.applyTextStyle(
                            TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface),
                            section.style?.elements?.get("title")?.text_style
                        )
                        Text(text = loc("guarantee.title", title), style = titleStyle)
                    }
                    // Description (mirrors iOS fallback chain)
                    val desc = section.data?.description
                        ?: section.data?.title?.let { _ ->
                            section.data.text ?: section.data.guarantee_text
                        }
                    desc?.let {
                        val descStyle = StyleEngine.applyTextStyle(
                            TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), textAlign = TextAlign.Center),
                            section.style?.elements?.get("text")?.text_style
                                ?: section.style?.elements?.get("description")?.text_style
                        )
                        Text(text = loc("guarantee.description", it), style = descStyle)
                    }
                }
            }
        }
        // SPEC-084: Missing sections
        "image" -> {
            ai.appdna.sdk.core.NetworkImage(
                url = section.data?.image_url,
                modifier = Modifier
                    .fillMaxWidth()
                    .height((section.data?.height ?: 240f).dp)
                    .clip(RoundedCornerShape((section.data?.corner_radius ?: 12f).dp))
                    .run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
        "spacer" -> {
            Spacer(modifier = Modifier.height((section.data?.spacer_height ?: 24f).dp).run { with(StyleEngine) { applyContainerStyle(section.style?.container) } })
        }
        // SPEC-085: Lottie section
        "lottie" -> {
            section.data?.lottie_url?.let { url ->
                LottieBlockView(
                    block = LottieBlock(
                        lottie_url = url,
                        loop = section.data.lottie_loop ?: true,
                        speed = section.data.lottie_speed ?: 1.0f,
                        // SPEC-070-A finalization B5 P3 — prefer the
                        // type-specific lottie_height over the generic
                        // height (mirrors iOS PaywallConfig.swift:133).
                        height = section.data.lottie_height ?: section.data.height ?: 200f,
                    )
                )
            }
        }
        // SPEC-085: Rive section
        "rive" -> {
            section.data?.rive_url?.let { url ->
                RiveBlockView(
                    block = RiveBlock(
                        rive_url = url,
                        state_machine = section.data.rive_state_machine,
                        height = section.data.height ?: 200f,
                    )
                )
            }
        }
        // SPEC-085 + SPEC-070-A audit attempt 9 F1: also handle
        // `video_background` so console-published full-bleed background-video
        // sections render. iOS PaywallRenderer.swift:549 dispatches both
        // strings to the same renderer.
        "video", "video_background" -> {
            section.data?.video_url?.let { url ->
                CoreVideoBlockView(
                    block = CoreVideoBlock(
                        video_url = url,
                        video_thumbnail_url = section.data.video_thumbnail_url ?: section.data.image_url,
                        // SPEC-070-A finalization B5 P3 — prefer the
                        // type-specific video_height over the generic
                        // height (mirrors iOS PaywallConfig.swift:136).
                        video_height = section.data.video_height ?: section.data.height ?: 200f,
                        video_corner_radius = section.data.corner_radius,
                        autoplay = section.data.video_autoplay,
                        loop = section.data.video_loop,
                    )
                )
            }
        }
        "testimonial" -> {
            // SPEC-401-A R84 (Lens A F2) \u2014 honor `data.layout` (quote / card /
            // minimal / default) matching iOS PaywallRenderer.swift:715-799.
            // Was hardcoded "quote" layout regardless of console value.
            val testimonialLayout = section.data?.layout ?: "quote"
            val quoteStyle = StyleEngine.applyTextStyle(
                TextStyle(
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = if (testimonialLayout == "minimal") 12.sp else 14.sp,
                    textAlign = TextAlign.Center,
                ),
                section.style?.elements?.get("quote")?.text_style
            )
            val authorNameStyle = StyleEngine.applyTextStyle(
                TextStyle(fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp),
                section.style?.elements?.get("author_name")?.text_style
            )
            val authorRoleStyle = StyleEngine.applyTextStyle(
                TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp),
                section.style?.elements?.get("author_role")?.text_style
            )
            val cardModifier = if (testimonialLayout == "card") {
                Modifier
                    .background(Color(0xFFF9FAFB), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(12.dp))
                    .padding(16.dp)
            } else Modifier
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .then(cardModifier)
                    .run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
            ) {
                // Quote-mark only on "quote" layout
                if (testimonialLayout == "quote") {
                    Text(
                        text = "\u201C",
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = ai.appdna.sdk.AppDNA.brandAccentColor(),
                    )
                }
                if (testimonialLayout == "minimal") {
                    // Inline "\u2014 Author Name" caption
                    val author = section.data?.author_name?.let { " \u2014 $it" } ?: ""
                    Text(
                        text = loc("testimonial.quote", section.data?.quote ?: section.data?.testimonial ?: "") + author,
                        style = quoteStyle,
                    )
                } else {
                    Text(
                        text = loc("testimonial.quote", section.data?.quote ?: section.data?.testimonial ?: ""),
                        style = quoteStyle,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Author avatar (URL) or initials fallback
                        val avatarUrl = section.data?.avatar_url
                        if (!avatarUrl.isNullOrBlank()) {
                            ai.appdna.sdk.core.NetworkImage(
                                url = avatarUrl,
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                        } else {
                            section.data?.author_name?.let { name ->
                                val initials = name.split(" ").mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }.take(2).joinToString("")
                                Box(
                                    modifier = Modifier.size(40.dp).clip(CircleShape).background(ai.appdna.sdk.AppDNA.brandAccentColor().copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(initials, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = ai.appdna.sdk.AppDNA.brandAccentColor())
                                }
                            }
                        }
                        Column {
                            section.data?.author_name?.let {
                                Text(loc("testimonial.author_name", it), style = authorNameStyle)
                            }
                            section.data?.author_role?.let {
                                Text(loc("testimonial.author_role", it), style = authorRoleStyle)
                            }
                        }
                    }
                }
            }
        }
        // SPEC-089d: 12 new paywall section types
        "countdown" -> {
            PaywallCountdownSection(section = section, loc = loc)
        }
        "legal" -> {
            PaywallLegalSection(section = section, loc = loc, onRestore = onRestore)
        }
        "divider" -> {
            PaywallDividerSection(section = section)
        }
        "sticky_footer" -> {
            // Rendered outside scrollable content — see PaywallScreen
        }
        "card" -> {
            PaywallCardSection(section = section, loc = loc)
        }
        "carousel" -> {
            PaywallCarouselSection(section = section, config = config, loc = loc)
        }
        "timeline" -> {
            PaywallTimelineSection(section = section, loc = loc)
        }
        "icon_grid" -> {
            PaywallIconGridSection(section = section, loc = loc)
        }
        "comparison_table" -> {
            PaywallComparisonTableSection(section = section, loc = loc)
        }
        "promo_input" -> {
            PaywallPromoInputSection(
                section = section,
                loc = loc,
                onPromoCodeSubmit = onPromoCodeSubmit,
                // SPEC-070-A C.7 — hoisted state plumbing
                promoCode = promoCode,
                promoState = promoState,
                onPromoCodeChange = onPromoCodeChange,
                onPromoStateChange = onPromoStateChange,
            )
        }
        "toggle" -> {
            PaywallToggleSection(section = section, loc = loc, toggleStates = toggleStates)
        }
        "reviews_carousel" -> {
            PaywallReviewsCarouselSection(section = section, loc = loc)
        }
    }
}

// MARK: - SPEC-089d: Countdown section (AC-028)

@Composable
private fun PaywallCountdownSection(
    section: PaywallSection,
    loc: (String, String) -> String,
) {
    val duration = section.data?.duration_seconds ?: section.data?.countdown_seconds ?: 3600
    // SPEC-401-A R76 (Lens C P1) — render label + layout (boxed/banner/
    // inline) + background_color matching iOS PaywallRenderer.swift
    // :875-901 `countdownSectionView`. DTOs already carry `label`,
    // `label_text`, `background_color` (PaywallConfig.kt:168/182/237) and
    // decoder maps them — pure renderer gap before this fix. Console-
    // authored "Offer ends in 23:59:42" boxed banner with red-tinted
    // backdrop now renders identically across platforms.
    val labelText = section.data?.label_text ?: section.data?.label
    val layout = section.data?.layout ?: ""
    val bgHex = section.data?.background_color ?: when (layout) {
        "boxed", "banner" -> "#FEF2F2"
        else -> null
    }
    val bgColor = bgHex?.let { parseHexColor(it) }
    val cornerRadius = when (layout) {
        "boxed" -> 12.dp
        "banner" -> 0.dp
        else -> 0.dp
    }
    val needsContainer = layout == "boxed" || layout == "banner" || bgColor != null

    val outerModifier = Modifier.fillMaxWidth()
        .run { with(StyleEngine) { applyContainerStyle(section.style?.container) } }
        .let { if (needsContainer && bgColor != null) it.background(bgColor, RoundedCornerShape(cornerRadius)) else it }
        .let { if (needsContainer) it.padding(vertical = 12.dp, horizontal = 16.dp) else it }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = outerModifier,
    ) {
        if (!labelText.isNullOrBlank()) {
            Text(
                text = loc("countdown.label", labelText),
                color = section.data?.label_color?.let { parseHexColor(it) }
                    ?: parseHexColor("#7F1D1D"),
                fontSize = (section.data?.label_font_size ?: 14f).sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        CountdownTimer(
            seconds = duration,
            valueTextStyle = section.style?.elements?.get("value")?.text_style,
        )
    }
}

// MARK: - SPEC-089d: Legal section (AC-029)

@Composable
private fun PaywallLegalSection(
    section: PaywallSection,
    loc: (String, String) -> String,
    onRestore: () -> Unit = {},
) {
    val textColor = section.data?.color?.let { parseHexColor(it) } ?: Color.White.copy(alpha = 0.5f)
    val fontSize = section.data?.font_size ?: 11f
    val textAlignment = when (section.data?.alignment) {
        "left" -> TextAlign.Start
        "right" -> TextAlign.End
        else -> TextAlign.Center
    }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
    ) {
        section.data?.text?.let { text ->
            // Parse markdown links [text](url)
            // SPEC-485 (#649) — pass the authored link colour through (accent_color, which the
            // console's Link Color now maps onto at the sync boundary).
            val legalLinkColor = section.data?.accent_color?.let { parseHexColor(it) }
            val annotated = buildAnnotatedStringWithLinks(text, legalLinkColor)
            androidx.compose.foundation.text.ClickableText(
                text = annotated,
                style = TextStyle(color = textColor, fontSize = fontSize.sp, textAlign = textAlignment),
                onClick = { offset ->
                    annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()?.let { annotation ->
                            // SPEC-070-B W11 — a markdown link in a legal block is still a
                            // CONFIG-DRIVEN url. It goes through the allowlist like every other.
                            ai.appdna.sdk.core.URLSafety.open(context, annotation.item)
                        }
                },
            )
        }
        val links = section.data?.links
        if (!links.isNullOrEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                links.forEach { link ->
                    val accentColor = section.data.accent_color?.let { parseHexColor(it) } ?: ai.appdna.sdk.AppDNA.brandAccentColor()
                    Text(
                        text = link.label,
                        color = accentColor,
                        fontSize = fontSize.sp,
                        // SPEC-401-A R85 (Lens A F4 P0) — route restore-action
                        // links to onRestore callback matching iOS
                        // PaywallRenderer.swift:931-938. Was unconditionally
                        // launching ACTION_VIEW with link.url which for restore
                        // was empty/sentinel — tap was no-op (or crashed) and
                        // hosting users could not restore from the legal block.
                        modifier = Modifier.clickable {
                            if (link.action == "restore") {
                                onRestore()
                            } else if (link.url.isNotBlank()) {
                                // SPEC-070-B W11 — allowlisted. A paywall config that names
                                // `javascript:` or `intent:` here is refused, not opened.
                                ai.appdna.sdk.core.URLSafety.open(context, link.url)
                            }
                        },
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * SPEC-485 (#649) — inline `[label](url)` links in paywall legal text.
 *
 * Was `private`, i.e. file-private, so the OTHER legal renderer
 * (`screens/sections/ModuleSectionWrappers.PaywallLegalSection`, used when a paywall section is
 * embedded in a Screen module) could not reach it and drew a plain `Text` with the brackets
 * visible. `internal` makes it reachable across the module, so there is ONE parser rather than a
 * second copy — a duplicate is how the split happened in the first place. iOS had the mirror of
 * exactly this split and is fixed the same way.
 */
internal fun buildAnnotatedStringWithLinks(
    text: String,
    linkColor: Color? = null,
): androidx.compose.ui.text.AnnotatedString {
    val builder = androidx.compose.ui.text.AnnotatedString.Builder()
    val pattern = Regex("\\[([^]]+)]\\(([^)]+)\\)")
    var lastIndex = 0
    pattern.findAll(text).forEach { match ->
        // Add text before the match
        builder.append(text.substring(lastIndex, match.range.first))
        // Add the link text with annotation
        val label = match.groupValues[1]
        val url = match.groupValues[2]
        builder.pushStringAnnotation(tag = "URL", annotation = url)
        builder.pushStyle(
            androidx.compose.ui.text.SpanStyle(
                // SPEC-485 (#649) — the AUTHORED link colour when there is one. This was
                // unconditionally the brand accent, so the console's "Link Color" was ignored even
                // once its value finally reached the device. iOS honours it via `.tint(linkColor)`.
                color = linkColor ?: ai.appdna.sdk.AppDNA.brandAccentColor(),
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
            )
        )
        builder.append(label)
        builder.pop() // style
        builder.pop() // annotation
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        builder.append(text.substring(lastIndex))
    }
    return builder.toAnnotatedString()
}

// MARK: - SPEC-089d: Divider section (AC-030)

@Composable
private fun PaywallDividerSection(section: PaywallSection) {
    // SPEC-401-A R75 (Lens A P2) — divider default `#E5E7EB` matches iOS
    // PaywallRenderer.swift:1003 `data?.color ?? "#E5E7EB"` (light gray
    // hairline). Was `Color.White.copy(alpha = 0.2f)` — invisible on
    // light-themed paywalls. iOS uses an absolute hex default that works
    // on both light + dark; mirror that here.
    val dividerColor = section.data?.color?.let { parseHexColor(it) } ?: parseHexColor("#E5E7EB")
    val thickness = section.data?.thickness ?: 1f
    val lineStyle = section.data?.line_style ?: "solid"
    val mTop = section.data?.margin_top ?: 8f
    val mBottom = section.data?.margin_bottom ?: 8f
    val mH = section.data?.margin_horizontal ?: 0f

    Box(
        modifier = Modifier
            .padding(top = mTop.dp, bottom = mBottom.dp, start = mH.dp, end = mH.dp)
            .fillMaxWidth(),
    ) {
        val labelText = section.data?.label_text
        if (labelText != null) {
            // Divider with centered label
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                DividerLine(color = dividerColor, thickness = thickness, style = lineStyle, modifier = Modifier.weight(1f))
                Text(
                    text = labelText,
                    // SPEC-401-A R75 (Lens A P2) — divider label default
                    // `#9CA3AF` matches iOS PaywallRenderer.swift:1017
                    // `data?.labelColor ?? "#9CA3AF"`. Was 50%-white —
                    // invisible on light paywalls.
                    color = section.data.label_color?.let { parseHexColor(it) } ?: parseHexColor("#9CA3AF"),
                    fontSize = (section.data.label_font_size ?: 12f).sp,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .background(
                            section.data.label_bg_color?.let { parseHexColor(it) } ?: Color.Transparent,
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 4.dp),
                )
                DividerLine(color = dividerColor, thickness = thickness, style = lineStyle, modifier = Modifier.weight(1f))
            }
        } else {
            DividerLine(color = dividerColor, thickness = thickness, style = lineStyle, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DividerLine(color: Color, thickness: Float, style: String, modifier: Modifier = Modifier) {
    when (style) {
        "dashed", "dotted" -> {
            val dashLength = if (style == "dashed") 6f else 2f
            val gapLength = if (style == "dashed") 3f else 2f
            androidx.compose.foundation.Canvas(
                modifier = modifier.height(thickness.dp),
            ) {
                drawLine(
                    color = color,
                    start = androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2),
                    strokeWidth = thickness,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        floatArrayOf(dashLength * density, gapLength * density),
                        0f,
                    ),
                )
            }
        }
        else -> { // solid
            Divider(
                thickness = thickness.dp,
                color = color,
                modifier = modifier,
            )
        }
    }
}

// MARK: - SPEC-089d: Card section (AC-032)

@Composable
private fun PaywallCardSection(
    section: PaywallSection,
    loc: (String, String) -> String,
) {
    val radius = section.data?.corner_radius ?: 16f
    val bgColor = section.data?.background_color?.let { parseHexColor(it) } ?: Color.White.copy(alpha = 0.08f)
    val borderClr = section.data?.border_color?.let { parseHexColor(it) } ?: Color.White.copy(alpha = 0.15f)

    Card(
        shape = RoundedCornerShape(radius.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderClr, RoundedCornerShape(radius.dp))
            .run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
    ) {
        Column(modifier = Modifier.padding((section.data?.padding ?: 16f).dp)) {
            section.data?.title?.let {
                Text(
                    text = loc("card.title", it),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = Color.White,
                )
                Spacer(Modifier.height(4.dp))
            }
            section.data?.subtitle?.let {
                Text(
                    text = loc("card.subtitle", it),
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(8.dp))
            }
            section.data?.text?.let {
                Text(
                    text = loc("card.body", it),
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
        }
    }
}

// MARK: - SPEC-089d: Carousel section (AC-033)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaywallCarouselSection(
    section: PaywallSection,
    config: PaywallConfig,
    loc: (String, String) -> String,
) {
    val pages = section.data?.pages ?: return
    if (pages.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val coroutineScope = rememberCoroutineScope()
    val autoScroll = section.data.auto_scroll ?: false
    val intervalMs = section.data.auto_scroll_interval_ms ?: 3000
    val showIndicators = section.data.show_indicators ?: true

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().height((section.data.height ?: 200f).dp),
        ) { pageIdx ->
            val page = pages[pageIdx]
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            ) {
                page.children?.forEach { child ->
                    child.data?.title?.let {
                        Text(text = it, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                    child.data?.subtitle?.let {
                        Text(text = it, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                    child.data?.image_url?.let { url ->
                        ai.appdna.sdk.core.NetworkImage(
                            url = url,
                            modifier = Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    }
                }
            }
        }

        if (showIndicators) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(pages.size) { idx ->
                    val activeColor = section.data.indicator_active_color?.let { parseHexColor(it) } ?: Color.White
                    val inactiveColor = section.data.indicator_color?.let { parseHexColor(it) } ?: Color.White.copy(alpha = 0.3f)
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (idx == pagerState.currentPage) activeColor else inactiveColor),
                    )
                }
            }
        }
    }

    // Auto-scroll
    if (autoScroll) {
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(intervalMs.toLong())
                val nextPage = (pagerState.currentPage + 1) % pages.size
                pagerState.animateScrollToPage(nextPage)
            }
        }
    }
}

// MARK: - SPEC-089d: Timeline section (AC-034)

@Composable
private fun PaywallTimelineSection(
    section: PaywallSection,
    loc: (String, String) -> String,
) {
    val items = section.data?.items ?: return
    val isCompact = section.data.compact ?: false
    val showLine = section.data.show_line ?: true

    // QA-R12 \u2014 full restructure to match iOS vertical timeline
    // (`PaywallRenderer.swift:1267-1340`). Was rendering each item as a
    // self-contained Row with a 24dp circle + 24dp-tall 2dp line stacked
    // below \u2014 visually a chain of large bubbles. iOS uses:
    //   \u2022 8dp accent dots (not 24dp bubbles)
    //   \u2022 1dp-wide hairline connector at `itemSpacing` height (20dp normal,
    //     12dp compact) drawn ONLY between adjacent dots
    //   \u2022 dot color * 0.6 opacity, connector color * 0.25 opacity
    //   \u2022 two sibling columns: left = continuous chain of dots+lines,
    //     right = chain of (title + subtitle) labels with no inter-row gap
    //   \u2022 5dp top inset on the left chain so the first dot aligns to text
    //     baseline
    // The 30/60/90 "misalignment" reported by Mrozu is the proportion
    // mismatch \u2014 Android's 24dp circles dominated the layout, while iOS's
    // tiny accent dots act as a subtle visual rail.
    val connectorColor = section.data.line_color?.let { parseHexColor(it) }
        ?: section.style?.elements?.get("connector")?.text_style?.color?.let { parseHexColor(it) }
        ?: Color.White
    val titleFontSize = (section.data.font_size ?: 14f).sp
    val itemSpacing = if (isCompact) 12.dp else 20.dp

    // SPEC-419 — iOS centers the timeline block (PaywallRenderer.swift:1267-1340).
    // Wrap in a full-width Box and let the Row be content-sized + centered, instead
    // of a fillMaxWidth Row whose titles were left-pinned via weight(1f).
    Box(
        modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
        contentAlignment = Alignment.Center,
    ) {
        // SPEC-419 — per-item Row at IntrinsicSize.Min so the left rail (dot +
        // connector) is exactly as tall as THIS item's title+subtitle, keeping
        // each dot aligned to its own title instead of two independent chains
        // drifting (left height 8dp+itemSpacing != right title+spacer height).
        Column(modifier = Modifier.wrapContentWidth()) {
            items.forEachIndexed { index, item ->
                val baseStatusColor = when (item.status) {
                    "completed" -> parseHexColor(section.data.completed_color ?: "#22C55E")
                    "current" -> parseHexColor(section.data.current_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
                    else -> parseHexColor(section.data.upcoming_color ?: "#666666")
                }
                val statusColor = item.color?.let { parseHexColor(it) } ?: baseStatusColor
                val isLast = index == items.size - 1
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.height(IntrinsicSize.Min),
                ) {
                    // Left rail: dot pinned to the title's top, connector fills
                    // the remaining intrinsic height down toward the next dot.
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxHeight().padding(top = 3.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor.copy(alpha = 0.6f)),
                        )
                        if (showLine && !isLast) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .weight(1f)
                                    .background(connectorColor.copy(alpha = 0.25f)),
                            )
                        }
                    }
                    // Right: title + subtitle. Bottom padding = itemSpacing gives
                    // the vertical rhythm AND drives the Row's intrinsic height,
                    // which the connector then spans.
                    Column(
                        modifier = Modifier
                            .wrapContentWidth()
                            .padding(bottom = if (isLast) 0.dp else itemSpacing),
                    ) {
                        item.title?.let {
                            Text(text = it, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = titleFontSize)
                        }
                        item.subtitle?.let {
                            Text(text = it, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - SPEC-089d: Icon grid section (AC-035)

@Composable
private fun PaywallIconGridSection(
    section: PaywallSection,
    loc: (String, String) -> String,
) {
    val items = section.data?.items ?: return
    val columnCount = section.data.columns ?: 3
    val gridSpacing = section.data.spacing ?: 16f
    val iconSz = section.data.icon_size ?: 32f
    val iconClr = section.data.icon_color?.let { parseHexColor(it) } ?: ai.appdna.sdk.AppDNA.brandAccentColor()

    val titleStyle = section.style?.elements?.get("title")?.text_style
    val descStyle = section.style?.elements?.get("description")?.text_style

    // Manual grid since LazyVerticalGrid can't be nested in scrollable Column
    val chunked = items.chunked(columnCount)
    Column(
        verticalArrangement = Arrangement.spacedBy(gridSpacing.dp),
        modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
    ) {
        chunked.forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(gridSpacing.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                rowItems.forEach { item ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f),
                    ) {
                        item.icon?.let { icon ->
                            // Check if emoji (non-ASCII) or icon name
                            if (icon.any { it.code > 127 }) {
                                Text(text = icon, fontSize = iconSz.sp)
                            } else {
                                IconView(
                                    ref = IconReference(library = "material", name = icon, size = iconSz, color = null),
                                    modifier = Modifier.size(iconSz.dp),
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        val itemLabel = item.label ?: item.title
                        itemLabel?.let {
                            val ts = if (titleStyle != null) StyleEngine.applyTextStyle(
                                TextStyle(fontWeight = FontWeight.Medium, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center),
                                titleStyle,
                            ) else TextStyle(fontWeight = FontWeight.Medium, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
                            Text(text = it, style = ts, textAlign = TextAlign.Center)
                        }
                        val itemDesc = item.description ?: item.subtitle
                        itemDesc?.let {
                            val ds = if (descStyle != null) StyleEngine.applyTextStyle(
                                TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, textAlign = TextAlign.Center),
                                descStyle,
                            ) else TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, textAlign = TextAlign.Center)
                            Text(text = it, style = ds, textAlign = TextAlign.Center)
                        }
                    }
                }
                // Fill remaining slots for last row
                repeat(columnCount - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// MARK: - SPEC-089d: Comparison table section (AC-036)

@Composable
private fun PaywallComparisonTableSection(
    section: PaywallSection,
    loc: (String, String) -> String,
) {
    val cols = section.data?.table_columns ?: return
    val rows = section.data.rows ?: return
    val checkColor = section.data.check_color?.let { parseHexColor(it) } ?: Color(0xFF22C55E)
    // SPEC-401-A R79 (Lens A P1) — defaults match iOS PaywallRenderer.swift:
    // 1397-1399. Cross was bright red `#EF4444`; iOS uses muted gray `#D1D5DB`.
    // Border was 20%-white (invisible on light paywall); iOS uses `#E5E7EB`.
    val crossColor = section.data.cross_color?.let { parseHexColor(it) } ?: parseHexColor("#D1D5DB")
    val highlightClr = section.data.highlight_color?.let { parseHexColor(it) } ?: ai.appdna.sdk.AppDNA.brandAccentColor()
    val borderClr = section.data.border_color?.let { parseHexColor(it) } ?: parseHexColor("#E5E7EB")
    // SPEC-401-A R79 (Lens A P1) — theme-adaptive label colors matching iOS
    // `.foregroundColor(.primary)` / `.secondary`. Was hardcoded white,
    // invisible on light-themed paywalls.
    val tablePrimaryText = MaterialTheme.colorScheme.onSurface
    val tableSecondaryText = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    // SPEC-401-A R79 (Lens A P2) — header background `#F9FAFB` matching iOS
    // PaywallRenderer.swift:1421. Was 5%-white (invisible on white paywall).
    val tableHeaderBg = parseHexColor("#F9FAFB")
    val radius = section.data.corner_radius ?: 12f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius.dp))
            .border(1.dp, borderClr, RoundedCornerShape(radius.dp))
            .run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
    ) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth().background(tableHeaderBg),
        ) {
            // Feature label column header
            Box(modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            cols.forEachIndexed { _, col ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(if (col.highlighted == true) highlightClr.copy(alpha = 0.15f) else Color.Transparent)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = col.label,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = tablePrimaryText,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Divider(color = borderClr, thickness = 0.5.dp)

        // Data rows
        rows.forEachIndexed { rowIdx, row ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = row.feature,
                    fontSize = 12.sp,
                    color = tableSecondaryText,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp),
                )
                row.values.forEachIndexed { valIdx, value ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (valIdx < cols.size && cols[valIdx].highlighted == true) highlightClr.copy(alpha = 0.08f)
                                else Color.Transparent,
                            )
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        // SPEC-070-A finalization B5 P2 \u2014 accept the same
                        // value aliases iOS does (PaywallRenderer.swift:1437-1445):
                        //   check / true / yes / y / \u2713
                        //   cross / false / no / n / - / \u2717
                        //   partial / ~
                        when (value.lowercase().trim()) {
                            "check", "true", "yes", "y", "\u2713" ->
                                Text("\u2713", color = checkColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            "cross", "false", "no", "n", "-", "\u2715" ->
                                Text("\u2715", color = crossColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            "partial", "~" ->
                                Text("\u2014", color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            else -> Text(value, fontSize = 12.sp, color = tablePrimaryText, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            if (rowIdx < rows.size - 1) {
                Divider(color = borderClr.copy(alpha = 0.3f), thickness = 0.5.dp)
            }
        }
    }
}

// MARK: - SPEC-089d: Promo input section (AC-037)

// `internal` (not private) so PromoCodeFallbackTest can drive the no-delegate
// branch directly. That branch used to accept any non-blank code.
@Composable
internal fun PaywallPromoInputSection(
    section: PaywallSection,
    loc: (String, String) -> String,
    onPromoCodeSubmit: ((String, (Boolean) -> Unit) -> Unit)? = null,
    // SPEC-070-A C.7 — state hoisted to parent so CTA tap handlers can read
    // the validated code. When omitted (e.g. legacy callers), falls back to
    // local state.
    promoCode: String? = null,
    promoState: String? = null,
    onPromoCodeChange: ((String) -> Unit)? = null,
    onPromoStateChange: ((String) -> Unit)? = null,
) {
    // Local fallback when parent did not hoist
    var localPromoCode by remember { mutableStateOf("") }
    var localPromoState by remember { mutableStateOf("idle") }
    val effectivePromoCode = promoCode ?: localPromoCode
    val effectivePromoState = promoState ?: localPromoState
    val updateCode: (String) -> Unit = { newCode ->
        if (onPromoCodeChange != null) onPromoCodeChange(newCode) else localPromoCode = newCode
    }
    val updateState: (String) -> Unit = { newState ->
        if (onPromoStateChange != null) onPromoStateChange(newState) else localPromoState = newState
    }

    Column(
        modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // SPEC-401-A R86 (Lens A F6) — filled TextField with #F9FAFB bg +
            // theme-adaptive text matches iOS PaywallRenderer.swift:1481-1488.
            // Was OutlinedTextField with white-on-dark-only palette → invisible
            // placeholder on light-mode paywalls.
            TextField(
                value = effectivePromoCode,
                onValueChange = updateCode,
                placeholder = { Text(section.data?.placeholder ?: "Promo code", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = parseHexColor("#F9FAFB"),
                    unfocusedContainerColor = parseHexColor("#F9FAFB"),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
            Button(
                onClick = {
                    updateState("loading")
                    // AC-037: Submit promo code via delegate callback
                    if (onPromoCodeSubmit != null) {
                        onPromoCodeSubmit(effectivePromoCode) { isValid ->
                            updateState(if (isValid) "success" else "error")
                        }
                    } else {
                        // No delegate configured — we cannot validate the code, so we
                        // must NOT report success. The old "any non-empty string is
                        // valid" fallback showed "Code applied!" for anything the user
                        // typed, and then folded that unvalidated string into purchase
                        // metadata as `promo_code` (see onCTATap). Rejecting matches
                        // every delegate default on every platform.
                        Log.warning(
                            "Promo code submitted but no AppDNAPaywallDelegate is registered — " +
                                "rejecting. Implement onPromoCodeSubmit to validate codes."
                        )
                        updateState("error")
                    }
                },
                enabled = effectivePromoState != "loading",
                colors = ButtonDefaults.buttonColors(
                    containerColor = section.data?.accent_color?.let { parseHexColor(it) } ?: ai.appdna.sdk.AppDNA.brandAccentColor(),
                ),
            ) {
                Text(
                    text = loc("promo.button", section.data?.button_text ?: "Apply"),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
            }
        }
        when (effectivePromoState) {
            "success" -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = section.data?.success_text ?: "Code applied!",
                    color = Color(0xFF22C55E),
                    fontSize = 12.sp,
                )
            }
            "error" -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = section.data?.error_text ?: "Invalid code",
                    color = Color(0xFFEF4444),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

// MARK: - SPEC-089d: Toggle section (AC-038)

@Composable
private fun PaywallToggleSection(
    section: PaywallSection,
    loc: (String, String) -> String,
    toggleStates: MutableMap<String, Boolean> = mutableMapOf(),
) {
    val toggleKey = section.data?.label ?: "toggle_${section.type}"
    var isToggled by remember { mutableStateOf(toggleStates[toggleKey] ?: section.data?.default_value ?: false) }
    val onColor = section.data?.on_color?.let { parseHexColor(it) } ?: ai.appdna.sdk.AppDNA.brandAccentColor()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
    ) {
        section.data?.icon?.let { iconName ->
            IconView(
                ref = IconReference(library = "material", name = iconName, size = 24f, color = section.data?.accent_color),
                modifier = Modifier.size(24.dp).padding(end = 8.dp),
            )
            Spacer(Modifier.width(8.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            section.data?.label?.let {
                Text(
                    text = loc("toggle.label", it),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = section.data.label_color_val?.let { c -> parseHexColor(c) } ?: Color.White,
                )
            }
            section.data?.description?.let {
                Text(
                    text = loc("toggle.description", it),
                    fontSize = 12.sp,
                    // SPEC-401-A R86 (Lens A F7) — default #9CA3AF matches iOS
                    // PaywallRenderer.swift:1545. Was Color.White 0.6 alpha →
                    // invisible on #FFFFFF paywall background.
                    color = section.data.description_color?.let { c -> parseHexColor(c) } ?: parseHexColor("#9CA3AF"),
                )
            }
        }

        Switch(
            checked = isToggled,
            onCheckedChange = { isToggled = it; toggleStates[toggleKey] = it },
            colors = SwitchDefaults.colors(checkedTrackColor = onColor),
        )
    }
}

// MARK: - SPEC-089d: Reviews carousel section (AC-039)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaywallReviewsCarouselSection(
    section: PaywallSection,
    loc: (String, String) -> String,
) {
    val reviews = section.data?.reviews ?: return
    if (reviews.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { reviews.size })
    val autoScroll = section.data.auto_scroll ?: true
    val intervalMs = section.data.auto_scroll_interval_ms ?: 4000
    val showStars = section.data.show_rating_stars ?: true
    val starClr = section.data.star_color?.let { parseHexColor(it) } ?: Color(0xFFFBBF24)
    val textStyleConfig = section.style?.elements?.get("text")?.text_style
    val authorStyleConfig = section.style?.elements?.get("author")?.text_style

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().height(180.dp),
        ) { pageIdx ->
            val review = reviews[pageIdx]
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize().padding(16.dp),
            ) {
                // Star rating
                if (showStars && review.rating != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(5) { star ->
                            Text(
                                text = if (star < review.rating.toInt()) "\u2605" else "\u2606",
                                color = starClr,
                                fontSize = 16.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Quote text
                val quoteTextStyle = if (textStyleConfig != null) {
                    StyleEngine.applyTextStyle(
                        TextStyle(color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, textAlign = TextAlign.Center),
                        textStyleConfig,
                    )
                } else {
                    TextStyle(color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, textAlign = TextAlign.Center)
                }
                Text(
                    text = "\u201C${review.text}\u201D",
                    style = quoteTextStyle,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))

                // Author
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // SPEC-070-A finalization B5 P2 — avatar resolution
                    // mirrors iOS PaywallHelperViews.swift:316-343,401-414:
                    //   1. avatar_url → render NetworkImage
                    //   2. avatar_emoji → render unicode glyph (raw or
                    //      mapped from a name like "woman"/"rocket"/"fire")
                    //   3. neither → render initial-letter circle fallback
                    val avatarUrl = review.avatar_url
                    val avatarEmojiRaw = review.avatar_emoji
                    val avatarEmoji = avatarEmojiRaw?.let { ReviewAvatarEmojiMap.resolve(it) }
                    when {
                        avatarUrl != null -> {
                            ai.appdna.sdk.core.NetworkImage(
                                url = avatarUrl,
                                modifier = Modifier.size(28.dp).clip(CircleShape),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                        }
                        avatarEmoji != null -> {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center,
                            ) { Text(text = avatarEmoji, fontSize = 16.sp) }
                        }
                        review.author.isNotBlank() -> {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = review.author.first().uppercaseChar().toString(),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                    val authorStyle = if (authorStyleConfig != null) {
                        StyleEngine.applyTextStyle(
                            TextStyle(fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp),
                            authorStyleConfig,
                        )
                    } else {
                        TextStyle(fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                    Text(text = review.author, style = authorStyle)
                    review.date?.let {
                        Text(text = it, fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f))
                    }
                }
            }
        }

        // Page indicators
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(reviews.size) { idx ->
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (idx == pagerState.currentPage) Color.White else Color.White.copy(alpha = 0.3f)),
                )
            }
        }
    }

    // Auto-scroll
    if (autoScroll) {
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(intervalMs.toLong())
                val nextPage = (pagerState.currentPage + 1) % reviews.size
                pagerState.animateScrollToPage(nextPage)
            }
        }
    }
}

// SPEC-084: Countdown timer for social proof
@Composable
private fun CountdownTimer(seconds: Int, valueTextStyle: ai.appdna.sdk.core.TextStyleConfig? = null) {
    var remaining by remember { mutableIntStateOf(seconds) }

    LaunchedEffect(Unit) {
        while (remaining > 0) {
            kotlinx.coroutines.delay(1000)
            remaining--
        }
    }

    val hours = remaining / 3600
    val minutes = (remaining % 3600) / 60
    val secs = remaining % 60

    val digitStyle = StyleEngine.applyTextStyle(
        TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White),
        valueTextStyle
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(hours to "h", minutes to "m", secs to "s").forEachIndexed { index, (value, label) ->
            if (index > 0) {
                Text(":", color = Color.White.copy(alpha = 0.6f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    // SPEC-070-A I.10 — `Locale.US` keeps the countdown digits
                    // ASCII (`05` not `۰۵`) so the paywall countdown reads the
                    // same in every locale. Mirrors iOS NumberFormatter.
                    text = String.format(java.util.Locale.US, "%02d", value),
                    style = digitStyle,
                )
                Text(text = label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}

// MARK: - SPEC-089d: Sticky footer section (AC-031)

@Composable
private fun PaywallStickyFooter(
    section: PaywallSection,
    isPurchasing: Boolean,
    onCTATap: () -> Unit,
    onRestore: () -> Unit,
    loc: (String, String) -> String,
    // Round-MZ — selected plan's per-plan cta_text (overrides section cta_text when non-empty).
    selectedPlanCtaText: String? = null,
) {
    // SPEC-401-A R86 (Lens A F1) — default background #FFFFFF matches iOS
    // PaywallRenderer.swift:1054. Was Color.Black 0.95 alpha → light-mode
    // paywalls rendered dark footer.
    val bgColor = section.data?.background_color?.let { parseHexColor(it) } ?: Color.White
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = (section.data?.padding ?: 20f).dp, vertical = 16.dp)
            .run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
    ) {
        // CTA button — selected plan's per-plan cta_text overrides the footer's configured label.
        (selectedPlanCtaText ?: section.data?.cta_text)?.let { ctaText ->
            Button(
                onClick = onCTATap,
                enabled = !isPurchasing,
                // SPEC-401-A R86 (Lens A F2) — honor cta_height + cta_font_size
                // matching iOS PaywallRenderer.swift:1067,1072. Was hardcoded.
                modifier = Modifier.fillMaxWidth().height((section.data?.cta_height ?: 52f).dp),
                shape = RoundedCornerShape((section.data?.cta_corner_radius ?: 14f).dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = section.data?.cta_bg_color?.let { parseHexColor(it) } ?: ai.appdna.sdk.AppDNA.brandAccentColor(),
                ),
            ) {
                if (isPurchasing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text(
                        text = loc("sticky_footer.cta", ctaText),
                        fontWeight = resolveCtaWeight(section.data?.cta_font_weight),
                        fontSize = (section.data?.cta_font_size ?: 17f).sp,
                        color = section.data?.cta_text_color?.let { parseHexColor(it) } ?: Color.White,
                    )
                }
            }
        }

        // Secondary action
        section.data?.secondary_text?.let { secondaryText ->
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                when (section.data.secondary_action) {
                    "restore" -> onRestore()
                    "link" -> {
                        // SPEC-070-B W11 — allowlisted, like every other config-driven URL.
                        ai.appdna.sdk.core.URLSafety.open(context, section.data.secondary_url)
                    }
                }
            }) {
                Text(
                    text = loc("sticky_footer.secondary", secondaryText),
                    // SPEC-401-A R86 (Lens A F4) — theme-adaptive secondary
                    // matches iOS `.foregroundColor(.secondary)` at
                    // PaywallRenderer.swift:1095. Was White 0.6 alpha → broken
                    // on light footers.
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
        }

        // Legal text
        section.data?.legal_text?.let { legalText ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = loc("sticky_footer.legal", legalText),
                // SPEC-401-A R86 (Lens A F5) — theme-adaptive legal matches iOS
                // `.foregroundColor(.secondary)` at PaywallRenderer.swift:1103.
                // SPEC-487 (#648) — now authorable. Unset keeps this exact colour and 10sp, so a
                // published paywall is unchanged; what changes is that an author CAN size it. The
                // hardcoded 10sp is why #648 read as a Legal-section bug: the Legal SECTION honoured
                // its authored 13, this line did not honour the 16 set beside it, so the legal
                // paragraph really did render larger.
                color = section.data?.legal_text_color?.let { parseHexColor(it) }
                    ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = (section.data?.legal_font_size ?: 10f).sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun parseHexColor(hex: String): Color {
    return try {
        val cleaned = hex.removePrefix("#")
        val colorLong = cleaned.toLong(16)
        when (cleaned.length) {
            6 -> Color(0xFF000000 or colorLong)
            8 -> Color(colorLong)
            else -> Color.Black
        }
    } catch (_: Exception) {
        Color.Black
    }
}

/**
 * SPEC-070-A finalization B5 P2 — review avatar emoji resolver.
 * Mirrors iOS PaywallHelperViews.swift `emojiFromName` map. Console
 * authors can store either:
 *   - a raw emoji ("👩"), passed through unchanged
 *   - a descriptive name ("woman", "rocket", "fire"), mapped to the
 *     corresponding unicode glyph
 * Returns null when the input is empty or `null`.
 */
private object ReviewAvatarEmojiMap {
    private val byName: Map<String, String> = mapOf(
        "woman" to "👩",
        "man" to "👨",
        "person" to "🧑",
        "girl" to "👧",
        "boy" to "👦",
        "rocket" to "🚀",
        "fire" to "🔥",
        "star" to "⭐",
        "heart" to "❤️",
        "thumbs_up" to "👍",
        "muscle" to "💪",
        "smile" to "😊",
        "sparkles" to "✨",
        "tada" to "🎉",
        "trophy" to "🏆",
        "crown" to "👑",
        "diamond" to "💎",
        "gem" to "💎",
        "bolt" to "⚡",
        "lightning" to "⚡",
        "100" to "💯",
        "ok_hand" to "👌",
        "clap" to "👏",
    )

    fun resolve(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        // If the input already contains non-ASCII (likely a literal emoji),
        // pass it through.
        if (s.any { it.code > 127 }) return s
        return byName[s.lowercase()]
    }
}

// SPEC-401-A R84 (Lens A F3) — compact count formatter mirroring iOS
// PaywallRenderer.swift `formatCount` (e.g. 1245 → "1.2K", 12450 → "12K",
// 1245000 → "1.2M"). Lower than 1000 → bare number.
private fun formatCompactCount(count: Int): String {
    val abs = kotlin.math.abs(count)
    return when {
        abs >= 1_000_000 -> {
            val whole = abs / 1_000_000
            val tenths = (abs % 1_000_000) / 100_000
            if (tenths == 0 || whole >= 10) "${whole}M" else "${whole}.${tenths}M"
        }
        abs >= 1_000 -> {
            val whole = abs / 1_000
            val tenths = (abs % 1_000) / 100
            if (tenths == 0 || whole >= 10) "${whole}K" else "${whole}.${tenths}K"
        }
        else -> "$abs"
    }
}
