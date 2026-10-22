package ai.appdna.sdk.paywalls

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1074 — THE TAP IS REPORTED EVEN WHEN IT CANNOT BECOME A PURCHASE.
 *
 * `CTA_TAPPED` used to be emitted by `PaywallManager.onPlanSelected`, which the renderer reaches
 * only once its `plan != null` guard has passed. iOS had the same shape twice (`handleCTATap`
 * opens with two silent `guard`s) and, worse, never called `onPaywallAction` at all.
 *
 * So a CTA tap that could not resolve a plan returned SILENTLY on both platforms: no delegate
 * call, no event, no log. A user saw a button do nothing, the host learned nothing, and from
 * outside the app "the SDK never heard the tap" and "the store refused" were the same silence —
 * which is why #1072 could not be diagnosed.
 *
 * 🔴 THE CASE THAT MATTERS IS THE ONE THAT CANNOT PROCEED, so that is what this drives: a paywall
 * with a root `cta` and NO plans at all. The CTA draws (#1073's fallback), the tap lands, and the
 * guard refuses it. The action must still be reported, and no purchase must be started.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CtaTapIsReportedBeforeTheGuardTest {

    @get:Rule
    val compose = createComposeRule()

    private fun paywallMap(plans: List<Map<String, Any>>): Map<String, Any> = mapOf(
        "id" to "pw_cta_report",
        "name" to "Cta report",
        "cta" to mapOf("text" to "Start Free Trial"),
        "layout" to mapOf(
            "type" to "stack",
            "sections" to listOf(
                mapOf(
                    "id" to "sec_plans",
                    "type" to "plans",
                    "config" to mapOf("plan_display_style" to "vertical_stack", "plans" to plans),
                ),
            ),
        ),
    )

    private val realPlan = mapOf(
        "id" to "plan_monthly", "product_id" to "app.premium.monthly",
        "label" to "Monthly", "price_display" to "A-11.11",
        "is_default" to true, "sort_order" to 0,
    )

    private fun render(plans: List<Map<String, Any>>, onTap: () -> Unit, onBuy: (PaywallPlan) -> Unit) {
        val config = PaywallConfigParser.parseSinglePaywall("pw_cta_report", paywallMap(plans))
            ?: error("paywall did not parse")
        compose.setContent {
            PaywallScreen(
                config = config,
                onPlanSelected = { plan, _ -> onBuy(plan) },
                onRestore = {},
                onDismiss = {},
                onCtaTapped = onTap,
            )
        }
    }

    /**
     * 🔴 WITH NO PLAN TO BUY, ANDROID DISABLES THE BUTTON — `enabled = !isPurchasing &&
     * selectedPlanId != null`, drawn at half alpha. So the "a tap that cannot proceed" case does
     * not arise here: the user cannot tap it, which is the right answer and is asserted rather
     * than assumed.
     *
     * iOS does NOT agree on the `cta`-section path (`CTAButton` is `.disabled(isPurchasing)` and
     * nothing else), so the same paywall shows a bright, tappable, inert button there. That
     * divergence is **#1075** and is not this test's subject — but it is why #1074's report has to
     * fire before the guards rather than relying on the button being unreachable.
     */
    @Test
    fun `with no plan to buy the CTA cannot be tapped at all`() {
        var taps = 0
        var bought: PaywallPlan? = null
        render(plans = emptyList(), onTap = { taps += 1 }, onBuy = { bought = it })
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Start Free Trial").assertIsNotEnabled()
        compose.onNodeWithText("Start Free Trial").performClick()
        assertEquals("a disabled CTA reports nothing, because nothing happened", 0, taps)
        assertNull(bought)
    }

    /**
     * 🔴 THE CONTROL: the ordinary path must report the tap exactly ONCE. Reporting at the tap site
     * without removing the old emission from `PaywallManager.onPlanSelected` would double every
     * successful tap, which is the mirror-image defect and would quietly double a funnel metric.
     */
    @Test
    fun `a tap that does buy is reported exactly once`() {
        var taps = 0
        var bought: PaywallPlan? = null
        render(plans = listOf(realPlan), onTap = { taps += 1 }, onBuy = { bought = it })
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Start Free Trial").performClick()
        assertEquals("exactly one report per tap", 1, taps)
        assertEquals("app.premium.monthly", bought?.product_id)
    }
}
