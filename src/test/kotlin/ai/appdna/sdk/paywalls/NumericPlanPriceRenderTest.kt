package ai.appdna.sdk.paywalls

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #959 — A NUMERIC PLAN PRICE REACHES THE SCREEN, not just the DTO.
 *
 * iOS coerces five plan scalars (`PaywallPlan.looseString`) because its all-or-nothing `Codable`
 * used to nil a whole paywall over one numeric price. Android read `price` with `as? String`, got
 * null, and drew an EMPTY price while iOS drew the number — for as long as the oldest shared
 * fixture carrying such a document.
 *
 * 🔴 WHY THIS EXISTS WHEN THE SHARED FIXTURE ALREADY ASSERTS THE PARSE. The fixture proves
 * `displayPrice` is `"49.99"`. It does not prove the price REACHES A PIXEL, and the repo has paid
 * for that gap twice: #923's store-price substitution passed its fixture and its live-StoreKit test
 * while reaching nothing, because real paywalls carry plans in `layout.sections[].config.plans`
 * rather than at the root, and only a render test found it. So this renders the REAL
 * `PaywallSectionView` over a plans section and reads the text back.
 *
 * 🔴 AND THE PRICE IS AUTHORED THE WAY NO CONSOLE CAN AUTHOR IT. The console's `PlanSchema` has no
 * bare `price` field at all — it publishes `price_display` — so this document can only arrive from
 * an import, a hand-edited row or a generator. That is exactly why nothing caught it: there is no
 * console path that produces it, and therefore no device walk that would have shown it either.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class NumericPlanPriceRenderTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * A plans section whose single plan carries ONLY a numeric `price` — no `price_display`, so
     * `displayPrice` has to fall through to it, which is the path that was broken.
     *
     * The plans live under `layout.sections[].config.plans`, where a real paywall keeps them (#923),
     * not at the document root — a fixture that put them at the root would pass against code that
     * reaches no real paywall.
     */
    private fun paywallMap(price: Any): Map<String, Any> = mapOf(
        "id" to "pw_numeric_price",
        "name" to "Numeric price",
        "layout" to mapOf(
            "type" to "stack",
            "sections" to listOf(
                mapOf(
                    "id" to "sec_plans",
                    "type" to "plans",
                    "config" to mapOf(
                        "plan_display_style" to "vertical_stack",
                        "plans" to listOf(
                            mapOf(
                                "id" to "plan_annual",
                                "product_id" to "app.premium.annual",
                                "label" to "Annual",
                                "price" to price,
                                "is_default" to true,
                                "sort_order" to 0,
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun renderPrice(price: Any) {
        val config = PaywallConfigParser.parseSinglePaywall("pw_numeric_price", paywallMap(price))
            ?: error("paywall did not parse")
        val section = config.sections.first { it.type == "plans" }
        val plans = section.data?.plans.orEmpty()
        assertEquals("the plan must be where a real paywall keeps it", 1, plans.size)

        compose.setContent {
            Column {
                PaywallSectionView(
                    section = section,
                    config = config,
                    selectedPlanId = plans.firstOrNull()?.id,
                    isPurchasing = false,
                    onPlanSelect = {},
                    onCTATap = {},
                    onRestore = {},
                    loc = { _, fallback -> fallback },
                )
            }
        }
    }

    @Test
    fun `a fractional numeric price is drawn`() {
        renderPrice(9.99)
        compose.onNodeWithText("9.99", substring = true).assertIsDisplayed()
    }

    @Test
    fun `an INTEGRAL numeric price loses its decimal point, as iOS does`() {
        // iOS: `if d == d.rounded(), abs(d) < 1e15 { String(Int64(d)) }` — so `49.0` is "49", not
        // "49.0". Android's previous ad-hoc coercion on the two fields it DID coerce used a plain
        // `toString()` and produced "49.0", which is a different price on the screen.
        renderPrice(49.0)
        compose.onNodeWithText("49", substring = true).assertIsDisplayed()
        compose.onAllNodesWithTextSafe("49.0").let { count ->
            assertEquals("an integral price must not render a decimal point", 0, count)
        }
    }

    @Test
    fun `THE CONTROL — an authored STRING price renders, so this harness can see a price at all`() {
        // Without this, "the numeric price is drawn" could pass for the worst possible reason: a
        // harness that finds any text it is asked for, or one that renders nothing and asserts
        // nothing. #923's first render test read an EMPTY accessibility tree and its absence
        // assertion passed.
        renderPrice("$12.34")
        compose.onNodeWithText("$12.34", substring = true).assertIsDisplayed()
    }

    /** Counts matching nodes without throwing when there are none. */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextSafe(
        text: String,
    ): Int = onAllNodes(
        androidx.compose.ui.test.hasText(text, substring = false),
    ).fetchSemanticsNodes().size
}
