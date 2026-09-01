package ai.appdna.sdk.onboarding

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * Pure onboarding advance state machine.
 *
 * WHY this exists: the routing surface (next_step_rules → step / paywall_trigger /
 * analytics_event / end / permission / screen / sub_flow, plus hook-result handling and
 * skip-to) is the most bug-prone code in the onboarding module, and it lived entirely in
 * closures inside the `@Composable OnboardingFlowHost`. Nothing outside the Compose runtime
 * could reach it, so it had no test seam and the shared cross-platform fixtures could only
 * assert against a re-implementation of it. The logic below is a verbatim MOVE of
 * `advanceOrComplete` / `skipToStep` / `mergeData` / `handleHookResult`; the composable now
 * computes an [AdvanceOutcome] here and executes it. No behavior change.
 *
 * Everything in here is pure: no Compose state, no event tracker, no delegate. Side effects
 * the composable must perform (haptic, analytics, navigation-history push, flow completion)
 * are DESCRIBED by the returned [AdvanceOutcome] instead of performed.
 */
internal object OnboardingAdvance {

    /** An analytics event the caller must hand to its `EventTracker`, in list order. */
    data class TrackedEvent(val name: String, val props: Map<String, Any>)

    /** Banner state the caller must raise (error pill / success pill). */
    sealed class Banner {
        data class Error(val message: String) : Banner()
        data class Success(val message: String) : Banner()
    }

    /** Where the flow goes next. */
    sealed class Navigation {
        /** Move to `flow.steps[index]`. */
        data class GoToIndex(val index: Int) : Navigation()

        /** Finish the flow, handing [responses] (already marker-augmented) to `onFlowCompleted`. */
        data class CompleteFlow(val responses: Map<String, Any>) : Navigation()

        /** Hand a `paywall_trigger` graph node to the paywall bridge. */
        data class PresentPaywallTrigger(val nodeId: String) : Navigation()

        /** Remain on the current step (hook returned `Block` / `Stay`). */
        object Stay : Navigation()

        /**
         * 🔴 DID THE STEP ACTUALLY COMPLETE?
         *
         * The only honest definition: the flow LEFT the step. [Stay] is the hook saying no — a wrong
         * password, a failed sign-in, a validation error — and the user is still looking at the very
         * same step. Nothing completed.
         *
         * `onboarding_step_completed` used to be emitted the moment the user tapped the button, before
         * the hook had decided anything. A user who mistyped their password three times emitted FOUR
         * completions of a step they never completed; the successful fourth attempt made five. That
         * corrupts step-completion and funnel conversion — the metrics the product is sold on — and it
         * over-counts worst at the credential step, the step users actually fail at, so the flows that
         * convert worst looked the healthiest.
         *
         * It lives on the pure machine, not in either renderer, so both platforms answer the question
         * identically and a test can ask it without a host.
         */
        val completesStep: Boolean
            get() = this !is Stay
    }

    data class AdvanceOutcome(
        val navigation: Navigation,
        /** The step responses after any hook-driven merge. Never carries completion markers. */
        val responses: Map<String, Any>,
        /** Hook-computed data the caller must persist via `SessionDataStore.mergeComputedData`. */
        val computedData: Map<String, Any>? = null,
        val events: List<TrackedEvent> = emptyList(),
        val banner: Banner? = null,
        /** Step ids to append to `navigationHistory`, in order, before applying [navigation]. */
        val historyPush: List<String> = emptyList(),
        /**
         * True when this outcome came from a natural advance (`advanceOrComplete`). Only that
         * path fires the `on_step_advance` haptic — skip-to and blocked hooks never did.
         */
        val hapticOnAdvance: Boolean = false,
    )

