package ai.appdna.sdk.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
// NextStepRule.conditions migrated to ImmutableList<Map<String, Any?>>.
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * Coverage for [NextStepRuleEvaluator] + [classifyRuleTarget].
 *
 * iOS reference: `OnboardingRenderer.swift:851-1100`. Each Kotlin test
 * mirrors a behavior the iOS unit suite exercises so cross-platform
 * rule eval is provably identical.
 */
class NextStepRuleEvaluatorTest {

    // -- Helpers --------------------------------------------------------------

    private fun emptyStep(id: String = "step1"): OnboardingStep =
        OnboardingStep(
            id = id,
            type = OnboardingStep.StepType.QUESTION,
            config = StepConfig(),
        )

    private fun responsesFor(stepId: String, fields: Map<String, Any?>): Map<String, Any?> =
        mapOf(stepId to fields)

    // -- Single-condition string ---------------------------------------------

    @Test
    fun `single condition string always matches`() {
        val rule = NextStepRule(condition = "always", target_step_id = "next")
        val ok = NextStepRuleEvaluator.evaluateRule(rule, "step1", emptyMap())
        assertTrue(ok)
    }

    @Test
    fun `no condition no conditions matches as always`() {
        val rule = NextStepRule(target_step_id = "next")
        val ok = NextStepRuleEvaluator.evaluateRule(rule, "step1", emptyMap())
        assertTrue(ok)
    }

    @Test
    fun `empty conditions list matches as always`() {
        val rule = NextStepRule(conditions = persistentListOf(), target_step_id = "next")
        val ok = NextStepRuleEvaluator.evaluateRule(rule, "step1", emptyMap())
        assertTrue(ok)
    }

    // -- Single condition map: answer_equals ---------------------------------

    @Test
    fun `single answer_equals condition truthy`() {
        val cond = mapOf<String, Any?>("type" to "answer_equals", "answer_key" to "color", "value" to "blue")
        val rule = NextStepRule(condition = cond, target_step_id = "blue_path")
        val ok = NextStepRuleEvaluator.evaluateRule(
            rule = rule,
            stepId = "step1",
            responses = responsesFor("step1", mapOf("color" to "blue")),
        )
        assertTrue(ok)
    }

    @Test
    fun `single answer_equals condition falsy`() {
        val cond = mapOf<String, Any?>("type" to "answer_equals", "answer_key" to "color", "value" to "blue")
        val rule = NextStepRule(condition = cond, target_step_id = "blue_path")
        val ok = NextStepRuleEvaluator.evaluateRule(
            rule = rule,
            stepId = "step1",
            responses = responsesFor("step1", mapOf("color" to "red")),
        )
        assertFalse(ok)
    }

    @Test
    fun `answer_equals against array response (multiselect) matches if list contains value`() {
        val cond = mapOf<String, Any?>("type" to "answer_equals", "answer_key" to "tags", "value" to "fitness")
        val rule = NextStepRule(condition = cond, target_step_id = "next")
        val ok = NextStepRuleEvaluator.evaluateRule(
            rule = rule,
            stepId = "step1",
            responses = responsesFor("step1", mapOf("tags" to listOf("fitness", "diet"))),
        )
        assertTrue(ok)
    }

    @Test
    fun `answer_contains substring matches`() {
        val cond = mapOf<String, Any?>("type" to "answer_contains", "answer_key" to "summary", "value" to "fast")
        val rule = NextStepRule(condition = cond, target_step_id = "next")
        val ok = NextStepRuleEvaluator.evaluateRule(
            rule = rule,
            stepId = "step1",
            responses = responsesFor("step1", mapOf("summary" to "fastest path forward")),
        )
        assertTrue(ok)
    }

    // -- Multi-conditions AND -------------------------------------------------

    @Test
    fun `multi conditions AND all true returns true`() {
        val rule = NextStepRule(
            conditions = listOf(
                mapOf("type" to "answer_equals", "answer_key" to "a", "value" to 1),
                mapOf("type" to "answer_equals", "answer_key" to "b", "value" to 2),
            ).toImmutableList(),
            logic = "and",
            target_step_id = "next",
        )
        val ok = NextStepRuleEvaluator.evaluateRule(
            rule = rule,
            stepId = "step1",
            responses = responsesFor("step1", mapOf("a" to 1, "b" to 2)),
        )
        assertTrue(ok)
    }

    @Test
    fun `multi conditions AND one false short-circuits to false`() {
        val rule = NextStepRule(
            conditions = listOf(
                mapOf("type" to "answer_equals", "answer_key" to "a", "value" to 1),
                mapOf("type" to "answer_equals", "answer_key" to "b", "value" to 2),
            ).toImmutableList(),
            logic = "and",
            target_step_id = "next",
        )
        val ok = NextStepRuleEvaluator.evaluateRule(
            rule = rule,
            stepId = "step1",
            responses = responsesFor("step1", mapOf("a" to 1, "b" to 99)),
        )
        assertFalse(ok)
    }

