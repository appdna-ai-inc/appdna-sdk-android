package ai.appdna.sdk.core

data class AudienceRule(
    val trait: String,
    // "equals", "not_equals", "gt", "lt", "contains", "exists", "in", "not_in", "between"
    val operator: String,
    val value: Any? = null,
    // `between` bounds. Authored as sibling `min` / `max` keys rather than inside `value`.
    val min: Any? = null,
    val max: Any? = null,
) {
    companion object {
        fun fromMap(data: Map<String, Any?>): AudienceRule {
            return AudienceRule(
                // The console writes `field`; older payloads (and the iOS models) write `trait`.
                // Reading only one of them produced a rule on trait "" that matched nothing —
                // or, for `between`, matched everything (see the vacuous-pass note below).
                trait = (data["trait"] as? String)?.takeIf { it.isNotBlank() }
                    ?: (data["field"] as? String)
                    ?: "",
                operator = data["operator"] as? String ?: "equals",
                // `in` / `not_in` rules are authored with a `values` array; `equals` uses `value`.
                value = data["value"] ?: data["values"],
                min = data["min"],
                max = data["max"],
            )
        }

        fun fromList(data: List<Map<String, Any?>>?): List<AudienceRule> {
            return data?.map { fromMap(it) } ?: emptyList()
        }
    }
}

data class AudienceRuleSet(
    val priority: Int = 0,
    val conditions: List<AudienceRule> = emptyList(),
    val matchMode: String = "all",  // "all" or "any"
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(data: Map<String, Any?>?): AudienceRuleSet? {
            if (data == null) return null
            // `rules` is the console's key for the same array the SDK models call `conditions`.
            val raw = (data["conditions"] ?: data["rules"]) as? List<Map<String, Any?>>
            return AudienceRuleSet(
                priority = (data["priority"] as? Number)?.toInt() ?: 0,
                conditions = raw?.map { AudienceRule.fromMap(it) } ?: emptyList(),
                matchMode = data["match_mode"] as? String ?: "all",
            )
        }

        /**
         * Parse an `audience_rules` payload in EITHER shape the console/backend emits:
         *   - `{ priority, conditions | rules: [...], match_mode }` — a rule set, or
         *   - `[ {trait, operator, value}, ... ]` — a bare rule list (AND across rules).
         *
         * WHY: consumers that only cast to `Map` read the list shape as "no rules", which for a
         * paywall means "matches everybody" at priority 0 — targeting silently disabled.
         * Returns `null` only when there is genuinely nothing to evaluate.
         */
        @Suppress("UNCHECKED_CAST")
        fun fromAny(rules: Any?): AudienceRuleSet? = when (rules) {
            is Map<*, *> -> fromMap(rules as Map<String, Any?>)
            is List<*> -> AudienceRuleSet(
                priority = 0,
                conditions = rules.mapNotNull { (it as? Map<String, Any?>)?.let(AudienceRule::fromMap) },
                matchMode = "all",
            )
            else -> null
        }
    }
}

internal object AudienceRuleEvaluator {

    fun evaluate(ruleSet: AudienceRuleSet?, userTraits: Map<String, Any>): Boolean {
        if (ruleSet == null || ruleSet.conditions.isEmpty()) return true

        return if (ruleSet.matchMode == "all") {
            ruleSet.conditions.all { evaluateRule(it, userTraits) }
        } else {
            ruleSet.conditions.any { evaluateRule(it, userTraits) }
        }
    }

    fun evaluate(rules: List<AudienceRule>?, userTraits: Map<String, Any>): Boolean {
        if (rules.isNullOrEmpty()) return true
        return rules.all { evaluateRule(it, userTraits) }
    }

    private fun evaluateRule(rule: AudienceRule, userTraits: Map<String, Any>): Boolean {
        val traitValue = userTraits[rule.trait]

        return when (rule.operator) {
            "equals", "eq" -> ConditionEvaluator.valuesEqual(traitValue, rule.value)
            "not_equals", "neq" -> !ConditionEvaluator.valuesEqual(traitValue, rule.value)
            "gt" -> ConditionEvaluator.compareNumeric(traitValue, rule.value) > 0
            "gte" -> ConditionEvaluator.compareNumeric(traitValue, rule.value) >= 0
            "lt" -> ConditionEvaluator.compareNumeric(traitValue, rule.value) < 0
            "lte" -> ConditionEvaluator.compareNumeric(traitValue, rule.value) <= 0
            "contains" -> {
                when {
                    traitValue is String && rule.value is String -> traitValue.contains(rule.value, ignoreCase = true)
                    traitValue is List<*> && rule.value is String -> traitValue.contains(rule.value)
                    else -> false
                }
            }
            "exists" -> traitValue != null
            // `in` / `not_in` compared raw `Any?` values, so an Int trait `1` never matched the
            // string values `["1", "2"]` the console writes for a numeric field. Route through
            // ConditionEvaluator so numbers and their string spellings coerce like everywhere else.
            "in" -> {
                if (traitValue == null) return false
                val arr = rule.value as? List<*> ?: return false
                arr.any { ConditionEvaluator.valuesEqual(traitValue, it) }
            }
            "not_in" -> {
                val arr = rule.value as? List<*> ?: return true
                arr.none { ConditionEvaluator.valuesEqual(traitValue, it) }
            }
            // `between` was never implemented, so it fell to the `else -> true` catch-all below
            // and passed VACUOUSLY — every user matched an audience that was meant to be bounded.
            // Inclusive on both ends; a non-numeric trait or a missing bound fails closed.
            "between" -> {
                val v = ConditionEvaluator.toDoubleOrNull(traitValue) ?: return false
                var lo = ConditionEvaluator.toDoubleOrNull(rule.min)
                var hi = ConditionEvaluator.toDoubleOrNull(rule.max)
                // Read a 2-element `value` list ONLY when NEITHER min nor max yields a
                // number, so sibling min/max keys WIN when present. iOS AudienceRule.swift has this
                // exact precedence; the old betweenBounds preferred the list unconditionally, so
                // {min:18,max:65,value:[30,40]} used [30,40] on Android but [18,65] on iOS.
                if (lo == null && hi == null) {
                    val list = rule.value as? List<*>
                    if (list != null && list.size >= 2) {
                        lo = ConditionEvaluator.toDoubleOrNull(list[0])
                        hi = ConditionEvaluator.toDoubleOrNull(list[1])
                    }
                }
                // Allow a ONE-SIDED bound (e.g. {min:18} with no max); iOS checks only
                // the present bound. Inclusive on both ends.
                when {
                    lo == null && hi == null -> false
                    lo != null && v < lo -> false
                    hi != null && v > hi -> false
                    else -> true
                }
            }
            else -> true
        }
    }
}