    /**
     * Fold a delegate/webhook [StepAdvanceResult] into the flow state. Mirrors the old
     * `handleHookResult` closure exactly.
     */
    fun apply(
        flow: OnboardingFlowConfig,
        currentIndex: Int,
        responses: Map<String, Any>,
        result: StepAdvanceResult,
        navigationHistory: List<String> = emptyList(),
        step: OnboardingStep? = flow.steps.getOrNull(currentIndex),
    ): AdvanceOutcome {
        val stepId = step?.id ?: ""
        return when (result) {
            is StepAdvanceResult.Proceed ->
                advance(flow, currentIndex, responses, navigationHistory)

            is StepAdvanceResult.ProceedWithData -> {
                val merged = mergeData(responses, result.data, stepId)
                advance(flow, currentIndex, merged, navigationHistory)
                    .copy(computedData = result.data)
            }

            is StepAdvanceResult.Block -> AdvanceOutcome(
                navigation = Navigation.Stay,
                responses = responses,
                banner = Banner.Error(result.message),
            )

            is StepAdvanceResult.SkipTo -> {
                val merged = result.data?.let { mergeData(responses, it, stepId) } ?: responses
                skipTo(flow, currentIndex, result.stepId, merged, navigationHistory)
                    .copy(computedData = result.data)
            }

            // SPEC-070-A C.8 — Stay renders the success banner when a message is present,
            // otherwise stays silent so the host can drive its own UI.
            is StepAdvanceResult.Stay -> AdvanceOutcome(
                navigation = Navigation.Stay,
                responses = responses,
                banner = result.message?.takeIf { it.isNotEmpty() }?.let { Banner.Success(it) },
            )
        }
    }

    /** Mirrors the old `mergeData` closure: deep-merge into the step's own response map. */
    fun mergeData(
        responses: Map<String, Any>,
        extraData: Map<String, Any>,
        stepId: String,
    ): Map<String, Any> {
        val out = responses.toMutableMap()
        val existing = responses[stepId]
        if (existing is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            val merged = (existing as Map<String, Any>).toMutableMap()
            merged.putAll(extraData)
            out[stepId] = merged
        } else {
            out[stepId] = extraData
        }
        return out
    }

    /** Mirrors the old `skipToStep` closure. Unknown target falls through to [advance]. */
    fun skipTo(
        flow: OnboardingFlowConfig,
        currentIndex: Int,
        targetStepId: String,
        responses: Map<String, Any>,
        navigationHistory: List<String> = emptyList(),
    ): AdvanceOutcome {
        val targetIndex = flow.steps.indexOfFirst { it.id == targetStepId }
        if (targetIndex < 0) return advance(flow, currentIndex, responses, navigationHistory)
        val fromStepId = flow.steps.getOrNull(currentIndex)?.id
        // SPEC-070-A finalization P0 audit-8 D1 — push the leaving step before skip-target
        // navigation so the destination's previous_step_* rules see the correct prevId.
        return AdvanceOutcome(
            navigation = Navigation.GoToIndex(targetIndex),
            responses = responses,
            // SPEC-070-B — a skip_to jump used to be analytically INVISIBLE: the flow silently
            // teleported over N steps, so the funnel could not tell a step that was jumped over
            // from one the user never reached. Emit the jump.
            events = listOf(
                TrackedEvent(
                    STEP_SKIPPED_EVENT,
                    buildMap {
                        put("flow_id", flow.id)
                        fromStepId?.let { put("from_step_id", it) }
                        put("to_step_id", targetStepId)
                    },
                ),
            ),
            historyPush = listOfNotNull(fromStepId),
        )
    }

