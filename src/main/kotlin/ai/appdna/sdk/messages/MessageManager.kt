package ai.appdna.sdk.messages

import ai.appdna.sdk.core.TriggerOperator

// Instantiated in AppDNA.kt configure(); track() fans out
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
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * Manages in-app message trigger evaluation, frequency
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
/**
 * Where a config-driven in-app message is drawn. The default draws over the resumed activity; a test passes its own.
 */
internal interface MessageSurface {
    /**
     * The resumed activity to draw over, or null when there is none — a process started with no activity (an FCM data
     * message, a WorkManager job, a background fetch).
     */
    fun host(): Activity?

    /** Puts the message on screen over [activity]. [onClosed] runs once, when it is really gone. */
    fun show(
        messageId: String,
        activity: Activity,
        config: MessageConfig,
        onCTATap: (Activity) -> Unit,
        onDismiss: () -> Unit,
        onClosed: () -> Unit,
    )

    /** Calls [block] each time an activity of the app resumes; returns what stops that. */
    fun observeActivation(block: () -> Unit): () -> Unit
}

class MessageManager internal constructor(
    private val context: Context,
    private val configProvider: () -> Map<String, MessageConfig>,
    @Suppress("unused") private val renderer: InAppMessageRenderer = InAppMessageRenderer.shared,
    // Consulted per-candidate (inside the present hook) for a
    // running in-app-message experiment targeting the message being shown.
    // The constructor is `internal` because it takes the internal
    // ExperimentManager; MessageManager is only ever built by the SDK
    // (AppDNA.configure), never by a host. The class + its public methods stay
    // public for the `AppDNA.inAppMessages` namespace.
    private val experimentManager: ai.appdna.sdk.config.ExperimentManager? = null,
    surface: MessageSurface? = null,
) {

    /**
     * Public delegate. Hosted by `AppDNA.inAppMessages` namespace —
     * `AppDNAModules.kt InAppMessagesModule.setDelegate(...)` writes
     * through to this field.
     */
    @Volatile
    var delegate: AppDNAInAppMessageDelegate? = null

    /**
     * OPTIONAL async wrapper-veto. Set by a cross-platform
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

    private val surface: MessageSurface = surface ?: LiveSurface(context)

    /**
     * Messages triggered with no resumed activity to draw over, in trigger order. A message triggered while the app
     * has no activity (a background start) is HELD — not drawn, not counted shown, its frequency not used up — and
     * drawn when an activity next resumes in the same process, if it is still published, inside its date window and
     * allowed by its frequency. Held once however often it triggers; drawing it takes it off the hold.
     */
    private class Held(val messageId: String, val triggerEvent: String)
    private val held = ArrayList<Held>()
    private val heldLock = Any()

    private val stopActivation: () -> Unit = this.surface.observeActivation { releaseHeld() }

    /** The ids of the held messages, in trigger order (tests). */
    internal val heldMessageIds: List<String> get() = synchronized(heldLock) { held.map { it.messageId } }

    /**
     * Main-thread scope for awaiting the async wrapper-veto.
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
        val was = this.suppress.getAndSet(suppress)
        // Turning it off draws a held message if an activity is resumed.
        if (was && !suppress) mainHandler.post { releaseHeld() }
    }

    /**
     * Clear in-session frequency counters. Called from `AppDNA.reset()`.
     *
     * Do NOT reset `isPresenting` here. iOS
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
     * Release `delay_seconds`-queued
     * runnables on SDK shutdown so Dialog presentations don't fire on a
     * stale Activity reference after `AppDNA.shutdown()`. Mirrors the
     * (ARC-driven) iOS DispatchWorkItem cancellation in InAppMessaging.
     */
    fun shutdown() {
        mainHandler.removeCallbacksAndMessages(null)
        isPresenting.set(false)
        suppress.set(false)
        stopActivation()
        synchronized(heldLock) { held.clear() }
    }

    // MARK: - Held messages

    /** No resumed activity: keep the message for the next one. Nothing is recorded — it was not seen. */
    private fun hold(messageId: String, triggerEvent: String, atFront: Boolean = false) {
        synchronized(heldLock) {
            if (held.any { it.messageId == messageId }) {
                Log.debug("[Messages] $messageId already held — not queued twice")
                return
            }
            if (atFront) held.add(0, Held(messageId, triggerEvent)) else held.add(Held(messageId, triggerEvent))
        }
        Log.debug("[Messages] No resumed activity — holding $messageId until an activity resumes")
    }

    /**
     * An activity resumed, or a message closed: draw the first held message that may still be shown. One at a time;
     * the next is drawn when that one closes. Each is re-read from the current config, so a message unpublished, out of
     * its date window, or used up by its frequency while it waited is dropped. Runs on the main thread.
     */
    internal fun releaseHeld() {
        if (isPresenting.get() || suppress.get() || surface.host() == null) return
        val active = try { configProvider() } catch (e: Throwable) { return }
        while (true) {
            val h = synchronized(heldLock) { if (held.isEmpty()) null else held.removeAt(0) } ?: return
            val config = active[h.messageId]
            if (config == null ||
                !frequencyTracker.canShow(h.messageId, config.trigger_rules.frequency, config.trigger_rules.max_displays) ||
                !checkDateRange(config)
            ) {
                Log.debug("[Messages] Held ${h.messageId} is no longer showable — dropped")
                continue
            }
            Log.debug("[Messages] Activity resumed — presenting held ${h.messageId}")
            present(h.messageId, config, h.triggerEvent, fromHold = true)
            return
        }
    }

    // MARK: - Presentation

    private fun present(messageId: String, activeConfig: MessageConfig, triggerEvent: String, fromHold: Boolean = false) {
        // Pause new in-app message presentation while the SDK is
        // backend-locked (per-key suspended day 20+ OR org cancelled).
        // Messages already shown stay visible. No analytics event emitted.
        // Check BEFORE the isPresenting flip so the slot stays available.
        if (ai.appdna.sdk.AppDNA.runtimeLock != null) {
            Log.debug("[Messages] $messageId suppressed — SDK in runtime-locked mode")
            return
        }

        // Experiment resolution (which records an experiment_exposure as a side effect) is
        // deferred into presentBody, so it runs ONLY after EVERY synchronous suppression gate
        // (runtimeLock above + isPresenting + shouldShowMessage veto + async veto below) AND the
        // foreground-activity check. Previously it ran here, above the isPresenting/veto gates, so a
        // host that vetoed the message via shouldShowMessage still recorded an exposure for a surface it
        // never saw — inflating the message-experiment denominator vs iOS (which resolves after its gate).

        // Re-check the gate inside the main-thread runnable in case a
        // previous async dispatch flipped it.
        if (!isPresenting.compareAndSet(false, true)) {
            Log.debug("[Messages] $messageId skipped — another message presenting")
            // A held message that lost the race to another: back on the hold, first.
            if (fromHold) hold(messageId, triggerEvent, atFront = true)
            return
        }

        // `shouldShowMessage` veto. Run BEFORE any analytics
        // tracking or view construction so a vetoed message produces no
        // `in_app_message_shown` event.
        val host = delegate
        if (host != null && !host.shouldShowMessage(messageId)) {
            Log.debug("[Messages] $messageId vetoed by host shouldShowMessage")
            isPresenting.set(false)
            return
        }

        // No resumed activity (a background start): held AFTER the gates above, so a message the host vetoes is
        // dropped as before, and BEFORE the async veto and the experiment resolution (which records an exposure). All
        // the gates are asked again when it is drawn.
        if (surface.host() == null) {
            isPresenting.set(false)
            hold(messageId, triggerEvent, atFront = fromHold)
            return
        }

        // OPTIONAL async wrapper-veto, awaited in ADDITION to
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
                presentBody(messageId, activeConfig, triggerEvent, fromHold)
            }
            return
        }

        presentBody(messageId, activeConfig, triggerEvent, fromHold)
    }

    /**
     * Presentation remainder, extracted so the D10 async wrapper-veto can gate
     * it behind an awaited decision. Runs on the main thread; [config] is the
     * experiment-resolved config computed by [present]. The [isPresenting] gate
     * has already been claimed by the caller.
     */
    private fun presentBody(messageId: String, activeConfig: MessageConfig, triggerEvent: String, fromHold: Boolean = false) {
        val activity = surface.host()
        if (activity == null) {
            // The activity went away while the veto was answered: held, not dropped.
            Log.debug("[Messages] No resumed activity for $messageId")
            isPresenting.set(false)
            hold(messageId, triggerEvent, atFront = fromHold)
            return
        }
        // Drawn now: a held copy of the same message is this one, so it is not drawn again when an activity resumes.
        synchronized(heldLock) { held.removeAll { it.messageId == messageId } }

        // Experiment-aware presentation. A running in-app-message experiment
        // targeting this message + a treatment bucket renders the treatment payload; control /
        // non-bucketed / old-doc → active. Resolved HERE (after every suppression gate +
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

        // SPEC-536: the message's content on every event it emits. Empty for a journey delivery
        // (PendingMessageListener builds its config with no version), which Serve instead never touches.
        val contentStamp = config.contentStamp
        AppDNA.trackInternal(
            "in_app_message_shown",
            mapOf(
                "message_id" to messageId,
                "message_type" to config.message_type.value,
                "trigger_event" to triggerEvent,
            ) + contentStamp,
        )

        // Fire onMessageShown
        mainHandler.post { delegate?.onMessageShown(messageId, triggerEvent) }

        try {
            // `show` keeps the message on screen across a recreation of the host's activity (a fold,
            // an unfold, a rotation), and runs onClosed once, when it is really gone.
            var openedShare = false
            surface.show(
                messageId,
                activity,
                config,
                onCTATap = { onActivity ->
                    val ctaActionType = config.content.cta_action?.type ?: "dismiss"
                    AppDNA.trackInternal(
                        "in_app_message_clicked",
                        mapOf("message_id" to messageId, "cta_action" to ctaActionType) + contentStamp,
                    )
                    // onMessageAction with action type + cta data
                    val ctaData: Map<String, Any>? = config.content.cta_action?.url?.let { mapOf("url" to it) }
                    mainHandler.post { delegate?.onMessageAction(messageId, ctaActionType, ctaData) }

                    // A share opens the system chooser over the host: the close that follows must not draw a held
                    // message behind it. It waits for the next activity resume — the user coming back from the
                    // chooser — as on iOS.
                    if (MessageCTADispatch.opensShare(config.content.cta_action)) openedShare = true
                    handleCTAAction(onActivity, config.content.cta_action)
                },
                onDismiss = {
                    AppDNA.trackInternal(
                        "in_app_message_dismissed",
                        mapOf("message_id" to messageId) + contentStamp,
                    )
                },
                onClosed = {
                    isPresenting.set(false)
                    mainHandler.post { delegate?.onMessageDismissed(messageId) }
                    // The next held message, if one is waiting — not behind a share chooser just opened.
                    if (!openedShare) mainHandler.post { releaseHeld() }
                },
            )
        } catch (e: Throwable) {
            Log.warning("[Messages] present failed for $messageId: ${e.message}")
            isPresenting.set(false)
        }
    }

    // dismiss / unknown / a link type with no link: nothing extra (the dialog dismisses). `share`
    // opens the share sheet. One implementation with PendingMessageListener.
    private fun handleCTAAction(activity: Activity, action: CTAAction?) =
        MessageCTADispatch.perform(activity, action, "[Messages]")

    // MARK: - Condition evaluation (mirrors MessageManager.swift:196-233)

    private fun evaluateConditions(conditions: List<TriggerCondition>?, properties: Map<String, Any?>): Boolean {
        if (conditions.isNullOrEmpty()) return true
        return conditions.all { condition ->
            val propValue = properties[condition.field] ?: return@all false
            val condValue = condition.value ?: return@all false
            TriggerOperator.matches(condition.operator, propValue, condValue)
        }
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

    // MARK: - The live surface

    private class LiveSurface(private val context: Context) : MessageSurface {
        override fun host(): Activity? = currentActivity()

        override fun show(
            messageId: String,
            activity: Activity,
            config: MessageConfig,
            onCTATap: (Activity) -> Unit,
            onDismiss: () -> Unit,
            onClosed: () -> Unit,
        ) {
            // `show` keeps the message on screen across a recreation of the host's activity (a fold,
            // an unfold, a rotation), and runs onClosed once, when it is really gone.
            InAppMessageDialog.show(activity, config, onCTATap, onDismiss, onClosed)
        }

        override fun observeActivation(block: () -> Unit): () -> Unit {
            val app = context.applicationContext as? Application ?: return {}
            val main = Handler(Looper.getMainLooper())
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                // Posted, so the activity has finished resuming when the surface looks for it.
                override fun onActivityResumed(activity: Activity) { main.post { block() } }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            }
            app.registerActivityLifecycleCallbacks(callbacks)
            return { app.unregisterActivityLifecycleCallbacks(callbacks) }
        }
    }
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
// (messages/MessageFrequencyTracker.kt).
