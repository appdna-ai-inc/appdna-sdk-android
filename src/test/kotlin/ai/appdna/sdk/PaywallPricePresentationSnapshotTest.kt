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
 * SPEC-438 (#544, #548) — Android pixels for product-level price presentation.
 *
 * The counterpart to iOS `VisualSnapshotTests.testPlanCard_*`, with the SAME plan JSON so the
 * two goldens are directly comparable. Robolectric renders the REAL Compose plan card on the
 * JVM, and `captureRoboImage` writes the actual pixels — which is the only thing that can show
 * that the pill hugs its text and that the headline layout stacks the way iOS does.
 *
 * The strings are the production ones from the reported paywall. `25,00 zł miesięcznie` is 20
 * characters where `$4.99` is 5, and that length is the whole reason #545 existed — a short
 * synthetic price would make this pass against broken layout code.
 *
 * Record:  ./gradlew recordRoborazziDebug
 * Compare: ./gradlew verifyRoborazziDebug   (the CI gate)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class PaywallPricePresentationSnapshotTest {

    private fun paywallMap(priceLayout: String?): Map<String, Any> = mapOf(
        "id" to "pw_price_presentation",
        "name" to "Price presentation",
        "sections" to listOf(
            mapOf(
                "id" to "sec_plans",
                "type" to "plans",
                "data" to buildMap {
                    put("plan_display_style", "vertical_stack")
                    put("show_plan_subtitles", true)
                    put("strikethrough_color", "#9CA3AF")
                    put("strikethrough_font_size", 13)
                    put("strikethrough_gap", 8)
                    if (priceLayout != null) put("price_layout", priceLayout)
                },
            ),
        ),
        "plans" to listOf(
            mapOf(
                "product_id" to "app.premium.annual",
                "label" to "Rocznie",
                "price_display" to "25,00 zł miesięcznie",
                "original_price_display" to "359,88 zł",
                "price_total_display" to "299,99 zł rocznie",
                "description" to "7-dniowy okres próbny",
                "description_badge" to mapOf(
                    "enabled" to true,
                    "bg_color" to "#15803D",
                    "text_color" to "#DCFCE7",
                    "corner_radius" to 6,
                ),
                "is_default" to true,
                "sort_order" to 0,
            ),
        ),
    )

    private fun capture(name: String, priceLayout: String?) {
        val config = PaywallConfigParser.parseSinglePaywall("pw_price_presentation", paywallMap(priceLayout))
            ?: error("paywall did not parse")
        val section = config.sections.first { it.type == "plans" }
        captureRoboImage("src/test/snapshots/$name.png") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F1117))
                    .padding(16.dp),
            ) {
                PaywallSectionView(
                    section = section,
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

    @Test
    fun headlineStackedWithSubtitlePill() = capture("paywall_headline_stacked_pill", "headline_stacked")

    /** The default. Must match what shipped before SPEC-438 — struck price inline, no charged total. */
    @Test
    fun inlineDefaultUnchanged() = capture("paywall_inline_default", null)
}