    /**
     * Mirrors the old `advanceOrComplete` closure: evaluate the current step's next-step rules,
     * else advance sequentially / complete.
     */
    fun advance(
        flow: OnboardingFlowConfig,
        currentIndex: Int,
        responses: Map<String, Any>,
        navigationHistory: List<String> = emptyList(),
    ): AdvanceOutcome {
        val events = mutableListOf<TrackedEvent>()
        // SPEC-070-A finalization P0 audit-7 — push the step we're leaving BEFORE the rules
        // branch decides where to go: every routing path advances away from the current step.
        val leaving = flow.steps.getOrNull(currentIndex)?.id
        val historyPush = listOfNotNull(leaving)
        val history = navigationHistory + historyPush

        fun outcome(nav: Navigation) = AdvanceOutcome(
            navigation = nav,
            responses = responses,
            events = events.toList(),
            historyPush = historyPush,
            hapticOnAdvance = true,
        )

        fun completeWithMarker(key: String, value: String): AdvanceOutcome {
            val merged = responses.toMutableMap()
            merged[key] = value
            return outcome(Navigation.CompleteFlow(merged.toMap()))
        }

        val step = if (currentIndex < flow.steps.size) flow.steps[currentIndex] else null
        // SPEC-070-A audit Round 2-restart attempt 2 F1: prefer the layout-level
        // `step.config.next_step_rules` (Logic-panel-authored) when it carries richer
        // `conditions[]` than the step-level rules. Mirrors iOS OnboardingRenderer.swift:761-766.
        val stepRules = step?.next_step_rules
        val layoutRules = step?.config?.next_step_rules
        val hasLayoutConditions = layoutRules?.any { !it.conditions.isNullOrEmpty() } == true
        val hasStepConditions = stepRules?.any { !it.conditions.isNullOrEmpty() } == true
        val rules = when {
            hasLayoutConditions && !hasStepConditions -> layoutRules
            !stepRules.isNullOrEmpty() -> stepRules
            else -> layoutRules
        }
        val isLastStep = currentIndex >= flow.steps.size - 1
        if (step != null && !rules.isNullOrEmpty()) {
            // SPEC-070-A A.21: first matching rule wins; unmatched rules fall through.
            for (rule in rules) {
                val matches = NextStepRuleEvaluator.evaluateRule(
                    rule = rule,
                    stepId = step.id,
                    responses = responses,
                    step = step,
                    // SPEC-401-A R50 (Lens B P0) — the just-pushed current step sits at the top
                    // of `history`, so the entry-prior step is `size - 2`.
                    previousStepId = history.elementAtOrNull(history.size - 2),
                    // A condition may name an earlier step's field; without the step list it cannot
                    // resolve which step that is.
                    allSteps = flow.steps,
                )
                if (!matches) continue

                val rawClassified = classifyRuleTarget(rule.target_step_id)
                // SPEC-401-A R10 — upgrade short-id paywall_trigger / end / analytics graph nodes.
                @Suppress("UNCHECKED_CAST")
                when (
                    val classified =
                        upgradeToShortIdRuleTarget(rawClassified, flow.graph_nodes as? Map<String, Any?>)
                ) {
                    is RuleTarget.Empty -> continue
                    is RuleTarget.PaywallTrigger ->
                        return outcome(Navigation.PresentPaywallTrigger(classified.rawTarget))
                    is RuleTarget.EndFlow -> return outcome(Navigation.CompleteFlow(responses))
                    // Mirror iOS auto-route: surface the target in the completion payload so the
                    // host (or paywall bridge) can act on it. Marker sentinels.
                    is RuleTarget.Permission ->
                        return completeWithMarker("__permission_request", classified.name)
                    is RuleTarget.Screen ->
                        return completeWithMarker("__screen_present", classified.screenId)
                    is RuleTarget.SubFlow ->
                        return completeWithMarker("__sub_flow", classified.flowId)
                    is RuleTarget.Step -> {
                        val targetIndex = flow.steps.indexOfFirst { it.id == classified.stepId }
                        if (targetIndex >= 0) return outcome(Navigation.GoToIndex(targetIndex))
                    }
                    is RuleTarget.AnalyticsEvent -> {
                        // SPEC-070-A finalization Phase D — analytics_event graph node. Mirrors
                        // iOS OnboardingRenderer.swift:789-801: fire `event_name` (default
                        // "onboarding_analytics") with {flow_id, node_id, step_id}, then follow
                        // `next_target` if set, else fall through to the next rule.
                        @Suppress("UNCHECKED_CAST")
                        val nodeData = (flow.graph_nodes?.get(classified.nodeId) as? Map<String, Any?>)
                        val eventName = (nodeData?.get("event_name") as? String) ?: "onboarding_analytics"
                        events.add(
                            TrackedEvent(
                                eventName,
                                mapOf(
                                    "flow_id" to flow.id,
                                    "node_id" to classified.nodeId,
                                    "step_id" to step.id,
                                ),
                            ),
                        )
                        val nextTarget = nodeData?.get("next_target") as? String
                        if (!nextTarget.isNullOrBlank()) {
                            val tIdx = flow.steps.indexOfFirst { it.id == nextTarget }
                            // SPEC-401-A R3 — DO NOT push history again here; the leaving step
                            // was already pushed above.
                            if (tIdx >= 0) return outcome(Navigation.GoToIndex(tIdx))
                        }
                        continue
                    }
                    is RuleTarget.Unknown -> continue
                }
            }
            // No rule matched. On the last step this is a rule-failure bailout, not a natural
            // end — emit the distinguishing event so ETL can tell them apart.
            if (isLastStep) {
                events.add(
                    TrackedEvent(
                        FLOW_COMPLETED_VIA_FALLBACK_EVENT,
                        mapOf(
                            "flow_id" to flow.id,
                            "step_id" to (flow.steps.lastOrNull()?.id ?: ""),
                            "step_index" to (flow.steps.size - 1).coerceAtLeast(0),
                        ),
                    ),
                )
                return outcome(Navigation.CompleteFlow(responses))
            }
        }
        // Fallback: sequential advance.
        return if (currentIndex + 1 >= flow.steps.size) {
            outcome(Navigation.CompleteFlow(responses))
        } else {
            outcome(Navigation.GoToIndex(currentIndex + 1))
        }
    }
}

