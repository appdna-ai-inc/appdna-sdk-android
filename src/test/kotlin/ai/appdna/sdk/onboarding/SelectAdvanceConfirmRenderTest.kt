package ai.appdna.sdk.onboarding

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Select with `auto_advance` and `advance_confirm` asks before it moves on: the pick opens the dialog, "No" stays
 * with the pick kept, a second pick asks again, and "Yes" calls the step's `next` once.
 * THE CONTROL: the same block without `advance_confirm` advances on the pick with no dialog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SelectAdvanceConfirmRenderTest {
    @get:Rule val compose = createComposeRule()
    private val inputValues = mutableStateMapOf<String, Any>()
    private val actions = mutableListOf<String>()

    @Before fun reset() = SelectedOptionStore.resetForTesting()

    private fun render(fieldConfig: Map<String, Any>) {
        val blocks = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s", "type" to "custom",
                "config" to mapOf(
                    "content_blocks" to listOf(
                        mapOf(
                            "id" to "m", "type" to "input_select", "field_id" to "fav", "auto_advance" to true,
                            "field_config" to (mapOf("display_style" to "list") + fieldConfig),
                            "field_options" to listOf(
                                mapOf("id" to "a", "value" to "a", "label" to "Alpha"),
                                mapOf("id" to "b", "value" to "b", "label" to "Bravo"),
                            ),
                        ),
                    ),
                ),
            ),
        )!!.config.content_blocks!!
        compose.setContent {
            ContentBlockRendererView(blocks = blocks, onAction = { actions.add(it) }, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
    }

    private fun tapAndSettle(label: String) {
        compose.onNodeWithText(label).performClick()
        compose.mainClock.advanceTimeBy(SelectAdvanceOnPick.DELAY_MS + 100)
        compose.waitForIdle()
    }

    @Test fun thePickAsksAndNoStaysWithThePickKept() {
        render(mapOf("advance_confirm" to true, "advance_confirm_text" to "Lock it in?", "advance_confirm_yes" to "Go", "advance_confirm_no" to "Change"))
        tapAndSettle("Alpha")
        compose.onNodeWithText("Lock it in?").assertExists()
        assertEquals(emptyList<String>(), actions)
        compose.onNodeWithText("Change").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Lock it in?").assertCountEquals(0)
        assertEquals(emptyList<String>(), actions)
        assertEquals("a", inputValues["fav"])
    }

    @Test fun aRepickAsksAgainAndYesAdvancesOnce() {
        render(mapOf("advance_confirm" to true))
        tapAndSettle("Alpha")
        compose.onNodeWithText("No").performClick()
        compose.waitForIdle()
        tapAndSettle("Bravo")
        compose.onNodeWithText("Are you sure?").assertExists()
        compose.onNodeWithText("Yes").performClick()
        compose.waitForIdle()
        assertEquals(listOf("next"), actions)
        assertEquals("b", inputValues["fav"])
    }

    @Test fun theControlAdvancesWithNoQuestion() {
        render(emptyMap())
        tapAndSettle("Alpha")
        compose.onAllNodesWithText("Are you sure?").assertCountEquals(0)
        assertEquals(listOf("next"), actions)
    }
}
