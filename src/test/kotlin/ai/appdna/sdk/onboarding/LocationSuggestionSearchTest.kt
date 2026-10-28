package ai.appdna.sdk.onboarding

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ONE location state holder both Android surfaces bind to ([LocationSuggestionSearch]), driven
 * through [LocationSearchRig]: the real holder, the real settings resolver, the real suggestion decoder,
 * the real resolve-body builder and the real reply parser; only the socket and the clock are replaced. A
 * debounced search stays PENDING until the test advances the clock, so "the tap cancelled it" cannot
 * pass because CI was slow.
 *
 * RED CONTROLS (each run once against a mutated holder, then restored):
 *   - the `textChanged` value check removed (every echo processed)  → the echo tests fail;
 *   - the tap's cancel of the pending search removed                → the tap-ends-the-search test fails;
 *   - an echo expectation that never resets / only a match consumes → the retype tests fail;
 *   - the field's text sent as `query`                              → the body test fails.
 *
 * All place data is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationSuggestionSearchTest {

    companion object {
        private fun b64(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray())

        /** An interpolated address's id: base64 of `urn:mbxadr-itp:` + the request that produced it. */
        val listedAddressId = "dXJuOm1ieGFkci1pdHA6" + b64("synthetic-listing")
        val resolvedAddressId = "dXJuOm1ieGFkci1pdHA6" + b64("synthetic-resolve")
        val avenueId = b64("urn:mbxadr:synthetic-avenue")
        val portoId = b64("urn:mbxplc:synthetic-porto")
        const val LINE = "12 Example Street, 4000-001 Porto, Portugal"

        fun addressSuggestion() = JSONObject()
            .put("id", listedAddressId).put("provider", "mapbox").put("formatted_address", LINE)
            .put("name", "12 Example Street").put("place_formatted", "4000-001 Porto, Portugal")

        fun avenueSuggestion() = JSONObject()
            .put("id", avenueId).put("provider", "mapbox")
            .put("formatted_address", "12 Example Avenue, 4100-002 Porto, Portugal")
            .put("name", "12 Example Avenue").put("place_formatted", "4100-002 Porto, Portugal")

        fun portoSuggestion() = JSONObject()
            .put("id", portoId).put("provider", "mapbox").put("formatted_address", "Porto, Portugal")
            .put("name", "Porto").put("place_formatted", "Portugal")

        fun resolvedReply(line: String = LINE, mapboxId: String? = resolvedAddressId): JSONObject {
            val suggestion = JSONObject()
                .put("formatted_address", line).put("city", "Porto").put("state", "Porto").put("state_code", "13")
                .put("country", "Portugal").put("country_code", "PT").put("latitude", 41.15).put("longitude", -8.61)
                .put("timezone", "Europe/Lisbon").put("timezone_offset", 0).put("postal_code", "4000-001")
                .put("provider", "mapbox")
            if (mapboxId != null) suggestion.put("mapbox_id", mapboxId)
            return JSONObject().put("data", JSONObject().put("resolved", true).put("suggestion", suggestion))
        }

        fun refusal(reason: String) =
            JSONObject().put("data", JSONObject().put("resolved", false).put("reason", reason))

        internal fun settings(config: Map<String, Any?>) = LocationSearchSettings.from(config, defaultMinChars = 3)

        internal val addressSettings = settings(
            mapOf("location_type" to "address", "location_bias_country" to "pt", "location_language" to "pt"),
        )

        fun list(vararg items: JSONObject) = JSONArray().apply { items.forEach { put(it) } }
    }

    private fun addressRig(storedAnswer: Any? = null) = LocationSearchRig(
        settings = addressSettings,
        storedAnswer = storedAnswer,
        autocomplete = mapOf(
            "12 Example" to list(addressSuggestion(), avenueSuggestion()),
            "12 Example Av" to list(avenueSuggestion()),
            "Porto" to list(portoSuggestion()),
        ),
    )

    /** List "12 Example", edit to "12 Example Av" (its search left PENDING), tap the first row. */
    private fun staleListTap(): LocationSearchRig {
        val rig = addressRig()
        rig.type("12 Example")
        rig.advance(300)
        assertTrue("precondition: the list is on screen", rig.holder.isDropdownOpen)
        assertEquals(2, rig.holder.results.size)
        rig.type("12 Example Av")
        rig.tap(0)
        return rig
    }

    // ---- The resolve body ----------------------------------------------------------------------

    /**
     * The stale-list case: the body repeats the LISTING request, not the field's text at the tap, and
     * carries the tapped suggestion's own id, provider and display line.
     * Red control: sending the field's current text, or the display line, as `query` fails it.
     */
    @Test
    fun theResolveBodyCarriesTheListingRequestNotTheFieldText() {
        val rig = staleListTap()
        assertEquals(1, rig.resolveBodies.size)
        val body = rig.resolveBodies.first()
        assertEquals("the listing's query", "12 Example", body["query"])
        assertNotEquals("never the field's text at the tap", "12 Example Av", body["query"])
        assertNotEquals("never the display line", LINE, body["query"])
        assertEquals("text is the tapped suggestion's display line", LINE, body["text"])
        assertEquals(listedAddressId, body["id"])
        assertEquals("mapbox", body["provider"])
        assertEquals("address", body["type"])
        assertEquals("pt", body["bias_country"])
        assertEquals("pt", body["language"])
        assertEquals(setOf("id", "provider", "text", "query", "type", "bias_country", "language"), body.keys)
    }

    /** An unauthored setting is absent from the resolve body, as it is from the listing request. */
    @Test
    fun anUnauthoredSettingIsAbsentFromTheBody() {
        val rig = LocationSearchRig(settings(emptyMap()), autocomplete = mapOf("Porto" to list(portoSuggestion())))
        rig.type("Porto")
        rig.advance(300)
        rig.tap(0)
        assertEquals(setOf("id", "provider", "text", "query"), rig.resolveBodies.first().keys)
    }

    /** A Google body is exactly {id, provider, language}: Places prediction text never returns to the server. */
    @Test
    fun aGoogleSuggestionSendsOnlyIdProviderAndLanguage() {
        val google = JSONObject().put("id", "ChIJ-synthetic").put("provider", "google")
            .put("formatted_address", "Springfield, IL, USA")
        val rig = LocationSearchRig(settings(mapOf("location_language" to "en")), autocomplete = mapOf("Springf" to list(google)))
        rig.type("Springf")
        rig.advance(300)
        rig.tap(0)
        val body = rig.resolveBodies.first()
        assertEquals(setOf("id", "provider", "language"), body.keys)
        assertEquals("ChIJ-synthetic", body["id"])
        assertEquals("google", body["provider"])
    }

    // ---- A tap ends the search -----------------------------------------------------------------

    /**
     * After the tap, the edit's pending search never fires and the dropdown stays closed: ONE autocomplete
     * call in all. Red control: the tap's cancel removed → 2 calls and the dropdown open.
     */
    @Test
    fun aTapCancelsThePendingSearchAndClosesTheDropdownForGood() {
        val rig = staleListTap()
        assertFalse("the tap closes the dropdown", rig.holder.isDropdownOpen)
        rig.reply(200, resolvedReply())
        rig.advance(1_000)
        assertEquals("the edit's pending search was cancelled by the tap", listOf("12 Example"), rig.autocompleteQueries)
        assertFalse("nothing reopens the dropdown until the user edits again", rig.holder.isDropdownOpen)
        assertTrue(rig.holder.results.isEmpty())
    }

    /** A fetch already running when a tap cancels the search must not reopen the dropdown when it lands. */
    @Test
    fun aFetchInFlightWhenTheSearchIsCancelledIsDropped() {
        var heldFetch: ((List<LocationSuggestion>) -> Unit)? = null
        val fired = mutableListOf<() -> Unit>()
        val holder = LocationSuggestionSearch(
            storedAnswer = null,
            fetch = { _, done -> heldFetch = done },
            resolve = { _, _ -> },
            schedule = { _, work -> fired += work; ({}) },
        )
        holder.textChanged("Porto", addressSettings)
        fired.toList().forEach { it() }
        assertNotNull("precondition: the fetch is in flight", heldFetch)
        holder.clear()
        heldFetch!!(decodeLocationSuggestions(list(portoSuggestion())))
        assertFalse(holder.isDropdownOpen)
        assertTrue(holder.results.isEmpty())
    }

    // ---- The echo ------------------------------------------------------------------------------

    /**
     * After a resolve the holder sets the field to the resolved line. A text field that reports that set
     * back (SwiftUI does; the fixtures run that shape on both platforms) must not have it stored as typed
     * text (which would overwrite the resolved answer) nor searched.
     * Red control: the `textChanged` value check removed → the answer becomes a String and a second search runs.
     */
    @Test
    fun theEchoOfTheResolvedLineIsNotStoredAsTypedNorSearched() {
        val rig = staleListTap()
        rig.reply(200, resolvedReply())
        val writesAfterResolve = rig.storeWrites
        rig.echo()
        rig.advance(1_000)
        @Suppress("UNCHECKED_CAST")
        val stored = rig.stored as? Map<String, Any> ?: error("the resolved selection did not survive the echo: ${rig.stored}")
        assertEquals(resolvedAddressId, stored["mapbox_id"])
        assertEquals("the echo wrote nothing", writesAfterResolve, rig.storeWrites)
        assertEquals("the echo started no search", 1, rig.autocompleteQueries.size)
    }

    /** A restore is a programmatic set too: its echo starts no search and writes nothing. */
    @Test
    fun theEchoOfARestoreStartsNoSearch() {
        val rig = addressRig()
        rig.storeFromOutside("Springfield")
        assertEquals("Springfield", rig.holder.text)
        val writes = rig.storeWrites
        rig.echo()
        rig.advance(1_000)
        assertEquals(emptyList<String>(), rig.autocompleteQueries)
        assertEquals(writes, rig.storeWrites)
    }

    /**
     * The Compose shape (no echo ever sent): resolve → edit away → type the line back. The retype is the
     * user's, so it is stored as typed and ONE search runs for it.
     * Red controls: an expectation that never resets, and one that only a match consumes, each swallow it.
     */
    @Test
    fun retypingTheResolvedLineWithoutAnEchoIsStoredAndSearched() {
        val rig = addressRig()
        rig.type("Porto")
        rig.advance(300)
        rig.tap(0)
        rig.reply(200, resolvedReply("Porto, Portugal", mapboxId = portoId))
        assertEquals("Porto, Portugal", rig.holder.text)
        rig.type("Porto, Portuga")
        rig.type("Porto, Portugal")
        rig.advance(300)
        assertEquals("the retyped text is the answer", "Porto, Portugal", rig.stored)
        assertEquals("one search ran for the retype", listOf("Porto", "Porto, Portugal"), rig.autocompleteQueries)
    }

    /**
     * The expectation is ONE-SHOT: consumed by the next change whatever its value. With an echo pending, a
     * real edit that arrives first is processed, and does not leave the expectation armed.
     */
    @Test
    fun aMismatchConsumesTheExpectation() {
        val rig = addressRig()
        rig.storeFromOutside("Springfield") // restore → expectation armed, no echo delivered (Compose)
        rig.type("Springfiel")              // a real edit: mismatch, processed, expectation cleared
        rig.type("Springfield")             // typed back: processed, not swallowed
        assertEquals("Springfield", rig.stored)
        rig.advance(300)
        assertEquals(listOf("Springfield"), rig.autocompleteQueries)
    }

    // ---- The resolve outcome -------------------------------------------------------------------

    /** While the resolve is in flight nothing is stored (a required field keeps Continue gated). */
    @Test
    fun nothingIsStoredWhileTheResolveIsInFlight() {
        val rig = staleListTap()
        assertNull("the typed text stored while typing is withdrawn during the resolve", rig.stored)
        assertTrue(rig.holder.isResolving)
        assertEquals("the field keeps the typed text meanwhile", "12 Example Av", rig.holder.text)
    }

    @Test
    fun aResolvedReplyIsStoredWithItsMapboxIdAndTheFieldShowsItsLine() {
        val rig = staleListTap()
        rig.reply(200, resolvedReply())
        @Suppress("UNCHECKED_CAST")
        val stored = rig.stored as Map<String, Any>
        assertEquals(resolvedAddressId, stored["mapbox_id"])
        assertEquals(LINE, stored["formatted_address"])
        assertEquals("the text at the tap, not the listing query", "12 Example Av", stored["raw_query"])
        assertEquals("Europe/Lisbon", stored["timezone"])
        assertNull("the reply's provider is not part of the stored answer", stored["provider"])
        assertNull(stored["id"])
        assertEquals("the field shows the resolved formatted_address", LINE, rig.holder.text)
        assertFalse(rig.holder.isResolving)
    }

    /** Every non-success stores the typed text and keeps showing it. */
    @Test
    fun everyNonSuccessStoresTheTypedText() {
        val cases: List<Pair<String, (LocationSearchRig) -> Unit>> = listOf(
            "refused permanent_not_enabled" to { r -> r.reply(200, refusal("permanent_not_enabled")) },
            "refused token_refused" to { r -> r.reply(200, refusal("token_refused")) },
            "400" to { r -> r.reply(400, JSONObject().put("error", JSONObject().put("code", "VALIDATION_ERROR"))) },
            "401" to { r -> r.reply(401, JSONObject().put("error", JSONObject().put("code", "UNAUTHORIZED"))) },
            "429" to { r -> r.reply(429, JSONObject().put("error", JSONObject().put("code", "RATE_LIMITED"))) },
            "500" to { r -> r.reply(500, JSONObject().put("error", JSONObject().put("code", "INTERNAL"))) },
            "network" to { r -> r.replyNetworkError() },
            "unparseable 200" to { r -> r.reply(200, JSONObject().put("unexpected", true)) },
            "not JSON" to { r -> r.reply(200, "<html>") },
            // Fails closed: a "resolved" answer without its provenance would be stored as permanent on the
            // device while the server treats it as one from an older SDK.
            "resolved without mapbox_id" to { r -> r.reply(200, resolvedReply("Porto, Portugal", mapboxId = null)) },
            "resolved without a line" to { r -> r.reply(200, resolvedReply("", mapboxId = portoId)) },
        )
        for ((name, answer) in cases) {
            val rig = addressRig()
            rig.type("Porto")
            rig.advance(300)
            rig.tap(0)
            assertNull("[$name] nothing stored in flight", rig.stored)
            answer(rig)
            assertEquals("[$name] the typed text is the answer", "Porto", rig.stored)
            assertEquals("[$name] the field keeps the typed text", "Porto", rig.holder.text)
            assertFalse("[$name]", rig.holder.isResolving)
            assertFalse("[$name]", rig.holder.isDropdownOpen)
        }
    }

    /**
     * A suggestion with no `id` (a server that treats this build as old, or a rollback) cannot be resolved,
     * and its full temporary result may not be stored: no request, the typed text stored.
     */
    @Test
    fun aSuggestionWithoutAnIdIsNotResolvedAndStoresTheTypedText() {
        val full = JSONObject().put("formatted_address", "Porto, Portugal").put("city", "Porto")
            .put("country", "Portugal").put("latitude", 41.15).put("longitude", -8.61).put("provider", "mapbox")
        val rig = LocationSearchRig(addressSettings, autocomplete = mapOf("Oporto" to list(full)))
        rig.type("Oporto")
        rig.advance(300)
        rig.tap(0)
        assertEquals(0, rig.resolveBodies.size)
        assertEquals("Oporto", rig.stored)
        assertEquals("Oporto", rig.holder.text)
        assertFalse(rig.holder.isResolving)
    }

    /** A resolve reply that arrives after the user has edited the field's text is dropped. */
    @Test
    fun aReplyAfterAnEditIsDropped() {
        val rig = addressRig()
        rig.type("Porto")
        rig.advance(300)
        rig.tap(0)
        rig.type("Portoo")
        rig.reply(200, resolvedReply("Porto, Portugal", mapboxId = portoId))
        assertEquals("Portoo", rig.stored)
        assertEquals("Portoo", rig.holder.text)
        assertFalse(rig.holder.isResolving)
    }

    /** A reply that arrives after the step has advanced is dropped — the composable's dispose detaches the holder. */
    @Test
    fun aReplyAfterTheFieldWentAwayIsDropped() {
        val rig = staleListTap()
        val writes = rig.storeWrites
        rig.holder.detach()
        rig.reply(200, resolvedReply())
        assertEquals("nothing written after detach", writes, rig.storeWrites)
        assertNull(rig.stored)
        assertEquals("12 Example Av", rig.holder.text)
    }

    /** A second tap supersedes the first: the first reply is dropped. */
    @Test
    fun aSecondTapSupersedesTheFirstReply() {
        val rig = addressRig()
        rig.type("12 Example")
        rig.advance(300)
        rig.tap(0)
        rig.type("12 Example Av")
        rig.advance(300)
        rig.tap(0)
        assertEquals(2, rig.resolvesInFlight)
        rig.reply(200, resolvedReply()) // the first tap's: dropped
        assertNull(rig.stored)
        rig.reply(200, resolvedReply("12 Example Avenue, 4100-002 Porto, Portugal", mapboxId = "synthetic-avenue-resolved"))
        @Suppress("UNCHECKED_CAST")
        assertEquals("synthetic-avenue-resolved", (rig.stored as Map<String, Any>)["mapbox_id"])
    }

    // ---- The field text ------------------------------------------------------------------------

    @Test
    fun theFieldTextForAStoredAnswer() {
        val address = mapOf("formatted_address" to LINE, "address" to LINE, "city" to "Porto", "mapbox_id" to "m")
        val region = mapOf(
            "formatted_address" to "California, United States", "city" to "California", "state" to "California",
            "state_code" to "CA", "country" to "United States", "mapbox_id" to "m",
        )
        val country = mapOf("formatted_address" to "Portugal", "city" to "Portugal", "country" to "Portugal", "mapbox_id" to "m")
        val legacy = mapOf("address" to "Lisbon, Portugal", "latitude" to 38.72, "longitude" to -9.14)
        val expectations: List<Pair<Any?, String>> = listOf(
            address to LINE,
            region to "California, United States",
            country to "Portugal",
            "Springfield" to "Springfield",
            legacy to "Lisbon, Portugal",
            "" to "",
            null to "",
            4 to "",
        )
        for ((answer, text) in expectations) {
            assertEquals("stored $answer", text, LocationSearchRig(addressSettings, storedAnswer = answer).holder.text)
        }
    }

    /**
     * The restore is REACTIVE: an answer seeded after construction (`FormStep` fills `values` in a
     * `LaunchedEffect` after the field's first composition) is restored while the text is empty.
     */
    @Test
    fun aRestoreIsReactiveWhenTheAnswerIsSeededLater() {
        val rig = addressRig()
        assertEquals("", rig.holder.text)
        rig.storeFromOutside(mapOf("formatted_address" to "California, United States", "city" to "California", "mapbox_id" to "m"))
        assertEquals("California, United States", rig.holder.text)
        assertEquals("a restore writes nothing", 0, rig.storeWrites)
    }

    /** …but never over text the user typed, and only on an absent → present transition. */
    @Test
    fun aLaterAnswerDoesNotOverwriteTypedText() {
        val rig = addressRig()
        rig.type("Spr")
        rig.storeFromOutside(mapOf("formatted_address" to "California, United States", "mapbox_id" to "m"))
        assertEquals("Spr", rig.holder.text)
    }

    /**
     * A configuration change (rotation) rebuilds the composition: a new holder is constructed from the
     * answer the ViewModel kept, and shows the resolved line, not the containing city.
     */
    @Test
    fun aRebuiltHolderShowsTheResolvedLine() {
        val first = staleListTap()
        first.reply(200, resolvedReply())
        val rebuilt = LocationSearchRig(addressSettings, storedAnswer = first.stored)
        assertEquals(LINE, rebuilt.holder.text)
        assertEquals("a restore writes nothing", 0, rebuilt.storeWrites)
    }

    // ---- The search ----------------------------------------------------------------------------

    @Test
    fun belowMinCharsNoSearchAndTheDropdownCloses() {
        val rig = addressRig()
        rig.type("12 Example")
        rig.advance(300)
        assertTrue(rig.holder.isDropdownOpen)
        rig.type("12")
        rig.advance(300)
        assertFalse(rig.holder.isDropdownOpen)
        assertTrue(rig.holder.results.isEmpty())
        assertEquals(listOf("12 Example"), rig.autocompleteQueries)
        assertEquals("typed text is still stored below the threshold", "12", rig.stored)
    }

    @Test
    fun theDebounceSendsOneRequestPerPause() {
        val rig = addressRig()
        rig.type("12 E")
        rig.advance(100)
        rig.type("12 Ex")
        rig.advance(299)
        assertEquals("still inside the debounce", emptyList<String>(), rig.autocompleteQueries)
        rig.advance(1)
        assertEquals(listOf("12 Ex"), rig.autocompleteQueries)
    }

    /** Each row keeps the request that listed it, even after the list is replaced. */
    @Test
    fun eachListCarriesItsOwnListingRequest() {
        val rig = addressRig()
        rig.type("12 Example")
        rig.advance(300)
        assertEquals("12 Example", rig.holder.results.first().listing.query)
        rig.type("12 Example Av")
        rig.advance(300)
        assertEquals(1, rig.holder.results.size)
        assertEquals("12 Example Av", rig.holder.results.first().listing.query)
        rig.tap(0)
        assertEquals("12 Example Av", rig.resolveBodies.first()["query"])
    }

    @Test
    fun clearEmptiesTheTextTheAnswerAndTheList() {
        val rig = addressRig()
        rig.type("12 Example")
        rig.advance(300)
        rig.holder.clear()
        rig.echo()
        rig.advance(1_000)
        assertEquals("", rig.holder.text)
        assertNull(rig.stored)
        assertFalse(rig.holder.isDropdownOpen)
        assertEquals("clearing searched nothing", listOf("12 Example"), rig.autocompleteQueries)
    }

    /** Erasing the field stores nothing (an absent answer), not an empty string. */
    @Test
    fun anEmptiedFieldStoresNoAnswer() {
        val rig = addressRig()
        rig.type("Po")
        rig.type("")
        assertNull(rig.stored)
    }

    // ---- The carriers --------------------------------------------------------------------------

    /**
     * A listed suggestion keeps its own id and provider on the internal type, and NEVER fills the
     * provenance ids, whatever keys it carries.
     */
    @Test
    fun theSuggestionDecoderKeepsIdAndProviderButNeverFillsProvenance() {
        val odd = portoSuggestion().put("mapbox_id", "should-not-land").put("place_id", "should-not-land")
        val item = decodeLocationSuggestions(list(odd)).single()
        assertEquals(portoId, item.id)
        assertEquals("mapbox", item.provider)
        assertEquals("Porto, Portugal", item.address)
        assertNull(item.mapboxId)
        assertNull(item.placeId)
        assertNull("a display-only suggestion carries no coordinates", item.latitude)
        // A blank id is no id.
        assertNull(decodeLocationSuggestions(list(portoSuggestion().put("id", "  "))).single().id)
    }

    /** The stored answer carries the provenance a resolve reply had, and `getLocationData`'s reader returns it. */
    @Test
    fun theStoredAnswerAndItsReaderKeepTheProvenanceIds() {
        val mapbox = LocationAnswer.selection(
            LocationSuggestion(address = "Porto, Portugal", latitude = null, longitude = null, mapboxId = "m-1",
                id = "suggestion-id", provider = "mapbox"),
            "Por",
        )
        assertEquals("m-1", mapbox["mapbox_id"])
        assertNull(mapbox["place_id"])
        assertNull("the suggestion's own id is never written", mapbox["id"])
        assertNull("the suggestion's provider is never written", mapbox["provider"])
        assertEquals("m-1", locationDataFromAnswer(mapbox)?.mapbox_id)

        val google = LocationAnswer.selection(
            LocationSuggestion(address = "Springfield, IL, USA", latitude = null, longitude = null, placeId = "ChIJ-synthetic"),
            "Spr",
        )
        assertEquals("ChIJ-synthetic", google["place_id"])
        assertEquals("ChIJ-synthetic", locationDataFromAnswer(google)?.place_id)

        // An answer without provenance has neither, and a stored `id` is never read into them.
        val legacy = locationDataFromAnswer(mapOf("formatted_address" to "Lisbon, Portugal", "id" to "x"))
        assertNull(legacy?.mapbox_id)
        assertNull(legacy?.place_id)
    }

    /** Both surfaces draw a display-only row from its display line. */
    @Test
    fun aDisplayOnlyRowHasATitleAndSubtitle() {
        val item = decodeLocationSuggestions(list(addressSuggestion())).single()
        assertEquals("12 Example Street" to "4000-001 Porto, Portugal", LocationAnswer.displayLines(item))
    }
}
