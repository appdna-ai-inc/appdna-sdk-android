package ai.appdna.sdk.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * #1432 — the countdown a step that advances by itself can draw while it waits.
 *
 * Authored as `auto_advance_indicator` beside `auto_advance_seconds`; off unless `show: true`, so every
 * flow already published draws nothing, as before. `{seconds}` in the text is the whole seconds LEFT,
 * rounded up, and the bar is the share of the wait REMAINING. Nothing is drawn for a step that does not
 * advance by itself (no timer, or `wait_for_host`).
 *
 * Mirrors iOS `AutoAdvanceIndicator` and `src/lib/onboarding/auto-advance-indicator.ts`. Pinned on both
 * platforms by the shared fixture `step_advance/auto_advance_indicator`.
 */
object AutoAdvanceIndicator {
    data class Frame(
        val style: String,
        val label: String?,
        val fraction: Double,
        val position: String,
        val textColor: String,
        val fontSize: Double,
        val barColor: String,
        val trackColor: String,
        val barHeight: Double,
    )

    private const val DEFAULT_TEXT = "Continuing in {seconds}"
    private val HEX = Regex("^#[0-9a-fA-F]{3,8}$")

    /** The authored hex colour, or null when it is absent or not a colour. */
    private fun colour(raw: Any?): String? = (raw as? String)?.trim()?.takeIf { HEX.matches(it) }

    private fun number(raw: Any?, fallback: Double, lo: Double, hi: Double): Double {
        val n = when (raw) {
            is Boolean -> Double.NaN
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull() ?: Double.NaN
            else -> Double.NaN
        }
        return if (n.isFinite()) n.coerceIn(lo, hi) else fallback
    }

    /** What is drawn [elapsed] seconds into a wait of [delay], or null when nothing is. */
    fun resolve(raw: Map<String, Any?>?, delay: Double?, elapsed: Double): Frame? {
        if (raw == null || raw["show"] != true || delay == null || delay <= 0.0) return null
        val style = (raw["style"] as? String)?.takeIf { it in setOf("text", "bar", "both") } ?: "both"
        val t = if (elapsed.isFinite()) maxOf(0.0, elapsed) else 0.0
        val left = maxOf(0.0, delay - t)
        val seconds = maxOf(0, kotlin.math.ceil(left - 1e-9).toInt())
        val text = (raw["text"] as? String)?.takeIf { it.isNotBlank() } ?: DEFAULT_TEXT
        return Frame(
            style = style,
            label = if (style == "bar") null else text.replace("{seconds}", seconds.toString()),
            fraction = Math.round((left / delay).coerceIn(0.0, 1.0) * 1000).toDouble() / 1000,
            position = if (raw["position"] == "top") "top" else "bottom",
            textColor = colour(raw["text_color"]) ?: "#6B7280",
            fontSize = number(raw["font_size"], 13.0, 8.0, 40.0),
            barColor = colour(raw["bar_color"]) ?: "#6366F1",
            trackColor = colour(raw["track_color"]) ?: "#E5E7EB",
            barHeight = number(raw["bar_height"], 4.0, 1.0, 16.0),
        )
    }

    /** The indicator for a step as published, [elapsed] seconds after it appeared. */
    fun resolve(step: OnboardingStep, elapsed: Double): Frame? =
        resolve(step.auto_advance_indicator, StepAutoAdvance.delaySeconds(step), elapsed)
}

/** Draws one resolved moment of the countdown. Pure, so a test can render a given instant. */
@Composable
internal fun AutoAdvanceIndicatorDrawing(frame: AutoAdvanceIndicator.Frame, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = if (frame.position == "top") Alignment.TopCenter else Alignment.BottomCenter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .testTag("auto_advance_indicator"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            frame.label?.let {
                Text(
                    text = it,
                    color = ai.appdna.sdk.core.StyleEngine.parseColor(frame.textColor),
                    fontSize = frame.fontSize.sp,
                    modifier = Modifier.testTag("auto_advance_indicator_label"),
                )
            }
            if (frame.style != "text") {
                val shape = RoundedCornerShape((frame.barHeight / 2).dp)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(frame.barHeight.dp)
                        .clip(shape)
                        .background(ai.appdna.sdk.core.StyleEngine.parseColor(frame.trackColor)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(frame.fraction.toFloat())
                            .fillMaxHeight()
                            .background(ai.appdna.sdk.core.StyleEngine.parseColor(frame.barColor))
                            .testTag("auto_advance_indicator_bar"),
                    )
                }
            }
        }
    }
}

/**
 * The live countdown over a step. Its clock is the frame clock from the moment the step composes,
 * which is when `LaunchedEffect(currentIndex)` starts the step's timer.
 */
@Composable
internal fun AutoAdvanceIndicatorOverlay(step: OnboardingStep, modifier: Modifier = Modifier) {
    if (AutoAdvanceIndicator.resolve(step, 0.0) == null) return
    var start by remember(step.id) { mutableStateOf(-1L) }
    var now by remember(step.id) { mutableStateOf(0L) }
    LaunchedEffect(step.id) {
        while (true) {
            withFrameNanos { t ->
                if (start < 0) start = t
                now = t
            }
        }
    }
    val elapsed = if (start < 0) 0.0 else (now - start) / 1_000_000_000.0
    val frame = AutoAdvanceIndicator.resolve(step, elapsed) ?: return
    AutoAdvanceIndicatorDrawing(frame, modifier.systemBarsPadding())
}
