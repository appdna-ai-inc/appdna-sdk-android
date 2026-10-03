package ai.appdna.sdk.core

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// Read once whether the user has Reduce Motion
// enabled (Settings → Accessibility → Remove animations sets all 3 scale
// settings to 0). iOS SwiftUI `.animation()` / `.transition()` are gated
// implicitly by `UIAccessibility.isReduceMotionEnabled` — Compose does not
// auto-gate, so each entry/stagger/CTA/glow modifier opts out manually below.
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            val scale = android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE,
                1f,
            )
            scale == 0f
        } catch (_: android.provider.Settings.SettingNotFoundException) {
            false
        }
    }
}

// MARK: - Entry Animation

@Composable
fun Modifier.entryAnimation(animation: String?, durationMs: Int? = null): Modifier {
    val anim = animation ?: "none"
    if (anim == "none") return this
    // Short-circuit when Reduce Motion is on.
    if (rememberReduceMotion()) return this

    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    val duration = durationMs ?: 400
    // easeOut matches iOS
    // .easeOut(duration:) at AnimationModifiers.swift:15. FastOutSlowInEasing
    // (≈ ease-in-out) made slide_up/scale_in feel slower-finishing.
    val animSpec = tween<Float>(durationMillis = duration, easing = LinearOutSlowInEasing)

    // Alpha only animates when the
    // animation type is fade-related. iOS AnimationModifiers.swift:13 only
    // toggles opacity for fade_in / slide_up. scale_in on iOS is pure
    // scale-with-no-fade; Android was cross-fading even on scale_in.
    val alpha by animateFloatAsState(
        targetValue = if (appeared || (anim != "fade_in" && anim != "slide_up")) 1f else 0f,
        animationSpec = animSpec,
        label = "entry_alpha",
    )
    val scaleVal by animateFloatAsState(
        targetValue = if (appeared || anim != "scale_in") 1f else 0.8f,
        animationSpec = animSpec,
        label = "entry_scale",
    )
    val offsetY by animateFloatAsState(
        targetValue = if (appeared || anim != "slide_up") 0f else 800f,
        animationSpec = animSpec,
        label = "entry_offset",
    )

    // `Modifier.offset { IntOffset(...) }` lambda
    // form is pixel-space, NOT dp. iOS `.offset(y: 800)` is point-space (dp
    // equivalent). Same payload renders 800dp displacement on mdpi (1x) but
    // only 200dp on xhdpi (2x), 100dp on xxhdpi (3x). Convert dp→px via
    // LocalDensity so all densities behave like iOS.
    val density = LocalDensity.current
    val offsetYPx = with(density) { offsetY.dp.toPx() }
    return this
        .alpha(alpha)
        .scale(scaleVal)
        .offset { IntOffset(0, offsetYPx.toInt()) }
}

// MARK: - Section Stagger Animation

@Composable
fun Modifier.sectionStagger(animation: String?, delayMs: Int? = null): Modifier {
    val anim = animation ?: "none"
    if (anim == "none") return this
    // Short-circuit when Reduce Motion is on.
    if (rememberReduceMotion()) return this

    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay((delayMs ?: 0).toLong())
        appeared = true
    }

    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(400),
        label = "stagger_alpha",
    )
    // Spring.StiffnessLow (~200) matches
    // iOS `.spring(response: 0.5, dampingFraction: 0.6)` ≈ stiffness 158. Was
    // defaulting to Spring.StiffnessMedium (1500) — ~10× stiffer — so
    // bounce-in stagger snapped instantly on Android vs slow-bounce on iOS.
    val offsetX by animateFloatAsState(
        targetValue = if (appeared) 0f else when (anim) {
            "slide_in_left" -> -300f
            "slide_in_right" -> 300f
            else -> 0f
        },
        animationSpec = if (anim == "bounce") spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow) else tween(400),
        label = "stagger_offset",
    )
    val scaleVal by animateFloatAsState(
        targetValue = if (appeared || anim != "bounce") 1f else 0.5f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow),
        label = "stagger_scale",
    )

    // Sister of entryAnimation fix above:
    // convert offsetX dp→px via LocalDensity so slide_in distance matches
    // iOS `.offset(x: ±100)` point-space across all density buckets.
    // Was raw 300f pixels: 300dp on mdpi (3x iOS), 150dp on xhdpi (1.5x),
    // 100dp on xxhdpi (matched only by coincidence), 75dp on xxxhdpi.
    val density = LocalDensity.current
    val offsetXPx = with(density) { offsetX.dp.toPx() }
    return this
        .alpha(alpha)
        .scale(scaleVal)
        .offset { IntOffset(offsetXPx.toInt(), 0) }
}

// MARK: - CTA Animations

@Composable
fun Modifier.ctaAnimation(animation: String?): Modifier {
    // Short-circuit when Reduce Motion is on.
    if (rememberReduceMotion()) return this
    return when (animation) {
        "pulse" -> this.pulseEffect()
        "glow" -> this.glowEffect()
        "bounce" -> this.bounceEffect()
        else -> this
    }
}

