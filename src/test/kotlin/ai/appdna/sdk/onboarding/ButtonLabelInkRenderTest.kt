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
 * RENDERED, through the real `ContentBlockRendererView`: a filled button on a dark fill with a Typography
 * `style` that sets no colour (only a size) draws its label in the variant's white; an outline button in its
 * fill colour. Android already did; this pins it beside the iOS test of the same name. THE CONTROLS: the
 * label authored each way draws, or does not draw, the pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class ButtonLabelInkRenderTest {
    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun block(map: Map<String, Any>): ContentBlock =
        OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(map)))
            ?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    /** Pixels matching [match] in one block rendered on a [bgHex] step. */
    private fun count(b: ContentBlock, bgHex: String, tag: String, match: (Int, Int, Int) -> Boolean): Int {
        val path = "build/tmp/button-label-ink/$tag.png"
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

    private val nearWhite = { r: Int, g: Int, b: Int -> r > 200 && g > 200 && b > 200 }
    private val nearBlack = { r: Int, g: Int, b: Int -> r < 60 && g < 60 && b < 60 }

    private fun button(text: String?, variant: String = "primary"): ContentBlock = block(buildMap {
        put("id", "b"); put("type", "button"); put("text", "WWWWWWWW WWWW"); put("variant", variant)
        put("bg_color", "#111827"); if (text != null) put("text_color", text)
        put("style", mapOf("font_size" to 20))
    })

    private val whitePx = { r: Int, g: Int, b: Int -> r > 225 && g > 225 && b > 225 }
    private val fillPx = { r: Int, g: Int, b: Int -> kotlin.math.abs(r - 0x11) < 6 && kotlin.math.abs(g - 0x18) < 6 && kotlin.math.abs(b - 0x27) < 6 }

    @Test
    fun `a style without a colour keeps the filled variant's white label`() {
        val dark = count(button("#111111"), "#0B1220", "filled-dark") { r, g, b -> whitePx(r, g, b) }
        val white = count(button("#FFFFFF"), "#0B1220", "filled-white") { r, g, b -> whitePx(r, g, b) }
        val styled = count(button(null), "#0B1220", "filled-styled") { r, g, b -> whitePx(r, g, b) }
        println("[button-ink filled] dark=$dark white=$white styled=$styled")
        assertTrue("harness control: $white vs $dark", white > dark + 100)
        assertTrue("a colourless style overrode the filled variant's white label: $styled vs $dark", styled > dark + 100)
    }

    @Test
    fun `a style without a colour keeps the outline variant's fill-coloured label`() {
        val black = count(button("#000000", "outline"), "#FFFFFF", "outline-black") { r, g, b -> fillPx(r, g, b) }
        val fill = count(button("#111827", "outline"), "#FFFFFF", "outline-fill") { r, g, b -> fillPx(r, g, b) }
        val styled = count(button(null, "outline"), "#FFFFFF", "outline-styled") { r, g, b -> fillPx(r, g, b) }
        println("[button-ink outline] black=$black fill=$fill styled=$styled")
        assertTrue("harness control: $fill vs $black", fill > black + 100)
        assertTrue("a colourless style overrode the outline variant's fill-coloured label: $styled vs $black", styled > black + 100)
    }
}
