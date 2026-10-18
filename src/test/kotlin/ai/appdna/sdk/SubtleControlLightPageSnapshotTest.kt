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
