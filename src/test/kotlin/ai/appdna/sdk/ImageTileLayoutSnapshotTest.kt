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
 * SPEC-441 (#541) — Android pixels for the Select's category chip row.
 *
 * The counterpart to iOS `VisualSnapshotTests.testSelectCategoryChips_firstChipActive`, with the
 * SAME config so the two goldens are directly comparable. This is the screen the reporter sent:
 * a scrollable chip row above the options, the active chip filtering the list, and a header
 * echoing it.
 *
 * Two things only pixels can show, and both are the point of the feature:
 *   - the options are actually FILTERED to the active chip (`Air Horn` is in `loud`, so it must
 *     be absent while Trending is active)
 *   - an option with NO category still shows, which is what makes adding chips to an existing
 *     Select non-destructive
 *
 * Record:  ./gradlew recordRoborazziDebug --tests "*ImageTileLayoutSnapshotTest*"
 * Compare: ./gradlew verifyRoborazziDebug   (the CI gate)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ImageTileLayoutSnapshotTest {

    /**
     * SPEC-447 (#555) — the two layouts that move the text OFF the image.
     *
     * A dto_parsing fixture proves the keys reach the model and NOT that a renderer honours them:
     * I deleted Android's `tile_image_layout` read and the fixture stayed green. Only pixels close
     * that gap, and only per-platform pixels close it on the platform that regressed — an iOS
     * golden cannot see an Android renderer ignoring a key.
     */
    private fun tilesStep(layoutConfig: Map<String, Any>): List<ContentBlock> {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "tiles_layout",
                        "type" to "input_select",
                        "field_id" to "winery",
                        "field_label" to "Wybierz winnicę",
                        "field_config" to (
                            mapOf<String, Any>("display_style" to "image_tiles", "grid_columns" to 2) + layoutConfig
                            ),
                        "field_options" to listOf(
                            mapOf<String, Any>("id" to "w1", "label" to "Winnica Wschód", "subtitle" to "Dolny Śląsk", "image_url" to "https://example.com/a.png"),
                            mapOf<String, Any>("id" to "w2", "label" to "Winnica Południe", "subtitle" to "Małopolska", "image_url" to "https://example.com/b.png"),
                        ),
                    ),
                ),
            ),
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

    @Test
    fun imageTiles_imageStrip() {
        capture(
            "image_tiles_strip",
            tilesStep(
                mapOf(
                    "tile_image_layout" to "image_strip",
                    "tile_strip_ratio" to 0.75,
                    "tile_surface_color" to "#1F2937",
                ),
            ),
        )
    }

    @Test
    fun imageTiles_contained() {
        capture(
            "image_tiles_contained",
            tilesStep(
                mapOf(
                    "tile_image_layout" to "contained",
                    "tile_strip_ratio" to 0.7,
                    "tile_surface_color" to "#1F2937",
                    "tile_image_inset" to 10,
                    "tile_image_frame_width" to 2,
                    "tile_image_frame_color" to "#F59E0B",
                ),
            ),
        )
    }
}
