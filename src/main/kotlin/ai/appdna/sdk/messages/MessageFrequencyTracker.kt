package ai.appdna.sdk.messages

import android.content.Context
import android.content.SharedPreferences

/**
 * Tracks in-app message display frequency. Mirrors iOS
 * `InAppMessaging/MessageFrequencyTracker.swift`.
 *
 * Three frequency modes are supported, all evaluated by [canShow]:
 *  - `once` — persisted across sessions; uses `<id>.shown` flag.
 *  - `once_per_session` — in-memory only; cleared by [resetSession].
 *  - `every_time` — always returns `true`.
 *  - `max_times` — persisted counter; show until `count >= max_displays`.
 *
 * Persisted counters live in a dedicated
 * `SharedPreferences` instance (`ai.appdna.sdk.msg_freq`) rather than
 * SessionDataStore. SessionDataStore is wiped by [AppDNA.clearSessionData]
 * which would otherwise reset cross-session `once` and `max_times` counters,
 * breaking parity with iOS UserDefaults isolation
 * (`InAppMessaging/MessageFrequencyTracker.swift:5-6`).
 */
internal class MessageFrequencyTracker(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** In-memory set for `once_per_session` tracking. */
    private val sessionShownIds: MutableSet<String> = mutableSetOf()

    /**
     * Returns true if [messageId] can be shown given its [frequency] rules.
     * Pass [maxDisplays] only for `max_times`; ignored otherwise.
     */
    fun canShow(messageId: String, frequency: String, maxDisplays: Int? = null): Boolean {
        return when (frequency) {
            "once" -> !hasBeenShown(messageId)
            "once_per_session" -> !sessionShownIds.contains(messageId)
            "max_times" -> {
                val max = maxDisplays ?: return true
                displayCount(messageId) < max
            }
            "every_time" -> true
            else -> true  // unknown mode → don't gate (matches iOS fallback semantics)
        }
    }

    /**
     * Record that a message was shown. No-ops for `every_time`.
     */
    fun recordShown(messageId: String, frequency: String) {
        when (frequency) {
            "once" -> {
                prefs.edit().putBoolean(shownKey(messageId), true).apply()
            }
            "once_per_session" -> {
                sessionShownIds.add(messageId)
            }
            "max_times" -> {
                val next = displayCount(messageId) + 1
                prefs.edit().putInt(countKey(messageId), next).apply()
            }
            // every_time / unknown → nothing to record
        }
    }

    /** Reset session-level (`once_per_session`) tracking — call on new session. */
    fun resetSession() {
        sessionShownIds.clear()
    }

    // MARK: - Private

    private fun hasBeenShown(messageId: String): Boolean {
        return prefs.getBoolean(shownKey(messageId), false)
    }

    private fun displayCount(messageId: String): Int {
        return prefs.getInt(countKey(messageId), 0)
    }

    companion object {
        private const val PREFS_NAME = "ai.appdna.sdk.msg_freq"
        private fun shownKey(id: String) = "$id.shown"
        private fun countKey(id: String) = "$id.count"
    }
}
