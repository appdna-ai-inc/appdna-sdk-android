package ai.appdna.sdk.onboarding

/**
 * One background on a warning banner.
 *
 * The banner draws its own card, and the Design panel's background, border and corner radius land
 * in `block_style`, which every block wraps itself in. Drawing both put a tinted card inside a
 * filled, rounded wrapper. `block_style` now styles the card, and the banner's own settings win
 * where both are set. With no `block_style` this draws exactly what the banner drew before.
 *
 * Twin of iOS `WarningBannerChrome` and the console's `resolveWarningBannerChrome`; shared fixture
 * `dto_parsing/warning_banner_chrome_resolution`.
 */
internal object WarningBannerChrome {
    enum class CardFill { OWN, TINT, NONE }

    data class Resolved(
        val radius: Double,
        val cardFill: CardFill,
        val cardBorder: Boolean,
        val wrapperFill: Boolean,
        val wrapperBorder: Boolean,
        val wrapper: BlockStyle?,
    )

    fun resolve(block: ContentBlock): Resolved = resolve(
        blockStyle = block.block_style,
        bgColor = block.bg_color,
        cornerRadius = (block.field_config?.get("banner_corner_radius") as? Number)?.toDouble(),
        borderWidth = (block.field_config?.get("banner_border_width") as? Number)?.toDouble(),
    )

    fun resolve(blockStyle: BlockStyle?, bgColor: String?, cornerRadius: Double?, borderWidth: Double?): Resolved {
        val radius = cornerRadius ?: blockStyle?.border_radius ?: 12.0
        val hasOwnFill = !isTransparent(bgColor)
        val styleFill = blockStyle?.background_gradient != null || !isTransparent(blockStyle?.background_color)
        val wrapperFill = !hasOwnFill && styleFill
        val styleBorder = (blockStyle?.border_width ?: 0.0) > 0.0
        val wrapperBorder = borderWidth == null && styleBorder

        var wrapper = blockStyle?.copy(border_radius = radius)
        if (!wrapperFill) wrapper = wrapper?.copy(background_color = null, background_gradient = null)
        if (!wrapperBorder) wrapper = wrapper?.copy(border_width = null, border_color = null, border_style = null)

        return Resolved(
            radius = radius,
            cardFill = if (hasOwnFill) CardFill.OWN else if (wrapperFill) CardFill.NONE else CardFill.TINT,
            cardBorder = !wrapperBorder,
            wrapperFill = wrapperFill,
            wrapperBorder = wrapperBorder,
            wrapper = wrapper,
        )
    }

    private fun isTransparent(color: String?): Boolean {
        val c = color?.lowercase() ?: return true
        return c.isEmpty() || c == "transparent" || c == "#00000000"
    }
}

/**
 * What a standalone `toggle` reads from `field_config`. Each colour falls back to what the toggle
 * drew before, and [action] names the element interaction the host receives when it is switched.
 * Twin of iOS `ToggleSettings`; shared fixture `dto_parsing/toggle_settings_resolution`.
 */
internal data class ToggleSettings(
    val onColor: String?,
    val labelColor: String?,
    val descriptionColor: String?,
    val action: String?,
) {
    companion object {
        fun from(fc: Map<String, Any?>?): ToggleSettings {
            fun nonEmpty(raw: Any?) = (raw as? String)?.takeIf { it.isNotEmpty() }
            return ToggleSettings(
                onColor = nonEmpty(fc?.get("toggle_on_color")),
                labelColor = nonEmpty(fc?.get("toggle_label_color")),
                descriptionColor = nonEmpty(fc?.get("toggle_description_color")),
                action = nonEmpty(fc?.get("toggle_action")),
            )
        }
    }
}
