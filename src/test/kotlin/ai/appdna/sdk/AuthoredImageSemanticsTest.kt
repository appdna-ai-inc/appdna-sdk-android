package ai.appdna.sdk

import ai.appdna.sdk.messages.InAppMessageView
import ai.appdna.sdk.messages.MessageConfigParser
import ai.appdna.sdk.onboarding.ContentBlockRendererView
import ai.appdna.sdk.onboarding.OnboardingConfigParser
import ai.appdna.sdk.paywalls.PaywallConfigParser
import ai.appdna.sdk.paywalls.PaywallSectionView
import ai.appdna.sdk.screens.ScreenConfig
import ai.appdna.sdk.screens.SectionContext
import ai.appdna.sdk.screens.sections.ImageSectionRenderer
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What TalkBack is handed for an authored image, read off the REAL composables' semantics tree (the tree
 * TalkBack walks — the merged one): a described image is one node read as its description with `Role.Image`; an image whose
 * description is blank is DECORATIVE and has no node at all — no "Image", no role, no loading spinner.
 *
 * THE CONTROLS, in every case: a sibling text is in the tree (this harness reads it), and the described twin of
 * each decorative image IS found (this harness can see that image when it is exposed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class AuthoredImageSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private val url = "https://cdn.example.invalid/authored-image-a11y.png"
    private val described = "A synthetic river valley under a clear sky"

    private data class Seen(val imageNodes: List<String>, val descriptions: List<String>, val progressNodes: Int, val sibling: Boolean)

    private fun read(content: @Composable () -> Unit): Seen {
        compose.setContent {
            Column {
                Text("Sibling label")
                content()
            }
        }
        compose.mainClock.advanceTimeBy(500)
        // The MERGED tree: what TalkBack is handed. A node set with clearAndSetSemantics keeps its children in the
        // unmerged tree, but they are replaced — never exposed to an accessibility service.
        val all = compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = false).fetchSemanticsNodes()
        return Seen(
            imageNodes = all.filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Image }
                .map { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") ?: "" },
            descriptions = all.flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription) ?: emptyList() },
            progressNodes = all.count { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null },
            sibling = all.any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Sibling label" } == true },
        )
    }

    private fun assertDescribed(s: Seen) {
        assertEquals("described image nodes: $s", listOf(described), s.imageNodes)
        assertEquals("the description is read once: $s", 1, s.descriptions.count { it == described })
        assertEquals("no spinner inside the image: $s", 0, s.progressNodes)
        assertTrue("control: this harness reads the sibling: $s", s.sibling)
    }

    private fun assertDecorative(s: Seen) {
        assertEquals("a decorative image has no node: $s", emptyList<String>(), s.imageNodes)
        assertFalse("a decorative image is not announced as \"Image\": $s", s.descriptions.contains("Image"))
        assertEquals("no spinner announced either: $s", 0, s.progressNodes)
        assertTrue("control: this harness reads the sibling: $s", s.sibling)
    }

    // ── onboarding image block (plain, framed, phone mockup) ──

    private fun onboarding(alt: String?, frame: String? = null, type: String = "image"): Seen {
        val block = mutableMapOf<String, Any>("id" to "img", "type" to type, "image_url" to url, "height" to 120)
        if (alt != null) block["alt"] = alt
        if (frame != null) block["image_frame"] = frame
        val blocks = OnboardingConfigParser.parseStepForTest(
            mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(block)),
        )?.config?.content_blocks ?: error("step did not parse")
        return read { ContentBlockRendererView(blocks = blocks, onAction = {}, toggleValues = mutableMapOf(), inputValues = mutableMapOf()) }
    }

    @Test fun `onboarding image with alt is read as its description`() = assertDescribed(onboarding(described))
    @Test fun `onboarding framed image with alt is read as its description`() = assertDescribed(onboarding(described, "color_frame"))
    @Test fun `onboarding phone mockup with alt is read as its description`() = assertDescribed(onboarding(described, "phone"))
    @Test fun `onboarding image with blank alt is skipped`() = assertDecorative(onboarding("   "))
    @Test fun `onboarding image with empty alt is skipped`() = assertDecorative(onboarding(""))
    @Test fun `onboarding image with no alt is skipped`() = assertDecorative(onboarding(null))
    @Test fun `onboarding framed image with blank alt is skipped`() = assertDecorative(onboarding("", "glow"))
    @Test fun `onboarding phone mockup with blank alt is skipped`() = assertDecorative(onboarding("", "phone"))
    @Test fun `onboarding animated image with alt is read as its description`() = assertDescribed(onboarding(described, type = "animated_image"))
    @Test fun `onboarding animated image with whitespace alt is skipped`() = assertDecorative(onboarding("  ", type = "animated_image"))

    // ── paywall image section ──

    private fun paywall(alt: String?): Seen {
        val cfg = mutableMapOf<String, Any>("image_url" to url)
        if (alt != null) cfg["alt"] = alt
        val config = PaywallConfigParser.parseSinglePaywall(
            "pw_alt",
            mapOf("id" to "pw_alt", "layout" to mapOf("type" to "stack", "sections" to listOf(mapOf("id" to "s1", "type" to "image", "config" to cfg)))),
        ) ?: error("paywall did not parse")
        val section = config.sections.first { it.type == "image" }
        return read {
            PaywallSectionView(
                section = section, config = config, selectedPlanId = null, isPurchasing = false,
                onPlanSelect = {}, onCTATap = {}, onRestore = {}, loc = { _, fallback -> fallback },
            )
        }
    }

    @Test fun `paywall image section with alt is read as its description`() = assertDescribed(paywall(described))
    @Test fun `paywall image section with blank alt is skipped`() = assertDecorative(paywall("  "))
    @Test fun `paywall image section with no alt is skipped`() = assertDecorative(paywall(null))

    // ── screen image section ──

    private fun screen(alt: String?): Seen {
        val data = mutableMapOf<String, Any>("image_url" to url, "height" to 120)
        if (alt != null) data["alt"] = alt
        val section = ScreenConfig.fromMap(
            mapOf("id" to "scr", "sections" to listOf(mapOf("id" to "s1", "type" to "image_section", "data" to data))),
        ).sections.first()
        return read { ImageSectionRenderer(section, SectionContext(screenId = "scr", onAction = {})) }
    }

    @Test fun `screen image section with alt is read as its description`() = assertDescribed(screen(described))
    @Test fun `screen image section with blank alt is skipped`() = assertDecorative(screen(" "))
    @Test fun `screen image section with no alt is skipped`() = assertDecorative(screen(null))

    // ── in-app message image: no description field, so always decorative ──

    private fun inApp(type: String) {
        val cfg = MessageConfigParser.parseSingleMessage(
            mapOf(
                "name" to "n", "message_type" to type,
                "content" to mapOf("title" to "A synthetic title", "image_url" to url, "cta_text" to "Go"),
                "trigger_rules" to mapOf("event" to "e"),
            ),
        ) ?: error("message did not parse")
        val s = read { InAppMessageView(config = cfg, onCTATap = {}, onDismiss = {}, isDark = false) }
        assertEquals("[$type] the message image has no node: $s", emptyList<String>(), s.imageNodes)
        assertFalse("[$type] not announced as \"Image\": $s", s.descriptions.contains("Image"))
        assertEquals("[$type] no spinner announced: $s", 0, s.progressNodes)
    }

    @Test fun `in-app modal image is skipped`() = inApp("modal")
    @Test fun `in-app fullscreen image is skipped`() = inApp("fullscreen")
}
