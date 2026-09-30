package ai.appdna.sdk.onboarding

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.core.SessionDataStore
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SPEC-497 §13h.3 (D2) — getLocationData over every stored shape. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationDataFromAnswerTest {

    @After
    fun tearDown() {
        SessionDataStore.instance?.clearAll()
    }

    @Test
    fun typedStringReturnsAPartialObject() {
        assertEquals(LocationData(formatted_address = "Auckland", raw_query = "Auckland"), locationDataFromAnswer("Auckland"))
    }

    @Test
    fun emptyNullNumberAndAddresslessMapReturnNull() {
        assertNull(locationDataFromAnswer(""))
        assertNull(locationDataFromAnswer("   "))
        assertNull(locationDataFromAnswer(null))
        assertNull(locationDataFromAnswer(42))
        assertNull(locationDataFromAnswer(mapOf("city" to "Nowhere")))
    }

    @Test
    fun fullDictAndLegacyAddressDict() {
        val full = locationDataFromAnswer(
            mapOf(
                "formatted_address" to "A, B", "city" to "A", "state" to "S", "country" to "C",
                "latitude" to 1.5, "longitude" to -2.5, "timezone" to "Etc/UTC", "timezone_offset" to 0,
            ),
        )!!
        assertEquals("A", full.city)
        assertEquals(1.5, full.latitude!!, 0.0)
        assertEquals(0, full.timezone_offset)
        assertNull(full.raw_query)

        val legacy = locationDataFromAnswer(mapOf("address" to "Old St", "latitude" to 3, "longitude" to 4))!!
        assertEquals("Old St", legacy.formatted_address)
        assertEquals(3.0, legacy.latitude!!, 0.0)
        assertNull(legacy.city)
        assertNull(legacy.timezone)
    }

    @Test
    fun nonFiniteOrNonNumericCoordinateIsNull() {
        val nan = locationDataFromAnswer(mapOf("formatted_address" to "X", "latitude" to Double.NaN, "longitude" to 1.0))!!
        assertNull(nan.latitude)
        assertEquals(1.0, nan.longitude!!, 0.0)
        val inf = locationDataFromAnswer(mapOf("formatted_address" to "X", "latitude" to "12.0", "longitude" to Double.POSITIVE_INFINITY))!!
        assertNull(inf.latitude)
        assertNull(inf.longitude)
    }

    @Test
    fun getLocationDataReadsTheStoredAnswer() {
        SessionDataStore.initialize(ApplicationProvider.getApplicationContext())
        SessionDataStore.instance!!.setOnboardingResponses(mapOf("s1" to mapOf("loc" to "Typed")))
        assertEquals("Typed", AppDNA.getLocationData("loc")?.raw_query)
        assertNull(AppDNA.getLocationData("missing"))
    }

    @Test
    fun aSelectionStoresTheFullShapeWithTheAddressAlias() {
        val s = parseLocationSuggestion(
            JSONObject(
                """{"formatted_address":"Q, R","latitude":1.0,"longitude":2.0,"city":"Q","state":"",""" +
                    """"country":"R","country_code":"RR","timezone":"Etc/UTC","postal_code":null}""",
            ),
        )
        val stored = LocationAnswer.selection(s, null)
        assertEquals(
            mapOf(
                "formatted_address" to "Q, R", "address" to "Q, R", "city" to "Q", "country" to "R",
                "latitude" to 1.0, "longitude" to 2.0, "timezone" to "Etc/UTC", "country_code" to "RR",
            ),
            stored,
        )
        // …and reads back through the same builder getLocationData uses.
        val read = locationDataFromAnswer(stored)!!
        assertEquals("Q", read.city)
        assertNull(read.state)
        assertEquals("RR", read.country_code)
    }

    /**
     * Impl audit round 2 A3 (iOS parity): a suggestion without coordinates is KEPT, and its stored
     * answer omits the missing coordinate — never 0.0.
     */
    @Test
    fun aSuggestionWithoutCoordinatesIsKeptAndStoresNoCoordinate() {
        val none = parseLocationSuggestion(JSONObject("""{"formatted_address":"Q","city":"Q"}"""))
        assertNull(none.latitude)
        assertNull(none.longitude)
        val stored = LocationAnswer.selection(none, null)
        assertEquals(mapOf("formatted_address" to "Q", "address" to "Q", "city" to "Q"), stored)
        assertNull(locationDataFromAnswer(stored)!!.latitude)

        val latOnly = parseLocationSuggestion(JSONObject("""{"formatted_address":"Q","latitude":1.5}"""))
        assertEquals(1.5, latOnly.latitude!!, 0.0)
        assertNull(latOnly.longitude)
        assertFalse(LocationAnswer.selection(latOnly, null).containsKey("longitude"))
        assertNull(parseLocationSuggestion(JSONObject("""{"formatted_address":"Q","latitude":null,"longitude":2.0}""")).latitude)
        assertNull(parseLocationSuggestion(JSONObject("""{"formatted_address":"Q","latitude":"x","longitude":2.0}""")).latitude)
        // A real 0.0 is still a coordinate (the spec treats only non-finite / non-numeric as missing).
        val zero = parseLocationSuggestion(JSONObject("""{"formatted_address":"Q","latitude":0.0,"longitude":0.0}"""))
        assertEquals(0.0, zero.latitude!!, 0.0)
        assertEquals(0.0, zero.longitude!!, 0.0)
        assertEquals(0.0, LocationAnswer.selection(zero, null)["latitude"])
    }
}
