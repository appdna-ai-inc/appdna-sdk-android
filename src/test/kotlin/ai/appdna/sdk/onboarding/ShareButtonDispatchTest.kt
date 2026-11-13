package ai.appdna.sdk.onboarding

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A `share` button, CLICKED in the real flow host: it starts the share sheet with the
 * button's own text and link, and the user stays on the step.
 *
 * Driven through `OnboardingFlowHost` rather than the block renderer alone, because "stays" is a
 * claim about the whole dispatch: the button, `handleAction` (whose unknown-action `else` ADVANCES)
 * and the flow's step index. The control test proves the same harness DOES see an advance, so
 * "still on the first screen" is not passing because nothing can ever move.
 *
 * Both a top-level button and a Multi-buttons child are tapped: the child is drawn by the same
 * `ButtonBlock`, but through a different container, and that is the path a regression would take.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ShareButtonDispatchTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun reset() = OnboardingPressedButton.clear()

    private val completed = mutableListOf<String>()

    private fun flow(): OnboardingFlowConfig = OnboardingConfigParser.parseFlowForTest(
        "f",
        mapOf(
            "id" to "f", "name" to "f", "version" to 1, "settings" to emptyMap<String, Any>(),
            "steps" to listOf(
                mapOf(
                    "id" to "milestone", "type" to "custom",
                    "content_blocks" to listOf(
                        mapOf("id" to "h1", "type" to "heading", "text" to "First screen"),
                        mapOf(
                            "id" to "cta_share", "type" to "button", "text" to "Share my week",
                            "action" to "share",
                            "share_text" to "  I finished my first week!  ",
                            "share_url" to " https://example.com/invite ",
                        ),
                        mapOf(
                            "id" to "cta_share_empty", "type" to "button", "text" to "Share nothing",
                            "action" to "share", "share_text" to "   ",
                        ),
                        mapOf(
                            "id" to "mb", "type" to "multi_buttons",
                            "children" to listOf(
                                mapOf(
                                    "id" to "child_share", "type" to "button", "text" to "Invite a friend",
                                    "action" to "share", "share_url" to "https://example.com/child",
                                ),
                                mapOf("id" to "child_next", "type" to "button", "text" to "Go on", "action" to "next"),
                            ),
                        ),
                    ),
                ),
                mapOf(
                    "id" to "after", "type" to "custom",
                    "content_blocks" to listOf(mapOf("id" to "h2", "type" to "heading", "text" to "Second screen")),
                ),
            ),
        ),
    )

    private fun host() {
        compose.setContent {
            OnboardingFlowHost(
                flow = flow(), viewModel = null, delegate = null,
                onStepViewed = { _, _ -> }, onStepCompleted = { id, _, _ -> completed += id }, onStepSkipped = { _, _ -> },
                onFlowCompleted = {}, onFlowDismissed = { _, _ -> },
            )
        }
        compose.waitForIdle()
    }

    private fun exists(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun tap(label: String) {
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
        repeat(30) { compose.mainClock.advanceTimeByFrame() }
    }

    /** The share text of the chooser started from the host activity, or null when none was. */
    private fun startedShareText(): String? {
        val started = shadowOf(compose.activity).nextStartedActivity ?: return null
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        @Suppress("DEPRECATION")
        val send = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        return send.getStringExtra(Intent.EXTRA_TEXT)
    }

    private fun assertStayed() {
        assertTrue("still on the step the share was tapped on", exists("First screen"))
        assertTrue("the next step never appeared", !exists("Second screen"))
        assertEquals("no step completed", emptyList<String>(), completed)
        assertNull("a share press is not remembered for routing", OnboardingPressedButton.take())
    }

    @Test
    fun `a top-level share button opens the sheet with its text and link and stays`() {
        host()
        tap("Share my week")
        assertEquals("I finished my first week!\nhttps://example.com/invite", startedShareText())
        assertStayed()
    }

    @Test
    fun `a multi-buttons child share button opens the sheet and stays`() {
        host()
        tap("Invite a friend")
        assertEquals("https://example.com/child", startedShareText())
        assertStayed()
    }

    @Test
    fun `a share button with nothing to share opens nothing and stays`() {
        host()
        tap("Share nothing")
        assertNull("no sheet for an empty share", shadowOf(compose.activity).nextStartedActivity)
        assertStayed()
    }

    @Test
    fun `control - a next button in the same harness does advance`() {
        host()
        tap("Go on")
        assertTrue("the harness can see an advance", exists("Second screen"))
        assertEquals(listOf("milestone"), completed)
    }
}
