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
 * On a GRADIENT page with no `selected_bg_color`, the selected plan tile was painted `#FFFFFF`, because
 * `selectedTileHex` composites over `pageBackgroundHex()` and that is null for a gradient. A light
 * `selected_text_color` (#F3F4F6, the generator's dark-theme shape) was then unreadable.
 *
 * The frame is a dark gradient. Near-white pixels in it can only come from the text, the badge or a white tile:
 * with the fix, a two-plan stack has a few thousand (glyphs); with the white tile it has tens of thousands.
 * NEGATIVE CONTROL: restoring `StyleEngine.parseColor(selectedTileHex ?: "#FFFFFF")` fails the first case.
 * The second case is the same paywall on a FLAT dark page, where the tile was always derived: it pins that the
 * count really measures the tile and that the flat path did not move.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class SelectedPlanTileOnGradientTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun paywall(background: Map<String, Any>): PaywallConfig = PaywallConfigParser.parseSinglePaywall(
        "pw_gradient_tile",
        mapOf(
            "id" to "pw_gradient_tile", "name" to "Synthetic gradient paywall",
            "layout" to mapOf("type" to "stack", "background" to background),
            "sections" to listOf(mapOf(
                "id" to "plans", "type" to "plans",
                "config" to mapOf(
                    "selected_text_color" to "#F3F4F6",
                    "selected_border_color" to "#3B82F6",
                ),
            )),
            "plans" to listOf(
                mapOf("id" to "plan_a", "product_id" to "app.synthetic.annual", "label" to "Annual plan", "price_display" to "\$29.99/year", "is_default" to true),
                mapOf("id" to "plan_b", "product_id" to "app.synthetic.monthly", "label" to "Monthly plan", "price_display" to "\$3.99/month"),
            ),
            "cta" to mapOf("text" to "Continue"),
        ),
    ) ?: error("paywall did not parse")

    private fun nearWhitePixels(config: PaywallConfig, name: String): Int {
        compose.setContent { PaywallScreen(config = config, onPlanSelected = { _, _ -> }, onRestore = {}, onDismiss = {}) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val path = "build/tmp/selected-plan-tile/$name.png"
        File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame")
        var n = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            if (android.graphics.Color.red(c) > 225 && android.graphics.Color.green(c) > 225 && android.graphics.Color.blue(c) > 225) n++
        }
        return n
    }

    private val gradient = mapOf(
        "type" to "gradient",
        "gradient" to mapOf("type" to "linear", "angle" to 180, "stops" to listOf(
            mapOf("color" to "#0F172A", "position" to 0), mapOf("color" to "#1E293B", "position" to 100),
        )),
    )

    @Test
    fun `a gradient page does not paint the selected plan white`() {
        val n = nearWhitePixels(paywall(gradient), "gradient")
        println("[selected-tile] gradient near-white=$n")
        assertTrue("near-white pixels on a dark gradient page should be glyphs only, not a white tile: $n", n < 12_000)
    }

    @Test
    fun `control - the same paywall on a flat dark page`() {
        val n = nearWhitePixels(paywall(mapOf("type" to "color", "value" to "#0F172A")), "flat")
        println("[selected-tile] flat near-white=$n")
        assertTrue("flat dark page: glyphs only: $n", n < 12_000)
    }
}
