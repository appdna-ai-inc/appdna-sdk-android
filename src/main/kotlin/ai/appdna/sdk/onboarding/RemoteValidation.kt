package ai.appdna.sdk.onboarding

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import ai.appdna.sdk.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Live validation — live validation of a single-line text input through the host app (`onValidateInput`).
 *
 * The SDK opens no connection of its own: the host answers whether a value is valid. This file holds the whole
 * behaviour, so the shared fixtures (`step_advance/remote_validation_*`) drive the SAME code the step runs. iOS mirrors
 * it in `Onboarding/RemoteValidation.swift`.
 */

/**
 * What [AppDNAOnboardingDelegate.onValidateInput] returns. `null` means "no answer": the field ends unavailable and,
 * unless its author chose "block", the user can continue.
 */
sealed class InputValidationResult {
    /** The value is accepted. [message], when set, is shown under the field in place of the authored valid text. */
    data class Valid @JvmOverloads constructor(val message: String? = null) : InputValidationResult()

    /** The value is refused. [message], when set, is shown under the field in place of the authored invalid text. */
    data class Invalid @JvmOverloads constructor(val message: String? = null) : InputValidationResult()

    /** The delegate's default body, and a wrapper with no handler: the host does not validate. */
    internal object NotImplemented : InputValidationResult()

    companion object {
        /** The status a wrapper bridge sends when the host registered no `onValidateInput` handler. */
        const val NOT_IMPLEMENTED_STATUS = "not_implemented"

        /**
         * The ONE decoder for a reply that crossed a wrapper bridge: `{"status": "valid"|"invalid", "message"?: String}`.
         * The status is case-sensitive; any other shape is null. A non-string or empty message is no message.
         * `{"status": "not_implemented"}` (the wrapper's "no handler") tells the step the host does not implement
         * the hook, so the field behaves as if there were no delegate.
         */
        @JvmStatic
        fun decodeBridgeReply(reply: Any?): InputValidationResult? {
            val map = reply as? Map<*, *> ?: return null
            val status = map["status"] as? String ?: return null
            val message = (map["message"] as? String)?.takeIf { it.isNotEmpty() }
            return when (status) {
                "valid" -> Valid(message)
                "invalid" -> Invalid(message)
                NOT_IMPLEMENTED_STATUS -> NotImplemented
                else -> null
            }
        }

        /**
         * How long a wrapper bridge should wait for its host: the field's own timeout plus a second, so the step's
         * timer (which ends the check) always decides first. Read inside `onValidateInput`; 6 s outside a check.
         */
        @JvmStatic
        suspend fun bridgeTimeoutMs(): Long = (kotlin.coroutines.coroutineContext[RemoteValidationTimeout]?.ms ?: 5000L) + 1000L
    }
}

/** The field's timeout, carried by the coroutine of an `onValidateInput` call (see [InputValidationResult.bridgeTimeoutMs]). */
internal class RemoteValidationTimeout(val ms: Long) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<RemoteValidationTimeout>
}

/** `field_config.remote_validation` on `input_text` / `input_email` / `input_phone` / `input_number` / `input_url`. */
internal data class RemoteValidationConfig(
    val confirmTrigger: Boolean = false,
    val debounceMs: Int = 600,
    val minChars: Int = 1,
    val timeoutMs: Int = 5000,
    /** "If the app can't answer": false = let the user continue (the default, owner decision 165), true = block. */
    val blocksWhenUnavailable: Boolean = false,
    val checkingText: String? = null,
    val validText: String? = null,
    val invalidText: String? = null,
    val unavailableText: String? = null,
    val offlineText: String? = null,
) {
    companion object {
        val VALIDATED_TYPES = setOf("input_text", "input_email", "input_phone", "input_number", "input_url")
        const val BUILT_IN_INVALID = "This value isn't available"
        const val BUILT_IN_UNAVAILABLE = "We couldn't check this right now."
        const val BUILT_IN_OFFLINE = "You're offline. We'll check again when you're back online."

        /** The config of a block, or null when it does not validate remotely. [loc] localises the five texts. */
        fun of(block: ContentBlock, loc: ((String, String) -> String)? = null): RemoteValidationConfig? {
            if (block.type !in VALIDATED_TYPES) return null
            return from(block.field_config?.get("remote_validation"), block.id, loc)
        }

        fun from(raw: Any?, blockId: String = "", loc: ((String, String) -> String)? = null): RemoteValidationConfig? {
            val map = raw as? Map<*, *> ?: return null
            if (map["enabled"] != true) return null
            fun int(key: String, fallback: Int, lo: Int, hi: Int): Int =
                ((map[key] as? Number)?.toInt() ?: fallback).coerceIn(lo, hi)
            fun text(key: String): String? {
                val s = (map[key] as? String)?.takeIf { it.isNotEmpty() } ?: return null
                return loc?.invoke("block.$blockId.remote_validation.$key", s) ?: s
            }
            return RemoteValidationConfig(
                confirmTrigger = map["trigger"] == "confirm",
                debounceMs = int("debounce_ms", 600, 300, 2000),
                minChars = int("min_chars", 1, 1, 50),
                timeoutMs = int("timeout_ms", 5000, 1000, 15000),
                blocksWhenUnavailable = map["when_unavailable"] == "block",
                checkingText = text("checking_text"),
                validText = text("valid_text"),
                invalidText = text("invalid_text"),
                unavailableText = text("unavailable_text"),
                offlineText = text("offline_text"),
            )
        }

        /** Characters as typed, counted in code points (iOS counts Unicode scalars: the same number). */
        fun length(value: String): Int = value.codePointCount(0, value.length)

        /** Offline always says so; any other "no answer" shows a message only when the field blocks on it. */
        fun unavailableMessage(c: RemoteValidationConfig, reason: String?, blocks: Boolean): String? =
            if (reason == "offline") c.offlineText ?: BUILT_IN_OFFLINE
            else if (blocks) c.unavailableText ?: BUILT_IN_UNAVAILABLE
            else null
    }
}

