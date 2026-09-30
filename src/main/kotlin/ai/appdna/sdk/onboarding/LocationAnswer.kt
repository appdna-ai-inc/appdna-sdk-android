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
 * SPEC-497 §13h — what a location field stores as its answer. One rule for both writers (the form-step
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
