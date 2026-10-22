package ai.appdna.sdk.paywalls

/**
 * Which visual variant a paywall countdown draws — #980.
 *
 * 🔴 THE CONSOLE OFFERED FOUR AND EVERY PAYWALL DREW ONE. The Variant select persisted
 * `digital` | `circular` | `flip` | `bar` through `CountdownConfigSchema`, the generator wrote one
 * on every countdown it produced, and no renderer on either platform read `variant` — so all four
 * values drew `digital`. #979 removed the control rather than leave a select that did nothing;
 * this is the rule behind it now that both platforms draw all four.
 *
 * 🔴 A SHARED RULE because the FALLBACK is the part that would diverge. An unrecognised value must
 * draw `digital` — what every published paywall drew — and "unrecognised" has to mean the same
 * thing on both platforms, including for an empty string, which is what a cleared control leaves.
 *
 * Mirrors iOS `PaywallCountdownVariant`.
 */
internal object PaywallCountdownVariant {

    /** The four the renderers draw. */
    val supported = listOf("digital", "circular", "flip", "bar")

    /** The default, and the answer for anything off the list. */
    const val FALLBACK = "digital"

    /**
     * Which variant to draw.
     *
     * [timerVariant] is the spelling the ONBOARDING countdown block uses; it is read as tolerance
     * for a section copied between the two surfaces. [variant] is the paywall's own key and wins.
     */
    fun resolve(variant: String?, timerVariant: String? = null): String {
        for (candidate in listOf(variant, timerVariant)) {
            val raw = candidate?.trim() ?: continue
            if (raw in supported) return raw
        }
        return FALLBACK
    }
}
