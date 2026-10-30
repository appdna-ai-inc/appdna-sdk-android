package ai.appdna.sdk.paywalls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ARE TWO PLANS IN A CONSOLE-PUBLISHED PAYWALL DISTINGUISHABLE? A regression guard for
 * `withFallbackId`, written against the shape production actually sends.
 *
 * 🔴 WHY THIS IS NOT COVERED ELSEWHERE. The console's `PlanSchema` has no `id` field at all — a
 * published plan carries `product_id`, `label`, `price_display`, `is_default` and `sort_order` —
 * and nothing in `PaywallService` adds one on save (`plans: input.plans` passes the array through
 * verbatim). Measured against the dev database: **0 of 129 plans carry an `id`, 123 carry a
 * `product_id`, and 30 ACTIVE paywalls have more than one plan.** Yet every other Android test
 * authors an explicit `"id" to "plan_monthly"`, a shape no console can produce. So the one shape
 * that matters in production had no test, which is the same blind spot `NumericPlanPriceRenderTest`
 * records from the other direction.
 *
 * What makes it work is `PaywallConfig.withFallbackId(index)` — `if (id.isBlank()) copy(id =
 * "plan_$index")` — applied at PARSE time at both plan sites, on the raw published array, so each
 * plan carries a stable synthetic handle before anything reorders it. The purchase target is
 * `product_id`, so a synthetic selection id is safe; iOS reaches the same place differently, with
 * `_id ?? productId`.
 *
 * That is worth pinning precisely because it is invisible: nothing in the renderer hints that
 * `plan.id`, compared at around twenty sites, is usually a value no author ever wrote. Delete the
 * fallback and every plan shares `""` — every row reports itself selected, a tap stores a value
 * that changes nothing, and the CTA buys whichever plan is FIRST whatever the user chose.
 *
 * The plans live under `layout.sections[].config.plans`, where a real paywall keeps them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PlanIdentityFromConsoleShapeTest {

    /** Exactly the fields the console's `PlanSchema` publishes — note there is no `id`. */
    private fun consolePublishedPaywall(): Map<String, Any> = mapOf(
        "id" to "pw_console_shape",
        "name" to "Console shape",
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
                                "product_id" to "app.premium.monthly",
                                "label" to "Monthly",
                                "price_display" to "$9.99",
                                "is_default" to true,
                                "sort_order" to 0,
                            ),
                            mapOf(
                                "product_id" to "app.premium.annual",
                                "label" to "Annual",
                                "price_display" to "$79.99",
                                "is_default" to false,
                                "sort_order" to 1,
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `two console-published plans must be distinguishable by the id the renderer compares`() {
        val config = PaywallConfigParser.parseSinglePaywall("pw_console_shape", consolePublishedPaywall())
            ?: error("paywall did not parse")
        val plans = config.sections.first { it.type == "plans" }.data?.plans.orEmpty()
        assertEquals("both plans must parse", 2, plans.size)

        // What the probe is actually measuring, named in the failure so the evidence is the output.
        val ids = plans.map { it.id }
        val products = plans.map { it.product_id }

        assertTrue(
            "every plan must have a non-empty id, because `selectedPlanId == plan.id` is how every " +
                "row decides whether it is selected and how the CTA resolves what to buy. " +
                "Parsed ids=$ids from product_ids=$products",
            ids.none { it.isEmpty() },
        )
        assertNotEquals(
            "the two plans must not share an id — if they do, every row reports itself selected, a " +
                "tap changes nothing, and the CTA buys whichever plan is FIRST whatever the user " +
                "chose. Parsed ids=$ids from product_ids=$products",
            ids[0],
            ids[1],
        )
    }
}
