package ai.appdna.sdk.onboarding

/**
 * The ink an unstyled `warning_banner` draws its message and subtitle in.
 *
 * The banner's default fill is its accent at 14% opacity, so what shows through is almost entirely the
 * surface behind it. The message used to default to `#FFFFFF` whatever that surface was: on a light step
 * the amber tint composites to about `#FEF1DD`, and white on it measures 1.11:1 — invisible.
 *
 * The rule, shared with iOS and driven by the `warning_banner_default_ink` fixture: an authored
 * `text_color` wins; else the ink is picked from the banner's own `bg_color` when one is authored (it is
 * opaque, so it is what the text sits on); else from what is behind the banner. A dark surface keeps the
 * white every published banner on a dark step already draws.
 */
internal object WarningBannerInk {
    const val LIGHT_INK_HEX = "#FFFFFF"
    const val DARK_INK_HEX = "#111827"

    internal fun resolve(
        textColor: String?,
        bgColor: String?,
        behind: GoogleMapsAttribution.Backdrop,
        darkAppearance: Boolean,
    ): String {
        textColor?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val bg = bgColor?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() !in setOf("transparent", "clear") }
        val surface = bg?.let { GoogleMapsAttribution.Backdrop.Plain(it) } ?: behind
        return if (GoogleMapsAttribution.needsLightInk(surface, darkAppearance)) LIGHT_INK_HEX else DARK_INK_HEX
    }
}
