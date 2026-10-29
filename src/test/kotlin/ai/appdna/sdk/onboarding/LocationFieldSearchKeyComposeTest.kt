package ai.appdna.sdk.onboarding

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The keyboard's Search key in a location field does what return does on iOS: the field is left, so
 * the suggestion dropdown closes and no pending search reopens it, and the keyboard goes away. Typed
 * text stays in the field. On each REAL location composable: the form step's field and the
 * `input_location` block.
 *
 * The key is pressed through the field's own IME action. Each walk first shows the dropdown open (the
 * control that this harness can see it), and the keyboard is read from Robolectric's shadow input
 * method manager. All place data is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LocationFieldSearchKeyComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val row = "4000-001 Porto, Portugal"

    private val imm: InputMethodManager
        get() = ApplicationProvider.getApplicationContext<Context>().getSystemService(InputMethodManager::class.java)

    private fun keyboardUp(): Boolean = shadowOf(imm).isSoftInputVisible

    /** Every autocomplete answers one row; the number of requests is counted. */
    private var searches = 0
    private val stub = object : GeocodeTransport {
        override suspend fun post(url: String, headers: Map<String, String>, body: String): GeocodeHttpReply {
            searches++
            val r = JSONObject().put("id", "synthetic-listed-id").put("provider", "mapbox")
                .put("formatted_address", "12 Example Street, $row").put("name", "12 Example Street")
                .put("place_formatted", row)
            return GeocodeHttpReply(200, JSONObject().put("data", JSONObject().put("suggestions", JSONArray().put(r))).toString())
        }
    }

    @After
    fun reset() {
        locationGeocodeTransport = LiveGeocodeTransport
    }

    private fun field(): SemanticsNodeInteraction = compose.onNode(hasSetTextAction())

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun dropdownOpen(): Boolean = compose.onAllNodes(hasText(row), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun fieldText(): String =
        field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text ?: ""

    /** Types, opens the dropdown, presses Search, and reads what is left. */
    private fun walk() {
        field().performSemanticsAction(SemanticsActions.RequestFocus)
        settle()
        assertTrue("control: focusing the field shows the keyboard, and the harness sees it", keyboardUp())
        field().performTextInput("12 Ex")
        settle()
        assertTrue("control: typing opens the dropdown, and the harness sees it", dropdownOpen())
        assertEquals("the field's key is Search", ImeAction.Search, field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.ImeAction))

        field().performImeAction()
        settle()

        assertFalse("Search closes the dropdown", dropdownOpen())
        field().assertIsNotFocused()
        assertFalse("Search hides the keyboard", keyboardUp())
        assertEquals("the typed text stays", "12 Ex", fieldText())

        // A pending search does not reopen it.
        val before = searches
        settle(); settle()
        assertFalse("nothing reopens the dropdown", dropdownOpen())
        assertEquals("no search runs after leaving", before, searches)
    }

    @Test
    fun theFormStepsSearchKeyClosesTheDropdownAndTheKeyboard() {
        locationGeocodeTransport = stub
        val step = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s_form", "type" to "form",
                "config" to mapOf("fields" to listOf(mapOf("id" to "where", "type" to "location", "label" to "Where"))),
            ),
        ) ?: error("form step did not parse")
        compose.setContent { FormStep(config = step.config, onNext = {}, savedValues = null) }
        compose.waitForIdle()
        walk()
    }

    @Test
    fun theBlocksSearchKeyClosesTheDropdownAndTheKeyboard() {
        locationGeocodeTransport = stub
        val blocks = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s_block", "type" to "content",
                "config" to mapOf(
                    "content_blocks" to listOf(
                        mapOf(
                            "id" to "b_loc", "type" to "input_location", "field_id" to "where",
                            "field_config" to mapOf("location_type" to "address", "location_min_chars" to 3),
                        ),
                    ),
                ),
            ),
        )!!.config.content_blocks!!.toList()
        val inputValues = mutableStateMapOf<String, Any>()
        compose.setContent { ContentBlockRendererView(blocks = blocks, onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues) }
        compose.waitForIdle()
        walk()
        assertEquals("the typed text is the stored answer", "12 Ex", LocationAnswer.fieldText(inputValues["where"]))
    }
}
