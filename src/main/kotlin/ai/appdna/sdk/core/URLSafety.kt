package ai.appdna.sdk.core

import android.content.Context
import android.net.Uri
import ai.appdna.sdk.Log

/**
 * A scheme allowlist for URLs that arrive from remote config.
 *
 * Onboarding blocks, in-app messages, and server-driven screens all hand config strings straight to
 * `Intent.ACTION_VIEW`. A compromised or misconfigured config could therefore drive `javascript:`,
 * `content:`, `file:`, or plain `http:` navigation from inside the app — in-app phishing, with no
 * certificate pinning (W9) to make it harder.
 *
 * The rule: **https for anything external**, plus the small set of system schemes a growth SDK
 * legitimately needs, plus **any URI that resolves back into the host's own package** so its deep
 * links keep working. Everything else is refused and logged.
 */
internal object URLSafety {

    /**
     * Schemes always permitted. `http` is deliberately absent — a config-driven cleartext
     * navigation is exactly the attack this guards.
     */
    val ALLOWED_SCHEMES: Set<String> = setOf(
        "https",
        "mailto",
        "tel",
        "sms",
        "market", // Play Store
    )

    /** Whether a config-driven URL may be opened. [context] enables the host-deep-link check. */
    fun isAllowed(uri: Uri, context: Context? = null): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme in ALLOWED_SCHEMES) return true
        // A deep link back into the host app is not external navigation. Anything that resolves to
        // some OTHER package is, and stays refused.
        return context != null && resolvesToHost(uri, context)
    }

    private fun resolvesToHost(uri: Uri, context: Context): Boolean = runCatching {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
        val resolved = intent.resolveActivity(context.packageManager) ?: return false
        resolved.packageName == context.packageName
    }.getOrDefault(false)

    /**
     * Sanitize and open, in one call. Returns whether the URL was allowed and launched.
     *
     * 🔴 The helper above existed and was unit-tested, while the paywall — the MONEY surface — and
     * the SDUI flow manager built `Intent(ACTION_VIEW, Uri.parse(configString))` by hand and
     * bypassed it entirely. A tested guard nothing calls is not a guard. Every config-driven open
     * goes through here now, so there is one place to test and one place to break.
     */
    fun open(context: Context, raw: String?, newTask: Boolean = false): Boolean {
        val uri = sanitized(raw, context) ?: return false
        return runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            if (newTask) intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrElse {
            Log.warning("Opening a config URL failed: ${it.message}")
            false
        }
    }

    /** Parse and validate in one step. Returns null — and logs why — when the URL is refused. */
    fun sanitized(raw: String?, context: Context? = null): Uri? {
        if (raw.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: run {
            Log.warning("Refusing to open a malformed config URL")
            return null
        }
        if (!isAllowed(uri, context)) {
            // The scheme, never the full URL: the path may carry a token.
            Log.warning("Refusing to open config URL with disallowed scheme '${uri.scheme ?: "none"}'")
            return null
        }
        return uri
    }
}
