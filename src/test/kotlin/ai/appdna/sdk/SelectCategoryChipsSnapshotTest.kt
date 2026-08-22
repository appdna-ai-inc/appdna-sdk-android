package ai.appdna.sdk

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
 * Record:  ./gradlew recordRoborazziDebug --tests "*SelectCategoryChipsSnapshotTest*"
 * Compare: ./gradlew verifyRoborazziDebug   (the CI gate)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SelectCategoryChipsSnapshotTest {

    @Test
    fun categoryChips_firstChipActive() {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "sel_chips",
                        "type" to "input_select",
                        "field_id" to "sound",
                        "field_label" to "Wybierz własny dźwięk alarmu",
                        "field_config" to mapOf<String, Any>(
                            "display_style" to "stacked",
                            "category_header" to true,
                            "categories" to listOf(
                                mapOf<String, Any>("id" to "trending", "label" to "Trending", "icon" to "💖"),
                                mapOf<String, Any>("id" to "loud", "label" to "Loud", "icon" to "💥"),
                                mapOf<String, Any>("id" to "alarm", "label" to "Alarm tone", "icon" to "🔔"),
                                mapOf<String, Any>("id" to "classic", "label" to "Classical", "icon" to "🎻"),
                            ),
                        ),
                        "field_options" to listOf(
                            mapOf<String, Any>("id" to "o1", "label" to "Wake up you lazy", "category" to "trending"),
                            mapOf<String, Any>("id" to "o2", "label" to "You're gonna be late", "category" to "trending"),
                            mapOf<String, Any>("id" to "o3", "label" to "Rise and Shine Mothertrucker", "category" to "trending"),
                            // In `loud` — must NOT appear while Trending is active.
                            mapOf<String, Any>("id" to "o4", "label" to "Air Horn", "category" to "loud"),
                            // No category — must appear under EVERY chip.
                            mapOf<String, Any>("id" to "o5", "label" to "Available under every chip"),
                        ),
                    ),
                ),
            ),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        captureRoboImage("src/test/snapshots/select_category_chips.png") {
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
