package ai.appdna.sdk.integrations

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import ai.appdna.sdk.PushAction
import ai.appdna.sdk.PushPayload
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Firebase Cloud Messaging service for handling push notifications.
 * Apps should extend this or register it in AndroidManifest.xml.
 *
 * SPEC-070-A H.5–H.22 — full push hardening:
 *   H.6: dismissal broadcast wired via setDeleteIntent → push_dismissed event
 *   H.8: AppDNAPushDelegate.onPushReceived fires before notify()
 *   H.10: payload schema parses canonical iOS-shaped action/actions plus legacy flat keys
 *   H.16: foreground state inferred from ActivityManager (works in :fcm process)
 *   H.19: server-supplied channel group honored for a named (non-default) channel
 *   H.21: notificationIcon resolved via Configuration → manifest meta-data → app icon
 *   H.21b: server-supplied channel importance / name / description apply to the custom-sound channel
 *          only; the default `appdna_push` channel always takes the SDK's own settings
 *   H.22: a notification+data payload reaches this service only in the foreground (FCM displays
 *         it itself in the background), so the SDK displays it here like a data-only payload
 */
open class AppDNAMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        // SPEC-497 §9.2 (B2) — only AppDNA's own messages. A host's (or a third-party pusher's)
        // message is not tracked, fires no delegate and is never shown as a blank notification.
        if (!AppDNA.push.isAppDNAMessage(message.data)) return

        // Tracks push_delivered (with delivery_id), records the session push id and fires
        // AppDNAPushDelegate.onPushReceived BEFORE the notification is built (SPEC-070-A H.7/H.8).
        // A message a host already forwarded through AppDNA.push.handleMessage* is not re-handled.
        val outcome = AppDNA.push.receive(
            message.data,
            message.notification?.title,
            message.notification?.body,
            applicationContext,
        )
        if (outcome != ai.appdna.sdk.PushModule.ReceiveOutcome.HANDLED) return

        // A message with a `notification` block reaches onMessageReceived ONLY while the app is in
        // the foreground — in the background FCM displays it itself and never calls this service. So
        // here nothing else will show it: the SDK displays it with the same channel and visuals as a
        // data-only message (the notification block's title / body fill a missing data title / body).
        // (It used to return here on the belief that FCM auto-displays it — true only in the
        // background, so a foreground notification message was never shown.) A message the host
        // already forwarded through AppDNA.push.handleMessage* returned above (not HANDLED), so the
        // host's own display is never doubled.
        val notifTitle = message.notification?.title
        val notifBody = message.notification?.body
        // SPEC-497 §9.2 — an AppDNA message with a blank title AND body is data for the app, not
        // something to show: no blank notification.
        if (!AppDNANotificationPoster.isDisplayable(message.data, notifTitle, notifBody)) {
            Log.debug("Push has no title or body — not displaying a notification")
            return
        }

        AppDNANotificationPoster.ensureNotificationChannel(this, message.data)
        val notification = buildNotification(message)
        try {
            NotificationManagerCompat.from(this)
                .notify(AppDNANotificationPoster.notificationId(message.data), notification)
            Log.debug("Push displayed (id=${message.data["push_id"]})")
        } catch (e: SecurityException) {
            Log.warning("Missing POST_NOTIFICATIONS permission")
        }
    }

    override fun onNewToken(token: String) {
        AppDNA.onNewPushToken(token)
    }

    protected open fun buildNotification(message: RemoteMessage): android.app.Notification =
        AppDNANotificationPoster.build(this, message.data, message.notification?.title, message.notification?.body)

    companion object {
        const val DEFAULT_CHANNEL_ID = "appdna_push"

        /**
         * SPEC-070-A H.16: foreground detection that works in any process,
         * including `:fcm`. Reads the running-process importance of the
         * app's main process — `IMPORTANCE_FOREGROUND` means the user is
         * actively interacting; anything else means backgrounded or killed.
         */
        internal fun isAppInForeground(context: Context): Boolean {
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    ?: return false
                val processes = am.runningAppProcesses ?: return false
                val pkg = context.packageName
                processes.any {
                    it.processName == pkg && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                }
            } catch (_: Throwable) {
                false
            }
        }
    }
}

/**
 * The notification the SDK posts for a data-only AppDNA push — shared by [AppDNAMessagingService]
 * and `AppDNA.push.handleMessage` (a host that owns Firebase Messaging but lets AppDNA display).
 */
internal object AppDNANotificationPoster {

