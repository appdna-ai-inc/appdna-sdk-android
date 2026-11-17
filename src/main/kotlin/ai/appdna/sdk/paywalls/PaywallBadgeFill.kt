package ai.appdna.sdk.paywalls

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import ai.appdna.sdk.core.StyleEngine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * What a plan badge is FILLED with: a solid colour, or a linear gradient.
 *
 * A plan badge took one solid colour (`badge_bg_color`), so a two-tone "Best offer" badge could not be authored.
 * `badge_gradient` is the same shape as the CTA's `cta_gradient` — `{ type, angle, stops: [{ color, position }] }` — so
 * the console reuses its gradient editor. It wins when it has at least two stops with a colour; otherwise the badge
 * keeps `badge_bg_color`, then the brand accent, then `#6366F1`.
 *
 * `angle` is in CSS degrees (90 = left to right, the default; 180 = top to bottom), as the console preview draws it.
 * A stop's `position` is 0–100; a stop without one is spread evenly. The shared fixture `paywall_badge_fill` pins
 * [resolve] here and in iOS `PaywallBadgeFill`.
 */
internal object PaywallBadgeFill {

    data class Resolved(
        /** "solid" or "gradient". */
        val kind: String,
        val colors: List<String>,
        /** 0..1 per colour; one entry (0) for a solid fill. */
        val locations: List<Double>,
        val angle: Double,
    )

    const val FALLBACK = "#6366F1"

    fun resolve(bgColor: String?, gradient: PaywallGradient?, accent: String?): Resolved {
        val stops = (gradient?.stops ?: emptyList()).filter { !it.color.isNullOrBlank() }
        if (stops.size >= 2) {
            val n = (stops.size - 1).toDouble()
            return Resolved(
                kind = "gradient",
                colors = stops.map { it.color!!.trim() },
                locations = stops.mapIndexed { i, s -> ((s.position ?: (i / n * 100)) / 100).coerceIn(0.0, 1.0) },
                angle = gradient?.angle ?: 90.0,
            )
        }
        val solid = listOf(bgColor, accent).mapNotNull { it?.trim() }.firstOrNull { it.isNotEmpty() } ?: FALLBACK
        return Resolved(kind = "solid", colors = listOf(solid), locations = listOf(0.0), angle = 0.0)
    }

    /** The badge's background in its corner shape. */
    fun modifier(r: Resolved, shape: Shape): Modifier {
        if (r.kind != "gradient") return Modifier.background(StyleEngine.parseColor(r.colors[0]), shape)
        val stops = r.colors.zip(r.locations).map { (c, l) -> l.toFloat() to StyleEngine.parseColor(c) }.toTypedArray()
        val rads = r.angle * PI / 180
        val dx = sin(rads).toFloat()
        val dy = (-cos(rads)).toFloat()
        // A brush in unit space, scaled to the badge by drawing: start/end on the box's edges along the angle.
        val brush = object : androidx.compose.ui.graphics.ShaderBrush() {
            override fun createShader(size: androidx.compose.ui.geometry.Size): androidx.compose.ui.graphics.Shader {
                val cx = size.width / 2f
                val cy = size.height / 2f
                // The CSS gradient line length: |w·sin| + |h·cos|, so the end colours reach the corners.
                val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
                return androidx.compose.ui.graphics.LinearGradientShader(
                    from = Offset(cx - dx * half, cy - dy * half),
                    to = Offset(cx + dx * half, cy + dy * half),
                    colors = stops.map { it.second },
                    colorStops = stops.map { it.first },
                )
            }
        }
        return Modifier.background(brush as Brush, shape)
    }
}
