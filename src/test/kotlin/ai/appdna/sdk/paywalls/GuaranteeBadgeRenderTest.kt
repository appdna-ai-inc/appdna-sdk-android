package ai.appdna.sdk.paywalls

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The guarantee section through the real `PaywallScreen`: the default badge draws a shield, not a "?". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class GuaranteeBadgeRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun paywall(icon: String?): PaywallConfig = PaywallConfigParser.parseSinglePaywall(
        "pw_g",
        mapOf(
            "id" to "pw_g", "name" to "g",
            "background" to mapOf("type" to "color", "value" to "#FFFFFF"),
            "sections" to listOf(mapOf("id" to "g", "type" to "guarantee", "data" to buildMap<String, Any> {
                put("guarantee_text", "Cancel anytime"); if (icon != null) put("icon", icon)
            })),
            "cta" to mapOf("text" to "Continue"),
        ),
    ) ?: error("paywall did not parse")

    private fun questionMarks(icon: String?): Int {
        compose.setContent { PaywallScreen(config = paywall(icon), onPlanSelected = { _, _ -> }, onRestore = {}, onDismiss = {}) }
        compose.waitForIdle()
        return compose.onAllNodes(hasText("?")).fetchSemanticsNodes().size
    }

    @Test fun `the default badge draws an icon, not a question mark`() = assertEquals(0, questionMarks(null))

    @Test fun `the old default spelling draws an icon`() = assertEquals(0, questionMarks("shield.checkmark.fill"))

    /** Every SF Symbol the console's picker offers, one guarantee badge each on one paywall: none draws a "?". */
    // A tall window, so the lazy paywall composes every badge (the count below proves it did).
    @Config(qualifiers = "w390dp-h3200dp-xhdpi")
    @Test fun `every picker SF Symbol draws an icon`() {
        val names = listOf(
            "bolt.fill", "gift.fill", "trophy.fill", "flag.fill", "bookmark.fill", "tag.fill", "sparkles",
            "flame.fill", "crown.fill", "paintpalette.fill", "info.circle", "heart.circle.fill",
        )
        val config = PaywallConfigParser.parseSinglePaywall(
            "pw_many",
            mapOf(
                "id" to "pw_many", "name" to "many",
                "background" to mapOf("type" to "color", "value" to "#FFFFFF"),
                "sections" to names.mapIndexed { i, n ->
                    mapOf("id" to "g$i", "type" to "guarantee", "data" to mapOf("guarantee_text" to "Badge $i", "icon" to n))
                },
                "cta" to mapOf("text" to "Continue"),
            ),
        ) ?: error("paywall did not parse")
        compose.setContent { PaywallScreen(config = config, onPlanSelected = { _, _ -> }, onRestore = {}, onDismiss = {}) }
        compose.waitForIdle()
        assertEquals(names.size, compose.onAllNodes(hasText("Badge ", substring = true)).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText("?")).fetchSemanticsNodes().size)
    }

    /** Proves this harness can see the fallback at all: a symbol nobody maps. */
    @Test fun `the control - an unmapped symbol does draw the fallback`() = assertEquals(1, questionMarks("nosuch.symbol.name"))
}
