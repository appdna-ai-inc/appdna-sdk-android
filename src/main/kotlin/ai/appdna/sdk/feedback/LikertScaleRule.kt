package ai.appdna.sdk.feedback

/*
 * #998 — THE LIKERT SCALE'S BOUNDS, AS ONE RULE BOTH PLATFORMS CALL.
 *
 * Android had rendered a likert scale for releases while iOS drew nothing, and the clamp that
 * keeps a malformed config from crashing lived INSIDE each view. That is the shape #1037 was: a
 * rule written twice, in two languages, agreeing until one of them was edited — there, a Kotlin
 * `?: return` where Swift had an `if let` inside the condition, and a survey's follow-up silently
 * never ran.
 *
 * So the rule is a function, in one file per platform, called by the renderer AND by the shared
 * behavioural fixture. A fixture that re-implemented it could only ever agree with itself.
 */
object LikertScaleRule {
    /** Both platforms draw 1..5 when the config says nothing. */
    const val DEFAULT_MIN = 1
    const val DEFAULT_MAX = 5

    data class Bounds(val lo: Int, val hi: Int)

    /**
     * The scale's inclusive bounds.
     *
     * 🔴 A malformed `min > max` is SWAPPED, not trusted. On iOS a reversed `ClosedRange` traps at
     * runtime, and here `lo..hi` would simply be empty and draw no buttons at all — two different
     * wrong answers to the same bad config, which is why one rule decides it.
     */
    @JvmStatic
    fun bounds(min: Int?, max: Int?): Bounds {
        val lo = min ?: DEFAULT_MIN
        val hi = max ?: DEFAULT_MAX
        return if (lo <= hi) Bounds(lo, hi) else Bounds(hi, lo)
    }

    /** How many buttons the scale draws. */
    @JvmStatic
    fun count(min: Int?, max: Int?): Int {
        val b = bounds(min, max)
        return b.hi - b.lo + 1
    }

    /** Anchors are hidden when NEITHER is set, so a bare numeric scale reserves no empty space. */
    @JvmStatic
    fun showsAnchors(low: String?, high: String?): Boolean = low != null || high != null
}
