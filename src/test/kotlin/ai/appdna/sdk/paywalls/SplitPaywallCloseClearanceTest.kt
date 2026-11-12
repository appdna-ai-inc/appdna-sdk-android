package ai.appdna.sdk.paywalls

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
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
 * On a split paywall (a wide window: the pitch leading, the plans trailing) the close ✕ sits over the
 * top corner of the trailing pane, so the pane has to start below it. The ✕ is a Material IconButton,
 * whose 48dp minimum touch size is what it actually draws — a reserve sized from the glyph alone
 * (16 + 2 × 16 = 48dp from the top) left the first plan card under the circle.
 *
 * Read off PIXELS: the ✕'s translucent-black circle over a white page, and the first plan's magenta
 * border. The paywall's own padding is 0 here, so nothing but the trailing pane's top reserve keeps
 * the card clear. A control asserts the harness can see both the circle and the card.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h700dp-mdpi")
class SplitPaywallCloseClearanceTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun paywall(): PaywallConfig = PaywallConfigParser.parseSinglePaywall(
        "pw_close",
        mapOf(
            "id" to "pw_close", "name" to "Close",
            "background" to mapOf("type" to "color", "value" to "#FFFFFF"),
            "layout" to mapOf("padding" to 0),
            "dismiss" to mapOf("type" to "x_button", "color" to "#FFFFFF"),
            "sections" to listOf(
                mapOf("id" to "hdr", "type" to "header", "data" to mapOf("title" to "Pitch")),
                mapOf(
                    "id" to "pl", "type" to "plans",
                    "data" to mapOf(
                        "selected_border_color" to "#FF00FF",
                        "plans" to listOf(
                            mapOf("id" to "a", "product_id" to "app.synthetic.a", "label" to "Plan", "price_display" to "\$1", "is_default" to true),
                        ),
                    ),
                ),
            ),
            "cta" to mapOf("text" to "Continue"),
        ),
    ) ?: error("paywall did not parse")

    @Test
    fun `the first plan card starts below the close control on a split paywall`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Box(Modifier.size(840.dp, 673.dp)) {
                    PaywallScreen(config = paywall(), onPlanSelected = { _, _ -> }, onRestore = {}, onDismiss = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        val path = "build/tmp/split-paywall-close/split.png"
        File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame")

        // The trailing pane is the right half of the 840dp surface.
        val paneLeft = 420
        fun isCircle(c: Int): Boolean {
            val r = android.graphics.Color.red(c); val g = android.graphics.Color.green(c); val b = android.graphics.Color.blue(c)
            // Black at 30% over white ≈ (179,179,179): grey, not the white page, not the white glyph.
            return r in 150..205 && kotlin.math.abs(r - g) < 6 && kotlin.math.abs(g - b) < 6
        }
        fun isMagenta(c: Int) = android.graphics.Color.red(c) > 200 && android.graphics.Color.green(c) < 60 && android.graphics.Color.blue(c) > 200

        var circleBottom = -1
        var magentaTop = Int.MAX_VALUE
        for (y in 0 until minOf(bmp.height, 400)) for (x in paneLeft until minOf(bmp.width, 840)) {
            val c = bmp.getPixel(x, y)
            if (x > 700 && y < 150 && isCircle(c)) circleBottom = maxOf(circleBottom, y)
            if (isMagenta(c)) magentaTop = minOf(magentaTop, y)
        }
        assertTrue("control: the harness sees the close circle ($circleBottom)", circleBottom > 0)
        assertTrue("control: the harness sees the first plan's border ($magentaTop)", magentaTop < Int.MAX_VALUE)
        println("[split-close] circle bottom=$circleBottom plan top=$magentaTop")
        assertTrue(
            "the first plan (top y=$magentaTop) starts below the close circle (bottom y=$circleBottom)",
            magentaTop > circleBottom,
        )
    }
}
