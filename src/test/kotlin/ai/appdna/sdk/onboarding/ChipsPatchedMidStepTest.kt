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
 * A chips field whose stored value is written from outside mid-step (a host's `inputValuePatches`) draws
 * and toggles what the field now holds. A list counts only when every element is a String, so after a
 * `["a", 2]` or `[1]` patch nothing is selected, and the next tap stores just the tapped chip. The
 * shipping renderer is rendered and operated: chips are tapped, and the answer is read back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ChipsPatchedMidStepTest {
    @get:Rule val compose = createComposeRule()
    private val inputValues = mutableStateMapOf<String, Any>()

    @Before fun reset() = SelectedOptionStore.resetForTesting()

    private fun render(type: String) {
        val blocks = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s", "type" to "custom",
                "config" to mapOf(
                    "content_blocks" to listOf(
                        mapOf(
                            "id" to "c", "type" to type, "field_id" to "tags",
                            "field_config" to mapOf("display_style" to "list"),
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

    private fun tap(label: String) {
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    private fun patch(value: Any) {
        compose.runOnIdle { inputValues["tags"] = value }
        compose.waitForIdle()
    }

    @Test fun aMixedListPatchIsNoSelectionAndTheNextTapStoresOnlyTheTappedChip() {
        render("input_chips")
        tap("Alpha")
        assertEquals(listOf("a"), inputValues["tags"])
        patch(listOf("a", 2))
        tap("Bravo")
        assertEquals(listOf("b"), inputValues["tags"])
    }

    @Test fun aNumberListPatchIsNoSelection() {
        render("input_chips")
        tap("Alpha")
        patch(listOf(1))
        tap("Bravo")
        assertEquals(listOf("b"), inputValues["tags"])
    }

    /** Control: an all-String patch IS the selection, so the next tap adds to it. */
    @Test fun anAllStringPatchIsTheSelection() {
        render("input_chips")
        tap("Alpha")
        patch(listOf("a"))
        tap("Bravo")
        assertEquals(listOf("a", "b"), inputValues["tags"])
    }
}
