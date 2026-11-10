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
 * RENDERED, through the real `ContentBlockRendererView`, with the step's backdrop handed down as a step hands
 * it: an unstyled `warning_banner` (no `text_color`, no `bg_color`) on a light step draws its message in dark
 * ink, on a dark step in white, and on its own dark `bg_color` in white. The icon is hidden so the only ink
 * counted is the message's. THE CONTROLS: the colour authored each way draws, or does not draw, the pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class WarningBannerInkRenderTest {
    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun block(map: Map<String, Any>): ContentBlock =
        OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(map)))
            ?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    /** Pixels matching [match] in one block rendered on a [bgHex] step. */
    private fun count(b: ContentBlock, bgHex: String, tag: String, match: (Int, Int, Int) -> Boolean): Int {
        val path = "build/tmp/warning-banner-ink/$tag.png"
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

    private val darkPx = { r: Int, g: Int, b: Int -> r < 70 && g < 70 && b < 70 }
    private val whitePx = { r: Int, g: Int, b: Int -> r > 225 && g > 225 && b > 225 }

    private fun banner(text: String?, bg: String? = null): ContentBlock = block(buildMap {
        put("id", "wb"); put("type", "warning_banner"); put("text", "WWWWWWWW WWWWWWWW")
        if (text != null) put("text_color", text)
        if (bg != null) put("bg_color", bg)
        put("field_config", mapOf("banner_variant" to "warning", "banner_hide_icon" to true))
    })

    @Test
    fun `an unstyled banner on a light step draws dark ink`() {
        val white = count(banner("#FFFFFF"), "#FFFFFF", "light-white") { r, g, b -> darkPx(r, g, b) }
        val dark = count(banner("#111111"), "#FFFFFF", "light-dark") { r, g, b -> darkPx(r, g, b) }
        val unstyled = count(banner(null), "#FFFFFF", "light-unstyled") { r, g, b -> darkPx(r, g, b) }
        println("[banner-ink light] white=$white dark=$dark unstyled=$unstyled")
        assertTrue("harness control: $dark vs $white", dark > white + 100)
        assertTrue("an unstyled banner on a light step draws no dark ink: $unstyled vs $white", unstyled > white + 100)
    }

    @Test
    fun `an unstyled banner on a dark step keeps white ink`() {
        val dark = count(banner("#111111"), "#0F1117", "dark-dark") { r, g, b -> whitePx(r, g, b) }
        val white = count(banner("#FFFFFF"), "#0F1117", "dark-white") { r, g, b -> whitePx(r, g, b) }
        val unstyled = count(banner(null), "#0F1117", "dark-unstyled") { r, g, b -> whitePx(r, g, b) }
        println("[banner-ink dark] dark=$dark white=$white unstyled=$unstyled")
        assertTrue("harness control: $white vs $dark", white > dark + 100)
        assertTrue("an unstyled banner on a dark step lost its white ink: $unstyled vs $dark", unstyled > dark + 100)
    }

    @Test
    fun `an authored dark fill on a light step keeps white ink`() {
        val dark = count(banner("#111111", "#1F2937"), "#FFFFFF", "fill-dark") { r, g, b -> whitePx(r, g, b) }
        val white = count(banner("#FFFFFF", "#1F2937"), "#FFFFFF", "fill-white") { r, g, b -> whitePx(r, g, b) }
        val unstyled = count(banner(null, "#1F2937"), "#FFFFFF", "fill-unstyled") { r, g, b -> whitePx(r, g, b) }
        println("[banner-ink fill] dark=$dark white=$white unstyled=$unstyled")
        assertTrue("harness control: $white vs $dark", white > dark + 100)
        assertTrue("an unstyled banner on its own dark fill lost its white ink: $unstyled vs $dark", unstyled > dark + 100)
    }
}
