package ai.appdna.sdk.onboarding

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.animate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
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
import androidx.compose.runtime.getValue

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
    // The sheet slides up from below on open (`enter`), and follows a downward drag (`drag`) — on the handle, or on
    // the content once it is scrolled to the top (the nested-scroll connection below). Released past a quarter of
    // the sheet's height, or flung down, it is a swipe dismissal; otherwise it springs back.
    val enter = remember(sheet.id) { Animatable(2000f) }
    LaunchedEffect(sheet.id) { enter.animateTo(0f, tween(260)) }
    val drag = remember(sheet.id) { mutableFloatStateOf(0f) }
    val sheetHeightPx = remember { floatArrayOf(1f) }
    val latestDismiss by androidx.compose.runtime.rememberUpdatedState(onDismiss)
    fun settle(velocityY: Float) {
        val past = drag.floatValue > sheetHeightPx[0] / 4f || velocityY > 2000f
        if (past && latestDismiss("swipe")) return
        scope.launch { animate(drag.floatValue, 0f, animationSpec = tween(180)) { v, _ -> drag.floatValue = v } }
    }
    val nested = remember(sheet.id) {
        object : NestedScrollConnection {
            // An upward drag first takes back a sheet that was pulled down.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f && drag.floatValue > 0f) {
                    val used = maxOf(available.y, -drag.floatValue)
                    drag.floatValue += used
                    return Offset(0f, used)
                }
                return Offset.Zero
            }
            // What the content could not scroll (it is at its top) pulls the sheet down.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && source == NestedScrollSource.Drag) {
                    drag.floatValue += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (drag.floatValue > 0f) { settle(available.y); return available }
                return Velocity.Zero
            }
        }
    }

    // Back closes the sheet (or is consumed when it is not dismissible). The activity overrides
    // onBackPressed, which bypasses the back dispatcher, so it asks this first.
    DisposableEffect(sheet.id) {
        StepSheetBack.handler = { latestDismiss("back") }
        onDispose { StepSheetBack.handler = null }
    }

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
                .offset { IntOffset(0, (enter.value + drag.floatValue).roundToInt().coerceAtLeast(0)) }
                .then(heightMod)
                .background(bg, shape)
                // A tap inside the sheet is not a scrim tap.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .testTag("step-sheet")
                .semantics { contentDescription = "step-sheet:${sheet.id}" }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta -> drag.floatValue = (drag.floatValue + delta).coerceAtLeast(0f) },
                    onDragStopped = { v -> settle(v) },
                )
                .nestedScroll(nested)
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

/** The open step sheet's Back, which [OnboardingActivity.onBackPressed] asks before closing the flow. */
internal object StepSheetBack {
    @Volatile var handler: (() -> Boolean)? = null
}
