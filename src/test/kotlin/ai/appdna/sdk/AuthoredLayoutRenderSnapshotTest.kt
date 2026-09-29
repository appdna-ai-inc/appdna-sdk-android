package ai.appdna.sdk

import ai.appdna.sdk.onboarding.ContentBlock
import ai.appdna.sdk.onboarding.ContentBlockRendererView
import ai.appdna.sdk.onboarding.MapBlock
import ai.appdna.sdk.onboarding.MapPlacement
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
 * Pixels for the two authored-layout defects that only pixels can settle — #654/#659 and #609.
 *
 * `AuthoredLayoutRenderGatesTest` asserts the RULES (`needsOuterAlignmentBox`, `multiButtonRowPlan`) and
 * the shared fixture asserts the row arithmetic, but neither can see whether the renderer actually
 * USES them. That gap is not hypothetical here: #654/#659 were reported precisely because the rule
 * was right and the composition order was wrong — the sizing box was applied first, so the
 * alignment was then resolved inside a box already shrunk to 75%, leaving nowhere to move. A test
 * that only checks the function would have stayed green through the entire bug.
 *
 * Record:  ./gradlew recordRoborazziDebug --tests "*AuthoredLayoutRenderSnapshotTest*"
 * Compare: ./gradlew verifyRoborazziDebug   (the CI gate)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AuthoredLayoutRenderSnapshotTest {

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

    /**
     * SPEC-495 — one map, captured through the PLACEMENT the step layout would give it.
     *
     * 🔴 The first version of the anchored golden went through `capture()`, which renders a block
     * list inside a padded column — so it drew the map INLINE, with 12dp corners and a 16dp gutter,
     * and the test was named `meetsBothEdges`. The picture disproved its own name and I nearly
     * committed it. A golden whose name describes something the pixels do not show is worse than no
     * golden: it is a false witness that outlives the person who recorded it.
     *
     * No padding here, because the whole claim is that an anchored map has none.
     */
    private fun capturePlacement(name: String, block: ContentBlock, placement: MapPlacement) {
        captureRoboImage("src/test/snapshots/$name.png") {
            MaterialTheme {
                Column(Modifier.fillMaxWidth().background(Color(0xFF0F1117))) {
                    MapBlock(block, placement)
                }
            }
        }
    }

    private fun button(id: String, text: String) =
        mapOf<String, Any>("id" to id, "type" to "button", "text" to text, "action" to "next")

    // ── #671 — a six-block image/heading/map/text/sound/CTA step, authored the way the console writes it ──

    /**
     * Reading code did not explain a report of a step whose screen was "frozen", so this renders a
     * step with the same SHAPE as the one reported rather than a minimal approximation of it: six
     * blocks (image, heading, map, text, sound button, button), their zones (`vertical_align` puts
     * the sound button in CENTER and the CTA in BOTTOM, the rest fall to TOP), and a map
     * `field_config` in which every numeric is a STRING ("220", "12", "28"), `map_stops` is empty,
     * and `map_interactive` is true. The content is synthetic.
     *
     * If composition throws, this test says so with the exception. If it renders, the golden shows
     * what an Android user actually sees.
     */
    private fun sixBlockMapStepBlocks(): List<ContentBlock> {
        val json = org.json.JSONArray(SIX_BLOCK_MAP_STEP_JSON)
        fun conv(v: Any?): Any? = when (v) {
            is org.json.JSONObject -> v.keys().asSequence().associateWith { conv(v.get(it)) }.filterValues { it != null }
            is org.json.JSONArray -> (0 until v.length()).map { conv(v.get(it)) }
            else -> v
        }
        @Suppress("UNCHECKED_CAST")
        val list = (0 until json.length()).map { conv(json.getJSONObject(it)) as Map<String, Any> }
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "Map summary", "analytics_name" to "map_summary", "skip_allowed" to false,
            "config" to mapOf<String, Any>("content_blocks" to list, "layout_variant" to "image_fullscreen"),
        )
        return OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()
    }

    @Test
    fun sixBlockMapStep_rendersWithoutThrowing() {
        capture("authored_layout_six_block_map_step", sixBlockMapStepBlocks())
    }

    // ── #671 — the map block's full-bleed crashed the whole Android screen ───────────────────

    /**
     * Reported: on Android a step containing a map was "frozen — nothing on it moves/scrolls"; on
     * iOS the same step scrolls fine. A map that merely failed to load could not freeze the rest of the step,
     * so the suspicion is composition, not the map.
     *
     * `map_full_bleed` cancels the step's horizontal padding so the map meets both screen edges.
     * iOS does it with `.padding(.horizontal, -20)`, which SwiftUI accepts. Android copied the idea
     * as `Modifier.padding(horizontal = (-20).dp)` — and Compose REQUIRES non-negative padding, so
     * that throws the moment the modifier is constructed, which is during composition of the step.
     *
     * If this test throws, that is the bug: one authored boolean takes down the entire screen.
     */
    @Test
    fun mapFullBleed_doesNotCrashTheStep() {
        capture(
            "authored_layout_map_full_bleed",
            blocksOf(
                mapOf<String, Any>(
                    "id" to "map1", "type" to "map",
                    "field_config" to mapOf<String, Any>("map_full_bleed" to true, "map_height" to 180.0),
                ),
                mapOf<String, Any>("id" to "cta", "type" to "button", "text" to "Continue", "action" to "next"),
            ),
        )
    }

    // ── SPEC-495 — the sizing modes, as pictures ──────────────────────────────────────────────
    //
    // 🔴 GOLDENS, BECAUSE EVERY MAP BUG IN THIS BATCH WAS A LAYOUT BUG. #671 was a negative inset
    // that a unit test would have called fine; the "frozen" report was a step that fits its viewport
    // and therefore has nothing to scroll. Neither is visible from an assertion about a number. A
    // picture is the only artefact that shows a map meeting both edges, or a step that lost its CTA
    // behind a backdrop.
    //
    // No token and no key here on purpose: the runner has neither, so what these capture is the
    // FALLBACK state — surface colour, corner radius, labelled text, and above all the geometry. The
    // geometry is the part that broke.

    /** A map pinned to the bottom edge: full width, square corners, no gutter on either side. */
    @Test
    fun mapAnchoredBottom_meetsBothEdges() {
        capturePlacement(
            "map_anchor_bottom",
            blocksOf(
                mapOf<String, Any>(
                    "id" to "map1", "type" to "map",
                    "field_config" to mapOf<String, Any>(
                        "map_anchor" to "bottom",
                        "map_height_mode" to "aspect",
                        "map_aspect" to "16:9",
                        "map_fallback_text" to "Map unavailable",
                    ),
                ),
            ).first(),
            MapPlacement.ANCHOR_BOTTOM,
        )
    }

    /**
     * A fullscreen map rendered INLINE — which is what a legacy step does with one, and what the
     * hoist exists to prevent. It must still be a map, not a crash and not a zero-height strip.
     */
    @Test
    fun mapFullscreen_rendersAsATallBlock() {
        capture(
            "map_fullscreen_inline",
            blocksOf(
                mapOf<String, Any>(
                    "id" to "map1", "type" to "map",
                    "field_config" to mapOf<String, Any>(
                        "map_height_mode" to "fullscreen",
                        "map_provider" to "google",
                        "map_theme" to "dark",
                        "map_fallback_text" to "Map unavailable",
                    ),
                ),
            ),
        )
    }

    /**
     * The authored theme colours reaching a surface a person can see. With no key the map itself
     * cannot draw, so this pins the one thing that IS under our control at that moment: the
     * fallback surface and its text, which is the state a keyless host actually ships.
     */
    @Test
    fun mapThemedFallback_usesAuthoredSurface() {
        capture(
            "map_themed_fallback",
            blocksOf(
                mapOf<String, Any>(
                    "id" to "map1", "type" to "map",
                    "field_config" to mapOf<String, Any>(
                        "map_provider" to "google",
                        "map_theme" to "dark",
                        "map_theme_water_color" to "#0B1F3A",
                        "map_surface_color" to "#1D2C4D",
                        "map_fallback_text" to "Map unavailable",
                        "map_fallback_text_color" to "#8EC3B9",
                        "map_height" to 160.0,
                        "map_corner_radius" to 20.0,
                    ),
                ),
            ),
        )
    }

    // ── #654 / #659 — horizontal_align on a width-constrained block ───────────────────────────

    /**
     * The reported screen: a 75%-wide button authored `center`, which rendered hard left.
     * The golden must show clear background on BOTH sides of the button, in equal measure.
     */
    @Test
    fun constrainedWidthButton_centered() {
        capture(
            "authored_layout_align_center_75pct",
            blocksOf(
                mapOf<String, Any>(
                    "id" to "cta", "type" to "button", "text" to "Refresh List", "action" to "next",
                    "element_width" to "75%", "horizontal_align" to "center",
                ),
            ),
        )
    }

    /** The same block aligned right — if the outer box were missing, this is pixel-identical to left. */
    @Test
    fun constrainedWidthButton_right() {
        capture(
            "authored_layout_align_right_75pct",
            blocksOf(
                mapOf<String, Any>(
                    "id" to "cta", "type" to "button", "text" to "Play Sample", "action" to "next",
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
            "authored_layout_align_center_fullwidth",
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
            "authored_layout_multi_buttons_2_then_1_center",
            blocksOf(
                mapOf<String, Any>(
                    "id" to "group", "type" to "multi_buttons", "spacing" to 12,
                    "field_config" to mapOf<String, Any>("buttons_per_row" to 2, "last_row" to "center"),
                    "block_style" to mapOf<String, Any>("background_color" to "#1F2937", "border_radius" to 16),
                    "stack_children" to listOf(
                        button("b1", "Book a session"),
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
            "authored_layout_multi_buttons_2_then_1_stretch",
            blocksOf(
                mapOf<String, Any>(
                    "id" to "group", "type" to "multi_buttons", "spacing" to 12,
                    "field_config" to mapOf<String, Any>("buttons_per_row" to 2, "last_row" to "stretch"),
                    "block_style" to mapOf<String, Any>("background_color" to "#1F2937", "border_radius" to 16),
                    "stack_children" to listOf(
                        button("b1", "Book a session"),
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
            "authored_layout_multi_buttons_3_per_row",
            blocksOf(
                mapOf<String, Any>(
                    "id" to "group", "type" to "multi_buttons", "spacing" to 8,
                    "field_config" to mapOf<String, Any>("buttons_per_row" to 3),
                    "block_style" to mapOf<String, Any>("background_color" to "#1F2937", "border_radius" to 16),
                    "stack_children" to listOf(
                        button("b1", "Small"),
                        button("b2", "Medium"),
                        button("b3", "Large"),
                    ),
                ),
            ),
        )
    }

    private companion object {
        // Synthetic content with the shape of a console-authored step: numerics as strings, empty stops.
        const val SIX_BLOCK_MAP_STEP_JSON = """[{"image_fit": "contain", "horizontal_align": "center", "type": "image", "height": "150", "image_url": "https://assets.example.com/onboarding/hero.png", "corner_radius": "12", "id": "img_1"}, {"style": {"color": "#F5F5F5", "font_size": "22", "font_weight": "700", "alignment": "center"}, "horizontal_align": "center", "id": "heading_1", "text": "Your plan is almost ready!", "type": "heading", "level": "1"}, {"id": "map_1", "type": "map", "field_config": {"route_width": "4", "map_fallback_text": "Map unavailable", "route_casing_width": "2", "map_stops": [], "map_surface_color": "#E5E7EB", "marker_style": "pin", "route_style": "solid", "map_height": "220", "marker_size": "28", "route_show": true, "map_fit_to_stops": true, "map_zoom": "12", "route_casing_color": "#FFFFFF", "map_center_lat": 40.7128, "map_center_lng": -74.006, "map_style": "streets", "map_corner_radius": "12", "map_interactive": true, "marker_color": "#F59E0B", "route_color": "#EF4444"}, "horizontal_align": "center"}, {"style": {"color": "#F5F5F5", "alignment": "center", "font_size": "16", "font_weight": "500"}, "text": "Listen to a short sample", "type": "text", "horizontal_align": "center", "id": "text_1"}, {"style": {"alignment": "center", "color": "#F5F5F5", "font_size": "16", "font_weight": "600"}, "autoplay": false, "icon_emoji": "🔊", "element_width": "75%", "audio_url": "", "id": "sound_1", "text_color": "#ffffff", "element_height": "50px", "bg_color": "#6366F1", "horizontal_align": "center", "vertical_align": "center", "type": "sound_button", "vertical_offset": "0", "text": "{{hook_data.sample_url}}", "zone": "center", "button_corner_radius": "24", "variant": "primary"}, {"variant": "primary", "id": "cta_1", "button_corner_radius": "24", "zone": "bottom", "vertical_offset": "0", "text": "Continue", "style": {"color": "#111827", "alignment": "center", "font_size": "16", "font_weight": "600"}, "type": "button", "text_color": "#111827", "horizontal_align": "center", "bg_color": "#F59E0B", "vertical_align": "bottom", "element_width": "fill", "action": "next"}]"""
    }

}
