package ai.appdna.sdk.paywalls

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A paywall extra button authored `action: "share"` (#1797), CLICKED on the real `PaywallScreen`:
 * the share sheet opens with the button's `share_text` and its `url` as the link, and the paywall
 * stays — no dismiss, no purchase, no host action.
 *
 * The control is a sibling `dismiss` extra button on the same screen, which DOES reach onDismiss: so
 * "nothing dismissed" below is about the share branch, not a harness that cannot see a dismiss.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PaywallShareButtonTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun paywallMap(): Map<String, Any> = mapOf(
        "id" to "pw_share",
        "name" to "Share",
        "cta" to mapOf("text" to "Start Free Trial"),
        "layout" to mapOf(
            "type" to "stack",
            "sections" to listOf(
                mapOf(
                    "id" to "sec_cta",
                    "type" to "cta",
                    "config" to mapOf(
                        "extra_buttons" to listOf(
                            mapOf(
                                "text" to "Share with a friend", "action" to "share",
                                "share_text" to "  Try this app with me  ", "url" to " https://example.com/invite ",
                            ),
                            mapOf("text" to "Share nothing", "action" to "share", "share_text" to " "),
                            mapOf("text" to "Maybe later", "action" to "dismiss"),
                        ),
                    ),
                ),
            ),
        ),
    )

    private class Seen {
        val actions = mutableListOf<PaywallAction>()
        var dismissed = 0
    }

    private fun render(): Seen {
        val seen = Seen()
        val config = PaywallConfigParser.parseSinglePaywall("pw_share", paywallMap()) ?: error("paywall did not parse")
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalPaywallAction provides { action -> seen.actions += action },
            ) {
                PaywallScreen(
                    config = config,
                    onPlanSelected = { _, _ -> },
                    onRestore = {},
                    onDismiss = { seen.dismissed++ },
                )
            }
        }
        // Settle the first frame, then stop driving the clock: the paywall carries animations
        // that never end (see PaywallActionsAreReportedTest).
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        return seen
    }

    private fun tap(label: String) {
        compose.onNodeWithText(label).performClick()
        repeat(10) { compose.mainClock.advanceTimeByFrame() }
    }

    @Test
    fun `a share extra button opens the sheet over the paywall and the paywall stays`() {
        val seen = render()
        tap("Share with a friend")
        val started = shadowOf(compose.activity).nextStartedActivity ?: error("no share sheet was started")
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        @Suppress("DEPRECATION")
        val send = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals("Try this app with me\nhttps://example.com/invite", send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("the paywall stays", 0, seen.dismissed)
        assertEquals("no host action for a share", emptyList<PaywallAction>(), seen.actions)
        compose.onNodeWithText("Start Free Trial").assertExists()
    }

    @Test
    fun `a share extra button with nothing to share opens nothing and stays`() {
        val seen = render()
        tap("Share nothing")
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        assertEquals(0, seen.dismissed)
    }

    @Test
    fun `control - a dismiss extra button on the same screen does dismiss`() {
        val seen = render()
        tap("Maybe later")
        assertEquals(1, seen.dismissed)
        assertNull(shadowOf(compose.activity).nextStartedActivity)
    }
}
