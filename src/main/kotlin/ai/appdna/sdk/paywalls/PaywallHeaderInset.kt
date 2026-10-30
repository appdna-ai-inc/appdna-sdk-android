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
    return bg.value ?: bg.color
}
