package ai.appdna.sdk.onboarding

import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Leaving a location field while the input method is composing after Google's line (#1147), on each
 * REAL location composable: the form step's field and the `input_location` content block, each with a
 * second text field to move to.
 *
 * A blur ends the input method's composition, and the field reports that commit before it reports the
 * focus loss. When the field shows a Google answer's line, the commit is the first edit: it restarts from
 * the user's own text and draws a new field. The leaving must still count: the other field keeps the
 * focus, and the search the commit started never runs. Gboard composes almost every keystroke, so this is
 * the ordinary way a user types after the line and then taps the next field.
 *
 * The composition goes through the field's REAL input connection, the one the input method holds. The
 * autocomplete stub answers every query with one row, so a search that ran would also open a dropdown
 * the test can see.
 *
 * Controls in the same harness: a Google answer left without composing, a typed answer left while
 * composing, and a Google answer whose composition commits while the user STAYS in the field, which must
 * keep the focus across the new field and search (the harness sees the request and the dropdown).
 *
 * All place data is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LocationFieldBlurComposingComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val line = "Springfield, IL, USA"
    private val google: Map<String, Any> = mapOf(
        "formatted_address" to line, "address" to line, "city" to "Springfield", "state" to "Illinois",
        "state_code" to "IL", "country" to "United States", "country_code" to "US", "latitude" to 39.78,
        "longitude" to -89.65, "raw_query" to "Springf", "place_id" to "ChIJ-synthetic-springfield",
    )
    private val rowSecondLine = "Synthetic County, Exampleland"

    /** Records each autocomplete query and answers it with one row. */
    private inner class Recorder : GeocodeTransport {
        val queries = mutableListOf<String>()
        override suspend fun post(url: String, headers: Map<String, String>, body: String): GeocodeHttpReply {
            queries += JSONObject(body).optString("query")
            val row = JSONObject().put("id", "synthetic-listed-id").put("provider", "mapbox")
                .put("formatted_address", "Springfield Row, $rowSecondLine").put("name", "Springfield Row")
            return GeocodeHttpReply(200, JSONObject().put("data", JSONObject().put("suggestions", JSONArray().put(row))).toString())
        }
    }

    @After
    fun resetTransport() {
        locationGeocodeTransport = LiveGeocodeTransport
    }

    private lateinit var view: View

    private fun location(): SemanticsNodeInteraction = compose.onAllNodes(hasSetTextAction())[0]
    private fun other(): SemanticsNodeInteraction = compose.onAllNodes(hasSetTextAction())[1]
    private fun focused(node: SemanticsNodeInteraction): Boolean =
        node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) ?: false
    private fun text(node: SemanticsNodeInteraction): String =
        node.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun dropdownShown(): Boolean = compose.onAllNodes(hasText(rowSecondLine)).fetchSemanticsNodes().isNotEmpty()

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    // ---- The two surfaces ----------------------------------------------------------------------

    /** The content block, with an `input_text` block after it. Returns where the block keeps its answer. */
    private fun showBlock(seed: Any): () -> Any? {
        val inputValues = mutableStateMapOf<String, Any>("where" to seed)
        val blocks = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s_block", "type" to "content",
                "config" to mapOf(
                    "content_blocks" to listOf(
                        mapOf(
                            "id" to "b_loc", "type" to "input_location", "field_id" to "where",
                            "field_config" to mapOf("location_type" to "city", "location_min_chars" to 3),
                        ),
                        mapOf("id" to "b_name", "type" to "input_text", "field_id" to "name", "field_config" to mapOf("label" to "Name")),
                    ),
                ),
            ),
        )!!.config.content_blocks!!.toList()
        compose.setContent {
            view = LocalView.current
            ContentBlockRendererView(blocks = blocks, onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
        compose.waitForIdle()
        return { inputValues["where"] }
    }

    /** The form step, with a text field after the location field. Its answers are read on Continue only. */
    private fun showFormStep(seed: Any): () -> Any? {
        val step = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s_form", "type" to "form",
                "config" to mapOf(
                    "fields" to listOf(
                        mapOf("id" to "where", "type" to "location", "label" to "Where", "required" to true),
                        mapOf("id" to "name", "type" to "text", "label" to "Name"),
                    ),
                ),
            ),
        ) ?: error("form step did not parse")
        compose.setContent {
            view = LocalView.current
            FormStep(config = step.config, onNext = {}, savedValues = mapOf("where" to seed))
        }
        compose.waitForIdle()
        return { null }
    }

    // ---- The walks -----------------------------------------------------------------------------

    /** Focuses the location field and returns the input connection the input method would hold. */
    private fun focusTheLocationField(shown: String): InputConnection {
        assertEquals("the field shows the seeded answer", shown, text(location()))
        location().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        assertTrue(focused(location()))
        return assertNotNullConnection(view.onCreateInputConnection(EditorInfo()))
    }

    private fun assertNotNullConnection(ic: InputConnection?): InputConnection {
        assertNotNull("the focused field has an input session", ic)
        return ic!!
    }

    /** Taps the other field: the location field loses focus, then lets the debounce time pass. */
    private fun leaveForTheOtherField() {
        other().performSemanticsAction(SemanticsActions.RequestFocus)
        settle()
    }

    private fun assertLeft(recorder: Recorder, expectedText: String) {
        assertEquals("the location field shows", expectedText, text(location()))
        // One reading of everything the leaving decides, so a failure shows all of it at once.
        val state = { other: Boolean, location: Boolean, queries: List<String>, dropdown: Boolean ->
            "otherFocused=$other locationFocused=$location queries=$queries dropdown=$dropdown"
        }
        assertEquals(
            "the other field keeps the focus the user moved to it, the location field does not take it back, " +
                "and no search runs or opens a dropdown after the user left",
            state(true, false, emptyList(), false),
            state(focused(other()), focused(location()), recorder.queries.toList(), dropdownShown()),
        )
    }

    /** #1147: composing after Google's line, then tapping the other field. */
    private fun composeAfterGooglesLineThenLeave(show: (Any) -> () -> Any?) {
        val recorder = Recorder().also { locationGeocodeTransport = it }
        val stored = show(google)
        val ic = focusTheLocationField(line)
        compose.runOnUiThread { ic.setComposingText("x", 1) }
        compose.waitForIdle()
        assertEquals("the composition shows after the line", "${line}x", text(location()))
        leaveForTheOtherField()
        // The blur committed the composition: the first edit, restarted from the user's own text.
        assertLeft(recorder, "Springfx")
        stored()?.let { assertEquals("Springfx", it) }
    }

    /**
     * The same path when the restart lands on the text the edit left: the composition replaces the whole
     * line with the user's own text, and the commit draws a new field without setting any text.
     */
    private fun composeTheTypedTextOverGooglesLineThenLeave(show: (Any) -> () -> Any?) {
        val recorder = Recorder().also { locationGeocodeTransport = it }
        val stored = show(google)
        val ic = focusTheLocationField(line)
        compose.runOnUiThread {
            ic.setComposingRegion(0, line.length)
            ic.setComposingText("Springf", 1)
        }
        compose.waitForIdle()
        assertEquals("Springf", text(location()))
        leaveForTheOtherField()
        assertLeft(recorder, "Springf")
        stored()?.let { assertEquals("Springf", it) }
    }

    /** Control: a Google answer left without composing. */
    private fun leaveGooglesLineWithoutComposing(show: (Any) -> () -> Any?) {
        val recorder = Recorder().also { locationGeocodeTransport = it }
        val stored = show(google)
        focusTheLocationField(line)
        leaveForTheOtherField()
        assertLeft(recorder, line)
        stored()?.let { assertEquals("the Google answer is kept", line, (it as? Map<*, *>)?.get("formatted_address")) }
    }

    /** Control: a typed answer left while composing. */
    private fun composeAfterTypedTextThenLeave(show: (Any) -> () -> Any?) {
        val recorder = Recorder().also { locationGeocodeTransport = it }
        val stored = show("Springf")
        val ic = focusTheLocationField("Springf")
        compose.runOnUiThread { ic.setComposingText("x", 1) }
        compose.waitForIdle()
        // The composing edit of typed text is an ordinary edit: it searches once the debounce fires,
        // which the leaving below cancels before it does.
        leaveForTheOtherField()
        assertLeft(recorder, "Springfx")
        stored()?.let { assertEquals("Springfx", it) }
    }

    /**
     * Control: the composition after Google's line commits while the user STAYS in the field. The commit
     * draws a new field, which keeps the focus, and the search runs and opens the dropdown, so this
     * harness sees both when they happen.
     */
    private fun composeAfterGooglesLineAndStay(show: (Any) -> () -> Any?) {
        val recorder = Recorder().also { locationGeocodeTransport = it }
        val stored = show(google)
        val ic = focusTheLocationField(line)
        compose.runOnUiThread { ic.setComposingText("x", 1) }
        compose.waitForIdle()
        compose.runOnUiThread { ic.finishComposingText() }
        settle()
        assertEquals("Springfx", text(location()))
        assertTrue("typing on in the field keeps the focus across the new field", focused(location()))
        assertFalse(focused(other()))
        assertEquals("staying in the field searches", listOf("Springfx"), recorder.queries)
        assertTrue("the harness sees the dropdown when it opens", dropdownShown())
        stored()?.let { assertEquals("Springfx", it) }
    }

    // ---- The content block ---------------------------------------------------------------------

    @Test
    fun theBlockLeftWhileComposingAfterGooglesLineKeepsTheFocusWhereTheUserMovedIt() =
        composeAfterGooglesLineThenLeave(::showBlock)

    @Test
    fun theBlockLeftWhileComposingTheTypedTextOverGooglesLineKeepsTheFocusWhereTheUserMovedIt() =
        composeTheTypedTextOverGooglesLineThenLeave(::showBlock)

    @Test
    fun controlTheBlockLeftOnGooglesLineWithoutComposing() = leaveGooglesLineWithoutComposing(::showBlock)

    @Test
    fun controlTheBlockLeftWhileComposingAfterTypedText() = composeAfterTypedTextThenLeave(::showBlock)

    @Test
    fun controlTheBlockKeepsTheFocusWhenTheCompositionCommitsInTheField() = composeAfterGooglesLineAndStay(::showBlock)

    // ---- The form step -------------------------------------------------------------------------

    @Test
    fun theFormStepLeftWhileComposingAfterGooglesLineKeepsTheFocusWhereTheUserMovedIt() =
        composeAfterGooglesLineThenLeave(::showFormStep)

    @Test
    fun theFormStepLeftWhileComposingTheTypedTextOverGooglesLineKeepsTheFocusWhereTheUserMovedIt() =
        composeTheTypedTextOverGooglesLineThenLeave(::showFormStep)

    @Test
    fun controlTheFormStepLeftOnGooglesLineWithoutComposing() = leaveGooglesLineWithoutComposing(::showFormStep)

    @Test
    fun controlTheFormStepLeftWhileComposingAfterTypedText() = composeAfterTypedTextThenLeave(::showFormStep)

    @Test
    fun controlTheFormStepKeepsTheFocusWhenTheCompositionCommitsInTheField() =
        composeAfterGooglesLineAndStay(::showFormStep)
}
