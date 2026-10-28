package ai.appdna.sdk.onboarding

/**
 * One geocoding autocomplete suggestion, as the server's `data.suggestions` items carry it, or a resolve
 * reply's `suggestion`. Parsed by [parseLocationSuggestion]; stored by [LocationAnswer.selection].
 *
 * Internal on purpose: the suggestion's own [id] / [provider] ride here for the tap path and are never
 * written by [LocationAnswer.selection], so the public [LocationData] (and `getLocationData`) cannot
 * expose a suggestion id. [mapboxId] / [placeId] are the provenance of an answer a resolve reply
 * produced; a listed suggestion never carries them ([decodeLocationSuggestions]).
 */
internal data class LocationSuggestion(
    val address: String,
    /** Null when the lookup had no finite coordinate: the stored answer then omits it. */
    val latitude: Double?,
    val longitude: Double?,
    val city: String? = null,
    val state: String? = null,
    val stateCode: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val timezone: String? = null,
    /** Minutes east of UTC, as the server's time-zone lookup reports it. */
    val timezoneOffset: Int? = null,
    val postalCode: String? = null,
    val rawQuery: String? = null,
    /** The suggestion's own id, sent to the resolve route on a tap. Null = it cannot be resolved. */
    val id: String? = null,
    /** `mapbox` / `google`: which resolve the tap asks for. */
    val provider: String? = null,
    /** A Mapbox answer resolved permanently. Only a resolve reply fills it. */
    val mapboxId: String? = null,
    /** A Google answer resolved by place id. Only a resolve reply fills it. */
    val placeId: String? = null,
)

/**
 * One autocomplete item → [LocationSuggestion], read with the rules `getLocationData` and iOS
 * `LocationData.fromStoredAnswer` use: a string field is present only when it IS a non-blank string, a
 * coordinate only when it IS a finite number (a numeric string is not one — iOS never read it as one),
 * `timezone_offset` only when it is a number. A wrong-typed field is absent; the suggestion is kept.
 * A real 0.0 / 0.0 from the server is a coordinate.
 */
internal fun parseLocationSuggestion(item: org.json.JSONObject): LocationSuggestion {
    fun str(key: String): String? = (item.opt(key) as? String)?.takeIf { it.isNotBlank() }
    return LocationSuggestion(
        address = (item.opt("formatted_address") as? String) ?: (item.opt("address") as? String) ?: "",
        latitude = suggestionCoordinate(item, "latitude"),
        longitude = suggestionCoordinate(item, "longitude"),
        city = str("city"),
        state = str("state"),
        stateCode = str("state_code"),
        country = str("country"),
        countryCode = str("country_code"),
        timezone = str("timezone"),
        timezoneOffset = (item.opt("timezone_offset") as? Number)?.toInt(),
        postalCode = str("postal_code"),
        rawQuery = str("raw_query"),
        id = str("id"),
        provider = str("provider"),
        mapboxId = str("mapbox_id"),
        placeId = str("place_id"),
    )
}

/**
 * The autocomplete list → suggestions, through [parseLocationSuggestion], at most five.
 *
 * 🔴 A listed suggestion NEVER carries `mapbox_id` / `place_id`, whatever keys it has: those mark an
 * answer a resolve reply produced, and a listed suggestion is a temporary result that may not be stored.
 * A display-only suggestion (`{id, provider, formatted_address, name, place_formatted}`) decodes to its
 * display line, its id and its provider.
 */
internal fun decodeLocationSuggestions(items: org.json.JSONArray): List<LocationSuggestion> =
    (0 until minOf(items.length(), 5)).mapNotNull { i ->
        items.optJSONObject(i)?.let { parseLocationSuggestion(it).copy(mapboxId = null, placeId = null) }
    }

/** An autocomplete item's coordinate: a finite number, else null (absent, null, non-numeric, NaN). */
internal fun suggestionCoordinate(item: org.json.JSONObject, key: String): Double? =
    (item.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }

/**
 * What a location field stores as its answer. One rule for both writers (the form-step
 * Location field and the `input_location` content block) and for both platforms (iOS
 * `LocationAnswer.swift`); the `location_answer_from_input` shared fixtures pin it.
 *
 * - Typed but not selected: the typed `String`, as typed.
 * - A selection: `formatted_address` (and `address`, its legacy alias) plus each of `city`, `state`,
 *   `state_code`, `country`, `country_code`, `latitude`, `longitude`, `timezone`, `timezone_offset`,
 *   `postal_code` and `raw_query` ONLY when the lookup has it — a blank string, a non-finite coordinate
 *   or a failed time-zone lookup is left out. `timezone_offset` is kept only together with a
 *   `timezone`. `raw_query` is the text the user typed to find the place (the suggestion's own
 *   `raw_query` is the fallback).
 */
internal object LocationAnswer {

    fun typed(text: String): String = text

    fun selection(s: LocationSuggestion, typedQuery: String?): Map<String, Any> = buildMap {
        fun String?.present(): String? = this?.takeIf { it.isNotBlank() }
        put("formatted_address", s.address)
        put("address", s.address)
        s.city.present()?.let { put("city", it) }
        s.state.present()?.let { put("state", it) }
        s.stateCode.present()?.let { put("state_code", it) }
        s.country.present()?.let { put("country", it) }
        s.countryCode.present()?.let { put("country_code", it) }
        s.latitude?.takeIf { it.isFinite() }?.let { put("latitude", it) }
        s.longitude?.takeIf { it.isFinite() }?.let { put("longitude", it) }
        s.timezone.present()?.let { tz ->
            put("timezone", tz)
            s.timezoneOffset?.let { put("timezone_offset", it) }
        }
        s.postalCode.present()?.let { put("postal_code", it) }
        (typedQuery.present() ?: s.rawQuery.present())?.let { put("raw_query", it) }
        s.mapboxId.present()?.let { put("mapbox_id", it) }
        s.placeId.present()?.let { put("place_id", it) }
    }

    /**
     * The ONE rule for the text a location field shows for a stored answer, on both Android surfaces,
     * after a tap and on a restore alike: a selection shows its `formatted_address` (else the legacy
     * `address`), a typed answer shows itself. Anything else (absent, empty, a number) shows nothing
     * (null). `LocationAnswer.fieldText(forStored:)` in `LocationAnswer.swift` is the Swift twin, and
     * the `location_field_text` fixtures pin the pair.
     */
    /**
     * Whether a stored answer came from Google: a selection carrying a non-blank `place_id`, which
     * only a Google resolve reply fills. Google's terms require attribution wherever such an answer's
     * content is shown without a Google map, so this one rule decides the logo kept beside a field,
     * the credit an onboarding step draws for text templating the answer, and the typed text every
     * other surface's templates show in its place. `LocationAnswer.carriesPlaceId` is the Swift twin.
     */
    fun carriesPlaceId(value: Any?): Boolean =
        ((value as? Map<*, *>)?.get("place_id") as? String)?.isNotBlank() == true

    fun fieldText(stored: Any?): String? = when (stored) {
        is String -> stored.ifEmpty { null }
        is Map<*, *> -> (stored["formatted_address"] as? String)?.ifEmpty { null }
            ?: (stored["address"] as? String)?.ifEmpty { null }
        else -> null
    }

    /**
     * The two lines one autocomplete suggestion shows in the dropdown.
     *
     * 🔴 ONE RULE FOR EVERY SURFACE AND BOTH PLATFORMS (#963). This used to be four rules: the two
     * Android surfaces each split the address on its first comma — the form-step field with a
     * city/country fallback and the content block without — while on iOS the content block took
     * MapKit's own `title`/`subtitle` and the form-step field showed `city` over
     * `"state, country"`. Routing the iOS content block through the geocoding proxy removed
     * MapKit's pre-split strings and so required picking a rule; picking it once, here, is what
     * stops a fifth appearing. `LocationAnswer.displayLines` in `LocationAnswer.swift` is the Swift
     * twin, and `location_suggestion_display_lines` pins the pair.
     *
     * `formatted_address` is a comma-separated address, so its first segment is the place and the
     * remainder is where it is. The `city, country` fallback covers a single-segment address (a
     * country-level suggestion), which would otherwise show one line and no context.
     */
    fun displayLines(s: LocationSuggestion): Pair<String, String> {
        val parts = s.address.split(",", limit = 2)
        val title = parts.getOrNull(0)?.trim().orEmpty().ifEmpty { s.address }
        val subtitle = parts.getOrNull(1)?.trim().orEmpty()
            .ifEmpty {
                // A fallback part equal to the title is dropped: a single-segment city suggestion
                // would otherwise read "Kyoto / Kyoto, Japan".
                listOfNotNull(s.city?.trim()?.ifEmpty { null }, s.country?.trim()?.ifEmpty { null })
                    .filter { it != title }
                    .joinToString(", ")
            }
        return title to subtitle
    }
}

