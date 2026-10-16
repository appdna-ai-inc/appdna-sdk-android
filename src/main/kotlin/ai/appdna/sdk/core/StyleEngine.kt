package ai.appdna.sdk.core

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.sin

// MARK: - Config data classes

data class TextStyleConfig(
    val font_family: String? = null,
    val font_size: Double? = null,
    val font_weight: Int? = null,
    val color: String? = null,
    val alignment: String? = null,
    val line_height: Double? = null,
    val letter_spacing: Double? = null,
    val opacity: Double? = null,
    /**
     * iOS canonical `text_transform` from
     * applyTextStyleDecorations. Values: `"uppercase"`, `"lowercase"`.
     * Applied at the Compose Text composition site (cannot live inside
     * the TextStyle copy because Compose has no text-case modifier on
     * TextStyle itself; consumers must read `style.applyTransform(...)`
     * on the rendered string instead).
     */
    val text_transform: String? = null,
)

/**
 * Apply iOS-style `text_transform` to a raw string.
 * Mirrors SwiftUI `.textCase(.uppercase|.lowercase)` from
 * `Core/StyleEngine.swift:194-198`. Returns the input untouched when
 * the field is null/unrecognised.
 */
fun TextStyleConfig?.applyTransform(input: String): String = when (this?.text_transform) {
    "uppercase" -> input.uppercase()
    "lowercase" -> input.lowercase()
    else -> input
}

data class BackgroundStyleConfig(
    val type: String? = null,
    val color: String? = null,
    val gradient: GradientConfig? = null,
    val image_url: String? = null,
    val image_fit: String? = null,
    val overlay: String? = null,
    // Match iOS BackgroundStyle (StyleEngine.swift:343-358 +
    // 361-392). `overlay_opacity` lets authors dim the underlying media; when
    // unset the SDK applies a 0.4 default for pure-black/white #000000/#FFFFFF
    // hexes (editor default) so the image/lottie remains visible. `lottie_url`
    // + `animation_loop` enable a full-screen Lottie background type.
    val overlay_opacity: Double? = null,
    val lottie_url: String? = null,
    val animation_loop: Boolean? = null,
    // Match iOS BackgroundStyle "rive" branch
    // (StyleEngine.swift:393-425). Rive step backgrounds rendered transparent
    // on Android because the parser dropped the field.
    val rive_url: String? = null,
)

data class GradientConfig(
    val type: String? = null,
    val angle: Double? = null,
    val stops: List<GradientStopConfig>? = null,
)

data class GradientStopConfig(
    val color: String,
    val position: Double,
)

data class BorderStyleConfig(
    val width: Double? = null,
    val color: String? = null,
    val style: String? = null,
    val radius: Double? = null,
    val radius_top_left: Double? = null,
    val radius_top_right: Double? = null,
    val radius_bottom_left: Double? = null,
    val radius_bottom_right: Double? = null,
)

data class ShadowStyleConfig(
    val x: Double? = null,
    val y: Double? = null,
    val blur: Double? = null,
    val spread: Double? = null,
    val color: String? = null,
)

data class SpacingConfig(
    val top: Double? = null,
    val right: Double? = null,
    val bottom: Double? = null,
    val left: Double? = null,
)

data class ElementStyleConfig(
    val background: BackgroundStyleConfig? = null,
    val border: BorderStyleConfig? = null,
    val shadow: ShadowStyleConfig? = null,
    val padding: SpacingConfig? = null,
    val corner_radius: Double? = null,
    val opacity: Double? = null,
    val text_style: TextStyleConfig? = null,
)

data class SectionStyleConfig(
    val container: ElementStyleConfig? = null,
    val elements: Map<String, ElementStyleConfig>? = null,
)

data class AnimationConfig(
    val entry_animation: String? = null,
    val entry_duration_ms: Int? = null,
    val section_stagger: String? = null,
    val section_stagger_delay_ms: Int? = null,
    val cta_animation: String? = null,
    val plan_selection_animation: String? = null,
    val dismiss_animation: String? = null,
)

// MARK: - Style engine

object StyleEngine {

