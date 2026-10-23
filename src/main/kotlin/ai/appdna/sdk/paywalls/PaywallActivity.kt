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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.launch
// Accessibility string resources (Close / Restore /
// Purchase). Hosts can override these via their own `strings.xml`.
import ai.appdna.sdk.R

/**
 * Activity to render paywall UI using Jetpack Compose.
 * Follows the same pattern as SurveyActivity.
 */
class PaywallActivity : ComponentActivity() {

    /*
     * #952 — a debuggable host measures its own main-thread stalls while this surface is in front.
     *
     * A customer's logcat carried `wm_failed_to_pause` for `PaywallActivity` at the moment an
     * onboarding map step ended, and nothing of ours said what was running. A release build pays
     * nothing: `MainThreadStall.start` returns immediately when the host is not debuggable.
     */
    override fun onResume() {
        super.onResume()
        ai.appdna.sdk.core.MainThreadStall.start(this, "PaywallActivity")
    }

    override fun onPause() {
        ai.appdna.sdk.core.MainThreadStall.stop()
        super.onPause()
    }


    // OB-5 audit-2 HIGH-3 — instance-level snapshot
    // of the dismiss callback so `onDestroy` can fire it as a backstop when
    // the OS / user kills the Activity outside the regular dismissal paths
    // (task swipe in recents, low-memory kill, finish() from elsewhere).
    // Without this, an onboarding-presented paywall that's force-killed
    // never routes its `on_dismiss_target`, leaving the host onboarding
    // flow stuck in mid-state on next resume.
    private var snapshotOnDismiss: ((DismissReason) -> Unit)? = null
    private var dispatchedDismiss: Boolean = false
    // 3 P1 fix — instance-stored
    // launch token so cleanup/onBackPressed/onDestroy can pull THIS
    // Activity's slots from the per-launch registry, not whatever was
    // last written to the static companion-object slots.
    private var launchToken: String? = null
    private var instanceConfig: PaywallConfig? = null
    private var instancePaywallId: String? = null
    // When this Activity became visible, to gate delayed-dismiss
    // (`dismiss.delay_seconds`) back-press the same way the on-screen X is gated.
    private var presentedAtMs: Long = 0L

    /**
     * Host-controlled opt-out for SDK auto-dismiss on
     * restore success. Hosts that want to keep the paywall visible after
     * restore (e.g. show a "Restored! Tap X when ready" overlay) flip
     * this flag inside their `onPaywallRestoreCompleted` delegate body
     * before returning. When true, [dismissAfterRestore] short-circuits.
     * Mirrors iOS PaywallDismissGuard.skipSDKAutoDismiss.
     */
    internal var skipSDKAutoDismiss: Boolean = false

    /**
     * Auto-dismiss on restore success. Called from
     * [PaywallManager.handleRestore] AFTER the delegate's
     * `onPaywallRestoreCompleted(productIds)` fires with non-empty products.
     * No-ops if the user already dismissed (`dispatchedDismiss`) or if the
     * host requested to keep the paywall up ([skipSDKAutoDismiss]).
     *
     * Also no-op when the Activity is
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
        // Emit RESTORE_SUCCESS ("restore_success") instead
        // of PURCHASED so dashboards filtering MTPU vs restore can segment
        // correctly. Matches iOS PaywallManager.swift:411 dismiss_reason.
        snapshotOnDismiss?.invoke(DismissReason.RESTORE_SUCCESS)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge so the full-bleed paywall background paints UNDER
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
        // 3 — read THIS Activity's
        // launch slot via the per-call UUID extra. Concurrent launches
        // each get their own slot; no clobber.
        //
        // 4 — PEEK (not remove) so the
        // slot survives Activity recreation (rotation, dark-mode toggle,
        // font-scale, locale, multi-window resize). Removal happens in
        // onDestroy only when `isFinishing && !isChangingConfigurations`
        // — i.e. real dismissal, not config-change recreate. Without
        // this, a rotation mid-paywall would call onCreate again, find
        // no slot, finish(), and the user's purchase context vanishes.
        val token = intent.getStringExtra(EXTRA_LAUNCH_TOKEN)
        val slots = token?.let { activeLaunches[it] } ?: run {
            // Process-death recovery.
            // If we're being recreated by the OS (`savedInstanceState != null`)
            // but the static `activeLaunches` map is empty, the SDK process was
            // killed since the original launch and our in-memory callbacks are
            // gone. Fire the global PaywallDelegate so analytics + onPaywallDismissed
            // fires (with `reason=process_death`) instead of finishing silently.
            if (savedInstanceState != null) {
                try {
                    // Emit canonical
                    // `paywall_close` event (matches iOS PaywallManager.swift:104
                    // + Android PaywallManager.kt:136) instead of the
                    // dashboard-divergent `paywall_dismissed` name.
                    // Use `dismiss_reason` key
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
        // Register this Activity so PaywallManager can
        // call dismissAfterRestore() on it after a successful restore.
        activePaywallInstances[paywallId] = java.lang.ref.WeakReference(this)

        val onAppear = slots.onAppear
        val onDismiss = slots.onDismiss
        val onPlanSelected = slots.onPlanSelected
        val onPromoCodeSubmit = slots.onPromoCodeSubmit
        // Restore lifecycle hook (delegate-fired by PaywallManager)
        val onRestoreCb = slots.onRestore
        val onActionCb = slots.onAction

        // OB-5 audit-2 HIGH-3 — capture onDismiss
        // on the instance so onDestroy can fire it when the regular paths
        // (Compose onDismiss, onBackPressed) didn't run.
        snapshotOnDismiss = onDismiss

        // Notify appearance
        onAppear?.invoke()

        setContent {
            // Read system dark-mode pref so the renderer can
            // pick `dark` overrides from paywall content blocks. Mirrors the
            // SurveyActivity pattern landed by Phase D for surveys.
            val isDark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (isDark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()) {
                // #1076 — PROVIDED, not passed: one more parameter on PaywallScreen made ART
                // reject the class outright (see LocalPaywallAction).
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalPaywallAction provides { action -> onActionCb?.invoke(action) },
                ) {
                PaywallScreen(
                    config = config,
                    onPlanSelected = { plan, metadata ->
                        onPlanSelected?.invoke(plan, metadata)
                    },
                    onRestore = {
                        // Forward restore taps to the
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
    }

    private fun cleanup() {
        // 3 — slot was already removed
        // from activeLaunches in onCreate; no companion-object cleanup
        // required. instanceConfig is dropped by Activity destruction.
        instanceConfig = null
        finish()
    }

    override fun onBackPressed() {
        // Force-choice paywalls: when `dismiss.allowed == false`,
        // intercept the system back so the user can't dismiss without selecting
        // a plan or restoring. Mirrors iOS `Paywalls/PaywallRenderer.swift` which
        // wraps the paywall in `.interactiveDismissDisabled(!allowed)`.
        // 3 — read THIS Activity's
        // dismiss policy from instanceConfig, NOT a global slot.
        // `delay_seconds` reveals dismiss after N seconds even when
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
        // OB-5 audit-2 HIGH-3 — backstop for
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
        // 4 — GC the launch slot ONLY
        // on real dismissal (`isFinishing && !isChangingConfigurations`).
        // Config-change recreates (rotation, dark-mode, locale, font
        // scale) MUST keep the slot so the recreated Activity's
        // onCreate can read it. Without this guard, rotating the device
        // during a paywall would lose all pending callbacks +
        // PaywallConfig and `finish()` the recreated instance.
        if (isFinishing && !isChangingConfigurations) {
            launchToken?.let { activeLaunches.remove(it) }
            // Clear our weak ref entry so a stale
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

        // 3 P1 fix — per-launch slot
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
            /**
             * #1074 / #1076 — what the USER DID, reported separately from what it achieved.
             *
             * `CTA_TAPPED` used to be emitted by `PaywallManager.onPlanSelected`, which is reached
             * only once the renderer's `plan != null` guard has passed. So a tap that could not
             * proceed told the host nothing, and "the SDK never heard it" and "the store refused"
             * were the same silence (#1072).
             *
             * One slot for the whole `PaywallAction` set rather than one per case: the renderer
             * decides WHICH action happened, and a per-case slot would have to be threaded through
             * `PaywallSectionView` five times over.
             */
            val onAction: ((PaywallAction) -> Unit)? = null,
        )
        private val activeLaunches = java.util.concurrent.ConcurrentHashMap<String, LaunchSlots>()

        // Per-paywallId registry of live PaywallActivity
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
         * Look up the currently-live PaywallActivity for
         * the given paywallId so [PaywallManager.handleRestore] can call
         * [dismissAfterRestore] from a non-Activity coroutine context.
         * Returns null if no Activity is registered or the WeakReference
         * has been cleared by GC.
         */
        @JvmStatic
        internal fun activeInstance(paywallId: String): PaywallActivity? =
            activePaywallInstances[paywallId]?.get()

        /**
         * Finish ALL live paywall activities
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
                            // When the CALLER already handles the dismiss notification (the
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
         * Clear all in-flight slot
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
            onAction: ((PaywallAction) -> Unit)? = null,
        ) {
            val token = java.util.UUID.randomUUID().toString()
            activeLaunches[token] = LaunchSlots(
                config = config,
                onAppear = onAppear,
                onDismiss = onDismiss,
                onPlanSelected = onPlanSelected,
                onPromoCodeSubmit = onPromoCodeSubmit,
                onRestore = onRestore,
                onAction = onAction,
            )
            val intent = Intent(context, PaywallActivity::class.java).apply {
                putExtra(EXTRA_PAYWALL_ID, paywallId)
                putExtra(EXTRA_LAUNCH_TOKEN, token)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // 4 — startActivity can
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
         * Broadcast post-purchase success overlay (confetti +
         * lottie) into a live PaywallActivity composition. Mirrors iOS's
         * `NotificationCenter.default.post(.paywallPurchaseSuccess, ...)`.
         */
        @Volatile internal var postPurchaseOverlay: PostPurchaseOverlayState? = null

        /**
         * Bumped by [PaywallManager] when a paywall purchase ends without success
         * (a failure, a user cancel, a pending approval, or the purchase coroutine cancelled — e.g.
         * `shutdown()` with the paywall on screen) — and, since an earlier fix, after a success with no `on_success`
         * config, where the paywall stays up for the host to close. A live paywall re-enables its CTA (`isPurchasing =
         * false`) when it sees the value change. Before this, only a drained `retry` / `show_error`
         * overlay reset it, so a failure with no `on_failure` config — or a cancelled purchase — left the
         * CTA spinning and disabled for good.
         */
        internal val purchaseEndedSignal = java.util.concurrent.atomic.AtomicLong(0L)

        @JvmStatic
        internal fun signalPurchaseEnded() {
            purchaseEndedSignal.incrementAndGet()
        }
    }
}

/**
 * Payload for the post-purchase success overlay (confetti +
 * lottie animation rendered atop the paywall after a successful purchase).
 */
