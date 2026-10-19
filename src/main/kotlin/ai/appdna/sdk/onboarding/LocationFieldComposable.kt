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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val minChars = search.minChars
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
     * Both halves move together or the surfaces disagree again.
     */
    val locationType = search.type
    val biasCountry = search.biasCountry
    val language = search.language

    val initialText = (values[field.id] as? Map<*, *>)?.get("address") as? String
        ?: values[field.id]?.toString().orEmpty()
    var text by remember { mutableStateOf(initialText) }
    var suggestions by remember { mutableStateOf<List<LocationSuggestion>>(emptyList()) }
    var showSuggestions by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<Job?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { newText ->
                text = newText
                values[field.id] = LocationAnswer.typed(newText)
                errors.remove(field.id)
                debounceJob?.cancel()
                if (newText.length >= minChars) {
                    debounceJob = coroutineScope.launch {
                        delay(300)
                        isSearching = true
                        suggestions = fetchLocationSuggestions(
                            query = newText,
                            locationType = locationType,
                            biasCountry = biasCountry,
                            language = language,
                        )
                        showSuggestions = suggestions.isNotEmpty()
                        isSearching = false
                    }
                } else {
                    suggestions = emptyList()
                    showSuggestions = false
                }
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
                    isSearching -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    values[field.id] is Map<*, *> -> IconButton(
                        onClick = {
                            text = ""
                            values[field.id] = null
                            suggestions = emptyList()
                            showSuggestions = false
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
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = errors.containsKey(field.id),
        )

        if (showSuggestions) {
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
                suggestions.forEachIndexed { index, suggestion ->
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
                                // The one stored shape on both platforms and
                                // both writers (LocationAnswer); the typed text is the raw_query.
                                val typed = text
                                text = suggestion.address
                                showSuggestions = false
                                values[field.id] = LocationAnswer.selection(suggestion, typed)
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
            } }
        }
    }
}

/**
 * Calls `/api/v1/sdk/geocode/autocomplete?q=…` (host comes from the
 * configured environment) and forwards the SDK API key via `x-api-key`.
 * The ONLY location-autocomplete call in the SDK: the form-step Location field and the
 * `input_location` content block both go through here (#963 deleted the content block's own copy,
 * which dropped every parameter below).
 */
internal suspend fun fetchLocationSuggestions(
    query: String,
    /** Null or blank = the author chose none, and the server must not be told one. */
    locationType: String?,
    biasCountry: String?,
    language: String?,
): List<LocationSuggestion> {
    return try {
        val baseUrl = ai.appdna.sdk.AppDNA.getApiBaseUrl()
        val apiKey = ai.appdna.sdk.AppDNA.getApiKey()
        val url = java.net.URL("$baseUrl/api/v1/sdk/geocode/autocomplete")
        // Backend route at
        // `src/app/api/v1/sdk/geocode/autocomplete/route.ts:27` exports
        // ONLY POST (Zod-validated body with `query`/`bias_country`).
        // Android was sending GET with `?q=…` query params → 405 Method
        // Not Allowed → silent emptyList → suggestions never appeared on
        // Android while iOS rendered them correctly. Now matches iOS
        // `LocationFieldView.swift:197-234` which POSTs the JSON body
        // `{query, limit, type, bias_country, language}`.
        val requestBody = org.json.JSONObject().apply {
            put("query", query)
            put("limit", 5)
            if (!locationType.isNullOrBlank()) put("type", locationType)
            if (!biasCountry.isNullOrBlank()) put("bias_country", biasCountry)
            if (!language.isNullOrBlank()) put("language", language)
        }.toString()
        val body: String? = withContext(Dispatchers.IO) {
            val connection = (url.openConnection() as? java.net.HttpURLConnection)?.apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (apiKey != null) setRequestProperty("x-api-key", apiKey)
                // The server answers null for a missing coordinate / zone only to an SDK that says it
                // reads null (Android >= 1.0.54); without these headers it sends the legacy 0 / "UTC" shape.
                setRequestProperty("x-sdk-version", ai.appdna.sdk.AppDNA.sdkVersion)
                setRequestProperty("x-sdk-platform", "android")
            } ?: return@withContext null
            try {
                connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode != 200) return@withContext null
                connection.inputStream.bufferedReader().readText()
            } finally {
                runCatching { connection.disconnect() }
            }
        }
        if (body == null) return emptyList()
        // Backend wraps suggestions in
        // `{data: {suggestions: [...]}}` (route.ts:65); iOS reads
        // `json.data.suggestions` (LocationFieldView.swift:220-221).
        // Android was reading `json.data` as JSONArray which yields null
        // because `data` is an object. Now matches iOS shape.
        val json = JSONObject(body)
        val results = json.optJSONObject("data")?.optJSONArray("suggestions") ?: return emptyList()
        (0 until minOf(results.length(), 5)).mapNotNull { i ->
            val item = results.optJSONObject(i) ?: return@mapNotNull null
            // Same parser as the content block; `raw_query` comes from the typed text at selection.
            parseLocationSuggestion(item)
        }
    } catch (e: Exception) {
        ai.appdna.sdk.Log.debug("LocationFieldComposable autocomplete failed: ${e.message}")
        emptyList()
    }
}
