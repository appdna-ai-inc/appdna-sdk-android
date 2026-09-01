package ai.appdna.sdk.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Clicks the REAL button and asserts what reaches the host dispatcher.
 *
 * WHY THIS IS NOT COVERED BY THE UNIT TESTS ALREADY: `OnboardingCTAFlagTest` proves
 * `OnboardingActionPair.encode` and `.decode` in isolation, and the shared fixtures prove
 * `parse`/`applyTo`/the advance machine. Neither one proves the piece BETWEEN them — that the
 * button's `when (action)` actually reaches the flag branch and hands the encoded pair to
 * `onAction`. That is precisely where `permission` broke before: the branch existed, the encode did
 * not, and a per-CTA permission type silently resolved to null while every unit test stayed green.
 *
 * So this renders the shipping `ContentBlockRendererView`, taps the button a user would tap, and
 * asserts on the exact string the host receives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class FlagCtaDispatchTest {

    @get:Rule
    val compose = createComposeRule()

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
    fun `a flag CTA hands the host the colon-encoded pair`() {
        val received = tap("Add a private tasting", listOf(
            button("Add a private tasting", "flag", "upsell_choice=booking"),
        ))
        assertEquals(listOf("flag:upsell_choice=booking"), received)

        // And the dispatcher's own decode turns it back into the authored flag — the round trip the
        // host actually depends on, across the real button.
        val (rawAction, value) = OnboardingActionPair.decode(received.single())
        assertEquals(OnboardingCTAFlag.ACTION_NAME, rawAction)
        assertEquals(OnboardingCTAFlag.Flag("upsell_choice", "booking"), OnboardingCTAFlag.parse(value))
    }

    @Test
    fun `a flag CTA with only a key emits the bare action`() {
        val received = tap("Talk to a human", listOf(
            button("Talk to a human", "flag", "wants_callback"),
        ))
        assertEquals(listOf("flag:wants_callback"), received)
        assertEquals("true", OnboardingCTAFlag.parse(OnboardingActionPair.decode(received.single()).second)?.value)
    }

    @Test
    fun `a flag CTA with no key at all still reaches the dispatcher`() {
        // An author who picked "Flag & continue" and left the key blank must still get an advancing
        // button. Emitting nothing would look like a dead control.
        val received = tap("Continue", listOf(button("Continue", "flag", null)))
        assertEquals(listOf("flag"), received)
    }

    @Test
    fun `a plain next CTA is unchanged by the flag branch`() {
        val received = tap("Continue", listOf(button("Continue", "next", null)))
        assertEquals(listOf("next"), received)
    }

    @Test
    fun `a permission CTA still carries its per-CTA type`() {
        // The regression this whole encode path exists for; it shares the branch structure.
        val received = tap("Enable alarms", listOf(button("Enable alarms", "permission", "alarm")))
        assertEquals(listOf("permission:alarm"), received)
    }
}