    /** `delivery_id ?: push_id` — one notification (and one set of pending intents) per delivery. */
    fun deliveryKey(data: Map<String, String>): String =
        data["delivery_id"]?.takeIf { it.isNotBlank() } ?: data["push_id"].orEmpty()

    /**
     * SPEC-497 §9.2 — keyed on the delivery, so two deliveries of one campaign (same `push_id`,
     * different `delivery_id`) are two notifications that keep their own extras.
     */
    fun notificationId(data: Map<String, String>): Int = deliveryKey(data).hashCode()

    fun isDisplayable(data: Map<String, String>, notifTitle: String? = null, notifBody: String? = null): Boolean =
        !((data["title"] ?: notifTitle).isNullOrBlank() && (data["body"] ?: notifBody).isNullOrBlank())

    /** Post the notification for [data] unless it has nothing to show. */
    fun post(context: Context, data: Map<String, String>, notifTitle: String?, notifBody: String?) {
        if (!isDisplayable(data, notifTitle, notifBody)) return
        ensureNotificationChannel(context, data)
        val notification = build(context, data, notifTitle, notifBody)
        try {
            NotificationManagerCompat.from(context).notify(notificationId(data), notification)
        } catch (e: SecurityException) {
            Log.warning("Missing POST_NOTIFICATIONS permission")
        }
    }

    fun build(
        context: Context,
        data: Map<String, String>,
        notifTitle: String?,
        notifBody: String?,
    ): android.app.Notification {
        // SPEC-497 §17 item 28: a bundled custom sound posts on its own `appdna_sound_<key>` channel
        // (Android 8+ plays the channel's sound); pre-8 the sound is set on the notification below.
        val soundChannel = PushSoundChannels.ensureChannel(context, data)
        val channelId = soundChannel ?: data["channel_id"]?.takeIf { it.isNotBlank() } ?: AppDNAMessagingService.DEFAULT_CHANNEL_ID
        // SPEC-088: Interpolate push title and body via TemplateEngine
        val pushCtx = ai.appdna.sdk.core.TemplateEngine.buildContext()
        val title = ai.appdna.sdk.core.TemplateEngine.interpolate(
            data["title"] ?: notifTitle ?: "", pushCtx
        )
        val body = ai.appdna.sdk.core.TemplateEngine.interpolate(
            data["body"] ?: notifBody ?: "", pushCtx
        )
        val pushId = data["push_id"] ?: ""
        val key = deliveryKey(data)

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(body)
            // SPEC-070-A H.21: resolve icon via Configuration.notificationIcon →
            // manifest <meta-data> → app icon. setSmallIcon throws when given a
            // 0/missing res id, so we always have a valid fallback.
            .setSmallIcon(NotificationIconResolver.resolve(context.applicationContext ?: context))
            .setAutoCancel(true)
            .setPriority(
                if (data["priority"] == "high") NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )

        // SPEC-085: Rich image via RichPushHandler for reliable download with timeout
        data["image_url"]?.let { url ->
            try {
                val richBuilder = RichPushHandler.buildRichNotification(
                    context = context,
                    channelId = channelId,
                    title = title,
                    body = body,
                    imageUrl = url,
                    mediaType = data["media_type"],
                    customSound = data["sound"],
                )
                // Copy the style from the rich builder
                val richNotification = richBuilder.build()
                richNotification.extras?.let { extras ->
                    builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(
                        extras.getParcelable("android.picture") as? Bitmap
                    ))
                    extras.getParcelable<Bitmap>("android.largeIcon")?.let { icon ->
                        builder.setLargeIcon(icon)
                    }
                }
            } catch (e: Exception) {
                Log.warning("Failed to download push image: ${e.message}")
            }
        }

        if (soundChannel != null) PushSoundChannels.soundUri(context, data["sound"])?.let { builder.setSound(it) }

        // Custom color
        data["color"]?.let { color ->
            try {
                builder.setColor(android.graphics.Color.parseColor(color))
            } catch (_: Exception) {}
        }

        // Tap intent with push_id for tracking + iOS-canonical action fields
        // (action_type, action_value, screen_id, deep_link).
        val tapIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra("push_id", pushId)
            // SPEC-497 §9.2 — the marker and the delivery id reach handlePushTap, which is gated on
            // the marker and tracks the tap against this delivery.
            putExtra("appdna", data["appdna"])
            putExtra("delivery_id", data["delivery_id"])
            putExtra("action", data["action"])
            putExtra("actions", data["actions"])
            putExtra("action_type", data["action_type"])
            putExtra("action_value", data["action_value"])
            putExtra("screen_id", data["screen_id"])
            putExtra("deep_link", data["deep_link"])
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (tapIntent != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context, key.hashCode(), tapIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }

