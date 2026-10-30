package ai.appdna.sdk.paywalls

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `tab_groups` draws a tab per plan, and the TAB SELECTS WHICH CARD IS SHOWN.
 *
 * The console has offered this style in its picker since it shipped and neither renderer had an arm
 * for it, so an author picked "Tab groups", saw the console preview draw it, published, and every
 * device drew the default vertical stack. Both dispatches end in a default arm, so there was no
 * error, no warning and no blank screen — just the wrong layout.
 *
 * 🔴 THE TAP IS THE TEST, not the screenshot. Rule 24: a control that draws perfectly and does
 * nothing is the defect this repo keeps shipping — a chevron that drew, five rating stars that drew and
 * collected nothing. A tab bar exists to switch panels, so the assertion that matters is that the
 * body CHANGES when a tab is tapped, and that only one plan's card is present at a time.
 *
 * The plans live under `layout.sections[].config.plans` where a real paywall keeps them, not
 * at the document root — a fixture that put them at the root would pass against code that reaches
 * no real paywall.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TabGroupsRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun paywallMap(): Map<String, Any> = mapOf(
        "id" to "pw_tab_groups",
        "name" to "Tab groups",
        "layout" to mapOf(
            "type" to "stack",
            "sections" to listOf(
                mapOf(
                    "id" to "sec_plans",
                    "type" to "plans",
                    "config" to mapOf(
                        "plan_display_style" to "tab_groups",
                        "plans" to listOf(
                            mapOf(
                                "id" to "plan_annual",
                                "product_id" to "app.premium.annual",
                                "label" to "Annual",
                                "price_display" to "PRICE-ANNUAL",
                                "is_default" to true,
                                "sort_order" to 0,
                            ),
                            mapOf(
                                "id" to "plan_monthly",
                                "product_id" to "app.premium.monthly",
                                "label" to "Monthly",
                                "price_display" to "PRICE-MONTHLY",
                                "is_default" to false,
                                "sort_order" to 1,
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    /**
     * Renders the real section with a live selection, exactly as `PaywallActivity` does: the
     * selected plan is STATE, and `onPlanSelect` is what moves it. Hard-coding the selection would
     * make the tap assertion below impossible to fail.
     */
    private fun render() {
        val config = PaywallConfigParser.parseSinglePaywall("pw_tab_groups", paywallMap())
            ?: error("paywall did not parse")
        val section = config.sections.first { it.type == "plans" }
        compose.setContent {
            var selected by remember { mutableStateOf(section.data?.plans?.firstOrNull()?.id) }
            Column {
                PaywallSectionView(
                    section = section,
                    config = config,
                    selectedPlanId = selected,
                    isPurchasing = false,
                    onPlanSelect = { selected = it },
                    onCTATap = {},
                    onRestore = {},
                    loc = { _, fallback -> fallback },
                )
            }
        }
    }

    @Test
    fun `a tab is drawn for EVERY plan, including the unselected one`() {
        render()
        /*
         * 🔴 COUNTS, not `onNodeWithText`, and the counts are the assertion.
         *
         * The first version of this test used `onNodeWithText("Annual")` and failed with "Expected
         * at most 1 node but found 2" — because the SELECTED plan's name appears twice, once on its
         * tab and once inside the card beneath. That is the layout working, and the failure was
         * worth keeping as the shape of the assertion: the selected label appears TWICE, the
         * unselected one ONCE.
         *
         * The unselected count is what proves this is a tab bar at all. "Monthly" has no card
         * rendered (the next test asserts its price is absent), so its single node can only be a
         * tab — which is exactly what `vertical_stack` would NOT produce.
         */
        val annual = compose.onAllNodesWithText("Annual", substring = true).fetchSemanticsNodes().size
        val monthly = compose.onAllNodesWithText("Monthly", substring = true).fetchSemanticsNodes().size
        require(annual == 2) { "the selected plan's name belongs on its tab AND in its card; found $annual" }
        require(monthly == 1) { "the unselected plan needs a tab and no card; found $monthly" }
    }

    @Test
    fun `only the SELECTED plan's card is beneath the tabs`() {
        render()
        compose.onNodeWithText("PRICE-ANNUAL", substring = true).assertIsDisplayed()
        // The unselected plan's price must be ABSENT, not merely off-screen: the whole point of
        // this style over `vertical_stack` is that one panel shows at a time.
        compose.onAllNodesWithText("PRICE-MONTHLY", substring = true)
            .fetchSemanticsNodes()
            .let { require(it.isEmpty()) { "the unselected plan's card must not be rendered; found ${it.size}" } }
    }

    @Test
    fun `TAPPING a tab swaps the card beneath it`() {
        render()
        compose.onNodeWithText("PRICE-ANNUAL", substring = true).assertIsDisplayed()

        compose.onNodeWithText("Monthly", substring = true).performClick()

        // The body followed the tap — the assertion a screenshot cannot make.
        compose.onNodeWithText("PRICE-MONTHLY", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("PRICE-ANNUAL", substring = true)
            .fetchSemanticsNodes()
            .let { require(it.isEmpty()) { "the previously-selected card must be gone; found ${it.size}" } }
    }
}
