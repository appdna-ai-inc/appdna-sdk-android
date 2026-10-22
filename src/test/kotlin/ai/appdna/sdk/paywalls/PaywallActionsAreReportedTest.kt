package ai.appdna.sdk.paywalls

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1076 — ALL FOUR of the remaining `PaywallAction` cases, reported from the real screen.
 *
 * `cta_tapped` was given an emitter in #1074. The other four were declared, documented on four
 * doc pages and delivered by nothing on either platform — a host implementing `onPaywallAction`
 * waited for callbacks that could not arrive. The owner asked for all four.
 *
 * 🔴 THE CONTROL IS THAT THE DEFAULT SELECTION REPORTS NOTHING. `plan_changed` means "the user
 * chose a different plan". A paywall seeds its default on appear, and reporting that as a change
 * the user made would put one spurious event at the top of every paywall session — a funnel metric
 * that looks like engagement and is furniture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PaywallActionsAreReportedTest {

    @get:Rule
    val compose = createComposeRule()

    private fun paywallMap(): Map<String, Any> = mapOf(
        "id" to "pw_actions",
        "name" to "Actions",
        "cta" to mapOf("text" to "Start Free Trial"),
        "layout" to mapOf(
            "type" to "stack",
            "sections" to listOf(
                mapOf(
                    "id" to "sec_features",
                    "type" to "features",
                    "config" to mapOf("features" to listOf("Unlimited everything", "No adverts")),
                ),
                mapOf(
                    "id" to "sec_plans",
                    "type" to "plans",
                    "config" to mapOf(
                        "plan_display_style" to "vertical_stack",
                        "plans" to listOf(
                            mapOf(
                                "id" to "plan_monthly", "product_id" to "app.premium.monthly",
                                "label" to "Monthly", "price_display" to "A-11.11",
                                "is_default" to true, "sort_order" to 0,
                            ),
                            mapOf(
                                "id" to "plan_annual", "product_id" to "app.premium.annual",
                                "label" to "Annual", "price_display" to "A-99.99",
                                "is_default" to false, "sort_order" to 1,
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    /** Every action the screen reported, in order. */
    private fun render(): MutableList<PaywallAction> {
        val seen = mutableListOf<PaywallAction>()
        val config = PaywallConfigParser.parseSinglePaywall("pw_actions", paywallMap())
            ?: error("paywall did not parse")
        compose.setContent {
            PaywallScreen(
                config = config,
                onPlanSelected = { _, _ -> },
                onRestore = {},
                onDismiss = {},
                onAction = { seen += it },
            )
        }
        return seen
    }

    @Test
    fun `the default plan selected on appear reports nothing`() {
        val seen = render()
        compose.waitForIdle()
        assertEquals("a paywall that has only been LOOKED at reports no user action", emptyList<PaywallAction>(), seen)
    }

    @Test
    fun `tapping a different plan reports plan_changed`() {
        val seen = render()
        compose.onNodeWithText("Annual").performClick()
        assertEquals(listOf(PaywallAction.PLAN_CHANGED), seen)
    }

    @Test
    fun `tapping a feature row reports feature_selected`() {
        val seen = render()
        compose.onNodeWithText("No adverts").performClick()
        assertEquals(listOf(PaywallAction.FEATURE_SELECTED), seen)
    }

    /**
     * 🔴 AND IT DOES NOT LEAK. A feature row sits above the plans; a tap handler that swallowed or
     * forwarded would show up here as a second action. One tap, one report.
     */
    @Test
    fun `a feature tap does not also fire a plan or cta action`() {
        val seen = render()
        compose.onNodeWithText("Unlimited everything").performClick()
        assertEquals(1, seen.size)
        assertEquals(PaywallAction.FEATURE_SELECTED, seen.single())
    }
}
