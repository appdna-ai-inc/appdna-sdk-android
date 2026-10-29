package ai.appdna.sdk.screens

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.core.AudienceRuleEvaluator
import ai.appdna.sdk.core.ConditionEvaluator
import java.util.concurrent.locks.ReentrantLock
// ScreenConfig.sections is ImmutableList<ScreenSection> for
// Compose stability; override-merging needs to re-wrap after .map { ... }.
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Visibility relaxed from `internal` to public so hosts can
// register an `AppDNAScreenDelegate` via `ScreenManager.shared.setDelegate(...)`.
// The class itself remains opaque — only the delegate-setter and a couple of
// existing fan-out methods (`showScreen`/`showFlow`) form the public API.
class ScreenManager private constructor() {
    companion object {
        val shared = ScreenManager()

        /** The screens module's own events, which do not run screen triggers (see [evaluateTriggers]). */
        internal val OWN_EVENTS = setOf(
            "screen_presented", "screen_dismissed", "screen_action", "screen_response_submitted",
            "flow_started", "flow_completed", "flow_abandoned", "interception_triggered",
            "slot_registered", "slot_rendered", "slot_empty",
            "message_request", "purchase_request", "restore_request",
        )

        /** A manager with its own index and trigger state, for tests that drive the trigger decision. */
        internal fun newForTest(): ScreenManager = ScreenManager()
    }

    private var screenIndex: ScreenIndex? = null
    private val screenCache = mutableMapOf<String, ScreenConfig>()
    private val flowCache = mutableMapOf<String, FlowConfig>()
    private var nestingDepth = 0
    private val maxNestingDepth = 5
    private val lock = ReentrantLock()
    private val autoTriggerEngine = AutoTriggerEngine()
    @Volatile private var interceptionEnabled = false
    @Volatile private var interceptionFilter: List<String>? = null

    /**
     * Server-driven screen delegate. Hosts register via
     * [setDelegate]. Read fresh on every callback so a delegate registered
     * after the first showScreen() call still receives subsequent events.
     */
    @Volatile private var screenDelegate: AppDNAScreenDelegate? = null

    /**
     * OPTIONAL async `onScreenAction` wrapper-veto. Set by a
     * cross-platform wrapper (the Flutter plugin) that can only answer a veto
     * asynchronously. Consulted by [dispatchScreenAction] in ADDITION to the
     * synchronous [handleScreenAction] veto; either can block the action. Null
     * for native hosts → the action is performed synchronously and inline
     * exactly as before.
     */
    @Volatile var asyncOnScreenAction: (suspend (String, Map<String, Any?>) -> Boolean)? = null

    /** Register the host's screen delegate. */
    fun setDelegate(delegate: AppDNAScreenDelegate?) {
        screenDelegate = delegate
    }

    /**
     * Main-thread scope for awaiting the async wrapper-veto.
     * Only used when [asyncOnScreenAction] is set.
     */
    private val vetoScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    internal fun updateIndex(index: ScreenIndex) { lock.lock(); try { screenIndex = index } finally { lock.unlock() } }
    internal fun cacheScreen(id: String, config: ScreenConfig) { lock.lock(); try { screenCache[id] = config } finally { lock.unlock() } }
    internal fun getCachedScreen(id: String): ScreenConfig? { lock.lock(); try { return screenCache[id] } finally { lock.unlock() } }
    internal fun cacheFlow(id: String, config: FlowConfig) { lock.lock(); try { flowCache[id] = config } finally { lock.unlock() } }
    internal fun getCachedFlow(id: String): FlowConfig? { lock.lock(); try { return flowCache[id] } finally { lock.unlock() } }

    /**
     * Preview a screen from a raw JSON payload (host-side,
     * not from remote config). Mirrors iOS `ScreenManager.previewScreen(json:)`.
     * Parses → cache under the parsed `id` → present via [showScreen].
     * Used by the console preview flow + integration tests.
     *
     * Returns the parsed [ScreenConfig] on success, null on parse failure.
     */
    fun previewScreen(json: String): ScreenConfig? {
        return try {
            val obj = org.json.JSONObject(json)
            val map = jsonObjectToMap(obj)
            val config = ScreenConfig.fromMap(map)
            cacheScreen(config.id, config)
            showScreen(config.id)
            config
        } catch (e: Throwable) {
            ai.appdna.sdk.Log.warning("previewScreen failed: ${e.message}")
            null
        }
    }

