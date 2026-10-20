package ai.appdna.sdk.core

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

data class ParticleEffect(
    val type: String = "confetti",       // "confetti", "sparkle", "fireworks", "snow", "hearts"
    val trigger: String = "on_appear",   // "on_appear", "on_step_complete", "on_purchase", "on_flow_complete"
    // Default 2000ms matches iOS
    // ConfettiOverlay.swift:89,101 `effect.duration_ms ?? 2000`. Was 2500 —
    // iOS-emitted unstyled effects animated 500ms shorter on iOS.
    val duration_ms: Int = 2000,
    val intensity: String = "medium",    // "light", "medium", "heavy"
    val colors: List<String>? = null,
)


@Composable
fun ConfettiOverlay(
    effect: ParticleEffect,
    trigger: Boolean = true,
) {
    var isActive by remember { mutableStateOf(false) }
    val particleCount = when (effect.intensity) {
        "light" -> 30
        "heavy" -> 120
        else -> 60
    }

    /*
     * #1040 — ONE LIST, not a `when` per platform. This returned Compose's named constants for
     * four of six values (`Color.Red` is #FF0000) where iOS returned SwiftUI's semantic colours
     * (`.red` is #FF3B30), so an unauthored celebration — the default the editor produces — drew
     * pure RGB here and Apple's palette there, four of seven values apart on plain confetti. An
     * earlier pass on this file had already committed to the HIG values in its own comment and
     * converted only the three explicit hexes.
     */
    val effectColors = ai.appdna.sdk.core.CelebrationPalette
        .colors(effect.type, effect.colors)
        .map { StyleEngine.parseColor(it) }

    // #1040 — the shared rule, so "circle or glyph" is one question with one answer.
    val glyph = ai.appdna.sdk.core.CelebrationPalette.glyph(effect.type)
    val progress = remember { Animatable(0f) }

    /*
     * 🔴 #956 — NO SECOND GATE on `effect.trigger`. Every caller already decides WHEN to pass
     * `trigger = true`: the paywall after a purchase, the survey on completion, a message on its
     * own condition. Re-checking the authored trigger here could only SUBTRACT, and it did — an
     * effect authored `on_purchase` was armed by the paywall and refused here, and
     * `PaywallConfig.kt` defaults an unauthored paywall effect to exactly that. iOS had the same
     * double gate in `ConfettiOverlay.swift`.
     */
    LaunchedEffect(trigger) {
        if (trigger) {
            isActive = true
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = effect.duration_ms, easing = LinearEasing)
            )
            isActive = false
        }
    }

    if (isActive) {
        val particles = remember(particleCount) {
            (0 until particleCount).map { i ->
                ConfettiParticle(
                    startX = (Math.random() * 1000).toFloat(),
                    startY = -(Math.random() * 200).toFloat(),
                    color = effectColors[i % effectColors.size],
                    size = (4f + Math.random().toFloat() * 8f),
                    speedX = (-50f + Math.random().toFloat() * 100f),
                    // speedY is now a fraction
                    // of canvas height (0.6..1.0) so particles always reach
                    // bottom of tall devices.
                    speedY = (0.6f + Math.random().toFloat() * 0.4f),
                    rotation = Math.random().toFloat() * 360f,
                )
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = progress.value
            // Fall distance is the actual canvas
            // height (+50 buffer), matching iOS ConfettiOverlay.swift:91-95
            // which animates to `UIScreen.main.bounds.height + 50`. Was
            // hardcoded `200..600 px` — particles never reached the bottom
            // of tall devices (Pixel 6/7 ≈ 3120 px, tablets larger). Heavy
            // confetti looked stunted vs iOS full-screen rain.
            val fallDistance = size.height + 50f

            // When the effect type has a glyph (hearts/snow/sparkle/
            // fireworks), draw the emoji via NativeCanvas.drawText for parity
            // with iOS ConfettiOverlay.swift:67-75 which composes a `Text(...)`
            // per particle. Plain "confetti" stays drawCircle (matches iOS
            // Rectangle()). drawIntoCanvas + NativeCanvas is available since
            // Compose 1.0 — no BoM upgrade required (deferral resolved).
            if (glyph.isNotEmpty()) {
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    particles.forEach { particle ->
                        val x = particle.startX / 1000f * size.width + particle.speedX * t
                        val y = particle.startY + particle.speedY * fallDistance * t
                        val alpha = (1f - t).coerceIn(0f, 1f)
                        if (y < size.height + 50) {
                            // Particle size 4..12 → glyph size 20..36sp.
                            // iOS uses .font(.system(size: particle.size * 2))
                            // → 8..24pt. We bias slightly larger for parity
                            // with Compose default-size emoji metrics.
                            paint.textSize = particle.size * 3f
                            paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
                            canvas.nativeCanvas.drawText(
                                glyph,
                                x.coerceIn(0f, size.width),
                                y,
                                paint,
                            )
                        }
                    }
                }
            } else {
                particles.forEach { particle ->
                    val x = particle.startX / 1000f * size.width + particle.speedX * t
                    val y = particle.startY + particle.speedY * fallDistance * t
                    val alpha = (1f - t).coerceIn(0f, 1f)
                    if (y < size.height + 50) {
                        drawCircle(
                            color = particle.color.copy(alpha = alpha),
                            radius = particle.size,
                            center = Offset(x.coerceIn(0f, size.width), y),
                        )
                    }
                }
            }
        }
    }
}

private data class ConfettiParticle(
    val startX: Float,
    val startY: Float,
    val color: Color,
    val size: Float,
    val speedX: Float,
    val speedY: Float,
    val rotation: Float,
)