/** One step presentation's live validation: the per-field state machine and the CTA gate. Main thread only. */
internal class RemoteValidationCoordinator(
    /** Runs [fire] after [ms]; returns its cancel. */
    private val schedule: (ms: Long, fire: () -> Unit) -> () -> Unit,
    /** Whether there is a host to ask: false = no delegate, or one known not to implement the hook. */
    var hasHost: () -> Boolean,
    /** Starts the host call for (fieldId, value) with the field's timeout; returns its cancel. `complete` may run synchronously. */
    var startCall: (fieldId: String, value: String, timeoutMs: Long, complete: (HostOutcome) -> Unit) -> () -> Unit,
    var isOnline: () -> Boolean = { true },
    var now: () -> Long = { System.currentTimeMillis() },
    var track: (Map<String, Any>) -> Unit = {},
    var warn: (String) -> Unit = { Log.warning(it) },
) {
    enum class Phase(val wire: String) {
        IDLE("idle"), UNCHECKED("unchecked"), WAITING("waiting"), CHECKING("checking"),
        VALID("valid"), INVALID("invalid"), UNAVAILABLE("unavailable"),
    }

    data class FieldState(
        val phase: Phase = Phase.IDLE,
        val value: String = "",
        /** What is drawn under the field; null = nothing. */
        val message: String? = null,
        /** UNAVAILABLE only: timeout | offline | error | no_delegate. */
        val reason: String? = null,
        val config: RemoteValidationConfig = RemoteValidationConfig(),
    ) {
        val showsProgress: Boolean get() = phase == Phase.WAITING || phase == Phase.CHECKING
    }

    sealed class HostOutcome {
        data class Answered(val result: InputValidationResult?) : HostOutcome()
        object NotImplemented : HostOutcome()
        object Threw : HostOutcome()
    }

    sealed class Decision {
        object Allow : Decision()
        object Wait : Decision()
        data class Block(val message: String) : Decision()
    }

    data class Entry(val id: String, val value: String, val config: RemoteValidationConfig)

    private val fieldStates = mutableStateMapOf<String, FieldState>()
    private val waitingState = mutableStateOf(false)

    val fields: Map<String, FieldState> get() = fieldStates
    /** A CTA tap is waiting for a check to end (the CTA draws its loading state). */
    val ctaWaiting: Boolean get() = waitingState.value

    private class Flight(val seq: Int, var cancel: () -> Unit, var cancelTimeout: () -> Unit, val started: Long)

    private val debounce = HashMap<String, () -> Unit>()
    private val flights = HashMap<String, Flight>()
    private var seq = 0
    private val cache = LinkedHashMap<String, InputValidationResult>()
    private val warned = HashSet<String>()
    private var pendingTap: Pair<() -> List<Entry>, (Decision) -> Unit>? = null
    private var ended = false
    private var reevaluating = false

    fun state(fieldId: String): FieldState? = fieldStates[fieldId]

    /** The user edited the field (or a saved value was restored: [immediate] skips the debounce). */
    fun valueChanged(fieldId: String, value: String, config: RemoteValidationConfig, immediate: Boolean = false) {
        if (ended) return
        fieldStates[fieldId]?.let { if (it.value == value && it.config == config) return }
        cancelDebounce(fieldId)
        cancelFlight(fieldId)
        val base = FieldState(Phase.IDLE, value, null, null, config)
        try {
            if (value.isEmpty() || RemoteValidationConfig.length(value) < config.minChars) {
                fieldStates[fieldId] = base
                return
            }
            if (!hasHost()) {
                fieldStates[fieldId] = base
                endWithoutHost(fieldId)
                return
            }
            cache[cacheKey(fieldId, value)]?.let { hit ->
                fieldStates[fieldId] = apply(hit, base)
                return
            }
            if (config.confirmTrigger && !immediate) {
                fieldStates[fieldId] = base.copy(phase = Phase.UNCHECKED)
                return
            }
            if (immediate) {
                fieldStates[fieldId] = base
                startCheck(fieldId)
                return
            }
            fieldStates[fieldId] = base.copy(phase = Phase.WAITING, message = config.checkingText)
            debounce[fieldId] = schedule(config.debounceMs.toLong()) {
                val s = fieldStates[fieldId]
                if (s?.phase == Phase.WAITING && s.value == value) {
                    debounce.remove(fieldId)
                    startCheck(fieldId)
                }
            }
        } finally {
            reevaluateTap()
        }
    }

    /** Return key or focus lost: a value still waiting is checked now. */
    fun confirm(fieldId: String) {
        val p = fieldStates[fieldId]?.phase ?: return
        if (p != Phase.WAITING && p != Phase.UNCHECKED) return
        cancelDebounce(fieldId)
        startCheck(fieldId)
    }

    /** The connection came back: every field that ended offline is checked again, once. */
    fun connectivityRegained() {
        if (ended) return
        for (id in fieldStates.keys.sorted()) {
            val s = fieldStates[id] ?: continue
            if (s.phase == Phase.UNAVAILABLE && s.reason == "offline") startCheck(id)
        }
    }

    fun presentationEnded() {
        ended = true
        debounce.keys.toList().forEach { cancelDebounce(it) }
        flights.keys.toList().forEach { cancelFlight(it) }
        pendingTap = null
        waitingState.value = false
    }

    /** The decision for the step's fields under live validation, in order; an empty value never blocks. */
    fun decide(entries: List<Entry>): Decision {
        var waits = false
        for (e in entries) {
            if (e.value.isEmpty()) continue
            val s = fieldStates[e.id]
            if (s == null || s.value != e.value) { waits = true; continue }
            when (s.phase) {
                Phase.IDLE -> return Decision.Block(s.config.invalidText ?: RemoteValidationConfig.BUILT_IN_INVALID)
                Phase.UNCHECKED, Phase.WAITING, Phase.CHECKING -> waits = true
                Phase.VALID -> {}
                Phase.INVALID -> return Decision.Block(s.message ?: s.config.invalidText ?: RemoteValidationConfig.BUILT_IN_INVALID)
                Phase.UNAVAILABLE -> if (s.config.blocksWhenUnavailable) {
                    return Decision.Block(
                        RemoteValidationConfig.unavailableMessage(s.config, s.reason, true) ?: RemoteValidationConfig.BUILT_IN_UNAVAILABLE,
                    )
                }
            }
        }
        return if (waits) Decision.Wait else Decision.Allow
    }

    /** The decision without waiting (a path that cannot wait, e.g. `add_widget`): checks still due start now. */
    fun decideNow(entries: List<Entry>): Decision {
        flushDue(entries)
        return decide(entries)
    }

    /**
     * A CTA tap. Starts every check still due, then calls [done] once with Allow or Block(message) — at once when
     * nothing is in progress, else when the checks it waits on end. A tap while one already waits is ignored.
     */
    fun tap(entries: () -> List<Entry>, done: (Decision) -> Unit) {
        if (waitingState.value || ended) return
        flushDue(entries())
        val d = decide(entries())
        if (d != Decision.Wait) { done(d); return }
        pendingTap = entries to done
        waitingState.value = true
    }

    private fun flushDue(entries: List<Entry>) {
        for (e in entries) {
            if (e.value.isEmpty()) continue
            val s = fieldStates[e.id]
            if (s == null || s.value != e.value) {
                valueChanged(e.id, e.value, e.config, immediate = true)
                continue
            }
            if (s.phase == Phase.WAITING || s.phase == Phase.UNCHECKED) {
                cancelDebounce(e.id)
                startCheck(e.id)
            }
        }
    }

    private fun reevaluateTap() {
        if (reevaluating) return
        val pending = pendingTap ?: return
        reevaluating = true
        try {
            flushDue(pending.first())
            if (pendingTap == null) return
            val d = decide(pending.first())
            if (d == Decision.Wait) return
            pendingTap = null
            waitingState.value = false
            pending.second(d)
        } finally {
            reevaluating = false
        }
    }

    private fun startCheck(fieldId: String) {
        val s = fieldStates[fieldId] ?: return
        if (ended) return
        if (!hasHost()) { endWithoutHost(fieldId); return }
        cancelFlight(fieldId)
        seq += 1
        val mySeq = seq
        val flight = Flight(mySeq, {}, {}, now())
        flights[fieldId] = flight
        val cancel = startCall(fieldId, s.value, s.config.timeoutMs.toLong()) { outcome -> complete(fieldId, mySeq, outcome) }
        // Completed synchronously (a host whose default body answered "not implemented"): nothing more to arm.
        if (flights[fieldId]?.seq != mySeq) return
        flight.cancel = cancel
        flight.cancelTimeout = schedule(s.config.timeoutMs.toLong()) { timedOut(fieldId, mySeq) }
        fieldStates[fieldId] = s.copy(phase = Phase.CHECKING, message = s.config.checkingText, reason = null)
    }

    private fun complete(fieldId: String, mySeq: Int, outcome: HostOutcome) {
        val flight = flights[fieldId]
        if (flight == null || flight.seq != mySeq || ended) return
        flights.remove(fieldId)
        flight.cancelTimeout()
        var s = fieldStates[fieldId] ?: return
        try {
            val result = (outcome as? HostOutcome.Answered)?.result
            if (outcome == HostOutcome.NotImplemented || result == InputValidationResult.NotImplemented) {
                endWithoutHost(fieldId)
                return
            }
            s = if (result != null) {
                remember(fieldId, s.value, result)
                apply(result, s)
            } else {
                unavailable(s, if (isOnline()) "error" else "offline")
            }
            fieldStates[fieldId] = s
            emit(fieldId, s, now() - flight.started)
        } finally {
            reevaluateTap()
        }
    }

    private fun timedOut(fieldId: String, mySeq: Int) {
        val flight = flights[fieldId]
        if (flight == null || flight.seq != mySeq || ended) return
        val s = fieldStates[fieldId] ?: return
        flights.remove(fieldId)
        flight.cancel()
        val next = unavailable(s, if (isOnline()) "timeout" else "offline")
        fieldStates[fieldId] = next
        emit(fieldId, next, now() - flight.started)
        reevaluateTap()
    }

    private fun endWithoutHost(fieldId: String) {
        val s = fieldStates[fieldId] ?: return
        val next = unavailable(s, "no_delegate")
        fieldStates[fieldId] = next
        if (warned.add(fieldId)) {
            warn("remote_validation is on for field \"$fieldId\" but the host app does not implement onValidateInput")
            emit(fieldId, next, 0)
        }
    }

    private fun apply(result: InputValidationResult, s: FieldState): FieldState = when (result) {
        is InputValidationResult.Valid -> s.copy(phase = Phase.VALID, reason = null, message = result.message ?: s.config.validText)
        is InputValidationResult.Invalid -> s.copy(
            phase = Phase.INVALID, reason = null,
            message = result.message ?: s.config.invalidText ?: RemoteValidationConfig.BUILT_IN_INVALID,
        )
        InputValidationResult.NotImplemented -> unavailable(s, "no_delegate")
    }

    private fun unavailable(s: FieldState, reason: String): FieldState = s.copy(
        phase = Phase.UNAVAILABLE, reason = reason,
        message = RemoteValidationConfig.unavailableMessage(s.config, reason, s.config.blocksWhenUnavailable),
    )

    private fun emit(fieldId: String, s: FieldState, latencyMs: Long) {
        val props = mutableMapOf<String, Any>("field_id" to fieldId, "status" to s.phase.wire, "latency_ms" to latencyMs)
        s.reason?.let { props["reason"] = it }
        track(props)
    }

    private fun cancelDebounce(fieldId: String) { debounce.remove(fieldId)?.invoke() }

    private fun cancelFlight(fieldId: String) {
        val f = flights.remove(fieldId) ?: return
        f.cancelTimeout()
        f.cancel()
    }

    private fun cacheKey(fieldId: String, value: String) = "$fieldId\u0000$value"

    private fun remember(fieldId: String, value: String, result: InputValidationResult) {
        cache.remove(cacheKey(fieldId, value))
        cache[cacheKey(fieldId, value)] = result
        while (cache.size > CACHE_LIMIT) cache.remove(cache.keys.first())
    }

    companion object {
        const val CACHE_LIMIT = 20

        /** The production wiring: the host's `onValidateInput` on [scope] (the main dispatcher), started
         *  undispatched so a default body that answers "not implemented" is known before any spinner draws. */
        fun forStep(
            scope: CoroutineScope,
            delegate: () -> AppDNAOnboardingDelegate?,
            flowId: String,
            stepId: String,
            isOnline: () -> Boolean,
            track: (Map<String, Any>) -> Unit,
        ): RemoteValidationCoordinator {
            var knownMissing = false
            return RemoteValidationCoordinator(
                schedule = { ms, fire ->
                    val job = scope.launch { delay(ms); fire() }
                    ({ job.cancel() })
                },
                hasHost = { delegate() != null && !knownMissing },
                startCall = { fieldId, value, timeoutMs, complete ->
                    val host = delegate()
                    if (host == null) {
                        complete(HostOutcome.NotImplemented)
                        ({})
                    } else {
                        val job = scope.launch(RemoteValidationTimeout(timeoutMs), start = CoroutineStart.UNDISPATCHED) {
                            val outcome = try {
                                val r = host.onValidateInput(flowId, stepId, fieldId, value)
                                knownMissing = r == InputValidationResult.NotImplemented
                                HostOutcome.Answered(r)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                Log.warning("onValidateInput threw for field \"$fieldId\": ${e.message}")
                                HostOutcome.Threw
                            }
                            complete(outcome)
                        }
                        ({ job.cancel() })
                    }
                },
                isOnline = isOnline,
                track = { props -> track(props + mapOf("flow_id" to flowId, "step_id" to stepId)) },
            )
        }
    }
}