    private fun jsonObjectToMap(obj: org.json.JSONObject): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        val it = obj.keys()
        while (it.hasNext()) {
            val key = it.next()
            out[key] = unwrapJsonValue(obj.opt(key))
        }
        return out
    }

    private fun jsonArrayToList(arr: org.json.JSONArray): List<Any?> {
        val out = ArrayList<Any?>(arr.length())
        for (i in 0 until arr.length()) out.add(unwrapJsonValue(arr.opt(i)))
        return out
    }

    private fun unwrapJsonValue(value: Any?): Any? = when (value) {
        is org.json.JSONObject -> jsonObjectToMap(value)
        is org.json.JSONArray -> jsonArrayToList(value)
        org.json.JSONObject.NULL -> null
        else -> value
    }

    /**
     * Helper — ISO8601 instant WITH its zone → epoch millis, as iOS
     * `ISO8601.date(from:)`. Returns `null` otherwise, so callers fall through (an unparsable or
     * zone-less date is "no scheduling constraint", as on iOS). The zone-less `SimpleDateFormat`
     * fallback it used to have read such a value in the device's time zone instead.
     */
    internal fun parseIsoToEpochMs(iso: String): Long? = ai.appdna.sdk.core.IsoInstant.parseEpochMs(iso)

    fun showScreen(screenId: String, callback: ((ScreenResult) -> Unit)? = null) {
        // Acquire lock once for the entire nesting check + config lookup + decrement
        val config: ScreenConfig?
        lock.lock()
        try {
            if (nestingDepth >= maxNestingDepth) {
                callback?.invoke(ScreenResult(screenId = screenId, dismissed = true, error = ScreenError.NESTING_DEPTH_EXCEEDED))
                return
            }
            nestingDepth++
            config = screenCache[screenId]
        } finally {
            lock.unlock()
        }
        val startTime = System.currentTimeMillis()
        try {
            if (config == null) {
                val errResult = ScreenResult(screenId = screenId, dismissed = true, error = ScreenError.SCREEN_NOT_FOUND)
                callback?.invoke(errResult)
                // Emit `screen_dismissed` even on the not-found
                // path so analytics shows the failed surface. Mirrors iOS error
                // dispatch shape.
                AppDNA.track("screen_dismissed", mapOf(
                    "screen_id" to screenId,
                    "duration_ms" to 0,
                    "error" to "screen_not_found",
                ))
                fireOnScreenDismissed(screenId, errResult)
                return
            }
            // Validate empty sections (mirrors
            // iOS ScreenManager.swift:118-123 AC-088/089).
            if (config.sections.isEmpty()) {
                val errResult = ScreenResult(screenId = screenId, dismissed = true, error = ScreenError.CONFIG_INVALID)
                callback?.invoke(errResult)
                AppDNA.track("screen_dismissed", mapOf(
                    "screen_id" to screenId,
                    "duration_ms" to 0,
                    "error" to "config_invalid",
                ))
                fireOnScreenDismissed(screenId, errResult)
                return
            }
            // Honor `start_date`/`end_date`
            // scheduling on the manual API path. Mirrors iOS
            // ScreenManager.swift:125-135 ISO8601 comparisons.
            val nowMs = System.currentTimeMillis()
            val startMs = config.startDate?.let { parseIsoToEpochMs(it) }
            if (startMs != null && startMs > nowMs) {
                val r = ScreenResult(screenId = screenId, dismissed = true)
                callback?.invoke(r)
                fireOnScreenDismissed(screenId, r)
                return
            }
            val endMs = config.endDate?.let { parseIsoToEpochMs(it) }
            if (endMs != null && endMs < nowMs) {
                val r = ScreenResult(screenId = screenId, dismissed = true)
                callback?.invoke(r)
                fireOnScreenDismissed(screenId, r)
                return
            }

            // Resolve experiment variant +
            // apply variant overrides + tag track props with `experiment_id`
            // and `variant_key`. Mirrors iOS
            // ScreenManager.swift:137-181.
            var resolvedConfig = config
            var variantKey: String? = null
            val expId = config.experimentId
            val variantsMap = config.variants
            if (!expId.isNullOrBlank() && variantsMap != null) {
                val bucket = AppDNA.getExperimentVariant(expId)
                if (bucket != null) {
                    variantKey = bucket
                    val override = variantsMap[bucket]
                    // Variants is now typed
                    // (Map<String, ScreenVariantOverride>); merge straight
                    // off the typed fields. Sections present means the
                    // variant supplied its own list — fall through to base
                    // when null. Other fields fall through individually.
                    if (override?.sections != null) {
                        resolvedConfig = config.copy(
                            sections = override.sections,
                            presentation = override.presentation ?: config.presentation,
                            // Variant transition merge.
                            transition = override.transition ?: config.transition,
                            background = override.background ?: config.background,
                            triggerRules = override.triggerRules ?: config.triggerRules,
                        )
                    }
                }
            }

            // Route every manual-API
            // presentation through PresentationCoordinator so concurrent
            // surface conflicts are arbitrated centrally (mirrors iOS
            // ScreenManager.swift:189). Without this, a direct
            // `AppDNA.showScreen(id)` could race with an in-flight
            // paywall/survey and clobber it.
            val trackProps = mutableMapOf<String, Any>(
                "screen_id" to screenId,
                "screen_name" to resolvedConfig.name,
                "presentation" to resolvedConfig.presentation,
            )
            if (!expId.isNullOrBlank()) trackProps["experiment_id"] = expId
            if (variantKey != null) trackProps["variant_key"] = variantKey

            val capturedConfig = resolvedConfig
            ai.appdna.sdk.core.PresentationCoordinator.shared.requestPresentation(
                type = ai.appdna.sdk.core.PresentationCoordinator.PresentationType.SCREEN,
                isAutoTriggered = false,
            ) {
                AppDNA.track("screen_presented", trackProps)
                // Fire onScreenPresented to host delegate.
                fireOnScreenPresented(screenId)

                val result = ScreenResult(screenId = screenId)
                callback?.invoke(result)

                // Actually present
                // the screen via [ScreenHostActivity]. iOS counterpart is
                // ScreenPresenter.swift:7-55 which wraps the renderer in
                // a UIHostingController and presents it. Without this Activity
                // launch, hosts that called `AppDNA.showScreen("welcome")`
                // got analytics-only behavior — the screen never rendered.
                val appCtx = AppDNA.getApplicationContext()
                if (appCtx != null) {
                    ScreenHostActivity.launch(
                        context = appCtx,
                        screenId = screenId,
                        config = capturedConfig,
                        onDismiss = { dismissResult ->
                            val durationMs = dismissResult.durationMs
                                ?: (System.currentTimeMillis() - startTime).toInt()
                            AppDNA.track("screen_dismissed", mapOf(
                                "screen_id" to screenId,
                                "screen_name" to capturedConfig.name,
                                "duration_ms" to durationMs,
                            ))
                            fireOnScreenDismissed(screenId, dismissResult.copy(durationMs = durationMs))
                            ai.appdna.sdk.core.PresentationCoordinator.shared.onDismissed()
                        },
                    )
                } else {
                    // Fallback: no application context (SDK not configured) — emit
                    // synchronous dismiss so callers + analytics don't hang.
                    val durationMs = (System.currentTimeMillis() - startTime).toInt()
                    AppDNA.track("screen_dismissed", mapOf(
                        "screen_id" to screenId,
                        "screen_name" to capturedConfig.name,
                        "duration_ms" to durationMs,
                        "error" to "no_application_context",
                    ))
                    fireOnScreenDismissed(
                        screenId,
                        ScreenResult(screenId = screenId, dismissed = true, durationMs = durationMs),
                    )
                    ai.appdna.sdk.core.PresentationCoordinator.shared.onDismissed()
                }
            }
        } finally {
            lock.lock()
            try { nestingDepth-- } finally { lock.unlock() }
        }
    }

    fun showFlow(flowId: String, callback: ((FlowResult) -> Unit)? = null) {
        val config = getCachedFlow(flowId)
        if (config == null) {
            val errResult = FlowResult(flowId = flowId, error = ScreenError.SCREEN_NOT_FOUND)
            callback?.invoke(errResult)
            // `flow_abandoned` covers the not-found bailout
            // (no flow ran to completion). Mirrors iOS shape.
            AppDNA.track("flow_abandoned", mapOf(
                "flow_id" to flowId,
                "error" to "flow_not_found",
            ))
            fireOnFlowCompleted(flowId, errResult)
            return
        }
        // 🔴 THE FLOW USED TO RENDER NOTHING. This method emitted `flow_started`, built an EMPTY
        // `FlowResult` (completed=false), invoked the caller's callback, emitted `flow_abandoned`
        // and returned — while [FlowManager], the real 21-verb router, was never instantiated
        // anywhere in `src/main`. Every console-authored multi-screen flow — from a native host,
        // from React Native, from Flutter — started, instantly "finished", and showed no UI.
        //
        // Now it does what iOS does at `Screens/ScreenManager.swift:220-256`: collect the flow's
        // screen configs, build a FlowManager over them, wire `onComplete` to the analytics + the
        // caller's callback, emit `flow_started`, and PRESENT (ScreenPresenter.presentFlow →
        // ScreenHostActivity flow mode). `flow_completed` vs `flow_abandoned` is now decided by the
        // FlowManager's REAL terminal transition, and carries the real screens_viewed + duration.
        val screens = mutableMapOf<String, ScreenConfig>()
        for (ref in config.screens) {
            getCachedScreen(ref.screenId)?.let { screens[ref.screenId] = it }
        }

        val flowManager = FlowManager(config, screens)
        flowManager.onComplete = { result ->
            // iOS ScreenManager.swift — same event names, same property set. Emit the LITERAL name per
            // terminal: the catalog-provenance gate harvests real `track("…")` literals, and a
            // ternary-built name is invisible to it (that is why `flow_completed` could not be catalogued).
            val props = mapOf(
                "flow_id" to flowId,
                "flow_name" to config.name,
                "screens_viewed" to result.screensViewed,
                "duration_ms" to result.durationMs,
            )
            if (result.completed) {
                AppDNA.track("flow_completed", props)
            } else {
                AppDNA.track("flow_abandoned", props)
            }
            // Fire onFlowCompleted delegate (fires on both
            // completed and abandoned paths — host reads result.completed).
            fireOnFlowCompleted(flowId, result)
            callback?.invoke(result)
        }

        AppDNA.track("flow_started", mapOf(
            "flow_id" to flowId,
            "flow_name" to config.name,
            "start_screen_id" to config.startScreenId,
        ))

        val appCtx = AppDNA.getApplicationContext()
        if (appCtx == null || flowManager.currentScreen == null) {
            // Nothing can be rendered (SDK not configured, or the start screen's config was never
            // cached). Terminate the flow properly instead of leaving the caller hanging — one
            // `flow_abandoned` through the SAME path a user-abandoned flow takes.
            ai.appdna.sdk.Log.warning(
                if (appCtx == null) "showFlow($flowId): no application context — SDK not configured."
                else "showFlow($flowId): start screen '${config.startScreenId}' not cached — cannot render.",
            )
            flowManager.dismissFlow()
            return
        }
        ScreenHostActivity.launchFlow(appCtx, flowId, flowManager)
    }

    /**
     * Best-effort dismissal of the currently
     * presented SDUI screen. Mirrors iOS `ScreenManager.dismissScreen` which
     * dismisses the top UIViewController.
     *
     * Today screens render via [AppDNAScreenSlot] in the host's own
     * Composable tree (NOT via a dedicated SDUI ScreenActivity yet — that's
     * tracked separately as the larger B6 P0-2 follow-up). For activity-hosted
     * presentations (PaywallActivity / OnboardingActivity / SurveyActivity)
     * the host already calls module-specific dismissers; this method
     * additionally signals [PresentationCoordinator] that the modal closed
     * so the next queued presentation can fire, and best-effort finishes
     * any tracked SDUI ScreenActivity once that lands.
     */
    fun dismissScreen() {
        // Release the modal mutex so a queued paywall/message/survey can run.
        try {
            ai.appdna.sdk.core.PresentationCoordinator.shared.onDismissed()
        } catch (_: Throwable) { /* best-effort */ }
    }

    // Main-thread delegate fan-out helpers. All read the
    // delegate fresh on every call so hosts can register/unregister
    // dynamically without missing events.

    private fun fireOnScreenPresented(screenId: String) {
        val delegate = screenDelegate ?: return
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { delegate.onScreenPresented(screenId) } catch (_: Throwable) { /* swallow */ }
            }
        } catch (_: Throwable) { /* best-effort */ }
    }

    private fun fireOnScreenDismissed(screenId: String, result: ScreenResult) {
        val delegate = screenDelegate ?: return
        val map = mutableMapOf<String, Any?>(
            "screen_id" to result.screenId,
            "dismissed" to result.dismissed,
            "responses" to result.responses,
            "last_action" to result.lastAction,
            "duration_ms" to result.durationMs,
        )
        result.error?.let { map["error"] = it.name }
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { delegate.onScreenDismissed(screenId, map) } catch (_: Throwable) { /* swallow */ }
            }
        } catch (_: Throwable) { /* best-effort */ }
    }

    private fun fireOnFlowCompleted(flowId: String, result: FlowResult) {
        val delegate = screenDelegate ?: return
        val map = mutableMapOf<String, Any?>(
            "flow_id" to result.flowId,
            "completed" to result.completed,
            "last_screen_id" to result.lastScreenId,
            "responses" to result.responses,
            "screens_viewed" to result.screensViewed,
            "duration_ms" to result.durationMs,
        )
        result.error?.let { map["error"] = it.name }
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { delegate.onFlowCompleted(flowId, map) } catch (_: Throwable) { /* swallow */ }
            }
        } catch (_: Throwable) { /* best-effort */ }
    }

    /**
     * Public hook for SectionRegistry / Activity-based
     * action dispatch to fire the screen delegate's `onScreenAction` veto
     * hook AND emit the `screen_action` analytics event. Returns `false`
     * when the host vetoed the action — the caller MUST NOT execute the
     * default action handling in that case.
     *
     * [action] mirrors iOS `SectionAction` payload shape (`type` plus
     * type-specific fields).
     */
    fun handleScreenAction(screenId: String, action: Map<String, Any?>): Boolean {
        // Veto hook: false from delegate prevents default handling. Synchronous
        // because the caller relies on the return value to skip default behavior. Errors swallowed →
        // treat as "no veto" (allow).
        // This no longer emits `screen_action`. The PERFORMER emits it (the standalone
        // ScreenHostActivity dispatch, after side-effects, only when BOTH vetoes allow), matching iOS
        // performAction: NO emit on a vetoed action (sync OR async), NO emit for inline slots, and the
        // emit ordered AFTER the action's side-effects. Emitting in the gate fired BEFORE the async
        // wrapper-veto (so RN/Flutter async-vetoed actions over-emitted) and fired for slots too.
        val delegate = screenDelegate ?: return true
        return try {
            delegate.onScreenAction(screenId, action)
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * Full veto gate for a screen action. Runs the synchronous
     * [handleScreenAction] veto (which also emits the `screen_action` analytics)
     * exactly as before; if it allows AND an async wrapper-veto
     * ([asyncOnScreenAction]) is registered, awaits it too. [perform] runs only
     * when BOTH vetoes allow.
     *
     * When no async veto is set (every native host), [perform] runs
     * synchronously inline so the host's action dispatch ordering is
     * byte-identical to before this seam existed. When an async veto is set,
     * [perform] runs on the main thread once the awaited decision resolves.
     */
    fun dispatchScreenAction(screenId: String, action: Map<String, Any?>, perform: () -> Unit) {
        // (1) synchronous gate — unchanged; false → block (and no analytics
        // change since handleScreenAction still emits screen_action).
        if (!handleScreenAction(screenId, action)) return
        // (2) async wrapper-veto — only when registered.
        val asyncVeto = asyncOnScreenAction
        if (asyncVeto == null) {
            perform()
            return
        }
        vetoScope.launch {
            val allow = try {
                asyncVeto(screenId, action)
            } catch (_: Throwable) {
                true
            }
            if (allow) perform()
        }
    }

    /**
     * Emit `screen_response_submitted` for form/submit
     * actions. Hosts call this when a screen submits a form payload to
     * the SDK. Mirrors iOS ScreenManager.swift:311.
     */
    fun trackScreenResponseSubmitted(screenId: String, fieldCount: Int) {
        AppDNA.track("screen_response_submitted", mapOf(
            "screen_id" to screenId,
            "field_count" to fieldCount,
        ))
    }

    fun screenForSlot(slotName: String): Pair<String, ScreenConfig>? {
        val index = screenIndex ?: return null
        val assignment = index.slots?.firstOrNull { it.slotName == slotName } ?: return null
        if (assignment.audienceRules != null) {
            val traits = AppDNA.getUserTraits()
            if (!AudienceRuleEvaluator.evaluate(assignment.audienceRules, traits)) return null
        }
        val config = getCachedScreen(assignment.screenId) ?: return null
        return assignment.screenId to config
    }

    /**
     * Every tracked event (`AppDNA.track`) runs the screen triggers, as messages and surveys run theirs. The
     * screens module's own events are skipped: a session-count, install-age or trait trigger matches on any
     * evaluation, and a presented (or failed) screen tracking `screen_*` must not re-trigger itself.
     */
    fun evaluateTriggers(event: String, properties: Map<String, Any>?) {
        if (event in OWN_EVENTS) return
        present { triggeredScreen(event, properties, currentScreenName = null) }
    }

    /**
     * A host screen appeared: evaluate the `on_screen` triggers against its name. Called from the same
     * place the "after" navigation interceptions are (every Activity resume, and `notifyScreenAppeared`
     * for Compose screens). An event is not involved, so no `events` trigger matches here.
     */
    fun onScreenAppeared(screenName: String) {
        present { triggeredScreen(event = "", properties = null, currentScreenName = screenName) }
    }

    /**
     * The ONE trigger decision: which screen (if any) this event or screen appearance presents. Every
     * trigger kind the console can author is evaluated by [AutoTriggerEngine] (events with their
     * conditions, session count, days since install, the current screen, user traits) together with the
     * frequency caps, which the engine records when it picks a screen.
     */
    internal fun triggeredScreen(
        event: String,
        properties: Map<String, Any>?,
        currentScreenName: String?,
        inputs: ScreenTriggerInputs = ScreenTriggerInputs.current(),
        traits: Map<String, Any> = AppDNA.getUserTraits(),
    ): String? {
        // Consent gate (iOS `ScreenManager.evaluateTriggers`): screens never fire from analytics events
        // once the user revoked consent.
        if (!AppDNA.isConsentGranted()) return null
        val entries = run { lock.lock(); try { screenIndex?.screens } finally { lock.unlock() } } ?: return null
        return autoTriggerEngine.evaluate(
            entries, event, properties, traits,
            sessionCount = inputs.sessionCount,
            daysSinceInstall = inputs.daysSinceInstall,
            currentScreenName = currentScreenName,
        )
    }

    /**
     * Presents what [decide] picks. Presentability is checked FIRST: the engine records an impression when it
     * picks a screen, so a pick that could not be shown would otherwise spend the screen's frequency cap.
     */
    private inline fun present(decide: () -> String?) {
        if (!ai.appdna.sdk.core.PresentationCoordinator.shared.canPresent(
            ai.appdna.sdk.core.PresentationCoordinator.PresentationType.SCREEN, isAutoTriggered = true)) return
        decide()?.let { showScreen(it) }
    }

    fun enableNavigationInterception(forScreens: List<String>? = null) {
        interceptionEnabled = true
        interceptionFilter = forScreens
        // Register a marker hook per pattern so
        // `NavigationInterceptor.shared` knows which screens this host has
        // opted in for. The hook body is a no-op returning `Allow` —
        // server-driven `screen_index.interceptions[*]` evaluation is
        // already wired inside `NavigationInterceptor.evaluateInterceptions`
        // which calls back into `ScreenManager.evaluateInterceptions`. The
        // hook list serves as the host's allowlist; without these entries
        // the interceptor still fans out via Activity callbacks but won't
        // be aware of which Compose-only routes are eligible.
        val patterns = forScreens ?: listOf("*")
        for (pattern in patterns) {
            NavigationInterceptor.shared.registerHook(pattern) { _ ->
                InterceptionResult.Allow
            }
        }
    }
    fun disableNavigationInterception() {
        interceptionEnabled = false
        val patterns = interceptionFilter ?: listOf("*")
        for (pattern in patterns) {
            NavigationInterceptor.shared.unregisterHook(pattern)
        }
        interceptionFilter = null
    }

    fun evaluateInterceptions(screenName: String, timing: String) {
        if (!interceptionEnabled) return
        val filter = interceptionFilter
        if (filter != null && !filter.any { matchGlob(it, screenName) }) return
        val interceptions = screenIndex?.interceptions ?: return
        val traits = AppDNA.getUserTraits()
        for (interception in interceptions) {
            if (interception.timing != timing) continue
            if (!matchGlob(interception.triggerScreen, screenName)) continue
            if (interception.audienceRules != null && !AudienceRuleEvaluator.evaluate(interception.audienceRules, traits)) continue
            AppDNA.track("interception_triggered", mapOf("trigger_screen" to screenName, "timing" to timing, "screen_id" to interception.screenId))
            showScreen(interception.screenId)
            return
        }
    }

    private fun matchGlob(pattern: String, string: String): Boolean {
        val p = pattern.lowercase(); val s = string.lowercase()
        if (!p.contains("*")) return p == s
        if (p.startsWith("*") && p.endsWith("*")) return s.contains(p.drop(1).dropLast(1))
        if (p.startsWith("*")) return s.endsWith(p.drop(1))
        if (p.endsWith("*")) return s.startsWith(p.dropLast(1))
        return p == s
    }

    fun reset() { lock.lock(); try { screenIndex = null; screenCache.clear(); flowCache.clear(); nestingDepth = 0 } finally { lock.unlock() }; disableNavigationInterception() }
}
