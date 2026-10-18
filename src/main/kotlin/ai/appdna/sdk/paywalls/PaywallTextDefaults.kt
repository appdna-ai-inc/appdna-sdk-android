package ai.appdna.sdk.paywalls

import androidx.compose.runtime.compositionLocalOf
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
/**
 * #946 — the page the paywall is drawn on, available to every composable beneath it.
 *
 * Twelve composables resolved their colours by assuming a backdrop, because the alternative was
 * threading one string through twelve signatures — and the two that were measured came back as
 * blank white rectangles on a light page (#938). A CompositionLocal costs each of them one line
 * and no signature at all, which is what makes the sweep possible rather than merely desirable.
 *
 * iOS reads the same value from `\.paywallBackgroundHex` in the environment; this is the Android
 * spelling of it, so a change on either side has an obvious counterpart on the other.
 *
 * `null` means "the document authored no page colour", which `readable` already answers with
 * `#FFFFFF` — the behaviour every one of these composables had before.
 */
internal val LocalPaywallPageHex = compositionLocalOf<String?> { null }

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

    /**
     * #938 — the alpha a DECORATIVE control takes when nobody authored its colour.
     *
     * An inactive segmented pill's border, a carousel's unselected indicator and a pager's
     * unselected dot are all meant to be present without competing. 30% is the figure iOS's plan
     * tile settled on (`Color.gray.opacity(0.3)`), so the two agree.
     */
    const val SUBTLE_ALPHA = 0.3f

    /**
     * #938 — a decorative control's colour, READ OFF THE PAGE instead of assumed.
     *
     * Three of these were `Color.White.copy(alpha = 0.3f)`: white at 30% over a WHITE page is
     * nothing, so an author who set no indicator colour on a light paywall shipped controls that
     * are not there. #926 found the same constant on the unselected plan tile's border and the
     * device frame showed the whole card disappear; iOS had already retired it on its side with
     * the comment *"(was white.opacity(0.15) — invisible on light paywall backgrounds)"*.
     *
     * 🔴 ON A DARK PAGE THIS IS BYTE-IDENTICAL TO THE OLD CONSTANT. `readable` returns `#FFFFFF`
     * for any page dark enough for white to pass AA, so the result is `Color.White` at the same
     * 30% — which is the whole reason this is safe to change. A default that moves pixels on live
     * paywalls would be worse than the inconsistency it fixes; this one moves pixels only where
     * the old value drew nothing.
     */
    fun subtleOnPage(background: String?): Color = readable(background).copy(alpha = SUBTLE_ALPHA)

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
