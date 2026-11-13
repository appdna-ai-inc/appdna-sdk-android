package ai.appdna.sdk.core

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import ai.appdna.sdk.Log

/**
 * The `share` action (#1797): open the platform share sheet with authored text and an optional link.
 *
 * ONE helper for every surface that offers it — an onboarding button (top-level or a child of
 * Multi-buttons / Row / a stack layer), an in-app message CTA and a paywall extra button — so the
 * trimming rule, the "nothing to share" rule and the intent shape cannot drift between them. iOS has
 * the same rule in its own helper; the shared fixtures `action_dispatch/cta_share_*` pin both.
 *
 * What it deliberately does NOT do:
 *  - advance anything. Sharing is a detour: the user comes back to the screen they were on, whether
 *    they shared or cancelled, exactly like `link` and `rate_app`.
 *  - track an event. Android's chooser cannot report whether a share completed (only which target
 *    was picked, and only through a PendingIntent callback some targets never fire), so a
 *    `share_completed` would mean different things on the two platforms. No event on either.
 *  - scheme-check the url. It is never navigated to — it is handed to the share target as TEXT.
 */
internal object ShareAction {

    /** The action name a block, a CTA or an extra button is authored with. */
    const val ACTION_NAME = "share"

    /** What the sheet carries. Both halves are trimmed; at least one is non-null. */
    data class SharePayload(val text: String?, val url: String?)

    /**
     * The payload for an authored [text] and [url], or null when there is nothing to share.
     *
     * Each half is trimmed, and a blank half is absent. Both absent → null: an empty share sheet is a
     * broken screen, so the caller opens nothing (the console warns at publish time; this is the
     * device's safe fallback, not a path an author is meant to reach).
     */
    fun payload(text: String?, url: String?): SharePayload? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() }
        val u = url?.trim()?.takeIf { it.isNotEmpty() }
        if (t == null && u == null) return null
        return SharePayload(text = t, url = u)
    }

    /**
     * The chooser for [payload]: one `ACTION_SEND` of `text/plain` whose `EXTRA_TEXT` is the text and
     * the link on separate lines (either alone when the other is absent). One string, because that is
     * what every share target reads; a separate `EXTRA_SUBJECT` is dropped by most of them.
     */
    fun buildChooserIntent(payload: SharePayload): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, listOfNotNull(payload.text, payload.url).joinToString("\n"))
        return Intent.createChooser(send, null)
    }

    /**
     * Opens the sheet for [payload] from [context]. Returns whether a sheet was started.
     *
     * A context that is not (and does not wrap) an Activity needs `FLAG_ACTIVITY_NEW_TASK`, or the
     * start throws. A device with nothing that handles `ACTION_SEND` is logged, never crashed on.
     */
    fun open(context: Context, payload: SharePayload): Boolean {
        val intent = buildChooserIntent(payload)
        val activity = context.findActivity()
        if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            (activity ?: context).startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.warning("[Share] no app can handle a share on this device: ${e.message}")
            false
        } catch (e: Throwable) {
            Log.warning("[Share] could not open the share sheet: ${e.message}")
            false
        }
    }

    /**
     * Payload + open in one step, for a call site that holds the authored fields. Nothing to share →
     * an authoring warning naming [surface], nothing opened, and false.
     */
    fun open(context: Context, text: String?, url: String?, surface: String): Boolean {
        val p = payload(text, url)
        if (p == null) {
            Log.warning(
                "[Share] A $surface is configured to share but has neither share text nor a link; " +
                    "nothing was opened.",
            )
            return false
        }
        return open(context, p)
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c != null) {
            if (c is Activity) return c
            c = (c as? ContextWrapper)?.baseContext
        }
        return null
    }
}
