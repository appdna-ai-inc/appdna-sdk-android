package ai.appdna.sdk.onboarding

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.test.junit4.createComposeRule
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
 * Tapping an option in the shipping Select renderer calls the step's `next` when the block says
 * `auto_advance`, after the beat that draws the pick, once however many picks land in that beat.
 * THE CONTROL: the same block without the flag taps and answers, and nothing advances.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SelectAdvanceOnPickRenderTest {
    @get:Rule val compose = createComposeRule()
    private val inputValues = mutableStateMapOf<String, Any>()
    private val actions = mutableListOf<String>()

    @Before fun reset() = SelectedOptionStore.resetForTesting()

    private fun render(extra: Map<String, Any>, fieldConfig: Map<String, Any> = emptyMap()) {
        val blocks = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s", "type" to "custom",
                "config" to mapOf(
                    "content_blocks" to listOf(
                        mapOf(
                            "id" to "m", "type" to "input_select", "field_id" to "fav",
                            "field_config" to (mapOf("display_style" to "list") + fieldConfig),
                            "field_options" to listOf(
                                mapOf("id" to "a", "value" to "a", "label" to "Alpha"),
                                mapOf("id" to "b", "value" to "b", "label" to "Bravo"),
                            ),
                        ) + extra,
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

    @Test fun aSingleSelectAdvancesOnThePickAfterRecordingIt() {
        render(mapOf("auto_advance" to true))
        tapAndSettle("Alpha")
        assertEquals(listOf("next"), actions)
        assertEquals("a", inputValues["fav"])
    }

    @Test fun twoPicksInsideTheBeatAdvanceOnce() {
        render(mapOf("auto_advance" to true))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Alpha").performClick()
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithText("Bravo").performClick()
        compose.mainClock.advanceTimeBy(SelectAdvanceOnPick.DELAY_MS + 100)
        assertEquals(listOf("next"), actions)
        assertEquals("b", inputValues["fav"])
    }

    @Test fun theControlAnswersAndDoesNotAdvance() {
        render(emptyMap())
        tapAndSettle("Alpha")
        assertEquals(emptyList<String>(), actions)
        assertEquals("a", inputValues["fav"])
    }

    @Test fun aMultiSelectAdvancesOnThePickThatReachesTheLimit() {
        render(mapOf("auto_advance" to true), mapOf("multi_select" to true, "max_selections" to 2))
        tapAndSettle("Alpha")
        assertEquals(emptyList<String>(), actions)
        tapAndSettle("Bravo")
        assertEquals(listOf("next"), actions)
        assertEquals(listOf("a", "b"), inputValues["fav"])
    }
}
