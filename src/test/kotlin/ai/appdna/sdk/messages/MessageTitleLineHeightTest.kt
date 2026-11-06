package ai.appdna.sdk.messages

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A wrapped in-app title's lines must not overlap: the distance from one line to the next is at least the
 * font size. Read from the REAL `InAppMessageView`'s text layout (what Compose drew), for the fullscreen,
 * modal, banner and tooltip titles. THE CONTROL: the title really wraps (two or more lines), so the check
 * is about line spacing and not about a single line.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class MessageTitleLineHeightTest {
    @get:Rule
    val compose = createComposeRule()

    private val title = "A long synthetic title that has to wrap onto a second line and then onto a third line as well"

    @Test
    fun `wrapped titles keep their lines apart on every message type`() {
        var type by androidx.compose.runtime.mutableStateOf("fullscreen")
        compose.setContent {
            androidx.compose.runtime.key(type) {
                val cfg = MessageConfigParser.parseSingleMessage(
                    mapOf("name" to "n", "message_type" to type, "content" to mapOf("title" to title, "cta_text" to "Go"), "trigger_rules" to mapOf("event" to "e")),
                )!!
                InAppMessageView(config = cfg, onCTATap = {}, onDismiss = {}, isDark = false)
            }
        }
        for (t in listOf("fullscreen", "modal", "banner", "tooltip")) {
            type = t
            compose.mainClock.advanceTimeBy(1_000)
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val layout = results.single()
            assertTrue("[$t] control: the title wraps (${layout.lineCount} lines)", layout.lineCount >= 2)
            val step = layout.getLineTop(1) - layout.getLineTop(0)
            val fontPx = with(compose.density) { layout.layoutInput.style.fontSize.toPx() }
            println("[title-line-height] $t step=$step font=$fontPx")
            assertTrue("[$t] lines overlap: line step $step < font size $fontPx", step >= fontPx)
        }
    }
}
