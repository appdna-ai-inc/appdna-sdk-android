package ai.appdna.sdk.onboarding

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
 * A list-style MULTI-select Select draws exactly the selection its next tap toggles, and every tap
 * writes the selected-option store.
 *
 * The view and the write once read the stored answer differently: a stored single answer (a `String`,
 * as a host's `input_values` patch can write) was drawn CHECKED, while the tap treated it as no
 * selection. Tapping the checked row left it checked with the answer `[a]`; tapping another row
 * silently dropped it (`[b]`). iOS draws a `String` as nothing selected, so its screen and answer
 * agree. These tests render the shipping renderer, read the DRAWN selected state, and tap it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MultiSelectStoredAnswerDrawTest {
    @get:Rule val compose = createComposeRule()
    private val inputValues = mutableStateMapOf<String, Any>()

    @Before fun reset() = SelectedOptionStore.resetForTesting()

    private fun render(stored: Any) {
        val blocks = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s", "type" to "custom",
                "config" to mapOf(
                    "content_blocks" to listOf(
                        mapOf(
                            "id" to "m", "type" to "input_select", "field_id" to "fav", "multi_select" to true,
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
        inputValues["fav"] = stored
        compose.setContent {
            ContentBlockRendererView(blocks = blocks, onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
        compose.waitForIdle()
    }

    private fun drawnSelected(label: String): Boolean =
        compose.onNodeWithText(label).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true

    private fun tap(label: String) {
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun storedLabels(): List<Any?> =
        (SelectedOptionStore.snapshot()["fav"] as? List<Map<String, Any?>>)?.map { it["label"] }.orEmpty()

    /** Negative control: the harness can SEE a drawn selection, so "not selected" below means something. */
    @Test fun aStoredListDrawsItsPicksSelected() {
        render(listOf("b"))
        assertEquals(false, drawnSelected("Alpha"))
        assertEquals(true, drawnSelected("Bravo"))
    }

    @Test fun aStoredSingleAnswerDrawsNothingSelected() {
        render("a")
        assertEquals(false, drawnSelected("Alpha"))
        assertEquals(false, drawnSelected("Bravo"))
    }

    @Test fun tappingTheStoredSingleAnswerSelectsIt() {
        render("a")
        tap("Alpha")
        assertEquals(listOf("a"), inputValues["fav"])
        assertEquals(true, drawnSelected("Alpha"))
        assertEquals(listOf("Alpha"), storedLabels())
    }

    @Test fun twoTapsKeepBothPicksDrawnAndStoredInPickOrder() {
        render("a")
        tap("Bravo")
        tap("Alpha")
        assertEquals(listOf("b", "a"), inputValues["fav"])
        assertEquals(true, drawnSelected("Alpha"))
        assertEquals(true, drawnSelected("Bravo"))
        // The view's tap writes the selected-option store, not only `inputValues`.
        assertEquals(listOf("Bravo", "Alpha"), storedLabels())
    }

    @Test fun tappingASelectedRowRemovesItFromTheDrawingTheAnswerAndTheStore() {
        render(listOf("a", "b"))
        tap("Alpha")
        assertEquals(listOf("b"), inputValues["fav"])
        assertEquals(false, drawnSelected("Alpha"))
        assertEquals(listOf("Bravo"), storedLabels())
    }
}
