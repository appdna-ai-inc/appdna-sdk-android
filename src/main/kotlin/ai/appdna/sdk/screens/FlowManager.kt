package ai.appdna.sdk.screens

import ai.appdna.sdk.core.ConditionEvaluator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class FlowManager(
    val flowConfig: FlowConfig,
    val screens: Map<String, ScreenConfig>,
) {
    private val _currentScreenIndex = MutableStateFlow(0)
    val currentScreenIndex: StateFlow<Int> = _currentScreenIndex

    private val navigationStack = mutableListOf<String>()
    val responses = mutableMapOf<String, Any>()
    val screensViewed = mutableListOf<String>()
    val startTime = System.currentTimeMillis()

    var onComplete: ((FlowResult) -> Unit)? = null

    /**
     * Host hook fired immediately after [onComplete], on the SAME terminal
     * transition. The presenting host ([ScreenHostActivity] in flow mode) uses
     * it to finish itself. Kept separate from [onComplete] because
     * [ScreenManager] owns that one (analytics + the caller's callback) and a
     * flow must be drivable in a unit test with no Activity at all.
     */
    internal var onFinished: (() -> Unit)? = null

    /**
     * A flow has exactly ONE terminal transition. Without this latch the
     * Activity's `onDestroy` backstop would fire `dismissFlow()` after a
     * `completeFlow()` had already run, and the SAME flow would emit
     * `flow_completed` AND `flow_abandoned`.
     */
    private var finished = false

    /** True when [navigateBack] has somewhere to go — drives hardware-back handling in the host. */
    internal val canGoBack: Boolean get() = navigationStack.isNotEmpty()

    val currentScreen: ScreenConfig?
        get() {
            val idx = _currentScreenIndex.value
            if (idx >= flowConfig.screens.size) return null
            return screens[flowConfig.screens[idx].screenId]
        }

    val currentScreenId: String?
        get() {
            val idx = _currentScreenIndex.value
            if (idx >= flowConfig.screens.size) return null
            return flowConfig.screens[idx].screenId
        }

    init {
        val startIdx = flowConfig.screens.indexOfFirst { it.screenId == flowConfig.startScreenId }
        if (startIdx >= 0) _currentScreenIndex.value = startIdx
        currentScreenId?.let { screensViewed.add(it) }
    }

    /**
     * Handle every SectionAction the SDUI layer can dispatch.
     * Mirrors iOS `Screens/FlowManager.swift handleAction(_:)`. Cases that
     * surface platform-level behavior (deep_link, paywall present, purchase)
     * fan out through [ai.appdna.sdk.AppDNA] so a single source of truth
     * runs both flows AND standalone Activity hosts.
     */
    fun handleAction(action: SectionAction) {
        when (action) {
            // 1. dismiss
            is SectionAction.Dismiss -> dismissFlow()
            // 2. next_step
            is SectionAction.Next -> advanceToNextScreen()
            // 3. prev_step
            is SectionAction.Back -> navigateBack()
            // 4. restart
            is SectionAction.Restart -> restartFlow()
            // 5. complete
            is SectionAction.Complete -> completeFlow()
            // 6. set_response
            is SectionAction.SetResponse -> {
                action.value?.let { responses[action.key] = it }
            }
            // 7. present_paywall
            is SectionAction.PresentPaywall -> {
                action.id?.let { ai.appdna.sdk.AppDNA.showPaywall(it) }
            }
            // 8. dismiss_paywall (no-op at flow level — paywall hosts its own dismissal)
            is SectionAction.DismissPaywall -> { /* paywall Activity dismisses itself */ }
            // 9. show_survey
            is SectionAction.ShowSurvey -> {
                action.id?.let { ai.appdna.sdk.AppDNA.showSurvey(it) }
            }
            // 10. show_message
            is SectionAction.ShowMessage -> {
                action.id?.let { /* MessageManager presents by id; surface via track */ }
                ai.appdna.sdk.AppDNA.track("message_request", mapOf("message_id" to (action.id ?: "")))
            }
            // 11. deep_link
            is SectionAction.DeepLink -> {
                openDeepLink(action.url)
            }
            // 12. open_url (uses OpenURL verb here; OpenWebview reserved for in-app)
            is SectionAction.OpenURL -> openExternalUrl(action.url)
            is SectionAction.OpenWebview -> openExternalUrl(action.url)
            // 13. track_event
            is SectionAction.Track -> {
                ai.appdna.sdk.AppDNA.track(action.event, action.properties ?: emptyMap())
            }
            // 14. set_user_property
            is SectionAction.SetUserProperty -> {
                action.value?.let {
                    val current = ai.appdna.sdk.AppDNA.getUserTraits().toMutableMap()
                    current[action.key] = it
                    val userId = (current["user_id"] as? String)
                        ?: (current["userId"] as? String)
                        ?: ""
                    if (userId.isNotEmpty()) {
                        ai.appdna.sdk.AppDNA.identify(userId, current)
                    }
                }
            }
            // 15. purchase — surface via track; host paywall manager performs the buy.
            is SectionAction.Purchase -> {
                ai.appdna.sdk.AppDNA.track("purchase_request", mapOf("product_id" to action.productId))
            }
            // 16. restore
            is SectionAction.Restore -> {
                ai.appdna.sdk.AppDNA.track("restore_request", emptyMap())
            }
            // Existing SDUI verbs that aren't part of the 16-case Flow API
            // but still useful as direct routes:
            is SectionAction.Navigate -> navigateToScreen(action.screenId)
            is SectionAction.ShowPaywall -> action.id?.let { ai.appdna.sdk.AppDNA.showPaywall(it) }
            is SectionAction.ShowScreen -> ai.appdna.sdk.AppDNA.showScreen(action.id)
            is SectionAction.OpenAppSettings -> openAppSettings()
            is SectionAction.Share, is SectionAction.SubmitForm, is SectionAction.Haptic,
            is SectionAction.Custom -> { /* host handles */ }
        }
    }

    private fun restartFlow() {
        // Re-enter the start screen, preserve responses for re-renders.
        val startIdx = flowConfig.screens.indexOfFirst { it.screenId == flowConfig.startScreenId }
        if (startIdx >= 0) {
            navigationStack.clear()
            _currentScreenIndex.value = startIdx
            currentScreenId?.let { screensViewed.add(it) }
        }
    }

    /**
     * Where the app context comes from. Production reads it off the configured SDK; the
     * test substitutes a Robolectric context so it can drive [handleAction] — the REAL call site
     * — rather than re-implementing the open in the test.
     */
    internal var contextProvider: () -> android.content.Context? =
        { ai.appdna.sdk.AppDNA.appContextForBridges() }

    // Both openers used to be `Intent(ACTION_VIEW, Uri.parse(configString))`,
    // straight from remote config, with no allowlist. A `javascript:` or `intent:` URL published
    // into a flow config opened whatever it named. `URLSafety.open` refuses anything that is not
    // https / mailto / tel / sms / market, or a deep link back into the host's own package.
    private fun openDeepLink(url: String) {
        val ctx = contextProvider() ?: return
        ai.appdna.sdk.core.URLSafety.open(ctx, url, newTask = true)
    }

    private fun openExternalUrl(url: String) {
        val ctx = contextProvider() ?: return
        ai.appdna.sdk.core.URLSafety.open(ctx, url, newTask = true)
    }

    private fun openAppSettings() {
        try {
            val ctx = ai.appdna.sdk.AppDNA.appContextForBridges() ?: return
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", ctx.packageName, null),
            )
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (_: Throwable) { /* best-effort */ }
    }

    private fun advanceToNextScreen() {
        val idx = _currentScreenIndex.value
        if (idx >= flowConfig.screens.size) { completeFlow(); return }

        val currentRef = flowConfig.screens[idx]
        for (rule in currentRef.navigationRules) {
            if (evaluateCondition(rule)) {
                when (rule.target) {
                    "next" -> moveToNext()
                    "end" -> completeFlow()
                    else -> navigateToScreen(rule.target)
                }
                return
            }
        }
        moveToNext()
    }

    private fun moveToNext() {
        val idx = _currentScreenIndex.value
        if (idx + 1 < flowConfig.screens.size) {
            currentScreenId?.let { navigationStack.add(it) }
            _currentScreenIndex.value = idx + 1
            currentScreenId?.let { screensViewed.add(it) }
        } else {
            completeFlow()
        }
    }

    private fun navigateBack() {
        val prev = navigationStack.removeLastOrNull() ?: return
        val newIdx = flowConfig.screens.indexOfFirst { it.screenId == prev }
        if (newIdx >= 0) _currentScreenIndex.value = newIdx
    }

    private fun navigateToScreen(screenId: String) {
        val newIdx = flowConfig.screens.indexOfFirst { it.screenId == screenId }
        if (newIdx >= 0) {
            currentScreenId?.let { navigationStack.add(it) }
            _currentScreenIndex.value = newIdx
            screensViewed.add(screenId)
        }
    }

    private fun completeFlow() = finish(completed = true)

    fun dismissFlow() = finish(completed = false)

    private fun finish(completed: Boolean) {
        if (finished) return
        finished = true
        val duration = (System.currentTimeMillis() - startTime).toInt()
        onComplete?.invoke(
            FlowResult(
                flowConfig.id,
                completed,
                currentScreenId ?: "",
                responses.toMap(),
                screensViewed.toList(),
                duration,
            ),
        )
        onFinished?.invoke()
    }

    private fun evaluateCondition(rule: NavigationRule): Boolean {
        return ConditionEvaluator.evaluateCondition(rule.condition, rule.variable, rule.value, mapOf("responses" to responses))
    }

    fun mergeResponses(newResponses: Map<String, Any>) { responses.putAll(newResponses) }
}
