package ai.appdna.sdk.onboarding

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * #1501 — what a `custom_view` block draws when the host app has NOT registered its `view_key`.
 *
 * It used to draw the literal `[view_key]` (a developer's debug label) to end users whenever the block carried no
 * `placeholder_text`. Now, in order: the host's view; else the authored placeholder image; else the authored
 * `placeholder_text`; else — in a DEBUGGABLE app only — the bracketed key, so a developer sees what is missing; else
 * NOTHING (the block collapses). A release build never shows the key; it logs it instead.
 */
internal object CustomViewFallback {
    /** Tests set this to exercise a release build; null reads the host app's `debuggable` flag. */
    @Volatile var debugOverride: Boolean? = null

    fun isDebugBuild(context: Context): Boolean =
        debugOverride ?: ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)

    /** The text an unregistered block draws, or null to draw nothing. */
    fun text(viewKey: String?, placeholderText: String?, debug: Boolean): String? {
        if (!placeholderText.isNullOrEmpty()) return placeholderText
        val key = viewKey ?: ""
        if (debug) return "[$key]"
        ai.appdna.sdk.Log.debug("[Onboarding] custom_view '$key' is not registered and has no placeholder_text — drawing nothing")
        return null
    }

    /** The decision, as the shared fixture names it: `host_view`, `placeholder_image`, `text:<…>` or `nothing`. */
    fun resolve(viewKey: String?, registered: Boolean, placeholderImageUrl: String?, placeholderText: String?, debug: Boolean): String {
        if (registered) return "host_view"
        if (!placeholderImageUrl.isNullOrEmpty()) return "placeholder_image"
        return text(viewKey, placeholderText, debug)?.let { "text:$it" } ?: "nothing"
    }
}