    @Test
    fun `null logic defaults to AND`() {
        val rule = NextStepRule(
            conditions = listOf(
                mapOf("type" to "answer_equals", "answer_key" to "a", "value" to 1),
                mapOf("type" to "answer_equals", "answer_key" to "b", "value" to 2),
            ).toImmutableList(),
            logic = null,
            target_step_id = "next",
        )
        val ok = NextStepRuleEvaluator.evaluateRule(
            rule = rule,
            stepId = "step1",
            responses = responsesFor("step1", mapOf("a" to 1, "b" to 2)),
        )
        assertTrue(ok)
    }

    // -- Multi-conditions OR --------------------------------------------------

    @Test
    fun `multi conditions OR one true returns true`() {
        val rule = NextStepRule(
            conditions = listOf(
                mapOf("type" to "answer_equals", "answer_key" to "a", "value" to 99),
                mapOf("type" to "answer_equals", "answer_key" to "b", "value" to 2),
            ).toImmutableList(),
            logic = "or",
            target_step_id = "next",
        )
        val ok = NextStepRuleEvaluator.evaluateRule(
            rule = rule,
            stepId = "step1",
            responses = responsesFor("step1", mapOf("a" to 1, "b" to 2)),
        )
        assertTrue(ok)
    }

    @Test
    fun `multi conditions OR all false returns false`() {
        val rule = NextStepRule(
            conditions = listOf(
                mapOf("type" to "answer_equals", "answer_key" to "a", "value" to 99),
                mapOf("type" to "answer_equals", "answer_key" to "b", "value" to 99),
            ).toImmutableList(),
            logic = "or",
            target_step_id = "next",
        )
        val ok = NextStepRuleEvaluator.evaluateRule(
            rule = rule,
            stepId = "step1",
            responses = responsesFor("step1", mapOf("a" to 1, "b" to 2)),
        )
        assertFalse(ok)
    }

    // -- Conditions array prefers over single condition ----------------------

    @Test
    fun `conditions array takes precedence when both single and array present`() {
        // Single condition would be true on its own; conditions array is false.
        // iOS prefers the array, so result must be false.
        val rule = NextStepRule(
            condition = mapOf("type" to "always"),
            conditions = listOf(
                mapOf("type" to "answer_equals", "answer_key" to "x", "value" to "yes"),
            ).toImmutableList(),
            target_step_id = "next",
        )
        val ok = NextStepRuleEvaluator.evaluateRule(
            rule = rule,
            stepId = "step1",
            responses = responsesFor("step1", mapOf("x" to "no")),
        )
        assertFalse(ok)
    }

    // -- Operator coverage ----------------------------------------------------

    @Test
    fun `not_empty matches non-empty string`() {
        val cond = mapOf<String, Any?>("type" to "not_empty", "answer_key" to "name")
        val rule = NextStepRule(condition = cond, target_step_id = "next")
        assertTrue(
            NextStepRuleEvaluator.evaluateRule(
                rule, "step1", responsesFor("step1", mapOf("name" to "ada"))
            )
        )
        assertFalse(
            NextStepRuleEvaluator.evaluateRule(
                rule, "step1", responsesFor("step1", mapOf("name" to ""))
            )
        )
    }

    @Test
    fun `empty matches missing key`() {
        val cond = mapOf<String, Any?>("type" to "empty", "answer_key" to "name")
        val rule = NextStepRule(condition = cond, target_step_id = "next")
        assertTrue(
            NextStepRuleEvaluator.evaluateRule(
                rule, "step1", responsesFor("step1", emptyMap())
            )
        )
    }

    @Test
    fun `answer_not_equals against array returns true when value absent`() {
        val cond = mapOf<String, Any?>("type" to "answer_not_equals", "answer_key" to "tags", "value" to "vegan")
        val rule = NextStepRule(condition = cond, target_step_id = "next")
        val ok = NextStepRuleEvaluator.evaluateRule(
            rule, "step1", responsesFor("step1", mapOf("tags" to listOf("fitness", "diet")))
        )
        assertTrue(ok)
    }

    // -- Number equality ------------------------------------------------------

    @Test
    fun `number equality across boxed types`() {
        val cond = mapOf<String, Any?>("type" to "answer_equals", "answer_key" to "age", "value" to 30)
        val rule = NextStepRule(condition = cond, target_step_id = "next")
        // Long stored vs Int rule value should still be equal numerically.
        val ok = NextStepRuleEvaluator.evaluateRule(
            rule, "step1", responsesFor("step1", mapOf("age" to 30L))
        )
        assertTrue(ok)
    }