        // SPEC-070-A H.6: dismissal intent → push_dismissed event broadcast.
        // Set scheme + data via setData() so we don't shadow the outer
        // `data: Map<String,String>` inside the apply block.
        val dismissIntent = Intent(context, PushDismissReceiver::class.java).apply {
            action = PushDismissReceiver.ACTION_PUSH_DISMISSED
            putExtra("push_id", pushId)
            putExtra("delivery_id", data["delivery_id"])
            setData(android.net.Uri.parse("appdna://push/dismiss/${android.net.Uri.encode(key)}"))
        }
        builder.setDeleteIntent(
            PendingIntent.getBroadcast(
                context, key.hashCode(), dismissIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )

        // SPEC-084 / SPEC-497 §17 item 28: action buttons (dismiss / background buttons via
        // PushActionReceiver, text_reply with a RemoteInput) + SPEC-088 label interpolation.
        PushNotificationActions.addTo(builder, context, data, key, notificationId(data)) { label ->
            ai.appdna.sdk.core.TemplateEngine.interpolate(label, pushCtx)
        }

        return builder.build()
    }

    /**
     * Creates the channel a push without a bundled custom sound posts on, if it does not exist yet:
     * `data.channel_id` (a channel the host or the campaign's custom data names), else the SDK's default
     * `appdna_push`.
     *
     * The channel is created with the SDK's OWN settings — name "Push Notifications", importance high, no
     * description — whatever the push says. `channel_importance` / `channel_name` / `channel_description`
     * describe the CUSTOM-SOUND channel only ([PushSoundChannels]). They used to be read here too, and a
     * channel's settings are fixed once it exists, so the first push to reach a device decided the default
     * channel for good: one campaign sent at Low or Min importance silenced every later push without a
     * custom sound. `channel_group` (SPEC-070-A H.19) still applies to a named non-default channel; the
     * default channel joins no group.
     */
    fun ensureNotificationChannel(context: Context, data: Map<String, String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        ensureNotificationChannelOreoPlus(context, data)
    }

    /** The default channel's name in the system settings. */
    const val DEFAULT_CHANNEL_NAME = "Push Notifications"

    /**
     * SPEC-070-A J.15 — extracted helper that ASSUMES API 26+ so Android Lint
     * flags any accidental call that isn't behind a
     * `Build.VERSION.SDK_INT >= O` guard. [NotificationChannel] and the
     * `NotificationManager.getNotificationChannel` / `createNotificationChannel`
     * APIs were introduced in API 26.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun ensureNotificationChannelOreoPlus(context: Context, data: Map<String, String>) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channelId = data["channel_id"]?.takeIf { it.isNotBlank() } ?: AppDNAMessagingService.DEFAULT_CHANNEL_ID
        if (manager.getNotificationChannel(channelId) != null) return

        val channel = NotificationChannel(channelId, DEFAULT_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
            data["channel_group"]?.takeIf { channelId != AppDNAMessagingService.DEFAULT_CHANNEL_ID }?.let {
                // Channel group must exist before the channel can adopt it; create
                // both lazily.
                if (manager.getNotificationChannelGroup(it) == null) {
                    manager.createNotificationChannelGroup(
                        android.app.NotificationChannelGroup(it, it),
                    )
                }
                group = it
            }
        }
        manager.createNotificationChannel(channel)
    }

}

/**
 * SPEC-070-A H.6: Receives the dismissal broadcast (the notification's deleteIntent, an explicit
 * intent for this class) and emits a `push_dismissed` event on behalf of the user. It MUST be declared
 * in the manifest — the SDK's own `AndroidManifest.xml` declares it (not exported): an explicit
 * broadcast to an undeclared receiver is never delivered, which is why `push_dismissed` used to be
 * tracked never.
 */
internal class PushDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PUSH_DISMISSED) return
        val pushId = intent.getStringExtra("push_id") ?: return
        try {
            AppDNA.track("push_dismissed", mapOf("push_id" to pushId))
        } catch (e: Throwable) {
            Log.warning("PushDismissReceiver: failed to track push_dismissed: ${e.message}")
        }
    }

    companion object {
        const val ACTION_PUSH_DISMISSED = "ai.appdna.sdk.PUSH_DISMISSED"
    }
}

/**
 * SPEC-070-A H.21: shared resolver so both [AppDNAMessagingService] and
 * [RichPushHandler] use identical icon-resolution logic.
 */
