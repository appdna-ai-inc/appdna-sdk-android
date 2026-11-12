package ai.appdna.sdk.onboarding

/**
 * The "Add widget" button action: Android asks the launcher to pin the HOST app's own widget.
 *
 * The SDK ships no widget and draws none. It finds the host's `AppWidgetProvider` (see
 * [WidgetProviderDiscovery]), asks the launcher to pin it with `requestPinAppWidget`, and — where
 * the launcher cannot or will not show a pin dialog — shows the button's authored Android how-to
 * sheet (`howto_blocks_android`). The step then advances ONCE with `widget_status` in its data, so
 * `next_step_rules` can branch on it:
 *
 *  - `added`       — the launcher's success callback arrived, or the widget-id set grew;
 *  - `not_added`   — the attempt ended and no new widget was seen;
 *  - `unavailable` — the app declares no home-screen widget at all.
 *
 * This class is the attempt's state machine and nothing else. Platform calls go through
 * [WidgetPinPlatform] and everything with a side effect (events, the advance, the sheet, timers)
 * goes through [WidgetPinEffects], so a JVM test and the shared fixture runner drive exactly this
 * code with fakes on both seams.
 *
 * Detection is a SET DIFFERENCE of widget ids (`ids(provider) − S0`), never a count compare: a
 * widget removed and another added in the same trip still reads as added.
 */
