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
 * A text input strokes `error_border_color` while it is in error, over the focused and resting colours. The view is
 * drawn by the REAL `FormInputTextBlock`; nothing on a step puts it in error yet (remote validation, when it ships, is the
 * first state that will), so the error state is set here directly.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class TextInputBorderRenderTest {
    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private val block: ContentBlock = OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(mapOf(
        "id" to "name", "type" to "input_text", "field_id" to "name", "field_placeholder" to "Name",
        "field_style" to mapOf("border_color" to "#00FF00", "focused_border_color" to "#0000FF", "error_border_color" to "#FF00FF", "border_width" to 3),
    ))))?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    private fun render(hasError: Boolean, tag: String): android.graphics.Bitmap {
        val path = "build/tmp/text-input-border/$tag.png"
        java.io.File(path).parentFile?.mkdirs()
        captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record)) {
            MaterialTheme(colorScheme = lightColorScheme()) {
                CompositionLocalProvider(LocalGoogleAttributionBackdrop provides GoogleMapsAttribution.Backdrop.Plain("#FFFFFF")) {
                    Box(Modifier.fillMaxWidth().background(Color.White).padding(16.dp)) {
                        FormInputTextBlock(block, mutableMapOf(), android.text.InputType.TYPE_CLASS_TEXT, hasError = hasError)
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
    private val magenta = { r: Int, g: Int, b: Int -> r > 220 && g < 40 && b > 220 }
    private val green = { r: Int, g: Int, b: Int -> r < 40 && g > 220 && b < 40 }

    @Test
    fun `a field in error strokes the error border colour`() {
        val resting = render(hasError = false, tag = "resting")
        val error = render(hasError = true, tag = "error")
        assertTrue("control: a resting field strokes its border colour: green=${count(resting, green)}", count(resting, green) > 500)
        assertTrue("control: a resting field draws no error colour: magenta=${count(resting, magenta)}", count(resting, magenta) == 0)
        assertTrue("a field in error strokes error_border_color: magenta=${count(error, magenta)}", count(error, magenta) > 500)
        assertTrue("and not the resting colour: green=${count(error, green)}", count(error, green) == 0)
    }
}
