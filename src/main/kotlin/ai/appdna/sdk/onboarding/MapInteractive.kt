package ai.appdna.sdk.onboarding

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ai.appdna.sdk.core.StyleEngine
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState

/**
 * SPEC-495 §A tier 2 — a REAL, pannable, zoomable map, drawn by the bundled Google Maps SDK.
 *
 * This is the tier #671 asked for. Before it, `map_interactive: true` on a Flutter host silently
 * produced a still image, because the only interactive path went through
 * `AppDNA.registerMapView` — an API the Flutter wrapper never exposed.
 *
 * 🔴 THE MANIFEST KEY IS A REAL CONSTRAINT, AND IT CORRECTS SPEC-495 §D4.
 *
 * The spec claimed the bundled engine would need "no extra wiring at all" from the host. That is
 * true of the STATIC tier, whose key is server-delivered — and it is NOT true here. The Google Maps
 * Android SDK reads `com.google.android.geo.API_KEY` from the MERGED MANIFEST at build time; there
 * is no runtime setter, so an AppDNA-delivered key cannot reach it. A host that wants a pannable
 * Google map must add one `<meta-data>` line.
 *
 * Rather than render a grey rectangle when that line is missing — which is what the Maps SDK does,
 * and it looks exactly like a broken map — `hasGoogleMapsManifestKey` is checked FIRST and the
 * caller falls back to the static tier, which needs no manifest and still honours every styling
 * field. A host that does nothing therefore gets the map it gets today, not a regression.
 */
@Composable
internal fun GoogleInteractiveMap(block: ContentBlock, modifier: Modifier = Modifier) {
    val stops = mapStopsOf(block)
    val isPlace = block.mapStr("map_mode") == "place"

    val centre = when {
        isPlace -> LatLng(block.mapNum("place_lat") ?: 47.6205, block.mapNum("place_lng") ?: -122.3493)
        stops.isNotEmpty() -> LatLng(stops.first().first, stops.first().second)
        else -> LatLng(block.mapNum("map_center_lat") ?: 47.6205, block.mapNum("map_center_lng") ?: -122.3493)
    }
    val zoom = (block.mapNum("map_zoom") ?: 12.0).toFloat()
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(centre, zoom)
    }

    val context = LocalContext.current
    val styleOptions = remember(block.mapStr("map_style_json"), block.mapStr("map_theme")) {
        googleStyleJsonOf(block)?.takeIf { it.isNotBlank() }?.let {
            // A malformed style must not take the map down with it — the static tier makes the same
            // choice (no style params rather than a broken request).
            runCatching { MapStyleOptions(it) }.getOrNull()
        }
    }

    GoogleMap(
        modifier = modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        properties = MapProperties(
            mapType = when (googleMapType(block.mapStr("map_style"))) {
                "satellite" -> MapType.SATELLITE
                "hybrid" -> MapType.HYBRID
                "terrain" -> MapType.TERRAIN
                else -> MapType.NORMAL
            },
            mapStyleOptions = styleOptions,
            // 🔴 NEVER true. `isMyLocationEnabled` makes the Maps SDK request a location fix, and on
            // a host that holds the permission it starts one silently. An onboarding map must not
            // turn the user's first run into a location prompt they did not ask for (SPEC-495 §D3).
            isMyLocationEnabled = false,
        ),
        uiSettings = MapUiSettings(
            // The authored gate. `map_interactive` is what the console's "Pan & zoom" switch sets,
            // and an author who turned it off wants a picture of a place, not a map the user can
            // drag away from the place the step is about.
            scrollGesturesEnabled = block.mapBool("map_interactive") != false,
            zoomGesturesEnabled = block.mapBool("map_interactive") != false,
            rotationGesturesEnabled = block.mapBool("map_interactive") != false,
            tiltGesturesEnabled = false,
            zoomControlsEnabled = block.mapBool("map_show_controls") == true,
            compassEnabled = block.mapBool("map_show_controls") == true,
            myLocationButtonEnabled = false,
            mapToolbarEnabled = false,
        ),
    ) {
        // Route BEFORE markers, so pins draw over the line — the same ordering the two static
        // builders use, so switching tier does not reorder the map.
        if (!isPlace && block.mapBool("route_show") != false && stops.size >= 2) {
            val width = (block.mapNum("route_width") ?: 4.0).toFloat()
            val casing = (block.mapNum("route_casing_width") ?: 2.0).toFloat()
            val points = stops.map { LatLng(it.first, it.second) }
            if (casing > 0) {
                Polyline(
                    points = points,
                    color = StyleEngine.parseColor(block.mapStr("route_casing_color") ?: "#FFFFFF"),
                    width = width + casing * 2,
                )
            }
            Polyline(
                points = points,
                color = StyleEngine.parseColor(block.mapStr("route_color") ?: "#6366F1"),
                width = width,
            )
        }
        stops.forEachIndexed { i, s ->
            Marker(
                state = MarkerState(position = LatLng(s.first, s.second)),
                title = if (i == 0) block.mapStr("place_title") else null,
            )
        }
    }
}

/**
 * SPEC-495 §D3 — whether this app can actually draw a Google map.
 *
 * The Maps SDK takes its key from `com.google.android.geo.API_KEY` in the merged manifest, so this
 * is a build-time fact about the HOST, not something AppDNA can supply at runtime. Checked before
 * composing a map rather than after, because the failure mode without it is a grey rectangle that
 * looks identical to a map that failed to load.
 *
 * Any lookup failure counts as "no key": a map that might work is not worth a crash on a screen
 * the user is trying to get through.
 */
internal fun hasGoogleMapsManifestKey(context: Context): Boolean = runCatching {
    val ai = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
    !ai.metaData?.getString("com.google.android.geo.API_KEY").isNullOrBlank()
}.getOrDefault(false)
