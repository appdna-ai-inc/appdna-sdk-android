package ai.appdna.sdk.core

/**
 * WHICH of Facebook's two official marks a Social Login button draws. Mirror of iOS
 * `Core/FacebookMark.swift`, pinned by the shared `facebook_mark_variant` fixture.
 *
 * The provider drew a bold letter "f", and Meta's brand guidelines say "don't use just the 'f' from
 * our logo". The SDK now ships Meta's official logo pack files, unmodified:
 *
 * - PRIMARY: the Facebook Blue circle with a white "f", for light surfaces;
 * - SECONDARY: the white circle with a transparent "f", for a Facebook Blue or dark surface.
 *
 * `monochrome_light` → secondary (it IS the white mark). `monochrome_dark` → primary, because Meta
 * allows the logo only in Facebook Blue or white. Outlined / minimal → primary. Filled → secondary
 * when the button's own background is dark (the 0.5 luminance line [GoogleMarkChip] uses); an absent
 * or unreadable background means the default `#1877F2` fill, which is dark.
 */
object FacebookMark {
    enum class Variant(val key: String) { PRIMARY("primary"), SECONDARY("secondary") }

    /** Drawn size in dp. Meta's digital minimum is 16px wide. */
    const val SIZE_DP: Float = 20f
    /** Meta: "at least half a logo distance away from any other content". */
    const val SPACING_DP: Float = SIZE_DP / 2f

    fun variant(buttonStyle: String?, backgroundHex: String?, iconStyle: String?): Variant {
        if (iconStyle == "monochrome_light") return Variant.SECONDARY
        if (iconStyle == "monochrome_dark") return Variant.PRIMARY
        val style = buttonStyle ?: "filled"
        if (style == "outlined" || style == "minimal") return Variant.PRIMARY
        val l = GoogleMarkChip.luminance(backgroundHex) ?: return Variant.SECONDARY
        return if (l < GoogleMarkChip.LIGHT_ENOUGH) Variant.SECONDARY else Variant.PRIMARY
    }
}
