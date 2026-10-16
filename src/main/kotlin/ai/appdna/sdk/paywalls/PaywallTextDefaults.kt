package ai.appdna.sdk.paywalls

import androidx.compose.ui.graphics.Color
import ai.appdna.sdk.core.StyleEngine
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * #891 — a text colour nobody authored, resolved from the BACKGROUND rather than from the theme.
 *
 * A paywall's defaults assumed a background instead of reading one. This side hard-coded
 * `Color.White` on the plan tiles and took `MaterialTheme.colorScheme.onSurface` for the header,
 * while iOS used `.primary` — a SYSTEM colour that is black in light appearance. So a paywall with
 * a dark background drew black text on iOS for anyone whose phone is set to light, and a paywall
 * with a LIGHT background drew white text here. One published document, two different screens, and
 * on one of them the words were gone.
 *
 * The rule is the server's, so the three agree: try `#111827`, then `#FFFFFF`, take the first that
 * passes WCAG AA against the background, and if neither does, take whichever is further away.
 * `src/lib/ui/editors/design-controls/readable-text.ts` is the same ladder minus the brand palette,
 * which an SDK does not have.
 *
 * Driven by the `paywall_default_text_colour` shared fixture on both platforms.
 */
object PaywallTextDefaults {
    const val DARK = "#111827"
    const val LIGHT = "#FFFFFF"

    /** The hex a default text colour should take on [background], or [LIGHT] when it cannot parse. */
    fun readableHex(background: String?): String {
        val bgL = background?.let { luminance(it) } ?: return LIGHT
        val darkRatio = contrast(luminance(DARK)!!, bgL)
        val lightRatio = contrast(luminance(LIGHT)!!, bgL)
        if (darkRatio >= 4.5) return DARK
        if (lightRatio >= 4.5) return LIGHT
        return if (lightRatio >= darkRatio) LIGHT else DARK
    }

    /** The same answer as a Compose [Color], for a renderer to apply directly. */
    fun readable(background: String?): Color = StyleEngine.parseColor(readableHex(background))

    /** A translucent tile over a page: what the eye actually sees, so the text is judged on THAT. */
    fun composite(foreground: String?, over: String?, alpha: Double): String? {
        val f = foreground?.let { channels(it) } ?: return over
        val b = over?.let { channels(it) } ?: return over
        fun mix(a: Double, c: Double) = a * alpha + c * (1 - alpha)
        return String.format(
            "#%02X%02X%02X",
            (mix(f[0], b[0]) * 255).toInt(),
            (mix(f[1], b[1]) * 255).toInt(),
            (mix(f[2], b[2]) * 255).toInt(),
        )
    }

    // ── WCAG ──

    private fun channels(hex: String): DoubleArray? {
        var h = hex.removePrefix("#")
        if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
        if (h.length < 6) return null
        val v = h.substring(0, 6).toLongOrNull(16) ?: return null
        return doubleArrayOf(
            ((v shr 16) and 0xFF) / 255.0,
            ((v shr 8) and 0xFF) / 255.0,
            (v and 0xFF) / 255.0,
        )
    }

    private fun luminance(hex: String): Double? {
        val c = channels(hex) ?: return null
        fun lin(x: Double) = if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(c[0]) + 0.7152 * lin(c[1]) + 0.0722 * lin(c[2])
    }

    private fun contrast(a: Double, b: Double): Double {
        val hi = max(a, b)
        val lo = min(a, b)
        return (hi + 0.05) / (lo + 0.05)
    }
}
