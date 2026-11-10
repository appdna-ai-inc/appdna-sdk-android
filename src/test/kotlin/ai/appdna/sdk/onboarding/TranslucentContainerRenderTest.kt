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
 * RENDERED, through the real `ContentBlockRendererView` on a white step: an authored translucent (8-digit) colour
 * keeps its alpha, and an opacity on top MULTIPLIES it, as on iOS —
 * - a container `#00000080` with no opacity composites to ~#7F7F7F, and at `container_opacity` 0.5 to ~#BFBFBF;
 * Pixels are counted near each expected composite.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class TranslucentContainerRenderTest {
    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun block(map: Map<String, Any>): ContentBlock =
        OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(map)))
            ?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    /** Pixels matching [match] in one block rendered on a [bgHex] step. */
    private fun count(b: ContentBlock, bgHex: String, tag: String, match: (Int, Int, Int) -> Boolean): Int {
        val path = "build/tmp/translucent/$tag.png"
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

    private fun grey(v: Int, tol: Int = 6) = { r: Int, g: Int, b: Int -> kotlin.math.abs(r - v) <= tol && kotlin.math.abs(g - v) <= tol && kotlin.math.abs(b - v) <= tol }
    private val black = { r: Int, g: Int, b: Int -> r < 10 && g < 10 && b < 10 }

    private fun box(fill: String, opacity: Double?) = block(buildMap<String, Any> {
        put("id", "s"); put("type", "heading"); put("text", "WWWWWWWWWWWW"); put("element_width", "fill"); put("style", mapOf("font_size" to 40, "color" to "#FFFFFF"))
        put("field_config", buildMap<String, Any> { put("container_bg_color", fill); if (opacity != null) put("container_opacity", opacity) })
    })

    @Test
    fun `a translucent container keeps its alpha and multiplies the opacity`() {
        val half = count(box("#00000080", null), "#FFFFFF", "c-half") { r, g, b -> grey(0x7F)(r, g, b) }
        val opaque = count(box("#00000080", null), "#FFFFFF", "c-opaque") { r, g, b -> black(r, g, b) }
        val quarter = count(box("#00000080", 0.5), "#FFFFFF", "c-quarter") { r, g, b -> grey(0xBF)(r, g, b) }
        println("[translucent container] half=$half opaque=$opaque quarter=$quarter")
        assertTrue("an authored #00000080 drew opaque ($opaque black, $half half-grey)", half > 3000 && opaque < 100)
        assertTrue("container_opacity 0.5 over #00000080 is not a quarter ($quarter)", quarter > 3000)
    }
}