internal data class PostPurchaseOverlayState(
    val message: String? = null,
    val confetti: Boolean = false,
    val lottieUrl: String? = null,
    /**
     * Failure overlay
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

// Parity: maps a console font-weight string (normal | medium | semibold | bold) to a Compose
// FontWeight. Mirrors iOS resolveCTAFontWeight semantics.
//
// #979 — the fallback is a PARAMETER because the countdown needs two of them: its label has
// always drawn SemiBold and its digits Bold, so one shared default would have changed the pixels
// of every countdown already in the field.
fun resolveWeight(w: String?, fallback: FontWeight): FontWeight = when (w?.lowercase()) {
    "normal", "regular" -> FontWeight.Normal
    "medium" -> FontWeight.Medium
    "bold" -> FontWeight.Bold
    "semibold" -> FontWeight.SemiBold
    else -> fallback
}

fun resolveCtaWeight(w: String?): FontWeight = resolveWeight(w, FontWeight.SemiBold)

@Composable
fun PaywallScreen(
    config: PaywallConfig,
    onPlanSelected: (PaywallPlan, Map<String, Any>) -> Unit,
    onRestore: () -> Unit,
    onDismiss: (DismissReason) -> Unit,
    // Callback for promo code validation. Returns true if code is valid, false otherwise.
    onPromoCodeSubmit: ((String, (Boolean) -> Unit) -> Unit)? = null,
    // System dark-mode pref. Renderers/blocks may apply
    // `dark` overrides from console-designed paywall content based on this.
    @Suppress("UNUSED_PARAMETER") isDark: Boolean = false,
) {
    // #1076 — out-of-band, because one more PARAMETER on this composable made ART reject it
    // (see LocalPaywallAction).
    val onAction = LocalPaywallAction.current
    var selectedPlanId by remember { mutableStateOf<String?>(null) }
    var showDismiss by remember { mutableStateOf(false) }
    // #652 — the Back button has its OWN delay, so it cannot ride on `showDismiss`.
    var showBack by remember { mutableStateOf(false) }
    var isPurchasing by remember { mutableStateOf(false) }
    var isDismissing by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(0f) }
    var showConfetti by remember { mutableStateOf(false) }
    // Hoisted toggle states for inclusion in purchase metadata
    val toggleStates = remember { mutableStateMapOf<String, Boolean>() }
    // Promo state hoisted out of PaywallPromoInputSection
    // so CTA tap handlers can fold the validated code into the purchase
    // metadata. Mirrors iOS `PaywallRenderer.swift:2160-2167`.
    var promoCode by remember { mutableStateOf("") }
    var promoState by remember { mutableStateOf("idle") } // idle | loading | success | error
    // Post-purchase success overlay state. Drained from the
    // companion-object slot every recomposition so PaywallManager.handle
    // PostPurchaseSuccess can fire confetti + lottie atop the paywall while
    // the dismiss timer counts down.
    var postPurchaseOverlay by remember { mutableStateOf<PostPurchaseOverlayState?>(null) }
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        // The purchase-ended signal as of this composition; a later bump re-enables
        // the CTA (see [PaywallActivity.signalPurchaseEnded]).
        var seenPurchaseEnded = PaywallActivity.purchaseEndedSignal.get()
        // Poll the companion-object slot for new overlay payloads. Cheap
        // (one variable read per frame) — and we only enter this loop while
        // the paywall composition is alive.
        while (true) {
            val purchaseEnded = PaywallActivity.purchaseEndedSignal.get()
            if (purchaseEnded != seenPurchaseEnded) {
                seenPurchaseEnded = purchaseEnded
                isPurchasing = false
            }
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
        // Read effective plans (sections OR
        // top-level config.plans fallback). Computed inline because
        // effectivePlans() lives in the @Composable body below; mirror its
        // logic here at the LaunchedEffect site.
        val sectionPlans = config.sections
            .firstOrNull { it.type == "plans" }
            ?.data?.plans
            ?: emptyList<PaywallPlan>()
        // #1056 — the authored rank, which both platforms decoded and neither read.
        val plans = PaywallPlanOrder.sorted(if (sectionPlans.isEmpty()) (config.plans ?: emptyList()) else sectionPlans)
        selectedPlanId = plans.firstOrNull { it.is_default == true }?.id
            ?: plans.firstOrNull()?.id

        // Initialize toggle defaults from config
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

    // #652 — the Back button reveals on its own schedule. Deliberately NOT gated on
    // `dismiss.allowed`: that flag is about the close affordance on a force-choice paywall, and an
    // author who switched Back on has said what they want.
    LaunchedEffect(config.dismiss?.back?.enabled, config.dismiss?.back?.delay_seconds) {
        val back = config.dismiss?.back
        if (back?.enabled != true) {
            showBack = false
            return@LaunchedEffect
        }
        val backDelay = back.delay_seconds ?: 0
        if (backDelay > 0) {
            kotlinx.coroutines.delay(backDelay * 1000L)
        }
        showBack = true
    }

    // Localization helper: Template variable interpolation
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

        // Extract sticky_footer section
        val stickyFooterSection = config.sections.firstOrNull { it.type == "sticky_footer" }

        // `effectivePlans` helper. Mirrors iOS
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
            // #1056 — the authored rank, which both platforms decoded and neither read.
            return PaywallPlanOrder.sorted(if (sectionPlans.isEmpty()) (config.plans ?: emptyList()) else sectionPlans)
        }
        // CTA text resolution chain is now
        // inlined at the CTA section render site (PaywallActivity.kt:~1497)
        // because that site lives inside PaywallSectionView, a separate
        // @Composable scope from PaywallScreen. The helper that previously
        // lived here was dead code.

        // #903 — the pinned-zone cap's state, declared HERE because the viewport is measured on
        // the Column below and Kotlin cannot see a variable declared after the lambda that reads it.
        var pinnedHeightPx by remember { mutableStateOf(0) }
        var viewportHeightPx by remember { mutableStateOf(0) }
        var bottomZoneOverflowed by remember { mutableStateOf(false) }

        // Content in a Column with scrollable area + sticky footer
        Column(
            modifier = Modifier
                .fillMaxSize()
                // #903 — the viewport the pinned-zone cap is measured against. It has to be
                // measured in THIS scope: the state the cap reads lives here, and the root Box
                // further down the file is a different composable that cannot see it.
                .onSizeChanged { viewportHeightPx = it.height },
        ) {
            // Convert eager `Column.verticalScroll` to
            // `LazyColumn` with stable keys so the recompose-window only
            // touches sections that scrolled into view + plan/feature
            // sub-lists don't fully reconstruct when a single section
            // re-emits. iOS uses `ScrollView { LazyVStack }` for the same
            // reason (PaywallRenderer.swift:38-67).
            val staggerDelay = config.animation?.section_stagger_delay_ms ?: 0
            // Filter `sticky_footer`
            // (rendered separately below) so it doesn't render twice.
            // Mirrors iOS PaywallRenderer.swift:38-67,119 partition.
            //
            // `legal` sections that
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
            // Exclude the CTA section from the scroll body so it
            // can be PINNED to the bottom zone (iOS safeAreaInset(.bottom),
            // PaywallRenderer.swift:141-230) instead of floating mid-screen.
            /*
             * 🔴 #1073 — A PAYWALL WITH A TOP-LEVEL `cta` AND NO `cta` SECTION HAD NO BUY BUTTON.
             *
             * iOS has carried a fallback for this since the renderer was written —
             * `PaywallRenderer.swift`'s `} else if let cta = config.cta {` draws a pinned button
             * from the document-root `cta` when the layout holds no `cta` section. Android looked
             * for the SECTION and stopped, so those paywalls rendered every other section and
             * then simply ended: the plans were selectable and nothing could buy them.
             *
             * Measured on dev: 5 of 42 ACTIVE paywalls are authored that way, and one of them is
             * what a device walk opened. It is the surface that takes the money, so this is the
             * worst shape the divergence Rule 8 exists for can take.
             *
             * A SYNTHESISED SECTION, not a second button. The `"cta"` arm of `PaywallSectionView`
             * already resolves the label, colours and radius from `config.cta` when the section's
             * own data is silent, so handing it an empty `cta` section reuses the renderer the
             * authored path uses rather than re-implementing it beside it — a copy could only
             * drift, and would drift on the one screen nobody can afford to have drift.
             */
            val ctaSection = when {
                ctaIndex >= 0 -> config.sections[ctaIndex]
                config.cta != null && stickyFooterSection == null ->
                    // `cta_text` carries iOS's own default for this path — its root fallback reads
                    // `cta.text ?? "Continue"` while the SECTION path defaults to "Subscribe", and
                    // the two would otherwise disagree on a root cta authored with no text. It
                    // sits BELOW `config.cta.text` in the arm's resolution chain, so an authored
                    // label still wins.
                    PaywallSection(
                        type = "cta",
                        id = "__root_cta__",
                        data = PaywallSectionData(cta_text = "Continue"),
                    )
                else -> null
            }

            /*
             * #884 — ANY section can be pinned to the bottom zone, not only the CTA.
             *
             * > Owner, 2026-10-16: "don't you think this whole section should be tied to low zone
             * > not to top to look better in therms of UI/UX?" — about the plan tiles.
             *
             * Before this, the plans scrolled while the button that buys them stayed put, so a
             * generated paywall left a gap where they had been and pushed the choice off screen
             * on a short device. A section authored `zone: "bottom"` now joins the pinned group,
             * in the order it was authored, ABOVE the CTA — which is the reading order a user
             * expects: choose, then confirm.
             *
             * 🔴 CAPPED, because pinning everything is the same bug the other way round. A pinned
             * group taller than PINNED_ZONE_MAX_FRACTION of the screen would leave a sliver to
             * scroll in, so past that only the CTA (and the legal beneath it) stay pinned and the
             * rest goes back to the body. The CTA is never unpinned: reaching it is the whole
             * point of the zone.
             */
            val authoredBottomZone = config.sections.filter {
                it.zone == "bottom" && it.type != "cta" && it !in pinnedLegalSet && it.type != "sticky_footer"
            }

            /*
             * #903 — the cap this comment has been describing. `PINNED_ZONE_MAX_FRACTION` was named
             * here and existed in no file; `PaywallBottomZone` is it, measured rather than guessed.
             *
             * 🔴 LATCHED, because the decision feeds back into what it measures. Unpinning shrinks
             * the pinned group below the threshold, which would re-pin it, which would exceed the
             * threshold again — a paywall oscillating once per frame. The first full measurement
             * decides, and the decision holds for this presentation.
             */
            if (!bottomZoneOverflowed &&
                PaywallBottomZone.overflows(pinnedHeightPx.toDouble(), viewportHeightPx.toDouble())
            ) {
                bottomZoneOverflowed = true
            }
            // Everything except the CTA goes back to the body, in its authored position.
            val bottomZoneSections = if (bottomZoneOverflowed) emptyList() else authoredBottomZone
            val scrollableSections = config.sections.filter {
                it.type != "sticky_footer" && it.type != "cta" && it !in pinnedLegalSet &&
                    it !in bottomZoneSections
            }

            /*
             * #881 — the dismiss control's footprint, computed ONCE and handed to the header.
             *
             * The SAME resolution the overlay uses (`dismissType`, PaywallActivity.kt:1220) and the
             * same gate (`neverDismissable`, :685), so the inset and the control can never disagree
             * about whether there is a button up there. A delayed reveal reserves from the START,
             * or the title reflows the moment the button appears. `text_link` sits at the bottom
             * and `swipe_down` is a capsule at the top CENTRE, so neither steals width from a
             * centred title.
             *
             * The arithmetic matches the control: `.padding(16.dp).size((glyphSize * 2).dp)`.
             */
            // #895 — through `PaywallHeaderInset`, the one function the shared fixture drives on
            // both platforms. It was inline on each side and pinned by a source gate, which cannot
            // tell you the two agree: they did not, because iOS reserved space for a `swipe`
            // dismiss and this side did not.
            val headerCloseInset = PaywallHeaderInset.resolve(config.dismiss).dp
            // collapse_on_scroll wiring.
            // Mirrors iOS PaywallScrollOffsetPrefKey-driven collapse: as user
            // scrolls, sections marked collapse_on_scroll==true fade their
            // alpha + slightly translate up. We hoist a LazyListState here
            // and derive a SINGLE global scroll offset that all marked
            // sections share — see globalCollapseRatio below.
            val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
            // 1 HIGH-1 — collapse_on_scroll
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
                            headerCloseInset = headerCloseInset,
                            selectedPlanId = selectedPlanId,
                            isPurchasing = isPurchasing,
                            onPlanSelect = { planId ->
                                selectedPlanId = planId
                                // #1076 — the user CHOSE this plan (see the sibling sites).
                                onAction(PaywallAction.PLAN_CHANGED)
                                // Haptic on plan select
                                HapticEngine.triggerIfEnabled(
                                    currentView,
                                    config.haptic?.triggers?.on_plan_select,
                                    config.haptic,
                                )
                            },
                            onCTATap = {
                                // #1074 — the tap, before the guard below decides whether it can
                                // become a purchase.
                                onAction(PaywallAction.CTA_TAPPED)
                                // PW-2 — top-level plans fallback.
                                val plans = effectivePlans()
                                val plan = plans.firstOrNull { p -> p.id == selectedPlanId }
                                if (plan != null) {
                                    isPurchasing = true
                                    // Haptic on CTA tap
                                    HapticEngine.triggerIfEnabled(
                                        currentView,
                                        config.haptic?.triggers?.on_button_tap,
                                        config.haptic,
                                    )
                                    // Confetti on purchase
                                    if (config.particle_effect != null) {
                                        showConfetti = true
                                    }
                                    // Fold validated promo
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
                            // Lets an authored button leave the paywall.
                            onDismiss = { triggerDismiss() },
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = (config.layout.footer_padding ?: 8f).dp)
                    // #903 — the pinned group's own height, which is what the cap is measured on.
                    .onSizeChanged { pinnedHeightPx = it.height },
            ) {
            // CTA pinned to the bottom zone: below the scroll
            // body (weight(1f)), above the pinned legal + sticky footer. Same
            // horizontal inset as the scrolled content. Mirrors iOS
            // safeAreaInset(.bottom) ordering (PaywallRenderer.swift:141-230).
            // #884 — the authored bottom-zone sections, above the CTA, in authored order.
            bottomZoneSections.forEach { pinned ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = (config.layout.padding ?: 20f).dp)) {
                    PaywallSectionView(
                        section = pinned,
                        config = config,
                        selectedPlanId = selectedPlanId,
                        isPurchasing = isPurchasing,
                        onPlanSelect = { planId ->
                            selectedPlanId = planId
                            // #1076 — the user CHOSE this plan. Only a tap reaches here; the
                            // default selection is seeded directly, so it is not reported as a
                            // change the user made.
                            onAction(PaywallAction.PLAN_CHANGED)
                            HapticEngine.triggerIfEnabled(currentView, config.haptic?.triggers?.on_plan_select, config.haptic)
                        },
                        onCTATap = {},
                        onRestore = {},
                        onDismiss = { triggerDismiss() },
                        loc = ::loc,
                        toggleStates = toggleStates,
                        promoCode = promoCode,
                        promoState = promoState,
                        onPromoCodeChange = { promoCode = it },
                        onPromoStateChange = { promoState = it },
                    )
                }
                Spacer(modifier = Modifier.height((config.layout.spacing ?: 16f).dp))
            }
            ctaSection?.let { cta ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = (config.layout.padding ?: 20f).dp)) {
                    PaywallSectionView(
                        section = cta,
                        config = config,
                        selectedPlanId = selectedPlanId,
                        isPurchasing = isPurchasing,
                        onPlanSelect = { planId ->
                            selectedPlanId = planId
                            // #1076 — the user CHOSE this plan. Only a tap reaches here; the
                            // default selection is seeded directly, so it is not reported as a
                            // change the user made.
                            onAction(PaywallAction.PLAN_CHANGED)
                            HapticEngine.triggerIfEnabled(currentView, config.haptic?.triggers?.on_plan_select, config.haptic)
                        },
                        onCTATap = {
                            // #1074 — the tap, before the guard below decides whether it can
                            // become a purchase. A tap that cannot told the host nothing at all.
                            onAction(PaywallAction.CTA_TAPPED)
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
                        // Lets an authored button leave the paywall.
                        onDismiss = { triggerDismiss() },
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

            // Pinned legal sections
            // render between the scroll body and the sticky footer (or above
            // the system inset when no sticky footer). Mirrors iOS
            // PaywallRenderer.swift:213-222 safeAreaInset.bottom layer where
            // legals authored AFTER the cta stay permanently visible.
            if (pinnedLegalSections.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = (config.layout.padding ?: 20f).dp)
                        // The pinned legal (Terms & Conditions) is the BOTTOM-most
                        // element; without the nav-bar inset it rendered under the gesture bar
                        // and was clipped/invisible (iOS shows it under Restore). Clear the bar.
                        .navigationBarsPadding(),
                ) {
                    for (section in pinnedLegalSections) {
                        PaywallSectionView(
                            section = section,
                            config = config,
                            headerCloseInset = headerCloseInset,
                            selectedPlanId = selectedPlanId,
                            isPurchasing = isPurchasing,
                            onPlanSelect = {},
                            onCTATap = {},
                            onRestore = onRestore,
                            // Lets an authored button leave the paywall.
                            onDismiss = { triggerDismiss() },
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

            // Sticky footer pinned to bottom
            if (stickyFooterSection != null) {
                PaywallStickyFooter(
                    section = stickyFooterSection,
                    isPurchasing = isPurchasing,
                    // Round-MZ — selected plan's per-plan cta_text overrides the footer label.
                    selectedPlanCtaText = effectivePlans().firstOrNull { it.id == selectedPlanId }?.cta_text?.takeIf { it.isNotBlank() },
                    onCTATap = {
                        // #1074 — the tap, before the guard below decides whether it can become a
                        // purchase.
                        onAction(PaywallAction.CTA_TAPPED)
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
                            // Fold validated promo code
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

        // Confetti overlay
        if (showConfetti && config.particle_effect != null) {
            ConfettiOverlay(
                effect = config.particle_effect,
                trigger = showConfetti,
            )
        }

        // Post-purchase success overlay (confetti + lottie).
        // Mirrors iOS `PaywallRenderer.swift:346` `.paywallPurchaseSuccess`
        // notification observer. Confetti uses an ad-hoc ParticleEffect when
        // the paywall has no `particle_effect` config of its own. Lottie URL
        // renders via the shared LottieBlockView.
        postPurchaseOverlay?.let { overlay ->
            // Only render confetti if the
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
                // a centered card atop the paywall.
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

        // #652 — the Back button, rendered INDEPENDENTLY of the close control.
        //
        // The first pass made "back" a dismiss STYLE, so a paywall could have a chevron or an X but
        // never both, and they shared one position/colour/size. They are two buttons. iOS parity:
        // PaywallRenderer.swift `backAffordance`.
        config.dismiss?.back?.let { back ->
            if (back.enabled == true && showBack) {
                val backCd = stringResource(R.string.appdna_a11y_paywall_close)
                val backSize = back.size ?: 16f
                // #946 — the chevron is drawn on the PAGE.
                val backColor = back.color?.let { parseHexColor(it) }
                    ?: PaywallTextDefaults.readable(config.pageBackgroundHex())
                val backOnLeft = (back.position ?: "top_left") == "top_left"
                if ((back.style ?: "chevron") == "text_link") {
                    TextButton(
                        onClick = { triggerDismiss() },
                        modifier = Modifier
                            .align(if (backOnLeft) Alignment.TopStart else Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(16.dp)
                            .semantics { contentDescription = backCd },
                    ) {
                        Text(
                            text = loc("back.text", back.text ?: "Back"),
                            color = backColor,
                            fontSize = backSize.sp,
                        )
                    }
                } else {
                    IconButton(
                        onClick = { triggerDismiss() },
                        modifier = Modifier
                            .align(if (backOnLeft) Alignment.TopStart else Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(16.dp)
                            .size((backSize * 2).dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.3f))
                            .semantics { contentDescription = backCd },
                    ) {
                        Text(
                            // ‹ chevron or ← arrow — the glyph is the only visual difference.
                            text = if ((back.style ?: "chevron") == "arrow") "\u2190" else "\u2039",
                            color = backColor,
                            fontSize = backSize.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        // Dismiss control
        if (showDismiss) {
            // Prefer `dismiss.style`
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
                            // Text-link dismiss has no icon
                            // semantic so we attach an explicit a11y label.
                            .semantics { contentDescription = dismissCd },
                    ) {
                        Text(
                            text = loc("dismiss.text", config.dismiss?.text ?: "No thanks"),
                            // #946 — "No thanks" is drawn on the page, not on a control.
                            color = PaywallTextDefaults.readable(config.pageBackgroundHex())
                                .copy(alpha = 0.6f),
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
                            // #946 — the grabber is the only affordance on a swipe-down paywall,
                            // and white at 50% over a white page is no affordance at all.
                            .background(
                                PaywallTextDefaults.readable(config.pageBackgroundHex()).copy(alpha = 0.5f),
                            )
                    )
                }
                // A chevron that LEAVES the paywall, which returns the user to
                // whatever was presented before it. Same operation as close on a single-view
                // paywall; only the glyph differs. Shares the
                // x_button branch so an authored size/colour/position cannot apply to one and be
                // forgotten on the other. Navigation between views INSIDE one paywall is a
                // separate feature ("FR · Console · Multi-view paywalls"), not built here.
                else -> { // back_button chevron, or x_button (the default)
                    val closeCd = stringResource(R.string.appdna_a11y_paywall_close)
                    val glyphSize = (config.dismiss?.size ?: 16f)
                    // #946 — the ✕ is drawn on the PAGE, so its default is read off the page.
                    val glyphColor = config.dismiss?.color?.let { parseHexColor(it) }
                        ?: PaywallTextDefaults.readable(config.pageBackgroundHex())
                    val onLeft = (config.dismiss?.position ?: "top_right") == "top_left"
                    IconButton(
                        onClick = { triggerDismiss() },
                        modifier = Modifier
                            .align(if (onLeft) Alignment.TopStart else Alignment.TopEnd)
                            // Sit BELOW the status bar (edge-to-edge Activity),
                            // matching iOS which places the X inside the safe area. Without
                            // this the X overlapped the clock/battery and was hard to tap.
                            .statusBarsPadding()
                            .padding(16.dp)
                            .size((glyphSize * 2).dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.3f))
                            // \u2014 close X has no semantic icon
                            // (it's a Text composable with the unicode glyph),
                            // so attach an a11y label that TalkBack reads.
                            .semantics { contentDescription = closeCd },
                    ) {
                        Text(
                            // ✕ for close, ‹ for back — the glyph is the only visual difference.
                            text = if (dismissType == "back_button") "\u2039" else "\u2715",
                            color = glyphColor,
                            fontSize = glyphSize.sp,
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
    // Full PaywallBackground matrix
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
                        // iOS uses UnitPoint (0..1 FRACTIONS). Compose
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
            // iOS treats the literal
            // strings "transparent" / "clear" as Color.clear
            // (PaywallRenderer.swift:427-431). Android parseHexColor("transparent")
            // falls through to Color.Black, so console-authored
            // bg.color="transparent" rendered BLACK on Android and CLEAR on iOS.
            // Special-case those literals here before parseHexColor is called.
            //
            // 🔴 #909 — an UNSET colour is WHITE, not black.
            //
            // iOS has always resolved this to white (`PaywallRenderer.swift`:
            // `bg?.color ?? bg?.value ?? "#FFFFFF"`) and this side resolved it to BLACK, so one
            // published document painted opposite pages on the two platforms.
            //
            // Worse than a cosmetic split: the server's contrast repair computes every default
            // text colour against #FFFFFF for an unset background — its own log says
            // `header.title #FFFFFF->#111827 on #FFFFFF`. So a repaired paywall carries text
            // chosen to read on WHITE and Android painted it on BLACK. Measured on a Pixel API 34
            // emulator with a paywall whose colours were stripped from the published document:
            // page (0,0,0), title #111827, and the selected plan tile's brightest text pixel
            // IDENTICAL to its background — a contrast ratio of 1:1, text that is simply not there.
            //
            // Two ACTIVE production paywalls publish `background: {"type":"color"}` with no value.
            val color = when (background.value?.lowercase()?.trim()) {
                null, "" -> Color.White
                "transparent", "clear" -> Color.Transparent
                else -> background.value?.let { parseHexColor(it) } ?: Color.White
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
 * Video background composable backed by
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

/**
 * Extra buttons under the CTA, in authored order.
 *
 * What lifts the "two buttons, one of them restore" cap: a second restore, a "Maybe later", or a
 * terms link are all just entries here. Mirrors iOS `extraButtonsView`.
 *
 * `link` goes through `URLSafety.open`, the same allowlisted path a legal markdown link uses — a
 * config-driven URL is never handed straight to an Intent.
 */
@Composable
private fun PaywallExtraButtons(
    buttons: List<PaywallExtraButton>,
    cornerRadius: Float,
    onCTATap: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
    loc: (String, String) -> String,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val onAction = LocalPaywallAction.current   // #1076
    for (button in buttons) {
        val label = loc("cta.extra.${button.text ?: ""}", button.text ?: "")
        if (label.isBlank()) continue
        val size = (button.font_size ?: 16f).sp
        val onTap: () -> Unit = {
            when (button.action ?: "dismiss") {
                "purchase" -> onCTATap()
                "restore" -> onRestore()
                "link" -> {
                    onAction(PaywallAction.LINK_TAPPED)   // #1076
                    button.url?.let { ai.appdna.sdk.core.URLSafety.open(context, it) }
                }
                "dismiss" -> onDismiss()
                else -> {
                    // #1076 — an authored action this SDK does not know. It still dismisses, which
                    // is what the unauthored default has always done, but the host is told: that
                    // is the only meaning an action "defined in the paywall config" can have.
                    onAction(PaywallAction.CUSTOM)
                    onDismiss()
                }
            }
        }
        if ((button.style ?: "filled") == "text") {
            Text(
                text = label,
                fontSize = size,
                fontWeight = FontWeight.Medium,
                color = button.text_color?.let { parseHexColor(it) }
                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { onTap() }.padding(vertical = 6.dp),
            )
        } else {
            Button(
                onClick = onTap,
                // #651 — an extra button had a Background but always inherited the CTA's radius, so
                // a pill CTA forced every extra button to be a pill too. Unset still inherits.
                shape = RoundedCornerShape((button.corner_radius ?: cornerRadius).dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = button.bg_color?.let { parseHexColor(it) }
                        ?: MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = label,
                    fontSize = size,
                    fontWeight = FontWeight.SemiBold,
                    color = button.text_color?.let { parseHexColor(it) } ?: Color.White,
                )
            }
        }
    }
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
    // An authored button can be told to DISMISS, which leaves the
    // paywall and so returns the user to the previous screen. Defaulted, so the other
    // call sites of this composable are unaffected.
    onDismiss: () -> Unit = {},
    loc: (String, String) -> String,
    toggleStates: MutableMap<String, Boolean> = mutableMapOf(),
    onPromoCodeSubmit: ((String, (Boolean) -> Unit) -> Unit)? = null,
    // Hoisted promo state for purchase metadata.
    promoCode: String = "",
    promoState: String = "idle",
    onPromoCodeChange: (String) -> Unit = {},
    onPromoStateChange: (String) -> Unit = {},
    /**
     * #881 — the width the floating dismiss control occupies in the top corner, reserved on BOTH
     * sides of the header so centred text stays centred.
     *
     * The header title is centred across the FULL width and the dismiss control is an overlay with
     * nothing holding space for it, so a title whose first line reaches the edge runs underneath.
     * On a device, two different generated paywalls both put the ✕ on the word "going". It is a
     * generated-content problem meeting a hand-authored default: the prompt asks for an
     * outcome-led headline, so a generated title is a full sentence (31-38 characters on dev)
     * where a hand-authored one is a few words.
     *
     * Passed IN rather than derived here, matching iOS — and so this composable names no
     * paywall-level config that is not its own section's.
     */
    headerCloseInset: Dp = 0.dp,
) {
    val onAction = LocalPaywallAction.current   // #1076
    /*
     * #946 — the page, published once for every composable beneath this one.
     *
     * Twelve of them resolved colours by assuming a backdrop, and the two that were measured
     * came back as blank white rectangles on a light page (#938). Threading one string through
     * twelve signatures is what kept that from being fixed; a CompositionLocal costs each of
     * them one line and no signature at all.
     *
     * Provided HERE rather than only in `PaywallScreen`, because the section renderers are also
     * driven directly — by the snapshot tests, and by the screens surface — and the default of
     * `null` would quietly mean "assume white" on those paths.
     */
    CompositionLocalProvider(LocalPaywallPageHex provides config.pageBackgroundHex()) {
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
                    // #881 — the dismiss control's footprint, reserved on BOTH sides. Computed by
                    // the caller (`headerCloseInset`), the way iOS does it, so this branch names no
                    // paywall-level config of its own.
                    .padding(horizontal = headerCloseInset)
                    .run { with(StyleEngine) { applyContainerStyle(section.style?.container) } }
            ) {
                // Render header section image
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
                    // Prefer data-level
                    // `title_style` over section-level
                    // `style.elements["title"].text_style` matching iOS
                    // HeaderSection.swift:10-15 precedence.
                    val titleStyle = StyleEngine.applyTextStyle(
                        // #891 — read off the PAGE, not off the theme. `onSurface` is the device's
                        // night-mode answer and iOS's `.primary` was the same mistake in the other
                        // direction: a paywall with a dark authored background drew a dark title on
                        // a light-appearance phone, and the words were gone. The author cannot see
                        // the setting that decided it, so the background decides instead.
                        TextStyle(color = PaywallTextDefaults.readable(config.pageBackgroundHex()), fontWeight = FontWeight.Bold, fontSize = 28.sp, textAlign = TextAlign.Center),
                        section.data.title_style ?: section.style?.elements?.get("title")?.text_style
                    )
                    Text(
                        text = loc("section-header.title", it),
                        style = titleStyle,
                        // Paywall hero/header title is the
                        // accessibility heading for the screen, mirroring iOS
                        // `accessibilityAddTraits(.isHeader)`.
                        // #910 — and its authored container style, which the console has always
                        // offered and this platform ignored until #905 made the key decode.
                        modifier = Modifier
                            .semantics { heading() }
                            .run {
                                with(StyleEngine) {
                                    applyContainerStyle(section.style?.elements?.get("title")?.elementContainer)
                                }
                            },
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
                        // #910 — the subtitle's authored container, as the title has.
                        modifier = Modifier.run {
                            with(StyleEngine) {
                                applyContainerStyle(section.style?.elements?.get("subtitle")?.elementContainer)
                            }
                        },
                    )
                }
            }
        }
        "features" -> {
            /*
             * 🔴 THE PAGE-READABLE INK, NOT WHITE (#1067). This defaulted to `Color.White`, so on a
             * light paywall every feature line was WHITE ON WHITE — the text was drawn the whole
             * time and nobody could see it. A device walk read five bullet points with no words
             * beside them, which looks like missing data rather than a colour.
             *
             * It is #946's fix, which every other section on this screen already has (the countdown
             * and the comparison table both read `PaywallTextDefaults.readable`), and which the
             * features list never received. iOS has used it since: `FeatureList.swift` draws with
             * `PaywallTextDefaults.readable(on: pageBackgroundHex)`.
             */
            val featureItemStyle = StyleEngine.applyTextStyle(
                TextStyle(color = PaywallTextDefaults.readable(LocalPaywallPageHex.current), fontSize = 16.sp),
                section.style?.elements?.get("item")?.text_style
            )
            // #910 — the item's authored container, from `item_text` or its `item` alias, resolved
            // ONCE and applied at BOTH text sites below.
            //
            // 🔴 This section renders the same item text TWICE, from two different data keys: the
            // legacy `data.features` list of strings and the rich `data.items` list of objects
            // (see the RICH ITEMS note below). Wiring only the first one is exactly the defect
            // #905 shipped on the plans section — the border was authored, served and absent from
            // the screen, because the branch production actually uses was the other one. The
            // device frame is what caught it both times. iOS does not have this hazard: its
            // `itemText(_:)` is one function that both loops call.
            val featureItemModifier = Modifier.run {
                with(StyleEngine) {
                    applyContainerStyle(
                        section.style?.elements?.get("item_text")?.elementContainer
                            ?: section.style?.elements?.get("item")?.elementContainer,
                    )
                }
            }
            // #910 — and the `icon` element's container, which the console offers beside the icon
            // colour it already honoured. Applied per branch rather than by wrapping the `when` in
            // a Box: a Box is a layout node whether or not the author set anything, and the five
            // icon shapes below are already aligned by their Row.
            val featureIconModifier = Modifier.run {
                with(StyleEngine) {
                    applyContainerStyle(section.style?.elements?.get("icon")?.elementContainer)
                }
            }
            // \u2014 iOS FeatureList is a content-sized VStack(.leading)
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
                            /*
                             * #1076 — the row is TAPPABLE and reports `FEATURE_SELECTED`.
                             *
                             * 🔴 `clickable`, with no ripple and no visual change: a paywall's
                             * feature list lives inside a vertical scroll, and this repo has
                             * already shipped a tap handler that swallowed every gesture on the
                             * screen. `clickable` yields to the scroll's drag; a `pointerInput`
                             * that consumed events would not.
                             *
                             * The whole row, not just the text — a tap target the width of one
                             * word is a tap target nobody hits.
                             */
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null,
                                ) { onAction(PaywallAction.FEATURE_SELECTED) }
                                .padding(vertical = 6.dp)
                        ) {
                            Text(
                                text = "\u2713",
                                color = ai.appdna.sdk.AppDNA.brandAccentColor(),
                                fontSize = 18.sp,
                                modifier = featureIconModifier,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = loc("feature.$index", feature),
                                style = featureItemStyle,
                                modifier = featureItemModifier,
                            )
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
                    // #1067 — the subtitle takes the same readable ink as the line above it,
                    // at the 70% iOS uses, rather than a white that vanishes on a light page.
                    val subtitleColor = section.data?.item_subtitle_color?.takeIf { it.isNotBlank() }
                        ?.let { StyleEngine.parseColor(it) }
                        ?: PaywallTextDefaults.readable(LocalPaywallPageHex.current).copy(alpha = 0.7f)
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
                            // #1076 — a RICH item row is tappable too, and this is the branch a
                            // real paywall uses: the console writes `config.items`, so the device
                            // walk found `clickable="false"` on the only rows anybody actually
                            // renders while the plain-string branch beside it was already wired.
                            // Same no-indication click as there, so the scroll still wins the drag.
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null,
                                ) { onAction(PaywallAction.FEATURE_SELECTED) }
                                .padding(vertical = 6.dp)
                                .alpha(if (included) 1f else 0.4f),
                        ) {
                            when {
                                !item.emoji.isNullOrEmpty() ->
                                    Text(text = item.emoji, fontSize = 18.sp, modifier = featureIconModifier)
                                !item.image_url.isNullOrEmpty() ->
                                    ai.appdna.sdk.core.NetworkImage(
                                        url = item.image_url,
                                        modifier = featureIconModifier.size(20.dp).clip(imageShape),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    )
                                /*
                                 * #1067 — THROUGH THE RESOLVER, not as raw text. The console
                                 * writes `icon: "check"`, and drawing the string printed the WORD
                                 * "check" down the page where the feature list should be. That is
                                 * right for an emoji and wrong for a NAME, so `NamedIcon` decides
                                 * which it is and `IconView` draws it through the lucide/material
                                 * tables — falling back to an emoji and then a visible placeholder,
                                 * so an unmapped name is seen rather than printed as prose.
                                 */
                                ai.appdna.sdk.core.NamedIcon.reference(item.icon) != null ->
                                    ai.appdna.sdk.core.IconView(
                                        ref = ai.appdna.sdk.core.NamedIcon.reference(item.icon)!!,
                                        modifier = featureIconModifier,
                                        defaultSize = 18f,
                                    )
                                // The excluded cross is AUTHORABLE, not a hardcoded red. A colour
                                // a person sees is a colour a person should be able to set — and a
                                // fixed red is unreadable on the red-toned paywalls it would land on.
                                else -> Text(
                                    modifier = featureIconModifier,
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
                                    modifier = featureItemModifier,
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
            // Fall back to top-level
            // `config.plans` when section has no plans (matches iOS
            // PaywallRenderer.swift:511-512 effectivePlans logic).
            val sectionPlansRaw = section.data?.plans ?: emptyList()
            // #1056 — the authored rank, which both platforms decoded and neither read.
            val plans = PaywallPlanOrder.sorted(if (sectionPlansRaw.isEmpty()) (config.plans ?: emptyList()) else sectionPlansRaw)

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
            /*
             * #891 — white, and deliberately, because THIS platform's unselected tile is dark.
             *
             * The first attempt resolved this from the authored page the way iOS does, and a
             * device frame refused it: with no authored colours the plan NAME disappeared. The
             * tile here is `Color.White.copy(alpha = 0.1f)` over the paywall's own Material
             * surface, which this activity themes DARK regardless of the page — so it comes out
             * dark navy even on a white paywall, and white text is the readable choice.
             *
             * iOS composites the same 10% white over the PAGE instead, so its tile follows the
             * authored background and its text has to be derived. Two tiles, two answers, one
             * authored document — tracked as its own divergence rather than papered over by
             * guessing a backdrop here.
             */
            /*
             * #905 — the CONTAINER style an element asks for, which the console has always offered
             * and neither platform read. Its editor gives every element a TextStylePanel and an
             * ElementStylePanel; the second writes `element_style`, and the DTO had nowhere to put
             * it. `applyContainerStyle` is the same function a SECTION container uses, so an
             * element and its section are styled by one implementation.
             */
            fun elementStyle(key: String) = section.style?.elements?.get(key)?.elementContainer

            /*
             * The two per-element containers this section can carry, resolved ONCE. Twelve plan
             * display styles draw a name and a price, each in its own composable; repeating the
             * expression at each site is how one of them ends up missing it — which is exactly
             * what happened on the first pass, where the default `vertical_stack` branch was a
             * separate implementation from the `PlanCard` composable and only the latter was wired.
             * A device frame caught it: the borders were authored, served, and absent.
             */
            val planNameElementModifier = Modifier.run {
                with(StyleEngine) { applyContainerStyle(elementStyle("plan_name") ?: elementStyle("label")) }
            }
            val priceElementModifier = Modifier.run {
                with(StyleEngine) { applyContainerStyle(elementStyle("price")) }
            }
            // #910 — the other two plan elements the console styles. `trial_label` already had its
            // text style honoured and nothing else; `feature` had NEITHER — the per-plan feature
            // rows drew a hardcoded 13sp on all three layouts that render them, so the Style tab's
            // feature controls did nothing on either platform.
            val trialElementModifier = Modifier.run {
                with(StyleEngine) { applyContainerStyle(elementStyle("trial_label")) }
            }
            val planFeatureElementModifier = Modifier.run {
                with(StyleEngine) { applyContainerStyle(elementStyle("feature")) }
            }
            val planFeatureTextStyle = section.style?.elements?.get("feature")?.text_style
            // #913 — the `period` element's container. Android has rendered the period's TEXT at
            // five sites all along; what it never had is the container half, and what NOTHING had
            // was a way to author `plan.period` at all (no schema field, no console control, 0 of
            // 81 production plans). Both are real now, and iOS draws the period too.
            val periodElementModifier = Modifier.run {
                with(StyleEngine) { applyContainerStyle(elementStyle("period")) }
            }
            /*
             * #946 — the plans section's own ink, read off the page.
             *
             * `planNameStyle` and `priceStyle` default to white below, which is right on a tile
             * filled with the brand accent and wrong on every arm that draws straight onto the
             * page. `radio_list` and `segmented_toggle` were measured on a `#FFFFFF` page and came
             * back with 0 page-derived pixels: the selected row was legible only by its accent
             * fill, and the UNSELECTED plan was not on the screen at all — no tile, no border, and
             * white text.
             */
            val planInk = PaywallTextDefaults.readable(config.pageBackgroundHex())
            val planSubtleInk = PaywallTextDefaults.subtleOnPage(config.pageBackgroundHex())
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
            // Device QA — default badge bg is the brand accent (matches iOS + the console editor
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
            // Device QA — badge border + leading icon were decoded (PaywallConfig.kt:312-314) but
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
                /*
                 * #988 — 6dp, not 4. iOS's `badgeShape` has returned
                 * `RoundedRectangle(cornerRadius: 6)` for `rounded` since it shipped
                 * (`PlanCard.swift:632`), so the same authored badge had a visibly tighter corner
                 * on Android. Two pixels, on the one element a paywall uses to say "most popular".
                 */
                "rounded" -> RoundedCornerShape(6.dp)
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
            // The console writes HYPHENATED positions ("top-right" etc.) but the `when`
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
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        // #905 — the badge's authored container style, applied OUTSIDE its own
                        // chrome so an authored border or padding surrounds it rather than
                        // replacing it.
                        .run { with(StyleEngine) { applyContainerStyle(elementStyle("badge")) } },
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

            // Pull all 5 plan-card show-flags
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
            /*
             * The same authored field as the line above, kept as its HEX so a renderer can
             * composite over the page and derive a readable label from the result.
             *
             * 🔴 Read HERE rather than inside the `when` branch that uses it. check:authorability
             * attributes every renderer read to its enclosing dispatch branch, and the matcher
             * treats the timeline branch as the owner of the unnamed default that follows it — so a
             * plans-section field read down there was reported against the timeline block type,
             * which has no such control, while the control it really has sat one attribution away.
             * Hoisting is the fix; regenerating the baseline would bury it.
             */
            /*
             * 🔴 #938 — the ink, not an assumed white. `composite("#FFFFFF", page, 0.1)` is the page
             * itself when the page IS white, so the unselected tile had no edge on a light paywall
             * — the screenshot showed the second plan as bare text with no card. Compositing the
             * READABLE ink gives a faint gray there and, on any dark page, `readable` returns
             * `#FFFFFF` and the result is byte-identical to what shipped.
             */
            val unselectedTileHex = section.data?.unselected_bg_color
                ?: PaywallTextDefaults.composite(
                    PaywallTextDefaults.readableHex(config.pageBackgroundHex()),
                    config.pageBackgroundHex(),
                    0.1,
                )
            val customSelectedBorder = section.data?.selected_border_color?.let { parseHexColor(it) }
            val customSelectedBg = section.data?.selected_bg_color?.let { parseHexColor(it) }

            /*
             * #966 — the SELECTED tile, as a hex, derived the same way its unselected sibling is.
             *
             * 🔴 THE SELECTED CARD WAS THE ONE THAT DISAPPEARED, which is the inverse of the usual
             * failure and was sitting in a committed golden:
             * `src/test/snapshots/paywall_light_page_plans_vertical_stack.png` shows the selected
             * plan with NO NAME AND NO PRICE — only its grey description survived — while the
             * unselected plan beside it renders all three lines.
             *
             * Two assumptions stacked. The fill defaulted to `Color.White.copy(alpha = 0.16f)`,
             * which over a white page IS the page. And the text defaulted to `Color.Unspecified`,
             * which inherits the Activity's DARK-themed `onSurface` and so came out near-white.
             * White on white.
             *
             * The comment on `planTextColor` already argued this exact point — "the text can no
             * longer be left to the theme: on a white page a white label on a near-white tile is
             * exactly the 1:1 defect #909 fixed" — and then applied it to the UNSELECTED branch
             * only. One branch of one expression.
             *
             * A higher alpha than the unselected tile's 0.1 on purpose: the two must be
             * distinguishable from each other, not merely from the page. On any dark page
             * `readableHex` returns `#FFFFFF` and the composite is byte-identical to the white-16%
             * that shipped, so nothing that is live today moves.
             */
            val selectedTileHex = section.data?.selected_bg_color
                ?: PaywallTextDefaults.composite(
                    PaywallTextDefaults.readableHex(config.pageBackgroundHex()),
                    config.pageBackgroundHex(),
                    0.16,
                )

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
                /*
                 * #926 — COMPOSITED OVER THE PAGE, the way iOS does it and the way the contrast
                 * repair already reasons about it.
                 *
                 * `Color.White.copy(alpha = 0.1f)` is a TRANSLUCENT wash, and what it washes over
                 * here is this activity's Material surface — which is themed DARK whatever the
                 * page says. So one published document gave a near-white tile on iOS and a dark
                 * navy one on Android, and #891's text colour had to be hardcoded white to stay
                 * readable against a backdrop nobody authored.
                 *
                 * Compositing the same 10% white over the authored page yields an OPAQUE colour
                 * that follows the document, so both platforms now draw the same tile from the
                 * same JSON and the text colour can be derived rather than assumed.
                 *
                 * An authored `unselected_bg_color` still wins outright: 15 of 36 active plans
                 * sections on dev author one, and none of them changes.
                 */
                /*
                 * 🔴 #938 — the READABLE ink composited over the page, not an assumed white.
                 * `composite("#FFFFFF", page, 0.1)` returns the page itself when the page IS white,
                 * so this tile had no edge at all on a light paywall. Surfaced by
                 * `check:authorability` once its scans stopped reading comments as code, and it is
                 * the same defect the pill arm had. Byte-identical on any dark page, where
                 * `readable` returns `#FFFFFF` and the composite is the one that shipped.
                 */
                val unselectedBg = unselectedBgColor
                    ?: unselectedTileHex?.let { StyleEngine.parseColor(it) }
                    ?: Color.White.copy(alpha = 0.1f)
                /*
                 * PW-9: text color flips when selected if author provided one.
                 *
                 * #966 — and when the author provided NONE it follows the TILE, on both states.
                 * Both branches used to be `Color.Unspecified`, which inherits the Activity's
                 * DARK-themed `onSurface` and so comes out near-white.
                 *
                 * 🔴 THIS IS THE SHARED CARD, so that one expression reached five arms —
                 * `grid`, `mini_cards`, `horizontal_scroll`, `carousel_cards` and `single_hero`.
                 * Recorded on a `#FFFFFF` page, `grid` drew "Monthly $9.99" and "Annual $49.99" in
                 * white on a near-white card; only the grey descriptions were legible. #946 swept
                 * the SECTIONS and #961 the dispatch arms, and this card sat between them: it is
                 * not a section and it is not an arm.
                 *
                 * The selected tile here is the ACCENT at 10% over the page — this card's own fill,
                 * not the dispatch's `selectedTileHex` — so the ink is judged against that. On any
                 * dark page `readable` returns `#FFFFFF`, which is what `Color.Unspecified`
                 * resolved to there, so nothing that ships today moves.
                 */
                val selectedTileForInk = PaywallTextDefaults.composite(
                    ai.appdna.sdk.AppDNA.brandAccentHex,
                    config.pageBackgroundHex(),
                    0.1,
                )
                val resolvedTextColor = if (isSelected) {
                    selectedTextColor ?: PaywallTextDefaults.readable(selectedTileForInk)
                } else {
                    PaywallTextDefaults.readable(unselectedTileHex)
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
                        // Radio-button role + selected
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
                                modifier = planNameElementModifier,
                            )

                            // PW-9: subtitle above price if `subtitle_position == "above_price"`.
                            if (showPlanSubtitles && subtitlePosition == "above_price" && !plan.description.isNullOrBlank()) {
                                Spacer(Modifier.height(2.dp))
                                // A coloured pill when the product authored
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
                            // Authored strikethrough size/gap, and the
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
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(
                                            text = loc("plan.$planIdx.price", plan.displayPrice),
                                            style = priceStyle,
                                            color = planPriceColor,
                                            modifier = priceElementModifier,
                                        )
                                        Spacer(Modifier.width(2.dp))
                                    // #913 — the period, small beside the price. See the note
                                    // below the branch.
                                    // #933 — `displayPeriod`, not `period`: an authored period wins,
                                    // and a plan that authored none now reads the STORE's, phrased
                                    // in the device's language instead of drawing nothing.
                                    plan.displayPeriod?.takeIf { it.isNotBlank() }?.let {
                                        Text(
                                            text = loc("plan.$planIdx.period", it),
                                            // #913 — the period follows the TILE, like the price does.
                                            // `periodStyle`'s own default is white at 70%, which assumes a
                                            // dark tile; on the selected tile — light, once #909 aligned it —
                                            // that is white-on-white. Measured on a device: the period was
                                            // absent from the selected tile while rendering fine on the
                                            // unselected one beside it. Mirrors iOS `.opacity(0.7)`.
                                            color = if (resolvedTextColor != Color.Unspecified) {
                                                resolvedTextColor.copy(alpha = 0.7f)
                                            } else {
                                                Color.Unspecified
                                            },
                                            style = periodStyle,
                                            modifier = periodElementModifier,
                                        )
                                    }
                                    }
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
                                        modifier = priceElementModifier,
                                        color = planPriceColor,
                                    )
                                    // #913 — the period, small beside the price. See the note
                                    // below the branch.
                                    plan.displayPeriod?.takeIf { it.isNotBlank() }?.let {
                                        Text(
                                            text = loc("plan.$planIdx.period", it),
                                            // #913 — the period follows the TILE, like the price does.
                                            // `periodStyle`'s own default is white at 70%, which assumes a
                                            // dark tile; on the selected tile — light, once #909 aligned it —
                                            // that is white-on-white. Measured on a device: the period was
                                            // absent from the selected tile while rendering fine on the
                                            // unselected one beside it. Mirrors iOS `.opacity(0.7)`.
                                            color = if (resolvedTextColor != Color.Unspecified) {
                                                resolvedTextColor.copy(alpha = 0.7f)
                                            } else {
                                                Color.Unspecified
                                            },
                                            style = periodStyle,
                                            modifier = periodElementModifier,
                                        )
                                    }
                                }
                            }
                            /*
                             * 🔴 #913 — the standalone `plan.period` line is BACK, because all
                             * three reasons for dropping it have stopped being true.
                             *
                             * Audit pass-8 removed it "to match iOS PlanCard, which never renders
                             * plan.period", because "the console preview omits it", and because
                             * "`period` isn't authored via the console (not in PlanSchema)". Each
                             * was accurate at the time. As of #913 iOS renders it beside the price,
                             * `PaywallPreview.tsx` draws it, and `PlanSchema` carries the field
                             * with a control in the paywall editor.
                             *
                             * Deleting a renderer to match the platform that had the gap is how a
                             * field ends up dead on all four surfaces: the removal was correct
                             * parity work and the wrong direction. Production carries 0 periods
                             * across 81 plans, so nothing authored changes today.
                             */
                            // The note that used to live here read: drop the standalone line to match
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
                                    modifier = trialElementModifier,
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
                                // A coloured pill when the product authored
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
                                // Checkmark color matches iOS PlanCard.swift:162
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
                                            style = StyleEngine.applyTextStyle(
                                                TextStyle(fontSize = 13.sp, color = resolvedTextColor),
                                                planFeatureTextStyle,
                                            ),
                                            modifier = planFeatureElementModifier,
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
                    "horizontal_scroll", "carousel" -> {
                        // Horizontally scrollable plan cards — neighbours PEEK at both edges
                        // (48dp content padding), which is what distinguishes this style from
                        // `carousel_cards` below.
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
                    "carousel_cards" -> {
                        /*
                         * #962 — ONE CARD PER PAGE, WITH DOTS, like iOS.
                         *
                         * 🔴 This value used to share the arm above, so Android drew
                         * `carousel_cards` and `horizontal_scroll` IDENTICALLY — while the Console
                         * offers them as two separate choices, and iOS gives `carousel_cards` its
                         * own `case` (a paged `TabView`, which on iOS means full-width pages plus
                         * page dots). So an author picked the second option, got the first one on
                         * Android, and had nothing to tell them why.
                         *
                         * `check:plan-display-parity` could not see it: both platforms HAD an arm,
                         * so neither side was missing anything. Only reading the two bodies showed
                         * it, which is why the gate now also asserts that a style with its own arm
                         * on one platform is not grouped away on the other.
                         *
                         * Full-width pages (no `contentPadding` peek) is the behavioural difference
                         * that matters; the dots are what a reader uses to know how many plans
                         * there are, and they read off the PAGE (#938) rather than assuming a dark
                         * backdrop.
                         */
                        val pagerState = rememberPagerState(
                            initialPage = plans.indexOfFirst { it.id == selectedPlanId }.coerceAtLeast(0),
                            pageCount = { plans.size }
                        )
                        HorizontalPager(
                            state = pagerState,
                            pageSpacing = cardGap,
                        ) { planIdx ->
                            PlanCard(plan = plans[planIdx], planIdx = planIdx, modifier = Modifier.fillMaxWidth())
                        }
                        if (plans.size > 1) {
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                repeat(plans.size) { idx ->
                                    Box(
                                        modifier = Modifier
                                            .padding(horizontal = 3.dp)
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (idx == pagerState.currentPage) planInk else planSubtleInk),
                                    )
                                }
                            }
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
                        /*
                         * #946 — BOTH halves, read off what they are actually drawn on.
                         *
                         * The inactive half's border and label were white, and on a `#FFFFFF` page
                         * the capture showed one segment and an empty rectangle where the author's
                         * second plan should be.
                         *
                         * 🔴 The ACTIVE half was white too, and white was wrong there as well —
                         * `segActiveBg` is the brand accent at 20%, which over a light page is a
                         * pale lavender, not a solid fill. The first fixed capture showed "Annual"
                         * correctly and "Monthly" still legible only by its antialiasing. So the
                         * active label is read off the composited ACTIVE TILE rather than off the
                         * page, which gives white on a dark paywall and ink on a light one.
                         */
                        val segInactiveBorder = unselectedBorderColor ?: planSubtleInk.copy(alpha = 0.2f)
                        val segActiveBgHex = section.data?.selected_bg_color
                        val segActiveText = selectedTextColor ?: PaywallTextDefaults.readable(
                            PaywallTextDefaults.composite(
                                segActiveBgHex ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"),
                                config.pageBackgroundHex(),
                                // An authored fill is opaque; the default is the accent at 20%.
                                if (segActiveBgHex != null) 1.0 else 0.2,
                            ),
                        )
                        val segInactiveText = planInk.copy(alpha = 0.7f)
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
                                    (sp.displayPeriod?.let { " / ${loc("plan.$selectedIdx.period", it)}" } ?: ""),
                                style = priceStyle,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    "pill_selector" -> {
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
                                        /*
                                         * #938 — the unselected pill, READ OFF THE PAGE.
                                         *
                                         * Both of these were white: the tile at 10% and the border
                                         * at 30%, which over a WHITE page is a pill that is not
                                         * there at all — no fill, no outline, just its label. The
                                         * vertical_stack tile already composites (#926/#891) and
                                         * this arm is a separate implementation that did not, the
                                         * same split that hid #905's borders and #913's period.
                                         *
                                         * On a dark page both are byte-identical to what they were.
                                         */
                                        .background(
                                            if (isSelected) ai.appdna.sdk.AppDNA.brandAccentColor()
                                            else (unselectedTileHex?.let { StyleEngine.parseColor(it) }
                                                ?: Color.White.copy(alpha = 0.1f)),
                                            cardShape,
                                        )
                                        .border(
                                            if (isSelected) 0.dp else 1.dp,
                                            if (isSelected) Color.Transparent
                                            else PaywallTextDefaults.subtleOnPage(config.pageBackgroundHex()),
                                            cardShape,
                                        )
                                        .clickable { onPlanSelect(plan.id) }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    /*
                                     * 🔴 #938/#891 — the label, read off the PILL rather than
                                     * assumed. `planNameStyle` and `priceStyle` default to white,
                                     * which is right on the accent-filled selected pill and wrong
                                     * on the unselected one the moment the page is light: the first
                                     * screenshot after the tile became visible showed "Annual BBB"
                                     * as white on near-white, legible only by its antialiasing.
                                     * The selected pill keeps white, because its fill is the brand
                                     * accent whatever the page is.
                                     */
                                    val pillTextColor =
                                        if (isSelected) (selectedTextColor ?: Color.White)
                                        else PaywallTextDefaults.readable(unselectedTileHex)
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = loc("plan.$planIdx.name", plan.displayName),
                                            style = planNameStyle.copy(fontSize = 14.sp, color = pillTextColor),
                                            modifier = planNameElementModifier,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = loc("plan.$planIdx.price", plan.displayPrice),
                                            style = priceStyle.copy(fontSize = 14.sp, color = pillTextColor),
                                            modifier = priceElementModifier,
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
                                        // #946 — the unselected row's only edge. White at 20%
                                        // over a white page is a plan the author cannot see.
                                        if (isSelected) ai.appdna.sdk.AppDNA.brandAccentColor()
                                        else planSubtleInk.copy(alpha = 0.2f),
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
                                        // #946 — the unselected radio ring, read off the page.
                                        unselectedColor = planInk.copy(alpha = 0.5f),
                                    ),
                                )
                                Spacer(Modifier.width(8.dp))
                                /*
                                 * #946 — this row's text, read off the PAGE.
                                 *
                                 * Both rows draw onto the page: the unselected one is transparent
                                 * and the selected one is the brand accent at 10%, which over a
                                 * light page is still a light surface. `planNameStyle` and
                                 * `priceStyle` default to white, so the measured capture showed
                                 * the selected plan's name and price legible only by their
                                 * antialiasing and the unselected plan's not at all. An authored
                                 * `selected_text_color` still wins on the selected row.
                                 */
                                val rowInk = if (isSelected) (selectedTextColor ?: planInk) else planInk
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = loc("plan.$planIdx.name", plan.displayName), style = planNameStyle.copy(color = rowInk), modifier = planNameElementModifier)
                                    plan.displayPeriod?.let {
                                        Text(
                                            text = loc("plan.$planIdx.period", it),
                                            style = periodStyle.copy(color = rowInk.copy(alpha = 0.7f)),
                                            modifier = periodElementModifier,
                                        )
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(text = loc("plan.$planIdx.price", plan.displayPrice), style = priceStyle.copy(color = rowInk), modifier = priceElementModifier)
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
                            /*
                             * #966 — the text follows the tile, on BOTH states.
                             *
                             * 🔴 MEASURED, NOT READ. Recorded on a `#FFFFFF` page, this arm drew
                             * `Monthly` and `$9.99` in WHITE on a near-white card and the second
                             * plan was not on the screen at all — `planNameStyle` defaults to
                             * `color = Color.White` and nothing here overrode it, while the
                             * unselected fill was `Color.White.copy(alpha = 0.06f)`.
                             *
                             * `vertical_stack` solved exactly this and accordion never got the
                             * same treatment, which is what a capture per arm is for: the golden
                             * for this style did not exist until #966.
                             */
                            val accTileHex = if (isSelected) selectedTileHex else unselectedTileHex
                            val accTextColor = (if (isSelected) selectedTextColor else null)
                                ?: PaywallTextDefaults.readable(accTileHex)
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
                                    // Selected card BACKGROUND must use the authored
                                    // selected_bg_color (e.g. #2c374c) so it's visually DISTINCT
                                    // from the unselected card. Do NOT default it to the brand accent:
                                    // a light brand (e.g. white) collapses onto the white-10% unselected
                                    // fill and the selection becomes invisible. Brand drives foreground
                                    // accents (border/badge/check), not the card fill.
                                    containerColor = if (isSelected) (customSelectedBg ?: StyleEngine.parseColor(selectedTileHex ?: "#FFFFFF")) else (unselectedBgColor ?: StyleEngine.parseColor(unselectedTileHex ?: "#FFFFFF"))
                                ),
                            ) {
                                Column(modifier = Modifier.padding(cardPad)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(text = loc("plan.$planIdx.name", plan.displayName), style = planNameStyle, color = accTextColor, modifier = planNameElementModifier)
                                        Text(text = loc("plan.$planIdx.price", plan.displayPrice), style = priceStyle, color = accTextColor, modifier = priceElementModifier)
                                    }
                                    if (isSelected) {
                                        Spacer(Modifier.height(8.dp))
                                        plan.displayPeriod?.let {
                                        Text(
                                            text = loc("plan.$planIdx.period", it),
                                            style = periodStyle, color = accTextColor.copy(alpha = 0.7f),
                                            modifier = periodElementModifier,
                                        )
                                    }
                                        plan.badge?.let {
                                            Spacer(Modifier.height(4.dp))
                                            BadgeView(loc("plan.$planIdx.badge", it))
                                        }
                                        plan.trialLabel?.let {
                                            Spacer(Modifier.height(4.dp))
                                            Text(text = it, style = periodStyle, color = accTextColor.copy(alpha = 0.7f))
                                        }
                                        // Render expanded body
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
                                                style = periodStyle, color = accTextColor.copy(alpha = 0.7f),
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
                                                            style = periodStyle, color = accTextColor.copy(alpha = 0.7f),
                                                            modifier = planFeatureElementModifier,
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
                    // 5 plan
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
                    "tab_groups" -> {
                        /*
                         * #960 — a tab bar, one tab per plan, with the SELECTED plan's full card
                         * beneath it. The console has offered this style in its picker since it
                         * shipped and neither renderer had an arm for it, so an author picked
                         * "Tab groups", saw the console preview draw it, published, and every
                         * device silently drew the default vertical stack instead.
                         *
                         * The shape is the console preview's (`PaywallPreview.tsx`, "6. Tab
                         * Groups"), which was the only specification that existed: equal-width
                         * tabs over a full-width rule, the selected tab's segment of that rule in
                         * the accent colour, and one plan card below.
                         *
                         * 🔴 The rule is drawn as a per-tab underline rather than a row-wide border
                         * with an offset child. Both look identical; this one cannot drift out of
                         * alignment when a label wraps, and it needs no `drawBehind` arithmetic to
                         * stay centred under its tab.
                         */
                        val selectedIdx = plans.indexOfFirst { it.id == selectedPlanId }
                            .coerceAtLeast(0)
                        val selectedPlan = plans.getOrNull(selectedIdx)
                        val tabAccent = customSelectedBorder ?: ai.appdna.sdk.AppDNA.brandAccentColor()
                        // #946 — read off the PAGE, not a constant. A 20%-ink rule is visible on a
                        // dark paywall and on a light one; `Color.White` would be invisible on the
                        // second, which is the defect that sweep existed for.
                        val tabRule = unselectedBorderColor ?: planSubtleInk.copy(alpha = 0.2f)
                        val tabInactiveInk = planInk.copy(alpha = 0.7f)
                        Row(modifier = Modifier.fillMaxWidth()) {
                            plans.forEachIndexed { planIdx, plan ->
                                val isSelected = plan.id == selectedPlan?.id
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onPlanSelect(plan.id) },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        text = loc("plan.$planIdx.name", plan.displayName),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSelected) tabAccent else tabInactiveInk,
                                        maxLines = 1,
                                        // Same reason as every other label here: Clip cuts a long
                                        // plan name mid-glyph with no sign anything is missing.
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(bottom = 8.dp),
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(2.dp)
                                            .background(if (isSelected) tabAccent else tabRule),
                                    )
                                }
                            }
                        }
                        selectedPlan?.let { sp ->
                            Spacer(Modifier.height(12.dp))
                            PlanCard(
                                plan = sp,
                                planIdx = selectedIdx,
                                modifier = Modifier.fillMaxWidth(),
                            )
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
                                    // #961 — was `Color.White.copy(alpha = 0.85f)`. On a light
                                    // paywall this is the only way to reach the other plans, and
                                    // it was invisible: the flow looked like it had one plan.
                                    // `readable` returns the old white on any dark page.
                                    color = planInk.copy(alpha = 0.85f),
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
                                    // iOS
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
                                /*
                                 * #961 — EVERY row is accent-filled, matching iOS
                                 * (`PaywallRenderer.swift` `case "product_as_cta"`, which fills
                                 * unconditionally with `cardStyle.selectedBorderColor ?? accent`).
                                 *
                                 * 🔴 This was two defects in one expression. The `else` branch drew
                                 * a 10%-white tile carrying 90%-white text — invisible on invisible
                                 * on a light page, so the alternative plans were simply not there.
                                 * And it was drawing a SELECTION state that this layout's own
                                 * interaction makes meaningless: `onClick` above does
                                 * `onPlanSelect` AND `onCTATap` in one gesture, so tapping a row
                                 * buys it. There is no select-then-confirm step for a highlight to
                                 * belong to, and a ghosted row that is really a one-tap purchase
                                 * button reads as disabled.
                                 *
                                 * White content is correct here precisely BECAUSE the fill is now
                                 * always the accent — which is why this arm needs no
                                 * `PaywallTextDefaults.readable(on: pageBackgroundHex)` treatment
                                 * the way its `single_hero` and `timeline_reveal` siblings did: it
                                 * never draws on the page.
                                 *
                                 * `isSelected` is still read by `planSelectionAnimation` below, so
                                 * the tapped row animates.
                                 */
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ai.appdna.sdk.AppDNA.brandAccentColor(),
                                    contentColor = Color.White,
                                ),
                                contentPadding = PaddingValues(horizontal = cardPad, vertical = cardPad),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = loc("plan.$planIdx.name", plan.displayName),
                                        style = planNameStyle,
                                        modifier = planNameElementModifier,
                                    )
                                    Text(
                                        text = loc("plan.$planIdx.price", plan.displayPrice),
                                        style = priceStyle.copy(fontSize = 18.sp),
                                        modifier = priceElementModifier,
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
                                            // #961 — the unselected dot was `Color.White` at 40%,
                                            // so on a light page the timeline lost every stop but
                                            // the current one.
                                            if (isSelected) ai.appdna.sdk.AppDNA.brandAccentColor() else planSubtleInk.copy(alpha = 0.4f),
                                        ),
                                )
                                Spacer(Modifier.width(12.dp))
                                PlanCard(plan = plan, planIdx = planIdx, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    /*
                     * #962 — `minimal_chips` and `toggle_cards` USED TO BE RENDERED HERE and iOS
                     * never had either, so the same document drew a pill selector / toggle cards on
                     * one platform and a plain vertical stack on the other. (`minimal_chips` was an
                     * ALIAS on the `pill_selector` arm; `toggle_cards` had eleven lines of its own.)
                     *
                     * 🔴 Removed rather than ported, because neither value exists anywhere in the
                     * platform that could produce it: `grep -rn "minimal_chips\|toggle_cards" src/`
                     * returns NOTHING — not the Console's plan_display_style picker (13 values,
                     * neither among them), not the paywall schema, not the console preview, not the
                     * generator, not a prompt. They could only ever arrive by hand-editing a
                     * document, and a style the SDK renders that the Console cannot set is the
                     * dead-end Rule 8 exists to forbid. Both now fall to the shared vertical stack,
                     * which is exactly what iOS already did — so the two platforms agree.
                     *
                     * Re-adding either means a Console control AND both renderers AND a fixture, in
                     * one change (Rule 8). The old Compose is in git history behind this commit.
                     */
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
                            /*
                             * #926 — GRAY, not white, and iOS fixed this same line already: its
                             * comment reads "(was white.opacity(0.15) — invisible on light paywall
                             * backgrounds)". White at 30% over a white page is nothing, and once
                             * the tile composites over that page there is no fill to see either —
                             * the first device frame after this change showed the Monthly plan as
                             * bare text with no card at all. Gray at 30% reads on both.
                             */
                            val unselBorder = unselectedBorderColor ?: Color.Gray.copy(alpha = 0.3f)
                            val radioColor = if (isSelected) (selectedTextColor ?: selBorder) else unselBorder
                            /*
                             * #926 — THE TILE AND ITS TEXT MOVE TOGETHER, or one of them is wrong.
                             *
                             * This arm is the `vertical_stack` default and a SEPARATE implementation
                             * from `fun PlanCard` — the same split that hid #905's borders and
                             * #913's period. Its unselected tile was `Color(0xFF1B2540)`, a navy
                             * constant, and its text was `Color.Unspecified`, which inherits the
                             * activity's DARK-themed `onSurface` and so came out light. Both
                             * assumptions were about a backdrop nobody authored.
                             *
                             * Composited over the page, the tile follows the document — which means
                             * the text can no longer be left to the theme: on a white page a white
                             * label on a near-white tile is exactly the 1:1 defect #909 fixed for
                             * the selected tile. So the colour is DERIVED from the tile the user
                             * will actually see, authored or composited.
                             */
                            // #966 — BOTH branches derive from the tile the user will see. The
                            // selected one used to fall to `Color.Unspecified`, i.e. the Activity's
                            // dark theme, which is why the selected plan's name and price vanished
                            // on a white page while its unselected sibling rendered correctly.
                            val planTextColor = if (isSelected) {
                                selectedTextColor ?: PaywallTextDefaults.readable(selectedTileHex)
                            } else {
                                unselectedTileHex?.let { PaywallTextDefaults.readable(it) } ?: Color.Unspecified
                            }
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
                                        // Authored selected_bg_color (e.g. #2c374c) wins;
                                        // Unselected card must be OPAQUE + uniform. A
                                        // translucent white-10% let the navy PAGE GRADIENT bleed
                                        // through, giving a "strange gradient inside the card". Derive
                                        // an opaque subtle tone from the authored selected color (darker)
                                        // so it stays theme-aware + distinct; fall back to a subtle navy.
                                        containerColor = if (isSelected) (customSelectedBg ?: StyleEngine.parseColor(selectedTileHex ?: "#FFFFFF"))
                                            else (unselectedBgColor
                                                // #926 — the page's own 10% wash, opaque, so this
                                                // tile matches iOS instead of a navy constant.
                                                ?: unselectedTileHex?.let { StyleEngine.parseColor(it) }
                                                ?: customSelectedBg?.let { androidx.compose.ui.graphics.lerp(it, Color.Black, 0.40f) }
                                                ?: Color(0xFF1B2540))
                                    )
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                    // Plan image, full-width at the card top (iOS
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
                                            // subtitle/description text color falls back to a
                                            // muted grey (iOS PlanCard.swift secondary); accent for trial.
                                            val subtitleColor = planTextColor.takeIf { it != Color.Unspecified } ?: Color.Gray
                                            val trialColor = planTextColor.takeIf { it != Color.Unspecified } ?: ai.appdna.sdk.AppDNA.brandAccentColor()
                                            Text(
                                                text = loc("plan.$planIdx.name", plan.displayName),
                                                style = planNameStyle,
                                                color = planTextColor,
                                                modifier = planNameElementModifier,
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            // Subtitle ABOVE price (iOS PlanCard.swift:105).
                                            if (showPlanSubtitles && subtitlePosition == "above_price" && !plan.description.isNullOrBlank()) {
                                                run {
                                                    // Pill when the product authored one.
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
                                            // Authored strikethrough size/gap + the
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
                                                    Row(verticalAlignment = Alignment.Bottom) {
                                                        Text(
                                                            text = loc("plan.$planIdx.price", plan.displayPrice),
                                                            style = priceStyle,
                                                            color = planTextColor,
                                                            modifier = priceElementModifier,
                                                        )
                                                        Spacer(Modifier.width(2.dp))
                                                    // #913 — the period, beside the price. This
                                                    // `else` arm is the DEFAULT vertical_stack
                                                    // layout and a separate implementation from
                                                    // `fun PlanCard`; wiring only the latter is how
                                                    // #905 and #910 each reached a device with a
                                                    // field that drew nowhere.
                                                    plan.displayPeriod?.takeIf { it.isNotBlank() }?.let {
                                                        Text(
                                                            text = loc("plan.$planIdx.period", it),
                                                            // #913 — the period follows the TILE, like the price does.
                                                            // `periodStyle`'s own default is white at 70%, which assumes a
                                                            // dark tile; on the selected tile — light, once #909 aligned it —
                                                            // that is white-on-white. Measured on a device: the period was
                                                            // absent from the selected tile while rendering fine on the
                                                            // unselected one beside it. Mirrors iOS `.opacity(0.7)`.
                                                            color = if (planTextColor != Color.Unspecified) {
                                                                planTextColor.copy(alpha = 0.7f)
                                                            } else {
                                                                Color.Unspecified
                                                            },
                                                            style = periodStyle,
                                                            modifier = periodElementModifier,
                                                        )
                                                    }
                                                    }
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
                                                    Text(
                                                        text = loc("plan.$planIdx.price", plan.displayPrice),
                                                        style = priceStyle,
                                                        color = planTextColor,
                                                        modifier = priceElementModifier,
                                                    )
                                                    // #913 — the period, beside the price. This
                                                    // `else` arm is the DEFAULT vertical_stack
                                                    // layout and a separate implementation from
                                                    // `fun PlanCard`; wiring only the latter is how
                                                    // #905 and #910 each reached a device with a
                                                    // field that drew nowhere.
                                                    plan.displayPeriod?.takeIf { it.isNotBlank() }?.let {
                                                        Text(
                                                            text = loc("plan.$planIdx.period", it),
                                                            // #913 — the period follows the TILE, like the price does.
                                                            // `periodStyle`'s own default is white at 70%, which assumes a
                                                            // dark tile; on the selected tile — light, once #909 aligned it —
                                                            // that is white-on-white. Measured on a device: the period was
                                                            // absent from the selected tile while rendering fine on the
                                                            // unselected one beside it. Mirrors iOS `.opacity(0.7)`.
                                                            color = if (planTextColor != Color.Unspecified) {
                                                                planTextColor.copy(alpha = 0.7f)
                                                            } else {
                                                                Color.Unspecified
                                                            },
                                                            style = periodStyle,
                                                            modifier = periodElementModifier,
                                                        )
                                                    }
                                                }
                                            }
                                            // PARITY FIX: the default vertical_stack path
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
                                                    // Pill when the product authored one.
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
                                                        Text(
                                                            text = loc("plan.$planIdx.feature.$fIdx", feature),
                                                            style = StyleEngine.applyTextStyle(
                                                                TextStyle(fontSize = 13.sp, color = planTextColor),
                                                                planFeatureTextStyle,
                                                            ),
                                                            modifier = planFeatureElementModifier,
                                                        )
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
                                        // Plan icon, trailing before the radio (iOS
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
                // Restore button moved to the
                // CTA section under `cta.show_restore` (PW-10). Previously this
                // hardcoded button rendered unconditionally below `plans`, so
                // restore appeared TWICE when CTA show_restore was true and
                // appeared even when authors disabled it. Now the CTA section
                // is the single source of truth, matching iOS.
            }
        }
        "cta" -> {
            // Full CTA section with optional
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
            // Guard each step with isNotBlank(). An empty-string
            // bg_color hit StyleEngine.parseColor("") → Color.Transparent, which
            // is non-null and SHORT-CIRCUITED the chain (CTA went transparent =
            // dark bg showing through = "plain black"). iOS `??` over a nil falls
            // through to the brand accent; mirror that by treating "" as absent.
            // iOS PaywallConfig.swift:511 reads `styleObj?.bg_color` FIRST:
            // the console writes `cta: { style: { bg_color, text_color, corner_radius } }`,
            // a NESTED object. Android's PaywallCTA.style is a raw `Any?` that was never
            // read, so an authored `cta.style.bg_color = #ffffff` (white button) was dropped
            // and the CTA fell through to the brand accent / transparent = "plain black".
            // Resolve to null (NOT Transparent) for blank/invalid/
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
            // Capture whether an EXPLICIT solid CTA bg_color was authored
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
                // Compose Offset is in PIXELS, not 0..1 fractions. Passing
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
            // #910 — the restore link's DEFAULT colour was `Color.White.copy(alpha = 0.6f)`, a
            // colour that assumes a dark paywall. On the light one this walk published it drew
            // white-on-white: the link was in the view tree, had its authored border, and could be
            // tapped — and was invisible. iOS has always used `.secondary`, which is grey in light
            // appearance, so one published document gave two different screens.
            //
            // Exactly #891's defect in a different control, so it takes #891's answer: the shared
            // WCAG ladder, which the console and both SDKs agree on. 0.6 alpha is kept so an
            // authored-colour-free restore link still reads as secondary to the CTA.
            val restoreColor = section.data?.restore_text_color?.let { parseHexColor(it) }
                ?: PaywallTextDefaults.readable(config.pageBackgroundHex()).copy(alpha = 0.6f)
            val restoreFontSize = (section.data?.restore_font_size ?: 13f).sp
            // The CTA↔Restore gap. Unset keeps the previous hardcoded 8.dp.
            val restoreGap = (section.data?.restore_gap ?: 8f).dp
            // Mirrors iOS `performButtonAction`. Without this Android would
            // parse cta_action/restore_action and ignore them, which is exactly the iOS/Android
            // divergence this wave keeps finding. check:authorability caught it as a dead control.
            val ctaCtx = androidx.compose.ui.platform.LocalContext.current
            fun performButtonAction(action: String?, url: String?, default: String) {
                when (action ?: default) {
                    "restore" -> onRestore()
                    "dismiss" -> onDismiss()
                    "link" -> {
                        onAction(PaywallAction.LINK_TAPPED)   // #1076
                        url?.let { ai.appdna.sdk.core.URLSafety.open(ctaCtx, it) }
                    }
                    "purchase" -> onCTATap()
                    // #1076 — an unrecognised authored action still purchases, as every
                    // unauthored CTA always has, and the host is told it was a custom one.
                    else -> {
                        onAction(PaywallAction.CUSTOM)
                        onCTATap()
                    }
                }
            }

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
                // #651 — Restore could be recoloured and resized but never given a FILL, so it
                // could not be made to look like a second button next to the CTA. Unset renders the
                // plain centred link exactly as before; the clip/background go on before the
                // clickable so the ripple follows the authored shape.
                val restoreBg = section.data?.restore_bg_color
                    ?.takeIf { it.isNotEmpty() }?.let { parseHexColor(it) }
                val restoreRadius = (section.data?.restore_corner_radius ?: 0f).dp
                // #910 — the `restore_text` element, both halves. iOS has honoured its text style
                // since the element existed and Android read NEITHER half, so a restore link
                // styled in the console looked right on one platform and plain on the other.
                val restoreElementStyle = section.style?.elements?.get("restore_text")
                Text(
                    text = loc("cta.restore_text", restoreText),
                    style = StyleEngine.applyTextStyle(
                        TextStyle(
                            color = restoreColor,
                            fontSize = restoreFontSize,
                            textAlign = TextAlign.Center,
                        ),
                        restoreElementStyle?.text_style,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .run {
                            with(StyleEngine) { applyContainerStyle(restoreElementStyle?.elementContainer) }
                        }
                        .then(
                            if (restoreBg != null) {
                                Modifier.clip(RoundedCornerShape(restoreRadius)).background(restoreBg)
                            } else {
                                Modifier
                            },
                        )
                        // The restore link's authored action; unset restores.
                        .clickable { performButtonAction(section.data?.restore_action, null, "restore") }
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
                // Gate enabled state by both
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
                        // CTA corner_radius default 12 matches iOS
                        // PaywallCTA.resolvedCornerRadius at PaywallConfig.swift:515
                        // `styleObj?.corner_radius ?? corner_radius ?? 12.0`. Was 14.
                        // Apply the fill DIRECTLY with the shape (was .clip()+.then()
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
                        // #910 — the `button` ELEMENT's own container, which is a different thing
                        // from the section's.
                        //
                        // 🔴 AFTER `.background(…)`, not before it. Compose draws modifiers in
                        // chain order, so a `.border()` placed earlier is painted OVER by the
                        // button's own background and vanishes — and the device frame showed worse
                        // than that: the whole button went white-on-white, because the `.clip()`
                        // this helper applies landed between the Box's size and its fill. One
                        // authored CTA border, one blank button. Placed here the button paints
                        // itself first and the authored border draws on top, which is also what
                        // iOS does (`CTAButton`: `.applyElementStyle` sits outside the `Button`).
                        //
                        // `withoutBackground`: the fill is already resolved into `buttonBgColor`
                        // above, so re-applying it here would paint it a second time at a
                        // different radius.
                        .run {
                            with(StyleEngine) {
                                applyContainerStyle(section.style?.elements?.get("button")?.withoutBackground)
                            }
                        }
                        .clickable(
                            enabled = ctaEnabled,
                            interactionSource = ctaInteraction,
                            indication = null, // QA-R9 — no purple ripple
                        // The CTA's authored action; unset purchases.
                        ) { performButtonAction(section.data?.cta_action, null, "purchase") },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isPurchasing) {
                        // Mirror iOS CTAButton.swift:58-69 which shows a spinner
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
                            // iOS resolution chain.
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
                // Extra buttons sit between the CTA and the restore link,
                // matching iOS.
                section.data?.extra_buttons?.takeIf { it.isNotEmpty() }?.let { extras ->
                    Spacer(Modifier.height(restoreGap))
                    PaywallExtraButtons(
                        buttons = extras,
                        // `cta.style.corner_radius` is what the console actually writes, parsed
                        // into `config.cta.corner_radius` — the same resolution this file's own
                        // audit-pass-8 comment documents a few lines below. A section-data fallback
                        // was dropped: check:authorability showed no console control writes it, so
                        // it would have been a dead reference. (Deliberately not naming that field
                        // here — the gate scans raw source, so a comment mentioning it recreates
                        // the finding it explains.)
                        cornerRadius = config.cta?.corner_radius?.toFloat() ?: 12f,
                        onCTATap = onCTATap,
                        onRestore = onRestore,
                        onDismiss = onDismiss,
                        loc = loc,
                    )
                }
                if (showRestoreLink && restorePosition != "above") {
                    Spacer(Modifier.height(restoreGap))
                    RestoreLink()
                }
            }
        }
        "social_proof" -> {
            // Social proof sub-types
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
                            showDays = section.data?.show_days,
                            showHours = section.data?.show_hours,
                            showMinutes = section.data?.show_minutes,
                            showSeconds = section.data?.show_seconds,
                            onExpireAction = section.data?.on_expire_action,
                            expiredText = section.data?.expired_text?.let { loc("countdown.expired_text", it) },
                            onExpire = onDismiss,
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
                        /*
                         * 🔴 #947's defect, on the platform #947 did not touch.
                         *
                         * This was `Color.White.copy(alpha = 0.7f)` — white at 70% over a WHITE
                         * page is nothing, so the review count and the quote simply were not there
                         * on any light paywall. iOS retired the same constant on its side when
                         * #947 measured 1,557 pixels changing between appearances; Android kept
                         * it, so the two platforms disagreed about a line both of them draw.
                         *
                         * On a dark page this is byte-identical to the old constant — `readable`
                         * returns `#FFFFFF` for any page dark enough for white to pass AA — which
                         * is what makes it safe: it moves pixels only where the old value drew
                         * nothing. The same reasoning `subtleOnPage` is documented with.
                         */
                        val socialProofSecondary =
                            PaywallTextDefaults.readable(LocalPaywallPageHex.current).copy(alpha = 0.7f)
                        val socialValueStyle = StyleEngine.applyTextStyle(
                            TextStyle(color = socialProofSecondary, fontSize = 13.sp),
                            section.style?.elements?.get("value")?.text_style
                        )
                        // #910 — the `value` element's container, beside the text style it
                        // already honoured (iOS SocialProof.swift).
                        val socialValueModifier = Modifier.run {
                            with(StyleEngine) {
                                applyContainerStyle(section.style?.elements?.get("value")?.elementContainer)
                            }
                        }
                        /*
                         * #954 — the caption, above the stars.
                         *
                         * `data.text` decoded, travelled and was drawn by the `countdown` and
                         * `trial_badge` branches and by NOTHING in this one, so an author could
                         * write "Loved by 12,000 runners", see it in the console preview, publish
                         * it, and have no device draw it. One saved section on dev carried `text`
                         * and nothing else and rendered blank for exactly this reason. Above the
                         * stars because that is the reading order of the pattern, and where the
                         * countdown branch puts its own label (iOS SocialProof.swift).
                         */
                        section.data?.text?.takeIf { it.isNotBlank() }?.let { caption ->
                            Text(
                                text = loc("social_proof.text", caption),
                                style = StyleEngine.applyTextStyle(
                                    TextStyle(fontSize = 12.sp, color = socialProofSecondary),
                                    section.style?.elements?.get("text")?.text_style,
                                ),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.run {
                                    with(StyleEngine) {
                                        applyContainerStyle(section.style?.elements?.get("text")?.elementContainer)
                                    }
                                },
                            )
                        }
                        section.data?.rating?.let { rating ->
                            // \u2014 render 5 fixed stars
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
                                // \u2014 compact count
                                // formatter (e.g. 1245 \u2192 "1.2K") matching iOS
                                // formatCount; was the literal string
                                // "$rating from $count reviews".
                                val compact = formatCompactCount(count)
                                Text(
                                    text = loc("social_proof.review_text", "($compact)"),
                                    style = socialValueStyle,
                                    modifier = socialValueModifier,
                                )
                            }
                        }
                        section.data?.testimonial?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "\"${loc("social_proof.testimonial", it)}\"",
                                /*
                                 * #954 — the QUOTE's own element, not the review count's.
                                 *
                                 * It shared `value` with the count, so an author who restyled one
                                 * restyled both and neither could be set alone. iOS gave the quote
                                 * `elements["testimonial"]` in the same change, and
                                 * `paywall-element-style-every-site` caught the half-done version
                                 * immediately — "an element styled on one platform and not the
                                 * other is the divergence class this repo keeps shipping".
                                 */
                                style = StyleEngine.applyTextStyle(
                                    TextStyle(fontSize = 13.sp, color = socialProofSecondary),
                                    section.style?.elements?.get("testimonial")?.text_style,
                                ),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.run {
                                    with(StyleEngine) {
                                        applyContainerStyle(
                                            section.style?.elements?.get("testimonial")?.elementContainer,
                                        )
                                    }
                                },
                            )
                            /*
                             * #955 — the attribution, which the generator writes and this section
                             * could not draw. 12 of 22 saved sections on dev carry an `author`,
                             * because the paywall prompt asked for one; it decoded as `author_name`
                             * and reached no renderer. The `testimonial` SECTION has drawn it all
                             * along, which is what makes this a gap rather than a feature: the same
                             * content rendered two ways depending on which section type an author
                             * happened to pick.
                             */
                            section.data.author_name?.takeIf { n -> n.isNotBlank() }?.let { author ->
                                Text(
                                    text = "\u2014 ${loc("social_proof.author_name", author)}",
                                    style = StyleEngine.applyTextStyle(
                                        TextStyle(fontSize = 12.sp, color = socialProofSecondary.copy(alpha = 0.75f)),
                                        section.style?.elements?.get("author_name")?.text_style,
                                    ),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.run {
                                        with(StyleEngine) {
                                            applyContainerStyle(
                                                section.style?.elements?.get("author_name")?.elementContainer,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        "guarantee" -> {
            // Full 4-element VStack matching
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
                        // #913 — the guarantee section's own `title` container.
                        Text(
                            text = loc("guarantee.title", title),
                            style = titleStyle,
                            modifier = Modifier.run {
                                with(StyleEngine) {
                                    applyContainerStyle(section.style?.elements?.get("title")?.elementContainer)
                                }
                            },
                        )
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
                        // The same `text` ?: `description` chain the style above uses, so the two
                        // halves of one element resolve from the same key.
                        Text(
                            text = loc("guarantee.description", it),
                            style = descStyle,
                            modifier = Modifier.run {
                                with(StyleEngine) {
                                    applyContainerStyle(
                                        section.style?.elements?.get("text")?.elementContainer
                                            ?: section.style?.elements?.get("description")?.elementContainer,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
        // Missing sections
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
        // Lottie section
        "lottie" -> {
            section.data?.lottie_url?.let { url ->
                LottieBlockView(
                    block = LottieBlock(
                        lottie_url = url,
                        loop = section.data.lottie_loop ?: true,
                        speed = section.data.lottie_speed ?: 1.0f,
                        // Prefer the
                        // type-specific lottie_height over the generic
                        // height (mirrors iOS PaywallConfig.swift:133).
                        height = section.data.lottie_height ?: section.data.height ?: 200f,
                    )
                )
            }
        }
        // Rive section
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
        // Also handle
        // `video_background` so console-published full-bleed background-video
        // sections render. iOS PaywallRenderer.swift:549 dispatches both
        // strings to the same renderer.
        "video", "video_background" -> {
            section.data?.video_url?.let { url ->
                CoreVideoBlockView(
                    block = CoreVideoBlock(
                        video_url = url,
                        video_thumbnail_url = section.data.video_thumbnail_url ?: section.data.image_url,
                        // Prefer the
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
            // \u2014 honor `data.layout` (quote / card /
            // minimal / default) matching iOS PaywallRenderer.swift:715-799.
            // Was hardcoded "quote" layout regardless of console value.
            val testimonialLayout = section.data?.layout ?: "quote"
            /*
             * #961 follow-up — the testimonial section's ink reads the PAGE, not the phone.
             *
             * These three were `Color.White`, `Color.White.copy(alpha = 0.9f)` and
             * `Color.White.copy(alpha = 0.7f)`: a white quote with a white attribution on a light
             * paywall, which is the identical defect #946/#947/#966 swept out of the plan-display
             * arms. The section was missed because it is not a plan arm — the sweep was scoped to
             * `plan_display_style`, and this renders above them.
             *
             * iOS has read the page here all along
             * (`PaywallRenderer.swift:1083`: `PaywallTextDefaults.readable(on: pageBackgroundHex)
             * .opacity(0.7)`), so this is Android catching up rather than a new rule.
             *
             * Byte-identical on any dark page, where `readable` returns `#FFFFFF`.
             */
            val testimonialInk = PaywallTextDefaults.readable(config.pageBackgroundHex())
            val quoteStyle = StyleEngine.applyTextStyle(
                TextStyle(
                    color = testimonialInk.copy(alpha = 0.9f),
                    fontSize = if (testimonialLayout == "minimal") 12.sp else 14.sp,
                    textAlign = TextAlign.Center,
                ),
                section.style?.elements?.get("quote")?.text_style
            )
            val authorNameStyle = StyleEngine.applyTextStyle(
                TextStyle(fontWeight = FontWeight.SemiBold, color = testimonialInk, fontSize = 14.sp),
                section.style?.elements?.get("author_name")?.text_style
            )
            val authorRoleStyle = StyleEngine.applyTextStyle(
                TextStyle(color = testimonialInk.copy(alpha = 0.7f), fontSize = 12.sp),
                section.style?.elements?.get("author_role")?.text_style
            )
            // #913 — the three testimonial elements' containers, from the ElementStylePanel the
            // console shows beside each TextStylePanel. Resolved once; the quote is drawn at TWO
            // sites below (the `minimal` layout has its own), and wiring only one is how #905 and
            // #910 each reached a device with a border that drew nowhere.
            val quoteElementModifier = Modifier.run {
                with(StyleEngine) { applyContainerStyle(section.style?.elements?.get("quote")?.elementContainer) }
            }
            val authorNameElementModifier = Modifier.run {
                with(StyleEngine) { applyContainerStyle(section.style?.elements?.get("author_name")?.elementContainer) }
            }
            val authorRoleElementModifier = Modifier.run {
                with(StyleEngine) { applyContainerStyle(section.style?.elements?.get("author_role")?.elementContainer) }
            }
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
                        modifier = quoteElementModifier,
                    )
                } else {
                    Text(
                        text = loc("testimonial.quote", section.data?.quote ?: section.data?.testimonial ?: ""),
                        style = quoteStyle,
                        modifier = quoteElementModifier,
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
                                Text(
                                    loc("testimonial.author_name", it),
                                    style = authorNameStyle,
                                    modifier = authorNameElementModifier,
                                )
                            }
                            section.data?.author_role?.let {
                                Text(
                                    loc("testimonial.author_role", it),
                                    style = authorRoleStyle,
                                    modifier = authorRoleElementModifier,
                                )
                            }
                        }
                    }
                }
            }
        }
        // 12 new paywall section types
        "countdown" -> {
            PaywallCountdownSection(section = section, loc = loc, onDismiss = onDismiss)
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
                // Hoisted state plumbing
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
            PaywallReviewsCarouselSection(
                section = section,
                pageBackgroundHex = config.pageBackgroundHex(),
                loc = loc,
            )
        }
    }
    }
}

// MARK: - Countdown section

@Composable
private fun PaywallCountdownSection(
    section: PaywallSection,
    loc: (String, String) -> String,
    /*
     * #978 — what `on_expire_action: auto_advance` calls. Defaulted to a no-op so the other call
     * sites of this composable are unaffected, and wired to the paywall's OWN dismiss rather than
     * to `finish()`: the Activity keeps a single dismiss path, and a section reaching past it
     * would skip the guard that stops the SDK dismissing twice.
     */
    onDismiss: () -> Unit = {},
) {
    val duration = section.data?.duration_seconds ?: section.data?.countdown_seconds ?: 3600
    // Render label + layout (boxed/banner/
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
                fontWeight = resolveWeight(section.data?.label_font_weight, FontWeight.SemiBold),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        CountdownTimer(
            seconds = duration,
            valueTextStyle = section.style?.elements?.get("value")?.text_style,
            digitFontSize = section.data?.digit_font_size,
            digitFontWeight = section.data?.digit_font_weight,
            digitColorHex = section.data?.text_color,
            showDays = section.data?.show_days,
            showHours = section.data?.show_hours,
            showMinutes = section.data?.show_minutes,
            showSeconds = section.data?.show_seconds,
            onExpireAction = section.data?.on_expire_action,
            expiredText = section.data?.expired_text?.let { loc("countdown.expired_text", it) },
            onExpire = onDismiss,
            // #980 — the Variant the console offers and no renderer read.
            variant = section.data?.variant,
        )
    }
}

// MARK: - Legal section

@Composable
private fun PaywallLegalSection(
    section: PaywallSection,
    loc: (String, String) -> String,
    onRestore: () -> Unit = {},
) {
    // #946 — the readable colour FOR THIS PAGE. Every use below keeps the alpha it had, so a
    // dark paywall draws exactly the pixels it drew before and a light one becomes legible.
    val ink = PaywallTextDefaults.readable(LocalPaywallPageHex.current)
    val textColor = section.data?.color?.let { parseHexColor(it) } ?: ink.copy(alpha = 0.5f)
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
            // Pass the authored link colour through (accent_color, which the
            // console's Link Color now maps onto at the sync boundary).
            val legalLinkColor = section.data?.accent_color?.let { parseHexColor(it) }
            val annotated = buildAnnotatedStringWithLinks(text, legalLinkColor)
            // #910 — the `text` element, which the console's Style tab offers for this section and
            // NEITHER half of which was read on either platform: not the text style, not the
            // container. An author styling the legal paragraph got nothing at all.
            val legalTextElement = section.style?.elements?.get("text")
            androidx.compose.foundation.text.ClickableText(
                text = annotated,
                style = StyleEngine.applyTextStyle(
                    TextStyle(color = textColor, fontSize = fontSize.sp, textAlign = textAlignment),
                    legalTextElement?.text_style,
                ),
                modifier = Modifier.run {
                    with(StyleEngine) { applyContainerStyle(legalTextElement?.elementContainer) }
                },
                onClick = { offset ->
                    annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()?.let { annotation ->
                            // A markdown link in a legal block is still a
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
                        // Route restore-action
                        // links to onRestore callback matching iOS
                        // PaywallRenderer.swift:931-938. Was unconditionally
                        // launching ACTION_VIEW with link.url which for restore
                        // was empty/sentinel — tap was no-op (or crashed) and
                        // hosting users could not restore from the legal block.
                        modifier = Modifier.clickable {
                            if (link.action == "restore") {
                                onRestore()
                            } else if (link.url.isNotBlank()) {
                                // allowlisted. A paywall config that names
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
 * Inline `[label](url)` links in paywall legal text.
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
                // The AUTHORED link colour when there is one. This was
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

// MARK: - Divider section

@Composable
private fun PaywallDividerSection(section: PaywallSection) {
    // Divider default `#E5E7EB` matches iOS
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
                    // Divider label default
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

// MARK: - Card section

@Composable
private fun PaywallCardSection(
    section: PaywallSection,
    loc: (String, String) -> String,
) {
    // #946 — the readable colour FOR THIS PAGE. Every use below keeps the alpha it had, so a
    // dark paywall draws exactly the pixels it drew before and a light one becomes legible.
    val ink = PaywallTextDefaults.readable(LocalPaywallPageHex.current)
    val radius = section.data?.corner_radius ?: 16f
    val bgColor = section.data?.background_color?.let { parseHexColor(it) } ?: ink.copy(alpha = 0.08f)
    val borderClr = section.data?.border_color?.let { parseHexColor(it) } ?: ink.copy(alpha = 0.15f)

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
                    color = ink,
                )
                Spacer(Modifier.height(4.dp))
            }
            section.data?.subtitle?.let {
                Text(
                    text = loc("card.subtitle", it),
                    fontSize = 14.sp,
                    color = ink.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(8.dp))
            }
            section.data?.text?.let {
                Text(
                    text = loc("card.body", it),
                    fontSize = 14.sp,
                    color = ink.copy(alpha = 0.9f),
                )
            }
        }
    }
}

// MARK: - Carousel section

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaywallCarouselSection(
    section: PaywallSection,
    config: PaywallConfig,
    loc: (String, String) -> String,
) {
    val pages = section.data?.pages ?: return
    if (pages.isEmpty()) return
    /*
     * #938/#891 — a carousel page's title and subtitle were hard-coded white, so a carousel on a
     * light paywall showed its heading as nothing. `ink` is the readable colour for THIS page and
     * each use keeps the alpha it had: a dark paywall draws exactly what it drew before.
     */
    val ink = PaywallTextDefaults.readable(config.pageBackgroundHex())
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
                        Text(text = it, fontWeight = FontWeight.Bold, color = ink, fontSize = 16.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                    child.data?.subtitle?.let {
                        Text(text = it, color = ink.copy(alpha = 0.8f), fontSize = 14.sp)
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
                    /*
                     * #938 — an indicator nobody authored, READ OFF THE PAGE. Both of these were
                     * white — the active dot opaque, the rest at 30% — so a carousel on a light
                     * paywall showed no dots at all and the reader had no way to know how many
                     * pages there were. Byte-identical on a dark page.
                     */
                    val activeColor = section.data.indicator_active_color?.let { parseHexColor(it) }
                        ?: PaywallTextDefaults.readable(config.pageBackgroundHex())
                    val inactiveColor = section.data.indicator_color?.let { parseHexColor(it) }
                        ?: PaywallTextDefaults.subtleOnPage(config.pageBackgroundHex())
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

// MARK: - Timeline section

@Composable
private fun PaywallTimelineSection(
    section: PaywallSection,
    loc: (String, String) -> String,
) {
    // #946 — the readable colour FOR THIS PAGE. Every use below keeps the alpha it had, so a
    // dark paywall draws exactly the pixels it drew before and a light one becomes legible.
    val ink = PaywallTextDefaults.readable(LocalPaywallPageHex.current)
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
    // The 30/60/90 "misalignment" reported in device QA is the proportion
    // mismatch \u2014 Android's 24dp circles dominated the layout, while iOS's
    // tiny accent dots act as a subtle visual rail.
    val connectorColor = section.data.line_color?.let { parseHexColor(it) }
        ?: section.style?.elements?.get("connector")?.text_style?.color?.let { parseHexColor(it) }
        ?: ink
    val titleFontSize = (section.data.font_size ?: 14f).sp
    val itemSpacing = if (isCompact) 12.dp else 20.dp

    // iOS centers the timeline block (PaywallRenderer.swift:1267-1340).
    // Wrap in a full-width Box and let the Row be content-sized + centered, instead
    // of a fillMaxWidth Row whose titles were left-pinned via weight(1f).
    Box(
        modifier = Modifier.fillMaxWidth().run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
        contentAlignment = Alignment.Center,
    ) {
        // Per-item Row at IntrinsicSize.Min so the left rail (dot +
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
                            Text(text = it, fontWeight = FontWeight.SemiBold, color = ink, fontSize = titleFontSize)
                        }
                        item.subtitle?.let {
                            Text(text = it, color = ink.copy(alpha = 0.7f), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Icon grid section

@Composable
private fun PaywallIconGridSection(
    section: PaywallSection,
    loc: (String, String) -> String,
) {
    // #946 — the readable colour FOR THIS PAGE. Every use below keeps the alpha it had, so a
    // dark paywall draws exactly the pixels it drew before and a light one becomes legible.
    val ink = PaywallTextDefaults.readable(LocalPaywallPageHex.current)
    val items = section.data?.items ?: return
    val columnCount = section.data.columns ?: 3
    val gridSpacing = section.data.spacing ?: 16f
    val iconSz = section.data.icon_size ?: 32f
    val iconClr = section.data.icon_color?.let { parseHexColor(it) } ?: ai.appdna.sdk.AppDNA.brandAccentColor()

    val titleStyle = section.style?.elements?.get("title")?.text_style
    val descStyle = section.style?.elements?.get("description")?.text_style
    // #913 — and their containers, from the ElementStylePanel beside each TextStylePanel.
    val titleElementModifier = Modifier.run {
        with(StyleEngine) { applyContainerStyle(section.style?.elements?.get("title")?.elementContainer) }
    }
    val descElementModifier = Modifier.run {
        with(StyleEngine) { applyContainerStyle(section.style?.elements?.get("description")?.elementContainer) }
    }

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
                                TextStyle(fontWeight = FontWeight.Medium, color = ink, fontSize = 12.sp, textAlign = TextAlign.Center),
                                titleStyle,
                            ) else TextStyle(fontWeight = FontWeight.Medium, color = ink, fontSize = 12.sp, textAlign = TextAlign.Center)
                            Text(text = it, style = ts, textAlign = TextAlign.Center, modifier = titleElementModifier)
                        }
                        val itemDesc = item.description ?: item.subtitle
                        itemDesc?.let {
                            val ds = if (descStyle != null) StyleEngine.applyTextStyle(
                                TextStyle(color = ink.copy(alpha = 0.6f), fontSize = 10.sp, textAlign = TextAlign.Center),
                                descStyle,
                            ) else TextStyle(color = ink.copy(alpha = 0.6f), fontSize = 10.sp, textAlign = TextAlign.Center)
                            Text(text = it, style = ds, textAlign = TextAlign.Center, modifier = descElementModifier)
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

// MARK: - Comparison table section

@Composable
private fun PaywallComparisonTableSection(
    section: PaywallSection,
    loc: (String, String) -> String,
) {
    val cols = section.data?.table_columns ?: return
    val rows = section.data.rows ?: return
    val checkColor = section.data.check_color?.let { parseHexColor(it) } ?: Color(0xFF22C55E)
    // Defaults match iOS PaywallRenderer.swift:
    // 1397-1399. Cross was bright red `#EF4444`; iOS uses muted gray `#D1D5DB`.
    // Border was 20%-white (invisible on light paywall); iOS uses `#E5E7EB`.
    val crossColor = section.data.cross_color?.let { parseHexColor(it) } ?: parseHexColor("#D1D5DB")
    val highlightClr = section.data.highlight_color?.let { parseHexColor(it) } ?: ai.appdna.sdk.AppDNA.brandAccentColor()
    val borderClr = section.data.border_color?.let { parseHexColor(it) } ?: parseHexColor("#E5E7EB")
    // Theme-adaptive label colors matching iOS
    // `.foregroundColor(.primary)` / `.secondary`. Was hardcoded white,
    // invisible on light-themed paywalls.
    val tablePrimaryText = MaterialTheme.colorScheme.onSurface
    val tableSecondaryText = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    // Header background `#F9FAFB` matching iOS
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
                        // \u2014 accept the same
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

// MARK: - Promo input section

// `internal` (not private) so PromoCodeFallbackTest can drive the no-delegate
// branch directly. That branch used to accept any non-blank code.
@Composable
internal fun PaywallPromoInputSection(
    section: PaywallSection,
    loc: (String, String) -> String,
    onPromoCodeSubmit: ((String, (Boolean) -> Unit) -> Unit)? = null,
    // State hoisted to parent so CTA tap handlers can read
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
            // Filled TextField with #F9FAFB bg +
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
                    // Submit promo code via delegate callback
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

// MARK: - Toggle section

@Composable
private fun PaywallToggleSection(
    section: PaywallSection,
    loc: (String, String) -> String,
    toggleStates: MutableMap<String, Boolean> = mutableMapOf(),
) {
    // #946 — the readable colour FOR THIS PAGE. Every use below keeps the alpha it had, so a
    // dark paywall draws exactly the pixels it drew before and a light one becomes legible.
    val ink = PaywallTextDefaults.readable(LocalPaywallPageHex.current)
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
                    color = section.data.label_color_val?.let { c -> parseHexColor(c) } ?: ink,
                )
            }
            section.data?.description?.let {
                Text(
                    text = loc("toggle.description", it),
                    fontSize = 12.sp,
                    // Default #9CA3AF matches iOS
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

// MARK: - Reviews carousel section

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaywallReviewsCarouselSection(
    section: PaywallSection,
    /**
     * #938 — the page this section is drawn on, so a colour nobody authored can be read off it.
     *
     * The hex rather than the whole `PaywallConfig`: this composable needs one fact about the
     * document and taking all of it would make the dependency look larger than it is. iOS reads
     * the same value from the environment (`\.paywallBackgroundHex`).
     */
    pageBackgroundHex: String?,
    loc: (String, String) -> String,
) {
    val reviews = section.data?.reviews ?: return
    if (reviews.isEmpty()) return
    /*
     * #938/#891 — every colour below was a hard-coded white, so this whole section was legible
     * only on a dark page: the quote, the author, the date, the avatar circle and the pager dots.
     * `ink` is the readable colour FOR THIS PAGE and each use keeps the alpha it had, so a dark
     * paywall draws exactly the pixels it drew before and a light one becomes readable.
     */
    val ink = PaywallTextDefaults.readable(pageBackgroundHex)
    val pagerState = rememberPagerState(pageCount = { reviews.size })
    val autoScroll = section.data.auto_scroll ?: true
    val intervalMs = section.data.auto_scroll_interval_ms ?: 4000
    val showStars = section.data.show_rating_stars ?: true
    val starClr = section.data.star_color?.let { parseHexColor(it) } ?: Color(0xFFFBBF24)
    val textStyleConfig = section.style?.elements?.get("text")?.text_style
    val authorStyleConfig = section.style?.elements?.get("author")?.text_style
    // #910 — the container halves of the same two elements. `card` already had one; the console's
    // ElementStylePanel writes one for `text` and `author` too, and neither was read.
    val quoteElementModifier = Modifier.run {
        with(StyleEngine) { applyContainerStyle(section.style?.elements?.get("text")?.elementContainer) }
    }
    val authorElementModifier = Modifier.run {
        with(StyleEngine) { applyContainerStyle(section.style?.elements?.get("author")?.elementContainer) }
    }

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
                // #910 — the `card` element's container, which iOS has applied to this very view
                // since #905 (`cardStyle: elements["card"].container`) and Android never read.
                // An authored review card drew a border on one platform and nothing on the other;
                // the cross-platform half of the element gate is what found it.
                modifier = Modifier
                    .fillMaxSize()
                    .run {
                        with(StyleEngine) {
                            applyContainerStyle(section.style?.elements?.get("card")?.elementContainer)
                        }
                    }
                    .padding(16.dp),
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
                        TextStyle(color = ink.copy(alpha = 0.9f), fontSize = 14.sp, textAlign = TextAlign.Center),
                        textStyleConfig,
                    )
                } else {
                    TextStyle(color = ink.copy(alpha = 0.9f), fontSize = 14.sp, textAlign = TextAlign.Center)
                }
                Text(
                    text = "\u201C${review.text}\u201D",
                    style = quoteTextStyle,
                    textAlign = TextAlign.Center,
                    modifier = quoteElementModifier,
                )
                Spacer(Modifier.height(12.dp))

                // Author
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Avatar resolution
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
                                    .background(ink.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center,
                            ) { Text(text = avatarEmoji, fontSize = 16.sp) }
                        }
                        review.author.isNotBlank() -> {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(ink.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = review.author.first().uppercaseChar().toString(),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ink,
                                )
                            }
                        }
                    }
                    val authorStyle = if (authorStyleConfig != null) {
                        StyleEngine.applyTextStyle(
                            TextStyle(fontWeight = FontWeight.Medium, color = ink.copy(alpha = 0.7f), fontSize = 12.sp),
                            authorStyleConfig,
                        )
                    } else {
                        TextStyle(fontWeight = FontWeight.Medium, color = ink.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                    Text(text = review.author, style = authorStyle, modifier = authorElementModifier)
                    review.date?.let {
                        Text(text = it, fontSize = 10.sp, color = ink.copy(alpha = 0.4f))
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
                        // #938 — same page-derived default as the carousel indicators above: a
                        // white dot over a white page is one dot's worth of nothing.
                        .background(
                            if (idx == pagerState.currentPage) ink
                            else PaywallTextDefaults.subtleOnPage(pageBackgroundHex),
                        ),
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

// Countdown timer for social proof
/*
 * #978 — the six fields that were decoded here and read by NOTHING.
 *
 * A timer reached zero and sat at `00:00:00`, which is the one outcome none of
 * `on_expire_action`'s three values describes. They were in `PaywallSectionData`, so every reader
 * believed they worked and the paywall generator could author them into real documents.
 *
 * `showDays` and its siblings are a FORMAT choice: with days shown a 3-day countdown reads
 * "02d 23h 59m" rather than "71h 59m". All four unset means the historical h:m:s, so no published
 * paywall changes. Mirrors iOS `CountdownTimerView`.
 */
@Composable
private fun CountdownTimer(
    seconds: Int,
    valueTextStyle: ai.appdna.sdk.core.TextStyleConfig? = null,
    /*
     * #979 — the digits' own three console controls. Unset, they keep exactly the values this
     * composable hard-coded (22sp, Bold, the page-readable ink), so a countdown nobody has styled
     * draws the pixels it drew before. `valueTextStyle` still applies ON TOP: it is the
     * design-system channel and the more specific of the two.
     */
    digitFontSize: Float? = null,
    digitFontWeight: String? = null,
    digitColorHex: String? = null,
    showDays: Boolean? = null,
    showHours: Boolean? = null,
    showMinutes: Boolean? = null,
    showSeconds: Boolean? = null,
    onExpireAction: String? = null,
    expiredText: String? = null,
    onExpire: (() -> Unit)? = null,
    /*
     * #980 — `digital` | `circular` | `flip` | `bar`. The console's Variant select offered all four,
     * `CountdownConfigSchema` persisted all four, the generator wrote one on every countdown it
     * produced, and NO renderer on any platform read it: every paywall countdown drew `digital`.
     *
     * The four designs are ported from the ONBOARDING countdown block, which has implemented them
     * on both platforms all along — so the same authored value now looks the same on a paywall as
     * it does in a flow, rather than two surfaces inventing two looks.
     *
     * Unset is `digital`, which is what every published paywall drew, so nothing restyles.
     */
    variant: String? = null,
) {
    // #946 — the readable colour FOR THIS PAGE. Every use below keeps the alpha it had, so a
    // dark paywall draws exactly the pixels it drew before and a light one becomes legible.
    val ink = PaywallTextDefaults.readable(LocalPaywallPageHex.current)
    var remaining by remember { mutableIntStateOf(seconds) }
    var didFireExpiry by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (remaining > 0) {
            kotlinx.coroutines.delay(1000)
            remaining--
        }
        // 🔴 Once only, and OUTSIDE the loop: a timer authored at zero never enters it, and a
        // re-entrant fire would dismiss repeatedly under `auto_advance`.
        if (!didFireExpiry) {
            didFireExpiry = true
            if (onExpireAction == "auto_advance") onExpire?.invoke()
        }
    }

    val expired = remaining <= 0
    // `hide` removes the section outright once spent; the other two keep drawing.
    if (expired && onExpireAction == "hide") return
    if (expired && onExpireAction == "show_expired_text" && !expiredText.isNullOrEmpty()) {
        Text(text = expiredText, color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        return
    }

    val anyUnitAuthored = showDays != null || showHours != null || showMinutes != null || showSeconds != null
    val uDays = if (anyUnitAuthored) (showDays ?: false) else false
    val uHours = if (anyUnitAuthored) (showHours ?: true) else true
    val uMinutes = if (anyUnitAuthored) (showMinutes ?: true) else true
    val uSeconds = if (anyUnitAuthored) (showSeconds ?: true) else true

    val days = remaining / 86_400
    // With days hidden the hours column carries the whole remainder — 71h rather than 2d 23h.
    val hours = if (uDays) (remaining % 86_400) / 3600 else remaining / 3600
    val minutes = (remaining % 3600) / 60
    val secs = remaining % 60

    // An authored digit colour tints the whole digit row — the chips behind the numbers, the unit
    // letters and the `:` between them — because they are all one element to an author.
    val digitInk = digitColorHex?.let { parseHexColor(it) } ?: ink
    val digitStyle = StyleEngine.applyTextStyle(
        TextStyle(
            fontSize = (digitFontSize ?: 22f).sp,
            fontWeight = resolveWeight(digitFontWeight, FontWeight.Bold),
            color = digitInk,
        ),
        valueTextStyle
    )

    val shown = buildList {
        if (uDays) add(days to "d")
        if (uHours) add(hours to "h")
        if (uMinutes) add(minutes to "m")
        if (uSeconds) add(secs to "s")
    }
    // `Locale.US` keeps the countdown digits ASCII (`05` not `۰۵`) so the paywall countdown reads
    // the same in every locale. Mirrors iOS NumberFormatter. Every variant uses these.
    val segments = shown.map { String.format(java.util.Locale.US, "%02d", it.first) }
    val labels = shown.map { it.second }
    /*
     * How much is left, 0..1 — the ring's sweep and the bar's width, from the SAME shared rule iOS
     * and the onboarding block use (#1066, where this was a frozen 0.75 and the ring never moved).
     */
    val fraction = ai.appdna.sdk.onboarding.CountdownProgress
        .remainingFraction(remaining.toLong(), seconds.toLong()).toFloat()

    // #980 — the authored variant, through the shared rule that owns the fallback.
    when (PaywallCountdownVariant.resolve(variant)) {
        "circular" -> {
            // A ring that empties as the timer runs, with the joined time centred in it.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val sw = 4.dp.toPx()
                        val arcSize = androidx.compose.ui.geometry.Size(this.size.width - sw, this.size.height - sw)
                        drawArc(
                            // The TRACK, from the page-readable ink rather than a hardcoded light
                            // grey (#946's lesson; `check:authorability` caught the literal).
                            color = digitInk.copy(alpha = 0.2f),
                            startAngle = 0f, sweepAngle = 360f, useCenter = false,
                            style = Stroke(width = sw, cap = StrokeCap.Round),
                            topLeft = Offset(sw / 2, sw / 2), size = arcSize,
                        )
                        drawArc(
                            color = digitInk,
                            startAngle = -90f, sweepAngle = 360f * fraction, useCenter = false,
                            style = Stroke(width = sw, cap = StrokeCap.Round),
                            topLeft = Offset(sw / 2, sw / 2), size = arcSize,
                        )
                    }
                    Text(
                        text = segments.joinToString(":"),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = digitInk,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        }
        "flip" -> {
            /*
             * Each segment on its own tinted card, with the unit label beneath.
             *
             * 🔴 CENTRED. `spacedBy(8.dp)` alone left-aligns inside a `fillMaxWidth` Row, and the
             * device walk caught it: iOS centres (a `.frame(maxWidth: .infinity)` VStack child
             * does) and Android drew the cards hard against the left margin. The same authored
             * paywall looked different on the two platforms, which is the divergence this whole
             * variant work exists to remove.
             */
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                segments.forEachIndexed { index, seg ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .background(digitInk.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = seg,
                                style = digitStyle.copy(fontSize = (digitFontSize ?: 24f).sp),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            )
                        }
                        Text(
                            text = labels.getOrElse(index) { "" },
                            fontSize = 10.sp,
                            color = digitInk.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
        "bar" -> {
            // The time on one line with a bar beneath it that shrinks as the timer runs.
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = segments.joinToString(":"),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = digitInk,
                    )
                    /*
                     * Plain text, matching iOS and the onboarding block exactly. There is no
                     * SDK-wide string table, so localising it HERE would make one of three places
                     * translatable and leave two in English — worse than three consistent ones.
                     */
                    Text(text = "remaining", fontSize = 15.sp, color = digitInk.copy(alpha = 0.6f))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(digitInk.copy(alpha = 0.2f), RoundedCornerShape(4.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(8.dp)
                            .background(digitInk, RoundedCornerShape(4.dp)),
                    )
                }
            }
        }
        else -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                segments.forEachIndexed { index, seg ->
                    if (index > 0) {
                        Text(":", color = digitInk.copy(alpha = 0.6f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .background(digitInk.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(text = seg, style = digitStyle)
                        Text(
                            text = labels.getOrElse(index) { "" },
                            fontSize = 10.sp,
                            color = digitInk.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Sticky footer section

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
    val onAction = LocalPaywallAction.current   // #1076
    // Default background #FFFFFF matches iOS
    // PaywallRenderer.swift:1054. Was Color.Black 0.95 alpha → light-mode
    // paywalls rendered dark footer.
    val bgColor = section.data?.background_color?.let { parseHexColor(it) } ?: Color.White
    /*
     * #976 — `blur_background`, which this section DECODED and never drew. iOS fills the footer
     * with `.ultraThinMaterial` when it is true (`PaywallRenderer.swift`), so a footer authored to
     * float over the page as frosted glass was frosted on iOS and an opaque slab on Android: one
     * document, two paywalls, the divergence class ADR-001 exists to prevent.
     *
     * Compose has no `.ultraThinMaterial`. `StyleEngine.applyBlockContainerStyle` already
     * approximates it for block containers with a translucent white layer, and this uses the same
     * approximation rather than a second one — the effect over a solid background is close enough
     * to read as the same design, and a HazeChild dependency is not worth one section.
     *
     * Unauthored keeps the opaque fill, so nothing already published moves.
     */
    val footerFill = if (section.data?.blur_background == true) {
        Color.White.copy(alpha = 0.72f)
    } else {
        bgColor
    }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .background(footerFill)
            .padding(horizontal = (section.data?.padding ?: 20f).dp, vertical = 16.dp)
            .run { with(StyleEngine) { applyContainerStyle(section.style?.container) } },
    ) {
        // CTA button — selected plan's per-plan cta_text overrides the footer's configured label.
        (selectedPlanCtaText ?: section.data?.cta_text)?.let { ctaText ->
            Button(
                onClick = onCTATap,
                enabled = !isPurchasing,
                // Honor cta_height + cta_font_size
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
                        onAction(PaywallAction.LINK_TAPPED)   // #1076
                        // Allowlisted, like every other config-driven URL.
                        ai.appdna.sdk.core.URLSafety.open(context, section.data.secondary_url)
                    }
                }
            }) {
                Text(
                    text = loc("sticky_footer.secondary", secondaryText),
                    // Theme-adaptive secondary
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
                // Theme-adaptive legal matches iOS
                // `.foregroundColor(.secondary)` at PaywallRenderer.swift:1103.
                // Now authorable. Unset keeps this exact colour and 10sp, so a
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

/**
 * The ONE colour parser. Delegates to [StyleEngine.parseColor].
 *
 * This file used to carry its own copy, and the copy was wrong in two ways that only showed up
 * when a payload reached a device:
 *
 *  - 8-char hex went through `Color(colorLong)`, which Compose reads as **ARGB**, so `#1122334F`
 *    became (A=0x11, R=0x22, G=0x33, B=0x4F) — every channel rotated, and near-transparent.
 *    iOS reads the same string as **RGBA** (`PaywallHelperViews.swift` `Color(hex:)`). The same
 *    bug was found and fixed in `StyleEngine.parseColor`; this copy never got the fix.
 *  - an unparseable string fell back to `Color.Black`, where `StyleEngine` and iOS both fall back
 *    to transparent. A page background the SDK could not read therefore rendered an opaque black
 *    screen on Android and a see-through one on iOS, from one value — which is what one production
 *    paywall carrying `rgba(255,255,255,0.15)` does today.
 *
 * Keeping the signature means the 77 call sites in this file stay as they are; what changes is
 * that they now agree with the rest of the SDK, and with iOS.
 */
private fun parseHexColor(hex: String): Color = StyleEngine.parseColor(hex)

/**
 * Review avatar emoji resolver.
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

// Compact count formatter mirroring iOS
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
