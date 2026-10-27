package ai.appdna.sdk.core

import android.content.Context
import android.content.SharedPreferences
import ai.appdna.sdk.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists cross-module data (onboarding responses, computed hook data, session data)
 * so it can be used by TemplateEngine across all SDK modules.
 * Thread-safe via synchronized blocks. Persists to SharedPreferences (not sensitive data).
 */
class SessionDataStore private constructor(private val prefs: SharedPreferences) {

    companion object {
        private const val PREFS_NAME = "ai.appdna.sdk.sessiondata"
        private const val KEY_ONBOARDING = "onboarding_responses"
        private const val KEY_COMPUTED = "computed_data"
        private const val KEY_SESSION = "session_data"

        // Cap to prevent unbounded growth
        private const val MAX_STORAGE_BYTES = 100 * 1024 // 100KB

        @Volatile
        var instance: SessionDataStore? = null
            private set

        /**
         * Initialize the SessionDataStore. Call once during SDK configure().
         */
        fun initialize(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        val prefs = context.applicationContext
                            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        instance = SessionDataStore(prefs)
                    }
                }
            }
        }
    }

    // In-memory state loaded from SharedPreferences on init
    private var _onboardingResponses: Map<String, Map<String, Any>> = emptyMap()
    private var _computedData: Map<String, Any> = emptyMap()
    private var _sessionData: Map<String, Any> = emptyMap()

    val onboardingResponses: Map<String, Map<String, Any>>
        get() = synchronized(this) { _onboardingResponses }

    val computedData: Map<String, Any>
        get() = synchronized(this) { _computedData }

    val sessionData: Map<String, Any>
        get() = synchronized(this) { _sessionData }

    init {
        // Load persisted data
        _onboardingResponses = loadNestedMap(KEY_ONBOARDING)
        _computedData = loadMap(KEY_COMPUTED)
        _sessionData = loadMap(KEY_SESSION)
    }

    // MARK: - Onboarding Responses

    /**
     * Called when onboarding flow completes — persists all step responses.
     */
    fun setOnboardingResponses(responses: Map<String, Any>) {
        synchronized(this) {
            val converted = mutableMapOf<String, Map<String, Any>>()
            for ((stepId, value) in responses) {
                if (value is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    converted[stepId] = value as Map<String, Any>
                }
            }
            _onboardingResponses = converted
            persistMap(KEY_ONBOARDING) { mapToJson(converted, it) }
        }
    }

    // MARK: - Computed Data (from proceedWithData)

    /**
     * Merge hook-injected data into the computed namespace.
     */
    fun mergeComputedData(data: Map<String, Any>) {
        synchronized(this) {
            val merged = _computedData.toMutableMap()
            merged.putAll(data)
            _computedData = merged
            persistMap(KEY_COMPUTED) { flatMapToJson(merged, it) }
        }
    }

    // MARK: - Session Data (public API)

    /**
     * Set a session data value (public API: AppDNA.setSessionData(key, value)).
     */
    fun setSessionData(key: String, value: Any) {
        synchronized(this) {
            val updated = _sessionData.toMutableMap()
            updated[key] = value
            _sessionData = updated
            persistMap(KEY_SESSION) { flatMapToJson(updated, it) }
        }
    }

    /**
     * Get a session data value (public API: AppDNA.getSessionData(key)).
     */
    fun getSessionData(key: String): Any? {
        return synchronized(this) { _sessionData[key] }
    }

    /**
     * Clear all session data (public API: AppDNA.clearSessionData()).
     */
    fun clearSessionData() {
        synchronized(this) {
            _sessionData = emptyMap()
            prefs.edit().remove(KEY_SESSION).apply()
        }
    }

    /**
     * Clear everything (onboarding + computed + session).
     */
    /**
     * Re-reads the persisted copy, as a cold start does — `init` runs the same loaders. The shared fixture
     * `location_data_after_relaunch` (#1150) drives it so both platforms prove an answer survives a relaunch.
     */
    internal fun reloadFromDisk() {
        synchronized(this) {
            _onboardingResponses = loadNestedMap(KEY_ONBOARDING)
            _computedData = loadMap(KEY_COMPUTED)
            _sessionData = loadMap(KEY_SESSION)
        }
    }

    fun clearAll() {
        synchronized(this) {
            _onboardingResponses = emptyMap()
            _computedData = emptyMap()
            _sessionData = emptyMap()
            prefs.edit()
                .remove(KEY_ONBOARDING)
                .remove(KEY_COMPUTED)
                .remove(KEY_SESSION)
                .apply()
        }
    }

    // MARK: - Persistence Helpers

    /**
     * Persists [key] from [buildJson]. `JSONObject.put` THROWS on a NaN / infinite number, and that
     * exception used to escape into the host's `setSessionData` / onboarding completion call. Now each
     * entry is written on its own and an entry that cannot be written as JSON is left out of the saved
     * copy — logged by its name only, never the values. It used to drop the WHOLE store, so the next
     * launch lost every other value because one was NaN. A store over the size cap is logged and its
     * saved copy cleared. The in-memory value stays readable for the rest of the session. Same rule as
     * iOS `SessionDataStore.persistDict`.
     */
    private fun persistMap(key: String, buildJson: (skipped: MutableList<String>) -> String) {
        val skipped = mutableListOf<String>()
        val json = try {
            buildJson(skipped)
        } catch (e: org.json.JSONException) {
            Log.warning("SessionDataStore: $key could not be encoded — not persisted; the saved copy is cleared")
            prefs.edit().remove(key).apply()
            return
        }
        if (skipped.isNotEmpty()) {
            Log.warning("SessionDataStore: $key entries ${skipped.sorted()} hold a value that is not valid JSON (a NaN / infinite number or a non-JSON type) — those entries are not persisted")
        }
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_STORAGE_BYTES) {
            Log.warning("SessionDataStore: $key exceeds $MAX_STORAGE_BYTES bytes — not persisted; the saved copy is cleared")
            prefs.edit().remove(key).apply()
            return
        }
        prefs.edit().putString(key, json).apply()
    }

    private fun loadMap(key: String): Map<String, Any> {
        val json = prefs.getString(key, null) ?: return emptyMap()
        return try {
            jsonToFlatMap(JSONObject(json))
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun loadNestedMap(key: String): Map<String, Map<String, Any>> {
        val json = prefs.getString(key, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            val result = mutableMapOf<String, Map<String, Any>>()
            for (stepId in obj.keys()) {
                val stepObj = obj.optJSONObject(stepId)
                if (stepObj != null) {
                    result[stepId] = jsonToFlatMap(stepObj)
                }
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun jsonToFlatMap(obj: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        for (key in obj.keys()) {
            val value = obj.get(key)
            if (value != JSONObject.NULL) {
                map[key] = fromJsonValue(value)
            }
        }
        return map
    }

    /**
     * #1150 — a nested `JSONObject` / `JSONArray` comes back as the `Map` / `List` it was written from, so a stored
     * location selection or a multi-select answer reads the same after a relaunch as before it. A `null` inside an
     * object is dropped, as at the top level; one inside an array keeps its place.
     */
    private fun fromJsonValue(value: Any): Any = when (value) {
        is JSONObject -> jsonToFlatMap(value)
        is JSONArray -> List(value.length()) { i -> value.opt(i)?.takeIf { it != JSONObject.NULL }?.let(::fromJsonValue) }
        else -> value
    }

    /**
     * #1150 — the JSON form of [value], deep. Android's `org.json` stores a `Map` or `List` given to `put` as-is and
     * writes it as its `toString()`, so a selected location answer used to persist as `"{formatted_address=…}"` and
     * read back as text. Throws `JSONException` for what JSON cannot hold — a NaN / infinite number or a non-JSON
     * type, at any depth — so the caller leaves that one entry out, as it already did for a top-level NaN.
     */
    private fun toJsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray -> value
        is Map<*, *> -> JSONObject().also { o -> for ((k, v) in value) o.put(k.toString(), toJsonValue(v)) }
        is Iterable<*> -> JSONArray().also { a -> for (v in value) a.put(toJsonValue(v)) }
        is Array<*> -> toJsonValue(value.asList())
        is Double -> if (value.isNaN() || value.isInfinite()) throw org.json.JSONException("non-finite number") else value
        is Float -> if (value.isNaN() || value.isInfinite()) throw org.json.JSONException("non-finite number") else value
        is Number, is String, is Boolean -> value
        else -> throw org.json.JSONException("not a JSON type: ${value.javaClass.simpleName}")
    }

    /** One entry per key; a value `JSONObject` refuses (a NaN / infinite number) is skipped and named in [skipped]. */
    private fun flatMapToJson(map: Map<String, Any>, skipped: MutableList<String>): String {
        val obj = JSONObject()
        for ((k, v) in map) {
            try {
                obj.put(k, toJsonValue(v))
            } catch (_: org.json.JSONException) {
                skipped += k
            }
        }
        return obj.toString()
    }

    private fun mapToJson(map: Map<String, Map<String, Any>>, skipped: MutableList<String>): String {
        val obj = JSONObject()
        for ((k, v) in map) {
            val inner = JSONObject()
            for ((ik, iv) in v) {
                try {
                    inner.put(ik, toJsonValue(iv))
                } catch (_: org.json.JSONException) {
                    skipped += "$k.$ik"
                }
            }
            obj.put(k, inner)
        }
        return obj.toString()
    }
}
