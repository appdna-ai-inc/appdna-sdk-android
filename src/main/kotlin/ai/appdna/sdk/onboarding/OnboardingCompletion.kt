package ai.appdna.sdk.onboarding

/**
 * SPEC-070-B — the onboarding flow-completion seam.
 *
 * WHY THIS FILE EXISTS: "the flow finished" is the single most important event onboarding emits —
 * it is the denominator of the whole funnel and the only moment the host is handed the user's
 * answers. On Android it was reachable ONLY through `OnboardingFlowManager.present(activity, ...)`:
 * the event track, the response persistence and `onOnboardingCompleted` all lived inside a lambda
 * that `present()` closes over, so nothing without an `Activity` — no unit test, no cross-platform
 * fixture, no future host-driven presentation path — could reach the completion decision or prove
 * it fired. [OnboardingAdvance] had already been extracted for exactly this reason and returns
 * `Navigation.CompleteFlow`; this is the other half of that seam: what the SDK must DO when it
 * gets one.
 *
 * THE CROSS-PLATFORM CONTRACT (read off iOS, not invented here):
 *  - event `onboarding_flow_completed`, props `{flow_id, total_steps, total_duration_ms, responses}`
 *    — iOS `Onboarding/OnboardingFlowManager.swift:104-110`. Android already emitted this exact
 *    name and prop set; the bug was reachability, not naming, so nothing is renamed.
 *  - delegate `onOnboardingCompleted(flowId, responses)` — iOS `OnboardingFlowManager.swift:112`,
 *    Android [AppDNAOnboardingDelegate.onOnboardingCompleted].
 *  - responses are persisted to the session store first (SPEC-088), so a host reacting inside
 *    `onOnboardingCompleted` already sees them.
 *
 * NOT `flow_completed`: that name belongs to the SCREENS module
 * (`ScreenManager` — iOS `Screens/ScreenManager.swift:232`, Android `ScreenManager.kt`), which
 * emits `flow_completed` / `flow_abandoned` for screen flows. Neither platform has ever emitted it
 * for onboarding, and reusing it would silently merge two different funnels in the warehouse.
 */
internal object OnboardingCompletion {

    /**
     * The event the SDK MUST emit when an onboarding flow completes. A constant, not a literal at
     * the call site: the shared cross-platform fixtures assert this exact spelling, so a rename has
     * to break them rather than quietly retire the funnel's denominator.
     */
    const val ONBOARDING_FLOW_COMPLETED_EVENT = "onboarding_flow_completed"

    /** Pure: the completion event, so its shape can be asserted without a tracker or an Activity. */
    fun completionEvent(
        flowId: String,
        totalSteps: Int,
        durationMs: Int,
        responses: Map<String, Any>,
    ): OnboardingAdvance.TrackedEvent = OnboardingAdvance.TrackedEvent(
        ONBOARDING_FLOW_COMPLETED_EVENT,
        mapOf(
            "flow_id" to flowId,
            "total_steps" to totalSteps,
            // Int, not Long — iOS emits `Int(... * 1000)` (OnboardingFlowManager.swift:82) and the
            // warehouse schema is typed off it.
            "total_duration_ms" to durationMs,
            "responses" to responses,
        ),
    )

    /**
     * The whole completion decision, Activity-free: emit the event, persist the responses, notify
     * the delegate — in that order, so a host that reads `AppDNA.getOnboardingResponses()` from
     * inside `onOnboardingCompleted` sees them, and so the analytic exists even if the host's
     * delegate throws.
     *
     * [track] is the caller's `EventTracker::track` (or any sink — this is what makes the seam
     * testable). [delegate] is the flow's listener; null is legal (a host may not want a callback).
     */
    fun complete(
        flowId: String,
        totalSteps: Int,
        durationMs: Int,
        responses: Map<String, Any>,
        track: (String, Map<String, Any>) -> Unit,
        delegate: AppDNAOnboardingDelegate?,
        /**
         * How to open a `link_on_complete` destination. Injected rather than taken as a Context for
         * the same reason [track] is: this function stays provable without presenting an Activity.
         * Null means "no opener wired", which is legal — the route is still consumed so it cannot
         * fire later against a different flow.
         */
        openRoute: ((String) -> Boolean)? = null,
    ) {
        val event = completionEvent(flowId, totalSteps, durationMs, responses)
        track(event.name, event.props)
        // SPEC-088: persist onboarding responses for cross-module access.
        ai.appdna.sdk.core.SessionDataStore.instance?.setOnboardingResponses(responses)
        try {
            delegate?.onOnboardingCompleted(flowId = flowId, responses = responses)
        } catch (e: Throwable) {
            // A throwing host delegate must not take the SDK (or the flow's dismissal) down with it.
            ai.appdna.sdk.Log.warning("AppDNAOnboardingDelegate.onOnboardingCompleted threw: ${e.message}")
        }
        // A `link_on_complete` CTA asked to go somewhere once the flow finished. Taken AFTER the
        // delegate so the host's own completion work — dismissing, persisting, its own navigation —
        // runs first; opening before it would race our navigation against theirs.
        //
        // `take()` unconditionally, even with no opener wired: consuming it is what stops a stale
        // destination firing at the end of some later flow.
        val route = PendingCompletionRoute.take()
        if (route != null) {
            val opened = try {
                openRoute?.invoke(route) ?: false
            } catch (e: Throwable) {
                ai.appdna.sdk.Log.warning("Opening a CTA's after-onboarding link threw: ${e.message}")
                false
            }
            if (!opened) {
                ai.appdna.sdk.Log.warning("A CTA's after-onboarding link was not opened: '$route'")
            }
        }
    }
}
