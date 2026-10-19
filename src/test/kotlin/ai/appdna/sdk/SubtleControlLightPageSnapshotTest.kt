package ai.appdna.sdk

import ai.appdna.sdk.paywalls.PaywallConfigParser
import ai.appdna.sdk.paywalls.PaywallSectionView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #938 — the three sections whose defaults were hard-coded white, rendered ON A WHITE PAGE.
 *
 * #938 refused to change those defaults without SEEING one fail, and this is the seeing. Each
 * capture is the real Compose section through `PaywallSectionView`, on a `#FFFFFF` page, with no
 * colours authored — the exact document an author produces by choosing a light background and
 * touching nothing else.
 *
 * 🔴 WHY A SNAPSHOT AND NOT AN IN-TEST PIXEL ASSERTION. Two in-test attempts were written first and
 * both certified the defect: a white-vs-dark ink ratio passed at 0.989 because cards and chrome
 * drowned the controls, and a hand-drawn `decorView.draw()` returned 87,360 identical pixels for
 * all three sections — the system bars, with the Compose content contributing nothing measurable.
 * `captureRoboImage` is the capture path this module already uses for paywall pixels, it writes a
 * file a human can open, and the count is taken from that file. A measurement that cannot see the
 * defect is worse than none, because it certifies it.
 *
 * Record:  ./gradlew recordRoborazziDebug --tests "ai.appdna.sdk.SubtleControlLightPageSnapshotTest"
 * Compare: ./gradlew verifyRoborazziDebug   (the CI gate)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class SubtleControlLightPageSnapshotTest {

    private val whitePage = "#FFFFFF"

    private fun paywall(section: Map<String, Any>, plans: List<Map<String, Any>>) = mapOf(
        "id" to "pw_subtle",
        "name" to "Subtle controls",
        "layout" to mapOf(
            "type" to "stack",
            "background" to mapOf("type" to "color", "value" to whitePage),
            "sections" to listOf(section),
        ),
        "plans" to plans,
    )

    private fun capture(name: String, section: Map<String, Any>, plans: List<Map<String, Any>> = emptyList()) {
        val config = PaywallConfigParser.parseSinglePaywall("pw_subtle", paywall(section, plans))
            ?: error("the fixture paywall did not parse")
        captureRoboImage("src/test/snapshots/$name.png") {
            Column(
                modifier = Modifier.fillMaxWidth().background(Color.White).padding(16.dp),
            ) {
                PaywallSectionView(
                    section = config.sections.first(),
                    config = config,
                    selectedPlanId = config.plans?.firstOrNull()?.id,
                    isPurchasing = false,
                    onPlanSelect = {},
                    onCTATap = {},
                    onRestore = {},
                    loc = { _, fallback -> fallback },
                )
            }
        }
    }

    /** A `pill_selector`: the unselected pill's fill was white@10% and its border white@30%. */
    @Test
    fun pillSelectorOnAWhitePage() = capture(
        "paywall_light_page_pill_selector",
        mapOf("id" to "sec_plans", "type" to "plans", "data" to mapOf("plan_display_style" to "pill_selector")),
        listOf(
            mapOf("id" to "p_month", "label" to "Monthly", "price_display" to "AAA", "is_default" to true),
            mapOf("id" to "p_year", "label" to "Annual", "price_display" to "BBB"),
        ),
    )

    /** A carousel: its page title was opaque white, its subtitle white@80%, its dots white@30%. */
    @Test
    fun carouselOnAWhitePage() = capture(
        "paywall_light_page_carousel",
        mapOf(
            "id" to "sec_carousel", "type" to "carousel",
            "data" to mapOf(
                "show_indicators" to true, "auto_scroll" to false,
                "pages" to listOf(
                    mapOf("id" to "pg1", "children" to listOf(mapOf(
                        "type" to "card",
                        "data" to mapOf("title" to "First page", "subtitle" to "Its subtitle"),
                    ))),
                    mapOf("id" to "pg2", "children" to listOf(mapOf(
                        "type" to "card",
                        "data" to mapOf("title" to "Second page", "subtitle" to "Another subtitle"),
                    ))),
                ),
            ),
        ),
    )

    // ── #946: the sections that were white THROUGHOUT ────────────────────────────────────────
    //
    // Six more composables resolved their colours by assuming a dark page. Each capture below is
    // the shape an author gets by choosing a light background and touching nothing else, and each
    // was recorded, counted and OPENED before the change was called done.

    /** A card: title opaque white, subtitle white@80%, body white@90%, fill white@8%, border white@15%. */
    @Test
    fun cardOnAWhitePage() = capture(
        "paywall_light_page_card",
        mapOf(
            "id" to "sec_card", "type" to "card",
            "data" to mapOf(
                "title" to "Card title", "subtitle" to "Its subtitle",
                "text" to "A short body paragraph that an author wrote.",
            ),
        ),
    )

    /** An icon grid: every label opaque white and every caption white@60%. */
    @Test
    fun iconGridOnAWhitePage() = capture(
        "paywall_light_page_icon_grid",
        mapOf(
            "id" to "sec_icons", "type" to "icon_grid",
            "data" to mapOf(
                "items" to listOf(
                    mapOf("icon" to "star", "title" to "First", "subtitle" to "One"),
                    mapOf("icon" to "heart", "title" to "Second", "subtitle" to "Two"),
                    mapOf("icon" to "bolt", "title" to "Third", "subtitle" to "Three"),
                ),
            ),
        ),
    )

    /** A timeline: every step title opaque white, every subtitle white@70%, the rail white. */
    @Test
    fun timelineOnAWhitePage() = capture(
        "paywall_light_page_timeline",
        mapOf(
            "id" to "sec_timeline", "type" to "timeline",
            "data" to mapOf(
                "items" to listOf(
                    mapOf("title" to "Today", "subtitle" to "Full access"),
                    mapOf("title" to "Day 5", "subtitle" to "A reminder"),
                    mapOf("title" to "Day 7", "subtitle" to "Billing starts"),
                ),
            ),
        ),
    )

    /** A toggle: its label was opaque white. */
    @Test
    fun toggleOnAWhitePage() = capture(
        "paywall_light_page_toggle",
        mapOf(
            "id" to "sec_toggle", "type" to "toggle",
            "data" to mapOf("label" to "Remind me before the trial ends", "default_on" to true),
        ),
    )

    /** Legal copy: white@50%, which over white is nothing at all. */
    @Test
    fun legalOnAWhitePage() = capture(
        "paywall_light_page_legal",
        mapOf(
            "id" to "sec_legal", "type" to "legal",
            "data" to mapOf(
                "text" to "Payment is charged on confirmation. Cancel any time.",
                "show_restore" to true, "restore_text" to "Restore",
            ),
        ),
    )

    /** A countdown: its digits were opaque white and their tiles white@10%. */
    @Test
    fun countdownOnAWhitePage() = capture(
        "paywall_light_page_countdown",
        mapOf(
            "id" to "sec_countdown", "type" to "countdown",
            "data" to mapOf("countdown_seconds" to 3725, "title" to "Offer ends in"),
        ),
    )

    /**
     * Every plan display style, on a white page.
     *
     * `PaywallSectionView`'s dispatch holds the bulk of the remaining hard-coded whites and each
     * arm draws on something different — the page, its own tile, a brand-filled button — so which
     * of them are broken is a question to MEASURE rather than to judge by reading. One capture per
     * style answers it.
     */
    private fun planStyle(style: String) = capture(
        "paywall_light_page_plans_$style",
        mapOf(
            "id" to "sec_plans", "type" to "plans",
            "data" to mapOf("plan_display_style" to style, "show_plan_subtitles" to true),
        ),
        listOf(
            mapOf("id" to "p_month", "label" to "Monthly", "price_display" to "$9.99",
                  "period" to "month", "description" to "Billed monthly", "is_default" to true),
            mapOf("id" to "p_year", "label" to "Annual", "price_display" to "$49.99",
                  "period" to "year", "description" to "Billed yearly", "badge" to "Save 58%"),
        ),
    )

    @Test fun plansVerticalStackOnAWhitePage() = planStyle("vertical_stack")
    @Test fun plansRadioListOnAWhitePage() = planStyle("radio_list")
    @Test fun plansSegmentedToggleOnAWhitePage() = planStyle("segmented_toggle")
    @Test fun plansHorizontalScrollOnAWhitePage() = planStyle("horizontal_scroll")
    @Test fun plansMiniCardsOnAWhitePage() = planStyle("mini_cards")
    @Test fun plansComparisonCardsOnAWhitePage() = planStyle("comparison_cards")
    @Test fun plansTierLadderOnAWhitePage() = planStyle("tier_ladder")
    @Test fun plansTimelineOnAWhitePage() = planStyle("timeline")
    @Test fun plansPricingTableOnAWhitePage() = planStyle("pricing_table")
    @Test fun plansFeatureMatrixOnAWhitePage() = planStyle("feature_matrix")

    /*
     * #966 — the seven arms that had NO light-page capture at all, which is why the selected-card
     * defect sat unseen in `paywall_light_page_plans_vertical_stack.png` (the selected plan there
     * renders no name and no price) and why `accordion` was never looked at.
     *
     * 🔴 WHICH OF THESE ARE BROKEN IS A QUESTION TO MEASURE. `accordion` passes `planNameStyle`
     * with no colour override and that style defaults to `color = Color.White`, so reading the code
     * predicts invisible names — but `single_hero`, `product_as_cta` and `timeline_reveal` were
     * changed for #961 without a light-page capture either, and a prediction is not a measurement.
     */
    @Test fun plansAccordionOnAWhitePage() = planStyle("accordion")
    @Test fun plansGridOnAWhitePage() = planStyle("grid")
    @Test fun plansTabGroupsOnAWhitePage() = planStyle("tab_groups")
    @Test fun plansSingleHeroOnAWhitePage() = planStyle("single_hero")
    @Test fun plansProductAsCtaOnAWhitePage() = planStyle("product_as_cta")
    @Test fun plansTimelineRevealOnAWhitePage() = planStyle("timeline_reveal")
    @Test fun plansCarouselCardsOnAWhitePage() = planStyle("carousel_cards")

    /*
     * #988 — THE BADGE'S CORNER, which no other capture pins.
     *
     * `badge_shape` defaults to `capsule`, so every capture above draws a full pill and none of
     * them would move if the `rounded` radius changed. iOS has drawn 6pt since it shipped and
     * Android drew 4 — two pixels on the one element a paywall uses to say "Most popular", and a
     * difference nobody authored.
     *
     * Three shapes, as a discriminating set: `rounded` is what changed, `rectangle` is the 2dp
     * neighbour it must stay distinct from, and `square` is the Android-only alias that has to keep
     * drawing the rectangle (iOS accepts it now too, so the word means the same thing on both).
     */
    private fun badgeShape(shape: String) = capture(
        "paywall_light_page_badge_$shape",
        mapOf(
            "id" to "sec_plans", "type" to "plans",
            "data" to mapOf("plan_display_style" to "vertical_stack", "badge_shape" to shape),
        ),
        listOf(
            mapOf("id" to "p_year", "label" to "Annual", "price_display" to "$49.99",
                  "period" to "year", "badge" to "Save 58%", "is_default" to true),
        ),
    )

    @Test fun badgeRoundedOnAWhitePage() = badgeShape("rounded")
    @Test fun badgeRectangleOnAWhitePage() = badgeShape("rectangle")
    @Test fun badgeSquareAliasOnAWhitePage() = badgeShape("square")
    @Test fun plansInteractiveSliderOnAWhitePage() = planStyle("interactive_slider")

    /** A reviews carousel: quote white@90%, author white@70%, date white@40%, dots white@30%. */
    @Test
    fun reviewsCarouselOnAWhitePage() = capture(
        "paywall_light_page_reviews_carousel",
        mapOf(
            "id" to "sec_reviews", "type" to "reviews_carousel",
            "data" to mapOf(
                "auto_scroll" to false, "show_rating_stars" to false,
                "reviews" to listOf(
                    mapOf("id" to "r1", "text" to "A short quote about the app", "author" to "A. Reader", "date" to "2026"),
                    mapOf("id" to "r2", "text" to "Another short quote", "author" to "B. Reader", "date" to "2026"),
                ),
            ),
        ),
    )
}
