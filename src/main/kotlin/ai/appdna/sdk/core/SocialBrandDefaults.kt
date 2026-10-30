package ai.appdna.sdk.core

/**
 * The brand colours a social-login provider falls back to when an author sets none. Mirror of iOS
 * `Core/SocialBrandDefaults.swift`.
 *
 * 🔴 Once the chip rule needed a MEASURABLE background, the table existed twice on each
 * platform: as `Color`s in the renderer and as hexes beside them. Two copies of a constant table
 * is how `#24292E` and `#24292F` ended up one character apart across platforms, and
 * `check:authorability` reads a bare hex in a renderer as a colour an author cannot reach —
 * true of a brand default and useless to say, since the author's control is the provider's own
 * `bg_color` and this list is its fallback.
 *
 * `null` means "no fill" — an outlined or minimal button draws the step's own surface.
 */
object SocialBrandDefaults {

    /** The resolved background for a provider, or `null` when the style has no fill. */
    fun backgroundHex(
        type: String?,
        style: String?,
        blockAccent: String? = null,
        blockBg: String? = null,
        brandAccent: String? = null,
    ): String? {
        if (style == "outlined" || style == "minimal") return null
        return when (type) {
            "apple" -> "#000000"
            "google" -> "#4285F4"
            "facebook" -> "#1877F2"
            "github" -> "#24292F"
            else -> blockAccent?.takeIf { it.isNotEmpty() }
                ?: blockBg?.takeIf { it.isNotEmpty() }
                ?: (brandAccent ?: "#6366F1")
        }
    }
}
