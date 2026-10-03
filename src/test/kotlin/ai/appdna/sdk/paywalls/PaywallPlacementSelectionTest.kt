package ai.appdna.sdk.paywalls

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `audience_rules` in its LIST shape used to fail the `Map` cast in
 * `presentByPlacement`, so the paywall was treated as untargeted (matched everybody) at priority 0.
 */
class PaywallPlacementSelectionTest {

    private fun paywall(id: String, placement: String, audienceRules: Any?): PaywallConfig =
        PaywallConfigParser.parseSinglePaywall(
            id,
            buildMap {
                put("name", id)
                put("placement", placement)
                put("layout", mapOf("type" to "stack"))
                put("sections", emptyList<Any>())
                if (audienceRules != null) put("audience_rules", audienceRules)
            },
        )!!

    @Test
    fun `list-shaped audience_rules are evaluated, not treated as untargeted`() {
        val targeted = paywall(
            "pro_only",
            "home",
            listOf(mapOf("trait" to "plan", "operator" to "equals", "value" to "pro")),
        )
        val fallback = paywall("fallback", "home", null)

        // A free user must NOT match the pro-only paywall — before the fix the failed cast made the
        // rule set null, which reads as "no targeting" and the pro paywall matched everyone.
        val free = selectPaywallForPlacement(listOf(targeted, fallback), "home", mapOf("plan" to "free"))
        assertEquals("fallback", free?.id)

        val pro = selectPaywallForPlacement(listOf(targeted, fallback), "home", mapOf("plan" to "pro"))
        assertEquals("pro_only", pro?.id)
    }

    @Test
    fun `priority from the map shape orders candidates highest-first`() {
        val low = paywall(
            "low",
            "home",
            mapOf("priority" to 1, "conditions" to emptyList<Any>()),
        )
        val high = paywall(
            "high",
            "home",
            mapOf("priority" to 9, "conditions" to emptyList<Any>()),
        )
        val chosen = selectPaywallForPlacement(listOf(low, high), "home", emptyMap())
        assertEquals("high", chosen?.id)
    }

    @Test
    fun `no candidate for the placement returns null`() {
        val pw = paywall("a", "home", null)
        assertEquals(null, selectPaywallForPlacement(listOf(pw), "settings", emptyMap()))
    }
}
