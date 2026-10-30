package ai.appdna.sdk.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A MAP INSIDE A STEP GETS THE DRAG, AND EVERYWHERE ELSE STILL SCROLLS.
 *
 * The customer's report was *"the Google map barely reacts to dragging. A pan moves it only a tiny
 * amount, and only when the gesture is picked up at all."* The cause is gesture arbitration, not
 * map configuration: `MapInteractive` already enables every `GoogleMap` gesture, but the content
 * column of `ThreeZoneBlockLayout` is a `Modifier.verticalScroll`, and a vertical drag that starts
 * on the embedded `MapView` is claimed by that scroll.
 *
 * 🔴 WHY A COMPOSE TEST AND NOT ONLY A DEVICE WALK. The Android sample's manifest carries no
 * `com.google.android.geo.API_KEY`, so on the emulator a map block renders the STATIC tier and the
 * interactive one is never built — the device cannot answer the question without a Maps key. What a
 * device also cannot show is the arbitration itself: a screenshot says the step moved, not which
 * modifier moved it. This drives the REAL `ThreeZoneBlockLayout` and the REAL
 * `Modifier.mapClaimsTheDrag`, which is the whole of the fix.
 *
 * 🔴 EVERY ASSERTION HERE HAS ITS CONTROL IN THE SAME CLASS. `scrolls when nothing claims the drag`
 * is what makes `does not scroll while a map holds the gesture` mean anything: without it, a
 * harness that cannot scroll at all would pass the second test for the worst possible reason —
 * which is exactly how an earlier first test passed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MapClaimsDragComposeTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * A step taller than the viewport, so its content column genuinely scrolls. `FIRST` is at the
     * top and is what the assertions watch: if the column scrolls, it moves up and off.
     */
    private fun tallBlocks(): List<ContentBlock> =
        listOf(ContentBlock(id = "b0", type = "heading", text = FIRST)) +
            // Spacers, not text: a text block's height depends on font metrics, and 61 of them came
            // to 16dp more than the viewport — a column that CAN scroll, by less than a finger's
            // width. The assertions need room the swipe cannot exhaust.
            (1..20).map { ContentBlock(id = "s$it", type = "spacer", spacer_height = 120.0) } +
            listOf(ContentBlock(id = "bN", type = "heading", text = LAST))

    private fun step(scrollFlag: androidx.compose.runtime.MutableState<Boolean>?) {
        compose.setContent {
            CompositionLocalProvider(LocalStepScrollEnabled provides scrollFlag) {
                ThreeZoneBlockLayout(
                    blocks = tallBlocks(),
                    onAction = {},
                    toggleValues = mutableMapOf(),
                    inputValues = mutableMapOf(),
                    loc = null,
                    currentStepIndex = 0,
                    totalSteps = 1,
                    modifier = Modifier.testTag(STEP),
                )
            }
        }
    }

    /**
     * Where the first block's top edge sits, in root coordinates.
     *
     * 🔴 ONLY MEANINGFUL WHILE THE NODE IS ON SCREEN. `getBoundsInRoot()` returns a zero rect for a
     * node the scroll has clipped away, and the first reading of this test took that 0.0 for "the
     * column scrolled by 16dp" — a number that looked like a measurement and was an absence. The
     * assertions below therefore say DISPLAYED or NOT DISPLAYED first, and compare positions only
     * in the case where the node is still there.
     */
    private fun topOfFirst(): Float = compose.onNodeWithText(FIRST).getBoundsInRoot().top.value

    // ── the layout half ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the step scrolls when nothing claims the drag`() {
        step(scrollFlag = null) // no provider — a map is not on screen, or the surface does not scroll
        // The fixture has to overflow or nothing here means anything: the last block must start off
        // screen, and the first must be on it.
        compose.onNodeWithText(FIRST).assertIsDisplayed()
        compose.onNodeWithText(LAST).assertIsNotDisplayed()

        compose.onNodeWithTag(STEP).performTouchInput { swipeUp() }
        compose.waitForIdle()

        compose.onNodeWithText(FIRST).assertIsNotDisplayed() // scrolled up and away
    }

    @Test
    fun `the step still scrolls while the flag is UP`() {
        // The steady state: a map is present but nobody is touching it.
        step(scrollFlag = mutableStateOf(true))
        compose.onNodeWithText(FIRST).assertIsDisplayed()
        compose.onNodeWithTag(STEP).performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithText(FIRST).assertIsNotDisplayed()
    }

    @Test
    fun `the step does not scroll while a map holds the gesture`() {
        step(scrollFlag = mutableStateOf(false)) // what a finger down on the map leaves behind
        val before = topOfFirst()
        compose.onNodeWithTag(STEP).performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithText(FIRST).assertIsDisplayed()
        assertEquals(
            "the step scrolled while a map was holding the drag — the map gets only the few pixels " +
                "the scroll leaves it, which is the defect verbatim",
            before,
            topOfFirst(),
            0.5f,
        )
    }

    // ── the map half: the real modifier ──────────────────────────────────────────────────────

    /** Renders a box carrying the production modifier, and returns the flag it drives. */
    private fun spyBox() = mutableStateOf(true).also { flag ->
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(200.dp).testTag(MAP).mapClaimsTheDrag(flag))
            }
        }
    }

    @Test
    fun `a finger down on the map lowers the flag, and lifting it raises it again`() {
        val flag = spyBox()
        assertTrue("nothing has been touched yet", flag.value)

        compose.onNodeWithTag(MAP).performTouchInput { down(center) }
        compose.waitForIdle()
        assertEquals("ACTION_DOWN on the map must stand the step's scroll down", false, flag.value)

        compose.onNodeWithTag(MAP).performTouchInput { up() }
        compose.waitForIdle()
        assertEquals("ACTION_UP must give the step its scroll back", true, flag.value)
    }

    @Test
    fun `a cancelled gesture raises the flag too`() {
        val flag = spyBox()
        compose.onNodeWithTag(MAP).performTouchInput { down(center) }
        compose.waitForIdle()
        assertEquals(false, flag.value)

        // A gesture the system takes away — a notification shade, an incoming call — never sends UP.
        compose.onNodeWithTag(MAP).performTouchInput { cancel() }
        compose.waitForIdle()
        assertEquals(
            "a cancelled gesture left the step unable to scroll, which is worse than the bug",
            true,
            flag.value,
        )
    }

    @Test
    fun `the spy does not consume the gesture - what is underneath still receives it`() {
        /*
         * 🔴 THE ASSERTION THE WHOLE FIX RESTS ON. If watching the gesture also took it, the map
         * would stop panning for a new reason and the step would stop scrolling as well. A child
         * that counts its own events proves the events still arrive.
         */
        val flag = mutableStateOf(true)
        var seen = 0
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .size(200.dp)
                        .testTag(MAP)
                        .mapClaimsTheDrag(flag)
                        .pointerInputCounter { seen += 1 },
                )
            }
        }
        compose.onNodeWithTag(MAP).performTouchInput { down(center); moveTo(center); up() }
        compose.waitForIdle()
        assertTrue("the spy swallowed the gesture — nothing reached the view underneath", seen > 0)
    }

    @Test
    fun `a null flag leaves the modifier inert`() {
        // The screens surface draws maps outside a scrolling step; there is nothing to yield to.
        compose.setContent {
            Box(Modifier.fillMaxSize()) { Box(Modifier.size(200.dp).testTag(MAP).mapClaimsTheDrag(null)) }
        }
        compose.onNodeWithTag(MAP).performTouchInput { down(center); up() }
        compose.waitForIdle() // the assertion is that this neither throws nor needs a flag
    }

    private companion object {
        const val FIRST = "Top of the step"
        const val STEP = "step"
        const val LAST = "Bottom of the step"
        const val MAP = "map"
    }
}

/** Counts pointer events reaching the node, without consuming them. */
private fun Modifier.pointerInputCounter(onEvent: () -> Unit): Modifier =
    this.then(
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent()
                    onEvent()
                }
            }
        },
    )
