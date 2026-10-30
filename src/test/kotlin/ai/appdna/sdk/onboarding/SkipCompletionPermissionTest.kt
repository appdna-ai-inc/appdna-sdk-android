package ai.appdna.sdk.onboarding

import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Three real gaps the honest cross-platform fixture runner exposed on Android:
 *
 *  B2  a `skip_to` step advance emitted NOTHING, so a flow that branches over steps was invisible
 *      in the funnel (a skipped step looked identical to an unreached one).
 *  B3  the `permission` CTA had no observable seam at all: the whole decision lived in a closure
 *      inside `@Composable OnboardingFlowHost`, so a host could not see that a permission element
 *      had been acted on, and the safe fallback (unsupported type → still advance) was unprovable.
 *  B4  flow completion (`onboarding_flow_completed` + `onOnboardingCompleted`) was reachable only
 *      through `OnboardingFlowManager.present(activity, ...)` — no Activity, no completion.
 *
 * Every test here runs with NO Activity and NO Compose tree. That is the point.
 */
class SkipCompletionPermissionTest {

    private fun step(id: String) = OnboardingStep(
        id = id,
        type = OnboardingStep.StepType.QUESTION,
        config = StepConfig(),
        next_step_rules = emptyList<NextStepRule>().toImmutableList(),
    )

    private fun flow(vararg steps: OnboardingStep) = OnboardingFlowConfig(
        id = "flow_with_skip",
        name = "Flow",
        version = 1,
        steps = steps.toList().toImmutableList(),
        settings = OnboardingSettings(),
    )

    // -- B2: skip_to emits ------------------------------------------------------------------------

    @Test
    fun `skip_to emits step_skipped carrying the flow and both step ids`() {
        val out = OnboardingAdvance.apply(
            flow = flow(step("login_step"), step("ask_goal"), step("ask_experience"), step("home_step")),
            currentIndex = 0,
            responses = emptyMap(),
            result = StepAdvanceResult.SkipTo("home_step"),
        )

        assertEquals(OnboardingAdvance.Navigation.GoToIndex(3), out.navigation)
        assertEquals(1, out.events.size)
        val e = out.events.single()
        assertEquals("step_skipped", e.name)
        assertEquals("flow_with_skip", e.props["flow_id"])
        assertEquals("login_step", e.props["from_step_id"])
        assertEquals("home_step", e.props["to_step_id"])
    }

    @Test
    fun `the skip event does not collide with the skip-BUTTON event`() {
        // `onboarding_step_skipped` (OnboardingFlowManager.onStepSkipped) means "the user tapped the
        // step's Skip button" and carries {step_id, step_index}. The branch event must NOT reuse it,
        // or the two become unanalysable in the same funnel.
        val out = OnboardingAdvance.skipTo(
            flow = flow(step("a"), step("b")),
            currentIndex = 0,
            targetStepId = "b",
            responses = emptyMap(),
        )
        assertTrue(out.events.none { it.name == "onboarding_step_skipped" })
        assertEquals(STEP_SKIPPED_EVENT, out.events.single().name)
    }

    @Test
    fun `an unknown skip target still falls through to a normal advance and emits no skip event`() {
        val out = OnboardingAdvance.apply(
            flow = flow(step("a"), step("b")),
            currentIndex = 0,
            responses = emptyMap(),
            result = StepAdvanceResult.SkipTo("does_not_exist"),
        )
        assertEquals(OnboardingAdvance.Navigation.GoToIndex(1), out.navigation)
        assertTrue(out.events.none { it.name == STEP_SKIPPED_EVENT })
    }

    // -- B3: the permission action seam ------------------------------------------------------------

    @Test
    fun `a supported permission runs the OS pipeline and does not pre-advance the step`() {
        var emitted: Map<String, Any>? = null
        val decision = emitPermissionAction(
            configType = null,
            layoutType = "notification",
            actionValue = null,
            toggleValues = emptyMap(),
            inputValues = emptyMap(),
            onNext = { emitted = it },
        )
        assertEquals(PermissionActionDecision.RunPipeline("notification"), decision)
        // The pipeline owns the advance — advancing here would skip the step before the OS answers.
        assertNull(emitted)
    }

