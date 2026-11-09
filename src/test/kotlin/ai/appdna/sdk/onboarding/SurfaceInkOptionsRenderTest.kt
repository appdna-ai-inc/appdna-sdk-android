package ai.appdna.sdk.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
 * Unstyled option and segmented labels take their ink from the surface they are drawn on, not from the phone's
 * appearance: a light step in a dark theme is dark ink, a dark step in a light theme is light ink. Authored colours win.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class SurfaceInkOptionsRenderTest {
    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun block(map: Map<String, Any>): ContentBlock =
        OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(map)))
            ?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    /** Pixels matching [match] in one block on a [bgHex] step, under a [darkTheme] system theme. */
    private fun count(b: ContentBlock, bgHex: String, darkTheme: Boolean, tag: String, match: (Int, Int, Int) -> Boolean): Int {
        val path = "build/tmp/surface-ink/$tag.png"
        java.io.File(path).parentFile?.mkdirs()
        captureRoboImage(path) {
            MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalGoogleAttributionBackdrop provides GoogleMapsAttribution.Backdrop.Plain(bgHex)) {
                    Box(Modifier.fillMaxWidth().background(Color(android.graphics.Color.parseColor(bgHex))).padding(16.dp)) {
                        ContentBlockRendererView(blocks = listOf(b), onAction = {}, toggleValues = mutableMapOf())
                    }
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
    private val nearBlack = { r: Int, g: Int, b: Int -> r < 90 && g < 90 && b < 90 }
    private val red = { r: Int, g: Int, b: Int -> r > 200 && g < 80 && b < 80 }

    private val options = listOf(mapOf("value" to "a", "label" to "WWWWWWWWWW"), mapOf("value" to "b", "label" to "WWWWWWWWWW"))

    private fun select(style: String, textColor: String? = null, fill: String? = null): ContentBlock = block(buildMap {
        put("id", "sel"); put("type", "input_select"); put("field_id", "goal"); put("field_options", options)
        put("field_config", buildMap<String, Any> {
            put("display_style", style)
            if (textColor != null) put("text_color", textColor)
            if (fill != null) put("bg_color", fill)
        })
    })

    private val segmented: ContentBlock get() = block(mapOf(
        "id" to "seg", "type" to "input_segmented", "field_id" to "goal", "field_options" to options,
    ))

    private val styles = listOf("list", "stacked", "grid", "bubble")

    @Test
    fun `every option style is dark ink on a white step in a dark theme`() {
        for (style in styles) {
            val unstyled = count(select(style), "#FFFFFF", true, "$style-white-dark") { r, g, b -> nearBlack(r, g, b) }
            val control = count(select(style, "#000000"), "#FFFFFF", true, "$style-white-dark-ctl") { r, g, b -> nearBlack(r, g, b) }
            println("[surface-ink] $style white-in-dark unstyled=$unstyled control=$control")
            assertTrue("harness control for $style: $control", control > 200)
            assertTrue("$style labels on white in a dark theme: $unstyled vs $control", unstyled > control / 2)
        }
    }

    @Test
    fun `every option style is light ink on a dark step in a light theme`() {
        for (style in styles) {
            val unstyled = count(select(style), "#0B1220", false, "$style-dark-light") { r, g, b -> nearWhite(r, g, b) }
            val control = count(select(style, "#FFFFFF"), "#0B1220", false, "$style-dark-light-ctl") { r, g, b -> nearWhite(r, g, b) }
            println("[surface-ink] $style dark-in-light unstyled=$unstyled control=$control")
            assertTrue("harness control for $style: $control", control > 200)
            assertTrue("$style labels on a dark step in a light theme: $unstyled vs $control", unstyled > control / 2)
        }
    }

    private val darkish = { r: Int, g: Int, b: Int -> r < 140 && g < 140 && b < 140 }
    private val lightish = { r: Int, g: Int, b: Int -> r > 150 && g > 150 && b > 150 }

    @Test
    fun `segmented labels follow the surface in both directions`() {
        // No authored colour exists for these labels, so the control is the same control in the theme that matches its surface.
        val onWhite = count(segmented, "#FFFFFF", true, "seg-white-dark") { r, g, b -> darkish(r, g, b) }
        val onWhiteCtl = count(segmented, "#FFFFFF", false, "seg-white-light") { r, g, b -> darkish(r, g, b) }
        assertTrue("segmented on white in a dark theme: $onWhite vs $onWhiteCtl", onWhiteCtl > 200 && onWhite > onWhiteCtl / 2)
        val onDark = count(segmented, "#0B1220", false, "seg-dark-light") { r, g, b -> lightish(r, g, b) }
        val onDarkCtl = count(segmented, "#0B1220", true, "seg-dark-dark") { r, g, b -> lightish(r, g, b) }
        assertTrue("segmented on dark in a light theme: $onDark vs $onDarkCtl", onDarkCtl > 200 && onDark > onDarkCtl / 2)
    }

    @Test
    fun `an option with its own light fill keeps dark ink on a dark step`() {
        val inked = count(select("stacked", fill = "#FFFFFF"), "#0B1220", false, "fill-light-on-dark") { r, g, b -> nearBlack(r, g, b) }
        val control = count(select("stacked", "#000000", "#FFFFFF"), "#0B1220", false, "fill-light-on-dark-ctl") { r, g, b -> nearBlack(r, g, b) }
        assertTrue("label on an authored light fill: $inked vs $control", inked > control / 2)
    }

    @Test
    fun `an authored label colour still wins in a mismatched theme`() {
        for (style in styles) {
            val n = count(select(style, "#FF0000"), "#FFFFFF", true, "$style-authored") { r, g, b -> red(r, g, b) }
            assertTrue("authored red on $style: $n", n > 200)
        }
    }
}
