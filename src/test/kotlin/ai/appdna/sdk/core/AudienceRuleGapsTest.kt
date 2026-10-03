package ai.appdna.sdk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audience-rule gaps the shared fixtures exposed once they started calling real
 * SDK code instead of a re-implementation.
 */
class AudienceRuleGapsTest {

    private fun ruleSet(vararg rules: Map<String, Any?>): AudienceRuleSet? =
        AudienceRuleSet.fromAny(rules.toList())

    // --- `between` (was unimplemented → hit `else -> true` and passed vacuously) ---

    @Test
    fun `between fails when the trait is outside the bounds`() {
        val rs = ruleSet(mapOf("trait" to "age", "operator" to "between", "min" to 18, "max" to 30))
        assertFalse(AudienceRuleEvaluator.evaluate(rs, mapOf("age" to 44)))
    }

    @Test
    fun `between matches inside the bounds, inclusive`() {
        val rs = ruleSet(mapOf("trait" to "age", "operator" to "between", "min" to 18, "max" to 30))
        assertTrue(AudienceRuleEvaluator.evaluate(rs, mapOf("age" to 18)))
        assertTrue(AudienceRuleEvaluator.evaluate(rs, mapOf("age" to 30)))
        assertTrue(AudienceRuleEvaluator.evaluate(rs, mapOf("age" to 25)))
    }

    @Test
    fun `between with a missing trait fails closed`() {
        val rs = ruleSet(mapOf("trait" to "age", "operator" to "between", "min" to 18, "max" to 30))
        assertFalse(AudienceRuleEvaluator.evaluate(rs, emptyMap()))
    }

    // --- in / not_in coercion (Int trait vs string values) ---

    @Test
    fun `in coerces a numeric trait against string values`() {
        val rs = ruleSet(mapOf("field" to "tier", "operator" to "in", "values" to listOf("1", "2")))
        assertTrue(AudienceRuleEvaluator.evaluate(rs, mapOf("tier" to 1)))
        assertFalse(AudienceRuleEvaluator.evaluate(rs, mapOf("tier" to 3)))
    }

    @Test
    fun `not_in coerces a numeric trait against string values`() {
        val rs = ruleSet(mapOf("field" to "tier", "operator" to "not_in", "values" to listOf("1", "2")))
        assertFalse(AudienceRuleEvaluator.evaluate(rs, mapOf("tier" to 2)))
        assertTrue(AudienceRuleEvaluator.evaluate(rs, mapOf("tier" to 3)))
    }

    // --- key aliases ---

    @Test
    fun `field is an alias for trait and values for value`() {
        val rule = AudienceRule.fromMap(
            mapOf("field" to "plan", "operator" to "in", "values" to listOf("pro", "max")),
        )
        assertEquals("plan", rule.trait)
        assertEquals(listOf("pro", "max"), rule.value)
    }

    @Test
    fun `rules is an alias for conditions`() {
        val rs = AudienceRuleSet.fromMap(
            mapOf(
                "priority" to 7,
                "rules" to listOf(mapOf("field" to "plan", "operator" to "equals", "value" to "pro")),
            ),
        )
        assertEquals(7, rs?.priority)
        assertEquals(1, rs?.conditions?.size)
        assertTrue(AudienceRuleEvaluator.evaluate(rs, mapOf("plan" to "pro")))
        assertFalse(AudienceRuleEvaluator.evaluate(rs, mapOf("plan" to "free")))
    }

    @Test
    fun `fromAny accepts the bare list shape and ANDs across rules`() {
        val rs = AudienceRuleSet.fromAny(
            listOf(
                mapOf("trait" to "plan", "operator" to "equals", "value" to "pro"),
                mapOf("trait" to "age", "operator" to "between", "min" to 18, "max" to 30),
            ),
        )
        assertEquals(2, rs?.conditions?.size)
        assertTrue(AudienceRuleEvaluator.evaluate(rs, mapOf("plan" to "pro", "age" to 22)))
        assertFalse(AudienceRuleEvaluator.evaluate(rs, mapOf("plan" to "pro", "age" to 55)))
    }
}