/**
 * The event fired when a step-advance hook (host delegate OR server webhook) returns.
 *
 * A constant rather than a literal at the two call sites in `OnboardingFlowHost`: the shared
 * cross-platform fixtures assert this exact name, so a rename must break them rather than silently
 * retire the event that the onboarding funnel is built on.
 */
internal const val ONBOARDING_HOOK_COMPLETED_EVENT = "onboarding_hook_completed"

/**
 * SPEC-070-B — the event fired when the flow JUMPS over one or more steps because a hook (host
 * delegate or server webhook) returned `StepAdvanceResult.SkipTo(stepId)`.
 *
 * WHY A NEW NAME AND NOT `onboarding_step_skipped`:
 * `onboarding_step_skipped` (OnboardingFlowManager.onStepSkipped, iOS OnboardingFlowManager.swift:95)
 * already means something else and MUST keep meaning it — the user tapped the step's own **Skip
 * button**, so its props are `{flow_id, step_id, step_index}`: one step, declined by the user, and
 * the flow then advances normally to the NEXT step. A `skip_to` is a *programmatic branch*: the
 * host jumps the flow to an arbitrary target and every step in between is bypassed without ever
 * being viewed. The two carry different props (`step_id` vs `from_step_id`/`to_step_id`), have
 * different actors (user vs host), and answer different funnel questions ("how many people declined
 * this step?" vs "which steps were branched over?"). Folding them into one name would make both
 * unanalysable, so this is a NON-COLLIDING new name.
 *
 * The spelling + props are the cross-platform contract asserted by the shared fixture
 * `packages/sdk-shared-fixtures/step_advance/skip_to_target_step.fixture.json` (`step_skipped` with
 * `from_step_id` / `to_step_id`); iOS emits the identical name and props. `flow_id` rides along for
 * consistency with every other onboarding event.
 */
internal const val STEP_SKIPPED_EVENT = "step_skipped"

/**
 * The wire spelling of a [StepAdvanceResult] — the `result` property on
 * [ONBOARDING_HOOK_COMPLETED_EVENT], and the discriminator every webhook body uses.
 *
 * Lifted out of the `resultName` closure inside `@Composable OnboardingFlowHost` (verbatim; the
 * composable now calls this) so it is assertable without a Compose tree. These five strings are the
 * contract and are never localized or renamed.
 */
internal fun stepAdvanceResultName(result: StepAdvanceResult): String = when (result) {
    is StepAdvanceResult.Proceed -> "proceed"
    is StepAdvanceResult.ProceedWithData -> "proceed_with_data"
    is StepAdvanceResult.Block -> "block"
    is StepAdvanceResult.SkipTo -> "skip_to"
    is StepAdvanceResult.Stay -> "stay"
}

