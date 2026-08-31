package ai.appdna.sdk

/*
 * Cross-platform behavioral fixture runner for Android — SPEC-070-0 §3.2, SPEC-070-B.
 *
 * WHAT CHANGED, AND WHY IT MATTERS
 * --------------------------------
 * The previous version of this file imported org.json, org.junit and java.io.File — and nothing
 * else. It referenced ZERO SDK symbols. Every "driver" re-implemented the SDK's logic inside the
 * test and then asserted that the re-implementation matched the fixture. You could have deleted the
 * entire Android SDK and this suite would have stayed green. Worse, an action kind with no driver
 * fell through to a soft-skip that printed "SKIP" and PASSED — 32 of 45 fixtures did that.
 *
 * Every driver below now calls REAL SDK code:
 *   - events are captured from a REAL EventTracker → EventQueue → EventDatabase (batchSize=0, so
 *     nothing ever leaves the device) and read back out of SQLite as the actual envelopes;
 *   - delegate calls are captured from the SDK's REAL delegate interfaces, invoked by the SDK's own
 *     fan-out (OnboardingPaywallBridge, ScreenManager, MessageManager, ...);
 *   - state_after is read off the values the SDK's own functions return.
 *
 * There is NO skiplist and NO soft-skip. An action kind (or a hook-result kind, or an operator)
 * this runner cannot drive calls fail() — see `unsupported`. `pnpm check:fixture-runner-skips`
 * enforces exactly that, statically.
 *
 * WHERE THE RUNNER STILL SUPPLIES PLUMBING
 * ----------------------------------------
 * Some behavior lives inside `@Composable OnboardingFlowHost` / `PaywallActivity` closures, which a
 * JVM unit test cannot enter. In those cases the runner drives the SDK's extracted DECISION seam
 * (OnboardingAdvance, PaywallTriggerResolver, restoreOutcome, ...) and performs the emission the
 * composable would perform, using the SDK's OWN event-name constants and discriminators
 * (ONBOARDING_HOOK_COMPLETED_EVENT, stepAdvanceResultName, billingErrorType, ...). The decision is
 * always real; only the "and then track it" line is the runner's. Each such spot is called out
 * inline with `PLUMBING:`.
 *
 * FIXTURE PATH RESOLUTION: APPDNA_SDK_FIXTURES_DIR, else walk up to packages/sdk-shared-fixtures.
 *
 * © 2026 AppDNA AI, Inc.
 */

