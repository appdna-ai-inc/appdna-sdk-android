package ai.appdna.sdk.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RENDERED: unstyled `rich_text` and `list` on a dark surface (the backdrop a dark step or fill hands its
 * blocks), through the real `ContentBlockRendererView`. They must be drawn light, as text is. THE CONTROLS:
 * the same block styled #FFFFFF draws near-white pixels and styled #111111 draws none.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class RichTextListInkRenderTest {
    @org.junit.Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun block(map: Map<String, Any>): ContentBlock =
        OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(map)))
            ?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    /** Near-white pixels of one block rendered on a dark surface (Roborazzi: a frame, no idle wait). */
    private fun nearWhite(b: ContentBlock): Int {
        val path = "build/tmp/rich-text-list-ink/${b.id}-${b.hashCode()}.png"
        java.io.File(path).parentFile?.mkdirs()
        captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record)) {
            CompositionLocalProvider(LocalGoogleAttributionBackdrop provides GoogleMapsAttribution.Backdrop.Plain("#0F172A")) {
                Box(Modifier.fillMaxWidth().background(Color(0xFF0F172A)).padding(16.dp)) {
                    ContentBlockRendererView(blocks = listOf(b), onAction = {}, toggleValues = mutableMapOf())
                }
            }
        }
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame at $path")
        var n = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            if (android.graphics.Color.red(c) > 200 && android.graphics.Color.green(c) > 200 && android.graphics.Color.blue(c) > 200) n++
        }
        return n
    }

    private val words = "WWWWWWWW WWWWWWWW WWWWWWWW"

    @Test
    fun `unstyled rich text and list on a dark surface are light`() {
        val r = listOf(
            block(mapOf("id" to "rt0", "type" to "rich_text", "markdown_content" to words, "base_style" to mapOf("color" to "#111111"))),
            block(mapOf("id" to "rt1", "type" to "rich_text", "markdown_content" to words, "base_style" to mapOf("color" to "#FFFFFF"))),
            block(mapOf("id" to "rt2", "type" to "rich_text", "markdown_content" to words)),
            block(mapOf("id" to "ls0", "type" to "list", "items" to listOf("WWWWWWWWWW", "WWWWWWWWWW"), "style" to mapOf("color" to "#111111"))),
            block(mapOf("id" to "ls1", "type" to "list", "items" to listOf("WWWWWWWWWW", "WWWWWWWWWW"), "style" to mapOf("color" to "#FFFFFF"))),
            block(mapOf("id" to "ls2", "type" to "list", "items" to listOf("WWWWWWWWWW", "WWWWWWWWWW"))),
        ).map { nearWhite(it) }
        println("[rich-text-list-ink] $r")
        assertTrue("harness control (rich_text): $r", r[1] > r[0] + 100)
        assertTrue("unstyled rich_text on a dark surface is not light: $r", r[2] > r[0] + 100)
        assertTrue("harness control (list): $r", r[4] > r[3] + 100)
        assertTrue("unstyled list on a dark surface is not light: $r", r[5] > r[3] + 100)
    }
}
