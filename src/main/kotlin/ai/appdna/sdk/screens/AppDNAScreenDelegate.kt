package ai.appdna.sdk.screens

/**
 * Server-driven screen lifecycle delegate.
 *
 * Mirrors iOS `AppDNAScreenDelegate` (AppDNA+Delegates.swift:78-83). The
 * fourth method ([onScreenAction]) returns `Boolean` for veto/intercept
 * — `false` prevents default handling so hosts can fully replace the
 * action behavior.
 *
 * All methods have default implementations so hosts can implement only
 * the callbacks they care about.
 *
 * Hosts register via [ScreenManager.shared.setDelegate].
 */
interface AppDNAScreenDelegate {
    /** Called immediately after a screen has been presented. */
    fun onScreenPresented(screenId: String) {}

    /**
     * Called when a screen is dismissed. [result] mirrors iOS `ScreenResult`
     * shape: keys include `screen_id`, `dismissed`, `responses`, `last_action`,
     * `duration_ms`, optional `error`.
     */
    fun onScreenDismissed(screenId: String, result: Map<String, Any?>) {}

    /**
     * Called when a flow finishes (completed OR abandoned). [result] mirrors
     * iOS `FlowResult`: keys include `flow_id`, `completed`, `last_screen_id`,
     * `responses`, `screens_viewed`, `duration_ms`, optional `error`.
     */
    fun onFlowCompleted(flowId: String, result: Map<String, Any?>) {}

    /**
     * Veto hook. Return `false` to intercept the action
     * and prevent the SDK's default handling (e.g. so the host can perform
     * its own navigation, render its own paywall, etc). Return `true`
     * (default) to let the SDK process the action normally.
     *
     * [action] keys mirror iOS `SectionAction` payload: `type` (e.g.
     * `"navigate"`, `"deep_link"`, `"show_paywall"`, `"track"`, `"custom"`),
     * plus type-specific fields (`target_screen_id`, `url`, `paywall_id`,
     * `event_name`, `event_properties`, `value`, etc).
     */
    fun onScreenAction(screenId: String, action: Map<String, Any?>): Boolean = true
}
