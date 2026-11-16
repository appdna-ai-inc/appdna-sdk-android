package ai.appdna.sdk.onboarding

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A consent toggles from a tap anywhere on its row — its words included — and a link inside the words opens the
 * link instead of toggling. Before, only the 22 dp box toggled and a tap on the words did nothing. Renders the
 * shipping renderer and taps the drawn label.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ConsentLabelTapTest {
    @get:Rule val compose = createComposeRule()
    private val inputValues = mutableStateMapOf<String, Any>()

    private fun render(block: Map<String, Any>) {
        val blocks = OnboardingConfigParser.parseStepForTest(
            mapOf("id" to "s", "type" to "custom", "config" to mapOf("content_blocks" to listOf(block))),
        )!!.config.content_blocks!!.toList()
        compose.setContent {
            ContentBlockRendererView(blocks = blocks, onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
        compose.waitForIdle()
    }

    private val multi = mapOf(
        "id" to "a", "type" to "agreement", "field_id" to "consents",
        "field_config" to mapOf("consents" to listOf(
            mapOf("id" to "terms", "text" to "I accept the terms", "required" to true),
            mapOf("id" to "policy", "text" to "[Read the privacy policy and the full terms of service that apply to this application and every one of its features](https://example.com/policy)", "required" to false),
        )),
    )

    @Test fun `a tap on a consent's words toggles it, and a second tap unticks it`() {
        render(multi)
        assertEquals(false, inputValues["consents.terms"])
        compose.onNodeWithText("I accept the terms", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(true, inputValues["consents.terms"])
        compose.onNodeWithText("I accept the terms", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(false, inputValues["consents.terms"])
    }

    // A tap on a link's glyphs is not driven here: Robolectric lays text out without glyph metrics, so every tap
    // position maps to the end of the text. The link branch is unchanged code and is walked on a device.

    @Test fun `the single-consent shape toggles from its words too`() {
        render(mapOf("id" to "one", "type" to "agreement", "field_id" to "agree", "text" to "I agree to everything"))
        assertEquals(false, inputValues["agree"])
        compose.onNodeWithText("I agree to everything", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(true, inputValues["agree"])
    }
}

