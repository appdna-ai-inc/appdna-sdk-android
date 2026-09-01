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
        ai.appdna.sdk.AppDNA.reportInitDegraded(
            IllegalStateException(
                "Content block type '$rawType' is not supported by AppDNA SDK " +
                    "${ai.appdna.sdk.AppDNA.sdkVersion} and will render as nothing. Update the SDK."
            )
        )
    }

    /** Test seam — the set is process-global, so a test asserting on the first report must clear it. */
    fun resetForTesting() = reported.clear()
}
