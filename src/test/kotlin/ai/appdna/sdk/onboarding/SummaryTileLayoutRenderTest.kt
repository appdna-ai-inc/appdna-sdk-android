package ai.appdna.sdk.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * RENDERED, through the real `ContentBlockRendererView` (#1680): a two-column summary tile's value breaks only
 * on word boundaries, and the tiles in one row share a height. A control: a short value keeps its authored size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class SummaryTileLayoutRenderTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private val cardHex = "#FF00FF"

    private fun block(vararg values: String): ContentBlock =
        OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(mapOf(
            "id" to "sum", "type" to "summary_screen", "bg_color" to cardHex,
            "field_config" to mapOf("summary_stats" to values.map { mapOf("value" to it, "label" to "L") }),
        ))))?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    private fun show(vararg values: String) {
        val b = block(*values)
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Box(Modifier.fillMaxWidth().background(Color.White).padding(16.dp)) {
                    ContentBlockRendererView(blocks = listOf(b), onAction = {}, toggleValues = mutableMapOf())
                }
            }
        }
        compose.waitForIdle()
    }

    private fun layout(text: String): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(out)
        return out.first()
    }

    private fun assertBreaksOnlyOnWords(text: String) {
        val r = layout(text)
        for (line in 0 until r.lineCount - 1) {
            val end = r.getLineEnd(line, visibleEnd = false)
            assertTrue("\"$text\" broke mid-word at $end, line $line", text[end - 1].isWhitespace())
        }
    }

    @Test
    fun `a single word too wide for the tile shrinks onto one line`() {
        show("Intermediate", "Mid")
        val r = layout("Intermediate")
        println("[summary-fit] Intermediate lines=${r.lineCount} size=${r.layoutInput.style.fontSize}")
        assertEquals("the word stays whole", 1, r.lineCount)
        assertTrue("the control: a short value keeps 24sp", layout("Mid").layoutInput.style.fontSize.value == 24f)
    }

    @Test
    fun `an unbroken id shrinks rather than breaking`() {
        show("home_basics_and_more", "Mid")
        assertBreaksOnlyOnWords("home_basics_and_more")
        assertEquals(1, layout("home_basics_and_more").lineCount)
    }

    @Test
    fun `a phrase wraps on its spaces`() {
        show("Take a short walk every day", "Mid")
        assertBreaksOnlyOnWords("Take a short walk every day")
    }

    @Test
    fun `tiles in a row share a height`() {
        show("Take a short walk every single day", "Mid")
        val path = "build/tmp/summary-tiles/row.png"
        File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame")
        fun extent(x0: Int, x1: Int): Pair<Int, Int> {
            var top = -1; var bottom = -1
            for (y in 0 until bmp.height) for (x in x0 until x1) {
                val c = bmp.getPixel(x, y)
                if (android.graphics.Color.red(c) > 240 && android.graphics.Color.green(c) < 20 && android.graphics.Color.blue(c) > 240) {
                    if (top < 0) top = y
                    bottom = y
                }
            }
            return top to bottom
        }
        val left = extent(0, bmp.width / 2); val right = extent(bmp.width / 2, bmp.width)
        println("[summary-row] left=$left right=$right")
        assertTrue("the control: a tile's fill was found", left.first >= 0 && right.first >= 0)
        assertEquals("the row's tiles share a height", left.second - left.first, right.second - right.first)
    }
}
