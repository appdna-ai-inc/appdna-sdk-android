package ai.appdna.sdk.onboarding

/**
 * SPEC-070-A A.21 — Onboarding next-step rule evaluator.
 *
 * Ports iOS `OnboardingRenderer.swift:920-1100` `evaluateRule` +
 * `evaluateCondition` so console-authored conditional navigation
 * (`answer_equals` / `answer_contains` / `always` etc.) renders
 * identically across iOS and Android.
 *
 * This module is intentionally a standalone object (no Compose imports,
 * no Activity dependency) so it is unit-testable on the JVM without
 * Robolectric — see `src/test/kotlin/.../NextStepRuleEvaluatorTest.kt`.
 *
 * iOS reference (per SPEC-070-A §3.1 P0-21):
 *   packages/appdna-sdk-ios/Sources/AppDNASDK/Onboarding/OnboardingRenderer.swift
 *     :920 evaluateRule(_:stepId:)
 *     :994 evaluateCondition(_:responses:step:)
 */
internal object NextStepRuleEvaluator {

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Evaluate a rule against accumulated `responses` for [stepId].
     *
     * - Prefers `conditions[]` (multi) over single `condition`.
     * - Empty conditions OR no condition → matches (`always`).
     * - `logic` defaults to `"and"` when null.
     */
    fun evaluateRule(
        rule: NextStepRule,
        stepId: String,
        responses: Map<String, Any?>,
        step: OnboardingStep? = null,
        previousStepId: String? = null,
        /** Every step in the flow — required to resolve a cross-step `answer_key`. */
        allSteps: List<OnboardingStep>? = null,
    ): Boolean {
        val conditionList: List<Any?> = when {
            !rule.conditions.isNullOrEmpty() -> rule.conditions
            rule.condition != null -> listOf(rule.condition)
            else -> return true // No condition = always match
        }

        if (conditionList.isEmpty()) return true

        val logic = (rule.logic ?: "and").lowercase()
        @Suppress("UNCHECKED_CAST")
        val stepResponses = (responses[stepId] as? Map<String, Any?>) ?: emptyMap()

        for (cond in conditionList) {
            val matches: Boolean = when (cond) {
                is String -> cond == "always"
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    evaluateCondition(
                        cond as Map<String, Any?>, stepResponses, step, previousStepId,
                        responses, allSteps,
                    )
                }
                null -> true
                else -> true
            }

            if (logic == "or" && matches) return true
            if (logic == "and" && !matches) return false
        }

