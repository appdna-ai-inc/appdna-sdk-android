package ai.appdna.sdk.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RENDERED: a `wheel`-style measurement picker draws its band, the value on the band, the rows around it, the unit
 * label and both toggle labels in the colours authored for them — each a pure primary, counted in the captured frame.
 * Before, the band came from `track_color`, the value on it and every row from the accent, and the unit label and toggle
 * labels from the theme, so none of these keys reached a pixel.
 *
 * THE CONTROL: the same picker with nothing authored draws none of those primaries, so a pass is the authored keys
 * reaching the frame, not a harness that finds a colour everywhere.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class MeasurementPaletteRenderTest {
    @get:Rule
    val compose = createComposeRule()

    @org.junit.Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private var frame = 0

    private val units = listOf(
        mapOf("id" to "kg", "label" to "kg", "min" to 30, "max" to 200, "step" to 0.5, "decimals" to 1, "factor" to 1, "offset" to 0),
        mapOf("id" to "lbs", "label" to "lbs", "min" to 66, "max" to 441, "step" to 1, "decimals" to 0, "factor" to 2.20462, "offset" to 0),
    )

    private fun frame(colours: Map<String, String>): android.graphics.Bitmap {
        val cfg = mapOf<String, Any>(
            "measurement_type" to "weight", "measurement_style" to "wheel", "measurement_default" to 115,
            "unit_default" to "kg", "units" to units, "value_font_size" to 60, "unit_font_size" to 60,
        ) + colours
        val block = ContentBlock(id = "w", type = "wheel_picker", field_id = "weight", highlight_color = "#888888", field_config = cfg)
        val config = parseMeasurementConfig(block) ?: error("measurement mode must resolve")
        compose.setContent {
            Box(Modifier.fillMaxWidth().background(Color.White)) { MeasurementWheelBlock(block, config, mutableMapOf()) }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val path = "build/tmp/measurement-palette/frame-${frame++}.png"
        java.io.File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        return android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame at $path")
    }

    /** Pixels within a small distance of `rgb`, opaque. */
    private fun count(bmp: android.graphics.Bitmap, rgb: Int): Int {
        var n = 0
        val r = android.graphics.Color.red(rgb); val g = android.graphics.Color.green(rgb); val b = android.graphics.Color.blue(rgb)
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            if (android.graphics.Color.alpha(c) > 240 &&
                kotlin.math.abs(android.graphics.Color.red(c) - r) < 12 &&
                kotlin.math.abs(android.graphics.Color.green(c) - g) < 12 &&
                kotlin.math.abs(android.graphics.Color.blue(c) - b) < 12) n++
        }
        return n
    }

    private val authored = mapOf(
        "band_color" to "#FF0000",
        "band_text_color" to "#0000FF",
        "row_text_color" to "#00FF00",
        "unit_color" to "#FF00FF",
        "toggle_active_color" to "#FFFF00",
        "toggle_active_text_color" to "#00FFFF",
        "toggle_text_color" to "#800000",
    )

    /**
     * The rows around the band fade with distance (iOS's rule), so their pure green arrives blended over white:
     * counted as strongly green-dominant pixels rather than as the exact authored value.
     */
    private fun greenDominant(bmp: android.graphics.Bitmap): Int {
        var n = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            val r = android.graphics.Color.red(c); val g = android.graphics.Color.green(c); val b = android.graphics.Color.blue(c)
            if (g > 200 && g - r > 80 && g - b > 80) n++
        }
        return n
    }

    private fun drawn(bmp: android.graphics.Bitmap, key: String, hex: String): Int =
        if (key == "row_text_color") greenDominant(bmp) else count(bmp, android.graphics.Color.parseColor(hex))

    @Test fun everyAuthoredPartReachesThePixels() {
        val bmp = frame(authored)
        for ((key, hex) in authored) {
            val n = drawn(bmp, key, hex)
            assertTrue("$key $hex drew $n pixels", n > 15)
        }
    }

    @Test fun theControlNothingAuthoredDrawsNoneOfThem() {
        val bmp = frame(emptyMap())
        for ((key, hex) in authored) {
            val n = drawn(bmp, key, hex)
            assertTrue("unauthored, $key's colour $hex still drew $n pixels", n <= 15)
        }
    }
}
