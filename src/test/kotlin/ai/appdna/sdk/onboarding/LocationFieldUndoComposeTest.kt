package ai.appdna.sdk.onboarding

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A hardware keyboard's undo, driven through each REAL location composable: after the first edit of a
 * Google answer's line restarts the field from the user's own text, no undo brings the line back.
 *
 * A Compose text field records an undo history of its own (every recomposition may snapshot its value,
 * and every key event forces the next snapshot), and Ctrl+Z replays it through `onValueChange` as if the
 * user had typed it. Both composables key their text field on the holder's text sets, so the field drawn
 * after the restart starts with an empty history. The user's own edits after the restart still undo.
 *
 * Each walk: the field holds a restored Google answer; the caret moves (so the line is snapshotted, as it
 * is for any user who has touched the field); Backspace restarts the field from the typed text; another
 * Backspace is the user's own edit; Ctrl+Z undoes that edit (the control: this harness reaches undo);
 * further Ctrl+Z does nothing. It also checks what the re-keyed field must keep: focus, the caret at the
 * end, the search after the restart (a field that lost focus would have ended it) and typing on.
 *
 * All place data is synthetic.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LocationFieldUndoComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val line = "Springfield, IL, USA"
    private val google: Map<String, Any> = mapOf(
        "formatted_address" to line, "address" to line, "city" to "Springfield", "state" to "Illinois",
        "state_code" to "IL", "country" to "United States", "country_code" to "US", "latitude" to 39.78,
        "longitude" to -89.65, "raw_query" to "Springf", "place_id" to "ChIJ-synthetic-springfield",
    )

    /** Answers every autocomplete with an empty list, and records each query. */
    private class Recorder : GeocodeTransport {
        val queries = mutableListOf<String>()
        override suspend fun post(url: String, headers: Map<String, String>, body: String): GeocodeHttpReply {
            queries += JSONObject(body).optString("query")
            return GeocodeHttpReply(200, """{"data":{"suggestions":[]}}""")
        }
    }

    @After
    fun resetTransport() {
        locationGeocodeTransport = LiveGeocodeTransport
    }

    private fun field(): SemanticsNodeInteraction = compose.onNode(hasSetTextAction())

    private fun fieldText(): String =
        field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun caret(): TextRange =
        field().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]

    private fun press(key: Key) {
        field().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /**
     * Ctrl+Z from a hardware keyboard. On a device the key map gives Ctrl+Z no character, so the field
     * reads it as the undo command; Robolectric's key map types a "z" instead (the control below caught
     * that), so the event carries the device's answer itself.
     */
    private fun undo() {
        for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
            val native = object : android.view.KeyEvent(
                0L, 0L, action, KEYCODE_Z, 0, META_CTRL_ON or META_CTRL_LEFT_ON,
            ) {
                override fun getUnicodeChar(): Int = 0
                override fun getUnicodeChar(metaState: Int): Int = 0
            }
            field().performKeyPress(androidx.compose.ui.input.key.KeyEvent(native))
        }
        compose.waitForIdle()
    }

    /**
     * The walk both surfaces share. [stored] reads the field's answer where the surface keeps one
     * (null where it is visible only on Continue).
     */
    private fun walk(recorder: Recorder, stored: (() -> Any?)?) {
        assertEquals("the field shows the restored Google answer", line, fieldText())
        field().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        // The caret moves left and back: the field snapshots the line in its own history.
        press(Key.DirectionLeft)
        press(Key.DirectionRight)
        assertEquals(line, fieldText())

        // The first edit restarts from the text the user typed.
        press(Key.Backspace)
        assertEquals("the first edit restarts from the typed text", "Springf", fieldText())
        stored?.let { assertEquals("Springf", it()) }
        field().assertIsFocused()
        assertEquals("the caret is at the end of the restarted text", TextRange(7), caret())
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals("the restarted text is searched: the field kept its focus", listOf("Springf"), recorder.queries)

        // The user's own edit, and its undo: the harness reaches the field's undo history.
        press(Key.Backspace)
        assertEquals("Spring", fieldText())
        undo()
        assertEquals("the control: Ctrl+Z undoes the user's own edit", "Springf", fieldText())
        stored?.let { assertEquals("Springf", it()) }

        // Nothing before the restart is in the history.
        undo()
        assertEquals("Ctrl+Z cannot bring Google's line back", "Springf", fieldText())
        stored?.let { assertEquals("Springf", it()) }
        undo()
        assertEquals("Springf", fieldText())
        stored?.let { assertEquals("Springf", it()) }
        field().assertIsFocused()

        // Typing on goes in at the caret.
        field().performTextInput("i")
        compose.waitForIdle()
        assertEquals("Springfi", fieldText())
        stored?.let { assertEquals("Springfi", it()) }
    }

    @Test
    fun theBlocksUndoCannotBringGooglesLineBack() {
        val recorder = Recorder()
        locationGeocodeTransport = recorder
        val inputValues = mutableStateMapOf<String, Any>("where" to google)
        val blocks = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s_block", "type" to "content",
                "config" to mapOf(
                    "content_blocks" to listOf(
                        mapOf(
                            "id" to "b_loc", "type" to "input_location", "field_id" to "where",
                            "field_config" to mapOf("location_type" to "city", "location_min_chars" to 3),
                        ),
                    ),
                ),
            ),
        )!!.config.content_blocks!!.toList()
        compose.setContent {
            ContentBlockRendererView(blocks = blocks, onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
        compose.waitForIdle()
        walk(recorder) { inputValues["where"] }
    }

    @Test
    fun theFormStepsUndoCannotBringGooglesLineBack() {
        val recorder = Recorder()
        locationGeocodeTransport = recorder
        val step = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s_form", "type" to "form",
                "config" to mapOf(
                    "fields" to listOf(mapOf("id" to "where", "type" to "location", "label" to "Where", "required" to true)),
                ),
            ),
        ) ?: error("form step did not parse")
        var advanced: Map<String, Any>? = null
        compose.setContent { FormStep(config = step.config, onNext = { advanced = it }, savedValues = mapOf("where" to google)) }
        compose.waitForIdle()
        walk(recorder, stored = null)
        // The form step's answer is what Continue submits.
        compose.onAllNodes(hasClickAction() and hasText("Continue", ignoreCase = true))[0].performClick()
        compose.waitForIdle()
        assertEquals("Springfi", advanced?.get("where"))
    }
}
