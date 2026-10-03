package ai.appdna.sdk.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The interactive map plan, rule by rule. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapInteractivePlanTest {

    @get:Rule
    val compose = createComposeRule()

    private val sample = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
    private val a = mapOf("lat" to 48.85661, "lng" to 2.35222)
    private val b = mapOf("lat" to 45.764, "lng" to 4.83566)

    private fun block(vararg cfg: Pair<String, Any>) =
        ContentBlock(id = "m", type = "map", field_config = mapOf<String, Any>("map_mode" to "route") + cfg)

    @Test
    fun polylineWinsAndFitsRoutePlusStops() {
        val plan = MapInteractivePlan.compute(block("map_route_polyline" to sample, "map_stops" to listOf(a, b)))
        assertEquals("polyline", plan.route.source)
        assertEquals(3, plan.route.points.size)
        assertEquals(2, plan.markers.size)
        assertEquals(5, (plan.camera as MapInteractivePlan.Camera.Fit).points.size)
    }

    @Test
    fun stopsOnlyDrawStraightSegmentsAndFit() {
        val plan = MapInteractivePlan.compute(block("map_stops" to listOf(a, b)))
        assertEquals("stops", plan.route.source)
        assertEquals(listOf(48.85661 to 2.35222, 45.764 to 4.83566), (plan.camera as MapInteractivePlan.Camera.Fit).points)
    }

    @Test
    fun malformedPolylineFallsBackToStops() {
        val plan = MapInteractivePlan.compute(block("map_route_polyline" to "_p~iF", "map_stops" to listOf(a, b)))
        assertEquals("stops", plan.route.source)
    }

    @Test
    fun routeHiddenOrPlaceModeDrawsNothing() {
        assertEquals("none", MapInteractivePlan.compute(block("route_show" to false, "map_stops" to listOf(a, b))).route.source)
        val place = MapInteractivePlan.compute(
            ContentBlock(id = "p", type = "map", field_config = mapOf("map_mode" to "place", "place_lat" to 1.0, "place_lng" to 2.0, "map_zoom" to 7)),
        )
        assertEquals("none", place.route.source)
        assertEquals(MapInteractivePlan.Camera.Center(1.0, 2.0, 7.0), place.camera)
    }

    @Test
    fun fitOffCentresOnTheAuthoredCentreNotTheFirstStop() {
        val plan = MapInteractivePlan.compute(
            block("map_fit_to_stops" to false, "map_center_lat" to 36.3, "map_center_lng" to 174.8, "map_zoom" to 9, "map_stops" to listOf(a, b)),
        )
        assertEquals(MapInteractivePlan.Camera.Center(36.3, 174.8, 9.0), plan.camera)
        // No authored centre → Seattle at zoom 12, as the static tier.
        assertEquals(
            MapInteractivePlan.Camera.Center(47.6205, -122.3493, 12.0),
            MapInteractivePlan.compute(block("map_fit_to_stops" to false, "map_stops" to listOf(a))).camera,
        )
    }

    @Test
    fun singlePointCentresAtZoom15EvenWithAuthoredZoom() {
        val plan = MapInteractivePlan.compute(block("map_zoom" to 9, "map_stops" to listOf(a)))
        assertEquals(MapInteractivePlan.Camera.Center(48.85661, 2.35222, 15.0), plan.camera)
        // A one-point polyline and no stops: no line, but the camera centres on it.
        val one = MapInteractivePlan.compute(block("map_route_polyline" to encodeMapPolyline(listOf(10.0 to 20.0))))
        assertEquals("none", one.route.source)
        assertEquals(MapInteractivePlan.Camera.Center(10.0, 20.0, 15.0), one.camera)
    }

    @Test
    fun nothingToFitUsesTheAuthoredCentre() {
        assertEquals(MapInteractivePlan.Camera.Center(47.6205, -122.3493, 12.0), MapInteractivePlan.compute(block()).camera)
    }

    @Test
    fun rawResolvedKeepsAPolylineContainingBraces() {
        // A REAL encoded polyline can contain `{{` (ASCII 63–126): this two-point route encodes to one.
        val braced = encodeMapPolyline(listOf(48.0 to 2.0, 48.00974 to 2.1))
        assertTrue("fixture polyline must contain {{: $braced", braced.contains("{{"))
        val blk = block("map_route_variable" to braced, "map_route_polyline" to sample)

        // The RAW path (the host-data pass resolved the variable): the variable IS the polyline.
        val raw = MapInteractivePlan.compute(blk, rawResolved = true)
        assertEquals("polyline", raw.route.source)
        assertEquals(2, raw.route.points.size)
        assertEquals(48.0, raw.route.points[0].first, 1e-5)
        assertEquals(48.00974, raw.route.points[1].first, 1e-5)
        assertEquals(2.1, raw.route.points[1].second, 1e-5)

        // Not resolved: an unresolved-looking `{{` falls through to the authored polyline (3 points).
        val interpolated = MapInteractivePlan.compute(blk, rawResolved = false)
        assertEquals("polyline", interpolated.route.source)
        assertEquals(3, interpolated.route.points.size)
    }

    @Test
    fun interactiveMapComposesWithAPolylinePlanWithoutThrowing() {
        val blk = block("map_route_polyline" to sample, "map_stops" to listOf(a, b))
        compose.setContent { GoogleInteractiveMap(blk) }
        compose.waitForIdle()
    }
}