/** The step's live validation, for the text inputs and the CTA at any depth. Null outside an onboarding step. */
internal val LocalRemoteValidation = compositionLocalOf<RemoteValidationCoordinator?> { null }

/**
 * The border a validated text input strokes for its state: invalid → `error_border_color` (#DC2626),
 * valid → `valid_border_color` (#16A34A), in progress → `checking_border_color`, else the focused colour, else the
 * resting rule. Idle, unchecked and unavailable draw the ordinary border ([textInputBorderHex]).
 */
internal fun remoteValidationBorderHex(style: FormFieldBlockStyle?, focused: Boolean, phase: RemoteValidationCoordinator.Phase?): String {
    fun authored(s: String?) = s?.takeIf { it.isNotEmpty() }
    return when (phase) {
        RemoteValidationCoordinator.Phase.INVALID -> authored(style?.error_border_color) ?: "#DC2626"
        RemoteValidationCoordinator.Phase.VALID -> authored(style?.valid_border_color) ?: "#16A34A"
        RemoteValidationCoordinator.Phase.WAITING, RemoteValidationCoordinator.Phase.CHECKING ->
            authored(style?.checking_border_color) ?: authored(style?.focused_border_color)
                ?: textInputBorderHex(style, focused = focused, hasError = false)
        else -> textInputBorderHex(style, focused = focused, hasError = false)
    }
}

