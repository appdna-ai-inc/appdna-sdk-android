package ai.appdna.sdk.onboarding

/**
 * One geocoding autocomplete suggestion, as the server's `data.suggestions` items carry it. Parsed by
 * [parseLocationSuggestion]; stored by [LocationAnswer.selection].
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
    )
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