    // -- Target classification ------------------------------------------------

    // -- Last-step fallback event-name contract -------------------------------

    @Test
    fun `last-step fallback event name matches spec`() {
        // Requires this exact event name so ETL can
        // distinguish "natural end" from "all rules failed". Locking it
        // in a unit test prevents accidental rename drift.
        assertEquals("flow_completed_via_fallback", FLOW_COMPLETED_VIA_FALLBACK_EVENT)
    }

    @Test
    fun `classifyRuleTarget covers every iOS prefix`() {
        assertEquals(RuleTarget.Empty, classifyRuleTarget(""))
        assertEquals(RuleTarget.Empty, classifyRuleTarget(null))
        assertEquals(RuleTarget.PaywallTrigger("paywall_trigger_42"), classifyRuleTarget("paywall_trigger_42"))
        assertEquals(RuleTarget.EndFlow, classifyRuleTarget("end_1"))
        assertEquals(RuleTarget.Permission("notifications"), classifyRuleTarget("permission_notifications"))
        assertEquals(RuleTarget.Screen("home"), classifyRuleTarget("screen_home"))
        assertEquals(RuleTarget.SubFlow("retention"), classifyRuleTarget("flow_retention"))
        assertEquals(RuleTarget.Step("step3"), classifyRuleTarget("step3"))
    }

    // -- previous_step_* operators (7) ----------

    @Test
    fun `previous_step_equals matches when prev id matches`() {
        // 7 — mirrors iOS
        // OnboardingRenderer.swift:1065-1070.
        val cond = mapOf<String, Any?>("type" to "previous_step_equals", "value" to "step_a")
        val rule = NextStepRule(condition = cond, target_step_id = "paywall_a")
        val ok = NextStepRuleEvaluator.evaluateRule(
            rule = rule,
            stepId = "step1",
            responses = emptyMap(),
            previousStepId = "step_a",
        )
        assertTrue(ok)
    }

    @Test
    fun `previous_step_equals does not match on mismatch`() {
        val cond = mapOf<String, Any?>("type" to "previous_step_equals", "value" to "step_a")
        val rule = NextStepRule(condition = cond, target_step_id = "paywall_a")
        val ok = NextStepRuleEvaluator.evaluateRule(
            rule = rule,
            stepId = "step1",
            responses = emptyMap(),
            previousStepId = "step_b",
        )
        assertFalse(ok)
    }

    @Test
    fun `previous_step_equals does not match when no prev id`() {
        val cond = mapOf<String, Any?>("type" to "previous_step_equals", "value" to "step_a")
        val rule = NextStepRule(condition = cond, target_step_id = "paywall_a")
        val ok = NextStepRuleEvaluator.evaluateRule(
            rule = rule,
            stepId = "step1",
            responses = emptyMap(),
            previousStepId = null,
        )
        assertFalse(ok)
    }

    @Test
    fun `previous_step_in matches when prev id in array`() {
        // 7 — mirrors iOS
        // OnboardingRenderer.swift:1071-1088.
        val cond = mapOf<String, Any?>(
            "type" to "previous_step_in",
            "previous_step_ids" to listOf("step_a", "step_b", "step_c"),
        )
        val rule = NextStepRule(condition = cond, target_step_id = "paywall_b")
        val ok = NextStepRuleEvaluator.evaluateRule(
            rule = rule,
            stepId = "step1",
            responses = emptyMap(),
            previousStepId = "step_b",
        )
        assertTrue(ok)
    }

    @Test
    fun `previous_step_in matches via legacy CSV value`() {
        // iOS legacy fallback: comma-separated string in `value`.
        val cond = mapOf<String, Any?>(
            "type" to "previous_step_in",
            "value" to "step_a, step_b , step_c",
        )
        val rule = NextStepRule(condition = cond, target_step_id = "paywall_c")
        val ok = NextStepRuleEvaluator.evaluateRule(
            rule = rule,
            stepId = "step1",
            responses = emptyMap(),
            previousStepId = "step_b",
        )
        assertTrue(ok)
    }

    @Test
    fun `previous_step_in does not match when prev id absent`() {
        val cond = mapOf<String, Any?>(
            "type" to "previous_step_in",
            "previous_step_ids" to listOf("step_a", "step_b"),
        )
        val rule = NextStepRule(condition = cond, target_step_id = "paywall_b")
        val ok = NextStepRuleEvaluator.evaluateRule(
            rule = rule,
            stepId = "step1",
            responses = emptyMap(),
            previousStepId = "step_z",
        )
        assertFalse(ok)
    }
}
