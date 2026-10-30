package ai.appdna.sdk.onboarding

/**
 * The ONE format rule both platforms apply, written down once per platform and kept
 * character-for-character identical.
 *
 * 🔴 `android.net.Uri.parse` is NOT the rule, and that is the whole reason this object exists. It
 * accepts `"not a url"` and returns a Uri with a null scheme, so a check written the obvious way
 * would have passed everything while looking strict. Foundation's `URL(string:)` is equally
 * permissive on iOS. A deliberate pattern is the only way the two can agree on what a user typed
 * being "a URL" means.
 *
 * The rule, in words, so the Swift twin can be read against it:
 *
 * - leading and trailing whitespace is ignored;
 * - an `http://` or `https://` scheme is OPTIONAL and case-insensitive — an author collecting
 *   "your website" should not have to make everyone type the scheme;
 * - then a host of at least two dot-separated labels, each of letters, digits or hyphens, with a
 *   final label of at least two letters, so `example` fails and `example.com` passes;
 * - an optional `:port` of one to five digits;
 * - an optional path, query or fragment, which may contain anything except whitespace.
 */
object FormFieldFormats {
    /** The shared URL shape. Mirrored in Swift by `FormFieldFormats.urlPattern`. */
    const val URL_PATTERN = "^(?i)(https?://)?([A-Za-z0-9-]+\\.)+[A-Za-z]{2,}(:[0-9]{1,5})?([/?#][^\\s]*)?$"

    private val urlRegex by lazy { Regex(URL_PATTERN) }

    fun looksLikeUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        return urlRegex.matches(trimmed)
    }
}
