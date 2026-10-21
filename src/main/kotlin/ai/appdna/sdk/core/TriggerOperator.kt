package ai.appdna.sdk.core

/**
 * How a trigger condition's operator is evaluated — the ONE implementation, shared by surveys and
 * in-app messages.
 *
 * 🔴 `SurveyManager` and `MessageManager` each carried a private copy of this `when`, identical by
 * hand. That is the shape that let a shared rule diverge between Swift and Kotlin before, and on
 * #1064 the two platforms had already drifted on what an UNKNOWN operator means: iOS decoded it to
 * `eq` (so a condition authored `neq` selected precisely the audience the author excluded) while
 * Android answered false. A new operator added here now reaches both surfaces or neither.
 *
 * Mirrors iOS `TriggerCondition.ConditionOperator.matches` in `MessageConfig.swift`.
 */
internal object TriggerOperator {

    /** Every operator both platforms evaluate. An operator outside this set never matches. */
    val supported: Set<String> =
        setOf("eq", "neq", "gte", "lte", "gt", "lt", "contains", "in", "not_in")

    /**
     * Does [propValue] satisfy [op] against [condValue]?
     *
     * An unrecognised [op] answers **false**. An unrecognised operator is a configuration error,
     * and the safe reading of one is "this condition does not match" — a survey or message that
     * never appears is noticed and diagnosed, while one shown to the wrong audience is silent.
     */
    fun matches(op: String, propValue: Any, condValue: Any?): Boolean {
        condValue ?: return false
        fun numeric(compare: (Double, Double) -> Boolean): Boolean {
            val p = toDouble(propValue) ?: return false
            val c = toDouble(condValue) ?: return false
            return compare(p, c)
        }
        return when (op) {
            "eq" -> "$propValue" == "$condValue"
            "neq" -> "$propValue" != "$condValue"
            "gte" -> numeric { p, c -> p >= c }
            "lte" -> numeric { p, c -> p <= c }
            "gt" -> numeric { p, c -> p > c }
            "lt" -> numeric { p, c -> p < c }
            "contains" -> "$propValue".contains("$condValue")
            /*
             * `in` / `not_in` take a LIST. A non-list condition value is compared as a single
             * member rather than refused, so `{ operator: "in", value: "pro" }` behaves as `eq` —
             * a one-element list is what the author meant, and the alternative is a condition that
             * silently never matches.
             */
            "in" -> members(condValue).contains("$propValue")
            "not_in" -> !members(condValue).contains("$propValue")
            else -> false
        }
    }

    /** The condition value as string members, for `in` / `not_in`. */
    private fun members(condValue: Any): List<String> = when (condValue) {
        is List<*> -> condValue.map { "$it" }
        is Array<*> -> condValue.map { "$it" }
        else -> listOf("$condValue")
    }

    private fun toDouble(value: Any): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }
}
