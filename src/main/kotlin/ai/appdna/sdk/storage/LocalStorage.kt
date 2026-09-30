package ai.appdna.sdk.storage

import ai.appdna.sdk.Log
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * SharedPreferences wrapper for persistent SDK data.
 *
 * SPEC-070-A G.5: Sensitive items (`anon_id`, `user_id`, `user_traits`,
 * `push_token`) are stored in [EncryptedSharedPreferences] (AES-256, AndroidX
 * Security Crypto) — iOS parity = Keychain.
 *
 * Existing plaintext entries are migrated lazily on first read: we copy the
 * value from the legacy [SharedPreferences] file into the encrypted store and
 * remove the plaintext copy so it's a one-time operation.
 *
 * Non-sensitive keys (event-queue cache, config cache, frequency counters,
 * etc.) keep using the plaintext file — no migration needed there.
 */
internal class LocalStorage(val context: Context) {
    private val plaintextPrefs: SharedPreferences =
        context.getSharedPreferences(LEGACY_PREFS_FILE, Context.MODE_PRIVATE)

    private val securePrefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Throwable) {
        // Devices without AndroidKeystore HW (rare) or with corrupted keysets fall
        // back to plaintext so the SDK still functions. Log loudly so we can spot
        // the regression in support tickets.
        Log.warning { "EncryptedSharedPreferences unavailable: ${e.message}. Falling back to plaintext." }
        null
    }

    private fun isSensitive(key: String): Boolean = SENSITIVE_KEYS.contains(key)

    private fun storeFor(key: String): SharedPreferences {
        return if (isSensitive(key) && securePrefs != null) securePrefs else plaintextPrefs
    }

    /**
     * Lazy migration: if the key was last written before EncryptedSharedPreferences
     * was added, the value still lives in plaintext. We promote it on first read
     * and clear the plaintext copy.
     */
    private fun migrateIfNeeded(key: String) {
        if (!isSensitive(key) || securePrefs == null) return
        if (securePrefs.contains(key)) return
        if (!plaintextPrefs.contains(key)) return
        val raw: Any? = plaintextPrefs.all[key]
        try {
            val editor = securePrefs.edit()
            when (raw) {
                is String -> editor.putString(key, raw)
                is Int -> editor.putInt(key, raw)
                is Long -> editor.putLong(key, raw)
                is Boolean -> editor.putBoolean(key, raw)
                is Float -> editor.putFloat(key, raw)
                else -> return
            }
            editor.apply()
            plaintextPrefs.edit().remove(key).apply()
        } catch (e: Throwable) {
            Log.warning { "Migration of '$key' to encrypted store failed: ${e.message}" }
        }
    }

    fun getString(key: String): String? {
        migrateIfNeeded(key)
        return storeFor(key).getString(key, null)
    }

    fun setString(key: String, value: String) {
        storeFor(key).edit().putString(key, value).apply()
    }

    fun getInt(key: String, default: Int = 0): Int {
        migrateIfNeeded(key)
        return storeFor(key).getInt(key, default)
    }

    fun setInt(key: String, value: Int) {
        storeFor(key).edit().putInt(key, value).apply()
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean {
        migrateIfNeeded(key)
        return storeFor(key).getBoolean(key, default)
    }

    fun setBoolean(key: String, value: Boolean) {
        storeFor(key).edit().putBoolean(key, value).apply()
    }

    fun getLong(key: String, default: Long = 0L): Long {
        migrateIfNeeded(key)
        return storeFor(key).getLong(key, default)
    }

    fun setLong(key: String, value: Long) {
        storeFor(key).edit().putLong(key, value).apply()
    }

    /**
     * SPEC-497 §13a.2 (Q1) — write several keys in ONE `edit()` finished with `commit()`, so they are on
     * disk together when this returns: a crash can never persist the reported set without its delivery
     * queue entry, or the other way round. A `null` value removes the key. Only non-sensitive keys go
     * through here (the billing ledger); a sensitive key would split the write across two files.
     *
     * It is the ONLY writer of `appdna.reported_purchase_tokens`, `appdna.pending_deliveries_v1`,
     * `appdna.verify_attempted_v1` and `appdna.purchase_class_cache_v1`.
     */
    fun setStringsSync(values: Map<String, String?>): Boolean {
        require(values.keys.none { isSensitive(it) }) { "setStringsSync writes non-sensitive keys only" }
        val editor = plaintextPrefs.edit()
        for ((k, v) in values) {
            if (v == null) editor.remove(k) else editor.putString(k, v)
        }
        return editor.commit()
    }

    fun remove(key: String) {
        // Remove from both stores defensively — the value may have been written
        // to the plaintext file before migration ran.
        plaintextPrefs.edit().remove(key).apply()
        securePrefs?.edit()?.remove(key)?.apply()
    }

    /**
     * Get all stored event JSON strings (for offline queue).
     */
    fun getEventQueue(): List<String> {
        val json = plaintextPrefs.getString("event_queue", null) ?: return emptyList()
        return json.split("\n").filter { it.isNotBlank() }
    }

    fun setEventQueue(events: List<String>) {
        plaintextPrefs.edit().putString("event_queue", events.joinToString("\n")).apply()
    }

    companion object {
        private const val LEGACY_PREFS_FILE = "ai.appdna.sdk"
        private const val ENCRYPTED_PREFS_FILE = "ai.appdna.sdk.secure"

        /**
         * SPEC-070-A G.5: Keys that hold PII or auth-bearing data and must live in
         * the encrypted prefs file. Others stay plaintext to avoid the
         * AndroidKeystore overhead on hot-path reads.
         */
        private val SENSITIVE_KEYS = setOf(
            "anon_id",
            "user_id",
            "user_traits",
            "push_token",
        )
    }
}
