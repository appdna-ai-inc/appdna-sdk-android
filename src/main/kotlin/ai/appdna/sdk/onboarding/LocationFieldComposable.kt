package ai.appdna.sdk.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Reusable location-autocomplete composable for legacy
 * form-step `FormFieldType.LOCATION` fields. Ports the autocomplete logic
 * previously embedded inline in
 * `ContentBlockRenderer.kt:3840-3974 FormInputLocationPlaceholder` so the
 * legacy onboarding `FormStepComposable` can use the same UI fidelity that
 * block-based rendering already enjoys.
 *
 * Wires three [FormFieldConfig] knobs:
 *   - `location_type` — surfaced as an `x-location-type` query header so
 *     the backend can return only the requested level (city / region /
 *     country / address). Falls back to "address".
 *   - `location_bias_country` — ISO 3166-1 alpha-2 country bias hint
 *     (`country=us`).
 *   - `location_language` — BCP-47 language tag for response strings
 *     (`language=fr-FR`).
 *   - `location_min_chars` — minimum keystrokes before triggering the
 *     /api/v1/sdk/geocode/autocomplete debounce (default 3).
 *
 * The IME action is `Search` so the keyboard's enter key submits the
 * query. Mirrors iOS `Onboarding/OnboardingStepViews/LocationFieldView.swift`.
 */
