package ai.appdna.sdk.integrations

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.storage.LocalStorage

/**
 * SPEC-497 §9.2 — idempotency for the push entry points.
 *
 * A host that owns Firebase Messaging may forward a message the SDK's own service (or a second host
 * handler) also saw. Each message is tracked ONCE per kind, keyed on `delivery_id ?: push_id`:
 *   - **delivered** — an in-process set, capped at [CAP] (oldest evicted). Across a process restart
 *     the set is empty; since the server counts only real status transitions for a `delivery_id`
 *     (§8.2), a re-forward after relaunch no longer double-counts.
 *   - **tapped** — the persisted key [AppDNA.KEY_LAST_HANDLED_PUSH_TAP], shared by
 *     `AppDNA.handlePushTap(intent)` and `AppDNA.push.handleTapData(data)`, so both entry points for
 *     one tap track once, and an Activity re-created with the same intent does not re-fire.
 */
internal object PushIdempotency {
    const val CAP = 256

    private val delivered = LinkedHashSet<String>()

    /** True the FIRST time [key] is seen as delivered in this process; false for a repeat. */
    @Synchronized
    fun claimDelivered(key: String): Boolean {
        if (!delivered.add(key)) return false
        while (delivered.size > CAP) delivered.remove(delivered.first())
        return true
    }

    /** Whether the persisted last-handled tap equals [key]. */
    fun tapAlreadyHandled(storage: LocalStorage?, key: String): Boolean =
        storage?.getString(AppDNA.KEY_LAST_HANDLED_PUSH_TAP) == key

    fun markTapHandled(storage: LocalStorage?, key: String) {
        storage?.setString(AppDNA.KEY_LAST_HANDLED_PUSH_TAP, key)
    }

    /**
     * Check-and-mark in ONE step (I4 minor 31): true the first time [key] is claimed as tapped, false
     * when it already is. Synchronized, so two entry points racing on the same tap (the intent and a
     * host's `handleTapData`) cannot both pass the check. `setString` updates the in-memory
     * SharedPreferences map before returning, so the next claim in this process sees it.
     */
    @Synchronized
    fun claimTap(storage: LocalStorage?, key: String): Boolean {
        if (tapAlreadyHandled(storage, key)) return false
        markTapHandled(storage, key)
        return true
    }

    /**
     * Test-only: clears the in-process set AND the persisted tap key, so two fixtures that reuse a
     * `push_id` do not dedup each other. `@JvmName` because the RN / Flutter test modules call it by
     * reflection and Kotlin mangles `internal` names in bytecode.
     */
    @JvmName("resetForTesting")
    internal fun resetForTesting() {
        synchronized(this) { delivered.clear() }
        AppDNA.push.context?.let { LocalStorage(it).remove(AppDNA.KEY_LAST_HANDLED_PUSH_TAP) }
    }
}

/**
 * SPEC-497 §9.2 / §8.7 — the push-tap router. [routeSink] is a test seam: production leaves it null.
 * When set, the router reports `("show_screen", id)` / `("deep_link", url)` / … to it BEFORE the real
 * navigation, because the navigation sits inside a catch-all that would swallow a Robolectric
 * failure and skip a sink called after it.
 */
internal object PushTapRouter {
    @JvmField
    @Volatile
    internal var routeSink: ((String, String) -> Unit)? = null

    /** Route [action] exactly as `handlePushTap` always has: show_screen, deep_link, show_paywall, show_survey. */
    fun route(action: ai.appdna.sdk.PushAction?) {
        if (action == null || action.value.isBlank()) return
        val routable = action.type in setOf("show_screen", "deep_link", "show_paywall", "show_survey")
        if (!routable) return
        routeSink?.invoke(action.type, action.value)
        try {
            when (action.type) {
                "show_screen" -> AppDNA.showScreen(action.value)
                "deep_link" -> AppDNA.deepLinks.handleURL(action.value)
                "show_paywall" -> AppDNA.showPaywall(action.value)
                "show_survey" -> AppDNA.showSurvey(action.value)
            }
        } catch (e: Throwable) {
            ai.appdna.sdk.Log.warning("push tap: auto-route failed: ${e.message}")
        }
    }
}
