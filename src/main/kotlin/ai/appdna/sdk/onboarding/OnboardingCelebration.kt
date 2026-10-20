package ai.appdna.sdk.onboarding

/**
 * WHEN an onboarding flow's authored celebration fires. Mirror of iOS
 * `Onboarding/OnboardingCelebration.swift`, and the same function both renderers call.
 *
 * 🔴 #1026 — the console has offered a "Celebration Effect" in the flow settings panel since it
 * shipped. This file's own `OnboardingConfig.kt` parsed it and no composable read it; iOS did not
 * parse it at all. `ConfettiOverlay` existed on both platforms the whole time.
 *
 * Two moments only: `on_appear` (the flow's first step) and `on_step_complete` (each advance).
 * `on_flow_complete` would be dismissed in the frame it started — the Activity finishes on
 * completion — and `on_purchase` has no meaning inside onboarding; the paywall has its own effect.
 *
 * Shared because the last two particle defects were both two platforms disagreeing about when an
 * effect fires: #997's double gate, and `PaywallConfig.kt` defaulting to a trigger that could not
 * fire. Driven by the `onboarding_celebration_moment` fixture.
 */
object OnboardingCelebration {

    /** The first step is the flow APPEARING; any later one is the previous step COMPLETED. */
    fun moment(stepIndex: Int): String = if (stepIndex <= 0) "on_appear" else "on_step_complete"

    /**
     * The moment that fires for an authored [trigger] at [stepIndex], or `null` when this arrival
     * is not the authored moment.
     *
     * An unset or empty trigger means `on_appear`, matching both decoders' defaults. A trigger the
     * flow cannot honour returns `null` at every index rather than firing at the nearest moment:
     * silently relocating an author's choice is how a celebration ends up in the wrong place with
     * nobody able to explain why.
     */
    fun celebratedMoment(trigger: String?, stepIndex: Int): String? {
        val authored = if (trigger.isNullOrEmpty()) "on_appear" else trigger
        val moment = moment(stepIndex)
        return if (authored == moment) moment else null
    }
}
