package ai.appdna.sdk.onboarding

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.input.key.Key
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The rendered half of the "Add widget" action:
 *  - an `add_widget` button at any depth (top level, a Row child, a Multi-buttons child, an id-less
 *    child) hands the step its DECODED block through [LocalWidgetAction];
 *  - with no channel (Screens / SDUI) it takes the ordinary action path;
 *  - the how-to sheet renders its authored blocks, acts on Done and Close, and a `link` inside it
 *    does nothing at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class WidgetActionComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun blocksOf(vararg blocks: Map<String, Any>): List<ContentBlock> =
        OnboardingConfigParser.parseStepForTest(
            mapOf("id" to "s", "type" to "content", "config" to mapOf("content_blocks" to blocks.toList())),
        )!!.config.content_blocks!!.toList()

    private val howtoJson = listOf(
        mapOf("id" to "h_head", "type" to "heading", "text" to "Put it on your home screen"),
        mapOf("id" to "h_done", "type" to "button", "text" to "Done", "action" to "next"),
    )

    private fun widget(id: String?, label: String): Map<String, Any> = buildMap {
        id?.let { put("id", it) }
        put("type", "button"); put("text", label); put("action", "add_widget")
        put("howto_blocks_android", howtoJson)
    }

    @Test
    fun `the channel reaches a top-level button, a Row child, a Multi-buttons child and an id-less child`() {
        val blocks = blocksOf(
            widget("top", "Top"),
            mapOf("id" to "row_1", "type" to "row", "stack_children" to listOf(widget("row_w", "In a row"))),
            mapOf("id" to "mb", "type" to "multi_buttons", "stack_children" to listOf(widget("mb_w", "In a group"))),
            mapOf("id" to "row_2", "type" to "row", "stack_children" to listOf(widget(null, "No id"))),
        )
        val tapped = mutableListOf<ContentBlock>()
        val actions = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalWidgetAction provides WidgetActionChannel { tapped += it }) {
                ContentBlockRendererView(blocks = blocks, onAction = { actions += it }, toggleValues = mutableStateMapOf())
            }
        }
        for (label in listOf("Top", "In a row", "In a group", "No id")) compose.onNodeWithText(label).performClick()
        assertEquals(listOf("top", "row_w", "mb_w"), tapped.take(3).map { it.id })
        assertEquals(4, tapped.size)
        assertTrue("the id-less child carries the decoder's id", tapped[3].id.isNotBlank())
        // The block itself arrives, how-to and all — no lookup by id needed.
        assertEquals(listOf("h_head", "h_done"), tapped[2].howto_blocks_android!!.map { it.id })
        assertEquals("nothing goes down the action path while the channel is there", emptyList<String>(), actions)
    }

    @Test
    fun `with no channel the button takes the ordinary action path`() {
        val blocks = blocksOf(widget("top", "Top"))
        val actions = mutableListOf<String>()
        compose.setContent {
            ContentBlockRendererView(blocks = blocks, onAction = { actions += it }, toggleValues = mutableStateMapOf())
        }
        compose.onNodeWithText("Top").performClick()
        assertEquals(listOf("add_widget"), actions)
    }

    private fun sheetBlocks() = blocksOf(
        mapOf("id" to "h_head", "type" to "heading", "text" to "Put it on your home screen"),
        mapOf("id" to "h_body", "type" to "text", "text" to "Touch and hold the home screen"),
        mapOf("id" to "h_link", "type" to "button", "text" to "Learn more", "action" to "link", "action_value" to "https://example.com/widgets"),
        mapOf("id" to "h_widget", "type" to "button", "text" to "Nested add", "action" to "add_widget"),
        mapOf("id" to "h_done", "type" to "button", "text" to "Done", "action" to "next"),
        mapOf("id" to "h_close", "type" to "button", "text" to "Close", "action" to "dismiss", "variant" to "text"),
    )

    private class Outcome {
        var done = 0
        val closes = mutableListOf<String>()
        val nestedTaps = mutableListOf<String>()
    }

    private fun showSheet(o: Outcome, loc: ((String, String) -> String)? = null) {
        val blocks = sheetBlocks()
        compose.setContent {
            // The how-to sits under a step that HAS a channel: the sheet must not let it through.
            CompositionLocalProvider(LocalWidgetAction provides WidgetActionChannel { o.nestedTaps += it.id }) {
                BlockSheet(
                    blocks = blocks, loc = loc,
                    onDone = { o.done++ }, onClose = { o.closes += it },
                    forwardEveryAction = true, skipPartiallyExpanded = true,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the how-to renders its authored blocks, localized with the step's loc`() {
        showSheet(Outcome()) { key, fallback -> if (key == "block.h_head.text") "Localized heading" else fallback }
        compose.onNodeWithText("Localized heading").assertExists()
        compose.onNodeWithText("Touch and hold the home screen").assertExists()
        compose.onNodeWithText("Done").assertExists()
        compose.onNodeWithText("Close").assertExists()
    }

    @Test
    fun `Done ends the attempt and Close closes without it`() {
        val o = Outcome()
        showSheet(o)
        compose.onNodeWithText("Done").performClick()
        assertEquals(1, o.done)
        assertEquals(emptyList<String>(), o.closes)
        compose.onNodeWithText("Close").performClick()
        assertEquals(listOf("dismiss"), o.closes)
        assertEquals(1, o.done)
    }

    @Test
    fun `a link and an add_widget button inside the how-to do nothing`() {
        val o = Outcome()
        showSheet(o)
        compose.onNodeWithText("Learn more").performClick()
        compose.onNodeWithText("Nested add").performClick()
        compose.waitForIdle()
        val app = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
        assertNull("the link opened a browser", app.nextStartedActivity)
        assertEquals(emptyList<String>(), o.nestedTaps)
        assertEquals(0, o.done)
        assertEquals(emptyList<String>(), o.closes)
        assertTrue(compose.onAllNodes(hasText("Done")).fetchSemanticsNodes().isNotEmpty())
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun `back closes the how-to and is reported as back, not as the scrim`() {
        val o = Outcome()
        showSheet(o)
        compose.onNodeWithText("Done").requestFocus().performKeyInput { pressKey(Key.Back) }
        compose.waitForIdle()
        assertEquals(listOf("back"), o.closes)
        assertEquals(0, o.done)
    }
}