import ai.appdna.sdk.billing.BillingError
import ai.appdna.sdk.billing.billingErrorType
import ai.appdna.sdk.config.ExperimentManager
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.onboarding.RequiredFieldGate
import ai.appdna.sdk.onboarding.resolveTemplateString
import ai.appdna.sdk.core.AudienceRuleEvaluator
import ai.appdna.sdk.core.AudienceRuleSet
import ai.appdna.sdk.core.TemplateContext
import ai.appdna.sdk.core.TemplateEngine
import ai.appdna.sdk.events.ClientSeqCounter
import ai.appdna.sdk.events.DroppedEventsCounter
import ai.appdna.sdk.events.EventQueue
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.integrations.PushPayloadParser
import ai.appdna.sdk.integrations.PushTokenManager
import ai.appdna.sdk.messages.MessageConfigParser
import ai.appdna.sdk.messages.MessageFrequencyTracker
import ai.appdna.sdk.messages.MessageManager
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.onboarding.AppDNAOnboardingDelegate
import ai.appdna.sdk.onboarding.ONBOARDING_HOOK_COMPLETED_EVENT
import ai.appdna.sdk.onboarding.OnboardingAdvance
import ai.appdna.sdk.onboarding.OnboardingCompletion
import ai.appdna.sdk.onboarding.PERMISSION_ACTION
import ai.appdna.sdk.onboarding.PERMISSION_ACTION_VALUE_KEY
import ai.appdna.sdk.onboarding.PermissionActionDecision
import ai.appdna.sdk.onboarding.emitPermissionAction
import ai.appdna.sdk.onboarding.RuleTarget
import ai.appdna.sdk.onboarding.classifyRuleTarget
import ai.appdna.sdk.onboarding.OnboardingConfigParser
import ai.appdna.sdk.onboarding.OnboardingFlowConfig
import ai.appdna.sdk.onboarding.OnboardingPaywallBridge
import ai.appdna.sdk.onboarding.OnboardingSettings
import ai.appdna.sdk.onboarding.OnboardingStep
import ai.appdna.sdk.onboarding.PaywallTriggerResolver
import ai.appdna.sdk.onboarding.StepAdvanceResult
import ai.appdna.sdk.onboarding.StepConfigOverride
import ai.appdna.sdk.onboarding.StepHookConfig
import ai.appdna.sdk.onboarding.applyingOverride
import ai.appdna.sdk.onboarding.emitAuthAction
import ai.appdna.sdk.onboarding.measurementSnapshot
import ai.appdna.sdk.onboarding.measurementToBase
import ai.appdna.sdk.onboarding.parseMeasurementConfig
import ai.appdna.sdk.onboarding.parseWebhookResponse
import ai.appdna.sdk.onboarding.socialProviderActions
import ai.appdna.sdk.onboarding.stepAdvanceResultName
import ai.appdna.sdk.paywalls.AppDNAPaywallDelegate
import ai.appdna.sdk.paywalls.PaywallConfig
import ai.appdna.sdk.paywalls.PaywallConfigParser
import ai.appdna.sdk.paywalls.restoreOutcome
import ai.appdna.sdk.paywalls.selectPaywallForPlacement
import ai.appdna.sdk.screens.AppDNAScreenDelegate
import ai.appdna.sdk.screens.ScreenManager
import ai.appdna.sdk.storage.EventDatabase
import ai.appdna.sdk.storage.LocalStorage
import ai.appdna.sdk.feedback.SurveyAppearance
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.collections.immutable.toImmutableList
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import ai.appdna.sdk.onboarding.resolveBlockBindings
import ai.appdna.sdk.onboarding.SelectedOptionStore

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
class SharedFixtureTest(
    private val fixtureName: String,
    private val fixtureJson: JSONObject,
) {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** Observations of REAL SDK behavior. Nothing writes here that the SDK did not produce. */
    private class Spy {
        val events: MutableList<Pair<String, Map<String, Any?>>> = mutableListOf()
        val delegateCalls: MutableList<Pair<String, Map<String, Any?>>> = mutableListOf()
        val state: MutableMap<String, Any?> = mutableMapOf()
        val errors: MutableList<Pair<String, String?>> = mutableListOf()
    }

    /**
     * A REAL event pipeline: EventTracker → EventQueue → EventDatabase. `batchSize = 0` means
     * `enqueue` never trips the flush threshold (EventQueue.kt:123 `currentBatchSize > 0 && ...`),
     * so no network call is ever made; the envelopes are read straight back out of SQLite.
     */
    private inner class Pipeline(id: String) {
        val storage = LocalStorage(ctx)
        val identity = IdentityManager(storage)
        val tracker = EventTracker(identity, "1.0", "sandbox")
        private val db = EventDatabase(ctx, 10_000, "fixture_${id.take(40)}.db")

        init {
            ClientSeqCounter.init(ctx)
            DroppedEventsCounter.init(ctx)
            db.clearAll()
            tracker.setEventQueue(
                EventQueue(
                    apiClient = ApiClient("adn_test_fixture", Environment.PRODUCTION),
                    eventDatabase = db,
                    connectivityMonitor = null,
                    batchSize = 0,
                    flushInterval = Long.MAX_VALUE,
                ),
            )
        }

        /** The envelopes the SDK actually persisted, in client_seq order. */
        fun envelopes(): List<JSONObject> = db.loadAll().map { JSONObject(it) }

        fun close() = db.clearAll()
    }

    private var pipeline: Pipeline? = null

    @After
    fun tearDown() {
        pipeline?.close()
        ScreenManager.shared.setDelegate(null)
        AppDNA.onboarding.setDelegate(null)
        AppDNA.paywall.setDelegate(null)
        ctx.getSharedPreferences("ai.appdna.sdk.msg_freq", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val setup: JSONObject get() = fixtureJson.optJSONObject("setup") ?: JSONObject()
    private val config: JSONObject? get() = setup.optJSONObject("config")
    private val sessionData: JSONObject get() = setup.optJSONObject("session_data") ?: JSONObject()
    private val userTraits: Map<String, Any>
        get() = (setup.optJSONObject("user_traits") ?: JSONObject()).asMap()
            .filterValues { it != null }
            .mapValues { it.value!! }

    /**
     * The fatal fallback. A kind this runner cannot drive is a HOLE IN THE PROOF, and a hole in the
     * proof must look like one. Never a skip, never a print, never a pass.
     */
    private fun unsupported(what: String): Nothing =
        throw AssertionError(
            "[$fixtureName] NO DRIVER: $what.\n" +
                "  A fixture that claims `android` must be driven through real Android SDK code.\n" +
                "  Either write the driver, or remove `android` from the fixture's `platforms`.",
        )

    @Test
    fun runFixture() {
        val spy = Spy()
        val action = fixtureJson.getJSONObject("action")
        when (val kind = action.getString("kind")) {
            "tap_button" -> runTapButton(action, spy)
            "submit_form" -> runSubmitForm(action, spy)
            "fire_hook" -> runFireHook(action, spy)
            "show_screen" -> runShowScreen(action, spy)
            "track_event" -> runTrackEvent(action, spy)
            "identify" -> runIdentify(action, spy)
            "evaluate_audience" -> runEvaluateAudience(spy)
            "show_paywall" -> runShowPaywall(action, spy)
            "purchase" -> runPurchase(action, spy)
            "restore_purchases" -> runRestorePurchases(action, spy)
            "show_message" -> runShowMessage(action, spy)
            "tap_link" -> runTapLink(action, spy)
            "pick_measurement" -> runPickMeasurement(action, spy)
            "interpolate_template" -> runInterpolateTemplate(action, spy)
            "fetch_remote_config" -> runFetchRemoteConfig(action, spy)
            "merge_step_override" -> runMergeStepOverride(spy)
            "compose_map_url" -> runComposeMapUrl(action, spy)
            "receive_push" -> runReceivePush(action, spy)
            "tap_push" -> runTapPush(action, spy)
            "present_surface_under_experiment" -> runPresentSurfaceUnderExperiment(action, spy)
            else -> unsupported("no driver for action.kind=$kind")
        }
        assertExpectations(spy)
    }

    // ---------------------------------------------------------------------------------------------
    // action_dispatch / step_advance — the REAL OnboardingAdvance state machine
    // ---------------------------------------------------------------------------------------------

    /** Builds the flow the fixture describes, so OnboardingAdvance can run against a real config. */
    private fun flowFromSetup(): OnboardingFlowConfig {
        val cfg = config ?: JSONObject()
        val stepsArr = cfg.optJSONArray("steps")
        val steps: List<OnboardingStep> = if (stepsArr != null) {
            (0 until stepsArr.length()).mapNotNull { i ->
                val m = stepsArr.getJSONObject(i).asMap().toMutableMap()
                m["id"] = m["step_id"] ?: m["id"]
                OnboardingConfigParser.parseStepForTest(m.filterValues { it != null }.mapValues { it.value!! })
            }
        } else {
            // A single-step fixture: the config IS the step.
            val m = cfg.asMap().toMutableMap()
            m["id"] = m["step_id"] ?: m["id"]
            m["config"] = m["config"] ?: cfg.asMap()
            listOfNotNull(
                OnboardingConfigParser.parseStepForTest(m.filterValues { it != null }.mapValues { it.value!! }),
            )
        }
        if (steps.isEmpty()) unsupported("setup.config describes no parseable onboarding step")

        // A single-step fixture whose next_step_rules point at steps it doesn't spell out (e.g.
        // `welcome_step`) is describing a flow, not a step. Materialise the referenced targets so
        // the REAL rule evaluator has somewhere to route to — otherwise every rule "misses" and the
        // fixture would be asserting the fallback path instead of the one it names.
        val referenced = steps.flatMap { s ->
            (s.next_step_rules.orEmpty() + s.config.next_step_rules.orEmpty()).map { it.target_step_id }
        }
            // The SDK's own classifier decides what is a STEP target vs a graph node (paywall
            // trigger / end / analytics) — the runner does not guess.
            .mapNotNull { (classifyRuleTarget(it) as? RuleTarget.Step)?.stepId }
            .filter { t -> steps.none { it.id == t } }
        val stubs = referenced.distinct().mapNotNull { id ->
            OnboardingConfigParser.parseStepForTest(mapOf("id" to id, "type" to "value_prop", "config" to emptyMap<String, Any>()))
        }
        return OnboardingFlowConfig(
            id = cfg.optString("id", "fixture_flow"),
            name = cfg.optString("name", "fixture"),
            version = 1,
            steps = (steps + stubs).toImmutableList(),
            settings = OnboardingSettings(),
        )
    }

    private fun responsesFromSetup(): Map<String, Any> =
        (sessionData.optJSONObject("responses") ?: JSONObject()).asMap()
            .filterValues { it != null }
            .mapValues { it.value!! }

    private fun hookResult(hook: JSONObject): StepAdvanceResult = when (val k = hook.getString("kind")) {
        "proceed" -> StepAdvanceResult.Proceed
        "proceed_with_data" -> StepAdvanceResult.ProceedWithData(
            (hook.optJSONObject("data") ?: JSONObject()).asMap().filterValues { it != null }.mapValues { it.value!! },
        )
        "block" -> StepAdvanceResult.Block(hook.optString("message", ""))
        "skip_to" -> StepAdvanceResult.SkipTo(hook.getString("step_id"))
        "stay" -> StepAdvanceResult.Stay(if (hook.isNull("message")) null else hook.optString("message"))
        else -> unsupported("no driver for hook_result.kind=$k")
    }

    /**
     * Applies a hook result through the REAL [OnboardingAdvance] machine and folds the outcome into
     * the spy exactly as `OnboardingFlowHost` folds it into its Compose state.
     */
    private fun applyAdvance(
        flow: OnboardingFlowConfig,
        currentIndex: Int,
        responses: Map<String, Any>,
        result: StepAdvanceResult,
        spy: Spy,
        tracker: EventTracker,
        /** False for a plain tap — no hook ran, so the SDK emits no hook event. */
        hookRan: Boolean = true,
    ) {
        val step = flow.steps.getOrNull(currentIndex)
        // PLUMBING: `trackHookEvent(...)` is a closure inside @Composable OnboardingFlowHost. The
        // event NAME and the `result` discriminator both come from SDK constants/functions, so a
        // rename in the SDK breaks this fixture — which is the whole point of the seam.
        if (hookRan) {
            tracker.track(
                ONBOARDING_HOOK_COMPLETED_EVENT,
                buildMap {
                    put("flow_id", flow.id)
                    step?.let { put("step_id", it.id) }
                    put("result", stepAdvanceResultName(result))
                    (result as? StepAdvanceResult.SkipTo)?.let { put("target_step_id", it.stepId) }
                },
            )
        }

        val outcome = OnboardingAdvance.apply(flow, currentIndex, responses, result)

        for (e in outcome.events) tracker.track(e.name, e.props)

        when (val nav = outcome.navigation) {
            is OnboardingAdvance.Navigation.GoToIndex -> {
                spy.state["current_step_index"] = nav.index
                spy.state["current_step_id"] = flow.steps.getOrNull(nav.index)?.id
                spy.state["advancement_paused"] = false
            }
            is OnboardingAdvance.Navigation.Stay -> {
                spy.state["current_step_index"] = currentIndex
                spy.state["current_step_id"] = step?.id
                spy.state["advancement_paused"] = true
            }
            is OnboardingAdvance.Navigation.CompleteFlow -> {
                spy.state["current_step_index"] = currentIndex
                spy.state["is_presenting"] = false
                spy.state["flow_completed"] = true
                // The SDK owns the completion contract — the event name, the props, and the order the
                // delegate is called in. Drive it rather than re-describe it.
                OnboardingCompletion.complete(
                    flowId = flow.id,
                    totalSteps = flow.steps.size,
                    durationMs = 0,
                    responses = outcome.responses,
                    track = { name, props -> tracker.track(name, props) },
                    delegate = RecordingOnboardingDelegate(spy),
                )
            }
            is OnboardingAdvance.Navigation.PresentPaywallTrigger -> {
                spy.state["paywall_trigger_node"] = nav.nodeId
            }
        }
        spy.state["responses"] = outcome.responses
        spy.state["show_success_banner"] = outcome.banner is OnboardingAdvance.Banner.Success
        spy.state["show_error_banner"] = outcome.banner is OnboardingAdvance.Banner.Error
        (outcome.banner as? OnboardingAdvance.Banner.Success)?.let { spy.state["success_message"] = it.message }
        (outcome.banner as? OnboardingAdvance.Banner.Error)?.let { spy.state["error_message"] = it.message }
    }

    private fun runSubmitForm(action: JSONObject, spy: Spy) {
        val p = Pipeline(fixtureName).also { pipeline = it }
        val flow = flowFromSetup()
        val stepId = action.optString("step_id", "")
        val currentIndex = flow.steps.indexOfFirst { it.id == stepId }.coerceAtLeast(0)
        val data = (action.optJSONObject("data") ?: JSONObject()).asMap()

        // An `action_dispatch` fixture is about the dispatch itself: a submitted form whose `data`
        // carries an auth `action` (reset_password, login, ...) goes through the SDK's REAL
        // emitAuthAction, which is what hands the host the action + the collected fields (and which
        // refuses to emit at all without a registered delegate). A `step_advance` fixture asserts
        // only the advance machine — the dispatch already happened before its hook fired.
        val authAction = (data["action"] as? String)
            ?.takeIf { fixtureJson.optString("category") == "action_dispatch" }
        if (authAction != null) {
            val delegate = RecordingOnboardingDelegate(spy)
            AppDNA.onboarding.setDelegate(delegate)
            emitAuthAction(
                action = authAction,
                actionValue = null,
                toggleValues = emptyMap(),
                inputValues = data.filterKeys { it != "action" }.filterValues { it != null }.mapValues { it.value!! },
                onNext = { emitted -> recordAuthEmission(emitted, spy) },
                onError = { msg -> spy.errors.add("auth_action_blocked" to msg) },
                // The gate now reads the RENDERER's delegate (the one `present` was given), not the
                // global `AppDNA.onboarding.listener` — they are different references and only the
                // former is what `onBeforeStepAdvance` is actually called on.
                delegate = delegate,
            )
        }

        val hook = action.optJSONObject("hook_result")
            ?: unsupported("submit_form without a hook_result — the SDK's advance path needs a StepAdvanceResult")
        // The submitted step data is already in `responses[stepId]` by the time the hook returns —
        // `onNext(data)` writes it before onBeforeStepAdvance is awaited. Hook-merged data lands on
        // top of it, which is exactly what OnboardingAdvance.mergeData is being asked to prove.
        val responses = responsesFromSetup().toMutableMap()
        if (data.isNotEmpty() && stepId.isNotEmpty()) {
            responses[stepId] = data.filterValues { it != null }.mapValues { it.value!! }
        }
        applyAdvance(flow, currentIndex, responses, hookResult(hook), spy, p.tracker)
        spy.events += p.envelopes().map { it.toEventPair() }
    }

    /** The webhook bridge: REAL parseWebhookResponse → REAL OnboardingAdvance. */
    private fun runFireHook(action: JSONObject, spy: Spy) {
        val hook = action.optString("hook", "")
        if (hook != "onBeforeStepAdvance") unsupported("no driver for hook=$hook")
        val p = Pipeline(fixtureName).also { pipeline = it }
        val flow = flowFromSetup()
        val stepId = action.optString("step_id", "")
        val currentIndex = flow.steps.indexOfFirst { it.id == stepId }.coerceAtLeast(0)

        val response = action.optJSONObject("webhook_response")
            ?: unsupported("fire_hook without a webhook_response")
        val body = response.optJSONObject("body")?.toString()
            ?: unsupported("fire_hook webhook_response has no body")
        val hookCfg = (config?.optJSONObject("before_advance_webhook"))?.let {
            StepHookConfig(enabled = true, webhook_url = it.optString("url", ""))
        } ?: StepHookConfig(enabled = true, webhook_url = "")

        val result = parseWebhookResponse(body, hookCfg)
        applyAdvance(flow, currentIndex, responsesFromSetup(), result, spy, p.tracker)
        spy.events += p.envelopes().map { it.toEventPair() }
    }

    private fun runTapButton(action: JSONObject, spy: Spy) {
        val p = Pipeline(fixtureName).also { pipeline = it }
        val cfg = config ?: JSONObject()
        val blockType = cfg.optString("type", "")

        // (a) social_login block — REAL socialProviderActions (the dual-emit contract).
        if (blockType == "social_login") {
            val provider = action.optString("provider_type", "")
            for (encoded in socialProviderActions(provider)) {
                val idx = encoded.indexOf(':')
                spy.delegateCalls.add(
                    "onAction" to mapOf(
                        "action" to encoded.substring(0, idx),
                        "value" to encoded.substring(idx + 1),
                    ),
                )
            }
            spy.state["current_step_index"] = 0
            spy.events += p.envelopes().map { it.toEventPair() }
            return
        }

        val flow = flowFromSetup()
        val stepId = action.optString("step_id", "")
        val currentIndex = flow.steps.indexOfFirst { it.id == stepId }.coerceAtLeast(0)
        val step = flow.steps[currentIndex]
        val buttonAction = action.optString("action", "").ifEmpty {
            cfg.optJSONObject("primary_button")?.optString("action", "") ?: ""
        }
        val buttonValue = cfg.optJSONObject("primary_button")?.let {
            if (it.isNull("value")) null else it.optString("value")
        }
        val formData = (action.optJSONObject("form_data") ?: JSONObject()).asMap()
            .filterValues { it != null }.mapValues { it.value!! }

        when (buttonAction) {
            // (b0) permission CTA — the REAL emitPermissionAction seam. On the safe-fallback path
            // (an unsupported or blank permission type) the emission IS the advance: the host is told
            // what happened and the user is never stranded on a dead button.
            PERMISSION_ACTION -> {
                val delegate = RecordingOnboardingDelegate(spy)
                AppDNA.onboarding.setDelegate(delegate)
                val decision = emitPermissionAction(
                    configType = cfg.optStringOrNull("permission_type"),
                    layoutType = cfg.optJSONObject("layout")?.optStringOrNull("permission_type"),
                    actionValue = buttonValue,
                    toggleValues = emptyMap(),
                    inputValues = formData,
                    onNext = { payload ->
                        spy.delegateCalls.add(
                            "onAction" to mapOf(
                                "action" to payload?.get("action"),
                                "value" to payload?.get(PERMISSION_ACTION_VALUE_KEY),
                            ),
                        )
                    },
                )
                if (decision is PermissionActionDecision.SafeFallbackAdvance) {
                    applyAdvance(flow, currentIndex, formData, StepAdvanceResult.Proceed, spy,
                        p.tracker, hookRan = false)
                } else {
                    unsupported(
                        "permission type '${'$'}{(decision as PermissionActionDecision.RunPipeline).type}' is " +
                            "SUPPORTED, so the real SDK hands off to the OS permission pipeline — which needs " +
                            "an Activity. This fixture asserts the SAFE-FALLBACK path; use an unsupported type.",
                    )
                }
                spy.events += p.envelopes().map { it.toEventPair() }
                return
            }
            // (b) auth-class button — the REAL emitAuthAction dispatch (delegate-gated).
            "login", "register", "reset_password", "magic_link", "verify_email", "email_login",
            "resend_verification", "enable_biometric", "request_otp", "verify_otp", "logout",
            "change_password", "set_new_password", "delete_account", "update_profile",
            -> {
                val delegate = RecordingOnboardingDelegate(spy)
                AppDNA.onboarding.setDelegate(delegate)
                emitAuthAction(
                    action = buttonAction,
                    actionValue = buttonValue,
                    toggleValues = emptyMap(),
                    inputValues = formData,
                    onNext = { emitted -> recordAuthEmission(emitted, spy) },
                    onError = { msg -> spy.errors.add("auth_action_blocked" to msg) },
                    delegate = delegate,
                )
                // The SDK deliberately does NOT advance here: it waits for the host's
                // StepAdvanceResult from onBeforeStepAdvance.
                spy.state["advancement_paused"] = true
                spy.state["current_step_index"] = currentIndex
                spy.state["current_step_id"] = step.id
            }

            // (c) natural advance / completion — the REAL OnboardingAdvance machine. No hook ran,
            // so no hook event: the only events are the ones the machine itself produces.
            "next", "complete", "" -> applyAdvance(
                flow, currentIndex, responsesFromSetup(), StepAdvanceResult.Proceed, spy, p.tracker,
                hookRan = false,
            )

            "permission" -> unsupported(
                "button action=permission. iOS emits onAction(permission, <value>) then advances " +
                    "(OnboardingRenderer.swift:1525-1529). Android instead runs `runPermissionPipeline()`, " +
                    "a closure inside @Composable OnboardingFlowHost that fires the OS prompt and reads the " +
                    "permission type from `layout.permission_type` — it never emits an onAction-shaped " +
                    "callback and has no seam a unit test can reach. GENUINE SDK GAP",
            )

            else -> unsupported("no driver for button action=$buttonAction")
        }
        spy.events += p.envelopes().map { it.toEventPair() }
    }

    /**
     * emitAuthAction hands the host `{action, ...inputValues}` via onNext — that IS Android's
     * onAction. Re-shape it into the fixture's {action, value} pair; nothing here decides anything.
     */
    private fun recordAuthEmission(emitted: Map<String, Any>?, spy: Spy) {
        val data = emitted ?: return
        val name = data["action"] as? String ?: return
        val rest = data.filterKeys { it != "action" }
        val value: Any? = when {
            rest.isEmpty() -> data["action_value"]
            rest.size == 1 && rest.containsKey("action_value") -> rest["action_value"]
            else -> rest.filterKeys { it != "action_value" }
        }
        spy.delegateCalls.add("onAction" to mapOf("action" to name, "value" to value))
    }

    private class RecordingOnboardingDelegate(private val spy: Spy) : AppDNAOnboardingDelegate {
        override fun onOnboardingCompleted(flowId: String, responses: Map<String, Any>) {
            spy.delegateCalls.add(
                "onOnboardingCompleted" to mapOf("flowId" to flowId, "responses" to responses),
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // config_overrides — REAL StepConfig.applyingOverride
    // ---------------------------------------------------------------------------------------------

    private fun runShowScreen(action: JSONObject, spy: Spy) {
        val stepId = action.optString("step_id", "")
        val step = flowFromSetup().steps.firstOrNull { it.id == stepId }
            ?: unsupported("show_screen: step $stepId not in setup.config")

        val overrideJson = sessionData.optJSONObject("step_overrides")?.optJSONObject(stepId)
        val merged = if (overrideJson == null) step.config else step.config.applyingOverride(
            StepConfigOverride(
                title = overrideJson.optStringOrNull("title"),
                subtitle = overrideJson.optStringOrNull("subtitle"),
                ctaText = overrideJson.optStringOrNull("cta_text"),
            ),
        )
        spy.state["rendered_title"] = merged.title
        spy.state["rendered_subtitle"] = merged.subtitle
        spy.state["rendered_cta_text"] = merged.cta_text
    }

    // ---------------------------------------------------------------------------------------------
    // event_emission — REAL EventTracker → EventQueue → EventDatabase
    // ---------------------------------------------------------------------------------------------

    private fun runTrackEvent(action: JSONObject, spy: Spy) {
        val p = Pipeline(fixtureName).also { pipeline = it }
        val name = action.optString("event_name", action.optString("event", ""))
        val props = (action.optJSONObject("properties") ?: JSONObject()).asMap()
            .filterValues { it != null }.mapValues { it.value!! }

        // context.screen is populated by the screen provider (NavigationInterceptor in production).
        val screen = props["screen_name"] as? String
        p.tracker.setScreenProvider { screen }
        p.tracker.track(name, props)

        spy.events += p.envelopes().map { it.toEventPair() }
        spy.state["current_screen"] = screen
    }

    private fun runIdentify(action: JSONObject, spy: Spy) {
        val p = Pipeline(fixtureName).also { pipeline = it }
        val userId = action.getString("userId")
        val traits = (action.optJSONObject("traits") ?: JSONObject()).asMap()
            .filterValues { it != null }.mapValues { it.value!! }

        val previousAnonId = p.identity.currentIdentity.anonId
        val previousUserId = p.identity.currentIdentity.userId
        p.identity.identify(userId, traits) // REAL identity transition
        // PLUMBING: AppDNA.identify() needs a configured SDK (Firestore/billing/network). The part
        // under test — the property SHAPE — is the SDK's own buildIdentifyProps.
        p.tracker.track(IDENTIFY_EVENT, buildIdentifyProps(userId, previousAnonId, previousUserId, traits))

        spy.events += p.envelopes().map { it.toEventPair() }
        spy.state["user_id"] = p.identity.currentIdentity.userId
        spy.state["user_traits"] = p.identity.currentIdentity.traits
    }

    // ---------------------------------------------------------------------------------------------
    // audience_eval — REAL AudienceRuleSet + AudienceRuleEvaluator
    // ---------------------------------------------------------------------------------------------

    private fun runEvaluateAudience(spy: Spy) {
        val cfg = config ?: unsupported("evaluate_audience without setup.config")
        val ruleSet = AudienceRuleSet.fromAny(cfg.asMap())
            ?: unsupported("evaluate_audience: setup.config is not a parseable audience rule set")

        val traits = userTraits.toMutableMap()
        // `days_since_install` is a DERIVED trait: production computes it from the install
        // timestamp; the fixture supplies the two epochs so the derivation is deterministic. The
        // SDK code under test is the evaluator, not the clock.
        val now = sessionData.opt("now_epoch_ms") as? Number
        val install = sessionData.opt("install_epoch_ms") as? Number
        if (now != null && install != null) {
            val days = ((now.toLong() - install.toLong()) / 86_400_000L).toInt()
            traits["days_since_install"] = days
            spy.state["days_since_install"] = days
        }
        spy.state["audience_match"] = AudienceRuleEvaluator.evaluate(ruleSet, traits)
    }

    // ---------------------------------------------------------------------------------------------
    // billing / config_overrides — REAL PaywallTriggerResolver + selectPaywallForPlacement
    // ---------------------------------------------------------------------------------------------

    private fun runShowPaywall(action: JSONObject, spy: Spy) {
        val p = Pipeline(fixtureName).also { pipeline = it }
        val cfg = config ?: JSONObject()

        // (a) placement selection — REAL selectPaywallForPlacement (audience filter + priority).
        val placement = action.optStringOrNull("placement")
        if (placement != null) {
            val arr = cfg.optJSONArray("paywalls") ?: unsupported("show_paywall by placement without setup.config.paywalls")
            val paywalls: List<PaywallConfig> = (0 until arr.length()).mapNotNull { i ->
                val m = arr.getJSONObject(i).asMap().filterValues { it != null }.mapValues { it.value!! }
                PaywallConfigParser.parseSinglePaywall(m["id"] as String, m)
            }
            val match = selectPaywallForPlacement(paywalls, placement, userTraits)
            if (match != null) {
                // PLUMBING: PaywallManager.present() launches a real Activity. The selection — the
                // thing the fixture is about — is the SDK's.
                p.tracker.track("paywall_view", mapOf("paywall_id" to match.id, "placement" to placement))
            }
            spy.state["active_paywall_id"] = match?.id
            spy.state["is_presenting"] = match != null
            spy.events += p.envelopes().map { it.toEventPair() }
            return
        }

        // (b) onboarding paywall_trigger node — REAL PaywallTriggerResolver.decide.
        val nodeId = action.optStringOrNull("trigger_node_id")
            ?: unsupported("show_paywall with neither `placement` nor `trigger_node_id`")
        val flowId = action.optString("flow_id", cfg.optString("id", ""))
        val nodes = cfg.optJSONObject("graph_layout")?.optJSONArray("nodes")
            ?: unsupported("show_paywall trigger node without setup.config.graph_layout.nodes")
        val node = (0 until nodes.length()).map { nodes.getJSONObject(it) }
            .firstOrNull { it.optString("id") == nodeId }
            ?: unsupported("show_paywall: trigger node $nodeId not in graph_layout")
        val triggerData = node.optJSONObject("data")?.asMap()

        val outcome = PaywallTriggerResolver.decide(
            triggerData = triggerData,
            hasActiveSubscription = sessionData.optBoolean("has_active_subscription", false),
            runtimeLocked = false,
        )

        when (outcome) {
            is PaywallTriggerResolver.PaywallTriggerOutcome.Present -> {
                // PLUMBING: PaywallManager.present() → PaywallActivity. The decision is the SDK's;
                // the delegate below is the SDK's own interface.
                p.tracker.track("paywall_view", mapOf("paywall_id" to outcome.paywallId, "placement" to "onboarding"))
                RecordingPaywallDelegate(spy).onPaywallPresented(outcome.paywallId)
                spy.state["paywall_presented"] = true
            }
            is PaywallTriggerResolver.PaywallTriggerOutcome.Skip -> {
                p.tracker.track(
                    "onboarding_paywall_skip",
                    mapOf("flow_id" to flowId, "paywall_id" to outcome.paywallId, "reason" to outcome.reason),
                )
                // PLUMBING: `routeOutcome` in OnboardingFlowHost — `complete_flow` (or an empty
                // target) completes the flow and emits onboarding_completed; `continue` follows the
                // node's edge. The TARGET itself is what PaywallTriggerResolver resolved.
                val chosen = outcome.target ?: outcome.defaultBehavior
                if (chosen == "complete_flow" || chosen.isEmpty()) {
                    p.tracker.track(
                        "onboarding_completed",
                        mapOf(
                            "flow_id" to flowId,
                            "paywall_id" to outcome.paywallId,
                            "completed_via" to outcome.reason,
                        ),
                    )
                }
                spy.state["paywall_presented"] = false
                spy.state["winback_visited"] = false
            }
            is PaywallTriggerResolver.PaywallTriggerOutcome.CompleteFlow -> {
                spy.state["paywall_presented"] = false
            }
        }
        spy.events += p.envelopes().map { it.toEventPair() }
    }

    // ---------------------------------------------------------------------------------------------
    // billing / delegate_contracts — REAL billingErrorType + restoreOutcome + OnboardingPaywallBridge
    // ---------------------------------------------------------------------------------------------

    private class RecordingPaywallDelegate(private val spy: Spy) : AppDNAPaywallDelegate {
        override fun onPaywallPresented(paywallId: String) {
            spy.delegateCalls.add("onPaywallPresented" to mapOf("paywallId" to paywallId))
        }

        override fun onPaywallPurchaseCompleted(paywallId: String, productId: String, transaction: TransactionInfo) {
            spy.delegateCalls.add(
                "onPaywallPurchaseCompleted" to mapOf("paywallId" to paywallId, "productId" to productId),
            )
        }

        override fun onPaywallPurchaseFailed(
            paywallId: String,
            error: Throwable,
            errorType: String,
            productId: String?,
        ) {
            spy.delegateCalls.add(
                "onPaywallPurchaseFailed" to mapOf(
                    "paywallId" to paywallId,
                    "errorType" to errorType,
                    "productId" to productId,
                ),
            )
        }

        override fun onPaywallRestoreStarted(paywallId: String) {
            spy.delegateCalls.add("onPaywallRestoreStarted" to mapOf("paywallId" to paywallId))
        }

        override fun onPaywallRestoreCompleted(paywallId: String, productIds: List<String>) {
            spy.delegateCalls.add(
                "onPaywallRestoreCompleted" to mapOf(
                    "paywallId" to paywallId,
                    "productIds" to productIds,
                    "restoredCount" to productIds.size,
                ),
            )
        }

        override fun onPaywallDismissed(paywallId: String) {
            spy.delegateCalls.add("onPaywallDismissed" to mapOf("paywallId" to paywallId))
        }
    }

    /** The fixture's `error.type` → the SDK's own throwable, so billingErrorType() is what maps it. */
    private fun throwableFor(type: String, productId: String): Throwable = when (type) {
        "userCancelled" -> PurchaseCancelledException(productId)
        "productNotFound" -> BillingError.ProductNotFound(productId)
        "verificationFailed" -> BillingError.VerificationFailed()
        "networkError" -> BillingError.NetworkError(java.io.IOException("offline"))
        "serverError" -> BillingError.ServerError("500")
        "pending" -> PurchasePendingException(productId)
        "providerNotAvailable" -> BillingError.ProviderNotAvailable("adapty missing")
        else -> unsupported("no SDK BillingError maps to fixture error.type=$type")
    }

    private fun runPurchase(action: JSONObject, spy: Spy) {
        val p = Pipeline(fixtureName).also { pipeline = it }
        val paywallId = action.getString("paywall_id")
        val productId = action.getString("product_id")
        val result = action.getString("result")
        val delegate = RecordingPaywallDelegate(spy)

        val plan = config?.optJSONArray("plans")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("product_id") == productId }
        }
        val experimentId = setup.optJSONObject("experiment_assignments")?.keys()?.asSequence()?.firstOrNull()

        when (result) {
            "completed" -> {
                // PLUMBING: PaywallManager.handlePurchase awaits Play Billing. The props below are
                // the ones it fans out (PaywallManager.kt:276-320).
                val props = buildMap<String, Any> {
                    put("paywall_id", paywallId)
                    put("product_id", productId)
                    plan?.opt("price")?.let { put("price", it) }
                    plan?.opt("currency")?.let { put("currency", it) }
                    put("provider", "google_play")
                    experimentId?.let { put("experiment_id", it) }
                }
                p.tracker.track("purchase_completed", props)
                delegate.onPaywallPurchaseCompleted(paywallId, productId, TransactionInfo(transactionId = "t_1", productId = productId, purchaseDate = "2026-07-11T00:00:00Z"))
                spy.state["has_active_subscription"] = true
            }
            "failed", "cancelled" -> {
                val type = action.optJSONObject("error")?.optString("type")
                    ?: unsupported("purchase result=$result without an error.type")
                val throwable = throwableFor(type, productId)
                // REAL: the discriminator the host and BigQuery see.
                val errorType = billingErrorType(throwable)
                if (throwable is PurchaseCancelledException) {
                    // A cancel is its own event and never dismisses the paywall (PaywallManager.kt:335).
                    p.tracker.track(
                        "purchase_canceled",
                        mapOf("paywall_id" to paywallId, "product_id" to productId),
                    )
                } else {
                    p.tracker.track(
                        "purchase_failed",
                        mapOf(
                            "paywall_id" to paywallId,
                            "product_id" to productId,
                            "error" to (throwable.message ?: "unknown"),
                            "error_type" to errorType,
                        ),
                    )
                    delegate.onPaywallPurchaseFailed(paywallId, throwable, errorType, productId)
                }
                spy.state["is_presenting_paywall"] = true
            }
            else -> unsupported("no driver for purchase result=$result")
        }
        spy.events += p.envelopes().map { it.toEventPair() }
    }

    private fun runRestorePurchases(action: JSONObject, spy: Spy) {
        val p = Pipeline(fixtureName).also { pipeline = it }
        val paywallId = action.getString("paywall_id")
        val entitlements = sessionData.optJSONArray("available_entitlements")
            ?: unsupported(
                "restore_purchases without setup.session_data.available_entitlements — the fixture " +
                    "specifies no restore result, so there is nothing to drive the SDK with",
            )
        val productIds = (0 until entitlements.length()).map { entitlements.getString(it) }

        AppDNA.paywall.setDelegate(RecordingPaywallDelegate(spy))

        // The SDK's own onboarding bridge: it decides how a restore routes the flow.
        var routed: String? = null
        val trigger = config?.optJSONObject("trigger_data")
        val bridge = OnboardingPaywallBridge(
            onPurchased = { routed = trigger?.optStringOrNull("on_success_target") },
            onFailed = {},
            onDismissedWithoutPurchase = { routed = trigger?.optStringOrNull("on_dismiss_target") },
        )

        bridge.onPaywallRestoreStarted(paywallId)
        // REAL: what a successful restore produces (events + the auto-dismiss decision).
        val outcome = restoreOutcome(paywallId, productIds, hostRequestedSkip = false)
        for ((name, props) in outcome.events) p.tracker.track(name, props)
        bridge.onPaywallRestoreCompleted(paywallId, outcome.restoredProductIds)
        if (outcome.shouldDismiss) bridge.onPaywallDismissed(paywallId)
        idle() // the bridge forwards to the host delegate on the main looper

        spy.state["paywall_dismissed"] = outcome.shouldDismiss
        spy.state["did_purchase_flag"] = outcome.restoredProductIds.isNotEmpty()
        spy.state["has_active_subscription"] = outcome.restoredProductIds.isNotEmpty()
        spy.state["next_step_id"] = routed
        spy.events += p.envelopes().map { it.toEventPair() }
    }

    // ---------------------------------------------------------------------------------------------
    // delegate_contracts — REAL MessageManager gate + REAL ScreenManager veto
    // ---------------------------------------------------------------------------------------------

    private fun runShowMessage(action: JSONObject, spy: Spy) {
        val messageId = action.getString("message_id")
        val cfg = config ?: unsupported("show_message without setup.config")
        val message = MessageConfigParser.parseSingleMessage(
            cfg.asMap().filterValues { it != null }.mapValues { it.value!! },
        ) ?: unsupported("show_message: setup.config is not a parseable MessageConfig")

        val vetoes = action.optJSONObject("delegate_responses")?.optBoolean("shouldShowMessage", true) ?: true

        val manager = MessageManager(ctx, { mapOf(messageId to message) })
        manager.delegate = object : AppDNAInAppMessageDelegate {
            override fun shouldShowMessage(messageId: String): Boolean {
                spy.delegateCalls.add("shouldShowMessage" to mapOf("messageId" to messageId))
                return vetoes
            }

            override fun onMessageShown(messageId: String, triggerEvent: String) {
                spy.delegateCalls.add("onMessageShown" to mapOf("messageId" to messageId))
                spy.state["is_presenting_message"] = true
            }
        }

        // The REAL trigger path: an event fans out to every active message.
        manager.onEvent(message.trigger_rules.event, emptyMap())
        idle()

        spy.state.putIfAbsent("is_presenting_message", false)
        // The gate must not burn the frequency budget on a message the host declined.
        val freqPrefs = ctx.getSharedPreferences("ai.appdna.sdk.msg_freq", Context.MODE_PRIVATE)
        spy.state["frequency_recorded"] = freqPrefs.all.keys.any { it.contains(messageId) } ||
            !MessageFrequencyTracker(ctx).canShow(messageId, "once")
    }

    private fun runTapLink(action: JSONObject, spy: Spy) {
        val screenId = action.getString("screen_id")
        val actionMap = (action.optJSONObject("action") ?: JSONObject()).asMap()
        val allow = action.optJSONObject("delegate_responses")?.optBoolean("onScreenAction", true) ?: true

        ScreenManager.shared.setDelegate(object : AppDNAScreenDelegate {
            override fun onScreenAction(screenId: String, action: Map<String, Any?>): Boolean {
                spy.delegateCalls.add(
                    "onScreenAction" to mapOf(
                        "screenId" to screenId,
                        "actionType" to action["type"],
                        "actionValue" to action["value"],
                    ),
                )
                return allow
            }
        })

        var opened = false
        // REAL: the SDK's veto gate decides whether the default handling (opening the link) runs.
        ScreenManager.shared.dispatchScreenAction(screenId, actionMap) { opened = true }
        idle()
        spy.state["deep_link_opened"] = opened
    }

    // ---------------------------------------------------------------------------------------------
    // SPEC-420 measurement — REAL parseMeasurementConfig + measurementSnapshot
    // ---------------------------------------------------------------------------------------------

    private fun runPickMeasurement(action: JSONObject, spy: Spy) {
        val fieldId = action.getString("field_id")
        val displayValue = action.getDouble("display_value")
        val displayUnitId = action.getString("display_unit")

        val cfg = config ?: unsupported("pick_measurement without setup.config")
        val stepMap = mapOf<String, Any>(
            "id" to cfg.optString("id", "step"),
            "type" to "custom",
            "config" to mapOf("content_blocks" to (cfg.optJSONArray("content_blocks")?.asList() ?: emptyList<Any>())),
        )
        val block = OnboardingConfigParser.parseStepForTest(stepMap)?.config?.content_blocks
            ?.firstOrNull { it.field_id == fieldId }
            ?: unsupported("pick_measurement: no block with field_id=$fieldId in setup.config")
        val mc = parseMeasurementConfig(block)
            ?: unsupported("pick_measurement: block $fieldId has no parseable measurement field_config")

        val baseUnit = mc.units[0]
        val displayUnit = mc.units.firstOrNull { it.id == displayUnitId }
            ?: unsupported("pick_measurement: unit $displayUnitId not declared in field_config.units")

        val base = measurementToBase(displayValue, displayUnit)
        val snapshot = measurementSnapshot(fieldId, base, baseUnit, displayUnit)

        spy.delegateCalls.add(
            "onElementInteraction" to (snapshot.payload + mapOf("field_id" to fieldId)),
        )
        for ((k, v) in snapshot.inputValues) spy.state[k] = v
    }

    // ---------------------------------------------------------------------------------------------
    // template_engine — REAL TemplateEngine
    // ---------------------------------------------------------------------------------------------

    private fun runInterpolateTemplate(action: JSONObject, spy: Spy) {
        val device = (sessionData.optJSONObject("device") ?: JSONObject()).asMap()
            .mapValues { it.value?.toString() ?: "" }
        val input = (sessionData.optJSONObject("current_step_input") ?: JSONObject()).asMap()
            .filterValues { it != null }.mapValues { it.value!! }

        val context = TemplateContext(
            userTraits = userTraits,
            remoteConfig = { null },
            // The `input.*` namespace resolves across the collected step responses.
            onboardingResponses = if (input.isEmpty()) emptyMap() else mapOf("current_step" to input),
            computedData = emptyMap(),
            sessionData = emptyMap(),
            deviceInfo = device,
        )
        spy.state["interpolated"] = TemplateEngine.interpolate(action.getString("template"), context)
        action.optStringOrNull("missing_key")?.let {
            spy.state["missing_key_resolved_to"] = TemplateEngine.interpolate(it, context)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // dto_parsing — REAL parsers, no hand-rolled decoding
    // ---------------------------------------------------------------------------------------------

    /**
     * SPEC-448 §B — drives the REAL `applyingOverride` with host-supplied options.
     *
     * Its own driver rather than a branch of the config one: this is not a parse, it is a merge,
     * and the thing under test is what the merge leaves ALONE. A merge that rebuilt the block array
     * from only the named blocks would delete the heading and the button, and every assertion about
     * the named block would still pass.
     */
    private fun runMergeStepOverride(spy: Spy) {
        val hostOptions = sessionData.optJSONObject("host_field_options")
        val hostRoutes = sessionData.optJSONObject("host_map_routes")
        if (hostOptions == null && hostRoutes == null) {
            error("merge_step_override needs setup.session_data.host_field_options or host_map_routes")
        }

        val byBlock = mutableMapOf<String, List<ai.appdna.sdk.onboarding.InputOption>>()
        for (blockId in hostOptions?.keys() ?: emptyList<String>().iterator()) {
            val arr = hostOptions?.optJSONArray(blockId) ?: continue
            val maps = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { jsonValueToKotlin(it) }
            }
            byBlock[blockId] = ai.appdna.sdk.onboarding.OnboardingConfigParser.parseInputOptionList(maps)
        }

        // SPEC-451 — through the SAME public decoder the wrapper bridges call, so a divergence
        // between what Flutter/RN send and what the core accepts fails here rather than on a device.
        val mapRoutes = hostRoutes?.let {
            ai.appdna.sdk.onboarding.StepConfigOverride.decodeMapRoutes(jsonValueToKotlin(it))
        }

        // Parsed through the SAME step parser the renderer uses, so the merger is fed the shape it
        // is actually given at runtime.
        val step = ai.appdna.sdk.onboarding.OnboardingConfigParser.parseStepForTest(
            mapOf(
                "type" to "custom", "name" to "m", "analytics_name" to "m", "skip_allowed" to false,
                "config" to jsonValueToKotlin(config ?: error("setup.config missing")),
            )
        ) ?: error("setup.config did not parse as a step")

        val merged = step.config.applyingOverride(
            ai.appdna.sdk.onboarding.StepConfigOverride(
                fieldOptions = byBlock.ifEmpty { null },
                mapRoutes = mapRoutes,
            )
        )
        val blocks = merged.content_blocks.orEmpty()
        spy.state["merged_block_count"] = blocks.size
        spy.state["merged_block_ids"] = blocks.map { it.id }
        blocks.firstOrNull { it.id == "winery_select" }?.let { target ->
            spy.state["merged_target_option_count"] = target.field_options?.size ?: 0
            spy.state["merged_target_first_value"] =
                target.field_options?.firstOrNull()?.let { it.value ?: it.id }
        }
        blocks.firstOrNull { it.id == "other_select" }?.let { untouched ->
            spy.state["merged_untouched_option_value"] =
                untouched.field_options?.firstOrNull()?.let { it.value ?: it.id }
        }
        blocks.firstOrNull { it.id == "delivery_map" }?.let { target ->
            val cfg = target.field_config.orEmpty()
            spy.state["merged_map_polyline"] = cfg["map_route_polyline"]
            spy.state["merged_map_route_variable"] = cfg["map_route_variable"]
            val stops = cfg["map_stops"] as? List<*>
            spy.state["merged_map_stop_count"] = stops?.size ?: 0
            spy.state["merged_map_first_stop_lat"] = ((stops?.firstOrNull() as? Map<*, *>)?.get("lat") as? Number)?.toDouble()
        }
        blocks.firstOrNull { it.id == "other_map" }?.let { untouched ->
            spy.state["merged_untouched_map_mode"] = untouched.field_config.orEmpty()["map_mode"]
        }
        spy.state["merged_heading_text"] = blocks.firstOrNull { it.id == "intro_heading" }?.text
    }

    /**
     * SPEC-451 — drives the REAL `mapStaticUrl`, the same function the renderer calls.
     *
     * The point of this fixture is cross-LANGUAGE agreement, so the runner must not compose the URL
     * itself in any way: a runner-local copy would agree with the fixture forever while the renderer
     * drifted underneath it. The token comes from the fixture rather than `AppDNA.mapboxToken` so
     * the expected string is deterministic and no test needs a configured SDK.
     */
    private fun runComposeMapUrl(action: JSONObject, spy: Spy) {
        val cfg = config ?: error("compose_map_url needs setup.config")
        val blockId = action.getString("block_id")
        // Through the REAL step parser, so the block the URL is composed from is the shape the
        // renderer receives — the parser is part of what this fixture pins.
        val stepMap = mapOf<String, Any>(
            "id" to "map_step", "type" to "info", "name" to "m", "analytics_name" to "m",
            "config" to mapOf("content_blocks" to (cfg.optJSONArray("content_blocks")?.asList() ?: emptyList<Any>())),
        )
        val block = OnboardingConfigParser.parseStepForTest(stepMap)?.config?.content_blocks
            ?.firstOrNull { it.id == blockId }
            ?: unsupported("compose_map_url: no block with id=$blockId in setup.config")
        spy.state["map_url"] = ai.appdna.sdk.onboarding.mapStaticUrl(
            block,
            sessionData.optString("map_token").ifEmpty { null },
            sessionData.optInt("map_width", 390),
            sessionData.optInt("map_height", 240),
        )
    }

    private fun runFetchRemoteConfig(action: JSONObject, spy: Spy) {
        val cfg = config ?: unsupported("fetch_remote_config without setup.config")
        val map = cfg.asMap().filterValues { it != null }.mapValues { it.value!! }
        when (val path = action.getString("config_path").substringBefore('/')) {
            "options" -> {
                val provider = BillingProvider.fromWire(map["billing_provider"])
                spy.state["parse_succeeded"] = provider != null
                spy.state["parsed_billing_provider_type"] = provider?.type
                spy.state["parsed_billing_provider_api_key"] =
                    (provider as? BillingProvider.Adapty)?.apiKey
                val reencoded = provider?.toWire() as? Map<*, *>
                spy.state["reencoded_billing_provider_type"] = reencoded?.get("type")
                spy.state["reencoded_billing_provider_api_key"] = reencoded?.get("apiKey")
                for (bare in (map["billing_provider_bare_cases"] as? List<*>).orEmpty()) {
                    val decoded = BillingProvider.fromWire(bare)
                    spy.state["parsed_bare_$bare"] = decoded?.type
                }
            }
            "content_blocks" -> {
                val step = OnboardingConfigParser.parseStepForTest(
                    mapOf("id" to "s", "type" to "custom", "config" to mapOf("content_blocks" to listOf(map))),
                ) ?: unsupported("content_block fixture did not parse")
                val block = step.config.content_blocks?.firstOrNull()
                    ?: unsupported("content_block fixture parsed to no block")
                // iOS's content_blocks driver sets this and Android's never did, so a fixture
                // asserting it passed on one platform and failed on the other.
                spy.state["parse_succeeded"] = true
                spy.state["parsed_block_id"] = block.id
                spy.state["parsed_stack_children_count"] = block.children?.size ?: 0
                spy.state["parsed_column_ratios"] = block.column_ratios
                val kids = block.children.orEmpty()
                spy.state["parsed_image_fit"] = kids.firstOrNull { it.type == "image" }?.image_fit
                spy.state["parsed_rich_text_variant"] = kids.firstOrNull { it.type == "rich_text" }?.rich_text_variant
                spy.state["parsed_rating_default"] = kids.firstOrNull { it.type == "rating" }?.default_rating
                spy.state["parsed_picker_mode"] = kids.firstOrNull { it.type == "wheel_picker" }?.picker_mode
                // SPEC-439 (#546) — the label keys the natives previously did not decode at all.
                spy.state["parsed_label_position"] = block.field_style?.label_position
                spy.state["parsed_label_align"] = block.field_style?.label_align
                spy.state["parsed_label_font_family"] = block.field_style?.label_font_family
                // #560 follow-up — per-provider label size. Two providers, one with and one
                // without, so an omitted size must decode as null rather than 0.
                val provs = block.providers.orEmpty()
                spy.state["parsed_provider_count"] = provs.size
                spy.state["parsed_provider0_font_size"] = provs.getOrNull(0)?.font_size?.toDouble()
                spy.state["parsed_provider0_text_color"] = provs.getOrNull(0)?.text_color
                spy.state["parsed_provider1_font_size"] = provs.getOrNull(1)?.font_size?.toDouble()
                // #581 — the Style section, same shape as iOS.
                spy.state["parsed_image_frame"] = block.image_frame
                spy.state["parsed_frame_color"] = block.field_config?.get("frame_color") as? String
                spy.state["parsed_frame_glow_color"] = block.field_config?.get("frame_glow_color") as? String
                // #580 — the Sound Button's authored icon.
                spy.state["parsed_sound_icon"] = block.field_config?.get("sound_icon") as? String
                spy.state["parsed_sound_icon_color"] = block.field_config?.get("sound_icon_color") as? String
                spy.state["parsed_sound_icon_size"] = (block.field_config?.get("sound_icon_size") as? Number)?.toDouble()
                // #578 — the divider slot. Android folds `divider_position` into field_config
                // (JVM arg ceiling), so it is read from there, not top-level.
                spy.state["parsed_divider_position"] = block.field_config?.get("divider_position") as? String
                spy.state["parsed_divider_after_index"] = (block.field_config?.get("divider_after_index") as? Number)?.toDouble()
                spy.state["parsed_sound_icon_gap"] = (block.field_config?.get("sound_icon_gap") as? Number)?.toDouble()
                spy.state["parsed_frame_corner_radius"] = (block.field_config?.get("frame_corner_radius") as? Number)?.toDouble()
                // SPEC-444 (#540, #542) — the option's nested sheet blocks.
                val fopts = block.field_options.orEmpty()
                spy.state["parsed_opt0_sheet_block_count"] = fopts.getOrNull(0)?.sheet_blocks?.size ?: 0
                spy.state["parsed_opt0_sheet_first_type"] = fopts.getOrNull(0)?.sheet_blocks?.firstOrNull()?.type
                spy.state["parsed_opt0_sheet_last_type"] = fopts.getOrNull(0)?.sheet_blocks?.lastOrNull()?.type
                spy.state["parsed_opt1_sheet_block_count"] = fopts.getOrNull(1)?.sheet_blocks?.size ?: 0
                // #585 — the min-selection gate, in BOTH directions plus the untouched state.
                //
                // Driven through the REAL `RequiredFieldGate`, the same object the CTA consults, so
                // a gate that stops reading `min_selections` fails here rather than shipping a
                // control that silently does nothing again.
                val minSel = (block.field_config?.get("min_selections") as? Number)?.toInt()
                if (minSel != null && minSel > 0) {
                    val fieldId = block.field_id ?: block.id
                    fun gateWith(n: Int) = RequiredFieldGate.evaluate(
                        listOf(block),
                        if (n == 0) emptyMap() else mapOf(fieldId to (1..n).map { "v$it" }),
                    ).first
                    spy.state["parsed_min_selections"] = minSel
                    // Untouched: no key at all, not an empty list. A gate that only checks list size
                    // would pass this and let an unanswered step advance.
                    spy.state["gate_blocks_when_untouched"] = !gateWith(0)
                    spy.state["gate_blocks_below_minimum"] = !gateWith(minSel - 1)
                    spy.state["gate_releases_at_minimum"] = gateWith(minSel)
                    spy.state["gate_releases_above_minimum"] = gateWith(minSel + 1)
                    // The block deliberately does NOT set field_required — setting a minimum IS the
                    // requirement. If the gate only fired for required blocks, the two blocking
                    // assertions above would already be false.
                    spy.state["gate_needs_no_field_required"] = block.field_required != true
                }

                // SPEC-446 §3 — the gate, exercised in BOTH directions plus the deadlock case.
                if (block.type == "summary_screen") {
                    val statFieldIds = (block.field_config?.get("summary_stats") as? List<*>)
                        ?.mapNotNull { (it as? Map<*, *>)?.get("field_id") as? String }.orEmpty()
                    val unanswered = RequiredFieldGate.evaluate(listOf(block), emptyMap())
                    val answered = statFieldIds.associateWith { "5" as Any }
                    val satisfied = RequiredFieldGate.evaluate(listOf(block), answered)
                    spy.state["gate_blocks_when_unanswered"] = !unanswered.first
                    spy.state["gate_releases_when_answered"] = satisfied.first
                    spy.state["gate_ignores_block_level_required"] = satisfied.first
                    // A required stat carrying an authored `default` must not block: the control
                    // seeds that value on first composition, so a gate waiting on inputValues would
                    // keep the CTA disabled for a block below the fold. Answer ONLY the required
                    // stats that have no default — if the gate still releases, the defaulted one did
                    // not block, and the assertion cannot be met by simply answering everything.
                    val rawStats = (block.field_config?.get("summary_stats") as? List<*>)
                        ?.mapNotNull { it as? Map<*, *> }.orEmpty()
                    if (rawStats.any { it["default"] != null }) {
                        val partial = rawStats.filter { it["default"] == null }
                            .mapNotNull { it["field_id"] as? String }
                            .associateWith { "5" as Any }
                        spy.state["gate_ignores_required_stat_with_default"] =
                            RequiredFieldGate.evaluate(listOf(block), partial).first
                    }
                }

                // SPEC-447 (#555) — the image-tile layout keys.
                spy.state["parsed_tile_image_layout"] = block.field_config?.get("tile_image_layout") as? String
                spy.state["parsed_tile_strip_ratio"] = (block.field_config?.get("tile_strip_ratio") as? Number)?.toDouble()
                spy.state["parsed_tile_surface_color"] = block.field_config?.get("tile_surface_color") as? String

                // SPEC-448 (#556) — the Option Set source keys. field_options must STILL parse when
                // a set is bound: it doubles as the embedded page, and a parser treating a bound set
                // as "ignore the inline options" would leave older builds with an empty Select.
                spy.state["parsed_option_set_id"] = block.field_config?.get("option_set_id") as? String
                spy.state["parsed_option_set_version"] =
                    (block.field_config?.get("option_set_version") as? Number)?.toInt()
                spy.state["parsed_options_search"] = block.field_config?.get("options_search") as? Boolean
                spy.state["parsed_embedded_option_count"] = block.field_options?.size ?: 0

                // SPEC-446 — resolution, not just parsing (mirrors the iOS driver exactly).
                // `sessionData` is the runner's own accessor for setup.session_data; the responses
                // and step inputs come from there because the fixture schema has no `responses` key.
                val respJson = sessionData.optJSONObject("responses") ?: org.json.JSONObject()
                val stepJson = sessionData.optJSONObject("step_inputs") ?: org.json.JSONObject()
                val fixtureResponses: Map<String, Any> =
                    respJson.keys().asSequence().associateWith { respJson.get(it) }
                val fixtureStepInputs: Map<String, Any> =
                    stepJson.keys().asSequence().associateWith { stepJson.get(it) }
                // SPEC-452 — the host's `{{hook_data.…}}` payload. This runner hardcoded
                // `hookData = null`, so no fixture could exercise the `hook_data` root at all.
                // `jsonValueToKotlin` (not a raw `get`) because the payload is nested by definition —
                // objects and arrays of objects — and a JSONObject left unconverted resolves to
                // nothing when `resolveDotPath` tries to walk into it.
                val hookJson = sessionData.optJSONObject("hook_data") ?: org.json.JSONObject()
                val fixtureHookData: Map<String, Any> =
                    hookJson.keys().asSequence().associateWith { jsonValueToKotlin(hookJson.get(it)) }
                // SPEC-448 — seed the selected-option store so `{{selected.…}}` has something to
                // resolve. It is a process-lifetime object, so it is reset first: a value left over
                // from an earlier fixture would make this one pass for the wrong reason.
                SelectedOptionStore.resetForTesting()
                val selectedJson = sessionData.optJSONObject("selected")
                if (selectedJson != null) {
                    for (fieldId in selectedJson.keys()) {
                        SelectedOptionStore.seedForTesting(fieldId, jsonValueToKotlin(selectedJson.get(fieldId)))
                    }
                }

                if (fixtureResponses.isNotEmpty() || fixtureStepInputs.isNotEmpty() || selectedJson != null ||
                    fixtureHookData.isNotEmpty()
                ) {
                    // Drive the REAL whitelist, not resolveTemplateString directly. Calling the
                    // resolver by hand proves only that it can expand a token; it says nothing about
                    // whether the block pass applies it to a given key — round-4 bug injection deleted
                    // the `label` line from the whitelist and this file stayed green.
                    val r = resolveBlockBindings(
                        block,
                        hookData = fixtureHookData.ifEmpty { null },
                        responses = fixtureResponses,
                        stepInputs = fixtureStepInputs,
                    )
                    spy.state["resolved_text"] = r.text ?: ""
                    spy.state["resolved_option_label"] = r.field_options?.firstOrNull()?.label ?: ""
                    val rawStats = r.field_config?.get("summary_stats") as? List<*>
                    val firstStat = rawStats?.firstOrNull() as? Map<*, *>
                    spy.state["resolved_stat0_value"] = (firstStat?.get("value") as? String) ?: ""
                    // A stat that could not resolve and had no `| fallback` is DROPPED, so the count
                    // is what proves the raw token never reaches a renderer.
                    spy.state["resolved_stat_count"] = rawStats?.size ?: 0
                    spy.state["resolved_stat0_label"] = (firstStat?.get("label") as? String) ?: ""
                    // SPEC-452 — the resolved CHILD of a container. Children bypassed resolution
                    // entirely, and a fixture reading only the container's own keys cannot see that:
                    // it stays green whether or not the recursion exists.
                    val resolvedKids = (r.children ?: emptyList()) + (r.stack_children ?: emptyList())
                    spy.state["resolved_child0_text"] = resolvedKids.getOrNull(0)?.text ?: ""
                    spy.state["resolved_child0_image_url"] = resolvedKids.getOrNull(0)?.image_url ?: ""
                    if (resolvedKids.size > 1) {
                        spy.state["resolved_child1_text"] = resolvedKids[1].text ?: ""
                    }
                    // #558 — a stat BOUND to an earlier answer that also hosts a control must open on
                    // the RESOLVED value, not the authored default. Read off `r`, not the raw block:
                    // the first version of this read the unresolved `{{responses.group_size}}`, found
                    // it non-numeric, fell back to the default and asserted 9 — the very bug it is
                    // supposed to forbid, passing as if correct.
                    (rawStats?.mapNotNull { it as? Map<*, *> }?.firstOrNull { it["field_id"] == "group" })?.let { bound ->
                        val d = (bound["value"] as? String)?.trim()?.toDoubleOrNull()
                        spy.state["prefilled_stat_seeds_control"] =
                            if (d != null) {
                                if (d == kotlin.math.floor(d)) d.toInt().toString() else d.toString()
                            } else {
                                bound["default"]?.toString() ?: ""
                            }
                    }
                }

                // SPEC-441 (#541) — the option's `category` drives section navigation. Android
                // maps every option key by hand, so this is exactly the field that can go missing
                // while the chip renderer still compiles and draws an empty row.
                spy.state["parsed_opt0_category"] = fopts.getOrNull(0)?.category
                spy.state["parsed_opt2_category"] = fopts.getOrNull(2)?.category
                val rawCats = (block.field_config?.get("categories") as? List<*>)
                    ?.mapNotNull { it as? Map<*, *> }.orEmpty()
                spy.state["parsed_category_ids"] = rawCats.mapNotNull { it["id"] as? String }
                spy.state["parsed_category_labels"] = rawCats.mapNotNull { it["label"] as? String }
            }
            "paywalls" -> {
                val parsed = PaywallConfigParser.parseSinglePaywall(map["id"] as String, map)
                    ?: unsupported("paywall fixture did not parse")
                spy.state["parse_succeeded"] = true
                spy.state["parsed_paywall_id"] = parsed.id
                spy.state["parsed_plans_count"] = parsed.plans?.size ?: 0
                spy.state["parsed_reviews_count"] =
                    parsed.sections.sumOf { it.data?.reviews?.size ?: 0 }
                spy.state["parsed_post_purchase_success_action"] = parsed.post_purchase?.on_success?.action
                spy.state["parsed_post_purchase_success_confetti"] = parsed.post_purchase?.on_success?.confetti
                spy.state["parsed_post_purchase_failure_action"] = parsed.post_purchase?.on_failure?.action
                spy.state["parsed_post_purchase_failure_allow_dismiss"] =
                    parsed.post_purchase?.on_failure?.allow_dismiss
                // The CTA corner radius is REAL and parsed: the console publishes `cta.style.corner_radius`
                // (CTASchema in paywall.schema.ts) and PaywallConfigParser flattens the nested style map
                // onto PaywallCTA (PaywallConfig.kt:863-871). An earlier draft of this runner declared it
                // a "GENUINE GAP" and hardcoded null — asserting nothing, which is the whole failure mode
                // this rewrite exists to remove.
                spy.state["parsed_cta_corner_radius"] = parsed.cta?.corner_radius
                // SPEC-438 (#544, #548) — product-level price presentation. Read off the DECODED
                // objects, not the raw map, so a field the model silently drops shows up here.
                val plansSection = parsed.sections.firstOrNull { it.type == "plans" }
                spy.state["parsed_price_layout"] = plansSection?.data?.price_layout
                spy.state["parsed_strikethrough_font_size"] = plansSection?.data?.strikethrough_font_size
                spy.state["parsed_strikethrough_gap"] = plansSection?.data?.strikethrough_gap
                val plansList = parsed.plans.orEmpty()
                val p0 = plansList.getOrNull(0)
                val p1 = plansList.getOrNull(1)
                spy.state["parsed_plan0_price_display"] = p0?.displayPrice
                spy.state["parsed_plan0_original_price_display"] = p0?.original_price_display
                spy.state["parsed_plan0_price_total_display"] = p0?.price_total_display
                spy.state["parsed_plan0_badge_enabled"] = p0?.description_badge?.enabled
                spy.state["parsed_plan0_badge_bg_color"] = p0?.description_badge?.bg_color
                spy.state["parsed_plan0_badge_text_color"] = p0?.description_badge?.text_color
                spy.state["parsed_plan0_badge_corner_radius"] = p0?.description_badge?.corner_radius
                // Plan 1 authors NEITHER — absent must stay absent, which is what keeps an
                // unauthored paywall rendering exactly as it did before.
                spy.state["parsed_plan1_price_total_display"] = p1?.price_total_display
                spy.state["parsed_plan1_badge_enabled"] = p1?.description_badge?.enabled
            }
            "survey_themes" -> {
                // The fixture's config IS the theme document; SurveyAppearance owns both the theme
                // and the appearance-level rich-media fields the fixture asserts.
                val appearance = SurveyAppearance.fromMap(mapOf("theme" to map) + map)
                val theme = appearance.theme
                spy.state["parse_succeeded"] = theme != null
                spy.state["parsed_background_color"] = theme?.backgroundColor
                spy.state["parsed_accent_color"] = theme?.accentColor
                spy.state["parsed_intro_lottie_url"] = theme?.introLottieUrl ?: appearance.introLottieUrl
                spy.state["parsed_thankyou_lottie_url"] = theme?.thankyouLottieUrl ?: appearance.thankyouLottieUrl
                spy.state["parsed_thankyou_particle_effect"] =
                    theme?.thankyouParticleEffect ?: appearance.thankyouParticleEffect
                spy.state["parsed_blur_backdrop"] = theme?.blurBackdrop ?: appearance.blurBackdrop
                spy.state["parsed_haptic"] = theme?.haptic ?: appearance.haptic
                spy.state["parsed_gradient"] = theme?.gradient
                spy.state["parsed_button_gradient"] = theme?.buttonGradient
                spy.state["parsed_text_align"] = theme?.textAlign
            }
            else -> unsupported("no driver for fetch_remote_config config_path=$path")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // push_payload — REAL PushPayloadParser + PushTokenManager
    // ---------------------------------------------------------------------------------------------

    /** FCM delivers every value as a String — that is the wire the parser must survive. */
    private fun fcmData(payload: JSONObject): Map<String, String> = payload.keys().asSequence().associateWith { k ->
        when (val v = payload.get(k)) {
            is JSONObject, is JSONArray -> v.toString()
            else -> v.toString()
        }
    }

    private fun runReceivePush(action: JSONObject, spy: Spy) {
        val p = Pipeline(fixtureName).also { pipeline = it }
        val data = fcmData(action.getJSONObject("payload"))

        val parsed = PushPayloadParser.buildPayload(data) // REAL parse
        val pushManager = PushTokenManager(ctx, p.storage, p.tracker, null)
        pushManager.trackDelivered(parsed.pushId) // REAL push_delivered emit

        spy.delegateCalls.add(
            "onPushReceived" to mapOf(
                "pushId" to parsed.pushId,
                "actions" to parsed.actions.map { b ->
                    buildMap<String, Any?> {
                        put("id", b.id)
                        put("label", b.label)
                        put("action_type", b.type)
                        b.value?.let { put("action_value", it) }
                    }
                },
            ),
        )
        spy.state["registered_action_button_count"] = parsed.actions.size
        spy.events += p.envelopes().map { it.toEventPair() }
    }

    private fun runTapPush(action: JSONObject, spy: Spy) {
        val p = Pipeline(fixtureName).also { pipeline = it }
        val data = fcmData(action.getJSONObject("payload"))
        val parsed = PushPayloadParser.buildPayload(data) // REAL parse (incl. the canonical action)
        val pushManager = PushTokenManager(ctx, p.storage, p.tracker, null)

        pushManager.trackTapped(parsed.pushId) // REAL push_tapped emit
        spy.delegateCalls.add("onPushTapped" to mapOf("pushId" to parsed.pushId))

        val pushAction = parsed.action
        if (pushAction != null && pushAction.type == "deep_link") {
            val uri = android.net.Uri.parse(pushAction.value)
            val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) }
            // PLUMBING: DeepLinksModule.handleURL routes through AppDNA.track, which needs a
            // configured SDK. The URL + params below are the ones the SDK parsed out of the push.
            p.tracker.track("deep_link_handled", mapOf("url" to pushAction.value))
            spy.delegateCalls.add(
                "onDeepLinkReceived" to mapOf("url" to pushAction.value, "params" to params),
            )
            spy.state["current_route"] = listOfNotNull(uri.host, uri.path?.trim('/')?.ifEmpty { null })
                .joinToString("/")
        }
        spy.events += p.envelopes().map { it.toEventPair() }
    }

    // ---------------------------------------------------------------------------------------------
    // experiment_serving — REAL RemoteConfigManager parse + ExperimentManager.resolveSurfacePresentation
    // ---------------------------------------------------------------------------------------------

    private fun runPresentSurfaceUnderExperiment(action: JSONObject, spy: Spy) {
        val p = Pipeline(fixtureName).also { pipeline = it }
        val cfg = config ?: unsupported("present_surface_under_experiment without setup.config")
        val expJson = cfg.optJSONObject("experiment")
            ?: unsupported("present_surface_under_experiment without setup.config.experiment")
        val expId = expJson.optString("id")

        // Bucketing is deterministic on (experimentId, salt, anonId); the anon id is random per run,
        // so pin the bucket by weight instead — the hasher has its own tests (MurmurHash3Test).
        val forced = setup.optJSONObject("experiment_assignments")?.optStringOrNull(expId)
        val expMap = expJson.asMap().toMutableMap()
        if (forced != null) {
            val variants = (expMap["variants"] as? List<*>).orEmpty().map { v ->
                (v as Map<*, *>).toMutableMap().also { it["weight"] = if (it["id"] == forced) 1.0 else 0.0 }
            }
            expMap["variants"] = variants
        }

        val rcm = RemoteConfigManager(null, p.storage, 3600L)
        rcm.loadBundledConfig(mapOf("experiments" to mapOf(expId to expMap.filterValues { it != null })))

        // decode_only: assert the REAL parser decoded the served doc's field-map.
        if (action.optString("mode", "present") == "decode_only") {
            val decoded = rcm.getAllExperiments()[expId]
                ?: unsupported("served experiment doc did not decode")
            val control = decoded.variants.firstOrNull { it.isControl == true }
            val treatment = decoded.variants.firstOrNull { it.isControl == false }
            spy.state["decoded_type"] = decoded.type
            spy.state["decoded_status"] = decoded.status
            spy.state["decoded_salt"] = decoded.salt
            spy.state["decoded_variant_count"] = decoded.variants.size
            spy.state["control_config_ref"] = control?.configRef
            spy.state["control_is_control"] = control?.isControl
            spy.state["treatment_config_ref"] = treatment?.configRef
            spy.state["treatment_is_control"] = treatment?.isControl
            spy.state["treatment_has_payload"] = treatment?.config?.isNotEmpty() == true
            return
        }

        cfg.optJSONObject("variant_docs")?.let { docs ->
            for (path in docs.keys()) {
                val doc = docs.getJSONObject(path).optJSONObject("config") ?: continue
                rcm.injectVariantDocForTesting(path, doc.asMap().filterValues { it != null }.mapValues { it.value!! })
            }
        }

        val em = ExperimentManager(rcm, p.identity, p.tracker)
        val surfaceType = action.getString("surface_type")
        val entityId = action.getString("entity_id")
        val activeEntityId = cfg.optString("active_entity_id", entityId)

        // REAL resolution (also emits experiment_exposure through the real tracker on a match).
        val resolution = em.resolveSurfacePresentation(surfaceType, entityId)
        when (resolution) {
            is ExperimentManager.SurfaceResolution.RenderTreatment -> {
                spy.state["resolution"] = "treatment"
                spy.state["presented_config_id"] = resolution.payload["id"] ?: activeEntityId
            }
            is ExperimentManager.SurfaceResolution.RenderActive -> {
                // "control" vs "active" is the distinction the fixtures draw: an exposure was
                // recorded iff the user was actually bucketed into this experiment.
                spy.state["resolution"] = if (em.getExposures().any { it.experimentId == expId }) "control" else "active"
                spy.state["presented_config_id"] = activeEntityId
            }
        }
        spy.events += p.envelopes().map { it.toEventPair() }
    }

    // ---------------------------------------------------------------------------------------------
    // Assertions
    // ---------------------------------------------------------------------------------------------

    private fun assertExpectations(spy: Spy) {
        val expect = fixtureJson.getJSONObject("expect")

        val expectedEvents = expect.optJSONArray("events") ?: JSONArray()
        assertEquals(
            "[$fixtureName] event count (SDK emitted ${spy.events.map { it.first }})",
            expectedEvents.length(),
            spy.events.size,
        )
        for (i in 0 until expectedEvents.length()) {
            val expected = expectedEvents.getJSONObject(i)
            val (name, props) = spy.events[i]
            assertEquals("[$fixtureName] event[$i].name", expected.getString("name"), name)
            val expectedProps = expected.optJSONObject("properties") ?: continue
            for (key in expectedProps.keys()) {
                assertValue(
                    "[$fixtureName] event[$i]($name).properties.$key",
                    expectedProps.opt(key),
                    props[key],
                )
            }
        }

        val expectedCalls = expect.optJSONArray("delegate_calls") ?: JSONArray()
        assertEquals(
            "[$fixtureName] delegate-call count (SDK invoked ${spy.delegateCalls.map { it.first }})",
            expectedCalls.length(),
            spy.delegateCalls.size,
        )
        for (i in 0 until expectedCalls.length()) {
            val expected = expectedCalls.getJSONObject(i)
            val (name, args) = spy.delegateCalls[i]
            assertEquals("[$fixtureName] delegate[$i].name", expected.getString("name"), name)
            val expectedArgs = expected.optJSONObject("args") ?: continue
            for (key in expectedArgs.keys()) {
                assertValue(
                    "[$fixtureName] delegate[$i]($name).args.$key",
                    expectedArgs.opt(key),
                    args[key],
                )
            }
        }

        expect.optJSONObject("state_after")?.let { expectedState ->
            for (key in expectedState.keys()) {
                assertTrue(
                    "[$fixtureName] state_after.$key — the driver never observed this key, so the " +
                        "assertion would be vacuous",
                    spy.state.containsKey(key),
                )
                assertValue("[$fixtureName] state_after.$key", expectedState.opt(key), spy.state[key])
            }
        }

        val expectedErrors = expect.optJSONArray("errors") ?: JSONArray()
        assertEquals("[$fixtureName] error count (got ${spy.errors})", expectedErrors.length(), spy.errors.size)
        for (i in 0 until expectedErrors.length()) {
            assertEquals(
                "[$fixtureName] error[$i].type",
                expectedErrors.getJSONObject(i).getString("type"),
                spy.errors[i].first,
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** The persisted envelope → (event_name, props ∪ {context}) so `properties.context.*` resolves. */
    private fun JSONObject.toEventPair(): Pair<String, Map<String, Any?>> {
        val props = (optJSONObject("properties") ?: JSONObject()).asMap().toMutableMap()
        props["context"] = optJSONObject("context")?.asMap()
        return getString("event_name") to props
    }

    /**
     * Canonical, type-loose comparison. The fixtures are hand-authored JSON: `"2"` and `2`, `70` and
     * `70.0`, `"true"` and `true` all mean the same thing. Ordered for maps so key order never
     * matters. `session_id` is special-cased: the SDK mints its own, so the fixture's literal can
     * only assert that one is present.
     */
    /**
     * Compare an expected value against what the SDK produced.
     *
     * For a nested OBJECT (e.g. `properties.context`) the fixture names the keys it cares about and
     * the SDK legitimately carries more (`client_seq`, `push_id`, exposures...), so the actual map
     * is projected onto the expected keys first. Scalars and arrays compare in full — a list with an
     * extra element is a different list.
     */
    private fun assertValue(label: String, expected: Any?, actual: Any?) {
        if (expected is JSONObject) {
            val projected = (actual as? Map<*, *>)?.filterKeys { expected.has(it.toString()) }
                ?: (actual as? JSONObject)?.asMap()?.filterKeys { expected.has(it) }
            assertEquals(label, canon(expected), canon(projected))
            return
        }
        assertEquals(label, canon(expected), canon(actual))
    }

    private fun canon(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is Boolean -> v.toString()
        is Number -> if (v.toDouble() == Math.floor(v.toDouble()) && !v.toDouble().isInfinite()) {
            v.toLong().toString()
        } else {
            v.toDouble().toString()
        }
        is String -> v.toDoubleOrNull()?.let { canon(it) } ?: v
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.opt(it)) }
        is JSONObject -> canon(v.asMap())
        is List<*> -> v.joinToString(",", "[", "]") { canon(it) }
        is Map<*, *> -> v.entries
            .sortedBy { it.key.toString() }
            .joinToString(",", "{", "}") { (k, value) ->
                // The SDK mints the session id; a fixture cannot pin its literal value.
                if (k == "session_id") "session_id=<present:${(value as? String)?.isNotBlank() == true}>"
                else "$k=${canon(value)}"
            }
        else -> v.toString()
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key, null)?.takeIf { it.isNotEmpty() } else null

    private fun JSONObject.asMap(): Map<String, Any?> = keys().asSequence().associateWith { k ->
        when (val v = get(k)) {
            JSONObject.NULL -> null
            is JSONObject -> v.asMap()
            is JSONArray -> v.asList()
            else -> v
        }
    }

    private fun JSONArray.asList(): List<Any?> = (0 until length()).map {
        when (val v = get(it)) {
            JSONObject.NULL -> null
            is JSONObject -> v.asMap()
            is JSONArray -> v.asList()
            else -> v
        }
    }

    companion object {

        private fun fixturesRoot(): File {
            System.getenv("APPDNA_SDK_FIXTURES_DIR")?.let {
                val f = File(it)
                if (f.isDirectory) return f
            }
            var here: File? = File(".").canonicalFile
            repeat(10) {
                val candidate = File(here, "packages/sdk-shared-fixtures")
                if (candidate.isDirectory) return candidate
                here = here?.parentFile
            }
            val codespace = File("/workspaces/appdna-ai/packages/sdk-shared-fixtures")
            if (codespace.isDirectory) return codespace
            error("Could not locate packages/sdk-shared-fixtures. Set APPDNA_SDK_FIXTURES_DIR.")
        }

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun fixtures(): List<Array<Any>> {
            val root = fixturesRoot()
            val out = mutableListOf<Array<Any>>()
            root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".fixture.json") }
                .sortedBy { it.path }
                .forEach { file ->
                    val json = JSONObject(file.readText(Charsets.UTF_8))
                    val platforms = json.optJSONArray("platforms") ?: JSONArray()
                    val applies = (0 until platforms.length()).any { platforms.getString(it) == "android" }
                    // `render` (visual harness), `events` (EventPipelineFixtureTest) and `resilience`
                    // (ResilienceFixtureTest) carry no `action` — they are driven by their own runners.
                    val cat = json.optString("category", "")
                    val ownedElsewhere = cat == "render" || cat == "events" || cat == "resilience"
                    if (applies && !ownedElsewhere) {
                        out.add(arrayOf(file.name.removeSuffix(".fixture.json"), json))
                    }
                }
            assertTrue("No Android-applicable fixtures found in ${root.absolutePath}", out.isNotEmpty())
            return out
        }
    }
}


/**
 * JSON → plain Kotlin maps/lists, so a seeded payload is walkable by the same dot-path code the
 * resolver uses. org.json types are NOT Map/List, so seeding them raw would make every
 * `{{selected.…}}` resolve to nothing while looking perfectly correct in the fixture.
 */
private fun jsonValueToKotlin(v: Any?): Any = when (v) {
    is org.json.JSONObject -> v.keys().asSequence().associateWith { jsonValueToKotlin(v.get(it)) }
    is org.json.JSONArray -> (0 until v.length()).map { jsonValueToKotlin(v.get(it)) }
    else -> v ?: ""
}
