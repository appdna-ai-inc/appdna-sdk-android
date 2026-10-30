package ai.appdna.sdk.paywalls

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `carousel_cards` IS PAGED, not merely drawn.
 *
 * The Console offers `horizontal_scroll` and `carousel_cards` as two separate choices and Android
 * rendered them with ONE arm — a peeking pager — so an author who picked the second silently got
 * the first. `carousel_cards` now has its own arm: full-width pages, no peek, and a page-dot
 * indicator beneath, which is what iOS's `TabView` gives.
 *
 * 🔴 A SNAPSHOT CANNOT ASSERT THIS. `paywall_light_page_plans_carousel_cards.png` shows one
 * full-width card and two dots, which proves the layout and says nothing about whether the second
 * page can be reached — and "the second plan exists but cannot be swiped to" is the version of this
 * defect that would still lose the sale. Rule 24: a carousel is operated by swiping through every
 * page, so this swipes.
 *
 * The control is in the assertions themselves: the second plan must be ABSENT before the swipe.
 * Without that, a layout that stacked both plans on one page would pass "Annual is displayed".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CarouselCardsPagingTest {

    @get:Rule
    val compose = createComposeRule()

    private companion object {
        const val FIRST = "Monthly"
        const val SECOND = "Annual"
    }

    /** Plans under `layout.sections[].config.plans`, where a real paywall keeps them. */
    private fun paywallMap(style: String): Map<String, Any> = mapOf(
        "id" to "pw_carousel",
        "name" to "Carousel",
        "layout" to mapOf(
            "type" to "stack",
            "background" to mapOf("type" to "color", "value" to "#FFFFFF"),
            "sections" to listOf(
                mapOf(
                    "id" to "sec_plans",
                    "type" to "plans",
                    "config" to mapOf(
                        "plan_display_style" to style,
                        "plans" to listOf(
                            mapOf(
                                "id" to "p_month", "product_id" to "app.premium.monthly",
                                "label" to FIRST, "price_display" to "$9.99",
                                "is_default" to true, "sort_order" to 0,
                            ),
                            mapOf(
                                "id" to "p_year", "product_id" to "app.premium.annual",
                                "label" to SECOND, "price_display" to "$49.99",
                                "is_default" to false, "sort_order" to 1,
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun render(style: String) {
        val config = PaywallConfigParser.parseSinglePaywall("pw_carousel", paywallMap(style))
            ?: error("paywall did not parse")
        val section = config.sections.first { it.type == "plans" }
        compose.setContent {
            Column {
                PaywallSectionView(
                    section = section,
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
    }

    @Test
    fun `carousel_cards shows one plan per page and swipes to the next`() {
        render("carousel_cards")

        compose.onNodeWithText(FIRST, substring = true).assertIsDisplayed()
        // The control: one page at a time is the whole difference from the peeking pager.
        compose.onNodeWithText(SECOND, substring = true).assertIsNotDisplayed()

        compose.onNodeWithText(FIRST, substring = true).performTouchInput { swipeLeft() }
        compose.waitForIdle()

        compose.onNodeWithText(SECOND, substring = true).assertIsDisplayed()
    }

    /**
     * The sibling the Console offers beside it. Both plans are in the tree here because the pager
     * peeks — which is the behaviour an author picks `horizontal_scroll` FOR, and the behaviour
     * `carousel_cards` used to be given by mistake.
     */
    @Test
    fun `horizontal_scroll peeks the next plan instead of paging it away`() {
        render("horizontal_scroll")

        compose.onNodeWithText(FIRST, substring = true).assertIsDisplayed()
        compose.onNodeWithText(SECOND, substring = true).assertIsDisplayed()
    }
}
