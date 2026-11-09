package ai.appdna.sdk.core

internal object ConditionEvaluator {

    fun valuesEqual(lhs: Any?, rhs: Any?): Boolean {
        if (lhs == null && rhs == null) return true
        if (lhs == null || rhs == null) return false

        if (lhs is String && rhs is String) return lhs == rhs
        if (lhs is Boolean && rhs is Boolean) return lhs == rhs

        val ln = toDouble(lhs)
        val rn = toDouble(rhs)
        if (ln != null && rn != null) return ln == rn

        return lhs.toString() == rhs.toString()
    }

    fun compareNumeric(lhs: Any?, rhs: Any?): Int {
        val ln = toDouble(lhs) ?: return 0
        val rn = toDouble(rhs) ?: return 0
        return ln.compareTo(rn)
    }

    fun evaluateCondition(
        type: String,
        variable: String?,
        value: Any?,
        context: Map<String, Any>,
    ): Boolean {
        val resolvedValue = resolveVariable(variable, context)

        return when (type) {
            "always" -> true
            "when_equals" -> valuesEqual(resolvedValue, value)
            "when_not_equals" -> !valuesEqual(resolvedValue, value)
            "when_gt" -> compareNumeric(resolvedValue, value) > 0
            "when_lt" -> compareNumeric(resolvedValue, value) < 0
            "when_not_empty" -> resolvedValue != null && resolvedValue.toString().isNotEmpty()
            "when_empty" -> resolvedValue == null || resolvedValue.toString().isEmpty()
            else -> true
        }
    }

    fun resolveVariable(path: String?, context: Map<String, Any>): Any? {
        if (path.isNullOrEmpty()) return null
        val parts = path.split(".")

        var current: Any? = context
        for (part in parts) {
            current = when (current) {
                is Map<*, *> -> current[part]
                else -> return null
            }
        }
        return current
    }

    /** Numeric coercion shared with [AudienceRuleEvaluator]'s `between` bounds. */
    fun toDoubleOrNull(value: Any?): Double? = toDouble(value)

    private fun toDouble(value: Any?): Double? {
        return when (value) {
            is Double -> value
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            // Through its text, so a slider's 0.1f stays 0.1 and equals an authored 0.1.
            is Float -> value.toString().toDouble()
            is String -> if (DECIMAL.matches(value)) value.toDouble() else null
            is Number -> value.toDouble()
            else -> null
        }
    }

    /**
     * A plain decimal number, the one grammar both platforms share. `toDoubleOrNull` alone also reads "4d" and " 4 ", and
     * Swift's parser reads hex and "nan", so a typed answer could equal a number on one platform only.
     */
    private val DECIMAL = Regex("^[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?$")

    /**
     * An answer or a rule value as text, for a substring comparison: a string as it is, a number in its shortest form
     * (4.0 reads "4"), and null for anything else (a Boolean, a list). iOS `ConditionEvaluator.text`.
     */
    fun text(value: Any?): String? {
        if (value is String) return value
        if (value !is Number) return null
        val d = toDouble(value) ?: return null
        return if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
    }
}