/**
 * The four Console settings that shape a location autocomplete search, resolved once for whichever
 * surface is asking.
 *
 * 🔴 EXTRACTED BECAUSE THE DUPLICATION WAS THE DEFECT (#963). The form-step Location field read all
 * four keys and the `input_location` content block read none of them — it had grown its own copy of
 * the search call that dropped every parameter, so an author who set Type = "City" on a content
 * block got street addresses, silently. One resolver, used by both surfaces, is what makes that
 * impossible to repeat rather than merely fixed once.
 *
 * `defaultMinChars` differs per surface on purpose: the form-step field has always used 2 and the
 * content block 3 (which is also what the Console seeds). Preserving each keeps a block that
 * authored nothing on the threshold it has today.
 */
internal data class LocationSearchSettings(
    /**
     * 🔴 NULLABLE, and null means the author never chose one — which is NOT the same as "address".
     * The server narrows results only when `type` is present (`GeocodingService.ts:230-232`,
     * `:318-323`), so substituting a default here would silently change what every unauthored field
     * returns. Each surface applies its own historical default at the call site.
     */
    val type: String?,
    val biasCountry: String?,
    val language: String?,
    val minChars: Int,
) {
    companion object {
        /**
         * The only values the geocoding proxy accepts
         * (`/api/v1/sdk/geocode/autocomplete` → `z.enum(['city','address','region','country'])`).
         *
         * 🔴 A TYPE WE CANNOT USE IS TREATED AS ABSENT, NOT PASSED ON. The proxy answers an unknown
         * `type` with a ValidationError, and both SDKs read a non-200 as "no suggestions" — so
         * forwarding a bad value turns the whole autocomplete OFF rather than degrading it.
         *
         * Not hypothetical. Content-block `field_config` is `.passthrough()` and therefore
         * UNVALIDATED, and the Console's content-block Location Type select offered
         * **`coordinates`** — in no enum, no SDK, and refused by the proxy. Those blocks were
         * harmless only because this surface sent no `type` at all; the moment it started honouring
         * the authored one (#963) every one of them would have gone silently dead. The picker is
         * fixed too (#964), but published documents outlive a picker fix.
         */
        private val PROXY_TYPES = setOf("city", "address", "region", "country")

        private fun acceptableType(raw: String?): String? =
            raw?.takeIf { it.isNotBlank() }?.takeIf { it in PROXY_TYPES }

        /** From a content block's free-form `field_config` map. */
        fun from(config: Map<String, Any?>?, defaultMinChars: Int): LocationSearchSettings =
            LocationSearchSettings(
                type = acceptableType(config?.get("location_type") as? String),
                biasCountry = (config?.get("location_bias_country") as? String)?.takeIf { it.isNotBlank() },
                language = (config?.get("location_language") as? String)?.takeIf { it.isNotBlank() },
                // A threshold below 1 would search on an empty field, which is a request the proxy
                // rejects and a dropdown the user never asked for.
                minChars = ((config?.get("location_min_chars") as? Number)?.toInt() ?: defaultMinChars)
                    .coerceAtLeast(1),
            )

        /** From a form-step field's typed `FormFieldConfig`. */
        fun from(config: FormFieldConfig?, defaultMinChars: Int): LocationSearchSettings =
            LocationSearchSettings(
                type = acceptableType(config?.location_type),
                biasCountry = config?.location_bias_country?.takeIf { it.isNotBlank() },
                language = config?.location_language?.takeIf { it.isNotBlank() },
                minChars = (config?.location_min_chars ?: defaultMinChars).coerceAtLeast(1),
            )
    }
}
