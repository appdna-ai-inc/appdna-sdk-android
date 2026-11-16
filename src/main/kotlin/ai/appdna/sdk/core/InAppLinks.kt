package ai.appdna.sdk.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import ai.appdna.sdk.Log

/**
 * Opens a link authored in an onboarding flow the way iOS does (its in-app browser): in a Custom Tab, so the user
 * reads the page over the app and Back returns to the flow. When the device has no Custom Tabs provider, the link
 * opens in whatever handles a plain VIEW intent, as it did before.
 *
 * Every onboarding link goes through here: a `link` button (on a step, in an option sheet, in a step sheet), a
 * link in rich text or an option's title, an agreement consent's link and a warning banner's link. The URL is
 * scheme-checked by [URLSafety] first, because it comes from remote config.
 */
object InAppLinks {
    /** The intent [open] starts for [uri]: a Custom Tab when a provider exists, else a plain VIEW. */
    fun intentFor(context: Context, uri: Uri): Intent {
        val provider = runCatching { CustomTabsClient.getPackageName(context, null) }.getOrNull()
        if (provider == null) return Intent(Intent.ACTION_VIEW, uri)
        return CustomTabsIntent.Builder().setShowTitle(true).build().intent.apply {
            data = uri
            setPackage(provider)
        }
    }

    /** Scheme-checks [raw] and opens it; false when it was refused or nothing could open it. */
    fun open(context: Context, raw: String?): Boolean {
        val uri = URLSafety.sanitized(raw, context) ?: return false
        return runCatching {
            val intent = intentFor(context, uri)
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrElse {
            Log.warning("Opening a config URL failed: ${it.message}")
            false
        }
    }
}
