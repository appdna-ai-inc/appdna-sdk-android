package ai.appdna.sdk

import ai.appdna.sdk.storage.LocalStorage
import org.json.JSONObject
import java.util.UUID

/**
 * Device identity: anonymous + optional identified user.
 */
data class DeviceIdentity(
    val anonId: String,
    val userId: String? = null,
    val traits: Map<String, Any>? = null
)

/**
 * Manages anonymous and identified user identity.
 * Persists identity to SharedPreferences (via LocalStorage).
 */
internal class IdentityManager(private val storage: LocalStorage) {
    private val lock = Any()
    @Volatile private var _anonId: String = ""
    @Volatile private var _userId: String? = null
    @Volatile private var _traits: Map<String, Any>? = null

    val currentIdentity: DeviceIdentity
        get() = synchronized(lock) { DeviceIdentity(anonId = _anonId, userId = _userId, traits = _traits) }

    @Volatile var sessionId: String = UUID.randomUUID().toString()
        private set

    init {
        // Load or generate anonymous ID
        _anonId = storage.getString("anon_id") ?: run {
            val id = UUID.randomUUID().toString()
            storage.setString("anon_id", id)
            id
        }
        _userId = storage.getString("user_id")
        // Load persisted traits so they survive app restart
        _traits = loadTraits()
    }

    /**
     * Link the anonymous device to a known user.
     *
     * When [traits] is null we DO NOT overwrite the stored
     * traits — mirrors iOS `IdentityManager.identify(userId:traits:)` which only
     * persists `keychainStore.setUserTraits(traits)` when a non-nil dictionary
     * is supplied (Sources/AppDNASDK/Core/Identity/IdentityManager.swift:49-58).
     */
    fun identify(userId: String, rawTraits: Map<String, Any>?) {
        // What is stored and persisted is JSON-safe ([IdentifyTraits]); the public identify has already
        // logged anything it dropped.
        val traits = ai.appdna.sdk.core.IdentifyTraits.sanitize(rawTraits, log = false)
        // On a null `traits`, clear traits ONLY when the user actually CHANGES (an
        // account switch — the prior user's traits don't apply to the new one), and retain them on a
        // same-user re-identify (the common per-launch call). Previously Android retained traits even
        // across a user switch, so user B was evaluated against user A's stale traits; iOS wiped them on
        // every nil-traits identify. Both now converge on this correct behavior.
        // "Switched" = a known user → a DIFFERENT known user. The first anonymous→login transition
        // (previous == null) is NOT a switch, so device-scoped traits (e.g. bootstrap geo merged before
        // the host's first identify) survive first login.
        val switchedUser: Boolean
        synchronized(lock) {
            val previous = _userId
            switchedUser = previous != null && previous != userId
            _userId = userId
            when {
                traits != null -> _traits = traits
                switchedUser -> _traits = null
                // else: same user or first login, no new traits → keep existing traits.
            }
        }
        storage.setString("user_id", userId)
        when {
            traits != null -> persistTraits(traits)
            switchedUser -> storage.remove("user_traits")
        }
        Log.info { "Identified user: $userId" }
    }

    /**
     * Merge [newTraits] into the existing trait map without
     * overwriting any user-set keys. Used for auto-injected geo/locale/device
     * traits from the bootstrap response.
     *
     * No-op if every value in [newTraits] is already present (avoids a redundant
     * disk write on every cold start). Matches iOS
     * `IdentityManager.mergeTraits(_:)` at
     * Sources/AppDNASDK/Core/Identity/IdentityManager.swift:62-73.
     */
    fun mergeTraits(newTraits: Map<String, Any>) {
        if (newTraits.isEmpty()) return
        synchronized(lock) {
            val existing = _traits ?: emptyMap()
            val merged = LinkedHashMap<String, Any>(existing)
            var changed = false
            for ((key, value) in newTraits) {
                if (!merged.containsKey(key)) {
                    merged[key] = value
                    changed = true
                }
            }
            if (!changed) return
            _traits = merged
            persistTraits(merged)
        }
    }

    fun reset() {
        synchronized(lock) {
            _userId = null
            _traits = null
            // DO NOT rotate sessionId on
            // reset. iOS canonical reset() preserves the in-flight session
            // so post-logout events still attribute to the same session
            // until newSession() / app foreground triggers natural rotation.
            // Cross-platform attribution divergence on logout removed.
        }
        storage.remove("user_id")
        storage.remove("user_traits")
        Log.info { "Identity reset" }
    }

    fun newSession() {
        synchronized(lock) {
            sessionId = UUID.randomUUID().toString()
        }
    }

    // Trait persistence helpers

    private fun persistTraits(traits: Map<String, Any>?) {
        if (traits == null) {
            storage.remove("user_traits")
            return
        }
        try {
            val json = JSONObject()
            for ((key, value) in traits) {
                // `wrap` turns a map or a list into a JSON object or array; `put` alone stored their
                // toString().
                json.put(key, JSONObject.wrap(value))
            }
            storage.setString("user_traits", json.toString())
        } catch (e: Exception) {
            // Surface parse errors instead of silently swallowing.
            Log.warning { "Failed to persist user traits: ${e.message}" }
        }
    }

    private fun loadTraits(): Map<String, Any>? {
        val json = storage.getString("user_traits") ?: return null
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, Any>()
            // A JSON null is a trait value like any other (`IdentifyTraits` keeps it as `JSONObject.NULL`),
            // so it is kept across a relaunch rather than lost on the next launch only.
            for (key in obj.keys()) map[key] = obj.get(key)
            map
        } catch (e: Exception) {
            // Surface parse errors instead of silently swallowing.
            Log.warning { "Failed to load user traits: ${e.message}" }
            null
        }
    }
}
