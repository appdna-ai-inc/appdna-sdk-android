package ai.appdna.sdk.feedback

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1964 — a choice question taller than the survey sheet. The bottom sheet is 60% of the window, so a question with
 * eight options does not fit on a phone. Back / Next / "Not now" must stay on screen with a real size, the options must
 * scroll so the LAST one can be picked, and Next must then move on. Before the fix the navigation row was clipped to
 * zero size and nothing scrolled, so the survey could not be finished.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SurveyLongQuestionScrollTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun config(presentation: String) = SurveyConfig.fromMap(
        mapOf(
            "name" to "Probe", "survey_type" to "custom",
            "questions" to listOf(
                mapOf(
                    "id" to "q_many", "type" to "multi_choice", "required" to false,
                    "text" to "Which areas would you like us to improve? (Select all that apply)",
                    "options" to (1..8).map { mapOf("id" to "o$it", "text" to "Synthetic option number $it") },
                ),
                mapOf("id" to "q_next", "type" to "free_text", "text" to "The second question", "required" to false),
            ),
            "trigger_rules" to mapOf("event" to "app_open", "frequency" to "once"),
            "appearance" to mapOf("presentation" to presentation, "dismiss_allowed" to true, "show_progress" to true),
        ),
    )!!

    private fun walk(presentation: String) {
        val cfg = config(presentation)
        compose.setContent {
            when (presentation) {
                "modal" -> ModalSurveyWrapper(config = cfg, onComplete = {}, onDismiss = {}, isDark = false)
                else -> BottomSheetSurveyWrapper(config = cfg, onComplete = {}, onDismiss = {}, isDark = false)
            }
        }
        compose.mainClock.advanceTimeBy(3000)
        compose.waitForIdle()
        for (label in listOf("Next", "Not now")) {
            compose.onNodeWithText(label).assertIsDisplayed()
            val b = compose.onNodeWithText(label).getBoundsInRoot()
            assertTrue("$label has a real size on screen ($b)", b.bottom.value - b.top.value > 20f)
        }
        // The last option is below the fold: it scrolls into view and can be picked.
        compose.onNodeWithText("Synthetic option number 8").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Next").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNodeWithText("The second question").assertIsDisplayed()
    }

    @Test fun bottomSheetLongQuestionKeepsNavigationAndScrolls() = walk("bottom_sheet")

    @Test fun modalLongQuestionKeepsNavigationAndScrolls() = walk("modal")
}
