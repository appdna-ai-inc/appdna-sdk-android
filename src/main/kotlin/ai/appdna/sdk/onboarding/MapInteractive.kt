package ai.appdna.sdk.onboarding

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import ai.appdna.sdk.Log
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraPositionState
import kotlin.math.roundToInt
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
internal fun GoogleInteractiveMap(block: ContentBlock, rawResolved: Boolean = false, modifier: Modifier = Modifier) {
    // SPEC-497 §7.2 — every decision (route source, markers, camera) is the pure plan; this
    // composable only draws it. `rawResolved` keeps a raw host polyline containing `{{` (R69).
    val plan = remember(block, rawResolved) { MapInteractivePlan.compute(block, rawResolved) }

    val cameraPositionState = rememberCameraPositionState {
        position = initialCameraPosition(plan.camera)
    }
    var mapLoaded by remember { mutableStateOf(false) }
    val density = LocalDensity.current.density
    // Rule 4 — applied once the map has laid out, and again ONLY when the camera input changes
    // (e.g. host routes arriving after first paint). Styling or recomposition never moves it, so a
    // user's pan/zoom is kept.
    LaunchedEffect(mapLoaded, plan.camera) {
        if (mapLoaded) applyPlanCamera(cameraPositionState, plan.camera, density)
    }
    // A key whose Google Cloud project lacks "Maps SDK for Android" (or whose app restriction does
    // not list this app) is not detectable up front: the Maps SDK draws a grey map and reports it
    // only in logcat. A map that never finishes loading is the one visible symptom — say what to fix.
    LaunchedEffect(Unit) { mapLoadWatchdog(isLoaded = { mapLoaded }) }

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
        onMapLoaded = { mapLoaded = true },
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
        PlanRoute(block, plan.route)
        plan.markers.forEachIndexed { i, s ->
            Marker(
                state = MarkerState(position = LatLng(s.first, s.second)),
                title = if (i == 0) block.mapStr("place_title") else null,
            )
        }
    }
}

/** The route line, styled exactly as before, for whichever source the plan chose. */
@Composable
private fun PlanRoute(block: ContentBlock, route: MapInteractivePlan.Route) {
    if (route.source == "none" || route.points.size < 2) return
    val width = (block.mapNum("route_width") ?: 4.0).toFloat()
    val casing = (block.mapNum("route_casing_width") ?: 2.0).toFloat()
    val points = route.points.map { LatLng(it.first, it.second) }
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

/** A starting position before the map has a size: the fit's first point, or the plan's centre. */
internal fun initialCameraPosition(camera: MapInteractivePlan.Camera): CameraPosition = when (camera) {
    is MapInteractivePlan.Camera.Center -> CameraPosition.fromLatLngZoom(LatLng(camera.lat, camera.lng), camera.zoom.toFloat())
    is MapInteractivePlan.Camera.Fit -> CameraPosition.fromLatLngZoom(
        LatLng(camera.points.first().first, camera.points.first().second),
        12f,
    )
}

/**
 * Moves the camera to the plan. `newLatLngBounds` takes PIXELS, so the 48 dp padding is converted
 * with the display density (SDK minor 18). A failure (e.g. a zero-sized map) leaves the camera where
 * it is rather than taking the step down.
 */
private fun applyPlanCamera(state: CameraPositionState, camera: MapInteractivePlan.Camera, density: Float) {
    runCatching {
        val update = when (camera) {
            is MapInteractivePlan.Camera.Center ->
                CameraUpdateFactory.newLatLngZoom(LatLng(camera.lat, camera.lng), camera.zoom.toFloat())
            is MapInteractivePlan.Camera.Fit -> {
                val bounds = LatLngBounds.builder().apply {
                    camera.points.forEach { include(LatLng(it.first, it.second)) }
                }.build()
                CameraUpdateFactory.newLatLngBounds(bounds, (MapInteractivePlan.FIT_PADDING_DP * density).roundToInt())
            }
        }
        state.move(update)
    }.onFailure { Log.debug("map: camera not applied — ${it.message}") }
}

internal const val MAP_LOAD_WATCHDOG_MS = 15_000L

internal const val MAP_NOT_LOADED_MESSAGE =
    "Interactive map: the Google map did not finish loading within 15 s, so it is probably grey. " +
        "The usual cause is the key in com.google.android.geo.API_KEY: enable \"Maps SDK for Android\" " +
        "for its Google Cloud project (APIs & Services → Library), and if the key has an Android app " +
        "restriction, add this app's package name and signing SHA-1. Look for \"Authorization failure\" " +
        "from the Maps SDK in logcat. (A slow or missing network causes this too.)"

/**
 * Waits [timeoutMs] and logs [MAP_NOT_LOADED_MESSAGE] as an error when the map has not reported
 * `onMapLoaded` by then. Returns whether it logged. Log only — never a fallback: a slow network
 * looks the same, and swapping a map that is still loading for the still image would be worse.
 */
internal suspend fun mapLoadWatchdog(
    isLoaded: () -> Boolean,
    timeoutMs: Long = MAP_LOAD_WATCHDOG_MS,
    log: (String) -> Unit = { Log.error(it) },
): Boolean {
    kotlinx.coroutines.delay(timeoutMs)
    if (isLoaded()) return false
    log(MAP_NOT_LOADED_MESSAGE)
    return true
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
