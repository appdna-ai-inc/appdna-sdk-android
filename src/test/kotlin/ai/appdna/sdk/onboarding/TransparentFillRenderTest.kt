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
 * RENDERED, through the real `ContentBlockRendererView` on a white step: a row fill (`field_config.bg_color`) or a
 * block container fill (`field_config.container_bg_color`) that the colour grammar reads as transparent draws
 * NOTHING. Compose's `Color.Transparent` is black with zero alpha, so replacing the alpha with the opacity
 * (`copy(alpha = opacity)`) drew those values as an opaque black box; the opacity must multiply the alpha.
 *
 * Also: an 8-digit fill keeps its own alpha (`#00000080` is half grey, not black), and a fully transparent 8-digit
 * fill (`#FFFFFF00`, `#00000000`) draws nothing. Negative control in the same test: an opaque `#000000` fill is
 * seen by this harness, so "no dark pixels" cannot pass because nothing was captured.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class TransparentFillRenderTest {
    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun block(map: Map<String, Any>): ContentBlock =
        OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(map)))
            ?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    /** Pixels in one block, rendered on a white step, whose every channel is at or below [max]. */
    private fun darkPixels(b: ContentBlock, tag: String, max: Int = 0x60): Int {
        val path = "build/tmp/transparent-fill/$tag.png"
        java.io.File(path).parentFile?.mkdirs()
        captureRoboImage(path) {
            CompositionLocalProvider(LocalGoogleAttributionBackdrop provides GoogleMapsAttribution.Backdrop.Plain("#FFFFFF")) {
                Box(Modifier.fillMaxWidth().background(Color.White).padding(16.dp)) {
                    ContentBlockRendererView(blocks = listOf(b), onAction = {}, toggleValues = mutableMapOf())
                }
            }
        }
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame at $path")
        var n = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            if (android.graphics.Color.red(c) <= max && android.graphics.Color.green(c) <= max && android.graphics.Color.blue(c) <= max) n++
        }
        return n
    }

    /** Pixels within 10 of a mid grey `#808080` (black at half alpha on white). */
    private fun halfGreyPixels(tag: String): Int {
        val bmp = android.graphics.BitmapFactory.decodeFile("build/tmp/transparent-fill/$tag.png") ?: error("no frame")
        var n = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            if (listOf(android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c)).all { kotlin.math.abs(it - 0x80) <= 10 }) n++
        }
        return n
    }

    private fun row(bg: String, opacity: Double? = null): ContentBlock = block(buildMap {
        put("id", "r"); put("type", "row")
        put("field_config", buildMap<String, Any> { put("bg_color", bg); if (opacity != null) put("background_opacity", opacity); put("corner_radius", 0) })
        put("children", listOf(mapOf("id" to "t", "type" to "spacer", "height" to 80)))
    })

    private fun container(bg: String, opacity: Double? = null): ContentBlock = block(buildMap {
        // A row with no fill of its own, so only the container style can paint.
        put("id", "c"); put("type", "row")
        put("field_config", buildMap<String, Any> { put("container_bg_color", bg); if (opacity != null) put("container_opacity", opacity) })
        put("children", listOf(mapOf("id" to "t", "type" to "spacer", "height" to 80)))
    })

    /** Every value the colour grammar reads as transparent. */
    private val transparentValues = listOf("transparent", "clear", "CLEAR", "#FFF", "white", "rgba(0,0,0,0.5)", "##FFFFFF", "#FFFFFF00", "#00000000")

    @Test
    fun `a grammar-transparent row fill draws nothing, at any opacity`() {
        val control = darkPixels(row("#000000"), "row-control")
        println("[transparent-fill] row control #000000 dark=$control")
        assertTrue("control: this harness sees an opaque black row ($control)", control > 5000)
        val drawn = (transparentValues + "").flatMap { v ->
            listOf(1.0, 0.5).map { op -> "row bg_color=\"$v\" opacity=$op" to darkPixels(row(v, op), "row-${v.hashCode()}-$op") }
        }
        drawn.forEach { (k, n) -> println("[transparent-fill] $k dark=$n") }
        val black = drawn.filter { it.second >= 50 }
        assertTrue("grammar-transparent row fills drew dark pixels: $black", black.isEmpty())
    }

    @Test
    fun `a grammar-transparent container fill draws nothing, at any opacity`() {
        val control = darkPixels(container("#000000"), "container-control")
        println("[transparent-fill] container control #000000 dark=$control")
        assertTrue("control: this harness sees an opaque black container ($control)", control > 5000)
        val drawn = transparentValues.flatMap { v ->
            listOf(1.0, 0.5).map { op -> "container_bg_color=\"$v\" opacity=$op" to darkPixels(container(v, op), "container-${v.hashCode()}-$op") }
        }
        drawn.forEach { (k, n) -> println("[transparent-fill] $k dark=$n") }
        val black = drawn.filter { it.second >= 50 }
        assertTrue("grammar-transparent container fills drew dark pixels: $black", black.isEmpty())
    }

    @Test
    fun `an 8-digit fill keeps its own alpha`() {
        val rowDark = darkPixels(row("#00000080"), "row-half")
        val rowGrey = halfGreyPixels("row-half")
        val boxDark = darkPixels(container("#00000080"), "container-half")
        val boxGrey = halfGreyPixels("container-half")
        println("[transparent-fill] #00000080 row dark=$rowDark grey=$rowGrey container dark=$boxDark grey=$boxGrey")
        assertTrue("a #00000080 row fill was drawn opaque ($rowDark dark) or not at half ($rowGrey grey)", rowDark < 50 && rowGrey > 5000)
        assertTrue("a #00000080 container fill was drawn opaque ($boxDark dark) or not at half ($boxGrey grey)", boxDark < 50 && boxGrey > 5000)
    }
}
