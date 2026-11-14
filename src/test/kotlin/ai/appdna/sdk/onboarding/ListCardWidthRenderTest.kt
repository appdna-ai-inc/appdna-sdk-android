package ai.appdna.sdk.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RENDERED through the real `ContentBlockRendererView`: a `list` on its own card (`block_style.background_color`)
 * with `element_width: "100%"` draws a card as wide as the row, as iOS and the console preview do. Before the fix
 * the card was painted around a hugging Column, so it was only as wide as the longest item.
 *
 * THE CONTROL: the same list with no width still hugs its rows, so the harness can tell the two apart.
 * RED CONTROL: drop the `fillMaxWidth()` in `ListBlock` and the 100% card comes back as narrow as the control.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class ListCardWidthRenderTest {
    @org.junit.Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun block(width: String?): ContentBlock {
        val map = mutableMapOf<String, Any>(
            "id" to "ls", "type" to "list", "list_style" to "check", "items" to listOf("Short", "A bit longer row"),
            "style" to mapOf("color" to "#111111"), "horizontal_align" to "left",
            "block_style" to mapOf("background_color" to "#FF0000", "padding_top" to 12, "padding_bottom" to 12, "padding_left" to 12, "padding_right" to 12),
        )
        if (width != null) map["element_width"] = width
        return OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(map)))
            ?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")
    }

    /** The right-most red column and the frame width, in pixels. */
    private fun cardRightEdge(width: String?): Pair<Int, Int> {
        val path = "build/tmp/list-card-width/${width ?: "unset"}.png"
        java.io.File(path).parentFile?.mkdirs()
        captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record)) {
            Box(Modifier.fillMaxWidth().background(Color.White)) {
                ContentBlockRendererView(blocks = listOf(block(width)), onAction = {}, toggleValues = mutableMapOf())
            }
        }
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame at $path")
        var right = -1
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            if (android.graphics.Color.red(c) > 220 && android.graphics.Color.green(c) < 40 && android.graphics.Color.blue(c) < 40 && x > right) right = x
        }
        return right to bmp.width
    }

    @Test
    fun `a sized list card fills the row`() {
        val (hugging, frame) = cardRightEdge(null)
        val (filled, _) = cardRightEdge("100%")
        println("[list-card] hugging=$hugging filled=$filled frame=$frame")
        assertTrue("harness control: the card colour must be visible", hugging > 0)
        assertTrue("harness control: an unsized list hugs its rows ($hugging of $frame)", hugging < frame * 0.7)
        assertTrue("a list authored 100% wide draws its card across the row ($filled of $frame)", filled > frame * 0.9)
    }

    @Test
    fun `only an authored width fills`() {
        assertFalse(listFillsWidth(null))
        assertFalse(listFillsWidth("auto"))
        assertTrue(listFillsWidth("100%"))
        assertTrue(listFillsWidth("fill"))
        assertTrue(listFillsWidth("80%"))
    }
}
