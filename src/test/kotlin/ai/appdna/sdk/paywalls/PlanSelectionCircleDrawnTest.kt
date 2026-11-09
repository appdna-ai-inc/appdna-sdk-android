package ai.appdna.sdk.paywalls

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
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
 * RENDERED, through the real `PaywallScreen`: the default `vertical_stack` plan card draws iOS's trailing selection
 * circle (PlanCard.swift's `checkmark.circle.fill`), tinted with the selected border colour. THE CONTROL: the shared
 * `grid` card has the same coloured border and no circle, so an interior-pixel count is not just finding the border.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class PlanSelectionCircleDrawnTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun paywall(displayStyle: String): PaywallConfig = PaywallConfigParser.parseSinglePaywall(
        "pw_circle",
        mapOf(
            "id" to "pw_circle", "name" to "Circle",
            "background" to mapOf("type" to "color", "value" to "#FFFFFF"),
            "sections" to listOf(mapOf(
                "id" to "sec_plans", "type" to "plans",
                "data" to mapOf(
                    "plan_display_style" to displayStyle,
                    "selected_border_color" to "#FF00FF",
                    "plans" to listOf(mapOf(
                        "id" to "plan_a", "product_id" to "app.synthetic.annual",
                        "label" to "Plan", "price_display" to "\$1", "is_default" to true,
                    )),
                ),
            )),
            "cta" to mapOf("text" to "Continue"),
        ),
    ) ?: error("paywall did not parse")

    private val styleState = androidx.compose.runtime.mutableStateOf("")
    private val magenta = { c: Int ->
        android.graphics.Color.red(c) > 200 && android.graphics.Color.green(c) < 60 && android.graphics.Color.blue(c) > 200
    }

    /** Magenta pixels inside each row's magenta extent, leaving the border columns and full-width border rows out. */
    private fun interiorMagentaPixels(displayStyle: String): Int {
        if (styleState.value.isEmpty()) {
            compose.mainClock.autoAdvance = false
            styleState.value = displayStyle
            compose.setContent {
                val style = styleState.value
                androidx.compose.runtime.key(style) {
                    PaywallScreen(config = paywall(style), onPlanSelected = { _, _ -> }, onRestore = {}, onDismiss = {})
                }
            }
        } else {
            styleState.value = displayStyle
        }
        compose.mainClock.advanceTimeBy(1_000)
        val path = "build/tmp/plan-selection-circle/$displayStyle.png"
        File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame")
        var interior = 0
        for (y in 0 until bmp.height) {
            val xs = (0 until bmp.width).filter { magenta(bmp.getPixel(it, y)) }
            if (xs.size < 2) continue
            val left = xs.first() + 12
            val right = xs.last() - 12
            val inside = xs.count { it in left..right }
            if (inside * 2 < right - left) interior += inside
        }
        return interior
    }

    @Test
    fun `every plan layout draws the selection circle`() {
        for (style in listOf("vertical_stack", "grid", "horizontal_scroll", "carousel", "mini_cards", "carousel_cards", "single_hero")) {
            val n = interiorMagentaPixels(style)
            println("[plan-circle] $style=$n")
            assertTrue("$style drew no selection circle ($n)", n > 50)
        }
    }
}