@Composable
fun LocationFieldComposable(
    field: FormField,
    values: MutableMap<String, Any?>,
    errors: MutableMap<String, String>,
    modifier: Modifier = Modifier,
) {
    val config = field.config
    // iOS LocationFieldView.swift:43-44
    // defaults to 2 keystrokes before triggering autocomplete. Android
    // was at 3, delaying suggestions on short city names.
    // #963 — resolved by the shared LocationSearchSettings, the same one the `input_location`
    // content block uses, so the two surfaces cannot drift apart again.
    val search = LocationSearchSettings.from(config, defaultMinChars = 2)
    val placeholder = config?.location_placeholder
        ?: field.placeholder
        ?: "Search for a location..."
    /*
     * #964 — an unauthored type sends NOTHING, as iOS already does.
     *
     * This was `?: "address"`, and that substitution was the third of three answers to one
     * question. The proxy filters only when the key is PRESENT (`GeocodingService.ts:230-232`), so
     * iOS's form-step field sent nothing and the user got an unfiltered search; Android sent
     * `"address"` and the same authored field returned street addresses only; and the Console's own
     * select displayed **City**, which nobody implemented. A flow authored once rendered
     * differently on the two platforms — the divergence class ADR-001 exists to prevent.
     *
     * The Console half shipped first: the select now carries an explicit "Any (no filter)" and
     * shows it when the key is absent, so it states the behaviour it will publish instead of a
     * default it never wrote. This is the other half, and without it the Console would be telling
     * the truth about iOS and a lie about Android.
     *
     * 🔴 THIS CHANGES LIVE RESULTS, on purpose, and only here: an Android form-step Location field
     * whose author set no type starts returning unfiltered suggestions instead of addresses only.
     * That population is the one already getting a result nobody chose. A field that authored
     * `address` is untouched, and so is the `input_location` content block, which seeds `address`
     * explicitly (`StepContentEditor.tsx`) and is therefore truthful already.
     *
     * To reverse: restore `?: "address"` here and change the Console's unauthored display back.
     * Both halves move together or the surfaces disagree again. (`search.type` is what the holder
     * sends, null when unauthored.)
     */

    // The ONE location state holder, shared with the `input_location` content block: it owns the field
    // text, the list (each row with the request that listed it), the dropdown, the tap → resolve and the
    // answer it writes into `values`.
    val coroutineScope = rememberCoroutineScope()
    val holder = remember(field.id) { LocationSuggestionSearch.live(values[field.id], coroutineScope) }
    // The restore is reactive: `FormStep` fills `values` in a `LaunchedEffect` AFTER this field's first
    // composition (a key-less `remember` of the text read it as "" and never looked again), so every
    // composition reports the stored answer and the holder restores it on its absent → present change.
    // The holder is a plain class: it bumps this counter when it changes, and reading it below is what
    // recomposes the field. Keyed on the holder only: a map key could compare by content and restart
    // on every write.
    var holderVersion by remember(holder) { mutableIntStateOf(0) }
    DisposableEffect(holder) {
        holder.onChange = { holderVersion++ }
        holder.attach { answer -> values[field.id] = answer }
        onDispose {
            holder.onChange = null
            holder.detach()
        }
    }
    // The form step settles this field when Continue advances (a resolve in flight stores the typed
    // text); `onDispose` above runs only after the answers were submitted.
    val settlement = LocalLocationAdvanceSettlement.current
    DisposableEffect(holder, settlement) {
        settlement?.register(field.id, holder)
        onDispose { settlement?.unregister(holder) }
    }
    // Leaving the field ends its search: a debounced search still pending would otherwise fire and
    // open the dropdown under a field the user has already left.
    var hadFocus by remember(holder) { mutableStateOf(false) }
    val stored = values[field.id]
    SideEffect { holder.storedAnswerChanged(stored) }
    @Suppress("UNUSED_EXPRESSION")
    holderVersion
    val text = holder.text
    val suggestions = holder.results

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { newText ->
                errors.remove(field.id)
                // Stores the typed text, drops a resolve in flight, and debounces the search.
                holder.textChanged(newText, search)
            },
            label = { Text(field.label) },
            placeholder = { Text(placeholder) },
            // Opt-out for the prefix location
            // pin matches iOS LocationFieldView.swift:1384 reading
            // `field_config["show_prefix_icon"]` (default true). Some flows
            // hide the pin in dark/minimalist designs.
            leadingIcon = if (config?.show_prefix_icon != false) {
                {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else null,
            trailingIcon = {
                // Clear-X button when a
                // location is selected (`values[field.id]` is a Map).
                // iOS LocationFieldView.swift:81-87 + clearSelection() at
                // :272 lets users clear with one tap. Without this Android
                // user can't clear after select except by manually deleting
                // every char of the formatted address.
                when {
                    // A search, or a tap's resolve, is in flight.
                    holder.isSearching || holder.isResolving -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    values[field.id] is Map<*, *> -> IconButton(
                        onClick = {
                            holder.clear()
                            errors.remove(field.id)
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Cancel,
                            contentDescription = "Clear location",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
            // Search IME action so Enter triggers a
            // keyboard-driven submit (autocomplete still fires on debounce).
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search,
            ),
            modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
                if (state.isFocused) {
                    hadFocus = true
                } else if (hadFocus) {
                    hadFocus = false
                    holder.editingEnded()
                }
            },
            singleLine = true,
            isError = errors.containsKey(field.id),
        )

        if (holder.isDropdownOpen) {
            // Material3 Card elevation replaces the
            // 1px border. Elevation is the natural Android cue for a
            // floating menu, matching iOS `LocationFieldView.swift:55`'s
            // shadow + stroke combo.
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) { Column(modifier = Modifier.fillMaxWidth()) {
                suggestions.forEachIndexed { index, listed ->
                    val suggestion = listed.suggestion
                    // Material3 ListItem primary/secondary
                    // text hierarchy parity with iOS LocationFieldView's
                    // two-line cells. Primary = first comma-segment of the
                    // formatted address (street/POI), secondary = the
                    // remainder so users can disambiguate "Springfield, IL"
                    // vs "Springfield, MA" at a glance.
                    // #963 — the rule itself is `LocationAnswer.displayLines`, shared with the
                    // content block and with both iOS surfaces, so the four copies that used to
                    // disagree on the fallback cannot drift again.
                    val (primary, secondary) = LocationAnswer.displayLines(suggestion)
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // A tap ends the search (the pending one is cancelled, the dropdown
                                // closes) and resolves the suggestion; the holder stores the answer.
                                holder.select(listed)
                            },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Filled.LocationOn,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        headlineContent = {
                            Text(
                                text = primary,
                                fontSize = 14.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            )
                        },
                        supportingContent = if (secondary.isNotBlank()) {
                            { Text(text = secondary, fontSize = 12.sp) }
                        } else null,
                        colors = ListItemDefaults.colors(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    )
                    if (index < suggestions.size - 1) {
                        // Material3 1.2 deprecated `Divider`
                        // in favor of `HorizontalDivider`. Switch matches the
                        // R22 LinearProgressIndicator lambda-form pattern.
                        // Theme-aware divider
                        // (was Color.LightGray, faintly visible in dark mode).
                        // Matches iOS native Divider() inheriting separator.
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    }
                }
                // Google's attribution under Google suggestions, inside the dropdown: the list is
                // Google content shown without a Google map. The dropdown's surface is the theme's,
                // so the plain variant follows the appearance.
                if (holder.showsGoogleLogo) {
                    GoogleMapsLogo(backdrop = GoogleMapsAttribution.Backdrop.Plain(null))
                }
            } }
        } else if (LocationAnswer.carriesPlaceId(values[field.id])) {
            // A Google answer keeps its attribution after the dropdown closes, and when it is restored
            // on returning to the step: the field shows Google content.
            GoogleMapsLogo(backdrop = LocalGoogleAttributionBackdrop.current)
        }
    }
}

