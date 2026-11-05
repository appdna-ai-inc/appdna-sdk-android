package ai.appdna.sdk.paywalls

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A paywall image section's "Alt Text" is what TalkBack reads: the REAL `PaywallSectionView`, given a
 * paywall decoded by the REAL parser, is searched by content description — the same lookup TalkBack's
 * node tree answers. THE CONTROL: the same section without alt text has no node carrying that text, so a
 * pass is not a harness that finds every string.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class PaywallImageAltSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private val label = "A runner crossing a finish line"

    private fun show(alt: String?) {
        val cfg = mutableMapOf<String, Any>("image_url" to "https://cdn.example.com/p/hero.png")
        if (alt != null) cfg["alt"] = alt
        val config = PaywallConfigParser.parseSinglePaywall(
            "pw_alt",
            mapOf("id" to "pw_alt", "layout" to mapOf("type" to "stack", "sections" to listOf(mapOf("id" to "s1", "type" to "image", "config" to cfg)))),
        ) ?: error("paywall did not parse")
        val section = config.sections.first { it.type == "image" }
        compose.setContent {
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
        compose.mainClock.advanceTimeBy(500)
    }

    @Test
    fun `the authored alt text is the image's content description`() {
        show(label)
        assertEquals(1, compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().size)
    }

    @Test
    fun `control - without alt text no node carries it`() {
        show(null)
        assertEquals(0, compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().size)
    }
}
