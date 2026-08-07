package ai.appdna.sdk.core

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Resolves platform-native font families from the cross-platform font value stored in config.
 * Only fonts natively available on Android are supported — no custom font downloading.
 */
object FontResolver {

    fun resolve(fontFamily: String?): FontFamily {
        // A `font_family` value may be a hosted custom-font URL (.ttf/.otf). If so,
        // FontLoader downloads + registers it and returns its Compose FontFamily (or we
        // fall back to the default family until the download completes).
        if (FontLoader.isCustomFontURL(fontFamily)) {
            return FontLoader.resolve(fontFamily!!) ?: FontFamily.Default
        }
        return when (fontFamily) {
            // System
            "system", "-apple-system", "BlinkMacSystemFont", "sans-serif", "Roboto" -> FontFamily.Default
            "system-serif", "serif" -> FontFamily.Serif
            "system-mono", "monospace", "SF Mono" -> FontFamily.Monospace
            // Android built-in font variants
            "sans-serif-light" -> FontFamily.Default
            "sans-serif-medium" -> FontFamily.Default
            "sans-serif-condensed" -> FontFamily.Default
            "serif-monospace" -> FontFamily.Monospace
            "casual" -> FontFamily.Cursive
            // Sans-Serif mapped to Android equivalents
            "helvetica-neue", "Helvetica Neue", "avenir", "Avenir",
            "gill-sans", "Gill Sans", "verdana", "Verdana",
            "arial", "Arial", "trebuchet", "Trebuchet MS" -> FontFamily.Default
            "avenir-next", "Avenir Next", "futura", "Futura" -> FontFamily.Default
            // Serif
            "georgia", "Georgia", "times", "Times New Roman",
            "palatino", "Palatino", "baskerville", "Baskerville",
            "didot", "Didot", "bodoni", "Bodoni 72",
            "optima", "Optima" -> FontFamily.Serif
            // Monospace
            "courier-new", "Courier New", "menlo", "Menlo" -> FontFamily.Monospace
            // Display / cursive
            "snell", "Snell Roundhand" -> FontFamily.Cursive
            "chalkboard", "Chalkboard SE", "noteworthy", "Noteworthy" -> FontFamily.Cursive
            "copperplate", "Copperplate" -> FontFamily.Serif
            // Niche fonts → best Android equivalent
            "sf-compact", "SF Compact Text" -> FontFamily.Default
            "cochin", "Cochin", "iowan", "Iowan Old Style" -> FontFamily.Serif
            "courier", "Courier" -> FontFamily.Monospace
            "papyrus", "Papyrus", "marker-felt", "Marker Felt" -> FontFamily.Cursive
            "academy-engraved", "Academy Engraved LET" -> FontFamily.Serif
            else -> FontFamily.Default
        }
    }

    fun fontWeight(weight: Int?): FontWeight {
        return when (weight ?: 400) {
            in 0..199 -> FontWeight.Thin
            in 200..299 -> FontWeight.ExtraLight
            in 300..399 -> FontWeight.Light
            in 400..499 -> FontWeight.Normal
            in 500..599 -> FontWeight.Medium
            in 600..699 -> FontWeight.SemiBold
            in 700..799 -> FontWeight.Bold
            in 800..899 -> FontWeight.ExtraBold
            else -> FontWeight.Black
        }
    }
}
