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
 * The selected segment's fill covers its whole cell, whatever the row's height. A neighbour whose label
 * wraps to two lines makes the row taller; the selected fill used to keep its own one-line height and
 * cover only the top of its cell.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class SegmentedFillHeightRenderTest {
    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun block(map: Map<String, Any>): ContentBlock =
        OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(map)))
            ?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    private fun render(b: ContentBlock, tag: String): android.graphics.Bitmap {
        val path = "build/tmp/segment-fill-height/$tag.png"
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
    fun `the selected fill grows with a row a two-line neighbour makes taller`() {
        val oneLine = fillHeight(render(segmented("Maybe"), "one-line"))
        val twoLine = fillHeight(render(segmented("Only on the weekends when it rains"), "two-line"))
        assertTrue("control: a one-line row draws the fill: $oneLine", oneLine > 40)
        assertTrue("the fill covers the taller row: one-line $oneLine px, two-line $twoLine px", twoLine >= oneLine + 25)
    }
}