/** The colour of the message under a validated field. */
internal fun remoteValidationMessageHex(style: FormFieldBlockStyle?, phase: RemoteValidationCoordinator.Phase, blocksWhenUnavailable: Boolean): String {
    fun authored(s: String?) = s?.takeIf { it.isNotEmpty() }
    return when {
        phase == RemoteValidationCoordinator.Phase.INVALID -> authored(style?.error_text_color) ?: "#DC2626"
        phase == RemoteValidationCoordinator.Phase.VALID ->
            authored(style?.valid_text_color) ?: authored(style?.valid_border_color) ?: "#16A34A"
        phase == RemoteValidationCoordinator.Phase.UNAVAILABLE && blocksWhenUnavailable -> authored(style?.error_text_color) ?: "#DC2626"
        else -> "#6B7280"
    }
}

/** Whether the step's CTA draws in its disabled colour: a field is invalid, or unavailable under "block". */
internal fun RemoteValidationCoordinator.blocksCta(blocks: List<ContentBlock>, inputValues: Map<String, Any>): Boolean {
    for (b in blocks) {
        val config = RemoteValidationConfig.of(b) ?: continue
        val id = b.field_id ?: b.id
        val s = fields[id] ?: continue
        val v = inputValues[id] as? String
        if (v.isNullOrEmpty() || v != s.value) continue
        if (s.phase == RemoteValidationCoordinator.Phase.INVALID) return true
        if (s.phase == RemoteValidationCoordinator.Phase.UNAVAILABLE && config.blocksWhenUnavailable) return true
    }
    return false
}
