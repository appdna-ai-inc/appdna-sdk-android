package ai.appdna.sdk.onboarding

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The soft keyboard across the location field's re-draws, on each REAL location composable.
 *
 * The field is drawn afresh after each text set its holder makes (a resolve, a clear), and the new field
 * takes the focus the old one had. A field that gains focus starts an input session, and a new session
 * shows the keyboard, so a keyboard the user had hidden would reopen. Each walk hides the keyboard the
 * way Back does on a device (the system hides it; the field keeps its focus), taps, and reads the
 * keyboard's state back; the control walks leave it up and expect it still up.
 *
 * The keyboard is read from Robolectric's shadow input method manager, which records every show and hide
 * the field asks for. Robolectric has no IME window, so the window insets cannot say whether the keyboard
 * is up: the field's reading of that ([locationImeShown]) answers from the same shadow here.
 *
 * All place data is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LocationFieldKeyboardComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val line = "12 Example Street, 4000-001 Porto, Portugal"
    private val resolved: Map<String, Any> = mapOf(
        "formatted_address" to line, "address" to line, "city" to "Porto", "country" to "Portugal",
        "country_code" to "PT", "latitude" to 41.15, "longitude" to -8.61, "mapbox_id" to "synthetic-resolved-id",
    )

    private val imm: InputMethodManager
        get() = ApplicationProvider.getApplicationContext<Context>().getSystemService(InputMethodManager::class.java)

    private fun keyboardUp(): Boolean = shadowOf(imm).isSoftInputVisible

    private val liveImeShown = locationImeShown

    @Before
    fun readTheKeyboardFromTheShadow() {
        locationImeShown = { keyboardUp() }
    }

    @After
    fun reset() {
        locationGeocodeTransport = LiveGeocodeTransport
        locationImeShown = liveImeShown
    }

    /** Answers autocomplete with one row, and resolve with [resolved]. */
    private inner class Stub : GeocodeTransport {
        override suspend fun post(url: String, headers: Map<String, String>, body: String): GeocodeHttpReply =
            if (url.endsWith("/resolve")) {
                GeocodeHttpReply(
                    200,
                    JSONObject().put("data", JSONObject().put("resolved", true).put("suggestion", JSONObject(resolved))).toString(),
                )
            } else {
                val row = JSONObject().put("id", "synthetic-listed-id").put("provider", "mapbox")
                    .put("formatted_address", line).put("name", "12 Example Street")
                    .put("place_formatted", "4000-001 Porto, Portugal")
                GeocodeHttpReply(200, JSONObject().put("data", JSONObject().put("suggestions", JSONArray().put(row))).toString())
            }
    }

    private lateinit var view: View

    private fun field(): SemanticsNodeInteraction = compose.onNode(hasSetTextAction())

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** Focuses the field as a tap does: the keyboard comes up (the control that this harness sees it). */
    private fun focusTheField() {
        field().performSemanticsAction(SemanticsActions.RequestFocus)
        settle()
        assertTrue("the control: focusing the field shows the keyboard, and the harness sees it", keyboardUp())
    }

    /** What Back does on a device: the system hides the keyboard, and the field keeps its focus. */
    private fun pressBackToHideTheKeyboard() {
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        settle()
        assertFalse("the keyboard is hidden", keyboardUp())
        field().assertIsFocused()
    }

    /** Types, lets the debounce fire, taps the one row and lets the resolve land. */
    private fun typeAndTapTheRow() {
        field().performTextInput("12 Ex")
        settle()
        compose.onNodeWithText("4000-001 Porto, Portugal").performClick()
        settle()
        assertTrue("the resolve landed", compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().any {
            it.config.getOrNull(SemanticsProperties.EditableText)?.text == line
        })
    }

    private fun blockContent(inputValues: MutableMap<String, Any>) {
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
        compose.setContent {
            view = LocalView.current
            ContentBlockRendererView(blocks = blocks, onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
        compose.waitForIdle()
    }

    private fun formContent() {
        val step = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s_form", "type" to "form",
                "config" to mapOf(
                    "fields" to listOf(mapOf("id" to "where", "type" to "location", "label" to "Where", "required" to true)),
                ),
            ),
        ) ?: error("form step did not parse")
        compose.setContent {
            view = LocalView.current
            FormStep(config = step.config, onNext = {}, savedValues = null)
        }
        compose.waitForIdle()
    }

    // ---- The content block ---------------------------------------------------------------------

    @Test
    fun theBlocksKeyboardStaysHiddenAfterAResolve() {
        locationGeocodeTransport = Stub()
        val inputValues = mutableStateMapOf<String, Any>()
        blockContent(inputValues)
        focusTheField()
        field().performTextInput("12 Ex")
        settle()
        pressBackToHideTheKeyboard()
        compose.onNodeWithText("4000-001 Porto, Portugal").performClick()
        settle()
        assertEquals("the resolve landed", line, (inputValues["where"] as? Map<*, *>)?.get("formatted_address"))
        field().assertIsFocused()
        assertFalse("the keyboard the user hid stays hidden after the resolve", keyboardUp())
    }

    @Test
    fun theBlocksKeyboardStaysUpAfterAResolve() {
        locationGeocodeTransport = Stub()
        val inputValues = mutableStateMapOf<String, Any>()
        blockContent(inputValues)
        focusTheField()
        typeAndTapTheRow()
        assertEquals(line, (inputValues["where"] as? Map<*, *>)?.get("formatted_address"))
        field().assertIsFocused()
        assertTrue("a keyboard that was up stays up after the resolve", keyboardUp())
    }

    // ---- The form step -------------------------------------------------------------------------

    @Test
    fun theFormStepsKeyboardStaysHiddenAfterAResolveAndAClear() {
        locationGeocodeTransport = Stub()
        formContent()
        focusTheField()
        field().performTextInput("12 Ex")
        settle()
        pressBackToHideTheKeyboard()
        compose.onNodeWithText("4000-001 Porto, Portugal").performClick()
        settle()
        field().assertIsFocused()
        assertFalse("the keyboard the user hid stays hidden after the resolve", keyboardUp())

        compose.onNodeWithContentDescription("Clear location").performClick()
        settle()
        assertEquals("the clear emptied the field", "", field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        field().assertIsFocused()
        assertFalse("the keyboard the user hid stays hidden after the clear", keyboardUp())
    }

    @Test
    fun theFormStepsKeyboardStaysUpAfterAResolveAndAClear() {
        locationGeocodeTransport = Stub()
        formContent()
        focusTheField()
        typeAndTapTheRow()
        field().assertIsFocused()
        assertTrue("a keyboard that was up stays up after the resolve", keyboardUp())

        compose.onNodeWithContentDescription("Clear location").performClick()
        settle()
        assertEquals("", field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        field().assertIsFocused()
        assertTrue("a keyboard that was up stays up after the clear", keyboardUp())
    }

    /** The clear on its own, with the keyboard hidden after the resolve rather than before it. */
    @Test
    fun theFormStepsKeyboardHiddenAfterTheResolveStaysHiddenAfterTheClear() {
        locationGeocodeTransport = Stub()
        formContent()
        focusTheField()
        typeAndTapTheRow()
        assertTrue(keyboardUp())
        pressBackToHideTheKeyboard()
        compose.onNodeWithContentDescription("Clear location").performClick()
        settle()
        field().assertIsFocused()
        assertFalse("the keyboard the user hid stays hidden after the clear", keyboardUp())
    }
}
