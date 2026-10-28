package ai.appdna.sdk.onboarding

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two geocode requests on the wire, through a stub [GeocodeTransport]: the URL, both SDK headers and
 * the body as built, and the reply parsed by the real code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationGeocodeTransportTest {

    private class Captured(val url: String, val headers: Map<String, String>, val body: JSONObject)

    private class StubTransport(private val reply: () -> GeocodeHttpReply) : GeocodeTransport {
        val sent = mutableListOf<Captured>()
        override suspend fun post(url: String, headers: Map<String, String>, body: String): GeocodeHttpReply {
            sent += Captured(url, headers, JSONObject(body))
            return reply()
        }
    }

    /** NEGATIVE CONTROL for everything below: the stub saw the request and read its body. */
    @Test
    fun theResolveIsPostedToTheResolveRouteWithBothSdkHeaders() = runBlocking<Unit> {
        val stub = StubTransport { GeocodeHttpReply(200, LocationSuggestionSearchTest.resolvedReply().toString()) }
        val body = mapOf("id" to "x", "provider" to "mapbox", "text" to "Porto, Portugal", "query" to "Porto")
        val outcome = LocationResolve.send(body, stub)
        assertTrue(outcome is LocationResolveOutcome.Resolved)
        val sent = stub.sent.single()
        assertTrue(sent.url, sent.url.endsWith("/api/v1/sdk/geocode/resolve"))
        assertEquals("the body is sent as built", body.keys, sent.body.keys().asSequence().toSet())
        assertEquals("Porto", sent.body.getString("query"))
        assertEquals("android", sent.headers["x-sdk-platform"])
        assertEquals(ai.appdna.sdk.AppDNA.sdkVersion, sent.headers["x-sdk-version"])
        assertEquals("application/json", sent.headers["Content-Type"])
    }

    @Test
    fun aResolvedReplyParsesWithItsProvenanceAndNoRawQuery() = runBlocking<Unit> {
        val stub = StubTransport { GeocodeHttpReply(200, LocationSuggestionSearchTest.resolvedReply().toString()) }
        val outcome = LocationResolve.send(mapOf("id" to "x", "provider" to "mapbox"), stub)
        val location = (outcome as LocationResolveOutcome.Resolved).location
        assertEquals(LocationSuggestionSearchTest.resolvedAddressId, location.mapboxId)
        assertEquals(LocationSuggestionSearchTest.LINE, location.address)
        assertNull("the reply carries no raw_query; the SDK sets it from the tap", location.rawQuery)
    }

    @Test
    fun aRefusalIsRefused() = runBlocking<Unit> {
        val stub = StubTransport { GeocodeHttpReply(200, LocationSuggestionSearchTest.refusal("permanent_not_enabled").toString()) }
        assertEquals(
            LocationResolveOutcome.Refused("permanent_not_enabled"),
            LocationResolve.send(mapOf("id" to "x", "provider" to "mapbox"), stub),
        )
    }

    @Test
    fun anHttpErrorFails() = runBlocking<Unit> {
        val stub = StubTransport { GeocodeHttpReply(429, """{"error":{"code":"RATE_LIMITED"}}""") }
        assertEquals(LocationResolveOutcome.Failed("http_429"), LocationResolve.send(mapOf("id" to "x"), stub))
    }

    @Test
    fun aNetworkFailureFails() = runBlocking<Unit> {
        val stub = StubTransport { throw java.io.IOException("synthetic") }
        assertEquals(LocationResolveOutcome.Failed("network"), LocationResolve.send(mapOf("id" to "x"), stub))
    }

    /** A display-only suggestion keeps its id and provider through the real autocomplete request. */
    @Test
    fun theAutocompleteSendsTheListingAndKeepsEachSuggestionsIdAndProvider() = runBlocking<Unit> {
        val reply = JSONObject().put(
            "data",
            JSONObject().put("suggestions", LocationSuggestionSearchTest.list(LocationSuggestionSearchTest.addressSuggestion())),
        )
        val stub = StubTransport { GeocodeHttpReply(200, reply.toString()) }
        val listing = LocationListingRequest(query = "12 Example", type = "address", biasCountry = null, language = "pt")
        val items = fetchLocationSuggestionItems(listing, stub)
        assertEquals(1, items.size)
        assertEquals(LocationSuggestionSearchTest.listedAddressId, items.single().id)
        assertEquals("mapbox", items.single().provider)
        assertEquals(LocationSuggestionSearchTest.LINE, items.single().address)
        val sent = stub.sent.single()
        assertEquals("the listing request is what was sent", setOf("query", "limit", "type", "language"), sent.body.keys().asSequence().toSet())
        assertTrue(sent.url, sent.url.endsWith("/api/v1/sdk/geocode/autocomplete"))
        assertEquals("android", sent.headers["x-sdk-platform"])
        assertEquals(ai.appdna.sdk.AppDNA.sdkVersion, sent.headers["x-sdk-version"])
    }

    @Test
    fun anAutocompleteErrorIsAnEmptyList() = runBlocking<Unit> {
        assertEquals(emptyList<LocationSuggestion>(), fetchLocationSuggestionItems(
            LocationListingRequest("Porto", null, null, null), StubTransport { GeocodeHttpReply(500, "") },
        ))
        assertEquals(emptyList<LocationSuggestion>(), fetchLocationSuggestionItems(
            LocationListingRequest("Porto", null, null, null), StubTransport { throw java.io.IOException("synthetic") },
        ))
    }
}
