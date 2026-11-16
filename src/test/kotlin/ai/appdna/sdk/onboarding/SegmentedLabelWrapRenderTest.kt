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
 * A segmented answer's label wraps to at most two lines and is then cut, so a row is never taller than two lines of
 * text. Measured by the selected segment's fill, which covers its whole cell.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class SegmentedLabelWrapRenderTest {
    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun block(map: Map<String, Any>): ContentBlock =
        OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(map)))
            ?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    private fun render(b: ContentBlock, tag: String): android.graphics.Bitmap {
        val path = "build/tmp/segment-label-wrap/$tag.png"
        java.io.File(path).parentFile?.mkdirs()
        captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record)) {
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

    /** Pixel rows holding a run of the blue fill: the drawn height of the selected segment. */
    private fun fillHeight(bmp: android.graphics.Bitmap): Int {
        var rows = 0
        for (y in 0 until bmp.height) {
            var n = 0
            for (x in 0 until bmp.width) {
                val c = bmp.getPixel(x, y)
                if (android.graphics.Color.red(c) < 60 && android.graphics.Color.blue(c) > 180) n++
            }
            if (n >= 20) rows++
        }
        return rows
    }

    // The first option is the default answer, so it is the selected (filled) segment.
    private fun segmented(second: String) = block(mapOf(
        "id" to "seg", "type" to "input_segmented", "field_id" to "goal",
        "field_style" to mapOf("fill_color" to "#1D4ED8"),
        "field_options" to listOf(
            mapOf("value" to "a", "label" to "Yes"),
            mapOf("value" to "b", "label" to second),
            mapOf("value" to "c", "label" to "No"),
        ),
    ))

    @Test
    fun `a long label wraps to two lines and no further`() {
        val one = fillHeight(render(segmented("Maybe"), "one-line"))
        val two = fillHeight(render(segmented("Only on the weekends"), "two-line"))
        val many = fillHeight(render(segmented("Only on the weekends when it rains and the shops are closed early"), "many-line"))
        assertTrue("control: a one-line row draws the fill: $one", one > 40)
        assertTrue("a two-line label makes the row taller: one $one, two $two", two >= one + 25)
        assertTrue("a label of four or more lines is cut at two: two $two, many $many", many <= two + 4)
    }
}
