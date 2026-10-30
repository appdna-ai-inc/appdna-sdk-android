package ai.appdna.sdk.onboarding

import java.util.concurrent.ConcurrentHashMap

/**
 * Every block `type` seen in a published flow that this SDK version does not know.
 *
 * WHY THIS EXISTS: an unknown block renders as NOTHING. That is the right behaviour — one block the
 * SDK has never heard of must not take the step down with it — but it used to happen in complete
 * silence. A step then draws its heading, its CTAs and its footnote with a hole in the middle, which
 * is indistinguishable from a layout bug unless the SDK says otherwise, and a console preview that
 * renders the block perfectly makes the SDK look like the thing that is broken.
 *
 * Reported ONCE per distinct type: a flow with twenty steps carrying the same unsupported block
 * would otherwise fire the host's delegate twenty times and drown the signal it exists to give.
 *
 * Mirrors iOS `UnsupportedBlockTypes`.
 */
internal object UnsupportedBlockTypes {
    private val reported = ConcurrentHashMap.newKeySet<String>()

    fun note(rawType: String) {
        if (!reported.add(rawType)) return
        val detail = "content_blocks[].type: '$rawType' is not supported by AppDNA SDK " +
            "${ai.appdna.sdk.AppDNA.sdkVersion} and renders as nothing. Update the SDK."
        ai.appdna.sdk.AppDNA.reportInitDegraded(IllegalStateException(detail))
        /*
         * And to the fleet, and to the lifecycle delegate.
         *
         * `onInitDegraded` has carried this since it was written, which is the HOST half. What
         * nobody had was the other one: how often this happens in the field. The event answers
         * that without any host implementing anything, and `onConfigurationIssue` is the delegate
         * a host can forward to its own crash reporter alongside every other config problem,
         * rather than only the ones that look like an init failure.
         */
        ai.appdna.sdk.core.ConfigurationIssueReporter.report(
            surface = "onboarding_flow",
            id = null,
            detail = detail,
        )
    }

    /** Test seam — the set is process-global, so a test asserting on the first report must clear it. */
    fun resetForTesting() = reported.clear()
}
