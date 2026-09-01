package ai.appdna.sdk.onboarding

/**
 * The destination a `link_on_complete` CTA asked for, held until the flow finishes.
 *
 * WHY THIS EXISTS: a cross-sell near the end of a flow ("book a wine tasting") has a destination
 * that should open once the user is actually done. Opening it on tap tears them out of an
 * onboarding they have not finished — the same reason `flag` advances instead of routing. So the
 * tap records where to go, the user continues, and the SDK opens it at completion.
 *
 * Deliberately NOT stored in the completion `responses`: that map is what customer webhooks receive
 * and what `onOnboardingCompleted` hands the host, and it must stay byte-identical for anyone not
 * using this. A CTA destination is SDK plumbing, not an answer the user gave. (This is the one way
 * it differs from `OnboardingCTAFlag`, which is a recorded ANSWER and belongs in `responses`.)
 *
 * SINGLE-SHOT, and that is the point. [take] reads and clears, so a recorded route fires at most
 * once. Without it, a route recorded in a flow the user ABANDONED would still be here when a later
 * flow completed, and the app would navigate somewhere the user never asked to go — surfacing as
 * "the app randomly opens booking".
 *
 * Mirrors iOS `PendingCompletionRoute.swift`.
 */
internal object PendingCompletionRoute {

    /** The `action` value the console writes and every SDK switches on. */
    const val ACTION_NAME = "link_on_complete"

    private val lock = Any()
    private var url: String? = null

    /** Record where to go when the flow finishes. A second tap replaces the first. */
    fun record(raw: String?) {
        val trimmed = raw?.trim()
        if (trimmed.isNullOrEmpty()) return
        synchronized(lock) { url = trimmed }
    }

    /** Read and clear. Null when no CTA asked for a destination. */
    fun take(): String? = synchronized(lock) {
        val out = url
        url = null
        out
    }

    /**
     * Drop anything recorded. Called when a flow is PRESENTED, so a destination left by an
     * abandoned flow cannot leak into the next one.
     */
    fun clear() {
        synchronized(lock) { url = null }
    }
}
