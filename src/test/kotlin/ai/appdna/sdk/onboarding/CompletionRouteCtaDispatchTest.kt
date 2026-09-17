package ai.appdna.sdk.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Clicks the REAL "open link after onboarding" button and asserts the destination survives the trip.
 *
 * WHY THIS IS NOT COVERED ALREADY: the store's own unit test proves record/take/clear in isolation,
 * and `OnboardingCompletionTest` proves the completion consumes it. Neither proves the piece
 * BETWEEN them — that the button's `when (action)` reaches this branch and COLON-ENCODES the URL.
 * The default arm is `else -> onAction(action)`, which drops `action_value` on the floor: the
 * destination would arrive null, the CTA would advance, and nothing would ever open. That is the
 * same shape as the `permission` bug this encode path was built for, and it is invisible to every
 * unit test that does not go through the button.
 *
 * Follows `FlagCtaDispatchTest`, which exists for the same reason on the same seam.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CompletionRouteCtaDispatchTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun reset() {
        // The store is a singleton and single-shot; a route left by another test would make this
        // one pass for the wrong reason.
        PendingCompletionRoute.clear()
    }

    private fun tap(label: String, blocks: List<ContentBlock>): List<String> {
        val received = mutableListOf<String>()
        compose.setContent {
            ContentBlockRendererView(
                blocks = blocks,
                onAction = { received += it },
                toggleValues = mutableMapOf(),
                inputValues = mutableMapOf(),
            )
        }
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
        return received
    }

    private fun button(text: String, action: String, actionValue: String?) = ContentBlock(
        id = "cta_1",
        type = "button",
        text = text,
        action = action,
        action_value = actionValue,
    )

    @Test
    fun `the CTA hands the host the colon-encoded destination`() {
        val received = tap("Book a wine tasting", listOf(
            button("Book a wine tasting", "link_on_complete", "hostapp://booking/tasting"),
        ))
        assertEquals(listOf("link_on_complete:hostapp://booking/tasting"), received)

        // And the dispatcher's own decode returns the authored URL — the round trip that matters.
        // Decoding splits on the FIRST ':' only, which a URL scheme depends on: a naive split would
        // hand back "hostapp" and lose the rest.
        val (rawAction, value) = OnboardingActionPair.decode(received.single())
        assertEquals(PendingCompletionRoute.ACTION_NAME, rawAction)
        assertEquals("hostapp://booking/tasting", value)
    }

    @Test
    fun `an https destination survives too`() {
        val received = tap("See tastings", listOf(
            button("See tastings", "link_on_complete", "https://hostapp.example/book?ref=onboarding"),
        ))
        assertEquals("https://hostapp.example/book?ref=onboarding",
            OnboardingActionPair.decode(received.single()).second)
    }

    @Test
    fun `a CTA authored with no URL still reaches the dispatcher`() {
        // An author who picked "Open link after onboarding" and left the URL blank must still get an
        // advancing button; emitting nothing would read as a dead control.
        val received = tap("Continue", listOf(button("Continue", "link_on_complete", null)))
        assertEquals(listOf("link_on_complete"), received)
    }

    @Test
    fun `a plain link CTA is untouched by the new branch`() {
        // `link` opens immediately and must NOT be re-routed to completion.
        val received = tap("Terms", listOf(button("Terms", "link", "https://example.com/terms")))
        assertEquals(emptyList<String>(), received)
    }

    @Test
    fun `the store is single-shot so a destination cannot fire twice`() {
        PendingCompletionRoute.record("hostapp://booking/tasting")
        assertEquals("hostapp://booking/tasting", PendingCompletionRoute.take())
        // A second completion — a later flow, say — must not navigate anywhere.
        assertNull(PendingCompletionRoute.take())
    }

    @Test
    fun `a later tap replaces an earlier destination`() {
        PendingCompletionRoute.record("hostapp://booking/tasting")
        PendingCompletionRoute.record("hostapp://audio/pass")
        assertEquals("hostapp://audio/pass", PendingCompletionRoute.take())
    }

    // ── the last link in the chain: completion actually OPENS it ───────────────────────────────
    //
    // Everything above proves the destination survives the button and reaches the store. None of it
    // proves the SDK ever opens it — the piece a user would actually notice. `complete()` takes the
    // opener as a parameter for exactly this reason.

    private fun complete(delegate: AppDNAOnboardingDelegate? = null): MutableList<String> {
        val opened = mutableListOf<String>()
        OnboardingCompletion.complete(
            flowId = "flow_1",
            totalSteps = 2,
            durationMs = 10,
            responses = emptyMap(),
            track = { _, _ -> },
            delegate = delegate,
            openRoute = { url -> opened += url; true },
        )
        return opened
    }

    @Test
    fun `completion opens the destination the CTA recorded`() {
        PendingCompletionRoute.record("hostapp://booking/tasting")
        assertEquals(listOf("hostapp://booking/tasting"), complete())
    }

    @Test
    fun `completion opens nothing when no CTA asked for a destination`() {
        assertEquals(emptyList<String>(), complete())
    }

    @Test
    fun `the destination is consumed, so a SECOND completion opens nothing`() {
        // The abandoned-flow bug: without consuming, finishing any later flow would navigate the
        // user somewhere they never asked to go.
        PendingCompletionRoute.record("hostapp://booking/tasting")
        assertEquals(1, complete().size)
        assertEquals(emptyList<String>(), complete())
    }

    @Test
    fun `the host delegate runs BEFORE the link opens`() {
        // The host dismisses the flow and does its own navigation in onOnboardingCompleted. Opening
        // first would race our navigation against theirs.
        val order = mutableListOf<String>()
        PendingCompletionRoute.record("hostapp://booking/tasting")
        val delegate = object : AppDNAOnboardingDelegate {
            override fun onOnboardingCompleted(flowId: String, responses: Map<String, Any>) {
                order += "delegate"
            }
        }
        OnboardingCompletion.complete(
            flowId = "flow_1", totalSteps = 1, durationMs = 1, responses = emptyMap(),
            track = { _, _ -> }, delegate = delegate,
            openRoute = { order += "open"; true },
        )
        assertEquals(listOf("delegate", "open"), order)
    }

    @Test
    fun `a throwing host delegate does not stop the link opening`() {
        PendingCompletionRoute.record("hostapp://booking/tasting")
        val delegate = object : AppDNAOnboardingDelegate {
            override fun onOnboardingCompleted(flowId: String, responses: Map<String, Any>) {
                throw IllegalStateException("host blew up")
            }
        }
        val opened = mutableListOf<String>()
        OnboardingCompletion.complete(
            flowId = "flow_1", totalSteps = 1, durationMs = 1, responses = emptyMap(),
            track = { _, _ -> }, delegate = delegate,
            openRoute = { url -> opened += url; true },
        )
        assertEquals(listOf("hostapp://booking/tasting"), opened)
    }

    @Test
    fun `a blank URL records nothing`() {
        PendingCompletionRoute.record("   ")
        assertNull(PendingCompletionRoute.take())
    }
}
