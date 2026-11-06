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
 * RENDERED, through the real `ContentBlockRendererView`: a `media_gallery` title with no `gallery_title_color`
 * takes the unstyled ink for what is behind it — light on a dark step (it was the theme's dark ink, invisible
 * there), dark on a light step. THE CONTROLS: the title authored white / dark draws, or does not draw, the pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class GalleryTitleInkRenderTest {
    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun block(map: Map<String, Any>): ContentBlock =
        OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(map)))
            ?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    /** Pixels matching [match] in one block rendered on a [bgHex] step. */
    private fun count(b: ContentBlock, bgHex: String, tag: String, match: (Int, Int, Int) -> Boolean): Int {
        val path = "build/tmp/gallery-title/$tag.png"
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

    private val white = { r: Int, g: Int, b: Int -> r > 225 && g > 225 && b > 225 }
    private val black = { r: Int, g: Int, b: Int -> r < 70 && g < 70 && b < 70 }

    private fun gallery(color: String?): ContentBlock = block(buildMap {
        put("id", "g"); put("type", "media_gallery"); put("gallery_title", "WWWWWWWW WWWW")
        put("gallery_images", listOf("https://example.invalid/a.png"))
        if (color != null) put("gallery_title_color", color)
    })

    @Test
    fun `an unstyled gallery title on a dark step is light`() {
        val dark = count(gallery("#111111"), "#0F1117", "dark-dark") { r, g, b -> white(r, g, b) }
        val light = count(gallery("#FFFFFF"), "#0F1117", "dark-white") { r, g, b -> white(r, g, b) }
        val unstyled = count(gallery(null), "#0F1117", "dark-unstyled") { r, g, b -> white(r, g, b) }
        println("[gallery-title dark] dark=$dark white=$light unstyled=$unstyled")
        assertTrue("harness control", light > dark + 100)
        assertTrue("an unstyled gallery title on a dark step is not light: $unstyled vs $dark", unstyled > dark + 100)
    }

    @Test
    fun `an unstyled gallery title on a light step is dark`() {
        val light = count(gallery("#FFFFFF"), "#FFFFFF", "light-white") { r, g, b -> black(r, g, b) }
        val dark = count(gallery("#111111"), "#FFFFFF", "light-dark") { r, g, b -> black(r, g, b) }
        val unstyled = count(gallery(null), "#FFFFFF", "light-unstyled") { r, g, b -> black(r, g, b) }
        println("[gallery-title light] white=$light dark=$dark unstyled=$unstyled")
        assertTrue("harness control", dark > light + 100)
        assertTrue("an unstyled gallery title on a light step is not dark: $unstyled vs $light", unstyled > light + 100)
    }
}