@Composable
private fun Modifier.pulseEffect(): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "pulse_scale",
    )
    return this.scale(scale)
}

@Composable
private fun Modifier.glowEffect(): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "glow_alpha",
    )
    // Accent from MaterialTheme.primary
    // matching iOS `.accentColor` resolution from host app's tint / asset
    // catalog (AnimationModifiers.swift:73). Hardcoded #6366F1 forced indigo
    // regardless of host theme — glow halos always rendered indigo even
    // when the app's brand color was set to a different hue.
    val accentColor = MaterialTheme.colorScheme.primary
    return this.shadow(
        elevation = 15.dp,
        ambientColor = accentColor.copy(alpha = alpha),
        spotColor = accentColor.copy(alpha = alpha),
    )
}

@Composable
private fun Modifier.bounceEffect(): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "bounce")
    // Bounce amplitude 15→5dp matching
    // iOS AnimationModifiers.swift:84 `.offset(y: isBouncing ? -5 : 0)` (5pt).
    // Android was 3× iOS — CTA bounced dramatically more than iOS.
    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -5f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "bounce_offset",
    )
    return this.offset { IntOffset(0, offsetY.toInt()) }
}

// MARK: - Plan Selection Animation

@Composable
fun Modifier.planSelectionAnimation(animation: String?, isSelected: Boolean): Modifier {
    val anim = animation ?: "none"
    if (anim == "none") return this
    // Short-circuit when Reduce Motion is on.
    if (rememberReduceMotion()) return this

    // Match iOS `spring(response: 0.3,
    // dampingFraction: 0.7)` at AnimationModifiers.swift:125. Compose
    // default Spring.StiffnessMedium=1500 was ~3.4x stiffer than iOS
    // (response→stiffness: (2π/0.3)² ≈ 438), so the scale animation
    // snapped almost instantly on Android while iOS showed a visible
    // spring on plan-card tap. Apply same spring to elevation so glow
    // animation matches scale timing — was tween(300) which animated
    // a different curve than iOS's bundled .spring.
    val planSpring = spring<Float>(dampingRatio = 0.7f, stiffness = 438f)
    val scale by animateFloatAsState(
        targetValue = if (anim == "scale" && isSelected) 1.03f else 1f,
        animationSpec = planSpring,
        label = "plan_scale",
    )
    val elevation by animateFloatAsState(
        // Drop 12→10 to match iOS shadow(radius:10)
        // at AnimationModifiers.swift:118. R64 nailed the spring + accent color
        // but missed the radius magnitude — Android halo rendered ~20% larger.
        targetValue = if (anim == "glow" && isSelected) 10f else 0f,
        animationSpec = planSpring,
        label = "plan_glow",
    )
    // Accent from MaterialTheme.primary
    // matching iOS `.accentColor` (AnimationModifiers.swift:117 + 122). Was
    // hardcoded #6366F1 — `border_highlight` always rendered indigo on
    // Android regardless of host theme.
    val accent = MaterialTheme.colorScheme.primary
    val planGlowColor = accent.copy(alpha = if (anim == "glow" && isSelected) 0.5f else 0f)
    val borderMod = if (anim == "border_highlight" && isSelected) {
        Modifier.border(2.5.dp, accent, RoundedCornerShape(12.dp))
    } else Modifier

    return this
        .scale(scale)
        .shadow(elevation.dp, ambientColor = planGlowColor, spotColor = planGlowColor)
        .then(borderMod)
}

// MARK: - Dismiss Animation

@Composable
fun Modifier.dismissAnimation(animation: String?, dismiss: Boolean): Modifier {
    val anim = animation ?: "none"
    if (anim == "none") return this
    // Short-circuit when Reduce Motion is on.
    if (rememberReduceMotion()) return this

    // Only fade for fade_out anim;
    // matches iOS AnimationModifiers.swift:138
    // `.opacity(animation == "fade_out" && isDismissing ? 0 : 1)`. Was
    // cross-fading on every dismiss including slide_down — combined fade+
    // slide motion blur on Android vs solid slide on iOS.
    val alpha by animateFloatAsState(
        targetValue = if (dismiss && anim == "fade_out") 0f else 1f,
        animationSpec = tween(300, easing = FastOutLinearInEasing),
        label = "dismiss_alpha",
    )
    // FastOutLinearInEasing matches
    // iOS `.easeIn(duration: 0.3)` (AnimationModifiers.swift:139). Was
    // FastOutSlowInEasing (ease-in-out) so slide-down "hung" at start
    // before accelerating; iOS drops immediately.
    val offsetY by animateFloatAsState(
        targetValue = if (dismiss && anim == "slide_down") 800f else 0f,
        animationSpec = tween(300, easing = FastOutLinearInEasing),
        label = "dismiss_offset",
    )
    val scale by animateFloatAsState(
        targetValue = if (dismiss && anim == "scale_out") 0.8f else 1f,
        animationSpec = tween(300, easing = FastOutLinearInEasing),
        label = "dismiss_scale",
    )

    return this
        .alpha(alpha)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .offset { IntOffset(0, offsetY.toInt()) }
}