internal class WidgetPinAttempt(
    private val platform: WidgetPinPlatform,
    private val effects: WidgetPinEffects,
    private val flowId: String,
    private val stepId: String,
    private val newToken: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    enum class State { IDLE, WAITING_DIALOG, AWAY, GRACE, HOWTO, DONE }

    var state: State = State.IDLE
        private set

    /** The live attempt's token, or null when no attempt is live. A callback carrying any other is dropped. */
    var liveToken: String? = null
        private set

    private var block: ContentBlock? = null
    private var provider: String? = null
    private var s0: Set<Int>? = null
    private var pinned = false
    private var pauseTimer: WidgetPinTimer? = null
    private var graceEndTimer: WidgetPinTimer? = null
    private var graceTickTimer: WidgetPinTimer? = null

    // ---- Events from the step -------------------------------------------------------------------

    /** The `add_widget` button was tapped. A tap in any state but IDLE is ignored (a double tap sends one request). */
    fun tap(button: ContentBlock) {
        if (state != State.IDLE) {
            ai.appdna.sdk.Log.debug("Add widget: tap ignored, an attempt is already live ($state)")
            return
        }
        // The step's required-field gate runs BEFORE anything starts: a refusal shows the step's
        // validation message and starts nothing — no event, no dialog, no sheet.
        if (!effects.gatePasses()) return

        val chosen = WidgetProviderDiscovery.choose(platform.homeScreenProviders(), button.action_value)
        if (chosen == null) {
            track(ADD_UNAVAILABLE_EVENT, mapOf("reason" to "no_widget"))
            finish(STATUS_UNAVAILABLE)
            return
        }
        block = button
        provider = chosen
        pinned = false
        val token = newToken()
        liveToken = token
        s0 = platform.widgetIds(chosen)
        track(ADD_TAPPED_EVENT, mapOf("block_id" to button.id, "existing_count" to s0?.size))

        val unsupportedReason = when {
            platform.sdkInt < 26 -> "api_level"
            !platform.isRequestPinSupported() -> "launcher_unsupported"
            else -> null
        }
        if (unsupportedReason != null) {
            track(PIN_UNAVAILABLE_EVENT, mapOf("reason" to unsupportedReason))
            enterHowto()
            return
        }
        val sent = try {
            platform.requestPin(chosen, token)
        } catch (e: IllegalStateException) {
            // Not in the foreground, or the user is locked.
            ai.appdna.sdk.Log.warning("Add widget: requestPinAppWidget threw ${e.message}")
            false
        }
        if (!sent) {
            track(PIN_UNAVAILABLE_EVENT, mapOf("reason" to "request_refused"))
            enterHowto()
            return
        }
        track(PIN_REQUESTED_EVENT, mapOf("provider" to chosen))
        state = State.WAITING_DIALOG
        pauseTimer = effects.schedule(effects.pauseTimeoutMs) { onPauseTimeout() }
    }

    /** The host activity paused (the launcher's pin dialog is on top, or the user left). */
    fun onPause() {
        when (state) {
            State.WAITING_DIALOG -> {
                pauseTimer?.cancel(); pauseTimer = null
                state = State.AWAY
            }
            State.GRACE -> {
                cancelGrace()
                state = State.AWAY
            }
            else -> Unit
        }
    }

    /** The host activity resumed. */
    fun onResume() {
        when (state) {
            State.AWAY -> {
                if (pinned) {
                    added(METHOD_PIN)
                } else {
                    state = State.GRACE
                    graceEndTimer = effects.schedule(effects.graceMs) { onGraceEnd() }
                    scheduleTick()
                }
            }
            State.HOWTO -> {
                if (grew()) {
                    effects.closeHowto()
                    track(HOWTO_DISMISSED_EVENT, mapOf("via" to "detected"))
                    added(METHOD_HOWTO)
                }
            }
            else -> Unit
        }
    }

    /** The launcher's pin-success broadcast. Only the live attempt's token counts. */
    fun onCallback(token: String?) {
        if (token == null || token != liveToken) {
            ai.appdna.sdk.Log.debug("Add widget: dropped a pin callback for a stale attempt")
            return
        }
        when (state) {
            State.WAITING_DIALOG -> {
                pauseTimer?.cancel(); pauseTimer = null
                added(METHOD_PIN)
            }
            State.AWAY -> pinned = true
            State.GRACE -> {
                cancelGrace()
                added(METHOD_PIN)
            }
            State.HOWTO -> {
                // A late pin, after the silent-launcher timer had already shown the how-to.
                effects.closeHowto()
                track(HOWTO_DISMISSED_EVENT, mapOf("via" to "detected"))
                added(METHOD_PIN)
            }
            else -> Unit
        }
    }

    /** The silent-launcher timer: the request was "sent" but no dialog ever paused the activity. */
    fun onPauseTimeout() {
        if (state != State.WAITING_DIALOG) return
        pauseTimer = null
        track(PIN_UNAVAILABLE_EVENT, mapOf("reason" to "silent_launcher"))
        enterHowto()
    }

    /** A grace-window poll: the callback is not guaranteed, the id set is the other witness. */
    fun onGraceTick() {
        if (state != State.GRACE) return
        if (grew()) {
            cancelGrace()
            added(METHOD_PIN)
        } else {
            scheduleTick()
        }
    }

    /** The grace window ended: one final re-read decides. */
    fun onGraceEnd() {
        if (state != State.GRACE) return
        cancelGrace()
        if (grew()) added(METHOD_PIN) else notAdded(METHOD_PIN)
    }

    /** The how-to's Done button (`next` / `continue`). */
    fun onDoneCta() {
        if (state != State.HOWTO) return
        val now = provider?.let { platform.widgetIds(it) }
        val grew = isGrowth(now)
        if (grew) {
            track(ADDED_EVENT, mapOf("method" to METHOD_HOWTO, "provider" to provider))
        } else {
            track(NOT_ADDED_EVENT, mapOf("method" to METHOD_HOWTO, "detection" to detection(now)))
        }
        effects.closeHowto()
        track(HOWTO_DISMISSED_EVENT, mapOf("via" to "cta"))
        finish(if (grew) STATUS_ADDED else STATUS_NOT_ADDED)
    }

    /**
     * Any other close of the how-to — swipe, back, scrim or a `dismiss` button. The user stays on the
     * step, nothing advances, and the attempt is retired so a later tap starts a new one.
     */
    fun onClose(via: String) {
        if (state != State.HOWTO) return
        track(HOWTO_DISMISSED_EVENT, mapOf("via" to via))
        effects.closeHowto()
        effects.onAttemptRetired()
        reset()
    }

    /** The step host is torn down (activity destroyed, step left). The attempt is discarded: no status, no advance. */
    fun onDestroy() {
        if (state == State.DONE || state == State.IDLE) return
        pauseTimer?.cancel()
        cancelGrace()
        reset()
    }

    // ---- Internals ------------------------------------------------------------------------------

    private fun enterHowto() {
        state = State.HOWTO
        val howto = block?.howto_blocks_android.orEmpty()
        if (howto.isEmpty()) {
            // A flow authored before the Android how-to existed: there is nothing to teach with, so
            // the attempt ends here. The id set is still the witness, so a pin that did land counts.
            ai.appdna.sdk.Log.warning("Add widget: the button has no Android how-to; advancing")
            val now = provider?.let { platform.widgetIds(it) }
            if (isGrowth(now)) {
                track(ADDED_EVENT, mapOf("method" to METHOD_HOWTO, "provider" to provider))
                finish(STATUS_ADDED)
            } else {
                track(NOT_ADDED_EVENT, mapOf("method" to METHOD_HOWTO, "detection" to detection(now)))
                finish(STATUS_NOT_ADDED)
            }
            return
        }
        effects.showHowto(howto)
        track(HOWTO_SHOWN_EVENT, mapOf("block_id" to block?.id))
    }

    private fun added(method: String) {
        track(ADDED_EVENT, mapOf("method" to method, "provider" to provider))
        finish(STATUS_ADDED)
    }

    private fun notAdded(method: String) {
        val now = provider?.let { platform.widgetIds(it) }
        track(NOT_ADDED_EVENT, mapOf("method" to method, "detection" to detection(now)))
        finish(STATUS_NOT_ADDED)
    }

    private fun detection(now: Set<Int>?): String = if (s0 == null || now == null) "unavailable" else "ok"

    private fun grew(): Boolean = isGrowth(provider?.let { platform.widgetIds(it) })

    private fun isGrowth(now: Set<Int>?): Boolean {
        val before = s0 ?: return false
        return now != null && (now - before).isNotEmpty()
    }

    private fun scheduleTick() {
        graceTickTimer = effects.schedule(GRACE_TICK_MS) { onGraceTick() }
    }

    private fun cancelGrace() {
        graceEndTimer?.cancel(); graceEndTimer = null
        graceTickTimer?.cancel(); graceTickTimer = null
    }

    private fun finish(status: String) {
        state = State.DONE
        liveToken = null
        effects.advance(status)
    }

    private fun reset() {
        state = State.IDLE
        liveToken = null
        block = null
        provider = null
        s0 = null
        pinned = false
        pauseTimer = null
    }

    private fun track(name: String, extra: Map<String, Any?>) {
        val props = LinkedHashMap<String, Any>()
        props["flow_id"] = flowId
        props["step_id"] = stepId
        for ((k, v) in extra) if (v != null) props[k] = v
        effects.track(name, props)
    }

    companion object {
        const val ACTION_NAME = "add_widget"
        const val STATUS_KEY = "widget_status"
        const val STATUS_ADDED = "added"
        const val STATUS_NOT_ADDED = "not_added"
        const val STATUS_UNAVAILABLE = "unavailable"

        const val METHOD_PIN = "pin"
        const val METHOD_HOWTO = "howto"

        const val ADD_TAPPED_EVENT = "widget_add_tapped"
        const val PIN_REQUESTED_EVENT = "widget_pin_requested"
        const val PIN_UNAVAILABLE_EVENT = "widget_pin_unavailable"
        const val HOWTO_SHOWN_EVENT = "widget_howto_shown"
        const val HOWTO_DISMISSED_EVENT = "widget_howto_dismissed"
        const val ADDED_EVENT = "widget_added"
        const val NOT_ADDED_EVENT = "widget_not_added"
        const val ADD_UNAVAILABLE_EVENT = "widget_add_unavailable"

        /**
         * The silent-launcher timer: how long after a request the launcher has to pause the activity
         * with its dialog before the SDK concludes no dialog is coming and shows the how-to.
         *
         * Measured 2026-11-12 on the API 34 emulator (Pixel launcher), request → ON_PAUSE, N = 15
         * (10 accepted, 3 cancelled, 1 double tap, 1 Multi-buttons child): 18–145 ms, slowest 145 ms.
         * T_PAUSE = max(3 × 145, 1,500) = 1,500 ms.
         */
        const val PAUSE_TIMEOUT_MS = 1_500L

        /**
         * The grace window after the activity returns from the pin dialog with no callback yet: the
         * callback is not guaranteed, so the id set is polled every [GRACE_TICK_MS] until it ends.
         *
         * Measured 2026-11-12 on the API 34 emulator, ON_RESUME → success callback for an accepted
         * pin, N = 11 (10 accepted runs + 1 Multi-buttons child): the callback arrived BEFORE the
         * resume every time, 147–292 ms earlier, so the attempt went AWAY → pinned → RESUME and the
         * grace window was never needed. G = clamp(2 × slowest, 500, 3,000) = 500 ms (the floor).
         */
        const val GRACE_MS = 500L
        const val GRACE_TICK_MS = 250L

    }
}

