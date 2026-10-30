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
    // Consulted at present-time for a running onboarding
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
        // When no explicit flowId is supplied, evaluate every
        // flow's audience_rules against current userTraits and pick the
        // highest-priority match. Falls back to the active flow when nothing
        // matches. Mirrors iOS OnboardingFlowManager.swift:121-137.
        val activeFlow = resolveFlow(flowId)
        if (activeFlow == null) {
            /*
             * DO NOT ASSERT A FACT THIS HAS NOT CHECKED.
             *
             * "Onboarding flow not found" while the SDK is still configuring is wrong: at that
             * moment nothing has been looked up, because no config has loaded. The old line sent
             * integrators to their flow id and their console — the one place the problem is not.
             * Both answers returned `false`, so a host could not tell them apart either; see
             * `AppDNA.isReady`, which is the question to ask before retrying.
             */
            if (!AppDNA.isReady) {
                Log.warning(
                    "Onboarding flow ${flowId ?: "active"} was NOT looked up: the SDK is still " +
                        "configuring. This is not a missing flow — wait for AppDNA.onReady() (or " +
                        "check AppDNA.isReady) and present again.",
                )
            } else {
                Log.warning("Onboarding flow not found -- flowId: ${flowId ?: "active"}")
            }
            return false
        }

        val (flow, renderedTreatment) = applyExperiment(activeFlow)

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
            payload = buildPayload(activity, flow, renderedTreatment, startTime) { listener },
        )

        return true
    }

    /**
     * Rebuild the launch payload for a flow the OS killed while it was in the background.
     *
     * The activity comes back with only its saved state: the flow id, whether the experiment
     * treatment was on screen, when the flow started, and the step and answers. Everything else —
     * the flow body, the event closures, the host delegate — died with the process, so it is
     * resolved again here the same way [present] resolves it.
     *
     * Deliberately NOT repeated from [present]: `onboarding_flow_started`, the delegate's
     * `onOnboardingStarted`, and clearing the pending completion route. The flow did not start
     * again; it continues. The step on screen still reports `onboarding_step_viewed`, because the
     * user is viewing it again.
     *
     * [activity] is the restored onboarding activity itself: the screen that presented the flow is
     * not running.
     *
     * [listener] is read each time a callback fires, not once here. In a restored process the host's
     * screens are created again around this activity, so a delegate registered from one of them can
     * arrive after the flow is back; the observe-only callbacks (step changed, completed, dismissed)
     * still reach it. The step hooks take the delegate registered when the flow resumes, because
     * whether a delegate exists at all changes what the renderer allows.
     *
     * Returns null when the flow id does not resolve (unpublished, or a config that has not loaded
     * yet); the activity logs the outcome once it knows which.
     */
    fun resumePayload(
        activity: Activity,
        flowId: String,
        renderedTreatment: Boolean,
        startTime: Long,
        listener: () -> AppDNAOnboardingDelegate?,
    ): OnboardingActivity.LaunchPayload? {
        // Quiet on a miss: before the SDK is ready a miss is not final, and the caller decides.
        val activeFlow = remoteConfigManager.getOnboardingFlow(flowId) ?: return null
        val (flow, treatmentNow) = applyExperiment(activeFlow)
        if (treatmentNow != renderedTreatment) {
            Log.warning(
                "Onboarding flow $flowId resumed after a restart with a different experiment variant " +
                    "than it started with (treatment then: $renderedTreatment, now: $treatmentNow); " +
                    "the user continues on the variant that resolves now.",
            )
        }
        return buildPayload(activity, flow, treatmentNow, startTime, listener)
    }

    /**
     * Report a flow that could not be resumed after a restart as dismissed, with the same event and
     * delegate call a dismissal produces, so neither analytics nor the host is left waiting.
     */
    fun reportResumeAbandoned(
        flowId: String,
        lastStepId: String,
        lastStepIndex: Int,
        listener: AppDNAOnboardingDelegate?,
    ) {
        eventTracker.track("onboarding_flow_dismissed", mapOf(
            "flow_id" to flowId,
            "last_step_id" to lastStepId,
            "last_step_index" to lastStepIndex
        ))
        listener?.onOnboardingDismissed(flowId = flowId, atStep = lastStepIndex)
    }

    /**
     * Experiment-aware presentation. A running onboarding experiment targeting this flow + a
     * treatment bucket renders the treatment payload flow; otherwise the active flow (cohort
     * isolation). Returns the flow to render and whether it is the treatment.
     */
    private fun applyExperiment(activeFlow: OnboardingFlowConfig): Pair<OnboardingFlowConfig, Boolean> {
        val resolution = experimentManager?.resolveSurfacePresentation("onboarding_flow", activeFlow.id)
        if (resolution is ai.appdna.sdk.config.ExperimentManager.SurfaceResolution.RenderTreatment) {
            val treatment = OnboardingConfigParser.parseSingleFlow(activeFlow.id, resolution.payload)
            if (treatment != null) {
                Log.info("Onboarding flow ${activeFlow.id} rendering experiment treatment variant")
                return treatment to true
            }
        }
        return activeFlow to false
    }

    /** The five event closures and the delegate, shared by [present] and [resumePayload]. */
    private fun buildPayload(
        activity: Activity,
        flow: OnboardingFlowConfig,
        renderedTreatment: Boolean,
        startTime: Long,
        listener: () -> AppDNAOnboardingDelegate?,
    ): OnboardingActivity.LaunchPayload = OnboardingActivity.LaunchPayload(
            flow = flow,
            delegate = listener(),
            eventTracker = eventTracker,
            renderedTreatment = renderedTreatment,
            startTime = startTime,
            onStepViewed = { stepId, stepIndex ->
                eventTracker.track("onboarding_step_viewed", mapOf(
                    "flow_id" to flow.id,
                    "step_id" to stepId,
                    "step_index" to stepIndex,
                    "step_type" to (flow.steps.getOrNull(stepIndex)?.type?.value ?: "unknown")
                ))
                listener()?.onOnboardingStepChanged(flowId = flow.id, stepId = stepId, stepIndex = stepIndex, totalSteps = flow.steps.size)
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
                // Emit as Int matching iOS
                // OnboardingFlowManager.swift:84 `Int(... * 1000)`. Without
                // .toInt() the event payload carries a Long; downstream BQ
                // schema + dashboards typed against Int from iOS see type
                // drift on Android-only telemetry.
                val durationMs = (System.currentTimeMillis() - startTime).toInt()
                // The completion decision (event + persistence + delegate)
                // now lives in [OnboardingCompletion], NOT in this Activity-bound lambda, so it is
                // reachable — and provable — without presenting an Activity. Same event, same
                // props, same order as before.
                OnboardingCompletion.complete(
                    flowId = flow.id,
                    totalSteps = flow.steps.size,
                    durationMs = durationMs,
                    responses = responses,
                    track = { name, props -> eventTracker.track(name, props) },
                    delegate = listener(),
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
                listener()?.onOnboardingDismissed(flowId = flow.id, atStep = lastStepIndex)
            },
        )

    // MARK: - Private

    /**
     * Resolve a flow given an optional explicit id.
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