    /**
     * Apply TextStyleConfig to a Compose TextStyle.
     */
    fun applyTextStyle(base: TextStyle, config: TextStyleConfig?): TextStyle {
        if (config == null) return base
        // Preserve base's per-field defaults instead
        // of clobbering with hardcoded 16sp / Normal / Start / 1.4 *
        // 16. iOS resolves each field independently
        // (ContentBlockRendererView.swift:274-275 / 944-959), so a
        // partial `style: { color: "#000" }` keeps h1's 28sp/Bold
        // and rich_text legal's 12sp/Center defaults instead of
        // collapsing them to 16sp/Normal/Start.
        // Apply `opacity` even when `color` is null.
        // iOS adds `.opacity(s.opacity ?? 1.0)` as an always-on view
        // modifier; Android previously only multiplied opacity into
        // resolvedColor.alpha when config.color was set, so
        // `{ opacity: 0.5 }` payloads with no color rendered fully
        // opaque. Now: if a color is authored, multiply opacity into
        // its alpha; otherwise multiply opacity into the base color's
        // alpha.
        val opacityFactor = (config.opacity ?: 1.0).toFloat()
        val resolvedColor: Color = config.color?.let { c ->
            val parsed = parseColor(c)
            parsed.copy(alpha = parsed.alpha * opacityFactor)
        } ?: if (opacityFactor < 1f) base.color.copy(alpha = base.color.alpha * opacityFactor) else base.color
        val resolvedAlign: TextAlign = when (config.alignment) {
            "center" -> TextAlign.Center
            "right" -> TextAlign.End
            "left" -> TextAlign.Start
            "justify" -> TextAlign.Justify
            null -> base.textAlign ?: TextAlign.Unspecified
            else -> base.textAlign ?: TextAlign.Unspecified
        }
        val resolvedFamily = config.font_family?.let { FontResolver.resolve(it) } ?: base.fontFamily
        val resolvedSize = config.font_size?.sp ?: base.fontSize
        val resolvedWeight = config.font_weight?.let { FontResolver.fontWeight(it) } ?: base.fontWeight
        val resolvedLineHeight = config.line_height
            ?.let { ((it) * (config.font_size ?: base.fontSize.value.toDouble())).sp }
            ?: base.lineHeight
        // letter_spacing in `.em` not `.sp`.
        // iOS `.kerning(CGFloat(spacing))` is in POINTS (no Dynamic Type
        // scaling). Compose `.sp` ramps with `fontScale`, so 130% font-scale
        // user sees letter_spacing balloon to 130% of authored value while
        // iOS holds steady. Converting to em via `letter_spacing / font_size`
        // gives a font-size-relative tracking ratio that's stable across
        // accessibility scaling (em is a fraction of the text's own font
        // size, not the system scale factor).
        val resolvedLetterSpacing = config.letter_spacing?.let { spacing ->
            val baseFontPx = (config.font_size ?: base.fontSize.value.toDouble()).coerceAtLeast(1.0)
            (spacing / baseFontPx).em
        } ?: base.letterSpacing
        return base.copy(
            fontFamily = resolvedFamily,
            fontSize = resolvedSize,
            fontWeight = resolvedWeight,
            color = resolvedColor,
            textAlign = resolvedAlign,
            lineHeight = resolvedLineHeight,
            letterSpacing = resolvedLetterSpacing,
        )
    }

