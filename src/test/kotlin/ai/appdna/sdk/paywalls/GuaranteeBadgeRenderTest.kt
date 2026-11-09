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

    /** Proves this harness can see the fallback at all: a symbol nobody maps. */
    @Test fun `the control - an unmapped symbol does draw the fallback`() = assertEquals(1, questionMarks("nosuch.symbol.name"))
}
