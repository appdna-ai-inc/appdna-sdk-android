package ai.appdna.sdk.paywalls

/**
 * How much of the screen the pinned group may take before it stops being a pinned group.
 *
 * Any section may carry `zone: "bottom"`, which pins it above the CTA. Pin the plans and the
 * CTA — the case it exists for — and the paywall reads better. Pin the header, the features, a
 * testimonial and a comparison table as well and the scrolling body becomes a sliver: the same
 * defect pinning was meant to fix, arriving from the other direction.
 *
 * Two guards existed and both were advice — the console's Zone control warns, and the generator's
 * prompt says "plans + cta and nothing more". Neither is enforcement and neither can see a phone's
 * screen height. Android's renderer even carried a comment describing a cap "PINNED_ZONE_MAX_FRACTION"
 * that existed in no file.
 *
 * 🔴 DEGRADE, NEVER CLIP. Past the fraction, every pinned section except the CTA returns to the
 * body in its authored position. The CTA stays pinned whatever happens: reaching it is the entire
 * point of the zone, and a clipped group that hides the button is the one outcome worse than a
 * scrolling one.
 *
 * The fraction is measured against the VIEWPORT, because the schema cannot know how tall a section
 * renders — a two-line testimonial and a comparison table are the same `zone: "bottom"`.
 */
object PaywallBottomZone {
    /**
     * The share of the viewport the pinned group may occupy.
     *
     * 0.55 leaves a body taller than the pinned group on every phone this renders on, and was
     * chosen by pinning four sections on a Pixel API 34 and looking at the result rather than by
     * arithmetic. Above it the body stops being somewhere you can read and becomes somewhere you
     * can glimpse.
     */
    const val MAX_FRACTION = 0.55

    /**
     * Whether the pinned group is too tall to stay pinned.
     *
     * An unknown viewport (0 or less) keeps everything pinned: a measurement that has not happened
     * yet is not evidence of overflow, and un-pinning on a guess would move the page under the
     * user for one frame.
     */
    fun overflows(pinnedPx: Double, viewportPx: Double): Boolean {
        if (viewportPx <= 0 || pinnedPx <= 0) return false
        return pinnedPx > viewportPx * MAX_FRACTION
    }
}
