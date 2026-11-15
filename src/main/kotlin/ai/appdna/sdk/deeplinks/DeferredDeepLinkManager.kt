package ai.appdna.sdk.deeplinks

import android.content.Context
import android.net.Uri
import android.provider.Settings
import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import ai.appdna.sdk.events.EventTracker
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * A resolved deferred deep link.
 */
data class DeferredDeepLink(
    val screen: String,
    val params: Map<String, String>,
    val visitorId: String
) {
    fun toMap(): Map<String, Any> = mapOf(
        "screen" to screen,
        "params" to params,
        "visitorId" to visitorId
    )
}

/**
 * Checks for and resolves deferred deep links on first app launch.
 * Path: /orgs/{orgId}/apps/{appId}/config/deferred_deep_links/{visitorId}
 *
 *   (a) Install Referrer call moved off the main thread into a coroutine — was
 *       previously blocking with `CountDownLatch.await(2s)`.
 *   (b) When the resolved doc carries `screen_id`, [AppDNA.showScreen] is
 *       auto-invoked after 0.5s so the host doesn't have to wire a handler
 *       manually.
 *   (c) Adds a `Settings.Secure.ANDROID_ID` fingerprint fallback strategy
 *       (iOS-IDFV equivalent) — used when neither clipboard nor Install
 *       Referrer surfaced a visitor ID.
 */
