package ai.appdna.sdk.core

import android.content.Context
import android.content.SharedPreferences
import ai.appdna.sdk.Log
import org.json.JSONObject

/**
 * Persists cross-module data (onboarding responses, computed hook data, session data)
 * so it can be used by TemplateEngine across all SDK modules (SPEC-088).
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
            persistMap(KEY_ONBOARDING) { mapToJson(converted) }
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
            persistMap(KEY_COMPUTED) { flatMapToJson(merged) }
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
            persistMap(KEY_SESSION) { flatMapToJson(updated) }
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
     * exception used to escape into the host's `setSessionData` / onboarding completion call. Now a
     * value that cannot be written as JSON — or one over the size cap — is logged (the key only, never
     * the values) and the saved copy is cleared, so the store holds nothing rather than a stale copy.
     * The in-memory value stays readable for the rest of the session. Same rule as iOS
     * `SessionDataStore.persistDict`.
     */
    private fun persistMap(key: String, buildJson: () -> String) {
        val json = try {
            buildJson()
        } catch (e: org.json.JSONException) {
            Log.warning("SessionDataStore: $key holds a value that is not valid JSON (a NaN / infinite number or a non-JSON type) — not persisted; the saved copy is cleared")
            prefs.edit().remove(key).apply()
            return
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
                map[key] = value
            }
        }
        return map
    }

    private fun flatMapToJson(map: Map<String, Any>): String {
        val obj = JSONObject()
        for ((k, v) in map) {
            obj.put(k, v)
        }
        return obj.toString()
    }

    private fun mapToJson(map: Map<String, Map<String, Any>>): String {
        val obj = JSONObject()
        for ((k, v) in map) {
            val inner = JSONObject()
            for ((ik, iv) in v) {
                inner.put(ik, iv)
            }
            obj.put(k, inner)
        }
        return obj.toString()
    }
}
