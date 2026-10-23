package ai.appdna.sdk.paywalls

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1101 — A FAILED PURCHASE CAN ALWAYS BE DISMISSED, whatever `allow_dismiss` says.
 *
 * The post-purchase overlay is a `Box` with `fillMaxSize()` and a scrim, so it covers the whole
 * paywall — the CTA included. It used to be dismissible only when `allowDismiss` was set, and
 * `allow_dismiss: false` on a FAILURE therefore trapped the user: the button that buys is behind the
 * scrim, the plan rows are behind it, and the only exits are the system back button or killing the
 * app. Worse, a tap lands on the scrim, so it runs no action, logs nothing and calls no delegate —
 * indistinguishable from a store that refused, which is the shape #1072 is about.
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

    private fun render() {
        compose.setContent {
            PaywallScreen(
                config = paywall(),
                onPlanSelected = { _, _ -> },
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
            "a failure overlay authored allow_dismiss:false did not dismiss, so the paywall's CTA " +
                "is still behind a full-screen scrim with no way out — that is #1101",
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
            "a SUCCESS overlay authored allow_dismiss:false dismissed on tap, so #1101's fix was " +
                "applied too widely and the flag no longer means anything",
            compose.onAllNodesWithTextSafe(successMessage) > 0,
        )
    }

    /** Counts matching nodes without throwing when there are none. */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextSafe(
        text: String,
    ): Int = onAllNodes(
        androidx.compose.ui.test.hasText(text, substring = true),
    ).fetchSemanticsNodes().size
}
