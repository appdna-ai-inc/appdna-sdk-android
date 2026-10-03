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
    private val eventTracker: EventTracker?
) {
    companion object {
        private const val FIRST_LAUNCH_KEY = "ai.appdna.sdk.first_launch_completed"
        private const val EXPIRY_HOURS = 72L
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
                Log.warning("DeferredDeepLink: Firestore not available")
                markLaunched()
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
                    markLaunched()
                    Log.error("DeferredDeepLink fetch failed: ${e.message}")
                    callback(null)
                }
        }
    }

    private suspend fun resolveVisitorId(): String? {
        // Strategy 1: Check clipboard for visitor ID (web page sets it before store redirect)
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clipData = clipboard?.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0).text?.toString()
                if (text != null && text.startsWith("appdna:visitor:")) {
                    val visitorId = text.removePrefix("appdna:visitor:")
                    // Clear clipboard
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                    Log.debug("DeferredDeepLink: resolved visitor ID from clipboard")
                    return visitorId
                }
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
        prefs.edit().putBoolean(FIRST_LAUNCH_KEY, true).apply()
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
