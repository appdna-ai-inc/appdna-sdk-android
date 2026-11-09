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
 * Pressing a button in the shipping renderer records which one, for the rules to read. A press that
 * does not advance the step (a `link`) records nothing, so it cannot route the advance after it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ButtonPressedRoutingTest {
    @get:Rule val compose = createComposeRule()
    private val actions = mutableListOf<String>()

    @Before fun reset() = OnboardingPressedButton.clear()

    private fun render(vararg extra: Pair<String, Any>) {
        val blocks = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s", "type" to "custom",
                "config" to mapOf("content_blocks" to listOf(mapOf("id" to "login", "type" to "button", "text" to "Log in") + extra)),
            ),
        )!!.config.content_blocks!!
        compose.setContent {
            ContentBlockRendererView(blocks = blocks, onAction = { actions.add(it) }, toggleValues = mutableMapOf())
        }
    }

    @Test fun aNextButtonRecordsItsOwnId() {
        render()
        compose.onNodeWithText("Log in").performClick()
        compose.waitForIdle()
        assertEquals(listOf("next"), actions)
        assertEquals("login", OnboardingPressedButton.take())
        assertNull("take clears it", OnboardingPressedButton.take())
    }

    @Test fun aLinkButtonRecordsNothing() {
        render("action" to "link", "action_value" to "https://example.com")
        compose.onNodeWithText("Log in").performClick()
        compose.waitForIdle()
        assertNull(OnboardingPressedButton.take())
    }
}
