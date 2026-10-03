package ai.appdna.sdk.storage

import android.content.Context

/**
 * The persisted analytics-consent decision.
 *
 * Consent used to live only in `EventTracker.analyticsConsent`, an in-memory `Boolean` initialised
 * to `true`. So `setConsent(false)` held for the life of the process and was silently undone by the
 * next cold start: an opted-out user was opted back in on every launch. That is a bug, not a missing
 * feature, which is why the fix lands here rather than waiting for the full multi-purpose consent
 * store.
 *
 * A dedicated `SharedPreferences` file — not [LocalStorage] — because the decision must be readable
 * before `configure()` wires the pipeline, and because it must never land in the encrypted store's
 * silent-plaintext-fallback path. It is not PII: it is a single boolean about the user's choice.
 *
 * Three states, and the third is load-bearing:
 *   - `true`  — granted
 *   - `false` — denied
 *   - `null`  — **no decision yet**. `AppDNAOptions.requireConsent` decides what that means.
 */
internal object ConsentStore {
    private const val PREFS_NAME = "ai.appdna.sdk.consent"
    private const val KEY = "analytics_consent"

    /** The persisted decision, or null if the user has never been asked. */
    fun decision(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY)) prefs.getBoolean(KEY, false) else null
    }

    /** Persist a decision. Committed synchronously: a lost revocation re-enables analytics. */
    fun setDecision(context: Context, granted: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, granted)
            .commit()
    }

    /**
     * Resolve the decision `configure()` should start with.
     *
     * ⚠️ **READ THIS BEFORE SHIPPING INTO THE EU/UK.** `requireConsent` defaults to **false**, so the
     * `?: !requireConsent` below resolves a *never-asked* user to **granted**. Analytics are
     * **opt-OUT by default**, and `sdk_initialized` — carrying device, OS, locale and session context
     * — is emitted by `configure()` **before the user has made any consent decision at all**. If your
     * app needs a lawful basis before the first byte leaves the device, you MUST pass
     * `requireConsent = true`; nothing else in this SDK will do it for you.
     *
     * This is a deliberate contract, not an oversight, and it is worth saying so because the line
     * reads like a bug:
     *
     *   - Opt-out is what this SDK has always done (`EventTracker.analyticsConsent = true`), and what
     *     Amplitude/Mixpanel/Firebase do. Flipping the DEFAULT to opt-in would silently zero the
     *     analytics of every already-shipped host on their next SDK bump — discovered not from an
     *     error but from an empty dashboard, weeks later.
     *   - What AC-36 owed, and what this type delivers, is that the decision is now (a) **persisted**
     *     — `setConsent(false)` is no longer undone by the next cold start — and (b) **honored before
     *     the first event**, `sdk_initialized` included, whenever a decision exists or
     *     [requireConsent] is set. The pre-decision exposure at the default is the documented behavior
     *     OF the default, not a hole in the gate.
     *   - The per-purpose consent store (marketing / personalisation / analytics as separate grants)
     *     is separate work, and that is where a compliant DEFAULT belongs — it needs a consent UI and a
     *     host migration, neither of which is a wrapper's to invent.
     *
     * When [requireConsent] is true, the absence of a decision means **denied** (opt-in), and NO event
     * — `sdk_initialized` included — is emitted until `setConsent` is called.
     */
    fun effectiveConsent(context: Context, requireConsent: Boolean): Boolean =
        decision(context) ?: !requireConsent

    /** Test seam. Not part of the public surface. */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY).commit()
    }
}
