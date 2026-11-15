package ai.appdna.sdk.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A text input is named for TalkBack: its label, else its placeholder. The label is drawn as a separate text above the
 * field, so the field itself had no name once its placeholder was gone (and none at all with no placeholder).
 * Rendered through the REAL renderers; every node that takes text entry is read. iOS `TextInputAccessibilityNameTests`.
 * THE CONTROL: the inputs are found at all (the count), so an empty tree cannot pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1600dp-xhdpi")
class TextInputAccessibilityNameTest {
    @get:Rule val compose = createComposeRule()

    private fun block(map: Map<String, Any>): ContentBlock =
        OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(map)))
            ?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    private fun inputNames(): List<String> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText), useUnmergedTree = false)
            .fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") ?: "" }

    @Test
    fun `each text input block carries its label else its placeholder`() {
        val blocks = listOf(
            block(mapOf("id" to "num", "type" to "input_number", "field_id" to "amount", "field_label" to "Monthly savings goal")),
            block(mapOf("id" to "txt", "type" to "input_text", "field_id" to "name", "field_label" to "Your first name", "field_placeholder" to "Type it here")),
            block(mapOf("id" to "mail", "type" to "input_email", "field_id" to "mail", "field_placeholder" to "Email address")),
            block(mapOf("id" to "area", "type" to "input_textarea", "field_id" to "note", "field_label" to "Anything else")),
        )
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) { ContentBlockRendererView(blocks = blocks, onAction = {}, toggleValues = mutableMapOf()) }
        }
        val names = inputNames()
        assertEquals("control: four text inputs are found: $names", 4, names.size)
        assertEquals("every text input is named by its label, else its placeholder: $names",
            setOf("Monthly savings goal", "Your first name", "Email address", "Anything else"), names.toSet())
    }

    @Test
    fun `a legacy form step names its fields`() {
        val step = OnboardingConfigParser.parseStepForTest(mapOf("id" to "f", "type" to "form", "config" to mapOf("fields" to listOf(
            mapOf("id" to "f_name", "type" to "text", "label" to "Preferred name", "required" to false),
            mapOf("id" to "f_age", "type" to "number", "label" to "Age in years", "required" to false),
            mapOf("id" to "f_bio", "type" to "textarea", "label" to "A line about you", "required" to false),
        )))) ?: error("step did not parse")
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) { Column { FormStep(config = step.config!!, onNext = {}) } }
        }
        val names = inputNames()
        assertEquals("control: three text inputs are found: $names", 3, names.size)
        assertEquals("every legacy form field is named by its label: $names",
            setOf("Preferred name", "Age in years", "A line about you"), names.toSet())
    }
}