internal class DeferredDeepLinkManager(
    private val context: Context,
    private val orgId: String,
    private val appId: String,
    private val eventTracker: EventTracker?,
    /** Where the visitor id copied by the web page is read from. A test passes its own. */
    private val clipboard: ClipboardReading = SystemClipboard(context),
) {
    companion object {
        private const val FIRST_LAUNCH_KEY = "ai.appdna.sdk.first_launch_completed"
        private const val EXPIRY_HOURS = 72L

        /** Failed attempts so far, so a transport error does not spend the one chance. */
        private const val FAILED_ATTEMPTS_KEY = "ai.appdna.sdk.ddl_failed_attempts"

        /**
         * 🔴 A TRANSPORT FAILURE IS NOT AN ANSWER. `markLaunched()` used to run on every path out
         * of the fetch, `addOnFailureListener` included — so a user who installed from a link and
         * opened the app with no network (a lift, a plane, a carrier handover, or simply before
         * Firestore's first connection completed) lost the destination and the referrer
         * PERMANENTLY, and silently: the host got `null`, which is indistinguishable from "this
         * install did not come from a link".
         *
         * The chance survives a failure now, bounded so a permanently offline device does not read
         * Firestore on every launch for the life of the install. A SUCCESSFUL read — document
         * present, or genuinely absent — is still an answer and still spends it.
         *
         * Four, not one: the link expires after [EXPIRY_HOURS] anyway, so the attempts only need to
         * cover the launches a user plausibly makes inside three days while something is wrong with
         * their connection. Matches iOS `maxFailedAttempts`.
         */
        internal const val MAX_FAILED_ATTEMPTS = 4
        private const val INSTALL_REFERRER_TIMEOUT_MS = 2_000L
        // Delay before auto-routing to a screen so the
        // host's first Activity is fully resumed and ScreenManager has a
        // surface to attach to.
        private const val AUTO_SHOW_DELAY_MS = 500L
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun checkDeferredDeepLink(callback: (DeferredDeepLink?) -> Unit) {
        if (!isFirstLaunch()) {
            callback(null)
            return
        }

        // Visitor-id resolution may block on the Install
        // Referrer service for up to 2 seconds — run on Dispatchers.IO and
        // hand the result back on the main thread.
        scope.launch {
            val visitorId = withContext(Dispatchers.IO) { resolveVisitorId() }
            if (visitorId == null) {
                Log.debug("DeferredDeepLink: no visitor ID resolved")
                markLaunched()
                callback(null)
                return@launch
            }

            val path = "orgs/$orgId/apps/$appId/config/deferred_deep_links/visitors/$visitorId"
            Log.debug("DeferredDeepLink: checking $path")

            val db = AppDNA.firestoreDB
            if (db == null) {
                // 🔴 NOT an answer either: without a Firestore handle nothing was asked.
                recordFailedAttempt("Firestore is not available")
                callback(null)
                return@launch
            }

            db.document(path).get()
                .addOnSuccessListener { snapshot ->
                    markLaunched()

                    val data = snapshot.data
                    if (data == null) {
                        callback(null)
                        return@addOnSuccessListener
                    }

                    // Check expiry
                    val createdAt = (data["created_at"] as? Number)?.toDouble()
                    if (createdAt != null) {
                        val age = (System.currentTimeMillis() / 1000.0) - createdAt
                        if (age > EXPIRY_HOURS * 3600) {
                            Log.debug("DeferredDeepLink: expired (age: ${age / 3600}h)")
                            snapshot.reference.delete()
                            callback(null)
                            return@addOnSuccessListener
                        }
                    }

                    @Suppress("UNCHECKED_CAST")
                    val deepLink = DeferredDeepLink(
                        screen = data["screen"] as? String ?: "",
                        params = (data["params"] as? Map<String, String>) ?: emptyMap(),
                        visitorId = visitorId
                    )

                    // Delete after resolving (one-time use)
                    snapshot.reference.delete()

                    // Track event
                    eventTracker?.track("deferred_deep_link_resolved", mapOf(
                        "path" to deepLink.screen,
                        "params" to deepLink.params,
                        "visitor_id" to visitorId
                    ))

                    // Parity — auto-route ONLY when the doc
                    // carries an explicit `screen_id` (a server-driven screen), matching iOS
                    // (DeferredDeepLinkManager.swift). The legacy `screen` field is a host ROUTE
                    // PATH (e.g. "/workout/123"), NOT a screen id — feeding it to showScreen pushed
                    // a route into the server-driven screen presenter (no-op-with-warning at best,
                    // double-handling alongside the host's own routing at worst). The host still
                    // gets `deepLink` (incl. `screen`) via the callback to route it itself.
                    val screenId = (data["screen_id"] as? String)?.takeIf { it.isNotBlank() }
                    if (screenId != null) {
                        scope.launch {
                            delay(AUTO_SHOW_DELAY_MS)
                            try {
                                Log.debug("DeferredDeepLink: auto-routing to screen=$screenId")
                                AppDNA.showScreen(screenId)
                            } catch (e: Throwable) {
                                Log.warning("DeferredDeepLink: auto-show failed: ${e.message}")
                            }
                        }
                    }

                    // Callback LAST (matches iOS `completion(deepLink)` ordering).
                    callback(deepLink)
                }
                .addOnFailureListener { e ->
                    // The fetch never reached the server: keep the chance for the next launch.
                    recordFailedAttempt(e.message ?: e::class.java.simpleName)
                    callback(null)
                }
        }
    }

    private suspend fun resolveVisitorId(): String? {
        // Strategy 1: the clipboard (the web page sets it before the store redirect) — read only when
        // the system can tell, WITHOUT the read, that it holds a web link. See [ClipboardReading].
        try {
            if (DeferredLinkPasteboard.shouldRead(clipboard.hasText(), clipboard.isProbableWebUrl())) {
                val visitorId = DeferredLinkPasteboard.visitorId(clipboard.readText())
                if (visitorId != null) {
                    // Clear only OUR marker: someone else's link stays where they put it.
                    clipboard.clear()
                    Log.debug("DeferredDeepLink: resolved visitor ID from clipboard")
                    return visitorId
                }
            } else {
                Log.debug("DeferredDeepLink: clipboard holds no link — not read, so no 'pasted' toast")
            }
        } catch (e: Throwable) {
            Log.warning("DeferredDeepLink: clipboard probe failed: ${e.message}")
        }

        // Strategy 2: Android Install Referrer (non-blocking)
        val referrerVisitorId = getInstallReferrerVisitorId()
        if (referrerVisitorId != null) {
            Log.debug("DeferredDeepLink: resolved visitor ID from Install Referrer")
            return referrerVisitorId
        }

        // ANDROID_ID fingerprint fallback. Salted SHA-256
        // hash so the value is opaque and stable across launches without
        // exposing the raw device id. iOS uses IDFV here; Android's closest
        // equivalent is `Settings.Secure.ANDROID_ID`.
        return getAndroidIdFingerprint()
    }

    /**
     * Non-blocking Install Referrer probe. Wraps the
     * callback-based [InstallReferrerClient] in a suspend coroutine that
     * times out after 2 seconds without holding a thread.
     */
    private suspend fun getInstallReferrerVisitorId(): String? {
        return withTimeoutOrNull(INSTALL_REFERRER_TIMEOUT_MS) {
            suspendCancellableCoroutine<String?> { cont ->
                val client = try {
                    InstallReferrerClient.newBuilder(context).build()
                } catch (e: Throwable) {
                    Log.warning("DeferredDeepLink: InstallReferrerClient.newBuilder failed: ${e.message}")
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation {
                    runCatching { client.endConnection() }
                }
                try {
                    client.startConnection(object : InstallReferrerStateListener {
                        override fun onInstallReferrerSetupFinished(responseCode: Int) {
                            var visitorId: String? = null
                            if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                                try {
                                    val referrer = client.installReferrer.installReferrer
                                    val uri = Uri.parse("https://referrer?$referrer")
                                    visitorId = uri.getQueryParameter("appdna_visitor")
                                } catch (e: Exception) {
                                    Log.error("DeferredDeepLink: Install Referrer parse error: ${e.message}")
                                }
                            }
                            runCatching { client.endConnection() }
                            if (cont.isActive) cont.resume(visitorId)
                        }

                        override fun onInstallReferrerServiceDisconnected() {
                            runCatching { client.endConnection() }
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                } catch (e: Throwable) {
                    Log.warning("DeferredDeepLink: Install Referrer error: ${e.message}")
                    runCatching { client.endConnection() }
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    /**
     * Salted SHA-256 of `Settings.Secure.ANDROID_ID`. We
     * don't return the raw `ANDROID_ID` because (a) the backend deferred-link
     * lookup expects a visitor-id-shaped opaque string and (b) raw ANDROID_ID
     * may be considered a "device identifier" by Google Play policy in some
     * contexts. Hashing keeps the fingerprint stable per-(install × signing
     * key) without exposing the raw value.
     */
    private fun getAndroidIdFingerprint(): String? {
        return try {
            val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            if (raw.isNullOrBlank() || raw == "9774d56d682e549c") return null
            val md = MessageDigest.getInstance("SHA-256")
            val salt = "appdna-deferred-link-v1".toByteArray(Charsets.UTF_8)
            md.update(salt)
            val digest = md.digest(raw.toByteArray(Charsets.UTF_8))
            "androidid_" + digest.joinToString("") { "%02x".format(it) }.take(40)
        } catch (e: Throwable) {
            Log.warning("DeferredDeepLink: ANDROID_ID fallback failed: ${e.message}")
            null
        }
    }

    private fun isFirstLaunch(): Boolean {
        val prefs = context.getSharedPreferences("ai.appdna.sdk", Context.MODE_PRIVATE)
        return !prefs.getBoolean(FIRST_LAUNCH_KEY, false)
    }

    private fun markLaunched() {
        val prefs = context.getSharedPreferences("ai.appdna.sdk", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(FIRST_LAUNCH_KEY, true).remove(FAILED_ATTEMPTS_KEY).apply()
    }

    /**
     * Count a check that never got an answer, and give up once it has happened
     * [MAX_FAILED_ATTEMPTS] times — at which point the chance IS spent, so the SDK stops asking.
     */
    private fun recordFailedAttempt(reason: String) {
        val prefs = context.getSharedPreferences("ai.appdna.sdk", Context.MODE_PRIVATE)
        val attempts = prefs.getInt(FAILED_ATTEMPTS_KEY, 0) + 1
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            Log.warning(
                "DeferredDeepLink: giving up after $attempts failed attempts ($reason) — " +
                    "no deferred link will be resolved for this install",
            )
            markLaunched()
            return
        }
        prefs.edit().putInt(FAILED_ATTEMPTS_KEY, attempts).apply()
        Log.debug("DeferredDeepLink: attempt $attempts of $MAX_FAILED_ATTEMPTS failed ($reason) — will retry next launch")
    }

    /**
     * Cancel the private scope so
     * pending Install-Referrer reads and Firestore deferred-deep-link
     * listeners don't outlive [AppDNA.shutdown].
     */
    internal fun shutdown() {
        scope.cancel()
    }
}

/**
 * The clipboard the deferred deep link reads its visitor id from.
 *
 * [hasText] and [isProbableWebUrl] read only the clip's DESCRIPTION, which Android never announces;
 * [readText] reads the content, which on Android 12+ shows the system "<App> pasted from your
 * clipboard" toast.
 */
internal interface ClipboardReading {
    fun hasText(): Boolean
    fun isProbableWebUrl(): Boolean
    fun readText(): String?
    fun clear()
}

/**
 * 🔴 READ THE CLIPBOARD ONLY WHEN IT HOLDS A LINK.
 *
 * This read `primaryClip` on every first launch, looking for the visitor id a web page may have
 * copied before the store redirect. On Android 12+ every content read shows a "pasted from your
 * clipboard" toast — so every first launch of every host app whose user had copied anything showed
 * it, for a feature almost no install uses. iOS showed a blocking paste alert for the same read.
 *
 * Android 12+ classifies clip text itself (`ClipDescription.getConfidenceScore(TYPE_URL)`), and
 * reading the description does not toast — so the content is read only when that classification
 * is complete and says URL. Before Android 12 there is no toast and no classification, so the
 * content is read as before, and only our own marker is used or cleared.
 */
internal class SystemClipboard(private val context: Context) : ClipboardReading {
    private val manager: android.content.ClipboardManager?
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager

    override fun hasText(): Boolean {
        val m = manager ?: return false
        if (!m.hasPrimaryClip()) return false
        val d = m.primaryClipDescription ?: return false
        return d.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN) ||
            d.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_URILIST) ||
            d.hasMimeType("text/*")
    }

    override fun isProbableWebUrl(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return true
        val d = manager?.primaryClipDescription ?: return false
        return isUrlClassified(d)
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.S)
    private fun isUrlClassified(d: android.content.ClipDescription): Boolean {
        if (d.classificationStatus != android.content.ClipDescription.CLASSIFICATION_COMPLETE) return false
        return try {
            d.getConfidenceScore(android.view.textclassifier.TextClassifier.TYPE_URL) >= URL_CONFIDENCE
        } catch (_: IllegalStateException) {
            false
        }
    }

    override fun readText(): String? {
        val clip = manager?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.toString()
    }

    override fun clear() {
        val m = manager ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            m.clearPrimaryClip()
        } else {
            m.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
        }
    }

    private companion object {
        const val URL_CONFIDENCE = 0.5f
    }
}

/**
 * WHETHER the deferred deep link may read the clipboard, and what it accepts from it. Mirror of iOS
 * `DeferredLinkPasteboard`, pinned by the shared `deferred_link_pasteboard` fixture.
 *
 * Accepted content: a http(s) URL carrying an `appdna_visitor` query parameter (the key the Install
 * Referrer carries too), or the legacy `appdna:visitor:<id>` marker. A visitor id is 1-128 ASCII
 * letters, digits, `-` or `_`, because it becomes a Firestore document path segment.
 */
internal object DeferredLinkPasteboard {
    const val LEGACY_PREFIX = "appdna:visitor:"
    const val QUERY_PARAM = "appdna_visitor"

    fun shouldRead(hasText: Boolean, probableWebUrl: Boolean): Boolean = hasText && probableWebUrl

    fun visitorId(text: String?): String? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (raw.startsWith(LEGACY_PREFIX)) {
            val id = raw.removePrefix(LEGACY_PREFIX)
            return id.takeIf(::isVisitorIdShaped)
        }
        val uri = try { java.net.URI(raw) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val query = uri.rawQuery ?: return null
        val id = query.split('&').firstNotNullOfOrNull { pair ->
            val i = pair.indexOf('=')
            val key = if (i < 0) pair else pair.substring(0, i)
            if (decode(key) == QUERY_PARAM) decode(if (i < 0) "" else pair.substring(i + 1)) else null
        } ?: return null
        return id.takeIf(::isVisitorIdShaped)
    }

    fun isVisitorIdShaped(id: String): Boolean =
        id.length in 1..128 && id.all { (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') || it == '-' || it == '_' }

    private fun decode(s: String): String =
        try { java.net.URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
}
