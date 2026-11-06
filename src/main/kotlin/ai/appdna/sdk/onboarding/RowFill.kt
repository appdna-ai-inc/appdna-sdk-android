package ai.appdna.sdk.onboarding

/**
 * A row's own fill, as the console's Row Background control authors it: `field_config.bg_color` and
 * `field_config.background_opacity`. Shared with iOS and driven by the `row_background_fill` fixture.
 *
 * null means no fill (absent or empty `bg_color`). The opacity defaults to 1, is clamped to 0..1, and
 * MULTIPLIES the colour's own alpha: `#2563EB80` at 0.5 is a quarter-opaque fill on both platforms.
 */
internal object RowFill {
    data class Fill(val hex: String, val opacity: Float)

    fun resolve(cfg: Map<String, Any?>?): Fill? {
        val hex = (cfg?.get("bg_color") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val opacity = ((cfg["background_opacity"] as? Number)?.toFloat() ?: 1f).coerceIn(0f, 1f)
        return Fill(hex, opacity)
    }
}
