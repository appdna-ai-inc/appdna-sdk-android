package ai.appdna.sdk.paywalls

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
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

private const val LONG_NAME = "Premium Monthly Plan"
private const val BADGE_HEX = "#FF00AA"
private const val BADGE_RGB = 0xFF00AA

private fun plansPaywall(style: String) = mapOf(
    "id" to "pw_placement",
    "name" to "Placement",
    "layout" to mapOf(
        "type" to "stack",
        "background" to mapOf("type" to "color", "value" to "#FFFFFF"),
        "sections" to listOf(
            mapOf(
                "id" to "sec_plans",
                "type" to "plans",
                "data" to mapOf("plan_display_style" to style, "badge_bg_color" to BADGE_HEX),
            ),
        ),
    ),
    "plans" to listOf(
        mapOf(
            "id" to "p_month", "label" to LONG_NAME, "price_display" to "$6.99",
            "badge" to "Best value", "is_default" to true,
        ),
        mapOf("id" to "p_year", "label" to "Annual", "price_display" to "$39.99"),
    ),
)

private fun planConfig(style: String): PaywallConfig =
    PaywallConfigParser.parseSinglePaywall("pw_placement", plansPaywall(style))
        ?: error("the fixture paywall did not parse")

/** The layouts drawn by the shared plan card, and the one that has always been right, which is the control. */
private val CARD_STYLES = listOf("grid", "mini_cards", "horizontal_scroll", "carousel_cards", "single_hero")
private const val CONTROL_STYLE = "vertical_stack"

/**
 * Where the selection circle sits: in its own column at the card's trailing edge, so the plan's name
 * never runs under it. iOS gets this from the row its card is built on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class PlanCardCirclePlacementTest {

    @get:Rule
    val compose = createComposeRule()

    private fun assertCircleClearsName(style: String) {
        val config = planConfig(style)
        compose.setContent {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                PaywallSectionView(
                    section = config.sections.first { it.type == "plans" },
                    config = config,
                    selectedPlanId = "p_month",
                    isPurchasing = false,
                    onPlanSelect = {},
                    onCTATap = {},
                    onRestore = {},
                    loc = { _, fallback -> fallback },
                )
            }
        }
        val name = compose.onAllNodesWithText(LONG_NAME, useUnmergedTree = true)[0].getUnclippedBoundsInRoot()
        val circle = compose.onAllNodesWithTag(PLAN_SELECTION_CIRCLE_TAG, useUnmergedTree = true)[0].getUnclippedBoundsInRoot()
        assertTrue(
            "$style: the circle (from ${circle.left}) must start after the plan name ends (${name.right})",
            circle.left >= name.right,
        )
    }

    @Test fun `vertical_stack keeps the circle clear of the name`() = assertCircleClearsName(CONTROL_STYLE)
    @Test fun `grid keeps the circle clear of the name`() = assertCircleClearsName("grid")
    @Test fun `mini_cards keeps the circle clear of the name`() = assertCircleClearsName("mini_cards")
    @Test fun `horizontal_scroll keeps the circle clear of the name`() = assertCircleClearsName("horizontal_scroll")
    @Test fun `carousel_cards keeps the circle clear of the name`() = assertCircleClearsName("carousel_cards")
    @Test fun `single_hero keeps the circle clear of the name`() = assertCircleClearsName("single_hero")
}

/**
 * The badge straddles the card's top edge whole. A clip is invisible to layout bounds, so this counts
 * the badge's own colour in rendered frames, against the layout that draws it whole.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class PlanCardBadgeDrawnWholeTest {

    @Before
    fun enableCapture() {
        // These tests compare their own frames, so they record whichever gradle task runs them.
        System.setProperty("roborazzi.test.record", "true")
    }

    private fun badgePixels(style: String): Int {
        val config = planConfig(style)
        val path = "build/tmp/plan-card-placement/$style.png"
        File(path).parentFile?.mkdirs()
        captureRoboImage(path) {
            Column(Modifier.fillMaxWidth().background(Color.White).padding(16.dp)) {
                PaywallSectionView(
                    section = config.sections.first { it.type == "plans" },
                    config = config,
                    selectedPlanId = "p_month",
                    isPurchasing = false,
                    onPlanSelect = {},
                    onCTATap = {},
                    onRestore = {},
                    loc = { _, fallback -> fallback },
                )
            }
        }
        val image = BitmapFactory.decodeFile(path) ?: error("no frame was decoded from $path")
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (image.getPixel(x, y) and 0xFFFFFF == BADGE_RGB) count++
        }
        return count
    }

    @Test
    fun `every card layout draws the badge as whole as vertical_stack does`() {
        val whole = badgePixels(CONTROL_STYLE)
        assertTrue("the harness must see the control's badge at all (saw $whole px)", whole > 400)
        val drawn = CARD_STYLES.associateWith { badgePixels(it) }
        drawn.forEach { (style, pixels) ->
            assertTrue("$style draws $pixels badge pixels, the whole badge is $whole", pixels >= whole * 0.9)
        }
    }
}
