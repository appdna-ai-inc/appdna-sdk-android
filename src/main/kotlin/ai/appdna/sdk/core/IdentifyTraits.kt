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
 * A null IS a JSON value: a Kotlin `null` or `JSONObject.NULL`, as a trait or anywhere inside one, is
 * kept as `JSONObject.NULL`, which serialises as JSON `null` (the React Native bridge passes every JS
 * `null` that way, and the object its `getLocationData` resolves holds one for each unset key).
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
            if (json === Unencodable) dropped += key else out[key] = json
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

    /** Marks a value that cannot be JSON; distinct from a null, which can. */
    private object Unencodable

    /** [value] as a JSON-safe value, or [Unencodable] when it cannot be one (anywhere inside it). */
    private fun jsonValue(value: Any?): Any = when (value) {
        null, JSONObject.NULL -> JSONObject.NULL
        is String, is Boolean -> value
        is Double -> if (value.isFinite()) value else Unencodable
        is Float -> if (value.isFinite()) value else Unencodable
        is Number -> value
        is LocationData -> answerMap(value)
        is JSONObject, is JSONArray -> value
        is Map<*, *> -> {
            val out = LinkedHashMap<String, Any>()
            var ok = true
            for ((k, v) in value) {
                val json = jsonValue(v)
                if (k !is String || json === Unencodable) { ok = false; break }
                out[k] = json
            }
            if (ok) out else Unencodable
        }
        is Iterable<*> -> list(value)
        is Array<*> -> list(value.asIterable())
        else -> Unencodable
    }

    private fun list(items: Iterable<*>): Any {
        val out = ArrayList<Any>()
        for (item in items) {
            val json = jsonValue(item)
            if (json === Unencodable) return Unencodable
            out += json
        }
        return out
    }
}
