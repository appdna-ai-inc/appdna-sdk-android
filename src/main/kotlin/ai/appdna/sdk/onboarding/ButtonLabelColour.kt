package ai.appdna.sdk.onboarding

/**
 * WHICH authored colour a button's label draws in, in ONE place both platforms can be held to.
 *
 * 🔴 The two renderers disagreed, and the Kotlin documented the disagreement as if it were parity.
 * On iOS the button's own `text_color` already beat the generic Typography colour, because the
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

    /**
     * The colour the label actually draws in: `text_color ?: style.color ?: the variant's default` — the
     * fill colour for `outline` and `text` buttons, white for a filled one. A Typography `style` without
     * a colour is not a colour; both renderers draw the label in THIS (iOS used to let its style bake
     * `.primary` over the variant default).
     */
    internal fun label(textColor: String?, styleColor: String?, variant: String, fillHex: String): String =
        resolve(textColor, styleColor) ?: if (variant == "outline" || variant == "text") fillHex else "#FFFFFF"
}


/**
 * WHETHER a block's own background reaches the screen edges, and the inset it has to cancel.
 *
 * 🔴 "the shared background behind the buttons never reaches the screen edges, even with
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

    /** The most a side can reach by an authored value, in dp. */
    const val MAX_BLEED: Double = 400.0

    /** How far the background reaches past the block on each side, in dp. */
    data class Insets(val top: Double, val right: Double, val bottom: Double, val left: Double) {
        val isZero: Boolean get() = top == 0.0 && right == 0.0 && bottom == 0.0 && left == 0.0
        companion object { val ZERO = Insets(0.0, 0.0, 0.0, 0.0) }
    }

    /** One side as authored: the screen edge ([Side.Edge]) or a number of dp (0 = not at all). */
    sealed class Side {
        object Edge : Side()
        data class Points(val value: Double) : Side()
    }

    private val sideKeys = mapOf(
        "top" to "background_bleed_top", "bottom" to "background_bleed_bottom",
        "left" to "background_bleed_left", "right" to "background_bleed_right",
    )

    /**
     * One side as authored, or null when it is not authored at all (the caller applies its fallback).
     * Anything that is neither `"edge"` nor a positive number is an AUTHORED 0: an explicit value has
     * to beat `background_full_bleed`, or an author could never take one side back off. Mirrors iOS
     * and `src/lib/onboarding/block-bleed.ts`.
     */
    fun side(raw: Any?): Side? {
        if (raw == null) return null
        if (raw == "edge") return Side.Edge
        val n = when (raw) {
            is Boolean -> Double.NaN
            is Number -> raw.toDouble()
            is String -> raw.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: Double.NaN
            else -> Double.NaN
        }
        if (!n.isFinite() || n <= 0.0) return Side.Points(0.0)
        return Side.Points(minOf(n, MAX_BLEED))
    }

    /** The side the renderer applies, after the `background_full_bleed` fallback for left and right. */
    fun effectiveSide(fieldConfig: Map<String, Any?>?, name: String): Side {
        side(fieldConfig?.get(sideKeys[name]))?.let { return it }
        if ((name == "left" || name == "right") && isOn(fieldConfig?.get("background_full_bleed"))) return Side.Edge
        return Side.Points(0.0)
    }

    /** Whether the top or bottom reaches an edge, so the renderer has to measure where the block sits. */
    fun needsMeasuredEdge(fieldConfig: Map<String, Any?>?): Boolean =
        effectiveSide(fieldConfig, "top") == Side.Edge || effectiveSide(fieldConfig, "bottom") == Side.Edge

    /**
     * How far the background reaches on each side. `"edge"` is the step's horizontal inset on the left
     * and right, and the MEASURED distance from the block's slot to the surface edge on the top and
     * bottom. Pinned by the shared fixture `dto_parsing/block_background_bleed`.
     */
    fun resolve(fieldConfig: Map<String, Any?>?, stepInset: Double, edgeTop: Double, edgeBottom: Double): Insets {
        fun value(name: String, edge: Double): Double = when (val s = effectiveSide(fieldConfig, name)) {
            Side.Edge -> maxOf(0.0, edge)
            is Side.Points -> s.value
        }
        return Insets(
            top = value("top", edgeTop),
            right = value("right", stepInset),
            bottom = value("bottom", edgeBottom),
            left = value("left", stepInset),
        )
    }
}
