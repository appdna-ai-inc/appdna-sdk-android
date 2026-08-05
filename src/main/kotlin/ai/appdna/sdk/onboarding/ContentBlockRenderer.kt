@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package ai.appdna.sdk.onboarding

import androidx.compose.foundation.background
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import ai.appdna.sdk.Log
import ai.appdna.sdk.LogLevel
import ai.appdna.sdk.core.StyleEngine
import ai.appdna.sdk.core.TextStyleConfig
import ai.appdna.sdk.core.applyTransform
import ai.appdna.sdk.core.LottieBlock
import ai.appdna.sdk.core.LottieBlockView
import ai.appdna.sdk.core.RiveBlock
import ai.appdna.sdk.core.RiveBlockView
import ai.appdna.sdk.core.VideoBlock as CoreVideoBlock
import ai.appdna.sdk.core.VideoBlockView as CoreVideoBlockView
import ai.appdna.sdk.core.IconView
import ai.appdna.sdk.core.resolveIcon
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.clickable
// SPEC-419 STEP-2 — interactive EPIC-11 elements (otp keyboard, press-hold, calendar/memory taps).
// NOTE: KeyboardOptions + pointerInput are already imported below (lines ~123/128); do not re-import.
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import kotlinx.coroutines.delay
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
// QA-R16 — Material icons for password show/hide toggle (replaces literal emoji).
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.zIndex
// mutableIntStateOf, derivedStateOf, etc. covered by runtime.* import
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.DisposableEffect
import ai.appdna.sdk.AppDNA
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.spring
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image

// MARK: - Block Style Design Tokens (SPEC-089d §6.1)

/**
 * Per-block styling: background, border, shadow, padding, margin, opacity.
 *
 * SPEC-070-A J.10 — `@Immutable`: every field is a primitive / nullable
 * primitive / nested fully-immutable holder; no iterables to migrate.
 */
@androidx.compose.runtime.Immutable
data class BlockStyle(
    val background_color: String? = null,
    val background_gradient: BlockGradientStyle? = null,
    val border_color: String? = null,
    val border_width: Double? = null,
    val border_style: String? = null,  // solid, dashed, dotted
    val border_radius: Double? = null,
    val shadow: BlockShadowStyle? = null,
    val padding_top: Double? = null,
    val padding_right: Double? = null,
    val padding_bottom: Double? = null,
    val padding_left: Double? = null,
    val margin_top: Double? = null,
    val margin_bottom: Double? = null,
    val margin_left: Double? = null,
    val margin_right: Double? = null,
    val opacity: Double? = null,
)

/** Shadow definition for block_style. */
@androidx.compose.runtime.Immutable
data class BlockShadowStyle(
    val x: Double = 0.0,
    val y: Double = 2.0,
    val blur: Double = 8.0,
    val spread: Double = 0.0,
    // SPEC-401-A R26 — match iOS ContentBlockTypes.swift:60. iOS treats
    // a missing `shadow.color` as transparent (invisible shadow); Android
    // was hardcoding #1A000000 (10% black) which made the same JSON
    // `shadow: { x: 4, y: 4, blur: 12 }` (no color) render visibly on
    // Android but invisibly on iOS.
    val color: String = "transparent",
)

/** Gradient definition for block_style background. */
@androidx.compose.runtime.Immutable
data class BlockGradientStyle(
    val angle: Double = 135.0,
    // SPEC-401-A R26 — match iOS ContentBlockTypes.swift:67-68. iOS
    // gradient endpoint defaults are black (#000000) and white
    // (#FFFFFF) — applied only when console publishes a gradient
    // without start/end. Android was injecting brand indigo→purple
    // (#6366f1 → #a855f7) which made the same JSON `gradient: { angle:
    // 90 }` render as a brand-tinted gradient on Android while iOS
    // showed a black→white gradient.
    val start: String = "#000000",
    val end: String = "#FFFFFF",
)

// MARK: - Block Style Modifier Extension (SPEC-089d §6.1)

/**
 * Applies `block_style` design tokens to any Composable's Modifier.
 * Order: inner padding → background → clip/border → shadow → opacity → outer margin.
 */
fun Modifier.applyBlockStyle(style: BlockStyle?, clipContent: Boolean = true): Modifier {
    if (style == null) return this

    var mod = this

    // Outer margin (top/bottom/left/right)
    val mTop = style.margin_top ?: 0.0
    val mBottom = style.margin_bottom ?: 0.0
    val mLeft = style.margin_left ?: 0.0
    val mRight = style.margin_right ?: 0.0
    if (mTop > 0 || mBottom > 0 || mLeft > 0 || mRight > 0) {
        mod = mod.then(Modifier.padding(top = mTop.dp, bottom = mBottom.dp, start = mLeft.dp, end = mRight.dp))
    }

    // Opacity
    if (style.opacity != null) mod = mod.then(Modifier.alpha(style.opacity.toFloat()))

    // Shadow (must come before clip/background to be visible)
    if (style.shadow != null) {
        val s = style.shadow
        val shape = RoundedCornerShape((style.border_radius ?: 0.0).dp)
        mod = mod.then(
            Modifier.shadow(
                elevation = (s.blur / 2).dp,
                shape = shape,
                ambientColor = StyleEngine.parseColor(s.color),
                spotColor = StyleEngine.parseColor(s.color),
            )
        )
    }

    // Clip shape. `pulsing_avatar` / `star_background` draw pulse rings / stars that intentionally
    // overflow the block box (iOS renders them in an unclipped ZStack); clipping here cut the pulse
    // ring at peak animation scale. `clipContent = false` from those blocks preserves the overflow.
    val shape = RoundedCornerShape((style.border_radius ?: 0.0).dp)
    if (clipContent) mod = mod.then(Modifier.clip(shape))

    // Background (gradient takes precedence over solid color)
    if (style.background_gradient != null) {
        val g = style.background_gradient
        val rads = g.angle * Math.PI / 180.0
        val startX = (0.5 - sin(rads) / 2).toFloat()
        val startY = (0.5 + cos(rads) / 2).toFloat()
        val endX = (0.5 + sin(rads) / 2).toFloat()
        val endY = (0.5 - cos(rads) / 2).toFloat()
        mod = mod.then(
            Modifier.background(
                Brush.linearGradient(
                    colors = listOf(
                        StyleEngine.parseColor(g.start),
                        StyleEngine.parseColor(g.end),
                    ),
                    start = androidx.compose.ui.geometry.Offset(startX * 1000f, startY * 1000f),
                    end = androidx.compose.ui.geometry.Offset(endX * 1000f, endY * 1000f),
                ),
                shape,
            )
        )
    } else if (style.background_color != null) {
        mod = mod.then(Modifier.background(StyleEngine.parseColor(style.background_color), shape))
    }

    // Border
    if (style.border_width != null && style.border_width > 0) {
        val borderColor = StyleEngine.parseColor(style.border_color ?: "#000000")
        val borderWidthDp = style.border_width.dp
        val cornerRadiusVal = (style.border_radius ?: 0.0).toFloat()
        // SPEC-401-A R26 — match iOS ContentBlockTypes.swift:132-141 which
        // implements dashed `[8, 4]` and dotted `[2, 4]` strokes via
        // SwiftUI `StrokeStyle`. Android previously rendered all variants
        // as solid via `Modifier.border` because Compose's border modifier
        // doesn't natively support dash patterns. Now uses `drawBehind`
        // with `PathEffect.dashPathEffect` for dashed/dotted; keeps the
        // existing `Modifier.border` for the solid case (faster path).
        mod = when (style.border_style?.lowercase()) {
            "dashed", "dotted" -> {
                val pattern = if (style.border_style.lowercase() == "dotted") {
                    floatArrayOf(2f, 4f)
                } else {
                    floatArrayOf(8f, 4f)
                }
                mod.then(
                    Modifier.drawBehind {
                        drawRoundRect(
                            color = borderColor,
                            size = size,
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                cornerRadiusVal * density,
                                cornerRadiusVal * density,
                            ),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = borderWidthDp.toPx(),
                                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                    floatArrayOf(pattern[0] * density, pattern[1] * density),
                                    0f,
                                ),
                            ),
                        )
                    }
                )
            }
            else -> mod.then(Modifier.border(borderWidthDp, borderColor, shape))
        }
    }

    // Inner padding
    mod = mod.then(
        Modifier.padding(
            top = (style.padding_top ?: 0.0).dp,
            end = (style.padding_right ?: 0.0).dp,
            bottom = (style.padding_bottom ?: 0.0).dp,
            start = (style.padding_left ?: 0.0).dp,
        )
    )

    return mod
}

// MARK: - 2D Positioning Modifier (SPEC-089d §6.2)

/**
 * Applies vertical/horizontal alignment + offset positioning to a content block.
 */
fun Modifier.applyBlockPosition(
    verticalAlign: String?,
    horizontalAlign: String?,
    verticalOffset: Double?,
    horizontalOffset: Double?,
): Modifier {
    val hasPositioning = verticalAlign != null || horizontalAlign != null
        || verticalOffset != null || horizontalOffset != null
    if (!hasPositioning) return this

    var mod = this

    // SPEC-401-A R65 (Lens A P1 #2) — positive vertical_offset participates
    // in layout via top padding (siblings shift down + scrollable parents
    // can scroll the full content). Negative offset stays as Modifier.offset
    // (no negative padding). Mirrors iOS BlockPositionModifier at
    // ContentBlockTypes.swift:469-498 — comment there: "Positive
    // vertical_offset participates in layout (top padding) so scrollable
    // zones can still scroll the full content. Negative offsets stay as
    // `.offset` since there's no negative padding."
    val yOffset = verticalOffset ?: 0.0
    val xOffset = horizontalOffset ?: 0.0
    val topPaddingDp = if (yOffset > 0.0) yOffset.dp else 0.dp
    val residualYDp = if (yOffset < 0.0) yOffset.dp else 0.dp

    if (topPaddingDp.value > 0f) {
        mod = mod.then(Modifier.padding(top = topPaddingDp))
    }
    if (residualYDp.value != 0f || xOffset != 0.0) {
        mod = mod.then(Modifier.offset(x = xOffset.dp, y = residualYDp))
    }

    return mod
}

/**
 * Maps horizontal/vertical alignment strings to Compose Alignment.
 */
/**
 * SPEC-401-A — parse iOS combined alignment tokens (e.g. `top_left`,
 * `topLeading`, `bottomTrailing`). Returns null when the token isn't
 * recognised so callers can fall back to per-axis tokens.
 */
internal fun parseStackAlignment(token: String?): Alignment? {
    if (token.isNullOrBlank()) return null
    // SPEC-419 — normalize hyphenated editor values (top-left, center-left, bottom-center) to
    // underscores so they map alongside the existing camelCase/underscore tokens.
    return when (token.lowercase().replace("-", "_")) {
        "top_left", "topleft", "topleading", "top_leading" -> Alignment.TopStart
        "top", "topcenter", "top_center" -> Alignment.TopCenter
        "top_right", "topright", "toptrailing", "top_trailing" -> Alignment.TopEnd
        "left", "leading", "centerleading", "center_leading", "center_left" -> Alignment.CenterStart
        "center", "middle", "center_center" -> Alignment.Center
        "right", "trailing", "centertrailing", "center_trailing", "center_right" -> Alignment.CenterEnd
        "bottom_left", "bottomleft", "bottomleading", "bottom_leading" -> Alignment.BottomStart
        "bottom", "bottomcenter", "bottom_center" -> Alignment.BottomCenter
        "bottom_right", "bottomright", "bottomtrailing", "bottom_trailing" -> Alignment.BottomEnd
        else -> null
    }
}

internal fun mapBlockAlignment(horizontal: String?, vertical: String?): Alignment {
    val h = when (horizontal) {
        "left" -> Alignment.Start
        "right" -> Alignment.End
        else -> Alignment.CenterHorizontally
    }
    val v = when (vertical) {
        "top" -> Alignment.Top
        "bottom" -> Alignment.Bottom
        else -> Alignment.CenterVertically
    }
    return when {
        h == Alignment.Start && v == Alignment.Top -> Alignment.TopStart
        h == Alignment.CenterHorizontally && v == Alignment.Top -> Alignment.TopCenter
        h == Alignment.End && v == Alignment.Top -> Alignment.TopEnd
        h == Alignment.Start && v == Alignment.CenterVertically -> Alignment.CenterStart
        h == Alignment.End && v == Alignment.CenterVertically -> Alignment.CenterEnd
        h == Alignment.Start && v == Alignment.Bottom -> Alignment.BottomStart
        h == Alignment.CenterHorizontally && v == Alignment.Bottom -> Alignment.BottomCenter
        h == Alignment.End && v == Alignment.Bottom -> Alignment.BottomEnd
        else -> Alignment.Center
    }
}

// MARK: - Content Block data class

/**
 * SPEC-070-A J.10 — `@Stable`, not `@Immutable`: ContentBlock still carries
 * passthrough JSON Map/Any? fields per J.22 EXCLUDE rules (`lottie_json`,
 * `custom_config`, `field_config`, `icon_ref: Any?`, `bindings`) that we
 * deliberately don't migrate — they're caller-supplied JSON bags consumed by
 * specialized renderers, not Compose-iterated lists.
 *
 * SPEC-070-A J.22 — typed iterable fields (`children`, `items`, `providers`,
 * `timeline_items`, `loading_items`, `field_options`, `pricing_plans`,
 * `columns`, `size_range`) are migrated to ImmutableList<T> below so Compose
 * can short-circuit recompositions when contents are structurally equal.
 */
@androidx.compose.runtime.Stable
data class ContentBlock(
    val id: String,
    val type: String,  // heading, text, image, button, spacer, list, divider, badge, icon, toggle, video, ...
    val text: String? = null,
    val level: Int? = null,
    val image_url: String? = null,
    val alt: String? = null,
    // EPIC-3 — wrap the image in a device frame: "phone" = phone bezel + dynamic-island notch; else none.
    val image_frame: String? = null,
    // SPEC-401-A — `cover` (default) / `contain` / `fit` / `inside`
    // / `none`. iOS toggles `.fill`/`.fit` on this token.
    val image_fit: String? = null,
    val corner_radius: Double? = null,
    val height: Double? = null,
    val variant: String? = null,
    val action: String? = null,
    val action_value: String? = null,
    val bg_color: String? = null,
    val text_color: String? = null,
    val button_corner_radius: Double? = null,
    // Mrozu (Duolingo s20/s22) — sound_button: remote audio clip (mp3/wav/aac)
    // played on tap; `autoplay` (declared below with the video fields) plays it
    // when the block appears. Reuses all button styling fields.
    val audio_url: String? = null,
    val spacer_height: Double? = null,
    // SPEC-070-A J.22 — ImmutableList for Compose stability (list block items).
    val items: kotlinx.collections.immutable.ImmutableList<String>? = null,
    val list_style: String? = null,
    val divider_color: String? = null,
    val divider_thickness: Double? = null,
    val divider_margin_y: Double? = null,
    val badge_text: String? = null,
    val badge_bg_color: String? = null,
    val badge_text_color: String? = null,
    val badge_corner_radius: Double? = null,
    val badge_position: String? = null,        // "top_trailing" (default) | "top_leading" | "bottom_trailing" | "bottom_leading"
    val badge_size: Double? = null,            // Scale factor (default 1.0 — matches native defaults)
    // Pulsing avatar — image shape override (legacy default = circle).
    val image_shape: String? = null,           // "circle" (default) | "square" | "rounded"
    val image_corner_radius: Double? = null,   // Used when image_shape == "rounded" (default 16)
    val icon_emoji: String? = null,
    val icon_size: Double? = null,
    val icon_alignment: String? = null,
    val toggle_label: String? = null,
    val toggle_description: String? = null,
    val toggle_default: Boolean? = null,
    val video_thumbnail_url: String? = null,
    // SPEC-084: per-block text style override
    val style: TextStyleConfig? = null,
    // SPEC-084: video source URL, height, and corner radius
    val video_url: String? = null,
    val video_height: Double? = null,
    val video_corner_radius: Double? = null,
    // SPEC-085: Rich media fields
    val lottie_url: String? = null,
    val lottie_json: Map<String, Any>? = null,
    val lottie_autoplay: Boolean? = null,
    val lottie_loop: Boolean? = null,
    val lottie_speed: Float? = null,
    // SPEC-401-A — extra Lottie controls iOS forwards but Android
    // never declared (and so silently dropped).
    val play_on_scroll: Boolean? = null,
    val play_on_tap: Boolean? = null,
    val color_overrides: Map<String, String>? = null,
    val lottie_width: Double? = null,
    /**
     * SPEC-401-A R10 — iOS canonical Lottie-specific height field
     * (ContentBlockRendererView.swift:599 `block.lottie_height ?? block.height ?? 160`).
     * When both `lottie_height` and a generic `height` are authored,
     * lottie_height takes precedence so the same payload renders the
     * same vertical dimension on both natives.
     */
    val lottie_height: Double? = null,
    val rive_url: String? = null,
    val rive_artboard: String? = null,
    val rive_state_machine: String? = null,
    // SPEC-401-A — iOS canonical Rive field names (`artboard`,
    // `state_machine`) on the top-level ContentBlock plus the inputs
    // and trigger_on_step_complete fields iOS already forwards.
    val artboard: String? = null,
    val state_machine: String? = null,
    val rive_inputs: Map<String, Any>? = null,
    val trigger_on_step_complete: String? = null,
    val icon_ref: Any? = null,  // IconReference map or emoji string
    val video_autoplay: Boolean? = null,
    val video_loop: Boolean? = null,
    val video_muted: Boolean? = null,
    // SPEC-401-A — iOS canonical field names. Console writes both
    // legacy `video_*` and the unprefixed forms; honour both.
    val autoplay: Boolean? = null,
    val loop: Boolean? = null,
    val muted: Boolean? = null,
    val controls: Boolean? = null,
    val inline_playback: Boolean? = null,
    // SPEC-089d §6.1: Per-block style design tokens
    val block_style: BlockStyle? = null,
    // SPEC-089d §6.2: 2D positioning
    val vertical_align: String? = null,
    val horizontal_align: String? = null,
    val vertical_offset: Double? = null,
    val horizontal_offset: Double? = null,
    // SPEC-070-A I.17 — explicit "top" / "center" / "bottom" zone for the
    // ThreeZoneLayout step variant. Falls back to vertical_align when unset
    // so legacy authored content lands in the right zone without re-saving.
    val zone: String? = null,
    // SPEC-089d: page_indicator fields
    val dot_count: Int? = null,
    val active_index: Int? = null,
    val active_color: String? = null,
    val inactive_color: String? = null,
    val dot_size: Double? = null,
    val dot_spacing: Double? = null,
    val active_dot_width: Double? = null,
    // SPEC — page_indicator dot shape: "circle" (default) | "triangle" | "rectangle" | "star"
    val dot_shape: String? = null,
    // SPEC-089d: social_login fields
    // SPEC-070-A J.22 — ImmutableList for Compose stability.
    val providers: kotlinx.collections.immutable.ImmutableList<SocialProvider>? = null,
    val button_style: String? = null,
    val button_height: Double? = null,
    val spacing: Double? = null,
    val show_divider: Boolean? = null,
    val divider_text: String? = null,
    // Social-Login styling v2 — divider placement ("top" | "bottom", default
    // "bottom") and in-button text alignment ("leading" | "center", default
    // "center"). divider_color (declared with the divider block above) tints the
    // separator rules.
    val divider_position: String? = null,
    val button_text_align: String? = null,
    // SPEC-401-A R13 — match iOS ContentBlockTypes.swift:964-965.
    // `with_providers` (default) renders providers in author order;
    // `below_inputs` extracts the email provider, renders it FIRST,
    // then a clear spacer of `email_cta_spacing_below` (px), then
    // the remaining providers below.
    val email_login_placement: String? = null,
    val email_cta_spacing_below: Double? = null,
    // SPEC-089d: countdown_timer fields
    val target_type: String? = null,
    val duration_seconds: Int? = null,
    val target_datetime: String? = null,
    val show_days: Boolean? = null,
    val show_hours: Boolean? = null,
    val show_minutes: Boolean? = null,
    val show_seconds: Boolean? = null,
    val labels: CountdownLabels? = null,
    val on_expire_action: String? = null,
    val expired_text: String? = null,
    val accent_color: String? = null,
    val font_size: Double? = null,
    val alignment: String? = null,
    // SPEC-089d: rating fields
    val field_id: String? = null,
    val max_stars: Int? = null,
    val default_value: Double? = null,
    val star_size: Double? = null,
    val active_rating_color: String? = null,
    val inactive_rating_color: String? = null,
    val allow_half: Boolean? = null,
    val label: String? = null,
    // SPEC-401-A R3 — iOS canonical field names for the rating
    // standalone block. Console writes these; Android only knew the
    // `*_rating_color` form and `default_value`/`label` so authored
    // values silently dropped. Honour both with iOS canon first.
    val filled_color: String? = null,
    val empty_color: String? = null,
    val default_rating: Double? = null,
    val rating_label: String? = null,
    // SPEC-089d: rich_text fields
    val content: String? = null,
    // SPEC-401-A R4 — iOS canonical name `markdown_content`; the
    // console writes this. Previously Android only knew `content` so
    // any console-authored markdown body silently dropped to plain
    // `text` fallback.
    val markdown_content: String? = null,
    val base_style: TextStyleConfig? = null,
    // SPEC-401-A — `legal` variant centres + uses caption font +
    // secondary colour fallback (consent / privacy boilerplate).
    val rich_text_variant: String? = null,
    val link_color: String? = null,
    val max_lines: Int? = null,
    // SPEC-089d: progress_bar fields
    val segment_count: Int? = null,
    val active_segments: Int? = null,
    // SPEC-401-A — iOS canon `filled_segments` field-name parity.
    // Android historically only knew `active_segments`; backend writes
    // both. Honour `filled_segments` first, fall back to `active_segments`.
    val filled_segments: Int? = null,
    val total_segments: Int? = null,
    val progress_value: Double? = null,
    val progress_variant: String? = null,
    val bar_color: String? = null,
    // EPIC-2 — multiple progress colors at once: horizontal gradient across these hex colors.
    val bar_gradient_colors: List<String>? = null,
    val bar_height: Double? = null,
    val fill_color: String? = null,
    val track_color: String? = null,
    val segment_gap: Double? = null,
    val show_label: Boolean? = null,
    val label_style: TextStyleConfig? = null,
    // SPEC-089d: timeline fields
    // SPEC-070-A J.22 — ImmutableList for Compose stability.
    val timeline_items: kotlinx.collections.immutable.ImmutableList<TimelineItem>? = null,
    val line_color: String? = null,
    val completed_color: String? = null,
    val current_color: String? = null,
    val upcoming_color: String? = null,
    val show_line: Boolean? = null,
    val compact: Boolean? = null,
    val title_style: TextStyleConfig? = null,
    val subtitle_style: TextStyleConfig? = null,
    // SPEC-089d: animated_loading fields
    // SPEC-070-A J.22 — ImmutableList for Compose stability.
    val loading_items: kotlinx.collections.immutable.ImmutableList<LoadingItem>? = null,
    val progress_color: String? = null,
    val check_color: String? = null,
    val total_duration_ms: Int? = null,
    val auto_advance: Boolean? = null,
    val show_percentage: Boolean? = null,
    // SPEC-401-A R3 — iOS canonical `loading_variant` (circular, linear,
    // checklist). Android historically only read top-level `variant`.
    val loading_variant: String? = null,
    // EPIC-3 — configurable loading message: independent text + position (above/below) + size + color.
    val loading_text: String? = null,
    val loading_text_position: String? = null,  // "above" | "below" (default "below")
    val loading_text_size: Double? = null,       // sp, default 15
    val loading_text_color: String? = null,
    // EPIC-3 — media_gallery: horizontal row of image tiles.
    val gallery_images: kotlinx.collections.immutable.ImmutableList<String>? = null,
    val gallery_item_width: Double? = null,
    val gallery_item_height: Double? = null,
    val gallery_corner_radius: Double? = null,
    val gallery_spacing: Double? = null,
    val gallery_align: String? = null,  // "start" | "center" | "end" (default "center")
    // Media-gallery v2 (Mrozu QA): gallery_fill = full-width edge-to-edge cover tiles; gallery_autoscroll =
    // continuous loop; gallery_autoscroll_speed = seconds per full cycle (default 20). All default off.
    val gallery_fill: Boolean? = null,
    val gallery_autoscroll: Boolean? = null,
    val gallery_autoscroll_speed: Double? = null,
    // EPIC-4b — section_background reads background_zones + content_arrangement from field_config
    // (ContentBlock has hit the JVM 255-constructor-arg limit; new fields go through field_config).
    // SPEC-089d Phase F: circular_gauge fields
    val gauge_value: Double? = null,
    val max_gauge_value: Double? = null,
    // SPEC-401-A — iOS canonical name `max_value`. Console writes
    // this; Android historically only knew `max_gauge_value` and so
    // any console-authored max was silently dropped to default 100.
    val max_value: Double? = null,
    val gauge_variant: String? = null,
    val gradient_start_color: String? = null,
    val gradient_end_color: String? = null,
    val arrow_color: String? = null,
    val arrow_stroke_width: Double? = null,
    val min_label: String? = null,
    val max_label: String? = null,
    val min_max_font_size: Double? = null,
    val min_max_color: String? = null,
    val percentage_location: String? = null,
    val sublabel: String? = null,
    val stroke_width: Double? = null,
    val label_color: String? = null,
    val label_font_size: Double? = null,
    val animate: Boolean? = null,
    val animation_duration_ms: Int? = null,
    // SPEC-089d Phase F: date_wheel_picker fields
    // SPEC-070-A J.22 — ImmutableList for Compose stability.
    val columns: kotlinx.collections.immutable.ImmutableList<DateWheelColumn>? = null,
    val default_date_value: String? = null,
    val min_date: String? = null,
    val max_date: String? = null,
    val highlight_color: String? = null,
    val haptic_on_scroll: Boolean? = null,
    // SPEC-089d Phase F: stack / row fields (container blocks)
    // SPEC-070-A J.22 — ImmutableList for Compose stability of recursive children.
    val children: kotlinx.collections.immutable.ImmutableList<ContentBlock>? = null,
    // SPEC-401-A R61 (Lens A N1, P1) — accept iOS canonical `stack_children`
    // alongside `children`. Console editor (StepContentEditor.tsx) writes
    // `stack_children` on row-block creation; without this field Row blocks
    // shipped from console rendered an empty Row on Android. iOS already
    // reads both (`block.children ?? block.stack_children ?? []` at
    // ContentBlockRendererView.swift:1129).
    val stack_children: kotlinx.collections.immutable.ImmutableList<ContentBlock>? = null,
    val z_index: Double? = null,
    val gap: Double? = null,
    val wrap: Boolean? = null,
    val justify: String? = null,
    val align_items: String? = null,
    // Row direction and distribution (alignment gap fix)
    val row_direction: String? = null,       // horizontal (default), vertical
    val row_distribution: String? = null,    // start, center, end, space-between, space-around, space-evenly
    val row_child_fill: Boolean? = null,     // true (default) — each child gets weight(1f)
    // SPEC-401-A — proportional column widths. iOS reads
    // `field_config["column_ratios"]` first then falls back to
    // top-level `column_ratios` (e.g. "1:2"). Android historically
    // ignored both → equal-weight rows even when authoring asks for
    // 1/3-2/3 splits. Honour both paths.
    val column_ratios: String? = null,
    // SPEC-089d Phase F: custom_view fields
    val view_key: String? = null,
    val custom_config: Map<String, Any>? = null,
    val placeholder_image_url: String? = null,
    val placeholder_text: String? = null,
    // SPEC-089d Phase F: star_background fields
    val particle_type: String? = null,
    val density: String? = null,
    val speed: String? = null,
    val secondary_color: String? = null,
    // Mrozu QA (2026-08-04): confetti multicolor — cycle a fixed palette instead of primary/secondary.
    // Defaults on when particle_type == "confetti", explicit override otherwise.
    val particle_multicolor: Boolean? = null,
    // SPEC-070-A J.22 — ImmutableList for Compose stability.
    val size_range: kotlinx.collections.immutable.ImmutableList<Double>? = null,
    val fullscreen: Boolean? = null,
    // SPEC-089d Phase F: wheel_picker fields
    val min_value: Double? = null,
    val max_value_picker: Double? = null,
    val step_value: Double? = null,
    val default_picker_value: Double? = null,
    val unit: String? = null,
    val unit_position: String? = null,
    val visible_items: Int? = null,
    // SPEC-401-A R35 — wheel_picker orientation per iOS (modernHorizontalWheel
    // at ContentBlockStandaloneViews.swift:1521-1612). When unset or "vertical"
    // a vertical LazyColumn renders. "horizontal" → center-snap LazyRow.
    val wheel_orientation: String? = null,
    val orientation: String? = null,
    // SPEC-089d Phase F: pulsing_avatar fields
    val pulse_color: String? = null,
    val pulse_ring_count: Int? = null,
    val pulse_speed: Double? = null,
    val border_width: Double? = null,
    val border_color: String? = null,
    // SPEC-089d: pricing_card fields
    // SPEC-070-A J.22 — ImmutableList for Compose stability.
    val pricing_plans: kotlinx.collections.immutable.ImmutableList<PricingPlan>? = null,
    val pricing_layout: String? = null,
    // SPEC-089d Phase 3: Form input common fields
    val field_label: String? = null,
    val field_placeholder: String? = null,
    val field_required: Boolean? = null,
    // SPEC-401-A R49 (Lens A #1) — multi_select on input_select content block
    // (iOS ContentBlockTypes.swift:1120 + FormInputBlockViews.swift:403-406).
    // Without this Android always renders single-select even when authored
    // multi_select=true.
    val multi_select: Boolean? = null,
    // SPEC-401-A R49 (Lens A #3-#5) — date picker constraints + chrome.
    // iOS ContentBlockStandaloneViews.swift:966,970,980,984,1009,1012,
    // 1160,1174-1175,1193-1196,1239.
    val allow_future: Boolean? = null,
    val allow_past: Boolean? = null,
    val date_validation_message: String? = null,
    val picker_presentation: String? = null,
    val picker_mode: String? = null,
    // SPEC — input_time clock presentation: "12h" (default) | "24h"; time_text_size = displayed-time font size (sp)
    val time_format: String? = null,
    val time_text_size: Double? = null,
    val picker_spacing: Double? = null,
    val wheel_bg_color: String? = null,
    val wheel_height: Double? = null,
    val calendar_bg_color: String? = null,
    // SPEC-401-A R49 (Lens A #2) — Sprint 7 scroll-collapse behaviour.
    // iOS ContentBlockTypes.swift:1143 + ContentBlockRendererView.swift:51,68
    // + ThreeZoneStepLayout.swift:19. Without this header images don't
    // collapse on scroll on Android (visual desync).
    val collapse_on_scroll: Boolean? = null,
    val field_style: FormFieldBlockStyle? = null,
    // SPEC-070-A J.22 — ImmutableList for Compose stability.
    val field_options: kotlinx.collections.immutable.ImmutableList<InputOption>? = null,
    val field_config: Map<String, Any>? = null,
    // SPEC-089d §6.3: Visibility condition
    val visibility_condition: VisibilityCondition? = null,
    // SPEC-089d §6.4: Entrance animation
    val entrance_animation: EntranceAnimationConfig? = null,
    // SPEC-089d §6.5: Press/tap state
    val pressed_style: PressedStyleConfig? = null,
    // SPEC-089d §6.6: Dynamic bindings
    val bindings: Map<String, String>? = null,
    // SPEC-089d §6.7: Relative sizing
    val element_width: String? = null,
    val element_height: String? = null,
    // Overflow control (visible = no clipping)
    val overflow: String? = null,
)

/** Social login provider config (SPEC-089d §3.4). */
@androidx.compose.runtime.Immutable
data class SocialProvider(
    val type: String,
    val label: String? = null,
    val enabled: Boolean = true,
    // SPEC-070-A finalization OB-2 — per-provider color/style overrides.
    // iOS resolves these in the renderer with brand-default fallbacks
    // (`ContentBlockRendererView.swift:724-771`). Without these fields,
    // every social-login provider rendered with hardcoded brand colors
    // and console-authored overrides were silently dropped (user
    // reported as "colors on button text are not the same as on iOS
    // and set in console").
    val bg_color: String? = null,
    val text_color: String? = null,
    val border_color: String? = null,
    val border_width: Float? = null,
    val corner_radius: Float? = null,
    val icon_style: String? = null, // "logo" | "monochrome" | "filled"
    // Social-Login styling v2 — per-provider custom icon override. When set to a
    // non-empty URL the SDK loads the remote image instead of the built-in glyph.
    val icon_url: String? = null,
)

/** Countdown labels config (SPEC-089d §3.7). */
@androidx.compose.runtime.Immutable
data class CountdownLabels(
    val days: String? = null,
    val hours: String? = null,
    val minutes: String? = null,
    val seconds: String? = null,
)

/** Timeline item config (SPEC-089d §3.5). */
@androidx.compose.runtime.Immutable
data class TimelineItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val icon: String? = null,
    // SPEC-401-A R18 — match iOS ContentBlockTypes.swift:796 — `String?`
    // (no default). When console publishes a timeline_item without `status`,
    // iOS evaluates `item.status == "upcoming"` as false → solid-black title;
    // Android previously defaulted to "upcoming" → ALWAYS greyed-out title
    // for omitted-status items. Switching to nullable aligns the title
    // foreground while keeping the status circle's `default → upcoming`
    // fallback in `timelineStatusColor` (line 2554) intact.
    val status: String? = null,  // completed | current | upcoming
)

/** Animated loading item config (SPEC-089d §3.6). */
@androidx.compose.runtime.Immutable
data class LoadingItem(
    val label: String,
    val duration_ms: Int = 1000,
    val icon: String? = null,
    // SPEC-401-A R35 — match iOS LoadingItemConfig (ContentBlockTypes.swift:816-825).
    // Per-item orbit decoration used by the orbiting_icons variant. Already
    // dropped on JSON parse before this field set was added.
    val icon_url: String? = null,
    val icon_bg_color: String? = null,
    val icon_size: Float? = null,
    val icon_orbit_angle: Float? = null,
)

// EPIC-4b — a vertical background zone (proportional weight + color) for section_background.
data class BackgroundZone(
    val weight: Double = 1.0,
    val color: String = "#000000",
)

/** Pricing plan config for pricing_card block (SPEC-089d §3.17). */
@androidx.compose.runtime.Immutable
data class PricingPlan(
    val id: String,
    val label: String,
    val price: String,
    val period: String,
    val badge: String? = null,
    val is_highlighted: Boolean = false,
)

/** Date wheel column config (SPEC-089d §3.12).
 *
 * SPEC-070-A J.22: `values` is migrated to ImmutableList for Compose stability.
 */
@androidx.compose.runtime.Immutable
data class DateWheelColumn(
    val type: String,    // day | month | year | custom
    val label: String? = null,
    val values: kotlinx.collections.immutable.ImmutableList<String>? = null,
)

/** Star particle state for star_background animation. */
data class StarParticleState(
    var x: Float,
    var y: Float,
    var size: Float,
    var opacity: Float,
    var speed: Float,
)

/** Form field styling config (SPEC-089d §5.2). */
@androidx.compose.runtime.Immutable
data class FormFieldBlockStyle(
    val background_color: String? = null,
    val border_color: String? = null,
    val border_width: Double? = null,
    val corner_radius: Double? = null,
    val text_color: String? = null,
    val placeholder_color: String? = null,
    val font_size: Double? = null,
    val focused_border_color: String? = null,
    val label_color: String? = null,
    val label_font_size: Double? = null,
    val error_border_color: String? = null,
    val error_text_color: String? = null,
    val track_color: String? = null,
    val fill_color: String? = null,
    val thumb_color: String? = null,
    val toggle_on_color: String? = null,
    val toggle_off_color: String? = null,
    // SPEC-401-A R41 — match iOS ContentBlockTypes.swift:291-312 (3 missing
    // fields). Renderer wiring of focused-state background + size token +
    // weight is a separate ticket; data-class + parser presence prevents
    // future R-round renderer fixes from being shadowed by missing field.
    val height: String? = null,
    val font_weight: String? = null,
    val focused_background_color: String? = null,
    // Select v2 (Mrozu QA) — per-option styling extras applied by the select renderers.
    val option_font_family: String? = null,     // font family for option title/subtitle/labels
    val option_corner_radius: Double? = null,    // option card corner radius (falls back to corner_radius ?? 10)
    val option_text_wrap: Boolean? = null,       // true → option text wraps fully; false → single-line truncate
    val option_image_scale: String? = null,      // "contain" (default) | "cover" | "fit" for per-option images
    val checkmark_color: String? = null,         // radio/checkmark indicator color, decoupled from fill/accent
)

/**
 * Option for select, chips, segmented inputs.
 *
 * SPEC-070-A finalization P0 audit-11 Drift 2 — expanded from 3 fields
 * to 24 to mirror iOS `InputOption` (ContentBlockTypes.swift:316-406).
 * Console authors who set per-option styling (selected/unselected images,
 * subtitles, per-option colors / fonts / icons / borders / backgrounds /
 * cell alignment / image overlays) now see those values applied on
 * Android instead of being silently dropped.
 */
/** SPEC-419 D5 — per-option badge (e.g. RECOMMENDED). Mirrors iOS InputOption.OptionBadge. */
@androidx.compose.runtime.Immutable
data class OptionBadge(
    val text: String? = null,
    val bg_color: String? = null,
    val text_color: String? = null,
    val position: String? = null,
)

@androidx.compose.runtime.Immutable
data class InputOption(
    val value: String,
    val label: String,
    val image_url: String? = null,
    /** Stable id (falls back to `value` when null on iOS). */
    val id: String? = null,
    /** SF Symbol / emoji glyph rendered next to the label. */
    val icon: String? = null,
    /** Image swap on selection (falls back to `image_url`). */
    val selected_image_url: String? = null,
    val unselected_image_url: String? = null,
    /** Per-option subtitle (smaller font under label). */
    val subtitle: String? = null,
    /** Per-option text styling — overrides field_config defaults. */
    val title_color: String? = null,
    val subtitle_color: String? = null,
    val title_font_size: Double? = null,
    val subtitle_font_size: Double? = null,
    val title_font_weight: String? = null,
    /** Grid toggle: icon to show when selected/unselected. */
    val selected_icon: String? = null,
    val unselected_icon: String? = null,
    /** Image overlay: colored circle with opacity rendered over image. */
    val image_overlay_color: String? = null,
    val image_overlay_opacity: Double? = null,
    /** EPIC-1 — selected-state image tint (falls back to image_overlay_* when unset). */
    val selected_image_overlay_color: String? = null,
    val selected_image_overlay_opacity: Double? = null,
    /** EPIC-1 — per-option image clip shape: "circle" (default) | "rounded" | "square". */
    val image_shape: String? = null,
    /** Per-option border overrides. */
    val border_color: String? = null,
    val selected_border_color: String? = null,
    /** Per-option backgrounds. */
    val bg_color: String? = null,
    val selected_bg_color: String? = null,
    val selected_text_color: String? = null,
    /** Per-option grid cell alignment ("leading" | "center" | "trailing"). */
    val cell_alignment: String? = null,
    /** SPEC-419 D7 / EPIC-1 — leading + trailing labels and per-option text alignment.
     *  leading_text renders AFTER image+icon (field name, not edge position); trailing_text
     *  renders after the title/subtitle column, before the trailing radio. */
    val leading_text: String? = null,
    val trailing_text: String? = null,
    val text_alignment: String? = null,
    /** SPEC-419 D5 — per-option badge straddling the row top border. */
    val badge: OptionBadge? = null,
) {
    /** Mirrors iOS `resolvedImageURL(isSelected:)` — selected/unselected variant first, default image_url last. */
    fun resolvedImageURL(isSelected: Boolean): String? {
        val variant = if (isSelected) selected_image_url else unselected_image_url
        // SPEC-401-A R61 (Lens A backlog #8, P3) — `isNotEmpty` matches iOS
        // canonical ContentBlockTypes.swift:401-405 `if let v = variant,
        // !v.isEmpty { return v }`. Was `isNotBlank()` which treated
        // whitespace-only strings as empty (more lenient than iOS).
        // Aligning to iOS: a `"  "` URL is returned as-is (iOS shows broken
        // image; Android was silently falling back to image_url).
        return variant?.takeIf { it.isNotEmpty() } ?: image_url
    }
}

/** Visibility condition (SPEC-089d §6.3). */
@androidx.compose.runtime.Stable
data class VisibilityCondition(
    val type: String,        // always, when_equals, when_not_equals, when_not_empty, when_empty, when_gt, when_lt
    val variable: String? = null,
    val value: Any? = null,
    val expression: String? = null,
)

/** Entrance animation config (SPEC-089d §6.4). */
@androidx.compose.runtime.Immutable
data class EntranceAnimationConfig(
    val type: String = "none",    // none, fade_in, slide_up, slide_down, slide_left, slide_right, scale_up, scale_down, bounce, flip
    val duration_ms: Int = 300,
    val delay_ms: Int = 0,
    // Audit pass-8 — canonical missing-easing default is "linear" to match iOS's
    // swiftUIAnimation fall-through (ContentBlockTypes.swift:692 → .linear). Was
    // "ease_out", which resolved a hand-authored/imported easing-less payload to a
    // different curve than iOS. (Preview '|| ease' → '|| linear' is a sibling change.)
    val easing: String = "linear",
    val spring_damping: Double? = null,
    // Sequenced animation (Mrozu Duolingo s14 / Asana): per-block stagger + ordering.
    // animation_delay_ms is ADDED to delay_ms to sequence blocks; animation_order is
    // authored ordering metadata (lower plays first, full timeline engine deferred).
    val animation_delay_ms: Int = 0,
    val animation_order: Int? = null,
)

/** Pressed/tap style config (SPEC-089d §6.5). */
@androidx.compose.runtime.Immutable
data class PressedStyleConfig(
    val bg_color: String? = null,
    val text_color: String? = null,
    val scale: Double? = null,     // 0.85-1.0
    val opacity: Double? = null,   // 0.5-1.0
)

// MARK: - Visibility Condition Evaluator (SPEC-089d §6.3)

/**
 * Evaluates a visibility condition against the current data context.
 * Returns true if the block should be rendered.
 */
fun evaluateVisibilityCondition(
    condition: VisibilityCondition?,
    responses: Map<String, Any> = emptyMap(),
    hookData: Map<String, Any>? = null,
    userTraits: Map<String, Any>? = null,
    sessionData: Map<String, Any>? = null,
): Boolean {
    if (condition == null) return true

    return when (condition.type) {
        "always" -> true
        "when_equals" -> {
            val resolved = resolveDotPath(condition.variable, responses, hookData, userTraits, sessionData)
            resolved?.toString() == condition.value?.toString()
        }
        "when_not_equals" -> {
            val resolved = resolveDotPath(condition.variable, responses, hookData, userTraits, sessionData)
            resolved?.toString() != condition.value?.toString()
        }
        "when_not_empty" -> {
            val resolved = resolveDotPath(condition.variable, responses, hookData, userTraits, sessionData)
            resolved != null && resolved.toString().isNotEmpty()
        }
        "when_empty" -> {
            val resolved = resolveDotPath(condition.variable, responses, hookData, userTraits, sessionData)
            resolved == null || resolved.toString().isEmpty()
        }
        "when_gt" -> {
            val resolved = resolveDotPath(condition.variable, responses, hookData, userTraits, sessionData)
            val numA = resolved?.toString()?.toDoubleOrNull() ?: return false
            val numB = condition.value?.toString()?.toDoubleOrNull() ?: return false
            numA > numB
        }
        "when_lt" -> {
            val resolved = resolveDotPath(condition.variable, responses, hookData, userTraits, sessionData)
            val numA = resolved?.toString()?.toDoubleOrNull() ?: return false
            val numB = condition.value?.toString()?.toDoubleOrNull() ?: return false
            numA < numB
        }
        else -> true
    }
}

/**
 * Resolves a dot-path variable from the evaluation context.
 */
private fun resolveDotPath(
    path: String?,
    responses: Map<String, Any>,
    hookData: Map<String, Any>?,
    userTraits: Map<String, Any>?,
    sessionData: Map<String, Any>?,
): Any? {
    if (path.isNullOrEmpty()) return null
    val parts = path.split(".")
    if (parts.size < 2) return null

    val root: Map<String, Any>? = when (parts[0]) {
        "responses" -> responses
        "hook_data" -> hookData
        "user" -> userTraits
        "session" -> sessionData
        else -> null
    }

    var current: Any? = root ?: return null
    for (part in parts.drop(1)) {
        current = (current as? Map<*, *>)?.get(part) ?: return null
    }
    return current
}

/**
 * Resolves `{{variable}}` template strings in text (SPEC-089d §6.6).
 */
fun resolveTemplateString(
    text: String,
    hookData: Map<String, Any>?,
    responses: Map<String, Any>,
    sessionData: Map<String, Any>? = null,
    userTraits: Map<String, Any>? = null,
): String {
    val pattern = Regex("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*\\}\\}")
    return pattern.replace(text) { matchResult ->
        val path = matchResult.groupValues[1]
        val resolved = resolveDotPath(path, responses, hookData, userTraits, sessionData)
        resolved?.toString() ?: matchResult.value
    }
}

/**
 * AC-064/065/066: Resolves dynamic bindings and template strings on a block.
 * Returns a new ContentBlock with resolved text fields and binding property overrides.
 */
private fun resolveBlockBindings(
    block: ContentBlock,
    hookData: Map<String, Any>?,
    responses: Map<String, Any>,
): ContentBlock {
    val hasBindings = !block.bindings.isNullOrEmpty()
    val hasTemplates = (block.text?.contains("{{") == true)
        || (block.field_label?.contains("{{") == true)
        || (block.field_placeholder?.contains("{{") == true)
        || (block.badge_text?.contains("{{") == true)
        || (block.toggle_label?.contains("{{") == true)
        || (block.label?.contains("{{") == true)
        // RichText v2 — rich_text's primary content field is markdown_content; it
        // must run the SAME {{var}} interpolation as text so a rich_text block
        // referencing a prior-screen answer resolves on device (mirrors iOS).
        || (block.markdown_content?.contains("{{") == true)
    if (!hasBindings && !hasTemplates) return block

    var resolved = block

    // AC-066: Resolve bindings map — override block properties from data context
    if (hasBindings) {
        block.bindings?.forEach { (property, path) ->
            val value = resolveDotPath(path, responses, hookData, null, null)
            if (value != null) {
                resolved = applyBindingProperty(resolved, property, value)
            }
        }
    }

    // AC-064/065: Resolve template strings in text fields
    if (hasTemplates) {
        resolved = resolved.copy(
            text = resolved.text?.let { if (it.contains("{{")) resolveTemplateString(it, hookData, responses) else it },
            field_label = resolved.field_label?.let { if (it.contains("{{")) resolveTemplateString(it, hookData, responses) else it },
            field_placeholder = resolved.field_placeholder?.let { if (it.contains("{{")) resolveTemplateString(it, hookData, responses) else it },
            badge_text = resolved.badge_text?.let { if (it.contains("{{")) resolveTemplateString(it, hookData, responses) else it },
            toggle_label = resolved.toggle_label?.let { if (it.contains("{{")) resolveTemplateString(it, hookData, responses) else it },
            label = resolved.label?.let { if (it.contains("{{")) resolveTemplateString(it, hookData, responses) else it },
            markdown_content = resolved.markdown_content?.let { if (it.contains("{{")) resolveTemplateString(it, hookData, responses) else it },
        )
    }

    return resolved
}

/** Apply a single binding property override to a ContentBlock. */
private fun applyBindingProperty(block: ContentBlock, property: String, value: Any): ContentBlock {
    val strValue = value.toString()
    return when (property) {
        "text" -> block.copy(text = strValue)
        "markdown_content" -> block.copy(markdown_content = strValue)
        "field_label" -> block.copy(field_label = strValue)
        "field_placeholder" -> block.copy(field_placeholder = strValue)
        "badge_text" -> block.copy(badge_text = strValue)
        "toggle_label" -> block.copy(toggle_label = strValue)
        "label" -> block.copy(label = strValue)
        "image_url" -> block.copy(image_url = strValue)
        "bg_color" -> block.copy(bg_color = strValue)
        "text_color" -> block.copy(text_color = strValue)
        "active_color" -> block.copy(active_color = strValue)
        "inactive_color" -> block.copy(inactive_color = strValue)
        "fill_color" -> block.copy(fill_color = strValue)
        "icon_emoji" -> block.copy(icon_emoji = strValue)
        "active_index" -> (value as? Number)?.toInt()?.let { block.copy(active_index = it) } ?: block
        "dot_count" -> (value as? Number)?.toInt()?.let { block.copy(dot_count = it) } ?: block
        "segment_count" -> (value as? Number)?.toInt()?.let { block.copy(segment_count = it) } ?: block
        "active_segments" -> (value as? Number)?.toInt()?.let { block.copy(active_segments = it) } ?: block
        else -> block // Unknown property — no-op
    }
}

/**
 * Parses relative size strings (SPEC-089d §6.7).
 */
fun Modifier.applyRelativeSizing(width: String?, height: String?, useMinHeight: Boolean = false): Modifier {
    var mod = this
    when {
        width == "fill" -> mod = mod.then(Modifier.fillMaxWidth())
        width == "auto" -> { /* no-op, content-sized */ }
        width?.endsWith("%") == true -> {
            val fraction = width.dropLast(1).toFloatOrNull()?.div(100f)
            if (fraction != null) mod = mod.then(Modifier.fillMaxWidth(fraction))
        }
        width?.endsWith("px") == true -> {
            val px = width.dropLast(2).toFloatOrNull()
            if (px != null) mod = mod.then(Modifier.width(px.dp))
        }
    }
    when {
        height == "fill" -> mod = mod.then(Modifier.fillMaxHeight())
        height == "auto" -> { /* no-op */ }
        height?.endsWith("%") == true -> {
            val fraction = height.dropLast(1).toFloatOrNull()?.div(100f)
            if (fraction != null) mod = mod.then(Modifier.fillMaxHeight(fraction))
        }
        height?.endsWith("px") == true -> {
            val px = height.dropLast(2).toFloatOrNull()
            // SPEC-419 — useMinHeight → heightIn(min) so content TALLER than the authored height
            // (e.g. a 3-line heading inside element_height:100px) grows instead of being clipped.
            // iOS applies the same height via SwiftUI .frame(height:), which never clips overflow;
            // Compose .height() DOES clip → the therapist heading lost its last line ("relief?").
            // Fixed height kept for non-text blocks (images/spacers) that want an exact box.
            if (px != null) mod = mod.then(
                if (useMinHeight) Modifier.heightIn(min = px.dp) else Modifier.height(px.dp),
            )
        }
    }
    return mod
}

// MARK: - SPEC-419 STEP-2 — element-interaction wiring helpers (pure, unit-tested)

/**
 * Fold host-pushed per-block `field_config` overrides onto a block at READ TIME. ContentBlock is
 * immutable, so we `copy(field_config = merged)` where `overrides[block.id]` wins key-by-key over the
 * authored field_config. Empty/absent overrides → the block is returned unchanged. Applied
 * UNCONDITIONALLY at the render call site (NOT inside resolveBlockBindings, whose early-return skips
 * every EPIC-11 element that has no bindings). Mirrors iOS `resolvedFieldConfig`.
 */
fun resolvedFieldConfig(block: ContentBlock, overrides: Map<String, Map<String, Any>>): ContentBlock {
    val patch = overrides[block.id]
    if (patch.isNullOrEmpty()) return block
    val merged = (block.field_config ?: emptyMap()) + patch
    return block.copy(field_config = merged)
}

/**
 * Key-level merge of new per-block `field_config` patches over existing overrides (override wins).
 * Never blind-replaces a block's override bag — merges key by key. Mirrors iOS `mergeFieldConfigOverrides`.
 */
fun mergeFieldConfigOverrides(
    current: Map<String, Map<String, Any>>,
    patches: Map<String, Map<String, Any>>,
): Map<String, Map<String, Any>> {
    val out = current.toMutableMap()
    for ((id, patch) in patches) {
        out[id] = (out[id] ?: emptyMap()) + patch
    }
    return out
}

/**
 * Pure required-field validation used by `handleAction("next")`. Extracted from BlockBasedStepView's
 * `canAdvance` walk so the advance gate is unit-testable without a live host, proving an
 * interaction-driven advance can NOT bypass validation. Returns the first missing block's label (for
 * the validation pill copy) or null. Mirrors iOS `RequiredFieldGate` (Android keeps the extra
 * `List<*>` branch so an empty multi-select still fails the gate — pre-existing behavior).
 */
object RequiredFieldGate {
    fun evaluate(blocks: List<ContentBlock>, inputValues: Map<String, Any>): Pair<Boolean, String?> {
        for (block in blocks) {
            if (block.field_required != true) continue
            val fieldId = block.field_id ?: block.id
            val empty = when (val v = inputValues[fieldId]) {
                null -> true
                is String -> v.isEmpty()
                is Map<*, *> -> v.isEmpty()
                is List<*> -> v.isEmpty()
                // Mrozu QA (2026-08-04, Flo s1) — a required `agreement`/consent checkbox is satisfied
                // ONLY when checked; unchecked reports non-null `false` that would otherwise slip past.
                // Scoped to `agreement` so pre-existing required `input_toggle`/`toggle` keep their
                // behavior. Parity with iOS `block.type == .agreement`.
                is Boolean -> block.type == "agreement" && !v
                else -> false
            }
            if (empty) return false to (block.field_label ?: block.label ?: fieldId)
        }
        return true to null
    }
}

/**
 * Pure composition of the flow-host + step-scope interaction fold: awaits the delegate, applies the
 * [ElementInteractionResult] to `inputValues`, key-level-merges field_config overrides, and reports
 * whether an advance was requested. Production splits this across `OnboardingFlowHost.performInteraction`
 * (delegate + [applyInteractionResult]) and `BlockBasedStepView.handleInteract` (merge + advance); this
 * mirror exists so the composed seam is unit-testable without a live Compose host. Mirrors iOS
 * `fireElementInteraction`.
 */
suspend fun fireElementInteraction(
    delegate: AppDNAOnboardingDelegate?,
    flowId: String,
    stepId: String,
    blockId: String,
    action: String,
    value: String?,
    inputValues: Map<String, Any>,
    overrides: Map<String, Map<String, Any>>,
): Triple<Map<String, Any>, Map<String, Map<String, Any>>, Boolean> {
    val result = delegate?.onElementInteraction(flowId, stepId, blockId, action, value, inputValues)
        ?: return Triple(inputValues, overrides, false)
    val applied = applyInteractionResult(result, inputValues)
    val mergedOverrides = mergeFieldConfigOverrides(overrides, applied.fieldConfigOverrides)
    return Triple(applied.inputValues, mergedOverrides, applied.advance)
}

// MARK: - Content Block Renderer

@Composable
fun ContentBlockRendererView(
    blocks: List<ContentBlock>,
    onAction: (String) -> Unit,
    toggleValues: MutableMap<String, Boolean>,
    inputValues: MutableMap<String, Any> = mutableMapOf(),
    loc: ((String, String) -> String)? = null,
    responses: Map<String, Any> = emptyMap(),
    hookData: Map<String, Any>? = null,
    currentStepIndex: Int = 0,
    totalSteps: Int = 1,
    // SPEC-419 STEP-2 — interactive-element fire closure + per-block field_config override read-layer.
    // Default no-op / empty so non-interactive call paths (previews, legacy) compile unchanged.
    onInteract: (String, String, String?) -> Unit = { _, _, _ -> },
    fieldConfigOverrides: Map<String, Map<String, Any>> = emptyMap(),
) {
    // SPEC-089d §6.3: Filter blocks by visibility condition
    val visibleBlocks = blocks.filter { block ->
        evaluateVisibilityCondition(
            block.visibility_condition,
            responses = responses,
            hookData = hookData,
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        var animationCount = 0
        visibleBlocks.forEach { rawBlock ->
            // AC-064/065/066: Resolve dynamic bindings and template strings
            // SPEC-419 STEP-2 — fold host-pushed per-block field_config overrides UNCONDITIONALLY here,
            // AFTER resolveBlockBindings (which early-returns raw blocks that have no bindings — i.e.
            // every EPIC-11 element, so the merge can't live inside it). Empty overrides → no change.
            val block = resolvedFieldConfig(
                resolveBlockBindings(rawBlock, hookData = hookData, responses = responses),
                fieldConfigOverrides,
            )

            val shouldAnimate = block.entrance_animation != null
                && block.entrance_animation.type != "none"
                && animationCount < 10  // Max 10 animated blocks per step
            if (shouldAnimate) animationCount++

            // SPEC-089d §6.7: Apply relative sizing.
            // SPEC-401-A R9 — skip `element_height` for input_* blocks,
            // mirroring iOS ContentBlockRendererView.swift:58-62 which
            // explicitly passes `nil` height for inputs so e.g. a
            // 60dp text field inside a `element_height: 280dp` row
            // doesn't render a tall empty wrapper around it. iOS also
            // sets `useMinHeight=true` for `input_select` so the
            // dropdown can grow; Compose's relative sizing equivalent
            // already lets dropdowns expand, so we only need the skip.
            val isInputBlock = block.type.startsWith("input_")
            // SPEC-419 — text blocks treat element_height as a MINIMUM (grow, never clip) since a
            // wrapped heading/body can exceed the authored height; matches iOS .frame non-clipping.
            val isTextBlock = block.type in setOf("heading", "text", "subheading", "subtitle", "body", "paragraph", "rich_text")
            val effectiveHeight = if (isInputBlock) null else block.element_height
            val sizingModifier = Modifier.applyRelativeSizing(block.element_width, effectiveHeight, useMinHeight = isTextBlock)

            if (shouldAnimate) {
                block.entrance_animation?.let { anim ->
                    EntranceAnimationWrapper(animation = anim) {
                        Box(modifier = sizingModifier) {
                            RenderBlock(block = block, onAction = onAction, toggleValues = toggleValues, inputValues = inputValues, loc = loc, currentStepIndex = currentStepIndex, totalSteps = totalSteps, onInteract = onInteract, stepBlocks = blocks)
                        }
                    }
                }
            } else {
                Box(modifier = sizingModifier) {
                    RenderBlock(block = block, onAction = onAction, toggleValues = toggleValues, inputValues = inputValues, loc = loc, currentStepIndex = currentStepIndex, totalSteps = totalSteps, onInteract = onInteract, stepBlocks = blocks)
                }
            }
        }
    }
}

@Composable
internal fun RenderBlock(
    block: ContentBlock,
    onAction: (String) -> Unit,
    toggleValues: MutableMap<String, Boolean>,
    inputValues: MutableMap<String, Any> = mutableMapOf(),
    loc: ((String, String) -> String)? = null,
    currentStepIndex: Int = 0,
    totalSteps: Int = 1,
    // SPEC-419 STEP-2 — interactive-element fire closure; default no-op so container recursions
    // (carousel/section/stack/row) that don't thread it still compile.
    onInteract: (String, String, String?) -> Unit = { _, _, _ -> },
    // Mrozu QA (2026-08-04) — the step's sibling blocks, so a consent-reactive CTA button can run the
    // step-level RequiredFieldGate. Default empty (nested container recursions pass none → gate satisfied).
    stepBlocks: List<ContentBlock> = emptyList(),
) {
    // SPEC-089d: Wrap every block with block_style + 2D positioning modifiers
    val blockAlignment = if (block.horizontal_align != null || block.vertical_align != null) {
        mapBlockAlignment(block.horizontal_align, block.vertical_align)
    } else null

    // SPEC-401-A R9 — universal block-container styling. Mirrors
    // iOS `ContentBlockRendererView.swift:67` `.applyBlockContainerStyle(block)`
    // applied to EVERY block type. Previously only the `row` block read
    // these field_config keys; every other block silently dropped
    // authored `blur_background` / `container_bg_color` / `container_*`.
    // The hook is opt-in: it no-ops when the field_config has no
    // visible container values.
    val contentModifier = with(StyleEngine) {
        Modifier
            .applyBlockStyle(
                block.block_style,
                // Overflow blocks (pulse rings / star field) must not be clipped to the block box.
                clipContent = block.type != "pulsing_avatar" && block.type != "star_background",
            )
            .applyBlockPosition(
                verticalAlign = block.vertical_align,
                horizontalAlign = block.horizontal_align,
                verticalOffset = block.vertical_offset,
                horizontalOffset = block.horizontal_offset,
            )
            .applyBlockContainerStyle(block.field_config)
    }

    if (blockAlignment != null) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = blockAlignment,
        ) {
            Box(modifier = contentModifier) {
                RenderBlockContent(block, onAction, toggleValues, inputValues, loc, currentStepIndex, totalSteps, onInteract, stepBlocks)
            }
        }
    } else {
        Box(modifier = contentModifier) {
            RenderBlockContent(block, onAction, toggleValues, inputValues, loc, currentStepIndex, totalSteps, onInteract, stepBlocks)
        }
    }
}

@Composable
private fun RenderBlockContent(
    block: ContentBlock,
    onAction: (String) -> Unit,
    toggleValues: MutableMap<String, Boolean>,
    inputValues: MutableMap<String, Any> = mutableMapOf(),
    loc: ((String, String) -> String)? = null,
    currentStepIndex: Int = 0,
    totalSteps: Int = 1,
    // SPEC-419 STEP-2 — interactive-element fire closure threaded to the 7 interactive elements.
    onInteract: (String, String, String?) -> Unit = { _, _, _ -> },
    // Mrozu QA (2026-08-04) — step sibling blocks for the consent-reactive CTA gate (see RenderBlock).
    stepBlocks: List<ContentBlock> = emptyList(),
) {
    when (block.type) {
        "heading" -> HeadingBlock(block, loc)
        "text" -> TextBlock(block, loc)
        "image" -> ImageBlock(block)
        "media_gallery" -> MediaGalleryBlock(block)
        "section_background" -> SectionBackgroundBlock(block, onAction, toggleValues, inputValues, loc, stepBlocks)
        "carousel" -> CarouselBlock(block, onAction, toggleValues, inputValues, loc, stepBlocks)
        "otp_input" -> OtpInputBlock(block, inputValues, onInteract)
        "warning_banner" -> WarningBannerBlock(block, loc)
        "password_strength" -> PasswordStrengthBlock(block)
        "speech_bubble" -> SpeechBubbleBlock(block, loc)
        "feedback_panel" -> FeedbackPanelBlock(block, loc)
        "summary_screen" -> SummaryScreenBlock(block, loc)
        "press_hold_confirm" -> PressHoldConfirmBlock(block, inputValues, loc, onInteract)
        "health_connect" -> HealthConnectBlock(block, onAction, loc, onInteract)
        "settings_footer" -> SettingsFooterBlock(block, onAction, onInteract)
        "memory_match" -> MemoryMatchBlock(block, onInteract)
        "calendar_month" -> CalendarMonthBlock(block, inputValues, onInteract)
        "button" -> ButtonBlock(block, onAction, loc, stepBlocks, inputValues)
        // Mrozu (Duolingo s20/s22) — CTA-style button that plays `audio_url` on tap.
        "sound_button" -> SoundButtonBlock(block, onAction, loc, stepBlocks, inputValues)
        "spacer" -> Spacer(modifier = Modifier.height((block.spacer_height ?: 24.0).dp)) // SPEC-419 pass-14 #11 — unset default 24 to match editor+preview (was 16)
        "list" -> ListBlock(block, loc)
        "divider" -> DividerBlock(block)
        "badge" -> BadgeBlock(block, loc)
        "icon" -> IconBlock(block)
        "toggle" -> ToggleBlock(block, toggleValues, loc)
        "video" -> VideoBlock(block)
        // SPEC-085: Rich media block types
        "lottie" -> LottieContentBlock(block)
        "rive" -> RiveContentBlock(block)
        // SPEC-089d Phase A: Implemented onboarding block types
        "page_indicator" -> PageIndicatorBlock(block, currentStepIndex, totalSteps)
        "social_login" -> SocialLoginBlock(block, onAction, loc)
        "countdown_timer" -> CountdownTimerBlock(block, onAction)
        "rating" -> RatingBlock(block, inputValues, loc)
        "rich_text" -> RichTextBlock(block, loc)
        "progress_bar" -> ProgressBarBlock(block, loc, currentStepIndex, totalSteps)
        "timeline" -> TimelineBlock(block, loc)
        "animated_loading" -> AnimatedLoadingBlock(block, onAction)
        // SPEC-089d Phase A: Implemented blocks
        "wheel_picker" -> WheelPickerBlock(block, inputValues, onInteract)
        "pulsing_avatar" -> PulsingAvatarBlock(block)
        "star_background" -> StarBackgroundBlock(block)
        // SPEC-089d Phase F: Container & advanced block types
        "stack" -> StackBlock(block, onAction, toggleValues, inputValues, loc, stepBlocks)
        "custom_view" -> CustomViewBlock(block)
        "date_wheel_picker" -> DateWheelPickerBlock(block, inputValues)
        "circular_gauge" -> CircularGaugeBlock(block)
        "row" -> RowBlock(block, onAction, toggleValues, inputValues, loc, stepBlocks)
        // SPEC-089d: Pricing card
        "pricing_card" -> PricingCardBlock(block, onAction, inputValues)
        // SPEC-089d Phase 3: Form input block renderers (22 types)
        "input_text" -> FormInputTextBlock(block, inputValues, keyboardType = android.text.InputType.TYPE_CLASS_TEXT)
        "input_textarea" -> FormInputTextAreaBlock(block, inputValues)
        "input_number" -> FormInputTextBlock(block, inputValues, keyboardType = android.text.InputType.TYPE_CLASS_NUMBER)
        "input_email" -> FormInputTextBlock(block, inputValues, keyboardType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        "input_phone" -> FormInputTextBlock(block, inputValues, keyboardType = android.text.InputType.TYPE_CLASS_PHONE)
        "input_url" -> FormInputTextBlock(block, inputValues, keyboardType = android.text.InputType.TYPE_TEXT_VARIATION_URI)
        "input_password" -> FormInputPasswordBlock(block, inputValues)
        "input_date" -> FormInputDateBlock(block, inputValues, mode = "date")
        "input_time" -> FormInputDateBlock(block, inputValues, mode = "time")
        "input_datetime" -> FormInputDateBlock(block, inputValues, mode = "datetime")
        "input_select" -> FormInputSelectBlock(block, inputValues)
        "input_slider" -> FormInputSliderBlock(block, inputValues)
        "input_toggle" -> FormInputToggleBlock(block, inputValues)
        // Mrozu QA (2026-08-04, Flo s1) — standalone consent/agreement (checkbox + rich links → Bool).
        "agreement" -> AgreementBlock(block, inputValues)
        "input_stepper" -> FormInputStepperBlock(block, inputValues)
        "input_segmented" -> FormInputSegmentedBlock(block, inputValues)
        "input_rating" -> FormInputRatingBlock(block, inputValues)
        "input_range_slider" -> FormInputRangeSliderBlock(block, inputValues)
        "input_chips" -> FormInputChipsBlock(block, inputValues)
        "input_color" -> FormInputColorBlock(block, inputValues)
        "input_location" -> FormInputLocationPlaceholder(block, inputValues)
        "input_image_picker" -> FormInputImagePickerPlaceholder(block, inputValues)
        "input_signature" -> FormInputSignatureBlock(block, inputValues)
        // SPEC-089d AC-002: Backward compatibility — unknown types render as empty
        else -> {
            // Unknown block types silently render nothing.
            // This prevents crashes when the backend sends new block types
            // that this SDK version does not yet implement.
        }
    }
}

/**
 * SPEC-401-A — translate `horizontal_align` token into Compose
 * [TextAlign]. Mirrors iOS `multilineTextAlignment(...)` on text/
 * heading blocks. iOS accepts `leading`/`start`/`left`,
 * `trailing`/`end`/`right`, `center`. Unknown values fall through
 * to platform default (Start).
 */
private fun horizontalTextAlign(token: String?): androidx.compose.ui.text.style.TextAlign? = when (token) {
    "left", "start", "leading" -> androidx.compose.ui.text.style.TextAlign.Start
    "right", "end", "trailing" -> androidx.compose.ui.text.style.TextAlign.End
    "center" -> androidx.compose.ui.text.style.TextAlign.Center
    "justify" -> androidx.compose.ui.text.style.TextAlign.Justify
    else -> null
}

@Composable
private fun HeadingBlock(block: ContentBlock, loc: ((String, String) -> String)? = null) {
    val text = block.text ?: ""
    val baseStyle = TextStyle(
        // SPEC-401-A R61 (Lens A backlog #1, P2) — match iOS canonical default
        // at ContentBlockRendererView.swift:240-246: level 4-6 (and unknown)
        // falls to 28pt, NOT 18pt. Was rendering 18sp on Android vs 28pt on
        // iOS for the same `{level: 4}` payload — 10pt narrower.
        fontSize = when (block.level ?: 1) { 1 -> 28.sp; 2 -> 22.sp; 3 -> 18.sp; else -> 28.sp },
        fontWeight = FontWeight.Bold,
        color = Color.Unspecified,
    )
    val effectiveStyle = if (block.style != null) StyleEngine.applyTextStyle(baseStyle, block.style) else baseStyle
    val styleWithAlign = horizontalTextAlign(block.horizontal_align)
        ?.let { effectiveStyle.copy(textAlign = it) } ?: effectiveStyle
    val resolved = loc?.invoke("block.${block.id}.text", text) ?: text
    Text(
        // SPEC-401-A R10 — apply `style.text_transform` (uppercase/lowercase).
        // iOS does this via `.textCase` in StyleEngine.swift:194-198. Compose
        // has no equivalent TextStyle modifier so we transform the string.
        text = block.style.applyTransform(resolved),
        style = styleWithAlign,
        // SPEC — honor max_lines on headings (iOS added .lineLimit here too); nil
        // → no limit (unchanged). Ellipsis is a no-op when unbounded.
        maxLines = block.max_lines ?: Int.MAX_VALUE,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        // SPEC-070-A J.11 — heading content blocks announce as a heading
        // to screen readers, matching iOS `accessibilityAddTraits(.isHeader)`.
        modifier = Modifier
            .fillMaxWidth()
            .semantics { heading() },
    )
}

@Composable
private fun TextBlock(block: ContentBlock, loc: ((String, String) -> String)? = null) {
    val text = block.text ?: ""
    val baseStyle = TextStyle(fontSize = 16.sp, color = Color.Unspecified)
    val effectiveStyle = if (block.style != null) StyleEngine.applyTextStyle(baseStyle, block.style) else baseStyle
    val styleWithAlign = horizontalTextAlign(block.horizontal_align)
        ?.let { effectiveStyle.copy(textAlign = it) } ?: effectiveStyle
    val resolved = loc?.invoke("block.${block.id}.text", text) ?: text
    val content = block.style.applyTransform(resolved)
    val maxLines = block.max_lines ?: Int.MAX_VALUE
    val showTrailingDots = (block.field_config?.get("show_trailing_dots") as? Boolean) ?: false
    if (showTrailingDots) {
        // Mrozu QA — trailing animated ellipsis ("", ".", "..", "...") for loading-style text.
        // Parity w/ iOS AnimatedTrailingDots + console preview's pulsing-dots span.
        val boxAlign = when (block.horizontal_align) {
            "center" -> Alignment.Center
            "right", "trailing" -> Alignment.CenterEnd
            else -> Alignment.CenterStart
        }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = boxAlign) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = content,
                    style = styleWithAlign,
                    maxLines = maxLines,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                AnimatedTrailingDots(style = styleWithAlign)
            }
        }
    } else {
        Text(
            // SPEC-401-A R10 — apply `style.text_transform` (uppercase/lowercase).
            text = content,
            style = styleWithAlign,
            // SPEC — honor max_lines on text (iOS added .lineLimit here too); nil → no
            // limit (unchanged). Ellipsis is a no-op when unbounded.
            maxLines = maxLines,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Mrozu QA — a trailing animated ellipsis that cycles "" → "." → ".." → "..." every 400ms,
 * reserving the full "..." width so the preceding text does not shift. Used by [TextBlock] when
 * `field_config.show_trailing_dots` is true. Parity w/ iOS `AnimatedTrailingDots`. */
@Composable
private fun AnimatedTrailingDots(style: TextStyle) {
    var count by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            count = (count + 1) % 4
        }
    }
    Box(contentAlignment = Alignment.BottomStart) {
        // Invisible full-width placeholder keeps the layout stable.
        Text("...", style = style, color = Color.Transparent)
        Text(".".repeat(count), style = style)
    }
}

@Composable
private fun CarouselBlock(
    block: ContentBlock,
    onAction: (String) -> Unit,
    toggleValues: MutableMap<String, Boolean>,
    inputValues: MutableMap<String, Any>,
    loc: ((String, String) -> String)?,
    // Mrozu QA (2026-08-04) — thread the step's blocks so a nested consent-reactive CTA
    // evaluates the full step's RequiredFieldGate (parity with iOS, which recurses on the
    // same ContentBlockRendererView instance holding `self.blocks`).
    stepBlocks: List<ContentBlock> = emptyList(),
) {
    // EPIC-8 — swipeable carousel: each child block is a page; render a HorizontalPager
    // + a dot indicator. Page indicator colors come through field_config.
    val pages = block.children ?: block.stack_children ?: return
    if (pages.isEmpty()) return
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { pages.size })
    val activeColor = (block.field_config?.get("indicator_active_color") as? String)?.let { StyleEngine.parseColor(it) }
        ?: StyleEngine.parseColor(ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1")
    val inactiveColor = (block.field_config?.get("indicator_color") as? String)?.let { StyleEngine.parseColor(it) }
        ?: Color(0xFF4B5563)
    Column(modifier = Modifier.fillMaxWidth()) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().height((block.height ?: 240.0).dp),
        ) { page ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                RenderBlock(pages[page], onAction, toggleValues, inputValues, loc, stepBlocks = stepBlocks)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(pages.size) { i ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (i == pagerState.currentPage) activeColor else inactiveColor),
                )
            }
        }
    }
}

@Composable
private fun SectionBackgroundBlock(
    block: ContentBlock,
    onAction: (String) -> Unit,
    toggleValues: MutableMap<String, Boolean>,
    inputValues: MutableMap<String, Any>,
    loc: ((String, String) -> String)?,
    // Mrozu QA (2026-08-04) — thread step blocks so a nested consent-CTA gates on the full step (iOS parity).
    stepBlocks: List<ContentBlock> = emptyList(),
) {
    // EPIC-4b — paint vertical proportional color zones, overlay the children content on top.
    // Zones + arrangement come through field_config (ContentBlock is at the JVM constructor-arg limit).
    // SPEC-419 — NO early-return when the zones key is absent (AI/imported configs may omit it).
    // Fall back to an empty list so the foreground children still render on a bare background,
    // matching iOS (`?? []`) + the console preview (`|| []`). The comment below already said so.
    val zonesRaw = block.field_config?.get("background_zones") as? List<*> ?: emptyList<Any?>()
    val zones = zonesRaw.mapNotNull { z ->
        (z as? Map<*, *>)?.let {
            BackgroundZone(
                weight = (it["weight"] as? Number)?.toDouble() ?: 1.0,
                color = it["color"] as? String ?: "#000000",
            )
        }
    }
    // No early-return on empty zones — render the foreground children on a bare background, matching
    // iOS (ContentBlockRendererView) + the console preview (was: rendered nothing when zones absent).
    val children = block.children ?: block.stack_children ?: emptyList()
    val arrangement = when (block.field_config?.get("content_arrangement") as? String) {
        "top" -> Arrangement.Top
        "center" -> Arrangement.Center
        "bottom" -> Arrangement.Bottom
        else -> Arrangement.SpaceBetween
    }
    // EPIC-4b v2 — background_extent (% of screen height, 1–100) lets the section fill the screen
    // or reach a configured % from the top. When absent, fall back to the fixed height (480.dp default),
    // matching iOS (ContentBlockRendererView) + the console preview.
    // Use the FULL physical display height (heightPixels / density) as the basis so
    // extent=100 fills the same physical extent as iOS's UIScreen.main.bounds.height.
    // screenHeightDp excludes the status/nav bars (~24–70dp), which left a visible
    // gap on Android for the documented "fills the screen" contract (extent=100).
    val displayMetrics = LocalContext.current.resources.displayMetrics
    val fullScreenHeightDp = displayMetrics.heightPixels / displayMetrics.density
    val extentPct = (block.field_config?.get("background_extent") as? Number)?.toDouble()
    val fixedHeight = block.height ?: 480.0
    val boxMod = if (extentPct != null) {
        Modifier.fillMaxWidth()
            .height((fullScreenHeightDp * (extentPct.coerceIn(1.0, 100.0) / 100.0)).dp)
    } else {
        Modifier.fillMaxWidth().height(fixedHeight.dp)
    }
    Box(modifier = boxMod) {
        // Background: vertical weighted color zones.
        Column(modifier = Modifier.fillMaxSize()) {
            zones.forEach { zone ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // coerceAtLeast: Compose Modifier.weight require()s > 0 — a 0/negative authored zone weight crashed the step.
                        .weight(zone.weight.toFloat().coerceAtLeast(0.01f))
                        .background(StyleEngine.parseColor(zone.color)),
                )
            }
        }
        // Foreground: content overlaid on the zones.
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = arrangement,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            children.forEach { child ->
                RenderBlock(
                    block = child,
                    onAction = onAction,
                    toggleValues = toggleValues,
                    inputValues = inputValues,
                    loc = loc,
                    stepBlocks = stepBlocks,
                )
            }
        }
    }
}

@Composable
private fun MediaGalleryBlock(block: ContentBlock) {
    // EPIC-3 — horizontal scrollable gallery of image tiles (rounded, fixed size, placeholder bg).
    // Media-gallery v2 (Mrozu QA): gallery_fill = full-width edge-to-edge cover tiles; gallery_autoscroll =
    // seamless marquee loop (gallery_autoscroll_speed = seconds per full cycle, default 20). Both default off
    // → existing static LazyRow (no infinite animation started when off — non-breaking, zero battery cost).
    val images = block.gallery_images ?: return
    if (images.isEmpty()) return
    val itemH = (block.gallery_item_height ?: 180.0).dp
    val cr = (block.gallery_corner_radius ?: 12.0).dp
    val spacing = (block.gallery_spacing ?: 10.0).dp
    val fill = block.gallery_fill ?: false
    val autoscroll = block.gallery_autoscroll ?: false

    if (autoscroll) {
        MediaGalleryAutoScrollRow(images, block.gallery_item_width, itemH, cr, spacing, fill, block.gallery_autoscroll_speed ?: 20.0)
        return
    }

    // Mrozu QA (2026-08-04) — alarmy selectable gallery: gallery_preview_on_select opens a full-screen
    // enlarged overlay of the tapped image. Default off → the existing static row (non-breaking). Image
    // preview only — video/gif/sound preview playback is net-new host media infra (deferred). Parity w/ iOS.
    val previewOnSelect = (block.field_config?.get("gallery_preview_on_select") as? Boolean) ?: false
    var previewUrl by remember { mutableStateOf<String?>(null) }
    val onTile: ((String) -> Unit)? = if (previewOnSelect) ({ url -> previewUrl = url }) else null

    val itemW = (block.gallery_item_width ?: 140.0).dp
    if (fill) {
        androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val tileW = maxWidth
            LazyRow(modifier = Modifier.fillMaxWidth()) {
                items(images.size) { i -> GalleryTile(images[i], tileW, itemH, 0.dp, onTile) }
            }
        }
    } else {
        val align = when (block.gallery_align) {
            "start" -> androidx.compose.ui.Alignment.Start
            "end" -> androidx.compose.ui.Alignment.End
            else -> androidx.compose.ui.Alignment.CenterHorizontally
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing, align),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
        ) {
            items(images.size) { i -> GalleryTile(images[i], itemW, itemH, cr, onTile) }
        }
    }

    val shownPreview = previewUrl
    if (shownPreview != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { previewUrl = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color(0xE6000000))
                    .clickable { previewUrl = null },
                contentAlignment = Alignment.Center,
            ) {
                ai.appdna.sdk.core.NetworkImage(
                    url = shownPreview,
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
                Box(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Text(
                        "✕",
                        fontSize = 28.sp,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.clickable { previewUrl = null },
                    )
                }
            }
        }
    }
}

// Media-gallery v2 — shared tile (placeholder bg + cover image, rounded/clipped).
// Mrozu QA (2026-08-04) — optional onClick opens the selectable-gallery preview overlay (default null = inert).
@Composable
private fun GalleryTile(
    url: String,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    corner: androidx.compose.ui.unit.Dp,
    onClick: ((String) -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(corner))
            .background(androidx.compose.ui.graphics.Color(0xFF2A2A2E))
            .then(if (onClick != null) Modifier.clickable { onClick(url) } else Modifier),
    ) {
        ai.appdna.sdk.core.NetworkImage(
            url = url,
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
    }
}

// Media-gallery v2 — continuous auto-scroll marquee. The track is duplicated and translated by exactly one
// copy-width per cycle, so the loop wraps seamlessly (no jump). Only composed when gallery_autoscroll == true.
@Composable
private fun MediaGalleryAutoScrollRow(
    images: List<String>,
    itemWidthDp: Double?,
    itemH: androidx.compose.ui.unit.Dp,
    cr: androidx.compose.ui.unit.Dp,
    spacing: androidx.compose.ui.unit.Dp,
    fill: Boolean,
    cycleSeconds: Double,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(itemH)
            .clip(RectangleShape),
    ) {
        val gap = if (fill) 0.dp else spacing
        val tileW = if (fill) maxWidth else (itemWidthDp ?: 140.0).dp
        val cornerDp = if (fill) 0.dp else cr
        // One copy = n tiles + n gaps; shifting by this aligns the 2nd copy onto the 1st → seamless.
        val copyWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) {
            ((tileW + gap) * images.size).toPx()
        }
        val transition = rememberInfiniteTransition(label = "gallery_marquee")
        val offsetX by transition.animateFloat(
            initialValue = 0f,
            targetValue = -copyWidthPx,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = (maxOf(cycleSeconds, 1.0) * 1000).toInt(), easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "gallery_offset",
        )
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.graphicsLayer { translationX = offsetX },
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            repeat(images.size * 2) { i ->
                GalleryTile(images[i % images.size], tileW, itemH, cornerDp)
            }
        }
    }
}

@Composable
private fun ImageBlock(block: ContentBlock) {
    // SPEC-401-A — iOS no-render guard. iOS draws nothing when the
    // url is missing/empty; Android previously fell into NetworkImage
    // with a null URL which still rendered a fixed-height placeholder
    // box. Match iOS behaviour.
    if (block.image_url.isNullOrBlank()) return

    val cr = (block.corner_radius ?: 0.0)
    val isCircle = cr >= 9999

    val shapeModifier = if (block.overflow == "visible") {
        Modifier.graphicsLayer {
            clip = false
            shape = if (isCircle) CircleShape else RoundedCornerShape(cr.dp)
        }
    } else {
        if (isCircle) Modifier.clip(CircleShape) else Modifier.clip(RoundedCornerShape(cr.dp))
    }

    // SPEC-401-A — image_fit. iOS toggles `.fit`/`.fill` when fit is
    // "contain"/"fit"; default is `.fill` (Crop). Match the toggle so
    // authored portrait photos are no longer distorted by the fixed
    // height + Crop combo.
    // SPEC-401-A R64 (Lens A P2 #1) — collapse to iOS's two-arm semantics
    // at ContentBlockRendererView.swift:327: `(image_fit == "contain" ||
    // image_fit == "fit") ? .fit : .fill`. Console schema's `"none"`
    // and `"cover"` both fall through to .fill on iOS; Android was
    // mapping them to `ContentScale.None` (intrinsic-pixel) and
    // `ContentScale.Inside`, producing visibly different layouts for
    // the same payload.
    // SPEC-419 (P3) — add the "fill" (stretch) and "none" (intrinsic) arms so they no longer
    // collapse to Crop. Mirrors the preview's literal objectFit values
    // (OnboardingStepPreview.tsx) + iOS styledImage.
    val fit = block.image_fit ?: ""
    val contentScale = when (fit) {
        "contain", "fit" -> androidx.compose.ui.layout.ContentScale.Fit
        "fill" -> androidx.compose.ui.layout.ContentScale.FillBounds
        "none" -> androidx.compose.ui.layout.ContentScale.None
        else -> androidx.compose.ui.layout.ContentScale.Crop
    }

    // SPEC-419 (P2) — aspect_ratio routed through field_config (JVM-255 ContentBlock budget); the
    // preview applies it (OnboardingStepPreview.tsx) and iOS now does too. Same 5-option mapping.
    val aspectRatio: Float? = when (block.field_config?.get("aspect_ratio") as? String) {
        "16:9" -> 16f / 9f
        "4:3" -> 4f / 3f
        "1:1" -> 1f
        "3:4" -> 3f / 4f
        "9:16" -> 9f / 16f
        else -> null
    }

    // SPEC-419 (P3) — image_position top/bottom routed through field_config; preview uses
    // objectPosition, iOS uses frame alignment. Default center.
    val imageAlignment = when (block.field_config?.get("image_position") as? String) {
        "top" -> androidx.compose.ui.Alignment.TopCenter
        "bottom" -> androidx.compose.ui.Alignment.BottomCenter
        else -> androidx.compose.ui.Alignment.Center
    }

    // SPEC-401-A R9 — iOS uses `.frame(maxHeight: imgHeight)` at
    // ContentBlockRendererView.swift:338,344. Android previously hard-set
    // `.height(...)` so portrait/landscape assets with `image_fit="contain"`
    // were forced to that exact pixel height instead of shrinking to
    // intrinsic size. `heightIn(max=)` matches iOS behaviour.
    // SPEC-419 — honour an explicit px element_width (e.g. a 15px leading-icon image inside a
    // synthesis-card row) instead of ALWAYS fillMaxWidth. A small icon nested in a row was filling
    // the row's allocated width (~half the card) and ballooning, then the card clipped it. With a
    // fixed px width set, size the image to it (+ Fit so the icon isn't cropped); else fill width.
    val explicitW = block.element_width?.takeIf { it.endsWith("px") }?.dropLast(2)?.toFloatOrNull()
    // SPEC-419 — a small authored width (≤40px) is a leading/card icon. The authored 15px renders too
    // tiny to read; the old unconstrained path rendered it ~half the card wide then CLIPPED it. Render
    // it at a legible SQUARE (floor 32dp, never above the authored size for genuinely-larger images)
    // with Fit so it's prominent yet never cropped or clipped. Larger fixed widths use the value as-is.
    val iconSize = explicitW?.let { if (it <= 40f) maxOf(it, block.height?.toFloat() ?: it, 32f) else null }
    val imgWidthMod = when {
        iconSize != null -> Modifier.width(iconSize.dp)
        explicitW != null -> Modifier.width(explicitW.dp)
        else -> Modifier.fillMaxWidth()
    }
    val imgHeightMax = if (iconSize != null) iconSize.dp else (block.height ?: 200.0).dp
    if (block.image_frame == "phone") {
        // EPIC-3 — phone mockup: dark bezel + dynamic-island notch, image fills the "screen".
        // SPEC-419 pass-13 — cap at 260dp wide + center, matching iOS phoneMockup
        // `.frame(maxWidth: 260)` (+ preview). Without the cap the bezel stretched
        // full device-width on Android.
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
        Box(
            modifier = Modifier
                .then(if (explicitW != null) imgWidthMod else Modifier.widthIn(max = 260.dp).fillMaxWidth())
                .clip(RoundedCornerShape(40.dp))
                .background(androidx.compose.ui.graphics.Color(0xFF101012))
                .padding(10.dp),
        ) {
            Box {
                ai.appdna.sdk.core.NetworkImage(
                    url = block.image_url,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(imgHeightMax)
                        .clip(RoundedCornerShape(30.dp))
                        .background(androidx.compose.ui.graphics.Color(0xFF2A2A2E)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    contentDescription = block.alt ?: "Image",
                )
                Box(
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .width(96.dp)
                        .height(26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(androidx.compose.ui.graphics.Color.Black),
                )
            }
        }
        }
        return
    }
    val aspectMod = if (aspectRatio != null) Modifier.aspectRatio(aspectRatio) else Modifier
    ai.appdna.sdk.core.NetworkImage(
        url = block.image_url,
        modifier = Modifier
            .then(imgWidthMod)
            .then(aspectMod)
            .heightIn(max = imgHeightMax)
            .then(shapeModifier),
        contentScale = if (explicitW != null) androidx.compose.ui.layout.ContentScale.Fit else contentScale,
        alignment = imageAlignment,
        // SPEC-401-A R57 (Lens A R57 #15, P3) — fall back to "Image" when
        // alt unset, matching iOS accessibilityLabel(block.alt ?? "Image")
        // at ContentBlockRendererView.swift:340. Was passing null — TalkBack
        // treats null contentDescription on an Image as "decorative" so the
        // image is silently skipped.
        contentDescription = block.alt ?: "Image",
    )
}

@Composable
private fun ButtonBlock(
    block: ContentBlock,
    onAction: (String) -> Unit,
    loc: ((String, String) -> String)? = null,
    // Mrozu QA (2026-08-04) — Flo consent CTA: step siblings + live inputs drive the consent-reactive bg.
    stepBlocks: List<ContentBlock> = emptyList(),
    inputValues: Map<String, Any> = emptyMap(),
    // Mrozu (Duolingo s20/s22) — when set (sound_button), tap runs this instead of
    // the flow-action routing below (e.g. play an audio clip).
    onClickOverride: (() -> Unit)? = null,
) {
    val text = block.text ?: "Continue"
    // SPEC-401-A R54 (Lens A R54 #4, P2) — 16→17sp matching iOS
    // .body.weight(.semibold) at ContentBlockRendererView.swift:395-396.
    val baseStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    val effectiveStyle = if (block.style != null) StyleEngine.applyTextStyle(baseStyle, block.style) else baseStyle
    val context = LocalContext.current
    val btnVariant = block.variant ?: "primary"
    // Mrozu QA (2026-08-04) — Flo consent CTA: `cta_enabled_bg_color` / `cta_disabled_bg_color` drive the
    // button background off whether the step's required fields (incl. a consent checkbox) are satisfied.
    // Reuses the SAME RequiredFieldGate the advance gate uses, so the CTA recolors reactively as the user
    // toggles consent. Both nil → plain `bg_color` (non-breaking). Parity with iOS.
    val ctaEnabledHex = block.field_config?.get("cta_enabled_bg_color") as? String
    val ctaDisabledHex = block.field_config?.get("cta_disabled_bg_color") as? String
    val bgColor = run {
        val fallback = block.bg_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1")
        if (ctaEnabledHex == null && ctaDisabledHex == null) {
            StyleEngine.parseColor(fallback)
        } else {
            val satisfied = RequiredFieldGate.evaluate(stepBlocks, inputValues).first
            StyleEngine.parseColor(
                if (satisfied) (ctaEnabledHex ?: fallback) else (ctaDisabledHex ?: ctaEnabledHex ?: fallback),
            )
        }
    }
    val txtColor = StyleEngine.parseColor(block.text_color ?: "#FFFFFF")
    val cornerRadius = (block.button_corner_radius ?: 12.0).dp
    val displayText = loc?.invoke("block.${block.id}.text", text) ?: text

    val onClick: () -> Unit = onClickOverride ?: {
        val action = block.action ?: "next"
        when (action) {
            "link" -> {
                // SPEC-070-A finalization Phase E — `link` action divergence.
                // iOS InAppBrowser.present(url) does NOT auto-advance —
                // user returns to the same step after closing the browser.
                // Android previously called `onAction("next")` after
                // launching the browser, double-jumping the user away from
                // the step. Removed the auto-advance to match iOS behavior.
                // Browser launch path uses ACTION_VIEW for now (Chrome Custom
                // Tabs would be a polish followup once androidx.browser dep
                // is added).
                block.action_value?.let { url ->
                    // SPEC-070-B PN row 18 (W11): config-driven URL — scheme-checked before the OS sees it.
                    ai.appdna.sdk.core.URLSafety.sanitized(url, context)?.let { uri ->
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (_: Exception) {}
                    }
                }
                // No auto-advance — mirrors iOS InAppBrowser.present semantics.
            }
            // SPEC-401-A R14 — was `onAction("next")` which rewrote the
            // action before reaching the canonical handleAction switch.
            // R13 added a `"permission" -> onNext(null)` branch in
            // OnboardingActivity (matching iOS OnboardingRenderer.swift
            // :1525-1529), but block-authored permission buttons (the
            // only path the console emits today) never reached it
            // because of this rewrite. Forwarding the original action
            // keeps the iOS-canonical behavior as the single source.
            //
            // Mrozu (alarmy s4.1) — forward the button's OWN `action_value` (the per-CTA permission
            // type, e.g. "alarm") colon-encoded so handleAction's pair-parser routes it to
            // emitPermissionAction's `actionValue`. iOS already forwards block.action_value directly
            // (ContentBlockRendererView.swift `onAction(block.action ?? "next", block.action_value)`);
            // Android dropped it here, so a per-CTA permission type resolved to null and only the
            // step-level permission_type worked. Blank/absent → bare "permission" (unchanged).
            "permission" -> onAction(
                block.action_value?.takeIf { it.isNotBlank() }?.let { "permission:$it" } ?: "permission",
            )
            else -> onAction(action)
        }
    }

    // SPEC-089d §6.5: Pressed style — collect interaction source for scale/opacity
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // SPEC-401-A R26 — match iOS ContentBlockTypes.swift:650 which wraps
    // pressed-style scale/opacity in `.animation(.easeInOut(duration: 0.1),
    // value: isPressed)` — smooth 100ms transition. Android was reading raw
    // Float values directly into graphicsLayer, producing a one-frame snap
    // (visible "pop" on press/release). animateFloatAsState(tween(100))
    // gives the same easeInOut interpolation.
    val pressedScale by animateFloatAsState(
        targetValue = if (isPressed) (block.pressed_style?.scale ?: 0.97).toFloat() else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "pressedScale",
    )
    val pressedAlpha by animateFloatAsState(
        targetValue = if (isPressed) (block.pressed_style?.opacity ?: 0.9).toFloat() else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "pressedAlpha",
    )
    val pressedModifier = if (block.pressed_style != null) {
        Modifier
            .graphicsLayer(scaleX = pressedScale, scaleY = pressedScale, alpha = pressedAlpha)
    } else Modifier

    // EPIC-6 — apply authored button_height (resize the button itself) instead of only a 52dp floor.
    // Without this the console "Size" control had no effect on the CTA (the field existed but was never read).
    val heightMod: Modifier = block.button_height?.let { Modifier.height(it.dp) }
        ?: Modifier.defaultMinSize(minHeight = 52.dp)

    // Gap 5: Gradient background support
    val shape = RoundedCornerShape(cornerRadius)
    val gradient = block.block_style?.background_gradient
    val gradientModifier = if (gradient != null) {
        val rads = gradient.angle * Math.PI / 180.0
        val startX = (0.5 - sin(rads) / 2).toFloat()
        val startY = (0.5 + cos(rads) / 2).toFloat()
        val endX = (0.5 + sin(rads) / 2).toFloat()
        val endY = (0.5 - cos(rads) / 2).toFloat()
        Modifier.background(
            Brush.linearGradient(
                colors = listOf(StyleEngine.parseColor(gradient.start), StyleEngine.parseColor(gradient.end)),
                start = Offset(startX * 1000f, startY * 1000f),
                end = Offset(endX * 1000f, endY * 1000f),
            ),
            shape,
        )
    } else null

    // Gap 6: Button content with optional icon_emoji / image_url
    @Composable
    fun ButtonContent(textColor: Color) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // SPEC-401-A — iOS shows BOTH icon_emoji AND image_url
            // when both are set (ContentBlockRendererView.swift:383-394).
            // Android previously gated image_url on icon_emoji being
            // null/empty, so authored buttons with both lost the image.
            block.icon_emoji?.let { emoji ->
                if (emoji.isNotEmpty()) {
                    Text(emoji, modifier = Modifier.padding(end = 8.dp))
                }
            }
            if (!block.image_url.isNullOrEmpty()) {
                ai.appdna.sdk.core.NetworkImage(
                    url = block.image_url,
                    modifier = Modifier.size(20.dp).padding(end = 8.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            // SPEC-070-A finalization OB-3 — button text color precedence.
            // iOS: when `block.style.color` is set, it WINS over `text_color`
            // (`StyleEngine.swift:148-153` `applyTextStyle` foregroundColor
            // resolves to `s.color ?? .primary` — `.primary` then falls back
            // to the parent button's `text_color` via SwiftUI's foregroundColor
            // inheritance). Compose's contract is the opposite: an explicit
            // `color` parameter overrides `style.color`, so we previously
            // forced `text_color` even when console authored a `style.color`
            // override. Bake the resolved color into `effectiveStyle.color`
            // (priority: block.style.color → block.text_color), and drop the
            // explicit `color` param so style wins.
            val resolvedTextColor = block.style?.color
                ?.let { StyleEngine.parseColor(it) }
                ?: textColor
            Text(
                // SPEC-401-A R63 (Lens A Gap 3 Site A, P2) — apply
                // style.text_transform (uppercase/lowercase) matching iOS
                // ContentBlockRendererView.swift:395-397 `.applyTextStyle(
                // block.style)`. Compose TextStyle has no equivalent;
                // applyTransform helper at StyleEngine.kt:52 wraps the
                // string. R17 covered Heading/Text/Timeline; ButtonBlock
                // was missed.
                text = block.style.applyTransform(displayText),
                style = effectiveStyle.copy(color = resolvedTextColor),
            )
        }
    }

    when (btnVariant) {
        "outline" -> {
            // SPEC-089d §3.18: Outline variant — transparent bg, colored border + text
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth().then(heightMod).then(pressedModifier),
                shape = shape,
                border = androidx.compose.foundation.BorderStroke(1.5.dp, bgColor),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = bgColor),
                interactionSource = interactionSource,
            ) {
                ButtonContent(textColor = bgColor)
            }
        }
        "text" -> {
            TextButton(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth().then(heightMod).then(pressedModifier),
                shape = shape,
                interactionSource = interactionSource,
            ) {
                ButtonContent(textColor = bgColor)
            }
        }
        else -> {
            // primary / secondary — filled button
            if (gradientModifier != null) {
                // Gradient button: use Box with gradient background + clickable
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(heightMod)
                        .then(pressedModifier)
                        .clip(shape)
                        .then(gradientModifier)
                        .clickable { onClick() },
                    contentAlignment = Alignment.Center,
                ) {
                    ButtonContent(textColor = txtColor)
                }
            } else {
                Button(
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth().then(heightMod).then(pressedModifier),
                    shape = shape,
                    colors = ButtonDefaults.buttonColors(containerColor = bgColor),
                    // SPEC-419 — flat like iOS/SwiftUI buttons. Material's default ~1dp elevation
                    // casts a drop shadow that, on dark gradient steps, reads as a dark band below
                    // the CTA ("gap below the button" vs iOS where the gradient flows flat under it).
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 0.dp,
                        pressedElevation = 0.dp,
                        focusedElevation = 0.dp,
                        hoveredElevation = 0.dp,
                    ),
                    interactionSource = interactionSource,
                ) {
                    ButtonContent(textColor = txtColor)
                }
            }
        }
    }
}

/**
 * Mrozu (Duolingo s20/s22) — sound_button: a CTA-style button (reuses ALL of
 * ButtonBlock's styling) that plays a remote audio clip (mp3/wav/aac) from
 * `block.audio_url` on tap. When `block.autoplay == true` the clip plays as the
 * block first appears. Playback is routed through the shared AudioPlayer helper.
 */
@Composable
private fun SoundButtonBlock(
    block: ContentBlock,
    onAction: (String) -> Unit,
    loc: ((String, String) -> String)? = null,
    stepBlocks: List<ContentBlock> = emptyList(),
    inputValues: Map<String, Any> = emptyMap(),
) {
    val context = LocalContext.current
    LaunchedEffect(block.id) {
        if (block.autoplay == true) {
            ai.appdna.sdk.core.AudioPlayer.play(context, block.audio_url)
        }
    }
    // Stop autoplayed audio when the block leaves composition (step change /
    // dismiss); AudioPlayer.stop() is idempotent and safe when idle. Mirrors
    // iOS soundButtonBlock's .onDisappear.
    DisposableEffect(block.id) {
        onDispose { ai.appdna.sdk.core.AudioPlayer.stop() }
    }
    ButtonBlock(
        block, onAction, loc, stepBlocks, inputValues,
        onClickOverride = { ai.appdna.sdk.core.AudioPlayer.play(context, block.audio_url) },
    )
}

/** EPIC-11 — OTP / code-input: a row of N single-character boxes (verification codes). The entered value
 * lives in inputValues[field_id]; `field_config.otp_value` seeds a display value (used by snapshots/preview).
 * Filled boxes show the digit + accent border; the next empty box is the active box (accent border). */
@Composable
private fun OtpInputBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
    onInteract: (String, String, String?) -> Unit = { _, _, _ -> },
) {
    val length = (block.field_config?.get("otp_length") as? Number)?.toInt()?.coerceIn(2, 10) ?: 6
    val fieldId = block.field_id ?: block.id
    val accent = StyleEngine.parseColor(block.active_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    val boxBg = StyleEngine.parseColor(block.bg_color ?: "#1F2937")
    // Mrozu QA (2026-08-04): box border/text were hardcoded (accent/gray + white). When set, border_color
    // overrides the resting border (active box keeps the accent focus ring); text_color overrides the digit.
    val borderOverride = block.border_color?.let { StyleEngine.parseColor(it) }
    val digitColor = StyleEngine.parseColor(block.text_color ?: "#FFFFFF")

    // SPEC-419 STEP-2 — local editable state seeded from prior input / `otp_value` preview so re-entry +
    // snapshots keep the code. A hidden BasicTextField captures the number keyboard; tapping the boxes
    // focuses it; on reaching `otp_length` digits we write inputValues[fid] and fire ("otp_entered", code).
    var entered by remember(fieldId) {
        mutableStateOf(
            (inputValues[fieldId] as? String)
                ?: (block.field_config?.get("otp_value") as? String)
                ?: "",
        )
    }
    val focusRequester = remember { FocusRequester() }

    Box(modifier = Modifier.fillMaxWidth()) {
        // Hidden keyboard capture — 1dp + near-invisible so it never affects layout/snapshots.
        BasicTextField(
            value = entered,
            onValueChange = { newVal ->
                val filtered = newVal.filter { it.isDigit() }.take(length)
                entered = filtered
                if (filtered.length == length) {
                    inputValues[fieldId] = filtered
                    onInteract(block.id, "otp_entered", filtered)
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.size(1.dp).alpha(0.01f).focusRequester(focusRequester),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { focusRequester.requestFocus() },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (i in 0 until length) {
                val ch = entered.getOrNull(i)
                val isActive = i == entered.length
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(boxBg)
                        .border(
                            width = if (isActive || ch != null) 2.dp else 1.dp,
                            color = if (isActive) accent
                                else if (ch != null) (borderOverride ?: accent.copy(alpha = 0.5f))
                                else (borderOverride?.copy(alpha = 0.35f) ?: Color.Gray.copy(alpha = 0.35f)),
                            shape = RoundedCornerShape(10.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (ch != null) {
                        Text(ch.toString(), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = digitColor)
                    }
                }
            }
        }
    }
}

/** EPIC-11 — warning/info banner: a tinted rounded card with a leading icon + message. `field_config.
 * banner_variant` (warning|error|info|success) picks the accent + default icon; `banner_icon` overrides it. */
@Composable
private fun WarningBannerBlock(block: ContentBlock, loc: ((String, String) -> String)? = null) {
    val variant = (block.field_config?.get("banner_variant") as? String) ?: "warning"
    val (accentHex, defaultIcon) = when (variant) {
        "error" -> "#EF4444" to "⛔"
        "info" -> "#3B82F6" to "ℹ️"
        "success" -> "#10B981" to "✓"
        else -> "#F59E0B" to "⚠️"
    }
    val accent = StyleEngine.parseColor(block.active_color ?: accentHex)
    val icon = (block.field_config?.get("banner_icon") as? String) ?: defaultIcon
    val text = loc?.invoke("block.${block.id}.text", block.text ?: "") ?: (block.text ?: "")
    // Mrozu QA (2026-08-04): bg_color/text_color were uneditable. When set they override the
    // accent-tinted background / white message text; unset keeps the variant defaults (parity w/ iOS).
    val bgOverride = block.bg_color?.let { StyleEngine.parseColor(it) }
    val textColor = StyleEngine.parseColor(block.text_color ?: "#FFFFFF")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgOverride ?: accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(icon, fontSize = 18.sp)
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = textColor)
    }
}

/** EPIC-11 — password-strength meter: 4 segment bars filled by `field_config.strength_level` (0-4) with a
 * red→amber→yellow→green ramp + a label (Weak/Fair/Good/Strong, overridable via `strength_label`). */
@Composable
private fun PasswordStrengthBlock(block: ContentBlock) {
    val level = (block.field_config?.get("strength_level") as? Number)?.toInt()?.coerceIn(0, 4) ?: 0
    val (colorHex, defLabel) = when (level) {
        1 -> "#EF4444" to "Weak"
        2 -> "#F59E0B" to "Fair"
        3 -> "#EAB308" to "Good"
        4 -> "#10B981" to "Strong"
        else -> "#6B7280" to ""
    }
    val accent = StyleEngine.parseColor(block.active_color ?: colorHex)
    val label = (block.field_config?.get("strength_label") as? String) ?: defLabel
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0 until 4) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (i < level) accent else Color(0xFF374151)),
                )
            }
        }
        if (label.isNotEmpty()) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = accent)
        }
    }
}

/** EPIC-11 — speech bubble (mascot dialogue): a rounded card + a downward tail triangle. `field_config.
 * bubble_tail` (left|center|right, default left) positions the tail; bg_color/text_color style it. */
@Composable
private fun SpeechBubbleBlock(block: ContentBlock, loc: ((String, String) -> String)? = null) {
    val bubbleColor = StyleEngine.parseColor(block.bg_color ?: "#FFFFFF")
    val textColor = StyleEngine.parseColor(block.text_color ?: "#111827")
    val tailPos = (block.field_config?.get("bubble_tail") as? String) ?: "left"
    // Mrozu QA — bubble interior font family (bubble_font_family; null → system) + tail geometry
    // (tail_width/tail_length; default 18×9). Parity w/ iOS speechBubbleBlock + console preview.
    val bubbleFontFamily = ai.appdna.sdk.core.FontResolver.resolve(block.field_config?.get("bubble_font_family") as? String)
    val tailWidth = ((block.field_config?.get("tail_width") as? Number)?.toFloat() ?: 18f)
    val tailLength = ((block.field_config?.get("tail_length") as? Number)?.toFloat() ?: 9f)
    val text = loc?.invoke("block.${block.id}.text", block.text ?: "") ?: (block.text ?: "")
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(bubbleColor)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = textColor, fontFamily = bubbleFontFamily, lineHeight = 20.sp)
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            Canvas(
                modifier = Modifier
                    .align(
                        when (tailPos) {
                            "center" -> Alignment.TopCenter
                            "right" -> Alignment.TopEnd
                            else -> Alignment.TopStart
                        },
                    )
                    .padding(start = if (tailPos == "left") 24.dp else 0.dp, end = if (tailPos == "right") 24.dp else 0.dp)
                    .size(width = tailWidth.dp, height = tailLength.dp),
            ) {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close()
                }
                drawPath(p, bubbleColor)
            }
        }
    }
}

/** EPIC-11 — quiz feedback panel (Duolingo correct/wrong): tinted panel + circled icon + headline + detail.
 * `field_config.feedback_state` (correct|wrong|info) picks accent+icon+default headline; `feedback_detail`
 * is the secondary line; the `text` is the headline. */
@Composable
private fun FeedbackPanelBlock(block: ContentBlock, loc: ((String, String) -> String)? = null) {
    val state = (block.field_config?.get("feedback_state") as? String) ?: "correct"
    val (accentHex, icon, defHead) = when (state) {
        "wrong" -> Triple("#EF4444", "✗", "Not quite")
        "info" -> Triple("#3B82F6", "ℹ", "Heads up")
        else -> Triple("#10B981", "✓", "Great job!")
    }
    val accent = StyleEngine.parseColor(block.active_color ?: accentHex)
    // Mrozu QA (2026-08-04) — duolingo above-CTA feedback: `feedback_bg_color` overrides the tinted panel
    // background; `feedback_graphic_url` swaps the built-in ✓/✗ glyph for a custom image. Both default nil →
    // identical to the existing accent-tinted glyph panel (non-breaking). The runtime correct/wrong EVENT that
    // flips `feedback_state` is host-driven behavioral (deferred); this is the static/config render + styling.
    val panelBg = (block.field_config?.get("feedback_bg_color") as? String)?.let { StyleEngine.parseColor(it) }
        ?: accent.copy(alpha = 0.15f)
    val graphicUrl = (block.field_config?.get("feedback_graphic_url") as? String)
    val headline = loc?.invoke("block.${block.id}.text", block.text ?: defHead) ?: (block.text ?: defHead)
    val detail = (block.field_config?.get("feedback_detail") as? String)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(panelBg)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(accent),
            contentAlignment = Alignment.Center,
        ) {
            if (!graphicUrl.isNullOrEmpty()) {
                ai.appdna.sdk.core.NetworkImage(
                    url = graphicUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            } else {
                Text(icon, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(headline, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = accent)
            if (!detail.isNullOrEmpty()) {
                Text(detail, fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}

/** EPIC-11 — session summary screen (Duolingo end-of-lesson stats): optional headline + a 2-column grid of
 * stat cards. `field_config.summary_stats` = [{value, label, color?}]; each card shows a big colored value +
 * a muted label. */
@Composable
private fun SummaryScreenBlock(block: ContentBlock, loc: ((String, String) -> String)? = null) {
    val statsRaw = (block.field_config?.get("summary_stats") as? List<*>) ?: emptyList<Any>()
    val stats = statsRaw.mapNotNull { it as? Map<*, *> }
    val headline = loc?.invoke("block.${block.id}.text", block.text ?: "") ?: (block.text ?: "")
    val defaultAccent = ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"
    // Mrozu QA (2026-08-04): cards/headline were hardcoded (#1F2937 bg, white text, center, 2-col).
    // bg_color = card bg, text_color = headline + label, summary_align = headline align,
    // stats_layout = horizontal (2-col, default) | vertical (single full-width column). Parity w/ iOS.
    val cardBg = block.bg_color?.let { StyleEngine.parseColor(it) } ?: Color(0xFF1F2937)
    val textColor = StyleEngine.parseColor(block.text_color ?: "#FFFFFF")
    val headlineAlign = when ((block.field_config?.get("summary_align") as? String)) {
        "left" -> TextAlign.Start; "right" -> TextAlign.End; else -> TextAlign.Center
    }
    val perRow = if ((block.field_config?.get("stats_layout") as? String) == "vertical") 1 else 2
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (headline.isNotEmpty()) {
            Text(
                headline,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                textAlign = headlineAlign,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        stats.chunked(perRow).forEach { rowStats ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowStats.forEach { m ->
                    // Coerce — a numeric stat value (Int/Double) cast `as? String` would blank the card.
                    val value = m["value"]?.toString() ?: ""
                    val label = m["label"]?.toString() ?: ""
                    val color = StyleEngine.parseColor((m["color"] as? String) ?: defaultAccent)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(cardBg)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color)
                        Text(label, fontSize = 13.sp, color = textColor.copy(alpha = 0.7f))
                    }
                }
                if (perRow == 2 && rowStats.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/** EPIC-11 — press-and-hold-to-confirm: a pill that fills left→right as the user holds. `field_config.
 * hold_progress` (0-1) is the static fill fraction (runtime animates it); active_color = fill color. */
@Composable
private fun PressHoldConfirmBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
    loc: ((String, String) -> String)? = null,
    onInteract: (String, String, String?) -> Unit = { _, _, _ -> },
) {
    val accent = StyleEngine.parseColor(block.active_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    // Mrozu QA — track background (bg_color; default #1F2937), filled-state label (confirm_text; default
    // "✓"), optional above/below labels (label_above/label_below) in text_color (default #111827).
    // Parity w/ iOS PressHoldConfirmBlockView + console preview.
    val track = StyleEngine.parseColor(block.bg_color ?: "#1F2937")
    val text = loc?.invoke("block.${block.id}.text", block.text ?: "Hold to confirm") ?: (block.text ?: "Hold to confirm")
    val confirmText = (block.field_config?.get("confirm_text") as? String) ?: "✓"
    val labelAbove = block.field_config?.get("label_above") as? String
    val labelBelow = block.field_config?.get("label_below") as? String
    val labelColor = StyleEngine.parseColor(block.text_color ?: "#111827")
    val fieldId = block.field_id ?: block.id
    val holdMs = 1200

    // SPEC-419 STEP-2 — a pill that fills left→right while held. `pointerInput` sets `holding`; a
    // LaunchedEffect drives the fill Animatable while held (and rewinds on early release). On a full
    // hold we write inputValues[fid]=true and fire ("confirmed", null). `hold_progress` seeds the static
    // preview fill; the seed is NOT auto-rewound until the user actually presses (parity with iOS).
    val seeded = ((block.field_config?.get("hold_progress") as? Number)?.toDouble() ?: 0.0)
        .coerceIn(0.0, 1.0).toFloat()
    val progress = remember(fieldId) { Animatable(seeded) }
    var holding by remember(fieldId) { mutableStateOf(false) }
    var everHeld by remember(fieldId) { mutableStateOf(false) }
    var confirmed by remember(fieldId) { mutableStateOf(false) }

    LaunchedEffect(holding) {
        if (confirmed) return@LaunchedEffect
        if (holding) {
            val remaining = ((1f - progress.value) * holdMs).toInt().coerceAtLeast(1)
            progress.animateTo(1f, tween(remaining, easing = LinearEasing))
            if (progress.value >= 1f) {
                confirmed = true
                inputValues[fieldId] = true
                onInteract(block.id, "confirmed", null)
            }
        } else if (everHeld) {
            progress.animateTo(0f, tween(200))
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!labelAbove.isNullOrEmpty()) {
            Text(labelAbove, fontSize = 14.sp, color = labelColor)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(track)
                .pointerInput(confirmed) {
                    if (confirmed) return@pointerInput
                    detectTapGestures(onPress = {
                        everHeld = true
                        holding = true
                        tryAwaitRelease()
                        holding = false
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(progress.value)
                    .background(accent),
            )
            Text(if (confirmed) confirmText else text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
        if (!labelBelow.isNullOrEmpty()) {
            Text(labelBelow, fontSize = 14.sp, color = labelColor)
        }
    }
}

/** EPIC-11 — Health connect: a tappable card (icon + title + subtitle + chevron/✓). PLATFORM-FIXED to Google
 * Fit 🏃 on Android (iOS is Apple Health); the provider is NOT author-selectable — `health_provider` is ignored.
 * `field_config.health_subtitle` sets the subtitle; `connected` shows a green check; the native connect flow is
 * host-driven via onAction("health_connect"). */
@Composable
private fun HealthConnectBlock(
    block: ContentBlock,
    onAction: (String) -> Unit,
    loc: ((String, String) -> String)? = null,
    onInteract: (String, String, String?) -> Unit = { _, _, _ -> },
) {
    // EPIC-11 — provider is PLATFORM-FIXED: Android always shows Google Fit (Apple Health is iOS-only).
    val connected = (block.field_config?.get("connected") as? Boolean) ?: false
    val icon = "🏃"
    val defLabel = "Connect Google Fit"
    val iconBgHex = "#34A853"
    val label = loc?.invoke("block.${block.id}.text", block.text ?: defLabel) ?: (block.text ?: defLabel)
    val subtitle = (block.field_config?.get("health_subtitle") as? String) ?: "Sync steps, workouts & vitals"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1F2937))
            // SPEC-419 STEP-2 — keep the host onAction("health_connect") for the native connect flow AND
            // fire onInteract so the delegate can push backend state. Provider is "google_fit" (iOS: "apple_health").
            .clickable {
                onAction("health_connect")
                onInteract(block.id, "health_connect", "google_fit")
            }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(StyleEngine.parseColor(iconBgHex).copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, fontSize = 22.sp)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(subtitle, fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
        }
        if (connected) {
            Text("✓", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = StyleEngine.parseColor("#10B981"))
        } else {
            Text("›", fontSize = 26.sp, color = Color.White.copy(alpha = 0.5f))
        }
    }
}

/** EPIC-11 — interactive footer: a dark-mode capsule toggle + a language switcher pill. `field_config.dark_mode`
 * (bool) + `language` (label). Custom capsule switch (not the native widget) so both platforms pixel-match. */
@Composable
private fun SettingsFooterBlock(
    block: ContentBlock,
    onAction: (String) -> Unit,
    onInteract: (String, String, String?) -> Unit = { _, _, _ -> },
) {
    val darkMode = (block.field_config?.get("dark_mode") as? Boolean) ?: false
    val language = (block.field_config?.get("language") as? String) ?: "English"
    val accent = StyleEngine.parseColor(block.active_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("🌙", fontSize = 18.sp)
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (darkMode) accent else Color.White.copy(alpha = 0.22f))
                    // SPEC-419 STEP-2 — value is the intended NEXT state (matches iOS String(!darkMode)).
                    .clickable {
                        onAction("toggle_dark_mode")
                        onInteract(block.id, "toggle_dark_mode", (!darkMode).toString())
                    },
                contentAlignment = if (darkMode) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(modifier = Modifier.padding(3.dp).size(22.dp).clip(CircleShape).background(Color.White))
            }
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1F2937))
                .clickable {
                    onAction("switch_language")
                    onInteract(block.id, "switch_language", language)
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("🌐", fontSize = 15.sp)
            Text(language, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
            Text("▾", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
        }
    }
}

/** EPIC-11 — memory / pair-match grid (Duolingo): square cards in N columns, each face-down (accent "?"),
 * face-up (white + symbol), or matched (green + symbol). `field_config.match_columns` + `match_cards`=
 * [{symbol, state: down|up|matched}]. */
@Composable
private fun MemoryMatchBlock(
    block: ContentBlock,
    onInteract: (String, String, String?) -> Unit = { _, _, _ -> },
) {
    val cols = (block.field_config?.get("match_columns") as? Number)?.toInt()?.coerceIn(2, 5) ?: 3
    val cardsRaw = (block.field_config?.get("match_cards") as? List<*>) ?: emptyList<Any>()
    val cards = remember(cardsRaw) { cardsRaw.mapNotNull { it as? Map<*, *> } }
    val accent = StyleEngine.parseColor(block.active_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    val matched = StyleEngine.parseColor("#10B981")

    // SPEC-419 STEP-2 — local optimistic grid. Tap flips a face-down card; two up resolve to match (stay
    // up, fire ("pair_matched", symbol)) or mismatch (flip back after ~0.7s). All matched → ("completed").
    // Initial card `state`s seed the grid (preview parity). Replaces the old onAction("flip_card").
    val symbols = remember(cards) { cards.map { (it["symbol"] as? String) ?: "" } }
    val states = remember(cards) { cards.map { (it["state"] as? String) ?: "down" }.toMutableStateList() }
    val flippedUp = remember(cards) { mutableStateListOf<Int>() }
    var busy by remember(cards) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun flip(idx: Int) {
        if (busy || idx >= states.size || states[idx] != "down") return
        states[idx] = "up"
        flippedUp.add(idx)
        if (flippedUp.size < 2) return
        val a = flippedUp[0]
        val b = flippedUp[1]
        if (a < symbols.size && b < symbols.size && symbols[a] == symbols[b]) {
            states[a] = "matched"
            states[b] = "matched"
            flippedUp.clear()
            onInteract(block.id, "pair_matched", symbols[a])
            if (states.all { it == "matched" }) {
                onInteract(block.id, "completed", null)
            }
        } else {
            busy = true
            scope.launch {
                delay(700)
                if (a < states.size) states[a] = "down"
                if (b < states.size) states[b] = "down"
                flippedUp.clear()
                busy = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cards.indices.chunked(cols).forEach { rowIdxs ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowIdxs.forEach { idx ->
                    val symbol = symbols.getOrElse(idx) { "" }
                    val state = states.getOrElse(idx) { "down" }
                    val bg = when (state) {
                        "up" -> Color.White
                        "matched" -> matched.copy(alpha = 0.18f)
                        else -> accent.copy(alpha = 0.16f)
                    }
                    val border = when (state) {
                        "up" -> accent
                        "matched" -> matched
                        else -> accent.copy(alpha = 0.4f)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(bg)
                            .border(2.dp, border, RoundedCornerShape(12.dp))
                            .clickable { flip(idx) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state == "down") {
                            Text("?", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = accent)
                        } else {
                            Text(symbol, fontSize = 28.sp)
                        }
                    }
                }
                repeat(cols - rowIdxs.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

/** Per-month descriptor for the (possibly multi-month) calendar grid. */
private data class CalMonthDesc(
    val index: Int,
    val label: String,
    val daysInMonth: Int,
    val startOffset: Int,
    val year: Int,
    val month: Int, // 1..12
    val today: Int, // today's day for this month, -1 if none
)

private val CAL_MONTH_NAMES = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

private fun calDaysIn(year: Int, month: Int): Int {
    val table = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    val m = month.coerceIn(1, 12)
    if (m == 2 && ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0)) return 29
    return table[m - 1]
}

private fun calAddMonths(year: Int, month: Int, delta: Int): Pair<Int, Int> {
    val total = (month - 1) + delta
    return Pair(year + total / 12, total % 12 + 1)
}

/** Parse "June 2026" → (2026, 6). Falls back to (2026, 6) so all surfaces agree. */
private fun calParseBaseMonth(label: String): Pair<Int, Int> {
    var month = 6
    var year = 2026
    label.lowercase().split(" ").forEach { p ->
        if (p.isBlank()) return@forEach
        val idx = CAL_MONTH_NAMES.indexOfFirst { it.lowercase().startsWith(p) || p.startsWith(it.lowercase()) }
        if (idx >= 0) {
            month = idx + 1
        } else {
            p.toIntOrNull()?.let { if (it in 1901..2999) year = it }
        }
    }
    return Pair(year, month)
}

private fun calIso(y: Int, m: Int, d: Int): String = "%04d-%02d-%02d".format(y, m, d)

/** Month calendar (Flo). Renders `months_shown` (default 1) consecutive month grids stacked vertically.
 * SINGLE mode (`range_selectable` false): tapping an in-month day highlights it. For a single displayed month this
 * preserves the legacy contract — inputValues[fid]=day (Int) and ("day_selected", String(day)) — and
 * `selected_days`/`today` seed the first month. For multi-month single-select it writes the ISO date.
 * RANGE mode (`range_selectable` true): tapping two dates selects an inclusive range and persists
 * inputValues[fid] = {"start":"yyyy-MM-dd","end":"yyyy-MM-dd"}, firing ("range_selected", "start..end").
 * NOTE (deferred, needs device verification): continuous vertical scroll paging + drag-to-select the range are not
 * implemented — this renders all N months stacked and uses tap-two-dates selection. */
@Composable
private fun CalendarMonthBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
    onInteract: (String, String, String?) -> Unit = { _, _, _ -> },
) {
    val cfg = block.field_config
    val fieldId = block.field_id ?: block.id
    val monthLabel = (cfg?.get("month_label") as? String) ?: "June 2026"
    val daysInMonth = ((cfg?.get("days_in_month") as? Number)?.toInt() ?: 30).coerceIn(0, 31)
    val startOffset = ((cfg?.get("start_offset") as? Number)?.toInt() ?: 0).coerceIn(0, 6)
    val selectedDays = (cfg?.get("selected_days") as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
    val today = (cfg?.get("today") as? Number)?.toInt() ?: -1
    val monthsShown = ((cfg?.get("months_shown") as? Number)?.toInt() ?: 1).coerceIn(1, 12)
    val rangeSelectable = (cfg?.get("range_selectable") as? Boolean) ?: false
    val accent = StyleEngine.parseColor(block.active_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    val weekdays = listOf("S", "M", "T", "W", "T", "F", "S")

    // Build the month descriptors. Month 0 honors authored days/offset/today; later months continue the weekday
    // flow ((prevOffset + prevDays) % 7) and use real day counts for the parsed base month.
    val (baseYear, baseMonth) = calParseBaseMonth(monthLabel)
    val months = remember(monthLabel, daysInMonth, startOffset, today, monthsShown) {
        val list = mutableListOf<CalMonthDesc>()
        var prevOffset = startOffset
        var prevDays = daysInMonth
        for (i in 0 until monthsShown) {
            if (i == 0) {
                list.add(CalMonthDesc(0, monthLabel, daysInMonth, startOffset, baseYear, baseMonth, today))
            } else {
                val (yi, mi) = calAddMonths(baseYear, baseMonth, i)
                val di = calDaysIn(yi, mi)
                val off = (prevOffset + prevDays) % 7
                list.add(CalMonthDesc(i, "${CAL_MONTH_NAMES[mi - 1]} $yi", di, off, yi, mi, -1))
                prevOffset = off
                prevDays = di
            }
        }
        list
    }

    // SPEC-419 STEP-2 / Mrozu Flo s16 — selection state.
    var selectedDay by remember(fieldId) { mutableStateOf<Int?>(null) }   // legacy single-month day number
    var selectedIso by remember(fieldId) { mutableStateOf<String?>(null) } // multi-month single-select ISO
    var rangeStart by remember(fieldId) { mutableStateOf<String?>(null) }
    var rangeEnd by remember(fieldId) { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        months.forEach { m ->
            val rows = (m.startOffset + m.daysInMonth + 6) / 7
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(m.label, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Row(modifier = Modifier.fillMaxWidth()) {
                    weekdays.forEach { wd ->
                        Text(wd, modifier = Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.5f), textAlign = TextAlign.Center)
                    }
                }
                for (r in 0 until rows) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        for (c in 0 until 7) {
                            val day = r * 7 + c - m.startOffset + 1
                            val inMonth = day in 1..m.daysInMonth
                            val isoStr = if (inMonth) calIso(m.year, m.month, day) else ""
                            val isRangeStart = rangeSelectable && rangeStart != null && isoStr == rangeStart
                            val isRangeEnd = rangeSelectable && rangeEnd != null && isoStr == rangeEnd
                            val inRange = rangeSelectable && rangeStart != null && rangeEnd != null && isoStr > rangeStart!! && isoStr < rangeEnd!!
                            val singleSel = when {
                                rangeSelectable || !inMonth -> false
                                monthsShown <= 1 -> (m.index == 0 && day in selectedDays) || day == selectedDay
                                else -> selectedIso == isoStr
                            }
                            val isSelected = isRangeStart || isRangeEnd || singleSel
                            val isToday = inMonth && day == m.today
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .then(
                                        if (inMonth) Modifier.clickable {
                                            if (rangeSelectable) {
                                                if (rangeStart == null || rangeEnd != null) {
                                                    rangeStart = isoStr
                                                    rangeEnd = null
                                                    onInteract(block.id, "day_selected", isoStr)
                                                } else {
                                                    var s = rangeStart!!
                                                    var e = isoStr
                                                    if (e < s) { val t = s; s = e; e = t }
                                                    rangeStart = s
                                                    rangeEnd = e
                                                    inputValues[fieldId] = mapOf("start" to s, "end" to e)
                                                    onInteract(block.id, "range_selected", "$s..$e")
                                                }
                                            } else if (monthsShown <= 1) {
                                                selectedDay = day
                                                inputValues[fieldId] = day
                                                onInteract(block.id, "day_selected", day.toString())
                                            } else {
                                                selectedIso = isoStr
                                                inputValues[fieldId] = isoStr
                                                onInteract(block.id, "day_selected", isoStr)
                                            }
                                        } else Modifier,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (inMonth) {
                                    if (inRange) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(34.dp)
                                                .background(accent.copy(alpha = 0.22f)),
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(if (isSelected) accent else Color.Transparent)
                                            .then(if (isToday && !isSelected) Modifier.border(1.5.dp, accent, CircleShape) else Modifier),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            "$day",
                                            fontSize = 15.sp,
                                            fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.9f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListBlock(block: ContentBlock, loc: ((String, String) -> String)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        block.items?.forEachIndexed { index, item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (block.list_style) {
                    // SPEC-401-A R44 — theme-adaptive secondary color
                    // (was hardcoded Color.Gray). iOS uses .secondary
                    // (ContentBlockRendererView.swift:459).
                    // SPEC-401-A R52 (Lens A R51 #19, P3) — explicit 15sp matching
                    // iOS .subheadline.weight(.semibold) at
                    // ContentBlockRendererView.swift:457-459.
                    "numbered" -> Text("${index + 1}.", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    // SPEC-401-A \u2014 render `check` marker as Material
                    // CheckCircle icon (mirrors iOS `checkmark.circle.fill`).
                    // Was a plain Unicode glyph at 16sp which looked like
                    // a typo on Android.
                    // SPEC-401-A R6 — match iOS hardcoded #6366F1
                    // indigo (ContentBlockRendererView.swift:460-463).
                    // iOS ignores text_color on the check marker;
                    // Android previously defaulted to #22C55E green
                    // and accepted text_color, so the same authored
                    // payload rendered different colors across
                    // platforms.
                    // SPEC-419 pass-15 #5 — honor authored check_color (editor default green #22C55E); was hardcoded brandAccent.
                    "check" -> Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = StyleEngine.parseColor(block.check_color ?: "#22C55E"),
                        modifier = Modifier.size(16.dp),
                    )
                    // SPEC-401-A R44 — theme-adaptive bullet (was Color.Gray).
                    // iOS uses Color.primary.opacity(0.5)
                    // (ContentBlockRendererView.swift:466).
                    else -> Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)),
                    )
                }
                Text(
                    // SPEC-401-A R63 (Lens A Gap 3 Site B, P3) — apply
                    // style.text_transform matching iOS
                    // ContentBlockRendererView.swift:447-448 list item
                    // `.applyTextStyle(block.style)`.
                    text = block.style.applyTransform(loc?.invoke("block.${block.id}.item.$index", item) ?: item),
                    // SPEC-401-A R56→R57 (Lens A R56 #8, P3) — list item base 17sp
                    // matches iOS .body which inherits ambient default at
                    // ContentBlockRendererView.swift:447 list item Text. Was 16sp.
                    style = if (block.style != null) StyleEngine.applyTextStyle(TextStyle(fontSize = 17.sp), block.style) else TextStyle(fontSize = 17.sp),
                )
            }
        }
    }
}

@Composable
private fun DividerBlock(block: ContentBlock) {
    // SPEC-401-A R65 (Lens A P1) — port iOS ContentBlockRendererView.swift
    // :473-478 single `Rectangle().padding(.vertical, divider_margin_y)`.
    // Was emitting 3 children (Spacer/Box/Spacer) into the parent
    // `RenderBlock`'s `Box(modifier = contentModifier) { … }` — Compose
    // Box stacks children at the same z-position (NOT a Column), so the
    // two zero-width Spacers rendered no pixels and the line ended up
    // pinned to the top of an 8dp-tall Box. `divider_margin_y` was
    // functionally dead since written. Use single Box with vertical
    // padding to mirror iOS exactly.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = (block.divider_margin_y ?: 16.0).dp) // SPEC-419 pass-14 #12 — unset default 16 to match editor+preview (was 8)
            .height((block.divider_thickness ?: 1.0).dp)
            .background(StyleEngine.parseColor(block.divider_color ?: "#E5E7EB")),
    )
}

@Composable
private fun BadgeBlock(block: ContentBlock, loc: ((String, String) -> String)? = null) {
    val text = block.badge_text ?: ""
    // SPEC-401-A — read block.badge_corner_radius (defaults to 999 for
    // capsule). iOS FormInputBlockViews.swift:481-490 honours the field
    // similarly; Android was hardcoded to 999, ignoring author overrides.
    val badgeRadius = (block.badge_corner_radius ?: 999.0).dp
    Text(
        text = loc?.invoke("block.${block.id}.badge", text) ?: text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = StyleEngine.parseColor(block.badge_text_color ?: "#FFFFFF"),
        modifier = Modifier
            .background(
                StyleEngine.parseColor(block.badge_bg_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1")),
                RoundedCornerShape(badgeRadius),
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun IconBlock(block: ContentBlock) {
    val alignment = when (block.icon_alignment) {
        "left" -> Alignment.CenterStart
        "right" -> Alignment.CenterEnd
        else -> Alignment.Center
    }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment,
    ) {
        // SPEC-085: Support IconReference (structured icon) or plain emoji
        val iconRef = resolveIcon(block.icon_ref) ?: resolveIcon(block.icon_emoji)
        if (iconRef != null) {
            IconView(
                ref = iconRef.copy(size = iconRef.size ?: (block.icon_size ?: 32.0).toFloat()),
            )
        } else {
            Text(
                text = block.icon_emoji ?: "",
                fontSize = (block.icon_size ?: 32.0).sp,
            )
        }
    }
}

@Composable
private fun ToggleBlock(block: ContentBlock, toggleValues: MutableMap<String, Boolean>, loc: ((String, String) -> String)? = null) {
    var checked by remember { mutableStateOf(toggleValues[block.id] ?: (block.toggle_default ?: false)) }

    // SPEC-401-A R56 (Lens A R56 #4, P2) — 4dp inter-row spacing matches iOS
    // VStack(alignment: .leading, spacing: 4) at ContentBlockRendererView.swift
    // :523. Was bare Column → Row + description visually touching.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // SPEC-401-A R60 (Lens C P2 #1) — wrap label+Switch in `toggleable`
        // so TalkBack treats the row as a single switch element matching
        // iOS `Toggle("label", isOn:)` (ContentBlockRendererView.swift:524).
        // Switch becomes presentational (onCheckedChange = null); the Row
        // owns the click + a11y semantics. Without this users had to swipe
        // twice through label then "Switch, On".
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = androidx.compose.ui.semantics.Role.Switch,
                    onValueChange = {
                        checked = it
                        toggleValues[block.id] = it
                    },
                ),
        ) {
            val label = block.toggle_label ?: ""
            Text(
                text = loc?.invoke("block.${block.id}.label", label) ?: label,
                modifier = Modifier.weight(1f),
            )
            // SPEC-401-A R9 — match iOS standalone-toggle tint
            // ContentBlockRendererView.swift:525 `.tint(Color(hex: "#6366F1"))`.
            // Compose Switch defaults to Material3 green; without a `colors=`
            // override the same payload renders indigo on iOS and green on
            // Android. Track-thumb pair below mirrors iOS where the entire
            // track + thumb adopts the tint when checked.
            Switch(
                checked = checked,
                // SPEC-401-A R60 (Lens C P2 #1) — null handler so the Row's
                // toggleable owns the click + a11y semantics; Switch is now
                // a presentational visual.
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = ai.appdna.sdk.AppDNA.brandAccentColor(),
                ),
            )
        }
        block.toggle_description?.let {
            // SPEC-401-A R44 — theme-adaptive secondary (was Color.Gray).
            // iOS uses .secondary (ContentBlockRendererView.swift:529).
            Text(text = loc?.invoke("block.${block.id}.description", it) ?: it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun VideoBlock(block: ContentBlock) {
    val effectiveHeight = block.video_height ?: block.height ?: 200.0
    val effectiveCornerRadius = block.video_corner_radius ?: block.corner_radius ?: 8.0

    // SPEC-085: Use core VideoBlockView if video_url is available
    if (block.video_url != null) {
        CoreVideoBlockView(
            block = CoreVideoBlock(
                video_url = block.video_url,
                video_thumbnail_url = block.video_thumbnail_url ?: block.image_url,
                video_height = effectiveHeight.toFloat(),
                video_corner_radius = effectiveCornerRadius.toFloat(),
                // SPEC-401-A — iOS canonical field-name parity. Honour
                // unprefixed `autoplay`/`loop`/`muted`/`controls`/
                // `inline_playback` first, fall back to legacy
                // `video_*` prefix for older payloads.
                autoplay = block.autoplay ?: block.video_autoplay,
                loop = block.loop ?: block.video_loop,
                muted = block.muted ?: block.video_muted,
                // SPEC-419 pass-14 #3 — honour the authored `video_controls`
                // fallback (folded into field_config in OnboardingConfig.fromMap)
                // so an authored `false` actually hides the controls, matching
                // iOS + preview. Was unconditionally `?: true`.
                controls = block.controls ?: (block.field_config?.get("video_controls") as? Boolean) ?: true,
                inline_playback = block.inline_playback ?: true,
            )
        )
    } else {
        // Fallback: thumbnail with play icon overlay
        // SPEC-401-A R38 (Lens C #1) — match iOS ContentBlockRendererView.swift:567
        // `.accessibilityLabel(block.alt ?? "Video")` so screen-reader users
        // know this is a video placeholder, not a decorative image.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(effectiveHeight.dp)
                .clip(RoundedCornerShape(effectiveCornerRadius.dp))
                .semantics { contentDescription = block.alt ?: "Video" },
            contentAlignment = Alignment.Center,
        ) {
            ai.appdna.sdk.core.NetworkImage(
                url = block.video_thumbnail_url ?: block.image_url,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("\u25B6", fontSize = 24.sp, color = Color.White)
            }
        }
    }
}

// SPEC-085: Lottie content block
@Composable
private fun LottieContentBlock(block: ContentBlock) {
    if (block.lottie_url != null || block.lottie_json != null) {
        LottieBlockView(
            block = LottieBlock(
                lottie_url = block.lottie_url,
                lottie_json = block.lottie_json,
                // SPEC-401-A R7 — iOS reads canonical `block.autoplay` /
                // `block.loop` (ContentBlockRendererView.swift:595-597);
                // Android historically only knew the prefixed
                // `lottie_autoplay`/`lottie_loop`. Console writes the
                // canonical names so authored `false` was silently
                // ignored. Honour canonical first, legacy as fallback.
                autoplay = block.autoplay ?: block.lottie_autoplay ?: true,
                loop = block.loop ?: block.lottie_loop ?: true,
                speed = block.lottie_speed ?: 1.0f,  // SPEC-419 pass-23 — editor now authors lottie_speed (decoupled from the overloaded string `speed` particle key)
                width = block.lottie_width?.toFloat(),
                // SPEC-401-A R10 — match iOS field-name precedence at
                // ContentBlockRendererView.swift:599. Authored `lottie_height`
                // overrides generic `height`; both fall through to 160 default.
                height = (block.lottie_height ?: block.height ?: 160.0).toFloat(),
                alignment = block.icon_alignment ?: block.alignment ?: "center",  // SPEC-419 pass-22 — editor writes block.alignment
                play_on_scroll = block.play_on_scroll,
                play_on_tap = block.play_on_tap,
                color_overrides = block.color_overrides,
            )
        )
    }
}

// SPEC-085: Rive content block
@Composable
private fun RiveContentBlock(block: ContentBlock) {
    if (block.rive_url != null) {
        RiveBlockView(
            block = RiveBlock(
                rive_url = block.rive_url,
                // SPEC-401-A — honour iOS canonical `artboard`/
                // `state_machine` first, fall back to legacy
                // `rive_*` prefix.
                artboard = block.artboard ?: block.rive_artboard,
                state_machine = block.state_machine ?: block.rive_state_machine,
                // SPEC-401-A R7 — forward canonical `autoplay` so authored
                // `false` actually disables auto-play. iOS reads
                // `block.autoplay` at ContentBlockRendererView.swift:621.
                // Android previously omitted the arg, defaulting to true
                // unconditionally.
                autoplay = block.autoplay ?: true,
                height = (block.height ?: 160.0).toFloat(),
                alignment = block.icon_alignment ?: block.alignment ?: "center",  // SPEC-419 pass-22 — editor writes block.alignment
                inputs = block.rive_inputs,
                trigger_on_step_complete = block.trigger_on_step_complete,
            )
        )
    }
}

// MARK: - Page Indicator Block (SPEC-089d AC-012)

/**
 * Renders a row of indicator dots. Active dot can be wider (pill) if active_dot_width is set.
 * SDK auto-binds active_index to current step index when inside an onboarding flow.
 */
/** Upward-pointing triangle for page_indicator `dot_shape = "triangle"`. */
private val TriangleDotShape = GenericShape { size, _ ->
    moveTo(size.width / 2f, 0f)
    lineTo(size.width, size.height)
    lineTo(0f, size.height)
    close()
}

/** Five-point star for page_indicator `dot_shape = "star"`. */
private val StarDotShape = GenericShape { size, _ ->
    val cx = size.width / 2f
    val cy = size.height / 2f
    val outer = minOf(cx, cy)
    val inner = outer * 0.5f
    // 10 alternating outer/inner vertices, starting at the top point (-90°).
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outer else inner
        val angle = Math.toRadians((-90 + i * 36).toDouble())
        val x = cx + (r * Math.cos(angle)).toFloat()
        val y = cy + (r * Math.sin(angle)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

@Composable
private fun PageIndicatorBlock(block: ContentBlock, currentStepIndex: Int = 0, totalSteps: Int = 1) {
    // Audit pass-8 — clamp to [0,50] to match iOS (ContentBlockRendererView.swift:1236),
    // which caps dot_count precisely because ForEach(0..<count) is unsafe. A
    // binding/API-driven dot_count above 50 would render an unbounded dot row on
    // Android while iOS caps at 50.
    val dotCount = (block.dot_count ?: totalSteps).coerceIn(0, 50)
    // SPEC-401-A — explicit `active_index = 0` is a valid first-dot
    // selection. Previously Android auto-rebound to currentStepIndex
    // when the value was 0 (couldn't tell unset from 0); iOS uses
    // nil-coalesce so 0 is honoured. Use null-check to match iOS:
    val activeIndex = block.active_index ?: currentStepIndex
    val activeColor = StyleEngine.parseColor(block.active_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    val inactiveColor = StyleEngine.parseColor(block.inactive_color ?: "#D1D5DB")
    val dotSize = (block.dot_size ?: 8.0).dp
    val dotSpacing = (block.dot_spacing ?: 8.0).dp
    val activeDotWidth = block.active_dot_width?.dp
    // SPEC — per-dot shape (default "circle" preserves the legacy pill/circle look).
    val dotShape = (block.dot_shape ?: "circle").lowercase()

    // SPEC-401-A R45 (Lens A #6) — match iOS PageIndicator alignment
    // resolution: only `block.alignment` is read (no icon_alignment
    // fallback). ContentBlockRendererView.swift:646-652. A block
    // authored with icon_alignment but no alignment laid out wrong
    // on Android.
    val hAlign = when (block.alignment) {
        "left" -> Arrangement.Start
        "right" -> Arrangement.End
        else -> Arrangement.Center
    }

    // SPEC-401-A R8 — TalkBack contentDescription "Page X of Y" for
    // a11y parity with iOS `accessibilityLabel("Page X of Y")` at
    // ContentBlockRendererView.swift:668. Without this the row was
    // silently announced as "container" only.
    val pageOfDesc = "Page ${activeIndex + 1} of $dotCount"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = pageOfDesc },
        horizontalArrangement = hAlign,
    ) {
        for (i in 0 until dotCount) {
            if (i > 0) Spacer(modifier = Modifier.width(dotSpacing))
            val isActive = i == activeIndex
            val color = if (isActive) activeColor else inactiveColor
            val w = if (isActive && activeDotWidth != null) activeDotWidth else dotSize
            when (dotShape) {
                "rectangle" -> Box(modifier = Modifier.size(width = w, height = dotSize).clip(RectangleShape).background(color))
                "triangle" -> Box(modifier = Modifier.size(width = w, height = dotSize).clip(TriangleDotShape).background(color))
                "star" -> Box(modifier = Modifier.size(width = w, height = dotSize).clip(StarDotShape).background(color))
                else -> {
                    // "circle" — legacy behaviour: active pill when active_dot_width set, else circle.
                    if (isActive && activeDotWidth != null) {
                        Box(modifier = Modifier.size(width = activeDotWidth, height = dotSize).clip(RoundedCornerShape(50)).background(color))
                    } else {
                        Box(modifier = Modifier.size(dotSize).clip(CircleShape).background(color))
                    }
                }
            }
        }
    }
}

// MARK: - Social Login Block (SPEC-089d AC-015)

/**
 * Renders social login provider buttons (Apple, Google, Email, Facebook, GitHub).
 * Supports filled, outlined, and minimal button styles.
 * Each tap fires the onAction callback with the provider type.
 */
@Composable
private fun SocialLoginBlock(
    block: ContentBlock,
    onAction: (String) -> Unit,
    loc: ((String, String) -> String)? = null,
) {
    // SPEC-419 — Apple Sign-In is iOS-only; Android has no native Apple auth and the AppDNA SDK
    // can't perform it, so never RENDER "Continue with Apple" on Android (iOS keeps it). This also
    // frees a button's worth of vertical space so the "Already have an account?" links below the
    // social buttons stay on-screen.
    // IMPORTANT: apple is kept in this list so the per-provider loc index (`block.<id>.provider.<n>`)
    // matches iOS, which indexes over the FULL enabled list (ContentBlockRendererView.swift:1295,
    // `filter { $0.enabled != false }`). The apple button itself is skipped inside renderProvider —
    // filtering it out here would shift every subsequent index and mis-localize the wrong provider.
    val providers = block.providers?.filter { it.enabled } ?: return
    val buttonStyle = block.button_style ?: "filled"
    val cornerRadius = (block.button_corner_radius ?: 12.0).dp
    // SPEC-401-A R45 (Lens A #5) — match iOS social_login default
    // button height 50pt (was 48dp). ContentBlockRendererView.swift:676.
    val buttonHeight = (block.button_height ?: 50.0).dp
    val spacing = (block.spacing ?: 12.0).dp
    val showDivider = block.show_divider ?: false
    val dividerText = block.divider_text ?: "or"
    // Social-Login styling v2 — divider color/placement + in-button text align.
    val dividerColor = block.divider_color?.let { StyleEngine.parseColor(it) } ?: Color.Gray.copy(alpha = 0.3f)
    val dividerPosition = block.divider_position ?: "bottom"
    val textAlign = block.button_text_align ?: "center"

    // SPEC-401-A R13 — match iOS ContentBlockRendererView.swift:684-715
    // `email_login_placement: "below_inputs"`: pull the email provider
    // out of the array, render it FIRST, insert a clear spacer of
    // (email_cta_spacing_below - spacing), then render the remaining
    // providers below. Default `with_providers` keeps author order.
    val placement = block.email_login_placement ?: "with_providers"
    val emailSpacer = (block.email_cta_spacing_below ?: 16.0).dp
    val (topGroup, bottomGroup) = run {
        if (placement == "below_inputs") {
            val emailIdx = providers.indexOfFirst { it.type == "email" }
            if (emailIdx >= 0) {
                val list = providers.toMutableList()
                val email = list.removeAt(emailIdx)
                Pair(listOf(email), list.toList())
            } else Pair(providers, emptyList())
        } else Pair(providers, emptyList())
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        // Local helper closes over block/loc/buttonStyle/etc to keep
        // the per-provider rendering identical in both groups.
        val renderProvider: @androidx.compose.runtime.Composable (Int, SocialProvider) -> Unit = renderer@ { index, provider ->
            // Apple Sign-In has no native Android path — skip its render but keep its index slot
            // (assigned by the caller) so subsequent providers keep the same loc index as iOS.
            if (provider.type.lowercase() == "apple") return@renderer
            val label = provider.label ?: when (provider.type) {
                "apple" -> "Continue with Apple"
                "google" -> "Continue with Google"
                "email" -> "Continue with Email"
                "facebook" -> "Continue with Facebook"
                "github" -> "Continue with GitHub"
                else -> "Continue with ${provider.type.replaceFirstChar { it.uppercase() }}"
            }
            val displayLabel = loc?.invoke("block.${block.id}.provider.$index", label) ?: label

            // SPEC-070-A finalization OB-2 — per-provider color overrides.
            // Brand-default Triple comes first; provider.bg_color / text_color
            // / border_color override piecewise when authored. Mirrors iOS
            // ContentBlockRendererView.swift:724-771 which resolves
            // `provider.bg_color ?? socialLoginBgColor(provider.type)` for
            // each color independently.
            val (defaultBg, defaultText, defaultBorder) = when (provider.type) {
                "apple" -> Triple(Color(0xFF000000), Color.White, Color(0xFF000000))
                // SPEC-401-A R51 (Lens A #7, P1) — Google brand bg `#4285F4`
                // matches iOS ContentBlockRendererView.swift:828-836 (iOS comment
                // at :723 explicitly states "Google=#4285F4"). Was rendering as
                // a white outlined button on Android while iOS shipped the blue
                // filled button — same payload, two different visuals.
                "google" -> Triple(Color(0xFF4285F4), Color.White, Color(0xFF4285F4))
                "facebook" -> Triple(Color(0xFF1877F2), Color.White, Color(0xFF1877F2))
                "github" -> Triple(Color(0xFF24292F), Color.White, Color(0xFF24292F))
                "email" -> Triple(
                    StyleEngine.parseColor(block.accent_color ?: block.bg_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1")),
                    Color.White,
                    StyleEngine.parseColor(block.accent_color ?: block.bg_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1")),
                )
                else -> Triple(ai.appdna.sdk.AppDNA.brandAccentColor(), Color.White, ai.appdna.sdk.AppDNA.brandAccentColor())
            }
            val bgColor = provider.bg_color?.let { StyleEngine.parseColor(it) } ?: defaultBg
            // SPEC-419 pass-13 — outlined/minimal buttons have a CLEAR background, so the
            // brand-white default text was invisible (white-on-transparent). iOS forces
            // `.primary` (theme-adaptive) for these styles
            // (ContentBlockRendererView.swift:1389-1392); mirror with onSurface.
            val textColor = provider.text_color?.let { StyleEngine.parseColor(it) }
                ?: if (buttonStyle == "outlined" || buttonStyle == "minimal") MaterialTheme.colorScheme.onSurface else defaultText
            // SPEC-419 — outlined buttons use a NEUTRAL outline, not the provider brand color.
            // iOS socialLoginBorderColor (ContentBlockRendererView.swift:1495-1501): google=#DADCE0,
            // else theme secondary. defaultBorder is the brand color (e.g. Google blue), which would
            // draw a colored ring around outlined buttons — mismatching iOS. Only applies when the
            // author hasn't set an explicit provider.border_color override.
            val borderColor = provider.border_color?.let { StyleEngine.parseColor(it) }
                ?: if (buttonStyle == "outlined") {
                    if (provider.type == "google") Color(0xFFDADCE0)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                } else defaultBorder
            // OB-2 — per-provider corner_radius + border_width overrides.
            val providerCorner = (provider.corner_radius ?: block.button_corner_radius?.toFloat() ?: 12f).dp
            // SPEC-401-A R27 — match iOS ContentBlockRendererView.swift:738-741
            // outlined-button default 1.5dp stroke; non-outlined defaults 0
            // (border invisible on filled/minimal anyway). Was hardcoded 1f.
            val providerBorderWidth = (provider.border_width ?: if (buttonStyle == "outlined") 1.5f else 0f).dp

            val providerIcon = when (provider.type) {
                // SPEC-401-A R11 \u2014 `\uF8FF` is in Apple's Private-Use range
                // and renders as a tofu box on every Android device (NotoSans
                // has no Apple glyph). iOS uses `Image(systemName:"applelogo")`
                // SF Symbol which Android cannot replicate without an asset
                // import. Pragmatic fallback: red apple emoji `\uD83C\uDF4E` \u2014
                // universally renders on Android, conveys "Apple" in the
                // sign-in row even if it's not the official logo. Replace
                // with a bundled Apple Sign-In branded vector drawable in a
                // follow-up if HIG-strict branding is required.
                "apple" -> "\uD83C\uDF4E"
                "google" -> "G"
                // SPEC-419 \u2014 no glyph for email: the envelope rendered white on the
                // brand-white email button (invisible) and its reserved 8dp pushed the
                // label off-center. "Continue with Email" is a plain CTA, no brand logo.
                "email" -> ""
                "facebook" -> "f"
                "github" -> "\u2B24"
                else -> ""
            }
            // SPEC-070-A finalization OB-2 audit-1 CRIT-2 \u2014 icon_style was a
            // dead field. Mirrors iOS ContentBlockRendererView.swift:778-814:
            // "monochrome_light" \u2192 force white, "monochrome_dark" \u2192 force
            // black, default \u2192 use provider-native or button textColor.
            val monoIconColor: Color? = when (provider.icon_style) {
                "monochrome_light" -> Color.White
                "monochrome_dark" -> Color.Black
                else -> null
            }
            // SPEC-401-A R59 (Lens A P1 #2) \u2014 match iOS per-provider icon
            // styling (ContentBlockRendererView.swift:790-805).
            //  * google "G"  \u2192 18sp Bold (iOS uses .system(size:18, weight:.bold,
            //    design:.rounded); Android Compose has no built-in rounded
            //    sans-serif so we keep FontFamily.SansSerif but match size+weight)
            //  * facebook "f" \u2192 20sp Bold + Facebook-brand-blue (#1877F2) when
            //    no monochrome icon_style override (was 18sp regular monochrome,
            //    losing brand identity in light buttons)
            //  * apple emoji / email envelope / github circle keep 18sp regular
            val providerIconFontSize = when (provider.type) {
                "facebook" -> 20.sp
                else -> 18.sp
            }
            val providerIconFontWeight = when (provider.type) {
                "google", "facebook" -> FontWeight.Bold
                else -> FontWeight.Normal
            }
            val providerIconColor: Color = when {
                monoIconColor != null -> monoIconColor
                // Facebook brand-blue "f" is only legible on transparent-background
                // buttons (outlined/minimal). On a FILLED facebook button the
                // background is already #1877F2, so a blue glyph would be invisible —
                // use the button textColor (white) there. Matches iOS + preview.
                provider.type == "facebook" && buttonStyle != "filled" -> Color(0xFF1877F2)
                else -> textColor
            }

            // SPEC-070-A C.1 — dual-emit for the `email` provider (parity with
            // iOS v1.0.60 `ContentBlockRendererView.swift:743-754`). The email
            // provider in a social_login block is not actually OAuth — emit
            // `email_login:email` so hosts can branch their auth handler
            // cleanly. Also dual-emit the legacy `social_login:email` action
            // this release so existing handlers that switch on
            // `social_login` + value=="email" keep working. Legacy emit
            // removed in v1.1.0 alongside iOS.
            // Representation: colon-encoded `action:value` (chosen over
            // widening callback to (String, String?) to keep ContentBlockRenderer
            // call sites stable). Documented here so 070-B/C wrappers mirror.
            val socialClick: () -> Unit = {
                socialProviderActions(provider.type).forEach(onAction)
            }
            // Social-Login styling v2 — shared button content so icon_url override
            // and button_text_align (leading|center) apply identically across the
            // filled / outlined / minimal branches. A custom icon_url replaces the
            // built-in provider glyph; leading alignment left-justifies icon+label.
            val buttonContent: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                Row(
                    // Explicit 16dp leading inset for leading alignment so the icon
                    // starts 16dp from the edge, matching iOS `.padding(.horizontal, 16)`
                    // / preview `px-4` (Material's filled-button content padding is ~24dp).
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (textAlign == "leading") Modifier.padding(start = 16.dp) else Modifier),
                    horizontalArrangement = if (textAlign == "leading") Arrangement.Start else Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val customIcon = provider.icon_url
                    if (!customIcon.isNullOrBlank()) {
                        ai.appdna.sdk.core.NetworkImage(
                            url = customIcon,
                            // padding OUTSIDE the fixed size so the image is a true 20dp
                            // square with an 8dp trailing gap (28dp footprint), matching
                            // iOS `.frame(width:20,height:20)` + preview `<img 20x20>`.
                            // The previous `size(20).padding(end=8)` order left only 12dp
                            // for the image → compressed/aspect-distorted logo.
                            // ContentScale.Fit (not default Crop) matches iOS .fit +
                            // preview object-contain so non-square provider logos aren't distorted.
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            modifier = Modifier.padding(end = 8.dp).size(20.dp),
                        )
                    } else if (providerIcon.isNotBlank()) {
                        Text(providerIcon, fontSize = providerIconFontSize, fontWeight = providerIconFontWeight, modifier = Modifier.padding(end = 8.dp), color = providerIconColor)
                    }
                    // SPEC-401-A R56 (Lens A R56 #2, P1) — explicit 17sp matches
                    // iOS .body.weight(.semibold) (ContentBlockRendererView.swift:759).
                    // Material Button content defaults to labelLarge=14sp.
                    Text(displayLabel, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = textColor)
                }
            }
            // SPEC-070-A finalization OB-2 audit follow-up — apply per-provider
            // colors + provider-level corner/border-width across ALL three
            // button styles (filled, outlined, minimal). Audit round 1 caught
            // that only the filled branch read the overrides; outlined+minimal
            // ignored authored colors entirely.
            when (buttonStyle) {
                "outlined" -> {
                    OutlinedButton(
                        onClick = socialClick,
                        modifier = Modifier.fillMaxWidth().height(buttonHeight),
                        shape = RoundedCornerShape(providerCorner),
                        border = androidx.compose.foundation.BorderStroke(providerBorderWidth, borderColor),
                        colors = ButtonDefaults.outlinedButtonColors(
                            // Audit pass-8 — honor an authored provider.bg_color on
                            // outlined buttons (iOS ContentBlockRendererView.swift:1362-1365
                            // applies bgColor unconditionally; preview OnboardingStepPreview.tsx:1542
                            // does the same). Must default TRANSPARENT (not the brand `bgColor`)
                            // so a plain outlined/Google button stays .clear like iOS, and only
                            // tints when the author explicitly sets bg_color.
                            containerColor = provider.bg_color?.let { StyleEngine.parseColor(it) } ?: Color.Transparent,
                            contentColor = textColor,
                        ),
                        // Zero Material3 default contentPadding so the inner buttonContent
                        // Row's own start=16.dp is the sole horizontal authority (matches iOS 16pt
                        // + preview px-4). Height stays fixed via `.height(buttonHeight)`.
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        content = buttonContent,
                    )
                }
                "minimal" -> {
                    TextButton(
                        onClick = socialClick,
                        modifier = Modifier.fillMaxWidth().height(buttonHeight),
                        shape = RoundedCornerShape(providerCorner),
                        colors = ButtonDefaults.textButtonColors(
                            // Audit pass-8 — same authored-bg_color honor as outlined
                            // (iOS applies bgColor to minimal too); TRANSPARENT default
                            // preserves the borderless minimal look until bg_color is set.
                            containerColor = provider.bg_color?.let { StyleEngine.parseColor(it) } ?: Color.Transparent,
                            contentColor = textColor,
                        ),
                        // Zero Material3 default contentPadding so the inner buttonContent
                        // Row's own start=16.dp is the sole horizontal authority (matches iOS 16pt
                        // + preview px-4). Height stays fixed via `.height(buttonHeight)`.
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        content = buttonContent,
                    )
                }
                else -> { // filled
                    Button(
                        onClick = socialClick,
                        modifier = Modifier.fillMaxWidth().height(buttonHeight),
                        // SPEC-070-A finalization OB-2 audit-1 CRIT-1 — was
                        // `RoundedCornerShape(cornerRadius)`, dropping the
                        // per-provider `corner_radius` override. Filled is
                        // the default style (block.button_style ?: "filled")
                        // so this affected every social_login block authored
                        // without explicit outlined/minimal opt-in.
                        shape = RoundedCornerShape(providerCorner),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = bgColor,
                            contentColor = textColor,
                        ),
                        // SPEC-419 — flat like iOS (no Material default-elevation shadow band).
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 0.dp,
                            pressedElevation = 0.dp,
                            focusedElevation = 0.dp,
                            hoveredElevation = 0.dp,
                        ),
                        // Zero Material3 default contentPadding so the inner buttonContent
                        // Row's own start=16.dp is the sole horizontal authority (matches iOS 16pt
                        // + preview px-4). Height stays fixed via `.height(buttonHeight)`.
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        content = buttonContent,
                    )
                }
            }

            // SPEC-401-A — divider moved out of the per-provider loop.
            // iOS renders the "or" divider ONCE at the bottom of the
            // social-login block (ContentBlockRendererView.swift:709-717).
            // The old per-provider divider gave a column of repeating
            // "or" rows which doesn't exist on iOS at all.
        }

        // Social-Login styling v2 — divider composable honoring divider_color;
        // placed at the top or bottom per divider_position.
        val dividerRow: @androidx.compose.runtime.Composable () -> Unit = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(dividerColor),
                )
                Text(
                    text = loc?.invoke("block.${block.id}.divider", dividerText) ?: dividerText,
                    // SPEC-401-A R55 (Lens A R55 #1, P3) — 14→15sp matching iOS
                    // ContentBlockRendererView.swift:713 .subheadline (~15pt).
                    fontSize = 15.sp,
                    // SPEC-401-A R44 — theme-adaptive secondary (was Color.Gray).
                    // iOS .secondary (ContentBlockRendererView.swift:714).
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(dividerColor),
                )
            }
        }

        if (showDivider && dividerPosition == "top") dividerRow()

        // SPEC-401-A R13 — render top group (email-first when
        // `below_inputs` placement, full author-order list otherwise),
        // then optional spacer, then the remaining providers below.
        topGroup.forEachIndexed { idx, provider -> renderProvider(idx, provider) }
        if (placement == "below_inputs" && bottomGroup.isNotEmpty()) {
            // Column already inserts `spacing` between adjacent items
            // via verticalArrangement, so the additional gap to add is
            // `email_cta_spacing_below - spacing` (matches iOS
            // ContentBlockRendererView.swift:707).
            Spacer(modifier = Modifier.height(maxOf(0.dp, emailSpacer - spacing)))
        }
        bottomGroup.forEachIndexed { idx, provider ->
            renderProvider(topGroup.size + idx, provider)
        }

        // SPEC-401-A — single divider gated on show_divider, bottom by default.
        if (showDivider && dividerPosition != "top") dividerRow()
    }
}

// MARK: - Countdown Timer Block (SPEC-089d AC-018)

/**
 * Countdown timer with digital or bar variant.
 * Uses LaunchedEffect with delay(1000) loop to decrement remaining seconds.
 * Supports fixed_duration and fixed_datetime target types.
 */
@Composable
private fun CountdownTimerBlock(block: ContentBlock, onAction: (String) -> Unit) {
    // SPEC-419 pass-15 #10 — editor authors `timer_variant` (folded into field_config
    // by OnboardingConfig.fromMap since Android is budget-locked); read it first, fall
    // back to legacy `variant`.
    val variant = (block.field_config?.get("timer_variant") as? String) ?: block.variant ?: "digital"
    val initialSeconds = when (block.target_type) {
        "fixed_datetime" -> {
            // Parse ISO datetime and compute remaining seconds
            try {
                val targetMs = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .parse(block.target_datetime ?: "")?.time ?: 0L
                val remaining = ((targetMs - System.currentTimeMillis()) / 1000).toInt()
                if (remaining > 0) remaining else 0
            } catch (_: Exception) { block.duration_seconds ?: 60 }
        }
        else -> block.duration_seconds ?: 60
    }

    var remainingSeconds by remember { mutableIntStateOf(initialSeconds) }
    var expired by remember { mutableStateOf(initialSeconds <= 0) }

    val textColor = StyleEngine.parseColor(block.text_color ?: "#000000")
    val accentColor = StyleEngine.parseColor(block.accent_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    val bgColor = block.bg_color?.let { StyleEngine.parseColor(it) }
    // SPEC-401-A R32 — match iOS ContentBlockStandaloneViews.swift:112
    // default font_size 28 (was 24).
    val fontSize = (block.font_size ?: 28.0).sp

    val showDays = block.show_days ?: true
    val showHours = block.show_hours ?: true
    val showMinutes = block.show_minutes ?: true
    val showSeconds = block.show_seconds ?: true

    // SPEC-419 pass-15 #28 — default unit labels hrs/min/sec to match preview (was Days/Hours/Min/Sec).
    val labels = block.labels ?: CountdownLabels()
    val daysLabel = labels.days ?: "days"
    val hoursLabel = labels.hours ?: "hrs"
    val minutesLabel = labels.minutes ?: "min"
    val secondsLabel = labels.seconds ?: "sec"
    // SPEC-419 pass-15 #11 — unit labels use secondary grey (digits use accent_color); matches preview.
    val unitLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    // Countdown tick
    LaunchedEffect(Unit) {
        while (remainingSeconds > 0) {
            kotlinx.coroutines.delay(1000L)
            remainingSeconds--
        }
        expired = true
        when (block.on_expire_action) {
            "auto_advance" -> onAction("next")
            else -> { /* hide or show_expired_text handled below */ }
        }
    }

    // On expire: hide (only when explicitly requested).
    if (expired && block.on_expire_action == "hide") return

    // SPEC-401-A R3 — on expire show expired_text by default. iOS
    // always renders `expired_text` once the timer hits zero
    // (ContentBlockStandaloneViews.swift:148-165) regardless of
    // on_expire_action, except for hide/auto_advance. Android
    // previously only rendered it when on_expire_action ==
    // "show_expired_text", so the default-unset case fell through
    // to the digital "00:00" timer indefinitely.
    // SPEC-401-A R32 — match iOS ContentBlockStandaloneViews.swift:112
    // default font_size 28 (was 24 on Android, 4pt smaller for any
    // countdown_timer authored without explicit font_size — most paywall
    // promo timers).
    if (expired && block.on_expire_action != "auto_advance") {
        // SPEC-401-A R23 — match iOS ContentBlockStandaloneViews.swift
        // :154-160. iOS forces `.font(.subheadline.weight(.semibold))` +
        // `.foregroundColor(.secondary)` on the expiry text regardless of
        // the timer's display style; default copy is "Time's up!" not
        // "Expired". Android was reusing the timer's giant `fontSize` +
        // `textColor` and the wrong default string — visually broken on
        // any timer larger than ~15sp (most paywall timers run 32-48sp).
        Text(
            text = block.expired_text ?: "Time's up!",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    val days = remainingSeconds / 86400
    val hours = (remainingSeconds % 86400) / 3600
    val minutes = (remainingSeconds % 3600) / 60
    val seconds = remainingSeconds % 60

    val hAlign = when (block.alignment) {
        "left" -> Arrangement.Start
        "right" -> Arrangement.End
        else -> Arrangement.Center
    }

    // SPEC-419 pass-15 #10 — h/m/s segments + labels for circular/flip/bar variants (matches preview).
    val segs = buildList {
        if (showHours) add(hours.toString().padStart(2, '0'))
        if (showMinutes) add(minutes.toString().padStart(2, '0'))
        if (showSeconds) add(seconds.toString().padStart(2, '0'))
    }
    val segLabels = buildList {
        if (showHours) add(hoursLabel)
        if (showMinutes) add(minutesLabel)
        if (showSeconds) add(secondsLabel)
    }
    val joined = segs.joinToString(":")

    when (variant) {
        "circular" -> {
            // SPEC-419 pass-15 #10 — 75% accent ring with joined digits centered.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val sw = 4.dp.toPx()
                        drawArc(
                            color = StyleEngine.parseColor("#E5E7EB"),
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = sw, cap = StrokeCap.Round),
                            topLeft = Offset(sw / 2, sw / 2),
                            size = androidx.compose.ui.geometry.Size(this.size.width - sw, this.size.height - sw),
                        )
                        drawArc(
                            color = accentColor,
                            startAngle = -90f,
                            sweepAngle = 270f,
                            useCenter = false,
                            style = Stroke(width = sw, cap = StrokeCap.Round),
                            topLeft = Offset(sw / 2, sw / 2),
                            size = androidx.compose.ui.geometry.Size(this.size.width - sw, this.size.height - sw),
                        )
                    }
                    Text(
                        text = joined,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        }
        "flip" -> {
            // SPEC-419 pass-15 #10 — each segment in a tinted card, label below.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                segs.forEachIndexed { i, seg ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(StyleEngine.parseColor("#F1F5F9"))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = seg,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = accentColor,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            )
                        }
                        Text(
                            text = segLabels.getOrElse(i) { "" },
                            fontSize = 10.sp,
                            color = unitLabelColor,
                        )
                    }
                }
            }
        }
        "bar" -> {
            // SPEC-419 pass-15 #10 — time + "remaining" label + shrinking accent bar (matches preview).
            val fraction = if (initialSeconds > 0) remainingSeconds.toFloat() / initialSeconds else 0f
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = joined, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = accentColor)
                    Text(text = "remaining", fontSize = 14.sp, color = unitLabelColor)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(bgColor ?: StyleEngine.parseColor("#E5E7EB")),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(accentColor),
                    )
                }
            }
        }
        else -> {
            // Digital variant: columns of time units
            // Build list of (value, label) pairs to display
            val timeUnits = mutableListOf<Triple<Int, String, Boolean>>()
            if (showDays && days > 0) timeUnits.add(Triple(days, daysLabel, true))
            if (showHours) timeUnits.add(Triple(hours, hoursLabel, true))
            if (showMinutes) timeUnits.add(Triple(minutes, minutesLabel, showSeconds))
            if (showSeconds) timeUnits.add(Triple(seconds, secondsLabel, false))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = hAlign,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                timeUnits.forEach { (value, unitLabel, _) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        // SPEC-401-A R46 (Lens A #5) — value→label 4pt gap
                        // matching iOS VStack(spacing: 4)
                        // (ContentBlockStandaloneViews.swift:138).
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = value.toString().padStart(2, '0'),
                            fontSize = fontSize,
                            fontWeight = FontWeight.Bold,
                            // SPEC-419 pass-15 #11 — digits use accent_color (matches preview).
                            color = accentColor,
                            // SPEC-401-A R3 — monospaced digits so the
                            // counter doesn't visibly shift width per
                            // second. iOS uses .system(.monospaced).
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                        Text(
                            text = unitLabel,
                            // SPEC-401-A R48 (Lens C #6) — countdown unit-label
                            // 10→11sp matching iOS .footnote (~11pt).
                            fontSize = 11.sp,
                            // SPEC-419 pass-15 #11 — unit labels use secondary grey.
                            color = unitLabelColor,
                        )
                    }
                    // SPEC-401-A R3 — drop the inter-column ":" separator.
                    // iOS doesn't render any separator between time-unit
                    // columns; Android's colon broke the visual rhythm.
                }
            }
        }
    }
}

// MARK: - Rating Block (SPEC-089d AC-019)

/**
 * Star rating input. Renders a row of star icons (filled/outlined).
 * Stores selected rating in inputValues map for response collection.
 */
@Composable
private fun RatingBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
    loc: ((String, String) -> String)? = null,
) {
    val maxStars = block.max_stars ?: 5
    val starSize = (block.star_size ?: 32.0).dp
    // SPEC-401-A R3 — iOS canonical field names first, fall back to
    // legacy Android names so old + new payloads both render.
    val activeColor = StyleEngine.parseColor(
        block.filled_color ?: block.active_rating_color ?: block.active_color ?: "#FBBF24"
    )
    val inactiveColor = StyleEngine.parseColor(
        block.empty_color ?: block.inactive_rating_color ?: block.inactive_color ?: "#D1D5DB"
    )
    val fieldId = block.field_id ?: block.id
    val allowHalf = block.allow_half == true

    // SPEC-401-A R3 — `default_rating` (iOS canon) / `default_value`
    // (legacy) honoured.
    val initial = (block.default_rating ?: block.default_value
        ?: (inputValues[fieldId] as? Number)?.toDouble()) ?: 0.0
    var selectedRating by remember { mutableStateOf(initial) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Optional label — iOS reads `rating_label ?? label`.
        // SPEC-401-A R11 — match iOS `Text(label).foregroundColor(.primary)`
        // (ContentBlockStandaloneViews.swift:21-25) — theme-aware text
        // colour, NOT a hardcoded gray. Previous Color.Gray made the
        // label read as dim secondary text on Android while iOS rendered
        // it as primary body text.
        // SPEC-401-A R61 (Lens A N7, P3) — drop `?: block.label` to match
        // iOS canonical ContentBlockStandaloneViews.swift:21 which only
        // reads `block.rating_label`. Same family as backlog #3.
        block.rating_label?.let { label ->
            val displayLabel = loc?.invoke("block.${block.id}.label", label) ?: label
            Text(
                text = displayLabel,
                // SPEC-401-A R56→R57 (Lens A R56 #6, P3) — 15sp matches iOS
                // .subheadline (ContentBlockStandaloneViews.swift:23). Was 14sp.
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            // SPEC-401-A R37 (Lens C #2) — combine per-star semantics into
            // one a11y element + announce current rating as state.
            // iOS ContentBlockStandaloneViews.swift:47,54-55 wraps the
            // HStack with `accessibilityElement(children: .combine)` +
            // `accessibilityValue("X of Y stars")`. Without this TalkBack
            // reads "Star 1, Star 2, ... Star 5" but never the chosen value.
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = "Rating"
                // SPEC-401-A R58 (Lens A R58 P3 #4) — match iOS
                // ContentBlockStandaloneViews.swift:55 `Int(selectedRating)`
                // when not allow_half ("3 of 5"), `String(format: "%.1f", …)`
                // when allow_half ("2.5 of 5"). Was `Double.toString` which
                // produced "3.0 of 5 stars" for every full-star rating.
                stateDescription = if (allowHalf) {
                    // Round-30 — Locale.US (POSIX) like iOS String(format:); default JVM locale
                    // renders "2,5"/Arabic-Indic "٢٫٥" in TalkBack. Twin of the iOS a11y line fixed
                    // in Round-29 (ContentBlockStandaloneViews.swift); this Android half was missed.
                    "${"%.1f".format(java.util.Locale.US, selectedRating)} of $maxStars stars"
                } else {
                    "${selectedRating.toInt()} of $maxStars stars"
                }
            },
        ) {
            for (i in 1..maxStars) {
                // SPEC-401-A R3 — half-star rendering when allow_half:
                // value >= i fills, value >= i-0.5 half-fills, else empty.
                val full = selectedRating >= i
                val half = !full && allowHalf && selectedRating >= i - 0.5
                Icon(
                    imageVector = when {
                        full -> Icons.Filled.Star
                        half -> androidx.compose.material.icons.Icons.Filled.StarHalf
                        else -> Icons.Outlined.Star
                    },
                    contentDescription = "Star $i",
                    tint = if (full || half) activeColor else inactiveColor,
                    modifier = Modifier
                        .size(starSize)
                        .clickable {
                            // Toggle half ↔ full when allow_half on
                            // a re-tap of the currently-filled star.
                            val tapped = i.toDouble()
                            selectedRating = when {
                                allowHalf && full && (selectedRating - tapped).let { it >= 0 && it < 0.5 } -> tapped - 0.5
                                else -> tapped
                            }
                            inputValues[fieldId] = selectedRating
                        },
                )
            }
        }
    }
}

// MARK: - Rich Text Block (SPEC-089d AC-020)

/**
 * Rich text with inline markdown-style formatting.
 * Parses **bold**, *italic*, and [link](url) patterns.
 * Uses ClickableText for link tap handling.
 */
@Composable
private fun RichTextBlock(block: ContentBlock, loc: ((String, String) -> String)? = null) {
    // SPEC-401-A R4 — iOS canonical `markdown_content` first, then
    // `content`, then plain `text`. Console writes markdown_content.
    // SPEC-401-A R61 (Lens A N4, P3) — drop `?: block.content` to match iOS
    // canonical ContentBlockRendererView.swift:932 `block.markdown_content ??
    // block.text`. iOS DTO has no `content` field; payloads using only
    // `block.content` rendered on Android, empty on iOS. Aligning to iOS.
    val rawContent = block.markdown_content ?: block.text ?: ""
    val content = loc?.invoke("block.${block.id}.content", rawContent) ?: rawContent
    val linkColor = StyleEngine.parseColor(block.link_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    val context = LocalContext.current

    // SPEC-401-A — `legal` variant defaults: caption font + centred
    // alignment + secondary colour. Mirrors iOS
    // ContentBlockRendererView.swift:932-980.
    // SPEC-401-A R10 — `legal` secondary color. iOS uses
    // `.foregroundColor(.secondary)` (theme-aware system label-secondary,
    // ~UIColor.secondaryLabel). Compose's `Color.Unspecified.copy(alpha=…)`
    // is a sentinel-on-sentinel hack that produces an unreliable color in
    // some Compose builds. Use MaterialTheme.colorScheme.onBackground at
    // 60% alpha so the same payload renders dim grey on both platforms,
    // and adapts to dark mode through the Material theme.
    val isLegal = block.rich_text_variant == "legal"
    val secondaryColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
    val legalDefault = if (isLegal) {
        TextStyle(
            fontSize = 12.sp,
            color = secondaryColor,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    } else {
        // SPEC-401-A R56 (Lens A R56 #5, P2) — non-legal default 17sp matches
        // iOS .body (ContentBlockRendererView.swift:965). Was 16sp — 1pt narrower
        // on every rich_text block authored without explicit base_style.font_size.
        TextStyle(fontSize = 17.sp, color = Color.Unspecified)
    }

    // Apply base_style if present
    val baseTextStyle = if (block.base_style != null) {
        StyleEngine.applyTextStyle(legalDefault, block.base_style)
    } else if (block.style != null) {
        StyleEngine.applyTextStyle(legalDefault, block.style)
    } else {
        legalDefault
    }

    val annotatedString = parseMarkdownToAnnotatedString(content, baseTextStyle, linkColor)

    // SPEC-401-A R82 (Lens C P2) — TalkBack link semantics. iOS Text with
    // markdown AttributedString announces "[link text], link" via inherent
    // AccessibilityTraits.link. Compose ClickableText with no `Modifier
    // .semantics { role = Role.Button }` reads as plain text — WCAG 4.1.2
    // Name/Role/Value compliance gap on every legal/rich_text block with
    // [label](url) syntax (Terms/Privacy most common).
    val hasLinks = annotatedString.getStringAnnotations(tag = "URL", start = 0, end = annotatedString.length).isNotEmpty()
    ClickableText(
        text = annotatedString,
        style = baseTextStyle,
        maxLines = block.max_lines ?: Int.MAX_VALUE,
        modifier = if (hasLinks) {
            Modifier.fillMaxWidth().semantics { role = androidx.compose.ui.semantics.Role.Button }
        } else {
            Modifier.fillMaxWidth()
        },
        onClick = { offset ->
            annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    // SPEC-070-B PN row 18 (W11): a link inside authored rich text is config-driven too.
                    ai.appdna.sdk.core.URLSafety.sanitized(annotation.item, context)?.let { uri ->
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (_: Exception) {
                            // No browser — silently ignore
                        }
                    }
                }
        },
    )
}

/**
 * Parses a subset of Markdown into an AnnotatedString.
 * Supported patterns:
 * - **bold** → SpanStyle(fontWeight = Bold)
 * - *italic* → SpanStyle(fontStyle = Italic)
 * - [text](url) → SpanStyle(color = linkColor, underline) + URL annotation
 */
private fun parseMarkdownToAnnotatedString(
    markdown: String,
    baseStyle: TextStyle,
    linkColor: Color,
): androidx.compose.ui.text.AnnotatedString {
    // Regex patterns (order matters: bold before italic to avoid ambiguity).
    // SPEC-401-A — `++underline++` is the iOS custom marker mirrored
    // here so the same Markdown source renders identically.
    val boldRegex = Regex("""\*\*(.+?)\*\*""")
    val italicRegex = Regex("""\*(.+?)\*""")
    val underlineRegex = Regex("""\+\+(.+?)\+\+""")
    val linkRegex = Regex("""\[(.+?)]\((.+?)\)""")

    data class StyledRange(val start: Int, val end: Int, val style: SpanStyle, val tag: String? = null, val annotation: String? = null)

    // First pass: find all link ranges in the original text and build a clean string
    val linkMatches = linkRegex.findAll(markdown).toList()
    val boldMatches = boldRegex.findAll(markdown).toList()
    val italicMatches = italicRegex.findAll(markdown).toList()

    return buildAnnotatedString {
        // Simple iterative parser: process the string character by character,
        // replacing markdown tokens as we go
        var remaining = markdown
        while (remaining.isNotEmpty()) {
            // Find the earliest markdown match
            val linkMatch = linkRegex.find(remaining)
            val boldMatch = boldRegex.find(remaining)
            val underlineMatch = underlineRegex.find(remaining)
            // Only match italic if it's not part of a bold marker
            val italicMatch = italicRegex.find(remaining)?.takeIf { m ->
                val idx = m.range.first
                // Ensure this is not a ** bold marker
                !(idx > 0 && remaining.getOrNull(idx - 1) == '*') &&
                    remaining.getOrNull(idx + 1) != '*'
            }

            val matches = listOfNotNull(
                linkMatch?.let { it to "link" },
                boldMatch?.let { it to "bold" },
                underlineMatch?.let { it to "underline" },
                italicMatch?.let { it to "italic" },
            ).sortedBy { it.first.range.first }

            if (matches.isEmpty()) {
                // No more markdown — append rest as plain text
                append(remaining)
                break
            }

            val (match, type) = matches.firstOrNull() ?: break

            // Append text before the match
            if (match.range.first > 0) {
                append(remaining.substring(0, match.range.first))
            }

            when (type) {
                "link" -> {
                    val linkText = match.groupValues[1]
                    val url = match.groupValues[2]
                    pushStringAnnotation(tag = "URL", annotation = url)
                    withStyle(SpanStyle(
                        color = linkColor,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    )) {
                        append(linkText)
                    }
                    pop()
                }
                "bold" -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(match.groupValues[1])
                    }
                }
                "underline" -> {
                    withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) {
                        append(match.groupValues[1])
                    }
                }
                "italic" -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(match.groupValues[1])
                    }
                }
            }

            remaining = remaining.substring(match.range.last + 1)
        }
    }
}

// MARK: - Progress Bar Block (SPEC-089d AC-021)

/**
 * Segmented or continuous progress bar.
 * Continuous: single track + fill bar.
 * Segmented: row of equally-sized segments, some filled, some empty.
 * SDK can auto-bind active_segments to current step index.
 */
@Composable
private fun ProgressBarBlock(block: ContentBlock, loc: ((String, String) -> String)? = null, currentStepIndex: Int = 0, totalSteps: Int = 1) {
    // SPEC-401-A — iOS field-name parity. iOS uses `progress_variant` /
    // `total_segments` / `filled_segments` / `bar_color` / `bar_height`.
    // Older Android-only payloads still set `variant` / `segment_count` /
    // `active_segments` / `fill_color` / `height` so honour both.
    val variant = block.progress_variant ?: block.variant ?: "continuous"
    // AC-021: Auto-bind to step index when no explicit values set
    val segmentCount = block.total_segments ?: block.segment_count ?: totalSteps
    // SPEC-401-A R10 — match iOS auto-bind exactly. iOS only checks
    // `filled_segments`; when null it always falls through to
    // `currentStepIndex+1`. Android previously also short-circuited to
    // `1` whenever `bar_color`/`fill_color` was authored, which froze
    // the bar at 1/total even for multi-step flows where iOS would
    // advance. ContentBlockRendererView.swift:1051-1056.
    val explicitFilled = block.filled_segments ?: block.active_segments
    val activeSegments = explicitFilled ?: (currentStepIndex + 1)
    val fillColor = StyleEngine.parseColor(block.bar_color ?: block.fill_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    // EPIC-2 — multiple progress colors at once (horizontal gradient across the fill).
    val gradColors = block.bar_gradient_colors?.takeIf { it.size >= 2 }?.map { StyleEngine.parseColor(it) }
    val trackColor = StyleEngine.parseColor(block.track_color ?: "#E5E7EB")
    // Progress/Loading v2 — clamp to the console slider max (24) so an out-of-range
    // published value can't render a giant bar the editor can't reproduce (duolingo s7).
    val barHeight = minOf(block.bar_height ?: block.height ?: 8.0, 24.0).dp // SPEC-419 pass-14 #14 — unset default 8 to match editor+preview (was 6)
    val cornerRadius = (block.corner_radius ?: 3.0).dp
    val segmentGap = (block.segment_gap ?: 4.0).dp
    val showLabel = block.show_label ?: true // SPEC-419 pass-14 #13 — unset default true to match editor+preview (was false)
    // SPEC-419 gap#2 — explicit continuous fill from `progress_value` (0–1
    // fraction OR 0–100 percent), clamped 0..1. When unset the bar keeps
    // auto-binding to the step index. Mirrors iOS + the console preview.
    val pvFraction: Float? = block.progress_value?.toFloat()?.let {
        (if (it > 1f) it / 100f else it).coerceIn(0f, 1f)
    }
    // SPEC-419 gap#6 — honor `label_format`/`custom_label`. The console editor
    // authors these top-level, but Android can't add top-level ContentBlock
    // params (JVM arg-budget), so the parser surfaces them via field_config.
    // Default keeps the existing "Step X of Y". Mirrors the preview label logic.
    val labelFormat = block.field_config?.get("label_format") as? String
    val customLabel = block.field_config?.get("custom_label") as? String
    // SPEC-419 pass-13 correctness — the percentage/fraction label must use the
    // SAME normalization as the fill (`pvFraction`). Previously rendered the RAW
    // `progress_value` → `progress_value=0.75` filled 75% but the label read
    // "0%". Mirrors iOS pvPercent.
    val pvPercent = ((pvFraction ?: 0f) * 100).roundToInt()

    // Progress/Loading v2 — label placement relative to the bar. Authored top-level
    // by the console editor; folded into field_config on Android (top-level budget-locked).
    val placement = block.field_config?.get("label_placement") as? String ?: "above"
    val showLabelFinal = showLabel && segmentCount > 0

    val labelContent: @Composable () -> Unit = {
        val labelText = when (labelFormat) {
            null -> "Step $activeSegments of $segmentCount"
            "fraction" -> if (variant == "segmented") "$activeSegments/$segmentCount" else "$pvPercent/100"
            "custom" -> customLabel ?: ""
            else -> if (variant == "segmented")
                "${((activeSegments.toFloat() / maxOf(segmentCount, 1)) * 100).toInt()}%"
            else "$pvPercent%"
        }
        val labelStyle = if (block.label_style != null) {
            // SPEC-401-A R44 — theme-adaptive secondary base (was Color.Gray).
            StyleEngine.applyTextStyle(TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)), block.label_style)
        } else {
            // SPEC-401-A R44 — theme-adaptive secondary fallback (was Color.Gray).
            TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Text(
            text = loc?.invoke("block.${block.id}.label", labelText) ?: labelText,
            style = labelStyle,
        )
    }

    val barContent: @Composable () -> Unit = {
        when (variant) {
            "segmented" -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(segmentGap),
                ) {
                    for (i in 0 until segmentCount) {
                        val isFilled = i < activeSegments
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(barHeight)
                                .clip(RoundedCornerShape(cornerRadius))
                                .background(if (isFilled) fillColor else trackColor),
                        )
                    }
                }
            }
            else -> {
                // Continuous progress bar
                val fraction = pvFraction ?: if (segmentCount > 0) {
                    (activeSegments.toFloat() / segmentCount).coerceIn(0f, 1f)
                } else 0f

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                        .clip(RoundedCornerShape(cornerRadius))
                        .background(trackColor),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(cornerRadius))
                            .then(
                                if (gradColors != null) {
                                    Modifier.background(androidx.compose.ui.graphics.Brush.horizontalGradient(gradColors))
                                } else {
                                    Modifier.background(fillColor)
                                },
                            ),
                    )
                }
            }
        }
    }

    // SPEC-401-A R47 (Lens C #7) — 8dp gap matches iOS VStack(spacing: 8).
    when (placement) {
        "left" -> Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showLabelFinal) { labelContent(); Spacer(modifier = Modifier.width(8.dp)) }
            Box(modifier = Modifier.weight(1f)) { barContent() }
        }
        "right" -> Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) { barContent() }
            if (showLabelFinal) { Spacer(modifier = Modifier.width(8.dp)); labelContent() }
        }
        "below" -> Column(modifier = Modifier.fillMaxWidth()) {
            barContent()
            if (showLabelFinal) { Spacer(modifier = Modifier.height(8.dp)); labelContent() }
        }
        else -> Column(modifier = Modifier.fillMaxWidth()) {
            if (showLabelFinal) { labelContent(); Spacer(modifier = Modifier.height(8.dp)) }
            barContent()
        }
    }
}

// MARK: - Timeline Block (SPEC-089d AC-016)

/**
 * Vertical timeline with status indicators (completed/current/upcoming).
 * Each item shows a status circle, optional connecting line, title, and subtitle.
 */
@Composable
private fun TimelineBlock(block: ContentBlock, loc: ((String, String) -> String)? = null) {
    val items = block.timeline_items ?: return
    val lineColor = StyleEngine.parseColor(block.line_color ?: "#E5E7EB")
    val completedColor = StyleEngine.parseColor(block.completed_color ?: "#22C55E")
    val currentColor = StyleEngine.parseColor(block.current_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    val upcomingColor = StyleEngine.parseColor(block.upcoming_color ?: "#D1D5DB")
    val showLine = block.show_line ?: true
    val isCompact = block.compact ?: false
    // SPEC-401-A R16 — match iOS ContentBlockRendererView.swift connector
    // min-height (compact 20pt / regular 32pt). Android was 12/24, making
    // timelines look cramped side-by-side.
    val itemSpacing = if (isCompact) 20.dp else 32.dp

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        items.forEachIndexed { index, item ->
            val statusColor = when (item.status) {
                "completed" -> completedColor
                "current" -> currentColor
                else -> upcomingColor
            }
            val isLast = index == items.size - 1

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (isLast) 0.dp else itemSpacing),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Left column: status indicator + connecting line
                // SPEC-401-A R16 — match iOS ContentBlockRendererView.swift:878-887
                // dimensions: 28pt circle + 12pt checkmark + 10pt current dot
                // (Android was 24/14/8 — visibly tighter side-by-side).
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(28.dp),
                ) {
                    // Status circle
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(statusColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (item.status == "completed") {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Completed",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp),
                            )
                        } else if (item.status == "current") {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color.White),
                            )
                        }
                    }

                    // Connecting line
                    if (showLine && !isLast) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(itemSpacing)
                                .background(lineColor),
                        )
                    }
                }

                // Right column: title + subtitle
                // SPEC-401-A R47 (Lens C #5+#6) — title↔subtitle 4dp
                // (iOS VStack(spacing: 4)).
                // SPEC-401-A R52 (Lens A R51 #14, P2) — drop inner bottom
                // padding. Outer Row already supplies `itemSpacing` (20/32dp);
                // the inner duplicate stacked to 28/44dp total inter-row gap
                // vs iOS ~16/20pt, making timeline rows visibly over-spaced.
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val titleText = loc?.invoke("block.${block.id}.item.$index.title", item.title) ?: item.title
                    // SPEC-401-A R16 — match iOS ContentBlockRendererView.swift
                    // :902 — `.subheadline.weight(.semibold)` for ALL items;
                    // status only varies foregroundColor. Android was flipping
                    // to FontWeight.Normal for non-current items, dropping the
                    // semibold weight on completed/upcoming rows.
                    val titleBaseStyle = TextStyle(
                        // SPEC-401-A R18 — match iOS Dynamic Type defaults
                        // (`.subheadline` = 15pt at default Larger Text). Was
                        // 16.sp which diverged from system font scaling.
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        // SPEC-401-A R44 — theme-adaptive upcoming color
                        // (was Color.Gray). iOS uses .secondary on upcoming.
                        color = if (item.status == "upcoming") MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) else Color.Unspecified,
                    )
                    val titleStyle = if (block.title_style != null) {
                        StyleEngine.applyTextStyle(titleBaseStyle, block.title_style)
                    } else titleBaseStyle

                    // SPEC-401-A R17 — match iOS ContentBlockRendererView.swift
                    // :903 + :909 chained `.applyTextStyle(block.title_style)` /
                    // `.applyTextStyle(block.subtitle_style)` which apply
                    // `.textCase(.uppercase|.lowercase)` from `text_transform`.
                    // Compose TextStyle has no equivalent of textCase, so we
                    // route the raw string through `applyTransform()` (same
                    // pattern HeadingBlock + TextBlock use).
                    Text(text = block.title_style.applyTransform(titleText), style = titleStyle)

                    item.subtitle?.let { subtitle ->
                        val subtitleText = loc?.invoke("block.${block.id}.item.$index.subtitle", subtitle) ?: subtitle
                        // SPEC-401-A R18 — match iOS `.caption` = 12pt default.
                        // SPEC-401-A R44 — theme-adaptive subtitle (was Color.Gray).
                        val subtitleBaseStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        val subtitleEffective = if (block.subtitle_style != null) {
                            StyleEngine.applyTextStyle(subtitleBaseStyle, block.subtitle_style)
                        } else subtitleBaseStyle

                        Text(text = block.subtitle_style.applyTransform(subtitleText), style = subtitleEffective)
                    }
                }
            }
        }
    }
}

// MARK: - Animated Loading Block (SPEC-089d AC-017)

/**
 * Animated loading/progress screen with sequential item completion.
 * Checklist variant: items appear with animated checkmarks.
 * Circular variant: CircularProgressIndicator.
 * Linear variant: LinearProgressIndicator.
 * Supports auto_advance to trigger step advance after completion.
 */
@Composable
private fun AnimatedLoadingBlock(block: ContentBlock, onAction: (String) -> Unit) {
    // SPEC-401-A R3 — iOS canonical field name `loading_variant`
    // (ContentBlockTypes.swift:1019); Android historically only read
    // the generic `variant` so console-authored loading_variant was
    // silently dropped. Honour both.
    val variant = block.loading_variant ?: block.variant ?: "checklist"
    val items = block.loading_items ?: emptyList()
    val progressColor = StyleEngine.parseColor(block.progress_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    val checkColor = StyleEngine.parseColor(block.check_color ?: "#22C55E")
    val textColor = StyleEngine.parseColor(block.text_color ?: "#000000")
    val totalDurationMs = block.total_duration_ms
    val autoAdvance = block.auto_advance ?: false
    val showPercentage = block.show_percentage ?: false
    // EPIC-3 — configurable loading message with independent position (above/below), size, color.
    val loadingMessage = block.loading_text
    val loadingTextPos = block.loading_text_position ?: "below"
    val loadingTextSize = (block.loading_text_size ?: 15.0).sp
    // SPEC-419 pass-15 #13 — loading message color falls back loading_text_color → text_color → #9CA3AF
    // (matches iOS + preview; Android previously fell back to text_color→#000).
    val loadingMessageColor = block.loading_text_color?.let { StyleEngine.parseColor(it) }
        ?: block.text_color?.let { StyleEngine.parseColor(it) }
        ?: StyleEngine.parseColor("#9CA3AF")
    // Progress/Loading v2 — loading message horizontal alignment (default center).
    // Authored top-level by the console editor; folded into field_config on Android.
    val loadingTextAlign = when (block.field_config?.get("loading_text_align") as? String) {
        "left" -> TextAlign.Left
        "right" -> TextAlign.Right
        else -> TextAlign.Center
    }

    // Track which items have completed
    var completedCount by remember { mutableIntStateOf(0) }
    // EPIC-3 — static `progress_value` override (snapshot/preview): start at the fixed value.
    // Accept either a 0–1 fraction or a 0–100 percentage (console authors %).
    var overallProgress by remember {
        mutableStateOf(block.progress_value?.toFloat()?.let { if (it > 1f) it / 100f else it } ?: 0f)
    }
    var finished by remember { mutableStateOf(false) }

    // Sequential item completion timer
    LaunchedEffect(Unit) {
        // EPIC-3 — static progress_value override holds the value (no timer).
        if (block.progress_value != null) {
            finished = true
            return@LaunchedEffect
        }
        if (items.isNotEmpty()) {
            // Round-33 — TIME-weighted progress (cumulative item duration / total), matching iOS
            // ContentBlockStandaloneViews. Was count-weighted (i/size), so a [1000ms,3000ms] loader
            // jumped to 50% when item 0 finished (t=1s) where iOS shows 25%.
            val totalMs = items.sumOf { it.duration_ms.toLong() }.coerceAtLeast(1L)
            var cumulativeBeforeMs = 0L
            for (i in items.indices) {
                val durationMs = items[i].duration_ms.toLong()
                val startProgress = cumulativeBeforeMs.toFloat() / totalMs
                val endProgress = (cumulativeBeforeMs + durationMs).toFloat() / totalMs
                cumulativeBeforeMs += durationMs

                val steps = (durationMs / 50).toInt().coerceAtLeast(1)
                val stepDelay = durationMs / steps
                for (s in 1..steps) {
                    kotlinx.coroutines.delay(stepDelay)
                    overallProgress = startProgress + (endProgress - startProgress) * (s.toFloat() / steps)
                }
                completedCount = i + 1
            }
        } else if (totalDurationMs != null && totalDurationMs > 0) {
            // No items, just progress over total duration
            val steps = (totalDurationMs / 50).coerceAtLeast(1)
            val stepDelay = totalDurationMs.toLong() / steps
            for (s in 1..steps) {
                kotlinx.coroutines.delay(stepDelay)
                overallProgress = s.toFloat() / steps
            }
        }
        finished = true
        if (autoAdvance) {
            // SPEC-401-A R9 — match iOS items-mode auto_advance grace
            // delay at ContentBlockStandaloneViews.swift:347-352 — wait
            // 500ms after the final checklist item completes so users
            // see the completed state before the step advances.
            // (iOS timer-only mode at line 317 fires instantly; Android
            //  ProgressBar block path matches that.)
            if (items.isNotEmpty()) {
                kotlinx.coroutines.delay(500)
            }
            onAction("next")
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // SPEC-419 gap#1 — `orbiting_icons` now renders the full radial-icon
        // layout (icons arranged in a circle around a central dot), matching
        // iOS OrbitingIconsLoaderView + the console preview. Previously it
        // fell back to the plain circular spinner.
        if (loadingMessage != null && loadingTextPos == "above") {
            Text(
                text = loadingMessage,
                fontSize = loadingTextSize,
                color = loadingMessageColor,
                textAlign = loadingTextAlign,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when (variant) {
            "orbiting_icons" -> {
                OrbitingIconsLoader(
                    block = block,
                    items = items,
                    progressColor = progressColor,
                    overallProgress = overallProgress,
                )
            }
            "circular" -> {
                // SPEC-401-A R19 — match iOS ContentBlockStandaloneViews
                // .swift:228-248. iOS rotates the indicator continuously via
                // `spinAngle = 360` repeatForever AND displays the current
                // loading item's label below the circle. Android previously
                // rendered a static circle with no caption.
                val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "loading_spin")
                val spinAngle by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(
                            durationMillis = 1500,
                            easing = androidx.compose.animation.core.LinearEasing,
                        ),
                    ),
                    label = "loading_spin_angle",
                )
                Box(contentAlignment = Alignment.Center) {
                    // SPEC-401-A R51 (Lens C #2, P2) — guarantee a visible 5%
                    // arc even at 0% progress. Mirrors iOS
                    // ContentBlockStandaloneViews.swift:226
                    // `Circle().trim(from: 0, to: max(0.05, overallProgress))`.
                    // Without this the rotation animation is invisible (zero-
                    // length arc) for the first ~600ms of every step before
                    // any item ticks complete — user sees a static ring.
                    CircularProgressIndicator(
                        progress = overallProgress.coerceAtLeast(0.05f),
                        modifier = Modifier
                            .size(80.dp)
                            .graphicsLayer { rotationZ = spinAngle },
                        color = progressColor,
                        // SPEC-401-A R46 (Lens A #6) — iOS lineWidth: 5 (was 6dp).
                        // ContentBlockStandaloneViews.swift:224,227.
                        trackColor = progressColor.copy(alpha = 0.2f),
                        strokeWidth = 5.dp,
                    )
                    if (showPercentage) {
                        Text(
                            text = "${(overallProgress * 100).toInt()}%",
                            // SPEC-401-A R19 — match iOS line 232:
                            // `.font(.system(size: 16, weight: .bold))
                            // .foregroundColor(progressCol)`. Was 18.sp +
                            // text_color; now 16.sp + progress fill color.
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = progressColor,
                        )
                    }
                }
                // SPEC-401-A R19 — current loading item caption matching iOS
                // lines 240-248. Renders the item.label of the in-progress
                // item (clamped to last item when finished).
                if (items.isNotEmpty()) {
                    val currentIdx = completedCount.coerceAtMost(items.size - 1)
                    items.getOrNull(currentIdx)?.label?.takeIf { it.isNotBlank() }?.let { label ->
                        // SPEC-401-A R47 (Lens C #3) — fall back to theme-adaptive
                        // .secondary when block.text_color unset (iOS line 244).
                        Text(
                            text = label,
                            // SPEC-401-A R56→R57 (Lens A R56 #7, P3) — 15sp matches
                            // iOS .subheadline (ContentBlockStandaloneViews.swift:243).
                            fontSize = 15.sp,
                            color = if (block.text_color == null)
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            else textColor.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            "ring" -> {
                // EPIC-3 — large radial % ring (Duolingo/Flo "loading N%"): big circular ring + prominent %.
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = overallProgress.coerceIn(0f, 1f),
                        modifier = Modifier.size(160.dp),
                        color = progressColor,
                        trackColor = progressColor.copy(alpha = 0.2f),
                        strokeWidth = 12.dp,
                    )
                    Text(
                        text = "${(overallProgress * 100).toInt()}%",
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = progressColor,
                    )
                }
            }
            "cog" -> {
                // EPIC-3 — cog/gear spinner (Asana settings-style loader): thick ring + 8 flat teeth, rotating.
                val cogTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "cog_spin")
                val cogAngle by cogTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(durationMillis = 2000, easing = androidx.compose.animation.core.LinearEasing),
                    ),
                    label = "cog_angle",
                )
                Canvas(modifier = Modifier.size(80.dp).graphicsLayer { rotationZ = cogAngle }) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val outerR = size.minDimension / 2f
                    val ringR = outerR * 0.60f
                    val ringStroke = outerR * 0.22f
                    val teethCount = 8
                    val toothW = outerR * 0.20f
                    val toothH = outerR * 0.28f
                    for (i in 0 until teethCount) {
                        rotate(degrees = i * 360f / teethCount, pivot = androidx.compose.ui.geometry.Offset(cx, cy)) {
                            drawRoundRect(
                                color = progressColor,
                                topLeft = androidx.compose.ui.geometry.Offset(cx - toothW / 2f, cy - ringR - toothH * 0.55f),
                                size = androidx.compose.ui.geometry.Size(toothW, toothH),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(toothW * 0.25f),
                            )
                        }
                    }
                    drawCircle(
                        color = progressColor,
                        radius = ringR,
                        center = androidx.compose.ui.geometry.Offset(cx, cy),
                        style = Stroke(width = ringStroke),
                    )
                }
            }
            "splash_bottom" -> {
                // EPIC-3 — splash-screen loader: a small spinner anchored to the BOTTOM of the area.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((block.height ?: 360.0).dp)
                        .padding(bottom = 24.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    val splashTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "splash_spin")
                    val splashAngle by splashTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                            animation = androidx.compose.animation.core.tween(durationMillis = 900, easing = androidx.compose.animation.core.LinearEasing),
                        ),
                        label = "splash_angle",
                    )
                    Canvas(modifier = Modifier.size(32.dp).graphicsLayer { rotationZ = splashAngle }) {
                        drawArc(
                            color = progressColor,
                            startAngle = -90f,
                            sweepAngle = 110f,
                            useCenter = false,
                            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }
            }
            "linear" -> {
                // SPEC-401-A R22 — Material3 1.2 lambda form (see
                // OnboardingActivity.kt:1093 for the same pre-emptive switch).
                LinearProgressIndicator(
                    progress = { overallProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        // SPEC-401-A R19 — match iOS lines 259+262:
                        // `RoundedRectangle(cornerRadius: 4)` filling
                        // `height: 8`. Was 6dp/3dp on Android (visibly
                        // thinner bar).
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = progressColor,
                    // SPEC-401-A R46 (Lens A #9) — neutral track (was tinted
                    // copy of progressColor at 0.2). iOS uses Color.gray.opacity(0.2)
                    // (ContentBlockStandaloneViews.swift:258).
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                )
                if (showPercentage) {
                    Text(
                        text = "${(overallProgress * 100).toInt()}%",
                        fontSize = 14.sp,
                        color = textColor,
                    )
                }
            }
            else -> { /* checklist is the default, handled below */ }
        }

        // Checklist items — render ONLY for the checklist (default) variant.
        // SPEC-419 pass-14 #8 — match iOS (renders the full checklist solely in
        // the `default:` switch case, ContentBlockStandaloneViews.swift:356) +
        // the console preview (no checklist under ring/cog/splash/circular/
        // linear). The graphic variants already show their own progress UI
        // (circular even shows a current-item caption); a full checklist below
        // them was Android-only divergence. orbiting_icons consumes `items` as
        // the orbit icons, so it never renders a checklist either.
        val isChecklistVariant = variant !in
            setOf("orbiting_icons", "circular", "ring", "cog", "splash_bottom", "linear")
        if (items.isNotEmpty() && isChecklistVariant) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items.forEachIndexed { index, item ->
                    val isCompleted = index < completedCount
                    val isCurrent = index == completedCount && !finished

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Status icon
                        Box(
                            modifier = Modifier.size(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            // SPEC-401-A R46 (Lens A #7) — match iOS state
                            // language at ContentBlockStandaloneViews.swift:275-288:
                            // completed = checkmark.circle.fill (check inside
                            // filled circle); pending = hollow Circle().stroke.
                            if (isCompleted) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Done",
                                    tint = checkColor,
                                    modifier = Modifier.size(20.dp),
                                )
                            } else if (isCurrent) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = progressColor,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                // Hollow ring matching iOS .stroke pending.
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .border(
                                            width = 1.5.dp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                            shape = CircleShape,
                                        ),
                                )
                            }
                        }

                        Text(
                            text = item.label,
                            fontSize = 15.sp,
                            // SPEC-401-A R46 (Lens A #8) — match iOS color logic
                            // at ContentBlockStandaloneViews.swift:294-295:
                            // current and completed both .primary; pending
                            // .secondary. No weight switch (iOS .font(.subheadline)
                            // is regular always; was SemiBold-on-current drift).
                            // SPEC-401-A R47 (Lens C #1) — when block.text_color
                            // unset, fall back to theme-adaptive onSurface (was
                            // raw default #000000 invisible-on-dark).
                            // SPEC-419 pass-14 #6 — the ACTIVE (current) item uses
                            // accent_color ("Active Text Color") like the preview
                            // (OnboardingStepPreview.tsx:1252) + iOS. Completed uses
                            // text_color; pending stays secondary.
                            color = when {
                                isCurrent ->
                                    block.accent_color?.let { StyleEngine.parseColor(it) }
                                        ?: (if (block.text_color == null)
                                            MaterialTheme.colorScheme.onSurface else textColor)
                                isCompleted ->
                                    if (block.text_color == null)
                                        MaterialTheme.colorScheme.onSurface
                                    else textColor
                                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            },
                            // SPEC-401-A R47 (Lens C #2) — drop SemiBold-on-current
                            // weight switch; iOS .font(.subheadline) regular always.
                        )
                    }
                }
            }
        }
        if (loadingMessage != null && loadingTextPos == "below") {
            Text(
                text = loadingMessage,
                fontSize = loadingTextSize,
                color = loadingMessageColor,
                textAlign = loadingTextAlign,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * SPEC-419 gap#1 — orbiting_icons loading layout. Icons arranged in a circle
 * around a central dot, rotating continuously. Reads its layout config from
 * `field_config` (no top-level ContentBlock params), mirroring iOS
 * `OrbitingIconsLoaderView` + the console preview.
 */
@Composable
private fun OrbitingIconsLoader(
    block: ContentBlock,
    items: List<LoadingItem>,
    progressColor: Color,
    overallProgress: Float,
) {
    val cfg = block.field_config
    fun cfgDouble(key: String): Double? = (cfg?.get(key) as? Number)?.toDouble()
    val sizeDp = (cfgDouble("size") ?: 240.0)
    val orbitRadius = (cfgDouble("orbit_radius") ?: 80.0)
    val centralSize = (cfgDouble("central_size") ?: 10.0).dp
    val centralBgHex = cfg?.get("central_bg_color") as? String ?: "#FEE2E2"
    val ringColorHex = cfg?.get("ring_color") as? String ?: "#D1D5DB"
    val ringWidth = (cfgDouble("ring_width") ?: 1.0).dp
    val ringOpacity = (cfgDouble("ring_opacity") ?: 0.5).toFloat()
    val labelSize = (cfgDouble("label_font_size") ?: 17.0).sp
    val subtitleSize = (cfgDouble("subtitle_font_size") ?: 14.0).sp
    val labelColor = StyleEngine.parseColor(cfg?.get("label_color") as? String ?: "#0F172A")
    val subtitleColor = StyleEngine.parseColor(cfg?.get("subtitle_color") as? String ?: "#E11D48")
    val showPercentage = block.show_percentage ?: false
    val pctLocation = cfg?.get("percentage_location") as? String ?: "below"
    // SPEC-419 pass-16 #3 — honor field_config.animated_bg ("none"|"constellation"|"pulse")
    // + animated_bg_color. Mirrors iOS ContentBlockStandaloneViews.swift:603-623; was
    // never rendered on Android.
    val animatedBg = cfg?.get("animated_bg") as? String ?: "none"
    val animatedBgColor = StyleEngine.parseColor(cfg?.get("animated_bg_color") as? String ?: "#EEEEEE")

    val orbitTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "orbit_spin")
    val bgPulse by orbitTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(
                durationMillis = 1500,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bg_pulse",
    )
    val rotation by orbitTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(
                durationMillis = ((cfgDouble("orbit_duration_ms") ?: 6000.0).toInt()),
                easing = androidx.compose.animation.core.LinearEasing,
            ),
        ),
        label = "orbit_angle",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (showPercentage && pctLocation == "above") {
            Text(
                text = "${(overallProgress * 100).toInt()}%",
                fontSize = labelSize,
                fontWeight = FontWeight.Bold,
                color = labelColor,
            )
        }

        Box(
            modifier = Modifier.size(sizeDp.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Animated background (optional) — mirrors iOS pulse/constellation.
            when (animatedBg) {
                "constellation" -> Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(animatedBgColor.copy(alpha = 0.2f), Color.Transparent),
                            ),
                        ),
                )
                "pulse" -> Box(
                    modifier = Modifier
                        .size((sizeDp * (1.0 + 0.1 * bgPulse)).dp)
                        .clip(CircleShape)
                        .background(animatedBgColor.copy(alpha = 0.15f + 0.15f * bgPulse)),
                )
            }
            // Orbit ring
            Box(
                modifier = Modifier
                    .size((orbitRadius * 2).dp)
                    .border(ringWidth, StyleEngine.parseColor(ringColorHex).copy(alpha = ringOpacity), CircleShape),
            )
            // Central dot/image — SPEC-419 pass-13: render central_image_url when
            // authored (was central-color dot only). Mirrors iOS
            // ContentBlockStandaloneViews.swift:528-540.
            val centralImageUrl = (cfg?.get("central_image_url") as? String)?.takeIf { it.isNotBlank() }
            if (centralImageUrl != null) {
                ai.appdna.sdk.core.NetworkImage(
                    url = centralImageUrl,
                    modifier = Modifier
                        .size(centralSize)
                        .clip(CircleShape)
                        .background(StyleEngine.parseColor(centralBgHex)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(centralSize)
                        .clip(CircleShape)
                        .background(StyleEngine.parseColor(centralBgHex)),
                )
            }
            // Orbiting icons
            Box(
                modifier = Modifier.graphicsLayer { rotationZ = rotation },
                contentAlignment = Alignment.Center,
            ) {
                items.forEachIndexed { idx, item ->
                    val baseAngle = item.icon_orbit_angle?.toDouble()
                        ?: (360.0 * idx / maxOf(items.size, 1))
                    val rad = baseAngle * Math.PI / 180.0
                    val xOff = (cos(rad) * orbitRadius).dp
                    val yOff = (sin(rad) * orbitRadius).dp
                    val iconSize = (item.icon_size ?: 48f).dp
                    val iconBg = StyleEngine.parseColor(item.icon_bg_color ?: "#BE123C")
                    Box(
                        modifier = Modifier
                            .offset(x = xOff, y = yOff)
                            .size(iconSize)
                            .clip(CircleShape)
                            .background(iconBg),
                        contentAlignment = Alignment.Center,
                    ) {
                        val iconUrl = item.icon_url
                        val iconGlyph = item.icon
                        when {
                            !iconUrl.isNullOrBlank() -> ai.appdna.sdk.core.NetworkImage(
                                url = iconUrl,
                                modifier = Modifier.fillMaxSize(0.6f).clip(CircleShape),
                            )
                            !iconGlyph.isNullOrBlank() -> Text(
                                text = iconGlyph,
                                fontSize = (item.icon_size ?: 48f).times(0.45f).sp,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }

        // Title label (block.text) + active item subtitle
        block.text?.takeIf { it.isNotBlank() }?.let { title ->
            Text(
                text = title,
                fontSize = labelSize,
                fontWeight = FontWeight.SemiBold,
                color = labelColor,
                textAlign = TextAlign.Center,
            )
        }
        items.firstOrNull()?.label?.takeIf { it.isNotBlank() }?.let { subtitle ->
            Text(
                text = subtitle,
                fontSize = subtitleSize,
                color = subtitleColor,
                textAlign = TextAlign.Center,
            )
        }

        if (showPercentage && pctLocation == "below") {
            Text(
                text = "${(overallProgress * 100).toInt()}%",
                fontSize = labelSize,
                fontWeight = FontWeight.Bold,
                color = labelColor,
            )
        }
    }
}

// MARK: - Circular Gauge Block (SPEC-089d AC-022)

/**
 * Circular arc gauge with center label. Supports animated fill via animateFloatAsState.
 */
@Composable
private fun CircularGaugeBlock(block: ContentBlock) {
    // SPEC-401-A R19 — match iOS ContentBlockStandaloneViews.swift:679
    // which only checks `gauge_value ?? progress_value ?? 0`. Android also
    // fell through to `block.default_value` which iOS ignores — same JSON
    // `{ "default_value": 75 }` rendered as 0 on iOS, 75 on Android.
    val value = (block.gauge_value ?: block.progress_value ?: 0.0).toFloat()
    val minVal = (block.min_value ?: 0.0).toFloat()
    // SPEC-401-A R11 — full port of iOS `CircularGaugeBlockView`
    // (ContentBlockStandaloneViews.swift:673-907). Variants: `arc`
    // (default 200), `radial` (full ring), `speedometer` (220° sweep,
    // min 240). Renders min/max labels, animated needle, gradient
    // fill via Brush.sweepGradient, and per-anchor percentage
    // (above/center/below/none).
    val maxVal = (block.max_value ?: block.max_gauge_value ?: 100.0).toFloat()
    val targetProgress = if ((maxVal - minVal) > 0f) ((value - minVal) / (maxVal - minVal)).coerceIn(0f, 1f) else 0f
    val variant = block.gauge_variant ?: "arc"
    val defaultSize = if (variant == "speedometer") 280.0 else 200.0
    val rawSize = block.height ?: defaultSize
    val sizePx = if (variant == "speedometer") maxOf(rawSize, 240.0) else rawSize
    val size = sizePx.dp
    val strokeW = (block.stroke_width ?: 14.0).toFloat()
    // SPEC-401-A R19 — match iOS line 690 fallback chain `bar_color ??
    // active_color ?? "#6366F1"`. Android also honored `fill_color` which
    // iOS doesn't — console-published `fill_color` rendered only on
    // Android.
    val fillColor = StyleEngine.parseColor(block.bar_color ?: block.active_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    val trackColor = StyleEngine.parseColor(block.track_color ?: "#E5E7EB")
    val labelColor = StyleEngine.parseColor(block.label_color ?: block.text_color ?: "#000000")
    val labelFontSize = (block.label_font_size ?: block.font_size ?: 24.0).sp
    val shouldAnimate = block.animate ?: true
    val animDurationMs = block.animation_duration_ms ?: 800
    val showPct = block.show_percentage ?: false
    val pctLocation = block.percentage_location ?: "below"

    // SPEC-401-A R72 (Lens A P1) — gauge gradient_start_color +
    // gradient_end_color live INSIDE field_config, NOT at the top-level
    // ContentBlock. iOS ContentBlockStandaloneViews.swift:703-705 reads
    // `block.field_config?["gradient_start_color"]`. Console editor at
    // `src/.../StepContentEditor.tsx:3814-3815` writes to
    // `field_config.gradient_start_color`. Android was reading the
    // top-level field which the parser never populates from field_config
    // — every console-published gauge gradient rendered as a solid color
    // on Android while sweeping correctly on iOS. Read field_config first,
    // fall back to top-level for back-compat.
    val gradStartHex = (block.field_config?.get("gradient_start_color") as? String)
        ?: block.gradient_start_color
    val gradEndHex = (block.field_config?.get("gradient_end_color") as? String)
        ?: block.gradient_end_color
    val useGradient = !gradStartHex.isNullOrEmpty() && !gradEndHex.isNullOrEmpty()
    val gradColors: List<Color> = if (useGradient) {
        listOf(StyleEngine.parseColor(gradStartHex!!), StyleEngine.parseColor(gradEndHex!!))
    } else {
        listOf(fillColor, fillColor)
    }

    // Needle / min-max styling (speedometer)
    val needleCol = StyleEngine.parseColor(block.arrow_color ?: block.label_color ?: "#1F2937")
    val needleW = (block.arrow_stroke_width ?: 3.0).toFloat()
    val minLabel = block.min_label ?: "${minVal.toInt()}"
    val maxLabel = block.max_label ?: "${maxVal.toInt()}"
    val minMaxFontSz = (block.min_max_font_size ?: 13.0).sp
    val minMaxCol = StyleEngine.parseColor(block.min_max_color ?: block.label_color ?: "#000000")

    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = if (shouldAnimate) tween(durationMillis = animDurationMs) else tween(0),
        label = "gauge_progress",
    )

    val hasCenterText = !block.text.isNullOrEmpty()
    val pctExplicitElsewhere = pctLocation == "above" || pctLocation == "below"
    val shouldShowCenter = pctLocation == "center"
        || hasCenterText
        || (showPct && !pctExplicitElsewhere)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showPct && pctLocation == "above") {
            Text(
                text = "${(animatedProgress * 100).roundToInt()}%",
                fontSize = labelFontSize,
                fontWeight = FontWeight.Bold,
                color = labelColor,
            )
        }

        when (variant) {
            "speedometer" -> {
                // 220° symmetric sweep around top (12 o'clock).
                val sweepDeg = 220.0
                val endpointRad = Math.toRadians(sweepDeg / 2.0)
                val endpointSinX = kotlin.math.sin(endpointRad).toFloat()  // ≈ 0.94
                val endpointCosY = kotlin.math.abs(kotlin.math.cos(endpointRad)).toFloat() // ≈ 0.34
                val canvasH = (sizePx * 0.66).dp + minMaxFontSz.value.dp + 32.dp
                Box(
                    modifier = Modifier
                        .size(width = size, height = canvasH)
                        .padding(strokeW.dp / 2),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cx = this.size.width / 2f
                        val radius = (this.size.width / 2f) - strokeW / 2f
                        val cy = radius + strokeW / 2f
                        // Speedometer angle math: SwiftUI Path arc uses iOS coordinate system;
                        // Compose drawArc startAngle is measured from 3 o'clock (right) clockwise.
                        // To replicate iOS sweep starting at 270° - sweep/2, we offset by -90.
                        // iOS startAngle: 270° - sweep/2 → Compose: 180° - sweep/2
                        val startAngle = 180f - (sweepDeg / 2.0).toFloat()
                        val arcSize = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f)
                        val arcOffset = androidx.compose.ui.geometry.Offset(cx - radius, cy - radius)
                        // Track
                        drawArc(
                            color = trackColor,
                            startAngle = startAngle,
                            sweepAngle = sweepDeg.toFloat(),
                            useCenter = false,
                            style = Stroke(width = strokeW, cap = StrokeCap.Round),
                            topLeft = arcOffset,
                            size = arcSize,
                        )
                        // Fill
                        val fillSweep = sweepDeg.toFloat() * animatedProgress
                        if (useGradient) {
                            drawArc(
                                brush = Brush.sweepGradient(gradColors, center = androidx.compose.ui.geometry.Offset(cx, cy)),
                                startAngle = startAngle,
                                sweepAngle = fillSweep,
                                useCenter = false,
                                style = Stroke(width = strokeW, cap = StrokeCap.Round),
                                topLeft = arcOffset,
                                size = arcSize,
                            )
                        } else {
                            drawArc(
                                color = fillColor,
                                startAngle = startAngle,
                                sweepAngle = fillSweep,
                                useCenter = false,
                                style = Stroke(width = strokeW, cap = StrokeCap.Round),
                                topLeft = arcOffset,
                                size = arcSize,
                            )
                        }
                        // Needle — rotated around (cx, cy).
                        val needleLen = radius - strokeW / 2f - 12f
                        val needleAngleFromUp = -sweepDeg / 2.0 + sweepDeg * animatedProgress
                        // Convert "up = 0°" to Compose canvas: subtract 90° because Canvas
                        // rotation is from 3 o'clock; so needle_angle_canvas = needleAngleFromUp - 90°
                        val needleAngleRad = Math.toRadians(needleAngleFromUp - 90.0)
                        val tipX = cx + (kotlin.math.cos(needleAngleRad) * needleLen).toFloat()
                        val tipY = cy + (kotlin.math.sin(needleAngleRad) * needleLen).toFloat()
                        drawLine(
                            color = needleCol,
                            start = androidx.compose.ui.geometry.Offset(cx, cy),
                            end = androidx.compose.ui.geometry.Offset(tipX, tipY),
                            strokeWidth = needleW,
                            cap = StrokeCap.Round,
                        )
                        // Hub circle
                        drawCircle(
                            color = needleCol,
                            radius = maxOf(6f, needleW * 1.75f),
                            center = androidx.compose.ui.geometry.Offset(cx, cy),
                        )
                    }
                    // Min/Max labels positioned at endpoint coordinates (relative to box).
                    val density = LocalDensity.current
                    val sizePxFloat = with(density) { size.toPx() }
                    val cxPx = sizePxFloat / 2f
                    val radiusPx = sizePxFloat / 2f - strokeW / 2f
                    val cyPx = radiusPx + strokeW / 2f
                    val endpointX = endpointSinX * radiusPx
                    val endpointY = endpointCosY * radiusPx
                    val labelOffsetXmin = with(density) { (cxPx - endpointX).toDp() }
                    val labelOffsetXmax = with(density) { (cxPx + endpointX).toDp() }
                    val labelOffsetY = with(density) { (cyPx + endpointY + strokeW).toDp() }
                    Text(
                        text = minLabel,
                        fontSize = minMaxFontSz,
                        fontWeight = FontWeight.Medium,
                        color = minMaxCol,
                        modifier = Modifier.absoluteOffset(x = labelOffsetXmin - 12.dp, y = labelOffsetY),
                    )
                    Text(
                        text = maxLabel,
                        fontSize = minMaxFontSz,
                        fontWeight = FontWeight.Medium,
                        color = minMaxCol,
                        modifier = Modifier.absoluteOffset(x = labelOffsetXmax - 12.dp, y = labelOffsetY),
                    )
                    // Center value (positioned in the bowl below center).
                    if (shouldShowCenter) {
                        val centerY = with(density) { (cyPx + radiusPx * 0.5f).toDp() }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .absoluteOffset(y = centerY - labelFontSize.value.dp / 2),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = if (showPct) "${(animatedProgress * 100).roundToInt()}%"
                                    else if (hasCenterText) block.text!!
                                    else "${value.toInt()}",
                                fontSize = labelFontSize,
                                fontWeight = FontWeight.Bold,
                                color = labelColor,
                            )
                            block.sublabel?.takeIf { it.isNotEmpty() }?.let {
                                Text(
                                    text = it,
                                    fontSize = (kotlin.math.min(labelFontSize.value * 0.5f, 13f)).sp,
                                    color = labelColor.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                }
            }
            "linear" -> {
                // SPEC-419 pass-15 #2 — horizontal bar gauge (mirrors preview linear variant):
                // value-indicator triangle + label above, gradient bar, ticks 0/25/50/75/100 below.
                val density = LocalDensity.current
                val barHeight = maxOf(8.0, sizePx * 0.08).dp
                val totalWidth = (sizePx * 1.2).dp
                val pct = animatedProgress
                val labelText = if (!block.text.isNullOrEmpty()) block.text!! else "${(animatedProgress * 100).roundToInt()}%"
                val ticks = listOf(0, 25, 50, 75, 100)
                val totalWidthPx = with(density) { totalWidth.toPx() }
                Column(
                    modifier = Modifier.width(totalWidth),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Box(modifier = Modifier.width(totalWidth).height(22.dp)) {
                        val indicatorOffset = with(density) { (pct * totalWidthPx - 5f).toDp() }
                        Column(
                            modifier = Modifier.absoluteOffset(x = indicatorOffset),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(text = labelText, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fillColor)
                            Canvas(modifier = Modifier.size(width = 10.dp, height = 6.dp)) {
                                val w = this.size.width
                                val h = this.size.height
                                val path = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(0f, 0f)
                                    lineTo(w, 0f)
                                    lineTo(w / 2f, h)
                                    close()
                                }
                                drawPath(path, color = fillColor)
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .width(totalWidth)
                            .height(barHeight)
                            .clip(RoundedCornerShape(barHeight / 2))
                            .background(trackColor),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(with(density) { (pct * totalWidthPx).toDp() })
                                .clip(RoundedCornerShape(barHeight / 2))
                                .background(Brush.horizontalGradient(listOf(fillColor.copy(alpha = 0.53f), fillColor))),
                        )
                    }
                    Box(modifier = Modifier.width(totalWidth).height(16.dp)) {
                        ticks.forEach { tick ->
                            val tickOffset = with(density) { ((tick / 100f) * totalWidthPx - 4f).toDp() }
                            Column(
                                modifier = Modifier.absoluteOffset(x = tickOffset),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(modifier = Modifier.width(1.dp).height(4.dp).background(StyleEngine.parseColor("#D1D5DB")))
                                Text(text = "$tick", fontSize = 8.sp, color = StyleEngine.parseColor("#9CA3AF"))
                            }
                        }
                    }
                }
            }
            else -> {
                // arc / radial — full square Box with Canvas + center label.
                val isRadial = variant == "radial"
                val effectiveStroke = if (isRadial) strokeW * 2f else strokeW
                Box(
                    modifier = Modifier.size(size).padding((effectiveStroke / 2).dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Track
                        drawArc(
                            color = trackColor,
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = effectiveStroke, cap = StrokeCap.Round),
                        )
                        // Fill — start at 12 o'clock (-90°)
                        val fillSweep = animatedProgress * 360f
                        if (useGradient) {
                            drawArc(
                                brush = Brush.sweepGradient(gradColors),
                                startAngle = -90f,
                                sweepAngle = fillSweep,
                                useCenter = false,
                                style = Stroke(width = effectiveStroke, cap = StrokeCap.Round),
                            )
                        } else {
                            drawArc(
                                color = fillColor,
                                startAngle = -90f,
                                sweepAngle = fillSweep,
                                useCenter = false,
                                style = Stroke(width = effectiveStroke, cap = StrokeCap.Round),
                            )
                        }
                    }
                    if (shouldShowCenter) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (showPct) "${(animatedProgress * 100).roundToInt()}%"
                                    else if (hasCenterText) block.text!!
                                    else "${value.toInt()}",
                                fontSize = labelFontSize,
                                fontWeight = FontWeight.Bold,
                                color = labelColor,
                            )
                            block.sublabel?.takeIf { it.isNotEmpty() }?.let {
                                Text(
                                    text = it,
                                    fontSize = 12.sp,
                                    color = labelColor.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showPct && pctLocation == "below") {
            Text(
                text = "${(animatedProgress * 100).roundToInt()}%",
                fontSize = labelFontSize,
                fontWeight = FontWeight.Bold,
                color = labelColor,
            )
        }
    }
}

// MARK: - Date Wheel Picker Block (SPEC-089d AC-023)

/**
 * Parse a seed date for the date wheel. Prefers a prior saved answer
 * ("yyyy-MM-dd" or "yyyy-MM-dd HH:mm") so re-entry restores the user's pick,
 * else the authored `default_date_value` — "today"/"now", a relative offset
 * ("-18y", "+1y", "-30d", "-6m"), or an ISO "yyyy-MM-dd". Returns null when
 * neither is usable so a required field with no default still forces the user
 * to spin the wheel. Mirrors iOS restoreDate() + parseDate()
 * (ContentBlockStandaloneViews.swift).
 */
private fun parseDateWheelSeed(saved: String?, default: String?): java.util.Calendar? {
    fun fromIso(s: String?): java.util.Calendar? {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val datePart = t.substringBefore(' ')
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").find(datePart) ?: return null
        val (y, mo, d) = m.destructured
        // Reject out-of-range month/day BEFORE Calendar.set — Calendar is lenient
        // and would roll "2000-13-45" over to a real-but-wrong date, whereas iOS's
        // non-lenient DateFormatter returns nil. Match iOS: garbage → no seed.
        if (mo.toInt() !in 1..12 || d.toInt() !in 1..31) return null
        val cal = java.util.Calendar.getInstance()
        cal.isLenient = false
        cal.set(y.toInt(), mo.toInt() - 1, d.toInt())
        try { cal.time } catch (e: Exception) { return null }  // e.g. Feb 30 → reject like iOS
        cal.isLenient = true  // date validated; allow normal handling for the optional time below
        Regex("""(\d{2}):(\d{2})""").find(t.substringAfter(' ', ""))?.let { tm ->
            val (h, mi) = tm.destructured
            cal.set(java.util.Calendar.HOUR_OF_DAY, h.toInt())
            cal.set(java.util.Calendar.MINUTE, mi.toInt())
        }
        return cal
    }
    fun fromDefault(s: String?): java.util.Calendar? {
        val t = s?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (t == "today" || t == "now") return java.util.Calendar.getInstance()
        val last = t.last()
        if (last in "dmy") {
            val amt = t.dropLast(1).toIntOrNull()
            if (amt != null) {
                val c = java.util.Calendar.getInstance()
                when (last) {
                    'd' -> c.add(java.util.Calendar.DAY_OF_YEAR, amt)
                    'm' -> c.add(java.util.Calendar.MONTH, amt)
                    'y' -> c.add(java.util.Calendar.YEAR, amt)
                }
                return c
            }
        }
        return fromIso(s)
    }
    return fromIso(saved) ?: fromDefault(default)
}

/**
 * Date picker using Material3 DatePickerDialog or simplified column picker.
 * For simplicity, renders three side-by-side LazyColumns for day/month/year.
 */
@Composable
private fun DateWheelPickerBlock(block: ContentBlock, inputValues: MutableMap<String, Any>) {
    val fieldId = block.field_id ?: block.id
    val highlightColor = StyleEngine.parseColor(block.highlight_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    // SPEC-401-A R77 (Lens C P1) — `wheel_text_color` / `text_color` /
    // `field_style.text_color` author override mirrors iOS
    // ContentBlockStandaloneViews.swift:1026-1028 + 1338-1339. Was
    // hard-locked to MaterialTheme.colorScheme.onSurface.alpha(0.6f) on
    // every column — author intent (e.g. white wheel text on dark card)
    // silently dropped on Android.
    val wheelTextColorHex = (block.field_config?.get("wheel_text_color") as? String)
        ?: block.text_color
        ?: block.field_style?.text_color
    val wheelTextColor = wheelTextColorHex?.let { StyleEngine.parseColor(it) }
        ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    // SPEC-401-A R57 (Lens C R57 #2, P3) — host View for SELECTION haptic on
    // wheel-column tap, mirroring iOS UIPickerView/UIDatePicker auto-emitting
    // selectionChanged() on every wheel snap (system behavior on iOS 13+).
    // Sibling WheelPickerBlock (line ~3953) already fires SELECTION haptic.
    val view = androidx.compose.ui.platform.LocalView.current
    // SPEC — `haptic_on_scroll == false` suppresses the wheel selection tick
    // (author opt-out); nil/true keep the native UIPickerView-style feedback.
    // Mirrors iOS WheelPickerBlockView gate.
    val fireHaptic: () -> Unit = {
        if (block.haptic_on_scroll != false) {
            ai.appdna.sdk.core.HapticEngine.trigger(view, ai.appdna.sdk.core.HapticType.SELECTION)
        }
    }

    // SPEC-419 — honor picker_mode (date/datetime/time): add hour/minute columns for time modes.
    val mode = (block.picker_mode ?: "date").lowercase()
    val showTime = mode == "datetime" || mode == "date_time" || mode == "time"
    val showDate = mode != "time"
    // SPEC-419 — honor wheel_height (was hardcoded 150dp), wheel_bg_color, date_validation_message,
    // and the block-level `text` label (none were rendered before).
    val wheelHeightDp = (block.wheel_height ?: 200.0).dp
    val wheelBg = block.wheel_bg_color
        ?.takeIf { it.isNotBlank() && it.lowercase() != "transparent" }
        ?.let { StyleEngine.parseColor(it) }
    val outerLabel = block.text
    val validationMsg = block.date_validation_message
    // Column inner padding centers the selected row under the highlight strip (40dp): (h-40)/2.
    val colPad = (((wheelHeightDp.value - 40f) / 2f).coerceAtLeast(0f)).dp
    // SPEC — honor authored inter-column spacing (was hardcoded 4dp).
    val colSpacing = (block.picker_spacing ?: 4.0).dp

    // SPEC-419 — year range from min_date/max_date + allow_future/allow_past (was hardcoded
    // 1950..2030, ignoring the authored constraints). Mirrors iOS dateRange (-150y..+50y default).
    val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    // SPEC-419 P1 — parse relative-date strings ("today"/"now", "-18y", "+1y",
    // "-30d", "-6m") the editor promotes (StepContentEditor min/max placeholders)
    // in addition to ISO "yyyy-MM-dd". Mirrors iOS parseDate()
    // (ContentBlockStandaloneViews.swift:1014-1037). Previously substring(0,4)
    // only → relative strings returned null/garbage → the 18+ DOB age-gate was
    // unenforced on Android vs iOS.
    fun yearOf(s: String?): Int? {
        val trimmed = s?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed == "today" || trimmed == "now") return currentYear
        val lastChar = trimmed.last()
        if (lastChar in "dmy") {
            val amount = trimmed.dropLast(1).toIntOrNull()
            if (amount != null) {
                val c = java.util.Calendar.getInstance()
                when (lastChar) {
                    'd' -> c.add(java.util.Calendar.DAY_OF_YEAR, amount)
                    'm' -> c.add(java.util.Calendar.MONTH, amount)
                    'y' -> c.add(java.util.Calendar.YEAR, amount)
                }
                return c.get(java.util.Calendar.YEAR)
            }
        }
        // Absolute ISO date "yyyy-MM-dd"
        return trimmed.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()
    }
    var minYear = yearOf(block.min_date) ?: (currentYear - 150)
    var maxYear = yearOf(block.max_date) ?: (currentYear + 50)
    if (block.allow_future == false) maxYear = minOf(maxYear, currentYear)
    if (block.allow_past == false) minYear = maxOf(minYear, currentYear)
    if (minYear > maxYear) minYear = maxYear
    val years = (minYear..maxYear).toList()

    // Simple day/month/year selectors
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    // SPEC — seed initial selection from a prior saved answer or the authored
    // default_date_value ("today"/"-18y"/ISO) instead of always opening on
    // Jan / 01 / 2000. seedCal is null only when there is neither a saved
    // answer nor an authored default, so required-field validation still
    // forces a spin. Mirrors iOS restoreDate() + default_date_value handling.
    val seedCal = remember(minYear, maxYear) {
        val raw = parseDateWheelSeed(inputValues[fieldId] as? String, block.default_date_value)
        // Clamp the whole seeded date into [min_date, max_date] (parity with iOS
        // `min(max(seeded, lower), upper)`), so an authored default before min_date
        // (or after max_date) doesn't open + emit an out-of-range value. min/max
        // parse the same relative/ISO grammar as the default.
        val minB = parseDateWheelSeed(null, block.min_date)
        val maxB = parseDateWheelSeed(null, block.max_date)
        raw?.let { s ->
            when {
                minB != null && s.before(minB) -> minB
                maxB != null && s.after(maxB) -> maxB
                else -> s
            }
        }
    }
    val seedYear = (seedCal?.get(java.util.Calendar.YEAR) ?: 2000).coerceIn(minYear, maxYear)
    val seedMonth = seedCal?.let { it.get(java.util.Calendar.MONTH) + 1 } ?: 1
    val seedDay = seedCal?.get(java.util.Calendar.DAY_OF_MONTH) ?: 1
    val seedHour = seedCal?.get(java.util.Calendar.HOUR_OF_DAY) ?: 0
    val seedMinute = seedCal?.get(java.util.Calendar.MINUTE) ?: 0

    var selectedDay by remember { mutableIntStateOf(seedDay) }
    var selectedMonth by remember { mutableIntStateOf(seedMonth) }
    var selectedYear by remember(minYear, maxYear) { mutableIntStateOf(seedYear) }
    var selectedHour by remember { mutableIntStateOf(seedHour) }
    var selectedMinute by remember { mutableIntStateOf(seedMinute) }

    // SPEC-419 — emit the combined value honoring the active mode.
    fun emit() {
        val parts = mutableListOf<String>()
        if (showDate) parts.add(String.format(java.util.Locale.US, "%04d-%02d-%02d", selectedYear, selectedMonth, selectedDay))
        if (showTime) parts.add(String.format(java.util.Locale.US, "%02d:%02d", selectedHour, selectedMinute))
        inputValues[fieldId] = parts.joinToString(" ")
    }

    // SPEC — persist the seed once so a restored answer or an authored default
    // is the submitted value without requiring a spin (parity with iOS, where
    // restoreDate() → onChange → persistDate()). Skipped when seedCal is null
    // so a required field with no default still gates on interaction.
    LaunchedEffect(Unit) {
        if (seedCal != null) emit()
    }

    // Each column opens centered on the seeded value — contentPadding=colPad
    // pushes the first visible item to the viewport midpoint. Previously all
    // columns opened at index 0 while selectedYear=2000, so the highlighted
    // year (min-year) disagreed with the stored value.
    val dayListState = rememberLazyListState(initialFirstVisibleItemIndex = (seedDay - 1).coerceIn(0, 30))
    val monthListState = rememberLazyListState(initialFirstVisibleItemIndex = (seedMonth - 1).coerceIn(0, 11))
    val yearListState = rememberLazyListState(initialFirstVisibleItemIndex = years.indexOf(seedYear).coerceAtLeast(0))
    val hourListState = rememberLazyListState(initialFirstVisibleItemIndex = seedHour.coerceIn(0, 23))
    val minuteListState = rememberLazyListState(initialFirstVisibleItemIndex = seedMinute.coerceIn(0, 59))

    // SPEC-401-A R62 (Lens C P1) — viewport-center math instead of
    // `firstVisibleItemIndex == index`. Without this, only the literal
    // first row was "center" — bold/highlighted text never aligned with
    // the visual selection strip once the user scrolled. Mirrors
    // WheelPickerBlock pattern (line ~4031).
    fun centeredIndexOf(state: androidx.compose.foundation.lazy.LazyListState): Int {
        val info = state.layoutInfo
        if (info.visibleItemsInfo.isEmpty()) return state.firstVisibleItemIndex
        val viewportCenter = info.viewportStartOffset +
            (info.viewportEndOffset - info.viewportStartOffset) / 2
        return info.visibleItemsInfo.minByOrNull {
            kotlin.math.abs((it.offset + it.size / 2) - viewportCenter)
        }?.index ?: state.firstVisibleItemIndex
    }
    val monthCentered by remember { derivedStateOf { centeredIndexOf(monthListState) } }
    val dayCentered by remember { derivedStateOf { centeredIndexOf(dayListState) } }
    val yearCentered by remember { derivedStateOf { centeredIndexOf(yearListState) } }
    val hourCentered by remember { derivedStateOf { centeredIndexOf(hourListState) } }
    val minuteCentered by remember { derivedStateOf { centeredIndexOf(minuteListState) } }

    // SPEC — persist the centered value on SCROLL-settle, not just on tap. iOS's
    // native DatePicker(.wheel) and the sibling WheelPickerBlock both persist on
    // scroll; the date wheel previously only wrote selected*/emit() from the
    // `.clickable` tap handlers, so a user who scrolled a drum and released
    // WITHOUT tapping the centered row advanced with the OLD value while the
    // wheel visibly showed the new one. `hasUserInteracted` gates the sync so the
    // initial seed layout (centeredIndex settling on the seeded row) can't clobber
    // selected* before the user actually scrolls. Keyed on isScrollInProgress so
    // we only commit once the drum snaps.
    var hasUserInteracted by remember { mutableStateOf(false) }
    LaunchedEffect(monthCentered, monthListState.isScrollInProgress) {
        if (monthListState.isScrollInProgress) hasUserInteracted = true
        else if (hasUserInteracted && showDate && monthCentered in 0..11 && monthCentered + 1 != selectedMonth) {
            selectedMonth = monthCentered + 1; emit()
        }
    }
    LaunchedEffect(dayCentered, dayListState.isScrollInProgress) {
        if (dayListState.isScrollInProgress) hasUserInteracted = true
        else if (hasUserInteracted && showDate && dayCentered in 0..30 && dayCentered + 1 != selectedDay) {
            selectedDay = dayCentered + 1; emit()
        }
    }
    LaunchedEffect(yearCentered, yearListState.isScrollInProgress) {
        if (yearListState.isScrollInProgress) hasUserInteracted = true
        else if (hasUserInteracted && showDate && yearCentered in years.indices && years[yearCentered] != selectedYear) {
            selectedYear = years[yearCentered]; emit()
        }
    }
    LaunchedEffect(hourCentered, hourListState.isScrollInProgress) {
        if (hourListState.isScrollInProgress) hasUserInteracted = true
        else if (hasUserInteracted && showTime && hourCentered in 0..23 && hourCentered != selectedHour) {
            selectedHour = hourCentered; emit()
        }
    }
    LaunchedEffect(minuteCentered, minuteListState.isScrollInProgress) {
        if (minuteListState.isScrollInProgress) hasUserInteracted = true
        else if (hasUserInteracted && showTime && minuteCentered in 0..59 && minuteCentered != selectedMinute) {
            selectedMinute = minuteCentered; emit()
        }
    }

    // SPEC-419 — outer Column carries the block-level label + validation message; the wheel honors
    // the authored height + background color.
    Column(modifier = Modifier.fillMaxWidth()) {
    if (!outerLabel.isNullOrBlank()) {
        Text(
            text = outerLabel,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(wheelHeightDp)
            .then(if (wheelBg != null) Modifier.background(wheelBg, RoundedCornerShape(8.dp)) else Modifier),
    ) {
        // SPEC-401-A R62 (Lens C P1) — visible center-strip overlay so
        // users can see WHERE the selection actually lives. Sits behind
        // the LazyColumn Row at viewport-center. Mirrors
        // WheelPickerBlock highlight strip (line 4161-4166).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .align(Alignment.Center)
                .background(highlightColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
        )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(wheelHeightDp),
        horizontalArrangement = Arrangement.spacedBy(colSpacing),
    ) {
        // Month column
        if (showDate) {
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = monthListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = colPad),
                horizontalAlignment = Alignment.CenterHorizontally,
                flingBehavior = rememberSnapFlingBehavior(lazyListState = monthListState),
            ) {
                items(12) { index ->
                    val isCenter = monthCentered == index
                    Text(
                        text = months[index],
                        fontSize = if (isCenter) 18.sp else 14.sp,
                        fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                        // SPEC-401-A R50 (Lens C #3, P1) — DateWheelPicker
                        // non-center text uses theme onSurface.alpha(0.6)
                        // matching iOS .secondary (was Color.Gray, illegible
                        // in dark mode).
                        color = if (isCenter) highlightColor else wheelTextColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable {
                                selectedMonth = index + 1
                                emit()
                                // SPEC-401-A R57 — SELECTION haptic mirrors iOS
                                // UIPickerView tick; gated by haptic_on_scroll.
                                fireHaptic()
                            },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // Day column
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = dayListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = colPad),
                horizontalAlignment = Alignment.CenterHorizontally,
                flingBehavior = rememberSnapFlingBehavior(lazyListState = dayListState),
            ) {
                items(31) { index ->
                    val day = index + 1
                    val isCenter = dayCentered == index
                    Text(
                        // SPEC-070-A final audit pass D F1 — Locale.US so the
                        // wheel doesn't show mixed-script digits (Persian /
                        // Arabic-Indic) on fa-IR / ar locales.
                        text = String.format(java.util.Locale.US, "%02d", day),
                        fontSize = if (isCenter) 18.sp else 14.sp,
                        fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                        // SPEC-401-A R50 (Lens C #3, P1) — DateWheelPicker
                        // non-center text uses theme onSurface.alpha(0.6)
                        // matching iOS .secondary (was Color.Gray, illegible
                        // in dark mode).
                        color = if (isCenter) highlightColor else wheelTextColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable {
                                selectedDay = day
                                emit()
                                // SPEC-401-A R57 — SELECTION haptic mirrors iOS
                                // UIPickerView tick; gated by haptic_on_scroll.
                                fireHaptic()
                            },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // Year column
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = yearListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = colPad),
                horizontalAlignment = Alignment.CenterHorizontally,
                flingBehavior = rememberSnapFlingBehavior(lazyListState = yearListState),
            ) {
                items(years.size) { index ->
                    val year = years[index]
                    val isCenter = yearCentered == index
                    Text(
                        text = year.toString(),
                        fontSize = if (isCenter) 18.sp else 14.sp,
                        fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                        // SPEC-401-A R50 (Lens C #3, P1) — DateWheelPicker
                        // non-center text uses theme onSurface.alpha(0.6)
                        // matching iOS .secondary (was Color.Gray, illegible
                        // in dark mode).
                        color = if (isCenter) highlightColor else wheelTextColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable {
                                selectedYear = year
                                emit()
                                // SPEC-401-A R57 — SELECTION haptic mirrors iOS
                                // UIPickerView tick; gated by haptic_on_scroll.
                                fireHaptic()
                            },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        } // SPEC-419 — close if (showDate)

        // SPEC-419 — hour + minute columns for picker_mode datetime/time (was day/month/year only).
        if (showTime) {
            // Hour column (00–23)
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = hourListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = colPad),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    flingBehavior = rememberSnapFlingBehavior(lazyListState = hourListState),
                ) {
                    items(24) { index ->
                        val isCenter = hourCentered == index
                        Text(
                            text = String.format(java.util.Locale.US, "%02d", index),
                            fontSize = if (isCenter) 18.sp else 14.sp,
                            fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCenter) highlightColor else wheelTextColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .clickable {
                                    selectedHour = index
                                    emit()
                                    fireHaptic()  // gated by haptic_on_scroll
                                },
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            // Minute column (00–59)
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = minuteListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = colPad),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    flingBehavior = rememberSnapFlingBehavior(lazyListState = minuteListState),
                ) {
                    items(60) { index ->
                        val isCenter = minuteCentered == index
                        Text(
                            text = String.format(java.util.Locale.US, "%02d", index),
                            fontSize = if (isCenter) 18.sp else 14.sp,
                            fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCenter) highlightColor else wheelTextColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .clickable {
                                    selectedMinute = index
                                    emit()
                                    fireHaptic()  // gated by haptic_on_scroll
                                },
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
    } // SPEC-401-A R62 (Lens C P1) — close Box wrapper added for highlight strip
    // SPEC-419 — authored validation message below the wheel (mirrors iOS).
    if (!validationMsg.isNullOrBlank()) {
        Text(
            text = validationMsg,
            fontSize = 12.sp,
            color = block.field_style?.error_text_color?.let { StyleEngine.parseColor(it) } ?: MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    } // SPEC-419 — close outer Column
}

// MARK: - Stack Block (ZStack container — SPEC-089d AC-024)

@Composable
private fun StackBlock(
    block: ContentBlock,
    onAction: (String) -> Unit,
    toggleValues: MutableMap<String, Boolean>,
    inputValues: MutableMap<String, Any>,
    loc: ((String, String) -> String)?,
    // Mrozu QA (2026-08-04) — thread step blocks so a nested consent-CTA gates on the full step (iOS parity).
    stepBlocks: List<ContentBlock> = emptyList(),
) {
    // SPEC-401-A R61 (Lens A N1, P1) — accept iOS canonical `stack_children`
    // alongside `children`; console editor writes `stack_children` for
    // stack-block creation. iOS reads both at ContentBlockRendererView.swift
    // :1129. Without this fallback Android renders empty Stack on console
    // payloads.
    val childBlocks = (block.children ?: block.stack_children ?: emptyList()).sortedBy { it.z_index ?: 0.0 }
    // SPEC-401-A — iOS Stack alignment supports 9 named tokens
    // (top_left/topLeading/topLeft, top, top_right, leading, center,
    // trailing, bottom_left, bottom, bottom_right). Android previously
    // delegated to mapBlockAlignment which only knew left/right/center
    // + top/bottom split into separate fields, so any combined token
    // fell through to Center. Parse the combined name first, fall
    // back to the per-axis path for legacy authoring.
    val stackAlignment = parseStackAlignment(block.alignment)
        ?: mapBlockAlignment(block.horizontal_align, block.vertical_align)

    Box(
        // SPEC-419 pass-14 #4 — apply authored `height` to the stack container
        // (the editor default-inits 200; preview applies block.height at
        // OnboardingStepPreview.tsx:1735). Was fillMaxWidth only, so authored
        // heights were dropped on-device.
        modifier = Modifier.fillMaxWidth().let { m -> block.height?.let { m.height(it.dp) } ?: m },
        contentAlignment = stackAlignment,
    ) {
        childBlocks.forEach { child ->
            Box(modifier = Modifier.zIndex((child.z_index ?: 0.0).toFloat())) {
                RenderBlock(
                    block = child,
                    onAction = onAction,
                    toggleValues = toggleValues,
                    inputValues = inputValues,
                    loc = loc,
                    stepBlocks = stepBlocks,
                )
            }
        }
    }
}

// MARK: - Row Block (HStack container — SPEC-089d AC-025)

@Composable
private fun RowBlock(
    block: ContentBlock,
    onAction: (String) -> Unit,
    toggleValues: MutableMap<String, Boolean>,
    inputValues: MutableMap<String, Any>,
    loc: ((String, String) -> String)?,
    // Mrozu QA (2026-08-04) — thread step blocks so a nested consent-CTA gates on the full step (iOS parity).
    stepBlocks: List<ContentBlock> = emptyList(),
) {
    // SPEC-401-A R61 (Lens A N1, P1) — accept iOS canonical `stack_children`
    // alongside `children` for Row blocks; console editor writes
    // `stack_children` on row-block creation (StepContentEditor.tsx).
    // Mirrors iOS ContentBlockRendererView.swift:1129.
    val childBlocks = block.children ?: block.stack_children ?: emptyList()
    // SPEC-419 — editor writes `spacing` (preview reads `spacing`); `gap` is the legacy/imported
    // key. Read spacing first so authored row gap isn't lost on-device.
    val rowGap = (block.spacing ?: block.gap ?: 8.0).dp
    val direction = block.row_direction ?: "horizontal"
    val distribution = block.row_distribution ?: "start"
    val childFill = block.row_child_fill ?: true

    // SPEC-401-A — column ratios (e.g. "1:2") for proportional widths.
    // iOS prefers field_config["column_ratios"] then top-level
    // column_ratios; Android matches.
    val ratioStr = (block.field_config?.get("column_ratios") as? String) ?: block.column_ratios
    val ratios: List<Float> = ratioStr
        ?.split(":")
        ?.mapNotNull { it.trim().toFloatOrNull()?.takeIf { v -> v > 0f } }
        ?: emptyList()

    // SPEC-401-A — row container styling (info-card pattern). All
    // values flow through field_config because the row block is reused
    // for many layouts; only when authored do we wrap.
    val cfg = block.field_config
    val rowBgCol = (cfg?.get("bg_color") as? String)?.let { StyleEngine.parseColor(it) }
    val rowBorderW = ((cfg?.get("border_width") as? Number)?.toFloat() ?: 0f).dp
    val rowBorderCol = (cfg?.get("border_color") as? String)?.let { StyleEngine.parseColor(it) }
    val rowCornerR = ((cfg?.get("corner_radius") as? Number)?.toFloat() ?: 0f).dp
    val rowBgOpacity = ((cfg?.get("background_opacity") as? Number)?.toFloat() ?: 1f)
    val rowUseBlur = (cfg?.get("blur_background") as? Boolean) == true

    // SPEC-401-A — leading-icon slot. Mirrors iOS rowLeadingIconView
    // (info-card pattern: circle bg + icon).
    val leadingIcon = cfg?.get("leading_icon") as? String
    val leadingIconSize = ((cfg?.get("leading_icon_size") as? Number)?.toFloat() ?: 24f).dp
    val leadingIconColor = (cfg?.get("leading_icon_color") as? String)?.let { StyleEngine.parseColor(it) }
    val leadingIconBgColor = (cfg?.get("leading_icon_bg_color") as? String)?.let { StyleEngine.parseColor(it) }
    val leadingIconBgSize = ((cfg?.get("leading_icon_bg_size") as? Number)?.toFloat()
        ?: (leadingIconSize.value + 16f)).dp

    val hasOverflowChild = childBlocks.any { it.overflow == "visible" }
    val hasContainerStyling = rowBgCol != null || rowBorderW.value > 0f || rowUseBlur

    @Composable
    fun LeadingIconSlot() {
        if (leadingIcon == null) return
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(leadingIconBgSize)) {
            if (leadingIconBgColor != null) {
                Box(
                    modifier = Modifier
                        .size(leadingIconBgSize)
                        .clip(CircleShape)
                        .background(leadingIconBgColor.copy(alpha = 0.15f)),
                )
            }
            Text(
                text = leadingIcon,
                // SPEC-419 — render the glyph at the CONFIGURED leading_icon_size (was
                // * 0.6f, which shrank a 24 setting to a tiny 14sp glyph). leading_icon_size
                // now IS the glyph size so the console scales it directly. The enclosing Box
                // is sized to bgSize (= size + 16) so the larger glyph is never clipped.
                fontSize = leadingIconSize.value.sp,
                color = leadingIconColor ?: Color.Unspecified,
            )
        }
    }

    @Composable
    fun RowChildren(modifier: Modifier = Modifier) {
        if (direction == "vertical") {
            // Vertical layout
            val vArrangement = when (distribution) {
                "center" -> Arrangement.Center
                "end" -> Arrangement.Bottom
                "space-between", "space_between" -> Arrangement.SpaceBetween
                "space-around", "space_around" -> Arrangement.SpaceAround
                "space-evenly", "space_evenly" -> Arrangement.SpaceEvenly
                else -> Arrangement.Top
            }
            val baseMod = if (hasOverflowChild) {
                Modifier.fillMaxWidth().graphicsLayer { clip = false }
            } else {
                Modifier.fillMaxWidth()
            }
            // SPEC-419 pass-13 — cross-axis (horizontal) alignment from align_items for
            // the vertical row, mirroring iOS hAlign (VStack alignment): leading/start →
            // Start, trailing/end → End, else center (was always Column-default Start).
            val hAlignment = when (block.align_items) {
                "leading", "start", "left" -> Alignment.Start
                "trailing", "end", "right" -> Alignment.End
                else -> Alignment.CenterHorizontally
            }
            Column(
                modifier = modifier.then(baseMod),
                verticalArrangement = if (rowGap.value > 0 && distribution == "start") Arrangement.spacedBy(rowGap) else vArrangement,
                horizontalAlignment = hAlignment,
            ) {
                LeadingIconSlot()
                childBlocks.forEach { child ->
                    val childMod = if (childFill) Modifier.fillMaxWidth() else Modifier
                    val overflowMod = if (child.overflow == "visible") childMod.zIndex(1f) else childMod
                    // SPEC-419 pass-15 #33 — apply per-child element_width/element_height (mirrors iOS + preview applyRelativeSizing); skip height for input_* like the top-level renderer.
                    val childSizeMod = overflowMod.applyRelativeSizing(child.element_width, if (child.type.startsWith("input_")) null else child.element_height)
                    Box(modifier = childSizeMod) {
                        RenderBlock(
                            block = child,
                            onAction = onAction,
                            toggleValues = toggleValues,
                            inputValues = inputValues,
                            loc = loc,
                            stepBlocks = stepBlocks,
                        )
                    }
                }
            }
        } else {
            // Horizontal layout (default)
            val hArrangement = when (distribution) {
                "center" -> Arrangement.Center
                "end" -> Arrangement.End
                "space-between", "space_between" -> Arrangement.SpaceBetween
                "space-around", "space_around" -> Arrangement.SpaceAround
                "space-evenly", "space_evenly" -> Arrangement.SpaceEvenly
                else -> Arrangement.spacedBy(rowGap)
            }
            val vAlignment = when (block.align_items) {
                "top" -> Alignment.Top
                "bottom" -> Alignment.Bottom
                else -> Alignment.CenterVertically
            }
            val baseMod = if (hasOverflowChild) {
                Modifier.fillMaxWidth().graphicsLayer { clip = false }
            } else {
                Modifier.fillMaxWidth()
            }
            if (block.wrap == true && ratios.isEmpty()) {
                // Mrozu QA: row.wrap flows children onto multiple lines (chips/badges)
                // instead of a single clipped Row. Parity with iOS WrapLayout. FlowRow
                // takes no weight(), so wrapped children are wrap-content (fractional
                // widths still honored via applyRelativeSizing).
                androidx.compose.foundation.layout.FlowRow(
                    modifier = modifier.then(baseMod),
                    horizontalArrangement = Arrangement.spacedBy(rowGap),
                    verticalArrangement = Arrangement.spacedBy(rowGap),
                ) {
                    LeadingIconSlot()
                    childBlocks.forEach { child ->
                        val cw = child.element_width
                        val cwFractional = cw != null && cw.endsWith("%")
                        val childSizeMod = Modifier.applyRelativeSizing(if (cwFractional) cw else null, if (child.type.startsWith("input_")) null else child.element_height)
                        Box(modifier = childSizeMod) {
                            RenderBlock(child, onAction, toggleValues, inputValues, loc, stepBlocks = stepBlocks)
                        }
                    }
                }
                return
            }
            Row(
                modifier = modifier.then(baseMod),
                horizontalArrangement = hArrangement,
                verticalAlignment = vAlignment,
            ) {
                LeadingIconSlot()
                if (ratios.isNotEmpty()) {
                    // SPEC-401-A — proportional weights from column_ratios.
                    // Children map 1:1 to ratios; extra children get
                    // the average weight (mirrors iOS allocate()).
                    val avg = ratios.sum() / ratios.size
                    childBlocks.forEachIndexed { idx, child ->
                        val w = if (idx < ratios.size) ratios[idx] else avg
                        val childMod = Modifier.weight(w)
                        val overflowMod = if (child.overflow == "visible") childMod.zIndex(1f) else childMod
                        // SPEC-419 pass-15 #33 — apply per-child element_height (width owned by weight here); skip height for input_*.
                        val childSizeMod = overflowMod.applyRelativeSizing(null, if (child.type.startsWith("input_")) null else child.element_height)
                        Box(modifier = childSizeMod) {
                            RenderBlock(
                                block = child,
                                onAction = onAction,
                                toggleValues = toggleValues,
                                inputValues = inputValues,
                                loc = loc,
                                stepBlocks = stepBlocks,
                            )
                        }
                    }
                } else {
                    childBlocks.forEach { child ->
                        // SPEC-419 — a fixed-px-width child (e.g. a leading icon image) WRAPS its own
                        // content so its layout box matches what it actually renders. weight(1f) gave
                        // it ~half the row (ballooned + clipped); forcing the authored 15px made the
                        // box smaller than the legible icon ImageBlock now draws (32dp), shifting it
                        // off-position ("too far left"). Wrap-content lets ImageBlock own the size and
                        // the row position it correctly, vertically centred (align_items default).
                        val cw = child.element_width
                        // SPEC-419 pass-16 #16 — fractional widths (e.g. "50%") get fillMaxWidth(fraction)
                        // via applyRelativeSizing, mirroring iOS + preview; was swallowed by weight(1f).
                        val cwFractional = cw != null && cw.endsWith("%")
                        // SPEC-419 — a fixed-width child (leading icon) wraps content + gets a small
                        // start inset so it isn't flush against the card's left border (user: "inset
                        // from the left edge"); flexible children weight/fill.
                        val childMod = if (cw != null && cw.endsWith("px")) Modifier.padding(start = 12.dp)
                            else if (cwFractional) Modifier
                            else if (childFill) Modifier.weight(1f) else Modifier
                        val overflowMod = if (child.overflow == "visible") childMod.zIndex(1f) else childMod
                        // SPEC-419 pass-15 #33 — apply per-child element_height (px width handled above);
                        // pass-16 #16 — fractional width applied here. Skip height for input_*.
                        val childSizeMod = overflowMod.applyRelativeSizing(if (cwFractional) cw else null, if (child.type.startsWith("input_")) null else child.element_height)
                        Box(modifier = childSizeMod) {
                            RenderBlock(
                                block = child,
                                onAction = onAction,
                                toggleValues = toggleValues,
                                inputValues = inputValues,
                                loc = loc,
                                stepBlocks = stepBlocks,
                            )
                        }
                    }
                }
            }
        }
    }

    // SPEC-401-A — wrap with container styling when authored. Mirrors
    // iOS `.if(rowBgCol != nil || rowBorderW > 0 || rowUseBlur) { … }`.
    if (hasContainerStyling) {
        val shape = RoundedCornerShape(rowCornerR)
        var containerMod: Modifier = Modifier
        if (rowBgCol != null) {
            containerMod = containerMod.background(rowBgCol.copy(alpha = rowBgOpacity), shape)
        }
        if (rowBorderW.value > 0f && rowBorderCol != null) {
            containerMod = containerMod.border(rowBorderW, rowBorderCol, shape)
        }
        Box(modifier = containerMod.padding(if (rowBorderW.value > 0f) 12.dp else 0.dp)) {
            RowChildren()
        }
    } else {
        RowChildren()
    }
}

// MARK: - Custom View Block (SPEC-089d AC-026)

@Composable
private fun CustomViewBlock(block: ContentBlock) {
    val key = block.view_key ?: ""
    val factory = AppDNA.registeredCustomViews[key]

    if (factory != null) {
        val heightMod = if (block.height != null) Modifier.height(block.height.dp) else Modifier
        Box(modifier = Modifier.fillMaxWidth().then(heightMod)) {
            factory(block.custom_config ?: emptyMap())
        }
    } else if (block.placeholder_image_url != null) {
        ai.appdna.sdk.core.NetworkImage(
            url = block.placeholder_image_url,
            modifier = Modifier
                .fillMaxWidth()
                .height((block.height ?: 100.0).dp),
            contentScale = ContentScale.Fit,
        )
    } else {
        Text(
            text = block.placeholder_text ?: "[$key]",
            fontSize = 12.sp,
            // SPEC-401-A R45 — theme-adaptive secondary (was Color.Gray).
            // CustomViewBlock placeholder fallback. iOS .secondary
            // (ContentBlockRendererView.swift:1361).
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        )
    }
}

// MARK: - Star Background Block (SPEC-089d AC-027)

@Composable
private fun StarBackgroundBlock(block: ContentBlock) {
    // SPEC-419 pass-15 #8/#9/#25 — editor authors particle_color / particle_opacity /
    // particle_speed (folded into field_config by OnboardingConfig.fromMap). Read those
    // first, fall back to the legacy native keys for back-compat.
    val fcParticleColor = block.field_config?.get("particle_color") as? String
    val fcParticleOpacity = (block.field_config?.get("particle_opacity") as? Number)?.toFloat()
    val fcParticleSpeed = block.field_config?.get("particle_speed") as? String
    val particleColor = StyleEngine.parseColor(fcParticleColor ?: block.active_color ?: block.text_color ?: "#FFFFFF")
    // SPEC-419 pass-15 #27 — secondary_color tints 1/3 of particles (matches editor + preview)
    val secondaryColor = block.secondary_color?.let { StyleEngine.parseColor(it) } ?: particleColor
    // Mrozu QA (2026-08-03): particle_type was decoded but the Canvas always drew a
    // circle, so stars/sparkles/snow all looked identical. Render the actual shape (parity with iOS).
    val particleType = block.particle_type ?: "dots"
    // Mrozu QA (2026-08-04): confetti = falling multicolor rounded rects. `particle_multicolor` cycles
    // a fixed palette per-particle (defaults ON for confetti). Parity with iOS confettiPalette.
    val useMulticolor = block.particle_multicolor ?: (particleType == "confetti")
    val confettiPalette = remember {
        listOf("#EF4444", "#F59E0B", "#FCD34D", "#10B981", "#3B82F6", "#8B5CF6", "#EC4899")
            .map { StyleEngine.parseColor(it) }
    }
    val baseOpacity = (fcParticleOpacity ?: (block.block_style?.opacity ?: 0.8).toFloat())
    val particleCount = when (block.density) {
        "sparse" -> 20; "dense" -> 100; else -> 50
    }
    val speedFactor = when (fcParticleSpeed ?: block.speed) {
        "slow" -> 0.3f; "fast" -> 1.5f; else -> 0.8f
    }
    val minSize = (block.size_range?.firstOrNull() ?: 1.0).toFloat()
    val maxSize = (block.size_range?.lastOrNull() ?: 3.0).toFloat()
    val isFullscreen = block.fullscreen ?: false
    val blockHeight = (block.height ?: 200.0).dp

    val particles = remember {
        mutableStateOf(
            (0 until particleCount).map {
                StarParticleState(
                    x = (Math.random() * 1000).toFloat(),
                    y = (Math.random() * 1000).toFloat(),
                    size = (minSize + Math.random().toFloat() * (maxSize - minSize)),
                    opacity = (0.2f + Math.random().toFloat() * 0.8f),
                    speed = (0.2f + Math.random().toFloat() * 0.8f),
                )
            }
        )
    }

    // Battery safety — stop animation when composable leaves composition
    var isActive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        isActive = true
        onDispose { isActive = false }
    }

    // Animation loop
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(33L) // ~30fps
            if (!isActive) continue
            particles.value = particles.value.map { p ->
                var newY = p.y + p.speed * speedFactor
                var newOpacity = p.opacity + (-0.02f + Math.random().toFloat() * 0.04f)
                newOpacity = newOpacity.coerceIn(0.1f, 1f)
                if (newY > 1000f) {
                    p.copy(y = -p.size, x = (Math.random() * 1000).toFloat(), opacity = newOpacity)
                } else {
                    p.copy(y = newY, opacity = newOpacity)
                }
            }
        }
    }

    Canvas(
        modifier = if (isFullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(blockHeight),
    ) {
        val scaleX = size.width / 1000f
        val scaleY = size.height / 1000f
        // SPEC-401-A R48 (Lens A #5) — particle radius is RAW pixels; do NOT
        // multiply by scaleX. iOS uses size_range as raw point values
        // (StarBackgroundView.swift:88 `circle.size = p.size`). Multiplying
        // by scaleX shrank particles to invisibility on narrow widths and
        // ballooned them into giant blobs in fullscreen mode.
        particles.value.forEachIndexed { i, p ->
            // Mrozu QA (2026-08-04): multicolor confetti cycles the palette; otherwise every 3rd
            // particle uses secondary_color (SPEC-419 pass-15 #27).
            val baseColor = if (useMulticolor) confettiPalette[i % confettiPalette.size]
                else if (i % 3 == 0) secondaryColor else particleColor
            val pColor = baseColor.copy(alpha = p.opacity * baseOpacity)
            val center = Offset(p.x * scaleX, p.y * scaleY)
            // Mrozu QA (2026-08-04): iOS treats p.size as a BOX/diameter (particle
            // drawn inside a size×size CGRect — StarBackgroundBlockView), so the
            // effective radius is p.size/2. Android previously used p.size as the
            // RADIUS, rendering every particle ~2× larger than iOS. Halve the geometry
            // so all particle_type shapes match iOS box semantics.
            when (particleType) {
                "stars" -> drawPath(starParticlePath(5, 0.42f, center, p.size / 2f), pColor)
                "sparkles" -> drawPath(starParticlePath(4, 0.30f, center, p.size / 2f), pColor)
                "snow" -> drawPath(starParticlePath(6, 0.50f, center, p.size / 2f), pColor)
                "confetti" -> drawRoundRect(
                    color = pColor,
                    topLeft = Offset(center.x - p.size / 2f, center.y - p.size / 2f),
                    size = androidx.compose.ui.geometry.Size(p.size, p.size),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(p.size * 0.3f),
                )
                else -> drawCircle(color = pColor, radius = p.size / 2f, center = center) // dots, bokeh
            }
        }
    }
}

// Mrozu QA (2026-08-03): build an N-point star polygon for star_background particle_type
// (parity with iOS ContentBlockStandaloneViews.starPath). dots/bokeh keep drawCircle.
private fun starParticlePath(points: Int, innerRatio: Float, center: Offset, radius: Float): androidx.compose.ui.graphics.Path {
    val path = androidx.compose.ui.graphics.Path()
    val inner = radius * innerRatio
    val step = (Math.PI / points).toFloat()
    var angle = (-Math.PI / 2).toFloat() // start pointing up
    for (i in 0 until points * 2) {
        val r = if (i % 2 == 0) radius else inner
        val x = center.x + r * kotlin.math.cos(angle)
        val y = center.y + r * kotlin.math.sin(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        angle += step
    }
    path.close()
    return path
}

// MARK: - Wheel Picker Block (SPEC-089d AC-013)

@Composable
private fun WheelPickerBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
    onInteract: (String, String, String?) -> Unit = { _, _, _ -> },
) {
    // SPEC-420 — opt-in measurement mode. When `field_config["measurement_type"]` is
    // present AND the units[] resolve to a usable set, render the measurement variant
    // (unit toggle + ruler/gauge/dial/wheel). Otherwise the legacy drum below is
    // UNCHANGED. The measurement wrapper OWNS persistence (base + sibling keys).
    val measurementConfig = parseMeasurementConfig(block)
    if (measurementConfig != null) {
        // SPEC-419 STEP-2 — the measurement wrapper fires ("value_changed", base) on commit.
        MeasurementWheelBlock(block, measurementConfig, inputValues, onInteract)
        return
    }

    val minVal = block.min_value ?: 0.0
    // SPEC-419 — editor writes `max_value`/`default_value`; native canonical keys are
    // `max_value_picker`/`default_picker_value`. Read the picker key first, fall back to the editor
    // key so authored ranges/defaults aren't lost on-device.
    val maxVal = block.max_value_picker ?: block.max_value ?: 100.0
    val step = (block.step_value ?: 1.0).let { if (it > 0.0) it else 1.0 }  // SPEC-419 pass-35 — clamp >0; the value-gen `while (current <= maxVal)` hangs forever on step<=0 (mirror iOS number-wheel)
    val defaultVal = block.default_picker_value ?: block.default_value ?: minVal
    val unitStr = block.unit ?: ""
    val unitPos = block.unit_position ?: "after"
    val highlightColor = StyleEngine.parseColor(block.highlight_color ?: block.active_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    val fieldId = block.field_id ?: block.id
    // SPEC-401-A R35 — horizontal mode per iOS modernHorizontalWheel
    // (ContentBlockStandaloneViews.swift:1445,1454-1473,1521-1612).
    val isHorizontal = (block.wheel_orientation ?: block.orientation)?.lowercase() == "horizontal"

    val values = remember {
        val vals = mutableListOf<Double>()
        var current = minVal
        while (current <= maxVal) {
            vals.add(current)
            current += step
        }
        if (vals.isEmpty()) vals.add(0.0)
        vals
    }

    val initialIndex = values.indexOfFirst { it >= defaultVal }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)

    // SPEC-401-A R50 (Lens C #1, P0) — derive the truly-centered item from
    // viewport offset rather than `firstVisibleItemIndex`. iOS uses
    // `.scrollPosition(anchor: .center)` (ContentBlockStandaloneViews.swift
    // :1549) which always reports the index nearest the viewport midpoint.
    // Without this fix, the highlight chip + bold/colored text rendered
    // over a half-empty area: chip pinned to viewport center while
    // `firstVisibleItemIndex` still pointed to the leftmost item.
    val centeredIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewportCenter = info.viewportStartOffset +
                (info.viewportEndOffset - info.viewportStartOffset) / 2
            info.visibleItemsInfo.minByOrNull {
                kotlin.math.abs((it.offset + it.size / 2) - viewportCenter)
            }?.index ?: listState.firstVisibleItemIndex
        }
    }
    // SPEC-401-A R35 — haptic selection feedback on wheel snap (Lens C #1).
    // iOS pre-warms UISelectionFeedbackGenerator and fires .selectionChanged()
    // after the user starts interacting (ContentBlockStandaloneViews.swift:1500-1510).
    val view = androidx.compose.ui.platform.LocalView.current
    var hasUserInteracted by remember { mutableStateOf(false) }
    var lastHapticIndex by remember { mutableStateOf(initialIndex) }
    LaunchedEffect(centeredIndex) {
        // SPEC-401-A R60 (Lens A P1) — gate persistence behind interaction
        // when the field is required, matching iOS
        // ContentBlockStandaloneViews.swift:1493-1498:
        //   if block.field_required != true { persistValue(...) }
        // Without this, the default wheel value is written to inputValues
        // on first composition; the required-field validator at
        // OnboardingActivity.kt:2503-2508 sees a non-null Double and lets
        // the user advance without ever interacting with the wheel. Also
        // mirror iOS Int/Double type discrimination so server-side
        // analytics joins don't drift.
        val isRequired = block.field_required == true
        if (centeredIndex in values.indices && (!isRequired || hasUserInteracted)) {
            val v = values[centeredIndex]
            inputValues[fieldId] = if (v == v.toLong().toDouble()) v.toLong() else v
        }
        if (!hasUserInteracted && listState.isScrollInProgress) {
            hasUserInteracted = true
        }
        if (hasUserInteracted && centeredIndex != lastHapticIndex && centeredIndex in values.indices) {
            // SPEC — respect haptic_on_scroll == false (author opt-out); nil/true keep the tick.
            if (block.haptic_on_scroll != false) {
                ai.appdna.sdk.core.HapticEngine.trigger(view, ai.appdna.sdk.core.HapticType.SELECTION)
            }
            lastHapticIndex = centeredIndex
        }
    }

    // SPEC — honor visible_items / wheel_height on the vertical drum (was
    // hardcoded 150dp height / 55dp contentPadding). ~44dp per row; the
    // contentPadding keeps boundary values reachable at viewport center.
    // When neither is authored the exact legacy 150dp / 55dp is preserved.
    val drumH: Float? = block.wheel_height?.toFloat()
        ?: block.visible_items?.let { (it.coerceIn(1, 9) * 44).toFloat() }
    val drumHeightDp = (drumH ?: 150f).dp
    val drumPadDp = (drumH?.let { ((it - 44f) / 2f).coerceAtLeast(0f) } ?: 55f).dp

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // SPEC-401-A R3 — wheel_picker label: iOS reads
        // `rating_label ?? text ?? label`; Android only knew `label`
        // so console-authored labels via either field were lost.
        // SPEC-401-A R61 (Lens A N7, P3) — drop `?: block.label` to match
        // iOS canonical ContentBlockStandaloneViews.swift:1448
        // `block.rating_label ?? block.text`. Same family as backlog #3.
        (block.rating_label ?: block.text)?.let { label ->
            // SPEC-401-A R45 — theme-adaptive wheel_picker label (was Color.Gray).
            // iOS .secondary (ContentBlockStandaloneViews.swift:1451).
            // SPEC-401-A R57 (Lens A R57 #6, P2) — 15sp matches iOS .subheadline.
            Text(text = label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 8.dp))
        }

        if (isHorizontal) {
            Box(
                modifier = Modifier.fillMaxWidth().height(80.dp),
                contentAlignment = Alignment.Center,
            ) {
                // SPEC-419 — iOS modernHorizontalWheel has NO highlight chip/box; the
                // selection is conveyed purely by the centred number being bold + the
                // highlight colour, neighbours faded (s04 iOS shows "6" bold-white, "7 8"
                // faded, no box). The old faint-white chip was an Android-only element that
                // read as a "stepper box". Removed for parity.
                //
                // SPEC-419 — itemWidth is the SINGLE source of truth for both the item slot
                // AND the side contentPadding, so the centred item lands exactly at the
                // viewport midpoint. The old code used width(80)+padding(8) = 96dp items but
                // sized sidePad off 80dp, so the centred value (esp. the min, which has no
                // left neighbour) sat right-of-centre.
                // SPEC-419 — ~56dp spacing matches iOS modernHorizontalWheel (iOS shows the
                // selection + ~2-3 faded neighbours each side; at 72dp only one neighbour fit
                // before the next hit the screen edge). Same value drives item slot + sidePad so
                // the centred value stays exactly at the viewport midpoint.
                val itemWidth = 56.dp
                val sidePad = (LocalConfiguration.current.screenWidthDp.dp - itemWidth) / 2
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = sidePad),
                    verticalAlignment = Alignment.CenterVertically,
                    flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
                ) {
                    items(values.size) { index ->
                        val v = values[index]
                        // SPEC-401-A R59 (Lens A P3 #5) — Locale.US matches
                        // iOS ContentBlockStandaloneViews.swift:1465
                        // `String(format: "%.1f", val)` (POSIX locale).
                        // Default JVM locale would render Arabic-Indic digits
                        // ("٢٫٥") on ar/fa/bn/my locales — sister of R27
                        // which fixed countdown + form date/time pickers.
                        val formatted = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(java.util.Locale.US, v)
                        val display = if (unitPos == "before" || unitPos == "prefix") "$unitStr$formatted" else "$formatted$unitStr"
                        val isCenter = index == centeredIndex

                        Text(
                            text = display,
                            fontSize = if (isCenter) 28.sp else 18.sp,
                            fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                            // SPEC-401-A R48 (Lens A #4) — non-center text uses
                            // theme-adaptive onSurface.alpha(0.7) instead of
                            // hardcoded Color.Gray. Matches iOS .secondary
                            // and renders correctly in dark mode.
                            // SPEC-419 — faded neighbours based on the (author-chosen, contrasting)
                            // highlight colour, not MaterialTheme.onSurface which wasn't resolving
                            // bright in this context → neighbours washed out so only the centre value
                            // showed (iOS shows several faded numbers around the selection). 0.45α
                            // keeps them clearly visible-but-secondary like iOS .secondary.
                            color = if (isCenter) highlightColor else highlightColor.copy(alpha = 0.45f),
                            // SPEC-401-A R59 (Lens C P2 #2) — TalkBack-announce
                            // every selection change on the centered value. iOS
                            // ContentBlockStandaloneViews.swift:1462-1473 uses
                            // native `Picker(.wheel)` which auto-announces wheel
                            // changes to VoiceOver; Compose has no equivalent so
                            // explicit `liveRegion = Polite` is required.
                            modifier = Modifier
                                .width(itemWidth) // SPEC-419 — same width as sidePad uses → centred item hits viewport midpoint
                                .then(
                                    if (isCenter) Modifier.semantics {
                                        liveRegion = LiveRegionMode.Polite
                                        contentDescription = display
                                    } else Modifier
                                ),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().height(drumHeightDp),
                contentAlignment = Alignment.Center,
            ) {
                // Highlight strip at center
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(highlightColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                )

                // SPEC-401-A R62 (Lens C P1) — vertical wheel needs
                // top+bottom contentPadding so first/last items can scroll
                // under the center highlight strip. Without this,
                // boundary values (e.g. age 18 / 99 in 18..99 range) are
                // physically unreachable — viewport center math
                // (centeredIndex line ~4031) requires items to be
                // positioned at parent center, which is impossible at
                // index 0 / N-1 when there's no leading/trailing padding.
                // iOS native Picker(.wheel) auto-positions any index.
                // Mirrors the horizontal branch fix at line 4106.
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = drumPadDp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
                ) {
                    items(values.size) { index ->
                        val v = values[index]
                        // SPEC-401-A R59 (Lens A P3 #5) — Locale.US matches
                        // iOS ContentBlockStandaloneViews.swift:1465
                        // `String(format: "%.1f", val)` (POSIX locale).
                        // Default JVM locale would render Arabic-Indic digits
                        // ("٢٫٥") on ar/fa/bn/my locales — sister of R27
                        // which fixed countdown + form date/time pickers.
                        val formatted = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(java.util.Locale.US, v)
                        val display = if (unitPos == "before" || unitPos == "prefix") "$unitStr$formatted" else "$formatted$unitStr"
                        val isCenter = index == centeredIndex

                        Text(
                            text = display,
                            fontSize = if (isCenter) 22.sp else 16.sp,
                            fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                            // SPEC-401-A R48 (Lens A #4) — non-center text uses
                            // theme-adaptive onSurface.alpha(0.7) instead of
                            // hardcoded Color.Gray (vertical wheel mode).
                            // SPEC-419 — faded neighbours based on the (author-chosen, contrasting)
                            // highlight colour, not MaterialTheme.onSurface which wasn't resolving
                            // bright in this context → neighbours washed out so only the centre value
                            // showed (iOS shows several faded numbers around the selection). 0.45α
                            // keeps them clearly visible-but-secondary like iOS .secondary.
                            color = if (isCenter) highlightColor else highlightColor.copy(alpha = 0.45f),
                            // SPEC-401-A R59 (Lens C P2 #2) — vertical-wheel
                            // TalkBack live announce; mirrors horizontal branch.
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .then(
                                    if (isCenter) Modifier.semantics {
                                        liveRegion = LiveRegionMode.Polite
                                        contentDescription = display
                                    } else Modifier
                                ),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Pulsing Avatar Block (SPEC-089d AC-014)

@Composable
private fun PulsingAvatarBlock(block: ContentBlock) {
    val avatarSize = (block.icon_size ?: block.height ?: 80.0).dp
    val pulseColor = StyleEngine.parseColor(block.pulse_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    // iOS forces `max(1, pulse_ring_count ?? 3)` (ContentBlockStandaloneViews.swift:1942) so a
    // `pulse_ring_count: 0` still shows one ring and the per-ring stagger never divides by zero.
    // Mirror it — without the floor Android drew ZERO rings where iOS drew one.
    val ringCount = (block.pulse_ring_count ?: 3).coerceAtLeast(1)
    val pulseDurationMs = ((block.pulse_speed ?: 1.5) * 1000).toInt()
    val borderW = (block.border_width ?: 0.0).dp
    val borderCol = StyleEngine.parseColor(block.border_color ?: "#FFFFFF")
    val hAlign = when (block.alignment) {
        "left" -> Alignment.CenterStart; "right" -> Alignment.CenterEnd; else -> Alignment.Center
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    // SPEC-401-A R48 (Lens A #3) — reserve a fixed-size frame so the largest
    // pulse ring at peak scale (×1.2) doesn't overlap adjacent content.
    // iOS uses .frame(width: avatarSize + CGFloat(ringCount + 1) * 20 * 1.3,
    // height: same). We mirror the same reservation on Android so layout
    // doesn't collapse adjacent rows on top of the rings during animation.
    val frameSize = (avatarSize.value + (ringCount + 1) * 20f * 1.3f).dp
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = hAlign,
    ) {
        Box(
            modifier = Modifier.size(frameSize),
            contentAlignment = Alignment.Center,
        ) {
            // Pulse rings — drawn on a Canvas that fills the reserved frame so each
            // stroked circle grows by RADIUS (like iOS Circle().stroke().scaleEffect,
            // ContentBlockStandaloneViews.swift:1664) and can never be clipped to a
            // per-ring box. The prior Box(.size(ringSize).scale().border(CircleShape))
            // clipped the ring to its own square graphicsLayer bounds at peak scale —
            // a circle clipped by a square smaller than its radius reads as a
            // rounded-square "cut" (flat axis edges + arc corners). That was the
            // Mrozu-reported pulse clip; it is a layer-bounds clip, NOT a frame-size
            // issue (frame stays at the iOS 1.3× reservation).
            for (i in 0 until ringCount) {
                val baseRadiusDp = (avatarSize.value + (i + 1) * 20f) / 2f
                val scale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = pulseDurationMs),
                        repeatMode = RepeatMode.Restart,
                        initialStartOffset = androidx.compose.animation.core.StartOffset(
                            offsetMillis = (pulseDurationMs / ringCount) * i,
                        ),
                    ),
                    label = "ring_scale_$i",
                )
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = pulseDurationMs),
                        repeatMode = RepeatMode.Restart,
                        initialStartOffset = androidx.compose.animation.core.StartOffset(
                            offsetMillis = (pulseDurationMs / ringCount) * i,
                        ),
                    ),
                    label = "ring_alpha_$i",
                )
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    // iOS composes TWO opacity factors: a fixed stroke opacity 0.3
                    // (ContentBlockStandaloneViews.swift:1959 `.stroke(pulseCol.opacity(0.3))`) times the
                    // animated view opacity 0.6→0 (:1963) = 0.18 peak. Fold BOTH in — using `alpha`
                    // alone (0.6 peak) rendered the rings 3.3× too opaque vs iOS and the prior Android.
                    drawCircle(
                        color = pulseColor.copy(alpha = alpha * 0.3f),
                        radius = baseRadiusDp.dp.toPx() * scale,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                    )
                }
            }

            // Avatar image — shape honors block.image_shape ("circle" default |
            // "square" | "rounded"). block.image_corner_radius controls the
            // rounded corner radius (default 16). Missing field preserves
            // previous circle-crop behavior so existing flows look identical.
            val avatarShape = when (block.image_shape) {
                "square" -> RectangleShape
                "rounded" -> RoundedCornerShape((block.image_corner_radius ?: 16.0).dp)
                else -> CircleShape
            }
            Box(
                modifier = Modifier
                    .size(avatarSize)
                    .clip(avatarShape)
                    .then(
                        if (borderW.value > 0) Modifier.border(borderW, borderCol, avatarShape) else Modifier
                    ),
            ) {
                if (block.image_url != null) {
                    ai.appdna.sdk.core.NetworkImage(
                        url = block.image_url,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Gray.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("\uD83D\uDC64", fontSize = (avatarSize.value * 0.4).sp)
                    }
                }
            }

            // Badge — honors badge_position (4-corner alignment), badge_size
            // (font + padding scale factor), badge_corner_radius (999 = capsule).
            // Missing fields preserve the previous hardcoded behavior.
            if (!block.badge_text.isNullOrEmpty()) {
                val badgeScale = (block.badge_size ?: 1.0).toFloat()
                // SPEC-401-A R19 — match iOS ContentBlockStandaloneViews.swift
                // :1709 `badgeFontSize: CGFloat = 11 * badgeScale` (caption2
                // ≈ 11pt). Was 10.sp on Android.
                val badgeFontSize = 11.sp * badgeScale
                val badgeHPadding = (6f * badgeScale).dp
                val badgeVPadding = (2f * badgeScale).dp
                val badgeRadius = (block.badge_corner_radius ?: 999.0).dp
                // QA-R15 — badge offset MUST be relative to the
                // ZStack centre, not to a `.align(corner)` anchor. iOS
                // `ContentBlockStandaloneViews.swift:1707-1733` builds the
                // ZStack with no explicit alignment on the badge — it
                // inherits the default centre — then `.offset(x, y)` shifts
                // it from the centre INTO the avatar's corner.
                //
                // The old Android code combined `.align(Alignment.TopEnd)`
                // (anchor at the FRAME's top-right corner) with the same
                // outward offset signs iOS uses. Net effect: badge anchored
                // to frame corner THEN pushed FURTHER outward, ending up
                // far above-right of the avatar instead of overlapping into
                // its NE corner. Mrozu's "LIVE badge clipped in half"
                // screenshot is exactly this: the badge drew above the
                // frame top edge and got cropped by the parent column.
                //
                // Fix: omit `.align(...)` (inheriting parent Box
                // `contentAlignment = Center`), and use the same offset
                // signs iOS uses (which are now centre-relative).
                val overlapPx = (avatarSize.value * 0.35f).dp
                val (offsetX, offsetY) = when (block.badge_position) {
                    "top_leading" -> -overlapPx to -overlapPx
                    "bottom_trailing" -> overlapPx to overlapPx
                    "bottom_leading" -> -overlapPx to overlapPx
                    else -> overlapPx to -overlapPx  // top_trailing default
                }
                Text(
                    text = block.badge_text,
                    fontSize = badgeFontSize,
                    fontWeight = FontWeight.SemiBold,
                    color = StyleEngine.parseColor(block.badge_text_color ?: "#FFFFFF"),
                    modifier = Modifier
                        .offset(x = offsetX, y = offsetY)
                        .background(
                            StyleEngine.parseColor(block.badge_bg_color ?: "#EF4444"),
                            RoundedCornerShape(badgeRadius),
                        )
                        .padding(horizontal = badgeHPadding, vertical = badgeVPadding),
                )
            }
        }
    }
}

// MARK: - Pricing Card Block (SPEC-089d)

@Composable
private fun PricingCardBlock(
    block: ContentBlock,
    onAction: (String) -> Unit,
    inputValues: MutableMap<String, Any>,
) {
    val plans = block.pricing_plans ?: emptyList()
    val isSideBySide = block.pricing_layout == "side_by_side"
    val accentColor = StyleEngine.parseColor(block.active_color ?: block.bg_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))

    var selectedPlanId by remember { mutableStateOf<String?>(null) }

    @Composable
    fun PlanCardContent(plan: PricingPlan, modifier: Modifier = Modifier) {
        val isHighlighted = plan.is_highlighted
        val isSelected = selectedPlanId == plan.id

        Card(
            onClick = {
                selectedPlanId = plan.id
                inputValues["selected_plan_id"] = plan.id
                inputValues["selected_plan_label"] = plan.label
                // SPEC-401-A R3 — match iOS contract:
                // onAction("select_plan", planId) — host parses an
                // ACTION + VALUE, NOT a colon-embedded string. The
                // generic onAction(String) only carries action; we
                // emit a separate "select_plan_id:<planId>" sentinel
                // so existing hosts that don't grok the dual-arg form
                // can still recover the planId. Hosts implementing the
                // canonical contract should listen for "select_plan"
                // first and inspect the planId from the most-recent
                // event sequence (mirrors iOS pattern).
                onAction("select_plan")
                onAction("select_plan_id:${plan.id}")
            },
            modifier = modifier
                // SPEC-401-A R16 — match iOS ContentBlockStandaloneViews.swift
                // :1911 highlighted shadow (`.shadow(color: isHighlighted ?
                // accent.opacity(0.15) : .clear, radius: 4, y: 2)`). Without
                // this the "best value" plan loses its accent-tinted lift on
                // Android; Card's default elevation is 0 because we override
                // containerColor.
                .shadow(
                    elevation = if (isHighlighted) 4.dp else 0.dp,
                    shape = RoundedCornerShape(12.dp),
                    spotColor = accentColor.copy(alpha = 0.15f),
                )
                .border(
                    width = if (isSelected || isHighlighted) 2.dp else 1.dp,
                    color = if (isSelected) accentColor else if (isHighlighted) accentColor else Color.Gray.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(12.dp),
                ),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                // SPEC-401-A R16 — match iOS ContentBlockStandaloneViews.swift
                // :1902 unselected uses `Color(.systemBackground)` (opaque
                // surface). Android's `Color.Transparent` left plan cards
                // visibly without a backplate when the page background was
                // tinted. Use MaterialTheme.colorScheme.surface so the card
                // adopts the right opaque color in light/dark themes.
                containerColor = if (isSelected) accentColor.copy(alpha = 0.05f) else MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                // SPEC-401-A R48 (Lens C #8) — plan card row spacing 4→6dp
                // matches iOS PricingTableBlockView VStack(spacing: 6).
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!plan.badge.isNullOrEmpty()) {
                    Text(
                        text = plan.badge,
                        // SPEC-401-A R48 (Lens C #7) — badge font 10→11sp
                        // mirrors iOS .footnote.
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .background(accentColor, RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                // SPEC-401-A R48 (Lens C #9) — plan label/price use theme
                // onSurface so dark mode reads as a true on-surface tint, not
                // the default Color.Black inherited from CardDefaults.
                Text(
                    text = plan.label,
                    // SPEC-401-A R57 (Lens A R57 #9, P2) — 15sp matches iOS
                    // .subheadline.weight(.medium) (ContentBlockStandalone
                    // Views.swift:1889).
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = plan.price,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // SPEC-401-A R44 — theme-adaptive period (was Color.Gray).
                Text(text = plan.period, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }

    if (isSideBySide) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            plans.forEach { plan ->
                PlanCardContent(plan, modifier = Modifier.weight(1f))
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            plans.forEach { plan ->
                PlanCardContent(plan, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// MARK: - Entrance Animation Wrapper (SPEC-089d §6.4)

/**
 * Wraps content with entrance animation.
 * Uses AnimatedVisibility with appropriate EnterTransition.
 */
@Composable
fun EntranceAnimationWrapper(
    animation: EntranceAnimationConfig,
    content: @Composable () -> Unit,
) {
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay((animation.delay_ms + animation.animation_delay_ms).toLong())
        isVisible = true
    }

    val durationMs = animation.duration_ms
    // SPEC-401-A R13 — match iOS swiftUIAnimation easing map
    // (ContentBlockTypes.swift:618-633). Compose's `tween(d)` defaults
    // to `FastOutSlowInEasing` (≈ ease_in_out), but iOS default is
    // `.linear`. Without explicit mapping, `linear`/`ease_in`/`ease_out`
    // collapse to the same Material curve and look visibly different
    // from iOS in side-by-side comparison.
    val resolvedEasing: androidx.compose.animation.core.Easing = when (animation.easing) {
        "ease_in" -> androidx.compose.animation.core.FastOutLinearInEasing
        "ease_out" -> androidx.compose.animation.core.LinearOutSlowInEasing
        "ease_in_out", "ease" -> androidx.compose.animation.core.FastOutSlowInEasing
        else -> androidx.compose.animation.core.LinearEasing // iOS default
    }
    val tweenSpec = tween<Float>(durationMillis = durationMs, easing = resolvedEasing)
    val tweenIntOffset = tween<androidx.compose.ui.unit.IntOffset>(durationMillis = durationMs, easing = resolvedEasing)
    val springFloatSpec = androidx.compose.animation.core.spring<Float>(
        dampingRatio = (animation.spring_damping ?: 0.7).toFloat()
    )
    val springIntOffsetSpec = androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntOffset>(
        dampingRatio = (animation.spring_damping ?: 0.7).toFloat()
    )
    val isSpring = animation.easing == "spring"

    // Audit pass-8 — a real "flip" is a 3D X-axis rotation to match iOS
    // (ContentBlockTypes.swift:613-616 rotates 90°→0 around x). AnimatedVisibility's
    // EnterTransition cannot express rotationX, and the previous impl used
    // fadeIn + scaleIn(0.0) (scale-from-zero, NO rotation) so a "flip" block
    // visibly did not flip. Drive rotationX via graphicsLayer instead. (Preview
    // rotateY→rotateX axis alignment is a sibling change.)
    if (animation.type == "flip") {
        // Named `flipRotation` (not `rotationX`) so it doesn't shadow the
        // GraphicsLayerScope.rotationX property inside the graphicsLayer lambda.
        val flipRotation by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (isVisible) 0f else 90f,
            animationSpec = if (isSpring) springFloatSpec else tweenSpec,
            label = "flipRotationX",
        )
        val flipAlpha by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (isVisible) 1f else 0f,
            animationSpec = if (isSpring) springFloatSpec else tweenSpec,
            label = "flipAlpha",
        )
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.graphicsLayer {
                rotationX = flipRotation
                alpha = flipAlpha
                // Perspective so the X-axis rotation reads as depth, not a flat squash.
                cameraDistance = 12f * density
            }
        ) {
            content()
        }
        return
    }

    val enterTransition: androidx.compose.animation.EnterTransition = when (animation.type) {
        "fade_in" -> androidx.compose.animation.fadeIn(if (isSpring) springFloatSpec else tweenSpec)
        "slide_up" -> androidx.compose.animation.slideInVertically(if (isSpring) springIntOffsetSpec else tweenIntOffset) { it }
        "slide_down" -> androidx.compose.animation.slideInVertically(if (isSpring) springIntOffsetSpec else tweenIntOffset) { -it }
        "slide_left" -> androidx.compose.animation.slideInHorizontally(if (isSpring) springIntOffsetSpec else tweenIntOffset) { -it }
        "slide_right" -> androidx.compose.animation.slideInHorizontally(if (isSpring) springIntOffsetSpec else tweenIntOffset) { it }
        "scale_up" -> androidx.compose.animation.scaleIn(if (isSpring) springFloatSpec else tweenSpec, initialScale = 0.5f)
        "scale_down" -> androidx.compose.animation.scaleIn(if (isSpring) springFloatSpec else tweenSpec, initialScale = 1.5f)
        // SPEC-401-A R13 — bounce previously always used spring,
        // ignoring `duration_ms`. iOS resolves bounce via swiftUIAnimation
        // which honors `duration_ms` for any non-spring easing — so when
        // `easing != "spring"` use the duration tween instead.
        "bounce" -> androidx.compose.animation.scaleIn(
            if (isSpring) springFloatSpec else tweenSpec,
            initialScale = 0.3f,
        )
        // "flip" is handled above via graphicsLayer rotationX (3D X-axis flip).
        else -> androidx.compose.animation.EnterTransition.None
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = isVisible,
        enter = enterTransition,
    ) {
        content()
    }
}

// MARK: - Form Input Block Composables (SPEC-089d Phase 3: AC-040 through AC-053)

/** Label composable for form field blocks. */
@Composable
private fun FormFieldLabel(block: ContentBlock) {
    // SPEC-401-A R55 (Lens A R55 #11, P2) — match iOS canonical fallback chain
    // at FormInputBlockViews.swift:11 `block.field_label ?? block.rating_label
    // ?? block.text`. Was missing rating_label so payloads using only that
    // field on a `rating` block rendered no label on Android.
    // SPEC-401-A R61 (Lens A backlog #3, P2) — drop `?: block.label` from
    // fallback chain to match iOS canonical FormInputBlockViews.swift:11
    // `block.field_label ?? block.rating_label ?? block.text`. Authored
    // payload using only `block.label` rendered the label on Android but
    // not on iOS — Android was masking iOS bug; align to iOS.
    val label = block.field_label ?: block.rating_label ?: block.text
    if (!label.isNullOrEmpty()) {
        val required = block.field_required ?: false
        Row {
            Text(
                text = label,
                // SPEC-401-A R56 (Lens A R56 #3, P2) — default 15sp matches iOS
                // .subheadline (FormInputBlockViews.swift:15). Was 14sp on every
                // FormFieldLabel without explicit label_font_size.
                // Parity with iOS FormFieldLabelView: field_style first, then the
                // top-level label_font_size, then 15sp (was field_style-only, so a
                // top-level label_font_size was honored on iOS but dropped here).
                fontSize = (block.field_style?.label_font_size ?: block.label_font_size ?: 15.0).sp,
                fontWeight = FontWeight.Medium,
                color = StyleEngine.parseColor(block.field_style?.label_color ?: "#374151"),
            )
            if (required) {
                // SPEC-401-A R60 (Lens C P3 #3) — match iOS systemRed
                // (#FF3B30 light) which is what SwiftUI `Color.red`
                // resolves to at FormInputBlockViews.swift:19. Was
                // Compose `Color.Red` (#FF0000) — harsher than iOS.
                Text(text = "*", color = Color(0xFFFF3B30), fontSize = 15.sp)
            }
        }
    }
}

/** Generic text-based input (text, number, email, phone, url). */
@Composable
private fun FormInputTextBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
    keyboardType: Int,
) {
    val fieldId = block.field_id ?: block.id
    // SPEC-070-A finalization OB-6 — restore saved value on back nav.
    // iOS pre-populates `_inputValues = State(initialValue: savedResponses ?? [:])`
    // (`OnboardingRenderer.swift:1326`); Android previously initialized to ""
    // so every back-press wiped typed answers. Read from inputValues map.
    var text by remember { mutableStateOf((inputValues[fieldId] as? String) ?: "") }
    val borderColor = StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB")
    val cornerRadius = (block.field_style?.corner_radius ?: 8.0).dp
    // SPEC-419 pass-16 #4 — honor field_config.input_text_size (preferred) / font_size
    // (default 14), mirroring preview + iOS precedence. Was no textStyle (M3 default).
    val inputFontSize = ((block.field_config?.get("input_text_size") as? Number)?.toFloat()
        ?: (block.field_config?.get("font_size") as? Number)?.toFloat() ?: 14f).sp
    // SPEC-419 pass-16 #5 — honor field_config.field_height (editor "Field Height"),
    // mirroring iOS fieldHeight() minHeight. Was ignored on Android.
    val fieldHeightDp = (block.field_config?.get("field_height") as? Number)?.toFloat()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        val kbType = when (keyboardType) {
            android.text.InputType.TYPE_CLASS_NUMBER -> androidx.compose.ui.text.input.KeyboardType.Number
            android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> androidx.compose.ui.text.input.KeyboardType.Email
            android.text.InputType.TYPE_CLASS_PHONE -> androidx.compose.ui.text.input.KeyboardType.Phone
            android.text.InputType.TYPE_TEXT_VARIATION_URI -> androidx.compose.ui.text.input.KeyboardType.Uri
            else -> androidx.compose.ui.text.input.KeyboardType.Text
        }

        // SPEC-401-A R42 (Lens C #1) — wire field_style.text_color +
        // background_color + focused_background_color + border_color so
        // OutlinedTextField actually applies the console-authored
        // theming. iOS FormInputBlockViews.swift:88-106 reads + applies
        // all of these. Android previously parsed border_color but never
        // threaded it into `colors=`/`border=` modifier — so authored
        // borders were silently invisible. Same payload rendered styled
        // on iOS and Material-default on Android.
        val textColor = block.field_style?.text_color?.let { StyleEngine.parseColor(it) } ?: Color.Unspecified
        // SPEC-419 — default the field CONTAINER to TRANSPARENT (was Color.Unspecified →
        // M3's default surface fill, which drew a filled rounded-rect INSIDE the custom
        // .border() = the "double border" the user saw). Transparent → only the authored
        // .border() shows; the page gradient reads through cleanly like iOS.
        val bgColor = block.field_style?.background_color?.let { StyleEngine.parseColor(it) } ?: Color.Transparent
        val focusedBgColor = block.field_style?.focused_background_color?.let { StyleEngine.parseColor(it) } ?: bgColor
        val focusedBorderColor = block.field_style?.focused_border_color?.let { StyleEngine.parseColor(it) } ?: borderColor
        // SPEC-401-A R55 (Lens C R54 #3, P2) — honor field_style.border_width
        // (and focused_border_width) by drawing the border on an outer Box
        // and zeroing OutlinedTextField's built-in border colors. Compose's
        // OutlinedTextField doesn't expose border thickness through `colors=`
        // so authored 2/3/4dp widths were silently dropped (always 1dp
        // unfocused / 2dp focused). iOS FormInputBlockViews.swift:103-106
        // reads `block.field_style?.border_width ?? 1`.
        val borderWidth = (block.field_style?.border_width ?: 1.0).dp
        // Focus uses authored width if set, else iOS-equivalent 2dp accent.
        val focusedBorderWidth = (block.field_style?.border_width ?: 2.0).dp
        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val isFocused by interactionSource.collectIsFocusedAsState()
        // Audit pass-8 — honor authored field_style.border_width by drawing the
        // border on an OUTER Box and zeroing the field's built-in border (fixed at
        // M3 1dp/2dp), mirroring FormInputTextAreaBlock (:7600-7636) and iOS
        // FormInputBlockViews.swift:103-106. borderWidth/focusedBorderWidth/isFocused
        // were computed above but unused (dead code) — this wires them up. The
        // SPEC-419 double-border guard is preserved: when the block itself draws a
        // container outline (block_style.border_width > 0 — e.g. login capsule
        // inputs), skip the outer border so the two outlines don't stack.
        val hasBlockBorder = (block.block_style?.border_width ?: 0.0) > 0.0
        Box(
            modifier = if (hasBlockBorder) Modifier.fillMaxWidth()
                else Modifier.fillMaxWidth().border(
                    width = if (isFocused) focusedBorderWidth else borderWidth,
                    color = if (isFocused) focusedBorderColor else borderColor,
                    shape = RoundedCornerShape(cornerRadius),
                ),
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    inputValues[fieldId] = it
                },
                placeholder = {
                    Text(
                        text = block.field_placeholder ?: "",
                        color = StyleEngine.parseColor(block.field_style?.placeholder_color ?: "#9CA3AF"),
                    )
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = kbType,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                shape = RoundedCornerShape(cornerRadius),
                textStyle = TextStyle(fontSize = inputFontSize),
                modifier = if (fieldHeightDp != null) Modifier.fillMaxWidth().heightIn(min = fieldHeightDp.dp) else Modifier.fillMaxWidth(),
                singleLine = true,
                interactionSource = interactionSource,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = textColor,
                    unfocusedTextColor = textColor,
                    focusedContainerColor = focusedBgColor,
                    unfocusedContainerColor = bgColor,
                    // Built-in border always zeroed: the authored width is drawn by the
                    // outer Box, or (when the block draws its own outline) the block_style
                    // capsule owns it — either way the field itself stays border-less.
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}

/** Multi-line textarea input. */
@Composable
private fun FormInputTextAreaBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    // SPEC-070-A finalization OB-6 — restore saved value on back nav.
    // iOS pre-populates `_inputValues = State(initialValue: savedResponses ?? [:])`
    // (`OnboardingRenderer.swift:1326`); Android previously initialized to ""
    // so every back-press wiped typed answers. Read from inputValues map.
    var text by remember { mutableStateOf((inputValues[fieldId] as? String) ?: "") }
    val minLines = (block.field_config?.get("min_lines") as? Number)?.toInt() ?: 3
    // SPEC-419 pass-19 #2 — honor field_config.field_height as an additional minimum
    // (the editor shows "Field Height" for textarea too; iOS sibling now mirrors this).
    // min_lines stays the floor; field_height adds a second null-safe minimum.
    val taFieldHeightDp = (block.field_config?.get("field_height") as? Number)?.toFloat()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        // SPEC-401-A R42 (Lens C #1) — same field_style wiring as
        // FormInputTextBlock above; was ignoring all field_style except
        // corner_radius.
        val textColor = block.field_style?.text_color?.let { StyleEngine.parseColor(it) } ?: Color.Unspecified
        // SPEC-419 — default the field CONTAINER to TRANSPARENT (was Color.Unspecified →
        // M3's default surface fill, which drew a filled rounded-rect INSIDE the custom
        // .border() = the "double border" the user saw). Transparent → only the authored
        // .border() shows; the page gradient reads through cleanly like iOS.
        val bgColor = block.field_style?.background_color?.let { StyleEngine.parseColor(it) } ?: Color.Transparent
        val focusedBgColor = block.field_style?.focused_background_color?.let { StyleEngine.parseColor(it) } ?: bgColor
        val borderColor = StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB")
        val focusedBorderColor = block.field_style?.focused_border_color?.let { StyleEngine.parseColor(it) } ?: borderColor
        // SPEC-401-A R55 (Lens C R54 #3, P2) — same border_width pattern as
        // FormInputTextBlock above. Compose's OutlinedTextField has no border
        // thickness API on `colors=`, so authored field_style.border_width
        // was silently dropped. Outer Box draws the border; inner field uses
        // transparent built-in border colors.
        val cornerRadius = (block.field_style?.corner_radius ?: 8.0).dp
        val borderWidth = (block.field_style?.border_width ?: 1.0).dp
        val focusedBorderWidth = (block.field_style?.border_width ?: 2.0).dp
        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val isFocused by interactionSource.collectIsFocusedAsState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (isFocused) focusedBorderWidth else borderWidth,
                    color = if (isFocused) focusedBorderColor else borderColor,
                    shape = RoundedCornerShape(cornerRadius),
                ),
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    inputValues[fieldId] = it
                },
                placeholder = {
                    Text(
                        text = block.field_placeholder ?: "",
                        color = StyleEngine.parseColor(block.field_style?.placeholder_color ?: "#9CA3AF"),
                    )
                },
                shape = RoundedCornerShape(cornerRadius),
                modifier = Modifier.fillMaxWidth().let { if (taFieldHeightDp != null) it.heightIn(min = taFieldHeightDp.dp) else it },
                singleLine = false,
                minLines = minLines,
                interactionSource = interactionSource,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = textColor,
                    unfocusedTextColor = textColor,
                    focusedContainerColor = focusedBgColor,
                    unfocusedContainerColor = bgColor,
                    // Built-in border zeroed; outer Box draws the authored width.
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}

/** Password input with show/hide toggle. */
@Composable
private fun FormInputPasswordBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    // SPEC-070-A finalization OB-6 — restore saved value on back nav.
    // iOS pre-populates `_inputValues = State(initialValue: savedResponses ?? [:])`
    // (`OnboardingRenderer.swift:1326`); Android previously initialized to ""
    // so every back-press wiped typed answers. Read from inputValues map.
    var text by remember { mutableStateOf((inputValues[fieldId] as? String) ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        // QA-R16 \u2014 explicit `OutlinedTextFieldDefaults.colors(...)` so
        // the password field stops falling through to Material3's purple
        // `colorScheme.primary` for `focusedBorderColor`, `focusedLabelColor`,
        // `cursorColor`, `focusedPlaceholderColor` defaults. Reads any
        // console-authored colors first; otherwise defaults to white-on-
        // transparent that matches onboarding gradient backgrounds.
        val focusedBorder = block.field_style?.focused_border_color?.let { StyleEngine.parseColor(it) } ?: Color.White
        val unfocusedBorder = block.field_style?.border_color?.let { StyleEngine.parseColor(it) } ?: Color.White.copy(alpha = 0.3f)
        val textCol = block.field_style?.text_color?.let { StyleEngine.parseColor(it) } ?: Color.White
        val placeholderCol = block.field_style?.placeholder_color?.let { StyleEngine.parseColor(it) } ?: Color.White.copy(alpha = 0.5f)
        val cursorCol = focusedBorder
        // SPEC-419 pass-17 — mirror FormInputTextBlock font_size + field_height onto the password sibling.
        val pwFontSize = ((block.field_config?.get("input_text_size") as? Number)?.toFloat()
            ?: (block.field_config?.get("font_size") as? Number)?.toFloat() ?: 14f).sp
        val pwFieldHeightDp = (block.field_config?.get("field_height") as? Number)?.toFloat()
        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                inputValues[fieldId] = it
            },
            placeholder = { Text(block.field_placeholder ?: "Password", color = placeholderCol) },
            shape = RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = pwFontSize),
            // SPEC-401-A R11 \u2014 Password Autofill keyboard + Done return key.
            modifier = Modifier.fillMaxWidth().let { if (pwFieldHeightDp != null) it.heightIn(min = pwFieldHeightDp.dp) else it },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            ),
            visualTransformation = if (passwordVisible)
                androidx.compose.ui.text.input.VisualTransformation.None
            else
                androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = {
                // QA-R16 \u2014 replace literal `\uD83D\uDE48`/`\uD83D\uDC41` emoji with
                // proper Material icons so the eye toggle scales correctly
                // with system font size and renders consistently across
                // OEM emoji fonts.
                androidx.compose.material3.IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    androidx.compose.material3.Icon(
                        imageVector = if (passwordVisible)
                            androidx.compose.material.icons.Icons.Filled.VisibilityOff
                        else
                            androidx.compose.material.icons.Icons.Filled.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        tint = textCol.copy(alpha = 0.7f),
                    )
                }
            },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                // SPEC-419 — border-LESS when the block already draws a container border
                // (block_style.border_width > 0), else the field + block outlines double up.
                focusedBorderColor = if ((block.block_style?.border_width ?: 0.0) > 0.0) Color.Transparent else focusedBorder,
                unfocusedBorderColor = if ((block.block_style?.border_width ?: 0.0) > 0.0) Color.Transparent else unfocusedBorder,
                focusedTextColor = textCol,
                unfocusedTextColor = textCol,
                focusedPlaceholderColor = placeholderCol,
                unfocusedPlaceholderColor = placeholderCol,
                focusedLabelColor = focusedBorder,
                unfocusedLabelColor = unfocusedBorder,
                cursorColor = cursorCol,
                // SPEC-419 pass-18 — honor field_style.background_color ("Field Fill") like the
                // Text/TextArea siblings + iOS; defaults Transparent so M3's default surface fill
                // doesn't draw inside the border (the "double border" vs the page bg).
                // SPEC-419 pass-19 #4 — differentiate focused_background_color on focus like the
                // Text/TextArea siblings (focusedBgColor = focused_background_color ?? background_color).
                focusedContainerColor = block.field_style?.focused_background_color?.let { StyleEngine.parseColor(it) }
                    ?: block.field_style?.background_color?.let { StyleEngine.parseColor(it) } ?: Color.Transparent,
                unfocusedContainerColor = block.field_style?.background_color?.let { StyleEngine.parseColor(it) } ?: Color.Transparent,
            ),
        )
    }
}

/** Date / Time / DateTime picker input. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormInputDateBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
    mode: String,
) {
    val fieldId = block.field_id ?: block.id
    // SPEC-401-A R12 — restore the saved value into the visible button
    // text on back-nav so the user sees their prior answer. Previous
    // impl only restored `pendingDate` (datetime sub-state) and left
    // displayText showing "Select date..." as if no answer existed.
    val savedRaw = (inputValues[fieldId] as? String).orEmpty()

    // SPEC-401-A R35 \u2014 match iOS FormInputBlockViews.swift display formatting.
    // iOS shows a localised `DateFormatter` short string when the user
    // reopens the field; Android was leaking the raw ISO8601 wire format.
    val isoFormatter = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
    }
    // SPEC-401-A R57 (Lens A R57 P1) — derive `<field_id>_age` from picked
    // birth-date in date-only mode. Mirrors iOS FormInputBlockViews.swift
    // :1190-1199 which exposes `inputValues["\(fieldId)_age"]` (whole years
    // from selectedDate to now). Hosts branching on `birth_date_age` got
    // nothing on Android. Date-only mode only — datetime/time skip.
    fun persistAgeIfDate(epochMillis: Long) {
        if (mode != "date" && mode.isNotEmpty() && mode != "input_date") return
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
        val now = java.util.Calendar.getInstance()
        var years = now.get(java.util.Calendar.YEAR) - cal.get(java.util.Calendar.YEAR)
        if (now.get(java.util.Calendar.DAY_OF_YEAR) < cal.get(java.util.Calendar.DAY_OF_YEAR)) years--
        if (years < 0) return
        inputValues["${fieldId}_age"] = years
    }
    // SPEC — honor time_format ("12h"/"24h") on the displayed time. When set,
    // build an explicit SimpleDateFormat (HH:mm 24h / h:mm a 12h) instead of the
    // locale-default SHORT time; unset keeps the device-locale behaviour.
    val timeFmt = block.time_format?.lowercase()
    val displayFormatter = remember(mode, timeFmt) {
        val timePattern = if (timeFmt == "24h") "HH:mm" else "h:mm a"
        when (mode) {
            "time" ->
                if (timeFmt != null) java.text.SimpleDateFormat(timePattern, java.util.Locale.US)
                else java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, java.util.Locale.getDefault())
            "datetime" ->
                if (timeFmt != null) java.text.SimpleDateFormat("MMM d, yyyy $timePattern", java.util.Locale.US)
                else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, java.util.Locale.getDefault())
            else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale.getDefault())
        }
    }
    fun formatForDisplay(iso: String): String {
        if (iso.isEmpty()) return block.field_placeholder ?: "Select $mode..."
        return try {
            val parsed = isoFormatter.parse(iso)
            if (parsed != null) displayFormatter.format(parsed) else iso
        } catch (_: Exception) { iso }
    }

    var displayText by remember { mutableStateOf(formatForDisplay(savedRaw)) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDate by remember { mutableStateOf(savedRaw) }
    // SPEC-401-A R49 (Lens A #3, P1) \u2014 allow_future/allow_past date validation.
    // iOS ContentBlockStandaloneViews.swift:966,970,1160,1174-1175 enforces
    // these constraints and surfaces an inline error string under the picker.
    // Android was silently writing invalid dates.
    var dateError by remember { mutableStateOf<String?>(null) }
    // Audit pass-8 — resolve min_date/max_date to day-bounded epoch millis so
    // validateDate can enforce them. These were parsed on the block and honored
    // by DateWheelPicker (:5899-5920) + FormStep, but the calendar/DatePicker
    // path silently dropped them (only allow_future/allow_past were checked),
    // matching the iOS gap. Reuse parseDateWheelSeed (the same helper the wheel
    // uses for its min/max clamp) and normalize min→start-of-day, max→end-of-day
    // so a pick landing exactly on the boundary day isn't rejected by time-of-day.
    val minDateMillis = parseDateWheelSeed(block.min_date, null)?.apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }?.timeInMillis
    val maxDateMillis = parseDateWheelSeed(block.max_date, null)?.apply {
        set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 59)
        set(java.util.Calendar.SECOND, 59); set(java.util.Calendar.MILLISECOND, 999)
    }?.timeInMillis
    fun validateDate(millis: Long): Boolean {
        val now = System.currentTimeMillis()
        val msg = block.date_validation_message
        if (block.allow_future == false && millis > now) {
            dateError = msg ?: "Future dates are not allowed"
            return false
        }
        if (block.allow_past == false && millis < now) {
            dateError = msg ?: "Past dates are not allowed"
            return false
        }
        if (minDateMillis != null && millis < minDateMillis) {
            dateError = msg ?: "Date is before the earliest allowed date"
            return false
        }
        if (maxDateMillis != null && millis > maxDateMillis) {
            dateError = msg ?: "Date is after the latest allowed date"
            return false
        }
        dateError = null
        return true
    }

    // SPEC-401-A R35 \u2014 picker_variant per iOS FormInputBlockViews.swift:208-410.
    // "graphical" \u2192 inline DatePicker; "compact"/null/unknown \u2192 tap-to-open
    // button. "wheel" falls back to compact today (Material3 lacks a wheel
    // date picker out of the box; tracked for follow-up).
    // SPEC-401-A R49 (Lens A #4, P1) \u2014 picker_presentation="field" forces
    // tap-to-open compact; otherwise legacy field_config.picker_variant
    // controls inline graphical vs compact. picker_mode similarly may
    // override the function `mode` parameter.
    val effectiveMode = block.picker_mode ?: mode
    val pickerVariant = when {
        block.picker_presentation == "field" -> "compact"
        block.picker_presentation == "inline" -> "graphical"
        else -> (block.field_config?.get("picker_variant") as? String)?.lowercase() ?: "compact"
    }
    val inlineGraphical = pickerVariant == "graphical" && (effectiveMode == "date" || effectiveMode == "datetime")

    // SPEC-419 pass-15 #16/#17/#34 — honor field_style.text_color (compact button text),
    // calendar_bg_color/wheel_bg_color (picker + button background), and highlight_color
    // (picker accent). iOS + preview already apply these.
    val buttonTextColor = block.field_style?.text_color?.let { StyleEngine.parseColor(it) } ?: MaterialTheme.colorScheme.onSurface
    val highlightColor = block.highlight_color?.let { StyleEngine.parseColor(it) }
    val calendarBg = ((block.field_config?.get("calendar_bg_color") as? String)?.let { StyleEngine.parseColor(it) }
        ?: block.calendar_bg_color?.let { StyleEngine.parseColor(it) })
        ?.let { c -> (block.field_config?.get("calendar_opacity") as? Number)?.toFloat()?.let { c.copy(alpha = it.coerceIn(0f, 1f)) } ?: c }  // SPEC-419 pass-26 — fold calendar_opacity into the fill alpha (iOS applies .opacity)
    val wheelBg = ((block.field_config?.get("wheel_bg_color") as? String)?.let { StyleEngine.parseColor(it) }
        ?: block.wheel_bg_color?.let { StyleEngine.parseColor(it) })
        ?.let { c -> (block.field_config?.get("wheel_opacity") as? Number)?.toFloat()?.let { c.copy(alpha = it.coerceIn(0f, 1f)) } ?: c }  // fold wheel_opacity into fill alpha (iOS applies .opacity), mirroring calendarBg
    // SPEC-419 pass-16 #12 — honor wheel_text_color on the inline graphical picker
    // (day/weekday/year content), mirroring iOS colorMultiply + preview wheelText.
    val wheelTextColor = (block.field_config?.get("wheel_text_color") as? String)?.let { StyleEngine.parseColor(it) }
    val datePickerColors: androidx.compose.material3.DatePickerColors = androidx.compose.material3.DatePickerDefaults.colors().let { base ->
        if (highlightColor == null && calendarBg == null && wheelTextColor == null) base
        else androidx.compose.material3.DatePickerDefaults.colors(
            containerColor = calendarBg ?: base.containerColor,
            selectedDayContainerColor = highlightColor ?: base.selectedDayContainerColor,
            todayDateBorderColor = highlightColor ?: base.todayDateBorderColor,
            selectedYearContainerColor = highlightColor ?: base.selectedYearContainerColor,
            dayContentColor = wheelTextColor ?: base.dayContentColor,
            weekdayContentColor = wheelTextColor ?: base.weekdayContentColor,
            yearContentColor = wheelTextColor ?: base.yearContentColor,
        )
    }
    // SPEC-419 pass-16 #11 — opt-in picker border + padding around the whole picker
    // (any variant). Mirrors editor field_config.picker_border_*/picker_padding +
    // iOS overlay + preview OnboardingStepPreview.tsx:2577-2580.
    val pickerBorderColorHex = block.field_config?.get("picker_border_color") as? String
    val pickerBorderWidth = (block.field_config?.get("picker_border_width") as? Number)?.toFloat()
        ?: (if (pickerBorderColorHex != null) 1f else 0f)
    val pickerCornerRadius = ((block.field_config?.get("picker_corner_radius") as? Number)?.toFloat() ?: 12f).dp
    val pickerPadding = ((block.field_config?.get("picker_padding") as? Number)?.toFloat() ?: 0f).dp
    val pickerChromeMod = Modifier
        .then(
            if (pickerBorderWidth > 0f && pickerBorderColorHex != null)
                Modifier.border(pickerBorderWidth.dp, StyleEngine.parseColor(pickerBorderColorHex), RoundedCornerShape(pickerCornerRadius))
            else Modifier,
        )
        .padding(pickerPadding)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        if (inlineGraphical) {
            val initialMillis = remember(savedRaw) {
                if (savedRaw.isEmpty()) null else try { isoFormatter.parse(savedRaw)?.time } catch (_: Exception) { null }
            }
            val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
            DatePicker(state = datePickerState, modifier = Modifier.fillMaxWidth().then(pickerChromeMod), colors = datePickerColors)
            LaunchedEffect(datePickerState.selectedDateMillis) {
                val millis = datePickerState.selectedDateMillis
                if (millis != null && validateDate(millis)) {
                    val isoStr = isoFormatter.format(java.util.Date(millis))
                    if (effectiveMode == "datetime") {
                        pendingDate = isoStr
                        showTimePicker = true
                    } else {
                        displayText = formatForDisplay(isoStr)
                        inputValues[fieldId] = isoStr
                        persistAgeIfDate(millis)
                    }
                }
            }
        } else {
            // AC-042: Tappable button opens actual Material3 date/time picker
            OutlinedButton(
                onClick = {
                    // SPEC-401-A R49 (Lens A #4) — use effectiveMode (block.picker_mode override).
                    when (effectiveMode) {
                        "date", "datetime" -> showDatePicker = true
                        "time" -> showTimePicker = true
                    }
                },
                modifier = Modifier.fillMaxWidth().then(pickerChromeMod),
                shape = RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB"),
                ),
                // SPEC-419 pass-15 #16/#17 — honor field_style.text_color + wheel/calendar bg.
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = buttonTextColor,
                    containerColor = wheelBg ?: calendarBg ?: Color.Transparent,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // SPEC — honor time_text_size on the displayed time (time/datetime modes).
                    Text(
                        text = displayText,
                        fontSize = (if (effectiveMode == "time" || effectiveMode == "datetime") block.time_text_size else null)?.sp ?: 14.sp,
                        color = buttonTextColor,
                    )
                    // SPEC-401-A R49 (Lens A #4) \u2014 use effectiveMode for icon.
                    Text(text = when (effectiveMode) {
                        "date" -> "\uD83D\uDCC5"
                        "time" -> "\u23F0"
                        else -> "\uD83D\uDCC5"
                    }, fontSize = 16.sp)
                }
            }
        }
        // SPEC-401-A R49 (Lens A #3, P1) \u2014 inline error caption when
        // allow_future / allow_past validation rejects a selection.
        // iOS ContentBlockStandaloneViews.swift:1174-1175 surfaces the
        // same red-tinted caption directly under the picker.
        dateError?.let { msg ->
            Text(
                text = msg,
                fontSize = 12.sp,
                color = block.field_style?.error_text_color?.let { StyleEngine.parseColor(it) } ?: MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    // SPEC-401-A R12 — match iOS ISO 8601 serialization at
    // FormInputBlockViews.swift:386-389 (`ISO8601DateFormatter().string(from:)`).
    // Previous Android impl wrote `"yyyy-MM-dd"` strings, so server-side
    // consumers + journey triggers + host analytics saw incompatible
    // payloads from the two SDKs for the same flow. iOS's
    // ISO8601DateFormatter defaults emit `yyyy-MM-dd'T'HH:mm:ssZ` in UTC.
    // (R35 — formatter hoisted earlier in the function next to displayFormatter
    // so both inline-graphical and dialog branches reuse the same instance.)

    // Material3 DatePickerDialog
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    showDatePicker = false
                    val millis = datePickerState.selectedDateMillis
                    // SPEC-401-A R49 (Lens A #3) — gate on validateDate.
                    if (millis != null && validateDate(millis)) {
                        // ISO8601 normalised to midnight UTC of the picked day.
                        val isoStr = isoFormatter.format(java.util.Date(millis))
                        if (effectiveMode == "datetime") {
                            pendingDate = isoStr
                            showTimePicker = true
                        } else {
                            displayText = formatForDisplay(isoStr)
                            inputValues[fieldId] = isoStr
                            persistAgeIfDate(millis)
                        }
                    }
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
            colors = datePickerColors,
        ) {
            DatePicker(state = datePickerState, colors = datePickerColors)
        }
    }

    // Material3 TimePickerDialog (using AlertDialog wrapper)
    if (showTimePicker) {
        // SPEC — honor time_format: "24h" → 24-hour columns; "12h" → 12-hour + AM/PM;
        // unset → follow device locale (matches iOS, which follows locale for both the
        // wheel and the display text).
        val is24 = block.time_format?.lowercase()?.let { it == "24h" }
            ?: android.text.format.DateFormat.is24HourFormat(LocalContext.current)
        val timePickerState = rememberTimePickerState(is24Hour = is24)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    showTimePicker = false
                    if (effectiveMode == "datetime" && pendingDate.isNotEmpty()) {
                        // Combine: parse pendingDate (ISO date), apply hour/minute,
                        // re-serialise to ISO8601 with the user's chosen time.
                        try {
                            val baseDate = isoFormatter.parse(pendingDate)
                            if (baseDate != null) {
                                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                                cal.time = baseDate
                                cal.set(java.util.Calendar.HOUR_OF_DAY, timePickerState.hour)
                                cal.set(java.util.Calendar.MINUTE, timePickerState.minute)
                                cal.set(java.util.Calendar.SECOND, 0)
                                val combined = isoFormatter.format(cal.time)
                                displayText = formatForDisplay(combined)
                                inputValues[fieldId] = combined
                            }
                        } catch (_: Exception) {
                            // Fallback: keep pendingDate alone if parse fails.
                            displayText = formatForDisplay(pendingDate)
                            inputValues[fieldId] = pendingDate
                        }
                        pendingDate = ""
                    } else {
                        // Time-only mode: ISO8601 with today's date in UTC.
                        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                        cal.set(java.util.Calendar.HOUR_OF_DAY, timePickerState.hour)
                        cal.set(java.util.Calendar.MINUTE, timePickerState.minute)
                        cal.set(java.util.Calendar.SECOND, 0)
                        val timeStr = isoFormatter.format(cal.time)
                        displayText = formatForDisplay(timeStr)
                        inputValues[fieldId] = timeStr
                    }
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
            title = { Text("Select time") },
            // SPEC-419 pass-16 #14 — honor highlight_color on the time picker (selector +
            // selected containers); was M3 primary only. iOS FormInputDateBlock uses .tint(accentColor).
            text = {
                if (highlightColor != null) {
                    TimePicker(
                        state = timePickerState,
                        colors = androidx.compose.material3.TimePickerDefaults.colors(
                            selectorColor = highlightColor,
                            periodSelectorSelectedContainerColor = highlightColor,
                            timeSelectorSelectedContainerColor = highlightColor.copy(alpha = 0.2f),
                        ),
                    )
                } else {
                    TimePicker(state = timePickerState)
                }
            },
        )
    }
}

/** Dropdown/stacked/grid select input with display_style support. */
// SPEC-419 pass-25 — selection indicator honoring field_config.radio_fill.
// Parity with iOS radioIndicator (FormInputBlockViews.swift:935):
//   "circle"    → default Material RadioButton (single) / Checkbox (multi)
//   "checkmark" → filled check-circle when selected, empty circle when not
//   <emoji>     → the glyph when selected, "○" when not (SF Symbol names are
//                 iOS-only and fall back to the default circle on Android)
@Composable
private fun SelectRadioIndicator(
    isSelected: Boolean,
    isMulti: Boolean,
    fillCol: Color,
    radioFill: String,
) {
    when {
        radioFill == "checkmark" -> {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = fillCol,
                    modifier = Modifier.size(24.dp),
                )
            } else if (isMulti) {
                Checkbox(checked = false, onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = fillCol))
            } else {
                RadioButton(selected = false, onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = fillCol))
            }
        }
        radioFill == "circle" -> {
            if (isMulti) {
                Checkbox(checked = isSelected, onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = fillCol))
            } else {
                RadioButton(selected = isSelected, onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = fillCol))
            }
        }
        // Emoji glyph (short, all non-ASCII) renders directly; anything else
        // (incl. SF Symbol names) falls back to the default circle indicator.
        radioFill.length <= 2 && radioFill.isNotEmpty() && radioFill.all { it.code > 127 } -> {
            Text(text = if (isSelected) radioFill else "○", fontSize = 20.sp)
        }
        else -> {
            if (isMulti) {
                Checkbox(checked = isSelected, onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = fillCol))
            } else {
                RadioButton(selected = isSelected, onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = fillCol))
            }
        }
    }
}

@Composable
private fun FormInputSelectBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    val options = block.field_options ?: emptyList()
    // Gap 1: Read display_style from field_config; defaults to "dropdown".
    val displayStyle = (block.field_config?.get("display_style") as? String) ?: "dropdown"
    // Gap 8: Gracefully handle dynamic options (use_variable / use_webhook) — parse but ignore
    val useVariable = block.field_config?.get("use_variable") as? String
    val useWebhook = block.field_config?.get("use_webhook") as? String
    // For now, dynamic options require server round-trip. Display static options if present.
    // SPEC-401-A R49 (Lens A #1) — multi-select branch. iOS
    // FormInputBlockViews.swift:403-406 selects between single + multi
    // based on `block.multi_select == true`. For multi-mode the value
    // written to inputValues is List<String> (list of selected values),
    // for single-mode it remains a single String. Dropdown layout is
    // single-only on both platforms (multi-dropdown is rare UX).
    val isMulti = (block.multi_select == true) ||
        ((block.field_config?.get("multi_select") as? Boolean) == true)
    // Hotfix-1.0.33 — field_config block-level styling reads, mirroring iOS
    // FormInputBlockViews.swift:478-530 (`stackedSelectView`). Each iOS read
    // gets a Kotlin counterpart so console-authored colors stop being silently
    // dropped. The `option.bg_color / selected_bg_color / border_color /
    // selected_border_color / selected_text_color` per-option fields are
    // resolved inline at the option-row site below — these are just the
    // block-level fallbacks behind them.
    val cfg = block.field_config
    val accentHex = block.field_style?.fill_color
        ?: block.field_style?.focused_border_color
        ?: block.active_color
        ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1")
    val fillCol = StyleEngine.parseColor(accentHex)
    // Legacy corner radius — used by the GRID renderer (unchanged; ignores
    // option_corner_radius so grid stays isolated, matching iOS gridSelectView + preview grid).
    val cornerR = (block.field_style?.corner_radius ?: 10.0).dp
    // Select v2 — STACKED option card corner: option_corner_radius wins, else legacy, else 10.
    val optionCornerR = (block.field_style?.option_corner_radius ?: block.field_style?.corner_radius ?: 10.0).dp
    // Select v2 — per-option styling extras (Mrozu QA).
    val optionFontFamily = block.field_style?.option_font_family?.let { ai.appdna.sdk.core.FontResolver.resolve(it) }
    // Default TRUE = full wrap (preserves prior native behavior + the Mrozu ask that
    // long option text stays fully visible). option_text_wrap=false opts into truncation.
    val optionTextWrap = block.field_style?.option_text_wrap ?: true
    val optionImageScale = block.field_style?.option_image_scale ?: "contain"
    // "cover" crops/fills; "contain"/"fit" fit the whole image inside so an oversized image is not cropped.
    val optionImageContentScale = if (optionImageScale == "cover") ContentScale.Crop else ContentScale.Fit
    // checkmark_color decouples the indicator tint from the accent/fill; falls back to fillCol when unset.
    val checkmarkCol = block.field_style?.checkmark_color?.let { StyleEngine.parseColor(it) } ?: fillCol
    val cfgOptBg = (cfg?.get("bg_color") as? String)?.let { StyleEngine.parseColor(it) }
    val cfgOptBorder = (cfg?.get("border_color") as? String)?.let { StyleEngine.parseColor(it) }
    val cfgSelectedBg = (cfg?.get("selected_bg_color") as? String)?.let { StyleEngine.parseColor(it) }
    val cfgSelectedText = (cfg?.get("selected_text_color") as? String)?.let { StyleEngine.parseColor(it) }
    val cfgOptText = (cfg?.get("text_color") as? String)?.let { StyleEngine.parseColor(it) }
    // EPIC-1 Win 1 — honor authored `option_image_size` for per-option images (was hardcoded
    // 24dp stacked / 32dp grid → flags/icons squished, and ignored the console slider that iOS
    // already reads). Defaults match iOS: 32 stacked (FormInputBlockViews.swift:539), 40 grid (:871).
    val optImgSizeRaw = (cfg?.get("option_image_size") as? Number)?.toFloat()
    // Unstyled options fall back to fully transparent to match iOS
    // (`Color.clear`) and the console preview ('transparent') — so an
    // unstyled stacked/grid select shows the step gradient through the
    // option, NOT a frosted-glass card. The `c.alpha * bgOpacity` multiply
    // at the use sites preserves the base color's alpha, so a transparent
    // base (alpha=0) stays transparent regardless of bgOpacity.
    val unselectedBg = cfgOptBg
        ?: block.field_style?.background_color?.let { StyleEngine.parseColor(it) }
        ?: Color.Transparent
    val selectedBg = cfgSelectedBg ?: fillCol.copy(alpha = 0.15f)
    val unselectedBorder = cfgOptBorder
        ?: block.field_style?.border_color?.let { StyleEngine.parseColor(it) }
        // EPIC-1 — neutral gray default (was accent fillCol@0.3 = the "purple-border bug" that
        // tinted every unselected option with the accent). Matches iOS field-border #D1D5DB.
        ?: StyleEngine.parseColor("#D1D5DB")
    val selectedBorder = fillCol
    val textCol = cfgOptText
        ?: block.field_style?.text_color?.let { StyleEngine.parseColor(it) }
        ?: Color.Unspecified
    val selectedTextCol = cfgSelectedText ?: textCol
    // Selection indicator: "radio" (default), "border", "both", "none"
    val selectionIndicator = (cfg?.get("selection_indicator") as? String) ?: "radio"
    // EPIC-1 — selection animation glow ("glow"/"pulse"/"sparkle" → accent halo on the selected option).
    val selectionAnimation = (cfg?.get("selection_animation") as? String) ?: "none"
    val showRadio = selectionIndicator == "radio" || selectionIndicator == "both"
    // Only thicken the selected border when the indicator mode actually draws a border
    // highlight ("border"/"both"). In "radio"/"none" modes the selected option keeps the
    // unselected border width — matches iOS + console preview.
    val showBorderHighlight = selectionIndicator == "border" || selectionIndicator == "both"
    val radioPosition = (cfg?.get("radio_position") as? String) ?: "right"
    val radioOnLeft = radioPosition == "left" || radioPosition == "leading"
    // SPEC-419 pass-25 — radio_fill: "circle" (default), "checkmark", or an emoji glyph.
    // Mirrors iOS radioIndicator (FormInputBlockViews.swift:935). SF Symbol names are
    // iOS-only → on Android they fall back to the default circle (handled in SelectRadioIndicator).
    val radioFill = (cfg?.get("radio_fill") as? String) ?: "circle"
    val selectedBorderW = ((cfg?.get("selected_border_width") as? Number)?.toDouble() ?: 2.0).dp
    val unselectedBorderW = ((cfg?.get("unselected_border_width") as? Number)?.toDouble() ?: 1.0).dp
    val bgOpacity = ((cfg?.get("background_opacity") as? Number)?.toDouble() ?: 1.0).toFloat().coerceIn(0f, 1f)
    val optionSpacingDp = ((cfg?.get("option_spacing") as? Number)?.toDouble() ?: 8.0).dp
    // SPEC-419 pass-15 #36 — block-level title/subtitle font defaults from field_config (iOS
    // FormInputBlockViews.swift:678-679, defaults 15/12); per-option size overrides these.
    val defaultTitleSize = (cfg?.get("title_font_size") as? Number)?.toFloat() ?: 15f
    val defaultSubtitleSize = (cfg?.get("subtitle_font_size") as? Number)?.toFloat() ?: 12f
    var selectedValue by remember { mutableStateOf(inputValues[fieldId] as? String ?: "") }
    val initialSelected: Set<String> = remember {
        when (val existing = inputValues[fieldId]) {
            is List<*> -> existing.filterIsInstance<String>().toSet()
            is String -> if (existing.isNotEmpty()) setOf(existing) else emptySet()
            else -> emptySet()
        }
    }
    var selectedValues by remember { mutableStateOf(initialSelected) }
    fun toggleMulti(value: String) {
        selectedValues = if (selectedValues.contains(value)) {
            selectedValues - value
        } else {
            selectedValues + value
        }
        inputValues[fieldId] = selectedValues.toList()
    }
    fun isOptionSelected(value: String): Boolean =
        if (isMulti) selectedValues.contains(value) else selectedValue == value
    fun pickOption(value: String) {
        if (isMulti) {
            toggleMulti(value)
        } else {
            selectedValue = value
            inputValues[fieldId] = value
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        when (displayStyle) {
            "stacked" -> {
                // Stacked: Column of cards. Per-option bg/border/text colors honored
                // (Hotfix-1.0.33). Mirrors iOS FormInputBlockViews.swift:532-622.
                // SPEC-419 pass-13 — option_height (or size) → row min-height, mirroring iOS.
                val stackedMinHeight = ((cfg?.get("option_height") as? Number)?.toFloat()
                    ?: (cfg?.get("size") as? Number)?.toFloat())
                // SPEC-419 pass-24 — show_item_separators (console Switch, stacked only) draws a
                // divider between stacked options, honoring separator_color + separator_thickness
                // like the list variant. Off by default (cards already have borders).
                val showItemSeparators = (cfg?.get("show_item_separators") as? Boolean) == true
                val stackedSepColor = (cfg?.get("separator_color") as? String)?.let { StyleEngine.parseColor(it) }
                    ?: StyleEngine.parseColor("#D1D5DB")
                val stackedSepThickness = ((cfg?.get("separator_thickness") as? Number)?.toDouble() ?: 1.0).dp
                Column(
                    verticalArrangement = Arrangement.spacedBy(optionSpacingDp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    options.forEachIndexed { oi, option ->   // SPEC-419 — per-index parity node key
                        val isSelected = isOptionSelected(option.value)
                        // Per-option color overrides — each option can carry its own
                        // bg / border / selected colors that override the field_config
                        // block-level fallbacks resolved above. Selected state wins so
                        // `selected_text_color` always applies on top of `title_color`.
                        val optUnselBg = option.bg_color?.let { StyleEngine.parseColor(it) } ?: unselectedBg
                        val optSelBg = option.selected_bg_color?.let { StyleEngine.parseColor(it) } ?: selectedBg
                        val optUnselBorder = option.border_color?.let { StyleEngine.parseColor(it) } ?: unselectedBorder
                        val optSelBorder = option.selected_border_color?.let { StyleEngine.parseColor(it) } ?: selectedBorder
                        val optSelText = option.selected_text_color?.let { StyleEngine.parseColor(it) } ?: selectedTextCol
                        val optTitleColor = if (isSelected) optSelText else (option.title_color?.let { StyleEngine.parseColor(it) } ?: textCol)
                        // SPEC-419 — SELECTED-state legibility for the secondary labels (subtitle +
                        // trailing_text). When selected they adopt the option's selected text color so
                        // they stay readable on a colored selected bg; otherwise the faded base. Mirrors
                        // iOS FormInputBlockViews.swift (both use optSubtitleColor). Without this the
                        // subtitle ("Slow and steady") AND trailing label ("Popular") went invisible on
                        // the green selected card.
                        val optSubtitleColor = run {
                            val base = if (textCol == Color.Unspecified) Color.White else textCol
                            if (isSelected) {
                                option.selected_text_color?.let { StyleEngine.parseColor(it) }
                                    ?: option.subtitle_color?.let { StyleEngine.parseColor(it) }
                                    ?: base.copy(alpha = 0.65f)
                            } else {
                                option.subtitle_color?.let { StyleEngine.parseColor(it) } ?: base.copy(alpha = 0.65f)
                            }
                        }
                        // SPEC-401-A R64 — single click target so TalkBack treats
                        // Card+Checkbox/RadioButton as ONE element. Card owns the
                        // click + a11y; inner RadioButton/Checkbox uses null handler.
                        // SPEC-419 D5 — Box wrap so the badge can straddle the card top border
                        // (render half above the top edge, un-clipped by the Card).
                        androidx.compose.foundation.layout.Box {
                        Card(
                            modifier = Modifier
                                // EPIC-1 — selection_animation glow: accent halo on the selected option
                                // (static glow now; pulse/sparkle motion is a future dynamic layer).
                                .then(
                                    if (isSelected && selectionAnimation != "none") {
                                        Modifier.shadow(
                                            elevation = 12.dp,
                                            shape = RoundedCornerShape(optionCornerR),
                                            clip = false,
                                            ambientColor = fillCol,
                                            spotColor = fillCol,
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .fillMaxWidth()
                                .then(if (stackedMinHeight != null) Modifier.heightIn(min = stackedMinHeight.dp) else Modifier)
                                .testTag("option.$oi.row.bg")
                                .then(
                                    if (isMulti) {
                                        Modifier.toggleable(
                                            value = isSelected,
                                            role = androidx.compose.ui.semantics.Role.Checkbox,
                                            onValueChange = { pickOption(option.value) },
                                        )
                                    } else {
                                        Modifier.selectable(
                                            selected = isSelected,
                                            role = androidx.compose.ui.semantics.Role.RadioButton,
                                            onClick = { pickOption(option.value) },
                                        )
                                    }
                                ),
                            shape = RoundedCornerShape(optionCornerR),
                            colors = CardDefaults.cardColors(
                                // QA-R4 — multiply alpha (not overwrite).
                                // Old `.copy(alpha = bgOpacity)` turned a base
                                // alpha-0.15 white into alpha-1.0 white when
                                // bgOpacity was 1.0 (the default), producing a
                                // fully opaque card. Multiplying preserves the
                                // authored translucency: base 0.15 × 1.0 = 0.15.
                                containerColor = (if (isSelected) optSelBg else optUnselBg).let { c ->
                                    c.copy(alpha = c.alpha * bgOpacity)
                                },
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                if (isSelected && showBorderHighlight) selectedBorderW else unselectedBorderW,
                                if (isSelected) optSelBorder else optUnselBorder,
                            ),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (showRadio && radioOnLeft) {
                                    SelectRadioIndicator(isSelected = isSelected, isMulti = isMulti, fillCol = checkmarkCol, radioFill = radioFill)
                                    Spacer(Modifier.width(8.dp))
                                }
                                // Per-option image (with optional selected/unselected variants).
                                option.resolvedImageURL(isSelected)?.takeIf { it.isNotEmpty() }?.let { url ->
                                    Box(modifier = Modifier.size((optImgSizeRaw ?: 32f).dp).clip(when (option.image_shape) { "rounded" -> RoundedCornerShape(12.dp); "square" -> RoundedCornerShape(0.dp); else -> CircleShape })) {
                                        ai.appdna.sdk.core.NetworkImage(
                                            url = url,
                                            modifier = Modifier.fillMaxSize(),
                                            // Select v2 — option_image_scale (cover=Crop, contain/fit=Fit).
                                            contentScale = optionImageContentScale,
                                        )
                                        // EPIC-1 — image overlay tint; selected uses selected_image_overlay_*
                                        // (falls back to base). Parity with iOS imageWithOverlay (FormInputBlockViews).
                                        (if (isSelected) (option.selected_image_overlay_color ?: option.image_overlay_color) else option.image_overlay_color)
                                            ?.takeIf { it.isNotBlank() }?.let { ov ->
                                                val ovA = ((if (isSelected) (option.selected_image_overlay_opacity ?: option.image_overlay_opacity) else option.image_overlay_opacity) ?: 0.3).toFloat()
                                                Box(Modifier.matchParentSize().background(StyleEngine.parseColor(ov).copy(alpha = ovA)))
                                            }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                }
                                option.icon?.takeIf { it.isNotEmpty() }?.let { icon ->
                                    Text(text = icon)
                                    Spacer(Modifier.width(8.dp))
                                }
                                // SPEC-419 D7 — leading_text (renders after image+icon, contract order)
                                option.leading_text?.takeIf { it.isNotBlank() }?.let { lt ->
                                    Text(
                                        text = lt,
                                        fontSize = 14.sp,
                                        color = optTitleColor,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = optionFontFamily,
                                        modifier = Modifier.testTag("option.$oi.leading_text"),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    // EPIC-1 — per-option center alignment (was always start-aligned).
                                    // Mirrors iOS FormInputBlockViews.swift:601 (VStack .center/.leading).
                                    horizontalAlignment = if (option.text_alignment == "center") Alignment.CenterHorizontally else Alignment.Start,
                                ) {
                                    Text(
                                        text = option.label,
                                        modifier = Modifier.testTag("option.$oi.title"),
                                        // SPEC-419 pass-15 #36 — fall back to block-level default (not hardcoded 14).
                                        fontSize = (option.title_font_size?.toFloat() ?: defaultTitleSize).sp,
                                        color = optTitleColor,
                                        // Select v2 — option_font_family + option_text_wrap (wrap fully vs single-line truncate).
                                        fontFamily = optionFontFamily,
                                        maxLines = if (optionTextWrap) Int.MAX_VALUE else 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        fontWeight = option.title_font_weight?.let { wStr ->
                                            ai.appdna.sdk.core.FontResolver.fontWeight(wStr.toIntOrNull() ?: when (wStr.lowercase()) {
                                                "thin" -> 100; "extralight", "ultralight" -> 200
                                                "light" -> 300; "normal", "regular" -> 400
                                                "medium" -> 500; "semibold" -> 600
                                                "bold" -> 700; "extrabold", "heavy" -> 800
                                                "black" -> 900; else -> 400
                                            })
                                        } ?: FontWeight.Normal,
                                    )
                                    option.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                                        // QA-R14 — subtitle default color
                                        // = option text color × 0.65 alpha,
                                        // mirroring iOS `FormInputBlockViews.swift:
                                        // 518-519` `textCol.opacity(0.65)`. Was
                                        // `MaterialTheme.colorScheme.onSurface ×
                                        // 0.6` which on dark gradient backgrounds
                                        // resolved to near-black-on-dark (low
                                        // contrast) because M3 default `onSurface`
                                        // adapts to the host's color scheme, not
                                        // the step's authored background.
                                        Text(
                                            text = subtitle,
                                            modifier = Modifier.testTag("option.$oi.subtitle"),
                                            // SPEC-419 pass-15 #36 — fall back to block-level default (not hardcoded 12).
                                            fontSize = (option.subtitle_font_size?.toFloat() ?: defaultSubtitleSize).sp,
                                            color = optSubtitleColor,
                                            // Select v2 — option_font_family + option_text_wrap.
                                            fontFamily = optionFontFamily,
                                            maxLines = if (optionTextWrap) Int.MAX_VALUE else 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                // SPEC-419 D7 — trailing_text (after title/subtitle column, before the radio)
                                option.trailing_text?.takeIf { it.isNotBlank() }?.let { tt ->
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = tt,
                                        fontSize = 12.sp,
                                        color = optSubtitleColor,
                                        fontFamily = optionFontFamily,
                                        modifier = Modifier.testTag("option.$oi.trailing_text"),
                                    )
                                }
                                if (showRadio && !radioOnLeft) {
                                    Spacer(Modifier.width(8.dp))
                                    SelectRadioIndicator(isSelected = isSelected, isMulti = isMulti, fillCol = checkmarkCol, radioFill = radioFill)
                                }
                            }
                        }
                        // SPEC-419 D5 — per-option badge straddling the card top border (BoxScope child,
                        // half above the top edge; inset 12dp from the trailing/leading edge).
                        option.badge?.text?.takeIf { it.isNotBlank() }?.let { bt ->
                            // SPEC-419 pass-13 — honor the full 4-corner + center badge_position
                            // set (was contains("leading") only → bottom_* badges pinned to top).
                            // Mirrors iOS badgeAlignment/badgeOffsetX (FormInputBlockViews.swift:842).
                            val badgePos = option.badge?.position ?: ""
                            val badgeAlign = when (badgePos) {
                                "top_leading" -> Alignment.TopStart
                                "bottom_leading" -> Alignment.BottomStart
                                "bottom_trailing" -> Alignment.BottomEnd
                                "leading" -> Alignment.CenterStart
                                "trailing" -> Alignment.CenterEnd
                                else -> Alignment.TopEnd
                            }
                            val badgeOffsetX = when (badgePos) {
                                "top_leading", "bottom_leading", "leading" -> 12.dp
                                else -> (-12).dp
                            }
                            Box(
                                modifier = Modifier
                                    .align(badgeAlign)
                                    .offset(x = badgeOffsetX, y = (-9).dp)
                                    .testTag("option.$oi.badge")
                                    .background(
                                        option.badge?.bg_color?.let { StyleEngine.parseColor(it) } ?: Color(0xFF22C55E),
                                        RoundedCornerShape(999.dp),
                                    )
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = bt,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = option.badge?.text_color?.let { StyleEngine.parseColor(it) } ?: Color.White,
                                )
                            }
                        }
                        }
                        // SPEC-419 pass-24 — optional divider between stacked options (parity with list).
                        if (showItemSeparators && oi < options.lastIndex) {
                            Box(modifier = Modifier.fillMaxWidth().height(stackedSepThickness).background(stackedSepColor))
                        }
                    }
                }
            }
            "grid" -> {
                // Grid: N-column layout. Hotfix-1.0.33 — read `grid_columns` from
                // field_config (default 2). Per-option color reads now honored.
                val gridCols = ((cfg?.get("grid_columns") as? Number)?.toInt() ?: 2).coerceIn(1, 6)
                // SPEC-419 pass-13 — honor option_height (or size) as a cell min-height,
                // mirroring iOS gridSelectView optionHeight (FormInputBlockViews.swift:1026).
                val gridMinHeight = ((cfg?.get("option_height") as? Number)?.toFloat()
                    ?: (cfg?.get("size") as? Number)?.toFloat())
                val chunked = options.chunked(gridCols)
                Column(verticalArrangement = Arrangement.spacedBy(optionSpacingDp)) {
                    chunked.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(optionSpacingDp),
                        ) {
                            row.forEach { option ->
                                val isSelected = isOptionSelected(option.value)
                                val optUnselBg = option.bg_color?.let { StyleEngine.parseColor(it) } ?: unselectedBg
                                val optSelBg = option.selected_bg_color?.let { StyleEngine.parseColor(it) } ?: selectedBg
                                val optUnselBorder = option.border_color?.let { StyleEngine.parseColor(it) } ?: unselectedBorder
                                val optSelBorder = option.selected_border_color?.let { StyleEngine.parseColor(it) } ?: selectedBorder
                                val optSelText = option.selected_text_color?.let { StyleEngine.parseColor(it) } ?: selectedTextCol
                                val optTitleColor = if (isSelected) optSelText else (option.title_color?.let { StyleEngine.parseColor(it) } ?: textCol)
                                // EPIC-1 — resolve cell alignment: per-option override → block grid_cell_alignment → center.
                                // Mirrors iOS gridSelectView cellAlignmentKey. Was hardcoded CenterHorizontally / TextAlign.Center.
                                val cellAlignKey = option.cell_alignment ?: (cfg?.get("grid_cell_alignment") as? String) ?: "center"
                                val cellHAlign = when (cellAlignKey) {
                                    "leading", "left" -> Alignment.Start
                                    "trailing", "right" -> Alignment.End
                                    else -> Alignment.CenterHorizontally
                                }
                                val cellTextAlign = when (cellAlignKey) {
                                    "leading", "left" -> TextAlign.Start
                                    "trailing", "right" -> TextAlign.End
                                    else -> TextAlign.Center
                                }
                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .then(if (gridMinHeight != null) Modifier.heightIn(min = gridMinHeight.dp) else Modifier)
                                        .clickable { pickOption(option.value) },
                                    shape = RoundedCornerShape(cornerR),
                                    colors = CardDefaults.cardColors(
                                        // QA-R4 — multiply alpha, same fix as stacked branch.
                                        containerColor = (if (isSelected) optSelBg else optUnselBg).let { c ->
                                            c.copy(alpha = c.alpha * bgOpacity)
                                        },
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(
                                        if (isSelected) selectedBorderW else unselectedBorderW,
                                        if (isSelected) optSelBorder else optUnselBorder,
                                    ),
                                ) {
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                                            horizontalAlignment = cellHAlign,
                                        ) {
                                            option.resolvedImageURL(isSelected)?.takeIf { it.isNotEmpty() }?.let { url ->
                                                Box(modifier = Modifier.size((optImgSizeRaw ?: 40f).dp).clip(when (option.image_shape) { "rounded" -> RoundedCornerShape(12.dp); "square" -> RoundedCornerShape(0.dp); else -> CircleShape })) {
                                                    ai.appdna.sdk.core.NetworkImage(
                                                        url = url,
                                                        modifier = Modifier.fillMaxSize(),
                                                        contentScale = ContentScale.Crop,
                                                    )
                                                    // EPIC-1 — image overlay tint; selected uses selected_image_overlay_* (falls back to base).
                                                    (if (isSelected) (option.selected_image_overlay_color ?: option.image_overlay_color) else option.image_overlay_color)
                                                        ?.takeIf { it.isNotBlank() }?.let { ov ->
                                                            val ovA = ((if (isSelected) (option.selected_image_overlay_opacity ?: option.image_overlay_opacity) else option.image_overlay_opacity) ?: 0.3).toFloat()
                                                            Box(Modifier.matchParentSize().background(StyleEngine.parseColor(ov).copy(alpha = ovA)))
                                                        }
                                                }
                                                Spacer(Modifier.height(4.dp))
                                            }
                                            Text(
                                                text = option.label,
                                                // Grid honors block-level title default (0.85 factor, parity with iOS gridSelectView + preview).
                                                fontSize = (option.title_font_size?.toFloat() ?: defaultTitleSize * 0.85f).sp,
                                                color = optTitleColor,
                                                fontWeight = option.title_font_weight?.let { wStr ->
                                                    ai.appdna.sdk.core.FontResolver.fontWeight(wStr.toIntOrNull() ?: when (wStr.lowercase()) {
                                                        "thin" -> 100; "extralight", "ultralight" -> 200
                                                        "light" -> 300; "normal", "regular" -> 400
                                                        "medium" -> 500; "semibold" -> 600
                                                        "bold" -> 700; "extrabold", "heavy" -> 800
                                                        "black" -> 900; else -> 400
                                                    })
                                                } ?: FontWeight.Normal,
                                                textAlign = cellTextAlign,
                                            )
                                            option.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                                                Text(
                                                    text = subtitle,
                                                    fontSize = (option.subtitle_font_size?.toFloat() ?: defaultSubtitleSize).sp,
                                                    color = option.subtitle_color?.let { StyleEngine.parseColor(it) }
                                                        ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                    textAlign = cellTextAlign,
                                                )
                                            }
                                        }
                                        // EPIC-1 / SPEC-419 pass-13 — toggle-icon overlay honors
                                        // show_toggle_icon, toggle_icon_position, toggle_icon_size,
                                        // and selected/unselected bg+fg colors (was hardcoded TopEnd,
                                        // 12sp, no bg). Mirrors iOS gridSelectView
                                        // (FormInputBlockViews.swift:1003-1149).
                                        val defSelIcon = cfg?.get("selected_icon") as? String
                                        val defUnselIcon = cfg?.get("unselected_icon") as? String
                                        val showToggleIcon = (cfg?.get("show_toggle_icon") as? Boolean) ?: (defSelIcon != null)
                                        if (showToggleIcon) {
                                            val toggleAlign = when (cfg?.get("toggle_icon_position") as? String) {
                                                "top_leading" -> Alignment.TopStart
                                                "bottom_trailing" -> Alignment.BottomEnd
                                                "bottom_leading" -> Alignment.BottomStart
                                                else -> Alignment.TopEnd
                                            }
                                            val toggleSizeF = (cfg?.get("toggle_icon_size") as? Number)?.toFloat() ?: 20f
                                            val badgeIcon = if (isSelected) (option.selected_icon ?: defSelIcon ?: "✓")
                                                            else (option.unselected_icon ?: defUnselIcon ?: "+")
                                            val badgeFg = if (isSelected)
                                                ((cfg?.get("toggle_icon_selected_fg_color") as? String)?.let { StyleEngine.parseColor(it) } ?: optSelText)
                                            else ((cfg?.get("toggle_icon_unselected_fg_color") as? String)?.let { StyleEngine.parseColor(it) } ?: textCol.copy(alpha = 0.5f))
                                            val badgeBg = if (isSelected)
                                                ((cfg?.get("toggle_icon_selected_bg_color") as? String)?.let { StyleEngine.parseColor(it) } ?: fillCol.copy(alpha = 0.2f))
                                            else ((cfg?.get("toggle_icon_unselected_bg_color") as? String)?.let { StyleEngine.parseColor(it) } ?: Color.Transparent)
                                            Box(
                                                modifier = Modifier
                                                    .align(toggleAlign)
                                                    .padding(6.dp)
                                                    .size(toggleSizeF.dp)
                                                    .background(badgeBg, CircleShape),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    text = badgeIcon,
                                                    fontSize = (toggleSizeF * 0.5f).sp,
                                                    color = badgeFg,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            // Fill empty slots in last row to keep grid alignment
                            repeat(gridCols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    // EPIC-1 / SPEC-419 pass-13 — tooltip below the grid (was ignored on
                    // Android). Mirrors iOS gridSelectView (FormInputBlockViews.swift:1178-1188).
                    (cfg?.get("tooltip_text") as? String)?.takeIf { it.isNotBlank() }?.let { tip ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text("ⓘ", fontSize = 12.sp, color = textCol.copy(alpha = 0.5f))
                            Text(tip, fontSize = 12.sp, color = textCol.copy(alpha = 0.5f))
                        }
                    }
                }
            }
            "image_tiles" -> {
                // EPIC-1 — tall tiles: image fills the tile, label overlaid at the bottom over a
                // dark scrim; selected = accent border. N-column grid (grid_columns, default 2).
                val tileCols = ((cfg?.get("grid_columns") as? Number)?.toInt() ?: 2).coerceIn(1, 4)
                val tileHeight = ((cfg?.get("tile_height") as? Number)?.toFloat() ?: 140f).dp
                // SPEC-419 pass-24 — tile_aspect_ratio ("W:H", console Select 1:1/4:3/16:9/3:4)
                // sizes the tile by its (weight-1f) width × the ratio. When set it overrides the
                // fixed tile_height; falls back to tile_height when unset.
                val tileAspect = (cfg?.get("tile_aspect_ratio") as? String)?.let { s ->
                    val parts = s.split(":")
                    val w = parts.getOrNull(0)?.toFloatOrNull()
                    val h = parts.getOrNull(1)?.toFloatOrNull()
                    if (parts.size == 2 && w != null && h != null && w > 0f && h > 0f) w / h else null
                }
                Column(verticalArrangement = Arrangement.spacedBy(optionSpacingDp)) {
                    options.chunked(tileCols).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(optionSpacingDp),
                        ) {
                            row.forEach { option ->
                                val isSelected = isOptionSelected(option.value)
                                val optSelBorder = option.selected_border_color?.let { StyleEngine.parseColor(it) } ?: selectedBorder
                                val optUnselBorder = option.border_color?.let { StyleEngine.parseColor(it) } ?: unselectedBorder
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .then(if (tileAspect != null) Modifier.aspectRatio(tileAspect) else Modifier.height(tileHeight))
                                        .clip(RoundedCornerShape(cornerR))
                                        .selectable(
                                            selected = isSelected,
                                            role = androidx.compose.ui.semantics.Role.RadioButton,
                                            onClick = { pickOption(option.value) },
                                        )
                                        .border(
                                            androidx.compose.foundation.BorderStroke(
                                                if (isSelected) selectedBorderW else unselectedBorderW,
                                                if (isSelected) optSelBorder else optUnselBorder,
                                            ),
                                            RoundedCornerShape(cornerR),
                                        ),
                                ) {
                                    option.resolvedImageURL(isSelected)?.takeIf { it.isNotEmpty() }?.let { url ->
                                        ai.appdna.sdk.core.NetworkImage(
                                            url = url,
                                            modifier = Modifier.matchParentSize(),
                                            contentScale = ContentScale.Crop,
                                        )
                                    }
                                    (if (isSelected) (option.selected_image_overlay_color ?: option.image_overlay_color) else option.image_overlay_color)
                                        ?.takeIf { it.isNotBlank() }?.let { ov ->
                                            val ovA = ((if (isSelected) (option.selected_image_overlay_opacity ?: option.image_overlay_opacity) else option.image_overlay_opacity) ?: 0.3).toFloat()
                                            Box(Modifier.matchParentSize().background(StyleEngine.parseColor(ov).copy(alpha = ovA)))
                                        }
                                    // Bottom scrim so the label stays legible over any image.
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .background(
                                                Brush.verticalGradient(
                                                    0.45f to Color.Transparent,
                                                    1f to Color.Black.copy(alpha = 0.65f),
                                                ),
                                            ),
                                    )
                                    Column(
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                    ) {
                                        Text(
                                            text = option.label,
                                            color = Color.White,
                                            fontSize = (option.title_font_size ?: 15.0).sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        option.subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
                                            Text(text = sub, color = Color.White.copy(alpha = 0.85f), fontSize = (option.subtitle_font_size ?: 12.0).sp)
                                        }
                                    }
                                }
                            }
                            // Keep tiles equal width when the last row is short.
                            repeat(tileCols - row.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            "bubble" -> {
                // EPIC-1 — chips/pills that wrap (FlowRow). Selected = filled accent; unselected =
                // bordered/transparent. Common for interests/tags multi-select.
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(optionSpacingDp),
                    verticalArrangement = Arrangement.spacedBy(optionSpacingDp),
                ) {
                    options.forEach { option ->
                        val isSelected = isOptionSelected(option.value)
                        val chipBg = if (isSelected) (option.selected_bg_color?.let { StyleEngine.parseColor(it) } ?: fillCol)
                            else (option.bg_color?.let { StyleEngine.parseColor(it) } ?: Color.Transparent)
                        val chipBorder = if (isSelected) (option.selected_border_color?.let { StyleEngine.parseColor(it) } ?: fillCol)
                            else (option.border_color?.let { StyleEngine.parseColor(it) } ?: unselectedBorder)
                        val chipText = if (isSelected) selectedTextCol else textCol
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .background(chipBg)
                                .border(
                                    androidx.compose.foundation.BorderStroke(
                                        if (isSelected) selectedBorderW else unselectedBorderW,
                                        chipBorder,
                                    ),
                                    RoundedCornerShape(percent = 50),
                                )
                                .selectable(
                                    selected = isSelected,
                                    role = androidx.compose.ui.semantics.Role.RadioButton,
                                    onClick = { pickOption(option.value) },
                                )
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                        ) {
                            Text(
                                text = option.label,
                                color = chipText,
                                fontSize = (option.title_font_size ?: 14.0).sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
            "list" -> {
                // EPIC-1 — borderless list: full-width rows + a hairline divider between items
                // (settings-list look). Honors per-option bg + separator_color + selected tint/check.
                val separatorColor = (cfg?.get("separator_color") as? String)?.let { StyleEngine.parseColor(it) }
                    ?: StyleEngine.parseColor("#D1D5DB")
                // SPEC-419 pass-24 — separator_thickness (console Slider 0–4, default 1) drives the
                // divider height; thickness was previously hardcoded to 1.dp.
                val separatorThickness = ((cfg?.get("separator_thickness") as? Number)?.toDouble() ?: 1.0).dp
                Column(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { oi, option ->
                        val isSelected = isOptionSelected(option.value)
                        val rowBg = if (isSelected) (option.selected_bg_color?.let { StyleEngine.parseColor(it) } ?: selectedBg)
                            else (option.bg_color?.let { StyleEngine.parseColor(it) } ?: Color.Transparent)
                        val rowTitle = if (isSelected) (option.selected_text_color?.let { StyleEngine.parseColor(it) } ?: selectedTextCol)
                            else (option.title_color?.let { StyleEngine.parseColor(it) } ?: textCol)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(rowBg)
                                .selectable(
                                    selected = isSelected,
                                    role = androidx.compose.ui.semantics.Role.RadioButton,
                                    onClick = { pickOption(option.value) },
                                )
                                .padding(horizontal = 4.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = option.label, color = rowTitle, fontSize = (option.title_font_size ?: 16.0).sp)
                                option.subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
                                    Text(text = sub, color = rowTitle, fontSize = (option.subtitle_font_size ?: 13.0).sp)
                                }
                            }
                            if (isSelected) {
                                Text(text = "✓", color = fillCol, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (oi < options.lastIndex) {
                            Box(modifier = Modifier.fillMaxWidth().height(separatorThickness).background(separatorColor))
                        }
                    }
                }
            }
            else -> {
                // "dropdown" (default): Spinner/dropdown
                var expanded by remember { mutableStateOf(false) }
                var selectedLabel by remember { mutableStateOf(
                    options.firstOrNull { it.value == selectedValue }?.label ?: block.field_placeholder ?: "Select..."
                ) }

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB"),
                        ),
                        // SPEC-401-A R44 — theme-adaptive content color (was Color.DarkGray
                // — invisible on dark surface in dark mode).
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(text = selectedLabel, fontSize = 14.sp)
                            Text(text = "\u25BC", fontSize = 12.sp)
                        }
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        options.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // Gap 7: Option image_url in dropdown
                                        option.image_url?.let { url ->
                                            if (url.isNotEmpty()) {
                                                ai.appdna.sdk.core.NetworkImage(
                                                    url = url,
                                                    modifier = Modifier.size(24.dp).clip(CircleShape),
                                                    contentScale = ContentScale.Crop,
                                                )
                                                Spacer(Modifier.width(8.dp))
                                            }
                                        }
                                        Text(option.label)
                                    }
                                },
                                onClick = {
                                    selectedLabel = option.label
                                    selectedValue = option.value
                                    inputValues[fieldId] = option.value
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        }

        // SPEC-401-A R68 (Lens A P2) — tooltip caption below select grid /
        // stacked / dropdown matching iOS FormInputBlockViews.swift:769-770
        // + 926-937. Reads field_config.tooltip_text + tooltip_icon.
        // Renders 12sp caption with optional 12dp leading icon at 50% alpha.
        // Audit pass-8 — scope the tooltip caption to displayStyle=="grid" to
        // match iOS, which renders it only inside gridSelectView
        // (FormInputBlockViews.swift:1180/1349), and the editor, which exposes
        // tooltip_text only in the grid section (StepContentEditor.tsx:6941).
        // Previously this rendered for every display style (dropdown/stacked/
        // list/bubble/image_tiles), so a tooltip on a non-grid select showed on
        // Android but not iOS.
        val tooltipText = block.field_config?.get("tooltip_text") as? String
        val tooltipIconRef = block.field_config?.get("tooltip_icon") as? String
        if (!tooltipText.isNullOrBlank() && displayStyle == "grid") {
            val captionColor = StyleEngine.parseColor(block.field_style?.text_color ?: "#1A1A1A").copy(alpha = 0.5f)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                if (!tooltipIconRef.isNullOrBlank()) {
                    val iconRef = ai.appdna.sdk.core.resolveIcon(tooltipIconRef)
                    if (iconRef != null) {
                        ai.appdna.sdk.core.IconView(ref = iconRef, defaultSize = 12f)
                    }
                }
                Text(
                    text = tooltipText,
                    fontSize = 12.sp,
                    color = captionColor,
                )
            }
        }
    }
}

/** Slider input for single numeric value. */
@Composable
private fun FormInputSliderBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    // SPEC-419 pass-21 — editor authors min/max/step into field_config for the
    // slider (StepContentEditor :5411/:5415/:5419); top-level keys never populated.
    // Top-level first (back-compat), then field_config, then literal default.
    // SPEC-419 pass-22 — field_config min/max now authorable; clamp so Compose Slider valueRange stays valid (min<max).
    val rawMinV = (block.min_value ?: (block.field_config?.get("min_value") as? Number)?.toDouble() ?: 0.0).toFloat()
    val rawMaxV = (block.max_value_picker ?: (block.field_config?.get("max_value") as? Number)?.toDouble() ?: 100.0).toFloat()
    val minVal = minOf(rawMinV, rawMaxV)
    // SPEC-419 pass-39 — widen by the authored step (match iOS minVal+stepVal), not a hardcoded 1f, so a sub-unit decimal-step range isn't over-widened past the authored max.
    val maxVal = maxOf(rawMaxV, minVal + ((block.step_value ?: (block.field_config?.get("step") as? Number)?.toDouble() ?: 1.0).toFloat().let { if (it > 0f) it else 1f }))
    // SPEC-401-A R61 (Lens A N2, P1) — default step_value=1.0 matches iOS
    // FormInputBlockViews.swift:953 `block.step_value ?? 1`. Was 0.0
    // (continuous), out of parity with iOS integer-snap default; also made
    // the R59 step-tick haptic never fire because bucket-change gate was
    // `stepVal > 0f`.
    val stepVal = (block.step_value ?: (block.field_config?.get("step") as? Number)?.toDouble() ?: 1.0).toFloat()
    val unitStr = block.unit ?: ""
    val fillCol = StyleEngine.parseColor(block.field_style?.fill_color ?: block.active_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    // SPEC-401-A R55 (Lens C R55 #3, P3) — inactive track color from
    // field_style.track_color (or block.track_color) per iOS
    // FormInputBlockViews.swift slider track. Was Material default purple.
    val trackCol = StyleEngine.parseColor(block.field_style?.track_color ?: block.track_color ?: "#E5E7EB")
    // OB-6 audit follow-up — restore saved value on back nav.
    var value by remember {
        mutableStateOf(
            (inputValues[fieldId] as? Number)?.toFloat()
                ?: (block.default_picker_value ?: (block.field_config?.get("default_value") as? Number)?.toDouble() ?: minVal.toDouble()).toFloat()
        )
    }
    // SPEC-401-A R55 (Lens C R55 #2, P2) — discrete step gradations matching
    // iOS `Slider(value:in:step:)` at FormInputBlockViews.swift. With step=0.5
    // on 0–10 range Android was continuous; iOS snaps to 21 stops.
    val stepCount = if (stepVal > 0f) {
        ((maxVal - minVal) / stepVal - 1).toInt().coerceAtLeast(0)
    } else 0
    // SPEC-401-A R55 (Lens C R55 #2 cont., P2) — value formatting honors
    // fractional step so 7.5 doesn't display as 8. Matches iOS
    // String(format: "%.1f", value) when step < 1.
    // Round-30 — decimal for ANY fractional step (not just step<1), so a step of 2.5 shows "2.5"
    // on both platforms instead of iOS truncating to "2" / Android rounding to "3". Whole-number
    // steps → rounded int (roundToInt, matching iOS Int(value.rounded()); avoids FP-drift off-by-one).
    // Locale.US (POSIX) like iOS String(format:); default JVM locale renders "2,5"/Arabic-Indic "٢٫٥".
    val displayValue = if (stepVal > 0f && stepVal % 1f != 0f) {
        "%.1f".format(java.util.Locale.US, value)
    } else {
        "${value.roundToInt()}"
    }
    // SPEC-401-A R57 (Lens A R57 #3, P2) — honor field_config.show_value=false
    // matching iOS FormInputBlockViews.swift:954 (`block.field_config?
    // ["show_value"]?.value as? Bool ?? true`). Was always rendered on Android.
    val showValue = (block.field_config?.get("show_value") as? Boolean) ?: true

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FormFieldLabel(block)
            if (showValue) {
                Text(
                    text = "$displayValue$unitStr",
                    // SPEC-401-A R57 (Lens A R57 #2, P2) — 15sp matches iOS
                    // .subheadline.weight(.semibold) at FormInputBlockViews
                    // .swift:966. Was 14sp.
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = fillCol,
                )
            }
        }

        // SPEC-401-A R59 (Lens C P2 #1) — discrete-step haptic tick on each
        // step crossing matching iOS native `Slider(value:in:step:)` which
        // auto-emits UISelectionFeedbackGenerator. Compose Slider has no
        // equivalent so we gate on bucket-index change.
        val sliderHapticView = androidx.compose.ui.platform.LocalView.current
        val sliderLastBucket = remember(fieldId) {
            mutableStateOf(if (stepVal > 0f) ((value - minVal) / stepVal).toInt() else 0)
        }
        Slider(
            value = value,
            onValueChange = { v ->
                value = v
                inputValues[fieldId] = v.toDouble()
                if (stepVal > 0f && maxVal > minVal) {
                    val bucket = ((v - minVal) / stepVal).toInt()
                    if (bucket != sliderLastBucket.value) {
                        sliderLastBucket.value = bucket
                        ai.appdna.sdk.core.HapticEngine.trigger(sliderHapticView, ai.appdna.sdk.core.HapticType.SELECTION)
                    }
                }
            },
            valueRange = minVal..maxVal,
            steps = stepCount,
            colors = SliderDefaults.colors(
                // Mrozu QA: honor authored thumb_color; else default to a WHITE thumb
                // to match iOS FormInputSliderBlock + the console preview (previously
                // fell back to the accent fillCol, diverging from both).
                thumbColor = block.field_style?.thumb_color?.let { StyleEngine.parseColor(it) } ?: StyleEngine.parseColor("#FFFFFF"),
                activeTrackColor = fillCol,
                inactiveTrackColor = trackCol,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    LaunchedEffect(Unit) {
        inputValues[fieldId] = value.toDouble()
    }
}

/** Toggle (switch) input. */
@Composable
private fun FormInputToggleBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    val onColor = StyleEngine.parseColor(block.field_style?.toggle_on_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    // Mrozu QA (2026-08-03): toggle_off_color + thumb_color were decoded but only on-color applied.
    // Honor all four Switch colors (parity with iOS custom toggle).
    val offColor = StyleEngine.parseColor(block.field_style?.toggle_off_color ?: "#E5E5EA")
    val thumbColor = StyleEngine.parseColor(block.field_style?.thumb_color ?: "#FFFFFF")
    val label = block.field_label ?: block.toggle_label ?: ""
    // OB-6 audit follow-up — restore saved value on back nav.
    // Audit pass-8 — the input_toggle editor writes 'Default On' to
    // field_config.default_value (StepContentEditor.tsx:6950) and the preview
    // reads cfg.default_value (OnboardingStepPreview.tsx:3721), but this init read
    // only block.toggle_default (never field_config.default_value), so the
    // default-on setting was silently dropped. Fall back to field_config.default_value.
    var checked by remember {
        mutableStateOf(
            (inputValues[fieldId] as? Boolean)
                ?: (block.field_config?.get("default_value") as? Boolean)
                ?: (block.toggle_default ?: false)
        )
    }

    // SPEC-401-A R60 (Lens C P2 #1) — wrap label+Switch in `toggleable` so
    // TalkBack treats the row as a single switch element matching iOS
    // `Toggle("label", isOn:)` (FormInputBlockViews.swift). Switch becomes
    // presentational (onCheckedChange = null); the Row owns the click +
    // a11y semantics. Without this users had to swipe twice through label
    // then "Switch, On".
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = androidx.compose.ui.semantics.Role.Switch,
                onValueChange = {
                    checked = it
                    inputValues[fieldId] = it
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // SPEC-401-A R57 (Lens A R57 #5, P2) — 15sp matches iOS .subheadline
        // (FormInputBlockViews.swift:999). Was 14sp.
        Text(text = label, modifier = Modifier.weight(1f), fontSize = 15.sp)
        Switch(
            checked = checked,
            // SPEC-401-A R60 (Lens C P2 #1) — null handler so the Row's
            // toggleable owns the click + a11y semantics; Switch is a
            // presentational visual.
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = onColor,
                uncheckedTrackColor = offColor,
                checkedThumbColor = thumbColor,
                uncheckedThumbColor = thumbColor,
            ),
        )
    }

    LaunchedEffect(Unit) {
        inputValues[fieldId] = checked
    }
}

/**
 * Mrozu QA (2026-08-04, Flo s1) — standalone consent / agreement element: a tappable checkbox + a
 * rich label whose `[terms](url)` / `[privacy](url)` markdown links open a browser via [URLSafety].
 * Persists a Boolean to `inputValues[field_id]`; when `field_required` is set, [RequiredFieldGate]
 * gates the CTA until checked. All authoring config travels through `field_config` (byte-identical
 * keys with the iOS `AgreementBlock` and the console editor). Mirrors [FormInputToggleBlock] +
 * [RichTextBlock] link handling.
 */
@Composable
private fun AgreementBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    val checkboxColor = StyleEngine.parseColor((block.field_config?.get("checkbox_color") as? String) ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    val borderColor = StyleEngine.parseColor((block.field_config?.get("checkbox_border_color") as? String) ?: "#C7C7CC")
    val checkmarkColor = StyleEngine.parseColor((block.field_config?.get("checkmark_color") as? String) ?: "#FFFFFF")
    val textColor = StyleEngine.parseColor((block.field_config?.get("text_color") as? String) ?: "#8E8E93")
    val linkColor = StyleEngine.parseColor((block.field_config?.get("link_color") as? String) ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    val agreementText = (block.field_config?.get("agreement_text") as? String)
        ?: block.text
        ?: "I agree to the [Terms of Service](https://example.com/terms) and [Privacy Policy](https://example.com/privacy)."
    val context = LocalContext.current

    var checked by remember {
        mutableStateOf((inputValues[fieldId] as? Boolean) ?: ((block.field_config?.get("default_checked") as? Boolean) ?: false))
    }

    val baseTextStyle = TextStyle(fontSize = 13.sp, color = textColor)
    val annotatedString = parseMarkdownToAnnotatedString(agreementText, baseTextStyle, linkColor)

    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .padding(top = 1.dp, end = 10.dp)
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (checked) checkboxColor else Color.Transparent)
                .border(1.5.dp, if (checked) checkboxColor else borderColor, RoundedCornerShape(6.dp))
                .toggleable(
                    value = checked,
                    role = androidx.compose.ui.semantics.Role.Checkbox,
                    onValueChange = {
                        checked = it
                        inputValues[fieldId] = it
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = checkmarkColor,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        ClickableText(
            text = annotatedString,
            style = baseTextStyle,
            modifier = Modifier.weight(1f),
            onClick = { offset ->
                annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                    .firstOrNull()?.let { annotation ->
                        ai.appdna.sdk.core.URLSafety.sanitized(annotation.item, context)?.let { uri ->
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            } catch (_: Exception) {
                                // No browser — silently ignore
                            }
                        }
                    }
            },
        )
    }

    LaunchedEffect(Unit) {
        inputValues[fieldId] = checked
    }
}

/** Stepper input (increment/decrement). */
@Composable
private fun FormInputStepperBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    // SPEC-419 pass-21 — editor authors min/max/step into field_config for the
    // stepper (StepContentEditor :5388/:5392/:5396); top-level keys never populated.
    // SPEC-419 pass-22 — clamp the now-authorable range (min<max, step>0) to guard coerceIn.
    val rawStepI = (block.step_value ?: (block.field_config?.get("step") as? Number)?.toDouble() ?: 1.0).toInt()
    val stepVal = if (rawStepI > 0) rawStepI else 1
    val rawMinI = (block.min_value ?: (block.field_config?.get("min_value") as? Number)?.toDouble() ?: 0.0).toInt()
    val rawMaxI = (block.max_value_picker ?: (block.field_config?.get("max_value") as? Number)?.toDouble() ?: 100.0).toInt()
    val minVal = minOf(rawMinI, rawMaxI)
    val maxVal = maxOf(rawMaxI, minVal + stepVal)
    val unitStr = block.unit ?: ""
    // SPEC-401-A R57 (Lens C R57 #1, P2) — host View for haptic feedback on
    // +/- tap. Mirrors iOS UIStepper auto-emitting click haptic on every
    // increment/decrement (system behavior). Sibling FormStepComposable.kt
    // legacy-form path already fires LIGHT haptic on the same buttons —
    // this content-block path was missing it.
    val view = androidx.compose.ui.platform.LocalView.current
    // OB-6 audit follow-up — restore saved value on back nav.
    var value by remember {
        mutableStateOf(
            (inputValues[fieldId] as? Number)?.toInt()
                ?: (block.default_picker_value ?: (block.field_config?.get("default_value") as? Number)?.toDouble() ?: minVal.toDouble()).toInt()
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // SPEC-401-A R38 (Lens C #3) — a11y labels on +/- buttons.
            // Native iOS Stepper ships built-in "Decrement"/"Increment"
            // labels via VoiceOver; Compose OutlinedButton wrapping a "-"/
            // "+" Text glyph reads as just the punctuation character.
            OutlinedButton(
                onClick = {
                    if (value - stepVal >= minVal) {
                        value -= stepVal
                        inputValues[fieldId] = value
                        // SPEC-401-A R57 (Lens C R57 #1, P2) — LIGHT haptic
                        // mirrors iOS UIStepper system click haptic.
                        ai.appdna.sdk.core.HapticEngine.trigger(view, ai.appdna.sdk.core.HapticType.LIGHT)
                    }
                },
                modifier = Modifier
                    .size(40.dp)
                    .semantics { contentDescription = "Decrease" },
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("-", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "$value$unitStr",
                // SPEC-401-A R56→R57 (Lens A R56 #9, P3) — 17sp Medium matches iOS
                // .body.weight(.medium) (FormInputBlockViews.swift:1035). Was 18sp
                // SemiBold — 1pt larger, one weight notch heavier.
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            OutlinedButton(
                onClick = {
                    if (value + stepVal <= maxVal) {
                        value += stepVal
                        inputValues[fieldId] = value
                        // SPEC-401-A R57 (Lens C R57 #1, P2) — LIGHT haptic
                        // mirrors iOS UIStepper system click haptic.
                        ai.appdna.sdk.core.HapticEngine.trigger(view, ai.appdna.sdk.core.HapticType.LIGHT)
                    }
                },
                modifier = Modifier
                    .size(40.dp)
                    .semantics { contentDescription = "Increase" },
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    LaunchedEffect(Unit) {
        inputValues[fieldId] = value
    }
}

/** Segmented picker input. */
@Composable
private fun FormInputSegmentedBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    val options = block.field_options ?: emptyList()
    // OB-6 audit follow-up — restore saved value on back nav.
    var selectedValue by remember {
        mutableStateOf(
            (inputValues[fieldId] as? String) ?: (options.firstOrNull()?.value ?: "")
        )
    }
    val fillCol = StyleEngine.parseColor(block.field_style?.fill_color ?: block.active_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                // SPEC-401-A R45 — theme-adaptive segmented border (was Color.Gray).
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        ) {
            options.forEach { option ->
                val isSelected = selectedValue == option.value
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(if (isSelected) fillCol else Color.Transparent)
                        .clickable {
                            selectedValue = option.value
                            inputValues[fieldId] = option.value
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option.label,
                        // SPEC-401-A R38 (Lens C #5) — theme-aware unselected
                        // text. Was Color.DarkGray which is invisible against
                        // MaterialTheme.colorScheme.surface in dark mode.
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (selectedValue.isNotEmpty()) inputValues[fieldId] = selectedValue
    }
}

/** Star rating input (form variant). */
@Composable
private fun FormInputRatingBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    // Audit pass-8 — the input_rating editor (StepContentEditor.tsx:6963-6977)
    // writes max_stars/star_size/filled_color/empty_color/allow_half to
    // field_config, and the preview (OnboardingStepPreview.tsx:3765-3767) + iOS
    // (FormInputBlockViews.swift:1699-1706) read field_config FIRST. Android was
    // reading only the top-level keys (never populated for input_rating), so all
    // 5 authored rating settings were silently dropped. Read field_config first,
    // then the existing top-level fallbacks.
    val fc = block.field_config
    val maxStars = ((fc?.get("max_stars") as? Number)?.toInt() ?: block.max_stars ?: 5).coerceAtLeast(1)
    val starSize = ((fc?.get("star_size") as? Number)?.toDouble() ?: block.star_size ?: 32.0).sp
    // SPEC-401-A B2 P1 / R56 — DTO field-name + priority parity with iOS
    // (`filled_color ?? field_style.fill_color`, `empty_color ?? "#D1D5DB"`,
    // FormInputBlockViews.swift:1095-1096), now with field_config taking highest
    // precedence to match the editor/preview/iOS.
    val filledCol = StyleEngine.parseColor(
        (fc?.get("filled_color") as? String) ?: block.filled_color ?: block.active_rating_color ?: block.field_style?.fill_color ?: "#FBBF24"
    )
    val emptyCol = StyleEngine.parseColor(
        (fc?.get("empty_color") as? String) ?: block.empty_color ?: block.inactive_rating_color ?: "#D1D5DB"
    )
    val allowHalf = (fc?.get("allow_half") as? Boolean) ?: (block.allow_half == true)
    // SPEC-401-A — promote selectedRating to Double for half-star round-trip.
    // Mirrors iOS FormInputBlockViews.swift:1106 which already supports
    // half-stars when block.allow_half is true.
    var selectedRating by remember {
        mutableStateOf(
            (inputValues[fieldId] as? Number)?.toDouble() ?: (block.default_value ?: 0.0)
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 1..maxStars) {
                val starState: Double = when {
                    selectedRating >= i -> 1.0
                    allowHalf && selectedRating >= i - 0.5 -> 0.5
                    else -> 0.0
                }
                // SPEC-401-A — repeat-tap toggle for half-stars. Compose
                // `clickable` doesn't expose tap coordinates; the toggle
                // pattern (full → .5 → full) reaches every 0.5 step iOS
                // reaches via tap-location math, without `pointerInput`.
                Icon(
                    imageVector = when (starState) {
                        1.0 -> Icons.Filled.Star
                        0.5 -> Icons.Filled.StarHalf
                        else -> Icons.Outlined.Star
                    },
                    contentDescription = if (starState >= 1.0) "$i stars" else if (starState > 0.0) "${i - 0.5} stars" else "$i stars (empty)",
                    tint = if (starState > 0.0) filledCol else emptyCol,
                    modifier = Modifier
                        .size(starSize.value.dp)
                        .clickable {
                            val newRating: Double = if (allowHalf && selectedRating == i.toDouble()) {
                                (i - 0.5).coerceAtLeast(0.5)
                            } else {
                                i.toDouble()
                            }
                            selectedRating = if (allowHalf) newRating else newRating.toInt().toDouble()
                            inputValues[fieldId] = selectedRating
                        },
                )
            }
        }
    }
}

/** Range slider (dual-thumb) input. */
@Composable
private fun FormInputRangeSliderBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    // SPEC-419 pass-21 — editor authors min/max/step into field_config for the
    // range slider (StepContentEditor :5411/:5415/:5419); top-level keys never populated.
    // SPEC-419 pass-22 — clamp the now-authorable range so Compose Slider valueRange stays valid (min<max).
    val rawMinR = (block.min_value ?: (block.field_config?.get("min_value") as? Number)?.toDouble() ?: 0.0).toFloat()
    val rawMaxR = (block.max_value_picker ?: (block.field_config?.get("max_value") as? Number)?.toDouble() ?: 100.0).toFloat()
    val minVal = minOf(rawMinR, rawMaxR)
    val maxVal = maxOf(rawMaxR, minVal + 1f)
    // SPEC-401-A R61 (Lens A N2, P1) — default step_value=1.0 matches iOS
    // FormInputBlockViews.swift:953 `block.step_value ?? 1`. Was 0.0
    // (continuous), out of parity with iOS integer-snap default; also made
    // the R59 step-tick haptic never fire because bucket-change gate was
    // `stepVal > 0f`.
    val stepVal = (block.step_value ?: (block.field_config?.get("step") as? Number)?.toDouble() ?: 1.0).toFloat()
    val unitStr = block.unit ?: ""
    val fillCol = StyleEngine.parseColor(block.field_style?.fill_color ?: block.active_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    // SPEC-401-A R55 (Lens C R55 #3, P3) — inactive track color from
    // field_style.track_color (or block.track_color) per iOS.
    val trackCol = StyleEngine.parseColor(block.field_style?.track_color ?: block.track_color ?: "#E5E7EB")
    // OB-6 audit follow-up — restore saved range on back nav.
    // Saved range is stored as Map<"min","max"> under fieldId per the
    // write sites below (Slider onValueChange + LaunchedEffect).
    val savedMap = inputValues[fieldId] as? Map<*, *>
    var lowValue by remember {
        mutableStateOf((savedMap?.get("min") as? Number)?.toFloat() ?: minVal)
    }
    var highValue by remember {
        mutableStateOf((savedMap?.get("max") as? Number)?.toFloat() ?: maxVal)
    }
    // SPEC-401-A R55 (Lens C R55 #2, P2) — discrete step gradations + iOS
    // value formatting honoring fractional step.
    val stepCount = if (stepVal > 0f) {
        ((maxVal - minVal) / stepVal - 1).toInt().coerceAtLeast(0)
    } else 0
    // Round-30 — decimal for ANY fractional step (see single-slider note above); Locale.US (POSIX)
    // like iOS; default JVM locale would render comma/Arabic-Indic decimals.
    val low = if (stepVal > 0f && stepVal % 1f != 0f) "%.1f".format(java.util.Locale.US, lowValue) else "${lowValue.roundToInt()}"
    val high = if (stepVal > 0f && stepVal % 1f != 0f) "%.1f".format(java.util.Locale.US, highValue) else "${highValue.roundToInt()}"

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FormFieldLabel(block)
            Text(
                text = "$low$unitStr - $high$unitStr",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = fillCol,
            )
        }

        // SPEC-401-A R59 (Lens C P2 #1) — discrete-step haptic tick on each
        // step crossing for both range thumbs (matches iOS native Slider).
        val rangeHapticView = androidx.compose.ui.platform.LocalView.current
        val lowLastBucket = remember(fieldId) {
            mutableStateOf(if (stepVal > 0f) ((lowValue - minVal) / stepVal).toInt() else 0)
        }
        val highLastBucket = remember(fieldId) {
            mutableStateOf(if (stepVal > 0f) ((highValue - minVal) / stepVal).toInt() else 0)
        }

        // Min slider
        Row(verticalAlignment = Alignment.CenterVertically) {
            // SPEC-401-A R44 — theme-adaptive secondary (was Color.Gray).
            Text((block.field_config?.get("min_label") as? String) ?: "Min", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.width(30.dp))
            Slider(
                value = lowValue,
                onValueChange = { v ->
                    lowValue = v
                    if (lowValue > highValue) highValue = lowValue
                    inputValues[fieldId] = mapOf("min" to lowValue.toDouble(), "max" to highValue.toDouble())
                    if (stepVal > 0f && maxVal > minVal) {
                        val bucket = ((v - minVal) / stepVal).toInt()
                        if (bucket != lowLastBucket.value) {
                            lowLastBucket.value = bucket
                            ai.appdna.sdk.core.HapticEngine.trigger(rangeHapticView, ai.appdna.sdk.core.HapticType.SELECTION)
                        }
                    }
                },
                valueRange = minVal..maxVal,
                steps = stepCount,
                colors = SliderDefaults.colors(thumbColor = fillCol, activeTrackColor = fillCol, inactiveTrackColor = trackCol),
                modifier = Modifier.weight(1f),
            )
        }
        // Max slider
        Row(verticalAlignment = Alignment.CenterVertically) {
            // SPEC-401-A R44 — theme-adaptive secondary (was Color.Gray).
            Text((block.field_config?.get("max_label") as? String) ?: "Max", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.width(30.dp))
            Slider(
                value = highValue,
                onValueChange = { v ->
                    highValue = v
                    if (highValue < lowValue) lowValue = highValue
                    inputValues[fieldId] = mapOf("min" to lowValue.toDouble(), "max" to highValue.toDouble())
                    if (stepVal > 0f && maxVal > minVal) {
                        val bucket = ((v - minVal) / stepVal).toInt()
                        if (bucket != highLastBucket.value) {
                            highLastBucket.value = bucket
                            ai.appdna.sdk.core.HapticEngine.trigger(rangeHapticView, ai.appdna.sdk.core.HapticType.SELECTION)
                        }
                    }
                },
                valueRange = minVal..maxVal,
                steps = stepCount,
                colors = SliderDefaults.colors(thumbColor = fillCol, activeTrackColor = fillCol, inactiveTrackColor = trackCol),
                modifier = Modifier.weight(1f),
            )
        }
    }

    LaunchedEffect(Unit) {
        inputValues[fieldId] = mapOf("min" to lowValue.toDouble(), "max" to highValue.toDouble())
    }
}

/** Chips / tag selection input. */
@Composable
private fun FormInputChipsBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    val options = block.field_options ?: emptyList()
    val fillCol = StyleEngine.parseColor(block.field_style?.fill_color ?: block.active_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
    val maxSelections = (block.field_config?.get("max_selections") as? Number)?.toInt()
    // OB-6 audit follow-up — restore saved chips selection on back nav.
    var selectedValues by remember {
        mutableStateOf(
            (inputValues[fieldId] as? List<*>)?.filterIsInstance<String>()?.toSet()
                ?: (inputValues[fieldId] as? Set<*>)?.filterIsInstance<String>()?.toSet()
                ?: setOf()
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        // Wrapping flow layout using FlowRow (Material3)
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                val isSelected = selectedValues.contains(option.value)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .border(
                            1.dp,
                            if (isSelected) fillCol else Color.Gray.copy(alpha = 0.3f),
                            RoundedCornerShape(999.dp),
                        )
                        // SPEC-401-A R21 — match iOS FormInputBlockViews.swift
                        // :1220 idle background `Color.gray.opacity(0.1)`. Was
                        // 0.05f (half opacity), making idle chips noticeably
                        // fainter on Android for the same JSON.
                        .background(if (isSelected) fillCol else Color.Gray.copy(alpha = 0.1f))
                        .clickable {
                            selectedValues = if (isSelected) {
                                selectedValues - option.value
                            } else {
                                if (maxSelections != null && selectedValues.size >= maxSelections) {
                                    selectedValues // at max
                                } else {
                                    selectedValues + option.value
                                }
                            }
                            inputValues[fieldId] = selectedValues.toList()
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = option.label,
                        // SPEC-401-A R57 (Lens A R57 #4, P2) — 15sp matches iOS
                        // .font(.subheadline) at FormInputBlockViews.swift:1217.
                        fontSize = 15.sp,
                        // SPEC-401-A R21 — match iOS FormInputBlockViews.swift
                        // :1221 idle text uses `.primary` (theme-aware: black
                        // in light mode, white in dark mode). Android was
                        // hardcoding Color.DarkGray which renders dark-grey
                        // on dark-grey backgrounds in dark mode (invisible).
                        // MaterialTheme.colorScheme.onSurface gives the
                        // equivalent theme-aware color on Android.
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** Color picker — grid of preset color swatches. */
@Composable
private fun FormInputColorBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    @Suppress("UNCHECKED_CAST")
    val presetColors: List<String> = (block.field_config?.get("preset_colors") as? List<String>)
        ?: listOf("#EF4444", "#F97316", "#EAB308", "#22C55E", "#3B82F6", (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"), "#A855F7", "#EC4899", "#000000", "#6B7280")
    // OB-6 audit follow-up — restore saved color on back nav.
    var selectedColor by remember {
        mutableStateOf((inputValues[fieldId] as? String) ?: "")
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        // SPEC-401-A R73 (Lens A P2 #1) — fixed 5-col grid matches iOS
        // FormInputBlockViews.swift:1257 `LazyVGrid(columns: 5)`. FlowRow
        // packed 7-10+ swatches per row depending on screen width while
        // iOS always renders 2×5 layout for 10 colors. Same authored
        // preset rendered visibly different across phones / iOS.
        val cols = 5
        val chunked = presetColors.chunked(cols)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chunked.forEach { rowColors ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowColors.forEach { color ->
                        val isSelected = selectedColor == color
                        // SPEC-401-A R73 (Lens A P2 #2) — selection-ring inset
                        // mirrors iOS FormInputBlockViews.swift:1264-1266
                        // `.stroke(...).padding(2)`: outer Box draws the ring,
                        // inner Box (the swatch fill) sits with 2dp inset so a
                        // 2dp halo gap separates ring from color. Was drawing
                        // border directly on the swatch — visually thickened
                        // it by 3dp instead of overlaying with a gap.
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .border(
                                    width = if (isSelected) 2.dp else 0.dp,
                                    // SPEC-401-A R44 — theme-adaptive selection ring
                                    // (was Color.DarkGray — invisible on dark surface).
                                    // iOS uses Color.primary at FormInputBlockViews.swift:1264.
                                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    shape = CircleShape,
                                )
                                .clickable {
                                    selectedColor = color
                                    inputValues[fieldId] = color
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(if (isSelected) 4.dp else 0.dp)
                                    .clip(CircleShape)
                                    .background(StyleEngine.parseColor(color))
                            )
                        }
                    }
                    // Pad shorter last row so swatches stay same width as
                    // full-row swatches (avoids stretching to full width).
                    repeat(cols - rowColors.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Placeholder for complex inputs (location, image_picker, signature). */
@Composable
private fun FormInputPlaceholderBlock(
    block: ContentBlock,
    icon: String,
    label: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp))
                // SPEC-401-A R45 — honour field_style.background_color
                // (was hardcoded white #F9FAFB). iOS reads field_style
                // background_color or transparent fallback
                // (FormInputBlockViews.swift:1304).
                .background(
                    block.field_style?.background_color
                        ?.takeIf { it.isNotEmpty() && it.lowercase() != "transparent" }
                        ?.let { StyleEngine.parseColor(it) }
                        ?: Color.Transparent
                )
                .border(
                    1.dp,
                    StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB"),
                    RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp),
                )
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = icon, fontSize = 16.sp)
                // SPEC-401-A R45 — theme-adaptive label (was Color.Gray).
                // iOS .secondary (FormInputBlockViews.swift:1297-1300).
                Text(text = label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }
    }
}

// MARK: - AC-046: Location Input with backend geocoding autocomplete

/** Location input with debounced autocomplete via AppDNA backend geocoding API. */
@Composable
private fun FormInputLocationPlaceholder(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    // SPEC-070-A finalization OB-6 — restore saved value on back nav.
    // iOS pre-populates `_inputValues = State(initialValue: savedResponses ?? [:])`
    // (`OnboardingRenderer.swift:1326`); Android previously initialized to ""
    // so every back-press wiped typed answers. Read from inputValues map.
    var text by remember { mutableStateOf((inputValues[fieldId] as? String) ?: "") }
    var suggestions by remember { mutableStateOf<List<LocationSuggestion>>(emptyList()) }
    var showSuggestions by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // SPEC-419 pass-15 #18/#19/#35 — honor field_style.text_color, show_prefix_icon,
    // and dropdown_* styling (all read from field_config, matching iOS + editor).
    val cfg = block.field_config
    val showPrefixIcon = (cfg?.get("show_prefix_icon") as? Boolean) ?: true
    val fieldTextColor = block.field_style?.text_color?.let { StyleEngine.parseColor(it) } ?: Color.Unspecified
    val ddBgColor = (cfg?.get("dropdown_bg_color") as? String)?.takeIf { it.isNotEmpty() }?.let { StyleEngine.parseColor(it) }
    val ddTextColor = (cfg?.get("dropdown_text_color") as? String)?.let { StyleEngine.parseColor(it) }
    val ddFontSize = ((cfg?.get("dropdown_font_size") as? Number)?.toFloat() ?: 14f).sp
    val ddRowHeight = (cfg?.get("dropdown_row_height") as? Number)?.toFloat()
    val ddIconColor = (cfg?.get("dropdown_icon_color") as? String)?.let { StyleEngine.parseColor(it) }
    val ddIconBgColor = (cfg?.get("dropdown_icon_bg_color") as? String)?.takeIf { it.isNotEmpty() }?.let { StyleEngine.parseColor(it) }
    val ddOpacity = (cfg?.get("dropdown_opacity") as? Number)?.toFloat() ?: 1f
    // SPEC-419 pass-16 #13 — honor dropdown_subtext_color/_sub_font_size (subtitle row),
    // dropdown_icon_size, and dropdown_icon_bg_gradient. iOS FormInputBlockViews.swift:1749-1791
    // already renders all of these; Android hardcoded 24dp icon / solid bg / no subtitle.
    val ddSubtextColor = (cfg?.get("dropdown_subtext_color") as? String)?.let { StyleEngine.parseColor(it) }
    val ddSubFontSize = ((cfg?.get("dropdown_sub_font_size") as? Number)?.toFloat() ?: 12f).sp
    val ddIconSize = ((cfg?.get("dropdown_icon_size") as? Number)?.toFloat() ?: 24f).dp
    @Suppress("UNCHECKED_CAST")
    val ddIconBgGradColors: List<Color>? = ((cfg?.get("dropdown_icon_bg_gradient") as? Map<String, Any?>)
        ?.get("colors") as? List<*>)
        ?.mapNotNull { (it as? String)?.let { hex -> StyleEngine.parseColor(hex) } }
        ?.takeIf { it.size >= 2 }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        OutlinedTextField(
            value = text,
            onValueChange = { newText ->
                text = newText
                inputValues[fieldId] = newText
                // Debounce API calls — wait 300ms after last keystroke
                debounceJob?.cancel()
                if (newText.length >= 3) {
                    debounceJob = coroutineScope.launch {
                        kotlinx.coroutines.delay(300)
                        isSearching = true
                        suggestions = fetchLocationSuggestions(newText)
                        showSuggestions = suggestions.isNotEmpty()
                        isSearching = false
                    }
                } else {
                    suggestions = emptyList()
                    showSuggestions = false
                }
            },
            // SPEC-401-A R44 — drop hardcoded Color.Gray placeholder color so
            // Material3 OutlinedTextFieldDefaults adaptive placeholder color
            // applies (theme-aware in dark mode).
            placeholder = { Text(block.field_placeholder ?: "Search location...") },
            // SPEC-419 pass-15 #35 \u2014 honor show_prefix_icon (default true).
            leadingIcon = if (showPrefixIcon) {
                { Text("\uD83D\uDCCD", fontSize = 16.sp) }
            } else null,
            trailingIcon = {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            },
            // SPEC-419 pass-17 — honor field_config.field_height on the location search box (iOS sibling already does).
            modifier = Modifier.fillMaxWidth().let { m -> (block.field_config?.get("field_height") as? Number)?.toFloat()?.let { m.heightIn(min = it.dp) } ?: m },
            singleLine = true,
            shape = RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = StyleEngine.parseColor(block.field_style?.focused_border_color ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1")),
                unfocusedBorderColor = StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB"),
                // SPEC-419 pass-15 #18 \u2014 honor field_style.text_color for the input text.
                focusedTextColor = fieldTextColor,
                unfocusedTextColor = fieldTextColor,
                // SPEC-419 pass-19 #3 \u2014 honor field_style.background_color ("Field Fill") like the
                // Text/Password siblings + iOS (FormInputBlockViews.swift:1676); else M3's default
                // surface fill draws over the authored fill. Defaults Transparent.
                focusedContainerColor = block.field_style?.background_color?.let { StyleEngine.parseColor(it) } ?: Color.Transparent,
                unfocusedContainerColor = block.field_style?.background_color?.let { StyleEngine.parseColor(it) } ?: Color.Transparent,
            ),
        )

        // Autocomplete results dropdown
        if (showSuggestions) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp))
                    // SPEC-419 pass-15 #19 — honor dropdown_bg_color (+ opacity); default theme surface.
                    .background((ddBgColor ?: MaterialTheme.colorScheme.surface).copy(alpha = ddOpacity))
                    .border(
                        1.dp,
                        StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB"),
                        RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp),
                    ),
            ) {
                suggestions.forEachIndexed { index, suggestion ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (ddRowHeight != null) Modifier.height(ddRowHeight.dp) else Modifier)
                            .clickable {
                                text = suggestion.address
                                showSuggestions = false
                                inputValues[fieldId] = mapOf(
                                    "address" to suggestion.address,
                                    "latitude" to suggestion.latitude,
                                    "longitude" to suggestion.longitude,
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // SPEC-419 pass-15 #19 / pass-16 #13 — pin icon with optional gradient/solid
                        // bg + icon color, honoring dropdown_icon_size.
                        Box(
                            modifier = Modifier
                                .size(ddIconSize)
                                .then(
                                    when {
                                        ddIconBgGradColors != null -> Modifier.clip(RoundedCornerShape(6.dp))
                                            .background(Brush.linearGradient(ddIconBgGradColors))
                                        ddIconBgColor != null -> Modifier.clip(RoundedCornerShape(6.dp)).background(ddIconBgColor)
                                        else -> Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = "📍", fontSize = 10.sp, color = ddIconColor ?: Color.Unspecified)
                        }
                        // #13 — primary + optional subtitle row (split address on first comma),
                        // mirroring iOS title/subtitle.
                        val addrParts = suggestion.address.split(",", limit = 2)
                        val addrPrimary = addrParts.getOrNull(0)?.trim().orEmpty().ifEmpty { suggestion.address }
                        val addrSecondary = addrParts.getOrNull(1)?.trim().orEmpty()
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = addrPrimary,
                                fontSize = ddFontSize,
                                color = ddTextColor ?: MaterialTheme.colorScheme.onSurface,
                            )
                            if (addrSecondary.isNotEmpty()) {
                                Text(
                                    text = addrSecondary,
                                    fontSize = ddSubFontSize,
                                    color = ddSubtextColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (index < suggestions.size - 1) {
                        // SPEC-401-A R24 — Material3 1.2 deprecated `Divider`
                        // in favor of `HorizontalDivider` (same API surface,
                        // distinct widget). Pre-emptive switch silences runtime
                        // warnings + unblocks the next BOM bump (1.4 removes).
                        // SPEC-401-A R45 — theme-adaptive divider (was Color.LightGray).
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

/** Data class for geocoding autocomplete suggestion. */
private data class LocationSuggestion(
    val address: String,
    val latitude: Double,
    val longitude: Double,
)

/** Fetch location suggestions from the AppDNA backend geocoding API.
 *
 * SPEC-070-A A.12: Path is `/api/v1/sdk/geocode/autocomplete` (matches the actual
 * SDK-scoped backend route — the previous `/api/v1/geocoding/autocomplete` 404'd).
 * Host is sourced from the configured environment so sandbox builds hit the
 * correct backend; the SDK API key is forwarded via `x-api-key` to match other
 * SDK calls.
 */
private suspend fun fetchLocationSuggestions(query: String): List<LocationSuggestion> {
    return try {
        val baseUrl = ai.appdna.sdk.AppDNA.getApiBaseUrl()
        val apiKey = ai.appdna.sdk.AppDNA.getApiKey()
        val url = java.net.URL("$baseUrl/api/v1/sdk/geocode/autocomplete")
        // SPEC-401-A R28 P0 — backend at `src/app/api/v1/sdk/geocode/
        // autocomplete/route.ts:27` exports ONLY POST. GET returned 405 →
        // silent emptyList → suggestions never appeared. iOS POSTs JSON
        // body via Endpoint.geocodeAutocomplete (LocationFieldView.swift
        // :197-234). Both sites in this file (this `LocationFieldComposable`
        // helper inline path + the canonical helper in
        // LocationFieldComposable.kt) need the POST body shape.
        val requestBody = org.json.JSONObject().apply {
            put("query", query)
            put("limit", 5)
        }.toString()
        val body: String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val connection = (url.openConnection() as? java.net.HttpURLConnection)?.apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (apiKey != null) setRequestProperty("x-api-key", apiKey)
            } ?: return@withContext null
            try {
                connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode != 200) return@withContext null
                connection.inputStream.bufferedReader().readText()
            } finally {
                runCatching { connection.disconnect() }
            }
        }
        if (body == null) return emptyList()
        // Backend wraps in `{data: {suggestions: [...]}}`; iOS reads
        // `json.data.suggestions`. Was reading `json.data` directly →
        // null because data is an object.
        val json = org.json.JSONObject(body)
        val results = json.optJSONObject("data")?.optJSONArray("suggestions") ?: return emptyList()

        (0 until minOf(results.length(), 5)).mapNotNull { i ->
            val item = results.optJSONObject(i) ?: return@mapNotNull null
            LocationSuggestion(
                address = item.optString("formatted_address", ""),
                latitude = item.optDouble("latitude", 0.0),
                longitude = item.optDouble("longitude", 0.0),
            )
        }
    } catch (e: Exception) {
        ai.appdna.sdk.Log.debug("Location autocomplete failed: ${e.message}")
        emptyList()
    }
}

// MARK: - AC-048: Image Picker with PickVisualMedia (Android 13+, backported via Activity Result API)

/** Image picker using PickVisualMedia. Stores selected image data as base64 in inputValues. */
@Composable
private fun FormInputImagePickerPlaceholder(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    val context = LocalContext.current
    var selectedImageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var thumbnailBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes()
                inputStream?.close()
                if (bytes != null) {
                    thumbnailBitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    inputValues[fieldId] = mapOf(
                        "data" to base64,
                        "size" to bytes.size,
                        "mime_type" to (context.contentResolver.getType(uri) ?: "image/jpeg"),
                    )
                }
            } catch (e: Exception) {
                ai.appdna.sdk.Log.debug("Image picker load failed: ${e.message}")
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        if (thumbnailBitmap != null) {
            // Show selected image thumbnail
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp))
                    // SPEC-401-A R21 \u2014 match iOS FormInputBlockViews.swift
                    // :1687,1704 which reads `field_style.border_width ?? 1`.
                    // Android was hardcoding 1.dp, dropping any console-set
                    // thickness on the populated thumbnail state.
                    .border(
                        ((block.field_style?.border_width ?: 1.0).toFloat()).dp,
                        StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB"),
                        RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp),
                    )
                    .clickable {
                        launcher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
            ) {
                thumbnailBitmap?.asImageBitmap()?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = "Selected image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                    )
                }
                // SPEC-401-A R21 \u2014 match iOS FormInputBlockViews.swift
                // :1707-1712 edit overlay: filled white circle + pencil
                // glyph + shadow. Android was rendering a small black emoji
                // that disappeared against dark thumbnails.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .shadow(2.dp, CircleShape)
                        .background(Color.White.copy(alpha = 0.9f), CircleShape)
                        .padding(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit image",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        } else {
            // Empty state — dashed border tap target
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp))
                    // SPEC-401-A R21 — same fixes R20 made for FormInputSignature
                    // also apply here. iOS reads field_style.background_color
                    // (transparent default) AND uses dashed `StrokeStyle
                    // (lineWidth: 1, dash: [6, 3])`. Android was hardcoding
                    // #F9FAFB + solid border.
                    .background(
                        block.field_style?.background_color
                            ?.takeIf { it.isNotEmpty() && it.lowercase() != "transparent" }
                            ?.let { StyleEngine.parseColor(it) }
                            ?: Color.Transparent
                    )
                    .drawBehind {
                        val cornerR = ((block.field_style?.corner_radius ?: 8.0).toFloat()) * density
                        val borderColorParsed = StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB")
                        val strokeWidthPx = 1f * density
                        drawRoundRect(
                            color = borderColorParsed,
                            size = size,
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerR, cornerR),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = strokeWidthPx,
                                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                    floatArrayOf(6f * density, 3f * density),
                                    0f,
                                ),
                            ),
                        )
                    }
                    .clickable {
                        launcher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "\uD83D\uDDBC", fontSize = 16.sp)
                    // SPEC-401-A R45 — theme-adaptive prompt (was Color.Gray).
                    // iOS .secondary (FormInputBlockViews.swift:1720).
                    // SPEC-401-A R57 (Lens A R57 #11, P2) — 15sp matches iOS
                    // .subheadline (FormInputBlockViews.swift:1719).
                    Text(text = "Tap to pick image", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
        }
    }
}

// MARK: - AC-051: Signature Input (interactive Canvas with touch drawing)

/** Interactive signature pad with basic touch/drag drawing. */
@Composable
private fun FormInputSignatureBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    val lines = remember { mutableStateListOf<List<Offset>>() }
    var currentLine by remember { mutableStateOf<List<Offset>>(emptyList()) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp))
                // SPEC-401-A R20 — match iOS FormInputBlockViews.swift:1791:
                // background reads `field_style.background_color` (default
                // transparent). Android was hardcoding #F9FAFB which silently
                // overrode any console-published field background.
                .background(
                    block.field_style?.background_color
                        ?.takeIf { it.isNotEmpty() && it.lowercase() != "transparent" }
                        ?.let { StyleEngine.parseColor(it) }
                        ?: Color.Transparent
                )
                // SPEC-401-A R20 — match iOS FormInputBlockViews.swift:1797:
                // `.stroke(borderColor, style: StrokeStyle(lineWidth: 1,
                // dash: [6, 3]))`. Compose `Modifier.border` is solid only;
                // use `drawBehind` with `PathEffect.dashPathEffect` for the
                // dotted-stroke convention. Was rendering as solid border on
                // Android for the same JSON.
                .drawBehind {
                    val cornerR = ((block.field_style?.corner_radius ?: 8.0).toFloat()) * density
                    val borderColorParsed = StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB")
                    val strokeWidthPx = 1f * density
                    drawRoundRect(
                        color = borderColorParsed,
                        size = size,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerR, cornerR),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = strokeWidthPx,
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                floatArrayOf(6f * density, 3f * density),
                                0f,
                            ),
                        ),
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            currentLine = listOf(offset)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            currentLine = currentLine + change.position
                        },
                        onDragEnd = {
                            if (currentLine.isNotEmpty()) {
                                lines.add(currentLine)
                                currentLine = emptyList()
                                inputValues[fieldId] = "signed"
                            }
                        },
                    )
                },
        ) {
            // SPEC-401-A B2 P1 \u2014 theme-aware stroke colour + 2.dp width
            // matching iOS `FormInputBlockViews.swift:1780-1789` (which uses
            // `.color(.primary)` and `lineWidth: 2`). Was hardcoded
            // `Color.Black` + 4f (twice as thick + invisible in dark mode).
            // SPEC-419 pass-15 #15 — honor field_config.stroke_color (editor + preview); was hardcoded theme onSurface.
            val strokeColor = (block.field_config?.get("stroke_color") as? String)?.let { StyleEngine.parseColor(it) }
                ?: MaterialTheme.colorScheme.onSurface
            // SPEC-419 pass-16 #2 — honor field_config.stroke_width (editor default 2); was hardcoded 2.dp.
            val strokeWidthDp = (block.field_config?.get("stroke_width") as? Number)?.toFloat() ?: 2f
            val strokePx = with(LocalDensity.current) { strokeWidthDp.dp.toPx() }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val allLines = lines.toList() + listOf(currentLine)
                allLines.forEach { line ->
                    if (line.size > 1) {
                        for (i in 0 until line.size - 1) {
                            drawLine(
                                color = strokeColor,
                                start = line[i],
                                end = line[i + 1],
                                strokeWidth = strokePx,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
            }

            // SPEC-401-A B3 P2 \u2014 clear button is now an icon-only Material
            // `IconButton` (Material trailing-action pattern). iOS uses an
            // SF Symbol icon button \u2014 Android equivalent is `Icons.Filled.Close`.
            if (lines.isNotEmpty()) {
                IconButton(
                    onClick = {
                        lines.clear()
                        currentLine = emptyList()
                        inputValues.remove(fieldId)
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Clear signature",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
        // SPEC-401-A B3 P2 \u2014 placeholder caption now sits BELOW the canvas
        // (iOS standard form-field empty-state) rather than centred inside.
        // Inside-canvas placeholder was less discoverable when the user
        // started drawing.
        if (lines.isEmpty() && currentLine.isEmpty()) {
            Text(
                // SPEC-401-A R23 — match iOS FormInputBlockViews.swift:1825
                // ("Draw your signature above"). Android was "...here".
                text = "Draw your signature above",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

// MARK: - Stub Placeholder (SPEC-089d)

/**
 * Placeholder view for new block types whose full renderers are not yet implemented.
 * Renders a subtle debug label when SDK log level is DEBUG; nothing otherwise.
 */
@Composable
private fun StubBlockPlaceholder(typeName: String) {
    if (Log.level.level >= LogLevel.DEBUG.level) {
        Text(
            text = "[$typeName]",
            fontSize = 10.sp,
            // SPEC-401-A R45 — theme-adaptive stub label (was Color.Gray).
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )
    }
    // In non-debug mode: renders nothing (empty composable)
}
