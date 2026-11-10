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
 * RENDERED, through the real `ContentBlockRendererView` on a white step: a heading NESTED in a stack, with its own
 * `field_config.container_bg_color: #000000`, draws that black fill behind it — as the same heading does at the
 * top level. THE CONTROL: without the container colour, no black fill is drawn.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class NestedContainerStyleRenderTest {
    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun block(map: Map<String, Any>): ContentBlock =
        OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(map)))
            ?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    /** Pixels matching [match] in one block rendered on a [bgHex] step. */
    private fun count(b: ContentBlock, bgHex: String, tag: String, match: (Int, Int, Int) -> Boolean): Int {
        val path = "build/tmp/nested-container/$tag.png"
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

    private fun heading(fill: String?) = buildMap<String, Any> {
        put("id", "h"); put("type", "heading"); put("text", "WWWW"); put("style", mapOf("color" to "#FFFFFF", "font_size" to 28))
        if (fill != null) put("field_config", mapOf("container_bg_color" to fill))
    }
    private fun stack(fill: String?) = block(mapOf("id" to "s", "type" to "stack", "children" to listOf(heading(fill))))

    @Test
    fun `a nested block draws its container fill`() {
        val none = count(stack(null), "#FFFFFF", "none") { r, g, b -> black(r, g, b) }
        val nested = count(stack("#000000"), "#FFFFFF", "nested") { r, g, b -> black(r, g, b) }
        val top = count(block(heading("#000000")), "#FFFFFF", "top") { r, g, b -> black(r, g, b) }
        println("[nested-container] none=$none nested=$nested top=$top")
        assertTrue("control: no container colour, no black fill ($none)", none < 100)
        assertTrue("harness: a top-level container fill draws ($top)", top > 2000)
        assertTrue("a nested block's container fill is not drawn ($nested)", nested > 2000)
    }
}