        // Mirror iOS `OnboardingRenderer.swift:951`:
        //   "All passed for and, none passed for or"
        return logic == "and"
    }

    /**
     * Evaluate a single condition dict against step responses.
     *
     * Matches iOS `evaluateCondition` operator set:
     *   `always`, `answer_equals`, `answer_contains`, `answer_not_equals`,
     *   `not_empty`, `empty`, `previous_step_equals`, `previous_step_in`.
     */
    @Suppress("UNCHECKED_CAST")
    /**
     * Where a condition reads its answer from: the leaf field id, that step's response map, and the
     * step whose options define the id↔value aliases.
     */
    private data class ConditionScope(
        val field: String,
        val responses: Map<String, Any?>,
        val step: OnboardingStep?,
    )

    /**
     * Resolve a possibly cross-step `answer_key` ("Screen path 1.select_path", "step1.select_path")
     * to the step that actually owns the answer.
     *
     * Conditions used to read only the CURRENT step's answers, so a rule branching on an earlier
     * screen's choice could never match — and it failed silently: every rule returned false and
     * advance fell through to sequential order, which routinely IS the first rule's target, so a
     * dead branch looked exactly like "it always picks the first option".
     *
     * Resolves by authored name as well as by step id, because the console writes
     * "<StepLabel>.<field_id>" and flows already in production are keyed that way.
     *
     * Mirrors iOS `OnboardingAdvance.resolveConditionScope`.
     */
    private fun resolveConditionScope(
        rawField: String,
        stepResponses: Map<String, Any?>,
        allResponses: Map<String, Any?>,
        currentStep: OnboardingStep?,
        allSteps: List<OnboardingStep>?,
    ): ConditionScope {
        val own = ConditionScope(rawField, stepResponses, currentStep)
        // A key the current step actually answered always wins: a field_id may legitimately contain
        // a dot, and this keeps every pre-existing rule byte-identical.
        if (stepResponses[rawField] != null) return own
        if (allSteps == null) return own
        val dot = rawField.lastIndexOf('.')
        if (dot <= 0 || dot == rawField.length - 1) return own
        val stepRef = rawField.substring(0, dot)
        val leaf = rawField.substring(dot + 1)
        // Id first, then authored name — an id is unambiguous, a name is what the console writes.
        val source = allSteps.firstOrNull { it.id == stepRef }
            ?: allSteps.firstOrNull { it.name == stepRef }
            ?: return own
        @Suppress("UNCHECKED_CAST")
        val sourceResponses = (allResponses[source.id] as? Map<String, Any?>) ?: emptyMap()
        return ConditionScope(leaf, sourceResponses, source)
    }

    private fun evaluateCondition(
        cond: Map<String, Any?>,
        stepResponses: Map<String, Any?>,
        currentStep: OnboardingStep?,
        previousStepId: String? = null,
        allResponses: Map<String, Any?> = emptyMap(),
        allSteps: List<OnboardingStep>? = null,
    ): Boolean {
        val type = cond["type"] as? String ?: return true
        // Console writes `answer_key`; SDK also accepts `field` for back-compat.
        val rawField = cond["answer_key"] as? String ?: cond["field"] as? String ?: ""

        // Re-bound to the original local names so every operator below is unchanged. `step` matters
        // as much as `responses`: the aliases that map an authored "opt_1" onto the stored
        // "wine_tasting" come from the SOURCE step's options, not the step being left.
        val scope = resolveConditionScope(rawField, stepResponses, allResponses, currentStep, allSteps)
        val field = scope.field
        val responses = scope.responses
        val step = scope.step

        // Resolve id↔value aliases lazily (input_select options).
        val (idToVal, valToId) = optionAliases(field, step)

        fun aliasedEquals(expected: String, actual: String): Boolean {
            if (expected == actual) return true
            idToVal[expected]?.let { if (it == actual) return true }
            valToId[actual]?.let { if (it == expected) return true }
            return false
        }

        return when (type) {
            "always" -> true

            "answer_equals" -> {
                val expected = cond["value"]
                val actual = responses[field]
                when {
                    actual is List<*> && expected is String -> {
                        if (actual.filterIsInstance<String>().contains(expected)) return true
                        actual.filterIsInstance<String>().any { aliasedEquals(expected, it) }
                    }
                    isEqual(actual, expected) -> true
                    expected is String && actual is String -> aliasedEquals(expected, actual)
                    else -> false
                }
            }

            "answer_contains" -> {
                val expected = cond["value"] as? String ?: ""
                val actual = responses[field] as? String ?: ""
                if (actual.contains(expected)) return true
                idToVal[expected]?.let { mapped ->
                    if (mapped.isNotEmpty() && actual.contains(mapped)) return true
                }
                false
            }

            "answer_not_equals" -> {
                val expected = cond["value"]
                val actual = responses[field]
                when {
                    actual is List<*> && expected is String ->
                        !actual.filterIsInstance<String>().contains(expected)
                    else -> !isEqual(actual, expected)
                }
            }

            "not_empty" -> {
                // SPEC-401-A R8 — match iOS OnboardingRenderer.swift:1057-1064
                // exactly: only String and nil are checked; any other
                // value (List, Map, scalar) returns true. Previous Android
                // impl explicitly checked List emptiness, so a multi-select
                // answer of `[]` was treated as `not_empty=false` here while
                // iOS treats it as `not_empty=true`. That silently reversed
                // the rule outcome on every multi-select branch.
                val actual = responses[field]
                when (actual) {
                    is String -> actual.isNotEmpty()
                    null -> false
                    else -> true
                }
            }

            "empty" -> {
                // SPEC-401-A R8 — same iOS-parity correction as `not_empty`
                // above: only String + nil are inspected; any other type
                // is treated as non-empty (returns false here).
                val actual = responses[field]
                when (actual) {
                    is String -> actual.isEmpty()
                    null -> true
                    else -> false
                }
            }

            "previous_step_equals" -> {
                // SPEC-070-A finalization P0 audit-7 — ports iOS
                // OnboardingRenderer.swift:1065-1070. Match if the step the
                // user came FROM equals the given step ID. Used for
                // conditional paywall routing ("if came from 12a → paywall_a").
                val prevId = previousStepId ?: return false
                val expected = cond["value"] as? String ?: ""
                prevId == expected
            }

            "previous_step_in" -> {
                // SPEC-070-A finalization P0 audit-7 — ports iOS
                // OnboardingRenderer.swift:1071-1088. Match if the previous
                // step ID is one of the listed IDs. Console saves as
                // `previous_step_ids` array; legacy fallback accepts
                // comma-separated `value`.
                val prevId = previousStepId ?: return false
                (cond["previous_step_ids"] as? List<*>)?.let { list ->
                    val ids = list.filterIsInstance<String>()
                    if (ids.contains(prevId)) return true
                }
                (cond["value"] as? String)?.let { csv ->
                    val ids = csv.split(",").map { it.trim() }
                    if (ids.contains(prevId)) return true
                }
                false
            }

            else -> true // Unknown operator: be permissive (match iOS default branch)
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun isEqual(a: Any?, b: Any?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        // Number equality should compare numeric value, not boxed type.
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        return a == b
    }

    /**
     * Look up id↔value aliases on the current step's input_select options.
     * iOS `OnboardingRenderer.swift:961-980` distinguishes between
     * `option.id` (e.g. "opt_1") and `option.value` (e.g. "by_learning_..."),
     * so a console rule built on the id can still match an SDK response
     * stored as the value. The Android `InputOption` data class
     * (`ContentBlockRenderer.kt:568`) collapses both into a single `value`
     * field, so the Android alias map is effectively the identity map —
     * we still emit it so the evaluator path is structurally identical to
     * iOS and any future divergence in `InputOption` (adding a separate
     * `id` field, for instance) only requires editing this helper.
     */
    private fun optionAliases(field: String, step: OnboardingStep?): Pair<Map<String, String>, Map<String, String>> {
        if (field.isEmpty() || step == null) return emptyMap<String, String>() to emptyMap()
        val blocks = step.config.content_blocks ?: return emptyMap<String, String>() to emptyMap()
        val block = blocks.firstOrNull { b ->
            b.field_id == field && b.type.startsWith("input_")
        } ?: return emptyMap<String, String>() to emptyMap()
        val options = block.field_options ?: return emptyMap<String, String>() to emptyMap()
        // SPEC-401-A R10 — read both `opt.id` and `opt.value` to build a
        // real bidirectional alias map. iOS at OnboardingRenderer.swift:
        // 961-980 maps `option.id ↔ option.value`. Previous Android impl
        // collapsed to identity (v→v), so a console rule keyed on the
        // string the user-facing value-rendered from `opt.id` couldn't
        // match a response Android persisted as `opt.value`.
        val idToVal = mutableMapOf<String, String>()
        val valToId = mutableMapOf<String, String>()
        for (opt in options) {
            val id = opt.id?.takeIf { it.isNotEmpty() }
            val value = opt.value.takeIf { it.isNotEmpty() }
            // Identity entries so the existing `aliasedEquals` shortcut
            // still resolves a literal equal match.
            id?.let { idToVal[it] = it; valToId[it] = it }
            value?.let { idToVal[it] = it; valToId[it] = it }
            // Cross-mappings when both id + value are present and differ.
            if (id != null && value != null && id != value) {
                idToVal[id] = value
                valToId[value] = id
            }
        }
        return idToVal to valToId
    }
}

// -- Target-prefix routing helpers (SPEC-070-A A.21) -----------------------

/**
 * Classification of a `target_step_id` for the activity-level dispatcher
 * to decide whether to navigate to a step, fire a paywall, end the flow,
 * or hand off to a permission/screen/sub-flow auto-route.
 *
 * Mirrors iOS auto-route prefixes (`OnboardingRenderer.swift:808-823`)
 * plus `permission_*` / `screen_*` / `flow_*` for cross-module triggers.
 */
internal sealed class RuleTarget {
    object Empty : RuleTarget()
    data class Step(val stepId: String) : RuleTarget()
    data class PaywallTrigger(val rawTarget: String) : RuleTarget()
    object EndFlow : RuleTarget()
    data class Permission(val name: String) : RuleTarget()
    data class Screen(val screenId: String) : RuleTarget()
    data class SubFlow(val flowId: String) : RuleTarget()
    /**
     * SPEC-070-A finalization Phase D — `analytics_event_*` graph node.
     * iOS routes these targets to fire a custom analytics event then
     * follow `next_target` (or fall through to natural advancement).
     *
     * `nodeId` is the graph-node identifier (loaded into the analytics
     * payload as `node_id` for warehouse grouping). `eventName` is
     * resolved by the OnboardingActivity consumer because it requires
     * graph_nodes lookup (this file is composable-free).
     */
    data class AnalyticsEvent(val nodeId: String) : RuleTarget()
    data class Unknown(val rawTarget: String) : RuleTarget()
}

/**
 * Event name fired when the onboarding flow completes because no
 * `next_step_rule` on the last step matched (rule-failure bailout)
 * rather than via natural sequential end. ETL uses this to distinguish
 * authored-flow completions from misconfigured-flow completions.
 *
 * SPEC-070-A A.21 — see `OnboardingActivity.advanceOrComplete`.
 */
internal const val FLOW_COMPLETED_VIA_FALLBACK_EVENT = "flow_completed_via_fallback"

internal fun classifyRuleTarget(target: String?): RuleTarget {
    if (target.isNullOrBlank()) return RuleTarget.Empty
    return when {
        target.startsWith("paywall_trigger_") -> RuleTarget.PaywallTrigger(target)
        target.startsWith("end_") -> RuleTarget.EndFlow
        target.startsWith("permission_") -> RuleTarget.Permission(target.removePrefix("permission_"))
        target.startsWith("screen_") -> RuleTarget.Screen(target.removePrefix("screen_"))
        target.startsWith("flow_") -> RuleTarget.SubFlow(target.removePrefix("flow_"))
        // SPEC-070-A finalization Phase D — analytics_event_* graph node.
        // Carry the FULL target (the graph_node id) so the consumer can
        // resolve event_name + next_target via flow.graph_nodes lookup.
        target.startsWith("analytics_event_") -> RuleTarget.AnalyticsEvent(target)
        else -> RuleTarget.Step(target)
    }
}

/**
 * SPEC-070-A finalization Phase D — short-id analytics_event detector.
 * Console editor emits short ids like `analytics2` instead of the legacy
 * `analytics_event_<timestamp>`. iOS at OnboardingRenderer.swift:789 routes
 * via `target.hasPrefix("analytics_event_") || nodeType == "analytics_event"`;
 * Android needs the same dual-path. Call this from the consumer after
 * the prefix-classified result is `RuleTarget.Step` to upgrade to
 * AnalyticsEvent when the graph_node type matches.
 */
internal fun upgradeToAnalyticsEventIfShortId(
    classified: RuleTarget,
    graphNodes: Map<String, Any?>?,
): RuleTarget {
    if (classified !is RuleTarget.Step) return classified
    val nodeData = graphNodes?.get(classified.stepId) as? Map<*, *> ?: return classified
    val nodeType = nodeData["type"] as? String ?: return classified
    return if (nodeType == "analytics_event") {
        RuleTarget.AnalyticsEvent(classified.stepId)
    } else classified
}

/**
 * SPEC-401-A R10 — short-id detector for paywall_trigger + end graph
 * nodes. Console editor emits `paywall1` / `end1` instead of legacy
 * `paywall_trigger_<ts>` / `end_<ts>`. iOS routes via dual prefix-or-
 * nodeType detection at `OnboardingRenderer.swift:808,814`; Android
 * `classifyRuleTarget` only matched the long prefix so a console-
 * authored rule with a short ID fell into `RuleTarget.Step`, missed
 * the step lookup, and either no-oped or natural-advanced. Combined
 * with [upgradeToAnalyticsEventIfShortId] this covers all 3 graph-
 * node short-id surfaces (analytics_event / paywall_trigger / end).
 *
 * Caller pattern (OnboardingActivity rule entry):
 *
 *     val classified = upgradeToShortIdRuleTarget(
 *         classifyRuleTarget(rule.target_step_id),
 *         flow.graph_nodes,
 *     )
 */
internal fun upgradeToShortIdRuleTarget(
    classified: RuleTarget,
    graphNodes: Map<String, Any?>?,
): RuleTarget {
    if (classified !is RuleTarget.Step) return classified
    val nodeData = graphNodes?.get(classified.stepId) as? Map<*, *> ?: return classified
    return when (nodeData["type"] as? String) {
        "analytics_event" -> RuleTarget.AnalyticsEvent(classified.stepId)
        "paywall_trigger" -> RuleTarget.PaywallTrigger(classified.stepId)
        "end" -> RuleTarget.EndFlow
        else -> classified
    }
}
