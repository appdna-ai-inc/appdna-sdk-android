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
 * RENDERED, through the real `PaywallScreen`: a plans section whose Style tab authors a colour for the plan name
 * (`elements.plan_name.text_style.color`) and the price (`elements.price.text_style.color`) draws those colours on
 * the plan card, as iOS's PlanCard does — the tile's readable ink is only the default. THE CONTROL: the same
 * paywall without the style colours draws none of them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class PlanStyleColourRenderTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun paywall(styled: Boolean, priceColor: String? = null): PaywallConfig = PaywallConfigParser.parseSinglePaywall(
        "pw_style",
        buildMap {
            put("id", "pw_style"); put("name", "Style")
            put("background", mapOf("type" to "color", "value" to "#FFFFFF"))
            put("sections", listOf(
                buildMap<String, Any> {
                    put("id", "sec_plans"); put("type", "plans")
                    put("data", mapOf(
                        "plan_display_style" to "vertical_stack",
                        "plans" to listOf(mapOf(
                            "id" to "plan_a", "product_id" to "app.synthetic.annual",
                            "label" to "WWWWWWWW", "price_display" to "WWWWWW", "is_default" to true,
                        ) + (if (priceColor != null) mapOf("price_color" to priceColor) else emptyMap())),
                    ))
                    if (styled) put("style", mapOf("elements" to mapOf(
                        "plan_name" to mapOf("text_style" to mapOf("color" to "#FF0000", "font_size" to 22)),
                        "price" to mapOf("text_style" to mapOf("color" to "#00C000", "font_size" to 22)),
                    )))
                },
            ))
            put("cta", mapOf("text" to "Continue"))
        },
    ) ?: error("paywall did not parse")

    private val styledState = androidx.compose.runtime.mutableStateOf<Boolean?>(null)
    private var priceColor: String? = null

    /** One composition per test (a compose rule allows one `setContent`); the paywall is swapped by state. */
    private fun count(styled: Boolean, match: (Int, Int, Int) -> Boolean): Int {
        if (styledState.value == null) {
            compose.mainClock.autoAdvance = false
            styledState.value = styled
            compose.setContent {
                val st = styledState.value ?: false
                androidx.compose.runtime.key(st) {
                    PaywallScreen(config = paywall(st, priceColor), onPlanSelected = { _, _ -> }, onRestore = {}, onDismiss = {})
                }
            }
        } else {
            styledState.value = styled
        }
        compose.mainClock.advanceTimeBy(1_000)
        val path = "build/tmp/plan-style-colour/${if (styled) "styled" else "plain"}.png"
        File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame")
        var n = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            if (match(android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))) n++
        }
        return n
    }

    private val red = { r: Int, g: Int, b: Int -> r > 200 && g < 60 && b < 60 }
    private val green = { r: Int, g: Int, b: Int -> r < 60 && g > 160 && b < 60 }

    @Test
    fun `the plan name colour from the Style tab is drawn`() {
        val plain = count(false, red); val styled = count(true, red)
        println("[plan-style name] plain=$plain styled=$styled")
        assertTrue("the Style tab's plan-name colour lost to the tile ink ($styled vs $plain)", styled > plain + 100)
    }

    @Test
    fun `the price colour from the Style tab is drawn`() {
        val plain = count(false, green); val styled = count(true, green)
        println("[plan-style price] plain=$plain styled=$styled")
        assertTrue("the Style tab's price colour lost to the tile ink ($styled vs $plain)", styled > plain + 100)
    }

    @Test
    fun `a plan's own price colour is drawn in the default vertical stack`() {
        priceColor = "#00C000"
        val withPriceColour = count(false, green)
        println("[plan-style price_color] with=$withPriceColour")
        assertTrue("the plan's price_color is not drawn ($withPriceColour)", withPriceColour > 100)
    }
}