/** A cancellable timer handed back by [WidgetPinEffects.schedule]. */
internal fun interface WidgetPinTimer {
    fun cancel()
}

/** The platform the attempt asks. The production one wraps `AppWidgetManager`; tests pass fakes. */
internal interface WidgetPinPlatform {
    val sdkInt: Int

    /** Fully qualified class names of the host package's home-screen widget providers, in any order. */
    fun homeScreenProviders(): List<String>

    /** `isRequestPinAppWidgetSupported()`. Asked only on API 26+. */
    fun isRequestPinSupported(): Boolean

    /**
     * `requestPinAppWidget` for [providerClass], with a success callback carrying [token].
     * True means the request was SENT (not that the widget is pinned). May throw [IllegalStateException].
     */
    fun requestPin(providerClass: String, token: String): Boolean

    /** `getAppWidgetIds` for [providerClass], or null when it cannot be read. */
    fun widgetIds(providerClass: String): Set<Int>?
}

/** What an attempt does to the world. The step implements it; tests record it. */
internal interface WidgetPinEffects {
    /** The step's required-field gate. A refusal shows the step's validation message and returns false. */
    fun gatePasses(): Boolean
    fun track(name: String, properties: Map<String, Any>)
    /** Advance the step once, with `widget_status = status` merged into its data. */
    fun advance(status: String)
    fun showHowto(blocks: List<ContentBlock>)
    fun closeHowto()
    /** A how-to was closed without Done: the press must not route a later advance. */
    fun onAttemptRetired() {}
    fun schedule(delayMs: Long, action: () -> Unit): WidgetPinTimer
    val pauseTimeoutMs: Long get() = WidgetPinAttempt.PAUSE_TIMEOUT_MS
    val graceMs: Long get() = WidgetPinAttempt.GRACE_MS
}

