package ai.appdna.sdk.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RENDERED, through the real `ContentBlockRendererView` on a white step: a row authored the way the console's
 * Row Background control writes it (`field_config.bg_color`, `background_opacity`) draws that fill —
 * `#2563EB` opaque, `#2563EB` at 0.5 (composited `#92B1F5`), and `#2563EB80` at 0.5 (a quarter: `#C9D8FA`, the
 * opacity multiplying the colour's own alpha as on iOS). Pixels are counted near each expected composite.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class RowFillRenderTest {
    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun block(map: Map<String, Any>): ContentBlock =
        OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(map)))
            ?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    /** Pixels matching [match] in one block rendered on a [bgHex] step. */
    private fun count(b: ContentBlock, bgHex: String, tag: String, match: (Int, Int, Int) -> Boolean): Int {
        val path = "build/tmp/row-fill/$tag.png"
        java.io.File(path).parentFile?.mkdirs()
        captureRoboImage(path) {
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

    private fun row(bg: String?, opacity: Double? = null): ContentBlock = block(buildMap {
        put("id", "r"); put("type", "row")
        put("field_config", buildMap<String, Any> { if (bg != null) put("bg_color", bg); if (opacity != null) put("background_opacity", opacity); put("corner_radius", 0) })
        put("children", listOf(mapOf("id" to "t", "type" to "spacer", "height" to 80)))
    })

    private fun near(r0: Int, g0: Int, b0: Int) = { r: Int, g: Int, b: Int ->
        kotlin.math.abs(r - r0) <= 8 && kotlin.math.abs(g - g0) <= 8 && kotlin.math.abs(b - b0) <= 8
    }

    @Test
    fun `a console-authored row fill draws at its opacity`() {
        val opaque = near(0x25, 0x63, 0xEB); val half = near(0x92, 0xB1, 0xF5); val quarter = near(0xC9, 0xD8, 0xFA)
        val none = count(row(null), "#FFFFFF", "none") { r, g, b -> opaque(r, g, b) || half(r, g, b) || quarter(r, g, b) }
        val full = count(row("#2563EB"), "#FFFFFF", "full") { r, g, b -> opaque(r, g, b) }
        val halfN = count(row("#2563EB", 0.5), "#FFFFFF", "half") { r, g, b -> half(r, g, b) }
        val quarterN = count(row("#2563EB80", 0.5), "#FFFFFF", "quarter") { r, g, b -> quarter(r, g, b) }
        val quarterAsHalf = count(row("#2563EB80", 0.5), "#FFFFFF", "quarter-as-half") { r, g, b -> half(r, g, b) }
        println("[row-fill] none=$none full=$full half=$halfN quarter=$quarterN quarterAsHalf=$quarterAsHalf")
        assertTrue("control: no fill draws none of the fill colours ($none)", none < 50)
        assertTrue("an opaque row fill did not draw: $full", full > 5000)
        assertTrue("a row fill at 0.5 did not draw at half: $halfN", halfN > 5000)
        assertTrue("a #RRGGBBAA fill at 0.5 is not a quarter (as on iOS): quarter=$quarterN half=$quarterAsHalf", quarterN > 5000 && quarterAsHalf < 50)
    }
}
