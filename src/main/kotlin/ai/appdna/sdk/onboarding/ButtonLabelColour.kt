package ai.appdna.sdk.onboarding

/**
 * WHICH authored colour a button's label draws in, in ONE place both platforms can be held to.
 *
 * 🔴 The two renderers disagreed, and the Kotlin documented the disagreement as if it were parity.
 * #594 made the button's own `text_color` beat the generic Typography colour on iOS, because the
 * "Text" picker sits beside "Background" on the button panel and a control that loses silently is a
 * control that does not exist. Android kept a later
 * `style = effectiveStyle.copy(color = block.style?.color ?: textColor)`, so `style.color` won
 * there — the opposite rule. A button with both set rendered in two different colours on the two
 * platforms, and the console could not tell an author which one they would get.
 *
 * It is a function rather than two inline expressions so a shared fixture can drive the REAL
 * decision on both platforms instead of re-implementing it (CLAUDE.md Rule 25).
 *
 * Returns null when neither is authored, meaning "leave the variant's own content colour alone".
 */
object ButtonLabelColour {

    /**
     * @param textColor the button panel's own "Text" picker (`block.text_color`).
     * @param styleColor the Typography panel's "Color" (`block.style.color`).
     * @return the hex to draw the label in, or null to leave the variant's colour in charge.
     */
    fun resolve(textColor: String?, styleColor: String?): String? {
        // Specificity decides, the rule used everywhere else here: the control that sits ON the
        // element beats the generic one. An EMPTY string is not an authored colour — the console
        // writes "" when a picker is cleared.
        if (!textColor.isNullOrEmpty()) return textColor
        if (!styleColor.isNullOrEmpty()) return styleColor
        return null
    }
}


/**
 * WHETHER a block's own background reaches the screen edges, and the inset it has to cancel.
 *
 * 🔴 #1006 — "the shared background behind the buttons never reaches the screen edges, even with
 * the width set to 100%". It could not: `element_width: 100%` is 100% of the CONTENT COLUMN, and
 * the column is inset by the step's horizontal padding. A background that has to pass that inset is
 * a different thing from a width, which is why it needs its own control rather than a bigger
 * number.
 *
 * 20 is the step's own horizontal content padding and the same value the map's full bleed cancels.
 * Named here so the two platforms and the console preview cannot drift to three different numbers.
 */
object BlockFullBleed {
    /**
     * The step's horizontal content padding, which a full-bleed background has to cancel.
     *
     * 🔴 IT IS RESPONSIVE, and a constant was wrong. The first version used 20 and the iOS device
     * showed a "full bleed" background still inset by about eleven points, because the step layout
     * insets content by `max(24, screenWidth * 0.08)` — 31.4 on a 393pt phone. A bleed that cancels
     * the wrong number does not reach the edge, which is the defect it exists to fix.
     *
     * The step layout reads this too, so the two cannot drift. iOS computes the same thing.
     */
    fun stepHorizontalInset(screenWidthDp: Double): Double = maxOf(24.0, screenWidthDp * 0.08)

    /** `field_config.background_full_bleed`, read the same way on both platforms. */
    fun isOn(value: Any?): Boolean = when (value) {
        is Boolean -> value
        // The console writes a boolean; a string sneaks in through hand-authored config and an
        // import, and "true" meaning false is the kind of silence this codebase keeps paying for.
        is String -> value == "true" || value == "1"
        is Number -> value.toDouble() != 0.0
        else -> false
    }
}
