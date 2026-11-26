package ai.appdna.sdk.paywalls

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
 * a paywall page gradient follows CSS angles, as the console draws it: 180° runs top → bottom, 90° left → right.
 * It used the maths convention, so 180° ran RIGHT → LEFT. The page below is red at its first stop and blue at its last,
 * with nothing drawn over the corners that are sampled.
 * NEGATIVE CONTROL: restoring the `cos`/`sin` mapping in `pageGradientFractions` fails both render cases (the 180° page
 * comes out red on the right, the 90° page red at the bottom).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class PageGradientAngleTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun paywall(angle: Int): PaywallConfig = PaywallConfigParser.parseSinglePaywall(
        "pw_angle",
        mapOf(
            "id" to "pw_angle", "name" to "Synthetic angle paywall",
            "layout" to mapOf("type" to "stack", "background" to mapOf(
                "type" to "gradient",
                "gradient" to mapOf("type" to "linear", "angle" to angle, "stops" to listOf(
                    mapOf("color" to "#FF0000", "position" to 0), mapOf("color" to "#0000FF", "position" to 100),
                )),
            )),
            "sections" to emptyList<Any>(),
            "plans" to emptyList<Any>(),
        ),
    ) ?: error("paywall did not parse")

    /** Red minus blue at a point given as fractions of the frame: > 0 is the first stop's side. */
    private fun redness(angle: Int): (Double, Double) -> Int {
        compose.setContent { PaywallScreen(config = paywall(angle), onPlanSelected = { _, _ -> }, onRestore = {}, onDismiss = {}) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val path = "build/tmp/page-gradient-angle/a$angle.png"
        File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame")
        return { fx, fy ->
            val c = bmp.getPixel((fx * (bmp.width - 1)).toInt(), (fy * (bmp.height - 1)).toInt())
            android.graphics.Color.red(c) - android.graphics.Color.blue(c)
        }
    }

    @Test
    fun `180 degrees runs from the top to the bottom`() {
        val r = redness(180)
        println("[page-gradient] 180 top=${r(0.02, 0.15)} bottom=${r(0.02, 0.85)} left=${r(0.1, 0.5)} right=${r(0.9, 0.5)}")
        assertTrue("the first stop (red) is at the top: ${r(0.02, 0.15)}", r(0.02, 0.15) > 100)
        assertTrue("the last stop (blue) is at the bottom: ${r(0.02, 0.85)}", r(0.02, 0.85) < -100)
        assertTrue("left and right of one row match: ${r(0.1, 0.5)} vs ${r(0.9, 0.5)}", kotlin.math.abs(r(0.1, 0.5) - r(0.9, 0.5)) < 40)
    }

    @Test
    fun `90 degrees runs from the left to the right`() {
        val r = redness(90)
        println("[page-gradient] 90 left=${r(0.1, 0.5)} right=${r(0.9, 0.5)} top=${r(0.02, 0.15)} bottom=${r(0.02, 0.85)}")
        assertTrue("the first stop (red) is on the left: ${r(0.1, 0.5)}", r(0.1, 0.5) > 100)
        assertTrue("the last stop (blue) is on the right: ${r(0.9, 0.5)}", r(0.9, 0.5) < -100)
    }

    @Test
    fun `the fractions are the CSS ones`() {
        assertEquals(listOf(0.5f, 0f, 0.5f, 1f), pageGradientFractions(180.0).map { Math.round(it * 1000) / 1000f })
        assertEquals(listOf(0f, 0.5f, 1f, 0.5f), pageGradientFractions(90.0).map { Math.round(it * 1000) / 1000f })
        assertEquals(listOf(0.5f, 1f, 0.5f, 0f), pageGradientFractions(0.0).map { Math.round(it * 1000) / 1000f })
    }
}
