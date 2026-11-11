package ai.appdna.sdk.onboarding

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An author's default answer (`field_config.default_value`) on the shipping Select and Segmented renderers: drawn
 * preselected and WRITTEN as the answer the moment the question shows, as a tap writes it, so a later summary draws the
 * option's label. A restored answer wins, a tap replaces it, and with no default nothing is written (the control).
 * Synthetic shapes only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SelectDefaultAnswerRenderTest {
    @get:Rule val compose = createComposeRule()
    private val inputValues = mutableStateMapOf<String, Any>()

    @Before fun reset() = SelectedOptionStore.resetForTesting()

    private fun render(type: String, fieldConfig: Map<String, Any>, extra: Map<String, Any> = emptyMap()) {
        val blocks = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s", "type" to "custom",
                "config" to mapOf(
                    "content_blocks" to listOf(
                        mapOf(
                            "id" to "m", "type" to type, "field_id" to "fav",
                            "field_config" to fieldConfig,
                            "field_options" to listOf(
                                mapOf("id" to "a", "value" to "a", "label" to "Alpha"),
                                mapOf("id" to "b", "value" to "b", "label" to "Bravo"),
                                mapOf("id" to "n", "value" to "2", "label" to "Two"),
                            ),
                        ) + extra,
                    ),
                ),
            ),
        )!!.config.content_blocks!!
        compose.setContent {
            ContentBlockRendererView(blocks = blocks, onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
        compose.waitForIdle()
    }

    private fun drawn(): String? = SelectedOptionStore.drawnAnswer("fav", inputValues["fav"].toString(), SelectedOptionStore.drawingRecords())

    @Test fun aSingleDefaultIsWrittenAndDrawsItsLabel() {
        render("input_select", mapOf("display_style" to "list", "default_value" to "b"))
        assertEquals("b", inputValues["fav"])
        // Recorded as a tap records it: the label is drawn with no flow option list set.
        assertEquals("Bravo", drawn())
    }

    @Test fun aRequiredQuestionWithADefaultIsAnswered() {
        render("input_select", mapOf("display_style" to "stacked", "default_value" to "b"), mapOf("field_required" to true))
        assertEquals("b", inputValues["fav"])
    }

    @Test fun aTapReplacesTheDefault() {
        render("input_select", mapOf("display_style" to "list", "default_value" to "b"))
        compose.onNodeWithText("Alpha").performClick()
        compose.waitForIdle()
        assertEquals("a", inputValues["fav"])
        assertEquals("Alpha", drawn())
    }

    @Test fun aRestoredAnswerWins() {
        inputValues["fav"] = "a"
        render("input_select", mapOf("display_style" to "list", "default_value" to "b"))
        assertEquals("a", inputValues["fav"])
    }

    @Test fun aNumberDefaultNamesTheOptionWithThatValue() {
        render("input_select", mapOf("display_style" to "list", "default_value" to 2))
        assertEquals("2", inputValues["fav"])
    }

    @Test fun aMultiDefaultKeepsTheAuthoredOrder() {
        render("input_select", mapOf("display_style" to "stacked", "multi_select" to true, "default_value" to listOf("b", "missing", "a")))
        assertEquals(listOf("b", "a"), inputValues["fav"])
    }

    @Test fun aRequiredSegmentedWithADefaultStartsOnIt() {
        render("input_segmented", mapOf("default_value" to "b"), mapOf("field_required" to true))
        assertEquals("b", inputValues["fav"])
        assertEquals("Bravo", drawn())
    }

    @Test fun anOptionalSegmentedDefaultBeatsTheFirstOption() {
        render("input_segmented", mapOf("default_value" to "2"))
        assertEquals("2", inputValues["fav"])
    }

    @Test fun controlNoDefaultWritesNothing() {
        render("input_select", mapOf("display_style" to "list"))
        assertFalse(inputValues.containsKey("fav"))
    }

    @Test fun controlARequiredSegmentedWithNoDefaultStartsEmpty() {
        render("input_segmented", emptyMap(), mapOf("field_required" to true))
        assertFalse(inputValues.containsKey("fav"))
    }
}
