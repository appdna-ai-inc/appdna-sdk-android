package ai.appdna.sdk.messages

// SPEC-070-A A.9 — instantiated in AppDNA.kt configure(); track() fans out
// to onEvent(); reset() calls resetSession(). The configProvider lambda
// reads from `AppDNA.activeMessages`, a snapshot Map<String, MessageConfig>
// updated by the RemoteConfigManager messages handler.
//
// Note: full Firestore-doc parsing into the activeMessages snapshot is part
// of the messages remote-config wiring (RemoteConfigManager messageUpdateHandler,
// to be added when the active-messages Firestore doc shape ships in console).
// Until that handler is wired, configProvider returns an empty map and the
// trigger evaluator runs but has nothing to evaluate — A.9 mechanism is in
// place; activation is decoupled.

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.AppDNAInAppMessageDelegate
import ai.appdna.sdk.Log
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * SPEC-070-A A.9 — Manages in-app message trigger evaluation, frequency
 * tracking, and presentation. Mirrors iOS
 * `InAppMessaging/MessageManager.swift:6-267`.
 *
 * Architectural split (do not change):
 * - `MessageManager` owns: trigger evaluation, condition matching,
 *   frequency tracking, candidate selection, queue gate, delegate veto,
 *   `in_app_message_*` analytics events, CTA action routing.
 * - `InAppMessageView` (composable in `InAppMessageRenderer.kt`) owns:
 *   actual rendering of banner/modal/fullscreen/tooltip variants.
 *
 * `MessageManager` hosts the `InAppMessageView` inside a `ComponentDialog`
 * — same approach `PendingMessageListener` uses — so journey-delivered and
 * trigger-delivered messages both land in the same renderer.
 *
 * NOTE: this class deliberately does NOT touch the renderer ctor — it
 * uses the existing `@Composable InAppMessageView` function directly.
 * The `renderer` constructor parameter (kept on the public API for
 * symmetry with iOS DI) is currently unused as a delivery handle; it
 * only exists so a sample app or test can subclass / replace the
 * presentation pipeline.
 */
