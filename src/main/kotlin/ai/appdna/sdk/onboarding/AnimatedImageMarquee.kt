package ai.appdna.sdk.onboarding

import ai.appdna.sdk.core.authoredImageSemantics
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest

/**
 * `animated_image` — one long image (a photo collage, say 1600×400) scrolling in a seamless loop.
 *
 * The image fills the block across the scroll axis (its height for left/right, its width for up/down)
 * and is repeated along it; the strip moves at `scroll_speed` points per second. Copies sit exactly
 * one image-length apart, so shifting by that length looks identical: the offset is the distance
 * travelled MODULO that length and the loop never jumps.
 *
 * Mirrors iOS `AnimatedImageMarquee` and `src/lib/onboarding/animated-image.ts`. Pinned on both
 * platforms by the shared fixture `dto_parsing/animated_image_marquee`.
 */
object AnimatedImageMarquee {
    const val DEFAULT_SPEED = 30.0
    const val MIN_SPEED = 1.0
    const val MAX_SPEED = 400.0
    const val DEFAULT_HEIGHT = 200.0

    /** `field_config.scroll_direction`: left | right | up | down; anything else is left. */
    fun direction(raw: Any?): String = (raw as? String)?.takeIf { it in setOf("left", "right", "up", "down") } ?: "left"

    /** `field_config.scroll_speed` in points per second, clamped; unreadable is the default. */
    fun speed(raw: Any?): Double {
        val n = when (raw) {
            is Boolean -> Double.NaN
            is Number -> raw.toDouble()
            is String -> raw.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: Double.NaN
            else -> Double.NaN
        }
        if (!n.isFinite()) return DEFAULT_SPEED
        return n.coerceIn(MIN_SPEED, MAX_SPEED)
    }

    fun isVertical(direction: String): Boolean = direction == "up" || direction == "down"

    /** One copy's length along the scroll axis, from the image's natural size; 0 until it has loaded. */
    fun tileLength(direction: String, naturalW: Double, naturalH: Double, blockW: Double, blockH: Double): Double {
        if (!(naturalW > 0) || !(naturalH > 0)) return 0.0
        return if (isVertical(direction)) blockW * (naturalH / naturalW) else blockH * (naturalW / naturalH)
    }

    /**
     * The strip's translation along its axis after [elapsed] seconds. Left / up run 0 → −L, right /
     * down −L → 0, so the window is always covered from its leading edge.
     */
    fun offset(direction: String, speed: Double, tileLength: Double, elapsed: Double): Double {
        if (!(tileLength > 0) || !elapsed.isFinite()) return 0.0
        val travelled = (speed * maxOf(0.0, elapsed)) % tileLength
        val toward = if (direction == "left" || direction == "up") -travelled else travelled - tileLength
        val rounded = Math.round(toward * 1000).toDouble() / 1000
        return if (rounded == 0.0) 0.0 else rounded
    }

    /** Copies that cover a window [viewport] long while the strip moves by up to one copy. */
    fun copies(viewport: Double, tileLength: Double): Int {
        if (!(tileLength > 0)) return 0
        return maxOf(2, kotlin.math.ceil(viewport / tileLength).toInt() + 1)
    }
}

@Composable
internal fun AnimatedImageBlock(block: ContentBlock) {
    // Nothing to scroll draws nothing — as the image block does with no URL.
    val url = block.image_url?.takeIf { it.isNotBlank() } ?: return
    val context = LocalContext.current
    /*
     * 🔴 `Size.ORIGINAL`, or the image never loads. A Coil request with no size takes it from where
     * its painter is DRAWN, and this painter is only drawn once it has loaded (the strip needs its
     * intrinsic size to lay out the copies) — so it waited for a size that never came, and the block
     * was blank on every device. The strip sizes the copies itself, from the natural size.
     */
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context).data(url).size(coil.size.Size.ORIGINAL).build(),
        imageLoader = ai.appdna.sdk.core.AppDNAImageLoader.singleton(context),
    )
    val loaded = painter.state is AsyncImagePainter.State.Success
    val cfg = block.field_config
    AnimatedImageStrip(
        painter = if (loaded) painter else null,
        direction = AnimatedImageMarquee.direction(cfg?.get("scroll_direction")),
        speed = AnimatedImageMarquee.speed(cfg?.get("scroll_speed")),
        modifier = Modifier
            .fillMaxWidth()
            .height(maxOf(1.0, block.height ?: AnimatedImageMarquee.DEFAULT_HEIGHT).dp)
            .clip(RoundedCornerShape((block.corner_radius ?: 0.0).dp))
            // The same rule as every authored image: alt read as the image, blank alt decorative (no node at all).
            .then(Modifier.authoredImageSemantics(block.imageAccessibilityLabel)),
    )
}

/**
 * The moving strip, given a loaded painter. The clock is the frame clock ([withFrameNanos]), so a
 * Compose test advancing `mainClock` sees it move exactly as a device does.
 */
@Composable
internal fun AnimatedImageStrip(
    painter: Painter?,
    direction: String,
    speed: Double,
    modifier: Modifier = Modifier,
) {
    var startNanos by remember { mutableStateOf(-1L) }
    var nowNanos by remember { mutableStateOf(0L) }
    if (painter != null) {
        LaunchedEffect(painter, direction, speed) {
            while (true) {
                withFrameNanos { t ->
                    if (startNanos < 0) startNanos = t
                    nowNanos = t
                }
            }
        }
    }
    BoxWithConstraints(modifier = modifier) {
        if (painter == null) return@BoxWithConstraints
        val natural: Size = painter.intrinsicSize
        if (!natural.usable() || natural.width <= 0f || natural.height <= 0f) return@BoxWithConstraints
        val vertical = AnimatedImageMarquee.isVertical(direction)
        val w = maxWidth.value.toDouble()
        val h = maxHeight.value.toDouble()
        val tile = AnimatedImageMarquee.tileLength(direction, natural.width.toDouble(), natural.height.toDouble(), w, h)
        val n = AnimatedImageMarquee.copies(if (vertical) h else w, tile)
        val elapsed = if (startNanos < 0) 0.0 else (nowNanos - startNanos) / 1_000_000_000.0
        val density = LocalDensity.current
        val offPx = with(density) { AnimatedImageMarquee.offset(direction, speed, tile, elapsed).toFloat().dp.toPx() }
        val tileModifier = if (vertical) Modifier.size(maxWidth, tile.toFloat().dp) else Modifier.size(tile.toFloat().dp, maxHeight)
        Box(
            Modifier
                .testTag("animated_image_strip")
                .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                .graphicsLayer { if (vertical) translationY = offPx else translationX = offPx },
        ) {
            if (vertical) {
                Column {
                    repeat(n) { Image(painter, contentDescription = null, modifier = tileModifier, contentScale = ContentScale.FillBounds) }
                }
            } else {
                Row {
                    repeat(n) { Image(painter, contentDescription = null, modifier = tileModifier, contentScale = ContentScale.FillBounds) }
                }
            }
        }
    }
}

private fun Size.usable(): Boolean = this != Size.Unspecified && width.isFinite() && height.isFinite()
