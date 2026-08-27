package ai.appdna.sdk

import ai.appdna.sdk.onboarding.ContentBlockRendererView
import ai.appdna.sdk.onboarding.ContinuousProgressBar
import ai.appdna.sdk.onboarding.NavGlyph
import ai.appdna.sdk.onboarding.OnboardingConfigParser
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * SPEC-419 EPIC-1 (Select overhaul) — visual snapshot (surface #4: onboarding select).
 *
 * Renders a stacked input_select exercising the EPIC-1 per-option features:
 *   - leading_text + trailing_text on one row ("5 min/day … Easy")
 *   - a positionable badge ("RECOMMENDED")
 *   - per-option subtitle
 *
 * 100%-confirmation harness: Robolectric renders the REAL Compose UI on the JVM (no device,
 * no Firestore, no config cache); captureRoboImage writes the actual pixels. The golden is
 * written to a bridge-readable path so it can be pulled + reviewed. Run in record mode:
 *   ./gradlew recordRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SelectEpic1SnapshotTest {

    @Test
    fun stackedSelect_leadingTrailingBadge() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "sel1",
                        "type" to "input_select",
                        "field_config" to mapOf<String, Any>("display_style" to "stacked"),
                        "field_options" to listOf(
                            mapOf<String, Any>(
                                "id" to "o1", "label" to "Casual",
                                "leading_text" to "5 min/day", "trailing_text" to "Easy",
                                "badge" to mapOf<String, Any>(
                                    "text" to "RECOMMENDED", "bg_color" to "#22C55E",
                                    "text_color" to "#FFFFFF", "position" to "top_trailing",
                                ),
                            ),
                            mapOf<String, Any>(
                                "id" to "o2", "label" to "Regular",
                                "leading_text" to "10 min/day", "trailing_text" to "Steady",
                            ),
                            mapOf<String, Any>(
                                "id" to "o3", "label" to "Serious", "subtitle" to "Big goals",
                                "leading_text" to "15 min/day", "trailing_text" to "Hard",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/select_epic1.png") {
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
    fun stackedSelect_centerAligned() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "sel2",
                        "type" to "input_select",
                        "field_config" to mapOf<String, Any>("display_style" to "stacked"),
                        "field_options" to listOf(
                            mapOf<String, Any>("id" to "c1", "label" to "Beginner", "subtitle" to "Just starting out", "text_alignment" to "center"),
                            mapOf<String, Any>("id" to "c2", "label" to "Intermediate", "subtitle" to "Some experience", "text_alignment" to "center"),
                            mapOf<String, Any>("id" to "c3", "label" to "Advanced", "subtitle" to "Very experienced", "text_alignment" to "center"),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/select_center.png") {
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
    fun stackedSelect_imageOverlay() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "sel3",
                        "type" to "input_select",
                        "field_config" to mapOf<String, Any>("display_style" to "stacked"),
                        "field_options" to listOf(
                            mapOf<String, Any>("id" to "i1", "label" to "Circle", "image_url" to "https://example.com/a.png", "image_overlay_color" to "#FF5722", "image_overlay_opacity" to 0.85),
                            mapOf<String, Any>("id" to "i2", "label" to "Rounded", "image_url" to "https://example.com/b.png", "image_shape" to "rounded", "image_overlay_color" to "#2196F3", "image_overlay_opacity" to 0.85),
                            mapOf<String, Any>("id" to "i3", "label" to "Square", "image_url" to "https://example.com/c.png", "image_shape" to "square", "image_overlay_color" to "#22C55E", "image_overlay_opacity" to 0.85),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/select_overlay.png") {
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
    fun stackedSelect_selectedState() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "sel4",
                        "type" to "input_select",
                        "field_config" to mapOf<String, Any>("display_style" to "stacked"),
                        "field_style" to mapOf<String, Any>("fill_color" to "#22C55E"),
                        "field_options" to listOf(
                            mapOf<String, Any>("id" to "a", "value" to "a", "label" to "Casual", "subtitle" to "Easy pace"),
                            mapOf<String, Any>("id" to "b", "value" to "b", "label" to "Regular", "subtitle" to "Recommended"),
                            mapOf<String, Any>("id" to "c", "value" to "c", "label" to "Serious", "subtitle" to "Intense"),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/select_selected.png") {
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
                        inputValues = mutableMapOf("sel4" to "b"),
                    )
                }
            }
        }
    }

    @Test
    fun stackedSelect_selectedImageTint() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "sel5",
                        "type" to "input_select",
                        "field_config" to mapOf<String, Any>("display_style" to "stacked"),
                        "field_options" to listOf(
                            mapOf<String, Any>("id" to "p", "value" to "p", "label" to "Picked", "image_url" to "https://example.com/a.png", "image_overlay_color" to "#9CA3AF", "selected_image_overlay_color" to "#22C55E", "image_overlay_opacity" to 0.85, "selected_image_overlay_opacity" to 0.85),
                            mapOf<String, Any>("id" to "q", "value" to "q", "label" to "Other", "image_url" to "https://example.com/b.png", "image_overlay_color" to "#9CA3AF", "image_overlay_opacity" to 0.85),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/select_sel_imgtint.png") {
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
                        inputValues = mutableMapOf("sel5" to "p"),
                    )
                }
            }
        }
    }

    @Test
    fun imageTiles() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "tiles1",
                        "type" to "input_select",
                        "field_config" to mapOf<String, Any>("display_style" to "image_tiles", "grid_columns" to 2),
                        "field_style" to mapOf<String, Any>("fill_color" to "#FACC15"),
                        "field_options" to listOf(
                            mapOf<String, Any>("id" to "run", "value" to "run", "label" to "Running", "image_url" to "https://example.com/a.png", "image_overlay_color" to "#E11D48", "image_overlay_opacity" to 0.9),
                            mapOf<String, Any>("id" to "lift", "value" to "lift", "label" to "Lifting", "image_url" to "https://example.com/b.png", "image_overlay_color" to "#2563EB", "image_overlay_opacity" to 0.9),
                            mapOf<String, Any>("id" to "yoga", "value" to "yoga", "label" to "Yoga", "image_url" to "https://example.com/c.png", "image_overlay_color" to "#7C3AED", "image_overlay_opacity" to 0.9),
                            mapOf<String, Any>("id" to "swim", "value" to "swim", "label" to "Swimming", "image_url" to "https://example.com/d.png", "image_overlay_color" to "#059669", "image_overlay_opacity" to 0.9),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/select_tiles.png") {
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
                        inputValues = mutableMapOf("tiles1" to "lift"),
                    )
                }
            }
        }
    }

    @Test
    fun bubbleChips() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "bubble1",
                        "type" to "input_select",
                        "field_config" to mapOf<String, Any>("display_style" to "bubble"),
                        "field_style" to mapOf<String, Any>("fill_color" to "#22C55E", "text_color" to "#FFFFFF"),
                        "field_options" to listOf(
                            mapOf<String, Any>("id" to "running", "value" to "running", "label" to "Running"),
                            mapOf<String, Any>("id" to "yoga", "value" to "yoga", "label" to "Yoga"),
                            mapOf<String, Any>("id" to "cycling", "value" to "cycling", "label" to "Cycling"),
                            mapOf<String, Any>("id" to "swimming", "value" to "swimming", "label" to "Swimming"),
                            mapOf<String, Any>("id" to "boxing", "value" to "boxing", "label" to "Boxing"),
                            mapOf<String, Any>("id" to "pilates", "value" to "pilates", "label" to "Pilates"),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/select_bubble.png") {
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
                        inputValues = mutableMapOf("bubble1" to "running"),
                    )
                }
            }
        }
    }

    @Test
    fun listSeparators() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "list1",
                        "type" to "input_select",
                        "field_config" to mapOf<String, Any>("display_style" to "list"),
                        "field_style" to mapOf<String, Any>("fill_color" to "#3B82F6", "text_color" to "#FFFFFF"),
                        "field_options" to listOf(
                            mapOf<String, Any>("id" to "free", "value" to "free", "label" to "Free", "subtitle" to "Basic features"),
                            mapOf<String, Any>("id" to "plus", "value" to "plus", "label" to "Plus", "subtitle" to "More storage + priority support"),
                            mapOf<String, Any>("id" to "pro", "value" to "pro", "label" to "Pro", "subtitle" to "Everything, unlimited"),
                            mapOf<String, Any>("id" to "team", "value" to "team", "label" to "Team", "subtitle" to "For your whole organization"),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/select_list.png") {
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
                        inputValues = mutableMapOf("list1" to "plus"),
                    )
                }
            }
        }
    }

    @Test
    fun selectionGlow() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "glow1",
                        "type" to "input_select",
                        "field_config" to mapOf<String, Any>("display_style" to "stacked", "selection_animation" to "glow"),
                        "field_style" to mapOf<String, Any>("fill_color" to "#22C55E"),
                        "field_options" to listOf(
                            mapOf<String, Any>("id" to "a", "value" to "a", "label" to "Calm", "subtitle" to "Relaxing pace"),
                            mapOf<String, Any>("id" to "b", "value" to "b", "label" to "Focused", "subtitle" to "Steady progress"),
                            mapOf<String, Any>("id" to "c", "value" to "c", "label" to "Intense", "subtitle" to "Push hard"),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/select_glow.png") {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1117))
                        .padding(24.dp),
                ) {
                    ContentBlockRendererView(
                        blocks = blocks,
                        onAction = {},
                        toggleValues = mutableMapOf(),
                        inputValues = mutableMapOf("glow1" to "b"),
                    )
                }
            }
        }
    }

    @Test
    fun customFieldBorderFill() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "name", "type" to "input_text", "label" to "Full name", "field_placeholder" to "Jane Doe",
                        "field_style" to mapOf<String, Any>("border_color" to "#22C55E", "background_color" to "#1F2937", "text_color" to "#FFFFFF", "placeholder_color" to "#9CA3AF"),
                    ),
                    mapOf<String, Any>(
                        "id" to "email", "type" to "input_email", "label" to "Email", "field_placeholder" to "jane@example.com",
                        "field_style" to mapOf<String, Any>("border_color" to "#3B82F6", "background_color" to "#1F2937", "text_color" to "#FFFFFF", "placeholder_color" to "#9CA3AF"),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/field_borderfill.png") {
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
    fun progressGradient() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "pb1", "type" to "progress_bar",
                        "progress_variant" to "continuous", "total_segments" to 5, "filled_segments" to 4,
                        "bar_height" to 14, "corner_radius" to 7, "track_color" to "#374151",
                        "bar_gradient_colors" to listOf("#22C55E", "#EAB308", "#EF4444"),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/progress_gradient.png") {
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
    fun flowProgressThinGradient() {
        captureRoboImage("src/test/snapshots/flow_progress.png") {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1117))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    // Thin (2dp) solid — proves the bar can go thinner than Material's ~4dp floor.
                    ContinuousProgressBar(
                        progress = 0.6f,
                        color = Color(0xFF6366F1),
                        trackColor = Color(0xFF374151),
                        height = 2.dp,
                    )
                    // Thick (12dp) multi-color gradient.
                    ContinuousProgressBar(
                        progress = 0.8f,
                        color = Color(0xFF22C55E),
                        trackColor = Color(0xFF374151),
                        height = 12.dp,
                        gradientColors = listOf(Color(0xFF22C55E), Color(0xFFEAB308), Color(0xFFEF4444)),
                    )
                }
            }
        }
    }

    @Test
    fun navGlyphs() {
        captureRoboImage("src/test/snapshots/nav_glyphs.png") {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1117))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // EPIC-2 — custom back glyph (chevron), default arrow, and the back⇄X close glyph.
                    NavGlyph("‹", Color(0xFF6366F1), 28.sp) // custom chevron
                    NavGlyph("←", Color(0xFFE5E7EB), 20.sp) // default back arrow
                    NavGlyph("✕", Color(0xFFEF4444), 20.sp) // close (back⇄X switch)
                }
            }
        }
    }

    @Test
    fun flowProgressSkipBeside() {
        captureRoboImage("src/test/snapshots/progress_skip.png") {
            MaterialTheme {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1117))
                        .padding(vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(start = 16.dp),
                    ) {
                        ContinuousProgressBar(
                            progress = 0.5f,
                            color = Color(0xFF6366F1),
                            trackColor = Color(0xFF374151),
                            height = 6.dp,
                        )
                    }
                    Text(
                        text = "Skip",
                        modifier = Modifier.padding(start = 12.dp, end = 16.dp),
                        color = Color(0xFF9CA3AF),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }

    @Test
    fun phoneMockupFrame() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "img1", "type" to "image",
                        "image_url" to "https://example.com/screen.png",
                        "image_frame" to "phone", "height" to 420, "element_width" to "240px",
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/phone_mockup.png") {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFE5E7EB))
                        .padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
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
    fun loadingRadialRing() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "ld1", "type" to "animated_loading",
                        "loading_variant" to "ring", "progress_value" to 0.65,
                        "show_percentage" to true, "progress_color" to "#6366F1",
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/loading_ring.png") {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1117))
                        .padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
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
    fun loadingCogSpinner() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "ld2", "type" to "animated_loading",
                        "loading_variant" to "cog", "progress_color" to "#6366F1",
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/loading_cog.png") {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1117))
                        .padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
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
    fun loadingSplashBottom() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "ld3", "type" to "animated_loading",
                        "loading_variant" to "splash_bottom", "height" to 360, "progress_color" to "#6366F1",
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/loading_splash.png") {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1117)),
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
    fun loadingTextStyling() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "ld4", "type" to "animated_loading",
                        "loading_variant" to "ring", "progress_value" to 0.6,
                        "loading_text" to "Almost there", "loading_text_position" to "above",
                        "loading_text_size" to 24, "loading_text_color" to "#A5B4FC",
                        "progress_color" to "#6366F1",
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/loading_text.png") {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1117))
                        .padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
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
    fun mediaGallery() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "mg1", "type" to "media_gallery",
                        "gallery_images" to listOf(
                            "https://example.com/1.jpg",
                            "https://example.com/2.jpg",
                            "https://example.com/3.jpg",
                        ),
                        "gallery_item_width" to 105, "gallery_item_height" to 160,
                        "gallery_corner_radius" to 14, "gallery_spacing" to 10,
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/media_gallery.png") {
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
    fun sideBySideButtons() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "row1", "type" to "row", "row_child_fill" to true, "gap" to 12,
                        "children" to listOf(
                            mapOf<String, Any>(
                                "id" to "b1", "type" to "button", "text" to "Skip",
                                "bg_color" to "#2A2A2E", "text_color" to "#FFFFFF",
                                "button_corner_radius" to 14, "element_width" to "fill",
                            ),
                            mapOf<String, Any>(
                                "id" to "b2", "type" to "button", "text" to "Continue",
                                "bg_color" to "#6366F1", "text_color" to "#FFFFFF",
                                "button_corner_radius" to 14, "element_width" to "fill",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/side_by_side.png") {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1117))
                        .padding(20.dp),
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
    fun sectionBackground() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "sec1", "type" to "section_background", "height" to 420,
                        "field_config" to mapOf<String, Any>(
                            "content_arrangement" to "space_between",
                            "background_zones" to listOf(
                                mapOf<String, Any>("weight" to 2, "color" to "#1E1B4B"),
                                mapOf<String, Any>("weight" to 1, "color" to "#6366F1"),
                            ),
                        ),
                        "children" to listOf(
                            mapOf<String, Any>(
                                "id" to "t1", "type" to "text", "text" to "Welcome to AppDNA",
                                "style" to mapOf<String, Any>("font_size" to 26, "font_weight" to 700, "color" to "#FFFFFF"),
                            ),
                            mapOf<String, Any>(
                                "id" to "b1", "type" to "button", "text" to "Get Started",
                                "bg_color" to "#FFFFFF", "text_color" to "#1E1B4B",
                                "button_corner_radius" to 14, "element_width" to "fill",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/section_bg.png") {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1117)),
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
    fun selectGridMultiColumn() {
        // EPIC-1 — multi-column grid select (display_style "grid", grid_columns 2).
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "selg", "type" to "input_select",
                        "field_config" to mapOf<String, Any>("display_style" to "grid", "grid_columns" to 2),
                        "field_options" to listOf(
                            mapOf<String, Any>("id" to "a", "value" to "sleep", "label" to "Sleep", "subtitle" to "Better rest"),
                            mapOf<String, Any>("id" to "b", "value" to "focus", "label" to "Focus", "subtitle" to "Deep work"),
                            mapOf<String, Any>("id" to "c", "value" to "calm", "label" to "Calm", "subtitle" to "Less stress"),
                            mapOf<String, Any>("id" to "d", "value" to "energy", "label" to "Energy", "subtitle" to "More drive"),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/select_grid.png") {
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
    fun richTextInlineStyles() {
        // EPIC-9 — rich_text inline markdown: bold, italic, link + authored base_style color.
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "rt", "type" to "rich_text",
                        "markdown_content" to "This is **bold**, *italic*, and a [link](https://appdna.ai).",
                        "base_style" to mapOf<String, Any>("color" to "#E5E7EB"),
                        "link_color" to "#A5B4FC",
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/rich_text.png") {
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
    fun socialLoginProviders() {
        // EPIC-7 — social login provider buttons (Apple / Google / Email) with brand defaults.
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "sl", "type" to "social_login",
                        // Apple is intentionally iOS-only (hidden on Android), so use the
                        // cross-platform providers for a like-for-like parity snapshot.
                        "providers" to listOf(
                            mapOf<String, Any>("type" to "google", "label" to "Continue with Google"),
                            mapOf<String, Any>("type" to "email", "label" to "Continue with Email"),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/social_login.png") {
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
    fun socialLoginProviderFontSize() {
        // #560 — per-provider label size. The DTO fixture proves the number is DECODED; only a
        // snapshot proves it is DRAWN, which is the half a decode-only test would let ship broken.
        // Two providers at different sizes, so the golden fails on a renderer that reads the field
        // and then ignores it as much as on one that never reads it.
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "sl_size", "type" to "social_login",
                        "providers" to listOf(
                            mapOf<String, Any>("type" to "google", "label" to "Continue with Google", "font_size" to 22),
                            mapOf<String, Any>("type" to "email", "label" to "Continue with Email", "font_size" to 12),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/social_login_font_size.png") {
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
    fun carouselPaged() {
        // EPIC-8 — swipeable carousel: 3 pages + dot indicator (page 0 active). Snapshot = first page.
        val pageStyle = mapOf<String, Any>("font_size" to 24, "font_weight" to 700, "color" to "#FFFFFF")
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "car", "type" to "carousel", "height" to 120,
                        "children" to listOf(
                            mapOf<String, Any>("id" to "p1", "type" to "text", "text" to "Welcome to AppDNA", "style" to pageStyle),
                            mapOf<String, Any>("id" to "p2", "type" to "text", "text" to "Discover your insights", "style" to pageStyle),
                            mapOf<String, Any>("id" to "p3", "type" to "text", "text" to "Get started today", "style" to pageStyle),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/carousel.png") {
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
    fun pricingCardPlans() {
        // EPIC-10 — pricing plan cards: Monthly + Yearly (highlighted "BEST VALUE", accent border + badge).
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "pc", "type" to "pricing_card", "active_color" to "#6366F1",
                        "pricing_plans" to listOf(
                            mapOf<String, Any>("id" to "m", "label" to "Monthly", "price" to "$9.99", "period" to "per month"),
                            mapOf<String, Any>(
                                "id" to "y", "label" to "Yearly", "price" to "$59.99", "period" to "per year",
                                "badge" to "BEST VALUE", "is_highlighted" to true,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/pricing_card.png") {
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
    fun variablesConditional() {
        // EPIC-5 — variables + conditional logic: heading uses a {{responses.user_name}} template (carried
        // over from a prior step); two blocks gated by an age condition — "verified" shows (25 > 18), "too
        // young" hides. Proves binding/template carry-over + visibility_condition + dot-path resolution.
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "h", "type" to "heading", "horizontal_align" to "center", "text" to "Welcome back, {{responses.user_name}}!",
                        "style" to mapOf<String, Any>("font_size" to 26, "font_weight" to 700, "color" to "#FFFFFF", "alignment" to "center"),
                    ),
                    mapOf<String, Any>(
                        "id" to "ok", "type" to "text", "horizontal_align" to "center", "text" to "✓ Age verified — you're all set",
                        "visibility_condition" to mapOf<String, Any>("type" to "when_gt", "variable" to "responses.age", "value" to "18"),
                        "style" to mapOf<String, Any>("font_size" to 16, "font_weight" to 600, "color" to "#34D399", "alignment" to "center"),
                    ),
                    mapOf<String, Any>(
                        "id" to "no", "type" to "text", "horizontal_align" to "center", "text" to "✗ You must be 18 or older",
                        "visibility_condition" to mapOf<String, Any>("type" to "when_lt", "variable" to "responses.age", "value" to "18"),
                        "style" to mapOf<String, Any>("font_size" to 16, "font_weight" to 600, "color" to "#F87171", "alignment" to "center"),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/variables_conditional.png") {
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
                        responses = mapOf("user_name" to "Alex", "age" to "25"),
                    )
                }
            }
        }
    }

    @Test
    fun buttonHeightResize() {
        // EPIC-6 — authored button_height resizes the CTA itself (default ~52 vs tall 72), not the layout.
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>("id" to "b1", "type" to "button", "text" to "Continue", "bg_color" to "#6366F1"),
                    mapOf<String, Any>("id" to "b2", "type" to "button", "text" to "Get Started", "bg_color" to "#10B981", "button_height" to 72),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/button_height.png") {
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
    fun otpInput() {
        // EPIC-11 — OTP / code-input: 6 boxes, "1234" entered (4 filled + active 5th + empty 6th).
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "otp", "type" to "otp_input", "active_color" to "#6366F1",
                        "field_config" to mapOf<String, Any>("otp_length" to 6, "otp_value" to "1234"),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/otp_input.png") {
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
    fun warningBanner() {
        // EPIC-11 — warning/info banner variants: warning (amber) / error (red) / success (green).
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>("id" to "w", "type" to "warning_banner", "text" to "Your session is about to expire", "field_config" to mapOf<String, Any>("banner_variant" to "warning")),
                    mapOf<String, Any>("id" to "e", "type" to "warning_banner", "text" to "Passwords do not match", "field_config" to mapOf<String, Any>("banner_variant" to "error")),
                    mapOf<String, Any>("id" to "s", "type" to "warning_banner", "text" to "Email verified successfully", "field_config" to mapOf<String, Any>("banner_variant" to "success")),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/warning_banner.png") {
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
    fun passwordStrength() {
        // EPIC-11 — password-strength meter: weak (1/4 red) / good (3/4 yellow) / strong (4/4 green).
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>("id" to "p1", "type" to "password_strength", "field_config" to mapOf<String, Any>("strength_level" to 1)),
                    mapOf<String, Any>("id" to "p2", "type" to "password_strength", "field_config" to mapOf<String, Any>("strength_level" to 3)),
                    mapOf<String, Any>("id" to "p3", "type" to "password_strength", "field_config" to mapOf<String, Any>("strength_level" to 4)),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/password_strength.png") {
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
    fun speechBubble() {
        // EPIC-11 — speech bubble (mascot dialogue): white bubble + downward left tail.
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "sb", "type" to "speech_bubble",
                        "text" to "Great job! You're on a 7-day streak 🔥",
                        "bg_color" to "#FFFFFF", "text_color" to "#111827",
                        "field_config" to mapOf<String, Any>("bubble_tail" to "left"),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/speech_bubble.png") {
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
    fun feedbackPanel() {
        // EPIC-11 — quiz feedback panel: correct (green ✓) + wrong (red ✗), each headline + detail.
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>("id" to "fc", "type" to "feedback_panel", "text" to "Great job!", "field_config" to mapOf<String, Any>("feedback_state" to "correct", "feedback_detail" to "10-day streak kept 🔥")),
                    mapOf<String, Any>("id" to "fw", "type" to "feedback_panel", "text" to "Not quite", "field_config" to mapOf<String, Any>("feedback_state" to "wrong", "feedback_detail" to "Correct answer: Tokyo")),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/feedback_panel.png") {
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
    fun summaryScreen() {
        // EPIC-11 — session summary: headline + 2x2 stat grid (Time / Accuracy / XP / Streak).
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "sum", "type" to "summary_screen", "text" to "Lesson complete!",
                        "field_config" to mapOf<String, Any>(
                            "summary_stats" to listOf(
                                mapOf<String, Any>("value" to "5:32", "label" to "Time", "color" to "#6366F1"),
                                mapOf<String, Any>("value" to "92%", "label" to "Accuracy", "color" to "#10B981"),
                                mapOf<String, Any>("value" to "+120", "label" to "XP earned", "color" to "#F59E0B"),
                                mapOf<String, Any>("value" to "7", "label" to "Day streak", "color" to "#EF4444"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/summary_screen.png") {
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
    fun pressHoldConfirm() {
        // EPIC-11 — press-and-hold-to-confirm: pill 65% filled (left→right accent fill behind centered text).
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "ph", "type" to "press_hold_confirm", "text" to "Hold to confirm",
                        "active_color" to "#6366F1", "field_config" to mapOf<String, Any>("hold_progress" to 0.65),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/press_hold_confirm.png") {
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
    fun healthConnect() {
        // EPIC-11 — Health connect card. Provider is PLATFORM-FIXED: Android renders Google Fit (Apple Health
        // is iOS-only), so this golden intentionally differs from the iOS one. Two states: connect + connected.
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>("id" to "h1", "type" to "health_connect"),
                    mapOf<String, Any>("id" to "h2", "type" to "health_connect", "field_config" to mapOf<String, Any>("connected" to true)),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/health_connect.png") {
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
    fun settingsFooter() {
        // EPIC-11 — interactive footer: dark-mode capsule toggle (off/on) + language switcher pill.
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>("id" to "sf1", "type" to "settings_footer", "field_config" to mapOf<String, Any>("dark_mode" to false, "language" to "English")),
                    mapOf<String, Any>("id" to "sf2", "type" to "settings_footer", "active_color" to "#6366F1", "field_config" to mapOf<String, Any>("dark_mode" to true, "language" to "Español")),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/settings_footer.png") {
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
    fun memoryMatch() {
        // EPIC-11 — memory/pair-match: 3-col grid, all 3 states (up 🍎 / down ? / matched 🍌).
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "mm", "type" to "memory_match", "active_color" to "#6366F1",
                        "field_config" to mapOf<String, Any>(
                            "match_columns" to 3,
                            "match_cards" to listOf(
                                mapOf<String, Any>("symbol" to "🍎", "state" to "up"),
                                mapOf<String, Any>("state" to "down"),
                                mapOf<String, Any>("symbol" to "🍌", "state" to "matched"),
                                mapOf<String, Any>("state" to "down"),
                                mapOf<String, Any>("symbol" to "🍎", "state" to "up"),
                                mapOf<String, Any>("symbol" to "🍌", "state" to "matched"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/memory_match.png") {
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
    fun calendarMonth() {
        // EPIC-11 — month calendar: June 2026, days 12-14 selected (accent), today=15 (ring).
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "cal", "type" to "calendar_month", "active_color" to "#6366F1",
                        "field_config" to mapOf<String, Any>(
                            "month_label" to "June 2026", "days_in_month" to 30, "start_offset" to 1,
                            "selected_days" to listOf(12, 13, 14), "today" to 15,
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/calendar_month.png") {
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
    fun styleAlignment() {
        // EPIC-9 parity — heading + text centered via STYLE.alignment only (no horizontal_align). Was: Android
        // center / iOS left; now both center.
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>("id" to "h", "type" to "heading", "text" to "Centered Heading",
                        "style" to mapOf<String, Any>("font_size" to 24, "font_weight" to 700, "color" to "#FFFFFF", "alignment" to "center")),
                    mapOf<String, Any>("id" to "t", "type" to "text", "text" to "This body is centered via style.alignment",
                        "style" to mapOf<String, Any>("font_size" to 15, "color" to "#A5B4FC", "alignment" to "center")),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/style_alignment.png") {
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
}
