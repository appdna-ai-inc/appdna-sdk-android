package ai.appdna.sdk.onboarding

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The advance/skip/hook-result machine and the paywall_trigger skip gate used to live
 * in closures inside `@Composable OnboardingFlowHost`, so no test could reach them. This asserts
 * the extracted seams behave exactly as the composable did.
 */
class OnboardingAdvanceSeamTest {

    private fun step(id: String, rules: List<NextStepRule> = emptyList()) = OnboardingStep(
        id = id,
        type = OnboardingStep.StepType.QUESTION,
        config = StepConfig(),
        next_step_rules = rules.toImmutableList(),
    )

    private fun flow(vararg steps: OnboardingStep, graphNodes: Map<String, Any?>? = null) =
        OnboardingFlowConfig(
            id = "flow1",
            name = "Flow",
            version = 1,
            steps = steps.toList().toImmutableList(),
            settings = OnboardingSettings(),
            graph_nodes = graphNodes,
        )

    // -- advance --------------------------------------------------------------

    @Test
    fun `sequential advance moves one step and pushes the leaving step onto history`() {
        val out = OnboardingAdvance.advance(flow(step("a"), step("b")), 0, emptyMap())
        assertEquals(OnboardingAdvance.Navigation.GoToIndex(1), out.navigation)
        assertEquals(listOf("a"), out.historyPush)
        assertTrue(out.hapticOnAdvance)
    }

    @Test
    fun `the last step completes the flow with the responses`() {
        val out = OnboardingAdvance.advance(flow(step("a")), 0, mapOf("a" to mapOf("x" to 1)))
        assertEquals(
            OnboardingAdvance.Navigation.CompleteFlow(mapOf("a" to mapOf("x" to 1))),
            out.navigation,
        )
    }

    @Test
    fun `an unmatched rule on the last step emits the fallback-completion event`() {
        val rule = NextStepRule(
            conditions = persistentListOf(
                mapOf<String, Any?>("type" to "answer_equals", "answer_key" to "q", "value" to "yes"),
            ),
            target_step_id = "nowhere",
        )
        val out = OnboardingAdvance.advance(flow(step("a", listOf(rule))), 0, emptyMap())
        assertEquals(listOf(FLOW_COMPLETED_VIA_FALLBACK_EVENT), out.events.map { it.name })
        assertTrue(out.navigation is OnboardingAdvance.Navigation.CompleteFlow)
    }

    @Test
    fun `a paywall_trigger target routes to the paywall bridge`() {
        val rule = NextStepRule(target_step_id = "paywall1")
        val f = flow(
            step("a", listOf(rule)),
            step("b"),
            graphNodes = mapOf("paywall1" to mapOf("type" to "paywall_trigger", "paywall_id" to "pw1")),
        )
        val out = OnboardingAdvance.advance(f, 0, emptyMap())
        assertEquals(OnboardingAdvance.Navigation.PresentPaywallTrigger("paywall1"), out.navigation)
    }

    // -- hook results ---------------------------------------------------------

    @Test
    fun `Block stays on the step and raises the error banner`() {
        val out = OnboardingAdvance.apply(
            flow(step("a"), step("b")), 0, emptyMap(), StepAdvanceResult.Block("Invalid email"),
        )
        assertEquals(OnboardingAdvance.Navigation.Stay, out.navigation)
        assertEquals(OnboardingAdvance.Banner.Error("Invalid email"), out.banner)
        assertEquals(emptyList<String>(), out.historyPush)
    }

    @Test
    fun `ProceedWithData merges into the step's own responses and advances`() {
        val out = OnboardingAdvance.apply(
            flow(step("a"), step("b")),
            0,
            mapOf("a" to mapOf("name" to "Ada")),
            StepAdvanceResult.ProceedWithData(mapOf("score" to 9)),
        )
        assertEquals(mapOf("a" to mapOf("name" to "Ada", "score" to 9)), out.responses)
        assertEquals(mapOf("score" to 9), out.computedData)
        assertEquals(OnboardingAdvance.Navigation.GoToIndex(1), out.navigation)
    }

    @Test
    fun `SkipTo an unknown step falls through to a natural advance`() {
        val out = OnboardingAdvance.apply(
            flow(step("a"), step("b")), 0, emptyMap(), StepAdvanceResult.SkipTo("ghost"),
        )
        assertEquals(OnboardingAdvance.Navigation.GoToIndex(1), out.navigation)
    }

    @Test
    fun `Stay with a message raises the success banner and does not navigate`() {
        val out = OnboardingAdvance.apply(
            flow(step("a"), step("b")), 0, emptyMap(), StepAdvanceResult.Stay("Saved"),
        )
        assertEquals(OnboardingAdvance.Navigation.Stay, out.navigation)
        assertEquals(OnboardingAdvance.Banner.Success("Saved"), out.banner)
    }

    // -- paywall_trigger skip gate -------------------------------------------

    @Test
    fun `a subscribed user skips to the subscribed-skip target`() {
        val outcome = PaywallTriggerResolver.decide(
            triggerData = mapOf(
                "paywall_id" to "pw1",
                "on_subscribed_skip_target" to "thanks",
                "on_success_target" to "success",
            ),
            hasActiveSubscription = true,
            runtimeLocked = false,
        )
        assertEquals(
            PaywallTriggerResolver.PaywallTriggerOutcome.Skip(
                "pw1", "thanks", "continue", PaywallTriggerResolver.REASON_ALREADY_SUBSCRIBED,
            ),
            outcome,
        )
    }

    @Test
    fun `skip_if_subscribed false presents the upsell paywall to a subscriber`() {
        val outcome = PaywallTriggerResolver.decide(
            triggerData = mapOf("paywall_id" to "pw1", "skip_if_subscribed" to false),
            hasActiveSubscription = true,
            runtimeLocked = false,
        )
        assertEquals(PaywallTriggerResolver.PaywallTriggerOutcome.Present("pw1"), outcome)
    }

    @Test
    fun `a runtime-locked SDK skips even an upsell paywall`() {
        val outcome = PaywallTriggerResolver.decide(
            triggerData = mapOf("paywall_id" to "pw1", "skip_if_subscribed" to false),
            hasActiveSubscription = false,
            runtimeLocked = true,
        )
        assertEquals(
            PaywallTriggerResolver.PaywallTriggerOutcome.Skip(
                "pw1", null, "continue", PaywallTriggerResolver.REASON_RUNTIME_LOCKED,
            ),
            outcome,
        )
    }

    @Test
    fun `a trigger node with no paywall_id completes the flow`() {
        assertEquals(
            PaywallTriggerResolver.PaywallTriggerOutcome.CompleteFlow,
            PaywallTriggerResolver.decide(mapOf("on_success_target" to "x"), false, false),
        )
    }

    // -- other extracted seams ------------------------------------------------

    @Test
    fun `applyingOverride merges only the authored fields`() {
        val base = StepConfig(title = "Hi", subtitle = "Sub", cta_text = "Next")
        val merged = base.applyingOverride(StepConfigOverride(title = "Welcome back"))
        assertEquals("Welcome back", merged.title)
        assertEquals("Sub", merged.subtitle)
        assertEquals("Next", merged.cta_text)
    }

    @Test
    fun `the email social provider dual-emits, others emit one colon-encoded action`() {
        assertEquals(listOf("email_login:email", "social_login:email"), socialProviderActions("email"))
        assertEquals(listOf("social_login:apple"), socialProviderActions("apple"))
    }
}
