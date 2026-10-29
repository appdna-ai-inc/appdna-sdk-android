package ai.appdna.sdk.onboarding

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A link in rich text and in an agreement's copy is TAPPED, and the browser is asked for the URL
 * iOS opens: an international host converted as Foundation converts it, and no browser at all for a
 * link Foundation cannot build. The shared fixtures read the URL off the drawn text; this operates
 * the drawn text, so the click handler between the two is covered as well.
 */
@RunWith(RobolectricTestRunner::class)
// Real text layout: without it every glyph measures ~1px and a tap lands past the text.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MarkdownLinkTapTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Draws [block] and taps the left edge of its text, [shown]. */
    private fun tapWholeBlock(block: Map<String, Any>, shown: String): Intent? {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>("content_blocks" to listOf(block)),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()
        compose.setContent {
            MaterialTheme {
                Column {
                    ContentBlockRendererView(blocks = blocks, onAction = {}, toggleValues = mutableMapOf(), inputValues = mutableMapOf())
                }
            }
        }
        compose.onNodeWithText(shown, substring = true).performTouchInput { click(Offset(4f, centerY)) }
        compose.waitForIdle()
        // The text starts the browser from its own Activity (`LocalContext`).
        return shadowOf(compose.activity).nextStartedActivity
    }

    @Test
    fun richTextLinkToAnInternationalHostOpensWhatIosOpens() {
        val intent = tapWholeBlock(mapOf("id" to "rt", "type" to "rich_text", "markdown_content" to "[straße](https://straße.de/x)"), "straße")
        assertEquals(Intent.ACTION_VIEW, intent?.action)
        assertEquals("https://xn--strae-oqa.de/x", intent?.data?.toString())
    }

    @Test
    fun richTextLinkWithAJoinerItsScriptAllowsOpens() {
        val intent = tapWholeBlock(mapOf("id" to "rt", "type" to "rich_text", "markdown_content" to "[ks](https://\u0915\u094D\u200D\u0937.com/x)"), "ks")
        assertEquals("https://xn--11b2ezcw70k.com/x", intent?.data?.toString())
    }

    @Test
    fun aLinkFoundationCannotBuildOpensNothing() {
        // A joiner its context does not allow: iOS draws the label and links nothing.
        assertNull(tapWholeBlock(mapOf("id" to "rt", "type" to "rich_text", "markdown_content" to "[ab](https://a\u200Db.com/x)"), "ab"))
    }

    @Test
    fun agreementLinkToAnInternationalHostOpensWhatIosOpens() {
        val intent = tapWholeBlock(
            mapOf(
                "id" to "ag", "type" to "agreement", "field_id" to "ag",
                "field_config" to mapOf("agreement_text" to "[straße](https://straße.de/terms)"),
            ),
            "straße",
        )
        assertEquals("https://xn--strae-oqa.de/terms", intent?.data?.toString())
    }

    @Test
    fun control_aTapOnAPlainAsciiLinkReachesTheBrowser() {
        // Without this, a tap that missed the text would pass the "opens nothing" case for the wrong reason.
        val intent = tapWholeBlock(mapOf("id" to "rt", "type" to "rich_text", "markdown_content" to "[abcdef](https://x.com/y)"), "abcdef")
        assertEquals("https://x.com/y", intent?.data?.toString())
    }
}
