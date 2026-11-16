package ai.appdna.sdk.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A sheet inside a step, drawn INSIDE the step's composable: the step stays drawn under the scrim and gets no
 * taps; the sheet sits at the bottom with its corner radius, drag indicator and height. Not a
 * `ModalBottomSheet`: a separate window would put the step's own validation pill under it, and the scrim,
 * height and dismiss routing would be Material's rather than the authored ones.
 *
 * The sheet's blocks go through the step's own renderer with the STEP's answer maps, so what is ticked here
 * is the step's answer at once. [onAction] receives each button's encoded action ([OnboardingActionPair]);
 * [onDismiss] a gesture (`swipe` / `scrim` / `back`) and returns whether it closed (false when the sheet is
 * not dismissible, in which case back is still consumed).
 */
@Composable
internal fun StepSheetOverlay(
    sheet: StepSheet,
    stepBackgroundHex: String?,
    stepGateBlocks: List<ContentBlock>,
    toggleValues: MutableMap<String, Boolean>,
    inputValues: MutableMap<String, Any>,
    loc: (String, String) -> String,
    responses: Map<String, Any>,
    hookData: Map<String, Any>?,
    onAction: (String) -> Unit,
    onDismiss: (via: String) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // The sheet slides up from below on open, and back down by a drag; released past a quarter of its
    // height it is a swipe dismissal, otherwise it springs back.
    val offsetY = remember(sheet.id) { Animatable(2000f) }
    LaunchedEffect(sheet.id) { offsetY.animateTo(0f, tween(260)) }
    val sheetHeightPx = remember { floatArrayOf(1f) }

    BackHandler(enabled = true) { onDismiss("back") }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag("step-sheet-scrim")
            .background(ai.appdna.sdk.core.StyleEngine.parseColor(sheet.scrim_color))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss("scrim") },
    ) {
        val maxH = maxHeight
        val heightMod = when (sheet.height) {
            "medium" -> Modifier.height(maxH * 0.5f)
            "large" -> Modifier.height(maxH * 0.9f)
            else -> Modifier.heightIn(max = maxH * 0.9f)
        }
        val bg = (sheet.background_color ?: stepBackgroundHex)?.let { ai.appdna.sdk.core.StyleEngine.parseColor(it) } ?: Color.White
        val shape = RoundedCornerShape(topStart = sheet.corner_radius.dp, topEnd = sheet.corner_radius.dp)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .offset { IntOffset(0, offsetY.value.roundToInt().coerceAtLeast(0)) }
                .then(heightMod)
                .background(bg, shape)
                // A tap inside the sheet is not a scrim tap.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .testTag("step-sheet")
                .semantics { contentDescription = "step-sheet:${sheet.id}" }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        scope.launch { offsetY.snapTo((offsetY.value + delta).coerceAtLeast(0f)) }
                    },
                    onDragStopped = {
                        val past = offsetY.value > sheetHeightPx[0] / 4f
                        if (past && onDismiss("swipe")) return@draggable
                        offsetY.animateTo(0f, tween(180))
                    },
                )
                .navigationBarsPadding()
                .imePadding(),
        ) {
            sheetHeightPx[0] = with(density) { maxH.toPx() } * 0.5f
            if (sheet.drag_indicator) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 8.dp, bottom = 4.dp)
                        .size(width = 36.dp, height = 5.dp)
                        .background(Color(0x66808080), RoundedCornerShape(3.dp))
                        .testTag("step-sheet-indicator"),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 24.dp),
            ) {
                ContentBlockRendererView(
                    blocks = sheet.content_blocks,
                    onAction = onAction,
                    toggleValues = toggleValues,
                    inputValues = inputValues,
                    loc = loc,
                    responses = responses,
                    hookData = hookData,
                    stepBlocks = stepGateBlocks,
                )
            }
        }
    }
}
