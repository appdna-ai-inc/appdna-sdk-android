package ai.appdna.sdk.paywalls

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1097 — DOES THE AUTHORED RANK DECIDE THE ORDER THE PLANS DRAW IN? Rendered, and read back by
 * position.
 *
 * 🔴 WHY THIS EXISTS WHEN THE SHARED FIXTURE ALREADY ASSERTS THE SORT. `resolve_plan_order` calls
 * [PaywallPlanOrder.sorted] DIRECTLY, on both platforms. That proves the sorter sorts and cannot
 * see whether any renderer calls it — so deleting the call at every site leaves it green, which is
 * exactly how #1097 happened on iOS: the sort was wired into two sites that look a plan up BY ID,
 * where order is irrelevant by construction, and not into the one site that draws. Same blind spot
 * as #1094 and #1095, where a runner that re-implemented the advance payload could not notice the
 * production assembly breaking.
 *
 * Android's render site (`PaywallActivity.kt`, `"plans" ->`) does sort, so this is the regression
 * guard for the platform that was already right — and the assertion is the same on both, which is
 * what Rule 8 asks for.
 *
 * The plans live under `layout.sections[].config.plans`, where a real paywall keeps them (#923).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PlanOrderRenderTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * A plans section holding the given plans in the given array order. `rank` null omits
     * `sort_order` entirely — the common shape, and the one `sorted` must leave alone.
     */
    private fun paywallMap(plans: List<Triple<String, String, Int?>>): Map<String, Any> = mapOf(
        "id" to "pw_plan_order",
        "name" to "Plan order",
        "layout" to mapOf(
            "type" to "stack",
            "sections" to listOf(
                mapOf(
                    "id" to "sec_plans",
                    "type" to "plans",
                    "config" to mapOf(
                        "plan_display_style" to "vertical_stack",
                        "plans" to plans.map { (id, label, rank) ->
                            buildMap {
                                put("id", id)
                                put("product_id", id)
                                put("label", label)
                                put("price_display", "$1.00")
                                if (rank != null) put("sort_order", rank)
                            }
                        },
                    ),
                ),
            ),
        ),
    )

    private fun render(plans: List<Triple<String, String, Int?>>) {
        val config = PaywallConfigParser.parseSinglePaywall("pw_plan_order", paywallMap(plans))
            ?: error("paywall did not parse")
        val section = config.sections.first { it.type == "plans" }
        assertEquals(
            "the plans must be where a real paywall keeps them",
            plans.size,
            section.data?.plans.orEmpty().size,
        )
        compose.setContent {
            Column {
                PaywallSectionView(
                    section = section,
                    config = config,
                    selectedPlanId = null,
                    isPurchasing = false,
                    onPlanSelect = {},
                    onCTATap = {},
                    onRestore = {},
                    loc = { _, fallback -> fallback },
                )
            }
        }
    }

    /** The y of the first node drawing [text], in root coordinates. */
    private fun ComposeContentTestRule.topOf(text: String): Float {
        val nodes = onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes()
        assertTrue("this harness must be able to SEE the row labelled \"$text\"", nodes.isNotEmpty())
        return nodes.first().positionInRoot.y
    }

    // MARK: - The claim

    @Test
    fun `an authored rank that contradicts the array order decides what is drawn`() {
        // Authored alpha-then-bravo, ranked bravo-then-alpha.
        render(
            listOf(
                Triple("plan_alpha", "Alpha", 1),
                Triple("plan_bravo", "Bravo", 0),
            ),
        )
        assertTrue(
            "Bravo carries sort_order 0 and Alpha 1, so Bravo must draw ABOVE Alpha — if it does " +
                "not, the rank was dropped and the plans drew in serialisation order (#1097)",
            compose.topOf("Bravo") < compose.topOf("Alpha"),
        )
    }

    @Test
    fun `THE CONTROL — with no rank anywhere, the array order is the drawing order`() {
        // Without this, the assertion above could pass for the worst possible reason: a renderer
        // that happens to draw these two labels in that order whatever it is handed. It also
        // proves the harness reads POSITION and not merely presence.
        render(
            listOf(
                Triple("plan_alpha", "Alpha", null),
                Triple("plan_bravo", "Bravo", null),
            ),
        )
        assertTrue(
            "with no sort_order anywhere, the array's own order IS the drawing order — Alpha was " +
                "authored first, so it must draw above Bravo",
            compose.topOf("Alpha") < compose.topOf("Bravo"),
        )
    }
}
