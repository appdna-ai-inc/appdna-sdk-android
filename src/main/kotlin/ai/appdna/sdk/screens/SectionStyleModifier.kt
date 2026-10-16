package ai.appdna.sdk.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import ai.appdna.sdk.core.ShadowStyleConfig
import ai.appdna.sdk.core.StyleEngine

/**
 * #904 — the authored section style, applied.
 *
 * iOS has applied the whole of `SectionStyle` since this surface existed (`ScreenRenderer.swift`
 * `applySectionStyle`). Android PARSED all of it — background, gradient, padding, margin, radius,
 * border, shadow, opacity — into [SectionStyle] and then read none of it: the only other mention of
 * the type in the Android sources was the parser that built it. One authored screen rendered styled
 * on an iPhone and unstyled on an Android, and no gate could see it: the field IS authorable, and
 * the section DOES render.
 *
 * 🔴 THE ORDER IS A MIRROR, INCLUDING ITS ODDITY. On iOS the chain reads
 *
 *     .padding(margin_*) .background(…) .overlay(border) .shadow(…) .padding(padding_*) .opacity(…)
 *
 * and SwiftUI applies modifiers outside-in, so `margin_*` ends up INSIDE the background and
 * `padding_*` OUTSIDE it — the two are, in effect, swapped against their names. Mirroring that is
 * deliberate: the point of this change is that the two platforms draw one authored screen the same
 * way, and "fixing" the naming on one side would replace a missing-style divergence with a
 * spacing one. The inversion itself is filed separately, for both platforms at once.
 *
 * The shadow goes through [StyleEngine.resolveShadow] and [StyleEngine.appdnaShadow] (#886), so it
 * cannot drift from what a block or a paywall section does with the same five fields — and it is
 * applied BEFORE the background, because a clip or a background later in the chain would otherwise
 * cut away the offset, blurred part a shadow consists of.
 */
internal fun Modifier.applySectionStyle(style: SectionStyle?): Modifier {
    if (style == null) return this
    val shape = RoundedCornerShape((style.borderRadius ?: 0.0).dp)
    var mod = this

    // "margin" — inside the background, matching the iOS chain.
    val mTop = style.marginTop ?: 0.0
    val mBottom = style.marginBottom ?: 0.0
    if (mTop > 0 || mBottom > 0) mod = mod.padding(top = mTop.dp, bottom = mBottom.dp)

    mod = with(StyleEngine) {
        mod.appdnaShadow(
            StyleEngine.resolveShadow(
                style.shadow?.let {
                    ShadowStyleConfig(x = it.x, y = it.y, blur = it.blur, spread = it.spread, color = it.color)
                },
            ),
            shape,
        )
    }

    val gradient = style.backgroundGradient
    if (gradient?.start != null && gradient.end != null) {
        // iOS fills top → bottom regardless of `angle`; the angle is not read there, so it is not
        // read here either. A gradient that honoured the angle on one platform only would be the
        // same class of divergence this file exists to remove.
        mod = mod.background(
            Brush.verticalGradient(
                listOf(StyleEngine.parseColor(gradient.start), StyleEngine.parseColor(gradient.end)),
            ),
            shape,
        )
    } else if (style.backgroundColor != null) {
        mod = mod.background(StyleEngine.parseColor(style.backgroundColor), shape)
    }

    if ((style.borderWidth ?: 0.0) > 0) {
        mod = mod.border((style.borderWidth ?: 0.0).dp, StyleEngine.parseColor(style.borderColor ?: "transparent"), shape)
    }

    // "padding" — outside the background, matching the iOS chain.
    val pTop = style.paddingTop ?: 0.0
    val pRight = style.paddingRight ?: 0.0
    val pBottom = style.paddingBottom ?: 0.0
    val pLeft = style.paddingLeft ?: 0.0
    if (pTop > 0 || pRight > 0 || pBottom > 0 || pLeft > 0) {
        mod = mod.padding(top = pTop.dp, end = pRight.dp, bottom = pBottom.dp, start = pLeft.dp)
    }

    style.opacity?.let { mod = mod.alpha(it.toFloat()) }
    return mod
}
