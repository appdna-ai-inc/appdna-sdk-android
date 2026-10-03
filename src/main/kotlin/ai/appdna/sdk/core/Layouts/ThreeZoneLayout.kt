package ai.appdna.sdk.core.Layouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import ai.appdna.sdk.onboarding.ContentBlock

/**
 * `ThreeZoneLayout` partitions a step's [ContentBlock]
 * list into three vertical zones — top, center, bottom — and spaces them
 * with weighted spacers so each zone retains its visual region as the
 * screen height changes.
 *
 * Mirrors iOS `Onboarding/ThreeZoneStepLayout.swift:109-122`. Empty zones
 * collapse so the renderer doesn't introduce phantom whitespace; non-empty
 * zones each carry a weight of 1 by default, matching iOS Layout's
 * "GeometryReader { proxy in VStack { … } }" partition.
 *
 * The zone for each block is decided by:
 *   1. `block.zone` (explicit "top" / "center" / "bottom"),
 *   2. else `block.vertical_align` (legacy field, same vocabulary),
 *   3. else default to "center".
 *
 * Hosts use this when `step.layout == "three_zone"` (or whatever flag the
 * outer renderer chooses). For non-three_zone steps the existing
 * `BlockBasedStepView` continues to render inline.
 *
 * @param blocks  All step blocks (already filtered for visibility).
 * @param renderBlock Callback the layout invokes for each block — keeps
 *                    rendering decisions in the host (style, action wiring)
 *                    while this layout owns only the zoning.
 * @param topAlignment Alignment within the top zone (default = TopCenter).
 * @param centerAlignment Alignment within the center zone (default = Center).
 * @param bottomAlignment Alignment within the bottom zone (default = BottomCenter).
 */
@Composable
fun ThreeZoneLayout(
    blocks: List<ContentBlock>,
    renderBlock: @Composable (ContentBlock) -> Unit,
    modifier: Modifier = Modifier,
    topAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    centerAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    bottomAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
) {
    val partitioned = blocks.groupBy { resolveZone(it) }
    val top = partitioned[Zone.TOP].orEmpty()
    val center = partitioned[Zone.CENTER].orEmpty()
    val bottom = partitioned[Zone.BOTTOM].orEmpty()

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Top zone — only carries weight when content present.
        if (top.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = topAlignment,
            ) {
                top.forEach { renderBlock(it) }
            }
        } else {
            Spacer(Modifier.weight(0.0001f))
        }

        // Center zone fills remaining vertical space when any blocks are
        // assigned to it. Empty center collapses, mirroring iOS.
        if (center.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = centerAlignment,
            ) {
                center.forEach { renderBlock(it) }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        // Bottom zone — only carries weight when content present.
        if (bottom.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = bottomAlignment,
            ) {
                bottom.forEach { renderBlock(it) }
            }
        } else {
            Spacer(Modifier.weight(0.0001f))
        }
    }
}

private enum class Zone { TOP, CENTER, BOTTOM }

private fun resolveZone(block: ContentBlock): Zone {
    val raw = (block.zone ?: block.vertical_align)?.lowercase()
    return when (raw) {
        "top" -> Zone.TOP
        "bottom" -> Zone.BOTTOM
        else -> Zone.CENTER
    }
}
