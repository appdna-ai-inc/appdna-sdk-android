package ai.appdna.sdk.integrations

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import ai.appdna.sdk.PushActionButton
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import org.json.JSONArray

/**
 * Custom push sounds. On Android 8+ a notification plays its CHANNEL's sound, never a per-notification
 * one, so every custom sound gets its own channel, `appdna_sound_<key>`, created the first time the SDK
 * sees the sound. The server names the same channel in `android.notification.channel_id`, so a push
 * FCM displays itself (app in the background) plays the sound once the channel exists; before that FCM
 * falls back to the app's default channel. The key is the `res/raw` resource name: the authored file
 * name lower-cased, extension dropped, anything outside `[a-z0-9_]` replaced by `_` (the server's
 * `androidSoundKey` is the same rule).
 */
internal object PushSoundChannels {
    fun soundKey(sound: String): String =
        sound.trim().replace(Regex("\\.[^./]*$"), "").lowercase().replace(Regex("[^a-z0-9_]"), "_")

    fun channelId(key: String): String = "appdna_sound_$key"

    /** Test seam: the `res/raw` lookup (a library's unit tests bundle no raw resource). */
    @Volatile
    internal var rawResourceId: (Context, String) -> Int = { context, key ->
        context.resources.getIdentifier(key, "raw", context.packageName)
    }

    /** The `res/raw` uri for [sound], or null when it is blank, `default`, or not bundled. */
    fun soundUri(context: Context, sound: String?): Uri? {
        if (sound.isNullOrBlank() || sound.trim() == "default") return null
        val key = soundKey(sound)
        val resId = rawResourceId(context, key)
        if (resId == 0) {
            Log.warning("Push sound '$sound' is not bundled as res/raw/$key — using the default sound")
            return null
        }
        return Uri.parse("android.resource://${context.packageName}/$resId")
    }

    /**
     * The channel to post [data] on when it names a bundled custom sound (created on Android 8+ if
     * missing), else null — the caller keeps its usual channel.
     */
    fun ensureChannel(context: Context, data: Map<String, String>): String? {
        val sound = data["sound"]
        val uri = soundUri(context, sound) ?: return null
        val id = channelId(soundKey(sound!!))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) createOreoPlus(context, id, uri, data)
        return id
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createOreoPlus(context: Context, id: String, uri: Uri, data: Map<String, String>) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(id) != null) return
        val importance = when (data["channel_importance"]?.lowercase()) {
            "default" -> NotificationManager.IMPORTANCE_DEFAULT
            "low" -> NotificationManager.IMPORTANCE_LOW
            "min" -> NotificationManager.IMPORTANCE_MIN
            else -> NotificationManager.IMPORTANCE_HIGH
        }
        val name = data["channel_name"] ?: "Push Notifications"
        val channel = NotificationChannel(id, "$name (${soundKey(data["sound"].orEmpty())})", importance).apply {
            data["channel_description"]?.let { description = it }
            setSound(
                uri,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        manager.createNotificationChannel(channel)
    }
}

/**
 * The action buttons of an AppDNA notification (`data.actions`, SDK shape
 * `{id, label, action_type, action_value, foreground}`), routed through the push-tap path:
 *   - a button that opens the app (every type except `dismiss`, unless `foreground` is false) launches
 *     the app with `action_id` + the button's `action_type` / `action_value`, and the host's
 *     `AppDNA.handlePushTap(intent)` tracks the tap, fires `onPushTapped(payload, actionId)` and routes
 *     the button's own action;
 *   - `dismiss`, and a `foreground: false` button, go to [PushActionReceiver]: the tap is tracked and
 *     `onPushTapped` fires without opening the app;
 *   - `text_reply` carries a [RemoteInput] (its value is the placeholder); the typed text reaches the
 *     host as `data["reply_text"]` in `onPushTapped`.
 * Either way the notification is removed (a button tap does not auto-cancel it).
 */
internal object PushNotificationActions {
    const val EXTRA_NOTIFICATION_ID = "appdna_notification_id"
    const val REPLY_KEY = "appdna_reply_text"
    const val REPLY_DATA_KEY = "reply_text"

