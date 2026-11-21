package ai.appdna.sdk

import ai.appdna.sdk.billing.StorePriceLookup
import ai.appdna.sdk.paywalls.LocalPaywallPageHex
import ai.appdna.sdk.paywalls.PaywallConfig
import ai.appdna.sdk.paywalls.PaywallConfigParser
import ai.appdna.sdk.paywalls.PaywallSectionView
import ai.appdna.sdk.paywalls.applyingSavings
import ai.appdna.sdk.paywalls.applyingStoreFacts
import ai.appdna.sdk.paywalls.pageBackgroundHex
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * DOES THE SAVINGS BADGE AGREE WITH THE PRICES DRAWN BESIDE IT? Rendered, and compared.
 *
 * The shared fixture `billing/plan_savings_percent` pins the arithmetic. This pins what a user SEES:
 * Play's answer → the presenter's own `applyingStoreFacts` → the real Compose plans section → pixels.
 * Play is not reachable under Robolectric, so its answer is the [StorePriceLookup.StoreFacts] the
 * lookup would return — the same type the presenter receives.
 *
 * The paywall AUTHORS $39.99 / $6.99 (52%); Play serves $79.99 / $14.99, whose saving is 55.5%.
 *
 *     A   badge "Save {{savings_percent}}", Play's answer APPLIED
 *     B   badge "Save 55%" typed literally, Play's answer applied
 *     C   badge "Save 52%" typed literally, Play's answer applied — the defect as it shipped
 *
 * A == B: the figure drawn is the one the drawn prices give. A != C: the harness can SEE a wrong
 * figure, so A == B is not two blank frames agreeing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class PlanSavingsRenderTest {

    private val play = mapOf(
        "premium_annual" to StorePriceLookup.StoreFacts(price = "$79.99", periodIso = "P1Y", amount = 79.99),
        "premium_monthly" to StorePriceLookup.StoreFacts(price = "$14.99", periodIso = "P1M", amount = 14.99),
    )

    private fun config(
        badge: String,
        localizations: Map<String, Any>? = null,
        annualPrice: String = "$39.99",
        monthlyPrice: String = "$6.99",
    ): PaywallConfig {
        val plans = listOf(
            mapOf("product_id" to "premium_annual", "label" to "Premium Annual", "price_display" to annualPrice,
                "is_default" to true, "badge" to badge, "sort_order" to 0),
            mapOf("product_id" to "premium_monthly", "label" to "Premium Monthly", "price_display" to monthlyPrice,
                "is_default" to false, "sort_order" to 1),
        )
        val doc = buildMap<String, Any> {
            put("id", "pw_plan_savings")
            put("name", "Plan savings")
            put("background", mapOf("type" to "color", "value" to "#FFFFFF"))
            put("sections", listOf(mapOf("id" to "sec_plans", "type" to "plans", "data" to mapOf("plans" to plans))))
            put("plans", plans)
            if (localizations != null) put("localizations", localizations)
        }
        return PaywallConfigParser.parseSinglePaywall("pw_plan_savings", doc) ?: error("paywall did not parse")
    }

    private fun render(config: PaywallConfig, name: String): ByteArray {
        val section = config.sections.first { it.type == "plans" }
        val path = "build/tmp/plan-savings-render/$name.png"
        File(path).parentFile?.mkdirs()
        captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record)) {
            CompositionLocalProvider(LocalPaywallPageHex provides config.pageBackgroundHex()) {
                Column(modifier = Modifier.fillMaxWidth().background(Color.White).padding(16.dp)) {
                    PaywallSectionView(
                        section = section,
                        config = config,
                        selectedPlanId = section.data?.plans?.firstOrNull()?.id,
                        isPurchasing = false,
                        onPlanSelect = {},
                        onCTATap = {},
                        onRestore = {},
                        loc = { _, fallback -> fallback },
                    )
                }
            }
        }
        val f = File(path)
        check(f.exists()) { "no frame was written to $path — every comparison would be about missing files" }
        return f.readBytes()
    }

    @Before
    fun enableCapture() {
        System.setProperty("roborazzi.test.record", "true")
    }

    private fun PaywallConfig.annualBadge() = sections.first { it.type == "plans" }.data?.plans?.first()?.badge

    @Test
    fun `the badge drawn agrees with the store prices drawn`() {
        val token = config("Save {{savings_percent}}").applyingStoreFacts(play)
        assertEquals("1 − 79.99 / (14.99 × 12) is 55.5%, floored", "Save 55%", token.annualBadge())

        val computed = render(token, "computed")
        val literalRight = render(config("Save 55%").applyingStoreFacts(play), "literal_right")
        val literalStale = render(config("Save 52%").applyingStoreFacts(play), "literal_stale")

        assertTrue("the badge must draw the figure the drawn prices give, to the pixel", computed.contentEquals(literalRight))
        assertFalse("negative control: the authored-price figure must look DIFFERENT", computed.contentEquals(literalStale))
    }

    @Test
    fun `a per-month annual price is read as a month - the badge draws 74 percent, not 97`() {
        fun cfg(badge: String) = config(badge, annualPrice = "\$5.00/mo, billed yearly", monthlyPrice = "\$19.99/mo")
        val drawn = cfg("Save {{savings_percent}}").applyingStoreFacts(emptyMap())
        assertEquals("1 − 5.00 / 19.99 is 74.99%, floored", "Save 74%", drawn.annualBadge())

        val computed = render(drawn, "per_month_computed")
        val literalRight = render(cfg("Save 74%").applyingStoreFacts(emptyMap()), "per_month_literal_right")
        val literalWrong = render(cfg("Save 97%").applyingStoreFacts(emptyMap()), "per_month_literal_wrong")
        assertTrue("the badge must draw the real saving, to the pixel", computed.contentEquals(literalRight))
        assertFalse("negative control: the shipped 97% must look DIFFERENT", computed.contentEquals(literalWrong))
    }

    @Test
    fun `a failed lookup fills the badge from the authored prices`() {
        val drawn = config("Save {{savings_percent}}").applyingStoreFacts(emptyMap())
        assertEquals("Save 52%", drawn.annualBadge())
        assertTrue(render(drawn, "authored").contentEquals(render(config("Save 52%").applyingStoreFacts(emptyMap()), "authored_literal")))
    }

    @Test
    fun `a translated badge is filled against the drawn order, and an unfillable one is dropped`() {
        val out = config(
            "Save {{savings_percent}}",
            mapOf("de" to mapOf("plan.0.badge" to "Spare {{savings_percent}}", "plan.1.badge" to "Spare {{savings_percent}}", "cta.text" to "Weiter")),
        ).applyingSavings()
        assertEquals("Spare 52%", out.localizations?.get("de")?.get("plan.0.badge"))
        assertNull("the monthly plan saves nothing", out.localizations?.get("de")?.get("plan.1.badge"))
        assertEquals("Weiter", out.localizations?.get("de")?.get("cta.text"))
    }
}
