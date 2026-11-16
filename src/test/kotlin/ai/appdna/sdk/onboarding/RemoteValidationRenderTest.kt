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
 * Live validation — a text input under live validation strokes the colour of its state, drawn by the REAL `FormInputTextBlock`
 * reading the REAL `RemoteValidationCoordinator` through `LocalRemoteValidation`. The host answers synchronously here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class RemoteValidationRenderTest {
    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private val block: ContentBlock = OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(mapOf(
        "id" to "name", "type" to "input_text", "field_id" to "name", "field_placeholder" to "Name",
        "field_config" to mapOf("remote_validation" to mapOf("enabled" to true)),
        "field_style" to mapOf("border_color" to "#00FF00", "error_border_color" to "#FF00FF", "valid_border_color" to "#00FFFF", "border_width" to 3),
    ))))?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    private fun coordinator(answer: InputValidationResult?, hasHost: Boolean = true) = RemoteValidationCoordinator(
        schedule = { _, _ -> {} },
        hasHost = { hasHost },
        startCall = { _, _, _, complete -> complete(RemoteValidationCoordinator.HostOutcome.Answered(answer)); {} },
    )

    private fun render(c: RemoteValidationCoordinator, tag: String): android.graphics.Bitmap {
        val path = "build/tmp/remote-validation/$tag.png"
        java.io.File(path).parentFile?.mkdirs()
        val values = mutableMapOf<String, Any>("name" to "somebody")
        // The restored value is checked on appear; the host answers at once.
        c.valueChanged("name", "somebody", RemoteValidationConfig.of(block)!!, immediate = true)
        captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record)) {
            MaterialTheme(colorScheme = lightColorScheme()) {
                CompositionLocalProvider(
                    LocalGoogleAttributionBackdrop provides GoogleMapsAttribution.Backdrop.Plain("#FFFFFF"),
                    LocalRemoteValidation provides c,
                ) {
                    Box(Modifier.fillMaxWidth().background(Color.White).padding(16.dp)) {
                        FormInputTextBlock(block, values, android.text.InputType.TYPE_CLASS_TEXT)
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
    private val cyan = { r: Int, g: Int, b: Int -> r < 40 && g > 220 && b > 220 }

    @Test
    fun `each answer strokes its border colour`() {
        val none = render(coordinator(null, hasHost = false), "no-delegate")
        val invalid = render(coordinator(InputValidationResult.Invalid("Already taken")), "invalid")
        val valid = render(coordinator(InputValidationResult.Valid()), "valid")
        assertTrue("control: with no delegate the field strokes its resting colour: green=${count(none, green)}", count(none, green) > 500)
        assertTrue("control: and no state colour: magenta=${count(none, magenta)} cyan=${count(none, cyan)}", count(none, magenta) == 0 && count(none, cyan) == 0)
        assertTrue("invalid strokes error_border_color: magenta=${count(invalid, magenta)}", count(invalid, magenta) > 500)
        assertTrue("invalid is not the resting colour: green=${count(invalid, green)}", count(invalid, green) == 0)
        assertTrue("valid strokes valid_border_color: cyan=${count(valid, cyan)}", count(valid, cyan) > 500)
    }
}