internal object NotificationIconResolver {
    fun resolve(context: Context): Int {
        // 1. Configuration override (set by host via AppDNA.notificationIcon).
        val override = AppDNA.notificationIcon
        if (override != 0) return override

        // 2. Manifest meta-data (matches Firebase Messaging convention).
        try {
            val ai = context.packageManager
                .getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA)
            val metaName = "com.google.firebase.messaging.default_notification_icon"
            val resId = ai.metaData?.getInt(metaName, 0) ?: 0
            if (resId != 0) return resId
        } catch (_: Throwable) { /* fall through */ }

        // 3. Last resort — application icon (NotificationCompat will reject 0).
        val fallback = context.applicationInfo.icon
        return if (fallback != 0) fallback else android.R.drawable.ic_dialog_info
    }

    /**
     * SPEC-070-A finalization B3#P1 — resolve a push action button icon ref
     * to an Android drawable resource id, given a host context. Accepts:
     *   1. Flat string `"ic_email"` → host R.drawable lookup.
     *   2. JSON dict `{library:"lucide"|"material"|"sf-symbols"|"emoji", name:"mail"}`
     *      → try host R.drawable named after `name`, then fall back to a
     *      small map of common Lucide/Material names → `android.R.drawable.*`.
     * Mirrors iOS `IconMapping.lucideToSFSymbol` at
     * Push/PushNotificationHandler.swift:62-95.
     */
    fun resolvePushActionIconRes(context: Context, raw: String): Int {
        val (library, name) = try {
            val obj = org.json.JSONObject(raw)
            (obj.optString("library").takeIf { it.isNotEmpty() } ?: "lucide") to obj.optString("name", "")
        } catch (_: Throwable) {
            "" to raw
        }
        if (name.isEmpty()) return 0

        val hostId = context.resources.getIdentifier(name, "drawable", context.packageName)
        if (hostId != 0) return hostId

        val lower = name.lowercase().replace('-', '_')
        return when (library.lowercase()) {
            "lucide", "material", "sf-symbols", "" -> when (lower) {
                "mail", "envelope", "email" -> android.R.drawable.ic_dialog_email
                "phone", "call" -> android.R.drawable.ic_menu_call
                "share", "share_2" -> android.R.drawable.ic_menu_share
                "delete", "trash", "trash_2", "x", "close" -> android.R.drawable.ic_menu_delete
                "edit", "edit_3", "pencil" -> android.R.drawable.ic_menu_edit
                "info" -> android.R.drawable.ic_dialog_info
                "alert_triangle", "warning" -> android.R.drawable.ic_dialog_alert
                "check", "check_circle" -> android.R.drawable.checkbox_on_background
                "search" -> android.R.drawable.ic_menu_search
                "settings", "cog", "gear" -> android.R.drawable.ic_menu_preferences
                "calendar" -> android.R.drawable.ic_menu_my_calendar
                "map_pin", "map", "location" -> android.R.drawable.ic_menu_mylocation
                "external_link", "link" -> android.R.drawable.ic_menu_view
                else -> 0
            }
            else -> 0
        }
    }
}

/**
 * SPEC-070-A H.7: session-scoped `push_id` propagation. We persist the
 * latest delivered push_id in SharedPreferences so the next event session
 * (which may be a different process or a cold launch from tap) can fold
 * it into `context.push_id` for attribution. Mirrors iOS
 * `PushNotificationHandler.lastPushId` (Push/PushNotificationHandler.swift).
 */
internal object PushSessionContext {
    private const val PREFS_NAME = "ai.appdna.sdk.push"
    private const val KEY_LAST_PUSH_ID = "last_push_id"
    private const val KEY_LAST_PUSH_AT = "last_push_at"
    // Treat a push older than 30 minutes as no longer "current" — matches
    // SessionManager rotation (SPEC-070-A A.8).
    private const val WINDOW_MS = 30 * 60 * 1000L

    fun recordPushReceived(context: Context, pushId: String) {
        if (pushId.isBlank()) return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_LAST_PUSH_ID, pushId)
                .putLong(KEY_LAST_PUSH_AT, System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) { /* SharedPreferences is best-effort */ }
    }

    /**
     * Returns the last push_id received within the rolling 30-minute window.
     * Returned value is consumed lazily — callers do NOT clear it; the
     * window logic ensures stale ids age out.
     */
    fun currentPushId(context: Context): String? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val pushId = prefs.getString(KEY_LAST_PUSH_ID, null) ?: return null
            val at = prefs.getLong(KEY_LAST_PUSH_AT, 0L)
            if (at == 0L) return null
            if (System.currentTimeMillis() - at > WINDOW_MS) return null
            pushId
        } catch (_: Throwable) {
            null
        }
    }
}