class MessageManager internal constructor(
    private val context: Context,
    private val configProvider: () -> Map<String, MessageConfig>,
    @Suppress("unused") private val renderer: InAppMessageRenderer = InAppMessageRenderer.shared,
    // SPEC-036-F §1.2 — consulted per-candidate (inside the present hook) for a
    // running in-app-message experiment targeting the message being shown.
    // The constructor is `internal` because it takes the internal
    // ExperimentManager; MessageManager is only ever built by the SDK
    // (AppDNA.configure), never by a host. The class + its public methods stay
    // public for the `AppDNA.inAppMessages` namespace.
    private val experimentManager: ai.appdna.sdk.config.ExperimentManager? = null,
) {

    /**
     * Public delegate. Hosted by `AppDNA.inAppMessages` namespace —
     * `AppDNAModules.kt InAppMessagesModule.setDelegate(...)` writes
     * through to this field.
     */
    @Volatile
    var delegate: AppDNAInAppMessageDelegate? = null

    /**
     * SPEC-070-C D10 — OPTIONAL async wrapper-veto. Set by a cross-platform
     * wrapper (the Flutter plugin) that can only answer a veto asynchronously
     * (round-trip to Dart). Awaited by [present] in ADDITION to the synchronous
     * [AppDNAInAppMessageDelegate.shouldShowMessage]; both can suppress. Null
     * for native hosts → no behavior change (presentation stays synchronous).
     * The wrapper is responsible for the timeout / default-allow.
     */
    @Volatile
    var asyncShouldShowMessage: (suspend (String) -> Boolean)? = null

    private val isPresenting = AtomicBoolean(false)
    private val suppress = AtomicBoolean(false)
    private val frequencyTracker = MessageFrequencyTracker(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * SPEC-070-C D10 — main-thread scope for awaiting the async wrapper-veto.
     * Only used when [asyncShouldShowMessage] is set; [presentBody] then runs
     * on the main thread after the awaited decision resolves.
     */
    private val vetoScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /**
     * Evaluate every active message against an event. Called from
     * `AppDNA.trackInternal()` after the event has been written to the queue
     * (see file-top wiring TODO).
     */
    fun onEvent(eventName: String, props: Map<String, Any?>) {
        if (isPresenting.get() || suppress.get()) {
            Log.debug("[Messages] Skipping '$eventName' — isPresenting=${isPresenting.get()}, suppress=${suppress.get()}")
            return
        }

        val messages = try {
            configProvider()
        } catch (e: Throwable) {
            Log.warning("[Messages] configProvider threw: ${e.message}")
            return
        }

        Log.debug("[Messages] Evaluating ${messages.size} active message(s) for '$eventName'")

        var filteredByEvent = 0
        var filteredByConditions = 0
        var filteredByFrequency = 0
        var filteredByDateRange = 0
        val candidates: MutableList<Pair<String, MessageConfig>> = mutableListOf()

        for ((id, config) in messages) {
            val rules = config.trigger_rules

            // 1. Event name match
            if (rules.event != eventName) {
                filteredByEvent++
                continue
            }
            // 2. Conditions evaluation
            if (!evaluateConditions(rules.conditions, props)) {
                filteredByConditions++
                continue
            }
            // 3. Frequency check
            if (!frequencyTracker.canShow(id, rules.frequency, rules.max_displays)) {
                filteredByFrequency++
                continue
            }
            // 4. Date range check
            if (!checkDateRange(config)) {
                filteredByDateRange++
                continue
            }
            candidates.add(id to config)
        }

        if (candidates.isEmpty()) {
            Log.debug("[Messages] No candidates for '$eventName' — filtered: event=$filteredByEvent, conditions=$filteredByConditions, frequency=$filteredByFrequency, dateRange=$filteredByDateRange")
            return
        }

        // 5. Sort by priority (highest first); tie-break by id for determinism.
        val winner = candidates.sortedWith(
            compareByDescending<Pair<String, MessageConfig>> { it.second.priority }.thenBy { it.first }
        ).first()

        // 6. Optional delay
        val delaySec = winner.second.trigger_rules.delay_seconds ?: 0
        val runnable = Runnable { present(winner.first, winner.second, eventName) }
        if (delaySec > 0) {
            mainHandler.postDelayed(runnable, delaySec * 1000L)
        } else {
            mainHandler.post(runnable)
        }
    }

    /**
     * Toggle session-wide suppression. While true, `onEvent` is a no-op.
     */
    fun suppressDisplay(suppress: Boolean) {
        this.suppress.set(suppress)
    }

    /**
     * Clear in-session frequency counters. Called from `AppDNA.reset()`.
     *
     * SPEC-401-A R86 (Lens B F1) — do NOT reset `isPresenting` here. iOS
     * `MessageManager.resetSession()` leaves the gate untouched
     * (InAppMessaging/MessageManager.swift:99-101). If `AppDNA.reset()` is
     * called while a Dialog is actively showing, dropping the gate races
     * a second concurrent present() (in-flight Dialog stays visible while
     * a follow-up tracked event passes the gate and calls present() again,
     * stacking dialogs). The gate is reset on natural dismiss or shutdown.
     */
    fun resetSession() {
        frequencyTracker.resetSession()
    }

    /**
     * SPEC-070-A final audit pass C F2 — release `delay_seconds`-queued
     * runnables on SDK shutdown so Dialog presentations don't fire on a
     * stale Activity reference after `AppDNA.shutdown()`. Mirrors the
     * (ARC-driven) iOS DispatchWorkItem cancellation in InAppMessaging.
     */
    fun shutdown() {
        mainHandler.removeCallbacksAndMessages(null)
        isPresenting.set(false)
        suppress.set(false)
    }

    // MARK: - Presentation

    private fun present(messageId: String, activeConfig: MessageConfig, triggerEvent: String) {
        // SPEC-404 — pause new in-app message presentation while the SDK is
        // backend-locked (per-key suspended day 20+ OR org cancelled).
        // Messages already shown stay visible. No analytics event emitted.
        // Check BEFORE the isPresenting flip so the slot stays available.
        if (ai.appdna.sdk.AppDNA.runtimeLock != null) {
            Log.debug("[Messages] $messageId suppressed — SDK in runtime-locked mode")
            return
        }

        // Round-13 F1 — experiment resolution (which records an experiment_exposure as a side effect) is
        // deferred into presentBody, so it runs ONLY after EVERY synchronous suppression gate
        // (runtimeLock above + isPresenting + shouldShowMessage veto + async veto below) AND the
        // foreground-activity check. Previously it ran here, above the isPresenting/veto gates, so a
        // host that vetoed the message via shouldShowMessage still recorded an exposure for a surface it
        // never saw — inflating the message-experiment denominator vs iOS (which resolves after its gate).

        // Re-check the gate inside the main-thread runnable in case a
        // previous async dispatch flipped it.
        if (!isPresenting.compareAndSet(false, true)) {
            Log.debug("[Messages] $messageId skipped — another message presenting")
            return
        }

        // SPEC-400 — `shouldShowMessage` veto. Run BEFORE any analytics
        // tracking or view construction so a vetoed message produces no
        // `in_app_message_shown` event.
        val host = delegate
        if (host != null && !host.shouldShowMessage(messageId)) {
            Log.debug("[Messages] $messageId vetoed by host shouldShowMessage")
            isPresenting.set(false)
            return
        }

        // SPEC-070-C D10 — OPTIONAL async wrapper-veto, awaited in ADDITION to
        // the synchronous veto above. When set (Flutter host), suspend on the
        // main scope; a `false` reply releases the presenting gate and aborts.
        // When null (every native host), fall through to synchronous rendering
        // exactly as before — no behavior change.
        val asyncVeto = asyncShouldShowMessage
        if (asyncVeto != null) {
            vetoScope.launch {
                val allow = try {
                    asyncVeto(messageId)
                } catch (t: Throwable) {
                    true
                }
                if (!allow) {
                    Log.debug("[Messages] $messageId vetoed by host asyncShouldShowMessage")
                    isPresenting.set(false)
                    return@launch
                }
                presentBody(messageId, activeConfig, triggerEvent)
            }
            return
        }

        presentBody(messageId, activeConfig, triggerEvent)
    }

    /**
     * Presentation remainder, extracted so the D10 async wrapper-veto can gate
     * it behind an awaited decision. Runs on the main thread; [config] is the
     * experiment-resolved config computed by [present]. The [isPresenting] gate
     * has already been claimed by the caller.
     */
    private fun presentBody(messageId: String, activeConfig: MessageConfig, triggerEvent: String) {
        val activity = currentActivity()
        if (activity == null) {
            Log.warning("[Messages] No foreground activity for $messageId")
            isPresenting.set(false)
            return
        }

        // SPEC-036-F §1.2 — experiment-aware presentation. A running in-app-message experiment
        // targeting this message + a treatment bucket renders the treatment payload; control /
        // non-bucketed / old-doc → active. Round-13 F1 — resolved HERE (after every suppression gate +
        // the foreground-activity check) rather than in present(), so `resolveSurfacePresentation`'s
        // experiment_exposure side effect records ONLY for a message actually being shown.
        var config = activeConfig
        val resolution = experimentManager?.resolveSurfacePresentation("in_app_message", messageId)
        if (resolution is ai.appdna.sdk.config.ExperimentManager.SurfaceResolution.RenderTreatment) {
            val treatment = MessageConfigParser.parseSingleMessage(resolution.payload)
            if (treatment != null) {
                Log.info("[Messages] $messageId rendering experiment treatment variant")
                config = treatment
            }
        }

        // Record shown BEFORE rendering so concurrent `onEvent` calls
        // see the updated counter via `canShow`.
        frequencyTracker.recordShown(messageId, config.trigger_rules.frequency)

        AppDNA.trackInternal(
            "in_app_message_shown",
            mapOf(
                "message_id" to messageId,
                "message_type" to config.message_type.value,
                "trigger_event" to triggerEvent,
            ),
        )

        // SPEC-400 — fire onMessageShown
        mainHandler.post { delegate?.onMessageShown(messageId, triggerEvent) }

        try {
            val dialog = ComponentDialog(activity)
            val composeView = ComposeView(activity).apply {
                setViewTreeLifecycleOwner(dialog)
                setViewTreeSavedStateRegistryOwner(dialog)
                setContent {
                    InAppMessageView(
                        config = config,
                        onCTATap = {
                            val ctaActionType = config.content.cta_action?.type ?: "dismiss"
                            AppDNA.trackInternal(
                                "in_app_message_clicked",
                                mapOf("message_id" to messageId, "cta_action" to ctaActionType),
                            )
                            // SPEC-400 — onMessageAction with action type + cta data
                            val ctaData: Map<String, Any>? = config.content.cta_action?.url?.let { mapOf("url" to it) }
                            mainHandler.post { delegate?.onMessageAction(messageId, ctaActionType, ctaData) }

                            handleCTAAction(activity, config.content.cta_action)
                            dialog.dismiss()
                        },
                        onDismiss = {
                            AppDNA.trackInternal(
                                "in_app_message_dismissed",
                                mapOf("message_id" to messageId),
                            )
                            dialog.dismiss()
                        },
                    )
                }
            }
            dialog.setContentView(composeView)
            dialog.setOnDismissListener {
                isPresenting.set(false)
                mainHandler.post { delegate?.onMessageDismissed(messageId) }
            }
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                )
            }
            dialog.show()
        } catch (e: Throwable) {
            Log.warning("[Messages] present failed for $messageId: ${e.message}")
            isPresenting.set(false)
        }
    }

    private fun handleCTAAction(activity: Activity, action: CTAAction?) {
        if (action == null) return
        when (action.type) {
            "deep_link", "open_url" -> {
                val url = action.url ?: return
                // SPEC-070-B PN row 18 (W11): config-driven URL — scheme-checked before the OS sees it.
                val uri = ai.appdna.sdk.core.URLSafety.sanitized(url, activity) ?: return
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Throwable) {
                    Log.warning("[Messages] open URL failed: ${e.message}")
                }
            }
            else -> Unit // dismiss / unknown handled by dialog dismiss
        }
    }

    // MARK: - Condition evaluation (mirrors MessageManager.swift:196-233)

    private fun evaluateConditions(conditions: List<TriggerCondition>?, properties: Map<String, Any?>): Boolean {
        if (conditions.isNullOrEmpty()) return true
        return conditions.all { condition ->
            val propValue = properties[condition.field] ?: return@all false
            val condValue = condition.value ?: return@all false
            evaluateOperator(condition.operator, propValue, condValue)
        }
    }

    private fun evaluateOperator(op: String, propValue: Any, condValue: Any): Boolean {
        return when (op) {
            "eq" -> propValue.toString() == condValue.toString()
            "gte" -> {
                val p = toDouble(propValue) ?: return false
                val c = toDouble(condValue) ?: return false
                p >= c
            }
            "lte" -> {
                val p = toDouble(propValue) ?: return false
                val c = toDouble(condValue) ?: return false
                p <= c
            }
            "gt" -> {
                val p = toDouble(propValue) ?: return false
                val c = toDouble(condValue) ?: return false
                p > c
            }
            "lt" -> {
                val p = toDouble(propValue) ?: return false
                val c = toDouble(condValue) ?: return false
                p < c
            }
            "contains" -> propValue.toString().contains(condValue.toString())
            else -> false
        }
    }

    private fun toDouble(value: Any): Double? = when (value) {
        is Double -> value
        is Float -> value.toDouble()
        is Int -> value.toDouble()
        is Long -> value.toDouble()
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    // MARK: - Date range

    /**
     * The server serializes `start_date`/`end_date` via `.toISOString()` — a full ISO-8601 instant
     * with time-of-day and `Z`. Read it as iOS does (`MessageManager.parseWindowDate`): first as an
     * instant, as iOS's `ISO8601.date(from:)` reads it, then — for hand-authored / legacy values — as a
     * date at UTC midnight, as iOS's UTC `yyyy-MM-dd` `DateFormatter` reads it. Both readers are
     * [ai.appdna.sdk.core.IsoInstant], which reproduces iOS's ICU parsing, leniencies included (see its
     * KDoc), so one string opens the window at the same instant on both platforms. Unparsable → null
     * (treated as no scheduling constraint). Pinned by the shared fixture
     * `dto_parsing/message_window_date_parsing`.
     */
    internal fun parseWindowDate(iso: String): Date? {
        val ms = ai.appdna.sdk.core.IsoInstant.parseEpochMs(iso)
            ?: ai.appdna.sdk.core.IsoInstant.parseDateOnlyUtcMs(iso)
        return ms?.let { Date(it) }
    }

    private fun checkDateRange(config: MessageConfig): Boolean {
        val now = Date()
        config.start_date?.let { startStr ->
            val start = parseWindowDate(startStr)
            if (start != null && now.before(start)) return false
        }
        config.end_date?.let { endStr ->
            val end = parseWindowDate(endStr)
            if (end != null && now.after(end)) return false
        }
        return true
    }

    // MARK: - Activity lookup (reflection — mirrors PendingMessageListener)

    private fun currentActivity(): Activity? {
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentMethod = activityThread.getMethod("currentActivityThread")
            val thread = currentMethod.invoke(null)
            val activitiesField = activityThread.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val activities = activitiesField.get(thread) as? android.util.ArrayMap<Any, Any>
            activities?.values?.firstNotNullOfOrNull { record ->
                val pausedField = record.javaClass.getDeclaredField("paused")
                pausedField.isAccessible = true
                if (!pausedField.getBoolean(record)) {
                    val activityField = record.javaClass.getDeclaredField("activity")
                    activityField.isAccessible = true
                    activityField.get(record) as? Activity
                } else null
            }
        } catch (e: Throwable) {
            Log.warning("[Messages] currentActivity reflection failed: ${e.message}")
            null
        }
    }
}

/**
 * Companion shared instance for the existing renderer. Kept here as a
 * lightweight handle so the manager has a typed dependency to inject.
 * The renderer is implemented as a top-level `@Composable` so this class
 * carries no state.
 */
class InAppMessageRenderer private constructor() {
    companion object {
        val shared: InAppMessageRenderer = InAppMessageRenderer()
    }
}

// MessageFrequencyTracker has been extracted to its own file
// (messages/MessageFrequencyTracker.kt) per SPEC-070-A F.12.
