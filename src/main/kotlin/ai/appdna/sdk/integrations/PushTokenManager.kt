package ai.appdna.sdk.integrations

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.storage.LocalStorage
import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Manages push token capture, storage, backend registration, and delivery tracking.
 */
internal class PushTokenManager(
    private val context: Context,
    private val storage: LocalStorage,
    private val eventTracker: EventTracker,
    private val apiClient: ApiClient? = null,
    /**
     * Who the token belongs to, read at send time: the device's anonymous id and, once `identify` ran, the
     * host's user id. Without them the server stored every token as user `anonymous`, so a push to a user, a
     * list or a segment found no token for an SDK device.
     */
    private val identity: () -> Pair<String?, String?> = { null to null },
) {
    companion object {
        private const val KEY_PUSH_TOKEN = "push_token"
        /**
         * Request code passed to [ActivityCompat.requestPermissions] when
         * [requestPermission] is invoked with an Activity. Hosts can match this
         * value in `onRequestPermissionsResult` to identify the SDK-initiated
         * permission prompt.
         */
        const val PUSH_PERMISSION_REQUEST_CODE = 0xAD7A
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Current push token from local storage. */
    var currentToken: String?
        get() = storage.getString(KEY_PUSH_TOKEN)
        private set(value) {
            if (value != null) storage.setString(KEY_PUSH_TOKEN, value)
        }

    /** Delegate for push notification lifecycle events. */
    var pushListener: ai.appdna.sdk.AppDNAPushDelegate? = null

    /**
     * Request push notification permission.
     *
     * Mirrors iOS PushTokenManager.swift requestAuthorization. On Android API 33+
     * (TIRAMISU) we request POST_NOTIFICATIONS at runtime; pre-33 the manifest
     * declaration is sufficient and we treat the call as already-granted.
     *
     * The runtime prompt must come from an Activity context. Pass the host
     * Activity in via [activity] — if null we fall back to the SDK app context
     * which on API 33+ cannot show a prompt; in that case we log a warning so
     * the host knows to surface the prompt themselves.
     */
    fun requestPermission(activity: android.app.Activity? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.info("Push permission: pre-API-33, manifest declaration is sufficient")
            return
        }
        val perm = android.Manifest.permission.POST_NOTIFICATIONS
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, perm) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            Log.info("Push permission already granted")
            return
        }
        val host = activity
        if (host != null) {
            androidx.core.app.ActivityCompat.requestPermissions(host, arrayOf(perm), PUSH_PERMISSION_REQUEST_CODE)
        } else {
            Log.warning("Push permission request: pass an Activity to requestPermission() to show the runtime prompt; otherwise call ActivityCompat.requestPermissions(activity, [POST_NOTIFICATIONS], ${PUSH_PERMISSION_REQUEST_CODE}) yourself")
        }
    }

    /**
     * Store the push token, send a registration event, and register with backend.
     */
    fun setPushToken(token: String) {
        val previousToken = storage.getString(KEY_PUSH_TOKEN)
        storage.setString(KEY_PUSH_TOKEN, token)

        if (token != previousToken) {
            val hashedToken = sha256(token)
            eventTracker.track("push_token_registered", mapOf(
                "token_hash" to hashedToken,
                "platform" to "android"
            ))
            Log.info("Push token registered (hash: ${hashedToken.take(12)}...)")

            // Fire onPushTokenRegistered to the host's
            // AppDNAPushDelegate. Mirrors iOS PushTokenManager.swift:51.
            // Posted to main thread so host code runs off the FCM listener.
            try {
                val listener = pushListener
                if (listener != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            listener.onPushTokenRegistered(token)
                        } catch (e: Throwable) {
                            Log.warning("AppDNAPushDelegate.onPushTokenRegistered threw: ${e.message}")
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.warning("PushTokenManager: delegate fan-out failed: ${e.message}")
            }
        }

        // Register token with backend (POST /api/v1/push/token)
        registerTokenWithBackend(token)
    }

    /**
     * Track push permission status.
     */
    fun setPushPermission(granted: Boolean) {
        if (granted) {
            eventTracker.track("push_permission_granted")
        } else {
            eventTracker.track("push_permission_denied")
        }
        Log.info("Push permission: ${if (granted) "granted" else "denied"}")
    }

    /**
     * Track that a push notification was delivered.
     */
    fun trackDelivered(pushId: String, deliveryId: String? = null) {
        // The per-token delivery id rides along when the payload carries one, so the
        // server updates THAT delivery row (and counts only a real status transition).
        val props = mutableMapOf<String, Any>("push_id" to pushId)
        deliveryId?.takeIf { it.isNotBlank() }?.let { props["delivery_id"] = it }
        eventTracker.track("push_delivered", props)
        scope.launch {
            try {
                val body = JSONObject().apply {
                    put("push_id", pushId)
                    deliveryId?.takeIf { it.isNotBlank() }?.let { put("delivery_id", it) }
                }
                apiClient?.post("/api/v1/push/delivered", body.toString())
            } catch (e: Exception) {
                Log.warning("Failed to track push delivered: ${e.message}")
            }
        }
    }

    /**
     * Track that a push notification was tapped.
     */
    fun trackTapped(pushId: String, action: String? = null, deliveryId: String? = null) {
        val props = mutableMapOf<String, Any>("push_id" to pushId)
        action?.let { props["action"] = it }
        deliveryId?.takeIf { it.isNotBlank() }?.let { props["delivery_id"] = it }
        eventTracker.track("push_tapped", props)
        scope.launch {
            try {
                val body = JSONObject().apply {
                    put("push_id", pushId)
                    deliveryId?.takeIf { it.isNotBlank() }?.let { put("delivery_id", it) }
                }
                apiClient?.post("/api/v1/push/tapped", body.toString())
            } catch (e: Exception) {
                Log.warning("Failed to track push tapped: ${e.message}")
            }
        }
    }

    /**
     * Called when FCM token refreshes — re-register.
     */
    fun onNewToken(token: String) {
        setPushToken(token)
    }

    /**
     * Proactively fetch FCM token and register with backend.
     *
     * (part 1) — bind to the SDK's secondary FirebaseApp
     * ("appdna") so AppDNA receives push tokens issued against the AppDNA
     * Firebase project — not the host app's default FirebaseApp (which
     * would otherwise produce tokens that AppDNA's backend can't deliver
     * to). When the secondary app is unavailable (host hasn't shipped
     * `google-services-appdna.json`) we fall back to the default app and
     * log loudly, matching the existing fallback contract elsewhere in the
     * SDK.
     */
    fun registerToken() {
        try {
            val messaging = appdnaScopedMessaging() ?: com.google.firebase.messaging.FirebaseMessaging.getInstance()
            messaging.token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    if (token != null) setPushToken(token)
                } else {
                    Log.warning("Failed to get FCM token: ${task.exception?.message}")
                }
            }
        } catch (e: Exception) {
            Log.warning("Firebase not available: ${e.message}")
        }
    }

    /**
     * Returns the FirebaseMessaging instance bound to the AppDNA secondary
     * FirebaseApp ("appdna"), or null if the secondary app isn't initialized
     * (caller should fall back to the default app).
     */
    private fun appdnaScopedMessaging(): com.google.firebase.messaging.FirebaseMessaging? {
        return try {
            // Scope FCM to the secondary "appdna" FirebaseApp.
            // FirebaseMessaging.getInstance(FirebaseApp) is package-private; we
            // resolve via the FirebaseApp's component container (the same path
            // the public no-arg getInstance uses for the default app).
            val app = com.google.firebase.FirebaseApp.getInstance("appdna")
            app.get(com.google.firebase.messaging.FirebaseMessaging::class.java)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Registers the stored token again, with the identity as it is NOW. `identify` and `reset` call this, so
     * the server's row follows the user who is signed in. No stored token, nothing sent.
     */
    fun reregister() {
        val token = currentToken?.takeIf { it.isNotEmpty() } ?: return
        registerTokenWithBackend(token)
    }

    private fun registerTokenWithBackend(token: String) {
        scope.launch {
            try {
                val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                val appVersion = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
                } catch (_: Exception) { "" }

                val (anonId, userId) = identity()
                val body = PushTokenRegistration.body(
                    token = token,
                    platform = "android",
                    deviceId = deviceId ?: "",
                    appVersion = appVersion,
                    sdkVersion = AppDNA.sdkVersion,
                    // Normalized to the 3-part dotted form so cross-platform OS-version cohort dashboards
                    // bucket Android the same as iOS (bare "14" before).
                    osVersion = ai.appdna.sdk.events.EventSchema.normalizeOsVersion(Build.VERSION.RELEASE),
                    anonId = anonId,
                    userId = userId,
                    pushDisplay = PushDisplayProbe.pushDisplay(context),
                )
                apiClient?.post("/api/v1/push/token", body.toString())
                Log.info("Push token registered with backend")
            } catch (e: Exception) {
                Log.warning("Failed to register push token with backend: ${e.message}")
            }
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Cancel the background scope so any pending
     * delivered/tapped POSTs do not outlive [AppDNA.shutdown]. Safe to call
     * multiple times.
     */
    internal fun shutdown() {
        scope.cancel()
        pushListener = null
    }
}

/**
 * The body of `POST /api/v1/push/token`. Shared with iOS and driven by the `push_token_registration_body`
 * fixture: `anon_id` and `user_id` ride along when known (an empty string is not an id), so the server
 * stores who the token belongs to.
 */
internal object PushTokenRegistration {
    fun body(
        token: String, platform: String, deviceId: String, appVersion: String,
        sdkVersion: String, osVersion: String, anonId: String?, userId: String?,
        pushDisplay: String? = null,
    ): JSONObject = JSONObject().apply {
        put("token", token)
        put("platform", platform)
        put("device_id", deviceId)
        put("app_version", appVersion)
        put("sdk_version", sdkVersion)
        put("os_version", osVersion)
        if (!anonId.isNullOrEmpty()) put("anon_id", anonId)
        if (!userId.isNullOrEmpty()) put("user_id", userId)
        // #1786 — `sdk` when the SDK's own service receives FCM messages here, so the server may send data-only
        // messages that the SDK draws and confirms delivery for (also in the background). Absent otherwise.
        if (!pushDisplay.isNullOrEmpty()) put("push_display", pushDisplay)
    }
}

/**
 * #1786 — whether the SDK's [AppDNAMessagingService] (or a host subclass of it) is the service FCM delivers messages
 * to in this app. Firebase hands `com.google.firebase.MESSAGING_EVENT` to the ONE enabled service that resolves for
 * the package; when it is the SDK's, a data-only AppDNA push reaches the SDK in the foreground and the background, and
 * the SDK draws it and reports `push_delivered`. When the host runs its own service (and disabled the SDK's), the
 * server keeps sending notification messages FCM draws itself, because a data-only message would depend on the host
 * displaying it.
 */
internal object PushDisplayProbe {
    const val MESSAGING_EVENT = "com.google.firebase.MESSAGING_EVENT"
    const val SDK = "sdk"

    /** Test seam: replaces the resolution (production: the package manager). */
    @Volatile internal var resolvedServiceOverride: (() -> String?)? = null

    fun pushDisplay(context: Context): String? = if (sdkServiceReceivesMessages(context)) SDK else null

    fun sdkServiceReceivesMessages(context: Context): Boolean {
        val name = resolvedServiceOverride?.invoke() ?: resolvedServiceName(context) ?: return false
        return try {
            AppDNAMessagingService::class.java.isAssignableFrom(Class.forName(name, false, context.classLoader))
        } catch (_: Throwable) {
            false
        }
    }

    private fun resolvedServiceName(context: Context): String? = try {
        val intent = android.content.Intent(MESSAGING_EVENT).setPackage(context.packageName)
        context.packageManager.resolveService(intent, 0)?.serviceInfo?.name
    } catch (_: Throwable) {
        null
    }
}
