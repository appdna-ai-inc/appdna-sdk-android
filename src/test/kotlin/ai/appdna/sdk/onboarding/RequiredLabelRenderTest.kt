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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode


/** A required field's asterisk stays visible and trails the text when the label wraps. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class RequiredLabelRenderTest {
    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun block(map: Map<String, Any>): ContentBlock =
        OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(map)))
            ?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    private fun render(b: ContentBlock, tag: String): android.graphics.Bitmap {
        val path = "build/tmp/segment-label-chip/$tag.png"
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

    private fun count(bmp: android.graphics.Bitmap, match: (Int, Int, Int) -> Boolean): Int {
        var n = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            if (match(android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))) n++
        }
        return n
    }

    private fun field(label: String, required: Boolean) = block(mapOf(
        "id" to "f", "type" to "input_text", "field_id" to "name", "field_label" to label, "field_required" to required,
    ))

    private val long = List(14) { "Wrapped" }.joinToString(" ")
    private val red = { r: Int, g: Int, b: Int -> r > 200 && g < 110 && b < 110 }

    @Test
    fun `the asterisk trails the last line when the label wraps`() {
        val bmp = render(field(long, true), "req-long")
        var redY = 0L; var redN = 0L; var top = Int.MAX_VALUE; var bottom = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            val r = android.graphics.Color.red(c); val g = android.graphics.Color.green(c); val b = android.graphics.Color.blue(c)
            if (red(r, g, b)) { redY += y; redN++ }
            // The label's own ink (#374151), which the field's pale outline is not.
            if (r < 90 && g < 100 && b < 120) { top = minOf(top, y); bottom = maxOf(bottom, y) }
        }
        assertTrue("red asterisk pixels beside a wrapped label: $redN", redN > 10)
        assertTrue("the label wraps in this harness: $top..$bottom", bottom - top > 60)
        val mean = redY / redN
        assertTrue("the asterisk sits on the last line, not the first: y=$mean of $top..$bottom", mean > (top + bottom) / 2)
    }

    @Test
    fun `control - the asterisk is drawn on a one line label and absent when not required`() {
        assertTrue(count(render(field("Short", true), "req-short"), red) > 10)
        assertEquals(0, count(render(field(long, false), "req-optional"), red))
    }
}
