package ai.appdna.sdk.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure precision-5 decoder. */
class MapPolylineTest {

    private fun assertPoints(expected: List<Pair<Double, Double>>, actual: List<Pair<Double, Double>>?) {
        requireNotNull(actual)
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) ->
            assertEquals(e.first, a.first, 1e-5)
            assertEquals(e.second, a.second, 1e-5)
        }
    }

    @Test
    fun googleDocumentedSample() {
        assertPoints(
            listOf(38.5 to -120.2, 40.7 to -120.95, 43.252 to -126.453),
            MapPolyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@"),
        )
    }

    @Test
    fun emptyIsEmptyAndMalformedIsNull() {
        assertEquals(emptyList<Pair<Double, Double>>(), MapPolyline.decode(""))
        assertNull(MapPolyline.decode("_p~iF"))
        assertNull(MapPolyline.decode("_p~iF~ps|U_ulL"))
        assertNull(MapPolyline.decode("_"))
        assertNull(MapPolyline.decode("_p~iF~ps|U\u0001"))
    }

    @Test
    fun outOfRangeCoordinatesAreMalformed() {
        // A precision-6 route decodes 10x too large: 100.0 degrees latitude.
        assertNull(MapPolyline.decode(encodeMapPolyline(listOf(95.0 to 10.0))))
        assertNull(MapPolyline.decode(encodeMapPolyline(listOf(10.0 to 181.0))))
    }

    @Test
    fun roundTripsThroughTheEncoder() {
        val stops = listOf(48.85661 to 2.35222, 45.764 to 4.83566, -33.8688 to 151.2093, 0.0 to -0.00001)
        assertPoints(stops, MapPolyline.decode(encodeMapPolyline(stops)))
    }
}
