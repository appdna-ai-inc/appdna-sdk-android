package ai.appdna.sdk.paywalls

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `pill_selector`, `segmented_toggle` and `product_as_cta` draw a
 * plan's badge. Before this change Android drew it on the pill selector only, so an author's "Best value" never
 * reached a user on the other two.
 *
 * Every badge these layouts draw carries [PaywallPlanBadge.TEST_TAG]; the tests count them and read where they sit.
 * The plans live under `layout.sections[].config.plans`, where a real paywall keeps them.
 *
 * Controls: a paywall with no badge draws no tagged node (so a count is not the harness seeing something else),
 * and a card layout's badge IS seen (so "none" is not the harness being blind).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PlanBadgeLayoutsRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private var ctaTaps = 0

    private fun paywallMap(style: String, badges: List<String?>, position: String?): Map<String, Any> {
        val plans = badges.mapIndexed { i, badge ->
            buildMap<String, Any> {
                put("id", if (i == 0) "plan_annual" else "plan_monthly")
                put("product_id", if (i == 0) "app.premium.annual" else "app.premium.monthly")
                put("label", if (i == 0) "Annual" else "Monthly")
                put("price_display", if (i == 0) "PRICE-ANNUAL" else "PRICE-MONTHLY")
                put("is_default", i == 0)
                put("sort_order", i)
                if (badge != null) put("badge", badge)
            }
        }
        val sectionConfig = buildMap<String, Any> {
            put("plan_display_style", style)
            put("plans", plans)
            if (position != null) put("badge_position", position)
        }
        return mapOf(
            "id" to "pw_plan_badge_$style",
            "name" to "Plan badge",
            "layout" to mapOf("type" to "stack", "sections" to listOf(mapOf("id" to "sec_plans", "type" to "plans", "config" to sectionConfig))),
        )
    }

    private data class Spec(val style: String, val badges: List<String?>, val position: String?)
    private val spec = mutableStateOf<Spec?>(null)
    private var contentSet = false

    /**
     * Renders the real section with a live selection, as `PaywallActivity` does. `setContent` may run once per
     * test, so later renders swap the spec the content reads, under `key` so each one starts fresh.
     */
    private fun render(style: String, badges: List<String?>, position: String? = null) {
        spec.value = Spec(style, badges, position)
        if (!contentSet) {
            contentSet = true
            compose.setContent {
                val s = spec.value ?: return@setContent
                androidx.compose.runtime.key(s) {
                    val config = PaywallConfigParser.parseSinglePaywall("pw_plan_badge_${s.style}", paywallMap(s.style, s.badges, s.position))
                        ?: error("paywall did not parse")
                    val section = config.sections.first { it.type == "plans" }
                    var selected by remember { mutableStateOf(section.data?.plans?.firstOrNull()?.id) }
                    Column {
                        PaywallSectionView(
                            section = section,
                            config = config,
                            selectedPlanId = selected,
                            isPurchasing = false,
                            onPlanSelect = { selected = it },
                            onCTATap = { ctaTaps++ },
                            onRestore = {},
                            loc = { _, fallback -> fallback },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun badges() = compose.onAllNodesWithTag(PaywallPlanBadge.TEST_TAG, useUnmergedTree = true).fetchSemanticsNodes()
    private fun rootWidth() = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
    private fun centreX(i: Int = 0) = badges()[i].boundsInRoot.center.x

    @Test
    fun `control - no badge authored draws no badge in any of the three layouts`() {
        for (style in listOf("pill_selector", "segmented_toggle", "product_as_cta")) {
            render(style, listOf(null, null))
            require(badges().isEmpty()) { "$style drew ${badges().size} badges with none authored" }
        }
    }

    @Test
    fun `control - the harness sees a card layout's badge`() {
        render("vertical_stack", listOf("Best value", null))
        require(badges().size == 1) { "the harness did not see the card's badge; found ${badges().size}" }
    }

    @Test
    fun `a blank badge draws nothing`() {
        for (style in listOf("pill_selector", "segmented_toggle", "product_as_cta")) {
            render(style, listOf("   ", null))
            require(badges().isEmpty()) { "$style drew a badge for a blank string" }
        }
    }

    @Test
    fun `pill_selector draws the badge inside its pill, under the price`() {
        render("pill_selector", listOf("Best value", null))
        require(badges().size == 1) { "expected one badge; found ${badges().size}" }
        val price = compose.onNodeWithText("PRICE-ANNUAL", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val badge = badges()[0].boundsInRoot
        require(badge.top >= price.bottom - 1f) { "the badge is not under the price: badge $badge, price $price" }
        require(badge.center.x < rootWidth() / 2) { "the badge is not in the first pill" }
    }

    @Test
    fun `segmented_toggle draws the badge above the control, over its own segment`() {
        render("segmented_toggle", listOf("Best value", null))
        require(badges().size == 1) { "expected one badge; found ${badges().size}" }
        val label = compose.onNodeWithText("Annual", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        require(badges()[0].boundsInRoot.bottom <= label.top) { "the badge is not above the segment" }
        require(centreX() < rootWidth() / 2) { "the badge is not over the first segment" }

        render("segmented_toggle", listOf(null, "Save 40%"))
        require(centreX() > rootWidth() / 2) { "the badge is not over the second segment" }
    }

    @Test
    fun `product_as_cta draws the badge straddling the button at badge_position, and the button still buys`() {
        render("product_as_cta", listOf("Best value", null))
        require(badges().size == 1) { "expected one badge; found ${badges().size}" }
        val name = compose.onNodeWithText("Annual", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        require(badges()[0].boundsInRoot.top < name.top) { "the badge does not sit at the button's top edge" }
        require(centreX() > rootWidth() / 2) { "default top_right is not on the right" }

        render("product_as_cta", listOf("Best value", null), position = "top-left")
        require(centreX() < rootWidth() / 2) { "top-left is not on the left" }

        render("product_as_cta", listOf("Best value", null), position = "inline")
        require(badges().size == 1) { "an inline badge was not drawn" }

        // Rule 24: the badge sits on the button, and the button is still the purchase.
        ctaTaps = 0
        render("product_as_cta", listOf("Best value", null))
        compose.onNodeWithText("Annual").performClick()
        require(ctaTaps == 1) { "tapping the badged plan did not start the purchase; taps=$ctaTaps" }
    }
}
