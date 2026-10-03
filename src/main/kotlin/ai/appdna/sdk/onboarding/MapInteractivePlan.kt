package ai.appdna.sdk.onboarding

import ai.appdna.sdk.Log

/**
 * Google's encoded-polyline format, precision 5, decoded.
 *
 * Hand-written (the mirror of [encodeMapPolyline]); no `android-maps-utils` dependency. Pure, so the
 * shared fixtures run it without the Maps SDK.
 *
 * Returns `null` for malformed input — a truncated chunk, a character outside `?`…`~`, a latitude
 * chunk with no longitude chunk, or a coordinate outside ±90 / ±180 (which is also how a precision-6
 * polyline, decoded 10× too large, is rejected) — and `[]` for an empty string.
 */
internal object MapPolyline {
    fun decode(encoded: String): List<Pair<Double, Double>>? {
        val out = mutableListOf<Pair<Double, Double>>()
        var index = 0
        var lat = 0L
        var lng = 0L

        /** One zig-zag varint chunk, or null when it is truncated or holds an invalid character. */
        fun chunk(): Long? {
            var result = 0L
            var shift = 0
            while (true) {
                if (index >= encoded.length) return null
                val b = encoded[index++].code - 63
                if (b < 0 || b > 63) return null
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
                if (shift > 60) return null
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }

        while (index < encoded.length) {
            val dLat = chunk() ?: return null
            // A latitude with no longitude is a truncated point, not a point on the equator.
            if (index >= encoded.length) return null
            val dLng = chunk() ?: return null
            lat += dLat
            lng += dLng
            val la = lat / 1e5
            val ln = lng / 1e5
            if (la < -90.0 || la > 90.0 || ln < -180.0 || ln > 180.0) return null
            out.add(la to ln)
        }
        return out
    }
}

/**
 * What the interactive map draws and where its camera goes. The ONE seam:
 * [GoogleInteractiveMap] only consumes it, and the shared fixtures assert it directly.
 */
internal data class MapInteractivePlan(
    val route: Route,
    /** One per stop, unchanged styling. Polyline points never become markers. */
    val markers: List<Pair<Double, Double>>,
    val camera: Camera,
) {
    data class Route(val source: String, val points: List<Pair<Double, Double>>)

    sealed class Camera {
        /** Frame the bounding box of [points] (naive min/max; antimeridian out of scope). */
        data class Fit(val points: List<Pair<Double, Double>>) : Camera()
        data class Center(val lat: Double, val lng: Double, val zoom: Double) : Camera()
    }

    companion object {
        /** Fixed padding around a fitted route, in dp (converted to px when applied). */
        const val FIT_PADDING_DP = 48

        /** Zoom for a fit whose point set collapses to one point. Not `map_zoom`: the console disables that slider while fit is on. */
        const val SINGLE_POINT_ZOOM = 15.0

        private const val DEFAULT_LAT = 47.6205
        private const val DEFAULT_LNG = -122.3493
        private const val DEFAULT_ZOOM = 12.0

        fun compute(block: ContentBlock, rawResolved: Boolean = false): MapInteractivePlan {
            val isPlace = block.mapStr("map_mode") == "place"
            val stops = mapStopsOf(block)

            val encoded = if (isPlace) null else mapRoutePolyline(block, rawResolved)
            val decoded = encoded?.let { MapPolyline.decode(it) }
            if (encoded != null && decoded == null) Log.debug("map: polyline malformed, using stops")

            // Rule 1 — the route line.
            val route = when {
                isPlace || block.mapBool("route_show") == false -> Route("none", emptyList())
                decoded != null && decoded.size >= 2 -> Route("polyline", decoded)
                stops.size >= 2 -> Route("stops", stops)
                else -> Route("none", emptyList())
            }

            // Rule 3 — the camera.
            val zoom = block.mapNum("map_zoom") ?: DEFAULT_ZOOM
            val camera: Camera = if (isPlace) {
                Camera.Center(block.mapNum("place_lat") ?: DEFAULT_LAT, block.mapNum("place_lng") ?: DEFAULT_LNG, zoom)
            } else {
                // A one-point polyline draws no line, but it is still where the route is.
                val routePoints = if (route.source == "none" && decoded?.size == 1) decoded else route.points
                val fitSet = LinkedHashSet<Pair<Double, Double>>().apply {
                    addAll(routePoints)
                    addAll(stops)
                }.toList()
                val fit = block.mapBool("map_fit_to_stops") != false
                when {
                    fit && fitSet.size >= 2 -> Camera.Fit(fitSet)
                    fit && fitSet.size == 1 -> Camera.Center(fitSet[0].first, fitSet[0].second, SINGLE_POINT_ZOOM)
                    else -> Camera.Center(
                        block.mapNum("map_center_lat") ?: DEFAULT_LAT,
                        block.mapNum("map_center_lng") ?: DEFAULT_LNG,
                        zoom,
                    )
                }
            }
            return MapInteractivePlan(route, stops, camera)
        }
    }
}
