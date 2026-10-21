package ai.appdna.sdk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1064 — THE OPERATOR VOCABULARY, AND WHAT AN UNKNOWN ONE MUST DO.
 *
 * The console's survey schema allowed `neq`, `in` and `not_in`, which no SDK implemented. iOS
 * decoded an unrecognised operator to `eq`, so a `neq` condition selected precisely the audience
 * the author had excluded; Android answered false. Both now evaluate the full set, and both answer
 * false for anything outside it.
 *
 * Mirrors `TriggerOperatorTests.swift`.
 */
class TriggerOperatorTest {

    @Test fun `eq and neq are text comparisons, and are exact opposites`() {
        assertTrue(TriggerOperator.matches("eq", "pro", "pro"))
        assertFalse(TriggerOperator.matches("eq", "pro", "free"))
        // 🔴 The pair that was inverted on iOS: neq must be the complement of eq, never its twin.
        assertFalse(TriggerOperator.matches("neq", "pro", "pro"))
        assertTrue(TriggerOperator.matches("neq", "pro", "free"))
        // Numbers compare as text here, so 30 and "30" are equal.
        assertTrue(TriggerOperator.matches("eq", 30, "30"))
    }

    @Test fun `the numeric operators need both sides numeric`() {
        assertTrue(TriggerOperator.matches("gte", 30, 30))
        assertTrue(TriggerOperator.matches("gt", 31, 30))
        assertFalse(TriggerOperator.matches("gt", 30, 30))
        assertTrue(TriggerOperator.matches("lte", "29", 30))
        assertTrue(TriggerOperator.matches("lt", 29.5, 30))
        // A non-numeric side never matches rather than comparing as text.
        assertFalse(TriggerOperator.matches("gte", "pro", 30))
        assertFalse(TriggerOperator.matches("gte", 30, "pro"))
    }

    @Test fun `contains is a substring of the text`() {
        assertTrue(TriggerOperator.matches("contains", "pro_annual", "annual"))
        assertFalse(TriggerOperator.matches("contains", "pro_annual", "monthly"))
    }

    @Test fun `in and not_in read a list, and a single value as a one-element list`() {
        assertTrue(TriggerOperator.matches("in", "pro", listOf("free", "pro")))
        assertFalse(TriggerOperator.matches("in", "team", listOf("free", "pro")))
        assertFalse(TriggerOperator.matches("not_in", "pro", listOf("free", "pro")))
        assertTrue(TriggerOperator.matches("not_in", "team", listOf("free", "pro")))
        // A non-list value is the author's one-element list, not a condition that never matches.
        assertTrue(TriggerOperator.matches("in", "pro", "pro"))
        assertFalse(TriggerOperator.matches("not_in", "pro", "pro"))
        // Members compare as text, so a numeric list works.
        assertTrue(TriggerOperator.matches("in", 3, listOf(1, 2, 3)))
    }

    @Test fun `an unknown operator never matches, and a null value never matches`() {
        /*
         * 🔴 THE DEFECT, FROM THE ANDROID SIDE. Whatever an unknown operator does, it must not
         * become a DIFFERENT operator — which is what iOS's `?? .eq` did. `false` is the safe
         * reading: a survey that never appears is noticed, one shown to the wrong audience is not.
         */
        for (op in listOf("equals", ">=", "eq ", "EQ", "", "regex")) {
            assertFalse("`$op` must not match", TriggerOperator.matches(op, "pro", "pro"))
        }
        // control: the SUPPORTED spelling of the same comparison does match, so the loop above
        // is measuring the operator and not the operands.
        assertTrue(TriggerOperator.matches("eq", "pro", "pro"))
        // A condition with no value cannot be evaluated.
        assertFalse(TriggerOperator.matches("eq", "pro", null))
    }

    @Test fun `the supported set is exactly what matches`() {
        // Every advertised operator evaluates; nothing outside it does. This is the parity contract
        // the console schema and the docs are written against.
        assertEquals(
            setOf("eq", "neq", "gte", "lte", "gt", "lt", "contains", "in", "not_in"),
            TriggerOperator.supported,
        )
        for (op in TriggerOperator.supported) {
            // Each one answers SOMETHING without throwing for a plausible pair.
            TriggerOperator.matches(op, "3", listOf("3"))
        }
    }
}
