package ai.appdna.sdk.onboarding

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
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
 * A dropdown-style Select with `multi_select` draws what it stores: the field shows the picked labels in
 * pick order (the placeholder when nothing is picked), and the open menu draws a check beside each picked
 * option. A single dropdown draws no check. The shipping renderer is rendered and operated: the field is
 * tapped open and options are picked from the menu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MultiPickDropdownDrawTest {
    @get:Rule val compose = createComposeRule()
    private val inputValues = mutableStateMapOf<String, Any>()

    @Before fun reset() = SelectedOptionStore.resetForTesting()

    private fun render(multi: Boolean) {
        val blocks = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s", "type" to "custom",
                "config" to mapOf(
                    "content_blocks" to listOf(
                        mapOf(
                            "id" to "m", "type" to "input_select", "field_id" to "fav", "multi_select" to multi,
                            "field_placeholder" to "Pick some",
                            "field_options" to listOf(
                                mapOf("id" to "a", "value" to "a", "label" to "Alpha"),
                                mapOf("id" to "b", "value" to "b", "label" to "Bravo"),
                            ),
                        ),
                    ),
                ),
            ),
        )!!.config.content_blocks!!.toList()
        compose.setContent {
            ContentBlockRendererView(blocks = blocks, onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
        compose.waitForIdle()
    }

    private fun pick(fieldText: String, option: String) {
        compose.onNodeWithText(fieldText).performClick()
        compose.waitForIdle()
        // The open menu's item is the last node with that text (the field may draw it too).
        compose.onAllNodesWithText(option).onLast().performClick()
        compose.waitForIdle()
    }

    private fun checksInOpenMenu(fieldText: String): Int {
        compose.onNodeWithText(fieldText).performClick()
        compose.waitForIdle()
        return compose.onAllNodesWithText("✓").fetchSemanticsNodes().size
    }

    @Test fun theFieldDrawsThePicksInPickOrderAndTheMenuChecksThem() {
        render(multi = true)
        compose.onNodeWithText("Pick some").assertExists()
        pick("Pick some", "Bravo")
        compose.onNodeWithText("Bravo").assertExists()
        pick("Bravo", "Alpha")
        compose.onNodeWithText("Bravo, Alpha").assertExists()
        assertEquals(listOf("b", "a"), inputValues["fav"])
        assertEquals(2, checksInOpenMenu("Bravo, Alpha"))
    }

    @Test fun unpickingTheLastPickDrawsThePlaceholderAgain() {
        render(multi = true)
        pick("Pick some", "Alpha")
        pick("Alpha", "Alpha")
        assertEquals(emptyList<String>(), inputValues["fav"])
        compose.onNodeWithText("Pick some").assertExists()
    }

    /** Negative control: a single dropdown stores one value and draws no check. */
    @Test fun aSingleDropdownStoresOneValueAndDrawsNoCheck() {
        render(multi = false)
        pick("Pick some", "Bravo")
        assertEquals("b", inputValues["fav"])
        assertEquals(0, checksInOpenMenu("Bravo"))
    }
}
