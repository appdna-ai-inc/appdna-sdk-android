package ai.appdna.sdk.onboarding

import ai.appdna.sdk.Log
import android.os.Bundle

/**
 * What a flow needs to come back after the OS kills the process while it is in the background.
 *
 * When the user leaves the app mid-flow and the process is killed, Android later restores the
 * onboarding activity in a NEW process with only its saved Bundle. The launch payload (a static) and
 * the ViewModel died with the old process. This is the part of the flow that survives: which flow,
 * which variant, when it started, the step on screen, the answers, the navigation history, a
 * completion route a CTA recorded, and what the user had typed on the step on screen and not yet
 * submitted (secrets excluded — see [StepDrafts.writeTo]).
 *
 * Kept small on purpose. The flow body is NOT saved — the Bundle travels through a binder
 * transaction with a hard size limit — so the flow is resolved again from config, and the saved
 * step id is checked against it before the user is put back on that step.
 */
internal class OnboardingResumeState private constructor(
    val flowId: String,
    val flowVersion: Int,
    val renderedTreatment: Boolean,
    val startTime: Long,
    val stepIndex: Int,
    val stepId: String?,
    private val responsesJson: String?,
    private val navigationHistory: Array<String>?,
    private val completionRoute: String?,
    private val stepDraftJson: String?,
) {

    /**
     * Where to put the user in [flow]: the saved index when the step there is still the one they
     * left; otherwise wherever that step moved to in a republished flow. Null when the step is gone,
     * so the caller ends the flow rather than drop the user on an unrelated screen.
     */
    fun resolveStepIndex(flow: OnboardingFlowConfig): Int? {
        val atIndex = flow.steps.getOrNull(stepIndex)
        if (stepId == null) return if (atIndex != null) stepIndex else null
        if (atIndex?.id == stepId) return stepIndex
        val moved = flow.steps.indexOfFirst { it.id == stepId }
        if (moved < 0) return null
        Log.warning(
            "Onboarding flow $flowId changed while the app was closed; step $stepId moved from " +
                "position ${stepIndex + 1} to ${moved + 1}. Resuming there.",
        )
        return moved
    }

    /** Put the saved progress into a freshly bound ViewModel, before the flow is first composed. */
    fun applyTo(viewModel: OnboardingViewModel, index: Int) {
        viewModel.currentIndex.intValue = index
        responses()?.let { viewModel.responses.putAll(it) }
        navigationHistory?.let {
            viewModel.navigationHistory.clear()
            viewModel.navigationHistory.addAll(it)
        }
        completionRoute?.let { PendingCompletionRoute.record(it) }
        viewModel.flow?.steps?.getOrNull(index)?.id?.let { viewModel.stepDrafts.restore(stepDraftJson, it) }
    }

    /** Write this state back unchanged, for an activity recreated again before it could resume. */
    fun writeTo(out: Bundle) {
        out.putString(KEY_FLOW_ID, flowId)
        out.putInt(KEY_FLOW_VERSION, flowVersion)
        out.putBoolean(KEY_RENDERED_TREATMENT, renderedTreatment)
        out.putLong(KEY_STARTED_AT, startTime)
        out.putInt(OnboardingActivity.KEY_CURRENT_STEP_INDEX, stepIndex)
        stepId?.let { out.putString(KEY_CURRENT_STEP_ID, it) }
        responsesJson?.let { out.putString(OnboardingActivity.KEY_RESPONSES, it) }
        navigationHistory?.let { out.putStringArray(OnboardingActivity.KEY_NAV_HISTORY, it) }
        completionRoute?.let { out.putString(KEY_COMPLETION_ROUTE, it) }
        stepDraftJson?.let { out.putString(StepDrafts.KEY_STEP_DRAFT, it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun responses(): Map<String, Any>? = responsesJson?.let { json ->
        try {
            // Deep-convert, so a nested answer is a Map / List again and the rule evaluator reads it.
            fromJsonAny(org.json.JSONObject(json)) as? Map<String, Any>
        } catch (e: Exception) {
            Log.warning("Onboarding flow $flowId: the saved answers could not be read back (${e.message}); resuming without them.")
            null
        }
    }

    companion object {
        private const val KEY_FLOW_ID = "appdna_onboarding_flow_id"
        private const val KEY_FLOW_VERSION = "appdna_onboarding_flow_version"
        private const val KEY_RENDERED_TREATMENT = "appdna_onboarding_rendered_treatment"
        private const val KEY_STARTED_AT = "appdna_onboarding_started_at"
        private const val KEY_CURRENT_STEP_ID = "appdna_onboarding_current_step_id"
        private const val KEY_COMPLETION_ROUTE = "appdna_onboarding_completion_route"

        /** The flow-identity half of the saved state; the activity writes the step and answers. */
        fun writeFlow(
            out: Bundle,
            flow: OnboardingFlowConfig,
            stepIndex: Int,
            renderedTreatment: Boolean,
            startTime: Long,
        ) {
            out.putString(KEY_FLOW_ID, flow.id)
            out.putInt(KEY_FLOW_VERSION, flow.version)
            out.putBoolean(KEY_RENDERED_TREATMENT, renderedTreatment)
            out.putLong(KEY_STARTED_AT, startTime)
            flow.steps.getOrNull(stepIndex)?.id?.let { out.putString(KEY_CURRENT_STEP_ID, it) }
            PendingCompletionRoute.peek()?.let { out.putString(KEY_COMPLETION_ROUTE, it) }
        }

        /** The saved flow in [saved], or null when there is none to resume. */
        fun from(saved: Bundle?): OnboardingResumeState? {
            val flowId = saved?.getString(KEY_FLOW_ID)?.takeIf { it.isNotEmpty() } ?: return null
            return OnboardingResumeState(
                flowId = flowId,
                flowVersion = saved.getInt(KEY_FLOW_VERSION, 0),
                renderedTreatment = saved.getBoolean(KEY_RENDERED_TREATMENT, false),
                startTime = saved.getLong(KEY_STARTED_AT, System.currentTimeMillis()),
                stepIndex = saved.getInt(OnboardingActivity.KEY_CURRENT_STEP_INDEX, 0).coerceAtLeast(0),
                stepId = saved.getString(KEY_CURRENT_STEP_ID),
                responsesJson = saved.getString(OnboardingActivity.KEY_RESPONSES),
                navigationHistory = saved.getStringArray(OnboardingActivity.KEY_NAV_HISTORY),
                completionRoute = saved.getString(KEY_COMPLETION_ROUTE),
                stepDraftJson = saved.getString(StepDrafts.KEY_STEP_DRAFT),
            )
        }
    }
}
