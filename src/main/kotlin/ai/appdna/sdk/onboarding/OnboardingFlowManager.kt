package ai.appdna.sdk.onboarding

import android.app.Activity
import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.core.AudienceRule
import ai.appdna.sdk.core.AudienceRuleEvaluator
import ai.appdna.sdk.core.AudienceRuleSet
import ai.appdna.sdk.events.EventTracker

/**
 * Manages onboarding flow presentation, state, and event tracking (Android).
 * Mirrors the iOS OnboardingFlowManager behavior.
 */
internal class OnboardingFlowManager(
    private val remoteConfigManager: RemoteConfigManager,
    private val eventTracker: EventTracker,
    // SPEC-036-F §1.2 — consulted at present-time for a running onboarding
    // experiment targeting the resolved flow.
    private val experimentManager: ai.appdna.sdk.config.ExperimentManager? = null,
) {

    /**
     * Present an onboarding flow. Returns false if config is unavailable.
     */
    fun present(
        activity: Activity,
        flowId: String? = null,
        listener: AppDNAOnboardingDelegate? = null
    ): Boolean {
        // SPEC-070-A F.13: when no explicit flowId is supplied, evaluate every
        // flow's audience_rules against current userTraits and pick the
        // highest-priority match. Falls back to the active flow when nothing
        // matches. Mirrors iOS OnboardingFlowManager.swift:121-137.
        val activeFlow = resolveFlow(flowId)
        if (activeFlow == null) {
            Log.warning("Onboarding flow not found -- flowId: ${flowId ?: "active"}")
            return false
        }

        // SPEC-036-F §1.2 — experiment-aware presentation. A running onboarding
        // experiment targeting this flow + a treatment bucket renders the
        // treatment payload flow; otherwise the active flow (cohort isolation).
        var flow = activeFlow
        val resolution = experimentManager?.resolveSurfacePresentation("onboarding_flow", activeFlow.id)
        if (resolution is ai.appdna.sdk.config.ExperimentManager.SurfaceResolution.RenderTreatment) {
            val treatment = OnboardingConfigParser.parseSingleFlow(activeFlow.id, resolution.payload)
            if (treatment != null) {
                Log.info("Onboarding flow ${activeFlow.id} rendering experiment treatment variant")
                flow = treatment
            }
        }

        // Track flow started
        // Drop any after-onboarding destination left by a flow the user ABANDONED, so it cannot
        // fire when THIS one completes.
        PendingCompletionRoute.clear()

        eventTracker.track("onboarding_flow_started", mapOf(
            "flow_id" to flow.id,
            "flow_version" to flow.version
        ))

        val startTime = System.currentTimeMillis()

        // Notify delegate of flow start
        listener?.onOnboardingStarted(flowId = flow.id)

        // Launch the OnboardingActivity
        OnboardingActivity.launch(
            context = activity,
            flow = flow,
            delegate = listener,
            eventTracker = eventTracker,
            onStepViewed = { stepId, stepIndex ->
                eventTracker.track("onboarding_step_viewed", mapOf(
                    "flow_id" to flow.id,
                    "step_id" to stepId,
                    "step_index" to stepIndex,
                    "step_type" to (flow.steps.getOrNull(stepIndex)?.type?.value ?: "unknown")
                ))
                listener?.onOnboardingStepChanged(flowId = flow.id, stepId = stepId, stepIndex = stepIndex, totalSteps = flow.steps.size)
            },
            onStepCompleted = { stepId, stepIndex, data ->
                eventTracker.track("onboarding_step_completed", mapOf(
                    "flow_id" to flow.id,
                    "step_id" to stepId,
                    "step_index" to stepIndex,
                    "selection_data" to (data ?: emptyMap<String, Any>())
                ))
            },
            onStepSkipped = { stepId, stepIndex ->
                eventTracker.track("onboarding_step_skipped", mapOf(
                    "flow_id" to flow.id,
                    "step_id" to stepId,
                    "step_index" to stepIndex
                ))
            },
            onFlowCompleted = { responses ->
                // SPEC-401-A R59 (Lens B P3) — emit as Int matching iOS
                // OnboardingFlowManager.swift:84 `Int(... * 1000)`. Without
                // .toInt() the event payload carries a Long; downstream BQ
                // schema + dashboards typed against Int from iOS see type
                // drift on Android-only telemetry.
                val durationMs = (System.currentTimeMillis() - startTime).toInt()
                // SPEC-070-B — the completion decision (event + SPEC-088 persistence + delegate)
                // now lives in [OnboardingCompletion], NOT in this Activity-bound lambda, so it is
                // reachable — and provable — without presenting an Activity. Same event, same
                // props, same order as before.
                OnboardingCompletion.complete(
                    flowId = flow.id,
                    totalSteps = flow.steps.size,
                    durationMs = durationMs,
                    responses = responses,
                    track = { name, props -> eventTracker.track(name, props) },
                    delegate = listener,
                    // Scheme-checked before the OS sees it — the URL comes from remote config.
                    openRoute = { raw ->
                        ai.appdna.sdk.core.URLSafety.sanitized(raw, activity)?.let { uri ->
                            try {
                                activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                                true
                            } catch (_: Exception) { false }
                        } ?: false
                    },
                )
            },
            onFlowDismissed = { lastStepId, lastStepIndex ->
                eventTracker.track("onboarding_flow_dismissed", mapOf(
                    "flow_id" to flow.id,
                    "last_step_id" to lastStepId,
                    "last_step_index" to lastStepIndex
                ))
                listener?.onOnboardingDismissed(flowId = flow.id, atStep = lastStepIndex)
            }
        )

        return true
    }

    // MARK: - Private

    /**
     * SPEC-070-A F.13: resolve a flow given an optional explicit id.
     *
     * - Explicit id → look up directly via [RemoteConfigManager.getOnboardingFlow].
     * - No id + non-empty userTraits → evaluate every flow's `audience_rules`
     *   and pick the highest-priority match.
     * - Otherwise → fall back to the active flow (`getOnboardingFlow(null)`).
     *
     * Mirrors iOS `Onboarding/OnboardingFlowManager.swift:115-145`.
     */
    private fun resolveFlow(flowId: String?): OnboardingFlowConfig? {
        if (flowId != null) {
            return remoteConfigManager.getOnboardingFlow(flowId)
        }

        val userTraits = AppDNA.getUserTraits()
        if (userTraits.isNotEmpty()) {
            val matches = remoteConfigManager.getAllOnboardingFlows().values
                .mapNotNull { flow ->
                    val (matched, priority) = evaluateAudienceRules(flow.audience_rules, userTraits)
                    if (matched) flow to priority else null
                }
                .sortedByDescending { it.second }

            val best = matches.firstOrNull()
            if (best != null) {
                Log.info("[Onboarding] Audience-matched flow: ${best.first.id} (priority: ${best.second})")
                return best.first
            }
        }

        return remoteConfigManager.getOnboardingFlow(null)
    }

    /**
     * Evaluate `audience_rules` against [traits].
     *
     * Accepts both shapes used by the console / iOS:
     *   - `[ {trait, operator, value}, ... ]`  (raw rule list — AND across rules)
     *   - `{ priority, conditions: [...], match_mode: "all" | "any" }`
     *     (mirrors [AudienceRuleSet])
     *
     * Returns `(matched, priority)` so the caller can sort highest-priority first.
     * `null` rules → `(false, 0)` so flows without targeting only become the fallback,
     * never an audience match. Mirrors iOS AudienceRuleEvaluator semantics.
     */
    @Suppress("UNCHECKED_CAST")
    private fun evaluateAudienceRules(rules: Any?, traits: Map<String, Any>): Pair<Boolean, Int> {
        return when (rules) {
            null -> false to 0
            is List<*> -> {
                val list = rules.mapNotNull { (it as? Map<String, Any?>)?.let(AudienceRule.Companion::fromMap) }
                if (list.isEmpty()) false to 0
                else AudienceRuleEvaluator.evaluate(list, traits) to 0
            }
            is Map<*, *> -> {
                val mp = rules as Map<String, Any?>
                val priority = (mp["priority"] as? Number)?.toInt() ?: 0
                val ruleSet = AudienceRuleSet.fromMap(mp)
                val matched = AudienceRuleEvaluator.evaluate(ruleSet, traits)
                matched to priority
            }
            else -> false to 0
        }
    }
}
