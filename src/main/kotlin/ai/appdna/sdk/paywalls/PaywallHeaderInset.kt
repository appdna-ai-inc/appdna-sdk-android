package ai.appdna.sdk.paywalls

/**
 * The dismiss control's footprint, as a value both platforms can be held to.
 *
 * This used to be computed inline on each platform and pinned it with a SOURCE gate. A gate that reads
 * the arithmetic cannot tell you the two renderers agree, only that they contain similar text —
 * and they did not agree: iOS reserved space for a `swipe` dismiss and this side did not, so one
 * authored paywall wrapped its title differently on each platform and nothing said so.
 *
 * One function, driven by the `paywall_header_close_inset` fixture on both platforms.
 *
 * `swipe` and `swipe_down` are the same control under two spellings (the legacy `type` used
 * `swipe`), and it is a capsule at the top CENTRE; `text_link` sits at the bottom. Neither steals
 * width from a centred title, so neither reserves. A delayed reveal reserves from the START, or
 * the title reflows the moment the button appears.
 */
object PaywallHeaderInset {
    /** The horizontal padding a centred header title needs on BOTH sides, in dp. */
    fun resolve(dismiss: PaywallDismiss?): Double {
        val style = dismiss?.style ?: dismiss?.type ?: "x_button"
        val neverDismissable = dismiss?.allowed == false && (dismiss.delay_seconds ?: 0) <= 0
        val reservesTopCorner = !neverDismissable
            && style != "text_link" && style != "swipe" && style != "swipe_down"
        if (!reservesTopCorner) return 0.0
        val glyphSize = (dismiss?.size ?: 16f).toDouble()
        // Mirrors the control itself: `.padding(16.dp)` + `.size((glyphSize * 2).dp)`.
        return 16 + glyphSize * 2
    }

    /**
     * How far below the top of the safe area the close control ENDS, in dp, or 0 when it does not
     * sit in a top corner. A split paywall's trailing pane starts below this, so the first plan is
     * never under the ✕.
     *
     * Not [resolve]'s figure. The control is laid out as a `2 × glyph` box 16dp in from the corner,
     * and its circle is drawn at that size, but it is a Material `IconButton`, which grows its touch
     * target to 48dp CENTRED on that box. The pane starts below the touch target, not only the circle,
     * so a tap on the first plan's corner selects the plan instead of closing the paywall: for the
     * default 16dp glyph 16 + 16 + 24 = 56.
     */
    fun closeControlBottom(dismiss: PaywallDismiss?): Double {
        if (resolve(dismiss) == 0.0) return 0.0
        val box = (dismiss?.size ?: 16f).toDouble() * 2
        return 16 + box / 2 + maxOf(MIN_TOUCH_TARGET_DP, box) / 2
    }

    /** Material's minimum interactive size, which `IconButton` applies to the close control. */
    private const val MIN_TOUCH_TARGET_DP = 48.0
}

/**
 * The page's own background hex, when it is a flat colour.
 *
 * A gradient or an image yields null and the defaults fall back to light-on-dark, which is what
 * this surface has always assumed for those.
 */
internal fun PaywallConfig.pageBackgroundHex(): String? {
    val bg = layout.background ?: return null
    if (bg.type != "color") return null
    // `{"type":"color"}` with no value paints white, so the ink reads white too.
    return bg.value?.takeIf { it.isNotBlank() } ?: bg.color?.takeIf { it.isNotBlank() } ?: "#FFFFFF"
}

/**
 * The config with the page's real colour filled in when the author set no background at all.
 *
 * With no type the page paints the theme's background ([PaywallBackground]'s `else`), so [pageBackgroundHex]
 * had nothing to read and every default answered white: a white header on a white page in Light. The default
 * surface is the system's, as on iOS and in the console preview, so its ink comes from the same place. Only
 * `layout.background` changes, which only [pageBackgroundHex] reads, so the paint is untouched.
 */
internal fun PaywallConfig.withSystemPage(systemHex: String): PaywallConfig {
    if (background != null || layout.background != null) return this
    return copy(layout = layout.copy(background = PaywallBackground(type = "color", value = systemHex)))
}
