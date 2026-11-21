package ai.appdna.sdk.core

import ai.appdna.sdk.feedback.SurveyConfig
import ai.appdna.sdk.onboarding.OnboardingFlowConfig

/**
 * A placement is a name an app can ask for instead of an item's ID. Several items may share one.
 * They are tried in a fixed order, the same on every launch and on both platforms.
 */
internal object ItemPlacementResolver {
    /**
     * Higher audience priority, then a flow with audience conditions before a catch-all, then lowest
     * `id`. The first whose rules match wins; a flow with no rules matches everyone.
     */
    fun pickFlow(flows: Collection<OnboardingFlowConfig>, placement: String, traits: Map<String, Any>): OnboardingFlowConfig? {
        fun ruleSet(flow: OnboardingFlowConfig) = AudienceRuleSet.fromAny(flow.audience_rules)
        return flows
            .filter { it.placement == placement }
            .sortedWith(
                compareByDescending<OnboardingFlowConfig> { ruleSet(it)?.priority ?: 0 }
                    .thenByDescending { ruleSet(it)?.conditions?.isNotEmpty() == true }
                    .thenBy { it.id },
            )
            .firstOrNull { flow ->
                val rules = ruleSet(flow) ?: return@firstOrNull true
                try { AudienceRuleEvaluator.evaluate(rules, traits) } catch (_: Throwable) { true }
            }
    }

    /**
     * The same order as [pickFlow]: higher audience priority, then a survey with audience conditions before a
     * catch-all, then lowest `id`. The first whose `audience_rules` admit the user wins (the survey trigger's own
     * audience gate, [ai.appdna.sdk.feedback.SurveyManager.audienceAllows]); a survey with no rules matches everyone.
     */
    fun pickSurveyId(surveys: Map<String, SurveyConfig>, placement: String, traits: Map<String, Any>): String? {
        fun ruleSet(s: SurveyConfig) = AudienceRuleSet.fromAny(s.audienceRules)
        return surveys.entries
            .filter { it.value.placement == placement }
            .sortedWith(
                compareByDescending<Map.Entry<String, SurveyConfig>> { ruleSet(it.value)?.priority ?: 0 }
                    .thenByDescending { ruleSet(it.value)?.conditions?.isNotEmpty() == true }
                    .thenBy { it.key },
            )
            .firstOrNull { ai.appdna.sdk.feedback.SurveyManager.audienceAllows(it.value, traits) }
            ?.key
    }
}
