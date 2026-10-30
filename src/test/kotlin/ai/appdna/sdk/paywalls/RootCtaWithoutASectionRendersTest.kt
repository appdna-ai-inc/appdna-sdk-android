package ai.appdna.sdk.paywalls

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A PAYWALL WITH A TOP-LEVEL `cta` AND NO `cta` SECTION HAD NO BUY BUTTON ON ANDROID.
 *
 * iOS has carried a fallback for this since its renderer was written: `PaywallRenderer.swift`'s
 * `} else if let cta = config.cta {` draws a pinned button from the document-root `cta` when the
 * layout holds no `cta` section. Android looked for the SECTION and stopped, so such a paywall
 * rendered every other section and then simply ended — the plans were selectable and nothing on
 * the screen could buy them.
 *
 * 🔴 IT IS NOT A HYPOTHETICAL SHAPE. Measured against dev: 5 of 42 ACTIVE paywalls are authored
 * this way, and the one a device walk opened was one of them. It is the surface that takes the
 * money, which is the worst place for the divergence Rule 8 exists to stop.
 *
 * 🔴 WHY A RENDER TEST AND NOT A FIXTURE. A shared fixture asserts a DTO; this defect is entirely
 * in which sections the SCREEN decides to pin, and the DTO was always correct — `config.cta`
 * parsed perfectly and nothing read it. That is the same gap the numeric-price render test covers: the parse was never the
 * problem, reaching a pixel was.
 */
/*
 * 🔴 THE CTA IS MATCHED BY SUBSTRING, and the change in expectation is deliberate.
 *
 * On a paywall with more than one plan where no plan carries a `cta_text`, the CTA now appends the
 * selected plan's name — a button configured "Start Free Trial" draws "Start Free Trial — Monthly",
 * and "— Annual" once the annual row is tapped. This fixture is a TWO-plan paywall, so every CTA
 * lookup here sees the suffix.
 *
 * It exists because a plan tap that drifts far enough is claimed by the scrolling container, so the
 * selection does not change and the CTA buys the plan chosen before it (measured). Naming the
 * plan on the button is what makes that visible before the purchase rather than on the receipt
 * after. The exact-text assertions are what caught the change, which is them working.
 *
 * 🔴 Note for anyone locating a plan ROW by its name: the CTA now contains that name too, so a
 * substring query for "Annual" matches both. The row lookups below stay EXACT for that reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RootCtaWithoutASectionRendersTest {

    @get:Rule
    val compose = createComposeRule()

    private fun paywallMap(sections: List<Map<String, Any>>): Map<String, Any> = mapOf(
        "id" to "pw_root_cta",
        "name" to "Root cta",
        // The document-root cta the console writes when the layout carries no cta section.
        "cta" to mapOf(
            "text" to "Start Free Trial",
            "style" to mapOf("bg_color" to "#6366F1", "text_color" to "#FFFFFF", "corner_radius" to 12),
        ),
        "layout" to mapOf("type" to "stack", "sections" to sections),
    )

    private val plansSection: Map<String, Any> = mapOf(
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
    )

    private fun render(sections: List<Map<String, Any>>, onPlan: (PaywallPlan) -> Unit = {}) {
        val config = PaywallConfigParser.parseSinglePaywall("pw_root_cta", paywallMap(sections))
            ?: error("paywall did not parse")
        compose.setContent {
            PaywallScreen(
                config = config,
                onPlanSelected = { plan, _ -> onPlan(plan) },
                onRestore = {},
                onDismiss = {},
            )
        }
    }

    @Test
    fun `the root cta is drawn when the layout has no cta section`() {
        render(listOf(plansSection))
        compose.onNodeWithText("Start Free Trial", substring = true).assertIsDisplayed()
    }

    /**
     * 🔴 THE NEGATIVE CONTROL, and it is the one that matters: a fallback that fired when a cta
     * section already exists would draw the button TWICE, which is a new defect on the same
     * screen. Counting is the assertion, not "it is displayed".
     */
    @Test
    fun `an authored cta section is not doubled by the fallback`() {
        render(
            listOf(
                plansSection,
                mapOf("id" to "sec_cta", "type" to "cta", "config" to mapOf("cta_text" to "Start Free Trial")),
            ),
        )
        assertEquals(
            "exactly one CTA must be drawn",
            1,
            compose.onAllNodesWithText("Start Free Trial", substring = true).fetchSemanticsNodes().size,
        )
    }

    /**
     * A sticky footer owns the bottom of the screen and carries its own CTA, so synthesising one
     * beside it would put two buy buttons in the pinned area.
     */
    @Test
    fun `a sticky footer suppresses the fallback`() {
        render(
            listOf(
                plansSection,
                mapOf("id" to "sec_footer", "type" to "sticky_footer", "config" to mapOf("cta_text" to "Subscribe now")),
            ),
        )
        assertEquals(
            "the root cta must not be drawn beside a sticky footer",
            0,
            compose.onAllNodesWithText("Start Free Trial", substring = true).fetchSemanticsNodes().size,
        )
    }

    /*
     * And the button has to BUY THE SELECTED PLAN, not merely exist. This is the Android
     * half of the contrast the iOS walk proves on a device: tapping the annual row changes which
     * product the CTA hands to billing.
     *
     * 🔴 TWO RENDERS, NOT TWO TAPS IN ONE. The first CTA tap sets `isPurchasing`, which swaps the
     * label for a spinner — correct behaviour, and nothing resets it here because no billing
     * provider answers. A second tap in the same render therefore fails on "could not find any
     * node", which reads like a missing button rather than a button doing its job. So the variable
     * changes between RENDERS, which is the same experiment with the state reset.
     */

    private fun whatTheCtaBuys(tapAnnualFirst: Boolean): String? {
        var bought: PaywallPlan? = null
        render(listOf(plansSection)) { bought = it }
        if (tapAnnualFirst) compose.onNodeWithText("Annual").performClick()

        /*
         🔴 STOP DRIVING THE CLOCK BEFORE THE TAP. A CTA tap sets `isPurchasing`, which swaps the
         label for a `CircularProgressIndicator` — an INFINITE animation — and nothing resets it
         here because no billing provider answers. With the clock auto-advancing, the test rule
         never reaches idle: this run burned the whole 900-second bridge budget and took an
         unrelated Espresso test down with it (`AppNotIdleException` in
         `HostDataAuditRegressionTest`, a file this change does not touch), which reads as a
         regression somewhere else entirely.
         */
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Start Free Trial", substring = true).performClick()
        return bought?.product_id
    }

    @Test
    fun `an untouched paywall buys the default plan`() {
        assertEquals("app.premium.monthly", whatTheCtaBuys(tapAnnualFirst = false))
    }

    @Test
    fun `tapping the annual row changes what the fallback cta buys`() {
        assertEquals("app.premium.annual", whatTheCtaBuys(tapAnnualFirst = true))
    }
}