/**
 * Which of the host's widget providers to pin.
 *
 * The platform documents no order for the providers it lists, so they are sorted by fully
 * qualified class name: the choice is then the same on every launch and every device.
 *  - one provider → it;
 *  - several → the one whose class equals the button's `action_value`, or ends with
 *    `.<action_value>` (a simple name); with no value, or no match, the first, with a warning;
 *  - none → null (the app ships no widget).
 */
internal object WidgetProviderDiscovery {
    fun choose(providers: List<String>, actionValue: String?): String? {
        val sorted = providers.distinct().sorted()
        if (sorted.isEmpty()) return null
        if (sorted.size == 1) return sorted[0]
        val wanted = actionValue?.trim()?.takeIf { it.isNotEmpty() }
        if (wanted != null) {
            sorted.firstOrNull { it == wanted || it.endsWith(".$wanted") }?.let { return it }
            ai.appdna.sdk.Log.warning(
                "Add widget: no widget provider matches \"$wanted\"; pinning ${sorted[0]} " +
                    "(the first of ${sorted.size}, by class name)",
            )
        } else {
            ai.appdna.sdk.Log.warning(
                "Add widget: the app has ${sorted.size} widget providers and the button names none; " +
                    "pinning ${sorted[0]} (the first, by class name)",
            )
        }
        return sorted[0]
    }
}