    /**
     * Apply ElementStyleConfig as Modifier extensions.
     */
    fun Modifier.applyContainerStyle(style: ElementStyleConfig?): Modifier {
        if (style == null) return this

        // Per-corner radius support
        val shape = resolveShape(style)

        var mod = this
        // Padding
        style.padding?.let { p ->
            mod = mod.padding(
                start = (p.left ?: 0.0).dp,
                top = (p.top ?: 0.0).dp,
                end = (p.right ?: 0.0).dp,
                bottom = (p.bottom ?: 0.0).dp,
            )
        }
        // Shadow — #886. BEFORE the clip below, because a clip earlier in the chain cuts away
        // everything a later draw puts outside the shape, which is exactly where an offset,
        // blurred shadow lives. `applyBlockStyle` has always ordered it this way.
        mod = mod.appdnaShadow(resolveShadow(style.shadow), shape)
        // Background
        style.background?.let { bg ->
            mod = when (bg.type) {
                "color" -> mod.background(parseColor(bg.color ?: "#FFFFFF"), shape)
                "gradient" -> {
                    val stops = bg.gradient?.stops
                    if (stops != null && stops.size >= 2) {
                        val colors = stops.map { parseColor(it.color) }
                        val brush = when (bg.gradient.type) {
                            "radial" -> Brush.radialGradient(colors)
                            else -> {
                                // Span the FULL bounds (the fixed *1000f offset made the
                                // gradient finish at ~1000px and show flat end-colour below). Compose
                                // vertical/horizontal gradients auto-span the draw bounds.
                                val angle = ((bg.gradient.angle ?: 180.0) % 360 + 360) % 360
                                when (angle) {
                                    180.0 -> Brush.verticalGradient(colors)
                                    0.0 -> Brush.verticalGradient(colors.reversed())
                                    90.0 -> Brush.horizontalGradient(colors)
                                    270.0 -> Brush.horizontalGradient(colors.reversed())
                                    // Non-cardinal angles previously used FIXED 4000px
                                    // offsets, so a small element (button/card/badge) sampled only the
                                    // gradient's start → rendered near-flat, while iOS fills the element.
                                    // AngledGradientBrush reads the ACTUAL draw size and applies iOS's exact
                                    // per-axis UnitPoint math (0.5±dx/2, 0.5±dy/2) × size. A NAMED
                                    // class with value equals/hashCode (not an anonymous object) so identical
                                    // gradients compare equal across recompositions and Modifier.background
                                    // can skip re-creating the shader.
                                    else -> AngledGradientBrush(angle, colors)
                                }
                            }
                        }
                        mod.background(brush, shape)
                    } else mod
                }
                "image" -> {
                    // Image backgrounds are handled at the view level (AsyncImage),
                    // but we apply the overlay color if present
                    bg.overlay?.let { overlay ->
                        mod.background(parseColor(overlay), shape)
                    } ?: mod
                }
                else -> mod
            }
        }
        // Clip
        mod = mod.clip(shape)
        // Border
        style.border?.let { b ->
            if ((b.width ?: 0.0) > 0) {
                mod = mod.border(
                    (b.width ?: 0.0).dp,
                    parseColor(b.color ?: "transparent"),
                    shape,
                )
            }
        }
        // Opacity
        style.opacity?.let { alpha ->
            mod = mod.alpha(alpha.toFloat())
        }
        return mod
    }

    /**
     * Universal block-container styling, ported from
     * iOS `ContentBlockTypes.swift:744-786 applyBlockContainerStyle`.
     *
     * iOS applies this modifier to EVERY ContentBlock at
     * `ContentBlockRendererView.swift:67` (.applyBlockContainerStyle(block))
     * so any block type — text/image/row/input/etc. — can opt into
     * a container background, blur effect, border, and corner radius
     * via `field_config` keys:
     *   - `blur_background: Bool`   → frosted-glass effect
     *   - `container_bg_color: String`
     *   - `container_opacity: Double` (preferred) or `background_opacity` (legacy)
     *   - `container_border_width: Double`
     *   - `container_border_color: String`
     *   - `container_corner_radius: Double`
     *
     * Previously Android only honoured these on the `row` block, so any
     * authored container styling on a non-row block silently dropped.
     *
     * Opt-in: if blur is off, container_bg_color is empty/white/transparent,
     * AND no visible border, this returns `this` unchanged. Same opt-in
     * gate as iOS to avoid phantom backgrounds from stale color-picker
     * defaults.
     *
     * Blur caveat: Compose's `Modifier.blur(...)` (API 31+) blurs the
     * MODIFIER's content, not the content BEHIND it the way iOS
     * `.ultraThinMaterial` does. Without a HazeChild library we
     * approximate frosted-glass with a translucent white layer; the
     * effect on solid backgrounds is similar enough for parity.
     */
    @androidx.compose.runtime.Composable
    fun Modifier.applyBlockContainerStyle(fieldConfig: Map<String, Any?>?): Modifier {
        if (fieldConfig == null) return this
        val useBlur = (fieldConfig["blur_background"] as? Boolean) == true
        val containerBg = fieldConfig["container_bg_color"] as? String
        val hasBg = containerBg != null && containerBg.isNotEmpty()
            && !containerBg.equals("#ffffff", true)
            && !containerBg.equals("transparent", true)
        val containerBorderW = (fieldConfig["container_border_width"] as? Number)
            ?.toDouble()?.takeIf { it > 0 }?.dp
        val containerBorderC = fieldConfig["container_border_color"] as? String
        val hasBorder = containerBorderW != null && !containerBorderC.isNullOrEmpty()
        val bgOpacity = ((fieldConfig["container_opacity"] as? Number)?.toDouble()
            ?: (fieldConfig["background_opacity"] as? Number)?.toDouble()
            ?: 1.0).toFloat()
        val cornerR = ((fieldConfig["container_corner_radius"] as? Number)?.toDouble() ?: 0.0).dp

        if (!useBlur && !hasBg && !hasBorder) return this
        val shape = RoundedCornerShape(cornerR)
        var mod: Modifier = this
        if (useBlur) {
            // Approximation: translucent white veneer. Compose
            // Modifier.blur() blurs the wrapped content, not what's
            // behind it, so it can't replicate iOS .ultraThinMaterial.
            mod = mod.background(Color.White.copy(alpha = 0.3f), shape)
        }
        if (hasBg && containerBg != null) {
            val bgColor = parseColor(containerBg).copy(alpha = bgOpacity)
            mod = mod.background(bgColor, shape)
        }
        mod = mod.clip(shape)
        if (hasBorder && containerBorderW != null && containerBorderC != null) {
            mod = mod.border(containerBorderW, parseColor(containerBorderC), shape)
        }
        return mod
    }

