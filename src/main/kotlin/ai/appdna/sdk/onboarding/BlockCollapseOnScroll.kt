package ai.appdna.sdk.onboarding

/**
 * HOW FAR a block authored `collapse_on_scroll` has collapsed, given how far the step has scrolled.
 * Mirror of iOS `Onboarding/BlockCollapseOnScroll.swift`, and the same function both call.
 *
 * 🔴 IOS applied this and Android decoded it and stopped there: `collapse_on_scroll` and
 * `collapse_threshold` were two declarations in this module and no use, while every application of
 * the key lived in `PaywallActivity`, a different surface. One authored document, a block that
 * faded away on one platform and sat still on the other.
 *
 * `0` is fully shown, `1` is fully collapsed; the renderer maps it to alpha and height. The
 * threshold is how many points of scroll it takes, defaulting to the 50 that shipped on iOS.
 */
object BlockCollapseOnScroll {

    /** The threshold that shipped hardcoded on iOS. A zero or negative one would divide by zero. */
    const val DEFAULT_THRESHOLD: Double = 50.0

    /** `0` → fully visible, `1` → fully collapsed. */
    fun progress(enabled: Boolean, scrollOffset: Double, threshold: Double?): Double {
        if (!enabled) return 0.0
        val t = threshold?.let { if (it > 0) it else DEFAULT_THRESHOLD } ?: DEFAULT_THRESHOLD
        return (scrollOffset / t).coerceIn(0.0, 1.0)
    }
}