    /** `foreground` per button id, from the raw `actions` JSON (the public button type has no field for it). */
    fun foregroundById(data: Map<String, String>): Map<String, Boolean> {
        val raw = data["actions"] ?: return emptyMap()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id", "")
                if (id.isEmpty() || !o.has("foreground")) null else id to o.optBoolean("foreground", true)
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Whether tapping [button] opens the app (else it is handled by [PushActionReceiver]). */
    fun opensApp(button: PushActionButton, foreground: Boolean?): Boolean =
        button.type != "dismiss" && foreground != false

    fun addTo(
        builder: NotificationCompat.Builder,
        context: Context,
        data: Map<String, String>,
        key: String,
        notificationId: Int,
        interpolate: (String) -> String,
    ) {
        val foreground = foregroundById(data)
        PushPayloadParser.parseActionButtons(data).forEach { button ->
            val opens = opensApp(button, foreground[button.id])
            val isReply = button.type == "text_reply"
            val intent = if (opens) {
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            } else {
                Intent(context, PushActionReceiver::class.java).apply {
                    action = PushActionReceiver.ACTION_PUSH_ACTION
                    // Unique per button, so two buttons' PendingIntents are never merged.
                    setData(Uri.parse("appdna://push/action/${Uri.encode(key)}/${Uri.encode(button.id)}"))
                }
            } ?: return@forEach
            intent.putExtra("push_id", data["push_id"].orEmpty())
            intent.putExtra("appdna", data["appdna"])
            intent.putExtra("delivery_id", data["delivery_id"])
            intent.putExtra("action_id", button.id)
            intent.putExtra("action_type", button.type)
            intent.putExtra("action_value", button.value)
            intent.putExtra(EXTRA_NOTIFICATION_ID, notificationId.toString())

            // A RemoteInput fills the intent in, so that one PendingIntent must be mutable (API 31+).
            val mutability = if (isReply && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_IMMUTABLE
            }
            val requestCode = "$key:${button.id}".hashCode()
            val pending = if (opens) {
                PendingIntent.getActivity(context, requestCode, intent, mutability or PendingIntent.FLAG_UPDATE_CURRENT)
            } else {
                PendingIntent.getBroadcast(context, requestCode, intent, mutability or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val iconResId = button.icon?.let { NotificationIconResolver.resolvePushActionIconRes(context, it) } ?: 0
            val actionBuilder = NotificationCompat.Action.Builder(iconResId, interpolate(button.label), pending)
            if (isReply) {
                actionBuilder.addRemoteInput(
                    RemoteInput.Builder(REPLY_KEY)
                        .setLabel(button.value?.takeIf { it.isNotBlank() } ?: interpolate(button.label))
                        .build(),
                )
                actionBuilder.setAllowGeneratedReplies(false)
            }
            builder.addAction(actionBuilder.build())
        }
    }

    /** The typed reply of a `text_reply` button, if [intent] carries one. */
    fun replyText(intent: Intent): String? = try {
        RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY_KEY)?.toString()
    } catch (_: Throwable) {
        null
    }

    /** Removes the notification a button of it was tapped on (button taps do not auto-cancel). */
    fun cancelFor(context: Context?, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_NOTIFICATION_ID)?.toIntOrNull() ?: return
        intent.removeExtra(EXTRA_NOTIFICATION_ID)
        try {
            context?.let { NotificationManagerCompat.from(it).cancel(id) }
        } catch (_: Throwable) { /* best-effort */ }
    }
}

/**
 * Handles the buttons that do not open the app (`dismiss`, `foreground: false`): removes the
 * notification, then hands the tap to `AppDNA.push.handleTapData` — tracked as `push_tapped` with the
 * button's id, `onPushTapped(payload, actionId)` fires (with `data["reply_text"]` for a reply). Declared
 * in the SDK manifest (not exported).
 */
class PushActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PUSH_ACTION) return
        PushNotificationActions.cancelFor(context, intent)
        val data = HashMap<String, String>()
        intent.extras?.keySet()?.forEach { k ->
            @Suppress("DEPRECATION")
            (intent.extras?.get(k) as? String)?.let { data[k] = it }
        }
        PushNotificationActions.replyText(intent)?.let { data[PushNotificationActions.REPLY_DATA_KEY] = it }
        try {
            AppDNA.push.handleTapData(data, data["action_id"])
        } catch (e: Throwable) {
            Log.warning("PushActionReceiver: handling the button tap failed: ${e.message}")
        }
    }

    companion object {
        const val ACTION_PUSH_ACTION = "ai.appdna.sdk.PUSH_ACTION"
    }
}
