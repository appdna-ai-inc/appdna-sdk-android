package ai.appdna.sdk.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * paywall_trigger skip-target resolver chain tests (Android).
 *
 * Mirrors the iOS [PaywallTriggerSkipTargetTests] file. The actual resolver
 * lives inside `presentPaywallTriggerNode` (OnboardingActivity.kt:~814)
 * wired into a `routeOutcome` lambda. Test exercises the pure resolver
 * chain that mirrors:
 *
 *     routeOutcome(onSubscribedSkipTarget ?: onSuccessTarget,
 *                  "continue", "user_already_subscribed")
 *
 * End-to-end behavior is covered by the shared behavioral fixtures at
 * `packages/sdk-shared-fixtures/billing/` loaded by [SharedFixtureTest].
 */
class PaywallTriggerSkipTargetTest {

    /**
     * Pure mirror of the resolver chain. Android uses
     * `takeIf { it.isNotBlank() }` to coerce empty/blank strings to null
     * — see OnboardingActivity.kt:633 for the `onSuccessTarget` precedent.
     */
    private fun resolveSkipTarget(
        onSubscribedSkipTarget: String?,
        onSuccessTarget: String?,
    ): String? {
        val resolvedSubSkip = onSubscribedSkipTarget?.takeIf { it.isNotBlank() }
        val resolvedSuccess = onSuccessTarget?.takeIf { it.isNotBlank() }
        return resolvedSubSkip ?: resolvedSuccess
    }

    @Test
    fun `case 1 — explicit on_subscribed_skip_target wins`() {
        val resolved = resolveSkipTarget(
            onSubscribedSkipTarget = "complete_flow",
            onSuccessTarget = "step_some_other",
        )
        assertEquals("complete_flow", resolved)
    }

    @Test
    fun `case 2a — back-compat empty skip target falls back to on_success_target`() {
        val resolved = resolveSkipTarget(
            onSubscribedSkipTarget = "",
            onSuccessTarget = "complete_flow",
        )
        assertEquals("complete_flow", resolved)
    }

    @Test
    fun `case 2b — back-compat null skip target falls back to on_success_target`() {
        val resolved = resolveSkipTarget(
            onSubscribedSkipTarget = null,
            onSuccessTarget = "step_welcome_back",
        )
        assertEquals("step_welcome_back", resolved)
    }

    @Test
    fun `case 3 — both empty or null returns null (legacy edge follow)`() {
        assertNull(resolveSkipTarget(onSubscribedSkipTarget = null, onSuccessTarget = null))
        assertNull(resolveSkipTarget(onSubscribedSkipTarget = "", onSuccessTarget = ""))
        assertNull(resolveSkipTarget(onSubscribedSkipTarget = "", onSuccessTarget = null))
        assertNull(resolveSkipTarget(onSubscribedSkipTarget = null, onSuccessTarget = ""))
    }

    @Test
    fun `case 4 — author opts into chain-of-paywalls via explicit continue`() {
        val resolved = resolveSkipTarget(
            onSubscribedSkipTarget = "continue",
            onSuccessTarget = null,
        )
        assertEquals("continue", resolved)
    }

    @Test
    fun `case 5 — author picks a specific node id`() {
        val resolved = resolveSkipTarget(
            onSubscribedSkipTarget = "step_welcome_back",
            onSuccessTarget = "step_thank_you",
        )
        assertEquals("step_welcome_back", resolved)
    }

    @Test
    fun `case 6 — blank (whitespace) skip target falls back per takeIf isNotBlank`() {
        // Defensive: takeIf { it.isNotBlank() } treats whitespace-only as
        // null. Matches Android pattern at OnboardingActivity.kt:633 for
        // onSuccessTarget, so the resolver chain behavior is consistent
        // across all routing target fields.
        val resolved = resolveSkipTarget(
            onSubscribedSkipTarget = "   ",
            onSuccessTarget = "complete_flow",
        )
        assertEquals("complete_flow", resolved)
    }
}
