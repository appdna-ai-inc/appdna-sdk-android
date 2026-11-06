package ai.appdna.sdk.onboarding

/**
 * What a block's container style (`field_config.container_*`, `blur_background`) draws, or null when it draws
 * nothing. Shared with iOS (`BlockContainerStyle`) and driven by the `block_container_style` fixture.
 *
 * Opt-in: a fill only for a visible `container_bg_color` (not empty, `#ffffff` or `transparent`), a border only
 * with both a positive width and a colour. `container_opacity` wins over `background_opacity`; both default to 1.
 * It applies to every block, nested ones included.
 */
internal object BlockContainerStyle {
    data class Resolved(
        val fillHex: String?,
        val opacity: Double,
        val blur: Boolean,
        val borderWidth: Double?,
        val borderHex: String?,
        val cornerRadius: Double,
    )

    fun resolve(cfg: Map<String, Any?>?): Resolved? {
        if (cfg == null) return null
        val blur = (cfg["blur_background"] as? Boolean) == true
        val bg = cfg["container_bg_color"] as? String
        val fill = bg?.takeIf { it.isNotEmpty() && !it.equals("#ffffff", true) && !it.equals("transparent", true) }
        val bw = (cfg["container_border_width"] as? Number)?.toDouble()?.takeIf { it > 0 }
        val bc = (cfg["container_border_color"] as? String)?.takeIf { it.isNotEmpty() }
        val hasBorder = bw != null && bc != null
        if (!blur && fill == null && !hasBorder) return null
        return Resolved(
            fillHex = fill,
            opacity = (cfg["container_opacity"] as? Number)?.toDouble()
                ?: (cfg["background_opacity"] as? Number)?.toDouble() ?: 1.0,
            blur = blur,
            borderWidth = if (hasBorder) bw else null,
            borderHex = if (hasBorder) bc else null,
            cornerRadius = (cfg["container_corner_radius"] as? Number)?.toDouble() ?: 0.0,
        )
    }
}