/** One geocode proxy reply: the HTTP status and the body text (the error body for a non-2xx). */
internal class GeocodeHttpReply(val status: Int, val body: String)

/**
 * The socket under both geocode requests (autocomplete and resolve). A test seam: production always uses
 * [LiveGeocodeTransport]; a test answers from a stub and reads what was sent.
 */
internal fun interface GeocodeTransport {
    /** POSTs [body] to [url] with [headers]. Throws on a network failure. */
    suspend fun post(url: String, headers: Map<String, String>, body: String): GeocodeHttpReply
}

/** [GeocodeTransport] over `HttpURLConnection`, on the IO dispatcher. */
internal object LiveGeocodeTransport : GeocodeTransport {
    override suspend fun post(url: String, headers: Map<String, String>, body: String): GeocodeHttpReply =
        withContext(Dispatchers.IO) {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doOutput = true
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            try {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                GeocodeHttpReply(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } finally {
                runCatching { connection.disconnect() }
            }
        }
}

/**
 * The transport both location composables' requests use. Always [LiveGeocodeTransport] in production; a
 * rendered-composable test points it at a stub (and resets it) to drive a real tap through the view.
 */
@Volatile
internal var locationGeocodeTransport: GeocodeTransport = LiveGeocodeTransport

/** A geocode proxy route on the configured environment's host. */
internal fun geocodeUrl(path: String): String = "${ai.appdna.sdk.AppDNA.getApiBaseUrl()}$path"

/**
 * `POST /api/v1/sdk/geocode/autocomplete` → at most five suggestions, each keeping its own `id` /
 * `provider` for the tap. A failure is an empty list, never a throw: an autocomplete that cannot reach
 * the server must leave the field typable rather than break the step.
 *
 * The ONLY location-autocomplete call in the SDK: the form-step Location field and the `input_location`
 * content block both reach it through [LocationSuggestionSearch]. The body is `listing` exactly as built
 * ([LocationListingRequest.autocompleteBody]: `{query, limit, type?, bias_country?, language?}`), so the
 * holder can keep it with the list it produced. The proxy wraps the list as `{data: {suggestions: [...]}}`.
 */
internal suspend fun fetchLocationSuggestionItems(
    listing: LocationListingRequest,
    transport: GeocodeTransport = locationGeocodeTransport,
): List<LocationSuggestion> {
    return try {
        val url = geocodeUrl("/api/v1/sdk/geocode/autocomplete")
        val headers = locationAutocompleteHeaders(ai.appdna.sdk.AppDNA.getApiKey())
        val reply = transport.post(url, headers, listing.autocompleteBody().toString())
        if (reply.status != 200) return emptyList()
        val results = JSONObject(reply.body).optJSONObject("data")?.optJSONArray("suggestions")
            ?: return emptyList()
        // Same parser as every other location reader; `raw_query` comes from the typed text at selection.
        decodeLocationSuggestions(results)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ai.appdna.sdk.Log.debug("Location autocomplete failed: ${e.message}")
        emptyList()
    }
}

/**
 * `POST /api/v1/sdk/geocode/resolve` — the ONE resolve call in the Android SDK, used by both surfaces
 * through [LocationSuggestionSearch]. `LocationResolve` in `LocationAutocomplete.swift` is the Swift twin.
 */
internal object LocationResolve {

