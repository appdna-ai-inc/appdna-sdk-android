package ai.appdna.sdk.onboarding

import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * onboarding flow selection reaches `attribution.*` rules for a user with no traits.
 *
 * Before, `resolveFlow` evaluated audience rules only when `getUserTraits()` was non-empty, so an app that had not
 * called identify() (the normal first launch, which is exactly when attribution matters) never reached a flow
 * targeted at the install's source and always got the active flow.
 */
class OnboardingAttributionSelectionTest {
    private fun flow(id: String, rules: Any?) = OnboardingFlowConfig(
        id = id, name = id, version = 1, steps = persistentListOf(), settings = OnboardingSettings(), audience_rules = rules,
    )

    private val tiktok = flow(
        "from_example_network",
        mapOf("priority" to 5, "match_mode" to "all", "conditions" to listOf(
            mapOf("trait" to "attribution.network", "operator" to "eq", "value" to "example network"),
        )),
    )
    private val catchAll = flow("default", null)

    @Test
    fun `no traits but a stored attribution - the targeted flow is selected`() {
        ai.appdna.sdk.core.AttributionTargeting.payload = mapOf("network" to "Example Network", "raw" to emptyMap<String, Any>())
        try {
            val picked = OnboardingFlowManager.audienceMatchedFlow(listOf(catchAll, tiktok), emptyMap(), hasAttribution = true)
            assertEquals("from_example_network", picked?.id)
        } finally {
            ai.appdna.sdk.core.AttributionTargeting.payload = null
        }
    }

    @Test
    fun `a different source - no audience match, so the active flow is the fallback`() {
        ai.appdna.sdk.core.AttributionTargeting.payload = mapOf("network" to "Other Network", "raw" to emptyMap<String, Any>())
        try {
            assertNull(OnboardingFlowManager.audienceMatchedFlow(listOf(catchAll, tiktok), emptyMap(), hasAttribution = true))
        } finally {
            ai.appdna.sdk.core.AttributionTargeting.payload = null
        }
    }

    @Test
    fun `nothing to target on - rules are not evaluated`() {
        assertNull(OnboardingFlowManager.audienceMatchedFlow(listOf(catchAll, tiktok), emptyMap(), hasAttribution = false))
    }
}
