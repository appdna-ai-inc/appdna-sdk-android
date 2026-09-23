package ai.appdna.sdk

import ai.appdna.sdk.onboarding.ContentBlock
import ai.appdna.sdk.onboarding.ContentBlockRendererView
import ai.appdna.sdk.onboarding.OnboardingConfigParser
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixels for the two WineTrails defects that only pixels can settle — #654/#659 and #609.
 *
 * `WineTrailsRenderGatesTest` asserts the RULES (`needsOuterAlignmentBox`, `multiButtonRowPlan`) and
 * the shared fixture asserts the row arithmetic, but neither can see whether the renderer actually
 * USES them. That gap is not hypothetical here: #654/#659 were reported precisely because the rule
 * was right and the composition order was wrong — the sizing box was applied first, so the
 * alignment was then resolved inside a box already shrunk to 75%, leaving nowhere to move. A test
 * that only checks the function would have stayed green through the entire bug.
 *
 * Record:  ./gradlew recordRoborazziDebug --tests "*WineTrailsRenderSnapshotTest*"
 * Compare: ./gradlew verifyRoborazziDebug   (the CI gate)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class WineTrailsRenderSnapshotTest {

    private fun blocksOf(vararg blocks: Map<String, Any>): List<ContentBlock> {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>("content_blocks" to blocks.toList()),
        )
        return OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()
    }

    private fun capture(name: String, blocks: List<ContentBlock>) {
        captureRoboImage("src/test/snapshots/$name.png") {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1117))
                        .padding(16.dp),
                ) {
                    ContentBlockRendererView(
                        blocks = blocks,
                        onAction = {},
                        toggleValues = mutableMapOf(),
                        inputValues = mutableMapOf(),
                    )
                }
            }
        }
    }

    private fun button(id: String, text: String) =
        mapOf<String, Any>("id" to id, "type" to "button", "text" to text, "action" to "next")

    // ── #654 / #659 — horizontal_align on a width-constrained block ───────────────────────────

    /**
     * The reported screen: a 75%-wide button authored `center`, which rendered hard left.
     * The golden must show clear background on BOTH sides of the button, in equal measure.
     */
    @Test
    fun constrainedWidthButton_centered() {
        capture(
            "winetrails_align_center_75pct",
            blocksOf(
                mapOf<String, Any>(
                    "id" to "cta", "type" to "button", "text" to "Regenerate Results", "action" to "next",
                    "element_width" to "75%", "horizontal_align" to "center",
                ),
            ),
        )
    }

    /** The same block aligned right — if the outer box were missing, this is pixel-identical to left. */
    @Test
    fun constrainedWidthButton_right() {
        capture(
            "winetrails_align_right_75pct",
            blocksOf(
                mapOf<String, Any>(
                    "id" to "cta", "type" to "button", "text" to "Audio Preview", "action" to "next",
                    "element_width" to "75%", "horizontal_align" to "right",
                ),
            ),
        )
    }

    /**
     * The control. An UNCONSTRAINED width must NOT gain the wrapper: the sizing box already holds a
     * full-width child, so the inner alignment still has the whole row. If a future change wraps
     * these too, this golden moves and says so.
     */
    @Test
    fun fullWidthButton_centeredIsUnchanged() {
        capture(
            "winetrails_align_center_fullwidth",
            blocksOf(
                mapOf<String, Any>(
                    "id" to "cta", "type" to "button", "text" to "Continue", "action" to "next",
                    "horizontal_align" to "center",
                ),
            ),
        )
    }

    // ── #609 — Multi-buttons ──────────────────────────────────────────────────────────────────

    /** Three buttons, two per row: the layout that was asked for, with the short row centred. */
    @Test
    fun multiButtons_twoThenOne_centered() {
        capture(
            "winetrails_multi_buttons_2_then_1_center",
            blocksOf(
                mapOf<String, Any>(
                    "id" to "group", "type" to "multi_buttons", "spacing" to 12,
                    "field_config" to mapOf<String, Any>("buttons_per_row" to 2, "last_row" to "center"),
                    "block_style" to mapOf<String, Any>("background_color" to "#1F2937", "border_radius" to 16),
                    "stack_children" to listOf(
                        button("b1", "Book a tasting"),
                        button("b2", "Audio tour"),
                        button("b3", "Guided tour"),
                    ),
                ),
            ),
        )
    }

    /** The same three with a stretched last row — the other half of the `last_row` setting. */
    @Test
    fun multiButtons_twoThenOne_stretched() {
        capture(
            "winetrails_multi_buttons_2_then_1_stretch",
            blocksOf(
                mapOf<String, Any>(
                    "id" to "group", "type" to "multi_buttons", "spacing" to 12,
                    "field_config" to mapOf<String, Any>("buttons_per_row" to 2, "last_row" to "stretch"),
                    "block_style" to mapOf<String, Any>("background_color" to "#1F2937", "border_radius" to 16),
                    "stack_children" to listOf(
                        button("b1", "Book a tasting"),
                        button("b2", "Audio tour"),
                        button("b3", "Guided tour"),
                    ),
                ),
            ),
        )
    }

    /** Three per row, evenly divided — no remainder row at all. */
    @Test
    fun multiButtons_threePerRow() {
        capture(
            "winetrails_multi_buttons_3_per_row",
            blocksOf(
                mapOf<String, Any>(
                    "id" to "group", "type" to "multi_buttons", "spacing" to 8,
                    "field_config" to mapOf<String, Any>("buttons_per_row" to 3),
                    "block_style" to mapOf<String, Any>("background_color" to "#1F2937", "border_radius" to 16),
                    "stack_children" to listOf(
                        button("b1", "Red"),
                        button("b2", "White"),
                        button("b3", "Rosé"),
                    ),
                ),
            ),
        )
    }
}