    /**
     * Sends `body` (built by [LocationSuggestionSearch.resolveRequest]) and reads the reply. Never
     * throws: every failure is [LocationResolveOutcome.Failed], and the field stores the typed text.
     */
    suspend fun send(
        body: Map<String, String>,
        transport: GeocodeTransport = locationGeocodeTransport,
    ): LocationResolveOutcome {
        return try {
            val url = geocodeUrl("/api/v1/sdk/geocode/resolve")
            // The same two headers as autocomplete: the server reads the build from them.
            val headers = locationAutocompleteHeaders(ai.appdna.sdk.AppDNA.getApiKey())
            val reply = transport.post(url, headers, JSONObject(body).toString())
            outcome(reply.status, reply.body)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ai.appdna.sdk.Log.debug("Location resolve failed: ${e.message}")
            LocationResolveOutcome.Failed("network")
        }
    }

    /**
     * The route's reply → an outcome. A success must carry a non-empty display line AND a provenance id
     * (`mapbox_id`, or Google's `place_id`): the route always sends one, and a "resolved" answer without
     * it would be stored on the device as if it were permanent while the server treats it as one from an
     * older SDK — so it fails closed, to typed text.
     */
    fun outcome(status: Int, body: String): LocationResolveOutcome {
        if (status !in 200..299) return LocationResolveOutcome.Failed("http_$status")
        val reply = runCatching { JSONObject(body).optJSONObject("data") }.getOrNull()
            ?: return LocationResolveOutcome.Failed("unparseable")
        val resolved = reply.opt("resolved") as? Boolean ?: return LocationResolveOutcome.Failed("unparseable")
        if (!resolved) return LocationResolveOutcome.Refused((reply.opt("reason") as? String) ?: "unknown")
        val suggestion = reply.optJSONObject("suggestion") ?: return LocationResolveOutcome.Failed("unparseable")
        val location = parseLocationSuggestion(suggestion)
        if (location.address.isBlank() || (location.mapboxId == null && location.placeId == null)) {
            return LocationResolveOutcome.Failed("unparseable")
        }
        return LocationResolveOutcome.Resolved(location)
    }
}

/**
 * The headers of a location autocomplete or resolve request. The server answers null for a missing coordinate / zone only to an
 * SDK that says it reads null (Android >= 1.0.54); without `x-sdk-version` / `x-sdk-platform` it sends the legacy
 * 0 / "UTC" shape, and it decides from them which builds are shown Google suggestions at all, and which are shown
 * display-only suggestions they resolve on a tap.
 */
internal fun locationAutocompleteHeaders(apiKey: String?): Map<String, String> = buildMap {
    put("Content-Type", "application/json")
    if (apiKey != null) put("x-api-key", apiKey)
    put("x-sdk-version", ai.appdna.sdk.AppDNA.sdkVersion)
    put("x-sdk-platform", "android")
}