/**
 * Mirrors the old `applyOverrides` closure in `OnboardingFlowHost` — merges an
 * `onBeforeStepRender` override onto a step's config. Extracted so hosts/tests can assert the
 * merge without standing up a Compose tree.
 */
internal fun StepConfig.applyingOverride(o: StepConfigOverride): StepConfig = copy(
    title = o.title ?: title,
    subtitle = o.subtitle ?: subtitle,
    cta_text = o.ctaText ?: cta_text,
    field_defaults = o.fieldDefaults ?: field_defaults,
    // SPEC-448 §B — host-supplied options.
    //
    // ⚠️ NOT another flat `copy()` line like the four above. Those replace scalars on the step;
    // options live at `content_blocks[i].field_options`, one level down inside an ImmutableList.
    // So this maps over the blocks and rebuilds only the NAMED ones, leaving every sibling
    // untouched. Rebuilding the array from just the named blocks would silently delete the rest of
    // the step — invisible until an author noticed a missing block, which is why the fixture
    // asserts the untouched siblings survive.
    content_blocks = applyBlockLevelOverrides(content_blocks, o),
)

/**
 * The two override kinds that live one level down, inside `content_blocks`.
 *
 * ⚠️ NOT flat `copy()` lines like the four above. Those replace scalars on the step; these reach
 * into a named block. Both map over EVERY block and rebuild only the named ones — rebuilding the
 * array from just the named blocks would silently delete the rest of the step, invisible until an
 * author noticed a missing block, which is why the fixtures assert the untouched siblings survive.
 *
 * They compose: a step may legitimately have a host-supplied option list on one block and a
 * host-supplied route on another, so this runs both passes rather than choosing between them.
 */
private fun applyBlockLevelOverrides(
    blocks: ImmutableList<ContentBlock>?,
    o: StepConfigOverride,
): ImmutableList<ContentBlock>? {
    if (blocks == null) return null
    if (o.fieldOptions.isNullOrEmpty() && o.mapRoutes.isNullOrEmpty()) return blocks
    return blocks.map { block ->
        var next = block
        // SPEC-448 §B — host-supplied options.
        o.fieldOptions?.get(block.id)?.let { next = next.copy(field_options = it.toImmutableList()) }
        // SPEC-451 — host-supplied route. Written into `field_config` under the SAME keys the
        // console authors, so the renderer has exactly one code path and a delegate route cannot
        // render differently from an authored one.
        o.mapRoutes?.get(block.id)?.let { route ->
            val patch = mutableMapOf<String, Any>()
            route.polyline?.let {
                patch["map_route_polyline"] = it
                // A host that answered with a route outranks a `{{token}}` the author wired as the
                // fallback. Clearing it here is what makes that ordering true — leaving it would
                // let a stale variable win over a live answer.
                patch["map_route_variable"] = ""
            }
            if (route.stops.isNotEmpty()) {
                patch["map_stops"] = route.stops.map { s ->
                    val m = mutableMapOf<String, Any>("lat" to s.lat, "lng" to s.lng)
                    s.title?.let { m["title"] = it }
                    m
                }
            }
            if (patch.isNotEmpty()) {
                next = next.copy(field_config = (next.field_config ?: emptyMap()) + patch)
            }
        }
        next
    }.toImmutableList()
}

/**
 * Mirrors the `socialClick` closure in `ContentBlockRenderer` — the actions a social-login
 * provider button emits, colon-encoded as `action:value`.
 *
 * SPEC-070-A C.1 — the `email` provider in a social_login block is not OAuth, so it dual-emits
 * `email_login:email` (canonical) plus the legacy `social_login:email` that existing handlers
 * switch on. Legacy emit removed in v1.1.0 alongside iOS.
 */
internal fun socialProviderActions(providerType: String): List<String> =
    if (providerType == "email") {
        listOf("email_login:email", "social_login:email")
    } else {
        listOf("social_login:$providerType")
    }
