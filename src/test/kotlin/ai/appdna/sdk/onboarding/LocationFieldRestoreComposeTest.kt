package ai.appdna.sdk.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two location composables, rendered: what the field SHOWS for a held answer when its step is
 * composed again (back navigation, or a configuration change such as a rotation, where the ViewModel
 * keeps the answers and the composition is rebuilt), and that a typed edit reaches the holder.
 *
 * The holder's own behaviour is pinned by `LocationSuggestionSearchTest` and the shared fixtures; this
 * is the binding the fixtures cannot see:
 *  - the form step fills its `values` in a `LaunchedEffect` AFTER the field's first composition, so a
 *    field that read its text once showed "" for every held answer;
 *  - the content block read only a String answer, so a held selection (a Map) showed "".
 *
 * Each case is re-composed from scratch, which is what both back navigation and a rotation do to these
 * composables. The tap cases drive a real row tap through each view, with only the socket stubbed
 * ([locationGeocodeTransport]). All place data is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LocationFieldRestoreComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val line = "12 Example Street, 4000-001 Porto, Portugal"
    private val resolved: Map<String, Any> = mapOf(
        "formatted_address" to line, "address" to line, "city" to "Porto", "country" to "Portugal",
        "country_code" to "PT", "latitude" to 41.15, "longitude" to -8.61, "raw_query" to "12 Example",
        "mapbox_id" to "synthetic-resolved-id",
    )
    private val region: Map<String, Any> = mapOf(
        "formatted_address" to "California, United States", "address" to "California, United States",
        "city" to "California", "state" to "California", "state_code" to "CA", "country" to "United States",
        "mapbox_id" to "synthetic-region-id",
    )

    private fun formStep(): OnboardingStep = OnboardingConfigParser.parseStepForTest(
        mapOf(
            "id" to "s_form", "type" to "form",
            "config" to mapOf(
                "fields" to listOf(mapOf("id" to "where", "type" to "location", "label" to "Where", "required" to true)),
            ),
        ),
    ) ?: error("form step did not parse")

    private fun locationBlock(): List<ContentBlock> = OnboardingConfigParser.parseStepForTest(
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

    @After
    fun resetTransport() {
        locationGeocodeTransport = LiveGeocodeTransport
    }

    /** Answers autocomplete with one display-only row, and resolve with [resolveReply]; records both. */
    private inner class Stub(private val resolveReply: GeocodeHttpReply) : GeocodeTransport {
        val autocompleteBodies = mutableListOf<JSONObject>()
        val resolveBodies = mutableListOf<JSONObject>()
        override suspend fun post(url: String, headers: Map<String, String>, body: String): GeocodeHttpReply {
            val json = JSONObject(body)
            return if (url.endsWith("/resolve")) {
                resolveBodies += json
                resolveReply
            } else {
                autocompleteBodies += json
                val row = JSONObject().put("id", "synthetic-listed-id").put("provider", "mapbox")
                    .put("formatted_address", line).put("name", "12 Example Street")
                    .put("place_formatted", "4000-001 Porto, Portugal")
                GeocodeHttpReply(200, JSONObject().put("data", JSONObject().put("suggestions", JSONArray().put(row))).toString())
            }
        }
    }

    private val resolvedReply = GeocodeHttpReply(
        200,
        JSONObject().put(
            "data",
            JSONObject().put("resolved", true).put("suggestion", JSONObject(resolved - "raw_query" - "address")),
        ).toString(),
    )

    /** Types, lets the debounce fire, and taps the one row by its subtitle. */
    private fun typeAndTapTheRow(typed: String) {
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(typed)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("4000-001 Porto, Portugal").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun rowShown() =
        compose.onAllNodes(hasText("4000-001 Porto, Portugal")).fetchSemanticsNodes().isNotEmpty()

    private fun fieldShows(text: String) =
        compose.onAllNodes(hasSetTextAction() and hasText(text)).fetchSemanticsNodes().isNotEmpty()

    // ---- The form step -------------------------------------------------------------------------

    @Test
    fun theFormStepShowsAHeldResolvedAnswerWhenComposedAgain() {
        var show by mutableStateOf(true)
        compose.setContent {
            if (show) FormStep(config = formStep().config, onNext = {}, savedValues = mapOf("where" to resolved))
        }
        compose.waitForIdle()
        assertTrue("form step: the field shows the resolved line", fieldShows(line))
        // Composed again (navigating back to it, or a rotation): the same answer, the same text.
        show = false
        compose.waitForIdle()
        show = true
        compose.waitForIdle()
        assertTrue("form step re-composed: the field shows the resolved line", fieldShows(line))
    }

    @Test
    fun theFormStepShowsAHeldRegionAnswer() {
        compose.setContent { FormStep(config = formStep().config, onNext = {}, savedValues = mapOf("where" to region)) }
        compose.waitForIdle()
        assertTrue(fieldShows("California, United States"))
    }

    @Test
    fun theFormStepShowsAHeldTypedAnswer() {
        compose.setContent { FormStep(config = formStep().config, onNext = {}, savedValues = mapOf("where" to "Springfield")) }
        compose.waitForIdle()
        assertTrue(fieldShows("Springfield"))
    }

    /** NEGATIVE CONTROL for the three above: with no held answer the field shows none of those lines. */
    @Test
    fun theFormStepWithNoHeldAnswerShowsNothing() {
        compose.setContent { FormStep(config = formStep().config, onNext = {}, savedValues = null) }
        compose.waitForIdle()
        assertTrue("the harness can see the field", compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty())
        assertTrue(!fieldShows(line) && !fieldShows("California, United States") && !fieldShows("Springfield"))
    }

    // ---- The content block ---------------------------------------------------------------------

    @Test
    fun theBlockShowsAHeldResolvedAnswerWhenComposedAgain() {
        val inputValues = mutableStateMapOf<String, Any>("where" to resolved)
        var show by mutableStateOf(true)
        compose.setContent {
            if (show) ContentBlockRendererView(blocks = locationBlock(), onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
        compose.waitForIdle()
        assertTrue("block: the field shows the resolved line", fieldShows(line))
        show = false
        compose.waitForIdle()
        show = true
        compose.waitForIdle()
        assertTrue("block re-composed: the field shows the resolved line", fieldShows(line))
        assertEquals("a restore writes nothing", resolved, inputValues["where"])
    }

    @Test
    fun theBlockShowsAHeldTypedAnswer() {
        val inputValues = mutableStateMapOf<String, Any>("where" to "Springfield")
        compose.setContent {
            ContentBlockRendererView(blocks = locationBlock(), onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
        compose.waitForIdle()
        assertTrue(fieldShows("Springfield"))
    }

    /** An answer that arrives after the first composition (a host patch) is restored while the field is empty. */
    @Test
    fun theBlockRestoresAnAnswerSeededAfterItsFirstComposition() {
        val inputValues = mutableStateMapOf<String, Any>()
        compose.setContent {
            ContentBlockRendererView(blocks = locationBlock(), onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
        compose.waitForIdle()
        assertTrue(!fieldShows("California, United States"))
        inputValues["where"] = region
        compose.waitForIdle()
        assertTrue(fieldShows("California, United States"))
    }

    /** A typed edit goes through the holder into the step's answers (below Min characters: no request). */
    @Test
    fun aTypedEditIsStoredThroughTheHolder() {
        val inputValues = mutableStateMapOf<String, Any>()
        compose.setContent {
            ContentBlockRendererView(blocks = locationBlock(), onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
        compose.waitForIdle()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Po")
        compose.waitForIdle()
        assertEquals("Po", inputValues["where"])
        assertTrue(fieldShows("Po"))
    }

    // ---- A real tap through each view ----------------------------------------------------------

    @Test
    fun aTappedRowInTheBlockIsResolvedStoredAndShown() {
        val stub = Stub(resolvedReply)
        locationGeocodeTransport = stub
        val inputValues = mutableStateMapOf<String, Any>()
        compose.setContent {
            ContentBlockRendererView(blocks = locationBlock(), onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
        compose.waitForIdle()
        typeAndTapTheRow("12 Exa")
        assertEquals("one search", 1, stub.autocompleteBodies.size)
        val body = stub.resolveBodies.single()
        assertEquals("the listing's query", "12 Exa", body.getString("query"))
        assertEquals(line, body.getString("text"))
        assertEquals("synthetic-listed-id", body.getString("id"))
        @Suppress("UNCHECKED_CAST")
        val stored = inputValues["where"] as? Map<String, Any> ?: error("no selection stored: ${inputValues["where"]}")
        assertEquals("synthetic-resolved-id", stored["mapbox_id"])
        assertEquals("12 Exa", stored["raw_query"])
        assertNull("the suggestion's own id is not stored", stored["id"])
        assertTrue("the field shows the resolved line", fieldShows(line))
        assertFalse("the dropdown closed", rowShown())
    }

    @Test
    fun aTappedRowInTheFormStepIsResolvedStoredAndShown() {
        val stub = Stub(resolvedReply)
        locationGeocodeTransport = stub
        var advanced: Map<String, Any>? = null
        compose.setContent { FormStep(config = formStep().config, onNext = { advanced = it }, savedValues = null) }
        compose.waitForIdle()
        typeAndTapTheRow("12 Exa")
        assertEquals("12 Exa", stub.resolveBodies.single().getString("query"))
        assertTrue("the field shows the resolved line", fieldShows(line))
        assertFalse("the dropdown closed", rowShown())
        // The stored answer is what Continue submits.
        compose.onAllNodes(hasClickAction() and hasText("Continue", ignoreCase = true)).fetchSemanticsNodes()
            .takeIf { it.isNotEmpty() }
            ?.let {
                compose.onAllNodes(hasClickAction() and hasText("Continue", ignoreCase = true))[0].performClick()
                compose.waitForIdle()
                @Suppress("UNCHECKED_CAST")
                val answer = advanced?.get("where") as? Map<String, Any> ?: error("Continue submitted ${advanced}")
                assertEquals("synthetic-resolved-id", answer["mapbox_id"])
            } ?: error("the form step drew no Continue button")
    }

    @Test
    fun aRefusedResolveStoresTheTypedTextInTheBlock() {
        locationGeocodeTransport = Stub(
            GeocodeHttpReply(200, """{"data":{"resolved":false,"reason":"permanent_not_enabled"}}"""),
        )
        val inputValues = mutableStateMapOf<String, Any>()
        compose.setContent {
            ContentBlockRendererView(blocks = locationBlock(), onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
        compose.waitForIdle()
        typeAndTapTheRow("12 Exa")
        assertEquals("12 Exa", inputValues["where"])
        assertTrue(fieldShows("12 Exa"))
        assertFalse(rowShown())
    }
}
