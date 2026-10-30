package ai.appdna.sdk.paywalls

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A FAILED PURCHASE CAN ALWAYS BE DISMISSED, whatever `allow_dismiss` says.
 * And it is a BOTTOM BANNER with no scrim, the presentation iOS draws, so the paywall
 * behind it stays reachable.
 *
 * (History, kept because it is why the reachability test below exists:)
 * The post-purchase overlay is a `Box` with `fillMaxSize()` and a scrim, so it covers the whole
 * paywall — the CTA included. It used to be dismissible only when `allowDismiss` was set, and
 * `allow_dismiss: false` on a FAILURE therefore trapped the user: the button that buys is behind the
 * scrim, the plan rows are behind it, and the only exits are the system back button or killing the
 * app. Worse, a tap lands on the scrim, so it runs no action, logs nothing and calls no delegate —
 * indistinguishable from a store that refused.
 *
 * The flag keeps its meaning for a SUCCESS overlay (hold the celebration, then route) and for the
 * paywall itself (forced engagement for N seconds). It is only the failure path that is forced open.
 *
 * iOS never had the trap — its failure UI is a bottom banner that leaves the rest of the paywall
 * reachable — so this is also Rule 8 closing a one-platform behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class FailureOverlayIsAlwaysDismissibleTest {

    @get:Rule
    val compose = createComposeRule()

    private val failureMessage = "Payment failed. Please try again."

    /** A one-plan paywall; the overlay is what this test is about, not the plans. */
    private fun paywall(): PaywallConfig = PaywallConfigParser.parseSinglePaywall(
        "pw_failure_overlay",
        mapOf(
            "id" to "pw_failure_overlay",
            "name" to "Failure overlay",
            "layout" to mapOf(
                "type" to "stack",
                "sections" to listOf(
                    mapOf(
                        "id" to "sec_plans",
                        "type" to "plans",
                        "config" to mapOf(
                            "plan_display_style" to "vertical_stack",
                            "plans" to listOf(
                                mapOf(
                                    "id" to "plan_monthly",
                                    "product_id" to "app.premium.monthly",
                                    "label" to "Monthly",
                                    "price_display" to "$9.99",
                                    "is_default" to true,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            "cta" to mapOf("text" to "Start Free Trial"),
        ),
    ) ?: error("paywall did not parse")

    private val ctaText = "Start Free Trial"

    private fun render(onPlanSelected: (PaywallPlan) -> Unit = {}) {
        compose.setContent {
            PaywallScreen(
                config = paywall(),
                onPlanSelected = { plan, _ -> onPlanSelected(plan) },
                onRestore = {},
                onDismiss = {},
            )
        }
    }

    /**
     * `allow_dismiss: false` on a failure — the shape that used to trap the user.
     *
     * 🔴 The assertion is that the overlay GOES, not that something is clickable. A `clickable`
     * modifier that is present but wired to nothing would satisfy a source check and still trap
     * the user; only the message disappearing proves the tap did something.
     */
    @Test
    fun `a failure overlay with allow_dismiss false still dismisses on tap`() {
        PaywallActivity.postPurchaseOverlay = PostPurchaseOverlayState(
            message = failureMessage,
            confetti = false,
            lottieUrl = null,
            retryText = null,
            action = "show_error",
            allowDismiss = false,
        )
        render()
        compose.onNodeWithText(failureMessage, substring = true).assertExists()

        compose.onNodeWithText(failureMessage, substring = true).performClick()
        compose.waitForIdle()

        assertTrue(
            "a failure banner authored allow_dismiss:false did not dismiss on tap",
            compose.onAllNodesWithTextSafe(failureMessage) == 0,
        )
    }

    /**
     * THE CONTROL: a SUCCESS overlay with `allow_dismiss: false` keeps the flag's meaning.
     *
     * Without this, "the failure one dismisses" could be satisfied by making EVERY overlay
     * dismissible, which would quietly undo the flag an author set for a celebration that should
     * hold until it routes. The fix is deliberately scoped to the failure path.
     */
    @Test
    fun `THE CONTROL — a SUCCESS overlay with allow_dismiss false still does not dismiss`() {
        val successMessage = "Welcome to Premium!"
        PaywallActivity.postPurchaseOverlay = PostPurchaseOverlayState(
            message = successMessage,
            confetti = false,
            lottieUrl = null,
            retryText = null,
            action = "show_message",
            allowDismiss = false,
        )
        render()
        compose.onNodeWithText(successMessage, substring = true).assertExists()

        compose.onNodeWithText(successMessage, substring = true).performClick()
        compose.waitForIdle()

        assertTrue(
            "a SUCCESS overlay authored allow_dismiss:false dismissed on tap, so the always-dismissible rule was " +
                "applied too widely and the flag no longer means anything",
            compose.onAllNodesWithTextSafe(successMessage) > 0,
        )
    }

    /**
     * THE SAME FAILURE TWICE, THEN ONE TAP: THE BANNER STAYS GONE.
     *
     * Found on the api34 emulator walk: a user whose card is declined, who taps the purchase button
     * again and is declined again, taps the banner — and it comes straight back. The poller only took
     * a payload that differed from the one on screen, and the payload is a data class, so the second,
     * IDENTICAL failure was never taken and waited in the slot until the tap emptied the screen.
     */
    @Test
    fun `a second identical failure does not resurrect the banner after it is tapped away`() {
        val failure = PostPurchaseOverlayState(message = failureMessage, action = "retry", retryText = "Try Again")
        PaywallActivity.postPurchaseOverlay = failure
        render()
        compose.onNodeWithText(failureMessage, substring = true).assertExists()

        // The same purchase is refused again: an equal payload, a new instance.
        PaywallActivity.postPurchaseOverlay = failure.copy()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        compose.onNodeWithText(failureMessage, substring = true).performClick()
        // Several poll intervals (100ms each) — long enough for a waiting payload to be taken again.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        assertTrue(
            "the banner came back after it was tapped away — a second identical failure was left in " +
                "the overlay slot and the next poll drew it again",
            compose.onAllNodesWithTextSafe(failureMessage) == 0,
        )
    }

    /**
     * THE RETRY IS A SECOND TAP ON THE CTA: it buys the selected plan AND puts the CTA in its
     * in-progress state. The old retry was a cut-down copy that bought without it, so the CTA stayed
     * tappable mid-purchase (seen on the api34 emulator) — buying alone cannot tell the two apart,
     * which is why "Processing..." is asserted too.
     */
    @Test
    fun `the retry button buys the selected plan through the CTA's own path`() {
        PaywallActivity.postPurchaseOverlay = PostPurchaseOverlayState(
            message = failureMessage,
            action = "retry",
            retryText = "Try Again",
        )
        var bought: String? = null
        render { bought = it.id }
        compose.onNodeWithText("Try Again").performClick()
        compose.waitForIdle()

        assertTrue("the retry bought nothing", bought == "plan_monthly")
        assertTrue(
            "the retry bought without the CTA's in-progress state — it is not the CTA's path",
            compose.onAllNodesWithTextSafe("Processing...") > 0,
        )
        assertTrue("the banner is still up after the retry", compose.onAllNodesWithTextSafe(failureMessage) == 0)
    }

    /**
     * WITH THE BANNER UP, THE CTA STILL BUYS.
     *
     * This is the behaviour the presentation change exists for: iOS's banner leaves the plan rows
     * and the button that buys reachable, so a retry is one tap. Android's old centred card sat
     * on a `fillMaxSize()` scrim, and a tap on the CTA landed on the scrim instead — so this test
     * fails against it, which is what makes it evidence rather than a description.
     */
    @Test
    fun `the CTA behind a failure banner still buys`() {
        PaywallActivity.postPurchaseOverlay = PostPurchaseOverlayState(
            message = failureMessage,
            action = "show_error",
            allowDismiss = true,
        )
        var bought: String? = null
        render { bought = it.id }
        compose.onNodeWithText(failureMessage, substring = true).assertExists()

        compose.onNodeWithText(ctaText).performClick()
        compose.waitForIdle()

        assertTrue(
            "the CTA was tapped with the failure banner up and nothing was bought — something " +
                "still covers the paywall behind the banner",
            bought == "plan_monthly",
        )
    }

    /**
     * THE BANNER SITS ABOVE THE CTA, NOT ON IT.
     *
     * iOS places it at `max(80, pinnedZoneHeight + 16)` above the bottom, where the pinned zone is
     * MEASURED, so it clears the CTA however tall the pinned group is. A banner merely anchored to
     * the bottom would pass the reachability test above whenever the tap point missed it and still
     * cover the CTA's label, so the geometry is asserted on its own.
     */
    @Test
    fun `the failure banner is at the bottom, and clears the CTA`() {
        PaywallActivity.postPurchaseOverlay = PostPurchaseOverlayState(
            message = failureMessage,
            action = "retry",
            retryText = "Try Again",
        )
        render()
        compose.waitForIdle()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val retry = compose.onNodeWithText("Try Again").fetchSemanticsNode().boundsInRoot
        val message = compose.onNodeWithText(failureMessage, substring = true).fetchSemanticsNode().boundsInRoot
        val cta = compose.onNodeWithText(ctaText).fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the failure message is in the top half of the screen (top=${message.top}, " +
                "screen=${root.height}) — it is still the centred card, not the bottom banner",
            message.top > root.height / 2,
        )
        assertTrue(
            "the banner's lowest content (retry bottom=${retry.bottom}) overlaps the CTA " +
                "(top=${cta.top}) — it is not sitting above the measured pinned zone",
            retry.bottom <= cta.top,
        )
    }

    /** Counts matching nodes without throwing when there are none. */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextSafe(
        text: String,
    ): Int = onAllNodes(
        androidx.compose.ui.test.hasText(text, substring = true),
    ).fetchSemanticsNodes().size
}
