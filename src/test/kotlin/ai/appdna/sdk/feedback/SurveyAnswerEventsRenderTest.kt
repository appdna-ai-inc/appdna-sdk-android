package ai.appdna.sdk.feedback

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shipping survey screen: typing a free-text answer one character at a time reports the question answered ONCE,
 * with what was typed, when the user moves on (Submit here). The control: the same keystrokes, the survey left
 * without Submit, report nothing. Synthetic survey.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SurveyAnswerEventsRenderTest {
    @get:Rule val compose = createComposeRule()
    private val answered = mutableListOf<Pair<String, Any>>()

    private fun render() {
        val config = SurveyConfig.fromMap(
            mapOf(
                "name" to "Probe", "survey_type" to "custom",
                "questions" to listOf(mapOf("id" to "q1", "type" to "free_text", "text" to "Tell us more", "required" to false)),
                "trigger_rules" to mapOf("event" to "app_open", "frequency" to "once"),
                "appearance" to mapOf("presentation" to "fullscreen", "dismiss_allowed" to true, "show_progress" to false),
            ),
        )!!
        compose.setContent {
            SurveyScreen(config = config, onComplete = {}, onDismiss = {}, onQuestionAnswered = { id, _, a -> answered.add(id to a) }, isDark = false)
        }
        compose.mainClock.advanceTimeBy(3000)
        compose.waitForIdle()
    }

    private fun typeSlowly(text: String) {
        val field = compose.onNode(hasSetTextAction())
        for (c in text) { field.performTextInput(c.toString()); compose.waitForIdle() }
    }

    @Test fun aTypedAnswerIsReportedOnceOnSubmit() {
        render()
        typeSlowly("walk answer")
        assertEquals("nothing is reported while typing", emptyList<Pair<String, Any>>(), answered)
        compose.onNodeWithText("Submit").performClick()
        compose.waitForIdle()
        assertEquals(listOf("q1" to "walk answer"), answered)
    }

    @Test fun controlTypingWithoutMovingOnReportsNothing() {
        render()
        typeSlowly("abc")
        assertEquals(emptyList<Pair<String, Any>>(), answered)
    }
}