    @Test
    fun `an unsupported permission reports the action to the host and advances anyway`() {
        // The fixture's case: an `att`-style / mis-spelled type Android cannot request. Before this
        // seam the host saw nothing at all and the CTA was a silent dead end.
        var emitted: Map<String, Any>? = null
        val decision = emitPermissionAction(
            configType = null,
            layoutType = null,
            actionValue = "notifications", // NOT the supported spelling ("notification")
            toggleValues = emptyMap(),
            inputValues = mapOf("email" to "a@b.com"),
            onNext = { emitted = it },
        )
        assertEquals(PermissionActionDecision.SafeFallbackAdvance("notifications"), decision)
        val payload = requireNonNull(emitted)
        assertEquals("permission", payload["action"])
        assertEquals("notifications", payload["action_value"])
        // Step inputs ride along, exactly as they do for every other CTA.
        assertEquals("a@b.com", payload["email"])
    }

    @Test
    fun `permission type resolves config then layout then the buttons own value`() {
        assertEquals("camera", resolvePermissionType("camera", "location", "photos"))
        assertEquals("location", resolvePermissionType(null, "location", "photos"))
        assertEquals("photos", resolvePermissionType(null, "  ", "photos"))
        assertEquals("", resolvePermissionType(null, null, null))
    }

    @Test
    fun `a permission step with no declared type takes the safe fallback rather than dead-ending`() {
        var emitted: Map<String, Any>? = null
        val decision = emitPermissionAction(
            configType = null, layoutType = null, actionValue = null,
            toggleValues = emptyMap(), inputValues = emptyMap(),
            onNext = { emitted = it },
        )
        assertEquals(PermissionActionDecision.SafeFallbackAdvance(""), decision)
        assertEquals("permission", requireNonNull(emitted)["action"])
    }

    // -- B4: completion without an Activity --------------------------------------------------------

    @Test
    fun `flow completion emits the event and calls the delegate with no Activity in sight`() {
        val tracked = mutableListOf<Pair<String, Map<String, Any>>>()
        val delegateCalls = mutableListOf<Pair<String, Map<String, Any>>>()
        val delegate = object : AppDNAOnboardingDelegate {
            override fun onOnboardingCompleted(flowId: String, responses: Map<String, Any>) {
                delegateCalls.add(flowId to responses)
            }
        }
        val responses = mapOf(
            "ask_goal" to listOf("lose_weight"),
            "ask_experience" to listOf("beginner"),
        )

        OnboardingCompletion.complete(
            flowId = "flow_post_signup",
            totalSteps = 3,
            durationMs = 1234,
            responses = responses,
            track = { name, props -> tracked.add(name to props) },
            delegate = delegate,
        )

        val (name, props) = tracked.single()
        assertEquals("onboarding_flow_completed", name)
        assertEquals("flow_post_signup", props["flow_id"])
        assertEquals(3, props["total_steps"])
        assertEquals(1234, props["total_duration_ms"])
        assertEquals(responses, props["responses"])

        val (flowId, delivered) = delegateCalls.single()
        assertEquals("flow_post_signup", flowId)
        assertEquals(responses, delivered)
    }

    @Test
    fun `a throwing host delegate cannot lose the completion analytic`() {
        val tracked = mutableListOf<String>()
        val delegate = object : AppDNAOnboardingDelegate {
            override fun onOnboardingCompleted(flowId: String, responses: Map<String, Any>) {
                throw IllegalStateException("host blew up")
            }
        }
        OnboardingCompletion.complete(
            flowId = "f", totalSteps = 1, durationMs = 0, responses = emptyMap(),
            track = { name, _ -> tracked.add(name) },
            delegate = delegate,
        )
        assertEquals(listOf("onboarding_flow_completed"), tracked)
    }

    private fun <T : Any> requireNonNull(v: T?): T {
        if (v == null) throw AssertionError("expected a value, got null")
        return v
    }
}