    /**
     * Resolve per-corner or uniform corner radius into a Shape.
     */
    private fun resolveShape(style: ElementStyleConfig): RoundedCornerShape {
        val border = style.border
        val hasPerCorner = border != null && listOf(
            border.radius_top_left, border.radius_top_right,
            border.radius_bottom_left, border.radius_bottom_right
        ).any { it != null }

        return if (hasPerCorner) {
            RoundedCornerShape(
                topStart = (border?.radius_top_left ?: style.corner_radius ?: 0.0).dp,
                topEnd = (border?.radius_top_right ?: style.corner_radius ?: 0.0).dp,
                bottomStart = (border?.radius_bottom_left ?: style.corner_radius ?: 0.0).dp,
                bottomEnd = (border?.radius_bottom_right ?: style.corner_radius ?: 0.0).dp,
            )
        } else {
            RoundedCornerShape((style.corner_radius ?: 0.0).dp)
        }
    }

    /**
     * #886 — draw an authored shadow: colour, blur AND offset, following `shape` exactly.
     *
     * `Modifier.shadow` takes an ELEVATION and draws Compose's own ambient/spot shadow. It has no
     * offset at all, and its colour arguments only tint an elevation curve — so an authored soft
     * grey shadow and an authored hard black one came out identical, an authored offset was
     * dropped, and a shadow authored at zero alpha still drew. iOS honours all four values through
     * `.shadow(color:radius:x:y:)`: one authored style, two different screens, 508 occurrences
     * across 30 production flows.
     *
     * `setShadowLayer` on a transparent paint reproduces SwiftUI's four parameters exactly. The
     * outline comes from the SAME `shape` the background is clipped to, so a per-corner radius
     * casts a per-corner shadow instead of the single-radius approximation a corner value would
     * give. ONE implementation, called by both `applyContainerStyle` and `applyBlockStyle`.
     *
     * `spread` is accepted and IGNORED, deliberately: SwiftUI has no spread, so honouring it here
     * would re-create the divergence this removes.
     */
    fun Modifier.appdnaShadow(resolved: ResolvedShadow?, shape: Shape): Modifier {
        if (resolved == null) return this
        return this.drawBehind {
            val blurPx = resolved.radiusDp.dp.toPx()
            // A zero-radius shadow layer draws nothing on some devices; the smallest positive
            // radius keeps a hard-edged shadow visible rather than silently absent.
            val safeBlur = if (blurPx <= 0f) 0.01f else blurPx
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.TRANSPARENT
                setShadowLayer(safeBlur, resolved.dxDp.dp.toPx(), resolved.dyDp.dp.toPx(), resolved.colorArgb)
            }
            val path = Path()
            when (val outline = shape.createOutline(size, layoutDirection, this)) {
                is Outline.Rectangle -> path.addRect(outline.rect)
                is Outline.Rounded -> path.addRoundRect(outline.roundRect)
                is Outline.Generic -> path.addPath(outline.path)
            }
            drawIntoCanvas { canvas -> canvas.nativeCanvas.drawPath(path.asAndroidPath(), paint) }
        }
    }

    /**
     * #886 — the four values a shadow really draws with, or null when it draws nothing.
     *
     * A SEAM rather than inline arithmetic, so a shared fixture can drive the REAL resolution on
     * both platforms. Android used to pass only `blur` to `Modifier.shadow` as an elevation — no
     * colour, no offset — so an authored soft grey shadow and a hard black one came out identical
     * and a zero-alpha shadow still drew, while iOS honoured all four. 508 occurrences across 30
     * production flows were affected.
     *
     * `spread` is accepted and IGNORED on both platforms, deliberately: SwiftUI has no spread, and
     * honouring it on one side would re-create the divergence this removes.
     *
     * The radius is `blur / 2`, matching iOS's `CGFloat(s.shadow?.blur ?? 0) / 2`.
     */
    data class ResolvedShadow(
        val colorArgb: Int,
        val radiusDp: Double,
        val dxDp: Double,
        val dyDp: Double,
    )

    fun resolveShadow(shadow: ShadowStyleConfig?): ResolvedShadow? {
        val s = shadow ?: return null
        val c = parseColor(s.color ?: "transparent")
        if (c.alpha <= 0f) return null
        return ResolvedShadow(
            colorArgb = c.toArgb(),
            radiusDp = (s.blur ?: 0.0) / 2,
            dxDp = s.x ?: 0.0,
            dyDp = s.y ?: 0.0,
        )
    }

    /**
     * Parse a hex color string to Compose Color.
     *
     * Byte order matches iOS `Color(hex:)` in `Paywalls/PaywallHelperViews.swift`:
     *   - 6-char  → RGB,  alpha = 0xFF
     *   - 8-char  → RGBA  (R, G, B, A — NOT Compose's default ARGB long form)
     *   - "transparent" / "clear" → Color.Transparent
     *   - any other / parse failure → Color.Transparent (matches iOS fallback `(0, 0, 0, 0)`)
     *
     * Fixes the prior `Color(colorLong)` ARGB interpretation that
     * rotated channels for every console-saved RGBA color.
     */
    fun parseColor(hex: String): Color {
        return try {
            val lowered = hex.lowercase().trim()
            if (lowered == "transparent" || lowered == "clear") return Color.Transparent

            // Match iOS PaywallHelperViews.swift
            // :81-87 which only handles `count == 6 || count == 8`. Anything
            // else returns transparent. Android previously expanded 3-char
            // (`#ABC` → `#AABBCC`) and 4-char (`#ABCD` → `#AABBCCDD`) shorthand,
            // so a hand-edited config or future shortcut emitted visible color
            // on Android but transparent on iOS for the same payload.
            val cleaned = lowered.removePrefix("#")
            val expanded = when (cleaned.length) {
                6, 8 -> cleaned
                else -> return Color.Transparent
            }

            val r = expanded.substring(0, 2).toInt(16)
            val g = expanded.substring(2, 4).toInt(16)
            val b = expanded.substring(4, 6).toInt(16)
            val a = if (expanded.length == 8) expanded.substring(6, 8).toInt(16) else 255
            Color(r, g, b, a)
        } catch (_: Exception) {
            Color.Transparent
        }
    }
}

