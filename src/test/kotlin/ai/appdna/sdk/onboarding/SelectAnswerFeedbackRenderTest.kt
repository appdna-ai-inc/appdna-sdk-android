package ai.appdna.sdk.onboarding

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tapping an option in the shipping Select renderer draws the right/wrong message under the options when the block
 * marks a correct answer, and changes it when the user picks again.
 * THE CONTROL: the same block without `correct_values` taps and draws no message at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SelectAnswerFeedbackRenderTest {
    @get:Rule val compose = createComposeRule()
    private val inputValues = mutableStateMapOf<String, Any>()

    @Before fun reset() = SelectedOptionStore.resetForTesting()

    private fun render(fieldConfig: Map<String, Any>, displayStyle: String = "list") {
        val blocks = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s", "type" to "custom",
                "config" to mapOf(
                    "content_blocks" to listOf(
                        mapOf(
                            "id" to "q", "type" to "input_select", "field_id" to "sound",
                            "field_config" to (mapOf("display_style" to displayStyle) + fieldConfig),
                            "field_options" to listOf(
                                mapOf("id" to "a", "value" to "a", "label" to "Alpha"),
                                mapOf("id" to "b", "value" to "b", "label" to "Bravo"),
                            ),
                        ),
                    ),
                ),
            ),
        )!!.config.content_blocks!!
        compose.setContent {
            ContentBlockRendererView(blocks = blocks, onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
    }

    private val marked = mapOf(
        "correct_values" to listOf("b"),
        "feedback_correct_text" to "Excellent!", "feedback_wrong_text" to "Try again",
    )

    @Test fun nothingShowsBeforeThePick() {
        render(marked)
        compose.onAllNodesWithTag("select-answer-feedback", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun theRightPickSaysSo() {
        render(marked)
        compose.onNodeWithText("Bravo").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("select-answer-feedback", useUnmergedTree = true).assertIsDisplayed().assertTextEquals("Excellent!")
    }

    @Test fun aWrongPickSaysSoAndARepickChangesIt() {
        render(marked)
        compose.onNodeWithText("Alpha").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("select-answer-feedback", useUnmergedTree = true).assertTextEquals("Try again")
        compose.onNodeWithText("Bravo").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("select-answer-feedback", useUnmergedTree = true).assertTextEquals("Excellent!")
    }

    @Test fun theDefaultTextsShowWhenNoneIsAuthored() {
        render(mapOf("correct_values" to listOf("b")))
        compose.onNodeWithText("Alpha").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("select-answer-feedback", useUnmergedTree = true).assertTextEquals(SelectAnswerFeedback.DEFAULT_WRONG_TEXT)
    }

    @Test fun theControlWithNoCorrectAnswerDrawsNoMessage() {
        render(emptyMap())
        compose.onNodeWithText("Bravo").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("select-answer-feedback", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun aBubbleSelectSaysSoToo() {
        render(marked, displayStyle = "bubble")
        compose.onNodeWithText("Bravo").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("select-answer-feedback", useUnmergedTree = true).assertTextEquals("Excellent!")
    }
}
