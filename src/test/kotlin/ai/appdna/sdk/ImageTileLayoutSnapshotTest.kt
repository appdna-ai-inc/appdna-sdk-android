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
    private fun tilesStep(layoutConfig: Map<String, Any>, optionExtras: Map<String, Any> = emptyMap()): List<ContentBlock> {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "tiles_layout",
                        "type" to "input_select",
                        "field_id" to "winery",
                        "field_label" to "Pick a vineyard",
                        "field_config" to (
                            mapOf<String, Any>("display_style" to "image_tiles", "grid_columns" to 2) + layoutConfig
                            ),
                        "field_options" to listOf(
                            mapOf<String, Any>("id" to "w1", "label" to "Sunrise Vineyard", "subtitle" to "Lakeside", "image_url" to "https://example.com/a.png") + optionExtras,
                            mapOf<String, Any>("id" to "w2", "label" to "Southridge Vineyard", "subtitle" to "Highlands", "image_url" to "https://example.com/b.png") + optionExtras,
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

    /**
     * SPEC-447 AC — "in image_strip and contained the overlay covers the IMAGE REGION ONLY;
     * switching layout with a dark scrim set must not dim the text surface."
     *
     * The overlay used matchParentSize(), so it tinted the band as well and the authored
     * tile_surface_color came out muddied by a setting that is supposed to affect the photograph.
     * Nothing but pixels can show that: every key still parses, every renderer still draws, and
     * the tile still looks plausible. The scrim here is opaque black at 0.6 precisely so a
     * regression is unmissable in the diff rather than a subtle shade.
     */
    @Test
    fun imageTiles_stripOverlayDoesNotDimTheBand() {
        capture(
            "image_tiles_strip_overlay",
            tilesStep(
                mapOf(
                    "tile_image_layout" to "image_strip",
                    "tile_strip_ratio" to 0.75,
                    "tile_surface_color" to "#1F2937",
                ),
                optionExtras = mapOf("image_overlay_color" to "#000000", "image_overlay_opacity" to 0.6),
            ),
        )
    }

    /**
     * SPEC-446 §3 — a Summary Screen stat that HOSTS a control.
     *
     * The required-gate for these shipped without the rendering half on both platforms:
     * `RequiredFieldGate` blocked on an unanswered stat input while nothing ever drew one, so a
     * stat marked required could not be satisfied and the step could not be advanced. Every fixture
     * passed, because a fixture sets `inputValues` directly and never asks a renderer to produce the
     * control the user is supposed to touch. Only pixels can tell those two states apart.
     */
    /**
     * #581 / #580 — the new Image styles and the Sound Button icon.
     *
     * A fixture proves the keys are DECODED; only a snapshot proves they are DRAWN, and both
     * features are entirely visual. Deliberately the same four cases iOS records, so the pair can
     * be compared rather than each being checked against itself.
     */
    private fun blocksOf(vararg block: Map<String, Any>): List<ContentBlock> {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>("content_blocks" to block.toList()),
        )
        return OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()
    }

    @Test
    fun image_glowStyle() {
        capture("image_glow", blocksOf(mapOf(
            "id" to "img_glow", "type" to "image", "image_url" to "https://example.com/a.png",
            "image_frame" to "glow", "height" to 160, "corner_radius" to 16,
            "field_config" to mapOf("frame_glow_color" to "#F472B6"),
        )))
    }

    @Test
    fun image_colorFrameStyle() {
        capture("image_color_frame", blocksOf(mapOf(
            "id" to "img_cf", "type" to "image", "image_url" to "https://example.com/a.png",
            "image_frame" to "color_frame", "height" to 160,
            "field_config" to mapOf("frame_color" to "#F59E0B", "frame_corner_radius" to 24),
        )))
    }

    @Test
    fun image_phoneMockupThin() {
        capture("image_phone_thin", blocksOf(mapOf(
            "id" to "img_thin", "type" to "image", "image_url" to "https://example.com/a.png",
            "image_frame" to "phone_thin", "height" to 160,
        )))
    }

    @Test
    fun soundButton_playIcon() {
        capture("sound_button_icon", blocksOf(mapOf(
            "id" to "snd_icon", "type" to "sound_button", "text" to "Play sound",
            "audio_url" to "https://example.com/clip.mp3",
            "field_config" to mapOf(
                "sound_icon" to "play", "sound_icon_size" to 24,
                "sound_icon_color" to "#FDE047", "sound_icon_gap" to 12,
            ),
        )))
    }

    /**
     * #578 — the divider between two SPECIFIC providers, and the end slot beside it.
     *
     * A decode test cannot catch this: a renderer that reads the slot and then places the divider
     * at the end parses everything correctly. And the end case matters on its own — guarding it on
     * "not top" draws TWO dividers whenever an interior one is set, which only a picture shows.
     * Deliberately the same two cases iOS records, so the pair can be compared.
     */
    @Test
    fun socialLogin_dividerBetweenProviders() {
        capture("social_divider_between", blocksOf(mapOf(
            "id" to "social_div", "type" to "social_login",
            "show_divider" to true, "divider_text" to "or", "divider_position" to "after",
            "field_config" to mapOf("divider_after_index" to 1),
            "providers" to listOf(
                mapOf("type" to "apple", "label" to "Continue with Apple"),
                mapOf("type" to "google", "label" to "Continue with Google"),
                mapOf("type" to "email", "label" to "Continue with Email"),
            ),
        )))
    }

    @Test
    fun socialLogin_dividerAtBottom() {
        capture("social_divider_bottom", blocksOf(mapOf(
            "id" to "social_div_b", "type" to "social_login",
            "show_divider" to true, "divider_text" to "or", "divider_position" to "bottom",
            "providers" to listOf(
                mapOf("type" to "apple", "label" to "Continue with Apple"),
                mapOf("type" to "google", "label" to "Continue with Google"),
            ),
        )))
    }

    @Test
    fun summaryStat_rendersItsSliderControl() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "s", "analytics_name" to "s", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "sum_input",
                        "type" to "summary_screen",
                        "text" to "Your trip",
                        "field_config" to mapOf<String, Any>(
                            "stats_layout" to "vertical",
                            "summary_stats" to listOf(
                                mapOf<String, Any>(
                                    "label" to "Party size", "color" to "#6366F1",
                                    "input" to "slider", "field_id" to "party",
                                    "min" to 1, "max" to 30, "step" to 1, "default" to 4,
                                ),
                                mapOf<String, Any>("value" to "{{step.party}}", "label" to "Guests"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        capture("summary_stat_slider", OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList())
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