// MARK: - Localization helper

object LocalizationEngine {
    fun resolve(
        key: String,
        localizations: Map<String, Map<String, String>>?,
        defaultLocale: String?,
        fallback: String,
    ): String {
        val deviceLocale = java.util.Locale.getDefault().language
        localizations?.get(deviceLocale)?.get(key)?.let { return it }
        defaultLocale?.let { localizations?.get(it)?.get(key)?.let { v -> return v } }
        return fallback
    }
}

/**
 * A linear-gradient brush for a non-cardinal angle that spans the ACTUAL element bounds, applying iOS's
 * exact per-axis UnitPoint math: start = ((0.5 - dx/2)·W, (0.5 - dy/2)·H), end = ((0.5 + dx/2)·W,
 * (0.5 + dy/2)·H), where dx = sin(angle), dy = -cos(angle). SwiftUI maps a `UnitPoint(u,v)` to pixel
 * `(u·W, v·H)`, so this reproduces iOS byte-for-byte at every element size (the old code used a fixed
 * 4000px in place of W/H, collapsing to near-flat on small elements). A named class with value
 * equals/hashCode (not an anonymous object) so recompositions with identical params skip re-creation.
 */
private class AngledGradientBrush(
    private val angleDeg: Double,
    private val colors: List<Color>,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val rads = Math.toRadians(angleDeg)
        val dx = sin(rads).toFloat()
        val dy = -cos(rads).toFloat()
        return LinearGradientShader(
            from = Offset((0.5f - dx / 2f) * size.width, (0.5f - dy / 2f) * size.height),
            to = Offset((0.5f + dx / 2f) * size.width, (0.5f + dy / 2f) * size.height),
            colors = colors,
            colorStops = null,
            tileMode = TileMode.Clamp,
        )
    }

    override fun equals(other: Any?): Boolean =
        other is AngledGradientBrush && other.angleDeg == angleDeg && other.colors == colors

    override fun hashCode(): Int = 31 * angleDeg.hashCode() + colors.hashCode()
}
