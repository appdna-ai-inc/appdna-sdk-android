package ai.appdna.sdk

import ai.appdna.sdk.core.LocalizationEngine
import ai.appdna.sdk.core.TemplateContext
import ai.appdna.sdk.core.interpolated
import ai.appdna.sdk.onboarding.AuthSecretRedactor
import ai.appdna.sdk.onboarding.ContentBlock
import ai.appdna.sdk.onboarding.ElementInteractionResult
import ai.appdna.sdk.onboarding.HostDataDecodeFailure
import ai.appdna.sdk.onboarding.HostDataInputs
import ai.appdna.sdk.onboarding.HostDataInteractionAccess
import ai.appdna.sdk.onboarding.HostDataInteractionStore
import ai.appdna.sdk.onboarding.HostDataPresentation
import ai.appdna.sdk.onboarding.HostDataPresentations
import ai.appdna.sdk.onboarding.HostDataStepPipeline
import ai.appdna.sdk.onboarding.InputOption
import ai.appdna.sdk.onboarding.InteractionCoordinator
import ai.appdna.sdk.onboarding.OnboardingConfigParser
import ai.appdna.sdk.onboarding.OnboardingStep
import ai.appdna.sdk.onboarding.RequiredFieldGate
import ai.appdna.sdk.onboarding.ResolvedHostDataStep
import ai.appdna.sdk.onboarding.SelectedOptionStore
import ai.appdna.sdk.onboarding.StepConfigOverride
import ai.appdna.sdk.onboarding.applyInteractionReply
import ai.appdna.sdk.onboarding.applyingOverride
import ai.appdna.sdk.onboarding.beforeStepRenderEffect
import ai.appdna.sdk.onboarding.filterOptionsLocally
import ai.appdna.sdk.onboarding.isHiddenByHostData
import ai.appdna.sdk.onboarding.lookupOnlyLoc
import ai.appdna.sdk.onboarding.mapRoutePolyline
import ai.appdna.sdk.onboarding.pruneHostDataSelections
import ai.appdna.sdk.onboarding.recordOptionPick
import ai.appdna.sdk.onboarding.resolveBlockBindings
import ai.appdna.sdk.onboarding.resolveForInteractionAdvance
import ai.appdna.sdk.onboarding.resolveSheetBlocks
import ai.appdna.sdk.onboarding.resolvedFieldConfig
import ai.appdna.sdk.onboarding.resolvedFieldOptions
import ai.appdna.sdk.onboarding.runBeforeStepRenderHook
import ai.appdna.sdk.onboarding.storedAnswer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * `host_data_scenario`: a presented onboarding step driven through the REAL SDK seams the
 * step view uses, in the order the view uses them:
 *
 *   OnboardingConfigParser (raw capture + stamping) → HostDataPresentations (pending starts before
 *   the first resolve) → runBeforeStepRenderHook (generation tokens, independent 3 s deadline,
 *   re-fire after cancellation, onStepViewed once, the `callSeq` base stamp) → HostDataStepPipeline
 *   (raw pass → decode with per-key revert → StepConfigOverride merge → interaction layering) →
 *   pruneHostDataSelections → RequiredFieldGate.evaluateDetailed / lookup-only loc / mapRoutePolyline /
 *   filterOptionsLocally / recordOptionPick / resolveSheetBlocks / AuthSecretRedactor.
 *
 * Every element interaction goes through the production [InteractionCoordinator]
 * (one per presentation, as `remember(myPresentation)` builds it), its reply through the production
 * [applyInteractionReply], and `hook_data` is the production [HostDataInteractionAccess] effective map
 * over a [HostDataInteractionStore] — the same flow-level owner the ViewModel holds. The driver models
 * the flow's navigation the way production does it: a revisit navigates to a placeholder step at index
 * 1 (`stepCount = 2`) and back, so `presentationFor` creates a NEW presentation and the
 * `lastViewedStepIndex` gate re-fires `onBeforeStepRender`.
 *
 * Virtual time (kotlinx-coroutines-test), so `advance_clock_ms` is exact and a non-cooperative host
 * is a real coroutine that ignores cancellation.
 *
 * PLUMBING: the only things the runner does itself are what a Compose frame does — "recompose"
 * (call the pipeline again with the current inputs and apply the selection pruning the step's
 * LaunchedEffect applies), and `option_set_queries`, which counts the blocks whose Select would
 * query `OptionSetStore` (the view queries it iff `field_config.option_set_id` is non-empty).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class HostDataScenarioDriver(
    private val stepJson: Map<String, Any?>,
    private val baselineResponses: Map<String, Any?>,
    private val baselineUser: Map<String, Any?>?,
    private val baselineSession: Map<String, Any?>?,
    private val baselineSelected: Map<String, Any?>?,
    private val legacyContext: TemplateContext,
) {
    // ── flow-level state (the ViewModel's) ──────────────────────────────────────────────────────
    private val presentations = HostDataPresentations()
    private val configOverrides = mutableMapOf<String, StepConfigOverride>()
    private val interactionStore = HostDataInteractionStore()
    private val access = HostDataInteractionAccess(configOverrides, interactionStore, presentations)
    /** The ViewModel's `lastViewedStepIndex` — survives the simulated Activity recreation. */
    private var lastViewedStepIndex = -1
    private var delegateCalls = 0
    private var stepViewedCalls = 0
    private var pendingReply: CompletableDeferred<StepConfigOverride?>? = null
    private val releaseNonCooperative = CompletableDeferred<Unit>()
    private var hookJob: Job? = null
    private var presentedOnce = false
    /** Each step id's last written `responses_step` (a Back restores it as `savedResponses`). */
    private val lastResponses = mutableMapOf<String, Map<String, Any?>>()

    // ── the host's onElementInteraction ─────────────────────────────────────────────────────────
    private sealed class CallPlan {
        class Sync(val result: ElementInteractionResult?) : CallPlan()
        object Async : CallPlan()
    }
    private val plans = ArrayDeque<CallPlan>()
    private class HostCall(val reply: CompletableDeferred<ElementInteractionResult?>, var answered: Boolean = false)
    /** Calls started by `tap` / `fire_interaction`, in start order (`call_index`). */
    private val hostCalls = mutableListOf<HostCall>()
    /** Every call sent to the host: `{step_id, block_id, action, value}`. */
    private val interactionCalls = mutableListOf<Map<String, Any?>>()

    private lateinit var testScope: TestScope

    // ── one live step composition ───────────────────────────────────────────────────────────────
    private inner class StepState(
        val step: OnboardingStep,
        val stepIndex: Int,
        val pipeline: HostDataStepPipeline?,
        var presentation: HostDataPresentation,
    ) {
        val inputValues = mutableMapOf<String, Any>()
        val toggleValues = mutableMapOf<String, Boolean>()
        var fieldConfigOverrides = mutableMapOf<String, Map<String, Any>>()
        var fieldOptionsOverrides = mutableMapOf<String, List<InputOption>>()
        val searchQueries = mutableMapOf<String, String>()
        var firstResolvePending: Boolean? = null
        var resolved: ResolvedHostDataStep? = null
        var layered: List<ContentBlock> = emptyList()
        var rawIds: Set<String> = emptySet()
        var sheetBlocks: List<ContentBlock> = emptyList()
        var redactedFieldIds: List<String>? = null
        var responsesStep: Map<String, Any?>? = null
        /** The composition's `rememberCoroutineScope()` — cancelled when the composition leaves. */
        var scope: CoroutineScope = newScope()
        lateinit var coordinator: InteractionCoordinator

        /** `remember(myPresentation) { InteractionCoordinator(…) }`, with production's injected closures. */
        fun rebuildCoordinator() {
            val mine = presentation
            fieldConfigOverrides = mutableMapOf()
            fieldOptionsOverrides = mutableMapOf()
            coordinator = InteractionCoordinator(
                stepId = step.id,
                scope = scope,
                isCurrentPresentation = { presentations.current === mine },
                drawSeq = access::nextCallSeq,
                hasHandler = { true }, // the scripted host always implements onElementInteraction
                call = { stepId, blockId, action, value, _ -> hostCall(stepId, blockId, action, value) },
            )
        }

        fun inputs() = HostDataInputs(
            hookData = access.effectiveHookData(step.id),
            responses = baselineResponses,
            stepInputs = inputValues.toMap(),
            selected = SelectedOptionStore.snapshot(),
            user = baselineUser,
            session = baselineSession,
            pending = presentation.pending,
            stepOverride = configOverrides[step.id],
            fieldConfigOverrides = fieldConfigOverrides.toMap(),
            fieldOptionsOverrides = fieldOptionsOverrides.toMap(),
        )
    }

    private lateinit var cur: StepState
    /** The outgoing step of a `begin_transition_to`, still composed (the exit transition). */
    private var outgoing: StepState? = null

    private fun newScope(): CoroutineScope {
        val parent = testScope.backgroundScope.coroutineContext
        return CoroutineScope(parent + Job(parent[Job]))
    }

    /** Runs [script]; returns the observations at every checkpoint, by name. */
    fun run(script: List<Map<String, Any?>>): Map<String, Map<String, Any?>> {
        val checkpoints = LinkedHashMap<String, Map<String, Any?>>()
        runTest {
            testScope = this
            try {
                for (op in script) {
                    when (val name = op["op"]) {
                        "present_step" -> presentStep(op)
                        "delegate_reply" -> delegateReply(op)
                        "cancel_delegate_call" -> {
                            // Activity recreation: the effect's coroutine is cancelled mid-call…
                            hookJob?.cancel(); runCurrent()
                            // …and the recreated composition relaunches its LaunchedEffect(currentIndex)
                            // UNCONDITIONALLY; the production gate (`beforeStepRenderEffect`) decides
                            // whether that re-fires the delegate.
                            launchStepEffect(cur, delegateMode)
                        }
                        "advance_clock_ms" -> { advanceTimeBy((op["ms"] as Number).toLong()); runCurrent() }
                        "set_step_input" -> {
                            val fid = op["field_id"] as String
                            val v = op["value"]
                            if (v == null) cur.inputValues.remove(fid) else cur.inputValues[fid] = v
                        }
                        "toggle" -> cur.toggleValues[op["block_id"] as String] = op["value"] as Boolean
                        "select_option" -> {
                            val (st, block) = ownerOf(op["block_id"] as String)
                            recordOptionPick(block, block.field_options.orEmpty(), op["value"].toString(), st.inputValues)
                        }
                        "type_search" -> cur.searchQueries[op["block_id"] as String] = op["query"] as String
                        "open_sheet" -> openSheet(op)
                        "element_interaction" -> elementInteraction(op)
                        "complete_step" -> completeStep(cur)
                        "tap" -> tap(op)
                        "fire_interaction" -> fireInteraction(op)
                        "interaction_reply" -> interactionReply(op)
                        "interaction_throw" -> interactionThrow(op)
                        "leave_step" -> leaveStep()
                        "begin_transition_to" -> beginTransitionTo(op)
                        "reuse_view_new_presentation" -> reuseViewNewPresentation()
                        "recreate" -> recreate()
                        "refire_before_render" -> error("refire_before_render is iOS-only (the paywall-uncover re-fire)")
                        "checkpoint" -> { recompose(); checkpoints[op["name"] as String] = observe() }
                        else -> error("host_data_scenario: unknown op $name")
                    }
                    runCurrent()
                    recompose()
                }
            } finally {
                releaseNonCooperative.complete(Unit)
                hookJob?.cancel()
            }
        }
        // The driver writes snapshot state (the interaction layer, the coordinator's in-flight /
        // loading flags) with no Compose host running to flush it. Left pending, those global
        // changes make a LATER Compose test in the same JVM never go idle. Flush them here.
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        return checkpoints
    }

    private var delegateMode = "none"

    // ── navigation ──────────────────────────────────────────────────────────────────────────────

    private val placeholderStep: OnboardingStep by lazy {
        OnboardingConfigParser.parseStepForTest(
            mapOf("id" to "__placeholder__", "type" to "value_prop", "config" to emptyMap<String, Any>()),
        ) ?: error("placeholder step did not parse")
    }

    /**
     * Production Back / advance: the index moves to the placeholder at 1, whose presentation becomes
     * current (the placeholder has no `hook_data`, so it is never pending), and its
     * `LaunchedEffect(currentIndex)` records `lastViewedStepIndex = 1`. The left step's effect is
     * cancelled with its index.
     */
    private fun navigateToPlaceholder() {
        hookJob?.cancel()
        presentations.presentationFor(placeholderStep, 1, hasDelegate = false, hasCachedOverride = false)
        lastViewedStepIndex = 1
    }

    // ── ops ─────────────────────────────────────────────────────────────────────────────────────

    private fun parseStep(json: Map<String, Any?>): OnboardingStep {
        @Suppress("UNCHECKED_CAST")
        val stepMap = (json.filterValues { it != null } as Map<String, Any>)
        return OnboardingConfigParser.parseStepForTest(stepMap) ?: error("present_step: step did not parse")
    }

    private fun TestScope.presentStep(op: Map<String, Any?>) {
        var step = parseStep(stepJson)
        if (op["raw"] == "absent") step = step.copy(rawContentBlocks = null)
        delegateMode = op["delegate"] as? String ?: "none"
        if (!presentedOnce) {
            // A revisit keeps the store: a real Back does not reset it.
            SelectedOptionStore.resetForTesting()
            baselineSelected?.forEach { (k, v) -> if (v != null) SelectedOptionStore.seedForTesting(k, v) }
        } else if (presentations.current?.stepIndex == 0) {
            // A revisit always follows a navigation away (leave_step / an ended step); a script that
            // presents again without one still gets a new presentation, as production would.
            navigateToPlaceholder()
        }
        val saved: Map<*, *>? = if (op.containsKey("saved_responses")) op["saved_responses"] as? Map<*, *> else lastResponses[step.id]
        // The previous composition of this step (if any) has left.
        if (::cur.isInitialized) cur.scope.cancel()
        outgoing?.scope?.cancel(); outgoing = null

        val pipeline = if (step.rawContentBlocks != null) HostDataStepPipeline(step, legacyContext = { legacyContext }) else null
        (op["force_decode_failure"] as? Map<*, *>)?.let { f ->
            val blockId = f["block_id"] as String
            val keyPath = f["key_path"] as String
            pipeline?.decodeGuard = { id, current, post ->
                // The decode rejects the block while the key holds its RESOLVED value.
                if (id == blockId && valueAt(current, keyPath, post) != null) throw HostDataDecodeFailure(keyPath)
            }
        }
        val presentation = presentations.presentationFor(
            step = step,
            stepIndex = 0,
            hasDelegate = delegateMode != "none",
            // The REAL layer-aware "cached" value, latched when the presentation is created.
            hasCachedOverride = access.cachedAtArrival(step),
        )
        val st = StepState(step, 0, pipeline, presentation)
        // OnboardingStepView's savedResponses pre-population, verbatim.
        saved?.forEach { (k, v) ->
            val key = k.toString()
            if (v == null) return@forEach
            if (key.startsWith("toggle_") && v is Boolean) st.toggleValues[key.removePrefix("toggle_")] = v
            else if (!key.startsWith("toggle_") && key != "action" && key != "selected" && key != "selection_mode") st.inputValues[key] = v
        }
        st.rebuildCoordinator()
        cur = st
        presentedOnce = true
        // The first resolve happens in the same frame, BEFORE the delegate call starts.
        recompose()
        st.firstResolvePending = presentation.pending
        launchStepEffect(st, delegateMode)
    }

    /** The Activity's `LaunchedEffect(currentIndex)`, verbatim: the production gate, then the hook. */
    private fun TestScope.launchStepEffect(st: StepState, mode: String) {
        val p = st.presentation
        hookJob = launch {
            beforeStepRenderEffect(
                stepIndex = st.stepIndex,
                // The placeholder step at index 1 ("How the Android revisit re-fires").
                stepCount = 2,
                presentation = p,
                lastViewedStepIndex = { lastViewedStepIndex },
                setLastViewedStepIndex = { lastViewedStepIndex = it },
            ) {
            runBeforeStepRenderHook(
                presentation = p,
                hostScope = backgroundScope,
                call = {
                    when (mode) {
                        "none" -> null
                        "awaiting" -> {
                            delegateCalls++
                            val d = CompletableDeferred<StepConfigOverride?>()
                            pendingReply = d
                            d.await()
                        }
                        "non_cooperative" -> {
                            delegateCalls++
                            // Ignores cancellation entirely — the host that never answers. Released
                            // only at teardown so the test scheduler can finish.
                            withContext(NonCancellable) { releaseNonCooperative.await() }
                            null
                        }
                        else -> error("present_step: unknown delegate mode $mode")
                    }
                },
                onOverride = {},
                drawSeq = access::nextCallSeq,
                // The flow host's write: only while the user is still on this step.
                onStampedOverride = { o, seq ->
                    if (presentations.current?.stepId == st.step.id) access.applyBeforeRenderReply(st.step.id, o, seq)
                },
                onStepViewed = { stepViewedCalls++ },
                stillPresented = { presentations.current === p },
            )
            }
        }
        runCurrent()
    }

    @Suppress("UNCHECKED_CAST")
    private fun delegateReply(op: Map<String, Any?>) {
        val dc = op["data_context"] as? Map<String, Any?>
        val fo = op["field_options"] as? Map<*, *>
        val reply = if (dc == null && fo == null) null else StepConfigOverride(
            dataContext = dc?.filterValues { it != null } as Map<String, Any>?,
            fieldOptions = fo?.let { StepConfigOverride.decodeFieldOptions(it) },
        )
        val d = pendingReply ?: error("delegate_reply: no delegate call in flight")
        pendingReply = null
        d.complete(reply)
    }

    /** The scripted host's `onElementInteraction`: the head plan decides sync vs. in-flight. */
    private suspend fun hostCall(stepId: String, blockId: String, action: String, value: String?): ElementInteractionResult? {
        interactionCalls += linkedMapOf("step_id" to stepId, "block_id" to blockId, "action" to action, "value" to value)
        return when (val plan = plans.removeFirstOrNull() ?: error("host call without a plan")) {
            is CallPlan.Sync -> plan.result
            CallPlan.Async -> {
                val d = CompletableDeferred<ElementInteractionResult?>()
                hostCalls += HostCall(d)
                d.await()
            }
        }
    }

    /** One interaction through [st]'s coordinator. Returns whether it started (a refused start is no call). */
    private fun TestScope.startInteraction(st: StepState, blockId: String, action: String, value: String?, plan: CallPlan): Boolean {
        plans.addLast(plan)
        val job = st.coordinator.start(blockId, action, value, st.inputValues.toMap(), st.presentation.pending) { result, stamp ->
            onInteractionReply(st, result, stamp)
        }
        if (job == null) {
            plans.removeLast()
            return false
        }
        runCurrent()
        return true
    }

    /** BlockBasedStepView.handleInteract's reply lambda, verbatim. */
    private fun onInteractionReply(st: StepState, result: ElementInteractionResult, stamp: Long) {
        applyInteractionReply(result, stamp, st.step.id, st.inputValues, access, st.fieldConfigOverrides, st.fieldOptionsOverrides)
        // `advance: true` → handleAction("next") BEFORE the next frame: canAdvance judges the step
        // re-resolved with what was just written AND its vanished selections pruned (the
        // `interactionGate` of BlockBasedStepView, via the same resolveForInteractionAdvance).
        // Blocked → the step stays; allowed → onNext.
        if (result.advance) {
            val isCurrent = st === cur && presentations.current === st.presentation
            val fresh = st.pipeline?.let { p -> resolveForInteractionAdvance(p, isCurrent, st.inputValues) { st.inputs() } }
            val gate = if (fresh != null) RequiredFieldGate.evaluateDetailed(fresh.blocks, st.inputValues, fresh.rawResolvedIds)
                else RequiredFieldGate.evaluateDetailed(st.layered, st.inputValues, st.rawIds)
            if (gate.canAdvance) completeStep(st)
        }
    }

    private fun isRefreshButton(b: ContentBlock) = b.type == "button" && b.action == "refresh_step"

    private fun TestScope.tap(op: Map<String, Any?>) {
        val (st, block) = ownerOf(op["block_id"] as String)
        require(isRefreshButton(block)) { "tap: ${block.id} is not a refresh_step button (use fire_interaction)" }
        startInteraction(st, block.id, ElementInteractionResult.REFRESH_ACTION, block.action_value, CallPlan.Async)
    }

    private fun TestScope.fireInteraction(op: Map<String, Any?>) {
        val (st, block) = ownerOf(op["block_id"] as String)
        startInteraction(st, block.id, op["action"] as String, op["value"] as String?, CallPlan.Async)
    }

    @Suppress("UNCHECKED_CAST")
    private fun resultOf(raw: Map<String, Any?>?): ElementInteractionResult? = raw?.let { result ->
        ElementInteractionResult(
            fieldConfigPatches = (result["field_config_patches"] as? Map<String, Map<String, Any>>),
            inputValuePatches = (result["input_values"] as? Map<String, Any>),
            fieldOptions = (result["field_options"] as? Map<*, *>)?.let { StepConfigOverride.decodeFieldOptions(it) },
            advance = result["advance"] == true,
            dataContext = ElementInteractionResult.decodeDataContext(result["data_context"]),
        )
    }

    private fun callAt(op: Map<String, Any?>): HostCall {
        val idx = (op["call_index"] as? Number)?.toInt() ?: (hostCalls.size - 1)
        val call = hostCalls.getOrNull(idx) ?: error("no interaction call #$idx (started: ${hostCalls.size})")
        check(!call.answered) { "call #$idx was already answered — the fixture is invalid" }
        call.answered = true
        return call
    }

    @Suppress("UNCHECKED_CAST")
    private fun interactionReply(op: Map<String, Any?>) {
        // Delivered even when the SDK already ended the call (deadline / scope cancellation): dropped there.
        callAt(op).reply.complete(resultOf(op["result"] as? Map<String, Any?>))
    }

    private fun interactionThrow(op: Map<String, Any?>) {
        callAt(op).reply.completeExceptionally(IllegalStateException("scripted host threw"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun TestScope.elementInteraction(op: Map<String, Any?>) {
        val (st, block) = ownerOf(op["block_id"] as String)
        val action = op["action"] as? String
            ?: if (isRefreshButton(block)) ElementInteractionResult.REFRESH_ACTION
            else error("element_interaction: action is required for ${block.id}")
        val value = if (op.containsKey("value")) op["value"] as String? else block.action_value
        val result = resultOf(op["result"] as? Map<String, Any?> ?: emptyMap())
        startInteraction(st, block.id, action, value, CallPlan.Sync(result))
    }

    private fun TestScope.leaveStep() {
        // Back: the presentation ends, no responses are written, and (Android) the step composition
        // leaves after the exit tween — its scope is cancelled, which ends any call in flight.
        navigateToPlaceholder()
        cur.scope.cancel()
        runCurrent()
    }

    private fun TestScope.beginTransitionTo(op: Map<String, Any?>) {
        @Suppress("UNCHECKED_CAST")
        val bJson = op["step"] as Map<String, Any?>
        val b = parseStep(bJson)
        val clash = allBlocksOf(cur).map { it.id }.toSet() intersect
            (b.config.content_blocks.orEmpty().flatMap { flatten(it) }.map { it.id }.toSet())
        require(clash.isEmpty()) { "begin_transition_to: block ids must be unique across both steps: $clash" }
        val mode = op["delegate"] as? String ?: "none"
        hookJob?.cancel() // the LaunchedEffect is keyed on the index, which moved
        val p = presentations.presentationFor(b, 1, hasDelegate = mode != "none", hasCachedOverride = access.cachedAtArrival(b))
        val pipeline = if (b.rawContentBlocks != null) HostDataStepPipeline(b, legacyContext = { legacyContext }) else null
        val st = StepState(b, 1, pipeline, p)
        st.rebuildCoordinator()
        outgoing = cur
        cur = st
        delegateMode = mode
        recompose()
        st.firstResolvePending = p.pending
        launchStepEffect(st, mode)
    }

    private fun TestScope.reuseViewNewPresentation() {
        // 0→1→0 inside the exit tween: the SAME composition (scope and inputValues kept) is shown for a
        // new presentation; `remember(myPresentation)` builds a new coordinator and fresh overlays.
        navigateToPlaceholder()
        val p = presentations.presentationFor(cur.step, 0, hasDelegate = delegateMode != "none", hasCachedOverride = access.cachedAtArrival(cur.step))
        cur.presentation = p
        cur.rebuildCoordinator()
        recompose()
        launchStepEffect(cur, delegateMode)
    }

    private fun TestScope.recreate() {
        // Activity recreation: the step composition (its scope, coordinator, overlays) is new; the
        // ViewModel state — presentations, configOverrides, the layer, callSeq — is kept. An in-flight
        // interaction is cancelled and NOT re-fired.
        cur.scope.cancel()
        runCurrent()
        cur.scope = newScope()
        cur.rebuildCoordinator()
        hookJob?.cancel(); runCurrent()
        launchStepEffect(cur, delegateMode)
    }

    private fun openSheet(op: Map<String, Any?>) {
        val blockId = op["block_id"] as String
        val (st, block) = ownerOf(blockId)
        val option = block.field_options.orEmpty().firstOrNull { it.storedAnswer() == op["option_value"] }
            ?: error("open_sheet: no option ${op["option_value"]}")
        val raw = st.resolved?.sheetRaw?.get("$blockId|${option.id ?: option.value}")
        st.sheetBlocks = if (raw != null) resolveSheetBlocks(raw, emptyMap()) else option.sheet_blocks.orEmpty()
    }

    private fun completeStep(st: StepState) {
        // BlockBasedStepView.handleAction("next") → OnboardingFlowHost onNext, verbatim.
        val merged = mutableMapOf<String, Any>()
        merged.putAll(st.inputValues)
        for ((k, v) in st.toggleValues) merged["toggle_$k"] = v
        val safe = AuthSecretRedactor.redact(merged, st.step).orEmpty()
        st.redactedFieldIds = (merged.keys - safe.keys).sorted()
        st.responsesStep = safe
        lastResponses[st.step.id] = safe
        // The flow advances: this presentation ends (the composition leaves with the next present_step).
        if (st === cur && presentations.current === st.presentation) navigateToPlaceholder()
    }

    // ── one "frame" ─────────────────────────────────────────────────────────────────────────────

    private fun recompose() {
        if (!::cur.isInitialized) return
        outgoing?.let { resolveOnce(it, prune = false) }
        // Production prunes only the step on screen (`hostDataIsCurrent`): a step the user left
        // (leave_step / complete_step) must not touch its inputs or the global SelectedOptionStore.
        val onScreen = presentations.current === cur.presentation
        repeat(4) { if (!resolveOnce(cur, prune = onScreen)) return }
    }

    /** Returns true when a prune changed something (the next frame re-resolves). */
    private fun resolveOnce(st: StepState, prune: Boolean): Boolean {
        val p = st.pipeline
        if (p != null) {
            val r = p.resolve(st.inputs())
            st.resolved = r
            st.layered = r.blocks
            st.rawIds = r.rawResolvedIds
            // The step's LaunchedEffect(resolved blocks) — then the next frame re-resolves.
            return prune && pruneHostDataSelections(r.blocks, r.rawResolvedIds, st.inputValues)
        }
        // No raw → today's path: flow-level applyOverrides, then the view-level pass per block.
        val effective = configOverrides[st.step.id]?.let { st.step.config.applyingOverride(it) } ?: st.step.config
        st.layered = effective.content_blocks.orEmpty()
        st.rawIds = emptySet()
        return false
    }

    /** What ContentBlockRendererView draws for a top-level block (view pass only when there is no raw). */
    @Suppress("UNCHECKED_CAST")
    private fun drawn(st: StepState, block: ContentBlock): ContentBlock = if (st.pipeline != null) block else resolvedFieldOptions(
        resolvedFieldConfig(
            resolveBlockBindings(
                block,
                hookData = access.effectiveHookData(st.step.id)?.filterValues { it != null } as Map<String, Any>?,
                responses = baselineResponses.filterValues { it != null } as Map<String, Any>,
                stepInputs = st.inputValues,
            ),
            st.fieldConfigOverrides,
        ),
        st.fieldOptionsOverrides,
    )

    private fun flatten(b: ContentBlock): List<ContentBlock> {
        val out = mutableListOf<ContentBlock>()
        fun visit(x: ContentBlock) {
            out += x
            x.children?.forEach { visit(it) }
            x.stack_children?.forEach { visit(it) }
        }
        visit(b)
        return out
    }

    private fun allBlocksOf(st: StepState): List<ContentBlock> = st.layered.map { drawn(st, it) }.flatMap { flatten(it) }

    /** The live step (current first, then the outgoing one) that renders [id], and the block. */
    private fun ownerOf(id: String): Pair<StepState, ContentBlock> {
        for (st in listOfNotNull(cur, outgoing)) allBlocksOf(st).firstOrNull { it.id == id }?.let { return st to it }
        error("no rendered block $id")
    }

    private fun renderedText(st: StepState, block: ContentBlock, loc: Boolean): String {
        val text = block.text ?: ""
        if (!loc) return text
        val key = "block.${block.id}.text"
        return if (st.pipeline != null) {
            lookupOnlyLoc(key, text, st.resolved?.localizations, st.step.config.default_locale)
        } else {
            LocalizationEngine.resolve(key, st.step.config.localizations, st.step.config.default_locale, text).interpolated()
        }
    }

    private fun observeBlock(st: StepState, b: ContentBlock): Map<String, Any?> {
        val opts = b.field_options.orEmpty()
        val hidden = st.pipeline != null && b.isHiddenByHostData(st.rawIds)
        val query = st.searchQueries[b.id]
        val refresh = isRefreshButton(b)
        return linkedMapOf(
            "rendered_option_values" to opts.map { it.storedAnswer() },
            "rendered_option_ids" to opts.map { it.id },
            "visible_option_values" to (query?.let { filterOptionsLocally(opts, it) } ?: opts).map { it.storedAnswer() },
            "option_labels" to opts.map { it.label },
            "option_subtitles" to opts.map { it.subtitle },
            "resolve_state" to RequiredFieldGate.resolveState(b, st.rawIds),
            "empty_state" to b.field_config?.get("empty_state"),
            "hidden" to hidden,
            "rendered" to !hidden,
            "field_label" to b.field_label,
            "image_url" to b.image_url,
            "field_config" to b.field_config,
            "field_config_has_repeat" to (b.field_config?.containsKey("repeat") == true),
            "field_config_has_option_set_id" to (b.field_config?.containsKey("option_set_id") == true),
            "route_polyline_used" to mapRoutePolyline(b, rawResolved = st.pipeline != null),
            // What ButtonBlock reads from the step's coordinator (StepInteraction).
            "loading" to (refresh && st.coordinator.loadingBlockId == b.id),
            "tappable" to (!refresh || st.coordinator.isTappable(b.id, st.presentation.pending)),
        )
    }

    private fun observe(): Map<String, Any?> {
        val st = cur
        val blocks = allBlocksOf(st)
        val out = LinkedHashMap<String, Any?>()
        out["pending"] = st.presentation.pending
        out["first_resolve_pending"] = st.firstResolvePending
        out["delegate_calls"] = delegateCalls
        out["on_step_viewed_calls"] = stepViewedCalls
        out["block_ids"] = blocks.map { it.id }
        out["rendered_text"] = LinkedHashMap<String, Any?>().apply {
            blocks.forEach { put(it.id, renderedText(st, it, loc = true)) }
            // The option sheet's renderer is created without `loc` (sheet content is not translated).
            st.sheetBlocks.forEach { put(it.id, renderedText(st, it, loc = false)) }
        }
        out["toggle_values"] = st.toggleValues.toMap()
        out["input_values"] = st.inputValues.toMap()
        out["selected_option_store"] = SelectedOptionStore.snapshot().mapValues { (_, v) ->
            when (v) {
                is Map<*, *> -> v["value"]
                is List<*> -> v.map { (it as? Map<*, *>)?.get("value") }
                else -> v
            }
        }
        out["redacted_field_ids"] = st.redactedFieldIds
        out["responses_step"] = st.responsesStep
        out["option_set_queries"] = blocks.associate { b ->
            b.id to if ((b.field_config?.get("option_set_id") as? String).isNullOrEmpty()) 0 else 1
        }
        out["reverted_keys_logged"] = st.pipeline?.lastRevertedKeys.orEmpty().map { (b, k) -> mapOf("block_id" to b, "key_path" to k) }
        val gate = RequiredFieldGate.evaluateDetailed(st.layered, st.inputValues, st.rawIds)
        out["gate"] = mapOf("can_advance" to gate.canAdvance, "toast_kind" to gate.toastKind)
        // `blocks` covers both live steps during a transition; everything else is the current step's.
        val blockMap = LinkedHashMap<String, Any?>()
        outgoing?.let { o -> allBlocksOf(o).forEach { b -> blockMap[b.id] = observeBlock(o, b) } }
        blocks.forEach { b -> blockMap[b.id] = observeBlock(st, b) }
        out["blocks"] = blockMap
        out["hook_data"] = access.effectiveHookData(st.step.id)
        out["interaction_calls"] = interactionCalls.toList()
        return out
    }

    private companion object {
        /** The value at a dot path in [current], or null when it equals the post-expansion value. */
        fun valueAt(current: Map<String, Any?>, path: String, post: Map<String, Any?>): Any? {
            val segs = path.split('.')
            fun walk(root: Any?, segments: List<String>): Any? {
                var cur = root
                for (s in segments) {
                    val c = cur
                    cur = when (c) {
                        is Map<*, *> -> c[s]
                        is List<*> -> s.toIntOrNull()?.let { c.getOrNull(it) }
                        else -> null
                    }
                }
                return cur
            }
            val now = walk(current, segs)
            // field_options are matched by option id — resolved options may have been dropped.
            val then = if (segs.size >= 3 && segs[0] == "field_options") {
                val cur = walk(current, segs.take(2)) as? Map<*, *>
                val match = (post["field_options"] as? List<*>)?.firstOrNull { (it as? Map<*, *>)?.get("id") == cur?.get("id") }
                walk(match, segs.drop(2))
            } else walk(post, segs)
            return if (now != then) now else null
        }
    }
}
