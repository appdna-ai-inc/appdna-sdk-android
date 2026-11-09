package ai.appdna.sdk.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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


/** The selected segment's label contrasts with its authored fill. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class SegmentedSelectedLabelRenderTest {
    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun block(map: Map<String, Any>): ContentBlock =
        OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(map)))
            ?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    private fun render(b: ContentBlock, tag: String): android.graphics.Bitmap {
        val path = "build/tmp/segment-label-chip/$tag.png"
        java.io.File(path).parentFile?.mkdirs()
        captureRoboImage(path) {
            MaterialTheme(colorScheme = lightColorScheme()) {
                CompositionLocalProvider(LocalGoogleAttributionBackdrop provides GoogleMapsAttribution.Backdrop.Plain("#FFFFFF")) {
                    Box(Modifier.fillMaxWidth().background(Color.White).padding(16.dp)) {
                        ContentBlockRendererView(blocks = listOf(b), onAction = {}, toggleValues = mutableMapOf())
                    }
                }
            }
        }
        return android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame at $path")
    }

    private fun count(bmp: android.graphics.Bitmap, match: (Int, Int, Int) -> Boolean): Int {
        var n = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            if (match(android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))) n++
        }
        return n
    }

    private fun segmented(fill: String) = block(mapOf(
        "id" to "seg", "type" to "input_segmented", "field_id" to "goal",
        "field_style" to mapOf("fill_color" to fill),
        "field_options" to listOf(mapOf("value" to "a", "label" to "WWWWWWWWWW"), mapOf("value" to "b", "label" to "WWWWWWWWWW")),
    ))

    @Test
    fun `a selected segment with a light fill draws a dark label`() {
        val bmp = render(segmented("#FFEB3B"), "seg-yellow")
        val fill = count(bmp) { r, g, b -> r > 230 && g > 200 && b < 110 }
        val ink = count(bmp) { r, g, b -> r < 90 && g < 90 && b < 90 }
        assertTrue("control: the selected segment is drawn in its fill: $fill", fill > 500)
        assertTrue("the label on a light fill is dark: $ink", ink > 150)
    }

    @Test
    fun `a selected segment with a dark fill keeps a white label`() {
        val bmp = render(segmented("#1D4ED8"), "seg-blue")
        val ink = count(bmp) { r, g, b -> r > 200 && g > 200 && b > 200 }
        val fill = count(bmp) { r, g, b -> r < 60 && b > 180 }
        assertTrue("control: fill $fill", fill > 500)
        assertTrue("white pixels inside the blue segment: $ink", ink > 2000)
    }
}
