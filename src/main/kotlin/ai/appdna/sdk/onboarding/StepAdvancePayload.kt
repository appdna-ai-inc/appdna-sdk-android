package ai.appdna.sdk.onboarding

/**
 * #1094 — what a step advance CARRIES, as a pure function both the renderer and the fixture runner
 * call.
 *
 * 🔴 It exists because a test that re-implements the thing under test can only ever agree with it.
 * `SharedFixtureTest`'s flag-CTA branch assembled this map itself, so `cta_flag_records_and_advances`
 * kept passing with the renderer's own merge deleted — proved by poisoning it: 1878 tests, 0
 * failures, reports confirmed fresh and the poison confirmed on the build machine. The fixture was
 * guarding `OnboardingCTAFlag.parse` and `applyTo` (which it really does drive) and nothing about
 * the payload.
 *
 * The renderer could not be called from a unit test because its advance is a local `fun` inside a
 * composable — `inputValues` and `toggleValues` are `remember(step.id)`. Lifting the assembly out
 * is what makes it reachable, and the `by-construction` ordering below is the behaviour worth
 * pinning:
 *
 *  - inputs first, then `toggle_<key>` entries. iOS prefixes every toggle key so the namespaces
 *    cannot collide with form input keys; Android once merged raw toggle keys, which broke
 *    cross-platform analytics and any `next_step_rules` authored against `toggle_<id>`;
 *  - `extra` LAST, so a form field cannot silently overwrite a flag the CTA recorded;
 *  - an empty map answers `null`, because `onNext(null)` is how the SDK says "this step carried no
 *    answers" and an empty map would be recorded as a step that answered nothing.
 */
internal object StepAdvancePayload {

    fun build(
        inputValues: Map<String, Any>,
        toggleValues: Map<String, Any>,
        extra: Map<String, Any> = emptyMap(),
    ): Map<String, Any>? {
        val merged = mutableMapOf<String, Any>()
        merged.putAll(inputValues)
        for ((key, value) in toggleValues) {
            merged["toggle_$key"] = value
        }
        merged.putAll(extra)
        return if (merged.isEmpty()) null else merged
    }
}
