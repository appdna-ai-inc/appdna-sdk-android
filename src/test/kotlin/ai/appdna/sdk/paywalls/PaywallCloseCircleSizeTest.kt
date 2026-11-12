package ai.appdna.sdk.paywalls

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The paywall's close ✕ is drawn at `2 × glyph`, as iOS draws it and as the header title's side reserve
 * (`PaywallHeaderInset.resolve`, shared fixture `paywall_header_close_inset`) assumes. It is a Material
 * `IconButton`, whose 48dp minimum touch target used to be the size the circle was DRAWN at, so the
 * circle reached 8dp further in than the reserve, and a header title on a paywall with tight padding
 * ran under it.
 *
 * Read off pixels at a 390dp phone width with the paywall's padding at 0: the circle's diameter, and
 * whether any of the header title's ink falls in the circle's columns on the circle's rows.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-mdpi")
class PaywallCloseCircleSizeTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun paywall(): PaywallConfig = PaywallConfigParser.parseSinglePaywall(
        "pw_circle_size",
        mapOf(
            "id" to "pw_circle_size", "name" to "Circle",
            "background" to mapOf("type" to "color", "value" to "#FFFFFF"),
            "layout" to mapOf("padding" to 0),
            "dismiss" to mapOf("type" to "x_button", "color" to "#FFFFFF"),
            "sections" to listOf(
                mapOf(
                    "id" to "hdr", "type" to "header",
                    "data" to mapOf("title" to "MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM"),
                ),
            ),
            "cta" to mapOf("text" to "Continue"),
        ),
    ) ?: error("paywall did not parse")

    @Test
    fun `the close circle is 2 x glyph wide and the header title stays out of it`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                PaywallScreen(config = paywall(), onPlanSelected = { _, _ -> }, onRestore = {}, onDismiss = {})
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        val path = "build/tmp/paywall-close-circle/phone.png"
        File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame")

        fun grey(c: Int): Boolean {
            val r = android.graphics.Color.red(c); val g = android.graphics.Color.green(c); val b = android.graphics.Color.blue(c)
            return r in 150..205 && kotlin.math.abs(r - g) < 6 && kotlin.math.abs(g - b) < 6
        }
        fun ink(c: Int) = android.graphics.Color.red(c) < 110 && android.graphics.Color.green(c) < 110 && android.graphics.Color.blue(c) < 110

        // The circle, measured along its own centre lines so the title's anti-aliased edges cannot count:
        // its vertical extent down the column 32dp in from the right edge (the circle's centre on both
        // the old and the new drawing), then its horizontal extent along its middle row.
        val cx = bmp.width - 32
        var top = Int.MAX_VALUE; var bottom = -1
        for (y in 0 until 120) if (grey(bmp.getPixel(cx, y))) { top = minOf(top, y); bottom = maxOf(bottom, y) }
        val cy = (top + bottom) / 2
        var left = Int.MAX_VALUE; var right = -1
        for (x in (bmp.width - 90) until bmp.width) if (grey(bmp.getPixel(x, cy))) { left = minOf(left, x); right = maxOf(right, x) }
        assertTrue("control: the harness sees the circle", bottom > 0)
        // Control: the title's ink is on screen at all.
        var inkAnywhere = 0
        for (y in 0 until 200) for (x in 0 until bmp.width) if (ink(bmp.getPixel(x, y))) inkAnywhere++
        assertTrue("control: the harness sees the header title's ink", inkAnywhere > 200)

        val diameter = right - left + 1
        println("[close-circle] x $left..$right y $top..$bottom diameter=$diameter")
        assertEquals("the circle is 2 × 16dp wide (was the 48dp touch target)", 32.0, diameter.toDouble(), 2.0)

        var inkUnderCircle = 0
        for (y in top..bottom) for (x in (left + 1)..(right - 1)) if (ink(bmp.getPixel(x, y))) inkUnderCircle++
        assertEquals("no header-title ink inside the circle's box", 0, inkUnderCircle)
    }
}
