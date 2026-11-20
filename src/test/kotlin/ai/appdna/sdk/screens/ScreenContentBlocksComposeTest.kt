package ai.appdna.sdk.screens

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Screen's content-block section, drawn through the REAL `SectionRegistry.Render` (what `ScreenHostActivity` and a
 * slot draw).
 *
 * What the user types reaches the screen's responses, which `ScreenHostActivity` hands the host as
 * `ScreenResult.responses` and a flow as `FlowResult.responses`. It used to stay in the section's own state.
 * Red control: without `reportEnteredValues` the typed value never reaches `context.responses`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class ScreenContentBlocksComposeTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun register() { SectionRegistry.registerBuiltInSections() }

    private fun section(blocks: List<Map<String, Any?>>) = ScreenConfig.fromMap(mapOf(
        "id" to "scr_blocks",
        "sections" to listOf(mapOf("id" to "s1", "type" to "content_blocks", "data" to mapOf("blocks" to blocks))),
    )).sections.first()

    @Test
    fun `a typed value reaches the screen's responses`() {
        val responses = mutableMapOf<String, Any>("earlier" to "kept")
        val ctx = SectionContext(screenId = "scr_blocks", responses = responses, onAction = {})
        val s = section(listOf(mapOf(
            "id" to "b_name", "type" to "input_text", "field_id" to "nickname", "field_label" to "Nickname",
        )))
        compose.setContent { SectionRegistry.Render(s, ctx) }
        compose.onNode(hasSetTextAction()).performTextInput("Robin")
        compose.waitForIdle()
        assertEquals("Robin", responses["nickname"])
        assertEquals("another section's answer is untouched", "kept", responses["earlier"])
    }

    /**
     * A `{{…}}` token in a Screen's block resolves, as on iOS. It used to be drawn literally on Android.
     * Red control: without the view pass in `ContentBlocksSectionRenderer` the literal token is on screen.
     */
    @Test
    fun `a template token resolves against the screen's responses`() {
        val ctx = SectionContext(screenId = "scr_blocks", responses = mutableMapOf("nickname" to "Robin"), onAction = {})
        val s = section(listOf(mapOf("id" to "b_hi", "type" to "text", "text" to "Hi {{responses.nickname}}")))
        compose.setContent { SectionRegistry.Render(s, ctx) }
        compose.onNodeWithText("Hi Robin").assertExists()
        compose.onNodeWithText("Hi {{responses.nickname}}").assertDoesNotExist()
    }

    @Test
    fun `nothing typed reports nothing`() {
        val responses = mutableMapOf<String, Any>()
        val ctx = SectionContext(screenId = "scr_blocks", responses = responses, onAction = {})
        val s = section(listOf(mapOf("id" to "b_name", "type" to "input_text", "field_id" to "nickname")))
        compose.setContent { SectionRegistry.Render(s, ctx) }
        compose.waitForIdle()
        assertFalse(responses.containsKey("nickname"))
    }
}
