package ai.appdna.sdk.onboarding

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A button with `cta_dependencies` in the shipping renderer: a tap does nothing while a condition fails, the button
 * turns active as the user answers, and then the tap is the button's action.
 * THE CONTROL: the same screen with no dependencies taps straight through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CtaDependenciesRenderTest {
    @get:Rule val compose = createComposeRule()
    private val inputValues = mutableStateMapOf<String, Any>()
    private val actions = mutableListOf<String>()

    @Before fun reset() = SelectedOptionStore.resetForTesting()

    private fun render(buttonConfig: Map<String, Any>) {
        val blocks = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s", "type" to "custom",
                "config" to mapOf(
                    "content_blocks" to listOf(
                        mapOf(
                            "id" to "terms", "type" to "input_select", "field_id" to "terms",
                            "field_config" to mapOf("display_style" to "list", "multi_select" to true),
                            "field_options" to listOf(
                                mapOf("id" to "a", "value" to "a", "label" to "Alpha"),
                                mapOf("id" to "b", "value" to "b", "label" to "Bravo"),
                            ),
                        ),
                        mapOf(
                            "id" to "quiz", "type" to "input_select", "field_id" to "quiz",
                            "field_config" to mapOf("display_style" to "list", "correct_values" to listOf("y")),
                            "field_options" to listOf(
                                mapOf("id" to "x", "value" to "x", "label" to "Xray"),
                                mapOf("id" to "y", "value" to "y", "label" to "Yankee"),
                            ),
                        ),
                        mapOf("id" to "cta", "type" to "button", "text" to "Continue", "action" to "next", "field_config" to buttonConfig),
                    ),
                ),
            ),
        )!!.config.content_blocks!!
        compose.setContent {
            ContentBlockRendererView(blocks = blocks, onAction = { actions.add(it) }, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
    }

    private fun tap(label: String) {
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    @Test fun inactiveUntilEveryOptionIsPickedThenTheTapAdvances() {
        render(mapOf("cta_dependencies" to listOf(mapOf("block_id" to "terms", "condition" to "all_selected"))))
        compose.onNodeWithTag("cta-inactive", useUnmergedTree = true).assertExists()
        tap("Continue")
        assertEquals(emptyList<String>(), actions)
        tap("Alpha")
        tap("Continue")
        assertEquals("one of two is not all", emptyList<String>(), actions)
        tap("Bravo")
        compose.onNodeWithTag("cta-active", useUnmergedTree = true).assertExists()
        tap("Continue")
        assertEquals(listOf("next"), actions)
    }

    @Test fun twoConditionsBothHaveToHold() {
        render(mapOf("cta_dependencies" to listOf(
            mapOf("block_id" to "terms", "condition" to "answered"),
            mapOf("block_id" to "quiz", "condition" to "correct"),
        )))
        tap("Alpha")
        tap("Xray")
        tap("Continue")
        assertEquals("the wrong answer keeps it inactive", emptyList<String>(), actions)
        tap("Yankee")
        tap("Continue")
        assertEquals(listOf("next"), actions)
    }

    @Test fun theControlWithNoDependenciesTapsStraightThrough() {
        render(emptyMap())
        compose.onAllNodesWithTag("cta-inactive", useUnmergedTree = true).assertCountEquals(0)
        tap("Continue")
        assertEquals(listOf("next"), actions)
    }
}
