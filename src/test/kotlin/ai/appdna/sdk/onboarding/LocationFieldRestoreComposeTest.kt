package ai.appdna.sdk.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
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

    private fun formStep(required: Boolean = true): OnboardingStep = OnboardingConfigParser.parseStepForTest(
        mapOf(
            "id" to "s_form", "type" to "form",
            "config" to mapOf(
                "fields" to listOf(mapOf("id" to "where", "type" to "location", "label" to "Where", "required" to required)),
            ),
        ),
    ) ?: error("form step did not parse")

    /** A transport whose resolve is HELD until [release] completes; autocomplete answers at once. */
    private inner class HeldResolve(private val reply: GeocodeHttpReply = resolvedReply) : GeocodeTransport {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val autocomplete = Stub(reply)
        var resolveCalls = 0
        override suspend fun post(url: String, headers: Map<String, String>, body: String): GeocodeHttpReply {
            if (url.endsWith("/resolve")) {
                resolveCalls += 1
                release.await()
                return reply
            }
            return autocomplete.post(url, headers, body)
        }
    }

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

    // ---- What the block stores while resolving, and after a clear ---------------------------------

    /** A resolve held in flight: nothing is stored for the field, so a required field keeps Continue gated. */
    @Test
    fun theBlockStoresNothingWhileAResolveIsInFlight() {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val held = object : GeocodeTransport {
            override suspend fun post(url: String, headers: Map<String, String>, body: String): GeocodeHttpReply {
                if (url.endsWith("/resolve")) { release.await(); return resolvedReply }
                return Stub(resolvedReply).post(url, headers, body)
            }
        }
        locationGeocodeTransport = held
        val inputValues = mutableStateMapOf<String, Any>()
        compose.setContent {
            ContentBlockRendererView(blocks = locationBlock(), onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
        compose.waitForIdle()
        typeAndTapTheRow("12 Exa")
        assertFalse("nothing stored while the resolve is in flight", inputValues.containsKey("where"))
        release.complete(Unit)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** Emptying the field removes its answer (an empty field is unanswered, not `""`). */
    @Test
    fun clearingTheBlockRemovesItsAnswer() {
        val inputValues = mutableStateMapOf<String, Any>()
        compose.setContent {
            ContentBlockRendererView(blocks = locationBlock(), onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
        compose.waitForIdle()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Po")
        compose.waitForIdle()
        assertEquals("Po", inputValues["where"])
        compose.onAllNodes(hasSetTextAction())[0].performTextClearance()
        compose.waitForIdle()
        assertFalse("a cleared field has no answer", inputValues.containsKey("where"))
    }

    // ---- What the form step stores while resolving, and after a clear ------------------------------

    /** The form step's answer is the step's `values`: while a resolve is in flight it holds nothing, so Continue
     *  on this required field does not advance; once the reply lands, Continue submits the resolved answer. */
    @Test
    fun theFormStepHoldsContinueWhileAResolveIsInFlight() {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        locationGeocodeTransport = object : GeocodeTransport {
            override suspend fun post(url: String, headers: Map<String, String>, body: String): GeocodeHttpReply {
                if (url.endsWith("/resolve")) { release.await(); return resolvedReply }
                return Stub(resolvedReply).post(url, headers, body)
            }
        }
        var advanced: Map<String, Any>? = null
        compose.setContent { FormStep(config = formStep().config, onNext = { advanced = it }, savedValues = null) }
        compose.waitForIdle()
        typeAndTapTheRow("12 Exa")
        compose.onAllNodes(hasClickAction() and hasText("Continue", ignoreCase = true))[0].performClick()
        compose.waitForIdle()
        assertNull("Continue advanced with nothing stored while the resolve was in flight", advanced)
        release.complete(Unit)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onAllNodes(hasClickAction() and hasText("Continue", ignoreCase = true))[0].performClick()
        compose.waitForIdle()
        @Suppress("UNCHECKED_CAST")
        val answer = advanced?.get("where") as? Map<String, Any> ?: error("Continue submitted ${advanced}")
        assertEquals("synthetic-resolved-id", answer["mapbox_id"])
    }

    /** Emptying the form-step field removes its answer, so Continue on the required field does not advance. */
    @Test
    fun clearingTheFormStepFieldRemovesItsAnswer() {
        var advanced: Map<String, Any>? = null
        compose.setContent { FormStep(config = formStep().config, onNext = { advanced = it }, savedValues = null) }
        compose.waitForIdle()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Po")
        compose.waitForIdle()
        compose.onAllNodes(hasSetTextAction())[0].performTextClearance()
        compose.waitForIdle()
        compose.onAllNodes(hasClickAction() and hasText("Continue", ignoreCase = true))[0].performClick()
        compose.waitForIdle()
        assertNull("Continue advanced on a cleared required field", advanced)
    }

    // ---- The step advances while a resolve is in flight --------------------------------------------

    /**
     * An OPTIONAL form-step Location field, a resolve held in flight, Continue: the step advances (an
     * optional field does not gate it) and submits the text the user typed, as for any failed resolve.
     * The late reply then changes nothing. The field's own dispose runs a frame AFTER Continue built its
     * payload, so this is the step settling its field before it submits.
     * Red control: Continue without the settle → the payload carries "" for the field.
     */
    @Test
    fun continueOnAnOptionalFieldWhileResolvingSubmitsTheTypedText() {
        val held = HeldResolve()
        locationGeocodeTransport = held
        var advanced: Map<String, Any>? = null
        compose.setContent { FormStep(config = formStep(required = false).config, onNext = { advanced = it }, savedValues = null) }
        compose.waitForIdle()
        typeAndTapTheRow("12 Exa")
        assertEquals("precondition: the tap's resolve is held in flight", 1, held.resolveCalls)
        assertNull("precondition: nothing advanced yet", advanced)
        compose.onAllNodes(hasClickAction() and hasText("Continue", ignoreCase = true))[0].performClick()
        compose.waitForIdle()
        val submitted = advanced ?: error("Continue did not advance an optional field")
        assertEquals("the submitted answer is the typed text", "12 Exa", submitted["where"])
        // The late reply lands: nothing it carries reaches the field.
        held.release.complete(Unit)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue("the field still shows the typed text", fieldShows("12 Exa"))
        assertFalse("the resolved line never appears", fieldShows(line))
    }

    /**
     * The same on a block-based step, rendered through the real step view: an optional `input_location`
     * block with its resolve held in flight, then the step's own Continue button (action `next`). The
     * step hands on the typed text. Red control: the step's advance without the settle → the payload
     * has no answer for the field.
     */
    @Test
    fun continueOnABlockStepWhileResolvingSubmitsTheTypedText() {
        val held = HeldResolve()
        locationGeocodeTransport = held
        val step = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s_block_continue", "type" to "content",
                "config" to mapOf(
                    "content_blocks" to listOf(
                        mapOf(
                            "id" to "b_loc", "type" to "input_location", "field_id" to "where",
                            "field_config" to mapOf("location_type" to "address", "location_min_chars" to 3),
                        ),
                        mapOf("id" to "b_go", "type" to "button", "text" to "Continue", "action" to "next"),
                    ),
                ),
            ),
        )!!
        var advances = 0
        var advanced: Map<String, Any>? = null
        compose.setContent {
            OnboardingStepView(
                step = step, effectiveConfig = step.config,
                onNext = { advances += 1; advanced = it }, onSkip = {},
            )
        }
        compose.waitForIdle()
        typeAndTapTheRow("12 Exa")
        assertEquals("precondition: the tap's resolve is held in flight", 1, held.resolveCalls)
        compose.onAllNodes(hasClickAction() and hasText("Continue", ignoreCase = true))[0].performClick()
        compose.waitForIdle()
        assertEquals("Continue advanced the step once", 1, advances)
        // A step that carried no answers hands on null, so a missing field answer reads as null here.
        assertEquals("the submitted answer is the typed text", "12 Exa", advanced?.get("where"))
        held.release.complete(Unit)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    // ---- Leaving the field ends its search -------------------------------------------------------

    /**
     * Types, then leaves the field before the debounce fires (focus cleared), then lets the debounce
     * time pass: no autocomplete request and no dropdown. Then, as the NEGATIVE CONTROL in the same
     * composition, types again without leaving: the request is made and the row is shown — so the
     * harness can see a dropdown when one opens.
     */
    private fun leaveTheFieldWithASearchPending(stub: Stub, focus: () -> FocusManager) {
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("12 Exa")
        compose.runOnUiThread { focus().clearFocus() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals("no autocomplete request after the user left the field", 0, stub.autocompleteBodies.size)
        assertFalse("no dropdown under a field the user has left", rowShown())

        compose.onAllNodes(hasSetTextAction())[0].performTextInput("m")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals("control: staying in the field searches", 1, stub.autocompleteBodies.size)
        assertTrue("control: the harness sees the dropdown when it opens", rowShown())
    }

    /** The form step. Red control: no `editingEnded` on focus loss → the search fires and the row shows. */
    @Test
    fun leavingTheFormStepFieldWithASearchPendingOpensNoDropdown() {
        val stub = Stub(resolvedReply)
        locationGeocodeTransport = stub
        lateinit var focusManager: FocusManager
        compose.setContent {
            focusManager = LocalFocusManager.current
            FormStep(config = formStep().config, onNext = {}, savedValues = null)
        }
        compose.waitForIdle()
        leaveTheFieldWithASearchPending(stub) { focusManager }
    }

    /** The content block. Red control: no `editingEnded` on focus loss → the search fires and the row shows. */
    @Test
    fun leavingTheBlockWithASearchPendingOpensNoDropdown() {
        val stub = Stub(resolvedReply)
        locationGeocodeTransport = stub
        lateinit var focusManager: FocusManager
        val inputValues = mutableStateMapOf<String, Any>()
        compose.setContent {
            focusManager = LocalFocusManager.current
            ContentBlockRendererView(blocks = locationBlock(), onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
        compose.waitForIdle()
        leaveTheFieldWithASearchPending(stub) { focusManager }
    }
}
