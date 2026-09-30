package ai.appdna.sdk.integrations

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.storage.LocalStorage

/**
 * SPEC-497 §9.2 — idempotency for the push entry points.
 *
 * A host that owns Firebase Messaging may forward a message the SDK's own service (or a second host
 * handler) also saw. Each message is handled ONCE per kind, keyed on `delivery_id ?: push_id`:
 *   - **delivered** — an in-process set, capped at [CAP] (oldest evicted), that dedupes the DISPLAY
 *     and `onPushReceived`. A message is claimed there even before `configure` (impl audit round 2,
 *     A2): the first forward displays it; a forward of the same message after `configure` must not
 *     display it again. A claim that could not be tracked (no push manager yet) also goes into the
 *     [untracked] set, and the first forward that CAN track it does so — once — without displaying.
 *     Across a process restart the set is empty; since the server counts only real status transitions
 *     for a `delivery_id` (§8.2), a re-forward after relaunch no longer double-counts.
 *   - **tapped** — the persisted list [AppDNA.KEY_HANDLED_PUSH_TAPS] of the last [TAP_CAP] handled
 *     taps, shared by `AppDNA.handlePushTap(intent)` and `AppDNA.push.handleTapData(data)`, so both
 *     entry points for one tap track once, and an Activity re-created with ANY recent tap's intent —
 *     also after process death — does not re-fire (A1).
 */
internal object PushIdempotency {
    const val CAP = 256
    /** How many handled tap keys are persisted. */
    const val TAP_CAP = 32

    private val delivered = LinkedHashSet<String>()
    /** Claimed in [delivered] while no push manager could track them (before `configure`). */
    private val untracked = LinkedHashSet<String>()

    internal enum class Claim {
        /** First sighting: track (if possible), fire `onPushReceived`, display. */
        FIRST,
        /** Seen before `configure` and now trackable: track and fire `onPushReceived`, do NOT display. */
        LATE_TRACK,
        /** Already handled: nothing. */
        DUPLICATE,
    }

    /**
     * Claim [key] as delivered. [canTrack] is whether `push_delivered` can be tracked now (a push
     * manager exists). One synchronized step, so racing entry points claim a message once.
     */
    @Synchronized
    fun claimDelivered(key: String, canTrack: Boolean): Claim {
        if (delivered.add(key)) {
            if (!canTrack) untracked.add(key)
            while (delivered.size > CAP) {
                val oldest = delivered.first()
                delivered.remove(oldest)
                untracked.remove(oldest)
            }
            return Claim.FIRST
        }
        return if (canTrack && untracked.remove(key)) Claim.LATE_TRACK else Claim.DUPLICATE
    }

    private fun handledTaps(storage: LocalStorage?): List<String> {
        if (storage == null) return emptyList()
        val list = storage.getString(AppDNA.KEY_HANDLED_PUSH_TAPS)?.let { raw ->
            try {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length()).mapNotNull { arr.optString(it, "").ifEmpty { null } }
            } catch (_: Exception) {
                emptyList()
            }
        } ?: emptyList()
        // A tap handled by an SDK that persisted only the last key.
        val legacy = storage.getString(AppDNA.KEY_LAST_HANDLED_PUSH_TAP)
        return if (legacy != null && legacy !in list) listOf(legacy) + list else list
    }

    /** Whether [key] is one of the persisted recently handled taps. */
    fun tapAlreadyHandled(storage: LocalStorage?, key: String): Boolean = key in handledTaps(storage)

    fun markTapHandled(storage: LocalStorage?, key: String) {
        if (storage == null) return
        val next = (handledTaps(storage).filter { it != key } + key).takeLast(TAP_CAP)
        storage.setString(AppDNA.KEY_HANDLED_PUSH_TAPS, org.json.JSONArray(next).toString())
        storage.setString(AppDNA.KEY_LAST_HANDLED_PUSH_TAP, key)
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
     * Test-only: clears the in-process sets AND the persisted tap keys, so two fixtures that reuse a
     * `push_id` do not dedup each other. `@JvmName` because the RN / Flutter test modules call it by
     * reflection and Kotlin mangles `internal` names in bytecode.
     */
    @JvmName("resetForTesting")
    internal fun resetForTesting() {
        synchronized(this) { delivered.clear(); untracked.clear() }
        AppDNA.push.context?.let {
            val storage = LocalStorage(it)
            storage.remove(AppDNA.KEY_LAST_HANDLED_PUSH_TAP)
            storage.remove(AppDNA.KEY_HANDLED_PUSH_TAPS)
        }
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

    /**
     * Route [tapped] exactly as `handlePushTap` always has: show_screen, deep_link, show_paywall,
     * show_survey. SPEC-497 §17 item 28 — `open_url` (the console's URL action) goes to the host's
     * deep-link handler exactly like `deep_link`, as on iOS (`PushTapRouter.resolve`).
     */
    fun route(tapped: ai.appdna.sdk.PushAction?) {
        if (tapped == null || tapped.value.isBlank()) return
        val action = if (tapped.type == "open_url") ai.appdna.sdk.PushAction("deep_link", tapped.value) else tapped
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
