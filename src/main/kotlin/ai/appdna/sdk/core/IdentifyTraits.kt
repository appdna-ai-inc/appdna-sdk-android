package ai.appdna.sdk.core

import ai.appdna.sdk.Log
import ai.appdna.sdk.onboarding.LocationData
import org.json.JSONArray
import org.json.JSONObject

/**
 * The trait values `identify` can carry: JSON values, and the SDK's own [LocationData].
 *
 * A [LocationData] becomes the answer map a location field stores, the same map the onboarding
 * `responses` hold for it (snake_case keys, only the keys the answer has), so the server treats it as a
 * location answer. Any other value that is not a JSON value (a string, a finite number, a boolean, a map
 * with string keys, a list, or a JSON object or array built from them) is dropped with a warning naming
 * its trait key, and only that key. It is never sent as `null`, which is what `JSONObject` did with it.
 * The Swift twin is `IdentifyTraits` in `IdentifyTraits.swift`; the Flutter facade applies the same
 * rule before its channel call.
 */
internal object IdentifyTraits {

    /**
     * [traits] with every value JSON-safe: a [LocationData] as its answer map, and any other non-JSON
     * value dropped. [log] says which keys were dropped (the public `identify` logs; the identity store,
     * which sanitises again before persisting, does not).
     */
    fun sanitize(traits: Map<String, Any>?, log: Boolean = true): Map<String, Any>? {
        traits ?: return null
        val out = LinkedHashMap<String, Any>()
        val dropped = mutableListOf<String>()
        for ((key, value) in traits) {
            val json = jsonValue(value)
            if (json == null) dropped += key else out[key] = json
        }
        if (log && dropped.isNotEmpty()) {
            Log.warning {
                "identify: trait(s) $dropped dropped — a trait value must be a JSON value (string, number, " +
                    "boolean, map, list) or LocationData"
            }
        }
        return out
    }

    /** [location] as the answer map a location field stores: `formatted_address` and each set field. */
    fun answerMap(location: LocationData): Map<String, Any> = buildMap {
        put("formatted_address", location.formatted_address)
        location.city?.let { put("city", it) }
        location.state?.let { put("state", it) }
        location.state_code?.let { put("state_code", it) }
        location.country?.let { put("country", it) }
        location.country_code?.let { put("country_code", it) }
        location.latitude?.takeIf { it.isFinite() }?.let { put("latitude", it) }
        location.longitude?.takeIf { it.isFinite() }?.let { put("longitude", it) }
        location.timezone?.let { put("timezone", it) }
        location.timezone_offset?.let { put("timezone_offset", it) }
        location.postal_code?.let { put("postal_code", it) }
        location.raw_query?.let { put("raw_query", it) }
        location.mapbox_id?.let { put("mapbox_id", it) }
        location.place_id?.let { put("place_id", it) }
    }

    /** [value] as a JSON-safe value, or null when it cannot be one (anywhere inside it). */
    private fun jsonValue(value: Any?): Any? = when (value) {
        null -> null
        is String, is Boolean -> value
        is Double -> value.takeIf { it.isFinite() }
        is Float -> value.takeIf { it.isFinite() }
        is Number -> value
        is LocationData -> answerMap(value)
        is JSONObject, is JSONArray -> value
        is Map<*, *> -> {
            val out = LinkedHashMap<String, Any>()
            var ok = true
            for ((k, v) in value) {
                val json = jsonValue(v)
                if (k !is String || json == null) { ok = false; break }
                out[k] = json
            }
            if (ok) out else null
        }
        is Iterable<*> -> value.map { jsonValue(it) ?: return null }
        is Array<*> -> value.map { jsonValue(it) ?: return null }
        else -> null
    }
}
