package ai.appdna.sdk.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RENDERED, through the real `ContentBlockRendererView`: a heading carrying BOTH `block_style.background_color:
 * #000000` and `field_config.container_bg_color: #F3F4F6` shows the `block_style` fill over the container fill —
 * the order the console preview and iOS draw (container outer, `block_style` inner). THE CONTROLS: the
 * `block_style` fill alone draws black; the container fill alone draws no black.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class BlockLayerOrderRenderTest {
    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun block(map: Map<String, Any>): ContentBlock =
        OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(map)))
            ?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    /** Pixels matching [match] in one block rendered on a [bgHex] step. */
    private fun count(b: ContentBlock, bgHex: String, tag: String, match: (Int, Int, Int) -> Boolean): Int {
        val path = "build/tmp/layer-order/$tag.png"
        java.io.File(path).parentFile?.mkdirs()
        captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record)) {
            CompositionLocalProvider(LocalGoogleAttributionBackdrop provides GoogleMapsAttribution.Backdrop.Plain(bgHex)) {
                Box(Modifier.fillMaxWidth().background(Color(android.graphics.Color.parseColor(bgHex))).padding(16.dp)) {
                    ContentBlockRendererView(blocks = listOf(b), onAction = {}, toggleValues = mutableMapOf())
                }
            }
        }
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame at $path")
        var n = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            if (match(android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))) n++
        }
        return n
    }

    private val black = { r: Int, g: Int, b: Int -> r < 10 && g < 10 && b < 10 }

    private fun heading(blockStyle: Boolean, container: Boolean) = block(buildMap<String, Any> {
        put("id", "h"); put("type", "heading"); put("text", "WWWW"); put("style", mapOf("color" to "#FFFFFF", "font_size" to 28))
        if (blockStyle) put("block_style", mapOf("background_color" to "#000000"))
        if (container) put("field_config", mapOf("container_bg_color" to "#F3F4F6"))
    })

    @Test
    fun `block_style paints over the container fill`() {
        val styleOnly = count(heading(true, false), "#FF0000", "style") { r, g, b -> black(r, g, b) }
        val containerOnly = count(heading(false, true), "#FF0000", "container") { r, g, b -> black(r, g, b) }
        val both = count(heading(true, true), "#FF0000", "both") { r, g, b -> black(r, g, b) }
        println("[layer-order] styleOnly=$styleOnly containerOnly=$containerOnly both=$both")
        assertTrue("control: block_style alone draws black ($styleOnly)", styleOnly > 2000)
        assertTrue("control: the container alone draws no black ($containerOnly)", containerOnly < 100)
        assertTrue("with both, block_style is not the visible (inner) layer ($both vs $styleOnly)", both > styleOnly / 2)
    }
}
