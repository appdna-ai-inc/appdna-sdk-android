package ai.appdna.sdk.onboarding

/**
 * Whether a step advances by itself, and after how long.
 *
 * A splash screen and a step whose button was forgotten are the SAME SHAPE on the wire — content
 * blocks, nothing to tap. One is deliberate (held while the app loads, then advanced by the host
 * through `AppDNA.advanceOnboardingStep()`) and the other strands every user on it. Two step-level
 * fields make the intent explicit:
 *
 *   `auto_advance_seconds`  the SDK advances by itself after this many seconds
 *   `wait_for_host: true`   the step is DECLARED host-driven and must never time out
 *
 * 🔴 `wait_for_host` WINS over a seconds value. An author who says the app drives this step has
 * said it must hold; a leftover timer from an earlier edit must not yank the screen out from under
 * the app's own loading. The reverse precedence is unrecoverable from the author's side — they
 * would have to clear a field to make a toggle mean what it says.
 *
 * Mirrors `src/modules/onboarding/validators/step-auto-advance.ts` (console + publish validator)
 * and iOS `StepAutoAdvance.swift`. Pinned on all three by the shared fixture
 * `step_advance/step_auto_advance_delay`.
 */
internal object StepAutoAdvance {

    /**
     * Below this, "advance after N seconds" draws a screen nobody can read, which looks exactly
     * like the step being skipped — so it is treated as NOT SET rather than as "advance instantly".
     */
    const val MIN_SECONDS: Double = 0.1

    /**
     * A splash that outlives a minute is a bug. A hand-written config never passed through the
     * console's schema, so the ceiling is enforced here too: `99999` holds for a minute, not forever.
     */
    const val MAX_SECONDS: Double = 60.0

    /** The delay after which the SDK advances this step by itself, or null when it never does. */
    fun delaySeconds(autoAdvanceSeconds: Double?, waitForHost: Boolean?): Double? {
        if (waitForHost == true) return null
        val raw = autoAdvanceSeconds ?: return null
        if (raw.isNaN() || raw.isInfinite() || raw < MIN_SECONDS) return null
        return minOf(raw, MAX_SECONDS)
    }

    /** The delay for a step as published. */
    fun delaySeconds(step: OnboardingStep): Double? =
        delaySeconds(step.auto_advance_seconds, step.wait_for_host)

    /** [delaySeconds] in milliseconds, for `kotlinx.coroutines.delay`. */
    fun delayMillis(step: OnboardingStep): Long? =
        delaySeconds(step)?.let { (it * 1000).toLong() }
}
