package ai.appdna.sdk.onboarding

/**
 * Which button the user pressed to leave the current step, for a navigation rule that routes by it.
 *
 * A rule branches on the press as an ordinary `answer_equals` on [KEY], so a flow authored this way
 * degrades safely on an older SDK: there is no such answer, the rule does not match, and the flow walks
 * on in order. The id lives here and is supplied only while the rules are evaluated, so it never
 * reaches `responses`, which is what webhooks and `onOnboardingCompleted` receive.
 *
 * SINGLE-SHOT, like [PendingCompletionRoute]: [take] reads and clears, so an advance that a press did
 * not cause (a timer, a Select) cannot route on a press from earlier.
 */
internal object OnboardingPressedButton {
    /** The reserved answer key the console writes on a button-pressed rule. */
    const val KEY = "__button_pressed"

    private var buttonId: String? = null

    /**
     * The actions a press advances the step with. A press that stays (`link`, `rate_app`, a host
     * action, `refresh_step`) must not be remembered for the advance that follows it.
     */
    fun advances(action: String): Boolean =
        action == "next" || action == "continue" || action == OnboardingCTAFlag.ACTION_NAME ||
            action == PendingCompletionRoute.ACTION_NAME || action == "permission" ||
            // The advance comes when the widget attempt ends; a how-to closed without Done clears it.
            action == WidgetPinAttempt.ACTION_NAME

    @Synchronized
    fun record(id: String, action: String) {
        if (advances(action)) buttonId = id
    }

    @Synchronized
    fun take(): String? = buttonId.also { buttonId = null }

    /** the press without consuming it: a refused CTA that opened a sheet is re-run as itself. */
    @Synchronized
    fun peek(): String? = buttonId

    /** A press the required-field gate refused, or a new flow presented. */
    @Synchronized
    fun clear() {
        buttonId = null
    }

    /** `responses` with the press visible to the rules for [stepId]. Unchanged when no button was pressed. */
    fun forRules(responses: Map<String, Any>, stepId: String, pressed: String?): Map<String, Any> {
        if (pressed == null) return responses
        @Suppress("UNCHECKED_CAST")
        val own = (responses[stepId] as? Map<String, Any>) ?: emptyMap()
        return responses + (stepId to (own + (KEY to pressed)))
    }
}
