package ai.appdna.sdk.onboarding

/**
 * WHICH mark a warning/info banner draws, and at what size. Mirror of iOS
 * `Onboarding/BannerIconResolution.swift`, and the same function both renderers call.
 *
 * 🔴 Reported against a rebuild of a real app's info banner, which carries the app's own
 * icon. Ten emoji presets and a box to paste any other emoji makes the capability visible
 * and is still not a way to set a brand's mark. So an image can be uploaded, and the
 * precedence has to be identical on both platforms: an uploaded URL wins, an empty one falls back
 * to the authored glyph, and an unauthored glyph falls back to the variant's own icon, which the
 * caller passes in because the variant table lives with the colours.
 *
 * The SIZE is here too, and it is the part that would otherwise drift. 18 is what the glyph has
 * always drawn at and has to stay the default, or every published banner silently restyles; an
 * uploaded PNG at 18dp is unreadable, so the same number has to be settable and apply to both.
 */
object BannerIconResolution {

    /** What the renderer should draw. */
    sealed class Mark {
        /** Nothing — the author hid the icon. */
        object None : Mark()
        /** An uploaded image at this URL. */
        data class Image(val url: String) : Mark()
        /** A text glyph (an emoji, or the variant's default). */
        data class Glyph(val text: String) : Mark()
    }

    /**
     * The size the glyph has always drawn at. Also the default for an uploaded image, so nothing
     * changes until an author sets one.
     */
    const val DEFAULT_SIZE: Double = 18.0

    /**
     * Clamped to the range the console's control offers, so a hand-written document cannot produce
     * a 2000dp icon that pushes the message off the card.
     */
    fun size(authored: Double?): Double {
        if (authored == null || authored <= 0) return DEFAULT_SIZE
        return authored.coerceIn(8.0, 96.0)
    }

    fun mark(hidden: Boolean, iconUrl: String?, glyph: String?, variantDefault: String): Mark {
        if (hidden) return Mark.None
        if (!iconUrl.isNullOrBlank()) return Mark.Image(iconUrl)
        if (!glyph.isNullOrEmpty()) return Mark.Glyph(glyph)
        return Mark.Glyph(variantDefault)
    }
}
